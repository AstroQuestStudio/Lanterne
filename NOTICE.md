# Origines et attributions

Lanterne est publié sous **GPL-3.0-only** (voir `LICENSE`).

Ce choix n'est pas idéologique, il est mécanique. Le projet absorbe du travail
existant, et le copyleft remonte : pour incorporer du code LGPL-3.0 (Sodium,
Lithium, Krypton, ModernFix…) ou GPL-3.0 (MoreCulling, Exordium, RailOptimization…),
Lanterne doit être au moins aussi ouvert. Une licence permissive comme MIT ne
l'autoriserait pas — c'est un contresens fréquent : la licence « la plus ouverte »
est celle qui laisse *sortir* le code, pas celle qui laisse le plus *entrer*.

GPL-3.0-only laisse entrer MIT, Apache-2.0, CC0, Unlicense, MPL-2.0, LGPL-3.0,
LGPL-2.1-or-later et GPL-3.0. Cela couvre **50 des 59 cibles** retenues dans
`notes/MINE.md`.

## Ce qui n'est pas absorbable, et n'est donc pas absorbé

Neuf cibles sont sous licence fermée, non commerciale, sans dérivée, ou maison.
Leur code n'entre pas dans Lanterne, quelle que soit la licence de Lanterne :

| Projet | Licence | Ce qu'on en retient |
|---|---|---|
| EntityCulling | tr7zw Protective | L'idée : un raycast asynchrone vers chaque entité. Réécrit. |
| Sodium | PolyForm Shield 1.0.0 | Rien n'est copié. Lanterne cohabite avec lui. |
| TT20 | PolyForm Shield 1.0.0 | L'idée : dégrader le tick quand le TPS chute. Réécrit. |
| ai-improvements | All Rights Reserved | L'idée seule. |
| mobtimizations | All Rights Reserved | L'idée seule. |
| JEIOptimizer | All Rights Reserved | L'idée seule. |
| MemoryLeakFix | LGPL-2.1-only | Incompatible avec GPL-3. L'idée seule. |
| DSBG | CC-BY-ND-4.0 | Sans dérivée : l'idée seule. |
| server_heater | The Lambda License | L'idée seule. |

Une licence protège l'**expression**, pas l'idée. Lire ces sources pour
comprendre un mécanisme est licite et a été fait ; en recopier les lignes ne
l'est pas et ne l'a pas été.

## Ce qui est repris, et d'où

Cette table est tenue à jour module par module au fur et à mesure de
l'absorption. Chaque entrée nomme le fichier de Lanterne, le projet d'origine et
sa licence. Tant qu'un module n'y figure pas, il est d'origine.

