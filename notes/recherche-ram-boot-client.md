# Recherche RAM / démarrage / client — Lanterne (NeoForge 26.1.2)

Rapport de recherche pur (aucun fichier de `src/` touché). Contexte mesuré :

```
Vanilla nu          193,0 ms/tick   26,9 Go RAM
Modpack (17 mods)    23,8 ms         3,7 Go
Lanterne seul        24,4 ms         2,7 Go
Modpack + Lanterne   11,4 ms         0,8 Go
```

## Méthode

Deux sources ont été croisées :

1. **Recherche web** (GitHub, wikis, changelogs) sur FerriteCore, ModernFix, YAMO, Moonrise, etc. — fiable pour la logique générale, mais datée : la plupart des pages parlent de MC 1.16–1.21.x, pas de 26.1.2.
2. **Lecture directe du code source décompilé de Minecraft 26.1.2** livré avec le projet : `build/moddev/artifacts/minecraft-patched-26.1.2.97-sources.jar`. C'est la seule source qui fait foi pour "est-ce que telle classe existe encore, sous quel nom, avec quels champs". Les classes citées ci-dessous ont toutes été extraites et lues (pas devinées) depuis ce jar, sauf mention contraire explicite.

Ce croisement a produit une découverte qui change une bonne partie de l'axe RAM : **une partie des optimisations vedettes de FerriteCore semble déjà intégrée dans le vanilla 26.1.2**. Détail au §1.1.

---

## AXE 1 — RAM

### 1.1 BlockState : la table de voisins et la map de propriétés — probablement déjà corrigées en vanilla

FerriteCore (`summary.md`, points 2 et 3) documente deux gains historiques :

| Point | Mécanisme vanilla visé | Gain annoncé | Côté |
|---|---|---|---|
| `fastmap` | `Table<Property<?>, Comparable<?>, S>` par état pour `StateHolder#with` | ~600 Mo | client + serveur |
| `nopropertymap` (aucun sous-package mixin, fait partie du code fastmap) | `ImmutableMap<Property<?>, Comparable<?>>` par état pour stocker les propriétés | ~170 Mo | client + serveur |

J'ai lu `net/minecraft/world/level/block/state/StateHolder.java` et `StateDefinition.java` dans le jar 26.1.2. Constat direct :

```java
// StateHolder.java (26.1.2)
private final Property<?>[] propertyKeys;
private final Comparable<?>[] propertyValues;
private S[][] neighbors;
```

Ce ne sont **plus** un `ImmutableMap` et une `Table` : ce sont déjà des tableaux primitifs, indexés par position (`valueIndex(property)` fait une recherche linéaire dans `propertyKeys`, et `setValueInternal` indexe directement `this.neighbors[propertyIndex][valueIndex]`). C'est structurellement la même idée que le "FastMap" de FerriteCore (un tableau multi-dimensionnel à la place d'une table de hachage générique). `StateDefinition.java` confirme que ces tableaux sont construits une fois à l'enregistrement du bloc (`createMultiPropertyStates` → `StateCollection.fillNeighborsForState`), avec même un cache de calcul (`statesByPivotCache`) pour éviter de refaire le produit cartésien à chaque état.

**Conséquence pratique :** sur 26.1.2, les mixins `ferritecore.fastmap.*` de FerriteCore visent des champs qui n'existent plus sous leur forme "table/map" — soit ils ne s'appliquent plus du tout (mixin qui échoue silencieusement ou avec erreur selon la stricte des points d'injection), soit ils sont devenus inutiles. Dans les deux cas, **il ne faut pas s'attendre à ce que FerriteCore économise encore 600+170 Mo sur cette version** : ce chiffre vient de mesures sur 1.16–1.18. Je n'ai pas de changelog Mojang qui date précisément quand ce changement a eu lieu (je ne l'ai pas trouvé), mais la preuve est dans le code livré avec le projet, ce qui est plus fiable qu'un changelog.

**Ce que ça veut dire pour Lanterne :** ne pas réimplémenter le FastMap — c'est déjà fait par Mojang. Vérifier (banc à l'appui) si FerriteCore, une fois installé sur ce modpack, log une erreur Mixin au démarrage à propos de `StateHolder` ; si oui, c'est la confirmation, et FerriteCore ne doit alors son éventuel gain mesuré qu'à ses **autres** modules (voir 1.2).

Risque de cette conclusion : **moyen**. Elle repose sur la lecture d'un seul jar (une version). Elle n'a pas été vérifiée par une mesure heap réelle avec/sans FerriteCore sur 26.1.2. À confirmer par un banc dédié avant d'en faire une certitude absolue.

### 1.2 Ce qui, dans FerriteCore, reste pertinent — et seulement côté client

Les 3 autres points du `summary.md` de FerriteCore sont explicitement marqués **"Side: Client"** :

| Point | Mécanisme | Gain annoncé | Mixin subpackage |
|---|---|---|---|
| Multipart model predicate caching | Cache des prédicats bloc/valeur + listes triées par hash | 300–400 Mo | `predicates` |
| String instance reduction (`ModelResourceLocation`) | Dédup de la chaîne `variant` entre MRL, évite RL→String→split | ~300 Mo | `mrl` |
| Multipart model instances | Une seule instance de modèle multipart partagée par liste de prédicats identique | ~200 Mo | `dedupmultipart` |

J'ai vérifié dans le jar 26.1.2 que le système de modèles a été **refactoré** en profondeur : les classes concernées ne sont plus dans `net.minecraft.client.resources.model` mais dans `net.minecraft.client.renderer.block.dispatch.multipart` (`KeyValueCondition.java`, `MultiPartModel.java`, `Selector.java`). J'ai lu `KeyValueCondition.java` : **aucune trace de cache ou de déduplication** dans cette classe. Donc ce point précis (prédicats multipart) n'est très probablement *pas* corrigé par vanilla — mais je n'ai pas eu le temps de vérifier `MultiPartModel.java` en détail ni de comparer avec l'implémentation FerriteCore réelle (le mod n'est pas dans ce dépôt).

