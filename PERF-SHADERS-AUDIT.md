# Audit de performance du rendu avec shaders — bilan final (21/09/2026)

Agent « perf shaders ». Ne sont retenues ici que les mesures VALIDES : GPU lu dans le journal (RTX 3080 Laptop), scène restaurée par le banc central, heure figée à 12500, `inactivityFpsLimit:"minimized"`, aucun autre client (`silence=true`, `autres_clients=0`).

## 1. Mesures valides (banc central, jar 26.3.108, pack complet, DH absent, Sodium 0.9.2, 1280x720, rd 12)

| Job | Shaders | FPS moyen | ms/image | Plus bas (fenêtre 1 s) | Remarque |
|---|---|---|---|---|---|
| perfmods_P0_shoff | non | 338,7 | 2,1 | 6,7 | référence ; le plus bas est le chargement |
| perfmods_P0_shon | Complementary r5.9 | 100,2 | 6,13 | 1,8 | capture lue : rendu CORRECT sur RTX |
| perfmods_P2_BadOptimizations_shoff | non | 192,9 | 3,93 | 96,4 | UN seul échantillon, plus bas que P0 : NON concluant (bruit ou régression, non prouvé) |

Les FPS 469/477/451/474 des lignes du coordinateur (jar 106, Lanterne + Sodium seuls, sans le reste du pack) ne sont pas comparables à P0 : ensemble de mods différent.

Toutes mes mesures antérieures (bancs `perfrun5` : 145, 151, 168, 202, 73, 70, 53,6…) sont à considérer comme « scène dérivante / limite AFK / verrou absent » et ne doivent pas être citées.

## 2. Prouvé

1. **Limite AFK de Minecraft 26.3.** `options.txt` contient `inactivityFpsLimit:"afk"` par défaut : après ~60 s sans entrée, le jeu se bride à ~30 FPS. Preuve : Radiographie du jar 100 (bench propre) = 35 % du temps du fil de rendu dans `FramerateLimiter.limitDisplayFPS` (parkNanos) et 40 % du temps à 20-40 FPS. Un banc sans entrée mesure donc du vide ; le passage à `"minimized"` a fait passer le jar 38 de 145 à 202,9 FPS sur ma scène de l'époque.
2. **Profil d'allocations du fil de rendu** (JFR, jar 108, shaders actifs, RTX, 100 s, `C:\perfrun5\results\J1_j108_shaders_full.jfr`, scène non figée : l'attribution reste valable, pas le débit) :
   - 49,6 % des octets alloués passent par JourneyMap, dont 44,2 % pour la seule `TranslatableContents.decomposeTemplate` : à chaque image JourneyMap trie ses libellés et retraduit la clé à chaque comparaison (`ThemeLabelSource.getStrings` → `Constants.getString`).
   - Sodium 12,8 % (dont 218 Mo de copies de conteneurs de chunks), Iris 7,5 %, EMF/ETF 1,1 %, Lanterne 0,1 %, vanilla seul 31,9 %.
   - JourneyMap alloue aussi ~120 Mo d'`Identifier` par session (icônes de waypoints).
   - CPU du fil de rendu : piles passant par Iris 38,1 %, Sodium 30,7 %, JourneyMap 7,9 %, Lanterne 1,3 %. Pauses GC : 26 collectes, 7 à 33 ms (jeunes), une « old » à 60 ms.
