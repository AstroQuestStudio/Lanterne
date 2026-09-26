#!/usr/bin/env python3
"""Essai de bout en bout de l'API chantier, sur un serveur JETABLE (jamais sur le serveur des joueurs).

  LANTERNE_CHANTIER_URL=http://127.0.0.1:25591 LANTERNE_CHANTIER_JETON=... python essai_bout_en_bout.py [dossier_sortie]

Écrit dans des zones fixes (voir ZONE_*) : à ne lancer que sur un monde de test.
Chaque étape affiche OK/ÉCHEC ; le code de sortie vaut le nombre d'échecs.
"""
from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import serveur_mcp as m  # noqa: E402

sys.stdout.reconfigure(encoding="utf-8")
SORTIE = sys.argv[1] if len(sys.argv) > 1 else "sortie-essai"
os.makedirs(SORTIE, exist_ok=True)
ECHECS = 0
Y = 150


def rcon(cmd: str) -> str:
    """RCON minimal, pour les rares gestes que l'API ne fait pas (invoquer une entité témoin)."""
    import socket
    import struct
    s = socket.create_connection(("127.0.0.1", int(os.environ["LANTERNE_RCON_PORT"])), timeout=30)

    def envoi(i, t, corps):
        b = corps.encode("utf-8")
        s.sendall(struct.pack("<iii", len(b) + 10, i, t) + b + b"\0\0")

    def recu():
        n = struct.unpack("<i", s.recv(4))[0]
        d = b""
        while len(d) < n:
            d += s.recv(n - len(d))
        return d[8:-2].decode("utf-8", "replace")

    envoi(1, 3, os.environ.get("LANTERNE_RCON_MDP", ""))
    recu()
    envoi(2, 2, cmd)
    r = recu()
    s.close()
    return r


def verifier(nom: str, cond: bool, detail: str = "") -> None:
    global ECHECS
    print(("OK     " if cond else "ÉCHEC  ") + nom + (f" — {detail}" if detail else ""), flush=True)
    if not cond:
        ECHECS += 1


def lire(de, a) -> dict:
    return m.post_json("/lire/blocs", {"de": de, "a": a, "nbt": True})


def grille(rep: dict) -> dict[tuple[int, int, int], str]:
    sx, sy, sz = rep["boite"]["taille"]
    x0, y0, z0 = rep["boite"]["de"]
    b = m.rle_vers_liste(rep["rle"])
    out = {}
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                out[(x0 + x, y0 + y, z0 + z)] = rep["palette"][b[(y * sz + z) * sx + x]]
    return out


def relatif(rep: dict) -> list[str]:
    """Les états dans l'ordre y,z,x — pour comparer deux boîtes de même taille."""
    b = m.rle_vers_liste(rep["rle"])
    return [rep["palette"][i] for i in b]


def props(etat: str) -> dict[str, str]:
    if "[" not in etat:
        return {}
    return dict(p.split("=") for p in etat[etat.index("[") + 1:etat.index("]")].split(","))


def bloc(etat: str) -> str:
    return etat.split("[")[0].split("{")[0]


# -------------------------------------------------------------------------------------------------
print("== 0. Jeton ==")
jeton = m.JETON
m.JETON = "mauvais-jeton-mauvais-jeton"
t0 = time.time()
try:
    m.appeler("GET", "/etat")
    verifier("jeton faux refusé", False, "accepté !")
except m.ErreurApi as e:
    verifier("jeton faux refusé", "401" in str(e), f"{e} en {time.time() - t0:.2f} s")
m.JETON = jeton
etat = json.loads(m.appeler("GET", "/etat")[1])
verifier("jeton juste accepté", etat.get("api") == "lanterne-chantier", f"version {etat.get('version')}, joueurs {etat.get('joueurs')}")
try:
    m.post_json("/lire/blocs", {"de": [0, -64, 0], "a": [1, 400, 1]})
    verifier("boîte hors du monde refusée", False)
except m.ErreurApi as e:
    verifier("boîte hors du monde refusée", "400" in str(e), str(e))

