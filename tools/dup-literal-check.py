"""Fail on a duplicate string literal inside one Set.of / Map.of / Map.ofEntries call (profshare-1112).

Set.of and Map.of throw IllegalArgumentException("duplicate element/key") when the class initializes, which for a class
loaded at boot (ProfileManager) crashes the game before the title screen. Two branches that each add the same name to a
list merge cleanly as text and only fail there, so run this after every merge:  python tools/dup-literal-check.py [src]
Exit 1 with one line per duplicate.
"""
import pathlib
import re
import sys

CALL = re.compile(r'\b(Set\.of|Map\.of|Map\.ofEntries)\s*\(')
STRING = re.compile(r'"(?:[^"\\\n]|\\.)*"')


def args_of(text, start):
    """Top-level arguments of the call whose '(' is at text[start - 1]; None if unbalanced."""
    depth, i, cur, out = 1, start, start, []
    while i < len(text):
        c = text[i]
        if c == '"':
            m = STRING.match(text, i)
            i = m.end() if m else i + 1
            continue
        if c == "'":
            j = text.find("'", i + 2 if text[i + 1] == '\\' else i + 1)
            i = j + 1 if j > 0 else i + 1
            continue
        if c == '/' and text.startswith('//', i):
            i = text.find('\n', i)
            if i < 0:
                return None
            continue
        if c == '/' and text.startswith('/*', i):
            i = text.find('*/', i) + 2
            continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
            if depth == 0:
                out.append(text[cur:i])
                return out
        elif c == ',' and depth == 1:
            out.append(text[cur:i])
            cur = i + 1
        i += 1
    return None


def strip_comments(s):
    return re.sub(r'//[^\n]*|/\*.*?\*/', '', s, flags=re.S).strip()


def keys(kind, args):
    if kind == 'Set.of':
        picked = args
    elif kind == 'Map.of':
        picked = args[0::2]
    else:  # Map.ofEntries: the first argument of each entry(...)
        picked = []
        for a in args:
            m = re.match(r'\s*(?:Map\.)?entry\s*\(', strip_comments(a))
            if m:
                inner = args_of(strip_comments(a), m.end())
                if inner:
                    picked.append(inner[0])
    for a in picked:
        a = strip_comments(a)
        if STRING.fullmatch(a):
            yield a


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'src')
    bad = 0
    for f in sorted(root.rglob('*.java')):
        text = f.read_text(encoding='utf-8', errors='replace')
        for m in CALL.finditer(text):
            args = args_of(text, m.end())
            if not args:
                continue
            seen = set()
            for k in keys(m.group(1), args):
                if k in seen:
                    line = text.count('\n', 0, m.start()) + 1
                    print(f'{f}:{line}: {m.group(1)} lists {k} twice (throws at class init)')
                    bad += 1
                seen.add(k)
    print(f'dup-literal-check: {bad} duplicate(s)')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
