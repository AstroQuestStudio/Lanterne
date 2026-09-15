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

    /** Le compteur de basculements. Voir {@link #epoch()} pour ce qu'il protège. */
    private static long epoch;

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

    /** La boite vide partagee des conditions de memoire. Voir MemoryBoxMixin. */
    private static boolean boxes = true;

    /** L'ajustement automatique des distances selon la charge. Voir Tide. */
    private static boolean tide = true;

    /**
     * Le refus des chargements de chunk synchrones. Voir {@link Digue}.
     *
     * <p>Seul module de ce mod qui <b>retire une information au jeu</b> plutôt que de la calculer
     * moins cher : une carte laisse une case blanche, un villageois ne se couche pas. Chaque perte
     * est nommée dans le mixin qui la cause, et deux points d'accroche de {@code ServerCore} ont été
     * écartés parce que la leur se voyait.
     */
    private static boolean digue = true;

    /**
     * L'élastique : les seuils de mouvement mesurés à l'horloge. Voir {@link Elastique}.
     *
     * <h2>Le seul module livré ÉTEINT, et pourquoi</h2>
     *
     * <p>Tous les autres modules de ce mod font <b>moins de travail</b> pour le même résultat. Celui-ci
     * ne fait rien gagner du tout : il cache un symptôme. Il n'a donc pas sa place dans une
     * configuration d'usine, où il ferait croire à une optimisation et masquerait, au passage, le
     * signal qu'un serveur va mal.
     *
     * <p>Un administrateur l'allume quand il a constaté que tout le reste ne suffisait pas. C'est un
     * dernier recours, et un réglage par défaut n'est jamais un dernier recours.
     */
    private static boolean elastique = false;

    /**
     * Le sommaire des paquets zip. Voir {@link Sommaire}.
     *
     * <p>Le seul module de ce mod qui n'agit <b>ni pendant le tick ni pendant l'image</b>, mais au
     * chargement. Le banc de vitesse ne le verra donc jamais, quelle que soit son efficacité — c'est
     * la même raison qui a fait écrire une épreuve séparée pour la compression des paquets.
     */
    private static boolean sommaire = true;

    /**
     * Le tampon mutable sur les pilotes concernés. Voir {@code client.Tampon}.
     *
     * <p>Seul module de ce mod livré allumé <b>sans avoir été mesuré ici</b>. Ce n'est pas un
     * relâchement de la règle : c'est un rétroportage d'un correctif que Mojang a écrit lui-même en
     * 26.3, et la mesure qui le justifie est la sienne, pas la nôtre. Le javadoc de {@code Tampon}
     * le dit en premier paragraphe et non en note.
     */
    private static boolean tampon = true;


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
    /**
     * Le niveau de compression des paquets, ramené de six à un.
     *
     * <p>Voir {@link Wire}. Seul module du mod qui ne demande rien au client : le niveau est une
     * décision de l'émetteur, pas une propriété du flux.
     */
    private static boolean wire = true;
    /** Les points d'intérêt parcourus sans emballer d'entiers. Voir {@code PoiStreamMixin}. */
    private static boolean poi = true;
    /**
     * Le tableau vide non fabriqué dans les effets de blocs traversés.
     *
     * <p>Retiré une fois — 0,7 % de la mémoire, rien sur le temps — puis remis, parce que la règle du
     * projet était trop grossière. Voir {@code InsideEffectsMixin} : on retire ce qui coûte, on garde
     * ce qui gagne, même peu.
     */
    private static boolean spill = true;
    /**
     * Le voile : les créatures qu'un mur cache ne sont pas préparées pour le rendu.
     *
     * <p>Voir {@link Shroud}. Premier module de ce projet qui agit sur le client et non sur le
     * serveur — et le premier qu'il était impossible de retenir avant que le banc d'images cesse de
     * se contredire, puisqu'aucun chiffre n'en aurait été décidable.
     */
    private static boolean shroud = true;
    /**
     * Les coffres rendus comme des blocs ordinaires plutot que comme des entites animees.
     *
     * <p>Voir {@code StaticChestRendererMixin}. Repris de Faster Block Entities, sous GPL-3.0 —
     * premier module de ce projet dont le code vient d'ailleurs, et la raison pour laquelle Lanterne
     * a change de licence.
     */
    private static boolean staticChests = true;
    /** La chute rapide des feuilles detachees. Cote SERVEUR. Voir {@code LeafDecayMixin}. */
    /** Les formes de collision partagees entre etats. Voir {@link Moulds}. */
    /** Le calculateur de redstone de Mojang, celui qui traite le reseau entier. */
    /** Les conditions d'entree des comportements, mises en cache. Voir {@code BehaviorMemoryMixin}. */
    /** Le capteur de joueurs sans flux. Voir {@code PlayerSensorMixin}. */
    private static boolean senses = true;
    private static boolean recall = true;
    /**
     * Le cadastre du placement Jigsaw : la place déjà prise, tenue dans un index au lieu d'une forme
     * de voxels qui grossit. Voir {@link Cadastre}.
     */
    private static boolean cadastre = true;
    /**
     * Le pochoir des gabarits de structure, retiré après re-mesure — et le premier module de ce
     * dépôt que <em>Mojang</em> ait périmé plutôt que la mesure.
     *
     * <h2>×1,54 en 26.1.2, ×1,06 en 26.2</h2>
     *
     * <p>Le module réduisait la liste des blocs d'un gabarit à ceux qui tombaient dans la fenêtre du
     * chunk, <em>avant</em> que {@code processBlockInfos} ne les prépare un par un. En 26.1.2 la
     * préparation — position transformée, objet neuf, copie de balise NBT, chaîne des processeurs —
     * arrivait bel et bien avant le tri, et une maison de village à cheval sur quatre chunks était
     * préparée quatre fois en entier.
     *
     * <p>La 26.2 patchée NeoForge place {@code chunkBb.isInside(blockPos)} <b>avant</b> la
     * construction ({@code StructureTemplate.java}, lignes 445-458). Le pochoir n'épargnait donc plus
     * que la transformation de position — et il l'ajoutait une fois de plus.
     *
     * <p>Re-mesuré au maçon sur 26.2 : <b>×1,07</b> gabarit nu, <b>×1,06</b> avec les deux
     * processeurs de toute pièce à jigsaw, pour un taux d'écart toujours à 68,8 %. Les deux sont sous
     * la dérive de ×1,08 du laboratoire. Le module ne perdait rien ; il ne justifiait plus un
     * {@code @Redirect} sur {@code StructureTemplate$Palette.blocks()}, qui est le point d'accroche
     * que tout mod de structures veut.
     *
     * <p>Le détail, la mesure des deux versions et les trois choses à ne pas réapprendre sont dans
     * {@code notes/pochoir-retire.md}.
     */
    private static final boolean STENCIL_REMOVED_AFTER_REMEASUREMENT = true;
    private static boolean redstone;
    private static boolean moulds = true;
    private static boolean decay = true;
    /** Ticks entre le detachement d'une feuille et sa chute. */
    private static int decayDelay = 5;
    /**
     * Les taches d'un cerveau rangees a plat, retirees apres mesure — DIX-NEUVIEME plan demoli, et
     * le deuxieme du jour a tomber pour la meme raison.
     *
     * <h2>Le profileur disait 5,1 %, la mesure a rendu 1 %</h2>
     *
     * <p>{@code Brain.startEachNonRunningBehavior} traverse trois tables imbriquees par creature et
     * par tick, et l'echantillonneur la place a <b>5,1 %</b> du sommet de pile sur six cents
     * villageois. Le poste est reel ; Lithium l'optimise depuis des annees.
     *
     * <pre>
     * liste plate, test par comportement : ×1,03
     * liste plate, groupee par activite  : ×1,01
     * </pre>
     *
     * <p>La premiere version interrogeait {@code activeActivities} une fois par comportement — une
     * cinquantaine de fois la ou vanilla le fait une quinzaine. Corrige, groupe, ramene au compte
     * exact de vanilla : le gain a <em>baisse</em>. Il n'y avait donc rien a prendre.
     *
     * <p>C'est la premiere regle d'instrument, sous son visage le plus net : une methode courte et
     * tres appelee monte haut dans un echantillonneur de temps <b>sans porter le temps qu'on lui
     * prete</b>. Les 5,1 % vivent dans ce que la boucle appelle, pas dans la boucle.
     *
     * <p>Corollaire pour la suite : sur ce serveur, les micro-optimisations du cerveau sont un
     * cul-de-sac. Ce qui reste a prendre est ailleurs, et plus gros.
     */
    private static final boolean COUNCIL_REMOVED_AFTER_MEASUREMENT = true;
    /**
     * Le brassage du nombre de renvoi des positions, retire apres mesure — DIX-HUITIEME plan
     * demoli, et le premier venu d'un mod existant.
     *
     * <h2>Le poste etait reel, et enorme</h2>
     *
     * <p>{@code Vec3i.hashCode()} vaut en vanilla {@code (y + z*31)*31 + x}. Sur les 2 800 000
     * positions d'une zone de jeu ordinaire, cette formule ne produit que <b>194 571</b> valeurs
     * differentes : <b>93 % de collisions</b>. Le brassage de Fibonacci en produit 2 868 471, soit
     * <b>zero collision</b>. Les chiffres sont exacts et verifiables.
     *
     * <h2>Et le solde est negatif, sur deux charges</h2>
     *
     * <pre>
     * 600 villageois : 38,85 ms sans  ·  42,77 ms avec   →  ×0,91
     * Serveur reel   : 10,86 ms sans  ·  11,69 ms avec   →  ×0,93
     * </pre>
     *
     * <p>La cause est structurelle. Minecraft range ses positions <b>en entiers longs</b> le plus
     * souvent — {@code BlockPos.asLong()} — dans des tables {@code Long2ObjectOpenHashMap} de
     * fastutil, lesquelles n'appellent <b>jamais</b> {@code hashCode()} : elles ont leur propre
     * brassage integre. Quarante fichiers du jeu procedent ainsi, contre trente et un qui emploient
     * un {@code BlockPos} comme cle d'objet.
     *
     * <p>Le surcout — deux decalages et deux XOR de plus par appel — se paie donc <em>partout</em>
     * ou {@code hashCode()} est appele, tandis que le benefice ne se recolte que sur la moitie des
     * tables. Et la moitie qui en beneficie n'est pas la moitie chaude.
     *
     * <p>C'est le meme piege que la bordure du monde, sous un autre visage : un poste reel, une
     * correction juste, un solde negatif. La lecon s'ajoute aux deux regles d'instrument :
     * <b>un defaut mesurable n'est pas un defaut couteux</b>. Encore faut-il que le chemin corrige
     * soit celui que le jeu emprunte.
     */
    private static final boolean MIX_REMOVED_AFTER_MEASUREMENT = true;
    /** Quand masquer les faces de feuilles qui se touchent. Cote CLIENT. */
    private static ClientConfig.LeafCulling leafCulling = ClientConfig.LeafCulling.AUTO;
    /** Exemplaires dessines par pile d'objets au sol. Cote CLIENT. Voir {@code LooseItemMixin}. */
    private static int itemCopies = 1;
    /** Le voile etendu aux particules. Voir {@code ParticleVeilMixin}. */
    private static boolean veilParticles = true;
    /** Le voile etendu aux entites de bloc. Voir {@code BlockEntityVeilMixin}. */
    private static boolean veilBlockEntities = true;
    /** Le vacarme : sons identiques limites. Voir {@link Din}. */
    /** La lentille : mise a l'echelle de resolution. Voir {@link Lens}. */
    /** La jauge de performance sur le HUD. Voir {@code client.Gauge}. */
    private static boolean gauge;
    private static boolean lens;
    private static boolean din = true;
    /**
     * La bordure du monde retenue, retirée après mesure — seizième plan démoli, et la TROISIÈME
     * confirmation de la même règle.
     *
     * <h2>Le poste, et il était réel</h2>
     *
     * <pre>
     * public WorldBorder getWorldBorder() {
     *     WorldBorder worldBorder = this.getDataStorage().computeIfAbsent(WorldBorder.TYPE);
     *     worldBorder.applyInitialSettings(this.levelData.getGameTime());
     *     return worldBorder;
     * }
     * </pre>
     *
     * <p>Une consultation des données de monde à chaque appel, pour un objet identique pendant toute
     * la partie, sur un chemin parcouru une fois par entité et par tick. Le profileur lui attribuait
     * <b>2,9 %</b> du temps sur six mille TNT.
     *
     * <h2>Le verdict</h2>
     *
     * <pre>
     * 3 877 401 recherches épargnées
     * sans : 48,39 et 48,34 ms   ·   avec : 52,83 ms
     * </pre>
     *
     * <p>Près de quatre millions de consultations supprimées, et le banc <b>monte</b> de quatre
     * millisecondes.
     *
     * <h2>La règle, confirmée pour la troisième fois</h2>
     *
     * <p>Un poste élevé sur une méthode <b>courte et très appelée</b> est un artefact d'attribution
     * jusqu'à preuve du contraire : l'échantillonneur ne voit que le sommet de pile, et le compilateur
     * à la volée inline. La méthode monte haut sans porter le temps qu'on lui prête.
     *
     * <p>Et y poser un mixin lui <b>retire cette inlinisation</b> — c'est la leçon du repos posé,
     * retrouvée ici. Le coût d'un point d'accroche sur un chemin chaud dépasse celui du travail qu'on
     * y supprime, même quand ce travail est réel et qu'on en supprime quatre millions.
     */
    private static final boolean BORDER_REMOVED_AFTER_MEASUREMENT = true;
    /**
     * La chute libre, retirée après mesure — quinzième plan démoli, et le plus vite tranché.
     *
     * <h2>Le raisonnement</h2>
     *
     * <p>Le profil de six mille TNT désignait {@code Entity.move} à <b>cinquante-deux pour cent</b> du
     * tick, et l'explosion elle-même à un et demi. Chaque TNT qui tombe lit huit états de bloc, en
     * tire huit formes, les fusionne et résout axe par axe — pour découvrir qu'elle traverse de l'air.
     *
     * <p>Or {@code LevelChunkSection.hasOnlyAir()} est un compteur déjà tenu à jour. Une boîte
     * entièrement contenue dans des sections vides ne peut heurter aucun bloc : la certitude coûtait
     * deux lectures de champ au lieu du balayage entier.
     *
     * <h2>Le verdict, en un seul chiffre</h2>
     *
     * <pre>
     * 0 balayage évité sur 3 437 405 examinés   →   taux 0,0 %
     * </pre>
     *
     * <h2>La faute, et elle est de conception</h2>
     *
     * <p><b>La granularité du cache ne correspond pas à celle de la question.</b>
     * {@code hasOnlyAir()} porte sur une section de seize blocs de côté ; la boîte d'une entité en
     * fait un. Une TNT qui tombe près du sol est dans la même section que le sol, donc la section
     * n'est jamais vide. Le raccourci ne pouvait se déclencher que pour ce qui tombe en plein ciel,
     * loin de tout — c'est-à-dire pour presque rien.
     *
     * <p>Le compteur de taux l'a dit en une exécution. Sans lui, on aurait lu « ×1,61 contre ×1,76 »
     * et conclu « c'est le bruit » — vrai, et pour la mauvaise raison.
     *
     * <p>La règle des trois nombres — taux, épargne, appels — tenait sur deux d'entre eux :
     * l'épargne était réelle et les appels nombreux. <b>Le taux vaut zéro, et zéro multiplié par
     * n'importe quoi vaut zéro.</b>
     */
    private static final boolean FREEFALL_REMOVED_AFTER_MEASUREMENT = true;
    /**
     * Le minage compté à l'horloge réelle plutôt qu'en ticks serveur.
     *
     * <p>Voir {@code MiningMixin}. Corrige un défaut visible de vanilla : quand le serveur prend du
     * retard, les blocs cessent de casser parce que les deux bouts ne comptent pas la même chose.
     */
    private static boolean mining = true;
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
        if (master != value) {
            epoch++;
        }
        master = value;
    }

    /**
     * Le numéro de génération des réglages.
     *
     * <h2>À quoi sert de compter les basculements</h2>
     *
     * <p>Certains modules ne se contentent pas de lire un réglage : ils <b>entretiennent un état</b>
     * qui n'est juste que tant qu'ils tournent. {@code BrainMixin} tient la liste des comportements
     * en cours d'une créature en la mettant à jour à chaque démarrage et à chaque arrêt ; si le mod
     * s'éteint, vanilla reprend la main et fait changer ces statuts <em>sans prévenir personne</em>.
     * Rallumer le mod ferait alors travailler une liste périmée — une créature figée, ou un
     * comportement tické alors qu'il ne tourne plus.
     *
     * <p>Ce n'est pas un cas d'école. {@code /lanterne off} puis {@code /lanterne on} suffit à le
     * produire en jeu, et le banc entrelacé le produit <b>vingt fois par mesure</b>, puisqu'il
     * bascule le maître toutes les cinquante ticks.
     *
     * <p>Un module concerné retient le numéro qu'il a vu et jette son état dès qu'il change. La
     * vérification coûte une comparaison d'entiers longs par tick.
     */
    public static long epoch() {
        return epoch;
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

    /** La boite vide partagee. Voir {@code MemoryBoxMixin}. */
    public static boolean boxes() {
        return master && boxes;
    }

    /**
     * La maree, qui n'obeit pas au maitre.
     *
     * <p>Tous les autres modules s'eteignent avec {@code master}, parce que le banc bascule celui-ci
     * vingt fois par mesure pour comparer. La maree ne le peut pas : elle changerait les distances
     * pendant la mesure, et l'on comparerait deux mondes de tailles differentes en croyant comparer
     * deux versions du meme code.
     *
     * <p>Elle a donc son propre interrupteur, et le banc la met en sommeil de son cote.
     */
    public static boolean tide() {
        return tide;
    }

    /** Met la maree en sommeil pendant une mesure, et la rend ensuite. */
    public static void setTide(boolean value) {
        tide = value;
    }

    /** La digue. Voir {@link Digue}, et la liste des pertes assumées. */
    public static boolean digue() {
        return master && digue;
    }

    /**
     * L'élastique. Voir {@link Elastique} — et l'avertissement qui l'ouvre.
     *
     * <p>Obéit au maître comme les autres : l'épreuve doit pouvoir mesurer le bras sans protection,
     * et c'est ce bras-là qui donne le témoin.
     */
    public static boolean elastique() {
        return master && elastique;
    }

    /**
     * Arme ou désarme l'élastique en cours de partie.
     *
     * <p>Réservé à l'épreuve {@code Amarre}, qui doit relever les deux bras dans la même exécution :
     * une comparaison entre deux lancements séparés mesurerait aussi la différence de charge entre
     * eux, et ce dépôt a déjà publié un écart qui n'était que cela.
     */
    public static void setElastique(boolean value) {
        elastique = value;
        epoch++;
    }

    /**
     * Le sommaire ne dépend PAS de l'interrupteur général.
     *
     * <p>Il est consulté pendant le chargement des paquets, c'est-à-dire <b>avant</b> qu'un banc
     * puisse basculer quoi que ce soit, et son index est déjà bâti quand la première mesure
     * commence. Le lier au maître donnerait l'illusion qu'on peut le couper à chaud — ce qui est
     * faux — et son épreuve, qui le bascule elle-même, en a besoin indépendamment.
     */
    public static boolean sommaire() {
        return sommaire;
    }

    /** Bascule le sommaire pendant son épreuve, et le rend ensuite. */
    public static void setSommaire(boolean value) {
        sommaire = value;
    }

    /**
     * Le tampon ne dépend pas de l'interrupteur général, pour la même raison que la lentille : il
     * est consulté à la création du périphérique graphique, une seule fois, bien avant qu'un banc
     * puisse basculer quoi que ce soit — et le rebasculer ensuite ne referait pas les tampons déjà
     * alloués.
     */
    public static boolean tampon() {
        return tampon;
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

    public static boolean wire() {
        return master && wire;
    }

    public static boolean poi() {
        return master && poi;
    }

    public static boolean shroud() {
        return master && shroud;
    }

    public static boolean staticChests() {
        return master && staticChests;
    }

    public static boolean decay() {
        return master && decay;
    }

    /**
     * Lu pendant le chargement des registres, avant que les reglages soient poses.
     *
     * <p>Sans le {@code master &&} des autres : l'interrupteur principal est bascule par les bancs
     * APRES le chargement, et la deduplication, elle, a deja eu lieu. Le lier au maitre donnerait
     * l'illusion qu'on peut la couper a chaud, ce qui est faux — il faut recharger.
     */
    public static boolean moulds() {
        return moulds;
    }

    public static boolean redstone() {
        return master && redstone;
    }

    public static boolean recall() {
        return master && recall;
    }

    public static boolean senses() {
        return master && senses;
    }

    /**
     * Le cadastre suit l'interrupteur général, et il est lu depuis le POOL DE TRAVAIL.
     *
     * <p>Le placement Jigsaw tourne sur le pool de génération ; le banc, lui, bascule le maître
     * depuis le fil du serveur. Une structure en cours de placement peut donc voir le drapeau
     * changer d'avis au milieu de sa propre récursion — et là, contrairement au pochoir qui l'a
     * précédé, <b>cela compterait</b> : le cadastre tient un état, et l'abandonner en cours de route
     * laisserait la forme de voxels de vanilla périmée.
     *
     * <p>C'est pourquoi {@link Cadastre} ne relit pas ce drapeau à chaque question : il le lit
     * <b>une fois</b>, quand il prend en charge une région libre, et s'y tient jusqu'au bout de
     * celle-ci. Une bascule en cours de placement décide donc du sort de la <em>prochaine</em>
     * structure, jamais de celle qui est en train de se poser.
     */
    public static boolean cadastre() {
        return master && cadastre;
    }

    public static int decayDelay() {
        return decayDelay;
    }

    public static ClientConfig.LeafCulling leafCulling() {
        return master ? leafCulling : ClientConfig.LeafCulling.JAMAIS;
    }

    /** Quatre, soit le comportement de vanilla, des que le mod est coupe. */
    public static int itemCopies() {
        return master ? itemCopies : 4;
    }

    public static boolean veilParticles() {
        return master && veilParticles;
    }

    public static boolean veilBlockEntities() {
        return master && veilBlockEntities;
    }

    public static boolean din() {
        return master && din;
    }

    /**
     * Sans le {@code master &&} : la lentille est consultee par Window.getWidth(), donc pendant la
     * construction du client, bien avant qu'un banc puisse basculer quoi que ce soit. La couper a
     * chaud changerait la taille de la cible de rendu en pleine image.
     */
    public static boolean lens() {
        return lens;
    }

    public static boolean gauge() {
        return gauge;
    }

    public static boolean spill() {
        return master && spill;
    }




    /**
     * Le minage ne dépend PAS de l'interrupteur général.
     *
     * <p>Ce n'est pas une optimisation mais une <b>correction de justesse</b> : elle ne rend rien plus
     * rapide, elle fait que deux horloges mesurent la même chose. Un banc qui coupe tout doit continuer
     * de la porter, sinon il mesurerait un jeu où les blocs ne cassent pas.
     */
    public static boolean mining() {
        return mining;
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
        wire = wanted.contains("wire") || wanted.contains("compression") || wanted.contains("paquet");
        poi = wanted.contains("poi") || wanted.contains("interet");
        spill = wanted.contains("spill") || wanted.contains("effets") || wanted.contains("tableau");
        shroud = wanted.contains("shroud") || wanted.contains("voile") || wanted.contains("occlusion");
        staticChests = wanted.contains("chests") || wanted.contains("coffres");
        decay = wanted.contains("decay") || wanted.contains("chute");
        moulds = wanted.contains("moules");
        redstone = wanted.contains("redstone");
        recall = wanted.contains("memoire") || wanted.contains("conditions");
        senses = wanted.contains("capteur") || wanted.contains("sens");
        // « cadastre » et rien d'autre. Surtout pas « structure » ni « jigsaw » : le premier
        // apparaît déjà dans les notes du projet pour désigner tout autre chose, et le second est le
        // nom d'un bloc de vanilla qu'on retrouvera dans d'autres relevés. Un mot-clé qui ressemble
        // à un autre est exactement ce qui a fait mesurer deux modules pour un, trois fois.
        cadastre = wanted.contains("cadastre");
        // Quatre modules manquaient à cet appel, et c'est le troisième défaut du même genre.
        //
        // Un drapeau absent d'ici ne vaut pas « éteint » : il garde sa valeur par défaut, qui est
        // VRAIE. La phase active d'un banc lancé avec LANTERNE_MODULES=chute allumait donc aussi les
        // comportements composites, la fusion des orbes et la garde d'apparition — et le rapport
        // portait leur travail au crédit du module qu'on croyait isoler.
        //
        // Ces trois-là n'ont probablement rien faussé en pratique : aucune des charges déjà mesurées
        // ne contient de villageois ni d'orbes, et ils n'avaient donc rien à faire. Mais l'isolation
        // d'un banc ne doit pas dépendre du hasard de la charge choisie, et « mind » allait
        // précisément être mesuré sur six cents villageois — où la fuite, elle, aurait compté.
        mind = wanted.contains("mind") || wanted.contains("esprit") || wanted.contains("composite");
        tide = wanted.contains("maree") || wanted.contains("tide");
        // Surtout pas « chunk » ni « chargement » : ces deux mots apparaissent déjà dans les
        // relevés du recensement et dans le nom d'autres épreuves. Un mot-clé qui ressemble à un
        // autre est ce qui a fait mesurer deux modules pour un, trois fois dans ce dépôt.
        digue = wanted.contains("digue");
        // Surtout pas « mouvement » ni « rollback » : le premier désigne déjà les paquets de
        // position dans les relevés du réseau, et le second est un mot que l'utilisateur emploie
        // pour le symptôme, pas pour ce module. Un mot-clé qui ressemble à un autre est ce qui a
        // fait mesurer deux modules pour un, trois fois dans ce dépôt.
        elastique = wanted.contains("elastique");
        sommaire = wanted.contains("sommaire");
        tampon = wanted.contains("tampon");
        boxes = wanted.contains("boites") || wanted.contains("boxes");
        clump = wanted.contains("clump") || wanted.contains("orbes");
        vigil = wanted.contains("vigil") || wanted.contains("garde");
        waypoints = wanted.contains("waypoint") || wanted.contains("reperes");
        // Les réglages viennent de changer en bloc : les modules à état jettent le leur. Voir epoch().
        epoch++;
        // Le masquage des feuilles n'est pas un booléen mais un choix à trois branches, dont la
        // branche AUTO dépend d'une option vidéo VRAIE par défaut en vanilla. Un banc qui se
        // contenterait d'allumer le module mesurerait donc zéro, sans rien signaler. On force ici la
        // branche qui agit, et l'on garde le nom explicite plutôt que « feuilles » — ce mot désigne
        // déjà la chute côté serveur, et les confondre reviendrait à mesurer l'un en croyant
        // mesurer l'autre.
        // « objets » appartient déjà à la fusion au sol : le réemployer ici allumait DEUX modules
        // d'un coup, et le banc attribuait au plafond d'exemplaires un gain qui venait du tick
        // serveur. Le relevé le disait — « fusion-objets | rendu : objets-x1 » — mais il fallait
        // lire la ligne. Chaque module doit avoir un mot qui n'appartient qu'à lui.
        itemCopies = wanted.contains("exemplaires") || wanted.contains("piles") ? 1 : 4;
        veilParticles = wanted.contains("particules");
        // Surtout PAS « voile » : ce mot appartient au voile des creatures, et le partager
        // allumait les deux modules a la fois. Le banc l'a dit — « voile voile-blocs » — parce
        // qu'on lui avait appris a crier apres le meme defaut sur les objets au sol.
        veilBlockEntities = wanted.contains("blocs");
        din = wanted.contains("vacarme") || wanted.contains("sons");
        lens = wanted.contains("lentille");
        gauge = wanted.contains("jauge");
        leafCulling = wanted.contains("masque") || wanted.contains("cull")
                ? ClientConfig.LeafCulling.TOUJOURS
                : ClientConfig.LeafCulling.JAMAIS;
        mining = !wanted.contains("nomining");
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
    /**
     * Applique le fichier CLIENT : uniquement ce qui ne regarde que l'ecran du joueur.
     *
     * <p>Separe de {@link #applyFromConfig} parce que les deux fichiers ont des cycles de vie
     * differents - l'un suit la partie, l'autre suit le joueur - et parce qu'un serveur dedie ne
     * charge jamais le second. Appeler l'un depuis l'autre reintroduirait exactement le defaut que
     * la separation corrige.
     */
    public static void applyFromClientConfig() {
        shroud = ClientConfig.SHROUD.get();
        staticChests = ClientConfig.STATIC_CHESTS.get();
        leafCulling = ClientConfig.LEAF_CULLING.get();
        itemCopies = ClientConfig.ITEM_COPIES.get();
        veilParticles = ClientConfig.VEIL_PARTICLES.get();
        veilBlockEntities = ClientConfig.VEIL_BLOCK_ENTITIES.get();
        tampon = ClientConfig.TAMPON.get();
        din = ClientConfig.DIN.get();
        gauge = ClientConfig.GAUGE.get();
        lens = ClientConfig.LENS.get();
        Lens.tune(ClientConfig.LENS_PRESET.get());
        // La nettete et la houle survivent desormais au redemarrage. Sans ces deux lignes, un
        // joueur qui regle la lentille la retrouvait a « moyenne, houle eteinte » a chaque
        // lancement — et finissait par croire que le reglage ne servait a rien.
        fr.clubcitrouille.lanterne.client.upscale.Upscale.restore(
                ClientConfig.LENS_EDGE.get(), ClientConfig.LENS_SWELL.get());
        Din.tune(ClientConfig.DIN_REGION.get(), ClientConfig.DIN_ALLOWANCE.get(),
                ClientConfig.DIN_WINDOW.get());
        Shroud.tune(ClientConfig.SHROUD_DELAY.get(), ClientConfig.SHROUD_NEAR.get(),
                ClientConfig.SHROUD_BUDGET.get(), ClientConfig.SHROUD_FAR.get(),
                ClientConfig.SHROUD_BULKY.get());
    }

    /** Ce qui est actif cote rendu, pour le journal et les bancs. */
    public static String describeClient() {
        StringBuilder text = new StringBuilder();
        if (shroud) {
            text.append("voile ");
        }
        if (staticChests) {
            text.append("coffres ");
        }
        if (leafCulling != ClientConfig.LeafCulling.JAMAIS) {
            text.append("feuilles-").append(leafCulling.name().toLowerCase(Locale.ROOT)).append(' ');
        }
        if (itemCopies < 4) {
            text.append("objets-x").append(itemCopies).append(' ');
        }
        if (veilParticles) {
            text.append("voile-particules ");
        }
        if (veilBlockEntities) {
            text.append("voile-blocs ");
        }
        if (din) {
            text.append("vacarme ");
        }
        // Upscale ne nomme AUCUNE classe de net.minecraft.client : c'est ce qui autorise cette
        // classe-ci, chargée sur un serveur dédié, à l'appeler. Voir son en-tête.
        String scale = fr.clubcitrouille.lanterne.client.upscale.Upscale.describe();
        if (!scale.isEmpty()) {
            text.append(scale).append(' ');
        }
        return text.isEmpty() ? "aucun" : text.toString().trim();
    }

    public static void applyFromConfig() {
        lod = Config.LOD.get();
        network = Config.NETWORK.get();
        profilerCache = Config.PROFILER.get();
        density = Config.DENSITY.get();
        collisions = Config.COLLISIONS.get();
        projectiles = Config.PROJECTILES.get();
        explosions = Config.EXPLOSIONS.get();
        mind = Config.MIND.get();
        tide = Config.TIDE.get();
        boxes = Config.BOXES.get();
        // Idem : le fichier de configuration peut être rechargé en cours de partie. Voir epoch().
        epoch++;
        save = Config.SAVE.get();
        jam = Config.JAM.get();
        sleep = Config.SLEEP.get();
        bulk = Config.BULK.get();
        gather = Config.GATHER.get();
        broom = Config.BROOM.get();
        vigil = Config.VIGIL.get();
        pasture = Config.PASTURE.get();
        wire = Config.WIRE.get();
        poi = Config.POI.get();
        spill = Config.SPILL.get();
        moulds = Config.MOULDS.get();
        redstone = Config.REDSTONE.get();
        recall = Config.RECALL.get();
        senses = Config.SENSES.get();
        cadastre = Config.CADASTRE.get();
        digue = Config.DIGUE.get();
        elastique = Config.ELASTIQUE.get();
        sommaire = Config.SOMMAIRE.get();
        decay = Config.DECAY.get();
        decayDelay = Config.DECAY_DELAY.get();
        mining = Config.MINING.get();
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
        if (wire) {
            text.append("compression ");
        }
        if (poi) {
            text.append("poi ");
        }
        if (spill) {
            text.append("effets ");
        }
        if (moulds) {
            text.append("moules ");
        }
        if (redstone) {
            text.append("redstone ");
        }
        if (recall) {
            text.append("memoire ");
        }
        if (senses) {
            text.append("capteurs ");
        }
        if (cadastre) {
            text.append("cadastre ");
        }
        if (decay) {
            text.append("chute-feuilles ");
        }
        if (rationing) {
            text.append("ration ");
        }
        if (scratchPos) {
            text.append("positions ");
        }
        if (digue) {
            text.append("digue ");
        }
        if (elastique) {
            text.append("elastique ");
        }
        if (sommaire) {
            text.append("sommaire ");
        }
        if (tampon) {
            text.append("tampon ");
        }
        return text.isEmpty() ? "aucun module" : text.toString().trim();
    }
}
