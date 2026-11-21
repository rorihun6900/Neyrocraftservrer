"""Create the original NeyroCraft temple ambience WAV (run with Blender's Python)."""
import math
import sys
import wave

import numpy as np


def compose(path):
    rate, duration = 44100, 64
    rng = np.random.default_rng(718)
    clock = np.arange(rate * duration) / rate
    stereo = np.zeros((clock.size, 2))
    pi2 = 2 * math.pi

    def add(signal, start=0.0, pan=0.0):
        offset = int(start * rate)
        signal = signal[:len(stereo) - offset]
        balance = np.array([math.sqrt((1 - pan) / 2), math.sqrt((1 + pan) / 2)])
        stereo[offset:offset + len(signal)] += signal[:, None] * balance

    def frequency(note):
        return 440 * 2 ** ((note - 69) / 12)

    # Slow bowed bass and a faint beating fifth under the harmonic changes.
    for note, volume, pan in [(26, .18, 0), (38, .068, -.26), (45, .026, .36)]:
        pitch = frequency(note)
        phase = pi2 * pitch * clock + .23 * np.sin(pi2 * .13 * clock + note)
        add(volume * (np.sin(phase) + .27 * np.sin(phase * 2.002) + .09 * np.sin(phase * 3))
            * (.66 + .34 * np.sin(pi2 * .044 * clock + note) ** 2), pan=pan)

    chord_notes = ((50, 57, 65), (50, 58, 63), (48, 57, 64), (46, 53, 61),
                   (50, 57, 65), (51, 58, 66), (45, 52, 63), (50, 57, 62))
    local = np.arange(rate * 12) / rate
    envelope = np.sin(math.pi * local / 12) ** 2
    for bar, chord in enumerate(chord_notes):
        for index, note in enumerate(chord):
            pitch = frequency(note)
            phase = pi2 * pitch * local + .4 * np.sin(pi2 * .31 * local + note)
            air = np.sin(phase) + .11 * np.sin(2 * phase) + .04 * np.sin(5 * phase)
            add(air * envelope * [.034, .025, .013][index], bar * 8, [-.62, .62, .12][index])

    # Infrequent bells with inharmonic overtones and a decaying, alternating echo.
    bell_clock = np.arange(rate * 6) / rate
    for index, (when, note) in enumerate([(3, 62), (9, 63), (18, 57), (27, 66),
                                         (34, 62), (41, 65), (49, 58), (56, 61)]):
        phase = pi2 * frequency(note) * bell_clock
        bell = (np.sin(phase) + .28 * np.sin(2.76 * phase) + .12 * np.sin(4.08 * phase))
        bell *= np.minimum(1, bell_clock * 32) * np.exp(-bell_clock * 1.22) * .061
        pan = -.48 if index % 2 else .48
        for delay, gain in [(0, 1), (.41, .24), (.83, .13), (1.73, .07)]:
            add(bell * gain, when + delay, pan if delay == 0 else -pan)

    # Filtered noise gives the whispers an airy texture instead of a sharp hiss.
    noise = rng.standard_normal(clock.size)
    spectrum = np.fft.rfft(noise)
    bands = np.fft.rfftfreq(noise.size, 1 / rate)
    spectrum *= np.exp(-.5 * ((bands - 330) / 180) ** 2)
    whisper = np.fft.irfft(spectrum, n=noise.size)
    add(whisper * .14 * np.maximum(0, np.sin(pi2 * clock / 11 + .9)) ** 3, pan=-.4)
    add(np.roll(whisper, rate // 5) * .11 * np.maximum(0, np.sin(pi2 * clock / 13 + 1.8)) ** 3, pan=.55)

    fade = np.clip(np.minimum(clock, duration - clock) / 1.8, 0, 1)
    result = (np.tanh(stereo * 1.8) * fade[:, None] * 32767).astype("<i2")
    with wave.open(path, "wb") as sound:
        sound.setparams((2, 2, rate, 0, "NONE", "not compressed"))
        sound.writeframes(result.tobytes())


compose(sys.argv[sys.argv.index("--") + 1])
