#!/usr/bin/env python3
"""Reference builder of WonderSwan images (docs/AUDIO_ENGINE.md, "WonderSwan": image layout and "What the lowering
writes"), written independently of the Kotlin lowering (core backend/WsLowering.kt): build() turns a small song
description into the 131072-byte image the lowering must produce for the same song (WsGoldenTest compares them through
ws_golden.py), and the reference songs below are the host test's fixtures (prepare_ws.py).

Lowering rules mirrored (agreed with the Kotlin slice):
  - data region: every (order, lane) pattern encoding, byte-identical ones stored once, in first-use order (orders,
    then lanes); then the envelopes of each slot in slot order (volume, arpeggio, pitch, wave), one table shared by all
    kinds, identical bytes stored once; then the unsigned sample bytes in sample order (identical data once); then the
    signed copies (byte XOR $80) of the samples a DMA instrument plays (Color only), in sample order, identical copies
    once (never shared with unsigned bytes).
  - a pattern or envelope that would START beyond data offset 65535 is refused.
  - instrument record: flags $80 | kind | relative ($04, non-DMA kinds only) | DMA ($08) | LFSR reset ($10, noise) |
    backwards ($20, DMA samples); byte 1 wave / tap / sample; byte 2 CPU root / DMA rate; byte 3 loop (samples);
    u16 loop start only for CPU-fed looping samples; sweep value and time on every tone; envelopes: volume always,
    arpeggio and pitch except on DMA samples, the wave slot = a tone's wave sequence / a noise's tap sequence, none on
    samples; an empty envelope = 00 00 00 FF FF 00.
Pure Python 3."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ws_ref as R  # noqa: E402

OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS = R.OFF_ROWS, R.OFF_ORDERS, R.OFF_INSTRUMENTS
OFF_WAVES, OFF_SAMPLES, OFF_DATA = R.OFF_WAVES, R.OFF_SAMPLES, R.OFF_DATA
INSTRUMENT_SIZE = R.INSTRUMENT_SIZE

TONE, NOISE, SAMPLE = R.KIND_TONE, R.KIND_NOISE, R.KIND_SAMPLE
OFF = R.NOTE_OFF
FX = R.FX


class Env:
    """An envelope: values, loop index or None, release index or None."""

    def __init__(self, values, loop=None, release=None):
        self.values, self.loop, self.release = list(values), loop, release


class Instrument:
    def __init__(self, kind, source=0, root=57, rate=0, loop=False, loop_start=0, sweep=0, sweep_time=0,
                 relative=False, dma=False, lfsr_reset=False, reverse=False, volume=None, arpeggio=None, pitch=None,
                 wave=None):
        self.kind, self.source, self.root, self.rate = kind, source, root, rate
        self.loop, self.loop_start, self.sweep, self.sweep_time = loop, loop_start, sweep, sweep_time
        self.relative, self.dma, self.lfsr_reset, self.reverse = relative, dma, lfsr_reset, reverse
        self.volume, self.arpeggio, self.pitch, self.wave = volume, arpeggio, pitch, wave


class Sample:
    def __init__(self, data, stereo=False):
        self.data, self.stereo = bytes(data), stereo

    @property
    def frames(self):
        return len(self.data) // (2 if self.stereo else 1)


class Song:
    """orders: list of (rows, [lane rows]) where lane rows is a list of `rows` cells (note, instrument, [(letter,
    param), ...]) or None for an empty row; instruments: {slot: Instrument}."""

    def __init__(self, model=R.MODEL_MONO, speed=6, timer=1, shift=0, output=R.OUTPUT_HEADPHONES, loop=0):
        self.model, self.speed, self.timer, self.shift, self.output, self.loop = model, speed, timer, shift, output, loop
        self.orders = []
        self.instruments = {}
        self.waves = []
        self.samples = []

    @property
    def lanes(self):
        return R.CHANNELS if self.model == R.MODEL_COLOR else R.CHANNELS_MONO

    def order(self, rows=16):
        """Appends an order of empty rows; returns its lanes' row lists."""
        lanes = [[None] * rows for _ in range(self.lanes)]
        self.orders.append((rows, lanes))
        return lanes


def cell(note=0, instrument=0, *effects):
    """A row cell: note (0 none, 'OFF' or an absolute note), instrument slot, effects as 'C0F'-style strings or
    (letter, param) pairs."""
    fx = []
    for e in effects:
        if isinstance(e, str):
            fx.append((e[0], int(e[1:], 16)))
        else:
            fx.append(e)
    return (OFF if note == "OFF" else note, instrument, fx)


