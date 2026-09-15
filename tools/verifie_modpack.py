#!/usr/bin/env python3
"""Confronte les dependances declarees d'un dossier de mods a ce qui s'y trouve.

Pourquoi ce script existe
-------------------------
Un mod qui manque ne se signale pas par un avertissement : le jeu refuse de demarrer, et
le message est noye dans une page d'erreur. Autant le savoir AVANT de lancer, surtout
quand on assemble un modpack a la main.

Ce qu'il lit
------------
Le `META-INF/neoforge.mods.toml` de chaque jar : les identifiants fournis et les
dependances declarees, avec leur caractere obligatoire et leur intervalle de version.

Ce qu'il ne fait PAS
--------------------
Il ne verifie pas les intervalles de version. Les comparer demanderait de reimplementer
l'ordre de versions de Maven, et se tromper la-dessus donnerait de FAUX refus — plus
nuisibles qu'un silence. Il affiche donc l'intervalle et laisse lire.

Emploi
------
    python tools/verifie_modpack.py "<dossier mods>"
"""

import io
import os
import re
import sys
import zipfile

for flux in (sys.stdout, sys.stderr):
    try:
        flux.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

# Fournis par le jeu lui-meme : jamais des jars du dossier.
SOCLE = {"minecraft", "neoforge", "forge", "java", "fml"}


def lit_toml(jar):
    """Le texte du descripteur, ou None si le jar n'en a pas."""
    try:
        with zipfile.ZipFile(jar) as z:
            for nom in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                if nom in z.namelist():
                    return z.read(nom).decode("utf-8", "replace")
    except (zipfile.BadZipFile, OSError) as illisible:
        return "###ILLISIBLE### " + str(illisible)
    return None


def analyse(texte):
    """Rend (identifiants fournis, [(cible, obligatoire, intervalle)])."""
    fournis = re.findall(r'^\s*modId\s*=\s*"([^"]+)"', texte, re.M)
    # Les dependances sont des tables nommees [[dependencies.<qui>]] ; on decoupe dessus
    # pour ne pas confondre le modId d'un mod avec celui d'une de ses dependances.
    besoins = []
    blocs = re.split(r'\[\[dependencies\.[^\]]+\]\]', texte)[1:]
    for bloc in blocs:
        cible = re.search(r'^\s*modId\s*=\s*"([^"]+)"', bloc, re.M)
        if not cible:
            continue
        obligatoire = True
        m = re.search(r'^\s*(?:mandatory|required)\s*=\s*(true|false)', bloc, re.M)
        if m:
            obligatoire = m.group(1) == "true"
        t = re.search(r'^\s*type\s*=\s*"([^"]+)"', bloc, re.M)
        if t:
            obligatoire = t.group(1).lower() == "required"
        intervalle = re.search(r'^\s*versionRange\s*=\s*"([^"]*)"', bloc, re.M)
        besoins.append((cible.group(1), obligatoire,
                        intervalle.group(1) if intervalle else ""))
    return fournis, besoins


def main(argv):
    dossier = argv[0] if argv else "."
    jars = sorted(f for f in os.listdir(dossier) if f.endswith(".jar"))
    if not jars:
        raise SystemExit("aucun jar dans " + dossier)

    fournis = {}
    besoins = {}
    muets = []
    for nom in jars:
        texte = lit_toml(os.path.join(dossier, nom))
        if texte is None:
            muets.append(nom)
            continue
        if texte.startswith("###ILLISIBLE###"):
            muets.append(nom + "  (" + texte[16:] + ")")
            continue
        ids, deps = analyse(texte)
        for i in ids:
            fournis[i] = nom
        besoins[nom] = deps

    print("=== %d jar(s), %d identifiant(s) fourni(s) ===" % (len(jars), len(fournis)))
    for i in sorted(fournis):
        print("  %-28s  %s" % (i, fournis[i]))

    if muets:
        print("\n--- sans descripteur (ni mod NeoForge, ni lisible) ---")
        for m in muets:
            print("  " + m)

    manquants = []
    print("\n=== dependances ===")
    for nom in sorted(besoins):
        deps = besoins[nom]
        if not deps:
            continue
        lignes = []
        for cible, obligatoire, intervalle in deps:
            if cible in SOCLE:
                continue
            present = cible in fournis
            marque = "OK " if present else ("MANQUE" if obligatoire else "absent")
            if not present and obligatoire:
                manquants.append((nom, cible, intervalle))
            lignes.append("      %-8s %-24s %s%s"
                          % (marque, cible, intervalle,
                             "" if obligatoire else "   (facultatif)"))
        if lignes:
            print("  %s" % nom)
            print("\n".join(lignes))

    print()
    if manquants:
        print("=== %d DEPENDANCE(S) OBLIGATOIRE(S) MANQUANTE(S) ===" % len(manquants))
        for nom, cible, intervalle in manquants:
            print("  %s exige %s %s" % (nom, cible, intervalle))
        print("\nLe jeu refusera de demarrer.")
    else:
        print("Aucune dependance obligatoire manquante.")
        print("Rappel : les INTERVALLES de version ne sont pas verifies — les comparer")
        print("demanderait de reimplementer l'ordre de Maven, et s'y tromper donnerait de")
        print("faux refus. Ils sont affiches ci-dessus, a lire.")


if __name__ == "__main__":
    main(sys.argv[1:])
