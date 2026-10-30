#!/usr/bin/env python3
"""
Build 83 audio polish: original procedural cues for actions that had no sound of their own, a shared sound, or a weak one.

Deterministic (its own seeded generator; tools/gen_audio.py and every file it writes are untouched). Writes 22.05 kHz mono 16-bit WAVs to
assets/audio/ at -1 dBFS sample peak; the loudness balance is applied afterwards by tools/audio/master_sfx.py like every other effect.

    python3 -I tools/audio/gen_cues83.py [out_dir]

All sounds are band-limited by construction (sines, filtered noise, gentle FM), so nothing aliases at 22.05 kHz.
"""
import os, sys, wave
import numpy as np

SR = 22050
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), '..', '..', 'assets', 'audio')
rng = np.random.default_rng(8383)


def t_arr(d): return np.arange(int(SR * d)) / SR
def noise(n): return rng.uniform(-1, 1, n)
def lp(x, k):
    """one-pole low-pass (k = 0..1, larger = brighter)"""
    y = np.empty_like(x); acc = 0.0
    for i in range(len(x)):
        acc += k * (x[i] - acc); y[i] = acc
    return y
def hp(x, k): return x - lp(x, k)
def bp(x, lo, hi): return lp(x, hi) - lp(x, lo)
def adsr(n, a, d_tau):
    """attack a seconds (linear), then an exponential decay with time constant d_tau"""
    t = np.arange(n) / SR
    return np.where(t < a, t / max(a, 1e-6), np.exp(-(t - a) / d_tau))
def fade_tail(x, sec=0.015):
    n = min(len(x), int(SR * sec)); x = x.copy(); x[-n:] *= np.linspace(1, 0, n) ** 2; return x
def blip_end(m, sec=0.008):
    """a short linear fade at the end of a blip, so it never stops on a click"""
    return np.minimum(1.0, (m - np.arange(m)) / (SR * sec))
def phase(freq): return 2 * np.pi * np.cumsum(freq) / SR
def save(name, x, peak_db=-1.0):
    x = fade_tail(x - np.mean(x))
    x = x / (np.max(np.abs(x)) + 1e-12) * 10 ** (peak_db / 20)
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(np.round(x * 32767).astype('<i2').tobytes())


# boing: the bounce pad / spring. A short low knock (the pad giving way), then a springy rising tone whose wobble settles, with a faint metal
# ring on top so it still reads as a sprung pad. Replaces the quiet metal clang (pack clang10/11) as the pad's identity.
d = 0.48; t = t_arr(d); n = len(t)
knock = np.sin(phase(95 * np.exp(-t * 18) + 48)) * adsr(n, 0.002, 0.045)
f = 170 + 470 * (1 - np.exp(-t * 9))                         # rises fast, then levels
wob = 1 + 0.16 * np.exp(-t * 6) * np.sin(2 * np.pi * 17 * t)  # spring wobble that dies away
spring = np.sin(phase(f * wob)) * adsr(n, 0.004, 0.16)
ring = (np.sin(phase(np.full(n, 1180.0))) + 0.5 * np.sin(phase(np.full(n, 2950.0)))) * adsr(n, 0.001, 0.05) * 0.18
save('boing', knock * 0.9 + spring * 0.75 + ring)

# turbo: a turbo ramp's booster lighting - a hard ignition thump, a roar of air that swells and sweeps up, a little crackle.
d = 0.75; t = t_arr(d); n = len(t)
ign = np.sin(phase(70 * np.exp(-t * 10) + 40)) * adsr(n, 0.002, 0.07)
roar_env = np.minimum(1, t / 0.05) * np.exp(-np.maximum(0, t - 0.12) / 0.22)
cut = 0.06 + 0.30 * np.clip(t / 0.35, 0, 1)                   # the band opens as it fires
nz = noise(n); roar = np.empty(n); a1 = a2 = 0.0
for i in range(n):                                            # time-varying band-pass (two one-poles)
    a1 += cut[i] * (nz[i] - a1); a2 += 0.03 * (nz[i] - a2); roar[i] = a1 - a2
