# -*- coding: utf-8 -*-
"""Charte visuelle « Lanterne » : toutes les textures des blocs maison, generees.

Pourquoi : les blocs du mod empruntaient des textures vanilla (tonneau, enclume de forge,
entonnoir, observateur, magnetite) ou des faces sombres proches de la roche noire — on les
confondait avec des blocs vanilla. Ce script leur donne une famille visuelle unique.

La charte (identique pour tous les blocs, lisible a distance) :
  * cadre en LAITON rivete : liseré sombre, biseau clair en haut/gauche, fonce en bas/droite,
    quatre rivets aux coins, puis un filet noir-bleu qui enchasse le panneau ;
  * panneau BLEU NUIT (ardoise bleutee, lisse, brossee en diagonale — jamais de bruit) ;
  * un SYMBOLE lumineux propre a la fonction, en BLEU DES AMES (#39c6cf) avec halo :
    ancre, ecran a glyphes, engrenage, fleches circulaires, aiguillage... ;
  * couleur d'accent secondaire quand le bloc en a besoin : vert (ferme), violet (repere),
    ambre #ffc857 (sortie / reception, l'ambre des interfaces du mod) ;
  * dessous de chaque bloc : l'embleme Lanterne (petite lanterne laiton a flamme bleue).

Aucune texture vanilla n'a ce couple laiton + bleu nuit + lueur bleue : un bloc Lanterne se
reconnait au premier coup d'oeil. Tout est opaque (alpha 255) : les faces restent en couche
solide, comme avant.

Usage : python tools/textures_lanterne.py [--planche chemin.png] [--sans-ecriture]
  - sauvegarde d'abord les anciens fichiers dans tools/textures_anciennes/ (une seule fois :
    une relance ne l'ecrase pas) ;
  - ecrit les PNG et les modeles JSON ; la planche avant/apres est optionnelle.
Reproductible : aucun hasard, deux executions donnent des octets identiques.
"""
import argparse
import json
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

RACINE = Path(__file__).resolve().parents[1]
ASSETS = RACINE / "src" / "main" / "resources" / "assets" / "lanterne"
TEX = ASSETS / "textures" / "block"
SAUVE = RACINE / "tools" / "textures_anciennes"
JAR_VANILLA = Path(r"C:\Users\trufa\curseforge\minecraft\Install\versions\26.3\26.3.jar")

S = 16


def hx(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


# ---------------------------------------------------------------- palette
NOIR = hx("11141c")          # filet d'enchassement, contours
LAITON_F = hx("5c3d16")      # laiton fonce (liseré exterieur)
LAITON = hx("a8782f")        # laiton moyen (biseau bas/droite)
LAITON_C = hx("d9a94a")      # laiton clair (biseau haut/gauche)
LAITON_E = hx("f7df8f")      # eclat de rivet
PAN_F = hx("1a2130")         # panneau bleu nuit, ombre
PAN = hx("222b3d")           # panneau
PAN_C = hx("2b3650")         # panneau, lumiere
PAN_E = hx("34415e")         # panneau, reflet de brossage
VERRE = hx("0b1820")         # verre d'ecran eteint

ACCENTS = {
    # nom : (profond, moyen, principal, vif, blanc)
    "ame": (hx("155a63"), hx("2a9aa3"), hx("39c6cf"), hx("8fecef"), hx("e6ffff")),
    "vert": (hx("25561c"), hx("3f8f2c"), hx("6fd14a"), hx("b6f28a"), hx("efffe0")),
    "violet": (hx("3e1f6b"), hx("6a3aa8"), hx("a468f0"), hx("d2adff"), hx("f6ecff")),
    "ambre": (hx("6b4410"), hx("b8801f"), hx("ffc857"), hx("ffe39e"), hx("fffbe8")),
}


class Tex:
    """Une face 16x16 opaque, pixel par pixel ; `kind` retient ce qui est « panneau »
    (pour que le halo des symboles ne baigne que le fond, jamais le cadre)."""

    def __init__(self, fond=PAN, w=S, h=S):
        self.w, self.h = w, h
        self.px = [[fond] * w for _ in range(h)]
        self.kind = [["pan"] * w for _ in range(h)]

    def set(self, x, y, c, kind="sym"):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c
            self.kind[y][x] = kind

    def rect(self, x0, y0, x1, y1, c, kind="deco"):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c, kind)

    def image(self):
        im = Image.new("RGBA", (self.w, self.h))
        im.putdata([(*c, 255) for row in self.px for c in row])
        return im