def encode_rows(rows, meta=None, base=0):
    """The pattern bytes of one (order, lane): runs of empty rows, else mask/note/instrument/effects. `meta` (if given)
    collects absolute data offsets of the first note, instrument and each effect letter's code/param bytes."""
    out = bytearray()
    empty = 0

    def flush():
        nonlocal empty
        while empty:
            k = min(empty, 128)
            out.append(0x80 | (k - 1))
            empty -= k

    for c in rows:
        if c is None:
            empty += 1
            continue
        note, inst, fx = c
        fx = [(FX[l] if isinstance(l, str) else l, p) for l, p in fx if not (FX.get(l, l) == 0 and p == 0)]
        if not note and not inst and not fx:
            empty += 1
            continue
        flush()
        out.append((1 if note else 0) | (2 if inst else 0) | len(fx) << 2)
        if note:
            if meta is not None:
                meta.setdefault("note", base + len(out))
            out.append(note)
        if inst:
            if meta is not None:
                meta.setdefault("instrument", base + len(out))
            out.append(inst)
        for code, p in fx:
            if meta is not None:
                meta.setdefault("code_" + R.LETTERS[code], base + len(out))
                meta.setdefault("param_" + R.LETTERS[code], base + len(out) + 1)
            out += bytes((code, p & 0xFF))
    flush()
    return bytes(out)


def build(song, meta=None):
    """The image bytes of `song` (131072). `meta`, if a dict, receives data-region offsets for the loader tests."""
    if meta is None:
        meta = {}
    data = bytearray()
    lanes = song.lanes
    pattern_at = {}
    table = [[0] * R.CHANNELS for _ in song.orders]
    for o, (rows, lane_rows) in enumerate(song.orders):
        for c in range(lanes):
            enc = encode_rows(lane_rows[c])
            if enc not in pattern_at:
                if len(data) > 65535:
                    raise ValueError("patterns and envelopes pass the 64 KB their offsets reach")
                pattern_at[enc] = len(data)
                encode_rows(lane_rows[c], meta.setdefault("rows", {}), len(data))
                data += enc
            table[o][c] = pattern_at[enc]

    env_at = {}

    def place_env(values):
        b = bytes(v & 0xFF for v in values)
        if b not in env_at:
            if len(data) > 65535:
                raise ValueError("patterns and envelopes pass the 64 KB their offsets reach")
            env_at[b] = len(data)
            data.extend(b)
        return env_at[b]

    slots = sorted(song.instruments)
    records = {}
    for slot in slots:
        ins = song.instruments[slot]
        dma = ins.kind == SAMPLE and ins.dma
        envs = [ins.volume,
                None if dma else ins.arpeggio,
                None if dma else ins.pitch,
                ins.wave if ins.kind in (TONE, NOISE) else None]
        rec = bytearray(INSTRUMENT_SIZE)
        flags = 0x80 | ins.kind
        if ins.relative and not dma:
            flags |= R.FLAG_RELATIVE
        if dma:
            flags |= R.FLAG_DMA
        if ins.kind == NOISE and ins.lfsr_reset:
            flags |= R.FLAG_LFSR_RESET
        if dma and ins.reverse:
            flags |= R.FLAG_REVERSE
        rec[R.I_FLAGS] = flags
        rec[R.I_SOURCE] = ins.source
        if ins.kind == SAMPLE:
            rec[R.I_ROOT] = ins.rate if dma else ins.root
            rec[R.I_LOOP] = 1 if ins.loop else 0
            if ins.loop and not dma:
                rec[R.I_LOOP_START:R.I_LOOP_START + 2] = ins.loop_start.to_bytes(2, "little")
        if ins.kind == TONE:
            rec[R.I_SWEEP] = ins.sweep & 0xFF
            rec[R.I_SWEEP_TIME] = ins.sweep_time
        for k, e in enumerate(envs):
            at = R.I_ENVELOPES + R.ENVELOPE_BYTES * k
            if e is None or not e.values:
                rec[at:at + 6] = bytes((0, 0, 0, 0xFF, 0xFF, 0))
                continue
            off = place_env(e.values)
            meta.setdefault("env", {}).setdefault((slot, k), off)
            rec[at:at + 6] = bytes((off & 0xFF, off >> 8, len(e.values), 0xFF if e.loop is None else e.loop,
                                    0xFF if e.release is None else e.release, 0))
        records[slot] = rec

    sample_at, entries = {}, []
    for s in song.samples:
        if s.data not in sample_at:
            sample_at[s.data] = len(data)
            data += s.data
        entries.append([sample_at[s.data], s.frames, 0, 1 if s.stereo else 0])
    if song.model == R.MODEL_COLOR:
        signed_at = {}
        used = {ins.source for ins in song.instruments.values() if ins.kind == SAMPLE and ins.dma}
        for i, s in enumerate(song.samples):
            if i not in used:
                continue
            signed = bytes(b ^ 0x80 for b in s.data)
            if signed not in signed_at:
                signed_at[signed] = len(data)
                data += signed
            entries[i][2] = signed_at[signed]
            entries[i][3] |= R.SAMPLE_SIGNED
    if len(data) > R.DATA_CAPACITY:
        raise ValueError("the song needs %d bytes of data; the image holds %d" % (len(data), R.DATA_CAPACITY))

    img = bytearray(R.IMAGE_SIZE)
    img[0:4] = R.MAGIC
    img[R.H_LAYOUT] = R.LAYOUT
    img[R.H_MODEL] = song.model
    img[R.H_SPEED] = song.speed
    img[R.H_INSTRUMENTS] = slots[-1] if slots else 0
    img[R.H_ORDERS:R.H_ORDERS + 2] = len(song.orders).to_bytes(2, "little")
    img[R.H_LOOP:R.H_LOOP + 2] = song.loop.to_bytes(2, "little")
    img[R.H_WAVES] = len(song.waves)
    img[R.H_SAMPLES] = len(song.samples)
    img[R.H_TIMER] = song.timer
    img[R.H_SHIFT] = song.shift
    img[R.H_OUTPUT] = song.output
    img[R.H_DATA_SIZE:R.H_DATA_SIZE + 4] = len(data).to_bytes(4, "little")
    for o, (rows, _) in enumerate(song.orders):
        img[OFF_ROWS + o] = rows & 0xFF
        for c in range(R.CHANNELS):
            at = OFF_ORDERS + 2 * (R.CHANNELS * o + c)
            img[at:at + 2] = table[o][c].to_bytes(2, "little")
    for slot, rec in records.items():
        at = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        img[at:at + INSTRUMENT_SIZE] = rec
    for w, wave in enumerate(song.waves):
        assert len(wave) == R.WAVE_SIZE and all(0 <= v <= R.WAVE_MAX for v in wave)
        img[OFF_WAVES + R.WAVE_SIZE * w:OFF_WAVES + R.WAVE_SIZE * (w + 1)] = bytes(wave)
    for i, (off, frames, soff, flags) in enumerate(entries):
        at = OFF_SAMPLES + R.SAMPLE_ENTRY_SIZE * i
        img[at:at + 4] = off.to_bytes(4, "little")
        img[at + 4:at + 8] = frames.to_bytes(4, "little")
        img[at + 8:at + 12] = soff.to_bytes(4, "little")
        img[at + 12] = flags
    img[OFF_DATA:OFF_DATA + len(data)] = data
    meta["data_size"] = len(data)
    return bytes(img)


