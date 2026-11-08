#!/usr/bin/env python3
"""YM2612 image builder (the layout of docs/AUDIO_ENGINE.md "YM2612: image layout", as the Kotlin lowering must
produce it) and the hand-built test song of the native host test.

A song here: Song(speed, region, lfo, loop, instruments={slot: FmInstrument | PsgInstrument},
orders=[Order(rows, [pattern name per channel x 10])], patterns={name: {row: Cell}}). Channels 0-5 = FM 1-6,
6-8 = PSG 1-3, 9 = Noise. build(song) returns the 65536-byte image; build(song, relayout=True) lays the same song
out differently (patterns in reverse order behind a 37-byte gap, so every offset changes) for the live-edit test.

    python3 tools/audio/ym/ym_song.py OUT.bin [test|edited|tempo|short|pal|relayout]"""
import struct
import sys
from dataclasses import dataclass, field

IMAGE_SIZE = 65536
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 5440, 8000
INSTRUMENT_SIZE = 40
CHANNELS = 10
NOTE_OFF = 0xFE
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}


@dataclass
class FmOp:
    dt: int = 0        # 0..7 (4..7 = negative detune)
    mul: int = 1       # 0..15 (0 = x1/2)
    tl: int = 0        # 0..127
    rs: int = 0        # rate scaling 0..3
    ar: int = 31
    am: bool = False
    d1r: int = 0
    d2r: int = 0
    d1l: int = 0
    rr: int = 15
    ssg: int = 0       # SSG-EG 0..15 (8..15 = on)

    def regs(self):
        return [self.dt << 4 | self.mul, self.tl, self.rs << 6 | self.ar, self.am << 7 | self.d1r, self.d2r,
                self.d1l << 4 | self.rr, self.ssg]


@dataclass
class FmInstrument:
    ops: list                 # 4 FmOp, operators 1..4 as in the algorithm diagrams
    alg: int = 0
    fb: int = 0
    ams: int = 0              # 0..3
    fms: int = 0              # 0..7
    sweep: tuple = (0, 0, 0)  # start, end (semitones), speed (1/16 semitone per tick)

    def record(self):
        rec = bytearray(INSTRUMENT_SIZE)
        rec[0], rec[1], rec[2], rec[3] = 0x80, self.alg, self.fb, self.ams << 4 | self.fms
        for k, op in enumerate(self.ops):
            rec[4 + 7 * k: 11 + 7 * k] = bytes(op.regs())
        struct.pack_into("bbB", rec, 32, *self.sweep)
        return bytes(rec)


@dataclass
class Env:
    values: list              # volume 0..15, arpeggio -64..63 semitones, pitch -64..63 x 1/16 semitone
    loop: int = None          # index, None = hold the last value at the end
    release: int = None       # volume only: where key-off continues (needs loop < release)

    def encode(self):
        none = 0xFFFF
        return struct.pack("<HHH", len(self.values), none if self.loop is None else self.loop,
                           none if self.release is None else self.release) + struct.pack("%db" % len(self.values), *self.values)


@dataclass
class PsgInstrument:
    volume: Env = None        # None = constant 15
    arpeggio: Env = None      # None = 0
    pitch: Env = None         # None = 0
    relative: bool = False    # the pitch envelope accumulates
    noise: int = 0            # Noise channel: bit 2 white, bits 0-1 source (3 = pitched by PSG 3)
    sweep: tuple = (0, 0, 0)

    def envelopes(self):
        return [self.volume, self.arpeggio, self.pitch]

    def record(self, offsets):
        """`offsets`: data offset of each envelope (None = no envelope)."""
        rec = bytearray(INSTRUMENT_SIZE)
        rec[0:3] = bytes((0x81, self.noise, 1 if self.relative else 0))
        struct.pack_into("<HHH", rec, 4, *(0xFFFF if o is None else o for o in offsets))
        struct.pack_into("bbB", rec, 32, *self.sweep)
        return bytes(rec)