def panneau(t, x0=3, y0=3, x1=12, y1=12):
    """Fond bleu nuit : degrade vertical doux + brossage diagonal regulier (pas de bruit)."""
    for y in range(y0, y1 + 1):
        f = (y - y0) / max(1, y1 - y0)
        base = PAN_C if f < 0.2 else (PAN if f < 0.75 else PAN_F)
        for x in range(x0, x1 + 1):
            c = base
            if (x + 2 * y) % 7 == 0 and base != PAN_F:
                c = mix(base, PAN_E, 0.5)
            t.set(x, y, c, "pan")


def cadre(t, rivets=True, epais=True):
    """Cadre laiton rivete. epais=True : 3 px (laiton, biseau, filet noir) ;
    False : 2 px (laiton + filet), pour les ecrans qui veulent plus de surface."""
    n = S - 1
    for i in range(S):
        for (x, y) in ((i, 0), (i, n), (0, i), (n, i)):
            t.set(x, y, LAITON_F, "cadre")
    for i in range(1, n):
        t.set(i, 1, LAITON_C, "cadre")
        t.set(1, i, LAITON_C, "cadre")
        t.set(i, n - 1, LAITON, "cadre")
        t.set(n - 1, i, LAITON, "cadre")
    t.set(n - 1, 1, mix(LAITON_C, LAITON, 0.5), "cadre")
    t.set(1, n - 1, mix(LAITON_C, LAITON, 0.5), "cadre")
    if epais:
        for i in range(2, n - 1):
            for (x, y) in ((i, 2), (i, n - 2), (2, i), (n - 2, i)):
                t.set(x, y, NOIR, "cadre")
    if rivets:
        for (x, y) in ((1, 1), (n - 1, 1), (1, n - 1), (n - 1, n - 1)):
            t.set(x, y, LAITON_E, "cadre")
    return t


def tampon(t, x0, y0, motif, cmap, halo=None):
    """Pose un motif ASCII. '.' = transparent. halo = couleur d'accent : les pixels de
    panneau voisins du symbole prennent une lueur (melange 30 %)."""
    poses = []
    for dy, ligne in enumerate(motif):
        for dx, ch in enumerate(ligne):
            if ch == "." or ch == " ":
                continue
            t.set(x0 + dx, y0 + dy, cmap[ch], "sym")
            poses.append((x0 + dx, y0 + dy))
    if halo is not None:
        for (x, y) in poses:
            for (ax, ay) in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if 0 <= ax < t.w and 0 <= ay < t.h and t.kind[ay][ax] == "pan":
                    t.px[ay][ax] = mix(t.px[ay][ax], halo, 0.3)
                    t.kind[ay][ax] = "halo"


def cm(accent):
    p, m, c, v, b = ACCENTS[accent]
    return {"d": p, "m": m, "#": c, "+": v, "*": b, "o": NOIR,
            "n": LAITON_F, "b": LAITON, "B": LAITON_C, "E": LAITON_E, "v": VERRE,
            "p": PAN_F, "P": PAN_C}


