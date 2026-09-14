# Notes d'intégration — l'Atelier (tableaux + disques)

Ce fichier décrit ce que le travail sur `content/painting/` et `content/disc/` a touché **en dehors
de ces deux paquets**, ce qu'il n'a pas touché, et ce qui reste à décider.

## 1. Ce qui a été modifié hors des deux paquets

**Aucun fichier de `core/`, `mixin/`, `lab/`, `report/` n'a été touché.** Ni `Settings.java`, ni
`lanterne.mixins.json`. Aucun point d'accroche dans le cœur n'a été nécessaire : tout passe par des
évènements et des registres publics de NeoForge.

### `Lanterne.java` — six lignes ajoutées, aucune retirée

Dans le constructeur, juste avant `Settings.configureFromEnvironment()` :

```java
container.registerConfig(ModConfig.Type.COMMON, Studio.SPEC);
modBus.addListener(Studio::apply);
Easel.register(modBus);
Groove.register(modBus);
NeoForge.EVENT_BUS.addListener(Easel::onRegisterCommands);
NeoForge.EVENT_BUS.addListener(Groove::onRegisterCommands);
```

Dans la garde `FMLEnvironment.getDist().isClient()`, une ligne :

```java
CanvasRenderer.register(modBus);
```

Rien d'autre. Si tu préfères déplacer ces appels ailleurs, la seule contrainte est que
`Easel.register` soit appelé **avant** que `RegisterEvent` ne soit émis : il pose deux alias de
registre, et NeoForge interdit d'en ajouter après coup.

### `NOTICE.md` — une entrée d'attribution

Deux lignes dans la table « ce qui est repris, et d'où », plus un paragraphe expliquant ce qui
distingue ce travail d'Immersive Paintings. Aucun code de ce mod n'a été recopié ; sa licence est
GPL-3.0, donc la reprise aurait été licite, mais son architecture est précisément ce qu'on voulait
éviter.

### `README.md` — deux sections

`## 🖼️ Les Tableaux` et `## 💿 Les Disques`, insérées entre les repères et le balai, plus une table
des trois fichiers de configuration dans la section Configuration.

### Ressources ajoutées

```
assets/lanterne/items/{pinceau,disque}.json
assets/lanterne/models/item/{pinceau,disque}.json
assets/lanterne/textures/item/{pinceau,disque}.png    (générées par tools/AtelierTex.java)
assets/lanterne/lang/{fr_fr,en_us}.json               (deux clés chacun)
docs/images/{pinceau,disque,mosaique,tableau}.png
tools/AtelierTex.java
```

Les deux textures d'objet sont **générées par programme**, comme celles du carnet et de la boussole.
Pour les refaire :

```
java tools/AtelierTex.java src/main/resources/assets/lanterne/textures/item docs/images
```

## 1 bis. Le flux d'utilisation, refait après l'essai en jeu

Le premier flux — déposer les fichiers dans un dossier avant de jouer, puis les retrouver par une
commande — a été jugé impraticable, et à juste titre : c'est un flux d'administrateur. Il est
remplacé par **poser d'abord, remplir ensuite**, et le dossier devient la troisième voie.

### Ce qui a été ajouté

| Fichier | Rôle |
|---|---|
| `content/painting/Frame.java` | l'écran d'édition : aperçu, taille en blocs, champ d'adresse, bouton « Depuis mon PC », progression, erreurs |
| `content/painting/Reach.java` | le téléchargement borné et le sélecteur de fichiers natif (`TinyFileDialogs`) |
| `content/painting/PaintingOpen.java` | S2C — « ouvre l'atelier », émis **après** contrôle des droits |
| `content/painting/PaintingSet.java` | C2S — « pose cette image, de cette taille » (aucun pixel) |
| `content/painting/PaintingWant.java` | S2C — « je ne connais pas cette image, envoie-la » |
| `content/painting/PaintingGift.java` | C2S — les tranches d'image qui montent |
| `content/disc/DiscRefresh.java` | S2C — rechargement des ressources déclenché par `/disque recharger` |

