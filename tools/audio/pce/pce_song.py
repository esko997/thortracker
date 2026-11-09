#!/usr/bin/env python3
"""PC Engine image builder (the layout of docs/AUDIO_ENGINE.md "PC Engine: image layout" and its lowering rules, as
the Kotlin lowering must produce it) and the hand-built test songs of the native host test.

A song here: Song(speed, orders=[Order(rows, [pattern name per channel])], patterns={name: {row: Cell}},
instruments={slot: Instrument}, waves=[[32 values]], samples=[[frames]], loop, multispeed, and the chip's shared start
state master, lfo_freq, lfo_ctrl, timer). build(song) returns the 131072-byte image; build(song, relayout=True) lays
the same song out differently (patterns in reverse order behind a 37-byte gap, so every pattern, envelope and sample
offset changes) for the live-edit test.

Lowering rules (the Kotlin lowering's): patterns first, byte-identical (order, channel) encodings stored once in
first-use order (orders, then channels); then each filled slot's envelopes in slot order (volume, arpeggio, pitch,
wave; the wave envelope only on tone instruments), byte-identical value sequences stored once (first use); then the
samples in sample order, identical data stored once (every entry written). The instrument count is the highest filled
slot; W and S are the song's wave and sample counts; kinds' unused record fields are 0."""
import math
import struct
from dataclasses import dataclass, field

IMAGE_SIZE = 131072
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_WAVES, OFF_SAMPLES, OFF_DATA = 64, 320, 3392, 5440, 7488, 8000
INSTRUMENT_SIZE = 32
CHANNELS = 6
NOTE_OFF = 0xFE
ENV_NONE = 0xFF
KINDS = {"tone": 0, "noise": 1, "sample": 2}
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}

# floor(127 sin(2 pi i / 64) + 0.5): the player's vibrato table, used here to synthesize waves and samples.
SINE = tuple(math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64))


@dataclass
class Env:
    """An envelope as Thor Tracker's PceEnvelope: values, loop index (-1 none), release index (-1 none)."""
    values: list = field(default_factory=list)
    loop: int = -1
    release: int = -1


@dataclass
class Instrument:
    """Defaults as Thor Tracker's PceInstrument."""
    kind: str = "tone"
    wave: int = 0
    volume: Env = field(default_factory=lambda: Env([31]))
    arpeggio: Env = field(default_factory=Env)
    pitch: Env = field(default_factory=Env)
    relative: bool = False
    waves: Env = field(default_factory=Env)
    sample: int = 0
    root: int = 48
    loop: bool = False
    loop_start: int = 0

    def envelopes(self):
        """The four envelope slots as written: (volume, arpeggio, pitch, wave); the wave envelope only on tone."""
        return [self.volume, self.arpeggio, self.pitch, self.waves if self.kind == "tone" else Env()]

    def record(self, offsets):
        """The 32-byte record; offsets[k] = data offset of envelope k's values (ignored when empty)."""
        rec = bytearray(INSTRUMENT_SIZE)
        kind = KINDS[self.kind]
        rec[0] = 0x80 | kind | (0x04 if self.relative else 0)
        if self.kind == "tone":
            rec[1] = self.wave
        if self.kind == "sample":
            rec[2], rec[3], rec[4] = self.sample, self.root, 1 if self.loop else 0
            struct.pack_into("<H", rec, 6, self.loop_start if self.loop else 0)
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
    note: int = None          # 9..95, NOTE_OFF
    inst: int = None
    fx: list = field(default_factory=list)  # [(letter, param)], up to 4


@dataclass
class Order:
    rows: int
    patterns: list            # 6 pattern names (None = empty)


