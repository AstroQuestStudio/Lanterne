# 🎃 Lanterne

> La citrouille qui éclaire sans brûler.

**Mod d'optimisation serveur pour NeoForge 26.1.2.** Il ne cherche pas à rendre le jeu plus rapide,
mais à lui éviter le travail qui ne sert à rien.

<div align="center">

### Un seul mod fait jeu égal avec vingt — et les bat de 40 % sur la mémoire, sans casser les fermes.

| | Vanilla | Modpack · 20 mods | **Lanterne seul** | **Les deux** |
|---|---:|---:|---:|---:|
| **Base habitée** | 954 ms | 26,4 ms | **26,9 ms** | **13,1 ms** |
| **Serveur peuplé** | 211 ms | 32,9 ms | **36,6 ms** | **19,8 ms** |
| **Mémoire allouée** | 21,5 Go | 4,2 Go | **2,6 Go** | **1,8 Go** |

*Rendement d'une ferme : **100 %**, mesuré — ponte, croissance, reproduction.
Aucun autre mod d'optimisation ne publie ce chiffre.*

*Et celui qui n'arrange pas : **×3,2 seulement à cinquante joueurs**, où les 20 TPS ne tiennent pas.
La raison est expliquée, pas cachée.*

</div>
---

## 📊 Résultats

Serveur dédié NeoForge 26.1.2, Ryzen 7 5800H, monde pré-généré, distance de simulation 10. Tous les
chiffres sortent de `LANTERNE_SELFTEST` — **aucun n'a été saisi à la main**, et chacun est
reproductible par quiconque a le dépôt.

Le modpack de comparaison réunit **vingt mods d'optimisation** : Lithium, FerriteCore, ModernFix,
ServerCore, Adaptive Performance Tweaks, AI-Improvements, Immersive Optimization, LetMeDespawn,
Clumps, Get It Together Drops, Chunky, Cupboard, Almanac, Fast IP Ping et leurs dépendances.

### Le comparatif, sur deux charges qui ne se ressemblent pas

<table>
<tr><th></th><th colspan="2">Serveur peuplé<br><sub>10 500 entités réparties</sub></th><th colspan="2">Base habitée<br><sub>4 000 vaches en enclos + 4 000 objets</sub></th></tr>
<tr><th>Configuration</th><th>ms/tick</th><th>vs vanilla</th><th>ms/tick</th><th>vs vanilla</th></tr>
<tr><td>Vanilla nu</td><td align="right">211,5</td><td align="right">—</td><td align="right">954,1</td><td align="right">—</td></tr>
<tr><td>Modpack (20 mods)</td><td align="right">32,9</td><td align="right">×6,4</td><td align="right">26,4</td><td align="right">×36</td></tr>
<tr><td><b>Lanterne seul</b></td><td align="right"><b>36,6</b></td><td align="right"><b>×5,8</b></td><td align="right"><b>26,9</b></td><td align="right"><b>×35</b></td></tr>
<tr><td><b>Modpack + Lanterne</b></td><td align="right"><b>19,8</b></td><td align="right"><b>×10,7</b></td><td align="right"><b>13,1</b></td><td align="right"><b>×73</b></td></tr>
</table>

```mermaid
xychart-beta
    title "Base habitee : millisecondes par tick (moins c'est mieux)"
    x-axis ["Vanilla", "Modpack 20 mods", "Lanterne seul", "Les deux"]
    y-axis "ms par tick" 0 --> 960
    bar [954.1, 26.4, 26.9, 13.1]
```

**Un mod fait jeu égal avec vingt.** Sur la base habitée — un élevage et un sol jonché, ce que tout
serveur finit par avoir — Lanterne seul rend 26,9 ms là où vingt mods en rendent 26,4. L'écart est
sous le bruit de fond.

Sur le serveur peuplé, le modpack garde **11 % d'avance** (32,9 contre 36,6). C'est dit tel quel : il
réunit vingt ans de travail cumulé, et Lithium à lui seul contient environ deux cents optimisations
ciblées que ce mod n'a pas.

Et **les deux ensemble valent mieux que chacun** : ×10,7 et ×73. Ils ne font pas le même travail.

### Là où Lanterne prend nettement le dessus

```mermaid
xychart-beta
    title "Charges ou le gradient de Lanterne change tout"
    x-axis ["1 000 vaches 15x15", "8 000 objets au sol", "Base habitee"]
    y-axis "Facteur de gain" 0 --> 70
    bar [5.2, 62.4, 42]
```

