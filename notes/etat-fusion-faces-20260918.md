# Greedy meshing terrain — état au 2026-09-18 17:5x, interrompu en cours d'investigation

> **Suite et résolution (même jour, passe suivante) : cause trouvée et corrigée — voir la
> section "Le vrai écart : le greedy meshing" de `notes/rendu-terrain-vs-sodium.md`.** Ce
> n'était pas un bug de shader (toutes les pistes ci-dessous ont fini par être épuisées et
> innocentées avec preuve) mais un `QuadInstance` mutable/réutilisé par `ModelBlockRenderer`
> capturé par référence au lieu d'un instantané dans `Greedy.Pending`. Le reste de ce fichier
> est laissé tel quel comme trace de l'investigation qui a permis d'éliminer les fausses
> pistes ; ne pas rejouer les hypothèses 1/2/3 ci-dessous, déjà closes avant cette résolution.

Reprise de la passe `679bd57`/`3199cdd` (carrés gris sur sol fusionné, cause non confirmée).
Interrompu par l'utilisateur qui quitte son poste — **aucun code de correction commis dans
cette passe**, seulement de nouvelles preuves qui réduisent fortement le champ des causes
possibles. Écrit pour qu'une prochaine passe reparte directement de la piste ouverte à la
fin, sans rejouer l'élimination ci-dessous.

## Fait

- Rien de nouveau commis. `Greedy.java`, `TerrainVertexFormat.java`, `SectionCompilerMixin`,
  `RenderPipelinesMixin`, `FluidRendererMixin`, `terrain.vsh`/`.fsh` sont dans l'état exact
  laissé par `3199cdd` (vérifié : `git status` propre sur ces fichiers, seuls des fichiers
  d'un autre fork — `Lanterne.java`, `Config.java`, `Settings.java`, `SelfTest.java`,
  `lanterne.mixins.json`, `Loterie*.java`, `lab/Billet.java` — restent modifiés/non commis,
  et n'ont pas été touchés).
- Un shader de diagnostic temporaire a été écrit à nouveau dans `terrain.fsh`
  (`lanterneSample`, gated par un `#define LANTERNE_DEBUG_LOD 1` local), utilisé pour trois
  tests visuels successifs, puis **entièrement retiré avant la fin du tour** — `git diff`
  sur `terrain.fsh` est vide, comme après les passes précédentes. Si une prochaine passe veut
  le retrouver rapidement plutôt que le réécrire : la recette est décrite ci-dessous
  (section Mesuré), pas seulement le résultat.
- Découverte annexe utile : `run/options.txt` avait `preferredGraphicsBackend:"opengl"`
  écrit en dur (pas un repli silencieux — un choix explicite laissé par une passe
  antérieure). Changé en `"vulkan"` dans ce fichier de run (pas du code source, rien à
  committer) pour retester sur le vrai backend Vulkan visé par ce chantier. Confirmé au log :
  `Using graphics backend Vulkan, using drivers: 1.4.351 NVIDIA 616.92` sur le GPU discret
  (`Preferring discrete GPU: NVIDIA GeForce RTX 3080 Laptop GPU`) — Vulkan fonctionne bien sur
  cette machine malgré le fonctionnement sur batterie, une erreur Overwolf sur une couche
  Vulkan tierce (`ow-graphics-vulkan.dll`) est inoffensive (loggée en ERROR mais suivie d'une
  init Vulkan réussie). **Si une prochaine passe relance le client, vérifier que
  `run/options.txt` a toujours `preferredGraphicsBackend:"vulkan"`** avant de tirer une
  conclusion visuelle — sinon le test retombe sur OpenGL sans avertissement.

## Mesuré

**Avertissement de l'utilisateur reçu en cours de session, à respecter pour toute mesure
FUTURE de FPS** : le PC était sur batterie (confirmé par
`(Get-WmiObject -Namespace root\wmi -Class BatteryStatus).PowerOnline` → `False`) pendant
toute cette passe. Aucune mesure de FPS/Radiographie n'a été prise dans cette passe (voir
"Reste à faire") donc ce n'est pas un problème pour ce qui suit, mais la prochaine passe doit
soit mesurer sur secteur, soit noter explicitement l'état batterie dans son propre rapport —
la note ne s'applique qu'aux chiffres de PERFORMANCE (FPS/ms/frame), pas au diagnostic visuel
ci-dessous qui ne dépend pas de la vitesse de la machine.

