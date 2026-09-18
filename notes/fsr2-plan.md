# FSR 1 -> FSR 2/3 : ce qu'il faut, honnêtement

Le joueur juge le rendu actuel (FSR 1, spatial, un seul passage) trop flou et a
désactivé `lentille` en attendant mieux. Pas tenté dans ce budget (30 min,
partagées avec le diagnostic du freeze BlueMap) — voici le plan pour une session
dédiée, pas une tentative bâclée.

## Ce que FSR 1 fait aujourd'hui (`client/upscale/`)

Un seul passage, aucune mémoire d'une image à l'autre : `Resolve`/`Scene`
capturent la toile réduite, `Upscale`/le shader `fsr_easu.fsh` la remontent par
un noyau de Lanczos spatial. Bon marché, mais chaque image repart de zéro — pas
de détail "deviné" à partir des images précédentes.

## Ce que FSR 2/3 ajoute, et pourquoi c'est net

FSR 2/3 accumulent l'information de plusieurs images passées (accumulation
temporelle) au lieu de tout reconstruire d'une seule image basse résolution.
Trois briques manquantes ici, aucune présente dans ce dépôt aujourd'hui :

1. **Vecteurs de mouvement par pixel** — combien un pixel a bougé à l'écran
   depuis l'image précédente. Nécessite un rendu supplémentaire (ou une
   extraction) des positions monde précédente/actuelle de chaque fragment, donc
   soit une passe de rendu dédiée, soit un accès aux matrices de projection
   `précédente` ET `actuelle` au moment du shader — à vérifier si
   `com.mojang.renderpearl` expose déjà la matrice précédente quelque part
   (candidat : `Camera`/`GameRenderer.extractCamera`, jamais vérifié).
2. **Tampon d'historique** — la toile de l'image précédente, conservée et
   réinjectée à la suivante. Implique une texture supplémentaire à double
   tampon (ping-pong) dans le pipeline `Resolve`/`Scene`.
3. **Rejet de désocclusion** — détecter quand un pixel vient d'apparaître
   (occlusion levée, la caméra a tourné) pour ne PAS réutiliser un historique
   qui ne correspond plus à rien, sous peine de traînées ("ghosting"). C'est la
   partie la plus délicate algorithmiquement : une mauvaise heuristique produit
   un artefact plus visible que le flou qu'on cherche à corriger.

## Verdict honnête

Chacune des trois briques est un vrai chantier en soi, vérifiable seulement en
conditions réelles (le ghosting ne se voit qu'en bougeant la caméra). Tenter ça
en 30 minutes partagées avec autre chose aurait produit soit rien de testé, soit
un résultat à moitié fait présenté comme fini — exactement ce que ce dépôt
refuse de faire ailleurs (voir `notes/rendu-terrain-vs-sodium.md`).

`lentille` reste désactivée par le joueur lui-même en attendant — rien
d'urgent à déployer ici tant que ce n'est pas fiable.

## Point de départ recommandé pour la prochaine session

Dans cet ordre : (1) vérifier si `renderpearl` expose déjà une matrice de
projection précédente avant d'écrire quoi que ce soit soi-même, (2) implémenter
d'abord le tampon d'historique seul, sans rejet de désocclusion, mesurer le gain
de netteté ET le ghosting sur une caméra qui tourne vite (pire cas), (3)
n'ajouter le rejet de désocclusion qu'une fois le ghosting du (2) constaté et
chiffré — pas avant, pour savoir précisément ce qu'il corrige.
