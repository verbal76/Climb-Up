#!/usr/bin/env python3
"""Original procedural audio for Climb up (sfxr-style synth + small chord sequencer). Deterministic; output is committed to assets/audio."""
import numpy as np, wave, os, sys
SR = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', 'assets', 'audio')
rng = np.random.default_rng(1234)

def t_arr(d): return np.arange(int(SR * d)) / SR
def env(n, a=0.005, r=0.05, curve=1.0):
    e = np.ones(n); na = min(n, max(1, int(a * SR))); nr = min(n, max(1, int(r * SR)))
    e[:na] = np.linspace(0, 1, na); e[-nr:] *= np.linspace(1, 0, nr) ** curve
    return e
def tri(ph): return 2 * np.abs(2 * (ph % 1) - 1) - 1
def sq(ph, duty=0.5): return np.where((ph % 1) < duty, 1.0, -1.0)
def sweep(f0, f1, d, wave_fn=np.sin, exp=True):
    t = t_arr(d)
    f = f0 * (f1 / f0) ** (t / d) if exp else np.linspace(f0, f1, len(t))
    ph = np.cumsum(f) / SR
    return wave_fn(2 * np.pi * ph) if wave_fn is np.sin else wave_fn(ph)
def noise(d): return rng.uniform(-1, 1, int(SR * d))
def lp(x, k):
    y = np.zeros_like(x); a = k
    for i in range(1, len(x)): y[i] = y[i - 1] + a * (x[i] - y[i - 1])
    return y
def save(name, x, vol=0.8):
    x = x / (np.max(np.abs(x)) + 1e-9) * vol
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype('<i2').tobytes())

def note(f, d, shape='tri', vol=1.0, a=0.01, r=0.1):
    t = t_arr(d); ph = f * t
    w = tri(ph) if shape == 'tri' else sq(ph, 0.4) if shape == 'sq' else np.sin(2 * np.pi * ph)
    return w * env(len(t), a, r) * vol
def mix(parts, total):
    out = np.zeros(int(SR * total))
    for start, x in parts:
        i = int(start * SR); j = min(len(out), i + len(x))
        if i < len(out): out[i:j] += x[:j - i]
    return out
N = lambda m: 440.0 * 2 ** ((m - 69) / 12)

# ---------------- sfx
save('jump', sweep(260, 640, 0.16, np.sin) * env(int(SR * 0.16), 0.003, 0.08))
save('land', (lp(noise(0.1), 0.15) * 1.2 + note(80, 0.1, 'sin', 1.0, 0.002, 0.08)) * env(int(SR * 0.1), 0.001, 0.07))
x = sweep(180, 820, 0.38, np.sin); x *= (1 + 0.25 * np.sin(2 * np.pi * 22 * t_arr(0.38))); save('bounce', x * env(len(x), 0.004, 0.2))
save('grab', mix([(0, note(900, 0.05, 'sq', 0.5, 0.001, 0.04)), (0.05, note(1300, 0.08, 'sq', 0.5, 0.001, 0.07))], 0.14))
save('pull', mix([(0, note(N(64), 0.08, 'tri')), (0.08, note(N(67), 0.08, 'tri')), (0.16, note(N(72), 0.14, 'tri'))], 0.3))
c = lp(noise(0.55), 0.35) * env(int(SR * 0.55), 0.01, 0.4) ; c += (noise(0.55) * (rng.uniform(0, 1, int(SR * 0.55)) > 0.995)) * 0.8; save('crumble', c)
save('checkpoint', mix([(i * 0.09, note(N(m), 0.3, 'tri', 0.8, 0.005, 0.2)) for i, m in enumerate([72, 76, 79, 84])], 0.7))
save('respawn', sweep(700, 70, 0.45, np.sin) * env(int(SR * 0.45), 0.005, 0.2))
fan = [(0, 67, .12), (.12, 72, .12), (.24, 76, .12), (.36, 79, .2), (.58, 76, .1), (.68, 79, .5)]
save('win', mix([(s, note(N(m), d + 0.1, 'tri', 0.8, 0.005, 0.1) + 0.4 * note(N(m) * 2, d + 0.1, 'sq', 0.3, 0.005, 0.1)) for s, m, d in fan], 1.4))
save('click', note(1200, 0.04, 'sq', 0.5, 0.001, 0.03))
save('rope', lp(noise(0.07), 0.25) * env(int(SR * 0.07), 0.002, 0.05) + note(520, 0.07, 'tri', 0.2))
save('step', lp(noise(0.05), 0.2) * env(int(SR * 0.05), 0.001, 0.04))

