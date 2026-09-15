# Ponder, le guide animé de Create — ce qu'il fait vraiment

Note de lecture. Dépôt étudié : <https://github.com/Creators-of-Create/Ponder>, la
bibliothèque extraite du mod Create, clonée dans un dossier temporaire hors de ce
dépôt. Licence **MIT**. Le code de Create lui-même n'a pas eu à être lu : la
bibliothèque extraite contient l'intégralité du système, plus `catnip`, sa
bibliothèque utilitaire, vendorée dans le même arbre.

**Version visée par ce code : Minecraft 1.21.1** (`gradle.properties` :
`minecraft_version = 1.21.1`, `neo_version = 21.1.206`). C'est le fait le plus
important de cette note, et on y revient au § 6 : *tout* l'étage de rendu de
Ponder est écrit contre des API supprimées en 26.2.

Les chemins ci-dessous sont relatifs à
`common/src/main/java/net/createmod/` dans le dépôt de Ponder.

---

## 1. Le monde factice

### 1.1 La classe, et de quoi elle hérite

```
net.minecraft.world.level.Level          (vanilla, abstraite)
  └─ WrappedLevel        catnip/levelWrappers/WrappedLevel.java
       └─ SchematicLevel  catnip/levelWrappers/SchematicLevel.java
            └─ PonderLevel  ponder/api/level/PonderLevel.java
```

`PonderLevel` **étend réellement `Level`**. Ce n'est pas un détail : c'est ce qui
lui permet de passer tous les `instanceof Level` de vanilla, donc de faire
fonctionner tels quels les renderers de blocs-entités, `BlockState#getShape`, le
placement de structures, etc.

`WrappedLevel` emprunte tout au vrai monde du joueur dans son constructeur —
`getLevelData()`, `dimension()`, `registryAccess()`, `getLightEngine()` — puis
neutralise tout ce qui est effet de bord : `playSound` vide, `gameEvent` vide,
`players()` vide, `getEntity(int)` nul. Conséquence pratique : **une scène ne peut
être compilée qu'en jeu**, il lui faut un `Minecraft.getInstance().level` vivant.

### 1.2 Pas de chunks : une `HashMap`

C'est le cœur de l'astuce, et il tient en quatre champs (`SchematicLevel`) :

```java
protected Map<BlockPos, BlockState> blocks;
protected Map<BlockPos, BlockEntity> blockEntities;
protected List<Entity> entities;
protected BoundingBox bounds;
```

`getBlockState` traduit la position par une ancre et rend `AIR` hors bornes ;
`setBlock` écrit dans la map et **étend la boîte englobante** au lieu d'allouer
quoi que ce soit. Il n'existe **aucun chunk**. `getChunkSource()` rend un
`SchematicChunkSource` dont le seul rôle est de ne pas lever de
`NullPointerException` : il rend un `EmptierChunk` qui répond `VOID_AIR` partout.

La lumière est traitée avec le même minimalisme : `getBrightness` rend `15`,
`getShade` rend `1f`, `getMaxLocalRawBrightness` rend `15`. **Aucun moteur de
lumière n'est instancié.** `PonderLevel` ajoute par-dessus un simple scalaire
`overrideLight` poussé et dépilé par le rendu (`pushFakeLight` / `popLight`) ;
c'est lui qui produit les fondus d'apparition, sans reconstruire la géométrie.
L'ombrage directionnel, lui, ne vient pas du monde mais de l'état graphique
global : `RenderSystem.setupLevelDiffuseLighting(...)` dans l'écran.

### 1.3 Le chargement : un NBT de structure, dans les *assets*

Oui, c'est bien un NBT de structure vanilla — le format du bloc de structure,
gzippé — et il est chargé par le gestionnaire de ressources **client**, donc
depuis `assets/` et non `data/`
(`ponder/foundation/registration/PonderSceneRegistry.java`) :

```java
String path = "ponder/" + location.getPath() + ".nbt";
```

Chemin exact attendu pour un décor `mon_mod:machin/scene_3` :

```
assets/mon_mod/ponder/machin/scene_3.nbt
```

Le pipeline de compilation complet est court et n'écrit aucun désérialiseur :

