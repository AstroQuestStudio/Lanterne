# Release candidat — verdict (21/09/2026, release manager)

**Règle du patron : rien n'est publié (VPS, pack, amis) sans son ordre.** Ce document produit un candidat et son verdict.

## 1. Cause du rendu explosé (jar 26.3.106, celui des amis) — PROUVÉE
| Cas (RTX 3080, Complementary, pack complet, DH présent, scène restaurée, heure 12500, non bridé) | Résultat | Preuve |
|---|---|---|
| Jar **106** (jar des amis, SHA-256 183ad622…) | **RENDU EXPLOSÉ** (triangles noirs/rouges/verts) | `C:\bench\results\20260921-135052-rc_r106_full_dh_shon.png` (lu) |
| Jar **110** (branche 26.3, SHA-256 7867b73b…) | **RENDU CORRECT** (coucher de soleil, jungle, ombres) | `C:\bench\results\20260921-135052-rc_r110_full_dh_shon.png` (lu) |

Mécanisme (lu dans le bytecode, javap) : le jar 106 garde 4 mixins vanilla d'Iris (MixinLevelRenderer, MixinWindow, MixinRenderPipeline, MixinDebugScreenEntriesList) derrière `Compagnons.present("sodium")` = `ModList.get().isLoaded(...)`, qui répond « non » à l'application des mixins (ModList n'existe pas encore) → mixins désactivés à tort AVEC Sodium présent → pipeline Iris incohérent. Les jars 108 et 110 détectent Sodium par la classe (`SodiumClientMod`, commit bff38e7). Non prouvé mixin par mixin (pas d'expérience de réactivation isolée).
FPS mesurés dans ces deux jobs (non concluants pour la perf : 106 = scène cassée) : 106 = 73,3 ; 110 = 73,6 (le « ms/image » du 110 est 9,49 contre 4,84 : la scène cassée ne dessine presque rien).

## 2. Candidats
- **RC-MIN = branche 26.3 (jar 26.3.110)** : correctif minimal pour les amis (détection Sodium par classe + correctifs DH/JourneyMap des commits déjà dans 26.3).
- **RC-FULL = branche `rc-full` (jar 26.3.301)** : 26.3 + feat-dh, feat-shadow, feat-bilan, feat-regul, feat-scale, feat-ui, feat-settings, feat-shaderopt (ENABLED=false), feat-permods, feat-culling. Exclus (expérimentaux, non fusionnés) : integ-nosodium, feat-vertex, feat-decouple, feat-vk. Compilation : BUILD SUCCESSFUL.
