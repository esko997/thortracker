#!/usr/bin/env python3
"""YM2612 image builder (layout 2 of docs/AUDIO_ENGINE.md "YM2612: image layout", constants as
app/src/main/cpp/ym/ym_image.h, as the Kotlin lowering YmLowering/YmPcmLowering must produce it) and the hand-built
test songs of the native host test.

A song here: Song(speed, region, lfo, loop, fm3, samples, instruments={slot: FmInstrument | PsgInstrument |
PcmInstrument}, orders=[Order(rows, [pattern name per channel x 13])], patterns={name: {row: Cell}}). Channels 0-5 =
FM 1-6, 6-8 = PSG 1-3, 9 = Noise, 10-12 = PCM 1-3 (an Order naming fewer lanes leaves the rest empty, as the Kotlin
golden songs do). samples = [(name, rate, data)], data = the signed 8-bit source as bytes (two's complement).
build(song) returns the IMAGE_SIZE-byte image; build(song, relayout=True) lays the same song out differently
(patterns of both data regions in reverse order behind a 37-byte gap, so every pattern offset changes) for the
live-edit test.

    python3 tools/audio/ym/ym_song.py OUT.bin [test|edited|tempo|short|pal|pcm|pcm_moved|pcm_edited|relayout]"""
import math
import struct
import sys
from dataclasses import dataclass, field
from fractions import Fraction

IMAGE_SIZE = 1188864
LAYOUT = 2
H_FM3, H_PCM_VARIANTS, H_PCM_BANK_SIZE, H_PCM_DATA_SIZE = 16, 17, 18, 20
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 5440, 8000
INSTRUMENT_SIZE = 40
TONAL_SIZE = 65536
DATA_CAPACITY = TONAL_SIZE - OFF_DATA  # 57536
OFF_CH3 = 65536             # slot n: 4 x s16 at + 8 (n - 1), operators 1..4 in diagram order (FM records only)
OFF_PCM_ORDERS = 66048      # (order o, PCM channel k) at + 2 (3 o + k)
OFF_PCM_KEYS = 67584        # (slot n, note x) at + 96 (n - 1) + x: variant id, 0 unmapped (PCM records only)
PCM_KEYS = 96
OFF_PCM_VARIANTS = 73728    # id 1..123 at + 8 (id - 1): u16 start page, u16 pages, u8 flags, 3 x 0
PCM_VARIANT_SIZE = 8
PCM_MAX_VARIANTS = 123
OFF_PCM_DATA = 74752        # PCM 1-3's pattern region
PCM_DATA_CAPACITY = 65536
OFF_PCM_BANK = 140288       # compiled signed 8-bit waveforms, variant k from page start(k)
PCM_BANK_SIZE = 1048576
PCM_ALIGN = 256
PCM_BANK_PAGES = PCM_BANK_SIZE // PCM_ALIGN  # 4096
PCM_FULL_DIVIDER = 4032     # master clocks per sample of the XGM2 driver's full rate
CHANNELS = 13
TONAL_CHANNELS = 10
CH_PCM = 10
PCM_CHANNELS = 3
FM3_NORMAL, FM3_SPECIAL = 0, 1
NOTE_OFF = 0xFE
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}
MCLK = {0: 53693175, 1: 53203424}

# PCM compilation (YmPcmLowering): 2^(s/12) in Q28, Math.round of the doubles' exact products with 2^28 (ties up).
SEMITONE_Q28 = [math.floor(Fraction(r) * (1 << 28) + Fraction(1, 2)) for r in (
    1.0, 1.0594630943592953, 1.122462048309373, 1.189207115002721, 1.2599210498948732, 1.3348398541700344,
    1.4142135623730951, 1.4983070768766815, 1.5874010519681994, 1.681792830507429, 1.7817974362806785,
    1.8877486253633868)]
