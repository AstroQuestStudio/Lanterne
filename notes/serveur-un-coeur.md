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

### Le ramasse-miettes — ce qui est su, et ce qui ne l'est pas

Ce qui est **établi** : depuis Java 10, la machine virtuelle respecte les quotas de conteneur, et
elle choisit d'elle-même le ramasse-miettes série en dessous de deux processeurs et de 1792 Mo de
tas. Sur un VPS correctement isolé, elle fait donc déjà le bon choix.

Ce qui est **raisonnable, sans être mesuré ici** : si votre hébergeur vous donne un seul cœur avec
beaucoup de mémoire, la machine virtuelle peut retenir G1, dont les fils concurrents prendront le
cœur au tick. `-XX:+UseSerialGC` est alors défendable.

Ce que ce dépôt **n'a pas pu trancher** : la comparaison entre les deux. Elle exige de comparer des
temps absolus entre deux exécutions, or la variance d'une exécution à l'autre sur la même charge va
de 32 à 47 ms — largement plus que l'effet cherché. Le banc sait mesurer un rapport entre deux
phases entrelacées ; il ne sait pas comparer deux démarrages.

Pour mesurer sur **votre** machine, où la variance sera différente :

```
./gradlew runServer -Pgc=serial
./gradlew runServer -Pgc=g1
```

Et ne pas sur-allouer le tas : un tas énorme ne rend pas le serveur plus rapide, il rend les pauses
plus longues.

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
