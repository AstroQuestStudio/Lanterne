# Notes d'intégration — la Boutique et l'Économie

Ce fichier dit **ce qu'il reste à brancher**, avec le code exact et l'endroit exact. Rien n'a été
modifié en dehors de deux paquets neufs :

```
src/main/java/fr/clubcitrouille/lanterne/content/shop/    14 fichiers   côté serveur + réseau
src/main/java/fr/clubcitrouille/lanterne/client/shop/      3 fichiers   côté client
```

> **Mise à jour — le catalogue est désormais engendré.** Le premier jet contenait 121 articles écrits
> à la main. Il en contient maintenant **1 352**, c'est-à-dire tout le vanilla tarifable, et ils ne
> sont plus écrits : ils sont **déduits des recettes du jeu**. Seules 421 matières premières ont
> encore un prix posé à la main. Voir les sections 4 et 6 — la méthode a révélé un défaut sérieux
> dans l'équilibrage précédent, et la section 6 le raconte.

**Aucun fichier existant n'a été touché.** Ni `Lanterne.java`, ni `Settings.java`, ni `Config.java`,
ni `lanterne.mixins.json`, ni le `README`, ni les fichiers de langue, ni aucune ressource. Le code
compile (`./gradlew compileJava`), mais **rien ne s'exécute** tant que la section 1 n'est pas faite.

---

## 1. Les six lignes à ajouter dans `Lanterne.java`

### a) Dans le constructeur

À placer **après** le bloc de l'atelier (`Studio` / `Easel` / `Groove`), avant
`Settings.configureFromEnvironment()` :

```java
// La boutique et l'économie. Le fichier de réglages est de type SERVER : les règles du
// marché engagent le serveur seul, et un client ne doit jamais pouvoir s'inventer une
// amplitude de dérive. Le nom de fichier est explicite parce que Config.SPEC occupe déjà
// le fichier SERVER par défaut.
container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
        fr.clubcitrouille.lanterne.content.shop.Tariff.SPEC, "lanterne-boutique.toml");
modBus.addListener(fr.clubcitrouille.lanterne.content.shop.Tariff::apply);
fr.clubcitrouille.lanterne.content.shop.Bazaar.register(modBus);
NeoForge.EVENT_BUS.addListener(
        fr.clubcitrouille.lanterne.content.shop.Bazaar::onRegisterCommands);
```

### b) Dans la garde `FMLEnvironment.getDist().isClient()`

Une ligne, **après** `Compass.register(modBus);` — l'ordre compte, voir la note en bas de cette
section :

```java
fr.clubcitrouille.lanterne.client.shop.Bell.register(modBus);
```

**Pourquoi après `Compass`.** La touche d'ouverture réutilise la catégorie de raccourcis que
`Compass` déclare (`key.categories.lanterne`, déjà traduite). NeoForge lève une exception si l'on
enregistre deux fois le même identifiant de catégorie ; en créer une seconde aurait coupé en deux la
liste des touches du mod dans les options. Si tu supprimes un jour `Compass`, il faudra que `Bell`
déclare sa propre catégorie — trois lignes, indiquées dans la javadoc de `Bell`.

`Bazaar.register` n'a **aucune** contrainte d'ordre : il ne pose pas d'alias de registre, seulement
des canaux réseau et des écouteurs d'évènements.

---

## 2. Les deux lignes de langue (facultatives, mais visibles)

Sans elles, l'écran des commandes affiche la clé brute `key.lanterne.boutique` au lieu d'un nom.
Rien d'autre n'en dépend : **tout le texte de la boutique est écrit en dur, en français**, comme dans
`Dials` et `Board`.

`src/main/resources/assets/lanterne/lang/fr_fr.json` :

```json
  "key.lanterne.boutique": "Boutique",
```

`src/main/resources/assets/lanterne/lang/en_us.json` :

```json
  "key.lanterne.boutique": "Shop",
```

---

## 3. Ce que ça donne une fois branché

### Les fichiers créés au premier démarrage

| Fichier | Contenu |
|---|---|
| `config/lanterne-boutique.toml` | les **règles** : solde de départ, dérive, marge minimale, lot maximal, facteur de génération |
| `config/lanterne/boutique.txt` | les **prix** : **1 352 articles en 17 rayons**, une ligne par article |

Le catalogue est **engendré** au premier démarrage, à partir des recettes du jeu, puis **jamais
réécrit tout seul** — ni au démarrage suivant, ni après une mise à jour. Il se recharge à chaud par
`/boutique recharger`, les modifications faites en jeu s'y écrivent par `/boutique ecrire`, et
`/boutique generer` le refait de zéro en annonçant qu'il écrase.

### Les commandes

`/banque`

| Commande | Qui | Ce qu'elle fait |
|---|---|---|
| `/banque` | tous | ton solde |
| `/banque payer <joueur> <montant>` | tous | virement ; désactivable par `economie.virements` |
| `/banque classement` | tous | les plus fortunés |
| `/banque voir <joueur>` | GM | le solde d'un autre |
| `/banque donner <joueur> <montant>` | GM | crédite |
| `/banque retirer <joueur> <montant>` | GM | débite, plancher à zéro |
| `/banque fixer <joueur> <montant>` | GM | fixe |
| `/banque masse` | GM | masse monétaire du serveur, nombre de comptes, articles hors de leur ancre |

`/boutique`

| Commande | Qui | Ce qu'elle fait |
|---|---|---|
| `/boutique` | tous | **ouvre l'écran**, avec des prix frais par construction |
| `/boutique cours [objet]` | tous | le cours du moment, l'ancre, le volume net |
| `/boutique poser <objet> <achat> <rachat> [rayon]` | GM | inscrit ou corrige un article |
| `/boutique retirer <objet>` | GM | le retire du catalogue |
| `/boutique recharger` | GM | relit `boutique.txt` à chaud |
| `/boutique ecrire` | GM | écrit le catalogue courant dans `boutique.txt` |
| `/boutique generer` | GM | **refait tout depuis les recettes et écrase le fichier** |
| `/boutique verifier` | GM | cherche les boucles d'arbitrage sur toutes les recettes, et les nomme |
| `/boutique remettre [objet]` | GM | remet un article — ou tout le marché — à son prix d'ancrage |

**Les montants sont des chaînes, pas des décimaux Brigadier.** `12.50` s'écrit sans guillemets ;
`12,50` s'écrit entre guillemets (Brigadier refuse la virgule dans un mot nu). La raison est dans
`Coin` : un `double` ne représente pas 0,10, et l'analyseur de montants n'emploie que des entiers.

### La touche