# ---------------------------------------------------------------- motifs (10x10 sauf mention)
EMBLEME = [  # la petite lanterne : anse, chapeau laiton, verre a flamme bleue, 6x9
    "..nn..",
    ".n..n.",
    "BBBBBB",
    "bd+#db",
    "b#**#b",
    "b#**#b",
    "bd##db",
    "BBBBBB",
    ".bbbb.",
]
ANCRE = [
    "....##....",
    "...#..#...",
    "...#..#...",
    "....##....",
    ".########.",
    "....##....",
    "....##....",
    "#...##...#",
    ".#..##..#.",
    "..######..",
]
ANCRE_POUSSE = [
    ".##....##.",
    "..##..##..",
    "...#++#...",
    "....##....",
    ".########.",
    "....##....",
    "....##....",
    "#...##...#",
    ".#..##..#.",
    "..######..",
]
NOYAU = [  # cercle runique + coeur
    "...####...",
    "..#....#..",
    ".#..mm..#.",
    "#..m++m..#",
    "#.m+**+m.#",
    "#.m+**+m.#",
    "#..m++m..#",
    ".#..mm..#.",
    "..#....#..",
    "...####...",
]
LOSANGE = [
    "....##....",
    "...#++#...",
    "..#+mm+#..",
    ".#+m**m+#.",
    "#+m*++*m+#",
    "#+m*++*m+#",
    ".#+m**m+#.",
    "..#+mm+#..",
    "...#++#...",
    "....##....",
]
RECYCLAGE = [  # deux fleches epaisses qui se poursuivent (symetrie 180 degres)
    "...####...",
    "..######..",
    ".##....##.",
    ".......##.",
    ".++...++++",
    "++++...++.",
    ".##.......",
    ".##....##.",
    "..######..",
    "...####...",
]
ENGRENAGE = [
    "...#..#...",
    ".#.####.#.",
    "..##++##..",
    ".##+oo+##.",
    "##+oooo+##",
    "##+oooo+##",
    ".##+oo+##.",
    "..##++##..",
    ".#.####.#.",
    "...#..#...",
]
AIGUILLAGE_H = [  # double fleche  ⇄
    "..#.......",
    ".##.......",
    "#########.",
    ".##.......",
    "..#.......",
    ".......#..",
    ".......##.",
    ".#########",
    ".......##.",
    ".......#..",
]
AIGUILLAGE_Y = [  # une entree, deux sorties
    "###....###",
    "##......##",
    "#.#....#.#",
    "...#..#...",
    "....##....",
    "....##....",
    "....##....",
    "....##....",
    "....##....",
    "...####...",
]
FLECHE_BAS = [
    "...####...",
    "...#++#...",
    "...#++#...",
    "...#++#...",
    "#########.",
    ".#+++++#..",
    "..#+++#...",
    "...#+#....",
    "....#.....",
    "..........",
]
FLECHE_BAS = [
    "...####...",
    "...#++#...",
    "...#++#...",
    "...#++#...",
    "##########",
    ".#++++++#.",
    "..#++++#..",
    "...#++#...",
    "....##....",
    "..........",
]
FLECHE_HAUT = list(reversed(FLECHE_BAS))
FLECHE_HAUT = FLECHE_HAUT[1:] + FLECHE_HAUT[:1]
CHEVRONS_BAS = [
    "#......#",
    ".#....#.",
    "..#..#..",
    "...##...",
    "#......#",
    ".#....#.",
    "..#..#..",
    "...##...",
]
CHEVRONS_HAUT = list(reversed(CHEVRONS_BAS))
LENTILLE = [
    "...BBBB...",
    ".BBnnnnBB.",
    ".Bnvvvvnb.",
    "Bnvvvvvvnb",
    "Bnvv+vvvnb",
    "Bnvvvvvvnb",
    "Bnvvvvvvnb",
    ".bnvvvvnb.",
    ".bbnnnnbb.",
    "...bbbb...",
]
LENTILLE_ACTIVE = [
    "...BBBB...",
    ".BBnnnnBB.",
    ".Bn####nb.",
    "Bn#++++#nb",
    "Bn#+**+#nb",
    "Bn#+**+#nb",
    "Bn#++++#nb",
    ".bn####nb.",
    ".bbnnnnbb.",
    "...bbbb...",
]


# ---------------------------------------------------------------- faces communes
def face_dessous():
    """Dessous de tout bloc Lanterne : l'embleme, en veilleuse."""
    t = cadre(Tex())
    panneau(t)
    tampon(t, 5, 3, EMBLEME, cm("ame"), halo=ACCENTS["ame"][1])
    return t


def face_symbole(motif, accent, x0=3, y0=3, halo=True):
    t = cadre(Tex())
    panneau(t)
    tampon(t, x0, y0, motif, cm(accent), halo=ACCENTS[accent][2] if halo else None)
    return t


def face_aerations(accent="ame", vertical=False):
    """Flanc de machine : fentes d'aeration, lueur au fond de chaque fente."""
    t = cadre(Tex())
    panneau(t)
    p = ACCENTS[accent][0]
    m = ACCENTS[accent][1]
    for k in range(3):
        a = 4 + k * 3
        for i in range(4, 12):
            if vertical:
                t.set(a, i, NOIR, "deco")
                t.set(a + 1, i, p if i in (6, 7, 8, 9) else PAN_F, "deco")
            else:
                t.set(i, a, NOIR, "deco")
                t.set(i, a + 1, p if i in (6, 7, 8, 9) else PAN_F, "deco")
        if not vertical:
            t.set(7, a + 1, m, "deco")
            t.set(8, a + 1, m, "deco")
    return t


