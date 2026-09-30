"""Check every mixin injection target in this repo against a real Minecraft jar.

Why this exists: 31 of the 61 mixin configs use `defaultRequire: 0`, so a target that moved does NOT fail -
the feature simply never runs and nothing appears in the log. That is the failure mode a Minecraft version
bump produces most of, and it is invisible to `compileJava`. This is the repeatable version of the javap
check CLAUDE.md asks for.

Run it once per supported version, with that version's jar FIRST:

  # 26.1.2
  python tools/mixin-target-survey.py       .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.1.2/*-26.1.2.jar       <the-other-version-jar> src/main/java,src/mc26_1/java

  # 26.2
  python tools/mixin-target-survey.py <26.2-jar-or-extracted-classes-dir>       <the-other-version-jar> src/main/java,src/mc26_2/java

Both jars are passed because the second one only decides the wording: a target missing from the version under
test but present in the other is a version break, while one missing from both was already wrong.

For each mixin it resolves the `@Mixin` target class from the file's imports (including `Outer.Inner`),
lists that class's members with javap, walks the superclass and interface chain (an `@Accessor` may name an
inherited field, and not walking produces false alarms), then reports every `method =` name, every
`@Accessor`/`@Invoker` name and every `@At(target = "L...;member")` owner and member the class does not
have. It also flags a method that exists ONLY as an inherited one, because Mixin matches DECLARED methods and
that case is a silent miss rather than a working injection.

EXPECTED findings, which are not faults: a mixin written `method = {"oldName", "newName"}` to serve both
versions reports whichever name belongs to the other version, and ModMenu's ModListWidget does not resolve on
either version (a real but pre-existing bug, not a port break).
"""
import glob
import re
import subprocess
import sys

MC262, MC261 = sys.argv[1], sys.argv[2]
ROOTS = sys.argv[3].split(',')

_raw = {}


def javap(cp, fqcn):
    key = (cp, fqcn)
    if key in _raw:
        return _raw[key]
    out = subprocess.run(['javap', '-p', '-cp', cp, fqcn], capture_output=True, text=True)
    if out.returncode != 0:
        # JDK / platform classes resolve without a classpath.
        out = subprocess.run(['javap', '-p', fqcn], capture_output=True, text=True)
        if out.returncode != 0:
            _raw[key] = None
            return None
    _raw[key] = out.stdout
    return out.stdout


_members = {}


def declared(cp, fqcn):
    text = javap(cp, fqcn)
    if text is None:
        return None
    names = set()
    for line in text.split('\n'):
        s = line.strip().rstrip(';')
        if ' class ' in s or ' interface ' in s or ' enum ' in s or ' record ' in s:
            continue
        m = re.search(r'([A-Za-z_$][\w$]*)\(', s)
        if m:
            names.add(m.group(1))
        elif s and '(' not in s:
            f = re.search(r'([A-Za-z_$][\w$]*)$', s)
            if f:
                names.add(f.group(1))
    simple = fqcn.split('.')[-1].split('$')[-1]
    if simple in names:
        names.add('<init>')
    return names


def members(cp, fqcn, depth=0):
    """Declared member names, plus every superclass's and interface's, up the chain."""
    key = (cp, fqcn)
    if key in _members:
        return _members[key]
    text = javap(cp, fqcn)
    if text is None:
        _members[key] = None
        return None
    _members[key] = set()  # guard against a cycle while we recurse
    names = set(['<clinit>'])
    header = ''
    for line in text.split('\n'):
        s = line.strip().rstrip(';')
        if ' class ' in s or ' interface ' in s or ' enum ' in s or ' record ' in s:
            if not header:
                header = s
        m = re.search(r'([A-Za-z_$][\w$]*)\(', s)
        if m:
            names.add(m.group(1))
        elif re.search(r'([A-Za-z_$][\w$]*)$', s) and '(' not in s and s:
            names.add(re.search(r'([A-Za-z_$][\w$]*)$', s).group(1))
    simple = fqcn.split('.')[-1].split('$')[-1]
    if simple in names:
        names.add('<init>')
    if depth < 8 and header:
        sup = re.search(r'\bextends\s+([\w.$]+)', header)
        if sup and sup.group(1) != 'java.lang.Object':
            up = members(cp, sup.group(1), depth + 1)
            if up:
                names |= up
        impl = re.search(r'\bimplements\s+(.+)$', header)
        if impl:
            for iface in impl.group(1).split(','):
                iface = iface.strip().split('<')[0]
                if iface and iface != 'java.lang.Object':
                    up = members(cp, iface, depth + 1)
                    if up:
                        names |= up
    _members[key] = names
    return names


