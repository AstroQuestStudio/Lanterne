# La mémoire retenue d'un serveur, mesurée

`core/Ballast.java` a posé le diagnostic qui commande ce dossier : Lanterne divise par douze la
mémoire **allouée** — un débit, qui commande la fréquence des à-coups — et ne change **rien** à la
mémoire **retenue** — un stock, qui décide combien de mods tiennent dans quatre gigaoctets.

Ce document est l'inventaire de ce stock. Il a été pris avec `core/Inventaire.java`, qui est le seul
apport durable de cette session : un histogramme de tas **pris de l'intérieur**, sans JDK ni
profileur, donc utilisable sur un hébergement mutualisé où `jcmd` n'existe pas.

```
/lanterne memoire
```

## L'inventaire

Serveur vanilla 26.2 + Lanterne, une doublure connectée, distance de vue 10, 625 chunks chargés,
mesure prise **après un ramassage complet** — ce qui survit à un ramassage complet *est* la mémoire
retenue.

```
Tas retenu : 220,0 Mo

      55,3 Mo   26,9 %  [B                         ← tableaux d'octets
      19,9 Mo    9,7 %  [J                         ← tableaux de long
       9,9 Mo    4,8 %  [Ljava.lang.Object;
       7,7 Mo    3,7 %  java.lang.String
       7,0 Mo    3,4 %  java.util.HashMap$Node
       4,1 Mo    2,0 %  [Ljava.util.HashMap$Node;
       3,9 Mo    1,9 %  java.lang.Class
       3,5 Mo    1,7 %  java.util.LinkedHashMap$Entry
       3,2 Mo    1,6 %  BlockState
       2,9 Mo    1,4 %  BlockPos
       2,6 Mo    1,2 %  VoxelShape$SubShape
```

Et le relevé par domaine, qui dit **à qui** appartiennent ces octets :

```
625 chunks, 5 834 sections occupées, 9 166 déjà gratuites

  états de bloc        12,01 Mo   (nécessaire 10,74 Mo, gaspillé 10,6 %)
  lumière               8,15 Mo   (4 138 couches payées, 10 577 libres, 17 785 absentes)
      dont uniformes mais allouées : 1 089 couches, soit 2,14 Mo récupérables
  détecteurs de fil     4,35 Mo   (30 000 conteneurs, verrous compris)
  cartes de hauteur     0,74 Mo
  biomes                0,11 Mo
  ─────────────────    25,35 Mo de terrain, soit 41,5 Ko par chunk
```

## Ce que l'inventaire retourne

**Le terrain ne fait que 11,5 % du tas retenu.** C'est le résultat qui renverse l'intuition de tout
ce dossier — et celle de la littérature, qui envoie regarder les états de bloc.

Le reste se partage entre un **socle de démarrage** et ce qui suit les chunks. La comparaison des
deux relevés le sépare proprement :

| | 81 chunks (serveur au repos) | 625 chunks (une doublure) |
|---|---:|---:|
| Tas retenu | 169,0 Mo | 220,0 Mo |
| dont `[B` | 47,4 Mo | 55,3 Mo |
| dont terrain relevé | 3,17 Mo | 25,35 Mo |

Les tableaux d'octets ne montent que de 7,9 Mo quand on multiplie les chunks par huit — et la lumière
explique 8,15 Mo à elle seule. Autrement dit : **environ 47 Mo d'octets sont là avant le premier
chunk et n'en bougent plus.**

Ce que ce socle contient n'est **pas établi**. Un histogramme de classes ne dit pas qui pointe vers
quoi ; l'identifier demande un vidage de tas avec chemins de références, que cet outil ne fait pas.
Les chaînes de caractères en expliquent une part — 7,7 Mo d'objets `String`, dont les contenus sont
eux-mêmes des `byte[]` depuis Java 9 — mais le reste est une supposition, et ce document n'en fait
pas. **C'est le premier endroit où regarder ensuite**, et c'est aussi le seul poste dont on sait
qu'il grossit avec le nombre de mods sans rien devoir au monde.

## Poste par poste

### 1. Les tables de voisinage des états de bloc — clos, et vérifié une seconde fois

