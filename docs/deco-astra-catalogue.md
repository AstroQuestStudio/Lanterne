# Astra — proposition à valider, 60 blocs

**Statut : conception uniquement.** Aucun bloc, item, modèle de jeu, recette ou
paramètre de configuration Astra n'est enregistré par ce changement. La planche
de textures est une maquette extérieure aux ressources embarquées du mod.

## Direction artistique et règles communes

Décliner des matériaux vanilla peu exploités, conserver leur palette, leur son,
leur échelle et leur lisibilité. Pixels nets à 16 × 16, relief suggéré discret,
pas de matériau magique supplémentaire, pas de machine ni de progression propre.
Les nouvelles silhouettes restent simples : escalier vanilla, dalle vanilla,
mur vanilla, pilier axial, corniche et moulure orientables. Une voûte est un
**voussoir dans une case**, orientable pour former un arc avec plusieurs blocs ;
aucune structure multibloc automatique. Les piliers ont un axe, sans bloc-entité.
L'assemblage des vitrages reprend les connexions des vitres vanilla.

Les numéros ci-dessous sont des références de catalogue, pas des identifiants
déjà enregistrés. Chaque ligne représente un bloc et un BlockItem futurs,
donc **60 blocs + 60 items**, sans compter les états (axes, orientation, eau).
Les couleurs de vitrail sont six blocs distincts ; aucune série cachée de 16
couleurs n'est comprise dans ce total. La terre cuite proposée est non teintée.

## Recettes de référence

