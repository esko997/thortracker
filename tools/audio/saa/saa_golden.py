#!/usr/bin/env python3
"""Golden images for the Kotlin SAA1099 lowering (core SaaGoldenTest): the reference builder (saa_song.build) applied
to songs the Kotlin test rebuilds from Thor Tracker's model (SaaGoldenSongs.kt), written gzip-compressed to
core/src/jvmTest/resources/saa-golden/.

- sam.bin.gz: the host test's song (saa_song.test_song, SAM Coupé). It has no explicit "000", so Thor Tracker's cells
  store every cell of it.
- cms.bin.gz: the host test's CMS song (saa_song.cms_song: twelve lanes).
- features.bin.gz (SAM Coupé): what the test songs do not reach: notes B-0 and A#8 (the system's range), slot 64
  behind empty slots, envelopes of 256 values with loops (at 0, 128, 255) and a release (loop 16, release 200), a
  release at step 1 after a loop at step 0, arpeggio values -64 and 63, a looping mixer envelope over all four values,
  pans 0 and 15, every hardware envelope bit (3-bit, inverted right, external at rate 255, shape 7, retrigger off),
  noise clock 3, four effect columns on one row, blank columns before used ones (left out of the image), an
  instrument without note, orders of 64, 20 and 1 rows, multispeed 3, E/M/N/Y/8 with boundary parameters, Bxx/Dxx (the
  Kotlin song parks an order, so its jumps and loop are renumbered by the arrangement before lowering; the song here is
  that arrangement).

    python3 tools/audio/saa/saa_golden.py      # rewrites the three fixtures
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from saa_song import (Cell as C, Env, Instrument, NOTE_OFF, Order, SAM, Song, build, cms_song,  # noqa: E402
                      test_song)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "jvmTest", "resources", "saa-golden")


def features_song():
    I = {
        1: Instrument(volume=Env([(i * 7 + 3) % 16 for i in range(256)], loop=16, release=200),
                      arpeggio=Env([(i * 5) % 128 - 64 for i in range(256)], loop=0),
                      pitch=Env([(i * 3) % 128 - 64 for i in range(256)], loop=128), relative=True,
                      mixer=Env([i % 4 for i in range(256)], loop=255), noise=3, pan_left=0, pan_right=15),
        2: Instrument(pitch=Env([4, -4], loop=0), pan_left=15, pan_right=0),
        3: Instrument(volume=Env([15, 0], release=1), mixer=Env([1, 3, 2], loop=1), noise=2,
                      envelope=0xBF, rate=255),
        64: Instrument(volume=Env([15, 10], loop=0, release=1), arpeggio=Env([63, -64]), envelope=0x8E,
                       retrigger=True, noise=1),
    }
    P = {}
    P["a0"] = {0: C(11, 1, [("4", 0x22)]), 8: C(106), 16: C(NOTE_OFF, None, [("C", 0x0A)]), 40: C(48, 1),
               63: C(NOTE_OFF, None, [("A", 0x00)])}
    P["a1"] = {0: C(60, 2, [("C", 0x08), ("P", 0x90), ("4", 0x37), ("A", 0x01)]),
               1: C(None, None, [("8", 0x0F)]),              # Kotlin: column 2 only
               2: C(None, None, [("1", 0x10), ("M", 0x03)]),  # Kotlin: columns 1 and 3
               32: C(NOTE_OFF)}
    P["a2"] = {5: C(None, 64), 6: C(72, None, [("0", 0x47), ("E", 0xBF)]), 20: C(74, 3, [("E", 0x00)]),
               24: C(None, None, [("E", 0x80), ("N", 0x03)])}
    P["a3"] = {0: C(36, 3, [("Y", 0x03)]), 12: C(38, 64, [("Y", 0x00), ("M", 0x00)])}
    P["a4"] = {0: C(84, 64, [("S", 0x02), ("8", 0xF0)]), 4: C(86, 1, [("G", 0x02), ("N", 0x00)])}
    P["a5"] = {0: C(55, 3, [("Y", 0x01)]), 9: C(None, None, [("Y", 0x02)]), 10: C(None, None, [("Y", 0x00)])}
    # Document orders: 0, parked 1, 2, 3. B03 = document order 3, which plays as order 2; B02 = document order 2 = 1.
    P["b0"] = {0: C(50, 2, [("G", 0x01)]), 6: C(52, 1), 15: C(None, None, [("B", 0x02), ("D", 0x00)])}
    P["b3"] = {3: C(64, 3, [("G", 0x03), ("S", 0x02)]), 10: C(64, None, [("3", 0x10)]),
               12: C(67, None, [("1", 0x04), ("2", 0x00)])}
    P["c4"] = {0: C(36, 2, [("F", 0x04), ("B", 0x01)])}
    orders = [
        Order(64, ["a0", "a1", "a2", "a3", "a4", "a5"]),
        Order(20, ["b0", None, None, "b3", None, None]),
        Order(1, [None, None, None, None, "c4", None]),
    ]
    return Song(speed=5, orders=orders, patterns=P, instruments=I, loop=1, multispeed=3, variant=SAM)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, song in (("sam", test_song()), ("cms", cms_song()), ("features", features_song())):
        path = os.path.join(OUT, name + ".bin.gz")
        with gzip.GzipFile(path, "wb", mtime=0) as f:
            f.write(build(song))
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
