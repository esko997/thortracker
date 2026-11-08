#!/usr/bin/env python3
"""OPL3 image builder (the layout of docs/AUDIO_ENGINE.md "OPL3: image layout", as the Kotlin lowering must
produce it) and the hand-built test song of the native host test.

A song here: Song(speed, multispeed, depth, loop, instruments={slot: Instrument}, shapes=[[intervals]],
orders=[Order(rows, [pattern name per channel])], patterns={name: {row: Cell}}). build(song) returns the
65536-byte image; build(song, relayout=True) lays the same song out differently (patterns in reverse order
behind a gap, so every offset changes) for the live-edit test."""
import struct
from dataclasses import dataclass, field

IMAGE_SIZE = 65536
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_SHAPES, OFF_DATA = 64, 320, 4928, 6976, 7232
NOTE_OFF = 0xFE
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}


@dataclass
class Op:
    mult: int = 1
    tl: int = 0
    ar: int = 15
    dr: int = 0
    sl: int = 0
    rr: int = 4
    wave: int = 0
    ksl: int = 0
    am: bool = False
    vib: bool = False
    sustain: bool = True
    ksr: bool = False

    def regs(self):
        return [(self.am << 7) | (self.vib << 6) | (self.sustain << 5) | (self.ksr << 4) | self.mult,
                (self.ksl << 6) | self.tl, (self.ar << 4) | self.dr, (self.sl << 4) | self.rr, self.wave]


@dataclass
class Instrument:
    ops: list
    alg: int = 0
    fb: int = 0
    sweep: tuple = (0, 0, 0)  # start, end (semitones), speed (1/16 semitone per tick)

    def record(self):
        four = len(self.ops) == 4
        rec = bytearray(32)
        rec[0] = 0x80 | (1 if four else 0)
        rec[1], rec[2] = self.alg, self.fb
        for k, op in enumerate(self.ops):
            rec[4 + 5 * k: 9 + 5 * k] = bytes(op.regs())
        struct.pack_into("bbB", rec, 24, *self.sweep)
        return bytes(rec)


@dataclass
class Cell:
    note: int = None          # 0..95, NOTE_OFF
    inst: int = None
    fx: list = field(default_factory=list)  # [(letter, param)]


@dataclass
class Order:
    rows: int
    patterns: list            # 9 pattern names (None = empty)


@dataclass
class Song:
    speed: int
    orders: list
    patterns: dict
    instruments: dict
    shapes: list = field(default_factory=list)
    loop: int = 0
    multispeed: int = 1
    depth: int = 0


def encode_pattern(cells, rows):
    out, empty = bytearray(), 0

    def flush():
        nonlocal empty
        while empty:
            k = min(empty, 128)
            out.append(0x80 | (k - 1))
            empty -= k

    for r in range(rows):
        c = cells.get(r)
        if c is None or (c.note is None and c.inst is None and not c.fx):
            empty += 1
            continue
        flush()
        assert len(c.fx) <= 4
        m = (1 if c.note is not None else 0) | (2 if c.inst is not None else 0) | (len(c.fx) << 2)
        out.append(m)
        if c.note is not None:
            out.append(c.note)
        if c.inst is not None:
            out.append(c.inst)
        for letter, param in c.fx:
            out.extend((FX[letter], param))
    flush()
    return bytes(out)


def build(song, relayout=False):
    img = bytearray(IMAGE_SIZE)
    img[0:4] = b"TTO3"
    img[4], img[5], img[6], img[7] = 1, song.multispeed, song.speed, song.depth
    struct.pack_into("<HH", img, 8, len(song.orders), song.loop)
    img[12] = max(song.instruments) if song.instruments else 0
    img[13] = len(song.shapes)
    for slot, inst in song.instruments.items():
        img[OFF_INSTRUMENTS + 32 * (slot - 1): OFF_INSTRUMENTS + 32 * slot] = inst.record()
    for k, shape in enumerate(song.shapes):
        struct.pack_into("4b", img, OFF_SHAPES + 4 * k, *(list(shape) + [0] * (4 - len(shape))))
    # distinct encoded patterns, deduplicated by bytes
    blobs, uses = [], []
    for o, order in enumerate(song.orders):
        img[OFF_ROWS + o] = order.rows & 0xFF
        row = []
        for c in range(9):
            name = order.patterns[c]
            blob = encode_pattern(song.patterns[name] if name else {}, order.rows)
            if blob not in blobs:
                blobs.append(blob)
            row.append(blob)
        uses.append(row)
    order_of_blobs = list(reversed(blobs)) if relayout else blobs
    data, where = bytearray(), {}
    if relayout:
        data.extend(b"\x80" * 37)  # a gap of unused bytes moves every pattern
    for blob in order_of_blobs:
        where[blob] = len(data)
        data.extend(blob)
    assert len(data) <= IMAGE_SIZE - OFF_DATA
    struct.pack_into("<H", img, 14, len(data))
    img[OFF_DATA: OFF_DATA + len(data)] = data
    for o, row in enumerate(uses):
        for c, blob in enumerate(row):
            struct.pack_into("<H", img, OFF_ORDERS + 2 * (9 * o + c), where[blob])
    return bytes(img)