1. `NbtIo.read` puis `StructureTemplate.load(BuiltInRegistries.BLOCK.asLookup(), nbt)` ;
2. `activeTemplate.placeInWorld(level, BlockPos.ZERO, …, Block.UPDATE_CLIENTS)` —
   **vanilla appelle `level.setBlock`, qui tombe dans la `HashMap`**. Tout le
   truc est là : on laisse vanilla poser la structure dans un faux monde ;
3. `level.createBackup()` fige l'état de départ en NBT ;
4. le scénario est exécuté, une fois, pour produire la liste d'instructions ;
5. `scene.begin()`.

Les blocs-entités sont créés **paresseusement** dans `getBlockEntity` : si le
bloc en a un, on appelle `newBlockEntity` et on le mémorise. Ils ne sont pas
tickés par le monde mais par l'élément de scène, qui va chercher leur
`BlockEntityTicker` et l'appelle lui-même. Les entités vivent dans une `List` et
sont tickées à la main ; une astuce amusante fait croire à l'entité qu'il n'y a
rien sous `Y = 0` pendant son tick, de sorte que ce qui tombe hors du décor est
simplement supprimé au lieu de s'accumuler.

### 1.4 Le rectangle de l'écran : il n'y en a pas

C'est le point le plus contre-intuitif de toute cette lecture, et il vaut d'être
dit clairement : **Ponder ne rend pas sa scène dans un rectangle.** Pas de
`glViewport`, pas de `glScissor`, pas de cible de rendu intermédiaire.

`ponder/foundation/ui/PonderUI.java`, méthode `renderScene` :

```java
Matrix4f matrix4f = new Matrix4f(RenderSystem.getProjectionMatrix());
matrix4f.translate(0, 0, 800);
RenderSystem.setProjectionMatrix(matrix4f, VertexSorting.DISTANCE_TO_ORIGIN);
poseStack.pushPose();
poseStack.translate(0, 0, -800);
```

La projection reste **celle de l'interface**, c'est-à-dire une **orthographique**.
Le seul ajustement est un décalage de 800 unités de la plage de profondeur, pour
que les blocs — qui s'étendent loin en Z une fois tournés — ne soient pas coupés
par le plan proche. La scène est dessinée sur tout l'écran ; son cadrage est
uniquement le fait de la transformation qui la place et l'échelle.

D'où l'aspect axonométrique de toutes les scènes de Create : **il n'y a aucune
perspective**, et ce n'est pas un choix de style, c'est la matrice de l'interface
qu'on a réutilisée telle quelle.

La transformation, elle, est une pile de rotations
(`PonderScene.SceneTransform#apply`) :

```java
ms.translate(width / 2, height / 2, 200 + offset);
ms.mulPose(Axis.XP.rotationDegrees(-35));
ms.mulPose(Axis.YP.rotationDegrees(55));
…
UIRenderHelper.flipForGuiRender(ms);   // scale(1, -1, 1)
float f = 30 * scaleFactor;
ms.scale(f, f, f);                     // trente pixels d'interface par bloc
```

`sceneToScreen(Vec3)` est donc une simple multiplication par cette matrice, **sans
division perspective** — c'est ce qui permet aux bulles de texte de pointer un
point du monde pour presque rien. `screenToScene` est l'inverse, écrit à la main
en dépilant les rotations une à une plutôt qu'en inversant la matrice, et sert au
pointage à la souris du mode loupe.

---

## 2. Le scénario

### 2.1 Un scénario est du code, exécuté une seule fois

`ponder/api/scene/PonderStoryBoard.java`, dans son intégralité :

```java
@FunctionalInterface
public interface PonderStoryBoard {
    void program(SceneBuilder scene, SceneBuildingUtil util);
}
```

C'est tout. **Aucun langage dédié, aucun fichier de données, aucune analyse
syntaxique.** Un scénario est une fonction impérative Java dont le seul effet est
d'**empiler des instructions dans une liste**, et `program` n'est appelée
**qu'une fois**, à la compilation de la scène — jamais au rendu. Une boucle `for`
dans un scénario est donc *déroulée* : vingt tours de boucle produisent
littéralement quarante instructions dans la liste.

Une scène de démonstration complète, telle qu'elle est écrite
(`ponder/foundation/content/DebugScenes.java`) :