# Kit level 0..15 as a Q15 gain: 15 = x1, each step down 2 dB, 0 silent.
LEVEL_Q15 = [0, 1305, 1642, 2068, 2603, 3277, 4125, 5193, 6538, 8231, 10362, 13045, 16423, 20675, 26029, 32768]
PCM_PITCH = range(-48, 25)   # mapping pitch in semitones
PCM_RATE = range(1000, 48001)


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
    ch3: list = field(default_factory=lambda: [0, 0, 0, 0])  # FM 3 special mode: operators 1..4, 1/64 semitone

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
class PcmInstrument:
    level: int = 15           # 0..15: 15 plays the source as recorded, each step 2 dB quieter, 0 silent
    keys: dict = field(default_factory=dict)  # {note 0..95: (sample index, pitch -48..24 semitones)}

    @staticmethod
    def record():
        """Flags 0x82 and 39 zero bytes: the kit's keys live in the key table and the compiled bank."""
        return bytes([0x82]) + bytes(INSTRUMENT_SIZE - 1)


@dataclass
class Cell:
    note: int = None          # 0..95, NOTE_OFF
    inst: int = None
    fx: list = field(default_factory=list)  # [(letter, param)] per effect column; None or ("0", 0) = blank


@dataclass
class Order:
    rows: int
    patterns: list            # pattern name per lane (None = empty); lanes past the list's end are empty

    def __post_init__(self):
        assert len(self.patterns) <= CHANNELS
        self.patterns = list(self.patterns) + [None] * (CHANNELS - len(self.patterns))


@dataclass
class Song:
    speed: int
    orders: list
    patterns: dict
    instruments: dict
    region: int = 0           # 0 NTSC, 1 PAL
    lfo: int = 0              # register $22: 0 or $08-$0F
    loop: int = 0
    fm3: int = FM3_NORMAL     # FM 3 mode: FM3_NORMAL or FM3_SPECIAL
    samples: list = field(default_factory=list)  # PCM sources: (name, rate 1000..48000 Hz, signed 8-bit bytes)


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


def s8(values):
    """Signed 8-bit values -128..127 as bytes (two's complement), the samples' storage."""
    return bytes(v & 0xFF for v in values)


