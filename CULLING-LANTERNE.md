# Culling de Lanterne — état, outils de mesure et plan (branche feat-culling)

**Statut : aucun gain nouveau mesuré à ce jour.** Cette branche livre l'outillage de mesure et le plan ;
rien n'est activé par défaut. Priorité n°1 pour la suite (ordre du patron) : **le culling sous shaders**
(ombres selon le soleil, culling GPU).

## 1. Ce qui existe déjà dans Lanterne (lu dans le code, non modifié)
| Module | Où | Rôle |
|---|---|---|
| Le voile (`Shroud`) | `core/Shroud.java` (496 l.), `mixin/EntityCullMixin` | écarte les entités cachées par un mur : 5 sondes, lancer de rayons dans une grille (max 512 pas), cadence décalée par entité, budget par image, cache par point (`SPOTS`), zone proche/lointaine |
| Voile des blocs animés | `mixin/BlockEntityVeilMixin` | même test pour les entités de bloc (coffres, panneaux…) |
| Voile des particules | `mixin/ParticleVeilMixin` | même test pour les particules |
| Horizon / coffres statiques / feuilles | `core/Horizon`, `Settings.staticChests`, `mixin/LeafCullMixin` | culling de lointain, coffres figés, feuilles enterrées |
| Scènes de charge | `lab/Scene` (RING, PEN, BARN, DEPOT, FOLIAGE, MIXED, VILLAGE…) | grange = 800 bêtes sous un toit de pierre : LA scène qui prouve le gain ET l'iso-rendu (depuis dehors l'écran ne montre rien, avec ou sans voile) |

## 2. Ce que cette branche ajoute (commit sur `feat-culling`, inerte par défaut)
- `client/culling/CullingSwitch` : `-Dlanterne.culling.off=shroud,blocks,particles,horizon,chests` coupe un module
  pour une mesure A/B, sans toucher à la config. Sans la propriété : constantes `false`, coût nul (5 getters de `Settings`).
- `client/culling/CullingBench` : `-Dlanterne.culling.scene=<charge>[:<n>]` construit la charge de `lab.Scene` 5 s après l'entrée
  du joueur, le place à 26 blocs face à elle (`-Dlanterne.culling.pos=x,z,yaw,pitch` pour changer), et journalise
  toutes les 20 s `[CULLING] voile : vues / écartées / rayons` (compteurs de `Shroud`). Jamais actif chez un joueur.
- Compile : jar `26.3.290` (BUILD SUCCESSFUL). **Non lancé en jeu** (aucun job déposé : ordre d'arrêt du coordinateur).

## 3. Protocole de mesure (à lancer plus tard sur le banc central `C:\bench`)
Jobs A/B, même jar, RTX (GPU cité), scène restaurée, non bridé, 1280x720, `-Screenshot`, 90 s :
```
submit.ps1 -Name cull_barn_on   -Jar <lanterne-26.3.290.jar> -Mode bench -Seconds 90 -Screenshot -JvmArgs '-Dlanterne.culling.scene=barn:800'
submit.ps1 -Name cull_barn_off  -Jar <...>                   -Mode bench -Seconds 90 -Screenshot -JvmArgs '-Dlanterne.culling.scene=barn:800 -Dlanterne.culling.off=shroud,particles'
submit.ps1 -Name cull_depot_on  ... '-Dlanterne.culling.scene=depot:800'
submit.ps1 -Name cull_depot_off ... '-Dlanterne.culling.scene=depot:800 -Dlanterne.culling.off=blocks,chests'
```
Gain = FPS moyen, 1 % low, ms/image, `[CULLING]` (vues/écartées/rayons). **Iso-rendu** : comparer les deux captures de chaque paire avec
`C:\scalebench\Cmp.java` (PSNR/SSIM) : sur la grange, elles doivent être identiques (aucune bête visible dans les deux cas).
Ajouter des scènes variées : `mixed`, `village`, `foliage`, plaine/jungle (banc actuel). Coût du culling lui-même = comparer une scène
SANS entités cachées, voile actif vs coupé.

## 4. Plan, par ordre de priorité (à réaliser avec mesure avant/après et iso-rendu prouvé)
1. **PRIORITÉ n°1 — culling sous shaders.**
   a. *Culling des ombres selon le soleil* : ne dessiner dans la shadow map que les sections/entités pouvant projeter une ombre dans le champ visible
      (volume lumière ∩ frustum élargi dans la direction opposée au soleil ; cas soleil bas = ombres longues, marge de sécurité). À écrire avec l'agent
      `feat-shadow` (il possède `shadows/**` : Lanterne expose une API de visibilité, il l'appelle). Ombres = ~9 ms sur 13 ms dans l'ancienne base (à re-mesurer, scène restaurée).
   b. *Culling GPU (compute + pyramide Hi-Z de l'image précédente + dessin indirect)* en OpenGL 4.3, réutilisé pour les ombres. Modèle : culling GPU de VulkanModNeoForge
      (LGPL-3.0, code adaptable avec en-tête de licence et crédit dans `NOTICE-TIERS.md`, cf. `ANALYSE-REFS-VULKAN.md`). Prérequis à vérifier sur RTX ET iGPU AMD :
      `ARB_compute_shader`, `ARB_shader_storage_buffer_object`, `ARB_indirect_parameters` (repli CPU automatique si absent). L'API de Mojang n'expose pas le compute : GL direct.
   c. Réutiliser la visibilité à la rotation de la tête (résultats local / régulier / large, idée décrite dans `ANALYSE-SODIUM-ET-ECARTS.md`).
2. Mesurer l'existant (section 3) : le voile est-il rentable partout ? Quel coût CPU par entité/image ? (JFR via `-Jfr`).
3. Voile des entités : cadence adaptative des lointains, hystérésis, cache par section (zéro allocation par image), marge de sécurité contre toute disparition visible.
4. Particules / animations / nuages / ciel hors champ (chevauche `feat-settings`).

## 5. Licences (Lanterne = GPL-3.0-only)
- Adaptables avec en-têtes conservés et crédit : VulkanMod/VulkanModNeoForge (LGPL-3.0), ImmediatelyFast (LGPL), Lithium (LGPL), MIT/Apache/MPL.
- Idées seulement, code écrit par nous : **Sodium** (PolyForm Shield), **EntityCulling** (licence restrictive tr7zw), **MoreCulling** (auteur : pas de fusion sans permission), **Camera Culling** (GPL/protégé).
- Tout emprunt : ajouter à `NOTICE-TIERS.md` (fichier, source, licence, date).

## 6. Limites honnêtes
- Aucune mesure de gain, aucun iso-rendu prouvé : tout est à faire sur le banc.
- Le voile existe déjà et est plutôt avancé ; le gain marginal des entités est probablement plus faible que celui des ombres et du culling GPU sous shaders.
- Sur iGPU AMD, le rendu shaders était cassé avec le jar 106 (voir `OBJECTIFS-FPS.md`) : ne pas mesurer le culling GPU là avant correction.
