package fr.clubcitrouille.lanterne.client.scale;

import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.core.Lens;

/**
 * Réglages du chantier « petites configs » : profil automatique de shaderpack et réglage de la
 * lentille selon la classe de GPU. Fichier {@code config/lanterne-echelle-client.toml}, personnel à
 * cette machine (jamais envoyé à un serveur).
 */
public final class ScaleConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue PROFIL_AUTO;
    public static final ModConfigSpec.ConfigValue<String> PROFIL_IGPU;
    public static final ModConfigSpec.ConfigValue<String> PROFIL_ENTREE_DE_GAMME;
    public static final ModConfigSpec.BooleanValue LENTILLE_AUTO;
    public static final ModConfigSpec.EnumValue<Lens.Preset> LENTILLE_IGPU;
    public static final ModConfigSpec.BooleanValue LENTILLE_HOULE_IGPU;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("Lanterne - petites configs : reglages automatiques selon le GPU.").push("echelle");

        PROFIL_AUTO = BUILDER.comment(
                "Appliquer AU PREMIER LANCEMENT d'un shaderpack le profil de qualite qui convient au GPU",
                "(integre, entree de gamme), parmi les profils que le pack declare lui-meme",
                "(shaders.properties : profile.LOW, profile.POTATO...). Une seule fois par pack ; ne touche",
                "jamais un pack dont le joueur a deja change une option. Un GPU dedie courant n'est",
                "jamais modifie.")
                .define("profil_shaders_auto", true);
        PROFIL_IGPU = BUILDER.comment("Nom du profil du pack choisi sur GPU integre (ex. LOW, VERYLOW).")
                .define("profil_gpu_integre", "LOW");
        PROFIL_ENTREE_DE_GAMME = BUILDER.comment("Nom du profil choisi sur carte dediee d'entree de gamme.")
                .define("profil_entree_de_gamme", "MEDIUM");
        LENTILLE_AUTO = BUILDER.comment(
                "Sur GPU integre, activer la lentille (rendu a resolution reduite + remontee FSR) au premier",
                "lancement. Une seule fois ; le joueur garde la main ensuite (lanterne-client.toml).")
                .define("lentille_auto", true);
        LENTILLE_IGPU = BUILDER.comment("Prereglage de la lentille applique sur GPU integre.")
                .defineEnum("lentille_prereglage_gpu_integre", Lens.Preset.QUALITE);
        LENTILLE_HOULE_IGPU = BUILDER.comment("Activer aussi la houle (resolution dynamique) sur GPU integre.")
                .define("lentille_houle_gpu_integre", true);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ScaleConfig() {}

    public static boolean ready() {
        return SPEC.isLoaded();
    }
}