| Charge | Sans Lanterne | Avec | Gain |
|---|---:|---:|---:|
| 8 000 piles d'objets au sol | 463,5 ms | **7,4 ms** | **×62** |
| Base habitée (4 000 + 4 000) | 1 134,8 ms | **26,9 ms** | **×42** |
| 1 000 vaches dans 15 × 15 blocs | 28,4 ms | **5,4 ms** | **×5,2** |

<sub>Le ×62 n'est pas une aberration : sans le mod, un tick dure 463 ms et le chien de garde du
serveur finit par le déclarer planté. Il vient d'un fait vérifié dans le code —
`Entity.canBeCollidedWith()` rend **faux par défaut**, et trois classes seulement le redéfinissent
dans tout Minecraft. Mille vaches font donc un million de tests d'intersection par tick pour obtenir
mille listes vides.</sub>

### Mémoire — le seul domaine où Lanterne bat le modpack sans discussion

```mermaid
xychart-beta
    title "Gigaoctets alloues en 25 secondes (moins c'est mieux)"
    x-axis ["Vanilla", "Modpack 20 mods", "Lanterne seul", "Les deux"]
    y-axis "Go alloues" 0 --> 22
    bar [21.48, 4.23, 2.55, 1.82]
```

| Configuration | Mémoire **allouée** | Mémoire **retenue** après ramassage |
|---|---:|---:|
| Vanilla nu | 21,48 Go | 436,5 Mo |
| Modpack (20 mods) | 4,23 Go | 410,3 Mo |
| **Lanterne seul** | **2,55 Go** | **355,6 Mo** |
| Modpack + Lanterne | 1,82 Go | 376,1 Mo |

**Lanterne seul alloue 40 % de moins que vingt mods réunis**, et retient 55 Mo de moins.

Les deux colonnes ne disent pas la même chose, et les confondre est une erreur courante :

- la **mémoire allouée** est le débit — combien d'octets le serveur réclame par seconde. C'est elle
  qui commande la fréquence des ramassages, donc des à-coups. Sur un VPS à un cœur, un ramassage ne
  s'exécute pas « en parallèle » : il **fige le serveur** ;
- la **mémoire retenue** est ce qui reste occupé après un ramassage complet — que le banc provoque au
  lieu de l'attendre. C'est la réponse à « combien de RAM faut-il donner à ce serveur ? ».

### Plusieurs joueurs — y compris le chiffre qui n'arrange pas

```mermaid
xychart-beta
    title "Le gain s'erode quand les joueurs se multiplient"
    x-axis ["1 joueur", "10 joueurs", "50 joueurs"]
    y-axis "Facteur de gain" 0 --> 8
    line [7.1, 5.0, 3.2]
```

| Effectif | Sans Lanterne | Avec | Gain | TPS obtenu |
|---|---:|---:|---:|:---:|
| 1 joueur, 10 500 entités | 202,9 ms | **28,2 ms** | ×7,1 | 🟢 20 |
| 10 joueurs, 11 441 entités | 222,2 ms | **44,8 ms** | ×5,0 | 🟢 20 |
| **50 joueurs, 13 378 entités** | 283,9 ms | **87,9 ms** | **×3,2** | 🟠 **11** |

**Lanterne ne suffit pas à cinquante joueurs sur cette charge.** Le gain reste réel — un serveur à
3,5 TPS devient un serveur à 11 — mais les vingt ticks par seconde ne sont pas tenus.

La raison est structurelle et mérite d'être comprise plutôt que masquée : **ce mod dégrade ce qui est
loin de tout joueur.** Plus il y a de joueurs, moins il existe d'endroits éloignés de tous, et moins
il reste de travail à éviter.

> C'est pourquoi la cible assumée est le **petit serveur** : une poignée d'amis, une ou deux bases, un
> VPS à un ou deux cœurs. C'est là qu'il donne le meilleur, et c'est là qu'il a été conçu.

Le réseau suit le même gradient : **560 paquets par tick sans le mod, 387 avec** (×1,45) — ce qui
compte pour un joueur en mauvaise connexion.

<sub>Il a fallu trois tentatives pour obtenir la mesure à dix joueurs, les deux premières ayant été
**refusées par le contrôle préalable** : 18 entités vivantes sur 10 500, puis 962. Dix doublures
espacées de 500 blocs réclament 6 250 chunks que le gestionnaire de distance ne livre pas dans le
temps imparti. Sans ce refus, le banc aurait comparé deux mondes presque vides et annoncé « aucun
effet » avec aplomb.</sub>

### Quel module fait quoi — et la surprise

Chaque optimisation a son interrupteur, ce qui permet de la mesurer **seule**. Sur la base habitée :

