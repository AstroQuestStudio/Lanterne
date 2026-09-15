# Les shaders et Lanterne : ce qui compose, ce qui s'annule, ce qu'il faut régler

> Enquête close le 16 septembre 2026, Minecraft 26.2 / NeoForge 26.2.0.88.
> Sources primaires : les sources de 26.2 (`C:/mc262src`), les dépôts clonés de
> `C:/Users/trufa/Documents/Lanterne-mine` — `iris`, `sodium`, `vitrail-shaders`,
> `distanthorizons`, `entityculling` — et notre propre code.
> **[V]** = vérifié sur source primaire (fichier, ligne, citation).
> **[S]** = inféré, motivé, non prouvé.
>
> **AUCUN CLIENT N'A ÉTÉ LANCÉ.** Pas une ligne de cette note n'a été vue à l'écran.
> Le §6 dit exactement ce que seul un lancement trancherait.

---

## Pourquoi cette note existe

Le joueur veut des shaders, et il a raison de les vouloir : c'est ce qui change le jeu à l'œil.
Mais un pack de shaders **ne donne pas** d'images par seconde, il en coûte — c'est déjà écrit dans
`client/Dials.java:262`. Sur une RTX 3080, un pack complet à 1440p en plein écran descend sous les
soixante images. La lentille de ce dépôt — rendre le monde à 67 % de chaque dimension, donc **44 %
des pixels** — est exactement l'outil qui les rend jouables, *à condition qu'elle compose avec le
moteur de shaders*. Toute la question tient là.

La réponse est **oui**, et elle se démontre maillon par maillon. Le reste de la note dit à quel
prix, et ce qu'il faut régler autrement.

---

## 1. L'upscaling compose avec Iris — la chaîne, vérifiée bout à bout

### 1.1 Les quatre maillons [V]

Notre échange de cible tient à une seule hypothèse : *tout le monde relit la cible principale
pendant le rendu du niveau*. Voici la chaîne complète, chaque maillon lu dans un fichier :

1. **Notre échange a lieu avant le rendu du niveau.** `mixin/UpscaleGameRendererMixin.java` injecte
   dans `GameRenderer.render` à l'**INVOKE** de `GameRenderer.renderLevel`, donc juste avant que
   cette méthode ne s'exécute. `C:/mc262src/net/minecraft/client/renderer/GameRenderer.java:400`
   (`render`) et `:529` (`renderLevel`). [V]

2. **`GameRenderer.renderLevel` appelle `LevelRenderer.render`.** Vérifié dans le corps de la
   méthode : `this.minecraft.levelRenderer.render(this.resourcePool, deltaTracker, …)`.
   `C:/mc262src/net/minecraft/client/renderer/LevelRenderer.java:156`. [V]

3. **Iris prend la main au sommet de `LevelRenderer.render`.**
   `Lanterne-mine/iris/common/src/main/java/net/irisshaders/iris/mixin/MixinLevelRenderer.java:121` —
   `@Inject(method = "renderLevel", at = @At("HEAD"))` → `pipeline.beginLevelRendering()`.
   *(Le nom de la méthode diffère parce que ce clone vise 26.1.2 ; voir §1.2.)* [V]

4. **`beginLevelRendering()` dimensionne TOUT le pipeline sur la cible principale.**
   `iris/common/src/main/java/net/irisshaders/iris/pipeline/IrisRenderingPipeline.java:875`
   ouvre la méthode ; aux lignes **943 et 948** :

   ```java
   RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
   …
   boolean changed = renderTargets.resizeIfNeeded(…, main.width, main.height, …);
   ```

   et les lignes 951-975 propagent la taille à `beginRenderer`, `prepareRenderer`,
   `deferredRenderer`, `compositeRenderer`, `finalPassRenderer`, au `shaderStorageBufferHolder` et à
   toutes les images personnalisées. [V]

**Conclusion : la totalité du pipeline de shaders — G-buffers, passes de composition, passe
finale — tourne à l'échelle réduite.** C'est le meilleur cas possible : le poste le plus cher de
l'image est celui qui encaisse la réduction.

### 1.2 Le seul endroit où il reste de l'inférence [S]

