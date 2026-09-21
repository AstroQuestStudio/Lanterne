# Écosystème Sodium : extensions, variantes et mods de perf — analyse pour Lanterne

Date : 21/09/2026. Auteur : agent de veille (salle blanche : aucune ligne de code de Sodium ni d'un projet incompatible n'a été recopiée ; on décrit des **mécanismes** et on relève des **licences**).
Étiquettes : **[J]** lu dans un jar de l'instance du patron · **[M]** API Modrinth (fiche/versions) · **[G]** GitHub · **[L]** grep dans le code de Lanterne · **[H]** hypothèse non vérifiée.

## 0. Résumé (à lire en premier)

1. **Lanterne est sous GPL-3.0-only** (`gradle.properties:15 mod_license=GPL-3.0-only`, fichier `LICENSE` = texte GPLv3 [L]). C'est la règle qui décide de ce qu'on peut intégrer : MIT, Apache-2.0, LGPL-3.0, MPL-2.0 et GPL-3.0 sont **compatibles** ; « tous droits réservés », PolyForm Shield, « tr7zw Protective License » (non commercial), « Lambda License » (non FOSS) et OSL-3.0 **ne le sont pas** → idées seulement.
2. **Le pack actuel (`test 1`) n'embarque AUCUN mod de perf hors Sodium** [J]. L'ancien pack en avait beaucoup. C'est la première hypothèse sur « 800 → 300-400 FPS » (déjà notée dans IDEES-OPTIMISATION.md) ; elle se teste sans écrire une ligne de code.
3. **Sept mods de perf ont une version 26.3 NeoForge publiée cette semaine** [M] : ImmediatelyFast 1.17.1, BadOptimizations 2.4.1, FerriteCore 9.0.0, EntityCulling 1.11.1, Dynamic FPS 3.11.10, Ixeris 4.6.7, Lithium 0.26.1 (détail §2). **Pas de version 26.3** pour ModernFix, Exordium, Nvidium, Particle Rain, Immersive Optimization ; FISM est déjà **dans l'Iris de Lanterne** [L].
4. **Sodium Extra + Reese's Sodium Options + Chloride + Shadowy Path Blocks sont des greffons de Sodium** : ils disparaissent avec lui. Leurs *fonctions* (particules, animations, ciel, brouillard, toasts, distance des cadres…) sont pour l'essentiel des mixins sur du **vanilla** : elles se réécrivent proprement dans Lanterne ; Sodium Extra est LGPL-3.0 (réutilisable avec crédit sous notre GPL).
5. **Ce qui manque le plus à Lanterne** (preuves grep §5) : FPS hors focus/inactivité (Dynamic FPS), cache du lightmap (BadOptimizations), animations de textures seulement si visibles, filtrage des particules **par type**, réglages ciel/brouillard/entités par type, batching du rendu immédiat (ImmediatelyFast), entrées brutes groupées (Ixeris).

## 1. Cadre juridique

