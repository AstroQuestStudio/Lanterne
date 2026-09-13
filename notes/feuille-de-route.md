# Feuille de route — nuit du 13 septembre 2026

Objectif fixé : couvrir **tout** le spectre, pas seulement les deux cas cités en exemple
(1000 vaches en 15×15, 1 M d'objets au sol). Ceux-ci sont des échantillons.
Cible : 5 ms/tick au lieu de 25, démarrage ×2-3, RAM en rupture, client compris.

Règle qui commande tout : **on mesure avant, on mesure après, et on publie le refus quand le
chiffre ne tient pas.** Huit verdicts négatifs de ce banc se sont révélés justes ; aucune
intuition de ce projet ne l'a été sans mesure.

## Inventaire du spectre — tout ce qui consomme sur un serveur

| # | Domaine | Reproduit au banc ? | État |
|---|---------|--------------------|------|
| 1 | Créatures dispersées (gradient de distance) | oui — scène `ring` | mesuré, ×5 |
| 2 | Créatures entassées (bousculade quadratique) | oui — scène `pen` | **à mesurer** |
| 3 | Objets au sol immobiles | oui — scène `items` | **à mesurer** |
| 4 | Blocs-entités à échéance (fours) | oui — épreuve cuisson | exact au tick |
| 5 | Trémies / transfert d'objets | oui — chaîne d'entonnoirs | échec assumé, retiré |
| 6 | Explosions / dynamite | non — détruit le terrain qu'elle mesure | banc à charge reconstituée |
| 7 | Fluides qui coulent | non — se stabilise en quelques secondes | banc à charge reconstituée |
| 8 | Ticks aléatoires (cultures, feuilles, neige) | partiellement | gain nul démontré |
| 9 | Génération de chunks | non | à construire |
| 10 | Sauvegarde des chunks | non | à construire |
| 11 | Moteur de lumière | non | 13 % des allocations, jamais attaqué |
| 12 | Réseau / paquets | oui — compteur de paquets | mesuré |
| 13 | Occupation mémoire (et non débit d'allocation) | non | à construire |
| 14 | Démarrage du serveur | oui — chronomètre | 0,45 s, rien à gagner côté serveur |
| 15 | Client (images par seconde) | non | outillage inexistant |
| 16 | Pathfinding / IA des créatures | non | à construire |
| 17 | Villageois / points d'intérêt | non | à construire |
| 18 | Wagonnets, projectiles, entités mobiles | non | à construire |
| 19 | Redstone | non | hors garanties (casse les duplicateurs) |
| 20 | Apparition naturelle des créatures | partiellement | mixin posé |

## Ordre d'attaque retenu

1. **Scènes `pen` et `items`** — construire, mesurer, puis optimiser ce que le profil désigne.
2. **Bancs à charge reconstituée** — débloque explosions, fluides, génération, sauvegarde.
3. **Occupation mémoire** — mesurer le tas retenu après ramassage forcé, pas le débit.
4. **Ce que les profils désignent** — et rien d'autre.

## Journal

- 03:31 — inventaire ci-dessus. Dossier `mods/` nettoyé (8 jars → 2).
- 03:45 — `Scene.java` : charges `ring`, `pen`, `items`, `mixed`. Sources vanilla extraites
  dans `.mcsrc/` (6 596 fichiers) pour pouvoir vérifier chaque nom de méthode au lieu de le
  deviner.
- 03:50 — `ItemEntity.tick()` lu en entier. Confirmé : `Player.java:503` fait
  `entity.playerTouch(this)`, donc **c'est le joueur qui ramasse, pas l'objet qui se fait
  ramasser**. Un objet endormi reste donc ramassable — le sommeil des objets est possible
  sans rien casser.

## Ce que la nuit a trouvé — journal des mesures

### Le défaut le plus grave, et il était là depuis le début

**Lanterne détruisait le rendement des fermes éloignées.** Les compteurs de production ne sont pas
rangés à part dans le jeu : ils vivent au milieu de l'intelligence, dans les `aiStep()` des
sous-classes.

```
Chicken.aiStep    : if (--this.eggTime <= 0) { ... pond un œuf ... }
AgeableMob.aiStep : if (this.canAgeUp()) this.setAge(++age);
Animal.aiStep     : if (this.inLove > 0) this.inLove--;
```

Annuler le tick d'une créature — ce que le mod faisait pour aller vite — arrêtait ces horloges.
Mesuré par la nouvelle épreuve `Yield`, à 120 blocs d'un joueur :

| | pontes | croissance |
|---|---|---|
| sans compensation (état du mod avant cette nuit) | **3 %** | **19 %** |
| avec compensation | **100 %** | **100 %** |

Aucune des trois épreuves existantes ne pouvait le voir : la conformité mesure les chutes, la
cuisson les fours, le banc la vitesse. Personne ne mesurait la **production**.

### Trois chiffres faux corrigés dans l'outillage

1. **Le profileur diluait tout dans l'attente du serveur.** 76 % des relevés surprenaient le serveur
   endormi ; tous les postes étaient donc divisés par quatre. `EntitySection.getEntities` passait
   pour 5,6 % alors qu'il pesait 24 % du travail réel.
2. **Le rapport décrivait la phase témoin.** Le verdict s'écrit à la fin de la phase 2, mod éteint —
   il lisait donc des compteurs de cadence remis à plat. Il annonçait « travail évité : 4,5 % » là où
   la mesure réelle est 72 %.
3. **Une fausse alerte, rectifiée.** J'ai cru que les « 26,9 Go » du README décrivaient la mémoire occupée, mesurée au sommet de la dent de scie. Relecture faite : la colonne dit « Mémoire **allouée** », et il s'agit bien du débit d'allocation sur vingt-cinq secondes — que la mesure de cette nuit confirme à 25,81 Go. Le README était juste ; c'était ma synthèse qui était ambiguë. Le banc sait désormais mesurer les **deux**, et l'occupation réelle après ramassage est un chiffre neuf : 446,7 Mo sans le mod, 376,9 Mo avec.

### Gains mesurés cette nuit — charge « enclos » (1000 vaches en 15×15)

| étape | avec Lanterne | gain |
|---|---|---|
| état initial | 11,01 ms | ×2,53 |
| plafond de la recherche de poussées (`Shove`) | 12,44 ms | ×2,44 — **neutre, rejeté puis corrigé** |
| court-circuit des collisions d'entités (`Solid`) | **10,01 ms** | **×3,01** |
| architecture sélective (préservation stricte) | 14,31 ms | ×1,71 — **gardée en option** |

`Solid` repose sur un fait vérifié dans le code : `Entity.canBeCollidedWith()` rend **faux par
défaut**, et trois classes seulement le redéfinissent dans tout Minecraft (bateau, shulker, ghast
apprivoisé). Mille vaches font donc un million de tests d'intersection par tick pour obtenir mille
listes vides.

### Le banc ne finissait pas — quatrième défaut d'outillage

La charge « huit mille objets au sol » a révélé une hypothèse jamais écrite : le protocole demandait
deux cents ticks de chauffe puis cinq cents de mesure, ce qui suppose qu'un tick dure cinquante
millisecondes.

Phase témoin, mod éteint : **cent trente-cinq secondes par tick**. Sept cents ticks font alors
vingt-six heures. Le banc ne rendait pas un chiffre faux — il ne rendait rien du tout, ce qui est
pire : deux exécutions perdues avant de comprendre qu'il n'y avait rien à attendre.

Trois corrections, toutes nécessaires :
- la phase de mesure s'arrête à l'échéance (90 s) dès qu'elle a quinze relevés ;
- la **chauffe** s'arrête aussi (30 s) — l'oubli initial laissait cinq heures et demie avant le
  premier relevé ;
- la médiane ne porte que sur les cases remplies. Sans cela, les zéros du tableau auraient donné une
  médiane nulle, donc un gain infini, annoncé avec le même aplomb que les autres.

Le rapport dit désormais combien de relevés ont servi.

### Le sommeil des objets — deux bugs avant de fonctionner

**Bug 1 : la condition de vitesse ne se déclenchait jamais.** Un objet posé au sol n'a jamais une
vitesse nulle en vanilla : `applyGravity()` lui retire 0,04 en y à chaque tick, et le sol ne les lui
rend qu'un tick sur quatre, quand `move()` a effectivement lieu. Le critère est devenu le
**déplacement réel**, quantifié au centième de bloc — le même raisonnement que pour les amas.

**Bug 2 : le compteur comptait les mauvais ticks.** Il s'incrémentait à chaque passage, mais la
dégradation par densité ralentit déjà un tas d'objets à un tick sur seize. Atteindre quarante-cinq
demandait donc 720 ticks de jeu. Le seuil se mesure désormais en **temps de jeu**.

Résultat sur 8000 objets, profil mod actif : `EntitySection.getEntities` **disparu** du profil,
temps de travail divisé par 4,5 (3305 → 730 relevés), serveur endormi 83 % du temps.

### Le court-circuit de collision, généralisé

Le premier mixin ne visait que `Entity.collide`, là où le profil de l'enclos le désignait. La charge
des objets a montré un tout autre chemin : `ItemEntity.tick` → `noCollision` → `noEntityCollision`
→ `getEntityCollisions`, pour **88 %** du travail.

`EntityGetter.getEntityCollisions` est le goulot où tout converge — `CommonLevelAccessor` y délègue
explicitement. Un seul point d'accroche règle la question pour tous les appelants, connus et inconnus.

## Bilan de la nuit — 13 septembre 2026, 03:31 → 09:15

### Chiffres finaux, tous mesurés par l'auto-test

| Charge | Sans | Avec | Gain |
|---|---:|---:|---:|
| 10 500 entités, anneau de 160 blocs | 200,3 ms | **31,6 ms** | **×6,3** (était ×5,0) |
| 1 000 vaches dans 15×15 | 33,0 ms | **6,2 ms** | **×5,3** (jamais mesuré avant) |
| 8 000 piles d'objets au sol | 463,5 ms | **7,4 ms** | **×62,4** (jamais mesuré avant) |
| Sauvegarde de 64 chunks, deflate → lz4 | 125,3 ms | **33,4 ms** | **×3,7**, zéro code |

Conformité : chutes 100/100/93 % · fours exacts au tick · **rendement 100 %** · objets 0 décalage sur 40.

### Ce qui a été construit

**Optimisations** — `Solid` (court-circuit des collisions d'entités, fondé sur un fait vérifié :
`Entity.canBeCollidedWith()` rend faux par défaut et trois classes seulement le redéfinissent),
`Litter` + `Churn` (sommeil des objets au sol avec réveil événementiel par section),
`Produce` (les horloges de production tenues à la main pendant le sommeil),
`Shove` (recherche de poussées interrompue à la source), densité continue jusqu'à ×16.

**Outils** — `Scene`, `Yield`, `Tidy`, `Boom`, `Flow`, `Quarry`, `Vault`, `Glass` + `Pane`.
Le laboratoire passe de 7 à 15 outils.

### Chantiers ouverts, honnêtement

| Sujet | État |
|---|---|
| Banc des fluides | **corrigé** : il manquait de forcer les chunks du bassin, sans quoi aucun tick de fluide n'y est exécuté. La conformité est fiable (113 blocs des deux côtés, stable) ; le verdict de vitesse est rejeté par le banc lui-même (×1,58 puis ×3,33) |
| Banc des chunks | **réparé par appariement** : alternance chunk par chunk dans une seule grille. Génération « aucun effet mesurable » (37,2 vs 35,8 ms) et chargement ×0,89 — les deux verdicts sont enfin crédibles. Générer = 37 ms, relire = 6,8 ms |
| Débit d'une chaîne de trémies | **non reproductible** : 18/30, 39/16, 16/16 |
| Banc client `Glass` | écrit et câblé, **jamais exécuté** |
| Génération de chunks | mesurée à 36-44 ms/chunk — gisement énorme, non attaqué |
| `PalettedContainer.get` | 16,5 % du travail restant, profil devenu plat |

## La prochaine cible, déjà identifiée et chiffrée

Le profil de la charge de référence est devenu **plat** — plus aucun poste ne dépasse 17 %. C'est le
signe qu'on a épuisé les gisements structurels et qu'il faut passer aux optimisations ciblées, une
par une, à la façon de Lithium.

| Poste | Part du travail | Remarque |
|---|---:|---|
| `PalettedContainer.get` | 16,5 % | les lectures de bloc par entité : `onClimbable`, `checkInsideBlocks`, `getOnPos`, `updateFluidInteraction` |
| `ServerLevel.tickNonPassenger` | 6,8 % | la boucle elle-même |
| `ServerEntity.…lanterne$throttleUpdates` | 5,7 % | **notre propre code** — il recalcule `Cadence.forEntity` et `Census.distanceOf` que `EntityThrottle.shouldTick` vient de calculer dans le même tick |
| `EntitySection.getEntities` | 4,3 % | était à 24 %, réglé par `Solid` |

Le troisième est le plus tentant parce qu'il nous appartient. Il n'a **pas** été fait cette nuit, et
c'est délibéré : la correction demande de mémoriser la cadence par entité entre deux boucles
distinctes du tick, donc soit une table de hachage (qui remplace un calcul par une recherche — gain
incertain), soit un champ ajouté à `Entity` par mixin (plus rapide, plus invasif).

Aucune des deux n'a été mesurée. À 50 minutes de la remise, introduire un changement non mesuré dans
le chemin le plus chaud du serveur aurait contredit tout ce que ce projet défend.

### Une piste que le profil ne voit pas, et qui vaut plus que tout le reste

Générer un chunk coûte **36 à 44 ms** — presque un tick entier. Aucun banc de tick ne le montre,
parce qu'un monde pré-généré n'en génère aucun. C'est pourtant ce que vit un joueur qui explore, et
c'est le seul domaine où le mod ne fait encore **rien**.

### La cadence mémorisée — un gain probable, non attribuable

Le profil désignait notre propre code au deuxième rang : `NetworkThrottle.shouldSend` refaisait, pour
décider d'un envoi de paquet, le calcul que `EntityThrottle.shouldTick` venait d'achever dans le même
tick pour la même entité.

Deux champs posés sur `Entity` (et non une table : remplacer un calcul par un hachage aurait pu ne
rien rapporter, comme deux modules déjà retirés). Première tentative refusée au démarrage —
`IllegalClassLoadError` : une interface ordinaire ne peut pas vivre dans un paquet déclaré comme
paquet de mixins. Elle a été déplacée dans `core`, qui est d'ailleurs sa vraie place.

Mesures sur la charge de référence : **28,23 / 31,41 / 27,65 ms**, médiane 28,23, contre 31,59 avant
— une seule mesure.

**Le gain n'est pas attribué à ce module dans le README**, et c'est délibéré : comparer une médiane de
trois mesures à une mesure unique n'est pas une comparaison. Le chiffre publié (28,2 ms, ×7,1) est
simplement mieux étayé que le précédent. Le module est conservé parce que supprimer un calcul
redondant ne peut pas nuire, pas parce qu'on lui a prouvé un gain.

### Qui demande vraiment les lectures de bloc

Le profileur, doté d'un suivi multi-motifs capable de traverser toute la chaîne d'accès (palette →
section → chunk → monde), a répondu sur la charge de référence :

| Demandeur | Part du travail |
|---|---:|
| `BlockCollisions.computeNext` | **5,9 %** |
| `PathNavigationRegion.getBlockState` | 2,0 % |
| `LevelChunk.getFluidState` | 0,9 % |
| `ServerLevel.tickChunk` | 0,6 % |

Sur les 18,1 % de `PalettedContainer.get`, environ neuf sont identifiés ; le reste est diffus.

`BlockCollisions.computeNext` est le parcours des blocs pendant le déplacement d'une entité. C'est le
terrain de Lithium, qui y pose un chemin rapide évitant de matérialiser une forme de collision pour
les blocs entièrement pleins ou entièrement vides.

**Non attaqué, et délibérément.** Une erreur sur ce chemin ne produit pas un ralentissement : elle
fait passer une entité à travers le sol. Le gain envisageable — quelques pour cent — ne justifie pas
d'y toucher sans pouvoir répéter les mesures et les épreuves plusieurs fois. C'est la première cible
d'une prochaine session, avec le temps qu'elle mérite.

### La génération de chunks ne coûte rien au temps de tick

Le plus gros gisement apparent — 37 ms par chunk — vient d'être écarté, et par la mesure la plus
simple : brancher le profileur sur le banc de génération.

```
2510 relevés dont 205 pendant le travail (92 % du temps dormi)
17,6 %  Unsafe.unpark
 7,8 %  BlockableEventLoop.managedBlock
 3,9 %  Unsafe.park
```

Le fil du serveur **attend**. Ce qu'il fait pendant ce temps est de la coordination de fils, pas de la
génération de terrain : celle-ci s'exécute entièrement sur le pool de travail.

Trois conséquences, qui changent la façon de voir le sujet :

1. Les 37 ms mesurées sont une **latence**, pas un coût de processeur sur le fil principal. Le banc
   mesure « combien de temps avant que ce chunk soit prêt », et c'est bien ce qu'un joueur qui explore
   ressent — mais ce n'est pas du temps de tick.
2. **Optimiser la génération n'améliorerait pas les TPS.** Elle améliorerait la vitesse d'exploration,
   ce qui est un autre problème, réel mais distinct.
3. Le nombre de fils disponibles compte donc directement. Sur un VPS à deux cœurs, la génération est
   lente parce qu'il y a peu de travailleurs — pas parce que l'algorithme est mauvais.

C'est exactement le genre de conclusion qu'on ne peut pas atteindre en lisant du code, et qui évite
de passer une nuit à optimiser au mauvais endroit.

### Dix joueurs — la charge jamais mesurée, et deux refus avant d'y arriver

| 10 joueurs, 11 441 entités, 1 881 chunks | ms/tick | TPS |
|---|---:|:---:|
| Sans Lanterne | 222,2 | 4,5 |
| Avec Lanterne | **44,8** | 20 |

**×5,0** sur deux exécutions (×4,73 puis ×4,96). Réseau : 560 paquets par tick sans, 387 avec (×1,45).

Le contrôle préalable a refusé les deux premières tentatives — 18 entités vivantes sur 10 500, puis
962 — parce que dix doublures espacées de 500 blocs réclament 6 250 chunks que le gestionnaire de
distance ne livre pas dans le temps imparti. Deux corrections : le délai de chargement suit désormais
le nombre d'observateurs, et les joueurs sont posés à 128 blocs les uns des autres, ce qui décrit
beaucoup mieux un serveur réel.

**Un septième chiffre faux attrapé au passage.** Le compteur de paquets était publié en valeur
absolue et annonçait « ×0,93 » — le mod émettant *plus* que le témoin. La cause : la phase témoin
s'était arrêtée à l'échéance au bout de 401 relevés quand la phase active en faisait 500. Le mod avait
donc émis plus de paquets parce qu'il avait vécu plus de ticks, ce qui est exactement la preuve qu'il
fonctionne, présentée comme un défaut. Rapporté par tick, le chiffre devient ×1,45.

### Cinquante joueurs — le gain s'érode, et il faut le dire

| Observateurs | Sans | Avec | Gain | TPS obtenu |
|---|---:|---:|---:|:---:|
| 1 | 202,9 ms | 28,2 ms | ×7,1 | 20 |
| 10 | 222,2 ms | 44,8 ms | ×5,0 | 20 |
| 50 | 283,9 ms | 87,9 ms | **×3,2** | **11** |

**À cinquante joueurs, Lanterne ne tient pas les vingt ticks.** Le gain reste réel — un serveur à
3,5 TPS devient un serveur à 11 — mais ce n'est pas jouable.

La cause est structurelle : ce mod dégrade ce qui est **loin de tout joueur**. Plus il y a de joueurs,
moins il existe d'endroits éloignés de tous, et moins il reste de travail à éviter. Ce n'est pas une
défaillance du mod, c'est la limite de sa thèse — et elle se voit dès qu'on la mesure.

Deux conséquences pour la suite :
1. La cible assumée reste le **petit serveur**, ce qui était déjà le cahier des charges.
2. Pour aller plus loin à fort effectif, il faudrait des optimisations qui ne dépendent pas de la
   distance aux joueurs — c'est-à-dire du travail à la Lithium, système par système. `BlockCollisions`
   (5,9 %) en est le premier candidat.

### `BlockCollisions` : vanilla 26.1 a déjà absorbé Lithium

La cible désignée par le profileur (5,9 % du travail) a été examinée ligne par ligne, et **aucune
optimisation n'a été écrite** — pour une fois, la lecture a suffi à trancher.

`BlockCollisions.computeNext` contient déjà, en 26.1 :
- un cache du dernier chunk consulté (`cachedBlockGetter` / `cachedBlockGetterPos`) ;
- un chemin rapide pour le cube plein (`blockShape == Shapes.block()` → simple test d'intersection,
  sans construction de forme) ;
- le saut des coins du curseur (`cursorFaceType != 3`).

Et l'optimisation qui semblait rester — tester `isEmpty()` avant `move()` pour les blocs d'air — est
**déjà dans `VoxelShape.move`** :

```java
public VoxelShape move(double dx, double dy, double dz) {
    return this.isEmpty() ? Shapes.empty() : new ArrayVoxelShape(...);
}
```

Un shape vide déplacé ne coûte rien et n'alloue rien. Ces 5,9 % sont le coût incompressible du
parcours lui-même : une lecture d'état par bloc de la boîte englobante.

**À ne pas reprendre sans idée neuve.** Le seul angle restant serait de parcourir moins de blocs, ce
qui suppose de prouver qu'une entité ne bougera pas — c'est-à-dire le calcul qu'on cherche à éviter.

### La charge la plus réaliste, et le meilleur gain défendable

| 4 000 vaches en enclos + 4 000 objets au sol | ms/tick | TPS |
|---|---:|:---:|
| Sans Lanterne | 661,5 | 1,5 |
| Avec Lanterne | **32,8** | 20 |

**×20,2**, travail évité 93,2 %. C'est une base habitée : un élevage et un sol jonché en même temps,
ce que tout serveur finit par avoir. Sans le mod, un tick y dure deux tiers de seconde et le jeu est
perdu.

C'est le chiffre le plus représentatif du mod — plus que le ×62 des objets seuls, qui décrit un cas
extrême, et plus que le ×7,1 de l'anneau, qui décrit un cas facile.

### Le mécanisme le plus risqué, enfin couvert

`Solid` supprime les recherches de collision d'entités parce que trois classes seulement peuvent
bloquer dans tout Minecraft. Le raisonnement est vérifié. Mais s'il était mal implémenté, le symptôme
ne serait pas un ralentissement — ce serait **une créature qui traverse un bateau**, et aucune épreuve
existante n'aurait pu le voir.

L'épreuve des objets interroge donc le mécanisme directement : un bateau posé dans le monde doit
interdire le raccourci sur sa propre boîte, et le laisser possible ailleurs.

Les deux réponses sont correctes. Et les deux importent : un mécanisme qui refuserait toujours serait
sûr et parfaitement inutile.

### Le sommeil des objets, retiré — septième plan démoli, et le plus coûteux

C'était l'innovation phare de la nuit : quatre fichiers, deux mixins, un compteur de modifications par
section de chunk pour le réveil événementiel, une épreuve de conformité dédiée, et deux bugs corrigés.

Trois mesures l'ont écarté :

| | |
|---|---|
| module seul, 2 000 objets | 38,46 contre 38,84 ms → **aucun effet** |
| tout **sauf** lui, 8 000 objets | **×62,24** |
| tout **avec** lui, 8 000 objets | ×62,36 |

La cadence et le court-circuit de collision faisaient déjà tout le travail ; le sommeil n'arrivait
qu'après eux, sur un tick déjà supprimé.

**Ce que son retrait laisse en place**, et qui est la vraie trouvaille : en cherchant pourquoi la
disparition des objets était décalée — 152 ticks au lieu de 120 — on a découvert que la cadence
arrêtait leur horloge d'âge, comme elle arrêtait la ponte des poules. `Produce.age()` reste, et
l'épreuve `Tidy` confirme 0 décalage sur 40 objets **sans** le module de sommeil.

Retirer le module supprime aussi un mixin sur `LevelChunk.setBlockState` — sur chaque pose de bloc du
serveur. Un chemin très chaud, allégé pour de bon.

### Contribution mesurée de chaque module (charge mixte)

| Module seul | Gain |
|---|---:|
| `collisions` (`Solid` + `Shove`) | **×2,34** |
| `objets` (sommeil, retiré) | aucun effet |
| tous ensemble | ×20,2 |

### Contribution de chaque module, mesurée seul (charge « base habitée »)

| Modules actifs | Travail évité | Gain |
|---|---:|---:|
| `lod` seul | **0,8 %** | aucun (bruit) |
| `lod` + `densite` | **93,3 %** | **×10,7** |
| `collisions` seul | — | ×2,34 |
| tout | — | ×20 à ×30 |

**La thèse d'origine du mod ne fait rien là où les joueurs vivent.** « Dégrader ce qui est loin »
suppose qu'il y ait du loin ; un élevage et un sol jonché sont à vingt blocs du joueur, dans la zone
franche que le mod s'interdit de toucher.

Ce qui porte le gain est la **densité**. Et c'est ce qui justifie, rétrospectivement, le changement le
plus important de la nuit : la dégradation par densité n'a pu passer de ×4 à ×16 qu'une fois les
horloges de production tenues à la main. Sans `Produce`, ce facteur 16 aurait divisé par seize la ponte
d'une ferme à œufs.

### Le client démarre avec le mod

Vérification jamais faite, et pourtant essentielle : le mod est chargé côté client aussi. `runClient`
atteint le menu principal — OpenAL initialisé, moteur sonore démarré, atlas de textures construits,
aucune exception. La classe `Pane`, seul point de contact avec le code de rendu, est bien isolée
derrière `@EventBusSubscriber(Dist.CLIENT)` et n'empêche pas le serveur dédié de démarrer.

### Le plafond de densité monté à ×32

Puisque la densité porte tout le gain, son plafond méritait d'être poussé. Chaque montée a été mesurée
avant d'être gardée, et suivie des épreuves de conformité :

| Plafond | Résultat sur la base habitée | Rendement | Chutes |
|---|---|---|---|
| ×4 | ×3,0 sur l'élevage | intact | conforme |
| ×16 | travail évité 93,3 % | intact | conforme |
| **×32** | **26,89 ms**, travail évité **96,1 %** | **intact** | **conforme** |

Sur quatre mille bêtes entassées, cent vingt-cinq décident encore à chaque tick : le troupeau grouille.
Ce qui rend ces paliers défendables est `Produce` — sans lui, un facteur trente-deux aurait divisé par
trente-deux la ponte d'une ferme à œufs.
