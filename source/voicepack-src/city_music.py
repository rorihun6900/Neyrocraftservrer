"""
Neyro-City music: an original synthwave loop (100 BPM, A minor, 32 bars = 76.8 s, loops seamlessly).
Run with Blender's Python (it has numpy):
    "C:\\Games\\Steam\\steamapps\\common\\Blender\\5.3\\python\\bin\\python.exe" city_music.py
Writes city_music.wav next to this script; convert it to OGG with
    blender --background --python wav2ogg_stereo.py -- city_music.wav city_music.ogg
"""
import os
import struct
import wave

import numpy as np

SR = 44100
BPM = 100.0
BEAT = 60.0 / BPM
BAR = 4 * BEAT
BARS = 32
LOOP = BARS * BAR
N = int(round(LOOP * SR))
rng = np.random.default_rng(304)


def midi(n):
    return 440.0 * 2 ** ((n - 69) / 12)


# Am - F - C - G, then Am - F - Dm - E (darker turn); two bars each
PROG = [(57, "m"), (53, "M"), (48, "M"), (55, "M"), (57, "m"), (53, "M"), (50, "m"), (52, "M")]


def chord(root, kind):
    return [root, root + (3 if kind == "m" else 4), root + 7]


def env(n, a, d, s, r, sus_len):
    """ADSR envelope of n samples (times in seconds, sustain level s)."""
    t = np.arange(n) / SR
    e = np.ones(n) * s
    e = np.where(t < a, t / max(a, 1e-4), e)
    e = np.where((t >= a) & (t < a + d), 1 - (1 - s) * (t - a) / max(d, 1e-4), e)
    rel = t > sus_len
    e = np.where(rel, s * np.exp(-(t - sus_len) / max(r, 1e-4)), e)
    return e


def saw(f, n, phase=0.0):
    t = np.arange(n) / SR
    return 2 * ((f * t + phase) % 1.0) - 1


def pulse(f, n, width=0.5):
    t = np.arange(n) / SR
    return np.where(((f * t) % 1.0) < width, 1.0, -1.0)


def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i in range(len(x)):          # simple one-pole, fine for these lengths
        acc = (1 - a) * x[i] + a * acc
        y[i] = acc
    return y


def lowpass_fast(x, cutoff):
    # two cascaded one-poles via scipy-free recursion on blocks (vectorised with cumulative trick)
    a = np.exp(-2 * np.pi * cutoff / SR)
    b = 1 - a
    # IIR via exponential smoothing using np.frompyfunc is slow; use lfilter-like loop in chunks
    y = np.zeros_like(x)
    prev = 0.0
    step = 4096
    powers = a ** np.arange(1, step + 1)
    for s in range(0, len(x), step):
        seg = x[s:s + step]
        m = len(seg)
        # y[k] = a^(k+1) * prev + b * sum_{j<=k} a^(k-j) x[j]
        conv = np.convolve(seg, b * a ** np.arange(m))[:m]
        y[s:s + m] = conv + powers[:m] * prev
        prev = y[s + m - 1]
    return y


def place(buf, start, sig):
    i = int(round(start * SR)) % (2 * N)
    end = i + len(sig)
    if end <= len(buf):
        buf[i:end] += sig
    else:
        k = len(buf) - i
        buf[i:] += sig[:k]
        buf[:end - len(buf)] += sig[k:]


