# Regroupement des appels de rendu immédiat (idée ImmediatelyFast) — faisabilité

> Enquête du 20 septembre 2026, même méthode que `notes/mesh-shaders-terrain-faisabilite.md` et
> `notes/hiz-occlusion-entites.md` : **VÉRIFIÉ** veut dire qu'une commande a été lancée, qu'une
> source primaire (`javap -p -c -constants` sur le vrai jar patché,
> `minecraft_26.3_client.jar`, jamais `.mcsrc/`) a été lue et citée, ou qu'un inventaire de
> classes réel (`unzip -l`) a été produit ; **SUPPOSÉ** est signalé comme tel.
>
> Rappel du contexte qui a motivé cette enquête : un rapport d'audit soumis le 20 septembre 2026
> prétendait qu'un module nommé « Le Voile » implémentait déjà ce regroupement. **Ce module
> n'existe pas dans ce dépôt** — vérifié par `grep -rn "Voile\|LeVoile"` sur
> `src/main/java`, zéro résultat autre que `core/Shroud.java` (dont le nom français est
> effectivement « le voile », mais qui traite du *culling d'occlusion des entités*, un sujet sans
> rapport — voir son Javadoc de classe). Cette enquête est repartie de zéro.

---

## Verdict

**Rien à construire — la prémisse ne s'applique pas à ce moteur.** ImmediatelyFast (LGPL,
`RaphiMC/ImmediatelyFast`) corrige un défaut précis de Minecraft **pré-graphics-rewrite** : le
HUD, l'inventaire et les entités de bloc soumettaient leur géométrie à `Tesselator`/
`BufferUploader.draw()` en petits lots, un appel de dessin immédiat par élément, sur le pilote
OpenGL historique. Ce moteur (Blaze3D natif Vulkan, abstraction `com.mojang.renderpearl`) a
**déjà** remplacé ce chemin, à trois endroits vérifiés indépendamment au `javap` — pas un seul,
ce qui exclut la coïncidence :

| Sous-système | Preuve vérifiée | Mécanisme déjà en place |
|---|---|---|
| Terrain (sections de chunk) | `LevelRenderer.extractSectionDrawGroups` + `VulkanRenderPass.drawIndexedIndirect` → `VK12.vkCmdDrawIndexedIndirect` | Un seul appel indirect multi-lots par groupe de tampon partagé — voir `client/terrain/Guet.java`, déjà documenté dans ce dépôt avant cette enquête |
| Interface (HUD, inventaire, texte) | `net.minecraft.client.gui.render.GuiRenderer` : méthodes `addElementsToMeshes`/`addElementToMesh`/`draw`/`executeDraw(Draw, RenderPass)`/`executeDrawRange`, plus `GuiItemAtlas`/`DynamicAtlasAllocator` | Chaque élément d'interface est collecté dans un maillage partagé (`addElementToMesh`), les icônes d'objets sont packées dans un atlas dynamique partagé (`GuiItemAtlas`, évite un changement de texture par icône), et le dessin réel passe par des **plages** (`Draw`) soumises en bloc à une `RenderPass` — exactement le regroupement qu'ImmediatelyFast ajoutait à la main sur l'ancien moteur |
| Entités de bloc (panneaux, vérifié comme représentant de la famille) | `AbstractSignRenderer.submit(...)` appelle `SubmitNodeCollector` (pas un dessin immédiat), et le texte lui-même vient de `SignText.getRenderMessages(boolean, Function)` — un accesseur **déjà mis en cache par vanilla**, pas recalculé à chaque image | La mise en forme du texte (l'opération coûteuse que « sign text buffering » d'ImmediatelyFast visait) est déjà mémoïsée côté vanilla ; ce qui reste par image n'est qu'une soumission au collecteur, pas un calcul |

Trois familles indépendantes (terrain, interface, entités de bloc), trois preuves de bytecode
distinctes, la même conclusion : ce moteur soumet déjà sa géométrie par lots via un pipeline
« extraction → collection → soumission groupée » (`SubmitNodeCollector`/`Draw`/
`DrawIndirect`), pas par appel de dessin immédiat un par un. C'est un rétroportage que Mojang a
fait lui-même dans la réécriture Blaze3D dont ce moteur hérite — la même situation, structurellement,
que celle que `Guet.java` avait déjà établie pour le terrain seul avant cette enquête.

---

## 1. Pourquoi ce n'est pas juste « pas encore mesuré »

