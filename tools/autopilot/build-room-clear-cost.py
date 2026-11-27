"""Builds assets/killer560smod/autopilot/room-clear-cost.json: an estimated clear time in seconds for every mob room, from
where its mobs really spawn, not from its size (killer560 2026-11-26: "some 1x2s will take far longer than a 2x2 that only
has one boss mob in it ... Slime is awful ... Cathedral is very simple").

usage: python -I tools/autopilot/build-room-clear-cost.py [ROOM_COSTS.json] [OUT.json]

Inputs: the Blood Rush table bloodrush/br-data.json ("b": the mob pack boxes of each room, room-relative x1,y1,z1,x2,y2,z2;
"mini": miniboss rooms) and, for calibration, his learned Auto Clear times (the shared store's room-costs.json: per room
the last clear times "s"). Per room: packs (boxes), the walk through every pack (shortest nearest-neighbour tour over the
box centers, blocks), the height spread of the packs and the miniboss flag. Seconds = a + b*packs + c*walk/10 + d*height/10
+ e*miniboss, fitted by weighted least squares on the rooms he has times for (a coefficient below zero is dropped and
the fit redone), then blended with his own samples (each sample counts as much as PRIOR model points). The output is a few
kB: {"v": date, "fit": coefficients, "s": {room: seconds}}.
"""
import json
import math
import os
import sys

import numpy as np

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "killer560smod")
PRIOR = 2.0
FEATURES = ["const", "packs", "walk/10", "height/10", "mini"]


def center(b):
    return ((b[0] + b[3]) / 2.0, (b[1] + b[4]) / 2.0, (b[2] + b[5]) / 2.0)


def walk(points):
    if len(points) < 2:
        return 0.0
    best = math.inf
    for s in range(len(points)):
        left = list(range(len(points)))
        at = left.pop(s)
        total = 0.0
        while left:
            nxt = min(left, key=lambda j: math.dist(points[at], points[j]))
            total += math.dist(points[at], points[nxt])
            left.remove(nxt)
            at = nxt
        best = min(best, total)
    return best


def features(boxes, mini):
    pts = [center(b) for b in boxes]
    ys = [p[1] for p in pts] or [0.0]
    return [1.0, float(len(pts)), walk(pts) / 10.0, (max(ys) - min(ys)) / 10.0, 1.0 if mini else 0.0]


def fit(rows, ys, ws):
    keep = list(range(len(FEATURES)))
    while True:
        a = np.array([[r[k] for k in keep] for r in rows]) * np.sqrt(ws)[:, None]
        coef, *_ = np.linalg.lstsq(a, np.array(ys) * np.sqrt(ws), rcond=None)
        neg = [keep[i] for i, c in enumerate(coef) if c < 0 and keep[i] != 0]
        if not neg:
            out = [0.0] * len(FEATURES)
            for i, k in enumerate(keep):
                out[k] = float(coef[i])
            return out
        keep.remove(min(neg, key=lambda k: coef[keep.index(k)]))


def main():
    costs_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.expanduser("~"), ".killer560smod", "shared",
                                                                     "room-costs.json")
    out_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "autopilot", "room-clear-cost.json")
    br = json.load(open(os.path.join(ROOT, "bloodrush", "br-data.json"), encoding="utf-8"))
    learned = json.load(open(costs_path, encoding="utf-8")) if os.path.exists(costs_path) else {}
    mini = set(br["mini"])
    rooms = sorted(set(br["b"]) | mini)
    feats = {r: features(br["b"].get(r, []), r in mini) for r in rooms}
    rows, ys, ws = [], [], []
    for r in rooms:
        s = learned.get(r, {}).get("s") or []
        for v in s:
            rows.append(feats[r])
            ys.append(float(v))
            ws.append(1.0)
    coef = fit(rows, ys, np.array(ws))
    out = {}
    for r in rooms:
        model = max(0.8, sum(c * f for c, f in zip(coef, feats[r])))
        s = learned.get(r, {}).get("s") or []
        out[r] = round((model * PRIOR + sum(s)) / (PRIOR + len(s)), 1)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"v": "2026-11-26", "fit": dict(zip(FEATURES, [round(c, 3) for c in coef])), "s": out}, f,
                  separators=(",", ":"), sort_keys=True)
    ranked = sorted(out.items(), key=lambda kv: -kv[1])
    print("fit", dict(zip(FEATURES, [round(c, 3) for c in coef])), "rooms", len(out), "samples", len(ys))
    print("top", ranked[:10])
    print("bottom", ranked[-10:])
    for r in ("Slime", "Cathedral"):
        print(r, out.get(r), feats.get(r))


if __name__ == "__main__":
    main()
