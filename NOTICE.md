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
