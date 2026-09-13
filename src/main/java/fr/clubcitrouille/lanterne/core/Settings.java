package fr.clubcitrouille.lanterne.core;

import java.util.Locale;

/**
 * Les interrupteurs, et pourquoi ils sont le module le plus important du mod.
 *
 * <h2>Sept plans démolis par la mesure</h2>
 *
 * <p>Ce mod a commencé par des idées séduisantes, défendables, et fausses.
 *
 * <p><b>Une allocation par bloc dans la génération de terrain.</b> Un objet jetable créé pour chacun
 * des quatre-vingt-dix-huit mille blocs d'un chunk. Réel, corrigé ailleurs par C2ME, et sans effet
 * ici : le compilateur à la volée l'avait déjà éliminée par analyse d'échappement.
 *
 * <p><b>Le doublon des explosions.</b> Le calcul d'une explosion demande deux fois le même
 * renseignement au monde, dix-sept mille fois par tir. Le doublon est réel, vérifié ligne par ligne
 * dans le code du jeu — et le supprimer ne fait gagner <b>rien</b> : 972 µs contre 1022. Le cache de
 * chunk de vanilla rendait déjà la seconde demande gratuite.
 *
 * <p><b>La compensation des ticks aléatoires.</b> Ne tirer qu'une fois sur seize, mais seize fois
 * plus. La loi est préservée — c'est exact — et le gain est <b>nul</b> : même nombre total de
 * tirages, avant comme après.
 *
 * <p><b>Le ralentissement des entonnoirs.</b> Un entonnoir tické une fois sur seize transfère seize
 * fois moins d'objets, et les chunks concernés sont dans le rayon de simulation — ce sont donc des
 * fermes <em>en marche</em>. On ne les aurait pas optimisées, on les aurait cassées.
 *
 * <p><b>Un débordement d'entier</b> a neutralisé le recensement entier pendant six bancs, sans que
 * rien ne le signale. Le mod compilait, démarrait, journalisait, et ne faisait rien.
 *
 * <p>Ce qui les a écartés n'est ni l'expérience ni le flair : une multiplication posée avant de
 * coder, et une ligne de rapport. <b>Et ce qui vaut pour un plan vaut pour un résultat.</b>
 *
 * <h2>Un interrupteur par optimisation, et non un seul pour tout</h2>
 *
 * <p>Un interrupteur global suffit à prouver que le mod sert à quelque chose. Il ne dit pas
 * <em>lequel</em> de ses modules y est pour quelque chose — et c'est très exactement le reproche
 * qu'on fait à un assemblage de vingt mods d'optimisation.
 *
 * <p>Le cas s'est présenté aussitôt : le cache du profileur, qui vise seize pour cent du temps
 * mesuré, n'a rien changé au résultat d'ensemble. Non qu'il soit inutile, mais parce que le niveau
 * de détail écarte déjà la plupart des appels qu'il aurait accélérés. Sans interrupteurs séparés, on
 * aurait conclu « inutile » et jeté un module qui vaut peut-être beaucoup une fois seul.
 *
 * <p>Chaque module se mesure donc seul, et le rapport porte sur ce qu'il fait, pas sur ce qu'on
 * espérait qu'il fasse.
 */
public final class Settings {
    /** Coupe tout, d'un coup. Sert au banc et au diagnostic. */
    private static boolean master = true;

    /** Le niveau de détail appliqué au tick des entités. Le cœur du mod. */
    private static boolean lod = true;
    /** L'espacement des paquets de position pour les entités lointaines. */
    private static boolean network = true;
    /** Le cache du profileur intégré, qui évite une recherche par entité et par tick. */
    private static boolean profilerCache = true;
    /** La dégradation par densité : ce qui est noyé dans le nombre ne se distingue pas. */
    private static boolean density = true;
    /** Le plafond de poussées dans les tas d'entités, où le coût est quadratique. */
    private static boolean collisions = true;

