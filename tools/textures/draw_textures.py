"""Writes Pack Disabler's own item textures, reproducibly, from the grids in art_*.py.

  src/main/resources/assets/killer560smod/textures/item/packdisabler/<key>.png   16x16 RGBA
  src/main/resources/assets/killer560smod/models/item/packdisabler/<key>.json    item/generated, layer0 = the png
  src/main/resources/assets/killer560smod/items/packdisabler/<key>.json         the item model definition

<key> is the SkyBlock id lower-cased ("BEE_SALIVA" -> "bee_saliva"); the item model id the mod sets is
killer560smod:packdisabler/<key>. These are ordinary assets in the mod's namespace, so a resource pack the player
selects that supplies the same path draws instead of ours.

Run:  python -X utf8 tools/textures/draw_textures.py [contact sheet path]
Then re-run tools/items/gen_item_looks.py so the item table points at the new textures.
All art is original (see engine.py).
"""
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import engine  # noqa: E402
import art_accessories  # noqa: E402
import art_items  # noqa: E402

ASSETS = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'killer560smod')
TEX = os.path.join(ASSETS, 'textures', 'item', 'packdisabler')
MODELS = os.path.join(ASSETS, 'models', 'item', 'packdisabler')
DEFS = os.path.join(ASSETS, 'items', 'packdisabler')

ITEMS = {}
ITEMS.update(art_accessories.ITEMS)
ITEMS.update(art_items.ITEMS)

# Items that turned out to have a real pre-pack look (an old vanilla item or head skin), which always wins over ours -
# killer560 (2026-10-07) wants the original look for those. Their grids stay in art_items.py for reference but are not
# shipped. If gen_item_looks.py ever reports one of these as having no look again, take it out of this set.
HAS_OLD_LOOK = {'AMALGAMATED_CRIMSONITE', 'DUNGEON_CHEST_KEY', 'SIGNAL_ENHANCER', 'SUMMONING_EYE', 'TITANOBOA_SHED',
                'TUNGSTEN_KEY', 'UMBER_KEY',
                # New tiers that take their family's old look (gen_item_looks.py FAMILY).
                'GIGANTIC_FISHING_NET', 'ARCHER_DUNGEON_ABILITY_1', 'FIGHTING_BOOSTER_UNCOMMON',
                'FORAGING_FORTUNE_BOOSTER_UNCOMMON', 'FORAGING_WISDOM_BOOSTER_UNCOMMON', 'SWEEP_BOOSTER_UNCOMMON'}
for _sbid in HAS_OLD_LOOK:
    ITEMS.pop(_sbid, None)


def key(sbid):
    return sbid.lower().replace(':', '_').replace('-', '_')


def to_image(pixels):
    img = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), pixels[y][x])
    return img


def write_json(path, obj):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, separators=(',', ':'))
        f.write('\n')


def main():
    for d in (TEX, MODELS, DEFS):
        os.makedirs(d, exist_ok=True)
    # Drop textures whose grid was removed, so the jar never ships an orphan.
    wanted = {key(s) for s in ITEMS}
    for d, ext in ((TEX, '.png'), (MODELS, '.json'), (DEFS, '.json')):
        for f in os.listdir(d):
            if f.endswith(ext) and f[:-len(ext)] not in wanted:
                os.remove(os.path.join(d, f))
    images = {}
    for sbid in sorted(ITEMS):
        grid, pal = ITEMS[sbid]()
        img = to_image(engine.render(grid, pal))
        k = key(sbid)
        img.save(os.path.join(TEX, k + '.png'), optimize=True)
        write_json(os.path.join(MODELS, k + '.json'),
                   {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'killer560smod:item/packdisabler/' + k}})
        write_json(os.path.join(DEFS, k + '.json'),
                   {'model': {'type': 'minecraft:model', 'model': 'killer560smod:item/packdisabler/' + k}})
        images[sbid] = img
    print('wrote', len(images), 'textures to', os.path.normpath(TEX))
    if len(sys.argv) > 1:
        contact_sheet(images, sys.argv[1])


def contact_sheet(images, path):
    names = {}
    names_file = os.path.join(HERE, 'names.json')
    if os.path.exists(names_file):
        names = json.load(open(names_file, encoding='utf-8'))
    cols = 6
    cell_w, cell_h = 300, 168
    rows = (len(images) + cols - 1) // cols
    sheet = Image.new('RGBA', (cols * cell_w, rows * cell_h + 40), (32, 30, 36, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype('arial.ttf', 15)
        small = ImageFont.truetype('arial.ttf', 12)
    except OSError:
        font = small = ImageFont.load_default()
    draw.text((10, 10), 'Pack Disabler - original textures (8x, and at GUI scale 2 in a slot). killer560smod, all original art.',
              fill=(240, 230, 220, 255), font=font)
    for i, sbid in enumerate(sorted(images)):
        cx = (i % cols) * cell_w
        cy = 40 + (i // cols) * cell_h
        big = images[sbid].resize((128, 128), Image.NEAREST)
        draw.rectangle([cx + 8, cy + 4, cx + 8 + 131, cy + 4 + 131], fill=(139, 139, 139, 255), outline=(55, 55, 55, 255))
        sheet.alpha_composite(big, (cx + 10, cy + 6))
        # As drawn in an inventory slot at GUI scale 2: 32x32 on the slot grey with its bevel.
        sx, sy = cx + 150, cy + 6
        draw.rectangle([sx, sy, sx + 35, sy + 35], fill=(139, 139, 139, 255))
        draw.line([sx, sy, sx + 35, sy], fill=(55, 55, 55, 255), width=2)
        draw.line([sx, sy, sx, sy + 35], fill=(55, 55, 55, 255), width=2)
        draw.line([sx, sy + 35, sx + 35, sy + 35], fill=(255, 255, 255, 255), width=2)
        draw.line([sx + 35, sy, sx + 35, sy + 35], fill=(255, 255, 255, 255), width=2)
        sheet.alpha_composite(images[sbid].resize((32, 32), Image.NEAREST), (sx + 2, sy + 2))
        draw.text((cx + 10, cy + 140), names.get(sbid, sbid)[:38], fill=(240, 230, 220, 255), font=font)
        draw.text((cx + 150, cy + 50), sbid[:22], fill=(160, 150, 145, 255), font=small)
        if len(sbid) > 22:
            draw.text((cx + 150, cy + 64), sbid[22:44], fill=(160, 150, 145, 255), font=small)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    sheet.save(path)
    print('contact sheet', path)


if __name__ == '__main__':
    main()
