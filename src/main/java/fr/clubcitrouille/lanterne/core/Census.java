package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * Le recensement : un seul passage, une seule décision, et tout le monde la lit.
 *
 * <h2>Le vrai coût de vingt mods d'optimisation</h2>
 *
 * <p>Une instance chargée contient quatre modules qui font tous la même chose sans le savoir :
 * parcourir les entités pour déterminer lesquelles sont loin des joueurs. Les réglages adaptatifs
 * le font, les limiteurs d'IA le font, les gestionnaires de disparition le font, et le tout se
 * répète à chaque tick.
 *
 * <p>Quatre passages, quatre résultats, et aucune communication entre eux. L'un décide de ralentir
 * une vache, l'autre décide de sauter ses objectifs, le troisième envisage de la supprimer — chacun
 * ayant recalculé de son côté une distance que les trois autres venaient de calculer.
 *
 * <p>Ce n'est pas seulement du travail en double : c'est du travail <b>contradictoire</b>. Et cela
 * ne se corrige pas en réglant mieux les mods, parce que le défaut n'est pas dans leurs réglages —
 * il est dans le fait qu'ils soient plusieurs. Un recensement unique, lu par tout le monde, est la
 * seule réponse possible, et elle demande un mod unique.
 *
 * <h2>Recenser des chunks, et non des entités</h2>
 *
 * <p>L'approche naïve calcule, pour chaque entité, sa distance à chaque joueur. Avec cent mille
 * entités et cinq joueurs, cela fait cinq cent mille calculs de distance par tick — soit très
 * exactement le genre de remède qui coûte plus cher que le mal.
 *
 * <p>Mais deux vaches dans le même chunk sont, à seize blocs près, à la même distance du joueur. Et
 * seize blocs ne changent rien à une décision dont les seuils sont à trente-deux, soixante-quatre et
 * cent douze. <b>On recense donc les chunks.</b>
 *
 * <p>Le changement d'échelle est considérable. À dix chunks de simulation, un joueur en occupe
 * quatre cent quarante et un ; les cent mille entités se répartissent dedans. On passe de cinq cent
 * mille calculs à quatre cent quarante et un — <b>mille fois moins</b> — et le résultat est le même,
 * parce que la précision perdue était de la précision inutile.
 *
 * <p>C'est ce qui rend le système gratuit à l'échelle où il sert. Une optimisation dont le coût
 * croît avec le nombre d'entités ne tient pas la promesse qu'elle fait ; celle-ci croît avec le
 * nombre de chunks, c'est-à-dire avec le carré de la distance de simulation, et non avec ce que les
 * joueurs entassent dedans.
 *
 * <h2>La distance au coin, et non au centre</h2>
 *
 * <p>On mesure jusqu'au point du chunk le plus proche du joueur, pas jusqu'à son centre. C'est
 * délibérément prudent : on surestime la proximité, donc on dégrade moins que nécessaire. Une
 * erreur qui va dans ce sens ne se voit jamais ; l'autre se voit tout de suite.
 */
public final class Census {
    /**
     * Ticks entre deux recensements.
     *
     * <p>Le niveau de détail n'a pas besoin d'être juste au tick près. Une vache qui reste une
     * demi-seconde de trop en pleine simulation ne coûte rien ; le recensement fait à chaque tick,
     * lui, coûterait vingt fois son prix pour une exactitude dont personne ne profite.
     */
    private static final int REFRESH_PERIOD = 10;

    /**
     * Chunk encodé vers distance au joueur le plus proche, en blocs.
     *
     * <p>Une distance, et non un palier. La première version rangeait ici l'ordinal d'un niveau
     * parmi cinq ; c'était perdre l'information juste avant d'en avoir besoin. La cadence se calcule
     * mieux à partir du nombre lui-même, et les paliers ne servaient qu'à l'affichage.
     */
    private static final Long2ShortOpenHashMap DISTANCES = new Long2ShortOpenHashMap();