    /**
     * Le court-circuit de visibilité des explosions.
     *
     * <p>Deux modules « explosions » ont déjà été retirés après mesure — voir
     * {@link #BLAST_REMOVED_AFTER_MEASUREMENT}. Tous deux visaient le <b>tracé des rayons</b>, et tous
     * deux ont rendu zéro : le cache de chunk du jeu absorbait déjà ce qu'ils économisaient.
     *
     * <p>Celui-ci vise un autre poste, jamais mesuré : {@code getSeenPercent}, dont le coût croît comme
     * le <b>carré</b> du nombre de charges. Le raisonnement est dans {@code Rubble} ; la mesure décidera
     * comme elle a décidé pour les deux précédents.
     */
    private static boolean explosions = true;

    /**
     * Le saut des balayages de blocs dans le vide, retiré après mesure — huitième plan démoli.
     *
     * <h2>Le poste le plus gros du serveur, et le remède qui ne s'arme jamais</h2>
     *
     * <p>Une fois le banc de dynamite réparé, son profil désignait sans ambiguïté le premier poste du
     * serveur — et ce n'était ni l'explosion ni le réseau :
     *
     * <pre>
     * 73,5 %  PrimedTnt.tick
     * 51,4 %    Entity.move
     * 35,6 %      BlockCollisions.computeNext
     * 30,5 %        PalettedContainer.get
     * </pre>
     *
     * <p>Le raisonnement semblait imparable. {@code BlockCollisions} balaie la boîte de l'entité
     * élargie d'un bloc : trente-six états de bloc lus un par un, à chaque tick, pour chaque entité qui
     * bouge. Or une section de seize cubes sait répondre en une comparaison d'entier qu'elle ne
     * contient que de l'air, et l'air ne bloque rien. Une entité en vol devait donc être servie
     * gratuitement.
     *
     * <p>Le compteur a tranché sans appel :
     *
     * <pre>
     * Balayages de blocs évités : 690 sur 8 510 331   (0,008 %)
     * 94,42 ms sans · 92,36 ms avec  → aucun effet mesurable
     * </pre>
     *
     * <p>La cause est une ligne de {@code LevelChunk.getBlockState} que je n'avais pas lue :
     *
     * <pre>
     * LevelChunkSection section = this.sections[index];
     * if (!section.hasOnlyAir()) {
     *     return section.getBlockState(x &amp; 15, y &amp; 15, z &amp; 15);
     * }
     * return Blocks.AIR.defaultBlockState();
     * </pre>
     *
     * <p><b>Vanilla fait déjà ce test</b>, et il le fait mieux : position par position, là où le mien
     * exigeait que <em>toutes</em> les sections touchées soient vides. Une section fait seize blocs de
     * haut ; un sol et une entité qui vole dix blocs au-dessus y tiennent ensemble. Mon test échouait
     * donc toujours, et les trente pour cent de {@code PalettedContainer.get} sont des lectures dans
     * des sections réellement pleines — du travail que personne ne peut supprimer par cette voie.
     *
     * <p>Le module est retiré. Il laisse une leçon qui vaut mieux que lui : <b>avant d'ajouter un
     * test, vérifier que le jeu ne le fait pas déjà</b>. C'est la troisième fois — après le doublon
     * des explosions, absorbé par le cache de chunk, et l'allocation de la génération de terrain,
     * supprimée par le compilateur.
     */
    private static final boolean AIRSKIP_REMOVED_AFTER_MEASUREMENT = true;
    /** Le court-circuit de bousculade pour les amas immobiles. */
    private static boolean jam = true;
    /** Le sommeil à échéance des blocs-entités dont l'issue est connue d'avance. */
    private static boolean sleep = true;
    /** Le rationnement : réagir pendant le tick, et non au suivant. */
    private static boolean rationing = true;
    /** La position réutilisée pour les tirages aléatoires de blocs. */
    private static boolean scratchPos = true;
    /**
     * Le sommeil des objets au sol, retiré après mesure — septième plan démoli, et le plus coûteux.
     *
     * <h2>L'innovation de la nuit, écartée par son propre banc</h2>
     *
     * <p>C'était l'idée la mieux fondée de ce projet. Un objet posé au sol refait vingt fois par seconde
     * une requête de collision complète dont la réponse n'a pas changé depuis trois minutes ; et l'on
     * pouvait démontrer, code du jeu à l'appui, que l'endormir ne cassait rien — c'est le joueur qui
     * ramasse, la trémie qui aspire, l'explosion qui cherche.
     *
     * <p>Elle a demandé quatre fichiers, deux mixins, un compteur de modifications par section de chunk
     * pour le réveil événementiel, une épreuve de conformité dédiée, et la correction de deux bugs — le
     * critère de vitesse qui ne se déclenchait jamais, puis le compteur qui comptait les mauvais ticks.
     *
     * <p>Trois mesures l'ont écartée :
     *
     * <pre>
     * module seul, 2 000 objets   : 38,46 contre 38,84 ms  → aucun effet
     * tout sauf lui, 8 000 objets : ×62,24
     * tout avec lui, 8 000 objets : ×62,36
     * </pre>
     *
     * <p>Le niveau de détail et le court-circuit de collision faisaient déjà tout le travail. Le sommeil
     * n'arrivait qu'après eux, sur un tick déjà supprimé.
     *
     * <h2>Ce qu'il laisse derrière lui, et qui reste</h2>
     *
     * <p>Le retrait n'efface pas ce que sa construction a trouvé. En cherchant pourquoi la disparition
     * des objets était décalée — 152 ticks au lieu de 120 —, on a découvert que la cadence arrêtait leur
     * horloge d'âge. Cette correction, elle, est <b>réelle et conservée</b> : voir {@code Produce.age}.
     *
     * <p>Retirer le module supprime aussi un mixin sur {@code LevelChunk.setBlockState}, c'est-à-dire
     * sur chaque pose de bloc du serveur. Un chemin très chaud, allégé pour de bon.
     */
    private static final boolean LITTER_REMOVED_AFTER_MEASUREMENT = true;

