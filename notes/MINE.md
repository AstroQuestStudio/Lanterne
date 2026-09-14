# La mine — 202 mods tries par ce qu'ils font

Genere par `tools/carte.py` depuis l'API Modrinth (`notes/mine.json`).
Pas ecrit a la main : la licence et la version viennent de la source, pas de la memoire.

| Colonne | Ce qu'elle decide |
|---|---|
| **Licence** | MIT/CC0/Apache : absorbable tel quel. LGPL/GPL : absorbable **seulement** si Lanterne passe sous la meme licence. |
| **26.x** | `oui` = le mod est vivant sur ta version. L'absorber, c'est signer pour le re-maintenir contre son auteur. |
| **Copie** | `oui` : on peut reprendre le code sous GPL-3. `non` : licence fermee, non commerciale ou sans derivee — **seule l'idee est reutilisable**, il faut reecrire. |
| **Verdict** | **PRENDRE** : absorber le code. **REFAIRE** : l'idee vaut, le code est interdit. MESURER : banc avant code. `a cote` : reste un mod separe. `non` : redondant, mort, ou hors sujet. |

## Le moteur de rendu — on cohabite, on n'absorbe pas

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [threatengl](https://github.com/Richy-Z/ThreatenGL) | LGPL-3.0-only | oui | 1.21.11 | MESURER | Force un OpenGL moderne. A verifier sur 26.1 : peut deja etre le cas. |
| [distanthorizons](https://gitlab.com/distant-horizons-team/distant-horizons) | LGPL-3.0-only | oui | oui | a cote | Vue lointaine en LOD. Enorme et vivant. Cohabite. |
| [iris](https://github.com/IrisShaders/Iris) | LGPL-3.0-only | oui | oui | a cote | Les shaders. Depend de Sodium. Meme raisonnement. |
| [sodium](https://github.com/CaffeineMC/sodium) | LicenseRef-Polyform-Shield-1.0.0 | non | oui | a cote | 50k lignes de moteur de chunks. L'absorber = le re-maintenir contre l'upstream a chaque version. |
| [sodium-extra](https://github.com/FlashyReese/sodium-extra-fabric) | LGPL-3.0-only | oui | oui | a cote | Options de Sodium. Rien a absorber sans Sodium. |
| [veil](https://github.com/FoundryMC/Veil) | LGPL-3.0-only | oui | 1.21.1 | a cote | Bibliotheque de rendu pour moddeurs. Pas une optimisation. |
| [voxy-server-side](https://github.com/VoX/lod-server-support) | MIT | oui | oui | a cote | Idem. |
| [voxy-worldgen](https://github.com/iSeeEthan/voxy_worldgen_v2) | LicenseRef-iSeeEthan-Custom-License | non | oui | a cote | Lie a Voxy. |
| [embeddium](https://github.com/FiniteReality/embeddium) | LGPL-3.0-only | oui | 1.21.4 | non | Fork Forge de Sodium. Sodium est en 26.x nativement : le fork n'a plus d'objet. |
| [gputape](https://github.com/ITsMrToad/GpuTape) | GPL-3.0-only | oui | oui | non | Annonce instable par son propre auteur. Pas dans un modpack de reference. |
| [graphene](https://github.com/CarbonMC-CN/Graphene) | MIT | oui | 1.21.9 | non | Idem, et arrete en 1.21.9. |
| [rubidium](https://github.com/Asek3/Rubidium) | LGPL-3.0-only | oui | 1.20.1 | non | Idem, plus ancien encore. |
| [rubidium-extra](https://github.com/dima-dencep/rubidium-extra) | LGPL-3.0-only | oui | 1.21.1 | non | Doublon pour un fork mort. |
| [sodium-extra-information](https://github.com/anviaan/SodiumExtraInformation) | GPL-3.0-or-later | oui | oui | non | Affichage de debug. |
| [sodium-extras](https://github.com/txnimc/SodiumExtras) | LGPL-3.0-only | oui | 1.21.5 | non | Doublon de sodium-extra. |
| [sodium-options-mod-compat](https://github.com/txnimc/SodiumOptionsModCompat/) | LicenseRef-Tonis-MMC-License | non | 1.21.4 | non | Colle entre Sodium et d'autres mods. |
| [xenon-forge](https://github.com/anthxnymc/xenon) | LGPL-3.0-only | oui | 1.20.1 | non | Idem. |

## Le culling — la plus grosse reserve client non couverte

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [cull-leaves](https://github.com/TeamMidnightDust/CullLeaves) | MIT | oui | oui | **PRENDRE** | Feuilles : ne rend pas l'interieur des arbres. Petit code, gain net. |
| entity-collision-fps-fix *(pas de source)* | ? | ? | — | **PRENDRE** | MC-228976 : les collisions d'entites sur le fil de rendu. Cible nommee par toi. |
| [moreculling](https://github.com/fxmorin/moreculling) | GPL-3.0-only | oui | oui | **PRENDRE** | Culling des faces de blocs. Complementaire, pas redondant. |
| [entityculling](https://github.com/tr7zw/EntityCulling) | LicenseRef-tr7zw-Protective-License | non | oui | **REFAIRE** | Raycast asynchrone : n'affiche pas les entites derriere un mur. LE gain client le plus sur. **Code non copiable : a reecrire depuis l'idee.** |
| [brute-force-rendering-culling](https://github.com/RogoShum/BruteForceRenderingCulling) | LGPL-3.0-only | oui | 1.20.6 | MESURER | Culling par occlusion GPU. Gain annonce enorme, reputation d'artefacts. |
| [collision-fix](https://github.com/LopyMine/CollisionFix) | CC0-1.0 | oui | oui | MESURER | Empeche de se faire pousser. Touche au jeu, pas seulement aux perfs. |
| [too-many-entities](https://github.com/Discusser/TooManyEntities) | MIT | oui | oui | MESURER | Cache les entites en trop. Recouvre le culling. |
| [cull-less-leaves-reforged](https://github.com/CCr4ft3r/CullLessLeavesReforged) | LGPL-3.0-only | oui | 1.20.2 | non | Meme idee que cull-leaves, une seule suffit. |
| [cull-particles-multiloader](https://github.com/Commander07/CullParticles) | CC0-1.0 | oui | 1.21.5 | non | Idem, doublon. |
| [cullify](https://github.com/FluxsyumStudios/Cullify) | GPL-3.0-only | oui | oui | non | Idem pour l'herbe : a plier dans le meme module. |
| mob-collision-off *(pas de source)* | LicenseRef-All-Rights-Reserved | non | oui | non | Pas de source publique, et change le jeu. |
| no-collision *(pas de source)* | MIT | oui | oui | non | Datapack, meme effet. |
| [particle-culling](https://github.com/bl4ckscor3/ParticleCulling) | MIT | oui | 1.12.2 | non | Mort en 1.12.2. L'idee va dans le module particules. |
| [sodiumleafculling](https://github.com/txnimc/SodiumLeafCulling) | MIT | oui | 1.21.1 | non | Idem, et lie a Sodium. |

## Les block entities — coffres, panneaux, cadres, peintures

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [fast-item-frames](https://github.com/Fuzss/fastitemframes) | MPL-2.0 | oui | oui | **PRENDRE** | Les cadres sont un cout serveur ET client sur les grosses bases. |
| [faster-block-entities](https://github.com/0x1bd/Faster-Block-Entities) | LGPL-3.0-or-later | oui | oui | **PRENDRE** | Transforme les block entities en modeles ordinaires. Porte en 26.x : la reference. |
| [feytweaks](https://github.com/feytox/FeyTweaks) | MIT | oui | 1.21.1 | **PRENDRE** | Panneaux et balises. Deux couts classiques. |
| [obe](https://github.com/maDU59/OptimisedBlockEntities) | LGPL-3.0-or-later | oui | oui | **PRENDRE** | Rend les block entities plus vite cote client. |
| [better-block-entities](https://github.com/ceeden/betterblockentities) | LGPL-3.0-or-later | oui | oui | MESURER | Vivant en 26.x. A comparer a OBE avant de choisir. |
| fast-paintings *(pas de source)* | LicenseRef-All-Rights-Reserved | non | oui | MESURER | Pas de source publique : a refaire, pas a copier. |
| [ebe-forge](https://github.com/ITsMrToad/EBEReforged) | GPL-3.0-only | oui | 1.20.4 | non | Meme famille qu'OBE, plus ancien. |
| sf+ *(pas de source)* | CC-BY-4.0 | oui | oui | non | Pas de source, et feytweaks couvre les panneaux. |

## Les particules et les sons

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [limited-damage-indicator-particle](https://github.com/FOOLSIX/ldip) | GPL-3.0-only | oui | 1.21.4 | **PRENDRE** | Trivial et efficace en combat de masse. |
| [particle-core](https://github.com/fzzyhmstrs/pc) | MIT | oui | oui | **PRENDRE** | Limites et rendu des particules. MIT, vivant en 26.x. |
| [sound-culling](https://github.com/Cukkoo12/SoundCulling) | MIT | oui | oui | **PRENDRE** | Limite les sons superposes. Cout CPU reel dans les fermes. |
| [asyncparticles](https://github.com/Harveykang/AsyncParticles) | LGPL-3.0-only | oui | oui | MESURER | Les mettre sur un autre fil. Attention aux courses de donnees. |
| [smart-particles](https://github.com/chedidandrew/Smart_Particles) | MIT | oui | oui | MESURER | Concurrent direct de particle-core. Une seule des deux. |

## L'image — cadence, latence, mise a l'echelle

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [better-biome-blend](https://github.com/FionaTheMortal/better-biome-blend) | Unlicense | oui | oui | **PRENDRE** | Melange des biomes en cache. Cout reel au chargement de chunk. |
| [dynamic-fps](https://github.com/juliand665/Dynamic-FPS) | MIT | oui | oui | **PRENDRE** | Baisse la cadence en arriere-plan. Indispensable en modpack. |
| [exordium](https://github.com/tr7zw/Exordium) | GPL-3.0-only | oui | oui | **PRENDRE** | Rend l'interface moins souvent que le monde. Gros gain avec JEI ouvert. |
| [fast-items](https://github.com/Noryea/fast-items-fabric) | CC0-1.0 | oui | 1.21.4 | **PRENDRE** | Items au sol en 2D. Tu l'as signale toi-meme : gros gain sur les fermes. |
| [immediatelyfast](https://github.com/RaphiMC/ImmediatelyFast) | LGPL-3.0-or-later | oui | oui | **PRENDRE** | Regroupe les appels de rendu immediat. Gain large et sans effet de bord. |
| [inputbooster](https://github.com/Ahaduzzamankhan/inputbooster) | MIT | oui | oui | **PRENDRE** | Scrutation des entrees. Ressenti immediat, code court. |
| [rrls](https://github.com/dima-dencep/rrls) | OSL-3.0 | oui | oui | **PRENDRE** | Supprime l'ecran de chargement des ressources. Trivial. |
| [gnetum](https://github.com/decce6/Gnetum) | LGPL-3.0-only | oui | oui | MESURER | Etale les mises a jour du HUD. Meme famille qu'Exordium. |
| [ixeris](https://github.com/decce6/Ixeris) | LGPL-3.0-only | oui | oui | MESURER | Sort les appels GLFW du fil principal. Fragile mais reel. |
| [kerria-opt](https://github.com/decce6/Kerria) | LGPL-3.0-only | oui | 1.21.1 | MESURER | Animation des textures. Cout reel mais petit. |
| [reflex-antilag](https://github.com/Tythee/Minecraft-Reflex) | LicenseRef-All-Rights-Reserved | non | oui | MESURER | NVIDIA Reflex. Depend du pilote : a verifier qu'il s'active vraiment. |
| [superresolution](https://github.com/187J3X1-114514/superresolution) | GPL-3.0-or-later | oui | oui | MESURER | DLSS/FSR. La piste que tu vises : a instruire serieusement, voir la note dediee. |
| [bbrb](https://github.com/UntitledModGroup/better-biome-blend-reblend) | LGPL-3.0-only | oui | 1.21.11 | non | Doublon de better-biome-blend. |
| [better-clouds](https://github.com/Qendolin/better-clouds) | MPL-2.0 | oui | oui | non | Esthetique, pas une optimisation. |
| [clean-f3](https://github.com/tyrannus00/CleanF3) | MIT | oui | 1.21.8 | non | Cosmetique. |
| [idletweaks](https://github.com/Armandukx/IdleFPS) | CC-BY-NC-ND-4.0 | non | 1.21.11 | non | Doublon de dynamic-fps. |
| [modern-ui](https://github.com/BloCamLimb/ModernUI-MC) | LGPL-3.0-or-later | oui | oui | non | Refonte d'interface, pas une optimisation. |
| [veloxcaelo](https://github.com/nea89o/veloxcaelo/) | LicenseRef-All-Rights-Reserved | non | 1.8.9 | non | 1.8.9, OptiFine CIT. Hors sujet. |

## La memoire

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [ferrite-core](https://github.com/malte0811/FerriteCore) | MIT | oui | oui | **PRENDRE** | Deduplication des etats de blocs. Des centaines de Mo en modpack. |
| [modernfix](https://github.com/embeddedt/ModernFix) | LGPL-3.0-only | oui | oui | **PRENDRE** | Le plus rentable de la liste au demarrage ET en memoire. |
| [redirected](https://github.com/txnimc/Redirected) | LGPL-3.0-only | oui | 1.21.1 | **PRENDRE** | Deduplication des valeurs d'enum. Court et net. |
| [memoryleakfix](https://github.com/fxmorin/memoryLeakFix) | LGPL-2.1-only | non | 1.20.4 | **REFAIRE** | Fuites connues et documentees. Peu de code. **Code non copiable : a reecrire depuis l'idee.** |
| [mpalladium](https://github.com/ITsMrToad/PalladiumMod) | GPL-3.0-only | oui | 1.21.8 | MESURER | Annonce large et vague. A verifier avant d'y croire. |
| saturn *(pas de source)* | LGPL-3.0-only | oui | 1.21.1 | MESURER | Pas de source publique : a refaire depuis l'idee. |
| [foamfix](https://github.com/asiekierka/FoamFix) | LicenseRef-Custom | non | 1.14 | non | Ancien, largement absorbe par FerriteCore. |

## Le demarrage, le chargement, les recettes

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [fastquit-forge](https://github.com/frcvdt45g6by7hnj8ukm-nh8b7g6vtf5r4de3/FastQuit-Forge) | MIT | oui | 1.21.4 | **PRENDRE** | Retour au menu instantane. Idem. |
| [fastsuite](https://github.com/Shadows-of-Fire/FastSuite) | MIT | oui | oui | **PRENDRE** | Tout le systeme de recettes. MIT, deja en 26.1.2. |
| [ksyxis](https://github.com/VidTu/Ksyxis) | MIT | oui | oui | **PRENDRE** | Supprime le chargement des chunks du spawn au demarrage. Trivial, gain immediat. |
| [quick-pack](https://github.com/DrexHD/quick-pack) | MIT | oui | oui | **PRENDRE** | Lecture des zip de packs. |
| [quickquit](https://codeberg.org/MicrocontrollersDev/QuickQuit) | LGPL-3.0-only | oui | 1.8.9 | **PRENDRE** | Fermer pendant le chargement. Confort pur, deux lignes. |
| [resourcepackcached](https://github.com/Furq07/ResourcePackCached) | GPL-3.0-only | oui | oui | **PRENDRE** | Garde le pack du serveur. Gain enorme a chaque reconnexion. |
| [0pack2reload](https://github.com/Nova-Committee/0Pack2Reload) | LicenseRef-Nova-Relay-License-1.0 | non | 1.21.1 | **REFAIRE** | Evite un rechargement inutile des packs. **Code non copiable : a reecrire depuis l'idee.** |
| [jeioptimizer](https://github.com/bigenergy/JEIOptimizer) | LicenseRef-All-Rights-Reserved | non | oui | **REFAIRE** | 15x sur le filtre JEI. Tu gardes JEI : c'est donc pour toi. **Code non copiable : a reecrire depuis l'idee.** |
| [languagereloadunofficial](https://github.com/HiedaCamellia/LanguageReload) | GPL-3.0-only | oui | 1.21.3 | MESURER | Rechargement des langues. |
| [lazyyyyy](https://github.com/SettingDust/lazyyyyy) | CC-BY-SA-4.0 | oui | 1.20.1 | MESURER | Chargement des gros packs. |
| [lightspeedre](https://github.com/kltyton/LightSpeedRe) | LGPL-3.0-or-later | oui | 1.21.1 | MESURER | Fork du precedent, source a verifier. |
| [notenoughrecipebook](https://github.com/SSKirillSS/nerb) | LicenseRef-All-Rights-Reserved | non | oui | MESURER | Livre de recettes. Cout reel a la connexion. |
| smooth-boot *(pas de source)* | MIT | oui | oui | MESURER | Ordonnancement CPU au demarrage. Pas de source pour les deux forks. |
| craftboost-mccmcaozr *(pas de source)* | LicenseRef-All-Rights-Reserved | non | oui | non | Pas de source. FastSuite couvre la meme chose. |
| [distraction-free-recipes](https://github.com/txnimc/DistractionFreeRecipes) | LicenseRef-Tonis-MMC-License | non | 1.21.1 | non | Confort d'interface. |
| [fastload](https://github.com/BumbleSoftware/Fastload) | LGPL-2.1-only | non | 1.20.1 | non | Meme cible que Ksyxis. |
| lightspeed *(pas de source)* | LGPL-3.0-only | oui | 1.19.2 | non | Pas de source publique. |
| smooth-boot-reloaded *(pas de source)* | MIT | oui | 1.20.1 | non | Doublon sans source. |
| [tmrv](https://github.com/Nolij/TooManyRecipeViewers) | LicenseRef-OSL-3.0 | oui | 1.21.1 | non | Pont JEI/EMI. Hors sujet. |
| [workstation-recipe-exporter](https://github.com/Xiaoyu-2009/visual_recipe_editor) | GPL-3.0-or-later | oui | 1.20.1 | non | Outil de moddeur. |

## La plomberie — evenements, hachage, aleatoire, journalisation

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [cerulean-advancements](https://github.com/txnimc/Cerulean) | GPL-3.0-only | oui | 1.21.1 | **PRENDRE** | InventoryChangeTrigger : cout reel a chaque changement d'inventaire. |
| [efficient-hashing](https://github.com/ZZZank/EfficientHashing) | LGPL-3.0-only | oui | 1.21.1 | **PRENDRE** | Hachage de BlockPos/Vec3i. Chemin ultra-chaud, code minuscule. |
| [faster-random](https://github.com/AnOpenSauceDev/FastRandom) | Apache-2.0 | oui | 1.21.1 | **PRENDRE** | Le generateur aleatoire est partout. Apache-2.0. |
| [fastevent](https://github.com/ZZZank/FastEvent) | MIT | oui | 1.21.1 | **PRENDRE** | Le bus d'evenements NeoForge est un cout permanent en modpack. |
| [shut-up-gl-error](https://github.com/JamCoreModding/shut-up-gl-error) | MIT | oui | 1.21.11 | **PRENDRE** | Journalisation parasite. Trivial. |
| [dsbg](https://github.com/CapezOnMyBack/DSBG) | CC-BY-ND-4.0 | non | 1.21.1 | **REFAIRE** | Idem cote serveur : setBlock in a far chunk. **Code non copiable : a reecrire depuis l'idee.** |
| [asynclogger](https://github.com/decce6/AsyncLogger) | LGPL-3.0-only | oui | oui | MESURER | Journalisation asynchrone. Gain reel sur serveur bavard. |
| [uuid-hex](https://github.com/CJDevZ/UUID-Hex) | MIT | oui | oui | MESURER | Formatage d'UUID. A verifier que c'est vraiment chaud. |
| achievements-optimizer *(pas de source)* | MIT | oui | oui | non | Pas de source, meme cible. |
| [icterine](https://github.com/Mephodio/Icterine) | GPL-3.0-or-later | oui | 1.20.4 | non | Remplace par cerulean-advancements. |
| [jauml](https://github.com/MeherBenSalem/Jauml) | Apache-2.0 | oui | oui | non | Bibliotheque de configuration. Lanterne a la sienne. |
| [mixinbooster](https://github.com/sinytra/mixintransmogrifier) | MIT | oui | 1.20.1 | non | Forge ancien. NeoForge 26 n'a pas ce probleme. |
| [toadlib](https://github.com/ITsMrToad/ToadLib-mod/tree/1.19.4) | GPL-3.0-or-later | oui | oui | non | Bibliotheque d'un autre auteur. |

## Serveur — les entites et l'IA

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [despawn-tweaks](https://github.com/txnimc/DespawnTweaks) | LGPL-3.0-only | oui | 1.21.1 | **PRENDRE** | Les mobs qui ne despawnent jamais sont la premiere cause de derive. |
| [does-it-tick](https://github.com/txnimc/DoesItTickSC) | LGPL-3.0-only | oui | 1.21.1 | **PRENDRE** | Ordonnanceur de tick d'entites. Le plus proche de ce que Lanterne fait deja. |
| [harium](https://github.com/JustHari01/Harium) | LGPL-3.0-only | oui | 1.20.1 | **PRENDRE** | IA, redstone, entites, entonnoirs, physique. Le plus proche de l'ambition de Lanterne. |
| [skeleton-ai-fix](https://github.com/Fuzss/skeletonaifix) | MPL-2.0 | oui | oui | **PRENDRE** | Le strafe des squelettes est un cout ET un defaut de jeu. |
| [ai-improvements](https://github.com/BuiltBrokenModding/AI-Improvements) | LicenseRef-All-Rights-Reserved | non | oui | **REFAIRE** | Cible nommee par toi. Deja en 26.x, source lisible. **Code non copiable : a reecrire depuis l'idee.** |
| [mobtimizations](https://github.com/Corosauce/mobtimizations/) | LicenseRef-All-Rights-Reserved | non | 1.20.1 | **REFAIRE** | Taches d'IA gaspillees. Recouvre en partie EntityThrottle : a departager au banc. **Code non copiable : a reecrire depuis l'idee.** |
| [immersive-optimization](https://github.com/Luke100000/ImmersiveOptimization) | GPL-3.0-or-later | oui | oui | MESURER | Annonce un doublement de TPS. A verifier contre EntityThrottle. |
| [notick](https://github.com/Riqqqque/DoesItTickSC-main.git) | LGPL-3.0-or-later | oui | oui | MESURER | Meme famille. Trois mods pour la meme idee : n'en garder qu'une. |
| pathwright *(pas de source)* | MIT | oui | oui | MESURER | Recherche de chemin. Pas de source : a refaire depuis l'idee. |
| sheep-efficiency *(pas de source)* | MIT | oui | oui | MESURER | Les moutons qui mangent l'herbe. Pas de source, idee triviale a refaire. |
| [you-shall-not-spawn](https://github.com/nvb-uy/ysns) | LicenseRef-AGNYA-License | non | 1.21.3 | MESURER | Gestion du spawn. Lanterne a deja la Vigie : a comparer. |
| [lithium](https://github.com/caffeinemc/lithium-fabric) | LGPL-3.0-only | oui | oui | a cote | Vivant en 26.x et tres large. A comparer module par module, pas a absorber en bloc. |
| [a-star-pathfinding](https://github.com/CJDevZ/A-Star-Pathfinding) | GPL-3.0-only | oui | oui | non | Datapack. Pas applicable. |
| [async](https://github.com/AxalotLDev/Async) | GPL-3.0-or-later | oui | oui | non | Entites en parallele. Risque de course de donnees trop eleve pour un mod de reference. |
| betterspawn *(pas de source)* | LicenseRef-All-Rights-Reserved | non | oui | non | Pas de source, et hors sujet perfs. |
| canary *(pas de source)* | LGPL-3.0-only | oui | 1.20.4 | non | Idem, et sans source. |
| [lmd](https://github.com/frikinjay/let-me-despawn) | LGPL-3.0-only | oui | oui | non | Meme cible que despawn-tweaks. |
| [radium](https://github.com/Reforged-Hub/radium-upstream) | LGPL-3.0-only | oui | 1.21.1 | non | Fork Forge de Lithium, mort. |
| [zombies-reworked](https://github.com/TheASEStefan/ZombiesReworkedMod) | MIT | oui | 1.20.1 | non | Contenu, pas optimisation. |

## Serveur — chunks, generation, sauvegarde

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [noisiumforked](https://github.com/coredex-source/noisium) | LGPL-3.0-only | oui | oui | **PRENDRE** | Worldgen cible. Vivant en 26.x. |
| [structure-layout-optimizer](https://github.com/TelepathicGrunt/StructureLayoutOptimizer) | MIT | oui | oui | **PRENDRE** | Les grosses structures figent le serveur. MIT, en 26.x. |
| [zfastnoise](https://codeberg.org/ZenXArch/FastNoise) | MPL-2.0 | oui | oui | **PRENDRE** | Le bruit, c'est 30% de la generation, tu l'as dit. Cible nette. |
| born-in-a-barn *(pas de source)* | LicenseRef-All-Rights-Reserved | non | 1.12.2 | MESURER | Les villages chargent des chunks. Bug reel, sans source. |
| [deltachunk](https://github.com/user19870/delta_chunk) | MIT | oui | oui | MESURER | Reduit la taille du monde. Touche au format de sauvegarde : prudence. |
| [substrate](https://github.com/vesmaybevesper/substrate) | LGPL-3.0-only | oui | 1.21.11 | MESURER | Couches basses et hautes du monde. Idee originale a chiffrer. |
| [unloaded-activity](https://github.com/KeMoonoDev/Unloaded-Activity) | LGPL-3.0-only | oui | oui | MESURER | Rattrape les blocs apres dechargement. Change le jeu : a discuter. |
| [worldgenfeaturefix](https://github.com/SimonShiki/worldgen-feature-fix) | CC0-1.0 | oui | 1.21.11 | MESURER | Correctif de plantage. A garder sous le coude. |
| [c2me-neoforge](https://github.com/RelativityMC/C2ME-neoforge) | MIT | oui | oui | a cote | Genere les chunks en parallele. Enorme, vivant, et risque eleve. Reste a cote. |
| [c2me-ocl](https://github.com/RelativityMC/C2ME-fabric) | LicenseRef-All-Rights-Reserved | non | oui | a cote | Worldgen sur GPU via OpenCL. Fascinant, hors perimetre. |
| [chunky](https://github.com/pop4959/Chunky) | GPL-3.0-only | oui | oui | a cote | Pregeneration. Outil separe, tres bien comme il est. |
| [moonrise-opt](https://github.com/Tuinity/Moonrise) | GPL-3.0-only | oui | oui | a cote | Le plus serieux cote serveur. Vivant en 26.x. A comparer, pas a absorber. |
| [c2mef](https://github.com/sj-hub9796/c2meF) | MIT | oui | 1.20.1 | non | Fork mort. |
| [chunk_gen](https://github.com/Lucasv-github/Pregenerator/) | MIT | oui | oui | non | Datapack. |
| chunky-extension *(pas de source)* | MIT | oui | oui | non | Greffon de Chunky. |
| [huge-structure-blocks](https://github.com/SamB440/huge-structure-blocks) | LGPL-3.0-only | oui | oui | non | Contenu. |
| [noisiumed](https://github.com/imbavirus/noisiumed) | GPL-3.0-only | oui | 1.21.6 | non | Doublon. |
| [terrablenderfix](https://github.com/racoonman2/TerraBlenderFix) | MIT | oui | 1.21.1 | non | Correctif pour un mod tiers. |

## Serveur — la lumiere

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [scalablelux](https://github.com/RelativityMC/ScalableLux) | LGPL-3.0-only | oui | oui | MESURER | Starlight en mieux, d'apres toi. En 26.x. Le candidat serieux. |
| [starlight-forge](https://github.com/PaperMC/Starlight) | LGPL-3.0-only | oui | 1.20.2 | MESURER | Moteur de lumiere. Lanterne a mesure 3.8% seulement : a re-verifier avant d'y aller. |
| [alfheim-lighting-engine](https://github.com/Red-Studio-Ragnarok/Alfheim) | MIT | oui | 1.12.2 | non | 1.12.2. Les idees sont dans ScalableLux. |
| [phosphorlegacyforge](https://github.com/HowardZHY/Phosphor-Legacy-Forge) | GPL-3.0-or-later | oui | 1.11.2 | non | Mort. |
| [radon](https://github.com/Reforged-Hub/Radon) | LGPL-3.0-only | oui | 1.19.4 | non | Phosphor pour FML, mort. |

## Serveur — redstone, rails, machines

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [alternate-current](https://github.com/SpaceWalkerRS/alternate-current) | MIT | oui | oui | **PRENDRE** | Refonte du moteur de redstone. MIT, en 26.2. Tu l'as marque giga interessant : tu as raison. |
| [railoptimization](https://github.com/EasterGhost/RailOptimization) | GPL-3.0-only | oui | oui | **PRENDRE** | Rails 10x plus rapides. Code court. |
| [create-threaded-trains](https://github.com/MisterJulsen/Create-Threaded-Trains) | GPL-3.0-or-later | oui | 1.21.1 | a cote | Specifique a Create. |
| [createbetterfps](https://github.com/MoePus/CreateBetterFPS) | MIT | oui | 1.21.1 | a cote | Idem. |
| [iris-flw-compat](https://github.com/leon-o/iris-flw-compat) | CC0-1.0 | oui | 1.21.1 | a cote | Idem. |
| [autochefs-delight](https://github.com/Snownee/AutochefsDelight) | LicenseRef-All-Rights-Reserved | non | 1.21.1 | non | Specifique a Farmer's Delight. |
| [fastfurnace](https://github.com/Shadows-of-Fire/FastFurnace) | MIT | oui | oui | non | Lanterne a deja les fours, au tick pres et mesures. |
| [nora-rail-optimization](https://github.com/noramibu/RailOptimization) | LGPL-3.0-or-later | oui | oui | non | Fork du precedent. |
| [refined-fluid-substitution](https://github.com/starforcraft/Refined-Fluid-Substitution) | MIT | oui | oui | non | Specifique a Refined Storage. |
| syncboost-mccmcaozr *(pas de source)* | LicenseRef-All-Rights-Reserved | non | oui | non | Pas de source, et Lanterne a deja les entonnoirs. |

## Le reseau

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [disconnect-packet-fix](https://github.com/ascpixi/disconnect-packet-fix) | MIT | oui | oui | **PRENDRE** | MC-271325. Correctif net. |
| [fast-ip-ping](https://github.com/Fallen-Breath/fast-ip-ping) | LGPL-3.0-only | oui | oui | **PRENDRE** | Ping du menu multijoueur. Trivial et visible. |
| [krypton](https://github.com/astei/krypton) | LGPL-3.0-only | oui | oui | **PRENDRE** | La pile reseau. Lanterne a deja la compression : le reste est a prendre. |
| [not-enough-bandwidth](https://github.com/USS-Shenzhou/NotEnoughBandwidth) | GPL-3.0-or-later | oui | oui | **PRENDRE** | Economise la bande passante. Complementaire de la compression. |
| [xxl-packets](https://github.com/lazul1ne/XXLPackets) | CC0-1.0 | oui | oui | **PRENDRE** | Leve la limite de 2 Mo par paquet. Indispensable en gros modpack. |
| [bandwidthoptimizer](https://github.com/duckgun13476/BandwidthOptimizer) | LGPL-2.1-or-later | oui | oui | MESURER | Meme cible, a departager. |
| [krypton-fnp](https://github.com/404Setup/KryptonReno) | LGPL-3.0-only | oui | oui | MESURER | Extension bas niveau. |
| [resource-trimmer](https://github.com/Darkhax-Minecraft/Resource-Trimmer) | LGPL-2.1-only | non | oui | MESURER | Idem. |
| [vmp-forge](https://github.com/RelativityMC/VMP-forge) | MIT | oui | 1.20.1 | MESURER | Serveur a fort effectif. Mort en 1.20.1 mais les idees valent. |
| [xp-stream](https://github.com/Jed-Tech/XP_Stream) | CC-BY-NC-ND-4.0 | non | oui | MESURER | Concurrent de Clump. Lanterne a deja les orbes, x11.76 mesure. |
| [krypton-foxified](https://github.com/ThinkingStudios/KryptonFoxified) | LGPL-3.0-or-later | oui | 1.21.1 | non | Port NeoForge de Krypton, qui est deja en 26.x. |
| [ly-clumps](https://github.com/lullaby6/data-packs/tree/main/Clumps) | AGPL-3.0-or-later | non | oui | non | Lanterne a deja mieux, mesure. |
| pluto *(pas de source)* | LGPL-3.0-only | oui | 1.19.3 | non | Fork sans source. |

## Le nettoyage et le budget de tick

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [servercore](https://github.com/Wesley1808/ServerCore) | MIT | oui | oui | **PRENDRE** | Le plus proche de Lanterne dans l'esprit. MIT, en 26.2. A eplucher en priorite. |
| [tt20](https://github.com/snackbag/tt20) | LicenseRef-PolyForm-Shield-1.0.0 | non | oui | **REFAIRE** | Degrade proprement quand le TPS chute. Tu l'as marque cool : c'est la bonne intuition. **Code non copiable : a reecrire depuis l'idee.** |
| [potatoptimize](https://github.com/Tater-Certified/Potatoptimize) | GPL-3.0-only | oui | oui | MESURER | Greffon serveur. |
| [staaaaaaaaaaaack](https://github.com/frank89722/Staaaaaaaaaaaack) | MIT | oui | 1.21.8 | MESURER | Fusion au-dela de la pile. Va plus loin que Gather : a chiffrer. |
| entity-cleaner *(pas de source)* | LicenseRef-All-Rights-Reserved | non | oui | non | Idem, datapack. |
| [get-it-together-drops](https://github.com/bl4ckscor3/GetItTogetherDrops) | MIT | oui | oui | non | Deja absorbe dans Gather, x1.27 a x1.42 mesure. |
| opti-world *(pas de source)* | MIT | oui | oui | non | Idem. |
| [sweeper-maid](https://github.com/Viola-Siemens/Sweeper-Maid) | AGPL-3.0-only | non | 1.20.6 | non | Lanterne a deja le Balai. |
| tct-clearlag *(pas de source)* | LicenseRef-All-Rights-Reserved | non | oui | non | Idem, et sans source. |

## Confort, correctifs, et ce qui n'est pas de l'optimisation

| Mod | Licence | Copie | 26.x | Verdict | Pourquoi |
|---|---|:--:|:--:|---|---|
| [cleancut](https://github.com/Exoneration/CleanCut) | MIT | oui | oui | **PRENDRE** | Frapper a travers les hautes herbes. Confort net, code court. |
| [crashexploitfixer](https://github.com/DrexHD/CrashExploitFixer) | GPL-3.0-only | oui | oui | **PRENDRE** | Failles de plantage. Un mod de serveur doit les avoir. |
| [leaves-be-gone](https://github.com/Fuzss/leavesbegone) | MPL-2.0 | oui | oui | **PRENDRE** | Tu l'as decrit precisement, y compris le cas des feuilles posees a la main. |
| [vanillaicecreamfix](https://github.com/repletsin5/VanillaIcecreamFix) | MIT | oui | 1.21.4 | **PRENDRE** | VanillaFix : ne pas tomber au menu sur une erreur de rendu. |
| [server_heater](https://codeberg.org/NerjalNosk/server_heater) | LicenseRef-The-Lambda-License | non | oui | **REFAIRE** | Les gels a l'arret du serveur. Tu redemarres souvent. **Code non copiable : a reecrire depuis l'idee.** |
| [better-beds](https://github.com/TeamMidnightDust/BetterBeds) | MIT | oui | 1.21.4 | MESURER | Lits optimises et plus beaux. |
| fix-attack-lag *(pas de source)* | MIT | oui | 1.21.1 | MESURER | Le lag a l'attaque. Tu as signale exactement ce symptome sur les villageois. |
| mapboost-mccmcaozr *(pas de source)* | LicenseRef-All-Rights-Reserved | non | oui | MESURER | Frequence des cartes. Pas de source. |
| norollback *(pas de source)* | LGPL-3.0-only | oui | oui | MESURER | Les retours en arriere de position. Tu l'as marque important. |
| [patcher](https://github.com/Polyfrost/PolyPatcher) | CC-BY-NC-SA-4.0 | non | 1.12.2 | MESURER | Sac de correctifs. A trier un par un. |
| [curtain](https://github.com/Gu-ZT/Curtain) | LGPL-2.1-only | non | 1.21.1 | a cote | Carpet pour Forge. Outil de test, pas de production. |
| [observable](https://github.com/tasgon/observable) | MPL-2.0 | oui | 1.21.4 | a cote | Profileur. Outil, et Lanterne a deja ses bancs. |
| [accelerated-decay](https://github.com/ErrorMikey/AcceleratedDecay) | LGPL-3.0-only | oui | oui | non | Meme cible, moins propre. |
| afkfishing *(pas de source)* | MIT | oui | oui | non | Automatisation de jeu, hors sujet pour un mod de perfs. |
| bug-fixes *(pas de source)* | LicenseRef-All-Rights-Reserved | non | oui | non | Pas de source. |
| craftingtweaks *(pas de source)* | LicenseRef-All-Rights-Reserved | non | oui | non | Contenu, 700 recettes. Hors sujet. |
| [dim-tracker+afk-display](https://github.com/rogalKraft/dim-tracker-afk-display) | MIT | oui | oui | non | Datapack d'affichage. |
| [fism](https://github.com/maDU59/FasterIrisShadowMapper) | MIT | oui | oui | non | Deja integre dans Iris. |
| [item-obliterator](https://github.com/nvb-uy/item_obliterator) | LicenseRef-AGNYA-License | non | 1.21.1 | non | Outil de modpack. |
| [notsoessential](https://github.com/Scherso/NotSoEssential) | AGPL-3.0-only | non | oui | non | Specifique a Essential. |
| thirdperson *(pas de source)* | LicenseRef-ProjectMichi | non | oui | non | Contenu. |
| wither-spawn-fix *(pas de source)* | LicenseRef-All-Rights-Reserved | non | 1.21 | non | Pas de source, cas tres etroit. |

## Non classes

- `async-locator` — MIT — Changes the searching of features to be asynchronous to mitigate associated lag
- `badoptimizations` — MIT — Optimization mod that focuses on things other than rendering
- `connectored-c2me-(c2me-fork-for-connector` — ? — 
- `noisium-(unofficial-forge-port` — ? — 

## Le compte

- **PRENDRE** : 50
- **REFAIRE** : 9
- MESURER : 46
- a cote : 17
- non : 76

### Les idees qu'il faut reecrire au lieu de copier

- **0pack2reload** — `LicenseRef-Nova-Relay-License-1.0`. Evite un rechargement inutile des packs.
- **ai-improvements** — `LicenseRef-All-Rights-Reserved`. Cible nommee par toi. Deja en 26.x, source lisible.
- **dsbg** — `CC-BY-ND-4.0`. Idem cote serveur : setBlock in a far chunk.
- **entityculling** — `LicenseRef-tr7zw-Protective-License`. Raycast asynchrone : n'affiche pas les entites derriere un mur. LE gain client le plus sur.
- **jeioptimizer** — `LicenseRef-All-Rights-Reserved`. 15x sur le filtre JEI. Tu gardes JEI : c'est donc pour toi.
- **memoryleakfix** — `LGPL-2.1-only`. Fuites connues et documentees. Peu de code.
- **mobtimizations** — `LicenseRef-All-Rights-Reserved`. Taches d'IA gaspillees. Recouvre en partie EntityThrottle : a departager au banc.
- **server_heater** — `LicenseRef-The-Lambda-License`. Les gels a l'arret du serveur. Tu redemarres souvent.
- **tt20** — `LicenseRef-PolyForm-Shield-1.0.0`. Degrade proprement quand le TPS chute. Tu l'as marque cool : c'est la bonne intuition.

Une licence fermee protege l'**expression**, pas l'idee. Un raycast vers une entite, un cache de visibilite, un filtre d'ingredients : ces mecaniques se reecrivent. Ce qui est interdit, c'est d'ouvrir leur source et d'en recopier les lignes.

### Les PRENDRE, dans l'ordre du plan

`alternate-current`, `better-biome-blend`, `cerulean-advancements`, `cleancut`, `crashexploitfixer`, `cull-leaves`, `despawn-tweaks`, `disconnect-packet-fix`, `does-it-tick`, `dynamic-fps`, `efficient-hashing`, `entity-collision-fps-fix`, `exordium`, `fast-ip-ping`, `fast-item-frames`, `fast-items`, `faster-block-entities`, `faster-random`, `fastevent`, `fastquit-forge`, `fastsuite`, `ferrite-core`, `feytweaks`, `harium`, `immediatelyfast`, `inputbooster`, `krypton`, `ksyxis`, `leaves-be-gone`, `limited-damage-indicator-particle`, `modernfix`, `moreculling`, `noisiumforked`, `not-enough-bandwidth`, `obe`, `particle-core`, `quick-pack`, `quickquit`, `railoptimization`, `redirected`, `resourcepackcached`, `rrls`, `servercore`, `shut-up-gl-error`, `skeleton-ai-fix`, `sound-culling`, `structure-layout-optimizer`, `vanillaicecreamfix`, `xxl-packets`, `zfastnoise`