`Ballast` avait mesuré **4,8 Mo** sur 29 873 états, parce que `StateHolder` emploie des tableaux
(`Property<?>[]`, `Comparable<?>[]`, `S[][]`) là où les vieilles versions employaient une `Table` de
Guava. Vérifié de nouveau dans `C:/mc262src/net/minecraft/world/level/block/state/StateHolder.java` :
c'est toujours le cas en 26.2. L'optimisation principale de FerriteCore est **dans le jeu**.

**`ColorCollection` ne change rien au compte.** La 26.2 regroupe les blocs colorés
(`Blocks.BED` plutôt que seize constantes), mais `ColorCollection.registerBlocks` appelle
`register.apply` **seize fois** : c'est un remaniement d'écriture, pas une réduction d'états. Il n'y
a donc ni gain déjà pris, ni gain à prendre — et surtout rien à annoncer deux fois.

### 2. La largeur des palettes — mesuré, puis refusé

`report/Weave.java` attendait depuis une session le chiffre qui devait le trancher. Le voici :

```
payé 11,92 Mo · nécessaire 10,74 Mo · gaspillé 1,18 Mo, soit 9,9 %
```

Neuf virgule neuf pour cent, et non « du simple au quadruple ». Le raisonnement d'origine tenait sur
la section de sous-sol — pierre et air, deux états, un bit suffirait — sans savoir **combien** de
sections lui ressemblent. Peu : une section de terrain réel porte neuf à seize états distincts et
paie quatre bits parce qu'elle en a besoin de quatre. Les sections vraiment pauvres, elles, sont
**déjà gratuites** : 9 166 sur 15 000, soit 61 % du terrain, sont en palette à valeur unique.

Et la porte de sortie n'était pas propre. `Weave` annonçait que « le paquet réseau reste celui que le
client attend » ; `PalettedContainer.Data.write` dit le contraire en une ligne :

```java
buffer.writeByte(this.storage.getBits());   // les bits EN MÉMOIRE, pas ceux du disque
buffer.writeFixedSizeLongArray(this.storage.getRaw());
```

Un serveur tenant un bit en mémoire annoncerait « un bit » ; un client vanilla allouerait 256 `long`
pour en lire 64, et la connexion tomberait au premier chunk. Le **disque**, lui, était bien protégé —
`pack()` ré-encode toujours vers `bitsInStorage()`. L'écart entre les deux ne se voit qu'en lisant le
code.

1,18 Mo sur 220, au prix d'une rupture de protocole et d'un ré-encodage de 4 096 cases par section
envoyée, sur une machine à un cœur. **Refusé.**

### 3. Les détecteurs de concurrence — mesuré, réel, non corrigé

C'est le seul poste que ce dépôt n'avait jamais regardé, et il vient de la lecture de la liste des
modules de FerriteCore (`useSmallThreadingDetector`).

Chaque `PalettedContainer` porte un `ThreadingDetector`, et chaque section en a **deux** — les états
et les biomes, y compris pour les sections d'air pur que le relevé compte par ailleurs comme
« gratuites ». Elles ne paient pas de stockage ; elles paient de la serrure.

L'histogramme compte, sur le serveur de mesure :

```
ThreadingDetector                 64 992 objets   1,98 Mo
Semaphore                         65 111 objets   0,99 Mo
Semaphore$NonfairSync             64 994 objets   1,98 Mo
ReentrantLock                     65 218 objets   1,00 Mo
ReentrantLock$NonfairSync         65 267 objets   1,99 Mo
──────────────────────────────────────────────────────────
                                                  7,94 Mo   → 128 octets par conteneur
```

**Huit mégaoctets pour détecter un défaut qui n'arrive pas**, et qui croît linéairement avec les
sections chargées : sur une distance de vue plus généreuse ou à plusieurs joueurs, on parle de
dizaines de mégaoctets. L'auteur de FerriteCore annonce d'ailleurs « 10 à 15 Mo avec un seul joueur »,
ce que cette mesure confirme indépendamment.

**Ce n'est pas corrigé, et le dire est le point important.** FerriteCore propose le remplacement mais
le laisse **désactivé par défaut**, avec la mention « crashes très rares et très durs à reproduire,
à vos risques ». Remplacer un `Semaphore` par un champ nu retire aussi les barrières mémoire que
l'acquisition posait incidemment. Ce dépôt s'interdit d'expédier un changement de concurrence dont la
parité n'est pas éprouvée — la règle de la maison vaut surtout quand la récompense est tentante.

