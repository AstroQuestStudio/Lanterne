# Technologies modernes pour un mod « état de l'art » — veille du 21/09/2026

**Objet** : ce que Java 25, Minecraft 26.x / NeoForge et les GPU modernes permettent à Lanterne, pour viser des FPS maximaux (client, avec/sans shaders, OpenGL et Vulkan, iGPU) et un serveur qui ne vole pas le tick. Complète `IDEES-OPTIMISATION.md` (mods et idées) sans la refaire.

**Étiquettes** — chaque affirmation porte l'une d'elles :
- **[P]** prouvé par une source citée (liens en fin de document) ;
- **[M]** mesuré ou vérifié sur cette machine le 21/09 ;
- **[H]** hypothèse : plausible, à prouver sur notre banc (règle n°0 de `OBJECTIFS-FPS.md` : jeu non bridé, GPU cité, scène figée au coucher de soleil, sous file du banc central).

Aucun gain chiffré ci-dessous n'est un résultat de Lanterne, sauf mention **[M]**.

---

## 0. Faits vérifiés sur cette machine [M]

| Fait | Preuve |
|---|---|
| JDK du client de banc : Temurin **25.0.4.1** (LTS) | `java -version` |
| `-XX:+ZGenerational` est **ignoré avec un avertissement** en JDK 25 (« support was removed in 24.0 ») : ZGC est déjà générationnel, l'option ne sert plus | sortie réelle de `java -XX:+UseZGC -XX:+ZGenerational -version` |
| `-XX:+UseZGC` seul démarre (« Using The Z Garbage Collector ») | `-Xlog:gc*` |
| `-XX:+UseCompactObjectHeaders` est accepté (option produit en 25, **désactivée par défaut**) | `-XX:+PrintFlagsFinal` → `false {default}` ; démarrage OK |
| Le module `jdk.incubator.vector` existe dans ce JDK (**nécessite `--add-modules jdk.incubator.vector`**) | `java --list-modules` |
| Les options du cache AOT (`AOTCache`, `AOTConfiguration`, `AOTMode`) existent | `-XX:+PrintFlagsFinal` |
| G1 est le GC par défaut (ergonomique) ; Parallel, ZGC, Shenandoah sont disponibles | `-XX:+PrintFlagsFinal` |
| Machine de banc : 16 threads logiques, ≈ 31 Go de RAM | WMI |
| Le lanceur du banc central ajoute `-Xmx3G` par défaut ; les arguments d'un job s'y ajoutent (le dernier `-Xmx` l'emporte) | `C:\bench\runner.ps1` l.75-76 |

---

## A. Java 25

| Techno | Ce que ça apporte | Statut | Utilisable par Lanterne ? |
|---|---|---|---|
| **Compact Object Headers** (JEP 519, produit en 25) | En-tête d'objet de 12 → 8 octets ; annoncé −22 % de tas et −8 % de CPU sur SPECjbb2015, jusqu'à −30 % de mémoire engagée sur un banc synthétique. Sans changement de code. **[P]** | À activer par `-XX:+UseCompactObjectHeaders` (pas par défaut) | Oui, c'est un argument JVM à ajouter au pack. Pertinent pour nous : le F3 du patron montrait ~893 Mo/s d'allocation, donc beaucoup d'objets courts. **[H]** sur le gain de FPS |
| **ZGC** (générationnel par défaut depuis 24, JEP 490 retire l'ancien mode) | Pauses de l'ordre de la milliseconde, indépendantes de la taille du tas **[P]** ; mais les comparatifs cités disent que ZGC coûte du débit/FPS côté client à matériel égal et que G1 reste le meilleur défaut client **[P, sources non académiques]** | Disponible | À mesurer (job J1). Ne pas recommander avant mesure |
| **G1** (défaut) | Bon équilibre débit/pauses ; réglable (`MaxGCPauseMillis`, taille de tas) | Défaut | Base de comparaison (J0, J7) |
| **Shenandoah / Parallel** | Shenandoah : pauses courtes concurrentes ; Parallel : débit maximal, pauses longues | Disponibles | À mesurer (J6, J2) — Parallel peut battre G1 en FPS moyen mais avec des creux : à juger sur le 1 % low |
| **Vector API** (JEP 508, 10ᵉ incubation ; encore en incubation jusqu'à Valhalla) | SIMD portable (AVX/NEON) ; bibliothèques mathématiques natives via FFM **[P]** | **Incubateur : exige `--add-modules jdk.incubator.vector`** [M] | Possible pour le **serveur** (nous contrôlons ses arguments) : lumière, pregen. Côté client : le lanceur du joueur doit passer l'option, et le module doit être visible depuis les couches de modules de NeoForge **[H]** — à tester avant tout |
| **Foreign Function & Memory API** (JEP 454, finale depuis JDK 22) | Mémoire hors tas (`MemorySegment`) et appels natifs sans JNI ; interopérable avec `ByteBuffer` (`ofBuffer` / `asByteBuffer`) **[P]** ; objectif : performances comparables ou meilleures que JNI/Unsafe **[P]** | Finale, stable | Oui : envoi groupé de la souris brute, priorité MMCSS du fil de rendu (idées de `IDEES-OPTIMISATION.md`), tampons de maillage hors tas. Gain sur les FPS : **[H]** |
| **Threads virtuels** (JEP 491, 24 : `synchronized` ne les fixe plus au fil porteur) | Bons pour l'E/S bloquante massive **[P]** | Finale | Utile pour les téléchargements de la Mèche, la lecture de régions ; **pas** pour le maillage (calcul pur, il faut des fils de plate-forme) |
| **Structured Concurrency** (JEP 505) | Gestion structurée de sous-tâches | Encore en aperçu/ciblé en 25 **[P]** | Peu d'intérêt immédiat |
| **Cache AOT** (JEP 514 : commande simplifiée `-XX:AOTCacheOutput=…` ; JEP 515 : profils de méthodes dans le cache) | Démarrage plus rapide et échauffement JIT réduit : +19 % sur un petit programme de démonstration **[P]** | Disponible en 25 | **Démarrage seulement**, pas FPS ; le cache est lié au classpath exact : chaque mise à jour de jar (donc de la Mèche) l'invalide **[H]**. Faible priorité |

---

## B. Minecraft 26.x et NeoForge

| Sujet | Ce que disent les sources | Pour Lanterne |
|---|---|---|
| **Backend Vulkan** | Introduit avec le snapshot 26.2-snapshot-1 (7 avril 2026), en parallèle d'OpenGL ; Mojang recommande aux modders de passer par **Blaze3D** plutôt que d'appeler l'API GPU directement **[P]** | Écrire tout nouveau code de rendu contre l'API Blaze3D/renderpearl : il fonctionne alors en OpenGL **et** Vulkan (déjà la conclusion de `RENDU-LANTERNE-DESIGN.md`) |
| **Prérequis Vulkan** | Le backend exige `VK_KHR_dynamic_rendering` ; un mod (VulkanCompat) existe pour les vieux GPU qui ne l'ont pas **[P]** | Détecter et replier vers OpenGL : déjà dans l'esprit du repli « Réveil » |
| **API `RenderPipeline` (26.1 → 26.2)** | Une liste de `BindGroupLayout` remplace uniformes+échantillonneurs ; jusqu'à 16 tampons de sommets de formats différents ; jusqu'à 8 cibles couleur **[P]** (primer NeoForge) | Vérifier `client/terrain/TerrainVertexFormat` et `RenderPipelinesMixin` : le multi-tampons de sommets peut servir un format compact Iris |
| **Mixin** | NeoForge embarque MixinExtras ; `@WrapOperation` et `@ModifyExpressionValue` se **chaînent** entre mods, alors que `@Redirect` crée une incompatibilité dure **[P]** | Lanterne a ~108 mixins : **audit d'hygiène** à faire (remplacer les `@Redirect` par `@WrapOperation` quand c'est possible) → moins de conflits avec les autres mods du pack |
| **Apprentissage Blaze3D** | Guide communautaire *LearnBlaze3D* pour écrire des rendus avec la nouvelle API **[P]** | Référence pour l'équipe |
| **Profilage** | JFR : événement `jdk.ObjectAllocationSample` (depuis JDK 16) donne les piles qui allouent ; async-profiler sait produire du JFR (CPU + allocations) **[P]** | Déjà utilisé par l'agent perf (JourneyMap = 44 % des allocations). Tracy : aucune source trouvée pour Minecraft, **non retenu** |
| **Limite d'inactivité de FPS** | `inactivityFpsLimit` (défaut « afk ») bride à ~30 FPS après ~60 s sans entrée **[M]** (agent perf) | Toute mesure sans `"minimized"` est invalide (règle n°0) |

---

## C. GPU moderne

| Technique | Ce que disent les sources | Réserves pour nous |
|---|---|---|
| **Culling Hi-Z par compute + dessin indirect avec compteur** | Pipelines voxel entièrement pilotés par le GPU (cadre *Aokana*, 2025) : sélection de chunks, carte Hi-Z, tout en compute **[P]** ; `vkCmdDrawIndexedIndirectCount` / `VK_KHR_draw_indirect_count` permettent au GPU de décider du nombre de dessins après un culling en compute **[P]** | Vanilla 26.3 fait déjà du dessin indirect **[M]** (lu par `javap` par l'agent design). Le gain vient de culler aussi pour les **ombres**, où personne ne le fait sous Iris en OpenGL (`IDEES-OPTIMISATION.md` §1 n°2). **[H]** sur l'ampleur |
| **Sous-passes / entrées d'attachement** | Sur les GPU **à tuiles**, garder les gbuffers en mémoire de tuile évite les allers-retours en mémoire ; c'est une bonne pratique pour le rendu différé **[P]** | Nos cibles desktop (RTX, Radeon Vega du Ryzen 5800H) ne sont **pas** des GPU à tuiles classiques : le gain est surtout documenté sur mobile. Sur iGPU (mémoire partagée), économiser des lectures/écritures peut aider **[H]** — à ne pas promettre |
| **`VK_KHR_dynamic_rendering_local_read`** | Rend possible la lecture locale d'attachements **avec le rendu dynamique** (sans objets de passe) ; les pilotes peuvent servir ces lectures depuis la mémoire sur puce **[P]** | Le backend Vulkan de Minecraft utilise le rendu dynamique **[P]** : cette extension est **le** chemin (les sous-passes classiques ne sont pas disponibles). Support desktop non précisé par les sources : à vérifier sur les 3 GPU du banc |
| **Spécialisation SPIR-V, cache de pipelines** | Options du pack → constantes de spécialisation, pipelines sans branches | Cité par le chantier optimiseur de shaders ; bloqué tant que shaderc échoue sur les gros fragments (`RECHERCHE-VULKAN-SANS-SODIUM.md`) |
| **Mesh shaders** | Meshelium (Vulkan) annonce ×1,5 à ×5 selon la distance (voir `IDEES-OPTIMISATION.md`) | Matériel récent seulement ; ne concerne pas l'iGPU Vega |
| **Vulkan vs OpenGL selon le GPU** | Banc publié en avril sur un snapshot 26.2 : Vulkan −39 à −58 % sur AMD, −32 à −48 % sur Intel, +21 à +31 % sur NVIDIA (chiffres à remesurer, voir `IDEES-OPTIMISATION.md`) | Politique : OpenGL par défaut sur AMD/Intel/iGPU ; Vulkan optionnel sur NVIDIA une fois mesuré chez nous |

---

## D. Chunks et serveur

| Techno | Ce que disent les sources | Pour le VPS |
|---|---|---|
| **C2ME** | Parallélise génération de chunks, éclairage et cartes de hauteur sur plusieurs cœurs ; disponible pour NeoForge **[P]** | Utile **si plusieurs cœurs**. Le VPS a 4 vCPU (saturés à ~3,7 cœurs par le pregen cette nuit, mesure de session) ; la cible MineStrator serait mono-cœur (mémoire du projet) : sur un cœur, **aucun gain** **[H]**. Incompatible avec Moonrise **[P]** |
| **Moonrise** | Même objectif que C2ME par une autre voie, incompatible avec lui **[P]** | À comparer à C2ME sur le VPS si on l'adopte |
| **ScalableLux** | Propagation de lumière parallèle par chunk, disponible NeoForge 26.1 à 26.3 **[P]** | Candidat pour le pregen (la lumière pèse dans la génération) **[H]** |
| **Lithium** | Recommandé avec C2ME et ScalableLux **[P]** | Déjà présent dans l'instance principale ; à vérifier sur le VPS |
| **GC serveur** | Sous 12 Go, G1 avec réglages « Aikar » ; ZGC vaut un essai à partir de 12 Go **[P, sources de blogs]** | VPS mono/petit-cœur : le GC concurrent vole du CPU au tick ; à mesurer sur un serveur jetable, jamais sur le VPS de production |

---

## E. Tableau de décision

Bénéfice : **F** = FPS client, **S** = serveur, **D** = démarrage. Effort : XS < 1 h, S < 1 j, M < 3 j, L > 3 j.

| # | Techno | Bénéfice | Effort | Risque | Compat. NeoForge/mods | Mesure |
|---|---|---|---|---|---|---|
| 1 | Compact Object Headers | F (moins d'allocation/GC), S | XS | Faible (option produit en 25) | Neutre | Job J3 vs J0 ; `[BILAN]` GC/s |
| 2 | Choix du GC (G1/ZGC/Parallel/Shenandoah) | F 1 % low | XS | Faible | Neutre | J0, J1, J2, J6 |
| 3 | Tas fixe + pré-touche | F 1 % low | XS | Faible | Neutre | J4, J5 |
| 4 | `MaxGCPauseMillis` G1 | F 1 % low | XS | Faible | Neutre | J7 |
| 5 | Réduire les allocations par image (JourneyMap, Iris…) | F | S | Faible | Correctifs par mixin (`@WrapOperation`) | JFR + `[BILAN]` |
| 6 | FFM : souris brute groupée + MMCSS | F à 300+ FPS | S | Faible | Windows seulement | A/B sous souris haute fréquence |
| 7 | Audit Mixin (`@Redirect` → `@WrapOperation`) | Stabilité | M | Faible | Réduit les conflits | Démarrage avec le pack complet |
| 8 | Culling GPU Hi-Z (ombres incluses) | F sous shaders | L | Moyen | Touche au rendu terrain | Scène banc ; ms de passe d'ombre |
| 9 | `dynamic_rendering_local_read` (Vulkan) | F iGPU/Vulkan | L | Moyen (support à vérifier) | Vulkan seulement | Sur les 3 GPU du banc |
| 10 | Vector API (serveur d'abord) | S (lumière/pregen) | M | Moyen (incubateur) | Argument de lancement | Pregen chunks/s sur serveur jetable |
| 11 | C2ME / ScalableLux (VPS multi-cœur) | S | S | Moyen (mods tiers) | Vérifier avec BlueMap/Lanterne | Chunks/s sur jetable |
| 12 | Cache AOT | D | M | Moyen (invalidé par chaque MAJ) | Lié au classpath | Temps de démarrage |
| 13 | Threads virtuels (E/S de la Mèche) | Fluidité | S | Faible | Neutre | — |

---

## F. Matrice des arguments JVM du client

**État au moment de la rédaction : jobs déposés, NON EXÉCUTÉS.** La file du banc central (`C:\bench\queue`) respecte le verrou manuel `C:\bench-lock`, détenu par un autre agent (intégration sans Sodium, 12:17), et son premier job était encore en phase de préparation à 12:22. Aucun chiffre n'est donc ici : rien n'est inventé.

Base commune : Lanterne **26.3.106** + Sodium 0.9.2, RTX 3080 (préférence GPU forcée), 1280×720, distance de rendu 12, sans shaders, scène figée à 12500, 60 s, `inactivityFpsLimit:"minimized"`, tas par défaut du banc `-Xmx3G`.

| Job | Arguments ajoutés | Identifiant (dossier `C:\bench\queue` puis `results`) |
|---|---|---|
| J0 — référence G1 | *(aucun)* | `20260921-122016-jvm_J0_g1_base` |
| J1 — ZGC | `-XX:+UseZGC` | `20260921-122030-jvm_J1_zgc` |
| J2 — Parallel | `-XX:+UseParallelGC` | `20260921-122030-jvm_J2_parallel` |
| J3 — en-têtes compacts | `-XX:+UseCompactObjectHeaders` | `20260921-122030-jvm_J3_compact` |
| J4 — tas pré-touché | `-Xms3G -XX:+AlwaysPreTouch` | `20260921-122017-jvm_J4_pretouch` |
| J5 — tas 6 Go fixe | `-Xms6G -Xmx6G` | `20260921-122018-jvm_J5_heap6g` |
| J6 — Shenandoah | `-XX:+UseShenandoahGC` | `20260921-122030-jvm_J6_shenandoah` |
| J7 — G1 pause 20 ms | `-XX:MaxGCPauseMillis=20` | `20260921-122030-jvm_J7_g1pause20` |

Non testé volontairement : `-XX:+ZGenerational` (ignoré en 25 [M]), `Xss`, `StringDeduplication` (aucun effet attendu sur le fil de rendu **[H]**).

**Lire les résultats** (dès que le lanceur les produit) :
```
powershell -NoProfile -Command "Get-ChildItem C:\bench\results -Filter *jvm_J*.txt | ForEach-Object { '== ' + $_.Name; Get-Content $_.FullName }"
powershell -NoProfile -File C:\bench\status.ps1
```
Un résultat n'est valable que s'il cite la RTX et « sous file : oui ». Répéter les 3 meilleurs jobs 3 fois (moyenne et 1 % low), puis refaire le meilleur avec shaders et sur l'iGPU.

**Arguments recommandés pour le LISEZMOI du pack — PROVISOIRES [H], à confirmer par la matrice** :

| RAM de la machine | Proposition | Justification (source) |
|---|---|---|
| 8 Go | `-Xms2G -Xmx3G -XX:+UseCompactObjectHeaders` (G1 par défaut) | Sous 12 Go, G1 est le défaut recommandé **[P]** ; en-têtes compacts = moins de mémoire **[P]** |
| 16 Go | `-Xms4G -Xmx4G -XX:+UseCompactObjectHeaders` | Tas fixe pour éviter les redimensionnements **[H]** |
| 32 Go | `-Xms6G -Xmx6G -XX:+UseCompactObjectHeaders` ; essayer ZGC seulement si la matrice le confirme | ZGC recommandé à partir de 12 Go pour les **serveurs** **[P]**, effet FPS client à prouver |

Ne pas coller `-XX:+ZGenerational` (ignoré en 25). Le lanceur CurseForge impose ses propres arguments ; à tester avant de recommander à des joueurs.

---

## G. Top 10 à adopter (ordre de priorité)

1. **Arguments JVM du client** (en-têtes compacts, GC, tas) — XS, mesure en cours (matrice F).
2. **Supprimer les allocations par image** (JourneyMap 44 %, puis Iris/ombres) — S, prouvé par JFR.
3. **FFM : souris brute groupée + priorité MMCSS** — S, utile à 300+ FPS.
4. **Audit Mixin** (`@Redirect` → `@WrapOperation`) — M, réduit les conflits du pack.
5. **Culling Hi-Z pour les ombres sous shaders** (OpenGL) — L, idée nouvelle.
6. **Écrire tout rendu contre Blaze3D** (GL + Vulkan) — principe d'architecture.
7. **`dynamic_rendering_local_read` pour le rendu différé Vulkan** — L, après vérification du support.
8. **ScalableLux / C2ME sur le VPS multi-cœur** — S/M, à mesurer sur un serveur jetable.
9. **Vector API côté serveur** — M, après test d'accessibilité du module.
10. **Cache AOT** — M, démarrage seulement, faible priorité.

---

## Limites de cette veille
- **Les gains chiffrés viennent des auteurs**, sur d'autres machines ; aucun n'est prouvé sur notre banc.
- **La matrice JVM n'est pas exécutée** (file bloquée par le verrou) ; les recommandations d'arguments sont des hypothèses.
- Les affirmations « ZGC coûte des FPS au client » viennent de guides de blog, pas d'une étude ; à prouver ou infirmer par J1.
- Le support de `VK_KHR_dynamic_rendering_local_read` sur AMD/NVIDIA desktop n'est pas établi par les sources trouvées.
- La visibilité du module `jdk.incubator.vector` depuis les couches de modules de NeoForge n'a pas été testée.
- Recherche sur Reddit/Discord : non lisibles directement.

---

## Sources
- JEP 519, Compact Object Headers : <https://openjdk.org/jeps/519> ; synthèses : <https://javapro.io/2026/02/10/mastering-memory-efficiency-with-compact-object-headers-in-jdk-25/>, <https://www.baeldung.com/java-object-header-reduced-size-save-memory>
- Vector API : <https://openjdk.org/jeps/508>, <https://docs.oracle.com/en/java/javase/25/docs/api/jdk.incubator.vector/module-summary.html>, <https://inside.java/2025/10/17/new-in-jdk-25-2-mins/>
- Cache AOT : <https://openjdk.org/jeps/515>, <https://softwaremill.com/whats-new-in-project-leyden-jep-514-and-jep-515-explained/>, <https://inside.java/2026/01/09/run-aot-cache/>
- ZGC générationnel : <https://openjdk.org/jeps/490>, <https://openjdk.org/jeps/439>, <https://andrewbaker.ninja/2025/12/03/pauseless-garbage-collection-in-java-25-zgc-guide/>
- Drapeaux JVM pour Minecraft : <https://github.com/brucethemoose/Minecraft-Performance-Flags-Benchmarks>, <https://github.com/mattlegro/Minecraft-Performance-JavaFlag-Benchmarks>, <https://www.switchbladegaming.com/minecraft/jvm-arguments/>, <https://zerve.gg/blog/best-jvm-flags-minecraft/>
- FFM : <https://openjdk.org/jeps/454>
- Threads virtuels : <https://openjdk.org/jeps/491> ; structured concurrency : <https://inside.java/2024/11/21/newscast-80/>
- Minecraft Vulkan / Blaze3D : <https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition>, <https://github.com/Argon4W/LearnBlaze3D>, <https://github.com/1Yuqas/VulkanCompat>, <https://beebom.com/how-to-enable-vulkan-in-minecraft/>
- NeoForge : <https://docs.neoforged.net/primer/docs/26.2/>, <https://deepwiki.com/neoforged/NeoForge/6.1-rendering-events-and-pipeline>
- MixinExtras : <https://github.com/LlamaLad7/MixinExtras>, <https://github.com/LlamaLad7/MixinExtras/wiki/WrapOperation>, <https://github.com/LlamaLad7/MixinExtras/wiki/ModifyExpressionValue>
- Culling GPU voxel : <https://arxiv.org/html/2505.02017v1> (Aokana), <https://docs.vulkan.org/guide/latest/extensions/VK_KHR_draw_indirect_count.html>, <https://vkguide.dev/docs/gpudriven/draw_indirect/>, <https://www.rastergrid.com/blog/2010/10/hierarchical-z-map-based-occlusion-culling/>
- Sous-passes / tuiles : <https://docs.vulkan.org/guide/latest/tile_based_rendering_best_practices.html>, <https://www.khronos.org/blog/streamlining-subpasses>, <https://docs.vulkan.org/features/latest/features/proposals/VK_KHR_dynamic_rendering_local_read.html>
- Chunks/serveur : <https://modrinth.com/mod/c2me-neoforge>, <https://github.com/RelativityMC/C2ME-neoforge>, <https://www.curseforge.com/minecraft/mc-mods/scalablelux>, <https://minestrator.com/blog/post/neoforge-performance-mods-minecraft-fps-tps>
- Profilage : <https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-performance-issues-using-jfr.html>, <https://blogs.oracle.com/javamagazine/post/java-jdk-flight-recorder-jfr>
