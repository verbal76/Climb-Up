#!/usr/bin/env python3
"""
Build 83 SFX mastering: brings every sound effect to a measured loudness for its role, with true-peak headroom, without touching the originals.

Why: the owner found the effects too quiet even at EFFECTS VOLUME 10. libGDX clamps a Sound's play volume to 1.0, so a quiet file can never get
louder in the game; many pack clips were also very peaky (sample peaks at or above 0 dBFS but only -20..-28 LUFS loud), so a plain peak
normalise cannot help. This tool measures each file and writes a mastered copy:

  1. trim leading silence (below -50 dBFS, 2 ms pre-roll kept) and trailing silence (below -55 dBFS, 15 ms fade); loops are never trimmed
  2. measure loudness "L100": the loudest 100 ms window, K-weighted (ITU-R BS.1770 filters), in LUFS - fair to short clicks and long swells alike
  3. gain to the sound's target (from assets/data/sfx_mix.json, see below), at most +18 dB, found by a 0.5 dB grid search; a file whose
     peaks would need more than the budgets below stays short of its target (its transient is protected) and the report says so
  4. peak control on the 4x-oversampled signal: a soft clipper shaves only spikes poking more than CLIP_KNEE (3 dB) above the ceiling (at most
     MAX_CLIP, 6 dB), then a look-ahead limiter (attack = look-ahead 1.5 ms, release 12 ms, at most MAX_GR, 6 dB) holds -1.0 dBTP, so the
     16-bit output never clips and keeps headroom for the platform's resampler (pitch changes) and mixer
  5. write mono at the source rate to assets/audio/mastered/ (16-bit WAV for the game's own effects, OGG Vorbis q6 for pack clips, checked to
     decode under -0.5 dBTP) and every measurement to assets/data/sfx_levels.json

Inputs: the original effects in assets/audio/*.wav (synthesised / CC BY Enforcer clips) and the pack clips in assets/audio/pack/*.ogg named in
assets/data/sfx.json. Outputs are deterministic. Delete assets/audio/mastered/ (or set "mastered": false in assets/data/sfx_mix.json) to go back
to the original files.

    python3 -I tools/audio/master_sfx.py            (from the repository root; needs ffmpeg and numpy)
"""
import json, math, os, subprocess, sys, wave
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
AUD = os.path.join(ROOT, 'assets', 'audio')
OUT = os.path.join(AUD, 'mastered')
LEVELS = os.path.join(ROOT, 'assets', 'data', 'sfx_levels.json')
CEILING_DB = -1.0
MAX_GAIN_DB = 18.0
MAX_GR = 6.0                                                    # dB: most the limiter may take off (sustained peaks)
MAX_CLIP = 6.0                                                  # dB: most the clipper may shave off a spike
OGG_TP = -0.5                                                   # dBTP: most a decoded OGG master may reach (Vorbis overshoot allowance)
CLIP_KNEE = 3.0                                                 # dB above the ceiling where the clipper starts (below it only the limiter works)
RELEASE = 0.012                                                 # s: short, so a spike is shaved without pumping the body of the sound

# Targets come from assets/data/sfx_mix.json: each game sound has a class (major / action / texture, each with an IN-GAME target L100 at
# EFFECTS VOLUME 10) and "use", the loudest play volume the game asks for it. A file's target is the class target minus use (in dB), so the
# sound reaches its class level when the game plays it at that volume. A file used by several sounds takes the loudest target.
BEDS = ["motor"]                                                  # loops: never trimmed or limited, level unchanged
RANK = {"texture": 0, "action": 1, "major": 2}


def decode(path):
    sr = int(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate", "-of", "csv=p=0", path],
                            capture_output=True, text=True, check=True).stdout.strip())
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-f", "f64le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f8").copy(), sr


def biquad(x, b, a):
    b = [v / a[0] for v in b]; a = [v / a[0] for v in a]
    y = np.empty_like(x); x1 = x2 = y1 = y2 = 0.0
    b0, b1, b2, a1, a2 = b[0], b[1], b[2], a[1], a[2]
    for i, v in enumerate(x):
        o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, o
        y[i] = o
    return y


def k_weight(x, sr):
    # BS.1770 pre-filter (high shelf +4 dB at 1682 Hz) and RLB high-pass (38 Hz), for any sample rate
    G, Q, fc = 3.999843853973347, 0.7071752369554196, 1681.974450955533
    A = 10 ** (G / 40); w0 = 2 * math.pi * fc / sr; al = math.sin(w0) / (2 * Q); c = math.cos(w0); sA = math.sqrt(A)
    x = biquad(x, [A * ((A + 1) + (A - 1) * c + 2 * sA * al), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - 2 * sA * al)],
               [(A + 1) - (A - 1) * c + 2 * sA * al, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - 2 * sA * al])
    Q, fc = 0.5003270373238773, 38.13547087602444
    w0 = 2 * math.pi * fc / sr; al = math.sin(w0) / (2 * Q); c = math.cos(w0)
    return biquad(x, [(1 + c) / 2, -(1 + c), (1 + c) / 2], [1 + al, -2 * c, 1 - al])


