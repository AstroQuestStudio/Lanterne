# Migration d'Iris dans Lanterne — état réel

Tentative du 20-21/09/2026 : faire compiler le module `neoforge` d'Iris (~15 classes,
`net.irisshaders.iris.mixin.forge.*` + `net.irisshaders.iris.platform.*`) directement dans
`src/main/java` de Lanterne, en gardant leur package d'origine (`net.irisshaders.iris`, pas de
renommage — voir pourquoi plus bas).

## Ce qui a été essayé

Copie des 15 fichiers Java + 3 fichiers de ressources (`interface_injections.json`,
`accesstransformer.cfg`, `mixins.iris.forge.json`) depuis
`C:\Users\trufa\AppData\Local\Temp\claude\iris-src\neoforge\src\main\` vers l'arborescence de
Lanterne, `net.irisshaders.iris.mods.toml` de Lanterne, jamais copié — il aurait fallu fusionner un
second bloc `[[mods]]` à la main).

## Résultat réel : ça ne compile pas, et ce n'est pas un détail

`./gradlew compileJava` : **70 erreurs**, toutes du même type — `cannot find symbol`. La totalité
vient de classes du module **`common`** d'Iris (`IrisApi`, `IrisProgram`, `ShaderPackScreen`,
`BlockRenderType`, `BlockMaterialMapping`, `NeoForgeRenderPipelines`, etc.) que le module
`neoforge` importe sans arrêt — plus une dépendance directe à Sodium
(`net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer`
introuvable, `MixinFluidRendererImpl.java`).

**Le module `neoforge` ne peut structurellement pas compiler seul.** Il n'a jamais été conçu pour :
dans le dépôt Iris officiel, `neoforge` dépend de `common` via un module Gradle
(`compileOnly(project.project(":common")...)`) — pas d'un jar externe, d'un vrai sous-projet du
même build multi-module.

## Ce que ça implique pour la suite

Pour que ça compile réellement dans Lanterne, il faut le module `common` en entier (~960 classes),
pas juste `neoforge`. Ce n'est pas une question de copier plus de fichiers un par un cette nuit :
`common` a sa propre chaîne de dépendances vers Fabric Loom (accès aux mappings Minecraft via
Loom, pas via ModDevGradle) qu'il faudra démonter avant même de penser à compiler quoi que ce soit
— un chantier à part entière, pas une continuation directe de celui-ci.

**Décision prise cette nuit : annulé, pas laissé en l'état.** Un `compileJava` cassé sur la
branche `26.3` aurait bloqué tout autre travail cette nuit (smoke-tests, autres agents). Le dépôt
est revenu à l'état d'avant cette tentative — aucun fichier de `net.irisshaders.iris` dans Lanterne
actuellement. Le jar externe `iris-neoforge-1.11.6-INOFFICIEL-mc26.3.jar` (compilé séparément dans
`C:\Users\trufa\AppData\Local\Temp\claude\iris-src`) reste la seule version fonctionnelle,
installée dans `test 1/mods`.

## Prochaine tentative réaliste (pas pour cette nuit)

1. Copier le module `common` en entier (~960 classes) AVANT le module `neoforge` — sans lui, rien
   ne pourra jamais compiler.
2. Remplacer les dépendances Fabric Loom du module `common` (accès aux mappings/artefacts
   Minecraft) par l'équivalent ModDevGradle déjà en place dans `build.gradle.kts` de Lanterne —
   c'est le vrai morceau technique, pas la copie de fichiers elle-même.
3. Ne recompiler `neoforge` qu'une fois `common` vérifié compilant seul dans Lanterne.
4. Fusionner `META-INF/neoforge.mods.toml` (ajouter un second bloc `[[mods]]`) plutôt que
   l'écraser.

Estimation honnête : plusieurs jours de travail humain normal, pas une nuit — confirmé
empiriquement ce soir, pas juste supposé au départ.
