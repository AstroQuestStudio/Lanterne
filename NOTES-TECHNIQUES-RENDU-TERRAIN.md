# Notes de techniques — rendu de terrain haute performance (salle blanche)

*Rédigé le 21/09/2026 par l'équipe « lecteur ». Destinataires : l'équipe d'implémentation du moteur de
terrain de Lanterne (voir `RENDU-LANTERNE-DESIGN.md`, `AUDIT-IRIS-BESOINS-TERRAIN.md`).*

## 0. Règle de rédaction (à lire d'abord)

Sodium est publié sous **PolyForm Shield 1.0.0** : on ne s'en sert pas pour fabriquer un produit concurrent.
Ce document est donc une **note de principes** : il décrit *ce qui se fait* et *pourquoi c'est rapide*, avec
des mots à nous, des ordres de grandeur publics et des schémas dessinés ici. Il ne contient **aucun code**,
aucun nom interne de classe ou de méthode de Sodium, aucune constante recopiée sans justification
indépendante. Là où un chiffre vient d'une source publique, la source est citée en fin de document (S1…S14) ;
là où l'équipe d'implémentation doit choisir ses propres valeurs, la **dérivation** est donnée pour qu'elles
soient justifiées par notre besoin et non par imitation.

**Limite de méthode (honnêteté).** Je n'ai pas ouvert les fichiers source du dépôt : je me suis appuyé sur la
documentation publique, le wiki, les notes de version, des résumés de dépôt et des articles de référence.
Les résumés automatiques de ces pages peuvent contenir des erreurs ; chaque affirmation chiffrée est marquée
**[sûr]** (dite explicitement par une source primaire), **[probable]** (résumé cohérent avec plusieurs
sources) ou **[à mesurer]** (à vérifier sur notre banc avant d'en dépendre).

---

## 1. Architecture du rendu de terrain

### 1.1 Trois niveaux : bloc → section → région

```
 monde
  └─ région   : grille fixe de sections        (8 × 4 × 8 = 256 sections)   [sûr, S2]
       └─ section : cube de 16³ blocs, unité de maillage et de culling
            └─ sommets/indices d'UNE section, rangés par couche puis par direction de face
```

Pourquoi ce découpage : le coût dominant d'un moteur de terrain naïf est le **nombre d'appels de dessin et de
changements d'état par image**, pas le nombre de triangles. Regrouper 256 sections dans un même espace mémoire
permet de dessiner toute une région en un seul appel groupé.

### 1.2 Arènes de tampons GPU

Idée : au lieu d'un petit tampon par section (des milliers d'objets GPU, coûteux à créer/détruire et à lier),
on réserve de **grands tampons** et on y *sous-alloue* des plages. Une section = « une plage de sommets + une
plage d'indices » dans un tampon partagé.

```
 tampon de sommets (une arène par région)
 ┌────────┬────────┬──░░░░──┬────────┬────────┬──░░──┬──────────┐
 │ sec A  │ sec B  │ trou   │ sec C  │ sec D  │ trou │  libre   │
 └────────┴────────┴────────┴────────┴────────┴──────┴──────────┘
   ▲ un déchargement crée un trou ; un rechargement doit trouver un trou assez grand
```

Problèmes réels et réponses publiques :

| Problème | Réponse décrite publiquement |
|---|---|
| **Fragmentation** : des trous inutilisables s'accumulent pendant qu'on charge/décharge des chunks | Défragmentation **incrémentale** (déplacer quelques plages par image, pas tout d'un coup) [sûr, S3 : la version 0.9.2 annonce un « allocateur multi-arènes à redimensionnement automatique et défragmentation incrémentale »] |
| **Redimensionnement** : agrandir un tampon = copie complète + tous les décalages changent | Réduire le *nombre* de tampons alloués/redimensionnés pendant le chargement de chunks ; mettre à jour les métadonnées de section après une compaction [sûr, S3] |
| **Index partagés** : les quads se dessinent tous avec le même motif d'indices (0,1,2,2,3,0…) | Un seul tampon d'indices partagé pour tout ce qui n'a pas besoin d'ordre spécial ; un tampon d'indices **dédié** seulement pour les sections translucides triées [probable, S2] |
| **Envoi** : copier des données CPU→GPU sans bloquer | Passage par un tampon de *staging*, envois regroupés, budget d'octets par image [probable, S2] |

**Vanilla 26.3 fournit déjà ceci** (voir design §1) : `UberGpuBuffer` + `TlsfAllocator` (allocateur à listes
libres de type TLSF : allocation/libération en temps quasi constant, fragmentation faible). L'équipe n'a donc
**pas** à écrire d'arène ; elle doit seulement (a) mesurer si la défragmentation vanilla est incrémentale,
(b) vérifier l'alignement avec un format de sommet plus large (le stride vanilla est l'alignement de plage).

