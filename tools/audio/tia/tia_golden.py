#!/usr/bin/env python3
"""Golden images for the Kotlin Atari 2600 lowering (core TiaGoldenTest): the reference builder (tia_song.build)
applied to songs the Kotlin test rebuilds from Thor Tracker's model (TiaGoldenSongs.kt), written gzip-compressed to
core/src/jvmTest/resources/tia-golden/.

- spike.bin.gz: the host test's song (tia_song.test_song, NTSC) as it is (it holds no explicit "000", the one cell
  Thor Tracker's cells cannot store).
- features.bin.gz: a PAL song for what the test song does not reach: instrument slot 64 behind empty slots, a
  255-step relative pitch envelope, arpeggio values -64 and 63, an absolute pitch envelope with -128 and 127, a
  waveform envelope through every AUDC 0..15, envelopes shared byte-for-byte between sounds (their loop and release
  differ), two byte-identical instruments in slots 2 and 3 (two records, one set of envelope bytes), a raw sound,
  both effect columns on one row, a row whose first column is blank and whose second is used (lowered as one effect),
  an instrument without a note, orders of 64, 20 and 1 rows, and Bxx/Dxx (the Kotlin song parks an order between
  orders 0 and 1, so its jumps and loop are renumbered by the arrangement).

    python3 tools/audio/tia/tia_golden.py      # rewrites the two fixtures
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tia_song import (Cell as C, Env, Instrument, NOTE_OFF, Order, REGION_PAL, Song, build,  # noqa: E402
                      test_song)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "jvmTest", "resources", "tia-golden")


def spike_song():
    return test_song()


def features_song():
    shared_volume = Env([0, 15, 7], loop=1, release=2)
    I = {
        1: Instrument(waveform=6, volume=shared_volume, arpeggio=Env([-64, 63, 0], loop=2),
                      pitch=Env([(i * 37) % 256 - 128 for i in range(255)], loop=254), relative=True,
                      waveforms=Env(list(range(16)), loop=0)),
        # two byte-identical sounds: two records, their envelope bytes stored once
        2: Instrument(waveform=4, volume=Env([15, 9, 3], loop=2), pitch=Env([-128, 127, 0])),
        3: Instrument(waveform=4, volume=Env([15, 9, 3], loop=2), pitch=Env([-128, 127, 0])),
        # the same volume values as slot 1 with another loop and release: one envelope in the data
        5: Instrument(waveform=12, volume=Env([0, 15, 7], loop=0, release=1)),
        # raw noise
        6: Instrument(waveform=8, raw=True, volume=Env([15, 8, 0]), arpeggio=Env([0, 5])),
        64: Instrument(waveform=1),
    }
    P = {}
    P["a0"] = {0: C(0, 1, [("4", 0x22)]), 8: C(119, None, []), 16: C(NOTE_OFF, None, [("C", 0x07)]),
               40: C(48, 5, []), 41: C(None, None, [("W", 0x0F)]), 63: C(NOTE_OFF, None, [("A", 0x10)])}
    P["a1"] = {0: C(60, 64, [("C", 0x0F), ("P", 0x90)]), 1: C(None, 2, []), 2: C(62, 3, [("3", 0x10)]),
               5: C(None, None, [("S", 0x02)]), 32: C(70, 6, [("0", 0x47)])}
    P["b0"] = {0: C(50, 5, [("G", 0x01)]), 6: C(52, 1, []), 15: C(None, None, [("B", 0x02), ("D", 0x00)])}
    # in Kotlin: column 0 blank, column 1 = 1 04 (lowered as the one effect)
    P["b1"] = {3: C(64, 2, [("1", 0x04)]), 10: C(64, None, [("2", 0x08), ("F", 0x07)])}
    P["c0"] = {0: C(36, 3, [("F", 0x04), ("B", 0x01)])}
    orders = [
        Order(64, ["a0", "a1"]),
        Order(20, ["b0", "b1"]),
        Order(1, ["c0", None]),
    ]
    return Song(speed=5, orders=orders, patterns=P, instruments=I, loop=1, region=REGION_PAL)


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
