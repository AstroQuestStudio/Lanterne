# FSR 1 -> temporel : le vrai point de départ, trouvé cette passe

Mise à jour de ce plan après une relecture complète de `client/upscale/`. La
conclusion change : **la brique la plus dure était déjà écrite**, juste jamais
branchée. Ce qui reste à faire est plus petit et plus sûr que ce que la version
précédente de ce plan annonçait.

## Ce qui existe déjà, testé isolément, et zéro appelant

Trouvé par `grep -rln "new Jitter\|new Reproject"` : aucun résultat en dehors
des fichiers eux-mêmes. Ces deux classes sont complètes, documentées, et
possèdent chacune un outil de vérification autonome (`tools/EssaiJitter.java`,
`tools/EssaiReproject.java`) — mais **rien dans le pipeline ne les appelle**.

- **`Jitter`** : suite de Halton (bases 2/3), période adaptée au facteur
  d'échelle courant, `applyTo(Matrix4f, w, h)` décale une matrice de
  projection déjà construite. Sait exactement où s'insérer (voir javadoc) :
  la copie locale de la projection dans `GameRenderer.renderLevel`, juste
  avant `ProjectionMatrixBuffer.getBuffer(Matrix4f)` — **à revérifier en
  26.3** (le javadoc cite la ligne 539 en 26.2, jamais réconcilié avec cette
  version).
- **`Reproject`** : calcule la matrice de reprojection
  `vueProj(n-1) · T(caméra(n)-caméra(n-1)) · inverse(vueProj(n))`, avec le
  piège de Minecraft déjà résolu (la matrice de vue ne porte que la rotation,
  la position de caméra est retirée ailleurs). `advance()` une fois par image
  avec des matrices **non décalées** ; `matrix()` en NDC, prête pour un
  nuanceur ; `reset()` à appeler au changement de dimension/téléportation.
- **`Deep`** (DLSS) : conclusion déjà actée et argumentée en profondeur —
  licence NVIDIA l'interdit nommément (clause 4.e anticopyleft), la DLL n'est
  de toute façon pas présente chez le joueur, et les paramètres NGX sont des
  méthodes virtuelles C++ dont l'ordre diffère entre le SDK publié et ce que
  le pilote exporte réellement. Panama (Java 25) suffirait techniquement — la
  preuve existe (`tools/EssaiNgx.java`, `notes/dlss-panama.md`) — mais la
  licence ferme la porte. **Ne pas rouvrir ce sujet sans un fait nouveau.**

## Le vrai design, plus simple que "réécrire FSR en FSR2"

Ne pas remplacer EASU/RCAS (`fsr_easu.fsh`/`fsr_rcas.fsh`, stables, validés en
conditions réelles cette session). Les garder **tels quels**, et ajouter une
passe d'accumulation temporelle **avant** eux, sur la toile basse résolution :

```
scène basse résolution (jittée)  ──┐
                                    ├─► accumulation temporelle ─► EASU ─► RCAS ─► écran
historique (accumulation N-1)   ───┘         (nouveau)          (existant, inchangé)
```

Le gain de netteté temporel s'applique donc à l'**entrée** du pipeline FSR 1
existant, qui continue de faire exactement ce qu'il fait aujourd'hui sur une
image déjà plus riche en détail. Risque de régression sur ce qui marche déjà :
proche de zéro, puisque EASU/RCAS ne voient aucune différence de contrat
(toujours une toile RGBA de la même taille).

## Ce qu'il reste à écrire, dans l'ordre

1. **Vérifier le point d'injection du jitter en 26.3** (bytecode réel du jar
   client, jamais `.mcsrc/`) : `GameRenderer.renderLevel`, la copie locale de
   la projection, l'appel à `ProjectionMatrixBuffer.getBuffer(Matrix4f)`.
   Sans ce point exact, rien d'autre n'a de sens.