    /**
     * La préservation stricte du rendement, pour les créatures venues de mods.
     *
     * <h2>Un choix chiffré, laissé à l'administrateur</h2>
     *
     * <p>Éteint — le défaut — une créature ralentie voit son tick annulé, et ce mod rattrape à la main
     * les compteurs de production du jeu de base : croissance, reproduction, ponte. C'est la voie
     * rapide : <b>10,01 ms</b> sur la charge d'essai.
     *
     * <p>Allumé, le tick s'exécute et l'on n'en retire que l'intelligence et le déplacement. Tous les
     * compteurs de toutes les créatures survivent, y compris ceux qu'un mod aurait ajoutés et qu'on ne
     * peut pas connaître. C'est la voie sûre : <b>14,31 ms</b> sur la même charge.
     *
     * <p>Quarante pour cent du gain contre une garantie universelle. Le chiffre est mesuré, la décision
     * appartient à celui qui connaît ses mods. {@code LANTERNE_MODULES=…,strict}
     */
    private static boolean strictYield;

    /**
     * Le cache de lecture de bloc pendant une recherche de chemin.
     *
     * <p>Vanilla 26.1 a déjà absorbé les trois optimisations de pathfinding de Lithium — le cache de
     * type par position, le chemin rapide des blocs ordinaires, la forme de collision mémorisée par
     * état. Deux appels de {@code WalkNodeEvaluator} les contournent pourtant et relisent l'état brut,
     * jusqu'à huit fois la même position pendant une seule recherche. Voir {@code Wayfind}.
     */

    /**
     * Les allocations de position dans le calcul d'une explosion.
     *
     * <p>Un premier module « explosions » a déjà été retiré après mesure : il visait le temps de calcul
     * et ne rapportait rien. Celui-ci vise le <b>débit d'allocation</b>, qui est une autre grandeur —
     * celle qui commande la fréquence des ramassages, donc les à-coups. Voir {@code Blast}.
     */

    /**
     * Le cache d'état de bloc pour les entités immobiles.
     *
     * <p>Le profileur place les lectures d'état de bloc à dix-huit pour cent du travail du serveur. Une
     * créature qui n'a pas bougé relit les mêmes blocs vingt fois par seconde. Voir {@code Stone}.
     */

