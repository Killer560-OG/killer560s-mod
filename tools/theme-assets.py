"""Builds the main menu theme assets (themedl-1104, killer560 2026-11-04: "jar ships tiny previews ... full one downloads").

The full pictures and theme music live in theme-assets/ at the repo root, NOT in the jar. This script:
  1. writes each picture's in-jar preview to src/main/resources/assets/killer560smod/textures/gui/<name>.webp: lossy, a
     quarter of the size each way, except the first-boot default theme's (Amber's aurora), which ships at full size and
     medium quality (about 1 MB) so a fresh install looks right before anything downloads;
  2. writes the manifest the mod reads, src/main/resources/assets/killer560smod/theme-assets.json: the release tag and,
     for every file in theme-assets/, its name, sha256 and size;
  3. copies every file of theme-assets/ into build/theme-assets/ - the files to attach to that GitHub release.

Run it after changing anything in theme-assets/, bump RELEASE when a file changes (a release's files never change once
uploaded: a cached copy is named by its sha256), and upload build/theme-assets/* to the release named RELEASE (a
PRE-RELEASE, so Auto Update's /releases/latest keeps finding the mod's own release). Needs Pillow with WebP.

    python tools/theme-assets.py [--check]   # --check: only verify the manifest and previews are current (exit 1 if not)
"""
import hashlib
import io
import json
import shutil
import sys
from pathlib import Path

from PIL import Image

RELEASE = "theme-assets-1"
ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "theme-assets"
RES = ROOT / "src/main/resources/assets/killer560smod"
OUT = ROOT / "build/theme-assets"
# The default theme's picture (MenuConfig's default AMBER, Fancy, Amber's AURORA scene): full size, medium quality.
MEDIUM = {"amber_aurora": 95}
PREVIEW_DIV = 4
PREVIEW_QUALITY = 75


def preview_bytes(path):
    im = Image.open(path)
    im.load()
    quality = MEDIUM.get(path.stem)
    if quality is None:
        im = im.resize((im.width // PREVIEW_DIV, im.height // PREVIEW_DIV), Image.LANCZOS)
        quality = PREVIEW_QUALITY
    buf = io.BytesIO()
    # exact keeps the colour under transparent pixels: the alpha channel is a shader map, not transparency.
    # method 5, never 6: TwelveMonkeys 3.12.0 (the mod's decoder) throws "Coordinate out of bounds!" in VP8LDecoder.decodeBwRef
    # on the lossless alpha that method 6 writes for the full-size Amber picture (Pillow and browsers read it fine).
    im.save(buf, "WEBP", quality=quality, method=5, alpha_quality=100, exact=True)
    return buf.getvalue()


def main():
    check = "--check" in sys.argv[1:]
    files = sorted(p for p in SRC.iterdir() if p.is_file() and p.suffix in (".webp", ".ogg", ".bin"))
    assets = [{"name": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest(), "size": p.stat().st_size} for p in files]
    manifest = json.dumps({"release": RELEASE, "assets": assets}, indent=2) + "\n"
    stale = []
    target = RES / "theme-assets.json"
    if not target.exists() or target.read_text(encoding="utf-8") != manifest:
        stale.append(target)
        if not check:
            target.write_text(manifest, encoding="utf-8", newline="\n")
    for p in files:
        if p.suffix != ".webp":
            continue
        out = RES / "textures/gui" / p.name
        if check:
            if not out.exists():
                stale.append(out)
            continue
        data = preview_bytes(p)
        if not out.exists() or out.read_bytes() != data:
            out.write_bytes(data)
        print(f"preview {out.relative_to(ROOT)}: {len(data):,} bytes")
    if check:
        for s in stale:
            print(f"STALE {s.relative_to(ROOT)}: run python tools/theme-assets.py")
        sys.exit(1 if stale else 0)
    OUT.mkdir(parents=True, exist_ok=True)
    for p in files:
        shutil.copyfile(p, OUT / p.name)
    print(f"manifest {target.relative_to(ROOT)} ({len(assets)} assets, release {RELEASE})")
    print(f"upload to the {RELEASE} pre-release: " + ", ".join(f"{a['name']} ({a['size']:,})" for a in assets))


if __name__ == "__main__":
    main()