# -------------------------------------------------------------------------------------------------
print("== 1. Écriture de 50 000 blocs, mesure du tick ==")
DE50, A50 = [0, Y, 0], [99, Y + 4, 99]
m.post_json("/ecrire/remplir", {"de": DE50, "a": A50, "bloc": "minecraft:air"})  # zone vierge (annulable aussi)
cl = m.post_json("/cliche", {"de": DE50, "a": A50, "nom": "avant-50k"})
verifier("cliché avant", cl.get("blocs") == 50_000, f"{cl.get('blocs')} blocs en {cl.get('duree_ms')} ms")
motifs = ["minecraft:stone_bricks", "minecraft:oak_planks", "minecraft:glass",
          "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
          "minecraft:oak_stairs[facing=north,half=top,shape=inner_left,waterlogged=false]",
          "minecraft:oak_log[axis=x]", "minecraft:white_wool", "minecraft:red_wool", "minecraft:lantern[hanging=true,waterlogged=false]",
          "minecraft:chest[facing=south,type=single,waterlogged=false]{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}"]
plat = []
for y in range(Y, Y + 5):
    for z in range(100):
        for x in range(100):
            plat += [x, y, z, (x * 7 + z * 3 + y) % len(motifs)]
echantillons = []
arret = threading.Event()


def sonde():
    while not arret.is_set():
        try:
            e = json.loads(m.appeler("GET", "/etat")[1])
            echantillons.append((e["tick_moyen_ms"], e["pire_tick_100_ms"]))
        except Exception:
            pass
        time.sleep(0.25)


base = json.loads(m.appeler("GET", "/etat")[1])
th = threading.Thread(target=sonde, daemon=True)
th.start()
t0 = time.time()
r50 = m.post_json("/ecrire/blocs", {"palette": motifs, "blocs": plat})
duree = time.time() - t0
time.sleep(1.0)
arret.set()
th.join()
op50 = r50["operation"]
verifier("50 000 blocs écrits", r50["modifies"] == 50_000,
         f"{r50['modifies']} modifiés en {duree:.2f} s de bout en bout (serveur : {r50['duree_ms']} ms, "
         f"{r50['ticks']} ticks, {r50['temps_serveur_ms']} ms de temps serveur, pire tick chantier {r50['pire_tick_ms']} ms, "
         f"attentes de chunk {r50['attentes_chunk']})")
pire = max((p for _, p in echantillons), default=0)
moy = max((a for a, _ in echantillons), default=0)
print(f"       tick de référence : moyen {base['tick_moyen_ms']} ms, pire/100 {base['pire_tick_100_ms']} ms ; "
      f"pendant l'écriture : moyen max {moy} ms, pire tick {pire} ms ({len(echantillons)} sondes)")
# Le pas du chantier lui-même doit tenir son budget (à la préemption près : ce poste n'est pas dédié).
verifier("pas du chantier ≤ budget + 20 ms", r50["pire_tick_ms"] <= etat["budget_ms"] + 20, f"pire pas {r50['pire_tick_ms']} ms")
print(f"       (info) pire tick SERVEUR pendant l'écriture {pire} ms, contre {base['pire_tick_100_ms']} ms avant")
d = m.post_json("/diff", {"cliche": "avant-50k", "limite": 3})
verifier("diff après écriture ≠ 0", d["differences"] == 50_000, f"{d['differences']} différences")
be = m.post_json("/lire/bloc_entites", {"de": DE50, "a": A50})["bloc_entites"]
verifier("coffres écrits avec leur contenu", len(be) == 5_000 and "diamond" in be[0]["snbt"],
         f"{len(be)} coffres, ex. {be[0]['snbt'][:90]}")

print("== 2. Annulation exacte ==")
u = m.post_json("/annuler", {"operation": op50})
verifier("annulation", u["modifies"] == 50_000, f"{u['modifies']} restaurés en {u['duree_ms']} ms, pire tick {u['pire_tick_ms']} ms")
d = m.post_json("/diff", {"cliche": "avant-50k", "limite": 5})
verifier("diff cliché après annulation = 0 (états + NBT)", d["identique"], json.dumps(d["exemples"])[:300])
try:
    m.post_json("/annuler", {"operation": op50})
    verifier("double annulation refusée", False)
except m.ErreurApi as e:
    verifier("double annulation refusée", "409" in str(e), str(e))
