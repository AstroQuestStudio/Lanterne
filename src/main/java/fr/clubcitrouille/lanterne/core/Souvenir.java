package fr.clubcitrouille.lanterne.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.network.SouvenirManifest;

/**
 * Le souvenir : le client garde le monde qu'il a déjà vu, d'une session à l'autre.
 *
 * <h2>Ce qu'{@link Emballage} ne fait pas, et que celui-ci fait</h2>
 *
 * <p>{@code Emballage} met en cache le paquet réseau d'un chunk côté <b>serveur</b>, pour un autre
 * joueur qui redemande le même chunk dans la <b>même</b> exécution du serveur. Sa propre javadoc le
 * dit sans détour : rien n'est gardé pour un joueur qui se déconnecte puis revient, et rien n'est fait
 * pour la lecture disque elle-même. Ce module attaque l'axe complémentaire — la mémoire du
 * <b>client</b>, à travers les reconnexions — et non l'axe déjà couvert.
 *
 * <h2>Le principe : un numéro de révision, jamais un différentiel</h2>
 *
 * <p>Un différentiel au niveau bloc serait la voie la plus économe en octets, et la plus dangereuse :
 * elle suppose de recalculer, côté client, un état qu'il faudrait alors prouver identique à celui du
 * serveur bloc par bloc — exactement le genre de raccourci que ce dépôt refuse ailleurs (voir
 * {@code Impasse}, qui a délibérément écarté un cache de chemin partagé pour la même raison). La
 * réponse retenue est binaire : chaque chunk porte un numéro de révision, incrémenté aux
 * <b>mêmes</b> points d'accroche qu'{@link Emballage#oublie} — voir {@code EmballageChunkMixin}, qui
 * appelle désormais {@link #bump} juste à côté de chaque appel à {@code Emballage.oublie}. Le client
 * annonce la révision qu'il connaît pour un chunk ; si elle correspond à la révision courante du
 * serveur, rien n'est renvoyé. Sinon, le chunk part en entier, exactement comme aujourd'hui.
 *
 * <h2>Ce que la révision ne survit délibérément pas : un redémarrage du serveur</h2>
 *
 * <p>La table vit en mémoire, par monde, et repart vide à chaque démarrage. C'est un choix, pas un
 * oubli : la faire survivre à un redémarrage demanderait de l'écrire dans le NBT du chunk lui-même,
 * ce qui touche le format de sauvegarde — un chantier distinct, à la charge et au risque d'un autre
 * module de ce dépôt (voir {@code ChunkDecode}), pas de celui-ci. La conséquence est bornée et sûre :
 * après un redémarrage, le premier retour sur un chunk force un renvoi complet — le comportement
 * vanilla exact — puis la révision se remet à exister et le module reprend son travail pour le reste
 * de cette exécution. Aucun chunk périmé n'est jamais servi comme à jour : l'absence d'entrée dans la
 * table ne peut jamais, par construction, égaler la révision qu'un client prétend connaître (voir
 * {@link #revisionFor}, qui n'assigne une valeur la première fois qu'à l'instant même où le chunk part
 * réellement — jamais avant).
 *
 * <h2>L'identité du monde : jamais la seed</h2>
 *
 * <p>Un client range son cache sous un identifiant de monde. Cet identifiant ne doit jamais permettre
 * de remonter à la graine — sans quoi mettre ce cache en commun (le partager, le publier) reviendrait
 * à publier la seed. C'est un UUID aléatoire, tiré une seule fois à la première création du monde et
 * écrit dans un fichier à part ({@link #idFile}), <b>jamais</b> dérivé de la seed, du nom du monde ou
 * de quoi que ce soit de déterministe. Il ne sert qu'à distinguer un monde d'un autre pour le client ;
 * il ne révèle rien du monde lui-même.
 *
 * <h2>Ce que ce module n'est pas : une source de vérité</h2>
 *
 * <p>Un client modifié pourrait prétendre connaître une révision qu'il n'a pas, pour ne jamais
 * recevoir une mise à jour — cacher qu'un mur vient d'être détruit, par exemple. Ce risque est réel et
 * il faut le dire, comme {@code Digue} dit à chaque prise ce qu'elle perd. La réponse n'est pas
 * technique, elle est de <b>portée</b> : ce module ne change rien à l'arbitrage du jeu. Les
 * collisions, les coups, les interactions restent calculés par le serveur sur l'état réel, cache ou
 * pas — un client qui mentirait ne gagnerait qu'un décor visuellement en retard, jamais un avantage de
 * jeu. C'est une optimisation de confort, pas un canal de confiance.
 */
public final class Souvenir {
    private static final String ID_FILE_NAME = "lanterne_souvenir_id.dat";

    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> REVISIONS = new HashMap<>();

    private static long served;
    private static long skipped;

    /**
     * Ce que chaque joueur connecté prétend déjà connaître, par dimension.
     *
     * <p>Purement transitoire — jamais écrit sur disque côté serveur, vidé à la déconnexion par
     * {@link #forgetPlayer}. Une entrée qui ne correspond à rien ne coûte qu'un envoi complet : voir
     * la javadoc de classe sur ce que ce module n'est pas.
     */
    private static final Map<UUID, Map<Identifier, Long2LongOpenHashMap>> CLAIMS = new HashMap<>();

