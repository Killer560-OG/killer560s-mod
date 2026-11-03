"""Checks every head skin in the Pack Disabler table still resolves on textures.minecraft.net (HEAD request)."""
import json
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

table = json.load(open(sys.argv[1], encoding='utf-8'))['items']
hashes = sorted({r['s'] for r in table.values() if 's' in r})


def check(h):
    url = 'https://textures.minecraft.net/texture/' + h
    for _ in range(3):
        try:
            req = urllib.request.Request(url, method='HEAD', headers={'User-Agent': 'killer560smod-table-check'})
            with urllib.request.urlopen(req, timeout=20) as r:
                return h, r.status
        except urllib.error.HTTPError as e:
            return h, e.code
        except Exception as e:
            err = str(e)
    return h, err


with ThreadPoolExecutor(16) as ex:
    res = list(ex.map(check, hashes))
bad = [(h, s) for h, s in res if s != 200]
print('skins', len(hashes), 'ok', len(hashes) - len(bad), 'bad', len(bad))
for h, s in bad:
    ids = [k for k, r in table.items() if r.get('s') == h]
    print(' ', s, h, ids)
