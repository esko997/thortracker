#!/usr/bin/env python3
"""Golden images for the Kotlin OPM lowering (core OpmGoldenTest): the reference builder (opm_song.build) applied
to songs the Kotlin test rebuilds from Thor Tracker's model (OpmGoldenSongs.kt), written gzip-compressed to
core/src/jvmTest/resources/opm-golden/.

- spike.bin.gz: the host test's song (opm_song.test_song) with one cell left out: order 0's FM 1 row 4 holds an
  explicit "000" (arpeggio off), which Thor Tracker's cells cannot store (000 is the empty effect), so the Kotlin
  song has no effect there.
- features.bin.gz: a song for what the test song does not reach: notes D#0 (3) and D-8 (98), instrument slot 64
  behind empty slots, a noise instrument on FM 8 (NFRQ 31), a negative sweep and a full-range one, four effect
  columns on one row, a blank first column before a used one, an instrument without a note, an order of 1 row and
  one of 20 rows, multispeed 2, a non-zero header LFO (rate, triangle wave, AMD, PMD), CSM on at timer A 1023,
  Bxx/Dxx (the Kotlin song parks an order, so its jumps and loop are renumbered by the arrangement before lowering).

    python3 tools/audio/opm/opm_golden.py      # rewrites the two fixtures
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from opm_song import Cell as C, Instrument, NOTE_OFF, Op, Order, Song, build, test_song  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "jvmTest", "resources", "opm-golden")


def spike_song():
    s = test_song()
    s.patterns["lead0"] = dict(s.patterns["lead0"])
    del s.patterns["lead0"][4]
    return s


def features_song():
    I = {
        1: Instrument([Op(dt1=1, mul=4, tl=14, ks=1, ar=24, d1r=1, d1l=2, rr=5),
                       Op(dt1=6, mul=3, tl=30, ks=3, ar=28, am=True, d1r=2, dt2=3, d2r=31, d1l=3, rr=5),
                       Op(mul=0, tl=28, ar=26, d1r=2, dt2=1, d1l=3, rr=5),
                       Op(mul=15, tl=6, ar=31, am=True, d1r=31, d2r=4, d1l=15, rr=1)],
                      alg=4, fb=6, pms=7, ams=3),
        2: Instrument([Op(tl=127), Op(tl=127), Op(tl=127), Op(tl=0, ar=31, d1r=20, d1l=15, rr=15)],
                      alg=7, noise=True, nfrq=31),
        3: Instrument([Op(mul=2, tl=20, ar=31, d1r=5, d1l=6, rr=6), Op(mul=1, tl=24, ar=31, d1r=4, d1l=5, rr=6),
                       Op(mul=1, tl=127), Op(mul=1, tl=3, ar=31, d1r=4, d1l=5, rr=6)],
                      alg=0, fb=4, sweep=(-12, 5, 3)),
        4: Instrument([Op(mul=1, tl=127), Op(mul=15, tl=0, dt2=2), Op(mul=11, tl=1, ks=2), Op(mul=13, tl=2, dt1=4)],
                      alg=5, fb=0, sweep=(96, -96, 255)),
        64: Instrument([Op(tl=127), Op(tl=127), Op(tl=127), Op(tl=0)], alg=7),
    }
    P = {}
    P["a0"] = {0: C(3, 1, [("4", 0x22)]), 8: C(98, None, []), 16: C(NOTE_OFF, None, [("C", 0x10)]), 40: C(48, 1, [])}
    P["a1"] = {0: C(48, 4, [("E", 0x07), ("M", 0x4F), ("I", 0x3F), ("H", 0x73)]), 32: C(NOTE_OFF)}
    P["a2"] = {0: C(60, 3, [("C", 0x20), ("8", 0x11), ("4", 0x37), ("P", 0x90)]), 1: C(None, None, [("I", 0x10)]),
               2: C(None, None, [("8", 0x10), ("A", 0x0F)]), 63: C(NOTE_OFF, None, [("A", 0x00)])}
    P["a3"] = {5: C(None, 64, []), 6: C(72, None, [("0", 0x47)])}
    P["a6"] = {0: C(55, 1, [("L", 0xFF), ("W", 0x03)]), 4: C(57, None, [("T", 0x7F), ("V", 0x7F)]),
               12: C(59, None, [("Q", 0x80), ("X", 0x01)])}
    P["a7"] = {0: C(84, 2, [("N", 0x1F)]), 8: C(None, None, [("N", 0x00)]), 16: C(86, 2, [("S", 0x01)]), 24: C(60, 64)}
    P["b0"] = {0: C(50, 3, [("G", 0x01)]), 6: C(52, 1, []), 15: C(None, None, [("B", 0x02), ("D", 0x00)])}
    P["b5"] = {3: C(64, 4, [("G", 0x03), ("S", 0x02)]), 10: C(64, None, [("3", 0x10)]),
               12: C(67, None, [("1", 0x04), ("2", 0x00)])}
    P["c4"] = {0: C(36, 3, [("F", 0x04), ("B", 0x01)])}
    orders = [
        Order(64, ["a0", "a1", "a2", "a3", None, None, "a6", "a7"]),
        Order(20, ["b0", None, None, None, None, "b5", None, None]),
        Order(1, [None, None, None, None, "c4", None, None, None]),
    ]
    return Song(speed=5, orders=orders, patterns=P, instruments=I, loop=1, multispeed=2,
                lfo_rate=0xB7, lfo_wave=2, amd=0x33, pmd=0x45, csm=True, timer_a=1023)


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
