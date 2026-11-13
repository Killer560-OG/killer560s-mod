"""Append a ship's changelog lines to src/main/resources/assets/killer560smod/whatsnew.json under a date (default today).

python tools/whatsnew-add.py "line one" "line two" [--date YYYY-MM-DD] [--summary TEXT] [--section Title]
Lines go in one section (default "More") of that date's update; a new date is added at the top. Run by the merge train.
"""
import argparse, datetime, json, pathlib

F = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/killer560smod/whatsnew.json"

ap = argparse.ArgumentParser()
ap.add_argument("lines", nargs="+")
ap.add_argument("--date", default=datetime.date.today().isoformat())
ap.add_argument("--summary", default="")
ap.add_argument("--section", default="More")
a = ap.parse_args()

data = json.loads(F.read_text(encoding="utf-8"))
up = next((u for u in data if u["date"] == a.date), None)
if up is None:
    up = {"date": a.date, "summary": a.summary or a.lines[0][:60], "sections": []}
    data.insert(0, up)
sec = next((s for s in up["sections"] if s["title"] == a.section), None)
if sec is None:
    sec = {"title": a.section, "blurb": "Changes in this update.", "items": []}
    up["sections"].append(sec)
for l in a.lines:
    if l not in sec["items"]:
        sec["items"].append(l)
F.write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
print(f"{a.date}: {len(sec['items'])} line(s) in {a.section}")
