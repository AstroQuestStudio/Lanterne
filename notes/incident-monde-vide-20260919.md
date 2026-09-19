# Incident — monde de prod remplacé par une extraction vide, 2026-09-19 (RÉSOLU)

Le monde de prod (`/srv/minecraft/world`, Club Citrouille) a été écrasé par une extraction
tronquée pendant une session de test/débogage du mécanisme de téléchargement/envoi du monde
dans LanternePanel (`server/src/world.js`). Panne réelle, **déjà résolue** : le vrai monde a
été restauré, le serveur tourne normalement. Ce document sert de chronologie + post-mortem,
pas d'alerte en cours.

## Chronologie (reconstituée depuis `journalctl` côté VPS, timestamps de fichiers, contenu
des scripts — pas de log applicatif du script en cause, voir plus bas pourquoi)

- **~13:38–13:42** : plusieurs tentatives de téléchargement du monde via le panel web
  (browser → `/api/world/download` → `downloadWorld()`), avec contention de verrou
  (`[world] échec du téléchargement : une opération ... déjà en cours`, logué par
  `lanterne-panel.service`) et des `chmod -R g+rwX /srv/minecraft/world` répétés
  (`ensureWorldReadable()`). Une tentative de redémarrage de `minecraft.service` échoue à
  13:41:41 avec `AccessDeniedException: ./world/session.lock` — signe que ces opérations
  concurrentes touchaient déjà activement les permissions du dossier monde pendant que
  Minecraft essayait d'y écrire. Le serveur finit par redémarrer proprement à 13:42:38.
- **~13:42:38 → 14:31:45** : le serveur tourne normalement avec le vrai monde (943 Mo, 835
  fichiers de région `.mca`, confirmé par la suite).
- **14:31:34** : un appel `sudo systemctl start minecraft` (déclenché par `serverPower()`
  dans `systemctl.js`, donc par un appel applicatif, pas manuel) — le service était déjà
  actif, donc sans effet visible. Cohérent avec le bloc `finally` de `downloadWorld()` qui
  relance le serveur après coup, ici visiblement exécuté très en retard par rapport à
  l'opération qui l'a initié (voir hypothèse de course plus bas).
- **14:31:45** : `sudo systemctl stop minecraft`, cette fois suivi immédiatement de l'arrêt
  réel du service (`Stopping the server`, `Saving chunks...`). C'est très probablement le
  `serverPower('stop')` interne à `uploadWorld()`, juste avant le remplacement du dossier
  `world`. **C'est le remplacement qui a introduit le monde tronqué** (déduit de la fenêtre :
  dernier démarrage sain juste avant, premier échec juste après).
- **14:32:11–14:32:13** : nouveau `systemctl start minecraft` → tentative de redémarrage.
- **14:32:25** : `IllegalStateException: Overworld settings missing` —
  `world/data/minecraft/world_gen_settings.dat` absent, `LevelStorageSource` retombe sur des
  réglages par défaut et le démarrage échoue (`Couldn't find Minecraft server thread`).
  Nouvel essai à 14:32:37, même échec à 14:32:49.
- **14:32:55** : `systemctl stop minecraft`. **Aucune autre tentative de redémarrage
  jusqu'à 14:54** — le service n'étant pas configuré en redémarrage automatique agressif
  après échecs répétés, la panne est restée silencieuse (aucune alerte, personne dessus)
  pendant une vingtaine de minutes avant d'être prise en main.
- **14:54:12** : le vrai dossier `world` est restauré sur le VPS (constaté : timestamp de
  naissance de l'inode `/srv/minecraft/world` = 2026-09-19 14:54:12 UTC).
- **14:54:24–14:54:47** : redémarrage réussi (`Done`), serveur sain. **Vérifié à nouveau à la
  rédaction de ce document : `/srv/minecraft/world` contient bien 835 fichiers `.mca` pour
  943 Mo — le monde restauré est intact.**

## Ce qui a concrètement causé le remplacement par un monde vide

Une session de débogage tournait deux scripts de reproduction en ligne de commande sur le
VPS, appelant **directement** (sans passer par le serveur HTTP du panel) le code exact de
prod :

- `scripts/repro-roundtrip-vps.mjs` : télécharge le vrai monde de prod avec `downloadWorld()`
  dans un zip, puis renvoie immédiatement ce même zip à `uploadWorld()` pour vérifier la
  fidélité de l'aller-retour.
- `scripts/repro-upload-only-vps.mjs` : ré-envoie seul un zip déjà téléchargé lors d'un run
  précédent, pour itérer plus vite sans repayer le téléchargement (~34s) à chaque essai —
  conçu explicitement pour réutiliser un fichier zip entre plusieurs invocations séparées.

L'étape d'upload a réussi (le code a bien avancé jusqu'à `serverPower('stop')` puis au
remplacement du dossier `world` — confirmé par la séquence `journalctl` ci-dessus), avec un
résultat catastrophiquement incomplet : `level.dat` présent, dossiers de dimension vides,
**zéro fichier de région `.mca`**, `world_gen_settings.dat` absent.

**Le stdout de la session SSH qui a exécuté ces scripts n'a pas été conservé** (pas de
redirection vers un fichier, pas de `tmux`/`screen` retrouvé) : il est donc impossible
d'établir avec une certitude à 100 % l'enchaînement exact micro-seconde par micro-seconde.
Deux mécanismes ont été activement recherchés et évalués :

