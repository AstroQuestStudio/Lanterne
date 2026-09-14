# Le plan d'absorption

Trié depuis `notes/MINE.md` : 202 mods, 50 à prendre, 9 à réécrire, 46 à mesurer
avant de décider, 17 à laisser vivre à côté, 76 écartés.

## La règle qui ne change pas

Un module dont le gain n'est pas mesuré n'entre pas dans le README. C'est la
règle de la maison depuis le début du projet, et c'est précisément ce qui sépare
Lanterne des cent quatre-vingts mods de cette liste : la plupart annoncent un
chiffre, presque aucun ne dit sur quel banc.

Corollaire pratique : **un mod absorbé est un mod mesuré, un par un**. Absorber
cinquante modules d'un coup puis lancer un banc ne prouverait rien — on ne saurait
pas lequel paie et lequel coûte.

## Vague 0 — l'instrument (faite)

Rien de client n'était décidable avant, parce que le banc d'images se
contredisait : 11,82 ms de médiane contre 8,77 disait « perte », 2019 images
contre 1797 sur la même minute disait « gain ».

`Minecraft.getFrameTimeNs()` ne mesure que `gameRenderer.render(...)`. En solo, le
même fil enchaîne aussi le tick du serveur intégré et la présentation. Le banc
mesurait donc un tiers du problème — et le tiers où Lanterne ne travaille pas.

La Vitre relève maintenant l'**intervalle réel entre deux images**, garde le coût
de rendu seul en diagnostic, et **refuse de conclure** si `images × médiane` ne
retombe pas sur la durée de la fenêtre. Ce contrôle aurait attrapé la
contradiction tout seul.

## Vague 1 — le client, là où le gain est le plus gros

Dans l'ordre de gain attendu sur l'intervalle entre images.

| Cible | Source | Licence | Ce qu'on fait |
|---|---|---|---|
| Culling d'entités | EntityCulling | fermée | **Réécrire.** Raycast asynchrone caméra → entité, cache de visibilité. Le plus gros gain client connu. |
| Block entities | faster-block-entities, OBE | LGPL-3.0 | Copier. Rendre coffres et panneaux comme des modèles ordinaires. |
| Items au sol en 2D | fast-items | CC0 | Copier. Tu l'as signalé toi-même : décisif sur les fermes. |
| Faces de blocs | MoreCulling | GPL-3.0 | Copier. Complémentaire du culling d'entités. |
| Feuilles | cull-leaves | MIT | Copier, en y pliant l'herbe (Cullify). |
| Interface à cadence réduite | Exordium | GPL-3.0 | Copier. Gros gain avec JEI ouvert. |
| Rendu immédiat groupé | ImmediatelyFast | LGPL-3.0 | Copier. |
| Particules | particle-core | MIT | Copier. |
| Sons | sound-culling | MIT | Copier. |
| Collisions sur le fil de rendu | entity-collision-fps-fix | à vérifier | MC-228976. Cible que tu as nommée. |

**Sur Sodium, et donc sur DLSS.** Sodium est sous PolyForm Shield : incopiable, et
de toute façon cinquante mille lignes de moteur de chunks qu'il faudrait
re-maintenir à chaque version. Lanterne **cohabite** avec lui, ne le remplace pas.
La piste de mise à l'échelle (`superresolution`, GPL-3.0-or-later, vivant en 26.x)
est en revanche copiable et sera instruite — mais après avoir lu son code, pas
avant : promettre DLSS sans avoir vu comment il obtient un contexte graphique
depuis Java serait exactement le genre d'annonce que ce projet refuse de faire.

## Vague 2 — la mémoire et le démarrage

FerriteCore (MIT), Redirected (?), ModernFix (LGPL-3.0), Ksyxis (MIT),
ResourcePackCached (GPL-3.0), QuickPack (MIT), FastSuite (MIT), FastEvent (MIT).

Mesure : profileur d'allocations pour la mémoire — **avec la règle des
allocations en tête**, celle qui a coûté un facteur soixante-dix — et chronomètre
au démarrage pour le chargement.

## Vague 3 — le serveur

AlternateCurrent (MIT, refonte du moteur de redstone — la plus grosse pièce de
cette vague), zFastNoise, Noisium, StructureLayoutOptimizer, Harium, DoesItTick,
DespawnTweaks, SkeletonAIFix, EfficientHashing, FasterRandom, Krypton,
XXL-Packets, NotEnoughBandwidth.

Plusieurs recouvrent ce que Lanterne fait déjà (`EntityThrottle`, `Wire`,
`Gather`). Le banc tranche, pas l'intuition.

## Vague 4 — les correctifs et le confort

LeavesBeGone, CleanCut, CrashExploitFixer, VanillaIceCreamFix,
DisconnectPacketFix, FastIPPing, ShutUpGLError, RRLS, QuickQuit, FastQuit,
DynamicFPS, InputBooster, LimitedDamageIndicator, BetterBiomeBlend,
CeruleanAdvancements, RailOptimization, FastItemFrames, FeyTweaks.

Petites pièces, peu de risque, beaucoup de confort. Elles ferment la liste des
mods qu'un modpack devrait sinon installer.

## Ce qui reste à côté, et pourquoi

Sodium, Iris, Distant Horizons, Voxy, C2ME, Moonrise, Lithium, Chunky, Observable.
Tous vivants en 26.x, tous énormes. Les absorber, c'est signer pour les
re-maintenir contre leurs auteurs à chaque version de Minecraft. Lanterne les
lit, s'en inspire, et ne les remplace pas.

L'objectif annoncé — **JourneyMap, JEI, et Lanterne** — reste tenable : ce sont
les *mods d'optimisation* que Lanterne remplace, pas les moteurs de rendu.
