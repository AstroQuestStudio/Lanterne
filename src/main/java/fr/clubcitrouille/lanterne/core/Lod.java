package fr.clubcitrouille.lanterne.core;

/**
 * Le niveau de détail d'une simulation, et la seule idée du mod.
 *
 * <h2>Ce que fait un moteur 3D depuis trente ans, et que Minecraft ne fait pas</h2>
 *
 * <p>Aucun moteur graphique n'affiche une montagne lointaine avec autant de polygones qu'un caillou
 * sous les pieds du joueur. Le niveau de détail — <i>level of detail</i> — est la technique la plus
 * ancienne et la plus rentable du rendu temps réel : <b>dépenser là où cela se voit, et nulle part
 * ailleurs</b>.
 *
 * <p>La simulation de Minecraft ignore complètement ce principe. Elle ne connaît que deux états, et
 * ils sont binaires : un chunk est chargé, et tout ce qu'il contient est simulé vingt fois par
 * seconde ; ou il ne l'est pas, et rien ne vit. Une vache à cent cinquante blocs prend exactement
 * autant de temps processeur qu'une vache que le joueur regarde dans les yeux.
 *
 * <p>C'est <b>là</b> qu'est le gaspillage, et il est structurel. On peut rendre chaque système deux
 * fois plus rapide — c'est le travail de Lithium, et il est excellent — sans jamais toucher au fait
 * qu'on exécute cent fois plus de travail que nécessaire.
 *
 * <h2>Le gradient, et non la falaise</h2>
 *
 * <p>La tentation est de poser un seuil : au-delà de tant de blocs, on arrête de simuler. C'est ce
 * que fait l'<i>activation range</i> de Spigot, et cela casse les fermes — un mob qui ne tick plus
 * du tout ne tombe plus, ne brûle plus, ne se reproduit plus. Le joueur s'en aperçoit
 * immédiatement, et il a raison de se plaindre.
 *
 * <p>Pufferfish a montré la bonne réponse sur les serveurs Paper : un <b>gradient</b>. Plus l'entité
 * est loin, moins souvent on la tick — mais on la tick toujours. La dégradation est continue, et
 * donc invisible : personne ne voit qu'une vache à deux cents blocs réfléchit trois fois par seconde
 * au lieu de vingt, parce que personne ne la regarde.
 *
 * <p>Lanterne reprend ce gradient et lui ajoute ce qui lui manquait : <b>la compensation</b>.
 *
 * <h2>Ralentir n'est pas optimiser</h2>
 *
 * <p>C'est la limite de tout ce qui existe, et la raison d'être de ce mod. Un plant de blé tické une
 * fois sur dix pousse dix fois moins vite : le serveur va plus vite parce que <em>le jeu fait
 * moins</em>. Ce n'est pas de l'optimisation, c'est une baisse de qualité déguisée, et elle finit
 * toujours par se voir — le joueur revient à sa ferme et elle n'a pas poussé.
 *
 * <p>Or la croissance d'une culture est un processus aléatoire sans mémoire : à chaque tick, une
 * chance sur <i>n</i> de grandir. Un tel processus se rééchantillonne <b>exactement</b> : le ticker
 * une fois sur dix avec dix fois la probabilité donne la même distribution de résultats, au hasard
 * près. Pas « à peu près pareil » — <b>la même loi</b>.
 *
 * <p>La ferme pousse donc à la même vitesse, pour un dixième du coût. C'est la seule façon honnête
 * de tenir les deux bouts : le joueur ne perd rien, et la machine ne paie plus.
 *
 * <p>Là où la compensation n'est pas exacte — un déplacement, une décision d'IA — on ne la tente
 * pas. Mieux vaut une vache qui réfléchit moins souvent qu'une vache qui se téléporte.
 */
public enum Lod {
    /**
     * Pleine simulation, tous les ticks.
     *
     * <p>Ce que le joueur voit, ce qu'il touche, ce qui le vise. En deçà du seuil de proximité, rien
     * n'est dégradé : c'est la garantie qui rend tout le reste acceptable.
     */
    FULL(1),

    /** Un tick sur deux. La bordure du champ de vision utile. */
    NEAR(2),

    /** Un tick sur quatre. Assez loin pour qu'on ne distingue plus une démarche d'une autre. */
    FAR(4),

    /** Un tick sur huit. Le joueur sait qu'il y a quelque chose là-bas, pas ce que ça fait. */
    DISTANT(8),

    /**
     * Un tick sur seize.
     *
     * <p>Le plancher, et il est délibérément bas. Une fois par seconde suffit pour qu'une ferme
     * continue de produire, qu'un mob finisse par brûler au soleil et qu'un objet au sol finisse par
     * disparaître. En dessous, on entrerait dans le domaine où les mécaniques cassent — et une
     * mécanique cassée coûte bien plus cher en confiance qu'elle ne rapporte en millisecondes.
     */
    DORMANT(16);

    /** Un tick sur combien. */
    public final int period;

    Lod(int period) {
        this.period = period;
    }

    /**
     * Ce niveau doit-il agir à ce tick-ci ?
     *
     * <h2>Le décalage, et pourquoi il n'est pas un détail</h2>
     *
     * <p>La réponse évidente serait {@code tick % period == 0}. Elle est fausse en pratique, et de
     * la pire façon : toutes les entités d'un même niveau agiraient <b>dans le même tick</b>. On
     * aurait quinze ticks vides suivis d'un tick qui fait tout le travail — la charge ne baisserait
     * pas, elle se concentrerait. Les à-coups seraient pires qu'avant.
     *
     * <p>On étale donc, en décalant chaque entité selon son identifiant. Le travail se répartit
     * uniformément sur la période, et c'est ce lissage — plus encore que le volume économisé — qui
     * produit la sensation de fluidité.
     *
     * @param tick   le tick courant du monde
     * @param offset un nombre propre à l'entité, typiquement son identifiant
     */
    public boolean actsOn(long tick, int offset) {
        return period == 1 || (tick + offset) % period == 0;
    }

    /**
     * Le facteur de compensation à appliquer à un processus aléatoire.
     *
     * <p>Vaut exactement la période : si l'on n'agit qu'une fois sur seize, chaque passage doit
     * valoir seize tirages. Pour un processus sans mémoire, le résultat est de même loi — voir la
     * discussion en tête de classe.
     */
    public int catchUp() {
        return period;
    }

    /**
     * Le niveau qui convient à une distance, selon la pression du moment.
     *
     * @param distanceSq distance au carré jusqu'au joueur le plus proche, en blocs
     * @param pressure   sévérité demandée par {@link TickBudget}, de 0 (serveur au repos) à 1
     *                   (serveur en difficulté)
     */
    public static Lod forDistance(double distanceSq, double pressure) {
        // Les seuils se resserrent quand le serveur souffre, et s'écartent quand il respire. Un
        // serveur au repos n'a aucune raison de dégrader quoi que ce soit : la meilleure
        // optimisation est celle qui ne s'applique pas quand elle ne sert à rien.
        double scale = 1d - 0.45d * clamp(pressure);

        if (distanceSq < sq(32d * scale)) {
            return FULL;
        }
        if (distanceSq < sq(64d * scale)) {
            return NEAR;
        }
        if (distanceSq < sq(112d * scale)) {
            return FAR;
        }
        if (distanceSq < sq(176d * scale)) {
            return DISTANT;
        }
        return DORMANT;
    }

    private static double sq(double value) {
        return value * value;
    }

    private static double clamp(double value) {
        return value < 0d ? 0d : value > 1d ? 1d : value;
    }
}
