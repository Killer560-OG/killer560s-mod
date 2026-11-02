"""Builds the RNG tracker's shipped drop table from raw hypixelskyblock.minecraft.wiki wikitext (downloaded 2026-11-01
into ./wiki via index.php?action=raw). Usage: python -I rng_build_table.py <slayers.json> <out drop-table.json>

Every number comes from the wiki text; nothing is estimated here except the arithmetic noted per section."""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
WIKI = os.path.join(HERE, "wiki")
BASE = "https://hypixelskyblock.minecraft.wiki/w/"
ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]
ROMAN_N = {r: i for i, r in enumerate(ROMAN) if r}
BOSS = {"revenant": "Revenant Horror", "tarantula": "Tarantula Broodfather", "sven": "Sven Packmaster",
        "voidgloom": "Voidgloom Seraph", "inferno": "Inferno Demonlord", "vampire": "Riftstalker Bloodfiend"}
CHESTS = ["Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock"]

activities = {}
drops = []


def activity(key, label, group, url):
    activities.setdefault(key, {"label": label, "group": group, "url": url})


def add(**d):
    d.pop("key", None)  # derived: activity + "|" + name
    drops.append({k: v for k, v in d.items() if v is not None})


# ---------------------------------------------------------------- slayers
rows = json.load(open(sys.argv[1], encoding="utf-8"))
by_tier = {}
for r in rows:
    by_tier.setdefault((r["boss"], r["tier"]), []).append(r)
for (boss, tier), rs in sorted(by_tier.items()):
    key = f"slayer:{boss}:{tier}"
    activity(key, f"{BOSS[boss]} {ROMAN[tier]}", "Slayers", rs[0]["url"])
    token = sum(r["weight"] or 0 for r in rs if r["pool"] == "Token")
    main = sum(r["weight"] or 0 for r in rs if r["pool"] == "Main")
    extra = sum(r["weight"] or 0 for r in rs if r["pool"] == "Extra")
    for r in rs:
        pool = r["pool"] or ("Separate" if r["chance"] is not None and r["chance"] < 0.0001 else None)
        if pool in (None, "Blankcell"):
            pool = "Separate"
        if pool == "Token":
            continue  # guaranteed token drop, never a "luck" item
        chance = r["chance"]
        how = "wiki"
        if boss == "revenant":
            # Revenant's page gives weights only (Calculator:Slayer Drops); Slayer#Boss Loot: Main = w/(token+main),
            # Extra = w/(token+main+extra), Separate = the flat chance itself.
            w = r["weight"]
            chance = w if pool == "Separate" else w / (token + main) if pool == "Main" else w / (token + main + extra)
            how = "weights"
        if chance is None or chance >= 1:
            continue
        # Slayer#Boss Loot: Magic Find raises Main/Extra items whose chance is under 5%; Separate is "[Confirm]" there.
        mf = pool in ("Main", "Extra") and chance < 0.05
        add(key=f"{key}|{r['name']}", name=r["name"], activity=key, chance=round(chance, 10), pool=pool,
            req=r["req"] or None, mf=mf or None, cat="dye" if r["name"].endswith(" Dye") else "slayer",
            how=how)

# ---------------------------------------------------------------- dungeon reward chests
def book(entry):
    m = re.match(r"Enchanted Book &(.+) (\d+)&$", entry)
    return f"Enchanted Book ({m.group(1)} {ROMAN[int(m.group(2))]})" if m else entry


def chance_of(s):
    m = re.search(r"\{\{Chance\|([\d.]+)%", s or "")
    return float(m.group(1)) / 100 if m else None


