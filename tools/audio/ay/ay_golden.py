#!/usr/bin/env python3
"""Golden images for the Kotlin AY-3-8910 / YM2149 lowering (core AyGoldenTest): the reference builder
(ay_song.build) applied to songs the Kotlin test rebuilds from Thor Tracker's model (AyGoldenSongs), written
gzip-compressed to core/src/jvmTest/resources/ay-golden/.

- test-zx128.bin.gz, test-msx.bin.gz, test-atari-st.bin.gz, test-cpc.bin.gz: the native host test's song
  (ay_song.test_song) for each variant (the CPC's with its lowest note C-0; it has no explicit "000", the one cell
  Thor Tracker cannot store).
- features.bin.gz (MSX): what the test song does not reach: slot 64 behind empty slots, a 256-value volume envelope
  (loop 255) and a 256-value noise envelope, envelopes shared between sounds (stored once), every instrument field at
  its extremes (shape 15, auto octave -8 and 7, manual period 65535, sweep 96 / -96 / 255 and a negative start,
  arpeggio and pitch values -64 and 63, mixer 0..3, noise 0..31, relative pitch, sync), settings of the inactive
  mode that the lowering leaves out (a manual sound's octave and sync, an auto sound's period), shape-0 sounds with
  auto and manual settings, four effect columns on one row and blank columns before used ones, every note setting at
  its extremes, an instrument without a note, orders of 64, 20 and 1 rows, Bxx/Dxx (the Kotlin song parks an order,
  so its jumps and loop are renumbered by the arrangement before lowering).

    python3 tools/audio/ay/ay_golden.py      # rewrites the five fixtures
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ay_song import KEYS, LOWEST_NOTE, NOTE_OFF, Cell as C, Env, Instrument, Order, Song, build, test_song  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "jvmTest", "resources", "ay-golden")
MSX = 1


def features_song():
    shared_volume = Env([15, 12, 9, 6, 3, 0], loop=1, release=4)
    I = {
        1: Instrument(volume=shared_volume, arpeggio=Env([0, -12, 12, -64, 63], loop=0),
                      pitch=Env([1, -1, 0, -64, 63]), relative=True, mixer=Env([0, 1, 2, 3], loop=3),
                      noise=Env([0, 31, 15], loop=1), shape=15, auto=True, octave=-8, sync=True,
                      sweep=(96, -96, 255)),
        2: Instrument(shape=8, auto=True, octave=7),
        3: Instrument(volume=Env([15, 12, 9, 6, 3, 0], loop=1, release=4), shape=9, auto=False, period=65535,
                      sweep=(-12, 5, 3)),
        4: Instrument(),
        5: Instrument(shape=0, auto=False, octave=5, period=0x1234, sync=True),  # octave and sync not written
        6: Instrument(shape=0, auto=True, octave=3, period=777, sync=True),      # period not written
        63: Instrument(volume=Env([i % 16 for i in range(256)], loop=255), noise=Env([i % 32 for i in range(256)])),
        64: Instrument(mixer=Env([2]), noise=Env([31]), shape=12, auto=False, period=0),
    }
    lo = LOWEST_NOTE[MSX]
    P = {}
    P["a0"] = {0: C(lo, 1, [("4", 0x22)]), 8: C(95), 16: C(NOTE_OFF, None, [("C", 0x00)]), 40: C(48, 64),
               63: C(None, None, [("W", 0x1F)])}
    P["b0"] = {0: C(48, 2, [("E", 0x0F), ("J", 0xF8), ("V", 0x03), ("W", 0x1F)]),
               1: C(None, None, [None, None, ("H", 0xFF)]),  # blank columns before a used one are left out
               2: C(None, None, [None, ("I", 0xFF), None, ("A", 0xF0)]),
               3: C(None, 5),
               32: C(NOTE_OFF),
               63: C(NOTE_OFF, None, [("S", 0x00)])}
    P["c0"] = {0: C(60, 63, [("C", 0x0F), ("0", 0x47), ("P", 0xFF), ("A", 0x0F)]), 4: C(57, None, [("3", 0xFF)]),
               12: C(95, 6, [("P", 0x00)]), 13: C(None, 4),
               20: C(30, 3, [("G", 0x01), ("S", 0x03), ("4", 0xFF), ("E", 0x00)]),
               30: C(40, 64, [("1", 0xFF), ("2", 0x00), ("J", 0x07), ("V", 0x00)])}
    # B02 = the arranged order 2 (the Kotlin song's document order 3, behind a parked order).
    P["b2"] = {0: C(50, 2), 6: C(52, 1, [("W", 0x00), ("E", 0x08)]), 15: C(None, None, [("B", 0x02), ("D", 0x00)])}
    P["c2"] = {3: C(64, 3, [("G", 0x03), ("S", 0x02)]), 10: C(64, None, [("3", 0x10)]),
               12: C(67, None, [("1", 0x04), ("2", 0x00)]), 18: C(None, None, [("D", 0x05)])}
    P["a3"] = {0: C(36, 2, [("F", 0xFF), ("B", 0x01)])}
    orders = [
        Order(64, ["a0", "b0", "c0"]),
        Order(20, [None, "b2", "c2"]),
        Order(1, ["a3", None, None]),
    ]
    return Song(variant=MSX, speed=5, orders=orders, patterns=P, instruments=I, loop=1)


def main():
    os.makedirs(OUT, exist_ok=True)
    songs = [("test-" + KEYS[v], test_song(v)) for v in range(len(KEYS))] + [("features", features_song())]
    for name, song in songs:
        path = os.path.join(OUT, name + ".bin.gz")
        with gzip.GzipFile(path, "wb", mtime=0) as f:
            f.write(build(song))
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