def resolve(imports, simple, pkg):
    if '.' not in simple:
        return imports.get(simple, pkg + '.' + simple)
    outer, inner = simple.rsplit('.', 1)
    if outer in imports:                   # SpriteIconButton.TextAndIcon -> ...SpriteIconButton$TextAndIcon
        return imports[outer] + '$' + inner
    return simple


rows = []
seen = set()


def note(path, fqcn, problem, extra):
    key = (path, fqcn, problem)
    if key in seen:
        return
    seen.add(key)
    rows.append((path, fqcn, problem, extra))


files = []
for _r in ROOTS:
    files += glob.glob(_r + '/**/mixin/*.java', recursive=True)
    files += glob.glob(_r + '/**/*Mixin*.java', recursive=True)
files = sorted(set(files))
for path in files:
    text = open(path, encoding='utf-8', errors='replace').read().replace('\r\n', '\n')
    pkg = re.search(r'^package ([\w.]+);', text, re.M)
    pkg = pkg.group(1) if pkg else ''
    imports = {}
    for imp in re.findall(r'^import (?:static )?([\w.$]+);', text, re.M):
        imports[imp.split('.')[-1]] = imp
    mix = re.search(r'@Mixin\s*\(\s*(?:value\s*=\s*)?\{?\s*([\w.$]+)\.class', text)
    targets = re.search(r'@Mixin\s*\(\s*targets\s*=\s*\{?\s*"([^"]+)"', text)
    if mix:
        fqcn = resolve(imports, mix.group(1), pkg)
    elif targets:
        fqcn = targets.group(1).replace('/', '.')
    else:
        continue

    m262 = members(MC262, fqcn)
    m261 = members(MC261, fqcn)
    if not m262:
        note(path, fqcn, 'MIXIN TARGET CLASS not resolvable on 26.2',
             'on 26.1.2: ' + ('yes' if m261 else 'ALSO NO'))
        continue

    wanted = set()
    for group in re.findall(r'method\s*=\s*(\{[^}]*\}|"[^"]*")', text):
        for name in re.findall(r'"([^"]*)"', group):
            wanted.add(name)
    for name in re.findall(r'@Accessor\s*\(\s*"([^"]+)"', text):
        wanted.add(name)
    for name in re.findall(r'@Invoker\s*\(\s*"([^"]+)"', text):
        wanted.add(name)

    ats = set(re.findall(r'target\s*=\s*"(L[^"]+)"', text))
    for cname in re.findall(r'target\s*=\s*([A-Z_][A-Z0-9_]*)\b', text):
        c = re.search(r'String\s+' + cname + r'\s*=\s*\s*\n?\s*"([^"]+)"', text)
        if c:
            ats.add(c.group(1))

    for name in sorted(wanted):
        bare = name.split('(')[0].split(':')[-1].strip()
        if not bare or bare.startswith('L') or bare == '*':
            continue
        d262 = declared(MC262, fqcn) or set()
        if bare not in m262:
            note(path, fqcn, 'method missing on 26.2: ' + name,
                 'on 26.1.2: ' + ('yes' if m261 and bare in m261 else 'NO'))
        elif bare not in d262:
            note(path, fqcn, 'INHERITED ONLY on 26.2 (Mixin needs it declared): ' + name,
                 'declared on 26.1.2: ' + ('yes' if (declared(MC261, fqcn) or set()) and bare in (declared(MC261, fqcn) or set()) else 'NO'))

    for t in sorted(ats):
        m = re.match(r'L([\w/$]+);([\w<>$]+)', t)
        if not m:
            continue
        owner = m.group(1).replace('/', '.')
        member = m.group(2)
        om = members(MC262, owner)
        if not om:
            note(path, fqcn, '@At owner not resolvable on 26.2: ' + owner, '')
        elif member not in om:
            om261 = members(MC261, owner)
            note(path, fqcn, '@At member missing on 26.2: ' + owner + '#' + member,
                 'on 26.1.2: ' + ('yes' if om261 and member in om261 else 'NO'))

for path, fqcn, problem, extra in rows:
    print('%-58s | %-50s | %s | %s' % (path.replace('\\', '/'), fqcn, problem, extra))
print('\n%d finding(s) over %d mixin file(s)' % (len(rows), len(files)))
