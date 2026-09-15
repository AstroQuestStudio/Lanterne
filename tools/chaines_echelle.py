# -*- coding: utf-8 -*-
"""Genere les chaines de post-traitement de la mise a l'echelle.

Huit fichiers : quatre crans de nettete, avec et sans anticrenelage. Ils ne
different que par deux choses -- la presence de la passe d'anticrenelage, et la
valeur de nettete passee a RCAS -- et les ecrire a la main reviendrait a
recopier huit fois la meme structure en esperant ne pas se tromper d'un chiffre.

Pourquoi l'anticrenelage est une passe SEPAREE et non un drapeau du shader :
une chaine PostChain est figee a la compilation, ses uniformes sont lus une
seule fois depuis le JSON. Un drapeau ne pourrait pas changer en cours de
partie. Deux jeux de chaines, en revanche, se choisissent par leur nom.

Pourquoi la cible « lanterne:aa » est EXTERNE : une cible interne declaree dans
le JSON prend par defaut la taille de l'ecran, alors que l'anticrenelage doit
travailler a la taille REDUITE -- avant la remontee, pas apres. Le mod la
fournit donc lui-meme, comme il fournit deja « lanterne:scene ».

    python tools/chaines_echelle.py
"""

import json
import os

RACINE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "..", "src", "main", "resources", "assets", "lanterne", "post_effect")

QUAD = "minecraft:core/screenquad"

# Nom du cran -> nettete RCAS. None veut dire « pas de passe RCAS du tout ».
CRANS = {
    "aucune": None,
    "douce": 0.35,
    "moyenne": 0.6,
    "forte": 0.9,
}


def passe(fragment, entree, sortie, bilinear=False, uniformes=None):
    bloc = {
        "vertex_shader": QUAD,
        "fragment_shader": fragment,
        "inputs": [{"sampler_name": "In", "target": entree, "bilinear": bilinear}],
        "output": sortie,
    }
    if uniformes:
        bloc["uniforms"] = uniformes
    return bloc


def chaine(nettete, anticrenelage):
    passes = []
    # La source d'EASU : la toile brute, ou la toile anticrenelee.
    source = "lanterne:scene"
    cibles = {}

    if anticrenelage:
        passes.append(passe("lanterne:post/aa_edge", "lanterne:scene", "lanterne:aa"))
        source = "lanterne:aa"

    if nettete is None:
        # Sans RCAS, EASU ecrit directement sur la cible principale : une passe
        # de moins, et surtout pas de cible intermediaire a allouer.
        passes.append(passe("lanterne:post/fsr_easu", source, "minecraft:main"))
    else:
        cibles["swap"] = {"persistent": True}
        passes.append(passe("lanterne:post/fsr_easu", source, "swap"))
        passes.append(passe(
            "lanterne:post/fsr_rcas", "swap", "minecraft:main",
            uniformes={"RcasConfig": [
                {"name": "Tuning", "type": "vec4", "value": [nettete, 0.0, 0.0, 0.0]}
            ]}))

    document = {}
    if cibles:
        document["targets"] = cibles
    document["passes"] = passes
    return document


def main():
    ecrits = 0
    for cran, nettete in CRANS.items():
        for anticrenelage in (False, True):
            nom = "upscale_{}{}.json".format("aa_" if anticrenelage else "", cran)
            chemin = os.path.normpath(os.path.join(RACINE, nom))
            texte = json.dumps(chaine(nettete, anticrenelage), indent=4,
                               ensure_ascii=False) + "\n"
            with open(chemin, "w", encoding="utf-8", newline="\n") as fichier:
                fichier.write(texte)
            print("ecrit", nom)
            ecrits += 1
    print(ecrits, "chaines")


if __name__ == "__main__":
    main()
