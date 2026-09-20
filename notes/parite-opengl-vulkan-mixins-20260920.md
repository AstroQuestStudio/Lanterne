# Parité OpenGL vs Vulkan des mixins existants — diagnostic seul, aucun lancement de client

Contexte : mandat explicite du patron — « fix et optimise quand même OpenGL » — avec consigne de
vérifier honnêtement, mixin Vulkan par mixin Vulkan, si le même problème existe côté OpenGL ou si
c'est structurellement propre à Vulkan. Aucun client Minecraft n'a été lancé pour cette passe (six
processus `java.exe` non identifiés tournaient déjà au démarrage de la session parente — voir
`notes/rendu-terrain-vs-sodium.md`, section sécurité). Diagnostic de lecture de code et de bytecode
uniquement (`javap -p -c -constants`, jamais `.mcsrc/`), sur
`C:\Users\trufa\Documents\Lanterne\build\moddev\artifacts\minecraft-patched-26.3.0.3-beta.jar`
(lecture seule, extrait vers un dossier de scratch hors dépôt).

## Tableau des 8 mixins

| Mixin | Cible | Ce qu'il fait côté Vulkan | Verdict OpenGL |
|---|---|---|---|
| `VulkanDeviceMixin` | `VulkanDevice` (`<init>`/`close`) | Charge/sauvegarde `Empreinte` (cache `VkPipelineCache` persistant sur disque, évite de recompiler tous les pipelines à chaque lancement) | **Même problème existe, pas corrigé.** Vérifié : `GlPipelineRecompiler.compileProgram`/`GlDevice`/`GlRenderPipeline` ne contiennent AUCUN appel `glProgramBinary`/`glGetProgramBinary` (grep sur le désassemblage complet, zéro résultat) — OpenGL recompile aussi tous ses programmes GLSL à chaque lancement, sans persistance. Non corrigé ici : une vraie implémentation (format de cache versionné par pilote/driver, invalidation) est un vrai chantier, pas un garde-fou trivial — même verdict de prudence que ce dépôt applique déjà à `Empreinte` côté Vulkan. |
| `VulkanFeatureSetsMixin` | `VulkanFeatureSets` | Ajoute mesh shaders / ray tracing aux extensions Vulkan optionnelles demandées à la création du device | **Structurellement propre à Vulkan.** Grep exhaustif sur tout `com.mojang.renderpearl.backend.opengl` (désassemblage de TOUTES les classes du paquet) : zéro occurrence de `FeatureSet`. OpenGL négocie ses extensions autrement (`glGetString(GL_EXTENSIONS)`, pas de déclaration à la création du contexte) — aucun mécanisme équivalent à `FeatureSet.isSupported`, rien à répliquer. |
| `VulkanPhysicalDeviceMixin` | `VulkanPhysicalDevice` (`<init>`) | Journalise les extensions brutes du pilote + le support de l'indexation de descripteurs (`descriptorIndexing`) | **Structurellement propre à Vulkan.** Grep exhaustif : zéro occurrence de `DescriptorIndexing` dans tout le paquet `opengl`. L'indexation de descripteurs (ressources liées dynamiquement par tableau) est un concept Vulkan/D3D12, absent du modèle de binding OpenGL. Rien à journaliser côté OpenGL pour ce point précis. |
| `VulkanPresentDiagMixin` | `VulkanGpuSurface` | Diagnostic temporaire (chronomètres `acquireNextTexture`/`present`), enquête sur le banc RING (dix mille vaches, module `horizon`) qui a mesuré Vulkan ×3,71 plus lent qu'OpenGL en « hors rendu » | **Nuancé — voir section dédiée ci-dessous.** `GlSurface` implémente la MÊME interface `GpuSurfaceBackend` (`configure`/`acquireNextTexture`/`present`/`supportedPresentModes`) : un diagnostic jumeau est structurellement possible, pas empêché par l'architecture. Non écrit cette passe (voir raisonnement plus bas). |
| `VulkanQueueFamiliesMixin` | `VulkanPhysicalDevice` (`<init>`) | Journalise les familles de files (graphique/calcul/transfert) choisies, enquête sur une file de transfert dédiée jamais branchée | **Structurellement propre à Vulkan.** Grep exhaustif : zéro occurrence de `QueueFamily` dans tout le paquet `opengl`. OpenGL n'expose pas de files matérielles séparées — un seul flux de commandes implicite par contexte. Rien d'équivalent à journaliser. |
| `VulkanRenderPipelineMixin` | `VulkanRenderPipeline.compile` | Fait passer le handle `Empreinte` (cache persistant) à `vkCreateGraphicsPipelines`, pour TOUS les pipelines du moteur | **Même verdict que `VulkanDeviceMixin`** (mécanisme jumeau) : même lacune côté OpenGL, même prudence — pas de correctif à l'aveugle. |
| `VulkanSubmitDiagMixin` | `VulkanCommandEncoder` | Diagnostic temporaire (chronomètres `submit`/`awaitSubmitCompletion`), compagnon de `VulkanPresentDiagMixin` pour la même enquête RING | **Nuancé — voir section dédiée ci-dessous.** Découverte notable : `GlCommandEncoder.submit()` utilise RÉELLEMENT `GL33C.glFenceSync` puis `GlStateManager._glClientWaitSync` — un mécanisme d'attente bloquante CPU/GPU directement analogue au sémaphore timeline Vulkan (`vkWaitSemaphores`). Ce n'est donc PAS un cas où OpenGL n'a structurellement aucune synchronisation comparable à instrumenter. |
| `DynamicGpuDataMixin` | `net.minecraft.client.renderer.DynamicGpuData` (classe **vanilla partagée**, PAS dans `backend.vulkan`) | Relève la capacité de départ du « Dynamic Transforms UBO » (2 → configurable), évite une rafale de 7 réallocations de `VkBuffer` par image dense | **Ne s'applique pas comme les autres — bénéficie déjà d'OpenGL automatiquement.** Ce mixin ne cible pas une classe du paquet `backend.vulkan` : `DynamicGpuData` vit dans `net.minecraft.client.renderer`, la couche d'abstraction partagée par les deux backends (`GpuDevice.createBuffer` est appelé identiquement quel que soit le backend actif derrière). Le correctif déjà livré profite donc à un client OpenGL exactement de la même façon, sans travail supplémentaire — pas un « mixin Vulkan » au sens propre malgré son nom de fichier voisin des sept autres. |

