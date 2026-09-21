# Analyse de Sodium (branche dev) et écarts avec Minecraft 26.3 et Lanterne

Rédigé le 21/09/2026 par l'agent « Lecteur 2 » (salle blanche, deuxième passe : cette fois avec les sources de Sodium sous les yeux).

## 0. Règles de ce document (à lire avant tout)

- **Licence.** Sodium est distribué sous PolyForm Shield 1.0.0 (relevé dans son LICENSE.md) : on ne s'en sert pas pour fabriquer un produit concurrent. Le patron a autorisé la lecture et même la copie ; il n'est pas titulaire des droits, donc la copie reste exclue. Ce document est écrit **dans mes propres mots** : aucun extrait de code, aucun nom de classe ou de méthode interne, aucune structure de classes reproduite. Les chiffres cités sont des **ordres de grandeur observés** ; les équipes d'implémentation les redérivent par leur propre raisonnement et leurs mesures (le doc dit comment).
- **Qui lit quoi.** Ce fichier est fait pour les agents d'implémentation (sommets, ombres, découplage) qui **ne lisent pas** Sodium. Tout ce dont ils ont besoin doit s'y trouver, ou être mesurable sur notre banc.
- **Périmètre lu.** Le rendu du terrain (sections, régions, tampons, culling, maillage, tri des translucides, format de sommet, éclairage/AO), les contournements de pilotes, les options par défaut. Non lu en détail : rendu des entités et des particules, GUI, intégration des chargeurs de mods.
- **Contexte important trouvé en lisant.** La branche dev de Sodium est déjà portée sur l'API GPU de Mojang (la même que celle de Minecraft 26.3, avec des variantes de dessin pour OpenGL et pour Vulkan). Notre moteur n'a donc pas à choisir entre les deux : écrire contre l'API de Mojang donne les deux backends.

## 1. Les techniques de Sodium, une par une

### 1.1 Pipeline d'ensemble

```
chunks chargés ──► sections (16³) ──► [maillage sur threads] ──► tampons GPU partagés par région
       │                                                                   │
       └► graphe de visibilité ──► arbre de sections visibles ──► listes de dessin par région ──► 1 dessin/région/couche
              (thread dédié)            (front-to-back)             (mises en cache)
```

Idée directrice : **le travail par image doit être quasi nul**. Tout ce qui peut être calculé une fois (visibilité, listes de dessin, commandes de dessin) est mis en cache et invalidé seulement quand la caméra change de section, qu'un chunk change ou qu'un résultat de maillage arrive.

### 1.2 Régions et allocation GPU

- Les sections sont regroupées en **régions** de 8 × 4 × 8 sections (256 sections). Chaque région possède un tampon de sommets et un tampon d'indices partagés par toutes ses sections, sous forme d'**arène** (un grand tampon, des segments alloués dedans).
- Une région n'alloue de tampon que si elle contient du contenu ; la taille demandée s'aligne sur une estimation moyenne par section (de l'ordre de 750 sommets et 190 indices par couche) avec une marge d'extension.
- **Défragmentation budgétée.** Les segments libérés créent des trous ; un agrégateur d'arènes surveille la fraction libre (repères : ~7 % libre visés après désallocation, ~4 % de seuil d'abandon, ~5 % pour déclencher une compaction) et copie des blocs GPU→GPU avec un **budget par image** (de l'ordre de 32 copies et 32 Mo par Go d'arène et par image). Ne jamais tout compacter d'un coup : sinon creux d'image.
- Plafond de taille dynamique d'un tampon de l'ordre de 1,5 Go, au-delà duquel on grossit par petits pas (~10 %).
- **Piège** : sous-estimer la taille demandée fait réallouer et recopier des tampons entiers pendant que le joueur bouge.

### 1.3 Envoi des données au GPU (upload)

- Le maillage produit des tampons en mémoire hors-tas (sur les threads de travail) ; le thread de rendu ne fait que **planifier des copies** vers l'arène via un **tampon de transfert** (mémoire mappée en écriture, anneau circulaire) suivi d'une commande de copie GPU→GPU, protégée par une **barrière/fence** pour savoir quand la zone d'anneau est réutilisable. Les copies adjacentes sont **fusionnées** avant soumission.
- Si un envoi ne rentre pas dans l'anneau, repli sur une écriture directe.
- **Budget par image** : la part du temps d'image consacrée aux uploads est bornée (de l'ordre de 30 % de la durée moyenne d'image, plancher de quelques ms) **et** la taille est bornée (de l'ordre de 80 % de l'anneau). Ce budget est connu à l'avance grâce aux estimateurs (voir 1.5).

