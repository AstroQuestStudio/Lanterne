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

## PORTE DE SORTIE DE SODIUM (patron, 21/09) — condition pour le retirer du pack
Sodium ne quitte le pack (ligne `sodium-neoforge-*` dans RETIRER.txt, retrait du manifeste) QUE si TOUTES ces conditions sont mesurées (règle n°0, RTX + iGPU, sous verrou, scène de banc au coucher de soleil figé) :
1. Sans Sodium (notre moteur) ≥ avec Sodium sur : sans shaders, avec shaders (Complementary), petite config iGPU. Cible : nettement plus (objectifs O1/O2/O6).
2. Le client sans Sodium charge le monde, affiche le terrain et les shaders sans artefacts (captures lues), y compris avec DH.
3. 1 % low ≥ 60 % de la moyenne ; aucune régression de stabilité (creux > 50 ms).
4. Test de mise à jour réel : ancien client (avec Sodium) → nouveau serveur : la Mèche retire Sodium, le client relancé démarre et joue.
Si une seule condition échoue : Sodium reste, le chantier continue.

## Protocole v3 (21/09 13h30, coordinateur) — REMPLACE le protocole révisé précédent
Preuve de pollution (agent W2) : le client d'un autre agent en priorité BelowNormal utilisait 59 % puis 21 % du moteur 3D de la RTX pendant une mesure sous verrou. La priorité basse ne protège pas le GPU.
1. **TOUT lancement de client Minecraft (mesure OU test fonctionnel, même caché, même en priorité basse) exige le verrou `C:\bench-lock`.** Une seule instance de jeu à la fois sur la machine. Le verrou se prend pour UN lancement (≤ 5 min), se libère à la fermeture du client, puis on attend ≥ 60 s avant de le reprendre.
2. Un client qui reste ouvert après usage est un incident : le fermer immédiatement (PID lancé seulement).
3. Gradle/compilation restent autorisés sans verrou (CPU seulement, priorité BelowNormal, workers ≤ 2) ; aucune mesure de FPS n'est lancée pendant qu'une compilation est en cours.
4. Dès que le **banc central** (C:\bench, file d'attente) est validé par le coordinateur, plus aucun agent ne lance de client lui-même : il dépose un job et lit le résultat.

## RÈGLE D'ISO-QUALITÉ (patron, 21/09 14h) — hiérarchie des leviers d'optimisation
Danger : DLSS/FSR/échelle de résolution peuvent effacer des détails fins (herbe, feuillage, textes, arêtes). Donc, ordre STRICT des leviers :
1. **SANS PERTE (par défaut, toujours actifs)** : élimination d'allocations (JourneyMap 44 %…), contournement NVIDIA, cache/culling des ombres à résultat identique, optimiseur de shaders à ISO-RENDU prouvé, JVM/GC, culling GPU, batching, mise à jour paresseuse. Aucun changement visible.
2. **PERTE QUASI NULLE (actifs après preuve)** : réductions dont l'écart d'image est sous seuil (PSNR/SSIM documenté) sur des scènes de référence riches en détails fins (jungle/feuillage, herbe, texte, arêtes au soleil bas).
3. **PERTE VISIBLE = OPTIONNELLE, JAMAIS PAR DÉFAUT sur GPU capable** : échelle de résolution, upscaling (FSR/DLSS-like), baisse de distance/ombres/options du pack. Réservés aux profils « petite config/iGPU », proposés et expliqués à l'utilisateur, désactivables en un clic, jamais activés en silence sur une RTX.
4. Toute optimisation de niveau 2 ou 3 est livrée avec : captures avant/après sur les scènes de référence, métrique PSNR/SSIM, mention explicite « perte visible : oui/non ».
5. Le régulateur adaptatif essaie les niveaux dans cet ordre (1 → 2 → 3) et n'atteint le niveau 3 qu'en dernier recours.

## Suivi des mesures (21/09 fin de journée)
Le tableau « Suivi » alimenté automatiquement a été retiré : un ajout automatique ré-encodait le fichier à chaque écriture (18 Mo pour 105 lignes). Les mesures VALIDES sont consignées dans **PERF-SHADERS-AUDIT.md** et dans les résultats du banc central (C:\bench\results\*.txt / .json, avec GPU, scène restaurée, non bridé).
Mesures valides connues (RTX 3080, banc central, scène restaurée, heure 12500, non bridé, autres clients = 0) : jar 108 + Sodium + pack complet, DH coupé : **sans shaders 338,7 FPS** (94 % du temps > 120), **avec Complementary 100,2 FPS** (capture lue : rendu correct). Toutes les mesures antérieures sont « scène dérivante » ou bridées AFK : non comparables.
Règle pour tout script d'ajout automatique : ouvrir le fichier en UTF-8 SANS BOM, ne réécrire que la ligne ajoutée (append), jamais relire/ré-encoder tout le fichier.