## Le cas nuancé : `VulkanPresentDiagMixin` / `VulkanSubmitDiagMixin`

Contrairement à une hypothèse de départ raisonnable (« Vulkan a un modèle de synchronisation
explicite, OpenGL non — rien à instrumenter côté OpenGL »), la lecture du bytecode réel infirme la
partie « OpenGL n'a rien d'équivalent » :

- `GlSurface` (paquet `opengl`) implémente EXACTEMENT la même interface `GpuSurfaceBackend` que
  `VulkanGpuSurface` — mêmes méthodes `configure`/`acquireNextTexture`/`present`/
  `supportedPresentModes`, signatures identiques.
- `GlCommandEncoder.submit()` (vérifié par `javap -p -c`) appelle `GL33C.glFenceSync(...)` puis,
  plus loin dans la méthode, `GlStateManager._glClientWaitSync(...)` — une attente CPU bloquante sur
  complétion GPU, le pendant OpenGL du `vkWaitSemaphores`/sémaphore timeline que
  `VulkanSubmitDiagMixin` isole déjà côté Vulkan.

Un diagnostic jumeau (chronométrer `GlCommandEncoder.submit()`/l'attente `glClientWaitSync`, et
`GlSurface.present()`) serait donc structurellement possible et donnerait une vraie comparaison
symétrique — utile pour comprendre POURQUOI le banc RING a mesuré Vulkan plus lent qu'OpenGL sur
cette scène (30,1 im/s contre 92,1, ×57 de « hors rendu » en plus sous Vulkan), plutôt que de
supposer une explication structurelle qui ne tient pas entièrement à la lecture du code.

**Non écrit cette passe, délibérément** : ce serait une nouvelle classe d'instrumentation (pas un
correctif d'une ligne comme une garde nulle), et le mandat de cette passe limite l'écriture de code
aux correctifs trivialement sûrs face à un vrai bug identifié — pas à de nouveaux outils de mesure,
même sans risque de régression de rendu (les deux mixins Vulkan jumeaux sont eux-mêmes strictement
gated par une variable d'environnement, `LANTERNE_VK_PRESENT_DIAG`, et ne changent aucun
comportement). Documenté ici avec les noms de classes/méthodes exacts pour qu'une prochaine passe
qui reprend l'enquête RING puisse écrire `GlPresentDiagMixin`/`GlSubmitDiagMixin` directement, sans
redécouvrir ces deux points d'accroche.

