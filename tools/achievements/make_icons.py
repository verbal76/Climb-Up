#!/usr/bin/env python3
"""Steam achievement icons, Style A "Sky Diorama" (owner approved 15 October 2026).

One scene per achievement, built from renders of the game's own models (tools/achievements/renders, made by the desktop
IconShot tool from the Quaternius and Kenney CC0 packs the game ships) over the game's world skies, framed in the title's
gold and brown. Numbers use the game's own 5 x 7 lettering. The locked version is the same scene in a muted blue-grey.

  python3 tools/achievements/make_icons.py --sheet <dir>      contact sheet of the 27 designs (approval gate 2)
  python3 tools/achievements/make_icons.py --export <dir>     the 54 final 256 x 256 PNGs (after approval)

Deterministic: same inputs, same pixels.
"""
import argparse
import math
import os
import random
import sys

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from make_concepts import (BROWN, CREAM, GOLD, N, NAVY, SKIES, fit, load, paste, stopwatch, streaks,  # noqa: E402
                           title_text)

SKIES = dict(SKIES)
SKIES['gold'] = ((255, 160, 60), (255, 236, 170))
SKIES['high'] = ((40, 70, 160), (150, 200, 245))
ROPE, BEAM = (219, 179, 107), (115, 77, 46)


# ---------------------------------------------------------------- drawing helpers

def sky(kind, stars=0, seed=7):
    top, bot = SKIES[kind]
    im = Image.new('RGBA', (N, N)); d = ImageDraw.Draw(im)
    for y in range(N):
        t = y / (N - 1)
        d.line([(0, y), (N, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bot)) + (255,))
    rnd = random.Random(seed)
    for _ in range(stars):
        x, y, r = rnd.random() * N, rnd.random() * N * 0.85, rnd.choice((3, 3, 4, 5, 7))
        d.rectangle([x, y, x + r, y + r], fill=(255, 255, 255, rnd.randint(110, 255)))
    return im


def put(dst, name, cx, cy, h=None, w=None, rot=0, shadow=True, alpha=1.0, flip=False):
    im = fit(load(name), h=h, w=w)
    if flip:
        im = ImageOps.mirror(im)
    if rot:
        im = im.rotate(rot, expand=True, resample=Image.BICUBIC)
    if alpha < 1:
        im.putalpha(im.getchannel('A').point(lambda v: int(v * alpha)))
    paste(dst, im, cx, cy, shadow=shadow)


def clouds(dst, spots):
    for cx, cy, w in spots:
        put(dst, 'cloud', cx, cy, w=w, shadow=False)


