# Incident — crash client avec Distant Horizons, « Close the existing render pass », 2026-09-19

> Méthode identique aux autres constats de ce dépôt : **VÉRIFIÉ** veut dire qu'une commande a été
> lancée et qu'une source primaire (jar réel présent sur la machine, `javap -p -c -constants` sur
> le VRAI bytecode) a été lue et citée ; **SUPPOSÉ** est signalé comme tel. Aucune confiance
> accordée à `.mcsrc/` : tout ce qui suit vient de désassemblage réel, sur le jar patché produit
> par `build/moddev/artifacts/minecraft-patched-26.3.0.3-beta.jar` (moteur) et sur
> `DistantHorizons-3.3.0-26.3-fabric-neoforge.jar` (lu en lecture seule — voir la règle absolue de
> ce dépôt, jamais modifié ni décompilé pour être patché).

## Verdict

**Pas un bug Lanterne.** C'est une collision entre une décision d'architecture du moteur patché
(la passe de rendu Vulkan qui englobe le solide ET la transparence classique reste ouverte pendant
qu'un évènement NeoForge standard, documenté comme « après les features opaques », est posté) et
l'usage — par ailleurs parfaitement standard et raisonnable — que Distant Horizons fait de cet
évènement. **Aucun des deux mixins Lanterne visés par le rapport de crash
(`UpscaleGameRendererMixin`, `PortailRenderMixin`) n'est impliqué** : prouvé par bytecode, pas
supposé (détail en §2). Ni corrigible côté Lanterne sans inventer un tout nouveau mixin contre du
code moteur que ce dépôt ne possède pas — ce que la mission de ce soir n'autorisait pas et que je
n'ai donc pas fait.

Complète l'addendum de `notes/distant-horizons-faisabilite.md`, qui appelait justement à « un vrai
lancement, avec les logs regardés pour de vrai » — c'est ce lancement, et voici ses logs.

---

## 1. Le crash tel que rapporté

`crash-2026-09-19_20.32.08-client.txt` (464 ticks / 23,2 s de jeu avant crash — donc pas au
lancement, déclenché par un évènement précis) :

```
java.lang.IllegalStateException: Close the existing render pass before performing additional commands
	at com.mojang.renderpearl.frontend.FrontendCommandEncoder.writeToBuffer(FrontendCommandEncoder.java:303)
	at distanthorizons: BlazePostProcessUtil.createAndUploadScreenVertexData(BlazePostProcessUtil.java:74)
	at distanthorizons: BlazeVanillaFadeRenderer.tryInit(BlazeVanillaFadeRenderer.java:141)
	at distanthorizons: BlazeVanillaFadeRenderer.render(BlazeVanillaFadeRenderer.java:156)
	at distanthorizons: ClientApi.renderFadeOpaque(ClientApi.java:700)
	at distanthorizons: NeoforgeClientProxy.afterLevelEntityRenderEvent(NeoforgeClientProxy.java:238)
	at net.neoforged.bus.EventBus.post(...)
	at net.minecraft.client.renderer.LevelRenderer.executeSolid(LevelRenderer.java:540)
	at net.minecraft.client.renderer.LevelRenderer.lambda$addMainPass$0(LevelRenderer.java:465)
	at com.mojang.blaze3d.framegraph.FrameGraphBuilder.execute(FrameGraphBuilder.java:71)
	at net.minecraft.client.renderer.LevelRenderer.render(LevelRenderer.java:286)
	at net.minecraft.client.renderer.GameRenderer.renderLevel(GameRenderer.java:678)
		{neoforge:mixin[APP:lanterne.mixins.json:UpscaleGameRendererMixin, PortailRenderMixin,
		     APP:DistantHorizons.neoforge.mixins.json:client.MixinGameRenderer]}
```

La liste entre accolades à la fin de la frame `GameRenderer.renderLevel` énumère **tous les
mixins qui visent la classe** `GameRenderer` — pas ceux actifs sur la ligne précise où le crash a
lieu. C'est une source de confusion facile en lisant un rapport de crash brut ; §2.2 le vérifie
par bytecode plutôt que de s'y fier.

## 2. Ce que le bytecode prouve, côté moteur (VÉRIFIÉ)

### 2.1 — `FrontendCommandEncoder` : la règle qui casse

`javap -p -c -constants` sur `com/mojang/renderpearl/frontend/FrontendCommandEncoder.class`
(extrait de `build/moddev/artifacts/minecraft-patched-26.3.0.3-beta.jar`) :

- Champ d'instance `private boolean isInRenderPass`.
- `createRenderPass(RenderPassDescriptor)` (offset 586-588) : `putfield isInRenderPass, true`.
- `submitRenderPass()` (offset 17-19) : `putfield isInRenderPass, false` — c'est ce que
  `FrontendRenderPass.close()` appelle via le `Runnable` capturé à la construction (offset 653 du
  constructeur `createRenderPass`, `invokedynamic run:(...)Ljava/lang/Runnable`).
- `writeToBuffer(...)` (offset 0-17) : lève EXACTEMENT le message du crash si
  `isInRenderPass == true`, avant même de valider quoi que ce soit d'autre sur le buffer.

Donc : un seul champ, un seul mécanisme, aucune ambiguïté sur la condition qui déclenche
l'exception.

### 2.2 — `LevelRenderer.lambda$addMainPass$0` : où la passe s'ouvre et se ferme (VÉRIFIÉ, pas
un seul octet Lanterne)

