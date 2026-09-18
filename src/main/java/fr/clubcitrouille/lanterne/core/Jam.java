package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

import net.minecraft.world.entity.Entity;

/**
 * Les amas coincés : cesser de résoudre une bousculade dont le résultat est nul.
 *
 * <h2>Ce que le jeu calcule pour rien</h2>
 *
 * <p>À chaque tick, chaque créature cherche toutes ses voisines poussables et se pousse contre
 * chacune :
 *
 * <pre>
 * List&lt;Entity&gt; pushable = level.getPushableEntities(this, getBoundingBox());
 * for (Entity other : pushable) {
 *     this.doPush(other);
 * }
 * </pre>
 *
 * <p>Le coût croît avec le <b>carré</b> du nombre d'entités : cinquante vaches dans un chunk font
 * cinquante recherches spatiales et deux mille cinq cents poussées par tick. C'est le poste que le
 * profileur désigne sous {@code EntitySection.getEntities}, et il pèse près de huit pour cent.
 *
 * <h2>L'observation qui rend ce calcul inutile</h2>
 *
 * <p>Au milieu d'un tas, une créature est poussée <b>dans toutes les directions à la fois</b>. Les
 * forces se compensent, la somme est presque nulle, et elle ne va nulle part — c'est d'ailleurs
 * l'expérience de tout joueur qui a déjà entassé du bétail : ça grouille, mais ça ne se disperse
 * pas.
 *
 * <p>Le jeu dépense donc deux mille cinq cents opérations pour produire un déplacement de quelques
 * millièmes de bloc. Et ce résidu n'est pas neutre : c'est lui qui fait <b>vibrer</b> les créatures
 * sur place, les décollant du sol un tick sur deux.
 *
 * <p>Ce détail a un coût inattendu, découvert en corrigeant autre chose. Le mod exempte de
 * ralentissement ce qui tombe, pour ne pas casser les fermes ; or une créature qui vibre n'est pas
 * au sol, donc elle passait pour tomber, donc elle n'était plus jamais ralentie. <b>Le gain
 * s'effondrait de dix fois et demie à quatre.</b> La bousculade ne coûtait pas seulement son propre
 * calcul : elle annulait celui des autres.
 *
 * <h2>La règle retenue</h2>
 *
 * <p>Une créature qui <b>n'a pas bougé</b> depuis un moment, <b>entourée</b> de voisines, est
 * coincée. Tant qu'elle l'est, on ne résout plus sa bousculade : ni recherche de voisines, ni
 * poussées. Elle reste où elle est — ce qu'elle faisait déjà, mais sans le payer.
 *
 * <p>Les deux conditions sont nécessaires. L'immobilité seule désignerait une vache qui broute
 * tranquillement, dont on ne doit pas figer les collisions. La densité seule désignerait un
 * troupeau en mouvement, qui a bel et bien besoin d'être résolu.
 *
 * <h2>Comment on en sort</h2>
 *
 * <p>Une entité coincée est réexaminée périodiquement — un tick sur seize — et immédiatement si
 * elle bouge. C'est ce qui permet à un tas de se défaire quand la barrière s'ouvre, ou quand un
 * joueur vient s'y frayer un chemin : la première poussée qui déplace réellement quelqu'un rouvre
 * le calcul pour tout le monde, de proche en proche.
 *
 * <p>La correction est donc <b>conservatrice</b> : au pire, un tas met seize ticks à commencer à se
 * disperser au lieu d'un seul. Au mieux — et c'est le cas courant — on supprime la totalité d'un
 * calcul quadratique dont le résultat était l'immobilité.
 *
 * <h2>Appelant unique : le fil du serveur</h2>
 *
 * <p>{@link #WHERE} et {@link #STILLNESS} sont des tables fastutil, non synchronisées, indexées
 * par {@code entity.getId()}. C'est délibéré : un verrou, appelé une fois par créature et par tick,
 * réintroduirait un coût significatif sur exactement le chemin que ce module existe pour alléger.
 * La classe ne reste correcte que parce que son unique appelant — l'injection dans {@code
 * LivingEntity.pushEntities} — s'exclut désormais explicitement du côté client. En solo, client et
 * serveur intégré tournent dans la même JVM sur deux fils distincts et partagent les mêmes
 * identifiants d'entité ; sans cette exclusion, les deux fils mutaient ces tables en même temps et
 * corrompaient leur tableau interne. Toute nouvelle injection vers {@link #jammed} doit préserver
 * cette garantie plutôt que d'ajouter une synchronisation ici.
 */