**Point capital pour votre scénario : ces trois gains sont côté CLIENT (chargement de modèles de blocs). Un serveur dédié ne charge jamais de modèles.** Sur le banc mesuré (serveur dédié), ces 300+300+200 ≈ 800 Mo ne s'appliquent tout simplement pas. Ils ne comptent que si vous mesurez aussi le client.

**Conclusion sur FerriteCore pour ce projet :** sur un serveur dédié 26.1.2, la quasi-totalité du bénéfice RAM historique de FerriteCore semble soit obsolète (fastmap), soit hors-sujet (modèles client). C'est une explication plausible et vérifiable au fait que Lanterne seul n'arrive pas au niveau RAM du modpack : **FerriteCore n'est probablement pas la pièce qui manque**. Il faut chercher ailleurs (ModernFix, DFU, structures, JAR en mémoire — sections suivantes).

### 1.3 PalettedContainer / BitStorage / LevelChunkSection — coût réel par chunk

Lu intégralement : `PalettedContainer.java`, `LevelChunkSection.java`, `SimpleBitStorage.java`, `ZeroBitStorage.java`, `Strategy.java`, `Configuration.java` (tous dans `net.minecraft.world.level.chunk`).

**Formule exacte (vérifiée dans `Strategy.createForBlockStates`) :**

