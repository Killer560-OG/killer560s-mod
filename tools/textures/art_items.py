"""Every other Pack Disabler texture: boosters, honey, claws, bottles, plants, tools and oddities. Original art."""
from canvas import Canvas
from engine import Material
from materials import palette

ITEMS = {}


def item(*ids):
    def deco(fn):
        for i in ids:
            ITEMS[i] = fn
        return fn
    return deco


def outer(colour):
    return Material(colour, outer=True)


# ---- boosters: a capped canister of coloured fluid, an emblem on it; an Upgrade gets a green arrow badge -------

def booster(emblem, upgraded):
    c = Canvas([
        "................",
        "......iiii......",
        ".....iIiiii.....",
        ".....iiiiii.....",
        "....qqqqqqqq....",
        "....qeeeeeeq....",
        "....qeeeeeeq....",
        "....qeeeeeeq....",
        "....qeeeeeeq....",
        "....qeeeeeeq....",
        "....qeeeeeeq....",
        "....qeeeeeeq....",
        "....qqqqqqqq....",
        ".....iiiiii.....",
        "......iiii......",
        "................",
    ])
    c.stamp(emblem, 5, 6)
    if upgraded:
        c.stamp([
            "..v..",
            ".vVv.",
            "vvvvv",
            ".vvv.",
            ".vvv.",
        ], 11, 10)
    return c.grid()


SWORD = ["...zz.", "..zz..", ".zz...", "#z....", ".#...."]
CLOVER = [".vv.v.", "vvvvvv", ".vvvv.", "vvvvvv", ".v.vv."]
LEAF = ["...vv.", "..vvv.", ".vvnv.", "vvn...", "n....."]
EYE = ["......", ".zzzz.", "zz##zz", ".zzzz.", "......"]
SWEEP = ["zz....", "..zz..", "....zz", "..zz..", "zz...."]
COIN = [".yyyy.", "yyYyyy", "yyhhyy", "yyyyyy", ".yyyy."]

BOOSTERS = {
    'FIGHTING_BOOSTER_UNCOMMON': (SWORD, '#C9342A', True),
    'FORAGING_FORTUNE_BOOSTER_UNCOMMON': (LEAF, '#E0A030', True),
    'FORAGING_WISDOM_BOOSTER_UNCOMMON': (LEAF, '#3BB6C8', True),
    'HUNTING_WISDOM_BOOSTER_COMMON': (EYE, '#3B8FD0', False),
    'HUNTING_WISDOM_BOOSTER_UNCOMMON': (EYE, '#3B8FD0', True),
    'LUCK_BOOSTER': (CLOVER, '#2F8E3E', False),
    'LUCK_BOOSTER_UNCOMMON': (CLOVER, '#2F8E3E', True),
    'SWEEP_BOOSTER_UNCOMMON': (SWEEP, '#8E7AD8', True),
}
for _id, (_em, _col, _up) in BOOSTERS.items():
    def _draw(em=_em, col=_col, up=_up):
        return booster(em, up), palette(e=col, v='#5BE04A' if em is not CLOVER else '#9AF07A')
    ITEMS[_id] = _draw


# ---- Pots of Honeycomb: one clay pot, four sizes ------------------------------------------------------------------

def honey_pot(rx, ry, cy):
    c = Canvas()
    c.ellipse(8, cy, rx, ry, 'u')
    top = int(cy - ry) + 1
    c.rect(int(8 - rx * 0.55), top - 1, int(8 + rx * 0.55) - 1, top, 'd')
    c.rect(int(8 - rx * 0.45), top - 2, int(8 + rx * 0.45) - 1, top - 2, 'y')
    # honey running over the rim
    for x, h in ((int(8 - rx * 0.35), 2), (8, 3), (int(8 + rx * 0.3), 1)):
        for yy in range(top, top + h + 1):
            c.set(x, yy, 'y')
    # a honeycomb cell band across the belly
    band = int(cy + ry * 0.15)
    for x in range(int(8 - rx) + 1, int(8 + rx)):
        if c.get(x, band) == 'u':
            c.set(x, band, 'h' if x % 3 else 'y')
    c.set(int(8 - rx * 0.5), int(cy - ry * 0.2), 'U')
    return c.grid()


HONEY = palette(u='#A8643A', y='#F8C848', h='#D9902A')


@item('FUN_SIZED_POT_OF_HONEYCOMB')
def pot1():
    return honey_pot(3.5, 3.5, 11), HONEY


@item('FAMILY_SIZED_POT_OF_HONEYCOMB')
def pot2():
    return honey_pot(4.5, 4.5, 10.5), HONEY


@item('JUMBO_POT_OF_HONEYCOMB')
def pot3():
    return honey_pot(5.5, 5.2, 10), HONEY


@item('BEHEMOTH_POT_OF_HONEYCOMB')
def pot4():
    return honey_pot(7, 6, 9.6), HONEY


# ---- Honey Dippers: a wooden dipper, the grooved head grows with the tier ------------------------------------------

