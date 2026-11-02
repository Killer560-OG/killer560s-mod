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

# ---------------------------------------------------------------- mobs and sea creatures (every Mob Drops Table page)
# fetch_mobs.py saved every page that uses Template:Mob Drops Table. One activity per page ("mob:<title>"), or per level
# when a page has several tables with different levels. Loot Sharing tables are left out (a kill is counted for the
# killer). Garden pests are farming (post-2.0, killer560) and are left out. A row is TRACKED (has an expectation) when
# Hypixel announces it - drop rarity Rare, Legendary or RNGesus - or when its chance is under 5% (Magic Find's range, the
# drops that carry a "(+N% Magic Find)" line). Other rows under 100% are listed with their chance text only, since their
# drops reach sacks without a line and could never be counted.
def parse_chance(s):
    s = re.sub(r"\[\[.*", "", s or "").strip()
    out = []
    for part in s.split(","):
        part = part.strip()
        m = re.match(r"^1\s*/\s*([\d.]+)\s*([kKmM]?)$", part)
        if m:
            n = float(m.group(1)) * {"k": 1e3, "K": 1e3, "m": 1e6, "M": 1e6}.get(m.group(2), 1)
            out.append(1.0 / n)
            continue
        m = re.match(r"^([\d.]+)\s*%?$", part)
        if m:
            out.append(float(m.group(1)) / 100.0)
            continue
        return None
    return out or None


ANNOUNCED = {"r", "rare", "l", "legendary", "rng", "rngesus", "rl", "rngl", "ll"}
mobs = json.load(open(os.path.join(WIKI, "mobs.json"), encoding="utf-8"))
for title in sorted(mobs):
    src = mobs[title]
    if "Pest#Rare" in src or "[[Pest]]" in src[:3000] or "[[Pests]]" in src[:3000]:
        continue
    kind = "Sea Creatures" if "{{Infobox/Sea creature" in src else "Dungeon Mobs" if "{{Infobox/Dungeon enemy" in src else "Mobs"
    tables = []
    for t in re.findall(r"\{\{Mob Drops Table(.*?)\n\}\}", src, re.S):
        if re.search(r"\|\s*loot_share\s*=\s*true", t):
            continue
        lvl = re.search(r"^\|\s*level\s*=\s*([\d,]+)", t, re.M)
        rows = []
        for row in re.findall(r"\{\{Mob Drops Row(.*?)\n\s*\}\}", t, re.S):
            f = dict((k, v.strip()) for k, v in re.findall(r"\|\s*(\w+)\s*=\s*([^\n]*)", row))
            name = f.get("drop", "").strip()
            name = re.sub(r"\{\{[^}]*\}\}|<[^>]*>|\[\[|\]\]", "", name).strip()
            if not name:
                continue
            ch = parse_chance(f.get("chance"))
            rows.append((name, ch, f.get("rarity", "").strip().lower(), f.get("chance", "")))
        if rows:
            tables.append((lvl.group(1).replace(",", "") if lvl else "", rows))
    if not tables:
        continue
    levels = {lv for lv, _ in tables}
    multi = len(tables) > 1 and len(levels) == len(tables)
    base = re.sub(r"\s*\(.*\)$", "", title)
    for lv, rows in (tables if multi else tables[:1]):
        key = f"mob:{title}" + (f":L{lv}" if multi and lv else "")
        activity(key, title + (f" (Lv{lv})" if multi and lv else ""), kind, BASE + title.replace(" ", "_"))
        seen = {}
        for name, ch, rarity, text in rows:
            if ch is None:
                continue
            c = sum(ch)
            if c >= 1 and len(ch) == 1:
                continue  # guaranteed, never a luck item
            seen.setdefault(name, [0.0, rarity, text])
            seen[name][0] += c
        for name, (c, rarity, text) in seen.items():
            if c >= 1:
                continue
            tracked = rarity in ANNOUNCED or c < 0.05
            add(key=f"{key}|{name}", name=name, activity=key, chance=round(c, 12) if tracked else None,
                chanceText=None if tracked else f"{text} (no drop message; not counted)",
                mf=(c < 0.05) or None, loot=(not name.endswith(" Pet")) or None, cat="dye" if name.endswith(" Dye") else "mob",
                mob=base, level=int(lv) if lv else None)

