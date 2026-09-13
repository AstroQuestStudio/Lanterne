package fr.clubcitrouille.lanterne.core;

/**
 * La fusion des objets au sol : huit mille caisses deviennent cent vingt-cinq piles.
 *
 * <h2>Le plafond que ce module ne peut pas franchir, et pourquoi il gagne quand même</h2>
 *
 * <p>Une orbe d'expérience n'a pas de limite : quatre mille orbes peuvent en devenir cinq. Un objet
 * au sol en a une — soixante-quatre — et huit mille cailloux ne feront jamais moins de cent
 * vingt-cinq piles. Le module est donc borné par construction.
 *
 * <p>Il reste que cent vingt-cinq n'est pas huit mille. Chaque objet au sol supprimé, c'est un tick
 * d'entité en moins, une recherche de collision en moins, une position à diffuser en moins. Le
 * plafond n'est pas une limite du gain : c'est le gain lui-même, et il vaut soixante-quatre.
 *
 * <h2>Les trois freins de vanilla, et le seul qu'il faut garder</h2>
 *
 * <p><b>Le premier est une portée.</b>
 *
 * <pre>
 * this.level().getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(0.5, 0.0, 0.5), …)
 * </pre>
 *
 * <p>Un demi-bloc à l'horizontale, et <b>zéro à la verticale</b>. Le second chiffre est le plus
 * coûteux des deux : deux objets empilés l'un sur l'autre — ce que produit toute ferme qui laisse
 * tomber dans un trou d'un bloc — ne se rejoignent <em>jamais</em>. Ils resteront deux entités
 * jusqu'à leur disparition, à dix centimètres l'un de l'autre.
 *
 * <p><b>Le deuxième est une fréquence.</b>
 *
 * <pre>
 * int rate = moved ? 2 : 40;
 * if (this.tickCount % rate == 0 &amp;&amp; …) this.mergeWithNeighbours();
 * </pre>
 *
 * <p>Un objet <b>immobile</b> ne cherche ses voisins que toutes les deux secondes. C'est exactement
 * l'inverse de ce qu'il faudrait : un objet immobile est un objet <em>posé</em>, donc un objet dont
 * les voisins sont posés aussi, donc le cas où la fusion a le plus de chances d'aboutir. Vanilla
 * cherche le plus souvent là où ça marche le moins.
 *
 * <p><b>Le troisième est un âge, et on le garde.</b>
 *
 * <pre>
 * return … &amp;&amp; this.age &lt; 6000 &amp;&amp; item.getCount() &lt; item.getMaxStackSize();
 * </pre>
 *
 * <p>Celui-là n'est pas un bridage de coût : c'est une <b>règle de jeu</b>. La fusion prend l'âge du
 * plus jeune —
 *
 * <pre>
 * toItem.age = Math.min(toItem.age, fromItem.age);
 * </pre>
 *
 * <p>— si bien qu'un objet sur le point de disparaître, fusionné avec un objet neuf, serait
 * ressuscité. Sans le plafond d'âge, il suffirait de jeter un caillou près d'un tas mourant pour le
 * rendre éternel. Ce frein-là reste intact, comme l'égalité de valeur est restée intacte pour les
 * orbes : <b>on retire ce qui bride le coût, jamais ce qui tient la règle</b>.
 *
 * <h2>Jusqu'où élargir, et pourquoi pas comme les orbes</h2>
 *
 * <p>Les orbes fusionnent sur quatre blocs, et personne ne le remarque : une orbe n'a pas
 * d'identité, on ne la suit pas des yeux. Un objet, si. Un joueur qui pose un objet au sol et le voit
 * disparaître pour réapparaître quatre blocs plus loin verrait un défaut, pas une optimisation.
 *
 * <p>Deux blocs à l'horizontale et un à la verticale : c'est la taille d'un tas laissé par une mort,
 * d'une fournée qui tombe d'un entonnoir, d'une récolte de ferme. Cela réunit ce qui est tombé
 * ensemble, et rien d'autre.
 */
public final class Gather {
    /** Portée horizontale de la fusion, en blocs. */
    public static final double SPREAD = 2.0d;

    /**
     * Portée verticale, en blocs.
     *
     * <p>Vanilla y met zéro. C'est le frein le plus coûteux du lot, et le moins visible : il empêche
     * deux objets empilés de se rejoindre alors qu'ils se touchent.
     */
    public static final double STACK = 1.0d;

    /**
     * Ticks entre deux recherches pour un objet immobile.
     *
     * <p>Vanilla en met quarante. Dix laisse encore vingt-quatre recherches par minute et par objet,
     * et rassemble le tas quatre fois plus tôt — donc supprime quatre fois plus tôt les entités qui
     * font le coût. Les deux effets vont en sens contraire et seule la mesure les départage.
     */
    public static final int SETTLED_RATE = 10;

    private static long merges;

    private Gather() {}

    /** Une fusion de plus. */
    public static void noteMerge() {
        merges++;
    }

    public static long merges() {
        return merges;
    }

    public static void reset() {
        merges = 0L;
    }
}
