"""Maquettes Astra originales : 12 textures 16x16 sur une planche unique.

Usage : python tools/deco_astra_textures.py [--seed 263] [--output chemin.png]
Dépendance : Pillow (PIL). Aucune ressource Minecraft n'est lue ou modifiée.
Les images ne sont jamais écrites dans src/main/resources.
"""
from __future__ import annotations

import argparse
import hashlib
import random
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, PngImagePlugin

ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Matiere:
    numero: int
    titre: str
    sous_titre: str
    motif: str
    palette: tuple[str, ...]


CALCITE = ("#aaa79b", "#c2bfb2", "#d5d3c6", "#e2e0d3", "#efeddf")
BASALTE = ("#36363b", "#46464b", "#57585b", "#656767", "#797b78")
BOUE = ("#655040", "#79604b", "#94765a", "#a58968", "#b49873")
TERRE = ("#754237", "#925747", "#ab6951", "#bb7a5d", "#cf9470")
QUARTZ = ("#b6aea0", "#cec6b7", "#e2d9c8", "#eee7da", "#faf3e7")
AMETHYSTE = ("#514169", "#69517e", "#856298", "#a381b5", "#bca1cf")
PRISMARINE = ("#315e59", "#44786d", "#619584", "#7eab98", "#9ec4aa")
FER = ("#343839", "#4f5657", "#737c7d", "#9ca5a3", "#c3cbc6")
MIEL = ("#574733", "#977443", "#bd9956", "#dbc17c", "#f0d999")
MOUSSE = ("#364b39", "#617a4b", "#819958", "#a2b378", "#c5cf9c")

MATIERES = (
    Matiere(8, "Calcite appareillée", "Pierre de taille · calcite", "pierre", CALCITE),
    Matiere(4, "Pilier de calcite", "Cannelures · calcite", "pilier", CALCITE),
    Matiere(5, "Corniche de calcite", "Bandeau · calcite", "corniche", CALCITE),
    Matiere(15, "Pavage de basalte", "Sol · basalte lisse", "pavage", BASALTE),
    Matiere(21, "Voussoir de terre", "Arc · boue compactée", "voussoir", BOUE),
    Matiere(30, "Tuiles naturelles", "Toiture · terre cuite", "tuiles", TERRE),
    Matiere(35, "Corniche de quartz", "Relief · quartz lisse", "corniche", QUARTZ),
    Matiere(42, "Pavage d'améthyste", "Mosaïque · améthyste", "mosaique", AMETHYSTE),
    Matiere(47, "Corniche marine", "Bordure · prismarine", "corniche", PRISMARINE),
    Matiere(49, "Grille de fer", "Ferronnerie · barreaux", "grille", FER),
    Matiere(55, "Vitrail miel", "Verre jaune · joints fins", "vitrail", MIEL),
    Matiere(56, "Vitrail mousse", "Verre vert · joints fins", "vitrail", MOUSSE),
)


def rgba(hexa: str, alpha: int = 255) -> tuple[int, int, int, int]:
    return tuple(int(hexa[i:i + 2], 16) for i in (1, 3, 5)) + (alpha,)


