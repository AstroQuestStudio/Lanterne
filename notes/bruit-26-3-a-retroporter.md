# Le bruit : ce que Mojang a fait en 26.3, et ce qu'on peut en prendre

> Revue de littérature close le 15 septembre 2026, à l'arrêt des chantiers.
> Trois de mes prémisses étaient fausses ; elles sont corrigées en tête.
> **[V]** = vérifié sur source primaire (bytecode, fichier `LICENSE`, dépôt cloné).
> **[S]** = inféré, motivé, non prouvé.

---

## Pourquoi cette note existe

Le banc CPS a établi que **64 % du temps de génération part dans le bruit** et 22,6 %
dans les biomes — 86,6 % à eux deux. L'objectif était de descendre à 30 %.

La recherche devait dire ce que font `swiftgen`, `zfastnoise` et `C2ME`. Elle a
trouvé bien mieux : **Mojang a réécrit tout le moteur de fonctions de densité en
26.3**, et a essentiellement adopté l'architecture de C2ME dans le jeu de base.
C'est la mine, et elle est en partie rétroportable en 26.2 **sans changer le monde
engendré**.

---

## TROIS CORRECTIONS À MES PRÉMISSES

### 1. `swiftgen` ne cible pas la 26.2, et n'optimise pas le bruit [V]

`gradle.properties` dit `minecraft_version=1.21.1`, `versionRange = "[1.21.1,1.22)"`.
Aucun tag, aucune branche autre que `main`. **Rien n'est portable tel quel.**

Et son mixin nommé `NoiseFillMixin` **n'optimise pas le bruit** : il regroupe les
mises à jour de carte de hauteur et chronomètre. Le nom trompe.

Il **ne touche pas** `Climate.RTree.search` : il mémoïse *au-dessus*, sur
`getNoiseBiome`, dans un cache direct-mapped de 4096 entrées par fil, indexé sur
les coordonnées quart. Sur un succès, il saute à la fois la pile climatique **et**
la recherche dans l'arbre — mais aucune ligne ne nomme `RTree`.

Licence MIT valide, mais le fichier `LICENSE` traîne un appendice copié d'un autre
mod (LittleTiles / Sable). Ne pas le citer tel quel.

### 2. `zfastnoise` ne réécrit pas Perlin, et ne conflicte ni avec Lithium ni avec C2ME [V]

Recherche sur tout le dépôt, noms Mojang **et** Yarn : **zéro occurrence** de
`ImprovedNoise`, `PerlinNoise`, `NormalNoise`, `PerlinNoiseSampler`… Leur thèse est
explicite : *« Fast noise optimizes **storing** block and biome data into chunks »*.
C'est du **stockage**, pas du calcul. Leur « cache d'états » est un cache de
*block states* de surface, pas d'états de bruit.

Incompatibilités réelles, lues dans leur `fabric.mod.json` :

| Mod | Statut réel |
|---|---|
| **Moonrise** | `breaks` — *« changes minecraft internals drastically »* |
| **Noisium** | incompatible — *« intended as a replacement »* |
| **Anti-Xray** | `breaks` |
| **C2ME** | **`recommends`**, avec un module d'intégration dédié |
| **Lithium** | **jamais mentionné** |

Je confondais probablement avec **Moonrise**.

L'issue #39 n'est **pas** une divergence de bruit : c'est un bug d'**origine
verticale** sur des dimensions dont le `dimension_type` et les `noise_settings`
déclarent des `min_y` différents. Les chunks sortent 64 blocs trop bas.
**Leçon pour nous : prendre l'origine Y de la même source que vanilla.**

La seule idée réutilisable de leur côté, et elle est bonne : hisser le
`ThreadLocal` de l'arbre climatique **hors de la boucle** — un `get()` par chunk au
lieu d'un par position. Chez eux c'est `perf.biomes.tree`, **éteint par défaut**.

### 3. Le « plan précalculé » de C2ME ne s'appelle pas ainsi [V]

Zéro occurrence de `plan` ou `precomputed`. Les vrais noms : module
**`c2me-opts-dfc`**, « Density Function Compiler », **AST immuable**, génération de
**bytecode par ASM**.

**Sa séparation forme / état est la leçon** :

| | Où | Durée de vie |
|---|---|---|
| **forme** (structure + code) | les *méthodes* de la classe engendrée | une par `NoiseConfig` |
| **état** (caches mutables) | les *champs* d'une instance | une par chunk |

Une seule classe engendrée pour tout le routeur. Le constructeur est le point de
re-liaison, et un chunk ne fait qu'une **ré-instanciation**, pas une recompilation.

Licence : **MIT pour `c2me-opts-dfc`** [V], malgré le `NOASSERTION` affiché par
GitHub — c'est le module OpenCL voisin qui est en tous droits réservés et fait
mentir l'étiquette. ⚠️ Sur la branche `dev/26.3.0`, **le module est commenté dans
`settings.gradle` et ne compile pas**. Lire `dev/26.2.0`, ou `dev/1.21.11` pour une
version plus simple.

---

## CE QUE MOJANG A FAIT EN 26.3 — bien plus que la simple précision [V]

### Le piège des noms

La 26.x est livrée **désobfusquée**, mais Yarn continue de renommer. Les sources
qui parlent de `PooledSampleBuffer` ou `ContextualSamplerProvider` emploient des
noms **Yarn** ; en NeoForge, ce sont d'autres noms.

