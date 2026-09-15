package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import fr.clubcitrouille.lanterne.Lanterne;

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
 *
 * <h2>Une table par dimension, et ce n'est pas un détail de rangement</h2>
 *
 * <p>Ce recensement a longtemps tenu <b>une seule table statique pour tous les mondes</b>, avec un
 * seul horodatage. Trois conséquences s'enchaînaient, et la troisième a été rapportée depuis une
 * vraie partie : <em>« tout ce qui était entité était giga ralenti, le squelette lévite au sol
 * quand il prend des dégâts »</em>.
 *
 * <p><b>Un.</b> {@code LevelTickEvent.Pre} est tiré une fois <em>par monde</em>. Le premier monde à
 * ticker posait l'horodatage ; les deux autres repartaient aussitôt sur la garde de période. Un seul
 * monde était donc recensé, et sa carte servait à tous.
 *
 * <p><b>Deux.</b> {@code ChunkPos.pack} ne porte aucune dimension. Le chunk (10, 10) du Nether et
 * celui de l'Overworld partagent la même clé : un joueur dans l'un rendait « proche » le chunk
 * homonyme de l'autre.
 *
 * <p><b>Trois</b>, et c'est celle qui se voyait. Un chunk absent de la table rend {@link #UNSEEN},
 * c'est-à-dire la distance maximale, c'est-à-dire la cadence la plus lente — un réveil toutes les
 * soixante-douze ticks. Pour un joueur descendu au Nether, <b>aucun</b> chunk n'était inscrit : les
 * squelettes autour de lui étaient donc tickés une fois sur soixante-douze. La gravité ne
 * s'appliquant que dans le tick, ils flottaient ; leurs coups arrivaient trois secondes et demie
 * plus tard.
 *
 * <p>La garantie « rien n'est dégradé près d'un joueur » n'était pas fausse dans {@link Cadence} :
 * elle était juste alimentée par une distance inventée. <b>Une garantie ne vaut que ce que valent
 * ses entrées</b>, et celle-ci n'avait jamais été éprouvée ailleurs que dans l'Overworld.
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
     * Par monde : chunk encodé vers distance au joueur le plus proche, en blocs.
     *
     * <p>Une distance, et non un palier. La première version rangeait ici l'ordinal d'un niveau
     * parmi cinq ; c'était perdre l'information juste avant d'en avoir besoin. La cadence se calcule
     * mieux à partir du nombre lui-même, et les paliers ne servaient qu'à l'affichage.
     *
     * <p>Et une table <b>par monde</b> : voir la note de classe. Une seule table pour tous les mondes
     * ne se contentait pas de mélanger des chunks homonymes, elle laissait deux mondes sur trois
     * entièrement inconnus — donc entièrement dégradés.
     */
    private static final java.util.Map<ResourceKey<Level>, Long2ShortOpenHashMap> DISTANCES =
            new java.util.HashMap<>();

    /**
     * Par monde : tick du dernier recensement.
     *
     * <p>Un horodatage commun faisait que le premier monde à ticker fermait la porte aux autres pour
     * dix ticks. Chacun compte donc le sien.
     */
    private static final java.util.Map<ResourceKey<Level>, Long> REFRESHED_AT =
            new java.util.HashMap<>();

    /** Distance attribuée à un chunk qu'aucun joueur ne regarde. */
    private static final short UNSEEN = Short.MAX_VALUE;

    /**
     * La table d'un monde, créée à la demande.
     *
     * <p>Sa valeur par défaut est posée ici et non dans un bloc statique : chaque table neuve doit la
     * porter, faute de quoi un chunk absent rendrait zéro — c'est-à-dire « collé au joueur » — et le
     * mod cesserait d'agir dans ce monde sans que rien ne le dise.
     */
    private static Long2ShortOpenHashMap tableOf(ResourceKey<Level> dimension) {
        return DISTANCES.computeIfAbsent(dimension, ignored -> {
            Long2ShortOpenHashMap fresh = new Long2ShortOpenHashMap();
            fresh.defaultReturnValue(UNSEEN);
            return fresh;
        });
    }

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
     *
     * <p>L'horodatage vit désormais dans {@link #REFRESHED_AT}, un par monde.
     */
    /** Force le prochain recensement, sans dépendre d'une arithmétique qui peut déborder. */
    private static boolean stale = true;

    /**
     * Les chunks forcés valent-ils un joueur ?
     *
     * <h2>Une échappatoire réservée au laboratoire, et pourquoi elle est nécessaire</h2>
     *
     * <p>En jeu, forcer un chunk est un geste délibéré du joueur : il dit « continue de simuler
     * ceci ». Le recensement le traite donc comme une présence, et c'est le bon comportement.
     *
     * <p>Mais une épreuve du laboratoire force ses chunks pour une tout autre raison : sans joueur,
     * rien ne tourne, et il faut bien que la scène s'anime. Le forçage y est un <b>échafaudage</b>,
     * pas une intention. Depuis que le recensement l'honore, l'épreuve du duel refuse de conclure —
     * à juste titre : sa victime était devenue « à zéro bloc d'un joueur », donc dans la zone
     * franche, donc sans rien à démontrer.
     *
     * <p>Le laboratoire peut donc dire que son forçage ne compte pas. C'est la seule façon de
     * mesurer ce qui arrive à une créature <em>loin</em> de tout joueur, sur un serveur où il n'y a
     * personne. Rien dans le jeu ne touche à ce drapeau.
     */
    private static boolean honourForced = true;

    /**
     * Le laboratoire déclare que ses chunks forcés sont un échafaudage.
     *
     * <p>Voir {@link #honourForced}. À n'appeler que depuis {@code lab}.
     */
    public static void countForcedChunks(boolean honour) {
        honourForced = honour;
        stale = true; // la carte en cours porte l'ancienne regle : elle doit etre refaite
    }

    /**
     * Observateurs d'essai, en coordonnées à plat : x, z, x, z…
     *
     * <p>Alimentés par le laboratoire. Ils comptent exactement comme des joueurs pour le
     * recensement — ce sont eux qui permettent de mesurer un serveur que personne n'habite.
     */
    private static double[] probes = new double[0];
    private static int probeCount;

    /**
     * Chunks recenses, par monde.
     *
     * <p>Une somme et non un compteur unique : le dernier monde a ticker remettait le compteur a
     * zero, et le banc lisait alors « 0 chunk recense » sur un serveur parfaitement sain.
     */
    private static final java.util.Map<ResourceKey<Level>, Integer> chunksByLevel =
            new java.util.HashMap<>();
    private static int entitiesSeen;
    /** Somme des cadences constatées, pour en tirer le travail évité sans garder chaque valeur. */
    private static double keptWork;
    /** Comptage par rangée d'affichage. */
    private static final java.util.Map<String, long[]> BY_BUCKET = new java.util.LinkedHashMap<>();

    private Census() {}

    /**
     * Reconstruit la carte des niveaux, si le moment est venu.
     *
     * <p>Appelé au début du tick du monde. Le travail est proportionnel au nombre de chunks occupés
     * par des joueurs, et non au nombre d'entités.
     */
    public static void refresh(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        long tick = level.getGameTime();
        Long last = REFRESHED_AT.get(dimension);
        if (!stale && last != null && tick - last < REFRESH_PERIOD) {
            return;
        }
        stale = false;
        REFRESHED_AT.put(dimension, tick);

        Long2ShortOpenHashMap table = tableOf(dimension);
        table.clear();

        int radius = level.getServer().getPlayerList().getViewDistance() + 2;

        int seen = 0;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue; // un spectateur ne justifie pas qu'on simule un pays entier
            }
            markAt(table, player.getX(), player.getZ(), radius);
            seen++;
        }

        // L'observateur d'essai tient lieu de joueur quand il n'y en a pas. Sans lui, un banc lancé
        // depuis la console d'un serveur vide ne mesurerait rien : aucun joueur, donc aucun chunk
        // recensé, donc tout en pleine simulation — et le mod paraîtrait inutile alors qu'il n'aurait
        // simplement pas eu l'occasion d'agir.
        //
        // L'observateur ne vaut que pour le monde d'origine, faute de quoi un banc mesurant
        // l'Overworld protégerait aussi le Nether et l'on ne saurait plus ce qu'on mesure.
        if (level.dimension() == Level.OVERWORLD) {
            for (int i = 0; i < probeCount; i++) {
                markAt(table, probes[i * 2], probes[i * 2 + 1], radius);
            }
        }

        // <h2>Les chunks forcés comptent comme un joueur</h2>
        //
        // Poser un ticket de chargement permanent — par « /forceload », par un bloc chargeur de
        // chunk, par un mod — est le seul geste par lequel un joueur dit explicitement : « continue
        // de simuler ceci, même quand je n'y suis pas ». Les traiter comme un endroit inconnu
        // revenait à dégrader à la cadence plafond très exactement ce qu'on a demandé de garder
        // vivant : une ferme à villageois tournait à un tick sur quatre, le reste à un sur
        // soixante-douze, et le joueur l'aurait attribué à autre chose.
        //
        // La distance retenue est zéro, c'est-à-dire la pleine simulation. Le coût est borné par le
        // nombre de chunks que l'administrateur a lui-même décidé de forcer.
        if (honourForced) {
            for (long forced : level.getForceLoadedChunks()) {
                table.put(forced, (short) 0);
            }
        }

        // Le compteur affiché est la somme de tous les mondes : c'est lui que le banc relit, et un
        // compteur remis à zéro par le dernier monde à ticker rendrait « 0 chunk recensé » sur un
        // serveur parfaitement sain — le genre de faux négatif qui a déjà fait perdre une soirée.
        chunksByLevel.put(dimension, table.size());
        if (tick % 200 == 0) {
            Lanterne.LOG.info(
                    "[RECENSEMENT] {} · joueurs dans le niveau : {} · retenus : {} · chunks : {}",
                    dimension.identifier(), level.players().size(), seen, table.size());
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
    private static void markAt(Long2ShortOpenHashMap table, double px, double pz, int radius) {
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

                short current = table.get(key);
                if (wanted < current) {
                    table.put(key, wanted);
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
        return distanceOfBlock(entity.level(), entity.getBlockX(), entity.getBlockZ());
    }

    /**
     * La distance applicable à une position de bloc, pour les blocs et les blocs-entités.
     *
     * <p>Le monde est un paramètre et non une commodité : sans lui, la version précédente lisait la
     * table d'une autre dimension. Voir la note de classe.
     */
    public static double distanceOfBlock(Level level, int blockX, int blockZ) {
        Long2ShortOpenHashMap table = DISTANCES.get(level.dimension());
        if (table == null) {
            // Ce monde n'a encore jamais été recensé. Rendre UNSEEN reviendrait à dégrader au maximum
            // tout ce qui s'y trouve — le défaut exact qui faisait léviter les squelettes du Nether.
            // On rend zéro : la pleine simulation, le temps qu'un recensement ait lieu. Se tromper
            // dans ce sens coûte un tick de travail ; dans l'autre, cela casse le jeu.
            return 0d;
        }
        return table.get(ChunkPos.pack(
                SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockZ)));
    }

    // ------------------------------------------------------------------ comptabilité

    /** Enregistre une cadence, pour que le rapport dise ce qui a été évité. */
    public static void count(int period) {
        entitiesSeen++;
        keptWork += 1d / Math.max(1, period);
        BY_BUCKET.computeIfAbsent(Cadence.bucket(period), ignored -> new long[1])[0]++;
    }

    public static int chunksSeen() {
        int total = 0;
        for (int count : chunksByLevel.values()) {
            total += count;
        }
        return total;
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
