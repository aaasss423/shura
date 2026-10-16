"""Generate the game's original ten-minute ambient soundtrack (stdlib only)."""

import array
import math
import sys
import wave
from pathlib import Path

RATE = 44_100
SECONDS = 60
REPEATS = 10
TAU = math.tau
OUT = Path(__file__).resolve().parents[1] / "assets" / "star-catcher-ost.wav"


def make_motif(root):
    frames_per_block = RATE
    two_pi_over_rate = TAU / RATE
    # A slow, warm, original synth pad with a gentle, repeating star-like pulse.
    frequencies = (root, root * 1.4983, root * 2.0, root * 2.9966)
    phases = [0.0] * len(frequencies)
    increments = [f * two_pi_over_rate for f in frequencies]
    blocks = []
    for second in range(SECONDS):
        samples = array.array("h")
        for frame in range(frames_per_block):
            t = second + frame / RATE
            fade = min(1.0, t / 3.0, (SECONDS - t) / 3.0)
            swell = 0.52 + 0.18 * math.sin(TAU * t / 24.0) + 0.09 * math.sin(TAU * t / 8.0)
            a, b, c, d = phases
            left = (0.17 * math.sin(a) + 0.105 * math.sin(b) + 0.052 * math.sin(c) + 0.023 * math.sin(d)) * swell * fade
            right = (0.17 * math.sin(a + .035) + 0.105 * math.sin(b - .045) + 0.052 * math.sin(c + .07) + 0.023 * math.sin(d - .08)) * swell * fade
            samples.append(max(-32767, min(32767, int(left * 32767))))
            samples.append(max(-32767, min(32767, int(right * 32767))))
            for i in range(4):
                phases[i] += increments[i]
                if phases[i] >= TAU:
                    phases[i] -= TAU
        if sys.byteorder != "little":
            samples.byteswap()
        blocks.append(samples.tobytes())
    return blocks


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT), "wb") as wav:
        wav.setnchannels(2)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.setnframes(RATE * SECONDS * REPEATS)
        motif = make_motif(110.0)
        for _ in range(REPEATS):
            for block in motif:
                wav.writeframesraw(block)
    print(f"Wrote {OUT} ({OUT.stat().st_size:,} bytes, ten minutes)")


if __name__ == "__main__":
    main()
