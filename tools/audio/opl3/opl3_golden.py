#!/usr/bin/env python3
"""Golden images for the Kotlin OPL3 lowering (core Opl3GoldenTest): the reference builder
(opl3_song.build) applied to songs the Kotlin test rebuilds from Thor Tracker's model, written gzip-compressed to
core/src/jvmTest/resources/opl3-golden/.

- spike.bin.gz: the spike's test song (opl3_song.test_song) with one cell left out: order 0's E-piano row 4 holds an
  explicit "000" (arpeggio off), which Thor Tracker's cells cannot store (000 is the empty effect), so the Kotlin
  song has no effect there.
- features.bin.gz: a song for what the spike's does not reach: notes C-0 and B-7, instrument slot 64 behind empty
  slots, a negative sweep, chords with a zero digit (H40, H07) and every shape length incl. inversions and +-48,
  four effect columns on one row, a blank first column before a used one, an instrument without note, an order of
  1 row and one of 20 rows, multispeed 2, deep tremolo and vibrato, Bxx/Dxx (the Kotlin song parks an order, so
  its jumps and loop are renumbered by the arrangement before lowering).

    python3 tools/audio/opl3/opl3_golden.py      # rewrites the two fixtures
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from opl3_song import Cell as C, Instrument, NOTE_OFF, Op, Order, Song, build, test_song  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "jvmTest", "resources", "opl3-golden")


def spike_song():
    s = test_song()
    s.patterns["epiano0"] = dict(s.patterns["epiano0"])
    del s.patterns["epiano0"][4]
    return s


def features_song():
    I = {
        1: Instrument([Op(mult=4, tl=14, ar=12, dr=1, sl=2, rr=5), Op(mult=3, tl=30, ar=14, dr=2, sl=3, rr=5, ksl=3, am=True),
                       Op(mult=0, tl=28, ar=14, dr=2, sl=3, rr=5, ksr=True), Op(mult=1, tl=6, ar=12, dr=1, sl=2, rr=5, vib=True)],
                      alg=2, fb=1),
        2: Instrument([Op(mult=2, tl=20, ar=15, dr=5, sl=6, rr=6, wave=7), Op(mult=1, tl=3, ar=15, dr=4, sl=5, rr=6, sustain=False)],
                      alg=0, fb=4, sweep=(-12, 5, 3)),
        3: Instrument([Op(mult=1, tl=10, ar=10, rr=3, wave=3), Op(mult=2, tl=12, ar=9, rr=3, wave=5)], alg=1, fb=7),
        4: Instrument([Op(mult=1, tl=63), Op(mult=15, tl=0, wave=6), Op(mult=11, tl=1, ksl=2), Op(mult=13, tl=2, wave=4)],
                      alg=3, fb=0, sweep=(96, -96, 255)),
        64: Instrument([Op(mult=1, tl=40), Op(mult=1, tl=0)], alg=0, fb=0),
    }
    shapes = [[4, 7], [3, 7, 10, 14], [-12, -5, 4, 7], [48], [-48], [7, 12, 19]]
    P = {}
    P["a0"] = {0: C(0, 1, [("4", 0x22)]), 8: C(95, None, []), 16: C(NOTE_OFF, None, [("C", 0x10)]), 40: C(48, 1, [])}
    P["a1"] = {0: C(48, 4, [("W", 0x07), ("M", 0x4F), ("E", 0x03), ("I", 0x3F)]), 32: C(NOTE_OFF)}
    P["a2"] = {0: C(60, 2, [("C", 0x20), ("8", 0x11), ("4", 0x37), ("P", 0x90)]), 1: C(None, None, [("I", 0x10)]),
               2: C(None, None, [("8", 0x10), ("A", 0x0F)]), 63: C(NOTE_OFF, None, [("A", 0x00)])}
    P["a3"] = {5: C(None, 3, []), 6: C(72, None, [("0", 0x47)])}
    P["a6"] = {0: C(55, 2, [("H", 0x40)]), 4: C(57, None, [("H", 0x07)]), 12: C(59, None, [("K", 0x03)])}
    P["a8"] = {r * 8: C(60 + r, 64, [("K", r + 1)]) for r in range(6)}
    P["b0"] = {0: C(50, 2, [("K", 0x02)]), 6: C(52, 1, []), 15: C(None, None, [("B", 0x02), ("D", 0x00)])}
    P["b5"] = {3: C(64, 3, [("G", 0x03), ("S", 0x02)]), 10: C(64, None, [("3", 0x10)]), 12: C(67, None, [("1", 0x04), ("2", 0x00)])}
    P["c4"] = {0: C(36, 2, [("F", 0x04), ("B", 0x01)])}
    orders = [
        Order(64, ["a0", "a1", "a2", "a3", None, None, "a6", None, "a8"]),
        Order(20, ["b0", None, None, None, None, "b5", None, None, None]),
        Order(1, [None, None, None, None, "c4", None, None, None, None]),
    ]
    return Song(speed=5, orders=orders, patterns=P, instruments=I, shapes=shapes, loop=1, multispeed=2, depth=0xC0)


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
