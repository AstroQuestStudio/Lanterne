package fr.clubcitrouille.lanterne.core;

/**
 * L'interrupteur, et pourquoi il est le module le plus important du mod.
 *
 * <h2>Deux erreurs en une heure</h2>
 *
 * <p>Ce mod a commencé par deux plans qu'un calcul a démolis avant la première ligne de code.
 *
 * <p><b>La compensation des ticks aléatoires.</b> L'idée : ne tirer qu'une fois sur seize, mais
 * seize fois plus. La loi est préservée — c'est exact — et le gain est <b>nul</b> : quatre cent
 * quarante et un chunks fois vingt-quatre sections fois trois tirages, avant comme après. On aurait
 * préservé le comportement sans économiser une milliseconde.
 *
 * <p><b>Le ralentissement des entonnoirs.</b> L'idée : leur appliquer le même gradient qu'aux
 * entités. Sauf qu'un entonnoir tické une fois sur seize transfère seize fois moins d'objets, et que
 * les chunks concernés sont dans le rayon de simulation — ce sont donc des fermes <em>en marche</em>.
 * On ne les aurait pas optimisées, on les aurait cassées.
 *
 * <p>Les deux idées étaient séduisantes, défendables, et fausses. Ce qui les a écartées n'est pas
 * l'expérience ni le flair : c'est une multiplication posée avant de coder. <b>Et ce qui vaut pour
 * un plan vaut pour un résultat.</b>
 *
 * <h2>Un mod d'optimisation qui ne se compare pas ne prouve rien</h2>
 *
 * <p>« Ça a l'air plus fluide » n'est pas une mesure. Sur une instance qui compte vingt-deux mods
 * d'optimisation, personne ne peut dire lequel agit — ni si l'un d'eux coûte plus qu'il ne rapporte.
 * La seule preuve recevable est la comparaison du même instant avec et sans, sur la même charge.
 *
 * <p>D'où cet interrupteur. Il n'est pas un réglage de confort : il est l'<b>instrument de mesure</b>.
 * Sans lui, tout ce que ce mod affirmerait serait une opinion — y compris quand il aurait raison.
 */
public final class Settings {
    private static boolean enabled = true;

    private Settings() {}

    /**
     * Le mod agit-il ?
     *
     * <p>Éteint, <b>rien</b> n'est dégradé : le recensement continue de tourner pour que le rapport
     * reste lisible, mais aucune entité ne perd un tick. C'est ce qui rend la comparaison honnête —
     * on mesure deux fois le même monde, pas deux mondes différents.
     */
    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }
}
