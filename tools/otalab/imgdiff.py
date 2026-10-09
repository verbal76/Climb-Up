#!/usr/bin/env python3
"""imgdiff.py A.png B.png -> prints 'mean=<0..255> exact=<fraction of identical pixels>'. Exit 0 always (the caller applies the threshold)."""
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert("RGB"); b = Image.open(sys.argv[2]).convert("RGB")
if a.size != b.size: print("size-mismatch %sx%s vs %sx%s" % (a.size + b.size)); sys.exit(0)
d = ImageChops.difference(a, b); px = list(d.getdata()); n = len(px)
mean = sum(sum(p) for p in px) / (3.0 * n); exact = sum(1 for p in px if p == (0, 0, 0)) / float(n)
print("mean=%.3f exact=%.4f" % (mean, exact))
