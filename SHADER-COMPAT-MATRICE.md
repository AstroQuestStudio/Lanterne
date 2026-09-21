# Matrice de compatibilité de l'optimiseur de shaders (hors jeu)

Huit packs de cinq familles, téléchargés **pour test local uniquement** dans `C:\shaderwork\packs\` (aucune redistribution).
Outils : `tools/shaderopt/ShaderTool.java`, `SpecTool.java`, `CheckTool.java` ; classes du mod : `client/shaderopt/`.
Validateur : `glslang` 16.6.0. Monde `world0`, options par défaut du pack (profil MEDIUM pour Complementary).
Exécution : `java -cp <classes> SpecTool check|spec|attach <dossier shaders> <profil|-> <glslang.exe>`.

## Cartographie

| Pack | Famille | Licence (Modrinth) | Fichiers | Taille | Passes | Composites / deferred | Options | Profils | Uniformes perso | Images / SSBO |
|---|---|---|---|---|---|---|---|---|---|---|
| Complementary Reimagined r5.9.3 | Complementary | Complementary License 1.7 | 426 | 1,2 Mo | 37 | 7 / 1 | 232 | 8 | 30 | 7 / 4 |
| Complementary Unbound r5.9.3 | Complementary | Complementary License 1.7 | 426 | 1,2 Mo | 37 | 7 / 1 | 232 | 8 | 30 | 7 / 4 |
| Rethinking Voxels r0.1-beta9 | Complementary (dérivé) | Complementary Agreement | 430 | 8,6 Mo | 43 | 7 / 2 | 447 | 5 | 23 | 19 / 2 |
| BSL 10.1.7 | BSL / Chocapic | Tous droits réservés | 292 | 1,4 Mo | 32 | 8 / 2 | 283 | 5 | 41 | 3 / 0 |
| Bliss 2.1.2 | BSL / Chocapic (dérivé) | Tous droits réservés | 344 | 3,2 Mo | 50 | 14 / 3 | 324 | 2 | 76 | 8 / 0 |
| Photon 1.3b | Photon | licence propre (GitHub) | 530 | 4,7 Mo | 59 | 18 / 6 | 269 | 4 | 83 | 6 / 0 |
| Solas 3.7b | Solas | Tous droits réservés | 385 | 1,7 Mo | 29 | 6 / 2 | 296 | 4 | 29 | 2 / 0 |
| MakeUp Ultra Fast 9.5e | MakeUp | **LGPL-3.0** | 342 | 0,7 Mo | 27 | 3 / 1 | 74 | 8 | 32 | 0 / 0 |

Les comptes de programmes (`fsh/vsh/gsh/csh`) sont dans les sorties `SpecTool`/`ShaderTool scan`. Les packs de la famille
Complementary partagent le même code de base ; la matrice de test couvre donc quatre architectures : Complementary, BSL/Chocapic, Photon, Solas/MakeUp.

## Compilation de base (ce qu'on peut valider hors jeu)

Un programme est « valide » si `glslang` accepte le GLSL aplati de l'ORIGINAL (dans son `#version` d'origine, ou en 330 compatibilité).
`glslang` est plus strict que les pilotes et n'applique pas les corrections d'Iris ; un taux inférieur à 100 % ne signifie pas que le pack est cassé.
Les programmes `clrwl_*` (dépendance au mod Colorwheel) sont exclus.

| Pack | Programmes testés | Valides à l'origine | Raison principale des échecs |
|---|---|---|---|
| Complementary Reimagined | 65 | 51 (78 %) | surcharges de fonctions / vsh dépendant d'Iris |
| Rethinking Voxels | 78 | 67 (86 %) | idem |
| BSL | 63 | 55 (87 %) | |
| Bliss | 84 | 22 (26 %) | `flat varying` et déclarations attendues d'Iris |
| Photon | 115 | 75 (65 %) | surcharge `max0` (glslang plus strict que le pilote) |
| Solas | 51 | 47 (92 %) | |
| MakeUp Ultra Fast | 104 | 100 (96 %) | |

## Spécialisation sans perte : résultats

`SpecTool spec` : les 8 uniforms d'état neutre deviennent des constantes, les `if` décidables sont repliés, les fonctions mortes retirées.
« Régressions » = programmes valides avant et invalides après (l'optimiseur les aurait écartés par repli).

| Pack | Programmes avec uniforms d'état | Valides avant → après | Régressions | Lignes | Score statique | Plein écran |
|---|---|---|---|---|---|---|
| Complementary Reimagined | 32 | 25 → 25 | **0** | −22 % | −0,9 % | −0,8 % |
| Complementary Unbound | 32 | 25 → 25 | **0** | −22 % | −0,8 % | −0,7 % |
| Rethinking Voxels | 32 | 28 → 28 | **0** | −31 % | −0,5 % | −0,8 % |
| BSL | 19 | 18 → 18 | **0** | −33 % | −0,6 % | −2,4 % |
| Bliss | 23 | 0 → 0 | 0 (non validable) | −50 % | −4,5 % | −4,7 % |
| Photon | 21 | 2 → 2 | 0 (2 validables) | −30 % | −0,1 % | −0,1 % |
| Solas | 17 | 16 → 16 | **0** | −7 % | −1,7 % | −0,6 % |
| MakeUp Ultra Fast | 30 | 28 → 28 | **0** | −37 % | −0,6 % | −3,0 % |

Historique du test : avant le retrait des fonctions mortes, BSL avait 5 régressions et Solas 3 (indice de tableau constant négatif dans du code devenu mort).
Corrigé, puis 0 régression sur les 8 packs.

**Taux de succès de la transformation** (programmes valides à l'origine qui le restent) : **100 %** sur les 6 packs validables
(Complementary ×2, Rethinking Voxels, BSL, Solas, MakeUp). Sur Bliss et Photon, où l'original ne se valide pas ou presque pas dans `glslang`, la vérification
repose sur le repli à l'exécution (compilation réelle du pilote) : **non prouvé hors jeu**.

## Écritures de pièces jointes jamais relues

| Pack | Nombre | Détail |
|---|---|---|
| Complementary Reimagined (MEDIUM) | 2 | `colortex1`, `colortex7` |
| BSL | 1 | `colortex6` (DH) |
| MakeUp Ultra Fast | 1 | `colortex6` |
| Photon | 1 | `colortex2` |
| Bliss | 1 | `colortex12` |
| Solas, Rethinking Voxels | 0 | |

## Limites de cette matrice

- **Le score est statique** : ordres de grandeur relatifs, pas des millisecondes. Il surestime les passes à tuiles ou à gardes de zone (bloom de Complementary).
- **Le validateur est strict** : « non validable » ne veut pas dire « cassé ». Bliss et Photon demandent une validation en jeu.
- **Un seul état neutre** (au sec, ciel dégagé). Les états jour/nuit, souterrain, phase de lune ne sont pas spécialisés ici.
- **Aucune mesure GPU** dans ce fichier : voir §6 de `SHADER-ANALYSE-COMPLEMENTARY.md` (jobs A/B déposés dans le banc central).
- **Bilan des packs testés en jeu** : aucun pour l'instant ; la matrice ne dit rien sur le rendu réel des variantes.
