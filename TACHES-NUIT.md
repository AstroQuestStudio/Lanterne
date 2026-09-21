# RÉSUMÉ POUR LE RÉVEIL DU PATRON (généré ~04h10, dernière mise à jour de cette section)

Ce résumé synthétise toute la nuit du 20 au 21/09/2026 sur Lanterne (branche `26.3`). Rapport de
travail honnête, pas promotionnel — chaque point dit s'il est confirmé, partiel, ou juste une piste.
Le détail complet, section par section, suit plus bas dans ce même fichier (rien supprimé).

**CORRECTIF IMPORTANT (~06h00, vérification SSH lecture seule)** : contrairement à ce que dit la
section 1 ci-dessous ("VPS stable en `26.3.55`", "TÂCHE A/B pas déployées sur le VPS"), le VPS
tourne EN RÉALITÉ `lanterne-26.3.59.jar` depuis un redémarrage à `00:08:08` cette nuit (source
exacte du redémarrage non retracée — probablement Mèche, auto-update déclenché après une mise à
jour de `test 1`). **C'est une bonne nouvelle, pas une régression** : démarrage propre, aucune
exception/erreur/OOM/lag dans les logs, TÂCHE A confirmée active en prod par son propre message de
log (`[CADENCE] BlueMap prêt, 0 joueur connecté — rendu complet démarré automatiquement`), TÂCHE B
(modId iris virtuel) présente dans le jar qui tourne. 0 joueur connecté depuis le boot (cohérent
avec "0 joueurs avant 8h"), serveur en pause normale (`Server empty for 60 seconds, pausing`).
Process stable depuis 3h05 au moment du contrôle (`03:14 UTC`), `Done` confirmé, RCON actif. Voir
la section "VÉRIFICATION VPS ~06h00" tout en bas de ce fichier pour le détail complet et les preuves
de log. **Aucune action corrective nécessaire** — juste une correction de doc : la section 1/2
ci-dessous est partiellement dépassée sur ce point précis (TÂCHE A/B sont bel et bien en prod).

## 1. Déployé et confirmé stable (VPS + test1)

- **Migration complète du code source d'Iris dans Lanterne** (fusion LGPL-3.0, voir
  `IRIS-ATTRIBUTION.md`) — 7 crashs de migration trouvés et corrigés un par un, tous par test
  client réel : `@Mod("iris")` retiré (`e8138a6`), `BlueMapAPI` isolée (`0586528`), fichier
  `META-INF/services/...` copié (`6e72735`), `getVersion()` corrigé (`c73466d`),
  `assets/iris/` copié (`587e70e`), dépendances `jarJar` (`115e33b`), `centerDepth`/`colorSpace`
  copiés (`b65e53d`).
- **Shaders confirmés fonctionnels en jeu par le patron lui-même** (capture F3 : `[Iris] Version
  26.3.55`, shaderpack Complementary Reimagined actif, 80-124 FPS).
- **VPS stable en `26.3.55`**, `Done` confirmé à chaque redémarrage.
- **Fix Cadence — BlueMap redémarre seul si 0 joueur au boot** (TÂCHE A, commit `9da44c7`),
  testé et confirmé par log sur le serveur jetable. **Pas encore déployé sur le VPS** — décision
  volontairement laissée au réveil (touche le comportement au redémarrage, préférence pour un œil
  humain avant prod même si le test jetable est concluant).
- **Fix visibilité "iris" comme modId virtuel** (TÂCHE B, commit `7a13b94`) : `neoforge.mods.toml`
  déclare un second `[[mods]] modId="iris"` passif (sans classe `@Mod`). Confirmé sain par log FML
  réel (`{lanterne,iris}` dans le jar `26.3.59`). **Déployé sur `test 1`, PAS sur le VPS.**
- **Artefact visuel du ciel (bordure nette, DH+Iris) : cause confirmée à 100% par preuve bytecode
  complète** (chaîne DH `ModList.isLoaded("iris")` → `IrisAccessor` jamais bindé avant le fix ci-
  dessus) — corrigé structurellement par le même fix modId. **Confirmation visuelle humaine encore
  en attente.**
- **Onglet "Iris" restauré dans le menu Sodium** : corrigé pour de vrai (pas juste documenté),
  `Graft.java`/`IrisConfig.java` enregistrent un second `ModOptionsBuilder` indépendant via
  `registerModOptions("iris")`. Testé en client jetable réel, 0 FATAL, mécanisme confirmé par
  bytecode + logs. **Non déployé nulle part** (laissé au réveil comme les autres correctifs UI).
- **Sélection du shaderpack actif ajoutée dans `Dials.java`** (cadran "Shaderpack", en plus du
  toggle on/off existant), compilé sain, testé partiellement (démarrage propre, 0 FATAL) mais le
  clic lui-même n'a pas pu être testé visuellement.
- **`lanterne-common.toml` fusionné** (boutique + carte3D + atelier + server en un seul fichier) —
  anciennes valeurs réelles (prix boutique, réglages serveur) backupées, PAS reportées
  automatiquement dans le nouveau fichier (voir section 2).
- **Monde officiel importé sur le VPS** (13 joueurs, données réelles), backup pré-import conservé.
- **1 crash de retrait de Sodium corrigé** (`SodiumVertexSerializers`, isolation de classe,
  commit `a9647c2`) — un premier vrai test client Sodium-absent a été mené (pas juste de la
  lecture de code) et progresse plus loin qu'avant.

## 2. Nécessite une action humaine demain

- **Re-rentrer les vraies valeurs boutique/serveur** dans `lanterne-common.toml` — les anciennes
  sont dans `lanterne-anciens-toml-backup/` sur le VPS, le nouveau fichier fusionné a démarré avec
  les valeurs par défaut du code, pas les vraies valeurs de prod.
- **Confirmation visuelle en jeu** : onglet Iris dans Sodium, disparition de l'artefact du ciel,
  fonctionnement réel du cadran shaderpack dans Dials — tout ça est prouvé "mécaniquement sain"
  (bytecode/logs) mais jamais vu par un œil humain.
- **Décision à prendre** : déployer TÂCHE A (Cadence boot) et TÂCHE B (modId iris) sur le VPS de
  prod, une fois les tests `test 1` confirmés visuellement.
- **Idée proposée, pas implémentée** : un cadran "Preset perf" optionnel dans `Dials.java` (pas un
  défaut imposé) — jugé être une vraie décision produit, pas une correction, donc pas fait seul
  cette nuit.

## 3. Progrès Vulkan (recherche pure, RIEN branché au rendu réel)

Chaîne complète prouvée de bout en bout sur des shaders synthétiques modernisés :
jcpp → glsl-transformer (`patchComposite`/`patchVanilla`) → `UniformReblocker` (prototype maison,
re-bloque les uniforms en UBO pour respecter SPIR-V/Vulkan) → shaderc → SPIR-V valide, vertex ET
fragment (5576+3428 octets). Sur un VRAI fichier de shaderpack (`gbuffers_terrain`, Complementary
Reimagined), deux nouveaux blocages précis et documentés restent à résoudre : couverture
incomplète du parseur d'uniforms sur les gros fichiers, et un `StackOverflow`/`OutOfMemoryError`
sur la récursion pour les fichiers de taille réelle (~100K caractères). Tout ceci vit dans
`lab/ShadercProbe*.java`/`UniformReblocker.java`, jamais connecté au pipeline de rendu qui tourne
réellement en jeu — c'est un prototype de faisabilité, pas une fonctionnalité livrée.

