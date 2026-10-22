#!/usr/bin/env python3
"""Builds the Upwardly font pack (branding/fonts) from the game's own pixel font.

The game draws every letter from a 5 x 7 bitmap table in core/.../ui/PixelFont.java. This script reads that
table (so the fonts stay in step with the game), adds the characters the game never needed (space, the rest of
ASCII punctuation, a few typographic marks), traces every bitmap into TrueType outlines and writes two families:

  Upwardly Main   the title lettering, in the four layers the title screen stacks (bottom first):
                  Halo (the faded fringe, black 60 %), Edge (the thin brown band), Shadow (the dark one-voxel
                  layer, 90 %), Regular (the gold face).  Solid = face + shadow as one silhouette.
                  Color = all four layers in their colours in one font (COLR/CPAL and OpenType-SVG).
  Upwardly Minor  the line under the title and every other line of in-game text: Shadow (black 55 %) under
                  Regular (the cream face).  Solid.  Color = both layers in one font.

Capitals only, as in the game: lowercase letters show as capitals.  Monospaced: every glyph advances 6 pixels.
Units: 960 per em, 120 per font pixel (8 font pixels per em), so a size that is a multiple of 8 px is pixel
perfect (8 px = one screen pixel per font pixel).  Main's Edge and Halo sit a fraction of a font pixel off the face,
so the whole title stack is pixel perfect at multiples of 120 px (the game draws its title at 120 px).

Usage:  python3 tools/make_fonts.py [--out branding/fonts] [--check] [--chrome PATH] [--specimen] [--zip FILE]
Needs:  pip install fonttools freetype-py pillow   (freetype-py for --check, Pillow for --specimen and --chrome;
        opentype-sanitizer, when installed, adds the OpenType Sanitizer to --check)
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
JAVA = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'hotatticgames', 'climbup', 'ui', 'PixelFont.java')

GW, GH, ADV = 5, 7, 6      # the game's grid: 5 x 7 pixels, 6 pixels of advance (one pixel of air between letters)
SUB = 15                   # sub-cells per font pixel: the title's extra layers sit 6/15 to 23/15 of a pixel off the face
UNIT = 8                   # font units per sub-cell
PX = SUB * UNIT            # 120 units per font pixel
UPM = 8 * PX               # 960 units per em = 8 font pixels
ASC, DESC = GH * PX, -2 * PX   # 840 / -240: the halo reaches 1.53 px below the baseline, the line is 9 px tall
VERSION = '1.000'
DATE = 'Wed Oct 14 12:00:00 2026'

# The title's layers, in sub-cells, from TitleScreen.render (scale 15, so 1 screen px = 1 sub-cell at 120 px):
#   drawShadow(t, tx + 6, ty - 8, 15, brown, black 60 %) then drawShadow(t, tx, ty, 15, gold, dark brown 90 %),
# and drawShadow draws its shadow one voxel right and down before the colour, so the title stacks, bottom first:
#   Halo    black 60 %         at (+21, -23)   the faded fringe (slate blue over the sky)
#   Edge    brown #591F05      at (+6, -8)     the thin warm band just under the face
#   Shadow  dark #331200 90 %  at (+15, -15)   the one-voxel dark layer
#   Regular gold #FFBD29       at (0, 0)       the face
# Every other line of text in the game (Ui.text) is the cream face over a black 55 % shadow one voxel off.
FAMILIES = {
    'Main': {'Regular': [(0, 0)], 'Edge': [(6, -8)], 'Shadow': [(SUB, -SUB)], 'Halo': [(21, -23)], 'Solid': [(0, 0), (SUB, -SUB)]},
    'Minor': {'Regular': [(0, 0)], 'Shadow': [(SUB, -SUB)], 'Solid': [(0, 0), (SUB, -SUB)]},
}
STACK = {'Main': ['Halo', 'Edge', 'Shadow', 'Regular'], 'Minor': ['Shadow', 'Regular']}     # bottom layer first
STYLE_NOTE = {
    'Regular': 'the face', 'Edge': 'the thin brown band just under the face', 'Shadow': 'the one-voxel dark layer under the face',
    'Halo': 'the faded dark fringe under the shadow', 'Solid': 'face and shadow as one silhouette',
    'Color': 'every layer in its colour in one font',
}
# What the game colours each layer (TitleScreen for Main, Ui.TEXT / Ui.SHADOW for Minor): colour, opacity %.
COLOURS = {
    ('Main', 'Regular'): ('#FFBD29', 100), ('Main', 'Edge'): ('#591F05', 100), ('Main', 'Shadow'): ('#331200', 90),
    ('Main', 'Halo'): ('#000000', 60), ('Main', 'Solid'): ('#331200', 100),
    ('Minor', 'Regular'): ('#FFFAEB', 100), ('Minor', 'Shadow'): ('#000000', 55), ('Minor', 'Solid'): ('#FFFAEB', 100),
}

# Characters the game never needed, drawn on the same grid in the same style (one-pixel strokes, cut corners).
EXTRA = {
    ' ': '00000,00000,00000,00000,00000,00000,00000',
    '"': '01010,01010,00000,00000,00000,00000,00000',
    '#': '01010,01010,11111,01010,11111,01010,01010',
    '$': '00100,01111,10100,01110,00101,11110,00100',
    '*': '00000,10101,01110,11111,01110,10101,00000',
    ';': '00000,01100,01100,00000,01100,00100,01000',
    '@': '01110,10001,00001,01101,10101,10101,01110',
    '[': '01110,01000,01000,01000,01000,01000,01110',
    '\\': '10000,01000,01000,00100,00010,00010,00001',
    ']': '01110,00010,00010,00010,00010,00010,01110',
    '^': '00100,01010,10001,00000,00000,00000,00000',
    '`': '01000,00100,00010,00000,00000,00000,00000',
    '{': '00010,00100,00100,01000,00100,00100,00010',
    '|': '00100,00100,00100,00100,00100,00100,00100',
    '}': '01000,00100,00100,00010,00100,00100,01000',
    '~': '00000,00000,01000,10101,00010,00000,00000',
    '•': '00000,00000,01110,01110,01110,00000,00000',   # bullet
    '…': '00000,00000,00000,00000,00000,00000,10101',   # ellipsis
    '©': '01110,10001,10111,10100,10111,10001,01110',   # copyright
    '®': '01110,10001,10111,10101,10110,10101,01110',   # registered
    '™': '11111,00100,00100,00000,10001,11011,10101',   # trade mark
    '°': '01100,10010,10010,01100,00000,00000,00000',   # degree
    '×': '00000,10001,01010,00100,01010,10001,00000',   # multiply
    '÷': '00000,00100,00000,11111,00000,00100,00000',   # divide
    '±': '00100,00100,11111,00100,00100,00000,11111',   # plus-minus
    '£': '00110,01001,01000,11110,01000,01000,11111',   # pound
    '€': '00110,01001,11100,01000,11100,01001,00110',   # euro
    '¥': '10001,01010,00100,11111,00100,11111,00100',   # yen
    '¢': '00100,01111,10100,10100,10100,01111,00100',   # cent
    '¿': '00100,00000,00100,01000,10000,10001,01110',   # inverted question mark
    '¡': '00100,00000,00100,00100,00100,00100,00100',   # inverted exclamation mark
}
NOTDEF = '11111,10001,10001,10001,10001,10001,11111'.split(',')

NAMES = {
    '.': 'period', ',': 'comma', ':': 'colon', '!': 'exclam', '?': 'question', '-': 'hyphen', '+': 'plus', '/': 'slash',
    '(': 'parenleft', ')': 'parenright', '%': 'percent', "'": 'quotesingle', '=': 'equal', '<': 'less', '>': 'greater',
    '_': 'underscore', '&': 'ampersand', ' ': 'space', '"': 'quotedbl', '#': 'numbersign', '$': 'dollar', '*': 'asterisk',
    ';': 'semicolon', '@': 'at', '[': 'bracketleft', '\\': 'backslash', ']': 'bracketright', '^': 'asciicircum',
    '`': 'grave', '{': 'braceleft', '|': 'bar', '}': 'braceright', '~': 'asciitilde', '•': 'bullet',
    '…': 'ellipsis', '©': 'copyright', '®': 'registered', '™': 'trademark', '°': 'degree',
    '×': 'multiply', '÷': 'divide', '±': 'plusminus', '£': 'sterling', '€': 'Euro',
    '¥': 'yen', '¢': 'cent', '¿': 'questiondown', '¡': 'exclamdown',
    '0': 'zero', '1': 'one', '2': 'two', '3': 'three', '4': 'four', '5': 'five', '6': 'six', '7': 'seven', '8': 'eight', '9': 'nine',
}
# Code points that share a glyph: lowercase shows as capitals (the game upper-cases everything it prints), curly
# quotes and dashes use the straight ones, the no-break space is a space.
ALIASES = {chr(c): chr(c - 32) for c in range(ord('a'), ord('z') + 1)}
ALIASES.update({'‘': "'", '’': "'", '′': "'", '“': '"', '”': '"', '″': '"',
                '‐': '-', '‑': '-', '–': '-', '—': '-', '−': '-', ' ': ' ', '­': '-'})


def read_game_glyphs():
    """The game's table: char -> 7 rows of 5 bits, top row first, as PixelFont.java holds them."""
    src = open(JAVA, encoding='utf-8').read()
    chars = re.search(r'CHARS\s*=\s*"([^"]*)"', src).group(1)
    rows = re.findall(r'"([01]{5}(?:,[01]{5}){6})"', src)
    if len(rows) != len(chars):
        sys.exit('PixelFont.java: %d chars but %d bitmaps' % (len(chars), len(rows)))
    g = {c: r.split(',') for c, r in zip(chars, rows)}
    # The game's table has these two mirrored (its '<' points right). The game never prints either character,
    # so nothing shows in play; a font must have them the right way round.
    g['<'], g['>'] = g['>'], g['<']
    return g


