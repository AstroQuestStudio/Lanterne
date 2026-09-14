package fr.clubcitrouille.lanterne.core;

import java.util.AbstractList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * Une liste dont les éléments s'allument et s'éteignent sans jamais bouger de place.
 *
 * <h2>Le problème qu'elle résout</h2>
 *
 * <p>Le cerveau d'une créature doit répondre, à chaque tick, à la question « quels comportements
 * tournent en ce moment ? ». Vanilla y répond en <b>reconstruisant la réponse</b> : il alloue une
 * liste, parcourt les trois niveaux de tables imbriquées qui rangent les comportements, teste le
 * statut de chacun, et garde ceux qui tournent. Le résultat est jeté à la fin du tick, puis refait
 * au suivant.
 *
 * <p>Or la réponse ne change presque jamais. Un comportement démarre, tient plusieurs secondes,
 * s'arrête. Entre deux ticks, l'immense majorité des cerveaux rendrait exactement la même liste.
 *
 * <h2>Le principe</h2>
 *
 * <p>On range <b>tous</b> les comportements une fois pour toutes, dans l'ordre, et l'on tient à côté
 * un {@link BitSet} qui dit lesquels sont visibles. Démarrer un comportement, c'est allumer un bit ;
 * l'arrêter, c'est l'éteindre. Parcourir la liste, c'est sauter de bit allumé en bit allumé —
 * {@code nextSetBit} le fait en une instruction machine par mot de soixante-quatre bits.
 *
 * <p>Plus d'allocation, plus de parcours de tables, et un coût par changement au lieu d'un coût par
 * tick.
 *
 * <h2>Pourquoi on peut la modifier pendant qu'on la parcourt</h2>
 *
 * <p>C'est indispensable ici : un comportement qui s'arrête pendant son propre tick doit disparaître
 * de la liste, et vanilla itère justement cette liste au même moment. Une {@code ArrayList}
 * ordinaire lèverait {@code ConcurrentModificationException}.
 *
 * <p>L'itérateur ci-dessous ne garde aucune photo : il redemande {@code nextSetBit} à chaque pas. Un
 * bit éteint <em>après</em> avoir été rendu ne le concerne plus ; un bit éteint plus loin sera
 * simplement sauté. Aucune position ne se décale, puisque rien ne bouge jamais dans le tableau.
 *
 * <h2>Ce qui n'a pas été porté, et pourquoi</h2>
 *
 * <p>La version d'origine sait aussi <b>retirer</b> un élément, ce qui l'oblige à laisser des trous
 * puis à compacter le tableau quand ils deviennent trop nombreux. Le cerveau n'en a pas besoin : ses
 * comportements sont posés à la construction de la créature et ne s'en vont plus — ils s'éteignent,
 * ce qui est précisément ce que le masque exprime. On ne porte pas un chemin qu'on ne prend pas, et
 * qu'on ne saurait donc pas éprouver.
 *
 * <p>Conséquence assumée : un appel extérieur à {@code remove} échouera bruyamment plutôt que de
 * traverser du code jamais exercé. La seule méthode du jeu qui expose cette liste est marquée
 * {@code @Deprecated @VisibleForDebug}.
 *
 * <p>Portée depuis Lithium (LGPL-3.0), que la licence GPL-3.0 de ce mod absorbe.
 *
 * @param <E> le type des éléments
 */
public final class MaskedList<E> extends AbstractList<E> {
    private final ObjectArrayList<E> all = new ObjectArrayList<>();

    private final BitSet visible = new BitSet();

    private final Object2IntOpenHashMap<E> index = new Object2IntOpenHashMap<>();

    public MaskedList() {
        this.index.defaultReturnValue(-1);
    }

    /** Ajoute l'élément s'il est inconnu, et fixe sa visibilité dans tous les cas. */
    public void addOrSet(E element, boolean shown) {
        int at = this.index.getInt(element);
        if (at == -1) {
            at = this.all.size();
            this.index.put(element, at);
            this.all.add(element);
        }
        this.visible.set(at, shown);
    }

    /**
     * Allume ou éteint un élément.
     *
     * <p>Un élément inconnu est ignoré en silence, et c'est voulu : les comportements imbriqués dans
     * un comportement composite démarrent et s'arrêtent eux aussi, sans jamais figurer dans la liste
     * du cerveau. Ils passeraient ici sans avoir rien à y faire.
     */
    public void setVisible(E element, boolean shown) {
        int at = this.index.getInt(element);
        if (at != -1) {
            this.visible.set(at, shown);
        }
    }

    /** Éteint tout, sans rien désallouer. */
    public void hideAll() {
        this.visible.clear();
    }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private int from;

            private int found = -1;

            @Override
            public boolean hasNext() {
                this.found = MaskedList.this.visible.nextSetBit(this.from);
                return this.found != -1;
            }

            @Override
            public E next() {
                int at = this.found;
                this.found = -1;
                this.from = at + 1;
                return MaskedList.this.all.get(at);
            }
        };
    }

    /**
     * Le parcours en flux, réécrit lui aussi.
     *
     * <p>Sans cela, {@code AbstractList} en fabriquerait un à partir de {@link #get(int)}, qui doit
     * compter les bits allumés depuis le début pour trouver le n-ième. Un flux sur cette liste
     * deviendrait quadratique — et nous serions allés chercher un gain pour en poser un pire.
     */
    @Override
    public Spliterator<E> spliterator() {
        return new Spliterators.AbstractSpliterator<E>(Long.MAX_VALUE,
                Spliterator.ORDERED | Spliterator.NONNULL) {
            private int from;

            @Override
            public boolean tryAdvance(Consumer<? super E> action) {
                int at = MaskedList.this.visible.nextSetBit(this.from);
                if (at == -1) {
                    return false;
                }
                this.from = at + 1;
                action.accept(MaskedList.this.all.get(at));
                return true;
            }
        };
    }

    @Override
    public E get(int position) {
        if (position < 0) {
            throw new IndexOutOfBoundsException(position);
        }
        int at = this.visible.nextSetBit(0);
        for (int skipped = 0; skipped < position && at != -1; skipped++) {
            at = this.visible.nextSetBit(at + 1);
        }
        if (at == -1) {
            throw new IndexOutOfBoundsException(position);
        }
        return this.all.get(at);
    }

    @Override
    public int size() {
        return this.visible.cardinality();
    }

    @Override
    public boolean isEmpty() {
        return this.visible.isEmpty();
    }
}
