#!/usr/bin/env python3
"""Steam achievement art: the three style proposals (first approval gate).

Three example achievements (castle progression, a funny fall, speedrunning) drawn in three styles, each unlocked and
locked, from the game's own renders (tools/achievements/renders: the hero, castle, star, cloud and platform models
rendered by the desktop IconShot tool; Quaternius and Kenney packs, CC0) and the game's own 5 x 7 pixel lettering.

  python3 tools/achievements/make_concepts.py --out <dir>

Writes style_A.png, style_B.png, style_C.png (comparison sheets) and the 18 individual 256 x 256 icons.
"""
import argparse
import math
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))
import make_fonts as mf  # noqa: E402

R = os.path.join(HERE, 'renders')
N = 1024                     # working canvas; icons are 256 x 256
GOLD, BROWN, DARK, CREAM, NAVY = (255, 189, 41), (89, 31, 5), (51, 18, 0), (255, 250, 235), (20, 20, 42)
SKIES = {  # Palette.SKY_TOP / SKY_BOT of the game's worlds
    'meadow': ((77, 158, 242), (204, 237, 255)),
    'frost': ((133, 184, 242), (230, 245, 255)),
    'dusk': ((56, 41, 107), (252, 148, 102)),
    'night': ((8, 13, 41), (36, 51, 112)),
    'space': ((1, 1, 10), (15, 8, 41)),
}
G = mf.all_glyphs()


def load(name):
    im = Image.open(os.path.join(R, name + '.png')).convert('RGBA')
    return im.crop(im.getbbox())


def fit(im, h=None, w=None):
    k = (h / im.height) if h else (w / im.width)
    return im.resize((max(1, round(im.width * k)), max(1, round(im.height * k))), Image.LANCZOS)


def sky(kind, size=N, stars=False):
    top, bot = SKIES[kind]
    im = Image.new('RGBA', (size, size)); d = ImageDraw.Draw(im)
    for y in range(size):
        t = y / (size - 1)
        d.line([(0, y), (size, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bot)) + (255,))
    if stars:
        import random
        rnd = random.Random(7)
        for _ in range(90):
            x, y, r = rnd.random() * size, rnd.random() * size * 0.8, rnd.choice((2, 3, 3, 4, 6))
            d.rectangle([x, y, x + r, y + r], fill=(255, 255, 255, rnd.randint(120, 255)))
    return im