@dataclass
class Song:
    speed: int
    orders: list
    patterns: dict
    instruments: dict
    waves: list
    samples: list = field(default_factory=list)
    loop: int = 0
    multispeed: int = 1
    master: int = 0xFF
    lfo_freq: int = 0
    lfo_ctrl: int = 0
    timer: int = 0


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
    img[0:4] = b"TTPC"
    img[4], img[5], img[6] = 1, song.multispeed, song.speed
    img[7] = max(song.instruments) if song.instruments else 0
    struct.pack_into("<HH", img, 8, len(song.orders), song.loop)
    img[12], img[13] = len(song.waves), len(song.samples)
    img[14], img[15], img[16], img[17] = song.master, song.lfo_freq, song.lfo_ctrl, song.timer
    for w, wave in enumerate(song.waves):
        assert len(wave) == 32
        img[OFF_WAVES + 32 * w: OFF_WAVES + 32 * (w + 1)] = bytes(wave)
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
        data.extend(b"\x80" * 37)  # a gap of unused bytes moves every pattern, envelope and sample
    for blob in (list(reversed(blobs)) if relayout else blobs):
        where[blob] = len(data)
        assert len(data) <= 0xFFFF, "patterns and envelopes pass the 64 KB their offsets reach"
        data.extend(blob)
    for o, row in enumerate(uses):
        for c, blob in enumerate(row):
            struct.pack_into("<H", img, OFF_ORDERS + 2 * (CHANNELS * o + c), where[blob])
    # envelopes: slot order, then volume, arpeggio, pitch, wave; identical value bytes stored once
    envs = {}
    for slot in sorted(song.instruments):
        offsets = []
        for e in song.instruments[slot].envelopes():
            if e.values:
                key = env_bytes(e)
                if key not in envs:
                    envs[key] = len(data)
                    assert len(data) <= 0xFFFF, "patterns and envelopes pass the 64 KB their offsets reach"
                    data.extend(key)
                offsets.append(envs[key])
            else:
                offsets.append(0)
        img[OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1): OFF_INSTRUMENTS + INSTRUMENT_SIZE * slot] = \
            song.instruments[slot].record(offsets)
    # samples: sample order, identical data stored once
    stored = {}
    for s, frames in enumerate(song.samples):
        key = bytes(frames)
        if key not in stored:
            stored[key] = len(data)
            data.extend(key)
        struct.pack_into("<II", img, OFF_SAMPLES + 8 * s, stored[key], len(frames))
    assert len(data) <= IMAGE_SIZE - OFF_DATA
    struct.pack_into("<I", img, 20, len(data))
    img[OFF_DATA: OFF_DATA + len(data)] = data
    return bytes(img)


# ---- waves and samples (integer formulas; PceGoldenSongs.kt computes the same numbers) ------------------------------

