# Immersive Portals comme compagnon optionnel — faisabilité

> Enquête du 18 septembre 2026, même méthode que `notes/dlss-panama.md`, `notes/fsr2-faisabilite.md`
> et `notes/hiz-occlusion-entites.md` : **VÉRIFIÉ** veut dire qu'une commande a été lancée, qu'une
> source primaire (`javap` sur le VRAI jar patché, jamais `.mcsrc/`) a été lue et citée, ou qu'une
> recherche web réelle a été faite et sourcée ; **SUPPOSÉ** est signalé comme tel.

---

## Verdict

**Pas d'intégration cette passe — et il n'y a rien à regretter à ce choix : deux blocages
indépendants suffiraient chacun seul.**

1. **Aucun jar d'Immersive Portals n'existe pour ce moteur.** La dernière version réelle publiée,
   toutes distributions confondues (Fabric officiel, ports Forge/NeoForge communautaires, forks de
   compatibilité), cible Minecraft 1.21.1. « Minecraft 26.3 » / NeoForge 26.3.0.3-beta n'a pas
   d'équivalent réel — il n'y a donc rien à télécharger, décompiler ni charger contre ce build.
2. **Même en imaginant qu'un jar existe**, l'architecture que le mod suppose — un rendu de portail
   obtenu en réentrant dans le rendu de niveau avec un état GL mutable posé et retiré à la volée
   (pochoir, plan de coupe) — cible des classes que ce moteur a soit scindées
   (`LevelRenderer` → `LevelExtractor`), soit remplacées par une abstraction déclarative
   (`GpuDevice`/`CommandEncoder`/`RenderPipeline`, pipelines figés à la construction, jamais mutés
   en cours d'image). Les mixins qu'Immersive Portals a toujours écrits contre le vrai
   `LevelRenderer.renderLevel`/`RenderSystem` de vanilla ne trouveraient tout simplement pas leurs
   cibles dans `com.mojang.renderpearl.*`.

Construit cette passe : le scaffold honnête que la mission demande dans ce cas — un réglage
client, désactivé par défaut, qui ne fait rien d'autre que détecter et journaliser. Voir §4.

---

## 1. Ce qui est vérifié — disponibilité réelle du mod

Recherche web du 18 septembre 2026.

| Question | Réponse | Source |
|---|---|---|
| Le mod Fabric officiel (qouteall) est-il maintenu ? | Oui, mais plafonne à **Minecraft 1.21.1** (v6.0.6, novembre 2024 ; un correctif 1.20.1 a suivi en novembre 2025, sans avancer la version plafond) | [iPortalTeam/ImmersivePortalsMod](https://github.com/iPortalTeam/ImmersivePortalsMod), listing CurseForge |
| Un port NeoForge existe-t-il ? | Oui, **deux** : `iPortalTeam/ImmersivePortalsModForNeo` (communautaire) et un port Modrinth séparé par Nick1st, tous deux plafonnant également à MC 1.21.1 (dernier build NeoForge, juin 2025) | [iPortalTeam/ImmersivePortalsModForNeo](https://github.com/iPortalTeam/ImmersivePortalsModForNeo), CurseForge « Immersive Portals (for Forge) » |
| Le port NeoForge est-il maintenu par l'auteur d'origine ? | Non — l'auteur (qouteall) ne maintient que la version Fabric ; le port Forge/NeoForge est un dépôt séparé, maintenance communautaire (Nick1st) | Recherche web, README du dépôt Forge |
| Existe-t-il un fork de compatibilité récent, pour jauger l'activité de l'écosystème ? | Oui — « Immersive Portals - Sodium/Iris/DH Compat Fork » (mizunagames), qui corrige une casse causée par un changement d'API **interne** de Sodium (`OcclusionCuller.Visitor` promue en interface top-level, `Viewport.isBoxVisible` scindée en deux méthodes) sur NeoForge 1.21.1 | CurseForge « Immersive Portals - Sodium/Iris/DH Compat Fork » |
| Un build existe-t-il pour « Minecraft 26.3 » ou tout ce qui s'en approche ? | **Non trouvé, et ça n'a rien d'étonnant** : ni Mojang ni NeoForged ne publient de version portant ce numéro dans le monde réel — c'est le schéma de version propre à ce dépôt (voir `gradle.properties`, `neo_version=26.3.0.3-beta`) | Recherche web, aucun résultat |

**Ce que ce tableau signifie concrètement pour ce chantier.** Le fork de compatibilité cité
ci-dessus illustre à lui seul la fragilité du mod : une promotion d'interface interne dans Sodium
— un changement d'API **au sein du même moteur de rendu OpenGL/blaze3d que le mod a toujours
connu** — a suffi à le casser au démarrage sur NeoForge 1.21.1. Le saut demandé ici n'est pas une
promotion d'interface : c'est un remplacement complet de la couche de rendu (voir §2). Si un
changement mineur, dans le même paradigme, casse déjà le mod, il n'y a aucune raison de croire
qu'un changement de paradigme le laisserait debout.

**Conséquence pratique.** `javap` sur le vrai jar patché est la règle absolue de ce dépôt — mais
il n'existe ici **aucun jar à inspecter** : ni le jar Fabric (mauvais loader), ni les jars
Forge/NeoForge (bonne famille de loader, mais compilés contre un `neo_version` réel de la branche
1.21.1, donc contre un jar Minecraft totalement différent de
`minecraft-patched-26.3.0.3-beta-sources.jar`). Charger l'un de ces jars contre le
`neo_version=26.3.0.3-beta` de ce dépôt échouerait à la résolution de dépendances avant même
d'atteindre le chargement des mixins.

---

## 2. Ce qui est vérifié — l'architecture de rendu que ce moteur expose réellement

Reprise et synthèse de ce qui a déjà été établi ce mois-ci dans ce dépôt (`README.md` §« Le
pipeline de rendu », `notes/hiz-occlusion-entites.md`, `notes/moteur-rendu-maison.md`,
`mixin/RenderPipelinesMixin.java`, `mixin/VulkanFeatureSetsMixin.java`,
`mixin/VulkanPhysicalDeviceMixin.java`) — rien n'est redécouvert ici, tout est cité.

| Question | Réponse | Preuve |
|---|---|---|
| Le paquet de rendu est-il toujours `com.mojang.blaze3d` ? | **Non, en 26.3** — renommé/restructuré en `com.mojang.renderpearl` (`.backend.vulkan`, `.api.pipeline`). `com.mojang.blaze3d.opengl.*` subsiste mais **inerte** sous le backend Vulkan (voir commentaire de `ClientConfig.TAMPON`) | `javap` cité dans `VulkanPhysicalDeviceMixin`, `VulkanFeatureSetsMixin`, `RenderPipelinesMixin` — trois mixins déjà présents dans ce dépôt |
| `LevelRenderer.renderLevel`/`extractVisibleEntities` existent-ils encore tels quels ? | **Non** — scindés dans une classe neuve, `LevelExtractor`, en 26.2 déjà. C'est cette rupture précise que `tools/verifie_mixins.py` a été écrit pour attraper (voir sa docstring, §« Le passage à Minecraft 26.2 en a fourni la démonstration ») | `tools/verifie_mixins.py`, lignes 14-19 |
| L'état GPU est-il mutable à l'image (`RenderSystem.enableStencilTest()`, `glStencilFunc`, plan de coupe pilotable en cours de frame) ? | **Non** — le pipeline (`RenderPipeline`, y compris son `DepthStencilState`/`CompareOp`) est **construit une fois**, en `<clinit>` de `RenderPipelines`, puis sélectionné par `CommandEncoder`/`RenderPass` — jamais réécrit en cours d'image. C'est exactement ce que `RenderPipelinesMixin` doit contourner pour étendre ne serait-ce qu'un format de sommet : chaîner sur le `Builder` avant `buildSnippet()`, parce qu'après, le `Snippet` est immuable | `RenderPipelinesMixin.java`, javadoc complète — vérifiée par `javap -p -c -constants` sur `RenderPipelines.class` du vrai jar patché |
| Un nuanceur de calcul (compute shader) existe-t-il, pour un éventuel test d'occlusion piloté GPU façon portail ? | **Non** — `ShaderType` n'a que `VERTEX`/`FRAGMENT` ; `CommandEncoder` n'a ni `dispatch` ni `createComputePass` | `notes/hiz-occlusion-entites.md` §1, tableau, même moteur |
| Le Z est-il conventionnel (comparable aux hypothèses qu'un mod tiers écrit contre vanilla ferait) ? | **Non** — Z inversé, `CompareOp.GREATER_THAN_OR_EQUAL` partout dans `RenderPipelines.class` | `notes/hiz-occlusion-entites.md` §1, même table |

**Ce que cela signifie pour Immersive Portals spécifiquement.** Le mod — dans **toutes** ses
distributions connues, Fabric comme Forge/NeoForge, dixit ses propres dépôts publics — obtient son
rendu récursif de portail en :

1. **Réentrant dans le rendu de niveau** (appeler l'équivalent de `renderLevel` une seconde fois,
   récursivement, avec une caméra transformée par la géométrie du portail) — la méthode visée a
   déjà bougé une fois dans ce dépôt (`LevelRenderer` → `LevelExtractor`, 26.2) sans qu'aucun mod
   externe n'en soit informé à l'avance ;
2. **Posant et retirant un état GL à la volée** (pochoir pour ne peindre que la silhouette du
   portail à l'écran, ou plan de coupe par shader) — la seule façon de faire ça dans ce moteur
   serait de construire un `RenderPipeline` DÉDIÉ par portail, à l'avance, en connaissant sa forme
   et son `DepthStencilState` — un chantier de renderer complet, pas un mixin de quelques lignes ;
3. **Modifiant les shaders vanilla eux-mêmes** pour y injecter la logique de clipping — sur ce
   point précis, ce dépôt a DÉJÀ rencontré, sur un chantier bien plus modeste (ajouter deux
   attributs de sommet conditionnels au shader terrain), une régression réelle en client lancé
   (`ShaderCompileException` sur les pipelines OIT, voir `RenderPipelinesMixin.java` §« Les
   pipelines OIT, cassés puis réparés »). Le même risque, à l'échelle d'un mod entier qui réécrit
   la logique de clipping de CHAQUE pipeline de rendu de niveau, n'est pas une extrapolation
   prudente : c'est la même faute, en plus grand.

Aucun de ces trois points n'est un détail d'implémentation isolé qu'un mixin ciblé pourrait
contourner : ce sont les trois piliers de la technique du mod, et les trois s'appuient sur une
mutabilité d'état que ce moteur a précisément supprimée en adoptant `renderpearl`.

---

## 3. Ce qui serait, en toute rigueur, imaginable — et pourquoi ce n'est pas un chantier de ce soir

Par honnêteté envers la méthode de ce dépôt (voir `notes/hiz-occlusion-entites.md` §2, qui liste ce
qui serait *constructible* même quand le verdict est négatif) :

- Un rendu de portail *serait* concevable dans l'absolu sur ce moteur : construire, par portail
  connu à l'avance, un `RenderPipeline` avec son propre `DepthStencilState` de pochoir, dessiné
  dans un `RenderPass` séparé sur une `GpuTextureView` bornée à la silhouette écran du portail, puis
  composité — dans l'esprit de ce que `client/upscale/Scene.java`/`Resolve.java` font déjà pour la
  lentille (un `RenderTarget` + `FrameGraphBuilder` séparés, puis remontés).
- Mais ce ne serait **pas** « brancher Immersive Portals » : ce serait **réécrire son idée depuis
  zéro** contre l'abstraction `renderpearl`, un chantier de plusieurs semaines à mois — de l'ordre
  de grandeur de la Phase 3 de `notes/moteur-rendu-maison.md` (« Occlusion par graphe de visibilité
  entre sections »), qui est elle-même qualifiée de « phase la plus dure » de tout ce document.
  Ce ne serait plus un compagnon optionnel avec un jar séparé — la demande initiale — mais un
  module de rendu natif de Lanterne, avec son propre risque de régression visuelle silencieuse
  (« un mur invisible par endroits » — le même risque que ce dépôt nomme déjà pour le greedy
  meshing, en pire : ici, une image RECURSIVE mal cadrée ne crashe pas non plus).
- Rien de tout cela ne rentre dans « ce qui est honnêtement faisable ce soir ». Le classer ainsi
  serait exactement la faute que ce dépôt s'interdit ailleurs : un module non mesuré, non vu en
  jeu, n'existe pas.

---

## 4. Ce qui a été construit cette passe

Le scaffold honnête que la mission prévoit pour ce cas précis :

- **`client/portals/PortailsConfig.java`** — un fichier de réglages CLIENT séparé
  (`config/lanterne-portails-client.toml`), même raison que `content/relief/ReliefConfig.java` ou
  `client/screen/Consent.java` : un module sans effet réel vit dans son propre fichier, pour ne
  jamais avoir à toucher `core/ClientConfig.java` — qui, au moment d'écrire ces lignes, est déjà en
  cours de modification par un autre chantier de ce dépôt (`client/upscale/*`). Un seul réglage :
  `actif` (`portails_immersifs.actif`), **désactivé par défaut**.
- **`client/portals/Portail.java`** — une détection, via `ModList.get().isLoaded(...)`, du mod
  installé (identifiants **SUPPOSÉS**, lus dans les dépôts publics cités en §1, jamais confirmés
  par un jar réel — voir la javadoc de la classe). Si le réglage est allumé : un message de journal
  au démarrage du client, disant qu'aucune intégration n'est branchée et pourquoi. Rien d'autre :
  aucun mixin, aucun renderer, aucun `RegisterCommandsEvent`, aucun hook de cycle de rendu.
- **`Lanterne.java`** — deux ajouts strictement additifs : l'enregistrement du fichier de config
  CLIENT séparé (à côté de celui du projecteur), et l'appel à `Portail.verifie()` dans le bloc
  déjà gardé par `FMLEnvironment.getDist().isClient()`. Aucune ligne existante modifiée.

Aucun fichier partagé à risque (`core/Config.java`, `core/ClientConfig.java`,
`resources/lanterne.mixins.json`) n'a été touché. `lanterne.mixins.json` n'a d'ailleurs aucune
raison de l'être : ce scaffold ne contient aucun mixin.

---

## 5. Coût de performance

**Non mesuré, et volontairement non chiffré.** `Radiographie` mesure un coût de rendu réel ; il n'y
a ici aucun rendu à mesurer — le réglage n'exécute qu'une comparaison booléenne et, au mieux, une
ligne de journal une fois au démarrage. Publier un chiffre ici (« 0,0 ms ») serait une forme de
faux rapport : ce ne serait pas la preuve que l'intégration est gratuite, seulement la preuve
qu'elle n'existe pas. La règle que ce dépôt applique ailleurs — *un module non mesuré n'existe
pas* — s'applique ici à la lettre : ce module n'existe pas encore, au sens plein, et son coût
réel (des passes de rendu supplémentaires, intrinsèquement plus chères) ne pourra être chiffré que
le jour où une vraie implémentation existera pour le mesurer.

---

## Ce qui a été fait cette passe

- Recherche web réelle (18 septembre 2026) sur la disponibilité d'Immersive Portals pour NeoForge
  et pour toute version proche de « 26.x » — détail et sources en §1.
- Relecture de `README.md` §« Le pipeline de rendu », `notes/hiz-occlusion-entites.md`,
  `notes/moteur-rendu-maison.md`, `mixin/RenderPipelinesMixin.java`,
  `mixin/VulkanFeatureSetsMixin.java`, `mixin/VulkanPhysicalDeviceMixin.java`,
  `tools/verifie_mixins.py` (docstring) — pour établir l'architecture de rendu réelle de ce dépôt
  sans rien redécouvrir qui l'était déjà.
- Relecture de `core/Config.java`, `core/ClientConfig.java`, `core/Treve.java`,
  `content/relief/ReliefConfig.java`, `content/relief/Relief.java`, `report/Compagnons.java`,
  `Lanterne.java` en entier — pour trouver le bon patron d'intégration (compagnon détecté par
  `ModList`, fichier de config séparé) avant d'écrire une ligne.
- `git status`/`git diff --stat` avant toute édition : `core/ClientConfig.java` et
  `core/Settings.java` étaient déjà modifiés par un autre chantier au moment d'écrire ces lignes —
  d'où le choix, renforcé et non simplement suivi par prudence théorique, d'un fichier de config
  séparé plutôt que d'ajouter un champ à `ClientConfig.java`.
- Écrit `client/portals/PortailsConfig.java`, `client/portals/Portail.java`, ce fichier, et deux
  ajouts strictement additifs dans `Lanterne.java` — détail en §4.
- Aucun mixin écrit : `tools/verifie_mixins.py` n'a donc rien de neuf à vérifier, et
  `lanterne.mixins.json` n'a pas été touché.
- Aucun jar d'Immersive Portals n'a été téléchargé ni exécuté : aucun n'existe pour ce moteur (§1),
  et ce dépôt interdit toute exécution aveugle de toute façon.

---

## Ce qu'il faudrait pour lever le blocage

1. **Le blocage §1 (disponibilité)** ne se lève pas de ce côté-ci du projet : soit une version
   réelle de Minecraft/NeoForge rejoint un jour le numéro « 26.3 » de ce dépôt (peu probable — ce
   numéro n'appartient qu'à ce projet), soit ce dépôt migre un jour vers une vraie version que le
   mod couvre. Rien à faire ici en attendant.
2. **Le blocage §2 (architecture)** ne se lève que par une réécriture complète du concept sur
   `renderpearl` — pas un portage, une nouvelle implémentation. Voir §3 pour l'esquisse et l'ordre
   de grandeur (semaines à mois, dans la même classe d'effort que la Phase 3 de
   `notes/moteur-rendu-maison.md`).
3. **Si ce chantier est repris un jour**, la première étape n'est PAS d'écrire du code : c'est de
   vérifier, comme pour la Phase 0 de `notes/moteur-rendu-maison.md`, qu'un rendu multi-passes
   composé (un `RenderPass` par portail, sur une `GpuTextureView` dédiée) est bien exécutable sur ce
   moteur — par un essai minimal et isolé, avant tout mixin, avant toute idée de récursion.

Tant que ces conditions ne sont pas remplies, ce module **n'existe pas**, au sens où ce dépôt
l'entend — et c'est pour cela que le réglage construit cette passe est désactivé par défaut et ne
fait rien de plus qu'un message de journal : un mécanisme désactivé qui prétendrait faire plus
serait un décor, exactement ce que ce dépôt refuse ailleurs.