    /**
     * Le module de génération de terrain, retiré après mesure — sixième plan démoli par le banc.
     *
     * <h2>Une allocation par bloc, et le compilateur l'avait déjà supprimée</h2>
     *
     * <p>{@code Aquifer.NoiseBasedAquifer.computeSubstance} — appelée pour chacun des quatre-vingt-dix
     * -huit mille blocs d'un chunk en génération — crée un {@code new MutableDouble(Double.NaN)} qui ne
     * sert que de cache à une case pour les trois appels suivants. Objet jetable, jeté par bloc. C2ME
     * corrige ce point dans son propre générateur.
     *
     * <p>Le remède tient en un champ d'instance réutilisé, à comportement rigoureusement identique. Il
     * a été écrit, et son Javadoc portait déjà la réserve qui allait le condamner : <em>« le compilateur
     * à la volée sait parfois supprimer complètement une allocation dont il démontre qu'elle ne quitte
     * pas la méthode »</em>.
     *
     * <p>C'est ce qu'il fait. Trois exécutions du banc de génération apparié :
     *
     * <pre>
     * 34,41 ms contre 34,70  → aucun effet mesurable
     * 34,31 ms contre 35,74  → aucun effet mesurable
     * </pre>
     *
     * <p>Le module est retiré. Ce qui reste de l'affaire vaut mieux que le module : le <b>banc</b> de
     * génération, lui, a été réparé deux fois au passage — appariement des chunks dans une même grille,
     * puis inversion de parité à mi-grille pour annuler le biais d'ordre. Il rend désormais le bon
     * résultat, de façon reproductible, sur un cas dont on connaît la réponse. C'est ce qui permettra de
     * croire le jour où il annoncera un gain.
     */
    private static final boolean TERRAIN_REMOVED_AFTER_MEASUREMENT = true;

    /**
     * Le module des explosions, retiré après mesure — cinquième plan démoli par le banc.
     *
     * <h2>Un doublon réel, et sans conséquence</h2>
     *
     * <p>Le calcul d'une explosion interroge le monde deux fois pour la même position :
     * {@code getBlockState(pos)} puis {@code getFluidState(pos)}. Le second est un doublon strict du
     * premier — la chaîne d'appels descend jusqu'à {@code BlockStateBase.getFluidState()}, qui rend un
     * champ déjà calculé. Dix-sept mille fois par explosion.
     *
     * <p>Le raisonnement était juste, vérifié ligne par ligne, et le gain <b>nul</b> : 972 µs sans,
     * 1022 µs avec, sur trente tirs par moitié. L'écart est du bruit.
     *
     * <p>L'explication tient à ce qu'on n'avait pas mesuré : {@code getChunkAt} garde le dernier chunk
     * consulté. La seconde interrogation, portant sur la même position que la première, ne paie donc
     * jamais la résolution qu'on croyait lui économiser.
     *
     * <p>Le module est retiré. Un doublon qui ne coûte rien n'est pas une optimisation en attente —
     * c'est du code en plus, un mixin en plus, un conflit possible en plus, pour zéro microseconde.
     * C'est la même règle qui a fait retirer le ralentissement des entonnoirs.
     */
    private static final boolean BLAST_REMOVED_AFTER_MEASUREMENT = true;
    /**
     * Le filtre qui écarte les avertissements dont l'innocuité est démontrée.
     *
     * <h2>Éteint par défaut, et ce n'est pas de la prudence excessive</h2>
     *
     * <p>Sa première version a fait taire <b>la totalité</b> des messages du mod — bannière, mesure
     * de la machine, tout — au lieu des quatre qu'elle visait. La cause était un défaut connu de
     * Log4j ({@code AbstractFilter} refuse par défaut ce qu'il ne reconnaît pas) et elle a été
     * corrigée ; mais l'incident dit quelque chose de plus général.
     *
     * <p>Un mod d'optimisation qui casse le journal d'un serveur a fait bien pire que de le
     * ralentir : il a rendu la panne suivante indéchiffrable. Le rapport entre le gain — une console
     * plus propre — et le risque — ne plus voir une vraie erreur — ne justifie pas un défaut à
     * « allumé ».
     *
     * <p>Il s'active par {@code LANTERNE_MODULES=…,silence}.
     */
    private static boolean hush;