@dataclass
class Cell:
    note: int = None          # 0..95, NOTE_OFF
    inst: int = None
    fx: list = field(default_factory=list)  # [(letter, param)] per effect column; None or ("0", 0) = blank


@dataclass
class Order:
    rows: int
    patterns: list            # 10 pattern names (None = empty)


@dataclass
class Song:
    speed: int
    orders: list
    patterns: dict
    instruments: dict
    region: int = 0           # 0 NTSC, 1 PAL
    lfo: int = 0              # register $22: 0 or $08-$0F
    loop: int = 0


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
        fx = [e for e in (c.fx if c else []) if e is not None and e != ("0", 0)]  # blank columns are omitted
        assert len(c.fx if c else []) <= 4
        if c is None or (c.note is None and c.inst is None and not fx):
            empty += 1
            continue
        flush()
        out.append((1 if c.note is not None else 0) | (2 if c.inst is not None else 0) | (len(fx) << 2))
        if c.note is not None:
            out.append(c.note)
        if c.inst is not None:
            out.append(c.inst)
        for letter, param in fx:
            out.extend((FX[letter], param))
    flush()
    return bytes(out)


def build(song, relayout=False):
    """The image. Data region: every distinct (order, channel) pattern in first-use order (orders, then channels),
    then the PSG envelopes of slots 1..64 ascending (volume, arpeggio, pitch), each deduplicated by its bytes against
    the envelopes before it; no padding. `relayout` moves everything (a gap, patterns reversed)."""
    img = bytearray(IMAGE_SIZE)
    img[0:4] = b"TTYM"
    img[4], img[5], img[6], img[7] = 1, song.region, song.speed, song.lfo
    struct.pack_into("<HH", img, 8, len(song.orders), song.loop)
    img[12] = max(song.instruments) if song.instruments else 0
    blobs, uses = [], []  # distinct encoded patterns, deduplicated by bytes
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
    envelope_at = {}
    for slot in sorted(song.instruments):
        inst = song.instruments[slot]
        at = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        if isinstance(inst, PsgInstrument):
            offsets = []
            for env in inst.envelopes():
                if env is None:
                    offsets.append(None)
                    continue
                blob = env.encode()
                if blob not in envelope_at:
                    envelope_at[blob] = len(data)
                    data.extend(blob)
                offsets.append(envelope_at[blob])
            img[at: at + INSTRUMENT_SIZE] = inst.record(offsets)
        else:
            img[at: at + INSTRUMENT_SIZE] = inst.record()
    assert len(data) <= IMAGE_SIZE - OFF_DATA
    struct.pack_into("<H", img, 14, len(data))
    img[OFF_DATA: OFF_DATA + len(data)] = data
    for o, row in enumerate(uses):
        for c, blob in enumerate(row):
            struct.pack_into("<H", img, OFF_ORDERS + 2 * (CHANNELS * o + c), where[blob])
    return bytes(img)


# ---- the test song ---------------------------------------------------------------------------------

