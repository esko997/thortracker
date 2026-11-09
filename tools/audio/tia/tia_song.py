#!/usr/bin/env python3
"""Atari 2600 TIA image builder (the layout of docs/AUDIO_ENGINE.md "Atari 2600: image layout" and its lowering rules,
as the Kotlin lowering must produce it) and the hand-built test songs of the native host test.

A song here: Song(speed, orders=[Order(rows, [pattern name per channel])], patterns={name: {row: Cell}},
instruments={slot: Instrument}, loop, region). build(song) returns the 65536-byte image; build(song, relayout=True)
lays the same song out differently (patterns in reverse order behind a 37-byte gap, so every pattern and envelope
offset changes) for the relocation test.

Lowering rules (the Kotlin lowering's): patterns first, byte-identical (order, channel) encodings stored once in
first-use order (orders, then channels); then each filled slot's envelopes in slot order (volume, arpeggio, pitch,
waveform), byte-identical value sequences stored once (first use). The instrument count is the highest filled slot.
Slots are never merged: two identical instruments are two records (their envelopes share data bytes)."""
import struct
from dataclasses import dataclass, field

IMAGE_SIZE = 65536
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 1344, 3392
INSTRUMENT_SIZE = 32
CHANNELS = 2
NOTE_OFF = 0xFE
ENV_NONE = 0xFF
REGION_NTSC, REGION_PAL = 0, 1
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}


@dataclass
class Env:
    """An envelope as Thor Tracker's TiaEnvelope: values, loop index (-1 none), release index (-1 none)."""
    values: list = field(default_factory=list)
    loop: int = -1
    release: int = -1


@dataclass
class Instrument:
    """Defaults as a new Thor Tracker TiaInstrument: AUDC 12 (div 6 square), tonal, no envelopes (volume 15)."""
    waveform: int = 12
    raw: bool = False
    relative: bool = False
    volume: Env = field(default_factory=Env)
    arpeggio: Env = field(default_factory=Env)
    pitch: Env = field(default_factory=Env)
    waveforms: Env = field(default_factory=Env)

    def envelopes(self):
        """The four envelope slots as written: (volume, arpeggio, pitch, waveform)."""
        return [self.volume, self.arpeggio, self.pitch, self.waveforms]

    def record(self, offsets):
        """The 32-byte record; offsets[k] = data offset of envelope k's values (ignored when empty)."""
        rec = bytearray(INSTRUMENT_SIZE)
        rec[0] = 0x80 | (0x01 if self.raw else 0) | (0x04 if self.relative else 0)
        rec[1] = self.waveform
        for k, e in enumerate(self.envelopes()):
            at = 8 + 6 * k
            if e.values:
                struct.pack_into("<HBBBB", rec, at, offsets[k], len(e.values),
                                 ENV_NONE if e.loop < 0 else e.loop, ENV_NONE if e.release < 0 else e.release, 0)
            else:
                struct.pack_into("<HBBBB", rec, at, 0, 0, ENV_NONE, ENV_NONE, 0)
        return bytes(rec)


def env_bytes(e):
    return bytes(v & 0xFF for v in e.values)


@dataclass
class Cell:
    note: int = None          # 0..119, NOTE_OFF
    inst: int = None
    fx: list = field(default_factory=list)  # [(letter, param)], up to 2


@dataclass
class Order:
    rows: int
    patterns: list            # 2 pattern names (None = empty)


