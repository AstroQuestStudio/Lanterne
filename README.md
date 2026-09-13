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
et **dix bancs** ont été réparés parce qu'ils mentaient. C'est cette moitié-là qui rend l'autre croyable.

</div>

---

## ⚡ Les gains, charge par charge

Serveur dédié NeoForge 26.1.2, Ryzen 7 5800H, monde pré-généré, distance de simulation 10.
Deux phases de 25 s, médiane de 500 relevés, **scène reconstruite entre les phases**.

| Charge | Sans Lanterne | Avec | Gain | |
|---|---:|---:|:---:|:--:|
| **8 000 objets au sol** | 494,83 ms | 36,37 ms | **×13,60** | ✅ |
| **1 000 vaches dans 15×15** | 31,04 ms | **5,91 ms** | **×5,25** | ✅ |
| **4 000 orbes d'expérience** | 63,21 ms | 14,77 ms | **×4,28** | ⏳ |
| **Sauvegarde de 64 chunks** | 153,33 ms | 43,35 ms | **×3,54** | ⏳ |
| **6 000 projectiles en vol** | 97,25 ms | 32,50 ms | **×2,99** | ⏳ |
| **Serveur habité réaliste** | 33,46 ms | 17,65 ms | **×1,90** | ⏳ |
| **6 000 TNT** | 64,75 ms | 39,47 ms | **×1,64** | ⏳ |
| **600 villageois** | 56,30 ms | 38,43 ms | **×1,46** | ⏳ |
| **8 000 piles au sol** *(fusion)* | 9,65 ms | 6,78 ms | **×1,42** | ✅ |
| **10 000 entonnoirs actifs** | 13,21 ms | 10,58 ms | **×1,25** | ✅ |
| **L'enclos** *(en plus du socle)* | 8,23 ms | 7,86 ms | **×1,05** | ✅ |
| **Client — temps par image** | 24,79 ms | 19,80 ms | **×1,25** | ⏳ |

<div align="center">

✅ **revérifié après la réparation de l'instrument** &nbsp;·&nbsp; ⏳ **mesuré avant, à reprendre**

</div>

> **Pourquoi cette colonne existe.** Une charge de ce laboratoire portait la vitesse de tick aléatoire
> à 256 pour faire pousser son blé — et ne la remettait jamais. Le monde étant conservé d'une épreuve
> à l'autre, **toute mesure lancée après elle héritait d'un monde qui ticke 85 fois trop vite.**
> Trois autres défauts du même genre ont été trouvés le même soir.
>
> Les deux chiffres revérifiés se sont révélés **meilleurs** que ce qui était publié — ×13,60 au lieu
> de ×12,04, ×5,25 au lieu de ×5,02. Les autres sont probablement justes. « Probablement » n'est pas
> une mesure : ils repassent au banc avant d'être affirmés.

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

## 🏮 La Lanterne de Veille

<div align="center">

<img src="docs/images/veilleuse.png" width="320" alt="La Lanterne de Veille">

**Aucun monstre n'apparaît dans son chunk.**

<img src="docs/images/veilleuse_top.png" width="110" alt="Le dessus"> &nbsp;&nbsp; <img src="docs/images/veilleuse_side.png" width="110" alt="Les faces">

*Une cage d'acier noirci, trois fenêtres d'ambre, une flamme au cœur.*

</div>

Le mod s'appelle Lanterne et n'avait pas de lanterne. Voici la sienne — et c'est le **seul ajout de ce
projet qui rend le serveur plus rapide** au lieu de le rendre plus riche.

Elle n'éteint pas les monstres après coup : elle refuse la tentative **avant qu'elle ne commence**.

```java
public static void spawnCategoryForChunk(MobCategory c, ServerLevel level, LevelChunk chunk, …) {
    BlockPos start = getRandomPosWithin(level, chunk);   // ← annulé avant ceci
    if (start.getY() >= level.getMinY() + 1) {
        spawnCategoryForPosition(…);                      // ← et avant ceci
    }
}
```

Le tirage de position, la carte des hauteurs, la recherche du joueur le plus proche, les règles de
placement, et jusqu'à douze créatures qu'il aurait fallu créer puis faire vivre : **rien de cela n'a
lieu**. Un chunk gardé ne coûte pas moins cher à l'apparition — il ne coûte rien du tout.

| | |
|---|---|
| **Recette** | 4 lingots de fer · 4 verres · **1 pierre lumineuse** |
| **Lumière** | Niveau 15 — un bloc qui repousse la nuit doit avoir l'air de le faire |
| **Portée** | Son chunk, et lui seul — la même langue que l'Ancre |

