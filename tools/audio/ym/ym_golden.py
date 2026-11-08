#!/usr/bin/env python3
"""Golden images for the Kotlin YM2612 lowering (core YmGoldenTest): the reference builder (ym_song.build) applied
to songs the Kotlin test rebuilds from Thor Tracker's model, written gzip-compressed to
core/src/jvmTest/resources/ym-golden/.

- spike.bin.gz: the native host test's song (ym_song.test_song), unchanged (it has no explicit "000", the one cell
  Thor Tracker cannot store).
- features.bin.gz: what the spike's song does not reach: PAL, the LFO at rate 7, notes C-0 and B-7, FM slot 64 and
  PSG slot 63 behind empty slots, every operator field at its extremes (DT 7, MUL 0/15, TL 127, KS 3, AR 0, D1R/D2R
  31, SSG-EG 8-15), AMS 3 / FMS 7, algorithm 7 and feedback 7, negative and extreme sweeps; PSG envelopes: a volume
  loop with a release point, negative arpeggio values (-64..63), a relative pitch envelope, a 256-value envelope,
  an instrument without envelopes and one sharing another's volume envelope (stored once); noise modes periodic and
  white at every rate incl. pitched, and Nxy locks; four effect columns on one row and blank columns before used ones,
  an instrument without a note, orders of 64, 20 and 1 rows, Bxx/Dxx (the Kotlin song parks an order, so its jumps
  and loop are renumbered by the arrangement before lowering).

    python3 tools/audio/ym/ym_golden.py      # rewrites the two fixtures
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ym_song import Cell as C, Env, FmInstrument, FmOp, NOTE_OFF, Order, PsgInstrument, Song, build, test_song  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "jvmTest", "resources", "ym-golden")


def features_song():
    shared_volume = Env([15, 12, 9, 6, 3, 0], loop=1, release=4)
    I = {
        1: FmInstrument([FmOp(dt=7, mul=15, tl=127, rs=3, ar=0, am=True, d1r=31, d2r=31, d1l=15, rr=0, ssg=15),
                         FmOp(dt=4, mul=0, tl=0, rs=0, ar=31, d1r=0, d2r=0, d1l=0, rr=15, ssg=8),
                         FmOp(dt=1, mul=7, tl=64, rs=2, ar=12, am=True, d1r=5, d2r=1, d1l=7, rr=7, ssg=10),
                         FmOp(dt=5, mul=3, tl=20, rs=1, ar=20, d1r=9, d2r=4, d1l=3, rr=9, ssg=13)],
                        alg=7, fb=7, ams=3, fms=7, sweep=(96, -96, 255)),
        2: FmInstrument([FmOp(mul=2, tl=30), FmOp(), FmOp(mul=3, tl=40, ssg=12), FmOp()], alg=4, fb=0, sweep=(-12, 5, 3)),
        3: PsgInstrument(volume=shared_volume, arpeggio=Env([0, -12, 12, -64, 63], loop=0), pitch=Env([1, -1, 0]),
                         relative=True, noise=7),
        4: PsgInstrument(noise=0),
        5: PsgInstrument(volume=Env([15, 12, 9, 6, 3, 0], loop=1, release=4), arpeggio=Env([0, 7], loop=1), noise=2),
        63: PsgInstrument(volume=Env([i % 16 for i in range(256)], loop=255), noise=6),
        64: FmInstrument([FmOp(mul=1, tl=40), FmOp(mul=2, tl=36), FmOp(mul=1, tl=50), FmOp(mul=1, tl=0)], alg=3, fb=3),
    }
    P = {}
    P["a0"] = {0: C(0, 1, [("4", 0x22)]), 8: C(95), 16: C(NOTE_OFF, None, [("C", 0x10)]), 40: C(48, 64),
               63: C(None, None, [("L", 0x0F)])}
    P["a1"] = {0: C(48, 2, [("M", 0x4F), ("E", 0x03), ("I", 0x7F), ("8", 0x10)]), 32: C(NOTE_OFF)}
    P["a5"] = {0: C(60, 64, [("C", 0x7F), ("8", 0x01), ("P", 0x90), ("A", 0x0F)]),
               1: C(None, None, [None, None, ("I", 0x10)]),  # blank columns before a used one are left out
               2: C(None, None, [None, ("8", 0x11), None, ("A", 0x00)]),
               63: C(NOTE_OFF, None, [("S", 0x00)])}
    P["a6"] = {0: C(45, 3, [("C", 0x0F), ("0", 0x47)]), 4: C(57, None, [("3", 0x10)]), 12: C(95, 5, [("P", 0x70)]),
               13: C(None, 4)}
    P["a8"] = {0: C(72, 63, [("C", 0x00), ("G", 0x01), ("S", 0x03), ("4", 0xFF)])}
    P["a9"] = {0: C(36, 4, [("N", 0x00)]), 4: C(36, 3, [("N", 0x13)]), 8: C(40, 5), 12: C(NOTE_OFF),
               16: C(24, 63, [("N", 0x12), ("C", 0x08), ("A", 0x01), ("L", 0x00)])}
    P["b0"] = {0: C(50, 2), 6: C(52, 1), 15: C(None, None, [("B", 0x02), ("D", 0x00)])}
    P["b7"] = {3: C(64, 3, [("G", 0x03), ("S", 0x02)]), 10: C(64, None, [("3", 0x10)]),
               12: C(67, None, [("1", 0x04), ("2", 0x00)])}
    P["c4"] = {0: C(36, 2, [("F", 0x04), ("B", 0x01)])}
    orders = [
        Order(64, ["a0", "a1", None, None, None, "a5", "a6", None, "a8", "a9"]),
        Order(20, ["b0", None, None, None, None, None, None, "b7", None, None]),
        Order(1, [None, None, None, None, "c4", None, None, None, None, None]),
    ]
    return Song(speed=5, orders=orders, patterns=P, instruments=I, region=1, lfo=0x0F, loop=1)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, song in (("spike", test_song()), ("features", features_song())):
        path = os.path.join(OUT, name + ".bin.gz")
        with gzip.GzipFile(path, "wb", mtime=0) as f:
            f.write(build(song))
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
