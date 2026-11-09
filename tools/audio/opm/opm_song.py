#!/usr/bin/env python3
"""OPM image builder (the layout of docs/AUDIO_ENGINE.md "OPM: image layout", as the Kotlin lowering must produce
it) and the hand-built test songs of the native host test.

A song here: Song(speed, orders=[Order(rows, [pattern name per channel])], patterns={name: {row: Cell}},
instruments={slot: Instrument}, loop, multispeed, and the chip's shared start state lfo_rate, lfo_wave, amd, pmd,
csm, timer_a). build(song) returns the 65536-byte image; build(song, relayout=True) lays the same song out
differently (patterns in reverse order behind a 37-byte gap, so every offset changes) for the live-edit test."""
import struct
from dataclasses import dataclass, field

IMAGE_SIZE = 65536
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 4416, 6464
INSTRUMENT_SIZE = 32
CHANNELS = 8
NOTE_OFF = 0xFE
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}


@dataclass
class Op:
    """One operator; defaults as Thor Tracker's OpmOperator."""
    dt1: int = 0
    mul: int = 1
    tl: int = 0
    ks: int = 0
    ar: int = 31
    am: bool = False
    d1r: int = 0
    dt2: int = 0
    d2r: int = 0
    d1l: int = 0
    rr: int = 15

    def regs(self):
        return [self.dt1 << 4 | self.mul, self.tl, self.ks << 6 | self.ar, self.am << 7 | self.d1r,
                self.dt2 << 6 | self.d2r, self.d1l << 4 | self.rr]


@dataclass
class Instrument:
    ops: list                 # four Op: operators 1..4 = M1, C1, M2, C2
    alg: int = 0
    fb: int = 0
    pms: int = 0
    ams: int = 0
    noise: bool = False       # operator 4 plays the noise generator (FM 8 only)
    nfrq: int = 0
    sweep: tuple = (0, 0, 0)  # start, end (semitones), speed (1/16 semitone per tick)

    def record(self):
        assert len(self.ops) == 4
        rec = bytearray(INSTRUMENT_SIZE)
        rec[0] = 0x80 | (1 if self.noise else 0)
        rec[1] = self.fb << 3 | self.alg
        rec[2] = self.pms << 4 | self.ams
        rec[3] = self.nfrq if self.noise else 0
        for k, op in enumerate(self.ops):
            rec[4 + 6 * k: 10 + 6 * k] = bytes(op.regs())
        struct.pack_into("bbB", rec, 28, *self.sweep)
        return bytes(rec)


@dataclass
class Cell:
    note: int = None          # 3..98, NOTE_OFF
    inst: int = None
    fx: list = field(default_factory=list)  # [(letter, param)], up to 4


@dataclass
class Order:
    rows: int
    patterns: list            # 8 pattern names (None = empty)


@dataclass
class Song:
    speed: int
    orders: list
    patterns: dict
    instruments: dict
    loop: int = 0
    multispeed: int = 1
    lfo_rate: int = 0
    lfo_wave: int = 0
    amd: int = 0
    pmd: int = 0
    csm: bool = False
    timer_a: int = 0


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
        out.append((1 if c.note is not None else 0) | (2 if c.inst is not None else 0) | len(c.fx) << 2)
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
    img[0:4] = b"TTOM"
    img[4], img[5], img[6], img[7] = 1, song.multispeed, song.speed, song.lfo_wave
    struct.pack_into("<HH", img, 8, len(song.orders), song.loop)
    img[12] = max(song.instruments) if song.instruments else 0
    img[13] = 1 if song.csm else 0
    img[16], img[17], img[18] = song.lfo_rate, song.amd, song.pmd
    struct.pack_into("<H", img, 20, song.timer_a)
    for slot, inst in song.instruments.items():
        img[OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1): OFF_INSTRUMENTS + INSTRUMENT_SIZE * slot] = inst.record()
    # byte-identical (order, channel) encodings stored once, in first-use order (orders, then channels)
    blobs, uses = [], []
    for o, order in enumerate(song.orders):
        img[OFF_ROWS + o] = order.rows & 0xFF
        row = []
        for c in range(CHANNELS):
            name = order.patterns[c]
            blob = encode_pattern(song.patterns[name] if name else {}, order.rows)
            if blob not in blobs:
                blobs.append(blob)
            row.append(blob)
        uses.append(row)
    data, where = bytearray(), {}
    if relayout:
        data.extend(b"\x80" * 37)  # a gap of unused bytes moves every pattern
    for blob in (list(reversed(blobs)) if relayout else blobs):
        where[blob] = len(data)
        data.extend(blob)
    assert len(data) <= IMAGE_SIZE - OFF_DATA
    struct.pack_into("<H", img, 14, len(data))
    img[OFF_DATA: OFF_DATA + len(data)] = data
    for o, row in enumerate(uses):
        for c, blob in enumerate(row):
            struct.pack_into("<H", img, OFF_ORDERS + 2 * (CHANNELS * o + c), where[blob])
    return bytes(img)


