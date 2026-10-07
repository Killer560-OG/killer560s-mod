"""Generates src/main/resources/assets/killer560smod/bazaar/products.json: every Bazaar product's name, Hypixel
Bazaar category/group and rarity. Its ICON comes from the shared item table (tools/items/gen_item_looks.py, also read
by Pack Disabler), which must be generated first; BazaarCatalog joins the two at load.

Inputs (download them into one folder, pass it as the only argument):
  bazaar.json     https://api.hypixel.net/v2/skyblock/bazaar                 (the product ids)
  items.json      https://api.hypixel.net/v2/resources/skyblock/items        (names, tiers, skins, materials)
  neu.zip         NotEnoughUpdates-REPO master archive (MIT, Moulberry)      (enchantments, shards, essences, factions)
  vanilla_items.txt  one vanilla item id per line (assets/minecraft/items/*.json of the 26.1.2 client jar)
And tools/bazaar/bazaar_tree.txt (Hypixel's Bazaar menu taxonomy, from the wiki).

Run:  python -X utf8 tools/bazaar/gen_bazaar_data.py <data folder>
It prints the products with no icon source in the shared table; those are what still draw as paper.
"""
import json
import os
import re
import sys
import zipfile

DATA = sys.argv[1]
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'killer560smod', 'bazaar', 'products.json')

bazaar = json.load(open(os.path.join(DATA, 'bazaar.json'), encoding='utf-8'))['products']
catalog = {i['id']: i for i in json.load(open(os.path.join(DATA, 'items.json'), encoding='utf-8'))['items']}
vanilla = set(open(os.path.join(DATA, 'vanilla_items.txt'), encoding='utf-8').read().split())


def load_neu(name):
    z = zipfile.ZipFile(os.path.join(DATA, name))
    root = z.namelist()[0].split('/')[0]
    items = {}
    for n in z.namelist():
        if n.startswith(root + '/items/') and n.endswith('.json'):
            try:
                items[n.rsplit('/', 1)[1][:-5]] = json.loads(z.read(n).decode('utf-8'))
            except Exception:
                pass
    shards = {}
    try:
        for a in json.loads(z.read(root + '/constants/attribute_shards.json').decode('utf-8'))['attributes']:
            shards[a['bazaarName']] = a
    except KeyError:
        pass
    return items, shards


neu, shard_table = load_neu('neu.zip')
# Icons are NOT decided here: every product's look comes from the shared SkyBlock item table that Pack Disabler also
# reads (tools/items/gen_item_looks.py -> assets/killer560smod/skyblock/item_looks.json), so one item has one answer.
# Run that generator first.
LOOKS = json.load(open(os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'killer560smod', 'skyblock',
                                    'item_looks.json'), encoding='utf-8'))['items']

COLOR = re.compile('§.')
TIERS = ['COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY', 'MYTHIC', 'DIVINE', 'SPECIAL', 'VERY_SPECIAL']
TIER_BY_CODE = {'f': 'COMMON', 'a': 'UNCOMMON', '9': 'RARE', '5': 'EPIC', '6': 'LEGENDARY', 'd': 'MYTHIC',
                'b': 'DIVINE', 'c': 'SPECIAL'}


def strip(s):
    return re.sub(r'%%[a-z_]+%%', '', COLOR.sub('', s or '')).strip()


ALIASES = {'BAZAAR_COOKIE': 'BOOSTER_COOKIE'}  # Hypixel's bazaar id for the Booster Cookie product
ROMAN_NUMERALS = ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X', 'XI', 'XII', 'XIII', 'XIV', 'XV',
                  'XVI', 'XVII', 'XVIII', 'XIX', 'XX']


