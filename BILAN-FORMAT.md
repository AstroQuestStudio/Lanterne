# Le bilan `[BILAN]` du client Lanterne

Des lignes structurées dans `logs/latest.log` (et dans `logs/lanterne-bilan.log`, qui ne contient
QUE le bilan, précédé d'un horodatage) pour diagnostiquer performances et rendu **sans capture
d'écran ni devinette**. Lecture : `tools/bilan.ps1 <journal>`.

## Règles du format (stables)
- Une ligne = un enregistrement. Préfixe fixe `[BILAN] type=<type>`, puis des `cle=valeur`
  séparés par des espaces.
- Une valeur qui contient un espace, un `=` ou un guillemet est entre guillemets doubles (les
  guillemets internes sont échappés par `\"`).
- Nombres avec point décimal, unités dans le nom de la clé (`_ms`, `_mb`).
- `n/a` = non disponible sur cette machine (jamais une valeur inventée).
- Retrouver tout : `grep "\[BILAN\]" latest.log`.
- Ajouter des clés est permis ; en retirer ou en renommer ne l'est pas sans changer ce fichier.

## Réglages (propriétés JVM)
| Propriété | Effet |
|---|---|
| `-Dlanterne.bilan=off` | coupe tout le bilan (défaut : actif) |
| `-Dlanterne.bilan.period=<s>` | période des lignes `perf` (défaut 30 ; 0 = aucune) |
| `-Dlanterne.bilan.gpu=off` | coupe la mesure du temps GPU (requêtes GL) |

## Types

### `boot` — une fois, à la première image (contexte graphique prêt)
`lanterne minecraft neoforge os cpu cores ram_mb java heap_max_mb gc jvm_args backend gpu gpu_vendor
gpu_kind vram_mb gl_version gl_debug glsync_prop gpu_time sodium iris dh shaders shaderpack
shader_active shadow_distance dh_mode`
- `backend` : `OpenGL` ou `Vulkan` (celui réellement actif).
- `gpu` : la chaîne « OpenGL Renderer » du pilote ; `gpu_kind` : `integrated` ou `dedicated`
  (déduit du nom du GPU).
- `vram_mb` : mémoire vidéo dédiée si le pilote la donne (NVIDIA), sinon `-1`.
- `gl_debug` : `output=<b>,synchronous=<b>`. `synchronous=true` sur NVIDIA = le contournement de
  Sodium est actif (le pilote n'utilise plus sa soumission threadée : coût CPU connu).
- `gpu_time` : état de la mesure GPU (`on`, `off-vulkan`, `off-demande`, `off-non-supporte`,
  `off-erreur-gl-0x…`).
- `iris` : `active` (avec Sodium), `vanilla` (`-Dlanterne.irisvanilla=true`, sans Sodium) ou `inert`.
- `dh_mode` : `normal` (pas de shaders), `cut-under-shaders` (LOD coupés sous shaders, comportement
  par défaut), `full-under-shaders` (`-Dlanterne.dhshaders=true`).

### `world` — à chaque entrée en monde
`gpu backend render_distance simulation_distance max_fps vsync gui_scale fov clouds particles
biome_blend mipmaps entity_distance resolution sodium iris dh shaders shaderpack shader_active
shadow_distance dh_mode`

### `perf` — toutes les 30 s, pendant qu'un monde est affiché et la fenêtre focalisée
| Clé | Sens |
|---|---|
| `seconds`, `frames` | durée de l'intervalle, images comptées |
| `fps` | images / durée cumulée (pas une moyenne de FPS instantanés) |
| `low1`, `low01` | « 1 % low » et « 0,1 % low » : FPS moyen des 1 % / 0,1 % d'images les plus lentes |
| `ms_mean`, `ms_p99`, `ms_max` | durée d'image entre deux débuts de rendu (moyenne, centile 99, max) |
| `cpu_ms_mean`, `cpu_ms_p99` | temps passé DANS l'appel de rendu (fil de rendu) |
| `gpu_ms_mean`, `gpu_ms_p99`, `gpu_samples` | temps GPU du rendu (requêtes `GL_TIME_ELAPSED`, non bloquantes) ; `n/a` sous Vulkan |
| `phase_<nom>_ms` | temps moyen par image d'une phase instrumentée (`shadow terrain translucent composite dh entities gui`), présent seulement si la phase a été mesurée (API `Bilan.phaseStart/phaseEnd`) |
| `sections_visible_avg`, `sections_visible`, `sections_near` | sections de terrain visibles (moyenne échantillonnée / instantané / proches) |
| `mesh_queue`, `mesh_queue_max`, `mesh_free_buffers` | file d'attente de maillage et tampons libres |
| `entities`, `chunks_loaded`, `particles` | entités du monde, chunks chargés côté client, particules |
| `heap_used_mb`, `heap_max_mb` | tas Java |
| `gc_count`, `gc_ms` | ramasse-miettes dans l'intervalle |
| `unfocused_frames` | images ignorées car la fenêtre n'avait pas le focus |
| `spikes` | gels (> 100 ms) depuis le début de la session |

Un intervalle ne mélange jamais deux réglages : il est remis à zéro à chaque changement de shaderpack.

### `event`
- `event=spike ms=<durée> screen=<écran ouvert> heap_used_mb mesh_queue` : image > 100 ms (au plus 1 par seconde, 50 par session).
- `event=shaderpack shaderpack shaders shader_active shadow_distance` : changement de shaderpack ou activation/désactivation des shaders.
- `event=dimension to=<dimension>` : changement de dimension.

### `summary` — à la sortie du monde (`reason=world-leave`) et à la fermeture du jeu (`reason=shutdown`)
`reason seconds frames fps low1 low01 ms_mean ms_p99 ms_max spikes gc_count gc_ms gpu gpu_time` sur toute la session.

## Exemple (forme)
```
[BILAN] type=boot lanterne=26.3.220 minecraft=26.3 neoforge=26.3.0.7-beta os="Windows 11 10.0" cpu="AMD64 Family 25 ..." cores=16 ram_mb=32000 java=25.0.1 heap_max_mb=4096 gc=G1_Young_Generation+G1_Old_Generation jvm_args="-Xmx4G ..." backend=OpenGL gpu="NVIDIA GeForce RTX 3080 Laptop GPU/PCIe/SSE2" gpu_vendor="NVIDIA Corporation" gpu_kind=dedicated vram_mb=16384 ... sodium=0.9.2 iris=active dh=3.3.0 shaders=false
[BILAN] type=perf seconds=30.0 frames=5060 fps=168.7 low1=96.2 low01=41.0 ms_mean=5.93 ms_p99=10.20 ms_max=48.5 cpu_ms_mean=4.10 cpu_ms_p99=8.30 gpu_ms_mean=3.90 gpu_ms_p99=6.10 gpu_samples=1265 sections_visible=812 ...
```

## Coût
Aucune allocation par image (histogramme à pas de 50 µs, requêtes GPU en anneau de 4), lecture de
champs toutes les 30 images, une ligne toutes les 30 s. Aucun `glFinish`, aucune attente : si
l'anneau de requêtes est plein, l'image n'est pas mesurée.

## Jeu bridé : la mesure est-elle valide ? (ajout)
Minecraft 26.3 se limite lui-même (~30 FPS après ~60 s sans entrée : option `inactivityFpsLimit`,
défaut `afk`) : les FPS mesurés alors ne disent rien du rendu.
- `boot` : `inactivity_fps_limit` (`afk` ou `minimized`), `fps_limit_now` (limite effective à cet instant).
- `perf` : `limiter_wait_ms` (temps moyen par image passé dans `FramerateLimiter.limitDisplayFPS`, mesuré
  par `BilanLimiterMixin`), `limiter_share_pct` (part du temps d'image), `ms_useful` (temps d'image hors
  attente), `fps_limit`, `throttle` (raison selon Minecraft : `NONE`, `WINDOW_ICONIFIED`, `LONG_AFK`,
  `SHORT_AFK`, `OUT_OF_LEVEL_MENU`), `valid` (`true`/`false`), `invalid_causes` (liste : raison de
  Minecraft, `fenetre_sans_focus`, `vsync`, `max_fps_<n>`, `attente_limiteur_<n>pct` si > 10 %).
- Quand `valid=false`, une ligne `WARN` « AVERTISSEMENT : FPS mesures NON REPRESENTATIFS : jeu bride » est
  écrite dans le journal, et `tools/bilan.ps1` la répète en tête avec les causes. Refaire la mesure fenêtre
  au premier plan, sans vsync ni plafond, sans rester inactif plus d'une minute.
