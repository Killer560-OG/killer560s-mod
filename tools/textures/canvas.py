"""Shape helpers that build 16x16 material grids for engine.render. Coordinates are (x, y), y down."""

SIZE = 16


class Canvas:
    def __init__(self, rows=None):
        self.px = [list(r) for r in rows] if rows else [['.'] * SIZE for _ in range(SIZE)]

    def set(self, x, y, ch):
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.px[y][x] = ch

    def get(self, x, y):
        return self.px[y][x] if 0 <= x < SIZE and 0 <= y < SIZE else '.'

    def rect(self, x0, y0, x1, y1, ch):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, ch)
        return self

    def ellipse(self, cx, cy, rx, ry, ch):
        """Filled ellipse centred on (cx, cy) - half-integer centres give even widths."""
        for y in range(SIZE):
            for x in range(SIZE):
                dx = (x + 0.5 - cx) / rx
                dy = (y + 0.5 - cy) / ry
                if dx * dx + dy * dy <= 1.0:
                    self.set(x, y, ch)
        return self

    def ring(self, cx, cy, rx, ry, thick, ch):
        for y in range(SIZE):
            for x in range(SIZE):
                dx = (x + 0.5 - cx) / rx
                dy = (y + 0.5 - cy) / ry
                ix = (x + 0.5 - cx) / max(0.1, rx - thick)
                iy = (y + 0.5 - cy) / max(0.1, ry - thick)
                if dx * dx + dy * dy <= 1.0 and ix * ix + iy * iy > 1.0:
                    self.set(x, y, ch)
        return self

    def line(self, x0, y0, x1, y1, ch, width=1):
        steps = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(steps + 1):
            x = round(x0 + (x1 - x0) * i / steps)
            y = round(y0 + (y1 - y0) * i / steps)
            for w in range(width):
                self.set(x + (w if abs(x1 - x0) < abs(y1 - y0) else 0), y + (w if abs(x1 - x0) >= abs(y1 - y0) else 0), ch)
        return self

    def stamp(self, rows, ox, oy):
        for dy, line in enumerate(rows):
            for dx, ch in enumerate(line):
                if ch != '.':
                    self.set(ox + dx, oy + dy, ch)
        return self

    def replace(self, src, dst):
        for y in range(SIZE):
            for x in range(SIZE):
                if self.px[y][x] == src:
                    self.px[y][x] = dst
        return self

    def grid(self):
        return [''.join(r) for r in self.px]