for mm in (False, True):
    for n in range(1, 8):
        fname = f"loot_{'M' if mm else 'F'}_{ROMAN[n]}.wiki"
        page = f"The_Catacombs_-_Floor_{ROMAN[n]}/{'Master_Mode_Loot' if mm else 'Loot'}"
        src = open(os.path.join(WIKI, fname), encoding="utf-8").read()
        floor = ("M" if mm else "F") + str(n)
        for e in re.findall(r"\{\{Catacombs Chest Loot Table Entry(.*?)\n\}\}", src, re.S):
            f = dict(re.findall(r"\|[ \t]*(\w+)[ \t]*=[ \t]*([^\n]*)", e))
            entry = f["entry"].strip()
            if "Essence" in entry:
                continue
            chest = f["chest"].strip()
            key = f"chest:{floor}:{chest}"
            activity(key, f"{floor} {chest} Chest", "Dungeon Chests", BASE + page)
            cn = chance_of(f.get("average_chance_no_bonuses"))
            cx = chance_of(f.get("average_chance_max_bonuses"))
            if cn is None and cx is None:
                continue
            name = book(entry)
            add(key=f"{key}|{name}", name=name, activity=key, chance=cn, chanceMax=cx,
                cat="dye" if name.endswith(" Dye") else "dungeon")

# ---------------------------------------------------------------- dyes with other sources (Dyes page)
# Only rows whose attempt the mod can count today are tied to an activity; the rest are listed by name so a drop is
# still recorded (observed count, no expectation) until its attempts are tracked.
dsrc = open(os.path.join(WIKI, "Dyes.wiki"), encoding="utf-8").read().split("=== Animated Dyes")[0]
listed = {d["name"] for d in drops}
for row in dsrc.split("\n|-"):
    m = re.search(r"\{\{Item\|([^}|]+ Dye)\}\}", row)
    if not m or m.group(1) in listed:
        continue
    body = row.split('text-align:left;" |', 1)[-1]
    if "chance" not in body:
        continue  # bought, raffle or event: not a drop
    body = re.sub(r"\{\{overline\|(\d+)\}\}", r"\1", body)
    first = re.search(r"\{\{chance\|[^|}]*\|1\|([\d.,]+[kKM]?)\}\}\s*(.*?)(?:\n|\*|$)", body)
    text = first.group(2) if first else ""
    text = re.sub(r"<ref.*?(?:</ref>|/>)", "", text)
    text = re.sub(r"\[\[File:[^]]*\]\]", "", text)
    for _ in range(3):
        text = re.sub(r"\{\{Lv\|([^}]*)\}\}", lambda mm: "Lv " + re.sub(r"[^\d]+", "-", mm.group(1)), text)
        text = re.sub(r"\{\{[A-Za-z ]+\|([^{}|]*)\}\}", r"\1", text)
        text = re.sub(r"\{\{(Common|Uncommon|Rare|Epic|Legendary|Mythic)\}\}", r"\1", text)
    text = re.sub(r"\[\[(?:[^]|]*\|)?([^]]*)\]\]", r"\1", text)
    text = re.sub(r"\s+", " ", text).strip()
    add(key=f"untracked|{m.group(1)}", name=m.group(1), activity="untracked", chanceText="1/" + first.group(1) + " " + text if first else None,
        cat="dye")

activity("untracked", "Attempts not counted yet", "Other", BASE + "Dyes")
out = {
    "version": 1,
    "built": "2026-11-01",
    "source": "hypixelskyblock.minecraft.wiki raw wikitext, fetched 2026-11-01",
    "notes": [
        "chest:<floor>:<chest> chance = the wiki's 'Average Chance (No Bonuses)' per opened chest; chanceMax = 'Average Chance (S+ Max Bonuses)'. Pages: The_Catacombs_-_Floor_<N>/Loot and /Master_Mode_Loot.",
        "slayer:<boss>:<tier> chance = the boss page's Drop Chance column (every drop unlocked, no Magic Find, empty RNG Meter); Revenant Horror's page lists weights only, so its chances are weight/(token+main) for Main and weight/(token+main+extra) for Extra, per Slayer#Boss Loot; Separate entries are flat chances.",
        "mf=true: Slayer#Boss Loot says Magic Find multiplies the weight of Main/Extra items under a 5% chance by (1 + MF/100).",
        "Drop key = activity + \"|\" + name. untracked: a dye whose attempts the mod does not count yet; drops are still recorded.",
    ],
    "activities": activities,
    "drops": drops,
}
os.makedirs(os.path.dirname(os.path.abspath(sys.argv[2])), exist_ok=True)
json.dump(out, open(sys.argv[2], "w", encoding="utf-8", newline="\n"), ensure_ascii=False, separators=(",", ":"))
from collections import Counter
print(len(activities), "activities;", Counter(d["cat"] for d in drops), "drops")
