"""Generates the pinned fixture the testkit's Blood Rush shared-table cases compare against.

usage: python -I tools/bloodrush/gen-br-fixture.py BOXES.json PLANS.json OUT.txt

It is a second, simple implementation of the table's rules written straight from the source files (not from br-data.json),
so a bug in build-br-data.py or BloodRushData shows up as a difference. Lines:
  B|room|n|x1,y1,z1,x2,y2,z2            box n (1 up) of a room
  P|room|master|doorer|dx|dz|wx|wz|n|expected   the plan for a room entered at (dx, dz) (doorer: wither door wx, wz)
  M|room                                a miniboss room
expected is "roles|stacks": roles and stacks each ';' between roles and ',' between boxes, or "none".
Probes per table door: the door itself, the door moved (+2,+1) and (-1,+2), and (+9,+9), each for every team size 2..4,
in normal and master mode (master tries the master table first).
"""
import json
import sys

REACH = 3


class Tie(Exception):
    pass


def room_key(name):
    return name.replace("-", " ")


def fmt(plan, n):
    roles = ";".join(",".join(str(b) for b in r) for r in plan["roles"])
    stacks = plan.get("stacks") or []
    if not any(stacks):
        stacks = [[] for _ in plan["roles"]]
    return roles + "|" + ";".join(",".join(str(b) for b in r) for r in stacks)


def doors_of(table, room, doorer):
    out = []
    for key, counts in table.get(room, {}).items():
        if doorer:
            a, w = key.split(">")
            ax, az = map(int, a.split(","))
            wx, wz = map(int, w.split(","))
        else:
            ax, az = map(int, key.split(","))
            wx = wz = 0
        out.append((ax, az, wx, wz, counts))
    return out


def pick(doors, dx, dz, wither, n):
    best = None
    best_d = None
    tie = False
    for ax, az, wx, wz, counts in doors:
        e = abs(ax - dx) + abs(az - dz)
        w = 0 if wither is None else abs(wx - wither[0]) + abs(wz - wither[1])
        if best_d is not None and e + w == best_d:
            tie = True  # which of two equal doors wins is not defined: such a probe is left out
        elif best_d is None or e + w < best_d:
            best_d = e + w
            best = (e, w, counts)
            tie = False
    if tie:
        raise Tie()
    if best is None or best[0] > REACH or best[1] > REACH:
        return None
    return best[2].get(str(n))


def expect(rows, m7, doorer_rows, doorer_m7, room, master, doorer, dx, dz, wither, n):
    if doorer:
        if wither is None:
            return None
        if master:
            p = pick(doors_of(doorer_m7, room, True), dx, dz, wither, n)
            if p:
                return p
        return pick(doors_of(doorer_rows, room, True), dx, dz, wither, n)
    if master:
        p = pick(doors_of(m7, room, False), dx, dz, None, n)
        if p:
            return p
    return pick(doors_of(rows, room, False), dx, dz, None, n)


def main():
    boxes = json.load(open(sys.argv[1], encoding="utf-8"))["rooms"]
    roles = json.load(open(sys.argv[2], encoding="utf-8"))
    rows = {room_key(k): v for k, v in roles["rooms"].items()}
    m7 = {room_key(k): v for k, v in roles["m7"].items()}
    drows = {room_key(k): v for k, v in roles.get("doorer", {}).items()}
    dm7 = {room_key(k): v for k, v in roles.get("doorerM7", {}).items()}
    lines = []
    for room in sorted(room_key(k) for k in boxes):
        lst = sorted(boxes[[k for k in boxes if room_key(k) == room][0]], key=lambda x: x[6])
        for i, b in enumerate(lst, 1):
            lines.append("B|%s|%d|%s" % (room, i, ",".join(str(v) for v in b[:6])))
    for room in sorted(room_key(k) for k in roles["mini"]):
        lines.append("M|" + room)
    probes = set()
    for table, doorer in ((rows, False), (m7, False), (drows, True), (dm7, True)):
        for room, doors in table.items():
            for key in doors:
                a = key.split(">")[0] if doorer else key
                ax, az = map(int, a.split(","))
                wither = None
                if doorer:
                    wither = tuple(map(int, key.split(">")[1].split(",")))
                for ox, oz in ((0, 0), (2, 1), (-1, 2), (9, 9)):
                    probes.add((room, doorer, ax + ox, az + oz, wither))
    for room, doorer, dx, dz, wither in sorted(probes, key=lambda p: (p[0], p[1], p[2], p[3], str(p[4]))):
        for master in (False, True):
            for n in (2, 3, 4):
                try:
                    p = expect(rows, m7, drows, dm7, room, master, doorer, dx, dz, wither, n)
                except Tie:
                    continue
                lines.append("P|%s|%d|%d|%d|%d|%s|%s|%d|%s" % (
                    room, master, doorer, dx, dz, wither[0] if wither else "-", wither[1] if wither else "-", n,
                    fmt(p, n) if p else "none"))
    data = ("\n".join(lines) + "\n").encode("utf-8")
    if sys.argv[3].endswith(".gz"):
        import gzip
        data = gzip.compress(data, 9, mtime=0)
    with open(sys.argv[3], "wb") as f:
        f.write(data)
    print("wrote", sys.argv[3], len(lines), "lines")


main()
