"""For NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE sites of one called method, show the lines above each site that mention
a null check of the same call (triage helper): a site with no guard shown needs reading.
Usage: python -I tools/spotbugs/guard_check.py build/spotbugs/report.xml METHOD_SUBSTR GUARD_REGEX [LOOKBACK]"""
import os
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
want, guard = sys.argv[2], re.compile(sys.argv[3])
back = int(sys.argv[4]) if len(sys.argv) > 4 else 25
seen = set()
for b in root.iter('BugInstance'):
    if b.get('type') != 'NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE':
        continue
    called = [f"{m.get('classname')}.{m.get('name')}" for m in b.findall('Method') if m.get('role') == 'METHOD_CALLED']
    if not called or want not in called[0]:
        continue
    sl = b.find('SourceLine')
    f, n = sl.get('sourcepath'), int(sl.get('start'))
    if (f, n) in seen:
        continue
    seen.add((f, n))
    for r in ('src/main/java', 'src/mc26_3/java'):
        p = os.path.join(r, f)
        if os.path.exists(p):
            lines = open(p, encoding='utf-8').readlines()
            hits = [f"{i + 1}" for i in range(max(0, n - 1 - back), n) if guard.search(lines[i])]
            tag = 'GUARDED ' + ','.join(hits) if hits else 'NO GUARD'
            print(f"{tag:<22} {f.replace('com/killer560/hub/', '')}:{n} | {lines[n - 1].strip()[:110]}")
            break
