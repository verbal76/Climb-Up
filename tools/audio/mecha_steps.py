#!/usr/bin/env python3
"""
Derive the Enforcer's sounds from Helton Yan's FREE Retro Mecha SFX (CC BY 4.0, owner exception 2026-10-27; see ASSETS.md): five of the
"MECHA FOOTSTEPS" and three of the "MECHA SOUND SOURCES" (owner release "Enforcer", MECHA.SOUND.SOURCES.zip, sha256 571d9b27...).
The sources are unlabelled; they were chosen by measured shape (length, attack, brightness, noisiness), not by ear: 2 rises over 0.4 s
(the robot's alert), 55 is a short bright hit (a kick or swat landing), 19 a short bright noise burst (its jump jets).

Each source is a 2.4 s stereo 44.1 kHz clip, mostly tail. This mixes it to mono, resamples to 22.05 kHz (the game's other effects' rate),
trims the tail (-36 dB below the clip's peak, at most the length in PICKS, with a 60 ms fade) and normalises to -1 dBFS. Usage:

    python3 -I tools/audio/mecha_steps.py <folder with the "Mecha FOOTSTEP n.wav" and "Mecha SOUND SOURCE n.wav" files> assets/audio
"""
import struct, sys, wave, os

PICKS = {"enf_step1": ("Mecha FOOTSTEP", 3, 0.6), "enf_step2": ("Mecha FOOTSTEP", 9, 0.6), "enf_step3": ("Mecha FOOTSTEP", 17, 0.6),
         "enf_step4": ("Mecha FOOTSTEP", 27, 0.6), "enf_stomp": ("Mecha FOOTSTEP", 38, 1.0),
         "enf_alert": ("Mecha SOUND SOURCE", 2, 1.2), "enf_hit": ("Mecha SOUND SOURCE", 55, 0.6), "enf_jet": ("Mecha SOUND SOURCE", 19, 0.8)}


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
    for name, (stem, n, cap) in PICKS.items():
        path = os.path.join(src, "%s %d.wav" % (stem, n))
        if not os.path.exists(path): continue
        x, rate = load(path)
        y = trim(resample(x, rate, 22050), 22050, cap)
        w = wave.open(os.path.join(dst, name + ".wav"), "wb")
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(22050)
        w.writeframes(struct.pack("<%dh" % len(y), *y)); w.close()
        print(name, "from", stem, n, "%.2f s" % (len(y) / 22050))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
