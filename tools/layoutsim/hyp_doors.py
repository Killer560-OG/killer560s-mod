"""Compares the doors real Hypixel maps gave each room (his run history) with RoomDoors.of's measured doorways.

For each recorded room on each real map: the door sides it had (side, index along the side) in map orientation. The room's
rotation is not recorded, so the check is rotation-free: some quarter turn of the measured mask must contain every real
door. Prints, per room, the measured mask, the max real door count, and any run whose doors fit no turn.
usage: python tools/layoutsim/hyp_doors.py <instance>/config/killer560/dungeons/runs/killer560smod-runs/history.json doors.tsv
(doors.tsv: the DOORS lines of tools/layoutsim/run.sh -Ddoorstats=true [-Drooms=...] 1)
"""
import json
import sys
from collections import defaultdict

G = 11
N, E, S, W = 0, 1, 2, 3


def rotate(tx, tz, edges, turns):
    for _ in range(turns % 4):
        nxt = set()
        for side, idx in edges:
            if side == N:
                nxt.add((E, idx))
            elif side == E:
                nxt.add((S, tz - 1 - idx))
            elif side == S:
                nxt.add((W, idx))
            else:
                nxt.add((N, tz - 1 - idx))
        edges = nxt
        tx, tz = tz, tx
    return tx, tz, edges


def load_masks(path):
    masks = {}
    for line in open(path, encoding='utf-8'):
        parts = line.rstrip('\n').split('\t')
        name, typ, shape, dims, count, edges = parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]
        tx, tz = map(int, dims.split('x'))
        es = set()
        for e in edges.split():
            es.add(('NESW'.index(e[0]), int(e[1:])))
        masks[name] = (typ, shape, tx, tz, es)
    return masks


def main():
    hist = json.load(open(sys.argv[1], encoding='utf-8'))
    masks = load_masks(sys.argv[2])
    seen = defaultdict(list)  # name -> list of (ndoors, fits, doors)
    for run in hist['runs']:
        m = run.get('map')
        if not m or not m.get('rooms'):
            continue
        rc = [int(x) for x in m['roomCells'].strip(',').split(',')]
        dc = [int(x) for x in m['doorCells'].strip(',').split(',')]
        rooms = m['rooms']
        for ri, room in enumerate(rooms):
            cells = [(x, z) for z in range(0, G, 2) for x in range(0, G, 2) if rc[z * G + x] == ri]
            if not cells:
                continue
            xs = [c[0] // 2 for c in cells]
            zs = [c[1] // 2 for c in cells]
            ox, oz = min(xs), min(zs)
            tx, tz = max(xs) - ox + 1, max(zs) - oz + 1
            doors = set()
            for (x, z) in cells:
                for side, (dx, dz) in enumerate([(0, -1), (1, 0), (0, 1), (-1, 0)]):
                    bx, bz = x + dx, z + dz
                    ox2, oz2 = x + 2 * dx, z + 2 * dz
                    if not (0 <= bx < G and 0 <= bz < G):
                        continue
                    if dc[bz * G + bx] == 0:
                        continue
                    if 0 <= ox2 < G and 0 <= oz2 < G and rc[oz2 * G + ox2] == ri:
                        continue
                    idx = x // 2 - ox if side in (N, S) else z // 2 - oz
                    doors.add((side, idx))
            name = room['name']
            fits = None
            if name in masks:
                typ, shape, mx, mz, es = masks[name]
                fits = False
                for t in range(4):
                    rx, rz, re_ = rotate(mx, mz, es, t)
                    if (rx, rz) == (tx, tz) and doors <= re_:
                        fits = True
                        break
            seen[name].append((len(doors), fits, tuple(sorted(doors)), (tx, tz)))
    for name in sorted(seen):
        obs = seen[name]
        mk = masks.get(name)
        mtxt = '?' if mk is None else '%s %s %dx%d %d: %s' % (mk[0], mk[1], mk[2], mk[3], len(mk[4]),
                                                          ' '.join('NESW'[s] + str(i) for s, i in sorted(mk[4])))
        bad = [o for o in obs if o[1] is False]
        maxd = max(o[0] for o in obs)
        flag = ''
        if mk is not None and maxd > len(mk[4]):
            flag += ' MORE-DOORS-THAN-MEASURED'
        if bad:
            flag += ' FITS-NO-TURN x%d' % len(bad)
        print('%-20s seen %2d, real doors %s | measured %s%s' % (
            name, len(obs), sorted(set(o[0] for o in obs)), mtxt, flag))
        for o in bad[:3]:
            print('      real doors %s on a %dx%d' % (' '.join('NESW'[s] + str(i) for s, i in o[2]), o[3][0], o[3][1]))


main()
