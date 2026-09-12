# Lanterne

> La citrouille qui éclaire sans brûler.

Mod d'optimisation pour NeoForge 26.1.2. Il ne cherche pas à rendre le jeu plus rapide, mais à lui
éviter le travail qui ne sert à rien.

**Cible : les petits serveurs, 1 à 2 vCPU.** C'est là qu'il est le plus utile — et c'est là que les
mods qui parallélisent ne servent à rien, faute d'un second cœur où pousser le travail.

---

## Résultats mesurés

Tous les chiffres ci-dessous sortent de `LANTERNE_SELFTEST`, la procédure automatique décrite plus
bas. **Aucun n'a été saisi à la main.** Machine d'essai : Ryzen 7 5800H, NeoForge 26.1.2, serveur
dédié, distance de simulation 10.

| Charge | Sans | Avec | Gain | Travail évité |
|---|---:|---:|---:|---:|
| 2 000 entités, rayon 100 | 30,9 ms | 18,7 ms | **×1,65** | 57 % |
| 2 000 entités, rayon 200 | 26,6 ms | 10,1 ms | **×2,64** | 77 % |
| 2 000 entités, rayon 320 | 14,7 ms | 6,4 ms | **×2,28** | 78 % |
| 5 000 entités, rayon 200 | 64,1 ms | 22,0 ms | **×2,91** | 87 % |

### Le cas qui compte

Un tick dispose de **50 ms**. Au-delà, le serveur décroche et les TPS chutent.

```
5 000 entités, rayon 200 blocs

                0        20        40        60 ms
                |─────────|─────────|─────────|
  budget 50 ms  ┊         ┊         ┊    ▼
  Sans Lanterne ███████████████████████████████ 64,1 ms   → 15,6 TPS  ✗ décroche
  Avec Lanterne ██████████▌                     22,0 ms   → 20,0 TPS  ✓ confortable
```

Lanterne ne rend pas un bon serveur meilleur. **Il rend un serveur saturé jouable** — ce qui, sur un
VPS à un cœur, est toute la différence entre un projet qui vit et un projet qu'on abandonne.

### Le gain suit la charge

```
Gain  ×3 ┤                                    ● 5000 ent.
         ┤                    ● 2000 @200
      ×2 ┤                         ● 2000 @320
         ┤        ● 2000 @100
      ×1 ┼────────────────────────────────────────
         └─ plus la charge est lourde et dispersée, plus le gain monte
```

C'est la propriété qu'on veut : **le mod s'efface quand il n'a rien à faire, et travaille d'autant
plus qu'on en a besoin.** Un serveur au repos tourne en vanilla.

---

## La thèse

Lithium rend chaque système du jeu plus rapide, et le fait bien. Lanterne pose une autre question :
**pourquoi ce travail a-t-il lieu ?**

Une vache à 150 blocs coûte exactement autant qu'une vache sous les yeux du joueur. Le jeu ne connaît
que deux états — chargé, ou pas chargé — et cette absence de nuance est la source principale de
dépense sur une instance chargée.

Rendre ce travail deux fois plus rapide fait gagner un facteur deux.
**Ne pas le faire fait gagner un facteur trois**, mesuré ci-dessus.

C'est le principe du niveau de détail, vieux de trente ans dans le rendu 3D, et absent de la
simulation de Minecraft.

## Pourquoi un mod unique bat vingt mods

Ce n'est pas une question de qualité de code — les mods existants sont bons. C'est structurel.

| Défaut | Ce qu'il coûte |
|---|---|
| **Scans redondants** | Quatre modules parcourent les entités chaque tick pour calculer la même distance |
| **Le JIT ne peut plus inliner** | Chaque mixin empilé sur une méthode chaude transforme un appel inlinable en chaîne d'appels virtuels. Le coût n'est pas le code ajouté, c'est l'optimisation JVM perdue |
| **Décisions contradictoires** | L'un ralentit un mob, l'autre saute ses objectifs, le troisième le supprime. Aucun ne sait ce que l'autre a décidé |
| **Aucun budget global** | Chacun optimise avec une sévérité fixée dans un fichier, sans savoir combien de millisecondes il reste dans le tick |

Le dernier point est impossible à corriger sans unifier :
**on ne peut pas répartir un budget qu'on ne connaît pas.**

## Les quatre garanties

1. **Rien n'est dégradé près d'un joueur.** En deçà de 32 blocs, le jeu est le jeu.
2. **Rien n'est jamais arrêté**, seulement espacé. Le plancher est à une fois par seconde.
3. **Ce qui dépend d'une trajectoire ou d'un compte à rebours n'est jamais touché** — projectiles,
   TNT amorcée, blocs qui tombent, véhicules montés.
