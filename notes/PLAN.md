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

## Bilan des vagues 1 et 2

### Ce qui est entré, et ce que ça vaut

| Module | Charge | Gain | Côté |
|---|---|:---:|---|
| Le Voile — créatures | 1 000 vaches sous un toit | **×7,98** | client |
| Le Voile — blocs-entités | 1 200 coffres | **×2,65** | client |
| Objets au sol | 1 500 piles pleines | **×2,35** | client |
| Coffres statiques | 1 200 coffres | **×1,45** | client |
| Masquage des feuilles | 12 167 feuilles | **×1,42** | client |
| Le Voile — particules | — | non mesuré | client |
| Les Moules | — | non mesurable | les deux |
| Chute des feuilles | — | confort | serveur |
| Le Vacarme | — | confort | client |

### La découverte qui domine ces deux vagues

**26.1 a déjà absorbé une grande partie de ce que les mods de la mine corrigeaient.**
Vérifié dans le code décompilé, une cible après l'autre :

| Cible | Ce que le mod corrige | État en 26.1 |
|---|---|---|
| **Ksyxis** | Les 441 chunks de spawn chargés au démarrage | `prepareLevels` ne les charge **plus** |
| **FastSuite** | Pas de cache de recettes | `RecipeManager.CachedCheck` **existe** |
| **particle-core** | Particules hors champ non filtrées | `extract` reçoit déjà un `Frustum` |
| **particules** *(distance)* | Pas de filtre de distance | `doAddParticle` coupe à 32 blocs |
| **Lithium** *(Brain)* | Streams dans `startEachNonRunningBehavior` | Ce sont des **boucles `for`** |

Corollaire de méthode : **le code d'un mod reste copiable, son argumentaire non.** Les chiffres
qu'il annonce datent d'une version où le défaut existait. Il faut remesurer, jamais croire.

### Les trois modules retirés de ces vagues

| Module | Ce qu'il visait | Verdict |
|---|---|---|
| Brassage des positions | 93 % de collisions sur `Vec3i.hashCode` | ×0,91 et ×0,93 — les tables chaudes sont indexées par `long` |
| Conseil des comportements | 5,1 % du sommet de pile | ×1,03 puis ×1,01 — le temps vit dans ce que la boucle appelle |
| *(la dérive du banc)* | — | Réparée : 15 % → 4 % |

### Ce qui n'a pas pu être fait, et pourquoi

**MoreCulling**, **ImmediatelyFast**, **RRLS**, **DynamicFPS** ciblent tous **26.2**, pas 26.1.2.

L'historique de ces dépôts a été récupéré et fouillé — c'était la bonne idée, et elle a rendu des
renseignements précis :

| Dépôt | Ce que l'historique contient | Verdict |
|---|---|---|
| **ImmediatelyFast** | commit `980355d` « Added support for Minecraft **26.1.2** » | Code applicable, mais voir ci-dessous |
| **RRLS** | tags `26.1-5.2.4`, `26.1-5.2.5`, `26.1-5.2.6` | Une version 26.1 existe |
| **MoreCulling** | commit `d0bac28` « Port to 26.2 » | L'avant-dernier état ciblait 1.21 |

**Le blocage d'`enhanced_batching`, trouvé en lisant le code 26.1.1.** C'est le cœur
d'ImmediatelyFast : un `BufferSource` qui regroupe les lots au lieu de les clore un par un. Son
`drawDirect` réassigne `this.sharedBuffer`. Or en **26.1.2**, `MultiBufferSource.BufferSource`
déclare ce champ `protected **final**` — et leur configuration de mixins ne contient aucun
accesseur pour le rendre modifiable, ce qui signifie qu'il ne l'était pas encore en 26.1.1.

Leur code ne compile donc pas sur 26.1.2 sans un mixin supplémentaire levant ce `final` sur une
classe centrale du rendu. C'est faisable, ce n'est pas anodin : ce tampon sert **toutes** les
entités, et `ByteBufferBuilderPool` gère de la mémoire native.

**Deux modules écartés pour fragilité, pas pour difficulté.**
`skip_text_translucency_sorting` cible des lambdas par numéro (`lambda$static$22`) — un numéro qui
dépend de l'ordre de compilation et change d'une version corrective à l'autre.
`fast_text_lookup` cible une classe anonyme (`Font$GlyphVisitor$1`). Ni l'un ni l'autre n'est
mesurable sur les charges de ce laboratoire, et un mod qu'on installe sans y penser ne doit pas
reposer sur des noms que Mojang ne promet pas.

## La super-résolution, et donc le DLSS

La question était ouverte depuis le premier jour : ce projet avait refusé de promettre DLSS avant
d'avoir lu comment un mod Java obtient un contexte graphique. C'est fait — `superresolution`
(GPL-3.0-or-later) cible **26.1.x**, donc exactement cette version.

**Ce que le dépôt contient, vérifié :**

- **Aucun binaire natif.** Zéro `.dll`, zéro `.so`. Les bibliothèques propriétaires ne sont pas
  redistribuées, et ne pourraient pas l'être.
- **Les algorithmes d'AMD sont des shaders GLSL**, livrés en source, sous **licence MIT**
  (`ffx_a.h`, `ffx_fsr1.h` — « Copyright (c) 2021 Advanced Micro Devices »).
- **DLSS et XeSS passent par Vulkan** : le mod embarque sa propre couche
  (`io.homo.superresolution.core.graphics.vulkan.VulkanDevice`). C'est la raison de ses
  **997 fichiers**.

**La conclusion est nette, et elle sépare deux choses qu'on confond souvent :**

| | Faisable dans Lanterne ? | Pourquoi |
|---|---|---|
| **DLSS** | **Non** | Exige un contexte Vulkan — Minecraft rend en OpenGL — *et* la bibliothèque NVIDIA, qui n'est pas redistribuable. Bâtir une couche Vulkan est un projet à part entière, pas un module. |
| **XeSS** | Non | Même dépendance Vulkan. |
| **FSR 1** | **Oui** | Un shader de post-traitement pur, en GLSL, sous MIT. Rend à résolution réduite puis rehausse. Aucune dépendance propriétaire, aucun Vulkan. |

Autrement dit : **le bénéfice recherché — plus d'images en échange d'un peu de netteté — est
atteignable sans DLSS**, par FSR 1, et c'est la seule voie qu'un mod puisse emprunter sans
embarquer un pilote graphique. C'est la prochaine grosse pièce client, et elle mérite sa propre
étape plutôt qu'une ligne en fin de vague.

**Exordium** demande un tampon d'image séparé et son propre auteur annonce « still work in
progress, there will be issues ». Hors de question dans un mod qui se veut celui qu'on installe
sans y penser.

**ModernFix** cible 1.20.1 et pèse des milliers de lignes.

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
