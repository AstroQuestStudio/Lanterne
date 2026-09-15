package fr.clubcitrouille.lanterne.core;

/**
 * L'élastique : le joueur qu'on renvoie en arrière alors qu'il n'a rien fait.
 *
 * <h2>AVERTISSEMENT — CE MODULE NE REND RIEN PLUS RAPIDE</h2>
 *
 * <p>Il faut le dire avant toute chose, parce que le nom du fichier et la famille où il est rangé
 * laisseraient croire le contraire. <b>Ce module ne fait gagner aucune milliseconde, aucun TPS,
 * aucune image par seconde.</b> Un serveur qui rame ramera exactement autant après qu'avant.
 *
 * <p>Tout le reste de ce mod s'attaque à la <em>cause</em> : moins de travail par tick, moins
 * d'allocations, moins de chunks chargés en urgence. Celui-ci s'attaque au <em>symptôme</em>, et
 * seulement à l'un d'entre eux. C'est un pansement, il est assumé comme tel, et il est <b>éteint par
 * défaut</b> : on ne le pose qu'après avoir constaté que tout le reste ne suffisait pas.
 *
 * <p>Quiconque l'allumerait en espérant des TPS se tromperait de fichier.
 *
 * <h2>Le symptôme, et sa cause exacte</h2>
 *
 * <p>On court, et l'on se retrouve trois mètres en arrière. On contourne un angle, et l'on revient
 * d'où l'on vient. Ce n'est ni la latence ni le client : c'est le serveur qui <b>refuse</b> la
 * position annoncée et téléporte le joueur là où lui croit qu'il est.
 *
 * <p>Le refus vient de {@code ServerGamePacketListenerImpl.handleMovePlayer}, et il s'appuie sur
 * trois seuils. Voici le code du jeu, tel quel :
 *
 * <pre>
 * float metersPerTick = isFallFlying ? 300.0F : 100.0F;
 * if (movedDist - expectedDist &gt; metersPerTick * deltaPackets) { … teleport(…) ; return ; }
 * …
 * this.player.move(MoverType.PLAYER, new Vec3(xDist, yDist, zDist));
 * …
 * if (… movedDist &gt; 0.0625 …) { fail = true ; }
 * </pre>
 *
 * <p>Les deux mesures sont prises <b>entre deux ticks serveur</b> : {@code firstGood} est figé au
 * début du tick par {@code resetPosition()}, et tout ce que le client annonce d'ici au tick suivant
 * est jugé par rapport à lui.
 *
 * <p>Or le client, lui, ne ralentit pas. Il tourne à vingt pas par seconde quoi qu'il arrive. Si le
 * serveur met deux cents millisecondes à boucler un tick, le joueur a légitimement fait
 * <b>quatre pas</b> pendant que le serveur en attendait un — et il est jugé comme s'il n'en avait
 * fait qu'un.
 *
 * <p>Le second seuil est le plus traître. Après avoir accumulé le déplacement, le serveur l'applique
 * <b>d'un seul coup</b> : un unique appel à {@code move()} sur un delta quatre fois trop grand. Le
 * client, lui, l'avait parcouru en quatre petits pas. Un gros pas et quatre petits pas ne rencontrent
 * pas la même géométrie : le gros heurte le coin que les petits contournaient. L'écart qui en résulte
 * dépasse le quart de bloc, et le joueur repart en arrière.
 *
 * <p><b>C'est un faux positif pur.</b> Le joueur n'a pas triché ; le serveur a mesuré avec une règle
 * qui a rétréci.
 *
 * <h2>La correction, et pourquoi c'est un carré</h2>
 *
 * <p>Ces trois nombres — {@code 100.0F}, {@code 300.0F}, {@code 0.0625} — ont ceci de commun qu'ils
 * sont tous des <b>distances au carré</b> : {@code movedDist} vaut {@code x²+y²+z²}, jamais sa racine.
 * Le jeu compare des carrés pour s'épargner une racine carrée par paquet.
 *
 * <p>Un joueur légitime parcourt une distance <em>proportionnelle au temps</em>. Le carré de cette
 * distance croît donc comme le <b>carré</b> du temps. Si le serveur a mis quatre fois trop longtemps,
 * la distance légitime est quatre fois plus grande, et son carré <b>seize</b> fois plus grand.
 *
 * <p>D'où la loi unique de ce module : <b>on multiplie les trois seuils par le carré du retard
 * réellement constaté</b>. Ce n'est pas un réglage empirique qu'on aurait ajusté jusqu'à ce que la
 * gêne disparaisse ; c'est la seule mise à l'échelle qui soit homogène avec ce qu'on compare.
 *
 * <h2>Ce qui garantit qu'un serveur sain est vanilla au bit près</h2>
 *
 * <p>Le retard se mesure entre {@code resetPosition()} — l'instant exact où {@code firstGood} est
 * figé — et l'instant où le paquet est jugé. C'est très précisément la fenêtre pendant laquelle la
 * règle a rétréci, et rien d'autre.
 *
 * <p>Deux verrous rendent la garantie vérifiable :
 *
 * <ul>
 *   <li><b>Un plancher à un.</b> Un tick plus court que cinquante millisecondes ne resserre jamais
 *       les seuils. Ce module élargit, ou ne fait rien ; il ne durcit jamais vanilla.</li>
 *   <li><b>Une bande morte.</b> En deçà de {@value #DEAD_BAND} fois la durée nominale, on renvoie la
 *       constante d'origine <b>telle quelle</b>, sans la moindre opération flottante. Pas « environ
 *       vanilla » : le même {@code float}, le même {@code double}, le même bit.</li>
 * </ul>
 *
 * <p>La bande morte n'est pas une coquetterie. Sans elle, un tick à 50,3 ms produirait un facteur de
 * 1,012, et les seuils ne seraient <em>jamais</em> exactement ceux du jeu — une différence
 * indétectable en pratique, mais qui rendrait la promesse invérifiable. Une garantie qu'on ne peut
 * pas éprouver n'en est pas une, et ce dépôt a déjà payé cette leçon cinq fois.
 *
 * <h2>Pourquoi ce n'est pas une porte ouverte aux tricheurs</h2>
 *
 * <p>C'est la question qui prime sur le confort, car ces seuils servent aussi à refuser le vol et la
 * téléportation. Trois raisons, dans l'ordre d'importance :
 *
 * <p><b>1. La destination reste validée, indépendamment et sans condition.</b> C'est le point
 * essentiel, et il se lit dans le code du jeu : même lorsque {@code fail} vaut faux, l'acceptation
 * exige encore {@code !isEntityCollidingWithAnythingNew(level, player, oldAABB, targetX, targetY,
 * targetZ)}. Cette méthode reconstruit la boîte englobante <b>à la position annoncée</b> et la
 * confronte aux collisions du monde. Élargir {@code 0.0625} ne permet donc <b>pas</b> de traverser
 * un mur ni de finir dans un bloc : ce contrôle-là n'est pas touché, et il est le vrai garde-fou.
 * Ce qui passe en plus, c'est une coupe d'angle — bornée par le rayon élargi, et rien de plus.
 *
 * <p><b>2. La tolérance est indexée sur une mesure que le tricheur ne choisit pas.</b> Le retard est
 * lu sur l'horloge du serveur, jamais sur une affirmation du client. Un client qui mentirait, qui
 * inonderait le serveur de paquets ou qui prétendrait avoir du retard n'obtient rien : la fenêtre
 * est celle du serveur.
 *
 * <p><b>3. La garde anti-inondation de vanilla est laissée intacte.</b> Le jeu ramène
 * {@code deltaPackets} à un dès qu'il dépasse cinq — c'est sa punition du client bavard. Ce module
 * <b>n'y touche pas</b>, et n'en a pas besoin : le facteur de retard, qui croît avec le temps réel,
 * couvre à lui seul le cas du joueur honnête pris dans une pause du ramasse-miettes.
 *
 * <p>Restent les deux plafonds ci-dessous, qui bornent l'élargissement quoi qu'il arrive. Un serveur
 * qu'on ferait ramer exprès ne donne donc pas une tolérance illimitée.
 *
 * <h2>Ce que ce module ne corrige pas, et pourquoi</h2>
 *
 * <p><b>L'expulsion pour délai dépassé n'en est pas.</b> Le compte à rebours de
 * {@code ServerCommonPacketListenerImpl} s'appuie sur {@code Util.getMillis()} — l'horloge murale —
 * et non sur des ticks. Un serveur lent qui ticke encore ne produit aucun faux positif ; il faudrait
 * un gel de quinze secondes pleines, et aucun élargissement de seuil n'y changerait quoi que ce soit.
 *
 * <p><b>L'expulsion pour vol prolongé n'en est pas non plus.</b> {@code aboveGroundTickCount}
 * s'incrémente une fois par tick serveur : un serveur lent met <em>plus</em> de temps à déclencher ce
 * contrôle, jamais moins. Le retard joue ici en faveur du joueur.
 *
 * <p><b>Le temps de cassage d'un bloc est déjà traité ailleurs</b>, et mieux : voir
 * {@code MiningMixin}, qui compte la progression du minage à l'horloge réelle plutôt qu'en ticks
 * serveur. Le rejouer ici ferait compter deux fois.
 *
 * <p>Sur l'idée qu'un serveur en retard doive rendre au joueur le temps qu'il lui prend, voir
 * {@code NOTICE.md} : elle n'est pas de ce projet, mais l'implémentation, elle, l'est entièrement.
 */