### 1.3 Dessin groupé indirect

Principe : les paramètres de chaque dessin (nombre d'indices, décalage d'indices, décalage de sommet) ne sont
pas passés un par un par le CPU ; ils sont écrits dans un **tampon de commandes**, et un seul appel dit au GPU
« exécute les N commandes ». Le CPU ne fait plus qu'*écrire un tableau* par région.

```
 CPU : pour chaque section visible d'une région  → écrire 1 commande (5 entiers) dans un tableau
 GPU : 1 appel « multi-draw indirect »            → dessine toutes les sections du tableau
```

Coût CPU par image ≈ proportionnel au nombre de sections **visibles** (une écriture mémoire chacune), au lieu
d'un appel d'API chacune. C'est la source principale du gain « CPU-bound → GPU-bound » observé partout [sûr, S1/S2].

Vanilla 26.3 l'active déjà si le périphérique annonce une capacité d'indirect (`DeviceFeatures`,
`prepareChunkRendersIndirect`). Le module `Guet` de Lanterne a établi par bytecode qu'il est **actif** (design §1).

### 1.4 Couches de rendu

Le terrain est dessiné en plusieurs passes car chaque matériau a ses besoins :

```
 solid       : opaque, pas de mélange, test de profondeur     → dessiné d'abord, ordre indifférent
 cutout      : alpha tout-ou-rien (feuilles, herbe), « discard » → ordre indifférent, un peu plus cher
 translucent : mélange alpha (eau, verre)                    → doit être TRIÉ arrière→avant
```

Un drapeau par section et par couche (« cette section a-t-elle de la géométrie translucide ? ») évite de
même *considérer* les sections vides pour une passe [sûr, S2]. Astuce d'ordre de grandeur : la majorité des
sections n'ont ni eau ni verre → la passe translucide ne touche qu'une petite fraction.

### 1.5 Faces regroupées par direction

Les sommets d'une section sont rangés **par direction de face** (6 directions cardinales + une catégorie
« sans direction » pour les quads obliques), et la métadonnée de section stocke un **compte de sommets par
direction** [sûr, S4 : une structure de 48 octets par section avec sept compteurs].

```
 section = [ face −X | face +X | face −Y | face +Y | face −Z | face +Z | autres ]
                                  ▲ à l'image, ne dessiner que les plages dont la normale peut
                                    faire face à la caméra (cf. §2.4)
```

Gain attendu : en moyenne à peu près **la moitié** des faces d'une section tournent le dos à la caméra ;
sauter leurs plages divise le travail de sommets/rastérisation sur le terrain lointain, sans test par triangle.
Contrepartie : plusieurs commandes de dessin par section (ou un masque appliqué dans le tableau de commandes).

---

## 2. Culling (élimination de ce qui n'est pas dessiné)

### 2.1 Frustum

Test « la boîte de la section coupe-t-elle la pyramide de vue ? » : 6 plans × (au plus) 8 coins, ou test
plan/boîte plus court. Très bon marché ; élimine typiquement environ la moitié de la géométrie [probable, S13].

### 2.2 Graphe de visibilité (occlusion par parcours)

Au lieu de tester chaque section isolément, on **parcourt le monde en largeur depuis la section de la caméra** :

```
 caméra ─► section 0 ─► voisines visibles ─► leurs voisines … (on n'entre dans une voisine
                                              que si une face d'entrée et une face de sortie de la
                                              section courante sont « connectées » par de l'air)
```

Chaque section a, calculé une fois au maillage, un petit tableau « quelles faces communiquent avec quelles
autres ». Le parcours propage cette information : une grotte fermée ou une montagne pleine coupe la
propagation, donc tout ce qui est derrière n'est jamais atteint [sûr, S2 : parcours en largeur + adjacence et
transparence face-à-face]. Coût CPU ∝ nombre de sections *atteintes*, pas de sections chargées. Il peut être
déporté sur un thread [sûr, S2 : exécuteur asynchrone pour le culling].

