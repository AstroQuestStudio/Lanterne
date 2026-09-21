# État final du chantier O6 « iGPU + shaders sans lag » (21/09/2026)

Branche `feat-scale` (worktree `C:\lanterne-wt-scale`) : commits `2c45693`, `4666256`. Jar `lanterne-26.3.241.jar` compilé (BUILD SUCCESSFUL).
Arrêté sur ordre du patron : aucune mesure en file, aucun processus à moi en cours.

## Ce qui est livré (code)
Package `client/scale` + un hook dans `Lanterne.java` (fichier `config/lanterne-echelle-client.toml`).
- `GpuClass` : classe le GPU (intégré / dédié d'entrée de gamme / dédié / inconnu) d'après le nom du pilote.
- `ShaderProfileAuto` + `ProfileBridge` : **propose** le profil léger que le shaderpack déclare lui-même (`profile.LOW`…) et la lentille (rendu à 77 %, jamais moins de 75 % par défaut) sur GPU intégré ou d'entrée de gamme.
- **Règle d'iso-qualité respectée** : mode `PROPOSER` par défaut = un message dans la discussion, une fois ; rien n'est modifié sans accord (`/lanterne echelle profil`, `/lanterne echelle lentille`, `/lanterne echelle refuser`). Modes `OFF` et `AUTO` (choix explicite). Jamais proposé sur GPU dédié courant ni GPU inconnu.
- Le premier commit (`2c45693`) appliquait ces réglages **en silence** : c'était contraire à la règle, corrigé par `4666256`. Le jar 240 mesuré plus bas est celui du premier commit.
- **Non testé en jeu** : le comportement du mode `PROPOSER` (messages, commandes). Seule la compilation est vérifiée.

## Mesures sur l'iGPU AMD Radeon(TM) Graphics (Ryzen 7 5800H), 1080p, distance 12, Complementary, bridage AFK levé
GPU vérifié dans le log à chaque ligne. Sodium 0.9.2 présent.

| Essai | Jar / levier | FPS moyen | Capture | Validité |
|---|---|---|---|---|
| A | 106, base | 9,8 | rendu **cassé** (lames noires/vertes) | scène différente de D : **non comparable** aux autres FPS |
| B | 240, profil + lentille | 16,5 | **invalide** (le terminal recouvrait le jeu) | **invalide** |
| C | 240, profil seul | 23,5 | **invalide** (idem) | **invalide** |
| D | 240, lentille seule | 13,2 | rendu **correct** (jungle, bambou, ombres) | valide comme image ; scène « Bamboo » |
| A2 | 106, base, scène figée | 8,3 | rendu **cassé** | valide (scène figée, même que D) |

**Seule paire comparable : A2 contre D** (même scène « Bamboo », monde restauré avant chaque lancement) : 106 = rendu cassé à 8,3 FPS ; 240 avec lentille = rendu correct à 13,2 FPS.

## Constats
1. **Le rendu des shaders est cassé avec le jar 106 + Sodium sur l'iGPU AMD** : c'est la version des amis. Prouvé par A et A2 (captures dans `C:\scalebench\cap\`).
2. **Il est correct avec le jar 240 + lentille** dans la même scène (D). **La cause n'est pas isolée** : ce peut être la lentille (échange de cible de rendu), ou un changement entre 106 et 240. Je n'ai pas prouvé que la lentille était réellement active pendant D (le fichier de config relu après coup disait `lentille = false`).
3. Aucun gain de FPS n'est établi : les FPS d'une scène cassée ne signifient rien, et B/C sont invalides.
4. La ligne « ms/image » de la Radiographie ne colle pas avec les FPS (6 ms annoncées pour ~9 FPS) : c'est probablement un temps CPU. Et l'histogramme est parfois incohérent avec la moyenne. À ne pas citer comme mesure du GPU.

## Tests prévus et NON faits
- **G** (jar 241 sans aucun levier) et **H** (jar 106 + lentille forcée) : dire si c'est le jar ou la lentille qui répare le rendu. **Non faits.**
- **E2** (jar 106 sans shaders : le terrain vanilla est-il aussi cassé ?) : **lancée puis arrêtée sur ordre, aucun résultat.**
- **F2** (exécutable Java renommé, `C:\igpubench\jdk25\bin\mcrender.exe`) : **non faite.**
- **C2 / D2** avec le jar 241 en scène figée : **non faites.**
- Captures 100/90/77 % avec PSNR/SSIM (l'outil `C:\scalebench\Cmp.java` marche) : **non faites.**
- Leviers sans perte (coût par passe de shader, taille des cibles) : **non commencés.**

## Pour reprendre
Outils dans `C:\scalebench` : `m.ps1` (une mesure sous verrou : monde restauré depuis `saves-pristine`, capture avec fenêtre au premier plan, GPU vérifié, options `-Fresh -NoProfile -NoLens -LensOn -JavaName`), `q3.ps1` / `q4.ps1` (files A2/E2/F2/C2/D2 puis G/H), `Cmp.java`, `jars\` (106, 240, 241). Le monde d'origine est `saves-pristine\Bench` (scène « Bamboo »).
