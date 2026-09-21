# Objectifs de FPS — Lanterne (fixés le 21/09/2026)

Demande du patron : **boost massif, minimum 300 FPS stable**. Ce fichier fixe les cibles et le protocole pour qu'elles soient vérifiables.

## Protocole de mesure (identique pour toute mesure citée)
- Machine de banc : RTX 3080 Laptop, 1280x720, fenêtre focalisée, Java 25.
- Même monde solo, même position et même caméra, distance de rendu 12, vsync off, DH coupé (sauf ligne dédiée).
- Durée ≥ 60 s après 30 s de chauffe ; on note FPS moyen, 1 % low, temps par image.
- Toute optimisation = mesure AVANT/APRÈS dans ces conditions, sinon elle n'est pas comptée.

## Point de départ mesuré (agent perf, 21/09)
| Configuration | FPS moyen | ms/image |
|---|---|---|
| Lanterne 26.3.106 + Sodium, sans shaders | 168,8 | 4,2 |
| idem, ComplementaryReimagined actif | 53,6 | 13,4 |
| Lanterne 26.3.38, sans shaders | 151,3 | 4,65 |

Observation à expliquer : les amis rapportent 800 → 300/400 FPS sans shaders et ~90 au lieu de 150+ avec shaders (autres machines/scènes : à reproduire).

