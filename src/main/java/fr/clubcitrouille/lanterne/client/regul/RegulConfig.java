package fr.clubcitrouille.lanterne.client.regul;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Réglages du régulateur adaptatif de qualité — fichier {@code lanterne-regulateur-client.toml},
 * propre à cette machine (jamais envoyé à un serveur).
 */
public final class RegulConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ACTIF;
    public static final ModConfigSpec.IntValue CIBLE_FPS;
    public static final ModConfigSpec.IntValue DISTANCE_PLANCHER;
    public static final ModConfigSpec.BooleanValue PROFIL_AUTO;
    public static final ModConfigSpec.BooleanValue PROFIL_APPLIQUE;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "Lanterne - regulateur adaptatif de qualite (petites configs, GPU integres).",
                "",
                "Mesure le temps d'image et, quand le jeu passe sous la cible, baisse la qualite par",
                "petits paliers (ombres des entites, nuages, particules, distance des entites, distance",
                "de rendu...) puis la remonte quand il y a de la marge. Il ne depasse JAMAIS les",
                "reglages que vous avez choisis dans les options video.").push("regulateur");

        ACTIF = BUILDER.comment("Activer le regulateur.").define("actif", true);
        CIBLE_FPS = BUILDER.comment("Images par seconde visees (30, 60, 90, 120, 144...).")
                .defineInRange("cible_fps", 60, 20, 360);
        DISTANCE_PLANCHER = BUILDER.comment(
                "Distance de rendu minimale (chunks) en dessous de laquelle le regulateur ne descend pas.")
                .defineInRange("distance_plancher", 4, 2, 16);
        PROFIL_AUTO = BUILDER.comment(
                "Au premier lancement, choisir un profil 'petite config' si le GPU est integre",
                "(AMD Radeon Graphics, Intel UHD/Iris Xe...) ou a peu de memoire video.")
                .define("profil_auto", true);
        PROFIL_APPLIQUE = BUILDER.comment("Interne : le profil automatique a deja ete evalue.")
                .define("profil_applique", false);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private RegulConfig() {}
}
