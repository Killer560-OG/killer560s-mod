"""Shared "what did this SkyBlock item look like before Hypixel's resource pack" logic.

Used by tools/items/gen_item_looks.py (writes the table the mod reads) and tools/bazaar/gen_bazaar_data.py (the Bazaar
browser's names and groups; its icons come from the same table, so every item has one answer).

A look is a small dict:
  s  texture hash on textures.minecraft.net (a player head)
  i  a modern vanilla item id ("wheat_seeds")
  l  a 1.8 "minecraft:name:damage", resolved in game through vanilla's flattening fix
  e  1 when the item has an enchantment glint
All of them name VANILLA things that the client resolves at runtime (item ids and the normal player-head path), so the
player's own resource packs still decide how they are drawn. Nothing here ships a texture.
"""
import base64
import json
import os
import re
import zipfile

COLOR = re.compile('§.')
ITEM_MODEL = re.compile(r'ItemModel:\\?"([^"\\]+)\\?"')
SKULL_VALUE = re.compile(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"')
PACK_NS = 'hypixel_skyblock:'

LEGACY = {'INK_SACK': 'dye', 'RAW_FISH': 'fish', 'COOKED_FISH': 'cooked_fish', 'LOG_2': 'log2', 'CARROT_ITEM': 'carrot',
          'POTATO_ITEM': 'potato', 'NETHER_STALK': 'nether_wart', 'MYCEL': 'mycelium', 'ENDER_STONE': 'end_stone',
          'SULPHUR': 'gunpowder', 'PORK': 'porkchop', 'WATER_LILY': 'waterlily', 'SEEDS': 'wheat_seeds',
          'SUGAR_CANE': 'reeds', 'RAW_CHICKEN': 'chicken', 'RAW_BEEF': 'beef', 'SNOW_BALL': 'snowball',
          'RED_ROSE': 'red_flower', 'YELLOW_FLOWER': 'yellow_flower', 'EXP_BOTTLE': 'experience_bottle',
          'SKULL_ITEM': 'skull', 'NETHER_BRICK_ITEM': 'netherbrick', 'HUGE_MUSHROOM_1': 'brown_mushroom_block',
          'HUGE_MUSHROOM_2': 'red_mushroom_block', 'WATCH': 'clock', 'FIREWORK': 'fireworks',
          'EYE_OF_ENDER': 'ender_eye', 'SNOW_BLOCK': 'snow', 'MELON': 'melon', 'MELON_BLOCK': 'melon_block',
          'SPECKLED_MELON': 'speckled_melon', 'GRILLED_PORK': 'cooked_porkchop', 'LEASH': 'lead', 'WEB': 'web',
          'TRAP_DOOR': 'trapdoor', 'MONSTER_EGG': 'spawn_egg', 'REDSTONE_LAMP_OFF': 'redstone_lamp',
          'IRON_PLATE': 'heavy_weighted_pressure_plate', 'CARROT_STICK': 'carrot_on_a_stick', 'COMMAND': 'command_block',
          'WOOL': 'wool'}

# Bases that say nothing about the item: a pack-model item on one of these has no pre-pack look of its own.
BLANK_BASES = {'minecraft:paper', 'minecraft:skull', 'minecraft:player_head', ''}


def strip(s):
    return re.sub(r'%%[a-z_]+%%', '', COLOR.sub('', s or '')).strip()


def load_neu(path):
    """(items by NEU key, attribute-shard table by bazaar name) from a NotEnoughUpdates-REPO archive."""
    z = zipfile.ZipFile(path)
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


def neu_model(item):
    m = ITEM_MODEL.search((item or {}).get('nbttag', ''))
    return m.group(1) if m else None


def is_pack_model(model):
    return bool(model) and model.startswith(PACK_NS)


def skin_hash(value):
    try:
        url = json.loads(base64.b64decode(value + '==='))['textures']['SKIN']['url']
        return url.rsplit('/', 1)[1]
    except Exception:
        return None


def icon_from_neu(item, vanilla):
    """(look, pack model or None) from a NEU item json."""
    tag = item.get('nbttag', '')
    out = {}
    model = None
    m = ITEM_MODEL.search(tag)
    v = SKULL_VALUE.search(tag)
    if m and not m.group(1).startswith('minecraft:'):
        model = m.group(1)
    if v and (not m or m.group(1) in ('minecraft:player_head',) or model):
        h = skin_hash(v.group(1))
        if h:
            out['s'] = h
    elif m and m.group(1).startswith('minecraft:') and m.group(1)[10:] in vanilla:
        out['i'] = m.group(1)[10:]
    if not out and not model:
        itemid = item.get('itemid', '')
        if itemid:
            out['l'] = itemid + ':' + str(item.get('damage', 0))
    if 'ench:' in tag:
        out['e'] = 1
    return out, model


def icon_from_catalog(c, vanilla):
    """(look, pack model or None) from a /v2/resources/skyblock/items entry."""
    out = {}
    if c.get('skin'):
        h = skin_hash(c['skin'].get('value', ''))
        if h:
            out['s'] = h
    else:
        mat = c.get('material', '')
        out['l'] = 'minecraft:' + LEGACY.get(mat, mat.lower()) + ':' + str(c.get('durability', 0))
    if c.get('glowing'):
        out['e'] = 1
    model = c.get('item_model')
    if model and model.startswith('minecraft:'):
        if model[10:] in vanilla:
            out = {'i': model[10:], **({'e': 1} if c.get('glowing') else {})}
        model = None
    return out, model


def base_of(neu_item, cat_item):
    """The item's base material ("minecraft:iron_sword"), or '' when unknown."""
    if neu_item and neu_item.get('itemid'):
        return neu_item['itemid']
    if cat_item and cat_item.get('material'):
        mat = cat_item['material']
        return 'minecraft:' + LEGACY.get(mat, mat.lower())
    return ''


def base_look(neu_item, cat_item):
    if neu_item and neu_item.get('itemid'):
        return {'l': neu_item['itemid'] + ':' + str(neu_item.get('damage', 0))}
    mat = cat_item['material']
    return {'l': 'minecraft:' + LEGACY.get(mat, mat.lower()) + ':' + str(cat_item.get('durability', 0))}


def has_look(look):
    return bool(look.get('s') or look.get('i') or look.get('o')
                or (look.get('l') and not look['l'].startswith('minecraft:paper')
                    and not look['l'].startswith('minecraft:skull')))


def data_path(folder, name):
    return os.path.join(folder, name)