- Une section de blocs = 4096 entrées (`bitsPerAxis=4` → `1<<12`).
- Paliers de bits en mémoire : `0` → palette à une valeur (0 octet de stockage) ; `1,2,3,4` → **toujours 4 bits** (palette "linéaire", `LinearPalette`) ; `5,6,7,8` → palette `HashMapPalette` à 5/6/7/8 bits ; au‑delà de 8 → **palette globale directe**, avec `bitsInMemory = ceil(log2(taille du registre global de BlockState))`. Sur un modpack copieux (dizaines de milliers d'états), ça tourne autour de 15–17 bits.
- Une section de biomes = 64 entrées (`bitsPerAxis=2`). Paliers : `0`, `1,2,3` (linéaire), au‑delà → globale (`ceil(log2(nb biomes))`, souvent 6–8 bits selon le nombre de mods de biomes).

**Taille du tableau `long[]` de stockage :** `SimpleBitStorage` calcule `requiredLength = ceil(size / valuesPerLong)` avec `valuesPerLong = 64/bits`. Donc :

- Section homogène (air, pierre massive...) : `ZeroBitStorage`, tableau **`long[0]` partagé** (`ZeroBitStorage.RAW` est un `static final long[0]` unique) — coût quasi nul, déjà vanilla, rien à gratter ici.
- Section à 2–16 états distincts (le cas le plus courant en surface) : 4 bits → `4096*4/64 = 256 longs = 2048 octets`.
- Section à 17–256 états (zones très hétérogènes, redstone dense, bases très construites) : 8 bits → `4096*8/64 = 512 longs = 4096 octets`.
- Section en palette globale (very modded area, >256 états distincts dans 16³ blocs — rare mais arrive avec beaucoup de mods de blocs) : ~15 bits → `4096*15/64 ≈ 960 longs = 7680 octets`.

Pour un monde de hauteur 384 (24 sections) avec, disons, 10 sections non vides en moyenne à 4 bits + 14 sections vides (ZeroBitStorage) : **~10 × 2048 = 20 Ko de stockage de blocs par chunk**, plus les biomes (presque toujours `ZeroBitStorage` ou linéaire à 1–2 bits, donc négligeable), plus les deux calques de lumière (`BlockLightSectionStorage`/`SkyLightSectionStorage`, ~2048 octets par section allouée en nibbles quand la section n'est pas homogène), plus les en-têtes (`nonEmptyBlockCount` etc., quelques octets par section). **Ordre de grandeur réaliste : 20–60 Ko/chunk pour la partie voxel**, hors entités et hors block entities. C'est cohérent avec les fourchettes de "50–100 Ko par chunk vide, plusieurs Mo pour un chunk avec beaucoup de tile entities" trouvées dans la littérature généraliste (non vérifiée par le code, à prendre comme ordre de grandeur).

**Ce qui est déjà optimal (rien à gagner) :**
- `ZeroBitStorage` pour palette à une valeur : déjà zéro octet, déjà partagé statiquement. FerriteCore/Lanterne n'ont rien à faire ici.
- Pas de "palette à 1 entrée non détectée" comme bug ouvert — c'est le comportement normal du code, pas une fuite.

**Ce qui reste un vrai gisement, non couvert par vanilla :**
- **Le seuil des paliers de bits est fixe (4, 5, 6, 7, 8, puis direct).** Une section avec 9 états distincts saute directement à la palette globale à ~15 bits au lieu de rester sur une palette locale à 9 bits (`HashMapPalette` s'arrête à 8 bits dans `Strategy`). C'est un choix délibéré de Mojang (complexité du code), mais avec beaucoup de mods de blocs, des sections à 9–20 états distincts sont courantes en zone habitée/industrielle → elles payent le prix fort (15 bits au lieu de 5) alors qu'elles pourraient tenir sur 5 bits. **Piste concrète, pas trouvée ailleurs dans l'écosystème mod actuel** : un mixin sur `Strategy.createForBlockStates` qui ajoute des paliers `HashMapPalette` à 9–14 bits avant de basculer en globale. Gain estimé difficile à chiffrer sans profilage réel (dépend de la densité de construction), risque **moyen** (touche un chemin très chaud, currently pas fait par personne à ma connaissance — donc pas de retour d'expérience communautaire pour rassurer sur la stabilité).

### 1.4 SectionStorage / PoiManager — la fuite de villages est déjà corrigée par NeoForge

`SectionStorage.java` (26.1.2) contient une méthode explicitement commentée :

```java
/**
 * Neo: Removes the data for the given chunk position.
 * See PR #937
 */
public void remove(ChunkPos chunkPos) {
    synchronized (this.loadLock) {
        for (int y = this.levelHeightAccessor.getMinSectionY(); y <= this.levelHeightAccessor.getMaxSectionY(); y++) {
            this.storage.remove(getKey(chunkPos, y));
        }
        this.loadedChunks.remove(chunkPos.pack());
    }
}
```

C'est exactement la fuite mémoire documentée par la recherche web ("`SectionStorage.storage` ne retire jamais les POI d'un chunk déchargé") — **déjà patchée dans ce build de NeoForge** (marquée `Neo:`, donc un patch NeoForge par-dessus le vanilla, pas du vanilla lui-même). `PoiManager extends SectionStorage` et hérite donc de ce correctif. **Rien à faire ici pour Lanterne** ; vérifier seulement (par un test rapide en jeu, pas dans ce rapport) que `remove()` est bien appelé au déchargement de chunk — je n'ai pas retracé l'appelant exact (probablement `ServerChunkCache`/`ChunkMap`, hors budget de cette recherche).

### 1.5 VoxelShape / cache de collision — déjà en vanilla, sous un autre nom que prévu

`Shapes.java` ne contient **aucun** cache — la mission supposait un cache dans `Shapes`, ce n'est pas là. Le vrai cache est dans `BlockBehaviour.BlockStateBase` (fichier `BlockBehaviour.java`, classe interne `BlockStateBase.Cache`, ligne ~906) :

```java
private static final class Cache {
    protected final VoxelShape collisionShape;
    protected final boolean largeCollisionShape;
    private final boolean[] faceSturdy;              // par Direction × SupportType
    protected final boolean isCollisionShapeFullBlock;
    ...
}
```

Un objet `Cache` (forme de collision, "sturdiness" par face, etc.) est calculé **une fois par `BlockState`** à l'initialisation (`initCache()`), pas recalculé à la volée. C'est déjà ce qu'un mod d'optimisation voudrait faire. Ce que fait réellement `ModernFix` sous le nom `mixin.perf.reduce_blockstate_cache_rebuilds` / `mixin.perf.cache_blockstate_cache_arrays` (noms confirmés dans un fichier `modernfix-mixins.properties` réel, voir §2), ce n'est donc **pas** créer ce cache (il existe déjà), mais éviter qu'un événement (rechargement de datapack, certains mods qui itèrent sur tous les blocs) ne **reconstruise inutilement tous ces caches d'un coup** — un coût CPU au (re)chargement, pas vraiment un coût RAM permanent. Donc : peu pertinent pour l'axe RAM, plus pertinent pour l'axe démarrage (§2).

**Occlusion shapes par face** (`occlusionShapesByFace`, un `VoxelShape[6]`) : pour un bloc plein classique, vanilla réutilise déjà un tableau partagé (`FULL_BLOCK_OCCLUSION_SHAPES`, statique) plutôt que d'allouer 6 formes par état — encore une optimisation déjà en place, rien à gratter.

**Conclusion axe VoxelShape :** contrairement à l'hypothèse de départ, il n'y a pas de gisement RAM évident ici sur cette version — le cache existe, est partagé quand c'est trivialement possible, et son coût est amorti sur la durée de vie du serveur (calculé une fois par état de bloc enregistré, pas par bloc placé dans le monde).

### 1.6 ModernFix — inventaire réel des options (fichier `.properties` vérifié)

J'ai récupéré un fichier `modernfix-mixins.properties` réel (issu d'un modpack public, ATM9, donc **1.20.1/Forge** — pas 26.1.2/NeoForge, à vérifier que les mêmes clés existent encore côté NeoForge 26.1.2, ce que je n'ai pas pu confirmer directement faute d'accès au jar de ModernFix lui-même). Liste complète des clés `mixin.perf.*` pertinentes pour la RAM et pas déjà couvertes ci-dessus :

| Clé | Ce qu'elle fait (déduit du nom + doc partielle trouvée) | Pertinence serveur dédié |
|---|---|---|
| `mixin.perf.dynamic_dfu` | Charge le DataFixerUpper à la demande plutôt qu'au boot (le DFU réclame ~100+ Mo de règles) | **Oui, fort** — voir YAMO ci-dessous, même idée |
| `mixin.perf.nbt_memory_usage` | Patch de `CompoundTag` (confirmé par un nom de classe de crash log : `perf.nbt_memory_usage.CompoundTagMixin`) pour réduire l'empreinte des tags NBT | **Oui** — pertinent si beaucoup de structures/chunks sérialisés retenus en mémoire |
| `mixin.perf.compact_bit_storage` | Nom évocateur d'un travail sur `BitStorage`/`SimpleBitStorage` ; je n'ai pas trouvé de description officielle, donc **incertain** — à vérifier avant de s'y fier | Potentiellement oui |
| `mixin.perf.cache_strongholds` / `cache_upgraded_structures` | Cache les calculs de structures (forteresses, structures upgradées) plutôt que de les recalculer | Oui, RAM + CPU au premier accès |
| `mixin.perf.dynamic_structure_manager` | Chargement paresseux des templates de structure (`StructureTemplateManager`) | Oui — recoupe l'info trouvée sur `StructureTemplate` en `SoftReference` (voir ci-dessous) |
| `mixin.perf.remove_biome_temperature_cache` | Supprime un cache de température de biome jugé inutile en mémoire | Oui, mineur |
| `mixin.perf.mojang_registry_size` | Ajuste le dimensionnement interne des registres (probablement les tables de hachage de `MappedRegistry`) | Oui, mineur, surtout gros modpacks |
| `mixin.perf.tag_id_caching` | Cache la résolution tag→id | CPU surtout |
| `mixin.perf.state_definition_construct` | Vu le nom, vise très probablement la construction de `StateDefinition`/`StateHolder` — **regarder §1.1** : si vanilla 26.1.2 fait déjà ce travail en tableaux, cette option est peut-être devenue un no-op elle aussi | Incertain, probablement obsolète comme FerriteCore fastmap |
| `mixin.perf.dynamic_resources` | Modèles/items chargés à la demande plutôt qu'au boot complet | **Client uniquement** (les modèles de rendu n'existent pas côté serveur dédié) |
| `mixin.perf.deduplicate_wall_shapes` | Dédup des formes de collision des murs (nombreuses variantes géométriques quasi identiques) | Oui, mais faible volume (peu de blocs "wall" par registre) |

**Avertissement de fiabilité :** ce fichier `.properties` vient d'un modpack Forge 1.20.1, pas du repo officiel ModernFix, et pas de la version NeoForge 26.1.2. Les noms de clés peuvent avoir changé, certaines options peuvent avoir disparu (portées par vanilla), d'autres ajoutées. **Ne pas copier ces noms de mixin tels quels dans du code Lanterne sans les revérifier contre la version de ModernFix réellement présente dans le modpack testé.**

### 1.7 YAMO — lazy DFU + JAR hors mémoire (piste concrète et peu risquée)

YAMO (`github.com/judiraal/yamo`, NeoForge 1.21.1) fait deux choses précises, documentées dans son propre changelog/description :
1. **Ne garde pas les JAR de mods en mémoire** : il les recopie/indexe sur disque (`cache/mods`) plutôt que de garder l'index ZIP complet en RAM pour chaque JAR — un gain de "quelques Mo par gros mod" (ex. cité : MineColonies).
2. **Lazy-DFU** : le DataFixerUpper (règles de migration NBT, ancien format → actuel) coûte 100+ Mo de règles en mémoire dès qu'un seul vieux NBT est rencontré (fréquent avec des mods qui embarquent des structures anciennes). YAMO le charge à la demande, upgrade une fois toutes les structures sur disque (`cache/structures`), puis n'a plus jamais besoin du DFU au démarrage suivant.

**C'est un candidat sérieux pour expliquer une partie du 1,7 Go manquant.** Le DFU est un gros morceau (100+ Mo rien que pour les règles, potentiellement plus selon la profondeur d'historique), et son chargement est quasi automatique dès qu'un mod du pack a une vieille structure NBT — ce qui est le cas de la plupart des mods de contenu. **Gain estimé : 100–300 Mo selon le nombre de mods à vieilles structures. Risque : moyen** — un bug de lazy-DFU peut corrompre une structure NBT mal migrée (issue GitHub connue de conflit avec Brewin'&Chewin/Farmer's Delight, "wontfix"). À tester isolément avant d'adopter l'idée dans Lanterne.

### 1.8 Où vont les 26,9 Go du vanilla nu — et une réserve méthodologique importante

**Réserve d'abord, elle est cruciale :** 26,9 Go pour 10 500 entités est un ordre de grandeur qui, ramené à de la mémoire *par entité*, donnerait environ 2,5 Mo/entité si tout venait des entités — ce qui est invraisemblable (même une entité très riche en état, comme un villageois avec ses échanges/gossip/pathfinding, ne pèse pas 2,5 Mo). Deux explications alternatives, plus probables, à vérifier avant d'agir :
- **Mesure de heap "committed"/"used" à un instant donné plutôt que de "live set" après un Full GC.** Avec le G1GC par défaut, le tas peut rester haut longtemps après un pic d'allocation (génération de monde, chargement massif de chunks) sans refléter ce qui est réellement retenu. Un test A/B qui ne force pas un Full GC juste avant la mesure peut surestimer massivement le vanilla par rapport aux runs avec mods (qui, eux, déclenchent peut-être plus de collectes ou ont un profil d'allocation différent). **Recommandation : refaire la mesure vanilla avec un `jcmd <pid> GC.run` (Full GC) juste avant la lecture, ou avec `-Xlog:gc` pour voir le plancher post-GC, avant de conclure que 26,9 Go sont "réels".**
- **Le pré-généré du monde** : si le monde pré-généré est chargé à un rayon de simulation large sans mods de limitation de chunks (aucun des 17 mods absent en run "vanilla nu"), le nombre de chunks réellement en mémoire peut être très supérieur à ce qu'un joueur voit — la RAM vanille peut refléter surtout **des chunks**, pas des entités.