| Modules actifs | Travail évité | Gain |
|---|---:|---:|
| `lod` seul — dégrader ce qui est **loin** | 🔴 **0,8 %** | aucun |
| `lod` + `densite` — et ce qui est **entassé** | 💚 **96,1 %** | **×10,7** |
| `collisions` seul — le court-circuit de `Solid` | — | **×2,34** |
| tout ensemble | — | **×42** |

**La première ligne est le résultat le plus dérangeant du projet.** La thèse d'origine — « dégrader ce
qui est loin d'un joueur » — ne fait *rien* sur une base habitée. C'est logique une fois mesuré : un
élevage et un sol jonché sont **à vingt blocs du joueur**, dans la zone que le mod s'interdit de
toucher.

Ce qui porte le gain, là où les joueurs vivent réellement, c'est la **densité** : ce qui est noyé dans
le nombre ne se distingue pas, qu'il soit loin ou sous vos yeux.

### Sauvegarde — le réglage à trois lettres que personne n'active

Minecraft sait écrire ses fichiers de région en **LZ4** depuis longtemps. Il ne le fait pas :
`region-file-compression` vaut `deflate` par défaut.

| Compression | 64 chunks sauvegardés | Place sur disque |
|---|---:|---:|
| `deflate` (défaut) | 125,3 ms | 20,4 Ko/chunk |
| **`lz4`** | **33,4 ms** | ~24 Ko/chunk |

**×3,7 sans une ligne de code**, pour ~20 % de place en plus. La bascule est sans risque : la version
de compression est inscrite dans l'en-tête de **chaque chunk**, donc un monde en `deflate` se relit
sans rien convertir.

> Lanterne ne modifie pas votre `server.properties` — la configuration d'un serveur appartient à celui
> qui l'administre. Il le **détecte au démarrage** et affiche le conseil avec son chiffre.

### Génération de chunks — l'ordre de grandeur qui recadre le sujet

| Opération | Coût médian par chunk |
|---|---:|
| **Générer** un chunk neuf | **37 ms** |
| Relire un chunk déjà généré | **6,8 ms** |

Générer un chunk coûte presque **un tick entier**. Mais le profileur a tranché une question qu'on se
posait mal : **le fil du serveur attend à 92 %** pendant ce temps. La génération s'exécute sur le pool
de travail, et ces 37 ms sont une **latence d'exploration**, pas un coût de tick.

Optimiser là améliorerait la vitesse d'exploration, pas les TPS. Lanterne n'y touche **pas** — et le
dire est plus utile que de le laisser croire.

---

## ✅ Conformité — ce qu'aucun autre mod ne mesure

> Un mod d'optimisation qui casse une ferme a échoué, **même à ×10**.

### Chute libre — la mécanique de toutes les fermes à monstres

Une créature en chute libre parcourt **20,3 blocs en 25 ticks**. C'est la mécanique sur laquelle
repose toute ferme à monstres : on fait tomber d'assez haut pour tuer, et l'on ramasse en bas.

| Configuration | 16 blocs | 40 blocs | 64 blocs |
|---|:---:|:---:|:---:|
| Vanilla (référence) | 💚 100 % | 💚 100 % | 💚 100 % |
| **Lanterne seul** | 💚 **100 %** | 💚 **100 %** | 💚 **93 %** |
| **Modpack (20 mods)** | 🔴 **1 %** | 🔴 **1 %** | 🔴 **1 %** |
| Modpack + Lanterne | 🟠 10 % | 🟠 10 % | 🟠 8 % |

> ⚠️ **Le modpack réduit les chutes à un centième de leur vitesse** — 0,2 bloc au lieu de 20,3, y
> compris à seize blocs du joueur. Une ferme à chute y produit **cent fois moins**, et le joueur
> l'attribuera à autre chose : à la malchance, au spawn, à sa construction.

Ses excellents chiffres de vitesse sont donc payés, en partie, avec du rendement de jeu. Ce n'est pas
un procès : **Lanterne a fait exactement la même erreur**, et c'est l'épreuve qui l'a rattrapée — la
correction lui a coûté sa première place au chronomètre.

<sub>La dernière ligne est une observation inattendue, donnée avec prudence : ajouter Lanterne à ce
modpack fait remonter les chutes de 0,2 à 2,1 blocs. L'explication la plus probable est qu'Adaptive
Performance Tweaks dégrade en fonction des TPS mesurés — en soulageant le serveur, Lanterne lui fait
appliquer moins de dégradation. C'est un effet d'interaction, pas une réparation : il n'a pas été
isolé mod par mod, et il ne doit pas être lu comme une garantie.</sub>