```java
public static void coordinateScene(SceneBuilder scene, SceneBuildingUtil util) {
    scene.title("debug_coords", "Coordinate Space");
    scene.showBasePlate();
    scene.idle(10);
    scene.world().showSection(util.select().layersFrom(1), Direction.DOWN);

    Selection xAxis = util.select().fromTo(2, 1, 1, 4, 1, 1);
    …
    scene.idle(10);
    scene.overlay().showOutlineWithText(xAxis, 20)
        .colored(PonderPalette.RED)
        .text("Das X axis");
    scene.idle(20);
    …
}
```

### 2.2 La séparation verbe / nom

Deux objets, et c'est une bonne idée :

- **`SceneBuilder`** — le *verbe*, ce qui arrive. Il ouvre cinq sous-familles :
  `world()`, `overlay()`, `effects()`, `special()`, `debug()`.
- **`SceneBuildingUtil`** — le *nom*, ce qui est désigné. `select()`, `vector()`,
  `grid()`. **Aucune de ses méthodes n'ajoute d'instruction** : ce sont des
  constructeurs de valeurs, purs, dont les résultats se réutilisent.

`Selection` est algébrique : `add`, `substract` *(sic)*, `copy`, `getCenter`.

### 2.3 Le vocabulaire, famille par famille

**Temps et méta** (`SceneBuilder`) : `idle(ticks)`, `idleSeconds(s)`,
`markAsFinished()`, `rotateCameraY(degrés)`, `addKeyframe()`,
`addLazyKeyframe()`, `title(id, titre)`, `configureBasePlate(...)`,
`scaleSceneView(f)`, `showBasePlate()`.

**Monde** (`WorldInstructions`) — la famille la plus fournie :

- visibilité : `showSection(selection, direction)`, `hideSection`,
  `showIndependentSection` (qui rend un `ElementLink` manipulable),
  `restoreBlocks` ;
- transformation : `rotateSection(link, x, y, z, durée)`,
  `moveSection(link, offset, durée)`, `configureCenterOfRotation` ;
- blocs : `setBlock(s)`, `replaceBlocks` (ne touche pas l'air, contre `setBlocks`
  qui le remplace — un seul booléen les sépare), `destroyBlock`, `modifyBlock`,
  `cycleBlockProperty`, `toggleRedstonePower`,
  `incrementBlockBreakingProgress` ;
- entités : `createEntity(factory)`, `createItemEntity(pos, élan, pile)`,
  `modifyEntity(link, …)`, `modifyEntitiesInside(classe, zone, …)` ;
- blocs-entités : `modifyBlockEntityNBT`, `modifyBlockEntity`.

**Incrustations** (`OverlayInstructions`) — le fichier fait trente-six lignes :

```java
TextElementBuilder  showText(int duration);
TextElementBuilder  showOutlineWithText(Selection selection, int duration);
InputElementBuilder showControls(Vec3 sceneSpace, Pointing direction, int duration);
void chaseBoundingBoxOutline(PonderPalette color, Object slot, AABB box, int duration);
void showOutline(PonderPalette color, Object slot, Selection selection, int duration);
void showLine(PonderPalette color, Vec3 start, Vec3 end, int duration);
void showControls(...); void showScrollInput(...); void showFilterSlotInput(...);
```

Le paramètre `Object slot` est une **clé d'identité opaque** : réemployer le même
objet fait que la nouvelle surbrillance *poursuit* l'ancienne au lieu d'en créer
une seconde. Les deux constructeurs fluents qui sont rendus méritent d'être
cités : `TextElementBuilder` offre `colored`, `pointAt(Vec3)`, `text`,
`placeNearTarget`, `attachKeyFrame` ; `InputElementBuilder` offre `withItem`,
`leftClick`, `rightClick`, `scroll`, `whileSneaking`.

**Effets** : `emitParticles(pos, émetteur, quantitéParCycle, cycles)`,
`indicateRedstone(pos)`, `indicateSuccess(pos)`. La quantité par cycle est un
*flottant*, avec tirage au sort pour le reste — un joli détail.

**Spécial** : perroquets et wagonnets, plus `movePointOfInterest(pos)`, qui
déplace le *regard* de la scène. Rien de cela n'a de sens ici.