    /** Distance attribuée à un chunk qu'aucun joueur ne regarde. */
    private static final short UNSEEN = Short.MAX_VALUE;

    /**
     * Tick du dernier recensement.
     *
     * <h2>Un débordement qui neutralisait le mod entier</h2>
     *
     * <p>Ce champ valait {@code Long.MIN_VALUE} au départ, et {@link #setProbes} l'y remettait pour
     * forcer un recensement immédiat. La garde s'écrit :
     *
     * <pre>
     * if (tick - lastRefresh &lt; REFRESH_PERIOD) return;
     * </pre>
     *
     * <p>Or {@code 100 - Long.MIN_VALUE} <b>déborde</b> et redevient négatif. La condition était donc
     * toujours vraie, et le recensement <b>ne s'est jamais exécuté</b> — de la première ligne du mod
     * jusqu'au sixième banc. Tous les chunks restaient inconnus, tout retombait sur « pleine
     * simulation », et le mod ne faisait rien d'autre que consommer ses propres mesures.
     *
     * <p>Aucune relecture ne l'avait vu. Le banc, lui, l'a dit en une ligne : « chunks recensés : 0 ».
     * C'est très exactement ce pour quoi il a été écrit.
     */
    private static long lastRefresh;
    /** Force le prochain recensement, sans dépendre d'une arithmétique qui peut déborder. */
    private static boolean stale = true;

    /**
     * Observateurs d'essai, en coordonnées à plat : x, z, x, z…
     *
     * <p>Alimentés par le laboratoire. Ils comptent exactement comme des joueurs pour le
     * recensement — ce sont eux qui permettent de mesurer un serveur que personne n'habite.
     */
    private static double[] probes = new double[0];
    private static int probeCount;

    /** Compteurs du dernier recensement, pour le rapport. */
    private static int chunksSeen;
    private static int entitiesSeen;
    /** Somme des cadences constatées, pour en tirer le travail évité sans garder chaque valeur. */
    private static double keptWork;
    /** Comptage par rangée d'affichage. */
    private static final java.util.Map<String, long[]> BY_BUCKET = new java.util.LinkedHashMap<>();

    static {
        // Un chunk hors de portée de tout joueur est aussi loin qu'il est possible de l'être. Rien
        // ne s'y simule de toute façon : le jeu ne tick que ce qu'un ticket maintient.
        DISTANCES.defaultReturnValue(UNSEEN);
    }

    private Census() {}

