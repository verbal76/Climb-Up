#!/usr/bin/env python3
"""Builds a multi-size Windows .ico (PNG-compressed frames) from the existing game icon, for jpackage. usage: make_ico.py <in.png> <out.ico>"""
import struct, sys, io
from PIL import Image
src, out = sys.argv[1], sys.argv[2]
im = Image.open(src).convert("RGBA")
sizes = [16, 24, 32, 48, 64, 128, 256]
frames = []
for s in sizes:
    b = io.BytesIO(); im.resize((s, s), Image.LANCZOS).save(b, "PNG"); frames.append(b.getvalue())
hdr = struct.pack("<HHH", 0, 1, len(sizes))
off = 6 + 16 * len(sizes); ents = b""
for s, d in zip(sizes, frames):
    ents += struct.pack("<BBBBHHII", 0 if s == 256 else s, 0 if s == 256 else s, 0, 0, 1, 32, len(d), off); off += len(d)
open(out, "wb").write(hdr + ents + b"".join(frames))
print("wrote", out, off, "bytes")
