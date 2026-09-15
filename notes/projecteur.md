# Écran et projecteur — reconnaissance, et la décision qui commande tout le reste

*Établi le 15 septembre 2026. Minecraft 26.2, NeoForge 26.2.0.88, Lanterne en GPL-3.0-only.*

---

## 0. Le fait de départ, et il n'est pas négociable

**Minecraft ne sait pas décoder une vidéo.** Ni H.264, ni H.265, ni VP9, ni AV1. Il n'y a rien dans
LWJGL, rien dans la machine virtuelle, rien dans le jeu.

Ce n'est pas une lacune de ce mod : c'est l'état du monde. **Tous** les mods qui affichent de la
vidéo dans Minecraft — sans exception, y compris les plus connus — appellent du code natif venu
d'ailleurs. La question n'est donc jamais « comment décoder sans dépendance ». Elle est **laquelle,
à quel prix, et sous quelle licence**.

---

## 1. Ce qui a été vérifié, et les hypothèses qui étaient fausses

Le brief posait cinq hypothèses. **Deux sont fausses, une est très en dessous de la vérité.**

### 1.1 « MCEF n'existe que pour des versions anciennes » — **FAUX**

C'est vrai du dépôt qu'on cite habituellement, et faux de la lignée.

| Dépôt | Licence (fichier `LICENSE` lu) | Versions MC | Chargeurs | État |
|---|---|---|---|---|
| [montoyo/MCEF](https://github.com/montoyo/MCEF) | **Aucune licence du wrapper.** Le fichier ne contient que la BSD-3 de JCEF, explicitement bornée « to all file in the org.cef package ». GitHub classe `NOASSERTION` | 1.12.2 | Forge | Mort (2023) |
| [CinemaMod/mcef](https://github.com/CinemaMod/mcef) | **LGPL-2.1** (texte FSF intégral) ; les en-têtes disent « or any later version » → LGPL-2.1-or-later | 1.20.1 → **1.21.4 maximum** | Fabric/Forge/NeoForge | Gelé (dernière publication 2025-01-16) |
| [Keksuccino/Rinku](https://github.com/Keksuccino/Rinku) (ex-`Keksuccino/mcef`) | **LGPL-2.1** verbatim, branche `26.2.0` ; Modrinth : LGPL-2.1-or-later | 1.19.2 → **26.2** | Fabric/Forge/**NeoForge** | **Actif** (2026-08-27) |
| [DimasKama/mcef-modern](https://github.com/DimasKama/mcef-modern) | LGPL-2.1 verbatim | jusqu'à **26.2** | **Fabric seulement** | Actif |

**Rinku couvre 26.2, sur NeoForge, avec Java 25.** L'hypothèse « seulement pour des versions
anciennes » venait de ce que CinemaMod/mcef s'arrête à 1.21.4 — ce qui est exact pour ce dépôt-là et
trompeur pour la question posée.

*(`downthecrop/MCEF` : **n'existe pas**, 404 sur l'API GitHub.)*

### 1.2 « De l'ordre de la centaine de mébioctets par plateforme » — **très en dessous**

Chiffres mesurés (HEAD HTTP + `ISIZE` du trailer gzip, et tailles d'assets de l'API GitHub) :

| | Téléchargé | **Sur le disque du joueur** |
|---|---|---|
| **Rinku** windows x86-64 | 180,2 Mio | **429,1 Mio** |
| **Rinku** linux x86-64 | 175,0 Mio | **558,5 Mio** |
| **Rinku** macOS arm64 | 152,4 Mio | 362,9 Mio |
| CinemaMod windows x86-64 | 118,6 Mio | 268,3 Mio |
| MCEF Modern windows x86-64 | 154,6 Mio | — |

Donc **180 Mio téléchargés et 429 Mio installés** pour le fork qui vise 26.2, et non « une centaine ».
Plus **4 à 6 processus Chromium** qui vivent **hors du tas de la machine virtuelle** : augmenter
`-Xmx` n'y change rien, ils prennent la mémoire système.

*Aucun chiffre de RAM sourcé n'existe* — ni README ni ticket n'en publie. En revanche le
[ticket #63](https://github.com/CinemaMod/mcef/issues/63) (ouvert depuis novembre 2023) documente
des processus `jcef_helper` qui **survivent à la fermeture du jeu** et saturent un cœur ; il n'a reçu
qu'un contournement.

**Piège supplémentaire** : chez CinemaMod les binaires arm64 sont **faux** — le `libcef.so` de
`linux_arm64` est un ELF `i386:x86-64` (tickets [#64](https://github.com/CinemaMod/mcef/issues/64),
[#129](https://github.com/CinemaMod/mcef/issues/129)). Mes mesures le confirment : `windows_amd64`
et `windows_arm64` ne diffèrent que de **273 octets**. Chez Rinku les tailles divergent nettement, ce
qui suggère de vrais binaires arm64.

### 1.3 « VLCJ est GPL-2.0, donc inabsorbable » — **FAUX**

`vlcj` (caprica) est **GPL-3.0-or-later**, absorbable sans difficulté par la GPL-3.0-only de Lanterne.
`libVLC` est LGPL-2.1+ (`COPYING.LIB` vérifié chez VideoLAN), absorbable aussi. Le piège est
ailleurs, et il est sévère : voir §2.

### 1.4 « Un mod vers 1.20.1 fait ça » — oui, et il est **inutilisable ici**

C'est **WaterMedia** (SrRapero720) et son satellite **VideoPlayer** (NGoedix), qui couvrent
1.18 → 1.21.x. Ils sont hors de portée pour trois raisons cumulatives :

- `LICENSE.md` de WaterMedia = **PolyForm Strict License 1.0.0**. Non-OSI : redistribution et
  dérivés **interdits**, non commercial. On ne peut ni le forker, ni le redistribuer, ni s'en
  inspirer ligne à ligne.
- `LICENSE` de VideoPlayer (NGoedix) = littéralement **`All rights reserved.`** (20 octets, fichier
  entier).
- WaterMedia shade `vlcj` sous une **licence commerciale accordée par Caprica Software**, qui — le
  README le dit — **ne se transmet pas aux forks**.

### 1.5 La découverte que le brief n'avait pas anticipée

**[Dream Displays](https://github.com/arnodoelinger/dreamdisplays)** — **LGPL-3.0**, MC **1.21 → 26.2**,
Fabric/**NeoForge**/Quilt/Paper/Folia. C'est aujourd'hui la référence technique du domaine, et elle
ne ressemble à rien de ce que le brief listait :

- bibliothèque native Rust maison `dreamdisplays-lav` (`license = "LGPL-3.0"`) bâtie sur la caisse
  `ffmpeg-next` 9.0.0, **en processus** ;
- décodage matériel réel — `VideoToolbox`, `D3D11VA`/`DXVA2`, `VAAPI`, `CUDA` (énumération
  `HwAccelRequest` dans `native/lav/src/session.rs`) ;
- binaire ffmpeg **téléchargé au démarrage** depuis les builds de BtbN.

Sa licence est **compatible** avec la nôtre. C'est la preuve d'existence que la voie FFmpeg tient en
26.2 — et donc l'argument le plus fort en faveur de la recommandation ci-dessous.

### 1.6 Le reste du relevé, en une table

| Mod | Licence réelle | MC | Mécanisme |
|---|---|---|---|
| CinemaMod | MIT (+ notice BSD-3 concaténée) | **jamais publié**, en dev | CEF + JS injecté ; `ffprobe` (bytedeco) côté serveur |
| Web Displays (montoyo) | « public domain », 204 octets, pas une licence nommée | 1.12.2 | MCEF |
| WebDisplays (fork CinemaMod) | MIT | 1.20 | MCEF |
| FancyVideo-API | **GPL-3.0-or-later** | 1.18–1.19.2 | libVLC + vlcj embarqués — **archivé 2024** |
| VideoPlayer (squi2rel) | **GPL-3.0** | 1.20.1 → **26.2** | VLC **et** MPV, runtimes téléchargés (Fabric) |
| MediaPlayer (hackermdch) | LGPL-3.0 | 1.20–1.21.1 | JNI C++ maison, **Windows x64 seulement** |
| VMC | GPL-3.0-only | 26.1 → 26.2 | sous-processus natif, images en paquets de carte (Paper) |
| WATERFrAMES | **aucun `LICENSE`** → tous droits réservés | 1.18 → 1.21.8 | WaterMedia |

**Non trouvés, dits explicitement** : « Screens » comme mod vidéo, « flickerfree », « vlc4j »
(le binding s'appelle *vlcj*), « MoreVideoPlayer ». `MehVahdJukaar/Vista` → dépôt **404**.

---

## 2. Les licences, et ce que la GPL-3.0-only de Lanterne peut avaler

| Brique | Licence exacte, vérifiée | Absorbable ? |
|---|---|---|
| greffon **bytedeco** (javacpp / javacv) | Apache-2.0 **OU** GPLv2 + exception de chemin de classes, **au choix** | **oui**, sans contrainte |
| binaires **ffmpeg bytedeco** sans suffixe | `cppbuild.sh` porte `--enable-version3` sans `--enable-gpl` → **LGPL v3** | **oui** |
| binaires **ffmpeg bytedeco** `-gpl` | `+ --enable-gpl --enable-libx264 --enable-libx265` → **GPL v3** | **oui** |
| libVLC | LGPL-2.1 (`COPYING.LIB`) | oui |
| vlcj | **GPL-3.0-or-later** | oui |
| MCEF / Rinku | LGPL-2.1-**or-later** | oui — la clause « or later » est ce qui le permet ; une LGPL-2.1-**only** ne le permettrait pas (cf. MemoryLeakFix dans `NOTICE.md`) |
| WaterMedia | **PolyForm Strict 1.0.0** | **non** |
| VideoPlayer (NGoedix), WATERFrAMES | **tous droits réservés** | **non** |

**Le point le plus contre-intuitif du dossier** : les deux variantes des binaires bytedeco
conviennent. Un projet GPL-2.0-only ne pourrait avaler ni l'une ni l'autre (`--enable-version3`
force la v3). Lanterne, en GPL-3.0-only, peut prendre celle qu'il veut.

**Un avertissement à ne pas reprendre** : WaterMedia v3 soutient que grouper FFmpeg GPL-3.0 ne le
relicencie pas, au motif d'une « simple agrégation » (`WaterMedia → JavaCPP → glue JNI → FFmpeg`).
C'est **une position juridique d'auteur, pas un fait établi** : la FSF considère que l'édition de
liens dynamique vers une bibliothèque GPL crée une œuvre dérivée. Ici la question ne se pose pas —
Lanterne est déjà GPL-3.0 — mais l'argument ne doit pas être recopié ailleurs comme une garantie.

---

## 3. Les voies écartées, et pourquoi

**Java pur.** `jcodec` est en BSD-2 et décode H.264 baseline/main/**high** — la lecture de
`H264Decoder.java` (lignes 212-226) est plus généreuse que son README — mais **seulement 8 bits 4:2:0
progressif non-MBAFF**, et le projet annonce lui-même « an order of magnitude slower than native
implementations ». « Performance optimize H.264 decoder » est encore sur la feuille de route
« Future development ». **720p30 temps réel n'est pas tenable.** Pas de décodeur AV1 en Java ; VP8
dans jcodec = images-clés seulement, c'est-à-dire inutilisable pour de la vidéo.

**JavaFX Media.** Écarté, et ce n'est pas la voie « sans dépendance externe » qu'on croit :
plus dans le JDK depuis la 11 (OpenJFX séparé, 50-60 Mio), GPL-2.0 + exception de chemin de classes,
embarque GStreamer 1.26.5, exige `Platform.startup()` — donc un toolkit qui entre en conflit avec le
contexte LWJGL du jeu — et l'extraction d'images passe par `com.sun.media.jfxmedia`, c'est-à-dire des
API internes, avec `--add-exports`, de la réflexion sur un champ privé, et des images qui arrivent
sur le fil média. La voie supportée (`MediaView` + `snapshot()`) est bien trop lente pour 30 images
par seconde.

**JMF.** Mort. Dernière version **2.1.1e, 23 mai 2003**. Licence propriétaire. Aucun codec moderne.

**Extraction de flux YouTube (yt-dlp / NewPipe / InnerTube).** **Écartée, et c'est la seule ligne
juridiquement nette de tout le dossier.** yt-dlp est sous Unlicense — l'outil est libre — mais les
conditions d'utilisation de YouTube interdisent en toutes lettres trois choses que l'extraction fait
toutes les trois : `download`, `automated means (such as robots, botnets or scrapers)`, et
`circumvent, disable, fraudulently engage with… including security-related features`. Le
contournement de BotGuard — ce que fait `rustypipe-botguard` chez WaterMedia v3 — expose de plus à
l'anti-contournement (DMCA §1201, art. L331-5 CPI). **La voie navigateur ne franchit aucune de ces
lignes** : le client se comporte en navigateur, la publicité est servie, aucune mesure technique
n'est contournée. C'est un point réel en faveur de Chromium, et le seul.

---

## 4. Recommandation

> **Les bibliothèques FFmpeg, via les presets `bytedeco` — binaires sans `--enable-gpl`, donc
> LGPL v3 — téléchargés une fois depuis Maven Central plutôt qu'embarqués.**
>
> **Coût : 29,03 Mio par plateforme** (Windows x86-64 ; 25,67 Linux x86-64 ; 19,63 macOS arm64),
> plus 2,5 Mio de greffon. **Aucun processus supplémentaire.** Mémoire vive : une pellicule
> 1280 × 720 en RGBA = **3,5 Mio** de mémoire vidéo par écran actif, plafonnée à 4 écrans
> → **14 Mio**, bornée quelle que soit la taille des murs.
>
> **Chromium embarqué (Rinku) reste branché en second**, derrière la même interface, mais
> **seulement s'il est déjà installé** : jamais téléchargé par nous, jamais dépendance de l'archive.

### Pourquoi celui-ci

1. **180,2 Mio contre 29,03.** Un facteur six au téléchargement, et sur le disque c'est 429 Mio
   contre un jar. Dans un mod dont la raison d'être est d'alléger le jeu, faire démarrer un
   navigateur complet pour afficher une vidéo est une contradiction qu'aucun autre argument ne
   rachète.
2. **Aucun processus hors du tas.** Rinku en lance 4 à 6, dont un qui a l'habitude documentée de
   survivre à la fermeture du jeu.
3. **La licence tombe juste des deux côtés** : greffon Apache-2.0 au choix, binaires LGPL v3.
4. **Le décodage matériel est déjà activé** dans les binaires publiés : D3D11VA et DXVA2 sur
   Windows, VAAPI sur Linux, VideoToolbox sur macOS, CUDA et Vulkan.
5. **Il rend des images brutes**, que l'on téléverse soi-même. C'est exactement ce que
   `content/painting/Mosaic.java` sait déjà faire en 26.2 (`GpuFormat.RGBA8_UNORM`,
   `createCommandEncoder().writeToTexture`). Un navigateur, lui, rend une page dont on ne contrôle
   ni la position de lecture ni l'instant.
6. **Dream Displays le fait déjà en 26.2**, sous LGPL-3.0. La voie est éprouvée, pas spéculative.

### Ce qu'on perd, dit franchement

**Les grands hébergeurs de vidéo.** Aucun ne sert de fichier directement. Sans extraction de flux —
et on l'a écartée pour de bonnes raisons — cette voie joue des fichiers directs et du HLS, pas des
pages. C'est cohérent avec la liste blanche d'administrateur : on autorise des domaines dont on sert
soi-même les médias. Un serveur qui veut YouTube installe Rinku, et le second moteur prend le relais.

### Sur la réintroduction de ffmpeg, puisque le dépôt vient de l'écarter

**Ce n'est pas la même dépendance, et la distinction est de nature.**
`content/disc/Detour.java` appelait le **programme** `ffmpeg`, celui qu'on installe soi-même et qu'on
met dans son `PATH`. Il a été retiré parce qu'il n'est installé chez presque personne : la fonction
ne marchait donc chez presque personne, et elle échouait **après coup**, chez le joueur, au moment du
clic.

Ici rien n'est demandé au joueur. Les binaires sont **versionnés, choisis par nous, apportés avec le
mod**. Le jour où ça marche chez l'un, ça marche chez tous, et la version est celle qu'on a éprouvée.
Dépendre d'un programme absent et embarquer une bibliothèque présente sont deux choses différentes.

---

## 5. Ce qui a été bâti, et ce qui ne l'a pas été

**Bâti et compilé** : les deux blocs, le multibloc, l'horloge partagée, les réglages, la liste
blanche, le consentement, le gradient de performance, les deux rendus, l'interface. Voir
`content/screen/` et `client/screen/`.

**Non bâti** : le décodeur lui-même. Trois manques, nommés :

1. **La dépendance n'est pas déclarée dans `build.gradle`.** Ajouter 29 Mio par plateforme à
   l'archive d'un mod de performance est une décision qui appartient à l'auteur du dépôt, et elle se
   prend en même temps que le choix « embarqué ou téléchargé », qui change la taille publiée d'un
   facteur quatre.
2. **La pompe à images n'est pas écrite.** `AVFormatContext`, flux vidéo, `AVCodecContext`, contexte
   matériel, lecture des paquets sur un fil de fond, conversion RGBA, téléversement. C'est le corps
   de `Engine.Reel`, et il ne s'écrit pas sans pouvoir l'exécuter une fois.
3. **Le son n'a pas de chemin.** Le moteur sonore du jeu joue des ressources, pas des tampons
   arbitraires. Y faire entrer un flux vivant demande le même détour que les disques — un décodeur
   branché dans `SoundBufferLibrary`, cf. `content/disc/Needle.java` — avec une contrainte qu'ils
   n'ont pas : rester en phase avec l'image.

Aucun n'est un obstacle de **conception** : la place existe, la signature est
`client/screen/Engine.java`, et l'horloge partagée qui commande tout cela est écrite et juste.

---

## 6. Deux points d'ingénierie, traités comme tels

### 6.1 Une URL posée par un joueur est allée chercher par tous les clients

**Ce n'est pas une opinion, c'est du réseau.** Le serveur ne va rien chercher ; chaque client ouvre sa
propre connexion. L'hôte distant reçoit donc l'adresse IP de **chaque spectateur**. Un joueur qui
pose un lien vers un serveur qu'il contrôle obtient, en une minute, **la liste des IP de tous ceux
qui passeront devant son écran** — sans faille et sans outil, en lisant son journal d'accès.

**Une seconde surface, qu'on oublie toujours** : le client va chercher depuis **le réseau local du
joueur**. `http://192.168.1.1/` frappe à la porte du routeur du spectateur ; le temps de réponse
suffit à dire si la porte existe.

Trois barrières, dans `content/screen/Sieve.java` :

1. **Le serveur refuse** à la pose — liste blanche **active et vide par défaut**. Rien ne passe tant
   que l'administrateur n'a pas inscrit lui-même les domaines auxquels il accepte d'exposer ses
   joueurs. Réglable par fichier **ou** par `/projection domaine ajouter <domaine>`, parce qu'un
   administrateur sur hébergement partagé ne peut pas toujours éditer un TOML.
2. **Le client refuse** avant d'aller chercher, avec la liste que le serveur lui a annoncée
   (`Mail.Rules`). Redondant sur un serveur honnête — et c'est le but : un client n'expose pas son IP
   sur la seule foi de la partie qu'il ne contrôle pas.
3. **Le joueur refuse tout**, une bonne fois : `client/screen/Consent.java`, **`false` par défaut**.
   Son refus prime sur toute liste. L'interrupteur est dans l'écran de réglages, et l'écran affiche
   « Médias distants refusés » plutôt que de rester noir.

Les adresses de réseau privé sont refusées **même liste blanche désactivée** : un administrateur peut
ouvrir *son* serveur à Internet, il ne peut pas ouvrir le réseau domestique de ses joueurs.

**Ce que ça n'attrape pas, dit plutôt que caché** : un nom de domaine public qui *résout* vers une
adresse privée. L'attraper demanderait de résoudre nous-mêmes puis d'imposer l'adresse résolue, ce
qu'aucune bibliothèque HTTP ordinaire ne permet proprement et qu'un navigateur embarqué ne permet pas
du tout. **La liste blanche est la vraie protection** ; le test d'adresse privée est un garde-fou.

### 6.2 Ce mod est un mod de performance

Un écran qui décode 60 images par seconde est exactement ce qui fait chuter le taux d'images. Huit
écrans le font huit fois. Et le coût se paie **même quand personne ne regarde**, si l'on n'y prend
pas garde — c'est le défaut le plus fréquent du genre, invisible à qui l'écrit parce qu'il teste avec
un seul écran, en face de lui.

Le gradient est celui de `core/Cadence.java`, appliqué à l'image :

- **Rien ne décode si personne ne regarde.** Un écran ne se signale que depuis
  `extractRenderState` — donc seulement s'il a passé le tri de visibilité du jeu. Deux secondes sans
  être vu, la bobine se ferme (`Gaze.FORGET`).
- **La cadence baisse avec la distance.** Pleine cadence sous 16 blocs, puis une **droite** jusqu'à 5
  images par seconde — pas des paliers, pour la raison exacte que `Cadence` documente : un palier est
  trop prudent en haut de tranche et trop brutal en bas.
- **Il y a un plafond.** 4 écrans décodent, les autres gardent leur dernière image — une texture déjà
  envoyée se redessine gratuitement. **Le coût total est borné**, pas proportionnel au nombre
  d'écrans posés.
- **Le serveur paie zéro.** Aucun bloc-entité n'a de ticker. La position se *calcule*, donc elle n'a
  pas à être avancée ; la boucle est un modulo ; la fin de séance ne concerne que l'affichage. Un mur
  de 256 blocs coûte 0 ms par tick.
- **Le rendu paie quatre sommets.** Le maître dessine un rectangle pour tout le mur ; les 255 autres
  blocs ne dessinent rien.

---

## 7. L'horloge partagée — le cœur de la demande

« Sync entre joueurs, timecode similaire. » `content/screen/Clock.java`.

**Le serveur détient quatre nombres ; le client calcule.** `(ancre_tick, pause, ancre_ms, durée)`.
La position est une soustraction. Deux clients qui font la même soustraction sur les mêmes nombres
obtiennent le même résultat : la synchronisation est **exacte par construction**, pas maintenue par
correction.

**Pourquoi `getGameTime()`** : le serveur le diffuse **à tous les clients toutes les 20 ticks**
(`MinecraftServer.tickServer`, `tickCount % 20 == 0` → `forceGameTimeSynchronization` →
`ClientLevel.setTimeFromServer`, vérifié dans les sources de 26.2). Une horloge partagée recalée
chaque seconde existe donc déjà dans le jeu ; en bâtir une seconde serait absurde. L'heure système,
elle, n'est partagée par personne.

**Le défaut, nommé** : le compteur de ticks ralentit quand le serveur ralentit. À 15 TPS la séance
avance aux trois quarts. C'est le bon compromis — tout le monde ralentit *ensemble*, là où une
horloge murale ferait diverger les clients entre eux au moment précis où le serveur souffre.

Les quatre conséquences que le brief demandait de traiter explicitement :

- **La latence.** Le paquet de synchronisation met un aller simple à arriver ; un joueur à 200 ms
  regarde 100 ms dans le passé. Le jeu connaît déjà cette latence et la diffuse dans la liste des
  joueurs (`PlayerInfo.getLatency()`, aller-retour) ; on en compense **la moitié**, **bornée à
  250 ms** — au-delà, la correction fait plus de dégâts que le défaut.
- **La dérive.** Trois régimes, dans `Clock.correct`, une **fonction pure** qu'on peut relire et
  éprouver sans lancer le jeu. Sous 80 ms : rien, c'est le bruit de la mesure. De 80 ms à 2 s :
  **rattrapage progressif**, vitesse proportionnelle à l'écart, plafonnée à ±5 % — inaudible,
  invisible, et qui s'apaise au lieu de dépasser. Au-delà de 2 s : **saut sec**. Les deux situations
  ne sont pas de degré mais de nature : sous 2 s c'est une machine qui souffle et qui recollera ;
  au-delà c'est un joueur qui arrive en cours de séance.
- **Un joueur qui entre en cours de lecture.** Rien de spécial à faire : il reçoit les quatre nombres
  avec le chunk, fait la soustraction, et tombe sur l'écart « ≥ 2 s » qui déclenche le saut.
- **Un joueur qui sort de portée et revient.** Sa bobine s'est fermée après deux secondes ; elle
  rouvre et saute. Il n'a jamais payé le décodage pendant son absence.

**Le débit** : sur un serveur où dix écrans jouent pour trente joueurs, le protocole échange
**zéro paquet par seconde**. Un protocole qui diffuserait la position deux fois par seconde en
enverrait six cents — et serait *moins* précis, chaque paquet arrivant en retard d'un temps que
personne ne mesure.

Un seul pli monte sans qu'on le lui demande : `Mail.Measure`, la durée lue par un client. C'est le
seul endroit où le serveur croit un client, et il ne le croit **qu'une fois, qu'en l'absence de
valeur connue, qu'entre 500 ms et 12 h, et qu'à portée de bras**.

---

## 8. Ce qui n'a pas été vu tourner

**`runClient` n'a pas été lancé — consigne explicite. Aucune image n'a été vue.** Le dépôt compile
(`./gradlew build`). Ce qui n'est donc **pas** vérifié visuellement :

- l'orientation des images (une image en miroir ne se voit que le jour où un texte apparaît dedans) ;
- la géométrie du cône du projecteur ;
- le placement de la ligne d'ardoise sur le mur ;
- l'absence de clignotement entre l'image et le bloc (`LIP`) ;
- l'écran de réglages en échelle d'interface automatique.

Aucun mixin n'a été posé : `tools/verifie_mixins.py` est sans objet pour ce chantier.

**L'animation explicative à la Create n'a pas été écrite.** `client/ponder/Guide.java` construit sa
liste de chapitres depuis des méthodes privées ; y ajouter un chapitre demande d'éditer ce fichier,
qui appartient à un autre chantier en cours. C'est le dernier point du brief, explicitement « quand
fini », et il est laissé entier — trois scènes à écrire : bâtir un mur, poser un projecteur, régler
une source.
