#!/usr/bin/env python3
"""frames.py LATENCY_TXT -> frame-interval statistics from `dumpsys SurfaceFlinger --latency <layer>` output (nanosecond timestamps; the 2nd column is when each frame was presented)."""
import sys, statistics
rows = [l.split() for l in open(sys.argv[1]).read().splitlines()[1:] if len(l.split()) == 3]
t = [int(r[1]) for r in rows if int(r[1]) < 2**62]
t.sort()
if len(t) < 30: print("frames=%d (too few to summarise)" % len(t)); sys.exit(0)
d = [(b - a) / 1e6 for a, b in zip(t, t[1:]) if b > a]
d.sort(); n = len(d)
def pct(p): return d[min(n - 1, int(p * n))]
span = (t[-1] - t[0]) / 1e9
print("frames=%d span=%.1fs avg_fps=%.1f interval_ms: p50=%.1f p90=%.1f p95=%.1f p99=%.1f max=%.1f  over_20ms=%d over_33ms=%d over_100ms=%d" %
      (len(t), span, len(t) / span, pct(.5), pct(.9), pct(.95), pct(.99), d[-1], sum(1 for x in d if x > 20), sum(1 for x in d if x > 33.4), sum(1 for x in d if x > 100)))
