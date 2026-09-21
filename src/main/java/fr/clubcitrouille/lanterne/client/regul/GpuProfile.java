package fr.clubcitrouille.lanterne.client.regul;

import java.util.Locale;

/** Classe le GPU actif : intégré / faible, ou dédié. Décision sur la seule chaîne GL_RENDERER. */
public final class GpuProfile {
    private GpuProfile() {}

    /**
     * @return vrai si le renderer ressemble à un GPU intégré ou de bas de gamme (mémoire partagée).
     *     Les cartes dédiées connues (GeForce, Quadro, Radeon RX/PRO, Arc) l'emportent toujours.
     */
    public static boolean petiteConfig(String renderer) {
        if (renderer == null || renderer.isBlank()) {
            return false;
        }
        String r = renderer.toLowerCase(Locale.ROOT);
        if (r.contains("geforce") || r.contains("rtx") || r.contains("gtx") || r.contains("quadro")
                || r.contains("radeon rx") || r.contains("radeon pro") || r.contains("arc(")
                || r.contains("arc a") || r.contains("arc b")) {
            return false;
        }
        return r.contains("intel") || r.contains("uhd") || r.contains("iris")
                || r.contains("vega") || r.contains("radeon(tm) graphics")
                || r.contains("radeon graphics") || r.contains("mali") || r.contains("adreno")
                || r.contains("llvmpipe") || r.contains("software") || r.contains("microsoft basic");
    }
}
