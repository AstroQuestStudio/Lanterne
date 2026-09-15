# Le pochoir des structures — ce qu'il faisait, et pourquoi il a été retiré

> Retiré le **15 septembre 2026**, sur Minecraft 26.2 / NeoForge 26.2.0.88.
> Cette note existe pour une raison précise : **quelqu'un réécrira ce module dans deux ans** en
> relisant `StructureTemplate.placeInWorld` et en y voyant le même gaspillage qu'on y avait vu. Il
> ne l'y verra plus. Voici de quoi s'en convaincre sans refaire le chemin.

## Ce qu'il y avait

Trois fichiers, plus une entrée de réglage, un mot-clé et une ligne d'écran :

| Fichier | Rôle |
|---|---|
| `core/Stencil.java` | Le tri : réduire la liste des blocs d'un gabarit à ceux qui tombent dans la fenêtre du chunk. |
| `mixin/TemplateStencilMixin.java` | Un `@Redirect` sur `StructureTemplate$Palette.blocks()` dans `placeInWorld`. |
| `lab/Mason.java` | L'épreuve du maçon (`LANTERNE_MASON=1`) : vitesse **et** conformité, gabarit posé à cheval sur une fenêtre de seize blocs. |

Réglage : `pochoir_structures` dans `config/lanterne-server.toml`. Mot-clé : `pochoir` / `stencil`.
Ligne d'écran : « Pochoir des structures », famille « Le monde ».

## Le raisonnement, et il était juste en 26.1.2

Une structure ne se pose pas d'un coup : elle se pose **chunk par chunk**, au fur et à mesure que la
génération atteint chacun d'eux. `ChunkGenerator.getWritableArea` fabrique une boîte de seize blocs
de côté et `StructureStart.placeInChunk` rappelle `placeInWorld` pour chaque pièce qui la touche.

En 26.1.2, `placeInWorld` s'écrivait ainsi :

```java
List<StructureBlockInfo> processed = processBlockInfos(level, position, …, palette.blocks(), this);
for (StructureBlockInfo info : processed) {
    if (boundingBox == null || boundingBox.isInside(info.pos)) {   // ← le tri arrive ICI
        …
    }
}
```

Le tri arrivait **après** la préparation. Or la préparation n'est pas gratuite : pour *chacun* des
blocs du gabarit, `processBlockInfos` calcule la position transformée, fabrique un
`StructureBlockInfo` neuf, **recopie sa balise NBT** si elle en a une, et le fait traverser toute la
chaîne des `StructureProcessor`. Une maison de village qui chevauche quatre chunks était donc
préparée **quatre fois en entier** pour n'en écrire qu'un quart à chaque passage.

Le module posait le pochoir avant la peinture : la liste remise à `processBlockInfos` était réduite
d'avance. Idée reprise de **StructureLayoutOptimizer** (TelepathicGrunt, MIT).

## Ce qui a changé en 26.2

`C:/mc262src/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java`,
lignes 445-458 — la 26.2 patchée NeoForge écrit autre chose :

```java
boolean processOnlyInCurrentChunk = true;
for (StructureProcessor processor : settings.getProcessors()) {
    if (processor.evaluatesEntirePieceState()) { processOnlyInCurrentChunk = false; break; }
}
BoundingBox chunkBb = settings.getBoundingBox();
for (StructureBlockInfo blockInfo : blockInfoList) {
    BlockPos blockPos = calculateRelativePosition(settings, blockInfo.pos).offset(position);
    if (!processOnlyInCurrentChunk || chunkBb == null || chunkBb.isInside(blockPos)) {   // ← ICI
        StructureBlockInfo processed = new StructureBlockInfo(blockPos, blockInfo.state,
                blockInfo.nbt != null ? blockInfo.nbt.copy() : null);
        …toute la chaîne des processeurs…
    }
}
```

**L'objet neuf, la copie de balise et la chaîne de processeurs sont désormais derrière le test de
fenêtre.** Il ne reste au pochoir que la transformation de position — et il l'ajoute une fois de
plus, puisque `processBlockInfos` la refait ligne 457 pour les blocs qui passent.

Le garde-fou NeoForge `evaluatesEntirePieceState` déclenche sur le même ensemble que le troisième
garde-fou du pochoir : `CappedProcessor` est le seul processeur de vanilla à redéfinir
`finalizeProcessing`, et le seul à rendre `true`. Le pochoir était donc devenu un **sous-ensemble
strict** du comportement de NeoForge, en plus prudent.

