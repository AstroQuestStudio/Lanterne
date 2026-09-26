#!/usr/bin/env python3
"""Serveur MCP (stdio) pour l'API de construction de Lanterne (« chantier »).

Aucune dépendance : bibliothèque standard seule (Python 3.9+). Parle JSON-RPC 2.0 sur stdin/stdout,
un message par ligne, comme le veut le transport stdio de MCP.

Variables d'environnement :
  LANTERNE_CHANTIER_URL    adresse de l'API (défaut http://127.0.0.1:25590 — le bout local du tunnel SSH)
  LANTERNE_CHANTIER_JETON  le jeton (réglage « jeton » de config/lanterne-chantier.toml sur le serveur)

Essai en ligne de commande, sans client MCP :
  python serveur_mcp.py --appel chantier_etat '{}'
  python serveur_mcp.py --appel rendu '{"de":[0,60,0],"a":[63,120,63],"vue":"dessus","fichier":"vue.png"}'
"""
from __future__ import annotations

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

VERSION = "1.0.0"
URL = os.environ.get("LANTERNE_CHANTIER_URL", "http://127.0.0.1:25590").rstrip("/")
JETON = os.environ.get("LANTERNE_CHANTIER_JETON", "")


class ErreurApi(Exception):
    pass


# ---------------------------------------------------------------------------------------------------
# Client HTTP

def appeler(methode: str, chemin: str, corps: dict | None = None, delai: float = 700) -> tuple[int, bytes, dict]:
    if not JETON:
        raise ErreurApi("LANTERNE_CHANTIER_JETON n'est pas défini dans l'environnement du serveur MCP")
    donnees = None if corps is None else json.dumps(corps, separators=(",", ":")).encode("utf-8")
    req = urllib.request.Request(URL + chemin, data=donnees, method=methode)
    req.add_header("Authorization", "Bearer " + JETON)
    if donnees is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=delai) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        corps_err = e.read()
        try:
            msg = json.loads(corps_err).get("erreur", corps_err.decode("utf-8", "replace"))
        except Exception:
            msg = corps_err.decode("utf-8", "replace")
        raise ErreurApi(f"HTTP {e.code} : {msg}") from None
    except urllib.error.URLError as e:
        raise ErreurApi(f"API injoignable sur {URL} ({e.reason}). Le tunnel SSH est-il ouvert ? "
                        "api_construction=true et jeton renseigné côté serveur ?") from None


def travail(chemin: str, corps: dict) -> tuple[bytes, dict]:
    """POST puis, si l'API répond 202 (pas fini), relit /travail/<id> jusqu'à la fin."""
    corps = dict(corps)
    corps.setdefault("attendre", 120)
    code, b, h = appeler("POST", chemin, corps)
    debut = time.time()
    while code == 202:
        tid = json.loads(b)["travail"]
        if time.time() - debut > 3600:
            raise ErreurApi(f"travail {tid} toujours en cours après une heure")
        code, b, h = appeler("POST", "/travail/" + tid, {"attendre": 60})
    return b, h


def entete(h: dict, nom: str) -> str | None:
    """En-tête sans égard à la casse (le serveur HTTP du JDK écrit « X-chantier »)."""
    for k, v in h.items():
        if k.lower() == nom.lower():
            return v
    return None


def post_json(chemin: str, corps: dict) -> dict:
    b, _ = travail(chemin, corps)
    return json.loads(b)


# ---------------------------------------------------------------------------------------------------
# Mise en forme

def rle_vers_liste(rle: list[int]) -> list[int]:
    out: list[int] = []
    for i in range(0, len(rle), 2):
        out.extend([rle[i]] * rle[i + 1])
    return out


SYMBOLES = ".#@%&*+=-:ox$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnpqrstuvwyz0123456789"