@dataclass
class Song:
    speed: int
    orders: list
    patterns: dict
    instruments: dict
    loop: int = 0
    region: int = REGION_NTSC


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
        assert len(c.fx) <= 2
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
    img[0:4] = b"TTIA"
    img[4], img[5], img[6] = 1, song.region, song.speed
    img[7] = max(song.instruments) if song.instruments else 0
    struct.pack_into("<HH", img, 8, len(song.orders), song.loop)
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
        data.extend(b"\x80" * 37)  # a gap of unused bytes moves every pattern and envelope
    for blob in (list(reversed(blobs)) if relayout else blobs):
        where[blob] = len(data)
        data.extend(blob)
    for o, row in enumerate(uses):
        for c, blob in enumerate(row):
            struct.pack_into("<H", img, OFF_ORDERS + 2 * (CHANNELS * o + c), where[blob])
    # envelopes: slot order, then volume, arpeggio, pitch, waveform; identical value bytes stored once
    envs = {}
    for slot in sorted(song.instruments):
        offsets = []
        for e in song.instruments[slot].envelopes():
            if e.values:
                key = env_bytes(e)
                if key not in envs:
                    envs[key] = len(data)
                    data.extend(key)
                offsets.append(envs[key])
            else:
                offsets.append(0)
        img[OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1): OFF_INSTRUMENTS + INSTRUMENT_SIZE * slot] = \
            song.instruments[slot].record(offsets)
    assert len(data) <= IMAGE_SIZE - OFF_DATA, "the song passes the image's data region"
    struct.pack_into("<I", img, 20, len(data))
    img[OFF_DATA: OFF_DATA + len(data)] = data
    return bytes(img)


# ---- the test song --------------------------------------------------------------------------------------------------

def test_instruments():
    """Every AUDC value 0..15 is played by some instrument, waveform envelope or Wxx."""
    I = {}
    # 1: div 6 square (AUDC C), plain
    I[1] = Instrument(waveform=12)
    # 2: div 2 lead (AUDC 4): volume decay looping its tail, absolute pitch wobble across divider edges
    I[2] = Instrument(waveform=4, volume=Env([15, 14, 13, 12, 11, 10], loop=4),
                      pitch=Env([0, 16, 32, 16, 0, -16, -32, -16], loop=0))
    # 3: div 31 pad (AUDC 6): swell held at index 3, released from 4; looping octave arpeggio envelope
    I[3] = Instrument(waveform=6, volume=Env([4, 7, 10, 13, 12, 9, 6, 3, 0], loop=3, release=4),
                      arpeggio=Env([0, 0, 12, 12], loop=0))
    # 4: waveform envelope (ignores Wxx): C, C, 4, 1, 7, F, looping from 2
    I[4] = Instrument(waveform=12, volume=Env([15, 13, 11, 9, 8]), waveforms=Env([12, 12, 4, 1, 7, 15], loop=2))
    # 5: relative pitch on div 6 (AUDC D): wobble while held, falls after the release; volume released from 4
    I[5] = Instrument(waveform=13, volume=Env([15, 14, 13, 12, 8, 5, 2, 0], loop=3, release=4),
                      pitch=Env([4, 2, 2, -2, -2, -16, -16], loop=1, release=5), relative=True)
    # 6: raw hat (AUDC 8) with a divider arpeggio envelope
    I[6] = Instrument(waveform=8, raw=True, volume=Env([15, 11, 7, 4, 2, 1, 0]), arpeggio=Env([0, -3, 2]))
    # 7: raw snare (AUDC 8) held at index 2, released from 3
    I[7] = Instrument(waveform=8, raw=True, volume=Env([14, 13, 12, 9, 6, 3, 0], loop=2, release=3))
    # 8: raw kick: AUDC 3, then F, E, E; dividers falling
    I[8] = Instrument(waveform=15, raw=True, volume=Env([15, 15, 12, 8, 4, 0]), waveforms=Env([3, 15, 14, 14]),
                      arpeggio=Env([0, -2, -4, -6, -8]))
    # 9: DC click: AUDC 0 (constant), tonal: no AUDF write
    I[9] = Instrument(waveform=0, volume=Env([15, 8, 0, 4, 2, 0]))
    # 10: rumble AUDC 2 (465), tonal
    I[10] = Instrument(waveform=2)
    # 11: 4-bit poly buzz AUDC 1, tonal
    I[11] = Instrument(waveform=1, volume=Env([12]))
    # 12: div 93 AUDC E, tonal
    I[12] = Instrument(waveform=14)
    # 13: div 2 AUDC 5, tonal
    I[13] = Instrument(waveform=5, volume=Env([10, 9, 8], loop=2))
    # 14: div 31 AUDC A, tonal
    I[14] = Instrument(waveform=10)
    # 15: constant AUDC B, raw (writes AUDF anyway)
    I[15] = Instrument(waveform=11, raw=True, volume=Env([9, 0, 9, 0], loop=0))
    # 16: AUDC 3 (5-bit -> 4-bit poly), raw
    I[16] = Instrument(waveform=3, raw=True)
    # 17: AUDC 7 (5-bit poly -> div 2), tonal
    I[17] = Instrument(waveform=7)
    # 18: byte-identical to slot 1: its own record (slot identity)
    I[18] = Instrument(waveform=12)
    return I