**Ce que cela signifie pour la priorité Vulkan du patron** : le banc RING (10 000 vaches, module
`horizon`) est la seule mesure directe Vulkan-vs-OpenGL qui existe dans ce dépôt à ce jour, et son
verdict est défavorable à Vulkan sur cette scène précise (×3,71 plus lent en incluant le hors-rendu).
L'enquête `VulkanPresentDiagMixin`/`VulkanSubmitDiagMixin` a déjà disculpé `acquireNextTexture()` (0,02
-0,04 ms) et pointe maintenant vers `awaitSubmitCompletion()` dans `submit()` comme suspect principal
— mais AUCUNE mesure en jeu n'a encore confirmé ce chiffre (les deux mixins sont écrits mais
désactivés par défaut, jamais exécutés avec `LANTERNE_VK_PRESENT_DIAG=1` d'après ce qui est visible
dans le code — aucune trace de résultat chiffré dans les Javadoc, contrairement à d'autres enquêtes
de ce dépôt qui citent leurs chiffres mesurés). Honnêtement : la cause du ralentissement Vulkan sur
cette scène n'est PAS encore confirmée par une mesure, seulement par un raisonnement de bytecode qui
identifie un suspect plausible.

## Le sélecteur de moteur graphique — pas `Nuancier` (faux nom), mais `preferredGraphicsBackend`

`client/upscale/Nuancier.java` (1046 lignes, lu en tête) n'est PAS un sélecteur Vulkan/OpenGL : c'est
le chargeur de nuanciers de shaders de post-traitement déposés par un joueur (packs de ressources),
sans aucun rapport avec le choix du backend graphique. Fausse piste du mandat transmis — le vrai
mécanisme vit ailleurs, dans `client/Reveil.java` et `client/Vigie.java` :

- **Le choix du backend est un réglage 100% vanilla**, `preferredGraphicsBackend` dans
  `options.txt`, lu par `net.minecraft.client.PreferredGraphicsApi#getBackendsToTry` — vérifié par
  lecture directe (citée dans le Javadoc de `Vigie`) : `DEFAULT` essaie TOUJOURS `GlBackend` en
  premier, quel que soit le matériel disponible ; seule la valeur explicite `"vulkan"` inverse
  l'ordre.
- **`Reveil.install()`** pose `preferredGraphicsBackend:"vulkan"` (et `enableVsync:false`) — mais
  UNIQUEMENT si `options.txt` n'existe pas encore (tout premier lancement d'une instance neuve).
  Toute instance déjà provisionnée avant ce mécanisme, ou tout joueur qui a un `options.txt`
  préexistant, reste sur `DEFAULT` → OpenGL en pratique.
- **`Vigie.rapporte()`** journalise un avertissement `[LANTERNE][GPU]` explicite chaque fois que le
  backend réellement actif diverge de ce qui était demandé — et cite, dans son propre Javadoc de
  classe, **trois sessions réelles journalisées** où le jeu a démarré sur OpenGL + iGPU AMD sans la
  moindre erreur, alors qu'un GPU Vulkan capable (RTX 3080 Laptop) était détecté et préféré par la
  sonde de disponibilité.

**Verdict honnête sur la question posée** : contrairement à l'hypothèse du mandat (« Vulkan
seul a été testé faute de bascule facile »), c'est l'INVERSE qui est vérifiable dans ce dépôt —
OpenGL est le backend vanilla PAR DÉFAUT, facilement sélectionnable (réglages vidéo en jeu, ou la
clé `preferredGraphicsBackend` dans `options.txt`), et il a été exécuté et journalisé à plusieurs
reprises, y compris involontairement. Le banc RING (10 000 vaches) cité par les deux mixins de
diagnostic est une comparaison directe Vulkan-vs-OpenGL déjà menée. Le déséquilibre réel est plutôt
qu'aucune bascule *pratique en jeu* n'existe côté Lanterne pour comparer les deux à la volée sans
relancer le client (le réglage vit dans `options.txt`, lu une seule fois à `Minecraft.<init>`) — un
vrai axe d'amélioration (ex. commande `/lanterne backend` qui rappelle au joueur qu'un redémarrage
est nécessaire et pourquoi), mais différent de « OpenGL n'a jamais tourné ».

## Correctifs appliqués cette passe

**Aucun.** Deux lacunes réelles ont été identifiées côté OpenGL (cache de pipeline persistant
absent comme son pendant Vulkan pré-`Empreinte` ; diagnostic submit/present possible mais non
écrit), mais aucune des deux ne satisfait le critère « trivialement sûr sans vérification visuelle »
fixé pour cette passe — implémenter un cache de programme GLSL persistant est un vrai chantier
(format, invalidation par version de pilote), pas une garde d'une ligne, et écrire un nouveau mixin
de diagnostic (même inerte par défaut) reste une pièce de code non testée sans compilation +
`verifieMixins` au minimum. Diagnostic seul, documenté avec précision pour qu'une passe future avec
accès client puisse agir directement sur l'un ou l'autre point.