def dipper(head):
    c = Canvas()
    c.line(1, 14, 9, 6, 'w')
    c.line(2, 14, 10, 6, 'w')
    cx, cy = 11, 4.5
    c.ellipse(cx, cy, head, head, 'h')
    for i in range(-int(head), int(head) + 1, 2):
        for t in range(-int(head) - 1, int(head) + 2):
            x, y = int(cx - 0.5 + i * 0.7 + t * 0.7), int(cy - 0.5 - i * 0.7 + t * 0.7)
            if c.get(x, y) == 'h':
                c.set(x, y, 'y')
    c.set(int(cx), int(cy + head) + 1, 'y')
    c.set(int(cx), int(cy + head) + 2, 'y')
    return c.grid()


DIPPER = palette(w='#B07A44', h='#D98A12', y='#F8C848')


@item('SMALL_HONEY_DIPPER')
def dip1():
    return dipper(2.2), DIPPER


@item('MEDIUM_HONEY_DIPPER')
def dip2():
    return dipper(2.8), DIPPER


@item('LARGE_HONEY_DIPPER')
def dip3():
    return dipper(3.4), DIPPER


@item('GIANT_HONEY_DIPPER')
def dip4():
    return dipper(4.0), DIPPER


# ---- Sloth Claws: a hooked claw, longer per tier ------------------------------------------------------------------

def claw(n):
    """n = 1..4. A furry pad at the bottom left and a thick claw that arcs up and hooks down; it grows per tier."""
    import math
    c = Canvas()
    k = 0.55 + 0.15 * n
    steps = 48
    for i in range(steps + 1):
        t = i / steps
        x = 3 + 11 * k * t
        y = 12.5 - 10 * k * math.sin(math.pi * 0.8 * t)
        w = (3.6 * k) * (1 - t) + 0.9
        c.ellipse(x, y, w / 2 + 0.2, w / 2 + 0.2, 'f')
    c.ellipse(3.2, 13, 2.6 * k + 0.6, 2.1 * k + 0.4, 'l')
    return c.grid()


CLAW = palette(f='#E8DCC2', l='#6E5038')


@item('SMALL_SLOTH_CLAW')
def claw1():
    return claw(1), CLAW


@item('MEDIUM_SLOTH_CLAW')
def claw2():
    return claw(2), CLAW


@item('LARGE_SLOTH_CLAW')
def claw3():
    return claw(3), CLAW


@item('GIANT_SLOTH_CLAW')
def claw4():
    return claw(4), CLAW


# ---- bottles ------------------------------------------------------------------------------------------------------

def bottle(liquid=None, level=0):
    c = Canvas([
        "................",
        "......wwww......",
        "......wWww......",
        ".......qq.......",
        ".......qq.......",
        "......qqqq......",
        ".....qqqqqq.....",
        "....qqqqqqqq....",
        "...qqqqqqqqqq...",
        "...qqqqqqqqqq...",
        "...qqqqqqqqqq...",
        "...qqqqqqqqqq...",
        "...qqqqqqqqqq...",
        "....qqqqqqqq....",
        ".....qqqqqq.....",
        "................",
    ])
    if liquid:
        for y in range(14, 14 - level, -1):
            for x in range(16):
                if c.get(x, y) == 'q' and c.get(x - 1, y) == 'q' and c.get(x + 1, y) == 'q':
                    c.set(x, y, liquid)
    c.set(5, 8, 'Q')
    c.set(5, 9, 'Q')
    return c


@item('NOTHING_IN_A_BOTTLE')
def nothing_bottle():
    return bottle().grid(), palette(q=Material('#D8F0F6', alpha=190))


@item('SCATHA_IN_A_BOTTLE')
def scatha_bottle():
    c = bottle()
    for x, y in [(5, 12), (6, 12), (7, 11), (8, 11), (9, 11), (10, 10), (10, 9), (9, 8)]:
        c.set(x, y, 'a')
    c.set(9, 8, '#')
    return c.grid(), palette(q=Material('#D8F0F6', alpha=190), a='#8A6E5A')


@item('BOTTLED_MISCHIEF')
def mischief():
    c = bottle('p', 6)
    for x, y in [(6, 11), (9, 10), (7, 13), (10, 12)]:
        c.set(x, y, '*')
    c.stamp(["j..j", ".jj."], 6, 6)
    return c.grid(), palette(p='#7A3CC8', j='#C07AF0')


@item('VIAL_OF_SPRING_WATER')
def spring_vial():
    c = Canvas([
        "................",
        ".......ww.......",
        ".......WW.......",
        "......qqqq......",
        "......qbbq......",
        "......qbbq......",
        "......qbbq......",
        "......qbbq......",
        "......qbbq......",
        "......qbbq......",
        "......qbBq......",
        "......qbbq......",
        "......qbbq......",
        ".......qq.......",
        "................",
        "................",
    ])
    for x, y in [(3, 4), (12, 7), (4, 11), (11, 2)]:
        c.set(x, y, '*')
    return c.grid(), palette(b='#7FD0F0', w='#E0AE38')


