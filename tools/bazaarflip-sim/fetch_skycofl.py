"""Fetch Bazaar price history from SkyCofl and convert it into FlipSim snapshot files.

Source: SkyCofl (https://sky.coflnet.com), free history endpoints
  GET https://sky.coflnet.com/api/bazaar/{tag}/history?start=...&end=...
Attribution (required by SkyCofl): "Prices provided by SkyCofl" - https://sky.coflnet.com/data
Rate limit: the docs ask for about one request per second on detailed endpoints; this script waits 1.2 s between calls.

Output: one JSON file in the format FlipSim reads ({"snapshots": [...], "npcPrices": {...}}).
Caveat: the free history is a summary per point (best buy and sell, min and max, volumes, weekly volume), not a full
order book. The converter fills one book level per side from those summaries, so depth-sensitive results are approximate.

Usage (from the repo root):
  python tools/bazaarflip-sim/fetch_skycofl.py --start 2026-09-01T00:00:00Z --end 2026-10-01T00:00:00Z --top 40 --out OUT_DIR
"""

import argparse
import datetime as dt
import json
import os
import time
import urllib.parse
import urllib.request

BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar"
ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items"
HISTORY_URL = "https://sky.coflnet.com/api/bazaar/{tag}/history"
UA = "killer560s-mod-flipsim/1.0 (offline simulation; contact via the mod's GitHub)"


def get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def top_products(n):
    """The n Bazaar products with the highest weekly volume, from the live Hypixel snapshot (one request)."""
    data = get_json(BAZAAR_URL)
    rows = []
    for tag, p in data["products"].items():
        q = p.get("quick_status", {})
        rows.append((q.get("buyMovingWeek", 0) + q.get("sellMovingWeek", 0), tag))
    rows.sort(reverse=True)
    return [tag for _, tag in rows[:n]]


def npc_prices():
    """NPC sell prices from Hypixel's item list (one request)."""
    out = {}
    for item in get_json(ITEMS_URL).get("items", []):
        price = item.get("npc_sell_price")
        if price is not None and price > 0:
            out[item["id"]] = price
    return out


def fetch_history(tag, start, end):
    qs = urllib.parse.urlencode({"start": start, "end": end})
    return get_json(HISTORY_URL.format(tag=tag) + "?" + qs)


def to_ms(stamp):
    # SkyCofl timestamps are local-looking ISO strings without a zone; treat them as UTC.
    t = dt.datetime.fromisoformat(stamp)
    if t.tzinfo is None:
        t = t.replace(tzinfo=dt.timezone.utc)
    return int(t.timestamp() * 1000)


def level(price, amount):
    return [{"amount": int(amount), "pricePerUnit": float(price), "orders": 1}] if price and amount else []


def snapshot_from_point(tag, p):
    """One FlipSim snapshot for one product at one history point, in Hypixel's raw product shape."""
    return {
        "quick_status": {
            "productId": tag,
            "buyPrice": p["buy"],
            "sellPrice": p["sell"],
            "buyVolume": p.get("buyVolume", 0),
            "sellVolume": p.get("sellVolume", 0),
            "buyMovingWeek": p.get("buyMovingWeek", 0),
            "sellMovingWeek": p.get("sellMovingWeek", 0),
            "buyOrders": 1,
            "sellOrders": 1,
        },
        # Hypixel's sell_summary is the buy orders (best first); buy_summary is the sell offers (cheapest first).
        "sell_summary": level(p["sell"], max(1, p.get("sellVolume", 0) // 20)),
        "buy_summary": level(p["buy"], max(1, p.get("buyVolume", 0) // 20)),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", required=True)
    ap.add_argument("--end", required=True)
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    tags = top_products(args.top)
    time.sleep(1.2)
    npc = npc_prices()
    time.sleep(1.2)

    per_product = {}
    for tag in tags:
        try:
            points = fetch_history(tag, args.start, args.end)
        except Exception as e:  # keep going; a missing product is reported, not hidden
            print(f"skip {tag}: {e}")
            time.sleep(1.2)
            continue
        per_product[tag] = sorted(
            ((to_ms(p["timestamp"]), p) for p in points
             if "timestamp" in p and "buy" in p and "sell" in p and p["buy"] and p["sell"]), key=lambda x: x[0])
        print(f"{tag}: {len(points)} points")
        time.sleep(1.2)

    # One snapshot per timestamp across all products (FlipSim replays the whole market at each step).
    stamps = sorted({t for pts in per_product.values() for t, _ in pts})
    lookup = {tag: {t: p for t, p in pts} for tag, pts in per_product.items()}
    snapshots = []
    for t in stamps:
        products = {}
        for tag in per_product:
            p = lookup[tag].get(t)
            if p is not None:
                products[tag] = snapshot_from_point(tag, p)
        if products:
            snapshots.append({"lastUpdated": t, "products": products})

    out_file = os.path.join(args.out, f"skycofl-{args.start[:10]}-{args.end[:10]}.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump({"snapshots": snapshots, "npcPrices": {k: v for k, v in npc.items() if k in per_product}}, f)
    print(f"wrote {len(snapshots)} snapshot(s) for {len(per_product)} product(s) to {out_file}")


if __name__ == "__main__":
    main()