`K` par défaut, libre en vanilla et sans conflit avec `B` / `J` / `N` de JourneyMap. Elle
**n'ouvre pas** l'écran : elle envoie une demande, et le serveur répond avec le catalogue coté et le
drapeau d'ouverture. C'est ce qui garantit que les prix affichés sont ceux de l'instant.

---

## 4. Les décisions d'équilibrage, et pourquoi

### L'argent est un nombre, jamais un objet

Le solde vit dans une `SavedData` attachée à l'Overworld (`Ledger`), écrite avec la sauvegarde du
monde. Il n'y a **aucun objet-pièce**, aucune case de monnaie, rien dans aucun inventaire. Trois
raisons, développées dans la javadoc de `Ledger` : une pièce-objet se **perd** à la mort, se
**duplique** dès qu'une faille de duplication existe quelque part dans le jeu ou dans un autre mod,
et ne se **compte** pas — personne ne sait combien il y a d'argent sur un serveur dont la monnaie
dort dans des coffres.

Tout est en **centimes entiers** (`long`). Cent centimes font une pièce. Aucun flottant ne touche
jamais à un solde.

### L'invariant de la caisse : jamais d'argent sans objet, jamais d'objet sans argent

- **À la vente**, le crédit est calculé sur ce que `drain` a **réellement retiré**, jamais sur ce qui
  a été demandé. Il n'existe pas de chemin qui crédite avant de retirer.
- **À l'achat**, la place est vérifiée avant le débit ; et si l'insertion laissait malgré tout un
  reste, la différence est **recréditée au centime**. Les deux protections font double emploi, et
  c'est voulu.
- Une transaction entière s'exécute d'un seul tenant sur le **fil du serveur**, sans point de
  suspension : aucun verrou n'est nécessaire, et c'est structurel, pas une synchronisation qu'on
  pourrait oublier.

### La règle qui n'a pas d'exception

Le prix de rachat doit rester **sous** le prix d'achat, d'au moins `marge_min_pourcent` (10 % par
défaut). Un article qui l'enfreint est refusé à l'enregistrement — par commande **et** par fichier,
puisque les deux passent par `Stall.put`. Sinon : acheter, revendre, recommencer, aussi vite qu'on
clique, et plus vite encore avec un automate.

### Ce que la caisse refuse de racheter

Tout objet **porteur de composants** ou **abîmé** (`isComponentsPatchEmpty()`). Un catalogue donne un
prix à `minecraft:diamond_pickaxe` ; il ne dit rien d'une pioche Fortune III renommée à trois points
de durabilité. Le test couvre d'un coup les enchantements, les noms, l'usure, les potions, les livres
écrits, le contenu des sacs — et tout ce qu'un autre mod ajoutera après nous.

### La dérive des prix

```
    f = 1 + A · tanh(v / E)
```

`v` = volume net (unités achetées à la boutique − unités vendues à la boutique), `A` = amplitude
(50 % par défaut), `E` = élasticité (2048 unités par défaut). Les **deux** prix de l'article sont
multipliés par le **même** `f`.

- **Pourquoi `tanh`** : bornée, sans coude. Une droite écrêtée créerait des « murs » de prix que les
  joueurs apprendraient à viser. Ici le rendement décroît progressivement.
- **Pourquoi le même facteur des deux côtés** : le rapport achat/rachat ne change jamais, donc la
  marge garantie à l'enregistrement reste garantie pour toujours. Deux facteurs indépendants peuvent
  se croiser, et le jour où le rachat dépasse l'achat, l'économie du serveur est morte avant le
  lendemain.
- **Détente** : 6 % du volume effacé toutes les 5 minutes, ce qui ramène tout volume à **zéro
  exactement** (le dernier pas retire au moins une unité). Sans elle, un article massivement acheté
  une fois resterait cher à jamais.
- **Bornage du volume** à 100 × élasticité : au-delà, `tanh` rend déjà sa limite, et laisser le
  nombre croître ne ferait qu'allonger le temps de retour vers l'ancre.

### Le catalogue n'est pas écrit : il est calculé

C'est le changement le plus important de cette version, et il est motivé par une phrase de mon
propre en-tête de fichier, qui disait à la version précédente : « CE QUE CE FICHIER NE PEUT PAS
VÉRIFIER : l'artisanat et la fonte ». Il le peut, maintenant.

**Seules les matières premières ont un prix écrit à la main** — 421 entrées dans `Seam` : la terre,
la pierre, les bûches, les minerais bruts, le blé, les butins de créature, le cuivre patiné que le
temps fabrique, et les quelques objets pris dans un cycle de recettes pur. C'est là, et nulle part
ailleurs, qu'est le jugement d'équilibrage.

**Tout le reste est déduit des recettes du serveur** — 931 articles :

```
    prix(sortie) = arrondi_sup( somme(prix des ingrédients) × F / quantité produite )
    rachat       = prix d'achat × S
```

`F` = 1,20 (facteur de valeur ajoutée), `S` = 0,25 (part rachetée). Les deux sont configurables.

| | |
|---|---|
| Articles au catalogue | **1 352** |
| dont écrits à la main (`Seam`) | **421** |
| dont déduits des recettes (`Assay`) | **931** |
| Recettes lues | 1 446 |
| Passes de relaxation | 4 |
| Objets du registre sans prix | **0** |
| Objets écartés volontairement | 130 |
| Rayons | 17 |

#### Pourquoi une relaxation et non une récursion

La méthode naturelle — descente récursive mémoïsée — oblige à détecter les cycles, à décider quoi
mettre en mémoire quand on en traverse un, et se trompe discrètement si l'on s'y prend mal. La
relaxation part des matières premières et n'attribue un prix qu'une fois **tous** les ingrédients
connus. Un objet dont le prix dépendrait de lui-même n'en obtient donc jamais : **les cycles sont
traités par construction, sans une ligne de code pour les détecter.**

Et parce que `F` est strictement supérieur à 1, un cycle ne peut jamais faire *baisser* un prix —
refaire A depuis B puis B depuis A rend A plus cher. Le minimum est donc toujours atteint par un
chemin sans cycle, et la boucle converge. Quatre passes suffisent sur vanilla.

Les objets pris dans un cycle pur sont exactement ceux que `Seam` nomme, et c'est la seule raison
pour laquelle il les nomme : la pierre et ses pavés (l'un se cuit de l'autre, l'autre se taille du
premier), le charbon et son bloc, les gabarits de forge qui se dupliquent eux-mêmes.

#### Les trois choix du moins-disant