### Rendement — le défaut que ce mod s'est découvert à lui-même

C'est la découverte la plus importante de ce projet, et elle est arrivée tard.

Les compteurs qui font le **rendement** d'une ferme ne sont pas rangés à part dans Minecraft. Ils
vivent au milieu de l'intelligence, dans les `aiStep()` des sous-classes :

```java
Chicken.aiStep    : if (--this.eggTime <= 0) { ... pond un œuf ... }
AgeableMob.aiStep : if (this.canAgeUp()) this.setAge(++age);
Animal.aiStep     : if (this.inLove > 0) this.inLove--;
```

Tant que ralentir une créature voulait dire **annuler son tick**, ces trois horloges s'arrêtaient avec
elle. Mesuré, à 120 blocs d'un joueur :

| | Œufs pondus | Croissance des petits |
|---|:---:|:---:|
| Lanterne avant la correction | 🔴 **3 %** | 🔴 **19 %** |
| **Lanterne après** | 💚 **100 %** | 💚 **100 %** |

**Une ferme à œufs éloignée perdait 97 % de sa production.** Aucune des trois épreuves existantes ne
pouvait le voir : l'une mesure les chutes, l'autre les fours, la troisième la vitesse. Personne ne
mesurait la production.

> Le remède n'est pas de ralentir moins. C'est de **tenir les horloges à la main** pendant le
> sommeil — un veau vieillit, un délai de reproduction s'écoule, et une poule est réveillée juste
> avant sa ponte pour que l'œuf sorte au tick exact.

Et c'est ce qui a permis d'aller **plus loin** ensuite : puisque la cadence ne coûte plus de
production, la dégradation des foules a pu passer d'un facteur 4 à un facteur 16. Le gain sur
l'élevage intensif est passé de ×3,0 à ×5,3 — payé par la correction, pas malgré elle.

### Objets au sol — disparition au tick exact

Un sol jonché donne le plus gros chiffre du projet (×62). Il ne vaudrait rien si un objet ralenti
cessait de disparaître : le sol s'accumulerait, et le gain deviendrait une fuite de mémoire.

C'est exactement ce qui arrivait. La cadence annule le tick d'un objet, or **son horloge d'âge vit
dans ce tick** : durée de vie demandée 120 ticks, obtenue 152, sur les 40 objets mesurés. Le remède
est le même que pour la ponte des poules — tenir l'horloge à la main pendant le sommeil.

| Vérification | Résultat |
|---|---|
| Disparition (durée de vie imposée 120 ticks) | 💚 **0 objet décalé sur 40**, écart maximal 0 tick |
| Aspiration par une trémie | 💚 identique, avec et sans |
| Ramassage par un joueur | ⚪ **non mesuré** — une doublure n'exécute pas son `aiStep` |
| **Un bateau bloque-t-il encore ?** | 💚 raccourci **refusé** sur sa boîte, **autorisé** ailleurs |

<sub>La dernière ligne teste le mécanisme le plus risqué du mod. `Solid` supprime les recherches de
collision parce que trois classes seulement peuvent bloquer dans tout Minecraft — mais si c'était mal
implémenté, le symptôme ne serait pas un ralentissement : ce serait **une créature qui traverse un
bateau**. Aucune autre épreuve n'aurait pu le voir. Les deux réponses comptent autant l'une que
l'autre : un mécanisme qui refuserait toujours serait sûr et inutile.</sub>

<sub>La dernière ligne est affichée telle quelle plutôt que présentée comme réussie. Ce projet a déjà
annoncé « exact au tick près » en comparant deux fours qui n'avaient cuit ni l'un ni l'autre.</sub>

### Cuisson — exact au tick près

| | Four 1 | Four 2 | Four 3 | Four 4 | Four 5 |
|---|:---:|:---:|:---:|:---:|:---:|
| Sans Lanterne | 200 | 200 | 200 | 200 | 200 |
| **Avec Lanterne** | **200** | **200** | **200** | **200** | **200** |

Le four ne travaille plus que **deux fois au lieu de deux cents**, et cuit au même tick. Ce n'est pas
un ralentissement compensé : c'est un calcul qu'on cesse de refaire parce qu'on en connaît la réponse.

---

## 💡 La thèse

```mermaid
flowchart LR
    A["🐄 Une vache<br/>à 150 blocs"] -->|vanilla| B["coûte autant qu'une vache<br/>sous les yeux du joueur"]
    A -->|Lanterne| C["coûte ce qu'elle vaut<br/>pour le joueur"]
    style B fill:#5a2020,stroke:#c44,color:#fff
    style C fill:#1f4a2f,stroke:#4a4,color:#fff
```