    /**
     * Reconstruit la carte des niveaux, si le moment est venu.
     *
     * <p>Appelé au début du tick du monde. Le travail est proportionnel au nombre de chunks occupés
     * par des joueurs, et non au nombre d'entités.
     */
    public static void refresh(ServerLevel level) {
        long tick = level.getGameTime();
        if (!stale && tick - lastRefresh < REFRESH_PERIOD) {
            return;
        }
        stale = false;
        lastRefresh = tick;

        DISTANCES.clear();
        chunksSeen = 0;

        int radius = level.getServer().getPlayerList().getViewDistance() + 2;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue; // un spectateur ne justifie pas qu'on simule un pays entier
            }
            markAt(player.getX(), player.getZ(), radius);
        }

        // L'observateur d'essai tient lieu de joueur quand il n'y en a pas. Sans lui, un banc lancé
        // depuis la console d'un serveur vide ne mesurerait rien : aucun joueur, donc aucun chunk
        // recensé, donc tout en pleine simulation — et le mod paraîtrait inutile alors qu'il n'aurait
        // simplement pas eu l'occasion d'agir.
        for (int i = 0; i < probeCount; i++) {
            markAt(probes[i * 2], probes[i * 2 + 1], radius);
        }
    }

    /**
     * Place un observateur d'essai, qui compte comme un joueur pour le recensement.
     *
     * <p>Réservé au banc. Il ne change rien à la règle — c'est bien la distance à un observateur qui
     * décide — il fournit seulement un observateur là où il n'y en a pas.
     */
    public static void setProbes(double[] flatXZ, int count) {
        probes = flatXZ;
        probeCount = count;
        stale = true; // le prochain tick recense, sans attendre la période
    }

    public static int probeCount() {
        return probeCount;
    }

    /**
     * Inscrit les chunks autour d'un joueur, du meilleur niveau constaté.
     *
     * <p>Quand deux joueurs se recouvrent, c'est le plus exigeant qui l'emporte : un chunk proche
     * d'un joueur reste en pleine simulation même s'il est loin de tous les autres. C'est
     * exactement la règle qu'on attend, et elle tombe d'elle-même en gardant le minimum.
     */
    private static void markAt(double px, double pz, int radius) {
        int centreX = SectionPos.blockToSectionCoord((int) Math.floor(px));
        int centreZ = SectionPos.blockToSectionCoord((int) Math.floor(pz));

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centreX + dx;
                int cz = centreZ + dz;

                // Distance jusqu'au point du chunk le plus proche du joueur, et non jusqu'à son
                // centre : on surestime la proximité, donc on dégrade moins que nécessaire.
                double nearestX = clamp(px, cx << 4, (cx << 4) + 15);
                double nearestZ = clamp(pz, cz << 4, (cz << 4) + 15);
                double distanceSq = sq(px - nearestX) + sq(pz - nearestZ);

                short wanted = (short) Math.min(UNSEEN, (int) Math.sqrt(distanceSq));
                long key = ChunkPos.pack(cx, cz);

                short current = DISTANCES.get(key);
                if (wanted < current) {
                    DISTANCES.put(key, wanted);
                    if (current == UNSEEN) {
                        chunksSeen++;
                    }
                }
            }
        }
    }

    /**
     * Le niveau de détail applicable à une entité.
     *
     * <p>Une lecture dans une table de hachage, et rien d'autre — c'est tout ce que le chemin chaud
     * doit payer. Le calcul a eu lieu ailleurs, une fois pour tout un chunk.
     */
    public static double distanceOf(Entity entity) {
        long key = ChunkPos.pack(
                SectionPos.blockToSectionCoord(entity.getBlockX()),
                SectionPos.blockToSectionCoord(entity.getBlockZ()));
        return DISTANCES.get(key);
    }

    /** La distance applicable à une position de bloc, pour les blocs et les blocs-entités. */
    public static double distanceOfBlock(int blockX, int blockZ) {
        long key = ChunkPos.pack(
                SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockZ));
        return DISTANCES.get(key);
    }

    // ------------------------------------------------------------------ comptabilité

    /** Enregistre une cadence, pour que le rapport dise ce qui a été évité. */
    public static void count(int period) {
        entitiesSeen++;
        keptWork += 1d / Math.max(1, period);
        BY_BUCKET.computeIfAbsent(Cadence.bucket(period), ignored -> new long[1])[0]++;
    }

    public static int chunksSeen() {
        return chunksSeen;
    }

    public static int entitiesSeen() {
        return entitiesSeen;
    }

    public static java.util.Map<String, long[]> buckets() {
        return BY_BUCKET;
    }

    /**
     * Part du travail évité, entre zéro et un.
     *
     * <p>Chaque entité classée à un niveau de période <i>p</i> ne s'exécute qu'une fois sur <i>p</i>.
     * La somme des économies, rapportée au travail qu'on aurait fait sans rien, donne la seule
     * mesure qui compte — et elle est calculée, pas estimée.
     */
    public static double workAvoided() {
        return entitiesSeen == 0 ? 0d : 1d - keptWork / entitiesSeen;
    }

    public static void resetCounters() {
        entitiesSeen = 0;
        keptWork = 0d;
        BY_BUCKET.clear();
    }

    private static double clamp(double value, double low, double high) {
        return value < low ? low : value > high ? high : value;
    }

    private static double sq(double value) {
        return value * value;
    }
}