def loudness(x, sr, win=0.1):
    """loudest `win`-second window, K-weighted, LUFS (a clip shorter than the window counts as padded with silence)"""
    k = k_weight(x, sr); w = max(1, int(sr * win))
    if len(k) < w: k = np.concatenate([k, np.zeros(w - len(k))])
    c = np.concatenate([[0.0], np.cumsum(k * k)])
    ms = np.max((c[w:] - c[:-w]) / w)
    return -0.691 + 10 * math.log10(max(ms, 1e-12))


def upsample4(x):
    n = len(x)
    if n == 0: return x
    X = np.fft.rfft(x, 2 * n)                                   # zero-padded to avoid wrap-around ringing
    Y = np.zeros(4 * n + 1, dtype=complex); Y[:len(X)] = X
    return np.fft.irfft(Y, 8 * n)[:4 * n] * 4


def true_peak_db(x):
    return 20 * math.log10(max(np.max(np.abs(upsample4(x))) if len(x) else 0.0, 1e-12))


def db(v): return 20 * math.log10(max(v, 1e-12))


def trim(x, sr, loop):
    if loop or len(x) == 0: return x
    thr_in, thr_out = 10 ** (-50 / 20), 10 ** (-55 / 20)
    on = np.nonzero(np.abs(x) > thr_in)[0]
    if len(on) == 0: return x
    i0 = max(0, on[0] - int(0.002 * sr))
    # the end: the last 10 ms block still above -55 dBFS
    blk = int(0.01 * sr); end = len(x)
    while end - blk > i0 and np.max(np.abs(x[end - blk:end])) < thr_out: end -= blk
    y = x[i0:end].copy()
    f = min(len(y), int(0.015 * sr)); y[-f:] *= np.linspace(1, 0, f)
    return y


def limit(x, sr, ceiling_db):
    """look-ahead peak limiter driven by the 4x-oversampled true peak; returns (output, max gain reduction dB)"""
    ceil = 10 ** (ceiling_db / 20)
    up = np.abs(upsample4(x)).reshape(-1, 4).max(axis=1) if len(x) else np.abs(x)
    need = np.minimum(1.0, ceil / np.maximum(up, 1e-12))
    la = max(1, int(0.0015 * sr)); rel = math.exp(-1.0 / (RELEASE * sr))
    # the gain must already be down when the peak arrives: minimum over the look-ahead window, then a release
    padded = np.concatenate([need, np.ones(la)])
    mins = np.lib.stride_tricks.sliding_window_view(padded, la + 1).min(axis=1)[:len(need)]
    g = np.empty_like(mins); cur = 1.0
    for i, m in enumerate(mins):
        cur = m if m < cur else m + (cur - m) * rel
        g[i] = cur
    # smooth the attack (moving average over the look-ahead) and never above what is needed
    k = np.ones(la) / la
    gs = np.minimum(np.convolve(np.concatenate([np.ones(la - 1) * g[0], g]), k, mode='valid'), mins)
    y = x * gs
    return y, -db(float(np.min(gs))) if len(gs) else 0.0


def downsample4(u, n):
    U = np.fft.rfft(u, 2 * len(u))                               # zero-padded, then keep the original band
    return np.fft.irfft(U[:n + 1], 2 * n)[:n] / 4


def soft_clip(x, level_db):
    """shaves only what pokes more than CLIP_KNEE dB above the ceiling (short spikes), on the 4x-oversampled signal so it does not alias;
    a smooth tanh knee above the level. Returns (output, dB taken off the highest peak)."""
    if len(x) == 0: return x, 0.0
    u = upsample4(x); a = np.abs(u); L = 10 ** (level_db / 20)
    over = a > L
    if not over.any(): return x, 0.0
    K = L * (10 ** (1 / 20) - 1)                                 # the knee rounds off at most 1 dB above the level
    w = np.where(over, L + K * np.tanh((a - L) / K), a)
    w = np.minimum(w, a)
    peak_in = float(a.max()); u = np.sign(u) * w
    y = downsample4(u, len(x))
    return y, db(peak_in) - db(float(np.max(np.abs(u))))


def process(x, sr, gain_db):
    y, clip = soft_clip(x * 10 ** (gain_db / 20), CEILING_DB + CLIP_KNEE)
    y, gr = limit(y, sr, CEILING_DB)
    return y, clip, gr


def master(x, sr, target, loop):
    x = trim(x, sr, loop)
    if target is None:                                           # beds/loops: level only, untouched shape
        return x, {"gainDb": 0.0, "clipDb": 0.0, "grDb": 0.0}
    l_in = loudness(x, sr)
    # grid search: the smallest gain that reaches the target, else the loudest result within the clip / limit budgets.
    # (Turning a sound down is a plain gain; a sound that is spiky stops where its budget ends instead of being squashed.)
    want = min(MAX_GAIN_DB, target - l_in)
    best = (l_in if want >= 0 else None, 0.0)                    # (loudness, gain): 0 dB is always allowed
    if want < 0:
        best = (None, want)
    else:
        g = 0.5
        while g <= want + 1e-6:
            y, clip, gr = process(x, sr, g)
            if clip > MAX_CLIP + 0.05 or gr > MAX_GR + 0.05: break
            l = loudness(y, sr)
            if l > best[0] + 0.05: best = (l, g)
            if l >= target - 0.2: break
            g += 0.5
    gain = best[1]
    y, clip, gr = process(x, sr, gain)
    # final safety: 16-bit rounding and the resampler must not cross the ceiling
    tp = true_peak_db(y)
    if tp > CEILING_DB: y *= 10 ** ((CEILING_DB - tp) / 20)
    return y, {"gainDb": round(gain, 2), "clipDb": round(clip, 2), "grDb": round(gr, 2)}