# Annuler l'annulation = refaire ; puis la réannuler, et revérifier.
redo = m.post_json("/annuler", {"operation": u["operation"]})
d = m.post_json("/diff", {"cliche": "avant-50k", "limite": 1})
verifier("annuler l'annulation refait l'écriture", d["differences"] == 50_000 and redo["modifies"] == 50_000)
m.post_json("/annuler", {"operation": redo["operation"]})
d = m.post_json("/diff", {"cliche": "avant-50k", "limite": 1})
verifier("… et la réannuler rend le cliché", d["identique"])

# Annulation d'un remplacement de coffres pleins : le contenu revient.
print("== 3. Remplacer, creuser, seulement_air, garder_proprietes ==")
ZB = [300, Y, 300]
m.post_json("/ecrire/blocs", {"palette": [
    "minecraft:oak_stairs[facing=west,half=top,shape=outer_right,waterlogged=false]",
    "minecraft:chest[facing=east,type=single,waterlogged=false]{Items:[{Slot:4b,id:\"minecraft:emerald\",count:9}]}"],
    "blocs": [300, Y, 300, 0, 301, Y, 300, 1]})
rp = m.post_json("/ecrire/remplacer", {"de": ZB, "a": [301, Y, 300], "motif": "minecraft:oak_stairs", "par": "minecraft:spruce_stairs"})
g = grille(lire(ZB, [301, Y, 300]))
verifier("remplacer garde facing/half/shape", g[(300, Y, 300)] == "minecraft:spruce_stairs[facing=west,half=top,shape=outer_right,waterlogged=false]",
         g[(300, Y, 300)])
rc = m.post_json("/ecrire/remplacer", {"de": ZB, "a": [301, Y, 300], "motif": "minecraft:chest", "par": "minecraft:stone"})
m.post_json("/annuler", {"operation": rc["operation"]})
be = m.post_json("/lire/bloc_entites", {"de": ZB, "a": [301, Y, 300]})["bloc_entites"]
verifier("coffre remplacé puis annulé : contenu rendu", len(be) == 1 and "emerald" in be[0]["snbt"] and "count:9" in be[0]["snbt"],
         be[0]["snbt"][:120] if be else "aucun")
m.post_json("/ecrire/remplir", {"de": [310, Y, 300], "a": [319, Y + 9, 309], "bloc": "minecraft:stone"})
cr = m.post_json("/ecrire/creuser", {"de": [310, Y, 300], "a": [319, Y + 9, 309], "epaisseur": 1})
verifier("creuser vide l'intérieur 8×8×8", cr["modifies"] == 512, f"{cr['modifies']}")
sa = m.post_json("/ecrire/remplir", {"de": [310, Y, 300], "a": [319, Y + 9, 309], "bloc": "minecraft:glowstone", "seulement_air": True})
verifier("seulement_air ne touche que l'air", sa["modifies"] == 512 and sa["ecartes"] == 488, f"modifiés {sa['modifies']}, écartés {sa['ecartes']}")

# -------------------------------------------------------------------------------------------------
print("== 4. Clonage miroir/rotation d'une structure orientée ==")
SX0, SZ0 = 200, 200   # structure source 10 × 6 × 10
src = {}
for x in range(10):
    for z in range(10):
        src[(SX0 + x, Y, SZ0 + z)] = "minecraft:stone_bricks"
src[(SX0, Y + 1, SZ0)] = "minecraft:gold_block"          # repère coin nord-ouest
src[(SX0 + 9, Y + 1, SZ0)] = "minecraft:diamond_block"   # repère coin nord-est
for i, f in enumerate(["north", "east", "south", "west"]):
    src[(SX0 + 2 + i, Y + 1, SZ0 + 1)] = f"minecraft:oak_stairs[facing={f},half=bottom,shape=straight,waterlogged=false]"
    src[(SX0 + 2 + i, Y + 2, SZ0 + 1)] = f"minecraft:oak_stairs[facing={f},half=top,shape=inner_left,waterlogged=false]"
    src[(SX0 + 2 + i, Y + 3, SZ0 + 1)] = f"minecraft:oak_stairs[facing={f},half=bottom,shape=outer_right,waterlogged=false]"
