# Recherche client — mesure, coordination client+serveur, angles morts (NeoForge 26.1.2)

Rapport de recherche pur (aucun fichier de `src/` touché). Méthode identique aux rapports
précédents : chaque nom de classe cité a été **lu** dans `.mcsrc/net/minecraft/...`
(sources vanilla-patchées livrées avec le projet, `minecraft-patched-26.1.2` décompilé), sauf
mention explicite du contraire. Les classes NeoForge elles-mêmes
(`net.neoforged.neoforge.*`) ne sont **pas** dans `.mcsrc` — celui-ci ne contient que
`net.minecraft`. Quand un nom NeoForge est cité, c'est soit parce qu'il apparaît en toutes
lettres dans une source vanilla-patchée (donc vérifié malgré tout), soit parce qu'il apparaît
déjà dans le code existant de Lanterne (`src/`, lu mais non modifié), soit — et c'est marqué
à chaque fois — par connaissance générale non vérifiée dans ce dépôt.

Contexte : Lanterne devient exigé client **et** serveur. Le blocage à lever en premier est
outillage : sans mesure reproductible du client, aucune optimisation client ne peut être
retenue par ce projet, qui ne touche jamais à ce qu'il ne peut pas chiffrer.

---

## POINT 1 — Mesurer le client sans intervention humaine

### 1.1 Ce qui existe déjà, vérifié dans `Minecraft.java`

Trois éléments, tous confirmés par lecture directe :

```java
// Minecraft.java
private static int fps;                 // ligne 370
private long frameTimeNs;                // ligne 371
...
public int getFps() { return fps; }               // ligne 1499
public long getFrameTimeNs() { return this.frameTimeNs; }  // ligne 1503
```

**`getFps()`** est une moyenne sur la dernière seconde écoulée (recalculée toutes les
1000 ms réelles, lignes 1421-1425 : `while (Util.getMillis() >= this.lastTime + 1000L) { fps
= this.frames; ... }`). C'est un entier grossier, mis à jour une fois par seconde.

**`getFrameTimeNs()`** est bien plus fin et bien plus stable au sens demandé par la mission :
il est calculé ligne 1389, `this.frameTimeNs = Util.getNanos() - renderStartTimer;`, **avant**
l'appel au limiteur d'images (`FramerateLimiter.limitDisplayFPS`, ligne 1404, exécuté seulement
`if (framerateLimit < 260)`). Autrement dit : `getFrameTimeNs()` mesure le coût réel de rendu
d'une image, non pollué par une éventuelle synchronisation verticale ou un plafond d'images —
exactement le signal qu'il faut pour un banc. `getFps()`, lui, compte les images réellement
affichées (`this.frames++`, ligne 1408, après le plafond), donc peut être artificiellement bas
si un plafond est actif.

**Point d'accroche par image, sans mixin.** Les lignes 1381-1383 :

```java
net.neoforged.neoforge.client.ClientHooks.fireRenderFramePre(this.deltaTracker);
this.gameRenderer.render(this.deltaTracker, advanceGameTime);
net.neoforged.neoforge.client.ClientHooks.fireRenderFramePost(this.deltaTracker);
```

sont dans le fichier vanilla-patché livré avec ce projet — donc vérifiées, même si le corps de
`ClientHooks` (classe NeoForge, pas vanilla) n'est pas dans `.mcsrc`. Par convention NeoForge
(non re-vérifiée ici faute de source), ce genre de `ClientHooks.fireXxx` correspond
généralement à un évènement `RenderFrameEvent.Pre` / `.Post` sur le bus client — donc
**probablement** souscriptible depuis un mod sans aucun mixin. À confirmer par un agent qui a
accès au jar NeoForge lui-même (pas fourni dans ce dépôt de recherche).

**Piège vérifié à ne pas reproduire :** ligne 1385, `if
(!this.gameRenderer.getGameRenderState().windowRenderState.isMinimized) {
this.mainRenderTarget.blitToScreen(); }`. **Si la fenêtre est réduite, la présentation à
l'écran est purement et simplement sautée.** Un agent qui croirait malin de minimiser la
fenêtre pour faire tourner le banc « en arrière-plan » obtiendrait un `frameTimeNs` qui ne
mesure plus le vrai travail de rendu (le blit GPU→écran, une bonne part du coût réel, disparaît).
**La fenêtre doit rester visible et non minimisée pendant toute la mesure.**