def write_wav(path, x, sr):
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes(np.clip(np.round(x * 32767), -32768, 32767).astype('<i2').tobytes())


def write_ogg(path, x, sr):
    """pack-derived clips stay OGG Vorbis like their sources (q6); Vorbis can overshoot peaks a little, so the decoded file is checked and the
    level lowered until it stays under -0.5 dBTP"""
    tmp = path + '.tmp.wav'
    for _ in range(4):
        write_wav(tmp, x, sr)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", tmp, "-c:a", "libvorbis", "-q:a", "6", path], check=True)
        z, _ = decode(path); tp = true_peak_db(z)
        if tp <= OGG_TP: break
        x = x * 10 ** ((OGG_TP - 0.1 - tp) / 20)
    os.remove(tmp)


def stats(x, sr):
    return {"dur": round(len(x) / sr, 3), "peakDb": round(db(float(np.max(np.abs(x))) if len(x) else 0.0), 2),
            "truePeakDb": round(true_peak_db(x), 2), "l100": round(loudness(x, sr), 2), "m400": round(loudness(x, sr, 0.4), 2)}


def main():
    os.makedirs(OUT, exist_ok=True)
    sfx_map = json.load(open(os.path.join(ROOT, 'assets', 'data', 'sfx.json')))
    mix = json.load(open(os.path.join(ROOT, 'assets', 'data', 'sfx_mix.json')))
    classes, sounds = mix["classes"], mix["sounds"]
    def target_of(game):
        e = sounds.get(game, {"class": "action", "use": 1.0})
        return classes[e["class"]]["target"] - 20 * math.log10(max(1e-3, e.get("use", 1.0))), e["class"]
    jobs = {}                                                    # out name -> [src path, target, class, loop]
    def want(key, src, game):
        t, c = target_of(game)
        old = jobs.get(key)
        if old is None or t > old[1]: jobs[key] = [src, t, c, False]
    for game in sounds:                                          # the game's own effects
        p = os.path.join(AUD, game + '.wav')
        if os.path.exists(p): want(game, p, game)
    for n in BEDS: jobs[n] = [os.path.join(AUD, n + '.wav'), None, "bed", True]
    for game, files in sfx_map.items():
        if game.startswith('_') or not isinstance(files, list): continue
        if game not in sounds: print('not mastered (no sfx_mix.json entry, not played):', game); continue
        for f in files:
            if f.startswith('@'): want(f[1:], os.path.join(AUD, f[1:] + '.wav'), game); continue
            p = os.path.join(AUD, 'pack', f + '.ogg')
            if not os.path.exists(p): print('missing', p); continue
            want('p_' + f, p, game)
    levels = {"_comment": "Written by tools/audio/master_sfx.py: per mastered file, the source, class, the measurements before (in) and after "
                          "(out) and the gain / peak limiting applied. l100 = loudest 100 ms K-weighted (LUFS), m400 = loudest 400 ms "
                          "(EBU momentary). dur (s) also feeds the game's voice accounting.",
              "ceilingDb": CEILING_DB, "maxGrDb": MAX_GR, "maxClipDb": MAX_CLIP, "clipKneeDb": CLIP_KNEE, "files": {}}
    for name in sorted(jobs):
        src, target, cls, loop = jobs[name]
        x, sr = decode(src)
        before = stats(x, sr)
        y, info = master(x, sr, target, loop)
        out = os.path.join(OUT, name + ('.ogg' if src.endswith('.ogg') else '.wav'))
        (write_ogg if out.endswith('.ogg') else write_wav)(out, y, sr)
        z, _ = decode(out)
        after = stats(z, sr)
        levels["files"][name] = {"src": os.path.relpath(src, ROOT).replace(os.sep, '/'), "class": cls, "rate": sr, "in": before, "out": after,
                                 "target": None if target is None else round(target, 2), **info}
        short = "" if target is None or after["l100"] > target - 1.0 else "  (short of target %.1f: transient protected)" % target
        print(f"{name:22s} {cls:7s} L100 {before['l100']:6.1f} -> {after['l100']:6.1f}  TP {before['truePeakDb']:5.1f} -> {after['truePeakDb']:5.1f}"
              f"  gain {info['gainDb']:+5.1f}  clip {info['clipDb']:4.1f}  GR {info['grDb']:4.1f}  {before['dur']:.2f}s -> {after['dur']:.2f}s{short}")
    with open(LEVELS, 'w') as f: json.dump(levels, f, indent=1, sort_keys=True); f.write('\n')


if __name__ == '__main__':
    main()
