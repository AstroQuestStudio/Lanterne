# Les ouvriers — cadrage avant construction

## Ce qui est demandé

Des ouvriers auxquels on assigne un lieu de travail, qu'on équipe, et qui **remplacent un joueur** :
miner une zone, bâtir une schématique, rapporter le butin. Le rendement dépend du matériel qu'on
leur donne.

## Pourquoi ce n'est pas un module de plus

Tout ce que ce mod a livré jusqu'ici tient dans un ou deux fichiers et se juge par un chiffre. Les
ouvriers, non :

| Pièce | Ce qu'elle demande |
|---|---|
| L'entité | enregistrement, attributs, sérialisation, synchronisation client |
| **Le rendu** | modèle, couches, `EntityRenderer`, animations |
| L'IA | objectifs, navigation, reprise après échec, gestion de l'inventaire |
| L'assignation | interface de sélection de zone, persistance, permissions |
| Les schématiques | format de fichier, lecture, placement, gestion des matériaux manquants |
| L'équilibrage | vitesse selon l'outil, usure, carburant ou nourriture |

Six chantiers, dont un — le rendu — que cette session vient de rencontrer sous sa pire forme.

## Le risque que cette session a mis en évidence

L'API de rendu de 26.1 a changé en profondeur, et je l'ai découvert en écrivant les repères :

```
GuiGraphics        ->  GuiGraphicsExtractor
drawString         ->  text
Renderable.render  ->  extractRenderState      <- le rendu n'est plus un dessin, c'est une extraction
LevelRenderer      ->  LevelRenderState, Matrix4fc
```

`Renderable.render` est devenu `extractRenderState` : le moteur ne dessine plus au moment où on le
lui demande, il **extrait un état** qu'il rendra ensuite. Ce n'est pas un renommage, c'est un autre
cycle de rendu.

Écrire un `EntityRenderer` dans ces conditions, sans documentation et sans exemple, n'est pas
impossible — c'est un chantier à part entière, qu'il faut aborder reposé et avec un banc d'images
qui fonctionne. Le bâcler donnerait un ouvrier invisible, ou un client qui plante.

## Les deux voies, et ce qu'elles coûtent vraiment

### Voie A — l'ouvrier qui marche

Une entité, avec tout ce que le tableau ci-dessus implique. C'est ce qui a été demandé, et c'est la
plus belle.

**Le mur** : le rendu. Tant qu'il n'est pas franchi, il n'y a rien à montrer.

### Voie B — l'atelier qui travaille

Un **bloc** qu'on pose, qu'on équipe d'un outil, à qui on désigne une zone, et qui mine ou bâtit.
Aucune entité, aucun rendu, aucune navigation.

**Ce qu'on garde** de la demande : l'assignation d'une zone, l'équipement qui décide du rendement,
l'usure de l'outil, le dépôt dans un coffre voisin, la lecture de schématiques.

**Ce qu'on perd** : l'ouvrier ne marche pas. On ne le voit pas aller et venir.

**Ce qu'on gagne, et qui n'est pas rien pour ce mod-ci** : un bloc-entité se mesure au banc. Le
module `sommeil` s'y applique — un atelier qui mine sait quand son prochain bloc tombe, exactement
comme un four sait quand sa fournée finit. Une ferme d'ateliers serait *mesurable*, là où une foule
d'ouvriers-entités ajouterait de la charge que ce mod passe son temps à retirer.

## Ce que je recommande

**La voie B d'abord**, parce qu'elle est livrable, testable et mesurable, et qu'elle répond à
l'essentiel : du travail automatisé qu'on configure et qu'on équipe.

**La voie A ensuite**, une fois le rendu d'entité franchi sur un cas simple — et le bon cas simple
n'est pas un ouvrier : c'est quelque chose de jetable, dont l'échec ne coûte rien.

## Ce qu'il ne faut pas faire

Commencer par l'entité en fin de session, sans banc d'images, sur une API de rendu qu'on découvre.
C'est la recette d'un module invisible qu'on croira fini.
