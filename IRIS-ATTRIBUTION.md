# Attribution — moteur de shaders Iris

Lanterne embarque un portage NeoForge non officiel du moteur de shaders **Iris**
(https://github.com/IrisShaders/Iris), créé par **coderbot** et **IMS212**, avec
l'équipe IrisShaders. Iris est distribué sous licence **LGPL-3.0-only**.

## Ce qui a été fait

Sur leur branche `26.3`, le module `neoforge` existait dans leur dépôt mais était
désactivé (`settings.gradle.kts` commentait `"neoforge"` hors de la liste des
modules inclus). Nous avons réactivé ce module et adapté le code nécessaire à
MC 26.3.0.7-beta et au refactor RenderPearl (le split moteur OpenGL/Vulkan de ce
build). Référence de comportement : leur code réel sur la branche `26.3` au
20/09/2026, recoupé avec leur dernière release officielle publiée pour Fabric
(1.11.6, MC 26.3) — pas une réécriture, une adaptation de leur code existant.

Sodium reste une dépendance obligatoire de ce moteur : ce n'est pas une simple
couche de compatibilité optionnelle, le bootstrap et le format de vertex du
terrain d'Iris utilisent directement des types Sodium. Vérifié dans le code,
pas supposé.

## Ce que ça implique

Ce code va continuer à être adapté et intégré plus directement dans Lanterne au
fil du temps. La LGPL-3.0 l'autorise, à condition que :

- le fichier `LICENSE` d'Iris reste accessible et référencé ;
- tout code dérivé de leur travail reste lui-même sous LGPL-3.0, même si
  Lanterne est distribué sous GPL-3.0-only — une licence GPL peut absorber du
  code LGPL, jamais l'inverse.

**Lanterne n'est affilié à IrisShaders d'aucune façon officielle.** Ce portage
n'est pas endossé par eux ; il utilise leur travail, sous leur licence, avec
crédit.