**Ce qu'elle ne touche pas :** les générateurs de créatures que le joueur a bâtis, les raids, les
invocations, et les animaux. Ce sont des choses voulues ; les supprimer ne serait pas de
l'optimisation, ce serait retirer le jeu.

> L'Ancre garde son chunk **chargé**. La Lanterne le garde **calme**. Deux blocs, une seule règle à
> retenir.

---

## 📡 La compression réseau — et le bloc qui ne cassait pas

### Deflate niveau 1 au lieu de 6

```java
public CompressionEncoder(int threshold) {
    this.deflater = new Deflater();     // niveau par défaut = -1, soit SIX
}
```

Vanilla compresse **chaque paquet de chunk au niveau six**. Un joueur qui arrive avec 32 chunks de vue
en reçoit 441 d'affilée. Sur un hébergement à un cœur, les fils réseau ne s'exécutent pas « à côté »
du fil du serveur : ils lui disputent le même processeur.

| 289 chunks, 12 passes alternées | Temps | Émis | Taux |
|---|---:|---:|---:|
| **Niveau 6** *(vanilla)* | 3 086,8 ms | 1,02 Mo | ÷5,40 |
| **Niveau 1** *(Lanterne)* | **948,3 ms** | 1,23 Mo | ÷4,50 |

**×3,26 plus rapide, pour +20 % d'octets.** Traduit en situation réelle : une arrivée de joueur coûte
**121 ms de cœur au lieu de 393**.

> **Pourquoi le niveau et non LZ4.** Remplacer l'algorithme aurait exigé une négociation à la poignée
> de main, un repli, et aurait rendu le serveur **inaccessible à un client vanilla**. Le niveau ne
> change *rien au format* : un flux zlib de niveau 1 se décompresse par n'importe quel `Inflater`.
> C'est le seul module du mod qui ne demande rien à l'autre côté.

### Le bloc qui ne casse pas quand le serveur est en retard

Ce n'est ni le client, ni la latence, ni le matériel. C'est une divergence d'horloge :

```java
this.gameTicks++;                                    // une fois par tick SERVEUR
int ticksSpentDestroying = this.gameTicks - this.destroyProgressStart;
float destroyProgress = state.getDestroyProgress(…) * (ticksSpentDestroying + 1);
if (destroyProgress >= 0.7F) { destroyAndAck(…); }
```

Le serveur mesure en **ticks serveur**. Le client mesure en **ses ticks**, qui tournent à 20 quoi
qu'il arrive. À 15 TPS, le client accumule 20 unités là où le serveur en compte 15 — **25 % de moins**,
souvent juste sous le seuil. Le bloc revient.

**La correction compte le temps qui passe, pas les tours qu'on a faits.** Une unité toutes les 50 ms.

> Ce n'est **pas** un assouplissement du seuil : les 0,7 restent, et la référence reste l'horloge du
> serveur. On corrige une unité de mesure, pas une tolérance.

---

## 🧭 Les repères — un carnet, et aucune téléportation

```
Touche B          → le carnet
/lanterne wp add|remove|share <nom>
```

Les mods de carte offrent presque tous le saut vers un repère. C'est commode pour un opérateur, et
cela **détruit la survie** : plus rien ne coûte de distance, donc plus rien ne coûte de temps, donc le
monde cesse d'être grand.

**Un repère dit *où c'est* et *à quelle distance*. Le chemin reste à faire.**

| | |
|---|---|
| **Quota** | 5 par joueur, réglable — un carnet sans limite devient une liste de courses |
| **Partage** | public ou privé, d'un clic. Un repère public reste la propriété de son auteur |
| **Affichage** | les 3 plus proches en permanence, avec une flèche **relative au regard** |
| **Couleurs** | huit teintes — au-delà, deux pastilles voisines ne se distinguent plus |

> **Pourquoi une flèche et non un cap.** « 247° » demande de savoir où l'on regarde pour servir à
> quelque chose. Un joueur ne sait pas où est le nord ; il sait où il regarde.

Le serveur envoie le carnet **entier** à chaque changement. Le client n'ajoute, ne retire et ne
modifie jamais rien de lui-même : il remplace. C'est ce qui rend impossible le défaut classique de ces
systèmes — un repère qu'on croit partagé et qui ne l'est pas, un repère supprimé qui reste affiché.

