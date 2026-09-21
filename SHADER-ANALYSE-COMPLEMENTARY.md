# Analyse de Complementary Reimagined et conception de l'optimiseur de shaders de Lanterne

Rédigé le 21/09/2026. Outils et code : `tools/shaderopt/` (analyse hors jeu) et
`src/main/java/fr/clubcitrouille/lanterne/client/shaderopt/` (Java pur, sans dépendance à Minecraft).
Chaque chiffre ci-dessous vient d'une exécution réelle de ces outils sur les packs téléchargés pour test local ;
ce qui n'est pas encore mesuré en jeu est marqué **« à mesurer »**.

## État au 21/09/2026 (chantier mis en pause, reprise demain)

- **L'optimiseur reste DÉSACTIVÉ PAR DÉFAUT et n'est branché à aucun chemin de rendu.** Les classes de `client/shaderopt/` sont compilées
  (jar 26.3.260) mais rien ne les appelle : aucun joueur n'en subit l'effet.
- **Prouvé hors jeu** (`glslang`, 8 packs) : la spécialisation d'état neutre compile aussi bien que l'original (0 régression sur les 6 packs validables),
  réduit le source de 22 à 50 %, et 15 tests automatiques passent (`FolderSelfTest`).
- **Non mesuré en jeu** : le gain en FPS, l'iso-rendu par différence d'image, le comportement sur Bliss et Photon (non validables hors jeu). Les jobs A/B du premier essai ont planté (runner défaillant, voir §6) ;
  redéposés à 13:43, ils ont ensuite tourné sur le jar 106 au rendu cassé : **résultats invalides** (voir l'avertissement ci-dessous). À refaire avec le jar 110.
- **Conditions pour l'activer** : (1) gain mesuré en jeu sous le protocole de `OBJECTIFS-FPS.md` ; (2) iso-rendu prouvé par différence d'image
  (seuil documenté) ; (3) **avis écrit de Complementary Development** (voir §0).

> **⚠ TOUT RÉSULTAT OBTENU SUR LE JAR 26.3.106 EST INVALIDE.** Le 106 rend de façon EXPLOSÉE avec des shaders (garde Sodium fondée sur `ModList`,
> corrigée par `bff38e7` dans 26.3.110). Vérifié : la capture `C:\bench\results\20260921-134328-shopt_A1_orig.debut.png` (job sur le 106) montre une
> géométrie déformée et des couleurs aberrantes. Les jobs `shopt_A1_orig`, `B1_neutre`, `A2_orig`, `B2_neutre` des 12:43 et 13:43 ont tourné sur ce jar : leurs FPS
> (de 98,9 à 149,9, non repris ici) et toute comparaison d'iso-rendu **n'ont aucune valeur**. Le §6 est donc à refaire avec le **jar 26.3.110** ;
> `submit-ab.ps1` l'utilise maintenant par défaut et demande une capture (`-Screenshot`). **Avant de retenir un chiffre : lire la capture pour vérifier que
> le rendu est correct**, puis comparer orig/neutre par `tools/shaderopt/Cmp.java` (écart maximal ≤ 1/255 sur ≥ 99,9 % des pixels). Non relancé à 14 h : le runner du banc est
> occupé par les jobs d'autres agents (`C:\bench\status.ps1`) et la règle est de ne lancer que s'il est libre.

## 0. Licence : ce qu'on a le droit de faire (à lire avant tout)

Complementary Reimagined r5.9.3 est sous la **Complementary License Agreement 1.7** (fichier `License.txt` du pack).

| Point | Ce que dit la licence | Conséquence pour Lanterne |
|---|---|---|
| Usage normal (§0.2, §1.1) | Jouer et créer du contenu. Redistribuer ou **modifier le code du pack n'est PAS un usage normal**. | On ne livre JAMAIS de fichiers de shaders modifiés. |
| Modpack (§1.2) | Autorisé si le pack est **crédité**, **non modifié** (« pas même une variable »), et ajouté **via Modrinth/CurseForge**, pas par envoi de fichier direct. | Notre modpack ne doit pas embarquer le zip du shader : il le référence par le système de CurseForge. |
| Pack modifié (§1.3) | Redistribuable seulement s'il porte un autre nom, a un rendu visiblement différent, et contient beaucoup de code nouveau écrit à la main. | Hors sujet pour nous. |
| Tout le reste (§2.1) | « All Rights Reserved ». | Zone grise à traiter. |

**Point de vigilance juridique (non tranché).** Notre optimiseur transforme le GLSL **en mémoire, sur la machine du joueur**, au chargement,
sans jamais écrire ni distribuer de fichier modifié. C'est le même mécanisme qu'Iris (le patcher `TransformPatcher` réécrit déjà
chaque shader de chaque pack pour le rendre compatible). Mais la licence définit l'usage normal comme excluant « modifier le code du pack », et une
optimisation n'est pas une correction de compatibilité. **Recommandation** : (1) rester strictement à iso-rendu, (2) journaliser
clairement l'activation, (3) prévoir un interrupteur global et par pack, (4) demander l'avis écrit de Complementary Development
(contact sur complementary.dev) avant de l'activer par défaut. Les autres packs testés : BSL, Solas et Bliss sont en « tous droits
réservés » sur Modrinth ; Photon et Rethinking Voxels ont une licence propre ; **MakeUp Ultra Fast est en LGPL-3.0**, la seule base sur laquelle
modifier et redistribuer serait clairement permis.

## 1. Structure du pack

Mesuré avec `ShaderTool scan` :

| | Complementary Reimagined r5.9.3 |
|---|---|
| Fichiers / taille | 426 fichiers, 1,2 Mo |
| Programmes (fsh/vsh/gsh/csh) | 108 / 108 / 0 / 3 |
| Passes distinctes | 37 (7 `composite`, 1 `deferred`, gbuffers, `shadow`, `shadowcomp`, `final`, DH) |
| Options (`//[...]`) | 232 |
| Profils | 8 : POTATO, VERYLOW, LOW, MEDIUM, HIGH, VERYHIGH, ULTRA, COMPLEMENTARY |
| Uniformes personnalisés | 30 |
| Images / SSBO | 7 / 4 (voxelisation, éclairage coloré par remplissage) |

Organisation : chaque `world0/x.fsh` n'est qu'une coquille (`#define FRAGMENT_SHADER`, `#define GBUFFERS_TERRAIN`, puis
`#include "/program/x.glsl"`). Le vrai code est dans `program/*.glsl` (515 lignes pour le terrain) et dans `lib/` (1,1 Mo :
atmosphère, nuages, éclairage, TAA, voxelisation…), assemblés par les `#include`. Après préprocessing avec les options du profil MEDIUM,
`gbuffers_terrain` fait **3 754 lignes**, `deferred1` 1 280, `composite4` 682.

Rôle des passes plein écran (d'après les `#include` de chaque programme) :

| Passe | Rôle |
|---|---|
| `deferred1` | éclairage différé principal : ambiant, SSAO, ciel, nuages, aurores, étoiles, brouillard |
| `composite` | ciel/atmosphère et brouillard pour les éléments translucides |
| `composite1` | brouillard d'eau, filtre de flou des réflexions, ombres des translucides, brouillard de bloom |
| `composite2` | « réservée pour un usage futur, complètement inutilisée » (dit le fichier lui-même ; aucun `world0/composite2.fsh` ne l'appelle) |
| `composite3` à `composite5` | bloom (pyramide de flou) et lens flare |
| `composite6` | TAA (anti-crénelage temporel) |
| `composite7` | FXAA (désactivé selon profil) |
| `final` | tonemapping, texte d'erreur |

## 2. Coût statique par passe (profil MEDIUM)

Outil : `ShaderTool cost`. **Score = 10·échantillons + 4·transcendantes + 2·racines/normalisations + 0,25·opérations arithmétiques**,
pire cas (les deux branches d'un `if` comptent, une boucle à borne inconnue compte 8 tours). C'est un ordre de grandeur RELATIF, pas un
temps.

| Passe | Lignes | Échantillons | Transc. | ALU | Score |
|---|---|---|---|---|---|
| `gbuffers_terrain` (par fragment) | 3 754 | 119 | 322 | 7 121 | 4 562 |
| `gbuffers_block` | 1 164 | 113 | 295 | 5 265 | 3 908 |
| `gbuffers_water` | 1 627 | 75 | 79 | 1 735 | 1 590 |
| `gbuffers_entities` | 4 133 | 76 | 65 | 2 454 | 1 668 |
| `deferred1` (plein écran) | 1 280 | 72 | 55 | 2 717 | 1 741 |
| `composite1` | 1 016 | 170 | 47 | 1 475 | 2 303 |
| `composite4` (bloom) | 682 | 343 | 14 | 3 597 | 4 385 |
| `composite6` (TAA) | 802 | 41 | 1 | 349 | 505 |
| `final` | 696 | 6 | 1 | 41 | 74 |
| `shadow` | 747 | 3 | 9 | 94 | 102 |
| **Somme plein écran** | | 708 | | | **10 155** |

**Limite importante du modèle** : il additionne les 7 « tuiles » de bloom de `composite4`, alors qu'un pixel n'appartient qu'à une seule
tuile (garde `if (abs(coord.x - 0.5) < padding ...)`). Le score de 4 385 pour `composite4` est donc **très surestimé**. Seule une mesure par
requêtes GPU (`GL_TIME_ELAPSED` par passe) donnera le vrai coût : **à mesurer** avec les chronomètres par passe de l'agent « bilan ».

### Effet des profils (somme plein écran, score statique)

| Profil | Plein écran | Terrain | Eau | Ombre |
|---|---|---|---|---|
| POTATO | 6 515 | 3 796 | 512 | 21 |
| LOW | 7 424 | 3 925 | 964 | 21 |
| MEDIUM | 10 155 | 4 562 | 1 590 | 102 |
| HIGH | 12 179 | 4 563 | 1 590 | 102 |
| ULTRA / COMPLEMENTARY | 15 804 | 5 303 | 5 280 | 135 |

Passer de MEDIUM à POTATO réduit le coût plein écran d'environ 36 %, mais **avec perte visuelle** (niveau 3). Ces profils sont la base d'un
sélecteur automatique pour petites configs (travail parallèle de l'agent d'échelle dynamique).

## 3. Transformations à iso-rendu (niveau 1) : ce qui est mesuré

Code : `ConstFolder`, `AttachmentGraph`, `CostModel`, `ShaderOptimizer`.

### 3.1 Spécialisation sur l'état neutre

Idée : quand le joueur est au sec, sous un ciel dégagé, sans aveuglement, `isEyeInWater`, `rainStrength`, `wetness`, `thunderStrength`,
`blindness`, `darknessFactor`, `darknessLightFactor` et `nightVision` valent exactement 0. On recompile le shader avec ces uniforms
remplacés par des constantes ; dès que l'état change on revient à la version générale. Le rendu est **identique** puisque les valeurs sont
celles de l'état courant. Le pilote ne peut pas le faire seul : il ignore la valeur des uniforms.

Résultats réels (`SpecTool spec`, glslang comme validateur, mode choisi pour l'original et gardé pour le transformé) :

| Pack | Programmes avec uniforms d'état | Régressions de compilation | Lignes avant → après | Score statique total | Plein écran |
|---|---|---|---|---|---|
| Complementary Reimagined (MEDIUM) | 32 | **0** | 45 152 → 35 298 (−22 %) | −0,9 % | −0,8 % |
| BSL 10.1.7 | 19 | 0 | 9 662 → 6 462 (−33 %) | −0,6 % | −2,4 % |
| MakeUp Ultra Fast 9.5e | 30 | 0 | 8 921 → 5 628 (−37 %) | −0,6 % | −3,0 % |
| Rethinking Voxels beta9 | 32 | 0 | 44 577 → 30 740 (−31 %) | −0,5 % | −0,8 % |
| Solas 3.7b | 17 | 0 | 9 573 → 8 938 (−7 %) | −1,7 % | −0,6 % |
| Bliss 2.1.2 | 23 | 0 (non validable, voir §5) | 17 681 → 8 773 (−50 %) | −4,5 % | −4,7 % |
| Photon 1.3b | 21 | 0 (2 programmes validables) | 45 286 → 31 504 (−30 %) | −0,1 % | −0,1 % |

Lecture honnête :
- **Le gain de calcul statique est faible** (de −0,1 % à −4,7 %). Les branches d'état sont déjà peu coûteuses par pixel.
- **Le gain de taille de source est important** (−22 % à −50 %) : c'est du code mort et des fonctions devenues inutilisées. Effet attendu :
  **compilation plus rapide** des shaders (moins de saccade au chargement du pack ou au changement d'option), pas des FPS.
- **Le gain réel en FPS vient peut-être de l'occupation du GPU** (moins de registres quand le code des branches rares disparaît). Le modèle
  statique ne peut pas le voir. C'est l'expérience A/B du §3.4.

Un bug utile trouvé par la mesure : après figement, `denseFogColor[isEyeInWater - 2]` devient un indice constant négatif que le compilateur
refuse **même dans du code mort** (avec un uniform il l'ignorait). Cinq programmes de BSL et trois de Solas cessaient de compiler. Correctif : supprimer les
fonctions devenues inutilisées après repli des branches. C'est aussi la raison d'être du principe de repli (§4).

### 3.2 Écritures de pièces jointes jamais relues

`SpecTool attach` (options du profil appliquées, programmes désactivés par `shaders.properties` exclus) :

| Pack | Écritures jamais relues | Détail |
|---|---|---|
| Complementary (MEDIUM) | 2 | `colortex1` (écrit par `gbuffers_weather`, `shadow`), `colortex7` (écrit par `composite`, RGBA16F) |
| Complementary (POTATO) | 3 | + `colortex2` |
| BSL | 1 | `colortex6` (écrit par `dh_terrain`) |
| MakeUp Ultra Fast | 1 | `colortex6` (`composite`) |
| Photon | 1 | `colortex2` (`gbuffers_basic`, `gbuffers_line`) |
| Bliss | 1 | `colortex12` (`composite1`, `deferred1`) |
| Solas, Rethinking Voxels | 0 | |

Gain attendu **faible** : la plupart sont des passes secondaires (météo, ligne, DH). L'exception est `colortex7` de Complementary : une écriture
RGBA16F plein écran par image dans `composite` qui n'est relue par personne à MEDIUM. À vérifier en jeu avant d'implémenter la suppression.

### 3.3 Candidats sans perte identifiés (à prouver avant de coder)

1. **Bloom séparable, échantillonnage linéaire** : `composite4` calcule un noyau gaussien 7×7 (49 échantillons) avec `wg = weight[i+3] * weight[j+3]`,
   donc séparable. Avec le filtrage bilinéaire matériel, un noyau 1D à 7 points se replie en 4 échantillons : **49 → 16 (−67 %)**, identique aux
   arrondis près, si le sampler est bilinéaire. Transformation générique par reconnaissance de motif (boucles imbriquées, poids produit, offsets
   `vec2(i,j)/view`). À prouver par différence d'image ; gain réel **à mesurer** (les pixels hors tuile ne font pas de lecture).
2. **Variantes par état** en production : le §3.1 mais sur plusieurs bits d'état (jour/nuit, souterrain, cycle de la lune) avec cache de variantes
   compilées en tâche de fond ; on ne bascule qu'une fois la variante prête (aucune saccade).
3. **Suppression des fonctions mortes seule** (déjà écrite) : à activer même sans spécialisation, pour réduire le temps de compilation.

## 4. Principe de sûreté : ne jamais dégrader un pack qu'on ne comprend pas

`ShaderOptimizer.apply` reçoit un **validateur**. Le texte transformé n'est retenu que si le validateur l'accepte ; sinon (compilation refusée,
exception, différence d'image hors seuil) on garde le shader d'origine et on journalise. En jeu, le validateur sera la compilation réelle du pilote :
si la variante ne compile pas, Iris continue avec la version générale. C'est ce qui rend l'optimiseur sûr sur un pack inconnu, et c'est ce que le
test hors jeu vérifie : **0 régression sur 7 packs** une fois les fonctions mortes retirées.

Niveaux (règle d'iso-qualité de `OBJECTIFS-FPS.md`) :

| Niveau | Contenu | Par défaut |
|---|---|---|
| 1 sans perte | spécialisation d'état, fonctions mortes, écritures jamais relues, bloom séparable (si prouvé) | **activé** |
| 2 quasi sans perte | précision fp16 sur des calculs à erreur bornée (iGPU AMD), avec écart mesuré | désactivé |
| 3 avec perte | demi-résolution d'effets, moins d'échantillons, profil plus bas | désactivé, par profil, écart affiché |

## 5. Compatibilité mesurée : ce qu'on sait valider hors jeu

Validateur : `glslang` 16.6.0 (Khronos, release Windows) en mode « front-end » sur le GLSL aplati. C'est **plus strict que les pilotes**
et ne reproduit pas les corrections d'Iris : la validité de base d'un pack (ce qu'on obtient sur l'original) est donc inférieure à 100 %,
sans que le pack soit cassé en jeu. Voir `SHADER-COMPAT-MATRICE.md` pour le détail par pack. Là où l'original ne compile pas dans `glslang` (Bliss,
Photon en majorité), on ne peut pas valider hors jeu : l'optimiseur y compte sur le repli à l'exécution.

## 6. Mesure en jeu (expérience A/B)

Copie **locale de test, non redistribuée** de Complementary où les 7 uniforms d'état de `lib/uniforms.glsl` sont des constantes
(`ComplementaryReimagined_r5.9.3_NEUTRAL_TESTONLY.zip`, même nombre de programmes valides que l'original : 51/65). Quatre jobs déposés dans le banc
central, alternés pour neutraliser les dérives : `shopt_A1_orig`, `shopt_B1_neutre`, `shopt_A2_orig`, `shopt_B2_neutre` (Lanterne 26.3.106 + Sodium, RTX 3080,
1280×720, distance de rendu 12, heure figée 12500, 60 s, DH coupé). **Résultat : à mesurer.** Le runner du banc central a exécuté ces jobs à 12:46 mais **tous les jobs
de la file (les miens comme ceux des autres agents) ont planté en ~8 s** : le fichier `jvm.args` généré coupe le classpath sur des lignes se terminant par `;\` et Java
prend `;\` pour le nom de la classe principale (`Erreur : impossible de trouver ou de charger la classe principale ;\`, voir `stderr.log` dans `C:\bench\run\<job>\`). Ce n'est pas lié
au pack. Le runner a depuis été réparé (un autre job a donné 192,9 FPS sur la RTX). **Les 4 jobs A/B ont ensuite tourné, mais sur le jar 106 au rendu cassé : leurs résultats sont INVALIDES** (voir l'avertissement en tête de document). **À refaire avec le jar 110** : `powershell -File tools\shaderopt\submit-ab.ps1` (jar 110 et capture par défaut), seulement quand `C:\bench\status.ps1` montre le runner libre. Lire chaque capture, puis comparer orig/neutre avec `Cmp.java`. Comparer les moyennes A et B ; un écart inférieur à la dispersion A1/A2 signifie
« pas de gain mesurable ».

## 7. Plan « rupture technologique » sous Vulkan, ordonné par le critère sans perte

Réaliste, sans promesse. Du plus sûr au plus ambitieux :

1. **Constantes de spécialisation SPIR-V.** Les options du pack (`#define`) et l'état de jeu deviennent des constantes de spécialisation : un seul SPIR-V,
   des pipelines spécialisés sans branches, créés à la demande et mis en cache (`VkPipelineCache`, déjà présent dans Lanterne). Sans perte. Équivalent
   Vulkan du §3.1, mais sans recompiler le GLSL. **Prérequis : lever le blocage shaderc** (chantier Vulkan en cours).
2. **Fusion des passes ponctuelles en sous-passes** (`input attachments`). Deux passes plein écran qui ne lisent que le pixel courant de la précédente peuvent
   partager un rendu : on n'écrit ni ne relit la texture intermédiaire. Gain de bande passante important sur GPU intégré. Sans perte. **Limite** : les passes de
   Complementary lisent des voisins (flou, SSAO, TAA), donc seules les suites ponctuelles se fusionnent (ex. tonemapping après composition).
3. **Compute asynchrone** pour recouvrir la carte d'ombres ou la voxelisation et le SSAO sur GPU dédié. Sans perte, gain variable.
4. **Sous-groupes (subgroups)** pour les réductions et flous (moyennes de luminance, bloom) : moins d'accès mémoire. Sans perte si les sommes sont
   exactes au flottant près.
5. **Ombrage à cadence variable** (fragment shading rate) sur ciel et lointain : proche du sans perte, à classer niveau 2 tant que l'écart n'est pas mesuré.

Cette liste est un plan de recherche : aucun de ces points n'est implémenté ni mesuré ici.

## 8. Ce qui reste à faire

- Lire les résultats du banc (A/B du §6) et décider de l'intégration côté Iris (`TransformPatcher.transform`, où on branche le validateur).
- Chronomètres GPU par passe (agent bilan) pour remplacer le score statique par des millisecondes.
- Implémenter la reconnaissance du bloom séparable et prouver l'iso-rendu par différence d'image.
- Avis écrit de Complementary Development sur l'usage à l'exécution.