    private Souvenir() {}

    /** Enregistre ce qu'un joueur vient d'annoncer connaître pour une dimension. */
    public static void recordManifest(UUID player, Identifier dimension, List<SouvenirManifest.Entry> entries) {
        Long2LongOpenHashMap table = new Long2LongOpenHashMap(entries.size());
        for (SouvenirManifest.Entry entry : entries) {
            table.put(entry.chunkPos(), entry.revision());
        }
        CLAIMS.computeIfAbsent(player, ignored -> new HashMap<>()).put(dimension, table);
    }

    /** La révision qu'un joueur prétend connaître pour ce chunk, ou {@code 0} s'il n'en a annoncé aucune. */
    public static long claimed(UUID player, Identifier dimension, long chunkPosKey) {
        Map<Identifier, Long2LongOpenHashMap> byDimension = CLAIMS.get(player);
        if (byDimension == null) {
            return 0L;
        }
        Long2LongOpenHashMap table = byDimension.get(dimension);
        return table == null ? 0L : table.get(chunkPosKey);
    }

    /** À la déconnexion : rien de ce qui touche à ce joueur ne doit vivre plus longtemps que lui. */
    public static void forgetPlayer(UUID player) {
        CLAIMS.remove(player);
    }

    /**
     * L'identité du monde : lue si le fichier existe, tirée et écrite sinon.
     *
     * <p>Un seul tirage par monde, à la première fois qu'on le demande — en pratique, à la première
     * connexion d'un joueur qui comprend ce protocole. Le fichier vit dans le dossier de sauvegarde du
     * monde, à côté de {@code level.dat}, mais n'en fait pas partie : le perdre ou le corrompre ne
     * casse rien, cela force seulement une nouvelle identité et donc un cache client reconstruit.
     */
    public static UUID worldId(MinecraftServer server) {
        Path file = server.getWorldPath(new LevelResource(ID_FILE_NAME));
        try {
            if (Files.exists(file)) {
                String text = Files.readString(file).trim();
                return UUID.fromString(text);
            }
        } catch (IOException | IllegalArgumentException corrupted) {
            Lanterne.LOG.warn("[SOUVENIR] identifiant de monde illisible, nouvel identifiant tiré.");
        }
        UUID fresh = UUID.randomUUID();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, fresh.toString());
        } catch (IOException failed) {
            Lanterne.LOG.warn("[SOUVENIR] impossible d'écrire l'identifiant de monde — "
                    + "le module restera inactif pour cette session.");
        }
        return fresh;
    }

    /** La table de révisions de ce monde, créée à la demande. */
    private static Long2LongOpenHashMap tableOf(ResourceKey<Level> dimension) {
        return REVISIONS.computeIfAbsent(dimension, ignored -> new Long2LongOpenHashMap());
    }

    /**
     * La révision courante d'un chunk, assignée à l'instant où on la demande si elle n'existait pas
     * encore.
     *
     * <p>Appelée uniquement au moment où un chunk part réellement vers un joueur — jamais avant, et
     * jamais pour un chunk qui n'a encore jamais été envoyé à personne. C'est ce qui garantit qu'une
     * entrée absente ne peut jamais, par accident, égaler une révision qu'un client prétend connaître
     * depuis une session précédente : tant qu'un chunk n'a pas été servi <b>dans cette exécution</b>
     * du serveur, il n'a pas de révision comparable.
     */
    public static long revisionFor(ServerLevel level, ChunkPos pos) {
        Long2LongOpenHashMap table = tableOf(level.dimension());
        long key = pos.pack();
        long current = table.get(key);
        if (current == 0L) {
            current = 1L;
            table.put(key, current);
        }
        return current;
    }

    /**
     * Un chunk a changé : sa révision avance, et plus aucune ancienne valeur connue d'un client ne
     * peut plus correspondre.
     *
     * <p>Appelé depuis les mêmes cinq points d'accroche qu'{@link Emballage#oublie} — voir
     * {@code EmballageChunkMixin}. N'assigne rien si le chunk n'avait encore jamais été envoyé : un
     * chunk qui change avant d'avoir été vu par quiconque n'a besoin d'aucune comptabilité.
     */
    public static void bump(ServerLevel level, ChunkPos pos) {
        Long2LongOpenHashMap table = tableOf(level.dimension());
        long key = pos.pack();
        long current = table.get(key);
        if (current != 0L) {
            table.put(key, current + 1L);
        }
    }

    /** Un chunk servi en entier — le cas normal, ou une révision qui ne correspondait pas. */
    public static void noteServed() {
        served++;
    }

    /** Un chunk épargné : le client en avait déjà la bonne révision. */
    public static void noteSkipped() {
        skipped++;
    }

    public static long served() {
        return served;
    }

    public static long skipped() {
        return skipped;
    }

    public static void reset() {
        REVISIONS.clear();
        CLAIMS.clear();
        served = 0L;
        skipped = 0L;
    }
}
