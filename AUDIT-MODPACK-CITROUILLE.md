# Audit du modpack exporté `citrouille.zip` (21/09/2026)

Source : `C:\Users\trufa\Downloads\citrouille.zip` (5,6 Mo), extrait dans `/tmp/cz`. Rien n'a été modifié (VPS et dépôt intacts).

## 1. Contenu

Manifeste : nom « test 1 », MC 26.3, `neoforge-26.3.0.7-beta`, Java 25, **11 mods CurseForge** (IDs seuls, pas de noms dans le manifeste) + `overrides/mods` (2 jars). Les noms viennent de `modlist.html` ; l'association ID→nom n'a pas pu être vérifiée par l'API CurseForge (clé requise), elle est déduite des noms de fichiers de l'instance `test 1` (voir ci-dessous).

| projectID | mod | fichier réel dans l'instance `test 1` |
|---|---|---|
| 238222 | JEI | jei-26.3-neoforge-31.1.0.12.jar |
| 508933 | Distant Horizons | DistantHorizons-3.3.0-26.3-fabric-neoforge.jar (28 Mo) |
| 844662 | Entity Model Features | entity_model_features-3.3.8-26.3-neoforge.jar |
| 60089 | Mouse Tweaks | MouseTweaks-neoforge-mc26.3-2.31.jar |
| 394468 | Sodium | sodium-neoforge-0.9.2+mc26.3.jar |
| 511040 | Durability Tooltip | durabilitytooltip-1.2.0-neoforge-mc26.3.jar |
| 32274 | JourneyMap | journeymap-neoforge-26.3-6.0.9.jar |
| 1689768 | Rinku | rinku_neoforge_3.0.5_MC_26.3.jar |
| 1366156 | MezzConfig | mezz_config-26.3-neoforge-0.5.12.jar |
| 568563 | Entity Texture Features | entity_texture_features-7.2.4-26.3-neoforge.jar |
| 438332 | SuperMartijn642's Config Lib | supermartijn642configlib-1.1.8-neoforge-mc26.3.jar |

Overrides : `lanterne-26.3.38.jar`, `iris-neoforge-1.11.6-INOFFICIEL-mc26.3.jar`. Aucun dossier `config/` dans le zip.

## 2. Problèmes constatés

1. **Lanterne périmé** : 26.3.38 dans le pack, 26.3.100 sur le VPS et sur l'instance `test 1` (dernier build local : 26.3.101). Un joueur qui importe ce pack démarre en 26.3.38.
2. **Doublon Iris — bloquant avec un Lanterne récent.** `iris-neoforge-1.11.6-INOFFICIEL-mc26.3.jar` déclare `modId = "iris"` et contient `net/irisshaders/*` (972 entrées). Lanterne 26.3.101 déclare aussi `[[mods]] modId = "iris"` (passif) et embarque 974 entrées `net/irisshaders/*`. Deux fichiers pour le même modId + mêmes classes : NeoForge refuse le démarrage (mod dupliqué) ou charge un Iris au hasard. Lanterne 26.3.38 ne contenait pas Iris (0 entrée), donc le pack tel quel est cohérent, **mais dès que la Mèche met Lanterne à jour, le jar Iris doit disparaître en même temps**. Ordre de la Mèche : jar Lanterne d'abord (v1), catalogue ensuite ; le retrait d'Iris passe par `RETIRER.txt`.
3. **Sodium** : le pack l'embarque (0.9.2), et Lanterne 26.3.38 le déclare dépendance requise. Lanterne 26.3.101 le déclare `optional`. Sodium n'est pourtant pas retirable à ce stade : le dernier couplage (`VanillaRenderingPipeline` / `ChunkMeshFormats.COMPACT`) n'est pas migré et le démarrage sans Sodium n'a jamais été testé avec un vrai client. Recommandation : ne PAS le mettre dans `RETIRER.txt` avant ce test.
4. **Embeddium** : la dépendance `embeddium` de l'Iris jar est de type `incompatible` (pas un manque). Non concerné, aucun Embeddium dans le pack.
5. **Config Lanterne côté client** : dans les configs existantes, `auto_maj = false` (constaté dans `C:\lanterne-client-test\config\lanterne-maj-client.toml`). Le nouveau défaut ne s'applique qu'aux nouveaux fichiers ; c'est pourquoi la clé a été renommée `synchro_auto` (en cours dans le dépôt).
6. Aucun mod incompatible MC 26.3 détecté : tous les noms de fichiers de l'instance portent `26.3`. Rien de vérifié au chargement dans ce rapport, seulement par les noms/versions.
7. Aucune dépendance manquante : Mezz Config est requis par JEI/JourneyMap (présent), SuperMartijn642 Config Lib par Durability Tooltip (présent).

## 3. Comparaison avec le VPS (`/srv/minecraft/mods`)

Serveur : `Jade-mc26.3-NeoForge-26.3.1.jar`, `bluemap-5.27-neoforge.jar`, `lanterne-26.3.100.jar`, dossier `backup/`. Le dossier `config/lanterne/mods-clients/` **n'existe pas encore** sur le VPS.

