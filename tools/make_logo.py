#!/usr/bin/env python3
"""The title-screen logo (UPWARDLY + the tagline) as artwork with a transparent background.

Draws exactly what TitleScreen.render draws, from the game's own pixel font (PixelFont.java, read through
make_fonts.py): the title at 15 game pixels per font pixel in its four layers (black 60 % fringe at +21/-23, brown
#591F05 at +6/-8, dark #331200 90 % at +15/-15, gold #FFBD29 face), and the tagline 56 game pixels below it,
centred, at 3.2 game pixels per font pixel (as measured on the owner's screen) in cream #FFFAEB over a black 55 %
shadow one font pixel right and down. Nothing else (no sky, no bob).

Writes, per variant, to branding/logo:
  .svg  vector, any size without loss (Photoshop: File > Place Embedded keeps it a vector smart object)
  .pdf  vector, the same artwork (Photoshop places a PDF as a vector smart object too)
  .png  20x the game's size, transparent, every pixel edge on whole pixels

Usage: python3 tools/make_logo.py [--out branding/logo] [--scale 20]
Needs: pip install pillow reportlab   (and fonttools for make_fonts' import)
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_fonts as mf  # noqa: E402

TITLE_PX, TAG_PX, TAG_DROP = 15.0, 3.2, 56.0
TITLE = [  # (offset x, offset y in game px, y up), colour, opacity: bottom layer first, as the game draws them
    ((21, -23), '#000000', 0.60),
    ((6, -8), '#591F05', 1.0),
    ((15, -15), '#331200', 0.90),
    ((0, 0), '#FFBD29', 1.0),
]
TAG = [((TAG_PX, -TAG_PX), '#000000', 0.55), ((0, 0), '#FFFAEB', 1.0)]
TAGLINES = {'ten-towers': 'TEN TOWERS. ONE CLOCK.', 'trampolines': "TRAMPOLINES, CRABS AND CANNONS. YOU'RE WELCOME."}


def cells(text, g):
    """Font-pixel cells (column, row up from the baseline) of a line of text."""
    out = set()
    for i, ch in enumerate(text):
        rows = g[mf.ALIASES.get(ch, ch)]
        for r, row in enumerate(rows):
            for c, bit in enumerate(row):
                if bit == '1':
                    out.add((i * mf.ADV + c, mf.GH - 1 - r))
    return out


def layers(tagline, g):
    """Every layer of the logo, bottom first: (contours in game px with y up, colour, opacity)."""
    tw = len('UPWARDLY') * mf.ADV * TITLE_PX - TITLE_PX
    out = []
    tc = cells('UPWARDLY', g)
    title = mf.trace(tc)
    for (dx, dy), col, a in TITLE:
        out.append(([[(x * TITLE_PX + dx, y * TITLE_PX + dy) for x, y in c] for c in title], col, a,
                    [(x * TITLE_PX + dx, y * TITLE_PX + dy, TITLE_PX) for x, y in tc]))
    if tagline:
        gw = len(tagline) * mf.ADV * TAG_PX - TAG_PX
        x0, y0 = tw / 2 - gw / 2, -TAG_DROP
        gc = cells(tagline, g)
        tag = mf.trace(gc)
        for (dx, dy), col, a in TAG:
            out.append(([[(x0 + x * TAG_PX + dx, y0 + y * TAG_PX + dy) for x, y in c] for c in tag], col, a,
                        [(x0 + x * TAG_PX + dx, y0 + y * TAG_PX + dy, TAG_PX) for x, y in gc]))
    return out


def bounds(lay, margin):
    xs = [p[0] for cs, _, _, _ in lay for c in cs for p in c]
    ys = [p[1] for cs, _, _, _ in lay for c in cs for p in c]
    import math
    return (math.floor(min(xs) - margin), math.floor(min(ys) - margin), math.ceil(max(xs) + margin), math.ceil(max(ys) + margin))


def fmt(v):
    return ('%.3f' % v).rstrip('0').rstrip('.')


def write_svg(lay, box, path):
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %s %s" width="%s" height="%s">'
             % (fmt(w), fmt(h), fmt(w * 10), fmt(h * 10))]
    for cs, col, a, _ in lay:
        d = ''.join('M' + 'L'.join('%s %s' % (fmt(x - x0), fmt(y1 - y)) for x, y in c) + 'Z' for c in cs)
        parts.append('<path fill="%s"%s d="%s"/>' % (col, ' fill-opacity="%g"' % a if a < 1 else '', d))
    parts.append('</svg>')
    open(path, 'w').write('\n'.join(parts) + '\n')


def write_pdf(lay, box, path):
    from reportlab.pdfgen import canvas
    x0, y0, x1, y1 = box
    k = 10.0          # 10 points per game pixel: the page is about 7,500 x 2,400 pt; any size prints from vectors
    c = canvas.Canvas(path, pagesize=((x1 - x0) * k, (y1 - y0) * k))
    c.setTitle('Upwardly logo')
    c.setAuthor('Hot Attic Games')
    for cs, col, a, _ in lay:
        c.setFillColor(col)
        c.setFillAlpha(a)
        p = c.beginPath()
        for cont in cs:
            p.moveTo((cont[0][0] - x0) * k, (cont[0][1] - y0) * k)
            for x, y in cont[1:]:
                p.lineTo((x - x0) * k, (y - y0) * k)
            p.close()
        c.drawPath(p, stroke=0, fill=1, fillMode=0)
    c.showPage()
    c.save()


def write_png(lay, box, path, scale):
    from PIL import Image, ImageDraw
    x0, y0, x1, y1 = box
    W, H = round((x1 - x0) * scale), round((y1 - y0) * scale)
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    for _, col, a, rects in lay:
        mask = Image.new('L', (W, H), 0)
        d = ImageDraw.Draw(mask)
        for x, y, size in rects:            # each font pixel a rectangle, right and bottom edges exclusive
            l, t = round((x - x0) * scale), round((y1 - y - size) * scale)
            r, b = round((x + size - x0) * scale), round((y1 - y) * scale)
            d.rectangle([l, t, r - 1, b - 1], fill=255)
        layer = Image.new('RGBA', (W, H), tuple(int(col[i:i + 2], 16) for i in (1, 3, 5)) + (round(255 * a),))
        clear = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        img.alpha_composite(Image.composite(layer, clear, mask))
    img.save(path, optimize=True)
    return W, H


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--out', default=os.path.join(mf.ROOT, 'branding', 'logo'))
    ap.add_argument('--scale', type=int, default=20)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    g = mf.all_glyphs()
    variants = [('upwardly-logo', TAGLINES['ten-towers']), ('upwardly-logo-alt-tagline', TAGLINES['trampolines']),
                ('upwardly-title-only', None)]
    for name, tag in variants:
        lay = layers(tag, g)
        box = bounds(lay, 4.0)
        write_svg(lay, box, os.path.join(a.out, name + '.svg'))
        write_pdf(lay, box, os.path.join(a.out, name + '.pdf'))
        w, h = write_png(lay, box, os.path.join(a.out, name + '.png'), a.scale)
        print('wrote %s .svg .pdf .png (%d x %d)' % (name, w, h))


if __name__ == '__main__':
    main()
