"""Generates src/main/resources/assets/killer560smod/skyblock/item_looks.json: the look every SkyBlock item had BEFORE
Hypixel's 2026 resource pack, for Pack Disabler and the Bazaar browser's icons (one table, one answer per item).

Inputs, all in one folder passed as the only argument:
  items.json          https://api.hypixel.net/v2/resources/skyblock/items     (material, durability, skin, item_model)
  bazaar.json         https://api.hypixel.net/v2/skyblock/bazaar                (every product id)
  vanilla_items.txt   one vanilla item id per line (assets/minecraft/items/*.json of the 26.1.2 client jar)
  neu.zip             NotEnoughUpdates-REPO master (MIT, Moulberry)            (current items, shard table)
  neu-26169fef95.zip  NEU-REPO at 26169fef95, the parent of "Resourcepack -> Bazaar Items" (2026-07-09)
  neu-0046933f3d.zip  NEU-REPO at 0046933f3d, the parent of "Resourcepack -> Auction House" (2026-07-09), the first
                      of NEU's resource-pack conversions; it still has 9 items already on pack models
  neu-60e030e5ff.zip  NEU-REPO at 60e030e5ff (2026-04-21), before any item used a pack model
  (codeload.github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/zip/<sha> for each snapshot)

For an item whose current model is one of Hypixel's ("hypixel_skyblock:..."), the look is taken from the NEWEST NEU
snapshot in which it was not yet a pack model; failing that, from its base material when that is a real item (Hyperion
is an iron sword underneath); failing that, from our own original texture (tools/textures) when one is drawn. The rest
have no pre-pack look: they are printed, and written to tools/items/no_prepack_look.txt.

Run:  python -X utf8 tools/items/gen_item_looks.py <data folder>
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import looks as L  # noqa: E402

DATA = sys.argv[1]
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, '..', '..')
OUT = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'killer560smod', 'skyblock', 'item_looks.json')
OUR_TEXTURES = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'killer560smod', 'textures', 'item', 'packdisabler')
MISSING_OUT = os.path.join(HERE, 'no_prepack_look.txt')

SNAPSHOTS = ['neu-26169fef95.zip', 'neu-0046933f3d.zip', 'neu-60e030e5ff.zip']  # newest first
ALIASES = {'BAZAAR_COOKIE': 'BOOSTER_COOKIE'}  # Hypixel's bazaar id for the Booster Cookie product

vanilla = set(open(L.data_path(DATA, 'vanilla_items.txt'), encoding='utf-8').read().split())
catalog = {i['id']: i for i in json.load(open(L.data_path(DATA, 'items.json'), encoding='utf-8'))['items']}
bazaar = json.load(open(L.data_path(DATA, 'bazaar.json'), encoding='utf-8'))['products']
neu, shard_table = L.load_neu(L.data_path(DATA, 'neu.zip'))
snapshots = [L.load_neu(L.data_path(DATA, s))[0] for s in SNAPSHOTS]


def texture_key(sbid):
    return sbid.lower().replace(':', '_').replace('-', '_')


our = set()
if os.path.isdir(OUR_TEXTURES):
    our = {f[:-4] for f in os.listdir(OUR_TEXTURES) if f.endswith('.png')}


def neu_key(sbid):
    if sbid.startswith('SHARD_') and sbid in shard_table:
        return shard_table[sbid]['internalName']
    return sbid.replace(':', '-')


def current_pack_model(sbid):
    n = neu.get(neu_key(sbid))
    m = L.neu_model(n)
    if L.is_pack_model(m):
        return m
    c = catalog.get(sbid)
    if c and L.is_pack_model(c.get('item_model')):
        return c['item_model']
    return None


def look_of(sbid):
    """(look dict, pack model or None, where the look came from)."""
    if sbid.startswith('ENCHANTMENT_'):
        return {'i': 'enchanted_book', 'e': 1}, None, 'enchanted book'
    key = ALIASES.get(sbid, sbid)
    n = neu.get(neu_key(key))
    c = catalog.get(key)
    model = current_pack_model(key)
    if not model:
        look = {}
        if n:
            look, _ = L.icon_from_neu(n, vanilla)
        if not L.has_look(look) and c:
            look, _ = L.icon_from_catalog(c, vanilla)
        return look, None, 'current'
    for name, snap in zip(SNAPSHOTS, snapshots):
        old = snap.get(neu_key(key))
        if old and not L.is_pack_model(L.neu_model(old)):
            look, omodel = L.icon_from_neu(old, vanilla)
            if L.has_look(look):
                return look, model, name[:-4]
    if L.base_of(n, c) not in L.BLANK_BASES and (n or c):
        look = L.base_look(n, c)
        if L.has_look(look):
            return look, model, 'base material'
    if texture_key(sbid) in our:
        return {'o': texture_key(sbid)}, model, 'our texture'
    return {}, model, 'none'


ids = set(bazaar)
for k, d in neu.items():
    if ';' in k:
        continue
    if L.is_pack_model(L.neu_model(d)):
        ids.add(k.replace('-', ':'))
for k, c in catalog.items():
    if L.is_pack_model(c.get('item_model')):
        ids.add(k)
skipped_semicolon = sum(1 for k, d in neu.items() if ';' in k and L.is_pack_model(L.neu_model(d)))

rows = {}
stats = {}
missing = []
pack_items = 0
for sbid in sorted(ids):
    look, model, where = look_of(sbid)
    # A real pre-pack look always beats our art (killer560: our textures are for items that never had one). Where
    # both exist the row still names the texture as 'o', but ItemLooks uses 'o' only when s/i/l are all absent.
    row = dict(look)
    if texture_key(sbid) in our and 'o' not in row:
        row['o'] = texture_key(sbid)
    if model:
        row['m'] = model
        pack_items += 1
        stats[where] = stats.get(where, 0) + 1
    if not L.has_look(row):
        missing.append(sbid)
    rows[sbid] = row

os.makedirs(os.path.dirname(OUT), exist_ok=True)
out = {
    'source': 'tools/items/gen_item_looks.py: Hypixel /v2/resources/skyblock/items + /v2/skyblock/bazaar, '
              'NotEnoughUpdates-REPO (MIT) master, 26169fe, 0046933 and 60e030e; o = our own textures',
    'items': rows,
}
with open(OUT, 'w', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
with open(MISSING_OUT, 'w', encoding='utf-8', newline='\n') as f:
    f.write('# SkyBlock ids with no pre-pack look and no texture of ours (gen_item_looks.py output)\n')
    for m in missing:
        f.write(m + '\n')
print('rows', len(rows), 'file', os.path.getsize(OUT), 'bytes')
print('items on a Hypixel pack model', pack_items, 'look from', stats)
print('NEU pack-model keys with ";" (pets/enchants, not runtime ids) skipped', skipped_semicolon)
print('no look at all', len(missing))
for m in missing:
    print('   ', m, '[bazaar]' if m in bazaar else '')