### 1.4 Culling : trois types de résultats, un thread dédié

C'est la partie la plus originale de la branche dev.

- **Graphe de visibilité.** Chaque section maillée mémorise une petite matrice (6 × 6 faces, tenue dans un entier 64 bits) : « depuis la face A, peut-on voir la face B à travers cette section ? ». Un parcours en largeur part de la section de la caméra et propage la visibilité de proche en proche, en ne franchissant que les faces autorisées. On coupe aussi selon l'**angle** (une face « arrière » par rapport à la direction d'où l'on vient est éliminée) et selon la **distance de brouillard** (option « occlusion par le brouillard » activée par défaut).
- **Trois résultats du même parcours** :
  1. **Local** : valable pour la position et le frustum exacts de la caméra.
  2. **Régulier** : valable pour *toute* position de caméra dans la section courante, sans frustum (on garde les sections qui seraient visibles depuis n'importe quel point de la section).
  3. **Large** : valable pour toute position dans la section et ses voisines.
  Chaque niveau est un sur-ensemble du précédent (visible dans le frustum ⊂ visible en régulier ⊂ visible en large). **Conséquence : tourner la tête ne relance PAS le parcours** ; on lit la liste dans l'arbre « régulier » ou « large » déjà calculé, en filtrant par frustum à la lecture. Le parcours complet ne se refait que quand la caméra change de section, ou qu'un changement de structure survient.
- **Thread dédié.** Le parcours tourne sur un thread unique en arrière-plan, sur un instantané cohérent de la structure (une phase de « lecture sûre » gèle les modifications le temps du calcul). Le rendu consomme le dernier résultat terminé. On mesure la durée moyenne du parcours (moyenne mobile, ~20 % de poids au dernier).
- **Repli synchrone.** Si la caméra saute de plus d'environ 32 blocs en un pas (téléportation), on repasse en calcul synchrone jusqu'à ce qu'elle se stabilise sous ~20 blocs (hystérésis). Idem si aucune arborescence valide n'existe encore (premières images).
- **Arbre de sections en bitsets.** Les sections visibles sont rangées dans une hiérarchie à trois niveaux de masques 64 bits (64 × 64 × 64 sections par arbre, avec deux niveaux « réduits » qui disent quels blocs sont non vides). La traversée descend du gros vers le fin, teste **frustum et distance au niveau des nœuds** (« entièrement dedans » évite tout test plus bas) et sort les sections **dans l'ordre visuel avant→arrière**. Coût par image : proportionnel au nombre de nœuds non vides, très faible.
- **Correctif de « sections apparues »** : quand un résultat de maillage rend visible une section qui ne l'était pas, on la « patche » dans les arbres existants au lieu de tout recalculer.

### 1.5 Maillage, threads et estimateurs

- **Threads de maillage** : nombre calculé automatiquement à partir des cœurs (à peu près « cœurs − 6, mais jamais moins d'un tiers des cœurs », borné de 1 à 10), priorité légèrement **inférieure** au thread de rendu.
- **Priorités par « defer mode »** : les changements importants (bloc cassé ou posé à proximité, moins de ~16 blocs) sont **bloquants** : le rendu attend leur résultat dans la même image (zéro image de retard) pour éviter les trous visuels. Les changements « semi-importants » sont attendus à l'image suivante. Le reste (chargement lointain) est **différé** sans jamais bloquer. Une section proche de la caméra (< ~64 blocs) dont le résultat rend visible du nouveau contenu est présentée immédiatement.
- **Estimateurs en ligne** : pour ne pas dépasser le budget, Sodium apprend en continu, par régression linéaire à décroissance exponentielle : (a) la **durée d'une tâche** de maillage selon son « effort » (nombre de blocs/quads), (b) la **taille du résultat** selon la section, (c) la **durée d'envoi** selon la taille. Le planificateur ne lance des tâches que si le temps de travail disponible sur les threads et le budget d'upload le permettent. Valeurs initiales pessimistes (~5 ms/tâche) qui se corrigent après une centaine d'échantillons.
- **Cache d'éclairage par section** : pendant le maillage, la lumière (ciel, blocs, luminance), l'opacité et l'occlusion ambiante de chaque bloc du chunk et de ses voisins immédiats sont calculées **paresseusement une seule fois** dans un tableau plat d'entiers empaquetés (champs de 4 bits pour les lumières, 12 bits de précision pour l'AO, drapeaux d'opacité et de cube plein), réutilisé d'une tâche à l'autre (**zéro allocation**). Bien plus rapide qu'une table de hachage ; c'est l'origine d'une bonne part de l'accélération du maillage.
- **Comparaison de formes** de blocs pour décider si une face est cachée par un voisin : petit cache de quelques centaines d'entrées de comparaisons déjà faites (formes de vanille comparées par identité).
- **Faces classées par direction** : chaque quad est rangé dans une des 6 directions + une pile « sans direction ». Cela permet, au dessin, de **ne pas dessiner les faces tournées à l'opposé de la caméra** (culling de faces par section, activé par défaut) sans toucher au GPU : on masque des tranches de la commande de dessin.

### 1.6 Listes de dessin et dessin

- Pour chaque région visible, une **liste de sections** (ordre de découverte du parcours) ; pour chaque couche (solide, découpée, translucide), les commandes de dessin sont **construites une fois** (« lot de dessin en cache ») et gardées sur la région tant que rien ne change ; elles ne sont reconstruites que si la liste de la région est invalidée.
- **Un dessin indirect par région et par couche** : chaque section contribue jusqu'à sept sous-plages (une par direction). Les plages voisines dont les données sont contiguës sont **fusionnées** en une seule commande.
- Indices : un **tampon d'indices partagé** pour tous les quads (motif 0,1,2 / 2,3,0), énorme et global, sauf pour les sections translucides triées qui ont leur propre tampon d'indices (voir 1.8).
- Pas d'uniforme par section : la translation de région, l'heure de création (pour les fondus) et l'identifiant de région passent par **20 octets de constantes poussées** ; la position de section dans la région est codée **dans le sommet**.
- Le rendu d'une couche = bind pipeline, bind uniformes globaux (une fois), puis pour chaque région : bind tampon, pousser 20 octets, lancer le lot. Peu d'appels CPU.

### 1.7 Format de sommet compact (≈ 20 octets)

Le terrain vanilla utilise ≈ 28-32 octets par sommet. Sodium en utilise **20** :

```
[8 octets ] position : 3 × 20 bits, répartis sur deux mots (10 bits hauts / 10 bits bas par axe)
[4 octets ] couleur RGBA8 (la teinte et l'AO sont déjà multipliées à l'écriture)
[4 octets ] coordonnées de texture : 2 × 16 bits (15 bits + 1 bit de signe de biais)
[4 octets ] lumière (2 × 8 bits, décalées pour tenir en 8 bits), matériau (8 bits), index de section dans la région (8 bits)
```

- **Position** normalisée dans une plage de 32 blocs (origine décalée de 8) et quantifiée sur 20 bits → précision ~30 µ-blocs. Le shader ajoute la translation de région (constantes poussées) et la position de section (dans le sommet).
- **Texture** : la valeur est rapprochée du centroïde du quad d'une unité de quantification (biais de signe), et le shader applique l'opération inverse avec un très petit epsilon : le **saignement de texture** (texture bleeding) est ainsi évité sans stocker d'epsilon dans la donnée.
- **Lumière** stockée en 8 bits par canal avec un décalage (marge de 8 sur 256).
- **Matériau** : 8 bits (par exemple mode d'alpha-test, fondu…), lus par le shader pour choisir le rendu.
- **Gain** : ≈ −37 % de bande passante mémoire/vertex par rapport à vanille, ce qui aide surtout les GPU intégrés (mémoire partagée) et les très grandes distances.
- **Limite pour Iris** : ce format n'a **aucune place** pour les attributs dont les shaderpacks ont besoin (normale/tangente, identifiant de bloc, centre de texture, décalage du milieu de bloc). Iris utilise un format étendu (52 octets chez nous). Un format compact « Iris » doit être conçu séparément (voir §3 et §4).

### 1.8 Tri des translucides

- Après chaque maillage, une **heuristique** classe la section : vide, sans translucide, ordre indifférent, **un seul ordre possible** (tri statique par distance le long de la normale), **un seul ordre mais à trouver par tri topologique**, ou **dynamique** (plusieurs ordres selon la caméra).
- Pour les sections dynamiques, un **système de déclencheurs** ne relance un tri que si la caméra **franchit un plan** de géométrie translucide qui change l'ordre (plans déjà connus de la construction), pas à chaque déplacement. Les tris se font hors thread de rendu (tâches de tri, planifiées avec leur propre politique de report) et ne concernent que les sections proches (~25 blocs) ; au-delà, un ordre grossier suffit.
- Structures utilisées selon la complexité : tri par plans, **graphe topologique** avec coupe des cycles, ou **arbre de partition binaire (BSP)** en dernier recours ; découpage de quads (« quad splitting ») optionnel pour lever les cas impossibles.
- Gain : évite le tri par image de tous les quads translucides ; coût : mémoire des données de tri et complexité de mise en œuvre importante.

### 1.9 Block entities, animations, entités

- Les block entities sont **rangés par section** dans la région : global (toujours dessinés) ou « culled » (dessinés seulement si leur section est visible dans le graphe).
- **Textures animées uniquement si visibles** : une bitmap par section/région dit quelles animations sont référencées ; seules les animations des sections **visibles** sont marquées actives à l'image (option activée par défaut).
- **Culling d'entités** : avant de rendre une entité, on demande à l'arbre de visibilité si sa boîte englobante touche au moins une section non vide (test peu coûteux, complété par le frustum vanilla).

### 1.10 Contournements de pilotes (très important pour la perf réelle)

- **NVIDIA (Windows).** Le pilote détecte Minecraft en analysant la **ligne de commande du processus** et applique alors un profil dont l'« optimisation threadée » du pilote OpenGL provoque de sévères chutes de fluidité et des plantages. Sodium (1) **réécrit temporairement la ligne de commande du processus** (dans la structure mémoire du processus, ce que la doc Windows interdit officiellement) **juste avant la création de la fenêtre**, avec une chaîne qui laisse le pilote reconnaître le jeu mais pas activer le mode fautif, puis la **restaure** ; (2) une fois le contexte créé, **active le mode de débogage synchrone** d'OpenGL, ce qui empêche le pilote d'utiliser la soumission threadée. Sous Linux : une variable d'environnement suffit.
- **AMD (Windows).** Un contournement analogue existe (pilotes récents) ; il est sauté quand une carte NVIDIA est aussi présente (pour ne pas écraser deux fois la ligne de commande).
- **Intel anciens** : contournements de plantage sur copie de framebuffer et de comparaison de profondeur.
- **Piège majeur (à traiter en priorité) :** si l'application du contournement NVIDIA est déclenchée **deux fois sans restauration entre les deux**, la seconde échoue (« Command line is already modified » dans nos journaux) et **le contournement n'est pas appliqué** → sévères baisses de FPS. Sur une machine qui a **à la fois NVIDIA et AMD** (notre banc : RTX + iGPU Ryzen) la détection et les deux chemins d'application se croisent, ce qui explique probablement l'erreur vue dans nos logs. **Retirer Sodium retire aussi ce contournement** : Lanterne doit donc **fournir le sien** (fiable, appliqué une seule fois, restauré, avec garde-fou), sinon le retrait de Sodium fera *baisser* les FPS sur les cartes NVIDIA.
- Autre détail de plate-forme : Minecraft 26.3 crée la fenêtre via SDL (plus GLFW) ; Sodium enveloppe cet appel précis. Le contournement doit donc s'accrocher là.

### 1.11 Options par défaut qui comptent

Occlusion par le brouillard : oui. Culling de faces de blocs : oui. Culling d'entités : oui. Animer seulement les textures visibles : oui. Mode de report du maillage : « toujours » (jamais bloquer sauf changements proches). Contexte GL sans erreur : oui (sauf Linux/Wayland). Nombre de threads de maillage : automatique.

## 2. Grille d'écarts

Légende : ✔ acquis, ◐ partiel, ✘ absent. « Vanille » = Minecraft 26.3 vérifié dans le jar par mes soins (javap sur `minecraft-patched-26.3.0.7-beta.jar`) ou repris de `RENDU-LANTERNE-DESIGN.md` (vérifié par l'architecte). « Lanterne » = dépôt (`client/terrain`, module de vérification du rendu, etc.).

| # | Technique | Vanille 26.3 | Lanterne aujourd'hui | Manque |
|---|---|---|---|---|
| 1 | Tampons de sommets/indices partagés en arène | ✔ tampons « uber » découpés en tas gérés par un allocateur TLSF, envoi par tampon de transfert | ✔ réutilise vanilla | défragmentation budgétée (vanille n'expose pas d'opération de défrag) ; taille adaptée à l'estimation |
| 2 | Dessin indirect multi-lots | ✔ (`prepareChunkRendersIndirect`, si le périphérique le permet) | ✔ vérifié actif | fusion de commandes adjacentes, lot en cache par région à confirmer |
| 3 | Culling de sections | ◐ graphe d'occlusion de vanille (recherche « avancée » à partir de ~60 blocs, mise à jour sur mouvement) | ✘ | **trois résultats (local/régulier/large) réutilisables à la rotation** ; thread dédié ; arbre de bitsets |
| 4 | Culling de faces par côté de caméra | ✘ | ✘ | masque de directions par section au dessin |
| 5 | Maillage sur threads + budget | ✔ exécuteur ; « proche » = 32 blocs | ✘ | estimateurs en ligne, budget d'upload borné par le temps d'image, priorités bloquantes / 1 image / différées |
| 6 | Cache d'éclairage plat par section | ✘ (vanille recalcule) | ✘ | tableau plat d'entiers empaquetés |
| 7 | Format de sommet compact | ✘ (28–32 octets) | ◐ format **étendu** (plus gros) pour la fusion de faces | format compact, avec place pour les attributs Iris |
| 8 | Fusion de faces (greedy) | ✘ | ◐ UP/DOWN solides seulement | faces latérales ; deuxième axe |
| 9 | Tri des translucides borné | ◐ OIT (`executeOit`) sans tri | ✔ suit vanille | classification + déclencheurs si l'on veut la qualité d'un vrai tri |
| 10 | Textures animées si visibles | ✘ | ✘ | marquage par section visible |
| 11 | Culling d'entités par graphe | ✘ | ✘ | test de boîte contre l'arbre |
| 12 | Contournements pilotes | ✘ | ◐ dépend de Sodium (et échoue chez nous) | **implémentation propre à Lanterne** |
| 13 | Ombres Iris | (n'existe pas en vanille) | ◐ passe d'ombres écrite contre Sodium | tout le §3 |
| 14 | Backends OpenGL et Vulkan | ✔ interface commune de Mojang | ◐ infra Vulkan maison | shaderpacks en Vulkan (bug natif de la compilation GLSL→SPIR-V) |

## 3. Où faire mieux que Sodium

Chaque ligne : idée → gain estimé → coût → risque → **comment le mesurer**.

1. **Ombres : ne pas retraverser le graphe à chaque image.** Sodium (avec Iris) refait un parcours complet pour la lumière à chaque image et coupe l'occlusion pour les ombres. Nous pouvons mettre en cache la **liste de sections d'ombre** tant que le soleil et la caméra bougent peu (seuils de position/angle). Gain : gros (les ombres pèsent ~9 ms sur 13 ms sur notre banc avec shaders). Coût : moyen. Risque : ombres qui « sautent » à la sortie du cache (à lisser). Mesure : temps de la passe d'ombre en ms par image, avec/sans cache, monde fixe puis caméra qui tourne.
2. **Cache de la carte d'ombre (shadow map).** Ne la recalculer que si la lumière (position du soleil) a bougé au-delà d'un seuil, ou si la géométrie proche a changé ; alternance 1 image sur N en mouvement lent. Gain : très gros ; coût : moyen ; risque : artefacts sur entités mobiles (traiter dynamique et statique séparément). Mesure : FPS avec/sans, captures comparées.
3. **Culling d'ombre par direction du soleil.** Une section qui ne peut projeter d'ombre dans le champ visible (elle est « derrière » la caméra par rapport au soleil et hors de portée) est ignorée. Gain : moyen à gros ; coût : moyen ; risque : ombres manquantes aux bords (marge). Mesure : nombre de sections rendues dans la passe d'ombre.
4. **Distance d'ombre adaptative selon le budget d'image** (asservissement type régulateur). Gain : stabilité (1 % low) ; coût : faible ; risque : variation visible (hystérésis). Mesure : histogramme d'image.
5. **Occlusion pour les ombres avec un point de vue « soleil ».** Sodium la coupe. Un parcours de visibilité vu de la lumière (avec les mêmes matrices de faces) réduirait encore. Gain : moyen ; coût : élevé ; risque : erreurs de visibilité. À faire après 1-3.
6. **Fusion de passes.** Réutiliser le maillage/les listes entre la passe d'ombre et la passe principale quand les régions sont les mêmes ; ne pas reconstruire les commandes de dessin par passe. Gain : moyen ; coût : faible-moyen ; risque : faible.
7. **Culling par le GPU (pyramide Hi-Z) ou par ordinateur (compute).** Une image de latence, ne convient pas aux translucides triés. À 12 chunks de distance le gain est modeste ; à 32+ il devient significatif. Coût : élevé ; risque : moyen. **À faire en dernier**, seulement si un goulot GPU persiste.
8. **Priorités de maillage selon la vitesse de la caméra** (regarder devant soi, sections dans le cône de déplacement d'abord). Gain : perçu (moins de trous) ; coût : faible ; risque : faible. Mesure : temps de remplissage après téléportation.
9. **Format de sommet compact avec attributs Iris.** Au lieu de 52 octets, viser ~28-32 en encodant normale/tangente sur peu de bits, identifiant de bloc sur 16 bits, centre de texture dérivé dans le shader. Gain : bande passante −40 % sur les GPU intégrés ; coût : élevé (encodeur + patcher de shaders) ; risque : moyen. Mesure : octets/sommet, temps de la passe terrain sur iGPU.
10. **Zéro allocation par image** dans le rendu (listes réutilisées, tampons de constantes préalloués). Gain : moins de ramasse-miettes (creux) ; coût : faible ; risque : faible. Mesure : nombre/durée de GC pendant 60 s (le futur bilan `[BILAN]`).
11. **Upload par lots avec fusion de copies et budget de temps.** Déjà l'esprit de Sodium ; on peut l'adapter aux petites configs (budget plus serré, plus de threads de maillage en priorité basse). Mesure : 1 % low pendant un vol rapide.
12. **Régulateur global de qualité** (distance de rendu/ombres/entités/particules) : Sodium n'en a pas. C'est l'objet du chantier « petites configs ».
13. **Contournement NVIDIA/AMD maison, robuste.** Un seul point d'entrée, idempotent, journalisé, appliqué avant la fenêtre, retiré après. Mesure : FPS avant/après sur RTX (écart attendu très supérieur à 10 % si le pilote applique bien l'optimisation threadée).
14. **Estimateurs de tâches simplifiés.** Les régressions à décroissance exponentielle de Sodium sont sophistiquées ; une simple moyenne mobile par type de tâche donne 80 % du bénéfice pour 10 % de la complexité (à valider par mesure).

## 4. Ordre d'implémentation recommandé (max 15) avec critères de succès

Critères chiffrés sur le banc défini dans `OBJECTIFS-FPS.md` (verrou de mesure, GPU cité à chaque ligne).

1. **Contournement NVIDIA/AMD propre à Lanterne** (indépendant de Sodium). *Succès* : le log dit « appliqué » une seule fois, aucune erreur de type « déjà modifié », FPS sans shaders sur RTX ≥ +10 % vs état actuel, ou gain nul prouvé (alors on le documente).
2. **Cache de liste d'ombre + cache de shadow map** (W2). *Succès* : passe d'ombre −40 % en ms sur une scène fixe, FPS avec shaders ≥ 100 (base 53,6) sans artefact visible (captures lues).
3. **Distance d'ombre adaptative.** *Succès* : 1 % low ≥ 60 % de la moyenne avec shaders.
4. **Culling d'ombre par direction du soleil.** *Succès* : sections dans la passe d'ombre −30 %, aucune ombre manquante sur captures.
5. **Chemin sans Sodium : contexte de bloc dans le maillage vanilla + format de sommet Iris** (W1). *Succès* : terrain rendu avec Complementary sans Sodium, sans artefact, FPS ≥ 90 % de la version avec Sodium.
6. **Retrait des dernières références Sodium** (W3). *Succès* : 0 import restant hors modules d'interface, client sans Sodium atteint un monde.
7. **Zéro allocation par image dans nos hooks de rendu.** *Succès* : GC < 1 ms cumulé/s en régime établi.
8. **Résultats de culling réutilisables à la rotation** (idée « régulier/large »). *Succès* : coût CPU du culling par image −50 % pendant une rotation de caméra à position fixe.
9. **Format compact Iris** (§3 n° 9). *Succès* : ≤ 32 octets/sommet, FPS iGPU +15 %.
10. **Priorités de maillage** (§3 n° 8). *Succès* : temps de remplissage à 12 chunks après téléportation −30 %.
11. **Culling de faces par côté de caméra.** *Succès* : sommets soumis −15 à −25 %.
12. **Textures animées seulement si visibles.** *Succès* : temps d'animation CPU/GPU −X mesuré ; aucun sprite gelé visible.
13. **Culling d'entités par l'arbre de sections.** *Succès* : entités rendues −Y % en intérieur/sous terre.
14. **Régulateur adaptatif de qualité (iGPU).** *Succès* : tient 60 FPS moyens sur l'iGPU Ryzen à distance 8 sans shaders.
15. **Fusion de faces latérales** (extension de la fusion existante). *Succès* : sommets −20 % sur terrain plat.

(Les points 7, 12, 13 et 15 sont bornés et peuvent être menés en parallèle des chantiers 2 à 6.)

## 5. Ce que je n'ai pas compris ou pas vérifié

- **Le détail exact de la reconnaissance de Minecraft par le pilote NVIDIA** : Sodium écrit une chaîne précise dans la ligne de commande ; je décris le principe, pas la chaîne. C'est à établir **par expérience** chez nous (mesure avant/après), pas à recopier.
- **Pourquoi l'erreur « ligne de commande déjà modifiée » chez nous** : hypothèse forte (double application sur machine NVIDIA + AMD, ou création de fenêtre en deux temps) ; non prouvée. À vérifier en lisant le log complet d'un démarrage avec les deux GPU.
- **Variantes Vulkan** de la branche dev : présentes (classes de lot de dessin dédiées) mais je n'ai pas pu vérifier leur maturité.
- **Rendu des entités, particules, nuages** de Sodium : non lus. Ils pèsent aussi dans les FPS (les nuages en particulier avec shaders).
- **Constantes de vanille** : j'ai confirmé par javap la présence des classes (tampons « uber » + allocateur TLSF, `ChunkSectionsToRender` avec rendu par groupe et OIT, graphe d'occlusion avec « recherche avancée » à ~60 blocs, exécuteur de maillage avec distance « proche » de 32 blocs). Je n'ai **pas** ré-audité le moteur de threads de maillage de vanille (nombre de threads, priorité) : à lire avant de décider d'imiter la formule automatique de Sodium.
- **Chiffres** de ce document (tailles de régions, seuils, budgets) : ordres de grandeur pour **comprendre**, pas des constantes à reprendre. Chaque équipe les redérive sur banc.

## 6. Contrôle de conformité

Exécuté sur ce fichier avant commit (21/09/2026) :

1. **Noms de classes** : les 532 noms de classes Java de 9 caractères ou plus extraits du dépôt de Sodium (`find … -name "*.java"`) ont été cherchés mot pour mot dans ce fichier : **0 occurrence**.
2. **Motifs de code** : recherche de `net.caffeinemc`, des noms d'attributs et d'uniformes de shader de Sodium, de `import`, de déclarations Java : **aucun code** (un seul faux positif, le mot « import » dans une phrase de la liste du §4).
3. **Blocs de code** : deux seuls blocs délimités, tous deux des **schémas ASCII** (pipeline d'ensemble, format de sommet).
4. Les identifiants de **vanille** cités (tampons partagés, graphe d'occlusion, rendu par groupe, OIT) appartiennent à Minecraft, pas à Sodium.