@item('BRINE_TONIC')
def brine_tonic():
    c = bottle('t', 7)
    c.rect(4, 9, 11, 10, 's')
    c.set(6, 9, 'z')
    c.set(9, 10, 'z')
    return c.grid(), palette(t='#2A9E8E', s='#F0EAD8')


@item('SEA_BRINE')
def sea_brine():
    c = Canvas()
    c.rect(7, 1, 8, 2, 'w')
    c.rect(7, 3, 8, 4, 'q')
    c.ellipse(8, 10, 6, 5.5, 'q')
    c.ellipse(8, 10.5, 5, 4.5, 'b')
    for x in range(4, 13):
        if c.get(x, 7) == 'b':
            c.set(x, 7, 'z')
    c.set(5, 8, 'z')
    c.set(10, 8, 'z')
    c.set(4, 9, 'Q')
    return c.grid(), palette(b='#1F4E9A', z='#F2F4F2')


@item('BEE_SALIVA')
def bee_saliva():
    c = Canvas([
        "................",
        ".......y........",
        ".......y........",
        "......yyy.......",
        "......yyy.......",
        ".....yyyyy......",
        ".....yyyyy......",
        "....yYyyyyy.....",
        "....yYyyyyy.....",
        "...yyyyyyyyy....",
        "...yyyyyyyyy....",
        "...yyyyyyyyy....",
        "....yyyyyyy.....",
        ".....yyyyy......",
        "................",
        "................",
    ])
    c.stamp(["x.x", ".x."], 11, 2)
    c.set(12, 1, 'z')
    c.set(10, 1, 'z')
    return c.grid(), palette(y='#F2B21E', z=Material('#E8F4FF', alpha=200))


@item('MORNING_DEW')
def morning_dew():
    c = Canvas()
    c.ellipse(8, 10, 7, 4, 'v')
    c.line(2, 13, 13, 7, 'n')
    c.stamp([
        "..b..",
        ".bbb.",
        "bBbbb",
        "bbbbb",
        ".bbb.",
    ], 6, 3)
    c.set(12, 4, '*')
    return c.grid(), palette(b='#9FE0F5', v='#5DB040', n='#3A7A2A')


@item('INFINIPOT')
def infinipot():
    c = bottle('e', 8)
    c.stamp([
        ".ggg..ggg.",
        "g...gg...g",
        "g...gg...g",
        ".ggg..ggg.",
    ], 3, 9)
    return c.grid(), palette(e='#6A1A2A', g=Material('#FFD84A', flat=True))


# ---- sprayonators and nozzles -------------------------------------------------------------------------------------

def sprayer(body, tank):
    c = Canvas([
        "................",
        "...aaaaaaa......",
        "..aaAaaaaaaa....",
        "..aaaaaaaaaaa...",
        "....aaa..aa.....",
        "....aaa...a.....",
        "....eee.........",
        "...eeeee........",
        "..eeeeeee.......",
        "..eeeeeee.......",
        "..eEeeeee.......",
        "..eeeeeee.......",
        "..eeeeeee.......",
        "..eeeeeee.......",
        "...eeeee........",
        "................",
    ])
    for x, y in [(13, 2), (14, 1), (14, 3), (15, 2)]:
        c.set(x, y, 'b')
    return c.grid(), palette(a=body, e=tank, b='#9FE0F5')


@item('JUICY_SPRAYONATOR')
def juicy_spray():
    return sprayer('#E06A2A', '#F4A23A')


@item('SALTY_SPRAYONATOR')
def salty_spray():
    return sprayer('#3A7FC8', '#E8ECEF')


def nozzle(col):
    c = Canvas([
        "................",
        "................",
        "................",
        "................",
        "..aaaaaaaaa.....",
        ".aaAaaaaaaaaaa..",
        ".aaaaaaaaaaaaaa.",
        ".aaaaaaaaaaaaa..",
        "..aaaaaaaaa.....",
        "....aaa.........",
        "....aaa.........",
        "....iii.........",
        "....iii.........",
        "................",
        "................",
        "................",
    ])
    for x, y in [(15, 6), (14, 4), (14, 8)]:
        c.set(x, y, 'b')
    return c.grid(), palette(a=col, b='#9FE0F5')


@item('JUICY_NOZZLE')
def juicy_nozzle():
    return nozzle('#E06A2A')


@item('SALTY_NOZZLE')
def salty_nozzle():
    return nozzle('#3A7FC8')


# ---- plants -------------------------------------------------------------------------------------------------------

VEILSHROOM = [
    "................",
    "................",
    ".....mmmmmm.....",
    "...mmmzmmmmmm...",
    "..mmmmmmmmzmmm..",
    ".mmzmmmmmmmmmmm.",
    ".mmmmmmmMmmmmmm.",
    ".mmmmmmmmmmmzmm.",
    "..zz.z.ff.z.zz..",
    "...z.z.ff.z.z...",
    "....z..ff..z....",
    ".......ff.......",
    ".......ff.......",
    "......ffff......",
    ".....vffffv.....",
    "................",
]


@item('RUBY_VEILSHROOM')
def ruby_veilshroom():
    return VEILSHROOM, palette(m='#C8202E', z='#F4D8D8', f='#F0E4CC')


