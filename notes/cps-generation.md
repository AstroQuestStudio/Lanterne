# Le débit de génération de chunks — mesure, et où le temps part vraiment

> Mesures du **15 septembre 2026**, Minecraft 26.2 / NeoForge 26.2.0.88.
> Machine de mesure : Ryzen 7 5800H, **seize processeurs annoncés** à la machine virtuelle.
> Cible de production : **Minestrator MyBox Free — Xeon Gold 6230R, un cœur dédié, 4 Go**.
> Le Xeon tourne à 2,1 GHz de base : **par cœur il est plus lent que la machine de mesure**. Tout
> chiffre de cette note est donc optimiste là-bas, et c'est le régime **mono-fil** qui décide.

## 1. L'instrument

Le banc est `lab/Quarry.java` (`LANTERNE_QUARRY=1`), qui existait déjà pour comparer génération et
chargement. Il rend désormais un **débit en chunks par seconde**, avec les quatre conditions sans
lesquelles ce chiffre ne veut rien dire — voir `Quarry.cps()` :

1. **Le refus.** Un chunk « généré » en moins de 0,5 ms n'a pas été généré : il existait. Au-delà
   d'un sur dix, **aucun débit n'est publié**. Ce banc a déjà annoncé 209 chunks/s au lieu de 13,7
   pour cette raison exacte — voir `lab/Swarm.java`, qui porte le récit.
