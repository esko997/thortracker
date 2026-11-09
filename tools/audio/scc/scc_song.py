#!/usr/bin/env python3
"""SCC image builder (the canonical layout of docs/AUDIO_ENGINE.md "SCC and SCC+: image layout", byte for byte what
the Kotlin lowering must produce) and the hand-built test song of the native host test.

A song here: Song(speed, orders=[Order(rows, [pattern name per channel])], patterns={name: {row: Cell}},
instruments={slot: Instrument}, waves={number: [32 samples -128..127]}, loop, multispeed, variant).
build(song) returns the 65536-byte image; build(song, relayout=True) lays the same song out differently (every
distinct pattern and envelope byte string in reverse order behind a 37-byte gap, so every offset changes and the image
stays valid) for the live-edit test.

Test song slots (the host test relies on 1-3; see test_song()):
  01 "sine"      wave [1], no volume/arpeggio/pitch envelope, no RESTART
  02 "pluck"     volume [15 12 10 9 8 7 6 5 5 | 4 3 2 1 0] release 9 (ends at 0), wave [3]
  03 "lead"      RESTART, volume [15 14 13 12], wave [2]
  04 "pad"       volume [6 9 12 15 | 14 13 12 11 12 13 | 10 7 4 2 0] loop 4 release 10, wave [4 4 1 1 7 7] loop 0
  05 "arp"       volume [12], arpeggio [0 0 4 4 7 7] loop 0, wave [5]
  06 "kick"      RESTART, volume [15 15 13 11 9 7 5 3 1 0], relative pitch [-16 -12 -8 -6 -4 -2], wave [8 8 1]
  07 "snare"     volume [14 11 8 6 4 2 0], arpeggio [12 0], absolute pitch [8 4 0 -4], wave [6]
  08 "bell"      volume [15 13 11 9 8 7 8 9] loop 4, wave [1 7 4 3]
  09 "bass"      volume [15 15 14 14 13], wave [8]
  10 "shared"    volume [12], wave [3]
Waves: 1 sine floor(127 sin(2 pi i / 32) + 0.5), 2 square +-100, 3 saw -128 + 8 i, 4 triangle (peak 120), 5 pulse 25 %
(90 / -30), 6 noise (x = (75 x + 74) mod 65537 from 1, sample = (x & 255) - 128), 7 organ ((sine i + sine 2i) >> 1),
8 half sine (2 max(sine i, 0) - 127); 9..64 silent."""
import math
import struct
from dataclasses import dataclass, field

IMAGE_SIZE = 65536
MAGIC = b"TTSC"
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_WAVES, OFF_DATA = 64, 320, 2880, 3904, 5952
INSTRUMENT_SIZE = 16
DATA_CAPACITY = IMAGE_SIZE - OFF_DATA
CHANNELS = 5
WAVES = 64
NOTE_OFF = 0xFE
ENV_NONE = 0xFFFF
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}
SCC, PLUS = 0, 1
RELAYOUT_GAP = 37


@dataclass
class Env:
    values: list
    loop: int = -1
    release: int = -1


@dataclass
class Instrument:
    wave: Env
    volume: Env = None
    arpeggio: Env = None
    pitch: Env = None
    relative: bool = False   # PITCH_RELATIVE
    restart: bool = False    # RESTART

    def flags(self):
        return 0x80 | (0x01 if self.restart else 0) | (0x02 if self.relative else 0)


@dataclass
class Cell:
    note: int = None          # 0..95, NOTE_OFF
    inst: int = None
    fx: list = field(default_factory=list)  # [(letter, param)] = the cell's non-blank effect columns in order


@dataclass
class Order:
    rows: int
    patterns: list            # 5 pattern names (None = empty)