def enchant_entry(pid):
    """ENCHANTMENT_<NAME>_<LEVEL>: always an enchanted book. NEU has most levels; where it lacks this one (the
    Bazaar sells levels NEU never recorded), borrow the enchantment's name and rarity from any level it has."""
    body = pid[len('ENCHANTMENT_'):]
    ench, _, level = body.rpartition('_')
    lvl = int(level) if level.isdigit() else 0
    base = None
    tier = None
    for k in [ench + ';' + level] + [ench + ';' + str(i) for i in range(1, 11)]:
        it = neu.get(k)
        if not it:
            continue
        lore = it.get('lore') or []
        if len(lore) > 2:
            line = strip(lore[2])
            base = re.sub(r'\s+[IVXL]+$', '', line)
            if k == ench + ';' + level:
                tier = None
            code = (it.get('displayname') or '  ')[1:2]
            tier = TIER_BY_CODE.get(code)
            break
    if not base:
        words = ench.split('_')
        if words[0] == 'ULTIMATE':
            words = words[1:]
        base = ' '.join(w.capitalize() for w in words)
    numeral = ROMAN_NUMERALS[lvl] if 0 < lvl < len(ROMAN_NUMERALS) else ''
    name = (base + ' ' + numeral).strip()
    e = {'n': name, 'i': 'enchanted_book', 'e': 1}
    if tier:
        e['t'] = tier
    if ench.startswith('ULTIMATE_'):
        e['u'] = 1
    return e


def neu_key(pid):
    if pid.startswith('ENCHANTMENT_'):
        body = pid[len('ENCHANTMENT_'):]
        name, _, level = body.rpartition('_')
        return name + ';' + level
    if pid.startswith('SHARD_') and pid in shard_table:
        return shard_table[pid]['internalName']
    return pid.replace(':', '-')


# ---- taxonomy ----
groups = []          # [(category, group)]
by_name = {}         # display name (lower) -> group index
prefix_rules = {}    # '*ENCHANTMENT' -> group index
cat = None
for line in open(os.path.join(HERE, 'bazaar_tree.txt'), encoding='utf-8'):
    line = line.rstrip('\n')
    if not line or line.startswith('#'):
        continue
    if line.startswith('C:'):
        cat = line[2:]
    elif line.startswith('G:'):
        gname, _, items = line[2:].partition('|')
        groups.append((cat, gname))
        gi = len(groups) - 1
        for it in items.split(';'):
            it = it.strip()
            if it.startswith('*'):
                prefix_rules[it[1:]] = gi
            elif it:
                by_name.setdefault(it.lower(), gi)
groups.append(('Oddities', 'Other'))  # always last

ROMAN = {'I': 1, 'V': 5, 'X': 10}


def add_group(category, gname):
    for i, g in enumerate(groups):
        if g == (category, gname):
            return i
    groups.insert(len(groups) - 1, (category, gname))
    return len(groups) - 2


def supplemental_group(pid, c):
    """NOT from the wiki: the wiki's list predates these products (Garden mutations, Galatea, fossils...). Each
    rule places a product by what Hypixel's own item catalog says about it (category, or the folder of its
    resource-pack model); anything none of them covers stays in Oddities > Other."""
    model = (c or {}).get('item_model') or ''
    category = (c or {}).get('category') or ''
    if category == 'MUTATION' or '/garden/' in model or pid.endswith('_GARDEN_CHIP'):
        return ('Farming', 'Garden')
    if any(x in model for x in ('foraging_2', 'foraging_3', 'collections/lotus', 'lotus_atoll')):
        return ('Woods & Fishes', 'Galatea')
    if 'glacite/fossils' in model or pid.endswith('_FOSSIL'):
        return ('Mining', 'Glacite Tunnels')
    if '/boosters/' in model or '_BOOSTER' in pid:
        return ('Oddities', 'Modifiers')
    if '/reforge_stones/' in model:
        return ('Oddities', 'Reforge Stones')
    if '/power_stones/' in model:
        return ('Oddities', 'Power Stones')
    if pid.startswith('GENERATOR_UPGRADE_STONE_'):
        return ('Oddities', 'Minion Upgrades')
    if category == 'PET_ITEM' or pid.startswith('GRIFFIN_UPGRADE_STONE_'):
        return ('Oddities', 'Pet Upgrades')
    if 'community_center/mayor' in model:
        return ('Oddities', 'Events')
    if '/fishing/' in model:
        return ('Woods & Fishes', 'Mob Drops')
    if '/safari/' in model:
        return ('Combat', 'Hunting')
    if '/slayer/' in model:
        return ('Combat', 'Slayers')
    if 'crimson_isle' in model:
        return ('Combat', 'Crimson Isle')
    return None


