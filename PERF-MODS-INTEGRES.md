# PERF-MODS-INTEGRES — mods de performance repris dans Lanterne

État au 21/09/2026, branche `feat-permods`. Licences détaillées : `NOTICE-TIERS.md`. Contexte :
`ANALYSE-ECOSYSTEME-SODIUM.md`, `TECHNOS-MODERNES.md`, `OBJECTIFS-FPS.md` (règles de mesure, iso-qualité).

**Principe** : un module est *désactivé par défaut* tant que son gain et son iso-qualité ne sont pas mesurés
sur le banc central. Un module désactivé n'applique **aucun** mixin (coût nul). Activation :
`-Dlanterne.perf.<id>=true` ou ligne `<id>=true` dans `config/lanterne-perf.properties`.

## 1. Modules intégrés (code présent, compilé, NON mesuré)

Source : ImmediatelyFast branche `26.3` (LGPL-3.0-or-later, licence lue dans le dépôt). Code dans
`fr.clubcitrouille.lanterne.perf`, configuration `lanterne.perf.mixins.json`, contrôleur `PerfMixinPlugin`.

| Module (id) | Mixins | Ce que ça fait | Risque | Défaut | Statut |
|---|---|---|---|---|---|
| `batching_groupes` | `MixinScissorState`, `MixinRenderTypeFeatureRenderer_Group` | donne un equals/hashCode à `ScissorState` et autorise le réordonnancement des groupes de `RenderTypeFeatureRenderer` : plus de regroupement des appels de dessin (entités, texte, particules) | faible ; les deux vont ensemble | **off** | cibles vérifiées par `javap` sur le jar 26.3.0.7-beta ; effet non mesuré |
| `texte_recherche_rapide` | `MixinTextFeatureRenderer_GlyphRenderer` | mémorise le dernier `RenderType`/`VertexConsumer` pendant le rendu du texte (moins d'appels à `getVertexBuilder`) | très faible | **off** | cible vérifiée ; effet non mesuré |
| `texte_sans_tri_translucide` | `MixinRenderTypes` | supprime le tri à l'envoi du texte translucide | moyen : cible par noms `lambda$static$N` fragiles | **off** | **expérimental** ; `require = 0` : sans cible, aucun effet ni blocage |

**Compilation** : voir `build-permods.log` (résultat noté dans le rapport final). **Test de démarrage** et
**mesure avant/après** : non faits dans cette session (banc central occupé/instable, voir §4).

## 2. Ce que je propose de porter ensuite (licences vérifiées)

Le gain de ces modules est **modeste** d'après leurs propres auteurs : ce sont des micro-optimisations. Ne pas attendre « 10× ».

| Projet (licence) | Élément | Intérêt pour Lanterne 26.3 | Remarque |
|---|---|---|---|
| **BadOptimizations** (MIT) — lightmap | `MixinLightmapExtractor` (114 lignes) : saute la mise à jour du lightmap quand rien n'a changé | vanilla 26.3 remet `needsUpdate = true` à **chaque tick** (scintillement de la lumière des torches) : la version d'origine annule aussi ce scintillement. **Amélioration prévue** : sauter seulement si l'écart de scintillement est sous ~0,004 (invisible en 8 bits), donc iso-qualité | classes vérifiées existantes en 26.3 ; gain attendu ~0,02-0,05 ms/image (à mesurer) |
| BadOptimizations (MIT) | toasts, débogage, tutoriel, caméra, dispatch des entités/block entities | micro-gains, faible risque | à porter après mesure |
| **ImmediatelyFast** (LGPL) | `avoid_redundant_framebuffer_switching`, `enhanced_batching` complet, carte, panneaux | utiles surtout avec beaucoup d'entités/texte | cibles `renderpearl` à vérifier |
| **FerriteCore** (MIT) | dédoublonnage mémoire des états de blocs/modèles | moins de tas → moins de GC, démarrage plus court ; **pas de FPS direct** | le jar peut être publié tel quel par la Mèche |
| **Lithium** (LGPL-3.0) | logique de jeu (collisions, IA, tick) | surtout serveur : bénéfice pour le VPS mono-cœur, pas pour les FPS client | à tester sur un serveur jetable ; jar 0.26.1 utilisable sur le VPS |
| **Dynamic FPS** (MIT) | FPS réduit hors focus/inactif/batterie | recouvre « FPS hors focus » de `feat-settings` | ne pas doubler ; reprendre la détection batterie si voulu |

Non repris, idées seulement : Sodium, EntityCulling, MoreCulling, LambDynamicLights (voir `NOTICE-TIERS.md`).

## 3. Doublons et retrait par la Mèche

Un module de Lanterne ne fait pas doublon tant que le mod d'origine n'est pas installé. Quand un module est
activé **et** le mod d'origine présent, les deux modifient les mêmes classes (risque de conflit de mixins).

| Module Lanterne | Mod d'origine | Motif `RETIRER.txt` à prévoir | Quand |
|---|---|---|---|
| `batching_groupes`, `texte_recherche_rapide`, `texte_sans_tri_translucide` | ImmediatelyFast | `ImmediatelyFast-*` | seulement si ces modules sont activés par défaut ET que le pack contient ImmediatelyFast |
| (futur) lightmap | BadOptimizations | `BadOptimizations-*` | idem |

Aujourd'hui le pack `test 1` ne contient aucun de ces mods : **rien à retirer pour l'instant.** Attention au
doublon de `modId` si un joueur ajoute lui-même le mod d'origine à côté d'un module activé : documenter dans le
LISEZMOI.

## 4. Mesures : ce qui n'a pas pu être fait

- Aucune mesure avant/après sur le banc central pour ces modules (jar `feat-permods` non lancé en jeu).
- État du banc lu pendant cette session : base P0 (jar 108, pack complet, sans shaders) = **338,7 FPS**, avec
  shaders = **100,2 FPS** ; le lot des mods de perf (P1b, chacun seul) est en file chez l'agent perf. Ixeris
  4.6.7 ne démarre pas sur 26.3 (GLFW remplacé par SDL3).
- Protocole à suivre (règle n°0 d'`OBJECTIFS-FPS.md`) : `-Dlanterne.perf.batching_groupes=true` contre défaut,
  scène restaurée, GPU cité, jeu non bridé ; scènes riches en texte/entités (panneaux, cadres, armor stands) où
  ces modules ont un effet ; comparer les captures (aucune différence visible attendue).

## 5. Reste à faire
1. Test de démarrage de chaque module (job « functional » du banc), puis A/B.
2. Porter le lightmap de BadOptimizations avec le seuil de scintillement.
3. Relier les interrupteurs à l'écran d'options (`feat-settings`, famille « Performances ») sans en modifier les fichiers.
4. Décider avec les mesures des modules à activer par défaut et des `RETIRER.txt` correspondants.
