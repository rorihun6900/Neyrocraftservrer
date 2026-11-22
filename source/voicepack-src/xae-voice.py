"""Export one original greeting and matching, non-cloned voice lines to mono Vorbis."""
from pathlib import Path
import sys

import aud
import numpy as np


def statistics(sound):
    rate = int(sound.specs[0])
    mono = sound.data().mean(axis=1)
    rms = float(np.sqrt(np.mean(mono * mono)))
    step = max(1, rate // 12_000)
    sample_rate = rate / step
    signal = mono[::step]
    window = int(sample_rate * 0.045)
    hop = int(sample_rate * 0.017)
    low_lag = int(sample_rate / 440)
    high_lag = int(sample_rate / 100)
    pitches = []
    for start in range(0, len(signal) - window, hop):
        chunk = signal[start:start + window].astype(np.float64)
        chunk -= chunk.mean()
        if np.sqrt(np.mean(chunk * chunk)) < max(0.008, rms * .62):
            continue
        corr = np.correlate(chunk, chunk, mode="full")[window - 1:window + high_lag + 2]
        if corr[0] <= 0:
            continue
        corr *= window / ((window - np.arange(len(corr))) * corr[0])
        peaks = [
            lag for lag in range(low_lag, high_lag)
            if corr[lag - 1] < corr[lag] >= corr[lag + 1] and corr[lag] > .5
        ]
        if peaks:
            threshold = .86 * max(corr[lag] for lag in peaks)
            first = next(lag for lag in peaks if corr[lag] >= threshold)
            pitches.append(sample_rate / first)
    return mono, rms, float(np.median(pitches)) if pitches else None, len(pitches)


def export(sound, path):
    sound.write(str(path), 48000, aud.CHANNELS_MONO, aud.FORMAT_S16,
                aud.CONTAINER_OGG, aud.CODEC_VORBIS, 96_000, 2048)
    result = aud.Sound.file(str(path))
    return len(result.data()) / result.specs[0]


def main():
    greeting_file, dialogue_file, work, output = map(Path, sys.argv[sys.argv.index("--") + 1:])
    greeting = aud.Sound.file(str(greeting_file))
    mono, reference_rms, pitch, voiced = statistics(greeting)
    duration = export(greeting, output / "greeting.ogg")
    print(f"REFERENCE duration={duration:.3f}s peak={max(abs(mono)):.3f} "
          f"rms={reference_rms:.3f} median_pitch={pitch or 0:.1f}Hz voiced_windows={voiced}")
    rows = [f"greeting\t{duration:.3f}\tАра-ара…"]
    for line in dialogue_file.read_text(encoding="utf-8-sig").splitlines():
        key, subtitle = line.split("\t", maxsplit=1)
        original = aud.Sound.file(str(work / f"{key}.wav"))
        words, rms, voice_pitch, _ = statistics(original)
        # A small, bounded pitch adjustment approximates the feel of the sample. It does not clone it.
        factor = float(np.clip(pitch / voice_pitch, 0.92, 1.12)) if pitch and voice_pitch else 1.0
        gain = float(np.clip(reference_rms / rms, .6, 1.75)) if rms > 0 else 1.0
        gain = min(gain, .78 / max(float(max(abs(words))), .01))
        processed = original.pitch(factor).volume(gain)
        duration = export(processed, output / f"{key}.ogg")
        rows.append(f"{key}\t{duration:.3f}\t{subtitle}")
        print(f"{key}: {duration:.3f}s, voice_pitch={voice_pitch or 0:.1f}Hz, pitch_factor={factor:.3f}")
    (output / "lines.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
