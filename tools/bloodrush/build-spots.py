"""Builds the Blood Rush Helper's shipped spawn spot table, and its coverage report, from our own data.

Usage: python -I tools/bloodrush/build-spots.py [FILE_OR_PRISM_INSTANCES_DIR ...]

Sources, all ours and all seen on Hypixel (the mod never learns in the sim):
- the current table (so a rebuild never loses a room);
- every learned spot file: each argument is a killer560smod-bloodrush-spots.json or a folder searched for them (a Prism
  instances folder: <instance>/minecraft/config/killer560/dungeons/runs/killer560smod-bloodrush-spots.json); with no
  argument %APPDATA%/PrismLauncher/instances;
- the shared record store's sim-mob-looks.json (%USERPROFILE%/.killer560smod/shared), where every instance records the
  room-relative block of each starred mob it confirmed on its spawn spot.
These are only the fallback for rooms the shared table (build-br-data.py) does not cover.

Spots are room-relative blocks (RouteCoords: 1xN rooms run along +x, every room starts at 0,0), the same in every file.
A spot is kept only in a room that can hold starred mobs (tools/bench/roomtypes.json: NORMAL, RARE, CHAMPION), inside the
room's footprint for its shape, and between the floor and roof heights of our capture of the room
(assets/killer560smod/rooms). Two spots within MERGE blocks on each axis are one spot (the higher seen count and its
position win, so a rebuild is idempotent). The game groups a room's spots into boxes itself (BloodRushHelperFeature.LINK).
Writes the table and tools/bloodrush/coverage.txt (which mob rooms have spots, which have none yet).
"""
import json
import os
import sys

MERGE = 2
MAX_PER_ROOM = 48
MOB_TYPES = ("NORMAL", "RARE", "CHAMPION")
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "killer560smod")
TABLE = os.path.join(ASSETS, "bloodrush", "spawn-spots.json")
CAPTURES = os.path.join(ASSETS, "rooms")
ROOM_TYPES = os.path.join(ROOT, "tools", "bench", "roomtypes.json")
COVERAGE = os.path.join(HERE, "coverage.txt")
NAME = "killer560smod-bloodrush-spots.json"
LOOKS = os.path.join(os.path.expanduser("~"), ".killer560smod", "shared", "sim-mob-looks.json")


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


def label(path):
    """Where a learned file came from: its Prism instance name, else the file name."""
    parts = os.path.normpath(path).split(os.sep)
    if "minecraft" in parts:
        return "learned on Hypixel, Prism instance '" + parts[parts.index("minecraft") - 1] + "'"
    return "learned on Hypixel, " + os.path.basename(path)


def footprint(shape):
    """Largest relative x and z inside a room of this shape (rooms are 31 blocks a tile, 32 apart)."""
    if shape in ("2x2", "L"):
        return 62, 62
    if shape.startswith("1x"):
        return 32 * int(shape[2:]) - 2, 30
    return 30, 30


def heights(name):
    """The capture's (minY, maxY), or None without a capture."""
    p = os.path.join(CAPTURES, name.replace(" ", "_") + ".json")
    if not os.path.isfile(p):
        return None
    with open(p, encoding="utf-8") as h:
        d = json.load(h)
    return d.get("minY"), d.get("maxY")


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


def main():
    with open(ROOM_TYPES, encoding="utf-8") as h:
        mob = {r["name"]: r["shape"] for r in json.load(h) if r["type"] in MOB_TYPES}
    ys = {n: heights(n) for n in mob}
    rooms, origin, dropped = {}, {}, {}

    def take(name, spot, src):
        why = None
        if name not in mob:
            why = "not a mob room"
        else:
            mx, mz = footprint(mob[name])
            h = ys[name]
            if not (0 <= spot[0] <= mx and 0 <= spot[2] <= mz):
                why = "outside the room"
            elif h and h[0] is not None and not (h[0] <= spot[1] <= h[1]):
                why = "outside the room's heights"
        if why:
            dropped[why] = dropped.get(why, 0) + 1
            return
        add(rooms, name, spot)
        if src:
            origin.setdefault(name, set()).add(src)

    sources = ([TABLE] if os.path.isfile(TABLE) else []) + list(files(sys.argv[1:]))
    for f in sources:
        with open(f, encoding="utf-8") as h:
            data = json.load(h)
        table = os.path.abspath(f) == os.path.abspath(TABLE)
        if table:
            for name, src in (data.get("sources") or {}).items():
                origin.setdefault(name, set()).update(x.strip() for x in src.split(";") if x.strip())
        n = 0
        for name, spots in (data.get("rooms") or {}).items():
            for s in spots:
                if isinstance(s, list) and len(s) >= 3:
                    take(name, [int(v) for v in s[:4]], None if table else label(f))
                    n += 1
        print(f"{f}: {n} spot(s)")
    if os.path.isfile(LOOKS):
        with open(LOOKS, encoding="utf-8") as h:
            mobs = json.load(h).get("mobs") or []
        for m in mobs:
            if all(k in m for k in ("room", "x", "y", "z")):
                take(m["room"], [int(m["x"]), int(m["y"]), int(m["z"]), int(m.get("seen", 1))],
                     "recorded on Hypixel, shared sim-mob-looks")
        print(f"{LOOKS}: {len(mobs)} mob(s)")
    # Only spots that passed the checks make a room, so a source line never names a room the table dropped.
    out = {"version": 1, "rooms": {k: sorted(v) for k, v in sorted(rooms.items()) if v}}
    out["sources"] = {k: "; ".join(sorted(origin.get(k, {"unknown"}))) for k in out["rooms"]}
    os.makedirs(os.path.dirname(TABLE), exist_ok=True)
    with open(TABLE, "w", encoding="utf-8", newline="\n") as h:
        json.dump(out, h, indent=1)
        h.write("\n")
    have = [n for n in sorted(mob) if n in out["rooms"]]
    none = [n for n in sorted(mob) if n not in out["rooms"]]
    with open(COVERAGE, "w", encoding="utf-8", newline="\n") as h:
        h.write("Blood Rush Helper shipped spawn spots: written by tools/bloodrush/build-spots.py, do not edit.\n")
        h.write(f"{len(have)} of {len(mob)} rooms that can hold starred mobs have spots; "
                f"{sum(len(v) for v in out['rooms'].values())} spots.\n\n")
        for n in have:
            h.write(f"{n} ({mob[n]}): {len(out['rooms'][n])}\n")
        h.write(f"\nNo spots yet ({len(none)}; boxes there come from the mobs seen in the run, and learning fills them):\n")
        for n in none:
            h.write(f"{n} ({mob[n]})\n")
    print(f"dropped: {dropped or 'none'}")
    print(f"table: {len(have)} of {len(mob)} mob rooms, {sum(len(v) for v in out['rooms'].values())} spot(s) -> {TABLE}")


if __name__ == "__main__":
    main()