@item('ENCHANTED_RUBY_VEILSHROOM')
def enchanted_ruby_veilshroom():
    c = Canvas(VEILSHROOM)
    c.set(2, 2, '*')
    c.set(13, 1, '*')
    c.set(14, 10, '*')
    return c.grid(), palette(m='#E0263A', z='#FFE0E8', f='#F0E4CC')


@item('VEILSHROOM_BUNCH')
def veilshroom_bunch():
    c = Canvas()
    for cx, cy in ((4.5, 4), (11.5, 4), (8, 2.5)):
        c.ellipse(cx, cy, 3.5, 2.5, 'm')
        c.set(int(cx) - 1, int(cy) - 1, 'z')
    c.line(4, 6, 7, 10, 'f')
    c.line(11, 6, 8, 10, 'f')
    c.line(8, 4, 8, 10, 'f')
    c.stamp([
        ".ssssss.",
        "ssssssss",
        ".ssSsss.",
        "..ssss..",
        "..ssss..",
        "...ss...",
    ], 4, 9)
    return c.grid(), palette(m='#C8202E', z='#F4D8D8', s='#B8925A')


@item('MARSHROOM')
def marshroom():
    c = Canvas()
    c.ellipse(8, 6, 7, 4.5, 'a')
    c.rect(6, 9, 9, 14, 'f')
    for x, y in [(4, 4), (10, 3), (12, 6), (6, 7)]:
        c.set(x, y, 'n')
    for x in (3, 7, 12):
        c.set(x, 10, 'a')
        c.set(x, 11, 'a')
    c.rect(4, 14, 11, 14, 'v')
    return c.grid(), palette(a='#6E7A3A', n='#4A5426', f='#D8D0B0', v='#3E6A3A')


@item('BLOOMING_THORNS')
def blooming_thorns():
    c = Canvas()
    c.line(2, 14, 12, 4, 'n', 2)
    for x, y in [(4, 11), (7, 8), (10, 5), (5, 13), (8, 10), (11, 7)]:
        c.set(x, y, 'w')
    c.ellipse(11.5, 3.5, 3.5, 3.5, 'j')
    c.ellipse(11.5, 3.5, 1.4, 1.4, 'y')
    return c.grid(), palette(n='#3A6E2A', w='#C8D8A0', j='#F070A8', y='#FFE070')


@item('WATER_HYACINTH')
def water_hyacinth():
    c = Canvas()
    c.ellipse(8, 13, 7.5, 2.6, 'b')
    c.ellipse(5, 11, 3, 2, 'v')
    c.ellipse(11, 11, 3, 2, 'v')
    c.rect(7, 4, 8, 11, 'n')
    for y in range(1, 9):
        w = 2 if y % 2 else 3
        c.rect(8 - w, y, 7 + w, y, 'p') if y < 8 else None
    for x, y in [(6, 2), (9, 4), (6, 6)]:
        c.set(x, y, 'j')
    return c.grid(), palette(b='#3C7FE0', v='#4FA33A', n='#2C6A2A', p='#8A5AD8', j='#D0B0FF')


@item('WINDING_IVY')
def winding_ivy():
    c = Canvas()
    pts = [(2, 14), (3, 12), (5, 11), (7, 11), (8, 9), (7, 7), (8, 5), (10, 4), (12, 4), (13, 2)]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        c.line(x0, y0, x1, y1, 'n')
    for x, y in [(4, 13), (9, 10), (6, 6), (11, 5), (12, 1)]:
        c.stamp(["vv", "vV"], x - 1, y - 1)
    return c.grid(), palette(n='#4E3A22', v='#4FB03A')


@item('YOGI_BERRY')
def yogi_berry():
    c = Canvas()
    c.ellipse(8, 9.5, 6, 6, 'p')
    c.stamp(["vv...vv", ".vvnvv.", "...n..."], 5, 1)
    c.set(5, 6, 'P')
    c.set(6, 6, 'P')
    c.set(5, 7, 'P')
    for x, y in [(9, 10), (11, 8), (7, 12), (10, 13)]:
        c.set(x, y, 'k')
    return c.grid(), palette(p='#5A3AB8', k='#3A2478', v='#4FA33A', n='#2C6A2A')


@item('FORESTS_FAVOR')
def forests_favor():
    c = Canvas([
        "................",
        "..........yyy...",
        "........yyyyyy..",
        "......yyyyyyyy..",
        ".....yyyYyyyyy..",
        "....yyyyYyyyy...",
        "....yyyyyYyyy...",
        "...yyyyyyyYyy...",
        "...yyyyyhyyy....",
        "...yyyyhyyyy....",
        "...yyyhyyyy.....",
        "....yhyyyy......",
        "....hyyy........",
        "...h............",
        "..h.............",
        "................",
    ])
    for x, y in [(2, 3), (13, 10), (11, 13)]:
        c.set(x, y, '*')
    return c.grid(), palette(y='#E8C040', h='#8A6A20')


