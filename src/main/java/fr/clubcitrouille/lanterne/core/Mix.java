package fr.clubcitrouille.lanterne.core;

/**
 * Le brassage du nombre de renvoi des positions.
 *
 * <h2>Quatre-vingt-treize pour cent de collisions</h2>
 *
 * <p>Le nombre de renvoi de {@code Vec3i} — donc de {@code BlockPos}, qui en hérite — vaut en
 * vanilla {@code (y + z × 31) × 31 + x}. Cette formule a un défaut sévère sur les coordonnées d'un
 * monde Minecraft : {@code x} et {@code z} y parcourent des plages bien plus larges que {@code y},
 * et la multiplication par trente et un fait retomber d'innombrables positions distinctes sur le
 * même nombre.
 *
 * <p>Le compte est édifiant. Sur les 2 800 000 positions comprises entre {@code (-100, -20, -100)}
 * et {@code (100, 50, 100)}, la formule de vanilla ne produit que <b>194 571</b> valeurs
 * différentes : <b>93 % des positions entrent en collision</b>.
 *
 * <p>Une collision n'est pas une erreur — une table de hachage sait les résoudre — mais elle coûte.
 * Chaque collision transforme une recherche en parcours de liste, et le jeu est fait de tables
 * indexées par position : les entités de bloc d'un chunk, les points d'intérêt, les listes de
 * lumière à propager, les mises à jour planifiées. Toutes payent ces 93 %.
 *
 * <h2>Le brassage retenu</h2>
 *
 * <p>Multiplier par le nombre d'or mis à l'échelle de trente-deux bits, puis replier les bits de
 * poids fort sur ceux de poids faible. C'est le brassage classique de Fibonacci, et il porte
 * l'information de chaque bit d'entrée sur toute la largeur du résultat — ce que la multiplication
 * par trente et un ne fait pas.
 *
 * <p>Sur le même échantillon de 2 800 000 positions, il produit <b>2 868 471</b> valeurs
 * différentes, soit une par position, soit <b>aucune collision</b>.
 *
 * <h2>Pourquoi cette constante se retrouve ailleurs dans le mod</h2>
 *
 * <p>{@code Shroud.stagger} emploie la même pour disperser les échéances de son cache. Ce n'est pas
 * une coïncidence : la propriété recherchée est la même — répartir des entiers consécutifs le plus
 * uniformément possible — et il n'existe pas trente-six bonnes constantes pour cela.
 *
 * <p>Repris de <b>EfficientHashing</b> (ZZZank, LGPL-3.0). Voir {@code NOTICE.md}.
 */
public final class Mix {
    /** 2³² × φ, où φ = (√5 − 1) / 2. */
    private static final int PHI = 0x9E3779B9;

    private Mix() {}

    /** Le brassage de Fibonacci sur trente-deux bits. */
    public static int scatter(int value) {
        int product = value * PHI;
        return product ^ (product >>> 16);
    }

    /** Le nombre de renvoi d'une position, brassé sur ses trois coordonnées. */
    public static int position(int x, int y, int z) {
        return scatter(scatter(x) + y) + z;
    }
}
