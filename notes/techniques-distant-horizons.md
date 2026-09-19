# Distant Horizons — les techniques réelles, pour mémoire (pas un chantier)

> Note de veille technique, pas un plan de construction. Demande explicite de l'utilisateur : « ajoute
> ses techniques et trucs cool dans la base de connaissance (pas le mod) juste comme ça si une IA
> cherche un truc elle saura comment faire ». **Rien n'est construit ici, rien n'est promis pour plus
> tard** — c'est un compte rendu de ce que Distant Horizons fait réellement, pour qu'une future session
> n'ait pas à redécouvrir tout ça de zéro si elle a un jour besoin de s'en inspirer.

> **Méthode, comme partout ailleurs dans ce dépôt** : **VÉRIFIÉ** signifie lu directement dans le vrai
> jar installé par l'utilisateur —
> `C:\Users\trufa\curseforge\minecraft\Instances\test 1\mods\DistantHorizons-3.3.0-26.3-fabric-neoforge.jar`
> (`unzip` vers un dossier temporaire hors du dépôt, `javap -p -c -constants` avec le JDK 21
> d'Eclipse Adoptium déjà présent sur la machine — a fonctionné sans erreur malgré un jar visiblement
> compilé pour une version de classe plus récente, et lecture en clair des ressources texte embarquées,
> notamment les shaders GLSL du dossier `assets/distanthorizons/shaders/`, qui ne sont même pas
> compilés : ce sont des fichiers texte lisibles tels quels, la source la plus fiable qui soit). **Web**
> signifie recherche publique (GitLab officiel, pages CurseForge/Modrinth, changelog communautaire) —
> cité séparément, jamais mélangé à une affirmation VÉRIFIÉ. Rien n'a été observé à l'exécution : aucun
> client n'a été lancé pour cette note, c'est une inspection statique du jar tel qu'il est posé sur le
> disque, pas une trace de son comportement réel en jeu.
>
> **Contrainte respectée** : aucun fichier de ce dépôt en dehors de celui-ci n'a été touché. Le jar
> Distant Horizons n'a pas été copié, modifié, ni ajouté à un dossier `mods` d'une instance de ce
> projet — il a seulement été extrait dans un dossier temporaire (`%TEMP%\dh_extract`) pour être lu, en
> dehors de tout dépôt Git. Rien dans `C:\Users\trufa\Downloads\tokens.tok` n'a été lu ni utilisé.

## Identité du jar inspecté