Le clone d'Iris de la mine est la **branche 26.1.2**, pas 26.2 :
`iris/build.gradle.kts:7-13` — `MINECRAFT_VERSION = "26.1.2"`, `MOD_VERSION = "1.11.4"`,
`SODIUM_DEPENDENCY_NEO = "net.caffeinemc:sodium-neoforge-mod:0.9.2+mc26.1.2"`. HEAD du 12/09/2026. [V]

Or ce code appelle `Minecraft.getInstance().getMainRenderTarget()`, et **cette méthode n'existe plus
en 26.2** : zéro occurrence dans tout `C:/mc262src`. Treize fichiers de vanilla passent désormais par
`gameRenderer.mainRenderTarget()`. [V]

C'est une bonne nouvelle déguisée en mauvaise. Le portage d'Iris vers 26.2 **n'avait pas le choix** :
il n'existe qu'un seul chemin pour obtenir la cible principale en 26.2, et c'est le champ que nous
échangeons. La seule façon de nous échapper serait de **mettre l'objet en cache** — et Iris s'y
interdit explicitement, en nous nommant :

```
// NB: It is not safe to cache the render target due to mods like Resolution Control
//     modifying the render target field.
```

`iris/common/src/main/java/net/irisshaders/iris/uniforms/ViewportUniforms.java:25`. Les trois
uniformes `viewWidth`, `viewHeight`, `aspectRatio` sont des fournisseurs `PER_FRAME` qui relisent la
cible à chaque usage (lignes 27-28 et 36). [V]

**Ce qui reste [S] est donc étroit et bien cerné** : que le portage 26.2 d'Iris n'ait pas, en passant,
introduit un cache. Rien ne le suggère, tout l'interdit, mais personne ne l'a lu.

### 1.3 Aucune redirection concurrente sur la cible [V]

Ni Iris ni Sodium ne posent de `@Redirect` ou d'`@Accessor` en écriture sur
`GameRenderer.mainRenderTarget`. Le seul mixin d'Iris sur `RenderTarget`
(`iris/common/…/mixin/MixinRenderTarget.java:44`) s'injecte dans `destroyBuffers()` pour incrémenter
un compteur de version — précisément pour **détecter** qu'une cible a été refaite. C'est un
mécanisme de survie aux mods comme le nôtre, pas un obstacle.

---

## 2. Nos modules sous Iris, un par un

### 2.1 Le Voile des entités : intact, et par chance [V]

**Iris a sa PROPRE boucle de collecte d'entités pour la passe d'ombre.**
`iris/common/src/main/java/net/irisshaders/iris/shadows/ShadowRenderer.java:691-719` :

```java
private void extractVisibleEntities(Camera camera, Frustum frustum, …) {
    for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {
        …
        if (Minecraft.getInstance().getEntityRenderDispatcher()
                .shouldRender(entity, frustum, d, e, f) || …) {
```

Elle appelle `EntityRenderDispatcher.shouldRender` **depuis son propre corps**. Or notre
`mixin/EntityCullMixin.java` n'enveloppe pas cette méthode : il enveloppe **l'appel** qui en est fait
depuis `LevelExtractor.isEntityVisible`, via `@WrapOperation(method = "isEntityVisible", …)`.

**Le Voile ne s'applique donc pas à la passe d'ombre.** Une vache cachée derrière une colline
continue de projeter son ombre.

Le plus remarquable est que ce n'est pas une adaptation : le fichier explique déjà pourquoi il
enveloppe l'appel plutôt que la méthode — *« un mixin posé sur la méthode elle-même s'appliquerait à
tous, y compris là où la notion de "caché par un mur" n'a aucun sens »*. Le raisonnement visait
l'aperçu d'entité d'une interface. Il nous protège d'Iris par surcroît.

**Le prix, dit franchement [S] :** les entités que le Voile écarte du rendu principal sont
quand même **entièrement extraites** par la passe d'ombre (`ShadowRenderer.java:713`,
`extractEntity`). Le gain de « 1 000 vaches sous un toit : 29,9 → 238,8 images/s » **ne tiendra pas**
avec shaders. Il reste réel — l'extraction principale est toujours économisée — mais amputé de la
moitié qui part dans l'ombre. L'ampleur dépend du pack : `shouldRenderEntities` et la distance
d'ombre viennent de `shadow.properties`
(`ShadowRenderer.java:104, 106, 146, 148`), et beaucoup de packs coupent les entités dans l'ombre.