### 2.4 Le modèle d'exécution, et la seule instruction qui bloque

Quatre crochets seulement (`PonderInstruction`) : `onScheduled`, `tick`,
`isComplete`, `isBlocking`. La boucle qui les consomme
(`PonderScene#tick`) tient en dix lignes :

```java
for (Iterator<PonderInstruction> it = activeSchedule.iterator(); it.hasNext(); ) {
    PonderInstruction instruction = it.next();
    instruction.tick(this);
    if (instruction.isComplete()) {
        it.remove();
        if (instruction.isBlocking()) break;
        continue;
    }
    if (instruction.isBlocking()) break;
}
```

À chaque tick on parcourt la liste **depuis le début**, en tickant tout ce qu'on
rencontre, jusqu'à buter sur une instruction bloquante non terminée. Or **une
seule instruction est bloquante dans tout le vocabulaire : `idle`.** Tout le
reste part et continue en fond.

C'est cela qui rend les scénarios lisibles : on écrit une séquence, on obtient du
parallélisme. Une rotation de soixante ticks et un déplacement de cinquante ticks
écrits l'un sous l'autre démarrent le même tick et tournent ensemble, et le
`idle(40)` qui suit ne les interrompt pas.

Corollaire, moins heureux : **seules les instructions bloquantes comptent dans la
durée**. `TickingInstruction#onScheduled` fait
`if (isBlocking()) scene.addToSceneTime(totalTicks)`. Un `showText(1000)`
n'allonge donc pas la barre de progression d'un pixel.

### 2.5 L'enregistrement

Un mod implémente `PonderPlugin` (`getModId`, `registerScenes`, `registerTags`,
`registerSharedText`). Le stockage est une multimap `identifiant d'objet →
scénarios` : **un objet peut porter plusieurs scènes**, présentées en carrousel.
Les scènes se déclarent un ordre entre elles (`orderBefore` / `orderAfter`),
résolu par un tri topologique Guava avec rattrapage en cas de cycle.

---

## 3. Le temps

### 3.1 Vingt ticks par seconde, et un ralentisseur

`PonderUI#tick` est appelé par le jeu à 20 Hz et incrémente une horloge propre,
`ponderTicks`. Le rendu, lui, interpole avec des `partialTicks`.

La ruse qui rend tout le reste possible tient dans
`catnip/animation/AnimationTickHolder` : toute demande d'heure faite sur un
`PonderLevel` est **détournée** vers l'horloge de l'écran.

```java
return level instanceof PonderLevel ? PonderUI.ponderTicks : getTicks();
```

C'est ce qui permet aux renderers vanilla de tourner sans modification tout en
obéissant à la scène.

Il existe en plus un mode « lecture confortable » qui **étale un tick logique sur
trois ticks client** quand une fenêtre de texte est affichée, en renormalisant
les `partialTicks` sur l'intervalle élargi — la scène va trois fois moins vite et
reste parfaitement fluide.

### 3.2 La pause

Il n'y a **pas de bouton pause**. La pause, c'est le mode *loupe* (touche `Q` par
défaut, celle de « jeter »). Quand il est actif, l'horloge n'avance plus **et**
la fraction de tick est gelée à la valeur capturée au moment du gel
(`ponderPartialTicksPaused`) — sans quoi l'image tremblerait au milieu d'une
interpolation. Le mode sert aussi à pointer un bloc de la scène pour l'identifier.

### 3.3 Les images-clés

Une image-clé est **un entier, et rien d'autre** :

```java
public void markKeyframe(int offset) {
    if (!stoppedCounting) keyframeTimes.add(totalTime + offset);
}
```

Ce ne sont **pas** des clés d'animation — on n'interpole rien entre elles. Ce
sont des **repères de chapitre**, comme sur un DVD. Elles sont posées dans
`onScheduled`, donc **calculées avant toute lecture**, en une passe sur la liste.
`addLazyKeyframe()` pose le repère six ticks plus loin, le temps que le texte
soit apparu.

Elles servent à une seule chose, et c'est important : **elles sont la seule
granularité de navigation offerte à l'utilisateur.** La barre de progression
n'accepte de clic que sur un repère, et se désactive entièrement s'il n'y en a
aucun.