public final class Elastique {
    /** Durée nominale d'un tick, en nanosecondes : vingt ticks par seconde. */
    private static final long NOMINAL_NANOS = 50_000_000L;

    /**
     * En deçà de ce retard, on rend la constante du jeu <b>sans y toucher</b>.
     *
     * <p>Une fenêtre de moins de soixante-quinze millisecondes, c'est un serveur au-dessus de treize
     * ticks par seconde : le client a envoyé un pas, peut-être deux, et vanilla les accepte déjà sans
     * difficulté. Il n'y a rien à corriger, et corriger quand même interdirait de prouver que rien
     * n'a été corrigé.
     */
    private static final double DEAD_BAND = 1.5d;

    /**
     * Plafond du facteur appliqué au contrôle de vitesse ({@code 100.0F} et {@code 300.0F}).
     *
     * <p>Généreux, et il peut l'être : ces deux seuils sont des contrôles <em>grossiers</em> — cent
     * blocs au carré, c'est déjà dix blocs en une fenêtre, soit trente fois ce qu'un sprinteur
     * parcourt. Et quoi qu'ils laissent passer, la destination reste confrontée aux collisions du
     * monde. Seize couvre un tick de huit cents millisecondes, c'est-à-dire la pause de ramasse-
     * miettes qu'un VPS à un seul cœur inflige sans prévenir.
     */
    private static final double CEILING_SPEED = 16d;

