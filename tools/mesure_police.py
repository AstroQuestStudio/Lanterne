# -*- coding: utf-8 -*-
# Mesure la largeur reelle des chaines de l'ecran de la boutique, dans la police du jeu.
#
# La verification de disposition faite precedemment sur 968 275 combinaisons ne controlait que la
# POSITION des blocs. Elle ne pouvait donc pas voir qu'une phrase DEPASSE de sa colonne : il faut
# pour cela mesurer la police, et la police est dans le jar du jeu.
#
#     unzip -j <minecraft>.jar assets/minecraft/textures/font/ascii.png
#     python mesure_police.py ascii.png
#
# Tout est ecrit en echappements \uXXXX : ce fichier reste en ASCII pur, ce qui lui evite de
# dependre de l'encodage du terminal qui le lance.

import sys
from PIL import Image

CHARS = (
    "\u00c0\u00c1\u00c2\u00c8\u00ca\u00cb\u00cd\u00d3\u00d4\u00d5\u00da\u00df\u00e3\u00f5\u011f\u0130"
    "\u0131\u0152\u0153\u015e\u015f\u0174\u0175\u017e\u0207       "
    " !\"#$%&'()*+,-./0123456789:;<=>?"
    "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_"
    "`abcdefghijklmnopqrstuvwxyz{|}~ "
)


def table(path):
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    cw, ch = w // 16, h // 16
    px = im.load()
    out = {}
    for i, c in enumerate(CHARS):
        if c == " ":
            continue
        col, row = i % 16, i // 16
        x0, y0 = col * cw, row * ch
        last = -1
        for x in range(cw):
            for y in range(ch):
                if px[x0 + x, y0 + y][3] != 0:
                    last = max(last, x)
                    break
        out[c] = 4 if last < 0 else int(round((last + 1) * 8.0 / cw)) + 1
    out[" "] = 4
    return out


ESSAIS = [
    "tout", "max", "\u00d71", "\u00d78", "\u00d764", "1", "8", "64",
    "Prix \u00e0 l'unit\u00e9", "Rachat \u00e0 l'unit\u00e9",
    "au prix d'ancrage", "\u25b2 +30,5 % sur l'ancre",
    "de la place pour 1234", "tu en as 1234", "Total",
    "ACHETER 2304", "VENDRE 2304", "ACHETER", "VENDRE",
    "quantit\u00e9 : 2304", "cours 7,20 \u00a4", "ta part : 34 %",
    "non repris", "1 234 567 \u00a4", "Prix", "Rachat", "\u00e0 l'ancre",
    "\u25b2 +30,5 %", "place : 1234", "Vends autre chose.",
    "Trop vendu. Ta part remonte seule.",
    "Tu en as trop vendu. Ta part remonte d'elle-m\u00eame.",
    "Choisis une quantit\u00e9.",
    "Il te manque 1 234,50 \u00a4.",
]


def main():
    widths = table(sys.argv[1] if len(sys.argv) > 1 else "ascii.png")

    def width(s):
        return sum(widths.get(c, 6) for c in s)

    for s in ESSAIS:
        print("%5d  %s" % (width(s), s.encode("unicode_escape").decode("ascii")))


if __name__ == "__main__":
    main()
