"""Accessories: talismans, rings, artifacts, necklaces, belts, bracelets and powders. Original art."""
from canvas import Canvas
from engine import Material
from materials import palette

ITEMS = {}


def item(sbid):
    def deco(fn):
        ITEMS[sbid] = fn
        return fn
    return deco


# ---- the three accessory tiers share a silhouette, so a player reads the tier from the shape -------------------

TALISMAN = [
    "................",
    "......dddd......",
    ".....d....d.....",
    ".....d....d.....",
    "......gggg......",
    "....gg????gg....",
    "...g????????g...",
    "..g??????????g..",
    "..g??????????g..",
    "..g??????????g..",
    "..g??????????g..",
    "..g??????????g..",
    "...g????????g...",
    "....gg????gg....",
    "......gggg......",
    "................",
]

RING = [
    "................",
    "......????......",
    ".....??????.....",
    ".....??????.....",
    "......????......",
    "....gggggggg....",
    "..gggg....gggg..",
    "..gg........gg..",
    ".ggg........ggg.",
    ".gg..........gg.",
    ".gg..........gg.",
    ".ggg........ggg.",
    "..gg........gg..",
    "..gggg....gggg..",
    "....gggggggg....",
    "................",
]

ARTIFACT = [
    "................",
    ".gg....GG....gg.",
    ".ggggggggggggggg",
    "..gffffffffffg..",
    "..gf????????fg..",
    "..gf????????fg..",
    ".ggf????????fgg.",
    ".ggf????????fgg.",
    ".ggf????????fgg.",
    ".ggf????????fgg.",
    "..gf????????fg..",
    "..gf????????fg..",
    "..gffffffffffg..",
    ".gggggggggggggg.",
    ".gg..........gg.",
    "................",
]
ARTIFACT[2] = ".gggggggggggggg."


def tier(template, face, emblem, gem=None, ox=4, oy=5):
    c = Canvas(template)
    c.replace('?', face)
    if template is RING:
        c = Canvas(RING)
        c.replace('?', 'e')
        if gem:
            c.stamp(gem, 5, 1)
        return c.grid()
    c.stamp(emblem, ox, oy)
    return c.grid()


# Emblems (8x8). '.' keeps the face.
ACCRETION = [
    "..pppp..",
    ".pkkkkp.",
    "pkkxxkkp",
    "pkxxxxkP",
    "pkxxxxkp",
    "pkkxxkkp",
    ".pkkkkp.",
    "..pppp..",
]
HONEYCOMB = [
    "..hhhh..",
    ".hyyyyh.",
    "hyyYyyyh",
    "hyyyyyyh",
    "hhyyyyhh",
    "hyhhhhyh",
    "hyyhhyyh",
    ".hh..hh.",
]
FLAME = [
    "....o...",
    "...oo...",
    "..ooo.o.",
    ".ooyoo..",
    ".oyyyoo.",
    "oyy@yyo.",
    "oy@@@yo.",
    ".oyyyo..",
]
AXE = [
    "...iiii.",
    "..iiiIiw",
    "..iiiiw.",
    "...iiw..",
    "....w...",
    "...w....",
    "..w.....",
    ".w......",
]
CANYON = [
    "........",
    "cc....cc",
    "ccc..ccc",
    "cccbbccc",
    "ccbbbbcc",
    "cbbBbbbc",
    "cbbbbbbc",
    "cccccccc",
]

THEMES = {
    'ACCRETION': dict(face='k', emblem=ACCRETION, pal=dict(e='#8E4FE0'),
                      gem=["..pp..", ".pxxp.", ".pxxp.", "..pp.."]),
    'HONEYCOMB': dict(face='h', emblem=HONEYCOMB, pal=dict(e='#F6C440'),
                      gem=["..hh..", ".hyyh.", ".hyyh.", "..hh.."]),
    'HOTSPOT': dict(face='t', emblem=FLAME, pal=dict(e='#EE7A1C', g='#7FB8C8'),
                    gem=["..o...", ".ooo..", ".oyyo.", "..oo.."]),
    'LUMBERJACK': dict(face='n', emblem=AXE, pal=dict(e='#C3C9D0', g='#9C6B3C'),
                       gem=[".iii..", "iiiiw.", ".ii.w.", ".....w"]),
    'TORRHUS': dict(face='f', emblem=CANYON, pal=dict(e='#2BB3A3', g='#B4452F'),
                    gem=["..tt..", ".tTtt.", ".tttt.", "..tt.."]),
}

for theme, t in THEMES.items():
    def make(template, t=t):
        def draw():
            grid = tier(template, t['face'], t['emblem'], t.get('gem'),
                        oy=5 if template is TALISMAN else 4)
            pal = dict(t['pal'])
            if template is RING:
                pal['g'] = Material(pal.get('g', '#E0AE38'), outer=True)
            return grid, palette(**pal)
        return draw
    ITEMS[theme + '_TALISMAN'] = make(TALISMAN)
    ITEMS[theme + '_RING'] = make(RING)
    ITEMS[theme + '_ARTIFACT'] = make(ARTIFACT)


