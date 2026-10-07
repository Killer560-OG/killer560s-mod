"""Shared materials (letter -> Material). An item may override any letter for itself."""
from engine import Material

M = Material

BASE = {
    'g': M('#E0AE38'),              # gold
    'i': M('#C3C9D0'),              # iron / steel
    'w': M('#9C6B3C'),              # wood
    'd': M('#6A4426'),              # dark wood / leather strap
    'h': M('#D98A12'),              # honey (deep)
    'y': M('#F6C440'),              # honey (light) / yellow
    's': M('#D8CFBC'),              # string / cord / paper
    'k': M('#3B2A5E'),              # void indigo
    'x': M('#1C1724'),              # near-black
    'p': M('#9257D6'),              # purple
    'r': M('#C9342A'),              # red
    'o': M('#EE7A1C'),              # orange
    't': M('#2BB3A3'),              # teal
    'b': M('#3C7FE0'),              # water blue
    'q': M('#CBEAF2', alpha=170),   # glass
    'v': M('#4FA33A'),              # leaf green
    'n': M('#2C6A2A'),              # dark green
    'l': M('#8D5631'),              # leather
    'c': M('#B4452F'),              # canyon rock (Torrhus)
    'u': M('#9A5B33'),              # clay / terracotta
    'm': M('#D3343C'),              # mushroom red
    'f': M('#F0DFC0'),              # cream / stem
    'z': M('#F3F3F0'),              # white
    'a': M('#8B8D92'),              # stone grey
    'j': M('#EC86C2'),              # pink
    'e': M('#43D17A'),              # gem (overridden per item)
    '*': M('#FFFFFF', flat=True),   # sparkle
    '#': M('#141018', flat=True),   # pupil / ink
    '@': M('#FFF6B0', flat=True),   # glow
}


def palette(**over):
    p = dict(BASE)
    for k, v in over.items():
        key = {'star': '*', 'ink': '#', 'glow': '@'}.get(k, k)
        p[key] = v if isinstance(v, Material) else M(v)
    return p