def all_glyphs():
    g = read_game_glyphs()
    for c, rows in EXTRA.items():
        g[c] = rows.split(',')
    for c, rows in g.items():
        if len(rows) != GH or any(len(r) != GW or set(r) - {'0', '1'} for r in rows):
            sys.exit('bad bitmap for %r' % c)
    return g


def charset(g):
    """Glyph order after .notdef: space, letters, digits, then the rest in code-point order."""
    rest = sorted((c for c in g if not (c.isalnum() or c == ' ')), key=ord)
    return [' '] + [chr(c) for c in range(ord('A'), ord('Z') + 1)] + list('0123456789') + rest


def glyph_name(c):
    return NAMES.get(c, c)


def rgba(family, style):
    """(r, g, b) 0-255 and alpha 0-1 of a layer."""
    colour, alpha = COLOURS[(family, style)]
    return tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5)) + (alpha / 100.0,)


def cells_of(rows, layers):
    """Every filled sub-cell (x right, y up, baseline at y = 0) of the bitmap drawn once per layer offset."""
    cells = set()
    for dx, dy in layers:
        for r, row in enumerate(rows):
            for c, bit in enumerate(row):
                if bit == '1':
                    bx, by = c * SUB + dx, (GH - 1 - r) * SUB + dy
                    for sx in range(SUB):
                        for sy in range(SUB):
                            cells.add((bx + sx, by + sy))
    return cells


