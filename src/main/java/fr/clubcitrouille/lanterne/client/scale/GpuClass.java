package fr.clubcitrouille.lanterne.client.scale;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * La classe de GPU d'une machine : intégré au processeur, dédié d'entrée de gamme, ou dédié.
 *
 * <p>Sert à choisir un profil de qualité au premier lancement (voir {@link ShaderProfileAuto}). La
 * détection se fait sur le NOM du pilote — la seule information portable —, avec des motifs prudents :
 * un GPU inconnu est classé {@link #UNKNOWN} et ne déclenche AUCUN réglage automatique. Se tromper
 * vers le bas coûterait de la beauté à un joueur bien équipé ; se tromper vers le haut, des à-coups à
 * un joueur mal équipé. Les deux cas sont réglables à la main, et le réglage automatique ne s'applique
 * qu'une fois par shaderpack.
 */
public enum GpuClass {
    /** GPU intégré (Ryzen « Radeon Graphics », Intel UHD/Iris Xe, Arc intégré…). */
    INTEGRATED,
    /** Carte dédiée ancienne ou d'entrée de gamme. */
    LOW_DEDICATED,
    /** Carte dédiée courante ou plus. */
    DEDICATED,
    /** Nom non reconnu : aucun réglage automatique. */
    UNKNOWN;

    private static final Pattern AMD_IGPU = Pattern.compile(
            "radeon(\\(tm\\))? graphics|radeon \\d{3}m\\b|radeon vega \\d+|vega \\d+ graphics|radeon r\\d graphics");
    private static final Pattern INTEL_IGPU = Pattern.compile(
            "uhd graphics|iris(\\(r\\))? (xe|plus|pro)|hd graphics|intel\\(r\\) graphics|arc\\(tm\\) graphics");
    private static final Pattern NVIDIA_LOW = Pattern.compile(
            "\\bgt ?\\d{3,4}\\b|\\bmx ?\\d{3}\\b|gtx ?(7\\d{2}|9[45]0|10[35]0)\\b|quadro [kp]?\\d{3,4}\\b");
    private static final Pattern AMD_LOW = Pattern.compile("radeon (rx )?(5[0-9]{2}|r[579] ?\\d{3}|hd ?\\d{4})\\b");
    private static final Pattern INTEL_ARC_DISCRETE = Pattern.compile("\\barc\\b.*\\b[ab]\\d{3}");

    /**
     * @param name nom du pilote graphique (« AMD Radeon(TM) Graphics », « NVIDIA GeForce RTX 3080… »)
     * @param vendor fabricant tel que rapporté, éventuellement vide
     */
    public static GpuClass classify(String name, String vendor) {
        String n = (name == null ? "" : name).toLowerCase(Locale.ROOT);
        String v = (vendor == null ? "" : vendor).toLowerCase(Locale.ROOT);
        if (n.isEmpty()) {
            return UNKNOWN;
        }
        if (INTEL_ARC_DISCRETE.matcher(n).find()) {
            return DEDICATED;
        }
        if (n.contains("nvidia") || n.contains("geforce") || v.contains("nvidia")) {
            return NVIDIA_LOW.matcher(n).find() ? LOW_DEDICATED : DEDICATED;
        }
        if (n.contains("amd") || n.contains("radeon") || v.contains("amd") || v.contains("ati")) {
            if (AMD_IGPU.matcher(n).find()) {
                return INTEGRATED;
            }
            return AMD_LOW.matcher(n).find() ? LOW_DEDICATED : DEDICATED;
        }
        if (n.contains("intel") || v.contains("intel")) {
            return INTEL_IGPU.matcher(n).find() ? INTEGRATED : UNKNOWN;
        }
        return UNKNOWN;
    }
}
