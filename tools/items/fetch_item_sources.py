"""Downloads the inputs of gen_item_looks.py (and tools/bazaar/gen_bazaar_data.py) into one folder.

  items.json, bazaar.json      Hypixel's public SkyBlock resources (no API key needed)
  neu.zip, neu-<sha>.zip       NotEnoughUpdates-REPO (MIT, Moulberry) master and every snapshot in gen_item_looks.py
  vanilla_items.txt            assets/minecraft/items/*.json of the main version's client jar (--client-jar)

Data only: nothing here is code from those repositories, and nothing is shipped except the table the generator writes.
Hypixel's resource pack is NOT downloaded: copy the client's cached copy (<instance>/minecraft/downloads/<uuid>/<sha1>)
to <folder>/hypixel-pack.zip by hand if you want the coverage report.

Run:  python -X utf8 tools/items/fetch_item_sources.py <folder> --client-jar <minecraft-<ver>-client.jar>
"""
import argparse
import os
import re
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
NEU = 'https://codeload.github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/zip/'


def get(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 0 and 'neu-' in os.path.basename(path):
        print('have', path)
        return
    req = urllib.request.Request(url, headers={'User-Agent': 'killer560smod-item-table'})
    with urllib.request.urlopen(req, timeout=120) as r, open(path, 'wb') as f:
        f.write(r.read())
    print('got ', path, os.path.getsize(path), 'bytes')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('folder')
    ap.add_argument('--client-jar', help='Minecraft client jar for vanilla_items.txt')
    a = ap.parse_args()
    os.makedirs(a.folder, exist_ok=True)
    get('https://api.hypixel.net/v2/resources/skyblock/items', os.path.join(a.folder, 'items.json'))
    get('https://api.hypixel.net/v2/skyblock/bazaar', os.path.join(a.folder, 'bazaar.json'))
    get(NEU + 'refs/heads/master', os.path.join(a.folder, 'neu.zip'))
    src = open(os.path.join(HERE, 'gen_item_looks.py'), encoding='utf-8').read()
    for sha in re.findall(r"'neu-([0-9a-f]{10})\.zip'", src):
        get(NEU + sha, os.path.join(a.folder, 'neu-' + sha + '.zip'))
    if a.client_jar:
        z = zipfile.ZipFile(a.client_jar)
        pre = 'assets/minecraft/items/'
        ids = sorted(n[len(pre):-5] for n in z.namelist() if n.startswith(pre) and n.endswith('.json'))
        with open(os.path.join(a.folder, 'vanilla_items.txt'), 'w', encoding='utf-8', newline='\n') as f:
            f.write('\n'.join(ids) + '\n')
        print('vanilla items', len(ids))


if __name__ == '__main__':
    main()