def face_ecran(lignes, accent="ame"):
    """Ecran de verre sombre (cadre fin, 12x12 de verre) portant des glyphes."""
    t = cadre(Tex(), epais=False)
    for y in range(2, 14):
        for x in range(2, 14):
            t.set(x, y, mix(VERRE, hx("12303a"), (y - 2) / 22), "verre")
    # filet noir interieur
    for i in range(2, 14):
        t.set(i, 2, NOIR, "cadre")
        t.set(2, i, NOIR, "cadre")
    # reflet en coin
    t.set(3, 3, hx("1d4450"), "verre")
    t.set(4, 3, hx("16353f"), "verre")
    t.set(3, 4, hx("16353f"), "verre")
    p, m, c, v, b = ACCENTS[accent]
    cols = {"#": c, "+": v, "m": m, "d": p, "*": b}
    for y, ligne in lignes:
        for dx, ch in enumerate(ligne):
            if ch in cols:
                t.set(3 + dx, y, cols[ch], "sym")
    return t


# ---------------------------------------------------------------- blocs
def textures():
    out = {}

    # Ancre de chunk (bleu) / Ancre de ferme (vert)
    out["anchor_side"] = face_symbole(ANCRE, "ame")
    out["anchor_top"] = face_symbole(NOYAU, "ame")
    out["anchor_bottom"] = face_dessous()
    out["ancre_de_ferme_side"] = face_symbole(ANCRE_POUSSE, "vert")
    out["ancre_de_ferme_top"] = face_symbole(NOYAU, "vert")
    out["ancre_de_ferme_bottom"] = face_dessous()

    # Coeur de repere : losange violet (toutes faces)
    out["coeur_de_repere"] = face_symbole(LOSANGE, "violet")

    # Lanterne de veille : fenetre de verre, grande flamme bleue, barreaux laiton
    t = cadre(Tex())
    for y in range(3, 13):
        for x in range(3, 13):
            t.set(x, y, mix(hx("0f2f36"), hx("0a1a20"), abs(x - 7.5) / 5), "pan")
    grande = [
        "...#....",
        "...##...",
        "..#+#...",
        "..#++#..",
        ".#+*+#..",
        ".#+**+#.",
        "#+****+#",
        "#+****+#",
        ".#++++#.",
        "..####..",
    ]
    tampon(t, 4, 3, grande, cm("ame"), halo=ACCENTS["ame"][2])
    for y in range(3, 13):
        t.set(3, y, LAITON, "deco")
        t.set(12, y, LAITON_F, "deco")
    out["veilleuse_side"] = t
    t = cadre(Tex())
    panneau(t)
    for y in range(3, 13):
        for x in range(3, 13):
            if (x - 3) % 3 == 0 or (y - 3) % 3 == 0:
                t.set(x, y, LAITON if (x + y) % 2 else LAITON_C, "deco")
            else:
                t.set(x, y, ACCENTS["ame"][3] if 5 <= x <= 10 and 5 <= y <= 10
                      else ACCENTS["ame"][1], "sym")
    out["veilleuse_top"] = t
    out["veilleuse_bottom"] = face_dessous()

    # Ecran : dalle de verre + flancs a aerations
    out["ecran_face"] = face_ecran([])
    out["ecran_cote"] = face_aerations()

    # Projecteur : lentille (eteinte / allumee)
    out["projecteur_cote"] = face_aerations(vertical=True)
    out["projecteur_face"] = face_symbole(LENTILLE, "ame", halo=False)
    out["projecteur_face_actif"] = face_symbole(LENTILLE_ACTIVE, "ame")

    # Graveur de vinyle : platine vue de dessus, bras en laiton
    def platine(actif):
        """Vinyle vu de dessus (cercle calcule, sillons concentriques), bras laiton."""
        t = cadre(Tex())
        panneau(t)
        p, m, c, v, b = ACCENTS["ame"]
        for y in range(3, 13):
            for x in range(3, 13):
                r = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
                if r > 5.1:
                    continue
                if r > 4.4:
                    col = NOIR
                elif r < 1.6:
                    col = (b if (x + y) % 2 else v) if actif else m
                elif 3.2 <= r <= 3.8 or 2.0 <= r <= 2.4:
                    col = (p if actif else hx("3a3f4f"))
                else:
                    col = hx("16181f")
                t.set(x, y, col, "sym")
        # reflet du vinyle en haut a gauche
        t.set(5, 5, hx("4a5063"), "sym")
        t.set(4, 7, hx("2c3040"), "sym")
        if actif:
            for (x, y) in ((3, 7), (3, 8), (12, 7), (12, 8), (7, 3), (8, 3), (7, 12), (8, 12)):
                t.px[y][x] = mix(NOIR, c, 0.45)
        # bras de lecture : pivot hors du disque, pointe posee sur un sillon
        t.set(12, 3, LAITON_E, "deco")
        t.set(11, 4, LAITON_C, "deco")
        t.set(10, 5, v if actif else LAITON, "deco")
        t.set(12, 4, LAITON_F, "deco")
        return t

    out["graveur_top"] = platine(False)
    out["graveur_top_actif"] = platine(True)
    t = cadre(Tex())
    panneau(t)
    for x in range(4, 12):
        t.set(x, 6, LAITON_C, "deco")
        t.set(x, 7, NOIR, "deco")
        t.set(x, 8, ACCENTS["ame"][0], "deco")
        t.set(x, 9, LAITON, "deco")
    t.set(10, 11, ACCENTS["ame"][2], "sym")
    t.set(11, 11, ACCENTS["ame"][1], "sym")
    t.set(4, 11, LAITON_C, "deco")
    t.set(5, 11, LAITON_F, "deco")
    out["graveur_side"] = t
    out["graveur_bottom"] = face_dessous()

    # Aiguillage (reseau) : ⇄ sur les flancs, Y sur le dessus
    out["aiguillage_side"] = face_symbole(AIGUILLAGE_H, "ame")
    out["aiguillage_top"] = face_symbole(AIGUILLAGE_Y, "ame")

    # Guichet (« Terminal ») : ecran a glyphes bleus sur les 4 flancs, noeud reseau dessus
    out["guichet_side"] = face_ecran([
        (4, "##.#.###.m"),
        (6, "#.###.##.."),
        (8, "###.#.mm.."),
        (10, "+.++......"),
        (11, "..........".replace(".", ".", 1)),
    ])
    t = out["guichet_side"]
    t.set(6, 10, ACCENTS["ame"][4], "sym")  # curseur
    t.set(6, 11, ACCENTS["ame"][3], "sym")
    noeud = [
        "....##....",
        "....##....",
        "....##....",
        "...#++#...",
        "####**####",
        "####**####",
        "...#++#...",
        "....##....",
        "....##....",
        "....##....",
    ]
    out["guichet_top"] = face_symbole(noeud, "ame")
    out["guichet_bottom"] = face_dessous()

    # Coffres du reseau : depot (fleche bleue qui entre), reception (fleche ambre qui sort)
    out["coffre_depot_side"] = face_symbole(FLECHE_BAS, "ame")
    out["coffre_reception_side"] = face_symbole(FLECHE_HAUT, "ambre")

    def trappe(chevrons, accent):
        t = cadre(Tex())
        panneau(t)
        t.rect(3, 3, 12, 12, NOIR, "deco")
        for y in range(4, 12):
            for x in range(4, 12):
                t.set(x, y, mix(hx("0a0d14"), PAN_F, (y - 4) / 8), "pan")
        tampon(t, 4, 4, chevrons, cm(accent), halo=ACCENTS[accent][1])
        return t

    out["coffre_depot_top"] = trappe(CHEVRONS_BAS, "ame")
    out["coffre_reception_top"] = trappe(CHEVRONS_HAUT, "ambre")
    out["coffre_bottom"] = face_dessous()

    # Recycleur : fleches circulaires sur les flancs, broyeur (engrenage) dessus
    out["recycleur_side"] = face_symbole(RECYCLAGE, "ame")
    t = cadre(Tex())
    panneau(t)
    tampon(t, 3, 3, ENGRENAGE, {"#": LAITON, "+": LAITON_C, "o": ACCENTS["ame"][1]},
           halo=None)
    t.set(7, 7, ACCENTS["ame"][3]); t.set(8, 8, ACCENTS["ame"][2])
    t.set(8, 7, ACCENTS["ame"][2]); t.set(7, 8, ACCENTS["ame"][2])
    out["recycleur_top"] = t
    out["recycleur_bottom"] = face_dessous()

    # Trieur : geometrie d'entonnoir conservee, habillage Lanterne.
    # Faces sans UV explicites : le bol (y 10..16) lit les lignes 0..5 sur toute la
    # largeur, l'etage du milieu (x 4..12, y 4..10) les lignes 6..11, colonnes 4..11,
    # le bec (x 6..10, y 0..4 ; ou y 4..8 couche) les lignes 8..15, colonnes 6..9.
    t = Tex(PAN)
    for x in range(S):
        t.set(x, 0, LAITON_C, "cadre")
        t.set(x, 1, LAITON, "cadre")
        t.set(x, 5, LAITON_F, "cadre")
    for x in (0, 15):
        for y in range(2, 5):
            t.set(x, y, LAITON_F, "cadre")
    for y in range(2, 5):
        for x in range(1, 15):
            t.set(x, y, PAN_C if y == 2 else PAN, "pan")
    # trois voies de tri, trois couleurs
    for k, acc in enumerate(("ame", "vert", "ambre")):
        x = 3 + k * 4
        c = ACCENTS[acc]
        for (dx, dy, col) in ((0, 2, c[2]), (1, 2, c[2]), (0, 3, c[3]), (1, 3, c[3]),
                              (-1, 3, c[1]), (2, 3, c[1])):
            t.set(x + dx, dy + 0, col, "sym")
        t.set(x, 4, c[2], "sym")
        t.set(x + 1, 4, c[2], "sym")
    t.set(1, 1, LAITON_E, "cadre")
    t.set(14, 1, LAITON_E, "cadre")
    for y in range(6, 12):
        for x in range(0, 16):
            if 4 <= x <= 11:
                if x in (4, 11):
                    c = LAITON if x == 4 else LAITON_F
                else:
                    c = PAN_C if y < 8 else PAN
                    if x in (7, 8) and y in (8, 9, 10):
                        c = ACCENTS["ame"][2] if y == 9 else ACCENTS["ame"][1]
                t.set(x, y, c, "deco")
            else:
                t.set(x, y, PAN_F, "deco")
    for y in range(12, 16):
        for x in range(0, 16):
            t.set(x, y, PAN_F, "deco")
    for y in range(8, 16):
        for x in range(6, 10):
            if y >= 12:
                c = LAITON_C if x == 6 else (LAITON_F if x == 9 else LAITON)
                if y == 15:
                    c = ACCENTS["ame"][2] if x in (7, 8) else ACCENTS["ame"][1]
                t.set(x, y, c, "deco")
    out["trieur_outside"] = t
    t = cadre(Tex(), epais=False)
    panneau(t, 2, 2, 13, 13)
    out["trieur_top"] = t
    t = Tex(PAN_F)
    for y in range(S):
        for x in range(S):
            d = max(abs(x - 7.5), abs(y - 7.5)) / 8
            t.set(x, y, mix(hx("123a44"), hx("0d121b"), d), "pan")
    out["trieur_inside"] = t

    return {k: v.image() for k, v in out.items()}


