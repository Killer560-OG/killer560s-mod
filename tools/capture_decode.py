import base64, gzip, json, struct, sys

def load(path):
    d = json.load(open(path))
    sx, sz = d['sizeX'], d['sizeZ']
    minY, maxY = d['minY'], d['maxY']
    raw = gzip.decompress(base64.b64decode(d['blocksZ']))
    vals = struct.unpack('>%dh' % (len(raw) // 2), raw)
    return dict(name=d['name'], sx=sx, sz=sz, minY=minY, maxY=maxY,
                pal=d['palette'], v=vals, margin=d.get('margin', 1))

def at(r, x, y, z):
    if x < 0 or z < 0 or x >= r['sx'] or z >= r['sz'] or y < r['minY'] or y > r['maxY']:
        return None
    i = (y - r['minY']) * r['sx'] * r['sz'] + z * r['sx'] + x
    if i >= len(r['v']):
        return None
    p = r['v'][i]
    return r['pal'][p] if 0 <= p < len(r['pal']) else None

if __name__ == '__main__':
    r = load(sys.argv[1])
    print(r['name'], r['sx'], r['sz'], r['minY'], r['maxY'], 'margin', r['margin'])
    want = sys.argv[2] if len(sys.argv) > 2 else None
    if want:
        hits = [(x, y, z, b)
                for y in range(r['minY'], r['maxY'] + 1)
                for z in range(r['sz']) for x in range(r['sx'])
                for b in [at(r, x, y, z)] if b and want in b]
        print(len(hits), 'hits')
        for h in hits:
            print(h)
