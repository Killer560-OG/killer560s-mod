"""Pairs every room lever with the gate it takes away, for SimRoomLevers.

Hypixel moves a lever's gate with a command, so no capture connects the two. What does connect them is the
secret ROUTE: the community route data (Quantizr's DungeonRooms waypoints, as shipped in Skyblocker's test
resources; read, never copied into the mod) numbers each secret's steps, so "5 - Lever" belongs to the same
secret as "5 - Chest". That names the lever's TARGET. The gate is then read off the capture: a connected body
of gate material (logs, wood, planks, wooden stairs/slabs, iron bars, barriers) that fills its own bounding box,
is a wall (one axis at most two thick), and stands nearest the target, preferring one that holds the route's
"N - Stonk" point (the place you can mine through instead of using the lever).

    python tools/lever_gates.py [Room ...]        # every room, or the named ones
        --ref DIR   the killer560s-mod-logs checkout holding skyblocker-ref (default: ../killer560s-mod-logs)

Prints, per lever: its capture position (or that the capture has no lever there), its target, and the chosen
box with what it holds, plus the runners-up. Check every new pairing against a screenshot before it becomes a
rule; the tool's output is a proposal, the rule table is the decision.
"""
import argparse
import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from capture_decode import load, at  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOMS = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'killer560smod', 'rooms')