def test_song():
    """2 channels, 4 orders (16/32/16/24 rows), loop to order 1 (also by B01 on order 3's last row).

    Exercises every effect (0 1 2 3 4 A B C D F G P S W), two effect columns, tonal and raw instruments, every AUDC,
    volume/arpeggio/pitch/waveform envelopes with loops and release points, a relative pitch envelope, OFF with and
    without release, delays (one never due: G07 at speed 5) and cuts, empty runs, a pattern used in two orders,
    notes C-0 and B-9 (clamped to the waveform's ends), a constant waveform without AUDF writes, Wxx ignored by a
    waveform-envelope instrument, portamento and vibrato across divider edges, Dxx and the loop speed."""
    C = Cell
    P = {}
    # Order 0 (16 rows)
    P["a0"] = {0: C(60, 1, [("0", 0x37)]), 4: C(None, None, [("4", 0x34)]), 6: C(62, 2, [("0", 0x47), ("P", 0x88)]),
               10: C(64, None, [("W", 0x0C), ("C", 0x0B)]), 11: C(None, None, [("A", 0x02)]), 13: C(NOTE_OFF),
               14: C(65, 3, [("4", 0x30), ("A", 0x00)])}
    P["b0"] = {0: C(36, 5, [("1", 0x08)]), 3: C(None, None, [("1", 0x00)]), 4: C(36, None, [("2", 0x10)]),
               6: C(None, None, [("2", 0x00)]), 8: C(48, 6), 9: C(50, 7, [("S", 0x02)]), 10: C(43, 8, [("G", 0x02)]),
               12: C(41, 4, [("W", 0x01)]), 15: C(NOTE_OFF)}
    # Order 1 (32 rows, the loop). No Fxx: the loop (B01 from order 3 at speed 5) starts at the first pass's speed 6.
    P["a1"] = {0: C(57, 11), 4: C(59, 12), 8: C(0, 10), 10: C(119, 13), 12: C(60, 14, [("3", 0x20)]), 14: C(72),
               16: C(None, None, [("3", 0x00)]), 18: C(55, 17, [("4", 0x2F)]), 22: C(30, 16), 24: C(66, 15),
               26: C(70, 18), 28: C(NOTE_OFF), 30: C(64, 9)}
    P["b1"] = {0: C(48, 8), 4: C(66, 6), 8: C(48, 8, [("C", 0x08)]), 12: C(68, 7), 14: C(NOTE_OFF),
               16: C(48, 8, [("C", 0x0F)]), 20: C(66, 6), 24: C(48, 8), 26: C(57, 1, [("W", 0x09)]),
               28: C(None, None, [("W", 0x0E)]), 30: C(NOTE_OFF)}
    # Order 2 (16 rows): speed changes, pattern break at row 12 -> order 3 row 2; b0 again (same bytes: stored once)
    P["c2"] = {0: C(60, 2, [("F", 0x04), ("4", 0x48)]), 4: C(62, None, [("1", 0x20)]), 8: C(64, None, [("F", 0x05)]),
               12: C(65, None, [("D", 0x02), ("1", 0x00)])}
    # Order 3 (24 rows): volumes, a delay never due (G07 at speed 5), cut, B01 on the last row
    P["d3a"] = {0: C(55, 3, [("C", 0x0A)]), 2: C(57, None, [("C", 0x0F), ("G", 0x01)]), 6: C(NOTE_OFF), 8: C(59, 2),
                16: C(60, 2, [("4", 0x13)]), 23: C(NOTE_OFF, None, [("B", 0x01)])}
    P["d3b"] = {0: C(36, 1), 4: C(38, 5, [("G", 0x07)]), 10: C(40, 5, [("S", 0x03)]), 14: C(41, 1, [("A", 0x01)]),
                20: C(NOTE_OFF, None, [("A", 0x00)])}
    orders = [
        Order(16, ["a0", "b0"]),
        Order(32, ["a1", "b1"]),
        Order(16, ["c2", "b0"]),
        Order(24, ["d3a", "d3b"]),
    ]
    return Song(speed=6, orders=orders, patterns=P, instruments=test_instruments(), loop=1)


