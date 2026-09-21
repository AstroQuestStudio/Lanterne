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

## Modifications substantielles faites depuis (au-delà du portage NeoForge initial)

- Correctifs de démarrage/chargement propres à l'intégration dans Lanterne (retrait de
  l'annotation `@Mod` séparée, fichier `META-INF/services` manquant, assets/mixins non copiés,
  dépendances `jarJar`) — nécessaires à la fusion dans un seul jar, sans changer le comportement
  du moteur de rendu lui-même.
- Garde défensive sur 4 mixins pour tolérer l'absence de Sodium sans crash (`IrisMixinPlugin`).
- Nouvelle abstraction `VertexFormatSource`/`SodiumVertexFormatSource` isolant le couplage direct
  à un type Sodium dans `WorldRenderingSettings` (retrait partiel de la dépendance dure à Sodium,
  toujours en cours).
- **Prototype expérimental Vulkan (`UniformReblocker`, patches shaderc) : n'existe PAS dans Iris
  upstream, ajout propre à Lanterne**, pas une modification du comportement OpenGL existant
  d'Iris — isolé dans du code de test (`lab/ShadercProbe*.java`), jamais branché au pipeline de
  rendu réel utilisé en jeu.

## Ce que ça implique

Ce code va continuer à être adapté et intégré plus directement dans Lanterne au
fil du temps. La LGPL-3.0 l'autorise, à condition que :

- le fichier `LICENSE` d'Iris reste accessible et référencé ;
- tout code dérivé de leur travail reste lui-même sous LGPL-3.0, même si
  Lanterne est distribué sous GPL-3.0-only — une licence GPL peut absorber du
  code LGPL, jamais l'inverse.

**Point de conformité non résolu, à corriger avant toute distribution publique** : le texte
intégral de la licence LGPL-3.0 d'Iris n'est actuellement PRÉSENT NULLE PART dans ce dépôt
(vérifié par recherche exhaustive du texte "Lesser General Public" dans `src/`, les `.md` à la
racine, et `LICENSE` — seul le texte GPL-3.0 de Lanterne y figure). La condition citée ci-dessus
("le fichier LICENSE d'Iris reste accessible") n'est donc PAS remplie à ce jour. Correctif simple
recommandé pour la reprise de jour : ajouter une copie du texte officiel de la LGPL-3.0
(https://www.gnu.org/licenses/lgpl-3.0.txt) dans un fichier dédié (ex: `LICENSE-IRIS`), référencé
depuis ce document — non fait cette nuit pour éviter de retranscrire un texte légal de mémoire sans
pouvoir le vérifier mot pour mot.

**Lanterne n'est affilié à IrisShaders d'aucune façon officielle.** Ce portage
n'est pas endossé par eux ; il utilise leur travail, sous leur licence, avec
crédit.