Partout où le jeu laisse le choix, on prend **le moins cher**, parce que c'est ce qu'un joueur
ferait : un ingrédient donné par étiquette (`#minecraft:planks`) vaut le moins cher de ses membres ;
un objet fabricable de plusieurs façons vaut le moins cher de ses chemins ; un ingrédient qu'aucun
prix ne couvre rend la recette inutilisable — et non gratuite.

#### Un prix de base est un plancher, pas une consigne

Si une recette permet d'obtenir un objet moins cher que ce qui est écrit dans `Seam`, **c'est la
recette qui gagne**. Cette règle supprime toute une famille d'erreurs : un prix écrit trop haut ne
peut plus créer de boucle. Trois motifs de bannière ont été pris exactement ainsi pendant la mise au
point — écrits à 60 pièces alors qu'on les fabrique pour deux — et le passage au plancher les a
corrigés tous les trois sans qu'on ait à les nommer.

#### Les mods se tarifent tout seuls

Rien dans `Assay` ne parle de vanilla : on lit le gestionnaire de recettes du serveur, tel qu'il est
après le chargement des paquets de données. **Un mod dont les recettes redescendent à des matières
premières connues est tarifé sans que personne n'écrive une ligne** — et un mod qui fabrique ses
machines avec du fer et de la redstone en fait partie. Ce qui ne se résout pas est **nommé dans le
journal du serveur**, et repris par `/boutique generer` : c'est la liste de courses de
l'administrateur, et elle est exhaustive.

Le rangement en rayons marche aussi pour eux : il est deviné à partir du **nom** de l'objet
(`_slab`, `_stairs`, `_ore`…), pas d'une table. Un mod qui nomme ses blocs à la façon de vanilla
verra ses escaliers rangés avec les escaliers. Ce qui n'entre dans aucune règle tombe dans
« Pierres et terres », et c'est une colonne à corriger dans le fichier.

### Ce qui reste écrit à la main, et pourquoi

- **Les 421 matières premières.** L'échelle : une pièce vaut à peu près deux blocs de pierre. Le
  lingot de fer ressort à 14,40, le diamant à 240, le lingot de netherite à 3 801,60 — tous trois
  **calculés**, pas choisis.
- **130 objets bannis** : blocs techniques, œufs d'apparition, et ce qui porte des composants par
  nature (potions, livres enchantés, cartes remplies). Ils ne figurent pas au catalogue du tout.
- **64 objets achetables mais jamais rachetés** : élytres, totem, étoile du Nether, cœur de la mer,
  têtes de créature, tessons d'archéologie, disques. Tous se produisent en ferme ou se ramassent en
  quantité ; les racheter, même modestement, ferait du serveur cette seule ferme.
- **Une correction d'équilibrage trouvée par le calcul** : l'or du Nether était écrit trop bas, ce
  qui rendait le lingot d'or **moins cher que celui de fer**. Invisible sur une table écrite à la
  main ; évident dès qu'on regarde le résultat.

---

## 5. Les dix-sept fichiers

### `content/shop/` — serveur, réseau, données

| Fichier | Rôle |
|---|---|
| `Coin` | la monnaie : centimes entiers, formatage, analyse d'un montant écrit à la main |
| `Seam` | **la veine** : les 421 matières premières, les bannis, les non-rachetés, les rayons |
| `Assay` | **le titrage** : la propagation des prix par les recettes, et l'audit d'arbitrage |
| `Offer` | un article du catalogue : l'objet, les deux **ancres**, le rayon |
| `Quote` | un article **coté** : les prix du moment, tels que le client les reçoit |
| `Ledger` | la `SavedData` : les soldes **et** les volumes échangés, dans le même enregistrement |
| `Stall` | l'étal : le catalogue, son fichier texte, et le refus de la marge insuffisante |
| `Drift` | la dérive : la formule et ses bornes |
| `Till` | la caisse : les deux seules transactions, et l'invariant qu'elles tiennent |
| `Tariff` | les règles du marché, dans `lanterne-boutique.toml` |
| `Bazaar` | le liant : canaux réseau, évènements, les deux arbres de commandes |
| `StallSync` | serveur → client : le catalogue coté, le solde, le symbole, le lot maximal |
| `TradeAsk` | client → serveur : ouvrir, acheter, vendre |
| `TradeEcho` | serveur → client : le ticket de caisse et l'article remis à jour |

### `client/shop/` — l'interface

| Fichier | Rôle |
|---|---|
| `Slate` | l'ardoise : l'écho local du catalogue et du solde. Aucune autorité |
| `Counter` | le comptoir : l'écran à deux onglets, entièrement dessiné à la main |
| `Bell` | la touche d'ouverture |

### L'écran

Trois colonnes, dans la langue visuelle exacte de `client/Dials.java` : même noir bleuté, même ambre,
barre de familles à gauche, contenu au centre, panneau de détail à droite, liseré ambre en haut,
ligne d'aide en bas. **Aucun composant de vanilla** — ni bouton, ni cadre, ni champ de saisie.

- **Deux onglets** : ACHETER et VENDRE (bascule aussi à la touche Tab). L'onglet Vendre ne montre que
  ce que la boutique reprend, et affiche `×N` sur chaque case pour ce qu'on possède déjà.
- **Recherche** au clavier, sur le nom affiché, l'identifiant ou le rayon. Échap efface d'abord la
  recherche, ferme ensuite.
- **Rayons** dans la colonne de gauche, molette pour les faire défiler s'ils débordent.
- **Pagination** : molette sur la grille, ou les chevrons.
- **Quantités** ×1 / ×8 / ×64 / **tout**. « Tout » est recalculé à chaque image : à l'achat, ce que le
  solde **et** la place permettent ; à la vente, ce qu'on possède de propre.
- **Total avant validation**, en ambre s'il passe, en rouge sinon, avec la raison écrite juste
  au-dessus et le chiffre exact — « il te manque 42,50 ¤ », « 12 unités ne rentrent pas ».
- **Cote** : flèche ▲/▼ et pourcentage d'écart à l'ancre, sur chaque case et en détail.

**Tout est adaptatif.** Aucune dimension n'est en dur : le panneau se dérive de `this.width` et
`this.height` entre deux bornes, la colonne de détail maigrit avant que la grille ne perde une
colonne, et le nombre de colonnes et de lignes est recalculé à chaque disposition. `init()` étant
rappelé à chaque redimensionnement, la mise en page suit pendant qu'on tire le coin de la fenêtre.
Testé de tête aux extrêmes : à 340 px de large, une colonne de cases et un détail de 96 px ; à 760,
quatre colonnes.

### Pourquoi aucun `AbstractContainerMenu`