`PaintingRoll` porte désormais aussi la **loi du serveur** (taille maximale, blocs maximum, liens
autorisés, domaines), pour que l'écran ne propose jamais ce qui sera refusé.

### Le sélecteur natif

`org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(CharSequence title, CharSequence
defaultPath, PointerBuffer filterPatterns, CharSequence filterDescription, boolean multiple)` —
signature relevée dans `lwjgl-tinyfd-3.4.1.jar`, pas devinée. Il rend `null` à l'annulation.

L'appel **bloque** jusqu'au clic de l'utilisateur : il tourne sur le fil `Lanterne-courses`, jamais
sur le fil de rendu. Les motifs de filtre vivent dans un `MemoryStack` dépilé par `try`.

### Ce qui est vérifié, et par qui

| Barrière — le serveur la fait respecter | Politique — le serveur l'annonce seulement |
|---|---|
| taille en octets, taille en pixels (lue dans l'en-tête PNG avant tout décodage), quota, cadence de 3 s, portée de 64 blocs, droits d'édition, **empreinte recalculée** sur les octets reçus | la liste de domaines autorisés, appliquée par le client puisque c'est lui qui télécharge |

Cette distinction est écrite telle quelle dans le fichier de configuration et dans le Javadoc de
`PaintingRoll.Rules`. Un réglage qui protège moins qu'il n'en a l'air doit le dire.

## 1 ter. Le blocage du serveur dédié — cause et correctif

**Symptôme.** `Failed to create mod instance` à `Lanterne.java:88` (`Easel.register`),
`ClassNotFoundException: net.minecraft.client.gui.screens.Screen`.

**Cause.** `Easel.onRegisterPayloads` contenait une lambda faisant
`Minecraft.getInstance().setScreen(new Frame(...))`. Mon commentaire d'alors affirmait qu'un corps de
lambda n'est chargé qu'à son premier appel. **C'est faux.** Le vérificateur de la machine virtuelle
doit prouver que `Frame` est bien un `Screen` pour accepter l'appel à `setScreen`, et il le fait à la
*préparation* de la méthode, pas à son exécution. Une lambda n'y échappe pas : elle est compilée en
méthode synthétique de la classe qui la contient.

**Correctif.** Une interface commune par fonctionnalité, implémentée uniquement côté client :

| Interface (commune) | Implémentation (cliente) | Installée depuis |
|---|---|---|
| `Easel.Brushwork` | `painting/Hands.java` | `CanvasRenderer.register`, sous garde de distribution |
| `Groove.Turntable` | `disc/Wheel.install()` | `Lanterne.java`, sous garde de distribution |

Le serveur reçoit une implémentation muette (`DEAF`) et ne charge jamais une classe cliente.

**La règle, désormais sans exception.** Une classe chargée des deux côtés ne nomme jamais un type
client — ni dans une signature, ni dans un corps de méthode, ni dans une lambda. `@OnlyIn` ne protège
rien : NeoForge 26.1 avertit lui-même que le retrait des membres annotés n'a plus lieu à l'exécution.

**Vérification, faite sans lancer le jeu.** Deux passes, et la seconde est la seule qui prouve
quelque chose :

1. lecture des sources, imports et signatures ;
2. **inspection du bytecode compilé** — `javap -c` sur *chaque* `.class` produit, lambdas et classes
   internes comprises, à la recherche de `net/minecraft/client/` et `com/mojang/blaze3d/`.

Résultat : seules `Frame`, `Hands`, `Hoard`, `Mosaic`, `Reach`, `CanvasRenderer`, `CanvasState`,
`Lathe`, `Wheel$1` en contiennent, et aucune n'est nommée depuis une classe commune. `Easel`,
`Groove`, `Canvas`, `Brush`, `Studio`, `Trim`, `Slots`, `Reel`, `Press`, `Wax`, `Post` et tous les
paquets : **zéro référence**.

C'est la vérification à refaire au moindre doute — elle prend dix secondes et elle ne se trompe pas.

## 1 quater. Les encadrements et le graveur

### Encadrements — `painting/Trim.java`

Cinq présentations : **sans cadre** (le style affiche/graffiti), bois, or, pierre, fer. Le cadre est
porté par une donnée synchronisée de la toile, modifiable après coup sans retransmettre l'image.

Rendu : quatre bandes plates par-dessus le bord de l'image, teintées par la **couleur de sommet** et
puisant dans la même case unie de la mosaïque que le dos. Aucune texture supplémentaire, donc aucun
second lot de dessin — c'est ce qui aurait annulé tout le bénéfice de l'atlas.

Sans cadre : ni dos ni tranches, et la face avance de `0,015` bloc au lieu de `0,03125`. Les deux
constantes à connaître si un scintillement apparaît en jeu :

| Constante | Où | Ce qu'elle règle |
|---|---|---|
| `Trim.skin()` | `Trim.java` | distance motif ↔ mur pour le style sans cadre |
| `CanvasRenderer.LIP` | `CanvasRenderer.java` | distance cadre ↔ image |

### Graveur — `disc/Burner.java`, `BurnerEntity.java`, `Lathe.java`, `Slots.java`, `Wheel.java`, `Post.java`

Objets et blocs plutôt que commandes : `lanterne:disque_vierge`, `lanterne:graveur`, tous deux
fabricables et présents dans l'onglet créatif. Les commandes restent, en secours d'administrateur.

**La décision structurante** est expliquée en tête de `Slots.java` : pas de rechargement de données
pendant la partie, parce qu'il périmerait les `Holder` des disques déjà en inventaire et que
`RegistryFixedCodec` refuserait de les écrire — composant perdu à la sauvegarde. D'où un jeu de
sillons réservés d'avance, à durées réparties géométriquement.

**Recettes** (équilibrage révisable, `data/lanterne/recipe/`) :

| Objet | Recette |
|---|---|
| Disque vierge ×8 | 8 teintures noires autour d'une pépite de fer |
| Graveur | 6 lingots de cuivre, 1 lingot de fer, 1 bloc de note |
| Pinceau ×4 | 1 laine blanche sur 2 bâtons |

## 1 quinquies. Le disque muet — trois défauts, aucun mixin

Essai en jeu : gravure réussie, conversion réussie, durée exacte, mais **silence** dans le jukebox.
Et `sillons.properties` vide au redémarrage. Trois causes distinctes, toutes dans mon code.

### Défaut 1 — le flux était résolu trop tôt (c'est lui qui rendait muet)

Le mixin m'avait été autorisé. **Je ne m'en suis pas servi, parce qu'il aurait corrigé le mauvais
problème.** La lecture du code l'établit :

| Ce que j'ai vérifié | Où | Conclusion |
|---|---|---|
| `SoundManager` garde un `Map<Identifier, Resource>` figé au chargement | `SoundManager:62` | mais un `Resource` ne porte **pas** d'octets |
| `Resource.open()` rappelle son `IoSupplier` | `Resource:43` | à **chaque** appel, sans cache |
| Le cache de tampons décodés | `SoundBufferLibrary` | n'existe **que** dans `getCompleteBuffer` |
| Quel chemin prend un son en flux | `SoundEngine:433` `if (!isStreaming)` | `getStream`, qui n'a **aucun** cache |
| Ce que fait la validation au chargement | `SoundManager:134` | teste la **présence de la clé**, n'ouvre jamais le flux |

Le moteur relit donc bien le fichier à chaque lecture. Ce qui était figé, c'était **mon
fournisseur** : `burntStream` choisissait la branche — vrai morceau ou silence — *au moment de le
construire*, et au chargement tous les sillons sont vides. Chaque sillon a donc reçu, pour toute la
session, le fournisseur qui rend le silence.

Le correctif est un déplacement de trois lignes : c'est désormais le **corps** du fournisseur
(`Reel.open(int slot)`) qui interroge `Slots`. Un mixin sur `SoundManager` aurait ajouté une
invalidation de cache là où il n'y a pas de cache.

### Défaut 2 — les gravures perdues à l'extinction

`Slots` n'avait qu'une table statique. En partie solo, client et serveur partagent la machine
virtuelle : `Wheel.forget()` (déconnexion du client) la vidait, puis `ServerStopping` l'écrivait
par-dessus. Les deux horodatages du journal coïncident à la seconde.

Deux corrections, indépendantes :

- **deux tables** — `TAKEN` (serveur, persistée) et `SEEN` (miroir client, jamais écrite). Elles ne
  se recouvrent jamais ; aucune ne peut plus effacer l'autre ;
- **plus d'écriture à l'extinction** — chaque gravure et chaque `/disque liberer` persistent déjà
  sur-le-champ. Une écriture de plus ne pouvait qu'ajouter du risque, et c'est le risque qui s'est
  réalisé.

### Défaut 3 — deux sillons pour le même morceau

Un conteneur Ogg porte un **numéro de série de flux tiré au hasard** à chaque encodage : deux
conversions du même MP3 donnent des fichiers de taille identique et d'octets différents — d'où deux
empreintes, deux sillons, et une retransmission complète. Le journal le montre exactement
(4 504 492 octets les deux fois, sillons 45 puis 46).

Corrigé à deux endroits :

- `Wheel` tient un index `conversions.properties` : *empreinte de la source → empreinte du morceau*.
  Le même fichier choisi deux fois rend le même OGG au bit près, et la conversion `ffmpeg` est
  économisée ;
- `Groove.engrave` **réutilise** un sillon qui porte déjà cette empreinte au lieu d'en prendre un
  nouveau.

Réécrire le numéro de série à une valeur fixe aurait été l'autre voie : il aurait fallu recalculer la
somme de contrôle de chaque page, pour un résultat plus fragile.

> **Note d'architecture.** `.cache/` et `sillons.properties` sont **globaux**, pas par monde — comme
> le cache des images. Un disque gravé dans un monde reste jouable dans un autre. C'est cohérent avec
> le reste du paquet, mais c'est un choix : si tu le veux par monde, c'est `Groove.cacheFolder()`
> qu'il faut faire dépendre du chemin de sauvegarde.

## 2. Ce qui reste à décider — et qui n'a pas été fait de mon chef

### Un onglet créatif partagé

`Easel` et `Groove` ajoutent leurs objets à l'onglet de Lanterne via
`BuildCreativeModeTabContentsEvent`, en testant que l'espace de noms de l'onglet est `lanterne`. Cela
fonctionne **sans toucher à `Contents.java`**. Si tu préfères que le pinceau apparaisse dans la liste
explicite de `Contents.TAB.displayItems(...)`, c'est une ligne à ajouter là-bas — je ne l'ai pas
faite, ce fichier n'étant pas le mien.

### Recettes de fabrication

Il n'y en a **aucune**. Le pinceau et le disque s'obtiennent par commande ou en créatif. Une recette
supposerait un choix d'équilibrage qui t'appartient : un pinceau à un bâton et une laine serait
gratuit, à un diamant serait absurde. Les fichiers iraient dans `data/lanterne/recipe/`.

### La commande `/tableau` et `/disque` sont des arbres séparés

Elles ne sont **pas** greffées sur `/lanterne`, qui est dans `report/`. Si tu veux les y intégrer,
c'est `Easel.onRegisterCommands` et `Groove.onRegisterCommands` à transformer en méthodes rendant un
`LiteralArgumentBuilder`, puis un `.then(...)` dans `LanterneCommand`.

## 3. Limites connues, et pourquoi elles sont là

| Limite | Raison |
|---|---|
| — | *(corrigé)* Les morceaux **gravés** transitent désormais vers les autres joueurs, par le même mécanisme que les images : catalogue annoncé, réclamation, tranches réglées au tick, cache disque nommé par empreinte. Seuls les morceaux du **dossier** restent locaux à chaque machine |
| Pas d'encodeur Vorbis embarqué | Aucun en Java pur qui vaille la peine ; un natif pour trois plateformes alourdirait le mod de plusieurs Mio. `ffmpeg` est cherché dans le `PATH`, et son absence est **annoncée avec la ligne de commande exacte à taper** |
| Un disque gravé ne s'efface pas | Le sillon resterait occupé de toute façon ; rendre un vierge en échange de rien serait une duplication déguisée. `/disque liberer <n>` permet à un opérateur de récupérer un sillon, et le fichier `sillons.properties` est lisible à la main |
| Un sillon laisse jusqu'à ~6 % de silence après le morceau | La durée d'un sillon est figée au chargement du registre. La répartition géométrique ramène le pire cas à onze secondes sur trois minutes ; augmenter `sillons` le réduit encore. C'est le prix de l'absence de rechargement, et il est payé sciemment |
| Les disques du **dossier** demandent toujours `/reload` | Eux passent par une vraie entrée de registre, avec leur durée exacte. C'est l'inverse exact du graveur, et c'est pourquoi les deux chemins coexistent |
| Les graffitis d'Immersive Paintings ne sont pas migrés | Entités d'un autre type, posées à plat sur le sol ou le plafond. Une toile s'accroche à un mur, et `HangingEntity.setDirection` **lève une exception** sur un axe vertical : les aliaser ferait planter le chargement du chunk |
| Les cadres 3D ne sont pas migrés | Ce sont eux qui coûtaient cher chez le mod d'origine : géométrie chargée depuis des `.obj` et repoussée sommet par sommet à chaque image |

## 4. Ce qui n'a pas pu être vérifié

Le mod **compile** (`./gradlew compileJava` et `./gradlew build` passent) et deux de ses algorithmes
les plus risqués ont été testés hors du jeu :

- **la durée d'un OGG** : vérifiée sur les disques de vanilla `11`, `13` et `5` — 71 s, 178 s, 178 s,
  exact ;
- **la chaîne de traitement des images** : 40 images réelles du cache d'Immersive Paintings passées
  au moulin, 1,27 s au total, aucun refus.

En revanche, **rien n'a été exécuté en jeu** — consigne explicite de ne pas lancer `runClient` ni
`runServer`, le dossier `run/` étant occupé par des mesures. Le rendu, lui, a été vu par le
commanditaire et fonctionne. Restent à voir en conditions réelles :

1. **Les deux écrans.** La mise en page de `Frame` (tableaux) et de `Lathe` (graveur) est calculée à
   partir des dimensions du panneau et n'a jamais été affichée : un bouton peut déborder, un texte
   peut être coupé. Rien de structurel — tout est dans leurs `init()` et `extractRenderState()`, et
   se corrige en changeant des nombres.
2. **Le sélecteur de fichiers natif.** Vérifié sur la signature, pas à l'exécution. Réserve connue :
   sous macOS, certaines versions exigent que les dialogues natifs s'ouvrent depuis le fil principal.
   En cas d'échec, `Reach.browse` l'annonce proprement et les deux autres voies restent entières.
3. **Le téléchargement**, y compris ses refus : lien qui rend une page web, image trop lourde, hôte
   injoignable, domaine hors liste.
4. **La remontée d'image vers le serveur** et l'application sur la toile visée.
5. **Le graveur de bout en bout** : insertion du vierge, écran, conversion par ffmpeg, remontée au
   serveur, six secondes de gravure, disque en main, lecture dans un jukebox. C'est la chaîne la plus
   longue du mod et celle qui n'a jamais tourné.
6. **Les sillons** : que `sounds.json` déclare bien les 64 entrées vides sans que Minecraft ne
   proteste au chargement, et qu'un sillon rempli en cours de partie se joue **sans** rechargement.
   C'est le pari central du graveur ; s'il est faux, il faudra en passer par un mixin sur
   `SoundManager` — et ce serait alors un point d'accroche à te soumettre, pas à écrire moi-même.
7. **Les encadrements**, et surtout le style sans cadre : absence de scintillement contre le mur à
   distance, et sens de la normale sur les quatre orientations.
6. **La migration d'un monde réel** contenant des tableaux d'Immersive Paintings. C'est le point le
   plus délicat et le plus facile à annuler : il suffit de retirer les deux
   `ENTITIES.addAlias(...)` de `Easel.register` pour que le mécanisme disparaisse entièrement.