@item('HELIXIS')
def helixis():
    c = Canvas()
    c.rect(3, 1, 12, 14, 'w')
    c.rect(4, 1, 4, 14, 'd')
    c.rect(11, 1, 11, 14, 'd')
    for y in range(1, 15):
        import math
        x1 = int(round(7.5 + 2.5 * math.sin(y * 0.8)))
        x2 = int(round(7.5 - 2.5 * math.sin(y * 0.8)))
        c.set(x1, y, 't')
        c.set(x2, y, 'o')
    return c.grid(), palette(w='#5E4A3A', d='#3E3028', t='#40E0D0', o='#F0A040')


@item('DISTANT_ECHO')
def distant_echo():
    c = Canvas()
    c.ellipse(6, 9, 5, 5, 'f')
    c.ring(6, 9, 3.2, 3.2, 1, 'j')
    c.set(6, 9, 'j')
    c.set(5, 8, 'F')
    for r, ch in ((7, 'b'), (9, 'b')):
        for y in range(2, 16):
            for x in range(9, 16):
                d = ((x + 0.5 - 6) ** 2 + (y + 0.5 - 9) ** 2) ** 0.5
                if r - 0.5 <= d < r + 0.5 and c.get(x, y) == '.' and abs(y + 0.5 - 9) < r * 0.7:
                    c.set(x, y, ch)
    return c.grid(), palette(f='#F2DCC0', j='#D89A7A', b=Material('#7FC8F0', flat=True))


# ---- creatures and their bits -------------------------------------------------------------------------------------

@item('CRUNCHY_BUG')
def crunchy_bug():
    c = Canvas()
    c.ellipse(8, 9.5, 5, 5.5, 'n')
    c.ellipse(8, 3.5, 2.5, 2, 'x')
    c.line(8, 5, 8, 14, 'x')
    for y in (7, 10, 13):
        c.set(2, y, 'x')
        c.set(13, y, 'x')
    c.set(6, 1, 'x')
    c.set(9, 1, 'x')
    c.set(5, 7, 'N')
    c.set(5, 8, 'N')
    return c.grid(), palette(n='#3E8A4A', x='#2A2420')


@item('WRIGGLEWORM')
def wriggleworm():
    c = Canvas()
    pts = [(2, 12), (4, 13), (6, 12), (7, 10), (8, 8), (10, 7), (12, 8), (13, 6), (13, 4)]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        c.line(x0, y0, x1, y1, 'j', 2)
    c.set(13, 3, '#')
    c.set(12, 3, 'j')
    return c.grid(), palette(j=Material('#E88AA0', outer=True))


