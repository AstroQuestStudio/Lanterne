# FSR 2 sur Blaze3D 26.2 — faisabilité

> Enquête du 15 septembre 2026, même méthode que `notes/dlss-panama.md` : **VÉRIFIÉ** veut dire
> qu'une commande a été lancée ou une source primaire lue et citée ; **SUPPOSÉ** est signalé.

---

## Verdict

**FSR 2 tel qu'AMD le publie ne peut pas tourner sur Blaze3D 26.2.** Ce n'est pas une question de
licence — elle est bonne, MIT — ni de taille, ni de temps. C'est que ses huit passes sont des
**nuanceurs de calcul** et que le moteur de rendu de Minecraft 26.2 n'en exécute aucun.

**Mais le but reste atteignable**, par une voie plus simple que ce dépôt peut réellement écrire et
que vanilla soutient de première main. Voir §4.

---

## 1. Ce que FSR 2 exige — VÉRIFIÉ, sources primaires

`src/ffx-fsr2-api/vk/CMakeLists.txt`, arguments de compilation, cités mot pour mot :

```
-compiler=glslang -e main --target-env vulkan1.1 -S comp -Os -DFFX_GLSL=1
```

`-S comp` : **les huit passes sont des nuanceurs de calcul**, sans exception —
`tcr_autogen`, `autogen_reactive`, `accumulate`, `compute_luminance_pyramid`, `depth_clip`,
`lock`, `reconstruct_previous_depth`, `rcas`.

`src/ffx-fsr2-api/shaders/ffx_fsr2_callbacks_glsl.h` déclare **vingt images de stockage** en
écriture, et surtout ceci, qui est le cœur du problème :

```glsl
layout (set = 1, binding = FSR2_BIND_UAV_RECONSTRUCTED_PREV_NEAREST_DEPTH, r32ui)
    uniform uimage2D rw_reconstructed_previous_nearest_depth;
...
FfxUInt32 uDepth = floatBitsToUint(fDepth);
#if FFX_FSR2_OPTION_INVERTED_DEPTH
    imageAtomicMax(rw_reconstructed_previous_nearest_depth, iPxSample, uDepth);
#else
    imageAtomicMin(rw_reconstructed_previous_nearest_depth, iPxSample, uDepth);
#endif
```

Deux choses y sont impossibles à un nuanceur de fragment, et il faut les distinguer parce qu'on
les confond souvent :

- **L'atomique.** Plusieurs pixels source peuvent atterrir sur le même pixel destination ; il faut
  départager par un maximum atomique.
- **La dispersion.** `iPxSample` est calculé **depuis le vecteur de mouvement**, pas depuis la
  position du fil. Un nuanceur de fragment écrit dans *son* pixel et nulle part ailleurs : il
  rassemble, il ne disperse pas. C'est une propriété du pipeline, pas une limite d'API.

S'y ajoute `rw_spd_global_atomic` (`r32ui`, `coherent`), le compteur global du descendeur en une
passe, qui suppose une communication entre groupes de travail.

---

## 2. Ce que Blaze3D 26.2 offre — VÉRIFIÉ, sources décompilées

| besoin de FSR 2 | Blaze3D 26.2 | où c'est lu |
|---|---|---|
| pipeline de calcul | **absent** | `GpuDevice` ne sait créer que surface, encodeur, échantillonneur, texture, vue, tampon, requête |
| image de stockage liable | **absente** | `BindGroupLayout` ne contient que `List<String> samplers` et `List<UniformDescription> uniforms` |
| tampon de stockage | **absent** | `GpuBuffer.Usage` : `MAP_READ/WRITE`, `COPY_SRC/DST`, `VERTEX`, `INDEX`, `UNIFORM`, `UNIFORM_TEXEL_BUFFER`, `INDIRECT_PARAMETERS`. Pas de `STORAGE`. |
| mémoire partagée de groupe | **sans objet** — pas de groupes |

Les deux seules occurrences de `COMPUTE` dans tout `com/mojang/blaze3d/` sont une chaîne de
capacité de file (`COMBINED_GRAPHICS_COMPUTE_PRESENT_QUEUE`) et un nom d'étape de pipeline dans un
utilitaire de trace. NeoForge n'en ajoute aucune : **aucun** fichier de `net/neoforged/` ne
mentionne `ComputePipeline`, `glDispatchCompute`, `GL_COMPUTE_SHADER` ni
`VK_SHADER_STAGE_COMPUTE`.

Un `RenderPipeline` se résume à : un nuanceur de sommets, un nuanceur de fragments, des
échantillonneurs, des uniformes, des cibles de couleur et un état de profondeur.

**Conclusion : les trois choses que FSR 2 exige sont exactement les trois que Blaze3D n'a pas.**

---

## 3. Les portages existants ne contredisent pas ce verdict

