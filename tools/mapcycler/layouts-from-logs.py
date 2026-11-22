"""Rebuild real dungeon layouts from Minecraft game logs (mapcycler-history-1121).

The live map logs every change of its layout as a "[MapLayout] snapshot retaken" line (rooms, same-room joins and door
types per cell of the 11x11 map grid). Replaying those diffs after each "entered The Catacombs" chat line gives every
floor the player saw: the pre-start state (the last snapshot before Mort's run-start line, what the Map Cycler judges)
and the final one. Read-only; nothing here touches the game.

Usage:
  python -I tools/mapcycler/layouts-from-logs.py OUT.jsonl LOG_OR_DIR [LOG_OR_DIR ...]
  python -I tools/mapcycler/layouts-from-logs.py --table OUT.jsonl   (prints mapcycler/RushOdds' COUNTS rows from OUT)
LOG_OR_DIR: a latest.log / *.log.gz file, or a logs folder. Write OUT outside the repository.

Each output line is one usable floor (full grid, every room reached by doors, an Entrance-to-Blood door path):
  floor, source, time, across, down, slots, gaps, names, spec {Entrance, Blood, Fairy}, true (rooms before Blood: the
  rooms on the door path not counting Entrance, Fairy and Blood), tree, early {slots, gaps, names, spec}.
Slots are room ids per room slot (z * across + x, -1 unseen); gaps two per slot (east, south): 0 unknown, 1 join,
2 door, 3 wall/edge, 4 wither or blood door (RushBounds' values). A summary goes to stderr.
"""
import gzip
import json
import os
import re
import sys
from collections import Counter, defaultdict

ROMAN = {"I": 1, "II": 2, "III": 3, "IV": 4, "V": 5, "VI": 6, "VII": 7}
ENTER = re.compile(r"\[CHAT\].*entered (MM )?The Catacombs, (Floor ([IVX]+)|Entrance)")
SNAP = re.compile(r"\[MapLayout\] snapshot retaken \(([^)]*)\): rooms=(\d+) doors normal=(\d+) wither=(\d+) blood=(\d+) "
                  r"entrance=(\d+) locked=(\d+) current=(-?\d+) changedCells=(\d+)(.*)$")
TOKEN = re.compile(r" (\d+),(\d+):(room|door) ")
MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
TIME = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\]")
SIZE = {0: (4, 4), 1: (4, 5), 2: (5, 5), 3: (5, 5), 4: (6, 5)}  # MapPainter.floorRooms


def secs(line):
    m = TIME.match(line)
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3)) if m else None


def apply(rooms, doors, rest):
    parts = TOKEN.split(rest)
    for i in range(1, len(parts) - 3, 4):
        key = parts[i] + "," + parts[i + 1]
        body = parts[i + 3].rstrip()
        if body.endswith(" ..."):
            body = body[:-4]
        now = body[3:] if body.startswith("-->") else body.split("->", 1)[-1]
        target = rooms if parts[i + 2] == "room" else doors
        if now in ("-", "0", ""):
            target.pop(key, None)
        else:
            target[key] = now


def runs_of(path):
    """Yields each real map generation: floor, time, early state, final state, door counts (theirs, ours)."""
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        lines = f.read().splitlines()
    rooms, doors = {}, {}
    floor = enter_t = run = None
    for line in lines:
        m = ENTER.search(line)
        if m:
            floor = "E" if m.group(2) == "Entrance" else ("M" if m.group(1) else "F") + str(ROMAN.get(m.group(3), 0))
            enter_t = secs(line)
            continue
        if MORT in line and run is not None and run["early"] is None:
            run["early"] = (dict(rooms), dict(doors))
            continue
        s = SNAP.search(line)
        if not s:
            continue
        summary = [int(s.group(k)) for k in range(2, 9)]
        if s.group(1) in ("new generation", "first capture") or summary[0] == 0:
            if run is not None and run["final"] is not None:
                yield run
            run = None
            rooms, doors = {}, {}
            if summary[0] == 0:
                continue
            t = secs(line)
            if floor is not None and enter_t is not None and t is not None and (t - enter_t) % 86400 <= 60:
                run = {"floor": floor, "time": line[1:9], "early": None, "final": None}
            floor = None
        if run is None:
            continue
        apply(rooms, doors, s.group(10))
        kinds = Counter(v[0] for v in doors.values())
        run["final"] = (dict(rooms), dict(doors))
        run["counts_ok"] = [kinds["1"], kinds["2"], kinds["3"], kinds["4"]] == summary[1:5]
    if run is not None and run["final"] is not None:
        yield run


