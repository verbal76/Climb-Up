#!/usr/bin/env python3
"""Export, check and package the Steam achievement icons (Style A).

  python3 tools/achievements/package.py

Writes to branding/steam/achievements/:
  icons/ACH_*_unlocked.png, icons/ACH_*_locked.png   54 PNGs, 256 x 256, RGB (opaque by design)
  icons/manifest.csv                                 API name -> display name, description, both filenames, SHA-256
  Upwardly_Steam_Achievements.pdf                    every icon with its API name, name and description (to share)
  export_check.png                                   contact sheet made from the exported files themselves
  Upwardly_Steam_Achievement_Icons.zip               the 54 PNGs + manifest.csv + README.txt
The display names and descriptions are read from docs/STEAM_ACHIEVEMENTS_ENTRY_SHEET.md, the API names from
AchievementRules.java; the run stops if the three lists (code, entry sheet, artwork) disagree.
"""
import csv
import hashlib
import io
import os
import re
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
import make_icons as mi  # noqa: E402

OUT = os.path.join(ROOT, 'branding', 'steam', 'achievements')
ICONS = os.path.join(OUT, 'icons')
ZIP = os.path.join(OUT, 'Upwardly_Steam_Achievement_Icons.zip')
PDF = os.path.join(OUT, 'Upwardly_Steam_Achievements.pdf')


def code_ids():
    src = open(os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'hotatticgames', 'climbup', 'sim', 'AchievementRules.java')).read()
    block = src[src.index('ALL_IDS = {'):src.index('};', src.index('ALL_IDS = {'))]
    names = dict(re.findall(r'(\w+) = "(ACH_\w+)"', src))
    return [names[n] for n in re.findall(r'\b([A-Z_0-9]+)\b', block.split('{', 1)[1]) if n in names]


def sheet_rows():
    rows = {}
    for line in open(os.path.join(ROOT, 'docs', 'STEAM_ACHIEVEMENTS_ENTRY_SHEET.md')):
        m = re.match(r'\|\s*(\d+)\s*\|\s*`(ACH_\w+)`\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|', line)
        if m and m.group(2) not in rows:
            rows[m.group(2)] = (int(m.group(1)), m.group(3), m.group(4))
    return rows


