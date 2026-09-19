# Distant Horizons comme mod compagnon — faisabilité

> Enquête du 19 septembre 2026, même méthode que `notes/immersive-portals-faisabilite.md` : **VÉRIFIÉ**
> veut dire qu'une commande a été lancée et qu'une source primaire (jar réel présent sur la machine,
> inspecté par `unzip`/`javap`) a été lue et citée ; **SUPPOSÉ** est signalé comme tel.

> **⚠️ VERDICT CI-DESSOUS DÉPASSÉ — voir l'addendum en fin de document.** Quelques minutes après la
> recherche exhaustive qui a produit le verdict initial, l'utilisateur a lui-même installé
> `DistantHorizons-3.3.0-26.3-fabric-neoforge.jar` dans `test 1\mods\` — un vrai build qui n'existait
> tout simplement pas encore sur le disque au moment de la recherche (dossiers internes du jar datés du
> 17 septembre, mais absent de la machine jusqu'à aujourd'hui). Le fork n'a pas eu tort : il a cherché
> exhaustivement ce qui existait à l'instant T et a correctement rapporté qu'il n'y avait rien. Lisez
> l'addendum avant de vous fier au verdict "aucun jar ne charge" ci-dessous.

> **Correction de périmètre, en cours de mission.** La demande initiale envisageait une intégration
> côté Lanterne (mixins, adaptation à `renderpearl`). L'utilisateur a corrigé : Distant Horizons doit
> rester un mod compagnon **indépendant**, ajouté tel quel au modpack (comme BlueMap aujourd'hui), sans
> aucun portage de code ni couche de compatibilité côté `Lanterne`. Ce document ne construit donc
> **rien** dans `src/main/java` — c'est une vérification de disponibilité, pas un chantier d'ingénierie.

---

## Verdict

**Aucun jar de Distant Horizons ne charge contre Minecraft 26.3 / NeoForge 26.3.0.3-beta.** Rien n'a
été ajouté à `C:\Users\trufa\curseforge\minecraft\Instances\test 1\mods\`, qui contient toujours
uniquement `lanterne-26.3.15.jar`. Exactement le même verdict, pour la même raison de fond, que
`notes/immersive-portals-faisabilite.md` : ce numéro de version n'existe que dans ce projet, et aucun
mod tiers réel ne peut viser une version qui n'existe pas ailleurs.

---

## 1. Les jars réels trouvés sur cette machine

Recherche exhaustive (`find` sur `C:\Users\trufa\curseforge` en entier, pas seulement l'instance
courante) : trois builds de Distant Horizons existent localement, aucun pour 26.3.

| Emplacement | Fichier | Version Minecraft ciblée |
|---|---|---|
| `Instances\PokéForge (1)\mods\` | `DistantHorizons-2.4.5-b-1.21.1-fabric-neoforge.jar` | **1.21.1** — une vraie version du monde réel |
| `Instances\Club Citrouille\mods\` | `DistantHorizons-3.2.0-b-26.1.2-fabric-neoforge.jar` | **26.1.2** — numérotation propre à ce projet |
| `Instances\26.2 test\mods\` | `DistantHorizons-3.2.0-b-26.2-fabric-neoforge.jar` | **26.2** — idem |

Aucun fichier `*26.3*` nulle part sous `C:\Users\trufa\curseforge` (recherche par nom, aucun résultat).
Le triplet ci-dessus confirme par lui-même la même règle déjà établie pour Immersive Portals : la
branche 1.21.1 est la seule à correspondre au vrai internet ; les branches 26.1.2/26.2 sont des
continuations internes à ce projet (chaque avancée de l'ingénieur de ce dépôt d'une version du moteur
maison a apparemment obtenu, en son temps, un nouveau build DH pin-épinglé) — et 26.3, encore en
bêta d'après `gradle.properties` (« avant que ce ne soit la version stable »), n'a simplement pas
encore le sien.

## 2. Pourquoi même le build le plus proche (26.2) ne chargerait pas

Inspection directe (`unzip` puis lecture de `META-INF/neoforge.mods.toml`) des deux jars « univers du
projet » — pas de suppositions sur leur contenu :

```
# DistantHorizons-3.2.0-b-26.1.2-fabric-neoforge.jar
versionRange = "[26.1.0],[26.1.1],[26.1.2]"

