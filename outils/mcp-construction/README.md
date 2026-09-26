# Chantier — l'outil de construction MCP de Lanterne

Deux morceaux :

- **côté mod** (`src/main/java/fr/clubcitrouille/lanterne/chantier/`) : une API HTTP/JSON sur
  `127.0.0.1` du serveur Minecraft, éteinte par défaut, jeton obligatoire. Chaque requête devient un
  travail exécuté sur le thread serveur par tranches de `budget_ms` par tick ; les chunks manquants
  sont chargés en arrière-plan par ticket (jamais de chargement synchrone dans le tick) ; la pause
  « serveur vide » de vanilla est suspendue tant qu'un travail attend.
- **côté poste** (`serveur_mcp.py`) : un serveur MCP stdio, bibliothèque standard Python seule, qui
  expose l'API en outils.

## Activer sur le serveur (VPS)

`config/lanterne-chantier.toml` (créé au premier démarrage avec le jar qui contient le chantier) :

```toml
api_construction = true
jeton = "une-longue-chaine-aleatoire-d-au-moins-16-caracteres"
port = 25590          # écoute sur 127.0.0.1 uniquement
budget_ms = 10        # ms par tick prises au serveur (divisé par 4 si le tick moyen > 40 ms)
blocs_max = 4000000   # blocs au plus par opération
rayon_joueurs = 0     # >0 : refuser une écriture si un joueur est à moins de N blocs
corps_max_mo = 64
```

Redémarrer le serveur (le port s'ouvre au démarrage ; le jeton, lui, est relu à chaque requête).
Le journal serveur doit dire `[CHANTIER] API de construction ouverte sur http://127.0.0.1:25590`.

Générer un jeton : `python -c "import secrets; print(secrets.token_urlsafe(32))"`.

## Tunnel SSH (poste → VPS)

```bash
ssh -i C:/Users/trufa/.ssh/lanterne_vps -N -L 25590:127.0.0.1:25590 ubuntu@141.253.100.132
```

Laisser tourner ; l'API est alors sur `http://127.0.0.1:25590` du poste.

## Brancher dans Claude Code

`.mcp.json` (à la racine du projet, ou via `claude mcp add`) :

```json
{
  "mcpServers": {
    "lanterne-chantier": {
      "command": "python",
      "args": ["C:/Users/trufa/Documents/Lanterne/outils/mcp-construction/serveur_mcp.py"],
      "env": {
        "LANTERNE_CHANTIER_URL": "http://127.0.0.1:25590",
        "LANTERNE_CHANTIER_JETON": "le-meme-jeton-que-dans-lanterne-chantier.toml"
      }
    }
  }
}
```

Essai sans client MCP : `python serveur_mcp.py --appel chantier_etat "{}"`.

## Les outils

| outil | effet |
|---|---|
| `chantier_etat` | version, tick moyen, joueurs connectés et positions, file de travaux |
| `lire_blocs` | blocs d'une boîte : résumé, grilles ASCII par couche (`format_sortie: couches`), ou JSON complet (`fichier:`) |
| `lire_bloc_entites` | coffres, panneaux… avec leur SNBT |
| `lire_entites` | entités d'une boîte (type, position, nom, SNBT si `nbt`) |
| `lire_hauteurs` | plus haut bloc non-air par colonne, ou carte de hauteur du jeu |
| `ecrire_blocs` | liste de blocs (`[[x,y,z,"état"],…]`, palette+plat, ou fichier JSON) |
| `remplir` / `remplacer` / `creuser` | remplissage, remplacement par motif (propriétés gardées), coque creuse |
| `cloner` | copie avec miroir (`x`/`z`) puis rotation (0/90/180/270, horaire vu du dessus) |
| `annuler` | remet exactement l'état d'avant une opération (blocs + blocs-entités) |
| `journal` | dernières opérations |
| `cliche` / `diff` | instantané nommé d'une boîte, comparaison au monde actuel |
| `rendu` | PNG vue de dessus ou élévation nord/sud/est/ouest, couleurs de carte, légende |
| `travail` | relire un travail long |

Conventions : coordonnées du monde `[x, y, z]`, boîtes bornes incluses ; ordre des tableaux de blocs
y puis z puis x ; états écrits comme pour `/setblock`, NBT compris
(`minecraft:chest[facing=north]{Items:[…]}`). Chaque écriture rend un identifiant d'`operation`.

Modes d'écriture : `rapide` (défaut, = `/setblock … strict` : aucun voisin prévenu, rien ne coule ni
ne tombe, rien ne se raccorde tout seul — poser les états exacts) ; `voisins` (= `/setblock` normal).

L'API elle-même (routes, corps JSON) est décrite par `GET /etat` et par la Javadoc de `Porte`.
`POST /verifier/transformations` passe tous les états du registre (mods compris) aux identités
miroir²=id, rotation⁴=id, miroir x+180°=miroir z, et liste les blocs qui se cloneraient mal.

## Fichiers côté serveur

`<monde>/lanterne-chantier/operations.jsonl` (trace de chaque écriture et de chaque jeton refusé),
`annulation/<id>.nbt` (état d'avant, ~1,6 Mo par million de blocs), `cliches/<nom>.nbt`. Rien n'est
purgé automatiquement.

## Essai de bout en bout

Sur un serveur **jetable** uniquement (il écrit dans des zones fixes) :

```bash
LANTERNE_CHANTIER_URL=http://127.0.0.1:25591 LANTERNE_CHANTIER_JETON=... \
LANTERNE_RCON_PORT=25577 LANTERNE_RCON_MDP=... python essai_bout_en_bout.py sortie
```
