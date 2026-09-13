# Recherche serveur — cibles d'optimisation Minecraft 26.1.2 (NeoForge)

> Recherche seule, aucun fichier de `src/` touché. Sources vanilla vérifiées sur un dépôt de
> décompilation communautaire non officiel, **`ohnodev/decompiled-minecraft-26-1-1`**
> (`main`, correspond à Minecraft 26.1.1 — une révision derrière notre 26.1.2, mais avant les
> classes ici citées n'ont pas de raison d'avoir changé entre deux hotfixes). Chaque extrait de
> code cité a été récupéré tel quel via `raw.githubusercontent.com` ; je précise à chaque fois si
> je n'ai vérifié qu'une signature (mappings.dev / javadocs Forge) sans corps de méthode.

---

## 1. Collisions d'entités en foule

### 1.1 `LivingEntity.pushEntities` / `Level.getPushableEntities` — déjà traité par Lanterne, à compléter

**Déjà fait ici** : `LivingEntityMixin` (Lanterne) tronque la liste au-delà de 32 voisines
(`PUSH_LIMIT = 26`) et court-circuite entièrement via `Jam.jammed(...)` pour les amas immobiles.
C'est la même stratégie que Paper (`max-entity-collisions`, huit par défaut).

**Ce qui reste, vérifié dans le code de Lithium** (`CaffeineMC/lithium`, catégorie
`mixin.entity.collisions.unpushable_cramming`) :