# ---------------------------------------------------------------- modeles JSON
DISPLAY_BLOC = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0],
                              "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0],
                              "scale": [0.40, 0.40, 0.40]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0],
                             "scale": [0.40, 0.40, 0.40]},
}


def cube_bt(top, bottom, side):
    return {"parent": "minecraft:block/cube_bottom_top",
            "textures": {"top": top, "bottom": bottom, "side": side}}


def trieur_modele(parent):
    return {"parent": parent,
            "gui_light": "side",
            "textures": {"particle": "lanterne:block/trieur_outside",
                         "top": "lanterne:block/trieur_top",
                         "side": "lanterne:block/trieur_outside",
                         "inside": "lanterne:block/trieur_inside"},
            "display": DISPLAY_BLOC}


MODELES = {
    "models/block/aiguillage.json": cube_bt("lanterne:block/aiguillage_top",
                                            "lanterne:block/aiguillage_top",
                                            "lanterne:block/aiguillage_side"),
    "models/block/guichet.json": cube_bt("lanterne:block/guichet_top",
                                         "lanterne:block/guichet_bottom",
                                         "lanterne:block/guichet_side"),
    "models/block/coffre_depot.json": cube_bt("lanterne:block/coffre_depot_top",
                                              "lanterne:block/coffre_bottom",
                                              "lanterne:block/coffre_depot_side"),
    "models/block/coffre_reception.json": cube_bt("lanterne:block/coffre_reception_top",
                                                  "lanterne:block/coffre_bottom",
                                                  "lanterne:block/coffre_reception_side"),
    # Nouveaux modeles (le recycleur et le trieur pointaient directement vers des modeles
    # vanilla) : ils ne changent aucun identifiant de bloc ni d'objet.
    "models/block/recycleur.json": cube_bt("lanterne:block/recycleur_top",
                                           "lanterne:block/recycleur_bottom",
                                           "lanterne:block/recycleur_side"),
    "models/block/trieur.json": trieur_modele("minecraft:block/hopper"),
    "models/block/trieur_side.json": trieur_modele("minecraft:block/hopper_side"),
    "blockstates/recycleur.json": {"variants": {"": {"model": "lanterne:block/recycleur"}}},
    "blockstates/trieur.json": {"variants": {
        "facing=down": {"model": "lanterne:block/trieur"},
        "facing=east": {"model": "lanterne:block/trieur_side", "y": 90},
        "facing=north": {"model": "lanterne:block/trieur_side"},
        "facing=south": {"model": "lanterne:block/trieur_side", "y": 180},
        "facing=west": {"model": "lanterne:block/trieur_side", "y": 270},
    }},
    "items/recycleur.json": {"model": {"type": "minecraft:model",
                                       "model": "lanterne:block/recycleur"}},
    "items/trieur.json": {"model": {"type": "minecraft:model",
                                    "model": "lanterne:block/trieur"}},
}


