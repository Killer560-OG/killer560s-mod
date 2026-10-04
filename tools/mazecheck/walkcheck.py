# MazeWalk's grid rules (autopuzzles/MazeWalk.java) on the shipped Teleport Maze capture - see run.sh.
# Block heights are modelled from names: frame 13/16, bottom slab 1/2, walls/fences/bars 1.5, others full.
import sys, math, heapq
sys.path.insert(0,'tools')
from capture_decode import load, at
r=load('src/main/resources/assets/killer560smod/rooms/Teleport_Maze.json')
M=r['margin']
EMPTY=('air','short_grass','torch','wall_torch','tall_grass','fern','cave_air','redstone_wire','lever')
def shape(x,y,z):  # rel coords -> (minY,maxY) or None
    b=at(r,x+M,y,z+M)
    if b is None: return (0,1.0)
    n=b.split('[')[0].replace('minecraft:','')
    if n in EMPTY or n.endswith('_button'): return None
    if n=='end_portal_frame': return (0,0.8125)
    if 'slab' in n:
        if 'type=bottom' in b: return (0,0.5)
        if 'type=top' in b: return (0.5,1.0)
        return (0,1.0)
    if n.endswith('_wall') or 'fence' in n or 'iron_bars' in n or 'pane' in n: return (0,1.5)
    return (0,1.0)
def surface(x,y,z):
    h=shape(x,y,z)
    if h is None:
        f=shape(x,y-1,z)
        if f is None or f[1]>1.0: return None
        st=y-1+f[1]
    else:
        if h[1]>0.9: return None
        st=y+h[1]
    yy=y+1
    while yy<=math.floor(st+1.8):
        s=shape(x,yy,z)
        if s is not None and s[0]+yy<st+1.8: return None
        yy+=1
    return st
def can(a,b): return a is not None and b is not None and b-a<=0.6 and a-b<=1.25
def path(sx,sz,gx,gz,y=69):
    best={(sx,sz):0}; pq=[(0,sx,sz)]; S={}
    def sf(x,z):
        if (x,z) not in S: S[(x,z)]=surface(x,y,z)
        return S[(x,z)]
    if sf(gx,gz) is None: return None
    S[(sx,sz)] = sf(sx,sz) if sf(sx,sz) is not None else y+0.8
    while pq:
        d,x,z=heapq.heappop(pq)
        if (x,z)==(gx,gz): return d
        if d>best.get((x,z),1e9): continue
        for dx,dz in ((1,0),(-1,0),(0,1),(0,-1),(1,1),(1,-1),(-1,1),(-1,-1)):
            nx,nz=x+dx,z+dz
            if abs(nx-gx)>20 or abs(nz-gz)>25: continue
            if not can(sf(x,z),sf(nx,nz)): continue
            if dx and dz and (not can(sf(x,z),sf(nx,z)) or not can(sf(x,z),sf(x,nz))): continue
            nd=d+(1.4142 if dx and dz else 1)
            if nd<best.get((nx,nz),1e9): best[(nx,nz)]=nd; heapq.heappush(pq,(nd,nx,nz))
    return None
def straight(sx,sz,gx,gz,y=69):
    # walking straight at the pad: any cell under a half-width box along the line not steppable?
    L=math.hypot(gx-sx,gz-sz); n=max(1,int(L/0.25)); last=surface(sx,y,sz) or y+0.8
    for i in range(1,n+1):
        t=i/n; px=sx+.5+(gx-sx)*t; pz=sz+.5+(gz-sz)*t
        for ox in (-.31,.31):
            for oz in (-.31,.31):
                s=surface(math.floor(px+ox),y,math.floor(pz+oz))
                if not can(last,s): return False
        last=surface(math.floor(px),y,math.floor(pz))
    return True
PADS=[(4,14),(10,14),(10,20),(4,20),(4,12),(4,6),(10,6),(10,12),(12,28),(12,22),(18,22),(18,28),(26,14),(20,20),(20,14),(26,20),(26,28),(26,22),(20,28),(20,22),(10,22),(10,28),(4,28),(4,22),(20,6),(20,12),(26,12),(26,6)]
blocked=0; nopath=0; total=0
for c in range(7):
    for i in range(4):
        for j in range(4):
            if i==j: continue
            a=PADS[c*4+i]; b=PADS[c*4+j]; total+=1
            p=path(a[0],a[1],b[0],b[1]); s=straight(a[0],a[1],b[0],b[1])
            if p is None: nopath+=1; print('NO PATH chamber',c,a,b)
            if not s: blocked+=1
print('pad-to-pad pairs within a chamber:',total,'straight walk blocked:',blocked,'no path at all:',nopath)
d=path(15,-1,15,12)
print('doorway (15,68,-2) -> start pad (15,69,12): path length', d, 'straight ok', straight(15,-1,15,12))
