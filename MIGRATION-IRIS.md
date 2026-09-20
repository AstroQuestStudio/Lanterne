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

## Deuxième tentative (nuit du 20-21/09/2026) : en partant de `common`, beaucoup plus loin

Contrairement à la conclusion initiale ("chantier à part entière"), le module `common` d'Iris ne
référence **aucune classe `net.fabricmc.*` dans son code Java** (vérifié : `grep -rl
"net\.fabricmc\." common/src/main/java` → 0 résultat sur 696 fichiers). Les dépendances Fabric
Loom de son `build.gradle.kts` servent à résoudre les mappings Minecraft et l'API Fabric — pas
référencées directement par le code lui-même. Ça change complètement l'estimation.

**Progrès réel, par itérations de compilation successives (chaque itération = une vraie
`./gradlew compileJava`, pas une supposition) :**

1. Copié `common/src/main/java/net` (638 fichiers) → 100 erreurs, toutes `cannot find symbol` sur
   `net.irisshaders.iris.api.v0.*` (IrisApi, IrisApiConfig...).
2. Trouvé et copié `common/src/api/java/net` (le module API d'Iris vit dans un **sourceSet Gradle
   séparé** de `common`, pas dans `main`) → nouvelles erreurs, cette fois sur
   `com.seibel.distanthorizons.*` (Distant Horizons).
3. Ajouté `compileOnly files('libs/DHApi.jar')` dans `build.gradle` de Lanterne (jar trouvé à la
   racine d'`iris-src`, copié dans `Lanterne/libs/`) → nouvelles erreurs sur
   `kroppeb.stareval.*`.
4. Trouvé que `kroppeb` est un **package top-level séparé** sous `common/src/main/java/`
   (pas sous `net.irisshaders`) → copié aussi.
5. Ajouté dans `build.gradle` : `org.antlr:antlr4-runtime:4.13.1`,
   `io.github.douira:glsl-transformer:3.0.0-pre3`, `org.anarres:jcpp:1.4.14` (mêmes coordonnées
   que `common/build.gradle.kts`, déjà en cache Gradle local depuis le build du module `neoforge`
   plus tôt cette session), et `libs/sodium-neoforge-real-0.9.2+mc26.3.jar` (le vrai moteur
   Sodium extrait du jarJar, même raison que pour le module `neoforge`).

**Toutes ces dépendances se résolvent et compilent.** Le mur réel, atteint à cette itération :
erreurs `is not public` / `has private access` / `cannot find symbol` sur des membres vanilla
normalement exposés par **l'access widener Fabric d'Iris** (`common/src/main/resources/
iris.accesswidener`, jamais copié ni converti) et par des **méthodes injectées par interface
mixin** (`iris$getGlId()` etc., définies dans `interface_injections.json` du module `neoforge`,
jamais enregistrées dans ce contexte de compilation brute).

**Ce que ça confirme, précisément** : le vrai obstacle n'est PAS Fabric Loom vs ModDevGradle pour
les dépendances (résolu ce soir, sans douleur) — c'est la conversion de l'access widener Fabric
(`iris.accesswidener`) vers un access transformer NeoForge (format `accesstransformer.cfg`,
entrée par entrée, chacune vérifiée par javap comme fait pour le module `neoforge` plus tôt cette
session) + le branchement réel de Mixin (refmap, interface injections) sur ce nouveau code source
avant que `compileJava` seul suffise à juger.

**État laissé ce soir** : le code source d'Iris (`net/irisshaders`, `kroppeb`) a été RETIRÉ du
dépôt (`compileJava` cassé au-delà de ce qu'il était raisonnable de corriger cette nuit) —
`./gradlew compileJava` repasse au vert. **Gardé, en revanche** (infrastructure réutilisable,
vérifiée valide) : les 5 lignes `compileOnly` ajoutées dans `build.gradle` (antlr4-runtime,
glsl-transformer, jcpp, sodium-neoforge-real, DHApi.jar) et `Lanterne/libs/{DHApi.jar,
sodium-neoforge-real-0.9.2+mc26.3.jar}`.

**Prochaine tentative, point de départ exact (ne pas refaire la découverte ci-dessus)** :
1. Recopier `common/src/main/java/net`, `common/src/api/java/net`, `common/src/main/java/kroppeb`.
2. Convertir `common/src/main/resources/iris.accesswidener` en access transformer NeoForge,
   entrée par entrée, vérifiée par javap contre le vrai jar patché 26.3.0.7-beta (méthode déjà
   éprouvée pour le module `neoforge` — voir son historique de commits dans `iris-src`).
3. Ne recopier `neoforge/src/main/resources/interface_injections.json` (et son mécanisme
   d'enregistrement) qu'une fois l'access transformer en place — plusieurs erreurs actuelles
   (`iris$getGlId()` introuvable) en dépendent directement.
