# Idées d'optimisation — veille du 21/09/2026

**Objet** : trouver des idées *novatrices* d'optimisation (client, shaders, iGPU, serveur/pregen) que Lanterne n'a pas encore, avec leur source, leur licence et un plan de test A/B. Objectif patron : FPS ultime même sous charge, y compris iGPU + shaders.

**Méthode** : API Modrinth (mods « optimization » pour 26.1 / 26.2 / 26.3, triés par nouveauté, téléchargements, mise à jour), pages projet, dépôts GitHub, un test comparatif OpenGL/Vulkan, recherche web sur les techniques de rendu. Lecture du code et du README de Lanterne pour l'inventaire de l'existant. **Aucun code copié** : on retient des principes ; du code n'est réutilisable que sous licence permissive/LGPL avec crédit (voir §6).

**Règle de mesure** (`OBJECTIFS-FPS.md`, règle n°0) : `inactivityFpsLimit:"minimized"`, GPU cité, verrou `C:\bench-lock`, scène de banc au coucher de soleil figé. Les gains ci-dessous sont **ceux annoncés par les auteurs, sur leurs machines** : rien n'est acquis avant mesure sur notre banc.

---

## 1. Résumé — les 10 idées les plus prometteuses

| # | Idée | Pourquoi | Gain annoncé (source) | Licence | Effort |
|---|---|---|---|---|---|
| 1 | **Ixeris** : interrogation des événements sur un fil séparé + lecture groupée de la souris brute (Windows) | La souris haute fréquence peut manger le budget d'image, surtout à 400+ FPS | Souris 8000 Hz : 12 → 121 FPS (×10) ; 2000 Hz : 76 → 135 (×1,78) ; 500 Hz : 134 → 151 (×1,13) [S1] | LGPL-3.0 | XS (ajout au pack) |
| 2 | **Régime de terrain piloté par le GPU sous shaders, en OpenGL** (culling par compute + tracé indirect partagé ombres/écran) | Nvidium se désactive quand Iris utilise des shaders ; Meshelium est Vulkan-only et ne dit rien des shaders → personne ne le fait | Meshelium (hors shaders) : ×1,5 à 12 chunks, ×5,3 à 64 [S2] | idée seule | L |
| 3 | **Résolution interne dynamique + accumulation temporelle sous Iris/OpenGL** | GGTU le fait sous Iris (Fabric, propriétaire) ; Lanterne l'a déjà pour le rendu Vulkan (`client/upscale`) mais pas pour le chemin Iris/OpenGL | « qualité comparable à FSR 4 », frame gen ×2/×3 [S3] | GGTU : propriétaire (idée seule) | M-L |
| 4 | **Diète de la passe d'ombres** (ce qui ne change pas la silhouette n'est pas dessiné : texte, motifs, brillance, couches translucides) + cache/alternance de la shadow map | Le surcoût mesuré des shaders est surtout l'ombre ; FISM prouve le gain, déjà dans Iris 1.11.2 | +10 % (scène simple), ×1,7 (scène pleine de panneaux) [S4] | MIT | S (extension) |
| 5 | **Occlusion des entités / block entities par lancers de rayons multi-points, sans allocation, avec hystérésis** | Vanilla ne fait que le frustum ; l'occlusion réelle est ce qui rapporte | non chiffré par l'auteur [S5][S6] | GPL-3 (VO) / protégée (EntityCulling) → idée seule | M |
| 6 | **Régulateur de travail de chunks piloté par le temps d'image** (maillage/envoi bornés par un budget appris) | Réduit les à-coups à l'exploration ; notre banc mesure des 1 % low | non chiffré [S7] | MIT (FrameGuard) | M |
| 7 | **MMCSS / priorité « jeu » du fil de rendu** (API Windows via Panama) | Lissage sous multitâche, bas de gamme | non chiffré [S8] | MIT | XS |
| 8 | **Techniques de maillage de Meshelium** : fusion de faces (greedy), feuilles enterrées, détails sous-pixel, libération de la copie mémoire du terrain, restitution de mémoire au repos | Charge GPU/mémoire sur iGPU (la VRAM est la RAM) ; le code est **LGPL** donc réutilisable avec crédit | jusqu'à plusieurs Go de mémoire économisés au-delà de 64 chunks [S2] | LGPL-3.0 | M |
| 9 | **Accélération du pregen/chunks côté serveur** : C2ME + ScalableLux + VectorX + zfastsurface/zmaterial | Pregen VPS ≈ 17 chunks/s sur 4 vCPU ; parallélisme et SIMD n'y sont pas | Surface ≈ +40 % [S9] ; VectorX : noyaux SIMD (flag JVM requis) [S10] | MIT/LGPL/MPL-2.0 | S (ajout) puis mesure |
| 10 | **Calibration mesurée au premier lancement** (micro-banc de 20 s, profil enregistré) | Aucun mod ne règle la qualité d'après la mesure de la machine elle-même | idée nouvelle (voir §5) | — | M |