**Ceci dit, ce qui est retenu par entité (vanilla, sans mesure précise, à vérifier par un profilage réel — outil recommandé : JOL "Java Object Layout" ou un histogramme heap `jmap -histo` / Spark heap summary) :**
- `Entity` de base : UUID (16 octets + objet), position/rotation en `double`/`float` × plusieurs (position actuelle + `xo/yo/zo` pour l'interpolation de rendu — mais côté serveur ce champ existe aussi car `Entity` est une classe commune client/serveur), `AABB` courante, `SynchedEntityData` (une table de champs synchronisés réseau, avec boxing des primitifs dans certains cas anciens).
- `LivingEntity` : table d'attributs (`AttributeMap`, une `Map` par entité même si la plupart des attributs sont aux valeurs par défaut), inventaire d'équipement, effets de statut actifs.
- `Mob` : `GoalSelector`/`TargetSelector` avec leurs listes de `WrappedGoal`, mémoire de brain (`Brain<>`) pour les entités "à cerveau" (villageois, piglins, etc. — nettement plus lourd, avec des `Map<MemoryModuleType<?>, Optional<?>>` et des capteurs).
- Pathfinding : chemins calculés (`Path`, liste de `Node`) retenus tant qu'un objectif de déplacement est actif.

Sans profilage réel je ne peux pas donner un chiffre honnête en Mo/entité — **c'est la limite de cette recherche : elle documente les structures, pas leur poids mesuré sur ce build précis**. Recommandation concrète : lancer le banc vanille avec un heap dump (`jmap -dump`) ou un histogramme (`jcmd <pid> GC.class_histogram`) filtré sur `net.minecraft.world.entity`, pour obtenir un vrai chiffre plutôt qu'une estimation livresque.

### 1.9 Techniques de compaction génériques — ce qui est actionnable depuis Lanterne

- **`@Unique` + champs primitifs plutôt que des `Map` externes par entité** : c'est déjà l'esprit du mixin existant `ServerEntityMixin`/`LivingEntityMixin` du projet (à vérifier dans le code, non relu en détail ici puisque hors périmètre RAM strict) — cette pratique reste la bonne pour tout nouvel état ajouté par Lanterne : un champ `@Unique` typé primitif coûte l'équivalent d'un slot d'objet, pas d'une entrée de `HashMap` (~48 octets de surcoût par entrée `HashMap` en plus de la clé/valeur).
- **fastutil** : déjà utilisé abondamment par le jeu lui-même (`Int2ObjectOpenHashMap`, `Long2ObjectOpenHashMap` vus dans `SectionStorage`, `PoiManager`). Cohérent avec ce que Lanterne devrait utiliser pour tout compteur/index interne plutôt que les collections `java.util` boxées.
- **Interning** : FerriteCore le fait pour les `ModelResourceLocation` (client uniquement, cf. 1.2). Côté serveur, un interning utile et non couvert serait sur les **clés NBT répétées** dans les `CompoundTag` de block entities similaires (mêmes noms de champs des milliers de fois) — mais `mixin.perf.nbt_memory_usage` de ModernFix vise probablement déjà ça (à vérifier, voir 1.6).

### 1.10 Classement gain/risque — Axe RAM

| Piste | Gain estimé (serveur dédié) | Risque | Déjà fait par un mod ? |
|---|---|---|---|
| Lazy-DFU (façon YAMO) | 100–300 Mo | Moyen (migration NBT ratée) | Oui, YAMO ; possiblement `modernfix.mixin.perf.dynamic_dfu` |
| JAR de mods hors mémoire | Quelques dizaines de Mo (dépend du nb/poids des mods) | Faible | Oui, YAMO |
| Cache structures/stronghold paresseux | Variable, dizaines de Mo | Faible-moyen | ModernFix (`cache_strongholds`, `dynamic_structure_manager`) |
| Paliers de palette intermédiaires (9–14 bits) sur `PalettedContainer` | Non chiffrable sans profilage ; potentiellement significatif en zone très modée/construite | **Moyen-élevé** (chemin très chaud, aucun précédent connu) | Non, personne ne semble le faire |
| FastMap `StateHolder` (façon FerriteCore) | **Probablement ~0** sur 26.1.2 (déjà vanilla) | — | Oui (FerriteCore), mais vraisemblablement obsolète ici |
| Dédup modèles/MRL (façon FerriteCore points 4-6) | **0 côté serveur dédié** (client seulement) | — | Oui (FerriteCore), hors sujet serveur |
| PoiManager/SectionStorage leak | **0**, déjà patché par NeoForge (PR #937, vu dans le code) | — | Oui, NeoForge lui-même |
| Cache VoxelShape/Collision par état | **0**, déjà en vanilla (`BlockStateBase.Cache`) | — | Oui, vanilla |
| Profilage réel (heap histogram) pour chiffrer le poste "entités" | N/A — c'est un préalable, pas un gain | Aucun | Non fait ici (hors budget de cette recherche) |

---

## AXE 2 — Démarrage du serveur

### 2.1 Ce qui est déjà parallélisé en vanilla/NeoForge (vérifié dans le code)

- **`ModelBakery.java`** (baking des modèles, certes client-only) utilise déjà `ParallelMapTransform.schedule(...)` pour cuire les modèles de blocs et d'items en parallèle, et un `ModelBakerImpl.operationCache` (`ConcurrentHashMap`) pour mémoïser les opérations de baking partagées entre mods. Donc même côté client, le baking de modèles n'est déjà plus le goulot séquentiel qu'il était il y a quelques versions.
- **Les reload listeners** (tags, recettes, advancements, `RecipeManager.apply(...)`) suivent le pattern standard `SimplePreparableReloadListener`, exécuté par le framework de rechargement de ressources sur un pool d'exécuteurs en arrière-plan — confirmé indirectement par la doc NeoForge trouvée en recherche web ("tasks are being collected and executed in parallel... ratio d'utilisation des threads"). Le taux d'utilisation réel dépend du nombre de cœurs disponibles au démarrage.
- **Le cycle de vie des mods** (`FMLConstructModEvent`, `RegisterEvent`, `FMLCommonSetupEvent`...) : la plupart des événements de bus de mods sont des `ParallelDispatchEvent` et tournent en parallèle entre mods — **sauf les événements de registre** (`RegisterEvent`), qui sont **explicitement séquentiels** (confirmé par la doc NeoForged/DeepWiki : "Parallelized: No (registry events are sequential)"). C'est le goulot d'étranglement structurel : chaque mod enregistre ses blocs/items/entités un par un, dans l'ordre, sur un seul thread.

### 2.2 Ce qui est honnêtement compressible depuis l'intérieur d'un mod, après le chargement de FML

Soyons directs, comme demandé : **la marge de manœuvre depuis l'intérieur d'un mod normal (classe `@Mod`, mixins classiques) est faible sur le cœur du boot FML lui-même.** Ce qu'un mod peut réellement influencer :

- **Son propre enregistrement** : minimiser le nombre d'objets enregistrés (`RegisterEvent` étant séquentiel, chaque registre traité prend un temps proportionnel au nombre total d'entrées tous mods confondus — un mod ne peut accélérer que sa propre part, pas celle des autres).
- **Ses propres `DeferredRegister`/recharges de données** : les rendre paresseux si le mod en propose de nombreuses.
- **Le contenu des datapacks qu'il fournit** (tags, recettes, advancements, loot tables) : ces JSON sont traités par des reload listeners déjà parallélisés à l'échelle du jeu, mais un mod peut réduire son propre volume de recettes/tags redondants pour raccourcir sa propre part du travail. Le mécanisme `neoforge:conditions` (vu dans la doc NeoForge) permet de court-circuiter le chargement d'un fichier de données entier si une condition échoue — potentiellement utile pour Lanterne si le mod voulait désactiver conditionnellement des morceaux de données inutiles, mais ça ne s'applique qu'à *ses propres* données, pas à celles des 17 autres mods.
- **Il ne peut pas** accélérer la phase de scan des JAR par FML/ModLauncher, la transformation des classes par Mixin (SpongePowered Mixin applique ses transformations en tant que classe transformer, avant même que le mod ait un point d'entrée), ni le chargement des autres mods.

**Verdict honnête sur les 8 s de démarrage mesurées :** avec seulement 0,45 s pour le serveur lui-même et 12 ms pour le spawn, le reste (~7,5 s) est très majoritairement FML (scan des JAR, résolution de dépendances, transformation Mixin, chargement de classes) — un poste sur lequel un mod ordinaire n'a **quasiment aucune prise**. Le seul levier crédible de ce côté serait de réduire le nombre/poids des autres mods (hors périmètre de Lanterne) ou de raccourcir sa propre initialisation (déjà probablement marginale vu que Lanterne est petit).

### 2.3 Points d'entrée plus précoces : coremod / `ILaunchPluginService` / `IMixinConfigPlugin`

`IMixinConfigPlugin` existe et est bien documenté (rôle : véto sur le chargement de mixins, transformation pré/post). Point clé trouvé en recherche : **au moment où un `IMixinConfigPlugin` s'exécute, l'environnement de mods n'est pas encore initialisé** (`ModList` n'existe pas encore à ce stade — confirmé par un fil de forum Forge cité en recherche : un développeur qui voulait conditionner des mixins à la présence d'un autre mod a découvert que `ModList` n'est pas disponible à ce stade). Concrètement :

- Un `IMixinConfigPlugin`/coremod tourne **avant** le chargement normal des mods, dans le classloader de transformation (ModLauncher). Il ne peut référencer que des classes "bootstrap-safe" (pas de classes de jeu, pas de classes d'autres mods).
- **Risque : élevé.** Un bug ici casse le lancement du jeu entièrement (pas une simple exception rattrapable côté mod), et le domaine est peu documenté/peu testé par la communauté de mods de contenu (plutôt réservé aux mods de plateforme comme Mixin lui-même, Forge/NeoForge, ou des mods de triche/anti-triche).
- **Gain potentiel réel pour Lanterne : faible à nul.** Un `IMixinConfigPlugin` sert à décider *quels* mixins appliquer selon des conditions (utile pour la compatibilité), pas à accélérer le chargement en soi. Je n'ai trouvé aucune preuve que passer par ce point d'entrée réduit le temps de boot plutôt que la config JSON standard des mixins (`required`, conditions simples). **Ne pas s'y engager pour un gain de vitesse — seulement si Lanterne avait un vrai besoin de compatibilité conditionnelle complexe.**

`ILaunchPluginService` : je n'ai pas trouvé de documentation exploitable spécifique à NeoForge 26.1.2 dans le temps imparti (les résultats de recherche n'ont pas isolé ce point). **Je ne suis pas sûr** de son applicabilité ici — à ne pas utiliser sans vérification supplémentaire.

### 2.4 Tags, recettes, advancements — coût et parallélisme

Ces trois systèmes sont chargés via le mécanisme générique de rechargement de ressources (datapack), **après** la phase de registre des objets de jeu, sur des reload listeners qui s'exécutent déjà en parallèle entre eux dans la mesure où le graphe de dépendances le permet (les recettes dépendent des tags de blocs/items déjà résolus, par exemple, donc pas tout est parallélisable). NeoForge ajoute un système de priorités de recettes (pour arbitrer les conflits tag vs item spécifique) et un système de conditions (`neoforge:conditions`) qui permet d'ignorer des fichiers entiers avant même de les parser — c'est le seul levier "gratuit" qu'un mod peut exploiter pour son propre datapack (moins de fichiers parsés = moins de temps), mais ça ne s'applique qu'aux données que Lanterne fournit lui-même (aucune ici a priori, Lanterne étant un mod de comportement, pas de contenu).

**Conclusion Axe 2, sans détour :** il n'y a pas de gain de démarrage substantiel et sûr à aller chercher depuis l'intérieur d'un mod comme Lanterne. La seule vraie piste (réduire son propre enregistrement/datapack) est déjà minimale vu la nature du mod. Le gros du temps de boot est structurellement hors de portée d'un mod normal.

---

## AXE 3 — Client

### 3.1 Mesure reproductible — la question la plus importante d'abord

**FPS Benchmark** (Modrinth, `modrinth.com/mod/fps-benchmark`) est exactement l'outil demandé : un monde temporaire isolé à seed déterministe, une caméra scriptée sur un parcours cinématique fixe (~3 min, 8 "stress scenes"), et une sortie automatique dans `fpstest-reports/<sessionId>/` avec `report.md`, `report.json`, `fps.csv`/`session.csv` (FPS moyen, moyenne harmonique, 1 %/0,1 % low, p99 frame time, temps de tick client, heap/GC). **Aucune intervention humaine requise pendant le run.**

**Limite importante et honnête :** au moment de cette recherche, ce mod supporte **Fabric 1.21–1.21.1 uniquement** ; le NeoForge est annoncé "soon" sur sa page. **Il n'est donc pas directement utilisable tel quel sur ce modpack NeoForge 26.1.2** sans portage (ou sans qu'une version NeoForge sorte entre-temps — à revérifier périodiquement). Je n'ai pas trouvé de dépôt GitHub pour ce mod dans le temps imparti, donc je ne peux pas évaluer la difficulté d'un portage.