### 1.2 Charger un monde sans un seul clic — vérifié, déjà vanilla

`Main.java` (le point d'entrée du client, `net.minecraft.client.main.Main`) déclare, ligne 66-67 :

```java
OptionSpec<String> quickPlaySingleplayerOption = parser.accepts("quickPlaySingleplayer").withOptionalArg();
```

et la construit dans `GameConfig.QuickPlayData` (ligne 195). Elle est consommée dans
`net.minecraft.client.quickplay.QuickPlay.connect(...)`, lu en entier :

```java
case GameConfig.QuickPlaySinglePlayerData singlePlayerData:
    String worldId = singlePlayerData.worldId();
    if (StringUtil.isBlank(worldId)) {
        worldId = getLatestSingleplayerWorld(minecraft.getLevelSource());
    }
    joinSingleplayerWorld(minecraft, worldId);
```

```java
private static void joinSingleplayerWorld(Minecraft minecraft, @Nullable String identifier) {
    if (!StringUtil.isBlank(identifier) && minecraft.getLevelSource().levelExists(identifier)) {
        minecraft.createWorldOpenFlows().openWorld(identifier, () -> minecraft.setScreen(new TitleScreen()));
    } else { ... DisconnectedScreen ... }
}
```

**C'est un mécanisme 100 % vanilla, présent depuis les consoles/Bedrock crossplay, jamais
retiré.** `./gradlew runClient --args="--quickPlaySingleplayer nom-du-monde"` charge
directement un monde solo sauvegardé, sans passer par l'écran-titre ni la liste des mondes, et
sans qu'aucun clic humain n'intervienne. Un identifiant vide charge le monde le plus récent.
C'est exactement le point d'entrée que la mission demandait de chercher (« un moyen de
démarrer le client, charger un monde... sans clic humain ») — **il existe déjà**, personne n'a
besoin de l'inventer.

Détection de fin de chargement : `net.minecraft.client.gui.screens.LevelLoadingScreen` (lu),
qui porte un `ChunkLoadStatusView`/`loadTracker.serverProgress()` (ligne 80). Un agent peut
soit attendre que `Minecraft.getInstance().screen` ne soit plus une instance de cet écran, soit
plus simplement attendre que `Minecraft.getInstance().level != null` et que le joueur local
existe — les deux passages sont vérifiables sans mixin depuis un `ClientTickEvent`.

### 1.3 Fixer la caméra — pas besoin de toucher `Camera`

`net.minecraft.client.Camera` (lu en entier) a ses méthodes `setPosition`/`setRotation` en
**`protected`** (lignes 349-368), et recalcule sa position à chaque image à partir de l'entité
suivie (`setEntity(Entity)`, ligne 426 ; utilisé notamment ligne 100,
`this.setEntity(player);`). Vouloir piloter `Camera` directement demande donc soit un
accessor-mixin, soit d'étendre la classe — inutilement compliqué.

**Plus simple et tout aussi exact :** puisque la caméra suit le joueur local, il suffit de
fixer la position/rotation de l'**entité joueur** elle-même à chaque tick (`Entity.setPos`,
`setYRot`/`setXRot`, déjà publiques dans `Entity.java`). Le mod tourne des deux côtés
(intégré : client + serveur intégré dans le même processus en solo) : il a autorité complète
sur cette entité, pas besoin de paquet ni de triche réseau. Un simple hook côté client (tick de
fin de monde chargé) qui téléporte le joueur à des coordonnées et angles fixes, scène par
scène, suffit.

### 1.4 Terminer le processus proprement, sans intervention

`Minecraft.stop()` (lu, ligne 1639) fait `this.running = false;` (ligne 1641). La boucle
principale, `run()` (ligne 914), tourne sur `while (this.running)` (ligne 927) : dès que
`stop()` est appelé, elle se termine, et `Main.main` (qui a fait `newMinecraft.run();` puis
`minecraft.stop(); ... newMinecraft.destroy();`) rend la main normalement. **Le processus
`runClient` se termine tout seul, code de sortie 0, sans `kill` ni fermeture de fenêtre
manuelle.** C'est le mécanisme de fin de banc : le mod appelle
`Minecraft.getInstance().stop()` après avoir collecté ses échantillons.