2. **Le nombre de fils**, relevé et affiché. `LANTERNE_THREADS=1` force le cas de la MyBox.
3. **Les octets alloués par chunk**, tous fils confondus (`getTotalThreadAllocatedBytes`, et non le
   compteur du fil courant : la génération tourne sur le pool de travail, où un compteur de fil
   courant rendrait presque zéro tout en ayant l'air de fonctionner).
4. **La ventilation par étape**, figée à la fin de la génération appariée et non au rapport — ce qui
   suit est de la lecture disque et n'a rien à faire dans un bilan de génération.

Protocole : **une seule grille** de 8×8 chunks vierges, première rangée jetée, mod allumé un chunk
sur deux, **parité inversée à la moitié** pour que le biais d'ordre tombe autant d'un côté que de
l'autre. Médiane de 28 mesures par bras.

```
./gradlew runServer -Pbanc=cps          # avec LANTERNE_QUARRY=1
LANTERNE_THREADS=1                      # le cas MyBox
LANTERNE_PROFILE_ALL=1                  # échantillonne les fils de travail, pas seulement le serveur
```

⚠ **Effacer `run-cps/world` entre deux exécutions.** Sans cela la seconde relit le disque et le banc
refuse — ce qui est le bon comportement, mais fait perdre une exécution.

## 2. Le débit de référence

| Fils de travail | Vanilla | Lanterne | Mémoire allouée |
|---|---:|---:|---:|
| 15 (défaut sur 16 processeurs) | 32,2 chunk/s | 32,6 chunk/s | 17,98 Mo/chunk |
| **1 (`LANTERNE_THREADS=1`)** | **26,4 chunk/s** | **25,5 chunk/s** | 18,48 Mo/chunk |

**Quinze fils ne rendent que 22 % de plus qu'un seul.** C'est le résultat le plus important de cette
note et il confirme ce que `lab/Swarm` avait établi autrement : dans le régime « un chunk demandé,
attendu, puis le suivant » — celui d'un joueur qui explore — la génération **ne se parallélise
presque pas**, parce qu'un chunk dépend de ses voisins et qu'il n'y a jamais assez de travail
indépendant en vol. C2ME et les mods de parallélisme n'ont donc rien à donner ici, et **rien du tout**
sur la MyBox.

Corollaire pour l'objectif ×10 : il ne viendra pas du parallélisme. Il devrait venir du travail
algorithmique, sur une charge où **87 % du temps est du bruit de Perlin**.

**Dix-huit mégaoctets alloués par chunk** est l'autre chiffre à retenir. Sur 4 Go et un cœur, le
ramasse-miettes ne travaille pas *à côté* du tick : il le lui prend. Une pré-génération de 100 000
chunks alloue 1,8 To — que le ramasseur devra faire passer.

## 3. Où le temps part, étape par étape

Relevé de `core/Forge` (le chronomètre par étape, branché sur `ChunkStep.apply`), sur la même grille
de 64 chunks :

| Étape | 15 fils | **1 fil** | ms par passage (1 fil) |
|---|---:|---:|---:|
| `minecraft:noise` | 60,4 % | **64,0 %** | 96,3 |
| `minecraft:biomes` | 14,3 % | **22,6 %** | 25,1 |
| `minecraft:surface` | 8,2 % | 4,3 % | 6,5 |
| `minecraft:features` | 6,3 % | 3,0 % | 6,5 |
| `minecraft:structure_starts` | 3,9 % | 2,0 % | 0,49 |
| `minecraft:carvers` | 2,7 % | 1,5 % | 2,2 |
| `minecraft:light` | 2,2 % | 1,2 % | 4,1 |
| `minecraft:initialize_light` | 1,4 % | 1,1 % | 2,3 |

**Sur un seul fil, `noise` et `biomes` font 86,6 % à elles deux.** Tout le reste — surface, grottes,
décorations, lumière, structures — pèse 13 %. Un module qui rendrait `features` *gratuit* gagnerait
3 %.

## 4. Où le temps part, méthode par méthode

`LANTERNE_PROFILE_ALL=1`, échantillonnage des **fils de travail** et non du fil du serveur (qui dort
à 89 %), un seul fil de génération :

```
12,3 %  ImprovedNoise.noise                     ← le noyau de Perlin
 4,2 %  Thread.setNativeName                    ← ARTEFACT, voir plus bas
 4,2 %  Climate$RTree$SubTree.search            ← la recherche de biome
 4,0 %  DensityFunctions$PureTransformer.compute
 3,9 %  SurfaceRules$LazyCondition.test
 3,5 %  Aquifer$NoiseBasedAquifer.computeSubstance
 3,2 %  NoiseBasedChunkGenerator.doFill
 3,1 %  NoiseChunk.lambda$new$0
 3,0 %  NoiseChunk.updateForZ
 2,7 %  Objects.hashCode                        ← ??? voir §5
 2,5 %  SurfaceSystem.buildSurface
 2,3 %  LevelChunkSection.setBlockState
 1,8 %  DensityFunctions$Ap2.compute
 1,3 %  DensityFunctions$Ap2.fillArray
 1,2 %  PalettedContainer$Data.copyFrom
 1,1 %  TypedInstance.is
 1,0 %  DensityFunctions$MarkerOrMarked.mapChildren   ← ??? voir §5
```

### `Thread.setNativeName` à 4,2 % est un artefact du banc, pas un gisement

`Util.runNamed` et `TracingExecutor.forName` renomment le fil autour de chaque tâche — **mais
seulement si `SharedConstants.IS_RUNNING_IN_IDE`** (`Util.java` ligne 327, `TracingExecutor.java`
ligne 10). C'est vrai sous `runServer`, faux sur un serveur de production. Ce coût **n'existe pas
chez l'utilisateur**, et les jolis noms de fils du relevé (`wgen_fill_noise`, `init_biomes`) non plus.

Conséquence à retenir : **les chiffres de cette note sont pessimistes d'environ 4 %** par rapport à
une production. Et il n'y a rien à optimiser là.

## 5. Ce que `Objects.hashCode` cachait — le calque

`Objects.hashCode` à 2,7 % d'un profil de génération de terrain n'a aucune raison d'exister.
`mapChildren` non plus : c'est une méthode de *construction* d'arbre.

La cause tient en une ligne, `NoiseChunk.java` ligne 374 :

```java
private final Map<DensityFunction, DensityFunction> wrapped = new HashMap<>();

protected DensityFunction wrap(DensityFunction function) {
    return this.wrapped.computeIfAbsent(function, this::wrapNew);
}
```

Les fonctions de densité sont des **records**. Leur `hashCode()` est donc structurel et **récursif** :
hacher un nœud parcourt tout son sous-arbre, et rien n'est mémorisé. `mapAll` visite chaque nœud et
appelle `wrap` sur chacun. Le coût d'un décalque complet est de l'ordre de la **somme des tailles de
tous les sous-arbres** — quadratique en la taille de l'arbre, pour un routeur de surmonde qui compte
plusieurs centaines de nœuds.

Et ce décalque est refait **huit fois par chunk** : deux dans le constructeur de `NoiseChunk`
(lignes 142 et 158), **six de plus** dans `cachedClimateSampler` (ligne 169), appelé par
`NoiseBasedChunkGenerator.doCreateBiomes` pour l'étape des biomes.

**Ces six-là sont déjà calculées.** Le `wrappedRouter` de la ligne 142 contient exactement
`temperature`, `vegetation`, `continents`, `erosion`, `depth` et `ridges` décalquées par le même
`wrap` — `NoiseRouter.mapAll` mappe ses quinze champs (`NoiseRouter.java` lignes 49-67). Le
constructeur les jette.

Le module **`calque`** (`core/Calque.java`, `mixin/NoiseCalqueMixin.java`) les garde.

### Pourquoi c'est identique, et non « équivalent »

Décalquer deux fois le même arbre avec le même `wrap` rend **les mêmes objets**, et la raison est le
mémo lui-même. En remontant depuis les feuilles : une feuille passe par `wrap`, le mémo rend la même
enveloppe la seconde fois. Un nœud interne est reconstruit — `mapChildren` alloue un record neuf —
mais ce record neuf est **structurellement égal** à celui du premier passage, puisque ses enfants
sont les mêmes objets ; `computeIfAbsent` le reconnaît et rend l'enveloppe déjà créée.

Ce n'est pas un raisonnement qu'on croit sur parole : `LANTERNE_CALQUE_AUDIT=1` refait le décalque de
vanilla et compare les **références** de ce qui est réellement rendu.

```
CONTRÔLE : 2166 fonction(s) comparées à ce que vanilla aurait construit — AUCUN écart,
et ce sont LES MÊMES OBJETS, donc aucun écart n'est possible en aucun point
```

Comparer des références plutôt que des valeurs change la nature de la preuve : un échantillon de
températures dirait « je n'ai pas trouvé d'écart » ; l'identité dit qu'**aucun écart n'est possible**,
pour aucune graine, dans aucune dimension.

Et le contrôle **peut échouer** : `LANTERNE_BREAK_CALQUE=1` échange température et végétation.

```
CONTRÔLE : 722 fonction(s) sur 2166 N'ÉTAIENT PAS celles de vanilla — ne pas publier ce module
```

722 = deux fonctions sur six, sur 361 échantillonneurs. Exactement ce qui était cassé.

### Ce que ça vaut, et c'est peu

Quatre exécutions mono-fil, module seul (`LANTERNE_MODULES=calque`), grille appariée :

```
×1,07   ×1,07   ×1,00   ×1,03
```

Les deux basses ont été prises pendant qu'un **autre serveur tournait sur la machine** (débit absolu
tombé de 31 à 16 chunk/s dans la même exécution) : sous contention, ce banc ne mesure plus le module.
Les deux hautes sont propres.