# ---------------------------------------------------------------- Kuudra paid chests (Kuudra/Loot)
# One activity per tier: one opened Paid Chest. Its two slots roll separately, so an item's chance per chest is the
# sum of its First Slot and Second Slot chances (the "Base Chance" column; Wings of Destiny is not modeled).
kl = open(os.path.join(WIKI, "Kuudra_Loot.wiki"), encoding="utf-8").read()
for block in re.split(r"\n\|-\|", "\n" + kl)[1:]:
    tier = block.split("=", 1)[0].strip()
    key = f"kuudra:{tier}"
    activity(key, f"Kuudra {tier} Paid Chest", "Kuudra", BASE + "Kuudra/Loot")
    per = {}
    for tbl in re.findall(r"\|\+\s*Paid Chest \(Slot 1 and 2\)(.*?)\n\|\}", block, re.S):
        for cell in re.split(r"\n\|-", tbl):
            it = re.search(r"\{\{Item\|([^|}]+)([^}]*)\}\}", cell)
            ch = re.search(r"\{\{Chance\|([\d.]+)%", cell)
            if not it or not ch:
                continue
            name = it.group(1).strip()
            en = re.search(r"enchant=([^|}]+)", it.group(2))
            if en:
                name = f"Enchanted Book ({en.group(1).strip()})"
            per[name] = per.get(name, 0.0) + float(ch.group(1)) / 100
    dye = re.search(r"\{\{Item\|Tentacle Dye\}\} has a \{\{Chance\|([\d.]+)%", block)
    if dye:
        per["Tentacle Dye"] = float(dye.group(1)) / 100
    for name, c in per.items():
        if c >= 1 or name in ("Kuudra Teeth", "Crimson Essence"):
            continue  # also in the guaranteed part of a paid chest: a claim always holds some
        add(key=f"{key}|{name}", name=name, activity=key, chance=round(c, 10),
            cat="dye" if name.endswith(" Dye") else "kuudra")

# ---------------------------------------------------------------- Crystal Nucleus loot bundles (Crystal_Nucleus)
# One activity: one Crystal Loot Bundle, at the wiki's "Chance per Bundle (16.5 rolls)" (no Mole Pet / Bigger Box).
# Gemstones that come in bulk (Rough/Flawed/Fine, and the guaranteed Fine set) and stack items with several amounts are
# left out: the loot message shows a total per item, so their count per bundle could never be compared.
cn = open(os.path.join(WIKI, "Crystal_Nucleus.wiki"), encoding="utf-8").read().split("== RNG Meter ==")[0]
activity("nucleus", "Crystal Nucleus Loot Bundle", "Mining", BASE + "Crystal_Nucleus")
nuc = {}
for line in cn.split("\n"):
    m = re.match(r"\|\s*\{\{Item\|([^|}]+)\|amount=(\d+)\}\}.*?\|\|.*?\|\|.*?\|\|\s*\{\{Chance\|([\d.]+)%", line)
    if not m:
        continue
    nuc.setdefault(m.group(1).strip(), []).append(float(m.group(3)) / 100)
for name, cs in nuc.items():
    if len(cs) > 1 or name.startswith(("Flawed ", "Fine ", "Rough ")) or name == "Enchanted Book" or sum(cs) >= 0.05:
        continue
    add(key=f"nucleus|{name}", name=name, activity="nucleus", chance=round(sum(cs), 10), cat="mining")
jd = re.search(r"\{\{Chance\|([\d.]+)%\|1\|[\d,]+ rolls\}\} chance for each roll to be replaced with a \{\{Item\|Jade Dye\}\}", cn)
if jd:
    add(key="nucleus|Jade Dye", name="Jade Dye", activity="nucleus", chance=round(float(jd.group(1)) / 100 * 16.5, 12),
        cat="dye")

