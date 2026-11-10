#!/usr/bin/env python3
"""SAA1099 image builder (the canonical layout of docs/AUDIO_ENGINE.md "SAA1099: image layout", byte for byte what
the Kotlin lowering must produce) and the hand-built test songs of the native host test.

A song here: Song(speed, orders=[Order(rows, [pattern name per channel])], patterns={name: {row: Cell}},
instruments={slot: Instrument}, loop, multispeed, variant). build(song) returns the 65536-byte image;
build(song, relayout=True) lays the same song out differently (every distinct pattern and envelope byte string in
reverse order behind a 37-byte gap, so every offset changes and the image stays valid) for the live-edit test.

Test song slots (see test_instruments(); the Kotlin golden songs rebuild them from the model):
  01 "tone"     no envelopes (volume 15, tone), pan 15/15
  02 "pluck"    volume [15 12 10 9 8 7 6 5 5 | 4 3 2 1 0] release 9, pan left 15 right 10
  03 "buzz"     volume [15 14 13 12], hardware envelope 0x86 (internal, repetitive decay), retrigger
  04 "pad"      volume [6 9 12 15 | 14 13 12 11 12 13 | 10 7 4 2 0] loop 4 release 10, mixer [1], pan left 15 right 9
  05 "arp"      volume [12], arpeggio [0 0 4 4 7 7] loop 0, pan left 9 right 15
  06 "kick"     volume [15 15 13 11 9 7 5 3 1 0], relative pitch [-16 -12 -8 -6 -4 -2], mixer [3 1 1], noise 0
  07 "snare"    volume [14 11 8 6 4 2 0], arpeggio [12 0], absolute pitch [8 4 0 -4], mixer [2 2 3 2], noise 1
  08 "hat"      volume [10 6 3 0], mixer [2], noise 0, pan left 15 right 7
  09 "bass"     volume [15 15 14 14 13]
  10 "noise3"   volume [12], mixer [2], noise 3 (clocked by the triple's first tone generator)
  11 "swell"    volume [15], hardware envelope 0xBB (external, repetitive triangle, 3-bit, inverted right), rate 2,
                retrigger
  12 "decay"    volume [15], hardware envelope 0xA4 (external, single decay), rate 2, no retrigger
  13 "envtone"  volume [15], mixer [0], hardware envelope 0x8A (internal, repetitive triangle), retrigger"""
import struct
from dataclasses import dataclass, field

IMAGE_SIZE = 65536
MAGIC = b"TTSA"
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 6464, 7488
INSTRUMENT_SIZE = 16
DATA_CAPACITY = IMAGE_SIZE - OFF_DATA
MAX_CHANNELS = 12
NOTE_OFF = 0xFE
ENV_NONE = 0xFFFF
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}
SAM, CMS = 0, 1
RELAYOUT_GAP = 37


def channels_of(variant):
    return 12 if variant == CMS else 6


@dataclass
class Env:
    values: list
    loop: int = -1
    release: int = -1


@dataclass
class Instrument:
    volume: Env = None
    arpeggio: Env = None
    pitch: Env = None
    mixer: Env = None
    relative: bool = False    # PITCH_RELATIVE
    noise: int = 0            # noise clock 0..3
    pan_left: int = 15
    pan_right: int = 15
    envelope: int = 0         # hardware envelope control value (0 = none; else bit 7 set)
    rate: int = 0             # external clock rate (only with envelope bit 5)
    retrigger: bool = False   # ENV_RETRIGGER (only with an envelope)

    def flags(self):
        return 0x80 | (0x01 if self.relative else 0) | (0x02 if self.retrigger and self.envelope else 0)

    def record_head(self):
        """Bytes 0..5 of the instrument record."""
        external = bool(self.envelope & 0x20)
        return bytes((self.flags(), self.noise, self.pan_left | self.pan_right << 4, self.envelope,
                      self.rate if external else 0, 0))