def build(state, across, down):
    rooms, doors = state
    n = across * down
    parent = list(range(n))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def cell(s):
        return rooms.get(f"{2 * (s % across)},{2 * (s // across)}")

    gaps = [3] * (2 * n)
    for s in range(n):
        x, z = s % across, s // across
        for d in range(2):
            t = (s + 1 if x + 1 < across else -1) if d == 0 else (s + across if z + 1 < down else -1)
            if t < 0:
                continue
            key = f"{2 * x + (1 - d)},{2 * z + d}"
            gaps[2 * s + d] = 1 if key in rooms else (4 if doors[key][0] in "23" else 2) if key in doors else 0
            if key in rooms and cell(s) and cell(t):
                parent[find(s)] = find(t)
    ids, slots, names = {}, [], []
    for s in range(n):
        if cell(s) is None:
            slots.append(-1)
            continue
        r = find(s)
        if r not in ids:
            ids[r] = len(names)
            names.append(None)
        slots.append(ids[r])
        if cell(s) != "Unknown":
            names[ids[r]] = cell(s)
    spec = {k: (names.index(k) if k in names else -1) for k in ("Entrance", "Blood", "Fairy")}
    return {"slots": slots, "gaps": gaps, "names": names, "spec": spec}


def blood_path(lay, across, down):
    """(rooms before Blood, tree?) through the doors, or None when the doors do not join every room to the Entrance."""
    slots, gaps, spec = lay["slots"], lay["gaps"], lay["spec"]
    e, b, f = spec["Entrance"], spec["Blood"], spec["Fairy"]
    if e < 0 or b < 0 or -1 in slots:
        return None
    adj = defaultdict(set)
    for s in range(across * down):
        for d in range(2):
            t = s + 1 if d == 0 else s + across
            if gaps[2 * s + d] in (2, 4) and slots[s] != slots[t]:
                adj[slots[s]].add(slots[t])
                adj[slots[t]].add(slots[s])
    prev = {e: None}
    queue = [e]
    for v in queue:
        for w in adj[v]:
            if w not in prev:
                prev[w] = v
                queue.append(w)
    if len(prev) != len(lay["names"]):
        return None
    path, v = [], b
    while v is not None:
        path.append(v)
        v = prev[v]
    edges = sum(len(a) for a in adj.values()) // 2
    return sum(1 for r in path if r not in (e, b, f)), edges == len(prev) - 1


def table(path):
    """RushOdds.COUNTS: per wither/blood doors seen before the start (2..7, clamped), F7 floors by rooms before Blood."""
    rows = [[0] * 11 for _ in range(6)]
    for line in open(path, encoding="utf-8"):
        x = json.loads(line)
        if x["floor"] == "F7" and x["true"] <= 10:
            kd = sum(1 for g in x["early"]["gaps"] if g == 4)
            rows[min(max(kd, 2), 7) - 2][x["true"]] += 1
    print(f"    // {sum(map(sum, rows))} F7 floors")
    for i, r in enumerate(rows):
        print(f"            {{{', '.join(map(str, r))}}}, // {i + 2}{'+' if i == 5 else ''} wither/blood door(s) seen")


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--table":
        table(sys.argv[2])
        return
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    files = []
    for p in sys.argv[2:]:
        if os.path.isdir(p):
            files += [os.path.join(p, n) for n in sorted(os.listdir(p)) if n.endswith(".log.gz") or n == "latest.log"]
        else:
            files.append(p)
    stat, per_floor = Counter(), defaultdict(Counter)
    with open(sys.argv[1], "w", encoding="utf-8", newline="\n") as out:
        for path in files:
            try:
                runs = list(runs_of(path))
            except (OSError, EOFError) as ex:
                print("skipped", path, ex, file=sys.stderr)
                continue
            for run in runs:
                stat["real floors"] += 1
                d = 0 if run["floor"] == "E" else int(run["floor"][1:])
                across, down = SIZE.get(d, (6, 6))
                lay = build(run["final"], across, down)
                res = blood_path(lay, across, down) if run["counts_ok"] else None
                if res is None or run["early"] is None:
                    stat["dropped (cut log line, part of the grid unseen, or no pre-start state)"] += 1
                    continue
                if not res[1]:
                    # Doors always form a tree (killer560 2026-11-21): a map that does not was read before it was drawn.
                    stat["dropped (doors not a tree: a half-drawn map)"] += 1
                    continue
                stat["usable"] += 1
                per_floor[run["floor"]][res[0]] += 1
                rec = dict(floor=run["floor"], source=os.path.basename(path), time=run["time"], across=across, down=down,
                           true=res[0], tree=res[1], early=build(run["early"], across, down), **lay)
                out.write(json.dumps(rec) + "\n")
    for k, v in stat.items():
        print(f"{k}: {v}", file=sys.stderr)
    for fl in sorted(per_floor):
        c = per_floor[fl]
        print(f"{fl}: {sum(c.values())} floors, rooms before Blood " + " ".join(f"{k}:{c[k]}" for k in sorted(c)),
              file=sys.stderr)


main()