### 2.2 Le Voile des blocs-entités : il agit dans les deux passes, et c'est cohérent [V]

À l'inverse, la passe d'ombre d'Iris **repasse par vanilla** pour les blocs-entités :
`ShadowRenderer.java:667-668` — `accessor.invokeExtractBlockEntities(camera, tickDelta,
levelRenderState)`. Notre `mixin/BlockEntityVeilMixin.java` est posé sur
`BlockEntityRenderDispatcher.tryExtractRenderState`, donc **dans le chemin**.

Trois conséquences, toutes bénignes :

- La caméra transmise est `playerCamera` (`ShadowRenderer.java:577`), **pas** la caméra d'ombre. Les
  deux passes posent donc la même question et obtiennent la même réponse. **Aucun scintillement
  possible** — c'était le risque sérieux, et il n'existe pas.
- Un coffre caché par un mur ne projette pas d'ombre. On ne le voit pas ; son ombre pourrait
  théoriquement tomber dans le champ. Cas rare, artefact minuscule. [S]
- Le budget de rayons n'est **pas** doublé. `core/Shroud.java:202-208` met la réponse en cache par
  position de bloc pour 100 ms ; la seconde passe ne coûte qu'une recherche dans une table. Et comme
  la passe d'ombre s'exécute **avant** la passe principale (`MixinLevelRenderer.java:199`,
  `iris$renderTerrainShadows` injecté avant `addMainPass`), c'est elle qui dépense les rayons et la
  passe principale qui lit le cache. Le compteur `spent >= budget` de `Shroud.java:209` reste juste.

**Il n'y a donc rien à régler ici, et surtout rien à corriger.**

### 2.3 Les coffres statiques : neutres, peut-être bénéfiques [S]

`mixin/StaticChestRendererMixin.java` rend `null` sur `getRenderer`, et
`StaticChestShapeMixin` fait passer le bloc en `MODEL`. Le coffre retombe dans le **maillage du
chunk**, donc dans la géométrie que Sodium envoie et qu'Iris ombre comme du terrain. Il est dessiné
une fois, dans le G-buffer terrain, et il apparaît dans la carte d'ombres comme n'importe quel bloc.

Iris n'a aucun mixin annulant le rendu des blocs-entités : son seul crochet sur
`BlockEntityRenderDispatcher.submit`
(`iris/common/…/mixin/entity_render_context/MixinBlockEntityRenderDispatcher.java:49-72`) se contente
de poser `CapturedRenderingState.setCurrentBlockEntity(intId)` et de le reposer au `RETURN`. Comme
notre coffre n'a plus d'état de rendu du tout, `submit` ne le voit jamais, et cet état ne peut pas
rester coincé. [S] — c'est une déduction du chaînage, pas une observation.

*Réserve honnête* : un pack qui donnerait au coffre un identifiant de bloc-entité particulier dans
son `block.properties` ne le reconnaîtra plus, puisqu'il n'est plus une entité de bloc. Le coffre
sera ombré comme du bois. Personne ne le verra. [S]

### 2.4 Le masquage des feuilles : il marche, parce que Sodium l'appelle [V]

Iris ne maille rien : c'est Sodium qui tient le terrain. Et Sodium appelle bien notre point
d'accroche —
`Lanterne-mine/sodium/common/src/main/java/net/caffeinemc/mods/sodium/client/render/model/AbstractBlockRenderContext.java:122` :

```java
if (this.state.skipRendering(neighborBlockState, facing)) {
```

Notre `mixin/LeafCullMixin.java` est posé sur `LeavesBlock.skipRendering`. Il agit donc sous
Sodium, donc sous Iris. C'est ce que `report/Compagnons.java` annonce déjà au démarrage
(*« il appelle skipRendering, ça tient »*), et c'est maintenant vérifié à la ligne.

**Mais le mode `TOUJOURS` devient risqué sous shaders [S].** La carte d'ombres est construite à
partir de la même géométrie. Des faces internes supprimées alors que les feuilles sont ajourées, ce
sont des **fuites de lumière dans l'ombre des arbres** — un défaut bien plus visible qu'un trou dans
un chêne quand on colle le nez dessus. Le mode `AUTO`, qui est le défaut, n'a pas ce problème : il ne
supprime que des faces dont le jeu garantit qu'elles sont invisibles.