@dataclass
class Cell:
    note: int = None          # 0..119, NOTE_OFF
    inst: int = None
    fx: list = field(default_factory=list)  # [(letter, param)] = the cell's non-blank effect columns in order


@dataclass
class Order:
    rows: int
    patterns: list            # one pattern name per channel (None = empty)


@dataclass
class Song:
    speed: int
    orders: list
    patterns: dict
    instruments: dict
    loop: int = 0
    multispeed: int = 1
    variant: int = SAM


def encode_pattern(cells, rows):
    """A track: rows 0..rows-1, empty rows as maximal runs of at most 128."""
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


def encode_envelope(env):
    """Header (L, loop | 0xFFFF, release | 0xFFFF) and the values as bytes (two's complement), or None."""
    if env is None or not env.values:
        return None
    head = struct.pack("<HHH", len(env.values), ENV_NONE if env.loop < 0 else env.loop,
                       ENV_NONE if env.release < 0 else env.release)
    return head + bytes(v & 0xFF for v in env.values)


def build(song, relayout=False):
    channels = channels_of(song.variant)
    img = bytearray(IMAGE_SIZE)
    img[0:4] = MAGIC
    img[4], img[5], img[6], img[7] = 1, song.variant, song.multispeed, song.speed
    struct.pack_into("<HH", img, 8, len(song.orders), song.loop)
    img[12] = max(song.instruments) if song.instruments else 0
    img[13] = channels
    # Byte strings in placement order; an identical string already placed is reused (one map for tracks and envelopes).
    strings = []
    known = set()

    def place(blob):
        if blob not in known:
            known.add(blob)
            strings.append(blob)
        return blob

    uses = []
    for o, order in enumerate(song.orders):
        assert len(order.patterns) == channels
        img[OFF_ROWS + o] = order.rows & 0xFF
        row = []
        for c in range(channels):
            name = order.patterns[c]
            row.append(place(encode_pattern(song.patterns[name] if name else {}, order.rows)))
        uses.append(row)
    env_uses = {}
    for slot in sorted(song.instruments):
        inst = song.instruments[slot]
        blobs = []
        for env in (inst.volume, inst.arpeggio, inst.pitch, inst.mixer):
            blob = encode_envelope(env)
            blobs.append(place(blob) if blob is not None else None)
        env_uses[slot] = blobs
    data, where = bytearray(), {}
    if relayout:
        data.extend(b"\x80" * RELAYOUT_GAP)  # unused bytes: every offset moves
    for blob in (list(reversed(strings)) if relayout else strings):
        where[blob] = len(data)
        data.extend(blob)
    assert len(data) <= DATA_CAPACITY
    struct.pack_into("<H", img, 14, len(data))
    img[OFF_DATA: OFF_DATA + len(data)] = data
    for o, row in enumerate(uses):
        for c, blob in enumerate(row):
            struct.pack_into("<H", img, OFF_ORDERS + 2 * (MAX_CHANNELS * o + c), where[blob])
    for slot, blobs in env_uses.items():
        rec = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        img[rec: rec + 6] = song.instruments[slot].record_head()
        for k, blob in enumerate(blobs):
            struct.pack_into("<H", img, rec + 6 + 2 * k, ENV_NONE if blob is None else where[blob])
    return bytes(img)


# ---- the test songs ----------------------------------------------------------------------------------

