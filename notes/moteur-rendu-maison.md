# Moteur de rendu de chunks maison — plan de conception

**Statut : document de conception, aucun code écrit.** Aucune ligne de Sodium n'a été lue pour
produire ce document — ni son dépôt cloné dans `Lanterne-mine`, ni son code source ailleurs. Tout ce
qui suit vient de trois sources seulement : les sources décompilées de Minecraft 26.3
(`build/moddev/artifacts/minecraft-patched-26.3.0.3-beta-sources.jar`), des techniques de rendu
publiques documentées depuis des décennies (greedy meshing, portal culling, BSP), et le code déjà
écrit dans Lanterne (`client/upscale/*`, `client/sodium/Graft.java`).

## 1. Ce que Minecraft 26.3 expose déjà, nativement

Bonne surprise en lisant les sources : le moteur vanilla a plus de bases utiles qu'on ne l'imaginait.

- **`SectionCompiler`** (`net.minecraft.client.renderer.chunk`) construit le maillage d'une section
  16³ : `compile(SectionPos, RenderSectionRegion, VertexSorting, SectionBufferBuilderPack, List<...AdditionalSectionRenderer>)`
  renvoie un `Results` portant `Map<ChunkSectionLayer, MeshData> renderedLayers`,
  `VisibilitySet visibilitySet` et `MeshData.SortState transparencyState`. C'est un maillage par
  bloc/face (un quad par face exposée), pas du greedy meshing — la place pour l'optimiser est ici,
  sans rien mixiner à l'aveugle : `AddSectionGeometryEvent.AdditionalSectionRenderer` est déjà un
  point d'extension NeoForge officiel pour ajouter de la géométrie à une section compilée.
- **`VisGraph` + `VisibilitySet` existent déjà, et c'est exactement la primitive qu'il faut.**
  `VisGraph.setOpaque(BlockPos)` marque les blocs pleins d'une section, puis `resolve()` fait un
  remplissage par propagation (flood fill) sur les 4096 cellules et produit un `VisibilitySet` qui
  répond à `visibilityBetween(Direction, Direction)` — c'est-à-dire « peut-on voir d'une face de
  cette section à une autre en traversant du vide ? ». C'est la brique de base d'un culling par
  portails/graphe de visibilité, et elle est 100 % vanilla, déjà calculée à chaque compilation de
  section. Rien à réinventer ici.
- **Ce qui ne semble PAS déjà fait par vanilla** : le parcours en largeur (BFS) à travers plusieurs
  sections en chaînant leurs `VisibilitySet` pour décider quelles sections lointaines sont
  réellement invisibles. `LevelExtractor` (qui a remplacé la moitié amont de `LevelRenderer` en
  26.2) ne semble consulter `visibilityBetween` nulle part dans son propre fichier — la recherche
  vanilla actuelle a l'air de s'appuyer sur le frustum seul, section par section, sans chaîner le
  graphe entre sections. **C'est là que serait le vrai travail à faire**, pas dans le calcul du
  graphe lui-même (déjà là) mais dans son exploitation à travers les sections.
- **L'abstraction de rendu moderne** (`com.mojang.blaze3d.systems.GpuDevice/GpuBackend/CommandEncoder/RenderPass`,
  `RenderPipeline` avec `BindGroupLayout`) est déjà utilisée dans Lanterne : `client/upscale/Scene.java`
  et `Resolve.java` manipulent déjà des `RenderTarget` et un `FrameGraphBuilder` pour un système de
  rendu à résolution réduite puis remonté à l'écran — c'est la meilleure preuve que ce projet sait
  déjà travailler avec cette couche, et la référence directe à suivre plutôt que d'improviser.

## 2. Découpage en phases, chacune vérifiable en jeu

Aucune phase ne doit dépendre d'une phase suivante pour être jugée correcte — chacune doit pouvoir
être testée, confirmée ou rejetée par l'utilisateur indépendamment.

### Phase 0 — Rien de neuf, juste observer

Avant d'écrire quoi que ce soit : instrumenter (compteur à l'écran, ou ligne de journal) le nombre de
sections actuellement rendues par frame et le nombre de triangles envoyés, sans toucher au rendu
lui-même. **Critère de succès** : l'utilisateur voit un chiffre stable, cohérent avec ce qu'il
observe (par exemple, ce chiffre doit chuter nettement en regardant un mur à bout portant). Sert de
ligne de base à toutes les phases suivantes — sans mesure avant, aucun « gain » ne pourra jamais être
prouvé, exactement la règle que ce projet applique déjà partout ailleurs.
**Effort : heures. Risque : nul** — aucun changement de rendu.