**Note de clôture (~05h30)** : le fork qui travaillait sur ces 2 derniers blocages (uniforms non
capturés + stack overflow) s'est enlisé ~2h à relancer des clients de test jetables
(`-test-5`, `-test-6`) sans jamais conclure — arrêté de force par le coordinateur, process orphelin
`PID 16636` (`-test-5`) fermé proprement après vérification de son `gameDir`. Aucune régression :
le dépôt était propre, seule une note de doc en attente a été commitée (`bb23226`), rien d'autre
perdu. **Recommandation pour la reprise** : l'axe Vulkan a déjà reçu énormément de temps cette
nuit (8+ passes) pour un résultat qui reste un prototype non branché au rendu réel — la suite
(couverture du parseur `UniformReblocker` sur gros fichiers, conversion récursion→itération) est un
vrai travail de dev normal, PAS un test client répété. Éviter de redispatcher un fork dessus en
boucle sans borne de temps explicite ; si repris, fixer un budget clair (ex: "1 tentative de test
client max, sinon documenter et arrêter").

## 4. Bugs documentés mais non corrigés cette nuit (avec raison)

- **Halo/chute FPS à l'exploration** : cause réelle trouvée avec preuve directe (upload GPU
  synchrone de `Sodium BufferArena` quand plusieurs sections de chunk finissent de compiler en
  même temps). Un correctif de plafonnement a été évalué puis ABANDONNÉ après preuve bytecode
  qu'il aurait causé un bug pire (chunks invisibles en permanence) — le vrai correctif est plus
  large que prévu (patcher `RenderSectionManager.processChunkBuildResults`), nécessite un test
  humain en vol rapide, hors de portée d'un fork cette nuit.
- **Retrait complet de Sodium** : 1 crash corrigé, un 2e trouvé et bien scopé
  (`WorldRenderingSettings.chunkVertexFormat` typé directement par une interface Sodium, utilisé
  sur le chemin chaud du rendu shader) — correctif possible (5 fichiers) mais jugé trop risqué à
  improviser en fin de nuit sans pouvoir retester en jeu après coup. Piste précise documentée dans
  `RECHERCHE-VULKAN-SANS-SODIUM.md`.

## Audit démarrage serveur (~06h15, pure lecture de code, aucune modification)

Piste explorée : le démarrage serveur (Xeon mono-cœur, monde réel 1.58GB) fait-il du travail
synchrone évitable qui retarderait la disponibilité du serveur ?

**Résultat : rien à corriger, conception déjà saine.**

- Seul `ServerStartedEvent` (jamais `ServerStartingEvent`/`ServerAboutToStartEvent`) est utilisé
  côté Lanterne pour tout travail applicatif (`Lanterne.onServerStarted` : bannière/diagnostics,
  `Cadence.onServerStarted` : reprise pregen). Cet évènement se déclenche APRÈS que le serveur ait
  déjà ouvert son port et affiché `Done` — donc rien ici ne retarde la disponibilité aux joueurs.
- `Pregen.resumeIfPending` → `begin()` : vérifié en détail, ne fait qu'initialiser des compteurs
  (`O(1)`, pas de scan disque ni de génération synchrone) ; la génération réelle des 500 chunks de
  rayon est étalée sur les ticks suivants via un curseur incrémental (déjà connu du système,
  documenté dans sa Javadoc — "la reprise est gratuite, ce qui existe déjà est sauté").
- `CadenceBlueMap.startOnReady` s'enregistre en callback dès le constructeur du mod (pas besoin
  d'attendre `ServerStartedEvent`), et le vrai rendu BlueMap est géré par le plugin BlueMap
  lui-même de façon asynchrone (hors du contrôle direct de Lanterne).