def texture(matiere: Matiere, seed: int) -> Image.Image:
    """Toute la matière et tous les traits se dessinent à 16 pixels, sans lissage."""
    rng = random.Random(f"astra:{seed}:{matiere.numero}")
    couleurs = [rgba(c) for c in matiere.palette]
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), couleurs[rng.choices([1, 2, 3], [1, 6, 2])[0]])
    d = ImageDraw.Draw(img)
    motif = matiere.motif
    if motif == "pierre":
        for y in (0, 8):
            d.line((0, y, 15, y), fill=couleurs[0])
            d.line((0, y + 1, 15, y + 1), fill=couleurs[3])
            for x in range(0 if y == 0 else 5, 16, 10):
                d.line((x, y, x, y + 7), fill=couleurs[1])
        for _ in range(7):
            x, y = rng.randrange(16), rng.randrange(16)
            d.line((x, y, min(x + 1, 15), y), fill=couleurs[3])
    elif motif == "pilier":
        for x in range(16):
            tone = [1, 3, 4, 2, 1, 2, 3, 2][x % 8]
            d.line((x, 1, x, 14), fill=couleurs[tone])
        d.line((0, 0, 15, 0), fill=couleurs[1])
        d.line((0, 15, 15, 15), fill=couleurs[3])
        for _ in range(9):
            x, y = rng.randrange(16), rng.randrange(2, 14)
            if x % 8 in (3, 5, 7):
                img.putpixel((x, y), couleurs[2])
    elif motif == "corniche":
        bandes = [1, 3, 4, 3, 2, 1, 0, 1, 2, 3, 3, 2, 1, 0, 2, 3]
        for y, tone in enumerate(bandes):
            d.line((0, y, 15, y), fill=couleurs[tone])
        for x in range(0, 16, 4):
            d.rectangle((x, 8, x + 1, 10), fill=couleurs[1])
            d.point((x + 2, 9), fill=couleurs[4])
    elif motif == "pavage":
        for y in range(16):
            for x in range(16):
                u, v = (x + y) % 8, (x - y) % 8
                if u == 0 or v == 0:
                    img.putpixel((x, y), couleurs[0])
                elif u == 1 or v == 1:
                    img.putpixel((x, y), couleurs[3])
    elif motif == "voussoir":
        d.arc((-5, 2, 20, 27), 180, 360, fill=couleurs[0], width=2)
        d.arc((-1, 6, 16, 23), 180, 360, fill=couleurs[3], width=1)
        for ligne in ((7, 2, 7, 7), (1, 4, 4, 8), (13, 4, 11, 8), (0, 9, 2, 11), (15, 9, 13, 11)):
            d.line(ligne, fill=couleurs[0])
        for _ in range(18):
            x, y = rng.randrange(16), rng.randrange(16)
            if img.getpixel((x, y)) == couleurs[2]:
                d.point((x, y), fill=couleurs[1])
    elif motif == "tuiles":
        for y in range(16):
            for x in range(16):
                u = (x + (4 if y // 8 % 2 else 0)) % 8
                tone = [0, 1, 2, 3, 4, 3, 2, 1][u]
                if y % 8 == 0:
                    tone = max(0, tone - 2)
                img.putpixel((x, y), couleurs[tone])
        for _ in range(12):
            x, y = rng.randrange(16), rng.randrange(16)
            if y % 8 > 1 and (x + (4 if y // 8 % 2 else 0)) % 8 in (2, 6):
                d.point((x, y), fill=couleurs[2])
    elif motif == "mosaique":
        for y in range(16):
            for x in range(16):
                dist = abs(x - 7.5) + abs(y - 7.5)
                tone = 0 if 6 < dist < 8 else 3 if 4 < dist <= 6 else 2
                if x in (0, 15) or y in (0, 15):
                    tone = 1
                if (x + y) % 5 == 0 and tone == 2:
                    tone = 3
                img.putpixel((x, y), couleurs[tone])
        d.rectangle((7, 7, 8, 8), fill=couleurs[4])
    elif motif == "grille":
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        for x in (3, 11):
            d.line((x, 0, x, 15), fill=couleurs[1], width=2)
            d.line((x, 0, x, 15), fill=couleurs[3])
        for y in (3, 11):
            d.line((0, y, 15, y), fill=couleurs[1], width=2)
            d.line((0, y, 15, y), fill=couleurs[2])
            for x in (3, 11):
                d.rectangle((x, y, x + 1, y + 1), fill=couleurs[0])
                d.point((x, y), fill=couleurs[4])
    elif motif == "vitrail":
        for y in range(16):
            for x in range(16):
                joint = (x + y) % 8 == 0 or (x - y) % 8 == 0
                tone = 2 + ((x + y) // 8 + (x - y) // 8) % 2
                img.putpixel((x, y), (61, 62, 53, 255) if joint else rgba(matiere.palette[tone], 168))
                if not joint and (x + y) % 8 == 2:
                    img.putpixel((x, y), rgba(matiere.palette[4], 184))
    else:
        raise ValueError(motif)
    assert img.size == (16, 16) and img.mode == "RGBA"
    return img


def font(size: int, custom: str | None = None) -> ImageFont.ImageFont:
    candidats = [custom] if custom else [
        "C:/Windows/Fonts/segoeui.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/System/Library/Fonts/Supplemental/Arial.ttf",
    ]
    for candidat in candidats:
        if candidat and Path(candidat).is_file():
            return ImageFont.truetype(candidat, size=size)
    if custom:
        raise FileNotFoundError(custom)
    return ImageFont.load_default(size=size)


def planche(seed: int, custom_font: str | None) -> Image.Image:
    image = Image.new("RGB", (1200, 1170), "#1d2521")
    d = ImageDraw.Draw(image)
    ivoire, gris, accent = "#eeeadb", "#a5b1a8", "#d8b66c"
    fonts = {n: font(n, custom_font) for n in (14, 16, 18, 22, 45)}
    d.text((36, 24), "ASTRA", font=fonts[45], fill=ivoire)
    d.text((214, 43), "/  carnet de matières", font=fonts[22], fill=accent)
    d.text((36, 87), "60 blocs proposés  ·  12 matières phares  ·  textures originales de 16 × 16 pixels", font=fonts[18], fill=gris)
    d.line((36, 126, 1164, 126), fill="#536057", width=1)
    for i, matiere in enumerate(MATIERES):
        x = 36 + (i % 4) * 287
        y = 151 + (i // 4) * 304
        d.rectangle((x, y, x + 266, y + 283), fill="#28322c")
        d.text((x + 14, y + 10), f"{matiere.numero:02d}", font=fonts[16], fill=accent)
        d.text((x + 45, y + 10), matiere.titre, font=fonts[16], fill=ivoire)
        carre = Image.new("RGBA", (16, 16))
        pixels = carre.load()
        for cy in range(16):
            for cx in range(16):
                valeur = 76 if (cx // 4 + cy // 4) % 2 else 91
                pixels[cx, cy] = (valeur, valeur + 3, valeur - 1, 255)
        carre = Image.alpha_composite(carre, texture(matiere, seed))
        image.paste(carre.resize((192, 192), Image.Resampling.NEAREST).convert("RGB"), (x + 37, y + 42))
        d.text((x + 14, y + 246), matiere.sous_titre, font=fonts[14], fill=gris)
        for j, couleur in enumerate(matiere.palette):
            d.rectangle((x + 14 + j * 17, y + 270, x + 27 + j * 17, y + 273), fill=couleur)
    d.line((36, 1080, 1164, 1080), fill="#536057")
    d.text((36, 1094), "PROPOSITION À VALIDER  /  aucune ressource enregistrée dans le jeu", font=fonts[16], fill=accent)
    d.text((36, 1123), f"Faces de matériaux · agrandissement ×12 sans lissage · damier = transparence · graine {seed}", font=fonts[14], fill=gris)
    return image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=263)
    parser.add_argument("--output", type=Path, default=ROOT / "docs/maquettes/deco_astra_planche.png")
    parser.add_argument("--font", help="Police TTF facultative pour reproduire les légendes à l'identique")
    args = parser.parse_args()
    output = args.output.resolve()
    resources = (ROOT / "src/main/resources").resolve()
    if output.is_relative_to(resources):
        parser.error("Les maquettes doivent rester hors de src/main/resources.")
    textures = [texture(m, args.seed) for m in MATIERES]
    assert len({t.tobytes() for t in textures}) == 12
    assert all(t.tobytes() == texture(m, args.seed).tobytes() for m, t in zip(MATIERES, textures))
    info = PngImagePlugin.PngInfo()
    info.add_text("Projet", "Lanterne / Astra — proposition uniquement")
    info.add_text("Textures", "12 textures originales RGBA, 16x16, agrandies x12 sans interpolation")
    info.add_text("Graine", str(args.seed))
    info.add_text("Catalogue", ",".join(str(m.numero) for m in MATIERES))
    output.parent.mkdir(parents=True, exist_ok=True)
    planche(args.seed, args.font).save(output, pnginfo=info)
    with Image.open(output) as check:
        check.verify()
    with Image.open(output) as check:
        assert check.size == (1200, 1170) and check.format == "PNG"
    print(output)
    print(f"PNG valide, 1200x1170, 12 textures 16x16 ; SHA-256 {hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
