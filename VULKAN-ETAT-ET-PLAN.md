# Vulkan — état réel, causes, plan (21/09/2026)

Demande du patron : « quand je lance en Vulkan ça me met ça » + Iris/shaders + perfs en Vulkan ET OpenGL.
Tests faits avec un vrai client jetable (`C:\vkbench`, Lanterne 26.3.140, RTX 3080 Laptop + iGPU AMD, JDK 25).

## Comment le backend est choisi
- `options.txt` : `preferredGraphicsBackend:"vulkan"` (ou `"opengl"` / `"default"`). Lanterne pose `"vulkan"` au premier lancement (`client/Reveil.java`).
- `"default"` essaie TOUJOURS OpenGL d'abord ; seul `"vulkan"` essaie Vulkan.
- Garde-fou vanilla : si `startedCleanly:false` (lancement précédent tué/planté), Minecraft repasse en `DEFAULT` pour la session, donc en OpenGL, et **sur l'iGPU AMD** si c'est lui que le pilote présente. Une matrice de tests qui tue le client fausse donc les essais suivants : remettre `startedCleanly:true` avant chaque essai.
- Lanterne journalise le résultat (`[LANTERNE][GPU] Vulkan actif et confirmé…` ou `Backend actif…`) dans `client/Vigie.java`.

## Ce qui est prouvé

| Jeu de mods (Vulkan demandé) | Résultat | Preuve |
|---|---|---|
| Lanterne seul (Sodium absent, Iris inerte) | **Démarre en Vulkan sur la RTX 3080**, fenêtre répond | `Vulkan actif et confirmé : Vulkan sur NVIDIA GeForce RTX 3080 Laptop GPU`, Responding=True |
| Lanterne + Distant Horizons 3.3.0 (avec ou sans Sodium) | **Jeu FIGÉ au chargement** | voir ci-dessous |
| Lanterne + Sodium seul | **Non concluant** : processus disparu à 11:15:13 en plein chargement, sans crash-report ni erreur (probablement tué de l'extérieur pendant les mesures d'autres agents). À refaire. | `so_B.log` s'arrête sur le chargement de NeoForge |
| Shaders en Vulkan | **Non testable** : Iris est inerte sans Sodium ; avec Sodium, cas non concluant ci-dessus. Le blocage connu reste le fragment shaderc de 99 760 caractères (voir RECHERCHE-VULKAN-SANS-SODIUM.md) | — |

### La cause du « ça me met ça » (prouvée par thread dump)
1. Lanterne déclare un mod virtuel `iris` (Iris intégré).
2. Distant Horizons 3.3.0 voit `iris`, et impose son moteur OpenGL : `Changing Distant Horizons' rendering engine to [OPEN_GL] to allow for Iris rendering`.
3. Minecraft tourne en Vulkan, donc DH affiche une **boîte de dialogue Swing native** : « The Distant Horizons rendering engine [OpenGL] cannot be used since its API doesn't match what Minecraft is currently set to use [VULKAN]. Please either change Minecraft's rendering API or Distant Horizons'. »
4. Le thread `DH-PostClientInit` attend un clic sur cette boîte (`JOptionPane.showConfirmDialog`), pendant que le chargement des mods attend ce thread (`AbstractModInitializer.postClientInit` → `CompletableFuture.join`). Le jeu reste figé, `Responding=False` (fichier de preuve : `C:\vkbench\dump-dh-vulkan-gel.txt`). La boîte est probablement cachée derrière la fenêtre du jeu.
5. Reproduit aussi SANS Sodium (jeu D) : la cause est DH + mod `iris` + Vulkan, pas Sodium.

DH possède un moteur `BLAZE_3D` (backend Mojang, donc Vulkan), mais son code force `OPEN_GL` dès qu'un accesseur Iris existe.

## Correctif livré (à valider en jeu)
- Commit `5e3ea47` (`client/Reveil.java`) : avant que Minecraft lise `options.txt`, si un jar `distanthorizons` est dans `mods/`, Lanterne repasse `preferredGraphicsBackend` en `"opengl"` (ou pose `"opengl"` au premier lancement) et l'écrit dans le journal `[RÉVEIL]`. `-Dlanterne.vulkan.force=true` garde Vulkan à ses risques.
- Jar : `lanterne-26.3.150.jar` (build OK). **Non testé en jeu** : un verrou de mesure (`C:\bench-lock`) m'a interdit de relancer un client.

## Plan
1. **Valider** le repli (client avec DH + `"vulkan"` → doit démarrer en OpenGL avec le message).
2. **Sodium + Vulkan** : refaire l'essai B sans interférence (les mesures FPS des autres agents ont pu tuer le client).
3. **Vulkan + DH** : deux voies — (a) faire passer DH sur `BLAZE_3D` quand Minecraft est en Vulkan (`Config…Experimental.renderingEngine`), en évitant que le mod `iris` de Lanterne déclenche le forçage OpenGL ; (b) sinon garder le repli OpenGL.
4. **Shaders Vulkan** : dépend du chantier « Iris sans Sodium » et du contournement du bug natif shaderc (fragment de 99 760 caractères) — voir RECHERCHE-VULKAN-SANS-SODIUM.md.
5. **Perf** : comparer Vulkan et OpenGL avec le protocole de `OBJECTIFS-FPS.md` (banc verrouillé, DH coupé).

## À savoir
- Le client peut aussi retomber sur OpenGL/iGPU AMD sans erreur si `startedCleanly:false`. Le message `[LANTERNE][GPU]` l'indique.
- Le jeu 26.3 journalise `loader_create_instance_chain: Failed to find 'vkGetInstanceProcAddr' in layer …Overwolf…ow-graphics-vulkan.dll` : une couche Vulkan d'Overwolf est cassée sur cette machine. Elle n'empêche pas le démarrage, mais peut perturber d'autres PC avec Overwolf.
