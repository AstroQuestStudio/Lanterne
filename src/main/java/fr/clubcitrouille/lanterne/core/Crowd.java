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
 * <h2>La garantie de proximité reste entière</h2>
 *
 * <p>Une entité en pleine simulation ne descend jamais plus bas qu'un tick sur deux, quelle que soit
 * la foule autour d'elle. C'est la limite qu'on s'impose : sous les yeux du joueur, on accepte de
 * réfléchir deux fois moins souvent, jamais quatre.
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
    /** Au-delà, c'est une ferme : deux crans de dégradation se justifient. */
    private static final int PACKED = 32;

    /** Comptage du tick en cours. */
    private static Long2IntOpenHashMap counting = new Long2IntOpenHashMap();
    /** Comptage du tick précédent, celui qu'on lit. */
    private static Long2IntOpenHashMap tallied = new Long2IntOpenHashMap();

    private static long currentTick = Long.MIN_VALUE;

    private Crowd() {}

    /**
     * Inscrit une entité et rend la pénalité de son chunk.
     *
     * <p>Les deux gestes sont réunis parce qu'ils portent sur la même case d'une même table : les
     * séparer doublerait le coût de la recherche pour rien.
     *
     * @return nombre de crans à descendre dans l'échelle des niveaux, de 0 à 2
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
        counting.addTo(key, 1);

        int neighbours = tallied.get(key);
        if (neighbours >= PACKED) {
            return 2;
        }
        return neighbours >= CROWDED ? 1 : 0;
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
        currentTick = gameTime;
    }

    /** Le plus gros attroupement observé, pour le rapport. */
    public static int densest() {
        int most = 0;
        for (int value : tallied.values()) {
            most = Math.max(most, value);
        }
        return most;
    }

    public static void reset() {
        counting.clear();
        tallied.clear();
        currentTick = Long.MIN_VALUE;
    }
}
