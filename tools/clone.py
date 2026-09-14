# -*- coding: utf-8 -*-
"""Clone les sources de la mine, en surface, pour pouvoir les LIRE.

Lire une source publique est licite quelle que soit sa licence ; en recopier les
lignes ne l'est que si la licence le permet. Ce script ne fait que la premiere
chose. La colonne `copiable` de notes/MINE.md decide de la seconde, et chaque
depot clone recoit un fichier LISEZ-MOI qui rappelle son verdict.
"""
import json
import os
import subprocess
import sys

MINE = r"C:\Users\trufa\Documents\Lanterne\notes\mine.json"
DEST = r"C:\Users\trufa\Documents\Lanterne-mine"

# Vague 1 : le client. L'ordre est celui du plan.
VAGUE1 = [
    "faster-block-entities", "obe", "moreculling", "cull-leaves", "exordium",
    "immediatelyfast", "particle-core", "fast-items", "sound-culling",
    "better-biome-blend", "entityculling", "sodium", "dynamic-fps", "rrls",
]

# Vague 2 : memoire et demarrage.
VAGUE2 = ["ferrite-core", "modernfix", "ksyxis", "fastsuite", "fastevent", "quick-pack"]

# Vague 3 : le serveur.
VAGUE3 = [
    "alternate-current", "zfastnoise", "structure-layout-optimizer", "harium",
    "does-it-tick", "despawn-tweaks", "efficient-hashing", "faster-random",
    "krypton", "lithium", "moonrise-opt", "servercore", "noisiumforked",
]


def main():
    quels = sys.argv[1:] or (VAGUE1 + VAGUE2 + VAGUE3)
    d = json.load(open(MINE, encoding="utf-8"))
    os.makedirs(DEST, exist_ok=True)

    for slug in quels:
        info = d.get(slug)
        if not info:
            print(f"  ? {slug} : absent de la mine")
            continue
        url = info.get("source")
        if not url:
            print(f"  - {slug} : pas de source publique")
            continue
        cible = os.path.join(DEST, slug)
        if os.path.isdir(cible):
            print(f"  = {slug} : deja la")
            continue
        print(f"  > {slug} ({info.get('licence')}) {url}", flush=True)
        r = subprocess.run(
            ["git", "clone", "--depth", "1", "--quiet", url, cible],
            capture_output=True, text=True, timeout=600,
        )
        if r.returncode != 0:
            print(f"    echec : {r.stderr.strip()[:140]}")
            continue
        # Le verdict, ecrit dans le depot lui-meme : impossible de l'oublier en
        # ouvrant un fichier six semaines plus tard.
        lic = info.get("licence", "?")
        dur = lic.lower()
        copiable = not any(k in dur for k in (
            "all-rights", "-nc-", "-nd-", "polyform", "protective", "agpl",
            "lgpl-2.1-only", "licenseref-custom"))
        with open(os.path.join(cible, "LISEZ-MOI-LANTERNE.md"), "w", encoding="utf-8") as f:
            f.write(f"# {slug}\n\nLicence : **{lic}**\n\n")
            f.write("**Code copiable sous GPL-3.0.**\n" if copiable
                    else "**Code NON copiable.** Lire pour comprendre, reecrire depuis l'idee.\n")
            f.write(f"\nSource : {url}\n")

    print(f"\nClones dans {DEST}")


if __name__ == "__main__":
    main()
