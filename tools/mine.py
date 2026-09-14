# -*- coding: utf-8 -*-
"""Trie la mine d'or : pour chaque mod de la liste, licence, versions, source.

Le but n'est pas de telecharger des jars. C'est de savoir, avant d'ecrire une
ligne de code : est-ce que ce mod existe deja en 26.1 (donc inutile de
l'absorber), sous quelle licence (donc absorbable ou non), et ou est son code.
"""
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = "Lanterne/2.1 (regis.cottereau.fr@gmail.com)"
LISTE = r"C:\Users\trufa\Downloads\mods_important_certainsmauvaiseversionMAIS_CODESOURCE.md"
SORTIE = r"C:\Users\trufa\Documents\Lanterne\notes\mine.json"

# Licences qui autorisent l'absorption si Lanterne passe sous la meme famille.
COPYLEFT = ("lgpl", "gpl", "agpl", "mpl", "epl", "osl")
PERMISSIVE = ("mit", "apache", "bsd", "isc", "unlicense", "cc0", "zlib", "wtfpl")


def slugs():
    vus, sortie = set(), []
    with open(LISTE, encoding="utf-8") as f:
        for ligne in f:
            for m in re.finditer(r"modrinth\.com/(?:mod|plugin|datapack)/([^\s:)\]]+)", ligne):
                s = urllib.parse.unquote(m.group(1)).strip().rstrip("/")
                if s and s not in vus:
                    vus.add(s)
                    sortie.append(s)
    return sortie


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.load(r)


def famille(licence):
    bas = (licence or "").lower()
    if any(k in bas for k in COPYLEFT):
        return "copyleft"
    if any(k in bas for k in PERMISSIVE):
        return "permissive"
    if "arr" in bas or "all rights" in bas:
        return "ferme"
    return "inconnue"


def tri_version(v):
    """Ordonne 1.20 < 1.21.5 < 26.1 : le calendaire passe devant le semantique."""
    m = re.match(r"^(\d+)\.(\d+)(?:\.(\d+))?$", v)
    if not m:
        return (-1, 0, 0, 0)
    a, b, c = int(m.group(1)), int(m.group(2)), int(m.group(3) or 0)
    return (1 if a >= 25 else 0, a, b, c)


def main():
    resultats = {}
    noms = slugs()
    print(f"{len(noms)} mods a trier.", flush=True)
    for i, s in enumerate(noms, 1):
        try:
            p = get(f"https://api.modrinth.com/v2/project/{urllib.parse.quote(s)}")
        except urllib.error.HTTPError as e:
            resultats[s] = {"erreur": f"HTTP {e.code}"}
            print(f"  [{i}/{len(noms)}] {s} : introuvable ({e.code})", flush=True)
            time.sleep(0.12)
            continue
        except Exception as e:  # reseau
            resultats[s] = {"erreur": str(e)[:60]}
            time.sleep(0.5)
            continue

        versions = p.get("game_versions") or []
        stables = [v for v in versions if re.match(r"^\d+\.\d+(\.\d+)?$", v)]
        haute = max(stables, key=tri_version) if stables else ""
        lic = (p.get("license") or {}).get("id", "")
        resultats[s] = {
            "titre": p.get("title", ""),
            "licence": lic,
            "famille": famille(lic),
            "source": p.get("source_url") or "",
            "max_mc": haute,
            "a_26": any(v.startswith("26.") for v in versions),
            "loaders": p.get("loaders", []),
            "dl": p.get("downloads", 0),
            "resume": (p.get("description") or "")[:110],
        }
        if i % 20 == 0:
            print(f"  [{i}/{len(noms)}]", flush=True)
        time.sleep(0.12)

    with open(SORTIE, "w", encoding="utf-8") as f:
        json.dump(resultats, f, ensure_ascii=False, indent=1)
    print(f"\nEcrit dans {SORTIE}", flush=True)

    ok26 = [s for s, d in resultats.items() if d.get("a_26")]
    print(f"\nDeja en 26.x ({len(ok26)}) : {', '.join(sorted(ok26))}")


if __name__ == "__main__":
    sys.exit(main())
