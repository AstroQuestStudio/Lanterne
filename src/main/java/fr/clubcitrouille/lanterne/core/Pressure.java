package fr.clubcitrouille.lanterne.core;

/**
 * La pression, en un seul endroit.
 *
 * <h2>Pourquoi une classe pour une ligne</h2>
 *
 * <p>La pression décide du rayon de la zone franche, c'est-à-dire de la distance en deçà de laquelle
 * le mod promet de ne rien dégrader. C'est la garantie qui rend tout le reste acceptable.
 *
 * <p>Or cette pression se lisait à deux endroits, et <b>pas de la même façon</b> :
 *
 * <pre>
 * EntityThrottle : max(TickBudget.pressure(), rationing ? Rationing.pressure() : 0)
 * MobMixin       :     TickBudget.pressure()
 * </pre>
 *
 * <p>Les deux nombres pouvaient différer. La zone franche n'avait donc pas le même rayon selon qui
 * la consultait : une créature pouvait être « proche » pour le tour de simulation et « lointaine »
 * pour le tour des objectifs, dans le même tick. Une garantie qui existe en deux exemplaires n'en
 * est pas une — elle est la plus faible des deux, sans que personne ne le sache.
 *
 * <p>C'est la même leçon que le recensement des distances vient de donner : une garantie ne vaut que
 * ce que valent ses entrées, et une entrée recopiée finit toujours par diverger de son original.
 *
 * <h2>Les deux mesures, et pourquoi c'est le maximum qui gagne</h2>
 *
 * <p><b>{@link TickBudget}</b> mesure le tick <em>précédent</em> : lissé, lent, il dit si le serveur
 * tient la cadence sur la durée. <b>{@link Rationing}</b> mesure le tick <em>en cours</em> :
 * immédiat, il voit un pic avant qu'il ne devienne un à-coup.
 *
 * <p>On retient la plus forte. Prendre la moyenne reviendrait à attendre que la mesure lente confirme
 * la rapide, c'est-à-dire à réagir une seconde trop tard — et une seconde de retard sur un pic, c'est
 * un à-coup que le joueur a déjà senti.
 */
public final class Pressure {
    private Pressure() {}

    /**
     * La sévérité demandée, de 0 à 1.
     *
     * <p>Zéro : le serveur respire, rien n'est dégradé au-delà de ce que la distance impose déjà.
     * Un : il ne tient plus ses ticks, et la zone franche se rétrécit jusqu'à son plancher.
     */
    public static double now() {
        double budget = TickBudget.pressure();
        if (!Settings.rationing()) {
            return budget;
        }
        return Math.max(budget, Rationing.pressure());
    }
}