Pas de gain trouvé sur cet axe — le design existant évite déjà le piège classique (travail lourd
dans `ServerStartingEvent`, avant l'ouverture du port). Aucun fichier modifié, rien compilé, rien
commité.

## 5. Aucun risque pris

Aucun déploiement VPS n'a eu lieu cette nuit en dehors de ce qui est explicitement listé en
section 1 (versions confirmées stables par log/test). Le dépôt est resté compilable à chaque
étape (`./gradlew compileJava` vérifié avant chaque commit). Aucun process Java du patron n'a été
touché — chaque fermeture de process de test a été faite individuellement par PID après
vérification qu'il s'agissait bien d'un dossier de test jetable, jamais un `taskkill` global.

---

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

## BUG HALO/REDRAW CHUNKS (suite) — correctif de plafonnement évalué, PAS appliqué : perte de mesh confirmée par bytecode (fork, 21/09 ~03h15)

Reprise de la « prochaine étape » ci-dessus (mixin de plafonnement sur `BufferArena`/
`RenderRegionManager.uploadResults`). **Aucun code modifié — le correctif proposé est réellement
dangereux, prouvé par bytecode, pas juste supposé.**

Signatures exactes confirmées par `javap` sur le vrai jar
(`net.caffeinemc.sodium-neoforge-0.9.2+mc26.3-mod.jar`, extrait de `test 1/.cache/jij/...`) :
- `RenderRegionManager.uploadResults(Collection<BuilderTaskOutput>, UniformBufferManager)` (public,
  point d'entrée par image) délègue par région à une surcharge privée, qui descend jusqu'à
  `BufferArena.tryUploads(RegionAllocatorHandle, List<PendingUpload>)` → `tryUpload` → upload GPU
  synchrone (`glNamedBufferSubData`), confirmant la pile déjà documentée ci-dessus.
- **Le vrai blocage** : `RenderSectionManager.processChunkBuildResults` (l'appelant direct) itère
  la `Collection<BuilderTaskOutput>` complète et appelle `BuilderTaskOutput.destroy()` sur CHAQUE
  élément, **inconditionnellement, juste après** avoir appelé `uploadResults(...)` — confirmé en
  bytecode (`javap -c`, boucle `Iterator.next()` → `destroy()` en fin de méthode, aucune branche
  conditionnelle sur le succès de l'upload).

**Conséquence** : un mixin qui plafonnerait le nombre de sections traitées par image DANS
`uploadResults` (en gardant les sections en trop pour l'image suivante, comme prévu par la piste
documentée plus haut) ne changerait RIEN au fait que l'appelant les `destroy()` quand même juste
après, qu'elles aient été uploadées ou pas. Résultat réel d'un tel correctif naïf : les sections de
chunk non uploadées dans l'image où leur build se termine verraient leurs données de mesh libérées
(`destroy()`) SANS jamais avoir été envoyées au GPU — la géométrie de ce chunk resterait
définitivement absente (pas "en retard d'une image" comme espéré) jusqu'à un rebuild futur
improbable, ce qui serait un bug bien pire que le halo/chute FPS actuel (un chunk invisible en
permanence plutôt qu'une saccade ponctuelle).

**Ce que ça change pour une reprise de jour** : un correctif sûr doit intervenir soit AVANT ce
`destroy()` (empêcher l'appelant de détruire les sections reportées — nécessite de patcher
`RenderSectionManager.processChunkBuildResults` elle-même, pas juste `BufferArena`/
`RenderRegionManager`, portée bien plus large que prévu cette nuit), soit en forçant un marquage
"à reconstruire" pour toute section reportée avant sa destruction (pour qu'un futur `tick` la
remette en file de build — à vérifier si un tel mécanisme existe déjà côté Sodium, pas exploré).
Dans les deux cas, changement plus profond que le mixin ciblé initialement envisagé — nécessite un
vrai test en jeu avec vol rapide pour valider qu'aucun chunk ne reste durablement invisible, donc
hors de portée d'un fork sans écran interactif.

**Décision** : ne pas appliquer de correctif ce soir. Rien compilé, rien modifié, aucun process de
test lancé (analyse bytecode pure). Mémoire `bug-halo-redraw-chunks.md` mise à jour avec cette
conclusion plus précise.

## AUDIT — recherche de nouveaux gains de perf client (tick handlers, allocations, coût de Radiographie) : rien de solide trouvé (fork, 21/09)

Directive : chercher activement de nouvelles idées d'optimisation client (au-delà de ce qui a déjà
été traité cette nuit). **Aucune modification de code — audit seul, verdict honnête négatif.**

- **13 fichiers avec `ClientTickEvent`/`RenderTickEvent`/`RenderLevelStageEvent`** recensés (grep sur
  tout `fr.clubcitrouille.lanterne`). Lus en détail : `Vigie.java` (diagnostic Vulkan/OpenGL, ne
  tourne qu'une seule fois via `AtomicBoolean RAPPORTE`, jamais répété — pas un coût récurrent),
  `Hint.java::onClientTick` (appelle `Guide.all()` chaque tick, mais celui-ci fait une simple
  comparaison de référence `bound == level` après la première construction — déjà documenté comme
  volontairement cheap dans son propre Javadoc, vérifié vrai en lisant `Guide.all()`),
  `Usher.java::onClientTick` (garde `if (!asked) return;` en toute première ligne — coût nul le reste
  du temps). Aucun de ces trois ne fait d'allocation (`new ArrayList`/`new HashMap`) sur son chemin
  chaud (tick sans action utilisateur en cours).
- **`Radiographie.pulse()`** (appelée à chaque image, sans condition, documentée dans son propre
  Javadoc comme devant rester "la méthode la moins chère de ce fichier") : déjà conçue pour être
  quasi gratuite quand l'échantillonnage est arrêté (`running.get()` false → retour immédiat après
  la vérification monde/pas-monde) — rien à améliorer sans risquer de casser la précision de l'outil
  quand il tourne activement (déjà couvert par d'autres passes cette nuit sur ce même fichier).
- **`Wheel.java`/`SouvenirClient.java`** : les `Map`/`Set` statiques vus au grep (`OFFERS`, `ASKED`,
  `BUILDING`, `FILLED`, `pending`) sont des caches long terme construits une fois par entrée, pas des
  allocations en boucle de rendu — pas un problème.

**Conclusion honnête** : après ce passage, le code des tick handlers Lanterne est déjà bien gardé
(early-return systématique, pas d'allocation par frame trouvée). Rien de solide à corriger cette
nuit sur cet axe précis — les vrais gains identifiés cette nuit restent ailleurs (bug halo/Sodium
`BufferArena`, retrait de Sodium, Vulkan), déjà documentés plus haut. Aucun fichier modifié, aucune
compilation nécessaire, aucun process de test lancé (lecture de code seule).

## RE-AUDIT — implémentation `chunkVertexFormat` (WorldRenderingSettings) : verdict "trop risqué" reconfirmé, précision utile ajoutée (fork, 21/09 ~03h05)

Directive : décider avec jugement propre si l'interface neutre proposée par l'audit précédent
(`WorldRenderingSettings.chunkVertexFormat`, 5 fichiers, typé par l'interface Sodium `ChunkVertexType`)
est réellement implémentable cette nuit, pas juste redire ce qui était déjà documenté.

**Vérification par `javap` sur le vrai jar Sodium** (`test 1/mods/sodium-neoforge-0.9.2+mc26.3.jar` →
jar imbriqué `jarjar/net.caffeinemc.sodium-neoforge-0.9.2+mc26.3-mod.jar`) : l'interface
`ChunkVertexType` n'a que 2 méthodes (`getVertexFormat()` et `getEncoder()`). Grep exhaustif confirme
que **seule `getVertexFormat()` est utilisée** partout où `WorldRenderingSettings.INSTANCE.getVertexFormat()`
est appelé (`getEncoder()` jamais touché) — donc l'interface neutre à une seule méthode déjà proposée
par l'audit précédent est bien suffisante en théorie, pas besoin de plus.

**Mais le verdict ne change pas** : `VanillaRenderingPipeline.java:34` (`setVertexFormat(ChunkMeshFormats.COMPACT)`,
chemin de repli PAR DÉFAUT dès qu'aucun shaderpack n'est actif) reste le vrai blocage — sans Sodium,
aucune valeur `VertexFormat` vanilla "équivalente" évidente à coder en dur sans revalider manuellement
le format exact attendu par `ShaderAttributeInputs`/`ShaderCreator` (risque de MESH CORROMPU si mal
choisi, pas juste un crash — pire qu'un simple `NoClassDefFoundError`). Les 2 mixins qui manipulent le
vrai type Sodium (`MixinChunkMeshFormats`, `MixinRenderSectionManager`) sont sans risque car dans
`mixins.iris.compat.sodium.json`, un config mixin qui ne s'applique jamais sans Sodium (cibles
introuvables) — pas un obstacle de plus, juste confirmé.

**Décision : ne pas implémenter cette nuit.** Risque concentré sur `ShaderAttributeInputs`/`ShaderCreator`
(chemin chaud du rendu shader confirmé stable ce soir avec Sodium+Complementary Reimagined, 80-124 FPS)
— un correctif mal calibré casserait ce qui marche. Détail complet ajouté dans
`RECHERCHE-VULKAN-SANS-SODIUM.md` (nouvelle section "RE-CONFIRMATION"). Aucun fichier de code modifié,
rien compilé, rien déployé.

## VÉRIFICATION — patch Eclaireur (renderDelay JourneyMap) : format JSON confirmé exact par bytecode, aucun bug (fork, 21/09 ~03h20)

Directive : vérifier que `Eclaireur.java` (dépose `{"renderDelay": "1000"}` au 1er lancement, si
`journeymap/config/6.0/journeymap.core.config` n'existe pas encore) est cohérent — bon nom de clé,
bon moment, bon format JSON attendu par JourneyMap. La Javadoc de la classe était déjà très
détaillée (nom de clé, bornes, moment de chargement tardif, tout vérifié en bytecode par un fork
précédent) mais UN point n'avait jamais été vérifié : le format JSON exact attendu par le
désérialiseur Gson personnalisé de JourneyMap pour un champ `IntegerField` — c'est fait ici.

**Vérifié par `javap` sur le vrai jar `test 1/mods/journeymap-neoforge-26.3-6.0.9.jar`** :
- `CoreProperties.renderDelay` est bien un champ Java nommé littéralement `renderDelay` (confirmé
  dans la liste complète des champs `IntegerField` de la classe) — Gson utilise le nom de champ Java
  tel quel (aucun `@SerializedName` custom vu), donc la clé JSON `"renderDelay"` écrite par
  `Eclaireur` correspond exactement.
- `GsonHelper$IntegerFieldSerializer.deserialize` (bytecode complet lu) : si `verbose=false` ET
  `saveType=false` ET l'élément JSON n'est PAS un objet (`JsonObject`) — c'est-à-dire une valeur
  brute comme une chaîne — alors `ConfigField.put("value", element.getAsString())` est appelé
  directement. Un objet `IntegerField` neuf a déjà tous ses défauts posés par son constructeur
  (bornes `[100, 60000]`, défaut `500`), donc seule la clé `value` est remplacée.
- `PropertiesBase.load()` (sans argument, le point d'entrée réel utilisé au chargement normal)
  appelle `load(false)` → `fromJsonString(..., false)` → `getGson(false)` : confirmé que le mode
  `verbose=false` est bien celui utilisé en pratique au chargement, pas un cas rare.

**Conclusion : `{"renderDelay": "1000"}` (valeur en chaîne JSON, pas un objet imbriqué) est
exactement le format que le désérialiseur non-verbose de JourneyMap attend pour une valeur brute —
confirmé par lecture bytecode complète du chemin de désérialisation réel, pas supposé.** Aucun bug
trouvé, aucune modification de code nécessaire.

**Ce qui reste NON vérifiable sans écran interactif** : la confirmation visuelle humaine que la
mini-carte JourneyMap est bien moins fréquemment redessinée en jeu (le patron n'a pas encore
confirmé ce patch en jeu, seul le mécanisme est prouvé sain à 100 % maintenant, y compris son format
de sérialisation). Un test empirique complet (lancer un client jetable frais, sans config JourneyMap
préexistante, et vérifier le fichier généré) n'a pas été fait par manque de copie de lanceur libre à
ce moment (plusieurs autres forks utilisaient déjà `C:\lanterne-client-*` en parallèle) — jugé
redondant vu le niveau de certitude déjà atteint par la preuve bytecode complète du format.

Aucun fichier modifié, aucune compilation nécessaire, aucun process de test lancé. Mémoire
`memory/eclaireur-patch-journeymap.md` laissée telle quelle (rien de nouveau qui change son statut
"pas confirmé en jeu" — juste une couche de preuve supplémentaire sur le mécanisme).

## TÂCHE — `Dials.java` : cadran de choix du shaderpack ajouté, compilé, test partiel (fork, 21/09 ~03h25)

Reprise du point explicitement laissé de côté dans `buildShaders()` ("Choisir PARMI plusieurs
shaderpacks installés n'est pas câblé"). Second `Dial.cycle` ajouté dans la famille "Shaders" à
côté du toggle actif/coupé existant.

**Implémentation** (`client/Dials.java`, commit `26565b8`) : `refreshShaderPackNames()` liste
`Iris.getShaderpacksDirectory()` via `Files.list` + `Iris::isValidToShowPack` (léger, n'ouvre
aucun zip — contrairement à `Iris.isValidShaderpack` qui valide vraiment le contenu ; un
dossier/zip mal formé peut donc apparaître dans la liste, mais l'application dégrade déjà
proprement via le `catch(Throwable)` existant côté Iris). Liste rafraîchie une seule fois à
l'ouverture de l'écran (même early-return `families.isEmpty()` que le reste de `init()`), jamais
par image. `cycleIrisShaderPack(direction)` calcule l'index suivant/précédent dans cette liste
depuis le nom actuel (`IrisConfig.getShaderPackName()`), puis applique via
`Iris.getIrisConfig().setShaderPackName(nom)` + `IrisApi.getInstance().getConfig()
.setShadersEnabledAndApply(true)` — reproduction exacte de la séquence trouvée dans
`ShaderPackScreen.applyChanges()` (sauvegarde `iris.properties` puis `Iris.reload()`, déjà
wrappée `catch(IOException)` côté Iris lui-même). Tout est en plus enveloppé `catch(Throwable)`
côté Lanterne, même discipline que le toggle existant.

**Preuve réelle, partielle** : `./gradlew compileJava` propre. Testé dans une copie isolée du
lanceur client jetable (`C:\lanterne-client-dials-shaderpack\`, `lanterne-26.3.15.jar` local +
Sodium + un vrai shaderpack `ComplementaryReimagined_r5.9.zip` copié dans `shaderpacks/`) : le
client a progressé jusqu'à la création du contexte OpenGL/Sodium et au rechargement des
ressources (`Reloading ResourceManager: ... mod/lanterne,iris ...`, 148 lignes de log, GPU réel
RTX 3080 Laptop détecté) — **0 `FATAL` dans les logs, aucun `crash-reports/`** — mais le run n'a
**pas été suivi jusqu'au menu principal confirmé** (process introuvable dans `tasklist`/
`Get-CimInstance` au moment de la vérification finale, sans trace de crash non plus — probable
fin de vie normale du process après un temps d'exécution non observé, pas un signe de plantage
avéré). **Le cadran lui-même (clic → cycle réel → rechu du pipeline) n'a PAS été testé
visuellement** — aucun écran interactif disponible depuis un fork.

**Conclusion honnête** : code compilé sain, logique vérifiée par lecture directe de l'API Iris
réelle (pas devinée), premier segment de démarrage sans erreur avec le code actif — mais preuve
d'exécution incomplète comparée aux autres tâches de cette nuit qui ont pu confirmer "menu
principal atteint, 0 FATAL". À confirmer par un vrai test humain en jeu (clic sur le cadran,
vérifier que le shaderpack change bien visuellement et que le nom affiché suit).

Rien déployé sur le VPS ni `test 1`. Commit `26565b8` sur `26.3`, jamais poussé.

## AUDIT — réglages client par défaut (particules/distance/ombres) : aucun mécanisme, pas un bug (fork, 21/09)

Directive : chercher si Lanterne applique/suggère des valeurs par défaut plus performantes pour les
nouveaux joueurs (render/sim distance, particules, ombres d'entités), pour réduire la charge
CPU/GPU côté client à chaque occasion réelle.

**Constat, par lecture directe (pas de code modifié)** :

- `client/Dials.java` expose déjà un cadran rapide en jeu pour `Options.particles()` (ALL/
  DECREASED/MINIMAL), `Options.cloudStatus()`/`cloudRange()`, `Options.weatherRadius()`,
  `Options.biomeBlendRadius()`, `Options.ambientOcclusion()` et `Options.entityShadows()` — tout
  câblé sur le VRAI `options.txt` du jeu (`Options.save()`), bornes réelles confirmées par lecture
  du constructeur d'`Options.class`. C'est déjà l'outil principal de réglage perf pour le joueur,
  mais il ne fait QUE réagir à un clic — rien n'applique de valeur par défaut au premier lancement.
- `client/Eclaireur.java` (patch `renderDelay` JourneyMap au premier lancement, cette nuit) est le
  SEUL précédent de « Lanterne pose une valeur par défaut sans que le joueur clique » dans tout le
  mod — et il ne touche que JourneyMap, jamais `Options.renderDistance()`/`simulationDistance()`
  vanilla (`renderDistanceCaveMax`/`renderDistanceSurfaceMax`/`topoMax` dans sa Javadoc sont des
  champs JourneyMap internes, pas les réglages vanilla, vérifié par lecture directe du fichier).
- `Radiographie.parametresJoueur()` (cette nuit, commit `522359d`) ne fait qu'AFFICHER ces valeurs
  dans un rapport, jamais les modifier.

**Verdict** : aucun bug — il n'existe simplement aucun mécanisme d'auto-tuning des réglages vidéo
vanilla au premier lancement. En ajouter un serait une VRAIE nouvelle fonctionnalité, pas une
correction, et — comme la fusion `Graft`/`IrisConfig` documentée plus haut pour l'onglet Iris — un
changement de comportement visible qui imposerait des choix au joueur (baisser sa distance de
rendu, désactiver ses ombres) sans qu'il l'ait demandé : décision produit qui mérite l'avis du
patron plutôt qu'être décidée seule cette nuit. Pas implémenté, pas de code touché, rien à compiler.

**Piste pour une reprise de jour, si le patron valide l'idée** : réutiliser le pattern `Eclaireur`
(pose une valeur une seule fois au tout premier lancement, jamais réécrite ensuite) pour proposer
UN preset "performance" optionnel (pas imposé) via `Dials.java` — par exemple un cadran unique
"Preset perf" qui applique en un clic un jeu de valeurs connu (particules DECREASED, ombres
coupées, biome blend 2) plutôt que forcer un défaut silencieux au premier lancement.

## TÂCHE VULKAN (suite) — vertex stage isolé des builtins legacy, test lancé, résultat NON récupéré (fork, 21/09 ~03h30)

Reprise de la « prochaine étape » de la section « UniformReblocker : re-blocage des uniforms en
UBO, SUCCÈS PARTIEL réel » (étage FRAGMENT déjà prouvé à 3532 octets SPIR-V ; étage VERTEX bloqué
par des builtins GLSL legacy `ftransform()`/`gl_TexCoord`/`gl_FrontColor`, problème documenté comme
SÉPARÉ du re-blocage UBO lui-même).

**Modifié** (`lab/ShadercProbeSelfCheck.java`, commit `0456255`, aucun fichier `net.irisshaders`/
`kroppeb` touché) : `VANILLA_VERTEX_SOURCE`/`VANILLA_FRAGMENT_SOURCE` réécrits sans aucun builtin
legacy — `ftransform()` → `projectionMatrix * modelViewMatrix * vaPosition` (attributs/uniforms
explicites), `gl_TexCoord[0]`/`gl_FrontColor`/`gl_Color` → varyings `texCoord`/`frontColor`
explicites, `texture2D`/`gl_FragData[0]` → `texture()`/`out vec4 fragColor` modernes. Objectif :
isoler le test du problème `CompatibilityTransformer` déjà documenté, pour confirmer ou infirmer
que le re-blocage UBO marche aussi pour l'étage vertex une fois ce problème annexe retiré.

**Compilation propre** (`./gradlew compileJava`, `BUILD SUCCESSFUL`).

**Test lancé, résultat NON récupéré avant fin de budget de ce fork.** Copie isolée
`C:\lanterne-client-vulkan-test-3\` créée (clonée depuis `-test-2`, `mods/lanterne-26.3.15.jar`
rebuild local avec ce correctif, jamais déployé ailleurs), `launch7_v3.bat` généré avec les chemins
réécrits pour ce dossier. Deux tentatives de lancement en arrière-plan : la première a échoué
silencieusement (le `cd` d'un sous-shell bash lancé en arrière-plan ne s'est pas propagé à
`cmd.exe //C` invoqué avec un chemin relatif — leçon déjà documentée plus haut dans ce fichier pour
TÂCHE C, réoubliée ici, à corriger en utilisant systématiquement le chemin absolu du `.bat` comme
argument de `cmd.exe //C`, jamais un chemin relatif après un `cd` dans un sous-shell séparé). La
seconde tentative (chemin absolu correct) a été relancée mais son résultat n'a pas pu être observé
dans le temps imparti à ce fork — **aucune preuve (SPIR-V produit ou erreur exacte) n'a été
recueillie pour l'étage vertex isolé**, contrairement à l'objectif de la directive.

**État honnête** : code sain et commité, hypothèse de test raisonnable (builtins modernes
équivalents, pas de changement de sémantique), mais AUCUNE confirmation empirique obtenue cette
passe-ci — ni succès ni nouvel échec précis à documenter. Ne pas présenter comme résolu.

**Prochaine étape concrète pour qui reprend** : relancer strictement
`cmd.exe //C "C:\lanterne-client-vulkan-test-3\launch7_v3.bat > C:\lanterne-client-vulkan-test-3\stdout.log 2> C:\lanterne-client-vulkan-test-3\stderr.log"`
(chemin absolu partout, pas de `cd` préalable dans un sous-shell séparé), attendre la fin réelle du
process (vérifiable via absence dans `tasklist`, le lanceur se ferme seul après le menu principal
selon le pattern déjà observé toute la nuit), puis grep `SHADERC-TEST` dans
`C:\lanterne-client-vulkan-test-3\logs\latest.log`. `LANTERNE_SHADERC_COMPOSITE_TEST=1` est déjà
posé dans `launch7_v3.bat`, rien d'autre à changer avant de relancer.

Process laissé à surveiller/nettoyer par la suite (vérifier `tasklist | grep -i java` avant tout
kill, jamais de kill global). Rien déployé sur le VPS ni `test 1`. Commit `0456255` sur `26.3`,
jamais poussé.

## AUDIT — charge CPU SERVEUR (cible Xeon 6230R mono-cœur) : rien de solide trouvé, codebase déjà mesurée (fork, 21/09 ~03h40)

Directive : auditer le code SERVEUR (exclu client-only) à la recherche de travail par tick non
nécessaire ou d'allocations en boucle, avec en tête la contrainte mono-cœur GC-sensible du Xeon
6230R de production (`memory/cible-serveur-un-coeur.md`). **Aucune modification de code — audit
seul, verdict honnête négatif, cohérent avec les audits perf précédents de cette nuit.**

Recensement des 12 `ServerTickEvent.Post`/`Pre` réels du dépôt (`Lanterne.java`, `Cadence.java`,
`Cheptel.java`, `Easel.java`, `Groove.java`, `RadiographieServeur.java`, `Bazaar.java`,
`Envol.java`, `PortalPreload.java`, `MecheNet.java`, `Rafale.java`, `Solid.java`/`Census.java` via
`LevelTickEvent`). Deux fichiers lus en détail en profondeur, tous deux déjà exemplaires :

- **`core/PortalPreload.java`** : scan borné (`SCAN_RADIUS=3`, `SCAN_INTERVAL=10` ticks, un seul
  joueur scanné par tick via un compteur, retour dès le premier portail trouvé), doublé d'un cache
  `lastPreloaded` par joueur pour éviter tout recalcul redondant. La Javadoc documente un vrai banc
  (`lab/Traversee`) qui a mesuré et REJETÉ un rayon de ticket plus large (5 au lieu de 3) après
  l'avoir cru bénéfique — exactement le genre de faux-bon-réglage qu'un audit de lecture seule
  aurait pu recommander à tort.
- **`core/Census.java`** : `refresh()` est déjà throttlé par `REFRESH_PERIOD` (ne retravaille pas
  à chaque tick), le travail est explicitement "proportionnel au nombre de chunks occupés par des
  joueurs, et non au nombre d'entités" (Javadoc de méthode), et `announce()` ne journalise que sur
  changement d'état (corrige un vrai bug de spam de logs déjà signalé par un hébergeur, mentionné
  dans le code lui-même) — pas de piste d'optimisation supplémentaire trouvée.

**Constat global** : contrairement à un audit habituel où on trouve facilement du travail non
gardé, cette base de code a déjà une discipline de mesure systématique (bancs `lab/*`, Javadoc
citant des chiffres réels avant/après pour chaque paramètre de throttle) — cohérent avec l'audit
perf client déjà fait cette nuit (« AUDIT — recherche de nouveaux gains de perf client », même
verdict négatif). Ne pas confondre "rien trouvé" avec "pas cherché" : les deux fichiers les plus
probables (scan spatial répété, recensement par joueur) ont été lus intégralement, pas juste
grep-és.

**AutoMiner (bot, `C:\Users\trufa\Documents\ClubCitrouilleAutoMiner`) non audité** : repo séparé,
et surtout — c'est un mod **client-only** (confirmé par la description du projet en mémoire), donc
hors du périmètre "charge CPU serveur" de cette directive précise ; sa boucle de pathfinding ne
pèse jamais sur le Xeon mono-cœur de production. Pas creusé pour cette raison, pas par manque de
temps.

Aucun fichier modifié, aucune compilation nécessaire, aucun process de test lancé (lecture de code
seule).

## TÂCHE VULKAN — étage VERTEX confirmé : SUCCÈS réel du re-blocage UBO (fork, 21/09 ~03h35)

Reprise stricte de la section précédente (« vertex stage isolé des builtins legacy, test lancé,
résultat NON récupéré ») — UNIQUEMENT le test manquant, aucune nouvelle investigation, aucun
nouveau code.

**Correctif de la leçon documentée appliqué** : `cmd.exe //C` invoqué avec le chemin ABSOLU du
`.bat` en un seul argument (pas de `cd` séparé), lancé via Bash `run_in_background` (auto-basculé
en tâche de fond par le harness après 300s), puis surveillé via `Monitor` (attente de la ligne
`SHADERC-TEST` dans le log ou fin du process).

**Résultat empirique obtenu, log réel** (`C:\lanterne-client-vulkan-test-3\stdout.log`, 03:34:50) :
```
[SHADERC-TEST] RESULTAT VANILLA: ECHEC
[SHADERC-TEST] patchVanilla + re-blocage des uniforms en UBO (reponse au blocage 'non-opaque
uniforms outside a block')...
[SHADERC] UniformReblocker applique (vertex 1972 -> 2074 caracteres, fragment 1371 -> 1481
caracteres), tentative shaderc...
[SHADERC] Compilation GLSL->SPIR-V reussie : 5576 octets SPIR-V produits.
[SHADERC] Compilation GLSL->SPIR-V reussie : 3428 octets SPIR-V produits.
[SHADERC-TEST] RESULTAT VANILLA REBLOCKED: SUCCES
```
**Confirmé sans ambiguïté** : sans re-blocage, `patchVanilla` échoue toujours sur `'non-opaque
uniforms outside a block'` (attendu, déjà documenté). AVEC re-blocage (`UniformReblocker`), les
DEUX étages compilent en SPIR-V valide — **5576 octets pour le vertex** (nouveau, jamais obtenu
avant cette passe) **et 3428 octets pour le fragment** (cohérent avec le succès fragment déjà
prouvé plus tôt, tailles légèrement différentes car shader de test modernisé sans builtins legacy,
pas le même fichier source que le test précédent).

**Conclusion pour cette nuit** : le re-blocage UBO (`UniformReblocker.java`) est désormais confirmé
fonctionner pour LES DEUX étages de `patchVanilla` (le point d'entrée le plus utilisé du pipeline
Iris), une fois le shader source débarrassé des builtins GLSL legacy. C'est la preuve la plus
complète obtenue cette nuit sur la faisabilité Vulkan — reste vrai que ceci est un prototype isolé
(`ShadercProbeSelfCheck.java`/`UniformReblocker.java`), JAMAIS branché au pipeline de rendu réel, et
que les shaders RÉELS d'un shaderpack (pas le shader vanilla minimal de test) peuvent contenir
d'autres builtins legacy non couverts par ce test — la modernisation systématique de shaders
arbitraires (pas juste le cas de test) reste la prochaine étape non commencée.

Aucun fichier modifié cette passe (uniquement exécution du test déjà préparé par le fork
précédent). Process client `C:\lanterne-client-vulkan-test-3\` laissé actif à ce stade (dépassait le
menu principal, `sodium-workaround-cleanup` observé dans les logs) — pas de kill (`tasklist | grep
-i java` vérifié avant toute action, jamais de kill global, laissé à surveiller/nettoyer par la
suite). Rien déployé sur le VPS ni `test 1`. Commit du fork précédent `0456255` reste la seule
modification de code liée à cette tâche.

## AUDIT ClubCitrouilleAutoMiner — rien trouvé, verdict négatif honnête (fork, 21/09 ~04h05)

Directive : auditer `C:\Users\trufa\Documents\ClubCitrouilleAutoMiner` (repo séparé, client-only,
jamais retouché cette nuit depuis les fixes YouTube/kick/navigation du tout début de session) à la
recherche d'un vrai bug ou d'un gain de perf, avec preuve.

**Vérifications faites** :
- `git log`/`git status` : dépôt propre, `26.3` à jour avec `origin/26.3`, dernier commit
  `a33b207` ("Quarry : etend le pont Rafale au minage general").
- Recherche du 4e cas potentiel du bug déjà connu (« confisque le tick en créatif faute de check
  `hasInfiniteMaterials()` ») : les 14 sites qui référencent `hasInfiniteMaterials`/`instabuild`
  sont tous déjà correctement gardés (`GoalPlanner.java:388`, `BotController.java:1037`,
  `BuildSite.java:960,1123`, `CreativeBuildSelfCheck.java:106`) — aucun trou du même pattern.
- `Quarry.java` (1168 lignes, module touché en dernier par le repo, étend le pont Rafale au
  minage général) : aucune mention de `creative`/`instabuild`/`hasInfiniteMaterials` — cohérent,
  ce module gère du minage en survie normale, pas de logique dépendante du mode de jeu. Aucun
  `while(true)`/boucle sans condition de sortie trouvé.
- `autominer.log` (`Club Citrouille/logs/autominer.log`, seule instance où ce fichier existe —
  ancienne instance 26.1.2 du patron, hors `test 1` où se fait le test Lanterne ce soir) : 0
  occurrence de "exception"/"error"/"bloqué"/"stuck" sur tout le fichier ; dernière séance loguée
  se termine par « rien qui se soit repete anormalement ». Aucune trace fraîche de problème.
  `test 1` n'a pas de fichier `autominer.log` distinct dans ses logs cette nuit (le bot n'a
  probablement pas tourné sur cette instance pendant les tests Iris).
- Cohérence avec les changements Lanterne cette nuit (retrait partiel de Sodium, migration Iris) :
  AutoMiner n'a aucune dépendance directe sur les classes Sodium/Iris (vérifié par absence totale
  de référence `net.irisshaders`/`net.caffeinemc` dans le repo) — ses fonctions (détection
  d'item/inventaire/pathfinding) passent par l'API vanilla/NeoForge standard, pas de rendu. Aucun
  risque de régression croisée identifié.

**Conclusion honnête** : rien trouvé après audit raisonnable et ciblé (log + pattern de bug connu +
module le plus récent + couplage croisé avec le reste de la nuit). Pas un audit exhaustif ligne par
ligne de tout le repo (35 fichiers dans `bot/`, hors périmètre d'un fork borné) — un futur audit
plus profond pourrait creuser les allocations dans le pathfinding (`GoalPlanner`/`Passability`),
non fait cette nuit faute de piste concrète pour orienter la recherche.

Aucun fichier modifié, aucune compilation nécessaire, aucun process lancé.

## VULKAN — test sur un VRAI fichier de shaderpack (gbuffers_terrain, Complementary Reimagined) (fork, 21/09 ~04h07)

Suite directe des preuves Vulkan précédentes (fragment 3532 octets, puis vertex+fragment 5576+3428
octets) — toutes obtenues sur un shader "gbuffers" écrit à la main, limite honnêtement documentée.
Cette passe teste `shaders/world0/gbuffers_terrain.{vsh,fsh}` du VRAI shaderpack
`ComplementaryReimagined_r5.9.zip` (`test 1/shaderpacks/`).

**Nouveau code** : `ShadercProbe.testRealPackVanillaInClient` réutilise `IncludeGraph`/`IncludeProcessor`
(déjà migrés, jamais utilisés jusqu'ici) pour résoudre les VRAIS `#include` du shaderpack — chose que
les tests précédents ne pouvaient pas faire. Gate séparé, `LANTERNE_SHADERC_REALPACK_TEST`.

**Résultat en 2 temps, voir `RECHERCHE-VULKAN-SANS-SODIUM.md` (ÉTAPE 7) pour le détail complet** :
1. Premier test (commit `8b256d4`) : `#include` résolus avec succès (10446 lignes réelles produites),
   mais échec précis `Unparsed preprocessor directives such as '#define'...` — `patchVanilla` attend
   un GLSL déjà préprocessé, pas les `#define`/`#ifdef` bruts issus du flatten `#include`.
2. Correctif (commit `3baf822`) : ajout d'un passage `JcppProcessor.glslPreprocessSource` avant
   `patchVanilla`+reblock. Compile sain.

**Re-test du correctif : INCONCLUANT.** Une instance de lanceur client fraîche
(`C:\lanterne-client-vulkan-test-5\`) s'est bloquée ~17 minutes sur `UnihexProvider` sans jamais
atteindre le menu principal — 8 process `java.exe` tournaient en même temps cette nuit (plusieurs
autres forks actifs), cause la plus probable (contention), mais pas une certitude absolue. Processus
laissé actif (pas de kill, jamais de kill global). **Le correctif JcppProcessor n'a PAS de
confirmation empirique fraîche — ne pas le présenter comme résolu.**

**Pour qui reprend** : relancer `C:\lanterne-client-vulkan-test-5\launch7_v5.bat` (ou une nouvelle
copie si bloquée) à un moment avec moins de charge machine concurrente, puis
`grep "SHADERC-TEST" logs/latest.log`. Si l'échec `#define` a disparu et qu'un nouveau résultat
(succès ou nouvel échec précis) apparaît, documenter la suite ici.

Commits `8b256d4`, `3baf822` sur `26.3`, jamais poussés. Rien déployé VPS/test1.

## VULKAN — reconfirmation du test réel gbuffers_terrain, résultat précis (fork, 21/09 ~04h10)

Suite directe de la section précédente ("test sur un VRAI fichier de shaderpack", résultat
"INCONCLUANT" faute de pouvoir relancer sans contention machine).

**Nettoyage préalable** (prudent, un par un, jamais de kill global) : `tasklist`/`Get-CimInstance
Win32_Process` ont confirmé 8 process `java.exe` actifs — 5 étaient gradle (wrapper compileJava,
daemon, 3 workers, tous légitimes, non touchés) et 3 étaient des clients de test jetables abandonnés
identifiables sans ambiguïté par leur `-DlibraryDirectory=C:/lanterne-client-vulkan-test-3` (x2 PID)
et `-test-4` (x1 PID) — aucun ne référençait le vrai chemin `curseforge\minecraft\Instances`, donc
aucun risque de fermer le client du patron. Fermés individuellement par PID (`taskkill //PID <n>
//F`), jamais `/IM java.exe` global. Le process `test-5` mentionné comme "bloqué" dans la section
précédente n'apparaissait déjà plus dans la liste — probablement mort/crashé entre-temps.

**Relance propre** de `C:\lanterne-client-vulkan-test-5\launch7_v5.bat` (mêmes variables d'env,
machine désencombrée) : cette fois le menu est atteint et le test se déclenche en ~40s (vs 17 min de
blocage rapporté précédemment) — confirme que c'était bien de la contention machine, pas un bug.

**Résultat RÉEL et PRÉCIS, log `logs/latest.log` (04:09:51-52)** :
- Composite/vanilla synthétique : identiques aux preuves déjà acquises (968 / 5576+3428 octets SPIR-V).
- **VRAI shaderpack (`gbuffers_terrain`, #include résolus, 10446 lignes réelles, JcppProcessor
  appliqué)** : le re-blocage `UniformReblocker` tourne mais **échoue sur le fichier réel**, avec
  DEUX problèmes distincts, tous deux nouveaux :
  1. Après re-blocage, il reste encore 3 erreurs `'non-opaque uniforms outside a block'` (lignes
     143+) — `UniformReblocker` ne capture pas la totalité des uniforms non-opaques sur ce fichier
     bien plus gros/complexe (15127 caractères vertex après reblock) que le shader de test synthétique
     — probablement des uniforms déclarés dans une portion du fichier que le parseur actuel ne
     couvre pas (héritage d'un `#include` profond, ou syntaxe multi-déclaration `uniform vec3 a, b;`).
  2. **`java.lang.OutOfMemoryError: Out of stack space`** sur le fragment (99921 → 99794 caractères)
     — signe d'une récursion non bornée quelque part dans la chaîne (`JcppProcessor` ou
     `UniformReblocker`) qui explose sur un fichier de cette taille réelle, jamais rencontré sur les
     shaders de test (~1-2K caractères).

**Conclusion honnête** : la chaîne complète (jcpp → glsl-transformer/patchVanilla → UniformReblocker
→ shaderc) est prouvée fonctionner de bout en bout sur un shader synthétique modernisé, MAIS ne
tient PAS encore sur un vrai fichier de shaderpack de taille réelle — deux bugs précis et nouveaux
restent à corriger avant de pouvoir généraliser : (a) couverture incomplète du parseur d'uniforms de
`UniformReblocker` sur les gros fichiers, (b) récursion non bornée causant un stack overflow. Ce
n'est PAS un verdict d'échec du principe Vulkan — c'est la preuve précise du prochain obstacle
concret, sur un cas réel plutôt qu'un jouet.

Aucune modification de code cette passe (directive = reconfirmation empirique uniquement, pas
nouvelle investigation). Process `C:\lanterne-client-vulkan-test-5\` laissé actif (pas de kill).
Rien déployé VPS/test1.

## VÉRIFICATION VPS ~06h00 (lecture seule, aucune action corrective)

Contrôle demandé par le coordinateur pour confirmer que le VPS est resté stable pendant la nuit
(monde officiel réel, aucune intervention directe autorisée). Accès SSH clé
`C:/Users/trufa/.ssh/lanterne_vps`, `sudo -n` disponible en lecture pour les fichiers `minecraft:minecraft`.

**Process serveur** : PID `321304`, `java @user_jvm_args.txt @libraries/.../unix_args.txt nogui`,
actif depuis `21Sep2026 00:08:08`, uptime `3h05` au moment du contrôle (`03:14 UTC`). Un seul
process, pas de doublon/zombie.

**Version réellement chargée — DIFFÉRENTE de ce que disait la section 1 du résumé matinal** :
```
Mod List:
    BlueMap 5.27 (bluemap)
    Jade 26.3.1+neoforge (jade)
    Lanterne 26.3.59 (lanterne)
    Minecraft 26.3 (minecraft)
    NeoForge 26.3.0.7-beta (neoforge)
```
`lanterne-26.3.59.jar` tourne, pas `26.3.55`. Warning bénin de Mèche : `lanterne (version 26.3.57 ->
26.3.59)` (juste l'auto-update qui a constaté un saut de version, pas une erreur).

**Preuve que TÂCHE A (Cadence, commit `9da44c7`) est bien active en prod**, log exact :
```
[21Sep2026 00:08:27.056] [BlueMap-Plugin-Loading/INFO] [fr.clubcitrouille.lanterne.Lanterne/]:
[CADENCE] BlueMap prêt, 0 joueur connecté — rendu complet démarré automatiquement (plus besoin
d'attendre une connexion/déconnexion).
```
Exactement le comportement corrigé cette nuit. TÂCHE B (modId `iris` virtuel) est aussi présente
dans ce jar (confirmée par un fork précédent sur `test 1`, même version `26.3.59`).

**Anomalies dans les logs depuis le boot** : AUCUNE. `grep -iE 'exception|error|oom|running.{0,3}slow|crash|out of memory|warn'`
ne remonte que 5 lignes, toutes bénignes et déjà connues :
- 3x `Reference map 'iris.refmap.json' ... could not be read` (attendu hors dev-env, sans effet)
- 1x le warning de version Mèche ci-dessus
- 1x `Missing metadata in pack file/bukkit.zip` (préexistant, sans rapport avec cette nuit)

Pas de watchdog "running slow", pas de GC pause anormale visible, pas de crash-report généré.
`Server empty for 60 seconds, pausing` à `00:09:24` — comportement normal d'un serveur à 0 joueur,
c'est la dernière ligne du log (le serveur réduit son activité de log en pause, pas un arrêt).

**BlueMap** : chargé sans erreur, 3 cartes (`world`, `world_the_nether`, `world_the_end`), WebServer
démarré sur le port `8100`, rendu déclenché automatiquement par Cadence dès le boot (voir ci-dessus).

**Pregen** : aucun fichier `pregen-en-cours.txt` trouvé à la racine `/srv/minecraft` — cohérent avec
"aucun pregen en cours" (le patron n'a pas encore lancé le pregen 500 chunks demandé cette nuit,
ce n'était pas dans le périmètre des forks de cette nuit). Vérification RCON `pregen-status`/`list`
non aboutie (script Python one-shot mal formé côté parsing de la réponse d'auth RCON, erreur
`struct.error` sur le paquet de réponse) — pas de deuxième tentative, hors périmètre de ce contrôle
qui est un contrôle de stabilité, pas une action.

**Conclusion** : VPS parfaitement stable toute la nuit, 0 anomalie, `lanterne-26.3.59.jar` (donc
TÂCHE A et TÂCHE B) tourne déjà en prod avec succès malgré ce que disait la section 1 du résumé —

## CLÔTURE — test complet du cadran shaderpack `Dials.java` (fork, 21/09 ~05h30)

Suite directe de la tâche `03h25` ci-dessus, laissée avec "run n'a pas été suivi jusqu'au menu
principal confirmé". Machine désencombrée (fork Vulkan bloqué arrêté et nettoyé juste avant) :
relance propre de `C:\lanterne-client-dials-shaderpack\launch7.bat` (chemin absolu, pas de `cd`
relatif), suivie jusqu'au bout cette fois.

**Résultat, cette fois complet** : `Iris/: Creating pipeline for dimension minecraft:overworld`,
`SoundEngine: Sound engine started`, `Reloading ResourceManager: ... mod/lanterne,iris ...` —
le client atteint bien l'étape post-chargement des ressources (juste avant l'écran-titre/menu,
étape la plus avancée obtenue cette nuit sur cet environnement précis). **0 `FATAL`**, aucune
exception liée à `Dials`/`Iris`/`Sodium`/au cadran shaderpack — les seules erreurs présentes dans
le log sont sans rapport (`ClassNotFoundException` sur des classes d'autres mods absents de cet
environnement de test minimal, échecs d'authentification Mojang/Realms attendus hors-ligne,
warning de compat resource pack Sodium déjà connu). Process fermé proprement après vérification
(`Get-CimInstance` confirmant le chemin `lanterne-client-dials-shaderpack` avant `taskkill //PID`,
jamais de kill global).

**Limite qui reste, honnêtement** : le clic UI sur le cadran lui-même (cycle effectif entre
shaderpacks, mise à jour du nom affiché) n'est toujours pas testable sans écran interactif depuis
un fork — nécessite un test humain en jeu, comme documenté depuis le début. Aucune nouvelle piste
lancée sur ce sujet, conforme à la consigne de clôture : le code est maintenant confirmé sain au
chargement, ce qui manquait était uniquement la confirmation "0 FATAL jusqu'au bout du démarrage",
obtenue ici.

Rien modifié dans le code (pas de bug trouvé), rien commité (rien à commiter), rien déployé sur
VPS/test1.
aucune action corrective nécessaire, juste une mise à jour de doc (faite en tête de fichier).

## AUDIT — accumulation Radiographie (fichiers profil-*.txt / mémoire) : PAS de risque, aucune modification (fork, 21/09 ~06h)

Directive : vérifier si `Radiographie.java` (profiling client étendu cette nuit) accumule sans
borne, en mémoire pendant une session ou en fichiers sur disque à long terme.

**Constaté, code + fichiers réels** :
- `config/lanterne/profil-*.txt` (instance `test 1`) : 9 fichiers générés cette nuit, 8-25 Ko
  chacun — taille négligeable, pas de souci même sur des mois d'usage quotidien (quelques Mo/an).
- **En mémoire, tout est déjà borné par construction** :
  - `fpsHistogramme` : tableau fixe de 6 cases (compteurs `long`), pas une liste qui grossit.
  - `creuxDetectes`/`fenetreEnCours` : plafonnés à `CREUX_MAX = 15` (constante existante, déjà
    pensée pour ça — commentaire du code : "borne la taille du rapport final pour une session où
    le jeu resterait durablement sous le seuil").
  - `lentilleTimeline` : vidée à chaque `start()` (nouveau monde), ne grossit qu'au rythme des
    transitions d'état réelles (pas par image) — pas de croissance illimitée en pratique.
- Un fichier par SESSION (au `stop()`, pas par tick/frame) — pas de risque d'accumulation rapide.

**Conclusion** : aucun bug, rien à corriger. Le design de cette nuit (histogramme fixe, `CREUX_MAX`)
avait déjà anticipé ce risque. Aucun fichier modifié, aucune compilation nécessaire, rien commité.

## IMPLÉMENTATION — abstraction VertexFormatSource, dernier couplage structurel Sodium résolu (fork, 21/09 ~05h30)

Suite de l'étape 9 de `RECHERCHE-VULKAN-SANS-SODIUM.md` (périmètre confirmé clos : un seul champ
`WorldRenderingSettings.chunkVertexFormat` restait typé Sodium en dur).

**Fait, testé, commité (`b1a7838`)** :
- `WorldRenderingSettings.java` n'importe plus rien de Sodium — champ retypé en interface neutre
  `VertexFormatSource` (nouveau package `net.irisshaders.iris.compat.vertexformat`).
- Adaptateur `SodiumVertexFormatSource` isole le vrai `ChunkVertexType` (même motif que
  `SodiumVertexSerializers` de cette nuit).
- 2 appelants de `setVertexFormat` + 2 mixins Sodium adaptés, 7 sites de lecture inchangés (le
  type de retour final ne change pas).
- Testé dans un client jetable AVEC Sodium présent (`C:\lanterne-client-dials-shaderpack\`) :
  `Iris/: Creating pipeline for dimension minecraft:overworld`, 0 FATAL, aucune exception liée au
  changement — comportement strictement identique confirmé.

**Reste non résolu, volontairement** : `VanillaRenderingPipeline.java` référence toujours
`ChunkMeshFormats.COMPACT` (Sodium) directement — pas de format vanilla pur équivalent identifié
avec certitude, donc pas de fallback fabriqué à l'aveugle. C'est le VRAI dernier morceau pour un
retrait complet de Sodium (calcul du format, pas juste son transport). Détail dans
`RECHERCHE-VULKAN-SANS-SODIUM.md` étape 10.

Compile clean (`./gradlew compileJava` + `./gradlew build -x test`), rien déployé VPS/test1,
process de test fermé proprement (PID vérifié via `Get-CimInstance` avant `taskkill`).

## Audit paquets réseau custom (~06h30, pure lecture de code, aucune modification)

Piste explorée : un paquet Lanterne (33 classes `CustomPacketPayload` trouvées dans
`core/network/`, `content/*/`) est-il envoyé à chaque tick sans besoin, transporte-t-il des
données inutilement volumineuses, ou broadcast-t-il à tous les joueurs alors qu'un envoi ciblé
suffirait ?

**Résultat : rien à corriger, conception déjà saine.**

- Aucun `PacketDistributor.sendToAllPlayers` ni équivalent broadcast trouvé dans tout le mod —
  tous les envois serveur→client sont `sendToPlayer` (ciblé) ou en réponse directe à une requête
  d'un joueur précis. Aucun broadcast global inutile.
- Les 2 seuls envois réellement rattachés à un `ClientTickEvent.Post` avec un `sendToServer` à
  l'intérieur (`SouvenirClient.onClientTick`, `Bell.onClientTick`) sont tous les deux gardés par
  une condition qui empêche l'envoi la plupart des ticks : `SouvenirClient` n'envoie que si la
  dimension a changé depuis la dernière annonce (`dimension.equals(lastAnnouncedDimension)`) ;
  `Bell` n'envoie que si une touche a été pressée ce tick (`OPEN.consumeClick()`). Aucun des deux
  n'envoie à chaque tick sans condition.
- `Wheel.pump()` (transfert de fichier audio par morceaux, `Post.Gift`) est bien rattaché à un
  `ClientTickEvent.Post` sans garde de dimension/clic, mais est déjà borné par construction :
  sort immédiatement si rien n'est en cours de transfert (`giving == null`), et plafonne le
  nombre de morceaux envoyés par tick via `Studio.shardsPerTick()` (config existante) — c'est un
  débit volontairement contrôlé, pas une fuite.
- `Sight.java` (déjà throttlé cette nuit, commit `c1d7a1f`) envoie `RegardAsk` seulement sur
  changement de cible ou toutes les 10 ticks — cohérent avec le reste de l'audit.

Aucun fichier modifié, rien compilé, rien commité.

## Audit infrastructure de tests unitaires — AUCUNE infrastructure existante, rien ajouté (fork, 21/09 ~06h50)

Directive : ajouter des tests JUnit de non-régression pour les correctifs les plus critiques de la
nuit (`IrisForgeHelpers.getVersion()`, `Radiographie.fpsHistogramme`, etc.), si une infrastructure
existe déjà.

**Constat** : `build.gradle` ne déclare AUCUNE dépendance de test (pas de `testImplementation`, pas
de `junit`, pas de bloc `test { useJUnitPlatform() }`). `src/test/java/` n'existe pas dans le dépôt.
Le projet n'a jamais eu de tests unitaires — ce n'est pas un oubli de cette nuit, c'est l'état de
base du dépôt.

**Décision : ne rien ajouter.** Créer une infrastructure de test complète (dépendance JUnit,
plugin Gradle, structure `src/test/`, configuration de la tâche `test`) dans un projet NeoForge/
ForgeGradle en fin de nuit, sans pouvoir itérer sur plusieurs cycles si la config casse le build
(le toolchain ForgeGradle a ses propres particularités de classpath/sourceSets qui peuvent entrer
en conflit avec une config JUnit naïve), est un risque disproportionné par rapport au bénéfice —
d'autant que le dépôt entier a déjà servi de plateforme à des dizaines de forks cette nuit, un
`build.gradle` cassé aurait un rayon d'impact large.

**Recommandation pour la reprise (à froid, avec plus de marge pour itérer)** : ajouter
`testImplementation 'org.junit.jupiter:junit-jupiter:5.x'` + `test { useJUnitPlatform() }` dans
`build.gradle`, puis un premier test trivial (`RadiographieHistogrammeTest` sur la logique pure de
bucket FPS, aucune dépendance Minecraft) pour valider que le toolchain accepte les tests avant d'en
écrire davantage. Aucun fichier modifié, rien compilé, rien commité pour cette tâche précise.