`T1` : tailleur de pierre, 1 matériau indiqué → 1 bloc de la ligne.
`T2` : tailleur de pierre, 1 matériau indiqué → 2 demi-volumes (dalles,
corniches ou moulures). Les formes volumineuses restent à rendement 1.
Les matières premières sont toujours vanilla, jamais une variante Astra requise
pour progresser. Aucune conversion inverse vers des ressources et aucune boucle
avec le Recycleur (les blocs n'ont pas de durabilité).

Les escaliers peuvent également suivre le patron vanilla à l'établi : 6 blocs
du matériau en escalier → 4 escaliers. Les dalles : 3 blocs en ligne → 6 dalles.
Les murs : 6 blocs en deux rangées → 6 murs. Le tailleur reste préférable et
les recettes spécialisées évitent les collisions avec les recettes vanilla.

Pour les six ferronneries `F1` à `F6`, établi : `B` = barreaux de fer vanilla,
`N` = pépite de fer, `.` = case vide ; les `/` séparent les trois rangées.
Les patrons sont distincts et ne consomment ni seau ni outil.

| Code | Patron | Coût exact | Résultat |
|---|---|---|---|
| F1 | `B.B/N.N/B.B` | 4 barreaux + 2 pépites | 4 grilles croisées |
| F2 | `BNB/NBN/BNB` | 5 barreaux + 4 pépites | 6 treillis diagonaux |
| F3 | `BBB/N.N/B.B` | 5 barreaux + 2 pépites | 6 balustrades |
| F4 | `.N./.B./.B.` | 2 barreaux + 1 pépite | 2 potelets rivetés |
| F5 | `BB./.N./.N.` | 2 barreaux + 2 pépites | 2 consoles |
| F6 | `BNB/N.N/BNB` | 4 barreaux + 4 pépites | 4 anneaux muraux |

`V8` : établi, 8 vitres vanilla de la couleur indiquée autour de 1 pépite de
fer → 8 vitraux assortis. Le « plomb » désigne l'aspect des joints : aucun
minerai, lingot ou item plomb n'est proposé. Pour le vitrail clair, vitres
incolores. Ni recyclage en verre massif ni retour en fer.

## Catalogue exhaustif

### Calcite — 8 blocs

| N° | Bloc | Matière vanilla | Recette | Usage |
|---|---|---|---|---|
| 01 | Escalier de calcite | Calcite | T1 | Escaliers clairs |
| 02 | Dalle de calcite | Calcite | T2 | Seuils et plafonds |
| 03 | Mur de calcite | Calcite | T1 | Murets de jardins |
| 04 | Pilier cannelé de calcite ★ | Calcite | T1 | Colonnes sobres |
| 05 | Corniche de calcite ★ | Calcite | T2 | Couronnements |
| 06 | Moulure de calcite | Calcite | T2 | Encadrements fins |
| 07 | Voussoir de calcite | Calcite | T1 | Arcs blancs |
| 08 | Pierre de taille en calcite ★ | Calcite | T1 | Façades à grands joints |

### Basalte lisse — 8 blocs

| N° | Bloc | Matière vanilla | Recette | Usage |
|---|---|---|---|---|
| 09 | Escalier de basalte lisse | Basalte lisse | T1 | Escaliers sombres |
| 10 | Dalle de basalte lisse | Basalte lisse | T2 | Seuils |
| 11 | Mur de basalte lisse | Basalte lisse | T1 | Murets |
| 12 | Pilier cannelé de basalte lisse | Basalte lisse | T1 | Colonnes ; distinct du basalte poli vanilla |
| 13 | Corniche de basalte lisse | Basalte lisse | T2 | Liserés de façade |
| 14 | Voussoir de basalte lisse | Basalte lisse | T1 | Arcs de ponts |
| 15 | Pavage de basalte lisse ★ | Basalte lisse | T1 | Cours et rues |
| 16 | Briques de basalte lisse | Basalte lisse | T1 | Appareil sombre compact |

### Boue compactée — 6 blocs

Ces formes conservent la texture terreuse de `packed_mud`, et se distinguent
des briques de boue et de leurs escaliers/dalles/murs déjà vanilla.

| N° | Bloc | Matière vanilla | Recette | Usage |
|---|---|---|---|---|
| 17 | Escalier de boue compactée | Boue compactée | T1 | Architecture en terre |
| 18 | Dalle de boue compactée | Boue compactée | T2 | Sols rustiques |
| 19 | Mur de boue compactée | Boue compactée | T1 | Enclos |
| 20 | Pilier de boue compactée | Boue compactée | T1 | Poteaux massifs |
| 21 | Voussoir de boue compactée ★ | Boue compactée | T1 | Arcs de terre |
| 22 | Pavage de boue compactée | Boue compactée | T1 | Cours damées |

### Terre cuite naturelle — 8 blocs

| N° | Bloc | Matière vanilla | Recette | Usage |
|---|---|---|---|---|
| 23 | Escalier de terre cuite | Terre cuite non teintée | T1 | Toitures et marches |
| 24 | Dalle de terre cuite | Terre cuite non teintée | T2 | Rives de toit |
| 25 | Mur de terre cuite | Terre cuite non teintée | T1 | Murets chauds |
| 26 | Pilier de terre cuite | Terre cuite non teintée | T1 | Porches |
| 27 | Corniche de terre cuite | Terre cuite non teintée | T2 | Génoises |
| 28 | Voussoir de terre cuite | Terre cuite non teintée | T1 | Arcades |
| 29 | Pavage de terre cuite | Terre cuite non teintée | T1 | Cours méditerranéennes |
| 30 | Tuiles de terre cuite ★ | Terre cuite non teintée | T1 | Bloc plein à motif de tuiles, pose avec escaliers n°23 |

### Quartz — 6 blocs

Pas de doublon des escaliers/dalles ordinaires ou lisses vanilla : les deux
premières lignes reprennent explicitement l'appareil des **briques de quartz**.

| N° | Bloc | Matière vanilla | Recette | Usage |
|---|---|---|---|---|
| 31 | Escalier en briques de quartz | Briques de quartz | T1 | Marches appareillées |
| 32 | Dalle en briques de quartz | Briques de quartz | T2 | Terrasses appareillées |
| 33 | Mur en briques de quartz | Briques de quartz | T1 | Murets blancs |
| 34 | Mur de quartz lisse | Quartz lisse | T1 | Balcons unis |
| 35 | Corniche de quartz ★ | Quartz lisse | T2 | Relief blanc discret |
| 36 | Voussoir de quartz | Quartz lisse | T1 | Voûtes classiques |

### Améthyste — 6 blocs

| N° | Bloc | Matière vanilla | Recette | Usage |
|---|---|---|---|---|
| 37 | Escalier d'améthyste | Bloc d'améthyste | T1 | Accents violets |
| 38 | Dalle d'améthyste | Bloc d'améthyste | T2 | Seuils |
| 39 | Mur d'améthyste | Bloc d'améthyste | T1 | Jardins cristallins |
| 40 | Pilier d'améthyste | Bloc d'améthyste | T1 | Colonnes compactes |
| 41 | Corniche d'améthyste | Bloc d'améthyste | T2 | Bordures |
| 42 | Pavage d'améthyste ★ | Bloc d'améthyste | T1 | Mosaïque géométrique |

Pas d'améthyste bourgeonnante dans les recettes, pas de croissance, pas de
lumière émise nouvelle ; son et propriétés à aligner sur le bloc vanilla.

### Prismarine — 6 blocs

| N° | Bloc | Matière vanilla | Recette | Usage |
|---|---|---|---|---|
| 43 | Mur de prismarine sombre | Prismarine sombre | T1 | Jardins aquatiques |
| 44 | Mur en briques de prismarine | Briques de prismarine | T1 | Quais |
| 45 | Pilier en briques de prismarine | Briques de prismarine | T1 | Colonnes marines |
| 46 | Pilier de prismarine sombre | Prismarine sombre | T1 | Colonnes foncées |
| 47 | Corniche de prismarine ★ | Prismarine | T2 | Rives bleu-vert |
| 48 | Voussoir de prismarine | Prismarine | T1 | Arcs de ponts |

### Ferronnerie — 6 blocs

| N° | Bloc | Recette | Usage |
|---|---|---|---|
| 49 | Grille de fer croisée ★ | F1 | Baies quadrillées, silhouette distincte des barreaux |
| 50 | Treillis de fer diagonal | F2 | Jardin et baies |
| 51 | Balustrade de fer | F3 | Garde-corps |
| 52 | Potelet de fer riveté | F4 | Extrémités et portillons décoratifs |
| 53 | Console de fer | F5 | Support visuel sous balcons |
| 54 | Anneau mural de fer | F6 | Détail de façade, sans fonction de laisse |

### Vitraux à joints fins — 6 blocs

| N° | Bloc | Vitres vanilla requises | Recette | Usage |
|---|---|---|---|---|
| 55 | Vitrail miel ★ | Jaunes | V8 | Losanges chauds |
| 56 | Vitrail mousse ★ | Vertes | V8 | Verrières de jardin |
| 57 | Vitrail azur | Bleues | V8 | Baies marines |
| 58 | Vitrail laiteux | Blanches | V8 | Fenêtres diffuses |
| 59 | Vitrail rubis | Rouges | V8 | Accents chauds |
| 60 | Vitrail clair | Incolores | V8 | Verrières sobres |

Total : **8 + 8 + 6 + 8 + 6 + 6 + 6 + 6 + 6 = 60**.
Les douze étoiles correspondent aux douze textures de la planche.

## Interrupteur de module — proposition

Prévoir `deco_astra_actif = true` dans la configuration serveur synchronisée,
avec application au démarrage. À `false` : retirer les recettes Astra, masquer
les entrées créatives et dans JEI, refuser la pose de nouveaux blocs Astra et
les fonctions d'acquisition propres au module. L'autorité reste côté serveur,
y compris pour un item déjà présent dans un inventaire.

**Les enregistrements restent identiques dans les deux modes.** Les constructions
existantes restent visibles, cassables et récupérables ; aucun bloc ne devient
de l'air, aucun inventaire ne perd ses items. On désactive l'accès au module,
pas la capacité à charger un monde qui l'a déjà utilisé. Les blocs gardés dans
les coffres attendent sa réactivation. Le propriétaire doit valider explicitement
cette sémantique : enlever réellement les registres exigerait une migration de
mondes et romprait l'objectif de compatibilité. Une commande administrateur reste
un outil de maintenance ; le réglage n'est pas une protection contre l'opérateur.

## Livraison unique et compatibilité Mèche

Le code existant explique le cas vécu : la Mèche historique travaille en phase
de jeu, **après** la synchronisation des registres. Un client ignorant un seul
nouveau bloc/item peut être refusé avant d'avoir l'occasion de télécharger.
Ni un pack de textures, ni le bouton on/off, ni `/reload` ne créent les classes
et registres manquants dans un processus client déjà démarré.

Le dépôt possède déjà une solution en amont : `core/MecheConfig.java`, branché
par `mixin/MecheConfigurationMixin.java`, précède `SyncRegistries`.
`MecheRegistres` compare les clés ; `MecheCompat` propose une antichambre filtrée
pour les clients historiques, avec renumérotation des registres. Ce catalogue
ne modifie aucun de ces mécanismes et ne prétend pas qu'ils sont validés pour Astra.

Plan proposé au propriétaire, **non exécuté** :

1. Valider le catalogue entier, les 60 noms et toutes les variantes d'état.
   Geler le manifeste de registres : 60 blocs et 60 items ajoutés d'un seul lot,
   plus toute autre entrée réellement nécessaire à l'implémentation retenue.
   Préférer des blocs sans bloc-entité et les mécaniques vanilla afin de limiter
   cette surface. Ne pas distribuer douze micro-versions par famille.
2. Construire et vérifier un JAR unique, versionné et muni de l'empreinte que la
   Mèche annonce. Inclure tous les assets, tags, butins, recettes et traductions.
   Comparer l'ensemble final des clés, pas seulement le numéro de version.
   **Conserver** `lanterne-meche/registres-reference.txt` comme référence des
   anciens clients ; l'écraser avec les clés Astra mentirait sur leurs capacités.
3. Client ≥ 26.3.147 avec canal de configuration : annoncer le lot avant les
   registres, télécharger et vérifier le fichier, puis demander une fermeture
   et relance complète du jeu si des clés manquent. N'autoriser l'entrée dans
   le monde qu'après une reconnexion dont les registres sont compatibles.
   Aucun remplacement à chaud de blocs/classes déjà enregistrés.
4. Client ≤ 26.3.146 : utiliser l'antichambre Mèche existante uniquement après
   un essai démontrant qu'aucun état, item, recette, paquet de chunk ou inventaire
   Astra ne lui est envoyé. Il télécharge puis relance ; il ne joue jamais dans
   le monde Astra avec de faux équivalents vanilla. Si l'antichambre n'est pas
   sûre, si Mèche est désactivée, absente, refusée ou échoue : refus explicite
   **avant** la synchronisation, avec version requise et procédure manuelle.
5. Une échéance et un échec de téléchargement doivent finir par une déconnexion
   expliquée quand il manque des clés, jamais par l'ouverture du monde. Vérifier
   cette issue dans le code actuel avant toute livraison. Un joueur n'est pas
   compatible parce qu'il a accepté la mise à jour : son processus doit l'avoir chargée.
6. Sauvegarder le monde avant livraison. Pour revenir en arrière après placement
   d'Astra, conserver un JAR connaissant les mêmes registres et désactiver le
   module, ou restaurer la sauvegarde ; revenir à un ancien JAR supprimerait des
   données. Une mise à jour ultérieure de texture/recette peut conserver les clés.

Matrice à vérifier avant publication : client à jour ; client 147+ sans Astra ;
client historique avec Mèche ; client sans voie de mise à jour ; refus utilisateur ;
téléchargement interrompu/corrompu ; module éteint avec des constructions existantes.
Vérifier inventaire porté et coffres, reconnexion, redémarrage serveur, identifiants
et états de blocs, recettes, absence de pertes et absence de gel de connexion.

## Maquettes reproductibles

`python tools/deco_astra_textures.py` (Python + Pillow) génère une seule planche
`docs/maquettes/deco_astra_planche.png`. Chaque texture est calculée à **16 × 16**,
puis agrandie par voisin le plus proche. Les motifs sont procéduraux et originaux,
sans extraction de textures Minecraft. Graine fixe ; `--seed`, `--output` et
`--font` permettent de reproduire ou varier les essais.

La planche montre des **faces de matériaux**, pas des modèles 3D validés. Pour
les piliers, corniches et voussoirs, silhouettes, UV, faces de bout, collisions et
raccords devront être prototypés après accord. Les joints transparents des vitraux
et ferronneries sont présentés sur un damier ; le mod devra gérer la transparence.