**Verdict honnête : ×1,07 au mieux, sous le seuil de dérive de ×1,08 du laboratoire. NON PROUVÉ
comme gain de vitesse.** Ce qui est prouvé, c'est l'exactitude et le travail supprimé : 2 166
décalques d'arbre évités pour 361 chunks, soit six par chunk. Le module est gardé pour la même raison
que `memoire_conditions` : il ne coûte rien, il va dans le bon sens, et il n'a pas de chiffre.

### Ce qui reste sur la table, et c'est le gros morceau

Le calque supprime **six décalques sur huit**. Les deux du constructeur sont refaits à chaque chunk
parce que les enveloppes qu'ils fabriquent portent des caches propres au chunk : un
`NoiseInterpolator`, un `FlatCache`, un `CacheAllInCell` ne se partagent pas d'un chunk à l'autre.

Les supprimer demande de séparer la **forme** de l'arbre — identique pour tous les chunks d'un même
monde — de l'**état** des enveloppes, et d'instancier l'état depuis un plan précalculé, sans table de
hachage ni `hashCode` récursif. C'est le chantier qu'a mené C2ME. Il se compte en centaines de lignes
et il n'a pas été fait ici. Si le décalque pèse ce que le profil suggère (`Objects.hashCode` 2,7 % +
`lambda$new$0` 3,1 % + `mapChildren` 1,0 % + `TypedInstance.is` 1,1 % ≈ 8 %), c'est **le gisement le
plus net de toute cette note** — et le seul qui ne demande pas de toucher aux mathématiques.

## 6. Ce qui a été lu et écarté

`noisiumforked` (**LGPL-3.0-or-later**, lu dans le `LICENSE`) et `zfastnoise` (**MPL-2.0**, idem) ont
été dépouillés mixin par mixin.