### Hypothèses testées et éliminées avec preuve

1. **Niveau de mip trop élevé (mauvaise sélection de LOD)** — ÉLIMINÉE. Shader de
   diagnostic : calcule `rho = max(length(du/pixelSize), length(dv/pixelSize))`,
   `lod = log2(rho)`, affiché en fausses couleurs (vert=mip0 … magenta=mip4+). Résultat sur
   le même sol de neige fusionné qui affiche des carrés gris en rendu normal : **mip 0 (vert)
   uniformément sur tout le terrain, fusionné ou non**. La dérivée écran n'est donc PAS
   anormalement grande sur les quads fusionnés — élimine l'hypothèse d'une discontinuité de
   dérivée résiduelle (au-delà de celle déjà corrigée en `679bd57`).
2. **Bornes de sprite (UV1) illisibles côté shader** — déjà éliminée par la passe précédente
   (`3199cdd`), reconfirmée non contredite par les tests de cette passe.
3. **`mergedU` mal calculé côté Java (mauvais delta, mauvaise extrapolation)** — ÉLIMINÉE,
   avec preuve chiffrée cette fois (`LANTERNE_GREEDY_DIAG=1`, log réel capturé sur Vulkan à
   17:44:13) :
   ```
   run=3 first=(8,5,5) last=(10,5,5) spriteMin=(0.40625,0.5546875) spriteSize=(0.0078125,0.0078125)
   fu=[0.40625,0.40625,0.4140625,0.4140625] deltaU=[0.0078125,0.0078125]
   farU_k0=0.4296875 farU_k1=0.4296875
   ```
   Vérification arithmétique : `(farU - spriteMin.u) / spriteSize.u = (0.4296875-0.40625)/0.0078125
   = 3.0` exactement — `local` à l'extrémité "haute" du quad fusionné vaut bien `runLength`
   (3.0 pour ce run de 3 blocs), pas une valeur proche de 0. Un second exemple (`run=4`)
   confirme : `local` extrapolé = 4.0 exactement. **Le calcul Java de `mergedU` est donc
   correct**, contrairement à l'hypothèse de travail formée en début de passe.

### Piste ouverte, non résolue — contradiction constatée, pas expliquée