def sha(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()


def main():
    ids, rows, art = code_ids(), sheet_rows(), [a[0] for a in mi.ACH]
    problems = []
    if len(ids) != 27 or len(set(ids)) != 27:
        problems.append(f'code lists {len(ids)} ids ({len(set(ids))} distinct)')
    if ids != art:
        problems.append('artwork order/names differ from AchievementRules.ALL_IDS')
    if set(rows) != set(ids):
        problems.append(f'entry sheet names differ: {sorted(set(rows) ^ set(ids))}')
    for api, name, _, _ in mi.ACH:
        if api in rows and rows[api][1] != name:
            problems.append(f'{api}: display name "{name}" (art) vs "{rows[api][1]}" (entry sheet)')
    if problems:
        sys.exit('STOP: ' + '; '.join(problems))

    os.makedirs(ICONS, exist_ok=True)
    for f in os.listdir(ICONS):
        if f.endswith('.png'):
            os.remove(os.path.join(ICONS, f))
    mi.export(ICONS)

    # ---- validation of the exported files themselves
    files = sorted(f for f in os.listdir(ICONS) if f.endswith('.png'))
    expect = sorted([f'{a}_unlocked.png' for a in ids] + [f'{a}_locked.png' for a in ids])
    checks = []
    checks.append(('54 PNG files, exactly the expected names', files == expect))
    checks.append(('27 unlocked + 27 locked', sum(f.endswith('_unlocked.png') for f in files) == 27 and sum(f.endswith('_locked.png') for f in files) == 27))
    sizes_ok, open_ok, content_ok, frame_ok, differ_ok = True, True, True, True, True
    for api in ids:
        u = Image.open(os.path.join(ICONS, f'{api}_unlocked.png')); l = Image.open(os.path.join(ICONS, f'{api}_locked.png'))
        try:
            u.load(); l.load()
        except Exception:
            open_ok = False; continue
        for im in (u, l):
            if im.size != (256, 256) or im.format != 'PNG':
                sizes_ok = False
            ext = im.convert('L').getextrema()
            if ext[1] - ext[0] < 60:                         # empty / flat image
                content_ok = False
            if im.getpixel((1, 1)) != mi.NAVY:                # corner outside the frame: nothing cropped or shifted
                frame_ok = False
        uc = u.convert('RGB').resize((32, 32)); lc = l.convert('RGB').resize((32, 32))
        if sum(abs(a - b) for p, q in zip(list(uc.getdata()), list(lc.getdata())) for a, b in zip(p, q)) < 32 * 32 * 20:
            differ_ok = False                                # the locked version must visibly differ
    checks += [('every file opens as PNG', open_ok), ('every icon 256 x 256', sizes_ok), ('no empty or flat images', content_ok),
               ('frame intact at every corner (no unexpected cropping)', frame_ok), ('every locked icon clearly differs from its unlocked one', differ_ok),
               ('no duplicate API names', len(set(ids)) == 27)]
    hashes = {f: sha(os.path.join(ICONS, f)) for f in files}
    checks.append(('no two files identical', len(set(hashes.values())) == 54))
    mi.export(os.path.join(OUT, '.recheck'))                 # deterministic: a second export gives the same bytes
    same = all(sha(os.path.join(OUT, '.recheck', f)) == hashes[f] for f in files)
    for f in os.listdir(os.path.join(OUT, '.recheck')):
        os.remove(os.path.join(OUT, '.recheck', f))
    os.rmdir(os.path.join(OUT, '.recheck'))
    checks.append(('a second export is byte-identical (deterministic)', same))

    # ---- manifest
    with open(os.path.join(ICONS, 'manifest.csv'), 'w', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(['number', 'api_name', 'display_name', 'description', 'achieved_icon (unlocked)', 'unachieved_icon (locked)', 'sha256_unlocked', 'sha256_locked'])
        for api in ids:
            n, name, desc = rows[api]
            w.writerow([n, api, name, desc, f'{api}_unlocked.png', f'{api}_locked.png', hashes[f'{api}_unlocked.png'], hashes[f'{api}_locked.png']])

    # ---- the shareable PDF
    pdf(ids, rows)

    # ---- contact sheet from the exported files
    export_sheet(ids, rows)

    # ---- zip
    readme = ('Upwardly - Steam achievement icons (Steam App ID 5427130)\n\n'
              '54 PNG files, 256 x 256: for each achievement, <API name>_unlocked.png goes in "Achieved Icon" and\n'
              '<API name>_locked.png goes in "Unachieved Icon" on the Steamworks Achievements page.\n'
              'manifest.csv lists every API name with its display name, description and both files.\n'
              'Step-by-step entry: docs/STEAM_ACHIEVEMENTS_ENTRY_SHEET.md in the game repository.\n'
              'Artwork: original Hot Attic Games work, from renders of the game\'s own CC0 models (Quaternius, Kenney).\n')
    with zipfile.ZipFile(ZIP, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('Upwardly_Steam_Achievement_Icons/README.txt', readme)
        z.write(os.path.join(ICONS, 'manifest.csv'), 'Upwardly_Steam_Achievement_Icons/manifest.csv')
        for f in files:
            z.write(os.path.join(ICONS, f), f'Upwardly_Steam_Achievement_Icons/{f}')
    with zipfile.ZipFile(ZIP) as z:
        bad = z.testzip(); names = z.namelist()
        zipped_same = all(hashlib.sha256(z.read(f'Upwardly_Steam_Achievement_Icons/{f}')).hexdigest() == hashes[f] for f in files)
    checks.append(('zip opens, CRCs good, 54 PNGs + manifest + README, contents match the files', bad is None and len(names) == 56 and zipped_same))

    ok = all(v for _, v in checks)
    for k, v in checks:
        print(('PASS ' if v else 'FAIL ') + k)
    print('zip', os.path.getsize(ZIP), 'bytes, sha256', sha(ZIP))
    print('pdf', os.path.getsize(PDF), 'bytes')
    sys.exit(0 if ok else 1)


def font(size, bold=False):
    return ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf' % ('-Bold' if bold else ''), size)


def export_sheet(ids, rows):
    cols = 9
    W, H = 40 + cols * 236, 120 + 3 * 2 * 250 + 40
    im = Image.new('RGB', (W, H), (23, 26, 33)); d = ImageDraw.Draw(im)
    d.text((40, 30), 'EXPORT CHECK - the 54 files as written to icons/ (unlocked row, locked row below it)', font=font(30, True), fill=(255, 255, 255))
    for i, api in enumerate(ids):
        x, y = 40 + (i % cols) * 236, 100 + (i // cols) * 500
        for j, st in enumerate(('unlocked', 'locked')):
            ic = Image.open(os.path.join(ICONS, f'{api}_{st}.png')).resize((200, 200), Image.LANCZOS)
            im.paste(ic, (x, y + j * 250))
            d.text((x, y + 204 + j * 250), f'{rows[api][0]}. {api}', font=font(12), fill=(170, 180, 195))
    im.save(os.path.join(OUT, 'export_check.png'))


def pdf(ids, rows):
    from reportlab.lib.pagesizes import letter
    from reportlab.lib.utils import ImageReader
    from reportlab.pdfgen import canvas
    c = canvas.Canvas(PDF, pagesize=letter)
    c.setTitle('Upwardly - Steam achievements'); c.setAuthor('Hot Attic Games')
    Wp, Hp = letter
    per_page, row_h, top = 8, 84, Hp - 110

    def header(page):
        c.setFillColorRGB(0.09, 0.10, 0.13); c.rect(0, 0, Wp, Hp, stroke=0, fill=1)
        c.setFillColorRGB(1, 1, 1); c.setFont('Helvetica-Bold', 18); c.drawString(36, Hp - 48, 'Upwardly - Steam achievements (App ID 5427130)')
        c.setFont('Helvetica', 9.5); c.setFillColorRGB(0.75, 0.78, 0.84)
        c.drawString(36, Hp - 66, 'Style A "Sky Diorama". Each row: unlocked icon (Achieved Icon), locked icon (Unachieved Icon), name, API name.')
        c.drawString(36, Hp - 80, 'API names must be typed exactly as shown. Set By: Client. Hidden: unticked. Progress Stat: None.')
        c.drawRightString(Wp - 36, 24, f'page {page}')

    page = 1; header(page)
    for i, api in enumerate(ids):
        if i and i % per_page == 0:
            c.showPage(); page += 1; header(page)
        y = top - (i % per_page) * row_h - 72
        n, name, desc = rows[api]
        for j, st in enumerate(('unlocked', 'locked')):
            c.drawImage(ImageReader(os.path.join(ICONS, f'{api}_{st}.png')), 36 + j * 76, y, 68, 68)
        x = 36 + 2 * 76 + 10
        c.setFillColorRGB(1.0, 0.82, 0.35); c.setFont('Helvetica-Bold', 13); c.drawString(x, y + 50, f'{n}. {name}')
        c.setFillColorRGB(0.85, 0.88, 0.93); c.setFont('Courier-Bold', 11.5); c.drawString(x, y + 32, api)
        c.setFillColorRGB(0.75, 0.78, 0.84); c.setFont('Helvetica', 10); c.drawString(x, y + 15, desc)
        c.setFont('Helvetica', 8); c.setFillColorRGB(0.55, 0.6, 0.68); c.drawString(x, y + 1, f'{api}_unlocked.png  /  {api}_locked.png')
    # last page: the four stats
    c.showPage(); page += 1; header(page)
    c.setFillColorRGB(1, 1, 1); c.setFont('Helvetica-Bold', 14); c.drawString(36, top - 20, 'The four stats (enter these first, on the Stats page)')
    c.setFont('Helvetica', 10); c.setFillColorRGB(0.75, 0.78, 0.84)
    c.drawString(36, top - 40, 'Every stat: Type INT, Set By Client, Default 0, Min 0, Increment Only ticked, Max Change empty, Aggregated unticked.')
    stats = [('STAT_FALLS', 'Falls', 'empty', 'Lifetime falls back to a checkpoint'),
             ('STAT_MARATHON_M', 'Metres climbed', 'empty', 'Total new height climbed, metres'),
             ('STAT_TOWER_FELL_MASK', 'Towers fallen on', '1023', 'Which of the ten towers you have fallen on (bits)'),
             ('STAT_BEST_INFINITY_M', 'Best endless distance', 'empty', 'Best distance past the summit, metres')]
    for k, (api, name, mx, what) in enumerate(stats):
        y = top - 80 - k * 46
        c.setFillColorRGB(0.85, 0.88, 0.93); c.setFont('Courier-Bold', 12); c.drawString(36, y, api)
        c.setFillColorRGB(1.0, 0.82, 0.35); c.setFont('Helvetica-Bold', 11); c.drawString(250, y, name)
        c.setFillColorRGB(0.75, 0.78, 0.84); c.setFont('Helvetica', 10); c.drawString(250, y - 15, f'Max Val: {mx}.  {what}.')
    c.showPage(); c.save()


if __name__ == '__main__':
    main()