### Phase 1 — Culling de faces cachées (greedy meshing minimal)

Remplacer, section par section, le maillage par face-exposée de `SectionCompiler` par un maillage qui
ne génère aucune face entre deux blocs opaques adjacents (le calcul existe déjà implicitement dans le
test d'occlusion de face que `SectionCompiler` fait bloc par bloc — il s'agit de le rendre
systématique plutôt que ponctuel). Le greedy meshing complet (fusionner plusieurs faces coplanaires
identiques en un seul quad) est une deuxième étape, plus dure, à l'intérieur de cette même phase :
d'abord « ne pas dessiner les faces invisibles », ensuite « fusionner les faces visibles ».
**Critère de succès observable** : le compteur de triangles de la Phase 0 baisse nettement (les
techniques publiées parlent de 2 à 4× sur un terrain dense) **et** l'image est identique à l'œil —
aucun trou, aucune face manquante visible. C'est le test le plus facile à rater sans s'en rendre
compte : une face supprimée à tort ne crashe jamais, elle laisse un trou.
**Effort : semaines. Risque : réel et silencieux** — un bug ici ne crashe pas, il rend un mur
invisible par endroits. Demande une relecture attentive de l'utilisateur, pas juste « ça s'affiche ».

### Phase 2 — Culling par section plutôt que par colonne

Vérifier d'abord que ce n'est pas déjà vanilla : `LevelExtractor`/`SectionRenderDispatcher`
semblent déjà découper par section 16³ nativement (c'est la structure même de `SectionCompiler`) —
cette phase pourrait donc être un non-événement, à confirmer avant d'écrire une ligne. Si un gisement
existe, il serait dans la façon dont le frustum culling regroupe ou non des sections voisines plutôt
que dans la granularité elle-même.
**Effort : jours d'audit avant toute décision d'implémenter.**

### Phase 3 — Occlusion par graphe de visibilité entre sections

Le vrai chantier neuf identifié en partie 1 : chaîner les `VisibilitySet` de sections adjacentes en
un parcours en largeur depuis la section du joueur, façon culling par portails (technique publiée,
utilisée dans des moteurs de jeu depuis les années 1990, pas propre à un mod). Une section n'est
visitée que si le graphe permet d'y voir depuis la section de départ à travers des faces ouvertes.
**Critère de succès observable** : dans une grotte fermée ou sous une montagne, le compteur de
sections rendues chute fortement dès qu'aucun chemin de vue n'existe vers la surface — à vérifier en
se plaçant sciemment dans une poche fermée et en comparant le compteur avant/après.
**Effort : semaines à mois — c'est la phase la plus dure.** Risque principal : un bug de graphe peut
soit sur-cacher (sections manquantes, trous visibles au loin) soit sous-cacher (aucun gain, mais pas
de dégât visuel) — le sens d'erreur à préférer en cas de doute est le second.

### Phase 4 — Tri de transparence et réduction d'overdraw

Trier les faces transparentes (eau, verre) par profondeur avant envoi — BSP tree ou tri simple par
distance selon la complexité réelle rencontrée. **Critère de succès** : pas de scintillement/artefact
visuel en regardant à travers plusieurs couches de blocs transparents superposés (verre + eau, par
exemple), comparé à avant.
**Effort : semaines.**

## 3. Coexistence avec Sodium — prérequis avant tout code actif

`client/sodium/Graft.java` et `client/Doorway.java` utilisent déjà `net.neoforged.fml.ModList.get()`
pour détecter la présence de Sodium et s'y greffer plutôt que le concurrencer côté interface. Le même
mécanisme doit gater ce moteur : au démarrage, si `ModList.get().isLoaded("sodium")` (ou l'id exact du
mod) répond vrai, le chunk renderer maison de Lanterne ne s'active jamais — Sodium garde la main sur
tout le pipeline de section, sans qu'aucun des deux ne tente de mixiner la même méthode. **Cette
détection doit être écrite et vérifiée AVANT la Phase 1**, pas après — un conflit de deux mods qui
réécrivent tous deux `SectionCompiler`/`LevelExtractor` planterait au démarrage, exactement le genre
d'incident déjà rencontré cette session avec des cibles de mixin qui ne correspondent plus.

## 4. Ce que ce document ne tranche pas

Aucune phase au-delà de la 0 ne doit démarrer sans qu'une phase précédente ait été confirmée en jeu.
Le risque commun à toutes les phases de rendu (1, 3, 4) est le même : un défaut visuel ne crashe pas,
donc rien ici ne le détecte seul — seul un œil humain, en jeu, le peut.