### 2.5 Les objets au sol, les particules, le tampon

- **`mixin/LooseItemMixin.java`** (piles dessinées une fois au lieu de quatre) : la passe d'ombre
  passe par le même `EntityRenderDispatcher`, donc le plafond s'y applique aussi. Cohérent des deux
  côtés, rien à dire. [S]
- **`mixin/ParticleVeilMixin.java`** : il refuse la **naissance** d'une particule, dans
  `ClientLevel.doAddParticle`, qui n'est dans aucune passe de rendu. Il lit
  `gameRenderer.mainCamera()`, jamais une caméra d'ombre. Aucune interaction possible. [V]
  Iris ne touche d'ailleurs à aucun culling de particule : son unique mixin en la matière
  (`iris/common/…/mixin/MixinParticleEngine.java:19-36`) ne fait que poser une phase de rendu. [V]
- **`mixin/BufferStorageMixin.java` / `client/Tampon.java`** : c'est le rétroportage de MC-307596,
  qui vise précisément le cas « limité par le GPU » — donc le cas *avec shaders*. À laisser allumé.
  Sans effet sous Vulkan, mais Iris n'y tourne pas de toute façon (§3).

### 2.6 Un cadeau d'Iris, pour mémoire [V]

Iris **supprime lui-même** l'ombre-tache de vanilla sous les créatures dès qu'un pack fait de
l'ombrage — `MixinEntityRenderDispatcher.java:48` (`@WrapWithCondition` sur
`SubmitNodeCollector.submitShadow`), piloté par `IrisRenderingPipeline.java:1100-1104`. Nous n'avons
rien à faire de ce côté : il n'y a pas deux ombres, il y en a une, la bonne.

---

## 3. Ce qu'il faut installer, et dans quel ordre

### 3.1 Iris + Sodium, sur le backend OpenGL. Pas Vitrail.

**Iris exige Sodium, en dur.**
`iris/neoforge/src/main/resources/META-INF/neoforge.mods.toml:62-68` :

```toml
[[dependencies.iris]]
modId = "sodium"
type = "required"
reason = "Iris requires Sodium. Please install Sodium 0.6 for Iris to work."
versionRange = "[0.6,)"
```

Le même fichier déclare une **incompatibilité dure avec Embeddium** (lignes 54-60). [V]
« Sodium facultatif » n'est donc plus une option : avec shaders, Sodium est obligatoire.

Ce n'est pas un problème de licence pour nous : Sodium est en **PolyForm Shield 1.0.0**, non
absorbable, mais nous n'absorbons rien — `client/sodium/Graft.java` se greffe sur son API en
`compileOnly` et pas un octet de Sodium n'entre dans notre archive. La composition était déjà la
règle ; elle le reste.

### 3.2 Pourquoi pas Vitrail, alors qu'il est plus moderne

`vitrail-shaders` est un vrai mod, sérieux et vivant : 454 fichiers Java, version **0.11.0-beta**,
identifiant de mod **`vitrail`**, ciblant **26.2** (`vitrail-shaders/gradle.properties:8,9,11`), avec
une matrice de compatibilité par pack tenue à jour (`docs/compatibility.md`, 18 packs nommés). [V]
Il ne sera pas écarté pour sa qualité. Il est écarté pour trois faits :

1. **Il ne dessine rien hors Vulkan.** Son propre code le dit :
   *« this mod draws nothing off Vulkan and says so, so a reset does not rescue the session, it
   empties it »* — `vitrail-shaders/common/src/main/java/dev/vitrail/settings/GraphicsApiChoice.java:27-29`.
   Et `render/StartupGuard.java:79-86` **repose de force** `preferredGraphicsBackend` sur `VULKAN`. [V]
2. **Sa compatibilité Distant Horizons exige une build modifiée de DH.**
   `vitrail-shaders/INSTALL.md:120-133` nomme, pour NeoForge, un dépôt et une étiquette précis :
   `gitlab.com/avpbynf/distant-horizons`, release `vitrail-26.2-1`. Ce n'est pas le DH que le joueur
   installe depuis CurseForge. [V]
