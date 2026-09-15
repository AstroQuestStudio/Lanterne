# Régler un serveur qui n'a qu'un cœur

Ce guide vise la machine de production annoncée : un **Xeon Gold 6230R limité à un cœur**, alors que
tout le développement et toutes les mesures de ce dépôt se font sur un Ryzen 7 5800H à huit cœurs.

## Ce que la mesure a établi, et qui change la façon de régler

Le banc a été relancé avec le processus serveur **épinglé sur un seul cœur**. Vanilla y fait
**32,60 ms** par tick là où il en fait **47,34** sur huit cœurs — c'est-à-dire qu'il n'est pas plus
lent.

La conclusion est nette et elle oriente tout le reste : **sur une charge d'entités, le serveur est
mono-thread de toute façon**. Les sept autres cœurs ne le sauvaient pas. Ce qui compte est le
travail accompli par tick, et le ramasse-miettes — qui, sur un cœur unique, ne tourne plus *à côté*
du tick mais le lui **prend**.

D'où l'importance des chiffres d'allocation, qu'on aurait pu croire secondaires :

| 600 villageois, épinglé sur un cœur | Sans | Avec | |
|---|---:|---:|:---:|
| Temps par tick | 32,60 ms | 20,63 ms | **×1,58** |
| Mémoire allouée par fenêtre | 484 Mo | 271 Mo | **×1,79** |

## Les réglages

### `server.properties`

```properties
view-distance=10
simulation-distance=10
```

Laissez-les à la valeur que vous voulez **au mieux**, pas au pire. La marée s'occupe des pointes :
elle retire un palier de simulation quand le tick lissé dépasse sa cible, et le rend dès que la
machine respire. Elle ne dépasse **jamais** ce qui est écrit ici — un serveur réglé en 10 ne se
retrouvera pas en 16.

À vérifier en jeu avec `/lanterne maree`, qui affiche l'état courant et le nombre d'ajustements.

### Pré-générer la carte

C'est le conseil qui compte le plus pour une machine à un cœur, et il ne se voit pas dans les TPS.

La génération de terrain **ne coûte rien au temps de tick** : elle s'exécute sur le pool de travail
pendant que le fil du serveur attend. Mesuré, profileur à l'appui : 92 % du temps dormi, et ce qui
reste est de la coordination de fils. Mais ce pool n'a qu'un travailleur sur une machine à un cœur,
si bien qu'un joueur qui explore attend ses chunks — 37 ms chacun, en série.

```
/lanterne pregen 2000
```

Une carte déjà générée se **relit** en 6,8 ms au lieu de se créer en 37. C'est un facteur cinq sur
l'attente d'exploration, et il s'obtient une fois pour toutes, hors des heures de jeu.

### Le ramasse-miettes — ce que la mesure a dit, et ce qu'elle refuse de dire

Ce qui est **établi par la documentation** : depuis Java 10, la machine virtuelle respecte les quotas
de conteneur, et elle choisit d'elle-même le ramasse-miettes série en dessous de deux processeurs
**et** de 1792 Mo de tas. Le liant est un ET, et c'est lui qui fait le piège : **un cœur et quatre
gigaoctets font retenir G1**, parce que le tas a fait pencher la balance. C'est exactement ce que
vend un hébergeur Minecraft d'entrée de gamme, et personne ne le signale.

Lanterne le signale désormais au démarrage — voir `core/Creuset.java`, qui affiche la ligne à coller.

#### Le banc, et pourquoi le premier verdict a été jeté

`lab/Fonte.java` compare les deux ramasseurs sur une charge fixée, en publiant la grandeur que la
machine virtuelle compte elle-même : **le temps de pause cumulé**. C'est la sortie de l'impasse que
cette note décrivait — comparer des temps de tick entre deux démarrages est impossible, comparer des
pauses ne l'est pas.

Le premier essai a été **jeté**, et c'est le résultat le plus instructif de la série :

```
tas 2000 Mo, 1 cœur, 600 bêtes, 400 ticks
  série : 1 et 2 ramassages,  33 et 210 ms de pause
  G1    : 0 et 0 ramassages,   0 et   0 ms de pause
```

Zéro ramassage. Six cents vaches pendant vingt secondes n'allouent pas assez pour remplir deux
gigaoctets, et **un ramasseur qui ne passe jamais ne se distingue d'aucun autre**. Le banc n'avait
pas mesuré que G1 était meilleur ; il avait mesuré qu'il n'avait rien eu à faire. Il exige donc
maintenant un plancher de cinq ramassages et refuse de conclure en dessous.