def trace(cells):
    """Outlines of a set of unit cells: outer contours clockwise, holes anticlockwise (TrueType's convention).
    Each cell contributes its four edges clockwise; an edge shared by two cells cancels; what is left is chained,
    taking the right-hand turn at a pinch point so diagonally touching cells stay separate squares."""
    edges = {}
    for x, y in cells:
        for p, q in (((x, y), (x, y + 1)), ((x, y + 1), (x + 1, y + 1)), ((x + 1, y + 1), (x + 1, y)), ((x + 1, y), (x, y))):
            if (q, p) in edges:
                del edges[(q, p)]
            else:
                edges[(p, q)] = True
    out = defaultdict(list)
    for p, q in edges:
        out[p].append(q)
    contours = []
    while edges:
        p, q = next(iter(edges))
        del edges[(p, q)]
        out[p].remove(q)
        start, path, cur, d = p, [p], q, (q[0] - p[0], q[1] - p[1])
        while cur != start:
            path.append(cur)
            nxt = None
            for t in ((d[1], -d[0]), d, (-d[1], d[0])):        # right turn, straight on, left turn
                cand = (cur[0] + t[0], cur[1] + t[1])
                if cand in out[cur]:
                    nxt = cand
                    break
            if nxt is None:
                raise RuntimeError('open contour at %r' % (cur,))
            out[cur].remove(nxt)
            del edges[(cur, nxt)]
            d, cur = (nxt[0] - cur[0], nxt[1] - cur[1]), nxt
        contours.append(simplify(path))
    return contours


def simplify(path):
    """Drops the points along straight runs of a closed path."""
    n, keep = len(path), []
    for i in range(n):
        a, b, c = path[i - 1], path[i], path[(i + 1) % n]
        if (b[0] - a[0], b[1] - a[1]) != (c[0] - b[0], c[1] - b[1]):
            keep.append(b)
    return keep


def tt_glyph(contours):
    from fontTools.pens.ttGlyphPen import TTGlyphPen
    pen = TTGlyphPen(None)
    for cont in contours:
        pen.moveTo((cont[0][0] * UNIT, cont[0][1] * UNIT))
        for p in cont[1:]:
            pen.lineTo((p[0] * UNIT, p[1] * UNIT))
        pen.closePath()
    return pen.glyph()