`META-INF/neoforge.mods.toml`, lu en clair : `modId = "distanthorizons"`, `version = "3.3.0"`,
licence `LGPL`, auteurs James Seibel (créateur), Leonardo Amato, Cola, coolGi, Ran, Leetom, pshsh,
suivi de bugs `https://gitlab.com/distant-horizons-team/distant-horizons/-/issues`. Le vrai dépôt
public, confirmé par recherche web (résultat GitLab officiel, pas deviné) :
[`gitlab.com/distant-horizons-team/distant-horizons`](https://gitlab.com/distant-horizons-team/distant-horizons)
pour le mod, et [`gitlab.com/distant-horizons-team/distant-horizons-core`](https://gitlab.com/distant-horizons-team/distant-horizons-core)
pour le cœur du code (déplacé depuis l'ancien `jeseibel/distant-horizons-core`). Tout ce qui suit vient
soit de la lecture directe du jar, soit de recherches sur ces pages et sur le changelog communautaire —
jamais des deux mélangées sans le dire.

---

## 1. Adressage : une position entière de l'arbre tient dans un seul `long`

`com/seibel/distanthorizons/core/pos/DhSectionPos.class` — une classe **entièrement statique**, aucun
champ d'instance, qui manipule des positions de nœud de quadtree comme des `long` primitifs plutôt que
comme des objets. Disposition des bits, lue directement (`javap -p -constants`) :

```
niveau de détail : 7 bits utiles (masque 127), décalage  0
X                : 28 bits,                    décalage  8
Z                : 28 bits,                    décalage 36
```

`encode(byte detailLevel, int x, int z)` empaquette les trois en un seul `long` par trois `OR`
successifs. Tout le reste de la classe — `getParentPos`, `getChildByIndex(0..3)`, `getAdjacentPos`,
`contains`, `forEachChild`, `getChebyshevSignedBlockDistance` — opère uniquement sur ce `long`, par
arithmétique de bits, **sans jamais allouer d'objet** pour naviguer dans l'arbre. Une position de
quadtree est aussi bon marché à manipuler, comparer ou mettre en clé de `HashMap`/`ConcurrentHashMap`
qu'un entier 64 bits ordinaire — ce qui est exactement ce que le reste du code en fait
(`WorldGenerationQueue.waitingTaskByPos : ConcurrentHashMap<Long, ...>`,
`LodQuadTree.missingGenerationPosSet : Set<Long>`, etc.).

La largeur en blocs d'un niveau de détail est triviale et vérifiée (`getDetailLevelWidthInBlocks`,
une seule instruction : `BitShiftUtil.powerOfTwo(int)`) : **largeur = 2^niveau**. Trois niveaux nommés
existent comme constantes (`SECTION_MINIMUM_DETAIL_LEVEL = SECTION_BLOCK_DETAIL_LEVEL = 6`,
`SECTION_CHUNK_DETAIL_LEVEL = 10`, `SECTION_REGION_DETAIL_LEVEL = 15`) — le niveau 6 (2⁶ = 64 blocs)
est donc la plus fine granularité représentable : **Distant Horizons ne descend jamais à l'échelle
du bloc ni même de la section vanilla (16 blocs) dans sa structure de données** ; son grain le plus
fin est un carré de 64×64 blocs. C'est cohérent avec une deuxième constante trouvée indépendamment
dans `ColumnRenderSource` (`WIDTH = 64`, valeur lue directement dans la table des constantes du
`.class`, pas déduite) : une source de rendu/donnée « pleine » couvre une grille fixe de 64×64
colonnes — les deux nombres se recoupent et confirment l'un l'autre.

## 2. La structure elle-même : un quadtree générique, pas spécifique au rendu

`com/seibel/distanthorizons/core/util/objects/quadTree/{QuadTree,QuadNode}.class` — une paire de
classes **génériques** (`QuadTree<T>`, `QuadNode<T>`), séparées du rendu. `LodQuadTree` (le quadtree
réellement utilisé pour la scène) n'est qu'une spécialisation `QuadTree<LodRenderSection>` avec de la
logique de mise à jour par-dessus — la structure de données elle-même ne sait rien du rendu, du GPU ni
même de Minecraft. C'est le genre de séparation qui permettrait, en théorie, de réutiliser le même
arbre pour autre chose qu'un rendu de terrain.

Détail intéressant sur l'implémentation, vérifié par lecture des champs et méthodes :

- **La racine de l'arbre n'est pas un pointeur unique mais un `MovableGridRingList<QuadNode<T>>`** —
  une grille 2D « en anneau » (glissante) de nœuds racines. `setCenterBlockPos(DhBlockPos2D)` recentre
  cette grille quand le joueur se déplace, en appelant deux callbacks distincts
  (`mutateOutOfBoundChildNodes`, un `Consumer` pour ce qui sort du champ et un pour ce qui y entre)
  plutôt que de reconstruire l'arbre entier à chaque déplacement. C'est la même idée qu'une fenêtre de
  chargement de chunks qui glisse sans se recréer — sauf qu'ici elle porte des racines de sous-arbres
  entiers, pas des chunks individuels.
- **Sous chaque racine, un vrai arbre de pointeurs** — `QuadNode<T>` a quatre champs `nwChild` /
  `neChild` / `swChild` / `seChild`, la structure la plus classique qui soit pour un quadtree, adressée
  par la position empaquetée du §1 plutôt que par index de tableau.

Autrement dit : le sommet de l'arbre (jusqu'où le joueur peut avoir bougé) est une grille glissante bon
marché à recentrer ; en dessous, chaque racine porte un vrai arbre à quatre enfants pour le
raffinement fin. Un compromis délibéré entre « ne jamais réallouer une structure énorme quand le
joueur avance » et « garder un vrai arbre hiérarchique pour le détail ».

## 3. Le quadtree se redécoupe à chaque tick, en fonction de la distance au joueur — formule vérifiée

`LodQuadTree.calcExpectedDetailLevel` / `calcDetailLevelFromDistance` (deux méthodes, désassemblées
avec `javap -p -c`) calculent, pour une distance donnée, le niveau de détail attendu :

```
detailLevel(distance) = clamp( floor( log(distance / detailDropOffDistanceUnit)
                                       / log(detailDropOffLogBase) ),
                                0, 14 )
```

`updateDetailLevelVariables()` (désassemblée en entier) montre d'où viennent les deux paramètres :
`detailDropOffDistanceUnit = horizontalQuality.distanceUnitInBlocks × 16` et
`detailDropOffLogBase = log(horizontalQuality.quadraticBase)` — les deux pilotés par un seul réglage
utilisateur (« qualité horizontale »). C'est une sélection de niveau de détail **logarithmique**, pas
linéaire ni par paliers fixes — la même famille mathématique que la sélection de niveau de mipmap
d'une texture (`log2(distance)`), généralisée ici à une base configurable. Le résultat est ensuite
borné par `maxLeafRenderDetailLevel` (le réglage « résolution horizontale maximale ») et par le niveau
de détail de la racine de l'arbre.

Chaque tick, `LodQuadTree.tryTick` → `updateAllRenderSections` parcourt l'arbre
(`recursivelyUpdateRenderSectionNode`) et, nœud par nœud, décide s'il faut **subdiviser** (le niveau
attendu est plus fin que le nœud actuel — `onDetailLevelTooLow`, qui ajoute des enfants via
`tryAddNodeToTree`) ou **fusionner** (le niveau attendu est plus grossier — `onDesiredDetailLevel`,
qui supprime les enfants via `recursivelyDisableChildNodes`). L'arbre respire littéralement autour du
joueur à chaque tick, plus fin près de lui, plus grossier loin — sans jamais reconstruire l'ensemble.

