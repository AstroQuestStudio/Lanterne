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
2. Continuer le retrait de Sodium (33 fichiers, voir `RECHERCHE-VULKAN-SANS-SODIUM.md`).
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

**TÂCHE B (visibilité Iris/Sodium) non commencée faute de temps — reste à faire.**