# coffre double tourné vers le nord : gauche en x, droite en x+1 (le partenaire d'un « left » est à l'est)
src[(SX0 + 2, Y + 1, SZ0 + 5)] = "minecraft:chest[facing=north,type=left,waterlogged=false]{Items:[{Slot:0b,id:\"minecraft:gold_ingot\",count:5}]}"
src[(SX0 + 3, Y + 1, SZ0 + 5)] = "minecraft:chest[facing=north,type=right,waterlogged=false]{Items:[{Slot:1b,id:\"minecraft:apple\",count:2}]}"
src[(SX0 + 6, Y + 1, SZ0 + 5)] = "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]"
src[(SX0 + 6, Y + 2, SZ0 + 5)] = "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]"
src[(SX0 + 1, Y + 1, SZ0 + 7)] = "minecraft:oak_log[axis=x]"
src[(SX0 + 2, Y + 1, SZ0 + 7)] = "minecraft:oak_log[axis=z]"
src[(SX0 + 4, Y + 1, SZ0 + 7)] = "minecraft:rail[shape=north_east,waterlogged=false]"
src[(SX0 + 5, Y + 1, SZ0 + 7)] = "minecraft:red_bed[facing=south,occupied=false,part=foot]"
src[(SX0 + 5, Y + 1, SZ0 + 8)] = "minecraft:red_bed[facing=south,occupied=false,part=head]"
src[(SX0 + 7, Y + 1, SZ0 + 7)] = "minecraft:oak_sign[rotation=4,waterlogged=false]{front_text:{messages:[\"Nord\",\"\",\"\",\"\"]}}"
src[(SX0 + 8, Y + 2, SZ0 + 7)] = "minecraft:wall_torch[facing=east]"
src[(SX0 + 8, Y + 1, SZ0 + 7)] = "minecraft:cobblestone"
src[(SX0 + 7, Y + 1, SZ0 + 2)] = "minecraft:oak_trapdoor[facing=north,half=top,open=true,powered=false,waterlogged=false]"
src[(SX0 + 8, Y + 1, SZ0 + 2)] = "minecraft:lever[face=wall,facing=south,powered=false]"
DEs, As = [SX0, Y, SZ0], [SX0 + 9, Y + 5, SZ0 + 9]
m.post_json("/ecrire/remplir", {"de": DEs, "a": As, "bloc": "minecraft:air"})
PAL = list(dict.fromkeys(src.values()))
m.post_json("/ecrire/blocs", {"palette": PAL, "blocs": [v for (x, y, z), e in src.items() for v in (x, y, z, PAL.index(e))]})
A = lire(DEs, As)
verifier("structure source posée", sum(1 for e in relatif(A) if e != "minecraft:air") == len(src), f"{len(src)} blocs")


def cloner(de, a, vers, miroir="aucun", rot=0):
    return m.post_json("/ecrire/cloner", {"de": de, "a": a, "vers": vers, "miroir": miroir, "rotation": rot})


# (a) miroir x puis miroir x = identité
cloner(DEs, As, [220, Y, 200], "x")
cloner([220, Y, 200], [229, Y + 5, 209], [240, Y, 200], "x")
verifier("miroir x ∘ miroir x = identité", relatif(lire([240, Y, 200], [249, Y + 5, 209])) == relatif(A))
# (b) quatre rotations de 90° = identité
cloner(DEs, As, [200, Y, 220], rot=90)
cloner([200, Y, 220], [209, Y + 5, 229], [220, Y, 220], rot=90)
cloner([220, Y, 220], [229, Y + 5, 229], [240, Y, 220], rot=90)
cloner([240, Y, 220], [249, Y + 5, 229], [260, Y, 220], rot=90)
verifier("4 × rotation 90° = identité", relatif(lire([260, Y, 220], [269, Y + 5, 229])) == relatif(A))
# (c) miroir x + 180° = miroir z
cloner(DEs, As, [200, Y, 240], "x", 180)
cloner(DEs, As, [220, Y, 240], "z", 0)
ga, gz = relatif(lire([200, Y, 240], [209, Y + 5, 249])), relatif(lire([220, Y, 240], [229, Y + 5, 249]))
verifier("miroir x + 180° = miroir z", ga == gz, str([(a, b) for a, b in zip(ga, gz) if a != b][:3]))
# (d) le cas demandé : miroir x + rotation 90°, vérifié élément par élément
cm = cloner(DEs, As, [240, Y, 240], "x", 90)
B = lire([240, Y, 240], [249, Y + 5, 249])
gb = grille(B)