@item('RAINBOW_FEATHER')
def rainbow_feather():
    c = Canvas()
    colours = ['r', 'o', 'y', 'v', 'b', 'p']
    for i in range(12):
        y = 13 - i
        x = 3 + i
        w = 2 if i < 2 or i > 10 else 3
        ch = colours[min(5, i // 2)]
        for d in range(-w, w + 1):
            c.set(x + d // 2 - 1, y + d // 2 + d % 2 - 1, ch)
    c.line(1, 15, 13, 3, 's')
    return c.grid(), palette(y='#F6E040', s='#F4F0E8', b='#3CA0E0')


@item('ISOPOD_HUSK')
def isopod_husk():
    c = Canvas()
    c.ellipse(8.5, 8, 6, 5, 'a')
    for x in (5, 8, 11):
        for y in range(2, 14):
            if c.get(x, y) == 'a':
                c.set(x, y, 'x')
    c.rect(1, 7, 2, 8, 'a')
    c.line(1, 6, 0, 4, 'x')
    c.line(1, 9, 0, 11, 'x')
    c.rect(14, 7, 15, 8, 'x')
    for x in (4, 7, 10, 13):
        c.set(x, 13, 'x')
    c.set(4, 5, 'A')
    c.set(6, 4, 'A')
    return c.grid(), palette(a='#A8A0A0', x='#5E5458')


@item('SUBLIME_SILK')
def sublime_silk():
    c = Canvas()
    c.rect(4, 1, 11, 2, 'w')
    c.rect(4, 13, 11, 14, 'w')
    c.rect(5, 3, 10, 12, 'p')
    for y in range(3, 13, 2):
        c.rect(5, y, 10, y, 'j')
    c.line(10, 8, 14, 12, 'j')
    c.line(14, 12, 13, 15, 'j')
    return c.grid(), palette(p='#D8C8F8', j='#B8A0E8', w='#B07A44')


@item('TITANOBOA_SHED')
def titanoboa_shed():
    c = Canvas()
    c.ring(8, 8, 7, 6, 2.5, 'v')
    c.ring(8, 8, 4, 3.5, 2, 'v')
    for x, y in [(3, 4), (12, 4), (2, 9), (13, 10), (7, 2), (8, 13), (6, 7), (10, 8)]:
        if c.get(x, y) == 'v':
            c.set(x, y, 'n')
    return c.grid(), palette(v='#B8B880', n='#6A7A40')


@item('SHARD_PACKRAT_SKULL')
def packrat_shard():
    c = Canvas([
        "................",
        ".......aa.......",
        "......aAaa......",
        ".....aaaaaa.....",
        ".....aAaaaaa....",
        "....aaaaaaaaa...",
        "....aaffffaaa...",
        "...aaff##fffaa..",
        "...afffffffffa..",
        "...aff#ff#fffa..",
        "...afffffffffa..",
        "....affzfzffa...",
        ".....aaaaaaa....",
        "......aaaaa.....",
        ".......aaa......",
        "................",
    ])
    return c.grid(), palette(a='#9A7A5A', f='#E8E0CC')


# ---- books, gadgets, oddities -------------------------------------------------------------------------------------

def book(cover, mark):
    c = Canvas()
    c.rect(3, 1, 12, 14, 'r')
    c.rect(2, 1, 3, 14, 'd')
    c.rect(13, 2, 13, 14, 's')
    c.rect(3, 14, 13, 14, 's')
    c.set(13, 1, '.')
    c.stamp(mark, 5, 4)
    return c, palette(r=cover, s='#E8DCC0')


@item('DEAD_MANS_JOURNAL')
def dead_mans_journal():
    c, pal = book('#5E4A38', [
        "......",
        ".xxxx.",
        "x.xx.x",
        "xxxxxx",
        ".x.x..",
        "......",
    ])
    for x, y in [(4, 10), (5, 11), (9, 12), (11, 3)]:
        c.set(x, y, 'b')
    pal.update(palette(x='#E8E0D0', b=Material('#6A8AA8', flat=True)))
    pal['r'] = Material('#5E4A38')
    pal['s'] = Material('#D8CCA8')
    return c.grid(), pal


@item('MAGIC_FOR_PETS')
def magic_for_pets():
    c, pal = book('#3C6EC8', [
        ".x..x.",
        "x....x",
        "..xx..",
        ".xxxx.",
        ".xxxx.",
        "......",
    ])
    pal['x'] = Material('#F6D860')
    return c.grid(), pal


@item('REFRACTIVE_PAINT_PALETTE')
def paint_palette():
    c = Canvas()
    c.ellipse(8, 8.5, 7.2, 6, 'w')
    c.ellipse(11, 11, 1.6, 1.6, '.')
    for (x, y), ch in zip([(4, 5), (7, 4), (10, 5), (4, 9), (6, 12), (12, 7)], 'roybvp'):
        c.rect(x, y, x + 1, y + 1, ch)
    return c.grid(), palette(w='#D8B888', y='#F6E040', b='#3CA0E0')


@item('RUBBER_SNORKEL')
def rubber_snorkel():
    c = Canvas()
    c.rect(11, 1, 12, 11, 'y')
    c.line(11, 11, 8, 14, 'y', 2)
    c.rect(5, 13, 8, 14, 'y')
    c.rect(11, 0, 12, 0, 'o')
    c.ellipse(5.5, 6.5, 4.5, 3.5, 'q')
    c.ring(5.5, 6.5, 4.5, 3.5, 1, 'x')
    return c.grid(), palette(y='#F6D030', o='#E07A1A', q=Material('#BFE6F0', alpha=170), x='#3A3A44')


@item('SOOTHING_INCENSE')
def soothing_incense():
    c = Canvas()
    c.line(4, 15, 10, 6, 'w')
    c.line(5, 15, 11, 6, 'w')
    c.set(11, 5, 'o')
    c.set(10, 5, 'o')
    for x, y in [(11, 3), (12, 2), (11, 1), (13, 0), (9, 3), (8, 1)]:
        c.set(x, y, 'a')
    return c.grid(), palette(w='#8A4A6A', o='#F08030', a=Material('#C8C8D8', alpha=170, flat=True))


@item('SHINING_COIN')
def shining_coin():
    c = Canvas()
    c.ellipse(8, 8.5, 6, 6, 'g')
    c.ring(8, 8.5, 4.2, 4.2, 1, 'h')
    c.rect(7, 6, 8, 11, 'h')
    c.set(5, 5, 'G')
    c.set(6, 4, 'G')
    for x, y in [(1, 2), (14, 1), (14, 14), (2, 14)]:
        c.set(x, y, '*')
    return c.grid(), palette(g='#F2C83A', h='#B8861A')


@item('SPARKLING_PODIUM')
def sparkling_podium():
    c = Canvas()
    c.rect(2, 12, 13, 14, 'a')
    c.rect(4, 9, 11, 11, 'z')
    c.rect(5, 7, 10, 8, 'a')
    c.stamp(["..j..", ".jJj.", "jjjjj", ".jjj.", "..j.."], 5, 1)
    for x, y in [(2, 3), (13, 5), (12, 1)]:
        c.set(x, y, '*')
    return c.grid(), palette(a='#B8B0C8', z='#E8E4F0', j='#B98CF2')


@item('SUPER_JACOB_SYSTEM')
def super_jacob_system():
    c = Canvas()
    c.rect(1, 4, 14, 10, 'a')
    c.rect(3, 6, 6, 8, 'x')
    c.rect(9, 6, 12, 6, 'x')
    c.rect(9, 8, 10, 8, 'r')
    c.rect(12, 8, 12, 8, 'b')
    c.line(8, 11, 8, 12, 'x')
    c.rect(5, 12, 11, 14, 'z')
    c.set(6, 13, 'r')
    c.set(10, 13, 'b')
    c.set(2, 5, 'A')
    return c.grid(), palette(a='#C8C8D0', x='#3A3A44', z='#E0E0E8')


@item('MIRIA_PRIZE')
def miria_prize():
    c = Canvas()
    c.line(6, 9, 3, 15, 'b', 2)
    c.line(9, 9, 12, 15, 'b', 2)
    c.ellipse(7.5, 6, 5.5, 5.5, 'g')
    c.ellipse(7.5, 6, 3.2, 3.2, 'y')
    c.set(7, 6, 'Y')
    return c.grid(), palette(g='#D8A030', y='#F8E070', b='#3C7FD0')


@item('GATEWAY_WAND')
def gateway_wand():
    c = Canvas()
    c.line(2, 14, 10, 6, 'w', 2)
    c.ellipse(11.5, 4, 3.5, 3.5, 'p')
    c.ellipse(11.5, 4, 1.8, 1.8, 'k')
    c.set(10, 2, 'P')
    for x, y in [(6, 2), (14, 9), (8, 0)]:
        c.set(x, y, '*')
    return c.grid(), palette(w='#5A3A2A', p='#B05AE8', k='#3A1A5E')


@item('ARCHER_DUNGEON_ABILITY_1')
def drop_arrows():
    c = Canvas()
    for x0 in (3, 8, 12):
        top = 1 if x0 == 8 else 3
        c.rect(x0, top, x0, top + 1, 'z')
        c.rect(x0, top + 2, x0, top + 8, 'w')
        c.stamp(["i.i", ".i."], x0 - 1, top + 9)
        c.set(x0, top + 10, 'i')
    return c.grid(), palette(z='#F4F4F0', w='#B07A44', i='#A8AEB8')


@item('BAG_OF_SEEDS')
def bag_of_seeds():
    c = Canvas()
    c.ellipse(8, 10, 6, 5, 's')
    c.rect(6, 3, 9, 5, 's')
    c.rect(5, 5, 10, 5, 'd')
    c.rect(5, 1, 6, 2, 's')
    c.rect(9, 1, 10, 2, 's')
    for x, y in [(6, 9), (9, 10), (7, 12), (10, 8), (5, 11)]:
        c.set(x, y, 'v')
    return c.grid(), palette(s='#C8A878', d='#6A4426', v='#6A9A30')


def capsule(top, mid):
    c = Canvas()
    c.ellipse(8, 8, 6.5, 6.5, 'z')
    for y in range(16):
        for x in range(16):
            if c.get(x, y) == 'z' and y < 8:
                c.set(x, y, 'e')
    c.rect(1, 7, 14, 8, 'x')
    c.ellipse(8, 8, 2, 2, 'i')
    c.set(5, 4, 'E')
    return c.grid(), palette(e=top, i=mid, z='#F0F0F0', x='#3A3A44')


@item('CRITTER_CAPSULE')
def critter_capsule():
    return capsule('#4FB03A', '#E8E8E8')


@item('MASTERFUL_CRITTER_CAPSULE')
def masterful_capsule():
    return capsule('#9A4AE0', '#F2C83A')


@item('FEAST_BURGER')
def feast_burger():
    c = Canvas()
    c.ellipse(7, 5, 6, 3.5, 'o')
    c.rect(1, 6, 13, 6, 'v')
    c.rect(1, 7, 13, 8, 'd')
    c.rect(1, 9, 13, 9, 'y')
    c.rect(2, 10, 12, 11, 'o')
    for x, y in [(5, 3), (8, 2), (10, 4)]:
        c.set(x, y, 'f')
    for x in (12, 13, 14, 15):
        c.rect(x, 9 + (x % 2), x, 14, 'y')
    c.rect(11, 12, 15, 15, 'r')
    return c.grid(), palette(o='#D88A3A', d='#6A3A22', y='#F6D040', v='#5DB040', f='#F4E8C8')


@item('EXTREMELY_MILD_ADHESIVE')
def adhesive():
    c = Canvas()
    c.line(2, 13, 10, 5, 'z', 3)
    c.line(10, 5, 12, 3, 'a', 2)
    c.set(13, 2, 'a')
    c.line(3, 13, 5, 11, 'b', 2)
    c.rect(13, 4, 13, 6, 'y')
    c.set(13, 7, 'y')
    return c.grid(), palette(z='#F2F2EC', a='#9A9AA4', b='#3C7FD0', y='#F6E8A0')


@item('CONTRABAND')
def contraband():
    c = Canvas()
    c.rect(2, 4, 13, 14, 'w')
    c.rect(2, 4, 13, 5, 'd')
    c.rect(7, 4, 8, 14, 's')
    c.rect(2, 9, 13, 9, 'd')
    c.stamp([".zz.", "z##z", "zzzz", ".z.z"], 10, 10)
    c.set(3, 6, 'W')
    return c.grid(), palette(w='#A87A48', d='#6A4426', s='#D8C090')


@item('GRUNGLE')
def grungle():
    c = Canvas()
    c.ellipse(8, 10, 6.5, 5, 'v')
    c.rect(4, 3, 4, 6, 'v')
    c.rect(11, 2, 11, 6, 'v')
    c.set(4, 2, 'n')
    c.set(11, 1, 'n')
    c.rect(5, 9, 6, 10, 'z')
    c.rect(9, 9, 10, 10, 'z')
    c.set(6, 10, '#')
    c.set(10, 10, '#')
    c.rect(6, 13, 9, 13, 'n')
    return c.grid(), palette(v='#7AB830', n='#3E6A1A')


@item('MINING_OFF_CAMERA')
def mining_camera():
    c = Canvas()
    c.rect(1, 5, 14, 13, 'a')
    c.rect(3, 3, 6, 4, 'a')
    c.ellipse(8.5, 9, 3.6, 3.6, 'x')
    c.ellipse(8.5, 9, 2, 2, 'b')
    c.set(7, 8, 'B')
    c.rect(12, 6, 13, 6, 'r')
    c.set(2, 6, 'A')
    return c.grid(), palette(a='#5A5A64', x='#22222A', b='#3CA0E0')


@item('REINFORCED_NETTING')
def reinforced_netting():
    c = Canvas()
    c.rect(1, 1, 14, 14, 'w')
    c.rect(2, 2, 13, 13, '.')
    for i in range(2, 14, 3):
        c.line(i, 2, i, 13, 's')
        c.line(2, i, 13, i, 's')
    return c.grid(), palette(w=Material('#8A6A3A', outer=False), s='#D8C8A0')


@item('GIGANTIC_FISHING_NET')
def gigantic_net():
    c = Canvas()
    c.line(1, 15, 7, 9, 'w', 2)
    c.ring(10, 6, 5.5, 5.5, 1, 'd')
    for i in range(5, 16, 2):
        for j in range(0, 12):
            if c.get(i, j) == '.' and ((i - 10) ** 2 + (j - 6) ** 2) < 20:
                c.set(i, j, 's')
    return c.grid(), palette(w='#9C6B3C', d='#6A4426', s=Material('#E8E0C8', alpha=220))


@item('SIGNAL_ENHANCER')
def signal_enhancer():
    c = Canvas()
    c.rect(4, 9, 11, 14, 'a')
    c.rect(5, 10, 10, 12, 'x')
    c.set(6, 11, 't')
    c.set(8, 11, 't')
    c.rect(7, 4, 8, 8, 'i')
    c.ellipse(7.5, 3, 1.5, 1.5, 't')
    for r in (3, 5):
        for x in range(0, 16):
            for y in range(0, 8):
                d = ((x + 0.5 - 7.5) ** 2 + (y + 0.5 - 3) ** 2) ** 0.5
                if r - 0.5 <= d < r + 0.5 and c.get(x, y) == '.' and y < 5:
                    c.set(x, y, 'b')
    return c.grid(), palette(a='#8A8A98', x='#2A2A34', t='#40E0D0', b=Material('#7FE0F0', flat=True))


@item('SUMMONING_EYE')
def summoning_eye():
    c = Canvas()
    c.ellipse(8, 8, 6.5, 6.5, 'p')
    c.ellipse(8, 8, 3.5, 4.5, 'v')
    c.rect(7, 5, 8, 10, '#')
    c.set(5, 4, 'P')
    c.set(4, 5, 'P')
    return c.grid(), palette(p='#3A2468', v='#40C890')


@item('AMALGAMATED_CRIMSONITE')
def amalgamated_crimsonite():
    c = Canvas([
        "................",
        "........r.......",
        ".......rRr......",
        "...r..rrrrr.....",
        "..rRr.rrrrr..r..",
        "..rrrrrrrrrr.rr.",
        "..rrrrrrrrrrrrr.",
        ".rrrraarrrrrrrr.",
        ".rrraaaarrrrrr..",
        ".rrraaaaarrrrr..",
        "..aaaaaaaaarr...",
        "..aaaaaaaaaaa...",
        "...aaaaaaaaa....",
        ".....aaaaa......",
        "................",
        "................",
    ])
    return c.grid(), palette(r='#D8283A', a='#5A3A3A')


def key_item(metal, gem):
    c = Canvas()
    c.ring(5, 5, 4, 4, 1.6, 'g')
    c.line(7, 7, 13, 13, 'g', 2)
    c.rect(11, 13, 12, 14, 'g')
    c.rect(13, 11, 14, 12, 'g')
    if gem:
        c.set(4, 4, 'e')
        c.set(5, 4, 'e')
        c.set(4, 5, 'e')
        c.set(5, 5, 'e')
    return c.grid(), palette(g=Material(metal, outer=True), e=gem or '#000000')


@item('DUNGEON_CHEST_KEY')
def dungeon_chest_key():
    return key_item('#E0AE38', '#D8283A')


@item('TUNGSTEN_KEY')
def tungsten_key():
    return key_item('#8A9AB0', None)


@item('UMBER_KEY')
def umber_key():
    return key_item('#9A6438', None)
