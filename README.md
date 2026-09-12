# Lanterne

> La citrouille qui éclaire sans brûler.

Mod d'optimisation pour NeoForge 26.1.2. Il ne cherche pas à rendre le jeu plus rapide, mais à lui
éviter le travail qui ne sert à rien.

**Cible : les petits serveurs, 1 à 2 vCPU.** C'est là qu'il est le plus utile — et c'est là que les
mods qui parallélisent ne servent à rien, faute d'un second cœur où pousser le travail.

---

## Résultats mesurés

Même monde, même charge — 4 000 entités, un joueur, distance de simulation 10 — même protocole.
Tous les chiffres sortent de `LANTERNE_SELFTEST`. **Aucun n'a été saisi à la main.** Variance vérifiée
sur trois exécutions identiques : **2 %**.

### Vitesse

| Configuration | ms/tick | vs vanilla |
|---|---:|---:|
| Vanilla nu | ~68 | — |
| Modpack d'optimisation (17 mods) | 13,5 | ×5,0 |
| **Lanterne seul** | **19,3** | **×3,6** |
| Modpack + Lanterne | 8,5 | ×8,0 |

Le modpack va plus vite que Lanterne seul. **Il faut dire pourquoi**, et c'est l'objet du tableau
suivant.

### Conformité — ce que le mod ne doit pas avoir changé

Chute libre d'une créature, mesurée en blocs parcourus en 25 ticks. C'est la mécanique dont dépendent
**toutes** les fermes à monstres : on fait tomber d'assez haut pour tuer, et l'on ramasse.

| Configuration | 16 blocs | 40 blocs | 64 blocs |
|---|---:|---:|---:|
| Vanilla (référence : 20,3 blocs) | 100 % | 100 % | 100 % |
| **Modpack (17 mods)** | **20 %** | **20 %** | **20 %** |
| **Lanterne** | **100 %** | **100 %** | **85 %** |

**Le modpack réduit les chutes à un cinquième de leur vitesse, y compris à seize blocs du joueur.**
Une ferme à chute y produit cinq fois moins — et le joueur l'attribuera à autre chose.

Son ×5,0 est donc payé, en partie, avec du rendement de jeu. Lanterne a fait la même erreur, et
l'épreuve de conformité l'a rattrapée : la correction lui a coûté la première place au chronomètre.
C'est un arbitrage assumé — **un mod d'optimisation qui casse une ferme a échoué, même à ×10**.

### Mémoire et réseau

| | Vanilla | Modpack | **Lanterne** |
|---|---:|---:|---:|
| Mémoire allouée (25 s) | 9,5 Go | 1,9 Go | **2,4 Go** |
| Ramassages | 27 | 9 | **5** |

Sur un VPS à un cœur, la mémoire n'est pas un confort : un ramassage n'y tourne pas « en
parallèle », il **fige le serveur**.

## La thèse

Lithium rend chaque système du jeu plus rapide, et le fait bien. Lanterne pose une autre question :
**pourquoi ce travail a-t-il lieu ?**

Une vache à 150 blocs coûte exactement autant qu'une vache sous les yeux du joueur. Le jeu ne connaît
que deux états — chargé, ou pas chargé — et cette absence de nuance est la source principale de
dépense sur une instance chargée.

C'est le principe du niveau de détail, vieux de trente ans dans le rendu 3D, absent de la simulation
de Minecraft.

## Pourquoi un mod unique bat vingt mods

Ce n'est pas une question de qualité de code — les mods existants sont bons. C'est structurel.

| Défaut | Ce qu'il coûte |
|---|---|
| **Scans redondants** | Quatre modules parcourent les entités chaque tick pour calculer la même distance |
| **Le JIT ne peut plus inliner** | Chaque mixin empilé sur une méthode chaude transforme un appel inlinable en chaîne d'appels virtuels |
| **Décisions contradictoires** | L'un ralentit un mob, l'autre saute ses objectifs, le troisième le supprime. Aucun ne sait ce que l'autre a décidé |
| **Aucun budget global** | Chacun optimise avec une sévérité fixée dans un fichier, sans savoir combien de millisecondes il reste dans le tick |

Le dernier point est impossible à corriger sans unifier :
**on ne peut pas répartir un budget qu'on ne connaît pas.**

## Les quatre garanties

1. **Rien n'est dégradé près d'un joueur.** En deçà de 24 blocs, le jeu est le jeu.
2. **Rien n'est jamais arrêté**, seulement espacé.
3. **Ce qui produit garde sa cadence.** Villageois et pillards ne descendent jamais sous 1 tick sur
   4 : une ferme à villageois continue de travailler, de restocker et de se reproduire.
4. **Ce qui dépend d'une trajectoire ou d'un compte à rebours n'est jamais touché** — projectiles,
   TNT amorcée, blocs qui tombent, véhicules montés.

Et un cinquième qui commande les autres : **quand le serveur va bien, le mod ne fait presque rien.**

---

## Architecture

| Pièce | Rôle |
|---|---|
| `Cadence` | La formule : combien de ticks entre deux réveils, et pour qui |
| `Census` | Le recensement — **par chunk, pas par entité** |
| `Crowd` | La densité — ce qui est noyé dans le nombre ne se distingue pas |
| `TickBudget` | Mesure le tick réel, en tire une pression de 0 à 1 |
| `EntityThrottle` | Applique, via `EntityTickEvent.Pre`. **Zéro mixin sur le chemin chaud** |
| `NetworkThrottle` | Le même gradient appliqué aux paquets de position |
| `ProfilerMixin` | Cache le profileur vanilla, qui coûtait 16 % du processeur |
| `LivingEntityMixin` | Plafonne les poussées dans les tas d'entités (coût quadratique) |