4. **Quand le serveur va bien, le mod ne fait rien.** La sévérité suit la charge mesurée.

---

## Architecture

| Pièce | Rôle |
|---|---|
| `Lod` | Le gradient à 5 niveaux, de la pleine simulation au sommeil (1 tick sur 16) |
| `Census` | Le recensement — **par chunk, pas par entité** |
| `TickBudget` | Mesure le tick réel, en tire une pression de 0 à 1 |
| `EntityThrottle` | Applique, via `EntityTickEvent.Pre`. **Zéro mixin sur le chemin chaud** |
| `NetworkThrottle` | Le même gradient appliqué aux paquets de position |
| `Phantom` | L'observateur simulé — un joueur pour le monde, personne pour le réseau |
| `Bench` | La comparaison A/B, médiane, avec chauffe |

### Deux décisions qui portent tout

**Recenser des chunks, pas des entités.** L'approche naïve calcule la distance de chaque entité à
chaque joueur : 100 000 entités × 5 joueurs = 500 000 calculs par tick, un remède plus cher que le
mal. Mais deux vaches du même chunk sont à 16 blocs près à la même distance — et 16 blocs ne changent
rien à un seuil fixé à 112. À 10 chunks de simulation : **441 calculs au lieu de 500 000.**

**L'étalement.** La version évidente serait `tick % period == 0`. Elle est fausse, et de la pire
façon : toutes les entités d'un niveau agiraient dans le même tick. 15 ticks vides, puis un tick qui
fait tout. La charge ne baisserait pas, elle se **concentrerait**. D'où le décalage par identifiant.

---

## Mesurer soi-même

```bash
LANTERNE_SELFTEST="5000:200:1" ./gradlew runServer
```

Format : `entités:rayon:observateurs`. La procédure pose les observateurs, attend le chargement des
chunks, crée le troupeau, mesure 25 s avec puis 25 s sans, et écrit le verdict.

En jeu : `/lanterne` pour le rapport, `/lanterne bench` pour la comparaison, `/lanterne on|off`.

Le verdict est formulé pour être vérifiable, pas pour flatter. **Un gain sous 5 % est annoncé comme
du bruit**, et une perte est annoncée comme une perte — le banc l'a fait quatre fois pendant le
développement.

---

## Trois erreurs, et ce qu'elles ont coûté

Ce projet a été construit contre ses propres intuitions. Les quatre premiers bancs ont tous rendu un
verdict négatif, et chacun avait raison.

**La compensation des ticks aléatoires.** L'idée : ne tirer qu'une fois sur seize, mais seize fois
plus. La loi est préservée — c'est exact — et le gain est **nul** : même nombre total de tirages,
avant comme après. Abandonnée avant la première ligne de code, sur une multiplication.

**Le ralentissement des entonnoirs.** Un entonnoir tické une fois sur seize transfère seize fois
moins d'objets, et les chunks concernés sont dans le rayon de simulation — ce sont donc des fermes
*en marche*. On ne les aurait pas optimisées, on les aurait cassées.

**Le débordement qui neutralisait tout.** Le recensement se gardait ainsi :

```java
if (tick - lastRefresh < REFRESH_PERIOD) return;   // lastRefresh = Long.MIN_VALUE
```

`100 - Long.MIN_VALUE` déborde et redevient négatif. La condition était toujours vraie, et **le
recensement ne s'est jamais exécuté** — de la première ligne du mod jusqu'au sixième banc. Aucune
relecture ne l'avait vu. Le banc l'a dit en une ligne : `chunks recensés : 0`.

C'est très exactement ce pour quoi il a été écrit.

---

## Ce qui vient

| Phase | Objet | Attendu |
|---|---|---|
| Réseau | Mesurer le gain en bande passante, pas seulement en CPU | Fort sur mauvaise connexion |
| Blocs-entités | Ce qui est cosmétique peut s'espacer ; ce qui a un débit, non | Modéré, sans risque |
| Redstone | Moteur de propagation (approche *Alternate Current*) | ×10-100 sur gros circuits |
| Ticks aléatoires | Indexer les positions actives par section | À prouver avant de promettre |

## Cohabitation

**Immersive Optimization fait déjà du tick scheduling par distance.** Les deux mods appliqueraient
leur dégradation l'un sur l'autre, deux fois plus sévèrement que voulu, sans que ni l'un ni l'autre
ne le sache. Le retirer avant de juger Lanterne.
