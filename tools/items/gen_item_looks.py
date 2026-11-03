"""Generates src/main/resources/assets/killer560smod/skyblock/item_looks.json: the look every SkyBlock item had BEFORE
Hypixel's 2026 resource pack, for Pack Disabler and the Bazaar browser's icons (one table, one answer per item).

Inputs, all in one folder passed as the only argument:
  items.json          https://api.hypixel.net/v2/resources/skyblock/items     (material, durability, skin, item_model)
  bazaar.json         https://api.hypixel.net/v2/skyblock/bazaar                (every product id)
  vanilla_items.txt   one vanilla item id per line (assets/minecraft/items/*.json of the 26.1.2 client jar)
  neu.zip             NotEnoughUpdates-REPO master (MIT, Moulberry)            (current items, shard table)
  neu-<sha>.zip       NEU-REPO at the PARENT of each commit that moved items onto Hypixel's pack models (SNAPSHOTS
                      below, newest first), so every converted item's last pre-pack NBT is in one of them
  hypixel-pack.zip    optional: Hypixel's SkyBlock resource pack as the client cached it (downloads/<uuid>/<sha1>);
                      only read to report pack item models that no table row names (coverage check)
  (tools/items/fetch_item_sources.py downloads all of these except the pack; data only, no code is copied)

Refreshing the table (killer560, 2026-11-03): run fetch_item_sources.py <folder>, look at NEU's commit list for new
"texture"/"resource pack"/"model" commits since the newest snapshot and add each one's parent to SNAPSHOTS (and to
the fetch script), copy the pack in, run this, and read the coverage report it prints.

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

# NEU-REPO snapshots, newest first: the parent of each commit that moved items onto Hypixel's pack models.
SNAPSHOTS = [
    'neu-da962218a5.zip',  # parent of "0.27.2 More texture changes" (2026-10-07: reforge stones, power stones, equipment)
    'neu-f2e2f23aec.zip',  # parent of "Rods missing new Hypixel models" (2026-09-03)
    'neu-14c3bec39b.zip',  # parent of "More Resourcepack Item updates" (2026-07-09)
    'neu-c36f36d22c.zip',  # parent of "Resourcepack -> Recipe" (2026-07-09)
    'neu-26169fef95.zip',  # parent of "Resourcepack -> Bazaar Items" (2026-07-09)
    'neu-0046933f3d.zip',  # parent of "Resourcepack -> Auction House" (2026-07-09); 9 items already on pack models
    'neu-60e030e5ff.zip',  # 2026-04-21, before any item used a pack model
]
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

# Items Hypixel's pack draws although neither source lists them on a pack model yet (2026-11-03: the Bingo tools,
# Hoe of No Tilling, Spelling Stick, the InfiniVacuums): a pack model whose file name is a known id with the
# underscores taken out. Their current NEU/catalog look is still the pre-pack one, so they get a row like any other.
PACK = L.data_path(DATA, 'hypixel-pack.zip')
pack_models = set()
if os.path.exists(PACK):
    import zipfile
    _pre = 'assets/hypixel_skyblock/items/'
    pack_models = {'hypixel_skyblock:' + n[len(_pre):-5] for n in zipfile.ZipFile(PACK).namelist()
                   if n.startswith(_pre) and n.endswith('.json')}


def _squash(s):
    return s.lower().replace('_', '').replace('-', '').replace(':', '')


known_ids = {k.replace('-', ':') for k in neu if ';' not in k} | {k for k in catalog if ';' not in k}
by_squash = {}
for k in known_ids:
    by_squash.setdefault(_squash(k), set()).add(k)
named_by_pack = {}
for m in sorted(pack_models):
    for k in sorted(by_squash.get(_squash(m.rsplit('/', 1)[1]), ())):
        if k not in ids:
            named_by_pack[k] = m
ids |= set(named_by_pack)

rows = {}
stats = {}
missing = []
pack_items = 0
for sbid in sorted(ids):
    look, model, where = look_of(sbid)
    if model is None and sbid in named_by_pack:
        model, where = named_by_pack[sbid], 'current (named by the pack)'
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

# A new tier of a family whose other tiers had a pre-pack look takes that family's look, so the set stays together -
# killer560 (2026-10-07): "Some things like the gigantic fishing net have previous tiers, make sure it fits in with
# them." Applied only when the item has no pre-pack look of its own; its 'o' texture then goes unused.
FAMILY = {
    'GIGANTIC_FISHING_NET': 'TURBO_FISHING_NET',          # Basic/Medium/Turbo nets were all a cobweb
    'ARCHER_DUNGEON_ABILITY_1': 'ARCHER_DUNGEON_ABILITY_2',  # tiers 2 and 3 are a bow
    'FIGHTING_BOOSTER_UNCOMMON': 'FIGHTING_BOOSTER',      # the common booster's head
    'FORAGING_FORTUNE_BOOSTER_UNCOMMON': 'FORAGING_FORTUNE_BOOSTER',
    'FORAGING_WISDOM_BOOSTER_UNCOMMON': 'FORAGING_WISDOM_BOOSTER',
    'SWEEP_BOOSTER_UNCOMMON': 'SWEEP_BOOSTER',
}
for sbid, sibling in FAMILY.items():
    row, sib = rows.get(sbid), rows.get(sibling)
    if row is None or sib is None or any(k in row for k in ('s', 'i', 'l')):
        continue
    for k in ('s', 'i', 'l', 'e'):
        if k in sib:
            row[k] = sib[k]
    if sbid in missing and L.has_look(row):
        missing.remove(sbid)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
out = {
    'source': 'tools/items/gen_item_looks.py: Hypixel /v2/resources/skyblock/items + /v2/skyblock/bazaar, '
              'NotEnoughUpdates-REPO (MIT, Moulberry; data only) master and ' + ', '.join(
                  s[4:14] for s in SNAPSHOTS) + '; o = our own textures',
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

# Coverage: every item model in Hypixel's pack that no row names. Those are state variants the dynamic models in
# PackDisabler.java pick (carnival shovel tiers, attuned daggers), models no live item uses, or items neither Hypixel's
# item resource nor NEU know yet - the last kind is what the next refresh should look for.
if pack_models:
    named ={r['m'] for r in rows.values() if 'm' in r}
    unnamed = sorted(pack_models - named)
    gone = sorted(named - pack_models)
    print('pack item models', len(pack_models), 'named by a row', len(pack_models & named),
          'named by no row', len(unnamed), '; rows whose model the pack lacks', len(gone))
    for m in unnamed:
        print('   pack-only', m)
    for m in gone:
        print('   row-only ', m)