Et le serveur ne croit **rien** de ce qu'un client dit sur lui-même : l'identifiant du propriétaire
est lu dans la connexion, jamais dans le paquet. Un client modifié ne peut pas toucher au carnet d'un
autre.

---

## 🧹 Le balai — et pourquoi il est éteint par défaut

```
/lanterne clear            → passage immédiat
/lanterne clear on|off     → minuterie
/lanterne clear status     → temps restant, total effacé
```

C'est le **seul module qui retire quelque chose au jeu**. Tout le reste de ce mod fait le même travail
pour moins cher ; celui-ci supprime des entités. C'est un aveu, pas une optimisation — d'où
l'interrupteur à `false`.

Il existe parce qu'un administrateur qui en a besoin installera un mod de nettoyage de toute façon, et
parce qu'**un nettoyeur se juge sur ce qu'il refuse d'effacer** :

- tout ce qui **porte un nom** — une étiquette est une déclaration d'intention
- tout ce qui est **apprivoisé, monté, attaché, ou persistant**
- les **villageois** et les marchands ambulants
- tout ce qui est à **moins de 16 blocs d'un joueur** — faire disparaître un butin sous les yeux de
  celui qui vient de le faire tomber est la seule faute qu'un joueur ne pardonne pas

Deux avertissements avant chaque passage : dix secondes, puis trois. Le premier laisse le temps de
courir, le second celui de lâcher ce qu'on fait.

> La **fusion des objets au sol** réduit d'ailleurs le besoin d'y recourir : soixante-quatre objets
> devenus une pile n'ont plus besoin d'être effacés.

---

## ⚙️ Configuration

`config/lanterne-server.toml` — **une ligne par module, et chaque commentaire dit ce que le module a
rendu à la mesure.**

```toml
[socle]
    #Collisions d'entites : presque rien ne peut bloquer quoi que ce soit.
    #canBeCollidedWith rend faux par defaut ; seuls le bateau, le shulker et le
    #ghast apprivoise le redefinissent. 8000 objets au sol : x13,60.
    collisions = true

    #Entonnoirs : seize objets par recherche de conteneur, au lieu d'un.
    #La recharge suit le lot - seize objets coutent 8x16 ticks - donc le debit
    #moyen est EXACTEMENT celui de vanilla. Ce n'est pas un ralentissement.
    entonnoirs = true

    #Positions reutilisees pour les tirages de blocs.
    #Aucun effet sur le temps, et x3,01 SUR LA MEMOIRE ALLOUEE (4,08 Go -> 1,36 Go).
    positions = true

[ajouts]
    #Les orbes d'experience fusionnent 40 fois plus souvent, sur 4 blocs.
    #4000 orbes : 63,21 ms -> 14,77 ms, soit x4,28.
    fusion_des_orbes = true

    #La Lanterne de Veille : aucun monstre n'apparait dans son chunk.
    lanterne_de_veille = true

    [ajouts.balai]
        #Le seul module qui RETIRE quelque chose au jeu. Eteint par defaut.
        actif = false
```

> **Un réglage dont on ne connaît pas le prix ne se règle pas : il se subit.**

Deux sections, et la distinction compte. Le **socle** ne change rien au jeu — il enlève du travail
inutile, et son seul effet visible est que le serveur va plus vite. Les **ajouts** changent le jeu.
On doit pouvoir prendre le premier sans le second.

### Les boîtes de Shulker

Java 26.1 sait **déjà** les reteindre, et mieux qu'on ne le croit :

```json
{ "type": "minecraft:crafting_transmute",
  "input": "#minecraft:shulker_boxes",
  "material": "minecraft:red_dye" }
```