# ---- the test song ---------------------------------------------------------------------------------

NOISE_SLOT = 2


def test_instruments():
    I = {}
    # 1: pure sine (operator 4 alone) for pitch measurements
    I[1] = Instrument([Op(tl=127, rr=6), Op(tl=127, rr=6), Op(tl=127, rr=6), Op(tl=0, rr=6)], alg=7)
    # 2: noise hat: operator 4 is the noise generator (FM 8 only)
    I[2] = Instrument([Op(tl=127), Op(tl=127), Op(tl=127), Op(tl=4, ar=31, d1r=14, d2r=0, d1l=15, rr=15)],
                      alg=7, noise=True, nfrq=24)
    # 3: LFO pad: algorithm 4 (carriers 2 and 4 with amplitude modulation), PMS 5, AMS 2
    I[3] = Instrument([Op(mul=1, tl=34, ar=18, d1r=2, d1l=2, rr=5), Op(mul=1, tl=6, ar=16, d1r=1, d1l=1, rr=5, am=True),
                       Op(dt1=3, mul=2, tl=38, ar=18, d1r=2, d1l=2, rr=5), Op(dt1=7, mul=1, tl=8, ar=16, d1r=1, d1l=1, rr=5, am=True)],
                      alg=4, fb=3, pms=5, ams=2)
    # 4: kick: sweep from +24 semitones down to the note
    I[4] = Instrument([Op(mul=1, tl=30, ar=31, d1r=18, d1l=15, rr=12), Op(mul=1, tl=127), Op(mul=1, tl=127),
                       Op(mul=1, tl=0, ar=31, d1r=12, d2r=8, d1l=6, rr=10)], alg=0, fb=0, sweep=(24, 0, 40))
    # 5..12: algorithms 0..7, feedback 7..0, DT1/DT2/KS on several operators, MUL 0 and 15
    I[5] = Instrument([Op(dt1=1, mul=1, tl=28, ks=1, ar=28, d1r=6, d2r=2, d1l=4, rr=6), Op(mul=2, tl=34, ar=26, d1r=4, d1l=3, rr=6),
                       Op(dt1=5, mul=1, tl=30, ar=26, d1r=5, d1l=3, rr=6), Op(mul=1, tl=4, ar=24, d1r=3, d2r=1, d1l=2, rr=7)],
                      alg=0, fb=7)
    I[6] = Instrument([Op(mul=0, tl=24, ar=31, d1r=8, d1l=5, rr=8), Op(dt2=1, mul=1, tl=30, ar=31, d1r=6, d1l=4, rr=8),
                       Op(mul=1, tl=26, ks=2, ar=31, d1r=7, d1l=4, rr=8), Op(mul=1, tl=2, ar=31, d1r=4, d2r=3, d1l=3, rr=9)],
                      alg=1, fb=6)
    I[7] = Instrument([Op(mul=15, tl=40, ar=31, d1r=10, d1l=8, rr=7), Op(dt1=2, mul=3, tl=32, ar=30, d1r=6, d1l=5, rr=7),
                       Op(dt2=2, mul=1, tl=28, ar=30, d1r=5, d1l=4, rr=7), Op(mul=1, tl=6, ks=3, ar=28, d1r=4, d1l=3, rr=7)],
                      alg=2, fb=5)
    I[8] = Instrument([Op(mul=1, tl=30, ar=24, d1r=3, d1l=2, rr=6), Op(dt1=6, mul=1, tl=36, ar=24, d1r=3, d1l=2, rr=6),
                       Op(dt2=3, mul=2, tl=34, ar=24, d1r=3, d1l=2, rr=6), Op(mul=1, tl=5, ar=22, d1r=2, d1l=1, rr=6)],
                      alg=3, fb=4)
    I[9] = Instrument([Op(mul=4, tl=26, ar=31, d1r=12, d1l=9, rr=9), Op(mul=1, tl=8, ar=31, d1r=9, d2r=5, d1l=7, rr=9),
                       Op(dt1=1, dt2=1, mul=15, tl=20, ar=31, d1r=14, d1l=10, rr=10), Op(mul=1, tl=10, ks=1, ar=31, d1r=8, d1l=6, rr=9)],
                      alg=4, fb=3)
    I[10] = Instrument([Op(mul=2, tl=22, ar=26, d1r=2, d1l=2, rr=5), Op(dt1=3, mul=1, tl=10, ar=24, d1r=2, d1l=2, rr=5),
                        Op(dt1=7, mul=1, tl=12, ar=24, d1r=2, d1l=2, rr=5), Op(mul=1, tl=12, ar=24, d1r=2, d1l=2, rr=5)],
                       alg=5, fb=2)
    I[11] = Instrument([Op(mul=1, tl=18, ar=31, d1r=6, d1l=4, rr=6), Op(mul=1, tl=8, ar=31, d1r=5, d1l=3, rr=6),
                        Op(dt2=1, mul=2, tl=14, ks=2, ar=31, d1r=6, d1l=4, rr=6), Op(mul=0, tl=10, ar=31, d1r=4, d1l=3, rr=6)],
                       alg=6, fb=1)
    I[12] = Instrument([Op(mul=1, tl=14, ar=20, d1r=1, d1l=1, rr=4), Op(dt1=2, mul=2, tl=16, ar=20, d1r=1, d1l=1, rr=4),
                        Op(mul=4, tl=20, ar=20, d1r=2, d1l=2, rr=4), Op(dt2=2, mul=8, tl=24, ks=3, ar=20, d1r=3, d1l=3, rr=4)],
                       alg=7, fb=0)
    return I


