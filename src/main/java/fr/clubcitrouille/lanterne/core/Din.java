package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Le vacarme : au-delà d'un certain nombre, un même son au même endroit n'apporte plus rien.
 *
 * <h2>Ce que vanilla ne fait pas</h2>
 *
 * <p>{@code SoundEngine} n'a <b>aucune</b> limite sur les sons identiques. Le seul plafond est le
 * nombre de canaux audio disponibles ; quand il est atteint, les sons suivants sont simplement
 * perdus, sans choix ni priorité.
 *
 * <p>Une ferme à mobs qui tue trente créatures dans la même seconde demande donc trente sons de
 * mort, tous au même endroit, tous dans la même fraction de seconde. Le joueur en entend un — les
 * vingt-neuf autres se superposent en une bouillie — mais le moteur les résout, les positionne et
 * les mélange tous les trente.
 *
 * <h2>Ce que ce module promet, et ce qu'il ne promet pas</h2>
 *
 * <p>Il ne promet <b>pas</b> d'images par seconde. Le son vit sur son propre fil ; l'économie est
 * du temps processeur et des canaux audio, pas du temps de rendu. Elle ne se verra pas sur un banc
 * d'images, et ce module est donc rangé avec la chute des feuilles, du côté du confort.
 *
 * <p>Ce qu'il apporte est audible : dans une ferme, la bouillie redevient un son. C'est la raison
 * principale de le retenir, et elle est assumée comme telle plutôt que déguisée en performance.
 *
 * <h2>Pourquoi des nombres et non des chaînes</h2>
 *
 * <p>Le mod de référence construit une clé de texte par son — {@code "minecraft:entity.cow.hurt|3|4|-2"}
 * — ce qui alloue trois chaînes et un tableau à chaque son joué. Sur le chemin qu'on prétend
 * alléger, c'est exactement ce qu'il ne faut pas faire.
 *
 * <p>Ici la clé est un seul {@code long} : le nombre de renvoi du son mélangé aux coordonnées de sa
 * région. Aucune allocation, une seule recherche de table.
 */
public final class Din {
    /** Côté d'une région, en blocs. Deux sons dans le même cube sont « au même endroit ». */
    private static int region = 8;

    /** Sons identiques tolérés dans une région pendant la fenêtre. */
    private static int allowance = 3;

    /** Durée de la fenêtre glissante, en millisecondes. */
    private static long window = 250L;

    /**
     * Par clé : l'ouverture de la fenêtre courante et le compte, dans un seul nombre.
     *
     * <p>Les seize bits de poids faible portent le compte, les autres l'horodatage en
     * millisecondes. Un horodatage de quarante-huit bits couvre huit mille ans ; seize bits de
     * compte en autorisent soixante-cinq mille, très au-delà de ce qu'une fenêtre d'un quart de
     * seconde peut recevoir.
     */
    private static final Long2LongOpenHashMap HEARD = new Long2LongOpenHashMap();

    private static long muffled;

    private Din() {}

    /**
     * Ce son est-il de trop ?
     *
     * @param sound nombre de renvoi de l'identifiant du son
     * @return vrai si le même son a déjà retenti assez de fois ici pendant la fenêtre
     */
    public static boolean tooMany(int sound, double x, double y, double z) {
        long now = System.currentTimeMillis();
        long key = key(sound, x, y, z);
        long packed = HEARD.get(key);
        long opened = packed >>> 16;
        int count = (int) (packed & 0xFFFFL);

        if (packed == 0L || now - opened > window) {
            // Fenêtre absente ou expirée : celle-ci s'ouvre avec ce son pour premier occupant.
            HEARD.put(key, (now << 16) | 1L);
            return false;
        }
        if (count >= allowance) {
            muffled++;
            return true;
        }
        HEARD.put(key, (opened << 16) | (count + 1L));
        return false;
    }

    /**
     * Purge les fenêtres éteintes.
     *
     * <p>Appelée au tick client, pas à chaque son : parcourir la table à chaque appel coûterait
     * plus que les sons qu'on évite. Une table qui garde quelques centaines d'entrées mortes
     * pendant une seconde ne gêne personne.
     */
    public static void sweep() {
        if (HEARD.size() < 256) {
            return;
        }
        long now = System.currentTimeMillis();
        HEARD.long2LongEntrySet().removeIf(entry -> now - (entry.getLongValue() >>> 16) > window * 4L);
    }

    private static long key(int sound, double x, double y, double z) {
        long rx = Math.floorDiv((long) Math.floor(x), region);
        long ry = Math.floorDiv((long) Math.floor(y), region);
        long rz = Math.floorDiv((long) Math.floor(z), region);
        // Mélange de Fibonacci sur chaque composante : des régions voisines doivent tomber loin
        // l'une de l'autre dans la table, sans quoi une ferme concentre toutes ses clés au même
        // endroit et la table dégénère en liste.
        long mixed = sound * 0x9E3779B97F4A7C15L;
        mixed ^= rx * 0xC2B2AE3D27D4EB4FL;
        mixed ^= ry * 0x165667B19E3779F9L;
        mixed ^= rz * 0x85EBCA77C2B2AE63L;
        return mixed ^ (mixed >>> 31);
    }

    public static void tune(int regionBlocks, int allowedPerWindow, long windowMillis) {
        region = Math.max(1, regionBlocks);
        allowance = Math.max(1, allowedPerWindow);
        window = Math.max(1L, windowMillis);
    }

    /** Sons étouffés depuis le dernier rapport. */
    public static long muffled() {
        return muffled;
    }

    public static void reset() {
        HEARD.clear();
        muffled = 0L;
    }
}
