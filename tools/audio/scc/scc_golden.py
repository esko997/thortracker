#!/usr/bin/env python3
"""Golden images for the Kotlin SCC lowering (core SccGoldenTest): the reference builder (scc_song.build) applied to
songs the Kotlin test rebuilds from Thor Tracker's model (SccGoldenSongs.kt), written gzip-compressed to
core/src/jvmTest/resources/scc-golden/.

- spike.bin.gz: the host test's song (scc_song.test_song, on the SCC). It has no explicit "000", so Thor Tracker's
  cells store every cell of it.
- plus.bin.gz: the same song on the SCC+ (only the variant byte differs).
- features.bin.gz: what the test song does not reach: notes A-0 and B-7 (the system's range), slot 64 behind empty
  slots, envelopes of 256 values with loops (at 0, 128, 255) and a release (loop 16, release 200), a release at step 1
  after a loop at step 0, arpeggio values -64 and 63, wave 64 (an envelope and W40), four effect columns on one row,
  blank columns before used ones (left out of the image), an instrument without note, orders of 64, 20 and 1 rows,
  multispeed 3, T80 (the SCC's shared-RAM freeze), Bxx/Dxx (the Kotlin song parks an order, so its jumps and loop are
  renumbered by the arrangement before lowering; the song here is that arrangement).

    python3 tools/audio/scc/scc_golden.py      # rewrites the three fixtures
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scc_song import (Cell as C, Env, Instrument, NOTE_OFF, Order, SCC, Song, build, plus_song, pulse_wave,  # noqa: E402
                      saw_wave, sine_wave, square_wave, test_song, triangle_wave)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "jvmTest", "resources", "scc-golden")


def features_song():
    I = {
        1: Instrument(volume=Env([(i * 7 + 3) % 16 for i in range(256)], loop=16, release=200),
                      arpeggio=Env([(i * 5) % 128 - 64 for i in range(256)], loop=0),
                      pitch=Env([(i * 3) % 128 - 64 for i in range(256)], loop=128), relative=True,
                      wave=Env([1 + i % 4 for i in range(256)], loop=255), restart=True),
        2: Instrument(wave=Env([2]), pitch=Env([4, -4], loop=0)),
        3: Instrument(wave=Env([64, 3], loop=1), volume=Env([15, 0], release=1)),
        64: Instrument(wave=Env([5]), volume=Env([15, 10], loop=0, release=1), arpeggio=Env([63, -64])),
    }
    waves = {1: sine_wave(), 2: square_wave(), 3: saw_wave(), 4: triangle_wave(), 5: pulse_wave(),
             64: [127 if i % 2 else -128 for i in range(32)]}
    P = {}
    P["a0"] = {0: C(9, 1, [("4", 0x22)]), 8: C(95), 16: C(NOTE_OFF, None, [("C", 0x0A)]), 40: C(48, 1),
               63: C(NOTE_OFF, None, [("A", 0x00)])}
    P["a1"] = {0: C(60, 2, [("C", 0x08), ("P", 0x90), ("4", 0x37), ("A", 0x01)]),
               1: C(None, None, [("W", 0x40)]),              # Kotlin: column 2 only
               2: C(None, None, [("1", 0x10), ("A", 0x0F)]),  # Kotlin: columns 1 and 3
               32: C(NOTE_OFF)}
    P["a2"] = {5: C(None, 64), 6: C(72, None, [("0", 0x47)]), 20: C(74, 3, [("T", 0x80)]),
               24: C(None, None, [("T", 0x00)])}
    P["a3"] = {0: C(36, 3), 12: C(38, 64, [("W", 0x01)])}
    P["a4"] = {0: C(84, 64, [("S", 0x02)]), 4: C(86, 1, [("G", 0x02)])}
    # Document orders: 0, parked 1, 2, 3. B03 = document order 3, which plays as order 2; B02 = document order 2 = 1.
    P["b0"] = {0: C(50, 2, [("G", 0x01)]), 6: C(52, 1), 15: C(None, None, [("B", 0x02), ("D", 0x00)])}
    P["b3"] = {3: C(64, 3, [("G", 0x03), ("S", 0x02)]), 10: C(64, None, [("3", 0x10)]),
               12: C(67, None, [("1", 0x04), ("2", 0x00)])}
    P["c4"] = {0: C(36, 2, [("F", 0x04), ("B", 0x01)])}
    orders = [
        Order(64, ["a0", "a1", "a2", "a3", "a4"]),
        Order(20, ["b0", None, None, "b3", None]),
        Order(1, [None, None, None, None, "c4"]),
    ]
    return Song(speed=5, orders=orders, patterns=P, instruments=I, waves=waves, loop=1, multispeed=3, variant=SCC)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, song in (("spike", test_song()), ("plus", plus_song()), ("features", features_song())):
        path = os.path.join(OUT, name + ".bin.gz")
        with gzip.GzipFile(path, "wb", mtime=0) as f:
            f.write(build(song))
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