    /**
     * Plafond du facteur appliqué au contrôle de cohérence ({@code 0.0625}).
     *
     * <p>Bien plus serré que le précédent, et pour une raison précise : ce seuil-ci est le seul des
     * trois dont l'élargissement laisse passer quelque chose de réel — la coupe d'angle. Quatre
     * donne {@code 0.0625 × 16 = 1,0} bloc au carré, soit <b>un bloc</b> d'écart toléré, et
     * seulement tant que le tick dépasse deux cents millisecondes.
     *
     * <p>On aurait pu l'aligner sur le précédent et gagner un peu de confort en gel prolongé. Ce
     * n'est pas le bon arbitrage : le confort est le but de ce module, la correction du jeu est sa
     * contrainte, et une contrainte ne se négocie pas contre le but qu'elle encadre.
     */
    private static final double CEILING_RESIDUAL = 4d;

    /** Paquets de position jugés depuis la remise à zéro. */
    private static long samples;
    /** Ceux qui ont réellement bénéficié d'un élargissement — les autres étaient en bande morte. */
    private static long widened;
    /** Le pire retard observé, en multiples de la durée nominale d'un tick. */
    private static double worstLateness;
    /** Retours en arrière réellement subis, comptés par {@code ElastiqueMixin}. */
    private static long rollbacks;

    private Elastique() {}

    /**
     * Retire les dents du module sans l'éteindre.
     *
     * <h2>Pourquoi ce levier existe</h2>
     *
     * <p>Une épreuve qui ne peut pas échouer ne prouve rien, et ce dépôt en a rendu cinq « conforme »
     * sur une scène vide. {@code LANTERNE_BREAK_ELASTIQUE=1} laisse le module branché, compté et
     * journalisé, mais lui fait rendre un retard de un en toutes circonstances — c'est-à-dire le
     * comportement de vanilla.
     *
     * <p>L'épreuve {@code Amarre} doit alors <b>échouer</b> sur sa phase en charge. Si elle réussit
     * quand même, ce n'est pas le module qui est bon : c'est l'épreuve qui ne mesure rien.
     */
    private static final boolean TOOTHLESS = "1".equals(System.getenv("LANTERNE_BREAK_ELASTIQUE"));

    /** Vrai si le levier de sabotage est armé. Le banc l'annonce dans son en-tête. */
    public static boolean toothless() {
        return TOOTHLESS;
    }