Vanilla 26.3 possède déjà un graphe d'occlusion (`SectionOcclusionGraph`, `VisibilitySet`). À vérifier :
qualité (faux positifs), coût par image, et possibilité de le faire tourner hors du thread principal.

### 2.3 Distance

Rayon de rendu par section, fondu éventuel à la limite. Intégré au système de priorités du maillage (§3) : les
sections lointaines sont maillées après les proches.

### 2.4 Culling par faces (plans de face)

Combiné à §1.5 : pour une section, la position de la caméra par rapport aux 6 plans de la section indique
quelles directions peuvent être visibles. Test de 6 comparaisons → masque de 6 bits → sélection des plages.

### 2.5 Culling GPU (option avancée)

Deux familles publiques :

* **Compute + Hi-Z** [sûr, S12, S8] : un shader de calcul teste chaque objet contre le frustum puis contre une
  *pyramide de profondeur* de l'image précédente (mipmaps en « maximum »), et écrit **uniquement les survivants**
  dans le tampon de commandes ; un seul dessin indirect les consomme. Défauts documentés : **un cadre de latence**
  sur la pyramide (on agrandit un peu les volumes pour compenser) ; les opérations atomiques de compactage ne
  garantissent aucun ordre → **impossible pour les translucides triés** sans autre mécanisme.
* **Mesh shaders** [sûr, S6, S7] : culling et assemblage de la géométrie dans le GPU, sans aller-retour CPU.
  Le projet Meshelium (terrain par mesh shaders sur le backend Vulkan de MC 26.2/26.3) annonce ×1,5 à 12 chunks,
  ×3,1 à 32 chunks, ×5,3 à 64 chunks — les gains **croissent avec la distance** ; matériel : AMD RX 6000+,
  NVIDIA GTX 16xx+/RTX, Intel Arc [sûr, S6, chiffres de l'auteur, à reproduire chez nous].

À 12 chunks (le réglage des amis) le gain annoncé est modeste (×1,5) : **ce n'est pas la première priorité**,
sauf si le CPU reste le goulot après §1–§4.

### 2.6 Cas des ombres

La passe d'ombres re-dessine le terrain **depuis la lumière**. Les mêmes idées s'appliquent mais avec une
pyramide de vue *orthographique* : frustum de lumière, pas de culling par occlusion depuis la caméra (les
occulteurs qui comptent pour l'ombre sont *entre le soleil et la scène*, pas entre la caméra et la scène),
portée limitée par la distance d'ombre. Voir §6 et §7 (T9, T10).

---

## 3. Maillage

* **Threads** : la construction des sommets d'une section (lecture des blocs, faces cachées, AO, lumière,
  quantification) tourne sur des threads de travail ; le thread principal ne fait que *téléverser* les résultats
  [sûr, S2].
* **Copie du monde** : la tâche travaille sur un instantané des blocs voisins (une tranche de 18³ ou
  équivalent) pour ne pas lire le monde vivant [probable, S2].
* **Priorités et budget par image** : on estime la durée d'une tâche de maillage (selon la complexité des blocs)
  et la durée du téléversement, et on *reporte* ce qui dépasse le budget de l'image, en favorisant ce qui est
  proche de la caméra et ce qui est visible [sûr, S2 : estimateurs de durée + modes de report]. Objectif :
  **pas de pics de latence** (« frame pacing ») plutôt que débit brut [sûr, S3 : correctifs de « severe frame pacing issues »].
* **Tri des translucides** : tâche séparée qui réordonne les quads translucides d'une section selon la position
  de la caméra ; à ne refaire que quand la caméra a changé de « cellule » de tri, pas à chaque image
  [probable, S2 dit « chaque image » ; à mesurer : coût réel].
* **Prévention d'allocations** : tampons de travail réutilisés par thread, données de section en mémoire native
  contiguë plutôt qu'en objets [sûr, S4 : métadonnées par section en allocation native pour éviter le
  « pointer chasing » dans la boucle chaude].

---

## 4. Format de sommet compact

### 4.1 Ordres de grandeur publics

