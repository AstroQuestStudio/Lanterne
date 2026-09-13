package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * La densité : la seconde raison de ne pas simuler quelque chose à fond.
 *
 * <h2>Ce que la distance seule ne voit pas</h2>
 *
 * <p>Le niveau de détail dégrade ce qui est loin, et c'est le bon critère — pour un mob isolé.
 * Cinquante vaches serrées dans un enclos à vingt blocs du joueur restent toutes en pleine
 * simulation : chacune cherche son chemin, broute, cherche un partenaire, et se pousse contre ses
 * quarante-neuf voisines. C'est très exactement la situation qui met un serveur à genoux, et la
 * distance n'en dit rien.
 *
 * <p>Or <b>une vache dans un troupeau de cinquante est indiscernable des quarante-neuf autres</b>.
 * Personne ne suit sa trajectoire ; on voit un troupeau. Le joueur perçoit le mouvement de la masse,
 * pas la décision individuelle — et il faudrait le regarder très attentivement, très longtemps, pour
 * s'apercevoir que celle du fond réfléchit deux fois moins souvent.
 *
 * <p>C'est la même idée que le niveau de détail, appliquée à un autre axe : <b>on dégrade ce qui ne
 * se distingue pas</b>, que ce soit parce que c'est loin ou parce que c'est noyé dans le nombre.
 *
 * <h2>Un comptage qui ne coûte rien</h2>
 *
 * <p>Compter les entités par chunk demanderait un parcours supplémentaire — soit précisément le
 * genre de remède plus cher que le mal qu'on s'interdit ici. On compte donc <b>pendant</b> le
 * passage que l'on fait déjà : chaque entité qui demande son niveau s'inscrit au passage dans le
 * compteur de son chunk.
 *
 * <p>Le compte ainsi obtenu est celui du tick précédent, puisqu'il n'est complet qu'à la fin du
 * tour. C'est sans importance : un troupeau ne se disperse pas en cinquante millisecondes, et une
 * vache de plus ou de moins ne change aucune décision.
 *
 * <h2>La garantie de proximité ne vaut pas pour les foules, et il faut le dire</h2>
 *
 * <p>Ce que cette dégradation coûte est dit sans détour dans le mot d'accueil du serveur : dans une
 * foule, une bête décide jusqu'à trente-deux fois moins souvent — <b>y compris sous les yeux du
 * joueur</b>. Elle continue en revanche de vieillir, de pondre et de se reproduire à l'heure exacte.
 */
public final class Crowd {
    /**
     * En deçà, la foule n'en est pas une.
     *
     * <p>Huit entités dans un chunk de seize blocs de côté, c'est une famille de cochons ou un petit
     * troupeau : chacune reste distinguable, et dégrader se verrait. Au-delà, on entre dans la
     * masse.
     */
    private static final int CROWDED = 8;

    /**
     * Voisines par cran de ralentissement supplémentaire.
     *
     * <h2>Pourquoi les deux crans fixes ont sauté</h2>
     *
     * <p>La densité ne connaissait que trois états : normal, foule, ferme — soit un facteur un, deux
     * ou quatre. C'était le même défaut que les paliers de distance, abandonnés depuis longtemps pour
     * la même raison : <b>un palier est trop prudent en haut de tranche et trop brutal en bas</b>. Une
     * vache entourée de trente-deux voisines et une vache entourée de mille recevaient exactement le
     * même traitement.
     *
     * <p>Or mille vaches dans un chunk, ce n'est pas « une ferme un peu plus grande » : c'est trente
     * fois plus de travail pour une image qui, à l'écran, est la même bouillie de taches blanches et
     * noires. C'est très précisément la situation où la dégradation se voit le moins et rapporte le
     * plus.
     */
    private static final int PER_STEP = 16;

