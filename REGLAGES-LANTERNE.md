# Réglages de performance de Lanterne

Chaque réglage a **un effet réel**, lu par un hook du jeu (aucune case décorative). Les valeurs par défaut ne changent rien à l'image, sauf « Distance des particules » (128 blocs : une particule à cette distance tient en moins de quatre pixels). Quand un réglage est à sa valeur d'origine, son hook ne coûte qu'un test d'un booléen en cache.

Où les régler :
- **Écran Lanterne** (Options > Vidéo) : familles « Particules & météo », « Entités & blocs », « Performances », « Préréglages ». Clic gauche = cran suivant, clic droit = précédent.
- **Commande** : `/lanterne reglage` (liste), `/lanterne reglage <nom>` (détail), `/lanterne reglage <nom> <valeur>`, `/lanterne reglage preset <nom>`, `/lanterne reglage restaurer`, `/lanterne reglage exporter`, `/lanterne reglage importer <texte>`.
- **Fichier** : `config/lanterne-client.toml`, section `[rendu.reglages]`.
- **Propriété JVM** (bancs et tests, prioritaire sur le fichier) : `-Dlanterne.reglage.<id>=<valeur>`, par exemple `-Dlanterne.reglage.ciel_etoiles=0`.

Règle d'iso-qualité (voir `OBJECTIFS-FPS.md`) : « perte visible » dit ce que le joueur peut remarquer. Les défauts sont les niveaux « aucune ».

## Particules & météo

| Nom (id) | Effet | Défaut | Perte visible | Hook |
|---|---|---|---|---|
| Densité des particules (`particules_densite`) | Part de toutes les particules créées, 0-100 % | 100 % | faible | `ParticleEngine.createParticle` (retour nul, prévu par vanilla) |
| Explosions & feux d'artifice (`particules_explosions`) | Densité de la catégorie explosion/flash/feu d'artifice | 100 % | faible | idem |
| Fumées (`particules_fumee`) | feu de camp, torches, chaudron, poof, nuages, cendres | 100 % | faible | idem |
| Eau & bulles (`particules_eau`) | éclaboussures, bulles, gouttes, pêche, dauphin | 100 % | faible | idem |
| Feuilles & pétales (`particules_feuilles`) | feuilles/pétales qui tombent | 100 % | faible | idem |
| Magie, redstone & portails (`particules_magie`) | poussière (hors « falling_dust »), enchantement, portail, effets, âme, sculk… | 100 % | faible | idem |
| Distance des particules (`particules_distance`) | au-delà de N blocs, la particule n'est pas créée ; 0 = illimitée | **128** | aucune | idem (3 soustractions) |
| Pluie & neige dessinées (`meteo_dessinee`) | dessine ou non les gouttes/flocons (la météo continue) | actif | moyenne | `WeatherEffectRenderer.prepare/render/renderOit` |
| Soleil & lune (`ciel_soleil_lune`) | dessine le soleil et la lune | actif | forte | `SkyRenderer.renderSun/renderMoon` |
| Étoiles (`ciel_etoiles`) | dessine les étoiles | actif | forte | `SkyRenderer.renderStars` |

La densité d'une catégorie se multiplie à la densité globale : 50 % × 50 % = 25 % des particules de cette catégorie. Les catégories sont déduites de l'identifiant de la particule (`Reglages.classer`), une seule fois par type.

## Entités & blocs

| Nom (id) | Effet | Défaut | Perte visible | Hook |
|---|---|---|---|---|
| Distance des blocs animés (`blocs_entites_distance`) | coffres, panneaux, lits, bannières : au-delà de N blocs, pas dessinés comme blocs animés ; 0 = vanilla | 0 | faible | `BlockEntityRenderDispatcher.tryExtractRenderState` (5 arguments) |
| Distance des plaques de nom (`plaques_distance`) | plaque de nom d'entité affichée seulement sous N blocs ; 0 = 64 | 0 | faible | `EntityRenderer.extractNameTags` (argument de distance) |
| Fluidité des textures animées (`textures_animees`) | 0 toutes les images, 1 une sur deux, 2 une sur quatre, 3 figées | 0 | moyenne | `TextureAtlas.uploadAnimationFrames` |
| Distance des cadres & peintures (`cadres_distance`) | au-delà de N blocs, cadres d'objets et peintures non dessinés ; 0 = vanilla | 0 | faible | `EntityRenderDispatcher.shouldRender` |
| Distance des supports d'armure (`armor_stands_distance`) | au-delà de N blocs, armor stands non dessinés ; 0 = vanilla | 0 | faible | idem |

## Performances

| Nom (id) | Effet | Défaut | Perte visible | Hook |
|---|---|---|---|---|
| FPS hors focus (`fps_hors_focus`) | limite d'images par seconde quand la fenêtre est visible mais sans focus ; 0 = illimité | **60** | aucune | `FramerateLimitTracker.getFramerateLimit` |
| Inactivité (AFK) | vanilla : bridé à 30 FPS après 1 min sans entrée, ou seulement fenêtre réduite | AFK (vanilla) | aucune | option vanilla `inactivityFpsLimit` (cadran) |

Vanilla ne bride que la fenêtre **réduite** (10 FPS). Une fenêtre visible mais en arrière-plan tourne à plein régime : c'est ce que « FPS hors focus » corrige. Attention aux mesures : sur un banc, la fenêtre doit garder le focus (le banc central la met au premier plan).

