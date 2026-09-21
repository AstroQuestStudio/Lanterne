package fr.clubcitrouille.lanterne.client.culling;

import java.util.Locale;
import java.util.Set;

/**
 * Interrupteurs de MESURE des cullings existants, lus une seule fois au chargement de la classe.
 *
 * <p>{@code -Dlanterne.culling.off=shroud,blocks,particles,horizon,chests} coupe les modules cités,
 * sans toucher au fichier de configuration : c'est ce qui permet au banc central de comparer
 * « culling actif » et « culling coupé » sur la MÊME scène, avec le même jar. Sans la propriété, tout
 * est à sa valeur normale et le coût est un champ statique final lu par le JIT comme une constante.
 */
public final class CullingSwitch {
    private static final Set<String> OFF = parse(System.getProperty("lanterne.culling.off", ""));

    public static final boolean SHROUD_OFF = OFF.contains("shroud") || OFF.contains("voile");
    public static final boolean BLOCKS_OFF = OFF.contains("blocks") || OFF.contains("blocs");
    public static final boolean PARTICLES_OFF = OFF.contains("particles") || OFF.contains("particules");
    public static final boolean HORIZON_OFF = OFF.contains("horizon");
    public static final boolean CHESTS_OFF = OFF.contains("chests") || OFF.contains("coffres");

    private CullingSwitch() {}

    private static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }
}