def group_of(pid, name):
    for pre, gi in prefix_rules.items():
        if pid.startswith(pre + '_') or (pre == 'GEM' and pid.endswith('_GEM')) or (pre == 'MYTHOS' and 'MYTHOS' in pid):
            return gi
    n = name.lower()
    if n in by_name:
        return by_name[n]
    for alt in (n.replace('-', ' '), n.replace("'", ''), n.replace(' block', ''), 'enchanted ' + n):
        if alt in by_name:
            return by_name[alt]
    sup = supplemental_group(pid, catalog.get(pid))
    if sup:
        return add_group(*sup)
    return len(groups) - 1


products = {}
before_paper = []
after_paper = []
unmatched_group = []
for pid in sorted(bazaar):
    if pid.startswith('ENCHANTMENT_'):
        entry = enchant_entry(pid)
        entry.pop('i', None)
        entry.pop('e', None)
        entry['g'] = group_of(pid, entry['n'])
        products[pid] = entry
        before_paper.append(pid)
        continue
    c = catalog.get(ALIASES.get(pid, pid))
    nk = neu_key(ALIASES.get(pid, pid))
    ni = neu.get(nk)
    # --- the OLD code's result (BazaarApi.parseProduct + SkyblockItemStackFactory, pack not loaded) ---
    if c is None:
        before_paper.append(pid)
    elif not c.get('skin'):
        mat = c['material']
        remap = {'GOLD_SWORD', 'INK_SACK', 'RAW_FISH', 'COOKED_FISH', 'SULPHUR', 'WATCH', 'GRILLED_PORK', 'PORK',
                 'HUGE_MUSHROOM_1', 'HUGE_MUSHROOM_2', 'SNOW_BALL', 'SPECKLED_MELON', 'MELON', 'EYE_OF_ENDER',
                 'FIREWORK', 'NETHER_STALK', 'SUGAR_CANE', 'CARROT_STICK', 'QUARTZ', 'BOAT', 'MAP', 'EMPTY_MAP'}
        if mat == 'PAPER' or (mat not in remap and mat not in ('SKULL_ITEM',) and mat.lower() not in vanilla):
            before_paper.append(pid)

    name = None
    tier = None
    if c:
        name = strip(c.get('name'))
        tier = c.get('tier')
    if ni and not name:
        dn = strip(ni.get('displayname'))
        if pid.startswith('ENCHANTMENT_'):
            lore = ni.get('lore', [])
            dn = strip(lore[2]) if len(lore) > 2 else dn
        name = dn
    if ni and not tier:
        last = strip((ni.get('lore') or [''])[-1])
        for t in TIERS:
            if last.startswith(t):
                tier = t
        if not tier:
            code = (ni.get('displayname') or '  ')[1:2]
            tier = TIER_BY_CODE.get(code)
    if not name:
        name = ' '.join(w.capitalize() for w in pid.replace(':', '_').split('_'))

    look = LOOKS.get(pid, {})
    if pid in ALIASES:
        # Only the ICON is borrowed: BAZAAR_COOKIE and BOOSTER_COOKIE are separate products, and sharing a name would
        # make a Manage Orders line ambiguous.
        name = ' '.join(w.capitalize() for w in pid.split('_'))
    entry = {'n': name, 'g': group_of(pid, name)}
    if entry['g'] == len(groups) - 1:
        unmatched_group.append(pid + ' (' + name + ')')
    if tier:
        entry['t'] = tier
    if not (look.get('s') or look.get('i') or look.get('o')
            or (look.get('l') and not look['l'].startswith('minecraft:paper'))):
        after_paper.append(pid + ' (' + name + ')' + (' [pack model ' + look['m'] + ']' if look.get('m') else ''))
    products[pid] = entry

os.makedirs(os.path.dirname(OUT), exist_ok=True)
out = {
    'source': 'tools/bazaar/gen_bazaar_data.py: Hypixel /v2/skyblock/bazaar + /v2/resources/skyblock/items, '
              'NotEnoughUpdates-REPO (MIT) master and 26169fe, hypixelskyblock.minecraft.wiki Bazaar/Item List',
    'groups': [{'c': g[0], 'g': g[1]} for g in groups],
    'products': products,
}
with open(OUT, 'w', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
print('products', len(products), 'file', os.path.getsize(OUT), 'bytes')
print('BEFORE (old code, pack not loaded): paper', len(before_paper))
print('AFTER: no icon source', len(after_paper))
for p in after_paper:
    print('   ', p)
print('group "Other":', len(unmatched_group))
for p in unmatched_group:
    print('   ', p)