    private Settings() {}

    /**
     * Le mod agit-il ?
     *
     * <p>Éteint, <b>rien</b> n'est dégradé : le recensement continue de tourner pour que le rapport
     * reste lisible, mais aucune entité ne perd un tick. C'est ce qui rend la comparaison honnête —
     * on mesure deux fois le même monde, pas deux mondes différents.
     */
    public static boolean enabled() {
        return master;
    }

    public static void setEnabled(boolean value) {
        master = value;
    }

    public static boolean lod() {
        return master && lod;
    }

    public static boolean network() {
        return master && network;
    }

    public static boolean profilerCache() {
        return master && profilerCache;
    }

    public static boolean density() {
        return master && density;
    }

    public static boolean collisions() {
        return master && collisions;
    }

    public static boolean explosions() {
        return master && explosions;
    }

    public static boolean jam() {
        return master && jam;
    }

    public static boolean sleep() {
        return master && sleep;
    }

    public static boolean rationing() {
        return master && rationing;
    }

    public static boolean scratchPos() {
        return master && scratchPos;
    }




    /**
     * La préservation stricte du rendement n'est pas coupée par l'interrupteur général.
     *
     * <p>Elle choisit <em>comment</em> on ralentit, et non <em>si</em> l'on ralentit. Le banc éteint le
     * mod pour comparer ; il n'a pas à changer d'architecture au milieu d'une mesure.
     */



    public static boolean strictYield() {
        return strictYield;
    }

    /**
     * Le filtre de journal ne dépend pas de l'interrupteur général.
     *
     * <p>Le banc éteint le mod pour comparer ; il n'y a aucune raison que la console redevienne
     * bruyante pendant une mesure. Faire taire un message inutile ne change rien à la vitesse.
     */
    public static boolean hush() {
        return hush;
    }

    /**
     * Lit la sélection de modules depuis l'environnement.
     *
     * <p>{@code LANTERNE_MODULES="lod"} n'active que le niveau de détail ; {@code "lod,network"} en
     * active deux ; {@code "none"} n'en active aucun. Absent, tout est actif.
     *
     * <p>C'est ce qui permet d'attribuer un gain à un module plutôt qu'au mod dans son ensemble —
     * et donc de savoir ce qu'on garde.
     */
    public static void configureFromEnvironment() {
        String raw = System.getenv("LANTERNE_MODULES");
        if (raw == null || raw.isBlank()) {
            return;
        }
        String wanted = raw.toLowerCase(Locale.ROOT);
        lod = wanted.contains("lod");
        network = wanted.contains("network") || wanted.contains("reseau");
        profilerCache = wanted.contains("profiler");
        density = wanted.contains("density") || wanted.contains("densite");
        collisions = wanted.contains("collision");
        explosions = wanted.contains("explosion") || wanted.contains("blast");
        jam = wanted.contains("jam");
        sleep = wanted.contains("sleep") || wanted.contains("sommeil");
        rationing = wanted.contains("ration");
        scratchPos = wanted.contains("scratch") || wanted.contains("pos");
        strictYield = wanted.contains("strict");
        hush = wanted.contains("silence");
    }

    /** Ce qui est actif, pour l'en-tête du rapport. */
    public static String describe() {
        if (!master) {
            return "tout éteint";
        }
        StringBuilder text = new StringBuilder();
        if (lod) {
            text.append("lod ");
        }
        if (network) {
            text.append("réseau ");
        }
        if (profilerCache) {
            text.append("profileur ");
        }
        if (density) {
            text.append("densité ");
        }
        if (collisions) {
            text.append("collisions ");
        }
        if (explosions) {
            text.append("explosions ");
        }
        if (jam) {
            text.append("amas ");
        }
        if (sleep) {
            text.append("sommeil ");
        }
        if (rationing) {
            text.append("ration ");
        }
        if (scratchPos) {
            text.append("positions ");
        }
        return text.isEmpty() ? "aucun module" : text.toString().trim();
    }
}
