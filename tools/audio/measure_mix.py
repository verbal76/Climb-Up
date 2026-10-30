#!/usr/bin/env python3
"""
Build 83: measures a recording made by desktop/src/test/.../AudioMixProbe through OpenAL Soft's wave writer (what the game really outputs).

    python3 -I tools/audio/measure_mix.py <recording.wav> <timeline.tsv>

Cuts the recording at the timeline's marks (aligned on the first sound) and prints, per segment: the loudest 100 ms (K-weighted, both
channels as in BS.1770, LUFS), the sample peak (dBFS) and how many samples went over full scale (would clip on a fixed-point output; OpenAL
Soft's own output limiter was switched off for the recording so they are visible).
"""
import importlib.util, math, os, subprocess, sys
import numpy as np

spec = importlib.util.spec_from_file_location("master", os.path.join(os.path.dirname(__file__), "master_sfx.py"))
M = importlib.util.module_from_spec(spec); spec.loader.exec_module(M)


def load_stereo(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "2", "-ar", "44100", "-f", "f64le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f8").reshape(-1, 2)


def l100(seg, sr=44100):
    if len(seg) == 0: return float("-inf")
    w = int(sr * 0.1)
    p = None
    for ch in range(2):
        k = M.k_weight(seg[:, ch].copy(), sr)
        if len(k) < w: k = np.concatenate([k, np.zeros(w - len(k))])
        c = np.concatenate([[0.0], np.cumsum(k * k)]); m = (c[w:] - c[:-w]) / w
        p = m if p is None else p + m
    return -0.691 + 10 * math.log10(max(float(np.max(p)), 1e-12))


def main():
    x = load_stereo(sys.argv[1]); sr = 44100
    marks = [l.rstrip("\n").split("\t") for l in open(sys.argv[2]) if l.strip()]
    marks = [(float(m[0]), m[1], m[2], m[3]) for m in marks]
    mono = np.max(np.abs(x), axis=1)
    onset = int(np.argmax(mono > 10 ** (-60 / 20)))
    off = onset / sr - marks[0][0] - 0.005                    # recording time = timeline time + off
    print("# alignment: first sound at %.3f s in the recording, timeline %.3f s" % (onset / sr, marks[0][0]))
    print("# kind\tstep\tcue\tL100_LUFS\tpeak_dBFS\tover_0dBFS")
    for i, (t, kind, step, cue) in enumerate(marks):
        if kind == "end": break
        t1 = marks[i + 1][0] if i + 1 < len(marks) else t + 1.3
        a = max(0, int((t + off) * sr)); b = min(len(x), int((t1 + off) * sr))
        seg = x[a:b]
        pk = float(np.max(np.abs(seg))) if len(seg) else 0.0
        over = int(np.sum(np.abs(seg) > 1.0))
        print("%s\t%s\t%s\t%.1f\t%.1f\t%d" % (kind, step, cue, l100(seg), 20 * math.log10(max(pk, 1e-9)), over))


if __name__ == "__main__":
    main()