crackle = (rng.uniform(0, 1, n) > 0.985) * noise(n) * np.exp(-t * 5) * 0.5
tone = np.sin(phase(220 + 380 * np.clip(t / 0.4, 0, 1))) * roar_env * 0.12
save('turbo', ign * 0.8 + roar * roar_env * 2.4 + lp(crackle, 0.5) + tone)

# fizzle: a turbo flight cut short by a jump press - the booster sputters out (short falling hiss and two pops).
d = 0.34; t = t_arr(d); n = len(t)
hiss = bp(noise(n), 0.05, 0.5) * adsr(n, 0.004, 0.09) * 1.6
drop = np.sin(phase(520 * np.exp(-t * 7) + 90)) * adsr(n, 0.003, 0.08) * 0.35
pops = np.zeros(n)
for at in (0.07, 0.16):
    i = int(at * SR); m = int(0.025 * SR); tt = np.arange(m) / SR
    pops[i:i + m] += lp(noise(m), 0.4) * np.exp(-tt * 160) * 0.6
save('fizzle', hiss + drop + pops)

# slap: both hands slapping onto a ledge - a dry, bright palm smack with a short body. Layered under the grab click on a ledge catch.
d = 0.14; t = t_arr(d); n = len(t)
smack = hp(lp(noise(n), 0.75), 0.08) * adsr(n, 0.0008, 0.018)
body = np.sin(phase(np.full(n, 210.0))) * adsr(n, 0.001, 0.03) * 0.55
save('slap', smack * 1.3 + body)

# zing: catching a cable - the wire takes the weight and rings (a plucked metal tone with stretched partials and a short slide down).
d = 0.42; t = t_arr(d); n = len(t)
f0 = 392 * (1 - 0.05 * (1 - np.exp(-t * 20)))
z = sum(a * np.sin(phase(f0 * k)) * adsr(n, 0.001, tau) for k, a, tau in ((1.0, 1.0, 0.14), (2.03, 0.5, 0.09), (3.11, 0.3, 0.06), (4.27, 0.18, 0.04)))
tick = hp(noise(n), 0.3) * adsr(n, 0.0005, 0.006) * 0.6
save('zing', z + tick)

# zip: hand-over-hand along a cable - one tiny metal scrape per hand move (quiet, short, played sparingly).
d = 0.09; t = t_arr(d); n = len(t)
scr = bp(noise(n), 0.25, 0.85) * np.sin(np.pi * np.clip(t / d, 0, 1)) ** 1.5
glint = np.sin(phase(np.full(n, 1860.0))) * adsr(n, 0.002, 0.02) * 0.25
save('zip', scr + glint)

# ropecatch: grabbing a rope - fibres creak and the rope takes up the slack with a soft woody groan.
d = 0.26; t = t_arr(d); n = len(t)
fib = lp(noise(n), 0.35) * adsr(n, 0.004, 0.05) * 0.9
grains = (rng.uniform(0, 1, n) > 0.97) * noise(n)
groan = np.sin(phase(150 + 40 * np.sin(np.pi * np.clip(t / d, 0, 1)))) * np.sin(np.pi * np.clip(t / d, 0, 1)) ** 2 * 0.35
save('ropecatch', fib + lp(grains, 0.6) * adsr(n, 0.003, 0.09) * 0.7 + groan)

# thump: the extra weight of a hard landing (long falls) - a deep, short body hit under the normal landing sound.
d = 0.3; t = t_arr(d); n = len(t)
th = np.sin(phase(78 * np.exp(-t * 9) + 36)) * adsr(n, 0.0015, 0.07)
grit = lp(noise(n), 0.12) * adsr(n, 0.001, 0.035) * 0.7
save('thump', th + grit)

