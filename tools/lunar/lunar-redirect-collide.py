"""Match our @Redirect sites against Lunar mixin classes (from lunar-mixin-scan.txt) that reference the same INVOKE target
and the same mixin target class. Usage: python -I lunar-collide.py <mod src root> <scan file>"""
import os
import re
import sys

root, scan = sys.argv[1], sys.argv[2]
blocks, cur = [], None
for line in open(scan, encoding='utf-8'):
    if not line.startswith('    '):
        cur = [line.strip(), []]
        blocks.append(cur)
    elif cur:
        cur[1].append(line.strip())

for dp, _, fs in os.walk(root):
    for f in fs:
        if not f.endswith('.java'):
            continue
        p = os.path.join(dp, f)
        s = open(p, encoding='utf-8').read()
        if '@Redirect' not in s and '@Overwrite' not in s:
            continue
        m = re.search(r'@Mixin\(\s*(?:value\s*=\s*)?\{?\s*([A-Za-z0-9_.]+)\.class', s)
        tcls = m.group(1).split('.')[-1] if m else '?'
        for r in re.finditer(r'@(Redirect|Overwrite)\b(.*?)\)\s*\n\s*(?:private|public|protected)', s, re.S):
            body = r.group(2)
            meth = re.findall(r'method\s*=\s*\{?([^}\n]*)', body)
            tgt = re.findall(r'target\s*=\s*"([^"]+)"', body)
            for t in tgt:
                cls = re.sub(r'^L([^;]+);.*', r'\1', t).split('/')[-1]
                name = re.sub(r'^L[^;]+;', '', t)
                hits = []
                for head, lines in blocks:
                    if 'Redirect' not in head:
                        continue
                    txt = '\n'.join(lines)
                    if (cls + ';' + name.split('(')[0] + '(') in txt and tcls in txt:
                        hits.append(head.split(' ')[1])
                print(('COLLIDE ' if hits else 'ok      ') + f'{f} @{tcls} {meth} -> {t}' + (f'  lunar: {hits[:3]}' if hits else ''))
