"""Builds assets/killer560smod/bloodrush/br-data.json, the shared blood rush box and role table, from two source JSONs.

usage: python -I tools/bloodrush/build-br-data.py BOXES.json PLANS.json [OUT.json]

The jar copy is a versioned snapshot. When the shared source offers an API, only BloodRushData.load() changes; this
script then only matters for refreshing the bundled fallback.

BOXES.json: {"rooms": {room: [[x1,y1,z1,x2,y2,z2,id], ...]}}   (the box number is the place in the room's list)
PLANS.json: {"rooms": {room: {"x,z": {"2": {"roles": [[..]], "stacks": [[..]]}}}}, "m7": same, "doorer"/"doorerM7":
            {room: {"x,z>x,z": {...}}}, "mini": {room: true}}

Output keys: v (snapshot date), b (boxes by room, sorted by id, id dropped), p / m (normal / master plans), d / dm (door
runner plans), mini (rooms where every role kills every box). A plan is {count: [roles, stacks]}, stacks left out
when every one is empty. Room names are the room database's own (a hyphen in a source name becomes a space).
"""
import json
import sys


def name(room):
    return room.replace("-", " ")


def plans(table):
    out = {}
    for room, doors in table.items():
        od = {}
        for door, counts in doors.items():
            oc = {}
            for n, plan in counts.items():
                entry = [plan["roles"]]
                if any(plan.get("stacks", [])):
                    entry.append(plan["stacks"])
                oc[n] = entry
            od[door] = oc
        out[name(room)] = od
    return out


def main():
    boxes = json.load(open(sys.argv[1], encoding="utf-8"))
    roles = json.load(open(sys.argv[2], encoding="utf-8"))
    out_path = sys.argv[3] if len(sys.argv) > 3 else "src/main/resources/assets/killer560smod/bloodrush/br-data.json"
    b = {}
    for room, lst in boxes["rooms"].items():
        lst = sorted(lst, key=lambda x: x[6])
        if [x[6] for x in lst] != list(range(1, len(lst) + 1)):
            raise SystemExit("box ids not 1..n in " + room)
        b[name(room)] = [x[:6] for x in lst]
    data = {
        "v": "2026-11-03",
        "b": b,
        "p": plans(roles["rooms"]),
        "m": plans(roles["m7"]),
        "d": plans(roles.get("doorer", {})),
        "dm": plans(roles.get("doorerM7", {})),
        "mini": sorted(name(r) for r in roles["mini"]),
    }
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, separators=(",", ":"), sort_keys=True)
    print("wrote", out_path, sum(len(v) for v in b.values()), "boxes in", len(b), "rooms")


main()