Lithium rend chaque système du jeu plus rapide, et le fait bien. Lanterne pose une autre question :
**pourquoi ce travail a-t-il lieu ?**

Le jeu ne connaît que deux états — chargé, ou pas chargé. Cette absence de nuance est la source
principale de dépense sur une instance chargée. C'est le principe du **niveau de détail**, vieux de
trente ans dans le rendu 3D, et absent de la simulation de Minecraft.

### Les cinq garanties

| | |
|:---:|---|
| 🛡️ | **Le rendement ne dépend jamais de la cadence.** Ponte, croissance, reproduction, durée de vie des objets : tenus au tick exact, quelle que soit la distance. **Mesuré, pas promis.** |
| 🛡️ | **Rien n'est jamais arrêté**, seulement espacé. |
| 🛡️ | **Ce qui produit garde sa cadence.** Villageois et pillards : jamais sous 1 tick sur 4. |
| 🛡️ | **Ce qui tombe tombe.** Chute, projectiles, TNT amorcée, véhicules montés : jamais espacés. |
| 🛡️ | **Une bête isolée près d'un joueur** n'est pas touchée : en deçà de 24 blocs, le jeu est le jeu. |
| 🛡️ | **Quand le serveur va bien, le mod ne fait presque rien.** |

### Et ce qui est dégradé, dit franchement

| | |
|:---:|---|
| ⚖️ | **Dans une foule, une bête réfléchit jusqu'à 16 fois moins souvent** — y compris à sept blocs du joueur. Elle continue de vieillir, de pondre et de se reproduire à l'heure ; c'est sa *décision* qui est espacée, pas sa production. |
| ⚖️ | **Dans un tas, la bousculade est plafonnée** à 26 voisines au lieu de toutes. Dans un tas de deux cents, personne ne peut dire qui a poussé qui. |

<sub>La première ligne de ce tableau corrige une garantie que ce README affichait à tort : il
promettait « rien n'est dégradé près d'un joueur » alors que la dégradation par densité s'applique à
sept blocs comme à cent. Une garantie inexacte est pire qu'une garantie absente — elle empêche celui
qui constate un écart de soupçonner le bon coupable.</sub>

---

## ⚙️ Architecture

```mermaid
flowchart TD
    subgraph mesure["🔍 Mesurer"]
        TB["TickBudget<br/><i>pression du tick précédent</i>"]
        RA["Rationing<br/><i>pression intra-tick</i>"]
        MA["Machine<br/><i>la machine se mesure elle-même</i>"]
    end
    subgraph decide["🧭 Décider — un seul passage"]
        CE["Census<br/><i>par chunk, pas par entité</i>"]
        CR["Crowd<br/><i>densité locale</i>"]
        CA["Cadence<br/><i>ticks entre deux réveils</i>"]
    end
    subgraph agir["⚡ Agir"]
        ET["EntityThrottle"]
        NT["NetworkThrottle"]
        SL["Sleep<br/><i>sommeil à échéance</i>"]
        JA["Jam<br/><i>amas immobiles</i>"]
    end
    TB --> CA
    RA --> CA
    CE --> CA
    CR --> CA
    CA --> ET
    CA --> NT
    ET --> SL
    ET --> JA
```

### Les décisions qui portent tout

**Recenser des chunks, pas des entités.** L'approche naïve calcule la distance de chaque entité à
chaque joueur : 100 000 entités × 5 joueurs = 500 000 calculs par tick. Mais deux vaches du même
chunk sont à 16 blocs près à la même distance. À 10 chunks : **441 calculs au lieu de 500 000.**

**Une droite, pas des paliers.** Une créature à 100 blocs tombait dans « un tick sur quatre » et y
restait jusqu'à 112. Un palier est un compromis figé : trop prudent en haut de tranche, trop brutal
en bas.

**L'étalement.** La version évidente serait `tick % period == 0`. Elle est fausse, et de la pire
façon : toutes les entités d'une même cadence se réveilleraient **dans le même tick**. La charge ne
baisserait pas, elle se **concentrerait**.

**Le sommeil à échéance.** Un four qui cuit a deux échéances calculables. Entre les deux, rien
d'observable ne se produit — et pourtant chaque tick alloue deux objets et cherche une recette pour
incrémenter un compteur. On ne ralentit pas : **on dort, et l'on rattrape exactement**.

---

## 🔬 Le laboratoire

Un mod d'optimisation qui ne se mesure pas ne vaut rien. Celui-ci embarque de quoi **se réfuter**.

