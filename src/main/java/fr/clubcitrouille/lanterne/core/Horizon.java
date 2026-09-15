package fr.clubcitrouille.lanterne.core;

import net.minecraft.world.entity.Entity;

/**
 * L'horizon : jusqu'où l'on prépare des créatures, quand il y en a dix mille.
 *
 * <h2>Ce que vanilla coupe déjà — et il faut le dire avant de proposer quoi que ce soit</h2>
 *
 * <p>Les trois économies qui viennent d'abord à l'esprit sont <b>déjà faites par le jeu</b> en 26.2.
 * Les chercher une seconde fois aurait produit trois modules mesurés à zéro, et ce projet en a déjà
 * retiré neuf pour cette raison exacte.
 *
 * <ul>
 *   <li><b>Les étiquettes de nom.</b> {@code EntityRenderer.extractNameTags} les coupe au-delà de
 *       soixante-quatre blocs, et {@code LivingEntityRenderer.shouldShowName} descend à trente-deux
 *       pour une créature accroupie. La distance n'est même plus une constante : c'est l'attribut
 *       {@code Attributes.NAME_TAG_DISTANCE}, réglable par entité.</li>
 *   <li><b>Les ombres.</b> {@code EntityRenderer.extractShadow} calcule
 *       {@code pow = (1 - distSq / 256) * force}, puis sort si {@code pow <= 0}. Le balayage de blocs
 *       et les {@code ShadowPiece} qu'il alloue s'arrêtent donc net à <b>seize blocs</b>.</li>
 *   <li><b>La distance de rendu.</b> {@code Entity.shouldRender} compare déjà à
 *       {@code taille × 64 × viewScale}, où {@code viewScale} suit l'option « distance des
 *       entités » du joueur.</li>
 * </ul>
 *
 * <h2>Ce qui n'est pas coupé, et ne peut pas l'être</h2>
 *
 * <p>Le poste dominant est ailleurs, et il est <b>structurellement protégé</b> : la pose du modèle.
 * {@code ModelFeatureRenderer.prepareModel} appelle {@code model.setupAnim(state)} puis
 * {@code renderToBuffer} pour chaque créature soumise, à chaque image.
 *
 * <p>La tentation — n'animer une vache lointaine qu'une image sur trois — <b>ne fonctionne pas dans
 * cette architecture</b>, et c'est important de le dire plutôt que de l'essayer. Le modèle est un
 * <b>singleton partagé par le rendu de l'espèce</b> ({@code LivingEntityRenderer.model}) : il est
 * posé juste avant d'être dessiné, et il porte encore la pose de la vache <em>précédente</em>. Sauter
 * {@code setupAnim} ne figerait donc pas la vache — il la dessinerait <b>dans la pose d'une autre</b>.
 * Le défaut serait bien plus visible que ce qu'on cherchait à économiser.
 *
 * <p>Pour la même raison, il n'y a pas d'instanciation matérielle possible : dix mille vaches sont
 * dix mille poses différentes écrites successivement dans un seul modèle. Le jeu regroupe bien les
 * soumissions par {@code RenderType} — c'est du regroupement d'appels de dessin, pas de
 * l'instanciation — et chaque sommet est réémis par le processeur à chaque image.
 *
 * <h2>Le seul levier qui reste, donc, est de ne pas extraire</h2>
 *
 * <p>C'est déjà la thèse de {@link Shroud} : ce qu'un mur cache ne coûte rien. Le voile ne peut rien
 * contre dix mille vaches <b>à découvert</b>, et c'est précisément le cas que l'utilisateur décrit.
 *
 * <p>L'horizon est le pendant du gradient serveur, appliqué à l'image : <b>quand la foule dépasse ce
 * qu'une image peut porter, on rapproche la limite de préparation jusqu'à revenir dans le budget</b>.
 * Les créatures les plus lointaines cessent d'être préparées ; les proches ne changent pas.
 *
 * <h2>Quand il ne fait rien, et c'est le cas normal</h2>
 *
 * <p>En deçà du budget, l'horizon vaut l'infini et ce module se résume à un compteur et une
 * comparaison. Un joueur qui a trente vaches autour de lui ne verra jamais ce code agir — c'est la
 * quatrième garantie du mod, celle qui commande les trois autres.
 *
 * <h2>Ce qui n'est jamais escamoté</h2>
 *
 * <p>L'horizon ne descend pas sous {@link #FLOOR}. Trente-deux blocs : au-delà de la portée
 * d'interaction, au-delà de ce qu'on suit du regard en combat, et bien au-delà des seize blocs où le
 * jeu place déjà ses propres coupures. Une créature avec laquelle on peut interagir ne disparaît
 * donc jamais, quel que soit le nombre de ses congénères.
 *
 * <h2>Et ce qui n'est pas prouvé</h2>
 *
 * <p><b>Ce module n'a pas été mesuré.</b> Le dépôt s'interdit de lancer un client, et
 * {@code lab/Glass} attend toujours quelqu'un qui le puisse. Le raisonnement ci-dessus est tiré de la
 * lecture des sources de la 26.2 ; le gain, lui, n'est pas établi. Conformément à la règle du projet,
 * <b>un module non mesuré n'existe pas</b> — celui-ci est donc <b>éteint par défaut</b>, et il le
 * restera tant qu'une mesure n'aura pas été produite.
 */