# ---------------------------------------------------------------- sauvegarde / ecriture
def sauvegarder(noms_png):
    for nom in noms_png:
        src = TEX / (nom + ".png")
        dst = SAUVE / "textures" / "block" / (nom + ".png")
        if src.exists() and not dst.exists():
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
    for rel in MODELES:
        src = ASSETS / rel
        dst = SAUVE / rel
        if src.exists() and not dst.exists():
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)


def ecrire(imgs):
    for nom, im in imgs.items():
        im.save(TEX / (nom + ".png"), optimize=True)
    for rel, data in MODELES.items():
        (ASSETS / rel).write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n",
                                  encoding="utf-8")


# ---------------------------------------------------------------- planche avant / apres
def vanilla(chemin):
    try:
        with zipfile.ZipFile(JAR_VANILLA) as z:
            with z.open("assets/minecraft/textures/" + chemin + ".png") as f:
                im = Image.open(f).convert("RGBA")
                return im.crop((0, 0, im.width, im.width)).copy()
    except (OSError, KeyError):
        return None


def ancienne(nom):
    for p in (SAUVE / "textures" / "block" / (nom + ".png"), TEX / (nom + ".png")):
        if p.exists():
            return Image.open(p).convert("RGBA")
    return None


def iso(top, gauche, droite, s=72):
    """Cube isometrique a partir de trois faces 16x16 (dessus, flanc sud, flanc est)."""
    import math
    cx = math.cos(math.radians(30))
    W, H = int(2 * cx * s) + 4, int(2 * s) + 4
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    def proj(x, y, z):
        return (W / 2 + (x - z) * cx * s, s * 0.5 + (x + z) * 0.5 * s - y * s + s * 0.5)

    def face(tex, coin, fshade):
        tex = tex.convert("RGBA").load()
        for v in range(16):
            for u in range(16):
                r, g, b, a = tex[u, v]
                if a == 0:
                    continue
                c = (int(r * fshade), int(g * fshade), int(b * fshade), 255)
                pts = [proj(*coin(u + du, v + dv)) for (du, dv) in ((0, 0), (1, 0), (1, 1), (0, 1))]
                d.polygon(pts, fill=c, outline=c)

    face(top, lambda u, v: (u / 16, 1, v / 16), 1.0)
    face(gauche, lambda u, v: (u / 16, 1 - v / 16, 1), 0.8)
    face(droite, lambda u, v: (1, 1 - v / 16, 1 - u / 16), 0.62)
    return im