def dest(x, y, z):
    """Position source → destination pour miroir x puis 90° horaire, taille 10×10."""
    xm, zm = 9 - (x - SX0), z - SZ0
    return 240 + (9 - zm), y, 240 + xm


verifier("repère or (NO) → coin attendu", gb[dest(SX0, Y + 1, SZ0)] == "minecraft:gold_block", str(dest(SX0, Y + 1, SZ0)))
verifier("repère diamant (NE) → coin attendu", gb[dest(SX0 + 9, Y + 1, SZ0)] == "minecraft:diamond_block")
# facing : miroir x échange est/ouest, puis 90° horaire : nord→est, est→sud, sud→ouest, ouest→nord
attendu_f = {"north": "east", "east": "north", "south": "west", "west": "south"}
ok = True
for i, f in enumerate(["north", "east", "south", "west"]):
    e = gb[dest(SX0 + 2 + i, Y + 1, SZ0 + 1)]
    ok &= bloc(e) == "minecraft:oak_stairs" and props(e)["facing"] == attendu_f[f]
verifier("escaliers : facing transformé", ok)
ok = True
for i, f in enumerate(["north", "east", "south", "west"]):
    e = gb[dest(SX0 + 2 + i, Y + 2, SZ0 + 1)]
    ok &= props(e)["shape"] == "inner_right" and props(e)["half"] == "top"
    e = gb[dest(SX0 + 2 + i, Y + 3, SZ0 + 1)]
    ok &= props(e)["shape"] == "outer_left"
verifier("escaliers : shape gauche↔droite sous miroir", ok,
         str([gb[dest(SX0 + 2 + i, Y + 2, SZ0 + 1)] for i in range(4)]))
c1, c2 = gb[dest(SX0 + 2, Y + 1, SZ0 + 5)], gb[dest(SX0 + 3, Y + 1, SZ0 + 5)]
verifier("coffre double : facing et type échangés", props(c1)["facing"] == "east" and props(c1)["type"] == "right"
         and props(c2)["type"] == "left", f"{c1} / {c2}")
# cohérence du coffre double : le partenaire d'un « right » tourné est est au nord (anti-horaire de est)
p1, p2 = dest(SX0 + 2, Y + 1, SZ0 + 5), dest(SX0 + 3, Y + 1, SZ0 + 5)
verifier("coffre double : moitiés adjacentes dans le bon sens", p2 == (p1[0], p1[1], p1[2] - 1), f"{p1} {p2}")
bes = {tuple(b["p"]): b["snbt"] for b in B["bloc_entites"]}
verifier("coffre : contenu recopié", "gold_ingot" in bes.get(p1, "") and "apple" in bes.get(p2, ""))
d1 = gb[dest(SX0 + 6, Y + 1, SZ0 + 5)]
d2 = gb[dest(SX0 + 6, Y + 2, SZ0 + 5)]
verifier("porte : facing tourné, hinge inversé, deux moitiés", props(d1)["facing"] == "north" and props(d1)["hinge"] == "right"
         and props(d2)["half"] == "upper" and props(d2)["hinge"] == "right", f"{d1}")
verifier("bûches : axis x↔z", props(gb[dest(SX0 + 1, Y + 1, SZ0 + 7)])["axis"] == "z" and props(gb[dest(SX0 + 2, Y + 1, SZ0 + 7)])["axis"] == "x")
rail = props(gb[dest(SX0 + 4, Y + 1, SZ0 + 7)])["shape"]
verifier("rail courbe transformé", rail == "north_east", rail)  # NE → (miroir x) NW → (90° horaire) NE
lit_pied, lit_tete = gb[dest(SX0 + 5, Y + 1, SZ0 + 7)], gb[dest(SX0 + 5, Y + 1, SZ0 + 8)]
verifier("lit : facing tourné, tête devant le pied", props(lit_pied)["facing"] == "west" and props(lit_tete)["part"] == "head"
         and dest(SX0 + 5, Y + 1, SZ0 + 8)[0] == dest(SX0 + 5, Y + 1, SZ0 + 7)[0] - 1)
ps = dest(SX0 + 7, Y + 1, SZ0 + 7)
# rotation 4 (ouest) → miroir x : 12 (est) → 90° horaire : 0 (sud)
verifier("panneau : texte recopié, rotation transformée", "Nord" in bes.get(ps, "") and props(gb[ps])["rotation"] == "0",
         gb[ps])