- Les 11 mods du pack sont tous utilisables côté client seul ; aucun n'est requis sur le serveur. JEI, MouseTweaks, Sodium, ETF/EMF, Durability Tooltip, Rinku, DH (le serveur n'en a pas besoin pour le rendu LOD) et JourneyMap sont optionnels ou client.
- Jade est sur le serveur mais absent du pack (les joueurs ne le voient donc pas côté client). À publier dans le catalogue pour cohérence.
- BlueMap est serveur seul : ne pas publier.

## 4. Contenu recommandé de `config/lanterne/mods-clients/` (serveur)

Fichiers (nom → SHA-256 préfixe, taille), tous issus de l'instance `test 1` (mêmes jars que le pack) :

```
DistantHorizons-3.3.0-26.3-fabric-neoforge.jar          fa58252587f0edb2   28 418 774 o
MouseTweaks-neoforge-mc26.3-2.31.jar                    e9ec08badec2e36f       74 133 o
durabilitytooltip-1.2.0-neoforge-mc26.3.jar             b4ebaae9722194f6       25 125 o
entity_model_features-3.3.8-26.3-neoforge.jar           4f61a0324e2766a2      650 715 o
entity_texture_features-7.2.4-26.3-neoforge.jar         b090bdb8027902fc      777 260 o
jei-26.3-neoforge-31.1.0.12.jar                         22cf8ba1e036b702    2 222 223 o
journeymap-neoforge-26.3-6.0.9.jar                      0494997081c999ee    4 352 830 o
mezz_config-26.3-neoforge-0.5.12.jar                    91fd6cbfac2ec07b      339 352 o
rinku_neoforge_3.0.5_MC_26.3.jar                        1e519d9c9e1c4c08      630 668 o
supermartijn642configlib-1.1.8-neoforge-mc26.3.jar     70b2aeed2157dcfb      206 423 o
Jade-mc26.3-NeoForge-26.3.1.jar   (copie du jar du serveur, /srv/minecraft/mods)
sodium-neoforge-0.9.2+mc26.3.jar  (à garder tant que Sodium n'est pas retiré, voir plus bas)
```

Le jar de Lanterne n'est PAS mis là : il est servi par la voie v1 (`MecheManifest`) à partir du jar que le serveur fait tourner. Les noms respectent `[A-Za-z0-9_.+()\-]{1,124}\.jar` exigé par `MecheClient` (aucun espace). Poids total ≈ 38 Mo, dont 28 Mo pour Distant Horizons (~15 s à 4 tranches × 24 Ko × 20 ticks/s).

### `RETIRER.txt` (maintenant)

```
# Lanterne embarque Iris : le jar Iris séparé est un doublon (même modId, mêmes classes)
iris-neoforge-*
# Anciennes versions à remplacer quand on publie une nouvelle version d'un mod
Jade-mc26.1-*
```

Si le pack est importé tel quel, ces deux motifs suffisent. Le premier est le seul qui soit indispensable pour que Lanterne 26.3.100+ démarre avec ce pack.

### `RETIRER.txt` (plus tard, à faire seulement après validation)

```
sodium-neoforge-*
sodium-extra-*
reeses-sodium-options-*
```

À n'ajouter qu'après un test client réel de Lanterne sans Sodium (rendu, onglet Iris, shaders) et sans que Sodium soit demandé par un autre mod du catalogue (Lanterne 26.3.38 le déclare requis, les versions anciennes qui restent chez un joueur refuseraient de démarrer une fois Sodium retiré ; la Mèche met Lanterne à jour avant, mais seulement après un redémarrage).

## 5. Points à surveiller

- La Mèche ne retire que : (a) les jars qui correspondent à `RETIRER.txt`, (b) ceux qu'elle a elle-même posés (registre local `config/lanterne-meche-registre.txt`). Un jar posé à la main qui n'est pas dans la liste reste. Jamais de retrait pour un nom qui commence par `lanterne`.
- Sous Windows le jar Iris chargé est verrouillé : le retrait passe par le nettoyage différé (`cmd` détaché) et n'a lieu qu'à la fermeture du jeu ; un lancement immédiat après la fermeture peut donc voir un doublon quelques secondes. À tester.
- Un client qui a encore Lanterne ≤ 26.3.59 (avant le catalogue) ne reçoit que le manifeste v1 : il passe en 26.3.101 (si `auto_maj = true`) mais ne reçoit le catalogue qu'à la connexion suivante. Le cas « Iris jar + Lanterne récent » peut donc exister une session entière et provoquer un échec au relancement. Solution recommandée : mettre `iris-neoforge-*` dans `RETIRER.txt` AVANT de publier Lanterne 26.3.101 sur le VPS, et republier le pack (mettre à jour `overrides/mods` : Lanterne 26.3.101 sans le jar Iris).
- Pas de `config/` dans le zip : les joueurs qui importent le pack repartent d'une config vide (`lanterne-common.toml` à re-saisir pour la boutique).

## 6. Pack corrigé à produire

`overrides/mods` = `lanterne-26.3.101.jar` seul (retirer `iris-neoforge-*` et le vieux `lanterne-26.3.38.jar`), le reste inchangé. Ne pas le publier tant que le test de bout en bout de la Mèche (client 26.3.59 → serveur jetable 26.3.101, en cours) n'a pas confirmé la mise à jour et le retrait.