# ---- waves and samples (integer arithmetic, deterministic) ----------------------------------------------------------

def wave_square(duty=16):
    return [15 if i < duty else 0 for i in range(32)]


def wave_saw():
    return [i // 2 for i in range(32)]


def wave_triangle():
    return [min(i, 31 - i) for i in range(32)]


def wave_sine():
    return [int(7.5 + 7.5 * math.sin(2 * math.pi * i / 32) + 0.5) for i in range(32)]


def lcg_bytes(n, seed, depth=255):
    out, x = [], seed
    for _ in range(n):
        x = (x * 1103515245 + 12345) & 0x7FFFFFFF
        out.append(128 + ((x >> 16) % (2 * depth + 1)) - depth if depth < 128 else (x >> 16) & 0xFF)
    return out


def kick(n=200):
    """A falling-pitch decaying thump, unsigned 8-bit around 128 (integer phase steps)."""
    out, phase = [], 0
    for i in range(n):
        amp = 120 * (n - i) // n
        step = 2600 - 12 * i if 2600 - 12 * i > 300 else 300
        phase = (phase + step) & 0xFFFF
        tri = (phase >> 8) if phase < 0x8000 else 255 - (phase >> 8)  # 0..127..0
        out.append(128 + (tri * 2 - 128) * amp // 128)
    return bytes(out)


def snare(n=120):
    noise = lcg_bytes(n, 1234, 100)
    return bytes(128 + (v - 128) * (n - i) // n for i, v in enumerate(noise))


def loop_tone(n=64):
    return bytes(128 + (60 if (i // 8) % 2 else -60) for i in range(n))


def stereo_pad(frames=96):
    out = bytearray()
    for i in range(frames):
        out.append(128 + (50 if (i // 12) % 2 else -50))   # left: square
        out.append(128 + ((i % 24) * 100 // 24 - 50))      # right: saw
    return bytes(out)


# ---- the host test's reference songs --------------------------------------------------------------------------------

def _common_waves():
    return [wave_square(), wave_saw(), wave_triangle(), wave_sine(), wave_square(8)]


def ws_song():
    """Original model: every lane kind and every effect the original model has, 3 orders of 16 rows, B back to 1."""
    s = Song(model=R.MODEL_MONO, speed=4, timer=2, shift=1, loop=1)
    s.waves = _common_waves()
    s.samples = [Sample(kick()), Sample(snare()), Sample(loop_tone())]
    s.instruments = {
        1: Instrument(TONE, source=0, volume=Env([15, 14, 13, 12, 11, 10, 6, 3, 0], loop=4, release=6),
                      arpeggio=Env([0, 0, 12, 0]), pitch=Env([2, -2], loop=0), relative=True),
        2: Instrument(TONE, source=1, sweep=5, sweep_time=3, volume=Env([15, 15, 12, 9])),
        3: Instrument(NOISE, source=2, lfsr_reset=True, volume=Env([15, 10, 6, 3, 0])),
        4: Instrument(NOISE, source=0, volume=Env([14, 12, 9, 7, 5, 3, 1, 0]), wave=Env([0, 0, 1, 1, 6])),
        5: Instrument(SAMPLE, source=0, root=57),
        6: Instrument(SAMPLE, source=2, root=45, loop=True, loop_start=16, volume=Env([15, 12, 9, 6, 3, 0], release=2)),
        7: Instrument(TONE, source=2, wave=Env([0, 1, 2, 3], loop=0), pitch=Env([0, 4, 8, 4, 0, -4, -8, -4], loop=0)),
        9: Instrument(TONE, source=4, sweep=-3, sweep_time=0),
    }
    o0 = s.order(16)
    o0[0][0] = cell(57, 1, "C0C", "820")
    o0[0][2] = cell(60, 1, "037")
    o0[0][4] = cell(64, 0, "103")
    o0[0][6] = cell(64, 0, "100", "4A6")
    o0[0][8] = cell(62, 7, "W02", "P84")
    o0[0][12] = cell("OFF")
    o0[0][14] = cell(55, 1, "S02", "A04")
    o0[1][0] = cell(45, 5, "F04")
    o0[1][4] = cell(33, 2, "G02")
    o0[1][8] = cell(45, 5, "T03")
    o0[1][10] = cell(50, 6, "8F8")
    o0[1][14] = cell("OFF")
    o0[2][0] = cell(36, 2)
    o0[2][4] = cell(38, 2, "U80", "V01")
    o0[2][8] = cell(40, 9)
    o0[2][12] = cell(43, 2, "U00")
    o0[3][0] = cell(70, 3)
    o0[3][2] = cell(70, 3, "N05")
    o0[3][4] = cell(72, 4)
    o0[3][8] = cell(65, 1, "C08")
    o0[3][12] = cell(70, 3, "K02")
    o1 = s.order(16)
    o1[0][0] = cell(69, 1, "305")
    o1[0][4] = cell(72, 0)
    o1[0][8] = cell(67, 0, "300")
    o1[0][10] = cell(64, 1, "201")
    o1[0][12] = cell(64, 0, "200", "A30")
    o1[1][0] = cell(45, 5, "T01")
    o1[1][4] = cell(47, 6)
    o1[1][6] = cell(52, 0, "305")
    o1[1][12] = cell(40, 1, "300")
    o1[2][0] = cell(31, 2, "V1F")
    o1[2][8] = cell(31, 9, "U7F", "V00")
    o1[3][0] = cell(60, 3, "N07")
    o1[3][4] = cell(62, 4, "N03")
    o1[3][8] = cell(57, 1, "S01")
    o1[3][15] = cell(0, 0, "D04")
    o2 = s.order(16)
    o2[0][0] = cell(62, 1, "0C7", "K00")
    o2[0][8] = cell(64, 7, "W04")
    o2[1][0] = cell(45, 5, "G01", "T02")
    o2[2][0] = cell(36, 2, "U10", "V02")
    o2[3][0] = cell(55, 4, "P7C")
    o2[3][12] = cell(0, 0, "B01")
    return s


def wsc_song():
    """Color: CPU-fed and DMA samples on both sample lanes, stereo Hyper Voice, DMA takeover, hold, backwards."""
    s = Song(model=R.MODEL_COLOR, speed=5, timer=1, shift=0, loop=0)
    s.waves = _common_waves()
    s.samples = [Sample(kick()), Sample(snare()), Sample(loop_tone()), Sample(stereo_pad(), stereo=True)]
    s.instruments = {
        1: Instrument(TONE, source=3, volume=Env([15, 13, 11, 9], release=2)),
        2: Instrument(SAMPLE, source=0, root=57),
        3: Instrument(SAMPLE, source=3, root=57, loop=True, loop_start=24, volume=Env([15, 15, 10, 5, 0], release=1)),
        4: Instrument(SAMPLE, source=1, dma=True, rate=2, loop=True),
        5: Instrument(SAMPLE, source=0, dma=True, rate=3),
        6: Instrument(SAMPLE, source=2, dma=True, rate=1, reverse=True, loop=True, volume=Env([15, 8, 4])),
        7: Instrument(NOISE, source=1, volume=Env([12, 8, 4, 0])),
        8: Instrument(SAMPLE, source=3, dma=True, rate=3, loop=True),
    }
    o0 = s.order(16)
    o0[0][0] = cell(57, 1, "4F2")
    o0[0][8] = cell(60, 1)
    o0[0][12] = cell("OFF")
    o0[1][0] = cell(57, 2)
    o0[1][4] = cell(57, 5, "8F0")
    o0[1][8] = cell(50, 2, "C0A")
    o0[1][12] = cell(57, 6, "88F")
    o0[2][0] = cell(48, 1, "U05", "V02")
    o0[3][0] = cell(70, 7)
    o0[3][8] = cell(72, 7, "N06")
    o0[4][0] = cell(57, 3, "8F3")
    o0[4][4] = cell("OFF")
    o0[4][8] = cell(57, 4, "C0C")
    o0[4][10] = cell(0, 0, "H01")
    o0[4][11] = cell(0, 0, "H00", "80F")
    o0[4][14] = cell(60, 8, "C07")  # stereo DMA loop: takes the DMA from CH2's backwards loop (row 12)
    o1 = s.order(32)
    o1[0][0] = cell(55, 1, "A02")
    o1[0][20] = cell(62, 1, "A00")
    o1[1][0] = cell(57, 5)
    o1[1][2] = cell(59, 2, "305")
    o1[1][6] = cell(52, 2, "300", "T02")
    o1[1][16] = cell(57, 6, "8FF")
    o1[1][24] = cell("OFF")
    o1[2][4] = cell(43, 1)
    o1[3][2] = cell(66, 7, "S01")
    # a CPU-fed stereo loop two ticks after CH2's DMA one-shot took the DMA from Hyper Voice; it streams on through
    # the next pass's re-key and OFF until order 0 row 8 (about 118 ticks)
    o1[4][0] = cell(64, 3, "G02", "8FF")
    return s


def wsc_vgm():
    """Color without Hyper Voice notes: a CH2 DMA one-shot and a DMA loop, tones elsewhere, looping to order 0."""
    s = Song(model=R.MODEL_COLOR, speed=6, timer=3, shift=0, loop=0)
    s.waves = [wave_triangle(), wave_square(12)]
    s.samples = [Sample(kick(160)), Sample(loop_tone(48))]
    s.instruments = {
        1: Instrument(TONE, source=0, volume=Env([15, 12, 10, 8], loop=3)),
        2: Instrument(SAMPLE, source=0, dma=True, rate=2),
        3: Instrument(SAMPLE, source=1, dma=True, rate=0, loop=True),
        4: Instrument(TONE, source=1, sweep=2, sweep_time=1),
    }
    o0 = s.order(16)
    o0[0][0] = cell(60, 1)
    o0[0][8] = cell(64, 1, "4F1")
    o0[1][0] = cell(57, 2)
    o0[1][6] = cell(57, 3, "8F8")
    o0[1][12] = cell("OFF")
    o0[2][0] = cell(36, 4)
    o0[3][0] = cell(48, 1, "C06")
    o0[4][0] = cell(0, 0, "T02")
    return s


def ws_tone():
    """Original model: A-4 held on CH1 (a square wave at volume 15) for the pitch check; one order of 64 rows."""
    s = Song(model=R.MODEL_MONO, speed=6, timer=1, loop=0)
    s.waves = [wave_square()]
    s.instruments = {1: Instrument(TONE, source=0)}
    o0 = s.order(64)
    o0[0][0] = cell(57, 1)
    return s


SONGS = {"ws_song": ws_song, "wsc_song": wsc_song, "wsc_vgm": wsc_vgm, "ws_tone": ws_tone}


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    for name, fn in SONGS.items():
        path = os.path.join(out, name + ".bin")
        with open(path, "wb") as f:
            f.write(build(fn()))
        print(path)