| Technique | Verdict pour la 26.2 |
|---|---|
| **Noisium** — écriture directe dans la palette au lieu de `LevelChunkSection.setBlockState` | **Portable, mais seulement dans sa forme « compat Lithium »**, qui appelle `recalcBlockCounts()` en fin de section. Sa forme principale tient les compteurs à la main, **oublie `fluidCount`** (sérialisé au client, `LevelChunkSection.java` ligne 180) et **sur-compte `tickingFluidCount`**. Sa parité « 1:1 » annoncée au README est donc fausse sur ce chemin en 26.2. |
| **Noisium** — ordre `y→z→x` dans `fillBiomesFromNoise` | **Portable et bit-identique.** Chaque cellule est écrite une fois, seul l'ordre change. Petit gain, sans risque. Non fait ici, faute de l'avoir mesuré. |
| **Noisium** — `MaterialRuleList.calculate` en boucle indexée | **Sans objet.** `javac` compile déjà le `for-each` sur tableau en boucle indexée ; leur version relit le champ à chaque tour. |
| **Noisium** — `getSections()` au lieu de `getSection(i)` | **Divergence réelle** : `ImposterProtoChunk` redéfinit les deux différemment (lignes 58 et 89). À ne pas reprendre tel quel. |
| **FastNoise** — `populateNoise` en échantillonnage par volume | **Non portable.** Repose sur `PooledSampleBuffer` / `ContextualSamplerProvider`, qui sont de la **26.3** et n'existent pas en 26.2 (`grep` sur `C:/mc262src` : aucune occurrence). La 26.2 n'a que `DensityFunction.fillArray`. |
| **FastNoise** — `zmatcomp`, compilation des règles de surface en `MethodHandle` | **Non portable.** Vise `MaterialRule#getBlockStateRule` / `MaterialRuleContext`, formes de la 26.3. La 26.2 a `SurfaceRules.RuleSource extends Function<Context, SurfaceRule>`. |
| **FastNoise** — paliers biomes (biome fixe, End) | Idées saines, mais bâties sur `BiomeSupplier` / `getBiomeSupplier`, **qui n'existent pas en 26.2** (`BiomeSource implements BiomeResolver` directement). À réécrire, pas à reprendre. |
| **C2ME** — parallélisme | **Sans objet sur la cible.** Voir §2 : quinze fils ne rendent que 22 %. |

**Et le point qui compte le plus** : ni Noisium ni FastNoise ne touchent à `Climate$RTree.search` ni à
`MultiNoiseBiomeSource.getNoiseBiome`, c'est-à-dire à la boucle chaude de l'étape des biomes — 22,6 %
du temps sur un fil. FastNoise déclare l'intention (`OPTIMIZE_BIOME_TREE`, éteint par défaut) mais ne
l'implémente pas. **Ce terrain est vierge.**

## 7. Sur l'objectif ×10 — ce que la mesure permet de dire

Elle ne permet pas de le promettre, et il faut le dire clairement.

- Le parallélisme est hors jeu : **×1,22 entre un fil et quinze**, et zéro sur la MyBox.
- 86,6 % du temps est dans `noise` + `biomes`, et l'essentiel de ces deux-là est
  `ImprovedNoise.noise` — **le bruit de Perlin *est* le terrain**. Il n'y a pas de travail inutile à
  supprimer là ; il y a du calcul dont dépend chaque bloc.
- Le seul court-circuit crédible sur `noise` a déjà été chiffré par ce dépôt : 31,1 % des appels à
  `MaterialRuleList.calculate` ne produisent que de l'air, donc un court-circuit **parfait**
  rapporterait au mieux 0,31 × 0,64 ≈ **20 %**. Réel, et ce n'est pas un facteur.
- La 26.3 fait passer les fonctions de densité en **simple précision**. C'est l'optimisation que
  Mojang a faite lui-même, et elle **change le monde engendré** : rétroportée, elle casserait la
  compatibilité des cartes existantes. Ce n'est pas un ×10 non plus.

**Le seul facteur multiplicatif établi sur ce domaine reste la pré-génération**, mesurée à ×15 par
`lab/Swarm` : servir un chunk fabriqué d'avance coûte quinze fois moins cher que le fabriquer devant
le joueur (et ×228 dans la mesure mono-fil du 15/09, chiffre que le banc a lui-même rejeté comme
non crédible côté protocole — voir le verdict « chargement » de `Quarry`). Ce n'est pas un contournement
de la question : c'est la réponse. Sur un cœur unique, **le chunk le plus rapide est celui qu'on n'a
pas à fabriquer**.

Ce qui reste à tenter, dans l'ordre du gisement probable, et à mesurer avant de croire quoi que ce
soit :

1. **Le plan de décalque précalculé** (§5, fin) — ~8 %, sans toucher aux mathématiques, exactitude
   démontrable par identité de références comme le fait déjà `LANTERNE_CALQUE_AUDIT`.
2. **`Climate.RTree.search`** — 4,2 % du profil, 22,6 % de l'étape, et **personne ne l'a regardé**.
3. **L'ordre `y→z→x` de `fillBiomesFromNoise`** (Noisium, bit-identique) — petit, gratuit, sûr.
4. **Le chemin d'écriture de `doFill`** (Noisium, forme « compat Lithium » uniquement) — 2,3 % vu au
   profil sur `LevelChunkSection.setBlockState`.