    /**
     * Ralentissement maximal dû à la seule foule.
     *
     * <h2>Ce qui a autorisé à monter si haut</h2>
     *
     * <p>Ce plafond était à quatre, et il n'aurait pas dû monter d'un cran de plus tant que ralentir
     * une créature revenait à <b>arrêter ses horloges de production</b>. Un facteur seize aurait
     * divisé par seize la ponte d'une ferme à œufs.
     *
     * <p>Depuis que {@code Produce} tient ces horloges à jour pendant le sommeil — et que l'épreuve de
     * rendement le prouve, dans les deux sens — la cadence ne décide plus que d'une chose :
     * <b>à quelle fréquence une bête réfléchit et se déplace</b>. Dans un tas de mille, elle ne va
     * nulle part de toute façon.
     *
     * <p>Le plafond est passé de quatre à seize, puis de seize à <b>trente-deux</b>, chaque montée étant
     * mesurée avant d'être gardée :
     *
     * <pre>
     * ×4   → gain ×3,0 sur l'élevage intensif
     * ×16  → gain ×5,2, travail évité 93,3 %
     * ×32  → 26,89 ms contre 31,63, travail évité 96,1 %
     * </pre>
     *
     * <p>Trente-deux ticks, soit un réveil toutes les une virgule six seconde. Sur quatre mille bêtes
     * entassées, <b>cent vingt-cinq décident encore à chaque tick</b> — le troupeau grouille, il
     * grouille simplement sans coûter trente fois son prix.
     *
     * <p>Et le rendement ne bouge pas : l'épreuve le confirme à chaque montée. C'est ce qui rend ces
     * paliers défendables. Sans {@code Produce}, un facteur trente-deux aurait divisé par trente-deux
     * la ponte d'une ferme à œufs — et personne ne l'aurait relié au mod.
     */
    private static final int MOST = 32;

    /** Comptage du tick en cours. */
    private static Long2IntOpenHashMap counting = new Long2IntOpenHashMap();
    /** Comptage du tick précédent, celui qu'on lit. */
    private static Long2IntOpenHashMap tallied = new Long2IntOpenHashMap();

    private static long currentTick = Long.MIN_VALUE;

    /** Plus gros attroupement du tick précédent, tenu au fil de l'eau plutôt que recalculé. */
    private static int peak;
    private static int risingPeak;

    private Crowd() {}

    /**
     * Inscrit une entité et rend la pénalité de son chunk.
     *
     * <p>Les deux gestes sont réunis parce qu'ils portent sur la même case d'une même table : les
     * séparer doublerait le coût de la recherche pour rien.
     *
     * @return facteur par lequel multiplier la cadence, de 1 à {@value #MOST}
     */
    /** Nombre de créatures recensées dans le chunk de celle-ci, au tick précédent. */
    public static int neighbours(Entity entity) {
        long key = ChunkPos.pack(
                SectionPos.blockToSectionCoord(entity.getBlockX()),
                SectionPos.blockToSectionCoord(entity.getBlockZ()));
        return tallied.get(key);
    }

    public static int noteAndPenalty(Entity entity, long gameTime) {
        if (gameTime != currentTick) {
            rotate(gameTime);
        }

        long key = ChunkPos.pack(
                SectionPos.blockToSectionCoord(entity.getBlockX()),
                SectionPos.blockToSectionCoord(entity.getBlockZ()));
        int now = counting.addTo(key, 1) + 1;
        if (now > risingPeak) {
            risingPeak = now;
        }

        int neighbours = tallied.get(key);
        if (neighbours < CROWDED) {
            return 1; // pas de foule : le jeu garde ses droits, sans un geste de moins
        }
        return Math.min(1 + neighbours / PER_STEP, MOST);
    }

    /**
     * Bascule le comptage d'un tick au suivant.
     *
     * <p>On échange les deux tables au lieu d'en allouer une neuve : sur une machine à un seul cœur,
     * un ramassage ne s'exécute pas en parallèle, il fige le serveur. Ce mod a mesuré dix gigaoctets
     * alloués en vingt-cinq secondes de charge ; il n'est pas question d'en ajouter.
     */
    private static void rotate(long gameTime) {
        Long2IntOpenHashMap previous = tallied;
        tallied = counting;
        previous.clear();
        counting = previous; // les deux tables se relaient ; aucune n'est jamais créée
        peak = risingPeak;
        risingPeak = 0;
        currentTick = gameTime;
    }

    /**
     * Le plus gros attroupement observé au tick précédent.
     *
     * <h2>Une méthode de rapport appelée dans une boucle chaude</h2>
     *
     * <p>Cette méthode parcourait toute la table des chunks recensés. C'était sans conséquence tant
     * qu'elle ne servait qu'à écrire une ligne de rapport, une fois par banc.
     *
     * <p>Le court-circuit de collision a voulu la consulter une fois par tick pour savoir s'il valait
     * son prix. Sur un monde où plusieurs milliers de chunks sont recensés, ce parcours a fait passer
     * la charge dynamite de 62,99 à <b>96,64 ms</b> — le remède coûtant trois fois le mal qu'il
     * soignait.
     *
     * <p>Le maximum est donc tenu au fil de l'eau : une comparaison d'entiers par entité, dans une
     * méthode qui en fait déjà plusieurs, contre un parcours complet par tick.
     */
    public static int densest() {
        return peak;
    }

    public static void reset() {
        counting.clear();
        tallied.clear();
        currentTick = Long.MIN_VALUE;
        peak = 0;
        risingPeak = 0;
    }
}
