#!/usr/bin/env python3
"""
Derive the Enforcer's sounds from Helton Yan's "MECHA FOOTSTEPS" (FREE Retro Mecha SFX, CC BY 4.0, owner exception 2026-10-27; see ASSETS.md).

Each source is a 2.4 s stereo 44.1 kHz clip, mostly tail. This mixes it to mono, resamples to 22.05 kHz (the game's other effects' rate),
trims the tail after the hit (-36 dB below the clip's peak, at most 0.6 s for a step and 1.0 s for the stomp, with a 60 ms fade) and normalises to -1 dBFS. Usage:

    python3 -I tools/audio/mecha_steps.py <folder with "Mecha FOOTSTEP n.wav"> assets/audio
"""
import struct, sys, wave, os

PICKS = {"enf_step1": 3, "enf_step2": 9, "enf_step3": 17, "enf_step4": 27, "enf_stomp": 38}


def load(path):
    w = wave.open(path)
    ch, rate, n = w.getnchannels(), w.getframerate(), w.getnframes()
    a = struct.unpack("<%dh" % (n * ch), w.readframes(n))
    return [sum(a[i * ch:(i + 1) * ch]) / ch for i in range(n)], rate


def resample(x, src, dst):
    if src == dst: return x
    step, out, t = src / dst, [], 0.0
    while t < len(x) - 1:
        i = int(t); f = t - i
        out.append(x[i] * (1 - f) + x[i + 1] * f)
        t += step
    return out


def trim(x, rate, cap):
    peak = max(abs(v) for v in x) or 1.0
    floor, win = peak * 10 ** (-36 / 20), int(rate * 0.01)
    end = min(len(x), int(rate * cap))
    while end > win and max(abs(v) for v in x[end - win:end]) < floor: end -= win
    fade = int(rate * 0.06)
    y = x[:end]
    for i in range(min(fade, len(y))): y[len(y) - 1 - i] *= i / fade
    g = 32767 * 10 ** (-1 / 20) / peak
    return [max(-32768, min(32767, int(round(v * g)))) for v in y]


def main(src, dst):
    for name, n in PICKS.items():
        x, rate = load(os.path.join(src, "Mecha FOOTSTEP %d.wav" % n))
        y = trim(resample(x, rate, 22050), 22050, 1.0 if name == "enf_stomp" else 0.6)
        w = wave.open(os.path.join(dst, name + ".wav"), "wb")
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(22050)
        w.writeframes(struct.pack("<%dh" % len(y), *y)); w.close()
        print(name, "from FOOTSTEP", n, "%.2f s" % (len(y) / 22050))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