@dataclass
class Song:
    speed: int
    orders: list
    patterns: dict
    instruments: dict
    waves: dict
    loop: int = 0
    multispeed: int = 1
    variant: int = SCC


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
    img = bytearray(IMAGE_SIZE)
    img[0:4] = MAGIC
    img[4], img[5], img[6], img[7] = 1, song.variant, song.multispeed, song.speed
    struct.pack_into("<HH", img, 8, len(song.orders), song.loop)
    img[12] = max(song.instruments) if song.instruments else 0
    img[13] = WAVES
    for k in range(1, WAVES + 1):
        samples = song.waves.get(k, [0] * 32)
        assert len(samples) == 32 and all(-128 <= v <= 127 for v in samples)
        img[OFF_WAVES + 32 * (k - 1): OFF_WAVES + 32 * k] = bytes(v & 0xFF for v in samples)
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
        img[OFF_ROWS + o] = order.rows & 0xFF
        row = []
        for c in range(CHANNELS):
            name = order.patterns[c]
            row.append(place(encode_pattern(song.patterns[name] if name else {}, order.rows)))
        uses.append(row)
    env_uses = {}
    for slot in sorted(song.instruments):
        inst = song.instruments[slot]
        blobs = []
        for env in (inst.volume, inst.arpeggio, inst.pitch, inst.wave):
            blob = encode_envelope(env)
            blobs.append(place(blob) if blob is not None else None)
        assert blobs[3] is not None, "the wave envelope is required"
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
            struct.pack_into("<H", img, OFF_ORDERS + 2 * (CHANNELS * o + c), where[blob])
    for slot, blobs in env_uses.items():
        rec = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        img[rec] = song.instruments[slot].flags()
        for k, blob in enumerate(blobs):
            struct.pack_into("<H", img, rec + 2 + 2 * k, ENV_NONE if blob is None else where[blob])
    return bytes(img)


# ---- waves -------------------------------------------------------------------------------------------

def sine_wave():
    return [math.floor(127 * math.sin(2 * math.pi * i / 32) + 0.5) for i in range(32)]


def square_wave():
    return [100] * 16 + [-100] * 16


def saw_wave():
    return [-128 + 8 * i for i in range(32)]


def triangle_wave():
    return [15 * i if i <= 8 else 120 - 15 * (i - 8) if i <= 24 else -120 + 15 * (i - 24) for i in range(32)]


def pulse_wave():
    return [90] * 8 + [-30] * 24


def noise_wave():
    out, x = [], 1
    for _ in range(32):
        x = (x * 75 + 74) % 65537
        out.append((x & 0xFF) - 128)
    return out


def organ_wave():
    s = sine_wave()
    return [(s[i] + s[(2 * i) % 32]) >> 1 for i in range(32)]


def half_sine_wave():
    s = sine_wave()
    return [2 * max(s[i], 0) - 127 for i in range(32)]


def test_waves():
    return {1: sine_wave(), 2: square_wave(), 3: saw_wave(), 4: triangle_wave(), 5: pulse_wave(), 6: noise_wave(),
            7: organ_wave(), 8: half_sine_wave()}


def test_instruments():
    return {
        1: Instrument(wave=Env([1])),
        2: Instrument(wave=Env([3]), volume=Env([15, 12, 10, 9, 8, 7, 6, 5, 5, 4, 3, 2, 1, 0], release=9)),
        3: Instrument(wave=Env([2]), volume=Env([15, 14, 13, 12]), restart=True),
        4: Instrument(wave=Env([4, 4, 1, 1, 7, 7], loop=0),
                      volume=Env([6, 9, 12, 15, 14, 13, 12, 11, 12, 13, 10, 7, 4, 2, 0], loop=4, release=10)),
        5: Instrument(wave=Env([5]), volume=Env([12]), arpeggio=Env([0, 0, 4, 4, 7, 7], loop=0)),
        6: Instrument(wave=Env([8, 8, 1]), volume=Env([15, 15, 13, 11, 9, 7, 5, 3, 1, 0]),
                      pitch=Env([-16, -12, -8, -6, -4, -2]), relative=True, restart=True),
        7: Instrument(wave=Env([6]), volume=Env([14, 11, 8, 6, 4, 2, 0]), arpeggio=Env([12, 0]),
                      pitch=Env([8, 4, 0, -4])),
        8: Instrument(wave=Env([1, 7, 4, 3]), volume=Env([15, 13, 11, 9, 8, 7, 8, 9], loop=4)),
        9: Instrument(wave=Env([8]), volume=Env([15, 15, 14, 14, 13])),
        10: Instrument(wave=Env([3]), volume=Env([12])),
    }


# ---- the test song -----------------------------------------------------------------------------------

