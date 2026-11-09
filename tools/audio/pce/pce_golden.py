#!/usr/bin/env python3
"""Golden images for the Kotlin PC Engine lowering (core PceGoldenTest): the reference builder (pce_song.build)
applied to songs the Kotlin test rebuilds from Thor Tracker's model (PceGoldenSongs.kt), written gzip-compressed to
core/src/jvmTest/resources/pce-golden/.

- spike.bin.gz: the host test's song (pce_song.test_song) as it is (it holds no explicit "000", the one cell Thor
  Tracker's cells cannot store).
- features.bin.gz: a song for what the test song does not reach: notes A-0 (9) and B-7 (95), instrument slot 64
  behind empty slots, all 64 waves, a 65535-frame sample (so later samples lie past offset 65535), two identical
  samples stored once, a one-frame looping sample, sample loop starts (and one kept in the model without a loop,
  lowered as 0), a 255-step relative pitch envelope, arpeggio values -64 and 63, a wave envelope, a noise sound whose
  model wave and wave envelope are not lowered, envelopes shared byte-for-byte between sounds (their loop and release
  differ), four effect columns on one row, blank columns before used ones, an instrument without a note, orders of 64,
  20 and 1 rows, multispeed 2, the shared header at its extremes (master $5A, LFO frequency $FF, control $83, timer
  $7F), and Bxx/Dxx (the Kotlin song parks an order, so its jumps and loop are renumbered by the arrangement).

    python3 tools/audio/pce/pce_golden.py      # rewrites the two fixtures
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pce_song import Cell as C, Env, Instrument, NOTE_OFF, Order, Song, build, test_song  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "jvmTest", "resources", "pce-golden")


def spike_song():
    return test_song()


def features_waves():
    return [[(i * (w % 7 + 1) + w) % 32 for i in range(32)] for w in range(64)]


def features_samples():
    small = [(i * 5) % 32 for i in range(100)]
    return [
        [(i * 13 + i // 7) % 32 for i in range(65535)],
        small,
        list(small),  # identical data: stored once, both entries point at it
        [31],
    ]


def features_song():
    shared_volume = Env([0, 31, 15], loop=1, release=2)
    I = {
        1: Instrument(wave=63, volume=shared_volume, arpeggio=Env([-64, 63, 0], loop=2),
                      pitch=Env([(i * 37) % 256 - 128 for i in range(255)], loop=254), relative=True,
                      waves=Env([0, 63, 31], loop=0)),
        # noise: the model's wave 5 and wave envelope are not lowered (byte 1 = 0, wave envelope length 0)
        2: Instrument(kind="noise", wave=5, volume=Env([31]), arpeggio=Env([31, -31]), pitch=Env([5]),
                      waves=Env([1, 2])),
        3: Instrument(kind="sample", volume=Env([31, 20]), sample=0, root=9, loop=True, loop_start=65534),
        # loop start kept in the model without a loop: lowered as 0
        4: Instrument(kind="sample", sample=2, root=95, loop=False, loop_start=50),
        # the same volume values as slot 1 with another loop and release: one envelope in the data
        5: Instrument(wave=0, volume=Env([0, 31, 15], loop=0, release=1)),
        6: Instrument(kind="sample", sample=3, root=48, loop=True, loop_start=0),
        64: Instrument(wave=1),
    }
    P = {}
    P["a0"] = {0: C(9, 1, [("4", 0x22)]), 8: C(95, None, []), 16: C(NOTE_OFF, None, [("C", 0x10)]), 40: C(48, 5, [])}
    P["a1"] = {0: C(60, 64, [("X", 0x5A), ("L", 0xFF), ("M", 0x83), ("T", 0x7F)]), 32: C(NOTE_OFF)}
    P["a2"] = {0: C(60, 1, [("C", 0x1F), ("8", 0x11), ("4", 0x37), ("P", 0x90)]), 1: C(None, None, [("W", 0x3F)]),
               2: C(None, None, [("8", 0x10), ("A", 0x0F)]), 63: C(NOTE_OFF, None, [("A", 0x00)])}
    P["a3"] = {5: C(None, 64, []), 6: C(72, None, [("0", 0x47)])}
    P["a4"] = {0: C(70, 2, [("S", 0x01)]), 4: C(57, 3), 12: C(60, 4, [("3", 0x10)]), 20: C(None, None, [("T", 0x00)])}
    P["a5"] = {0: C(66, 2), 8: C(50, 6), 16: C(NOTE_OFF)}
    P["b0"] = {0: C(50, 5, [("G", 0x01)]), 6: C(52, 1, []), 15: C(None, None, [("B", 0x02), ("D", 0x00)])}
    P["b5"] = {3: C(64, 2, [("G", 0x03), ("S", 0x02)]), 10: C(64, None, [("3", 0x10)]),
               12: C(67, None, [("1", 0x04), ("2", 0x00)])}
    P["c4"] = {0: C(36, 3, [("F", 0x04), ("B", 0x01)])}
    orders = [
        Order(64, ["a0", "a1", "a2", "a3", "a4", "a5"]),
        Order(20, ["b0", None, None, None, None, "b5"]),
        Order(1, [None, None, None, None, "c4", None]),
    ]
    return Song(speed=5, orders=orders, patterns=P, instruments=I, waves=features_waves(), samples=features_samples(),
                loop=1, multispeed=2, master=0x5A, lfo_freq=0xFF, lfo_ctrl=0x83, timer=0x7F)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, song in (("spike", spike_song()), ("features", features_song())):
        path = os.path.join(OUT, name + ".bin.gz")
        with gzip.GzipFile(path, "wb", mtime=0) as f:
            f.write(build(song))
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