def paste(dst, im, cx, cy, shadow=True):
    x, y = int(cx - im.width / 2), int(cy - im.height / 2)
    if shadow:
        sh = Image.new('RGBA', im.size, (0, 0, 0, 0)); sh.putalpha(im.getchannel('A').point(lambda v: v * 80 // 255))
        sh = sh.filter(ImageFilter.GaussianBlur(10))
        dst.alpha_composite(sh, (x + 14, y + 16))
    dst.alpha_composite(im, (x, y))


def streaks(dst, boxes, col=(255, 255, 255), a=170):
    lay = Image.new('RGBA', dst.size, (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    for x0, y0, x1, y1, w in boxes:
        d.line([(x0, y0), (x1, y1)], fill=col + (a,), width=w)
        r = w / 2
        d.ellipse([x0 - r, y0 - r, x0 + r, y0 + r], fill=col + (a,)); d.ellipse([x1 - r, y1 - r, x1 + r, y1 + r], fill=col + (a,))
    dst.alpha_composite(lay)


def stopwatch(size):
    """A chunky stopwatch in the title's gold and brown (the run clock), drawn 4x and reduced for clean edges."""
    S = size * 4; im = Image.new('RGBA', (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    c, r = S / 2, S * 0.40
    cy = c + S * 0.06
    d.rounded_rectangle([c - S * 0.07, cy - r - S * 0.15, c + S * 0.07, cy - r + S * 0.02], S * 0.02, fill=BROWN)
    d.rounded_rectangle([c - S * 0.055, cy - r - S * 0.135, c + S * 0.055, cy - r], S * 0.015, fill=GOLD)
    d.ellipse([c - r - S * 0.02, cy - r - S * 0.02, c + r + S * 0.02, cy + r + S * 0.02], fill=BROWN)
    d.ellipse([c - r, cy - r, c + r, cy + r], fill=GOLD)
    d.chord([c - r, cy - r, c + r, cy + r], 20, 200, fill=(230, 150, 20))       # lower-right shade of the rim
    ri = r * 0.80
    d.ellipse([c - ri - S * 0.012, cy - ri - S * 0.012, c + ri + S * 0.012, cy + ri + S * 0.012], fill=BROWN)
    d.ellipse([c - ri, cy - ri, c + ri, cy + ri], fill=CREAM)
    for i in range(12):
        a = i * math.pi / 6; l = ri * (0.78 if i % 3 else 0.68)
        d.line([(c + math.sin(a) * l, cy - math.cos(a) * l), (c + math.sin(a) * ri * 0.92, cy - math.cos(a) * ri * 0.92)], fill=NAVY, width=int(S * (0.025 if i % 3 == 0 else 0.014)))
    d.pieslice([c - ri * 0.86, cy - ri * 0.86, c + ri * 0.86, cy + ri * 0.86], -90, -10, fill=(255, 189, 41, 150))     # the elapsed wedge
    a = math.radians(80)
    d.line([(c, cy), (c + math.sin(a) * ri * 0.78, cy - math.cos(a) * ri * 0.78)], fill=(226, 59, 43), width=int(S * 0.03))
    d.ellipse([c - S * 0.035, cy - S * 0.035, c + S * 0.035, cy + S * 0.035], fill=NAVY)
    d.arc([c - r * 0.9, cy - r * 0.9, c + r * 0.9, cy + r * 0.9], 200, 250, fill=(255, 255, 255, 200), width=int(S * 0.02))
    return im.resize((size, size), Image.LANCZOS)


def title_text(text, cell, face=GOLD, layers=True):
    """Text in the game's 5 x 7 lettering, with the title's layers (fringe, brown edge, dark shadow, gold face)."""
    w = len(text) * mf.ADV - 1; h = mf.GH
    pad = 3
    im = Image.new('RGBA', (int((w + pad * 2) * cell), int((h + pad * 2) * cell)), (0, 0, 0, 0))
    lay = [((1.4, 1.5), (0, 0, 0, 153)), ((0.4, 0.55), BROWN + (255,)), ((1, 1), DARK + (230,)), ((0, 0), face + (255,))] if layers else [((0, 0), face + (255,))]
    for (ox, oy), col in lay:
        d = ImageDraw.Draw(im)
        for i, ch in enumerate(text):
            rows = G[mf.ALIASES.get(ch, ch)]
            for r, row in enumerate(rows):
                for cc, bit in enumerate(row):
                    if bit == '1':
                        x = (pad + i * mf.ADV + cc + ox) * cell; y = (pad + r + oy) * cell
                        d.rectangle([round(x), round(y), round(x + cell) - 1, round(y + cell) - 1], fill=col)
    return im.crop(im.getbbox())


# ---------------------------------------------------------------- the three example scenes (shared by all styles)

def scene(kind, size=N, with_sky=True):
    """(background, figure layer). The figure layer holds the characters and props, transparent elsewhere."""
    fg = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    k = size / N
    if kind == 'castle':                                                     # ACH_TOWER_5 "Halfway Up"
        bg = sky('meadow', size)
        for cx, cy, w in ((180, 210, 300), (820, 330, 240)):
            paste(bg, fit(load('cloud'), w=int(w * k)), cx * k, cy * k, shadow=False)
        plat = fit(load('platform'), w=int(1150 * k)); paste(fg, plat, 512 * k, 935 * k)
        paste(fg, fit(load('castle'), h=int(720 * k)), 420 * k, 520 * k)
        paste(fg, fit(load('bun_yes'), h=int(330 * k)), 790 * k, 700 * k)
    elif kind == 'fall':                                                     # ACH_FIRST_FALL "Gravity: 1, You: 0"
        bg = sky('dusk', size)
        paste(bg, fit(load('cloud'), w=int(260 * k)), 830 * k, 820 * k, shadow=False)
        plat = fit(load('platform'), w=int(700 * k)).rotate(8, expand=True, resample=Image.BICUBIC)
        paste(fg, plat, 200 * k, 150 * k)
        streaks(fg, [(x * k, 120 * k, x * k, (300 + d) * k, int(22 * k)) for x, d in ((420, 40), (520, 0), (630, 80), (720, 10))], a=150)
        bun = fit(load('bun_death'), h=int(560 * k)).rotate(155, expand=True, resample=Image.BICUBIC)
        paste(fg, bun, 560 * k, 600 * k)
        star = load('star')
        for i, (dx, dy, s) in enumerate(((-260, 230, 110), (-160, 330, 80), (210, 300, 95))):
            paste(fg, fit(star, w=int(s * k)).rotate(20 * i - 15, expand=True, resample=Image.BICUBIC), (560 + dx) * k, (600 + dy) * k, shadow=False)
    else:                                                                    # ACH_SPEED_SUMMIT "Express Elevator"
        bg = sky('night', size, stars=True)
        paste(fg, stopwatch(int(640 * k)), 640 * k, 360 * k)
        streaks(fg, [(40 * k, y * k, (40 + l) * k, y * k, int(24 * k)) for y, l in ((560, 300), (650, 380), (740, 260), (830, 340))], col=(140, 220, 255), a=190)
        paste(fg, fit(load('bun_run'), h=int(560 * k)), 470 * k, 690 * k)
    return bg, fg


# ---------------------------------------------------------------- style A: sky diorama

def style_a(kind, locked):
    bg, fg = scene(kind)
    im = bg.copy(); im.alpha_composite(fg)
    vg = Image.new('L', (N, N), 0); ImageDraw.Draw(vg).ellipse([-200, -200, N + 200, N + 200], fill=255)
    vg = ImageOps.invert(vg.filter(ImageFilter.GaussianBlur(120)))
    im = Image.composite(Image.new('RGBA', (N, N), (10, 10, 30, 255)), im, vg.point(lambda v: v * 110 // 255))
    if locked:
        g = ImageOps.grayscale(im.convert('RGB'))
        g = ImageOps.colorize(ImageEnhance.Contrast(g).enhance(0.75), (14, 18, 38), (128, 142, 170))
        im = g.convert('RGBA').filter(ImageFilter.GaussianBlur(1.5))
    if kind == 'castle':                                                     # the tower count in the title lettering
        t = title_text('5', 26, GOLD)
        if locked:
            t = ImageOps.colorize(ImageOps.grayscale(t.convert('RGB')), (14, 18, 38), (150, 160, 190)).convert('RGBA'); t.putalpha(title_text('5', 26).getchannel('A'))
        im.alpha_composite(t, (70, 64))
    outside = Image.new('L', (N, N), 255); ImageDraw.Draw(outside).rounded_rectangle([14, 14, N - 15, N - 15], 70, fill=0)
    im.paste(NAVY + (255,), (0, 0), outside)
    if not locked:
        d = ImageDraw.Draw(im)
        d.rounded_rectangle([14, 14, N - 15, N - 15], 70, outline=BROWN, width=22)
        d.rounded_rectangle([20, 20, N - 21, N - 21], 64, outline=GOLD, width=12)
    else:
        ImageDraw.Draw(im).rounded_rectangle([14, 14, N - 15, N - 15], 70, outline=(70, 78, 104), width=22)
    return im.resize((256, 256), Image.LANCZOS)


# ---------------------------------------------------------------- style B: pixel emblem (the game's HUD and title lettering)

PIX = 64


def emblem_mask(n=PIX, inset=0):
    m = Image.new('L', (n, n), 0); d = ImageDraw.Draw(m)
    c = 9 - inset // 2
    a, b = inset, n - 1 - inset
    d.polygon([(a + c, a), (b - c, a), (b, a + c), (b, b - c), (b - c, b), (a + c, b), (a, b - c), (a, a + c)], fill=255)
    return m


def style_b(kind, locked):
    bg, fg = scene(kind)
    small_bg = bg.resize((PIX, PIX), Image.BOX)
    small_fg = fg.resize((PIX, PIX), Image.BOX)
    small_fg.putalpha(small_fg.getchannel('A').point(lambda v: 255 if v > 110 else 0))
    # dark one-pixel outline around the figures, like the game's HUD sprites
    ol = small_fg.getchannel('A').filter(ImageFilter.MaxFilter(3))
    scene_px = small_bg.copy()
    scene_px.alpha_composite(Image.composite(Image.new('RGBA', (PIX, PIX), NAVY + (255,)), Image.new('RGBA', (PIX, PIX), (0, 0, 0, 0)), ol))
    scene_px.alpha_composite(small_fg)
    scene_px = scene_px.convert('RGB').quantize(28, method=Image.MEDIANCUT, dither=Image.NONE).convert('RGBA')
    im = Image.new('RGBA', (PIX, PIX), NAVY + (255,))
    outer, edge, inner = emblem_mask(PIX, 1), emblem_mask(PIX, 3), emblem_mask(PIX, 5)
    im.paste(BROWN + (255,), (0, 0), outer); im.paste(GOLD + (255,), (0, 0), edge)
    hl = Image.new('L', (PIX, PIX), 0); ImageDraw.Draw(hl).rectangle([0, 0, PIX, PIX // 2], fill=255)
    im.paste((255, 226, 130, 255), (0, 0), ImageChops.multiply(ImageChops.subtract(edge, emblem_mask(PIX, 4)), hl))
    im.paste(scene_px, (0, 0), inner)
    label = '5' if kind == 'castle' else ''
    if locked:
        g = ImageOps.grayscale(im.convert('RGB'))
        im = ImageOps.colorize(g, (10, 10, 26), (96, 108, 148)).convert('RGBA')
        im = im.quantize(6, dither=Image.NONE).convert('RGBA')
        lock = ['..###..', '.#...#.', '.#...#.', '#######', '###.###', '###.###', '#######']
        d = ImageDraw.Draw(im)
        for r, row in enumerate(lock):
            for c, ch in enumerate(row):
                if ch == '#':
                    d.point((PIX - 15 + c, PIX - 15 + r), fill=(200, 210, 235, 255))
    big = im.resize((256, 256), Image.NEAREST)
    if label:
        t = title_text(label, 4 * 2, CREAM if locked else GOLD)
        if locked:
            t = ImageOps.colorize(ImageOps.grayscale(t.convert('RGB')), (10, 10, 26), (150, 160, 190)).convert('RGBA'); t.putalpha(title_text(label, 8).getchannel('A'))
        big.alpha_composite(t, (30, 28))
    return big


# ---------------------------------------------------------------- style C: illustrated sticker badge

THEME = {'castle': ((255, 189, 41), (255, 214, 110)), 'fall': ((250, 120, 96), (255, 170, 140)), 'speed': ((60, 190, 230), (130, 225, 250))}
BANNER = {'castle': 'HALFWAY UP', 'fall': 'GRAVITY 1-0', 'speed': 'EXPRESS'}


def style_c(kind, locked):
    _, fg = scene(kind, with_sky=False)
    base, light = THEME[kind]
    im = Image.new('RGBA', (N, N), NAVY + (255,))
    disc = Image.new('RGBA', (N, N), base + (255,)); d = ImageDraw.Draw(disc)
    for i in range(16):                                                     # sunburst rays
        a0 = i * 2 * math.pi / 16
        pts = [(N / 2, N / 2)] + [(N / 2 + math.cos(a) * N, N / 2 + math.sin(a) * N) for a in (a0, a0 + math.pi / 16)]
        d.polygon(pts, fill=light + (255,))
    r = 430
    m = Image.new('L', (N, N), 0); ImageDraw.Draw(m).ellipse([N / 2 - r, N / 2 - r, N / 2 + r, N / 2 + r], fill=255)
    # figures: thick navy outline then the art, clipped to a slightly taller shape so heads pop out of the disc
    a = fg.getchannel('A')
    ol = a.point(lambda v: 255 if v > 40 else 0).filter(ImageFilter.MaxFilter(23))
    figs = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    figs.paste(NAVY + (255,), (0, 0), ol); figs.alpha_composite(fg)
    rim = Image.new('L', (N, N), 0); ImageDraw.Draw(rim).ellipse([N / 2 - r - 34, N / 2 - r - 34, N / 2 + r + 34, N / 2 + r + 34], fill=255)
    rim2 = Image.new('L', (N, N), 0); ImageDraw.Draw(rim2).ellipse([N / 2 - r - 50, N / 2 - r - 50, N / 2 + r + 50, N / 2 + r + 50], fill=255)
    im.paste(NAVY + (255,), (0, 0), rim2); im.paste((255, 255, 255, 255), (0, 0), rim)
    im.paste(disc, (0, 0), m)
    figs_clip = Image.new('RGBA', (N, N), (0, 0, 0, 0)); figs_clip.paste(figs, (0, 0), ImageChops.multiply(figs.getchannel('A'), m))
    im.alpha_composite(figs_clip)
    # ribbon banner
    t = title_text(BANNER[kind], 9, CREAM, layers=False)
    bw, bh, by = max(t.width + 120, 560), t.height + 64, 760
    b = ImageDraw.Draw(im)
    for dx in (-1, 1):
        x = N / 2 + dx * bw / 2
        b.polygon([(x, by + 26), (x + dx * 90, by + 26), (x + dx * 60, by + 26 + bh / 2), (x + dx * 90, by + 26 + bh), (x, by + 26 + bh)], fill=NAVY)
        b.polygon([(x, by + 30), (x + dx * 80, by + 30), (x + dx * 54, by + 26 + bh / 2), (x + dx * 80, by + 22 + bh), (x, by + 22 + bh)], fill=tuple(int(v * 0.7) for v in base))
    b.rounded_rectangle([N / 2 - bw / 2 - 8, by - 8, N / 2 + bw / 2 + 8, by + bh + 8], 20, fill=NAVY)
    b.rounded_rectangle([N / 2 - bw / 2, by, N / 2 + bw / 2, by + bh], 14, fill=(226, 59, 43) if not locked else (120, 120, 130))
    im.alpha_composite(t, (int(N / 2 - t.width / 2), int(by + bh / 2 - t.height / 2)))
    if locked:
        g = ImageOps.grayscale(im.convert('RGB'))
        g = ImageEnhance.Contrast(g).enhance(0.6)
        im = ImageOps.colorize(g, (16, 16, 32), (150, 156, 176)).convert('RGBA')
        # dashed "not yet peeled" ring
        dd = ImageDraw.Draw(im)
        for i in range(36):
            a0 = i * 10
            dd.arc([N / 2 - r - 34, N / 2 - r - 34, N / 2 + r + 34, N / 2 + r + 34], a0, a0 + 5, fill=(200, 205, 220), width=14)
    return im.resize((256, 256), Image.LANCZOS)


# ---------------------------------------------------------------- comparison sheets

STYLES = {
    'A': ('STYLE A  -  SKY DIORAMA', 'Soft 3D renders of the game\'s own models in the game\'s world skies, framed in the title\'s gold and brown. Matches the Sky Peek app icon.', style_a),
    'B': ('STYLE B  -  PIXEL EMBLEM', 'The game\'s 3D models reduced to chunky 64-pixel art inside a gold octagon, with numbers in the title lettering. Reads like the HUD.', style_b),
    'C': ('STYLE C  -  STICKER BADGE', 'Round sticker badges: sunburst colour per category, bold navy outline, red ribbon with the name in the game\'s lettering.', style_c),
}
EXAMPLES = [('castle', 'HALFWAY UP', 'ACH_TOWER_5', 'castle progression'), ('fall', 'GRAVITY: 1, YOU: 0', 'ACH_FIRST_FALL', 'funny fall'),
            ('speed', 'EXPRESS ELEVATOR', 'ACH_SPEED_SUMMIT', 'speedrunning')]


def sheet(key, out):
    title, blurb, fn = STYLES[key]
    W, H = 1900, 1060
    im = Image.new('RGB', (W, H), (23, 26, 33)); d = ImageDraw.Draw(im)
    f_b = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 44)
    f = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 22)
    f_s = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 18)
    d.text((40, 30), title, font=f_b, fill=(255, 255, 255)); d.text((40, 92), blurb, font=f, fill=(190, 200, 215))
    steam_bg = (27, 40, 56)
    for i, (kind, name, api, what) in enumerate(EXAMPLES):
        x0 = 40 + i * 620
        d.text((x0, 150), f'{name}', font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 26), fill=(255, 210, 90))
        d.text((x0, 184), f'{api}   ({what})', font=f_s, fill=(160, 170, 185))
        for j, locked in enumerate((False, True)):
            icon = fn(kind, locked)
            icon.save(os.path.join(out, f'style_{key}_{api}_{"locked" if locked else "unlocked"}.png'))
            ix = x0 + j * 290
            im.paste(icon.convert('RGB'), (ix, 220))
            d.text((ix, 486), 'LOCKED' if locked else 'UNLOCKED', font=f_s, fill=(200, 205, 215))
            # Steam sizes on Steam's own dark blue: 64 (achievement list) and 32 (overlay pop-up / small lists)
            d.rectangle([ix, 520, ix + 256, 620], fill=steam_bg)
            im.paste(icon.resize((64, 64), Image.LANCZOS).convert('RGB'), (ix + 20, 538))
            im.paste(icon.resize((32, 32), Image.LANCZOS).convert('RGB'), (ix + 110, 554))
            im.paste(icon.resize((48, 48), Image.LANCZOS).convert('RGB'), (ix + 170, 546))
        d.text((x0, 628), 'actual size on Steam: 64 / 32 / 48 px', font=f_s, fill=(140, 150, 165))
    # a mock Steam achievement row, so the style is seen the way players see it
    d.text((40, 680), 'As a player sees it in the Steam achievements list (64 px, actual size):', font=f, fill=(190, 200, 215))
    for i, (kind, name, api, _) in enumerate(EXAMPLES):
        y = 720 + i * 104
        d.rectangle([40, y, W - 40, y + 92], fill=steam_bg)
        im.paste(fn(kind, i == 2).resize((64, 64), Image.LANCZOS).convert('RGB'), (54, y + 14))
        d.text((136, y + 18), name.title(), font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 22), fill=(255, 255, 255) if i < 2 else (130, 140, 155))
        d.text((136, y + 52), 'Unlocked' if i < 2 else 'Locked', font=f_s, fill=(140, 150, 165))
    im.save(os.path.join(out, f'style_{key}.png'))


if __name__ == '__main__':
    ap = argparse.ArgumentParser(); ap.add_argument('--out', required=True); a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for k in STYLES:
        sheet(k, a.out)
    print('ok')
