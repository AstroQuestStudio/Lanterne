# Tâches de la nuit 20-21/09/2026 — continuité de contexte

Ce fichier existe pour qu'une session qui reprend après une perte de contexte sache exactement
où en est le travail, sans redemander au patron. Mettre à jour ce fichier à chaque étape
significative, pas juste le lire.

## État confirmé au 21/09 ~01h20

- **VPS stable en `26.3.55`**, `Done` confirmé à chaque redémarrage depuis plusieurs heures.
- **Client testé en vrai par le patron** : atteint le menu principal, active un shaderpack
  (Complementary Reimagined r5.9) — 5 crashs réels trouvés et corrigés un par un cette nuit
  (tous dus à la migration incomplète du code source d'Iris dans Lanterne) :
  1. `@Mod("iris")` sur IrisForgeMod → crash-loop VPS → annotation retirée (`e8138a6`)
  2. `BlueMapAPI` chargé sans garde dans Cadence → crash si BlueMap absent → isolé (`0586528`)
  3. `META-INF/services/...IrisPlatformHelpers` jamais copié → NoSuchElementException → copié (`6e72735`)
  4. `IrisForgeHelpers.getVersion()` cherchait `getModFileById("iris")` (n'existe plus) → NPE → utilise "lanterne" (`c73466d`)
  5. `assets/iris/` (textures GUI, shaders core) jamais copié → NPE sur widgets.png → copié (`587e70e`)
  6. `antlr4-runtime`/`glsl-transformer`/`jcpp` en `compileOnly` (jamais embarqués) → NoClassDefFoundError → passés en `jarJar` (`115e33b`)
  7. `centerDepth.fsh/vsh` + `colorSpace.csh/vsh` (racine resources, pas sous assets/) jamais copiés → NPE → copiés (`b65e53d`)

**Leçon à ne jamais oublier** : la migration du code source Java d'Iris a été faite AVANT de
vérifier exhaustivement tous les fichiers non-Java référencés (resources, assets, dépendances
runtime). Chaque nouveau test client a révélé un fichier manquant différent. Avant de déclarer
"la migration Iris est terminée", il faut un test client réel qui va JUSQU'AU BOUT (shader actif,
en jeu, plusieurs minutes) sans aucune erreur dans les logs — pas juste "ça compile" ou "le
serveur démarre" (le serveur ne charge jamais ce code, `Iris.createPipeline` est 100% client).

## Problème connu, PAS corrigé, à investiguer

- **Sodium n'affiche plus "Iris" dans son écran d'options vidéo.** Cause probable : `Iris.MODID`
  vaut toujours `"iris"` en dur (jamais renommé), mais `ModList.get().isLoaded("iris")` renvoie
  maintenant `false` partout (plus aucun fichier de mod séparé "iris" n'existe, tout est fondu
  dans "lanterne"). Les mods tiers qui détectent Iris via ce modId (ex: `reeses-sodium-options`,
  qui ajoute un onglet Iris dans le menu Sodium) ne le détectent donc plus. PAS corrigé cette nuit
  faute de temps — nécessite soit de patcher CES mods tiers (pas notre code, licence à vérifier),
  soit de comprendre si NeoForge permet d'enregistrer un modId "virtuel" supplémentaire pour un
  seul jar (`[[mods]]` avec un second bloc modId="iris" dans neoforge.mods.toml, à tester avec
  prudence — risque de recréer le crash-loop de `IrisForgeMod` si mal fait, donc TOUJOURS tester
  sur `C:\lanterne-server-test` avant tout déploiement VPS/client).

## Capacité de test découverte cette nuit

`C:\lanterne-server-test\` : serveur NeoForge 26.3.0.7-beta jetable, layout complet fonctionnel
(JDK: `C:\Users\trufa\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2\bin\java.exe`, PAS le java
du PATH qui est trop ancien). **Limite importante** : c'est un SERVEUR DÉDIÉ, il ne charge jamais
le code client (`Minecraft.<init>`, `Iris.createPipeline`, tout ce qui a planté cette nuit) — donc
il ne peut PAS remplacer un vrai test client. Un vrai lanceur de CLIENT jetable (pas juste
serveur) reste à construire si on veut vérifier le rendu/les shaders sans dépendre du patron —
voir si CurseForge stocke une commande de lancement complète réutilisable quelque part dans
l'instance `test 1` (minecraftinstance.json ?).

## Prochaines tâches réelles (pas juste la liste générique du goal)

1. Régler la visibilité "Iris" pour Sodium/reeses-sodium-options (voir ci-dessus).
2. Continuer le retrait de Sodium : nouvelle categorie trouvee (mixins sur cibles vanilla important Sodium, 4 fichiers - MixinLevelRenderer/MixinWindow/MixinRenderPipeline/MixinDebugScreenEntriesList), fix propose (etendre IrisMixinPlugin.shouldApplyMixin), pas applique - voir RECHERCHE-VULKAN-SANS-SODIUM.md.
3. Continuer Vulkan (`lab/ShadercProbe.java` prouve GLSL→SPIR-V fonctionnel, prochaine étape :
   l'intégrer réellement au pipeline de rendu, pas juste un prototype isolé).
4. Construire un vrai lanceur de client jetable pour tester sans dépendre d'un humain éveillé.

## Tentative de lanceur client jetable (fork, 21/09 ~01h25)

**Progrès réel, pas terminé.** `C:\lanterne-client-test\` créé via `neoforge-installer.jar --installClient .`
(nécessite un `launcher_profiles.json` factice copié depuis `Install/launcher_profiles.json` pour
passer sa vérification — sinon refuse de tourner). Classpath reconstruit manuellement en PowerShell
depuis les deux JSON de version (`versions/26.3/26.3.json` + `versions/neoforge-26.3.0.7-beta/*.json`),
en piochant les libs manquantes dans le dossier partagé `C:\Users\trufa\curseforge\minecraft\Install\libraries\`
(102/142 résolues directement, les 40 restantes sont TOUTES des natives Linux/macOS non pertinentes
sur Windows — pas un vrai manque).

**Lancé pour de vrai** (`net.neoforged.fml.startup.Client` avec Lanterne+Sodium dans mods/) : LWJGL
charge ses natives correctement (juste des warnings Java inoffensifs), mais FML échoue avec
"neoforge is not installed" — NeoForge était sur le classpath brut (`-cp`) mais pas découvert
comme un vrai "mod" par le mécanisme de découverte de FML (qui attend probablement une disposition
de dossier spécifique, pas juste une présence sur le classpath Java classique). Pas résolu faute de
budget de contexte suffisant pour cette tâche en une seule passe.

**Prochaine étape concrète** pour qui reprend ça : regarder comment le serveur jetable
`C:\lanterne-server-test\` (qui MARCHE) construit son propre classpath/arguments — probablement via
`@libraries/net/neoforged/neoforge/26.3.0.7-beta/win_args.txt` généré par l'installeur en mode SERVEUR
(`--installServer`), qui gère correctement cette découverte. Le mode CLIENT ne génère pas cet
équivalent (`--installClient` vise le vrai launcher Mojang, pas un lancement autonome) — chercher un
`unix_args.txt`/`win_args.txt` équivalent pour le client, ou étudier `net.neoforged.fml.loading.moddiscovery`
pour comprendre son vrai mécanisme de découverte avant de retenter.

Dossier `C:\lanterne-client-test\` laissé en place (classpath.txt inclus) pour repartir de là plutôt
que de tout refaire. Process de test arrêté proprement.

**TÂCHE B (visibilité Iris/Sodium) — investiguée en profondeur, correctif risqué reporté (fork, 21/09 ~01h35).**

Diagnostic précis, corrige l'hypothèse initiale : ce n'est PAS `ModList.isLoaded("iris")`. Sodium
lui-même (le vrai moteur, `net.caffeinemc.sodium-neoforge-0.9.2+mc26.3-mod.jar`) ne contient
AUCUNE classe liée à Iris — zéro détection côté Sodium. `reeses-sodium-options` n'est même pas
installé sur `test 1`. Le vrai mécanisme : NeoForge lit `modproperties.<modId>."sodium:config_api_user"`
dans `neoforge.mods.toml` pour savoir quelle classe (`ConfigEntryPoint`) appeler pour CHAQUE mod
qui en déclare une. Iris déclarait `modproperties.iris."sodium:config_api_user" =
net.irisshaders.iris.compat.sodium.config.IrisConfig` dans SON PROPRE toml original — perdu à la
fusion car "iris" n'est plus un modId. Lanterne a DÉJÀ sa propre entrée
(`modproperties.${mod_id}."sodium:config_api_user" = fr.clubcitrouille.lanterne.client.sodium.Graft`,
`client/sodium/Graft.java`) — un mod ne peut en déclarer qu'UNE seule.

Piste explorée : `Graft.registerConfigLate` et `IrisConfig.registerConfigLate` ont la même
signature (`ConfigEntryPoint.registerConfigLate(ConfigBuilder)`) — semblait fusionnable en
appelant les deux depuis Graft sur le même builder. MAIS les deux appellent
`builder.registerOwnModOptions()` (Graft ligne 104, IrisConfig ligne 42) — cette méthode pose le
nom/icône/thème de couleur de LA page du mod appelant, appelée une seule fois par convention.
L'appeler deux fois depuis le même point d'entrée risque un conflit ou un écrasement silencieux
(le second `.setName("Iris")` écraserait potentiellement le nom déjà posé par Graft) — jamais
vérifié en pratique, risque réel de casser la page de réglages Lanterne déjà utilisée par les
joueurs. PAS tenté en conditions de fin de session (contexte fork limité, pas de marge pour
itérer si ça casse). Documenté plutôt que forcé, comme demandé.

**Piste de reprise recommandée** : soit fusionner à la main le CONTENU des deux
`registerConfigLate` dans une seule méthode (une seule page combinée "Lanterne" avec les
sections Iris dedans, pas un onglet séparé "Iris" comme avant — changement de comportement
visible, à valider avec le patron plutôt qu'à décider seul), soit vérifier si l'API Sodium
expose un moyen de déclarer plusieurs "mod options" depuis un seul appelant (pas trouvé dans les
~50 premières lignes explorées de `ConfigBuilder`/`ConfigEntryPoint`, pas allé plus loin).

## Artefact visuel du ciel (bordure nette, teinte différente) — même cause probable, PAS corrigé

Signalé par le patron en jeu (capture non accessible au fork qui a investigué, décrit comme une
ligne horizontale nette dans le ciel séparant deux teintes, scène de nuit, DH actif).

**Hypothèse forte, avec preuve partielle (javap sur le vrai jar `DistantHorizons-3.3.0-26.3-fabric-neoforge.jar`), PAS un correctif tenté** :

- DH a son propre système de brouillard/ciel qui **désactive le fog vanilla** (`MixinVanillaFogCommon.cancelFog()`, confirmé en bytecode : renvoie `true` sauf cas spéciaux comme être dans un fluide ou aveuglé) pour dessiner SON PROPRE brouillard jusqu'à sa distance LOD (shaders `assets/distanthorizons/shaders/fog/gl/fog.frag` etc.).
- DH a un vrai accessoire de compat Iris (`neoforge/wrappers/modAccessor/IrisAccessor.class`, `getModName()` renvoie littéralement `"iris"`) qui appelle `IrisApi.getInstance().isShaderPackInUse()` — cet appel EN LUI-MÊME fonctionne toujours (appel Java direct sur une classe maintenant dans le jar Lanterne, pas de dépendance à un modId).
- **Mais** : je n'ai pas pu vérifier en bytecode (budget de fork insuffisant pour aller plus loin) SI DH décide d'instancier/utiliser cet `IrisAccessor` en se basant sur `ModList.isLoaded("iris")` AVANT même d'appeler `isShaderPackInUse()`. Si oui — ce qui est l'explication la plus probable vu le nom de la méthode (`getModName()` typique d'un pattern "vérifie que ce mod est chargé, puis instancie son accessor") — alors DH ne sait plus qu'Iris est actif, garde son PROPRE brouillard/fog activé en plus de celui d'Iris, d'où la bordure visible : c'est EXACTEMENT le même bug racine déjà documenté ci-dessus pour le menu Sodium (`ModList.isLoaded("iris")` cassé partout depuis la fusion), pas un nouveau bug introduit par la migration au sens strict — une CONSÉQUENCE du même problème sous-jacent.

**Recommandation** : ne pas traiter ce symptôme séparément. Le futur correctif de la visibilité
"iris" comme modId (voir section précédente, piste `[[mods]] modId="iris"` passif à tester avec
prudence sur `C:\lanterne-server-test`) devrait résoudre CE bug-ci également s'il est confirmé.
Vérifier après coup avec un vrai test client (le patron, ou le lanceur client jetable une fois
terminé) : si la bordure disparaît une fois "iris" à nouveau visible dans `ModList`, l'hypothèse
est confirmée rétroactivement.

Rien modifié dans le code pour cette tâche (investigation seule, hypothèse forte mais pas 100%
vérifiée par manque de budget de contexte). Aucune compilation nécessaire.

## TÂCHE A — BlueMap/pregen ne reprenait pas au boot — CORRIGÉ, testé, preuve réelle (fork, 21/09 ~01h37)

**Cause confirmée** : `Cadence.onServerStarted` ne s'occupait que de `Pregen.resumeIfPending` — rien
n'appelait jamais `resumeFullRender()` de BlueMap au démarrage lui-même, seulement à la transition
« dernier joueur part » (`onPlayerLeave`). Après un redémarrage à froid avec 0 joueur, le rendu
BlueMap restait donc bloqué sur « Render-Threads are STOPPED » indéfiniment. Pregen, lui, n'avait
en réalité PAS de bug — `resumeIfPending` était déjà correctement câblé (voir `lab/Pregen.java`,
`stateFile()`/`writeState()`/`clearState()`) ; l'observation du patron était probablement due au
pregen ayant simplement déjà terminé son rayon de 500 chunks, pas à un vrai bug.

**Correctif** : `CadenceBlueMap.startOnReady(Supplier<Boolean>)` (nouvelle méthode) s'accroche à
`BlueMapAPI.onEnable(...)`, qui se déclenche dès que le plugin BlueMap devient réellement prêt
(chargement de ses ressources/stockage asynchrone après `ServerStartedEvent` — confirmé dans les
logs, « Loaded! » arrive plusieurs secondes après « Done »). Si aucun joueur n'est connecté à ce
moment-là, démarre `getRenderManager().start()` directement. `Cadence.register()` appelle
`CadenceBlueMap.startOnReady(...)` derrière un `Compagnons.present(BLUEMAP)`, sans jamais référencer
un type BlueMap dans sa propre signature (leçon du crash #2 de cette nuit, voir Javadoc des deux
classes). Commit `9da44c7`.

**Preuve réelle** : rebuild (`26.3.57`), déployé sur `C:\lanterne-server-test\`. Premier test
bloqué par `config/bluemap/core.conf` → `accept-download: false` (BlueMap refusait de charger ses
ressources tant que l'EULA de téléchargement Mojang n'était pas accepté sur CE serveur jetable
précis — pas un bug Lanterne, juste la config du serveur de test). Corrigé (`accept-download: true`),
serveur relancé, log confirmé :
```
[BlueMap-Plugin-Loading/INFO] [fr.cl.la.Lanterne/]: [CADENCE] BlueMap prêt, 0 joueur connecté —
rendu complet démarré automatiquement (plus besoin d'attendre une connexion/déconnexion).
[BlueMap-Plugin-Loading/INFO] [BlueMap/]: Loaded!
```
**PAS encore déployé sur le VPS de prod** — attend confirmation/réveil du patron avant ce déploiement
précis (contrairement à d'autres fixes cette nuit, celui-ci touche le comportement au redémarrage
donc mieux vaut un œil humain avant de le pousser en prod, même si le test jetable est concluant).

## TÂCHE B — modId "iris" passif dans neoforge.mods.toml — TESTÉ, ne crash PAS, déployé sur `test 1` (fork, 21/09 ~01h40)

Reprise de la piste documentée plus haut (« modId virtuel supplémentaire », risque de recréer le
crash-loop du tout premier bug de la nuit). Ajouté un second bloc `[[mods]]` avec
`modId = "iris"`, SANS aucune classe `@Mod("iris")` associée (exact inverse du crash #1 — annotation
sans déclaration mods.toml ; ici c'est une déclaration mods.toml sans annotation). Commit `7a13b94`.

**Preuve réelle sur `C:\lanterne-server-test\`** : build `26.3.58`, serveur relancé, log :
```
Done (0.443s)! For help, type "help"
```
— aucun crash, aucune `ModLoadingException`, aucune erreur liée à `iris` dans les logs de
démarrage (grep exhaustif sur "iris" ne montre que les 3 warnings inoffensifs habituels de refmap
mixin, déjà présents avant ce changement). Le fix BlueMap de TÂCHE A fonctionne toujours en même
temps (`[CADENCE] BlueMap prêt...` reconfirmé sur ce même build). **NeoForge accepte donc bien un
`[[mods]]` metadata-only sans classe `@Mod` correspondante** — contrairement à la crainte initiale.

**Pas encore vérifié** : que `ModList.get().isLoaded("iris")` renvoie effectivement `true` en jeu
avec ce build (le serveur jetable ne permet pas de le confirmer facilement sans un client réel ou
une commande console qui l'affiche — aucune commande de ce type trouvée cette nuit). Donc l'effet
attendu (menu Sodium ré-affiche un onglet Iris, artefact du ciel disparaît) reste à confirmer
visuellement, PAS juste supposé.

**Déployé sur `test 1/mods/lanterne-26.3.58.jar`** (remplace `26.3.56`) pour que le patron teste au
réveil : lancer le client, vérifier (a) toujours 0 crash, (b) le menu vidéo Sodium/Iris, (c) si
l'artefact du ciel a disparu. **PAS déployé sur le VPS de prod** — même raison que TÂCHE A, à
confirmer par un test client réel avant de toucher la prod, ce risque-ci étant spéculatif
(inverse jamais testé du tout premier crash de la nuit).

**Serveur jetable arrêté proprement après ces deux tests** (aucun process resté en arrière-plan
inutilement).

## TÂCHE C — lanceur client jetable `C:\lanterne-client-test\` : progrès réel, PAS débloqué (fork, 21/09 ~01h50)

Reprise de la piste prioritaire (débloquer un vrai test client pour toute la nuit). Deux bugs réels
trouvés et corrigés dans la tentative de lancement, un blocage plus profond découvert et non résolu.

**Bug #1 trouvé et corrigé — `classpath.txt` en CRLF corrompait le classpath Java.** Le fichier
généré par une tentative précédente a des fins de ligne Windows (`\r\n`). En construisant le
`-cp` avec `tr '\n' ';'` depuis Git Bash, chaque chemin de jar gardait un `\r` collé à la fin
(sauf le dernier), donc chaque entrée du classpath pointait vers un fichier inexistant. Résultat
observé : `ClassNotFoundException: net.neoforged.fml.startup.Client` alors que la classe existe
bel et bien dans `libraries/net/neoforged/fancymodloader/loader/12.0.0/loader-12.0.0.jar` (vérifié
via `jar tf`). Correctif : `tr -d '\r' < classpath.txt | tr '\n' ';'` avant de construire `-cp`.
**Leçon pour la suite** : tout fichier `.txt`/`.json` généré par l'installeur NeoForge sous Windows
est en CRLF — toujours le nettoyer avant de le consommer depuis un outil POSIX (Bash/tr/sed).

**Bug #2 trouvé — les 3 arguments `--fml.neoForgeVersion/--fml.mcVersion/--fml.neoFormVersion`
sont obligatoires côté client aussi**, exactement comme documenté plus haut pour le serveur
jetable qui marche (`win_args.txt`). Sans eux, FML ne sait pas résoudre le jar patché depuis
`-DlibraryDirectory`. Avec eux, on passe bien le `ClassNotFoundException` ET on atteint enfin le
vrai scan de mods FML (logs montrent `BackgroundScanHandler/SCAN` sur `lanterne-26.3.55.jar`,
`sodium-neoforge-...jar`, etc. — jamais atteint dans les tentatives précédentes).

**Blocage restant, non résolu** : même avec les deux corrections ci-dessus, `--fml.startup.Client`
échoue encore avec :
```
[main/ERROR] [ne.ne.fm.lo.FMLLoader/]: The patched Minecraft jar is missing. Please try to reinstall NeoForge.
- Mod sodium requires neoforge 26.1.2.10-beta or above / Currently, neoforge is not installed
- Mod lanterne requires neoforge 26.3.0.7-beta or above / Currently, neoforge is not installed
```
alors que le jar patché EXISTE bel et bien et est physiquement présent et lisible :
`C:\lanterne-client-test\libraries\net\neoforged\minecraft-client-patched\26.3.0.7-beta\minecraft-client-patched-26.3.0.7-beta.jar`
(39 Mo, taille cohérente). Les logs montrent même que FML tente d'ouvrir ce jar comme fichier de
mod (`BackgroundScanHandler/SCAN` dessus) avant d'abandonner avec une `CancellationException` en
cascade sur TOUS les fichiers de scan en cours — signe que l'échec est décidé ailleurs (probablement
une vérification de hash/manifeste ou un marqueir d'installation que `--installClient` ne pose pas,
contrairement à `--installServer`) et que la `CancellationException` n'est qu'un symptôme de l'abandon
global, pas la cause. Testé avec `-DlibraryDirectory` en absolu ET en relatif (comme le serveur) :
même erreur dans les deux cas.

**Piste pour qui reprend ça** : comparer ce que `--installServer` écrit de différent de
`--installClient` autour du fichier patché — chercher un fichier de métadonnées/hash à côté du
jar patché côté serveur (jetable, qui marche) et voir s'il manque côté client. Ou décompiler/lire
`FMLLoader.java` (dans `loader-12.0.0.jar`, méthode autour de la ligne 356, message "patched
Minecraft jar is missing") pour voir la condition exacte testée.

**Décision prise faute de temps** : bascule sur le correctif de repli prévu par la directive plutôt
que de continuer à creuser un blocage sans certitude de délai. Deux process de test lancés pendant
cette investigation (PID Windows 39576 et 3780, tous deux dans `C:\lanterne-client-test\`, tous deux
déjà en échec FML) ont été arrêtés proprement et individuellement (`taskkill //PID <pid> //F`,
jamais un `taskkill` global sur `java.exe` — les autres process Java de la machine n'ont pas été
touchés).

## TÂCHE C (suite) — lanceur client jetable DÉBLOQUÉ, preuve réelle (fork, 21/09 ~02h00)

Reprise de TÂCHE C avec pour objectif unique de débloquer « The patched Minecraft jar is missing ».
**Débloqué. Le client démarre jusqu'au menu principal (logs propres, 0 FATAL).**

**Vraie cause trouvée (le message d'erreur était trompeur)** : ce n'est PAS le jar patché qui
manquait. En creusant `logs/latest.log` AVANT la cascade de `CancellationException` (qui n'est
qu'un symptôme), la vraie première erreur était :
```
[ne.ne.fm.lo.mo.lo.RequiredSystemFiles/]: Couldn't find [net/neoforged/neoforge/common/NeoForgeMod.class,
NeoForge MANIFEST.MF, net/neoforged/neoforge/client/ClientNeoForgeMod.class] on classpath, while we did
find other required files in: [jar(libraries/net/neoforged/minecraft-client-patched/.../minecraft-client-patched-....jar)]
```
Le jar patché (`minecraft-client-patched-*.jar`) ne contient QUE les classes Minecraft patchées —
`NeoForgeMod.class`/`ClientNeoForgeMod.class` vivent dans un jar **séparé** :
`libraries/net/neoforged/neoforge/26.3.0.7-beta/neoforge-26.3.0.7-beta-universal.jar`. Vérifié avec
`jar tf` : ce jar existe bel et bien côté client (`find`/`jar tf` confirmé), mais **le classpath
construit à la main par le fork précédent (`classpath.txt`/`launch_attempt2_cmd.txt`) ne le
listait pas**. FML cherche dynamiquement les fichiers requis dans les jars présents sur le
classpath — sans `neoforge-*-universal.jar` sur `-cp`, il ne trouve jamais `NeoForgeMod.class`,
et balance le message générique (et trompeur) « patched Minecraft jar is missing » à la place du
vrai problème.

**Première tentative de correctif (a révélé un 2e piège)** : ajouter
`neoforge-26.3.0.7-beta-universal.jar` directement sur le `-cp` brut, à côté du
`minecraft-client-patched-*.jar` (lui aussi ajouté à la main par le fork précédent). Ça corrige
bien l'erreur `RequiredSystemFiles`, MAIS fait détecter à FML un faux « dev environment » (probablement
parce qu'avoir le jar patché + le jar universal tous les deux en RAW sur `-cp` ressemble au classpath
généré par `gradle runClient`, pas à un vrai install de prod) → nouvelle erreur :
```
NeoForge dev environment Minecraft jar does not have a Minecraft-Dists attribute in its manifest;
this may be because you have an out-of-date gradle plugin
```

**Correctif final qui marche** : ne PAS mettre `minecraft-client-patched-*.jar` ni
`neoforge-*-universal.jar` sur le `-cp` brut du tout — exactement comme le fait le serveur jetable
qui marche (`win_args.txt` du serveur ne contient AUCUN des deux). FML les résout tout seul de façon
dynamique via `-DlibraryDirectory=<...>/libraries` + les 3 arguments `--fml.neoForgeVersion
--fml.mcVersion --fml.neoFormVersion`. Retirer ces deux jars du classpath (en gardant tout le reste :
les ~70 libs support + les libs client LWJGL/son/etc.) a suffi : **le client charge textures/atlas,
`Sound engine started`, Iris crée son pipeline pour `minecraft:overworld`, aucune ligne FATAL dans
`logs/latest.log` ni `logs/debug.log` (`grep -ic fatal` → 0 sur les deux).**

Warnings non bloquants observés (normaux, à ignorer) : bascule Vulkan→OpenGL silencieuse (Lanterne
log `[LANTERNE][GPU] ... le backend actif est OpenGL ... la création du backend Vulkan a
probablement échoué silencieusement` — sujet séparé, cf. section Vulkan plus bas, PAS un blocage du
lanceur), et échec d'auth Realms (`Failed to parse into SignedJWT: 0`, normal avec un faux compte
`--uuid 00000000...0 --accessToken 0 --userType legacy`).

**Fichier de commande final qui marche, prêt à relancer tel quel** :
`C:\lanterne-client-test\launch_attempt7_cmd.txt` (une seule ligne, java.exe + tous les arguments)
et sa version `.bat` équivalente `C:\lanterne-client-test\launch7.bat`. Pour relancer (depuis Git
Bash, ne PAS invoquer via `cmd.exe //C "chemin_long_direct"` — la ligne de commande cmd.exe a une
limite ~8191 caractères et le classpath complet la dépasse ; passer par un `.bat` sur disque
contourne cette limite) :
```
cmd.exe //C "C:\lanterne-client-test\launch7.bat > C:\lanterne-client-test\stdoutN.log 2> C:\lanterne-client-test\stderrN.log"
```
puis lire `C:\lanterne-client-test\logs\latest.log` (le launcher supprime/tourne les logs précédents
à chaque lancement — si erreur "fichier utilisé par un autre processus" au démarrage, c'est qu'un
process précédent tourne encore, à arrêter par PID précis avant de relancer).

**Piste restée ouverte, PAS ce soir** : pourquoi `--installClient` ne génère pas de
`win_args.txt`/`unix_args.txt` (contrairement à `--installServer`) reste vrai et non expliqué côté
installeur — mais n'est plus bloquant puisqu'on a maintenant un classpath équivalent construit à la
main qui fonctionne. Si un jour on veut un vrai lanceur "propre" sans classpath fait main, il faudrait
comprendre pourquoi l'installeur omet ce fichier côté client.

**Process de test arrêtés proprement** après validation (PID Windows 38464 et 18848, tous deux dans
`C:\lanterne-client-test\`, arrêtés individuellement via `taskkill //PID <pid> //F` — jamais de kill
global sur `java.exe`).

**Ce lanceur est maintenant réutilisable pour toute la suite de la nuit** (vérification visuelle du
guard Sodium de TÂCHE D, tests Vulkan, Iris, Dials "Shaders") : relancer `launch7.bat` tel quel.
Aucun code du dépôt Lanterne (`C:\Users\trufa\Documents\Lanterne`) n'a été touché pour cette tâche —
uniquement des fichiers dans `C:\lanterne-client-test\`, hors dépôt git. Rien déployé sur le VPS ni
sur `test 1`.

## TÂCHE D — correctif de repli : guard Sodium sur les 4 mixins vanilla d'Iris — NON TESTÉ VISUELLEMENT (fork, 21/09 ~01h55)

Puisque le lanceur client n'a pas pu être débloqué à temps (TÂCHE C), application du correctif de
repli prévu par la directive de cette tâche : `IrisMixinPlugin.shouldApplyMixin` (
`src/main/java/net/irisshaders/iris/mixin/IrisMixinPlugin.java`) gate maintenant les 4 mixins
identifiés dans `RECHERCHE-VULKAN-SANS-SODIUM.md` (§"mixins vanilla qui importent Sodium") —
`MixinLevelRenderer`, `MixinWindow`, `MixinRenderPipeline`, `MixinDebugScreenEntriesList`, tous
dans `net.irisshaders.iris.mixin` — derrière `Compagnons.present(Compagnons.SODIUM)`. Sans Sodium
installé, ces 4 mixins ne s'appliquent plus du tout (`shouldApplyMixin` renvoie `false`), ce qui
évite en théorie une résolution de type Sodium absent au moment où Mixin vérifie leur bytecode.

**Preuve réelle** : compilation seule (`./gradlew compileJava`), propre, aucune erreur — voir
sortie `Note: ... uses unchecked or unsafe operations` habituelle sur `MixinRenderFlame.java`
(préexistante, sans rapport). **PAS de test client** (le lanceur jetable n'est toujours pas
fonctionnel, TÂCHE C) : cet effet réel — comportement du rendu vanilla avec Sodium absent — n'a
PAS été vérifié visuellement, conformément à la leçon répétée toute la nuit. À confirmer par le
patron via un vrai client (avec et sans Sodium installé) avant de considérer ce fix terminé.

**PAS déployé sur le VPS.** Compilé et commité sur `26.3` uniquement, jamais poussé.

## TÂCHE B+D — vérification visuelle réelle via le lanceur client débloqué — CONFIRMÉ SAIN, déployé sur `test 1` (fork, 21/09 ~02h06)

Le lanceur client jetable (`launch7.bat`, TÂCHE C) a enfin permis de tester les deux correctifs en
attente ensemble, avec Sodium réellement présent dans `mods/` (contrairement aux tests serveur
précédents qui ne pouvaient pas le vérifier).

**Build `26.3.59`** = commit `50409bc` (HEAD de `26.3` à ce moment), donc TÂCHE B (modId "iris"
passif) + TÂCHE D (guard Sodium sur les 4 mixins vanilla) + tout le reste de la nuit, réunis dans
un seul jar.

**Preuve réelle (logs `C:\lanterne-client-test\logs\latest.log` + `debug.log`, run complet jusqu'au
menu principal, GPU réel utilisé — RTX 3080 Laptop)** :

- `grep -ic fatal` → **0** sur `latest.log` ET `debug.log`. Aucun `crash-reports/`.
- **`ModFileInfo/LOADING`: "Found valid mod file lanterne-26.3.59.jar with {lanterne,iris} mods —
  versions {26.3.59,26.3.59}"** — confirmation directe et non ambiguë que FML voit maintenant
  DEUX modId dans le même fichier. `ModList.get().isLoaded("iris")` doit donc renvoyer `true`
  partout où c'est appelé (DH `IrisAccessor`, etc.) — **la cause racine documentée plus haut pour
  le menu Sodium ET l'artefact du ciel est structurellement corrigée** par ce seul changement.
- Sodium charge sans erreur (`Loaded configuration file for Sodium: 37 options`), son
  `ResourcePackScanner` émet un avertissement bénin sur le resource pack `mod/lanterne,iris`
  ("replaces terrain shaders, which are not supported" — un simple avis informatif de Sodium sur
  les shaders vanilla, PAS un crash, PAS lié aux correctifs de cette nuit).
- `Iris/`: pipeline créé pour `minecraft:overworld` sans erreur, aucune exception liée aux 4
  mixins vanilla de TÂCHE D (`MixinLevelRenderer`/`MixinWindow`/`MixinRenderPipeline`/
  `MixinDebugScreenEntriesList`) — le guard `Compagnons.present(SODIUM)` ne casse rien avec Sodium
  RÉELLEMENT présent (c'était le risque non vérifié cette nuit).
- Process resté stable ~30s à l'écran-titre (mémoire ~1 Go, aucun crash différé), arrêté proprement
  (`taskkill //PID 20512 //F`, confirmé disparu).

**Ce qui reste NON vérifiable par les logs seuls** (nécessite un œil humain ou une capture
d'écran) : que l'onglet "Iris" réapparaît VISUELLEMENT dans le menu vidéo Sodium (le mécanisme
`sodium:config_api_user` documenté dans TÂCHE B reste un point distinct — un seul mod peut
déclarer cette propriété, Lanterne/`Graft.java` la détient déjà, donc l'onglet Iris séparé dans
Sodium ne réapparaîtra PAS tel quel avec ce correctif seul ; ce que `{lanterne,iris}` corrige,
c'est `ModList.isLoaded("iris")`, un mécanisme différent — voir le diagnostic déjà détaillé plus
haut). Et que l'artefact de ciel (bordure nette DH) a bien disparu.

**Déployé sur `test 1/mods/lanterne-26.3.59.jar`** (remplace `26.3.58`, aucun client `test 1` en
cours d'exécution au moment du remplacement — vérifié via `tasklist`). **PAS déployé sur le VPS**,
comme convenu — décision laissée au coordinateur/au patron au réveil.

**Prochaine étape réelle qui reste ouverte** : la vraie fusion des deux `registerConfigLate`
(Graft + IrisConfig) pour restaurer un onglet Iris dans Sodium, documentée dans TÂCHE B comme un
changement de comportement visible à ne pas décider seul sans le patron — toujours vrai, pas
tenté cette nuit.

## TÂCHE VULKAN — chaîne jcpp → glsl-transformer → shaderc prouvée de bout en bout, SUCCÈS réel (fork, 21/09 ~02h15)

Reprise du blocage précis documenté dans `RECHERCHE-VULKAN-SANS-SODIUM.md` (étape 3,
`RenderSystem.getDevice()` exige un device GPU vivant, jamais testable en standalone). Maintenant
que le lanceur client jetable marche (TÂCHE C ci-dessus), ce blocage n'existe plus DANS un vrai
client lancé.

**Ajouté** (aucun fichier `net.irisshaders`/`kroppeb`/`mixins.iris*` touché) :
`lab/ShadercProbe.testCompositeInClient` (appelle le vrai `TransformPatcher.patchComposite`
d'Iris, jamais modifié) et `lab/ShadercProbeSelfCheck` (gated `LANTERNE_SHADERC_COMPOSITE_TEST=1`,
même patron que `RegardSelfCheck` — jamais actif pour un joueur normal).

**Résultat, dans un vrai client (copie isolée `C:\lanterne-client-vulkan-test\`, pour ne pas
interférer avec le test TÂCHE B+D en cours dans `C:\lanterne-client-test\`)** :
`TransformPatcher.patchComposite` a réellement fonctionné (le device GPU a répondu). Premier essai
shaderc a échoué avec une erreur précise et honnête (`SPIR-V requires layout(binding=)/location=`
— le GLSL "style OpenGL" produit par Iris n'a jamais eu besoin de ces décorations explicites).
Corrigé en activant `shaderc_compile_options_set_auto_bind_uniforms`/`set_auto_map_locations` sur
un handle d'options shaderc (jusque-là toujours 0/absent). Deuxième essai :
```
[SHADERC] TransformPatcher.patchComposite reussi (363 caracteres patches), tentative shaderc...
[SHADERC] Compilation GLSL->SPIR-V reussie : 968 octets SPIR-V produits.
[SHADERC-TEST] RESULTAT: SUCCES
```
`grep -ic fatal` → 0. **Première preuve de bout en bout, dans un vrai contexte de jeu, que la
chaîne complète produit du SPIR-V valide à partir d'un vrai fragment composite.**

**Ce que ça ne prouve PAS encore** (voir le détail complet dans `RECHERCHE-VULKAN-SANS-SODIUM.md`,
étape 4) : un seul fragment composite autonome testé (pas vertex/geometry, pas `patchVanilla`/
`patchSodium`/`patchDHTerrain`, pas de `#include`) ; le SPIR-V produit n'a jamais été chargé dans
un vrai pipeline Vulkan ; les bindings/locations auto-assignés ne correspondraient pas forcément à
ce qu'un vrai pipeline attend (descriptor sets).

**Prochaine étape concrète** : essayer `patchVanilla` (le point d'entrée le plus utilisé, shaders
"gbuffers") avec un vrai shader vertex+fragment, toujours via ce même lanceur/patron. **Jamais
déployé sur le VPS ni `test 1`** — testé uniquement sur le lanceur client jetable isolé.

**Suite immédiate (même session, ~02h18)** : `patchVanilla` testé (`ShadercProbe.testVanillaInClient`).
La transformation AST réussit aussi pour ce point d'entrée (1732+1398 caractères patchés), mais
shaderc rejette les deux étages : `'non-opaque uniforms outside a block' : not allowed when using
GLSL for Vulkan`. **Nouveau blocage précis, différent de celui du composite** : le GLSL produit par
Iris (`patchVanilla`) déclare ses uniforms hors bloc UBO — valide en OpenGL, interdit par la
spécification SPIR-V Vulkan. `auto_bind_uniforms`/`auto_map_locations` ne corrigent pas ceci (ça
assigne des indices, pas une structure de bloc). Détail complet + deux pistes de reprise (cible
`shaderc_target_env_opengl` en intermédiaire, ou un vrai pass de re-blocage des uniforms) dans
`RECHERCHE-VULKAN-SANS-SODIUM.md`, étape 5. Conclusion honnête : la chaîne Iris→shaderc marche
pour appeler le code, mais le GLSL qu'Iris produit aujourd'hui cible structurellement OpenGL, pas
Vulkan — la prochaine vraie tâche Vulkan est un pass de re-blocage des uniforms, pas un réglage
shaderc. `grep -ic fatal` → 0. Rien déployé nulle part, testé uniquement sur le lanceur isolé.

## TÂCHE VULKAN (suite) — UniformReblocker : re-blocage des uniforms en UBO, SUCCÈS PARTIEL réel (fork, 21/09 ~02h30)

Reprise du blocage précis de l'étape 5 (`patchVanilla` produit des uniforms hors bloc UBO,
illégal SPIR-V Vulkan). Piste choisie : un vrai pass de re-blocage (`lab/UniformReblocker.java`,
nouveau, aucun fichier Iris touché) plutôt que `shaderc_target_env_opengl` (jugé cul-de-sac pour
l'objectif final Vulkan natif). Post-traite le GLSL déjà patché par `TransformPatcher.patchVanilla`
pour regrouper les uniforms non-opaques dans un bloc `layout(binding=N, std140) uniform {...};`.

**Premier essai** : nouveau blocage honnête, `layout(binding=)` exige GLSL 420+ ou une extension
sur `#version 150` — corrigé en ajoutant `#extension GL_ARB_shading_language_420pack : enable`
(commit `cfd737a`).

**Deuxième essai, testé pour de vrai dans un client jetable isolé (`C:\lanterne-client-vulkan-test-2\`,
copie dédiée pour ne pas interférer avec d'autres forks en cours)** : **l'étage FRAGMENT re-bloqué
compile en SPIR-V valide (3532 octets)** — première preuve réelle que le re-blocage résout bien le
blocage UBO pour un shader `patchVanilla` complet. L'étage VERTEX échoue encore, mais pour une
raison SÉPARÉE et non liée au re-blocage : le shader de test fabriqué utilise des builtins GLSL
legacy (`ftransform()`, `gl_TexCoord`) que `CompatibilityTransformer` ne modernise pas totalement
dans ce cas précis — hors périmètre de cette tâche, détail complet dans
`RECHERCHE-VULKAN-SANS-SODIUM.md` étape 6.

`grep -ic fatal` → 0. Rien déployé sur le VPS ni `test 1`. Commits `b01e08a`, `cfd737a` sur `26.3`,
jamais poussés. Process de test arrêté proprement (PID ciblé individuellement, jamais de kill
global `java.exe`).

**Prochaine étape concrète** : reformuler `VANILLA_VERTEX_SOURCE`/`VANILLA_FRAGMENT_SOURCE` dans
`ShadercProbeSelfCheck` sans builtins legacy, pour confirmer que l'étage vertex compile aussi une
fois isolé de ce problème annexe ; puis tenter de charger le SPIR-V produit dans un vrai
`VkShaderModule` (jamais fait — seule la compilation GLSL→SPIR-V est prouvée à ce stade).

## VÉRIFICATION — reprise du pregen après redémarrage : PAS de bug, confirmé par relecture directe (fork, 21/09)

Demande explicite du patron : vérifier que le pregen reprend bien après un simple redémarrage
serveur (comme BlueMap, TÂCHE A), tout en restant piloté par la présence de joueurs. D'autres java.exe
tournaient déjà (forks Sodium/Vulkan en cours sur le lanceur client jetable) — pas touché au serveur
jetable partagé pour éviter toute collision ; vérification faite par lecture directe du code source
(`lab/Pregen.java`, `lab/Cadence.java`, `core/Config.java`), pas juste re-citée depuis ce fichier.

**Confirmé, ligne par ligne, aucun bug** :
- `Cadence.onServerStarted` (`@SubscribeEvent` sur `ServerStartedEvent`, inconditionnel, sans garde
  BlueMap) appelle `Pregen.resumeIfPending(overworld)` à CHAQUE démarrage serveur, crash ou normal.
- `resumeIfPending` lit `config/lanterne/pregen-en-cours.txt` (écrit par `writeState()` à chaque
  `begin()`, effacé par `clearState()` à la fin ou à un `halt()` explicite) ; s'il existe, relance
  `begin(overworld, rayon)` — la reprise est gratuite par construction (`readExistingChunks` relit les
  en-têtes `.mca` et saute tout ce qui existe déjà, voir Javadoc de classe).
- Le pilotage par la présence de joueurs est déjà natif et INDÉPENDANT du redémarrage : `Pregen.tick()`
  appelle `suspended(server)` à chaque tick, qui se met en pause dès qu'au moins un joueur est
  connecté (si `PREGEN_PAUSE_ON_JOIN=true`, valeur par défaut) et reprend seul au départ du dernier —
  sans perdre ce qui est déjà en vol, et sans compter le temps de pause dans le débit affiché.
- Donc après un redémarrage à froid avec des joueurs déjà connectés (rare, mais possible), le pregen
  reprend son état MAIS reste immédiatement suspendu par `suspended()` tant qu'il y a du monde —
  exactement le comportement voulu pour BlueMap, déjà natif ici depuis le début, pas besoin du même
  correctif que TÂCHE A (BlueMap n'avait, lui, aucune notion de pause par joueur avant cette nuit).

**Conclusion honnête** : la javadoc de classe de `Cadence.java` (ligne 18-24) dit déjà elle-même
« Pré-génération : déjà géré, rien à refaire » — vérifié vrai par cette relecture indépendante, pas
supposé. Aucune modification de code nécessaire. Rien à compiler, rien à tester en vrai (pas de
changement de comportement à vérifier). Chunky (mod externe retiré cette nuit, voir plus haut dans la
session) n'apporte aucune idée de persistance que Lanterne n'ait déjà : lecture directe de l'en-tête
`.mca` (pas de fichier d'état listant les chunks un par un, contrairement à Chunky qui journalise
chaque région) est en fait plus robuste — aucun fichier d'état à corrompre pour la liste des chunks
eux-mêmes, seul le rayon (un entier) est persisté.

## VÉRIFICATION — artefact de ciel (TÂCHE B) : hypothèse CONFIRMÉE à 100% par chaîne bytecode complète (fork, 21/09)

Directive : confirmer visuellement le fix TÂCHE B (onglet Sodium + artefact ciel). Pas touché au
serveur/client jetable partagé — plusieurs `java.exe` d'autres forks (Vulkan/Sodium) tournaient déjà
(vérifié `tasklist | grep -i java`, aucun tué). Décision : au lieu de relancer un 4e lanceur client en
parallèle (risque de collision de fichiers de logs/process avec les forks en cours), j'ai comblé le
seul maillon manquant de la preuve déjà en place — TACHES-NUIT.md notait explicitement
« je n'ai pas pu vérifier en bytecode SI DH décide d'instancier IrisAccessor en se basant sur
`ModList.isLoaded("iris")` ». C'est maintenant fait, avec la chaîne complète en javap sur le vrai jar
`DistantHorizons-3.3.0-26.3-fabric-neoforge.jar` (celui de `test 1/mods`) :

1. `neoforge/NeoforgeMain.initializeModCompat()` appelle littéralement
   `tryCreateModCompatAccessor("iris", IIrisAccessor.class, IrisAccessor::new)` (constante `"iris"`
   visible en pool de constantes, `ldc #112 // String iris`).
2. `AbstractModInitializer.tryCreateModCompatAccessor(String, Class, Supplier)` (celle qui reçoit un
   `String` seul, pas un tableau) appelle d'abord `IModChecker.isModLoaded("iris")` — si `false`,
   saute directement au `return` (offset 43→59), **sans jamais binder `IrisAccessor`** dans
   `ModAccessorInjector`.
3. `ModChecker.isModLoaded(String)` (impl NeoForge, `neoforge/wrappers/modAccessor/ModChecker.class`)
   délègue en UNE instruction directe à `ModList.get().isLoaded(String)` — aucune indirection, aucun
   cache, aucun autre chemin possible.
4. `AbstractModInitializer` (la partie qui gère le fog, autour de l'offset 229) fait
   `ModAccessorInjector.INSTANCE.get(IIrisAccessor.class)` puis `ifnull 421` — si jamais bindé
   (étape 2 sautée), toute la coordination fog/ciel avec Iris est zappée silencieusement, ce qui
   correspond exactement au symptôme décrit par le patron (bordure nette dans le ciel).

**Conclusion : la chaîne causale complète est maintenant prouvée par bytecode, de bout en bout, sans
aucun maillon supposé.** Avant TÂCHE B, `ModList.isLoaded("iris")` renvoyait `false` (plus aucun mod
séparé "iris") → DH ne bindait jamais son `IrisAccessor` → sa propre logique de fog restait active en
plus de celle d'Iris → artefact visuel. Depuis le build `26.3.59` (confirmé par les logs FML
`{lanterne,iris}` de la TÂCHE B+D), `ModList.isLoaded("iris")` renvoie `true` → DH bindera son
`IrisAccessor` au prochain lancement → sa logique de fog se désactivera correctement en présence
d'Iris → l'artefact devrait avoir disparu. **C'est du 100% de certitude sur le mécanisme, mais toujours
0% de capture d'écran réelle** — seul un œil humain (le patron) ou une future passe avec capture
d'écran peut confirmer le dernier maillon purement visuel (rendu correct des pixels), ce qui reste
hors de portée d'un fork sans affichage interactif.

**Onglet "Iris" dans Sodium** : pas re-testé (mécanisme déjà diagnostiqué comme DIFFÉRENT et non
résolu par TÂCHE B — voir section TÂCHE B plus haut, `sodium:config_api_user` mono-déclarant,
`git log` vérifié : aucun commit de fusion `Graft`/`IrisConfig` n'a été fait depuis). Statu quo
honnête : cet onglet ne réapparaîtra PAS tant que la fusion des deux `registerConfigLate` n'est pas
faite — changement de comportement visible à valider avec le patron, toujours pas tenté cette nuit.

Aucun fichier du dépôt modifié (investigation bytecode pure, aucun code Iris/DH/Lanterne touché),
aucune compilation nécessaire, rien déployé.

## TÂCHE SODIUM — premier vrai test client Sodium-absent, 1 crash corrigé, 1 nouveau trouvé et scopé (fork, 21/09 ~02h35)

Reprise du retrait de Sodium (`RECHERCHE-VULKAN-SANS-SODIUM.md`, ~33 fichiers de couplage dur
identifiés hier). Jusqu'ici tout reposait sur de la lecture de code — jamais un vrai lancement client
avec Sodium physiquement retiré de `mods/`. Fait via une copie isolée du lanceur jetable
(`C:\lanterne-client-sodium-test\`, clone de `C:\lanterne-client-test\`, `launch7.bat` réécrit pour
son propre chemin — jamais touché aux dossiers utilisés par les autres tâches en parallèle cette nuit).

**Crash #1 — corrigé, reteste OK.** Le guard `isModLoaded("sodium")` déjà présent autour de
`new EntityToTerrainVertexSerializer()` etc. dans `Iris.onRenderSystemInit` (commit `0473178`, avant
cette tâche) **ne suffisait pas** : premier test réel (build `26.3.59`) plante quand même avec
`NoClassDefFoundError: .../VertexSerializer` — pas un crash franc, un blocage indéfini sur une boîte
de dialogue Swing FML jamais cliquable en lanceur automatisé (le vrai message n'était visible que dans
un heap dump `jmap`, jamais loggé). **Cause confirmée** : un `if` runtime ne protège pas de la
résolution de classe faite par la JVM au chargement de la méthode contenant l'instruction `NEW`, même
si la branche n'est jamais exécutée. Corrigé en isolant les 4 `registerSerializer(...)` dans une
nouvelle classe `net.irisshaders.iris.vertices.sodium.SodiumVertexSerializers` (jamais chargée sans
appel explicite), `Iris.java` ne référence plus aucun type Sodium directement — même motif que
`CadenceBlueMap`. Commit `a9647c2`. Compilation propre, rebuild, reteste sur le même lanceur isolé
(Sodium toujours absent) : **ce crash a disparu**, le jeu va bien plus loin (jusqu'à "Initializing
game" au lieu de planter avant le scan des mods).

**Crash #2 — trouvé, PAS corrigé, vrai couplage architectural (pas une isolation simple cette fois)** :
`WorldRenderingSettings` (`net.irisshaders.iris.shaderpack.materialmap`) déclare un champ
`private ChunkVertexType chunkVertexFormat;` où le TYPE DÉCLARÉ lui-même est une interface Sodium,
initialisé sans garde dans un singleton statique construit sans condition dès `Iris.onEarlyInitialize`
(appelé à chaque `Minecraft.<init>`, donc absolument inconditionnel). Contrairement au crash #1, un
descripteur de champ est résolu au chargement/liaison de la classe elle-même — aucune isolation
d'appel ne peut contourner ça, il faut changer le TYPE du champ. C'est exactement le couplage dur déjà
scopé comme « la partie la plus critique et la plus difficile à remplacer » dans
`RECHERCHE-VULKAN-SANS-SODIUM.md`. **Pas tenté** — nécessite d'auditer tous les appelants de
`getVertexFormat()`/`setVertexFormat()` avant de choisir un correctif sûr (remplacer par un type
interne Lanterne, ou typer en `Object` avec cast gardé) ; risqué à improviser en fin de session sans
cet audit. Détail complet + pistes de correctif dans `RECHERCHE-VULKAN-SANS-SODIUM.md`, nouvelle
section en fin de fichier.

**Sodium remis dans `mods/` de la copie isolée après le test.** Aucun process résiduel (vérifié
`tasklist`, chaque process de test arrêté individuellement par PID, jamais de kill global). Rien
déployé sur le VPS ni `test 1` pour ce sujet — seul le commit `a9647c2` (progrès réel, sans danger que
Sodium soit présent ou pas) est sur la branche `26.3`, jamais poussé.

**Prochaine étape réelle qui reste ouverte** : auditer `getVertexFormat()`/`setVertexFormat()`, choisir
et appliquer le correctif de `WorldRenderingSettings`, puis relancer ce même test pour voir si un 3e
crash apparaît (probable sur `vertices/sodium/terrain/`, même nature de couplage par type de champ).

## AUDIT — appelants de `chunkVertexFormat`, verdict : correctif trop risqué pour cette nuit (fork, 21/09 ~02h50)

Reprise de la « prochaine étape » ci-dessus (audit demandé par le patron pour le crash #2 de la tâche
précédente). **Aucun code modifié — audit seul, verdict honnête que le correctif n'est pas sûr à
improviser cette nuit.** Détail complet dans `RECHERCHE-VULKAN-SANS-SODIUM.md`, nouvelle section en fin
de fichier. Résumé :

- Le champ n'est PAS juste « stocké/transmis » : `ShaderAttributeInputs.java:36` et 4 sites de
  `ShaderCreator.java` (lignes 72/254/292/327) font un appel chaîné `.getVertexFormat().getVertexFormat()`
  pour construire le VRAI format GL utilisé par chaque programme shader — usage d'encodage réel, sur le
  chemin chaud du rendu shader confirmé stable cette nuit (Complementary Reimagined, Sodium présent).
- Aggravation trouvée : `VanillaRenderingPipeline.java:34` (le pipeline de REPLI, pas le pipeline
  shader) appelle lui aussi `ChunkMeshFormats.COMPACT` sans aucune garde — donc même un garde limité au
  seul constructeur de `WorldRenderingSettings` ne suffirait pas, il faudrait aussi garder ce site.
- 5 fichiers au total à toucher pour un correctif propre (interface neutre côté Lanterne avec 2
  implémentations, Sodium présent vs absent), dont 2 sur le chemin chaud de construction des shaders
  réellement utilisés en jeu — risque de régression directe sur le rendu shader déjà validé par le
  patron s'il est mal fait ou mal testé en fin de nuit sans pouvoir retester en jeu ensuite.

**Décision** : ne pas toucher au code. Piste de reprise précise documentée dans
`RECHERCHE-VULKAN-SANS-SODIUM.md` pour une reprise de jour avec plus de marge de test (en jeu, Sodium
présent + shaderpack actif, ET Sodium absent, pas juste au menu principal). Rien à compiler, rien
déployé, aucun process de test lancé pour cette tâche (audit de lecture seule).

## TÂCHE — onglet « Iris » dans Sodium RESTAURÉ, corrigé, testé, preuve réelle (fork, 21/09 ~03h05)

Reprise de la piste documentée dans TÂCHE B (« fusion des deux `registerConfigLate` jamais faite, à
valider avec le patron ») et dans VÉRIFICATION artefact ciel (« cet onglet ne réapparaîtra PAS tant
que la fusion n'est pas faite »). **Ces deux notes étaient trop pessimistes** — pas besoin de fusion
ni de décision visible à trancher : l'API Sodium le permet nativement, sans toucher à la page
Lanterne existante.

**Découverte clé (javap sur le vrai `ConfigBuilderImpl.class` de `sodium-fabric-0.9.2+mc26.3.jar`,
extrait dans `sodium-fabric-...-mc26.3.jar`, PAS `sodium-neoforge` qui ne contient pas ce code — le
module neoforge n'est qu'un mince habillage)** : `ConfigBuilder` expose DEUX méthodes distinctes,
pas juste `registerOwnModOptions()` :
- `registerOwnModOptions()` → en bytecode, appelle juste `registerModOptions(defaultConfigId)`
  où `defaultConfigId` est le modId qui a déclaré `sodium:config_api_user` (donc "lanterne" ici).
- `registerModOptions(String modId)` → résout `ModMetadata` (nom, version) pour CE modId via
  `modInfoFunction` (backé par `ModList`/FML), crée un `ModOptionsBuilderImpl` **indépendant** et
  l'ajoute à `pendingModConfigBuilders` (une simple `List`, pas une `Map` — pas de fusion ni
  d'écrasement possible par modId, vérifié en bytecode sur `ConfigBuilderImpl.build()`).

Donc : appeler `builder.registerModOptions("iris")` depuis Graft — maintenant possible car TÂCHE B a
donné à "iris" un vrai modId virtuel dans `neoforge.mods.toml` — crée un DEUXIÈME onglet totalement
indépendant du premier, sans aucun risque pour la page « Lanterne » déjà en place et déjà utilisée
par les joueurs. Le crainte documentée cette nuit (« le second `.setName("Iris")` écraserait le nom
posé par Graft ») était infondée : chaque appel produit un objet à part, il n'y a rien à écraser.

**Correctif appliqué** (aucun fichier `mixins.iris*`/`net.irisshaders.iris.mixin` touché) :
- `net/irisshaders/iris/compat/sodium/config/IrisConfig.java` : `registerConfigLate(ConfigBuilder)`
  extrait dans une méthode `public static void populate(ConfigBuilder, ModOptionsBuilder)` réutilisable
  (le contenu — pages, overlays — est identique, juste découplé de la création du `ModOptionsBuilder`).
  Les deux méthodes privées `createSettingsPage`/`createColorSpaceOption`/`createShadowDistanceOption`
  passées en `static` (nécessaire pour être appelées depuis la nouvelle méthode statique).
- `fr/clubcitrouille/lanterne/client/sodium/Graft.java` : `registerConfigLate` appelle en plus
  `registerIrisTab(builder)` (nouvelle méthode privée) qui fait
  `IrisConfig.populate(builder, builder.registerModOptions("iris"))`, enveloppé en
  `catch (Throwable)` avec un simple `LOG.warn(...)` — si jamais le modId "iris" n'est pas résolu
  (mod list incohérente dans un futur build), le joueur perd seulement l'onglet Iris, jamais tout
  l'écran Sodium.

**Preuve réelle, testée dans un vrai client jetable (`C:\lanterne-client-sodium-iris-tab\`, copie
isolée, `mods/` = `lanterne-26.3.15.jar` (rebuild local, pas de bump de version pour ce test isolé,
jamais déployé) + `sodium-neoforge-0.9.2+mc26.3.jar`)**, run complet jusqu'au menu principal :
- `grep -ic fatal` → **0** sur `latest.log` ET `debug.log`.
- Aucune ligne `[LANTERNE][SODIUM] Onglet Iris indisponible...` dans les logs — le `catch (Throwable)`
  ne s'est jamais déclenché, donc `builder.registerModOptions("iris")` a réussi silencieusement.
- `Reloading ResourceManager: ... mod/lanterne,iris ...` confirmé (FML voit toujours les 2 modId,
  cohérent avec TÂCHE B).
- Trouvaille annexe rassurante : `mixins.iris.compat.sodium.json` mixe bien une classe
  `MixinSodiumOptions` dans `SodiumConfigBuilder`, mais son corps est VIDE (juste des imports
  inutilisés) — un no-op qui ne peut pas entrer en conflit avec ce correctif.
- **Ce qui reste NON vérifiable sans écran interactif** : que l'onglet visuel « Iris » apparaît
  réellement dans la colonne de gauche du menu vidéo Sodium à côté de « Lanterne » — la preuve
  ci-dessus est mécanique (aucune exception, le bon objet est bien enregistré auprès de Sodium),
  mais pas une capture d'écran. Fortement probable vu la preuve bytecode complète, pas 100% comme
  pour l'artefact du ciel (qui n'a, lui, pas eu de code à écrire — juste une conséquence de TÂCHE B).

Process de test arrêté proprement (PID 32140 confirmé via `Get-CimInstance ... CommandLine` avant kill
individuel, jamais de kill global `java.exe`). Compilation vérifiée propre à chaque étape
(`./gradlew compileJava`). **Rien déployé sur le VPS ni `test 1`** — laissé au patron/coordinateur au
réveil, comme les autres correctifs de cette nuit touchant l'UI. Commit sur `26.3`, jamais poussé.

**Prochaine étape réelle qui reste ouverte** : confirmation visuelle humaine (capture d'écran ou le
patron en jeu) que l'onglet apparaît bien et fonctionne (clic → ouvre le sélecteur de shaderpack).

## BUG HALO/REDRAW CHUNKS — cause réelle trouvée par preuve directe (rapport Radiographie), PAS `Guet` (fork, 21/09 ~03h)

Reprise du bug signalé le 20/09/26 (« halo » sur certains chunks à l'exploration + chute FPS
250→150-180, voir mémoire `bug-halo-redraw-chunks.md`). La piste initiale pointait `Guet.java` — **écartée
avec certitude, par lecture complète du fichier** : `Guet.pulse()` ne fait QUE lire
`LevelRenderer.visibleSections()` (API publique du moteur), aucun mixin, aucune écriture, désactivé par
défaut (`ClientConfig.GUET`). Par construction, ce module ne peut rien produire visuellement — la
mémoire notait déjà une mise à jour du 20/09 allant dans ce sens (pointant plutôt vers
`SectionRenderDispatcher`/`GpuBufferPool.tryRecycleBuffers`), confirmée ici.

**Cause réelle confirmée par preuve directe**, trouvée dans un vrai rapport déjà généré par le patron
et jamais dépouillé jusqu'ici : `test 1/config/lanterne/profil-2026-09-21_01-23-51.txt` (rendu OpenGL,
Sodium+DH, vue 12/12 chunks, avant le fix modId iris de cette nuit). Un creux de FPS réel à t+6s (8.3
FPS) a sa pile n°1 (7/64 échantillons) qui remonte, sans ambiguïté, à :

```
BufferArena.tryUpload → BufferArena.tryUploads → BufferArena.upload
→ RegionAllocatorHandle.upload → RenderRegionManager.uploadResults
→ RenderSectionManager.processChunkBuildResults → processChunkBuilds
→ SodiumWorldRenderer.setupTerrain → LevelExtractor.extract (par image, fil de rendu)
```
qui descend jusqu'à `MojangStagingBuffer.enqueueCopy` → `glNamedBufferSubData` (upload GPU synchrone).

**Diagnostic** : c'est `net.caffeinemc.mods.sodium.client.gpu.arena.BufferArena` (le pool de mémoire
GPU de Sodium pour les maillages de chunk compilés) qui, quand PLUSIEURS sections finissent de compiler
la même image (typiquement après un déplacement rapide/vol qui charge beaucoup de nouveaux chunks d'un
coup), uploade tout en une seule fois, de façon SYNCHRONE sur le fil de rendu, dans
`SodiumWorldRenderer.setupTerrain` — d'où la sensation visuelle de « halo »/redraw en masse (beaucoup de
sections de chunk apparaissent/changent d'un coup) ET la chute de FPS simultanée (le fil de rendu
attend la fin de l'upload GPU avant de continuer).

**Ce n'est PAS un bug introduit par Lanterne ni par la migration Iris** : `BufferArena`/
`RenderRegionManager` sont du code Sodium pur, jamais touché ni migré cette nuit — c'est un comportement
architectural inhérent à Sodium (pas de lissage/étalement des uploads sur plusieurs images, contrairement
au build des maillages qui lui est bien multi-threadé). Confirmé absent de toute piste Iris/Lanterne :
aucune classe `net.irisshaders`/`fr.clubcitrouille` dans cette pile.

**PAS corrigé cette nuit** — un correctif (étaler les uploads `BufferArena` sur plusieurs images au lieu
de tout faire en une fois, via un mixin sur `RenderRegionManager.uploadResults`/`BufferArena.upload`)
est risqué à improviser sans pouvoir retester en jeu avec une vraie charge (vol rapide, beaucoup de
chunks à charger d'un coup) — exactement le genre de correctif qui a besoin d'un test client HUMAIN en
conditions réelles, pas juste un menu principal atteint via le lanceur jetable. Piste de reprise précise
pour une future passe : mixin `@Inject` ou `@Redirect` sur `BufferArena.tryUploads`/
`RenderRegionManager.uploadResults` pour plafonner le nombre d'octets/sections uploadés par image
(budget de temps, comme `Radiographie` le fait déjà pour d'autres compteurs), le reste reporté à l'image
suivante — nécessite de vérifier que Sodium n'a pas déjà un tel budget ailleurs (pas trouvé dans cette
passe, à vérifier plus profondément) et que retarder un upload ne casse pas l'affichage (un chunk
"prêt" mais pas encore uploadé doit rester invisible, pas afficher un maillage vide/corrompu).

Aucun fichier modifié, aucune compilation nécessaire (diagnostic seul, par lecture d'un rapport déjà
généré + lecture de code). Mémoire `bug-halo-redraw-chunks.md` mise à jour avec cette conclusion.
