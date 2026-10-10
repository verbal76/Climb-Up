#!/usr/bin/env python3
"""imgdiff.py A.png B.png -> prints 'mean=<0..255> exact=<fraction of identical pixels>'. Exit 0 always (the caller applies the threshold)."""
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert("RGB"); b = Image.open(sys.argv[2]).convert("RGB")
if a.size != b.size: print("size-mismatch %sx%s vs %sx%s" % (a.size + b.size)); sys.exit(0)
d = ImageChops.difference(a, b); px = list(d.getdata()); n = len(px)
mean = sum(sum(p) for p in px) / (3.0 * n); exact = sum(1 for p in px if p == (0, 0, 0)) / float(n)
lum = lambda im: list(im.convert("L").getdata())
la, lb = lum(a), lum(b); ma, mb = sum(la) / n, sum(lb) / n
cov = sum((x - ma) * (y - mb) for x, y in zip(la, lb)); va = sum((x - ma) ** 2 for x in la); vb = sum((y - mb) ** 2 for y in lb)
corr = cov / ((va * vb) ** 0.5) if va > 0 and vb > 0 else 0.0
print("mean=%.3f exact=%.4f corr=%.3f size=%dx%d" % (mean, exact, corr, a.size[0], a.size[1]))