def test_song():
    """10 channels, 4 orders (16/32/16/24 rows), loop to order 1 (also by B01 on the last row), NTSC, LFO on.

    Exercises all 8 FM algorithms with feedback on the six FM channels (FM 6 included), LFO (header, Lxx, AMS/FMS,
    operator AM), SSG-EG, DT/MUL variety, every effect on the channels that allow it with up to 4 columns (blank
    columns before used ones), OFF cells, same-note retriggers, an FM kick with a pitch sweep, white/periodic noise
    at fixed rates and pitched (muting PSG 3 while it sounds), PSG envelopes (volume loops before a release point,
    release tails, held ends, no envelope at all; arpeggio and absolute and relative pitch envelopes; one envelope
    shared by two instruments), empty runs and patterns used twice. Slot 1 = sine (A-4 measurement), slot 2 = plain
    PSG square."""
    I = {}
    mute = dict(tl=0x7F, ar=31, rr=15)
    # 1: pure sine: algorithm 7, only operator 4 audible
    I[1] = FmInstrument([FmOp(**mute), FmOp(**mute), FmOp(**mute), FmOp(tl=0, ar=31, rr=6)], alg=7, fb=0)
    # 2: plain PSG square: full level until key-off, then down one step every 2 ticks
    fade = Env([15] + [v for v in range(14, -1, -1) for _ in range(2)], loop=0, release=1)
    I[2] = PsgInstrument(volume=fade)
    # 3: bass, algorithm 0 (serial 1->2->3->4), feedback 5, detune variety
    I[3] = FmInstrument([FmOp(dt=3, mul=1, tl=0x23, ar=31, d1r=12, d2r=3, d1l=4, rr=8),
                         FmOp(dt=7, mul=2, tl=0x2C, ar=28, d1r=10, d2r=2, d1l=3, rr=8),
                         FmOp(dt=0, mul=1, tl=0x1A, ar=31, d1r=8, d2r=2, d1l=5, rr=8),
                         FmOp(dt=1, mul=1, tl=0x02, rs=1, ar=31, d1r=6, d2r=1, d1l=2, rr=9)], alg=0, fb=5)
    # 4: brass, algorithm 1 ((1 + 2) -> 3 -> 4), feedback 4
    I[4] = FmInstrument([FmOp(dt=2, mul=1, tl=0x20, ar=24, d1r=6, d2r=1, d1l=2, rr=6),
                         FmOp(dt=6, mul=1, tl=0x26, ar=22, d1r=5, d2r=1, d1l=2, rr=6),
                         FmOp(dt=0, mul=2, tl=0x1C, ar=26, d1r=4, d2r=1, d1l=3, rr=6),
                         FmOp(dt=0, mul=1, tl=0x04, ar=20, d1r=3, d2r=0, d1l=1, rr=7)], alg=1, fb=4)
    # 5: lead, algorithm 2 (1 + (2 -> 3) -> 4), feedback 6, LFO: AMS 2, FMS 3, operator AM on 2 and 4
    I[5] = FmInstrument([FmOp(dt=1, mul=3, tl=0x28, ar=31, d1r=4, d2r=0, d1l=2, rr=5),
                         FmOp(dt=5, mul=1, tl=0x30, ar=31, am=True, d1r=5, d2r=1, d1l=3, rr=5),
                         FmOp(dt=0, mul=2, tl=0x24, ar=31, d1r=3, d2r=0, d1l=2, rr=5),
                         FmOp(dt=2, mul=1, tl=0x06, ar=29, am=True, d1r=2, d2r=0, d1l=1, rr=6)],
                        alg=2, fb=6, ams=2, fms=3)
    # 6: clav, algorithm 3 ((1 -> 2) + 3 -> 4), feedback 3, SSG-EG on operators 1 and 3
    I[6] = FmInstrument([FmOp(dt=0, mul=5, tl=0x22, ar=31, d1r=14, d2r=6, d1l=6, rr=9, ssg=0x08),
                         FmOp(dt=4, mul=1, tl=0x2A, ar=31, d1r=12, d2r=5, d1l=5, rr=9),
                         FmOp(dt=3, mul=3, tl=0x26, ar=31, d1r=10, d2r=4, d1l=4, rr=9, ssg=0x0E),
                         FmOp(dt=0, mul=1, tl=0x05, rs=2, ar=31, d1r=8, d2r=3, d1l=3, rr=10)], alg=3, fb=3)
    # 7: e-piano, algorithm 4 (two pairs (1 -> 2) + (3 -> 4)), feedback 2, high ratio
    I[7] = FmInstrument([FmOp(dt=3, mul=14, tl=0x2E, ar=31, d1r=16, d2r=4, d1l=8, rr=7),
                         FmOp(dt=0, mul=1, tl=0x08, ar=31, d1r=6, d2r=2, d1l=4, rr=7),
                         FmOp(dt=7, mul=1, tl=0x24, ar=31, d1r=8, d2r=3, d1l=5, rr=7),
                         FmOp(dt=3, mul=1, tl=0x0A, ar=31, d1r=5, d2r=2, d1l=3, rr=7)], alg=4, fb=2)
    # 8: organ, algorithm 5 (1 -> 2, 3, 4), feedback 6, LFO: AMS 1, FMS 2, operator AM on carrier 3
    I[8] = FmInstrument([FmOp(dt=0, mul=2, tl=0x26, ar=31, d1r=2, d1l=1, rr=6),
                         FmOp(dt=1, mul=1, tl=0x10, ar=28, d1r=1, d1l=1, rr=6),
                         FmOp(dt=5, mul=2, tl=0x14, ar=28, am=True, d1r=1, d1l=1, rr=6),
                         FmOp(dt=0, mul=4, tl=0x18, ar=28, d1r=1, d1l=1, rr=6)], alg=5, fb=6, ams=1, fms=2)
    # 9: bell, algorithm 6 ((1 -> 2) + 3 + 4), feedback 1, inharmonic ratios
    I[9] = FmInstrument([FmOp(dt=2, mul=7, tl=0x24, ar=31, d1r=7, d2r=3, d1l=6, rr=5),
                         FmOp(dt=0, mul=3, tl=0x0C, ar=31, d1r=6, d2r=3, d1l=6, rr=5),
                         FmOp(dt=6, mul=0, tl=0x18, ar=31, d1r=5, d2r=2, d1l=5, rr=5),
                         FmOp(dt=0, mul=1, tl=0x10, ar=31, d1r=4, d2r=2, d1l=4, rr=5)], alg=6, fb=1)
    # 10: additive drawbar organ, algorithm 7 (1 + 2 + 3 + 4), feedback 7 on operator 1, AMS 3 / FMS 5
    I[10] = FmInstrument([FmOp(dt=0, mul=1, tl=0x14, ar=31, d1l=0, rr=7),
                          FmOp(dt=1, mul=2, tl=0x18, ar=31, d1l=0, rr=7),
                          FmOp(dt=0, mul=4, tl=0x1E, ar=31, am=True, d1l=0, rr=7),
                          FmOp(dt=2, mul=8, tl=0x24, ar=31, d1l=0, rr=7)], alg=7, fb=7, ams=3, fms=5)
    # 11: kick: algorithm 0, sweeping from +36 down to the note at 4 semitones per tick
    I[11] = FmInstrument([FmOp(mul=1, tl=0x30, ar=31, d1r=20, d2r=10, d1l=15, rr=15),
                          FmOp(mul=1, tl=0x7F, ar=31, rr=15),
                          FmOp(mul=1, tl=0x7F, ar=31, rr=15),
                          FmOp(mul=1, tl=0x00, ar=31, d1r=12, d2r=8, d1l=6, rr=12)], alg=0, fb=0, sweep=(36, 0, 64))
    # 12: PSG lead: swell to 15, settle at 8 (loop before the release point), release tail; looping vibrato in pitch
    I[12] = PsgInstrument(volume=Env([4, 8, 12, 15, 13, 11, 9, 8, 6, 4, 2, 0], loop=7, release=8),
                          pitch=Env([0, 1, 2, 1, 0, -1, -2, -1], loop=0))
    # 13: PSG pad: slow attack held at 10 with a looping major-chord arpeggio, long release
    I[13] = PsgInstrument(volume=Env([3, 6, 9, 10, 8, 6, 5, 4, 3, 2, 1, 0], loop=3, release=4),
                          arpeggio=Env([0, 4, 7], loop=0))
    # 14: PSG pluck: an octave blip, then decays to 0 and holds it (no release point: key-off is silent at once)
    I[14] = PsgInstrument(volume=Env([15, 13, 11, 9, 7, 5, 3, 1, 0]), arpeggio=Env([12, 0]))
    # 15: snare: white noise, clock/1024, decays to 4 and holds it
    I[15] = PsgInstrument(volume=Env([15, 12, 9, 7, 5, 4]), noise=4 | 1)
    # 16: hat: white noise, clock/512, short
    I[16] = PsgInstrument(volume=Env([12, 8, 4, 0]), noise=4 | 0)
    # 17: buzz: periodic noise, clock/2048, a two-step loop before the release point
    I[17] = PsgInstrument(volume=Env([8, 12, 11, 9, 6, 3, 0], loop=1, release=3), noise=0 | 2)
    # 18: tom: white noise pitched by PSG 3, sweeping +12 -> -12, and a relative pitch envelope falling on
    I[18] = PsgInstrument(volume=Env([15, 13, 11, 9, 8, 7, 6]), pitch=Env([-4], loop=0), relative=True,
                          noise=4 | 3, sweep=(12, -12, 24))
    # 19: PSG zap: tone with a downward sweep, the square's volume envelope (shared bytes)
    I[19] = PsgInstrument(volume=fade, sweep=(24, 0, 32))
    # 20: no envelopes: constant level 15 (Cxx and Axy only), silent at key-off
    I[20] = PsgInstrument()

    P = {}
    C = Cell
    OFF = NOTE_OFF
    # ---- Order 0 (16 rows): algorithms 0-5 on FM 1-6, PSG envelopes, noise kit with pitched noise.
    P["fm0_bass"] = {0: C(24, 3), 1: C(24), 2: C(24, None, [("1", 0x06)]), 4: C(None, None, [("1", 0x00)]),
                     6: C(31, None, [None, ("2", 0x0C)]), 8: C(None, None, [("2", 0x00)]),
                     10: C(27, None, [("P", 0x90)]), 12: C(29), 14: C(OFF), 15: C(24, 3, [("S", 0x03)])}
    P["fm1_brass"] = {0: C(48, 4), 4: C(52, None, [("4", 0x36)]), 8: C(55, None, [("4", 0x30)]), 12: C(OFF),
                      13: C(53, 4, [("E", 0x07), ("C", 0x60)])}
    P["fm2_lead"] = {0: C(60, 5, [("3", 0x00)]), 4: C(None, None, [("3", 0x10)]), 5: C(64), 8: C(67),
                     10: C(None, None, [("3", 0x00)]), 11: C(65, None, [("I", 0x60), ("M", 0x23)]), 15: C(OFF)}
    P["fm3_clav"] = {0: C(55, 6, [("0", 0x37)]), 2: C(None, None, [("0", 0x47)]), 4: C(57),
                     6: C(55, None, [("0", 0x0C), ("P", 0x70)]), 8: C(OFF), 10: C(59, 6, [("A", 0x04)]),
                     14: C(None, None, [("A", 0x00), ("C", 0x50)])}
    P["fm4_ep"] = {0: C(64, 7, [("8", 0x10)]), 4: C(67, None, [("8", 0x00)]), 6: C(None, None, [("8", 0x01)]),
                   8: C(69, None, [("8", 0x11)]), 12: C(OFF)}
    P["fm5_organ"] = {0: C(43, 8, [("L", 0x0E)]), 8: C(45, None, [("G", 0x02)]),
                      12: C(47, 8, [None, None, ("G", 0x07)])}  # G07 >= speed 6: never runs
    P["psg_lead"] = {0: C(60, 12), 3: C(OFF), 4: C(62, None, [("0", 0x37)]), 8: C(64, None, [("4", 0x24)]),
                     11: C(OFF), 12: C(67, 14), 14: C(OFF)}
    P["psg_pad"] = {0: C(48, 13, [("C", 0x0C)]), 8: C(52, None, [("A", 0x02)]), 12: C(None, None, [("A", 0x10)]),
                    15: C(OFF)}
    P["psg3_bass"] = {0: C(36, 2), 4: C(36), 5: C(36), 8: C(41, None, [("1", 0x04)]),
                      10: C(None, None, [("1", 0x00)]), 12: C(43, None, [("P", 0x88)])}
    P["noise0"] = {0: C(36, 16), 2: C(36, 16), 4: C(36, 15), 6: C(36, 16), 8: C(24, 18), 10: C(OFF),
                   12: C(36, 15, [("S", 0x02)]), 14: C(30, 17, [("N", 0x03)]), 15: C(OFF)}
    # ---- Order 1 (32 rows, the loop; no Fxx): algorithms 6 and 7, kick sweep, porta on PSG, LFO off and on.
    P["kick1"] = {0: C(24, 11), 8: C(24, 11), 16: C(24, 11), 24: C(24, 11), 28: C(26, 11, [("G", 0x03)])}
    P["bell1"] = {0: C(72, 9), 6: C(76, None, [("M", 0x47)]), 12: C(79, 9, [("8", 0x01)]),
                  16: C(77, 9, [("I", 0x20)]), 22: C(OFF), 24: C(74, 9, [("8", 0x11), ("S", 0x04)])}
    P["add1"] = {0: C(48, 10, [("C", 0x70)]), 8: C(50, None, [("E", 0x02)]), 16: C(52, None, [("I", 0x7F)]),
                 24: C(53, None, [("A", 0x08)]), 30: C(OFF, None, [("A", 0x00)])}
    P["clav1"] = {2: C(55, 6), 3: C(55), 10: C(58, 6, [("2", 0x04)]), 14: C(None, None, [("2", 0x00)]),
                  18: C(60, 4), 26: C(62, 4, [("4", 0x58)]), 31: C(OFF)}
    P["ep1"] = {4: C(64, 7), 12: C(66, 5, [("1", 0x02)]), 20: C(None, None, [("3", 0x00)]), 28: C(OFF)}
    P["fm6_1"] = {0: C(36, 8), 8: C(OFF), 10: C(38, 3, [("0", 0x0C)]), 16: C(36, 8, [("L", 0x00)]),
                  24: C(40, 8, [("L", 0x0B)])}
    P["psg1_1"] = {0: C(60, 19), 4: C(64, 19), 8: C(67, 12, [("3", 0x00)]), 12: C(None, None, [("3", 0x20)]),
                   13: C(72), 16: C(69), 20: C(OFF, None, [("3", 0x00)]), 24: C(65, 14, [("C", 0x0A)]),
                   28: C(65, 14, [("C", 0x0F)])}
    P["psg2_1"] = {0: C(55, 13), 16: C(57, 13, [("P", 0x60)]), 31: C(OFF)}
    P["psg3_1"] = {0: C(45, 2, [("4", 0x4F)]), 8: C(45, 2, [("4", 0x40)]), 16: C(48, 2, [("S", 0x03)]), 24: C(43, 2)}
    P["noise1"] = {0: C(36, 16), 2: C(36, 16), 4: C(36, 15), 6: C(36, 16), 9: C(26, 18), 11: C(OFF),
                   12: C(36, 15), 14: C(36, 16, [("A", 0x01)]), 16: C(None, None, [("A", 0x00), ("C", 0x0F)]),
                   20: C(36, 15), 22: C(36, 17), 23: C(36, 17, [("N", 0x11)]), 26: C(28, 17, [("N", 0x03)]),
                   28: C(36, 15), 30: C(OFF)}
    # ---- Order 2 (16 rows): F04, then two Fxx in one row (the later channel wins), D02 + B03 -> order 3 row 2.
    P["ep2"] = {0: C(60, 7, [("F", 0x04)]), 4: C(63), 8: C(65, None, [("F", 0x09)]), 12: C(67, None, [("D", 0x02)])}
    P["lead2"] = {0: C(72, 5, [("4", 0x48), ("8", 0x10)]), 4: C(74, None, [("1", 0x20)]),
                  8: C(None, None, [("1", 0x00), ("L", 0x08)])}
    P["bell2"] = {0: C(79, 9, [("E", 0x05), ("M", 0x11), ("I", 0x40)]), 6: C(OFF)}
    P["fm6_2"] = {0: C(36, 10), 8: C(36, 10, [("G", 0x02), ("F", 0x05)]), 12: C(None, None, [("B", 0x03)])}
    P["psg3_2"] = {0: C(50, 2, [("2", 0x08)]), 6: C(None, None, [("2", 0x00)]),
                   8: C(53, None, [None, None, None, ("0", 0x47)])}
    # ---- Order 3 (24 rows, entered at row 2): 4 columns of locks, sine, F07, pitched white noise, B01 at the end.
    P["o3a"] = {0: C(60, 10), 2: C(55, 4, [("C", 0x60)]), 4: C(57, None, [("C", 0x7F), ("G", 0x01)]), 8: C(OFF),
                10: C(59, 6, [("E", 0x03), ("I", 0x50), ("M", 0x12), ("4", 0x22)]), 16: C(57, 1, [("F", 0x07)]),
                23: C(OFF, None, [("B", 0x01)])}
    P["o3b"] = {2: C(36, 3, [("A", 0x10)]), 9: C(OFF), 12: C(31, 3, [("P", 0xA0)]), 20: C(29, 3, [("S", 0x02)])}
    P["o3d"] = {2: C(48, 8, [("8", 0x01)]), 12: C(50, 8, [("8", 0x10)]), 20: C(OFF)}
    P["o3psg1"] = {2: C(72, 14), 3: C(72, 14), 4: C(74, 14, [("0", 0x37)]), 8: C(76, 12, [("S", 0x03)]),
                   12: C(None, None, [("L", 0x0D)])}
    P["o3psg2"] = {2: C(57, 20, [("A", 0x01)]), 16: C(None, None, [("A", 0x00)]), 20: C(OFF)}
    P["o3psg3"] = {4: C(40, 13), 18: C(OFF)}
    P["o3noise"] = {2: C(24, 15, [("N", 0x13)]), 6: C(OFF), 8: C(36, 16), 16: C(36, 15, [("G", 0x02), ("S", 0x04)])}

    orders = [
        Order(16, ["fm0_bass", "fm1_brass", "fm2_lead", "fm3_clav", "fm4_ep", "fm5_organ",
                   "psg_lead", "psg_pad", "psg3_bass", "noise0"]),
        Order(32, ["kick1", "bell1", "add1", "clav1", "ep1", "fm6_1", "psg1_1", "psg2_1", "psg3_1", "noise1"]),
        Order(16, ["fm0_bass", "ep2", "lead2", None, "bell2", "fm6_2", "psg_lead", "psg_pad", "psg3_2", "noise0"]),
        Order(24, ["o3a", "o3b", None, "o3d", None, "kick1", "o3psg1", "o3psg2", "o3psg3", "o3noise"]),
    ]
    return Song(speed=6, orders=orders, patterns=P, instruments=I, region=0, lfo=0x0B, loop=1)