```bash
LANTERNE_SELFTEST="10000:150:1" ./gradlew runServer   # entités:rayon:joueurs
LANTERNE_CONFORMANCE=1          ./gradlew runServer   # l'épreuve de chute
LANTERNE_KITCHEN=1              ./gradlew runServer   # l'épreuve de cuisson
LANTERNE_ALLOC=1                ./gradlew runServer   # qui alloue, et combien
LANTERNE_MODULES="lod,density"  ./gradlew runServer   # un module à la fois
```

En jeu : `/lanterne`, `/lanterne bench`, `/lanterne on|off`.

| Outil | Ce qu'il apporte |
|---|---|
| `Preflight` | **Refuse de mesurer** si les conditions ne sont pas réunies |
| `Bench` | Comparaison A/B, médiane, chauffe **et échéance** — un banc doit toujours finir |
| `Scene` | Les charges d'essai : anneau, **enclos intensif**, **sol jonché d'objets** |
| `Sampler` | Profileur par échantillonnage — parts sur le **travail réel**, pas sur l'attente |
| `Allocations` | Profileur d'**allocations** (JFR) — qui alloue, et combien |
| `Understudy` | **De vrais joueurs simulés** — ni client, ni réseau |
| `Conformance` | L'épreuve de chute, 5 sujets par distance, médiane |
| `Kitchen` | L'épreuve de cuisson, au tick près |
| **`Yield`** | **L'épreuve de rendement** — œufs pondus, croissance des petits |
| **`Tidy`** | **L'épreuve des objets** — disparition, trémie, ramassage, et le bateau qui doit bloquer |
| **`Boom`** | Le coût d'une explosion, à charge reconstituée avant chaque tir |
| **`Flow`** | Le coût d'un écoulement d'eau, par différence avec un bassin sec |
| **`Quarry`** | Génération **contre** chargement de chunks, mesurés séparément |
| **`Vault`** | Sauvegarde sur disque : **temps et place**, jamais l'un sans l'autre |
| **`Glass`** | Images par seconde côté client, sans intervention humaine |

<sub>Les sept derniers ont été construits en une nuit. Deux d'entre eux ont immédiatement trouvé des
défauts dans le mod — `Yield` les 97 % de rendement perdus, `Tidy` les objets qui ne disparaissaient
plus à l'heure. Un banc qui ne trouve rien n'a pas encore prouvé qu'il fonctionne.</sub>

### Sept outils qui savent refuser de conclure

C'est la propriété la plus utile du laboratoire, et la plus difficile à obtenir : **un banc qui
préfère dire « je ne sais pas ».**

| Cas rencontré cette nuit | Ce que le banc a fait |
|---|---|
| Débit d'une chaîne de trémies : 18/30, puis 39/16, puis 16/16 | **refuse de citer le chiffre** — trois exécutions identiques, résultats incohérents |
| Chargement de chunks : « gain ×30 » | **rejette son propre verdict** — aucun module ne touche au chargement, donc c'est le protocole |
| Coût d'un écoulement d'eau : différence négative | **refuse de publier un ratio** — le signal est sous le bruit |
| Ramassage par un joueur : « non » des deux côtés | **dit « non mesuré »** — une doublure n'exécute pas son `aiStep` |
| Charge témoin à 135 ms/tick, 500 ticks demandés | **s'arrête à l'échéance** et dit sur combien de relevés il conclut |

`Understudy` est la pièce qui rend tout le reste possible : de vrais `ServerPlayer`, inscrits dans la
liste du serveur, avec une connexion qui absorbe les paquets. **Cent doublures coûtent ce que
coûteraient cent joueurs** — sur une machine qui n'en héberge aucun.

### La machine se mesure elle-même

```
Machine : 16 fil(s) annoncé(s), gain parallèle réel ×13.19 — paralléliser vaudrait le coup
Machine :  1 fil(s) annoncé(s), gain parallèle réel ×1.00 — contre-productif, faire moins
```

`Runtime.availableProcessors()` ment souvent : un VPS annoncé à quatre cœurs peut n'en avoir qu'un de
réellement disponible. La documentation de Folia recommande **seize cœurs physiques minimum**, en
deçà desquels l'ordonnancement coûte plus qu'il ne rapporte.

---

## 🧭 Ce que ce projet a appris en se trompant

> Le banc a rendu **quinze verdicts négatifs**, et chacun avait raison. Ce qui suit n'est pas une
> liste d'échecs : c'est la raison pour laquelle les chiffres du haut de page sont fiables.

<details>
<summary><b>🔴 Quatre fois où l'outil de mesure mentait, et non le mod</b></summary>

<br>