def render():
    # two loops in a row, keep the second: every tail (echo, release) wraps into the start
    L = np.zeros(2 * N)
    R = np.zeros(2 * N)
    for rep in range(2):
        t0 = rep * LOOP
        for bar in range(BARS):
            root, kind = PROG[(bar // 2) % len(PROG)]
            notes = chord(root, kind)
            section = bar // 8                       # 0 intro, 1 groove, 2 full, 3 full + lead
            bt = t0 + bar * BAR
            # ---- pad: detuned saws, wide
            if bar % 2 == 0:
                n = int(2 * BAR * SR)
                e = env(n, 0.6, 0.4, 0.8, 0.9, 2 * BAR - 0.3)
                for k, note in enumerate(notes + [notes[0] + 12]):
                    f = midi(note + 12)
                    l = saw(f * 0.997, n, 0.1 * k) * e
                    r = saw(f * 1.003, n, 0.37 * k) * e
                    place(L, bt, lowpass_fast(l, 1400 + 600 * (section >= 2)) * 0.05)
                    place(R, bt, lowpass_fast(r, 1400 + 600 * (section >= 2)) * 0.05)
            # ---- bass: 8th notes, octave jumps
            if section >= 1 or bar >= 4:
                for i in range(8):
                    note = root - 12 + (12 if i % 2 == 1 and section >= 2 else 0)
                    n = int(BEAT / 2 * SR)
                    e = env(n, 0.003, 0.08, 0.6, 0.05, BEAT / 2 * 0.7)
                    s = lowpass_fast(saw(midi(note), n) + 0.5 * pulse(midi(note) / 2, n), 700) * e * 0.32
                    place(L, bt + i * BEAT / 2, s)
                    place(R, bt + i * BEAT / 2, s)
            # ---- arpeggio: 16ths, with a ping-pong echo
            if section >= 1:
                pattern = [0, 1, 2, 3, 2, 1, 0, 2] * 2
                tones = notes + [notes[0] + 12]
                for i in range(16):
                    note = tones[pattern[i] % 4] + 24
                    n = int(BEAT / 4 * SR * 0.9)
                    e = env(n, 0.002, 0.05, 0.3, 0.04, BEAT / 4 * 0.5)
                    s = lowpass_fast(pulse(midi(note), n, 0.3), 3500) * e * 0.07
                    st = bt + i * BEAT / 4
                    pan = 0.35 if i % 2 == 0 else -0.35
                    place(L, st, s * (1 - pan))
                    place(R, st, s * (1 + pan))
                    place(R, st + 3 * BEAT / 4, s * 0.45)       # echo right
                    place(L, st + 6 * BEAT / 4, s * 0.25)       # echo left
            # ---- lead melody in the last section
            if section == 3:
                motif = [12, 10, 7, 10, 12, 15, 14, 12]
                for i, m in enumerate(motif):
                    note = root + 12 + m if kind == "m" else root + 12 + [12, 11, 7, 11, 12, 16, 14, 12][i]
                    n = int(BEAT / 2 * SR)
                    e = env(n, 0.01, 0.1, 0.7, 0.15, BEAT / 2 * 0.8)
                    t = np.arange(n) / SR
                    vib = np.sin(2 * np.pi * 5.5 * t) * 0.004
                    s = lowpass_fast(saw(midi(note) * (1 + vib), n), 2500) * e * 0.08
                    place(L, bt + i * BEAT / 2, s)
                    place(R, bt + i * BEAT / 2, s)
                    place(L, bt + i * BEAT / 2 + BEAT * 0.75, s * 0.3)
            # ---- drums
            if bar >= 2:
                for beat in range(4):
                    st = bt + beat * BEAT
                    if beat in (0, 2) or (section >= 2 and beat == 3 and bar % 2 == 1):
                        n = int(0.35 * SR)
                        t = np.arange(n) / SR
                        f = 50 + 110 * np.exp(-t * 30)
                        kick = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 9) * 0.55
                        place(L, st, kick)
                        place(R, st, kick)
                    if beat in (1, 3) and section >= 1:
                        n = int(0.45 * SR)
                        t = np.arange(n) / SR
                        noise = rng.standard_normal(n)
                        body = np.sin(2 * np.pi * 190 * t) * np.exp(-t * 25)
                        snare = (lowpass_fast(noise, 6000) * np.exp(-t * 11) * 0.28 + body * 0.25)
                        place(L, st, snare)
                        place(R, st, snare * 0.95)
                        place(L, st + 0.09, snare * 0.18)            # small room
                        place(R, st + 0.13, snare * 0.18)
                for h in range(8 if section >= 1 else 4):
                    st = bt + h * BEAT / (2 if section >= 1 else 1)
                    n = int(0.06 * SR)
                    t = np.arange(n) / SR
                    noise = rng.standard_normal(n)
                    hat = (noise - lowpass_fast(noise, 7000)) * np.exp(-t * 60) * (0.09 if h % 2 else 0.05)
                    place(L, st, hat * 0.8)
                    place(R, st, hat)
    L, R = L[N:], R[N:]
    peak = max(np.abs(L).max(), np.abs(R).max())
    L, R = np.tanh(L / peak * 1.2) * 0.85, np.tanh(R / peak * 1.2) * 0.85
    return L, R


def fade_ends(L, R, ms=10):
    """The game restarts the track on its own timer: soft 10 ms edges avoid a click at the restart."""
    k = int(SR * ms / 1000)
    ramp = np.linspace(0.0, 1.0, k)
    for ch in (L, R):
        ch[:k] *= ramp
        ch[-k:] *= ramp[::-1]
    return L, R


def write(path, L, R):
    L, R = fade_ends(L.copy(), R.copy())
    data = np.empty(2 * len(L), dtype=np.int16)
    data[0::2] = np.clip(L * 32767, -32768, 32767).astype(np.int16)
    data[1::2] = np.clip(R * 32767, -32768, 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    L, R = render()
    out = os.path.join(here, "city_music.wav")
    write(out, L, R)
    print("city_music.wav: %.1f s" % (len(L) / SR))
