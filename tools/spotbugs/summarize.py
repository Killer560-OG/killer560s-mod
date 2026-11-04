"""Print a SpotBugs XML report as one line per finding: priority, pattern, source file:line, message.
Usage: python -I tools/spotbugs/summarize.py build/spotbugs/report.xml [--priority 1|2]"""
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
maxp = int(sys.argv[sys.argv.index('--priority') + 1]) if '--priority' in sys.argv else 2
root = ET.parse(path).getroot()
rows = []
for b in root.iter('BugInstance'):
    pri = int(b.get('priority'))
    if pri > maxp:
        continue
    sl = b.find('SourceLine')
    if sl is None:
        cls = b.find('Class')
        sl = cls.find('SourceLine') if cls is not None else None
    loc = '?'
    if sl is not None:
        loc = f"{sl.get('sourcepath')}:{sl.get('start')}"
    msg = (b.findtext('ShortMessage') or '').strip()
    rows.append((pri, b.get('category'), b.get('type'), loc, msg))
rows.sort()
for r in rows:
    print(f"P{r[0]} {r[1]:<14} {r[2]:<38} {r[3]}  {r[4]}")
print(f"# {len(rows)} findings (priority <= {maxp})")
