# NOTICE-TIERS — code de tiers repris dans Lanterne

Lanterne est sous **GPL-3.0-only**. Ce fichier liste le code de tiers adapté dans le dépôt, sa licence
**vérifiée dans le dépôt source** (fichier LICENSE lu le 21/09/2026, pas seulement la fiche Modrinth), et
les fichiers concernés. Chaque fichier repris conserve son en-tête de copyright d'origine.

## Repris

| Projet | Version / commit | Licence (fichier lu) | Copyright | Fichiers Lanterne | Modifications |
|---|---|---|---|---|---|
| **ImmediatelyFast** — https://github.com/RaphiMC/ImmediatelyFast | branche `26.3`, commit `3e7986d` (1.17.2-SNAPSHOT du 17/09/2026) | LGPL-3.0-or-later (`LICENSE` = GNU LGPL v3 ; en-têtes « version 3 … or (at your option) any later version ») | © 2023-2026 RK_01/RaphiMC and contributors | `src/main/java/fr/clubcitrouille/lanterne/perf/mixin/MixinScissorState.java`, `MixinRenderTypeFeatureRenderer_Group.java`, `MixinTextFeatureRenderer_GlyphRenderer.java`, `MixinRenderTypes.java` | paquet changé, `require = 0`, champs renommés, activation par `PerfMixinPlugin` ; la configuration d'origine est remplacée par `PerfConfig` |

Ces fichiers restent LGPL-3.0-or-later (conversion vers la GPL-3.0 permise par la LGPL v3 §2 ; l'ensemble
distribué est sous GPL-3.0).

## Licences vérifiées, code PAS encore repris (candidats)

| Projet | Licence vérifiée (fichier LICENSE du dépôt) | Copyright | Statut |
|---|---|---|---|
| BadOptimizations — https://github.com/ItsThosea/BadOpitmizations (branche `26.2`, commit `5de4a3a`) | MIT | © 2025 Thosea | à porter (voir PERF-MODS-INTEGRES.md, module lightmap) |
| FerriteCore — https://github.com/malte0811/FerriteCore (branche `26.1`, commit `0cef1f2`, build 9.0.0) | MIT | © 2020 malte0811 | non repris ; le jar peut être publié tel quel par la Mèche |
| Dynamic FPS — https://github.com/juliand665/Dynamic-FPS (commit `3e318f6`) | MIT | © 2019-2024 Julian Dunskus | non repris (recouvre le réglage « FPS hors focus » de feat-settings) |
| Lithium — https://github.com/caffeinemc/lithium-fabric (branche `develop`, commit `11ea08a`) | LGPL-3.0 (`LICENSE.md`) | © CaffeineMC | non repris ; jar utilisable côté serveur (VPS) |

## Jamais copié (idées seulement)

Sodium (PolyForm Shield 1.0.0), EntityCulling (tr7zw Protective License), MoreCulling (l'auteur refuse la fusion
sans permission), LambDynamicLights (licence maison restrictive), tout projet OSL-3.0 ou « tous droits
réservés ». Aucune ligne de ces projets n'est dans ce dépôt.