`QuadTreeTickNodeHolder` (une classe entièrement dédiée à ce seul rôle) accumule les décisions d'un
passage de mise à jour en plusieurs listes séparées (`nodesToEnable`, `nodesToDisable`,
`sectionsToLoad`, `nodesForWorldGen`) avant de les appliquer d'un coup — et surtout,
`getWorldGenNodesNearToFar(DhBlockPos2D)` trie les nœuds en attente de génération par distance au
joueur via un comparateur dédié (`QuadNodeNearComparator`). C'est la confirmation, au niveau du
bytecode, d'une entrée de changelog public trouvée par recherche web (« LOD updates will happen
around the player instead of FIFO ») : **ce n'est plus une simple invention marketing, la structure de
tri par distance existe bel et bien dans ce build.**

## 4. La donnée : des colonnes de segments verticaux compressés dans un `long`, pas un bloc par bloc

C'est le cœur de « comment DH tient un monde entier en mémoire sans exploser » : deux formats de
donnée en `long` empaqueté, lus directement dans `FullDataPointUtil` et `RenderDataPointUtil`
(`javap -p -constants`, dispositions de bits citées telles quelles).

**Donnée « monde », persistée** (`FullDataPointUtil`) — un `long` = **un segment vertical continu**,
pas un bloc :

```
id (bloc + biome) : 32 bits, décalage  0
hauteur du segment : 12 bits, décalage 32
Y du bas du segment : 12 bits, décalage 44
lumière du ciel     :  4 bits, décalage 56
lumière de bloc      :  4 bits, décalage 60
```

`FullDataSourceV2.dataPoints` est un tableau de `LongArrayList` — **un `LongArrayList` par colonne
(x, z)**, chaque élément de la liste étant un de ces segments empaquetés. Une colonne de pierre massive
sur 60 blocs de haut surmontée de 3 blocs de terre puis d'un bloc d'herbe puis d'air jusqu'au ciel ne
prend donc **pas** 256 entrées comme le ferait un stockage par bloc vanilla, mais 4 — un par
changement de matière, exactement comme une compression par plages (RLE) appliquée à l'axe vertical
plutôt qu'à un patron 2D. C'est une différence de nature, pas de degré, avec le stockage par bloc de
Minecraft.

**Donnée « rendu », dérivée** (`RenderDataPointUtil`) — un second format de `long`, séparé du premier,
pour ce que le GPU consomme réellement :

```
couleur RVB (8 bits chacun) + alpha (4 bits) : décalages 32/40/48/56
Y max / Y min du segment (12 bits chacun)     : décalages 20/8
lumière de bloc / de ciel (4 bits chacune)    : décalages 4/0
identifiant de matériau pour Iris (4 bits)    : décalage 60
```

