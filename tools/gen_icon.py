#!/usr/bin/env python3
"""Original app icon for Climb up: sunset sky, green blocks, and the game's own 3D bunny hero (render made by desktop IconShot from the Quaternius hero, CC0). Writes Android launcher resources."""
import os
from PIL import Image, ImageDraw
ROOT = os.path.join(os.path.dirname(__file__), '..')
RES = os.path.join(ROOT, 'android', 'src', 'main', 'res')
plat = Image.open(os.path.join(ROOT, 'tools', 'icon', 'platform_render.png')).convert('RGBA'); plat = plat.crop(plat.getbbox())
hero = Image.open(os.path.join(ROOT, 'tools', 'icon', 'hero_face.png')).convert('RGBA'); hero = hero.crop(hero.getbbox())

def background(size):
    im = Image.new('RGBA', (size, size)); d = ImageDraw.Draw(im)
    top, bot = (58, 40, 112), (255, 150, 90)
    for y in range(size):
        t = y / (size - 1); d.line([(0, y), (size, y)], fill=tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3)) + (255,))
    u = size // 36
    for (cx, cy, w) in ((5, 10, 8), (22, 7, 9), (14, 22, 7)):   # blocky clouds
        for i in range(w):
            h = 1 + int(2.4 * min(i, w - 1 - i) / (w / 2) + 0.5)
            d.rectangle([(cx + i) * u, (cy - h) * u, (cx + i + 1) * u - 2, cy * u], fill=(255, 235, 230, 200))
    return im

def foreground(size):
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    u = size / 18.0
    # the bunny peeks over a ledge as it climbs up (face close-up; the render is cut off at the chest, the ledge hides the cut)
    hw = int(size * 0.50); hh = int(hw * hero.height / hero.width)
    ledge_top = int(size * 0.68)
    im.alpha_composite(hero.resize((hw, hh), Image.LANCZOS), ((size - hw) // 2, ledge_top - hh + int(size * 0.07)))
    # platform blocks rendered from the game's own Kenney grass block model, so the ledge looks like the platforms in the game
    pw = int(size * 0.80); ph = int(pw * plat.height / plat.width)
    im.alpha_composite(plat.resize((pw, ph), Image.LANCZOS), ((size - pw) // 2, ledge_top - int(ph * 0.22)))
    sw = int(size * 0.34); sh = int(sw * plat.height / plat.width)           # two smaller platforms below and beside it
    im.alpha_composite(plat.resize((sw, sh), Image.LANCZOS), (int(size * 0.02), int(size * 0.86)))
    im.alpha_composite(plat.resize((sw, sh), Image.LANCZOS), (int(size * 0.62), int(size * 0.80)))
    return im

def mono(fg):
    a = fg.split()[3]
    out = Image.new('RGBA', fg.size, (255, 255, 255, 0)); out.putalpha(a)
    w = Image.new('RGBA', fg.size, (255, 255, 255, 255)); w.putalpha(a)
    return w

fg = foreground(432); bg = background(432)
fg.save(os.path.join(RES, 'drawable-nodpi', 'ic_launcher_foreground.png'))
bg.save(os.path.join(RES, 'drawable-nodpi', 'ic_launcher_background.png'))
mono(fg).save(os.path.join(RES, 'drawable-nodpi', 'ic_launcher_monochrome.png'))
comp = bg.copy(); comp.alpha_composite(fg)
mask = Image.new('L', comp.size, 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, 431, 431], radius=80, fill=255)
legacy = Image.new('RGBA', comp.size, (0, 0, 0, 0)); legacy.paste(comp, (0, 0), mask)
for name, px in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
    legacy.resize((px, px), Image.LANCZOS).save(os.path.join(RES, f'mipmap-{name}', 'ic_launcher.png'))
    legacy.resize((px, px), Image.LANCZOS).save(os.path.join(RES, f'mipmap-{name}', 'ic_launcher_round.png'))
comp.resize((512, 512), Image.LANCZOS).save(os.path.join(ROOT, 'docs', 'play_store_icon_512.png'))
open(os.path.join(RES, 'mipmap-anydpi-v26', 'ic_launcher.xml'), 'w').write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
''')
open(os.path.join(RES, 'mipmap-anydpi-v26', 'ic_launcher_round.xml'), 'w').write(open(os.path.join(RES, 'mipmap-anydpi-v26', 'ic_launcher.xml')).read())
print('icons ok')
