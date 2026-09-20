# Vulkan ×3,71 plus lent : origine du chiffre, vérification indépendante, tentative de remesure

Mandat : confirmer ou infirmer, avec un VRAI chiffre pris cette passe, le ralentissement Vulkan
rapporté par le banc RING (`notes/parite-opengl-vulkan-mixins-20260920.md`), et chercher un
correctif seulement si confirmé. Travail effectué dans le worktree dédié
`Lanterne-worktree-vulkan-lenteur`, branche `feature/vulkan-lenteur-confirmee` — le dépôt principal
n'a jamais été modifié (uniquement lu, pour copier en lecture seule un monde de banc et deux
répertoires `run-*` déjà connus pour fonctionner).

## 1. Le banc RING — retrouvé, lu en entier

Contrairement à l'indication du mandat (« `lab/`, cherche-le »), il n'y a pas de dossier `lab/` à la
racine : c'est un **paquet Java**, `src/main/java/fr/clubcitrouille/lanterne/lab/`. Les classes qui
comptent :

- **`Glass.java`** (`lab/Glass.java`) : le vrai banc d'images. Compare AVEC/SANS Lanterne (pas
  directement Vulkan-vs-OpenGL — le choix de backend est un réglage séparé, voir plus bas), sur une
  scène posée par `Scene.build(...)`. Chauffe 30 s, mesure jusqu'à 60 s par phase (ou 16384 images),
  médiane + 1 % le plus lent + débit, contrôle de cohérence débit/médiane, détection de cadence
  imposée par l'écran. Armé par `LANTERNE_GLASS=1`, câblé par `client/Pane.java` (`Glass.begin()`/
  `Glass.frame()` sur l'évènement de rendu).
- **`Scene.java`** : `Kind.RING` = « le disque historique : gradient de distance, charge homogène »
  — un troupeau à ciel ouvert (pas sous toit), donc pleinement soumis au module `horizon`.
  `LANTERNE_GLASS_SCENE=ring` et `LANTERNE_GLASS_HERD=10000` sélectionnent cette charge et sa
  taille.
- **`Cheptel.java`** : un banc DIFFÉRENT (profil serveur par palier 1000/3000/10000), qui réutilise
  la même forme de scène RING mais ne mesure ni le rendu ni le backend — hors sujet ici.
- **`Snap.java`** : capture d'écran automatique (`LANTERNE_AUTO_SCREENSHOT=1`), gardée sous la main
  pour vérifier visuellement un éventuel correctif plus tard.