La couleur y est **déjà résolue** — un RVB fixe par segment, pas une référence à retexturer/rééchantillonner
à chaque frame — calculée une fois quand la donnée de rendu est construite à partir de la donnée
monde. À grande distance, le renderer n'a donc jamais besoin de rééchantillonner l'atlas de textures
par frame pour du terrain lointain : la couleur moyenne du segment est déjà là, prête à être posée sur
un quad. `ColumnRenderSource` (qui contient ces `long` de rendu, `WIDTH = 64` colonnes de côté)
garde aussi, en option, une petite palette de textures (`texturePalette`, `textureSetPaletteIndices` en
`ByteArrayList` — donc un index sur 8 bits, cohérent avec la constante trouvée
`MAX_TEXTURE_PALETTE_SIZE = 256`) pour un mode « LOD texturé » plus coûteux que le mode couleur pure.

`ColumnBox.addBoxQuadsToBuilder` transforme ensuite chaque pile de segments d'une colonne en quads de
boîte (une face par côté exposé d'un segment, pas par bloc) — la même idée que le culling de face
déjà documenté dans `notes/rendu-terrain-vs-sodium.md` pour le terrain vanilla, mais appliquée à des
boîtes de plusieurs mètres de haut plutôt qu'à des blocs d'un mètre.

## 5. Comment le niveau grossier est calculé à partir du fin — un mipmap appliqué à des colonnes de voxels

`FullDataSourceV2.downsampleFromOneAboveDetailLevel` / `mergeInputTwoByTwoDataColumn` fusionnent
quatre colonnes enfants (2×2) en une colonne parente au niveau de détail immédiatement supérieur —
exactement la logique d'une chaîne de mipmap, mais appliquée à des colonnes de segments verticaux
plutôt qu'à des pixels d'image :

- Pour une valeur **catégorielle** (identifiant de bloc/biome) :
  `determineMostCommonValueInColumnSlice` — un **vote majoritaire** parmi les quatre colonnes sources.
- Pour une valeur **numérique** (hauteur, lumière) :
  `determineAverageValueInColumnSlice` — une **moyenne**.

Cette chaîne est calculée une fois et mise en cache (le résultat redescend dans le même format
`FullDataSourceV2`/`LongArrayList[]` que la donnée fine, donc rien de spécial à gérer côté lecture),
pas recalculée à chaque frame ni à chaque tick — le coût de la simplification est payé une seule fois,
à la génération ou à la mise à jour de la donnée, jamais au rendu.

## 6. Persistance : SQLite, compression au choix, et un hachage pour savoir quoi refaire

Le jar embarque une copie renommée de `sqlite-jdbc` (dossier `dh_sqlite/`, relocalisé pour éviter tout
conflit avec un autre mod qui embarquerait sa propre version) — confirmé par l'arborescence extraite du
jar, pas supposé. `com/seibel/distanthorizons/core/sql/` porte un mini-ORM maison :
`AbstractDhRepo` + des dépôts spécialisés par table (`FullDataSourceV2Repo`, `ChunkHashRepo`,
`BeaconBeamRepo`) et un `DatabaseUpdater` avec des scripts SQL versionnés (`SqlScript`) — un vrai
mécanisme de migration de schéma, pas un fichier plat réécrit en entier à chaque changement de
version du mod.

**Une ligne par nœud de quadtree**, clé = le `long` de position du §1
(`FullDataSourceV2DTO.pos`). Le contenu de la ligne, lu champ par champ :

- `compressedDataByteArray` — le blob compressé des colonnes de la section elle-même.
- `compressedNorthAdjDataByteArray` / `South` / `East` / `West` — **les données de bordure des quatre
  sections voisines, mises en cache séparément et compressées elles aussi.** Sans ça, mailler les
  faces de bord d'une section obligerait à charger et garder en mémoire ses quatre voisines entières
  rien que pour lire leur première rangée de colonnes ; les avoir en cache local évite ce couplage.
- `dataChecksum` — un contrôle d'intégrité par ligne.
- `dataFormatVersion` / `compressionModeValue` — une ligne de 2024 et une ligne de 2026 peuvent
  cohabiter dans le même fichier, chacune sachant se décrire elle-même.

**La compression est un choix explicite, pas un algorithme figé** — `EDhApiDataCompressionMode`, un
`enum` à cinq valeurs lues directement : `UNCOMPRESSED`, `LZ4`, `Z_STD_BLOCK`, `Z_STD_STREAM`,
`LZMA2`. Les deux familles sont réellement présentes dans le jar, pas seulement citées en
configuration : LZ4 sous sa forme d'origine relocalisée (`DistantHorizons/libraries/jpountz/lz4/*`,
les vraies classes `LZ4Compressor`/`LZ4BlockOutputStream` de la bibliothèque `jpountz`) et Zstandard
sous forme d'un décompresseur **mis en pool** (`PooledZstdDecompressor`, `ZstdArrayCache`) — un détail
qui compte : décompresser en continu depuis plusieurs threads de fond sans réallouer un décompresseur
à chaque appel évite une pression mémoire/GC que ce genre de charge ferait sinon apparaître.