### 3.4 Le rembobinage — le point crucial

**Il n'y a aucun retour en arrière. C'est un rejeu intégral depuis zéro, en
accéléré, sans rendu.**

Cela se lit en deux endroits qui s'enchaînent. D'abord `PonderScene` refuse net :

```java
public void seekToTime(int time) {
    if (time < currentTime)
        throw new IllegalStateException("Cannot seek backwards. Rewind first.");
    while (currentTime < time && !finished) {
        forEach(e -> e.whileSkipping(this));
        tick();
    }
    forEach(WorldSectionElement.class, WorldSectionElement::queueRedraw);
}
```

Une boucle `while` synchrone, tous les ticks manquants exécutés dans la même
image. Puis l'écran, qui organise la marche arrière :

```java
public void seekToTime(int time) {
    if (getActiveScene().getCurrentTime() > time)
        replay();
    getActiveScene().seekToTime(time);
    if (time != 0) coolDownAfterSkip();
}
```

**Voilà tout le rembobinage : remettre à zéro, puis rejouer en avance rapide
jusqu'à la cible.** Reculer de cinq ticks, c'est tout rejouer depuis le premier.
`replay()` appelle `begin()`, qui restaure le monde depuis le cliché NBT,
réinitialise chaque instruction, vide les repères et **recalcule la durée
totale**.

Deux pansements accompagnent ce rejeu : `whileSkipping`, qui annule les
reconstructions de géométrie pendant l'avance rapide, et `coolDownAfterSkip`, qui
force `partialTicks = 0` pendant quinze ticks après un saut — sans quoi les
interpolations, dont la valeur précédente est devenue absurde, produiraient un
sursaut visible.

Les conséquences sont lourdes : le scénario doit être **déterministe et
rejouable**, le coût d'un saut est en **O(temps visé)** et non en O(1), et il
n'existe qu'un seul cliché, celui de l'instant zéro.

### 3.5 Les interpolations

`catnip/animation/LerpedFloat` est la primitive universelle, à deux étages : une
interpolation de rendu (`previousValue` → `value`, paramétrée par les fractions de
tick) et un *poursuiveur* qui fait avancer la valeur vers sa cible à chaque tick.

**Il n'y a pas de bibliothèque de courbes.** Tout Ponder tient sur trois
poursuiveurs :

```java
Chaser IDLE   = (c, s, t) -> (float) c;
Chaser EXP    = exp(Double.MAX_VALUE);          // v += (cible - v) * vitesse
Chaser LINEAR = (c, s, t) -> (float) (c + Mth.clamp(t - c, -s, s));
```

plus quelques `fade * fade` écrits en dur pour les fondus. `EXP` — la décroissance
exponentielle, c'est-à-dire l'atténuation classique — est de loin le plus employé.
C'est pauvre, et c'est un endroit où on peut faire mieux sans effort.

---

## 4. Les surbrillances et les bulles

### 4.1 Le registre, avec extinction automatique

`catnip/outliner/Outliner` tient une `Map<Object, OutlineEntry>` où la clé est le
« créneau » évoqué plus haut. Chaque entrée porte un compte à rebours : une
surbrillance qu'on cesse de rafraîchir s'éteint toute seule en huit ticks, avec
une courbe cubique sur l'opacité. Les instructions de surbrillance sont donc
d'une simplicité désarmante — elles rappellent l'`Outliner` **à chaque tick** de
leur durée, et n'ont rien à faire pour s'arrêter.

### 4.2 Comment le contour est dessiné

**Il n'y a aucun `GL_LINES`.** Les « lignes » sont des **parallélépipèdes fins** :

```java
public void bufferCuboidLine(…, Vector3f origin, Direction direction,
                             float length, float width, …) {
    float halfWidth = width / 2;
    minPos.set(origin.x() - halfWidth, …);
    maxPos.set(origin.x() + halfWidth, …);
    switch (direction) { case UP -> maxPos.add(0, length, 0); … }
    bufferCuboid(pose, consumer, minPos, maxPos, color, lightmap, disableNormals);
}
```