C'est la catégorie la plus dangereuse. Un mod lent se voit ; **un banc faux se croit**.

**Le profileur diluait tout dans l'attente.** Un serveur qui tient ses 20 ticks par seconde passe le
plus clair de son temps à dormir. 76 % des relevés surprenaient le serveur au repos, et tous les
postes étaient donc divisés par quatre : `EntitySection.getEntities` passait pour 5,6 % alors qu'il
pesait **24 % du travail réel**. Un profil qui sous-évalue d'un facteur quatre le poste le plus lourd
ne désigne pas la bonne cible.

**Le rapport décrivait la phase témoin.** Le verdict s'écrit à la fin de la seconde phase, mod
éteint — il lisait donc des compteurs remis à plat. Il annonçait « travail évité : 4,5 % » là où la
mesure réelle est 90 %. Deux chiffres du même rapport se contredisaient ; c'est ce qui a mis la puce
à l'oreille.

**Le banc ne finissait pas.** Le protocole demandait 700 ticks par exécution, en supposant sans le
dire qu'un tick dure 50 ms. Sur la charge des objets au sol, un tick durait **135 secondes** : 26
heures d'attente. Il ne rendait pas un chiffre faux, il ne rendait rien — ce qui est pire.

**Deux protocoles fabriquaient leur propre écart.** La chaîne de trémies n'était pas vidée entre les
phases (136 objets arrivés au bout d'une chaîne qui n'en reçoit que 64) et le relevé se faisait sur
deux durées différentes. L'écart de 18 % imputé au mod venait entièrement du banc.
</details>

<details>
<summary><b>🐣 Le défaut le plus grave : 97 % du rendement d'une ferme</b></summary>

<br>

Les compteurs de production vivent **dans** le tick, mêlés à l'intelligence : `eggTime` pour la
ponte, `age` pour la croissance, `inLove` pour la reproduction. Annuler le tick d'une créature —
c'est-à-dire la façon la moins coûteuse de ne rien faire — arrêtait ces trois horloges.

Une poule à 120 blocs pondait **3 %** de sa production normale. Un veau grandissait à **19 %** de sa
vitesse. Le mod tenait sa promesse de vitesse en prélevant la différence sur la production, sans le
savoir, et sans qu'aucune de ses trois épreuves ne puisse le voir.

Deux remèdes ont été écrits et mesurés. Le premier — laisser le tick s'exécuter et n'en retirer que
l'intelligence — est plus élégant, plus général, et couvre automatiquement les créatures des mods. Le
banc l'a chiffré : **14,31 ms contre 10,01**. Il est conservé, mais en option.

Le second, retenu : tenir les horloges à la main pendant le sommeil. Et c'est lui qui a permis
d'aller *plus loin* — puisque la cadence ne coûte plus de production, la dégradation des foules a pu
passer d'un facteur 4 à 16.
</details>

<details>
<summary><b>💤 Le sommeil des objets — l'idée phare, écartée par son propre banc</b></summary>

<br>

C'était l'innovation la mieux fondée de ce projet. Un objet posé au sol refait vingt fois par seconde
une requête de collision complète dont la réponse n'a pas changé depuis trois minutes — et l'on pouvait
démontrer, code du jeu à l'appui, que l'endormir ne cassait **rien** : c'est le joueur qui ramasse, la
trémie qui aspire, l'explosion qui cherche.

Quatre fichiers, deux mixins, un compteur de modifications par section de chunk pour le réveil
événementiel, une épreuve de conformité dédiée. Et deux bugs à corriger avant qu'il ne fonctionne :

**La condition ne se déclenchait jamais.** Elle exigeait une vitesse nulle. Or un objet posé au sol
n'a **jamais** une vitesse nulle en vanilla : `applyGravity()` lui retire 0,04 en y à chaque tick, et
le sol ne les lui rend qu'un tick sur quatre. Le bon critère est le **déplacement réel**.

**Le compteur comptait les mauvais ticks.** Il s'incrémentait à chaque passage — mais la dégradation
par densité ralentit déjà un tas d'objets à un tick sur seize. Atteindre 45 demandait **720 ticks de
jeu**. Deux modules du même mod se gênaient sans que rien ne le signale.

Une fois réparé, il a été mesuré. Et **retiré** :

| | |
|---|---|
| module seul, 2 000 objets | 38,46 contre 38,84 ms → **aucun effet** |
| tout **sauf** lui, 8 000 objets | **×62,24** |
| tout **avec** lui, 8 000 objets | ×62,36 |

La cadence et le court-circuit de collision faisaient déjà tout le travail ; le sommeil n'arrivait
qu'après eux, sur un tick déjà supprimé.

