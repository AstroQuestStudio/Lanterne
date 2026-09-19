# Notes d'ingénierie — le banc, ses défauts, et les modules qui ont perdu

Ce fichier n'est pas une deuxième documentation : c'est l'annexe de [README.md](README.md) pour qui
veut le détail méthodologique complet plutôt que le résumé qui y est gardé. Trois chapitres, déplacés
ici pour que le README reste une vitrine lisible sans rien effacer de ce qui a été mesuré.

---

## Le laboratoire, en détail

### Dix bancs qui mentaient, et comment on l'a su

| Le banc disait | La vérité | Ce qui l'a révélé |
|---|---|---|
| TNT : **perte ×0,50** | ×1,64 de **gain** | La phase 1 creusait le décor de la phase 2 |
| Projectiles : **×4,04** | ×2,99 | Le module supprimait le combat |
| Client : **perte ×0,86** | ×1,25 de **gain** | Monde vide, puis phases inégales |
| Fluides : **perte ×0,47** | Non mesurable | ×0,10 **sans le mod des deux côtés** |
| Village : ×1,19 | ×1,90 | Les décors s'accumulaient depuis des mois |
| Génération : ×15 | ×1,0 | Il relisait le disque au lieu de générer |
| Entonnoirs : **débit ÷2** | Débit **exact** | 3 exécutions identiques : 18/30, 39/16, 16/16 |
| Le monde entier : ~20 ms | ~10 ms | `random_tick_speed` resté à **256** depuis une autre charge |
| Chaîne de 6 trémies : **288 944 objets** | 19 | Elle mesurait le chantier d'un autre banc |
| Fusion au sol : ×1,08 | ×1,42 | Le nettoyage du décor semait 5 175 objets |

**Le tir de contrôle** — lancer le banc avec le mod éteint **des deux côtés** — est devenu la
première chose à faire devant un résultat surprenant. Sur les fluides, il a rendu ×0,10 alors que rien
ne changeait : le banc mesurait la gigue de sa propre méthode.

### Le défaut qu'aucun rapport ne montrait

Les quatre derniers ont une famille commune : **ils ne se voyaient pas dans un résultat, mais dans un
chiffre trop régulier.**

`clearDecor` annonçait avoir effacé 58 081 blocs, puis 58 963 — identiques à un pour cent près, alors
que douze mille cinq cents blocs de chantier auraient dû s'y ajouter. Un nettoyage qui ne voit pas ce
qu'il devrait voir.

La cause : l'altitude du chantier était gelée en lisant la carte des hauteurs **en (0,0)** — c'est-à-dire
au milieu du chantier précédent, où elle répond l'altitude d'un coffre. Le sol montait donc de trois
blocs à chaque exécution, le nettoyage passait **au-dessus** des ruines, et les charges s'empilaient.

> C'est la même faute que la dalle de pierre qui montait de quatre blocs par reconstruction, revenue
> ailleurs sous un autre visage. **Le sol se demande maintenant au générateur de terrain**, pas au
> monde : une propriété du relief, que rien de ce qu'on bâtit dessus ne peut déplacer.

---


---

## 🗑️ Treize modules écrits, mesurés, puis retirés

C'est la partie du projet dont il est le plus fier.