Le format du terrain de Sodium fait **20 octets par sommet** [sûr, S5], contre 28 à 32 pour un format vanilla
non compressé (position 3×float + couleur + uv + lumière + normale…). Les attributs sont quantifiés :
position relative à la région, couleur 8 bits/canal **prémultipliée par l'occlusion ambiante**, UV sur 15–16
bits avec biais pour éviter les saignements de texture aux arêtes, lumière 16 bits, identifiant de matériau
8 bits, index de section 8 bits [sûr, S5, S9].

### 4.2 Pourquoi c'est un gain (raisonnement à refaire chez nous)

Le terrain lointain est limité par la **bande passante de sommets** et par la mémoire GPU. Passer de 32 à 20
octets = **−37 %** de mémoire de sommets et de trafic. Le décodage (décalages, masques, multiplications) se
fait dans le vertex shader, quasi gratuit devant l'économie de bande passante.

### 4.3 Dérivation indépendante pour NOTRE format (ne pas copier les largeurs sans vérifier)

Nos besoins diffèrent (attributs Iris en plus). Point de départ raisonné :

| Attribut | Plage réelle | Résolution requise | Bits minimaux |
|---|---|---|---|
| Position dans la section (16 blocs) | 0..16 (+ marge modèles) | 1/16 bloc pour la plupart, 1/32–1/64 pour certains modèles | 3 × 10–12 bits |
| Région/section (décalage grossier) | 0..127 | 1 section | fourni par uniforme de région (0 bit/sommet) |
| UV atlas | 0..1 sur atlas ≤ 16384 px | sous-pixel 1/16 | 2 × 15 bits |
| Lumière (ciel+bloc) | 0..15 chacun | 4 bits chacun | 8 bits (16 si palette continue) |
| Couleur/teinte + AO | 8 bits/canal ; AO 2–4 bits | — | 24–32 bits |
| Normale | 6 directions + obliques | 3 bits si axiale, 10–15 bits sinon | 3–15 bits |
| **Attributs Iris** : identifiant d'entité de bloc (`mc_Entity`), centre de face (`mc_midTexCoord`, `at_midBlock`), tangente (`at_tangent`) | id ≤ 16 bits ; centre : offset par bloc, 6–8 bits/axe ; tangente : reconstruite | — | ~30–50 bits |

**Conclusion pratique** : viser **24–36 octets** en incluant les données Iris n'a de sens que si on *dérive*
la tangente et le centre dans le vertex shader à partir de la normale et de la position quantifiée (un
« patch » de shader). Iris possède déjà un encodeur de ce type (`XHFPTerrainVertex`, audit §4).

---

## 5. Astuces de performance et pièges

### 5.1 Les pilotes détectent « Minecraft » et changent de comportement (pièges majeurs)

* **NVIDIA — « optimisations threadées »** [sûr, S10, S11]. Le pilote reconnaît le jeu et active
  automatiquement une optimisation qui envoie les appels GL sur un thread séparé. Avec un moteur de terrain
  moderne qui émet déjà peu d'appels, elle ajoute de la latence, provoque des **baisses de fluidité
  « sévères »** et des plantages (versions de pilote citées : 526.47+, 536.23). Contournements décrits :
  (a) désactiver l'option dans le panneau NVIDIA (globalement ou pour le processus Java) ; (b) sous Linux,
  variable d'environnement dédiée à 0 ; (c) sous Windows, **empêcher le pilote de reconnaître l'application**
  (autre lanceur, ou masquer l'identité/la version du jeu au pilote au moment de la création de la fenêtre).
  Sodium applique le point (c) automatiquement [sûr, S11].
* **AMD (pilotes Windows 25.10.2+)** [sûr, S11] : le même mécanisme de reconnaissance applique une
  « optimisation » qui **rend le terrain invisible** ; contournement par modification temporaire de la ligne de
  commande du processus pendant la création de la fenêtre.
* **Intel** [sûr, S10, S11] : plantage sur copie de framebuffer quand la fenêtre perd le focus (Gen8 et
  antérieurs) ; comparaisons de profondeur peu fiables sur vieux pilotes ; Gen11 : scintillement.

**Conséquence pour Lanterne** — corrélation forte avec nos mesures : le journal des clients de test contient
« Command line is already modified » (le contournement de Sodium échoue) **et** l'agent de perf mesure un
écart massif (168,8 FPS sans shaders, 53,6 avec). Ce point est à traiter *avant* toute optimisation de terrain :
sans contournement, la **soumission GL est ralentie quel que soit le moteur** de terrain.
Approche indépendante recommandée : (1) détecter le constructeur du GPU actif ; (2) sur NVIDIA/Windows,
essayer de dissimuler l'identité du processus AVANT la création du contexte (ordre d'initialisation : un autre
composant a peut-être déjà modifié la ligne de commande → le contournement doit tolérer un état « déjà
modifié » et l'idempotence, au lieu d'échouer) ; (3) sinon **alerter le joueur** avec la marche à suivre
(profil du panneau NVIDIA pour `javaw.exe`) ; (4) mesurer avec et sans, dans le banc.

