package fr.clubcitrouille.lanterne.core;

/**
 * Les orbes d'expérience, fusionnées quarante fois plus souvent.
 *
 * <h2>Un frein posé par Mojang, et ce qu'il coûte</h2>
 *
 * <p>Deux orbes qui se touchent peuvent fusionner en une seule qui porte leur somme. Le jeu le fait
 * déjà — mais il s'en empêche presque toujours :
 *
 * <pre>
 * private static boolean canMerge(ExperienceOrb orb, int id, int value) {
 *     return !orb.isRemoved()
 *         &amp;&amp; (orb.getId() - id) % 40 == 0     // ← une orbe sur quarante
 *         &amp;&amp; orb.getValue() == value;
 * }
 * </pre>
 *
 * <p>La condition du milieu n'a aucun sens de jeu : elle compare des <b>identifiants d'entité</b>,
 * c'est-à-dire des numéros d'ordre de création. Elle n'est là que pour brider le coût de la fusion —
 * une orbe ne tente de se joindre qu'à un quarantième de ses voisines, choisies par une arithmétique
 * qui ne veut rien dire.
 *
 * <p>Le résultat est qu'une ferme à expérience accumule des milliers d'orbes qui <em>pourraient</em>
 * fusionner et ne le font pas. Chacune tick, cherche des collisions, cherche un joueur, cherche des
 * voisines — et le coût croît avec leur nombre.
 *
 * <h2>Ce qu'on retire, et ce qu'on garde</h2>
 *
 * <p>Le frein sur l'identifiant s'en va : une orbe peut désormais fusionner avec n'importe quelle
 * voisine éligible.
 *
 * <p>La condition d'<b>égalité de valeur</b> reste, et elle n'est pas négociable. Une orbe retient
 * deux nombres : la valeur d'une unité, et le nombre d'unités. La fusion additionne les secondes :
 *
 * <pre>
 * this.count = this.count + orb.count;
 * </pre>
 *
 * <p>Fusionner deux orbes de valeurs différentes ferait donc porter à toutes les unités la valeur de
 * la première — et <b>créerait ou détruirait de l'expérience</b>. C'est précisément le genre de
 * raccourci que ce mod refuse : un joueur qui perd de l'expérience ne saura jamais pourquoi, et il
 * aura raison de s'en plaindre.
 *
 * <p>En pratique, la restriction ne coûte presque rien : une ferme produit des orbes de même valeur,
 * et c'est justement le cas où elles s'accumulent.
 *
 * <h2>Ce que cela change pour le joueur</h2>
 *
 * <p>Il voit moins d'orbes et les ramasse plus vite, pour exactement la même expérience. C'est
 * observable, et c'est une amélioration du jeu autant que de la performance — un tapis de mille orbes
 * qui met dix secondes à être aspiré est une gêne, pas une fonctionnalité.
 */
public final class Clump {
    /** Fusions autorisées que vanilla aurait refusées. */
    private static long freed;

    private Clump() {}

    public static void noteFreed() {
        freed++;
    }

    public static long freed() {
        return freed;
    }

    public static void reset() {
        freed = 0L;
    }
}