# ---- the test song ---------------------------------------------------------------------------------

def test_song():
    """9 channels, 4 orders (16/32/16/24 rows), loop to order 1 (also by B01 on the last row).

    Exercises 2-op FM/additive and all four 4-op algorithms, chords (Hxy and shapes), pool exhaustion and
    stealing, 4-op/2-op pair switches taking a secondary back from the pool, drum pitch sweeps, every effect,
    multiple effect columns, OFF cells, empty runs and a pattern used twice."""
    I = {}
    # 1: 4-op brass, algorithm 0 (1->2->3->4)
    I[1] = Instrument([Op(mult=1, tl=30, ar=13, dr=3, sl=4, rr=5), Op(mult=2, tl=34, ar=12, dr=2, sl=3, rr=5),
                       Op(mult=1, tl=28, ar=12, dr=2, sl=3, rr=5), Op(mult=1, tl=4, ar=11, dr=2, sl=2, rr=6)],
                      alg=0, fb=5)
    # 2: 2-op FM bass
    I[2] = Instrument([Op(mult=1, tl=22, ar=15, dr=4, sl=5, rr=6, wave=1), Op(mult=1, tl=2, ar=15, dr=3, sl=3, rr=7)],
                      alg=0, fb=6)
    # 3: 2-op additive pad (organ)
    I[3] = Instrument([Op(mult=2, tl=12, ar=9, dr=2, sl=2, rr=4, wave=2), Op(mult=1, tl=6, ar=8, dr=1, sl=2, rr=4)],
                      alg=1, fb=2)
    # 4: 4-op e-piano, algorithm 1 (1->2) + (3->4)
    I[4] = Instrument([Op(mult=14, tl=36, ar=15, dr=6, sl=8, rr=6, ksl=1), Op(mult=1, tl=4, ar=15, dr=3, sl=5, rr=6),
                       Op(mult=1, tl=24, ar=15, dr=4, sl=6, rr=6), Op(mult=1, tl=8, ar=15, dr=2, sl=4, rr=6)],
                      alg=1, fb=3)
    # 5: 2-op lead
    I[5] = Instrument([Op(mult=1, tl=26, ar=14, dr=2, sl=3, rr=5, vib=True), Op(mult=1, tl=3, ar=13, dr=2, sl=2, rr=5)],
                      alg=0, fb=4)
    # 6: kick: sine with a fast downward sweep from +24
    I[6] = Instrument([Op(mult=1, tl=40, ar=15, dr=8, sl=15, rr=8), Op(mult=1, tl=0, ar=15, dr=4, sl=6, rr=7, sustain=False)],
                      alg=0, fb=0, sweep=(24, 0, 40))
    # 7: snare: noisy feedback, upward-then-down sweep start +7 to -5
    I[7] = Instrument([Op(mult=15, tl=8, ar=15, dr=7, sl=7, rr=8, wave=6), Op(mult=1, tl=4, ar=15, dr=6, sl=7, rr=8, sustain=False)],
                      alg=0, fb=7, sweep=(7, -5, 12))
    # 8: hat: short, high ratio
    I[8] = Instrument([Op(mult=12, tl=10, ar=15, dr=10, sl=12, rr=12, wave=7), Op(mult=9, tl=6, ar=15, dr=10, sl=12, rr=12, sustain=False)],
                      alg=0, fb=6)
    # 9: pure sine (modulator silent) for the A-4 measurement
    I[9] = Instrument([Op(mult=1, tl=63, ar=15, dr=0, sl=0, rr=15), Op(mult=1, tl=0, ar=15, dr=0, sl=0, rr=6)], alg=0, fb=0)
    # 10: 4-op organ, algorithm 2 (1 + (2->3->4))
    I[10] = Instrument([Op(mult=4, tl=14, ar=12, dr=1, sl=2, rr=5, wave=0), Op(mult=3, tl=30, ar=14, dr=2, sl=3, rr=5),
                        Op(mult=1, tl=28, ar=14, dr=2, sl=3, rr=5), Op(mult=1, tl=6, ar=12, dr=1, sl=2, rr=5)],
                       alg=2, fb=1)
    # 11: 4-op bell, algorithm 3 (1 + (2->3) + 4)
    I[11] = Instrument([Op(mult=3, tl=16, ar=15, dr=5, sl=9, rr=5, wave=4), Op(mult=7, tl=26, ar=15, dr=5, sl=7, rr=5),
                        Op(mult=1, tl=10, ar=15, dr=4, sl=8, rr=5), Op(mult=2, tl=12, ar=15, dr=6, sl=10, rr=5, wave=5)],
                       alg=3, fb=0)
    # 12: 2-op chord stab (short)
    I[12] = Instrument([Op(mult=2, tl=24, ar=15, dr=5, sl=6, rr=6), Op(mult=1, tl=6, ar=15, dr=4, sl=5, rr=6)], alg=0, fb=3)

    shapes = [[4, 7, 12], [3, 7, 10, 14], [-5, 4, 7], [7]]  # major+octave, minor 9th, inversion, fifth
    P = {}
    C = Cell
    # Order 0 (16 rows)
    P["brass0"] = {0: C(48, 1, [("4", 0x00), ("8", 0x11)]), 4: C(55, None, [("4", 0x34)]), 8: C(52, None, [("P", 0x88)]),
                   12: C(NOTE_OFF), 14: C(50, 1, [("8", 0x10), ("C", 0x30)])}
    P["bass0"] = {0: C(36, 2, [("1", 0x08)]), 3: C(None, None, [("1", 0x00)]), 4: C(36, None, [("2", 0x10)]),
                  6: C(None, None, [("2", 0x00)]), 8: C(43, None, [("P", 0x70)]), 12: C(41, None, []), 15: C(NOTE_OFF)}
    P["pad0"] = {0: C(60, 3, [("H", 0x47)]), 8: C(57, None, [("K", 0x01), ("8", 0x01)]), 15: C(NOTE_OFF)}
    P["epiano0"] = {0: C(64, 4, [("0", 0x37)]), 4: C(None, None, [("0", 0x00)]), 6: C(67, None, [("0", 0x47)]),
                    10: C(65, None, []), 13: C(NOTE_OFF)}
    P["lead0"] = {0: C(72, 5, [("3", 0x00), ("I", 0x30)]), 2: C(74, None, [("M", 0x13), ("W", 0x21)]),
                  4: C(None, None, [("3", 0x18)]), 5: C(79, None, []), 8: C(76, None, [("E", 0x01), ("W", 0x03)]),
                  10: C(None, None, [("3", 0x00), ("4", 0x26)]), 12: C(77, None, [("I", 0x3F), ("M", 0x24), ("E", 0x07), ("W", 0x12)])}
    P["chords0"] = {0: C(48, 12, [("K", 0x02), ("C", 0x2A)]), 4: C(53, None, [("H", 0x38), ("A", 0x04)]),
                    8: C(55, None, [("K", 0x03), ("A", 0x30)]), 12: C(NOTE_OFF, None, [("A", 0x00)])}
    P["kick"] = {0: C(36, 6), 4: C(36, 6), 8: C(36, 6), 12: C(36, 6), 14: C(38, 6, [("G", 0x02)])}
    P["snare"] = {4: C(50, 7), 12: C(50, 7, [("S", 0x03)]), 15: C(52, 7, [("G", 0x01), ("S", 0x04)])}
    P["hat"] = {r: C(84 if r % 4 else 86, 8, [("S", 0x02)] if r % 4 == 2 else []) for r in range(0, 16, 2)}
    # Order 1 (32 rows, the loop): every channel on 2-op chords drains the pool; then 4-op takes secondaries back.
    # No Fxx: the loop (B01 from order 3, at speed 5) starts again at the speed the first pass had (the player's rule).
    P["c1a"] = {0: C(60, 12, [("K", 0x02)]), 8: C(62, 1, []), 16: C(57, 12, [("K", 0x01)]), 24: C(59, 10, []),
                31: C(NOTE_OFF)}
    P["c1b"] = {2: C(55, 12, [("K", 0x01)]), 10: C(52, 12, [("H", 0x37)]), 18: C(50, 4, [("8", 0x01)]),
                26: C(48, 12, [("K", 0x04)])}
    P["c1c"] = {4: C(64, 3, [("K", 0x02), ("8", 0x10)]), 12: C(67, None, [("H", 0x59)]), 20: C(NOTE_OFF), 22: C(69, 3, [("K", 0x03)])}
    P["c1d"] = {6: C(43, 12, [("K", 0x01)]), 14: C(45, 11, [("C", 0x38)]), 22: C(47, 12, [("H", 0x47)]), 30: C(NOTE_OFF)}
    P["c1e"] = {1: C(72, 5, [("H", 0x04), ("4", 0x23)]), 9: C(74, None, [("H", 0x30)]), 17: C(76, 2, [("K", 0x01)]),
                25: C(79, 5, [("0", 0x47), ("H", 0x0C)])}
    P["c1f"] = {3: C(36, 2, [("H", 0x07)]), 11: C(38, 2, [("K", 0x02), ("1", 0x02)]), 19: C(40, 2, [("2", 0x01), ("H", 0x0C)]),
                27: C(NOTE_OFF, None, [("2", 0x00)])}
    P["kick1"] = {0: C(36, 6), 8: C(36, 6), 16: C(36, 6), 24: C(36, 6), 28: C(36, 6, [("G", 0x03)])}
    P["stab1"] = {5: C(60, 12, [("H", 0x47)]), 13: C(62, 12, [("H", 0x38), ("8", 0x11)]), 21: C(64, 12, [("K", 0x04)]),
                  29: C(65, 12, [("H", 0x37), ("S", 0x02)])}
    P["hat1"] = {r: C(86, 8, [("C", 0x3F - r)]) for r in range(0, 32, 4)}
    # Order 2 (16 rows): speed change, pattern break at row 12 -> order 3 row 2
    P["o2lead"] = {0: C(60, 5, [("F", 0x04), ("4", 0x48)]), 4: C(62, None, [("1", 0x20)]), 8: C(64, None, [("F", 0x05)]),
                   12: C(65, None, [("D", 0x02), ("1", 0x00)])}
    P["o2bell"] = {0: C(72, 11, [("8", 0x10)]), 6: C(76, 11, [("8", 0x01), ("P", 0x90)]), 11: C(NOTE_OFF)}
    P["o2organ"] = {0: C(48, 10, [("A", 0x01)]), 8: C(50, None, [("A", 0x10)])}
    # Order 3 (24 rows): volumes, OFF cells, 4-op algorithms 2 and 3, B01 jump on the last row
    P["o3a"] = {0: C(55, 10, [("C", 0x20)]), 2: C(57, None, [("C", 0x3F), ("G", 0x01)]), 6: C(NOTE_OFF), 8: C(59, 11, []),
                16: C(60, 1, [("4", 0x13)]), 23: C(NOTE_OFF, None, [("B", 0x01)])}
    P["o3b"] = {2: C(64, 2, [("H", 0x47)]), 9: C(NOTE_OFF), 12: C(62, 4, []), 20: C(60, 9, [("P", 0x40)])}
    P["o3c"] = {0: C(36, 6, [("8", 0x10)]), 6: C(36, 6, [("8", 0x01)]), 12: C(43, 6, []), 18: C(36, 6, [("8", 0x11), ("S", 0x01)])}

    orders = [
        Order(16, ["brass0", "bass0", "pad0", "epiano0", "lead0", "chords0", "kick", "snare", "hat"]),
        Order(32, ["c1a", "c1b", "c1c", "c1d", "c1e", "c1f", "kick1", "stab1", "hat1"]),
        Order(16, ["o2organ", "bass0", "o2bell", None, "o2lead", None, "kick", "snare", "hat"]),
        Order(24, ["o3a", "o3b", None, None, None, None, "o3c", None, None]),
    ]
    return Song(speed=6, orders=orders, patterns=P, instruments=I, shapes=shapes, loop=1)


def edited_song():
    """The test song with one cell edited in order 1 (row 16 of channel 1: a different note and chord)."""
    s = test_song()
    s.patterns["c1b"] = dict(s.patterns["c1b"])
    s.patterns["c1b"][16] = Cell(57, 12, [("K", 0x02)])
    return s


def tempo_song():
    """The test song with Fxx edited behind order 1's row 8 (F09 on row 4 of channel 8)."""
    s = test_song()
    s.patterns["hat1"] = dict(s.patterns["hat1"])
    s.patterns["hat1"][4] = Cell(86, 8, [("C", 0x3B), ("F", 0x09)])
    return s


def short_song():
    """Only the first two orders (a live edit that removes the playing order)."""
    s = test_song()
    s.orders = s.orders[:2]
    s.patterns["o3a"] = {}
    s.loop = 1
    return s