| Module Lanterne | Repris de | Licence d'origine |
|---|---|---|
| `core/{EntityThrottle,Cadence,Census}.java` — l'ordonnanceur de tick | **Rien.** Même idée que [Immersive Optimization](https://github.com/Luke100000/ImmersiveOptimization) (Luke100000), mécanique différente — voir ci-dessous. | GPL-3.0 (compatible) |
| `lab/Duel.java` — l'épreuve du duel | **Rien.** Écrite pour ce projet. | — |
| `core/Shroud.java` — le Voile | **Rien.** Écrit pour ce projet. | — |
| `mixin/StaticChestRendererMixin.java` | [Faster Block Entities](https://github.com/0x1bd/Faster-Block-Entities) (kvxd) | GPL-3.0-or-later |
| `mixin/StaticChestShapeMixin.java` | *idem* | GPL-3.0-or-later |
| `assets/minecraft/{blockstates,models,atlases}` *(coffres)* | [FastChest](https://github.com/FakeDomi/FastChest) (FakeDomi), via Faster Block Entities | MIT |
| `client/Foliage.java` + `mixin/LeafCullMixin.java` | **Rien.** Idée commune à [CullLeaves](https://github.com/TeamMidnightDust/CullLeaves) (MIT) et consorts. | — |
| `mixin/LeafDecayMixin.java` — chute rapide | **Rien.** Écrit pour ce projet. | — |
| `mixin/LooseItemMixin.java` — exemplaires au sol | **Rien.** Idée voisine de [fast-items](https://github.com/Noryea/fast-items-fabric) (CC0), mécanisme différent. | — |
| `mixin/ParticleVeilMixin.java` | **Rien.** Extension du Voile, écrite pour ce projet. | — |
| `mixin/BlockEntityVeilMixin.java` | **Rien.** Extension du Voile, écrite pour ce projet. | — |
| `core/Din.java` + `mixin/SoundLimitMixin.java` | **Rien.** Idée de [SoundCulling](https://github.com/Cukkoo12/SoundCulling) (MIT), écriture différente. | — |
| `core/Moulds.java` + `mixin/StateCacheDedupMixin.java` | Idée de [FerriteCore](https://github.com/malte0811/FerriteCore) (malte0811) | MIT |
| `content/painting/*` — l'Atelier, les tableaux | **Rien.** Idée de [Immersive Paintings](https://github.com/Luke100000/ImmersivePaintings) (Luke100000), architecture entièrement différente. | GPL-3.0 (compatible) |
| `content/disc/*` — le Sillon, les disques | **Rien.** Écrit pour ce projet. | — |
| `assets/lanterne/shaders/post/fsr_easu.fsh` | Algorithme **FidelityFX Super Resolution 1.0 (EASU)**, [AMD](https://github.com/GPUOpen-Effects/FidelityFX-FSR) — `ffx_fsr1.h` | MIT |
| `assets/lanterne/shaders/post/fsr_rcas.fsh` | Algorithme **FidelityFX Super Resolution 1.0 (RCAS)**, *idem* | MIT |

L'ordonnanceur de tick mérite la même précision. **Immersive Optimization** est
sous GPL-3.0, donc absorbable sans réserve, et son dépôt a été lu en entier —
`TickScheduler`, `ServerLevelMixin`, `Config` — avant d'écrire cette ligne. Rien
n'en a été repris, pour quatre raisons qui se vérifient dans son code :

- **Il calcule la distance entité par entité**, en parcourant la liste des
  joueurs pour chacune, sur un **fil d'arrière-plan** réveillé toutes les
  500 ms. Lanterne recense des *chunks* : quatre cent quarante et un au lieu de
  cent mille entités. Et un fil d'arrière-plan est précisément ce qu'il ne faut
  pas sur la cible de ce projet — **un VPS à un seul cœur**, où ce fil vole le
  tick qu'il prétend soulager.
- **Son gradient est plus grossier** : un cran tous les 64 blocs, zone franche de
  6 blocs. Ici, un cran tous les 16 blocs et une zone franche de 24 — dégradation
  plus fine, garantie plus large.
- **Il n'a aucune compensation de production.** Une poule tickée une fois sur
  sept pond sept fois moins d'œufs, et rien ne le signale. Voir `core/Produce.java`,
  qui tient les horloges à la main pendant le sommeil.
- **Il ignore la densité.** Cinquante vaches dans un enclos à vingt blocs sont
  toutes « proches » chez lui, et ce sont elles qui coûtent le plus cher.

La comparaison a tout de même rendu quelque chose, et il faut le dire :
**Immersive Optimization exempte les chunks maintenus chargés de force**, ce que
Lanterne ne fait pas. Chez lui, `getPriority` commence par
`if (data.forcedChunks.contains(entity.chunkPosition().pack())) return 0;`. Ici,
un chunk sous `/forceload` sans joueur à proximité est classé « inconnu », donc
dégradé au plafond de son espèce — alors qu'un `/forceload` est le seul geste par
lequel un joueur dit explicitement « continue de simuler ceci pendant mon
absence ». `ServerLevel#getForceLoadedChunks()` est public en 26.2 et rend
directement le jeu de chunks concernés ; le correctif tient en quelques lignes
dans le recensement. **Constat ouvert, non corrigé à ce jour.**

Les tableaux méritent le même genre de précision que le Voile. **Immersive
Paintings** est sous GPL-3.0, donc parfaitement absorbable ; son code n'a pourtant
pas été repris, parce que ce qui coûte cher chez lui est justement ce qu'on
voulait éviter. Trois différences de fond, toutes mesurables :

- **Une texture par tableau et par palier de distance** chez lui — jusqu'à cinq
  par image, toutes résidentes — contre **une seule mosaïque** pour tout le monde
  ici. Vingt tableaux à l'écran : cent changements de texture chez lui, un chez
  nous. Voir `content/painting/Mosaic.java`.
- **Un niveau de détail calculé en Java à chaque image** — distance, champ de
  vision, tangente, par tableau visible — contre des **mipmaps matériels**, qui
  font le même travail gratuitement et mieux. Voir `content/painting/Mill.java`.
- **Une géométrie repoussée sommet par sommet depuis des fichiers `.obj`** pour
  les cadres, contre pas de cadre du tout et une géométrie de quelques quads.

Ce qui lui est repris, en revanche, est son **idée**, qui est bonne : nommer une
image par l'empreinte de son contenu, annoncer un catalogue, et ne transmettre
que ce qui manque. La reprise s'arrête là, et le chemin de migration depuis son
format est décrit dans `content/painting/Relic.java`.

La mise à l'échelle mérite une précision, parce qu'elle touche à du code de
constructeur. **FSR 1.0** est publié par AMD sous licence **MIT**, que la GPL-3.0
absorbe sans difficulté. Les deux nuanceurs de `shaders/post/` sont écrits
**d'après l'algorithme publié** — la disposition des douze échantillons, le noyau
de Lanczos étiré le long du contour, la limite de 0,1875 de RCAS — et non copiés
ligne à ligne depuis `ffx_fsr1.h`. Les constantes n'ont pas été confrontées au
fichier d'origine ; le garde-fou de voisinage placé en fin de passe EASU borne
l'effet d'une erreur éventuelle à un peu plus ou un peu moins de douceur.

[Vitrail Shaders](https://github.com/avpbynf/Vitrail-Shaders) (LGPL-3.0) a été
lu pour vérifier qu'un portage GLSL de ces noyaux se pratique et sous quelle
licence ; aucune de ses lignes n'a été reprise, et Lanterne ne dépend ni de lui
ni de Vulkan.

Quatre fichiers de nuanceur portant l'en-tête d'un mod tiers (« Salt's Anti
Aliasing ») avaient été déposés dans `assets/lanterne/shaders/post/` sans figurer
dans cette table, et sans que ce projet puisse établir leur origine ni leur
licence. Ils ont été **supprimés** et remplacés par les deux fichiers ci-dessus.

Les ressources de shulker fournies par Faster Block Entities n'ont pas été
reprises : leur couvercle qui s'ouvre est un retour d'information utile, et le
module ne concerne donc que les coffres.

L'atlas `assets/minecraft/atlases/blocks.json` **ajoute** une source sans
effacer celles de vanilla — `SpriteSourceList.load` (ligne 85) parcourt
`getResourceStack`, donc tous les paquets sont fusionnés. Vérifié dans le code
décompilé, pas supposé.

Le Voile mérite une précision, parce qu'il traite le même sujet qu'un mod connu.
**EntityCulling** (tr7zw Protective License) a été lu, et deux de ses garde-fous
ont inspiré les nôtres : ne pas voiler au-delà d'une distance, ne pas voiler une
entité à très grosse boîte. Aucune de ses lignes n'a été reprise, et les choix de
fond diffèrent — il lance ses rayons sur un fil séparé, nous les lançons sur le
fil de rendu, amortis par un cache et plafonnés par image, parce que lire
`ClientLevel` depuis un autre fil est une course de données. Le point d'accroche
diffère aussi : il annule `extractEntity`, nous enveloppons l'appel à
`shouldRender` qui le précède.

Le parcours de grille employé est l'algorithme d'Amanatides et Woo, publié en
1987 ; il n'appartient à aucun mod.

Les objets au sol méritent une précision inverse des précédentes. **fast-items**
(CC0, donc copiable sans réserve) remplace le modèle tridimensionnel par une
image plate face à la caméra. Son code n'a pas été repris — non par scrupule,
mais parce qu'il vise `BakedModel` et `ItemRenderer.render`, deux interfaces que
26.1 a supprimées au profit de l'extraction d'états et de `SubmitNodeCollector`.
Le mécanisme retenu ici est autre : plafonner le nombre d'exemplaires que vanilla
dessine par pile, qui va jusqu'à **cinq** pour une pile de plus de quarante-huit.

Le masquage des feuilles mérite la même précision. **CullLeaves** (MIT, donc
copiable) a été lu. Son mixin *redéfinit* `skipRendering` en entier, ce qui
revient à décider seul du sort de toutes les faces de feuilles ; celui-ci
*injecte* un cas supplémentaire et laisse la méthode d'origine reprendre la main
partout ailleurs — de sorte qu'éteint, il ne change rigoureusement rien. Le
réglage à trois branches et l'accrochage à l'option `cutoutLeaves` de 26.1 n'ont
pas d'équivalent en amont.

## Les modules d'origine

Tout ce qui est listé dans `README.md` à la date du passage en GPL-3.0 a été
écrit pour ce projet et mesuré sur ses propres bancs : les entonnoirs par lots,
la fusion au sol, l'enclos, la Vigie, le Balai, la compression réseau, le minage
à l'horloge réelle, les cadences d'entités, le Carnet et la Boussole d'Ancre.
