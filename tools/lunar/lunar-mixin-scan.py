"""List Lunar Client mixin classes that use @Redirect / @Overwrite / @WrapOperation and the Minecraft names they reference.

Crude constant-pool string scan (Lunar's classes are obfuscated, but mixin annotation values carry Minecraft names).
Usage: python -I lunar-scan.py <jar>...
"""
import re
import struct
import sys
import zipfile


def utf8_constants(data):
    out = []
    if data[:4] != b'\xca\xfe\xba\xbe':
        return out
    count = struct.unpack('>H', data[8:10])[0]
    i, idx = 10, 1
    while idx < count:
        tag = data[i]
        if tag == 1:
            ln = struct.unpack('>H', data[i + 1:i + 3])[0]
            out.append(data[i + 3:i + 3 + ln].decode('utf-8', 'replace'))
            i += 3 + ln
        elif tag in (3, 4):
            i += 5
        elif tag in (5, 6):
            i += 9
            idx += 1
        elif tag in (7, 8, 16, 19, 20):
            i += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            i += 5
        elif tag == 15:
            i += 4
        else:
            break
        idx += 1
    return out


KINDS = {
    'Lorg/spongepowered/asm/mixin/injection/Redirect;': 'Redirect',
    'Lorg/spongepowered/asm/mixin/Overwrite;': 'Overwrite',
    'Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;': 'WrapOperation',
    'Lorg/spongepowered/asm/mixin/injection/ModifyArg;': 'ModifyArg',
    'Lorg/spongepowered/asm/mixin/injection/ModifyVariable;': 'ModifyVariable',
}

for jar in sys.argv[1:]:
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if not n.endswith('.class'):
                continue
            cs = utf8_constants(z.read(n))
            if 'Lorg/spongepowered/asm/mixin/Mixin;' not in cs:
                continue
            kinds = sorted({KINDS[c] for c in cs if c in KINDS})
            if not kinds:
                continue
            mc = sorted({c for c in cs if 'net/minecraft/' in c or 'com/mojang/' in c})
            targets = [c for c in cs if re.match(r'^L?net/minecraft/[^;]*;?[a-zA-Z_$]+\(', c) or re.match(r'^[a-zA-Z_$]+\(.*\)', c)]
            print(f'{jar.split("/")[-1]} {n} {kinds}')
            for t in targets:
                print('    at', t)
            for c in mc[:12]:
                print('    ref', c)
