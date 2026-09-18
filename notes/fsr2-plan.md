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
