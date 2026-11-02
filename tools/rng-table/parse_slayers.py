"""Parses the six slayer boss pages' raw wikitext (hypixelskyblock.minecraft.wiki) into drop rows.
Output: JSON list of {boss, tier, name, pool, weight, chance, req, quantity, odds, url}."""
import json, re, sys, os

WIKI = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wiki")
BASE = "https://hypixelskyblock.minecraft.wiki/w/"
ROMAN = {"I": 1, "II": 2, "III": 3, "IV": 4, "V": 5}


def clean(cell):
    c = cell.strip()
    # drop leading cell attributes:  style="..." | value   /  colspan="2" | value
    m = re.match(r'^((?:\s*(?:style|colspan|rowspan|class)\s*=\s*"[^"]*")+)\s*\|(.*)$', c)
    if m:
        c = m.group(2).strip()
    return c


def span(cell):
    m = re.search(r'colspan\s*=\s*"?(\d+)', cell.split('|')[0] if '|' in cell else '')
    return int(m.group(1)) if m else 1


def text(c):
    c = re.sub(r"\{\{Item\|([^|}]+)(?:\|[^}]*?text=([^|}]+))?[^}]*\}\}", lambda m: m.group(2) or m.group(1), c)
    c = re.sub(r"\{\{(?:Green|Aqua|Gold|Red)\|(.*)\}\}", r"\1", c)
    c = c.replace("'''", "")
    c = re.sub(r"\{\{bc\}\}", "", c)
    return c.strip()


def item_name(c):
    m = re.search(r"\{\{Item\|([^|}]+)([^}]*)\}\}", c)
    if not m:
        return text(c)
    name = m.group(1).strip()
    rest = m.group(2)
    t = re.search(r"text=([^|}]+)", rest)
    e = re.search(r"enchant=([^|}]+)", rest)
    if t:
        return t.group(1).strip()
    if e:
        return f"{name} ({e.group(1).strip()})"
    return name


def pct(c):
    m = re.search(r"([\d.,]+)\s*%", c)
    if m:
        return float(m.group(1).replace(",", "")) / 100.0
    m = re.search(r"1\s*/\s*([\d.,]+)\s*([kKmM]?)", c)
    if m:
        n = float(m.group(1).replace(",", ""))
        n *= {"k": 1e3, "K": 1e3, "m": 1e6, "M": 1e6}.get(m.group(2), 1)
        return 1.0 / n
    return None


def rows_of(table):
    """Split a wikitable body into rows of expanded cells."""
    out = []
    for raw in re.split(r"\n\|-[^\n]*", table):
        cells = []
        for line in raw.split("\n"):
            if not line.startswith("|") or line.startswith("|}") or line.startswith("|-"):
                continue
            for part in re.split(r"\s\|\|\s", line[1:]):
                head = part
                n = 1
                m = re.match(r'^\s*colspan\s*=\s*"?(\d+)"?\s*\|(.*)$', part)
                if m:
                    n = int(m.group(1))
                    part = m.group(2)
                m2 = re.match(r'^\s*style\s*=\s*"[^"]*"\s*\|(.*)$', part)
                if m2:
                    part = m2.group(1)
                cells.extend([part.strip()] * n)
        if cells:
            out.append(cells)
    return out


def revenant():
    src = open(os.path.join(WIKI, "Revenant_Horror.wiki"), encoding="utf-8").read()
    out = []
    tier = 0
    for block in re.split(r"\n\|-\|Tier ", src):
        m = re.match(r"([IVX]+)", block)
        if not m:
            continue
        tier = ROMAN[m.group(1)]
        for row in re.findall(r"\{\{Slayer Drops Row(.*?)\n\}\}", block, re.S):
            f = dict(re.findall(r"\|[ \t]*(\w+)[ \t]*=[ \t]*([^\n]*)", row))
            out.append(dict(boss="revenant", tier=tier, name=f["drop"].strip(), pool=f["pool"].strip(),
                            weight=float(f["weight"]), chance=None, req=int(f["requirement"] or 0),
                            quantity=f["quantity"].strip(), odds=f["odds"].strip(), url=BASE + "Revenant_Horror"))
    return out


def tabbed(page, boss):
    """Tarantula / Voidgloom: <tabber> tabs per tier, columns Slot, Item, LVL Req, Odds, Pool, Weight, Chance."""
    src = open(os.path.join(WIKI, page + ".wiki"), encoding="utf-8").read()
    out = []
    for m in re.finditer(r"\|-\|Tier (\d)=\s*\n\{\|(.*?)\n\|\}", src, re.S):
        tier = int(m.group(1))
        for cells in rows_of(m.group(2)):
            if len(cells) < 7 or "{{Item" not in cells[1]:
                continue
            name = item_name(cells[1])
            req = re.sub(r"\D", "", text(cells[2])) or "0"
            pool = re.sub(r"[^A-Za-z]", "", text(cells[4]))
            w = re.sub(r"[^\d.]", "", text(cells[5]))
            chance = pct(cells[6].split("|")[1] if "{{Chance|" in cells[6] else cells[6])
            amt = re.search(r"amount=([^|}]+)", cells[1])
            out.append(dict(boss=boss, tier=tier, name=name, pool=pool, weight=float(w) if w else None,
                            chance=chance, req=int(req), quantity=amt.group(1) if amt else "1",
                            odds=re.sub(r".*\|", "", cells[3]).strip("} "), url=BASE + page))
    return out


def wide(page, boss, tiers, has_pool, has_weight):
    """Sven / Inferno / Riftstalker: one row per drop, amounts per tier, then chances per tier."""
    src = open(os.path.join(WIKI, page + ".wiki"), encoding="utf-8").read()
    start = src.index("== Drops ==")
    tbl = src[src.index("{|", start):]
    tbl = tbl[:tbl.index("\n|}")]
    out = []
    for cells in rows_of(tbl):
        if len(cells) < 3 or "{{Item" not in cells[1]:
            continue
        name = item_name(cells[1])
        req = re.sub(r"\D", "", text(cells[2])) or "0"
        i = 3
        amounts = cells[i:i + tiers]; i += tiers
        odds = cells[i]; i += 1
        pool = text(cells[i]) if has_pool else None
        pool = re.sub(r"[^A-Za-z]", "", pool) if pool else None
        i += 1 if has_pool else 0
        weight = None
        if has_weight:
            w = re.sub(r"[^\d.]", "", text(cells[i])); i += 1
            weight = float(w) if w else None
        chances = cells[i:i + tiers]
        for t in range(tiers):
            if t >= len(chances):
                break
            c = pct(text(chances[t]))
            a = text(amounts[t]) if t < len(amounts) else ""
            if c is None:
                continue
            out.append(dict(boss=boss, tier=t + 1, name=name, pool=pool, weight=weight, chance=c, req=int(req),
                            quantity=a or "1", odds=re.sub(r".*\|", "", odds).strip("} "), url=BASE + page))
    return out


rows = (revenant() + tabbed("Tarantula_Broodfather", "tarantula") + tabbed("Voidgloom_Seraph", "voidgloom")
        + wide("Sven_Packmaster", "sven", 4, False, False) + wide("Inferno_Demonlord", "inferno", 4, True, False)
        + wide("Riftstalker_Bloodfiend", "vampire", 5, True, True))
json.dump(rows, open(sys.argv[1], "w", encoding="utf-8"), indent=1, ensure_ascii=False)
from collections import Counter
print(Counter((r["boss"], r["tier"]) for r in rows))
