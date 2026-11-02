"""Downloads the raw wikitext of every hypixelskyblock.minecraft.wiki page that uses a drop template the RNG tracker reads
(Template:Mob Drops Table for mobs and sea creatures, Template:Ultra-Rare Chance for the Experiments ultra-rare rewards),
50 pages per API request, into ./wiki/mobs.json  ({title: wikitext}) and ./wiki/ultrarare.json.

Usage: python -I fetch_mobs.py   (fetch.sh runs it). api.php answers plain curl/urllib like index.php (2026-11-02)."""
import json
import os
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
WIKI = os.path.join(HERE, "wiki")
API = "https://hypixelskyblock.minecraft.wiki/api.php"
UA = "killer560smod-rng-tracker/1.0 (drop table)"


def get(params):
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # network hiccup: back off and retry
            if attempt == 3:
                raise
            time.sleep(3 * (attempt + 1))


def pages_using(template):
    out = {}
    cont = {}
    while True:
        params = {"action": "query", "format": "json", "formatversion": "2", "generator": "embeddedin",
                  "geititle": template, "geinamespace": "0", "geilimit": "50", "prop": "revisions",
                  "rvprop": "content", "rvslots": "main"}
        params.update(cont)
        d = get(params)
        for p in d.get("query", {}).get("pages", []):
            revs = p.get("revisions")
            if revs:
                out[p["title"]] = revs[0]["slots"]["main"]["content"]
        if "continue" not in d:
            return out
        cont = d["continue"]
        time.sleep(1)


os.makedirs(WIKI, exist_ok=True)
mobs = pages_using("Template:Mob Drops Table")
json.dump(mobs, open(os.path.join(WIKI, "mobs.json"), "w", encoding="utf-8"), ensure_ascii=False)
print(len(mobs), "mob pages")
ultra = pages_using("Template:Ultra-Rare Chance")
json.dump(ultra, open(os.path.join(WIKI, "ultrarare.json"), "w", encoding="utf-8"), ensure_ascii=False)
print(len(ultra), "ultra-rare pages")