def test_instruments():
    return {
        1: Instrument(),
        2: Instrument(volume=Env([15, 12, 10, 9, 8, 7, 6, 5, 5, 4, 3, 2, 1, 0], release=9), pan_right=10),
        3: Instrument(volume=Env([15, 14, 13, 12]), envelope=0x86, retrigger=True),
        4: Instrument(volume=Env([6, 9, 12, 15, 14, 13, 12, 11, 12, 13, 10, 7, 4, 2, 0], loop=4, release=10),
                      mixer=Env([1]), pan_right=9),
        5: Instrument(volume=Env([12]), arpeggio=Env([0, 0, 4, 4, 7, 7], loop=0), pan_left=9),
        6: Instrument(volume=Env([15, 15, 13, 11, 9, 7, 5, 3, 1, 0]), pitch=Env([-16, -12, -8, -6, -4, -2]),
                      relative=True, mixer=Env([3, 1, 1]), noise=0),
        7: Instrument(volume=Env([14, 11, 8, 6, 4, 2, 0]), arpeggio=Env([12, 0]), pitch=Env([8, 4, 0, -4]),
                      mixer=Env([2, 2, 3, 2]), noise=1),
        8: Instrument(volume=Env([10, 6, 3, 0]), mixer=Env([2]), noise=0, pan_right=7),
        9: Instrument(volume=Env([15, 15, 14, 14, 13])),
        10: Instrument(volume=Env([12]), mixer=Env([2]), noise=3),
        11: Instrument(volume=Env([15]), envelope=0xBB, rate=2, retrigger=True),
        12: Instrument(volume=Env([15]), envelope=0xA4, rate=2),
        13: Instrument(volume=Env([15]), mixer=Env([0]), envelope=0x8A, retrigger=True),
    }


