import sys
from capture_decode import load, at

TILE = 31

def tiles(size):
    return max(1, (size - 1) // (TILE + 1))

def rotate(x, y, z, deg):
    n = ((deg % 360) + 360) % 360
    if n == 90:  return (z, y, -x)
    if n == 180: return (-x, y, -z)
    if n == 270: return (-z, y, x)
    return (x, y, z)

def rotate_local(x, z, sx, sz, deg):
    if deg == 0:   return (x, z)
    if deg == 90:  return (sz - 1 - z, x)
    if deg == 180: return (sx - 1 - x, sz - 1 - z)
    if deg == 270: return (z, sx - 1 - x)
    raise ValueError

class Room:
    def __init__(self, path):
        self.r = load(path)

    def dbrel_to_local(self, rx, ry, rz, paste, dbrot):
        r = self.r
        sx, sz, margin = r['sx'], r['sz'], r['margin']
        tx = tiles(sz) if paste in (90, 270) else tiles(sx)
        tz = tiles(sx) if paste in (90, 270) else tiles(sz)
        minX = minZ = -(TILE // 2)
        maxX = minX + tx * (TILE + 1) - 2
        maxZ = minZ + tz * (TILE + 1) - 2
        clayX = maxX if dbrot in (90, 180) else minX
        clayZ = maxZ if dbrot in (180, 270) else minZ
        wx, wy, wz = rotate(rx, ry, rz, -dbrot)
        wx += clayX; wz += clayZ
        tgt = (wx - (minX - margin), wz - (minZ - margin))
        for cx in range(sx):
            for cz in range(sz):
                if rotate_local(cx, cz, sx, sz, paste) == tgt:
                    return (cx, ry, cz)
        return None