public final class Jam {
    /** Ticks d'immobilité avant de déclarer une créature coincée. */
    private static final int PATIENCE = 20;
    /** Déplacement en deçà duquel on considère qu'une créature n'a pas bougé, en blocs. */
    private static final double STILL = 0.02d;
    /** Voisines qu'il faut avoir pour parler d'un amas. */
    private static final int NEIGHBOURS = 6;
    /** Un tick sur combien on réexamine un amas déclaré coincé. */
    private static final int RECHECK = 16;

    /** Position quantifiée au centième de bloc, empaquetée en un seul entier long. */
    private static final Int2LongOpenHashMap WHERE = new Int2LongOpenHashMap();
    /** Ticks d'immobilité consécutifs. */
    private static final Int2IntOpenHashMap STILLNESS = new Int2IntOpenHashMap();

    private static long lastSweep;

    private Jam() {}

    /**
     * Cette créature peut-elle se passer de résoudre sa bousculade à ce tick ?
     *
     * <p>Appelé une fois par créature et par tick : deux recherches dans une table d'entiers, et
     * rien d'autre. Le calcul qu'on évite, lui, est quadratique.
     */
    public static boolean jammed(Entity entity, int neighbours, long gameTime) {
        if (neighbours < NEIGHBOURS) {
            // Pas d'amas : on oublie ce qu'on savait d'elle, et le jeu reprend ses droits.
            forget(entity.getId());
            return false;
        }

        int id = entity.getId();
        long packed = pack(entity);
        long previous = WHERE.get(id);
        WHERE.put(id, packed);

        if (previous != packed) {
            STILLNESS.put(id, 0);
            return false; // elle a bougé : la bousculade a un effet, on la résout
        }

        int still = STILLNESS.get(id) + 1;
        STILLNESS.put(id, still);
        if (still < PATIENCE) {
            return false;
        }

        // Coincée, mais réexaminée de temps en temps : c'est ainsi qu'un tas se défait quand la
        // barrière s'ouvre, sans qu'on ait à surveiller la barrière.
        return (gameTime + id) % RECHECK != 0;
    }

    /**
     * Position quantifiée au centième de bloc.
     *
     * <p>Comparer des flottants à l'identique ne dirait rien : une créature bouge toujours d'un
     * cheveu. La quantification <em>est</em> le seuil d'immobilité, et elle rend la comparaison
     * exacte — un entier contre un entier, sans tolérance à régler.
     */
    private static long pack(Entity entity) {
        long x = (long) (entity.getX() / STILL);
        long z = (long) (entity.getZ() / STILL);
        return (x & 0xFFFFFFFFL) << 32 | (z & 0xFFFFFFFFL);
    }

    private static void forget(int id) {
        if (!WHERE.isEmpty()) {
            WHERE.remove(id);
            STILLNESS.remove(id);
        }
    }

    /**
     * Purge les créatures disparues.
     *
     * <p>Sans cela, les tables enfleraient de tout ce qui meurt, se déplace ou se décharge — et un
     * mod qui mesure ses propres allocations serait mal venu d'en fuir.
     */
    public static void sweep(long gameTime) {
        if (gameTime - lastSweep < 600L) {
            return;
        }
        lastSweep = gameTime;
        if (WHERE.size() > 20_000) {
            WHERE.clear();
            STILLNESS.clear();
        }
    }

    /** Nombre de créatures actuellement tenues pour coincées — pour le rapport. */
    public static int held() {
        int total = 0;
        for (int still : STILLNESS.values()) {
            if (still >= PATIENCE) {
                total++;
            }
        }
        return total;
    }

    public static void reset() {
        WHERE.clear();
        STILLNESS.clear();
    }
}