Alternative trouvée : **Performance Overlay** (Modrinth) — overlay + benchmark léger avec FPS/frametime temps réel, lows 1 %/0,1 %, stutters, stats GC/mémoire. Statut loader/version non vérifié en détail.

Autre piste : **Replay Mod** supporte un rendu **headless** en ligne de commande (`-Dreplaymod.render.file=...`, `render.path`, `render.fps`, `render.waitforchunks`) — conçu pour l'export vidéo, pas le benchmark FPS, mais le principe (caméra scriptée rejouée à l'identique + capture automatisée) est transposable : on pourrait mesurer le temps de rendu réel par frame pendant un rendu headless comme proxy de coût de rendu, de façon parfaitement reproductible (même chemin de caméra à chaque run). Existe un addon **ReplayFPS** pour améliorer la fidélité caméra première personne pendant la lecture — probablement pas nécessaire pour un simple banc de perf.

**Recommandation ferme, conforme à la mission :** ne pas toucher au client tant qu'un protocole de mesure reproductible (scène fixe, seed fixe, caméra scriptée, sortie chiffrée automatique) n'est pas en place. FPS Benchmark est la meilleure correspondance fonctionnelle trouvée, mais son incompatibilité actuelle avec NeoForge est bloquante — soit attendre son portage, soit construire un équivalent minimal maison (un mixin qui rejoue une trajectoire de caméra enregistrée + un `Herald`/`Bench`-like du projet Lanterne qui log FPS/frametime par frame, dans l'esprit de ce que fait déjà `report/Bench.java` côté serveur — non lu en détail ici, hors périmètre RAM/boot, mais l'existence de cette classe suggère que Lanterne a déjà une culture de "banc mesuré" qui pourrait s'étendre au client).

### 3.2 LOD de tick côté client — pourquoi c'est plus risqué que côté serveur, avec le mécanisme précis

Lu `ClientLevel.java` (méthode `tickNonPassenger`) et `Entity.java` (interpolation).

`ClientLevel.tickNonPassenger` **n'a aucune dégradation par distance** :

```java
public void tickNonPassenger(Entity entity) {
    entity.setOldPosAndRot();
    entity.tickCount++;
    ...
    entity.tick();
    ...
}
```

Chaque entité "tickante" côté client appelle inconditionnellement `entity.tick()`. C'est exactement le point d'accroche où un mixin analogue au `ServerEntityMixin`/LOD déjà présent dans Lanterne pourrait s'appliquer côté client — mais **le mécanisme d'interpolation rend ça visuellement dangereux**, et voici pourquoi, vérifié dans le code :

- **Interpolation de rendu (frame-à-frame)** : `Entity.getX(partialTickTime)` fait `Mth.lerp(partialTickTime, this.xo, this.getX())` — elle interpole entre la position du **tick précédent** (`xo`, mise à jour par `setOldPosAndRot()`, appelée par `ClientLevel.tickNonPassenger` **avant** `entity.tick()`) et la position **courante**. Ce mécanisme tourne à chaque frame de rendu, indépendamment du nombre de ticks.
- **Interpolation réseau (tick-à-tick)** : `Entity.lerpPositionAndRotationStep(int stepsToTarget, ...)` avance la position logique d'une fraction vers une position cible reçue du serveur, pas à pas. Cette méthode est appelée **depuis l'intérieur du tick** (`tick()`/`baseTick()`), pas depuis le traitement réseau lui-même (qui se contente de poser la cible et le nombre de steps).

**Conséquence si on saute l'appel complet à `tickNonPassenger` pour une entité lointaine :**
1. `xo/yo/zo` ne sont plus mis à jour (gelés) → l'interpolation de rendu n'a plus rien à interpoler, l'entité reste visuellement figée à sa dernière position tickée (pas de glitch pendant le gel lui-même, juste un arrêt).
2. Les mises à jour réseau reçues pendant le gel (nouvelles positions du serveur) s'accumulent côté cible sans être consommées par `lerpPositionAndRotationStep` (puisque `tick()` ne tourne pas) → au réveil, l'entité doit rattraper d'un coup un écart de position potentiellement important : **effet de téléportation/saut visible**, proportionnel à la durée du gel et à la vitesse de déplacement de l'entité.
3. Les états animés pilotés par tick (cycle de marche, `swingProgress`, temps de dégât, particules d'effet de statut) gèlent aussi puis reprennent brutalement.

**Verdict honnête, comme demandé par la mission :** *oui*, une dégradation de tick appliquée côté client crée des artefacts visibles précis (arrêt net, puis rattrapage brutal au réveil), contrairement au serveur où une entité lointaine dégradée n'a simplement aucun observateur. La technique n'est donc pas transposable telle quelle. **Une version plus sûre** consisterait à ne dégrader que la logique non visuelle (IA, calculs coûteux) en laissant tourner `setOldPosAndRot()` + l'interpolation de position, mais ça suppose un mixin beaucoup plus chirurgical que le "skip complet" utilisé côté serveur — et donc un travail d'implémentation non trivial, hors du périmètre de cette recherche (qui devait seulement documenter l'existant, pas concevoir la solution).

Risque : **élevé** sans mesure ; gain potentiel : réel si beaucoup d'entités hors-écran/lointaines sont simulées côté client (rendu + logique), mais **EntityCulling s'occupe déjà du rendu** des entités non visibles — la dégradation de tick n'apporterait quelque chose que pour des entités *visibles mais lointaines*, un cas plus restreint.

### 3.3 Ce qui n'est probablement pas couvert par la suite client actuelle (Sodium, Sodium Extra, ImmediatelyFast, MoreCulling, EntityCulling, BadOptimizations, betterfpsdist, smoothchunk, chloride, FpsReducer2, Distant Horizons)

D'après la recherche (limitations documentées de MoreCulling/EntityCulling) :

- **Les block entities à grand rayon de rendu** (beacon, poulies de Create, certains blocs Botania) sont explicitement listés comme cas particuliers qu'EntityCulling doit **whitelister** plutôt que culler correctement par défaut — donc une marge d'optimisation existe sur les `BlockEntityRenderer` à bounding box surdimensionnée, mais elle est déjà partiellement gérée par la configuration d'EntityCulling (pas par un vrai calcul de coût), pas par un mod qui mesurerait/adapterait le coût réel de rendu par frame.
- **Aucun outil trouvé dans cette suite qui mesure ou plafonne le coût des particules** par lui-même (le "Tick Control" pour Create existe mais est spécifique à ce mod). Un mod générique de LOD de particules (réduire densité de particules au-delà d'une distance, indépendamment du culling) n'apparaît dans aucun résultat de recherche comme étant déjà couvert par la liste fournie.
- **Sandpaper** (`github.com/danielhuang/mc-sandpaper`, trouvé en recherche, non présent dans la liste actuelle) répond à un problème distinct et documenté : le tick client tourne sur le thread principal (même thread que le rendu OpenGL), ce qui crée un pic de latence à chaque tick (toutes les 50 ms) même si le FPS moyen est élevé — Sandpaper décharge une partie du tick client vers un pool de threads. **Ce n'est pas dans la liste actuelle des mods installés** et pourrait combler un manque réel (stutter périodique indépendant du FPS moyen) — mais c'est un mod tiers existant, pas une piste à réimplémenter dans Lanterne sans mesure de latence par frame (1 %/0,1 % low) au préalable.
- **Create: Tick Control** ajoute une page "Tick Reduction" dans les réglages vidéo Sodium, avec un profil de qualité — spécifique à Create, hors du périmètre générique de Lanterne, mais confirme que l'approche "réduire tick + rendu des machines lointaines" a déjà un précédent communautaire avec des gains mesurés en jeu (6-10 FPS → 60+ FPS dans un cas cité, contexte Create spécifique, pas généralisable tel quel).

**Conclusion Axe 3 :** la seule chose qu'il est raisonnable de faire *avant tout code client* est de mettre en place la mesure (§3.1). Le reste — LOD de tick client, particules, block entity renderers — a un potentiel réel mais **aucune piste n'est mesurable aujourd'hui avec l'outillage disponible sur NeoForge 26.1.2**, ce qui, selon la contrainte posée par la mission elle-même, signifie qu'on ne doit pas y toucher pour l'instant.

---

## Résumé — 6 meilleures pistes

1. **Lazy-DFU + JAR hors mémoire (façon YAMO)** — RAM serveur, gain estimé 100–300+ Mo, risque moyen. **Mesurable dès maintenant** : comparer heap avant/après avec un banc identique à ceux déjà utilisés (Lanterne a déjà `report/Bench.java`).
2. **Revérifier si FerriteCore apporte encore un gain réel sur ce modpack 26.1.2** avant de chercher à le remplacer — le code source montre que son mécanisme principal (`fastmap`/`nopropertymap`) cible des structures déjà réécrites en tableaux dans le vanilla de cette version, et ses trois autres gains sont client-only (inapplicables à un serveur dédié). **Mesurable dès maintenant** : lancer le banc avec/sans FerriteCore et regarder le log Mixin au démarrage pour une erreur d'injection sur `StateHolder`.
3. **Profilage heap réel (histogram/heap dump) du run "vanilla nu" à 26,9 Go** avant toute autre décision RAM — le chiffre est probablement gonflé par une mesure prise avant Full GC ou par le poids des chunks pré-générés plutôt que par les entités elles-mêmes. **Mesurable dès maintenant**, c'est un préalable méthodologique, pas une optimisation.
4. **Paliers de palette intermédiaires (9–14 bits) sur `PalettedContainer.Strategy`** pour les sections très hétérogènes — piste originale, non couverte par un mod connu, potentiellement significative en zone modée dense, mais risque moyen-élevé (chemin très chaud) et **non mesurable sans un profilage ciblé au préalable** (compter la distribution réelle du nombre d'états distincts par section sur le monde de test).
5. **Options RAM/boot pertinentes de ModernFix côté serveur** (`dynamic_dfu`, `nbt_memory_usage`, `cache_strongholds`, `dynamic_structure_manager`) — probablement déjà actives si ModernFix est dans le modpack testé ; à confirmer nom par nom sur la version NeoForge réelle avant de considérer les réimplémenter dans Lanterne. **Mesurable en désactivant chaque option une par une** (le fichier `.properties` de ModernFix le permet nativement) plutôt qu'en devinant.
6. **LOD de tick côté client** — gain potentiel réel mais artefacts visuels démontrés (gel puis rattrapage de position, animations qui sautent), et **non mesurable aujourd'hui** faute d'un FPS Benchmark compatible NeoForge 26.1.2 (le mod existe mais est Fabric-only pour l'instant). À ne pas coder avant qu'un protocole de mesure reproductible existe.