**Détection de changement, pas suivi de chaque modification de bloc** : `ChunkHashDTO(DhChunkPos pos,
int chunkHash)` — un entier de hachage stocké par position de chunk vanilla. Plutôt que d'écouter
chaque événement de pose/casse de bloc, DH semble comparer le hachage actuel d'un chunk visité à celui
qu'il avait la dernière fois qu'il en a dérivé de la donnée LOD, et ne retravaille que ce qui a
réellement changé.

**Le même DTO sert deux fois** : `FullDataSourceV2DTO` implémente à la fois le contrat de stockage SQL
(`IBaseDTO`) et `INetworkObject` (`encode`/`decode` sur un `ByteBuf` Netty) — c'est le même objet qui
est écrit sur disque et envoyé sur le réseau quand DH tourne à la fois côté serveur et client. Pas deux
formats de sérialisation à maintenir en parallèle pour la même donnée.

## 7. Génération en arrière-plan sans voler de temps de jeu — trois leviers indépendants, tous vérifiés en bytecode

C'est le point le plus directement comparable à la philosophie déjà en place dans ce dépôt
(`Terrassement`, `PortalPreload` — mesurer le vrai coût, ne jamais supposer). Trois mécanismes
séparés, empilés, chacun désassemblé :

**a) Des pools de threads dédiés, nommés, à taille et priorité contrôlées.**
`ThreadUtil.makeThreadPool(int size, String name, int priority, boolean daemon)` (désassemblée
entièrement) construit un `ThreadPoolExecutor` à taille fixe (`corePoolSize == maximumPoolSize`,
lus au même registre local dans le bytecode — donc littéralement le même nombre), file d'attente
`LinkedBlockingQueue` non bornée, et une `DhThreadFactory(name, priority, daemon)` maison qui pose
explicitement le nom et la **priorité Java** du thread. Chaque sous-système (file de génération
monde, construction de tampons GPU, récupération de données, propagation de mise à jour...) a son
propre pool nommé — utile en profileur, et surtout isolé des autres : un sous-système saturé ne
prive pas la file d'un autre de ses threads.

**b) Un réglage utilisateur qui pose vraiment la priorité OS du thread, pas juste un indice Java.**
`Config$Common$MultiThreading` expose trois réglages : `numberOfThreads`, `threadPriority` (un entier
appliqué tel quel à `Thread.setPriority` dans `DhThreadFactory`, vérifié) et `threadRunTimeRatio` — un
`Double`. Les deux premiers sont classiques ; le troisième est le plus intéressant.

**c) Un throttle par cycle de service, complètement indépendant du tick — mécanisme le plus notable
de toute cette note, désassemblé instruction par instruction.**
`RateLimitedThreadPoolExecutor extends ThreadPoolExecutor` :

```
beforeExecute(thread, tâche) :
    runStartTime.set(System.nanoTime())     // avant chaque tâche prise dans la file

afterExecute(tâche, erreur) :
    si ratio >= 1.0 : ne rien faire (pas de throttle)
    sinon :
        écoulé = System.nanoTime() - runStartTime.get()
        Thread.sleep( (écoulé / ratio - écoulé) en millisecondes )
```

Concrètement : si `threadRunTimeRatio = 0.25` et qu'une tâche vient de prendre 10 ms à s'exécuter, le
thread dort ensuite 30 ms avant de reprendre la file — un cycle total de 40 ms dont 25 % de travail
réel, quel que soit le nombre ou la taille des tâches. C'est un **throttle au niveau du thread OS,
mesuré sur le vrai temps écoulé de chaque tâche**, complètement découplé de la boucle de tick du
serveur — logique, puisque ce travail ne tourne justement *pas* sur le thread de tick. C'est un
levier d'une autre nature que ceux déjà présents dans ce dépôt (`Marée`/`Rationing`, qui mesurent et
limitent un budget *par tick*) : ici il n'y a pas de tick à budgéter, le throttle vit entièrement dans
le pool de threads lui-même. Une idée directement transférable si ce dépôt construisait un jour un
travail de fond sur de vrais threads dédiés plutôt que sur la boucle de tick (voir §11).