### 5.2 Synchronisation et mémoire

| Technique | Principe | Piège |
|---|---|---|
| Mémoire persistante mappée | Un tampon GPU reste mappé côté CPU ; on écrit sans re-mapper | Écrire une plage encore lue par le GPU → corruption ; il faut des **fences** par tranche |
| Orphaning | Redemander un nouveau stockage pour un tampon utilisé par des commandes en vol | Allocation cachée par le pilote, à surveiller |
| Fences / tampons en anneau | 2–3 tranches de staging qu'on réutilise après signal | Ne pas attendre une fence dans le thread de rendu (stall) |
| Débuts d'image | Éviter `glFinish`/lectures GPU synchrones | Tout `glGet*` qui lit un résultat GPU bloque |
| Uniformes par région | Peu de données par appel (décalage de région, id) → uniformes ou constantes poussées | Trop de changements d'état ⇒ retour du coût CPU |

Ces points ne sont **pas** tous documentés par les sources consultées **[à mesurer]** ; vanilla 26.3 les
gère via `DeviceFeatures.persistentMapping` etc. (design §1).

### 5.3 Tri des translucides : compromis

Trier chaque image coûte cher ; ne le faire que pour les sections proches et seulement quand le déplacement
de caméra dépasse un seuil. Vanilla 26.3 propose aussi un mode « transparence indépendante de l'ordre »
(`executeOit`) : moins précis visuellement dans certains cas mais **sans tri** ; à comparer sur le banc
(compatible avec un culling GPU, contrairement au tri).

---

## 6. Comment Iris se greffe sur ce moteur

```
 image ─► [passe d'OMBRES]  terrain vu depuis la lumière → shadow map(s)
        ─► [gbuffers terrain] solid+cutout → tampons G (couleur, normale, spéculaire…)
        ─► [gbuffers water/translucent]
        ─► [composite / post-traitement plein écran]  (coût indépendant du terrain)
```

Ce qu'Iris exige du terrain (détails : `AUDIT-IRIS-BESOINS-TERRAIN.md`) : des **attributs supplémentaires**
par sommet (id de bloc, centre de face, tangente) ; les **mêmes** données de terrain servies **deux fois**
(ombre + principal) avec des matrices différentes ; un **frustum de lumière** pour l'ombre ; la possibilité de
remplacer les programmes shader par ceux du pack.

Où le temps part (mesuré chez nous, `PERF-SHADERS-AUDIT.md`) : ≈ 9 ms/13,4 ms **de surcoût** avec shaders (×3).
Un résumé public (S8, **[à mesurer]**, source de faible fiabilité) chiffre la passe d'ombres à 20–40 % du budget
GPU sans optimisation. Leviers de gain, du plus sûr au plus ambitieux :

1. **Ne pas retraverser le graphe à chaque image pour l'ombre** : la liste de sections visibles depuis la
   lumière ne change que quand le soleil ou la caméra bougent sensiblement.
2. **Mise en cache de la shadow map** [sûr comme principe, S13 : réutiliser la carte d'ombre quand ni la
   lumière ni la scène n'ont bougé ; mettre à jour les cascades lointaines moins souvent ; étaler les mises à jour
   sur plusieurs images ; « verrouiller » une cascade].
3. **Ombre statique + dynamique séparées** : le terrain (statique) mis en cache, entités/bloc-entités
   (dynamiques) redessinés seuls — principe des cartes d'ombre virtuelles [sûr, S13].
4. **Distance d'ombre adaptative** selon le budget d'image mesuré.
5. **Culling des occulteurs inutiles** : une section ne peut projeter d'ombre visible que si son ombre coupe le
   volume visible → test « rayon depuis la boîte dans la direction de la lumière contre le frustum de vue élargi ».
