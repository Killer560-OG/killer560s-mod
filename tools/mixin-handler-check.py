"""For every @Inject whose handler takes target arguments (anything before its CallbackInfo), check that some overload
of each named target method on the given classes dir has exactly those parameter types (simple names, generics
dropped). Mixin rejects a mismatch at load (InvalidInjectionException), which no compile catches.
Usage: python tools/mixin-handler-check.py <classes-dir-or-jar> <src-root>[,...]"""
import glob
import re
import subprocess
import sys

CP = sys.argv[1]
ROOTS = sys.argv[2].split(',')
cache = {}


def overloads(fqcn):
    if fqcn not in cache:
        out = subprocess.run(['javap', '-p', '-cp', CP, fqcn], capture_output=True, text=True).stdout
        m = {}
        for line in out.splitlines():
            mm = re.search(r'\s([\w$<>]+)\(([^)]*)\)', line)
            if mm and line.strip().endswith(';'):
                params = [p.strip().split('.')[-1].split('$')[-1] for p in mm.group(2).split(',') if p.strip()]
                m.setdefault(mm.group(1), []).append(params)
        cache[fqcn] = (m, bool(out.strip()))
    return cache[fqcn]


def resolve(imports, simple, pkg):
    head = simple.split('.')[0]
    if head in imports:
        return imports[head] + ''.join('$' + r for r in simple.split('.')[1:])
    return pkg + '.' + simple.replace('.', '$')


def simple(t):
    t = re.sub(r'<.*>', '', t).strip()
    t = t.replace('final ', '').strip()
    t = t.split()[0] if ' ' in t else t
    return t.split('.')[-1]


files = []
for r in ROOTS:
    files += glob.glob(r + '/**/*.java', recursive=True)
for path in sorted(set(files)):
    text = open(path, encoding='utf-8', errors='replace').read()
    if '@Mixin' not in text:
        continue
    text = re.sub(r'"\s*\+\s*"', '', text)
    pkg = (re.search(r'^package ([\w.]+);', text, re.M) or [None, ''])[1]
    imports = {i.split('.')[-1]: i for i in re.findall(r'^import ([\w.$]+);', text, re.M)}
    mix = re.search(r'@Mixin\s*\(\s*(?:value\s*=\s*)?\{?\s*([\w.$]+)\.class', text)
    if not mix:
        continue
    cls = resolve(imports, mix.group(1), pkg)
    meths, ok = overloads(cls)
    if not ok:
        continue
    for m in re.finditer(r'@Inject\s*\(', text):
        # annotation text up to the handler's opening brace
        body = text.find('{', text.find(')', m.end()))
        seg = text[m.start():]
        hm = re.search(r'\)\s*\n\s*(?:private|public|protected)?\s*(?:static\s+)?[\w<>\[\]]+\s+([\w$]+)\s*\(([^)]*)\)', seg, re.S)
        if not hm:
            continue
        ann = seg[:hm.start() + 1]
        names = re.findall(r'"([^"]*)"', (re.search(r'method\s*=\s*(\{[^}]*\}|"[^"]*")', ann) or [None, ''])[1] or '')
        params = [simple(p) for p in re.split(r',(?![^<]*>)', hm.group(2)) if p.strip()]
        if 'CallbackInfo' in params:
            before = params[:params.index('CallbackInfo')]
        elif 'CallbackInfoReturnable' in params:
            before = params[:params.index('CallbackInfoReturnable')]
        else:
            continue
        if not before:
            continue
        found_any = False
        for n in names:
            if '(' in n:
                continue  # full descriptor: mixin-descriptor-check.py covers it
            for ov in meths.get(n, []):
                if ov == before:
                    found_any = True
        plain = [n for n in names if '(' not in n]
        if plain and not found_any and any(n in meths for n in plain):
            print(f'{path} | {cls} | {hm.group(1)} {before} matches no overload of {plain}: '
                  + str({n: meths.get(n) for n in plain}))
print('checked', len(cache))
