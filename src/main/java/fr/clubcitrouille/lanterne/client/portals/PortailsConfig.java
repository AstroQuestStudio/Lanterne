package fr.clubcitrouille.lanterne.client.portals;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * PORTAILS IMMERSIFS : l'interrupteur, et rien d'autre pour l'instant.
 *
 * <h2>Pourquoi un fichier séparé de {@code core.ClientConfig}</h2>
 *
 * <p>Même raison que {@code content.relief.ReliefConfig} ou {@code client.screen.Consent} : un
 * module qui n'a encore aucun effet réel vit dans son propre fichier, pour qu'aucun chantier en
 * cours sur le fichier client commun — celui-ci change de forme cette semaine même, voir
 * {@code core.ClientConfig} — n'ait jamais à composer avec un réglage qui, de toute façon, ne fait
 * rien d'autre que journaliser.
 *
 * <h2>Ce que ce réglage fait aujourd'hui : rien</h2>
 *
 * <p>Voir {@code notes/immersive-portals-faisabilite.md} pour l'enquête complète (recherche du
 * jar réel, comparaison bytecode avec le moteur renderpearl de ce dépôt). Verdict court : aucun
 * jar d'Immersive Portals — officiel, communautaire, ou fork de compatibilité — n'existe pour
 * « Minecraft 26.3 » / NeoForge 26.3.0.3-beta, et l'architecture que le mod suppose (rendu
 * récursif dans {@code LevelRenderer.renderLevel}, état OpenGL/{@code RenderSystem} mutable à
 * l'image pour le pochoir/plan de coupe du portail) n'a pas d'équivalent dans l'abstraction
 * déclarative de ce moteur ({@code GpuDevice}/{@code CommandEncoder}/{@code RenderPipeline},
 * pipelines figés à la construction, jamais mutés en cours d'image).
 *
 * <p>Allumer ce réglage ne fait donc QU'UNE chose : écrire une ligne au journal, au démarrage du
 * client — voir {@link Portail#verifie()}. Aucun mixin, aucun renderer, aucune dégradation
 * d'aucune optimisation existante (Voile, lentille temporelle) : il n'y a rien à leur faire
 * cohabiter avec un rendu de portail qui n'existe pas dans ce build.
 */
public final class PortailsConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ACTIVE;

    public static final ModConfigSpec.BooleanValue TEST_PORTAL;
    public static final ModConfigSpec.IntValue TEST_X;
    public static final ModConfigSpec.IntValue TEST_Y;
    public static final ModConfigSpec.IntValue TEST_Z;
    public static final ModConfigSpec.IntValue TEST_WIDTH;
    public static final ModConfigSpec.IntValue TEST_HEIGHT;
    public static final ModConfigSpec.IntValue TEST_OFFSET_X;
    public static final ModConfigSpec.IntValue TEST_OFFSET_Y;
    public static final ModConfigSpec.IntValue TEST_OFFSET_Z;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "PORTAILS IMMERSIFS (qouteall / iPortalTeam) : compagnon optionnel, jar separe —",
                "jamais fusionne dans Lanterne.",
                "",
                "DESACTIVE PAR DEFAUT, et ce n'est pas de la prudence excessive : aucune integration",
                "reelle n'existe cette passe. Voir notes/immersive-portals-faisabilite.md pour",
                "l'enquete complete — recherche du jar reel (aucun ne couvre ce moteur), et",
                "comparaison bytecode avec l'architecture de rendu reellement exposee ici.",
                "",
                "Allumer ce reglage aujourd'hui ne fait QU'UNE chose : ecrire au journal, au",
                "demarrage du client, si le mod est detecte (ModList) et pourquoi rien de plus ne",
                "se passe. Aucun rendu, aucun mixin, aucune degradation de performance — il n'y a",
                "rien a degrader tant que rien n'est branche.")
                .push("portails_immersifs");
        ACTIVE = BUILDER.comment(
                "Coupe tout, y compris le message de journal ci-dessus.",
                "FAUX (defaut) : silence total, meme si le mod est present.",
                "VRAI : un message de journal au demarrage du client, rien de plus pour l'instant.")
                .define("actif", false);

        BUILDER.comment(
                "PROTOTYPE (nouvelle mission, correction du malentendu du debut de soiree) : PAS le",
                "mod tiers — une fonctionnalite de portail maison, minimale, non recursive, un seul",
                "portail statique. Voir client.portals.PortailPrototype et",
                "notes/immersive-portals-faisabilite.md section 'prototype maison' pour le detail",
                "de ce que fait reellement ce mecanisme, et de ce qu'il NE fait PAS (entites",
                "invisibles a travers le portail, ciel non rendu — deux limites structurelles,",
                "pas des oublis, voir la javadoc de PortailPrototype).",
                "",
                "DESACTIVE PAR DEFAUT : un second rendu de niveau complet par image a un cout reel,",
                "voir le chiffre (ou son absence honnete) dans la note.").push("prototype_maison");
        TEST_PORTAL = BUILDER.comment(
                "Allume le portail de test : un rectangle fixe, face SUD, a la position ci-dessous,",
                "montrant le monde vu depuis la position du joueur decalee de l'offset ci-dessous.",
                "FAUX (defaut) : rien n'est rendu, rien n'est alloue, cout nul.")
                .define("actif", false);
        TEST_X = BUILDER.comment("Position du centre du rectangle de test, coordonnee X.")
                .defineInRange("position_x", 0, -30_000_000, 30_000_000);
        TEST_Y = BUILDER.comment("Position du centre du rectangle de test, coordonnee Y.")
                .defineInRange("position_y", 100, -2032, 2032);
        TEST_Z = BUILDER.comment("Position du centre du rectangle de test, coordonnee Z.")
                .defineInRange("position_z", 0, -30_000_000, 30_000_000);
        TEST_WIDTH = BUILDER.comment("Largeur du rectangle, en blocs.")
                .defineInRange("largeur_blocs", 3, 1, 16);
        TEST_HEIGHT = BUILDER.comment("Hauteur du rectangle, en blocs.")
                .defineInRange("hauteur_blocs", 3, 1, 16);
        TEST_OFFSET_X = BUILDER.comment(
                "Decalage de la camera de destination par rapport a la position REELLE du joueur,",
                "recalcule chaque image — pas une position fixe. Meme orientation que le joueur :",
                "voir la javadoc de PortailPrototype pour pourquoi (limite assumee de ce prototype).")
                .defineInRange("destination_decalage_x", 16, -30_000_000, 30_000_000);
        TEST_OFFSET_Y = BUILDER.comment("Decalage de la camera de destination, coordonnee Y.")
                .defineInRange("destination_decalage_y", 0, -2032, 2032);
        TEST_OFFSET_Z = BUILDER.comment("Decalage de la camera de destination, coordonnee Z.")
                .defineInRange("destination_decalage_z", 0, -30_000_000, 30_000_000);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private PortailsConfig() {}

    public static void apply(ModConfigEvent event) {
        // Rien a recalculer : ModConfigSpec.BooleanValue se relit lui-meme a chaque .get(), comme
        // partout ailleurs dans ce depot (voir ReliefConfig.apply, meme commentaire).
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
    }
}
