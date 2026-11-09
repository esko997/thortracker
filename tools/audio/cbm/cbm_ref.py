#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's VIC-20 (VIC) and C16/Plus/4 (TED) player, written from the
specification in docs/AUDIO_ENGINE.md ("VIC-20 and C16/Plus/4: Thor Tracker's own player on the VIC and the TED")
independently of the C player (app/src/main/cpp/cbm/cbm_player.c). The host test compares the C player's register
writes with this one's batch by batch (ticks and TED timer interrupts, with their timeline cycles);
tools/audio/prepare_cbm.py embeds this reference's traces in app/src/test/cpp/cbm_test_song.h and
tools/audio/cbm/cbm_reference_check.py compares it with the native test's text traces.

    python3 tools/audio/cbm/cbm_ref.py IMAGE.cbimg --trace out.txt [--ticks 1500]

The text trace has one line per batch, the host test's cbm_trace_<name>.txt format:
    "R 0 0:"                                  the reset batch
    "T <k> <cycle> @<order>,<row>,<tick>:"    tick k (its first cycle, the position before it)
    "I <n> <cycle>:"                          TED timer interrupt n (counted from 0 since the reset), only if it wrote
each followed by " RR=VV" per write (two uppercase hex digits). Pure Python 3, no dependencies."""
import argparse
import math
import struct
import sys

# ---- chips, variants and clocks ---------------------------------------------------------------------------------------
CHIP_VIC, CHIP_TED = 0, 1
VIDEO_PAL, VIDEO_NTSC = 0, 1


def variant(chip, video):
    """CB_VARIANT: the index of the period table and of the timing tables below."""
    return chip * 2 + video


# Timeline clocks as exact quotients (Hz): VIC phi2, TED single clock; the TED's sound clock is a quarter of it.
CLOCK = ((17734475, 16), (315000000, 308), (17734475, 20), (315000000, 352))
# The clock the period table's frequencies divide (VIC phi2, TED sound clock) and the table's divider.
PERIOD_CLOCK = ((17734475, 16), (315000000, 308), (17734475, 80), (315000000, 1408))
PERIOD_DIVIDER = (64, 64, 2, 2)
FRAME_CYCLES = (71 * 312, 65 * 261, 57 * 312, 57 * 262)   # 22152, 16965, 17784, 14934

# ---- image layout ---------------------------------------------------------------------------------------------------
IMAGE_SIZE = 131072
MAGIC = b"TTCB"
LAYOUT = 1
HEADER_SIZE = 64
MAX_CHANNELS = 4
VIC_CHANNELS, TED_CHANNELS = 4, 3
MAX_ORDERS = 256
MAX_INSTRUMENTS = 64
MAX_SAMPLES = 64
MAX_EFFECTS = 2
MAX_MULTISPEED = 4
INSTRUMENT_SIZE = 32
SAMPLE_ENTRY_SIZE = 8
MAX_ENVELOPE = 255
MAX_SAMPLE_FRAMES = 65535
VIC_MAX_VOLUME, TED_MAX_VOLUME = 15, 8
DAC_MAX = 8
VIC_NOISE_CHANNEL = 3
TED_NOISE_CHANNEL, TED_DAC_CHANNEL = 1, 2

H_MAGIC, H_LAYOUT, H_CHIP, H_VIDEO, H_MULTISPEED, H_SPEED, H_INSTRUMENTS = 0, 4, 5, 6, 7, 8, 9
H_ORDERS, H_LOOP, H_SAMPLES, H_VOLUME, H_TIMER, H_DATA_SIZE = 10, 12, 14, 15, 16, 20
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_SAMPLES, OFF_DATA = 64, 320, 2368, 4416, 5120
DATA_CAPACITY = IMAGE_SIZE - OFF_DATA                       # 125952
I_FLAGS, I_WAVE, I_SAMPLE, I_ROOT, I_LOOP, I_LOOP_START, I_ENVELOPES = 0, 1, 2, 3, 4, 6, 8
ENVELOPE_BYTES = 6
ENV_VOLUME, ENV_ARPEGGIO, ENV_PITCH, ENV_WAVE, ENVELOPE_COUNT = 0, 1, 2, 3, 4
ENV_NONE = 0xFF
FLAG_DEFINED, FLAG_RELATIVE, KIND_MASK = 0x80, 0x04, 0x03
KIND_TONE, KIND_SAMPLE = 0, 1
WAVE_OFF, WAVE_SQUARE, WAVE_NOISE = 0, 1, 2
NOTE_OFF = 0xFE
ROOT_MIN, ROOT_MAX = 24, 108

PITCH_MAX = 8191                 # pitches are clamped to 0..8191 (1/64 semitone, 0 = C-0)
ACC_LIMIT = 8192                 # slide offset and relative pitch accumulator: +-8192
SEEK_TICKS = 3600

LETTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
FX = {c: i for i, c in enumerate(LETTERS)}
VIC_EFFECTS = frozenset(FX[c] for c in "01234ABDFGPSV")
TED_EFFECTS = VIC_EFFECTS | {FX["T"], FX["W"]}
ROW_START = frozenset(FX[c] for c in "BDFG")    # acted on at the row start, not when the row runs

# ---- lanes ----------------------------------------------------------------------------------------------------------
# Tonal lane ranges: the notes whose period, as the player rounds it, sounds within +-50 cents (contiguous from the
# lowest); prepare_cbm.py recomputes them from the tables (lane_range_from_tables) and fails on a difference.
VIC_RANGES = {VIDEO_PAL: ((13, 52), (25, 64), (37, 76), (0, 127)),
              VIDEO_NTSC: ((11, 48), (23, 60), (35, 72), (0, 127))}
TED_RANGES = ((33, 108), (33, 127), (24, 108))
VIC_SHIFT = (2, 1, 0)            # octave shift of the period table per VIC tone lane (bass, alto, soprano)


def lane_range(chip, video, channel):
    return VIC_RANGES[video][channel] if chip == CHIP_VIC else TED_RANGES[channel]


def channels_of(chip):
    return VIC_CHANNELS if chip == CHIP_VIC else TED_CHANNELS


def max_volume(chip):
    return VIC_MAX_VOLUME if chip == CHIP_VIC else TED_MAX_VOLUME


# ---- tables (docs: "Period tables", "Sample step") -----------------------------------------------------------------
def _q16(v, i):
    num, den = PERIOD_CLOCK[v]
    return math.floor(num / den / (PERIOD_DIVIDER[v] * 440 * 2 ** ((i / 64 - 57) / 12)) * 65536 + 0.5)


PERIOD_Q16 = tuple(tuple(_q16(v, i) for i in range(768)) for v in range(4))
STEP_Q16 = tuple(math.floor(65536 * 2 ** (i / 768) + 0.5) for i in range(768))
SINE = tuple(math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64))


def period(v, pitch, shift=0):
    """The chip period of a pitch (already clamped to 0..PITCH_MAX) for variant v and octave shift: VIC a in 1..128,
    TED reload P in 2..1024."""
    o, i = divmod(pitch + 768 * shift, 768)
    p = (PERIOD_Q16[v][i] + (1 << (15 + o))) >> (16 + o)
    return max(1, min(128, p)) if v < 2 else max(2, min(1024, p))


def sample_step(pitch, root):
    """Sample frames per timer interrupt in 16.16; the root note plays 65536."""
    o, i = divmod(pitch - 64 * root, 768)   # Python floors: o = floor(d / 768), i = d - 768 o in 0..767
    s = STEP_Q16[i] << o if o >= 0 else STEP_Q16[i] >> -o
    return max(1, min(1 << 24, s))


def vic_register(a):
    return (127 - a) & 127


def ted_register(p):
    return (1023 - p) & 0x3FF


def tick_cycle(k, v, multispeed):
    return k * FRAME_CYCLES[v] // multispeed


def timer_period(t):
    """TED timer 1 reload for DAC rate T: 64 + 4 T single clocks."""
    return 64 + 4 * t


def lane_range_from_tables(chip, video, channel):
    """The tonal range rule recomputed from the period tables: (lowest, highest) contiguous notes within +-50 cents."""
    v = variant(chip, video)
    num, den = PERIOD_CLOCK[v]
    shift = VIC_SHIFT[channel] if chip == CHIP_VIC else 0
    div = PERIOD_DIVIDER[v] * (1 << shift)

    def cents(n):
        p = period(v, 64 * n, shift)
        return 1200 * math.log2(num / den / (div * p) / (440 * 2 ** ((n - 57) / 12)))
    ok = [n for n in range(128) if abs(cents(n)) <= 50]
    lo = hi = ok[0]
    while hi + 1 < 128 and abs(cents(hi + 1)) <= 50:
        hi += 1
    return lo, hi


# ---- image ----------------------------------------------------------------------------------------------------------
class ImageError(ValueError):
    def __init__(self, reason, offset):
        super().__init__("%s (byte %d)" % (reason, offset))
        self.reason, self.offset = reason, offset


class Envelope:
    __slots__ = ("values", "loop", "release")

    def __init__(self, values, loop, release):
        self.values, self.loop, self.release = values, loop, release   # loop/release None = none


class Instrument:
    __slots__ = ("kind", "relative", "wave", "sample", "root", "loop", "loop_start", "env", "noise")


class Image:
    """A validated image: every check of the specification (the loader's), raising ImageError(reason, offset) on the
    first problem found; and the decoded tables. The reason strings are this reference's, not the C loader's."""

    def __init__(self, data, chip=None):
        data = bytes(data)
        if len(data) != IMAGE_SIZE:
            raise ImageError("image size", 0)
        self.data = data
        if data[0:4] != MAGIC:
            raise ImageError("magic", 0)
        if data[H_LAYOUT] != LAYOUT:
            raise ImageError("layout", H_LAYOUT)
        self.chip = data[H_CHIP]
        if self.chip not in (CHIP_VIC, CHIP_TED) or (chip is not None and self.chip != chip):
            raise ImageError("chip", H_CHIP)
        self.video = data[H_VIDEO]
        if self.video not in (VIDEO_PAL, VIDEO_NTSC):
            raise ImageError("video", H_VIDEO)
        self.variant = variant(self.chip, self.video)
        self.channels = channels_of(self.chip)
        self.multispeed = data[H_MULTISPEED]
        if not 1 <= self.multispeed <= MAX_MULTISPEED:
            raise ImageError("multispeed", H_MULTISPEED)
        self.speed = data[H_SPEED]
        if self.speed < 1:
            raise ImageError("speed", H_SPEED)
        self.instrument_count = data[H_INSTRUMENTS]
        if self.instrument_count > MAX_INSTRUMENTS:
            raise ImageError("instrument count", H_INSTRUMENTS)
        self.orders, self.loop = struct.unpack_from("<HH", data, H_ORDERS)
        if not 1 <= self.orders <= MAX_ORDERS:
            raise ImageError("order count", H_ORDERS)
        if self.loop >= self.orders:
            raise ImageError("loop order", H_LOOP)
        self.samples = data[H_SAMPLES]
        if self.samples > MAX_SAMPLES or (self.chip == CHIP_VIC and self.samples):
            raise ImageError("sample count", H_SAMPLES)
        self.max_volume = max_volume(self.chip)
        self.volume = data[H_VOLUME]
        if self.volume > self.max_volume:
            raise ImageError("volume", H_VOLUME)
        self.timer = data[H_TIMER]
        if self.chip == CHIP_VIC and self.timer:
            raise ImageError("timer on the VIC", H_TIMER)
        if any(data[17:20]):
            raise ImageError("header reserved bytes 17..19", 17)
        self.data_size = struct.unpack_from("<I", data, H_DATA_SIZE)[0]
        if self.data_size > DATA_CAPACITY:
            raise ImageError("data size", H_DATA_SIZE)
        if any(data[24:HEADER_SIZE]):
            raise ImageError("header reserved bytes 24..63", 24)
        if any(data[OFF_SAMPLES + SAMPLE_ENTRY_SIZE * MAX_SAMPLES:OFF_DATA]):
            raise ImageError("reserved bytes before the data region", OFF_SAMPLES + SAMPLE_ENTRY_SIZE * MAX_SAMPLES)
        self.rows, self.patterns = [], []
        for o in range(MAX_ORDERS):
            at = OFF_ORDERS + 2 * MAX_CHANNELS * o
            entry = list(struct.unpack_from("<4H", data, at))
            if o < self.orders:
                if self.chip == CHIP_TED and entry[3]:
                    raise ImageError("TED channel 3 order entry", at + 6)
                self.rows.append(data[OFF_ROWS + o] or 256)
                self.patterns.append(entry)
                continue
            if data[OFF_ROWS + o]:
                raise ImageError("rows of an order past the count", OFF_ROWS + o)
            if any(entry):
                raise ImageError("patterns of an order past the count", at)
        self.sample_table = []
        for s in range(MAX_SAMPLES):
            at = OFF_SAMPLES + SAMPLE_ENTRY_SIZE * s
            offset, length = struct.unpack_from("<II", data, at)
            if s >= self.samples:
                if offset or length:
                    raise ImageError("sample entry past the count", at)
                continue
            if not 1 <= length <= MAX_SAMPLE_FRAMES:
                raise ImageError("sample length", at + 4)
            if offset > self.data_size or length > self.data_size - offset:
                raise ImageError("sample past the data size", at)
            for j in range(length):
                if data[OFF_DATA + offset + j] > DAC_MAX:
                    raise ImageError("sample frame above 8", OFF_DATA + offset + j)
            self.sample_table.append((offset, length))
        self._instruments = {}
        for slot in range(1, MAX_INSTRUMENTS + 1):
            self._check_instrument(slot)
        self._decoded = {}
        self.start_speed = self._check_patterns()

    def _check_instrument(self, slot):
        base = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        rec = self.data[base: base + INSTRUMENT_SIZE]
        if not any(rec):
            return
        if slot > self.instrument_count:
            raise ImageError("instrument past the count", base)
        flags = rec[I_FLAGS]
        kind = flags & KIND_MASK
        if not flags & FLAG_DEFINED or flags & ~(FLAG_DEFINED | FLAG_RELATIVE | KIND_MASK):
            raise ImageError("instrument flags", base)
        if kind > KIND_SAMPLE or (kind == KIND_SAMPLE and self.chip == CHIP_VIC):
            raise ImageError("instrument kind", base)
        wave_max = WAVE_SQUARE if self.chip == CHIP_VIC else WAVE_NOISE
        loop_start = struct.unpack_from("<H", rec, I_LOOP_START)[0]
        if kind == KIND_TONE:
            if not WAVE_SQUARE <= rec[I_WAVE] <= wave_max:
                raise ImageError("instrument wave", base + I_WAVE)
            if rec[I_SAMPLE] or rec[I_ROOT] or rec[I_LOOP] or loop_start:
                raise ImageError("sample fields of a tone", base + I_SAMPLE)
        else:
            if rec[I_WAVE]:
                raise ImageError("wave byte of a sample", base + I_WAVE)
            if rec[I_SAMPLE] >= self.samples:
                raise ImageError("instrument sample", base + I_SAMPLE)
            if not ROOT_MIN <= rec[I_ROOT] <= ROOT_MAX:
                raise ImageError("root note", base + I_ROOT)
            if rec[I_LOOP] > 1:
                raise ImageError("loop flag", base + I_LOOP)
            length = self.sample_table[rec[I_SAMPLE]][1]
            if (loop_start >= length) if rec[I_LOOP] else loop_start:
                raise ImageError("loop start", base + I_LOOP_START)
        if rec[5]:
            raise ImageError("instrument reserved byte 5", base + 5)
        ins = Instrument()
        ins.kind, ins.relative = kind, bool(flags & FLAG_RELATIVE)
        ins.wave, ins.sample, ins.root = rec[I_WAVE], rec[I_SAMPLE], rec[I_ROOT]
        ins.loop, ins.loop_start = bool(rec[I_LOOP]), loop_start
        ins.env = []
        for k in range(ENVELOPE_COUNT):
            at = I_ENVELOPES + ENVELOPE_BYTES * k
            offset = struct.unpack_from("<H", rec, at)[0]
            length, loop, release, zero = rec[at + 2], rec[at + 3], rec[at + 4], rec[at + 5]
            if zero:
                raise ImageError("envelope reserved byte", base + at + 5)
            if length == 0:
                if offset or loop != ENV_NONE or release != ENV_NONE:
                    raise ImageError("empty envelope fields", base + at)
                ins.env.append(None)
                continue
            if kind == KIND_SAMPLE and k in (ENV_VOLUME, ENV_WAVE):
                raise ImageError("volume or wave envelope on a sample", base + at + 2)
            if loop != ENV_NONE and loop >= length:
                raise ImageError("envelope loop", base + at + 3)
            if release != ENV_NONE and not 1 <= release < length:
                raise ImageError("envelope release", base + at + 4)
            if loop != ENV_NONE and release != ENV_NONE and release <= loop:
                raise ImageError("envelope release before its loop", base + at + 4)
            if offset + length > self.data_size:
                raise ImageError("envelope past the data size", base + at)
            raw = self.data[OFF_DATA + offset: OFF_DATA + offset + length]
            values = [b - 256 if b > 127 else b for b in raw] if k in (ENV_ARPEGGIO, ENV_PITCH) else list(raw)
            for j, v in enumerate(values):
                if k == ENV_VOLUME and v > self.max_volume:
                    raise ImageError("volume envelope value", OFF_DATA + offset + j)
                if k == ENV_ARPEGGIO and not -64 <= v <= 63:
                    raise ImageError("arpeggio envelope value", OFF_DATA + offset + j)
                if k == ENV_WAVE and v > wave_max:
                    raise ImageError("wave envelope value", OFF_DATA + offset + j)
            ins.env.append(Envelope(values, None if loop == ENV_NONE else loop,
                                    None if release == ENV_NONE else release))
        ins.noise = kind == KIND_TONE and (ins.wave == WAVE_NOISE or (
            ins.env[ENV_WAVE] is not None and WAVE_NOISE in ins.env[ENV_WAVE].values))
        self._instruments[slot] = ins

    def allowed(self, ins, channel):
        """The placement rules: may instrument `ins` play on `channel`?"""
        if ins is None:
            return False
        if self.chip == CHIP_VIC:
            return ins.kind == KIND_TONE
        if channel == TED_DAC_CHANNEL:
            return ins.kind == KIND_SAMPLE
        return ins.kind == KIND_TONE and (channel == TED_NOISE_CHANNEL or not ins.noise)

    def _effect_problem(self, channel, code, param):
        known = VIC_EFFECTS if self.chip == CHIP_VIC else TED_EFFECTS
        if code not in known:
            return "unknown effect code"
        if code == FX["B"] and param >= self.orders:
            return "Bxx past the last order"
        if code == FX["F"] and param == 0:
            return "F00"
        if code == FX["V"] and param > self.max_volume:
            return "Vxx above the chip's volume"
        if code == FX["W"]:
            if channel == TED_DAC_CHANNEL:
                return "Wxx on the DAC lane"
            if param > WAVE_NOISE:
                return "Wxx above 02"
            if param == WAVE_NOISE and channel != TED_NOISE_CHANNEL:
                return "W02 off voice 2"
        return None

    def _check_patterns(self):
        """Per order: every channel's offset inside the used data, then rows(o) rows decoded across the channels,
        every byte inside the used data and valid. Returns the speed each order starts with in sequential play."""
        data, size = self.data, self.data_size
        speeds, speed = [], self.speed
        for o in range(self.orders):
            speeds.append(speed)
            offsets, runs = list(self.patterns[o]), [0] * MAX_CHANNELS
            for c in range(self.channels):
                if offsets[c] >= size:
                    raise ImageError("pattern offset past the data size", OFF_ORDERS + 2 * (MAX_CHANNELS * o + c))
            for _ in range(self.rows[o]):
                for c in range(self.channels):
                    if runs[c]:
                        runs[c] -= 1
                        continue
                    at = offsets[c]
                    if at >= size:
                        raise ImageError("pattern past the data size", OFF_DATA + at)
                    m = data[OFF_DATA + at]
                    at += 1
                    if m & 0x80:
                        runs[c], offsets[c] = m & 0x7F, at
                        continue
                    effects = (m >> 2) & 3
                    if m == 0 or m & 0x70:
                        raise ImageError("row mask byte", OFF_DATA + at - 1)
                    if at + (m & 1) + ((m >> 1) & 1) + 2 * effects > size:
                        raise ImageError("row past the data size", OFF_DATA + at)
                    if m & 1:
                        note = data[OFF_DATA + at]
                        lo, hi = lane_range(self.chip, self.video, c)
                        if note != NOTE_OFF and not lo <= note <= hi:
                            raise ImageError("note outside the lane's range", OFF_DATA + at)
                        at += 1
                    if m & 2:
                        if not self.allowed(self.instrument(data[OFF_DATA + at]), c):
                            raise ImageError("instrument undefined or not allowed on the lane", OFF_DATA + at)
                        at += 1
                    for _ in range(effects):
                        code, param = data[OFF_DATA + at], data[OFF_DATA + at + 1]
                        why = "effect code above Z" if code > 35 else self._effect_problem(c, code, param)
                        if why:
                            raise ImageError(why, OFF_DATA + at)
                        if code == FX["F"]:
                            speed = param
                        at += 2
                    offsets[c] = at
        return speeds

    def instrument(self, slot):
        return self._instruments.get(slot)

    def sample_frame(self, s, f):
        return self.data[OFF_DATA + self.sample_table[s][0] + f]

    def pattern_rows(self, order, channel):
        """Every row of (order, channel): None (empty) or (note, slot, [(code, param)])."""
        offset, n = self.patterns[order][channel], self.rows[order]
        key = (offset, n)
        if key in self._decoded:
            return self._decoded[key]
        data, rows, at = self.data, [], OFF_DATA + offset
        while len(rows) < n:
            m = data[at]
            at += 1
            if m & 0x80:
                rows.extend([None] * min((m & 0x7F) + 1, n - len(rows)))
                continue
            note, slot, effects = None, 0, []
            if m & 1:
                note = data[at]
                at += 1
            if m & 2:
                slot = data[at]
                at += 1
            for _ in range((m >> 2) & 3):
                effects.append((data[at], data[at + 1]))
                at += 2
            rows.append((note, slot, effects))
        self._decoded[key] = rows
        return rows


# ---- player ---------------------------------------------------------------------------------------------------------
class Channel:
    def __init__(self):
        self.row = None
        self.delay = None          # tick of a delayed row (Gxx)
        self.cut = None            # Sxx tick
        self.instrument = 0        # the slot rows set
        self.latched = 0           # the slot of the sounding note
        self.sounding = False
        self.fresh = False
        self.released = False
        self.note = 0              # the keyed note (VIC noise rate)
        self.pos = 0               # DAC sample position, 16.16 frames
        self.step = 0              # DAC frames per interrupt, 16.16 (the last update's)
        self.env_idx = [0] * ENVELOPE_COUNT
        self.rel_acc = 0
        self.base = self.target = 0
        self.fine = 0
        self.arp = self.arp_step = 0
        self.slide_mode = None     # "up", "down", "porta"
        self.slide_speed = 0
        self.slide = 0
        self.vib_speed = self.vib_depth = self.vib_phase = 0
        self.wave_override = -1
        self.wave = WAVE_OFF       # the wave of the last update (TED control step)


class Player:
    def __init__(self, image):
        self.img = image
        self.reset()

    # -- writes --
    def write(self, reg, val):
        if self.shadow.get(reg) != val:
            self.shadow[reg] = val
            self.batch.append((reg, val))

    def reset(self):
        img = self.img
        self.batch = []
        self.ch = [Channel() for _ in range(img.channels)]
        self.m8 = img.volume * 8           # the shared master level x 8 (Vxx, Axy)
        self.vslide = 0                    # shared Axy parameter
        self.timer = img.timer             # shared DAC rate T
        self.owner = -1                    # master volume envelope owner lane
        self.owner_value = 0               # the owner's volume value this tick
        self.vol_out = img.volume          # the volume of the last control step
        self.dac = False                   # D/A mode held by the DAC lane
        self.dac_level = 0
        self.speed = img.speed
        self.order = self.row = self.tick_in_row = 0
        self.jump = None
        self.shadow = {}
        regs = (0x0A, 0x0B, 0x0C, 0x0D) if img.chip == CHIP_VIC else (0x0E, 0x12, 0x0F, 0x10)
        for reg in regs:
            self.shadow[reg] = 0
            self.batch.append((reg, 0))
        control = 0x0E if img.chip == CHIP_VIC else 0x11
        self.shadow[control] = img.volume
        self.batch.append((control, img.volume))

    # -- notes --
    def key_on(self, c, note):
        t = self.ch[c]
        ins = self.img.instrument(t.instrument)
        if not self.img.allowed(ins, c):
            return
        t.latched = t.instrument
        t.env_idx = [0] * ENVELOPE_COUNT
        t.released = False
        t.rel_acc = 0
        t.note = note
        t.base = t.target = note * 64
        t.slide = 0
        t.arp_step = 0
        t.vib_phase = 0
        t.sounding = True
        t.fresh = True
        if ins.env[ENV_VOLUME] is not None:
            self.owner = c
        elif self.owner == c:
            self.owner = -1
        if self.img.chip == CHIP_TED and c == TED_DAC_CHANNEL:
            t.pos = 0x10000

    def control_value(self):
        """TED $11 as the control step composes it, with the volume of the last control step."""
        if self.dac:
            return 0x80 | 0x30 | self.dac_level
        v = self.vol_out
        t1, t2 = self.ch[0], self.ch[1]
        if t1.sounding and t1.wave == WAVE_SQUARE:
            v |= 0x10
        if t2.sounding:
            if t2.wave == WAVE_SQUARE:
                v |= 0x20
            elif t2.wave == WAVE_NOISE:
                v |= 0x40
        return v

    def stop(self, c):
        t = self.ch[c]
        t.sounding = False
        if self.owner == c:
            self.owner = -1
        if self.img.chip == CHIP_VIC:
            reg = 0x0A + c
            self.write(reg, self.shadow[reg] & 0x7F)
        elif c == TED_DAC_CHANNEL:
            self.dac = False

    def off(self, c):
        t = self.ch[c]
        if not t.sounding:
            return
        ins = self.img.instrument(t.latched)
        if any(ins.env[k] is not None and ins.env[k].release is not None for k in (ENV_VOLUME, ENV_WAVE)):
            t.released = True
            for k, e in enumerate(ins.env):
                if e is not None and e.release is not None:
                    t.env_idx[k] = e.release
        else:
            self.stop(c)

    def noise_lane(self, c):
        return self.img.chip == CHIP_VIC and c == VIC_NOISE_CHANNEL

    def run_row(self, c):
        t = self.ch[c]
        note, slot, effects = t.row if t.row else (None, 0, ())
        playing = note is not None and note != NOTE_OFF
        if playing:
            t.arp = 0
            t.fine = 0
            t.wave_override = -1
        if slot:
            t.instrument = slot
        mx = self.img.max_volume
        for code, param in effects:
            if code in ROW_START:
                continue
            if code == FX["0"]:
                t.arp, t.arp_step = param, 0
            elif code in (FX["1"], FX["2"], FX["3"]):
                t.slide_mode = {FX["1"]: "up", FX["2"]: "down", FX["3"]: "porta"}[code] if param else None
                t.slide_speed = param
            elif code == FX["4"]:
                t.vib_speed, t.vib_depth = param >> 4, param & 15
            elif code == FX["A"]:
                self.vslide = param
            elif code == FX["P"]:
                t.fine = param - 0x80
            elif code == FX["S"]:
                t.cut = param
            elif code == FX["T"]:
                self.timer = param
            elif code == FX["V"]:
                self.m8 = min(param, mx) * 8
            elif code == FX["W"]:
                t.wave_override = param
        if note == NOTE_OFF:
            self.off(c)
        elif playing:
            if t.slide_mode == "porta" and t.sounding and not self.noise_lane(c):
                t.target = note * 64
            else:
                self.key_on(c, note)

    @staticmethod
    def env_value(t, ins, k):
        e = ins.env[k]
        if e is None:
            return None
        return e.values[min(t.env_idx[k], len(e.values) - 1)]

    @staticmethod
    def advance_envelopes(t, ins):
        for k in range(ENVELOPE_COUNT):
            e = ins.env[k]
            if e is None:
                continue
            n = len(e.values)
            idx = min(t.env_idx[k], n - 1)
            if t.released and e.release is not None:
                t.env_idx[k] = min(idx + 1, n - 1)
            else:
                held = e.release if e.release is not None else n
                nxt = idx + 1
                if nxt >= held:
                    nxt = e.loop if e.loop is not None else held - 1
                t.env_idx[k] = nxt

    def update(self, c):
        img = self.img
        t = self.ch[c]
        ins = img.instrument(t.latched)
        arp = (0, t.arp >> 4, t.arp & 15)[t.arp_step]
        a = self.env_value(t, ins, ENV_ARPEGGIO)
        semitones = arp + (a or 0)
        pv = self.env_value(t, ins, ENV_PITCH)
        if pv is None:
            pitch_value = 0
        elif ins.relative:
            t.rel_acc = max(-ACC_LIMIT, min(ACC_LIMIT, t.rel_acc + pv))
            pitch_value = t.rel_acc
        else:
            pitch_value = pv
        vib = (SINE[t.vib_phase] * t.vib_depth) >> 4     # Python's >> floors: the spec's arithmetic shift
        pitch = max(0, min(PITCH_MAX, t.base + 64 * semitones + t.fine + t.slide + vib + pitch_value))
        we = self.env_value(t, ins, ENV_WAVE)
        if we is not None:
            wave = we
        elif t.wave_override >= 0:
            wave = t.wave_override
        else:
            wave = ins.wave
        t.wave = wave
        if c == self.owner:
            vv = self.env_value(t, ins, ENV_VOLUME)
            self.owner_value = vv if vv is not None else 0
        gate = 0x80 if wave >= WAVE_SQUARE else 0
        if img.chip == CHIP_VIC:
            if c == VIC_NOISE_CHANNEL:
                k = max(0, min(127, t.note + semitones))
                self.write(0x0D, gate | ((k + 127) & 127))
            else:
                self.write(0x0A + c, gate | vic_register(period(img.variant, pitch, VIC_SHIFT[c])))
        elif c == TED_DAC_CHANNEL:
            t.step = sample_step(pitch, ins.root)
            if t.fresh:
                self.dac = True
                self.dac_level = img.sample_frame(ins.sample, 0)
                self.write(0x11, 0xB0 | self.dac_level)
        else:
            x = ted_register(period(img.variant, pitch))
            lo, hi = (0x0E, 0x12) if c == 0 else (0x0F, 0x10)
            self.write(lo, x & 0xFF)
            self.write(hi, x >> 8)
        t.fresh = False
        self.advance_envelopes(t, ins)
        if t.arp:
            t.arp_step = (t.arp_step + 1) % 3
        if t.slide_mode == "up":
            t.slide = min(ACC_LIMIT, t.slide + t.slide_speed)
        elif t.slide_mode == "down":
            t.slide = max(-ACC_LIMIT, t.slide - t.slide_speed)
        elif t.slide_mode == "porta":
            if t.base < t.target:
                t.base = min(t.target, t.base + t.slide_speed)
            elif t.base > t.target:
                t.base = max(t.target, t.base - t.slide_speed)
        if t.vib_speed and t.vib_depth:
            t.vib_phase = (t.vib_phase + t.vib_speed) % 64

    def control(self):
        img = self.img
        mx = img.max_volume
        m = self.m8 >> 3
        self.vol_out = max(0, self.owner_value + m - mx) if self.owner >= 0 else m
        if img.chip == CHIP_VIC:
            self.write(0x0E, self.vol_out)
        else:
            self.write(0x11, self.control_value())
        if self.vslide:
            self.m8 = max(0, min(mx * 8, self.m8 + (self.vslide >> 4) - (self.vslide & 15)))

    # -- sequencing --
    def begin_row(self):
        img = self.img
        for c in range(img.channels):
            self.ch[c].row = img.pattern_rows(self.order, c)[self.row]
        jump_order = jump_row = None
        for c in range(img.channels):
            row = self.ch[c].row
            for code, param in (row[2] if row else ()):
                if code == FX["F"]:
                    self.speed = param
                elif code == FX["B"]:
                    jump_order = param
                elif code == FX["D"]:
                    jump_row = param
        if jump_order is None and jump_row is None:
            self.jump = None
        else:
            if jump_order is None:
                jump_order = self.order + 1 if self.order + 1 < img.orders else img.loop
            self.jump = (jump_order, jump_row or 0)
        for c in range(img.channels):
            t = self.ch[c]
            t.cut = None
            t.delay = None
            g = 0
            for code, param in (t.row[2] if t.row else ()):
                if code == FX["G"]:
                    g = param
            if g:
                t.delay = g
            else:
                self.run_row(c)

    def tick(self):
        """One player tick; returns its batch [(reg, val)]."""
        img = self.img
        self.batch = []
        if self.tick_in_row == 0:
            self.begin_row()
        else:
            for c in range(img.channels):
                t = self.ch[c]
                if t.delay is not None and t.delay == self.tick_in_row:
                    t.delay = None
                    self.run_row(c)
        for c in range(img.channels):
            if self.ch[c].cut == self.tick_in_row:
                self.off(c)
        for c in range(img.channels):
            if self.ch[c].sounding:
                self.update(c)
        self.control()
        self.tick_in_row += 1
        if self.tick_in_row >= self.speed:
            self.tick_in_row = 0
            if self.jump is not None:
                o, r = self.jump
                self.order, self.row = o, min(r, img.rows[o] - 1)
                self.jump = None
            else:
                self.row += 1
                if self.row >= img.rows[self.order]:
                    self.row = 0
                    self.order = self.order + 1 if self.order + 1 < img.orders else img.loop
            if (self.order, self.row) == (img.loop, 0):
                self.speed = img.start_speed[img.loop]
        return self.batch

    def timer_interrupt(self):
        """One TED timer interrupt; returns its batch."""
        self.batch = []
        if self.img.chip != CHIP_TED or not self.dac:
            return self.batch
        t = self.ch[TED_DAC_CHANNEL]
        ins = self.img.instrument(t.latched)
        length = self.img.sample_table[ins.sample][1]
        f = t.pos >> 16
        if f >= length:
            if ins.loop:
                span = (length - ins.loop_start) << 16
                t.pos = (ins.loop_start << 16) + (t.pos - (length << 16)) % span
                f = t.pos >> 16
            else:
                t.sounding = False
                self.dac = False
                self.write(0x11, self.control_value())
                return self.batch
        self.dac_level = self.img.sample_frame(ins.sample, f)
        self.write(0x11, 0xB0 | self.dac_level)
        t.pos += t.step
        return self.batch

    def position(self):
        return self.order, self.row, self.tick_in_row


class Timeline:
    """The player and the TED DAC timer on the timeline clock, as the machine runs them: tick k's batch at its first
    cycle, then the interrupts with start <= cycle < the next tick's start, each reloading with the player's T at that
    moment (the first R cycles after the reset)."""

    def __init__(self, image):
        self.img = image
        self.p = Player(image)
        self.reset_batch = list(self.p.batch)
        self.k = 0
        self.irqs = 0
        self.next_irq = timer_period(self.p.timer) if image.chip == CHIP_TED else None

    def tick(self):
        """(position before, start cycle, writes, [(n, cycle, writes)] of the interrupts that wrote)."""
        img = self.img
        start, end = (tick_cycle(self.k, img.variant, img.multispeed),
                      tick_cycle(self.k + 1, img.variant, img.multispeed))
        pos = self.p.position()
        writes = list(self.p.tick())
        irqs = []
        while self.next_irq is not None and self.next_irq < end:
            w = list(self.p.timer_interrupt())
            if w:
                irqs.append((self.irqs, self.next_irq, w))
            self.irqs += 1
            self.next_irq += timer_period(self.p.timer)
        self.k += 1
        return pos, start, writes, irqs


def trace(image, ticks):
    """(reset batch, [Timeline.tick() results]) for `ticks` ticks after the reset."""
    tl = Timeline(image)
    return tl.reset_batch, [tl.tick() for _ in range(ticks)]


def format_writes(writes):
    return "".join(" %02X=%02X" % w for w in writes)


def trace_lines(image, ticks):
    """The text trace (the host test's cbm_trace_<name>.txt), one string per line."""
    reset, ticks_out = trace(image, ticks)
    lines = ["R 0 0:" + format_writes(reset)]
    for k, (pos, start, writes, irqs) in enumerate(ticks_out):
        lines.append("T %d %d @%d,%d,%d:" % (k, start, pos[0], pos[1], pos[2]) + format_writes(writes))
        for n, cycle, w in irqs:
            lines.append("I %d %d:" % (n, cycle) + format_writes(w))
    return lines


def trace_bytes(image, ticks):
    """The binary trace embedded in cbm_test_song.h (format in app/src/test/cpp/cbm_machine_test.c's fixture notes)."""
    reset, ticks_out = trace(image, ticks)
    out = bytearray()

    def batch(kind, cycle, writes, pos=None):
        out.append(ord(kind))
        out.extend(struct.pack("<I", cycle))
        if pos is not None:
            out.extend(struct.pack("<HBB", *pos))
        out.append(len(writes))
        for reg, val in writes:
            out.extend((reg, val))
    batch("R", 0, reset)
    for pos, start, writes, irqs in ticks_out:
        batch("T", start, writes, pos)
        for _, cycle, w in irqs:
            batch("I", cycle, w)
    return bytes(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("image")
    ap.add_argument("--trace")
    ap.add_argument("--ticks", type=int, default=1500)
    args = ap.parse_args(argv)
    with open(args.image, "rb") as f:
        image = Image(f.read())
    if args.trace:
        with open(args.trace, "w") as f:
            for line in trace_lines(image, args.ticks):
                f.write(line + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