Une boutique ressemble à un conteneur, et c'est un piège. Un conteneur synchronise des *piles
d'objets* avec un protocole de verrouillage conçu pour des coffres ; nos cases sont des *lignes de
catalogue* qui ne changent jamais de main. On paierait tout le coût du mécanisme sans employer sa
propriété utile. Deux paquets suffisent, et l'inventaire du joueur reste le seul conteneur du jeu —
donc **aucun `MenuType` à enregistrer**, ce qui est aussi une ligne de moins dans `Lanterne.java`.

---

## 6. Ce qui a été vérifié, et comment

Les dix-sept fichiers **compilent sans une seule erreur ni un seul avertissement**, avec `-Xlint:all`
et le classpath exact de Gradle. `./gradlew compileJava` complet passe également.

### a) L'arithmétique de la monnaie

25 cas sur `Coin.parse`, `Coin.amount`, `Coin.plain`, plus un aller-retour exact sur 71 429 montants
de 0 à 5 000,00. Tout passe. Pour mémoire, la même somme de mille fois dix centimes donne
`99.9999999999986` en `double` et `10000` en centimes entiers.

### b) Les bornes de la dérive

Dix-sept volumes, de `Long.MIN_VALUE / 4` à `Long.MAX_VALUE / 4` : le facteur reste dans
`[0,5 ; 1,5]`, le rachat ne croise jamais l'achat, la marge minimale tient. Un article
volontairement à marge nulle (100 / 99 centimes) est passé sur 6 186 volumes sans un croisement.

*Correction apportée à la documentation* : la première rédaction affirmait que le facteur restait
*strictement* dans l'intervalle ouvert. C'est vrai en mathématiques et faux en flottant —
`Math.tanh` sature à 1,0 au-delà de `|x| ≈ 19`. La borne est donc **atteinte, jamais dépassée**, et
la javadoc le dit maintenant comme c'est.

### c) La détente et le bornage des volumes

Treize volumes de départ, tous ramenés à **zéro exactement**, en 178 pas au pire (environ quinze
heures de serveur allumé depuis le volume maximal). Le bornage à 100 × élasticité fonctionne.

### d) La disposition de l'écran

Rejouée sur **968 275 combinaisons** de largeur (320 à 3840) et de hauteur (240 à 2160), douze
contrôles à chaque fois : le panneau reste dans la fenêtre, la grille ne mord jamais sur la colonne
de détail, les cases restent lisibles, les blocs de la colonne de détail ne se chevauchent jamais.
**Zéro violation.** Aux extrêmes : une colonne de 130 points, quatre lignes, un détail de 96 et une
barre de rayons de 72.

Deux défauts trouvés et corrigés par cette vérification : sous 396 points de large la grille
débordait de 24 points sur la colonne de détail ; sous 340 points le panneau sortait de la fenêtre
des deux côtés.

### e) La génération du catalogue, sur les vraies recettes de 26.1

Les 1 516 fichiers de recettes et les 186 fichiers d'étiquettes ont été extraits du jar du jeu, et
tout l'algorithme — expansion des étiquettes, relaxation, choix du moins-disant, audit — rejoué
hors du jeu sur ces données réelles.