# ---------------------------------------------------------------- Experiments (Superpairs ultra-rare rewards)
# Experiments: "Base chance from Metaphysical Experiments is 1%" for an Ultra-Rare Book, 37 of them (Template:Ultra-Rare
# Chance), so 1/3,700 each. Supreme and Transcendent give them at a chance the wiki gives no number for: attempts are
# counted, the rewards are recorded, with no expectation.
ultra = json.load(open(os.path.join(WIKI, "ultrarare.json"), encoding="utf-8"))
books = []
for title, src in sorted(ultra.items()):
    if title == "Experiments":
        continue
    lines = src.split("\n")
    for i, line in enumerate(lines):
        if "Ultra-Rare Chance" not in line:
            continue
        tier = None
        for j in range(i, max(-1, i - 25), -1):
            m = re.search(r"\{\{Ench\|" + re.escape(title) + r" ([IVX]+)\}\}", lines[j])
            if m:
                tier = m.group(1)
                break
        if tier is None:
            ms = re.findall(r"\{\{Ench\|" + re.escape(title) + r" ([IVX]+)\}\}", src)
            tier = max(ms, key=lambda r: ROMAN_N.get(r, 0)) if ms else None
        books.append(f"{title} {tier}" if tier else title)
        break
n_ultra = 37
for stake in ("Supreme", "Transcendent", "Metaphysical"):
    key = f"experiment:{stake}"
    activity(key, f"Superpairs ({stake})", "Experiments", BASE + "Experiments")
    for b in books:
        name = f"Enchanted Book ({b})"
        if stake == "Metaphysical":
            add(key=f"{key}|{name}", name=name, activity=key, chance=round(0.01 / n_ultra, 12), cat="experiment")
        else:
            add(key=f"{key}|{name}", name=name, activity=key, chanceText="ultra-rare; the wiki gives no chance below Metaphysical",
                cat="experiment")

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
    "version": 2,
    "built": "2026-11-02",
    "source": "hypixelskyblock.minecraft.wiki raw wikitext, fetched 2026-11-02",
    "notes": [
        "chest:<floor>:<chest> chance = the wiki's 'Average Chance (No Bonuses)' per opened chest; chanceMax = 'Average Chance (S+ Max Bonuses)'. Pages: The_Catacombs_-_Floor_<N>/Loot and /Master_Mode_Loot.",
        "slayer:<boss>:<tier> chance = the boss page's Drop Chance column (every drop unlocked, no Magic Find, empty RNG Meter); Revenant Horror's page lists weights only, so its chances are weight/(token+main) for Main and weight/(token+main+extra) for Extra, per Slayer#Boss Loot; Separate entries are flat chances.",
        "mf=true: Slayer#Boss Loot says Magic Find multiplies the weight of Main/Extra items under a 5% chance by (1 + MF/100).",
        "Drop key = activity + \"|\" + name. untracked: a dye whose attempts the mod does not count yet; drops are still recorded.",
        "mob:<page> chance = the page's Mob Drops Table (Loot Sharing tables left out, several rolls of one item added); tracked when the drop rarity is Rare/Legendary/RNGesus or the chance is under 5%. loot=true: Looting multiplies it by 1 + 0.15 x level (Looting page; not for pets, not for slayers), then Magic Find applies if the looted chance is still under 5% (Magic Find page).",
        "kuudra:<tier> chance = Kuudra/Loot's Base Chance, First Slot plus Second Slot, per opened Paid Chest.",
        "nucleus chance = Crystal_Nucleus 'Chance per Bundle (16.5 rolls)'; bulk gemstones and multi-amount stacks left out.",
        "experiment:Metaphysical chance = 1% for an Ultra-Rare Book / 37 books (Experiments, Template:Ultra-Rare Chance).",
    ],
    "activities": activities,
    "drops": drops,
}
os.makedirs(os.path.dirname(os.path.abspath(sys.argv[2])), exist_ok=True)
json.dump(out, open(sys.argv[2], "w", encoding="utf-8", newline="\n"), ensure_ascii=False, separators=(",", ":"))
from collections import Counter
print(len(activities), "activities;", Counter(d["cat"] for d in drops), "drops")