---

## 2. Inventaire des mods et techniques

Légende : **Idée** = principe reformulé ; **Réutilisable** = code sous licence permissive ou LGPL (crédit obligatoire) ; **Interdit** = ne rien copier.

### 2.1 Rendu du terrain
| Projet | Ce qu'il fait | Gain annoncé | Licence | Statut pour nous |
|---|---|---|---|---|
| **Meshelium** (26.2/26.3, NeoForge+Fabric, client) | Dessine le terrain avec des *mesh shaders* (`VK_EXT_mesh_shader`) sur le rendu **Vulkan** de Minecraft ; culling de visibilité *sur le GPU* ; occlusion auto dès 48 chunks ; greedy meshing, feuilles enterrées, culling de détails sous-pixel ; libère la « seconde copie » du terrain en mémoire ; réduit la mémoire au repos ; plafond de distance jusqu'à 120 chunks. Fonctionne **sans Sodium** (il a son propre constructeur de chunks) | 1,50× à 12 chunks, 1,91× à 16, 2,67× à 24, 3,07× à 32, 3,71× à 48, 5,32× à 64 (RX 9070 XT, 1080p, Vulkan contre Vulkan) [S2] | **LGPL-3.0** | Réutilisable avec crédit ; **Vulkan seulement** ; pas de shaders (Iris est inerte sous Vulkan) |
| **Nvidium** (26.1/26.2, Fabric) | Terrain via *mesh shaders* NVIDIA (OpenGL), un seul appel de dessin quel que soit le nombre de chunks ; requiert Sodium | « jusqu'à ×10 » en terrain dense [S11] | LGPL-3.0 | **Se désactive quand Iris utilise des shaders** ; NVIDIA seulement |
| **Nvidium-plus** | Ajoute le rendu **instancié** des entités : un tampon GPU persistant par type d'entité (position, rotation, animation, équipement), un appel de dessin par type ; mises à jour CPU par mémoire mappée persistante | « significatif » pour élevages, objets au sol, salons [S12] | LGPL-3.0 | Idée (NVIDIA + mesh shaders) |
| **Voxy** | LOD très lointains, OpenGL 4.6, convertit le terrain en LOD à l'exploration ; shaders si le pack le prévoit explicitement | non chiffré | propriétaire | Idée seule (alternative à DH) |
| **cull-leaves / Leaves Be Gone / lcull** | Culling des faces de feuilles / logique de frustum | « énorme » (feuilles) ; lcull « ×2 avec Entity Culling », mais **augmente la CPU** | MIT / MPL-2.0 / LGPL-3 | Feuilles : déjà partiel (`LeafCullMixin`). lcull : écarté (CPU) |

### 2.2 Entités, block entities, particules, interface
| Projet | Idée | Licence |
|---|---|---|
| **Entity Culling** (168 M de téléchargements) | Occlusion par traçage de chemin *asynchrone* pour masquer entités/block entities non visibles | licence protectrice tr7zw → **idée seule** |
| **Camera Culling (VO)** | Lancers de rayons multi-points contre la géométrie de collision, **zéro allocation par image**, hystérésis anti-clignotement, culling des textes de panneau (2 faces), *LOD de textures* pour mobs lointains, protection des boss | GPL-3.0+ → idée seule |
| **MoreCulling** | Culling élargi (faces, entités…) | GPL-3.0 → idée seule |
| **Particle Core** (16 M) | Ne pas dessiner les particules hors champ (« 11/12 du champ sont invisibles »), réductions par type | MIT (réutilisable) |
| **ImmediatelyFast** (124 M) | Regroupement des dessins « immediate mode » (interface, texte, objets, block entities) | LGPL-3.0+ (réutilisable) |
| **Better Block Entities / EBE Reloaded** | Rendu hybride des block entities | LGPL-3.0 |
| **Cull Display Entities** | Boîtes englobantes pour entités d'affichage sans taille | MIT |
| **Optis** | Gestion de la *résidence* des textures en VRAM : suivi d'usage, budget, restitution asynchrone, atlas protégés | MIT (Fabric) |