# ---------------- effort grunts (voiced saw through a low-pass + breath noise): 'hnnf', 'ungh', 'hup'
def grunt(f0, d, drop, breath, seed):
    r = np.random.default_rng(seed)
    t = t_arr(d); f = f0 * (1 - drop * t / d) * (1 + 0.02 * np.sin(2 * np.pi * 31 * t))
    ph = np.cumsum(f) / SR; src = 2 * (ph % 1) - 1
    v = lp(lp(src, 0.10), 0.18)
    nz = lp(r.uniform(-1, 1, len(t)), 0.35) * breath
    env = (1 - np.exp(-t * 90)) * np.exp(-t * (5.5 if d < 0.3 else 4.0))
    return (v * 1.4 + nz) * env
save('grunt1', grunt(112, 0.26, 0.30, 0.45, 1))
save('grunt2', grunt(96, 0.32, 0.38, 0.55, 2))
save('grunt3', grunt(128, 0.22, 0.22, 0.40, 3))
save('effort', grunt(150, 0.30, -0.35, 0.50, 4))      # rising 'huup' for the pull-up

# ---------------- music (chord sequencer, loops cleanly on a bar boundary)
def song(name, bpm, bars, prog, seed, bright):
    r = np.random.default_rng(seed); beat = 60.0 / bpm; total = bars * 4 * beat
    parts = []
    scale = [0, 2, 4, 7, 9]  # major pentatonic
    for bar in range(bars):
        root = prog[bar % len(prog)]
        t0 = bar * 4 * beat
        # pad
        for k in (0, 7, 12 + 4 if bright else 12 + 3):
            parts.append((t0, note(N(48 + root + k), 4 * beat, 'sin', 0.22, 0.4, 0.5)))
        # bass
        for b in range(4):
            if b in (0, 2): parts.append((t0 + b * beat, note(N(36 + root), beat * 0.9, 'tri', 0.5, 0.01, 0.15)))
        # arpeggio
        for i in range(8):
            deg = scale[int(r.integers(0, 5))] + (12 if r.random() < 0.35 else 0)
            if r.random() < 0.78:
                parts.append((t0 + i * beat / 2, note(N(60 + root + deg), beat * 0.45, 'sq' if bright else 'tri', 0.28 if bright else 0.34, 0.004, 0.12)))
        # soft tick
        for b in range(4): parts.append((t0 + b * beat, lp(noise(0.04), 0.3) * env(int(SR * 0.04), 0.001, 0.03) * 0.15))
    x = mix(parts, total)
    # make the loop seamless: apply tiny fade at the ends
    n = int(0.01 * SR); x[:n] *= np.linspace(0, 1, n); x[-n:] *= np.linspace(1, 0, n)
    save(name, x, 0.55)
song('music_menu', 84, 8, [0, 5, 7, 2], 11, False)
#song('music_game', 112, 12, [0, 9, 5, 7], 21, True)

