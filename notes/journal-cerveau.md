# Le cerveau des créatures, la marée, et trois épreuves de plus

Session du 14 septembre 2026. Suite de « exploite tout le reste de Moonrise », puis priorité serveur
sur machine à un cœur.

## Le gain, et d'où il vient

| Charge : 600 villageois, 10 de vue / 10 de simulation | Sans | Avec | Gain |
|---|---:|---:|:---:|
| Module `mind` seul | 32,11 ms | 24,41 ms | **×1,32** |
| Mod complet, huit cœurs | 47,34 ms | 27,93 ms | **×1,69** |
| Mod complet, **épinglé sur un cœur** | 32,60 ms | 20,63 ms | **×1,58** |
| Mémoire allouée par fenêtre | 484 Mo | 271 Mo | **×1,79** |

Trois tentatives successives avaient cherché ce temps au mauvais endroit — les tables du cerveau
(×1,01), les capteurs (×0,98), les flux des comportements composites (×1,03). Le profileur désignait
pourtant clairement `Brain.startEachNonRunningBehavior`, à **38,7 %** de la pile.

La cause était ailleurs que dans la méthode nommée. `Brain.tick` appelle `tickEachRunningBehavior`,
qui appelle `getRunningBehaviors()` — une méthode que Mojang a marquée `@Deprecated @VisibleForDebug`
— **à chaque tick, pour chaque créature à cerveau**. Elle alloue une liste et traverse trois niveaux
de tables imbriquées, y compris les comportements des activités **inactives**, pour n'en retenir que
deux ou trois.

C'est la règle d'instrument sous son visage le plus utile : le sommet de pile ne désignait pas un
coupable mais ses complices — `HashMap$Values.forEach`, `LinkedHashMap.keySet`,
`Collections$UnmodifiableCollection$1.hasNext`. La machinerie de parcours, jamais le parcouru.

Deux collections tenues à jour remplacent les deux parcours : la liste plate des comportements dont
l'activité est active, et une `MaskedList` où démarrer un comportement allume un bit. Dispositif
porté de Lithium (LGPL-3.0). S'y ajoute le balayage des mémoires, qui ne s'exécute plus que si
quelque chose a pu expirer — ce qui a rendu au passage son intérêt au cache des conditions
d'entrée, jusque-là invalidé une fois par tick.

## Ce qui a été trouvé contre nous

**Quatre modules serveur échappaient à l'isolation du banc.** `mind`, `clump`, `vigil` et
`waypoints` n'étaient réglés par aucun mot-clé de `LANTERNE_MODULES` : un drapeau absent garde sa
valeur par défaut, qui est vraie. La phase active de tout banc les allumait donc, et leur travail
était porté au crédit du module qu'on croyait isoler. Aucune mesure publiée n'en est faussée — les
charges concernées ne contiennent ni villageois ni orbes — mais l'isolation ne doit pas dépendre du
hasard de la charge choisie, et `mind` allait précisément être mesuré sur six cents villageois.

**Une formule de dimensionnement des fils, écrite puis retirée.** Elle devait corriger un `available
Processors()` menteur sur VPS. Vérification faite : depuis Java 10 la machine virtuelle respecte les
quotas de conteneur, donc sur un VPS à un cœur elle rend 1, et la formule du jeu donne déjà un seul
fil. Il n'y avait rien à gagner — et sur huit cœurs, la formule serait passée de sept fils à deux,
brimant les grosses machines. Ne reste que le réglage explicite `LANTERNE_THREADS`.

## Ce qui a été écarté après lecture, sans écrire une ligne

| Cible | Raison |
|---|---|
| **Noisium** | Sans objet en 26.1.2 : `setBlockState` y utilise déjà `getAndSetUnchecked` et saute toute la décrémentation quand le bloc précédent est vide, ce qui est le cas partout en génération. Et son code met à jour `tickingFluidCount` sans jamais toucher `fluidCount` — les fluides des chunks générés seraient faux. |
| **`bitstorage`** (Moonrise) | Supprime la validation des bornes. Un index hors limites corromprait un chunk en silence, pour une arithmétique 32 bits là où vanilla en fait déjà une à nombres magiques. |
| **`ai/pathing`** (Lithium) | Désactivé par ses propres auteurs : `enabled = false`, avec un TODO sur les tests de saut des chèvres et des grenouilles devenus instables. |
| **`poi_lookup`** (Moonrise) | 807 lignes et huit `@Overwrite` sur `PoiManager`, pour une classe que le profileur ne désigne nulle part. |
| **Tick aléatoire** | Gain nul déjà démontré dans ce journal. Chiffré à nouveau : 1 à 3 % du tick, pour les 600 lignes de `block_counting`. |