6. **Réutiliser le maillage entre passes** : les mêmes tampons de sommets servent l'ombre et la passe principale
   (pas de second maillage) — acquis avec des tampons partagés.

---

## 7. Tableau de décision pour l'équipe d'implémentation

Légende gain : ★ faible, ★★ moyen, ★★★ fort. Coût : S = quelques heures, M = ½–1 jour, L = plusieurs jours.

| # | Technique | Gain attendu | Coût | Risque | Dépendances vanilla 26.3 |
|---|---|---|---|---|---|
| **N0** | **Contournement d'identité NVIDIA/AMD robuste** (§5.1) | ★★★ (potentiellement ×2–3 sur NVIDIA Windows) **[à mesurer]** | S | Faible | Aucune (avant création du contexte GL) |
| N1 | Arènes de tampons partagées | acquis | — | — | `UberGpuBuffer`, `TlsfAllocator` (vérifier défrag. incrémentale) |
| N2 | Dessin groupé indirect | ★★★ CPU | acquis (à vérifier pour l'ombre) | Faible | `prepareChunkRendersIndirect`, `DeviceFeatures` |
| N3 | Graphe d'occlusion vanilla, hors thread principal | ★★ | M | Moyen (faux négatifs = trous visibles) | `SectionOcclusionGraph` |
| N4 | Faces par direction + masque de visibilité | ★★ (terrain lointain) | M | Moyen (format de maillage) | `SectionCompiler` (ordre d'écriture des quads) |
| N5 | Format de sommet compact (+ attributs Iris dérivés en shader) | ★★ bande passante | L | Moyen (patch de shaders des packs) | `SectionCompiler`, stride des arènes (§1.2) |
| N6 | Maillage : priorités + budget d'envoi par image | ★★ (régularité) | M | Faible | `SectionRenderDispatcher` (budget/threads) |
| N7 | Tri des translucides borné (seuil de déplacement) ou OIT | ★ | S | Faible | `executeOit` |
| **N8** | **Ombre : liste de sections cachée (soleil/caméra)** | ★★★ (prévu −3 à −6 ms) **[à mesurer]** | M | Moyen (clignotements si seuil trop large) | `ChunkSectionsToRender`, frustum de lumière |
| **N9** | **Ombre : cache de shadow map + étalement des cascades** | ★★★ | M | Moyen | Passes Iris (`ShadowRenderer`) |
| N10 | Distance d'ombre adaptative (budget d'image) | ★★ | S | Faible | Cadran Lanterne (`Dials`) |
| N11 | Séparation ombre statique / dynamique | ★★ | L | Moyen | Passes Iris, entités |
| N12 | Culling compute + Hi-Z (caméra) | ★–★★ à 12 chunks, ★★★ au-delà | L | Élevé (latence 1 image, pas de translucides) | `DeviceFeatures`, compute (GL 4.3 / Vulkan) |
| N13 | Mesh shaders (Vulkan) | ×1,5 à 12 chunks, jusqu'à ×5 à 64 (S6) | L+ | Élevé | Backend Vulkan de 26.3, GPU compatible ; shaders Iris incompatibles (bug shaderc) |

**Ordre recommandé (rapport gain/coût/risque)** : N0 → N8/N9 (ombres) → N10 → N6 → N3 → N4/N5 → N7 → (N12/N13
seulement si les mesures montrent encore un goulot CPU/GPU de terrain à 12 chunks).

---

## 8. Limites et ce qui n'a pas été vérifié

1. **Pas de lecture directe du code source de Sodium** : uniquement documentation, wiki (dont pages générées
   par DeepWiki, qui peuvent se tromper), notes de version. Les largeurs de bits du format compact (§4.1) viennent
   de ces pages ; à ne pas considérer comme un cahier des charges.
2. **Le détail des envois (mémoire persistante mappée, fences, orphaning)** n'apparaît pas dans les sources
   consultées ; le tableau §5.2 est un catalogue de techniques générales, pas une description de Sodium.
3. **Gains chiffrés** : seuls les chiffres de Meshelium (×1,5/×3,1/×5,3) et la mention « 20–40 % du budget GPU
   pour l'ombre » sont sourcés ; le second provient d'un résumé automatique peu fiable. Tous les gains du §7
   marqués **[à mesurer]** doivent être confirmés sur le banc de l'agent perf (mêmes conditions, FPS moyens et
   1 % bas) avant décision.