### 1.5 Le mod « FPS Benchmark » — vérifié, la conclusion du rapport précédent est correcte

Page Modrinth récupérée (`modrinth.com/mod/fps-benchmark`) : loaders supportés affichés
**« Fabric NeoForge(Soon) »** — NeoForge est annoncé mais **pas encore livré**
(septembre 2026). Licence MIT, mais **aucun lien de code source public trouvé** (ni sur la
page Modrinth, ni par recherche ciblée GitHub) : impossible de réutiliser son implémentation,
seulement sa méthode documentée (monde temporaire à graine déterministe, chemin de caméra
scripté d'environ 3 minutes, rapport Markdown/JSON avec moyenne, 1 %-low, 0,1 %-low, p99).
**Verdict : le rapport précédent qui disait « Fabric-only » avait raison, et ça reste vrai
aujourd'hui.** Aucun outil clé-en-main n'existe pour NeoForge 26.1.2 ; il faut le construire
soi-même — mais les briques vanilla nécessaires (§1.2 à 1.4) existent déjà et sont vérifiées.

Autre mod trouvé, « Benchmark » (NeoForge 1.21.1) : ajoute un bouton au menu pause,
échantillonne FPS/TPS/CPU/GPU/mémoire sur une durée réglable et écrit un rapport texte. **Mais
il est pensé pour un usage interactif** (bouton de menu pause) — pas pour un lancement
automatisé sans humain. Ne résout pas le problème posé ; à noter comme référence d'implémentation
si son code est un jour étudié, pas comme solution.

### 1.6 Un vrai mode « headless » (sans affichage du tout) — n'existe pas, et ce n'est pas ce qu'il faut

Recherche confirmée : GLFW (utilisé sous LWJGL, donc sous Minecraft) n'a pas de support natif
d'un contexte OpenGL hors-écran/headless (issue GLFW ouverte de longue date, non résolue). Les
contournements connus (Xvfb, EGL/OSMesa) sont une affaire Linux/serveur X — **hors sujet ici
:** la machine de développement est Windows, et de toute façon, un rendu logiciel factice
(OSMesa) **mesurerait un pipeline de rendu différent de celui que Sodium/ImmediatelyFast/etc.
optimisent** — ce serait mesurer autre chose que ce qu'on veut mesurer. **Il ne faut donc pas
chercher un mode headless : il faut un client normal, avec sa vraie fenêtre visible, piloté par
script, qui se termine tout seul.** C'est exactement ce que §1.2-1.4 fournissent.

### 1.7 Synergie non exploitée : réutiliser les scènes serveur existantes pour la charge visuelle

Lanterne a déjà, côté serveur, un générateur de scènes de charge reproductibles :
`src/main/java/fr/clubcitrouille/lanterne/lab/Scene.java` (lu, non modifié), avec des charges
`ring`, `pen`, `items`, `mixed` déjà utilisées par le banc serveur (`Bench.java`). Comme le mod
tournera désormais aussi côté client, **le même appel qui peuple le monde intégré pour le banc
serveur peuple aussi le monde que le client va afficher** — pas besoin de construire une
deuxième infrastructure de scènes pour le client. Un agent qui implémente le protocole devrait
donc, au chargement du monde de banc (§1.2), appeler la même API que `Bench`/`Scene` utilise
déjà côté serveur, puis fixer la caméra (§1.3) aux coordonnées qui surplombent cette scène.
**Un seul run produit alors, simultanément, la mesure serveur (ms/tick, déjà outillée) et la
mesure client (images/temps de rendu, nouvellement outillée) sur exactement la même charge.**

### 1.8 Protocole retenu, étape par étape

1. Préparer une fois un monde de banc sauvegardé (`saves/lanterne-bench` ou équivalent sous le
   dossier `run/` de `runClient`), avec la même graine que le banc serveur.
2. Lancer `./gradlew runClient --args="--quickPlaySingleplayer lanterne-bench"` (ou via une
   tâche Gradle `runClientBenchmark` dédiée dans `neoForge.runs {}`, avec cet argument
   pré-rempli — plus reproductible qu'un argument tapé à la main).
3. Un déclencheur côté client (config/propriété système lue une fois, p. ex.
   `-Dlanterne.benchmark=pen`) attend la fin du chargement (§1.2), demande au serveur intégré
   de peupler la scène demandée via `Scene`/`Bench` (§1.7), puis fixe le joueur aux coordonnées
   de vue prévues pour cette scène (§1.3). Désactiver aussi tout plafond d'images
   (`framerateLimit >= 260`, cf. ligne 1403) pour ne pas mesurer un plafond artificiel.
4. À partir de là, un collecteur (souscrit au point d'accroche par image, §1.1) ignore les
   100-200 premières images (compilation de shaders, premier maillage de chunks — un
   pic de démarrage qui n'a rien à voir avec le régime établi), puis accumule
   `getFrameTimeNs()` sur une fenêtre fixe (par exemple 1800 ticks / 90 s réelles, à l'image de
   ce que fait FPS Benchmark en ~3 min).
5. Calculer moyenne, médiane, 1 %-low et 0,1 %-low (les creux comptent plus que la moyenne pour
   la fluidité perçue), écrire un rapport (CSV/Markdown) dans `run/`, puis appeler
   `Minecraft.getInstance().stop()` (§1.4). Le processus se termine seul, code 0.
6. Répéter N=5 par condition (mod activé/désactivé, fonctionnalité activée/désactivée) par un
   script qui relance `runClient` en boucle, exactement la discipline déjà appliquée par
   `Bench.java`/`Herd.java` côté serveur. Comparer médianes, jamais un run unique.

### 1.9 Limites honnêtes du protocole

- **Bruit de mesure plus élevé que côté serveur.** Un rendu GPU dépend du pilote, des autres
  processus, de l'état thermique de la machine — bien plus variable qu'un banc CPU pur.
  Comparaisons valables **seulement sur la même machine**, jamais entre deux postes.
- **Pas de CI sans affichage.** Ce protocole suppose une session Windows avec un écran (réel ou
  virtuel de type RDP/VNC persistant) attaché — il ne tourne pas dans un runner GitHub Actions
  sans GPU. Ce n'était pas l'objectif énoncé (« sur ce projet, sur cette machine », pas
  « en CI publique ») ; si ce besoin apparaît plus tard, c'est un problème distinct
  (runner GPU cloud), non résolu ici.
- **`getFrameTimeNs()` mesure le rendu, pas la fluidité perçue.** Un signal réseau qui change le
  pas d'interpolation (Point 2) améliore le *ressenti* de fluidité sans changer le temps de
  rendu d'une image — donc **invisible pour ce protocole**. Voir le tableau de synthèse en fin
  de rapport : chaque piste indique explicitement si elle est mesurable par ce protocole ou
  s'il en faudrait un autre (ex. variance de position/jerk, mesurable en accumulant
  `entity.position()` par image plutôt que `frameTimeNs`).

---

## POINT 2 — Ce que seul un mod client+serveur peut faire

### 2.1 Signaler au client la cadence réelle d'une entité throttlée (piste la plus solide)

**Ce que le serveur sait déjà, vérifié dans `src/` (lu, non modifié) :**

`fr.clubcitrouille.lanterne.core.Cadence.forEntity(entity, distance, pressure)` calcule un
entier `period` : le nombre de ticks entre deux « réveils » de simulation pour cette entité
(1 à 72 pour le tout-venant, 1 à 4 pour les PNJ/pillards — `Npc`/`Raider`, ceiling à
`SLOWEST_PRODUCTIVE`). `fr.clubcitrouille.lanterne.core.NetworkThrottle.shouldSend` réutilise
cette même cadence pour décider d'envoyer ou non les paquets de position
(`period / SOFTENING`, plafonné à 12). **Le serveur sait donc, à chaque tick, exactement à
quelle fréquence il alimente le client en positions pour chaque entité — et ne le dit à
personne.**

**Ce que le client fait de son côté, vérifié dans `.mcsrc` :**

`net.minecraft.world.entity.InterpolationHandler` (lu en entier) porte
`DEFAULT_INTERPOLATION_STEPS = 3` (ligne 11) et une méthode publique
`setInterpolationLength(int steps)` (lignes 73-75). `LivingEntity.java` (lu) instancie ce
handler ligne 243 : `protected final InterpolationHandler interpolation = new
InterpolationHandler(this);` — via le constructeur à un argument, donc **toujours 3 pas, quel
que soit l'espacement réel des paquets reçus.** `Entity.moveOrInterpolateTo(...)` (lignes
2468-2491, lu) est le point d'entrée appelé depuis `ClientPacketListener` (confirmé : 5 appels
trouvés, lignes 655/748/750/753/816, sur réception des paquets de mouvement/téléportation) ; il
délègue à `interpolationHandler.interpolateTo(position, yRot, xRot)`, qui étale le déplacement
sur `interpolationSteps` (donc 3) ticks, quelle que soit la fréquence réelle des paquets reçus.

**Le défaut visuel que ça cause, déductible du code lu :** `InterpolationHandler.interpolate()`
(lu) décrémente `steps` à chaque appel et, une fois `steps == 0`, `position()` retourne
`this.entity.position()` — la dernière position interpolée, **figée**, jusqu'au prochain
paquet. Pour une entité dont `NetworkThrottle` espace les paquets à 1 tous les 12 ticks : 3
ticks de mouvement lissé, puis **9 ticks de gel complet**, puis un nouveau saut suivi de 3
ticks de rattrapage. C'est exactement le symptôme décrit dans la mission (« le client interpole
vers une position figée puis rattrape brutalement ») — et c'est un défaut que Lanterne
introduit *déjà*, aujourd'hui, sans le savoir, dès que `NetworkThrottle` espace au-delà de
3 ticks (ce qui arrive dès que `simulation > 6`, cf. `period / SOFTENING`).