**d) La génération elle-même repasse par le vrai générateur de monde vanilla, pas par une
approximation maison.** `DhWorldGenerator` porte un `IChunkGenerator`/`IRoughGenerator` et appelle
plusieurs modes (`EDhApiDistantGeneratorMode` — l'énumération existe, ses valeurs exactes n'ont pas
été énumérées dans cette passe) qui échangent vraisemblablement de la complétude contre de la
vitesse. Le terrain lointain de DH est donc du **vrai terrain généré**, potentiellement à une étape
de génération moins poussée (probablement sans structures/grottes complètes à distance, seule
l'étape « surface » étant documentée publiquement comme la plus économique) — pas un terrain deviné
ou procéduralement réinventé.

**e) Priorité par proximité au joueur, à deux endroits distincts et cohérents entre eux.**
`WorldGenerationQueue.tryStartNextWorldGenTask` choisit la tâche la plus proche via une paire
`TaskDistancePair` comparée à `generationTargetPos` — le même principe que
`QuadTreeTickNodeHolder.getWorldGenNodesNearToFar` du §3, à deux étages différents du pipeline
(sélection du prochain nœud à générer, puis sélection de la prochaine tâche à lancer). Le travail de
fond converge systématiquement vers ce que le joueur est sur le point de voir.

**f) Un coût mesuré, pas supposé.** `WorldGenerationQueue.rollingAverageChunkGenTimeInMs` — une
moyenne glissante du temps réel de génération par chunk, utilisée pour estimer le travail restant
(`estimatedRemainingChunkCount`). Le même réflexe que ce dépôt applique déjà pour lui-même
(`Terrassement.rapporter`, `PortalPreload` cite `lab/Traversee`) : ne jamais publier un nombre qui
n'a pas été mesuré sur le vrai travail.

**g) Un effort de réduction de la pression mémoire à l'échelle du système**, cohérent avec une
affirmation publique du changelog (« rewrote object pooling to massively reduce memory use ») : la
classe `PhantomArrayList`/`PhantomArrayListPool` réapparaît comme type de base pour
`FullDataSourceV2`, `ColumnRenderSource` et `FullDataSourceV2DTO` — les trois plus grosses structures
de données de ce pipeline empruntent leurs tableaux à un pool plutôt que d'en allouer un neuf à
chaque section traversée, sous une charge de fond qui traite potentiellement des milliers de sections
par session.

## 8. Invalidation : que refaire quand le monde change

Vérifié à l'échelle des classes et des champs, **pas instrumenté à l'exécution** — honnêteté sur la
profondeur de cette passe : `FullDataUpdatePropagatorV2.runParentUpdates` /
`runChildUpdates`, combinés aux champs `applyToParent` / `applyToChildren` / `regenerateLeaf` de
`FullDataSourceV2`, indiquent qu'une modification à un nœud se propage **dans les deux sens** de la
hiérarchie : vers le haut (le parent, plus grossier, doit refléter le changement dans son résumé
mipmap — cohérent avec le §5) et vers le bas (les enfants plus fins doivent, le cas échéant, être
régénérés). `ChunkHashDTO` (§6) sert de déclencheur bon marché : un chunk dont le hachage n'a pas
changé depuis la dernière dérivation n'a aucune raison d'être retraité. Le tout retombe dans la même
file de fond que le §7 (`tryQueueWorldGenTask`/`queueRegeneration`) plutôt que dans un chemin séparé —
un changement de monde n'est donc pas un cas spécial pour ce pipeline, juste une nouvelle entrée dans
la même file, priorisée de la même façon.

## 9. La jointure visuelle entre terrain réel et terrain LOD — une vraie passe de post-traitement, lue en clair

C'est la réponse la plus directement vérifiable de toute cette note, parce que le fichier en cause
n'est **pas compilé** : `assets/distanthorizons/shaders/fade/gl/{dh_fade,vanilla_fade}.frag` sont du
texte GLSL lisible tel quel dans le jar, aucune décompilation nécessaire.