    /**
     * Le retard de la fenêtre en cours, en multiples d'un tick nominal.
     *
     * <p>Rend exactement {@code 1.0} quand le serveur tient sa cadence — plancher inclus, de sorte
     * que ce module n'ait jamais l'occasion de resserrer ce que le jeu accorde.
     *
     * @param anchorNanos instant du dernier {@code resetPosition()}, en nanosecondes
     */
    public static double lateness(long anchorNanos) {
        if (TOOTHLESS || anchorNanos == 0L) {
            return 1d;
        }
        long frozen = System.nanoTime() - anchorNanos;
        if (frozen <= NOMINAL_NANOS) {
            return 1d;
        }
        return (double) frozen / NOMINAL_NANOS;
    }

    /**
     * Élargit un seuil de vitesse — les {@code 100.0F} et {@code 300.0F} du jeu.
     *
     * <p>Rend la valeur reçue <b>à l'identique</b> tant qu'on est en bande morte : c'est ce qui rend
     * la promesse « vanilla au bit près » vérifiable plutôt que plausible.
     */
    public static float widenSpeed(float vanilla, long anchorNanos) {
        double factor = factor(anchorNanos, CEILING_SPEED);
        return factor == 1d ? vanilla : (float) (vanilla * factor);
    }

    /**
     * Élargit le seuil de cohérence — le {@code 0.0625} du jeu.
     *
     * <p>Même garantie de bande morte, et un plafond plus serré : voir {@link #CEILING_RESIDUAL}.
     *
     * <h2>Pourquoi le comptage est ici, et nulle part ailleurs</h2>
     *
     * <p>Il fallait un point par lequel chaque mouvement jugé passe <b>une fois et une seule</b>.
     * Trois candidats ont été écartés :
     *
     * <ul>
     *   <li><b>L'entrée de {@code handleMovePlayer}.</b> La méthode commence par
     *       {@code PacketUtils.ensureRunningOnSameThread}, qui lève une exception pour se replanifier
     *       quand le paquet arrive sur le fil réseau. Une accroche en tête compterait donc <b>deux
     *       fois</b> chaque paquet : une fois sur le fil réseau, une fois sur celui du serveur.</li>
     *   <li><b>Le seuil de vitesse.</b> Il n'est consulté que si le contrôle est actif, et une seule
     *       de ses deux constantes est évaluée selon que le joueur vole ou non.</li>
     *   <li><b>Les deux seuils ensemble.</b> Un mouvement ordinaire les consulte l'un puis l'autre :
     *       le relevé serait faux d'un facteur deux, et ce genre d'erreur ne se découvre que le jour
     *       où l'on compare deux bancs entre eux.</li>
     * </ul>
     *
     * <p>Le seuil de cohérence, lui, est atteint par tout mouvement qui va jusqu'au bout de son
     * traitement, sur le fil du serveur, une fois. Le compteur mesure donc ce que son nom dit.
     */
    public static double widenResidual(double vanilla, long anchorNanos) {
        double late = lateness(anchorNanos);
        samples++;
        if (late > worstLateness) {
            worstLateness = late;
        }
        if (late >= DEAD_BAND) {
            widened++;
        }
        double factor = factor(anchorNanos, CEILING_RESIDUAL);
        return factor == 1d ? vanilla : vanilla * factor;
    }

    /** Le multiplicateur à appliquer : le carré du retard, borné, et exactement un en bande morte. */
    private static double factor(long anchorNanos, double ceiling) {
        double late = lateness(anchorNanos);
        if (late < DEAD_BAND) {
            return 1d;
        }
        double capped = Math.min(late, ceiling);
        return capped * capped;
    }

    /** Un retour en arrière a tout de même eu lieu. Appelé par le mixin, dans la branche du jeu. */
    public static void countRollback() {
        rollbacks++;
    }

    public static long samples() {
        return samples;
    }

    public static long widened() {
        return widened;
    }

    public static long rollbacks() {
        return rollbacks;
    }

    public static double worstLateness() {
        return worstLateness;
    }

    /** L'état courant, en une ligne, pour la commande d'état et l'en-tête du banc. */
    public static String describe() {
        if (!Settings.elastique()) {
            return "élastique éteint";
        }
        return String.format(java.util.Locale.ROOT,
                "%d paquet(s) jugé(s) · %d élargi(s) · pire retard ×%.2f · %d retour(s) en arrière%s",
                samples, widened, worstLateness, rollbacks,
                TOOTHLESS ? " · SABOTÉ (LANTERNE_BREAK_ELASTIQUE)" : "");
    }

    public static void reset() {
        samples = 0L;
        widened = 0L;
        rollbacks = 0L;
        worstLateness = 0d;
    }
}
