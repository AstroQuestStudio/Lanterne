# Temurin 26 vs 25 sur la VM Oracle — 18 septembre 2026

Question : un JDK plus récent (26, disponible via `apt`) apporte-t-il un gain réel
par rapport au 25 (LTS) déjà en place, dont ZGC générationnel a été mesuré et
déployé la veille (pauses max 0,016 ms) ?

## Méthode

Charge reproductible : plateforme de sable 40×5×40 sous le sol + 300 vaches
summonées par RCON (hors budget par tick de Pregen, pour générer une vraie
pression d'allocation/tick indépendante du throttle). Même charge rejouée à
l'identique sous chaque JDK, fenêtre de mesure 75 s, lecture des vraies lignes
`Pause` de `-Xlog:gc*` (pas les lignes `Concurrent Mark`/cycle, qui ne sont pas
des pauses STW malgré des durées à 3 chiffres en ms — piège du format de log ZGC,
vérifié avant de tirer une conclusion).

## Résultat

| | Temurin 25 (LTS) | Temurin 26 |
|---|---:|---:|
| Pause STW max mesurée | 0,015 ms | 0,016 ms |
| Démarrage serveur (service actif → `Done`) | ~18 s | ~18 s |
| Version | `25.0.4.1 LTS` | `26.0.2.1` (pas LTS) |

Aucune différence mesurable entre les deux versions sur cette charge. Le bruit
de mesure (0,015 vs 0,016 ms) est du même ordre que la précision de l'horloge
utilisée par ZGC pour ce log, pas un vrai écart.

## Verdict

**Reste sur Temurin 25.** Aucun gain net mesuré, et 26 n'est pas LTS (chaîne de
version confirmée par la sortie même de `java -version`, pas supposée) — l'installer
en production imposerait une nouvelle migration dans quelques mois pour rien.

## État final

- Les deux JDK sont installés côte à côte (`/usr/lib/jvm/temurin-25-jdk-arm64`,
  `/usr/lib/jvm/temurin-26-jdk-arm64`), rien à réinstaller si le besoin revient.
- `update-alternatives` repointé explicitement sur le 25 (`--set java`, mode
  manuel — ne re-basculera plus automatiquement tout seul si un futur JDK plus
  récent est installé).
- Service `minecraft.service` redémarré et vérifié tournant sous
  `/usr/lib/jvm/temurin-25-jdk-arm64/bin/java` (confirmé par `readlink` sur le
  process réel, pas supposé).
- Entités de test (vaches) nettoyées. Une petite plateforme de sable créée pour
  la charge de test (sous le sol, coordonnées relatives à la position du dernier
  utilisateur RCON connecté, autour de Y -55/-60) n'a pas pu être nettoyée par
  manque de chunk chargé au moment du retrait — négligeable (hors-sol, aucun
  impact gameplay), à netttoyer en passant si quelqu'un tombe dessus un jour.