def test_patterns():
    C = Cell
    P = {}
    # Order 0 (16 rows). Lane 1: vibrato, pan, fine pitch, an M/N override on a sounding note (claims noise 0 with
    # clock 1), slides, a delayed note, OFF ending a slide. Lane 2: hat (noise 0, clock 0) and snare (clock 1)
    # claiming noise 0 in turn, A-4 as lane 3's envelope clock, Y01 without a note, noise clock 3. Lane 3: the
    # internal envelope (clocked by lane 2), an Exx change mid-note, the envelope as the waveform (mixer off), E00.
    # Lane 4: drums on noise 1 (delays, cuts). Lane 5: bass with portamento, cut, OFF. Lane 6: external envelopes.
    P["l0"] = {0: C(60, 2, [("4", 0x24), ("8", 0xF4)]), 4: C(62, None, [("P", 0x90)]),
               6: C(None, None, [("N", 0x01), ("M", 0x03)]), 8: C(64, None, [("4", 0x30)]),
               10: C(None, None, [("1", 0x04)]), 12: C(65, 2, [("2", 0x02), ("G", 0x02)]),
               14: C(NOTE_OFF, None, [("2", 0x00)])}
    P["l1"] = {0: C(48, 8), 2: C(48, 7), 4: C(57, 1), 8: C(None, None, [("Y", 0x01)]), 12: C(52, 10),
               15: C(NOTE_OFF)}
    P["l2"] = {0: C(45, 3), 4: C(None, None, [("E", 0x8E)]), 8: C(45, 13), 12: C(None, None, [("E", 0x00)]),
               13: C(47, 3), 15: C(NOTE_OFF)}
    P["d3"] = {0: C(36, 6), 4: C(50, 7), 8: C(36, 6), 10: C(36, 6, [("G", 0x03)]), 12: C(50, 7, [("S", 0x02)]),
               14: C(52, 7, [("G", 0x01), ("S", 0x04)])}
    P["b4"] = {0: C(36, 9, [("C", 0x0C)]), 2: C(NOTE_OFF), 4: C(43, 2), 6: C(None, None, [("3", 0x20)]), 7: C(48),
               10: C(36, None, [("3", 0x10)]), 12: C(None, None, [("3", 0x00), ("S", 0x03)]), 13: C(NOTE_OFF),
               14: C(41, 2, [("C", 0x0F)]), 15: C(NOTE_OFF)}
    P["e5"] = {0: C(57, 11), 8: C(64, 12), 15: C(NOTE_OFF)}
    # Order 1 (32 rows, the loop, no Fxx): Y02 holds the chip, Y00 releases it; retriggers and an external Exx
    # override (rate 1) on lane 3; noise 1 claimed by the snare's N02 and the hat; an arpeggio lead on lane 6.
    P["pad1"] = {0: C(60, 4), 12: C(64, 4, [("C", 0x0A), ("8", 0x5F)]), 16: C(NOTE_OFF),
                 20: C(67, 4, [("4", 0x46), ("P", 0x70)]), 28: C(NOTE_OFF), 30: C(NOTE_OFF)}
    P["clk1"] = {0: C(55, 1, [("C", 0x04)]), 8: C(None, None, [("1", 0x08)]), 10: C(None, None, [("1", 0x00)]),
                 16: C(62, 8), 18: C(55, 1), 24: C(None, None, [("Y", 0x02)]), 26: C(None, None, [("Y", 0x00)]),
                 31: C(NOTE_OFF)}
    P["buzz1"] = {0: C(43, 3), 8: C(43, 3), 16: C(46, 3, [("E", 0xB3)]), 24: C(NOTE_OFF)}
    P["drum1"] = {0: C(36, 6), 4: C(50, 7), 8: C(36, 6), 12: C(50, 7), 16: C(36, 6), 20: C(50, 7), 24: C(36, 6),
                  28: C(50, 7, [("N", 0x02)]), 30: C(62, 8)}
    P["bass1"] = {0: C(24, 9), 4: C(31), 8: C(24, 9, [("1", 0x08)]), 10: C(None, None, [("1", 0x00)]), 12: C(29, 9),
                  16: C(24, 9, [("2", 0x04)]), 18: C(None, None, [("2", 0x00)]), 20: C(27, 9), 24: C(24, 9),
                  28: C(31, 9, [("S", 0x04)])}
    P["lead1"] = {2: C(72, 11, [("4", 0x26)]), 6: C(74), 10: C(76, None, [("0", 0x0C)]), 14: C(NOTE_OFF),
                  18: C(79, 5, [("P", 0x88), ("C", 0x0B), ("4", 0x22), ("A", 0x01)]),
                  22: C(77, None, [("3", 0x08)]), 24: C(72), 26: C(None, None, [("3", 0x00)]), 30: C(NOTE_OFF)}
    # Order 2 (16 rows): speed changes, Y03 (sound off) then Y00, break to order 3 row 2.
    P["o2a"] = {0: C(60, 2, [("F", 0x04), ("4", 0x48)]), 4: C(62, None, [("1", 0x20)]),
                8: C(64, None, [("F", 0x05)]), 12: C(65, None, [("D", 0x02), ("1", 0x00)])}
    P["o2b"] = {0: C(72, 8, [("Y", 0x03)]), 2: C(None, None, [("Y", 0x00)]), 6: C(76, 10, [("P", 0x90), ("A", 0x01)]),
                11: C(NOTE_OFF)}
    # Order 3 (24 rows): entered at row 2; Y01 with a note, a mixer override, the one-shot external decay on
    # lane 3 (no retrigger), hard pans, B01 on the last row at speed 5.
    P["o3a"] = {0: C(55, 4, [("C", 0x08)]), 2: C(57, None, [("C", 0x0F), ("G", 0x01)]), 6: C(NOTE_OFF), 8: C(59, 2),
                16: C(60, 1, [("4", 0x13), ("Y", 0x01)]), 23: C(NOTE_OFF, None, [("B", 0x01)])}
    P["o3b"] = {2: C(64, 5, [("0", 0x47)]), 9: C(NOTE_OFF), 12: C(62, 7), 20: C(60, 1, [("P", 0x40)])}
    P["o3c"] = {2: C(50, 13, [("M", 0x01)]), 10: C(NOTE_OFF), 12: C(52, 12), 20: C(NOTE_OFF)}
    P["o3d"] = {0: C(36, 6), 6: C(36, 6), 12: C(43, 6), 18: C(36, 6, [("S", 0x01)])}
    P["o3e"] = {4: C(84, 7, [("8", 0xF0)]), 10: C(NOTE_OFF), 14: C(86, 7, [("8", 0x0F)])}
    return P