| Yarn 26.3 | **Mojang 26.3** |
|---|---|
| `PooledSampleBuffer` | **`ScopedDensityBuffer`** |
| `ContextualSamplerProvider` | **`DensitySamplerSet`** |
| `Sampler.Contextual` | **`DensitySampler.Bound`** |
| `SamplingRegion` | **`DensityVolume`** |
| `ChunkNoiseSampler` | **`NoiseChunk`** |
| `.getFilledBuffer(volume)` | `.sampleVolume(volume)` |

Tout dans le paquet neuf `net.minecraft.world.level.levelgen.densityfunction`.

### Les sept changements, par ordre d'intérêt pour nous

1. **Le `HashMap wrapped` de `NoiseChunk` est supprimé.** La classe passe de ~30
   champs mutables et une douzaine de méthodes d'interpolation à **cinq champs et
   cinq méthodes**. Le décalque devient une compilation **unique** portée par
   `RandomState`, partagée entre tous les chunks et tous les fils.
2. **Un compilateur d'arbre** : `DensityFunctionCompiler`, un
   `ConcurrentHashMap<DensityFunction, DensitySampler>` dans `RandomState`.
   L'arbre immuable est réécrit, optimisé, puis compilé en petits `record`
   **spécialisés** — `ConstAddSampler`, `ConstMulSampler`, `ShiftedXzSampler`…
3. **L'objet de contexte disparaît.** `FunctionContext` et `ContextProvider` sont
   supprimés : les coordonnées passent en **`int` nus**. Plus d'allocation par
   position, plus d'appel mégamorphique.
4. **Les caches sont réorganisés.** `cache_2d`, `cache_all_in_cell` et `flat_cache`
   **supprimés** au profit d'un mécanisme unique, dont le stockage est un **tableau
   indexé par un entier attribué à la compilation** — plus une table de hachage.
5. **Un optimiseur de dimensionnalité** : `domainAxes()` rend un masque de bits
   (X=1, Y=2, Z=4), et une règle de réécriture évite de réévaluer par Y un
   sous-arbre qui n'en dépend pas. C'est ce qui a **remplacé** `flat_cache`.
6. **L'ordre d'itération change** : `z → x → y`, Y variant le plus vite, avec
   **un seul** échantillonnage sur tout le volume du chunk au lieu d'une boucle à
   cinq niveaux avec interpolation cellule par cellule.
7. **Un pooling à deux niveaux** : `RandomState` prête des pools (16 au plus,
   éviction à 20 ticks), et chaque pool a des listes libres par tranche de taille.
   `ScopedDensityBuffer` est `AutoCloseable`, employé en *try-with-resources*.

Et le noyau lui-même a bien été réécrit : `ImprovedNoise` **supprimé** au profit de
`GradientNoise` + `PerlinNoise`, avec un nouveau `SmearedPerlinNoise`.

---

## CE QUI EST RÉTROPORTABLE EN 26.2 SANS CHANGER LE MONDE

Le « quoi » est vérifié au bytecode ; le classement parité est une **inférence
motivée [S]** et doit être éprouvé par une comparaison de blocs, pas supposé.

### À ne PAS rétroporter — change le terrain engendré
- **La simple précision elle-même.**
- La suppression de `flat_cache` / `cache_2d` / `cache_all_in_cell` : leur cache
  avait des effets **sémantiques**, et les corriger change la sortie.
- Le nouveau format de bruit des paquets de données.

### Le gisement — structurellement neutre
1. **Compiler une fois, partager toujours.** Hisser le décalque par chunk vers un
   `ConcurrentHashMap` porté par `RandomState`. Mémoïsation de *structure*, effet
   numérique **nul**. ← *le module `calque` a déjà mordu là, sans atteindre le seuil*
2. **L'objet de contexte → trois `int`.** Supprime l'allocation par position.
3. **Le créneau de cache : un entier dans un tableau**, pas une table de hachage.
4. **Le pooling de tableaux** par tranche de taille, avec portée fermante. En
   `double[]`, c'est **bit-exact**.
5. **La spécialisation sur opérande constant.** En `double`, `a + const` est
   bit-identique au chemin général — parité gratuite. *(En `float`, non.)*
6. **Le masque d'axes**, restreint aux sous-arbres qui ne dépendent que de X et Z —
   c'est exactement ce que `flat_cache` faisait déjà.
7. **L'échantillonnage par volume** au lieu d'un appel par bloc : réordonnancement
   de boucle et réutilisation de tampon, arithmétique par point **inchangée**.
8. **Hors Mojang** : hisser le `ThreadLocal` de l'arbre climatique hors de la
   boucle des biomes. Un `get()` par chunk au lieu d'un par position.

---

## CE QUI N'A PAS ÉTÉ TROUVÉ, et qu'il ne faut pas inventer
- Aucun chiffre de performance officiel de Mojang sur la 26.3.
- Aucun changelog de mod réagissant à cette réécriture : **la 26.3 est sortie le
  jour même de cette revue**.
- Aucune page publique de `swiftgen` : le « bit-identique » vient de son dépôt.

---

## OÙ REPRENDRE

Les points 1 à 7 ci-dessus, **dans cet ordre**, chacun mesuré séparément contre le
banc `lab/Quarry` (`notes/cps-generation.md`), et chacun accompagné d'une
comparaison de blocs — pas d'un échantillonnage.

Et la règle qui prime sur le gain : **un terrain engendré plus vite mais
différemment est un monde cassé.** Les structures ne tomberaient plus où les cartes
au trésor les annoncent.