`crafting_transmute` **conserve les composants** : on reteint une boîte déjà colorée, autant de fois
qu'on veut, **sans perdre son contenu**. Seul manquait le retour au violet d'origine — ajouté ici :
boîte colorée + carapace de Shulker.

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
LANTERNE_SELFTEST=1000:160:1 LANTERNE_SCENE=enclos  # élevage intensif — count:rayon:observateurs
LANTERNE_SCENE=items|tnt|orbes|villageois|village|champ|lumiere|projectiles|redstone
LANTERNE_SCENE=entonnoirs|recolte                   # transport d'objets, fusion au sol
LANTERNE_MODULES=collisions                         # mesurer UN module seul
LANTERNE_KITCHEN=1    # les fours cuisent-ils au tick près ? et le débit d'une chaîne
LANTERNE_VOLLEY=1     # une flèche touche-t-elle encore ?
LANTERNE_YIELD=1      # le rendement d'une ferme
LANTERNE_SWARM=1      # débit de génération selon le parallélisme
LANTERNE_PROFILE_ALL=1        # profiler tous les fils, pas seulement le serveur
LANTERNE_WATCH=<motif>        # qui appelle cette méthode ?
```

### Dix bancs qui mentaient, et comment on l'a su

| Le banc disait | La vérité | Ce qui l'a révélé |
|---|---|---|
| TNT : **perte ×0,50** | ×1,64 de **gain** | La phase 1 creusait le décor de la phase 2 |
| Projectiles : **×4,04** | ×2,99 | Le module supprimait le combat |
| Client : **perte ×0,86** | ×1,25 de **gain** | Monde vide, puis phases inégales |
| Fluides : **perte ×0,47** | Non mesurable | ×0,10 **sans le mod des deux côtés** |
| Village : ×1,19 | ×1,90 | Les décors s'accumulaient depuis des mois |
| Génération : ×15 | ×1,0 | Il relisait le disque au lieu de générer |
| Entonnoirs : **débit ÷2** | Débit **exact** | 3 exécutions identiques : 18/30, 39/16, 16/16 |
| Le monde entier : ~20 ms | ~10 ms | `random_tick_speed` resté à **256** depuis une autre charge |
| Chaîne de 6 trémies : **288 944 objets** | 19 | Elle mesurait le chantier d'un autre banc |
| Fusion au sol : ×1,08 | ×1,42 | Le nettoyage du décor semait 5 175 objets |

**Le tir de contrôle** — lancer le banc avec le mod éteint **des deux côtés** — est devenu la
première chose à faire devant un résultat surprenant. Sur les fluides, il a rendu ×0,10 alors que rien
ne changeait : le banc mesurait la gigue de sa propre méthode.

### Le défaut qu'aucun rapport ne montrait

Les quatre derniers ont une famille commune : **ils ne se voyaient pas dans un résultat, mais dans un
chiffre trop régulier.**

`clearDecor` annonçait avoir effacé 58 081 blocs, puis 58 963 — identiques à un pour cent près, alors
que douze mille cinq cents blocs de chantier auraient dû s'y ajouter. Un nettoyage qui ne voit pas ce
qu'il devrait voir.

La cause : l'altitude du chantier était gelée en lisant la carte des hauteurs **en (0,0)** — c'est-à-dire
au milieu du chantier précédent, où elle répond l'altitude d'un coffre. Le sol montait donc de trois
blocs à chaque exécution, le nettoyage passait **au-dessus** des ruines, et les charges s'empilaient.

> C'est la même faute que la dalle de pierre qui montait de quatre blocs par reconstruction, revenue
> ailleurs sous un autre visage. **Le sol se demande maintenant au générateur de terrain**, pas au
> monde : une propriété du relief, que rien de ce qu'on bâtit dessus ne peut déplacer.

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
| **Entonnoirs endormis** | Sept ticks sur huit ne font rien | Vrai, et sans valeur : ces sept ticks ne font qu'une décrémentation |
| **Repos posé** | 15 % du profil part en gravité | Retiré **deux fois** — voir ci-dessous |
| *…et cinq autres* | | |

### Celui qui a été retiré deux fois

Une bête posée sur un cube plein ne peut pas tomber : sa collision pouvait être **conclue** au lieu
d'être calculée. Le raisonnement était juste ; le verdict ne l'a pas été.

**Premier refus** — taux de déclenchement **4,5 %**, coût net d'une milliseconde. La cause n'était pas
le code mais l'état du monde : dans un tas, les bêtes se poussent en permanence et rien n'est jamais
immobile.

**L'enclos a corrigé cette cause.** 915 bêtes sur 1000 se sont posées, le taux est monté à **26 %**.
Quatre paires de mesures entrelacées ont tranché quand même :

```
sans : 6,21 · 7,21 · 8,25 · 6,75   moyenne 7,11 ms
avec : 6,37 · 6,48 · 8,29 · 7,97   moyenne 7,28 ms
```

Trois fois sur quatre dans le mauvais sens.

> **La règle, et elle est arithmétique.** Le rapport a fini par publier le bon chiffre : **157 appels
> à `collide` par tick**, une fois la cadence appliquée. Le raccourci portait sur quarante ; son point
> d'accroche se payait sur cent cinquante-sept.
>
> La valeur d'un raccourci est le produit de **trois** nombres : son taux de déclenchement, ce qu'il
> épargne, et le nombre d'appels. La première publication disait « 22 674 collisions épargnées » — un
> grand nombre qui cachait les deux autres.

### L'enclos, ou comment un module en débloque trois

Trois modules attendaient que les bêtes s'immobilisent, et aucun n'y arrivait. `Jam` exige vingt
ticks d'immobilité consécutifs et n'en trouvait que **152 sur 1000**.

La cause était en amont des trois : **les bêtes décident d'aller se promener.** Le compteur
d'immobilité se remet à zéro, l'amas ne se déclare jamais figé, et plus rien ne peut se poser.

Or une vache au milieu d'un enclos plein qui décide de marcher fait calculer un chemin, le suit, se
fait repousser par ses voisines, et **finit là où elle était**. Le résultat observable est identique ;
seul le calcul disparaît.

Le critère ne suppose rien sur la forme du lieu — pas la densité, qui aurait figé douze bêtes
parfaitement libres réparties sur un chunk. **Cette bête est-elle allée quelque part ?** On note sa
position, on revient cent ticks plus tard. Moins de deux blocs en cinq secondes, elle cesse de
décider. Dès qu'elle en parcourt deux, elle recommence.

> Aucune barrière à surveiller : **bouger est sa propre preuve.**

```
amas figés   121 / 162  →  372 / 422      Jam se déclenche 3× plus
915 bêtes figées sur 1000 · 23 649 décisions d'errance épargnées
```

### Celui qui est revenu

Le module des entonnoirs a été retiré une première fois sur un verdict sans appel — *« 47 objets sans
le mod, 22 avec »*, le débit divisé par deux. **Ce chiffre venait d'un instrument que le projet avait
lui-même déclaré non reproductible** : trois exécutions identiques rendaient 18/30, 39/16, puis 16/16.

La cause tenait dans le correctif précédent. Casser une trémie n'efface pas son contenu, il le
**relâche** — précisément là où la boucle suivante reposait les trémies, qui le ré-aspiraient.

Réparé, l'instrument rend trois fois le même chiffre à l'objet près. Et la bonne réponse n'était pas
d'endormir l'entonnoir mais de lui faire porter **seize objets par recherche de conteneur au lieu
d'un**, en payant seize fois la recharge : débit moyen identique, travail divisé par seize.

> Vanilla fait déjà cela pour un objet ramassé au sol — `addItem(Container, ItemEntity)` avale une
> pile entière de 64 pour la même recharge de 8 ticks. La règle « un objet à la fois » n'est pas une
> loi du jeu : c'est une particularité du transfert entre conteneurs, que vanilla lui-même enfreint.

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

```
/lanterne palette
```

Compte, pour chaque section de terrain chargée, **combien de bits elle paie et combien il lui en
faudrait**. Le jeu choisit ainsi :

```java
case 0          -> ZERO_BITS;           // une seule valeur : aucun stockage
case 1, 2, 3, 4 -> FOUR_BITS_LINEAR;    // ← ici
```

Une section à **deux** états distincts — de la pierre et de l'air, le sous-sol ordinaire — aurait
besoin d'**un** bit par bloc. Elle en reçoit quatre, sur 4 096 blocs. Le gaspillage va du simple au
quadruple.

Ce que l'outil ne décide pas : **combien** de sections sont dans ce cas. Si c'est un tiers du terrain,
le gain se compte en dizaines de mégaoctets ; si c'est deux pour cent, l'idée se jette. C'est le
chiffre qui tranchera, et le module n'est pas écrit avant.

---

## 🚧 Ce qui ne se fera pas, et pourquoi

| Piste | Verdict |
|---|---|
| **Palettes 1-2 bits en mémoire** | **Mesuré** : 5 834 sections, 11,94 Mo payés, 10,76 nécessaires. **1,17 Mo de gaspillage, soit 9,8 %** — et c'est le maximum théorique. L'instrument a évité le module. |
| **Autosave étalée** | **Vanilla le fait déjà** : `saveChunksEagerly` sauve au plus **20 chunks par tick**, plafonné à 128 écritures en vol, soumis au budget de temps, à chaque tick. |
| **Plafond d'apparition** | **Vanilla le teste déjà** avant tout tirage — `canSpawnForCategoryGlobal` filtre les catégories, `canSpawnForCategoryLocal` filtre les chunks. |
| **Ciblage des monstres** | Même verrou aléatoire que `LookAtPlayerGoal` : 9 appels sur 10 sortent sur un tirage, et la cible joueur passe par la liste des joueurs, pas par une requête spatiale. |
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