2. **Un mixin** qui appelle `Jitter.applyTo()` sur cette matrice locale, et
   `Reproject.advance()` une fois par image (matrices **non décalées**, donc
   avant l'appel à `Jitter.applyTo`) — dans cet ordre précis, sinon
   `Reproject` mesurerait le tremblement du décalage au lieu du mouvement de
   la caméra (piège déjà documenté dans `Reproject`).
3. **Une texture d'historique** dans `Scene`, gérée comme `target`/`aa`
   aujourd'hui (redimensionnée sur place, jamais remplacée — même piège du
   `SkyRenderer` documenté en tête de `Scene.java`, mais l'historique n'est
   pas capturé par lui donc moins de risque).
4. **Un nuanceur d'accumulation** (`temporal_accumulate.fsh`), même schéma que
   `fsr_easu.fsh` (`#version 410`, `layout(location)`, bloc `SamplerInfo`),
   mais deux échantillonneurs (`SceneSampler`, `HistorySampler`) et un bloc
   uniforme portant la matrice de reprojection (16 flottants, `layout(std140)`
   — suivre exactement le format déjà utilisé pour `SamplerInfo`). Mélange
   simple (`mix`), **sans rejet de désocclusion** pour cette première brique —
   c'est le point (2) du plan original, volontairement gardé à l'identique.
5. **Un nouveau maillon dans `Resolve`/`Scene`** : la toile basse résolution
   passe par ce nuanceur avant d'être confiée à `Resolve.chain()` existant.
   Nouveau réglage `lentille_temporelle` dans `ClientConfig`, **désactivé par
   défaut**, indépendant du cran de netteté FSR déjà présent.

## Ce qui n'a pas été fait cette passe, et pourquoi

Lecture et conception seules cette fois — aucun code de rendu écrit. Deux
raisons, pas une seule :

- Le budget de contexte restant pour cette tâche ne permettait pas d'écrire
  le mixin + la texture + le nuanceur + le branchement **et** de les vérifier
  sur un vrai client lancé (`--quickPlaySingleplayer`, monde réel du joueur)
  avant la fin. Livrer du code de rendu non vérifié irait contre la règle
  déjà appliquée partout ailleurs dans ce dépôt.
- Le ghosting d'un remonteur temporel **ne se voit qu'en bougeant la caméra
  vite** — la seule vérification qui vaille est visuelle, sur un client
  réel, pas une lecture de shader.

## Point de départ pour la prochaine session, très concret cette fois