L'épaisseur est ainsi constante **dans l'espace du monde** — une arête proche est
plus épaisse qu'une arête lointaine — et l'on échappe aux caprices de la largeur
de trait matérielle, qui n'est pas portable. Les douze arêtes d'une boîte sont
douze petits pavés. Le corps de la boîte, lui, est en quads translucides, dessinés
dans un tampon *tardif* pour passer après la géométrie opaque.

`BlockClusterOutline` fait mieux pour un ensemble de blocs : il ne dessine que les
faces et arêtes **visibles**, celles entre deux blocs voisins s'annulant. C'est ce
qui donne une silhouette propre plutôt qu'un grillage.

### 4.3 L'ancrage des bulles au monde

Les bulles ne sont **pas** dessinées en 3D. Ce sont des incrustations 2D, rendues
après la scène, qui **projettent** leur point d'ancrage par `sceneToScreen`. Et
le détail de mise en page vaut d'être retenu (`TextWindowElement#render`) :

```java
Vec2 sceneToScreen = transform.sceneToScreen(vec, partialTicks);
boolean settled = transform.xRotation.settled() && transform.yRotation.settled();
float pY = settled ? (int) sceneToScreen.y : sceneToScreen.y;
float yDiff = (screen.height / 2f - sceneToScreen.y - 10) / 100f;
float targetX = (screen.width * Mth.lerp(yDiff * yDiff, 6f / 8, 5f / 8));
```

L'ordonnée de la boîte **suit** le point projeté, mais son abscisse est **figée**
aux cinq huitièmes de l'écran. La colonne de texte reste donc calme pendant que
la caméra tourne, et seul le trait de rappel bouge. Et quand les rotations sont
au repos, les coordonnées sont arrondies à l'entier : sans cela la police
tremblerait en sous-pixel.

Le trait de rappel lui-même est un dégradé de deux pixels de haut, étiré par un
`scale`. Rien de plus.

---

## 5. Le branchement

Il n'y a **aucun code JEI, EMI ou REI dans la bibliothèque Ponder** — j'ai
vérifié, l'arbre n'en contient pas une ligne. Ce branchement-là vit dans Create.
Ponder, lui, expose trois chemins d'ouverture :

1. **Maintenir une touche sur un objet survolé.** `W` par défaut, via un
   `ConflictSafeKeyMapping` qui permet de réutiliser une touche déjà prise sans
   conflit déclaré. Une barre de progression est *injectée dans l'infobulle* de
   l'objet et se remplit tant qu'on maintient ; à plein, l'écran s'ouvre. Seuls
   les objets pour lesquels une scène existe sont éligibles.
2. **Une commande `/ponder [scène] [joueurs]`**, qui est une commande **serveur**
   et passe donc par un paquet vers le client pour ouvrir l'écran. Ouvrir chez
   *un autre joueur* demande le niveau 2.
3. **La navigation interne** : un index, un index d'étiquettes, un écran par
   étiquette.

Lanterne n'a pas JEI, et n'en veut pas. Le premier chemin (infobulle + touche
maintenue) est le plus élégant mais demande de se greffer sur le rendu des
infobulles. Le deuxième est, dans notre cas, **plus simple que chez Ponder** :
NeoForge 26.2 expose `RegisterClientCommandsEvent`, donc une commande purement
cliente, sans paquet, sans serveur, sans permission.

---

## 6. Le coût, et ce qui de tout cela est portable en 26.2

### 6.1 Ce qui coûte cher chez Ponder

**Le rendu des blocs.** Il n'y a ni `LevelRenderer`, ni maillage de chunk. Chaque
section de monde est *tesselée bloc par bloc* par
`ModelBlockRenderer.tesselateBlock`, puis mise en cache dans un `SuperByteBuffer`.

Or **un `SuperByteBuffer` n'est pas un tampon graphique** : c'est un gabarit de
sommets **en mémoire Java**, neuf entiers par sommet. `renderInto` **parcourt
chaque sommet, lui applique la matrice, et le ré-émet** dans le flux de rendu. Ce
qui est mis en cache, c'est donc le *modelage* — le coûteux : recherche du modèle,
occlusion ambiante, masquage de faces. Ce qui est payé **à chaque image**, c'est
une transformation sommet par sommet **sur le processeur**, plus un renvoi complet
vers la carte graphique.