def test_waves():
    """0 sine, 1 square, 2 saw, 3 triangle, 4 25% pulse."""
    return [
        [(SINE[2 * i] + 127) * 31 // 254 for i in range(32)],
        [31] * 16 + [0] * 16,
        list(range(32)),
        [2 * i if i < 16 else 62 - 2 * i for i in range(32)],
        [31] * 8 + [0] * 24,
    ]


def kick_sample():
    """600 frames: a sine whose phase rate falls from 4 to 2 steps per frame, decaying to the midpoint 15."""
    n = 600
    out = []
    for i in range(n):
        phase = i * 4 * (n - i // 2) // n % 64
        amp = n - i
        out.append(((SINE[phase] + 127) * amp + 127 * (n - amp)) * 31 // (254 * n))
    return out


def buzz_sample():
    """256 frames of a buzzy saw (looped from frame 64)."""
    return [(i * 3) % 28 + (i // 8) % 4 for i in range(256)]


# ---- the test song --------------------------------------------------------------------------------------------------

def test_instruments():
    I = {}
    # 1: plain sine (wave 0), constant volume
    I[1] = Instrument()
    # 2: saw lead: volume decay looping its tail, absolute pitch envelope looping a wobble
    I[2] = Instrument(wave=2, volume=Env([31, 30, 28, 26, 24, 22], loop=4), pitch=Env([0, 4, 8, 4, 0, -4, -8, -4], loop=0))
    # 3: triangle pad: volume swell held at index 3, released from 4; looping octave arpeggio envelope
    I[3] = Instrument(wave=3, volume=Env([8, 14, 20, 26, 24, 18, 12, 6, 0], loop=3, release=4),
                      arpeggio=Env([0, 0, 12, 12], loop=0))
    # 4: wave-envelope tone (ignores Wxx): square, square, pulse, pulse, saw (loop from 2)
    I[4] = Instrument(wave=1, volume=Env([31, 28, 25, 22, 20]), waves=Env([1, 1, 4, 4, 2], loop=2))
    # 5: relative pitch: wobble while held, falls after the release; volume released from 4
    I[5] = Instrument(wave=1, volume=Env([31, 30, 29, 28, 20, 12, 4, 0], loop=3, release=4),
                      pitch=Env([4, 2, 2, -2, -2, -16, -16], loop=1, release=5), relative=True)
    # 6: noise hat with a falling noise-rate arpeggio envelope
    I[6] = Instrument(kind="noise", volume=Env([31, 24, 16, 10, 6, 3, 0]), arpeggio=Env([0, -3, 2]))
    # 7: noise snare held at index 2, released from 3
    I[7] = Instrument(kind="noise", volume=Env([28, 27, 26, 20, 12, 6, 0], loop=2, release=3))
    # 8: one-shot sample (kick), root C-4
    I[8] = Instrument(kind="sample", sample=0, root=48)
    # 9: looping sample (buzz), root A-4, loop from frame 64
    I[9] = Instrument(kind="sample", sample=1, root=57, loop=True, loop_start=64)
    return I


def test_song():
    """6 channels, 4 orders (16/32/16/24 rows), loop to order 1 (also by B01 on order 3's last row).

    Exercises every effect (0 1 2 3 4 8 A B C D F G L M P S T W X), up to four effect columns, tone/noise/sample
    kinds, volume/arpeggio/pitch/wave envelopes with loops and release points, a relative pitch envelope, OFF with
    and without release, delays (one never due: G07 at speed 6) and cuts, empty runs, patterns used in two orders,
    a one-shot (PSG 5) and a looping (PSG 6) DDA sample with portamento and DDA rate changes (T00..T0F), noise and
    tone notes on the noise channels, the LFO (PSG 2 as the modulator at volume 0 with M01/M02/M03/M83/M81),
    master balance changes and channel pans."""
    C = Cell
    P = {}
    # Order 0 (16 rows)
    P["lead0"] = {0: C(60, 2, [("0", 0x37)]), 4: C(None, None, [("4", 0x34)]), 6: C(62, None, [("0", 0x47), ("P", 0x88)]),
                  10: C(64, None, [("W", 0x03), ("C", 0x18), ("8", 0xF8), ("A", 0x02)]), 13: C(NOTE_OFF),
                  14: C(65, 2, [("4", 0x30)])}
    P["bass0"] = {0: C(36, 5, [("1", 0x08)]), 3: C(None, None, [("1", 0x00)]), 4: C(36, None, [("2", 0x10)]),
                  6: C(None, None, [("2", 0x00)]), 8: C(43, None, [("P", 0x70)]), 12: C(41), 15: C(NOTE_OFF)}
    P["pad0"] = {0: C(60, 3), 8: C(57, None, [("8", 0x5F)]), 15: C(NOTE_OFF)}
    P["wave0"] = {0: C(64, 4, [("4", 0x22)]), 4: C(None, None, [("W", 0x00)]), 6: C(67, None, [("C", 0x14)]),
                  10: C(65, None, [("A", 0x04)]), 14: C(NOTE_OFF, None, [("A", 0x00)])}
    P["drums0"] = {0: C(48, 8), 4: C(70, 6), 6: C(72, 6, [("S", 0x02)]), 8: C(48, 8, [("G", 0x02)]), 12: C(70, 7),
                   14: C(None, None, [("8", 0xA5)]), 15: C(NOTE_OFF)}
    P["loop0"] = {0: C(57, 9), 6: C(60, None, [("3", 0x20)]), 10: C(None, None, [("3", 0x00)]), 12: C(NOTE_OFF)}
    # Order 1 (32 rows, the loop). No Fxx: the loop (B01 from order 3 at speed 5) starts at the first pass's speed.
    P["lead1"] = {0: C(57, 1, [("M", 0x01), ("L", 0x10)]), 8: C(None, None, [("M", 0x02)]),
                  16: C(None, None, [("M", 0x83)]), 20: C(None, None, [("M", 0x03)]),
                  24: C(59, None, [("M", 0x00), ("X", 0xFC)]), 31: C(NOTE_OFF, None, [("X", 0xEE)])}
    P["lfo1"] = {0: C(9, 1, [("C", 0x00)]), 16: C(21), 28: C(NOTE_OFF)}
    P["pad1"] = {2: C(55, 3, [("0", 0x47)]), 10: C(52, None, [("0", 0x38)]), 18: C(50), 26: C(NOTE_OFF)}
    P["bend1"] = {0: C(48, 5), 8: C(NOTE_OFF), 12: C(50, 5, [("P", 0x90)]), 20: C(NOTE_OFF), 24: C(53, 4, [("G", 0x07)])}
    P["kick1"] = {0: C(48, 8), 8: C(50, 8, [("T", 0x03)]), 16: C(48, 8, [("T", 0x0F)]), 24: C(45, 8, [("T", 0x07)])}
    P["noise1"] = {0: C(66, 6, [("8", 0xFF)]), 4: C(68, 7), 8: C(NOTE_OFF), 12: C(57, 9), 20: C(NOTE_OFF), 22: C(66, 6),
                   26: C(70, 6, [("S", 0x01)]), 28: C(64, 1)}
    # Order 2 (16 rows): speed changes, the fastest DDA rate, pattern break at row 12 -> order 3 row 2
    P["o2lead"] = {0: C(60, 2, [("F", 0x04), ("4", 0x48)]), 4: C(62, None, [("1", 0x20)]), 8: C(64, None, [("F", 0x05)]),
                   12: C(65, None, [("D", 0x02), ("1", 0x00)])}
    P["o2bell"] = {0: C(72, 4, [("8", 0xF0)]), 6: C(76, 4, [("8", 0x0F), ("P", 0x90)]), 11: C(NOTE_OFF)}
    P["o2t"] = {0: C(None, None, [("T", 0x00)]), 2: C(57, 9), 8: C(None, None, [("T", 0x07)]), 10: C(NOTE_OFF)}
    # Order 3 (24 rows): volumes, OFF cells, master balance, LFO halt, B01 on the last row
    P["o3a"] = {0: C(55, 3, [("C", 0x10)]), 2: C(57, None, [("C", 0x1F), ("G", 0x01)]), 6: C(NOTE_OFF), 8: C(59, 2),
                16: C(60, 2, [("4", 0x13)]), 23: C(NOTE_OFF, None, [("B", 0x01)])}
    P["o3b"] = {2: C(64, 5, [("X", 0xF7)]), 9: C(NOTE_OFF), 12: C(62, 1, [("W", 0x04)]), 20: C(60, 4, [("P", 0x40)])}
    P["o3c"] = {0: C(48, 8, [("8", 0xF0)]), 6: C(48, 8, [("8", 0x0F)]), 12: C(55, 8), 18: C(48, 8, [("8", 0xFF), ("S", 0x01)])}
    P["o3n"] = {2: C(67, 7, [("L", 0x40), ("M", 0x81)]), 10: C(NOTE_OFF), 14: C(71, 6, [("M", 0x00)])}

    orders = [
        Order(16, ["lead0", "bass0", "pad0", "wave0", "drums0", "loop0"]),
        Order(32, ["lead1", "lfo1", "pad1", "bend1", "kick1", "noise1"]),
        Order(16, ["o2lead", "bass0", "o2bell", None, "drums0", "o2t"]),
        Order(24, ["o3a", "o3b", None, None, "o3c", "o3n"]),
    ]
    return Song(speed=6, orders=orders, patterns=P, instruments=test_instruments(), waves=test_waves(),
                samples=[kick_sample(), buzz_sample()], loop=1, master=0xEE, lfo_freq=0x20, lfo_ctrl=0, timer=7)


def edited_song():
    """The test song with one note edited in order 2 (row 6 of PSG 3: 76 -> 74)."""
    s = test_song()
    s.patterns["o2bell"] = dict(s.patterns["o2bell"])
    s.patterns["o2bell"][6] = Cell(74, 4, [("8", 0x0F), ("P", 0x90)])
    return s


def tempo_song():
    """The test song with an Fxx added ahead in the song (F09 on order 1's row 4 of PSG 6)."""
    s = test_song()
    s.patterns["noise1"] = dict(s.patterns["noise1"])
    s.patterns["noise1"][4] = Cell(68, 7, [("F", 0x09)])
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


def shared_song():
    """The test song with every shared header value edited: master balance, LFO frequency and control, DDA timer."""
    s = test_song()
    s.master, s.lfo_freq, s.lfo_ctrl, s.timer = 0x9C, 0x80, 0x82, 0x10
    return s