def x_min(contours):
    return min((p[0] for cont in contours for p in cont), default=0) * UNIT


def svg_d(contours):
    """SVG path data in font units with y down (the OpenType-SVG glyph space). Every edge is horizontal or vertical."""
    parts = []
    for cont in contours:
        px_, py_ = cont[0]
        parts.append('M%d %d' % (px_ * UNIT, -py_ * UNIT))
        for x, y in cont[1:]:
            parts.append('H%d' % (x * UNIT) if y == py_ else 'V%d' % (-y * UNIT))
            px_, py_ = x, y
        parts.append('Z')
    return ''.join(parts)


def file_name(family, style):
    return 'Upwardly%s-%s.ttf' % (family, style)


def new_builder(order, g):
    from fontTools.fontBuilder import FontBuilder
    fb = FontBuilder(UPM, isTTF=True)
    fb.setupGlyphOrder(order)
    cs = charset(g)
    cmap = {ord(c): glyph_name(c) for c in cs}
    cmap.update({ord(k): glyph_name(v) for k, v in ALIASES.items()})
    fb.setupCharacterMap(cmap)
    return fb


def finish(fb, family, style, out_dir):
    """Names, metrics tables and the small hinting tables every file shares; saves the font."""
    from fontTools.ttLib import newTable
    from fontTools.ttLib.tables.ttProgram import Program
    from fontTools.misc.timeTools import timestampFromString
    fb.setupHorizontalHeader(ascent=ASC, descent=DESC)
    fam = 'Upwardly ' + family
    full = fam if style == 'Regular' else fam + ' ' + style
    ps = 'Upwardly%s-%s' % (family, style)
    what = 'the title lettering' if family == 'Main' else 'the smaller in-game text'
    fb.setupNameTable(dict(
        copyright='Copyright 2026 Hot Attic Games',
        familyName=full, styleName='Regular',          # legacy family per file so every app lists every layer
        typographicFamily=fam, typographicSubfamily=style,
        uniqueFontIdentifier='%s;HOTA;%s' % (VERSION, ps), fullName=full, version='Version ' + VERSION, psName=ps,
        manufacturer='Hot Attic Games', designer='Hot Attic Games',
        description='%s of the game Upwardly (%s): a 5 x 7 pixel grid, capitals only (lowercase shows as capitals), '
                    'monospaced. Pixel perfect at sizes that are multiples of 8 px; the game draws its title at 120 px.'
                    % (what[0].upper() + what[1:], STYLE_NOTE[style]),
        licenseDescription='Copyright Hot Attic Games.',
        sampleText='UPWARDLY'))
    fb.setupOS2(version=4, fsType=0, achVendID='HOTA', usWeightClass=400, usWidthClass=5,
                sTypoAscender=ASC, sTypoDescender=DESC, sTypoLineGap=0, usWinAscent=ASC, usWinDescent=-DESC,
                sxHeight=ASC, sCapHeight=ASC, fsSelection=0x40 | 0x80, ulCodePageRange1=1)
    fb.setupPost(isFixedPitch=1, underlinePosition=-PX, underlineThickness=PX)
    fb.font['OS/2'].recalcUnicodeRanges(fb.font)
    # A one-instruction prep program (SVTCA[y], a no-op here): with a program present, renderers that would auto-hint
    # an unhinted TrueType font draw the outlines as traced. gasp: smooth at every size (the pixels are crisp anyway).
    prep = newTable('prep')
    prep.program = Program()
    prep.program.fromBytecode(b'\x00')
    fb.font['prep'] = prep
    gasp = newTable('gasp')
    gasp.version, gasp.gaspRange = 1, {0xFFFF: 0x000F}
    fb.font['gasp'] = gasp
    head = fb.font['head']
    head.fontRevision = float(VERSION)
    head.lowestRecPPEM = 8
    head.created = head.modified = timestampFromString(DATE)
    fb.font.recalcTimestamp = False
    path = os.path.join(out_dir, file_name(family, style))
    fb.save(path)
    return path


def build(family, style, layers, g, out_dir):
    """One layer of a family as a plain (one-colour) font."""
    cs = charset(g)
    fb = new_builder(['.notdef'] + [glyph_name(c) for c in cs], g)
    glyf, metrics = {}, {}
    for name, rows, lay in [('.notdef', NOTDEF, [(0, 0)])] + [(glyph_name(c), g[c], layers) for c in cs]:
        contours = trace(cells_of(rows, lay))
        glyf[name], metrics[name] = tt_glyph(contours), (ADV * PX, x_min(contours))
    fb.setupGlyf(glyf)
    fb.setupHorizontalMetrics(metrics)
    return finish(fb, family, style, out_dir)