def edited_song():
    """The test song with one cell edited in order 1 (row 16 of channel 1: a different note and instrument)."""
    s = test_song()
    s.patterns["bell1"] = dict(s.patterns["bell1"])
    s.patterns["bell1"][16] = Cell(76, 10, [("I", 0x20)])
    return s


def tempo_song():
    """The test song with Fxx edited behind order 1's row 8 (F09 on row 4 of channel 9)."""
    s = test_song()
    s.patterns["noise1"] = dict(s.patterns["noise1"])
    s.patterns["noise1"][4] = Cell(36, 15, [("F", 0x09)])
    return s


def short_song():
    """Only the first two orders, loop 1 (a live edit that removes the playing order)."""
    s = test_song()
    s.orders = s.orders[:2]
    s.loop = 1
    return s


def pal_song():
    """The test song in PAL (50 Hz ticks, PAL tables and clocks)."""
    s = test_song()
    s.region = 1
    return s


VARIANTS = {"test": test_song, "edited": edited_song, "tempo": tempo_song, "short": short_song, "pal": pal_song}


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if not 1 <= len(argv) <= 2 or (len(argv) == 2 and argv[1] not in list(VARIANTS) + ["relayout"]):
        sys.exit("usage: ym_song.py OUT.bin [%s|relayout]" % "|".join(VARIANTS))
    variant = argv[1] if len(argv) == 2 else "test"
    if variant == "relayout":
        data = build(test_song(), relayout=True)
    else:
        data = build(VARIANTS[variant]())
    with open(argv[0], "wb") as f:
        f.write(data)
    return 0


if __name__ == "__main__":
    sys.exit(main())