3. **Ixeris 4.6.7+26.3 est incompatible avec Minecraft 26.3** : plantage à l'amorçage de FML (`NoClassDefFoundError org/lwjgl/glfw/GLFWErrorCallback`, `IxerisBootstrapper` → `findGlfwModule`). Le manifeste officiel `versions/26.3/26.3.json` ne contient plus `lwjgl-glfw` (le jeu est passé à SDL3 : « Created window using SDL video driver »). Aucun argument JVM n'y change rien. Journal : `C:\bench\results\20260921-131430-perfmods_P1_tous7_shoff.latest.log`.
4. **Les sept mods de performance existent pour 26.3/NeoForge** (Modrinth, SHA-512 vérifiés, tous « release »), dans `C:\bench\mods-perf\` : ImmediatelyFast 1.17.1+26.3 (LGPL-3.0-or-later), BadOptimizations 2.4.1 (MIT), FerriteCore 9.0.0 (MIT), EntityCulling 1.11.1 (**licence restrictive tr7zw : pas de redistribution sans accord**), Dynamic FPS 3.11.10 (MIT), Ixeris 4.6.7 (LGPL-3.0-only, inutilisable), Lithium 0.26.1 (LGPL-3.0-only). Aucun doublon de modId avec le pack.
5. **Contournement pilote de Sodium (N0), ordre des événements** (journal du client, 12:27) : masque de ligne de commande appliqué à 18.878 (écran de chargement précoce) ; 2e tentative 32.842 → « Command line is already modified » (le 1er masque est encore actif) ; masque réappliqué à 34.364, avant « Created window » 34.447 ; `GL_DEBUG_OUTPUT_SYNCHRONOUS` activé à 39.226. Le masque est donc en place à la création du contexte : l'erreur « READ ME » est très probablement un **faux positif sur RTX**.
6. **Rendu RTX correct** pour le jar 108 avec Complementary (capture `C:\bench\results\20260921-131430-perfmods_P0_shon.png`). Dans le banc central, `dh=off` NE copie PAS le jar Distant Horizons : mon P0 avait DH absent.

## 3. Livré dans le dépôt (branche 26.3)

- `e66361e` : `JourneyMapTraductionMixin`, mémoïse `journeymap.client.Constants.getString(String)` (invalidé au changement de langue, `@Pseudo`, `require = 0`). Compilé dans le jar 26.3.131 (`C:\perfbuild\build\libs`).
- `cb7ea75` : `GlSync` (jar 26.3.130), coupe `GL_DEBUG_OUTPUT_SYNCHRONOUS` seulement si `-Dlanterne.glsync=off` (ou `off-all`) : outil de mesure, sans effet par défaut.

## 4. NON prouvé

- **Le gain du correctif JourneyMap** (jar 131) : l'A/B 108/131 a été perdu (client tué de l'extérieur pendant J2). Le chiffre d'allocation est prouvé, le gain en FPS ne l'est pas.
- **Le gain de chaque mod de performance** : seuls P0 et BadOptimizations (sans shaders) ont été mesurés ; le reste de la série a été annulé sur ordre. P1b (les 6 mods compatibles) n'a pas été exécuté.
- **Le bug de rendu explosé du jar 106 sur RTX** (capture de l'agent W2) : jobs X1-X5 (106/108/131, avec/sans DH) déposés puis annulés, non exécutés. Ce qui est établi : mon P0 (108, DH absent) est correct ; W2 (106, DH absent) est cassé → le facteur DH est écarté pour cette paire, le jar (106 contre 108) est le suspect, sans preuve.
- **Le contournement NVIDIA comme cause des mauvais FPS** : ni « java renommé » ni `-Dlanterne.glsync=off` n'ont été mesurés.
- **O1 (≥ 300 FPS sans shaders)** : atteint sur P0 (338,7) seulement avec le jar 108 ; O2 (≥ 150 avec shaders) : NON atteint (100,2).

## 5. Prochaines étapes recommandées (dans l'ordre)

1. Rejouer X1-X5 (rendu explosé) avant tout autre chiffre : un rendu cassé rend les FPS non pertinents.
2. Mesurer 108 contre 131 avec shaders sur le banc central (gain du correctif JourneyMap), puis une capture JFR de contrôle : l'allocation JourneyMap doit disparaître du profil.
3. Reprendre la série mods : ImmediatelyFast, FerriteCore, Dynamic FPS, Lithium, BadOptimizations (répéter, 3 échantillons chacun), puis P1b. EntityCulling seulement avec l'accord de l'auteur.
4. Corriger dans les mods tiers ce que le profil désigne (JourneyMap : `Identifier` des waypoints à chaque image) et regarder l'allocation de Sodium (copie de `PalettedContainer`).
5. Si l'écart avec shaders reste grand : profil GPU (requêtes de minuterie GL sur les passes d'ombre) — le fil de rendu passe 38 % de son temps dans Iris.

## 6. Défaut du banc central à corriger (signalé au coordinateur)

`OBJECTIFS-FPS.md` pèse 18 Mo pour 105 lignes : l'ajout automatique de lignes au tableau « Suivi » relit et réécrit le fichier sans encodage UTF-8 explicite, ce qui double-encode le texte accentué à chaque passage (croissance exponentielle, section « Point de … » illisible). À corriger avant tout nouvel ajout (`Get-Content -Encoding UTF8` / `Set-Content -Encoding UTF8`, ou écriture en `[IO.File]::AppendAllText`), puis restaurer le fichier depuis git.