PLANCHE = [
    # (nom affiche, [(etiquette, avant, apres)], cube iso (dessus, flanc, face) apres/avant)
    ("Guichet (Terminal)", [("dessus", "v:block/lodestone_top", "guichet_top"),
                            ("flanc", "v:block/lodestone_side", "guichet_side"),
                            ("dessous", "v:block/lodestone_top", "guichet_bottom")]),
    ("Recycleur", [("dessus", "v:block/smithing_table_top", "recycleur_top"),
                   ("flanc", "v:block/smithing_table_front", "recycleur_side"),
                   ("dessous", "v:block/smithing_table_bottom", "recycleur_bottom")]),
    ("Trieur", [("bord", "v:block/hopper_top", "trieur_top"),
                ("exterieur", "v:block/hopper_outside", "trieur_outside"),
                ("interieur", "v:block/hopper_inside", "trieur_inside")]),
    ("Aiguillage", [("dessus", "v:block/observer_top", "aiguillage_top"),
                    ("flanc", "v:block/observer_side", "aiguillage_side")]),
    ("Coffre de depot", [("dessus", "v:block/barrel_top", "coffre_depot_top"),
                         ("flanc", "v:block/barrel_side", "coffre_depot_side"),
                         ("dessous", "v:block/barrel_bottom", "coffre_bottom")]),
    ("Coffre de reception", [("dessus", "v:block/barrel_top_open", "coffre_reception_top"),
                             ("flanc", "v:block/barrel_side", "coffre_reception_side")]),
    ("Ancre de chunk", [("dessus", "anchor_top", "anchor_top"),
                        ("flanc", "anchor_side", "anchor_side"),
                        ("dessous", "anchor_bottom", "anchor_bottom")]),
    ("Ancre de ferme", [("dessus", "ancre_de_ferme_top", "ancre_de_ferme_top"),
                        ("flanc", "ancre_de_ferme_side", "ancre_de_ferme_side")]),
    ("Coeur de repere", [("toutes", "coeur_de_repere", "coeur_de_repere")]),
    ("Lanterne de veille", [("dessus", "veilleuse_top", "veilleuse_top"),
                            ("flanc", "veilleuse_side", "veilleuse_side")]),
    ("Ecran", [("face", "ecran_face", "ecran_face"), ("cote", "ecran_cote", "ecran_cote")]),
    ("Projecteur", [("face", "projecteur_face", "projecteur_face"),
                    ("active", "projecteur_face_actif", "projecteur_face_actif"),
                    ("cote", "projecteur_cote", "projecteur_cote")]),
    ("Graveur", [("dessus", "graveur_top", "graveur_top"),
                 ("actif", "graveur_top_actif", "graveur_top_actif"),
                 ("flanc", "graveur_side", "graveur_side")]),
]


