"""Audit statique des blocs enregistrés, butins 26.3, items et outils (sans dépendance)."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "src/main/resources/data"
JAVA = ROOT / "src/main/java/fr/clubcitrouille/lanterne/content"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    sources = "\n".join(p.read_text(encoding="utf-8") for p in JAVA.rglob("*.java"))
    blocks = set(re.findall(r'BLOCKS\.registerBlock\(\s*"([a-z_]+)"', sources))
    items = set(re.findall(r'ITEMS\.registerSimpleBlockItem\(\s*"([a-z_]+)"', sources))
    technical = {"portail_repere"}
    expected = {
        "anchor", "ancre_de_ferme", "graveur", "projecteur", "ecran", "veilleuse",
        "coeur_de_repere", "guichet", "recycleur", "trieur", "aiguillage",
        "coffre_depot", "coffre_reception",
    }
    assert blocks == expected | technical, f"Audit à compléter : {blocks ^ (expected | technical)}"
    assert items == expected, f"Items non concordants : {items ^ expected}"
    loot_dir = DATA / "lanterne/loot_table/blocks"
    assert {p.stem for p in loot_dir.glob("*.json")} == expected
    tools = {}
    for tool in ("pickaxe", "axe"):
        tag = read_json(DATA / f"minecraft/tags/block/mineable/{tool}.json")
        assert not tag.get("replace", False), "Ne pas remplacer les blocs vanilla"
        tools[tool] = set(tag["values"])
    assert tools["axe"] == {"lanterne:coffre_depot", "lanterne:coffre_reception"}
    assert tools["pickaxe"] == {f"lanterne:{b}" for b in expected} - tools["axe"]
    diamond = read_json(DATA / "minecraft/tags/block/needs_diamond_tool.json")
    assert set(diamond["values"]) == {f"lanterne:{b}" for b in ("anchor", "ancre_de_ferme", "coeur_de_repere")}
    for block in sorted(expected):
        table = read_json(loot_dir / f"{block}.json")
        assert table["type"] == "minecraft:block"
        assert len(table["pools"]) == 1
        pool = table["pools"][0]
        assert pool["rolls"] == 1 and pool.get("bonus_rolls", 0) == 0
        assert pool["condition"] == {"type": "minecraft:survives_explosion"}
        assert "conditions" not in pool and "functions" not in pool
        assert len(pool["entries"]) == 1
        entry = pool["entries"][0]
        assert entry["type"] == "minecraft:item" and entry["name"] == f"lanterne:{block}"
        assert "functions" not in entry
        if block in {"coffre_depot", "coffre_reception", "trieur"}:
            assert entry["modifier"] == {
                "type": "minecraft:copy_components", "source": "block_entity",
                "include": ["minecraft:custom_name"],
            }, "Le contenu tombe par le cycle vanilla, jamais copié une seconde fois dans l'item"
        print(f"OK {block}: table 26.3, item homonyme, outil")
    print("OK portail_repere: bloc technique exclu du butin et des items")
    print("14 blocs audités. Contrôle statique uniquement ; ne remplace pas un essai en survie.")


if __name__ == "__main__":
    main()