def test_song(variant=SAM):
    """The SAM Coupé test song: 6 channels, 4 orders (16/32/16/24 rows), speed 6, loop to order 1 (also by B01 on
    order 3's last row). With variant=CMS the same six lanes on chip A and chip B silent (cms_song() fills it)."""
    orders = [
        Order(16, ["l0", "l1", "l2", "d3", "b4", "e5"]),
        Order(32, ["pad1", "clk1", "buzz1", "drum1", "bass1", "lead1"]),
        Order(16, ["o2a", "o2b", "l2", "d3", "b4", None]),
        Order(24, ["o3a", "o3b", "o3c", "o3d", None, "o3e"]),
    ]
    if variant == CMS:
        for o in orders:
            o.patterns = o.patterns + [None] * 6
    return Song(speed=6, orders=orders, patterns=test_patterns(), instruments=test_instruments(), loop=1,
                variant=variant)


def cms_song():
    """The CMS test song: chip A = the SAM song's lanes (with every Yxx), chip B = the same features on other lanes
    (bass on B1, drums claiming chip B's noise 0 on B2, envelopes on B3 clocked by B2 and on B6, no Yxx)."""
    s = test_song(CMS)
    chip_b = [
        ["b4", "d3", "l2", "pad1", None, "e5"],
        ["bass1", "drum1", "buzz1", "l0", None, "lead1"],
        ["b4", "d3", None, "pad1", None, None],
        ["o3b", "o3d", "o3c", None, None, "o3e"],
    ]
    for o, b in zip(s.orders, chip_b):
        o.patterns = o.patterns[:6] + b
    return s


def edited_song():
    """One note edited: order 1 row 16, channel 4 (SAA 5): C-2 -> D-2."""
    s = test_song()
    s.patterns["bass1"] = dict(s.patterns["bass1"])
    s.patterns["bass1"][16] = Cell(26, 9, [("2", 0x04)])
    return s


def tempo_song():
    """One Fxx added: order 1 row 4, channel 1 (SAA 2): F09 (the row and the rest of the order at 9 ticks, was 6)."""
    s = test_song()
    s.patterns["clk1"] = dict(s.patterns["clk1"])
    s.patterns["clk1"][4] = Cell(None, None, [("F", 0x09)])
    return s


def short_song():
    """Only the first two orders (a live edit that removes the playing order), loop order 1."""
    s = test_song()
    s.orders = s.orders[:2]
    s.loop = 1
    return s


def x2_song():
    """The test song at multispeed 2 (100 ticks per second)."""
    s = test_song()
    s.multispeed = 2
    return s


ENV_EDIT_SLOT = 3


def env_edit_song():
    """Slot 03 (the buzz on SAA 3 from order 0 row 0) edited: hardware envelope 0x86 -> 0x8A (repetitive triangle),
    volume [15 14 13 12] -> [12 12 10 8]."""
    s = test_song()
    s.instruments = dict(s.instruments)
    s.instruments[ENV_EDIT_SLOT] = Instrument(volume=Env([12, 12, 10, 8]), envelope=0x8A, retrigger=True)
    return s


def tone_song(variant=SAM, ticks=120):
    """A-4 held on lane 1 with the plain tone slot for `ticks` ticks (one order of one row), for pitch measurements."""
    channels = channels_of(variant)
    return Song(speed=ticks, orders=[Order(1, ["tone"] + [None] * (channels - 1))], patterns={"tone": {0: Cell(57, 1)}},
                instruments={1: Instrument()}, variant=variant)


def shortest_song(variant=SAM):
    """The shortest valid song (SaaDemoSong.shortest): one order of one row at speed 1, A-4 held on lane 1."""
    s = tone_song(variant, 1)
    return s