def pcm_step(rate, pitch, mclk):
    """Source samples advanced per output sample at the driver's full rate (MCLK / 4032), Q32, floored:
    rate x 2^(pitch/12) x 4032 / MCLK with the semitone in Q28 and the octave as a shift (4 + octave = 0..6)."""
    octave, s = divmod(pitch, 12)  # floorDiv / floorMod
    a = rate * SEMITONE_Q28[s] * PCM_FULL_DIVIDER
    shift = 4 + octave
    return ((a // mclk) << shift) + (((a % mclk) << shift) // mclk)


def pcm_length(size, step):
    """Output samples for a source of `size` bytes: every position j x step inside it."""
    return ((size << 32) + step - 1) // step


def pcm_waveform(data, step, length, level, padded):
    """The compiled waveform: linear interpolation between neighbouring source bytes (past the last towards 0), the
    level's Q15 gain, floor shifts, saturated; zero-padded to `padded` bytes."""
    out = bytearray(padded)
    gain = LEVEL_Q15[level]
    if gain == 0:
        return bytes(out)
    src = [(b ^ 0x80) - 0x80 for b in data]
    n, pos = len(src), 0
    for j in range(length):
        i, f = pos >> 32, (pos >> 16) & 0xFFFF
        a = src[i]
        b = src[i + 1] if i + 1 < n else 0
        v = a + (((b - a) * f) >> 16)
        out[j] = max(-128, min(127, (v * gain) >> 15)) & 0xFF
        pos += step
    return bytes(out)


def compile_pcm(song):
    """The PCM bank, as YmPcmLowering compiles it: (waveforms in id order, each padded to whole pages,
    {(slot, key): id}, pages). Every key of every PCM kit, slots ascending then keys ascending: a (sample, pitch,
    level) met before reuses its id; else the waveform is compiled (refused when it would not fit the bank, before
    deduplication), and byte-identical to an earlier one shares that id, else is appended (refused past 123)."""
    mclk = MCLK[song.region]
    waves, by_key, by_wave, ids, pages = [], {}, {}, {}, 0
    for slot in sorted(song.instruments):
        kit = song.instruments[slot]
        if not isinstance(kit, PcmInstrument):
            continue
        for key in sorted(kit.keys):
            assert 0 <= key < PCM_KEYS, "PCM key %d outside 0..95" % key
            sample, pitch = kit.keys[key]
            if not 0 <= sample < len(song.samples):
                raise ValueError("instrument %02X key %d maps missing PCM sample %d" % (slot, key, sample + 1))
            _, rate, data = song.samples[sample]
            if not data or rate not in PCM_RATE or pitch not in PCM_PITCH or not 0 <= kit.level <= 15:
                raise ValueError("instrument %02X key %d: empty sample, rate, pitch or level out of range" % (slot, key))
            k = (sample, pitch, kit.level)
            if k in by_key:
                ids[(slot, key)] = by_key[k]
                continue
            step = pcm_step(rate, pitch, mclk)
            length = pcm_length(len(data), step)
            need = (length + PCM_ALIGN - 1) // PCM_ALIGN
            if pages + need > PCM_BANK_PAGES:
                raise ValueError("the PCM kits compile to more than 1024 KB (instrument %02X key %d)" % (slot, key))
            wave = pcm_waveform(data, step, length, kit.level, need * PCM_ALIGN)
            vid = by_wave.get(wave)
            if vid is None:
                if len(waves) >= PCM_MAX_VARIANTS:
                    raise ValueError("the PCM kits need more than 123 compiled sounds (instrument %02X key %d)" % (slot, key))
                waves.append(wave)
                pages += need
                vid = by_wave[wave] = len(waves)
            by_key[k] = vid
            ids[(slot, key)] = vid
    return waves, ids, pages


def _pool(uses, relayout):
    """Lays the distinct blobs of `uses` (rows of blobs) out in first-use order; `relayout`: behind a 37-byte gap of
    unused bytes, in reverse order. Returns (data, {blob: offset})."""
    blobs = []
    for row in uses:
        for blob in row:
            if blob not in blobs:
                blobs.append(blob)
    data, where = bytearray(), {}
    if relayout:
        data.extend(b"\x80" * 37)
    for blob in (list(reversed(blobs)) if relayout else blobs):
        where[blob] = len(data)
        data.extend(blob)
    return data, where


def build(song, relayout=False):
    """The image. Tonal data region: every distinct (order, channel 0..9) pattern in first-use order (orders, then
    channels), then the PSG envelopes of slots 1..64 ascending (volume, arpeggio, pitch), each deduplicated by its
    bytes against the envelopes before it; no padding. PCM data region: every distinct (order, PCM lane) pattern in
    first-use order (orders, then lanes; a song without PCM has its empty tracks' runs there). FM 3 offsets for every
    FM slot; the compiled PCM bank (compile_pcm) with its key table and variant table (flags 0: full rate).
    `relayout` moves the patterns of both regions (a gap, patterns reversed)."""
    waves, ids, pages = compile_pcm(song)
    img = bytearray(IMAGE_SIZE)
    img[0:4] = b"TTYM"
    img[4], img[5], img[6], img[7] = LAYOUT, song.region, song.speed, song.lfo
    struct.pack_into("<HH", img, 8, len(song.orders), song.loop)
    img[12] = max(song.instruments) if song.instruments else 0
    img[H_FM3] = song.fm3
    img[H_PCM_VARIANTS] = len(waves)
    struct.pack_into("<H", img, H_PCM_BANK_SIZE, pages)

    def track(order, c):
        name = order.patterns[c]
        return encode_pattern(song.patterns[name] if name else {}, order.rows)

    tonal = [[track(order, c) for c in range(TONAL_CHANNELS)] for order in song.orders]
    pcm = [[track(order, c) for c in range(CH_PCM, CHANNELS)] for order in song.orders]
    for o, order in enumerate(song.orders):
        img[OFF_ROWS + o] = order.rows & 0xFF
    data, where = _pool(tonal, relayout)
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
            if isinstance(inst, FmInstrument):
                assert len(inst.ch3) == 4 and all(-3072 <= v <= 3072 for v in inst.ch3)
                struct.pack_into("<4h", img, OFF_CH3 + 8 * (slot - 1), *inst.ch3)
    assert len(data) <= DATA_CAPACITY
    struct.pack_into("<H", img, 14, len(data))
    img[OFF_DATA: OFF_DATA + len(data)] = data
    for o, row in enumerate(tonal):
        for c, blob in enumerate(row):
            struct.pack_into("<H", img, OFF_ORDERS + 2 * (TONAL_CHANNELS * o + c), where[blob])
    pcm_data, pcm_where = _pool(pcm, relayout)
    assert len(pcm_data) <= PCM_DATA_CAPACITY
    struct.pack_into("<I", img, H_PCM_DATA_SIZE, len(pcm_data))
    img[OFF_PCM_DATA: OFF_PCM_DATA + len(pcm_data)] = pcm_data
    for o, row in enumerate(pcm):
        for k, blob in enumerate(row):
            struct.pack_into("<H", img, OFF_PCM_ORDERS + 2 * (PCM_CHANNELS * o + k), pcm_where[blob])
    for (slot, key), vid in ids.items():
        img[OFF_PCM_KEYS + PCM_KEYS * (slot - 1) + key] = vid
    page = 0
    for i, wave in enumerate(waves):
        struct.pack_into("<HH", img, OFF_PCM_VARIANTS + PCM_VARIANT_SIZE * i, page, len(wave) // PCM_ALIGN)
        img[OFF_PCM_BANK + page * PCM_ALIGN: OFF_PCM_BANK + page * PCM_ALIGN + len(wave)] = wave
        page += len(wave) // PCM_ALIGN
    return bytes(img)


# ---- the test song ---------------------------------------------------------------------------------

def test_song():
    """10 tonal channels (PCM 1-3 empty, FM 3 normal, no PCM kits), 4 orders (16/32/16/24 rows), loop to order 1
    (also by B01 on the last row), NTSC, LFO on.

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


# ---- the PCM + FM 3 test song --------------------------------------------------------------------------

def _kick():
    """360 bytes: a triangle falling in pitch, decaying to 0."""
    out, phase = [], 0
    for i in range(360):
        phase = (phase + max(3, 24 - i // 16)) & 63
        tri = 4 * phase if phase < 32 else 4 * (63 - phase)
        out.append((tri - 62) * (360 - i) // 360)
    return s8(out)


def _noise(n, seed, power):
    """n bytes of LCG noise, decaying as ((n - i) / n)^power."""
    x, out = seed, []
    for i in range(n):
        x = (x * 1103515245 + 12345) & 0x7FFFFFFF
        out.append((((x >> 16) & 0xFF) - 128) * (n - i) ** power // n ** power)
    return s8(out)


def _pcm_samples(snare_seed=1):
    """The PCM test kit's sources: kick 11025 Hz, snare 16000 Hz (1600 bytes: 0.1 s), hat 22050 Hz, bass 8000 Hz (a
    125 Hz square fading out) and tone 11025 Hz (3600 bytes = 0.33 s of a steady 424 Hz square, 13 + 13 bytes of
    +-80: the host test measures the compiled variant's pitch by its zero crossings)."""
    bass = s8((60 if (i // 32) % 2 == 0 else -60) * min(64, 512 - i) // 64 for i in range(512))
    tone = s8(80 if (i // 13) % 2 == 0 else -80 for i in range(3600))
    return [("kick", 11025, _kick()), ("snare", 16000, _noise(1600, snare_seed, 1)), ("hat", 22050, _noise(120, 2, 2)),
            ("bass", 8000, bass), ("tone", 11025, tone)]


def pcm_song():
    """All 13 lanes' machinery: FM 3 special mode, two PCM kits, FM 6 beside the DAC. NTSC, speed 4, 3 orders
    (16/16/24 rows), loop to order 1 (by B01 on PCM 3 at the end; the first pass enters order 1 at row 2 by D02).

    - FM 3 (slot 1 offsets 0, +12, -5, +24 semitones; slot 2 -10, +1, 0 semitones and -1/64): offset notes under
      slides up and down, arpeggio, portamento and vibrato; both slots also on FM 1 / FM 6, which ignore offsets.
    - PCM kit A (slot 3, level 15: kick, kick -7, snare, hat, hat +5, bass at -12/0/+3/+7, tone at A-4) and kit B
      (slot 4, level 9: kick -12 and 0, snare +4, bass -12): pitched keys, all three lanes triggering in one tick
      and overlapping, a lane switching kits, same-note retriggers, unmapped keys (39, 43 on kit A: the sounding
      sample goes on), OFF, Gxx delays (a delayed OFF, too), Sxx cuts, G == S on one row (start then stop: net
      stop), S < G (never fires), and B, D, F, L on PCM rows.
    - FM 6 holds a note while PCM plays and on through order 2, where all PCM is stopped (row 0 OFF on every lane)
      for 96 ticks: FM 6 sounds alone for 88 of them.
    - The tone (order 1 row 2, PCM 3) plays out uninterrupted (27 ticks to the lane's next note)."""
    voice = [FmOp(mul=1, tl=0x24, d1r=4, d1l=2, rr=6), FmOp(mul=2, tl=0x08, d1r=3, d1l=2, rr=6),
             FmOp(mul=1, tl=0x28, d1r=5, d1l=3, rr=6), FmOp(mul=1, tl=0x06, d1r=2, d1l=1, rr=7)]
    bass = [FmOp(dt=1, mul=1, tl=0x22, d1r=6, d2r=1, d1l=3, rr=8), FmOp(mul=2, tl=0x2A, d1r=5, d1l=3, rr=8),
            FmOp(mul=1, tl=0x1C, d1r=4, d1l=2, rr=8), FmOp(mul=1, tl=0x00, d1r=4, d2r=0, d1l=2, rr=6)]
    I = {
        1: FmInstrument(voice, alg=4, fb=3, ch3=[0, 768, -320, 1536]),
        2: FmInstrument(bass, alg=2, fb=5, ch3=[-640, 64, 0, -1]),  # its carrier sustains: FM 6 holds
        3: PcmInstrument(15, {33: (3, -12), 35: (0, -7), 36: (0, 0), 38: (1, 0), 42: (2, 0), 44: (2, 5), 45: (3, 0),
                              48: (3, 3), 52: (3, 7), 57: (4, 0)}),
        4: PcmInstrument(9, {36: (0, -12), 38: (1, 4), 40: (3, -12), 41: (0, 0)}),
    }
    P = {}
    C = Cell
    OFF = NOTE_OFF
    # ---- Order 0 (16 rows, speed 4)
    P["fm1_a"] = {0: C(45, 1), 8: C(OFF)}
    P["fm3_a"] = {0: C(57, 1), 4: C(None, None, [("1", 0x06)]), 6: C(None, None, [("1", 0x00)]),
                  8: C(60, None, [("0", 0x37)]), 12: C(64, 2, [("2", 0x04)]), 14: C(None, None, [("2", 0x00)]),
                  15: C(OFF)}
    P["fm6_a"] = {0: C(33, 2), 15: C(OFF)}
    P["pcm1_a"] = {0: C(36, 3), 2: C(42), 4: C(38), 6: C(42), 7: C(42), 8: C(36), 9: C(39),
                   10: C(44, None, [("G", 0x02)]), 12: C(38, None, [("S", 0x01)]), 14: C(OFF),
                   15: C(42, None, [("G", 0x01), ("S", 0x01)])}
    P["pcm2_a"] = {0: C(45, 3), 3: C(48), 6: C(52, None, [("G", 0x03), ("S", 0x01)]),
                   8: C(33, None, [("L", 0x0A)]), 12: C(OFF, None, [("G", 0x02)]), 13: C(45),
                   15: C(None, None, [("D", 0x02)])}
    P["pcm3_a"] = {0: C(40, 4), 4: C(36), 5: C(38, None, [("G", 0x01)]), 8: C(41, None, [("S", 0x02)]),
                   11: C(36, 3), 12: C(36, 4), 14: C(OFF)}
    # ---- Order 1 (16 rows, the loop; F03 from row 8)
    P["fm3_b"] = {0: C(52, 1, [("3", 0x00)]), 2: C(55, None, [("3", 0x10)]), 6: C(60, None, [("4", 0x46)]),
                  8: C(57, 2, [("3", 0x00), ("0", 0x47)]), 12: C(OFF)}
    P["fm6_b"] = {0: C(36, 2), 12: C(31, 2)}
    P["pcm1_b"] = {0: C(36, 3), 2: C(42, 3), 4: C(38), 5: C(38), 6: C(42), 8: C(36),
                   10: C(44, None, [("G", 0x01)]), 12: C(38), 14: C(42, None, [("S", 0x02)])}
    P["pcm2_b"] = {2: C(45, 3), 6: C(52), 8: C(43), 10: C(33), 14: C(OFF)}
    P["pcm3_b"] = {0: C(40, 4), 2: C(57, 3), 8: C(None, None, [("F", 0x03)]), 9: C(36, 4),
                   12: C(41, None, [("G", 0x02), ("S", 0x02)])}
    # ---- Order 2 (24 rows): PCM stopped, FM 6 alone, LFO off from a PCM row, B01 on PCM 3
    P["fm3_c"] = {0: C(64, 1, [("0", 0x0C), ("1", 0x03)]), 8: C(OFF)}
    P["fm6_c"] = {22: C(OFF)}
    P["pcm1_c"] = {0: C(OFF, None, [("F", 0x04)])}
    P["pcm2_c"] = {0: C(OFF), 4: C(None, None, [("L", 0x00)])}
    P["pcm3_c"] = {0: C(OFF), 23: C(None, None, [("B", 0x01)])}

    def lanes(fm1, fm3, fm6, pcm1, pcm2, pcm3):
        return [fm1, None, fm3, None, None, fm6, None, None, None, None, pcm1, pcm2, pcm3]

    orders = [
        Order(16, lanes("fm1_a", "fm3_a", "fm6_a", "pcm1_a", "pcm2_a", "pcm3_a")),
        Order(16, lanes(None, "fm3_b", "fm6_b", "pcm1_b", "pcm2_b", "pcm3_b")),
        Order(24, lanes(None, "fm3_c", "fm6_c", "pcm1_c", "pcm2_c", "pcm3_c")),
    ]
    return Song(speed=4, orders=orders, patterns=P, instruments=I, region=0, lfo=0, loop=1, fm3=FM3_SPECIAL,
                samples=_pcm_samples())


def pcm_moved_song():
    """The PCM song with its two kits' slots swapped (kit A in slot 4, kit B in slot 3, the PCM rows renumbered):
    the same samples and music, but kit B now compiles first, so every variant's id and start page moves."""
    s = pcm_song()
    swap = {3: 4, 4: 3}
    s.instruments = {swap.get(n, n): inst for n, inst in s.instruments.items()}
    names = {order.patterns[c] for order in s.orders for c in range(CH_PCM, CHANNELS)} - {None}
    for name in names:
        s.patterns[name] = {r: Cell(c.note, swap.get(c.inst, c.inst), list(c.fx)) for r, c in s.patterns[name].items()}
    return s


def pcm_edited_song():
    """The PCM song with the snare's bytes changed (other noise; same length): its variants (kit A key 38, kit B key
    38) keep their ids and pages but not their bytes; every other variant is identical."""
    s = pcm_song()
    s.samples = _pcm_samples(snare_seed=7)
    return s


VARIANTS = {"test": test_song, "edited": edited_song, "tempo": tempo_song, "short": short_song, "pal": pal_song,
            "pcm": pcm_song, "pcm_moved": pcm_moved_song, "pcm_edited": pcm_edited_song}


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