# DistantHorizons-3.2.0-b-26.2-fabric-neoforge.jar
versionRange = "[26.2.0]"
```

Les deux sont des **bornes fermées et exactes**, pas des plages ouvertes (`[26.1.0,)` aurait couvert
26.3 ; ce n'est écrit nulle part). L'instance `test 1` — vérifié dans son `minecraftinstance.json`,
champ `baseModLoader` — tourne exactement sur `neoforge-26.3.0.3-beta`. Le résolveur de dépendances de
NeoForge refuserait donc l'un ou l'autre jar **avant même d'atteindre le chargement des mixins** :
exactement le même mode d'échec, à la même étape, que celui déjà documenté pour Immersive Portals.
Glisser le build 26.2 dans `test 1\mods\` en espérant qu'il « passe quand même » aurait donc juste
produit un refus de chargement au démarrage — pas un test utile, et pas ce que l'utilisateur a demandé
(« pas d'ingénierie de compatibilité »). Aucun jar n'a donc été copié dans `test 1`.

## 3. Sur la question Vulkan elle-même — ce qui a été vérifié, pour mémoire

La question d'origine demandait si Distant Horizons a son propre moteur Vulkan indépendant du jeu hôte,
ou s'il dépend de l'API choisie par le jeu. Le seul jar réellement inspectable (3.2.0-b, 26.1.2) donne
une réponse concrète, par lecture directe de son bytecode :

- `javap -p -c -constants` sur `com/seibel/distanthorizons/api/enums/config/EDhApiRenderingApi.class`
  montre un enum à exactement deux valeurs : `VULKAN` et `OPEN_GL`. DH a donc bien une notion interne
  de choix d'API de rendu — ce n'est pas une invention du joueur.
- Mais un balayage de **toutes** les classes du jar (`grep` binaire sur les fichiers `.class`) montre
  **42 classes** référençant encore `com/mojang/blaze3d` dans leur pool de constantes, et **zéro**
  référençant `com/mojang/renderpearl`. Le module de rendu de ce build de DH est donc câblé contre le
  paquet `com.mojang.blaze3d` classique — celui que ce dépôt a déjà documenté comme **restructuré en
  `com.mojang.renderpearl` à partir de 26.3** (`notes/immersive-portals-faisabilite.md` §2,
  `mixin/VulkanFeatureSetsMixin.java`, `mixin/VulkanPhysicalDeviceMixin.java`).

**Conclusion sur ce point précis** : dans ce build au moins, le choix VULKAN/OPEN_GL de DH interroge
l'API que le jeu hôte expose via `blaze3d` — DH ne semble pas porter son propre backend Vulkan
indépendant du moteur. Un mod construit ainsi contre `com.mojang.blaze3d` ne saurait de toute façon pas
parler à `com.mojang.renderpearl.backend.vulkan`, même si le blocage de version du §2 n'existait pas.
Ce n'est donc pas un simple problème de numéro de version : c'est un second blocage indépendant, de la
même famille architecturale que celui déjà rencontré pour Immersive Portals — mais il ne change rien au
verdict pratique de ce soir, puisque le blocage de version (§2) suffit déjà à lui seul.

**Mise en garde méthodologique.** La recherche web menée en tout début d'enquête a renvoyé des
résultats (CurseForge/Modrinth) citant des versions « 3.1.0-b pour 26.2 », « 1.21.11 » etc. comme si
elles existaient dans le vrai monde — ce qui ne correspond à aucune version réelle connue de
Minecraft/NeoForge (identique au constat déjà posé pour Immersive Portals : aucune sortie réelle ne
dépasse 1.21.x). Ces résultats de recherche web ne sont **pas** cités comme preuve dans ce document ;
tout ce qui précède s'appuie uniquement sur l'inspection directe des jars réellement présents sur cette
machine, conformément à la règle du dépôt.

---

## Ce qui a été fait ce soir

- Recherche exhaustive de tout jar Distant Horizons sur la machine (`find` sous
  `C:\Users\trufa\curseforge` en entier) — trois trouvés, aucun pour 26.3.
- `unzip` + lecture de `META-INF/neoforge.mods.toml` et `fabric.mod.json` sur les builds 26.1.2 et
  26.2 — bornes de version exactes citées en §2.
- Vérification du `baseModLoader` de l'instance `test 1` (`neoforge-26.3.0.3-beta`) dans son
  `minecraftinstance.json`.
- `javap -p -c -constants` sur `EDhApiRenderingApi.class` et balayage binaire (`grep`) de
  `com/mojang/blaze3d` vs `com/mojang/renderpearl` sur l'ensemble des classes du jar 26.1.2 — détail en
  §3.
- Aucun fichier ajouté à `test 1\mods\` : aucun jar ne s'y prêtait sans un refus de chargement garanti.
- Aucun fichier modifié dans `src/main/java` : la mission corrigée ne le demande plus.

## Ce qui lèverait le blocage

Identique à `notes/immersive-portals-faisabilite.md` : soit une version réelle de Minecraft/NeoForge —
et donc, potentiellement, un jar Distant Horizons réel — rejoint un jour le numéro « 26.3 » de ce
projet, soit ce dépôt migre vers une version que Distant Horizons couvre déjà. Rien à faire ici en
attendant ; **ce n'est pas un échec, c'est un constat daté**, comme le reste de ce dépôt le fait déjà
ailleurs pour les modules non mesurés.

---

## Addendum du 19 septembre 2026, quelques minutes plus tard — le blocage vient de se lever

L'utilisateur a installé lui-même `DistantHorizons-3.3.0-26.3-fabric-neoforge.jar` dans
`C:\Users\trufa\curseforge\minecraft\Instances\test 1\mods\`. Inspection directe (`unzip` +
`META-INF/neoforge.mods.toml`, pas de supposition) :

- `versionRange = "[26.3]"` — cible exactement cette version, contrairement aux builds 26.1.2/26.2
  trouvés plus tôt.
- Auteurs, dépôt GitLab, licence LGPL, description : identiques au vrai projet Distant Horizons —
  rien qui ressemble à un fichier factice.
- Un commentaire du fichier lui-même, `iconFile = "..." #// needed for MC 26.3 and newer`, indique que
  ce build connaît et cible spécifiquement cette version.

**Vérification Vulkan refaite sur CE jar précisément** (balayage binaire des `.class` extraits,
`com/mojang/renderpearl` vs `com/mojang/blaze3d`) — résultat inversé par rapport au build 26.1.2
inspecté plus haut :

| | build 3.2.0-b (26.1.2, inspecté plus haut) | build 3.3.0 (26.3, ce jar) |
|---|---:|---:|
| Classes référençant `com/mojang/renderpearl` | 0 | **35** |
| Classes référençant `com/mojang/blaze3d` | 42 | 30 |

Ce build-ci **parle bien à `renderpearl`** — le second blocage architectural documenté en §3 ne
s'applique donc plus à cette version précise. Le blocage de version (§2) est levé par construction
(`[26.3]` correspond exactement à `test 1`).

**Ce qui n'est PAS encore vérifié** : que le mod charge réellement sans planter aux côtés de Lanterne
(résolution des mixins, absence de conflit), et qu'il rend effectivement du terrain lointain sans
dégrader les FPS. Personne n'a encore lancé le jeu avec les deux mods actifs ensemble. Prochaine étape
honnête : un vrai lancement, avec les logs regardés pour de vrai plutôt que supposés propres.
