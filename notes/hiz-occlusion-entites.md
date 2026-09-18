# Culling d'occlusion Hi-Z pour les entités — faisabilité

> Enquête du 18 septembre 2026, même méthode que `notes/dlss-panama.md` et
> `notes/fsr2-faisabilite.md` : **VÉRIFIÉ** veut dire qu'une commande a été lancée ou qu'une
> source primaire (`javap` sur le VRAI jar patché, `minecraft_26.3_client.jar`, jamais `.mcsrc/`)
> a été lue et citée ; **SUPPOSÉ** est signalé comme tel.

---

## Verdict

**Pas implémenté cette passe.** Le module n'existe pas, au sens où `Settings.java` l'entend :
« un module non mesuré n'existe pas ». Le culling CPU existant (`Shroud`, `EntityCullMixin`,
raycast Amanatides-Woo, `@Redirect` depuis la `96571d9`) reste seul en place, **inchangé**.

Ce n'est **pas** un blocage aussi total que celui de FSR 2 (voir `notes/fsr2-faisabilite.md`) :
la construction d'une pyramide Hi-Z est un pur **rassemblement** — chaque texel d'un mip lit
quatre texels du mip précédent et n'écrit que chez lui — contrairement au *downsampler* en une
passe (SPD) de FSR2, qui a besoin d'un atomique. Elle serait donc constructible avec les seuls
nuanceurs de fragment que ce moteur sait exécuter. **Le blocage est ailleurs** : le point
d'accroche que la mission demande (`isEntityVisible`, pendant l'*extraction*, avant que le monde
de cette image ne soit dessiné) et la synchronisation GPU→CPU qu'exige une décision par entité,
prise en Java.

---

## 1. Ce qui est vérifié en bytecode réel

Sur `com/mojang/renderpearl/` de `minecraft_26.3_client.jar` (le même artefact NeoForm que
compile ce dépôt en 26.3, pas une décompilation) :

| Question | Réponse | Preuve |
|---|---|---|
| Un nuanceur de calcul existe-t-il ? | **Non** | `ShaderType` n'a que `VERTEX` et `FRAGMENT` (`javap -p ShaderType.class`). `GpuDevice.compilePipeline` ne prend qu'un `RenderPipeline`. `CommandEncoder` n'a ni `dispatch`, ni `createComputePass`. Concorde avec `notes/fsr2-faisabilite.md` §2 — même moteur, même constat, trouvé indépendamment. |
| Une requête d'occlusion matérielle existe-t-elle ? | **Non** | `GpuDevice` n'expose que `createTimestampQueryPool`. `GpuQueryPool`/`GpuQuery` ne rendent qu'un `OptionalLong` — un instant, pas un compte de fragments passés. |
| Le Z est-il inversé ? | **Oui, confirmé** | Toutes les occurrences de `DepthStencilState` dans `RenderPipelines.class` (solid, cutout, translucide, etc.) sont construites avec `CompareOp.GREATER_THAN_OR_EQUAL` — le test de profondeur garde le plus GRAND Z, donc « plus proche » = Z plus grand. Confirme le commentaire de `client/upscale/Deep.java` (« Z inversé depuis 26.2 »), vérifié ici indépendamment plutôt que repris tel quel. |
| Conséquence pour la pyramide | Réduction par **MIN**, pas MAX | Avec Z inversé, le texel le « moins occultant » d'une région (celui qui révèlerait le plus une entité derrière) est celui dont le Z est le plus PETIT — le plus loin. Un test conservateur (qui ne doit JAMAIS cacher à tort) doit comparer le point le plus proche de la boîte de l'entité au **minimum** de Z du bloc de mip couvrant sa projection. |
| Le format de profondeur est-il lisible ? | Oui, en principe | `RenderTargetAccessor` (déjà dans ce dépôt, mixin `@Accessor` sur `RenderTarget.depthFormat`) donne le `GpuFormat` réel (`D32_FLOAT`…). `CommandEncoder.copyTextureToTexture`/`copyTextureToBuffer` existent et pourraient s'en servir. |

---

## 2. Ce qui serait techniquement constructible, et comment

1. **Copier la profondeur avant sa destruction.** `GameRenderer.render` l'efface juste après le
   monde ; `RenderLevelStageEvent.AfterLevel` est émis juste avant (ligne 572 contre 578 —
   constat déjà fait et déjà exploité par `notes/fsr2-faisabilite.md` §4.2 pour un besoin voisin,
   la reprojection temporelle). Un second abonnement à cet évènement, **indépendant** de
   `Scene.java`/`Jitter.java`/`Reproject.java` (hors sujet, jamais touchés ici), suffirait — sans
   mixin.
2. **Cascade de passes plein-écran**, même patron que `client/upscale/Accumulate.java` : mip 0 =
   la copie ; chaque mip suivant échantillonne 2×2 texels du précédent via un
   `GpuTextureView` borné à ce niveau (`GpuDevice.createTextureView(texture, base, count)`
   existe) et réduit par MIN.
3. Ce serait un module neuf et isolé, sans dépendance sur un fichier interdit de cette mission.

Ce plan est donc **réel**, pas un prétexte — c'est justement pour cela qu'il valait la peine
d'aller jusqu'au bout de l'enquête avant de conclure.