def test_song():
    """8 channels, 4 orders (16/32/16/24 rows), loop to order 1 (also by B01 on order 3's last row).

    Exercises all eight algorithms, every effect (0 1 2 3 4 8 A B C D E F G H I L M N P Q S T V W X), up to four
    effect columns, the locks E I M H N at key-on, OFF cells, delays and cuts, empty runs, patterns used in two
    orders, noise on FM 8 with Nxx changes and a non-noise note there (noise off, frequency kept), CSM on with Qxx
    changes and off again, the LFO waveform through all four waves, a non-zero header LFO, and one explicit 000
    (arpeggio off without a note: order 0, channel 0, row 4), which Thor Tracker's cells cannot store."""
    C = Cell
    P = {}
    # Order 0 (16 rows)
    P["lead0"] = {0: C(60, 5, [("0", 0x37), ("I", 0x20)]), 4: C(None, None, [("0", 0x00)]),
                  6: C(62, None, [("0", 0x47), ("M", 0x23)]),
                  10: C(64, None, [("E", 0x03), ("M", 0x1F), ("I", 0x7F), ("H", 0x52)]),
                  13: C(NOTE_OFF), 14: C(65, 5, [("P", 0x88)])}
    P["bass0"] = {0: C(36, 6, [("1", 0x08)]), 3: C(None, None, [("1", 0x00)]), 4: C(36, None, [("2", 0x10)]),
                  6: C(None, None, [("2", 0x00)]), 8: C(43, None, [("P", 0x70)]), 12: C(41), 15: C(NOTE_OFF)}
    P["pad0"] = {0: C(60, 3), 8: C(57, None, [("8", 0x01), ("H", 0x73)]), 15: C(NOTE_OFF)}
    P["keys0"] = {0: C(64, 7, [("4", 0x34)]), 4: C(None, None, [("4", 0x30)]), 6: C(67, None, [("C", 0x60)]),
                  10: C(65, None, [("A", 0x04)]), 14: C(NOTE_OFF, None, [("A", 0x00)])}
    P["brass0"] = {0: C(48, 8, [("C", 0x50), ("8", 0x10)]), 8: C(50, None, [("8", 0x11), ("A", 0x30)]),
                   12: C(NOTE_OFF, None, [("A", 0x00)])}
    P["kick"] = {0: C(36, 4), 4: C(36, 4), 8: C(36, 4), 12: C(36, 4), 14: C(38, 4, [("G", 0x02)])}
    P["snare"] = {4: C(50, 9), 12: C(50, 9, [("S", 0x03)]), 15: C(52, 9, [("G", 0x01), ("S", 0x04)])}
    P["hat"] = {0: C(84, NOISE_SLOT), 2: C(86, NOISE_SLOT, [("S", 0x02)]), 4: C(84, NOISE_SLOT, [("N", 0x10)]),
                6: C(86, NOISE_SLOT, [("S", 0x02)]), 8: C(84, NOISE_SLOT), 10: C(86, NOISE_SLOT, [("S", 0x02)]),
                12: C(84, NOISE_SLOT, [("N", 0x18)]), 14: C(86, NOISE_SLOT, [("S", 0x02)])}
    # Order 1 (32 rows, the loop). No Fxx: the loop (B01 from order 3 at speed 5) starts at the first pass's speed.
    P["lead1"] = {0: C(72, 10, [("3", 0x00)]), 2: C(74, None, [("3", 0x18)]), 4: C(77), 8: C(None, None, [("3", 0x00)]),
                  10: C(79, None, [("4", 0x26)]), 16: C(NOTE_OFF), 18: C(72, 10, [("1", 0x04)]),
                  22: C(None, None, [("2", 0x06)]), 26: C(None, None, [("2", 0x00)]), 28: C(76, None, [("P", 0x90)]),
                  31: C(NOTE_OFF)}
    P["bass1"] = {0: C(36, 11, [("H", 0x41)]), 4: C(None, None, [("H", 0x22)]), 8: C(43), 12: C(41, None, [("C", 0x40)]),
                  16: C(36, None, [("C", 0x7F)]), 20: C(38, None, [("A", 0x01)]), 24: C(40, None, [("A", 0x10)]),
                  28: C(NOTE_OFF, None, [("A", 0x00)])}
    P["pad1"] = {0: C(64, 3, [("L", 0xC0), ("T", 0x40)]), 8: C(None, None, [("V", 0x50), ("W", 0x00)]),
                 16: C(67, None, [("W", 0x01)]), 24: C(None, None, [("W", 0x03), ("L", 0x80)]),
                 30: C(NOTE_OFF, None, [("W", 0x02), ("T", 0x10), ("V", 0x20)])}
    P["keys1"] = {2: C(55, 12), 10: C(52, 12, [("I", 0x40)]), 18: C(50, 7, [("8", 0x01)]), 26: C(48, 1)}
    P["chords1"] = {4: C(60, 8, [("0", 0x47)]), 12: C(62, None, [("0", 0x38)]), 20: C(64), 28: C(NOTE_OFF)}
    P["kick1"] = {0: C(36, 4), 8: C(36, 4), 16: C(36, 4), 24: C(36, 4), 28: C(36, 4, [("G", 0x03)])}
    P["stab1"] = {5: C(60, 9, [("E", 0x05)]), 13: C(62, 9, [("E", 0x00), ("8", 0x11)]), 21: C(64, 5, [("M", 0x40)]),
                  29: C(65, 6, [("S", 0x02)])}
    P["noise1"] = {0: C(84, NOISE_SLOT, [("N", 0x08)]), 4: C(None, None, [("N", 0x14)]), 8: C(86, NOISE_SLOT),
                   12: C(60, 1), 16: C(84, NOISE_SLOT, [("N", 0x1F), ("C", 0x60)]),
                   20: C(86, NOISE_SLOT, [("S", 0x01)]), 24: C(84, NOISE_SLOT), 28: C(86, NOISE_SLOT, [("G", 0x02)])}
    # Order 2 (16 rows): speed changes, CSM on with timer A changes and off, pattern break at row 12 -> order 3 row 2
    P["o2lead"] = {0: C(60, 5, [("F", 0x04), ("4", 0x48)]), 4: C(62, None, [("1", 0x20)]), 8: C(64, None, [("F", 0x05)]),
                   12: C(65, None, [("D", 0x02), ("1", 0x00)])}
    P["o2csm"] = {0: C(None, None, [("X", 0x01), ("Q", 0xF0)]), 4: C(None, None, [("Q", 0xC8)]),
                  10: C(None, None, [("X", 0x00)])}
    P["o2bell"] = {0: C(72, 7, [("8", 0x10)]), 6: C(76, 7, [("8", 0x01), ("P", 0x90)]), 11: C(NOTE_OFF)}
    # Order 3 (24 rows): volumes, OFF cells, a noise note with four lock columns, B01 on the last row
    P["o3a"] = {0: C(55, 10, [("C", 0x40)]), 2: C(57, None, [("C", 0x7F), ("G", 0x01)]), 6: C(NOTE_OFF), 8: C(59, 11),
                16: C(60, 5, [("4", 0x13)]), 23: C(NOTE_OFF, None, [("B", 0x01)])}
    P["o3b"] = {2: C(64, 6, [("H", 0x31)]), 9: C(NOTE_OFF), 12: C(62, 8), 20: C(60, 12, [("P", 0x40)])}
    P["o3c"] = {0: C(36, 4, [("8", 0x10)]), 6: C(36, 4, [("8", 0x01)]), 12: C(43, 4),
                18: C(36, 4, [("8", 0x11), ("S", 0x01)])}
    P["o3n"] = {2: C(84, NOISE_SLOT, [("N", 0x04), ("E", 0x07), ("I", 0x10), ("M", 0x4F)]), 10: C(NOTE_OFF),
                14: C(86, NOISE_SLOT)}

    orders = [
        Order(16, ["lead0", "bass0", "pad0", "keys0", "brass0", "kick", "snare", "hat"]),
        Order(32, ["lead1", "bass1", "pad1", "keys1", "chords1", "kick1", "stab1", "noise1"]),
        Order(16, ["o2lead", "bass0", "o2csm", "o2bell", None, "kick", "snare", "hat"]),
        Order(24, ["o3a", "o3b", None, None, None, "o3c", None, "o3n"]),
    ]
    return Song(speed=6, orders=orders, patterns=P, instruments=test_instruments(), loop=1,
                lfo_rate=0x9C, lfo_wave=2, amd=0x18, pmd=0x24, csm=False, timer_a=0x1F1)


