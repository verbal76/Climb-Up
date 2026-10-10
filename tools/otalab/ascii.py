#!/usr/bin/env python3
"""ascii.py IMG [cols] -> a luminance thumbnail in ASCII (so a CI log shows what the screen looked like), plus the dominant colours."""
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB"); cols = int(sys.argv[2]) if len(sys.argv) > 2 else 72
w, h = im.size; rows = max(1, int(cols * h / w * 0.5))
sm = im.resize((cols, rows)); ramp = " .:-=+*#%@"
for y in range(rows):
    print("".join(ramp[min(9, int(sum(sm.getpixel((x, y))) / 3 / 25.6))] for x in range(cols)))
q = im.resize((64, 64)).quantize(6).convert("RGB"); cnt = sorted(q.getcolors(4096), reverse=True)[:4]
print("dominant colours:", ", ".join("#%02x%02x%02x(%d%%)" % (c[0], c[1], c[2], 100 * n // 4096) for n, c in cnt))