3. **Notre lentille se met déjà en retrait devant lui**, et pour une raison qui n'a pas changé :
   Vitrail a son propre curseur de résolution (`settings/PackFile.java:47`, clé `renderscale`, bornes
   25-100 %) et sa propre passe FSR 1.0 EASU+RCAS (`render/RenderScale.java:124-307`). Deux
   réductions superposées rendraient le monde à 45 % de 45 %. C'est ce que fait déjà
   `client/upscale/Rival.java`, et c'est juste. [V]

Vitrail reste **la** bonne réponse le jour où le joueur passera à Vulkan — et il a une leçon à nous
donner, déjà créditée dans `NOTICE.md` : il échange les **champs de texture** à l'intérieur de
l'unique cible plutôt que l'objet (`render/RenderScale.java:45-48`, *« the sky renderer holds the
object by reference across frames »*). C'est plus sûr que ce que nous faisons. Mais ce n'est pas le
chantier d'aujourd'hui.

### 3.3 Distant Horizons : la version d'Iris est épinglée, et DH le dit

DH ne « supporte » pas Iris en général : il vise **une** build.
`Lanterne-mine/distanthorizons/versionProperties/26.2.0.properties:23` et `:53` :

```
iris_version=1.11.4+26.2-fabric
neo_iris_version=1.11.4+26.2-neoforge
```

Et la borne basse d'Iris est déclarée dans le même fichier, lignes 28-30 :

```
# Iris - Iris added a new depth feature we need to track in 1.11.4,
#        so we no longer support 1.11.3 and older
fabric_incompatibility_list={ "vertigo": "*", "iris": ">=1.11.4" }
```

⚠ **Le commentaire et la valeur se contredisent.** Le commentaire dit « il nous faut 1.11.4 ou plus
récent » ; la valeur, en sémantique `breaks` de Fabric, déclare l'inverse — incompatible **à partir**
de 1.11.4. Tous les autres fichiers de versions de DH écrivent des bornes du bon sens
(`"iris": "<=1.8.10"` en 1.21.5, etc.). C'est très probablement un défaut de leur métadonnée. [V] sur
le texte, [S] sur l'intention. **Le fichier est de toute façon `fabric.mod.json` : côté NeoForge, il
n'a aucun effet.** Sur notre modpack NeoForge, ce piège ne se déclenche pas.

Symétriquement, Iris déclare `"distanthorizons": "<=2.1.99"` dans son `breaks`
(`iris/common/src/main/resources/fabric.mod.json:46-59`) : **DH doit être ≥ 2.2**. Celui de la mine
est en `3.2.1-b-dev`. [V]

**Et il y a un réglage de DH dont dépend tout le reste.**
`distanthorizons/coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/config/Config.java:955-964`
documente l'option `renderingEngine` :

```
AUTO      - changes based on the most likely API for that MC version
OPEN_GL   - The Default for MC 1.21.11 and older (supports Iris shaders)
BLAZE_3D  - The Default for MC 26.1.2 and newer (supports Vulkan)
```

En 26.2, `AUTO` choisit donc **BLAZE_3D**, et c'est `OPEN_GL` qui porte la mention *« supports Iris
shaders »*. [V] **Si l'horizon lointain n'est pas ombré par le pack, c'est le premier réglage à
regarder** — et le seul que cette note ne puisse pas trancher sans lancer le jeu (§6).

Côté chaîne de rendu, DH est de notre côté : son crochet est posé **à l'intérieur** de la passe de
niveau, sur `ChunkSectionsToRender.renderGroup` en `HEAD` avec `order = 800` pour passer avant
l'annulation de Sodium
(`distanthorizons/fabric/src/main/java/…/mixins/client/MixinChunkSectionsToRender.java:100-122`), et
il dimensionne ses textures sur `MC.gameRenderer.mainRenderTarget().width/.height`, relu à chaque
appel (`common/src/main/java/…/wrappers/minecraft/MinecraftRenderWrapper.java:475-484` et `:618-640`). [V]

**Donc l'horizon de DH hérite de notre toile réduite et remonte avec le reste.** C'est exactement ce
que `report/Compagnons.java` annonce déjà au joueur — *« Lentille + Distant Horizons : l'horizon
lointain est remonté d'échelle comme le reste. Si ça bave, c'est la lentille. »* Cet avertissement
est désormais sourcé à la ligne.

