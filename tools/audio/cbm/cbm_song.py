#!/usr/bin/env python3
"""VIC-20 / C16-Plus/4 image builder (the layout and lowering rules of docs/AUDIO_ENGINE.md "VIC-20 and C16/Plus/4",
"Image layout", as the Kotlin lowering must produce them) and the hand-built test songs of the native host test.

A song here: Song(chip, video, speed, orders=[Order(rows, [pattern name per channel])], patterns={name: {row: Cell}},
instruments={slot: Instrument}, samples=[[frames 0..8]], loop, multispeed, volume, timer). build(song) returns the
131072-byte image.

Lowering rules (the Kotlin lowering's): patterns first, byte-identical (order, channel) encodings stored once in
first-use order (orders, then channels); then each filled slot's envelopes in slot order (volume, arpeggio, pitch,
wave), byte-identical value sequences stored once (first use); then the samples in sample order, identical frames
stored once. The instrument count is the highest filled slot, S the song's sample count; unused fields 0.

The test songs (SONGS) are original material made for the test: every lane, the shared master volume with an owner
envelope and release, slides, portamento, vibrato, fine pitch, arpeggios, delays, cuts, jumps, breaks, speed changes,
the VIC noise rates, the TED's voice-2 noise through the instrument wave, Wxx and wave envelopes, D/A samples (one-shot
and looped, pitched, with portamento) and DAC rate changes. Pure Python 3."""
import struct
from dataclasses import dataclass, field

import cbm_ref as ref

FX = ref.FX


@dataclass
class Env:
    """An envelope: values, loop index (-1 none), release index (-1 none)."""
    values: list = field(default_factory=list)
    loop: int = -1
    release: int = -1


@dataclass
class Instrument:
    kind: str = "tone"            # "tone" or "sample"
    wave: int = 1                 # tone: 1 square, 2 noise (TED); sample: ignored (0)
    volume: Env = field(default_factory=Env)
    arpeggio: Env = field(default_factory=Env)
    pitch: Env = field(default_factory=Env)
    relative: bool = False
    waves: Env = field(default_factory=Env)
    sample: int = 0
    root: int = 48
    loop: bool = False
    loop_start: int = 0

    def envelopes(self):
        return [self.volume, self.arpeggio, self.pitch, self.waves]

    def record(self, offsets):
        rec = bytearray(ref.INSTRUMENT_SIZE)
        rec[0] = 0x80 | (ref.KIND_SAMPLE if self.kind == "sample" else ref.KIND_TONE) | (0x04 if self.relative else 0)
        if self.kind == "tone":
            rec[1] = self.wave
        else:
            rec[2], rec[3], rec[4] = self.sample, self.root, 1 if self.loop else 0
            struct.pack_into("<H", rec, 6, self.loop_start if self.loop else 0)
        for k, e in enumerate(self.envelopes()):
            at = 8 + 6 * k
            if e.values:
                struct.pack_into("<HBBBB", rec, at, offsets[k], len(e.values),
                                 ref.ENV_NONE if e.loop < 0 else e.loop, ref.ENV_NONE if e.release < 0 else e.release, 0)
            else:
                struct.pack_into("<HBBBB", rec, at, 0, 0, ref.ENV_NONE, ref.ENV_NONE, 0)
        return bytes(rec)


@dataclass
class Cell:
    note: int = None              # absolute note, ref.NOTE_OFF
    inst: int = None
    fx: list = field(default_factory=list)   # [(letter, param)], up to 2


@dataclass
class Order:
    rows: int
    patterns: list                # pattern name per channel (None = empty)


@dataclass
class Song:
    chip: int
    video: int
    speed: int
    orders: list
    patterns: dict
    instruments: dict
    samples: list = field(default_factory=list)
    loop: int = 0
    multispeed: int = 1
    volume: int = 15
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
        cell = cells.get(r)
        fx = [(l, p) for l, p in (cell.fx if cell else []) if not (l == "0" and p == 0)]
        if cell is None or (cell.note is None and not cell.inst and not fx):
            empty += 1
            continue
        flush()
        mask = (1 if cell.note is not None else 0) | (2 if cell.inst else 0) | (len(fx) << 2)
        out.append(mask)
        if cell.note is not None:
            out.append(cell.note)
        if cell.inst:
            out.append(cell.inst)
        for letter, param in fx:
            out.extend((FX[letter], param))
    flush()
    return bytes(out)


def env_bytes(e):
    return bytes(v & 0xFF for v in e.values)


