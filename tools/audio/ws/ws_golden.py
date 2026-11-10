#!/usr/bin/env python3
"""Golden images for the Kotlin WonderSwan lowering (core WsGoldenTest): the reference builder (ws_song.build) applied
to the two songs the Kotlin test rebuilds from Thor Tracker's model (core/src/jvmTest/kotlin/org/thortracker/backend/
WsGoldenSongs.kt, whose KDoc defines them), written gzip-compressed (mtime 0) to core/src/jvmTest/resources/ws-golden/.

- mono.bin.gz: original WonderSwan: every lane kind, CH3 sweep, noise taps with a tap sequence, CPU-fed samples (a
  loop, a one-shot whose model loop start is not lowered), shared sample bytes and envelope bytes, a tone with a
  relative-pitch flag and no pitch envelope, fields of other kinds the lowering must leave out, four effect columns,
  the header's timer/shift/speaker settings, B jumps.
- color.bin.gz: WonderSwan Color: Hyper Voice with DMA (stereo, looping) and CPU feeds, Sound DMA on CH2 (backwards,
  one-shot, with arpeggio/pitch envelopes and a relative flag the lowering must drop), signed copies shared between
  identical samples, a CPU-only sample without one, H effects, slot 64 behind empty slots.

    python3 tools/audio/ws/ws_golden.py      # rewrites the two fixtures
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ws_ref as R  # noqa: E402
from ws_song import Env, Instrument, Sample, Song, cell, build, TONE, NOISE, SAMPLE  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "jvmTest", "resources", "ws-golden")


def mono():
    s = Song(model=R.MODEL_MONO, speed=6, timer=3, shift=2, output=R.OUTPUT_SPEAKER, loop=1)
    s.waves = [[i // 2 for i in range(32)], [15 if i < 16 else 0 for i in range(32)], [(5 * i) % 16 for i in range(32)]]
    a = bytes((37 * i + 11) % 256 for i in range(40))
    s.samples = [Sample(a), Sample(a), Sample(bytes((128, 0, 255)))]
    s.instruments = {
        # tone, wave 1, sweep -5 / time 3
        1: Instrument(TONE, source=1, volume=Env([15, 12, 8, 4, 0]), arpeggio=Env([0, 12], loop=0), sweep=-5,
                      sweep_time=3),
        # noise, tap 5, LFSR reset, relative pitch, tap sequence (its model wave, sweep and wave sequence not lowered)
        2: Instrument(NOISE, source=5, lfsr_reset=True, volume=Env([15, 10], loop=1), pitch=Env([-8, 8]),
                      relative=True, wave=Env([0, 3, 7], loop=2)),
        # CPU-fed sample 0, root 57, loop from 10, absolute pitch [4]
        3: Instrument(SAMPLE, source=0, root=57, loop=True, loop_start=10, volume=Env([15]), pitch=Env([4])),
        # CPU-fed sample 1, root 48, one-shot (its model loop start, wave sequence, rate and backwards not lowered)
        5: Instrument(SAMPLE, source=1, root=48, volume=Env([15, 12, 8, 4, 0]),
                      arpeggio=Env([-64, 63, 0], loop=0, release=2)),
        # tone, wave 2, wave sequence, relative flag with an empty pitch envelope
        6: Instrument(TONE, source=2, volume=Env([10]), wave=Env([0, 1, 2], loop=0), relative=True),
    }
    o0 = s.order(4)
    o0[0][0] = cell(57, 1, ("0", 0x37), ("W", 0x02))
    o0[0][2] = cell("OFF")
    o0[0][3] = cell(0, 0, ("B", 0x01))
    o0[1][0] = cell(48, 3, ("8", 0xF4), ("T", 0x02))
    o0[1][1] = cell(50, 0, ("P", 0x90))
    o0[2][0] = cell(60, 6, ("U", 0x05), ("V", 0x1F))
    o0[2][1] = cell(0, 0, ("1", 0x10))
    o0[2][3] = cell(0, 0, ("K", 0x03))
    o0[3][0] = cell(72, 2, ("N", 0x07), ("S", 0x02))
    o0[3][2] = cell(70, 0, ("G", 0x01))
    o1 = s.order(2)
    o1[0][0] = cell(19, 1, ("C", 0x0F), ("A", 0x21), ("4", 0x37), ("F", 0x03))
    o1[1][1] = cell(95, 5)
    return s


def color():
    s = Song(model=R.MODEL_COLOR, speed=3, timer=1, shift=0, output=R.OUTPUT_HEADPHONES, loop=0)
    s.waves = [[15 - i // 2 for i in range(32)]]
    m = bytes(12 * i for i in range(20))
    s.samples = [Sample(m), Sample(bytes(200 if i % 2 == 0 else 56 for i in range(16)), stereo=True), Sample(m),
                 Sample(bytes(255 - i for i in range(5)))]
    s.instruments = {
        1: Instrument(TONE, source=0, volume=Env([15])),
        # DMA, sample 0, rate 3, one-shot, backwards (its arpeggio, pitch, relative flag and root not lowered)
        2: Instrument(SAMPLE, source=0, dma=True, rate=3, reverse=True, volume=Env([15, 8]), arpeggio=Env([1, 2]),
                      pitch=Env([3]), relative=True, root=60),
        3: Instrument(SAMPLE, source=1, dma=True, rate=0, loop=True, volume=Env([12])),
        4: Instrument(SAMPLE, source=3, root=60, loop=True, loop_start=4, volume=Env([15])),
        5: Instrument(SAMPLE, source=2, dma=True, rate=2, volume=Env([15])),
        64: Instrument(NOISE, source=1, volume=Env([15])),
    }
    o0 = s.order(4)
    o0[0][0] = cell(57, 1)
    o0[1][0] = cell(48, 2, ("H", 0x01))
    o0[1][2] = cell(0, 0, ("H", 0x00))
    o0[3][1] = cell(60, 64)
    o0[4][0] = cell(60, 3, ("8", 0xF0))
    o0[4][2] = cell(50, 5)
    o0[4][3] = cell(62, 4, ("H", 0x01))
    return s


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, fn in (("mono", mono), ("color", color)):
        image = build(fn())
        R.Image(image)  # the reference loader accepts it
        path = os.path.join(OUT, name + ".bin.gz")
        with gzip.GzipFile(path, "wb", mtime=0) as f:
            f.write(image)
        print(path)


if __name__ == "__main__":
    main()
