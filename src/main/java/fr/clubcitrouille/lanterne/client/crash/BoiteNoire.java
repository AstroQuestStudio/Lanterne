package fr.clubcitrouille.lanterne.client.crash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.network.BoiteNoireTranche;

/**
 * La boîte noire, côté client : repère au moment de se connecter à un serveur Lanterne les
 * plantages (crash-reports, hs_err) qui n'ont encore jamais été envoyés, demande le consentement du
 * joueur une fois si la question n'a pas déjà de réponse, puis transmet un extrait tronqué et
 * anonymisé — voir {@link CrashSummary} — au serveur, à débit borné.
 *
 * <h2>Pourquoi la garde {@code Dist.CLIENT}</h2>
 *
 * <p>Cette classe nomme {@code Minecraft} et {@code ConfirmScreen}. Un serveur dédié ne doit JAMAIS la
 * charger — voir le piège connu de ce dépôt (une 131 a planté avec {@code NoClassDefFoundError:
 * Screen}). {@code @EventBusSubscriber(value = Dist.CLIENT)} garantit que FML ne charge cette classe
 * que sur un client, exactement comme {@code client.MecheClient} et {@code client.BouclierClient}.
 *
 * <h2>Le fichier d'état</h2>
 *
 * <p>Une seule ligne : l'horodatage (millisecondes) du dernier envoi. Tout fichier de plantage plus
 * récent que cette marque est considéré « non encore envoyé ». Au premier lancement de ce mécanisme,
 * la marque vaut zéro — ce qui signifie que les plantages DÉJÀ SUR LE DISQUE avant même l'installation
 * de cette version sont repérés eux aussi. C'est voulu : c'est exactement le cas qui a motivé ce
 * module — un joueur qui a déjà planté deux fois avant qu'on lui pose la question.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class BoiteNoire {
    /** Rapports envoyés au plus par connexion — le serveur applique de toute façon sa propre borne. */
    private static final int MAX_FICHIERS_PAR_CONNEXION = 3;

    /** Un paquet toutes les quatre ticks : ~5 par seconde, 8 Ko chacun — débit borné, pas une rafale. */
    private static final int TICKS_ENTRE_PAQUETS = 4;

    private static boolean askedThisSession;
    private static List<Path> pendingAsk;
    // Vrai si la question qui va s'afficher ne porte QUE sur les profils de performance — voir
    // onLoggingIn : cas où la question des plantages a déjà une réponse mais pas celle des profils.
    // Faux : question "combinée" (plantages + profils), posée quand ni l'une ni l'autre n'a encore
    // de réponse — voir BoiteNoireConsent pour pourquoi ce sont deux réglages séparés malgré une
    // question parfois commune.
    private static boolean profilsSeulementAsk;
    private static final Deque<BoiteNoireTranche> FILE_ATTENTE = new ArrayDeque<>();
    private static int cooldown;

    private BoiteNoire() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        askedThisSession = false; // une nouvelle connexion mérite une nouvelle chance de demander
        FILE_ATTENTE.clear();
        Minecraft client = Minecraft.getInstance();
        if (client.hasSingleplayerServer()) {
            return; // rien à prévenir : le « serveur » est cette même machine
        }
        List<Path> pending = findPending();
        // Voir la Javadoc de BoiteNoireConsent : deux questions distinctes (plantages, profils de
        // performance), mais une seule popup si aucune des deux n'a encore de réponse — pour ne pas
        // harceler le joueur de deux fenêtres à la suite au moment où il vient de se connecter.
        if (!BoiteNoireConsent.answered()) {
            pendingAsk = pending; // peut être vide : "0 plantage" est un cas valide de la question combinée
            profilsSeulementAsk = false;
            return; // posée au tick suivant, une fois l'écran de connexion refermé
        }
        if (BoiteNoireConsent.allowed() && !pending.isEmpty()) {
            sendAll(pending);
        }
        if (!BoiteNoireConsent.profilsAnswered()) {
            pendingAsk = List.of(); // la question des plantages a déjà sa réponse, rien à envoyer ici
            profilsSeulementAsk = true;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (pendingAsk != null && !askedThisSession && client.gui.screen() == null
                && client.player != null) {
            List<Path> toAsk = pendingAsk;
            boolean profilsSeulement = profilsSeulementAsk;
            pendingAsk = null;
            askedThisSession = true;
            if (profilsSeulement) {
                askProfilsSeulement();
            } else {
                ask(toAsk);
            }
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        BoiteNoireTranche next = FILE_ATTENTE.poll();
        if (next == null) {
            return;
        }
        cooldown = TICKS_ENTRE_PAQUETS;
        try {
            client.getConnection().send(next);
        } catch (RuntimeException clientOuServeurSansCanal) {
            // Voir la Javadoc de MecheNet : un serveur d'avant ce mécanisme n'a pas négocié ce canal.
            // On abandonne l'envoi en cours plutôt que de continuer à empiler des paquets qui
            // échoueront tous de la même façon.
            FILE_ATTENTE.clear();
        }
    }

    /**
     * La question combinée : posée quand ni les plantages ni les profils de performance n'ont encore
     * de réponse (le cas le plus fréquent, à la toute première connexion). Une seule popup, une seule
     * décision — voir la Javadoc de {@link BoiteNoireConsent} pour pourquoi ce sont malgré tout deux
     * réglages séparés une fois répondu (l'un peut changer d'avis dans les réglages sans l'autre).
     */
    private static void ask(List<Path> pending) {
        Minecraft client = Minecraft.getInstance();
        String serveur = client.getCurrentServer() != null ? client.getCurrentServer().ip : "ce serveur";
        String ligneDeplantage = pending.isEmpty()
                ? "Cette machine n'a aucun plantage en attente.\n\n"
                : "Cette machine a gardé " + pending.size()
                        + (pending.size() == 1 ? " plantage" : " plantages") + " non encore envoyé"
                        + (pending.size() == 1 ? "" : "s") + ".\n\n";
        client.gui.setScreen(new ConfirmScreen(yes -> {
            BoiteNoireConsent.setAllowed(yes);
            BoiteNoireConsent.setProfilsAllowed(yes);
            client.gui.setScreen(null);
            if (yes && !pending.isEmpty()) {
                sendAll(pending);
            }
        }, Component.literal("Envoyer tes rapports de plantage et de performance ?"),
                Component.literal(ligneDeplantage
                        + "Oui : un extrait tronqué de chaque plantage (cause, mods suspectés, quelques "
                        + "lignes de pile) part vers les admins de " + serveur + ", et un profil de "
                        + "performance détaillé (FPS, méthodes chaudes, pauses du ramasse-miettes) partira "
                        + "aussi automatiquement toutes les ~60 minutes de jeu — les chemins de ton disque "
                        + "sont anonymisés avant l'envoi.\nNon : rien ne part ; tu pourras changer d'avis "
                        + "séparément pour chacun dans les réglages de Lanterne."),
                Component.literal("Oui, envoyer"),
                Component.literal("Non")));
    }

    /**
     * La question dédiée aux profils de performance seuls : posée quand la question des plantages a
     * déjà une réponse (typiquement, un joueur déjà présent avant que ce mécanisme des profils
     * n'existe) mais que celle des profils n'en a pas encore. Ne touche jamais au consentement des
     * plantages, déjà tranché.
     */
    private static void askProfilsSeulement() {
        Minecraft client = Minecraft.getInstance();
        String serveur = client.getCurrentServer() != null ? client.getCurrentServer().ip : "ce serveur";
        client.gui.setScreen(new ConfirmScreen(yes -> {
            BoiteNoireConsent.setProfilsAllowed(yes);
            client.gui.setScreen(null);
        }, Component.literal("Envoyer tes profils de performance ?"),
                Component.literal("Lanterne peut mesurer précisément où passe le temps sur cette machine "
                        + "(méthodes chaudes, pauses du ramasse-miettes, FPS) et envoyer un rapport détaillé "
                        + "aux admins de " + serveur + " toutes les ~60 minutes de jeu, pour trouver où le "
                        + "jeu coûte cher.\n\nOui : un rapport (~200 Ko, sans chemin de disque ni nom de "
                        + "compte) part automatiquement. Non : rien ne part ; une copie reste quand même "
                        + "sur cette machine, dans config/lanterne/profils/."),
                Component.literal("Oui, envoyer"),
                Component.literal("Non")));
    }

    /** Construit et met en file les paquets des {@code MAX_FICHIERS_PAR_CONNEXION} plus anciens plantages. */
    private static void sendAll(List<Path> pending) {
        List<Path> retenus = pending.stream()
                .sorted(Comparator.comparing(BoiteNoire::mtimeQuiet))
                .limit(MAX_FICHIERS_PAR_CONNEXION)
                .toList();
        long marque = 0L;
        for (Path file : retenus) {
            try {
                queue(file);
                marque = Math.max(marque, mtimeQuiet(file));
            } catch (IOException echec) {
                Lanterne.LOG.warn("[BOITE-NOIRE] {} : lecture impossible, non envoyé.", file);
            }
        }
        if (marque > 0L) {
            saveMark(marque);
        }
    }

    private static void queue(Path file) throws IOException {
        boolean hsErr = file.getFileName().toString().startsWith("hs_err_pid");
        String texte = hsErr ? CrashSummary.summarizeHsErr(file) : CrashSummary.summarizeCrashReport(file);
        byte[] bytes = texte.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > BoiteNoireTranche.MAX_TOTAL_BYTES) {
            byte[] tronque = new byte[BoiteNoireTranche.MAX_TOTAL_BYTES];
            System.arraycopy(bytes, 0, tronque, 0, tronque.length);
            bytes = tronque;
        }
        String nom = file.getFileName().toString();
        if (nom.length() > BoiteNoireTranche.NOM_LIMIT) {
            nom = nom.substring(0, BoiteNoireTranche.NOM_LIMIT);
        }
        int total = bytes.length;
        int offset = 0;
        while (offset < total) {
            int taille = Math.min(BoiteNoireTranche.MAX_CHUNK_BYTES, total - offset);
            byte[] morceau = new byte[taille];
            System.arraycopy(bytes, offset, morceau, 0, taille);
            FILE_ATTENTE.add(new BoiteNoireTranche(nom, offset, total, morceau));
            offset += taille;
        }
        if (total == 0) {
            // Fichier vide après résumé (improbable, mais un total de zéro ne formerait jamais de
            // tranche ci-dessus, et le serveur n'aurait donc jamais de quoi clore le rapport) : une
            // seule tranche vide suffit à annoncer un rapport de longueur nulle.
            FILE_ATTENTE.add(new BoiteNoireTranche(nom, 0, 0, new byte[0]));
        }
    }

    /** Les fichiers de plantage plus récents que la marque de dernier envoi. */
    private static List<Path> findPending() {
        long marque = loadMark();
        List<Path> found = new ArrayList<>();
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path crashReports = gameDir.resolve("crash-reports");
        if (Files.isDirectory(crashReports)) {
            try (Stream<Path> listing = Files.list(crashReports)) {
                listing.filter(p -> p.getFileName().toString().startsWith("crash-")
                                && p.getFileName().toString().endsWith(".txt"))
                        .filter(p -> mtimeQuiet(p) > marque)
                        .forEach(found::add);
            } catch (IOException ignored) {
                // dossier illisible : on se contente de ce qu'on a déjà trouvé
            }
        }
        try (Stream<Path> listing = Files.list(gameDir)) {
            listing.filter(p -> p.getFileName().toString().startsWith("hs_err_pid")
                            && p.getFileName().toString().endsWith(".log"))
                    .filter(p -> mtimeQuiet(p) > marque)
                    .forEach(found::add);
        } catch (IOException ignored) {
            // racine illisible : improbable, mais pas fatal
        }
        return found;
    }

    private static long mtimeQuiet(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException echec) {
            return 0L;
        }
    }

    private static Path stateFile() {
        return FMLPaths.CONFIGDIR.get().resolve("lanterne-boite-noire-envoyes.txt");
    }

    private static long loadMark() {
        try {
            List<String> lines = Files.readAllLines(stateFile(), StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                return Long.parseLong(lines.get(0).trim());
            }
        } catch (IOException | NumberFormatException ignored) {
            // pas de fichier encore, ou contenu inattendu : tout compte comme « non envoyé »
        }
        return 0L;
    }

    private static void saveMark(long millis) {
        try {
            Files.createDirectories(stateFile().getParent());
            Files.writeString(stateFile(), Long.toString(millis), StandardCharsets.UTF_8);
        } catch (IOException echec) {
            Lanterne.LOG.warn("[BOITE-NOIRE] état non enregistré : les mêmes plantages seront "
                    + "reproposés à la prochaine connexion.");
        }
    }
}