### 3.4 L'ordre d'installation, en trois lignes

```
1.  Sodium 0.9.x pour 26.2   (obligatoire : Iris ne démarre pas sans lui)
2.  Iris 1.11.4+26.2-neoforge (la build que DH nomme ; ni 1.11.3, ni une autre)
3.  Distant Horizons >= 2.2  (3.2.x pour 26.2) — puis, dans SES réglages,
    Advanced > Graphics > renderingEngine = OPEN_GL si l'horizon n'est pas ombré
```

Et **rester sur OpenGL** : le cadran « Vulkan » de l'écran Lanterne ne doit pas être touché (§4.2).

---

## 4. Les réglages de Lanterne quand des shaders tournent — trois à changer, deux à laisser

Fichier : `config/lanterne-client.toml`, section `[rendu]`. Tout est aussi accessible depuis l'écran
des cadrans, par l'une de ses trois portes : le bouton « 🎃 Lanterne — rendu » dans les réglages
vidéo de vanilla, l'écran de configuration de la liste des mods, et — **puisque Sodium sera là** — la
page « Lanterne » dans sa colonne de gauche (`client/sodium/Graft.java`).

### 4.1 `lentille_houle = false` — LE réglage à couper, et ce n'est pas un détail [V]

La houle change le facteur d'échelle en cours de partie pour tenir un taux d'images. Chaque
changement redimensionne la toile. Et à **chaque** changement de taille de la cible principale, Iris
**réalloue tout son pipeline** : `IrisRenderingPipeline.java:951-975` rebâtit les passes de
nettoyage, recalcule `beginRenderer`, `prepareRenderer`, `deferredRenderer`, `compositeRenderer`,
`finalPassRenderer`, réalloue toutes les images personnalisées et reconstruit le programme de
conversion d'espace colorimétrique.

Un pack complet compte de huit à seize cibles de couleur. Faire cela toutes les quelques secondes,
c'est troquer un à-coup contre un autre — et le nouveau est **pire** que celui qu'on voulait éviter.

Le mécanisme est [V]. Que l'à-coup se voie est [S] : personne ne l'a regardé. Mais l'asymétrie est
nette, comme toujours dans ce dépôt : couper la houle coûte au joueur un réglage automatique qu'il
n'a de toute façon jamais activé (elle est **éteinte par défaut**, `ClientConfig.java:298`).

### 4.2 Le cadran « Vulkan » : ne pas y toucher [V]

`client/upscale/Pivot.java` offre un basculement OpenGL ↔ Vulkan en un clic, pour DLSS. Il est
parfaitement sûr en soi — le jeu se rattrape tout seul. Mais **Iris ne tourne pas sous Vulkan** : il
est bâti sur Sodium, dont le chemin de rendu est OpenGL. Un joueur qui bascule verra ses shaders
disparaître sans message, et cherchera longtemps.

Ce cadran ne le dit pas encore (`client/Dials.java:297-306` parle du backend, pas des shaders).
Voir §7 pour la ligne exacte à y ajouter.

### 4.3 `feuilles_masquees = AUTO`, jamais `TOUJOURS` [S]

Voir §2.4 : la carte d'ombres est bâtie sur la même géométrie, et `TOUJOURS` avec des feuilles
ajourées y ouvre des fuites de lumière. `AUTO` est le défaut et reste exact.

### 4.4 `lentille_prereglage` : c'est LÀ qu'on paie les shaders

Rien à corriger — c'est le réglage qui répond à la question du joueur. Les paliers, et ce qu'ils
signifient une fois qu'Iris tourne dedans :

| Préréglage | Par dimension | Pixels rendus | Ce que le pack coûte alors |
|---|---:|---:|---|
| `ULTRA_QUALITE` | 77 % | 59 % | Presque invisible à l'œil ; premier essai à tenter. |
| `QUALITE` | 67 % | **44 %** | Le réglage recommandé par AMD. Le bon défaut avec shaders. |
| `EQUILIBRE` | 59 % | 35 % | Si le pack est lourd (rayons volumétriques, PBR). |
| `PERFORMANCE` | 50 % | 25 % | Un quart du pipeline de shaders. |