def build(song):
    img = bytearray(ref.IMAGE_SIZE)
    channels = ref.channels_of(song.chip)
    data = bytearray()

    def place(stored, blob):
        """Patterns share only with patterns, envelopes only with envelopes (the Kotlin lowering's rule)."""
        if blob not in stored:
            stored[blob] = len(data)
            data.extend(blob)
        return stored[blob]

    pattern_store, envelope_store = {}, {}

    empty_rows = {}
    for o, order in enumerate(song.orders):
        img[ref.OFF_ROWS + o] = order.rows & 0xFF
        for c in range(channels):
            name = order.patterns[c] if c < len(order.patterns) else None
            cells = song.patterns[name] if name else empty_rows
            off = place(pattern_store, encode_pattern(cells, order.rows))
            struct.pack_into("<H", img, ref.OFF_ORDERS + 2 * (ref.MAX_CHANNELS * o + c), off)
    records = {}
    for slot in sorted(song.instruments):
        ins = song.instruments[slot]
        offsets = [place(envelope_store, env_bytes(e)) if e.values else 0 for e in ins.envelopes()]
        records[slot] = ins.record(offsets)
    assert len(data) <= 65536, "patterns and envelopes pass the 64 KB their offsets reach"
    sample_table = []
    sample_store = {}
    for frames in song.samples:
        blob = bytes(frames)
        if blob not in sample_store:
            sample_store[blob] = len(data)
            data.extend(blob)
        sample_table.append((sample_store[blob], len(blob)))
    assert len(data) <= ref.DATA_CAPACITY
    img[0:4] = ref.MAGIC
    img[ref.H_LAYOUT] = ref.LAYOUT
    img[ref.H_CHIP] = song.chip
    img[ref.H_VIDEO] = song.video
    img[ref.H_MULTISPEED] = song.multispeed
    img[ref.H_SPEED] = song.speed
    img[ref.H_INSTRUMENTS] = max(song.instruments) if song.instruments else 0
    struct.pack_into("<HH", img, ref.H_ORDERS, len(song.orders), song.loop)
    img[ref.H_SAMPLES] = len(song.samples)
    img[ref.H_VOLUME] = song.volume
    img[ref.H_TIMER] = song.timer
    struct.pack_into("<I", img, ref.H_DATA_SIZE, len(data))
    for slot, rec in records.items():
        at = ref.OFF_INSTRUMENTS + ref.INSTRUMENT_SIZE * (slot - 1)
        img[at:at + ref.INSTRUMENT_SIZE] = rec
    for s, (off, length) in enumerate(sample_table):
        struct.pack_into("<II", img, ref.OFF_SAMPLES + ref.SAMPLE_ENTRY_SIZE * s, off, length)
    img[ref.OFF_DATA:ref.OFF_DATA + len(data)] = data
    return bytes(img)


# ---- test songs -----------------------------------------------------------------------------------------------------
def C(note=None, inst=None, *fx):
    return Cell(note, inst, list(fx))


OFF = ref.NOTE_OFF


def vic_song(video, multispeed):
    """Four orders (16, 16, 8, 12 rows), loop order 1 reached through B01; notes inside both PAL and NTSC ranges."""
    instruments = {
        # owner of the master volume: attack, sustain loop at 4, release from 5
        1: Instrument(volume=Env([15, 14, 12, 10, 9, 9, 7, 5, 3, 1, 0], loop=4, release=5)),
        # gated arpeggio pluck: no master envelope
        2: Instrument(arpeggio=Env([0, 12, 7], loop=0), waves=Env([1, 1, 1, 0])),
        # bass with a relative pitch sweep
        3: Instrument(pitch=Env([24, 12, 6, 0], loop=3), relative=True),
        # noise drum: rate arpeggio and a gate envelope with release
        4: Instrument(arpeggio=Env([30, 20, 10, 0]), waves=Env([1, 1, 1, 1, 1, 0], loop=4, release=5)),
        # absolute pitch wobble
        5: Instrument(pitch=Env([0, 16, 0, -16], loop=0)),
        # second owner: short decay (takes the master volume from slot 1)
        6: Instrument(volume=Env([15, 11, 8, 6, 4])),
    }
    patterns = {
        "bassA": {0: C(28, 3), 4: C(35), 8: C(28, None, ("1", 0x10)), 12: C(31, None, ("1", 0)), 15: C(OFF)},
        "altoA": {0: C(40, 2, ("0", 0x37)), 2: C(None, None, ("0", 0x00)), 6: C(43, 2), 10: C(47, 5, ("4", 0x46)),
                  14: C(None, None, ("4", 0x40), ("S", 2))},
        "sopA": {0: C(52, 1), 3: C(55, None, ("3", 0x20)), 6: C(57), 9: C(OFF), 12: C(60, 1, ("P", 0x90)),
                 14: C(64, None, ("G", 3))},
        "noiseA": {0: C(100, 4), 4: C(120, 4, ("0", 0x5A)), 8: C(90, 4), 10: C(OFF), 12: C(127, 4), 13: C(0, 4),
                   14: C(64, 4, ("S", 1))},
        "bassB": {0: C(24, 3, ("V", 9)), 8: C(26, 3, ("A", 0x10)), 15: C(None, None, ("A", 0))},
        "sopB": {0: C(62, 6), 4: C(64, 1, ("F", 4)), 8: C(67, 1, ("2", 0x08)), 12: C(None, None, ("2", 0)),
                 14: C(OFF, None, ("F", 6))},
        "noiseB": {0: C(110, 4, ("A", 0x02)), 6: C(40, 4), 12: C(None, None, ("A", 0)), 15: C(70, 4, ("V", 12))},
        "altoC": {0: C(37, 5, ("4", 0x23)), 4: C(None, None, ("D", 3))},
        "sopC": {0: C(45, 2, ("0", 0x47)), 2: C(48, 1, ("G", 2))},
        "bassD": {0: C(33, 3), 6: C(40, 3, ("3", 0x30)), 9: C(36, None, ("B", 1))},
        "noiseD": {0: C(80, 4, ("F", 3)), 3: C(85, 4), 6: C(95, 4, ("F", 6)), 11: C(OFF)},
    }
    orders = [Order(16, ["bassA", "altoA", "sopA", "noiseA"]),
              Order(16, ["bassB", None, "sopB", "noiseB"]),
              Order(8, [None, "altoC", "sopC", None]),
              Order(12, ["bassD", "altoA", None, "noiseD"])]
    return Song(ref.CHIP_VIC, video, 6, orders, patterns, instruments, loop=1, multispeed=multispeed, volume=12)