torche = gb[dest(SX0 + 8, Y + 2, SZ0 + 7)]
verifier("torche murale : facing tourné", props(torche)["facing"] == "north", torche)

# -------------------------------------------------------------------------------------------------
print("== 5. Lectures : entités, hauteurs ==")
if os.environ.get("LANTERNE_RCON_PORT"):
    rcon(f"summon minecraft:armor_stand {SX0 + 4}.5 {Y + 1} {SZ0 + 4}.5 {{CustomName:\"Temoin\",NoGravity:1b}}")
    en = m.post_json("/lire/entites", {"de": DEs, "a": As})
    verifier("entités lues", any(e["type"] == "minecraft:armor_stand" for e in en["entites"]),
             json.dumps(en["entites"], ensure_ascii=False)[:200])
else:
    print("       (LANTERNE_RCON_PORT non défini : lecture d'entités non testée)")
h = m.post_json("/lire/hauteurs", {"de": DEs, "a": [SX0 + 9, Y + 20, SZ0 + 9]})
verifier("hauteurs : premier non-air", max(h["hauteurs"]) == Y + 3 and min(h["hauteurs"]) >= Y, f"min {min(h['hauteurs'])} max {max(h['hauteurs'])}")

# -------------------------------------------------------------------------------------------------
print("== 6. Rendus PNG ==")
for vue, de, a in [("dessus", [196, Y, 196], [273, Y + 5, 253]), ("nord", [196, Y, 196], [273, Y + 5, 253]),
                   ("dessus", [-64, -64, -64], [63, 319, 63]), ("sud", [-64, 40, -64], [63, 140, 63])]:
    png, h = m.travail("/rendu", {"de": de, "a": a, "vue": vue, "grille": 16 if a[0] - de[0] > 100 else 10})
    meta = json.loads(base64.b64decode(m.entete(h, "X-Chantier")))
    f = os.path.join(SORTIE, f"rendu-{vue}-{de[0]}_{de[2]}.png")
    with open(f, "wb") as fo:
        fo.write(png)
    verifier(f"rendu {vue} {de}→{a}", png[:8] == b"\x89PNG\r\n\x1a\n",
             f"{meta['largeur_px']}×{meta['hauteur_px']} px, {meta['duree_ms']} ms, {len(png)} octets → {f}")

# -------------------------------------------------------------------------------------------------
print("== 7. Protocole MCP (stdio) ==")
p = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "serveur_mcp.py")],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=dict(os.environ))


def rpc(i, methode, params=None):
    p.stdin.write((json.dumps({"jsonrpc": "2.0", "id": i, "method": methode, "params": params or {}}) + "\n").encode())
    p.stdin.flush()
    return json.loads(p.stdout.readline())


r = rpc(1, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "essai", "version": "0"}})
p.stdin.write(b'{"jsonrpc":"2.0","method":"notifications/initialized"}\n')
verifier("MCP initialize", r["result"]["serverInfo"]["name"] == "lanterne-chantier")
r = rpc(2, "tools/list")
noms = [t["name"] for t in r["result"]["tools"]]
verifier("MCP tools/list", len(noms) >= 15, ", ".join(noms))
r = rpc(3, "tools/call", {"name": "rendu", "arguments": {"de": DEs, "a": As, "vue": "dessus", "echelle": 8}})
c = r["result"]["content"]
verifier("MCP tools/call rendu → image", c[0]["type"] == "image" and base64.b64decode(c[0]["data"])[:4] == b"\x89PNG")
r = rpc(4, "tools/call", {"name": "lire_blocs", "arguments": {"de": [SX0, Y + 1, SZ0], "a": [SX0 + 9, Y + 1, SZ0 + 9], "format_sortie": "couches"}})
print(r["result"]["content"][0]["text"])
r = rpc(5, "tools/call", {"name": "annuler", "arguments": {"operation": "inexistante"}})
verifier("MCP erreur d'outil rendue proprement", r["result"].get("isError") is True, r["result"]["content"][0]["text"])
p.stdin.close()
p.wait(5)

print(f"\n{ECHECS} échec(s).")
sys.exit(ECHECS)
