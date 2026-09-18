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
