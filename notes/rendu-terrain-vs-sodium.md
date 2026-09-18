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

## Tentative de greedy meshing — le point d'accroche exact trouvé, l'implémentation refusée, et pourquoi

Poursuite de l'audit ci-dessus, avec pour instruction explicite d'implémenter le greedy
meshing sur le point d'accroche identifié. Le point d'accroche a bien été localisé et
vérifié par lecture de bytecode réel (`javap -c` sur le vrai jar, comme toujours) :

- `SectionCompiler.compile(...)` construit deux `BlockQuadOutput` via lambda
  (`lambda$compile$0`/`lambda$compile$1`, deux méthodes privées réelles et stables — pas
  des invokedynamic fragiles à cibler, un mixin `@Inject`/`@Redirect` classique fonctionne
  dessus sans risque particulier).
- Chacune fait exactement deux choses : `getOrBeginLayer(map, pack, quad.materialInfo().layer())`
  pour récupérer le `BufferBuilder` de la bonne couche, puis
  `bufferBuilder.putBlockBakedQuad(x, y, z, quad, instance)`.
- Intercepter ces deux méthodes (tampon par thread le temps d'une compilation de section,
  fusion, puis rejeu vers `putBlockBakedQuad` pour les quads fusionnés) est architecturalement
  sûr et directement faisable — ça n'a pas été le blocage.

**Le vrai blocage, découvert en vérifiant le pipeline de texture avant d'écrire une ligne de
fusion** : `BakedQuad.packedUV(int)` porte une coordonnée UV **littérale et fixe par sommet**
dans l'espace de l'atlas — pas un indicateur de répétition, pas de mode de bouclage. Vérifié :
`TextureAtlasSprite` n'expose aucune méthode de tuilage/répétition (`javap` : aucune méthode
`wrap`/`repeat`/`tile`), et `BufferBuilder` ne fait qu'empaqueter les UV donnés tels quels
(`putPackedUv`, pas de logique de répétition).

Concrètement : fusionner deux faces adjacentes en un seul quad deux fois plus large, en
étirant linéairement les UV du premier sommet au dernier, **étire la texture au lieu de la
répéter** — un bloc de pierre fusionné sur 2 de large afficherait une texture de pierre
écrasée/floue au lieu de deux textures de pierre côte à côte. C'est une régression visuelle
réelle et systématique, pas un cas limite : c'est vrai pour QUASIMENT tous les blocs de
terrain (herbe, pierre, sable, bois — aucun n'a une texture assez uniforme pour masquer
l'étirement). Sodium résout ce problème avec son propre shader, qui échantillonne l'atlas en
UV modulo/fractionnaire contraint aux bornes du sprite d'origine — une fonctionnalité de
pipeline de rendu, pas un algorithme de maillage.

**Décision** : ne pas livrer de fusion de géométrie qui casse le texturage. Un module éteint
par défaut ne protège pas ici — le seul état "sûr" testé sans client graphique serait un état
où la fusion ne fait jamais rien d'observable, ce qui ne vaut pas la peine d'être écrit. Zéro
ligne de mixin de fusion commise dans cette passe.

## Ce qu'il faudrait vraiment pour faire du greedy meshing correct ici

Deux pistes, ni l'une ni l'autre faite dans ce budget :

1. **Un shader de fragment personnalisé** sur les couches de rendu de section, qui
   recalcule l'UV échantillonné en `sprite.min + fract(uv_interpolé * largeur_fusionnée) *
   sprite.taille` — répète la texture dans le sprite d'origine plutôt que de l'étirer. C'est
   ce que fait réellement Sodium. Demande de toucher au pipeline `renderpearl` (shaders/passes
   de rendu), pas seulement au maillage — un chantier de rendu, pas un chantier de mixin.
2. **Fusionner uniquement les textures qui tolèrent l'étirement sans qu'on le voie** (eau,
   couleur unie) — gain marginal, la plupart du terrain (pierre, herbe, sable, bois) ne
   qualifie pas.

La piste 1 est la seule qui vaudrait la peine, et elle sort du périmètre "mixin sur le
maillage" pour entrer dans celui du pipeline de rendu de `renderpearl` lui-même — à traiter
comme un chantier séparé, avec le temps de le faire et de le vérifier proprement.
