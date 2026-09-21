# Banc central Lanterne : un seul lanceur, une file d'attente

Proposition du patron : « toi seul lances les Minecraft ; à 3 en parallèle, 2 sont forcément en arrière-plan ».
Un **runner unique** prend UN job à la fois, lance UN client (`javaw.exe`, sans console), le met au premier
plan, mesure ou teste, le ferme, et écrit un résultat lisible. Les agents ne lancent plus de client de
mesure eux-mêmes : ils **déposent un job** et **lisent le résultat**.

Le banc vit dans `C:\bench\` (hors dépôt, ~150 Mo : modèle de client, mondes, jars). Ce dossier contient
les scripts (copie de référence dans `tools/banc/`).

## Déposer un job
```
powershell -NoProfile -File C:\bench\submit.ps1 -Name <nom> -Jar <chemin du lanterne.jar> [options]
```
Rend l'identifiant du job (`AAAAMMJJ-HHMMSS-<nom>`), et copie le jar dans le banc (pas besoin de le garder).

| Option | Sens (défaut) |
|---|---|
| `-Mode bench\|functional` | `bench` = mesure de FPS ; `functional` = démarrage, entrée en monde, capture, lecture du crash (bench) |
| `-Seconds N` | durée de la mesure / du test (60) |
| `-Preset lanterne_only\|lanterne_sodium\|full` | mods : Lanterne seul / + Sodium / + les 9 autres mods du pack (lanterne_sodium) |
| `-NoSodium` | retire Sodium (Iris est alors « inerte » sauf `-JvmArgs '-Dlanterne.irisvanilla=true'` sur la branche intégrée) |
| `-ExtraMods a.jar,b.jar` | jars supplémentaires |
| `-Shaders on\|off`, `-ShaderPack x.zip`, `-ShadowDistance N` | shaders (off, Complementary r5.9, distance du pack) |
| `-Dh on\|off` | Distant Horizons (off) |
| `-Gpu rtx\|igpu` | RTX 3080 (JDK gradle, préférence GPU 2) ou iGPU AMD (`C:\igpubench\jdk25`, préférence 1) |
| `-Time N` | heure figée en ticks (12500 = coucher de soleil) |
| `-Res 1280x720`, `-RenderDistance 12`, `-MaxFps 260`, `-Backend opengl\|vulkan` | affichage |
| `-JvmArgs '...'` | arguments JVM en plus (`-Xmx3G` est déjà posé) |
| `-Screenshot` | capture d'écran de la fenêtre du jeu (`<id>.png`) |
| `-Jfr` | enregistrement JFR + top des allocations du Render thread (`<id>.jfr-top.txt`) |
| `-LanterneBench` | utilise le mode `-Dlanterne.bench=true` (branche feat-bilan) au lieu de la seule Radiographie |
| `-Note "..."` | commentaire libre |

## Lire les résultats
```
powershell -NoProfile -File C:\bench\status.ps1          # file, job actif, derniers résultats
Get-Content C:\bench\results\<id>.txt                     # résumé lisible
```
Fichiers par job dans `C:\bench\results\` : `<id>.txt` (résumé), `<id>.json` (tout), `<id>.png`,
`<id>.latest.log`, `<id>.radiographie.txt`, `<id>.jfr`, `<id>.jfr-top.txt`, `<id>.crash.txt`.
Statuts : `OK`, `CRASH_AU_DEMARRAGE`, `CRASH_EN_JEU`, `JAMAIS_ENTRE_EN_JEU`, `INVALIDE_GPU` (relancé une fois),
`SANS_MESURE`, `ERREUR_RUNNER`. Une ligne du tableau « Suivi » de `OBJECTIFS-FPS.md` est ajoutée pour chaque
mesure `OK` (le coordinateur commite).

## Ce que le runner garantit
- **Un seul client à la fois** (mutex `Global\LanterneBenchRunner`), aucune fenêtre de console.
- **Fenêtre au premier plan** pendant toute la mesure (SetForegroundWindow) : jamais en arrière-plan.
- **GPU vérifié** dans le log (`Using graphics device`) : différent du GPU demandé → résultat INVALIDE, relancé.
- **`inactivityFpsLimit:"minimized"`**, heure et météo figées (Freeze.java patche `data/minecraft/*.dat` du monde Bench : `advance_time`/`advance_weather`/spawns à 0, `total_ticks` = heure), gameDir neuf à chaque job.
- **Silence** : attend qu'aucun autre client ne tourne et que le CPU soit < 30 % (8 min max, puis note la contamination dans le résultat).
- **Verrou `C:\bench-lock`** : le runner pose `C:\bench\WANT`, attend que le détenteur manuel ait fini (verrou périmé après 6 min sans client, ou 15 min), puis le prend et le libère à la fin du job.
- Ne tue que le PID qu'il a lancé (fermeture propre d'abord, force ensuite).

## Exploitation
- Démarrer (caché) : `Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','C:\bench\runner.ps1' -WindowStyle Hidden`
- Arrêt propre : créer `C:\bench\STOP`. Journal : `C:\bench\runner.log`.
- Vider un job en file : supprimer son `.json` dans `C:\bench\queue\`.

## Limites connues
- Le modèle de client est celui de `C:\perfrun5` (NeoForge 26.3.0.7-beta, classpath fixe) ; changer de version de jeu demande de refaire `C:\bench\template`.
- `-Backend vulkan` n'est qu'une option d'`options.txt` : sans DH-compat (Réveil repasse en OpenGL si DH est présent).
- Mode `functional` : pas d'entrée clavier (aucune commande dans le jeu) ; on lit le log, le crash-report et une capture.
- Le tableau « Suivi » reçoit les lignes `bench` `OK` ; les mesures faites à la main sans le banc doivent le dire.
