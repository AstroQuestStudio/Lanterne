package fr.clubcitrouille.lanterne.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import net.minecraft.server.level.ServerPlayer;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.network.ProfilTranche;

/**
 * Le côté serveur des profils de performance envoyés par les joueurs : réassemble les tranches de
 * {@link ProfilTranche} et écrit le rapport complet dans
 * {@code logs/lanterne-profils/<joueur>-<horodatage>.txt}.
 *
 * <h2>Même charpente que {@code BoiteNoirePlantages}, mêmes garanties</h2>
 *
 * <p>Réassemblage par décalage attendu, abandon silencieux sur toute incohérence, revérification de la
 * taille annoncée — voir sa Javadoc pour le détail, dupliqué ici sciemment (un profil et un plantage ne
 * partagent ni leur taille, ni leur plafond, ni leur nom de fichier de destination) plutôt que factorisé
 * dans une classe générique qui aurait dû connaître les deux plafonds à la fois pour rien.
 *
 * <h2>Le quota supplémentaire : par JOUR, pas seulement par connexion</h2>
 *
 * <p>Un rapport de plantage est un évènement rare par nature (un joueur ne plante pas dix fois par
 * heure, ou alors le vrai problème est ailleurs). Un profil de performance, lui, est PÉRIODIQUE — une
 * rotation toutes les ~60 minutes, prévue pour durer toute une session de jeu. Un plafond par connexion
 * seul laisserait un client modifié rester connecté et ouvrir/fermer des connexions pour repartir de
 * zéro ; {@link #MAX_RAPPORTS_PAR_JOUR} borne le total réel, à la journée, quel que soit le nombre de
 * connexions.
 */
public final class ProfilReception {
    /** Rapports acceptés par joueur et par connexion — au-delà, les tranches suivantes sont ignorées. */
    private static final int MAX_RAPPORTS_PAR_SESSION = 30;

    /** Rapports acceptés par joueur et par jour calendaire (heure du serveur) — le vrai garde-fou. */
    private static final int MAX_RAPPORTS_PAR_JOUR = 30;

    private static final Pattern NOM_SUR = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final DateTimeFormatter HORODATAGE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final class EnCours {
        final int total;
        final ByteArrayOutputStream tampon = new ByteArrayOutputStream();

        EnCours(int total) {
            this.total = total;
        }
    }

    private static final class Quota {
        LocalDate jour = LocalDate.now();
        int comptesAujourdhui;
        int comptesSession;
    }

    private static final Map<UUID, EnCours> BUFFERS = new HashMap<>();
    private static final Map<UUID, Quota> QUOTAS = new HashMap<>();

    private ProfilReception() {}

    /** Reçoit une tranche, l'ajoute au rapport en cours pour ce joueur, et l'écrit une fois complet. */
    public static synchronized void onChunk(ServerPlayer player, ProfilTranche tranche) {
        UUID id = player.getUUID();
        if (tranche.total() <= 0 || tranche.total() > ProfilTranche.MAX_TOTAL_BYTES) {
            Lanterne.LOG.warn("[PROFIL] {} : taille annoncée refusée ({} octets), tranche ignorée.",
                    player.getGameProfile().name(), tranche.total());
            BUFFERS.remove(id);
            return;
        }
        Quota quota = QUOTAS.computeIfAbsent(id, k -> new Quota());
        LocalDate aujourdhui = LocalDate.now();
        if (!aujourdhui.equals(quota.jour)) {
            quota.jour = aujourdhui;
            quota.comptesAujourdhui = 0;
        }
        if (quota.comptesSession >= MAX_RAPPORTS_PAR_SESSION || quota.comptesAujourdhui >= MAX_RAPPORTS_PAR_JOUR) {
            return; // plafond anti-abus atteint : silence, pas de refus bruyant (même choix que la Boîte noire)
        }

        EnCours enCours = BUFFERS.get(id);
        if (tranche.offset() == 0 || enCours == null) {
            enCours = new EnCours(tranche.total());
            BUFFERS.put(id, enCours);
        }
        if (tranche.offset() != enCours.tampon.size() || tranche.total() != enCours.total) {
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
            quota.comptesSession++;
            quota.comptesAujourdhui++;
        }
    }

    private static void ecrireRapport(ServerPlayer player, byte[] contenu) {
        var server = player.level().getServer();
        Path dossier = server.getServerDirectory().resolve("logs").resolve("lanterne-profils");
        String nomJoueur = sanitize(player.getGameProfile().name());
        String fichier = nomJoueur + "-" + LocalDateTime.now().format(HORODATAGE) + ".txt";
        Path cible = dossier.resolve(fichier);
        try {
            Files.createDirectories(dossier);
            Files.write(cible, contenu);
            Lanterne.LOG.info("[PROFIL] {} : profil de performance reçu ({} octets) -> {}",
                    player.getGameProfile().name(), contenu.length, cible);
        } catch (IOException echec) {
            Lanterne.LOG.warn("[PROFIL] {} : écriture impossible pour {}.",
                    player.getGameProfile().name(), cible);
        }
    }

    private static String sanitize(String nom) {
        return NOM_SUR.matcher(nom).matches() ? nom : nom.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** Oublie l'état d'un joueur qui vient de partir — un rapport interrompu n'est jamais recollé. */
    public static synchronized void forget(UUID id) {
        BUFFERS.remove(id);
        Quota quota = QUOTAS.get(id);
        if (quota != null) {
            quota.comptesSession = 0; // seul le compte SESSION retombe ; le compte du JOUR survit à la déconnexion
        }
    }
}