def edited_song():
    """The test song with one note edited in order 2 (row 6 of FM 4: 76 -> 74)."""
    s = test_song()
    s.patterns["o2bell"] = dict(s.patterns["o2bell"])
    s.patterns["o2bell"][6] = Cell(74, 7, [("8", 0x01), ("P", 0x90)])
    return s


def tempo_song():
    """The test song with an Fxx added ahead in the song (F09 on order 1's row 4 of FM 8)."""
    s = test_song()
    s.patterns["noise1"] = dict(s.patterns["noise1"])
    s.patterns["noise1"][4] = Cell(None, None, [("N", 0x14), ("F", 0x09)])
    return s


def short_song():
    """Only the first two orders, loop to order 0 (a live edit that removes the playing order)."""
    s = test_song()
    s.orders = s.orders[:2]
    s.loop = 0
    return s


def x2_song():
    """The test song at multispeed 2."""
    s = test_song()
    s.multispeed = 2
    return s


def lfo_song():
    """The test song with every shared header value edited: LFO rate, waveform, AMD, PMD, and CSM on at timer A 900."""
    s = test_song()
    s.lfo_rate, s.lfo_wave, s.amd, s.pmd = 0x40, 1, 0x60, 0x7F
    s.csm, s.timer_a = True, 900
    return s