## Trois épreuves de plus

Le cerveau accéléré est **le seul module du mod dont la panne améliore le chiffre** : une créature
figée est la plus rapide de toutes. Il lui fallait des épreuves qui regardent ailleurs que l'horloge.

- **`Wits`** — déplacement réellement parcouru et mémoires de déplacement, mesurés hors de toute
  structure que le module touche. Trois fenêtres : allumé, éteint, **rallumé**, cette dernière
  vérifiant qu'une extinction en cours de partie ne laisse pas une liste périmée derrière elle.
  Verdict : **102 % du déplacement de vanilla, 97 % après rallumage**.
- **`Reap`** — la moisson et la reproduction, demandées explicitement. Verdict : **89 %** sur les
  fenêtres comparables, et le comportement de reproduction se déclenche (16 courtises contre 0).
- **`Surge`** — la marée. Verdict : **descendue 10 → 5 en simulation** sous 3 000 villageois à
  71,6 ms, **remontée 5 → 10** une fois soulagée, jamais au-dessus du plafond.

### Quatre défauts de protocole, trouvés par les épreuves elles-mêmes

1. **L'heure du monde.** `Wits` a rendu 10 078 blocs puis 2 203 pour le même code : les villageois
   travaillent ou dorment selon l'heure. Horloge arrêtée, les trois fenêtres se resserrent à
   1 914 / 1 884 / 1 819.
2. **Le compteur qui comptait l'absence.** `Reap` comptait « tout ce qui n'est pas un épi mûr », ce
   qui vaut pour un épi moissonné comme pour un épi jamais semé. Sur un champ vide, elle a annoncé
   « 100 %, conforme » trois fois. Un contrôle du semis précède désormais le comptage.
3. **Les chunks non chargés.** Sans joueur ni ticket, `setBlock` n'écrit rien. Troisième fois que ce
   défaut se présente dans ce laboratoire, après le tir et les fluides.
4. **Le biais de première fenêtre.** 80 épis, puis 27, puis 25 — c'est la position de départ qui
   gagne, pas le mod. Le verdict porte désormais sur les deux fenêtres adjacentes.

## La marée

Nouveau module, inspiré du gestionnaire dynamique de ServerCore (MIT). Une fois par seconde, il lit
le temps de tick lissé : au-dessus de la cible il retire **un** palier, en dessous il en rend un. La
simulation est sacrifiée avant la vue — personne ne voit qu'un mouton s'est arrêté de brouter à cent
quatre-vingts blocs, tout le monde voit un horizon qui recule. Elle ne dépasse **jamais** ce que
`server.properties` demande.

Le banc l'endort pour toute la durée d'une mesure : elle changerait les distances en cours de route,
et les deux phases compareraient des mondes de tailles différentes en croyant comparer deux versions
du même code.

## Ce que la machine à un cœur a appris

Épinglé sur un seul cœur, vanilla fait 32,60 ms là où il en fait 47,34 sur huit. Autrement dit : sur
une charge d'entités, **le serveur est mono-thread de toute façon**. Le nombre de cœurs ne le sauve
pas ; seuls comptent le travail par tick et le ramasse-miettes — qui, sur un seul cœur, ne tourne
plus à côté du tick mais le vole. C'est ce qui donne aux 213 Mo d'allocations évitées leur valeur
réelle.

Corollaire pour l'exploration : la génération de chunks ne coûte rien au temps de tick (elle tourne
sur le pool), mais elle sera lente faute de travailleurs. La réponse n'est pas d'optimiser le
générateur, c'est `/lanterne pregen` — et la marée pour le reste.