**Le mécanisme** : le terrain vanilla et le terrain LOD de DH sont dessinés dans des cibles de rendu
**séparées** (chacune avec sa propre couleur et sa propre profondeur — `uMcColorTexture`/
`uMcDepthTexture` pour vanilla, `uDhColorTexture`/`uDhDepthTexture` pour DH). Une passe de
post-traitement plein écran reconstruit ensuite, pour chaque pixel, sa distance au monde réel à partir
de sa profondeur et d'une matrice de projection-vue inversée (`calcViewPosition`, une fonction
partagée entre les deux shaders — le commentaire du fichier le dit lui-même : « if updated, make sure
to update the other versions as well »), puis :

```glsl
float fadeStep = smoothstep(uStartFadeBlockDistance, uEndFadeBlockDistance, fragmentDistance);
fragColor = mix(combinedMcDhColor, dhColor, fadeStep);
```

Un **fondu enchaîné progressif** (`smoothstep`, une courbe lissée en S, pas une simple interpolation
linéaire) sur une bande de distance configurable, pas une coupure nette à la limite de distance de
vue vanilla — c'est ce qui évite un « mur » visible entre le terrain net et le terrain simplifié. Le
fichier gère aussi, explicitement dans le code, plusieurs cas limites réels : les pixels où DH n'a
rien dessiné (`dhColor.a == 0`) retombent sur l'image combinée plutôt que de laisser un trou ; les
fragments au-dessus d'une hauteur maximale de niveau sont exclus du fondu (le commentaire dit
littéralement que c'est pour empêcher les nuages vanilla de s'afficher derrière les nuages DH) ; une
détection sol/ciel dépendante du mode de profondeur inversée (`uIsReverseZDepth`) évite de fondre le
ciel lui-même.

**Deux implémentations complètes existent côte à côte** — `GlDhFarFadeRenderer`/`GlDhFarFadeShader`
(chemin OpenGL classique) et `BlazeDhFarFadeRenderer` (chemin `com.mojang.blaze3d`/« blaze » — le nom
que ce jar donne en interne à son chemin de rendu moderne, cohérent avec ce que
`notes/distant-horizons-faisabilite.md` §3 avait déjà trouvé : ce build de DH parle bien à
`com.mojang.renderpearl`, le successeur de `blaze3d` sur cette branche 26.3). La logique de fondu
elle-même (le calcul `smoothstep`/`mix`) est dupliquée conceptuellement des deux côtés plutôt que
partagée à un niveau plus haut — un choix d'architecture, pas une lacune : les deux chemins de rendu
ont des cibles/API différentes, dupliquer une passe de post-traitement de cette taille coûte moins cher
que la faire cohabiter derrière une abstraction commune.