Même jar, `net/minecraft/client/renderer/LevelRenderer.class`, méthode
`lambda$addMainPass$0` :

```
offset 126-156 : RenderSystem.getDevice().createCommandEncoder().createRenderPass(...) → astore 8
offset 168-174 : invoke executeSolid(chunkSections, preparedFrame, renderPass)
offset 181-190 : invoke executeClassicTransparency(...)                    [si pas OIT]
offset 195-197 : renderPass.close()                                        [chemin normal]
offset 212-219 : renderPass.close()                                        [chemin exception, même finally]
```

Table d'exceptions : 163→190 cible 205, 212→219 cible 222 — un bloc `try`/`finally` classique
(exactement le sucre syntaxique que produit un `try (RenderPass pass = ...) { ... }`, le MÊME
patron que ce dépôt utilise lui-même dans `Accumulate.java:146`, `Gbuffer.java:140`,
`PortailPrototype.java:198`). La passe ouverte à l'offset 126-156 n'est refermée (donc
`isInRenderPass` remis à `false`) **qu'après le retour complet de `executeSolid` ET
`executeClassicTransparency`**.

**Aucune référence à `fr.clubcitrouille.lanterne.*` n'apparaît nulle part dans le bytecode de
cette méthode.** C'est entièrement `net.minecraft.client.renderer.LevelRenderer`, moteur patché,
sans intervention d'aucun mixin de ce dépôt.

### 2.3 — Où l'évènement NeoForge est posté, et par qui

`LevelRenderer.executeSolid` (ligne source 540, celle du rapport de crash) poste
`net.neoforged.neoforge.client.event.RenderLevelStageEvent$AfterOpaqueFeatures` sur le bus
NeoForge **standard** (`NeoForge.EVENT_BUS.post(...)`) — c'est du code moteur patché, PAS une
injection par mixin de Distant Horizons (vérifié : le mixin DH qui vise `LevelRenderer`,
`client.MixinLevelRenderer`, ne contient que trois méthodes d'injection —
`prepareTranslucents`, `executeOutline`, `executeOit` — aucune ne touche `executeSolid`).
L'accolade `{neoforge:mixin[...MixinLevelRenderer...]}` visible plus haut sur la frame
`GameRenderer.renderLevel` liste donc bien tous les mixins actifs sur la classe, pas celui
responsable de cette ligne précise — confirmé, pas supposé.

