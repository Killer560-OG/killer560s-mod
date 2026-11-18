"""Applies owner_looks.py to the current item_looks.json without the full table refresh, and checks every skin exists.

    python -I tools/items/apply_owner_looks.py
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, 'tools', 'items'))
import owner_looks  # noqa: E402

TABLE = os.path.join(ROOT, 'src/main/resources/assets/killer560smod/skyblock/item_looks.json')
SKINS = os.path.join(ROOT, 'src/main/resources/assets/killer560smod/textures/entity/packdisabler')

with open(TABLE, encoding='utf-8', newline='') as f:
    raw = f.read()
data = json.loads(raw)
missing = [i for i in list(owner_looks.HEAD) + list(owner_looks.VANILLA) if i not in data['items']]
if missing:
    raise SystemExit('ids not in the table: %s' % missing)
owner_looks.apply(data)
keys = {r['k'] for r in data['items'].values() if 'k' in r} | set(data['models'].values()) | set(owner_looks.PENDING)
keys |= {'critter_capsule_active', 'critter_capsule_inactive', 'critter_capsule_fail',
         'masterful_critter_capsule_active', 'masterful_critter_capsule_inactive'}
absent = sorted(k for k in keys if not os.path.exists(os.path.join(SKINS, k + '.png')))
if absent:
    raise SystemExit('skins missing: %s' % absent)
with open(TABLE, 'w', encoding='utf-8', newline='') as f:  # the generator's own form
    json.dump(data, f, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
print('applied', len(owner_looks.HEAD), 'heads', len(owner_looks.VANILLA), 'vanilla', len(keys), 'skins ok')
