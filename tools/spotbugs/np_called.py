"""Group NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE findings by the method whose result may be null (triage helper).
Usage: python -I tools/spotbugs/np_called.py build/spotbugs/report.xml [METHOD_SUBSTR to list locations]"""
import sys
import collections
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
want = sys.argv[2] if len(sys.argv) > 2 else None
by = collections.Counter()


def src_line(loc):
    import os
    f, _, n = loc.rpartition(':')
    for r in ('src/main/java', 'src/mc26_3/java'):
        p = os.path.join(r, f)
        if os.path.exists(p) and n.isdigit():
            with open(p, encoding='utf-8') as fh:
                return fh.readlines()[int(n) - 1].strip()[:160]
    return '?'

for b in root.iter('BugInstance'):
    if b.get('type') != 'NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE' or int(b.get('priority')) > 2:
        continue
    called = None
    for m in b.findall('Method'):
        if m.get('role') == 'METHOD_CALLED':
            called = f"{m.get('classname')}.{m.get('name')}"
    sl = b.find('SourceLine')
    loc = f"{sl.get('sourcepath')}:{sl.get('start')}" if sl is not None else '?'
    by[called] += 1
    if want and called and want in called:
        print(loc.replace('com/killer560/hub/', ''), '|', src_line(loc))
if not want:
    for k, v in by.most_common():
        print(v, k)