Les cinq étapes ci-dessus, dans l'ordre. L'étape 1 (vérifier le point
d'injection en bytecode 26.3) est un préalable strict à tout le reste — ne pas
écrire le mixin avant.

## Mise à jour — étape 1 faite, vrai blocage trouvé à l'étape 4

**Étape 1, confirmée en bytecode réel de la 26.3** (`javap -c -l` sur
`GameRenderer.class`, jamais `.mcsrc/`) : `renderLevel()`, variable locale
**6** — seul `Matrix4f` de la méthode, construit `new Matrix4f(camera.projectionMatrix)`
à l'offset 50-62, muté par le bobbing (vue/coup/rotation) jusqu'à l'offset 255,
consommé par `levelProjectionMatrixBuffer.getBuffer(Lorg/joml/Matrix4f;)` à
l'offset 262. Mixin à poser : `@ModifyVariable(method = "renderLevel", at =
@At(value = "INVOKE", target = ".../ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)..."),
ordinal = 0)` — ordinal 0 suffit, c'est le seul `Matrix4f` local de la méthode.

Pour `Reproject.advance()` : ne pas essayer de lire la variable locale 6 (elle
porte déjà le bobbing, pas la vue pure). `CameraRenderState` (variable locale 5
dans la même méthode) expose directement `projectionMatrix`, `viewRotationMatrix`
et `pos` en champs publics (vérifié par `javap -p`) — les lire tels quels à la
tête de `renderLevel()` donne exactement les matrices non décalées que
`Reproject.advance()` attend, sans dépendre du bobbing local.

**Livré et compilé, mais volontairement PAS branché** : `lentille_temporelle`
dans `ClientConfig` (défaut `false`), `Upscale.temporal()` (accesseur, lu par
personne pour l'instant), `shaders/post/temporal_accumulate.fsh`,
`post_effect/upscale_temporal_accumulate.json`. Rien de tout ça n'a d'effet :
aucun mixin ni aucun appelant ne les utilise encore — même état que `Jitter`/
`Reproject` avant cette passe.

**Le vrai blocage, trouvé à l'étape 4, pas supposé** : `PostChain`/`PostPass`
n'exposent **aucune API pour changer un uniforme après construction** — vérifié
par `javap -p` sur `PostChain.class` et `PostPass.class` dans le vrai jar. Les
`UniformValue` d'un JSON (voir `RcasConfig`/`Tuning` dans `upscale_aa_moyenne.json`)
sont figés à la compilation de la passe, exactement comme le documente déjà
l'en-tête de `Resolve.java` pour la netteté (« il faudrait bâtir le pipeline à
la main »). Or la matrice de reprojection change **à chaque image** — c'est
tout le principe de `Reproject`. Il est donc impossible de la faire passer par
le mécanisme JSON existant sans recompiler la chaîne à chaque image (inenvisageable :
un shader qui recompile 60 fois par seconde tue le FPS bien plus sûrement qu'il
ne le gagne).

**Deux vraies pistes pour la prochaine passe, ni essayées ni écartées** :
1. Chercher si `com.mojang.renderpearl` expose un mécanisme d'uniforme
   "dynamique"/calculé par image en dehors du JSON de `PostChain` — les blocs
   `SamplerInfo` (`InSize`/`OutSize`) sont bien recalculés à chaque image sans
   recompilation, donc un tel mécanisme existe forcément quelque part dans le
   moteur ; il reste à trouver comment un mod peut y accrocher son propre
   uniforme plutôt que d'en déduire qu'il n'existe pas.
2. Construire un `RenderPipeline` à la main pour cette seule passe (pas pour
   EASU/RCAS, qui restent sur `PostChain`) — plus de code, mais donne un accès
   direct au tampon d'uniformes sans passer par le JSON. C'est le détour que
   l'en-tête de `Resolve.java` évitait pour EASU/RCAS ; il redevient
   probablement nécessaire ici, pour cette seule passe qui a un besoin que
   `PostChain` ne sait pas satisfaire.

Ne pas retenter l'étape 4 sans avoir résolu ce point précis en premier — écrire
le nuanceur et le mixin avant serait retravailler pour rien.

## Mise à jour — le mécanisme `SamplerInfo` compris jusqu'au bout, piste 1 fermée proprement

Reprise sur les deux pistes laissées ouvertes. Lu `net/minecraft/client/renderer/PostPass.class`
en entier par `javap -p -c` (jamais `.mcsrc/`) pour comprendre précisément comment `SamplerInfo`
échappe à la règle « figé à la construction » — et la réponse ferme la piste 1 plutôt que de
l'ouvrir.

**Ce qui se passe réellement, ligne par ligne** :
- Le constructeur de `PostPass` prend un `Map<String, List<UniformValue>>` (les uniformes
  *déclarés dans le JSON*, ex. `Tuning` pour RCAS) et crée pour CHACUN un `GpuBuffer` figé une
  fois pour toutes — c'est `customUniforms`, et c'est exactement le blocage déjà documenté
  ci-dessus. Rien de nouveau ici.
- `SamplerInfo` n'en fait **pas partie**. C'est un champ séparé, `infoUbo`, un
  `MappableRingBuffer` dimensionné à la construction (`(inputs.size()+1) * UBO_SIZE_PER_SAMPLER`)
  mais dont le **contenu** est réécrit à chaque image, dans le lambda d'exécution
  (`lambda$addToFrame$1`) : `infoUbo.currentBuffer().map(...)`, un `Std140Builder` qui écrit
  `putVec2(largeur, hauteur)` de la cible de sortie puis un `putVec2` par texture d'entrée, la
  vue mappée se referme, et **alors seulement** `renderPass.setUniform("SamplerInfo",
  infoUbo.currentBuffer())` est appelé — juste avant `renderPass.draw(3,1,0,0)`.

**Conclusion, sans ambiguïté** : `SamplerInfo` est un cas **spécial et câblé en dur** dans
`PostPass` lui-même (le moteur sait qu'il porte des tailles de texture, rien d'autre) — ce n'est
pas un point d'extension générique où un mod pourrait accrocher sa propre donnée par image. Le
faire quand même demanderait de :
1. rediriger la construction de `infoUbo` (taille) pour NOTRE passe spécifiquement (comparer
   `this.name`/l'identifiant du pipeline dans le mixin, pour ne toucher à rien d'autre) ;
2. rediriger l'écriture dans le lambda pour ajouter 16 flottants après les `putVec2` existants ;
3. faire correspondre exactement la disposition std140 attendue côté `.fsh`.

Techniquement faisable par mixin ciblé (deux `@Redirect`/`@ModifyExpressionValue`, conditionnés
sur l'identité de la passe), mais ça revient à réécrire silencieusement le contrat interne d'une
classe partagée par **toutes** les passes de post-traitement du jeu, EASU/RCAS inclus — un mixin
mal contraint qui se déclenche sur la mauvaise passe casserait la remontée d'échelle existante,
stable en production. Risque jugé disproportionné par rapport à la piste 2, qui n'a aucun tel
risque de bord.

**Piste 2 retenue, et maintenant équipée d'une vraie recette** — la séquence d'appels exacte pour
construire et exécuter une passe plein écran à la main, lue directement dans le même
désassemblage (`lambda$addToFrame$1` de `PostPass`, offsets 213-427) :

```java
var encoder = RenderSystem.getDevice().createCommandEncoder();
try (var pass = encoder.createRenderPass(
        () -> "lanterne temporal_accumulate",
        outputTarget.getColorTextureView(),
        Optional.empty(),          // pas de vidage de couleur, on écrit tout
        null,                       // pas de cible de profondeur
        OptionalDouble.empty())) {
    pass.setPipeline(RenderSystem.getCompiledPipeline(NOTRE_PIPELINE));
    RenderSystem.bindDefaultUniforms(pass);
    pass.setUniform("Reproject", notreBufferReecritChaqueImage);   // NOTRE uniforme, à nous
    pass.setUniform("SceneSampler", sceneView, sampler);
    pass.setUniform("HistorySampler", historyView, sampler);
    pass.draw(3, 1, 0, 0);   // triangle plein écran, convention déjà vue partout dans ce fichier
}
```

`notreBufferReecritChaqueImage` : un `GpuBuffer` qu'on possède entièrement (créé une fois via
`GpuDevice.createBuffer(...)`, comme `customUniforms` le fait déjà pour les uniformes statiques —
sauf qu'on le réécrit nous-mêmes à chaque image avant `setUniform`, exactement comme `PostPass`
réécrit `infoUbo`). Zéro dépendance sur le mécanisme interne de `PostPass`/`PostChain` : cette
passe vit entièrement à côté, hors du système de chaînes JSON. EASU/RCAS n'en savent rien et
continuent de passer par `PostChain` sans aucun changement.

Le `RenderPipeline` lui-même (format de vertex trivial — pas de sommets, un triangle plein écran
généré dans le vertex shader comme le fait déjà `PostPass` pour ses propres passes, voir le motif
`draw(3,1,0,0)` sans tampon de sommets lié) se construit avec l'API publique
`RenderPipeline.builder(...)` déjà utilisée ailleurs dans ce dépôt pour comprendre
`RenderPipelines` — pas de mixin nécessaire pour ce point précis, juste de la construction
d'objet.

**Non fait, honnêtement, faute de budget de contexte dans cette passe** : écrire la classe qui
porte ce code (probablement `client/upscale/Accumulate.java`, sœur de `Resolve`), le mixin de
jitter (`@ModifyVariable` sur `GameRenderer.renderLevel`, point d'injection déjà confirmé plus
haut dans ce fichier), la texture d'historique dans `Scene`, le branchement dans `Upscale`/`give`,
et surtout la vérification sur un vrai client en mouvement. `lentille_temporelle` reste à `false`
par défaut, aucun comportement joueur n'a changé dans cette passe.

**Prochaine étape, sans ambiguïté restante** : écrire `Accumulate.java` en suivant la recette
ci-dessus au caractère près (elle vient du bytecode réel, pas d'une supposition), puis les quatre
autres pièces du plan original (mixin, historique, branchement, vérification en jeu). Le seul vrai
inconnu technique de ce chantier — comment injecter une donnée par image sans passer par le JSON
figé — est maintenant résolu et documenté ; ce qui reste est de l'assemblage, pas de la recherche.
