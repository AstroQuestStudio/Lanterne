package fr.clubcitrouille.lanterne.client.scale;

import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.core.Lens;

/**
 * Réglages du chantier « petites configs » : profil de shaderpack et lentille selon la classe de GPU.
 * Fichier {@code config/lanterne-echelle-client.toml}, personnel à cette machine.
 *
 * <h2>Règle d'iso-qualité</h2>
 *
 * <p>Réduire la résolution ou les options d'un pack peut effacer des détails fins. Ces leviers sont
 * donc de DERNIER RECOURS : par défaut ils sont seulement <b>proposés</b> (un message, une fois) sur un
 * GPU qui en a besoin, jamais appliqués en silence, et jamais proposés sur un GPU capable.
 */
public final class ScaleConfig {
    /** Ce que Lanterne fait quand un réglage de petite config s'applique à cette machine. */
    public enum Mode {
        /** Ne rien dire, ne rien faire. */
        OFF,
        /** Dire une fois, dans la discussion, comment l'accepter (défaut). Rien n'est modifié. */
        PROPOSER,
        /** Appliquer au premier lancement, avec un message. À choisir en connaissance de cause. */
        AUTO
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<Mode> PROFIL_MODE;
    public static final ModConfigSpec.ConfigValue<String> PROFIL_IGPU;
    public static final ModConfigSpec.ConfigValue<String> PROFIL_ENTREE_DE_GAMME;
    public static final ModConfigSpec.EnumValue<Mode> LENTILLE_MODE;
    public static final ModConfigSpec.EnumValue<Lens.Preset> LENTILLE_IGPU;
    public static final ModConfigSpec.BooleanValue LENTILLE_HOULE_IGPU;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("Lanterne - petites configs : propositions de reglages selon le GPU.").push("echelle");

        PROFIL_MODE = BUILDER.comment(
                "Profil de qualite du shaderpack (celui que le pack declare lui-meme : profile.LOW...).",
                "OFF      : rien.",
                "PROPOSER : (defaut) un message, une fois par pack, sur un GPU integre ou d'entree de",
                "           gamme, avec la commande pour l'accepter. RIEN n'est modifie sans accord.",
                "AUTO     : applique au premier lancement du pack, avec un message. Jamais sur un pack dont",
                "           une option a deja ete changee, jamais sur un GPU dedie courant.")
                .defineEnum("profil_shaders", Mode.PROPOSER);
        PROFIL_IGPU = BUILDER.comment("Nom du profil du pack propose sur GPU integre (ex. LOW, VERYLOW).")
                .define("profil_gpu_integre", "LOW");
        PROFIL_ENTREE_DE_GAMME = BUILDER.comment("Nom du profil propose sur carte dediee d'entree de gamme.")
                .define("profil_entree_de_gamme", "MEDIUM");
        LENTILLE_MODE = BUILDER.comment(
                "La lentille (rendu a resolution reduite puis remontee FSR) peut effacer des details fins",
                "(herbe, feuillages, texte, aretes) : c'est un levier de DERNIER RECOURS, propose seulement",
                "sur GPU integre.",
                "OFF / PROPOSER (defaut) / AUTO : meme sens que ci-dessus.")
                .defineEnum("lentille", Mode.PROPOSER);
        LENTILLE_IGPU = BUILDER.comment(
                "Prereglage de la lentille propose sur GPU integre. Defaut ULTRA_QUALITE (77 % par",
                "dimension) : jamais moins de 75 % par defaut.")
                .defineEnum("lentille_prereglage_gpu_integre", Lens.Preset.ULTRA_QUALITE);
        LENTILLE_HOULE_IGPU = BUILDER.comment("Activer aussi la houle (resolution dynamique) avec la lentille.")
                .define("lentille_houle_gpu_integre", false);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ScaleConfig() {}

    public static boolean ready() {
        return SPEC.isLoaded();
    }
}