Sur une RTX 3080, un pack complet en 1440p qui tombe à 45 images/s en natif devrait tenir la
soixantaine à `QUALITE`. **C'est une extrapolation arithmétique, pas une mesure** : le coût d'une
image n'est proportionnel au nombre de pixels que pour les passes qui en dépendent, et une partie du
travail d'un pack — l'ombre, la géométrie — n'en dépend pas. [S]

Les deux autres réglages de la lentille — `lentille_nettete` et `lentille_anticrenelage` — sont
indifférents à la présence d'Iris : ils s'exécutent **après** lui, sur l'image qu'il a composée.

### 4.5 Le Voile : on le laisse, en sachant ce qu'il rend

Voir §2.1. Il ne casse rien, il rend moins. Le couper ne rapporterait rien du tout.

---

## 5. Ce qui n'a PAS été trouvé, et qu'il ne faut pas inventer

- **Aucune mesure.** Pas un chiffre de cette note ne vient d'un banc. Le seul chiffre mesuré cité —
  les mille vaches — l'a été **sans shaders**, et le §2.1 explique pourquoi il ne tiendra pas.
- **Aucune source d'Iris pour 26.2.** La mine n'a que la branche 26.1.2. Le §1.2 borne exactement ce
  que cela laisse en suspens.
- **Aucune trace, dans le code d'Iris, d'un culling de particules ou d'un culling de blocs-entités**
  qui doublerait le nôtre. Vérifié par recherche sur tout l'arbre. [V]
- **Aucune mention d'un conflit connu entre Iris et un mod d'upscaling** dans les fichiers du dépôt
  d'Iris. Le commentaire de `ViewportUniforms.java:25` est le seul endroit où le sujet est nommé, et
  il va dans notre sens.
- **La documentation d'Iris avertit pourtant qu'un culling maison est un point sensible**, et elle
  nomme le remède :
  `iris/common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java`, javadoc de
  `isRenderingShadowPass()` — *« Pretty much the main legitimate use for this function that I've seen
  is in a mod like Immersive Portals, where it has very custom culling that doesn't work when the
  Iris shadow pass is active. »* [V]
  **Nous n'en avons pas besoin** : le §2.1 montre que notre culling d'entités n'entre pas dans la
  passe d'ombre, et le §2.2 que celui des blocs-entités y entre de façon cohérente. Mais si un jour
  quelqu'un observe un scintillement, **c'est cette méthode qu'il faut appeler**, et l'accès est
  déjà à moitié écrit dans `client/upscale/Rival.java` (résolution réflexive de `IrisApi`).
- **Un indice indirect, et il rassure** : le README d'EntityCulling — le mod de référence sur cette
  technique — note que *« Having active shaders for instance will cause higher numbers due to the
  shadow rendering »* (`Lanterne-mine/entityculling/README.md:97`). Autrement dit, sous shaders les
  entités sont bien comptées **deux fois**, et ce mod-là, largement déployé, vit avec sans avoir eu
  besoin d'exempter la passe d'ombre. [V]

---

## 6. Ce que SEUL un lancement trancherait

Par ordre décroissant de ce que ça coûterait de se tromper :

1. **L'image est-elle juste ?** Un pack actif, la lentille à `QUALITE` : le monde est-il net, à la
   bonne taille, sans décalage ni bande noire ? Toute la §1 est une lecture de code. Une capture
   d'écran vaut mieux que quatre maillons vérifiés.
2. **L'interface reste-t-elle à la résolution native ?** C'est le point sur lequel la première
   tentative de ce module s'est cassée (voir `core/Lens.java`). Avec Iris qui compose par-dessus, il
   faut le revoir.
3. **Le réglage `renderingEngine` de DH.** `AUTO` donne `BLAZE_3D` en 26.2 ; le commentaire de DH
   associe les shaders Iris à `OPEN_GL`. Un seul lancement avec un pack qui porte `dh_terrain` dit
   lequel des deux marche. C'est la seule inconnue de la §3 qui pourrait changer la recommandation.
4. **La houle sous shaders.** Le mécanisme de réallocation est vérifié ; l'à-coup ne l'est pas.
   Allumer la houle trente secondes avec un pack lourd suffirait à trancher — et à savoir s'il faut
   l'interdire sous shaders ou seulement la déconseiller.
