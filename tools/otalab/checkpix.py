#!/usr/bin/env python3
"""Reads an emulator screenshot and reports which synthetic-module clear colour the screen shows: 'OK v1' / 'OK v2' / 'OTHER r,g,b'. Samples several points; all must agree."""
import sys
from PIL import Image
EXPECT = {"v1": (33, 36, 56), "v2": (66, 36, 23)}      # clear colours of the synthetic module (hue 0.37*version, untouched)
im = Image.open(sys.argv[1]).convert("RGB"); w, h = im.size
pts = [(w // 2, h // 2), (w // 3, h // 3), (2 * w // 3, h // 2), (w // 2, 2 * h // 3)]
cols = [im.getpixel(p) for p in pts]
for name, e in EXPECT.items():
    if all(all(abs(c[i] - e[i]) <= 14 for i in range(3)) for c in cols):
        print("OK " + name); sys.exit(0)
print("OTHER " + " ".join("%d,%d,%d" % c for c in cols)); sys.exit(1)