WOODS = ('oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'pale_oak')
TARGET_KINDS = {'chest', 'item', 'wither', 'bat', 'redstone_key'}


def nm(b):
    return b.split('[')[0].replace('minecraft:', '') if b else None


def gate_class(n):
    """1 for wood, bars and barriers; 2 for solid cobblestone; 3 for walls and fences - Rails' and Spider's gates are
    lattices of cobblestone WALL blocks set in a cobblestone facade, and grown together with the facade they were one
    shapeless body that filled no box; 0 for anything else. A body is grown inside one class, so a bars gate between
    cobblestone pillars stays a bars gate."""
    if n is None:
        return 0
    if n in ('iron_bars', 'barrier'):
        return 1
    if n.endswith('_wall') or n.endswith('_fence'):
        return 3
    if n in ('cobblestone', 'mossy_cobblestone'):
        return 2
    for w in WOODS:
        if n.startswith(w + '_') and n[len(w) + 1:] in ('log', 'wood', 'planks', 'stairs', 'slab', 'trapdoor'):
            return 1
    return 0


def norm(s):
    return re.sub(r'[^a-z]', '', s.lower())


def rot(x, z, k):
    for _ in range(k):
        x, z = -z, x
    return x, z


class Capture:
    def __init__(self, path):
        self.r = load(path)
        self.name = self.r['name']
        r = self.r
        self.cells = {}
        for y in range(r['minY'], r['maxY'] + 1):
            for z in range(r['sz']):
                for x in range(r['sx']):
                    n = nm(at(r, x, y, z))
                    if n and n != 'air':
                        self.cells[(x, y, z)] = n
        self.chests = [p for p, n in self.cells.items() if n in ('chest', 'trapped_chest')]
        self.levers = [p for p, n in self.cells.items() if n == 'lever']
        self._gates = None

    def gates(self):
        if self._gates is not None:
            return self._gates
        cls = {p: gate_class(n) for p, n in self.cells.items()}
        cells = {p for p, k in cls.items() if k}
        seen, out = set(), []
        for c in cells:
            if c in seen:
                continue
            st, comp = [c], []
            seen.add(c)
            while st:
                p = st.pop()
                comp.append(p)
                for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                    q = (p[0] + d[0], p[1] + d[1], p[2] + d[2])
                    if q in cells and q not in seen and cls[q] == cls[p]:
                        seen.add(q)
                        st.append(q)
            if len(comp) < 6 or len(comp) > 120:
                continue
            lo = tuple(min(p[i] for p in comp) for i in range(3))
            hi = tuple(max(p[i] for p in comp) for i in range(3))
            dims = [hi[i] - lo[i] + 1 for i in range(3)]
            vol = dims[0] * dims[1] * dims[2]
            # a wall or a hatch: thin across one axis (a hatch is thin in y), at least two along the other two, and
            # filling its box (a frame round an opening counts only if the opening is filled too)
            # A lattice of walls fills only about two thirds of its box (Rails 21 of 30, Spider 16 of 25).
            fill = 0.5 if cls[comp[0]] == 3 else 0.85
            if min(dims) > 2 or sorted(dims)[1] < 2 or len(comp) < fill * vol:
                continue
            kinds = {}
            for p in comp:
                kinds[self.cells[p]] = kinds.get(self.cells[p], 0) + 1
            out.append(dict(lo=lo, hi=hi, n=len(comp), vol=vol, kinds=kinds))
        self._gates = out
        return out


def box_dist(p, g):
    d = 0
    for i in range(3):
        if p[i] < g['lo'][i]:
            d += (g['lo'][i] - p[i]) ** 2
        elif p[i] > g['hi'][i]:
            d += (p[i] - g['hi'][i]) ** 2
    return d ** 0.5


def fit(cap, entries):
    """The quarter turn and shift that land the route's chests (and levers) on the capture's."""
    sb_chests = [(e['x'], e['y'], e['z']) for e in entries if e['category'] == 'chest']
    sb_levers = [(e['x'], e['y'], e['z']) for e in entries if e['category'] == 'lever']
    best = (0, None)
    capc = set(cap.chests)
    capl = cap.levers
    for k in range(4):
        rs = [(rot(x, z, k), y) for x, y, z in sb_chests]
        for (rx, rz), y in rs:
            for c in cap.chests:
                if c[1] != y:
                    continue
                t = (c[0] - rx, c[2] - rz)
                score = sum(1 for (ax, az), ay in rs if (ax + t[0], ay, az + t[1]) in capc) * 10
                for x, y2, z in sb_levers:
                    ax, az = rot(x, z, k)
                    q = (ax + t[0], y2, az + t[1])
                    if any(abs(l[0] - q[0]) <= 1 and abs(l[1] - q[1]) <= 1 and abs(l[2] - q[2]) <= 1 for l in capl):
                        score += 1
                if score > best[0]:
                    best = (score, (k, t))
    return best


def to_cap(p, tr):
    k, t = tr
    x, z = rot(p[0], p[2], k)
    return (x + t[0], p[1], z + t[1])


def groups(name):
    m = re.match(r'^([\d/]+)\s*-', name)
    return set(m.group(1).split('/')) if m else set()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('rooms', nargs='*')
    ap.add_argument('--ref', default=os.path.join(os.path.dirname(ROOT), 'killer560s-mod-logs'))
    a = ap.parse_args()
    sb = json.load(open(os.path.join(a.ref, 'skyblocker-ref', 'src', 'test', 'resources', 'assets', 'skyblocker',
                                     'dungeons', 'secretlocations.json')))
    caps = {}
    for path in sorted(glob.glob(os.path.join(ROOMS, '*.json'))):
        if os.path.basename(path) == 'index.json':
            continue
        try:
            c = Capture(path)
        except Exception:
            continue
        caps[norm(c.name)] = c
    want = {norm(r) for r in a.rooms}
    for key, entries in sb.items():
        if not isinstance(entries, list) or not any(e['category'] == 'lever' for e in entries):
            continue
        base = norm(re.sub(r'-\d+$', '', key))
        # Every capture, best fit wins: a route's name can belong to a different room (Rail Track's route is Rails').
        cands = list(caps.values())
        best = (0, None, None)
        for c in cands:
            s, tr = fit(c, entries)
            if s > best[0]:
                best = (s, tr, c)
        score, tr, cap = best
        if cap is None:
            print('%s: no capture fits' % key)
            continue
        if want and norm(cap.name) not in want:
            continue
        print('== %s (route %s, fit %d, turn %d shift %s)' % (cap.name, key, score, tr[0], tr[1]))
        for e in entries:
            if e['category'] != 'lever':
                continue
            p = to_cap((e['x'], e['y'], e['z']), tr)
            real = [l for l in cap.levers if abs(l[0] - p[0]) <= 1 and abs(l[1] - p[1]) <= 1 and abs(l[2] - p[2]) <= 1]
            lever = real[0] if real else p
            g = groups(e['secretName'])
            targets = [to_cap((t['x'], t['y'], t['z']), tr) for t in entries
                       if t['category'] in TARGET_KINDS and groups(t['secretName']) & g]
            hints = [to_cap((t['x'], t['y'], t['z']), tr) for t in entries
                     if t['category'] in ('stonk', 'superboom', 'entrance', 'aotv') and groups(t['secretName']) & g]
            print('  lever "%s" at capture %s%s; targets %s; stonk/boom %s' % (
                e['secretName'], lever, '' if real else ' (NOT IN CAPTURE: ' + str(cap.cells.get(p, 'air')) + ')',
                targets, hints))
            ranked = []
            for gt in cap.gates():
                d = min([box_dist(t, gt) for t in targets] or [99])
                h = min([box_dist(t, gt) for t in hints] or [99])
                ranked.append((0 if h <= 1.5 else 1, d, gt))
            ranked.sort(key=lambda r: (r[0], r[1]))
            for i, (h, d, gt) in enumerate(ranked[:3]):
                print('    %s box %s..%s %d/%d %s, %.1f from target%s' % (
                    '->' if i == 0 else '  ', gt['lo'], gt['hi'], gt['n'], gt['vol'], gt['kinds'], d,
                    ', holds the stonk/boom point' if h == 0 else ''))


if __name__ == '__main__':
    main()