Un second shader de diagnostic (bandes dures bleu/jaune sur `mod(floor(local.x)+floor(local.y), 2.0)`,
censé montrer N bandes distinctes pour un run de longueur N) devait trancher si le motif se
répète vraiment N fois à l'écran. **Ce test n'a pas pu être relancé avant l'interruption** —
la construction (`./gradlew runClient`) a échoué deux fois de suite juste après l'édit du
shader, avec `java.net.ConnectException: Connection timed out` sur la connexion boucle-locale
Gradle Worker Daemon ↔ daemon (voir `bny1tuxbo`/`b066del6g`/`blifso5mq` dans les logs de
tâches de fond de cette session) — semble une instabilité réseau/ressources de la machine
(beaucoup de daemons Gradle arrêtés accumulés, `./gradlew --stop` puis retenté sans succès
avant l'interruption), pas un problème de code. **Pas de conclusion sur cette piste.**

Le test précédent (dégradé continu rouge-vert = `fract(local)` visualisé directement,
confirmé rejoué à l'identique en OpenGL ET en Vulkan — donc pas un artefact de driver) montrait
sur les zones plates fusionnées **un seul dégradé lisse par "case" visible à l'écran, sans
répétition visible** — ce qui semblerait contredire le point 3 ci-dessus (`local` DEVRAIT
parcourir `[0, runLength]`, donc répéter `runLength` fois). Deux lectures possibles, ni
l'une ni l'autre confirmée :

- Chaque "case" visible à l'écran ne correspond peut-être pas à UN SEUL quad fusionné du
  journal (les coordonnées journalisées sont relatives à la SECTION, pas absolues — impossible
  de corréler avec certitude quelle case à l'écran vient de quel run journalisé). Le
  dégradé continu pourrait très bien être 3-4 répétitions d'un motif trop fin pour être
  distingué à l'œil dans un dégradé continu (contrairement à des bandes dures) — d'où le
  second test en bandes dures, non encore obtenu.
- Ou alors il y a un vrai désaccord entre ce que Java calcule/journalise et ce que le GPU
  reçoit réellement pour `UV0` sur les sommets "haut" — pas encore exclu, pas encore prouvé
  non plus.

**Ne pas re-tester les hypothèses 1 et 2 ci-dessus** (mip, bornes de sprite illisibles) —
déjà éliminées avec preuve. **Ne pas re-tester l'hypothèse "mergedU mal calculé côté Java"**
sans données contraires — le journal `LANTERNE_GREEDY_DIAG=1` montre un calcul correct pour
les 6 premiers runs capturés.

## Reste à faire

1. **Priorité immédiate** : relancer `./gradlew runClient` (`LANTERNE_GREEDY_MESH=1
   LANTERNE_AUTO_SCREENSHOT=1 LANTERNE_SAVE="New World (1)"`, `run/options.txt` déjà réglé
   sur `preferredGraphicsBackend:"vulkan"`) et réécrire le shader de diagnostic à bandes
   dures dans `terrain.fsh` (fonction `lanterneSample`, remplacer le corps par un test sur
   `mod(floor(local.x) + floor(local.y), 2.0)` — voir "Mesuré" ci-dessus pour le code exact
   qui n'a pas pu être testé). Si les bandes dures montrent bien N répétitions par quad
   fusionné → le bug n'est PAS dans `mergedU`/`local`, il faut regarder en aval (échantillonnage
   `textureGrad` lui-même, `sampleNearest` 6-arguments dans `texture_sampling.glsl` extrait du
   jar réel — déjà lu cette passe, disponible via
   `unzip -p "C:\Users\trufa\.gradle\caches\neoformruntime\artifacts\minecraft_26.3_client.jar"
   "assets/minecraft/shaders/include/texture_sampling.glsl"` si besoin de le relire). Si UNE
   SEULE bande par quad → le désaccord Java/GPU est réel, regarder si `.setUv()` sur le
   `VertexConsumer`/`BufferBuilder` fait un clamp ou une normalisation inattendue pour un
   `RG32_FLOAT` (vérifier par `javap -c` sur `BufferBuilder`, jamais supposé).
2. **Si l'instabilité réseau Gradle revient** : `./gradlew --stop` avant de relancer (déjà
   fait une fois cette passe, a réduit le nombre de daemons zombies mais pas éliminé le
   problème de connexion) ; envisager de vérifier la mémoire libre
   (`Get-CimInstance Win32_OperatingSystem`, était descendue à 4.2 Go libre sur 31 Go en fin
   de session) et fermer des daemons Gradle inutiles avant de relancer un client lourd.
3. **Une fois la vraie cause confirmée et corrigée** : vérifier visuellement (Snap, même
   monde `New World (1)`), puis SEULEMENT ENSUITE mesurer le gain réel (sommets/draw calls
   et/ou FPS via Radiographie, `LANTERNE_GREEDY_MESH=1` vs non défini, sur secteur si possible
   pour un chiffre fiable) — non fait dans cette passe ni les précédentes, toujours en
   attente d'un rendu correct.
4. Items non explorés hérités de `3199cdd`, toujours ouverts et non contredits par cette
   passe : vérifier si le bug est spécifique aux runs longs ; les "traînées rouges" sur les
   pentes vues dans le diagnostic `spriteSize` de la passe précédente restent non expliquées
   (probablement un problème séparé, pas re-testé cette passe).
5. Latent, pas actif dans le cas testé (sol de neige uniforme) mais jamais corrigé : `mergeable()`
   dans `Greedy.java` ne vérifie pas que `first` et `last` partagent la même géométrie de quad
   (même modèle de bloc) — deux blocs différents partageant par coïncidence sprite/couleur/lumière
   identiques mais un modèle de face différent (ex. bloc plein vs dalle) pourraient fusionner avec
   un indexage de sommet incohérent. Pas la cause du bug actuel (terrain uniforme), mais à
   corriger avant d'activer `Greedy.ENABLED` par défaut.