def build_color(family, g, out_dir):
    """Every layer of a family stacked in its colour in one font: a COLR/CPAL table (Windows, browsers, FreeType)
    and an OpenType-SVG table with the same layers (Photoshop, Illustrator, Firefox, macOS). The plain outline of
    each character is the face, which is what an app without colour-font support shows."""
    from fontTools.colorLib.builder import buildCOLR, buildCPAL
    from fontTools.ttLib import newTable
    from fontTools.ttLib.tables.S_V_G_ import SVGDocument
    cs = charset(g)
    stack = STACK[family]
    bases = [glyph_name(c) for c in cs]

    def lname(n, s):
        return '%s.%s' % (n, s.lower())

    order = ['.notdef'] + bases + [lname(n, s) for s in stack for n in bases]
    fb = new_builder(order, g)
    glyf, metrics, lay = {}, {}, {}

    def add(name, contours):
        glyf[name], metrics[name] = tt_glyph(contours), (ADV * PX, x_min(contours))

    add('.notdef', trace(cells_of(NOTDEF, [(0, 0)])))
    for c, n in zip(cs, bases):
        add(n, trace(cells_of(g[c], [(0, 0)])))
        for s in stack:
            lay[(n, s)] = trace(cells_of(g[c], FAMILIES[family][s]))
            add(lname(n, s), lay[(n, s)])
    fb.setupGlyf(glyf)
    fb.setupHorizontalMetrics(metrics)
    inked = [n for n in bases if lay[(n, stack[-1])]]
    colours = [rgba(family, s) for s in stack]
    fb.font['CPAL'] = buildCPAL([[(r / 255.0, gg / 255.0, b / 255.0, a) for r, gg, b, a in colours]])
    fb.font['COLR'] = buildCOLR({n: [(lname(n, s), i) for i, s in enumerate(stack)] for n in inked}, version=0)
    gid = {name: i for i, name in enumerate(order)}
    docs = []
    for n in inked:
        paths = ''.join('<path fill="#%02X%02X%02X"%s d="%s"/>'
                        % (r, gg, b, ' fill-opacity="%g"' % a if a < 1 else '', svg_d(lay[(n, s)]))
                        for (r, gg, b, a), s in zip(colours, stack))
        docs.append(SVGDocument('<svg xmlns="http://www.w3.org/2000/svg" version="1.1" id="glyph%d">%s</svg>'
                                % (gid[n], paths), gid[n], gid[n], False))
    svg = newTable('SVG ')
    svg.docList = docs
    fb.font['SVG '] = svg
    return finish(fb, family, 'Color', out_dir)


def expected_pixels(rows, layers, k):
    """The screen pixels a glyph should fill at k screen pixels per font pixel (k = size / 8), x right, y up from
    the baseline. Layers that sit between font pixels (Main's Edge and Halo) are exact only when k is a multiple of SUB."""
    px = set()
    for dx, dy in layers:
        for r, row in enumerate(rows):
            for c, bit in enumerate(row):
                if bit == '1':
                    bx, by = c * k + dx * k // SUB, (GH - 1 - r) * k + dy * k // SUB
                    for sx in range(k):
                        for sy in range(k):
                            px.add((bx + sx, by + sy))
    return px


