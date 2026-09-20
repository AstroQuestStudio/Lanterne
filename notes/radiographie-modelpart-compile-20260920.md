# Radiographie du 20 septembre 2026 — `ModelPart.compile` à 0,6 %

> Même convention que `notes/hiz-occlusion-entites.md` : **VÉRIFIÉ** veut dire qu'une commande a
> été lancée ou qu'une source primaire (`javap` sur le jar patché réel
> `build/moddev/artifacts/minecraft-patched-26.3.0.3-beta.jar`, et ses sources décompilées
> officielles `minecraft-patched-26.3.0.3-beta-sources.jar` — jamais `.mcsrc/`, périmé, 26.1.2)
> a été lue et citée ; **SUPPOSÉ** est signalé comme tel.

Contexte : rapport `lab/Radiographie.java` d'une session de 512,5 s / 75179 images en jeu
(instance CurseForge « test 1 », Distant Horizons + Sodium en compagnons). La méthode
`net.minecraft.client.model.geom.ModelPart.compile` apparaît à **0,6 % de temps propre**,
cumulée sur toute la session. La mission demandait de vérifier si un mécanisme de mise en cache
manque ou est invalidé à tort.

---

## Verdict

**Pas un bug.** `ModelPart.compile` n'a jamais été, dans cette version, un coût de construction
de maillage mis en cache : c'est la méthode qui transforme les sommets de chaque `Cube` par la
pose (position/rotation/échelle) **courante** et les pousse dans le `VertexConsumer`. Elle est
appelée **sans aucune condition de cache**, depuis `ModelPart.render()`, à chaque image, pour
chaque partie de modèle visible et non vide. Rien à mettre en cache : la géométrie dépend de la
pose, qui change à chaque image dès qu'une entité est animée (marche, tête qui suit le joueur,
etc.) — un maillage figé produirait des entités statufiées.

---

## 1. Ce qui est vérifié en bytecode et source réels

Sur `net/minecraft/client/model/geom/ModelPart.class` / `.java` du jar patché 26.3.0.3-beta :

- **Source** (`ModelPart.java`, lignes 107-123) : `render(...)` appelle
  `this.compile(poseStack.last(), buffer, lightCoords, overlayCoords, color)` **sans aucun
  drapeau, sans aucun `if` de cache** — seule condition : `this.visible` et
  `!this.skipDraw`, deux booléens de *visibilité*, pas de *fraîcheur*.
- **Bytecode** (`javap -p -c ModelPart.class`) : la méthode `render(PoseStack, VertexConsumer,
  int, int, int)` contient `58: invokevirtual … Method compile:(...)V` en séquence directe,
  concordant exactement avec la source décompilée — aucune divergence entre les deux sources
  vérifiées indépendamment.
- `compile(PoseStack.Pose, VertexConsumer, int, int, int)` (lignes 177-181 / bytecode ligne 425)
  boucle sur `this.cubes` et appelle `ModelPart.Cube.compile(...)`, qui **retransforme chaque
  sommet par la matrice de pose courante** (`pose.pose().transformPosition(...)`,
  `Cube.java`-inline lignes 326-344) — un calcul qui n'a de sens que refait à chaque image tant
  que la pose peut varier.
- **Aucun champ « compiled »/« dirty » n'existe dans `ModelPart`** : la classe entière (397
  lignes lues intégralement) ne déclare que des champs de pose (`x`, `y`, `z`, `xRot`…),
  `visible`, `skipDraw`, `cubes`, `children`, `initialPose` — rien qui ressemble à un cache de
  maillage compilé.

## 2. Lanterne ne touche pas cette classe

`grep -rn "ModelPart" src/main/java` ne remonte qu'un faux positif dans
`client/terrain/Jointure.java` : il s'agit de `net.minecraft.client.renderer.block.dispatch.
BlockStateModelPart`, une classe **différente et sans rapport** (modèles de *bloc*, pas
d'*entité*). Aucun mixin, aucune classe `core/` ou `client/` de Lanterne n'importe ni n'étend
`net.minecraft.client.model.geom.ModelPart`.

## 3. Distant Horizons ne duplique pas ce coût

`BlazeDhGenericObjectRenderer.render` apparaît séparément dans le même rapport, à 0,6 %. Décompilé
et `javap`-é depuis le jar **réel de l'instance de test** (`DistantHorizons-3.3.0-26.3-fabric-
neoforge.jar`, dossier `curseforge/minecraft/Instances/test 1/mods/`) : cette classe ne référence
**jamais** `ModelPart` — elle dessine ses propres `RenderableBoxGroup` / `DhApiRenderableBox` via
son API « objets génériques » (son propre pipeline Vulkan, ses propres tampons de sommets), un
mécanisme totalement indépendant du rendu d'entité vanilla. Les deux lignes du rapport sont donc
deux coûts distincts et non liés, pas une double comptabilisation d'un même travail.

---

## Conclusion

0,6 % de temps propre cumulé sur 75179 images, pour retransformer les sommets de toutes les
entités animées visibles à chaque image, est un coût **proportionnel et attendu** — pas un défaut
de cache, puisqu'il n'existe ici rien de légitimement cacheable (la pose change en continu). Aucun
correctif proposé : forcer un « cache » sur une géométrie dépendant de la pose courante casserait
l'animation de toute entité affichée par ce mod ou par vanilla.

## Ce qui a été fait cette passe

- Lu en entier `ModelPart.java` (sources décompilées officielles 26.3.0.3-beta) et `javap -p -c`
  sur `ModelPart.class` (jar patché réel) — concordance totale entre les deux.
- `grep -rn "ModelPart" src/main/java` sur tout Lanterne : un seul résultat, faux positif
  (`BlockStateModelPart`, classe différente).
- Extrait et `javap -p -c` `BlazeDhGenericObjectRenderer.class` depuis le jar réel de Distant
  Horizons installé dans l'instance de test citée par la mission — aucune référence à `ModelPart`.
- Aucun fichier de production modifié.