### 2.3 Cadence, entrées, système
| Projet | Idée | Licence |
|---|---|---|
| **Ixeris** (10,8 M) | Fil séparé pour les événements ; *GetRawInputBuffer* (lecture groupée) en Java pour supprimer le coût JNI ; sur Linux/Wayland/X11 gain plus faible | LGPL-3.0, NeoForge 26.3 |
| **FramePace** | Rétroporte le correctif MC-307596 (mauvais rythme d'images GPU chargé, NVIDIA/Intel, OpenGL, surtout shaders) ; **déjà dans vanilla depuis 26.3 snapshot 9** | MIT |
| **FramePacer** | Limiteur de FPS à « sommeil hybride » plus précis ; ne fait pas monter les FPS, lisse les temps d'image ; < 5 Ko | MIT |
| **FrameGuard** | Adapte le travail de chunks au temps d'image, au mouvement et à la charge | MIT |
| **Windows Priority Boost** | Enregistre le fil principal auprès de MMCSS (« Games ») | MIT |
| **Dynamic FPS** | Ressources réduites en arrière-plan / inactif / sur batterie | MIT (vanilla a déjà `inactivityFpsLimit`) |
| **Sodium FPS Cap Fix** | Retire le plafond de 260 FPS | Unlicense (le réglage « Unlimited » de vanilla suffit ; `maxFps:260` = illimité) |

### 2.4 Shaders et petits GPU
| Projet | Idée | Licence |
|---|---|---|
| **GGTU** | Rend à résolution interne réduite puis reconstruit (algorithme MEASU + accumulation temporelle) ; frame generation ×2/×3 ; **fonctionne sous Iris** ; Fabric, 1.21.11 → 26.2 | propriétaire → idée seule |
| **Upscaled** | DLSS Super Resolution ou FSR 3.1.4 en Vulkan ; fournit mouvement, profondeur, gigue sous-pixel ; **vecteurs de mouvement reconstruits depuis la profondeur** (fantômes possibles) ; **incompatible avec Iris** ; le coût fixe peut le rendre plus lent que le natif à 1080p peu chargé [S13] | propriétaire → idée seule |
| **FISM** | Passe d'ombres : ne pas dessiner texte de panneau, motifs de bannière, brillance d'enchantement (la couleur n'influe pas sur l'ombre) ; **intégré à Iris 1.11.2** | MIT |
| **Fornax** | Compagnon Vulkan de Sodium : atlas séparés pour normales/spéculaires (packs 256× sur petit matériel), rendu différé, AO/SSR/TAA, **format de pack de shaders propre (TOML + GLSL)** | MIT |
| **ArtVK** | Backend Blaze3D Vulkan 1.0 pour vieux GPU/mobile ; distance limitée à 8 chunks ; mémoire plus haute (mises à jour de descripteurs) | PolyForm Shield → idée seule |
| **Conseils de packs** (Complementary Reimagined) | Distance d'ombre 16 → 8-10 chunks : **+15-20 %** sur GPU moyen ; résolution d'ombre 2048 ; AO en dernier recours ; la distance de rendu pèse le plus [S14][S15] | — |

### 2.5 Serveur, génération de chunks, pregen
| Projet | Idée | Licence |
|---|---|---|
| **C2ME** (37 M ; version NeoForge séparée) | Génération, E/S et chargement de chunks **parallèles** sur plusieurs cœurs ; conseillé avec Lithium + ScalableLux | MIT |
| **ScalableLux** (13,9 M ; NeoForge 26.3) | Moteur de lumière dérivé de Starlight, mises à jour parallèles | LGPL-3.0 |
| **VectorX** (26.2/26.3) | Boucles chaudes réécrites avec l'API Vector du JDK (AVX2/AVX-512/NEON) : densité de bruit, dépaquetage de palettes, canyons ; **chaque noyau a un jumeau scalaire** ; requiert `--add-modules=jdk.incubator.vector` ; cible worldgen et E/S, pas les FPS | LGPL-3.0 |
| **zfastsurface** (26.3) | Constructeur de surface réécrit (paquets de données), « ≈ 40 % plus rapide », parité vanilla | MPL-2.0 |
| **zmaterial-rule-compiler** | Compile les règles de matériaux en une seule fonction statique | MPL-2.0 |
| **Alternate Current / Lithium / VMP / KryptoN / ServerCore** | Redstone, logique, réseau, serveur | MIT/LGPL |

---

## 3. Ce que Lanterne fait déjà (inventaire vérifié dans le dépôt)

- **Profilage et bancs** : `Radiographie`, `Guet`, `Sonde`, `Vigie`, `Sampler`, `Snap` (capture d'écran), `Vertige` (caméra qui tourne seule), `GlSync`, `Conformance`. Le README rappelle « treize modules écrits, mesurés, puis retirés » : la règle maison est *mesurer avant de garder*.
- **Rendu** : format de sommet étendu (`client/terrain/TerrainVertexFormat`, 52 octets), `Greedy` (fusion des faces du dessus, périmètre étroit : −9,1 % de sommets, **aucun gain d'image mesuré**, scène limitée par le CPU), `Jointure`, `EntityCullMixin` (frustum vanilla, réécrit sans allocation : −13,8 % de temps d'image caché dans `LambdaForm`), `LeafCullMixin`, `ParticleVeilMixin`, `StaticChestRendererMixin`, `FluidRendererMixin`, `SectionCompilerMixin`, `RenderPipelinesMixin`.
- **Vulkan** : cache de pipelines (`Empreinte`), tampon `Réserve`, détection des extensions brutes (287 sur la RTX 3080) dont `VK_EXT_mesh_shader`, **aucun rendu mesh shader** (« preuve de concept hors-jeu »), repli silencieux vers l'iGPU corrigé (`Reveil`).
- **Image temporelle** : `client/upscale/*` (accumulation type FSR2 : `Accumulate`, `Reproject`, `Resolve`, `Jitter`, `Gbuffer`…), activée par défaut (`lentille_temporelle`) dans le rendu **Vulkan** vanilla ; pas dans le chemin Iris/OpenGL.
- **Serveur** : Marée, Cadence, Pregen, foule, cerveau des créatures, compression réseau, etc. (voir README). Le README conclut « Worldgen ×7 : pas de gros poisson » (`BlendedNoise` = jusqu'à 40 évaluations de Perlin 3D/point) — VectorX et zmaterial visent précisément ce genre de noyau et méritent un test.
- **Vanilla 26.3 fait déjà** (vérifié par l'audit) : arènes de tampons + allocateur TLSF, tracé indirect, graphe d'occlusion, maillage sur threads, backends OpenGL **et** Vulkan.

**Ce qui manque à Lanterne** (candidats) : occlusion des entités/block entities, gouverneur de travail de chunks, MMCSS, extraction native de la souris (Ixeris), terrain piloté par le GPU sous shaders, échelle dynamique sous Iris, calibration mesurée, libération mémoire du terrain.

---

## 4. Les mods du pack : ce qu'ils font et ce que Lanterne peut absorber

Pack `test 1` (celui des amis) : **DH, Sodium, Lanterne, JourneyMap, JEI, EMF, ETF, MouseTweaks, Rinku, durabilitytooltip, mezz/supermartijn config** — **aucun mod d'optimisation** en dehors de Sodium. L'ancien pack `Club Citrouille` (26.1) avait ImmediatelyFast, Entity Culling, MoreCulling, BadOptimizations, FerriteCore, ModernFix, Lithium, Chloride, FPSReducer, BetterFPSDist, Immersive Optimization, adaptive_performance_tweaks, smoothchunk… **Hypothèse à tester** pour « 800 → 300-400 FPS » : les amis comparent avec un ancien pack qui avait ces mods (et pas DH ni Iris). Bisect par mod recommandé (voir §6, ligne P0).

| Mod | Licence | Absorbable nativement dans Lanterne (« comme Iris ») ? |
|---|---|---|
| ImmediatelyFast | LGPL-3.0+ | Oui, avec crédit et sources |
| Ixeris | LGPL-3.0 | Oui, ou simple ajout au pack |
| ModernFix | LGPL-3.0 | Oui (à trier : Lanterne couvre déjà une partie) |
| FerriteCore, BadOptimizations, Dynamic FPS, Particle Core, FramePacer, Windows Priority Boost, FISM | MIT | Oui |
| Entity Culling | protective | **Non** (idée d'occlusion seulement) |
| MoreCulling, Camera Culling (VO) | GPL-3.0 | **Non** sauf si Lanterne passe en GPL (idée seule) |
| Chloride, AI-Improvements, Voxy, GGTU, Upscaled | propriétaire | **Non** |
| Sodium, ArtVK | PolyForm Shield | **Non** (salle blanche) |

---

## 5. Les 3 idées vraiment nouvelles

**N1 — Culling piloté par le GPU *sous shaders*, en OpenGL, pour tous les GPU (y compris iGPU).**
Constat : Nvidium se coupe dès qu'Iris a un shaderpack ; Meshelium exige Vulkan (où Iris est inerte) et ne documente pas les shaders. Le créneau « visibilité sur GPU + tracé indirect **avec** un shaderpack » est vide. Piste : un passage de *compute* (OpenGL 4.3, sans mesh shader donc valable AMD/Intel/iGPU) qui construit une pyramide de profondeur (Hi-Z) de l'image précédente, rejette les sections cachées et écrit une liste de tracé indirect ; **la même liste sert aux ombres** (rejet supplémentaire par volume de lumière), au lieu de deux parcours CPU par image. Gain visé : le temps CPU du parcours du graphe (déjà mesuré comme levier des ombres) et la charge de sommets cachés. Risque : latence d'une image (popping) et compatibilité des tampons vanilla 26.3 (`UberGpuBuffer`) — à prouver.

**N2 — Un gouverneur de qualité fondé sur le *coût mesuré par passe*, pas sur le seul FPS.**
Les mods existants réduisent une variable (GGTU : la résolution ; Dynamic FPS : la cadence). Piste : lire le coût GPU de chaque passe (requêtes de temps en anneau non bloquant : terrain, ombre, composite Iris, DH) et le coût CPU par phase (profileur intégré), puis répartir la réduction là où elle coûte le moins en qualité visible : d'abord la fréquence de mise à jour de la shadow map, puis la distance d'ombre, puis la résolution interne, puis les LOD DH ; avec hystérésis et objectif de **1 % low**, pas de moyenne. Lie `Radiographie`/`[BILAN]` au régulateur `client.regul`.

**N3 — Calibration au premier lancement par un micro-banc dans la scène de banc.**
Jeux et mods règlent « d'après le modèle du GPU ». Piste : au premier lancement (ou à la demande), Lanterne rejoue 20 secondes la scène de banc (coucher de soleil figé, caméra `Vertige`), mesure FPS/1 % low pour 3-4 réglages candidats (distance d'ombre, résolution interne, distance de rendu) et **enregistre le profil de cette machine**. La Mèche peut ensuite fournir un *profil serveur recommandé* par classe de GPU en point de départ. Coût : ~1 minute ; gain : profil adapté à la machine réelle (portable, iGPU, hybride NVIDIA+AMD comme le banc).

---

## 6. TOP 12 à implémenter — plans de test A/B

Tous les tests : règle n°0, GPU cité (RTX pour les bancs classiques, AMD pour l'iGPU), scène de banc à l'heure figée 12500, verrou, moyenne + 1 % low + ms/image, 60 s après chauffe, 3 répétitions.

| # | Élément | Base A / variante B | Mesure et critère |
|---|---|---|---|
| P0 | **Bisect de mods du pack** (hypothèse « ancien pack plus rapide ») | A : pack test 1 ; B : + ImmediatelyFast, + Ixeris, + BadOptimizations, + FerriteCore… un par un ; C : sans DH | Δ moyenne/1 % low par mod ; retenir tout gain ≥ 5 % |
| P1 | **Ixeris** (ajout ou intégration) | A/B avec/sans, entrées souris **synthétiques** (petit programme `SendInput` à 1000/4000/8000 Hz, caméra qui tourne) sur Windows | Gain de FPS pendant le mouvement ; ≥ +5 % à 1000 Hz pour valider |
| P2 | **Diète d'ombres** (extension de FISM : entités/BE petits et lointains hors passe d'ombre, cache d'ombre) — coordonné avec l'agent des ombres | A : shaders, ombres complètes ; B : diète | ms de la passe d'ombre (requête GPU), FPS ; objectif ≥ −20 % de temps d'ombre |
| P3 | **Échelle dynamique + accumulation sous Iris/OpenGL** | A : shaders 100 % ; B : 75 / 60 / 50 % avec reconstruction | FPS iGPU ; captures lues (flou, fantômes) ; objectif O6 |
| P4 | **Profil auto d'options de pack** (distance d'ombre 8-10 au lieu de 16, AO réduit) | A : défauts ; B : profil petite config | FPS iGPU ; attendu +15-20 % [S14] |
| P5 | **Occlusion entités/block entities** (idée Entity Culling/VO, code maison, zéro allocation, hystérésis) | scène `/lanterne demo` (foule/coffres) et scène ouverte | FPS et temps du rendu des entités (Radiographie) ; pas de clignotement (captures) |
| P6 | **Gouverneur de chunks piloté par le temps d'image** | A : vanilla ; B : gouverneur, vol rapide en terrain neuf (caméra `Vertige`+déplacement) | 1 % low et pires creux ; objectif ≥ +20 % du 1 % low |
| P7 | **MMCSS / priorité de jeu** via Panama | A/B avec charge CPU de fond simulée (autre processus à 50 %) | 1 % low sous charge |
| P8 | **Maillage** : compléter le greedy (faces latérales, 2ᵉ axe), feuilles enterrées, sous-pixel — techniques Meshelium (LGPL) | A : sans ; B : avec, scène limitée par le GPU (iGPU) | Sommets émis, ms GPU, FPS iGPU ; l'ancien essai n'a rien donné sur une scène limitée par le CPU → tester sur iGPU |
| P9 | **Mémoire** : libérer la copie du terrain (vérifier par `javap` que vanilla 26.3 la garde), restitution au repos, résidence des textures (Optis) | A/B, RAM et tas mesurés (`[BILAN]`) | Go économisés à 32/64 chunks ; 1 % low sur iGPU (RAM partagée) |
| P10 | **Pregen** : C2ME + ScalableLux + VectorX (+ flag JVM) + zfastsurface/zmaterial sur le serveur jetable limité à 4 cœurs | A : actuel ; B : ajouts un par un, même graine | chunks/s (actuel ≈ 17,5) ; parité de génération (comparaison de régions) |
| P11 | **Terrain piloté par GPU sous shaders** (idée N1), prototype hors-jeu puis branché | A : parcours CPU ; B : compute + indirect | Temps CPU/GPU du terrain et des ombres ; gain à valider avant d'investir |

Idée écartée ou différée : **Meshelium en l'état** — Vulkan-only, donc **sans shaders**, et Vulkan est aujourd'hui **plus lent qu'OpenGL sur AMD/Intel** (−32 % à −58 % sur RX 9070 XT et Arc B580 dans un banc de 26.2-snapshot-1 ; NVIDIA : +21 à +31 % en Vulkan) [S16]. À retenir : sur NVIDIA seulement, mode « sans shaders » Vulkan + mesh shaders comme profil optionnel ; sur l'iGPU AMD du banc, **rester en OpenGL**. Ces chiffres datent d'un snapshot précoce : à remesurer avec la version actuelle.

---

## 7. Licences — synthèse

- **Réutilisables avec crédit** : MIT (FerriteCore, BadOptimizations, Dynamic FPS, Particle Core, FramePacer, Windows Priority Boost, FISM, FrameGuard, Optis, C2ME, Fornax), MPL-2.0 (zfastsurface, zmaterial : fichiers modifiés à publier), LGPL-3.0 (Ixeris, Meshelium, Nvidium, ImmediatelyFast, ModernFix, ScalableLux, VectorX, DH, Iris — compatible avec l'Iris déjà intégré ; sources à fournir).
- **Idée seule** : GPL-3.0 (MoreCulling, Camera Culling VO), licence protectrice (Entity Culling), propriétaire (GGTU, Upscaled, Voxy, Chloride, AI-Improvements), **PolyForm Shield** (Sodium, ArtVK).
- Chaque réutilisation LGPL/MIT/MPL exige : crédit dans le README (section « Remerciements ») et le fichier d'attribution, en-tête de fichier, conservation de la licence.

---

## 8. Limites de cette veille

- Les gains chiffrés viennent des auteurs (machines et scènes différentes) : **aucun n'est prouvé sur notre banc**.
- Beaucoup de projets ciblent Fabric/26.2 ; leur disponibilité NeoForge 26.3 est à vérifier au cas par cas (Ixeris, Meshelium, ImmediatelyFast, ScalableLux, FramePacer sont indiqués NeoForge/26.3 sur Modrinth).
- « Cette idée est nouvelle » signifie « je n'ai pas trouvé d'implémentation publique équivalente » dans les résultats consultés, pas une preuve d'absence.
- Le banc OpenGL/Vulkan cité date d'avril 2026 (26.2-snapshot-1).
- Non lu : Reddit/Discord (accès direct impossible), code source de ces projets (seules descriptions et README).

---

## 9. Sources

- [S1] Ixeris — https://modrinth.com/mod/ixeris · https://github.com/decce6/Ixeris
- [S2] Meshelium — https://modrinth.com/mod/meshelium · https://github.com/ded811/Meshelium
- [S3] GGTU — https://modrinth.com/mod/ggtu
- [S4] FISM — https://modrinth.com/mod/fism · https://github.com/maDU59/FasterIrisShadowMapper
- [S5] Entity Culling — https://modrinth.com/mod/entityculling · https://github.com/tr7zw/EntityCulling
- [S6] Camera Culling (Vanilla Outsider) — https://modrinth.com/mod/vo-camera-culling · https://github.com/Rifaditya/Vanilla-Outsider-Camera-Culling
- [S7] FrameGuard — https://modrinth.com/mod/frameguard
- [S8] Windows Priority Boost — https://modrinth.com/mod/windows-priority-boost · https://github.com/girlbossdev/MinecraftMMCSS
- [S9] zfastsurface — https://modrinth.com/mod/zfastsurface · zmaterial-rule-compiler — https://modrinth.com/mod/zmaterial-rule-compiler
- [S10] VectorX — https://modrinth.com/mod/vectorx · https://github.com/etil2jz/VectorX
- [S11] Nvidium — https://modrinth.com/mod/nvidium · https://github.com/MCRcortex/nvidium
- [S12] Nvidium-plus — https://github.com/Scratchergit/Nvidium-plus
- [S13] Upscaled — https://www.curseforge.com/minecraft/mc-mods/upscaled
- [S14] Conseils Complementary — https://mctoolbox.net/shader/complementary-reimagined · https://shaderlabs.org/wiki/Performance_Tips
- [S15] Guide shaders bas de gamme — https://apexminecrafthosting.com/guides/general/minecraft-shaders-for-low-end-pcs/
- [S16] OpenGL vs Vulkan (26.2-snapshot-1) — https://nemez.net/posts/20260410-minecraft-snapshot-opengl-vs-vulkan-nvidia-amd-intel/
- Autres : FramePace https://modrinth.com/mod/framepace · FramePacer https://modrinth.com/mod/framepacer · Optis https://modrinth.com/mod/optis · Fornax https://modrinth.com/mod/fornax · ArtVK https://modrinth.com/mod/artvk · Voxy https://modrinth.com/mod/voxy · C2ME https://modrinth.com/mod/c2me-fabric · ScalableLux https://modrinth.com/mod/scalablelux · Particle Core https://modrinth.com/mod/particle-core · ImmediatelyFast https://modrinth.com/mod/immediatelyfast · API Modrinth https://api.modrinth.com/v2/search
