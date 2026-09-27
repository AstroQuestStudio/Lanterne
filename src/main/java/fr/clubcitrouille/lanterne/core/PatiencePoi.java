package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import net.minecraft.world.entity.PathfinderMob;

/**
 * La patience des villageois : ne pas redemander toutes les secondes ce qui n'existe pas.
 *
 * <h2>Le poste, mesuré</h2>
 *
 * <p>{@code AcquirePoi} cherche un lit, un poste de travail ou une cloche dans un rayon de 48 blocs
 * — toutes les une à deux secondes, pour chaque villageois à qui il en manque un, tant qu'il n'en
 * trouve pas. Sur le VPS le 27/09 : 155 villageois, aucun avec une cloche, 118 sans lit, 124 sans
 * métier, soit jusqu'à trois recherches vaines chacun, sans fin. JFR de 60 s, un joueur au château :
 * les villageois font 36 % du tick, dont {@code AcquirePoi} environ 16 % à lui seul (le balayage des
 * sections de POI, puis le tri du flux).
 *
 * <h2>Le remède</h2>
 *
 * <p>Une recherche qui ne trouve <b>aucun</b> candidat met ce villageois en attente pour ce type de
 * POI : 10 s, puis 20, 40, et au plus 60 s. Toute pose, disparition ou libération d'un POI dans le
 * serveur ({@link #changement}) lève toutes les attentes : un lit posé à côté d'un villageois est
 * trouvé à sa recherche suivante, comme chez vanilla. Une recherche qui trouve des candidats
 * injoignables reste à la charge de vanilla (sa file de réessais par position, jusqu'à 20 s).
 *
 * <p>Ce qui change pour le joueur : un villageois dont le seul lit libre apparaît par chargement de
 * chunk (et non par pose) peut attendre jusqu'à une minute avant de le voir. Module {@code poi}.
 */
public final class PatiencePoi {
    private PatiencePoi() {
    }

    /** Premier délai d'attente, en ticks. */
    private static final int PREMIER = 200;
    /** Délai maximal, en ticks. */
    private static final int PLAFOND = 1200;

    /** Incrémentée à chaque pose, retrait ou libération de POI : les attentes plus anciennes tombent. */
    private static long epoque;
    private static long evitees;

    /** Porté par chaque {@code PathfinderMob} (voir {@code PatiencePoiMobMixin}). */
    public interface Porteur {
        Reference2ObjectOpenHashMap<Object, long[]> lanterne$patience();
    }

    public static void changement() {
        epoque++;
    }

    /** Ce villageois doit-il encore attendre avant de chercher ce type de POI ? */
    public static boolean attendre(PathfinderMob body, Object type, long now) {
        Reference2ObjectOpenHashMap<Object, long[]> map = ((Porteur) body).lanterne$patience();
        long[] s = map.get(type);
        if (s == null) {
            return false;
        }
        if (s[2] != epoque) {
            map.remove(type);
            return false;
        }
        if (now < s[0]) {
            evitees++;
            return true;
        }
        return false;
    }

    /** Le résultat d'une recherche réellement faite. */
    public static void resultat(PathfinderMob body, Object type, long now, boolean vide) {
        Reference2ObjectOpenHashMap<Object, long[]> map = ((Porteur) body).lanterne$patience();
        if (!vide) {
            map.remove(type);
            return;
        }
        long[] s = map.get(type);
        int delai = s == null || s[2] != epoque ? PREMIER : (int) Math.min(PLAFOND, s[1] * 2);
        int jitter = body.getRandom().nextInt(delai / 4 + 1);
        map.put(type, new long[] {now + delai + jitter, delai, epoque});
    }

    /** Recherches sautées depuis le démarrage (rapport seulement). */
    public static long evitees() {
        return evitees;
    }
}