## La mesure qui a tranché

Épreuve du maçon, `plains_big_house_1` (7 × 11 × 11, 847 positions), fenêtre de seize blocs, ancre
décalée pour que le gabarit déborde. Médianes de quatre cents poses par côté, alternées.

| Version de Minecraft | gabarit nu | gabarit + les 2 processeurs de toute pièce à jigsaw |
|---|---:|---:|
| 26.1.2 | ×1,17 ×1,10 ×1,18 ×1,16 ×1,17 | ×1,54 ×1,32 ×1,30 ×1,52 ×1,52 |
| **26.2 / NeoForge 26.2.0.88** | **×1,07** | **×1,06** |

```
tri : 326 400 blocs examinés, 224 400 écartés (68,8 %), 0 renoncement
conformité : 15 360 positions sur quatre orientations, AUCUN écart
```

Les deux régimes de 26.2 sont **sous la dérive de ×1,08 du laboratoire**. Le module fonctionnait
toujours — le taux d'écart est resté à 68,8 % — mais ce qu'il écartait ne coûtait plus rien, parce
que le jeu l'écartait déjà.

**Les ×1,17 et ×1,54 ne doivent plus être republiés.** Ils sont vrais de la version où ils ont été
pris ; ils sont faux de celle qu'on livre.

## Pourquoi retirer plutôt que garder

Le module ne *perdait* rien : les deux mesures penchaient du bon côté, faiblement et dans le même
sens. La question à trancher n'était donc pas « rapporte-t-il ? » mais « justifie-t-il un point
d'accroche sur `placeInWorld` ? ».

Non, et pour trois raisons :

1. **Un `@Redirect` sur `StructureTemplate$Palette.blocks()` est un conflit en attente.** C'est le
   point d'accroche que tout mod de structures veut. Le tenir pour ×1,06 est un mauvais échange.
2. **Le tri se paie sur tous les appels**, y compris ceux qui n'écartent rien — un puits de village,
   un iglou, toute structure entièrement contenue dans un chunk.
3. **Le module lisait `Settings` depuis le pool de travail**, seul module du dépôt dans ce cas. Ce
   n'était pas dangereux (la javadoc de `Settings.stencil()` expliquait pourquoi), mais c'est une
   exception de moins à entretenir.

## Ce qui reste vrai, et qu'il ne faut pas jeter avec

Trois enseignements du pochoir survivent au pochoir, et ils comptent pour le module qui le remplace
(`core/Cadastre.java`) :

- **Une liste de blocs vide fait retirer la pièce du `StructureStart`.** `placeInWorld` rend alors
  `false`, et l'appelant retire la pièce — ce qui l'empêche de se poser dans les *autres* chunks
  qu'elle devait encore traverser. Le mod d'origine porte ici un commentaire en majuscules ; il a
  manifestement payé pour l'apprendre. Nous l'avons évité en réinsérant le premier bloc.
- **`GravityProcessor` déplace les blocs en hauteur** (`new BlockPos(x, level.getHeight(…) + offset
  + yLocal, z)`). Tout tri sur Y est donc faux en présence de ce processeur. Le pochoir ne triait que
  sur X et Z pour cette raison.
- **La génération de terrain ne coûte rien au temps de tick.** Elle s'exécute sur le pool de
  travail ; ce qu'on y gagne est de la **latence d'exploration**, pas des TPS. Voir
  `notes/serveur-un-coeur.md`. Le README ne doit pas les confondre.

## Si l'on veut le réécrire un jour

Deux conditions, et les deux sont vérifiables en dix minutes :

1. Relire `StructureTemplate.placeInWorld` dans les sources décompilées de la version visée. Si le
   test `chunkBb.isInside(blockPos)` est **toujours avant** la construction du `StructureBlockInfo`,
   le module n'a rien à récupérer.
2. Si Mojang ou NeoForge le remettait après, refaire l'épreuve du maçon avant d'écrire une ligne.
   Elle est supprimée avec le module, mais elle est courte et son protocole est décrit ci-dessus :
   un vrai gabarit du jeu, une fenêtre de seize, une ancre qui déborde, deux régimes (nu et avec les
   deux processeurs que `SinglePoolElement.place` ajoute à chaque pièce), et surtout **la conformité
   sur quatre orientations** — une erreur de signe dans `calculateRelativePosition` est invisible
   sans rotation ni miroir, et son symptôme serait un mur de maison manquant sur la moitié des
   villages du monde.