5. **Le coffre statique dans le G-buffer terrain.** §2.3 est une déduction du chaînage. Un coffre
   posé au soleil, avec et sans le réglage, répond en une seconde.
6. **La correction du ciel** (`client/upscale/Scene.java`), qui n'a jamais été observée non plus —
   et **elle ne devient pas sans objet sous shaders**, contrairement à ce qu'on pourrait croire.
   Vérifié : Iris ne remplace **pas** le `SkyRenderer` de vanilla. Il lui accroche des phases de
   rendu et n'annule le soleil et la lune que si le pack le demande
   (`iris/common/…/mixin/MixinSkyRenderer.java`, conditionné par `pipeline.shouldRenderSun()` /
   `shouldRenderMoon()`) ; et son autre mixin de ciel
   (`…/mixin/MixinLevelRenderer_Sky.java:46`) ne s'applique **que lorsqu'aucun pack n'est chargé**
   — sa première ligne est `if (Iris.getCurrentPack().isEmpty())`. [V]
   Le champ `private final RenderTarget` de `SkyRenderer.java:64` est donc toujours en jeu, et notre
   remède l'est aussi.

---

## 7. Aucun code n'a été écrit, et voici pourquoi

L'enquête ne débouche sur **rien à coder**. C'est le meilleur résultat possible, et il mérite d'être
dit plutôt que masqué par un fichier écrit pour l'honneur :

- La détection existe déjà et elle est juste : `client/upscale/Rival.java` distingue « installé »,
  « pack en service » et « en retrait devant Vitrail », et sa politique — reculer devant Vitrail,
  composer avec Iris — est exactement celle que cette note confirme.
- Les trois modules de rendu qui touchent au pipeline se comportent correctement sous Iris sans une
  ligne de plus, pour les raisons des §2.1 à §2.5.
- Les trois adaptations nécessaires sont des **réglages**, pas du code (§4).

Ajouter un `client/Shaders.java` reviendrait à recopier `Rival` sous un autre nom, ou à écrire un
module que rien n'appelle. Ce dépôt a déjà un fichier qui assume de ne rien faire
(`client/upscale/Deep.java`) ; il en a un autre qui assume que la mesure l'a réfuté
(`core/Ecluse.java`). Il n'a pas besoin d'un troisième qui n'aurait ni l'une ni l'autre excuse.

**Deux retouches seraient utiles, et toutes deux tombent dans des fichiers que cette enquête n'a pas
le droit de toucher.** Les voici, à la ligne près :

1. **`src/main/java/fr/clubcitrouille/lanterne/client/Dials.java`, ligne 304** — cadran « Vulkan »,
   colonne « quand l'effet est visible ». Remplacer cette ligne :

   ```java
                   "Au prochain lancement. Si Vulkan échoue, le jeu retombe DE LUI-MÊME sur OpenGL,"
   ```

   par celles-ci :

   ```java
                   "IRIS NE TOURNE PAS SOUS VULKAN : il est bâti sur Sodium, dont le chemin de"
                           + " rendu est OpenGL. Avec des shaders, ne touche pas à ce cadran."
                           + " Sinon : au prochain lancement. Si Vulkan échoue, le jeu retombe"
                           + " DE LUI-MÊME sur OpenGL,"
   ```

2. **`src/main/java/fr/clubcitrouille/lanterne/core/ClientConfig.java`, après la ligne 297** —
   commentaire de `LENS_SWELL`, juste avant `.define("lentille_houle", false)`. Insérer, en ASCII
   comme le reste du fichier :

   ```java
                   "",
                   "AVEC UN PACK DE SHADERS : LAISSER ETEINT. Chaque changement de palier",
                   "redimensionne la cible principale, et Iris REALLOUE ALORS TOUT SON PIPELINE :",
                   "cibles de couleur, passes de nettoyage, espace colorimetrique. Voir",
                   "notes/shaders.md, section 4.1.")
   ```

   (en supprimant la parenthèse fermante de la ligne 297, qui se déplace à la fin du bloc inséré)

Rien d'autre. Et c'est la conclusion : **Lanterne et Iris composent, et ce qui restait à faire était
de le prouver.**