| Module | Pourquoi il devait marcher | Pourquoi il ne marchait pas |
|---|---|---|
| **Tirage aléatoire** | 12 % du profil | **Artefact d'attribution** — 151 M de raccourcis, 0 ms |
| **Formes d'entité** | 1 M d'allocations par tick | Le JIT les éliminait déjà (analyse d'échappement) |
| **Saut des sections vides** | 30 % dans `PalettedContainer` | Vanilla fait déjà le test, plus finement |
| **Répartiteur de chunks** | Le tuyau est large de un | 39 633 voies ouvertes, **aucun effet** |
| **Visibilité des explosions** | Coût quadratique | **0 déclenchement sur 3 019 explosions** |
| **LOD client** | Sodium ne touche pas au tick | **Le tick produit l'état du rendu** — crash |
| **Recherche d'eau** | 162 positions par appel | Décor résiduel — l'eau n'était jamais trouvée |
| **Entonnoirs endormis** | Sept ticks sur huit ne font rien | Vrai, et sans valeur : ces sept ticks ne font qu'une décrémentation |
| **Repos posé** | 15 % du profil part en gravité | Retiré **deux fois** — voir ci-dessous |
| **Chute libre** | `Entity.move` = 52 % du profil TNT | **0 balayage évité sur 3 437 405** — granularité |
| **Bordure retenue** | 2,9 % du profil TNT | **3,9 M de recherches épargnées, et 4 ms de PLUS** |
| **Tableau vide des effets** | **44 % des allocations** (2,70 Go) | 2,4 M évités = **39 Mo**, pas 2 700 |
| *…et cinq autres* | | |

### Le profileur d'allocations ment aussi

Le profileur de temps attribuait 3 % à `applyEffectsFromBlocks` — la signature exacte d'un artefact,
donc à écarter. Le profileur d'**allocations** en donnait une tout autre lecture :

```
44,1 %  InsideBlockEffectApplier$StepBasedCollector.flushStep → Object[]   2,70 Go
```

La cause était réelle : `ArrayList.addAll` appelle `toArray()` **avant** de regarder si la source est
vide, et `Arrays.copyOf(données, 0)` alloue. Deux appels par type d'effet, par pas, par entité — pour
des toiles et des buissons qui ne sont presque jamais là.

```
2 448 370 tableaux non fabriqués
mémoire : 2,92 → 2,90 Go     (0,7 %)
temps   : aucun effet
```

**L'arithmétique qu'il fallait poser avant de coder :** 2,4 M × 16 octets = **39 Mo**. Le profileur en
annonçait 2 700. Il s'est trompé d'un facteur **soixante-dix**.

> `ObjectAllocationSample` **échantillonne et extrapole** : le poids vient du taux d'échantillonnage,
> pas de la taille des objets. Un site qui alloue de *tout petits* objets *très souvent* y est
> surévalué d'ordres de grandeur.
>
> Ce projet se méfiait du profileur de temps sur les méthodes courtes et très appelées. Il se méfie
> maintenant du profileur d'allocations sur les objets minuscules et très nombreux. **Dans les deux
> cas, le correctif est le même : multiplier le compte par la taille avant d'écrire une ligne.**

### Trois fois la même leçon

`ServerLevel.getWorldBorder()` consulte les données de monde **à chaque appel**, pour un objet
identique pendant toute la partie — sur un chemin parcouru une fois par entité et par tick. Le
profileur lui attribuait **2,9 %**.

```
3 877 401 recherches épargnées
sans : 48,39 et 48,34 ms   ·   avec : 52,83 ms
```

Près de quatre millions de consultations supprimées, et le banc **monte de quatre millisecondes**.

> Un poste élevé sur une méthode **courte et très appelée** est un artefact d'attribution jusqu'à
> preuve du contraire. Et y poser un mixin lui **retire son inlinisation** : le coût du point
> d'accroche dépasse celui du travail qu'on y supprime — **même quand on en supprime quatre
> millions**.

### Celui qui a été tranché en une exécution

Le profil de 6 000 TNT désignait `Entity.move` à **52 % du tick** — et l'explosion elle-même à 1,5 %.
Chaque TNT qui tombe lit huit états de bloc, en tire huit formes, les fusionne et résout axe par axe,
pour découvrir qu'elle traverse de l'air.

Or `LevelChunkSection.hasOnlyAir()` est un compteur déjà tenu à jour. Une boîte entièrement dans des
sections vides ne peut heurter aucun bloc : la certitude coûtait deux lectures de champ.

```
0 balayage évité sur 3 437 405 examinés   →   taux 0,0 %
```

**La granularité du cache ne correspond pas à celle de la question.** `hasOnlyAir()` porte sur une
section de seize blocs de côté ; la boîte d'une entité en fait un. Une TNT qui tombe près du sol est
dans la même section que le sol. Le raccourci ne pouvait servir qu'à ce qui tombe en plein ciel.

> Sans le compteur de taux, on aurait lu « ×1,61 contre ×1,76 » et conclu « c'est le bruit » — vrai,
> et pour la mauvaise raison. **Le taux vaut zéro, et zéro multiplié par n'importe quoi vaut zéro.**

### Celui qui a été retiré deux fois

Une bête posée sur un cube plein ne peut pas tomber : sa collision pouvait être **conclue** au lieu
d'être calculée. Le raisonnement était juste ; le verdict ne l'a pas été.

**Premier refus** — taux de déclenchement **4,5 %**, coût net d'une milliseconde. La cause n'était pas
le code mais l'état du monde : dans un tas, les bêtes se poussent en permanence et rien n'est jamais
immobile.

**L'enclos a corrigé cette cause.** 915 bêtes sur 1000 se sont posées, le taux est monté à **26 %**.
Quatre paires de mesures entrelacées ont tranché quand même :

```
sans : 6,21 · 7,21 · 8,25 · 6,75   moyenne 7,11 ms
avec : 6,37 · 6,48 · 8,29 · 7,97   moyenne 7,28 ms
```

Trois fois sur quatre dans le mauvais sens.

> **La règle, et elle est arithmétique.** Le rapport a fini par publier le bon chiffre : **157 appels
> à `collide` par tick**, une fois la cadence appliquée. Le raccourci portait sur quarante ; son point
> d'accroche se payait sur cent cinquante-sept.
>
> La valeur d'un raccourci est le produit de **trois** nombres : son taux de déclenchement, ce qu'il
> épargne, et le nombre d'appels. La première publication disait « 22 674 collisions épargnées » — un
> grand nombre qui cachait les deux autres.

### L'enclos, ou comment un module en débloque trois

Trois modules attendaient que les bêtes s'immobilisent, et aucun n'y arrivait. `Jam` exige vingt
ticks d'immobilité consécutifs et n'en trouvait que **152 sur 1000**.

La cause était en amont des trois : **les bêtes décident d'aller se promener.** Le compteur
d'immobilité se remet à zéro, l'amas ne se déclare jamais figé, et plus rien ne peut se poser.

Or une vache au milieu d'un enclos plein qui décide de marcher fait calculer un chemin, le suit, se
fait repousser par ses voisines, et **finit là où elle était**. Le résultat observable est identique ;
seul le calcul disparaît.

Le critère ne suppose rien sur la forme du lieu — pas la densité, qui aurait figé douze bêtes
parfaitement libres réparties sur un chunk. **Cette bête est-elle allée quelque part ?** On note sa
position, on revient cent ticks plus tard. Moins de deux blocs en cinq secondes, elle cesse de
décider. Dès qu'elle en parcourt deux, elle recommence.

> Aucune barrière à surveiller : **bouger est sa propre preuve.**

```
amas figés   121 / 162  →  372 / 422      Jam se déclenche 3× plus
915 bêtes figées sur 1000 · 23 649 décisions d'errance épargnées
```

### Celui qui est revenu

Le module des entonnoirs a été retiré une première fois sur un verdict sans appel — *« 47 objets sans
le mod, 22 avec »*, le débit divisé par deux. **Ce chiffre venait d'un instrument que le projet avait
lui-même déclaré non reproductible** : trois exécutions identiques rendaient 18/30, 39/16, puis 16/16.

La cause tenait dans le correctif précédent. Casser une trémie n'efface pas son contenu, il le
**relâche** — précisément là où la boucle suivante reposait les trémies, qui le ré-aspiraient.

Réparé, l'instrument rend trois fois le même chiffre à l'objet près. Et la bonne réponse n'était pas
d'endormir l'entonnoir mais de lui faire porter **seize objets par recherche de conteneur au lieu
d'un**, en payant seize fois la recharge : débit moyen identique, travail divisé par seize.

> Vanilla fait déjà cela pour un objet ramassé au sol — `addItem(Container, ItemEntity)` avale une
> pile entière de 64 pour la même recharge de 8 ticks. La règle « un objet à la fois » n'est pas une
> loi du jeu : c'est une particularité du transfert entre conteneurs, que vanilla lui-même enfreint.

### Les deux règles que ces échecs ont installées

> **1.** Un poste élevé sur une méthode **très courte et très appelée** doit être suspecté d'artefact
> avant d'être attaqué. L'échantillonneur ne voit que le sommet de pile, et le JIT inline.

> **2.** Compter des **allocations évitées** ne prouve rien tant qu'on n'a pas mesuré le tas. Deux
> fois, ce projet a supprimé ce que la machine virtuelle supprimait déjà.

---


---

## 🧾 Les trois règles d'instrument, et ce qu'elles ont coûté à apprendre

Ce laboratoire a deux profileurs. **Les deux mentent sur les extrêmes, et pour des raisons
symétriques.**

| | Ce qu'il voit | Où il se trompe | Prix payé |
|---|---|---|---|
| **Temps** *(sommet de pile)* | Où est le processeur | Une méthode **courte et très appelée** monte haut sans porter le temps qu'on lui prête — le JIT l'inline et elle absorbe ses voisines | 3 modules |
| **Allocations** *(JFR)* | Qui alloue | Il **échantillonne et extrapole** : un site qui alloue de **tout petits** objets **très souvent** est surévalué d'ordres de grandeur | 1 module, facteur **70** |

Et un piège commun aux deux : **y poser un mixin retire l'inlinisation**. Le coût du point d'accroche
peut dépasser celui du travail supprimé — la bordure du monde a coûté **4 ms** pour 3,9 millions de
recherches économisées.

> **Le correctif est le même dans les deux cas : multiplier le compte par la taille avant d'écrire
> une ligne de code.** 2,4 M de tableaux × 16 octets = 39 Mo, pas 2 700.

### La troisième, apprise en septembre 2026

Le profileur d'allocations désignait un poste à **10,5 %**, soit 1,88 Go — la boîte vide que le
cerveau fabrique à chaque test de condition. Le module écrit pour la partager a rendu **vingt
mégaoctets** par fenêtre de mesure, et ×1,04 : sous la dérive du banc.

L'écart n'était pas une erreur de mesure mais une erreur de lecture. Le profileur classe par **volume
cumulé depuis l'ouverture de l'enregistrement** — chargement du serveur, décantation et les *deux*
phases entrelacées comprises. Ses 17,99 Go de total ne sont pas le travail d'un tick.

> **Un pourcentage n'a de sens que rapporté à ce qui a été totalisé.** « Dix pour cent des
> allocations » désignait un poste réel, et ce poste pèse quatre pour cent d'une fenêtre de mesure.

---

