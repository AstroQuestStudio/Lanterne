# Audit du butin — Minecraft 26.3

## Périmètre et diagnostic

Base examinée : commit `9e6e85c` (26.3.152), NeoForge `26.3.0.7-beta`.
Les cinq registres de blocs (`Contents`, `Groove`, `Screens`, `Gates`, `Reseaux`)
contiennent 14 blocs : 13 blocs récupérables et une nappe de portail technique.
« Terminal » désigne ici le Guichet : aucune entrée `terminal` distincte.

Les 13 tables existaient, au bon emplacement singulier
`data/lanterne/loot_table/blocks/<id>.json`, avec le bon item enregistré.
Mais elles utilisaient toutes l'ancienne forme
`"conditions": [{"condition": "minecraft:survives_explosion"}]`.
Le JAR **26.3 effectivement présent dans le cache** fournit, pour l'obsidienne,
`"condition": {"type": "minecraft:survives_explosion"}`.
`LootPool.CODEC` confirme le champ optionnel singulier : l'ancien champ pouvait
être ignoré, donc cette migration corrige la condition d'explosion ; elle ne
suffit pas, à elle seule, à expliquer tous les échecs de minage.

Le défaut de récolte principal est indépendant : tous les blocs demandaient
un outil correct, mais seules les deux ancres figuraient dans `mineable/pickaxe`.
Avec un bloc absent de tout tag d'outil approprié, une pioche ne peut pas
satisfaire cette exigence. Les ancres étaient déjà correctement étiquetées :
leur récupération nécessite une pioche diamant ou netherite. Sans reproduction
en jeu, ne pas attribuer leur absence de butin à une cause démontrée supplémentaire.

## Inventaire exhaustif

Toutes les lignes récupérables : table migrée en 26.3, item homonyme vérifié.
Les valeurs indiquent **dureté / résistance aux explosions**, conservées.

| Bloc et ID | Outil après correction | Dureté / résistance | Contenu et particularités |
|---|---|---|---|
| Ancre `anchor` | Pioche diamant minimum, déjà correct | 50 / 1200 | Obsidienne ; pas de conteneur |
| Ancre de ferme `ancre_de_ferme` | Pioche diamant minimum, déjà correct | 50 / 1200 | Obsidienne ; pas de conteneur |
| Graveur de vinyle `graveur` | Tag pioche ajouté, bois minimum | 3,5 / 6 | Disque rendu par `preRemoveSideEffects`, aussi à l'explosion |
| Projecteur `projecteur` | Tag pioche ajouté, bois minimum | 3,5 / 6 | Référence multimédia, pas de stock d'items |
| Écran `ecran` | Tag pioche ajouté, bois minimum | 3 / 6 | Pas de stock d'items |
| Veilleuse `veilleuse` | Tag pioche ajouté, bois minimum | 3 / 6 | Pas de stock d'items |
| Cœur de repère `coeur_de_repere` | Tags pioche et diamant ajoutés | 50 / 1200 | Niveau cohérent avec le cœur en obsidienne |
| Guichet / Terminal `guichet` | Tag pioche ajouté, bois minimum | 3,5 / 6 | Accès aux coffres distants ; aucun effacement de ceux-ci |
| Recycleur `recycleur` | Tag pioche ajouté, bois minimum | 3,5 / 6 | Traitement immédiat, pas d'inventaire persistant |
| Trieur `trieur` | Tag pioche ajouté, bois minimum | 3 / 4,8 | Stock vanilla + restitution des vrais gabarits ; nom conservé |
| Aiguillage `aiguillage` | Tag pioche ajouté, bois minimum | 3 / 4,8 | Références aux conteneurs voisins, aucun stock propre |
| Coffre de dépôt `coffre_depot` | Hache accélérée, main autorisée | 2,5 / 3 | Inventaire vanilla restitué au sol ; nom conservé |
| Coffre de réception `coffre_reception` | Hache accélérée, main autorisée | 2,5 / 3 | Inventaire vanilla restitué au sol ; nom conservé |
| Nappe `portail_repere` | Aucun, volontairement incassable | -1 / valeur dérivée | `noLootTable`, aucun BlockItem, ni recette : inchangé |

