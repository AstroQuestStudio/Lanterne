package fr.clubcitrouille.lanterne.client.screen;

/**
 * Le moteur qui ne décode rien, et qui le dit.
 *
 * <h2>Ce qu'un écran doit faire quand il ne peut rien faire</h2>
 *
 * <p>Trois comportements étaient possibles, et deux sont mauvais.
 *
 * <p><b>Ne rien afficher</b> laisse un cube noir. Le joueur conclut que son écran est cassé, ou que
 * sa source est mauvaise, et il essaiera dix liens avant de soupçonner qu'aucun décodeur n'existe.
 *
 * <p><b>Planter</b> — ou lever une exception depuis le fil de rendu — emporte l'image entière. C'est
 * la faute classique d'un branchement natif : on suppose que la bibliothèque est là, et le jour où
 * elle ne l'est pas c'est le jeu qui tombe, pas la fonctionnalité.
 *
 * <p><b>Afficher une ardoise</b> — un fond sobre, et une ligne qui dit ce qui manque. Le joueur sait
 * en une seconde que le bloc marche, que sa source est enregistrée, et que ce qui manque est un
 * décodeur. C'est le seul des trois qui ne fasse perdre de temps à personne.
 *
 * <h2>Il est toujours prêt, et c'est ce qui rend l'architecture sûre</h2>
 *
 * <p>Parce qu'il l'est, {@code Engine.chosen()} ne rend jamais {@code null}. Aucun appelant n'a donc
 * de cas nul à traiter, et il n'existe aucun chemin de code vers l'écran noir — non parce qu'on y a
 * pensé partout, mais parce qu'il n'y en a pas.
 */
public final class Still implements Engine {
    public static final Still INSTANCE = new Still();

    private Still() {}

    @Override
    public String label() {
        return "ardoise";
    }

    @Override
    public Verdict verdict() {
        return Verdict.PRET;
    }

    /**
     * N'ouvre rien, toujours.
     *
     * <p>Rendre {@code null} plutôt qu'une bobine muette est délibéré : le rendu distingue « pas de
     * bobine » de « bobine sans image », et seul le premier cas déclenche l'ardoise explicative. Une
     * bobine muette aurait donné un écran uni sans un mot, c'est-à-dire le comportement qu'on refuse.
     */
    @Override
    public Reel open(String source) {
        return null;
    }

    /**
     * La ligne à écrire sur l'ardoise.
     *
     * <p>Elle nomme ce qui manque plutôt que de s'excuser. « Aucun décodeur vidéo » est une
     * information ; « une erreur est survenue » n'en est pas une.
     */
    public static String slate() {
        Engine best = Engine.CANDIDATES.get(0);
        for (Engine candidate : Engine.CANDIDATES) {
            if (candidate != INSTANCE) {
                best = candidate;
                break;
            }
        }
        return switch (best.verdict()) {
            case SANS_CONSENTEMENT -> "Médias distants refusés — réglages du mod";
            case SANS_MOD -> "Aucun décodeur vidéo installé";
            case SANS_BIBLIOTHEQUE -> "Décodeur présent, bibliothèque absente";
            default -> "Aucun décodeur vidéo — voir notes/projecteur.md";
        };
    }
}