#### La mesure qui compte, et son verdict

```
tas 768 Mo, 1 cœur (-XX:ActiveProcessorCount=1), 4000 bêtes, 800 ticks, 3 exécutions chacun
```

| | Série | G1 |
|---|---:|---:|
| Pause cumulée, les trois essais | 483 · 715 · 558 ms | 1037 · 783 · 457 ms |
| Pause cumulée, **médiane** | **558 ms** | **783 ms** |
| Étendue des pauses | 232 ms | **580 ms** |
| Tick médian, les trois essais | 27,85 · 29,64 · 24,06 ms | 33,60 · 24,88 · 24,23 ms |
| Tick médian, **médiane** | 27,85 ms | **24,88 ms** |
| Ramassages | 18 · 23 · 20 | 20 · 20 · 15 |

**Ce dépôt ne tranche pas.** La première exécution donnait au série un avantage net — 483 ms contre
1037, soit un facteur deux — et **elle ne s'est pas reproduite**. Sur trois essais, les étendues se
chevauchent des deux côtés, et le tick médian penche même légèrement pour G1. Annoncer le facteur
deux aurait été publier un coup de chance.

Ce qu'on peut dire, et qui suffit à décider :

- **L'effet est de l'ordre de quelques centaines de millisecondes sur quarante secondes**, soit **un
  à trois pour cent du temps de tick**. Réel, mais petit.
- **G1 est nettement moins régulier** : 580 ms d'étendue contre 232. Sur un serveur, c'est le pire
  tick qui se ressent, pas la moyenne — et c'est le seul point où le série mène franchement.
- Le chiffre de pause **sous-estime systématiquement le coût de G1**, puisqu'il ne compte que les
  arrêts complets et jamais le travail concurrent, qui est précisément ce qui vole le cœur unique.
  Si G1 ne gagne pas malgré cet avantage de comptage, il ne gagne pas.

**La recommandation est donc tiède, et c'est honnête qu'elle le soit** : `-XX:+UseSerialGC` est
défendable sur un cœur, surtout pour la régularité, mais **ne l'attendez pas comme un gain de
performance**. Le ramasseur vaut un ou deux pour cent. La mémoire retenue, elle, décide de combien de
mods tiennent — voir `notes/memoire-retenue.md`. C'est là qu'il faut regarder, pas ici.

Pour refaire la mesure sur **votre** machine :

```
LANTERNE_FONTE=1 LANTERNE_FONTE_HERD=4000 LANTERNE_FONTE_TICKS=800 \
  ./gradlew runServer -Pbanc=fonte -Pgc=serial -Pcoeurs=1 -Ptas=768
LANTERNE_FONTE=1 LANTERNE_FONTE_HERD=4000 LANTERNE_FONTE_TICKS=800 \
  ./gradlew runServer -Pbanc=fonte -Pgc=g1     -Pcoeurs=1 -Ptas=768
```

`-Pcoeurs=1` fait dimensionner à la machine virtuelle tous ses réservoirs de fils comme si elle
n'avait qu'un processeur. Ce qu'il **ne** fait pas : empêcher le système d'en donner huit à ces
fils. Le banc reproduit la *configuration* de la cible, pas sa *pénurie*.

Et ne pas sur-allouer le tas : un tas énorme ne rend pas le serveur plus rapide, il rend les pauses
plus longues — et, comme le premier essai l'a montré, il les rend aussi plus rares, ce qui masque le
problème au lieu de le régler.

### Si votre hébergeur ment sur le nombre de processeurs

Rare, mais possible sur une isolation mal faite : la machine virtuelle voit les vingt-six cœurs de
l'hôte et ouvre vingt-cinq fils de fond sur votre cœur unique. Le symptôme est un serveur qui rame
sans que rien ne le justifie, avec beaucoup de fils « Worker-Main ».

```
LANTERNE_THREADS=2
```

Sans cette variable, Lanterne ne touche à rien : le jeu décide seul. Voir `core/Threads.java` pour
pourquoi une formule automatique a été écrite puis **retirée**.

## Ce qu'il ne sert à rien de faire

- **Optimiser la génération de terrain.** Elle est hors du temps de tick ; pré-générer règle le
  problème réel.
- **Ajouter du parallélisme.** Il n'y a pas de second cœur pour l'accueillir.
- **Monter la distance de vue en espérant que la marée compense.** Elle ne monte jamais au-dessus du
  plafond ; le plafond, c'est vous qui le fixez.