# warp: back at the checkpoint - a quick rising shimmer (positive and short, so a retry feels instant, not like a penalty).
d = 0.5; t = t_arr(d); n = len(t)
notes = [(0.00, 523.25), (0.05, 659.25), (0.10, 783.99), (0.15, 1046.5)]
w = np.zeros(n)
for at, fr in notes:
    i = int(at * SR); m = n - i; tt = np.arange(m) / SR
    w[i:] += np.sin(phase(np.full(m, fr)) + 0.6 * np.sin(2 * np.pi * fr * 2 * tt)) * adsr(m, 0.003, 0.09)
sweep = np.sin(phase(300 + 900 * np.clip(t / 0.25, 0, 1))) * adsr(n, 0.01, 0.08) * 0.35
spark = hp(noise(n), 0.5) * (rng.uniform(0, 1, n) > 0.992) * np.exp(-t * 6) * 0.8
save('warp', w * 0.5 + sweep + spark)

# pb: a new personal best (a castle faster than ever) - a bright four-note run up to a held major chord.
d = 0.95; t = t_arr(d); n = len(t)
p = np.zeros(n)
for at, fr, hold in ((0.0, 659.25, 0.09), (0.07, 783.99, 0.09), (0.14, 987.77, 0.09), (0.21, 1318.5, 0.6)):
    i = int(at * SR); m = n - i; tt = np.arange(m) / SR
    tone = np.sin(phase(np.full(m, fr))) + 0.35 * np.sin(phase(np.full(m, fr * 2))) + 0.12 * np.sin(phase(np.full(m, fr * 3)))
    p[i:] += tone * adsr(m, 0.004, hold) * (1.0 if hold > 0.3 else 0.7)
ch = np.zeros(n); i = int(0.21 * SR); m = n - i
for fr in (659.25, 830.61, 987.77):
    ch[i:] += np.sin(phase(np.full(m, fr))) * adsr(m, 0.01, 0.35) * 0.3
save('pb', p * 0.6 + ch)

# back: menu back / cancel - two short soft blips going down (the forward click goes up), so leaving a menu sounds different from choosing.
d = 0.11; t = t_arr(d); n = len(t)
b = np.zeros(n)
for at, fr in ((0.0, 880.0), (0.045, 587.33)):
    i = int(at * SR); m = min(n - i, int(0.05 * SR)); tt = np.arange(m) / SR
    b[i:i + m] += (np.sin(2 * np.pi * fr * tt) + 0.25 * np.sin(2 * np.pi * fr * 3 * tt)) * adsr(m, 0.001, 0.014) * blip_end(m)
save('back', b)

# rebound: the timed rebound off a cannon ball or saw - a tight springy ping that says "you beat it".
d = 0.24; t = t_arr(d); n = len(t)
ping = np.sin(phase(880 + 600 * (1 - np.exp(-t * 30)))) * adsr(n, 0.001, 0.06)
knk = lp(noise(n), 0.5) * adsr(n, 0.0005, 0.008)
save('rebound', ping + 0.7 * knk + 0.4 * np.sin(phase(np.full(n, 2950.0))) * adsr(n, 0.001, 0.03))

# select: menu choose / confirm - the same soft blip timbre as "back" but going up. Replaces the pack click (click_1, about -22 LUFS: a 50 ms spike
# that cannot be made louder without distortion) as the menu click, so choosing and leaving sound related and clearly different.
d = 0.11; t = t_arr(d); n = len(t)
c = np.zeros(n)
for at, fr in ((0.0, 659.25), (0.04, 987.77)):
    i = int(at * SR); m = min(n - i, int(0.06 * SR)); tt = np.arange(m) / SR
    c[i:i + m] += (np.sin(2 * np.pi * fr * tt) + 0.25 * np.sin(2 * np.pi * fr * 3 * tt)) * adsr(m, 0.001, 0.016) * blip_end(m)
save('select', c)

print('ok')
