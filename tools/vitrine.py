# -*- coding: utf-8 -*-
# Verification de la disposition de l'ecran de la boutique, hors du jeu.
#
# CE QU'ELLE AJOUTE A LA PRECEDENTE. La verification faite a la version precedente rejouait
# 968 275 combinaisons de largeur et de hauteur et controlait douze invariants de POSITION : le
# panneau reste dans la fenetre, la grille ne mord pas sur la colonne de detail, les blocs ne se
# chevauchent pas. Elle ne pouvait pas voir qu'une PHRASE deborde de sa colonne, parce qu'elle ne
# mesurait pas la police.
#
# Celle-ci mesure la police - lue dans ascii.png du jar du jeu - et controle que chaque chaine
# ecrite dans la colonne de detail tient dans la largeur qu'elle a. C'est ce controle qui a trouve
# six debordements reels a 340 points de large, dont << tout >> qui perdait deux lettres dans son
# bouton et << de la place pour 1234 >> qui sortait du panneau de vingt-six points.
#
#     unzip -j <minecraft>.jar assets/minecraft/textures/font/ascii.png
#     python vitrine.py ascii.png

import sys
from mesure_police import table

HEADER, FOOTER, SEARCH, TAB, AISLE, CELL_H, CELL_WISH = 34, 22, 24, 24, 14, 26, 136


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def layout(w, h):
    """Copie exacte de Counter.layout()."""
    margin = 6 if h < 300 else 16
    panel_w = clamp(w - 2 * margin, 340, 760)
    panel_h = clamp(h - 2 * margin, 220, 460)
    panel_w = min(panel_w, w)
    panel_h = min(panel_h, h)
    panel_x = (w - panel_w) // 2
    panel_y = (h - panel_h) // 2
    sidebar = 126 if panel_w >= 600 else 104 if panel_w >= 460 else 84
    detail = 196 if panel_w >= 600 else 160 if panel_w >= 460 else 132
    spare = panel_w - sidebar - detail - 24
    if spare < CELL_WISH:
        detail = max(96, detail - (CELL_WISH - spare))
        spare = panel_w - sidebar - detail - 24
    if spare < CELL_WISH:
        sidebar = max(72, sidebar - (CELL_WISH - spare))
    grid_left = panel_x + sidebar + 10
    grid_right = panel_x + panel_w - detail - 12
    grid_top = panel_y + HEADER + SEARCH + 4
    grid_bottom = panel_y + panel_h - FOOTER - 16
    usable = max(60, grid_right - grid_left)
    columns = max(1, usable // CELL_WISH)
    return dict(x=panel_x, y=panel_y, w=panel_w, h=panel_h, sidebar=sidebar, detail=detail,
                grid_left=grid_left, grid_right=grid_right, grid_top=grid_top,
                grid_bottom=grid_bottom, columns=columns, cell_w=usable // columns,
                lines=max(1, (grid_bottom - grid_top) // CELL_H))


def main():
    widths = table(sys.argv[1] if len(sys.argv) > 1 else "ascii.png")

    def W(s):
        return sum(widths.get(c, 6) for c in s)

    tout = W("tout")
    fautes = []
    combinaisons = 0
    extremes = {"detail": 10 ** 9, "colonnes": 10 ** 9, "lignes": 10 ** 9, "rayons": 10 ** 9}

    for w in range(320, 3841):
        for h in (240, 241, 299, 300, 301, 360, 480, 600, 720, 1080, 1440, 2160):
            combinaisons += 1
            L = layout(w, h)
            dw = L["detail"] - 22
            dx = L["x"] + L["w"] - L["detail"] + 10
            tight = dw < 116
            extremes["detail"] = min(extremes["detail"], L["detail"])
            extremes["colonnes"] = min(extremes["colonnes"], L["columns"])
            extremes["lignes"] = min(extremes["lignes"], L["lines"])
            extremes["rayons"] = min(extremes["rayons"], L["sidebar"])

            if L["x"] < 0 or L["y"] < 0 or L["x"] + L["w"] > w or L["y"] + L["h"] > h:
                fautes.append((w, h, "panneau hors fenetre"))
            if L["grid_left"] + L["columns"] * L["cell_w"] > L["grid_right"] + 1:
                fautes.append((w, h, "grille sur le detail"))
            if dx + dw > L["x"] + L["w"]:
                fautes.append((w, h, "colonne de detail hors panneau"))

            slot = dw // 4
            gutter = 3 if slot >= tout + 6 else (1 if slot >= tout + 1 else 0)
            box = slot - gutter
            if slot >= tout + 6:
                labels = ["\u00d71", "\u00d78", "\u00d764", "tout"]
            elif box >= tout:
                labels = ["1", "8", "64", "tout"]
            else:
                labels = ["1", "8", "64", "max"]
            for lab in labels:
                if W(lab) > box:
                    fautes.append((w, h, "bouton %r %d > %d" % (lab, W(lab), box)))
            if 3 * slot + box > dw:
                fautes.append((w, h, "boutons hors colonne"))

            lignes = [
                "Rachat" if tight else "Rachat \u00e0 l'unit\u00e9",
                "Prix" if tight else "Prix \u00e0 l'unit\u00e9",
                "non repris",
                "ta part : 34 %",
                "\u25b2 +30,5 %" if tight else "\u25b2 +30,5 % sur l'ancre",
                "\u00e0 l'ancre" if tight else "au prix d'ancrage",
                "tu en as 2304",
                "place : 2304" if tight else "de la place pour 2304",
                "quantit\u00e9 : 2304",
            ]
            # Les deux lignes secondaires ne sont ecrites QUE si la colonne est large : couper un
            # montant est pire que ne pas l'ecrire.
            if not tight:
                lignes += ["cours 1 234,50 \u00a4", "repris 1 234,50 \u00a4"]
            for s in lignes:
                if W(s) > dw:
                    fautes.append((w, h, "texte %r %d > %d" % (s, W(s), dw)))

            plein = "ACHETER 2304"
            label = plein if W(plein) + 6 <= dw else "ACHETER"
            if W(label) + 6 > dw:
                fautes.append((w, h, "action %r ne tient pas" % label))

            bas = L["y"] + L["h"] - FOOTER
            action_top = bas - 26
            lot_top = action_top - 38
            message_top = lot_top - 34
            if message_top + 20 > lot_top:
                fautes.append((w, h, "message sur les quantites"))
            if message_top < L["y"] + HEADER + 8:
                fautes.append((w, h, "message au-dessus de la colonne"))
            if L["grid_top"] >= L["grid_bottom"]:
                fautes.append((w, h, "grille de hauteur nulle"))

    print("%d combinaisons verifiees, de 320x240 a 3840x2160." % combinaisons)
    print("Aux extremes : detail %d, colonnes %d, lignes %d, barre des rayons %d."
          % (extremes["detail"], extremes["colonnes"], extremes["lignes"], extremes["rayons"]))
    if not fautes:
        print("ZERO VIOLATION.")
        return 0
    vus = {}
    for w, h, quoi in fautes:
        vus.setdefault(quoi, []).append((w, h))
    for quoi, ou in sorted(vus.items()):
        print("  %-52s %6d cas, p.ex. %dx%d" % (quoi, len(ou), ou[0][0], ou[0][1]))
    return 1


if __name__ == "__main__":
    sys.exit(main())