| Licence du projet | Compatible avec Lanterne (GPL-3.0-only) ? | Ce qu'on peut faire |
|---|---|---|
| MIT, Apache-2.0, MPL-2.0 | oui | copier/adapter avec crédit et notice de licence |
| LGPL-3.0 (-only / -or-later) | oui | idem ; le fichier repris reste LGPL, l'ensemble reste GPL |
| GPL-3.0-only / -or-later | oui | idem (même famille) |
| « tr7zw Protective License » (EntityCulling) | **non** : interdit un avantage commercial, restriction supplémentaire | idées seulement ; ne pas redistribuer le jar nous-mêmes (passer par le téléchargement officiel) |
| « Lambda License » (LambDynamicLights) | **non** (licence maison, « pas FOSS » de l'aveu du texte [G]) | idées seulement |
| All Rights Reserved (Voxy, Chloride, betterfpsdist, smoothchunk, FPS Reducer) | **non** | idées seulement |
| PolyForm Shield (Sodium) | **non** | idées seulement (voir ANALYSE-SODIUM-ET-ECARTS.md) |
| OSL-3.0 (Zume) | non (incompatible avec la GPL) | idées seulement |

Cas particuliers : **MoreCulling** est GPL-3.0-only [J] mais l'auteur écrit sur sa page « I don't allow clients or other mods to merge this mod without permission » [M] → demander l'accord avant de reprendre du code ; sinon idées seulement. **Exordium** est GPL-3.0-only [M] mais non maintenu et sans version 26.3.

## 2. Inventaire des projets

Le patron a dans `Instances\Club Citrouille\mods` des builds **26.1.2** [J] (donc plus anciens que 26.3). La colonne « 26.3 NeoForge » vient de l'API Modrinth (filtre `game_versions=26.3`, `loaders=neoforge`) [M].

### 2.1 Greffons de Sodium (inutiles sans lui)

| Projet | Apporte | Mécanisme | Licence (vérifiée) | 26.3 NeoForge | Dépend de Sodium |
|---|---|---|---|---|---|
| **Sodium Extra** | ~60 réglages (voir §4) | mixins sur vanilla + config greffée dans l'écran de Sodium | LGPL-3.0 [J: `LICENSE.txt`] | 0.9.4 (16/09) | oui |
| **Reese's Sodium Options** | remplace l'écran d'options de Sodium (une page, recherche) | interface | MIT [J] | 2.2.4 | oui |
| **Sodium Options API** | API d'options pour addons | bibliothèque | LGPL-3.0 [M] | non (1.21.x seulement) | oui |
| **Chloride** | « rend Sodium agréable » (améliorations d'interface) | greffon | **ARR** [J] | 26.1.2 seulement | oui (`modId=sodium` requis [J]) |
| **Sodium Shadowy Path Blocks** | rétablit l'ombrage doux des blocs non pleins (chemins de terre) sous Sodium | correctif du rendu de sodium | LGPL-3.0 [M] | 8.0.0 | oui |
| **Indium** | compat Fabric Rendering API | — | Apache-2.0 | non (Fabric, 1.21.x) | — (inutile sur NeoForge) |
| **Embeddium** | fork de Sodium pour NeoForge | moteur de terrain | LGPL-3.0 [M] | non (dernière 1.21.4) | — |
| **Sodium Dynamic Lights** | lumières dynamiques sous Sodium | — | MIT [M] | non (1.21.x) | oui |
| **LambDynamicLights** | lumières dynamiques (la plus complète) | mixins + éclairage | **Lambda License** [G] | 4.13.0 | non (autonome) |

### 2.2 Culling et rendu

| Projet | Apporte | Mécanisme | Gain annoncé | Licence | 26.3 NeoForge |
|---|---|---|---|---|---|
| **EntityCulling** | masque entités/block entities invisibles (murs) | lancer de rayons asynchrone sur des cœurs libres, réutilisé toutes les quelques ticks [M] ; aussi réduction des ticks des entités cachées | « peut améliorer drastiquement » selon le nombre d'entités (pas de chiffre) [M] | tr7zw Protective [J] | 1.11.1 |
| **MoreCulling** | culling supplémentaire : feuilles, cadres, `blockstate`, profondeur, vertical, « gap » [J: options de langue] | filtrage par état de bloc/faces cachées | non chiffré | GPL-3.0-only [J] (mais « pas de fusion sans permission » [M]) | 1.9.0-beta.1 |
| **Cull Less Leaves** | supprime les faces de feuilles cachées quand on regarde | — | — | LGPL-3.0 [M] | non |
| **ImmediatelyFast** | accélère le **rendu immédiat** : entités, block entities, particules, texte, GUI/HUD, cartes ; « un tampon maison qui regroupe les appels et envoie les données plus efficacement » [M] | batching + tampons personnalisés | tableaux de comparaison sur la page du projet (non reproduits ici) | LGPL-3.0-or-later [J] | 1.17.1 (17/09) |
| **Exordium** | rend le **GUI à basse fréquence fixe** (hotbar à 100+ FPS inutile) [M] | cache du GUI dans une cible | non chiffré ; **non maintenu**, incompatible avec plusieurs mods GUI, ImmediatelyFast (crosshair) [M] | GPL-3.0-only [M] | non |
| **FISM** (Faster Iris Shadow Mapper) | n'affiche pas texte de panneaux, motifs de bannières, lueur d'enchantement pendant la passe d'ombre | 4 mixins | « +10 % » sur un exemple (100/87 FPS, 63/58 de 0,05 % low) [M] | MIT [G] | **déjà dans Iris ≥ 1.11.2**, donc dans Lanterne [L : `AbstractSignRendererMixin`, `BannerRendererMixin`, `ItemStackMixin`, `MapRendererMixin`] |
| **Bobby** | rendu au-delà de la distance serveur (chunks mis en cache côté client) | cache disque de chunks | — | LGPL-3.0 [M] | non |

### 2.3 Fréquence d'images, entrées, fenêtre

| Projet | Apporte | Mécanisme | Licence | 26.3 NeoForge |
|---|---|---|---|---|
| **Dynamic FPS** | réduit FPS, volume et toasts selon l'état : fenêtre active, survolée, non focalisée, invisible, **inactif** (délai réglable), **sur batterie** ; corrige un bug vanilla (CPU de fond trop élevé) ; ne rend plus le monde pendant l'écran de chargement des ressources [M] | détection d'état de fenêtre + plafonds de FPS | MIT [G] | 3.11.10 (16/09) |
| **Ixeris** | entrées **brutes en tampon** et lecture des événements sur un fil séparé [M] | déplace `glfwPollEvents` hors du fil de rendu | LGPL-3.0 [G] | 4.6.7 (17/09) |
| **Windows Priority Boost** | priorité « jeu » du fil de rendu via l'API Windows | appel natif | MIT [M] | Fabric seulement |
| **FrameGuard** | gouverneur de travail de chunks piloté par le temps d'image | budget par image | MIT [M] | Fabric 26.2 |
| **BadOptimizations** | micro-optimisations « qui s'additionnent » : **saute la mise à jour du lightmap si rien n'a changé** (« l'une des parties les plus coûteuses du tick client » [M]), pas de logique de débogage inutile, etc. | cache/annulation de travaux | MIT [J] | 2.4.1 (16/09) ; fichier « -forge » qui tourne sur NeoForge (l'instance en a un en 26.1) |

### 2.4 Moteurs alternatifs

| Projet | Apporte | Licence | 26.3 |
|---|---|---|---|
| **Nvidium** | moteur NVIDIA seul (mesh shaders) pour Sodium | LGPL-3.0 [M] | non (Fabric, 26.2) |
| **Voxy** | LOD très lointain | **ARR** [M] | Fabric seulement |
| **Meshelium** | rendu par mesh shaders, NVIDIA/AMD/Intel | LGPL-3.0 [M] | 1.6.1 (21/09), **Vulkan seulement** d'après IDEES-OPTIMISATION.md |

### 2.5 Logique de jeu, mémoire, serveur

| Projet | Apporte | Licence | 26.3 NeoForge |
|---|---|---|---|
| **Lithium** | optimisations de la logique du jeu (client solo et serveur) | LGPL-3.0-only [J] | 0.26.1 |
| **FerriteCore** | mémoire (états de blocs, modèles) | MIT [J] | 9.0.0 |
| **ModernFix** | démarrage, mémoire, correctifs | LGPL-3.0 [J] | **non** |
| **ScalableLux** | lumière rapide (basé sur Starlight) | LGPL-3.0 [M] | 0.3.0-alpha |
| **C2ME** | génération/chargement de chunks parallèles | MIT [M] | 0.4.2-alpha |
| **ServerCore** | optimisations serveur | MIT [J] | 1.5.20 |
| **Immersive Optimization** | ordonnanceur de ticks d'entités (« doubler le TPS ») | GPL-3.0 [J] | **non** |
| **betterfpsdist, smoothchunk, FPS Reducer** | présents dans l'instance du patron | **ARR** [J] | — (idées seulement) |
| **Particle Rain** | météo en particules, plus jolie | MIT [G] | non |

## 3. Ce que fait vraiment Sodium Extra (le plus gros greffon)

Liste **lue dans le jar** (`assets/sodium-extra/lang/en_us.json` et `sodium-extra.mixins.json`) [J]. C'est le catalogue à couvrir pour « faire tout ce que Sodium Extra fait » :

- **Animations** : eau, lave, feu, portail, sculk, animations de blocs, « tout » (mixins `animation.MixinSpriteContentsAnimationState` et `MixinTextureAtlas`).
- **Particules** : toutes / casse de bloc / éclaboussures de pluie / feux d'artifice / etc. (mixins `particle.*` sur `ParticleEngine`, `ClientLevel`, `FireworkParticle`, `LevelRenderer`).
- **Détails** : soleil, lune, étoiles, ciel, pluie/neige, couleurs de biomes, couleurs du ciel, faisceau de balise (+ hauteur limitée), livre de la table d'enchantement, cadres d'objets, cadres/plaques de nom, peintures, armor stands, pistons (mixins `entity.*`, `sky.*`, `biome_colors.*`).
- **Rendu** : brouillard (distance, début, forme, brouillard global, multi-dimensions, brouillard de nuages, environnements : eau, lave, neige poudreuse, cécité, obscurité), nuages (distance, hauteur, remplacement de hauteur), « **prévenir les shaders** » (mixin `prevent_shaders.MixinGameRenderer`).
- **Mise à jour de lumière** (`light_updates.MixinLevelLightEngine`), **`use_fast_random`**, **`linear_flat_color_blender`**.
- **Fenêtre/FPS** : synchro adaptative (`adaptive_sync.MixinGlDevice`), résolution Mac réduite, résolution plein écran Wayland, affichage FPS/coordonnées/étendu/coin/contraste du texte, **HUD de débogage stable** (intervalle de rafraîchissement).
- **Confort** : toasts (avancées, recettes, système, tutoriel), tooltips avancés, plus de surcouches HUD, **sneak instantané**, **projection Panini**.

Presque tout est du **vanilla + mixins** : la seule partie propre à Sodium est la greffe dans son écran d'options, qui n'aura plus de sens quand Sodium partira.

## 4. Matrice « fonctionnalité → Lanterne ? / vanilla 26.3 ? / manque »

Preuves [L] : `client/Dials.java` (libellés), `mixin/ParticleVeilMixin`, `mixin/BlockEntityVeilMixin`, `mixin/EntityCullMixin` + `core/Horizon`, `client/Foliage`, `client/terrain/{Greedy,Jointure,Guet}`, `client/Gauge`, `client/regard/Longuevue`, `core/Foule`, `core/Hush`, `mixin/ClimatMixin`. Les « 0 fichier » viennent de `grep -rlE` sur `src/main/java/fr` (motifs précis dans le rapport).

| Fonction | Source | Lanterne | Vanilla 26.3 | Verdict |
|---|---|---|---|---|
| Culling entités/block entities derrière un mur | EntityCulling | **oui** : « voile » (`EntityCullMixin`, `BlockEntityVeilMixin`), particules aussi (`ParticleVeilMixin`) | frustum seulement | à **comparer par mesure** (voile ≠ lancer de rayons asynchrone) |
| Faces de feuilles cachées | Cull Less Leaves, MoreCulling | **oui** : `Foliage.java` (feuilles pleines) | non | fait |
| Autres culling de faces (cadres, `blockstate`, profondeur) | MoreCulling | non | non | **manque** |
| Passe d'ombre sans texte/bannières/lueur/cartes | FISM | **oui**, via l'Iris intégré [L] | — | fait |
| Régler distance des créatures/block entities/particules/nuages, rayon météo, mélange de biomes, ombres des entités | Sodium Extra | **oui** : Dials « Créatures », « Blocs-entités », « Particules », « Nuages », « Distance des nuages », « Rayon météo », « Mélange de biomes », « Ombres des entités » | 3 niveaux de particules | partiel |
| Particules **par type**, densité, distance max, éclaboussures de pluie | Sodium Extra | non (0 fichier) | 3 niveaux | **manque** |
| Pluie/neige : ON/OFF, densité | Sodium Extra, Particle Rain | rayon météo seulement | — | **manque** |
| Animations de textures : toggles, **seulement si visibles** | Sodium Extra | non (0 fichier) | non | **manque** |
| Ciel : soleil, lune, étoiles ; nuages (hauteur) | Sodium Extra | non | non | **manque** |
| Brouillard : distance, début, forme, environnements | Sodium Extra | non (hors mise à l'échelle) | non | **manque** |
| Entités par type : cadres, peintures, armor stands, pistons, balise, table d'enchantement, plaques de nom | Sodium Extra | non (0 fichier pour cadres/toasts) | non | **manque** |
| Mises à jour de lumière (toggle), `fast_random` | Sodium Extra | non | non | **manque** |
| **Cache du lightmap** | BadOptimizations | non trouvé | non | **manque (gain « niveau 1 »)** |
| **Dynamic FPS** (focus, idle, batterie) | Dynamic FPS | non (seul `Radiographie` lit le focus) | **partiel** : option `inactivityFpsLimit` (AFK → ~30 FPS, minimisé) | **manque** (et bug vanilla de CPU de fond) |
| Batching du rendu immédiat (HUD, texte, entités, particules) | ImmediatelyFast | seulement `content/painting/Mosaic` | non | **manque (gros chantier)** |
| GUI/minimap à basse fréquence | Exordium | non | non | **manque** (attention JourneyMap) |
| Entrées brutes groupées | Ixeris | `Longuevue`/`ClientConfig` touchent la souris, pas le fil de lecture | non | **manque** |
| Priorité « jeu » du fil de rendu | Windows Priority Boost | non | non | **manque** |
| Synchro adaptative / VSync | Sodium Extra | `Reveil.java` (vsync) | partiel | à vérifier |
| Lumières dynamiques | LambDynamicLights | 0 fichier | non | **manque** (fonction, pas perf ; licence bloquante) |
| Toasts, sneak instantané, Panini, coordonnées HUD | Sodium Extra | non | non | manque (confort, gain nul ou faible) |
| Écran d'options unifié avec recherche | Reese's | **en cours** (branches `feat-ui`, `feat-settings`) | non | en cours |
| Zoom | Zume (OSL-3.0) | **oui** : `Longuevue` | non | fait |
| Logique serveur (entités, lumière, chunks) | Lithium, ScalableLux, C2ME, ServerCore | `Foule`, `Hush`, `Climat`… | non | à traiter à part (VPS) |

## 5. Top 15 à intégrer nativement dans Lanterne

Ordre : gain / effort / risque, en respectant la règle d'iso-qualité de `OBJECTIFS-FPS.md` (niveau 1 = sans perte ; niveau 3 = perte visible **optionnelle**). Les gains sont ceux annoncés par les auteurs, **à mesurer sur notre banc** (règle n°0 : jeu non bridé, GPU cité, scène figée).

| # | Fonction | Niveau | Effort | Licence de la source | Reprise |
|---|---|---|---|---|---|
| 1 | **FPS hors focus / inactif / batterie** | 1 | faible | MIT (Dynamic FPS) | code adaptable avec crédit |
| 2 | **Cache du lightmap** (ne recalculer que si luminosité/effets/dimension changent) | 1 | faible | MIT (BadOptimizations) | adaptable avec crédit |
| 3 | **Animations de textures seulement si visibles** + toggles par type | 1 (visibles) / 3 (toggles) | moyen | LGPL-3.0 (Sodium Extra) | adaptable, fichiers restent LGPL |
| 4 | **Particules par type** : densité, distance max, éclaboussures de pluie | 3, défaut imperceptible | moyen | LGPL-3.0 / MIT (Particle Rain) | adaptable / réécrit |
| 5 | **Batching du rendu immédiat** (HUD, texte, entités, particules) | 1 | **élevé** | LGPL-3.0-or-later (ImmediatelyFast) | adaptable avec crédit ; coordonner avec Iris et `Mosaic` |
| 6 | **Entrées brutes groupées** (fil séparé) | 1 | moyen (natif) | LGPL-3.0 (Ixeris) | adaptable ; gain à 400+ FPS avec souris rapide |
| 7 | **Culling d'entités asynchrone** : comparer le « voile » de Lanterne au lancer de rayons asynchrone | 1 | moyen | **idée seulement** (Protective) / GPL à négocier (MoreCulling) | réécrire |
| 8 | **Réglages ciel/brouillard/nuages** (soleil, lune, étoiles, brouillard, hauteur) | 3 | faible | LGPL-3.0 | adaptable |
| 9 | **Entités par type** : distance/toggles (cadres, peintures, armor stands, pistons, balise, enchantement, plaques) | 3 | faible | LGPL-3.0 | adaptable |
| 10 | **Toggle des mises à jour de lumière** + `fast_random` | 3 / 1 | faible | LGPL-3.0 | adaptable |
| 11 | **Culling de faces supplémentaire** (cadres, profondeur, `blockstate`) | 1 | moyen | GPL-3.0 (MoreCulling), **permission requise** | idée + accord |
| 12 | **Cache de HUD/minimap** à fréquence réduite (JourneyMap est **44 % des allocations** du fil de rendu, JFR de l'agent perf) | 2 | moyen | GPL-3.0 (Exordium, non maintenu) | idée seulement ; réécrire |
| 13 | **Priorité de fil « jeu »** (MMCSS, API Windows) | 1 | faible | MIT | adaptable |
| 14 | **HUD de débogage stable** + coordonnées + FPS | 1 | faible | LGPL-3.0 | recouvre `Gauge` |
| 15 | **Serveur (VPS)** : Lithium, ServerCore, ScalableLux, C2ME en piste séparée, mesurés sur un serveur jetable (VPS à un cœur dans la mémoire du projet) | 1 | à mesurer | LGPL / MIT | installation, pas d'intégration |

Mesure de chaque ligne : job du banc central (`C:\bench\submit.ps1`), scène de banc au coucher de soleil, **avant/après avec le réglage activé puis coupé**, GPU RTX **et** iGPU, `inactivityFpsLimit:"minimized"` (sinon la mesure est fausse). Pour les niveaux 2 et 3 : captures avant/après et PSNR/SSIM (outil `C:\scalebench\Cmp.java` de l'agent iGPU).

## 6. Court terme, SANS développer : remettre des mods de perf dans le pack

Le pack `test 1` n'a plus aucun mod de perf ; sept existent en 26.3 NeoForge [M]. Test recommandé (à faire sur le banc central avant tout déploiement) :

| Mod | Fichier 26.3 | Distribution |
|---|---|---|
| ImmediatelyFast | `ImmediatelyFast-NeoForge-1.17.1+26.3.jar` | LGPL : redistribuable (catalogue de la Mèche) ou manifeste CurseForge |
| BadOptimizations | `BadOptimizations-2.4.1-26.3-forge.jar` | MIT : redistribuable |
| FerriteCore | `ferritecore-9.0.0-neoforge.jar` | MIT : redistribuable |
| Dynamic FPS | `dynamic-fps-3.11.10+minecraft-26.3.0-neoforge.jar` | MIT : redistribuable |
| Ixeris | `Ixeris-4.6.7+26.3-neoforge.jar` | LGPL : redistribuable |
| Lithium | `lithium-neoforge-0.26.1+mc26.3.jar` | LGPL : redistribuable |
| EntityCulling | `entityculling-neoforge-1.11.1-mc26.3.jar` | **ne pas héberger nous-mêmes** (licence protectrice) : manifeste CurseForge / téléchargement officiel |

Limites : (a) **compatibilité avec Lanterne non testée** (mixins voisins : `Foliage`, `ParticleVeil`, `EntityCull`, rendu Iris) ; (b) ces mods se recouvrent en partie avec Lanterne (culling) : mesurer le **cumul**, pas seulement chaque mod ; (c) hébergement : la Mèche v2 sait publier des jars dans `config/lanterne/mods-clients/`, mais publier des jars LGPL/MIT suppose de joindre leurs textes de licence.

## 7. Architecture proposée : un seul mod, sans extensions à installer

1. **Un module « Réglages » unique** (`client.reglages`, déjà en construction dans `feat-settings`) qui porte tous les réglages du §3 et du §5, chaque réglage = interrupteur + valeur + étiquette « perte visible » + effet mesuré ; un mixin par famille, **actif seulement si le réglage s'écarte du défaut** (coût nul à l'état de repos).
2. **Un module « Gouverneur »** : FPS hors focus, cache du lightmap, cache de HUD, priorité de fil, HUD stable — tous de niveau 1.
3. **Un module « Rendu immédiat »** : batching du HUD/texte/entités/particules (ImmediatelyFast), avec un mode de compatibilité pour Iris et pour JourneyMap.
4. **Retrait via la Mèche** quand chaque fonction est couverte et mesurée : `RETIRER.txt` avec `sodium-extra-*`, `reeses-sodium-options-*`, `chloride-*`, `sodium-shadowy-path-blocks-*`, puis `sodium-neoforge-*` **le jour de la porte de sortie de Sodium** (OBJECTIFS-FPS.md). Ces greffons meurent de toute façon au départ de Sodium (dépendance dure) : les retirer **en même temps**, pas avant, sinon le client refuse de démarrer (`sodium` requis par Chloride [J] et Sodium Extra).
5. **Risque de doublon de `modId`** : Lanterne déclare déjà un mod virtuel `iris`. **Ne pas** déclarer de mod virtuel `sodium_extra`/`immediatelyfast` etc. : quand la fonction est native, on retire le jar ; déclarer le même `modId` créerait le conflit « deux mods avec le même identifiant » que le pack `citrouille.zip` a déjà subi avec le jar Iris (voir AUDIT-MODPACK-CITROUILLE.md). Pour rester compatible avec des mods qui *dépendent* d'un `modId` retiré, n'ajouter un mod virtuel que si une dépendance l'exige, et le vérifier dans `neoforge.mods.toml` des autres jars du pack.
6. **Crédit** : chaque fichier adapté d'un projet MIT/LGPL/GPL porte l'en-tête de crédit et la licence ; une section « Remerciements » dans le README (déjà commencée pour Sodium/Iris) et un `NOTICE` listant les projets et leurs licences.

## 8. Ce qui n'a pas été vérifié

- **Aucun gain n'est mesuré sur notre banc** : tous les chiffres viennent des auteurs. ImmediatelyFast publie des tableaux (non reproduits ici).
- Les jars de l'instance du patron sont en **26.1.2**, pas 26.3 ; les options peuvent différer en 0.9.4 (Sodium Extra 26.3).
- **Compatibilité entre ces mods et Lanterne** non testée (aucun client lancé, consigne de la mission).
- **Licences** : vérifiées dans les jars (marqué [J]) ou l'API GitHub/Modrinth ; pour Dynamic FPS, FISM, Ixeris et Particle Rain elles viennent de l'API GitHub (`spdx_id`), pas d'un fichier lu ; Exordium : GitHub ne donne pas de `spdx_id`, on s'est fié à Modrinth.
- « Immersive Optimization sans version 26.3 » : le filtre Modrinth utilise le slug `immersive-optimization` ; le projet peut exister sous un autre slug.
- La liste de recouvrement « Lanterne fait déjà X » repose sur des recherches de texte dans le code : un module peut faire X sous un autre nom.
- Reddit/Discord n'ont pas pu être consultés directement.

## 9. Sources

Jars lus : `Instances\Club Citrouille\mods` (sodium-extra 0.9.3, reeses-sodium-options 2.2.3, entityculling 1.10.5, moreculling 1.7.3, ImmediatelyFast 1.15.3, ferritecore 9.0.0, modernfix 5.27.22, lithium 0.24.7, betterfpsdist 6.2, FpsReducer2 2.16, BadOptimizations 2.4.1, chloride 1.8.1, immersive_optimization 0.2.0, smoothchunk 4.2, servercore 1.5.19).
API Modrinth (fiches, licences, versions 26.3) : https://api.modrinth.com/v2/project/{sodium, sodium-extra, reeses-sodium-options, sodium-options-api, indium, sodium-dynamic-lights, lambdynamiclights, cull-less-leaves, moreculling, entityculling, immediatelyfast, dynamic-fps, nvidium, voxy, bobby, exordium, particle-rain, embeddium, sodium-shadowy-path-blocks, ferrite-core, modernfix, lithium, badoptimizations, ixeris, fism, frameguard, windows-priority-boost, meshelium, zume, chloride, scalablelux, c2me-neoforge, servercore, spark}.
Dépôts : https://github.com/juliand665/Dynamic-FPS · https://github.com/RaphiMC/ImmediatelyFast · https://github.com/fxmorin/moreculling · https://github.com/tr7zw/EntityCulling · https://github.com/tr7zw/Exordium · https://github.com/maDU59/FasterIrisShadowMapper · https://github.com/decce6/Ixeris · https://github.com/PigCart/particle-rain · https://github.com/Rynnavinx/sodium-shadowy-path-blocks · https://github.com/LambdAurora/LambDynamicLights · https://github.com/FlashyReese/sodium-extra-fabric · https://github.com/FlashyReese/reeses-sodium-options · https://github.com/ItsThosea/BadOpitmizations · https://github.com/MCRcortex/nvidium · https://github.com/ded811/Meshelium.
Contexte du projet : `OBJECTIFS-FPS.md`, `IDEES-OPTIMISATION.md`, `TECHNOS-MODERNES.md`, `ANALYSE-SODIUM-ET-ECARTS.md`, `AUDIT-MODPACK-CITROUILLE.md`, `NOTES-TECHNIQUES-RENDU-TERRAIN.md`.