| | |
|---|---|
| Recettes lues | 1 466, dont 1 446 tarifables |
| Types traités | établi (formé et informe), fonte, cuisson, fumée, feu de camp, tailleur de pierre, forgeron, transmutation |
| Types écartés | les 20 recettes spéciales (feux d'artifice, copie de livre, réparation…) — leur résultat porte des composants |
| Passes de relaxation | 4 |
| Articles obtenus | **1 352** |
| Objets du registre sans prix | **0** |

**Et la table `Seam` du code Java a été comparée ligne à ligne au modèle vérifié** : un petit
programme charge la vraie classe `Seam`, lui demande les 1 482 objets du registre, et confronte le
résultat au modèle. **421 prix de base identiques, 64 non-rachetés identiques**, et un seul écart sur
les bannis — `white_banner`, que le modèle bannissait à tort. C'est le Java qui avait raison.

### f) L'audit d'arbitrage — et le défaut sérieux qu'il a trouvé

C'est le résultat le plus important de cette version.

**Sur les prix d'ancrage**, avec un rachat à 40 % : **zéro boucle** sur les 1 446 recettes. Tout
allait bien.

**Sauf que les prix d'ancrage ne sont pas les prix pratiqués.** La dérive fait bouger chaque article
indépendamment, et deux articles peuvent aller en sens contraire : il suffit que les ingrédients
soient au plancher (− 50 %) pendant que le produit est au plafond (+ 50 %) pour qu'une boucle
s'ouvre. Le rapport à tenir n'est pas `S × F` mais :

```
    S × F × (1 + A) / (1 − A)  <  1
```

Le test a donc été refait **aux extrêmes de la dérive**, avec l'arithmétique entière exacte du code
Java :

| Rachat | Boucles ALLER | Boucles RETOUR | Pire rapport |
|---|---|---|---|
| 40 % | **1 290** | 48 | 1,481 |
| 35 % | 1 259 | 26 | 1,280 |
| 30 % | 1 065 | 22 | 1,120 |
| 28 % | 831 | 19 | 1,037 |
| 27 % | 0 | 0 | 0,992 |
| **25 %** | **0** | **0** | **0,944** |
| 20 % | 0 | 0 | 0,737 |

**À 40 % de rachat, 1 290 des 1 446 recettes du jeu étaient exploitables** dès que la dérive écartait
deux articles. Rien ne l'aurait montré sans ce test : l'audit sur les ancres était parfaitement
vert.

Le seuil est entre 27 et 28 %. Le défaut est **25 %**, qui laisse un peu moins de six pour cent de
marge : l'exploitant y perd à chaque tour. `Tariff` vérifie l'inégalité au chargement et
**l'écrit dans le journal** si elle est enfreinte ; `/boutique verifier` rejoue l'audit complet en
jeu, sur le catalogue réel, aux deux extrêmes.

*Sur le sens RETOUR* : « acheter la sortie, revendre les entrées » suppose qu'on puisse détricoter.
On ne défait pas des planches en bûche. Le test ne s'applique donc qu'aux couples pour lesquels le
jeu possède réellement la recette inverse — 49 recettes, dont les neuf lingots contre un bloc.
Appliqué partout, il produisait 57 fausses alertes qui auraient masqué les vraies.

## 7. Ce qui n'a PAS pu être vérifié

**Rien n'a été exécuté en jeu** — consigne explicite de ne lancer ni `runClient` ni `runServer`, le
dossier `run/` étant occupé par des mesures. Restent donc à voir en conditions réelles :

1. **le rendu de l'écran** : la géométrie est vérifiée, le *dessin* ne l'est pas. Restent à voir les
   couleurs à l'œil et la lisibilité réelle aux petites échelles d'interface. Le seul appel dont je
   me méfiais — l'objet dessiné à deux fois sa taille dans la colonne de détail, par
   `pose().pushMatrix()` / `translate` / `scale(2, 2)` autour de `graphics.item` — s'est avéré être
   exactement le motif employé par `OverlayRecipeComponent` de vanilla en 26.1, à l'échelle 0,375
   près. `GuiItemRenderState` recopie la matrice de pose au moment de l'ajout, donc la
   transformation s'applique bien ;
2. **le trajet complet d'une transaction** : la persistance du grand livre à travers un
   redémarrage, la synchronisation de l'inventaire après un achat ou une vente, l'ouverture de
   l'écran depuis la commande et depuis la touche ;
3. **la détente sur la durée** : elle est vérifiée pas à pas, mais pas sur un vrai serveur pendant
   quinze heures ;
4. **la première écriture du catalogue** : le chemin `config/lanterne/boutique.txt`, la création du
   dossier, le déplacement atomique du fichier temporaire ;
5. **la lecture du gestionnaire de recettes du serveur.** C'est la seule partie que je n'ai pas pu
   exécuter du tout : `BuiltInRegistries` et `RecipeManager` exigent un chargeur FML vivant, qu'on
   ne peut pas monter hors du jeu. L'**algorithme**, lui, a été rejoué sur les vrais fichiers de
   recettes extraits du jar (section 6e), et la **table de base** a été comparée ligne à ligne à la
   vraie classe `Seam` chargée en mémoire. Ce qui reste à vérifier en jeu est donc précisément le
   pont entre les deux : que `Recipe.display()` rende bien, sur un serveur réel, ce que les fichiers
   JSON décrivent. Si le compte annoncé au démarrage est proche de 1 352, le pont tient.

Trois points d'attention, que je signale plutôt que de les taire :

- **Les prix ne tiennent pas compte des échanges avec les villageois.** Un villageois n'est pas une
  recette : il n'apparaît nulle part dans le graphe. L'émeraude est cotée 120 à la main, ce qui est
  cohérent avec le reste, mais un serveur où les fermes à villageois tournent déplacera la question.
  La dérive corrigera d'elle-même si l'émeraude se met à affluer, mais c'est une correction lente,
  pas une garantie. **Même remarque pour le combustible de la fonte** : le charbon dépensé dans un four
  n'est pas compté dans le prix du produit. L'erreur va dans le sens sûr — le produit sort un peu
  moins cher que la réalité, donc le revendre rapporte un peu moins.
- **Le catalogue pèse maintenant environ 75 Kio sur le réseau**, envoyés à la connexion et à chaque
  ouverture de l'écran. C'est peu au regard de ce que vanilla envoie à la connexion, et le choix
  d'envoyer le catalogue **entier** reste le bon — il rend la désynchronisation silencieuse
  impossible —, mais si un modpack montait à dix mille articles il faudrait y revenir. Le codec plafonne
  la liste à 16 384 entrées — il lève une exception à **l'écriture** au-delà, pas à la lecture, ce
  qui ferait échouer la connexion de chaque joueur en silence. `Bazaar.sync` tronque et l'écrit dans
  le journal plutôt que de laisser passer l'exception.
- **`Till.room` ne regarde que les trente-six emplacements du sac**, alors que `Inventory.add` sait
  aussi remplir la main secondaire. Le compte est donc volontairement **prudent** : on peut refuser un
  achat qui serait passé, jamais l'inverse. C'est le seul sens qui compte, mais un joueur avec un sac
  plein et la main secondaire vide pourrait se voir refuser une unité.

## 8. Ce que je n'ai pas fait, et pourquoi

- **Aucun bloc ni objet « comptoir » à poser dans le monde.** L'écran s'ouvre à la touche ou à la
  commande. En faire un bloc supposerait un choix de recette et de protection de zone qui
  t'appartient ; c'est une demi-journée de plus et une décision de serveur, pas de mod.
- **Aucun marché entre joueurs** (dépôt-vente, enchères). Le sujet demandait la boutique
  administrateur qui **ancre** les prix, et c'est elle qui rend un marché entre joueurs possible
  ensuite. Le grand livre et la caisse sont écrits pour l'accueillir sans être retouchés.
- **Aucun journal des transactions sur disque.** `/banque masse` donne la masse monétaire et le nombre
  d'articles hors de leur ancre, ce qui suffit à voir venir une inflation. Un journal complet serait
  un fichier qui grossit sans fin, et il faudrait décider de sa rotation.
- **Aucune migration depuis un autre mod d'économie.** Aucun n'a de format de sauvegarde stable en
  26.1 à ce jour.

---
---

# Deuxième passe — les puits, et l'inflation par accumulation

> Cette section s'ajoute à ce qui précède ; **rien de ce qui est écrit plus haut n'est devenu faux**.
> L'audit d'arbitrage, la dérive, l'invariant de la caisse, le catalogue engendré : tout tient, et les
> ajouts de cette passe ne peuvent structurellement pas les casser — voir « Ce que les puits ne
> peuvent pas casser », plus bas.
>
> **Aucun nouveau point d'accroche n'est nécessaire.** Les six lignes de `Lanterne.java` de la
> section 1 suffisent toujours. Rien n'a été écrit hors de `content/shop/`, `client/shop/`,
> `NOTES-SHOP.md` et `tools/`.

## 9. Le vrai problème n'était pas l'arbitrage

L'audit de `Assay` démontre qu'on ne peut pas **fabriquer** de l'argent par une boucle d'artisanat.
C'est nécessaire, c'est fait, et cela ne dit **rien** du défaut qui tue réellement les économies de
serveur, parce que ce défaut n'est pas une triche : **l'accumulation**.

Une ferme à fer automatique ne viole aucune inégalité. Elle verse, heure après heure, une somme qu'un
joueur ordinaire ne peut pas approcher. Et la dérive, qui devait amortir le choc, est **bornée par
construction** : `tanh` sature. Passé le plancher de −50 %, le débit d'une ferme n'est plus amorti du
tout. La dérive protège les **prix relatifs** ; elle ne protège pas de l'accumulation, et il ne faut
pas lui demander ce qu'elle ne peut pas donner.

Restait à le mesurer plutôt qu'à l'affirmer.

## 10. La simulation — `tools/Bourse.java`

Un programme autonome, sans dépendance, hors du jeu, sur le modèle des vérifications de la
section 6 :

```
cd tools
javac -encoding UTF-8 Bourse.java
java Bourse             # AVANT / APRÈS sur 37 jours, et la comparaison
java Bourse --long      # le même sur 90 jours
java Bourse --balayage  # le balayage des réglages, 120 lignes
```

**Reproduit exactement** : l'arithmétique entière en centimes de `Coin`, la formule de dérive de
`Drift` avec ses bornes, la détente et le bornage des volumes de `Ledger`, la retenue de `Toll`, et
l'ordre des opérations de `Till` — le prix est arrêté avant la transaction et vaut pour tout le lot.

**Approché** : les joueurs. Vingt comptes, quatre profils, un pas de cinq minutes (la période de
détente réelle), trente-sept jours. Les débits sont ceux de fermes réelles et documentées, pris dans
le **haut** de la fourchette : un réglage qui tient contre un joueur plus productif que le pire
joueur réel tiendra contre le vrai.

| Profil | Combien | Jeu | Ce qu'il fait |
|---|---|---|---|
| occasionnel | 8 | 1 h/jour | un peu de tout, achète des blocs de décor |
| mineur | 6 | 3 h/jour | du minerai, en quantité honnête |
| **exploitant de fermes** | 3 | 4 h/jour | fer, or, mobs, canne, bambou, pastèque — dix articles |
| bâtisseur | 3 | 3 h/jour | achète massivement, revend ses gravats |

Un **vingt-et-unième** joueur se connecte au trentième jour, au solde de départ, compteurs vierges.
C'est le test le plus parlant d'une économie multijoueur, et le plus souvent oublié — on règle
toujours son économie en regardant ceux qui sont déjà là.

### Les chiffres, avant

Avec la dérive pour seul amortisseur, c'est-à-dire le mod tel qu'il était :

| | à 37 jours | à 90 jours |
|---|---|---|
| masse monétaire | **3 790 970 ¤** | 9 220 432 ¤ |
| le plus riche | **1 225 989 ¤** | 2 976 575 ¤ |
| le médian | 766 ¤ | 1 661 ¤ |
| écart riche / médian | **× 1 599** | × 1 792 |
| Gini | 0,799 | 0,799 |
| l'exploitant, par heure de jeu | **× 59,8** l'occasionnel | × 60,0 |

Le serveur n'a plus d'économie : il a un homme riche et vingt figurants. Un diamant coûte 240 ¤ ; le
plus riche peut en acheter cinq mille.

### Les chiffres, après

| | à 37 jours | à 90 jours |
|---|---|---|
| masse monétaire | **1 027 091 ¤** (27 %) | 2 400 034 ¤ (26 %) |
| le plus riche | **150 494 ¤** (12 %) | 333 900 ¤ (11 %) |
| le médian | 766 ¤ — **inchangé** | 1 661 ¤ — **inchangé** |
| écart riche / médian | **× 196** | × 201 |
| Gini | 0,620 | 0,610 |
| l'exploitant, par heure de jeu | **× 9,0** l'occasionnel | × 8,3 |

Et le détail qui compte le plus — **qui paie** :

| Profil | recette horaire avant | après | retenu sur le mois |
|---|---|---|---|
| occasionnel | 122 ¤/h | **122 ¤/h** | **0 ¤** |
| mineur | 935 ¤/h | **935 ¤/h** | **0 ¤** |
| bâtisseur | 171 ¤/h | **171 ¤/h** | **0 ¤** |
| exploitant de fermes | 7 327 ¤/h | 1 102 ¤/h | 921 292 ¤ |

**Trois profils sur quatre ne perdent pas un centime.** C'est le résultat qu'on cherchait, et il ne
doit rien à la chance : il vient de la franchise, décrite plus bas.

### Le nouvel arrivant

| | avant | après |
|---|---|---|
| une heure de récolte ordinaire, au jour 1 | 156 ¤ | 156 ¤ |
| la même, au jour 37 | 131 ¤ (84 %) | **131 ¤ (84 %)** |
| son panier d'équipement, au jour 1 | 954 ¤ | 954 ¤ |
| le même, au jour 37 | 961 ¤ (101 %) | **961 ¤ (101 %)** |
| heures de jeu pour le payer | 6,1 h → 7,3 h | 6,1 h → **7,3 h** |
| arrivé au jour 30, après une semaine | 208 ¤ | **208 ¤** |

**Les puits sont rigoureusement invisibles pour lui**, au centime près, dans les deux colonnes. Et
son pouvoir d'achat ne bouge que de vingt pour cent en un mois.

**Pourquoi si peu ?** Parce que la boutique **ancre** les prix. C'est la propriété décisive de ce
dispositif, et elle mérite d'être écrite en clair : sur un serveur où les prix sont faits par les
joueurs — hôtel des ventes, dépôt-vente —, une masse monétaire qui triple triple les prix, et le
nouveau venu est ruiné avant d'avoir commencé. Ici, un diamant coûte 240 ¤ le premier jour et,
quoi qu'il arrive, entre 120 et 360 ¤ pour toujours. **L'inflation des prix n'existe pas dans ce
modèle.** Ce qui existe, et qu'il fallait traiter, c'est la concentration des fortunes et la
trivialisation du jeu pour celui qui possède les machines.

Les vingt pour cent qu'il perd quand même viennent de la dérive : les fermiers ont fait tomber le fer
et la canne à sucre vers leur plancher, donc sa récolte se vend un peu moins. C'est voulu, c'est
borné, et cela joue aussi dans son sens — ce qui est bradé à la vente est bradé à l'achat.

## 11. Les puits retenus, et pourquoi ceux-là

`content/shop/Toll.java` — **le péage**. Chaque joueur porte deux compteurs de **recettes récentes**,
en centimes, qui montent à chaque vente et redescendent tout seuls. Tant qu'un compteur reste sous sa
**franchise**, la boutique paie le plein tarif, sans exception. Au-delà :

```
    retenue = R · tanh( max(0, compteur − franchise) / franchise )
```

### 1. Le quota — par article. **Allumé.**

C'est la réponse directe aux fermes, et c'est le puits principal. Franchise 4 000 ¤ du même article,
retenue maximale 90 % : très au-delà, la boutique paie un dixième du cours. Le message au joueur est
« va vendre autre chose », et il lui reste mille trois cents articles au plein tarif pour le faire.

### 2. Le débit — tous articles confondus. **Allumé.**

Il existe parce que **le quota seul se contourne en diversifiant**, et la mesure le dit sans appel :

| réglage | masse à 37 j | le plus riche | exploitant / occasionnel |
|---|---|---|---|
| quota seul | 1 415 358 ¤ | 307 520 ¤ | **× 16,1** |
| débit seul | 2 111 902 ¤ | 544 481 ¤ | × 28,9 |
| **les deux** | **1 027 091 ¤** | **150 494 ¤** | **× 9,0** |

Dix fermes, c'est dix fois la franchise au plein tarif. Le débit ferme cette porte. Franchise
20 000 ¤ de recettes récentes, retenue maximale 60 %. **Le mineur régulier reste sous cette
franchise et ne subit aucune retenue** — c'est ce qui a décidé la valeur.

Ils ne font pas double emploi : le premier dit « diversifie », le second dit « il y a une limite à ce
qu'un marché absorbe ».

### 3. Les frais de virement. **Éteint par défaut.**

Il ferme la seule porte de sortie des deux autres : un exploitant peut payer trois amis pour vendre à
sa place, et multiplier sa franchise par quatre. Il est éteint quand même, et c'est un choix : sur un
serveur de vingt personnes qui se connaissent, l'entraide est ce qui fait tenir le groupe, et la
taxer pour fermer une porte que presque personne n'emprunte coûte plus qu'elle ne rapporte. À allumer
le jour où le contournement se voit, pas avant.

### Ce qui a été écarté

- **Un prélèvement sur les soldes.** C'est la seule chose qui **bornerait** la masse monétaire au
  lieu d'en diviser la pente. Écarté : les joueurs détestent voir leur épargne fondre, et surtout
  **ce n'est pas nécessaire ici**, puisque la boutique ancre les prix et qu'une masse qui grossit
  n'érode le pouvoir d'achat de personne. Les puits divisent la pente par quatre ; ils ne
  l'annulent pas, et c'est assumé.
- **Une taxe progressive sur la fortune.** Se contourne en garant son argent sur un compte ami, et
  punit le vétéran plutôt que la machine. Les compteurs de recettes, eux, portent sur ce qu'on
  **vend**, pas sur ce qu'on possède : les déplacer d'un compte à l'autre ne les efface pas.
- **Des services payants** (téléportation, protection). Cohérents, mais ils demandent des décisions
  de serveur — quelles zones, quels prix, quels droits — qui ne m'appartiennent pas, et un point
  d'accroche hors de `content/shop/`.

### Les deux décisions qui ont tout changé

**La franchise.** La première rédaction s'en passait, et elle était fausse. Une `tanh` nue a une
pente de 1 à l'origine : elle mord dès la première unité vendue. Le balayage l'a montré — le nouveau
venu, qui vend six cents blocs de pierre dans sa semaine, perdait **un cinquième** de sa recette
(161 ¤ au lieu de 208 ¤), alors que le dispositif tout entier n'existe que pour toucher les fermes.
Avec la franchise, il touche 208 ¤, exactement comme si les puits étaient éteints.

**La franchise est une valeur, pas un nombre d'objets.** « Cinq cents lingots par jour » se lit
mieux, et c'est faux dès qu'on regarde le catalogue : il contient le diamant à 240 ¤ et le pavé à
0,12 ¤. La première version, en unités, laissait passer une ferme à diamants et **étranglait le
bâtisseur qui revend ses gravats** — il tombait à × 0,2 de sa recette. En centimes, un seul nombre
vaut pour les mille trois cents articles, et le bâtisseur est à × 1,0.

**Le compteur suit le brut, jamais le net.** Sans cela : plus la retenue est forte, moins le compteur
monte, donc moins la retenue est forte — une boucle de retour qui ramollit précisément là où elle
devrait serrer.

### Le troisième réglage, celui qu'on ne voit pas

`puits.detente_pour_mille` fixe la **période** sur laquelle une franchise compte, et c'est le plus
décisif des trois :

- **20 ‰** → demi-vie de 3 heures. Le compteur est remis à neuf entre deux soirées : la franchise
  devient « par session », et le dispositif est **invisible pour qui joue tous les jours**,
  c'est-à-dire pour celui qu'il vise.
- **2 ‰** → demi-vie de 29 heures. La franchise devient « par jour » et s'accumule sur celui qui vend
  tous les jours. C'est le défaut.

À zéro, les compteurs ne redescendent jamais et la première ferme condamne son propriétaire à vie ;
`Tariff.warnIfLeaky()` l'écrit dans le journal.

### Ce que les puits ne peuvent pas casser

La retenue est un facteur entre 0 et 1 appliqué au **seul prix de rachat**. Elle ne peut donc que le
faire **baisser**. L'inégalité de sûreté — `rachat × facteur × (1+A)/(1−A) < 1`, mesurée à 0,944 sur
les 1 446 recettes — reste vraie *a fortiori*. **Il n'y a rien à revérifier**, et c'est structurel,
pas un test qu'il faudrait refaire. Aucun des chiffres de la section 6 n'a bougé.

## 12. Les nouveautés visibles

### Réglages — `config/lanterne-boutique.toml`, section `[puits]`

| Clé | Défaut | |
|---|---|---|
| `quota_actif` | `true` | le quota par article |
| `quota_franchise` | `4000` | en **pièces**, par joueur et par article |
| `quota_retenue_max` | `90` | en pourcent |
| `debit_actif` | `true` | le débit, tous articles confondus |
| `debit_franchise` | `20000` | en **pièces**, par joueur |
| `debit_retenue_max` | `60` | en pourcent |
| `detente_pour_mille` | `2` | voir ci-dessus — le réglage décisif |
| `virement_frais_pourcent` | `0` | **éteint** |

Tout éteindre rend exactement le comportement de la première passe : `quota_actif = false` et
`debit_actif = false`, et rien d'autre ne change.

### Commandes

| Commande | Qui | Ce qu'elle fait |
|---|---|---|
| `/boutique quota` | tous | où j'en suis de mes franchises, et pourquoi mon fer se vend moins cher |
| `/boutique quota <joueur>` | GM | la même chose pour un autre |
| `/boutique quota remettre <joueur>` | GM | remet ses compteurs à neuf |

`/banque masse` dit en plus **ce que les puits n'ont jamais versé** depuis le premier jour. Les deux
nombres se lisent ensemble : une retenue restée à zéro après un mois signifie qu'il n'y a pas de
fermes — ou que les franchises sont trop larges.

### Sauvegarde

Les compteurs vivent dans le **même enregistrement** que les soldes et les volumes (`Ledger`). Même
raisonnement que pour les volumes, poussé d'un cran : si les soldes survivaient à une panne mais pas
les compteurs, il suffirait de demander un redémarrage pour annuler le dispositif entier. Les champs
sont `optionalFieldOf` : une sauvegarde de la première passe se charge sans rien perdre.

## 13. L'interface — ce qui a été corrigé

L'écran a été relu ligne à ligne contre `client/Dials.java`. Neuf défauts, dont six trouvés par une
vérification neuve.

### Les six débordements aux petites résolutions

La vérification de la première passe rejouait 968 275 combinaisons, mais elle contrôlait la
**position des blocs** — jamais la **largeur du texte**. Elle ne pouvait donc pas voir qu'une phrase
sort de sa colonne. `tools/mesure_police.py` lit `ascii.png` dans le jar du jeu et rend la largeur
exacte de chaque chaîne ; `tools/vitrine.py` rejoue `layout()` sur 42 252 combinaisons de 320×240 à
3840×2160 et contrôle **chaque chaîne écrite**. Ce qu'elle a trouvé, à 340 points de large où la
colonne de détail n'offre que 74 points :

| Ce qui débordait | Réclamait | Corrigé par |
|---|---|---|
| le bouton **« tout »** | 20 pts dans une boîte de 17 | gouttière rendue, puis « max » (18 pts) |
| « ▲ +30,5 % **sur l'ancre** » | 107 pts — 21 hors du panneau | « ▲ +30,5 % » |
| « **de la place pour** 1234 » | 112 pts — 26 hors du panneau | « place : 1234 » |
| « Rachat **à l'unité** » | 77 pts | « Rachat » |
| **« Total »** et le montant | 85 pts à eux deux, superposés | l'étiquette cède, le nombre reste |
| « ACHETER 2304 » | tronqué en « ACHETER 230 » | « ACHETER » seul, la quantité est au-dessus |

Deux lignes secondaires — le cours du marché et « repris X » — ne sont **plus écrites du tout** sous
116 points : couper un montant est pire que ne pas l'écrire, parce que « 1 234,5 » se lit comme un
nombre et c'en est un autre. Le résultat est **zéro violation** sur les 42 252 combinaisons.

### Les cinq autres

1. **Aucun retour visuel quand une transaction passe.** Deux achats identiques écrivaient deux fois
   le même message, au même endroit, de la même couleur : le second était rigoureusement invisible,
   et le joueur recliquait en croyant que rien n'était parti. Le solde et le liseré du bouton virent
   au vert (ou au rouge) pendant 600 ms. Aucune vérification de géométrie ne pouvait trouver
   celui-là — il ne se voit qu'en s'en servant.
2. **La flèche de cote était peinte à l'envers dans l'onglet Vendre.** Toute hausse en rouge, toute
   baisse en vert : juste quand on achète, exactement faux quand on vend. L'écran disait
   « attention » au moment précis où il aurait dû dire « c'est le moment ».
3. **« Choisis une quantité » quand on venait de cliquer sur « tout ».** Un « tout » qui vaut zéro
   n'est pas un oubli, c'est un refus déguisé. L'écran dit maintenant lequel : « il te manque
   12,50 ¤ pour en acheter un seul », « ton sac est plein », « tu n'as rien à vendre ».
4. **La molette au-dessus de la colonne de droite** faisait tourner les pages de la grille d'à côté.
   Elle règle maintenant la quantité — le geste attendu au-dessus d'un réglage est de le régler.
   Combiné avec Entrée, une pile s'achète en **un clic et deux gestes**, contre trois clics avant.
5. **Le nombre qu'on possède déjà** (`×N`) n'était montré que dans l'onglet Vendre. Savoir qu'on a
   déjà trois cents pavés est au moins aussi utile au moment d'en acheter.

Et, en dessous : **le dessin et le clic calculaient les mêmes rectangles chacun de son côté**, à
partir de constantes recopiées. La rangée des quantités était écrite `actionTop - 16 - 22` d'un côté
et `actionTop - 38` de l'autre — vrai aujourd'hui, faux au premier changement. Neuf méthodes de
géométrie partagée suppriment la classe entière de bogues : une zone cliquable qui ne recouvre pas ce
qu'on voit ne se voit pas — le bouton est là, il ne répond simplement pas.

### Ce que les puits ajoutent à l'écran, sans l'encombrer

Un joueur qui n'a jamais dépassé une franchise **ne verra jamais rien de tout cela**.

- Le prix de rachat affiché est **celui qu'il touchera**, retenues comprises. Pas de surprise à la
  validation.
- Quand une franchise est entamée, ce prix passe en **orangé** — ni le vert d'un bon prix, ni le
  rouge d'un refus. Une couleur suffit dans une case de 26 points de haut.
- La colonne de détail ajoute alors **deux lignes courtes** : le cours du marché, et « ta part :
  34 % ». La zone de message, qui est libre puisque rien ne bloque, explique en une phrase.
- `/boutique quota` donne le détail complet, chiffres à l'appui.

## 14. Ce qui n'a pas pu être vérifié

Les cinq points de la section 7 restent vrais — **rien n'a été exécuté en jeu**. S'y ajoutent :

1. **Le rendu de l'orangé et du clignotement.** La géométrie et la largeur du texte sont vérifiées ;
   les couleurs à l'œil ne le sont pas. Les 600 ms du clignotement sont un jugement, pas une mesure.
2. **La persistance des nouveaux compteurs** à travers un redémarrage, et la relecture d'une
   sauvegarde écrite par la première passe. Les champs sont optionnels et la logique est celle des
   volumes, qui marche ; le chemin exact n'a pas tourné.
3. **Le coût réel de `Bazaar.sync`** avec des compteurs non vides. Le chemin rapide sort immédiatement
   quand le joueur n'a rien vendu — le cas courant — mais un exploitant de fermes fait mille trois
   cents appels à `Toll.keep` par ouverture d'écran. C'est de l'ordre du dixième de milliseconde en
   raisonnement ; ce n'est pas une mesure.
4. **Les réglages sont ceux d'un modèle, pas d'un serveur.** Le modèle est volontairement pessimiste,
   mais le premier mois de vrai jeu vaudra tous les balayages. Les deux nombres à regarder sont
   `/banque masse` — la retenue doit être non nulle, sinon les franchises sont trop larges — et le
   classement, dont l'écart doit rester de l'ordre de la centaine, pas du millier.
5. **Le contournement par comptes multiples ou par amis complaisants** n'est pas modélisé. Il existe.
   `virement_frais_pourcent` est là pour le second, éteint, et le premier relève de la modération.
6. **Le `README` n'a pas été mis à jour** — il est hors du périmètre qu'on m'a donné. La section
   « boutique » y gagnerait les huit clés de `[puits]` et la commande `/boutique quota`.
