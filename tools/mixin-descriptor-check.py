"""Checks every FULL descriptor in mixin strings (method = "name(desc)ret" and @At target = "Lowner;name(desc)ret")
against a class directory with javap -s. The name-only survey (tools/mixin-target-survey.py) cannot see a descriptor that
changed. Usage: python tools/mixin-descriptor-check.py <classes-dir> <src-root>[,<src-root>...]"""
import glob
import re
import subprocess
import sys

CP = sys.argv[1]
ROOTS = sys.argv[2].split(',')
cache = {}


def sigs(fqcn):
    if fqcn in cache:
        return cache[fqcn]
    out = subprocess.run(['javap', '-p', '-s', '-cp', CP, fqcn], capture_output=True, text=True).stdout
    found = set()
    name = None
    for line in out.splitlines():
        line = line.strip()
        if line.startswith('descriptor:'):
            if name:
                found.add(name + line.split(':', 1)[1].strip())
            name = None
            continue
        m = re.search(r'([\w$<>]+)\(', line)
        if m and line.endswith(';'):
            name = m.group(1)
            if name == fqcn.split('.')[-1].split('$')[-1]:
                name = '<init>'
        else:
            m2 = re.search(r' ([\w$]+);$', line)
            name = None
            if m2 and '(' not in line:
                name = m2.group(1) + ':'  # field
    cache[fqcn] = (found, bool(out.strip()))
    return cache[fqcn]


def resolve(imports, simple, pkg):
    head = simple.split('.')[0]
    if head in imports:
        base = imports[head]
        rest = simple.split('.')[1:]
        return base + ''.join('$' + r for r in rest)
    return pkg + '.' + simple.replace('.', '$')


files = []
for r in ROOTS:
    files += glob.glob(r + '/**/*.java', recursive=True)
bad = 0
for path in sorted(set(files)):
    text = open(path, encoding='utf-8', errors='replace').read()
    text = re.sub(r'"\s*\+\s*"', '', text)  # join "..." + "..." split across lines
    if '@Mixin' not in text:
        continue
    pkg = (re.search(r'^package ([\w.]+);', text, re.M) or [None, ''])[1]
    imports = {i.split('.')[-1]: i for i in re.findall(r'^import ([\w.$]+);', text, re.M)}
    mix = re.search(r'@Mixin\s*\(\s*(?:value\s*=\s*)?\{?\s*([\w.$]+)\.class', text)
    mixin_cls = resolve(imports, mix.group(1), pkg) if mix else None
    # method strings with a descriptor
    for group in re.findall(r'method\s*=\s*(\{[^}]*\}|"[^"]*")', text):
        names = re.findall(r'"([^"]*)"', group)
        full = [n for n in names if '(' in n]
        if not full or not mixin_cls:
            continue
        found, ok = sigs(mixin_cls)
        if not ok:
            continue
        hits = [n for n in full if n in found]
        if not hits:
            bad += 1
            print(f'{path} | {mixin_cls} | NO method descriptor matches: {full}')
    # @At targets (each @At(...) or at = {...} group)
    for tgt in re.findall(r'target\s*=\s*"(L[\w/$]+;[\w$<>]+(?:\([^"]*|:[^"]*))"', text):
        owner, member = tgt[1:].split(';', 1)
        fq = owner.replace('/', '.')
        found, ok = sigs(fq)
        if not ok:
            print(f'{path} | target owner not on this version: {fq} ({member[:60]})')
            continue
        if member not in found:
            print(f'{path} | target member not DECLARED on {fq}: {member}')
print('checked', len(cache), 'classes')