def kick(n):
    """A one-shot DAC kick: a falling-pitch triangle under a falling level, integer arithmetic only."""
    out, phase = [], 0
    for i in range(n):
        step = max(64, 1024 - i)          # phase step per frame (16-bit phase)
        phase = (phase + step) & 0xFFFF
        tri = phase >> 7 if phase < 0x8000 else (0xFFFF - phase) >> 7   # 0..255
        amp = max(0, n - i) * 8 // n                                      # 8 .. 0
        out.append(4 + ((tri - 128) * amp) // 256 if amp else 4)
    return [max(0, min(8, v)) for v in out]


def buzz(n):
    """A looped DAC tone: a 32-frame ramp over the nine levels."""
    return [(i % 32) * 9 // 32 for i in range(n)]


def ted_song(video, multispeed):
    """Three orders (16, 16, 12 rows), loop order 1 reached through B01."""
    instruments = {
        1: Instrument(volume=Env([8, 8, 7, 6, 5, 5, 4, 2, 0], loop=5, release=6)),
        2: Instrument(wave=2, waves=Env([2, 2, 2, 0])),                                  # noise hat (voice 2 only)
        3: Instrument(waves=Env([1, 1, 2, 2, 2, 2, 0]), pitch=Env([0, -32, -64, -96, -128])),  # square->noise drum
        4: Instrument(),                                                                 # plain square
        5: Instrument(kind="sample", sample=0, root=48),                                 # one-shot kick
        6: Instrument(kind="sample", sample=1, root=60, loop=True, loop_start=32,
                      arpeggio=Env([0, 0, 12, 12], loop=0)),                             # looped buzz
        7: Instrument(arpeggio=Env([0, 4, 7], loop=0), volume=Env([6, 5, 4, 3])),          # second owner
    }
    patterns = {
        "v1A": {0: C(57, 1), 4: C(60), 8: C(64, None, ("4", 0x34)), 12: C(OFF), 14: C(69, 7, ("P", 0x70))},
        "v2A": {0: C(80, 2), 2: C(85, 2), 4: C(45, 3), 8: C(80, 2, ("V", 6)), 10: C(48, 4, ("W", 1)),
                12: C(None, None, ("W", 2)), 14: C(None, None, ("W", 0))},
        "dacA": {0: C(48, 5), 8: C(50, 5, ("T", 4)), 12: C(60, 6, ("T", 12)), 15: C(OFF)},
        "v1B": {0: C(52, 4, ("1", 0x18)), 6: C(None, None, ("1", 0)), 8: C(64, 1, ("3", 0x40)), 10: C(55),
                15: C(OFF, None, ("F", 5))},
        "v2B": {0: C(100, 2, ("A", 0x01)), 4: C(127, 2), 8: C(70, 3, ("G", 2)), 12: C(None, None, ("A", 0)),
                13: C(90, 2, ("S", 2))},
        "dacB": {0: C(36, 6), 4: C(40, None, ("3", 0x30)), 8: C(72, 6, ("2", 0x20)), 11: C(OFF, None, ("V", 8)),
                 12: C(48, 5, ("T", 40))},
        "v1C": {0: C(33, 4, ("0", 0x37)), 6: C(108, 4), 9: C(70, 1, ("B", 1))},
        "dacC": {0: C(24, 5, ("P", 0x60)), 6: C(108, 5, ("T", 0)), 10: C(OFF, None, ("T", 12))},
    }
    orders = [Order(16, ["v1A", "v2A", "dacA"]),
              Order(16, ["v1B", "v2B", "dacB"]),
              Order(12, ["v1C", None, "dacC"])]
    return Song(ref.CHIP_TED, video, 6, orders, patterns, instruments, samples=[kick(900), buzz(96)], loop=1,
                multispeed=multispeed, volume=7, timer=12)


# name -> (song, traced ticks)
SONGS = {
    "vic_pal": (vic_song(ref.VIDEO_PAL, 1), 1500),
    "vic_ntsc": (vic_song(ref.VIDEO_NTSC, 2), 1500),
    "ted_pal": (ted_song(ref.VIDEO_PAL, 1), 600),
    "ted_ntsc": (ted_song(ref.VIDEO_NTSC, 3), 900),
}
