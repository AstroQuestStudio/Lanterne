#!/usr/bin/env python3
"""Insere dans le README les sections des chantiers de la nuit du 16 septembre 2026."""

import io
import sys

for flux in (sys.stdout, sys.stderr):
    try:
        flux.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

NOUVEAU = """---

## 🪨 La génération de chunks — 11,2 → 12,9 sur la vraie machine

Mesuré sur la cible, pas sur la machine de développement : une **MyBox Free, un cœur dédié, 4 Go**,
SerialGC. Travail rigoureusement identique — **1872 chunks générés, même graine, même rayon 24** :

```
avant : 2 min 48 s   11,2 chunks/s
apres : 2 min 25 s   12,9 chunks/s      x1,152
```

Au-dessus du plancher de dérive du laboratoire (×1,08).

### La trouvaille : ce débit était la signature d'un blocage

`ServerChunkCache.getChunkFuture` ment sur son nom. Appelée depuis un tick — et un gestionnaire de
tick l'est toujours :

```java
this.mainThreadProcessor.managedBlock(serverFuture::isDone);   // bloque
```

Elle ne rend la main qu'une fois le chunk **entièrement fabriqué**. Trois conséquences :

1. **Aucun chunk n'a jamais été « en vol ».** Le plafond de 24 n'a jamais rien plafonné — la
   génération était strictement sérielle.
2. **Le budget par tick était toujours dépassé d'un chunk entier.** `11,2 = 1/(89 ms)` : un chunk par
   tick, le tick durant ce que coûte le chunk. Régler le budget de 1 à 45 ne changeait *rien*.
3. **Le serveur restait en retard**, donc `haveTime()` faux, donc `processUnloads` gelé jusqu'à
   **2000 chunks** en attente — la soupape de secours de vanilla, pas son régime normal. Et comme
   `promoteChunkMap()` recopie *toute* la table à chaque soumission, **le coût par chunk croissait
   avec le retard**. C'est pourquoi une pré-génération au rayon 40 tombait à 9,9 chunks/s contre 11,2
   au rayon 24 : ce n'était pas le terrain, c'était la dette.

La soumission passe désormais par la voie asynchrone que vanilla emploie lui-même. Même
`scheduleChunkGenerationTask(FULL)`, même niveau de ticket 33.

**Bug corrigé au passage** : `dimensionFolder()` rendait `DIM-1`/`DIM1`, faux depuis la 26. **La
reprise était cassée sur le Nether et l'End** — ils se refabriquaient entièrement à chaque lancement.

### Ce qui est prouvé exact, et pas encore prouvé rentable

Trois modules touchent au cœur de la génération. Les trois sont **exacts**, sur des contrôles
exhaustifs et non des échantillons :

| module | ce qu'il fait | contrôle | écarts |
|---|---|---|---|
| **Colonne** | deux chunks voisins recalculaient le même bruit | 20 920 colonnes recalculées et comparées **entièrement** | **0** |
| **Ciel** | les cellules de terrain vides ne sont plus remplies bloc par bloc | **18 163 712** positions recontrôlées | **0** |
| **Climat** | la descente de l'arbre des biomes, sans ses sauts de pointeur | 1 000 000 distances, nœud par nœud | **0** |

**Colonne**, en un compte : le bruit est évalué aux *coins de cellules*, 5 × 5 colonnes par chunk. À
l'intérieur d'un chunk, vanilla réutilise. Entre chunks, non — la colonne de bord est calculée deux
fois, le coin quatre fois. Pour N × N chunks : `25 N²` calculées contre `(4N+1)² → 16 N²` distinctes.
**25/16 = 1,5625, soit 36 % de recalcul pur.** Exact par nature : une fonction de densité est pure,
la même position rend le même `double` au bit près.

**Aucun des trois n'a de gain mesuré.** Le banc de carrière donne 21,87 chunks/s sans, 21,83 avec —
et il ne *peut pas* voir Colonne : il apparie un chunk sur deux dans la même grille, donc deux chunks
« mod actif » n'y sont jamais adjacents, et un module qui exploite le voisinage n'a personne à qui
servir. D'où `LANTERNE_PREGEN=<rayon>`, qui génère tout au même réglage.

### Ce qui a été essayé et réfuté — pour qu'on ne le cherche pas deux fois

- **Les drapeaux JVM** (`-Xms` fixe, `AlwaysPreTouch`, `TransparentHugePages`, `NewRatio=1`) :
  **11,1 chunks/s contre 11,2**. Rien. Cohérent avec un ramasseur qui ne coûte que **3 %**
  (796 ramassages, 17,4 s de pause sur 575 s de vie).
- **L'ordonnancement** : le cœur est **saturé à 106-116 %** pendant une pré-génération. Il n'y a
  aucun temps mort à récupérer.
- **Mémoïser `Climate.RTree.search`** : elle **n'est pas pure**. Elle passe le dernier résultat du fil
  comme borne initiale, et à égalité de distance c'est la feuille héritée de l'appel précédent qui
  est rendue. Et la clé ne se répéterait jamais : `depth` varie de 312,5 unités quantifiées par pas
  de quart, soit **1536 clés distinctes par chunk**. Taux de succès attendu : **zéro**.
- **L'écluse** (reporter la livraison quand le tick déborde) : **éteinte par défaut**, la mesure ne
  lui a pas donné raison. Cinq exécutions alternées, pire tick 106 ms sans / 109 ms avec. Le pic
  n'est pas dans l'envoi, il est dans le **chargement** — à chaud, le pire tick tombe à 34 ms.

### Ce que coûte une arrivée de joueur, et où va ce temps

Le banc du seuil compare deux arrivées en ne faisant varier qu'une condition : la première doublure
trouve des chunks à lire, la seconde les trouve déjà en mémoire.

| relevé | coût cumulé | pire tick |
|---|---|---|
| arrivée à **froid** | 2197 ms | 106 ms |
| arrivée à **chaud** | 939 ms | 34 ms |
| changement de dimension | ~1510 ms | 77 ms |

**43 % du coût d'une arrivée est de l'emballage pur**, repayé intégralement à chaque connexion,
chaque changement de dimension, chaque aller-retour — et aucune pré-génération ne l'enlève. C'est ce
que vise le module **Emballage**, un cache du paquet de chunk borné en **octets** (et non en entrées :
un chunk de pierre et une base construite diffèrent d'un ordre de grandeur).

> ⚠️ Deux défauts d'instrument ont été trouvés en écrivant ce banc, et ils faussaient toutes les
> doublures du dépôt. Une doublure **n'accusait jamais** réception de ses lots de chunks — or vanilla
> n'en envoie qu'**un** tant qu'il n'est pas accusé : elle recevait **9 chunks puis plus rien**, soit
> 2 % du travail réel. Et `ClientInformation.createDefault()` annonce une distance de vue de **2** :
> 49 chunks au lieu de 441, **neuf fois moins**. Les deux correctifs sont **éteints par défaut** —
> changer le dénominateur de tous les bancs d'un coup invaliderait en silence les chiffres déjà
> publiés.

---

## 🧱 Le remblai — `/fill`, et le minage intensif

Même mécanique : beaucoup de modifications de blocs en peu de ticks.

`/fill` **diffère déjà** les notifications de voisinage. L'asymétrie est ailleurs :
`UPDATE_KNOWN_SHAPE` n'est pas passé, donc **six recalculs de forme par bloc posé**, chacun allouant
deux objets. **Douze par bloc**, plus sept pour la notification différée.

Un oracle classe chaque bloc **une fois** : si sa classe ne redéfinit ni `updateShape` ni
`neighborChanged`, l'appel est vide **par construction** — le corps par défaut de la première est
`return state;`, celui de la seconde est `{}`.

**Les 500 % sont hors d'atteinte, et c'est chiffré** : ~20 objets supprimés sur 23, mais restent
l'écriture en palette, quatre cartes de hauteur, la lumière, le marquage client. Ordre de grandeur
**×2 à ×2,5**. Aller à ×6 demanderait de toucher au moteur de lumière — c'est-à-dire de changer le
résultat.

Ce qui a été **écrit puis jeté** : différer et fusionner les notifications sur le périmètre. Une
torche à l'intérieur de la zone tombe *et lâche son objet* quand `/fill` détruit son support avant de
l'atteindre. **L'ordre est observable.**

---

## 🐄 La foule — ce qui reste quadratique quand les entités s'entassent

`EntitySection.getEntities` parcourt **tous** les habitants de la section et teste l'intersection sur
chacun. Une requête coûte `n`, pas le nombre de réponses. À une requête par entité et par tick :

| bêtes dans l'enclos | tests/tick |
|---|---|
| 250 | 62 500 |
| 1 000 | 1 000 000 |
| 4 000 | 16 000 000 |

Une grille de 8 × 8 × 8 cellules de 2 blocs **dans** la section, entretenue incrémentalement :
**~19 fois moins d'entités examinées**. Le terme reste quadratique par nature — une recherche exacte
ne peut pas rendre moins que ce qu'elle trouve — mais la constante tombe.

En deçà de **64 entités par section, rien n'est touché** : même code, même ordre.

**Trois réfutations, pour qu'on ne les cherche pas deux fois :**

- `Mob.checkDespawn` **n'est pas quadratique** : `getNearestPlayer` boucle sur les **joueurs**, pas
  sur les entités. Les branches mortes existent bien, mais les retirer rendrait des microsecondes.
- `EntityTickList` **ne recopie pas** sa liste à chaque tick.
- `forEachAccessibleNonEmptySection` balaie bien **un plan X entier du monde** — c'est un second terme
  quadratique, réel. **Il n'est pas corrigé** : aucun banc de ce dépôt ne peut le juger, et écrire une
  optimisation qu'on ne peut pas éprouver, c'est exactement ce qui a produit l'écluse.

---

## 🚫 La réclame de l'hébergeur

Un hébergement gratuit se paie autrement : le démon écrit une commande sur l'entrée standard du
serveur, comme si un administrateur l'avait tapée. Le message part à **tous les joueurs**, plusieurs
fois par heure.

Il ne pouvait **pas** être traité par le filtre de journal, et la raison valait d'être vérifiée
plutôt que supposée : **la commande n'apparaît pas dans `latest.log`**. Ce qu'on voit dans la console
du panneau est l'écho de l'entrée standard par le démon — un texte qui n'a jamais traversé le journal
du serveur.

La règle est étroite — on n'annule que si la commande **ne vient pas d'un joueur** *et* nomme un
hébergeur d'une liste écrite en clair. Un administrateur connecté en jeu reste maître de ce qu'il
écrit.

**Ce module ne rend rien en performances, et ne prétend pas le contraire** : un `tellraw` toutes les
deux minutes est indétectable. Ce qu'il retire est un message que le joueur n'a pas demandé.

> ⚠️ C'est la contrepartie d'un hébergement gratuit, et la retirer peut contrevenir aux conditions de
> votre hébergeur. D'où un réglage, et non un comportement imposé.

---

"""


def main():
    chemin = "README.md"
    with io.open(chemin, encoding="utf-8") as fichier:
        texte = fichier.read()

    ancre = "## 🛒 La Boutique et l'Économie"
    if ancre not in texte:
        raise SystemExit("ancre introuvable dans le README")
    if "La génération de chunks — 11,2" in texte:
        raise SystemExit("deja insere")

    texte = texte.replace(ancre, NOUVEAU + ancre, 1)
    with io.open(chemin, "w", encoding="utf-8", newline="") as fichier:
        fichier.write(texte)
    print("sections inserees")


if __name__ == "__main__":
    main()
