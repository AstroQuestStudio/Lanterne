package fr.clubcitrouille.lanterne.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Meche;
import fr.clubcitrouille.lanterne.core.MecheRegistres;
import fr.clubcitrouille.lanterne.core.network.MecheConfigPaquets;
import fr.clubcitrouille.lanterne.core.network.MecheManifest;

/**
 * Le côté client de la Mèche de CONFIGURATION — voir {@code core.MecheConfig} pour le mécanisme et
 * l'ordre des tâches.
 *
 * <h2>Ce qui se passe, selon les cas</h2>
 *
 * <ul>
 *   <li>Même empreinte : on répond « continuer », rien d'autre.</li>
 *   <li>Empreinte différente, consentement donné ({@link MecheConsent}) : on demande le jar, on le
 *       reçoit par tranches PENDANT la configuration, on vérifie l'empreinte, on l'écrit dans
 *       {@code mods/} (même nom et même nettoyage différé de l'ancien jar que {@link MecheClient}).
 *       Puis : si toutes les clés Lanterne du serveur existent déjà ici, on continue — le joueur
 *       entre et relancera quand il voudra ; sinon on se déconnecte proprement : « Lanterne mis à
 *       jour (X → Y). Relance le jeu pour rejoindre. ».</li>
 *   <li>Empreinte différente, pas de consentement : on entre si les registres le permettent (avec un
 *       message dans la discussion) ; sinon on se déconnecte avec un message qui dit quoi faire —
 *       jamais le « unknown keys » brut.</li>
 * </ul>
 *
 * <p>Tout ce qui touche au disque se fait sur l'atelier de {@link MecheClient}, jamais sur le fil du
 * jeu : le fil du jeu doit continuer de répondre aux maintiens de connexion du serveur.
 */
public final class MecheConfigClient {
    /** Même plafond que {@link MecheClient} : pas d'allocation absurde sur la foi d'une annonce. */
    private static final int PLAFOND = 256 * 1024 * 1024;

    private static Reception reception;

    private static final class Reception {
        private final IPayloadContext context;
        private final String versionLocale;
        private final String version;
        private final byte[] empreinte;
        private final byte[] tampon;
        private final boolean compatible;
        private final Path ancien;
        private int recus;

        private Reception(IPayloadContext context, String versionLocale, MecheConfigPaquets.Annonce annonce,
                boolean compatible, Path ancien) {
            this.context = context;
            this.versionLocale = versionLocale;
            this.version = annonce.version();
            this.empreinte = annonce.sha256();
            this.tampon = new byte[annonce.taille()];
            this.compatible = compatible;
            this.ancien = ancien;
        }
    }

    private MecheConfigClient() {}

    /** L'annonce du serveur — fil du jeu. */
    public static void annonce(MecheConfigPaquets.Annonce annonce, IPayloadContext context) {
        reception = null;
        Meche.JarInfo local = Meche.describeOwnJar(Lanterne.ID);
        if (local == null) {
            continuer(context); // développement : pas de jar à comparer
            return;
        }
        if (Arrays.equals(local.sha256(), annonce.sha256())) {
            Lanterne.LOG.info("[MÈCHE] configuration : Lanterne à jour ({}).", local.version());
            continuer(context);
            return;
        }
        String version = annonce.version();
        if (!MecheClient.safe(version) || annonce.sha256().length != MecheManifest.HASH_LENGTH
                || annonce.taille() <= 0 || annonce.taille() > PLAFOND) {
            Lanterne.LOG.warn("[MÈCHE] configuration : annonce rejetée (version ou taille suspectes).");
            continuer(context);
            return;
        }
        boolean compatible = MecheRegistres.toutesPresentes(annonce.cles());
        Lanterne.LOG.info("[MÈCHE] configuration : le serveur fait tourner Lanterne {}, cette machine {} "
                + "(registres {}).", version, local.version(), compatible ? "compatibles" : "INCOMPATIBLES");

        Path cible = FMLPaths.MODSDIR.get().resolve(MecheClient.fileName(Lanterne.ID, version));
        Reception r = new Reception(context, local.version(), annonce, compatible, local.path());
        if (dejaPose(cible, annonce.sha256())) {
            Lanterne.LOG.info("[MÈCHE] configuration : {} déjà téléchargé et vérifié dans mods/.", cible.getFileName());
            MecheClient.atelier(() -> conclure(r));
            return;
        }
        if (!MecheConsent.autoUpdate()) {
            if (compatible) {
                MecheClient.direApresConnexion(Component.literal("Lanterne " + version + " est disponible "
                        + "sur ce serveur (tu as " + local.version() + "). Active « synchro_auto » dans "
                        + "config/lanterne-maj-client.toml pour l'installer automatiquement.")
                        .withStyle(ChatFormatting.YELLOW));
                continuer(context);
            } else {
                context.disconnect(Component.literal("Ce serveur utilise Lanterne " + version + " (tu as "
                        + local.version() + "), avec des blocs ou objets que ta version ne connaît pas : "
                        + "impossible d'entrer sans mise à jour.\n\n"
                        + "La mise à jour automatique est coupée chez toi. Mets synchro_auto = true dans "
                        + "config/lanterne-maj-client.toml puis reconnecte-toi, ou installe lanterne-"
                        + version + ".jar dans mods/ (en retirant l'ancien) et relance le jeu."));
            }
            return;
        }
        reception = r;
        context.reply(new MecheConfigPaquets.Reponse(MecheConfigPaquets.Reponse.DEMANDE));
        Lanterne.LOG.info("[MÈCHE] configuration : téléchargement de Lanterne {} demandé ({} octets).",
                version, annonce.taille());
    }