def test_song(variant=SCC):
    """5 channels, 4 orders (16/32/16/24 rows), speed 6, loop to order 1 (also by B01 on order 3's last row).

    Order 0: vibrato, fine pitch, T20 (waveform restart at every period write) under vibrato, slides, a delayed note;
    the pluck released by OFF, portamento glides, a cut releasing it and OFF cutting the released note; 0xy over an
    arpeggio envelope, volume slides, Cxx, Wxx on a row without a note; drums (relative pitch kick with a multi-wave
    envelope, snare with arpeggio and absolute pitch envelopes) on SCC 4 and a bell with a multi-wave envelope on
    SCC 5, the two alternately claiming the shared wave RAM with different waves.
    Order 1 (the loop, no Fxx): T40 freezes every wave RAM across wave changes and key-ons, then T00 writes them;
    the looping/releasing pad, bass slides, a four-column cell, portamento, SCC 4 (saw) and SCC 5 (sine, Wxx
    square, the kick) claiming RAM 3 in turn.
    Order 2: F04 then F05, T02 / T01 / T00 briefly, D02 at row 12 to order 3 row 2; patterns of order 0 reused.
    Order 3: volumes, OFF cells, delays, cuts, Wxx with a note, B01 on the last row at speed 5."""
    C = Cell
    P = {}
    # Order 0 (16 rows)
    P["lead0"] = {0: C(60, 3, [("4", 0x24)]), 4: C(62, None, [("P", 0x90)]), 6: C(None, None, [("T", 0x20)]),
                  8: C(64, None, [("4", 0x30)]), 10: C(None, None, [("T", 0x00), ("1", 0x04)]),
                  12: C(65, 3, [("2", 0x02), ("G", 0x02)]), 14: C(NOTE_OFF, None, [("2", 0x00)])}
    P["bass0"] = {0: C(36, 2, [("C", 0x0C)]), 2: C(NOTE_OFF), 4: C(43, 2), 6: C(None, None, [("3", 0x20)]),
                  7: C(48), 10: C(36, None, [("3", 0x10)]), 12: C(None, None, [("3", 0x00), ("S", 0x03)]),
                  13: C(NOTE_OFF), 14: C(41, 2, [("C", 0x0F)]), 15: C(NOTE_OFF)}
    P["chord0"] = {0: C(48, 5, [("0", 0x37), ("A", 0x02)]), 4: C(None, None, [("0", 0x47)]),
                   8: C(53, 5, [("A", 0x10), ("C", 0x08)]), 12: C(None, None, [("A", 0x00), ("W", 0x07)]),
                   15: C(NOTE_OFF)}
    P["drums0"] = {0: C(36, 6), 4: C(50, 7), 8: C(36, 6), 10: C(36, 6, [("G", 0x03)]), 12: C(50, 7, [("S", 0x02)]),
                   14: C(52, 7, [("G", 0x01), ("S", 0x04)])}
    P["hat0"] = {2: C(72, 8), 6: C(76, 8, [("W", 0x05)]), 10: C(79, 8), 14: C(NOTE_OFF)}
    # Order 1 (32 rows, the loop): no Fxx, so B01 from order 3 (at speed 5) starts it at the first pass's speed.
    P["pad1"] = {0: C(60, 4), 8: C(None, None, [("T", 0x40)]), 12: C(64, 4, [("C", 0x0A)]),
                 14: C(None, None, [("T", 0x00)]), 16: C(NOTE_OFF), 20: C(67, 4, [("4", 0x46), ("P", 0x70)]),
                 28: C(NOTE_OFF), 30: C(NOTE_OFF)}
    P["bass1"] = {0: C(24, 9), 4: C(31), 8: C(24, 9, [("1", 0x08)]), 10: C(None, None, [("1", 0x00)]), 12: C(29, 9),
                  16: C(24, 9, [("2", 0x04)]), 18: C(None, None, [("2", 0x00)]), 20: C(27, 9), 24: C(24, 9),
                  28: C(31, 9, [("S", 0x04)])}
    P["lead1"] = {2: C(72, 3, [("4", 0x26)]), 6: C(74), 10: C(76, None, [("0", 0x0C)]), 14: C(NOTE_OFF),
                  18: C(79, 3, [("P", 0x88), ("C", 0x0B), ("4", 0x22), ("A", 0x01)]),
                  22: C(77, None, [("3", 0x08)]), 24: C(72), 26: C(None, None, [("3", 0x00)]), 30: C(NOTE_OFF)}
    P["sh4"] = {0: C(48, 10), 8: C(50, 10), 16: C(52, 10, [("W", 0x04)]), 24: C(53, 10), 31: C(NOTE_OFF)}
    P["sh5"] = {4: C(60, 1), 12: C(62, 1, [("W", 0x02)]), 20: C(64, 1), 28: C(65, 6)}
    # Order 2 (16 rows): speed changes, test register frequency modes, break to order 3 row 2
    P["o2lead"] = {0: C(60, 3, [("F", 0x04), ("4", 0x48)]), 4: C(62, None, [("1", 0x20)]),
                   8: C(64, None, [("F", 0x05)]), 12: C(65, None, [("D", 0x02), ("1", 0x00)])}
    P["o2bell"] = {0: C(72, 8, [("T", 0x02)]), 2: C(None, None, [("T", 0x01)]), 3: C(None, None, [("T", 0x00)]),
                   6: C(76, 8, [("P", 0x90), ("A", 0x01)]), 11: C(NOTE_OFF)}
    # Order 3 (24 rows): entered at row 2, B01 on the last row
    P["o3a"] = {0: C(55, 4, [("C", 0x08)]), 2: C(57, None, [("C", 0x0F), ("G", 0x01)]), 6: C(NOTE_OFF), 8: C(59, 2),
                16: C(60, 3, [("4", 0x13)]), 23: C(NOTE_OFF, None, [("B", 0x01)])}
    P["o3b"] = {2: C(64, 5, [("0", 0x47)]), 9: C(NOTE_OFF), 12: C(62, 7), 20: C(60, 1, [("P", 0x40)])}
    P["o3c"] = {0: C(36, 6), 6: C(36, 6), 12: C(43, 6), 18: C(36, 6, [("S", 0x01)])}
    P["o3d"] = {4: C(84, 7, [("W", 0x06)]), 10: C(NOTE_OFF), 14: C(86, 7)}
    orders = [
        Order(16, ["lead0", "bass0", "chord0", "drums0", "hat0"]),
        Order(32, ["pad1", "bass1", "lead1", "sh4", "sh5"]),
        Order(16, ["o2lead", "bass0", "o2bell", "drums0", "hat0"]),
        Order(24, ["o3a", "o3b", None, "o3c", "o3d"]),
    ]
    return Song(speed=6, orders=orders, patterns=P, instruments=test_instruments(), waves=test_waves(), loop=1,
                variant=variant)