4. **Aucune source primaire** ne détaille le mécanisme exact par lequel le pilote NVIDIA reconnaît Minecraft
   (nom de processus ? ligne de commande ? titre de fenêtre ?) ni la perte de FPS chiffrée : à établir par
   expérience (profil du panneau NVIDIA pour `javaw.exe` ; mesure avant/après).
5. **Voxy** : le résumé consulté décrit un moteur de **LOD lointains** (hiérarchie, Hi-Z, indirect), pas le terrain
   proche ; il sert de référence pour N12, non pour le cœur du moteur.
6. **Compatibilité avec Iris** (shaders de packs) des techniques GPU-driven (N12, N13) : non vérifiée ; le
   bug natif shaderc sur les gros fragments bloque Vulkan côté shaders (`RECHERCHE-VULKAN-SANS-SODIUM.md`).

---

## 9. Index des sources consultées

* S1 — Dépôt Sodium (README, licence PolyForm Shield 1.0.0, objectifs) : https://github.com/CaffeineMC/sodium
* S2 — Wiki (résumé de dépôt) « Chunk Rendering Pipeline » : https://deepwiki.com/CaffeineMC/sodium/3.1-chunk-rendering-pipeline
* S3 — Notes de version Sodium 0.9.x (allocateur multi-arènes, défragmentation incrémentale, frame pacing) : https://github.com/CaffeineMC/sodium/releases
* S4 — Wiki « GPU Memory Management » : https://deepwiki.com/CaffeineMC/sodium/3.4-gpu-memory-management
* S5 — Wiki « Block and Fluid Rendering » (format de sommet) : https://deepwiki.com/CaffeineMC/sodium/3.2-block-and-fluid-rendering
* S6 — Meshelium (terrain par mesh shaders, backend Vulkan MC 26.2/26.3) : https://github.com/ded811/Meshelium
* S7 — Nvidium (culling et assemblage par mesh shaders) : https://modrinth.com/mod/nvidium ; https://nvidium.net/ ; https://deepwiki.com/drouarb/nvidium
* S8 — Résumé de dépôt Iris (intégration terrain, ombres) : https://deepwiki.com/IrisShaders/Iris
* S9 — Wiki « Shader System » (uniformes, données par section, variantes) : https://deepwiki.com/CaffeineMC/sodium/3.3-shader-system
* S10 — Wiki Sodium « Driver Compatibility » : https://github.com/CaffeineMC/sodium/wiki/Driver-Compatibility
* S11 — Wiki « Hardware Workarounds » (NVIDIA, AMD, Intel) : https://deepwiki.com/CaffeineMC/sodium/4.1-hardware-workarounds ; issues associées : https://github.com/CaffeineMC/sodium/issues/2061 ; https://github.com/CaffeineMC/sodium/issues/1830 ; https://github.com/CaffeineMC/sodium/issues/3302
* S12 — Vulkan Guide, « Compute based Culling » (frustum, Hi-Z, compaction, limites d'ordre) : https://vkguide.dev/docs/gpudriven/compute_culling/
* S13 — Cartes d'ombre : mise en cache et mises à jour étalées — Cascaded Shadow Maps (Microsoft Learn) https://learn.microsoft.com/en-us/windows/win32/dxtecharts/cascaded-shadow-maps ; DigitalRune, « Shadow Maps » https://digitalrune.github.io/DigitalRune-Documentation/html/4f8d2843-e46a-44cf-ba8d-c58fb8d9302d.htm ; Epic, « Virtual Shadow Maps » https://dev.epicgames.com/documentation/unreal-engine/virtual-shadow-maps-in-unreal-engine ; Godot, proposition d'amélioration https://github.com/godotengine/godot-proposals/issues/6948
* S14 — Voxy (LOD lointains GPU-driven, Hi-Z, indirect) : https://github.com/MCRcortex/voxy ; https://deepwiki.com/MCRcortex/voxy ; culling GPU, Interplay of Light : https://interplayoflight.wordpress.com/2018/01/15/experiments-in-gpu-based-occlusion-culling-part-2-multidrawindirect-and-mesh-lodding/