    /** Une tranche — fil du jeu. */
    public static void tranche(MecheConfigPaquets.Tranche tranche, IPayloadContext context) {
        Reception r = reception;
        if (r == null) {
            return;
        }
        if (tranche.taille() != r.tampon.length || tranche.offset() < 0 || tranche.data().length == 0
                || tranche.offset() + tranche.data().length > r.tampon.length) {
            reception = null;
            echec(r, "tranche incohérente");
            return;
        }
        System.arraycopy(tranche.data(), 0, r.tampon, tranche.offset(), tranche.data().length);
        r.recus += tranche.data().length;
        if (r.recus < r.tampon.length) {
            return;
        }
        reception = null;
        MecheClient.atelier(() -> installer(r));
    }

    /** Atelier : vérifier, écrire, puis conclure. */
    private static void installer(Reception r) {
        if (!Arrays.equals(Meche.sha256(r.tampon), r.empreinte)) {
            echec(r, "empreinte différente de celle annoncée (corrompu en transit)");
            return;
        }
        Path mods = FMLPaths.MODSDIR.get();
        Path cible = mods.resolve(MecheClient.fileName(Lanterne.ID, r.version));
        Path partiel = mods.resolve(cible.getFileName() + ".part");
        try {
            Files.createDirectories(mods);
            Files.write(partiel, r.tampon);
            Files.move(partiel, cible, StandardCopyOption.REPLACE_EXISTING);
            Lanterne.LOG.info("[MÈCHE] configuration : Lanterne {} écrit et vérifié : {}", r.version, cible);
        } catch (Exception ecritureRatee) {
            try {
                Files.deleteIfExists(partiel);
            } catch (Exception ignore) {
                // un .part n'est jamais chargé par NeoForge
            }
            echec(r, "écriture dans mods/ impossible : " + ecritureRatee.getMessage());
            return;
        }
        conclure(r);
    }

    /** Atelier : retirer l'ancien jar (au besoin en différé), puis continuer ou se déconnecter. */
    private static void conclure(Reception r) {
        Path ancien = r.ancien;
        boolean retire = ancien != null && !ancien.getFileName().toString()
                .equals(MecheClient.fileName(Lanterne.ID, r.version)) && MecheClient.tryRemove(ancien);
        boolean differe = !retire && ancien != null && MecheClient.scheduleDeferredRemoval(ancien);
        String consigne = retire || differe ? ""
                : "\n\nAVANT de relancer : supprime l'ancien fichier "
                        + (ancien == null ? "lanterne-….jar" : ancien.getFileName().toString())
                        + " de ton dossier mods/ (garde lanterne-" + r.version + ".jar).";
        Minecraft.getInstance().execute(() -> {
            if (r.compatible) {
                MecheClient.direApresConnexion(Component.literal("Lanterne " + r.version
                        + " téléchargé et vérifié. Il s'appliquera au prochain lancement du jeu." + consigne)
                        .withStyle(ChatFormatting.GREEN));
                continuer(r.context);
            } else {
                r.context.disconnect(Component.literal("Lanterne mis à jour (" + r.versionLocale + " → "
                        + r.version + ").\n\nRelance le jeu pour rejoindre." + consigne));
            }
        });
    }

    private static void echec(Reception r, String raison) {
        Lanterne.LOG.warn("[MÈCHE] configuration : téléchargement de Lanterne {} raté : {}", r.version, raison);
        Minecraft.getInstance().execute(() -> {
            if (r.compatible) {
                MecheClient.direApresConnexion(Component.literal("Téléchargement de Lanterne " + r.version
                        + " raté (" + raison + "). Reconnecte-toi pour réessayer.")
                        .withStyle(ChatFormatting.RED));
                continuer(r.context);
            } else {
                r.context.disconnect(Component.literal("Ce serveur utilise Lanterne " + r.version
                        + " (tu as " + r.versionLocale + ") et son téléchargement a échoué : " + raison
                        + ".\n\nReconnecte-toi pour réessayer, ou installe lanterne-" + r.version
                        + ".jar dans mods/ à la main."));
            }
        });
    }

    private static void continuer(IPayloadContext context) {
        try {
            context.reply(new MecheConfigPaquets.Reponse(MecheConfigPaquets.Reponse.CONTINUER));
        } catch (RuntimeException deconnecte) {
            // La connexion est déjà fermée : rien à continuer.
        }
    }

    private static boolean dejaPose(Path cible, byte[] empreinte) {
        if (!Files.isRegularFile(cible)) {
            return false;
        }
        try {
            return Arrays.equals(Meche.sha256(cible), empreinte);
        } catch (Exception illisible) {
            return false;
        }
    }
}
