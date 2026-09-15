#!/usr/bin/env python3
"""Appelle le connecteur MineStrator en JSON-RPC direct.

Pourquoi ce script existe
-------------------------
Le connecteur est declare dans `~/.claude.json` sous le projet `Documents/Lanterne`.
Une session ouverte depuis un AUTRE repertoire — l'instance de jeu, par exemple — ne le
charge pas : Claude Code ne monte que les serveurs du repertoire courant. Le connecteur
repond pourtant parfaitement en HTTP.

Ce script court-circuite donc le montage et parle au serveur directement. Il sert aussi
quand on veut scripter une suite d'appels (un protocole de mesure, une collecte de logs)
plutot que de les passer un par un.

Le jeton n'est PAS ecrit ici : il est relu depuis `~/.claude.json`. Ce fichier peut donc
etre versionne sans rien divulguer.

Emploi
------
    python tools/minestrator.py list                 # les outils disponibles
    python tools/minestrator.py <outil> '<json>'     # appelle un outil
    python tools/minestrator.py <outil> k=v k=v      # forme courte, valeurs typees
"""

import io
import json
import os
import subprocess
import sys

CONFIG = os.path.expanduser("~/.claude.json")
PROJET = "C:/Users/trufa/Documents/Lanterne"

# La console de Windows est en cp1252. Les logs d'un serveur Minecraft ne le sont pas :
# ils charrient des accents, des symboles de section et ce que les joueurs ont tape dans
# le chat. Sans cette ligne, lire les logs leve une UnicodeEncodeError — et pire, une
# sortie longue s'interrompt EN SILENCE au premier caractere indesirable, ce qui donne une
# liste tronquee qu'on prend pour une liste complete.
for flux in (sys.stdout, sys.stderr):
    try:
        flux.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


def credentials():
    """Le point d'acces et le jeton, releves dans la configuration de Claude Code.

    On accepte les deux emplacements : la portee « utilisateur », et l'ancienne entree
    rangee sous le projet Lanterne. Une seule des deux suffit.
    """
    with io.open(CONFIG, encoding="utf-8") as handle:
        data = json.load(handle)
    candidats = [
        (data.get("mcpServers") or {}).get("minestrator"),
        ((data.get("projects") or {}).get(PROJET) or {}).get("mcpServers", {}).get(
            "minestrator"
        ),
    ]
    for serveur in candidats:
        if serveur:
            return serveur["url"], serveur["headers"]["Authorization"]
    raise SystemExit(
        "Connecteur minestrator introuvable dans " + CONFIG + " — le declarer avec :\n"
        "  claude mcp add --scope user --transport http minestrator <url> "
        '--header "Authorization: Bearer <jeton>"'
    )


def rpc(method, params):
    """Un appel JSON-RPC, passe par curl.

    Pourquoi curl et non `urllib` : le certificat de `mcp.sttr.io` est EXPIRE. Le magasin
    de Python le refuse — a juste titre — tandis que curl l'accepte avec le sien. On ne
    desactive donc pas la verification, on se contente du magasin qui accepte deja cet
    hote ; le jour ou le certificat sera renouvele, rien ici ne changera.
    """
    url, jeton = credentials()
    corps = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
    acheve = subprocess.run(
        [
            "curl", "-s", "-S", "-X", "POST", url,
            "-H", "Authorization: " + jeton,
            "-H", "Content-Type: application/json",
            # Le transport « streamable HTTP » autorise les deux formes de reponse. On
            # accepte les deux et l'on demele ensuite : certains serveurs repondent en
            # JSON simple, d'autres en flux d'evenements, et cela peut changer par outil.
            "-H", "Accept: application/json, text/event-stream",
            "--max-time", "180",
            "-d", corps,
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if acheve.returncode != 0:
        raise SystemExit("curl a echoue (%d) : %s" % (acheve.returncode, acheve.stderr))
    return demele(acheve.stdout)


def demele(brut):
    """Rend l'objet JSON-RPC, que la reponse soit du JSON nu ou un flux d'evenements."""
    brut = brut.strip()
    if brut.startswith("{"):
        return json.loads(brut)
    for ligne in brut.splitlines():
        if ligne.startswith("data:"):
            fragment = ligne[5:].strip()
            if fragment and fragment != "[DONE]":
                return json.loads(fragment)
    raise SystemExit("Reponse incomprise : " + brut[:500])


def texte(resultat):
    """Extrait le texte d'un resultat d'outil, en gardant le JSON s'il y en a."""
    if "error" in resultat:
        return "ERREUR " + json.dumps(resultat["error"], ensure_ascii=False, indent=2)
    contenu = resultat.get("result", {}).get("content", [])
    morceaux = []
    for bloc in contenu:
        if bloc.get("type") == "text":
            morceaux.append(bloc["text"])
        else:
            morceaux.append(json.dumps(bloc, ensure_ascii=False, indent=2))
    if not morceaux:
        return json.dumps(resultat.get("result", {}), ensure_ascii=False, indent=2)
    return "\n".join(morceaux)


def typer(valeur):
    """Convertit une valeur de ligne de commande : un ID de serveur doit etre un entier."""
    if valeur.lower() in ("true", "false"):
        return valeur.lower() == "true"
    try:
        return int(valeur)
    except ValueError:
        pass
    try:
        return float(valeur)
    except ValueError:
        return valeur


def main(argv):
    if not argv:
        raise SystemExit(__doc__)

    if argv[0] == "list":
        reponse = rpc("tools/list", {})
        outils = reponse.get("result", {}).get("tools", [])
        detaille = len(argv) > 1 and argv[1] == "-v"
        print("%d outils" % len(outils))
        for outil in outils:
            print("\n### " + outil["name"])
            description = outil.get("description") or ""
            print("    " + (description if detaille else description[:180]))
            schema = outil.get("inputSchema") or {}
            requis = schema.get("required", [])
            for nom, champ in (schema.get("properties") or {}).items():
                marque = " [REQUIS]" if nom in requis else ""
                aide = (champ.get("description") or "")[:110]
                print("      - %s (%s)%s %s" % (nom, champ.get("type"), marque, aide))
        return

    outil = argv[0]
    arguments = {}
    for brut in argv[1:]:
        if brut.startswith("{"):
            arguments.update(json.loads(brut))
        elif "=" in brut:
            cle, _, valeur = brut.partition("=")
            arguments[cle] = typer(valeur)
        else:
            raise SystemExit("Argument incompris : " + brut)

    print(texte(rpc("tools/call", {"name": outil, "arguments": arguments})))


if __name__ == "__main__":
    main(sys.argv[1:])