Conséquence directe des §2.2 et 2.3 : cet évènement se déclenche **par construction du moteur**,
alors qu'une passe Vulkan est encore ouverte (elle n'englobe pas QUE le solide : elle englobe
aussi la transparence classique qui suit, non encore exécutée à ce point). N'importe quel
écouteur synchrone de cet évènement qui essaierait d'émettre des commandes GPU via
l'encodeur partagé se heurterait à la même exception — avec ou sans Lanterne installé.

## 3. Ce que le bytecode prouve, côté Distant Horizons — lecture seule stricte

**Rappel : aucun octet du jar `DistantHorizons-3.3.0-26.3-fabric-neoforge.jar` n'a été modifié,
renommé ou réécrit. Tout ce qui suit vient d'un désassemblage `javap` en lecture seule, sur une
copie extraite dans un dossier temporaire.**

- `NeoforgeClientProxy.afterLevelEntityRenderEvent` a pour paramètre le type
  `net.neoforged.neoforge.client.event.RenderLevelStageEvent$AfterOpaqueFeatures` — un évènement
  NeoForge **officiel et documenté**, pas un mécanisme interne à DH. L'enregistrement
  (`NeoforgeClientProxy.registerEvents()` → `NeoForge.EVENT_BUS.register(this)`) est
  l'enregistrement standard sur le bus global, rien d'exotique.
- `BlazePostProcessUtil` garde un champ statique `COMMAND_ENCODER`, initialisé UNE FOIS dans son
  `<clinit>` via `RenderSystem.getDevice().createCommandEncoder()`. Vérification côté moteur (même
  jar patché) : `RenderSystem.getDevice()` renvoie un singleton statique, et
  `FrontendGpuDevice.createCommandEncoder()` renvoie un champ figé (`this.encoder`), **pas un
  objet neuf par appel**. Le `COMMAND_ENCODER` mis en cache par DH est donc **exactement la même
  instance** que celle utilisée par le pipeline principal du jeu pour la passe solide+transparence
  — et `isInRenderPass` est un champ d'INSTANCE sur cet objet partagé.
- `BlazeVanillaFadeRenderer.tryInit()` est protégé par un drapeau `init` (une seule exécution à
  vie du processus). Ça explique le délai avant crash : ce n'est pas aléatoire, c'est la toute
  première fois que `render()` s'exécute avec un fondu actif (mode `DOUBLE_PASS` par défaut) —
  probablement en approchant d'une bordure de terrain LOD, voir §4 pour le protocole.

DH ne fait donc rien d'anormal ni de fragile de son côté : il écoute un évènement standard, avec
le mécanisme standard, et suppose — raisonnablement, à la lecture du nom de l'évènement — qu'à ce
point-là aucune passe de rendu n'est retenue ouverte sur l'encodeur partagé. C'était probablement
vrai sous l'ancien pipeline `blaze3d`/OpenGL immédiat (pas de notion de passe persistante à
fermer) ; ce n'est plus garanti sous le nouveau modèle Vulkan natif de `renderpearl`, où une passe
englobe volontairement plusieurs étapes de dessin consécutives avant de se fermer.

## 4. Pourquoi ce n'est corrigible ni ici ni chez Distant Horizons, ce soir

- **Pas un bug Lanterne** : ses deux mixins qui visent `GameRenderer` (`UpscaleGameRendererMixin`,
  `PortailRenderMixin`) ne touchent ni `createRenderPass`, ni `submitRenderPass`, ni
  `LevelRenderer`, ni `FrameGraphBuilder` — vérifié par lecture de leur source ET par l'absence
  totale de `fr.clubcitrouille.lanterne` dans le bytecode de `lambda$addMainPass$0` (§2.2). Un
  balayage de tous les mixins du dépôt confirme qu'aucun autre (`EntityCullMixin`,
  `RenderPipelinesMixin`, etc.) ne s'approche de ce mécanisme non plus.