## Objectifs
| # | Cible | Critère de réussite |
|---|---|---|
| O0 | Expliquer la baisse « 800 → 300-400 » | cause prouvée par bisect (versions, Lanterne on/off, contournement NVIDIA) |
| O1 | Sans shaders : **≥ 300 FPS moyens** sur le banc, 1 % low ≥ 200 | tableau avant/après |
| O2 | Avec shaders (Complementary) : **≥ 150 FPS moyens** (retrouver le niveau d'avant), stretch 300 | tableau avant/après |
| O3 | Stabilité : 1 % low ≥ 60 % de la moyenne, pas de creux > 50 ms | histogramme FPS Lanterne |
| O4 | Sodium retiré du pack SANS perte de O1/O2/O3 | client sans Sodium, mêmes mesures |

## Leviers déjà identifiés (ordre de priorité, voir NOTES-TECHNIQUES-RENDU-TERRAIN.md)
N0 contournement NVIDIA (soumission threadée du pilote) → N8/N9 cache de liste et de shadow map → N10 distance d'ombre adaptative → N6 maillage → N3 occlusion → N4/N5 format compact → N7 translucides.

## Honnêteté
- Les cibles dépendent du matériel : un 300 FPS sur le banc ne garantit pas 300 sur chaque PC des amis.
- Tant qu'une cible n'est pas mesurée et atteinte, elle est notée « non atteinte » ici, avec le chiffre réel.
- Rien n'est déployé chez les joueurs avant une amélioration prouvée et un test de mise à jour par la Mèche.

## Suivi (à remplir par les agents, une ligne par mesure)
| Date/heure | Version | Configuration | FPS moy | 1 % low | ms | Remarque |
|---|---|---|---|---|---|---|

## Protocole de mesure EXCLUSIVE (ajout 21/09 11h40) — obligatoire
Plusieurs agents lancent des clients sur la MÊME machine : un FPS mesuré pendant qu'un autre client tourne est faux (CPU/GPU partagés).
1. Une mesure de FPS ne se fait que quand elle détient le verrou : `mkdir C:\bench-lock` (atomique ; s'il existe déjà, attendre 30 s et réessayer ; le verrou contient un fichier `owner.txt` avec le nom de l'agent et l'heure). Libérer avec `rm -r C:\bench-lock` dès la mesure finie ; un verrou de plus de 15 min est périmé.
2. Pendant une mesure, les AUTRES agents ne lancent pas de client (ou le lancent en priorité basse : `start /low` / PowerShell PriorityClass=BelowNormal) et ne compilent pas avec Gradle sans nécessité.
3. Tout client de test fonctionnel (crash, capture) qui n'est pas une mesure se lance en priorité BelowNormal.
4. Le tableau « Suivi » indique si la mesure a été faite sous verrou (oui/non) ; une ligne « non » n'est pas une preuve.

## Petites configs et GPU intégré (ajout 21/09 11h40)
Cible O5 : le jeu doit aussi tourner nettement mieux sur PC sans GPU dédié. Banc iGPU : le Ryzen 7 de cette machine (AMD Radeon Graphics) — forcer via `HKCU\Software\Microsoft\DirectX\UserGpuPreferences` (valeur = chemin du java.exe, données `GpuPreference=1;` = économie d'énergie/iGPU) ; vérifier dans le log « Backend actif : OpenGL sur AMD Radeon… ».
- O5a : mesure de base iGPU (sans shaders, distance 8 et 12), puis gain après régulateur adaptatif.
- O5b : régulateur adaptatif de qualité (distance de rendu, distance d'ombre, particules, entités, LOD) qui tient une cible de FPS choisie (défaut 60), avec profil « petite config » choisi automatiquement au premier lancement.

## Protocole révisé (21/09, coordinateur) — remplace la règle « aucun Gradle/client pendant le verrou »
Le verrou enchaîné bloquait tous les agents. Nouvelle règle, équitable :
1. **Un verrou = UNE mesure (≤ 5 min).** Le détenteur le LIBÈRE après chaque mesure, puis se remet en file (attendre ≥ 60 s avant de le reprendre) : les autres agents peuvent ainsi mesurer à leur tour. Interdit d'enchaîner plusieurs mesures sous le même verrou.
2. **Compilation Gradle et tests fonctionnels (crash, capture, lecture de log) sont AUTORISÉS à tout moment**, en priorité BelowNormal (Gradle : `-Dorg.gradle.workers.max=2`, lancé caché) ; ils ne comptent pas comme mesures.
3. Une mesure exige toujours : verrou détenu + GPU = celui voulu (RTX pour les bancs classiques, cité dans le tableau) + « sous verrou : oui ». Une mesure faite pendant qu'un autre agent compile est répétée si l'écart avec sa jumelle dépasse 10 %.
4. Fenêtre de mesure > 5 min ou lot de mesures : à annoncer au coordinateur d'abord.

## RÈGLE DE MESURE n°0 (découverte agent perf, 21/09 12h50) — inactivité = FPS faux
Minecraft 26.3 : option `inactivityFpsLimit` (options.txt, défaut `"afk"`) : après ~60 s sans clavier/souris le jeu se bride à ~30 FPS. Un banc sans entrée mesure donc 20-40 FPS. Preuve : Radiographie de Lanterne 26.3.100 (banc propre, RTX) : 35 % du temps du fil de rendu dans FramerateLimiter.limitDisplayFPS ; ms/image moyen 3,59 (~280 FPS quand non bridé).
- OBLIGATOIRE dans l'options.txt de tout client de MESURE : `inactivityFpsLimit:"minimized"` (ou envoyer des entrées régulières).
- Toute mesure faite sans cette option et durant > 60 s sans entrée est INVALIDE et à refaire ; les chiffres antérieurs (168,8 / 53,6 / 145,2 / 151,3 / 45,4…) sont suspects.
- Le tableau « Suivi » indique la valeur d'`inactivityFpsLimit` de chaque mesure.

## Objectif O6 (patron, 21/09 13h) — iGPU + shaders SANS lag, quel que soit le PC
- Cible : sur l'iGPU du banc (AMD Radeon Graphics, Ryzen 7) avec ComplementaryReimagined actif : **≥ 60 FPS moyens et 1 % low ≥ 40**, sans à-coups (pas de creux > 50 ms), en 1080p affiché. « Sans lag » = ressenti stable, pas seulement une moyenne.
- Leviers innovants à prototyper et MESURER (règle n°0 : jeu non bridé, GPU cité, sous verrou) : (1) échelle de résolution dynamique (rendu interne réduit + agrandissement/netteté type FSR), pilotée par le régulateur adaptatif ; (2) profil automatique d'options du shaderpack selon la classe de GPU (qualité d'ombre, distance d'ombre, réflexions, AO, nuages…), écrit dans le fichier d'options du pack ; (3) ombres en cache/alternées (W2) ; (4) régulateur adaptatif (feat-regul) qui tient la cible ; (5) DH réduit sous shaders sur petite config.
- Tout PC : le profil se choisit au premier lancement d'après le GPU détecté (log [BILAN] : GPU, VRAM, intégré/dédié).

## Suivi O6 (agent iGPU, 21/09) — détails et validité dans ETAT-FINAL-O6.md
GPU = AMD Radeon(TM) Graphics (iGPU Ryzen 7 5800H) vérifié dans le log ; 1080p, distance 12, Complementary, `inactivityFpsLimit:"minimized"` ; toutes sous verrou.
| Essai | Jar / levier | FPS moy | Rendu (capture lue) | Validité |
|---|---|---|---|---|
| A | 106 base | 9,8 | CASSÉ | scène différente : non comparable |
| B | 240 profil+lentille | 16,5 | capture invalide | invalide |
| C | 240 profil seul | 23,5 | capture invalide | invalide |
| D | 240 lentille seule | 13,2 | correct | valide (scène Bamboo) |
| A2 | 106 base, scène figée | 8,3 | CASSÉ | valide |
Seule paire comparable : A2 (106 cassé, 8,3) contre D (240+lentille, correct, 13,2). Cause non isolée (jar ou lentille) ; tests G/H/E2/F2 non faits.