---

## 3. Ce qui bloque réellement, et pourquoi le forcer cette passe serait irresponsable

### 3.1 Le décalage d'une image, structurel

`isEntityVisible` tourne pendant `LevelExtractor.extractVisibleEntities`, qui prépare la scène
**avant** que le monde de cette image ne soit rendu. La profondeur la plus fraîche disponible à
cet instant est donc celle de l'image **précédente**. C'est une technique connue — l'occlusion
culling temporel existe dans de vrais moteurs GPU-driven — mais son coût de correction doit être
**mesuré**, pas supposé : une entité qui vient de se démasquer (un mur détruit, un joueur qui
tourne vite) resterait occluse au moins une image de trop, et potentiellement plus (voir 3.2).

### 3.2 La décision par entité est prise en Java, pas sur le GPU

Rien dans ce moteur ne permet un test d'occlusion entièrement GPU pour des entités (pas de
tirage indirect piloté par un tampon de visibilité, pas de requête d'occlusion matérielle —
vérifié §1). La seule voie est donc de relire la pyramide sur le CPU. `CommandEncoder`
a bien `copyTextureToBuffer(..., Runnable, ...)`, mais c'est **asynchrone** : rien dans la
signature ni dans ce qui a été lu ne dit sur quel fil le `Runnable` s'exécute, ni à quel instant
les octets sont sûrs à lire par rapport au fil de rendu qui appelle `isEntityVisible`.

### 3.3 Précédent direct de ce projet, sur ce module précis

`Shroud.java` le dit dans son propre Javadoc : *« Ce projet a déjà refusé une entité parallèle
pour cette raison [course de données] [...] voir notes/MINE.md, ligne « async » »*. Et
`notes/MINE.md`, section rendu, classe déjà `brute-force-rendering-culling` (LGPL, culling
d'occlusion GPU, 1.20.6) comme *« Gain annoncé énorme, réputation d'artefacts »* — **jamais pris**,
rangé en `MESURER`, jamais mesuré depuis. Poser une lecture GPU→CPU asynchrone sur **exactement
le même module** (le voile d'entités), sans preuve de fil, sans épreuve de synchronisation
mesurée, referait l'erreur que ce dépôt a déjà nommée et écartée une fois — pour la même raison,
sur un module voisin (le tick asynchrone des entités).

---

## Ce qui a été fait cette passe

- Lu en entier `mixin/EntityCullMixin.java`, `core/Shroud.java`, `core/Horizon.java`,
  `core/Settings.java`, `core/Config.java`, `client/upscale/Deep.java`,
  `client/upscale/Accumulate.java`, `client/upscale/Resolve.java`,
  `mixin/RenderTargetAccessor.java`, `lab/Radiographie.java`, `lab/Snap.java`,
  `resources/lanterne.mixins.json` — pour rien modifier, seulement comprendre le point
  d'accroche exact et le patron d'accès GPU déjà en place.
- `javap -p` sur le vrai jar patché (`minecraft_26.3_client.jar`, NeoForm 26.3, pas `.mcsrc/`) :
  `ShaderType`, `GpuDevice`, `CommandEncoder`, `GpuQueryPool`, `GpuQuery`, `DepthStencilState`,
  `CompareOp`, et toutes les occurrences de `DepthStencilState` dans `RenderPipelines.class` —
  détail au §1.
- **Aucun fichier de production modifié.** `EntityCullMixin.java`, `Shroud.java`, `Settings.java`,
  `Config.java`, `lanterne.mixins.json` : inchangés. Le culling CPU existant reste l'unique
  mécanisme, tel quel — rien n'a été ajouté qui puisse le régresser ou le doubler d'un
  interrupteur inerte.
- Pas de `run-reveil` lancé pour ce chantier : il n'y avait rien à vérifier visuellement (`Snap`)
  ni à mesurer (`Radiographie`) puisqu'aucun code de rendu n'a été écrit.

---

## Ce qu'il faudrait pour reprendre ce chantier correctement

1. **Une preuve de fil et d'instant**, pas une supposition : sur quel fil `copyTextureToBuffer`
   invoque son `Runnable`, et la garantie que les octets sont visibles au moment où
   `isEntityVisible` les lirait. Une épreuve dédiée dans `lab/`, comme ce dépôt l'exige déjà pour
   tout le reste, avant d'écrire une seule ligne qui en dépende.
2. **Une mesure du taux de faux négatifs temporaires** (entité démasquée mais encore marquée
   occluse) sur une scène reproductible, caméra en mouvement rapide — `client/upscale/Vertige`
   existe déjà pour produire ce mouvement.
3. **Un budget de correction mesuré, pas deviné** : biais de profondeur, marge de boîte
   englobante, avant d'envisager un allumage par défaut.

Tant que ces trois points ne sont pas faits, ce module **n'existe pas**, au sens de
`Settings.java` — et c'est pour cela qu'aucun interrupteur, aucune variable d'environnement,
aucune classe vide n'a été ajoutée pour lui cette passe : un mécanisme désactivé qui ne fait
encore rien n'est qu'un décor, et ce dépôt s'en méfie autant que d'un module allumé à tort.