Un module non mesuré, dans ce dépôt, reste éteint par défaut mais existe — voir `Horizon`,
`Guet`. Ici la situation est différente : **il n'y a pas de code à écrire**. Un mixin qui
regrouperait des appels de dessin déjà regroupés par le moteur ne pourrait, au mieux, rien
changer, et au pire dupliquer un mécanisme existant avec un risque de le casser — exactement le
défaut que la mission demande d'éviter (« ne duplique rien »).

Ce n'est pas non plus la même famille de blocage que les mesh shaders
(`notes/mesh-shaders-terrain-faisabilite.md`) ou le Hi-Z (`notes/hiz-occlusion-entites.md`), qui
sont des **capacités qui n'existent pas encore** dans ce moteur et demanderaient des semaines de
plomberie neuve. Ici, la capacité existe déjà et fait déjà le travail visé.

---

## 2. Ce qui a été vérifié, précisément

- `grep -rn "Voile\|LeVoile"` sur `src/main/java` : aucun module de ce nom, confirmant qu'il ne
  faut rien retrouver ni corriger d'un travail antérieur inexistant.
- `unzip -l minecraft_26.3_client.jar | grep -iE "gui/GuiGraphics|gui/render|screens/state"` :
  inventaire réel des classes `net.minecraft.client.gui.render.*`, révélant `GuiRenderer`,
  `GuiItemAtlas`, `DynamicAtlasAllocator`, `TextureSetup`, `render/pip/*`.
- `javap -p -c -constants GuiRenderer.class` : liste complète des méthodes, dont
  `addElementsToMeshes`, `addElementToMesh`, `draw`, `executeDraw`, `executeDrawRange`,
  `prepareItemAtlas`, `submitBlitFromItemAtlas` — noms qui décrivent eux-mêmes un pipeline de
  lots, pas un dessin immédiat.
- `javap -p -c -constants AbstractSignRenderer.class` : `submit(...)` appelle
  `SubmitNodeCollector` (jamais un `Tesselator`/`BufferBuilder` direct), et `submitSignText`
  appelle `SignText.getRenderMessages(boolean, Function)` — signature confirmant un accesseur
  mémoïsé côté modèle de données, pas un recalcul de mise en page à chaque image.
- Relecture de `client/Empreinte.java`/`mixin/VulkanRenderPipelineMixin.java` (demandés par la
  mission) : confirment que ce moteur a un point de fabrication Vulkan **unique**
  (`VulkanRenderPipeline.compile`) pour tous les pipelines, vanilla et Lanterne confondus — cohérent
  avec un moteur qui centralise déjà sa soumission de rendu plutôt que de la disperser en appels
  immédiats épars, mais sans rapport direct avec le regroupement de *dessins* lui-même : ce sont
  deux couches différentes (compiler UN pipeline une fois, contre soumettre PLUSIEURS dessins par
  image), vérifiées séparément l'une de l'autre.

---

## 3. Ce que la mission demandait de considérer, et le verdict sur chaque piste

- **Entités de bloc (coffres, panneaux, cadres).** Les coffres sont déjà couverts
  (`StaticChestRendererMixin`/`StaticChestShapeMixin`, absorbés de Faster Block Entities). Les
  panneaux, vérifiés ci-dessus, passent déjà par le pipeline de soumission groupée et un cache de
  texte vanilla : rien à regrouper. Les cadres (`ItemFrame`) sont des **entités**, pas des
  entités de bloc — leur occlusion est déjà couverte par `Shroud`/`EntityCullMixin` ; le mod de
  référence sur leur coût spécifique (`fast-item-frames`) les convertit en un **bloc neuf avec sa
  propre `BlockEntity`**, un changement de gameplay/contenu bien au-delà d'un regroupement de
  rendu, et hors du périmètre « faible risque » de ce lot.
- **HUD/interface (le sens originel d'ImmediatelyFast).** Déjà batché nativement, voir tableau du
  Verdict.

---

## Ce qui a été fait cette passe

- Lecture complète de `client/Empreinte.java` et `mixin/VulkanRenderPipelineMixin.java`, comme
  demandé par la mission, avant toute conclusion.
- Inventaire réel des classes `net.minecraft.client.gui.render.*` et désassemblage de
  `GuiRenderer.class` et `AbstractSignRenderer.class` sur le jar patché 26.3.
- Vérification négative que « Le Voile » n'existe pas dans ce dépôt.
- Aucun fichier de rendu modifié : cette piste n'a rien produit à committer, par construction —
  documenté ici plutôt que forcé.
