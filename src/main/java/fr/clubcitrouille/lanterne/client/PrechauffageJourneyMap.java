package fr.clubcitrouille.lanterne.client;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.NativeImage;

import net.neoforged.fml.loading.FMLPaths;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.report.Compagnons;

/**
 * Préchauffe, sur un fil de fond démarré bien avant qu'un joueur ne se connecte, les fichiers que
 * JourneyMap relira ensuite SUR LE FIL PRINCIPAL au moment de la connexion — pour que ces lectures
 * trouvent le cache disque de l'OS déjà chaud plutôt que de payer un vrai accès disque au pire moment.
 *
 * <h2>Le gel mesuré, et ce qui en est traité ici</h2>
 *
 * <p>Un profil client montre un gel de 1,3 s à la connexion, dont trois contributeurs identifiés :
 * {@code JourneymapClient.loadConfigProperties} (huit fichiers de config lus sur le fil principal,
 * pendant le tick client), {@code MobIconCache.ensureMobIconsLoaded} → {@code FileHandler
 * .getImageFromFile} (icônes de mob relues depuis le disque au premier rendu de la minicarte), et
 * {@code Climate$RTree.build} (vanilla). Les deux premiers sont traités ici ; voir plus bas pourquoi
 * le troisième ne l'est pas.
 *
 * <h2>Pourquoi un préchauffage disque, et pas un appel anticipé au vrai code de JourneyMap</h2>
 *
 * <p>{@code MobIconCache.ensureMobIconsLoaded()} remplit un {@code HashMap} STATIQUE non
 * synchronisé ({@code mobsIcons}) — l'appeler depuis un fil séparé pendant qu'une vraie connexion le
 * remplit déjà en parallèle sur le fil principal corromprait cette table sans avertissement (deux
 * écritures concurrentes sur un {@code HashMap} ordinaire peuvent perdre des entrées ou boucler
 * indéfiniment). Pire : la voie « icône personnalisée » de {@code MobIconCache.getMobIcon} vérifie
 * explicitement {@code RenderSystem.isOnRenderThread()} avant de créer une texture — la création de
 * texture GL n'est valide que sur le fil de rendu, jamais un fil de fond. Reproduit par lecture du
 * bytecode réel ({@code journeymap-neoforge-26.1.2-6.0.7.jar}, {@code javap -c}).
 *
 * <p>Ce fichier-ci ne touche donc JAMAIS l'état interne de JourneyMap. Il se contente de :
 * <ul>
 *   <li>lire les octets des huit fichiers de config JourneyMap (même chemin que {@link Eclaireur},
 *       voir sa Javadoc de classe pour la preuve du sous-dossier {@code 6.0}) — une lecture qui ne
 *       fait rien d'autre que peupler le cache disque de l'OS, le résultat est jeté ;</li>
 *   <li>appeler {@code FileHandler.getMobIconsDomainsDirectories()} puis {@code FileHandler
 *       .getImageFromFile(File)} — les DEUX méthodes exactement nommées par le profileur, mais
 *       PUBLIQUES, STATIQUES et sans aucun effet de bord sur {@code mobsIcons} (vérifié en bytecode :
 *       la première ne fait que {@code File.listFiles}, la seconde décode un flux en
 *       {@link NativeImage} sans toucher OpenGL ni au cache de {@code MobIconCache}). Le
 *       {@link NativeImage} obtenu est fermé aussitôt : seul le passage par le disque et le
 *       décodeur PNG est préchauffé, rien n'est conservé.</li>
 * </ul>
 *
 * <h2>Pourquoi {@code Climate$RTree.build} (vanilla) n'a rien ici</h2>
 *
 * <p>Cet arbre dépend des paramètres de biomes du MONDE REÇU à la connexion — datapack du serveur
 * inclus — qui n'existent tout simplement pas avant la poignée de main. Rien ne peut être précalculé
 * à l'écran-titre sans déjà connaître le monde, et court-circuiter l'ordre de chargement vanilla pour
 * gagner ces millisecondes exigerait un mixin intrusif dans une séquence que Lanterne ne possède pas
 * — hors du seuil de sûreté fixé ici.
 *
 * <p>Réflexion et non dépendance de compilation : {@code journeymap.client.io.FileHandler} n'est pas
 * une API publique embarquée (même situation que {@code mezz.jei.common.Internal}, voir
 * {@code compat.RecettesEmbarquees.jeiSynchronise}). Un échec — mod absent, méthode renommée à une
 * future version — est silencieux et ne prive le joueur que de ce préchauffage, jamais du jeu :
 * JourneyMap referait alors ces lectures lui-même, exactement comme sans Lanterne.
 */
