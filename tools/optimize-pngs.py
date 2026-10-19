"""Lossless PNG recompressor for src/main/resources (release-jar size work, 2026-10-19).

Re-encodes each given PNG with a per-row adaptive filter (minimum sum of absolute differences) and zopfli
deflate (falls back to zlib level 9 if the zopfli package is missing). The decoded pixels are verified to be
identical before a file is replaced, and a file is only replaced when the result is smaller.

Needs: pip install pillow numpy [zopfli]
Usage: python tools/optimize-pngs.py [--iterations N] [--dry-run] FILE_OR_DIR...
Colour type is kept as it is (RGB stays RGB, RGBA stays RGBA); no pixel value is ever changed, including the
colour under fully transparent pixels.
"""
import argparse
import io
import os
import struct
import sys
import zlib

import numpy as np
from PIL import Image

try:
    import zopfli.zlib as zopfli_zlib
except ImportError:  # pragma: no cover
    zopfli_zlib = None

SIG = b"\x89PNG\r\n\x1a\n"


def chunk(tag, data):
    c = struct.pack(">I", len(data)) + tag + data
    return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def paeth(a, b, c):
    p = a.astype(np.int16) + b - c
    pa = np.abs(p - a)
    pb = np.abs(p - b)
    pc = np.abs(p - c)
    return np.where((pa <= pb) & (pa <= pc), a, np.where(pb <= pc, b, c)).astype(np.uint8)


def filter_rows(arr, bpp):
    """arr: (h, rowbytes) uint8. Returns the filtered stream with a filter byte per row."""
    h, n = arr.shape
    out = bytearray()
    prev = np.zeros(n, dtype=np.uint8)
    for y in range(h):
        row = arr[y]
        left = np.concatenate([np.zeros(bpp, dtype=np.uint8), row[:-bpp]])
        upleft = np.concatenate([np.zeros(bpp, dtype=np.uint8), prev[:-bpp]])
        cands = [
            row,
            (row - left).astype(np.uint8),
            (row - prev).astype(np.uint8),
            (row - ((left.astype(np.uint16) + prev) >> 1).astype(np.uint8)).astype(np.uint8),
            (row - paeth(left, prev, upleft)).astype(np.uint8),
        ]
        best, best_cost = 0, None
        for i, c in enumerate(cands):
            s = c.astype(np.int8).astype(np.int16)
            cost = int(np.abs(s).sum())
            if best_cost is None or cost < best_cost:
                best, best_cost = i, cost
        out.append(best)
        out += cands[best].tobytes()
        prev = row
    return bytes(out)


def encode(im, iterations):
    mode = im.mode
    if mode not in ("RGB", "RGBA", "L", "LA"):
        return None
    arr = np.asarray(im)
    h, w = arr.shape[:2]
    channels = 1 if arr.ndim == 2 else arr.shape[2]
    ctype = {1: 0, 2: 4, 3: 2, 4: 6}[channels]
    raw = filter_rows(arr.reshape(h, w * channels), channels)
    if zopfli_zlib is not None:
        comp = zopfli_zlib.compress(raw, numiterations=iterations)
    else:
        comp = zlib.compress(raw, 9)
    ihdr = struct.pack(">IIBBBBB", w, h, 8, ctype, 0, 0, 0)
    return SIG + chunk(b"IHDR", ihdr) + chunk(b"IDAT", comp) + chunk(b"IEND", b"")


def same_pixels(a_bytes, b_bytes):
    a = Image.open(io.BytesIO(a_bytes))
    b = Image.open(io.BytesIO(b_bytes))
    return a.mode == b.mode and a.size == b.size and a.tobytes() == b.tobytes()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--iterations", type=int, default=15)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    files = []
    for p in args.paths:
        if os.path.isdir(p):
            for d, _, names in os.walk(p):
                files += [os.path.join(d, n) for n in names if n.lower().endswith(".png")]
        else:
            files.append(p)
    total_before = total_after = 0
    for f in sorted(files):
        data = open(f, "rb").read()
        im = Image.open(io.BytesIO(data))
        im.load()
        new = encode(im, args.iterations)
        if new is None:
            print(f"skip (mode {im.mode}): {f}")
            continue
        if not same_pixels(data, new):
            print(f"MISMATCH, not replaced: {f}")
            continue
        total_before += len(data)
        if len(new) < len(data):
            total_after += len(new)
            print(f"{len(data)} -> {len(new)}  {f}")
            if not args.dry_run:
                with open(f, "wb") as o:
                    o.write(new)
        else:
            total_after += len(data)
            print(f"{len(data)} kept  {f}")
    print(f"total {total_before} -> {total_after}")


if __name__ == "__main__":
    sys.exit(main())
