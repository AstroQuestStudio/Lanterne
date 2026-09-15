# -*- coding: utf-8 -*-
"""Les textures de l'ecran et du projecteur.

Generees plutot que dessinees : six fichiers de seize pixels dont la moitie sont
des variations d'une meme grille. Un script les tient d'accord entre eux, ce qu'un
editeur d'image ne fait pas — et il documente la palette, qui est celle du reste du
mod (l'ambre 0xFFC857 des interfaces, le gris tres sombre des panneaux).
"""
from PIL import Image
import os, random

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources",
                   "assets", "lanterne", "textures", "block")
S = 16
AMBRE = (255, 200, 87)
SOMBRE = (20, 20, 26)
GRIS = (58, 58, 66)
GRIS_C = (78, 78, 88)
CUIVRE = (150, 96, 62)
CUIVRE_C = (186, 126, 84)


def grain(img, seed, force=8):
    """Un bruit leger : sans lui, une face unie de seize pixels a l'air d'un bogue."""
    rng = random.Random(seed)
    px = img.load()
    for y in range(S):
        for x in range(S):
            r, g, b, a = px[x, y]
            d = rng.randint(-force, force)
            px[x, y] = (max(0, min(255, r + d)), max(0, min(255, g + d)),
                        max(0, min(255, b + d)), a)
    return img


def plain(colour, seed, force=8):
    return grain(Image.new("RGBA", (S, S), colour + (255,)), seed, force)


def bord(img, colour):
    px = img.load()
    for i in range(S):
        for p in ((i, 0), (i, S - 1), (0, i), (S - 1, i)):
            px[p] = colour + (255,)
    return img


def rect(img, x0, y0, x1, y1, colour):
    px = img.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = colour + (255,)
    return img


os.makedirs(OUT, exist_ok=True)

# L'ardoise : le fond qu'un ecran montre quand il n'a pas d'image. Unie, tres sombre,
# avec un grain infime — c'est elle qu'on etire sur un mur de seize blocs, donc tout
# motif y deviendrait un damier geant.
plain(SOMBRE, 11, 3).save(os.path.join(OUT, "ardoise.png"))

# La face de l'ecran : l'ardoise, plus un cadre qui dit de quel cote regarder.
face = plain(SOMBRE, 12, 4)
bord(face, GRIS)
rect(face, 1, 1, 15, 2, (30, 30, 38))
face.save(os.path.join(OUT, "ecran_face.png"))

# Les cotes : la caisse. Gris de machine, avec deux rainures.
cote = plain(GRIS, 13)
rect(cote, 0, 4, S, 5, GRIS_C)
rect(cote, 0, 11, S, 12, GRIS_C)
cote.save(os.path.join(OUT, "ecran_cote.png"))

# Le projecteur : cuivre, comme le graveur — meme famille de machines.
pc = plain(CUIVRE, 21)
rect(pc, 0, 3, S, 4, CUIVRE_C)
rect(pc, 0, 12, S, 13, CUIVRE_C)
pc.save(os.path.join(OUT, "projecteur_cote.png"))

# Sa face : une lentille. Eteinte, puis allumee.
for nom, coeur, halo in (("projecteur_face", (44, 44, 52), (70, 70, 80)),
                         ("projecteur_face_actif", AMBRE, (255, 228, 160))):
    f = plain(CUIVRE, 22)
    bord(f, CUIVRE_C)
    px = f.load()
    for y in range(S):
        for x in range(S):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if d < 3.2:
                px[x, y] = coeur + (255,)
            elif d < 5.4:
                px[x, y] = halo + (255,)
    f.save(os.path.join(OUT, nom + ".png"))

# Le faisceau : un degrade doux, comme le rayon d'une balise. Il est etire sur toute
# la longueur du cone, donc il ne porte aucun detail — seulement de quoi eviter une
# bande parfaitement plate, qui trahit tout de suite le rectangle.
beam = Image.new("RGBA", (S, S), (0, 0, 0, 0))
bpx = beam.load()
for y in range(S):
    for x in range(S):
        bord_x = min(x, S - 1 - x) / (S / 2.0)
        a = int(255 * (0.35 + 0.65 * bord_x))
        bpx[x, y] = (255, 240, 205, a)
beam.save(os.path.join(OUT, "faisceau.png"))

print("ecrites :", sorted(n for n in os.listdir(OUT)
                          if n.startswith(("ecran", "projecteur", "ardoise", "faisceau"))))