- **Pas un bug interne à Distant Horizons** au sens où la mission l'envisageait (« sa propre passe
  de rendu, ouverte par son code, pas fermée par son code ») : DH n'ouvre aucune passe ici, il
  réagit à un évènement moteur standard avec le mécanisme standard.
- **La vraie cause est le point où le moteur patché poste `RenderLevelStageEvent.AfterOpaqueFeatures`**
  — à l'intérieur d'une passe Vulkan encore ouverte, par choix d'architecture (une seule passe pour
  solide + transparence classique). Ce code (`LevelRenderer`, le pipeline `renderpearl`) n'est pas
  du code source de ce dépôt : c'est le jar moteur patché consommé par Lanterne
  (`build/moddev/artifacts/minecraft-patched-26.3.0.3-beta.jar`), pas quelque chose que
  `src/main/java` de ce dépôt possède ou peut corriger proprement.
- Un correctif y **serait** techniquement possible depuis Lanterne (un nouveau mixin contre
  `LevelRenderer.lambda$addMainPass$0`, fermant puis rouvrant la passe autour du point de post de
  l'évènement) — mais ce serait un chantier neuf, sur un mécanisme jamais touché par ce dépôt
  jusqu'ici, avec un risque réel de casser des invariants du pipeline (résolution MSAA, état de
  profondeur/stencil attendu par `executeClassicTransparency` juste après) qu'une vérification
  purement statique ne peut pas garantir sans un vrai lancement. La mission de ce soir n'a pas
  demandé ce chantier — seulement une correction si l'UN DES DEUX mixins déjà en cause y était
  pour quelque chose. Il n'y est pour rien. Documenté ici plutôt qu'inventé.

**À signaler éventuellement, séparément** : à l'auteur de Distant Horizons (l'hypothèse « pas de
passe ouverte après les features opaques » ne tient plus sous ce moteur — un correctif DH côté
`BlazeVanillaFadeRenderer`/`BlazePostProcessUtil`, par exemple différer l'écriture du buffer à un
point garanti hors-passe, réglerait ça sans dépendre de Lanterne) — et potentiellement à l'auteur
du moteur patché lui-même si ce numéro de version continue d'évoluer (le nom `AfterOpaqueFeatures`
suggère un point « sûr » que le pipeline Vulkan ne respecte plus).

## 5. Protocole de reproduction précis (pour le coordinateur — pas de client relancé ici)

Le crash n'est pas lié au lancement (464 ticks / 23,2 s avant crash) mais au premier déclenchement
du fondu de Distant Horizons (`BlazeVanillaFadeRenderer.tryInit`, protégé par un drapeau « une
seule fois par session » — §3). Pour reproduire de façon fiable plutôt que d'attendre au hasard :