Les coffres sont en bois (`MapColor.WOOD` / `SoundType.WOOD`) : suppression de
`requiresCorrectToolForDrops`, ajout à `mineable/axe`, comme les coffres vanilla.
Aucune valeur de dureté ne nécessite de modification : machines comparables
au four/entonnoir, coffres en bois, ancres et cœur résistants comme l'obsidienne.
Les tags complètent les tags vanilla avec `replace: false`.

## Préservation du contenu

Le comportement vanilla d'un coffre cassé est de rendre **son bloc et ses objets
au sol**, et non de stocker son inventaire dans le bloc-item comme une shulker.
Les deux coffres héritent de `ChestBlockEntity`, le Trieur de `HopperBlockEntity`.
Dans cette version, `BlockEntity.preRemoveSideEffects` appelle déjà
`Containers.dropContents` pour un `Container` : ne pas doubler cette restitution,
ni ajouter de `clearContent`, ni copier `minecraft:container` dans la table.
La copie de `minecraft:custom_name` utilise le nouveau champ `modifier` de 26.3.

Le filtre du Trieur est un second `SimpleContainer`, invisible à cette restitution
automatique. Il rend maintenant ses vrais objets (y compris une vraie étiquette
renommée). Les motifs saisis dans le champ texte, marqués `Motif.estVirtuel`,
sont supprimés sans devenir des items gratuits. Les cinq cases de transfert
continuent de passer une seule fois par la méthode vanilla héritée.

Le Graveur utilisait uniquement `playerWillDestroy`. Sa restitution migre vers
le crochet du bloc-entité, avant sa suppression : elle couvre aussi les retraits
sans joueur. `spill` vide la référence après restitution, évitant un double gain.
Les règles vanilla des explosions restent applicables aux objets au sol.

## Vérifications et reproduction

`python tools/verifie_butin.py` contrôle les 14 enregistrements, les 13 tables,
les items et la couverture des outils, ainsi que l'absence d'une double copie
d'inventaire. `git diff --check` contrôle le patch. Ce sont des vérifications
statiques ; elles ne constituent pas un essai de survie.

Commande de compilation requise : `./gradlew build -x test --offline -q`.
L'état effectif du build et les limites d'environnement figurent dans le rapport
de livraison ; un build seul ne charge pas les tables dans un monde.

Essai en jeu à faire avant diffusion : casser les 13 blocs en survie avec leur
outil, puis sans outil ; vérifier diamant requis pour les trois blocs résistants.
Tester les coffres avec plusieurs piles et un nom, le Graveur chargé/en cours,
le Trieur avec stock + gabarits réels + motifs virtuels. Vérifier conservation
des quantités, absence de duplication, Fortune/Silk Touch sans effet multiplicateur.

Références : données et sources Minecraft 26.3 du cache NeoForm local ;
[documentation NeoForge sur le chemin des tables](https://docs.neoforged.net/docs/resources/server/loottables/).
La documentation publique consultée est marquée 26.1 : le schéma précis 26.3
a été vérifié dans le JAR local, et non déduit de cette page.

## Résultat de compilation de cette livraison

Le 27 septembre 2026 : `./gradlew build -x test --offline -q` termine avec
**code de sortie 0** dans la copie de travail `lanterne-deco-astra`, branche
`deco-astra` créée depuis `9e6e85c`. Aucun test de jeu n'a été exécuté.
Les avertissements de dépréciation et de types non vérifiés d'Iris subsistent.

La session n'a le droit d'écrire que dans l'instance `Club Citrouille` :
`C:\lanterne-jar` est resté en lecture seule. La compilation depuis ce chemin
précis n'a donc pas été réalisée. Les modifications non commitées de l'autre
chantier n'ont pas été importées dans la copie.

Pour le build isolé : copie locale des dépendances/cache, JDK 25 extrait du ZIP
déjà présent, alias de chemin temporaire `Q:` vers l'espace autorisé pour éviter
une erreur Windows de résolution de chemin. Gradle utilise un manifeste Mojang
local via `neoForge.neoFormRuntime.launcherManifestUrl`, sans téléchargement,
avec compilation incrémentale désactivée et classpath pointant sur cet alias.
Ces réglages vivent dans le Gradle User Home local, **pas dans le projet**.
Aucune tâche de compilation ni de packaging n'a été désactivée ; seuls les tests
sont exclus conformément à la commande demandée. Aucun push ni déploiement.