# ---------------- hazards (added last so earlier sounds keep their seeded noise)
r2 = np.random.default_rng(777)
def noise2(d): return r2.uniform(-1, 1, int(SR * d))
d = 0.42; t = t_arr(d)
hit = lp(noise2(d), 0.4) * np.exp(-t * 9) + sweep(240, 55, d, np.sin) * np.exp(-t * 7) + 0.5 * sweep(1400, 300, d, lambda p: sq(p, 0.3)) * np.exp(-t * 22)
save('hit', hit * env(len(t), 0.001, 0.1))
d = 0.6; t = t_arr(d)
boom = lp(noise2(d), 0.12) * np.exp(-t * 6) * 1.3 + sweep(120, 38, d, np.sin) * np.exp(-t * 5)
save('cannon', boom * env(len(t), 0.001, 0.25))
d = 0.5; t = t_arr(d)
whir = (sq(np.cumsum(520 + 90 * np.sin(2 * np.pi * 7 * t)) / SR, 0.5) * 0.35 + lp(noise2(d), 0.3) * 0.5) * (0.6 + 0.4 * np.sin(2 * np.pi * 14 * t))
save('saw', whir * env(len(t), 0.05, 0.1))
d = 0.22; t = t_arr(d)
save('spikes', (lp(noise2(d), 0.5) * np.exp(-t * 18) + note(1600, d, 'sq', 0.3, 0.001, 0.08)) * env(len(t), 0.001, 0.06))
d = 0.5; t = t_arr(d)
save('key', mix([(0, note(N(79), 0.12, 'tri', 0.8, 0.002, 0.1)), (0.08, note(N(84), 0.12, 'tri', 0.8, 0.002, 0.1)), (0.16, note(N(91), 0.3, 'tri', 0.7, 0.002, 0.25)), (0.16, 0.4 * note(N(103), 0.3, 'sin', 0.5, 0.002, 0.25))], 0.5))
d = 0.7; t = t_arr(d)
creak = sq(np.cumsum(180 + 120 * (t / d) ** 2 + 25 * np.sin(2 * np.pi * 11 * t)) / SR, 0.3) * 0.3 * env(len(t), 0.02, 0.3)
thud = np.zeros(len(t)); k0 = int(0.45 * SR); tt = t_arr(0.25); thud[k0:k0 + len(tt)] = (np.sin(2 * np.pi * 70 * tt) + 0.5 * lp(noise2(0.25), 0.2)) * np.exp(-tt * 14)
save('door', creak + thud)
d = 0.25; t = t_arr(d)
save('locked', (sweep(160, 90, d, np.sin) * np.exp(-t * 14) + 0.4 * lp(noise2(d), 0.3) * np.exp(-t * 30)) * env(len(t), 0.001, 0.08))
d = 0.28; t = t_arr(d)
sw = lp(noise2(d), 0.55) * np.sin(np.pi * t / d) ** 1.5 * 0.8 + sweep(300, 900, d, np.sin) * 0.15 * np.sin(np.pi * t / d)
save('swing', sw)
d = 0.2; t = t_arr(d)
save('bonk', (sweep(420, 140, d, np.sin) * np.exp(-t * 16) + 0.3 * lp(noise2(d), 0.4) * np.exp(-t * 40)) * env(len(t), 0.001, 0.06))
d = 0.35; t = t_arr(d)
save('squeak', sweep(900, 2100, d, np.sin) * (0.6 + 0.4 * np.sin(2 * np.pi * 26 * t)) * np.exp(-t * 7) * env(len(t), 0.002, 0.1))
d = 0.45; t = t_arr(d)
save('poof', lp(noise2(d), 0.12) * (1 - np.exp(-t * 60)) * np.exp(-t * 7) * 0.9 + 0.25 * sweep(500, 1400, d, np.sin) * np.exp(-t * 9))
d = 0.4; t = t_arr(d)
buzz = sq(np.cumsum(190 + 14 * np.sin(2 * np.pi * 30 * t)) / SR, 0.5) * 0.35 + 0.15 * np.sin(2 * np.pi * 380 * t)
save('buzz', lp(buzz, 0.5) * env(len(t), 0.03, 0.12))
d = 1.6; t = t_arr(d)
# spaceship fly-by: a doppler-ish sweep (pitch falls as it passes) over a swell of filtered noise
doppler = sq(np.cumsum(260 - 160 * (t / d) ** 1.4) / SR, 0.5) * 0.18 + sweep(520, 130, d, np.sin) * 0.2
rush = lp(noise2(d), 0.25) * np.sin(np.pi * np.clip(t / d, 0, 1)) ** 2.2 * 0.9
save('whoosh', (doppler + rush) * np.sin(np.pi * np.clip(t / d, 0, 1)) ** 0.8)
print('ok')
