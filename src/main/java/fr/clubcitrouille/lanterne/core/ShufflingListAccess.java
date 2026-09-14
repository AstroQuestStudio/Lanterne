package fr.clubcitrouille.lanterne.core;

/**
 * L'accès direct au contenu d'une {@code ShufflingList}, par indice.
 *
 * <h2>Pourquoi parcourir cette liste coûte, même sans stream</h2>
 *
 * <p>{@code ShufflingList} ne range pas ses éléments : elle range des {@code WeightedEntry} qui les
 * enveloppent, afin de leur attribuer un poids de tirage. Ses deux façons de la parcourir paient
 * toutes les deux ce déballage :
 *
 * <pre>
 * public Stream&lt;U&gt; stream() {
 *     return this.entries.stream().map(WeightedEntry::getData);   // deux pipelines imbriqués
 * }
 *
 * public Iterator&lt;U&gt; iterator() {
 *     return Iterators.transform(this.entries.iterator(), WeightedEntry::getData);   // Guava
 * }
 * </pre>
 *
 * <p>La première alloue un pipeline de flux <em>dans</em> un autre. La seconde — celle que notre
 * boucle {@code for} employait — est bien moins chère, mais alloue encore deux objets par appel :
 * l'itérateur de l'{@code ArrayList}, puis l'itérateur transformé de Guava qui l'enveloppe.
 *
 * <p>Or {@code entries} <b>est</b> une {@code ArrayList}. On peut donc demander l'élément numéro
 * <i>i</i> et ne rien allouer du tout.
 *
 * <h2>Pourquoi cette interface, et pas un transtypage</h2>
 *
 * <p>Le champ {@code entries} est {@code protected}, et {@code ShufflingList} vit dans un paquet de
 * Minecraft. Notre mixin sur {@code GateBehavior} y aura bien accès <em>à l'exécution</em>, une fois
 * fusionné dans sa classe cible qui est du même paquet — mais pas à la compilation, où il n'est
 * qu'une classe ordinaire de {@code fr.clubcitrouille.lanterne.mixin}.
 *
 * <p>L'interface franchit cette frontière : {@code ShufflingListMixin} l'implémente du côté où
 * l'accès est légal, et l'expose du côté où on en a besoin.
 *
 * <h2>Et pourquoi elle est ici plutôt que dans le paquet des mixins</h2>
 *
 * <p>Une interface de ce genre est chargée par le jeu <em>avant</em> que le moteur de mixins n'ait
 * fini son travail, puisque la classe transformée l'implémente. Si elle vivait dans le paquet des
 * mixins, le chargeur refuserait de la fournir — {@code IllegalClassLoadError}, avec une trace qui
 * désigne un tout autre endroit. La leçon a déjà été payée une fois, sur {@link MemoryPulseAccess}.
 *
 * @param <U> le type des éléments rangés dans la liste
 */
public interface ShufflingListAccess<U> {
    /** Le nombre d'éléments, sans allouer d'itérateur. */
    int lanterne$size();

    /** L'élément numéro {@code index}, déjà sorti de son enveloppe pondérée. */
    U lanterne$at(int index);
}
