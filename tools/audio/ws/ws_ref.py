#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's WonderSwan player, written from the specification in
docs/AUDIO_ENGINE.md ("WonderSwan: Thor Tracker's own player on the WonderSwan sound unit") independently of the C
player (app/src/main/cpp/ws/ws_player.c). The host test compares the C player's writes with this one's, tick by tick
and timer interrupt by timer interrupt (with their cycles), and its VGM bytes with vgm() here.

    python3 tools/audio/ws/ws_ref.py IMAGE.bin --trace out.txt [--ticks 1500]
    python3 tools/audio/ws/ws_ref.py IMAGE.bin --vgm out.vgm [--max-ticks N]

The trace file has the host test's ws_trace.txt format: per tick "T <tick> <order> <row> <tick-in-row> <cycle>" (the
position before the tick and its first cycle) and one "W <space> <addr hex4> <val hex2>" per write of its batch; per
timer interrupt "I <cycle>" and its W lines; per Sound DMA transfer "D <cycle> <target> <data hex2>". The reset batch
comes first as "R" followed by its W lines. The VGM written here has no GD3 tag (the engine's ws_vgm_render output;
ws_export_vgm appends the tag). Pure Python 3, no dependencies."""
import argparse
import math
import sys

# ---- clocks ---------------------------------------------------------------------------------------------------------
CPU_HZ = 3072000          # the sound unit's and the V30MZ's clock ("cycles")
TICK_CYCLES = 40704       # one frame: 159 lines of 256 cycles (75.4717 Hz)
LINE_CYCLES = 256         # one display line: the HBlank timer's unit (12 kHz)
BLOCK_CYCLES = 128        # one output frame of the sound unit (24 kHz)
DMA_TRANSFER_CYCLE = 122  # the Sound DMA's cycle within a block
VGM_CLOCK = 3072000       # VGM header 0xC0
VGM_NUM, VGM_DEN = 147, 10240  # 44100 / 3072000

# ---- image layout ---------------------------------------------------------------------------------------------------
IMAGE_SIZE = 131072
MAGIC = b"TTWS"
LAYOUT = 1
MODEL_MONO, MODEL_COLOR = 0, 1
CHANNELS, CHANNELS_MONO = 5, 4
VOICE_CHANNEL, SWEEP_CHANNEL, NOISE_CHANNEL, HYPER_VOICE = 1, 2, 3, 4
MAX_ORDERS = 256
MAX_INSTRUMENTS = 64
MAX_WAVES = 64
MAX_SAMPLES = 64
MAX_EFFECTS = 4
MAX_TIMER = 255
MAX_SHIFT = 3
MAX_ENVELOPE = 255
MAX_SAMPLE_FRAMES = 65535
MAX_TAP = 7
MAX_SWEEP_TIME = 31
MAX_DMA_RATE = 3
WAVE_SIZE = 32
WAVE_MAX = 15
DMA_BASE = 0x40000
WAVE_RAM = 0x3FC0
OUTPUT_HEADPHONES, OUTPUT_SPEAKER = 0, 1

H_MAGIC, H_LAYOUT, H_MODEL, H_SPEED, H_INSTRUMENTS, H_ORDERS, H_LOOP = 0, 4, 5, 6, 7, 8, 10
H_WAVES, H_SAMPLES, H_TIMER, H_SHIFT, H_OUTPUT, H_DATA_SIZE, HEADER_SIZE = 12, 13, 14, 15, 16, 20, 64
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_WAVES, OFF_SAMPLES, OFF_DATA = 64, 320, 2880, 4928, 6976, 8192
INSTRUMENT_SIZE = 32
SAMPLE_ENTRY_SIZE = 16
DATA_CAPACITY = IMAGE_SIZE - OFF_DATA
S_OFFSET, S_FRAMES, S_SIGNED_OFFSET, S_FLAGS = 0, 4, 8, 12
SAMPLE_STEREO, SAMPLE_SIGNED = 1, 2
I_FLAGS, I_SOURCE, I_ROOT, I_LOOP, I_LOOP_START, I_SWEEP, I_SWEEP_TIME, I_ENVELOPES = 0, 1, 2, 3, 4, 6, 7, 8
ENVELOPE_BYTES = 6
ENV_VOLUME, ENV_ARPEGGIO, ENV_PITCH, ENV_WAVE, ENVELOPE_COUNT = 0, 1, 2, 3, 4
ENV_NONE = 0xFF
FLAG_DEFINED, FLAG_RELATIVE, FLAG_DMA, FLAG_LFSR_RESET, FLAG_REVERSE = 0x80, 0x04, 0x08, 0x10, 0x20
KIND_MASK, KIND_TONE, KIND_NOISE, KIND_SAMPLE = 0x03, 0, 1, 2
SPACE_PORT, SPACE_RAM = 0, 1

NOTE_OFF = 0xFE
NOTE_MIN, NOTE_MAX = 19, 95      # G-1 .. B-7
UNITS_PER_SEMITONE = 64
PITCH_MIN = 1167                 # the lowest pitch whose N <= 2048
PITCH_MAX = 6143                 # B-7 + 63/64
SLIDE_LIMIT = 6144
VOL8_MAX = 120                   # Cxx volume 15 x 8

# round(96000 / (440 * 2^((i / 64 - 57) / 12)) * 65536): N = 2048 - d of C-0's octave in 16.16 fixed point
# (96000 = 3072000 / 32: the wavetable step rate over the 32 steps of a wave). ws_player.c writes the same numbers.
PERIOD_Q16 = tuple(round(96000 / (440 * 2 ** ((i / 64 - 57) / 12)) * 65536) for i in range(768))

# floor(127 sin(2 pi i / 64) + 0.5), written out (the OPL3 player's vibrato table).
SINE = (0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126,
        127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12,
        0, -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
        -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12)

LETTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
FX = {c: i for i, c in enumerate(LETTERS)}
EFFECTS = frozenset(FX[c] for c in "012348ABCDFGHKNPSTUVW")
DMA_DIVISOR = (6, 4, 2, 1)       # blocks per transfer for rates 0..3 (4000, 6000, 12000, 24000 bytes/s)
HV_SPEED_FOR_RATE = (5, 3, 1, 0)  # Hyper Voice update divisor 6, 4, 2, 1 matching the DMA rate


def period(pitch):
    """N = 2048 - d (1..2048) of a pitch in 1/64 semitones (0 = C-0), clamped to PITCH_MIN..PITCH_MAX."""
    p = max(PITCH_MIN, min(PITCH_MAX, pitch))
    o, i = divmod(p, 768)
    return (PERIOD_Q16[i] + (1 << (15 + o))) >> (16 + o)


def register(pitch):
    """The 11-bit frequency register d of a pitch."""
    return 2048 - period(pitch)


def sample_step(pitch, root_note):
    """Frames per timer interrupt in 16.16 for `pitch` (clamped here) with a root note playing one frame per interrupt."""
    p = max(PITCH_MIN, min(PITCH_MAX, pitch))
    rp = root_note * UNITS_PER_SEMITONE
    d = p // 768 - rp // 768
    if d >= 0:
        return ((PERIOD_Q16[rp % 768] << (16 + d)) // PERIOD_Q16[p % 768]) & 0xFFFFFFFF
    return (((PERIOD_Q16[rp % 768] << 16) // PERIOD_Q16[p % 768]) >> -d) & 0xFFFFFFFF


def vgm_sample(cycle):
    return cycle * VGM_NUM // VGM_DEN


def u16(b, at):
    return b[at] | b[at + 1] << 8


def u32(b, at):
    return b[at] | b[at + 1] << 8 | b[at + 2] << 16 | b[at + 3] << 24


def s8(v):
    return v - 256 if v >= 128 else v


class ImageError(ValueError):
    def __init__(self, reason, offset):
        super().__init__("%s (byte %d)" % (reason, offset))
        self.reason, self.offset = reason, offset


class Row:
    __slots__ = ("note", "instrument", "effects")

    def __init__(self, note=0, instrument=0, effects=()):
        self.note, self.instrument, self.effects = note, instrument, tuple(effects)


EMPTY_ROW = Row()


class Image:
    """A loaded image: the header fields, every (order, lane) pattern decoded, the derived start speeds. Checks the
    structure it needs to play (the full loader contract is the C loader's)."""

    def __init__(self, data):
        b = bytes(data) + bytes(IMAGE_SIZE - len(data)) if len(data) < IMAGE_SIZE else bytes(data)
        if len(b) != IMAGE_SIZE:
            raise ImageError("image size", 0)
        if b[0:4] != MAGIC:
            raise ImageError("bad magic", 0)
        if b[H_LAYOUT] != LAYOUT:
            raise ImageError("unknown layout", H_LAYOUT)
        self.bytes = b
        self.model = b[H_MODEL]
        if self.model not in (MODEL_MONO, MODEL_COLOR):
            raise ImageError("unknown model", H_MODEL)
        self.color = self.model == MODEL_COLOR
        self.lanes = CHANNELS if self.color else CHANNELS_MONO
        self.speed = b[H_SPEED]
        self.instruments = b[H_INSTRUMENTS]
        self.orders = u16(b, H_ORDERS)
        self.loop = u16(b, H_LOOP)
        self.waves = b[H_WAVES]
        self.samples = b[H_SAMPLES]
        self.timer = b[H_TIMER]
        self.shift = b[H_SHIFT]
        self.output = b[H_OUTPUT]
        self.data_size = u32(b, H_DATA_SIZE)
        if not (1 <= self.orders <= MAX_ORDERS) or self.loop >= self.orders or self.speed < 1:
            raise ImageError("bad header", H_ORDERS)
        self.data = b[OFF_DATA:OFF_DATA + DATA_CAPACITY]
        self.rows = [b[OFF_ROWS + o] or 256 for o in range(self.orders)]
        self.patterns = [[self._decode(o, c) for c in range(self.lanes)] for o in range(self.orders)]
        self.start_speed = []
        s = self.speed
        for o in range(self.orders):
            self.start_speed.append(s)
            for r in range(self.rows[o]):
                for c in range(self.lanes):
                    for code, param in self.patterns[o][c][r].effects:
                        if code == FX["F"]:
                            s = param
        self.wave_bytes = [b[OFF_WAVES + WAVE_SIZE * w:OFF_WAVES + WAVE_SIZE * (w + 1)] for w in range(MAX_WAVES)]

    def _decode(self, order, lane):
        offset = u16(self.bytes, OFF_ORDERS + 2 * (CHANNELS * order + lane))
        rows, at, n = [], offset, self.rows[order]
        while len(rows) < n:
            if at >= self.data_size:
                raise ImageError("pattern reads past the data", OFF_DATA + at)
            m = self.data[at]
            at += 1
            if m & 0x80:
                rows.extend([EMPTY_ROW] * min((m & 0x7F) + 1, n - len(rows)))
                continue
            note = instrument = 0
            if m & 1:
                note = self.data[at]
                at += 1
            if m & 2:
                instrument = self.data[at]
                at += 1
            effects = []
            for _ in range((m >> 2) & 7):
                effects.append((self.data[at], self.data[at + 1]))
                at += 2
            rows.append(Row(note, instrument, effects))
        return rows

    def record(self, slot):
        """The raw 32-byte record of a slot (1..64)."""
        slot = max(1, min(MAX_INSTRUMENTS, slot))
        at = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        return self.bytes[at:at + INSTRUMENT_SIZE]

    def instrument(self, slot):
        """A defined slot's record, else None."""
        if slot < 1 or slot > self.instruments:
            return None
        rec = self.record(slot)
        return rec if rec[I_FLAGS] & FLAG_DEFINED else None

    def sample_entry(self, index):
        at = OFF_SAMPLES + SAMPLE_ENTRY_SIZE * (index & (MAX_SAMPLES - 1))
        e = self.bytes[at:at + SAMPLE_ENTRY_SIZE]
        return u32(e, S_OFFSET), u32(e, S_FRAMES), u32(e, S_SIGNED_OFFSET), e[S_FLAGS]

    def hyper_voice_notes(self):
        """True when a Hyper Voice lane pattern has a note (the VGM refusal)."""
        if not self.color:
            return False
        return any(NOTE_MIN <= row.note <= NOTE_MAX for o in range(self.orders) for row in self.patterns[o][HYPER_VOICE])

    def dma_byte(self, address):
        """What Sound DMA reads at a linear address: the data region mapped at DMA_BASE, 0 elsewhere."""
        at = address - DMA_BASE
        return self.data[at] if 0 <= at < DATA_CAPACITY else 0


class Envelope:
    __slots__ = ("offset", "length", "loop", "release")

    def __init__(self, rec, k):
        at = I_ENVELOPES + ENVELOPE_BYTES * k
        self.offset = u16(rec, at)
        self.length = rec[at + 2]
        self.loop = -1 if rec[at + 3] == ENV_NONE else rec[at + 3]
        self.release = -1 if rec[at + 4] == ENV_NONE else rec[at + 4]

    def value(self, image, idx, signed=False):
        v = image.data[self.offset + min(idx, self.length - 1)]
        return s8(v) if signed else v

    def next(self, idx, released):
        idx = min(idx, self.length - 1)
        n = idx + 1
        if released and self.release >= 0:
            return min(n, self.length - 1)
        held = self.release if self.release >= 0 else self.length
        if n < held:
            return n
        return self.loop if self.loop >= 0 else held - 1


def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


def voice_bits(l, r):
    return (1 if r >= 12 else 2 if r >= 1 else 0) | (4 if l >= 12 else 8 if l >= 1 else 0)


def hv_control(v, l, r, speed):
    if v == 0 or (l == 0 and r == 0):
        return 0
    shift = 0 if v >= 12 else 1 if v >= 6 else 2 if v >= 3 else 3
    return 0x80 | speed << 4 | 2 << 2 | shift


class Lane:
    def __init__(self):
        self.row = EMPTY_ROW
        self.pending = False
        self.delay = 0
        self.cut = -1
        self.instrument = 0
        self.slot = 0
        self.kind = KIND_TONE
        self.dma = False
        self.sounding = False
        self.fresh = False
        self.released = False
        self.feeding = False
        self.pos = 0
        self.step = 0
        self.env = [0, 0, 0, 0]
        self.rel_pitch = 0
        self.base = self.target = 0
        self.fine = 0
        self.arp = 0
        self.arp_step = 0
        self.slide_mode = 0  # 0 none, 1 up, 2 down, 3 portamento
        self.slide_speed = 0
        self.slide = 0
        self.vib_speed = self.vib_depth = self.vib_phase = 0
        self.vol8 = VOL8_MAX
        self.volslide = 0
        self.pan = 0xFF
        self.wave_ov = None
        self.tap_ov = None
        self.sweep_ov = None
        self.time_ov = None
        self.hold = 0
        self.hold_written = 0
        self.loaded = -1
        self.was_sweep = False
        self.last_d = 0
        self.sweep_s = 0
        self.side_l = False
        self.side_r = False

    def state(self):
        return tuple((k, tuple(v) if isinstance(v, list) else (v.note, v.instrument, v.effects) if isinstance(v, Row) else v)
                     for k, v in sorted(vars(self).items()))


SHADOWED = tuple(range(0x80, 0x8D)) + (0x8E, 0x90, 0x91, 0x94, 0x6A, 0x6B)


class Player:
    def __init__(self, image, audition=False):
        self.image = image
        self.audition = audition
        self.reset()

    # ---- writes ------------------------------------------------------------------------------------------------
    def _emit(self, space, addr, val):
        self.writes.append((space, addr, val & 0xFF))

    def through(self, port, val):
        if self.shadow[port] == val:
            return
        self._emit(SPACE_PORT, port, val)
        self.shadow[port] = val

    def forced(self, port, val):
        self._emit(SPACE_PORT, port, val)
        self.shadow[port] = val & ~0x08 if port == 0x8E else val & ~0x10 if port == 0x6B else val

    def raw(self, port, val):
        self._emit(SPACE_PORT, port, val)
        if port == 0x8D:
            self.time_written = val
        if port == 0x89:
            self.shadow[0x89] = -1

    def ram(self, addr, val):
        self._emit(SPACE_RAM, addr, val)

    # ---- reset -------------------------------------------------------------------------------------------------
    def reset(self):
        im = self.image
        self.lanes = [Lane() for _ in range(im.lanes)]
        self.order = self.row = self.tick = 0
        self.speed = im.speed
        self.jump = False
        self.jump_order = self.jump_row = 0
        self.timer = im.timer
        self.shift = im.shift
        self.owner = -1
        self.dma_kill = False
        self.time_written = 0
        self.shadow = {p: -1 for p in SHADOWED}
        self.writes = []
        self._emit(SPACE_PORT, 0x8F, 0xFF)  # $8F is not shadowed: written once, here
        self.forced(0x90, 0)
        self.forced(0x91, 0x09 | self.shift << 1)
        for c in range(4):
            self.forced(0x80 + 2 * c, 0)
            self.forced(0x81 + 2 * c, 0)
            self.forced(0x88 + c, 0)
        self.forced(0x8C, 0)
        self.raw(0x8D, 0)
        self.forced(0x8E, 0)
        self.forced(0x94, 0)
        if im.color:
            self.forced(0x6A, 0)
            self.forced(0x6B, 0x10)
            self.raw(0x52, 0)

    def place(self, order):
        self.reset()
        self.order = order
        self.speed = self.image.start_speed[order]

    def state(self):
        """Every field that decides future writes (the VGM loop comparison)."""
        return (self.order, self.row, self.tick, self.speed, self.jump, self.jump_order, self.jump_row, self.timer,
                self.shift, self.owner, self.dma_kill, self.time_written, tuple(sorted(self.shadow.items())),
                tuple(l.state() for l in self.lanes))

    # ---- notes -------------------------------------------------------------------------------------------------
    def fits(self, c, rec):
        kind = rec[I_FLAGS] & KIND_MASK
        if kind == KIND_TONE:
            return c <= 3
        if kind == KIND_NOISE:
            return c == NOISE_CHANNEL
        if kind != KIND_SAMPLE:
            return False
        if rec[I_FLAGS] & FLAG_DMA and not self.image.color:
            return False
        stereo = self.image.sample_entry(rec[I_SOURCE])[3] & SAMPLE_STEREO
        return c == HYPER_VOICE or (c == VOICE_CHANNEL and not stereo)

    def key_on(self, c, note):
        ln = self.lanes[c]
        if self.owner == c:
            self.owner = -1
            self.raw(0x52, 0)
        rec = self.image.instrument(ln.instrument)
        if rec is None or not self.fits(c, rec):
            return
        ln.slot = ln.instrument
        ln.kind = rec[I_FLAGS] & KIND_MASK
        ln.dma = ln.kind == KIND_SAMPLE and bool(rec[I_FLAGS] & FLAG_DMA)
        ln.env = [0, 0, 0, 0]
        ln.released = False
        ln.rel_pitch = 0
        ln.base = ln.target = note * UNITS_PER_SEMITONE
        ln.slide = 0
        ln.arp_step = 0
        ln.vib_phase = 0
        ln.sounding = True
        ln.fresh = True
        ln.feeding = ln.kind == KIND_SAMPLE and not ln.dma
        if ln.feeding:
            ln.pos = 0x10000

    def stop(self, c):
        ln = self.lanes[c]
        ln.sounding = False
        ln.feeding = False
        if self.owner == c:
            self.owner = -1
            self.raw(0x52, 0)
        if c == HYPER_VOICE:
            self.through(0x6A, 0)

    def off(self, c):
        ln = self.lanes[c]
        if not ln.sounding:
            return
        rec = self.image.record(ln.slot)
        vol = Envelope(rec, ENV_VOLUME)
        if vol.length and vol.release >= 0:
            ln.released = True
            for k in range(ENVELOPE_COUNT):
                e = Envelope(rec, k)
                if e.length and e.release >= 0:
                    ln.env[k] = e.release
        else:
            self.stop(c)

    def control(self):
        v = 0
        for c in range(4):
            ln = self.lanes[c]
            if ln.sounding and (ln.kind == KIND_TONE or (c == NOISE_CHANNEL and ln.kind == KIND_NOISE)):
                v |= 1 << c
        l1, l2, l3 = self.lanes[1], self.lanes[2], self.lanes[3]
        if l1.sounding and l1.kind == KIND_SAMPLE:
            v |= 0x20
        if l2.sounding and l2.kind == KIND_TONE and l2.sweep_s != 0:
            v |= 0x40
        if l3.sounding and l3.kind == KIND_NOISE:
            v |= 0x80
        return v

    def run_row(self, c):
        ln = self.lanes[c]
        row = ln.row
        has_note = NOTE_MIN <= row.note <= NOTE_MAX
        if has_note:
            ln.arp = 0
            ln.fine = 0
            ln.wave_ov = ln.tap_ov = ln.sweep_ov = ln.time_ov = None
            ln.hold = 0
        if row.instrument:
            ln.instrument = row.instrument
        for code, v in row.effects:
            ch = LETTERS[code] if code < len(LETTERS) else "?"
            if ch == "0":
                ln.arp, ln.arp_step = v, 0
            elif ch == "1":
                ln.slide_mode, ln.slide_speed = (1 if v else 0), v
            elif ch == "2":
                ln.slide_mode, ln.slide_speed = (2 if v else 0), v
            elif ch == "3":
                ln.slide_mode, ln.slide_speed = (3 if v else 0), v
            elif ch == "4":
                ln.vib_speed, ln.vib_depth = v >> 4, v & 15
            elif ch == "8":
                ln.pan = v
            elif ch == "A":
                ln.volslide = v
            elif ch == "C":
                ln.vol8 = v * 8
            elif ch == "H":
                ln.hold = v
            elif ch == "K":
                self.shift = v
                self.through(0x91, 0x09 | self.shift << 1)
            elif ch == "N":
                ln.tap_ov = v
            elif ch == "P":
                ln.fine = v - 0x80
            elif ch == "S":
                ln.cut = v
            elif ch == "T":
                self.timer = v
            elif ch == "U":
                ln.sweep_ov = s8(v)
            elif ch == "V":
                ln.time_ov = v
            elif ch == "W":
                ln.wave_ov = v
        if row.note == NOTE_OFF:
            self.off(c)
        elif has_note:
            if ln.slide_mode == 3 and ln.sounding and (ln.kind == KIND_TONE or (ln.kind == KIND_SAMPLE and not ln.dma)):
                ln.target = row.note * UNITS_PER_SEMITONE
            else:
                self.key_on(c, row.note)

    # ---- per tick ----------------------------------------------------------------------------------------------
    def update(self, c):
        ln = self.lanes[c]
        if not ln.sounding:
            return
        im = self.image
        rec = im.record(ln.slot)
        flags = rec[I_FLAGS]
        env = [Envelope(rec, k) for k in range(ENVELOPE_COUNT)]
        e_vol = env[ENV_VOLUME].value(im, ln.env[ENV_VOLUME]) if env[ENV_VOLUME].length else 15
        arp_env = env[ENV_ARPEGGIO].value(im, ln.env[ENV_ARPEGGIO], True) if env[ENV_ARPEGGIO].length else 0
        pitch_env = 0
        if env[ENV_PITCH].length:
            v = env[ENV_PITCH].value(im, ln.env[ENV_PITCH], True)
            if flags & FLAG_RELATIVE:
                ln.rel_pitch = clamp(ln.rel_pitch + v, -SLIDE_LIMIT, SLIDE_LIMIT)
                pitch_env = ln.rel_pitch
            else:
                pitch_env = v
        vol = (e_vol * (ln.vol8 >> 3) + 7) // 15
        l = (vol * (ln.pan >> 4) + 7) // 15
        r = (vol * (ln.pan & 15) + 7) // 15
        arp = (ln.arp >> 4) if ln.arp_step == 1 else (ln.arp & 15) if ln.arp_step == 2 else 0
        semitones = arp + arp_env
        vib = (SINE[ln.vib_phase] * ln.vib_depth) >> 4
        pitch = clamp(ln.base + 64 * semitones + ln.fine + ln.slide + vib + pitch_env, PITCH_MIN, PITCH_MAX)
        d = register(pitch)
        if ln.kind == KIND_TONE:
            w = env[ENV_WAVE].value(im, ln.env[ENV_WAVE]) if env[ENV_WAVE].length else (
                ln.wave_ov if ln.wave_ov is not None else rec[I_SOURCE])
            if ln.loaded != w:
                wave = im.wave_bytes[w & (MAX_WAVES - 1)]
                for k in range(16):
                    self.ram(WAVE_RAM + 16 * c + k, wave[2 * k] | wave[2 * k + 1] << 4)
                ln.loaded = w
            if c == SWEEP_CHANNEL:
                s = ln.sweep_ov if ln.sweep_ov is not None else s8(rec[I_SWEEP])
                t = ln.time_ov if ln.time_ov is not None else rec[I_SWEEP_TIME]
                ln.sweep_s = s
                self.through(0x8C, s & 0xFF)
                if s != 0 and (ln.fresh or t != self.time_written):
                    self.raw(0x8D, t)
                if s != 0 or ln.was_sweep:
                    if ln.fresh or d != ln.last_d or (s != 0) != ln.was_sweep:
                        self.forced(0x84, d & 0xFF)
                        self.forced(0x85, d >> 8)
                else:
                    self.through(0x84, d & 0xFF)
                    self.through(0x85, d >> 8)
                ln.was_sweep = s != 0
                ln.last_d = d
            else:
                self.through(0x80 + 2 * c, d & 0xFF)
                self.through(0x81 + 2 * c, d >> 8)
            self.through(0x88 + c, l << 4 | r)
        elif ln.kind == KIND_NOISE:
            t = env[ENV_WAVE].value(im, ln.env[ENV_WAVE]) if env[ENV_WAVE].length else (
                ln.tap_ov if ln.tap_ov is not None else rec[I_SOURCE])
            t &= 7
            if ln.fresh and flags & FLAG_LFSR_RESET:
                self.forced(0x8E, 0x18 | t)
            else:
                self.through(0x8E, 0x10 | t)
            self.through(0x86, d & 0xFF)
            self.through(0x87, d >> 8)
            self.through(0x8B, l << 4 | r)
        else:
            offset, frames, signed_offset, sflags = im.sample_entry(rec[I_SOURCE])
            stereo = bool(sflags & SAMPLE_STEREO)
            ln.side_l, ln.side_r = l > 0, r > 0
            if not ln.dma:
                ln.step = sample_step(pitch, rec[I_ROOT])
                if c == VOICE_CHANNEL:
                    self.through(0x94, voice_bits(l, r))
                    if ln.fresh:
                        self.raw(0x89, im.data[offset])
                else:
                    if ln.fresh:
                        self.forced(0x6B, 0x10)
                    else:
                        self.through(0x6B, 0)
                    self.through(0x6A, hv_control(vol, l, r, 0))
                    if ln.fresh:
                        self.write_hv_frame(ln, offset, 0, stereo)
            else:
                rate = rec[I_ROOT] & 3
                loop = 1 if rec[I_LOOP] else 0
                reverse = 1 if flags & FLAG_REVERSE else 0
                mode = 0 if stereo else (1 if l > 0 else 0) | (2 if r > 0 else 0)
                control = 0x80 | rate | loop << 3 | (1 if c == HYPER_VOICE else 0) << 4 | reverse << 6
                if ln.fresh:
                    if self.owner >= 0 and self.owner != c:
                        self.stop(self.owner)
                    self.raw(0x52, 0)
                    a = DMA_BASE + (offset if c == VOICE_CHANNEL else signed_offset)
                    nbytes = frames * (2 if stereo else 1)
                    if reverse:
                        a += nbytes - 1
                    self.raw(0x4A, a & 0xFF)
                    self.raw(0x4B, a >> 8 & 0xFF)
                    self.raw(0x4C, a >> 16 & 0xFF)
                    self.raw(0x4E, nbytes & 0xFF)
                    self.raw(0x4F, nbytes >> 8 & 0xFF)
                    self.raw(0x50, nbytes >> 16 & 0xFF)
                    if c == VOICE_CHANNEL:
                        self.shadow[0x89] = -1  # the DMA writes $89 from now on
                        self.through(0x94, voice_bits(l, r))
                    else:
                        self.forced(0x6B, 0x10 | mode << 5)
                        self.through(0x6A, hv_control(vol, l, r, HV_SPEED_FOR_RATE[rate]))
                    self.raw(0x52, control | ln.hold << 2)
                    self.owner = c
                    ln.hold_written = ln.hold
                else:
                    if c == VOICE_CHANNEL:
                        self.through(0x94, voice_bits(l, r))
                    else:
                        self.through(0x6B, mode << 5)
                        self.through(0x6A, hv_control(vol, l, r, HV_SPEED_FOR_RATE[rate]))
                    if self.owner == c and ln.hold != ln.hold_written:
                        self.raw(0x52, control | ln.hold << 2)
                        ln.hold_written = ln.hold
        ln.fresh = False
        for k in range(ENVELOPE_COUNT):
            if env[k].length:
                ln.env[k] = env[k].next(ln.env[k], ln.released)
        if ln.arp:
            ln.arp_step = (ln.arp_step + 1) % 3
        if ln.slide_mode == 1:
            ln.slide = clamp(ln.slide + ln.slide_speed, -SLIDE_LIMIT, SLIDE_LIMIT)
        elif ln.slide_mode == 2:
            ln.slide = clamp(ln.slide - ln.slide_speed, -SLIDE_LIMIT, SLIDE_LIMIT)
        elif ln.slide_mode == 3:
            if ln.base < ln.target:
                ln.base = min(ln.base + ln.slide_speed, ln.target)
            elif ln.base > ln.target:
                ln.base = max(ln.base - ln.slide_speed, ln.target)
        if ln.vib_depth and ln.vib_speed:
            ln.vib_phase = (ln.vib_phase + ln.vib_speed) & 63
        if ln.volslide:
            ln.vol8 = clamp(ln.vol8 + (ln.volslide >> 4) - (ln.volslide & 15), 0, VOL8_MAX)

    def write_hv_frame(self, ln, offset, f, stereo):
        data = self.image.data
        if stereo:
            left, right = data[offset + 2 * f], data[offset + 2 * f + 1]
        else:
            left = right = data[offset + f]
        self.raw(0x69, left ^ 0x80 if ln.side_l else 0)
        self.raw(0x69, right ^ 0x80 if ln.side_r else 0)

    def row_start(self):
        im = self.image
        for c in range(im.lanes):
            self.lanes[c].row = im.patterns[self.order][c][self.row]
        has_b = has_d = False
        b = d = 0
        for c in range(im.lanes):
            for code, param in self.lanes[c].row.effects:
                if code == FX["F"]:
                    self.speed = param
                elif code == FX["B"]:
                    has_b, b = True, param
                elif code == FX["D"]:
                    has_d, d = True, param
        self.jump = has_b or has_d
        self.jump_order = b if has_b else (self.order + 1 if self.order + 1 < im.orders else im.loop)
        self.jump_row = d if has_d else 0
        for c in range(im.lanes):
            ln = self.lanes[c]
            ln.cut = -1
            ln.pending = False
            g = 0
            for code, param in ln.row.effects:
                if code == FX["G"]:
                    g = param
            if g:
                ln.pending, ln.delay = True, g
            else:
                self.run_row(c)

    def advance(self):
        im = self.image
        if self.jump:
            self.order = self.jump_order
            self.row = min(self.jump_row, im.rows[self.order] - 1)
            self.jump = False
        else:
            self.row += 1
            if self.row >= im.rows[self.order]:
                self.row = 0
                self.order += 1
                if self.order >= im.orders:
                    self.order = im.loop
        if self.order == im.loop and self.row == 0:
            self.speed = im.start_speed[self.order]

    def tick_batch(self):
        """One tick: returns its writes."""
        self.writes = []
        if self.dma_kill:
            self.dma_kill = False
            if self.owner >= 0:
                self.stop(self.owner)
        self.through(0x91, 0x09 | self.shift << 1)
        n = self.image.lanes
        if not self.audition:
            if self.tick == 0:
                self.row_start()
            else:
                for c in range(n):
                    ln = self.lanes[c]
                    if ln.pending and ln.delay == self.tick:
                        ln.pending = False
                        self.run_row(c)
            for c in range(n):
                if self.lanes[c].cut == self.tick:
                    self.off(c)
        for c in range(n):
            self.update(c)
        self.through(0x90, self.control())
        if not self.audition:
            self.tick += 1
            if self.tick >= self.speed:
                self.tick = 0
                self.advance()
        return self.writes

    def timer_batch(self):
        """One voice-timer interrupt: returns its writes."""
        self.writes = []
        im = self.image
        for c in (VOICE_CHANNEL, HYPER_VOICE):
            if c >= im.lanes:
                continue
            ln = self.lanes[c]
            if not ln.feeding:
                continue
            rec = im.instrument(ln.slot)
            if rec is None or rec[I_FLAGS] & KIND_MASK != KIND_SAMPLE or rec[I_FLAGS] & FLAG_DMA:
                self.stop(c)
                continue
            offset, frames, _, sflags = im.sample_entry(rec[I_SOURCE])
            f = ln.pos >> 16
            if f >= frames:
                start = u16(rec, I_LOOP_START)
                if not rec[I_LOOP] or start >= frames:
                    self.stop(c)
                    continue
                span = (frames - start) << 16
                ln.pos = (start << 16) + (ln.pos - (frames << 16)) % span
                f = ln.pos >> 16
            if c == VOICE_CHANNEL:
                self.raw(0x89, im.data[offset + f])
            else:
                self.write_hv_frame(ln, offset, f, bool(sflags & SAMPLE_STEREO))
            ln.pos += ln.step
        self.through(0x90, self.control())
        return self.writes


class Dma:
    """The Sound DMA unit as the spec defines it (Color), fed with the player's port writes, stepped per block."""

    def __init__(self):
        self.source = self.length = self.shadow_source = self.shadow_length = 0
        self.rate = self.hold = self.loop = self.target = self.direction = 0
        self.enable = 0
        self.clock = 0

    def state(self):
        return tuple(sorted(vars(self).items()))

    def write(self, port, val):
        if 0x4A <= port <= 0x4C:
            sh = 8 * (port - 0x4A)
            self.source = (self.source & ~(0xFF << sh) | val << sh) & 0xFFFFF
            self.shadow_source = (self.shadow_source & ~(0xFF << sh) | val << sh) & 0xFFFFF
        elif 0x4E <= port <= 0x50:
            sh = 8 * (port - 0x4E)
            self.length = (self.length & ~(0xFF << sh) | val << sh) & 0xFFFFF
            self.shadow_length = (self.shadow_length & ~(0xFF << sh) | val << sh) & 0xFFFFF
        elif port == 0x52:
            self.rate = val & 3
            self.hold = val >> 2 & 1
            self.loop = val >> 3 & 1
            self.target = val >> 4 & 1
            self.direction = val >> 6 & 1
            self.enable = 1 if val & 0x80 and self.length != 0 else 0

    def block(self, image):
        """Cycle 122 of one block: returns (target, data) of a transfer, or None."""
        if not self.enable:
            return None
        self.clock += 1
        if self.clock < DMA_DIVISOR[self.rate]:
            return None
        self.clock = 0
        if self.hold:
            return self.target, 0
        data = image.dma_byte(self.source)
        self.source = (self.source + (-1 if self.direction else 1)) & 0xFFFFF
        self.length = (self.length - 1) & 0xFFFFF
        if self.length == 0:
            if self.loop:
                self.source, self.length = self.shadow_source, self.shadow_length
            else:
                self.enable = 0
        return self.target, data


class Machine:
    """The player, the voice timer and the DMA unit on the cycle timeline (no sound): what the trace and the VGM log."""

    def __init__(self, image):
        self.image = image
        self.player = Player(image)
        self.dma = Dma()
        self.reset_writes = list(self.player.writes)
        self._feed(self.reset_writes)
        self.tick_index = 0
        self.next_irq = LINE_CYCLES * image.timer
        self.block = 0  # the next block the DMA has not run

    def _feed(self, writes):
        if not self.image.color:
            return
        for space, addr, val in writes:
            if space == SPACE_PORT and (0x4A <= addr <= 0x52):
                self.dma.write(addr, val)

    def state(self):
        start = self.tick_index * TICK_CYCLES
        return self.player.state(), self.next_irq - start, self.dma.state()

    def _run_blocks(self, end_cycle, transfers):
        """DMA over the blocks that end at or before end_cycle (block starts < end_cycle)."""
        if not (self.image.color and self.dma.enable):
            # Nothing happens until the next write: skip to the first block starting at or after end_cycle.
            self.block = max(self.block, (end_cycle + BLOCK_CYCLES - 1) // BLOCK_CYCLES)
            return
        while self.block * BLOCK_CYCLES < end_cycle:
            if self.dma.enable:
                t = self.dma.block(self.image)
                if t is not None:
                    transfers.append((self.block * BLOCK_CYCLES + DMA_TRANSFER_CYCLE, t[0], t[1]))
            self.block += 1

    def run_tick(self):
        """One tick. Returns (position before, start cycle, tick writes, [(cycle, writes)] interrupts,
        [(cycle, target, data)] DMA transfers)."""
        p = self.player
        position = (p.order, p.row, p.tick)
        start = self.tick_index * TICK_CYCLES
        end = start + TICK_CYCLES
        writes = list(p.tick_batch())
        events = [(start, writes)]
        irqs = []
        while self.next_irq < end:
            cycle = self.next_irq
            w = list(p.timer_batch())
            irqs.append((cycle, w))
            events.append((cycle, w))
            self.next_irq = cycle + LINE_CYCLES * p.timer
        transfers = []
        for cycle, w in events:
            self._run_blocks(cycle, transfers)
            self._feed(w)
        self._run_blocks(end, transfers)
        self.tick_index += 1
        return position, start, writes, irqs, transfers


def trace(image, ticks):
    """[(position, start cycle, writes, interrupts, transfers)] for `ticks` ticks from the start."""
    m = Machine(image)
    return m.reset_writes, [m.run_tick() for _ in range(ticks)]


def write_trace(image, ticks, path):
    reset, ticks_out = trace(image, ticks)
    with open(path, "w") as f:
        f.write("R\n")
        for space, addr, val in reset:
            f.write("W %d %04X %02X\n" % (space, addr, val))
        for k, (pos, start, writes, irqs, transfers) in enumerate(ticks_out):
            f.write("T %d %d %d %d %d\n" % (k, pos[0], pos[1], pos[2], start))
            for space, addr, val in writes:
                f.write("W %d %04X %02X\n" % (space, addr, val))
            # interrupts and transfers in cycle order (a transfer at block + 122 follows a write at its block start)
            items = [(cycle, 0, w) for cycle, w in irqs] + [(cycle, 1, (t, d)) for cycle, t, d in transfers]
            for cycle, kind, payload in sorted(items, key=lambda x: (x[0], x[1])):
                if kind == 0:
                    f.write("I %d\n" % cycle)
                    for space, addr, val in payload:
                        f.write("W %d %04X %02X\n" % (space, addr, val))
                else:
                    f.write("D %d %d %02X\n" % (cycle, payload[0], payload[1]))


# ---- VGM ------------------------------------------------------------------------------------------------------------

class VgmOut:
    def __init__(self):
        self.out = bytearray(0x100)
        self.sample = 0

    def wait_until(self, sample):
        n = sample - self.sample
        self.sample = sample
        while n > 0:
            if n == 735:
                self.out.append(0x62)
                n = 0
            elif n == 882:
                self.out.append(0x63)
                n = 0
            elif n <= 16:
                self.out.append(0x70 + n - 1)
                n = 0
            else:
                k = min(n, 65535)
                self.out += bytes((0x61, k & 0xFF, k >> 8))
                n -= k

    def writes(self, writes):
        for space, addr, val in writes:
            if space == SPACE_RAM:
                self.out += bytes((0xC6, addr >> 8 & 0xFF, addr & 0xFF, val))
            elif 0x80 <= addr <= 0x9F:
                self.out += bytes((0xBC, addr - 0x80, val))

    def event(self, cycle, writes):
        logged = [w for w in writes if w[0] == SPACE_RAM or 0x80 <= w[1] <= 0x9F]
        if not logged:
            return
        self.wait_until(vgm_sample(cycle))
        self.writes(logged)


def _tick_events(result):
    """The VGM events of one run_tick result in cycle order."""
    _, start, writes, irqs, transfers = result
    events = [(start, 0, writes)] + [(c, 1, w) for c, w in irqs] + \
             [(c, 2, [(SPACE_PORT, 0x89, d)]) for c, t, d in transfers if t == 0]
    events.sort(key=lambda e: (e[0], e[1]))
    return events


def vgm(image, max_ticks):
    """The VGM bytes (no GD3) and (total samples, loop offset, loop samples, ticks)."""
    if image.hyper_voice_notes():
        raise ImageError("VGM's WonderSwan chip has no Hyper Voice: export WAV", 0)
    m = Machine(image)
    results = []
    arrivals = []  # (tick index, state)
    loop_pair = None
    for k in range(max_ticks + 1):
        p = m.player
        if k < max_ticks and (p.order, p.row, p.tick) == (image.loop, 0, 0):
            s = m.state()
            for b, sb in arrivals:
                if sb == s:
                    loop_pair = (b, k)
                    break
            if loop_pair:
                break
            if len(arrivals) < 64:
                arrivals.append((k, s))
        if k == max_ticks:
            break
        results.append(m.run_tick())
    end_tick = loop_pair[1] if loop_pair else len(results)
    o = VgmOut()
    o.event(0, m.reset_writes)
    loop_offset = 0
    loop_start_sample = 0
    for k in range(end_tick):
        if loop_pair and k == loop_pair[0]:
            loop_start_sample = vgm_sample(k * TICK_CYCLES)
            o.wait_until(loop_start_sample)
            loop_offset = len(o.out)
        for cycle, _, writes in _tick_events(results[k]):
            o.event(cycle, writes)
    total = vgm_sample(end_tick * TICK_CYCLES)
    o.wait_until(total)
    o.out.append(0x66)
    b = o.out
    struct_put = lambda at, v: b.__setitem__(slice(at, at + 4), v.to_bytes(4, "little"))
    b[0:4] = b"Vgm "
    struct_put(0x04, len(b) - 4)
    struct_put(0x08, 0x171)
    struct_put(0x18, total)
    if loop_pair:
        struct_put(0x1C, loop_offset - 0x1C)
        struct_put(0x20, total - loop_start_sample)
    struct_put(0x34, 0xCC)
    struct_put(0xC0, VGM_CLOCK)
    return bytes(b), (total, loop_offset, total - loop_start_sample if loop_pair else 0, end_tick)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("image")
    ap.add_argument("--trace")
    ap.add_argument("--ticks", type=int, default=1500)
    ap.add_argument("--vgm")
    ap.add_argument("--max-ticks", type=int, default=45283)
    a = ap.parse_args(argv)
    with open(a.image, "rb") as f:
        image = Image(f.read())
    if a.trace:
        write_trace(image, a.ticks, a.trace)
    if a.vgm:
        data, info = vgm(image, a.max_ticks)
        with open(a.vgm, "wb") as f:
            f.write(data)
        print("%s: %d bytes, %d samples, loop offset %d, loop samples %d, %d ticks" % ((a.vgm, len(data)) + info))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