def sizes_for(layers_list):
    """Sizes at which every layer lands on whole pixels: 8, 32 and 120 px, or 120 px only for Main's fractional layers."""
    frac = any(dx % SUB or dy % SUB for layers in layers_list for dx, dy in layers)
    return (UPM // UNIT,) if frac else (8, 32, UPM // UNIT)


def check(out_dir, g):
    """FreeType renders every glyph of every file with hinting off (the outlines as drawn, which is what Windows,
    Photoshop and the game's look amount to) and the result is compared pixel for pixel with the bitmap the game
    draws; the lowercase and typographic aliases too. The colour fonts are rendered in colour (COLR layers blended
    by FreeType) and compared with the game's layers blended in the game's order and colours. Then the plain files
    again through FreeType's auto-hinter, which some Linux apps apply: deviations there are notes, not failures.
    Last, the OpenType Sanitizer (the font checker browsers run) when it is installed. Returns the number of failures."""
    import freetype
    cs = charset(g)
    probes = [(c, g[c]) for c in cs] + [(k, g[v]) for k, v in ALIASES.items() if k in 'aqz’— ']
    bad, total, notes = 0, 0, []
    for family, styles in FAMILIES.items():
        for style, layers in styles.items():
            face = freetype.Face(os.path.join(out_dir, file_name(family, style)))
            for size in sizes_for([layers]):
                face.set_char_size(size * 64)
                k = size // 8
                for c, rows in probes:
                    want = expected_pixels(rows, layers, k)
                    for mode, flags in (('unhinted', freetype.FT_LOAD_NO_HINTING), ('auto-hinted', freetype.FT_LOAD_FORCE_AUTOHINT)):
                        face.load_char(c, flags | freetype.FT_LOAD_RENDER)
                        bm, left, top = face.glyph.bitmap, face.glyph.bitmap_left, face.glyph.bitmap_top
                        buf, nrows, width, pitch = bm.buffer, bm.rows, bm.width, bm.pitch      # buffer copies on every access
                        got = {(left + x, top - 1 - y) for y in range(nrows) for x in range(width) if buf[y * pitch + x] >= 128}
                        if mode == 'unhinted':
                            total += 1
                            if got != want:
                                bad += 1
                                print('MISMATCH %s %s %r at %d px: %d pixels differ' % (family, style, c, size, len(got ^ want)))
                        elif got != want:
                            notes.append('%s %s %r at %d px' % (family, style, c, size))
    for family in FAMILIES:
        face = freetype.Face(os.path.join(out_dir, file_name(family, 'Color')))
        stack = STACK[family]
        for size in sizes_for([FAMILIES[family][s] for s in stack]):
            face.set_char_size(size * 64)
            k = size // 8
            for c, rows in probes:
                want = {}
                for s in stack:                                  # premultiplied RGBA, bottom layer first
                    r, gg, b, a = rgba(family, s)
                    for p in expected_pixels(rows, FAMILIES[family][s], k):
                        d = want.get(p, (0.0, 0.0, 0.0, 0.0))
                        want[p] = (r * a + d[0] * (1 - a), gg * a + d[1] * (1 - a), b * a + d[2] * (1 - a), 255 * a + d[3] * (1 - a))
                face.load_char(c, freetype.FT_LOAD_COLOR | freetype.FT_LOAD_NO_HINTING | freetype.FT_LOAD_RENDER)
                bm, left, top = face.glyph.bitmap, face.glyph.bitmap_left, face.glyph.bitmap_top
                total += 1
                if want and bm.pixel_mode != freetype.FT_PIXEL_MODE_BGRA:
                    bad += 1
                    print('MISMATCH %s Color %r at %d px: not rendered in colour' % (family, c, size))
                    continue
                buf, nrows, width, pitch = bm.buffer, bm.rows, bm.width, bm.pitch
                got = {}
                for y in range(nrows):
                    for x in range(width):
                        i = y * pitch + 4 * x
                        if buf[i + 3]:
                            got[(left + x, top - 1 - y)] = (buf[i + 2], buf[i + 1], buf[i], buf[i + 3])
                off = [p for p in set(got) | set(want)
                       if max(abs(u - v) for u, v in zip(got.get(p, (0, 0, 0, 0)), want.get(p, (0, 0, 0, 0)))) > 2]
                if off:
                    bad += 1
                    print('MISMATCH %s Color %r at %d px: %d pixels differ' % (family, c, size, len(off)))
    print('CHECK %s: %d glyph renders through FreeType %s (hinting off; colour fonts in colour), %d wrong'
          % ('PASS' if bad == 0 else 'FAIL', total, '.'.join(map(str, freetype.version())), bad))
    if notes:
        print('NOTE: FreeType\'s auto-hinter (some Linux apps; not Windows, not Photoshop) moves a pixel in %d render(s): %s'
              % (len(notes), ', '.join(notes)))
    try:
        import ots
    except ImportError:
        print('NOTE: opentype-sanitizer not installed; the OpenType Sanitizer was skipped')
        return bad
    files = sorted(f for f in os.listdir(out_dir) if f.endswith('.ttf'))
    tmp = tempfile.mkdtemp()
    failed = []
    for f in files:
        r = ots.sanitize(os.path.join(out_dir, f), os.path.join(tmp, f), capture_output=True)
        if r.returncode != 0:
            failed.append(f)
            print('OTS FAIL %s: %s' % (f, (r.stderr or b'').decode(errors='replace').strip()))
    shutil.rmtree(tmp, ignore_errors=True)
    print('OTS %s: %d files through the OpenType Sanitizer, %d rejected' % ('PASS' if not failed else 'FAIL', len(files), len(failed)))
    return bad + len(failed)


def chrome_check(out_dir, g, chrome, keep=None):
    """A real browser's view: Chromium draws the two colour fonts from their COLR tables (as text in a web page) and
    each glyph's OpenType-SVG document from the SVG table as it is stored (the tables Photoshop reads), over a flat
    sky colour; every pixel is compared with the game's layers blended in the game's order and colours.
    Returns the number of lines that differ."""
    from fontTools.ttLib import TTFont
    from PIL import Image
    bg = (97, 167, 236)
    tests = [('Main', 'UPWARDLY', 120), ('Minor', 'TEN TOWERS. ONE CLOCK.', 32)]
    tmp = tempfile.mkdtemp()
    html = ['<!doctype html><html><head><meta charset="utf-8"><style>',
            'html,body{margin:0;background:rgb(%d,%d,%d)}' % bg,
            '.t{position:absolute;left:40px;line-height:normal;white-space:pre;font-kerning:none;margin:0}',
            'svg.s{position:absolute;left:0;top:0;overflow:visible}svg.s svg{overflow:visible}']
    body, top, lines = [], 20, []
    for family, text, size in tests:
        shutil.copy(os.path.join(out_dir, file_name(family, 'Color')), tmp)
        html.append("@font-face{font-family:U%s;src:url('%s')}" % (family, file_name(family, 'Color')))
        body.append('<div class="t" style="top:%dpx;font:%dpx U%s">%s</div>' % (top, size, family, text))
        lines.append((family, text, size, 'COLR', top, top + size * 2))
        top += size * 2
        font = TTFont(os.path.join(out_dir, file_name(family, 'Color')))
        docs = {d.startGlyphID: d.data for d in font['SVG '].docList}
        gids = font.getReverseGlyphMap()
        cmap = font.getBestCmap()
        scale = size / float(UPM)
        base = top + size
        parts = []
        for i, c in enumerate(text):
            d = docs.get(gids[cmap[ord(c)]])
            if d:
                parts.append('<g transform="translate(%g %d) scale(%g)">%s</g>' % (40 + i * ADV * size / 8.0, base, scale, d))
        body.append('<svg class="s" width="1400" height="%d">%s</svg>' % (top + size * 2, ''.join(parts)))
        lines.append((family, text, size, 'SVG', top, top + size * 2))
        top += size * 2
    html.append('</style></head><body>')
    html += body
    html.append('</body></html>')
    page = os.path.join(tmp, 'index.html')
    open(page, 'w', encoding='utf-8').write('\n'.join(html))
    shot = os.path.join(tmp, 'shot.png')
    subprocess.run([chrome, '--headless', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
                    '--allow-file-access-from-files', '--font-render-hinting=none', '--virtual-time-budget=4000',
                    '--window-size=1400,%d' % (top + 200), '--screenshot=' + shot, 'file://' + page],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    img = Image.open(shot).convert('RGB')
    pix = img.load()
    bad = 0
    for family, text, size, kind, y0, y1 in lines:
        k = size // 8
        canvas = {}
        for s in STACK[family]:                     # the game's order: each layer across the whole line, bottom first
            r, gg, b, a = rgba(family, s)
            for i, c in enumerate(text):
                for (x, y) in expected_pixels(g[c], FAMILIES[family][s], k):
                    p = (i * ADV * k + x, y)
                    d = canvas.get(p, bg)
                    canvas[p] = (r * a + d[0] * (1 - a), gg * a + d[1] * (1 - a), b * a + d[2] * (1 - a))
        face = rgba(family, 'Regular')[:3]
        first = expected_pixels(g[text[0]], [(0, 0)], k)
        ay = max(y for x, y in first)
        ax = min(x for x, y in first if y == ay)
        anchor = next(((x, y) for y in range(y0, min(y1, img.height)) for x in range(img.width)
                       if max(abs(u - v) for u, v in zip(pix[x, y], face)) <= 3), None)
        if anchor is None:
            bad += 1
            print('BROWSER FAIL %s %s: nothing drawn' % (family, kind))
            continue
        ox, base = anchor[0] - ax, anchor[1] + 1 + ay
        xs = [p[0] for p in canvas]
        ys = [p[1] for p in canvas]
        off = 0
        for x in range(min(xs) - 4, max(xs) + 5):
            for y in range(min(ys) - 4, max(ys) + 5):
                sx, sy = ox + x, base - 1 - y
                if 0 <= sx < img.width and 0 <= sy < img.height:
                    want = canvas.get((x, y), bg)
                    if max(abs(u - v) for u, v in zip(pix[sx, sy], want)) > 3:
                        off += 1
        print('BROWSER %s %s %s at %d px: %s' % (family, kind, text, size, 'exact' if off == 0 else '%d pixels differ' % off))
        bad += 1 if off else 0
    if keep:
        shutil.copy(shot, keep)
    shutil.rmtree(tmp, ignore_errors=True)
    print('BROWSER %s: Chromium, %d lines, %d differ' % ('PASS' if bad == 0 else 'FAIL', len(lines), bad))
    return bad


def specimen(out_dir, g):
    """Two sheets: the title and the tagline as the game stacks them, then every character of each family."""
    from PIL import Image, ImageDraw, ImageFont
    cs = charset(g)

    def layered(img, text, family, styles, size, x, y):
        for style in styles:
            r, gg, b, a = rgba(family, style)
            layer = Image.new('RGBA', img.size, (0, 0, 0, 0))
            font = ImageFont.truetype(os.path.join(out_dir, file_name(family, style)), size)
            ImageDraw.Draw(layer).text((x, y), text, font=font, fill=(r, gg, b, round(255 * a)), anchor='ls')
            img.alpha_composite(layer)

    def sky(w, h):
        img = Image.new('RGBA', (w, h))
        d = ImageDraw.Draw(img)
        for y in range(h):
            k = y / h
            d.line([(0, y), (w, y)], fill=(int(70 + 60 * k), int(130 + 70 * k), int(210 + 30 * k), 255))
        return img

    chars = [c for c in cs if c != ' ']
    lines = [''.join(chars[i:i + 16]) for i in range(0, len(chars), 16)]
    for family in FAMILIES:
        top = 290 if family == 'Main' else 250
        img = sky(1280, top + 64 * len(lines) + 24)
        if family == 'Main':
            layered(img, 'UPWARDLY', 'Main', STACK['Main'], 120, 100, 150)
            layered(img, 'TEN TOWERS. ONE CLOCK.', 'Minor', STACK['Minor'], 32, 100, 205)
            layered(img, 'UPWARDLY MAIN: REGULAR OVER SHADOW OVER EDGE OVER HALO, AS THE TITLE SCREEN STACKS THEM',
                    'Minor', STACK['Minor'], 16, 100, 240)
        else:
            layered(img, 'UPWARDLY MINOR', 'Minor', STACK['Minor'], 64, 100, 120)
            layered(img, 'TRAMPOLINES, CRABS AND CANNONS. YOU’RE WELCOME.', 'Minor', STACK['Minor'], 24, 100, 165)
            layered(img, 'UPWARDLY MINOR: REGULAR OVER SHADOW, AS EVERY LINE OF IN-GAME TEXT IS DRAWN',
                    'Minor', STACK['Minor'], 16, 100, 200)
        for i, line in enumerate(lines):
            layered(img, line, family, STACK[family], 48, 100, top + 64 * i)
        img.convert('RGB').save(os.path.join(out_dir, 'specimen-%s.png' % family.lower()))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--out', default=os.path.join(ROOT, 'branding', 'fonts'))
    ap.add_argument('--check', action='store_true', help='render every glyph through FreeType and compare with the game')
    ap.add_argument('--chrome', help='a Chromium binary: also draw the colour fonts in a browser and compare')
    ap.add_argument('--chrome-shot', help='keep the browser screenshot here')
    ap.add_argument('--specimen', action='store_true', help='write specimen-main.png and specimen-minor.png')
    ap.add_argument('--zip', help='also write the pack (fonts, README, pictures) to this zip file')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    g = all_glyphs()
    cs = charset(g)
    files = sum(len(s) for s in FAMILIES.values()) + len(FAMILIES)
    print('%d glyphs (+ .notdef), %d aliases, %d files' % (len(cs), len(ALIASES), files))
    for family, styles in FAMILIES.items():
        for style, layers in styles.items():
            print('wrote', os.path.relpath(build(family, style, layers, g, a.out), ROOT))
        print('wrote', os.path.relpath(build_color(family, g, a.out), ROOT))
    bad = check(a.out, g) if a.check else 0
    if a.chrome:
        bad += chrome_check(a.out, g, a.chrome, a.chrome_shot)
    if a.specimen:
        specimen(a.out, g)
    if a.zip:
        with zipfile.ZipFile(a.zip, 'w', zipfile.ZIP_DEFLATED) as z:
            for name in sorted(os.listdir(a.out)):
                if name.endswith(('.ttf', '.md', '.png')):
                    z.write(os.path.join(a.out, name), 'Upwardly Fontpack/' + name)
        print('wrote', a.zip)
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