def sparkle(dst, cx, cy, r, col=(255, 255, 255), a=255):
    lay = Image.new('RGBA', dst.size, (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    k = r * 0.22
    d.polygon([(cx, cy - r), (cx + k, cy - k), (cx + r, cy), (cx + k, cy + k), (cx, cy + r), (cx - k, cy + k), (cx - r, cy), (cx - k, cy - k)], fill=col + (a,))
    dst.alpha_composite(lay)


def box3d(dst, x0, y0, x1, y1, col, depth=26):
    """A chunky voxel-ish block: top face lighter, front face, darker right side."""
    d = ImageDraw.Draw(dst)
    lt = tuple(min(255, int(c * 1.25)) for c in col); dk = tuple(int(c * 0.65) for c in col)
    d.polygon([(x0, y0), (x0 + depth, y0 - depth), (x1 + depth, y0 - depth), (x1, y0)], fill=lt)
    d.polygon([(x1, y0), (x1 + depth, y0 - depth), (x1 + depth, y1 - depth), (x1, y1)], fill=dk)
    d.rectangle([x0, y0, x1, y1], fill=col)


def rope(dst, x, y0, y1, w=26):
    d = ImageDraw.Draw(dst)
    d.rectangle([x - w / 2, y0, x + w / 2, y1], fill=ROPE)
    d.rectangle([x + w / 6, y0, x + w / 2, y1], fill=tuple(int(c * 0.8) for c in ROPE))
    for y in range(int(y0), int(y1), int(w * 1.3)):                                        # twist marks
        d.line([(x - w / 2, y + w * 0.6), (x + w / 2, y)], fill=tuple(int(c * 0.7) for c in ROPE), width=max(2, w // 6))


def dashed(dst, pts, col=(255, 255, 255), a=200, w=12, dash=30, gap=22):
    lay = Image.new('RGBA', dst.size, (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    acc, on = 0.0, True
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        seg = math.hypot(x1 - x0, y1 - y0)
        if on:
            d.line([(x0, y0), (x1, y1)], fill=col + (a,), width=w)
        acc += seg
        if acc > (dash if on else gap):
            on, acc = not on, 0.0
    dst.alpha_composite(lay)


def arc_pts(x0, y0, x1, y1, lift, n=80):
    return [(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t - lift * 4 * t * (1 - t)) for t in (i / n for i in range(n + 1))]


def chevrons(dst, cx, cy, s, a=220):
    d = ImageDraw.Draw(dst)
    for sgn in (-1, 1):
        x = cx + sgn * s
        d.line([(x, cy - s * 0.45), (x + sgn * s * 0.45, cy), (x, cy + s * 0.45)], fill=(255, 255, 255, a), width=int(s * 0.22), joint='curve')


def pad(dst, cx, y, w=420):
    box3d(dst, cx - w / 2, y, cx + w / 2, y + 70, (51, 77, 166), depth=34)
    box3d(dst, cx - w / 2 + 12, y - 40, cx + w / 2 - 12, y, (255, 209, 46), depth=30)


def moon(dst, cx, cy, r):
    d = ImageDraw.Draw(dst)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(250, 245, 220, 255))
    d.ellipse([cx - r * 0.55, cy - r * 1.05, cx + r * 1.35, cy + r * 0.85], fill=SKIES['night'][0] + (255,))


def sunburst(dst, cx, cy, col=(255, 255, 255), a=60, n=18):
    lay = Image.new('RGBA', dst.size, (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    for i in range(n):
        a0 = i * 2 * math.pi / n
        d.polygon([(cx, cy)] + [(cx + math.cos(t) * N * 1.5, cy + math.sin(t) * N * 1.5) for t in (a0, a0 + math.pi / n)], fill=col + (a,))
    dst.alpha_composite(lay)


def mover(dst, cx, cy, w):
    m = load('mover'); half = m.crop((0, 0, m.width // 2 - 4, m.height)); half = half.crop(half.getbbox())
    paste(dst, half.resize((int(w), int(w * half.height / half.width)), Image.LANCZOS), cx, cy)


def swing(dst, cx, cy, w, top):
    s = load('swing'); half = s.crop((0, 0, s.width // 2 - 4, s.height)); half = half.crop(half.getbbox())
    half = half.resize((int(w), int(w * half.height / half.width)), Image.LANCZOS)
    for dx in (-w * 0.38, w * 0.38):
        ImageDraw.Draw(dst).line([(cx + dx, top), (cx + dx, cy)], fill=ROPE, width=10)
    paste(dst, half, cx, cy)


def platform(dst, cx, cy, w, rot=0):
    put(dst, 'platform', cx, cy, w=w, rot=rot)


# ---------------------------------------------------------------- the 27 scenes (order and text from the entry sheet)

def s_tower_1():
    im = sky('meadow'); clouds(im, ((860, 230, 230),))
    platform(im, 512, 935, 1150); put(im, 'castle_b', 400, 520, h=700); put(im, 'bun_wave', 800, 700, h=330)
    return im


def s_tower_5():
    """Halfway: high above the clouds, the bunny cheering on the castle's battlements, a ten-step height gauge half full."""
    im = sky('high'); sunburst(im, 430, 300, col=(255, 236, 170), a=45, n=20)
    clouds(im, ((120, 930, 360), (520, 980, 420), (900, 930, 330)))
    put(im, 'castle', 430, 590, h=720)
    put(im, 'bun_yes', 430, 175, h=250)
    put(im, 'flag', 600, 160, h=230, shadow=False)
    d = ImageDraw.Draw(im)                                                                      # the height gauge: 10 steps, 5 lit
    x0, x1, top, step = 840, 930, 190, 66
    for k in range(10):
        y = top + (9 - k) * step
        if k < 5:
            d.rounded_rectangle([x0 - 6, y - 6, x1 + 6, y + step - 6], 14, fill=BROWN)
            d.rounded_rectangle([x0, y, x1, y + step - 12], 10, fill=GOLD)
        else:
            d.rounded_rectangle([x0, y, x1, y + step - 12], 10, outline=(255, 255, 255), width=8)
    d.polygon([(x0 - 70, top + 5 * step - 6 - 34), (x0 - 18, top + 5 * step - 6), (x0 - 70, top + 5 * step - 6 + 34)], fill=(226, 59, 43))   # "you are here"
    for x, y, r in ((250, 140, 34), (690, 300, 28)):
        sparkle(im, x, y, r)
    return im


def s_summit():
    im = sky('gold'); sunburst(im, 470, 330, a=55)
    clouds(im, ((150, 930, 330), (880, 950, 280)))
    put(im, 'castle', 470, 650, h=640); put(im, 'bun_yes', 520, 220, h=260); put(im, 'flag', 740, 210, h=300, shadow=False)
    return im


def s_fingertip():
    im = sky('dusk'); clouds(im, ((820, 860, 240),))
    d = ImageDraw.Draw(im); d.rectangle([0, 860, N, N], fill=(40, 25, 60))                      # the drop below
    put(im, 'bun_death', 400, 470, h=470)                                                       # hauling up over the edge: the ledge is in front of him
    platform(im, 640, 700, 900)
    for x, y in ((610, 330), (650, 400)):                                                       # sweat drops
        d = ImageDraw.Draw(im); d.ellipse([x - 16, y - 8, x + 16, y + 28], fill=(160, 220, 255)); d.polygon([(x - 13, y), (x, y - 30), (x + 13, y)], fill=(160, 220, 255))
    return im


def s_rope_top():
    im = sky('meadow'); clouds(im, ((150, 180, 260), (880, 260, 220)))
    rope(im, 512, 560, N, w=52)
    box3d(im, 170, 520, 850, 600, BEAM, depth=40)
    put(im, 'bun_yes', 512, 360, h=330)
    return im


def s_midair_catch():
    im = sky('frost'); clouds(im, ((860, 200, 240),))
    dashed(im, arc_pts(150, 520, 560, 640, 360), w=14)
    mover(im, 600, 840, 420); chevrons(im, 600, 850, 250)
    put(im, 'bun_land', 600, 600, h=330)
    return im


def s_crab_off():
    im = sky('meadow'); clouds(im, ((850, 820, 240),))
    platform(im, 400, 960, 900)
    put(im, 'bun_punch', 330, 690, h=380)
    put(im, 'club', 520, 560, h=300, rot=-70)
    streaks(im, [(560, 520, 700, 420, 18), (580, 600, 740, 520, 18)], a=180)
    put(im, 'crab', 790, 300, h=250, rot=35)
    for x, y, s in ((660, 180, 70), (930, 230, 55), (880, 420, 60)):
        put(im, 'star', x, y, w=s, shadow=False)
    return im


def s_bounce():
    im = sky('meadow'); clouds(im, ((170, 250, 240), (860, 520, 220)))
    pad(im, 512, 850)
    lay = Image.new('RGBA', im.size, (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    for r in (140, 230, 320):
        d.arc([512 - r, 740 - r * 0.5, 512 + r, 740 + r * 0.5], 195, 345, fill=(255, 255, 255, 210), width=16)
    for x in (360, 512, 664):
        d.line([(x, 640), (x, 520)], fill=(255, 255, 255, 170), width=14)
    im.alpha_composite(lay)
    put(im, 'bun_jump', 512, 270, h=360)
    return im


def s_infinity_enter():
    im = Image.new('RGBA', (N, N)); d = ImageDraw.Draw(im)
    for y in range(N):                                                                          # the sky turns to night above the summit
        t = y / (N - 1); a, b = SKIES['night'][0], SKIES['gold'][1]
        d.line([(0, y), (N, y)], fill=tuple(int(p + (q - p) * t) for p, q in zip(a, b)) + (255,))
    rnd = random.Random(3)
    for _ in range(40):
        x, y = rnd.random() * N, rnd.random() * 380
        d.rectangle([x, y, x + 5, y + 5], fill=(255, 255, 255, 200))
    put(im, 'castle', 300, 800, h=420)
    put(im, 'arrow', 730, 560, h=430, shadow=False, alpha=0.85)
    put(im, 'bun_jump', 560, 330, h=330)
    return im


def s_infinity_best():
    im = sky('night', stars=60)
    dashed(im, [(60, 760), (964, 760)], col=(255, 255, 255), a=170, w=12)
    put(im, 'flag', 220, 700, h=170, alpha=0.55, shadow=False)                                   # the old best, left below
    platform(im, 600, 560, 560)
    put(im, 'bun_yes', 560, 380, h=310); put(im, 'flag', 760, 360, h=330)
    for x, y, r in ((360, 200, 40), (860, 160, 32), (300, 420, 26)):
        sparkle(im, x, y, r)
    return im


def s_space():
    im = sky('space', stars=120)
    put(im, 'planet_b', 760, 260, w=420, shadow=False); put(im, 'planet_a', 170, 820, w=330, shadow=False)
    put(im, 'ship', 260, 230, w=260, rot=12, shadow=False)
    put(im, 'bun_jumpidle', 560, 640, h=400)
    return im


def s_clean_run():
    """No take-backs: one unbroken golden trail from the bottom to the summit castle, no checkpoint ever used."""
    im = sky('frost'); clouds(im, ((150, 220, 240), (900, 600, 200)))
    def bez(p0, p1, p2, p3, n=60):
        return [tuple((1 - t) ** 3 * a + 3 * (1 - t) ** 2 * t * b + 3 * (1 - t) * t ** 2 * c + t ** 3 * e for a, b, c, e in zip(p0, p1, p2, p3)) for t in (i / n for i in range(n + 1))]
    path = bez((30, 960), (420, 960), (900, 900), (840, 700)) + bez((840, 700), (790, 540), (480, 600), (560, 470))[1:] + bez((560, 470), (620, 400), (700, 430), (780, 420))[1:]
    lay = Image.new('RGBA', im.size, (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    d.line(path, fill=BROWN + (255,), width=50, joint='curve')
    d.line(path, fill=GOLD + (255,), width=34, joint='curve')
    d.line([(x - 3, y - 7) for x, y in path], fill=(255, 236, 160, 255), width=8, joint='curve')
    im.alpha_composite(lay)
    put(im, 'castle', 790, 270, h=320)
    put(im, 'bun_walk', 380, 790, h=330)
    for x, y, r in ((200, 860, 30), (640, 820, 30), (900, 560, 26), (470, 400, 26), (620, 960, 22)):
        sparkle(im, x, y, r)
    return im


def s_first_fall():
    im = sky('dusk'); clouds(im, ((830, 820, 260),))
    platform(im, 200, 150, 700, rot=8)
    streaks(im, [(x, 120, x, 300 + d, 22) for x, d in ((420, 40), (520, 0), (630, 80), (720, 10))], a=150)
    put(im, 'bun_death', 560, 600, h=560, rot=155)
    for i, (dx, dy, s) in enumerate(((-260, 230, 110), (-160, 330, 80), (210, 300, 95))):
        put(im, 'star', 560 + dx, 600 + dy, w=s, rot=20 * i - 15, shadow=False)
    return im


def s_fall_25():
    im = sky('frost'); clouds(im, ((180, 880, 260), (880, 700, 220)))
    for i, (x, y, a) in enumerate(((760, 230, 0.25), (680, 400, 0.45))):
        put(im, 'bun_hit', x, y, h=300, rot=160, alpha=a, shadow=False)
    put(im, 'bun_hit', 500, 640, h=420, rot=160)
    return im


def s_fall_100():
    im = sky('dusk')
    platform(im, 512, 850, 1250)
    lay = Image.new('RGBA', im.size, (0, 0, 0, 0)); d = ImageDraw.Draw(lay)                    # cracks in the ground
    for x0, x1, y1 in ((380, 300, 900), (640, 740, 905), (520, 520, 915)):
        d.line([(x0, 800), ((x0 + x1) / 2 + 20, 850), (x1, y1)], fill=(60, 30, 20, 220), width=12)
    im.alpha_composite(lay)
    put(im, 'bun_flat', 520, 700, w=640)
    for i, (x, y, s) in enumerate(((340, 520, 80), (520, 470, 95), (700, 520, 80))):
        put(im, 'star', x, y, w=s, rot=25 * i, shadow=False)
    return im


def s_fall_every_tower():
    im = sky('night', stars=50); moon(im, 860, 170, 80)
    put(im, 'castle', 200, 760, h=430, alpha=0.75); put(im, 'castle_b', 840, 760, h=430, alpha=0.75)
    put(im, 'castle', 520, 840, h=330, alpha=0.6, shadow=False)
    put(im, 'bun_death', 520, 420, h=420, rot=165)
    streaks(im, [(x, 60, x, 200, 18) for x in (430, 520, 610)], a=140)
    return im


def s_rage_spot():
    im = sky('dusk'); sunburst(im, 760, 520, col=(255, 80, 60), a=45)
    platform(im, 512, 950, 1150)
    put(im, 'saw', 760, 560, h=430)
    put(im, 'bun_no', 300, 680, h=400)
    put(im, 'thunder', 300, 270, h=230, rot=-10, shadow=False)
    return im


def endless(kind, bun, extra):
    def f():
        im = sky(kind, stars=90 if kind in ('night', 'space') else 0)
        extra(im)
        put(im, 'arrow', 780, 560, h=440, shadow=False, alpha=0.8)
        put(im, bun, 520, 520, h=420)
        return im
    return f


s_inf_500 = endless('high', 'bun_jump', lambda im: clouds(im, ((180, 880, 340), (720, 940, 380), (900, 760, 200))))
s_inf_1000 = endless('frost', 'bun_jump', lambda im: [sparkle(im, x, y, r, col=(255, 255, 255), a=230) for x, y, r in ((150, 300, 22), (300, 820, 18), (880, 250, 26), (840, 880, 20), (620, 160, 16), (120, 640, 18))])
s_inf_2500 = endless('night', 'bun_jumpidle', lambda im: moon(im, 820, 200, 100))
s_inf_5000 = endless('space', 'bun_jump', lambda im: (put(im, 'planet_b', 230, 780, w=380, shadow=False), put(im, 'ship', 820, 860, w=240, rot=-15, shadow=False)))


def s_all_worlds():
    """Grand tour: the five worlds stacked as the climb meets them (Meadow at the bottom, Deep Space on top), each with its
    landmark, and the route climbing through all of them to the bunny waving at the top."""
    im = Image.new('RGBA', (N, N)); d = ImageDraw.Draw(im)
    order = ['meadow', 'frost', 'dusk', 'night', 'space']                                       # bottom to top
    bh = N / 5
    for k, w in enumerate(order):
        y0 = N - (k + 1) * bh
        top, bot = SKIES[w]
        for y in range(int(y0), int(y0 + bh) + 1):
            t = (y - y0) / bh
            d.line([(0, y), (N, y)], fill=tuple(int(a + (b - a) * (0.25 + 0.6 * t)) for a, b in zip(top, bot)) + (255,))
    rnd = random.Random(5)
    for _ in range(70):                                                                          # stars in Night and Deep Space
        x, y = rnd.random() * N, rnd.random() * 2 * bh
        d.rectangle([x, y, x + 5, y + 5], fill=(255, 255, 255, 220))
    for k in range(1, 5):
        y = N - k * bh
        d.line([(0, y), (N, y)], fill=(255, 255, 255, 140), width=5)
    # landmarks, one per world
    platform(im, 300, N - 0.28 * bh, 520)                                                        # Meadow: grass platforms
    put(im, 'tree', 860, N - 1.45 * bh, h=170)                                                   # Frost: snowy pine
    d.ellipse([110, N - 2.75 * bh, 230, N - 2.75 * bh + 120], fill=(255, 210, 120))              # Dusk: low sun
    moon(im, 850, N - 3.55 * bh, 60)                                                             # Night: moon
    put(im, 'planet_b', 210, 0.48 * bh, w=290, shadow=False)                                     # Deep Space: ringed planet
    route = [(300, N - 0.6 * bh), (700, N - 1.5 * bh), (320, N - 2.5 * bh), (690, N - 3.5 * bh), (560, 0.75 * bh)]
    path = []
    for p, q in zip(route, route[1:]):
        path += arc_pts(p[0], p[1], q[0], q[1], 30, 30)
    dashed(im, path, col=(255, 236, 160), a=240, w=14)
    put(im, 'bun_wave', 600, 170, h=220)
    return im


def s_no_checkpoint_world():
    im = sky('dusk')
    d = ImageDraw.Draw(im); d.rectangle([0, 820, N, N], fill=(40, 25, 60))
    put(im, 'spikes', 512, 900, w=520, shadow=False)
    platform(im, 80, 640, 520); platform(im, 950, 560, 520)
    dashed(im, arc_pts(250, 560, 800, 480, 260), w=12)
    put(im, 'bun_jump', 520, 330, h=340)
    return im


def s_speed_summit():
    im = sky('night', stars=90)
    paste(im, stopwatch(640), 640, 360)
    streaks(im, [(40, y, 40 + l, y, 24) for y, l in ((560, 300), (650, 380), (740, 260), (830, 340))], col=(140, 220, 255), a=190)
    put(im, 'bun_run', 470, 690, h=560)
    return im


def s_perfect_summit():
    im = sky('gold'); sunburst(im, 512, 300, a=70, n=24)
    clouds(im, ((140, 920, 320), (890, 940, 300)))
    put(im, 'castle_b', 512, 700, h=560)
    put(im, 'bun_yes', 512, 330, h=250)
    put(im, 'jewel', 512, 130, h=170, shadow=False)
    for x, y, r in ((300, 160, 44), (730, 200, 38), (250, 420, 30), (790, 430, 34)):
        sparkle(im, x, y, r)
    return im


def s_swing_chain():
    im = sky('meadow'); clouds(im, ((860, 180, 220),))
    rope(im, 140, 0, 520, w=30)
    swing(im, 470, 640, 300, 0)
    mover(im, 860, 860, 300)
    dashed(im, arc_pts(150, 520, 470, 600, 120) + arc_pts(470, 600, 860, 790, 360)[1:], w=12)
    put(im, 'bun_jump', 690, 380, h=300, rot=-25)
    return im


def s_marathon():
    """The long haul: the bunny, big in front, setting off along a long winding route that links castle after castle up the sky."""
    im = Image.new('RGBA', (N, N)); d = ImageDraw.Draw(im)
    for y in range(N):
        t = y / (N - 1); a, b = SKIES['night'][0], SKIES['meadow'][1]
        d.line([(0, y), (N, y)], fill=tuple(int(p + (q - p) * t) for p, q in zip(a, b)) + (255,))
    rnd = random.Random(9)
    for _ in range(45):
        x, y = rnd.random() * N, rnd.random() * 330
        d.rectangle([x, y, x + 5, y + 5], fill=(255, 255, 255, 200))
    pts = [(330, 930), (860, 820), (560, 600), (880, 430), (620, 290), (800, 120)]
    path = []
    for p, q in zip(pts, pts[1:]):
        path += arc_pts(p[0], p[1], q[0], q[1], 40, 30)
    dashed(im, path, col=(255, 230, 150), a=235, w=16)
    put(im, 'castle', 880, 740, h=260); put(im, 'castle_b', 560, 520, h=190); put(im, 'castle', 880, 360, h=140); put(im, 'castle_b', 620, 230, h=100); put(im, 'castle', 800, 80, h=70, shadow=False)
    platform(im, 250, 990, 620)
    put(im, 'bun_walk', 250, 700, h=500)
    return im


ACH = [  # (API name, display name, scene, number shown in the title lettering)
    ('ACH_TOWER_1', 'First Castle', s_tower_1, '1'),
    ('ACH_TOWER_5', 'Halfway Up', s_tower_5, '5'),
    ('ACH_SUMMIT', 'To the Top', s_summit, '10'),
    ('ACH_FINGERTIP', 'By the Fingertips', s_fingertip, ''),
    ('ACH_ROPE_TOP', 'Top of the Rope', s_rope_top, ''),
    ('ACH_MIDAIR_CATCH', 'Nice Catch', s_midair_catch, ''),
    ('ACH_CRAB_OFF', 'Crab Rangoon', s_crab_off, ''),
    ('ACH_BOUNCE', 'Boing!', s_bounce, ''),
    ('ACH_INFINITY_ENTER', 'Beyond the Top', s_infinity_enter, ''),
    ('ACH_INFINITY_BEST', 'New Heights', s_infinity_best, ''),
    ('ACH_SPACE', 'Deep Space', s_space, ''),
    ('ACH_CLEAN_RUN', 'No Take-Backs', s_clean_run, ''),
    ('ACH_FIRST_FALL', 'Gravity: 1, You: 0', s_first_fall, ''),
    ('ACH_FALL_25', 'Persistence Over Skill', s_fall_25, '25'),
    ('ACH_FALL_100', 'The Ground Remembers You', s_fall_100, '100'),
    ('ACH_FALL_EVERY_TOWER', 'Dropped But Not Out', s_fall_every_tower, '10'),
    ('ACH_RAGE_SPOT', "It's Personal Now", s_rage_spot, '5'),
    ('ACH_INFINITY_500', 'Still Climbing', s_inf_500, '500'),
    ('ACH_INFINITY_1000', 'Thin Air', s_inf_1000, '1000'),
    ('ACH_INFINITY_2500', 'Why Are You Still Here', s_inf_2500, '2500'),
    ('ACH_INFINITY_5000', 'Basically Astronaut', s_inf_5000, '5000'),
    ('ACH_ALL_WORLDS', 'Grand Tour', s_all_worlds, ''),
    ('ACH_NO_CHECKPOINT_WORLD', 'No Safety Net', s_no_checkpoint_world, ''),
    ('ACH_SPEED_SUMMIT', 'Express Elevator', s_speed_summit, ''),
    ('ACH_PERFECT_SUMMIT', 'Flawless', s_perfect_summit, ''),
    ('ACH_SWING_CHAIN', 'Acrobat', s_swing_chain, ''),
    ('ACH_MARATHON', 'The Long Haul', s_marathon, ''),
]


# ---------------------------------------------------------------- Style A framing (approved), with the brighter locked state

LOCK_DARK, LOCK_LIGHT, LOCK_FRAME = (34, 40, 66), (176, 190, 216), (92, 102, 132)


def icon(scene_fn, number, locked):
    im = scene_fn()
    vg = Image.new('L', (N, N), 0); ImageDraw.Draw(vg).ellipse([-200, -200, N + 200, N + 200], fill=255)
    vg = ImageOps.invert(vg.filter(ImageFilter.GaussianBlur(120)))
    im = Image.composite(Image.new('RGBA', (N, N), (10, 10, 30, 255)), im, vg.point(lambda v: v * 110 // 255))
    if number:
        cell = 26 if len(number) == 1 else 22 if len(number) == 2 else 17 if len(number) == 3 else 14
        im.alpha_composite(title_text(number, cell, GOLD), (64, 58))
    if locked:
        g = ImageOps.grayscale(im.convert('RGB'))
        g = ImageOps.colorize(ImageEnhance.Contrast(g).enhance(0.85), LOCK_DARK, LOCK_LIGHT)
        im = g.convert('RGBA').filter(ImageFilter.GaussianBlur(1.2))
    outside = Image.new('L', (N, N), 255); ImageDraw.Draw(outside).rounded_rectangle([14, 14, N - 15, N - 15], 70, fill=0)
    im.paste(NAVY + (255,), (0, 0), outside)
    d = ImageDraw.Draw(im)
    if locked:
        d.rounded_rectangle([14, 14, N - 15, N - 15], 70, outline=LOCK_FRAME, width=22)
    else:
        d.rounded_rectangle([14, 14, N - 15, N - 15], 70, outline=BROWN, width=22)
        d.rounded_rectangle([20, 20, N - 21, N - 21], 64, outline=GOLD, width=12)
    return im.resize((256, 256), Image.LANCZOS).convert('RGB')


# ---------------------------------------------------------------- outputs

def font(size, bold=False):
    return ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf' % ('-Bold' if bold else ''), size)


def contact_sheet(out):
    cols, cw, ch = 7, 300, 380
    rows = (len(ACH) + cols - 1) // cols
    W, H = 40 + cols * cw, 150 + rows * ch + 470
    im = Image.new('RGB', (W, H), (23, 26, 33)); d = ImageDraw.Draw(im)
    d.text((40, 28), 'UPWARDLY  -  STEAM ACHIEVEMENTS  -  STYLE A SKY DIORAMA  -  ALL 27 (UNLOCKED)', font=font(40, True), fill=(255, 255, 255))
    d.text((40, 86), 'Approval gate 2. Each icon at full Steam size (256 px) with its number, display name and API name; 64 px preview on Steam blue below each.', font=font(22), fill=(190, 200, 215))
    icons = {}
    for i, (api, name, fn, num) in enumerate(ACH):
        ic = icon(fn, num, False); icons[api] = ic
        x, y = 40 + (i % cols) * cw, 150 + (i // cols) * ch
        im.paste(ic, (x, y))
        d.rectangle([x + 262, y + 180, x + 262 + 30, y + 256], fill=(23, 26, 33))
        d.text((x, y + 262), f'{i + 1}. {name}', font=font(19 if len(name) < 22 else 16, True), fill=(255, 210, 90))
        d.text((x, y + 290), api, font=font(15), fill=(160, 170, 185))
        d.rectangle([x, y + 314, x + 256, y + 314 + 56], fill=(27, 40, 56))
        im.paste(ic.resize((48, 48), Image.LANCZOS), (x + 4, y + 318))
        im.paste(ic.resize((32, 32), Image.LANCZOS), (x + 60, y + 326))
        d.text((x + 100, y + 330), '48 / 32 px', font=font(15), fill=(140, 150, 165))
    y0 = 150 + rows * ch + 10
    d.text((40, y0), 'Locked examples (revised: brighter than the style proposal), with the unlocked version beside each and both at 64 px on Steam blue:', font=font(22), fill=(190, 200, 215))
    sample = ['ACH_TOWER_5', 'ACH_FIRST_FALL', 'ACH_SPEED_SUMMIT', 'ACH_SPACE', 'ACH_INFINITY_5000', 'ACH_SWING_CHAIN']
    for j, api in enumerate(sample):
        _, name, fn, num = next(a for a in ACH if a[0] == api)
        lk = icon(fn, num, True)
        x = 40 + j * 350
        im.paste(lk.resize((200, 200), Image.LANCZOS), (x, y0 + 50))
        im.paste(icons[api].resize((120, 120), Image.LANCZOS), (x + 210, y0 + 50))
        d.rectangle([x, y0 + 262, x + 330, y0 + 340], fill=(27, 40, 56))
        im.paste(lk.resize((64, 64), Image.LANCZOS), (x + 8, y0 + 269)); im.paste(icons[api].resize((64, 64), Image.LANCZOS), (x + 84, y0 + 269))
        d.text((x + 160, y0 + 284), 'locked / unlocked', font=font(15), fill=(140, 150, 165))
        d.text((x, y0 + 350), f'{name} (locked)', font=font(17, True), fill=(200, 205, 215))
    im.save(os.path.join(out, 'contact_sheet_27.png'))
    return im


def export(out):
    os.makedirs(out, exist_ok=True)
    for api, _, fn, num in ACH:
        icon(fn, num, False).save(os.path.join(out, f'{api}_unlocked.png'), optimize=True)
        icon(fn, num, True).save(os.path.join(out, f'{api}_locked.png'), optimize=True)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--sheet'); ap.add_argument('--export')
    a = ap.parse_args()
    if a.sheet:
        os.makedirs(a.sheet, exist_ok=True); contact_sheet(a.sheet)
    if a.export:
        export(a.export)
    print('ok')