### 1. Faux-positif de signature dans le flux zip streamé (mécanisme confirmé possible,
   probabilité non nulle mais pas garantie à chaque run)

`uploadWorld()` extrait l'archive via `unzipper.Extract()` (streaming, sans accès aléatoire
au fichier). `archiver` (utilisé par `downloadWorld()`) écrit systématiquement ses entrées en
mode "streamé" (bit `flags & 0x08`, taille compressée inconnue au moment de l'en-tête local),
ce qui force `unzipper` à détecter la fin de chaque entrée en **scannant le flux d'octets**
à la recherche de la signature `PK\x07\x08` (data descriptor) plutôt que de lire une taille
connue à l'avance (`server/node_modules/unzipper/lib/PullStream.js`, fonction `stream()`).

Reproduit localement (hors prod, données synthétiques) : un contenu binaire volumineux et peu
compressible (comme un vrai `.mca`, déjà compressé en interne par le format Anvil) qui
contient par pure coïncidence cette séquence de 4 octets fait tronquer l'entrée exactement à
cet endroit et désynchronise le parseur pour tout ce qui suit dans le flux — confirmé par un
test dédié (`region/r.0.0.mca` de 2 Mo tronqué pile à l'octet où le marqueur avait été
injecté délibérément, fichiers suivants perdus). Calcul de probabilité pour un fichier
aléatoire de N octets : ~N / 2^32 par position — pour les ~943 Mo du monde réel, une chance
de l'ordre de 15 à 25 % qu'une telle collision existe quelque part dans l'archive complète.
Ni négligeable, ni garanti à chaque exécution — cohérent avec un incident qui ne s'est
produit qu'une fois sur ce script alors qu'il avait déjà tourné sans problème avant.

Point important : dans le test de reproduction local, cette collision fait bien lever une
erreur en aval (échec zlib `unexpected end of file`), qui — avec le code de `uploadWorld()`
tel qu'il était avant le correctif de ce jour — aurait dû être interceptée par son `catch`
et empêcher tout remplacement du monde. Ce mécanisme seul n'explique donc pas complètement
comment le remplacement a pu aller jusqu'au bout sans erreur ; il reste une piste sérieuse
mais pas la certitude.

### 2. Verrou anti-concurrence limité à un seul processus (mécanisme confirmé structurellement,
   cohérent avec les logs)

Le verrou `opInProgress` de `world.js` ("jamais deux opérations sur le monde en même temps")
est une simple variable JavaScript **locale au processus Node qui a chargé le module**. Le
service `lanterne-panel.service` (qui tournait déjà et servait de vraies requêtes de
téléchargement pendant la fenêtre 13:38–13:42, voir chronologie) et un script de repro lancé
à la main via SSH (`sudo -u panel node /tmp/repro-*.mjs`) sont **deux processus Node
totalement séparés**, chacun avec sa propre instance de `world.js` et donc son propre
`opInProgress` indépendant — ce verrou n'offre **aucune protection croisée** entre les deux.
La séquence `systemctl start` (14:31:34, sans effet car déjà actif) suivie 11s plus tard d'un
`systemctl stop` (14:31:45, celui qui a précédé le remplacement) est cohérente avec deux
opérations qui se chevauchent : l'une, initiée bien plus tôt (probablement pendant la fenêtre
13:38–13:42 où une opération est restée longtemps "en cours" selon les logs), terminant son
bloc `finally` (qui relance le serveur) très en retard, pendant qu'une autre — le script de
repro — commençait la sienne en parallèle sans le savoir.

### Conclusion sur la cause exacte