1. Instance `test 1`, avec `DistantHorizons-3.3.0-26.3-fabric-neoforge.jar` dans `mods\` (déjà en
   place) et le jar Lanterne fraîchement construit.
2. Se placer dans un monde déjà chargé depuis un moment (pas juste après le spawn) — le fondu de
   DH se déclenche sur du terrain LOD qui vient d'apparaître, pas sur le terrain déjà chargé en
   « vrais » chunks.
3. **Se déplacer en ligne droite, vite** (vol créatif recommandé, ou sprint+saut), vers une
   direction où le rendu de distance LOD de DH est visible (bordure du render distance normal,
   5/20 chunks dans le rapport de crash — donc DH prend le relais dès la limite native), pendant
   au moins 5 à 10 secondes de déplacement continu. C'est l'apparition d'un NOUVEAU tuile LOD dans
   le champ de vision qui arme `shouldRenderFade()` côté DH, pas une durée fixe.
4. Si rien ne crashe après un aller simple, faire demi-tour et repartir dans une autre direction
   pour forcer l'apparition d'autres tuiles LOD neuves — le drapeau `init` de
   `BlazeVanillaFadeRenderer` n'autorise qu'un seul essai par session, donc un seul déclenchement
   suffit à confirmer ou infirmer, mais il faut qu'un fondu se déclenche au moins une fois.
5. Un moyen plus direct, si l'option existe dans le menu DH (non vérifié ce soir, à confirmer en
   jeu) : désactiver puis réactiver Distant Horizons depuis son propre menu de config pendant une
   session, ou changer son mode de fondu (`DOUBLE_PASS`) — certaines de ces actions peuvent
   redéclencher `tryInit`.
6. Le crash, s'il se reproduit, doit à nouveau montrer EXACTEMENT la même pile —
   `FrontendCommandEncoder.writeToBuffer` ← `BlazePostProcessUtil.createAndUploadScreenVertexData`
   ← `BlazeVanillaFadeRenderer.tryInit`. Toute pile différente invaliderait ce diagnostic et
   mériterait une nouvelle investigation.

## Ce qui a été fait ce soir

- Lecture du rapport de crash complet (`crash-2026-09-19_20.32.08-client.txt`).
- `javap -p -c -constants` sur `FrontendCommandEncoder.class` (extrait du jar patché) : localisé
  le champ `isInRenderPass` et les deux méthodes qui le manipulent.
- Lecture source des deux mixins nommés dans le rapport (`UpscaleGameRendererMixin.java`,
  `PortailRenderMixin.java`) : confirmé qu'aucun ne touche aux passes de rendu ; confirmé par la
  pile de crash elle-même (frame directe `GameRenderer.renderLevel:678` →
  `LevelRenderer.render:286`, sans frame de mixin Lanterne intercalée) que c'est le PREMIER rendu
  réel qui plante, pas le second rendu du prototype de portail (`PortailRenderMixin`, qui ne
  s'exécute qu'après TAIL de `renderLevel`, jamais atteint ici).
- Balayage de tous les mixins du dépôt (`grep` sur `LevelRenderer|executeSolid|createRenderPass`) :
  seuls `EntityCullMixin` (culling d'entités, sans rapport) et les deux mixins déjà cités
  apparaissent ; aucun autre mixin ne s'approche du mécanisme en cause.
- `javap -p -c -constants` sur `LevelRenderer.class` (moteur patché) : localisé précisément
  l'ouverture/fermeture de la passe autour de `executeSolid`/`executeClassicTransparency` dans
  `lambda$addMainPass$0` — détail en §2.2.
- Lecture (LECTURE SEULE, aucune modification) par `javap` de `NeoforgeClientProxy`,
  `BlazeVanillaFadeRenderer`, `BlazePostProcessUtil`, `ClientApi` et du mixin DH
  `MixinLevelRenderer`, extraits du jar `DistantHorizons-3.3.0-26.3-fabric-neoforge.jar` dans un
  dossier temporaire — détail en §3.
- Vérifié que Lanterne suit lui-même le patron `try (RenderPass pass = ...)` partout où il ouvre
  une passe (`Accumulate.java:146`, `Gbuffer.java:140`, `PortailPrototype.java:198`) — c'est le
  même patron que celui trouvé côté moteur en §2.2, ce qui renforce la lecture (aucune passe
  ouverte de ce dépôt ne traîne au-delà de son bloc).
- **Aucun fichier source modifié.** Aucun correctif inventé qui n'aurait rien réglé.

## Ce qui lèverait le blocage

Un correctif côté Distant Horizons (différer l'écriture du buffer de fondu hors de la passe
solide+transparence — par exemple au prochain évènement `RenderLevelStageEvent.AfterLevel` ou
équivalent post-passe), ou une évolution du moteur patché qui déplace le point de post de
`RenderLevelStageEvent.AfterOpaqueFeatures` après la fermeture réelle de la passe. Rien de tout
cela n'est du ressort de ce dépôt ce soir.
