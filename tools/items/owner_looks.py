"""The Pack Disabler looks killer560 approved on 2026-11-17 (texredo2/texredo3): each item is drawn as a vanilla player
head wearing a skin bundled in the jar, or as a plain vanilla item, instead of our old 16x16 drawing.

Skins: src/main/resources/assets/killer560smod/textures/entity/packdisabler/<key>.png (64x64), drawn through the head's
skin patch (ItemLooks.bundledProfile); never downloaded.

Row keys written into item_looks.json: 'k' bundled skin key (head form), 'i' vanilla item, 'g' glint forced on at draw.
'models' (top level): a Hypixel pack model -> skin key, for items whose SkyBlock id is not confirmed but whose model is
named by Hypixel's own pack, and for Safari Belt rarities (one id, one model per rarity).

gen_item_looks.py calls apply(); apply_owner_looks.py applies it to the current table without the full refresh.
"""

HEAD = {
    'ACCRETION_TALISMAN', 'ACCRETION_RING', 'ACCRETION_ARTIFACT',
    'HONEYCOMB_TALISMAN', 'HONEYCOMB_RING', 'HONEYCOMB_ARTIFACT', 'HONEYCOMB_NECKLACE',
    'HOTSPOT_TALISMAN', 'HOTSPOT_RING', 'HOTSPOT_ARTIFACT',
    'LUMBERJACK_TALISMAN', 'LUMBERJACK_RING', 'LUMBERJACK_ARTIFACT',
    'TORRHUS_TALISMAN', 'TORRHUS_RING', 'TORRHUS_ARTIFACT', 'TORRHUS_BELT',
    'SPARKLING_AMULET', 'SAFARI_BELT', 'VEILSHROOM_BRACELET',
    'POWDER_OF_ONE', 'POWDER_OF_THE_FEW', 'POWDER_OF_THE_MANY', 'POWDER_OF_THE_PEOPLE',
    'HUNTING_WISDOM_BOOSTER_COMMON', 'HUNTING_WISDOM_BOOSTER_UNCOMMON', 'LUCK_BOOSTER', 'LUCK_BOOSTER_UNCOMMON',
    'SALTY_SPRAYONATOR', 'JUICY_SPRAYONATOR', 'SALTY_NOZZLE', 'JUICY_NOZZLE',
    'FUN_SIZED_POT_OF_HONEYCOMB', 'FAMILY_SIZED_POT_OF_HONEYCOMB', 'JUMBO_POT_OF_HONEYCOMB',
    'BEHEMOTH_POT_OF_HONEYCOMB',
    'SHINING_COIN', 'YOGI_BERRY', 'VIAL_OF_SPRING_WATER', 'RUBY_VEILSHROOM', 'VEILSHROOM_BUNCH',
    'ENCHANTED_RUBY_VEILSHROOM', 'GRUNGLE', 'CRITTER_CAPSULE', 'MASTERFUL_CRITTER_CAPSULE',
    'EXTREMELY_MILD_ADHESIVE', 'SEA_BRINE',
}
VANILLA = {
    'NOTHING_IN_A_BOTTLE': 'glass_bottle',
    'DISTANT_ECHO': 'echo_shard',
    'BEASTSLAYERS_CODEX': 'book',
    'DEAD_MANS_JOURNAL': 'writable_book',
    'MAGIC_FOR_PETS': 'enchanted_book',
    'BEE_SALIVA': 'honey_bottle',
    'HELIXIS': 'weeping_vines',
    'RAINBOW_FEATHER': 'feather',
}
GLINT = {'ENCHANTED_RUBY_VEILSHROOM', 'RAINBOW_FEATHER'}
_PACK = 'hypixel_skyblock:item/'
MODELS = {
    _PACK + 'island_relevant/safari/safari_belt_common': 'safari_belt',
    _PACK + 'island_relevant/safari/safari_belt_uncommon': 'safari_belt_uncommon',
    _PACK + 'island_relevant/safari/safari_belt_rare': 'safari_belt_rare',
    _PACK + 'island_relevant/safari/safari_belt_epic': 'safari_belt_epic',
    _PACK + 'island_relevant/safari/safari_belt_legendary': 'safari_belt_legendary',
    _PACK + 'island_relevant/safari/safari_belt_mythic': 'safari_belt_mythic',
    _PACK + 'island_relevant/garden/pests/basic_nozzle': 'basic_nozzle',
    _PACK + 'island_relevant/foraging_3/anthill': 'anthill',
    _PACK + 'fishing/ship/cracked_ship_helm': 'cracked_ship_helm',
    _PACK + 'fishing/ship/rusty_ship_hull': 'rusty_ship_hull',
    _PACK + 'island_relevant/dungeons/catacombs_gobbler': 'catacombs_gobbler',
}
# Skins in the jar that nothing maps yet: neither the id nor a pack model is confirmed. Map them once a live item
# shows its id (ExtraAttributes id) and model.
PENDING = ['personal_compactor_farming', 'personal_compactor_mining', 'personal_compactor_combat',
           'personal_compactor_foraging', 'personal_compactor_fishing', 'personal_compactor_husbandry']
# Our old drawings these forms replace, plus the never-drawn Packrat Skull shard (its head skin always won).
RETIRED_DRAWINGS = (HEAD | set(VANILLA) | {'SHARD_PACKRAT_SKULL'})


def apply(out):
    """out: the whole item_looks.json object ({'items': rows, ...}); edits it in place."""
    rows = out['items']
    for sbid in HEAD:
        row = rows[sbid]
        for k in ('o', 's', 'i', 'l', 'e'):
            row.pop(k, None)
        row['k'] = sbid.lower()
    for sbid, item in VANILLA.items():
        row = rows[sbid]
        for k in ('o', 's', 'l', 'e', 'k'):
            row.pop(k, None)
        row['i'] = item
    for sbid in GLINT:
        rows[sbid]['g'] = 1
    rows['SHARD_PACKRAT_SKULL'].pop('o', None)
    out['models'] = dict(MODELS)
    return out