**Ce que son retrait laisse en place est la vraie trouvaille.** En cherchant pourquoi la disparition
des objets était décalée — 152 ticks au lieu de 120 — on a découvert que la cadence arrêtait leur
horloge d'âge, exactement comme elle arrêtait la ponte des poules. Cette correction reste, et
l'épreuve confirme 0 décalage sur 40 objets **sans** le module de sommeil.

Le retrait supprime au passage un mixin sur `LevelChunk.setBlockState` — sur **chaque pose de bloc du
serveur**. Un chemin très chaud, allégé pour de bon.
</details>

<details>
<summary><b>Deux plans démolis avant d'être codés</b></summary>

<br>

**La compensation des ticks aléatoires.** Ne tirer qu'une fois sur seize, mais seize fois plus. La
loi est préservée — c'est exact — et le gain est **nul** : même nombre total de tirages. Abandonnée
sur une multiplication.

**Le ralentissement des entonnoirs.** Un entonnoir tické une fois sur seize transfère seize fois
moins d'objets, et les chunks concernés sont dans le rayon de simulation — ce sont des fermes *en
marche*. On ne les aurait pas optimisées, on les aurait cassées.
</details>

<details>
<summary><b>Un débordement d'entier qui neutralisait tout le mod</b></summary>

<br>

```java
if (tick - lastRefresh < REFRESH_PERIOD) return;   // lastRefresh = Long.MIN_VALUE
```

`100 - Long.MIN_VALUE` déborde et redevient négatif. La condition était toujours vraie, et **le
recensement ne s'est jamais exécuté** — de la première ligne jusqu'au sixième banc. Le mod compilait,
démarrait, journalisait, et ne faisait rien. Le banc l'a dit en une ligne : `chunks recensés : 0`.
</details>

<details>
<summary><b>Ce qui tombe — l'erreur qui aurait cassé toutes les fermes</b></summary>

<br>

La gravité s'accumule : `v += g` puis `y += v`. Ticker une créature une fois sur quatre ne divise pas
sa chute par quatre **mais par seize**. Mesuré : 7 % de la chute normale à 80 blocs.

Corriger cela a ramené le gain annoncé de ×10,5 à ×3,6. **Le ×10,5 n'était pas un gain, c'était une
dégradation non mesurée.**
</details>

<details>
<summary><b>Trois fois, le banc a menti — et toujours en accusant le mod</b></summary>

<br>

**Les doublures n'étaient pas de vrais joueurs**, puis l'étaient mais en spectateur — que le
recensement ignore — puis n'appartenaient à aucun monde. Dans les trois cas, le mod voyait un serveur
vide et ralentissait tout au maximum.

**Le banc générait le monde pendant qu'il mesurait** : la génération de terrain dominait les
allocations relevées. Corriger cela a fait passer le gain de ×4,4 à ×7,9.

**Le monde conservé gardait les créatures des épreuves précédentes** : l'épreuve de chute a rendu
6 % / 4 % / 0 %, imputés au mod alors que ses sujets étaient simplement noyés dans une foule.

C'est de ces trois mensonges qu'est né `Preflight` : **un banc doit refuser de rendre un chiffre
plutôt qu'en rendre un faux**, parce qu'un chiffre faux ne se corrige pas — il se propage.
</details>

<details>
<summary><b>Ce que le profileur d'allocations a trouvé, et que rien ne laissait deviner</b></summary>

<br>

```
41,0 %  NaturalSpawner.getRandomPosWithin  2,29 Go
11,9 %  Level.getBlockRandomPos            1,58 Go
```

Deux méthodes qui **allouent un objet pour rendre trois entiers**, appelées des dizaines de milliers
de fois par tick. Une position mutable réutilisée suffit — après avoir vérifié, et non supposé, que
rien ne conserve la référence.
</details>

---

## 🗺️ Ce qui vient

| Piste | Attendu | État |
|---|---|---|
| Moteur de lumière | Premier poste d'allocation restant | À mesurer |
| `EntityTickList` à buckets | **Terrain vierge** — personne, pas même Moonrise, ne le remplace | À prouver |
| Redstone (*Alternate Current*) | ×10-100 sur gros circuits | ⚠️ Casse les TNT dupers — hors garanties |
| Tick parallèle par régions | Nul sous 16 cœurs | Verdict rendu, non implémenté |

## ⚠️ Cohabitation

**Immersive Optimization fait du tick scheduling par distance, comme Lanterne.** Les deux
appliqueraient leur dégradation l'un sur l'autre. Le retirer avant de juger.
