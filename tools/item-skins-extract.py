# Builds src/main/resources/assets/killer560smod/armourdye/item-skins.json, the Hypixel item skin table Custom Items' Skin row
# reads (2026-11-01). Hypixel's public items resource has only ~170 skins and none of the Wither Goggles ones, so the table
# comes from the NotEnoughUpdates-REPO items/ folder: every item whose name ends in " Skin" (minion, barn, greenhouse and
# pet skins left out) that carries a head texture, with the items its lore says it "can be applied to".
#
#   curl -L -o repo.zip https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/refs/heads/master.zip
#   python -I tools/item-skins-extract.py repo.zip <out.json> <commit sha>
import json, re, sys, zipfile

z = zipfile.ZipFile(sys.argv[1])
strip = re.compile('§.')
out = []
for n in z.namelist():
    if '/items/' not in n or not n.endswith('.json'):
        continue
    try:
        d = json.loads(z.read(n).decode('utf-8'))
    except Exception:
        continue
    name = strip.sub('', d.get('displayname', ''))
    if not name.endswith(' Skin') or 'Minion Skin' in name or 'Barn Skin' in name or 'Greenhouse Skin' in name:
        continue
    iid = d.get('internalname') or n.rsplit('/', 1)[1][:-5]
    if iid.startswith('PET_SKIN'):
        continue
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)', d.get('nbttag', ''))
    if not m:
        continue
    lore = ' '.join(strip.sub('', l) for l in d.get('lore', []))
    t = re.search(r'This skin can be applied to (.+?)\.(?:\s|$)', lore)
    targets = []
    if t:
        targets = [x.strip() for x in t.group(1).replace(', and ', ', ').replace(' and ', ', ').split(',') if x.strip()]
    out.append({'id': iid, 'name': name, 'targets': targets, 'texture': m.group(1)})
out.sort(key=lambda e: e['id'])
json.dump({'source': 'NotEnoughUpdates-REPO items/ at commit ' + sys.argv[3], 'skins': out},
          open(sys.argv[2], 'w', encoding='utf-8'), separators=(',', ':'))
print(len(out), 'skins,', sum(1 for e in out if not e['targets']), 'without targets')
