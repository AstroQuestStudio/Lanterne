# Recherche : Vulkan et rendu sans Sodium pour notre portage Iris

Audit factuel du 20/09/2026, sur le clone de travail `iris-src` (branche 26.3 d'Iris,
LGPL-3.0 — voir `IRIS-ATTRIBUTION.md`). Chiffres obtenus par grep/javap sur le code et
bytecode réels, pas par supposition.

## Question 1 — Vulkan : l'ampleur précise, pas "impossible"

**Le moteur vanilla a déjà un vrai backend Vulkan parallèle à OpenGL.** Confirmé dans
`minecraft-patched-26.3.0.7-beta.jar` : `com/mojang/renderpearl/backend/{opengl,vulkan}/`
existent tous les deux sous une même API agnostique `com/mojang/renderpearl/api/`.
`VulkanRenderPipeline`, `VulkanDevice`, `VulkanCommandEncoder` etc. sont des classes
réelles et complètes côté vanilla.

**Répartition du code d'Iris (`common`, 696 fichiers .java) :**
- 119 fichiers couplés à OpenGL de façon dure (import direct `org.lwjgl.opengl.*` ou
  `com.mojang.renderpearl.backend.opengl.*`).
- 88 fichiers utilisent déjà l'API agnostique `com.mojang.renderpearl.api.*`.
- 37 fichiers utilisent **les deux** — déjà partiellement migrés, probablement parce
  que l'API agnostique ne couvre pas encore tout (compilation GLSL bas niveau,
  typiquement).
- **82 fichiers sont couplés dur SANS aucune trace de l'API agnostique** — c'est la
  vraie cible de réécriture, pas 696.

**Le vrai point dur n'est pas l'API de rendu, c'est la compilation des shaders.**
Iris charge des shaderpacks tiers écrits en GLSL, **au runtime**, choisis par le
joueur — ce n'est pas comparable au cas vanilla, où Mojang connaît ses propres
shaders à l'avance et peut les précompiler en SPIR-V (le format que Vulkan consomme,
PAS le GLSL) au moment du build. Vulkan ne sait pas lire du GLSL directement.

**Bonne nouvelle concrète** : `lwjgl-shaderc` (le compilateur GLSL→SPIR-V officiel de
Google/Khronos, avec bindings LWJGL) est **déjà présent dans le cache Gradle local**
(`org.lwjgl:lwjgl-shaderc:3.4.1`, natifs Windows inclus) — donc disponible sans
dépendance externe à ajouter. `com.mojang.renderpearl.util.ShaderCompileException`
existe déjà côté vanilla, ce qui confirme qu'un pipeline de compilation shader est un
concept déjà géré par le moteur, pas à inventer de zéro.

**Chemin concret (pas un refus)** :
1. Intégrer `lwjgl-shaderc` pour compiler les `.fsh`/`.vsh` GLSL des shaderpacks en
   SPIR-V au chargement du pack (pas à chaque frame — un coût ponctuel, comme la
   compilation OpenGL actuelle).
2. Réécrire les 82 fichiers durs pour passer par `renderpearl.api.*` au lieu des
   appels GL directs — chantier mécanique mais borné, pas infini.
3. Les 37 fichiers déjà hybrides sont la meilleure référence de comment Iris a déjà
   commencé cette transition ailleurs — s'en inspirer plutôt que réinventer.

**Ampleur réaliste** : plusieurs semaines de travail dédié (82 fichiers à auditer un
par un + le pipeline shaderc à écrire et tester), pas un simple flag à activer — mais
un chemin réel existe, avec les briques déjà là. Points d'ancrage les plus critiques :
`net.irisshaders.iris.pipeline.programs.ShaderCreator`/`ShaderMap` (compilation),
`net.irisshaders.iris.gl.*` (84 fichiers, le cœur de l'abstraction GL actuelle
d'Iris — à faire pointer vers `renderpearl.api` plutôt que LWJGL brut).

## Question 2 — Rendu sans Sodium : surface réelle, pas un simple retrait

**54 fichiers dans `common` référencent des types Sodium**, mais la moitié n'est pas
le vrai problème :
- **21 fichiers** sont dans `compat/sodium/mixin/` — ce sont des **mixins qui ciblent
  des classes Sodium**. Confirmé cette session (comportement Mixin standard) : si
  Sodium n'est pas chargé, ses classes ne sont jamais chargées non plus, donc ces
  mixins ne s'appliquent simplement jamais. Aucun risque, aucun travail nécessaire ici.
- **Les ~33 fichiers restants** sont le vrai couplage dur (imports Sodium utilisés en
  code Java normal, pas en cible de mixin) :
  - `vertices/sodium/` (5) + `vertices/sodium/terrain/` (3) : le format de vertex du
    terrain lui-même — la partie la plus critique et la plus difficile à remplacer,
    car c'est ce qui permet à Iris d'insérer des données par-vertex (normales,
    lumière) dans le pipeline de maillage de Sodium.
  - `mixin/` (5, dont le bootstrap `Iris.java` déjà patché cette session avec une
    garde `isModLoaded`) + `compat/sodium/config` (1) + `uniforms` (2) + `shadows/
    frustum/{fallback,advanced}` (4) + le reste (13 fichiers isolés).

**Aucun chemin "vanilla-only" mûr n'existe déjà dans Iris** : une seule garde
`isModLoaded("sodium")` trouvée dans tout `common/` (celle qu'on a ajoutée
nous-mêmes dans `Iris.java` hier soir). Vérifié : pas de branche alternative de
rendu terrain sans Sodium à moitié écrite qu'on pourrait terminer — il faudrait
réellement écrire un chemin de sérialisation de vertex directement contre le
pipeline de maillage vanilla (`SectionRenderDispatcher` côté Minecraft), depuis
zéro, pour ces ~33 fichiers.

**Conclusion honnête** : c'est un vrai sous-projet scopé (pas un retrait d'une
ligne), centré sur `vertices/sodium/terrain/` en premier — mais borné à ~33
fichiers, pas 980. Faisable, pas cette nuit dans son intégralité.