La sortie propre existerait : un `VarHandle` avec comparaison-échange conserverait **et** la détection
**et** la barrière, pour huit octets au lieu de cent vingt-huit. Elle demande un banc de parité
dédié, sous contention réelle. C'est un chantier, pas une retouche.

### 4. La lumière — petit, mais gratuit et sûr

`DataLayer` est déjà paresseux en 26.2 : tant qu'aucune valeur n'est écrite, `data` reste `null` et la
couche ne coûte rien. 10 577 couches sur 32 500 en profitent déjà.

Reste ceci : **1 089 couches allouées portent partout la même valeur** — 26 % des couches payées, soit
2,14 Mo. Elles viennent de `SkyLightSectionStorage.repeatFirstLayer`, qui alloue ses 2 048 octets sans
vérifier que le motif répété est uniforme, et de couches écrites puis revenues à zéro.

`DataLayer.fill(v)` est une méthode **publique de vanilla** qui repose exactement le cas
« uniforme » : `data = null`, `defaultValue = v`. Un balayage de compactage serait donc trivialement
à parité — `get()` rend les mêmes valeurs, au bit près, et un banc peut le vérifier case par case sur
les 4 096 cases de chaque couche.

2,14 Mo sur 220, soit **1 %**. Sûr, mais petit. À prendre le jour où l'on touchera à ce fichier pour
une autre raison, pas pour lui-même.

## Ce qui a été écarté, et sur quelle lecture

| Piste | Verdict | Sur quoi |
|---|---|---|
| Tables de voisinage des états | **Déjà dans le jeu** | `StateHolder` emploie `S[][]` en 26.2 ; 4,8 Mo mesurés |
| Cartes de propriétés | **Déjà dans le jeu** | `Property<?>[]` + `Comparable<?>[]`, pas d'`ImmutableMap` |
| `dataComponentPatch` de FerriteCore | **Déjà dans le jeu** | `PatchedDataComponentMap` construit avec `Reference2ObjectMaps.emptyMap()` et `copyOnWrite = true` — Mojang a adopté l'optimisation |
| Palettes plus étroites en mémoire | **Refusé, mesuré** | 1,18 Mo (9,9 %), et `write()` casse le protocole |
| Conteneurs à palette des sections | **Incompressible** | C'est le terrain ; le réduire, c'est retenir moins de chunks, ce qui est un autre sujet |
| Détecteurs de concurrence | **Réel — 7,94 Mo — non corrigé** | Parité de concurrence non éprouvée ; l'auteur d'amont le désactive lui-même par défaut |
| Compactage des couches de lumière | **Réel — 2,14 Mo — non fait** | Sûr et à parité vérifiable, mais 1 % |
| Socle de 47 Mo d'octets | **Non identifié** | Un histogramme de classes ne donne pas les chemins de références |

Trois entrées de ce tableau disent « déjà dans le jeu ». C'est le cinquième, sixième et septième cas
de la série ouverte par Noisium, Starlight et Lithium, et l'habitude de vérifier avant d'écrire a
maintenant épargné sept modules inutiles à ce dépôt.

## Sur FerriteCore, puisque c'était la piste

Licence **lue dans le fichier `LICENSE`** du dépôt, et non sur l'étiquette : **MIT**, texte standard,
`Copyright (c) 2020 malte0811`, identique sur toutes les branches. Absorbable par la GPL-3.0-only de
Lanterne avec crédit — ce qui est déjà fait dans `NOTICE.md` pour `core/Moulds.java`.

Un fait qui change la donne : **FerriteCore 9.0.0 existe pour 26.1/26.2**, publié le 24 mars 2026. Et
depuis la 8.0.0, tous ses modules de modèles ont été **retirés** (le système de modèles a été
réécrit en 1.21.5) : il ne lui reste que six options, **toutes côté serveur**. C'est devenu un mod de
serveur, et deux de ses six options — `replaceNeighborLookup` et `dataComponentPatch` — visent des
choses que Mojang a entre-temps corrigées lui-même.

Ses chiffres publics (3,1 Go → 1,1 Go) datent de l'ère 1.16, sont mesurés **sur un client** et **avec
un modpack**. Aucun chiffre serveur, aucun chiffre récent, aucun chiffre vanilla n'a jamais été
publié. Les reprendre pour un serveur 26.2 serait malhonnête.