Godot et `JuanDiegoMontoya/FidelityFX-FSR2-OpenGL` portent bien FSR 2, et c'est ce qui rend le
verdict contre-intuitif. Mais ils portent le **dorsal** — l'allocation des ressources et
l'ordonnancement des passes — en gardant les nuanceurs de calcul tels quels : Godot via
`RenderingDevice`, qui expose le calcul ; le second via `glDispatchCompute` et
`glBindImageTexture` d'OpenGL 4.3. Aucun des deux ne réécrit les passes en fragments, **parce que
ce n'est pas possible**.

Il faudrait donc, avant de porter FSR 2, ajouter un pipeline de calcul à Blaze3D — c'est-à-dire
réécrire une partie du moteur de rendu de Minecraft, pour les deux backends, par mixin. Hors de
portée, et fragile là où tout le reste de ce mod est prudent.

---

## 4. La voie qui reste, et pourquoi elle est bonne

Ce que le joueur demande n'est pas « FSR 2 » : c'est **un remonteur temporel**, c'est-à-dire de la
netteté en mouvement que FSR 1.0 ne peut pas donner. Or la technique dont FSR 2 <em>et</em> DLSS
descendent — l'accumulation temporelle avec reprojection — s'écrit entièrement **en rassemblement**,
donc en nuanceurs de fragment.

Et vanilla la soutient déjà. `PostChainConfig.InternalTarget` porte un champ **`persistent`** :

```java
public record InternalTarget(Optional<Integer> width, Optional<Integer> height,
                             boolean persistent, int clearColor)
```

Une cible persistante survit d'une image à l'autre : c'est précisément le tampon d'historique
qu'un remonteur temporel réclame, et c'est une fonction de première classe du moteur, pas un
détournement.

**L'enchaînement, tout en rassemblement :**

1. **Décaler la projection** d'un sous-pixel par image — `client/upscale/Jitter.java`, écrit et
   éprouvé (14 épreuves, 0 px d'écart). Sans lui, rien à accumuler.
2. **Copier la profondeur du monde** avant sa destruction. Elle meurt ligne 578 de
   `GameRenderer`, et `RenderLevelStageEvent.AfterLevel` est émis ligne 572 : la copie s'y prend
   **sans mixin**.
3. **Reprojeter en arrière.** Pour chaque pixel de sortie : lire sa profondeur, la déprojeter,
   la transformer par la matrice vue-projection de l'image **précédente**, et lire l'historique à
   la position obtenue. C'est un **rassemblement** — chaque fil lit où il veut et n'écrit que chez
   lui. Aucun atomique, aucune dispersion.
4. **Borner par le voisinage.** Boîte englobante des 3×3 pixels courants, à laquelle on ramène
   l'échantillon d'historique. C'est le remède classique au fantôme et aux traînées de
   désocclusion, et il remplace — moins finement — les « verrous » de FSR 2 qui, eux, exigeraient
   la dispersion.
5. **Mélanger**, puis **raffermir avec RCAS**, déjà écrit et déjà crédité.

**Ce que cela n'est pas.** Ce n'est **pas** FSR 2, et cela ne devra jamais être présenté comme tel.
C'est un remonteur temporel classique. Il sera moins bon que FSR 2 sur les fins détails en
mouvement et sur les objets qui bougent d'eux-mêmes — les vecteurs de mouvement reconstruits depuis
la seule profondeur sont justes pour la **caméra** et faux pour une créature qui marche, un
feuillage animé, une particule. Ce défaut appartient à la méthode et doit être annoncé avant d'être
découvert.

**Ce que cela est.** Le seul remonteur temporel que ce moteur puisse exécuter, sans mixin sur le
rendu, sans bibliothèque native, sans permission native qu'une mise à jour de Java puisse retirer,
et sur OpenGL comme sur Vulkan — donc pour tout le monde.

---

## 5. Sur la question « FSR est-il moins bon sur NVIDIA ? »

Elle est légitime et repose sur une confusion qu'il faut défaire, parce qu'elle décide de la
confiance qu'on accorde au réglage.

- **Le gain d'images par seconde est le même**, quel que soit le remonteur, parce qu'il ne vient
  pas de lui : il vient du **nombre de pixels rendus**. À 67 % par dimension il n'en reste que
  `0,67² ≈ 45 %`, soit **55 % de travail en moins** — avant que DLSS, FSR ou quoi que ce soit
  n'intervienne.
- **La qualité d'image, elle, diffère**, un peu : DLSS s'appuie sur des cœurs tensoriels et un
  réseau entraîné, et tient mieux les fins détails en mouvement. FSR est purement algorithmique.

Donc sur une RTX 3080, un remonteur AMD donne pratiquement **les mêmes images par seconde** que
DLSS, avec une image légèrement moins stable sur les détails fins. Ce n'est pas une voie de
secours : FSR est livré dans des dizaines de jeux du commerce qui tournent sur du matériel NVIDIA.

Cette explication est désormais dans la colonne de détail de la ligne « Upscaling ».

> **Au passage, une erreur corrigée.** L'écran et le javadoc de `Upscale` annonçaient « 45 % de
> pixels en moins » à 67 % par dimension. C'est l'inverse : il en **reste** 45 %, on en supprime
> 55 %. Le mod se sous-estimait d'une bonne moitié.