public final class PrechauffageJourneyMap {
    /** Même sous-dossier de version que {@link Eclaireur} — voir sa Javadoc pour sa composition réelle. */
    private static final String CONFIG_DIR = "journeymap/config/6.0";

    /** Les huit fichiers que {@code CoreProperties}/{@code PropertiesBase} lisent au démarrage du suivi. */
    private static final String[] FICHIERS_CONFIG = {
            "core", "fullmap", "minimap", "minimap2", "rendering", "topo", "waypoint", "webmap"
    };

    private PrechauffageJourneyMap() {}

    /**
     * Démarre le préchauffage en tâche de fond. Sans effet si JourneyMap n'est pas chargé.
     *
     * <p>À appeler une seule fois, tôt dans la construction du mod côté client — même fenêtre que
     * {@link Reveil#install()}/{@link Eclaireur#install()}, largement avant qu'un joueur puisse
     * cliquer sur « Rejoindre le serveur ». Le fil est un daemon de priorité minimale : il ne dispute
     * rien au fil principal, il gagne juste la course contre une connexion très rapide.
     */
    public static void install() {
        if (!Compagnons.present(Compagnons.JOURNEYMAP)) {
            return;
        }
        Path gameDir = FMLPaths.GAMEDIR.get();
        Thread fil = new Thread(() -> rechauffer(gameDir), "lanterne-prechauffage-journeymap");
        fil.setDaemon(true);
        fil.setPriority(Thread.MIN_PRIORITY);
        fil.start();
    }

    private static void rechauffer(Path gameDir) {
        int fichiers = rechaufferConfig(gameDir);
        int icones = rechaufferIconesMob();
        Lanterne.LOG.info("[PRÉCHAUFFAGE] JourneyMap : {} fichier(s) de config et {} icône(s) de mob "
                + "préchauffés en tâche de fond, avant toute connexion.", fichiers, icones);
    }

    private static int rechaufferConfig(Path gameDir) {
        int lus = 0;
        for (String nom : FICHIERS_CONFIG) {
            Path fichier = gameDir.resolve(CONFIG_DIR).resolve("journeymap." + nom + ".config");
            try {
                if (Files.exists(fichier)) {
                    Files.readAllBytes(fichier); // Résultat jeté : seul le cache disque de l'OS compte.
                    lus++;
                }
            } catch (Exception ignorable) {
                // Un fichier récalcitrant ne doit jamais empêcher le préchauffage des autres, ni du jeu.
            }
        }
        return lus;
    }

    /**
     * Voir la Javadoc de classe : deux méthodes publiques, statiques, sans effet de bord sur le
     * cache interne de JourneyMap — jamais {@code ensureMobIconsLoaded} lui-même.
     */
    private static int rechaufferIconesMob() {
        try {
            Class<?> fileHandler = Class.forName("journeymap.client.io.FileHandler", false,
                    PrechauffageJourneyMap.class.getClassLoader());
            Method domaines = fileHandler.getMethod("getMobIconsDomainsDirectories");
            Method imageDepuisFichier = fileHandler.getMethod("getImageFromFile", File.class);
            Object resultat = domaines.invoke(null);
            if (!(resultat instanceof File[] dossiers)) {
                return 0;
            }
            int icones = 0;
            for (File dossier : dossiers) {
                if (dossier == null || !dossier.isDirectory()) {
                    continue;
                }
                try (Stream<Path> chemins = Files.walk(dossier.toPath())) {
                    for (Path chemin : chemins.filter(p -> p.toString().endsWith(".png")).toList()) {
                        try {
                            Object image = imageDepuisFichier.invoke(null, chemin.toFile());
                            if (image instanceof NativeImage nativeImage) {
                                nativeImage.close(); // Seul le passage disque+décodeur est préchauffé.
                                icones++;
                            }
                        } catch (Exception uneIcone) {
                            // Une icône récalcitrante n'empêche jamais les suivantes.
                        }
                    }
                } catch (Exception dossierIllisible) {
                    // Un dossier illisible n'empêche jamais les autres domaines.
                }
            }
            return icones;
        } catch (Throwable indisponible) {
            // Méthode absente/renommée à une future version de JourneyMap : silencieux, voir Javadoc
            // de classe — JourneyMap referait alors ces lectures lui-même, comme sans Lanterne.
            return 0;
        }
    }
}
