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
