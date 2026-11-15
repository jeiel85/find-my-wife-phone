"""Generates android/app/src/main/res/raw/findme.wav — the "여기 있어요" chime.

A bright ascending three-note arpeggio (E6, G#6, B6 + E7) with a soft bell envelope, repeated
twice per loop. Bright, high partials cut through rooms and pockets better than low tones, and
an arpeggio reads as "someone wants you" rather than an alarm clock. Standard library only.

    python design/make_sound.py
"""
import math
import struct
import wave
from pathlib import Path

RATE = 44100
OUT = Path(__file__).resolve().parent.parent / "android/app/src/main/res/raw/findme.wav"

NOTES = [1318.5, 1661.2, 1975.5, 2637.0]  # E6 G#6 B6 E7
NOTE_SEC = 0.13
GAP_SEC = 0.02
PHRASE_PAUSE = 0.18
LOOP_PAUSE = 0.55


def bell(freq: float, seconds: float) -> list[float]:
    n = int(RATE * seconds)
    out = []
    for i in range(n):
        t = i / RATE
        attack = min(1.0, t / 0.004)
        decay = math.exp(-t * 9.0)
        s = (math.sin(2 * math.pi * freq * t)
             + 0.45 * math.sin(2 * math.pi * freq * 2.0 * t) * math.exp(-t * 14)
             + 0.18 * math.sin(2 * math.pi * freq * 3.01 * t) * math.exp(-t * 20))
        out.append(s * attack * decay)
    return out


def silence(seconds: float) -> list[float]:
    return [0.0] * int(RATE * seconds)


def main() -> None:
    phrase: list[float] = []
    for f in NOTES:
        phrase += bell(f, NOTE_SEC) + silence(GAP_SEC)
    # Let the last note ring a bit longer.
    phrase += [s * 0.6 for s in bell(NOTES[-1], 0.25)]
    samples = phrase + silence(PHRASE_PAUSE) + phrase + silence(LOOP_PAUSE)
    peak = max(abs(s) for s in samples)
    gain = 0.89 / peak  # about -1 dBFS
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s * gain)) * 32767)) for s in samples))
    print(f"wrote {OUT} ({len(samples) / RATE:.2f}s)")


if __name__ == "__main__":
    main()