Le sélecteur de module à isoler : `Settings.configureFromEnvironment()` — `LANTERNE_MODULES=horizon`
n'active QUE `horizon` (tout le reste éteint), confirmé en lisant `horizon = wanted.contains(
"horizon")`.

## 2. D'où vient le ×3,71 — retrouvé dans l'historique git, et la lecture d'origine est trompeuse

`git log --all -S"3,71" --oneline` donne quatre commits. Le premier chronologiquement,
**`c631b9ee8a8f1bb1af129182c1c32fa2c51c18dc`** (19/09/2026, « Deux plantages reels au lancement
corriges en testant vraiment en jeu, et horizon mesure »), est la SOURCE du chiffre. Message exact :

> Horizon : mesure en jeu (banc Glass, scene RING, 10000 vaches a decouvert, AVEC/SANS sur la meme
> session) - x12,41 sous OpenGL (7,4 -> 92,1 im/s), x3,71 sous Vulkan reellement force (8,1 -> 30,1
> im/s, le tick serveur des dix mille creatures devient le nouveau plafond une fois le rendu
> debloque).

Le même texte est repris mot pour mot dans le javadoc de `ClientConfig.HORIZON` (config utilisateur).

**Point important, vérifié en relisant ce commit avec soin plutôt que de reprendre la formule toute
faite : le ×3,71 n'est PAS un ratio Vulkan/OpenGL.** C'est le ratio de gain INTERNE du module
`horizon` (AVEC/SANS Lanterne) mesuré séparément sous chaque backend :

| Backend | SANS (mod off) | AVEC (mod on) | Gain interne |
|---|---|---|---|
| OpenGL  | 7,4 im/s | 92,1 im/s | ×12,41 |
| Vulkan  | 8,1 im/s | 30,1 im/s | **×3,71** |

Le vrai ratio Vulkan-vs-OpenGL, à tirer de ce même relevé (mod ON des deux côtés, condition livrée
par défaut) : **92,1 / 30,1 ≈ ×3,06** — Vulkan trois fois plus lent qu'OpenGL sur cette scène, pas
3,71 fois. Sans le module (mod OFF), Vulkan est même légèrement PLUS rapide qu'OpenGL (8,1 > 7,4).

Un commit ultérieur, **`003d5c1`** (« Diagnostic Vulkan : acquireNextTexture() disculpé... »),
relabellise ce chiffre dans son propre titre comme *« l'écart hors-rendu ×3,71 Vulkan/OpenGL »* —
ce qui n'est pas ce que le commit d'origine avait mesuré. Le vrai écart « hors rendu » mesuré
(commit **`5e9800e`**, même session de banc) est **×57** (25,92 ms hors-rendu sous Vulkan contre
0,45 ms sous OpenGL, rendu pur comparable : 7,47 vs 6,79 ms). Le dépôt contient donc, de bonne foi,
deux chiffres différents (×3,71 et ×57) qui ont fini par désigner la même enquête dans les javadoc
des mixins de diagnostic (`VulkanPresentDiagMixin`/`VulkanSubmitDiagMixin`) sans que la distinction
soit faite explicitement. **Conclusion honnête : le ralentissement Vulkan sur cette scène est réel
et déjà mesuré par une passe précédente (au moins ×3 sur le débit direct, ×57 sur le compartiment
hors-rendu isolé), mais le chiffre « ×3,71 » précisément cité dans le mandat de cette passe n'est
pas le bon proxy pour « Vulkan plus lent qu'OpenGL » — c'est un artefact de calcul interne au
backend Vulkan seul.**

## 3. Vérification indépendante au javap — la piste « 1 image en vol » est RÉFUTÉE

Avant toute remesure, vérification par lecture directe du bytecode du vrai jar patché
(`build/moddev/artifacts/minecraft-patched-26.3.0.3-beta-merged.jar`, `javap -p -c -constants`),
sans reprendre pour acquis ce que les commits précédents affirmaient :

```
public static final int MAX_SUBMITS_IN_FLIGHT = 2;
```

confirmé dans `VulkanCommandEncoder.class`. Le corps de `submit()` appelle bien
`awaitSubmitCompletion(currentSubmitIndex - 2L, 5_000_000_000L)`, et `awaitSubmitCompletion`
(désassemblé en entier) fait un retour anticipé SANS appel Vulkan si
`completedSubmitIndex >= submitIndex` (le GPU a déjà rattrapé), et n'appelle
`VK12.vkWaitSemaphores(...)` — un vrai blocage CPU — que si le GPU est réellement en retard.

**La piste suggérée par le mandat (profondeur de file bloquée à 1, rendu strictement synchrone) est
donc fausse : la profondeur est déjà 2**, indépendamment confirmée (le commit `003d5c1` l'affirmait
déjà par la même méthode ; cette passe le revérifie elle-même sur le jar réel plutôt que de le
recopier). Aucun correctif « augmenter les frames in flight » n'a de cible : il n'y a rien à
augmenter de 1 à 2, c'est déjà 2. Si un vrai gain existe en poussant à 3, ce serait une piste
nouvelle, pas confirmée ici, et qui demande de mesurer d'abord (latence contre débit).

## 4. Confusion matérielle découverte cette passe : OpenGL et Vulkan ne tournent PAS sur le même GPU

Sur cette machine (portable hybride), le journal du premier lancement de cette passe montre :

```
[minecraft/Minecraft]: Using graphics backend OpenGL, using drivers: 3.3.0 Core Profile Context
[minecraft/Minecraft]: Using graphics device: AMD Radeon(TM) Graphics (ATI Technologies Inc.)
...
[mojang/VulkanBackend]: Preferring discrete GPU: NVIDIA GeForce RTX 3080 Laptop GPU
[LANTERNE][GPU] Backend actif : OpenGL sur AMD Radeon(TM) Graphics — un GPU Vulkan capable a été
détecté mais n'est PAS utilisé : preferredGraphicsBackend="default" essaie TOUJOURS OpenGL en
premier sur ce moteur.
```

**OpenGL démarre sur l'iGPU AMD ; Vulkan (forcé) choisit le GPU dédié NVIDIA RTX 3080 Laptop.**
C'est exactement le comportement déjà documenté par `Vigie.java` dans ce dépôt (trois sessions
journalisées), mais jamais neutralisé dans aucune mesure Vulkan-vs-OpenGL de ce dépôt, y compris la
mesure d'origine du ×3,71/×3,06. Ce n'est PAS ce qui explique le ralentissement Vulkan observé (un
GPU dédié plus puissant qui ressort plus lent renforce plutôt l'hypothèse d'un coût logiciel, pas
matériel — et les temps de « rendu pur » déjà mesurés, 6,79 vs 7,47 ms, étaient comparables). Mais
c'est une réserve honnête à porter sur tout chiffre absolu de fps : la comparaison n'isole pas
strictement l'API, elle compare aussi implicitement deux GPU différents.

## 5. Sécurité — vérifiée à chaque tentative de lancement

`tasklist`/`Get-CimInstance Win32_Process` relancé avant chaque lancement de client. À chaque
vérification cette passe : uniquement des `GradleDaemon`/`GradleWorkerMain` (projet Lanterne) et un
`gradlew build`/`neoform-runtime` du projet SIBLING `ClubCitrouilleAutoMiner` — jamais de
`net.minecraft.client.main.Main` ni d'arguments `--username`/`--accessToken`/LWJGL. Aucun signe
d'une vraie partie en cours. Feu vert maintenu, revérifié avant chaque tentative.

## 6. Tentative de remesure — état à ce stade de la passe

Premier essai (répertoire `run-vklenteur-gl` neuf + monde `banc` copié isolément) : `--
quickPlaySingleplayer` est resté bloqué sur un écran vanilla qui n'écrit rien dans le journal (cause
exacte non identifiable sans l'humain devant l'écran — confirmation de sauvegarde, migration, ou
réglages expérimentaux, comme `Glass.java` le documente déjà comme risque connu). Le banc s'est
REFUSÉ tout seul après 120 s, proprement (`[VITRE] MESURE REFUSÉE`), sans publier de chiffre faux —
comportement voulu.

Deuxième essai en cours : répertoires `run-vklenteur-gl`/`run-vklenteur-vk` reconstruits par copie
intégrale (lecture seule) de `run-cheptel`/`run-vitrage`, deux répertoires que l'historique du dépôt
documente explicitement comme ayant déjà chargé sans accroc (`build.gradle`, commentaire sur
`runClient`). Voir la suite de cette note (ou le commit suivant sur cette branche) pour le résultat.

## 7. Ce qui reste à faire

- Obtenir un débit AVEC/SANS réel sous OpenGL (GPU AMD) et sous Vulkan (GPU NVIDIA, avec
  `LANTERNE_VK_PRESENT_DIAG=1`) sur la scène RING/10000/horizon, fenêtres courtes.
- Si confirmé, lire les lignes `[LANTERNE][VK-DIAG]` (`acquireNextTexture`, `present`, `submit
  (total)`, `awaitSubmitCompletion`) pour savoir si le temps hors-rendu Vulkan est bien capturé par
  l'attente du sémaphore timeline, ou ailleurs.
- Chercher un correctif seulement si le suspect est confirmé par ces lignes — pas avant.