def couches(rep: dict) -> str:
    """Rend une lecture de blocs en couches ASCII (une grille par y, nord en haut, ouest à gauche)."""
    sx, sy, sz = rep["boite"]["taille"]
    de = rep["boite"]["de"]
    pal = rep["palette"]
    blocs = rle_vers_liste(rep["rle"]) if "rle" in rep else rep["blocs"]
    compte: dict[int, int] = {}
    for v in blocs:
        compte[v] = compte.get(v, 0) + 1
    air = {i for i, p in enumerate(pal) if p in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air")}
    ordre = sorted((i for i in compte if i not in air), key=lambda i: -compte[i])
    sym = {i: SYMBOLES[1 + k] if k + 1 < len(SYMBOLES) else "?" for k, i in enumerate(ordre)}
    for i in air:
        sym[i] = "."
    lignes = ["Légende (symbole = état, nombre) :"]
    for i in ordre:
        lignes.append(f"  {sym[i]} = {pal[i]}  ({compte[i]})")
    lignes.append("  . = air")
    lignes.append(f"Grilles : x de {de[0]} (gauche) à {de[0] + sx - 1}, z de {de[2]} (haut) à {de[2] + sz - 1}.")
    for y in range(sy - 1, -1, -1):
        lignes.append(f"--- y = {de[1] + y} ---")
        for z in range(sz):
            base = (y * sz + z) * sx
            lignes.append("".join(sym[blocs[base + x]] for x in range(sx)))
    return "\n".join(lignes)


def resume_lecture(rep: dict) -> str:
    pal = rep.get("palette", [])
    blocs = rle_vers_liste(rep["rle"]) if "rle" in rep else rep.get("blocs", [])
    compte: dict[int, int] = {}
    for v in blocs:
        compte[v] = compte.get(v, 0) + 1
    lignes = [f"Boîte {rep['boite']['de']} → {rep['boite']['a']} (taille {rep['boite']['taille']}), "
              f"{len(blocs)} blocs, {len(pal)} états distincts, lue en {rep.get('duree_ms')} ms "
              f"({rep.get('ticks')} ticks, {rep.get('temps_serveur_ms')} ms de temps serveur)."]
    for i in sorted(compte, key=lambda i: -compte[i])[:40]:
        lignes.append(f"  {compte[i]:>8}  {pal[i]}")
    if len(compte) > 40:
        lignes.append(f"  … et {len(compte) - 40} autres états")
    if rep.get("bloc_entites"):
        lignes.append(f"{len(rep['bloc_entites'])} bloc-entité(s) (demander format_sortie=brut ou fichier= pour le SNBT).")
    return "\n".join(lignes)


def texte(o) -> dict:
    return {"type": "text", "text": o if isinstance(o, str) else json.dumps(o, ensure_ascii=False, indent=1)}


def ecrire_fichier(chemin: str, donnees: bytes) -> str:
    chemin = os.path.abspath(chemin)
    os.makedirs(os.path.dirname(chemin) or ".", exist_ok=True)
    with open(chemin, "wb") as f:
        f.write(donnees)
    return chemin


# ---------------------------------------------------------------------------------------------------
# Les outils

XYZ = {"type": "array", "items": {"type": "integer"}, "minItems": 3, "maxItems": 3}
DIM = {"type": "string", "description": "Dimension (défaut minecraft:overworld ; aussi minecraft:the_nether, minecraft:the_end)."}
BOITE = {
    "de": {**XYZ, "description": "Un coin de la boîte [x, y, z] (bornes incluses)."},
    "a": {**XYZ, "description": "Le coin opposé [x, y, z] (bornes incluses)."},
    "dim": DIM,
}
ECRITURE = {
    "mode": {"type": "string", "enum": ["rapide", "voisins"],
             "description": "rapide (défaut) = /setblock strict : aucune mise à jour de voisin, rien ne coule/tombe/se raccorde, "
                            "le plus rapide. voisins = /setblock normal : les voisins réagissent (raccords, redstone, eau)."},
    "seulement_air": {"type": "boolean", "description": "Ne poser que là où il y a de l'air (ne rien écraser). Défaut false."},
    "rayon_joueurs": {"type": "integer", "description": "Refuser si un joueur est à moins de N blocs de la zone (0 = réglage serveur)."},
}


def outil(nom: str, description: str, proprietes: dict, requis: list[str]):
    def deco(f):
        OUTILS[nom] = {"def": {"name": nom, "description": description,
                               "inputSchema": {"type": "object", "properties": proprietes, "required": requis}},
                       "f": f}
        return f
    return deco


OUTILS: dict[str, dict] = {}


@outil("chantier_etat",
       "État du serveur et de l'API : version de Lanterne, tick moyen (ms), joueurs connectés (nom, dimension, position), "
       "travaux en file, dimensions, limites (blocs_max, budget_ms). À appeler en premier pour vérifier le tunnel.",
       {}, [])
def t_etat(a):
    _, b, _ = appeler("GET", "/etat")
    return [texte(json.loads(b))]


@outil("lire_blocs",
       "Lit tous les blocs d'une boîte (palette d'états + tableau, ordre y puis z puis x : "
       "index = ((y-minY)*sz + (z-minZ))*sx + (x-minX)). format_sortie : 'resume' (défaut : comptes par état), "
       "'couches' (grilles ASCII par y avec légende — idéal pour relire une petite structure, ≤ 64×64 de base conseillé), "
       "'brut' (JSON complet de l'API). 'fichier' enregistre le JSON complet localement (pour traitement par script).",
       {**BOITE, "nbt": {"type": "boolean", "description": "Inclure les blocs-entités en SNBT."},
        "format_sortie": {"type": "string", "enum": ["resume", "couches", "brut"]},
        "fichier": {"type": "string", "description": "Chemin local où écrire la réponse JSON complète."}},
       ["de", "a"])
def t_lire_blocs(a):
    corps = {k: a[k] for k in ("de", "a", "dim", "nbt") if k in a}
    b, _ = travail("/lire/blocs", corps)
    rep = json.loads(b)
    sortie = []
    if a.get("fichier"):
        sortie.append(texte("JSON complet écrit dans " + ecrire_fichier(a["fichier"], b)))
    f = a.get("format_sortie", "resume")
    if f == "brut":
        sortie.append(texte(rep))
    elif f == "couches":
        sortie.append(texte(couches(rep)))
    else:
        sortie.append(texte(resume_lecture(rep)))
    return sortie


@outil("lire_bloc_entites",
       "Liste les blocs-entités d'une boîte (coffres, panneaux, bannières…) avec leur état et leurs données en SNBT.",
       {**BOITE, "fichier": {"type": "string", "description": "Chemin local où écrire la réponse JSON."}}, ["de", "a"])
def t_lire_be(a):
    b, _ = travail("/lire/bloc_entites", {k: a[k] for k in ("de", "a", "dim") if k in a})
    rep = json.loads(b)
    if a.get("fichier"):
        return [texte("JSON écrit dans " + ecrire_fichier(a["fichier"], b) + f" ({len(rep['bloc_entites'])} bloc-entités)")]
    return [texte(rep["bloc_entites"])]


@outil("lire_entites",
       "Liste les entités d'une boîte (type, uuid, position, orientation, nom ; SNBT complet si nbt=true). "
       "Charge les chunks et attend leurs entités (limite 1024 chunks).",
       {**BOITE, "nbt": {"type": "boolean"}}, ["de", "a"])
def t_lire_entites(a):
    return [texte(post_json("/lire/entites", {k: a[k] for k in ("de", "a", "dim", "nbt") if k in a}))]


@outil("lire_hauteurs",
       "Carte des hauteurs d'une zone : pour chaque colonne (ordre z puis x), y du plus haut bloc non-air ENTRE de.y et a.y "
       "(type 'premier_non_air', défaut) ou la carte du jeu (world_surface, motion_blocking, motion_blocking_no_leaves, "
       "ocean_floor — y ignoré). Renvoie aussi le bloc trouvé. Utile pour poser des fondations au sol.",
       {**BOITE, "type": {"type": "string", "enum": ["premier_non_air", "world_surface", "motion_blocking",
                                                   "motion_blocking_no_leaves", "ocean_floor"]},
        "format_sortie": {"type": "string", "enum": ["grille", "brut"], "description": "grille (défaut) : texte lisible ; brut : JSON."}},
       ["de", "a"])
def t_hauteurs(a):
    rep = post_json("/lire/hauteurs", {k: a[k] for k in ("de", "a", "dim", "type") if k in a})
    if a.get("format_sortie") == "brut":
        return [texte(rep)]
    sx, _, sz = rep["boite"]["taille"]
    h = rep["hauteurs"]
    lignes = [f"Hauteurs ({rep['type']}), x {rep['boite']['de'][0]}→{rep['boite']['a'][0]} en colonnes, "
              f"z {rep['boite']['de'][2]}→{rep['boite']['a'][2]} en lignes ; min {min(h)}, max {max(h)}."]
    if sx * sz <= 4096:
        for z in range(sz):
            lignes.append(" ".join(f"{v:4d}" for v in h[z * sx:(z + 1) * sx]))
    else:
        lignes.append("(zone trop grande pour la grille : format_sortie=brut)")
    pal = rep["palette"]
    compte: dict[int, int] = {}
    for v in rep["blocs"]:
        compte[v] = compte.get(v, 0) + 1
    lignes.append("Blocs de surface : " + ", ".join(f"{pal[i] if i >= 0 else 'vide'} ×{n}"
                                                   for i, n in sorted(compte.items(), key=lambda e: -e[1])[:12]))
    return [texte("\n".join(lignes))]


@outil("ecrire_blocs",
       "Pose une liste de blocs en masse (des dizaines de milliers par appel), en une opération ANNULABLE. "
       "Trois façons de donner les blocs : 'blocs' = [[x,y,z,\"état\"], …] ; ou 'palette' = [\"état\", …] + 'plat' = "
       "[x,y,z,indice, x,y,z,indice, …] ; ou 'fichier' = chemin local d'un JSON {\"palette\": [...], \"blocs\": [x,y,z,i,...]}. "
       "Un état s'écrit comme pour /setblock, NBT compris : minecraft:oak_stairs[facing=east,half=top] ou "
       "minecraft:chest[facing=north]{Items:[{Slot:0b,id:\"minecraft:torch\",count:16}]}. Renvoie l'identifiant d'opération "
       "(pour annuler) et les mesures (durée, temps serveur, pire tick).",
       {"blocs": {"type": "array", "items": {"type": "array"}},
        "palette": {"type": "array", "items": {"type": "string"}},
        "plat": {"type": "array", "items": {"type": "integer"}},
        "fichier": {"type": "string"}, "dim": DIM, **ECRITURE},
       [])
def t_ecrire_blocs(a):
    if a.get("fichier"):
        with open(a["fichier"], encoding="utf-8") as f:
            d = json.load(f)
        palette, plat = d["palette"], d["blocs"]
    elif a.get("blocs") is not None:
        palette, idx, plat = [], {}, []
        for x, y, z, e in a["blocs"]:
            if e not in idx:
                idx[e] = len(palette)
                palette.append(e)
            plat += [x, y, z, idx[e]]
    else:
        palette, plat = a.get("palette"), a.get("plat")
        if palette is None or plat is None:
            raise ErreurApi("donner 'blocs', ou 'palette' + 'plat', ou 'fichier'")
    corps = {"palette": palette, "blocs": plat}
    for k in ("dim", "mode", "seulement_air", "rayon_joueurs"):
        if k in a:
            corps[k] = a[k]
    return [texte(post_json("/ecrire/blocs", corps))]


@outil("remplir",
       "Remplit une boîte d'un même état (comme /fill, sans limite de 32 768 et annulable). "
       "Avec seulement_air=true, ne remplit que l'air.",
       {**BOITE, "bloc": {"type": "string", "description": "État à poser, ex. minecraft:stone_bricks"}, **ECRITURE},
       ["de", "a", "bloc"])
def t_remplir(a):
    return [texte(post_json("/ecrire/remplir", a))]


@outil("remplacer",
       "Dans une boîte, remplace les blocs qui correspondent au motif par un autre état (annulable). Motif = état partiel "
       "(minecraft:oak_stairs, minecraft:oak_stairs[half=top]) ou étiquette (#minecraft:logs). Avec garder_proprietes "
       "(défaut true), les propriétés communes (facing, half, shape, axis…) sont conservées : chêne → sapin sans rien réorienter.",
       {**BOITE, "motif": {"type": "string"}, "par": {"type": "string"},
        "garder_proprietes": {"type": "boolean"}, **ECRITURE},
       ["de", "a", "motif", "par"])
def t_remplacer(a):
    return [texte(post_json("/ecrire/remplacer", a))]


@outil("creuser",
       "Rend une boîte creuse : garde une coque d'épaisseur donnée (défaut 1) et remplit l'intérieur d'air "
       "(ou de 'remplissage'). Annulable.",
       {**BOITE, "epaisseur": {"type": "integer"}, "remplissage": {"type": "string"}, **ECRITURE},
       ["de", "a"])
def t_creuser(a):
    return [texte(post_json("/ecrire/creuser", a))]


@outil("cloner",
       "Clone une boîte vers 'vers' (coin minimal nord-ouest-bas de la destination), avec miroir puis rotation. "
       "Les états sont transformés correctement (facing, axis, shape des escaliers, hinge des portes, type des coffres "
       "doubles, rails…) et les blocs-entités recopiés (contenu des coffres, texte des panneaux). "
       "miroir : 'aucun' | 'x' (inverse les x, gauche↔droite vu du sud) | 'z' (inverse les z). rotation : 0/90/180/270 "
       "dans le sens horaire vu du dessus. La source est lue entièrement avant d'écrire (chevauchement permis). Annulable.",
       {**BOITE, "vers": {**XYZ, "description": "Coin minimal [x,y,z] de la destination."},
        "miroir": {"type": "string", "enum": ["aucun", "x", "z"]},
        "rotation": {"type": "integer", "enum": [0, 90, 180, 270]},
        "copier_air": {"type": "boolean", "description": "Recopier aussi l'air de la source (défaut true : copie exacte)."},
        "dim_source": DIM, **ECRITURE},
       ["de", "a", "vers"])
def t_cloner(a):
    corps = dict(a)
    if "dim" in corps and "dim_source" not in corps:
        corps["dim_source"] = corps["dim"]
    return [texte(post_json("/ecrire/cloner", corps))]


@outil("annuler",
       "Annule une opération d'écriture : remet exactement les états et les données de blocs-entités d'avant. "
       "L'annulation est elle-même une opération (donc annulable). Refuse une opération déjà annulée sauf forcer=true.",
       {"operation": {"type": "string", "description": "Identifiant rendu par l'écriture (champ 'operation')."},
        "forcer": {"type": "boolean"}, "rayon_joueurs": {"type": "integer"}},
       ["operation"])
def t_annuler(a):
    return [texte(post_json("/annuler", a))]


@outil("journal",
       "Les dernières opérations d'écriture (identifiant, nature, zone, nombre de blocs modifiés, annulée par…).",
       {"n": {"type": "integer", "description": "Combien (défaut 20)."}}, [])
def t_journal(a):
    _, b, _ = appeler("GET", f"/journal?n={int(a.get('n', 20))}")
    return [texte(json.loads(b))]


@outil("cliche",
       "Prend un cliché nommé d'une boîte (tous les blocs + blocs-entités), stocké sur le serveur, pour la comparer plus tard.",
       {**BOITE, "nom": {"type": "string", "description": "Nom (lettres, chiffres, _ . -)."}}, ["de", "a", "nom"])
def t_cliche(a):
    return [texte(post_json("/cliche", a))]


@outil("diff",
       "Compare le monde actuel à un cliché ('cliche') ou à l'état d'avant une opération ('operation'). "
       "Renvoie le nombre de différences (états, données de blocs-entités) et des exemples.",
       {"cliche": {"type": "string"}, "operation": {"type": "string"},
        "nbt": {"type": "boolean", "description": "Comparer aussi les données des blocs-entités (défaut true)."},
        "limite": {"type": "integer", "description": "Exemples au plus (défaut 50)."}}, [])
def t_diff(a):
    return [texte(post_json("/diff", a))]


@outil("rendu",
       "Image PNG d'une boîte rendue côté serveur (couleurs de carte du jeu), pour vérifier visuellement sans client. "
       "vue 'dessus' : nord en haut, ombrage de relief comme les cartes (pour un plan d'étage, mettre a.y juste au-dessus "
       "du plancher). vues 'nord'|'sud'|'est'|'ouest' : élévation vue DEPUIS ce côté, plus sombre = plus loin ; "
       "'profondeur' limite la tranche vue (ex. 1 = seulement la façade). Verre/air traversés. "
       "'grille' = trait tous les N blocs (coordonnées du monde, 16 = limites de chunk). Renvoie l'image et une légende.",
       {**BOITE, "vue": {"type": "string", "enum": ["dessus", "nord", "sud", "est", "ouest"]},
        "profondeur": {"type": "integer"}, "echelle": {"type": "integer", "description": "Pixels par bloc (défaut auto ~1024 px)."},
        "grille": {"type": "integer"}, "fond": {"type": "string", "description": "Couleur hexa (défaut d8e4ec) ou 'transparent'."},
        "fichier": {"type": "string", "description": "Chemin local où enregistrer aussi le PNG."}},
       ["de", "a"])
def t_rendu(a):
    corps = {k: a[k] for k in ("de", "a", "dim", "vue", "profondeur", "echelle", "grille", "fond") if k in a}
    png, h = travail("/rendu", corps)
    meta = json.loads(base64.b64decode(entete(h, "X-Chantier") or "e30="))
    info = {k: meta.get(k) for k in ("vue", "orientation", "largeur_px", "hauteur_px", "echelle", "profondeur",
                                    "boite", "legende", "duree_ms", "temps_serveur_ms")}
    sortie = [{"type": "image", "data": base64.b64encode(png).decode("ascii"), "mimeType": "image/png"}]
    if a.get("fichier"):
        info["fichier"] = ecrire_fichier(a["fichier"], png)
    sortie.append(texte(info))
    return sortie


@outil("travail",
       "Relit un travail long resté en cours (l'API renvoie son identifiant quand elle n'a pas fini à temps).",
       {"id": {"type": "string"}}, ["id"])
def t_travail(a):
    _, b, _ = appeler("POST", "/travail/" + a["id"], {"attendre": 30})
    return [texte(json.loads(b))]


# ---------------------------------------------------------------------------------------------------
# Transport MCP (stdio, JSON-RPC 2.0)

def envoyer(msg: dict) -> None:
    sys.stdout.buffer.write(json.dumps(msg, ensure_ascii=False).encode("utf-8") + b"\n")
    sys.stdout.buffer.flush()


def traiter(msg: dict) -> dict | None:
    methode = msg.get("method")
    mid = msg.get("id")
    if mid is None:
        return None  # notification
    if methode == "initialize":
        version = (msg.get("params") or {}).get("protocolVersion", "2025-06-18")
        return {"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": version,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "lanterne-chantier", "version": VERSION},
            "instructions": "Outils de construction pour le serveur Minecraft (mod Lanterne). Coordonnées du monde en "
                            "[x, y, z], boîtes bornes incluses. Toute écriture renvoie un identifiant d'opération annulable "
                            "avec 'annuler'. Vérifier avec 'rendu' (image) ou 'lire_blocs' format_sortie=couches."}}
    if methode == "ping":
        return {"jsonrpc": "2.0", "id": mid, "result": {}}
    if methode == "tools/list":
        return {"jsonrpc": "2.0", "id": mid, "result": {"tools": [o["def"] for o in OUTILS.values()]}}
    if methode == "tools/call":
        p = msg.get("params") or {}
        o = OUTILS.get(p.get("name"))
        if o is None:
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32602, "message": f"outil inconnu {p.get('name')}"}}
        try:
            contenu = o["f"](p.get("arguments") or {})
            return {"jsonrpc": "2.0", "id": mid, "result": {"content": contenu}}
        except ErreurApi as e:
            return {"jsonrpc": "2.0", "id": mid, "result": {"content": [texte(str(e))], "isError": True}}
        except Exception as e:  # noqa: BLE001 — une erreur d'outil se rend, elle ne tue pas le serveur
            return {"jsonrpc": "2.0", "id": mid, "result": {"content": [texte(f"{type(e).__name__} : {e}")], "isError": True}}
    return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"méthode inconnue {methode}"}}


def boucle() -> None:
    for ligne in sys.stdin.buffer:
        ligne = ligne.strip()
        if not ligne:
            continue
        try:
            msg = json.loads(ligne)
        except json.JSONDecodeError:
            envoyer({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "JSON illisible"}})
            continue
        lot = msg if isinstance(msg, list) else [msg]
        for m in lot:
            r = traiter(m)
            if r is not None:
                envoyer(r)


def main() -> None:
    if len(sys.argv) >= 3 and sys.argv[1] == "--appel":
        sys.stdout.reconfigure(encoding="utf-8")
        args = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        o = OUTILS[sys.argv[2]]
        try:
            for c in o["f"](args):
                if c["type"] == "text":
                    print(c["text"])
                else:
                    print(f"[{c['type']} {c.get('mimeType')} : {len(c['data']) * 3 // 4} octets]")
        except ErreurApi as e:
            print("ERREUR :", e)
            sys.exit(1)
        return
    boucle()


if __name__ == "__main__":
    main()
