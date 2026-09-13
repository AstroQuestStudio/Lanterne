package fr.clubcitrouille.lanterne.core;

/**
 * Les objets au sol : la première optimisation de ce mod qui ne concède rien.
 *
 * <h2>Ce qu'un objet posé par terre coûte réellement</h2>
 *
 * <p>Voici ce que le jeu fait, vingt fois par seconde, pour une pile de pierre posée sur un plancher
 * depuis trois minutes :
 *
 * <pre>
 * this.noPhysics = !this.level().noCollision(this, this.getBoundingBox().deflate(1.0E-7));
 * </pre>
 *
 * <p>Une requête de collision <b>complète</b> — blocs et entités — pour établir ce qui n'a pas changé
 * depuis trois minutes. Puis, un tick sur quatre, un {@code move()} entier avec sa résolution de
 * collisions et ses effets de blocs traversés. Puis {@code baseTick()}, qui cherche un portail, mesure
 * les fluides de sa boîte englobante, recalcule s'il nage, vérifie s'il est sous le monde. Puis, tous
 * les quarante ticks, une recherche spatiale de voisins fusionnables.
 *
 * <p>Le résultat de tout cela est connu d'avance et il est toujours le même : <b>rien</b>. Un sol de
 * dix mille objets paie ce rien dix mille fois par tick.
 *
 * <h2>Pourquoi ce n'est pas une dégradation</h2>
 *
 * <p>Tout ce que ce mod fait par ailleurs est un compromis assumé : une vache réfléchit moins souvent,
 * un troupeau se bouscule moins finement, et l'on défend ces choix parce que personne ne peut les
 * voir. Ici, il n'y a rien à défendre. On ne ralentit pas l'objet, <b>on cesse de recalculer une
 * réponse déjà connue</b>. Le comportement observable est identique, pas « indiscernable » : identique.
 *
 * <p>Ce qui suppose de démontrer trois choses, et la démonstration tient toute entière dans le code du
 * jeu.
 *
 * <h3>Un. L'objet reste ramassable</h3>
 *
 * <p>{@code Player.java:503} : {@code entity.playerTouch(this)}. C'est le <b>joueur</b> qui touche
 * l'objet, jamais l'inverse. Un objet endormi est donc ramassé exactement comme avant, puisqu'il n'a
 * jamais eu son mot à dire.
 *
 * <h3>Deux. La trémie l'aspire toujours</h3>
 *
 * <p>Même structure : c'est la trémie qui cherche les objets au-dessus d'elle. Une explosion, de même,
 * cherche les entités dans son rayon. <b>Tout ce qui agit sur un objet au sol vient le chercher</b> —
 * il n'est l'auteur d'aucune de ses interactions.
 *
 * <h3>Trois. Il disparaît au tick exact</h3>
 *
 * <p>C'est le seul point où l'objet agit sur lui-même : il compte son âge et disparaît à cinq minutes.
 * On continue donc de compter — c'est une addition — et l'on garde le crochet de NeoForge qui permet
 * aux mods de prolonger la durée de vie. Le sommeil ne touche pas à l'horloge, seulement au calcul.
 *
 * <h2>Le réveil, qui est le vrai sujet</h2>
 *
 * <p>Tout cela ne vaut que <b>tant que rien ne change autour</b>. Si l'on mine le bloc porteur, l'objet
 * doit tomber ; si de l'eau arrive, il doit dériver. C'est à quoi sert {@link Churn} : chaque section
 * de chunk compte ses changements de bloc, le dormeur retient la valeur vue en s'endormant, et tout
 * écart le réveille dans le tick même — pas au prochain réexamen, dans le tick même.
 *
 * <p>Trois autres réveils complètent celui-là :
 *
 * <ul>
 *   <li><b>la vitesse</b> — si quoi que ce soit a poussé l'objet, y compris un mod tiers dont on ne
 *       sait rien, il ne dort plus. Trois lectures de nombre à virgule, et toute la compatibilité qui
 *       va avec ;</li>
 *   <li><b>le crochet des objets de mods</b> — {@code getItem().onEntityItemUpdate(this)} continue
 *       d'être appelé à chaque tick, endormi ou non. Un objet modé qui a un comportement propre le
 *       garde entièrement ;</li>
 *   <li><b>un réveil de sûreté</b>, une fois toutes les dix secondes, décalé par identifiant. Il ne
 *       corrige aucun cas connu : il existe pour les cas <em>inconnus</em>. Le raisonnement ci-dessus
 *       peut avoir un trou qu'on n'a pas vu, et ce projet a déjà eu tort quatre fois avec autant
 *       d'assurance. Un réveil sur deux cents coûte un demi pour cent du gain et borne toute erreur
 *       future à dix secondes au lieu de l'éternité.</li>
 * </ul>
 *
 * <h2>Et la fusion ?</h2>
 *
 * <p>C'est la seule chose qu'un dormeur cesse de faire. Elle est pourtant préservée, pour deux raisons
 * qui se complètent.
 *
 * <p>D'abord, un objet ne s'endort qu'après <b>quarante-cinq ticks</b> d'immobilité — choisis pour
 * dépasser les quarante ticks de l'intervalle de fusion au repos. Chaque objet a donc tenté de
 * fusionner au moins une fois avant de dormir.
 *
 * <p>Ensuite, et c'est l'essentiel : quand un nouvel objet arrive à côté d'un dormeur, c'est
 * l'<b>arrivant</b> qui cherche ses voisins, et il trouve le dormeur comme n'importe quelle autre
 * entité. La fusion a lieu. Le seul cas réellement perdu serait celui de deux dormeurs fusionnables
 * qui ne se seraient jamais rencontrés éveillés — c'est-à-dire aucun.
 *
 * <p>Et les gros tas de serveur, ceux qui motivent tout ceci, sont faits de piles pleines :
 * {@code isMergable()} exige {@code count < maxStackSize}, donc ils ne fusionnaient déjà pas.
 */
public final class Litter {
    /**
     * Ticks d'immobilité avant de s'endormir.
     *
     * <p>Quarante-cinq, pour passer l'intervalle de fusion de quarante ticks. Un objet a ainsi tenté
     * de fusionner avant de dormir, et l'on n'a pas à se demander si le sommeil l'en a privé.
     */
    public static final int SETTLE = 45;

    /**
     * Un réveil complet sur combien, par sûreté.
     *
     * <p>Deux cents ticks, soit dix secondes. Aucun cas connu ne l'exige — il est là pour ceux qu'on
     * n'a pas vus.
     */
    public static final int SAFETY = 200;

    /** Vitesse en deçà de laquelle un objet est tenu pour immobile, au carré. */
    public static final double STILL_SQR = 1.0E-6d;

    private static long asleep;
    private static long awoken;

    private Litter() {}

    public static void slept() {
        asleep++;
    }

    public static void woke() {
        awoken++;
    }

    /** Objets endormis depuis le démarrage, pour le rapport. */
    public static long sleepCount() {
        return asleep;
    }

    public static long wakeCount() {
        return awoken;
    }

    public static void reset() {
        asleep = 0;
        awoken = 0;
    }
}