public final class Horizon {
    /**
     * Créatures préparées au-delà desquelles on commence à rapprocher l'horizon.
     *
     * <p>Quinze cents : de l'ordre de ce qu'une image porte sans peine, et bien au-delà de ce qu'un
     * joueur distingue. Le chiffre n'est pas mesuré — voir la note de classe — il est choisi pour
     * être franchement au-dessus du cas ordinaire, afin que le module dorme chez presque tout le
     * monde.
     */
    private static final int BUDGET = 1500;

    /** L'horizon ne descend jamais en deçà, en blocs. Voir la note de classe. */
    private static final double FLOOR = 32d;

    /** Au-delà, l'horizon ne contraint plus rien : c'est la valeur « aucune limite ». */
    private static final double OPEN = 1024d;

    /**
     * Vitesse à laquelle l'horizon se déplace, par image.
     *
     * <h2>Pourquoi il ne saute pas</h2>
     *
     * <p>Un horizon recalculé sèchement à chaque image oscillerait : on coupe, le compte retombe sous
     * le budget, on rouvre, le compte repasse au-dessus. Les créatures de la frange
     * <b>clignoteraient</b> — un défaut bien plus désagréable que celui qu'on corrige.
     *
     * <p>Il se déplace donc d'un vingtième de l'écart par image, soit environ un tiers de seconde
     * pour absorber un changement. C'est trop lent pour se voir, et assez rapide pour suivre un
     * joueur qui entre dans une ferme.
     */
    private static final double EASING = 0.05d;

    /** Créatures acceptées pendant l'image en cours. */
    private static int accepted;

    /** Créatures acceptées pendant l'image précédente — c'est elle qui décide. */
    private static int lastFrame;

    /** L'horizon courant, en blocs. */
    private static double radius = OPEN;

    /** Créatures écartées depuis le démarrage, pour que le rapport puisse dire s'il a agi. */
    private static long dropped;

    private Horizon() {}

    /**
     * Ouvre une image : on solde la précédente et l'on rapproche ou éloigne l'horizon.
     *
     * <p>Appelé depuis le même point que {@link Shroud#openFrame}, c'est-à-dire en tête de
     * l'extraction du monde.
     */
    public static void openFrame() {
        lastFrame = accepted;
        accepted = 0;

        double wanted;
        if (lastFrame <= BUDGET) {
            // Sous le budget : on rouvre. Le module doit savoir disparaître quand la foule se
            // disperse, sans quoi une ferme visitée une fois brimerait l'affichage pour le reste de
            // la partie.
            wanted = OPEN;
        } else {
            // La surface d'un disque croît comme le carré de son rayon : pour diviser par deux le
            // nombre de créatures d'un troupeau réparti, il faut diviser le rayon par racine de deux.
            // C'est la correction juste, et elle évite de tâtonner image après image.
            wanted = Math.max(FLOOR, radius * Math.sqrt(BUDGET / (double) lastFrame));
        }
        radius += (wanted - radius) * EASING;
        if (radius > OPEN) {
            radius = OPEN;
        }
    }

    /**
     * Cette créature est-elle au-delà de l'horizon ?
     *
     * <p>Appelée après le test de champ de vision du jeu et après le voile : on ne compte donc que ce
     * qui allait réellement être préparé, et le compteur qui pilote l'horizon décrit bien la charge
     * de l'image.
     *
     * @return vrai si la créature doit être écartée
     */
    public static boolean beyond(Entity entity, double camX, double camY, double camZ) {
        if (radius >= OPEN) {
            accepted++;
            return false;
        }
        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        if (dx * dx + dy * dy + dz * dz > radius * radius) {
            dropped++;
            return true;
        }
        accepted++;
        return false;
    }

    /** L'horizon courant, en blocs, pour le rapport et les cadrans. */
    public static double radius() {
        return radius;
    }

    /** Créatures préparées à l'image précédente. */
    public static int lastFrameCount() {
        return lastFrame;
    }

    /** Créatures écartées par l'horizon depuis le démarrage. */
    public static long dropped() {
        return dropped;
    }

    /** Remet l'horizon au large — au changement de monde, et pour les épreuves. */
    public static void reset() {
        accepted = 0;
        lastFrame = 0;
        radius = OPEN;
        dropped = 0L;
    }
}