C'est tenable parce qu'une scène fait cinq à seize blocs de côté, et c'est
*obligatoire* parce que les sections bougent : une section animée interdirait de
toute façon un tampon statique.

Le deuxième poste est le **rejeu** : reculer coûte O(temps visé), et à chaque
saut toute la géométrie est invalidée puis reconstruite.

Le troisième est plus sournois : chaque scène possède **sa propre instance** de
monde factice et sa propre entrée de cache, la clé étant un `hashCode` d'identité.
Rien n'est partagé entre scènes.

### 6.2 Ce qui est mort en 26.2

C'est massif, et cela décide de tout. En vrac, tout ce dont dépend l'étage de
rendu de Ponder et qui n'existe plus :

| Ce que Ponder appelle | État en 26.2 |
|---|---|
| `RenderSystem.backupProjectionMatrix / setProjectionMatrix / restore` | supprimé |
| `RenderSystem.setupLevelDiffuseLighting` | supprimé, l'éclairage est passé dans le pipeline |
| `RenderSystem.enableBlend / enableDepthTest / enableScissor` | supprimé, l'état est déclaratif |
| `MultiBufferSource`, `SuperRenderTypeBuffer` | **supprimé**, remplacé par la soumission (`SubmitNodeCollector`) |
| `RenderType.chunkBufferLayers()`, `RenderType.create(...)` | remanié en `RenderPipeline` + `RenderType` mince |
| `BakedModel`, `ModelBlockRenderer.tesselateBlock(...)` | `BakedModel` **n'existe plus** (`BlockStateModel` / `BlockModelPart`) |
| `BlockEntityRenderer.render(be, pt, PoseStack, MultiBufferSource, …)` | remplacé par la soumission |
| `EntityRenderDispatcher.render(entity, …, MultiBufferSource, …)` | passe par des `EntityRenderState` |
| `Tesselator` + `BufferUploader.drawWithShader` (particules) | supprimé |
| `RenderSystem.getModelViewStack()` | supprimé |

Ce qui **se porterait** tel quel, en revanche : le faux monde par `HashMap`, le
cliché et sa restauration, le masque de sélection, la mathématique de la
transformation de scène, le modèle d'instructions, le registre de surbrillances
et l'ancrage des bulles par projection.

### 6.3 Ce qu'on en garde pour Lanterne, et ce qu'on refuse

Lanterne est un mod de performance. Un guide qui ferait tomber le taux d'images
serait une contradiction, et il y a plus grave : **l'étage de rendu de Ponder
devrait être réécrit en entier**, contre un système de soumission neuf, sans
pouvoir lancer le client une seule fois pour vérifier. C'est le genre de pari
qu'on perd.

Les trois choix qui en découlent sont écrits dans le javadoc de
`client/ponder/Stage.java`, `Scene.java` et `Theatre.java`, et se résument ici :

1. **Pas de faux monde.** Un décor est une liste de cases et de piles d'objets,
   dessinées par `GuiGraphicsExtractor#item`. En 26.2 le rendu d'un objet
   d'interface est **mis en cache dans un atlas de textures** puis affiché comme
   un simple quad : cent blocs à l'écran coûtent cent quads tirés d'une texture,
   pas cent modèles tesselés. C'est moins cher que Ponder d'un ordre de grandeur,
   et cela n'emploie **aucune API disparue**.
2. **Pas de rejeu.** Une scène est une **fonction pure du temps** : chaque
   figurant connaît son emploi du temps, on l'évalue à l'instant demandé. Reculer,
   avancer, mettre en pause, faire défiler à la main coûtent tous exactement la
   même chose qu'avancer, c'est-à-dire rien. C'est plus simple que Ponder *et*
   plus capable — là où sa barre n'accepte le clic que sur un repère, la nôtre
   accepte n'importe quel instant.
3. **Pas de rotation de caméra.** Le rendu d'objet impose l'angle de l'inventaire
   (30° / 225°, la même famille axonométrique que le -35° / 55° de Ponder). On
   garde donc le déplacement et le zoom, qui sont continus et suffisent, et on
   perd la rotation. C'est le prix, il est écrit noir sur blanc, et il est bien
   moindre que celui d'un étage de rendu à réécrire à l'aveugle.
