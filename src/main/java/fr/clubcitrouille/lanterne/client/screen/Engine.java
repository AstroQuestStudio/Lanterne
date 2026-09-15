package fr.clubcitrouille.lanterne.client.screen;

import java.util.List;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le moteur de décodage : l'interface, les candidats, et le relevé de ce qui manque.
 *
 * <h2>Minecraft ne sait pas décoder une vidéo, et rien dans le JDK non plus</h2>
 *
 * <p>Ce n'est pas une lacune de ce mod, c'est l'état du monde. Ni LWJGL, ni la machine virtuelle, ni
 * le jeu ne contiennent de décodeur H.264, VP9 ou AV1. Tout ce qui affiche de la vidéo dans Minecraft
 * — sans exception, y compris les mods les plus connus — appelle du code natif venu d'ailleurs.
 *
 * <p>La question n'est donc pas « comment décoder sans dépendance » : elle n'a pas de réponse. Elle
 * est « laquelle, à quel prix, et sous quelle licence ». Le relevé complet est dans
 * {@code notes/projecteur.md} ; les deux candidats retenus sont {@link Lav} et {@link Chrome}, et
 * l'écart entre eux se compte en centaines de mébioctets.
 *
 * <h2>Pourquoi cette interface existe alors qu'aucun moteur ne la remplit</h2>
 *
 * <p>C'est la même réponse que {@code client.upscale.Deep} pour DLSS, et elle mérite d'être répétée
 * parce qu'elle est contre-intuitive : <b>le point de branchement, écrit et testable, vaut mieux
 * qu'une fausse fonctionnalité.</b>
 *
 * <p>Un mod qui affiche un écran, une barre de progression et un bouton « lecture » sans rien décoder
 * est indiscernable, à l'œil, d'un mod qui décode mal. Celui-ci dit ce qu'il fait. Tout ce qui ne
 * dépend pas du décodeur — les blocs, le multibloc, l'horloge partagée, les réglages, la liste
 * blanche, le rendu — est écrit, branché, et fonctionne. Le décodeur a une place, une signature, et
 * un verdict qui explique pourquoi elle est vide.
 *
 * <h2>Un ordre de préférence, et jamais d'écran noir</h2>
 *
 * <p>Les moteurs sont essayés dans l'ordre, le premier prêt gagne, et {@link Still} ferme la marche
 * en étant toujours prêt. Il ne décode rien et l'écrit sur l'écran. Un joueur sans moteur voit donc
 * une ardoise qui lui dit quoi installer, jamais un carré noir dont il conclura que le mod est cassé.
 */
public interface Engine {
    /** Le nom qu'on écrit dans l'interface. Court : il tient dans une ligne d'écran de réglages. */
    String label();

    /** Ce qui empêche ce moteur de servir, ou {@link Verdict#PRET}. */
    Verdict verdict();

    /**
     * Ouvre une source et rend de quoi en tirer des images.
     *
     * <p>N'est appelée que si {@link #verdict()} vaut {@link Verdict#PRET}. Un moteur qui n'est pas
     * prêt n'a pas à écrire cette méthode autrement qu'en rendant {@code null} : c'est ce que font
     * les deux qui existent ici, et c'est exactement ce qui les rend honnêtes.
     *
     * @return la bobine ouverte, ou {@code null} si l'ouverture échoue. Jamais d'exception vers
     *     l'appelant : l'appelant est le fil de rendu.
     */
    Reel open(String source);

