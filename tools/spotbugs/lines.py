"""Print the source line of each SpotBugs finding of one pattern (triage helper).
Usage: python -I tools/spotbugs/lines.py build/spotbugs/report.txt PATTERN [--exclude SUBSTR]"""
import sys
import os

rep, pat = sys.argv[1], sys.argv[2]
excl = sys.argv[sys.argv.index('--exclude') + 1] if '--exclude' in sys.argv else None
roots = ['src/main/java', 'src/mc26_3/java']
for line in open(rep, encoding='utf-8'):
    parts = line.split()
    if len(parts) < 4 or parts[2] != pat:
        continue
    loc = parts[3]
    if excl and excl in loc:
        continue
    f, _, n = loc.rpartition(':')
    src = None
    for r in roots:
        p = os.path.join(r, f)
        if os.path.exists(p):
            src = p
            break
    text = '?'
    if src and n.isdigit():
        with open(src, encoding='utf-8') as fh:
            lines = fh.readlines()
        i = int(n) - 1
        if 0 <= i < len(lines):
            text = lines[i].strip()
    print(f"{loc}  | {text[:170]}")