Note : vanilla limite déjà chaque type de bloc animé à sa propre distance (64 blocs en général) ; « Distance des blocs animés » n'a d'effet qu'en dessous de cette distance.

## Préréglages

| Nom | Valeurs | Perte visible |
|---|---|---|
| Qualité max (`qualite_max`) | tout à l'origine, distance des particules illimitée | aucune |
| Équilibré (`equilibre`) | défaut : distance des particules 128 | aucune |
| FPS max (`fps_max`) | densité 50 %, distance 64, fumée 50 %, eau 40 %, explosions 60 %, blocs 48, plaques 24, textures 1/2 | faible à moyenne |
| Petite config (`petite_config`) | densité 30 %, distance 32, plaques 16, blocs 32, textures 1/4 | moyenne |

« Tout restaurer » remet chaque réglage à son défaut. **Exporter** copie une ligne `lanterne-reglages;id=valeur;…` (seulement ce qui s'écarte du défaut) ; **Importer** la relit depuis le presse-papiers (un morceau illisible est ignoré).

## Ce qui n'est PAS encore fait (liste honnête)

- Le **régulateur adaptatif** (branche `feat-regul`) n'est pas encore relié à ces réglages : il ajustera les niveaux 1/2/3 d'iso-qualité, une fois les branches fusionnées.
- Les réglages du chapitre « Performances » de la demande (threads de maillage, mises à jour de chunk différées, limite de rendu en avance du CPU, culling de faces cachées) restent à faire : chacun demande un hook dans le mailleur qui dépend de l'intégration « Iris sans Sodium » en cours.
- « Textures animées seulement si visibles » (technique décrite dans `ANALYSE-SODIUM-ET-ECARTS.md`) demande un suivi de visibilité par sprite : non fait, remplacé pour l'instant par la fluidité réglable.
- Les gains FPS mesurés ne sont pas encore renseignés : ils exigent le banc central (`C:\bench`) et le protocole de mesure (`OBJECTIFS-FPS.md`, règle n°0).
- Vérification en jeu : voir la section « Preuves » à la fin de ce fichier une fois les tests faits.


## Inspirations et licences (une ligne par réglage)

Lanterne est sous **GPL-3.0-only**. Aucun code n'a été copié : les réglages ci-dessous sont écrits pour Lanterne à partir des sources vanilla 26.3 (lues avec `javap` et les sources du jar), l'inspiration est seulement une **idée** citée pour le crédit. La colonne « Reprise » dit ce que la licence de la source permettrait.

| Réglage | Idée vue dans | Licence de la source | Reprise |
|---|---|---|---|
| Densité / catégories / distance des particules | Sodium Extra (« particules par type ») | LGPL-3.0 | code adaptable avec crédit ; **ici réécrit**, catégories déduites de l'identifiant |
| Pluie & neige dessinées | Sodium Extra (« pluie/neige ») | LGPL-3.0 | idem |
| Soleil & lune, Étoiles | Sodium Extra (« détails du ciel ») | LGPL-3.0 | idem |
| Distance des blocs animés | Sodium Extra (block entities), vanilla `getViewDistance` | LGPL-3.0 | idem |
| Distance des plaques de nom | Sodium Extra (cadres/plaques de nom) | LGPL-3.0 | idem |
| Cadres & peintures, Armor stands | Sodium Extra (cadres, peintures, armor stands) | LGPL-3.0 | idem |
| Fluidité des textures animées | Sodium Extra (toggles d'animations) | LGPL-3.0 | idem ; la variante « seulement si visibles » reste à faire |
| FPS hors focus | Dynamic FPS (état de la fenêtre) | MIT | code adaptable avec crédit ; **ici réécrit** (1 hook) |
| Inactivité (AFK) | option vanilla `inactivityFpsLimit` | — (Mojang) | cadran sur une option existante |
| Préréglages, export/import | conception Lanterne | — | — |

Ne sont **pas** repris, même comme idée de code : Sodium (PolyForm Shield), EntityCulling (licence non commerciale), LambDynamicLights, Zume (OSL-3.0). MoreCulling (GPL-3.0) : accord de l'auteur à demander avant toute fusion.

Crédits à ajouter au README et à `IRIS-ATTRIBUTION.md` par le coordinateur lors de la fusion : Sodium Extra (LGPL-3.0), Dynamic FPS (MIT), BadOptimizations (MIT).

## Liste de l'analyse écosystème (top 15) : où on en est dans ce panneau

| # | Fonction | État |
|---|---|---|
| 1 | FPS hors focus / inactif | **fait** (`fps_hors_focus`, cadran AFK) ; batterie : non fait |
| 2 | Cache du lightmap | non fait (hook dans `LightTexture`, gain à mesurer) |
| 3 | Animations seulement si visibles | non fait ; réglage de fluidité fait |
| 4 | Particules par type | **fait** (5 catégories + densité + distance) |
| 5 | Batching du rendu immédiat | non fait (gros chantier) |
| 6 | Entrées brutes groupées | non fait |
| 7 | Culling d'entités asynchrone | non fait (idée seulement) |
| 8 | Ciel/brouillard/nuages | ciel **fait** ; brouillard non fait ; nuages : cadrans vanilla existants |
| 9 | Entités par type | cadres, peintures, armor stands, plaques **faits** ; pistons, balise, table d'enchantement non faits |
| 10-15 | mises à jour de lumière, culling de faces, cache HUD/minimap, priorité de fil, HUD debug stable | non faits |

## Preuves

À compléter : build, lancement d'un vrai client, captures.