def planche(imgs, chemin):
    Z = 8
    T = 16 * Z
    pad = 10
    ncol = 3
    iso_w = 130
    lab_h = 18
    row_h = T + lab_h + 28
    W = 170 + 2 * (ncol * (T + pad) + iso_w + 30)
    H = 40 + len(PLANCHE) * row_h
    out = Image.new("RGBA", (W, H), (32, 32, 38, 255))
    d = ImageDraw.Draw(out)
    xa = 170
    xb = 170 + ncol * (T + pad) + iso_w + 30
    d.text((xa, 12), "AVANT", fill=(230, 230, 230))
    d.text((xb, 12), "APRES (charte Lanterne)", fill=(255, 200, 87))
    for i, (titre, faces) in enumerate(PLANCHE):
        y = 40 + i * row_h
        d.text((10, y + T // 2), titre, fill=(240, 240, 240))
        avant_l, apres_l = [], []
        for j, (et, av, ap) in enumerate(faces):
            a = vanilla(av[2:]) if av.startswith("v:") else ancienne(av)
            b = imgs.get(ap)
            for (x0, im, lst) in ((xa, a, avant_l), (xb, b, apres_l)):
                lst.append(im)
                if im is not None:
                    out.alpha_composite(im.resize((T, T), Image.NEAREST), (x0 + j * (T + pad), y))
                d.text((x0 + j * (T + pad), y + T + 3), et, fill=(170, 170, 180))
        for (x0, lst) in ((xa, avant_l), (xb, apres_l)):
            if titre == "Trieur" or not lst or lst[0] is None:
                continue
            top = lst[0]
            side = lst[1] if len(lst) > 1 and lst[1] is not None else top
            if titre in ("Ecran", "Projecteur"):
                top, side = lst[-1], lst[0]
                cube = iso(top, side, lst[-1], s=62)
            else:
                cube = iso(top, side, side, s=62)
            out.alpha_composite(cube, (x0 + ncol * (T + pad), y + 4))
    Path(chemin).parent.mkdir(parents=True, exist_ok=True)
    out.save(chemin)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--planche", default=os.path.join(tempfile.gettempdir(),
                                                      "planche_lanterne.png"))
    ap.add_argument("--sans-ecriture", action="store_true")
    a = ap.parse_args()
    imgs = textures()
    for n, im in imgs.items():
        assert im.size == (16, 16), n
    if not a.sans_ecriture:
        sauvegarder(imgs.keys())
        ecrire(imgs)
    planche(imgs, a.planche)
    print(f"{len(imgs)} textures, {len(MODELES)} JSON ; planche : {a.planche}")


if __name__ == "__main__":
    sys.exit(main())
