# Rendu de terrain, ce qui reste réellement à faire pour égaler Sodium

Audit fait après retrait de Sodium de l'instance de test, sur la base d'une hypothèse
reçue en directive : "Sodium gagne principalement sur (a) le culling de faces entre blocs
adjacents et (b) la compilation de section multithreadée — vérifie si vanilla les a déjà."

**Les deux hypothèses de départ sont fausses. Vérifié par lecture directe du bytecode du
vrai jar client** (`C:\Users\trufa\.gradle\caches\neoformruntime\artifacts\minecraft_26.3_client.jar`
— pas `.mcsrc/`, périmé et référence encore l'ancien `com.mojang.blaze3d` pour le rendu de
terrain, alors que le vrai jar utilise `com.mojang.renderpearl`).

## (a) Culling de faces entre blocs adjacents — déjà présent

`net.minecraft.client.renderer.block.ModelBlockRenderer.shouldRenderFace(...)` appelle
directement `Block.shouldRenderFace(BlockState, BlockState, Direction)` :

```
private boolean shouldRenderFace(BlockAndTintGetter, BlockState, Direction, BlockPos)
  cull == false -> return true
  sinon -> return Block.shouldRenderFace(état_courant, état_voisin, direction)
```

C'est exactement le mécanisme que Sodium apporte historiquement. Rien à construire ici.

## (b) Compilation de section multithreadée — déjà présent

`SectionRenderDispatcher` a un champ `TracingExecutor executor` injecté au constructeur,
et `SectionRenderDispatcher$RenderSection$CompileTask` existe comme tâche dédiée. La
compilation d'une section ne bloque pas le fil de rendu. Rien à construire ici non plus.

## Le vrai écart : le greedy meshing (fusion de faces), absent

Recherché explicitement dans le jar entier (`unzip -l | grep -i greedy`) : aucune classe
liée à la fusion de faces/quads. `SectionCompiler.compile(...)` émet un `BakedQuad` par
face visible de chaque bloc (voir ses méthodes `lambda$compile$0/1/2`, qui alimentent un
`BufferBuilder` par `ChunkSectionLayer` sans étape de fusion). Sodium fusionne les faces
coplanaires de même texture/luminosité en quads plus grands, ce qui réduit le nombre de
sommets et d'appels de dessin envoyés au GPU — c'est là que vient une bonne part de son
gain FPS réel sur le rendu de terrain, pas sur le culling ou le threading (déjà couverts).

## Pourquoi ce n'est pas fait dans cette passe

Le point d'accroche exact est `SectionCompiler.compile(SectionPos, RenderSectionRegion,
VertexSorting, SectionBufferBuilderPack)` — modifier l'émission de quads pour fusionner
des faces adjacentes est un algorithme non trivial (balayage par plan, calcul de
run-length, gestion de l'AO et de la teinte par sommet qui doivent rester cohérentes après
fusion) et une implémentation incorrecte ne ralentit rien : elle produit des trous ou des
artefacts visuels dans le terrain. Aucun moyen de le vérifier visuellement dans ce fork
(pas de client graphique disponible), et ce dépôt refuse explicitement ce genre de pari à
l'aveugle ailleurs (voir `notes/PLAN.md`, section FSR). Reste un chantier à part entière,
chiffré et localisé précisément ci-dessus, pour une session avec le temps et les moyens de
vérifier visuellement chaque étape.

## Ce qui reste vrai malgré tout

Retirer Sodium ne casse rien (confirmé par un fork précédent : les optimisations de
Lanterne sur les entités sont indépendantes du rendu de terrain). La perte réelle en FPS
sur le terrain vient spécifiquement de l'absence de greedy meshing, pas d'un manque de
culling ou de threading — les deux étaient déjà au niveau de Sodium sur ces points précis.
