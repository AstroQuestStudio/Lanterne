<div align="center">

# 🎃 Lanterne

**La citrouille qui éclaire sans brûler.**

*Mod d'optimisation serveur et client pour NeoForge 26.1.2 — il ne cherche pas à rendre le jeu plus rapide, mais à lui éviter le travail qui ne sert à rien.*

<br>

![NeoForge](https://img.shields.io/badge/NeoForge-26.1.2-orange?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-21-blue?style=for-the-badge)
![Licence](https://img.shields.io/badge/licence-tous%20droits%20r%C3%A9serv%C3%A9s-lightgrey?style=for-the-badge)

<br>

### Chaque chiffre de ce document sort d'un banc automatique.

**Aucun n'a été saisi à la main.** Treize modules ont été écrits puis **retirés** faute de gain prouvé,
et six bancs ont été réparés parce qu'ils mentaient. C'est cette moitié-là qui rend l'autre croyable.

</div>

---

## ⚡ Les gains, charge par charge

Serveur dédié NeoForge 26.1.2, Ryzen 7 5800H, monde pré-généré, distance de simulation 10.
Deux phases de 25 s, médiane de 500 relevés, **scène reconstruite entre les phases**.

| Charge | Sans Lanterne | Avec | Gain |
|---|---:|---:|:---:|
| **8 000 objets au sol** | 433,64 ms | 36,02 ms | **×12,04** |
| **1 000 vaches dans 15×15** | 22,40 ms | **4,47 ms** | **×5,02** |
| **4 000 orbes d'expérience** | 63,21 ms | 14,77 ms | **×4,28** |
| **Sauvegarde de 64 chunks** | 153,33 ms | 43,35 ms | **×3,54** |
| **6 000 projectiles en vol** | 97,25 ms | 32,50 ms | **×2,99** |
| **Serveur habité réaliste** | 33,46 ms | 17,65 ms | **×1,90** |
| **6 000 TNT** | 64,75 ms | 39,47 ms | **×1,64** |
| **600 villageois** | 56,30 ms | 38,43 ms | **×1,46** |
| **Client — temps par image** | 24,79 ms | 19,80 ms | **×1,25** |

<div align="center">

### 🧠 Et la mémoire, qui décide des à-coups

| | Sans | Avec | |
|---|---:|---:|:---:|
| **Élevage intensif** | 2,05 Go | 277 Mo | **×7,58 moins** |
| **Monde au repos** | 3,15 Go | 254 Mo | **×12,69 moins** |
| **Serveur réaliste** | 4,05 Go | 978 Mo | **×4,24 moins** |

*Sur une machine à un cœur, un ramassage ne s'exécute pas « en parallèle » : **il fige le serveur**.
Moins allouer, c'est ramasser moins souvent — le seul levier réel sur la mémoire.*

</div>

---

## 🧭 Pré-génération — ×15 sur l'exploration

Deux façons de fournir un chunk à un joueur qui avance, mesurées sur la même machine :

| | Débit |
|---|---:|
| Le **fabriquer** devant lui | 13 à 17 chunks/s |
| Le **servir** depuis le disque | **155 à 209 chunks/s** |

```
/lanterne pregen <rayon>      /lanterne pregen-stop
```

- **Barre de progression pour les opérateurs seuls** — pourcentage, débit, et *temps restant*, parce que « 34 % » ne dit pas si tu peux aller te coucher.
- **Monde vanilla existant** : les 4 premiers Ko d'un `r.x.z.mca` sont une table de 1024 entrées, une par chunk. 4 Ko lus disent l'état de 1024 chunks, sans ouvrir le générateur. Ce qui existe est sauté.
- **Reprise gratuite** : interrompre et relancer saute d'emblée ce qui est fait. Aucun fichier d'état.
- **Ne bloque pas** : 30 ms par tick, budget relu à chaque chunk.

> C'est le seul levier de génération dont le gain **ne dépend pas du nombre de cœurs** — il supprime le travail au lieu de le distribuer. C2ME et VMP, qui le répartissent, ne rendent rien sur un hébergement à un cœur.

---

## ⚓ L'Ancre de chunk

<div align="center">

<img src="docs/images/anchor.png" width="320" alt="L'Ancre de chunk">

**Elle garde chargé le chunk où elle est posée — et lui seul.**

<img src="docs/images/anchor_top.png" width="110" alt="Le dessus"> &nbsp;&nbsp; <img src="docs/images/anchor_side.png" width="110" alt="Les faces">

*Un œil de lumière sur le dessus, une chaîne d'amarrage scellée dans la pierre sur les faces.*

</div>

Les mods de chargement existants chargent **large** — un rayon de trois, de cinq, parfois tout le
tronçon — pour une machine qui occupe un chunk. Celle-ci ne garde que le sien : on sait exactement ce
qu'on paie, et on paie exactement ce qu'on a demandé.

| | |
|---|---|
| **Recette** | 4 obsidiennes · 4 yeux de l'Ender · **1 lingot de netherite** |
| **Solidité** | 50 (obsidienne) · résistance 1200 — un creeper ne décharge pas ta ferme |
| **Lumière** | Niveau 7 — une ancre posée se voit de loin dans le noir |

**Elle survit à sa propre désinstallation.** Elle s'appuie sur le chargement forcé de vanilla, celui
de `/forceload` : écrit dans les données du monde, il survit au redémarrage et ne dépend d'aucun code
de ce mod pour être rétabli.

> Un mod d'optimisation qui ajoute un bloc éveille à juste titre la méfiance. Celui-ci répond à un
> besoin que l'optimisation elle-même fait naître : **si l'on dégrade ce qui est loin, comment garder
> sa ferme en marche ?** Sa recette est chère parce qu'un chunk chargé en permanence est une dette
> qu'on ne doit pas contracter par mégarde.

---

## ⚙️ Configuration

`config/lanterne-server.toml` — **une ligne par module, et chaque commentaire dit ce que le module a
rendu à la mesure.**

```toml
[socle]
    #Collisions d'entites : presque rien ne peut bloquer quoi que ce soit.
    #canBeCollidedWith rend faux par defaut ; seuls le bateau, le shulker et le
    #ghast apprivoise le redefinissent. 8000 objets au sol : x12,04.
    collisions = true

    #Positions reutilisees pour les tirages de blocs.
    #Aucun effet sur le temps, et x3,01 SUR LA MEMOIRE ALLOUEE (4,08 Go -> 1,36 Go).
    positions = true

[ajouts]
    #Les orbes d'experience fusionnent 40 fois plus souvent, sur 4 blocs.
    #4000 orbes : 63,21 ms -> 14,77 ms, soit x4,28.
    fusion_des_orbes = true

    #L'Ancre de chunk garde charge le chunk ou elle est posee, et lui seul.
    ancre_de_chunk = true
```

> **Un réglage dont on ne connaît pas le prix ne se règle pas : il se subit.**

Deux sections, et la distinction compte. Le **socle** ne change rien au jeu — il enlève du travail
inutile, et son seul effet visible est que le serveur va plus vite. Les **ajouts** changent le jeu.
On doit pouvoir prendre le premier sans le second.

**Le bloc reste enregistré même réglage coupé.** Un bloc absent du registre devient de l'air dans les
mondes où il était posé : un interrupteur de performance n'a pas le droit de détruire une construction.

---

## ✅ Conformité — ce qu'aucun autre mod ne mesure

Un mod d'optimisation qui casse une ferme a échoué, même s'il double les TPS. Chaque mécanisme risqué
a son épreuve, et **deux d'entre elles ont rattrapé un module qui cassait le jeu**.

| Épreuve | Ce qu'elle vérifie | Verdict |
|---|---|---|
| **Rendement** | Ponte, croissance, reproduction d'une ferme | **100 %** |
| **Tir** | Une flèche touche-t-elle encore une créature ? | ✅ 10,0 → 4,0 PV des deux côtés |
| **Expérience** | L'XP totale après fusion des orbes | ✅ **4 000 points pour 4 000 orbes** |
| **Bateau** | Une créature traverse-t-elle un bateau ? | ✅ Non |
| **Chute libre** | La mécanique de toutes les fermes à monstres | ✅ Identique |
| **Fluides** | L'eau s'écoule-t-elle pareil ? | ✅ 113 blocs, à l'unité près |
| **Objets au sol** | Disparition au tick exact | ✅ 120 ticks |
| **Cuisson** | Les fours produisent-ils autant ? | ✅ Exact au tick près |

### L'épreuve qui a sauvé le mod

Le module des projectiles annonçait **×4,04**. L'épreuve de tir a répondu en deux lignes :

```
mod allumé  →  la vache a été épargnée   (10,0 → 10,0 PV)
mod éteint  →  la vache a ÉTÉ TOUCHÉE    (10,0 →  4,0 PV)
```

Il obtenait son gain **en rendant toutes les flèches inoffensives**. Trois défauts en cascade, dont
un que l'épreuve a trouvé sur elle-même avant de pouvoir juger le module. Corrigé, le vrai chiffre
est **×2,99** — trois fois moins beau, et vrai.

---

## 💡 La thèse

> Rendre un travail deux fois plus rapide fait gagner un facteur deux.
> **Ne pas le faire fait gagner un facteur dix.**

Le jeu ne connaît que deux états — chargé, ou pas. Une vache à 150 blocs coûte exactement autant
qu'une vache sous les yeux du joueur. Lanterne ajoute la nuance qui manque, sur trois axes :

| Axe | Principe |
|---|---|
| **Distance** | Ce qui est loin se distingue moins |
| **Densité** | Ce qui est noyé dans le nombre se distingue moins |
| **Échéance** | Un four qui cuit sait quand il aura fini — il n'a rien à faire d'ici là |

### Les garanties, tenues sans exception

1. **Rien n'est dégradé près d'un joueur.** En deçà de 32 blocs, le jeu est le jeu.
2. **Rien n'est jamais arrêté**, seulement espacé. Plancher : une fois par seconde.
3. **Ce qui peut être compensé exactement l'est.** Une culture tickée une fois sur dix pousse avec dix fois la probabilité.
4. **Quand le serveur va bien, le mod ne fait rien.** La sévérité suit la charge mesurée.
5. **Tout est mesuré, y compris ce qui dérange.**

### Ce qui est dégradé, dit franchement

Dans une foule, une bête décide jusqu'à **32 fois moins souvent** — y compris sous les yeux du joueur.
Elle continue en revanche de vieillir, de pondre et de se reproduire **à l'heure exacte**. C'est écrit
dans le mot d'accueil du serveur, pas caché dans un fichier.

---

## 🔬 Le laboratoire

Le mod mesure ses propres effets, et **refuse de conclure** quand il ne peut pas.

```bash
LANTERNE_SELFTEST=1000:0:1 LANTERNE_SCENE=enclos    # élevage intensif
LANTERNE_SCENE=items|tnt|orbes|villageois|village|champ|lumiere|projectiles|redstone
LANTERNE_VOLLEY=1     # une flèche touche-t-elle encore ?
LANTERNE_YIELD=1      # le rendement d'une ferme
LANTERNE_SWARM=1      # débit de génération selon le parallélisme
LANTERNE_PROFILE_ALL=1        # profiler tous les fils, pas seulement le serveur
LANTERNE_WATCH=<motif>        # qui appelle cette méthode ?
```

### Six bancs qui mentaient, et comment on l'a su

| Le banc disait | La vérité | Ce qui l'a révélé |
|---|---|---|
| TNT : **perte ×0,50** | ×1,64 de **gain** | La phase 1 creusait le décor de la phase 2 |
| Projectiles : **×4,04** | ×2,99 | Le module supprimait le combat |
| Client : **perte ×0,86** | ×1,25 de **gain** | Monde vide, puis phases inégales |
| Fluides : **perte ×0,47** | Non mesurable | ×0,10 **sans le mod des deux côtés** |
| Village : ×1,19 | ×1,90 | Les décors s'accumulaient depuis des mois |
| Génération : ×15 | ×1,0 | Il relisait le disque au lieu de générer |

**Le tir de contrôle** — lancer le banc avec le mod éteint **des deux côtés** — est devenu la
première chose à faire devant un résultat surprenant. Sur les fluides, il a rendu ×0,10 alors que rien
ne changeait : le banc mesurait la gigue de sa propre méthode.

---

## 🗑️ Treize modules écrits, mesurés, puis retirés

C'est la partie du projet dont il est le plus fier.

| Module | Pourquoi il devait marcher | Pourquoi il ne marchait pas |
|---|---|---|
| **Tirage aléatoire** | 12 % du profil | **Artefact d'attribution** — 151 M de raccourcis, 0 ms |
| **Formes d'entité** | 1 M d'allocations par tick | Le JIT les éliminait déjà (analyse d'échappement) |
| **Saut des sections vides** | 30 % dans `PalettedContainer` | Vanilla fait déjà le test, plus finement |
| **Répartiteur de chunks** | Le tuyau est large de un | 39 633 voies ouvertes, **aucun effet** |
| **Visibilité des explosions** | Coût quadratique | **0 déclenchement sur 3 019 explosions** |
| **LOD client** | Sodium ne touche pas au tick | **Le tick produit l'état du rendu** — crash |
| **Recherche d'eau** | 162 positions par appel | Décor résiduel — l'eau n'était jamais trouvée |
| **Entonnoirs** | Ticker moins souvent | On aurait cassé les fermes, pas optimisé |
| *…et cinq autres* | | |

### Les deux règles que ces échecs ont installées

> **1.** Un poste élevé sur une méthode **très courte et très appelée** doit être suspecté d'artefact
> avant d'être attaqué. L'échantillonneur ne voit que le sommet de pile, et le JIT inline.

> **2.** Compter des **allocations évitées** ne prouve rien tant qu'on n'a pas mesuré le tas. Deux
> fois, ce projet a supprimé ce que la machine virtuelle supprimait déjà.

---

## 🔍 Ce que vanilla 26.1 fait déjà

Vérifier avant d'écrire a évité **huit** modules inutiles. Mojang a absorbé, version après version,
l'essentiel des mods de référence :

| Ce qu'on voulait porter | État en 26.1 |
|---|---|
| **Noisium** — écriture directe dans la palette | `section.setBlockState(…, false)`, déjà fait |
| **Starlight** — moteur de lumière | 0,6 % du profil sur 2,5 M de blocs détruits |
| **FerriteCore** — propriétés de BlockState | `Property[]` et `Comparable[]`, des tableaux |
| **Lithium** — `hasOnlyAir`, `VoxelShape.move` | Déjà court-circuités |
| **Alternate Current** — redstone | `ExperimentalRedstoneWireEvaluator` existe |
| **Entity Culling** — frustum | `EntityRenderer.shouldRender` le fait |
| Caches de pathfinding | `pathTypesByPosCacheByMob`, déjà là |

**La conclusion qui compte :** le créneau de Lanterne n'est pas de refaire ce travail, c'est de poser
une question que personne ne pose — *pourquoi ce travail a-t-il lieu ?*

---

## 🧰 Outils pour modpack lourd

```
/lanterne nbt [lignes]
```

Pèse chaque bloc-entité **en le sérialisant par le même encodeur que la sauvegarde**, en mémoire, et
rend le classement par type : combien d'exemplaires, combien d'octets, quelle part du total.

Compter les blocs-entités n'aurait rien dit — mille coffres vides pèsent moins qu'une machine de
Create qui retient son inventaire, sa recette, son réseau et son historique. **Ce qui compte est ce
qui est écrit.**

C'est l'**instrument**, pas le résultat : un monde vanilla ne contient aucune machine de Create. Posé
sur un serveur qui porte vraiment le modpack, il dira ce qu'il faut optimiser — et non l'inverse.

---

## 🚧 Ce qui ne se fera pas, et pourquoi

| Piste | Verdict |
|---|---|
| **Remplacer Sodium** | Réécrire le pipeline de rendu — maillages, culling, tampons GPU, compatibilité Iris. Hors de portée, et le prétendre serait mentir. |
| **Worldgen ×7** | Le coût est **étalé et intrinsèque** : `BlendedNoise.compute` fait jusqu'à 40 évaluations de Perlin 3D par point. Pas de gros poisson. |
| **Sommeil généralisé aux mods** | On ne peut pas endormir ce dont on ne sait pas quand il doit se réveiller. Lire l'état coûte plus que le tick. |
| **Mémoire retenue** | 4,8 Mo d'états de bloc ; le reste **est le terrain** (242 Mo de `BitStorage`), incompressible. |
| **Tick parallèle par régions** | Nul sous 16 cœurs. Verdict rendu, non implémenté. |

---

## ⚠️ Cohabitation

> **Immersive Optimization** fait du tick scheduling par distance, **comme Lanterne**. Les deux
> appliqueraient leur dégradation l'un sur l'autre. Le retirer avant de juger.

Lanterne est **complémentaire** de Sodium (qui optimise ce qui est *dessiné*) et de Lithium (ce qui
est *calculé*). Il s'occupe de ce qui ne devrait pas être *fait*.

---

<div align="center">

**Club Citrouille** · Tous droits réservés

*Un mod qu'on ne peut pas réfuter n'est pas un mod rapide, c'est une affirmation.*

</div>
