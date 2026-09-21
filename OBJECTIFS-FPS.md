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
