package fr.clubcitrouille.lanterne.core;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import net.minecraft.server.level.ServerPlayer;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.network.BoiteNoireTranche;

/**
 * Le côté serveur de la Boîte noire : réassemble les tranches envoyées par
 * {@code client.crash.BoiteNoire}, et écrit le rapport complet dans
 * {@code logs/lanterne-plantages/<joueur>-<horodatage>.txt}.
 *
 * <h2>Pourquoi cette classe ne nomme aucune classe client</h2>
 *
 * <p>Voir la Javadoc de {@code core.network.BoiteNoireNet} : c'est ce qui permet à ce module d'être
 * chargé sans risque sur un serveur dédié, alors même que l'idée « boîte noire » n'existe que pour lui
 * envoyer des rapports produits côté client.
 *
 * <h2>Un client modifié n'est jamais cru sur parole</h2>
 *
 * <p>{@code MAX_TOTAL_BYTES} et {@code MAX_REPORTS_PAR_SESSION} sont revérifiés ici, pas seulement du
 * côté du client honnête qui a écrit la Javadoc de {@link BoiteNoireTranche}. Un client qui mentirait
 * sur la taille annoncée, ou qui enverrait des dizaines de rapports d'affilée, est simplement coupé :
 * aucune exception ne remonte, aucun message n'informe l'attaquant de ce qui a été détecté.
 */
public final class BoiteNoirePlantages {
    /** Rapports acceptés par joueur et par connexion — au-delà, les tranches suivantes sont ignorées. */
    private static final int MAX_REPORTS_PAR_SESSION = 5;

    private static final Pattern NOM_SUR = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final DateTimeFormatter HORODATAGE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final class EnCours {
        final String nomFichier;
        final int total;
        final ByteArrayOutputStream tampon = new ByteArrayOutputStream();

        EnCours(String nomFichier, int total) {
            this.nomFichier = nomFichier;
            this.total = total;
        }
    }

    private static final Map<UUID, EnCours> BUFFERS = new HashMap<>();
    private static final Map<UUID, Integer> COMPTES = new HashMap<>();

    private BoiteNoirePlantages() {}

    /** Reçoit une tranche, l'ajoute au rapport en cours pour ce joueur, et l'écrit une fois complet. */
    public static synchronized void onChunk(ServerPlayer player, BoiteNoireTranche tranche) {
        UUID id = player.getUUID();
        if (tranche.total() <= 0 || tranche.total() > BoiteNoireTranche.MAX_TOTAL_BYTES) {
            Lanterne.LOG.warn("[BOITE-NOIRE] {} : taille annoncée refusée ({} octets), tranche ignorée.",
                    player.getGameProfile().name(), tranche.total());
            BUFFERS.remove(id);
            return;
        }
        int comptees = COMPTES.getOrDefault(id, 0);
        if (comptees >= MAX_REPORTS_PAR_SESSION) {
            return; // plafond anti-abus atteint pour cette connexion : silence, pas de refus bruyant
        }

        EnCours enCours = BUFFERS.get(id);
        // Un décalage à zéro démarre toujours un nouveau rapport — même si le précédent était
        // incomplet, l'ancien est simplement abandonné plutôt que mélangé au nouveau.
        if (tranche.offset() == 0 || enCours == null) {
            enCours = new EnCours(tranche.nomFichier(), tranche.total());
            BUFFERS.put(id, enCours);
        }
        if (tranche.offset() != enCours.tampon.size() || tranche.total() != enCours.total) {
            // Tranche hors ordre ou rapport changé en cours de route : on abandonne plutôt que de
            // deviner un recollage incorrect.
            BUFFERS.remove(id);
            return;
        }
        if (enCours.tampon.size() + tranche.data().length > enCours.total) {
            BUFFERS.remove(id);
            return;
        }
        enCours.tampon.writeBytes(tranche.data());
        if (enCours.tampon.size() >= enCours.total) {
            BUFFERS.remove(id);
            ecrireRapport(player, enCours.tampon.toByteArray());
            COMPTES.put(id, comptees + 1);
        }
    }

    private static void ecrireRapport(ServerPlayer player, byte[] contenu) {
        var server = player.level().getServer();
        Path dossier = server.getServerDirectory().resolve("logs").resolve("lanterne-plantages");
        String nomJoueur = sanitize(player.getGameProfile().name());
        String fichier = nomJoueur + "-" + LocalDateTime.now().format(HORODATAGE) + ".txt";
        Path cible = dossier.resolve(fichier);
        try {
            Files.createDirectories(dossier);
            Files.write(cible, contenu);
            Lanterne.LOG.info("[BOITE-NOIRE] {} : rapport de plantage reçu ({} octets) -> {}",
                    player.getGameProfile().name(), contenu.length, cible);
        } catch (IOException echec) {
            Lanterne.LOG.warn("[BOITE-NOIRE] {} : écriture impossible pour {}.",
                    player.getGameProfile().name(), cible);
        }
    }

    private static String sanitize(String nom) {
        return NOM_SUR.matcher(nom).matches() ? nom : nom.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** Oublie l'état d'un joueur qui vient de partir — un rapport interrompu n'est jamais recollé. */
    public static synchronized void forget(UUID id) {
        BUFFERS.remove(id);
        COMPTES.remove(id);
    }
}