### Trois décisions qui portent tout

**Recenser des chunks, pas des entités.** L'approche naïve calcule la distance de chaque entité à
chaque joueur : 100 000 entités × 5 joueurs = 500 000 calculs par tick. Mais deux vaches du même
chunk sont à 16 blocs près à la même distance. À 10 chunks de simulation : **441 calculs au lieu de
500 000.**

**Une droite, pas des paliers.** La première version dégradait par tranches — plein, ½, ¼, ⅛. Une
créature à 100 blocs tombait dans « un tick sur quatre » et y restait jusqu'à 112. Un palier est un
compromis figé : trop prudent en haut de tranche, trop brutal en bas.

**L'étalement.** La version évidente serait `tick % period == 0`. Elle est fausse, et de la pire
façon : toutes les entités d'une même cadence se réveilleraient **dans le même tick**. La charge ne
baisserait pas, elle se **concentrerait**.

---

## Le laboratoire

Un mod d'optimisation qui ne se mesure pas ne vaut rien. Celui-ci embarque de quoi se réfuter.

```bash
LANTERNE_SELFTEST="4000:120:1" ./gradlew runServer      # entités:rayon:joueurs
LANTERNE_MODULES="lod,density" ./gradlew runServer      # un module à la fois
```

En jeu : `/lanterne`, `/lanterne bench`, `/lanterne on|off`.

| Outil | Ce qu'il apporte |
|---|---|
| `Bench` | Comparaison A/B, médiane, 200 ticks de chauffe |
| `Sampler` | Profileur par échantillonnage, avec vue « qui appelle qui » |
| `Understudy` | **De vrais joueurs simulés** — ni client, ni réseau |
| `Herd` | Charge reproductible, répartie en spirale |

`Understudy` est la pièce qui rend tout le reste possible : de vrais `ServerPlayer`, inscrits dans la
liste du serveur, avec une connexion qui absorbe les paquets. Cent doublures coûtent ce que
coûteraient cent joueurs — sur une machine qui n'en héberge aucun.

Le verdict est formulé pour être vérifiable, pas pour flatter : **un gain sous 5 % est annoncé comme
du bruit**, et une perte comme une perte. Le banc l'a fait six fois pendant le développement.

---

## Six erreurs, et ce qu'elles ont coûté

Ce projet a été construit contre ses propres intuitions. Les six premiers bancs ont tous rendu un
verdict négatif, et chacun avait raison.

**La compensation des ticks aléatoires.** Ne tirer qu'une fois sur seize, mais seize fois plus. La
loi est préservée — c'est exact — et le gain est **nul** : même nombre total de tirages. Abandonnée
avant la première ligne de code, sur une multiplication.

**Le ralentissement des entonnoirs.** Un entonnoir tické une fois sur seize transfère seize fois
moins d'objets, et les chunks concernés sont dans le rayon de simulation — ce sont des fermes *en
marche*. On ne les aurait pas optimisées, on les aurait cassées.

**Le débordement qui neutralisait tout.**

```java
if (tick - lastRefresh < REFRESH_PERIOD) return;   // lastRefresh = Long.MIN_VALUE
```

`100 - Long.MIN_VALUE` déborde et redevient négatif. La condition était toujours vraie, et **le
recensement ne s'est jamais exécuté** — de la première ligne jusqu'au sixième banc. Le banc l'a dit
en une ligne : `chunks recensés : 0`.

**Les entités qui s'évaporaient.** Trois bancs de suite ont rendu « entités dans le monde : 0 »
après en avoir créé cinq mille. `TicketType.PLAYER_SIMULATION` ne charge pas les chunks : son
troisième paramètre n'est pas un niveau mais un **champ de bits**, et `12 = 8|4` n'a pas le bit 2,
celui du chargement. Un vrai joueur pose *deux* tickets.

**Un `values()` dans le chemin chaud.** Introduit dans la méthode même qui venait d'apporter la
moitié du gain. `Enum.values()` clone son tableau à chaque appel — cent mille tableaux jetables par
seconde. Repéré en relisant l'état de l'art, pas en relisant le code.

**Un banc qui mesurait autre chose.** Trois défauts successifs du laboratoire ont produit des
chiffres flatteurs et faux. Les observateurs n'étaient pas de vrais joueurs, puis l'étaient mais en
spectateur — que le recensement ignore — puis n'appartenaient à aucun monde. Dans les trois cas le
recensement voyait un serveur vide et **tout** était ralenti au maximum, y compris ce qui touchait le
joueur. Un ×10,5 a été publié sur cette base ; il ne valait rien.

**Le plus coûteux : ce qui tombe.** La gravité s'accumule — `v += g` puis `y += v` — donc ticker une
créature une fois sur quatre ne divise pas sa chute par quatre **mais par seize**. Toutes les fermes
à monstres reposent sur une chute. Corriger cela a ramené le gain de ×10,5 à ×3,6 : le ×10,5 n'était
pas un gain, c'était une dégradation non mesurée.

La leçon vaut au-delà du cas : **un banc qui ne reproduit pas les conditions réelles mesure autre
chose que ce qu'on croit, et il le fait sans prévenir.**

---

## Ce qui vient

| Objet | Attendu |
|---|---|
| Blocs-entités — ce qui est cosmétique peut s'espacer ; ce qui a un débit, non | Modéré, sans risque |
| Redstone — moteur de propagation (approche *Alternate Current*) | ×10-100 sur gros circuits |
| Ticks aléatoires — indexer les positions actives par section | À prouver avant de promettre |
| Collisions — hiérarchie adaptative plutôt que grille uniforme | Piste non exploitée par l'état de l'art |