    /** Ce qui manque à un moteur, dans l'ordre où la question se pose. */
    enum Verdict {
        /** Rien ne manque. Aucun moteur ne rend ceci dans l'état actuel du dépôt. */
        PRET("prêt"),
        /** Le joueur a demandé qu'aucun média distant ne soit chargé. Voir {@link Consent}. */
        SANS_CONSENTEMENT("refus du joueur"),
        /** Le mod compagnon qui porterait le décodeur n'est pas installé. */
        SANS_MOD("mod compagnon absent"),
        /** Les bibliothèques natives ne sont pas là. */
        SANS_BIBLIOTHEQUE("bibliothèque absente"),
        /** Tout est réuni côté machine, et le pont vers le natif n'existe pas dans ce dépôt. */
        SANS_PONT("pont natif non écrit");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    /**
     * Une source ouverte, dont on tire des images à la demande.
     *
     * <h2>« Donne-moi l'image de cet instant », et non « avance »</h2>
     *
     * <p>Toute la synchronisation tient dans cette différence. Un lecteur qu'on fait <em>avancer</em>
     * tient sa propre horloge, qui dérive de celle des autres joueurs, et il faut alors la corriger
     * en permanence. Un lecteur à qui l'on <em>demande</em> l'image d'une position n'a pas d'horloge
     * du tout : c'est {@link fr.clubcitrouille.lanterne.content.screen.Clock} qui la lui donne, et
     * elle est déjà la même pour tout le monde.
     *
     * <p>En pratique, un décodeur ne peut pas sauter à une position arbitraire à chaque image — il
     * faudrait relire depuis l'image-clé précédente. {@link #present} lui dit donc la position voulue
     * et le laisse s'en approcher par ses propres moyens ; {@link #drift()} dit ensuite de combien il
     * s'en est écarté, et {@code Clock.correct} décide si l'on rattrape ou si l'on saute.
     */
    interface Reel extends AutoCloseable {
        /**
         * Amène l'image la plus proche de {@code millis} dans {@code film}.
         *
         * <p>Appelée sur le fil de rendu, au plus une fois par image et par écran, et seulement si
         * {@link Gaze} a jugé que cet écran méritait une image — voir la cadence par distance.
         *
         * @return vrai si la texture a été écrite. Faux n'est pas une erreur : c'est « la même image
         *     qu'avant », ce qui est le cas le plus fréquent dès que la vidéo tourne moins vite que
         *     l'affichage.
         */
        boolean present(long millis, Film film);

        /** Durée totale, en millisecondes, ou zéro si inconnue — un direct, par exemple. */
        long duration();

        /** Écart entre la position demandée et celle réellement affichée. Voir {@code Clock.correct}. */
        long drift();

        /** Vitesse de lecture. Voir {@code Clock.Correction.Trim} pour ce que ce nombre corrige. */
        void rate(float multiplier);

        /** Volume, de zéro à un, déjà pondéré par la distance. Voir {@code Feed.loudness}. */
        void volume(float gain);

        @Override
        void close();
    }

    // --- Le choix -------------------------------------------------------------

    /**
     * Les moteurs, du plus souhaitable au dernier recours.
     *
     * <p>{@link Lav} avant {@link Chrome} : voir {@code notes/projecteur.md}, mais l'argument tient en
     * un chiffre — vingt-neuf mébioctets contre cent quatre-vingts. Un mod dont le sujet est la
     * performance ne fait pas démarrer un navigateur complet quand une bibliothèque de décodage
     * suffit.
     *
     * <p>{@link Still} est toujours dernier et toujours prêt. C'est ce qui garantit que
     * {@link #chosen()} ne rend jamais {@code null}, donc qu'aucun appelant n'a de cas nul à traiter,
     * donc qu'il n'existe aucun chemin vers l'écran noir.
     */
    List<Engine> CANDIDATES = List.of(Lav.INSTANCE, Chrome.INSTANCE, Still.INSTANCE);

    /**
     * Le moteur retenu pour cette partie.
     *
     * <p>Le choix est refait à chaque appel, et ce n'est pas un gaspillage : il tient en trois
     * lectures de champ, et il doit pouvoir changer en cours de partie. Le consentement du joueur est
     * un réglage qu'il bascule pendant qu'il regarde un écran — le figer au démarrage voudrait dire
     * qu'un refus ne prend effet qu'au prochain lancement du jeu, ce qui n'est pas un refus.
     */
    static Engine chosen() {
        for (Engine candidate : CANDIDATES) {
            if (candidate.verdict() == Verdict.PRET) {
                return candidate;
            }
        }
        return Still.INSTANCE;
    }

    /**
     * Écrit le relevé complet dans le journal, une fois.
     *
     * <p>Une fois, parce que la ligne ne change pas et que la répéter à chaque image la rendrait
     * illisible — le même soin que {@code Deep.announce}. Et complète, parce qu'un joueur qui se
     * demande pourquoi son écran n'affiche rien doit trouver la réponse entière dans son journal,
     * sans avoir à deviner quel moteur le mod a bien pu essayer.
     */
    static void announce() {
        if (Roll.announced) {
            return;
        }
        Roll.announced = true;
        StringBuilder note = new StringBuilder("[PROJECTION] moteurs de décodage :");
        for (Engine candidate : CANDIDATES) {
            note.append(" « ").append(candidate.label()).append(" » → ")
                    .append(candidate.verdict().label()).append(" ;");
        }
        Lanterne.LOG.info("{} retenu : « {} ».", note, chosen().label());
    }

    /** Le drapeau d'annonce. Une interface ne peut pas porter de champ mutable ; celle-ci le peut. */
    final class Roll {
        static boolean announced;

        private Roll() {}
    }
}