**La correction, réalisable sans toucher au protocole vanilla :** un paquet personnalisé
NeoForge (canal enregistré des deux côtés puisque le mod est désormais obligatoire
client+serveur — `net.neoforged.neoforge.network.registration.NetworkRegistry` et son
`checkPacket` sont déjà touchés par Lanterne dans
`src/main/java/fr/clubcitrouille/lanterne/mixin/NetworkRegistryMixin.java`, lu, donc
l'infrastructure de canal personnalisé est déjà en partie en place) porte simplement
`(entityId, period)`, envoyé seulement **quand `period` change** — pas à chaque tick, un
changement de palier de distance suffit. Côté client, un accessor-mixin minimal sur
`LivingEntity` appelle `entity.getInterpolation().setInterpolationLength(period)` au lieu de
laisser la valeur par défaut de 3. Le mouvement d'une créature lointaine devient alors
continûment lissé sur toute la fenêtre réelle entre deux paquets, au lieu de geler puis
rattraper.

- **Classes vérifiées :** `net.minecraft.world.entity.InterpolationHandler` (méthode
  `setInterpolationLength`, ligne 73), `net.minecraft.world.entity.LivingEntity` (champ
  `interpolation`, ligne 243 ; `getInterpolation()`, ligne 3328 ; appel dans `aiStep()`,
  ligne 3063), `net.minecraft.world.entity.Entity.moveOrInterpolateTo` (lignes 2468-2491),
  `net.minecraft.client.multiplayer.ClientPacketListener` (5 sites d'appel confirmés).
  Côté Lanterne : `Cadence.forEntity`, `NetworkThrottle.shouldSend` (existants, réutilisés en
  lecture seule pour cette idée).
- **Faisabilité réseau :** réelle. Le canal personnalisé NeoForge exige une négociation des
  deux côtés — désormais garantie puisque le client est obligatoire. Coût réseau
  supplémentaire négligeable (un entier, seulement au changement de palier).
- **Risque visuel :** faible si la valeur est bien réinitialisée à 3 quand l'entité revient à
  pleine cadence (sinon une créature qui redevient proche resterait lissée avec un retard
  perceptible). À garder : ne jamais dépasser une poignée de ticks (l'ordre de grandeur des
  cadences lointaines de Lanterne va jusqu'à 72 — un test doit vérifier qu'un
  `interpolationSteps` aussi grand ne produit pas un mouvement visiblement « en retard » sur ce
  qui se passe réellement, seulement plus lisse).
- **Mesurable avec le protocole du Point 1 ? Non, pas directement.** C'est un gain de fluidité
  perçue, pas de temps de rendu — `getFrameTimeNs()` ne bougera pas. Il faut une métrique
  différente : échantillonner `entity.position()` par image dans la scène `ring` (créatures
  dispersées, déjà un banc existant) avec et sans le signal, et comparer la variance de
  l'accélération apparente (le « jerk ») de quelques entités lointaines suivies. Une extension
  simple du protocole de mesure client, pas le même signal.

### 2.2 Paquets de position groupés pour les entités lointaines

**Ce qui existe déjà en vanilla, vérifié (donc à ne pas réinventer) :**
`net.minecraft.network.protocol.game.ClientboundBundlePacket` existe et est utilisé par
`ServerEntity.sendChanges()` (lu, lignes 88-190) — mais seulement pour grouper **atomiquement
deux paquets de la même entité** (mouvement + poussée de projectile, lignes 178-184), jamais
pour grouper les mises à jour de **plusieurs entités différentes**. Chaque entité suivie
appelle son propre `sendChanges()` indépendamment, avec son propre `player.connection.send(...)`.
**Il n'y a donc aucun regroupement inter-entités en vanilla : la piste n'est pas redondante
avec quelque chose qui existerait déjà.**

**Faisabilité :** réelle, via un paquet personnalisé NeoForge qui regroupe, une fois toutes les
N ticks, les deltas de position de toutes les entités d'un même groupe de cadence pour un
joueur donné, plutôt que d'attendre que chaque `ServerEntity` envoie le sien indépendamment.

**Réserve honnête sur le gain :** le propre javadoc de `NetworkThrottle` (lu) cadre le problème
comme un coût de **connexion/nombre de paquets**, pas de bande passante brute — et l'essentiel
de ce gain-là est déjà pris par l'espacement que `NetworkThrottle` fait aujourd'hui (moins
d'envois du tout, pas des envois plus compacts). La couche Netty sous-jacente regroupe déjà
plusieurs écritures dans le même flush TCP au niveau octets ; le gain réel d'un regroupement
applicatif serait surtout **un coût CPU par objet paquet** (allocation, encodage VarInt,
répartition par NeoForge) économisé, pas une réduction de bande passante significative. C'est
une piste secondaire à celle du §2.1, plus complexe à construire (nouveau type de paquet +
dispatch client) pour un gain plus incertain.

- **Mesurable avec le protocole du Point 1 ? Non — mais déjà mesurable aujourd'hui**, sans rien
  construire de nouveau : le compteur de paquets par seconde côté serveur, déjà en place
  d'après `feuille-de-route.md` (« Réseau / paquets ... mesuré »), suffit à comparer
  paquets/s avec et sans regroupement. Pas besoin du protocole client pour celle-ci.

### 2.3 Annoncer les entités « en sommeil » pour que le client cesse de les animer

Prolongement naturel du §2.1 : quand `Cadence.forEntity` place une entité dans le dernier
palier (`Cadence.bucket` la classe « En sommeil », au-delà de 24 pas de 16 blocs, donc très
loin — largement au-delà de la plupart des distances de rendu courantes), le serveur pourrait
aussi dire au client de cesser de faire progresser l'état d'animation (cycle de marche,
émissions de particules liées au comportement) de cette entité, en plus du signal
d'interpolation. Ce n'est **pas** la même chose que la disparition du rendu que fait déjà
EntityCulling/MoreCulling (eux ne rendent pas l'entité si elle est hors champ, mais son
`tick()` client continue de tourner en entier si elle est chargée) — c'est réduire le **coût de
tick** d'une entité chargée mais fonctionnellement endormie, ce qu'aucun des deux mods de
culling ne fait puisqu'ils travaillent au niveau du rendu, pas du tick.

- **Classes concernées, non vérifiées en détail par manque de temps dans cette recherche :**
  la logique d'animation vit dans les sous-classes concrètes (`Mob`, `AnimalModel`, etc.), pas
  dans une méthode centrale unique — un agent qui implémenterait ceci devrait d'abord
  cartographier où l'état d'animation progresse réellement (probablement
  `LivingEntity.tick()`/`aiStep()` côté client, à distinguer soigneusement du travail déjà
  vérifié en §2.1).
- **Risque visuel :** plus élevé que §2.1 — une entité chargée mais visuellement figée peut
  paraître cassée si le seuil de sommeil n'est pas strictement aligné avec la distance de rendu
  réelle du joueur (variable selon les réglages vidéo, contrairement au seuil serveur qui est
  fixe). Non mesuré, non construit : à traiter comme une extension du §2.1, seulement après
  qu'il aura fait ses preuves.
- **Mesurable avec le protocole du Point 1 ? Oui, potentiellement**, cette fois côté temps de
  rendu/tick client : dans la scène `mixed` à forte densité de créatures lointaines
  (banc déjà existant côté serveur), comparer `getFrameTimeNs()` avec/sans l'arrêt d'animation.
  Gain attendu faible (le tick d'animation à distance est rarement le poste dominant face au
  coût de rendu lui-même) — **à mesurer avant de s'y engager, pas supposer.**

---

## POINT 3 — Ce qui reste non couvert côté client par Sodium + les dix autres mods

Inventaire vérifié par recherche web de ce que fait chaque mod de la liste de référence, pour
identifier les recouvrements et les trous :

| Mod | Ce qu'il couvre réellement | Ce qu'il NE touche PAS |
|---|---|---|
| Sodium / Sodium Extra | Rendu de chunks, mémoire GPU, upload, moteur de lumière **côté rendu** | Logique de jeu, simulation, réseau, GUI |
| ImmediatelyFast | Rendu immédiat (GUI, items, police) par lots | Rendu du monde, entités, réseau |
| MoreCulling / EntityCulling | Ne pas *rendre* les entités/block-entities hors champ | Le **tick** de ces mêmes entités continue en entier |
| BadOptimizations | Micro-optimisations client isolées (rendus de debug inactifs, couleur du ciel, cache de lightmap) — **vérifié via recherche web** : client seul, mainteneur `thosea` | Réseau, tick d'entité, GC |
| betterfpsdist | Change la forme du rendu (cylindre → sphère étirable), réduit le nombre de sections rendues | Simulation, réseau, mémoire |
| smoothchunk | **Serveur** : étale la sauvegarde de chunks dans le temps pour éviter les pics | Rien côté rendu client — nom trompeur, ce n'est pas un mod client |
| chloride (Sodium++) | Culling de distance pour entités/feuilles, modèles simplifiés — add-on client de Sodium | Réseau, simulation |
| FpsReducer2 | Réduit FPS/CPU/GPU **seulement quand la fenêtre est inactive/minimisée** | N'améliore rien pendant que le joueur joue activement |
| Distant Horizons | Rendu LOD du terrain à très longue distance | Entités, réseau, tick |

**Trois trous confirmés, dans aucun des dix mods :**

1. **Le rubber-banding réseau des entités lointaines** (interpolation figée au-delà de la
   fenêtre par défaut de 3 ticks) — décrit et chiffré au §2.1. Aucun des dix mods ne peut le
   traiter : c'est un problème de protocole client↔serveur, pas de rendu ni de logique locale.
   **C'est exactement la catégorie de gain qu'un mod purement client ne peut pas produire**,
   confirmant le postulat de la mission.

2. **Le coût de tick client des entités chargées mais non rendues** (§2.3) — les mods de
   culling arrêtent le rendu, aucun n'arrête le tick. Angle mort structurel de cette famille de
   mods.

3. **Les pauses liées au ramasse-miettes pendant l'exploration/génération de chunks.**
   Recherche confirmée : un ticket Mojira actuellement suivi et non résolu, **MC-273262**
   (« important »), documente un motif de gel répété sur les versions 1.20.6 à 1.21.3, corrélé
   à la mémoire JVM grimpant vers ~75 % avant chaque à-coup — diagnostiqué par la communauté
   via l'écran de débogage F3 comme un motif de GC (gel régulier et rythmé visible à la fois
   sur le graphe de temps d'image et sur le graphe de TPS), distinct d'un simple chargement de
   chunk (qui, lui, ne perturbe que le graphe d'images). Aucun des dix mods de la liste ne
   réduit le débit d'allocation ; les mods qui s'y attaquent habituellement (ModernFix,
   Lithium — pour sa part côté allocation d'entités) **ne sont pas dans la liste de
   référence**. Piste distincte du reste de ce rapport (n'a rien à voir avec la coordination
   client+serveur), mais un vrai trou, confirmé par une source externe et non par supposition.

**Point notable pour la mémoire, en cohérence avec le rapport RAM déjà produit
(`recherche-ram-boot-client.md`) :** recherche confirmée que **le moteur de lumière vanilla a
déjà absorbé, depuis la version 1.20, les idées centrales du mod Starlight** (files
séparées augmentation/diminution, propagation optimisée) — l'auteur de Starlight lui-même
juge la comparaison peu tranchée sur le client depuis cette date, et le portage Forge autonome
de Starlight est aujourd'hui archivé. **Ce n'est donc plus un trou** — même schéma que
FerriteCore/fastmap déjà documenté côté RAM : une optimisation historique de la communauté,
absorbée par le vanilla, à ne pas rechasser une deuxième fois.

---

## Synthèse — chaque piste, verdict de mesurabilité

| Piste | Classe/méthode vérifiée | Gain estimé | Risque visuel | Mesurable par le protocole §1 ? |
|---|---|---|---|---|
| Protocole de mesure FPS (§1) | `Minecraft.getFps/getFrameTimeNs`, `QuickPlay.joinSingleplayerWorld`, `Minecraft.stop()` — tous vérifiés | — (c'est l'outil, pas une optimisation) | aucun | — |
| Signal de cadence → `InterpolationHandler.setInterpolationLength` (§2.1) | Vérifié des deux côtés (`Cadence`/`NetworkThrottle` existants + `InterpolationHandler`/`LivingEntity`/`ClientPacketListener` vanilla) | Fluidité perçue ↑ sur créatures lointaines ; FPS quasi inchangé | Faible, si réinitialisation correcte au retour en pleine cadence | **Non** — nécessite une métrique de jerk/position, pas frame time |
| Paquets groupés multi-entités (§2.2) | `ClientboundBundlePacket` vérifié non redondant ; `NetworkRegistry.checkPacket` déjà touché par Lanterne | Modeste, surtout CPU par paquet, pas bande passante | Faible | Non, mais déjà mesurable via le compteur de paquets serveur existant |
| Sommeil d'animation annoncé (§2.3) | Principe déductible de `Cadence`/`aiStep`, classes exactes non cartographiées | Incertain, probablement faible | Moyen (dépend du réglage de distance de rendu du joueur) | Oui, potentiellement — à mesurer avant d'engager le travail |
| GC pendant génération de chunks (§3.3) | Ticket Mojira MC-273262 vérifié, aucune classe Lanterne concernée (hors périmètre coordination) | Inconnu, nécessite profilage dédié | Aucun (bug de fluidité, pas de logique) | Oui, via échantillonnage de `frameTimeNs` corrélé à un journal GC — protocole à étendre |

---

## Ce qui n'est PAS faisable, dit franchement

- **Un vrai mode headless (sans GPU/affichage) pour CI publique** n'existe pas et ne peut pas
  être construit à partir de ce qui est disponible ici (GLFW n'a pas de contexte hors-écran
  natif, un rasterizeur logiciel mesurerait un pipeline différent de celui qu'on veut
  optimiser). Si ce besoin se présente un jour, c'est un problème d'infrastructure (runner GPU
  cloud), pas un problème de code Lanterne — à ne pas confondre avec le protocole retenu ici,
  qui répond à la vraie question posée (mesure reproductible sur machine de développement, sans
  clic humain), pas à « mesure sans aucun écran ».
- **Le regroupement de paquets (§2.2)** n'a probablement pas le gain qu'on lui prête
  intuitivement : la vraie économie de connexion, `NetworkThrottle` la fait déjà par
  l'espacement. Ne pas y engager une nuit de travail sans d'abord mesurer, via le compteur de
  paquets déjà existant, ce que le regroupement ajouterait par-dessus l'espacement actuel.
- **Le sommeil d'animation (§2.3)** repose sur une intuition (« l'animation d'une créature
  lointaine coûte quelque chose ») qui n'a pas été chiffrée dans cette recherche — elle pourrait
  très bien être négligeable face au coût de rendu lui-même. À vérifier au banc avant tout
  investissement.