def edited_song():
    """The test song after a live edit: order 0 starts at speed 3, order 1 has other notes, order 2 shrinks to 8 rows
    (its Dxx row gone) and starts at speed 5, slot 2's envelopes change length (a sounding note clamps its indices),
    slot 18 (identical to slot 1 before) gets another waveform while slot 1 keeps its own, slot 9 is removed and slot
    5 is removed (its rows now name slot 1: a note still sounding slot 5 when the edit lands stops at its next
    update)."""
    s = test_song()
    C = Cell
    s.patterns["a0"] = dict(s.patterns["a0"])
    s.patterns["a0"][0] = C(60, 1, [("0", 0x37), ("F", 0x03)])
    s.patterns["a1"] = dict(s.patterns["a1"])
    s.patterns["a1"][4] = C(61, 12)
    s.patterns["a1"][30] = C(64, 1)
    s.patterns["b0"] = dict(s.patterns["b0"])
    s.patterns["b0"][0] = C(36, 1, [("1", 0x08)])
    s.patterns["d3b"] = dict(s.patterns["d3b"])
    s.patterns["d3b"][4] = C(38, 1, [("G", 0x07)])
    s.patterns["d3b"][10] = C(40, 1, [("S", 0x03)])
    s.patterns["c2"] = {0: C(60, 2, [("F", 0x05), ("4", 0x48)]), 4: C(63, None, [("1", 0x20)]),
                        6: C(None, None, [("1", 0x00)])}
    s.patterns["e2"] = {0: C(45, 18), 4: C(NOTE_OFF)}
    s.orders[2] = Order(8, ["c2", "e2"])
    s.instruments[2] = Instrument(waveform=4, volume=Env([15, 12], loop=1), pitch=Env([0, 24], loop=0))
    s.instruments[18] = Instrument(waveform=4)
    del s.instruments[9]
    del s.instruments[5]
    return s


def tempo_song():
    """Speed and position edge cases: header speed 3, a one-row order with F01, Gxx at speed 1 (never due) and at
    speed 2, Dxx past the target order's rows (clamped to its last row), B00 + D01 into a one-row order (clamped),
    a 64-row order and the loop to order 1."""
    C = Cell
    P = {
        "t0": {0: C(60, 1, [("F", 0x01)])},
        "t1": {0: C(62, 1, [("G", 0x01)]), 1: C(None, None, [("F", 0x02)]), 2: C(64, None, [("G", 0x01)]),
               3: C(None, None, [("D", 0x7F)])},
        "t2": {0: C(48, 6, [("G", 0x05)]), 2: C(NOTE_OFF, None, [("F", 0x03)])},
        "t3": {0: C(57, 2), 32: C(59, None, [("3", 0x08)]), 33: C(64), 63: C(NOTE_OFF, None, [("B", 0x00), ("D", 0x01)])},
    }
    orders = [Order(1, ["t0", None]), Order(4, ["t1", "t2"]), Order(64, ["t3", None])]
    return Song(speed=3, orders=orders, patterns=P, instruments=test_instruments(), loop=1)


def short_song():
    """The shortest valid song (also the WAV fixture tia-shortest): one row, A-4 held on TIA 1 with a div 6 square
    without envelopes (AUDC C, AUDF 11: 436.10 Hz NTSC)."""
    return Song(speed=6, orders=[Order(1, ["s", None])], patterns={"s": {0: Cell(57, 1)}},
                instruments={1: Instrument(waveform=12)})


def pal_song():
    """The test song on the PAL engine."""
    s = test_song()
    s.region = REGION_PAL
    return s