@item('HONEYCOMB_NECKLACE')
def honeycomb_necklace():
    c = Canvas()
    for (x0, y0, x1, y1) in [(2, 0, 6, 6), (13, 0, 9, 6)]:
        c.line(x0, y0, x1, y1, 'g')
    c.stamp([
        "...hhhh...",
        "..hyyyyh..",
        ".hyyYyyyh.",
        ".hyyyyyyh.",
        ".hhyyyyhh.",
        ".hyhhhhyh.",
        "..hyyyyh..",
        "...hhhh...",
    ], 3, 6)
    return c.grid(), palette(g=Material('#E0AE38', outer=True))


@item('SPARKLING_AMULET')
def sparkling_amulet():
    c = Canvas()
    c.line(2, 0, 6, 5, 's')
    c.line(13, 0, 9, 5, 's')
    c.stamp([
        "..gggg..",
        ".gjjjjg.",
        "gjjJjjjg",
        "gjjjjjjg",
        ".gjjjjg.",
        "..gjjg..",
        "...gg...",
    ], 4, 6)
    for (x, y) in [(1, 8), (14, 9), (2, 13), (13, 13), (7, 15)]:
        c.set(x, y, '*')
    return c.grid(), palette(j='#B98CF2', s=Material('#E0AE38', outer=True))


BELT = [
    "................",
    "................",
    "....llllllll....",
    "..llLLLLLLLLll..",
    ".lll........lll.",
    "lll..........lll",
    "ll............ll",
    "ll............ll",
    "lll..........lll",
    ".lll..gggg..lll.",
    "..lllggeeggllll.",
    "...llg.ee.gll...",
    "....lggeeggl....",
    "......gggg......",
    "................",
    "................",
]
BELT[10] = "..lllggeegglll.."


@item('TORRHUS_BELT')
def torrhus_belt():
    return BELT, palette(l=Material('#A04A30', outer=True), g='#C3C9D0', e='#2BB3A3')


@item('SAFARI_BELT')
def safari_belt():
    c = Canvas(BELT)
    for (x, y) in [(5, 2), (9, 2), (1, 6), (14, 7), (3, 10)]:
        c.set(x, y, 'd')
    return c.grid(), palette(l=Material('#C9A46A', outer=True), d='#6A4426', g='#9C6B3C', e='#4FA33A')


@item('VEILSHROOM_BRACELET')
def veilshroom_bracelet():
    c = Canvas()
    c.ring(8, 10, 6.5, 4.5, 2.2, 'a')
    for x in (2, 7, 12):
        c.stamp([".mm.", "mMmm", "mmmm", ".ff."], x - 1, 1)
        c.set(x, 5, 'f')
        c.set(x + 1, 5, 'f')
    return c.grid(), palette(a=Material('#6F8C45', outer=True), m='#D7323F')


# ---- Powder of One / the Few / the Many / the People: a corked vial that fills with each mining powder -----------

def powder(layers, sparkle=False):
    c = Canvas([
        "................",
        "......wwww......",
        "......wWww......",
        ".......qq.......",
        "......qqqq......",
        ".....qqqqqq.....",
        "....qqqqqqqq....",
        "....qqqqqqqq....",
        "....qqqqqqqq....",
        "....qqqqqqqq....",
        "....qqqqqqqq....",
        "....qqqqqqqq....",
        "....qqqqqqqq....",
        ".....qqqqqq.....",
        "................",
        "................",
    ])
    y = 12
    for ch, h in layers:
        for _ in range(h):
            for x in range(5, 11):
                if c.get(x, y) == 'q':
                    c.set(x, y, ch)
            y -= 1
    if sparkle:
        for (x, yy) in [(2, 3), (13, 5), (12, 1)]:
            c.set(x, yy, '*')
    return c.grid()


POWDERS = palette(e='#3FD08A', j='#E06AC0', b='#7FC8F0')


@item('POWDER_OF_ONE')
def powder_one():
    return powder([('e', 3)]), POWDERS


@item('POWDER_OF_THE_FEW')
def powder_few():
    return powder([('e', 3), ('j', 3)]), POWDERS


@item('POWDER_OF_THE_MANY')
def powder_many():
    return powder([('e', 3), ('j', 2), ('b', 3)]), POWDERS


@item('POWDER_OF_THE_PEOPLE')
def powder_people():
    return powder([('e', 3), ('j', 3), ('b', 2), ('y', 1)], sparkle=True), POWDERS


@item('BEASTSLAYERS_CODEX')
def beastslayers_codex():
    c = Canvas()
    c.rect(3, 1, 12, 14, 'r')
    c.rect(2, 1, 3, 14, 'd')
    c.rect(13, 2, 13, 14, 's')
    c.rect(3, 14, 13, 14, 's')
    for x0 in (7, 10, 13):
        c.line(x0 - 1, 4, x0 - 4, 11, 'f')
    c.rect(13, 1, 13, 1, '.')
    return c.grid(), palette(r='#7E2B24', s='#E8DCC0', f='#F4E6C8')
