#!/usr/bin/env python3
"""The app icon, design A "Sky Peek" (owner's choice, 15 October 2026, Build 69): the game's own blue bunny (render by the desktop
IconShot from the Quaternius hero, CC0) rising over a row of the game's teal platforms (render of the Kenney block, CC0) against the
game sky. Writes every icon the builds use, from one composition:
  - Android adaptive icon layers (background, foreground, monochrome) + the legacy round-cornered mipmaps
  - docs/play_store_icon_512.png: the full-bleed square master; the Windows EXE icon (tools/windows/make_ico.py) and the Linux
    launcher icon (tools/linux/package.sh) are made from it at packaging time
  - desktop/src/main/resources/icons/upwardly_N.png: the desktop window and taskbar icons
The Steam icons in branding/steam are the same design."""
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.join(os.path.dirname(__file__), '..')
RES = os.path.join(ROOT, 'android', 'src', 'main', 'res')
plat = Image.open(os.path.join(ROOT, 'tools', 'icon', 'platform_render.png')).convert('RGBA'); plat = plat.crop(plat.getbbox())
hero = Image.open(os.path.join(ROOT, 'tools', 'icon', 'hero_face.png')).convert('RGBA'); hero = hero.crop(hero.getbbox())

D = 736.0   # the design is laid out on a 736-unit square (the size the concept was drawn at)


def sky(size, frame=None):
    """The game-sky gradient with two soft clouds. frame = (x0, y0, side) of the design square inside the image (clouds follow it)."""
    x0, y0, side = frame or (0, 0, size)
    k = side / D
    im = Image.new('RGBA', (size, size)); d = ImageDraw.Draw(im)
    top, bot = (76, 140, 222), (160, 214, 250)
    for y in range(size):
        t = min(1.0, max(0.0, (y - y0) / max(1, side - 1)))
        d.line([(0, y), (size, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bot)) + (255,))
    for cx, cy, s, a in ((60, 150, 55, 200), (520, 250, 45, 170)):
        c = Image.new('RGBA', (size, size), (0, 0, 0, 0)); cd = ImageDraw.Draw(c)
        for dx, dy, r in ((0, 0, 1), (0.9, -0.35, 1.2), (1.9, 0, 0.95), (1.0, 0.25, 1.1)):
            X, Y, R = x0 + (cx + dx * s) * k, y0 + (cy + dy * s) * k, r * s * k
            cd.ellipse([X - R, Y - R, X + R, Y + R], fill=(255, 255, 255, a))
        im.alpha_composite(c)
    return im


def figure(size, frame=None):
    """The bunny (with a soft drop shadow) and the platform row, transparent elsewhere."""
    x0, y0, side = frame or (0, 0, size)
    k = side / D
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    fw = int(560 * k); f = hero.resize((fw, int(hero.height * fw / hero.width)), Image.LANCZOS)
    fx, fy = int(x0 + (side - fw) / 2), int(y0 + 150 * k)
    sh = Image.new('RGBA', f.size, (0, 0, 0, 0)); sh.putalpha(f.getchannel('A').point(lambda v: v * 90 // 255))
    sh = sh.filter(ImageFilter.GaussianBlur(max(1, 8 * k)))
    im.alpha_composite(sh, (fx + int(10 * k), fy + int(10 * k))); im.alpha_composite(f, (fx, fy))
    pw = int(900 * k); p = plat.resize((pw, int(plat.height * pw / plat.width)), Image.LANCZOS)
    im.alpha_composite(p, (int(x0 + (side - pw) / 2), int(y0 + side - 170 * k)))
    return im


def square(size):
    """The full-bleed design (no transparency): store, Windows, Linux, desktop windows, Steam."""
    im = sky(size); im.alpha_composite(figure(size)); return im


def mono(fg):
    w = Image.new('RGBA', fg.size, (255, 255, 255, 255)); w.putalpha(fg.split()[3]); return w


if __name__ == '__main__':
    big = square(1472)
    # Android adaptive icon: 108 dp canvas, the middle 72 dp is what a launcher shows, so the design square sits in the middle two thirds.
    A = 432; frame = (A // 6, A // 6, A * 2 // 3)
    bg = sky(A, frame)
    fg = figure(A, frame)
    fg.save(os.path.join(RES, 'drawable-nodpi', 'ic_launcher_foreground.png'))
    bg.save(os.path.join(RES, 'drawable-nodpi', 'ic_launcher_background.png'))
    mono(fg).save(os.path.join(RES, 'drawable-nodpi', 'ic_launcher_monochrome.png'))
    # Legacy launchers (Android 7 and older): the full design with rounded corners.
    comp = big.resize((A, A), Image.LANCZOS)
    mask = Image.new('L', comp.size, 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, A - 1, A - 1], radius=80, fill=255)
    legacy = Image.new('RGBA', comp.size, (0, 0, 0, 0)); legacy.paste(comp, (0, 0), mask)
    for name, px in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
        legacy.resize((px, px), Image.LANCZOS).save(os.path.join(RES, f'mipmap-{name}', 'ic_launcher.png'))
        legacy.resize((px, px), Image.LANCZOS).save(os.path.join(RES, f'mipmap-{name}', 'ic_launcher_round.png'))
    big.convert('RGB').resize((512, 512), Image.LANCZOS).save(os.path.join(ROOT, 'docs', 'play_store_icon_512.png'))
    for px in (16, 32, 48, 64, 128, 256):
        big.convert('RGB').resize((px, px), Image.LANCZOS).save(os.path.join(ROOT, 'desktop', 'src', 'main', 'resources', 'icons', f'upwardly_{px}.png'))
    open(os.path.join(RES, 'mipmap-anydpi-v26', 'ic_launcher.xml'), 'w').write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
''')
    open(os.path.join(RES, 'mipmap-anydpi-v26', 'ic_launcher_round.xml'), 'w').write(open(os.path.join(RES, 'mipmap-anydpi-v26', 'ic_launcher.xml')).read())
    print('icons ok')
