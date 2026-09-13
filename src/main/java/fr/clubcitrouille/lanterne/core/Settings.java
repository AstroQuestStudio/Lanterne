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

    /** Le court-circuit de recherche de cible pour les projectiles. Voir Quarry. */
    private static boolean projectiles = true;

    /**
     * L.effet de l.ancre de chunk.
     *
     * <h2>Le socle et les ajouts</h2>
     *
     * <p>Ce mod est d.abord un socle de performance, et les modules ci-dessus en font partie. L.ancre
     * est autre chose : un <b>ajout</b> de contenu, qui répond à un besoin que l.optimisation fait
     * naître — si l.on dégrade ce qui est loin, comment garder sa ferme en marche ?
     *
     * <p>Les deux doivent rester séparables. Un administrateur qui ne veut que la vitesse coupe
     * l.ajout ; un autre qui veut l.ancre garde tout. {@code LANTERNE_MODULES} sans {@code ancre}
     * laisse le bloc exister — il reste posable, cassable, échangeable — mais il ne garde plus son
     * chunk chargé.
     *
     * <p>Le bloc lui-même est enregistré <b>en toutes circonstances</b>, et ce n.est pas négociable :
     * un bloc absent du registre devient de l.air dans les mondes où il était posé. Un interrupteur de
     * performance n.a pas le droit de détruire une construction.
     */
    private static boolean anchor = true;

    /** Les comportements composites parcourus sans streams. Voir Mind. */
    private static boolean mind = true;


    /** La fusion des orbes d.expérience, débridée. Voir Clump. */
    private static boolean clump = true;

    /**
     * La forme d.entité construite à la demande, retirée après mesure — treizième plan démoli.
     *
     * <h2>Un million d.objets supprimés, et rien au compteur</h2>
     *
     * <p>{@code BlockCollisions} est créé à chaque appel de {@code Entity.move}, et son constructeur
     * fabrique {@code Shapes.create(box)}. Ce champ n.est lu qu.en <b>un seul endroit</b>, dans la
     * branche des blocs à forme partielle — dalles, escaliers, murs. Un monde est fait de blocs pleins ;
     * la forme était donc construite pour rien presque à chaque déplacement de chaque entité.
     *
     * <p>Le raisonnement était juste et le compteur l.a confirmé sans réserve :
     *
     * <pre>
     * Formes différées : 1 123 417, dont 619 construites   →   99,9 % évitées
     * 22,81 ms sans  ·  22,78 ms avec   →   aucun effet mesurable
     * mémoire allouée : 3,98 Go sans  ·  3,87 Go avec   →   ×1,03
     * </pre>
     *
     * <p>Un million cent vingt-trois mille constructions d.objet supprimées, pour trois pour cent de
     * mémoire et zéro milliseconde.
     *
     * <p>La cause est celle qui avait déjà emporté le module de génération de terrain : le compilateur
     * à la volée <b>élimine ces allocations par analyse d.échappement</b>. L.objet ne quitte pas la
     * méthode dans l.immense majorité des cas, donc il n.est jamais vraiment créé.
     *
     * <p>C.est la deuxième fois que ce projet supprime une allocation que la machine virtuelle avait
     * déjà supprimée. La règle à en tirer complète celle des artefacts d.attribution : <b>compter des
     * allocations évitées ne prouve rien tant qu.on n.a pas mesuré le tas</b>.
     */
    private static final boolean OUTLINE_REMOVED_AFTER_MEASUREMENT = true;

    /** La compression des sauvegardes, choisie pour la vitesse. Voir Attic. */
    private static boolean save = true;

    /**
     * Le niveau de détail porté sur le client, retiré après essai — douzième plan démoli, et le seul
     * qui ait cassé le jeu.
     *
     * <h2>Le créneau était juste, la mise en œuvre non</h2>
     *
     * <p>Sodium et les mods de rendu optimisent ce qui est <b>dessiné</b>. Personne ne touche à ce qui
     * est <b>simulé côté client</b> — or {@code ClientLevel.tickEntities} appelle {@code tick()} sur
     * chaque entité chargée, vingt fois par seconde, qu.elle soit à trois blocs ou à cent cinquante.
     * Y porter le niveau de détail du mod était l.idée évidente, et elle reste bonne.
     *
     * <p>L.accroche, elle, était fausse. {@code ClientLevel.tickNonPassenger} contient ceci :
     *
     * <pre>
     * entity.setOldPosAndRot();
     * entity.tickCount++;          // ← ici
     * entity.tick();
     * </pre>
     *
     * <p>Annuler la méthode entière fige donc {@code tickCount}. Le décalage par identifiant qui
     * devait répartir les entités sur plusieurs ticks devenait constant : l.entité n.était plus jamais
     * simulée, son animation restait bloquée au même pas, et le client s.arrêtait sur un
     * {@code ArrayIndexOutOfBoundsException} quelques secondes après le chargement du monde.
     *
     * <p>Une version chirurgicale — n.annuler que {@code entity.tick()} en laissant le compteur
     * avancer — serait possible. Elle n.a pas été écrite, pour une raison mesurée et non par
     * prudence : le gain côté client est <b>déjà</b> de ×1,25 sans toucher une ligne de rendu, obtenu
     * en libérant du processeur au serveur intégré. Risquer l.animation des créatures pour un module
     * dont le seuil de soixante-quatre blocs ne s.armerait presque jamais dans le cas mesuré n.est pas
     * un échange raisonnable.
     *
     * <p>La leçon est celle qu.un crash enseigne mieux qu.un raisonnement : <b>un compteur incrémenté
     * à l.intérieur de la méthode qu.on annule cesse d.avancer</b>, et toute logique de cadence qui
     * s.appuie dessus se bloque sur sa première valeur.
     *
     * <h2>La version chirurgicale a été écrite, et elle plante aussi</h2>
     *
     * <p>Seconde tentative : n.envelopper que l.appel à {@code entity.tick()}, en laissant
     * {@code tickCount++} et {@code setOldPosAndRot()} s.exécuter. Le compteur avance, l.animation
     * progresse, seule la simulation est espacée. C.était la correction évidente du premier échec.
     *
     * <p>Le client s.arrête toujours, sur d.autres index :
     *
     * <pre>
     * ArrayIndexOutOfBoundsException: Index 63 out of bounds for length 33
     * ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 65
     * </pre>
     *
     * <p>La cause est plus profonde que l.accroche, et elle ferme le domaine : <b>côté client, le tick
     * ne consomme pas l.état du rendu, il le produit</b>. Les tableaux que le moteur de rendu indexe —
     * poses d.animation, cycles de marche, positions interpolées — sont tenus à jour par
     * {@code tick()}. Les figer ne ralentit pas le rendu : cela lui donne des index qui n.existent
     * plus.
     *
     * <p>La thèse de ce mod — <em>on ne fait pas le travail dont le résultat ne se voit pas</em> — ne
     * s.applique donc pas ici, parce que le travail <b>est</b> ce qui se voit. C.est la première fois
     * qu.elle rencontre sa limite, et elle valait d.être trouvée.
     *
     * <p>Le client gagne de toute façon <b>×1,25</b> sans qu.on touche à une ligne de rendu, en
     * récupérant le processeur que le mod libère au serveur intégré. C.est un gain indirect, entier,
     * et sans risque.
     */
    private static final boolean HAZE_REMOVED_AFTER_CRASH = true;



    /**
     * Le raccourci du tirage aléatoire, retiré après mesure — dixième plan démoli, et le plus
     * instructif.
     *
     * <h2>Douze pour cent au profil, cent cinquante millions de raccourcis, zéro milliseconde</h2>
     *
     * <p>La charge « village » — un serveur réaliste, aucun réglage touché — plaçait
     * {@code FluidState.isRandomlyTicking} en deuxième position de son profil, à <b>12,0 %</b>. Le
     * raisonnement était net : la méthode délègue à {@code getType().isRandomlyTicking()}, le site
     * d.appel voit passer l.eau, la lave et le vide, il est donc mégamorphique et la machine virtuelle
     * renonce à l.incorporer. Un bloc sans fluide rend un singleton ; une comparaison de référence
     * devait suffire à l.écarter.
     *
     * <p>Le raccourci a fonctionné exactement comme prévu :
     *
     * <pre>
     * Tirages de bloc sans fluide reconnus : 151 055 840
     * 25,36 ms sans  ·  25,58 ms avec   →  aucun effet mesurable
     * </pre>
     *
     * <p>Cent cinquante et un millions d.appels virtuels supprimés, et pas une milliseconde gagnée.
     *
     * <h2>La leçon, qui vaut mieux que le module</h2>
     *
     * <p>Les douze pour cent n.existaient pas. Ce sont un <b>artefact d.attribution</b> de
     * l.échantillonneur : la méthode est si courte que le compilateur l.incorpore dans ses appelantes,
     * et le relevé, qui ne voit que le sommet de pile, lui impute le temps de ses voisines.
     *
     * <p>D.où une règle que ce projet appliquera désormais : <b>un poste élevé sur une méthode très
     * courte et très appelée doit être suspecté d.artefact avant d.être attaqué</b>. Elle disqualifie
     * du même coup {@code PalettedContainer.get} comme cible directe — et elle explique après coup
     * pourquoi le module « vide », qui le visait, n.avait rien rendu.
     *
     * <p>Les postes qui restent crédibles sont ceux dont le coût est <em>structurel</em> :
     * {@code FarmlandBlock.isNearWater} parcourt cent soixante-deux positions, et cela, aucun
     * compilateur ne l.effacera.
     */
    private static final boolean DRAW_REMOVED_AFTER_MEASUREMENT = true;



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
    /**
     * Le transfert par lots des entonnoirs : seize objets par recherche, et seize fois la recharge.
     *
     * <p>Ce module est la <b>seconde</b> tentative sur les entonnoirs. La première — les endormir
     * pendant leur recharge — ne pouvait rien rapporter : les sept ticks qu'elle évitait ne faisaient
     * qu'une décrémentation. Voir {@link Bulk} pour ce qui coûte réellement, et pourquoi sortir la
     * recherche de conteneur de la boucle est la seule chose qui compte ici.
     */
    private static boolean bulk = true;
    /**
     * La fusion élargie des objets au sol.
     *
     * <p>Voir {@link Gather}. Vanilla cherche un demi-bloc à l'horizontale et <b>zéro</b> à la
     * verticale, et seulement un tick sur quarante pour un objet posé — c'est-à-dire le plus rarement
     * dans le cas où la fusion réussit le mieux.
     */
    private static boolean gather = true;
    /**
     * Le balai : un nettoyage périodique, éteint par défaut.
     *
     * <p>Seul module du mod qui <b>retire quelque chose au jeu</b>. Le socle fait le même travail pour
     * moins cher ; le balai, lui, supprime des entités. Il est donc rangé parmi les ajouts et ne
     * s'allume qu'à la demande expresse d'un administrateur. Voir {@link Broom}.
     */
    private static boolean broom;
    /**
     * La Lanterne de Veille : aucun monstre n'apparaît dans son chunk.
     *
     * <p>Seul ajout du mod qui rend le serveur <b>plus rapide</b> plutôt que plus riche. Voir
     * {@link Watch} pour le chemin d'apparition qu'une garde épargne en entier.
     */
    private static boolean vigil = true;
    /**
     * L'enclos : une bête qui a prouvé qu'elle n'allait nulle part cesse de décider d'y aller.
     *
     * <p>Voir {@link Pasture}. C'est le chaînon qui bloquait {@link Jam} et le repos posé : tant que
     * les bêtes décident d'errer, rien ne s'immobilise, donc rien d'autre ne peut se court-circuiter.
     */
    private static boolean pasture = true;
    /** Le carnet de repères. Voir {@code content.waypoint.Waypoint} — et l'absence de téléportation. */
    private static boolean waypoints = true;
    /**
     * Le repos posé, retiré DEUX fois après mesure — quatorzième plan démoli, et le seul que ce
     * projet ait éprouvé à nouveau après avoir corrigé sa cause d'échec.
     *
     * <h2>Le raisonnement, et il était juste</h2>
     *
     * <p>Une bête posée sur un cube plein ne peut pas tomber. Sa collision — requête spatiale sur les
     * entités voisines, relevé des formes de bloc, fusion, résolution axe par axe — pouvait donc être
     * conclue au lieu d'être calculée, en lisant quatre booléens déjà en cache.
     *
     * <h2>Le premier refus, et sa cause</h2>
     *
     * <pre>
     * taux de déclenchement 4,5 %   ·   sans : 6,10 et 6,55 ms   ·   avec : 7,32 et 7,26 ms
     * </pre>
     *
     * <p>Dans un enclos, les bêtes se poussent en permanence : leur mouvement horizontal n'est jamais
     * exactement nul, et la première condition échouait. Le module ne servait presque jamais.
     *
     * <h2>Le second refus, une fois la cause corrigée</h2>
     *
     * <p>{@link Pasture} a supprimé la décision d'errance des bêtes qui n'allaient nulle part. Neuf
     * cent quinze sur mille se sont posées, les amas figés sont passés de cent vingt à trois cent
     * soixante-douze, et le taux de déclenchement de quatre et demi à <b>vingt-six pour cent</b>.
     *
     * <p>La précondition était remplie. Quatre paires de mesures entrelacées ont tranché quand même :
     *
     * <pre>
     * sans : 6,21 · 7,21 · 8,25 · 6,75   moyenne 7,11 ms
     * avec : 6,37 · 6,48 · 8,29 · 7,97   moyenne 7,28 ms
     * </pre>
     *
     * <p>Trois fois sur quatre dans le mauvais sens, et un écart de dix-sept centièmes pour une
     * dispersion de deux millisecondes à l'intérieur de chaque bras.
     *
     * <h2>La règle, et elle est arithmétique</h2>
     *
     * <p>Le rapport a fini par publier le bon chiffre : <b>78 719 appels examinés sur cinq cents
     * ticks</b>, soit cent cinquante-sept par tick. Une fois la cadence appliquée, la plupart des
     * bêtes ne passent plus du tout par {@code collide}. Le raccourci portait donc sur quarante
     * appels par tick — et son point d'accroche se payait sur les cent cinquante-sept.
     *
     * <p>La valeur d'un raccourci est le produit de trois nombres : <b>son taux de déclenchement, ce
     * qu'il épargne à chaque fois, et le nombre d'appels</b>. Ce projet avait retenu de ne pas se fier
     * au deuxième seul ; il retient maintenant qu'un taux élevé ne suffit pas non plus si le troisième
     * est petit. La première publication disait « 22 674 collisions épargnées » — un grand nombre qui
     * cachait les deux autres.
     */
    private static final boolean REST_REMOVED_TWICE_AFTER_MEASUREMENT = true;
    /** Le rationnement : réagir pendant le tick, et non au suivant. */
    private static boolean rationing = true;
    /**
     * La position réutilisée pour les tirages aléatoires de blocs — et le premier contributeur du mod
     * sur la mémoire.
     *
     * <h2>Un module rangé comme un détail, mesuré seul pour la première fois</h2>
     *
     * <p>{@code NaturalSpawner.getRandomPosWithin} crée une position par tirage, et le spawner en tire
     * beaucoup. Éviter cette allocation semblait un détail d.hygiène, et le module avait été écrit puis
     * oublié parmi les autres.
     *
     * <p>Mesuré seul sur la charge du village — un serveur réaliste, aucun réglage touché :
     *
     * <pre>
     * 20,58 ms sans  ·  19,74 ms avec        →  aucun effet sur le temps
     * 4,08 Go alloués  ·  1,36 Go alloués     →  ×3,01 SUR LA MÉMOIRE
     * </pre>
     *
     * <p>Aucun effet sur le temps de tick, et un facteur trois sur la mémoire allouée. C.est lui qui
     * porte l.essentiel du ×4,24 que le mod complet obtient sur cette charge.
     *
     * <p>La leçon rejoint celle des interrupteurs séparés, et elle est plus forte : un module jugé sur
     * le seul temps de tick aurait été <b>retiré comme sans effet</b>. Mesuré sur la bonne grandeur, il
     * est le premier contributeur du mod sur ce qui décide des à-coups d.un serveur à un cœur — la
     * fréquence des ramassages, qui suit le débit d.allocation et rien d.autre.
     */
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

    public static boolean projectiles() {
        return master && projectiles;
    }

    public static boolean anchor() {
        return master && anchor;
    }

    public static boolean mind() {
        return master && mind;
    }

    public static boolean clump() {
        return master && clump;
    }

    public static boolean save() {
        return master && save;
    }

    public static boolean jam() {
        return master && jam;
    }

    public static boolean sleep() {
        return master && sleep;
    }

    public static boolean bulk() {
        return master && bulk;
    }

    public static boolean gather() {
        return master && gather;
    }

    public static boolean pasture() {
        return master && pasture;
    }

    /**
     * Les repères ne dépendent pas de l'interrupteur général, pour la même raison que la Lanterne :
     * ce sont des <b>données de joueur</b>. Un banc qui coupe tout ne doit pas pouvoir rendre un
     * carnet invisible en cours de partie.
     */
    public static boolean waypoints() {
        return waypoints;
    }



    /**
     * La Lanterne ne dépend pas non plus de l'interrupteur général — pour la raison inverse du balai.
     *
     * <p>Le balai en est exclu parce qu'il fausserait les mesures. La Lanterne en est exclue parce
     * qu'elle est un <b>bloc posé dans le monde</b> : l'éteindre en cours de partie ferait apparaître
     * des monstres dans une base qui en était protégée, sans que rien ne l'annonce. Un interrupteur de
     * banc ne doit pas pouvoir faire cela.
     */
    public static boolean vigil() {
        return vigil;
    }

    /**
     * Le balai ne dépend PAS de l'interrupteur général.
     *
     * <p>Tous les autres modules s'éteignent avec {@code master} parce que le banc a besoin de
     * comparer « le mod » à « pas le mod ». Le balai n'a rien à faire dans cette comparaison : il ne
     * rend pas le jeu plus rapide à travail égal, il enlève du travail. L'inclure fausserait chaque
     * mesure du projet, et d'une manière particulièrement pernicieuse — en supprimant la charge même
     * qu'on est en train de mesurer.
     */
    public static boolean broom() {
        return broom;
    }

    public static void setBroom(boolean value) {
        broom = value;
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
        projectiles = wanted.contains("projectile") || wanted.contains("fleche");
        anchor = wanted.contains("anchor") || wanted.contains("ancre");
        save = wanted.contains("save") || wanted.contains("sauvegarde");
        explosions = wanted.contains("explosion") || wanted.contains("blast");
        jam = wanted.contains("jam");
        sleep = wanted.contains("sleep") || wanted.contains("sommeil");
        bulk = wanted.contains("bulk") || wanted.contains("lot") || wanted.contains("entonnoir");
        gather = wanted.contains("gather") || wanted.contains("fusion") || wanted.contains("objets");
        pasture = wanted.contains("pasture") || wanted.contains("enclos") || wanted.contains("errance");
        rationing = wanted.contains("ration");
        scratchPos = wanted.contains("scratch") || wanted.contains("pos");
        strictYield = wanted.contains("strict");
        hush = wanted.contains("silence");
    }

    /**
     * L.environnement a-t-il posé un choix de modules ?
     *
     * <p>Sert au fichier de configuration, qui doit s.effacer devant lui : un banc lancé avec un seul
     * module verrait sinon le fichier rallumer les autres, et mesurerait le mod entier en croyant
     * mesurer une pièce.
     */
    public static boolean environmentSpoke() {
        String raw = System.getenv("LANTERNE_MODULES");
        return raw != null && !raw.isBlank();
    }

    /** Applique le fichier de configuration. Voir Config, et la priorité donnée à l.environnement. */
    public static void applyFromConfig() {
        lod = Config.LOD.get();
        network = Config.NETWORK.get();
        profilerCache = Config.PROFILER.get();
        density = Config.DENSITY.get();
        collisions = Config.COLLISIONS.get();
        projectiles = Config.PROJECTILES.get();
        explosions = Config.EXPLOSIONS.get();
        mind = Config.MIND.get();
        save = Config.SAVE.get();
        jam = Config.JAM.get();
        sleep = Config.SLEEP.get();
        bulk = Config.BULK.get();
        gather = Config.GATHER.get();
        broom = Config.BROOM.get();
        vigil = Config.VIGIL.get();
        pasture = Config.PASTURE.get();
        waypoints = Config.WAYPOINTS.get();
        rationing = Config.RATIONING.get();
        scratchPos = Config.SCRATCH_POS.get();
        strictYield = Config.STRICT_YIELD.get();
        clump = Config.CLUMP.get();
        anchor = Config.ANCHOR.get();
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
        if (projectiles) {
            text.append("projectiles ");
        }
        if (save) {
            text.append("sauvegarde ");
        }
        if (jam) {
            text.append("amas ");
        }
        if (sleep) {
            text.append("sommeil ");
        }
        if (bulk) {
            text.append("entonnoirs ");
        }
        if (gather) {
            text.append("fusion-objets ");
        }
        if (pasture) {
            text.append("enclos ");
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