Le mécanisme précis qui a produit une extraction incomplète **sans lever d'erreur détectée**
n'a pas pu être établi avec une certitude absolue, faute de journal du script lui-même. Les
deux pistes ci-dessus sont réelles, démontrées structurellement (l'une par test de
reproduction local, l'autre par lecture de code + cohérence des logs), et pas mutuellement
exclusives. Un verdict négatif honnête reste préférable à une fausse certitude ici.

**Ce qui EST certain et démontré**, en revanche, et qui est la vraie faille de fond quel que
soit le déclencheur exact : `uploadWorld()` ne validait la légitimité d'une archive qu'en
vérifiant la **présence** de `level.dat` (`locateWorldRoot()`), jamais sa **complétude**.
N'importe quelle extraction cassée — par ce mécanisme ou un autre, y compris un simple envoi
interrompu par un vrai utilisateur du panel — qui parvient à produire un `level.dat` passe
pour un monde valide et écrase le monde en place. C'est cette faille-là qui a permis à une
extraction tronquée de remplacer le vrai monde de prod, et c'est elle qui a été corrigée.

## Correctif appliqué

`server/src/world.js` — `uploadWorld()` refuse maintenant toute archive manifestement
incomplète **avant** d'arrêter le serveur ou de toucher au monde en place :

- Nouvelle fonction `countMcaFiles()` : compte récursivement les fichiers `.mca` sous un
  dossier monde (toutes dimensions).
- Avant `serverPower('stop')`, si un monde existe déjà (`world/` présent) et qu'il contient
  au moins un `.mca`, on compare la nouvelle archive au monde actuellement en place — jamais
  à un seuil absolu en dur — sur deux critères : nombre de `.mca` et taille totale. Si l'un
  des deux tombe sous 10 % de la valeur actuelle, l'archive est rejetée avec une erreur
  explicite et **rien n'est touché** (pas d'arrêt du serveur, pas de renommage).
- Un tout premier envoi sur un serveur neuf (`world/` absent, ou présent mais sans aucun
  `.mca`) n'est jamais bloqué par ce garde-fou : la comparaison s'efface d'elle-même puisqu'il
  n'y a rien à comparer.
- Testé localement (archive tronquée → refusée, monde en place intact ; archive légitime mais
  réduite à 25 % du monde actuel → acceptée ; serveur neuf sans monde existant → accepté).

La vraie route HTTP utilisée par les vrais utilisateurs (`POST /api/world/upload` dans
`server/src/index.js`, derrière `WorldTransfer.tsx` côté client) appelle directement cette
même fonction `uploadWorld()` — elle hérite donc automatiquement du garde-fou, sans code
dupliqué.

`scripts/repro-roundtrip-vps.mjs` et `scripts/repro-upload-only-vps.mjs` ont reçu :
- un avertissement en tête de fichier expliquant qu'ils ont causé cette panne, pour que
  personne ne les relance à l'aveugle contre la prod ;
- un contrôle explicite d'intégrité du zip (présence de `level.dat`, nombre de `.mca` > 0)
  **avant** l'appel à `uploadWorld()`, en plus du garde-fou côté serveur.

## État actuel

**Résolu.** Le monde de prod a été restauré depuis une sauvegarde saine (l'étape 1 du script
de repro — le téléchargement — avait, elle, produit un zip complet et fidèle : 835 `.mca`,
943 Mo, confirmé). Le serveur `minecraft.service` tourne normalement depuis 14:54:47 le
19/09/2026, aucune perte de données joueur. Vérifié à nouveau à la rédaction de ce document :
`/srv/minecraft/world` contient toujours 835 fichiers `.mca` pour 943 Mo.

## Reste ouvert / pistes non résolues

1. La cause exacte de la troncature (piste 1 vs piste 2 ci-dessus, ou une combinaison des
   deux) n'est pas tranchée — pas bloquant pour la sécurité (le garde-fou couvre les deux),
   mais si le sujet ressort, envisager de faire écrire aux scripts de repro leur propre log
   persistant (`> /tmp/repro.log 2>&1`) pour la prochaine fois.
2. Le verrou `opInProgress` reste limité à un seul processus. Pas corrigé dans le cadre de cet
   incident (le garde-fou de complétude suffit à empêcher la conséquence la plus grave — un
   remplacement silencieux par du vide — mais une vraie course entre deux opérations
   légitimes concurrentes sur le monde reste théoriquement possible). À envisager si ce genre
   de script de repro doit être réutilisé : un verrou fichier (`flock` sur un fichier sous
   `ROOT_REAL`) protégerait aussi contre plusieurs processus.