- Fichier : [`EntitySectionMixin.java`](https://github.com/CaffeineMC/lithium/blob/develop/common/src/main/java/net/caffeinemc/mods/lithium/mixin/entity/collisions/unpushable_cramming/EntitySectionMixin.java)
- Lithium ne plafonne pas le nombre de voisines : il **retire de la liste candidate**, en amont,
  les entités qui ne peuvent structurellement pas être poussées (dans une échelle/un état non
  poussable), via une `ReferenceMaskedList` maintenue à jour par les callbacks d'ajout/retrait de
  `EntitySection`. Le README interne du mod est explicite :

  > « The bounding box intersection and the pushability test are evaluated by each entity for
  > each entity nearby. The amount of work done grows quadratically... Placing more than 100
  > entities in a ladder leads to an unreasonable amount of lag. »

- Cette optimisation est **complémentaire**, pas redondante, avec le plafond de Lanterne : elle
  réduit le *n* du carré (moins de candidates dans la boîte) là où Lanterne réduit le nombre de
  poussées *après* avoir trouvé les candidates. Le cas cible du brief — 1000 vaches sur 15×15
  blocs, donc **une seule `EntitySection` 16×16×16** — est exactement le cas que Lanterne couvre
  déjà (le seuil de foule à 32 déclenche la troncature). Lithium n'aide ici que si les vaches sont
  dans une échelle, ce qui n'est pas le cas cible.

### 1.2 Ce qui est réellement quadratique, avec chiffres

Avec 1000 vaches dans 15×15 blocs (une `EntitySection`) :

- `pushEntities()` (vanilla) : chaque vache appelle `Level.getPushableEntities(this, box)` →
  parcourt la section entière (~1000 entités), teste l'intersection AABB puis la « poussabilité »
  pour chacune. **1000 × 1000 = 1 000 000 tests** par tick, avant même la poussée elle-même.
  C'est le nombre que le javadoc de `LivingEntityMixin` (Lanterne) documente déjà
  (« sept virgule huit pour cent du temps » sur `EntitySection.getEntities`).
- `Entity.collide(Vec3)` (mouvement, pas poussée) : signature confirmée dans
  [`Lithium EntityMixin`](https://github.com/CaffeineMC/lithium/blob/develop/common/src/main/java/net/caffeinemc/mods/lithium/mixin/entity/collisions/movement/EntityMixin.java) —
  `collide(Vec3)`, `Entity.collideBoundingBox(Entity, Vec3, AABB, Level, List<VoxelShape>)` (statique),
  `Level.getEntityCollisions(Entity, AABB)`. Lithium **reporte** l'appel à
  `getEntityCollisions` (normalement fait avant tout calcul) pour ne le faire que si le
  déplacement n'a pas déjà été bloqué par les blocs — un déplacement bloqué par un mur n'a pas
  besoin de chercher les entités voisines. C'est un **changement d'ordre d'évaluation**, résultat
  identique. Fichier :
  [`LithiumEntityCollisions` (paresse des listes)](https://github.com/CaffeineMC/lithium/blob/develop/common/src/main/java/net/caffeinemc/mods/lithium/mixin/entity/collisions/movement/EntityMixin.java).
- `EntityGetter.getEntities` → `Level.noCollision` : Lithium réécrit aussi
  [`LevelMixin` (intersection)](https://github.com/CaffeineMC/lithium/blob/develop/common/src/main/java/net/caffeinemc/mods/lithium/mixin/entity/collisions/intersection/LevelMixin.java)
  pour tester d'abord les blocs (accès rapide), puis seulement les entités « dures », puis la
  bordure du monde — dans cet ordre, parce que c'est le moins cher en premier. Encore un
  réordonnancement à résultat identique.

### 1.3 Ce qu'`immersive_optimization` et `adaptive_performance_tweaks` font (et qui ne touche PAS aux collisions)

Vérifié sur le code source des deux :

- **immersive_optimization** (`Luke100000/ImmersiveOptimization`,
  [`TickScheduler.java`](https://github.com/Luke100000/ImmersiveOptimization/blob/1.20.1/common/src/main/java/net/conczin/immersive_optimization/TickScheduler.java)) :
  calcule une « priorité » par entité (1 = pleine vitesse, N = un tick sur N) selon la distance au
  joueur le plus proche, la portée de tracking réseau, et le frustum de rendu (uniquement en
  solo/intégré). `shouldTick(Entity)` renvoie `(tick + entityId) % priority == 0` — **exactement
  la même formule d'étalement que `EntityThrottle`/`Cadence` de Lanterne**. C'est un scheduler de
  *tick*, pas de collision : deux vaches ralenties par lui se poussent quand même en O(n²) le tick
  où elles agissent. Le README du projet lui-même prévient de la redondance avec un mod qui ferait
  la même chose (confirmé aussi par le `README.md` de Lanterne : « Cohabitation »).
- **adaptive_performance_tweaks** (`MarkusBordihn/BOs-Adaptive-Performance-Tweaks`) : le module
  `AiThrottleManager` (feature/aithrottle) réduit la fréquence de l'IA des mobs sous charge, mais
  ne touche ni `pushEntities` ni `collide`. Aucune ligne du dépôt ne référence
  `getPushableEntities` ou `getEntityCollisions`.

**Conclusion axe 1** : le vrai gain qui reste, non couvert par Lanterne ni par les mods du pack,
est le **réordonnancement d'évaluation dans `Entity.collide`** (blocs avant entités avant bordure)
et la **paresse de `getEntityCollisions`** — tel que fait par Lithium. Risque : quasi nul, ce sont
des réordonnancements à résultat mathématiquement identique (Lithium le fait depuis des années en
production). Le seul vrai risque serait une régression d'ordre d'évaluation si un mod tiers
attend un effet de bord précis dans `getEntityCollisions` (aucun cas connu).

---

## 2. Items au sol

### 2.1 Ce que vanilla fait vraiment — `ItemEntity.tick()` (source vérifiée, corps complet)

Contrairement à l'hypothèse implicite du brief, **vanilla n'appelle pas `mergeWithNeighbours()`
à chaque tick**. Extrait exact (`ItemEntity.java`, 26.1.1) :

```java
boolean moved = Mth.floor(this.xo) != Mth.floor(this.getX())
   || Mth.floor(this.yo) != Mth.floor(this.getY())
   || Mth.floor(this.zo) != Mth.floor(this.getZ());
int rate = moved ? 2 : 40;
if (this.tickCount % rate == 0 && !this.level().isClientSide() && this.isMergable()) {
   this.mergeWithNeighbours();
}
```

Et `mergeWithNeighbours()` :

```java
private void mergeWithNeighbours() {
   if (this.isMergable()) {
      for (ItemEntity entity : this.level()
         .getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(0.5, 0.0, 0.5),
                              other -> other != this && other.isMergable())) {
         if (entity.isMergable()) {
            this.tryToMerge(entity);
            if (this.isRemoved()) break;
         }
      }
   }
}
```

`isMergable()` : `isAlive() && pickupDelay != 32767 && age != -32768 && age < 6000 &&
item.getCount() < item.getMaxStackSize()`. `LIFETIME = 6000` ticks (5 min), confirmé.

**Coût réel** : un item posé (`moved == false`) ne tente une fusion qu'un tick sur 40 (2 s). Un
item qui bouge encore (chute, poussé par courant) le fait un tick sur 2. Le coût par tentative est
une requête spatiale `getEntitiesOfClass` sur une boîte de 1×?×1 bloc — **coût local**, proportion
-nel à la densité au même endroit, pas global. Avec 1 000 000 d'items **répartis sur un grand
monde**, le coût par item est donc faible (peu de voisins). Le cas qui fait vraiment mal est
l'**entonnoir de ferme** où des milliers d'items non empilables (ex. items avec NBT différents)
s'accumulent au même bloc : chaque item retente alors 25 fusions/s contre tous ses voisins non
mergeables, coût quadratique **local** (k² avec k = nombre d'items au même point).

### 2.2 Constat non prévu par le brief : effet de horde synchronisée

`tickCount % rate == 0` utilise l'âge propre de l'entité, pas un décalage par identifiant. **Tous
les items qui apparaissent au même tick** (une explosion qui casse 40 blocs, un mob-farm qui tue
30 mobs d'un coup) partagent le même `tickCount` de départ, donc **retentent leur fusion sur les
mêmes ticks, indéfiniment** (40, 80, 120…). C'est exactement le défaut que Lanterne a déjà identifié
et corrigé ailleurs (`NetworkThrottle`/`ServerEntityMixin`, javadoc : « on aurait remplacé un flux
continu par des rafales »). Optimisation proposée : étaler avec `(tickCount + getId()) % rate == 0`
— même formule que `Cadence`/`immersive_optimization`. Risque : minime — retarde une fusion d'au
plus `rate-1` ticks pour certains items, sans jamais l'empêcher ; aucune ferme ne dépend de l'ordre
exact de fusion des items entre eux.

### 2.3 Ce que fait `getittogetherdrops` (source vérifiée)

Fichier : [`ItemEntityMixin.java`](https://github.com/bl4ckscor3/GetItTogetherDrops/blob/26.1.1/common/src/main/java/bl4ckscor3/mod/getittogetherdrops/mixin/ItemEntityMixin.java)

```java
@Inject(method = "mergeWithNeighbours", at = @At("HEAD"), cancellable = true)
private void mergeWithNeighbours(CallbackInfo info) {
    ...
    for (ItemEntity ei : me.level().getEntitiesOfClass(ItemEntity.class,
            me.getBoundingBox().inflate(radius, checkY ? radius : 0.0D, radius),
            e -> e != me && ((ItemEntityInvoker) e).callIsMergable())) {
        ((ItemEntityInvoker) me).callTryToMerge(ei);
        if (me.isRemoved()) break;
    }
    info.cancel();
}
```

**Ce mod ne change pas la complexité** : il remplace le rayon fixe de 0,5 bloc par un rayon
configurable (jusqu'à 500 blocs). Un rayon plus grand **augmente** le nombre de voisins trouvés par
requête — c'est un gain de *gameplay* (les items se rassemblent mieux dans une ferme étalée), pas
un gain de *performance*. Avec un grand rayon et beaucoup d'items, ce mod peut **aggraver** le
coût quadratique local qu'il cherche à éviter. Aucune structure de données nouvelle : toujours un
`getEntitiesOfClass` par item, à la même cadence que vanilla.

### 2.4 Ce que Lithium fait (catégorie expérimentale, code vérifié)

Fichier : [`item_entity_merging/ItemEntityMixin.java`](https://github.com/CaffeineMC/lithium/blob/develop/common/src/main/java/net/caffeinemc/mods/lithium/mixin/experimental/entity/item_entity_merging/ItemEntityMixin.java)

Lithium redirige `Level.getEntitiesOfClass` (appelé par `mergeWithNeighbours`) vers une structure
**`ItemEntityList`** propre à chaque `EntitySection`, qui **catégorise les items par type
d'objet ET par groupe de taille de pile** (doc du mixin config : « Categorizing by stack size
allows skipping merge attempts of full item entities »). Résultat : un diamant qui cherche à
fusionner ne parcourt plus la liste de *tous* les items de la section, seulement ceux du même type
et pas déjà pleins. C'est la **seule** solution du panorama qui réduit vraiment le *n* de la
boucle. Signalé « expérimental » par Lithium lui-même — donc pas activé par défaut, à auditer avant
adoption.

### 2.5 Ce que fait `adaptive_performance_tweaks` — approche radicalement différente et plus risquée

Fichier : [`ItemEntityManager.java`](https://github.com/MarkusBordihn/BOs-Adaptive-Performance-Tweaks/blob/1.20.1/Common/src/main/java/de/markusbordihn/adaptiveperformancetweaks/feature/items/ItemEntityManager.java)

Ce mod **abandonne totalement** la recherche spatiale de vanilla : il maintient sa propre table
`Map<String, Set<ItemEntity>>` indexée par `[dimension]nom_item`, fusionne par comparaison directe
de coordonnées entières (`(int) getX()` ± `itemsClusterRange`), et surtout **applique un plafond
dur** (`maxNumberOfItems`, `maxNumberOfItemsPerType`) : au-delà, il **détruit** l'item le plus petit
du tas (`removalCandidate.remove(RemovalReason.DISCARDED)`). C'est efficace, mais **casse la
garantie « rien n'est jamais supprimé »** que Lanterne s'impose : passé le plafond, des items
disparaissent silencieusement — dans une ferme de minerai qui produit plus vite qu'un joueur ne
ramasse, ça se traduit par une perte de production non signalée. À proscrire dans la philosophie
de ce mod, mais bon à connaître : c'est l'option "nucléaire" quand tout le reste a échoué.

**Conclusion axe 2** : la vraie cible non couverte est double — (a) étaler `tickCount % rate` par
identifiant (trivial, sûr, jamais fait ailleurs) et (b) le bucketing par type/taille de pile façon
Lithium pour le cas dense (ferme qui empile). Le plafond dur façon Adaptive Performance Tweaks est
à éviter.

---

## 3. Fluides

### 3.1 Ce que vanilla fait déjà (surprise : un cache existe déjà)

`FlowingFluid.tick(ServerLevel, BlockPos, BlockState, FluidState)` (source vérifiée, 26.1.1) :
calcule `getNewLiquid` (jusqu'à 6 lectures de bloc : 4 horizontales + bas + haut), planifie un
retick si l'état change, puis appelle `spread()` → `spreadToSides()` → `getSpread()`.

`getSpread()` appelle, pour chacune des 4 directions horizontales, `getSlopeDistance()` —
**récursion non mémoïsée**, profondeur = `getSlopeFindDistance` (4 pour l'eau), branchement = 3
directions à chaque niveau (on exclut la direction de provenance). Pire cas théorique : jusqu'à
3⁴ = 81 lectures de bloc par direction de départ, ×4 = **jusqu'à ~324 `getBlockState` par bloc
d'eau qui coule**, par tick où il est planifié. Aucune mémoïsation des positions déjà visitées
dans la vanilla actuelle : deux chemins qui convergent vers la même case la re-testent.

**Fait notable, non mentionné dans le brief** : vanilla cache déjà `canPassThroughWall` via un
`ThreadLocal<Object2ByteLinkedOpenHashMap>` nommé `OCCLUSION_CACHE` (LRU de 200 entrées,
clé = paire d'états de bloc + direction). Ce n'est donc **pas** un boulevard vide : une partie du
travail (formes de collision répétées) est déjà mise en cache par le jeu lui-même.

### 3.2 Ce que Lithium fait (code vérifié, catégorie non-expérimentale `mixin.block.fluid.flow`)

Fichier : [`FlowingFluidMixin.java`](https://github.com/CaffeineMC/lithium/blob/develop/common/src/main/java/net/caffeinemc/mods/lithium/mixin/block/fluid/flow/FlowingFluidMixin.java)

Réécrit entièrement `getSpread` : (1) un chemin rapide si au plus une seule direction peut
recevoir le fluide (évite toute la recherche complexe) ; (2) sinon, un vrai parcours en largeur
(`calculateComplexFluidFlowDirections`) avec deux `Byte2ByteOpenHashMap` (`prevPositions`/
`currentPositions`) qui **dédoublonnent les positions déjà visitées** — contrairement à la
récursion vanilla. Un tableau `BlockState[]` local sert de cache de lecture pour la durée de
l'appel (les 1 à 2 lectures de bloc par position ne sont jamais refaites deux fois dans le même
appel). Gain net attendu : élimination des révisites, donc sous-linéaire par rapport au pire cas
vanilla dans les configurations où plusieurs chemins convergent (bassins, canaux).

**Conclusion axe 3** : le vrai gain qui reste est de **porter l'algorithme BFS-avec-dédoublonnage**
de Lithium (ou s'en inspirer) plutôt que la récursion vanilla. Risque : faible à modéré — c'est un
changement d'algorithme de recherche, pas juste de cache ; Lithium le classe en dehors de sa
catégorie « experimental », ce qui indique une confiance de production, mais toute réécriture
d'algorithme de recherche doit être rejouée contre des cas limites (bassins asymétriques, un seul
bloc d'eau isolé) avant d'être fiable à 100 % identique.

---

## 4. Explosions / TNT — la meilleure cible du rapport

### 4.1 Le corps réel de `ServerExplosion.explode()` (26.1.1, vérifié intégralement)

La classe qui porte la logique s'appelle **`ServerExplosion`** (implémente l'interface
`Explosion`), pas `Explosion.explode()` comme suggéré par le brief — `Explosion` n'est plus qu'une
interface de données depuis le refactor. Méthode : `calculateExplodedPositions()` :

```java
private List<BlockPos> calculateExplodedPositions() {
   ...
   for (int xx = 0; xx < 16; xx++)
    for (int yy = 0; yy < 16; yy++)
     for (int zz = 0; zz < 16; zz++)
      if (xx==0||xx==15||yy==0||yy==15||zz==0||zz==15) {   // surface du cube seulement
         ...
         for (float stepSize = 0.3F; remainingPower > 0.0F; remainingPower -= 0.22500001F) {
            BlockPos pos = BlockPos.containing(xp, yp, zp);
            BlockState block = this.level.getBlockState(pos);
            FluidState fluid = this.level.getFluidState(pos);   // ← DEUXIÈME accès chunk, redondant
            ...
         }
      }
}
```

Le test `xx==0||xx==15||...` confirme : **16×16×16 − 14×14×14 = 4096 − 2744 = 1352 rayons**,
exactement le chiffre du brief. Pour une charge de puissance 4 (TNT), `remainingPower` démarre
autour de `4 × (0.7 à 1.3) ≈ 2.8–5.2`, décrémenté de `0.225`/pas → **environ 12 à 23 pas par
rayon**. Total : **1352 × ~18 ≈ 24 000 itérations**, chacune appelant `getBlockState` **et**
`getFluidState` séparément.

### 4.2 La découverte : un appel redondant, prouvé par le code du reste du moteur

`Level.getFluidState(BlockPos)` (vérifié, `Level.java` 26.1.1) :

```java
public FluidState getFluidState(final BlockPos pos) {
   if (!this.isInValidBounds(pos)) return Fluids.EMPTY.defaultFluidState();
   LevelChunk chunk = this.getChunkAt(pos);   // ← relookup de chunk
   return chunk.getFluidState(pos);            // ← relookup de section + palette, INDÉPENDANT du BlockState déjà lu
}
```

`LevelChunk.getFluidState(int,int,int)` refait tout le travail depuis zéro (index de section,
vérification `hasOnlyAir`, lecture de palette). **Il n'y a aucun partage avec le `BlockState` déjà
récupéré trois lignes plus haut.** Or `BlockState.getFluidState()` — utilisé par `FlowingFluid`
lui-même (voir §3, `belowState.getFluidState()`, `blockState.getFluidState()` partout dans
`FlowingFluid.java`) — est un accesseur **local à l'objet déjà en main**, sans aucun accès monde.
`ServerExplosion` est la seule classe du moteur, parmi celles étudiées ici, à ne pas suivre cet
idiome.

**Optimisation** : remplacer `this.level.getFluidState(pos)` par `block.getFluidState()` dans
`calculateExplodedPositions`. Résultat **rigoureusement identique** (même bloc, donc même état de
fluide dérivé), pour ~24 000 accès en moins par explosion — grossièrement, **la moitié** du coût
d'accès au monde de chaque TNT qui explose disparaît.

**Risque** : je ne lui en trouve aucun. `block.getFluidState()` et `level.getFluidState(pos)`
rendent par construction la même valeur tant que le bloc à `pos` n'a pas changé entre les deux
appels — ce qui est vrai ici (aucune écriture entre les deux lignes). Aucun mod du panorama étudié
(Lithium compris) ne touche `ServerExplosion` : **personne ne fait cette correction aujourd'hui**.
C'est la cible la plus sûre et la plus rentable de tout ce rapport.

### 4.3 `getSeenPercent` (vérifié) — coût additionnel, par entité proche

```java
public static float getSeenPercent(final Vec3 center, final Entity entity) {
   // grille de rayons sur la boîte englobante de l'entité, un appel level.clip(...) par point
}
```

Le nombre de points dépend de la taille de la boîte (pas fixe comme les 1352 rayons de bloc) ; pour
une vache (~0,9×1,3×0,9), c'est de l'ordre de quelques dizaines de `clip()` (chacun étant lui-même
un parcours de rayon à travers plusieurs blocs). Ce n'est appelé que pour les entités **dans le
rayon d'effet ×2** (`hurtEntities()` filtre par `AABB` avant) — donc ne s'aggrave que si beaucoup
d'entités sont proches de l'explosion (le cas du carré à 1000 vaches, justement, si une charge de
TNT y explose). Aucune optimisation « sûre » identifiée ici sans changer le nombre d'échantillons
(ce qui changerait le résultat observable de la formule d'exposition) — **à laisser tel quel**.

**Conclusion axe 4** : `getFluidState` redondant = gain énorme, risque nul, personne ne l'a fait.
Priorité absolue de ce rapport.

---

## 5. `LevelTicks` / block ticks / random ticks

### 5.1 Structure vérifiée — déjà bien conçue, peu de gras

`LevelTicks<T>` (source vérifiée, `net.minecraft.world.ticks`) : une `Long2ObjectMap` de
`LevelChunkTicks<T>` (un conteneur par chunk), plus une `Long2LongMap nextTickForContainer` qui
indexe, par position de chunk, **la prochaine échéance connue**. À chaque tick,
`sortContainersToTick` ne regarde que les entrées dont l'échéance est `<= currentTick` — **un
chunk sans tick dû ne coûte qu'une comparaison de `long`, un chunk sans tick planifié ne coûte
rien**. `LevelChunkTicks` utilise une `PriorityQueue` (tas binaire) par chunk, plus un
`ObjectOpenCustomHashSet` de déduplication (`ScheduledTick.UNIQUE_TICK_HASH`, hash sur position +
type). Il n'y a **pas** de balayage global à chaque tick : c'est déjà une structure à coût
amorti, pas quadratique. Rien à corriger ici sans risquer de casser l'ordre de dépilement
(`DRAIN_ORDER` : triggerTick, puis priorité, puis ordre d'insertion — des blocs comme les
répéteurs/comparateurs en dépendent pour leur synchronisation).

### 5.2 Random ticks — la vraie dépense, et ce que Lithium fait (vérifié, non expérimental)

Le tick des blocs aléatoires (`ServerLevel.tickChunk`) tire, par section non vide et par valeur de
`randomTickSpeed` (3 par défaut), une position aléatoire via `getBlockRandomPos` puis teste si le
bloc à cette position est aléatoire-tickable. **Lanterne a déjà supprimé l'allocation** de
`BlockPos` ici (`LevelMixin`), mais pas la recherche elle-même.

Fichier Lithium : [`ServerLevelMixin.java` (random_block_ticking)](https://github.com/CaffeineMC/lithium/blob/develop/common/src/main/java/net/caffeinemc/mods/lithium/mixin/world/chunk_ticking/random_block_ticking/ServerLevelMixin.java)

Lithium maintient, par `LevelChunkSection`, un **compteur de blocs aléatoire-tickables**
(`BlockCountingSection.lithium$getCount`) et un tableau `byte[]` de 17 « mini-sections » qui
comptent combien de blocs tickables s'y trouvent. Quand ce compteur est bas (`<= 384` sur 4096,
seuil réglé empiriquement), au lieu de tirer une position au hasard et vérifier si elle tombe sur
un bloc tickable (ce qui échoue la plupart du temps quand peu de blocs sont concernés), Lithium
tire **directement un indice parmi les blocs tickables** puis retrouve sa position via une
recherche linéaire bornée à la mini-section (≤ 256 blocs). **Aucun changement de la loi de
probabilité** — le commentaire du mod le dit explicitement (« Optimize random ticks without
changing their random distribution »). Le compteur est maintenu à jour à chaque
`setBlockState`, donc son coût est amorti sur les écritures, pas sur les lectures.

**Conclusion axe 5** : `LevelTicks` lui-même n'a pas de gras à couper — Mojang l'a déjà bien
construit. Le vrai gain est le **bucketing par compteur de blocs tickables** façon Lithium, qui
n'est pas dans Lanterne aujourd'hui (Lanterne n'a fait que l'allocation, pas la recherche). Risque
faible : la loi de probabilité est préservée, Lithium le classe hors « experimental ».

---

## 6. Sauvegarde des chunks

### 6.1 Ce qui a déjà changé dans cette version — vérifié, et ça change la réponse

**Correction importante par rapport au brief** : dans cette version (26.1.1), la sérialisation
NBT (`SerializableChunkData.write()`) et l'écriture disque sont **déjà asynchrones**. Extrait
vérifié de `ChunkMap.save(ChunkAccess)` :

```java
this.activeChunkWrites.incrementAndGet();
SerializableChunkData data = SerializableChunkData.copyOf(this.level, chunk);      // ← synchrone, thread principal
CompletableFuture<CompoundTag> encodedData =
    CompletableFuture.supplyAsync(data::write, Util.backgroundExecutor());          // ← async
this.write(pos, encodedData::join).handle((ignored, throwable) -> {                 // ← écriture disque, sur le thread du futur
    ...
    this.activeChunkWrites.decrementAndGet();
    return null;
});
```

`ChunkMap.saveAllChunks(boolean flushStorage)` existe bien (nom exact confirmé), mais son
chemin « périodique » (`flushStorage == false`) ne fait qu'appeler `saveChunkIfNeeded` pour
chaque chunk visible, avec un throttle déjà intégré au moteur :
`saveChunksEagerly` (appelée depuis `processUnloads`, donc à chaque tick serveur) plafonne à
**20 chunks sauvegardés par tick** et **128 écritures actives simultanées**
(`activeChunkWrites.get() < 128`) — c'est déjà un système de rationnement inspiré de ce que fait
`Rationing`/`TickBudget` dans Lanterne, mais construit dans le moteur.

### 6.2 Ce qui reste synchrone, et pourquoi c'est plus délicat qu'il n'y paraît

`SerializableChunkData.copyOf(ServerLevel, ChunkAccess)` — **ceci s'exécute sur le thread
principal**, avant l'offload asynchrone. Il copie chaque section non vide
(`LevelChunkSection.copy()`), les calques de lumière, les heightmaps, **et sérialise chaque
bloc-entité en NBT** via `chunk.getBlockEntityNbtForSaving(pos, registryAccess)` — un appel par
bloc-entité du chunk, sur le thread principal.

**Optimisation théorique** : déplacer aussi `copyOf` sur un thread d'arrière-plan.
**Pourquoi c'est risqué** : `copyOf` lit l'état *vivant* du chunk (sections, bloc-entités) pendant
que le thread principal continue de le modifier au tick suivant si l'appel n'est pas synchrone à
l'instant de la copie — c'est précisément pour garantir une vue cohérente que Mojang la garde sur
le thread principal. Le coût par bloc-entité (`getBlockEntityNbtForSaving`) est en revanche
proportionnel au nombre de bloc-entités du chunk (typiquement quelques dizaines, sauf base à
stockage massif — des centaines de coffres/tonneaux). C'est là qu'un hoquet de sauvegarde peut se
produire, mais je n'ai pas de chiffre fiable à donner sans profiler un cas réel avec beaucoup de
conteneurs.

**Conclusion axe 6** : contrairement à l'hypothèse du brief, **la compression et l'I/O disque ne
sont plus le problème** dans cette version — Mojang l'a déjà résolu. Le seul reste possible est le
coût de `copyOf` (essentiellement la sérialisation NBT des bloc-entités), et il est difficile à
réduire sans risquer une vue incohérente. **Priorité basse**, gain incertain et non chiffré, risque
non négligeable sur la cohérence des données sauvegardées si mal fait — une corruption de sauvegarde
est bien pire qu'un tick lent.

---

## 7. Génération de chunks

### 7.1 Ce qui est déjà asynchrone, vérifié

`NoiseBasedChunkGenerator.fillFromNoise(...)` **renvoie déjà un `CompletableFuture<ChunkAccess>`**
(signature vérifiée dans le code source, 26.1.1). La génération de terrain ne bloque donc déjà pas
le thread principal — elle tourne sur le pool d'exécuteurs de génération de chunks depuis plusieurs
versions. Ce que demande le brief (« quelles parties sont parallélisables ») est donc en grande
partie déjà répondu par le moteur lui-même : **la question n'est plus « sur quel thread », mais
« sur combien de threads en parallèle, et avec quelle granularité de verrouillage »**.

`StructureCheck.checkStart` (vérifié) est **déjà mis en cache** par chunk
(`Long2ObjectMap<Object2IntMap<Structure>> loadedChunks`) — un chunk déjà vérifié pour une
structure donnée ne relit jamais le disque pour la même question. Le coût restant est
`tryLoadFromStorage`, qui déclenche une lecture NBT (`ChunkScanAccess`/`RegionFileStorage.scanChunk`)
**la première fois seulement**, pour les chunks pas encore chargés en mémoire — donc surtout un
coût d'E/S disque en périphérie de la carte explorée, pas un coût CPU répété.

### 7.2 Ce que font C2ME, Noisium et Moonrise — d'après recherche web, non vérifié sur code source

Je n'ai **pas** lu le code source de ces trois mods (contrainte de temps) ; ce qui suit vient de
recherche web (READMEs, changelogs) et doit être vérifié avant toute décision d'implémentation :

- **C2ME** (`RelativityMC/C2ME-neoforge`) : ne change pas la logique de génération elle-même —
  il augmente le degré de parallélisme du pipeline existant (génération + E/S + lumière sur plus
  de threads que le pool par défaut de vanilla) et ajoute des utilitaires de détection de
  concurrence non sûre (`CheckedThreadLocalRandom`) pour repérer les mods qui casseraient le
  parallélisme. Le projet documente lui-même que la non-déterminisme entre deux exécutions avec
  la même seed est un **bug vanilla connu** (MC-55596), pas introduit par C2ME.
- **Noisium** (`Steveplays28/noisium`) : optimise `NoiseBasedChunkGenerator` en écrivant
  directement dans le stockage en palette plutôt que via les méthodes de haut niveau de pose de
  bloc (évite des recalculs de mise à jour de voisinage inutiles pendant la génération), et met en
  cache des constantes (`horizontalCellBlockCount`/`verticalCellBlockCount`) recalculées à chaque
  appel dans vanilla. Revendique une parité 1:1 avec la génération vanilla.
- **Moonrise** (`Tuinity/Moonrise`, par l'auteur de Folia) : rétroporte le système de chunks de
  Paper (pas la simulation régionalisée complète de Folia) vers Fabric/NeoForge — amélioration du
  système de chargement/génération de chunks sans changer le comportement observable. C'est un
  changement d'infrastructure profond (remplace des structures internes du moteur de chunks), pas
  un ensemble de mixins ciblés — donc un risque de compatibilité plus élevé avec d'autres mods qui
  touchent aussi au système de chunks.

**Conclusion axe 7** : je ne peux pas, avec le temps disponible, désigner une cible précise
« classe + méthode » nouvelle et sûre sur cet axe — la génération est déjà largement asynchrone
dans le moteur actuel, et les trois mods cités attaquent le problème par des changements
d'infrastructure larges (pool de threads, remplacement du système de stockage), pas par des
micro-corrections isolées comme celles trouvées aux axes 2 à 5. **Ne pas se précipiter ici sans
lire le code de Noisium en détail** (c'est le plus proche, en esprit, de ce que fait Lanterne
ailleurs — des micro-corrections ciblées plutôt qu'un changement d'architecture).

---

## Classement — gain estimé / risque

| # | Cible | Axe | Gain estimé | Risque | Fait déjà par |
|---|---|---|---|---|---|
| 1 | `ServerExplosion.calculateExplodedPositions` : remplacer `level.getFluidState(pos)` par `block.getFluidState()` | 4 | **Très élevé** — supprime ~24 000 accès chunk/section redondants par explosion (≈ moitié du coût d'accès bloc de chaque TNT) | **Quasi nul** — résultat rigoureusement identique | Personne (vérifié : ni Lithium, ni aucun mod du pack) |
| 2 | Étaler `ItemEntity` : `(tickCount + getId()) % rate == 0` au lieu de `tickCount % rate == 0` | 2 | Moyen — aplati les pics de fusion synchronisés (explosions, fermes qui tuent en masse) | Très faible — retarde une fusion d'au plus `rate-1` ticks | Personne (ni vanilla, ni les mods étudiés) |
| 3 | Bucketing des random ticks par compteur de blocs tickables par section (façon Lithium) | 5 | Moyen-élevé sur mondes avec sections pauvres en blocs tickables (nether, désert, structures) | Faible — loi de probabilité inchangée, Lithium le classe hors « experimental » | Lithium (`random_block_ticking`, non expérimental) — pas dans Lanterne |
| 4 | Bucketing des items par type/taille de pile avant recherche de fusion (façon Lithium) | 2 | Élevé dans le cas dense (tas d'items non empilables au même point) | Modéré — Lithium le classe lui-même « experimental » | Lithium (`item_entity_merging`, expérimental) |
| 5 | Réordonnancement `Entity.collide` : blocs avant entités avant bordure du monde, paresse de `getEntityCollisions` | 1 | Moyen — réduit le travail sur mouvement en foule (pas la poussée, déjà traitée) | Faible — réordonnancement à résultat identique, en prod chez Lithium depuis des années | Lithium (`collisions.movement`, `collisions.intersection`) |
| 6 | BFS avec dédoublonnage pour `FlowingFluid.getSpread` (au lieu de la récursion non mémoïsée) | 3 | Moyen — surtout utile sur bassins/canaux à embranchements multiples ; vanilla a déjà un cache d'occlusion (`OCCLUSION_CACHE`) | Modéré — changement d'algorithme de recherche, à valider sur cas limites | Lithium (`block.fluid.flow`, non expérimental) |
| 7 | Cap dur sur le nombre d'items par type/monde (suppression au-delà d'un seuil) | 2 | Élevé en throughput mais **casse la production** au-delà du seuil | **Élevé** — perte silencieuse d'items, contraire à la philosophie « rien n'est jamais supprimé » | Adaptive Performance Tweaks — **à éviter** dans Lanterne |
| 8 | Offload de `SerializableChunkData.copyOf` (snapshot du chunk) hors du thread principal | 6 | Faible à moyen, non chiffré (dépend du nombre de bloc-entités par chunk) | Élevé — risque de vue incohérente / corruption de sauvegarde si mal fait | Personne — le reste du pipeline de sauvegarde est déjà asynchrone dans cette version |

**Non classé / pas de cible concrète identifiée avec confiance** : axe 7 (génération de chunks) —
déjà largement asynchrone dans le moteur, les mods de référence (C2ME/Noisium/Moonrise) attaquent
le problème par changement d'infrastructure plutôt que par micro-correction, et je n'ai pas lu leur
code source (recherche web uniquement, à vérifier). Axe 5 (`LevelTicks` lui-même) : structure déjà
bien conçue par Mojang, rien à corriger sans risque de casser l'ordre de dépilement dont dépendent
redstone et blocs à recharge.

## Corrections à l'hypothèse implicite du brief

- **Clumps ne touche pas les items au sol.** Vérifié sur le code source
  ([`MixinExperienceOrb.java`](https://github.com/Jaredlll08/Clumps/blob/26.2/common/src/main/java/com/blamejared/clumps/mixin/MixinExperienceOrb.java)) :
  Clumps fusionne des **orbes d'expérience** (`ExperienceOrb`), pas des `ItemEntity`. Le mod qui
  fusionne les items au sol dans le pack de référence est uniquement `getittogetherdrops`.
- **La sauvegarde de chunk n'est plus le goulot d'étranglement supposé.** Dans cette version, NBT
  et écriture disque sont déjà asynchrones ; le brief supposait un coût de compression/E-S
  synchrone qui n'existe plus sous cette forme.
- **`ChunkMap.saveAllChunks` existe bien**, nom exact confirmé par lecture de code — mais son rôle
  réel (flush complet au shutdown/`/save-all flush`) diffère du chemin périodique
  (`saveChunkIfNeeded`, déjà rationné à 20 chunks/tick et 128 écritures actives).