def plus_song():
    """The test song on the SCC+ (five wave RAMs)."""
    return test_song(PLUS)


def edited_song():
    """One note edited: order 1 row 16, channel 1 (SCC 2): C-2 -> D-2."""
    s = test_song()
    s.patterns["bass1"] = dict(s.patterns["bass1"])
    s.patterns["bass1"][16] = Cell(26, 9, [("2", 0x04)])
    return s


def tempo_song():
    """One Fxx added: order 1 row 4, channel 4 (SCC 5): F09 (the row and the rest of the order at 9 ticks, was 6)."""
    s = test_song()
    s.patterns["sh5"] = dict(s.patterns["sh5"])
    s.patterns["sh5"][4] = Cell(60, 1, [("F", 0x09)])
    return s


def short_song():
    """Only the first two orders (a live edit that removes the playing order), loop order 1."""
    s = test_song()
    s.orders = s.orders[:2]
    s.loop = 1
    return s


def x2_song():
    """The test song at multispeed 2 (120 ticks per second)."""
    s = test_song()
    s.multispeed = 2
    return s


WAVE_EDIT_NUMBER = 2


def wave_edit_song():
    """Wave 2 (the square of slot 03, played by channel 0 from order 0 row 0) inverted: -100 x 16, then +100 x 16."""
    s = test_song()
    s.waves = dict(s.waves)
    s.waves[WAVE_EDIT_NUMBER] = [-100] * 16 + [100] * 16
    return s


def tone_song(variant=SCC, ticks=120):
    """A-4 held on SCC 1 with the sine slot for `ticks` ticks (one order of one row), for pitch measurements."""
    return Song(speed=ticks, orders=[Order(1, ["tone", None, None, None, None])], patterns={"tone": {0: Cell(57, 1)}},
                instruments={1: Instrument(wave=Env([1]))}, waves={1: sine_wave()}, variant=variant)
