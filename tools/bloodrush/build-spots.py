"""Builds the Blood Rush Helper's shipped spawn spot table from learned spot files.

Usage: python -I tools/bloodrush/build-spots.py [FILE_OR_PRISM_INSTANCES_DIR ...]

Each argument is a killer560smod-bloodrush-spots.json, or a folder searched for them (a Prism instances folder:
<instance>/minecraft/config/killer560/dungeons/runs/killer560smod-bloodrush-spots.json). With no argument it reads
%APPDATA%/PrismLauncher/instances. The current table is merged in too, so a rebuild never loses a room.

Spots are room-relative blocks (RouteCoords), the same in every file. Two spots within MERGE blocks on each axis are one
spot (the higher seen count and its position win, so a rebuild is idempotent). Only spots learned on Hypixel (the mod never learns in the sim) and
never adm's data (All Rights Reserved).
"""
import json
import os
import sys

MERGE = 2
MAX_PER_ROOM = 48
HERE = os.path.dirname(os.path.abspath(__file__))
TABLE = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "killer560smod", "bloodrush",
                     "spawn-spots.json")
NAME = "killer560smod-bloodrush-spots.json"


def files(args):
    if not args:
        args = [os.path.join(os.environ.get("APPDATA", ""), "PrismLauncher", "instances")]
    for a in args:
        if os.path.isfile(a):
            yield a
        elif os.path.isdir(a):
            for inst in sorted(os.listdir(a)):
                p = os.path.join(a, inst, "minecraft", "config", "killer560", "dungeons", "runs", NAME)
                if os.path.isfile(p):
                    yield p


def add(rooms, name, s):
    lst = rooms.setdefault(name, [])
    seen = s[3] if len(s) > 3 else 1
    for t in lst:
        if all(abs(t[i] - s[i]) <= MERGE for i in range(3)):
            if seen > t[3]:
                t[0], t[1], t[2] = s[0], s[1], s[2]
            t[3] = max(t[3], seen)  # max, not sum: a rebuild reads the table AND the files it came from
            return
    if len(lst) < MAX_PER_ROOM:
        lst.append([s[0], s[1], s[2], seen])


def label(path):
    """Where a learned file came from: its Prism instance name, else the file name."""
    parts = os.path.normpath(path).split(os.sep)
    if "minecraft" in parts:
        return "learned on Hypixel, Prism instance '" + parts[parts.index("minecraft") - 1] + "'"
    return "learned on Hypixel, " + os.path.basename(path)


def main():
    rooms = {}
    origin = {}
    sources = []
    if os.path.isfile(TABLE):
        sources.append(TABLE)
    sources += list(files(sys.argv[1:]))
    for f in sources:
        with open(f, encoding="utf-8") as h:
            data = json.load(h)
        n = 0
        if os.path.abspath(f) == os.path.abspath(TABLE):
            for name, src in (data.get("sources") or {}).items():
                origin.setdefault(name, set()).update(x.strip() for x in src.split(";") if x.strip())
        for name, spots in (data.get("rooms") or {}).items():
            if os.path.abspath(f) != os.path.abspath(TABLE):
                origin.setdefault(name, set()).add(label(f))
            for s in spots:
                if isinstance(s, list) and len(s) >= 3:
                    add(rooms, name, [int(v) for v in s[:4]])
                    n += 1
        print(f"{f}: {n} spot(s)")
    out = {"version": 1, "rooms": {k: sorted(v) for k, v in sorted(rooms.items()) if v}}
    out["sources"] = {k: "; ".join(sorted(origin.get(k, {"unknown"}))) for k in out["rooms"]}
    os.makedirs(os.path.dirname(TABLE), exist_ok=True)
    with open(TABLE, "w", encoding="utf-8", newline="\n") as h:
        json.dump(out, h, indent=1)
        h.write("\n")
    print(f"table: {len(out['rooms'])} room(s), {sum(len(v) for v in out['rooms'].values())} spot(s) -> {TABLE}")


if __name__ == "__main__":
    main()
