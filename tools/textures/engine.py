"""A tiny pixel-art engine for Pack Disabler's own item textures.

Every texture is a 16x16 grid of MATERIAL letters (one char per pixel, '.' transparent). The engine shades it the way
vanilla item art is shaded, so the grids only have to say what each pixel is made of:

  * an edge pixel that touches transparency is the outline: dark, and darker on its bottom/right side;
  * an inner pixel whose upper/left neighbour is another material is lit, whose lower/right neighbour is another
    material is in shadow, otherwise mid tone;
  * an UPPERCASE letter forces that material's highlight (a glint, a rim of light);
  * a material can be 'flat' (no inner shading: tiny details) or carry its own alpha (glass, water).

All art here is original. Nothing is copied, traced or recoloured from Hypixel's pack or from vanilla textures; those
were only looked at for the general style (16x16, soft shading, darker lower outline).
"""
import colorsys

SIZE = 16


def _clamp(v):
    return max(0, min(255, int(round(v))))


def hex_rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def shade(rgb, light, sat=1.0, hue_shift=0.0):
    """Lighten (light > 0) or darken (light < 0) in HLS, with a small hue shift (shadows lean cool, lights warm)."""
    r, g, b = (c / 255.0 for c in rgb)
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    l = max(0.0, min(1.0, l + light * (1 - l if light > 0 else l)))
    s = max(0.0, min(1.0, s * sat))
    h = (h + hue_shift) % 1.0
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    return (_clamp(r * 255), _clamp(g * 255), _clamp(b * 255))


class Material:
    def __init__(self, base, alpha=255, flat=False, outline=None, outer=False):
        self.base = hex_rgb(base) if isinstance(base, str) else base
        self.alpha = alpha
        self.flat = flat
        # outer: thin parts (ring bands, chains, straps). Their outline is drawn on the transparent pixels around
        # them, so a two-pixel band keeps its own lit and shaded tones instead of turning into all outline.
        self.outer = outer
        b = self.base
        self.ramp = {
            'hi': shade(b, 0.45, 0.85, -0.01),
            'light': shade(b, 0.20, 0.95, -0.005),
            'mid': b,
            'shadow': shade(b, -0.22, 1.05, 0.01),
            'out_top': shade(hex_rgb(outline), 0, 1) if outline else shade(b, -0.50, 1.0, 0.015),
            'out_bot': shade(hex_rgb(outline), -0.25, 1) if outline else shade(b, -0.66, 1.0, 0.02),
        }


def render(grid, palette):
    """grid: 16 strings of 16 chars; palette: letter -> Material. Returns a 16x16 list of RGBA tuples."""
    rows = [r for r in grid]
    if len(rows) != SIZE or any(len(r) != SIZE for r in rows):
        raise ValueError('grid must be 16x16, got %s' % [len(r) for r in rows])

    def mat_at(x, y):
        if x < 0 or y < 0 or x >= SIZE or y >= SIZE:
            return None
        c = rows[y][x]
        return None if c == '.' else c.lower()

    out = [[(0, 0, 0, 0)] * SIZE for _ in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            c = rows[y][x]
            if c == '.':
                continue
            key = c.lower()
            if key not in palette:
                raise KeyError('no material %r' % c)
            m = palette[key]
            up, left, down, right = mat_at(x, y - 1), mat_at(x - 1, y), mat_at(x, y + 1), mat_at(x + 1, y)
            if c.isupper():
                tone = 'hi'
            elif m.flat:
                tone = 'mid'
            elif m.outer:
                if up != key or left != key:
                    tone = 'light'
                elif down != key or right != key:
                    tone = 'shadow'
                else:
                    tone = 'mid'
            elif down is None or right is None:
                tone = 'out_bot'
            elif up is None or left is None:
                tone = 'out_top'
            elif up != key or left != key:
                tone = 'light'
            elif down != key or right != key:
                tone = 'shadow'
            else:
                tone = 'mid'
            out[y][x] = m.ramp[tone] + (m.alpha,)
    # Outer outlines: a transparent pixel beside an 'outer' material takes that material's outline, darker when it
    # sits below or right of the part (the lower outline).
    for y in range(SIZE):
        for x in range(SIZE):
            if rows[y][x] != '.':
                continue
            best = None
            for dx, dy, lower in ((0, -1, True), (-1, 0, True), (0, 1, False), (1, 0, False)):
                k = mat_at(x + dx, y + dy)
                if k is not None and k in palette and palette[k].outer:
                    if best is None or lower:
                        best = (palette[k], lower)
            if best:
                m, lower = best
                out[y][x] = m.ramp['out_bot' if lower else 'out_top'] + (255,)
    return out


def overlay(grid, stamp, ox, oy):
    """Return grid with stamp's non-'.' chars written at (ox, oy)."""
    rows = [list(r) for r in grid]
    for dy, line in enumerate(stamp):
        for dx, ch in enumerate(line):
            if ch != '.':
                rows[oy + dy][ox + dx] = ch
    return [''.join(r) for r in rows]


def fill(grid, src, dst):
    return [r.replace(src, dst) for r in grid]
