#!/usr/bin/env python3
"""Denombre l'arbre de fonctions de densite d'un monde, par type de noeud.

Pourquoi ce script existe
-------------------------
Le banc dit que l'etape « noise » pese 64 % du temps de generation sur un seul fil, et
le profil par echantillonnage ne met que 12,3 % sur `ImprovedNoise.noise` — le bruit de
Perlin lui-meme. Les cinquante pour cent restants sont donc AILLEURS, et la seule
candidate est l'interpretation de l'arbre : un appel virtuel par noeud et par position.

Avant d'optimiser cet arbre, il faut savoir de quoi il est fait. Ce script le lit dans
les donnees du jeu — les fichiers JSON du paquet vanilla — et non dans une supposition.

Ce qu'il compte, et la nuance qui compte
----------------------------------------
Un noeud reference depuis trois endroits est EVALUE trois fois (sauf si le jeu l'a mis
en cache). On denombre donc l'arbre **deploye**, references resolues, et pas le nombre
de fichiers. C'est le deuxieme chiffre qui dit le travail reel.

Emploi
------
    python tools/arbre_densite.py <racine extraite> [nom du noise_settings]
"""

import collections
import io
import json
import os
import sys

for flux in (sys.stdout, sys.stderr):
    try:
        flux.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


def charge(racine):
    """Toutes les fonctions de densite nommees, indexees par identifiant."""
    base = os.path.join(racine, "data", "minecraft", "worldgen", "density_function")
    table = {}
    for dossier, _, fichiers in os.walk(base):
        for nom in fichiers:
            if not nom.endswith(".json"):
                continue
            chemin = os.path.join(dossier, nom)
            cle = os.path.relpath(chemin, base).replace(os.sep, "/")[: -len(".json")]
            with io.open(chemin, encoding="utf-8") as handle:
                table["minecraft:" + cle] = json.load(handle)
    return table


class Compteur:
    def __init__(self, table):
        self.table = table
        self.types = collections.Counter()
        self.noeuds = 0
        # Une reference circulaire ferait tourner ce script indefiniment. Les fonctions
        # de densite n'en ont pas, mais un paquet de donnees tiers pourrait en
        # introduire, et un outil d'analyse ne doit pas dependre de la bonne foi de son
        # entree.
        self.pile = []
        self.profondeur_max = 0
        self.cycles = 0

    def visite(self, noeud):
        self.noeuds += 1
        self.profondeur_max = max(self.profondeur_max, len(self.pile))

        # Une constante peut s'ecrire comme un nombre nu.
        if isinstance(noeud, (int, float)):
            self.types["<constante nue>"] += 1
            return

        # Une chaine est une reference a une fonction nommee.
        if isinstance(noeud, str):
            if noeud in self.pile:
                self.cycles += 1
                return
            cible = self.table.get(noeud)
            if cible is None:
                self.types["<reference inconnue : %s>" % noeud] += 1
                return
            self.pile.append(noeud)
            self.visite(cible)
            self.pile.pop()
            return

        if isinstance(noeud, list):
            for element in noeud:
                self.visite(element)
            return

        if not isinstance(noeud, dict):
            self.types["<inattendu %s>" % type(noeud).__name__] += 1
            return

        genre = noeud.get("type", "<sans type>")
        self.types[genre] += 1
        for cle, valeur in noeud.items():
            if cle == "type":
                continue
            # On ne descend que dans ce qui peut porter une fonction : les nombres nus
            # d'un noeud (rarete, amplitude...) ne sont pas des sous-arbres.
            if isinstance(valeur, (dict, list, str)):
                self.visite(valeur)


def main(argv):
    racine = argv[0] if argv else "."
    reglages = argv[1] if len(argv) > 1 else "overworld"

    table = charge(racine)
    chemin = os.path.join(racine, "data", "minecraft", "worldgen", "noise_settings",
                          reglages + ".json")
    with io.open(chemin, encoding="utf-8") as handle:
        settings = json.load(handle)

    routeur = settings.get("noise_router", {})
    print("=== %s : %d entree(s) de routeur, %d fonction(s) nommees au total ==="
          % (reglages, len(routeur), len(table)))

    total = Compteur(table)
    par_entree = []
    for nom, valeur in sorted(routeur.items()):
        un = Compteur(table)
        un.visite(valeur)
        par_entree.append((un.noeuds, nom, un.profondeur_max))
        total.visite(valeur)

    print("\n--- par entree du routeur, arbre DEPLOYE (references resolues) ---")
    for noeuds, nom, profondeur in sorted(par_entree, reverse=True):
        print("  %7d noeuds  profondeur %3d   %s" % (noeuds, nom, profondeur)
              if False else "  %7d noeuds  profondeur %3d   %s" % (noeuds, profondeur, nom))

    print("\n--- types de noeuds, tout le routeur (%d noeuds deployes) ---" % total.noeuds)
    for genre, combien in total.types.most_common():
        part = 100.0 * combien / max(1, total.noeuds)
        print("  %7d  %5.1f %%  %s" % (combien, part, genre))

    if total.cycles:
        print("\nATTENTION : %d reference(s) circulaire(s) coupee(s)." % total.cycles)


if __name__ == "__main__":
    main(sys.argv[1:])
