"""Builds Reforge Helper's table (src/main/resources/assets/killer560smod/reforge/reforges.json) from the
NotEnoughUpdates-REPO's constants/reforges.json: the Blacksmith's basic reforges, each with its NBT modifier, the item
type it rolls on and its stats per rarity. Never type the numbers by hand; rerun this when Hypixel changes them.

    python -I tools/reforge-table/build_reforges.py [reforges.json] [--sha COMMIT]

With no file it downloads master (and the commit it came from, for the stamp)."""
import json
import re
import sys
import urllib.request
from pathlib import Path

REPO = "NotEnoughUpdates/NotEnoughUpdates-REPO"
OUT = Path(__file__).resolve().parents[2] / "src/main/resources/assets/killer560smod/reforge/reforges.json"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "killer560smod-tools"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")


def main():
    args = sys.argv[1:]
    sha = None
    if "--sha" in args:
        i = args.index("--sha")
        sha = args[i + 1]
        del args[i:i + 2]
    if args:
        src = json.loads(Path(args[0]).read_text(encoding="utf-8"))
    else:
        if sha is None:
            sha = json.loads(fetch(f"https://api.github.com/repos/{REPO}/commits?path=constants/reforges.json&per_page=1"))[0]["sha"]
        src = json.loads(fetch(f"https://raw.githubusercontent.com/{REPO}/{sha}/constants/reforges.json"))
    rows = []
    for key in sorted(src, key=str.lower):
        r = src[key]
        types = r["itemTypes"]
        if not isinstance(types, str):
            continue  # an item-specific reforge (only reforge stones have these); the Blacksmith never rolls one
        name = r["reforgeName"]
        # NEU's own rule when nbtModifier is missing: lower case, drop symbols, spaces and dashes to underscores.
        mod = r.get("nbtModifier") or re.sub(r"[\s-]", "_", re.sub(r"[^a-z0-9\s_-]", "", name.lower()))
        stats = {}
        for rarity, s in (r.get("reforgeStats") or {}).items():
            stats[rarity] = {k: (int(v) if float(v).is_integer() else v) for k, v in s.items()}
        rows.append({"n": name, "m": mod, "t": types, "s": stats})
    out = {"source": f"{REPO} constants/reforges.json" + (f" @ {sha}" if sha else ""), "reforges": rows}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, separators=(",", ":")) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(rows)} reforges -> {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
