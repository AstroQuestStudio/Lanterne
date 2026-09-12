package fr.clubcitrouille.lanterne.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.raid.Raider;

/**
 * La cadence : combien de ticks entre deux réveils, et pour qui.
 *
 * <h2>Pourquoi les paliers ont été abandonnés</h2>
 *
 * <p>La première version dégradait par gros paliers : plein, un sur deux, un sur quatre, un sur huit.
 * Un banc face à Immersive Optimization a montré le défaut, chiffres à l'appui — six millisecondes
 * par tick contre quatorze, sur la même charge.
 *
 * <p>La cause tient à ce qui se passe <em>entre</em> les paliers. Une créature à cent blocs tombait
 * dans la tranche « un tick sur quatre », et y restait jusqu'à cent douze blocs. La formule adverse,
 * {@code ceil(distance / 16)}, lui donnait un tick sur sept — <b>presque deux fois moins de
 * travail</b>, sans rien dégrader de plus au regard du joueur, qui ne voit ni l'une ni l'autre.
 *
 * <p>Un palier est un compromis figé : trop prudent en haut de tranche, trop brutal en bas. Une
 * droite n'a pas ce défaut, et la dégradation devient réellement continue — ce que le gradient
 * prétendait être depuis le début sans l'être tout à fait.
 *
 * <h2>La zone franche, qui ne bouge pas</h2>
 *
 * <p>En deçà d'une certaine distance, la cadence reste à un tick sur un. C'est la garantie qui rend
 * tout le reste acceptable : ce que le joueur peut voir de près se comporte exactement comme le jeu
 * le prévoit, sans exception ni condition.
 *
 * <h2>Ce qui a droit à des égards</h2>
 *
 * <p>Toutes les créatures ne sont pas égales devant le ralentissement, et la différence n'est pas
 * affective : elle tient à <b>ce qu'elles produisent</b>.
 *
 * <p>Une vache broute. Si elle broute trois fois moins souvent, rien ne change pour personne. Un
 * villageois, lui, travaille, restocke son étal, se reproduit et réapprovisionne ses offres — et
 * tout cela passe par son cerveau. Le ralentir, c'est ralentir la ferme à villageois qu'un joueur a
 * mis trois semaines à bâtir, et c'est très exactement le genre de dégât qu'un mod d'optimisation ne
 * doit jamais causer : invisible sur le moment, découvert trop tard.
 *
 * <p>Les villageois et les pillards gardent donc une cadence bien plus serrée. Ils sont peu
 * nombreux au regard du bétail, et ce qu'on leur concède se paie sur les vaches, qui n'en ont que
 * faire.
 */
public final class Cadence {
    /**
     * Rayon de la zone franche, en blocs.
     *
     * <p>Vingt-quatre blocs : au-delà de la portée d'interaction, au-delà de ce qu'on distingue
     * nettement, et en deçà de la distance à laquelle un joueur suit encore une créature du regard.
     */
    private static final double UNTOUCHED = 24d;

    /**
     * Blocs par cran de ralentissement.
     *
     * <p>Un tick de plus entre deux réveils tous les seize blocs — soit un chunk. La valeur vient
     * d'une comparaison chiffrée avec ce que fait la concurrence, et non d'une intuition : à seize,
     * la dégradation est invisible et le gain est là.
     */
    private static final double BLOCKS_PER_STEP = 16d;

    /**
     * Cadence la plus lente autorisée pour le tout-venant.
     *
     * <p>Un réveil toutes les trois secondes et demie. À cette distance, la créature n'est ni
     * visible, ni audible, ni atteignable ; ses mécaniques de fond — vieillissement, disparition —
     * sont comptées hors de son tick et continuent de tourner sans elle.
     */
    private static final int SLOWEST = 72;

    /**
     * Cadence la plus lente autorisée pour ce qui produit.
     *
     * <p>Quatre ticks, pas davantage. Un villageois conserve ainsi le quart de son temps de
     * réflexion où qu'il soit, ce qui suffit à tenir son métier, ses offres et sa descendance.
     */
    private static final int SLOWEST_PRODUCTIVE = 4;

    private Cadence() {}

    /**
     * Ticks entre deux réveils pour cette entité.
     *
     * @param distance distance au joueur le plus proche, en blocs
     * @param pressure sévérité demandée par le budget de tick, de 0 à 1
     */
    public static int forEntity(Entity entity, double distance, double pressure) {
        // La pression rapproche la dégradation du joueur quand le serveur souffre, et l'en éloigne
        // quand il respire. Un serveur au repos ne dégrade presque rien.
        double free = UNTOUCHED * (1d - 0.5d * clamp(pressure));
        if (distance <= free) {
            return 1;
        }

        int steps = 1 + (int) ((distance - free) / BLOCKS_PER_STEP);
        return Math.min(steps, ceiling(entity));
    }

    /**
     * Ce qu'on ne ralentira pas au-delà.
     *
     * <p>La question n'est pas « cette créature est-elle importante ? » mais « que perd-on à la
     * ralentir ? ». Un villageois perd sa production, un pillard perd un raid en cours — deux
     * choses qu'un joueur remarque et sur lesquelles il a construit.
     */
    public static int ceiling(Entity entity) {
        // {@code Npc} plutôt qu'une liste d'espèces : l'interface désigne exactement ce qu'on veut
        // protéger — ce qui a un métier — et couvre d'office les créatures ajoutées par des mods qui
        // se déclarent comme telles.
        if (entity instanceof Npc || entity instanceof Raider) {
            return SLOWEST_PRODUCTIVE;
        }
        return SLOWEST;
    }

    /**
     * Ce réveil-ci a-t-il lieu ?
     *
     * <p>Le décalage par identifiant étale le travail sur toute la période. Sans lui, toutes les
     * créatures d'une même cadence se réveilleraient dans le même tick : la charge ne baisserait
     * pas, elle se concentrerait, et les à-coups seraient pires qu'avant.
     */
    public static boolean actsOn(int period, long tick, int offset) {
        return period <= 1 || (tick + offset) % period == 0;
    }

    /** Rangée d'affichage pour le rapport : on ne va pas lister soixante-douze cadences. */
    public static String bucket(int period) {
        if (period <= 1) {
            return "Pleine simulation";
        }
        if (period <= 3) {
            return "Proche";
        }
        if (period <= 8) {
            return "Lointain";
        }
        if (period <= 24) {
            return "Très lointain";
        }
        return "En sommeil";
    }

    private static double clamp(double value) {
        return value < 0d ? 0d : value > 1d ? 1d : value;
    }
}
