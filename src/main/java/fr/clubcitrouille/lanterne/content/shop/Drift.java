package fr.clubcitrouille.lanterne.content.shop;

/**
 * La dérive : les prix suivent ce que les joueurs échangent réellement.
 *
 * <h2>Le problème qu'elle résout</h2>
 *
 * <p>Une boutique administrateur à prix fixes finit toujours de la même façon. Les joueurs trouvent
 * l'article dont le prix de rachat est le plus généreux par rapport au coût de le produire — presque
 * toujours un objet de ferme automatique — et l'économie du serveur devient cet objet. Tout le reste
 * du catalogue cesse d'exister, et l'administrateur passe ses soirées à corriger un prix à la main.
 *
 * <p>La dérive rend ce travail automatique, en une phrase : <b>ce que tout le monde vend perd de la
 * valeur, ce que tout le monde achète en gagne</b>. La ferme à melons finit par payer trois fois
 * moins, sans que personne ait à l'annoncer, et un autre article redevient intéressant.
 *
 * <h2>La formule, et ce qui la borne</h2>
 *
 * <p>Soit {@code v} le volume net d'un article — unités achetées à la boutique moins unités qui lui
 * ont été vendues —, {@code A} l'amplitude et {@code E} l'élasticité. Le facteur appliqué aux deux
 * prix est :
 *
 * <pre>    f = 1 + A · tanh(v / E)</pre>
 *
 * <p>{@code tanh} vaut zéro en zéro, croît doucement, et tend vers ±1. Le facteur reste donc dans
 * {@code [1−A, 1+A]} quel que soit {@code v} — y compris si un joueur achète un milliard
 * d'exemplaires. Ce n'est pas une limite qu'on écrête après coup : c'est une propriété de la
 * fonction, donc une garantie qui ne peut pas être oubliée dans une branche du code.
 *
 * <p><b>Une précision honnête sur cette borne.</b> En mathématiques, {@code tanh} n'atteint jamais
 * ±1 et l'intervalle serait ouvert. En arithmétique flottante, {@code Math.tanh} rend
 * <em>exactement</em> 1,0 au-delà de {@code |x|} voisin de 19, et le facteur vaut alors exactement
 * 1,5 avec les réglages par défaut. La borne est donc <b>atteinte, jamais dépassée</b> : c'est ce
 * qui a été vérifié hors du jeu sur dix-sept volumes, des plus petits jusqu'à
 * {@code Long.MAX_VALUE / 4}. Rien ne change pour l'équilibrage — la promesse était « au plus
 * ±50 % », elle est tenue — mais l'écrire faux aurait fait douter de tout le reste.
 *
 * <p>Pourquoi {@code tanh} plutôt qu'une droite écrêtée : parce qu'une droite écrêtée a un coude. Un
 * article qui atteint la borne cesse brusquement de réagir, et l'on voit apparaître dans le jeu des
 * « murs » de prix que les joueurs apprennent à viser. {@code tanh} n'a pas de coude ; le rendement
 * décroît progressivement, ce qui décourage le volume sans jamais le bloquer.
 *
 * <h2>Le même facteur des deux côtés — l'invariant qui rend la dérive sûre</h2>
 *
 * <p>Le prix d'achat et le prix de rachat sont multipliés par le <b>même</b> {@code f}. Leur rapport
 * ne change donc jamais, et la marge garantie à l'enregistrement du catalogue — voir {@link Stall} —
 * reste garantie pour toujours.
 *
 * <p>C'est délibérément moins « réaliste » qu'un écart qui s'ouvrirait et se refermerait selon la
 * demande, et c'est pour une bonne raison : deux facteurs indépendants peuvent se croiser. Le jour où
 * le prix de rachat d'un article dépasse son prix d'achat, un joueur s'en aperçoit en quelques
 * minutes, et l'économie du serveur est morte avant le lendemain. Un système qui peut créer de
 * l'argent à l'infini n'est pas un système avec un défaut, c'est un système sans économie.
 *
 * <p>Il reste une marge de sécurité, malgré tout : {@link #sell} refuse de rendre un prix de rachat
 * supérieur ou égal au prix d'achat courant, même si l'arrondi l'y amenait. Les deux protections font
 * double emploi, et c'est voulu — la seconde coûte une comparaison.
 */
public final class Drift {
    private Drift() {}

    /**
     * Le facteur du moment pour un volume donné.
     *
     * <p>{@code Math.tanh} et non {@code StrictMath.tanh} : le calcul n'a lieu que sur le serveur, son
     * résultat est envoyé au client tout fait, et rien ne compare jamais deux machines. Exiger la
     * reproductibilité stricte n'achèterait donc rien.
     */
    public static double factor(long volume) {
        if (!Tariff.drift() || volume == 0L) {
            return 1.0;
        }
        double amplitude = Tariff.amplitude() / 100.0;
        return 1.0 + amplitude * Math.tanh((double) volume / Tariff.elasticity());
    }

    /** Le prix d'achat du moment, en centimes. Jamais nul : un article gratuit n'est pas un article. */
    public static long buy(Offer offer, long volume) {
        long price = Math.round(offer.buy() * factor(volume));
        return Math.max(1L, Math.min(Coin.PRICE_CEILING, price));
    }

    /**
     * Le prix de rachat du moment, en centimes. Zéro si l'article n'est pas racheté.
     *
     * <p>Le plafond à {@code buy − 1} est la seconde protection décrite dans l'en-tête. Il n'a
     * d'effet que sur un article dont l'administrateur aurait fixé une marge nulle et dont les deux
     * arrondis tomberaient du même côté — un cas rare, jamais impossible.
     */
    public static long sell(Offer offer, long volume) {
        if (!offer.bought() || !Tariff.buyback()) {
            return 0L;
        }
        long price = Math.round(offer.sell() * factor(volume));
        price = Math.max(1L, Math.min(Coin.PRICE_CEILING, price));
        return Math.max(0L, Math.min(price, buy(offer, volume) - 1L));
    }

    /**
     * L'écart au prix d'ancrage, en pour mille : ce que la flèche de l'écran montre.
     *
     * <p>Pour mille et non pour cent : à cinquante pour cent d'amplitude, un pour cent entier est un
     * pas grossier, et l'on verrait une flèche immobile pendant des dizaines de transactions.
     */
    public static int trend(long volume) {
        double away = (factor(volume) - 1.0) * 1000.0;
        return (int) Math.max(-1000.0, Math.min(1000.0, Math.round(away)));
    }
}