**Rapprochement avec ce qui existe déjà dans ce dépôt** : `client/upscale/Scene.java` et
`Resolve.java` manipulent déjà des `RenderTarget` et un `FrameGraphBuilder` pour composer plusieurs
passes de rendu en post-traitement (voir `notes/moteur-rendu-maison.md` §1, qui cite exactement ces
deux fichiers comme « la référence directe à suivre plutôt que d'improviser »). La technique de DH ici
— deux scènes rendues séparément, composées par une passe plein écran qui lit profondeur + couleur des
deux — est la même famille de mécanisme, à un niveau de complexité comparable. **Rien à construire
maintenant** : c'est noté ici uniquement parce que si ce dépôt avait un jour besoin d'un fondu
enchaîné entre deux représentations de la même scène (par exemple entre un rendu normal et un rendu
simplifié à très longue distance), le point d'ancrage naturel serait `client/upscale/`, pas un nouveau
sous-système.

## 10. Heuristiques de rendu bon marché, notées pour mémoire

- `Config$Client$Advanced$Graphics$Culling.overdrawPrevention` (`Float`) et
  `reduceOverdrawWithFastMovement` (`Boolean`) — un levier de qualité adaptative en fonction de la
  vitesse de déplacement de la caméra (typiquement en vol à l'élytre) ; réduire la charge de rendu
  pendant un déplacement rapide, où le joueur ne fixe de toute façon aucun détail longtemps. Le
  comportement précis derrière ce réglage n'a pas été tracé plus loin dans cette passe — seule
  l'existence du réglage est vérifiée.
- `enableCaveCulling` / `caveCullingHeight` — désactive le rendu LOD quand le joueur est sous terre,
  où le terrain lointain simplifié ne peut de toute façon rien apporter visuellement.
- `LodRenderSection.uploadRenderDataToGpuAsync()` garde un unique `CompletableFuture` en vol par
  section via un `AtomicReference` — une section déjà en cours de construction/envoi GPU n'est jamais
  reconstruite en double si elle est redemandée pendant qu'elle est déjà en cours de traitement. Un
  garde-fou simple contre le travail redondant, du genre facile à oublier.

## 11. Ce qui est déjà couvert côté Lanterne, et ce qui serait vraiment nouveau

**Déjà couvert, sous une autre forme, par ce dépôt** :

- *Charger par anticipation ce qui va être vu* — `PortalPreload` pose déjà un ticket de chargement à
  l'endroit d'arrivée avant que le joueur ne franchisse un portail ; `Tide` fait grossir la distance
  de vue progressivement. DH fait la même chose à l'échelle d'un champ LOD entier et continu plutôt
  qu'à celle d'un événement ponctuel (un franchissement de portail), mais le principe — anticiper
  plutôt que réagir — est déjà présent ici.
- *Mesurer le vrai coût avant de publier un chiffre* — `Terrassement` (verdict par comparaison
  témoin/mesuré, médiane sur plusieurs relevés) et la citation de `lab/Traversee` dans `PortalPreload`
  appliquent déjà l'éthique que `rollingAverageChunkGenTimeInMs` applique ici à un autre problème.

**Ce qui n'existe nulle part dans ce dépôt, à vérifier avant de le sous-estimer** :

- Une **structure de quadtree générique et réutilisable**, adressée par position empaquetée sur un
  seul `long`, avec une racine en grille glissante et de vrais nœuds à quatre enfants en dessous
  (§1-2). Rien de comparable n'existe dans `Lanterne` aujourd'hui — le dépôt n'a, à ce jour, aucune
  structure hiérarchique spatiale de ce genre pour quoi que ce soit.
- Un **format de donnée en colonnes de segments verticaux compressés**, distinct du stockage par bloc
  de vanilla, avec une chaîne de simplification façon mipmap calculée une fois et mise en cache
  (§4-5). Sans objet pour Lanterne aujourd'hui — ce dépôt ne stocke pas de représentation de terrain
  parallèle à celle de vanilla.
- Un **throttle au niveau du pool de threads, mesuré sur le temps réel écoulé et complètement
  indépendant du tick** (§7c, `RateLimitedThreadPoolExecutor`) — une brique d'une autre nature que les
  throttles par tick déjà présents ici (`Marée`/`Rationing`). Ne deviendrait pertinent que si ce
  dépôt lançait un jour un travail de fond sur de vrais threads dédiés plutôt que sur la boucle de
  tick du serveur — ce qu'il ne fait pas aujourd'hui.
- Une **passe de post-traitement de fondu enchaîné entre deux rendus séparés de la même scène**
  (§9) — techniquement à la portée de l'infrastructure `client/upscale/` déjà en place, mais rien
  d'équivalent n'y est construit à ce jour.

Rien de tout cela n'est proposé comme chantier ici — la demande était de documenter, pas de
s'engager. Si une session future veut un jour s'attaquer à l'un de ces points, cette note lui évite de
redécouvrir la structure interne de DH depuis zéro.

---

## Ce qui n'a pas été fait, honnêtement

- Aucun client n'a été lancé avec ce jar cette passe — tout ce qui précède est une inspection statique
  du jar posé sur le disque (bytecode + ressources texte), jamais une observation de son comportement
  réel en jeu. `notes/distant-horizons-faisabilite.md` couvre déjà la question « ce jar charge-t-il
  vraiment aux côtés de Lanterne » — cette note-ci ne la retouche pas.
- Les modes exacts de `EDhApiDistantGeneratorMode` (§7d) n'ont pas été énumérés un par un.
- Le comportement précis derrière `overdrawPrevention`/`reduceOverdrawWithFastMovement` (§10) n'a pas
  été tracé au-delà de l'existence du réglage.
- La disposition exacte des fichiers `.sqlite` sur disque (un fichier par dimension ? par monde ?) n'a
  pas été confirmée au-delà de l'existence de `LocalSaveStructure`/`ClientOnlySaveStructure`.
- Aucune ligne de code n'a été écrite, aucun mixin, aucun fichier de ce dépôt modifié en dehors de
  celui-ci.
