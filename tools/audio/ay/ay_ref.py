#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's AY-3-8910 / YM2149 player ("ay-zx128", "ay-msx", "ay-atari-st", "ay-cpc"),
written from the specification in docs/AUDIO_ENGINE.md ("AY-3-8910 / YM2149": "Image layout", "Pitch", "Player",
"Shared noise and envelope", "Effects", "Position, seek and live edits", "VGM export") independently of the C player
(app/src/main/cpp/ay/). The host test compares the C player's register writes with this one's, tick by tick, and the
C loader's rejection reasons with this loader's (strings and byte offsets agreed with ay_image.c).

    python3 tools/audio/ay/ay_ref.py IMAGE.bin --trace out.txt [--ticks 1500]
    python3 tools/audio/ay/ay_ref.py IMAGE.bin --vgm out.vgm [--max-ticks N]

Pure Python 3, no dependencies. Also defines the period tables AY_DIV and the vibrato sine AY_SINE (generated into
app/src/main/cpp/generated/ay_tables.h by tools/audio/prepare_ay.py).

Writes are (register, value) pairs for R0-R13. A trace line (the host test writes the same file) is
"order row tick:" (the position before the tick) followed by " RR=VV" per write (two-digit upper-case hex).

Spec readings chosen where the text leaves room (agreed with the native player):
  - "last R13 written" is none after reset (the first envelope owner always writes R13); ownership "moved" means the
    owner differs from the previous tick's owner (none included).
  - "phase on" means exactly on: a released note ignores OFF/Sxx, and a portamento note on a released channel keys on.
  - A note whose key-on does nothing (no or undefined instrument) is a row without a key-on: its note settings apply
    to the current note at once.
  - Exx on a row without a key-on sets restart pending iff the shape changes; Hxx/Ixx switch to manual (J kept), Jxx
    to auto; the sync flag stays the instrument's and applies in auto mode only.
  - apply_image (relocate) follows the OPL3/YM2612 rules as ym_player.c's ymp_relocate implements them."""
import argparse
import math
import struct
import sys

# ---- variants (docs/AUDIO_ENGINE.md, "Variants") ------------------------------------------------------------------

ZX128, MSX, ATARI_ST, CPC = 0, 1, 2, 3
VARIANTS = (ZX128, MSX, ATARI_ST, CPC)
KEYS = ("zx128", "msx", "atari-st", "cpc")
NAMES = ("ZX Spectrum 128 (AY-3-8912)", "MSX (AY-3-8910)", "Atari ST (YM2149F)", "Amstrad CPC (AY-3-8912)")
ENGINE_IDS = ("ay-zx128", "ay-msx", "ay-atari-st", "ay-cpc")
MASTER_HZ = (3546900, 10738635, 32084988, 16000000)
AY_DIV = (2, 6, 16, 16)                  # chip clock = master / AY_DIV
FRAME_DIV = (70908, 179208, 641024, 319488)  # master clocks per tick
IO_BITS = (0xC0, 0x80, 0xC0, 0x00)       # R7 bits 7-6
YM_CHIP = (False, False, True, False)    # YM2149: 32-level DAC and envelope
STEREO = (0, 0, 0, 1)                    # 1 = the CPC's stereo socket (left A + B, right B + C); else mono
VGM_TYPE = (0x01, 0x00, 0x10, 0x01)
VGM_CLOCK = (1773450, 1789772, 2005312, 1000000)
VGM_RATE = (50, 60, 50, 50)
VGM_SAMPLE_RATE = 44100

UNITS_PER_SEMITONE = 64
UNITS_PER_OCTAVE = 768
PITCH_MAX = 8 * UNITS_PER_OCTAVE - 1
C0_HZ = 440.0 * 2 ** (-57 / 12)
TONE_PERIOD_MAX = 4095
NOISE_PERIOD_MAX = 31
ENV_PERIOD_MAX = 65535
NOTE_OFF = 0xFE
NOTES = 96
LETTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
FX = {c: i for i, c in enumerate(LETTERS)}
SETTINGS = {FX[c] for c in "EHIJVW"}     # note settings, collected per row

# ---- image layout -------------------------------------------------------------------------------------------------

IMAGE_SIZE = 65536
MAGIC = b"TTAY"
LAYOUT = 1
HEADER_SIZE = 64
OFF_ROWS, OFF_ORDERS, OFF_RESERVED, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 1856, 2048, 4096
INSTRUMENT_SIZE = 32
DATA_CAPACITY = IMAGE_SIZE - OFF_DATA    # 61440
CHANNELS = 3
MAX_ORDERS = 256
MAX_INSTRUMENTS = 64
MAX_EFFECTS = 4
ENV_HEADER = 6
ENV_MAX_LENGTH = 256
NO_ENVELOPE = 0xFFFF
FLAG_DEFINED, FLAG_PITCH_RELATIVE, FLAG_AUTO_PERIOD, FLAG_SYNC = 0x80, 0x01, 0x02, 0x04
I_FLAGS, I_SHAPE, I_OCTAVE, I_PERIOD, I_ENV, I_SWEEP_START, I_SWEEP_END, I_SWEEP_SPEED = 0, 1, 2, 4, 6, 16, 17, 18
ENV_KINDS = ("volume", "arpeggio", "pitch", "mixer", "noise")
ENV_RANGE = ((0, 15), (-64, 63), (-64, 63), (0, 3), (0, 31))


# ---- tables -------------------------------------------------------------------------------------------------------

def clock_hz(variant):
    return MASTER_HZ[variant] / AY_DIV[variant]


def div_table(variant):
    """256 x the tone period of C + f/768 octave at octave 0: floor(256 clock / (16 C0 2^(f/768)) + 0.5)."""
    clock = clock_hz(variant)
    return [math.floor(256 * clock / (16 * C0_HZ * 2 ** (f / UNITS_PER_OCTAVE)) + 0.5)
            for f in range(UNITS_PER_OCTAVE)]


def sine_table():
    return [math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64)]


DIV = [div_table(v) for v in VARIANTS]
SINE = sine_table()


def clamp(x, lo, hi):
    return lo if x < lo else hi if x > hi else x


def tone_period(variant, pitch):
    """TP(pitch) = clamp((DIV[f] + (128 << o)) >> (8 + o), 1, 4095)."""
    o, f = divmod(pitch, UNITS_PER_OCTAVE)
    return clamp((DIV[variant][f] + (128 << o)) >> (8 + o), 1, TONE_PERIOD_MAX)


def triangle(shape):
    return 1 if shape in (10, 14) else 0


def auto_envelope_period(variant, pitch, octave, shape):
    """EP(pitch, J, shape) = clamp((DIV[f] + (1 << (s - 1))) >> s, 1, 65535), s = 8 + o + 4 + t + J: the envelope's
    audible cycle sounds the note J octaves up (s >= 4 for J >= -8)."""
    o, f = divmod(pitch, UNITS_PER_OCTAVE)
    s = 8 + o + 4 + triangle(shape) + octave
    return clamp((DIV[variant][f] + (1 << (s - 1))) >> s, 1, ENV_PERIOD_MAX)


def synced_tone_period(ep, octave, shape):
    """TP = EP 2^k, k = 4 + t + J (k < 0: rounded right shift), clamped 1..4095: the tone stays at the note."""
    k = 4 + triangle(shape) + octave
    tp = ep << k if k >= 0 else (ep + (1 << (-k - 1))) >> -k
    return clamp(tp, 1, TONE_PERIOD_MAX)


# Every reason ay_load_image reports (strings agreed with ay_image.c; prepare_ay.py checks the C file has exactly
# these). Order here: the loader's check order.
LOADER_REASONS = (
    "not an AY image (magic)", "unknown layout",
    "variant must be 0 (ZX Spectrum 128), 1 (MSX), 2 (Atari ST) or 3 (Amstrad CPC)",
    "image is for another AY variant", "speed must be 1..255", "header reserved byte 7", "order count must be 1..256",
    "loop order past the last order", "instrument count above 64", "header reserved byte 13",
    "data size above the region", "header reserved bytes 16..63", "rows of an order past the order count",
    "patterns of an order past the order count", "reserved bytes 1856..2047",
    "instrument record past the instrument count", "instrument flags", "sync needs the auto envelope period",
    "envelope shape must be 0 or 8..15", "auto octave outside -8..7", "auto octave on a manual-period instrument",
    "instrument reserved byte 3", "manual period on an auto-period instrument", "envelope outside the data size",
    "envelope length outside 1..256", "envelope loop past its end", "release point on a non-volume envelope",
    "volume release point needs a loop before it", "volume envelope value outside 0..15",
    "arpeggio or pitch envelope value outside -64..63", "mixer envelope value outside 0..3",
    "noise envelope value outside 0..31", "sweep start/end outside -96..96 semitones",
    "instrument reserved bytes 19..31", "pattern offset past the data size", "pattern runs past the data size",
    "row mask byte", "row runs past the data size", "note byte", "row names an instrument the image does not define",
    "effect code above Z", "Bxx jumps past the last order", "Cxx volume above 0F", "Exx shape must be 00 or 08..0F",
    "F00: speed must be 1..FF", "Jxx octave must be 00..07 or F8..FF", "Vxx mixer above 03",
    "Wxx noise period above 1F", "unknown effect code",
)


def tone_hz(variant, period):
    return clock_hz(variant) / (16 * period)


def envelope_hz(variant, period, shape):
    """Frequency of the envelope's audible cycle: 256 EP clocks per saw, 512 per triangle (period 0 acts as 1)."""
    return clock_hz(variant) / ((512 if triangle(shape) else 256) * max(1, period))


def lowest_note(variant):
    """The lowest note whose tone period fits 12 bits."""
    for n in range(NOTES):
        o, f = divmod(n * UNITS_PER_SEMITONE, UNITS_PER_OCTAVE)
        if (DIV[variant][f] + (128 << o)) >> (8 + o) <= TONE_PERIOD_MAX:
            return n
    raise AssertionError


assert [tone_period(v, 57 * 64) for v in VARIANTS] == [252, 254, 285, 142]
assert [lowest_note(v) for v in VARIANTS] == [9, 9, 11, 0]
assert tone_period(CPC, 0) == 3822 and tone_period(CPC, 95 * 64) == 16
assert FRAME_DIV[CPC] == 2496 * 8 * AY_DIV[CPC]  # the CPC's tick is exactly 2496 chip steps


# ---- image --------------------------------------------------------------------------------------------------------

class ImageError(ValueError):
    """A rejected image: `problem` is ay_load_image's reason string, `offset` its byte (AyInfo.problem_offset)."""

    def __init__(self, problem, offset):
        super().__init__("%s (byte %d)" % (problem, offset))
        self.problem = problem
        self.offset = offset


def u16(data, at):
    return data[at] | data[at + 1] << 8


def s8(v):
    return v - 256 if v >= 128 else v


def effect_problem(orders, code, param):
    """None if (code, param) may stand in a row, else ay_image.c's reason."""
    if code > 35:
        return "effect code above Z"
    letter = LETTERS[code]
    if letter in "01234ADGHIPS":
        return None
    if letter == "B":
        return "Bxx jumps past the last order" if param >= orders else None
    if letter == "C":
        return "Cxx volume above 0F" if param > 0x0F else None
    if letter == "E":
        return "Exx shape must be 00 or 08..0F" if param != 0 and not 0x08 <= param <= 0x0F else None
    if letter == "F":
        return "F00: speed must be 1..FF" if param == 0 else None
    if letter == "J":
        return "Jxx octave must be 00..07 or F8..FF" if not (param <= 0x07 or param >= 0xF8) else None
    if letter == "V":
        return "Vxx mixer above 03" if param > 0x03 else None
    if letter == "W":
        return "Wxx noise period above 1F" if param > 0x1F else None
    return "unknown effect code"


class Image:
    """A loaded image (ay_load_image's checks in its order). `variant`: the engine's variant (None = any)."""

    def __init__(self, data, variant=None):
        b = bytes(data)
        if len(b) != IMAGE_SIZE:
            raise ValueError("an AY image is %d bytes, not %d" % (IMAGE_SIZE, len(b)))
        self.data = b

        def fail(problem, offset):
            raise ImageError(problem, offset)

        if b[0:4] != MAGIC:
            fail("not an AY image (magic)", 0)
        if b[4] != LAYOUT:
            fail("unknown layout", 4)
        self.variant = b[5]
        if self.variant > CPC:
            fail("variant must be 0 (ZX Spectrum 128), 1 (MSX), 2 (Atari ST) or 3 (Amstrad CPC)", 5)
        if variant is not None and variant >= 0 and self.variant != variant:
            fail("image is for another AY variant", 5)
        self.speed = b[6]
        if not self.speed:
            fail("speed must be 1..255", 6)
        if b[7]:
            fail("header reserved byte 7", 7)
        self.orders = u16(b, 8)
        if not 1 <= self.orders <= MAX_ORDERS:
            fail("order count must be 1..256", 8)
        self.loop = u16(b, 10)
        if self.loop >= self.orders:
            fail("loop order past the last order", 10)
        self.instrument_count = b[12]
        if self.instrument_count > MAX_INSTRUMENTS:
            fail("instrument count above 64", 12)
        if b[13]:
            fail("header reserved byte 13", 13)
        size = self.data_size = u16(b, 14)
        if size > DATA_CAPACITY:
            fail("data size above the region", 14)
        if any(b[16:HEADER_SIZE]):
            fail("header reserved bytes 16..63", 16)
        for o in range(self.orders, MAX_ORDERS):
            if b[OFF_ROWS + o]:
                fail("rows of an order past the order count", OFF_ROWS + o)
            at = OFF_ORDERS + 2 * CHANNELS * o
            if any(b[at: at + 2 * CHANNELS]):
                fail("patterns of an order past the order count", at)
        if any(b[OFF_RESERVED:OFF_INSTRUMENTS]):
            fail("reserved bytes 1856..2047", OFF_RESERVED)
        self.rows = [b[OFF_ROWS + o] or 256 for o in range(self.orders)]
        self.patterns = [[u16(b, OFF_ORDERS + 2 * (CHANNELS * o + c)) for c in range(CHANNELS)]
                         for o in range(self.orders)]
        self.instruments = {}
        for n in range(1, MAX_INSTRUMENTS + 1):
            self._instrument(n, fail)
        self.start_speed = self._patterns(fail)
        self._decoded = {}

    def _envelope(self, field, kind, fail):
        b, size = self.data, self.data_size
        off = u16(b, field)
        if off == NO_ENVELOPE:
            return None
        if off + ENV_HEADER > size:
            fail("envelope outside the data size", field)
        e = OFF_DATA + off
        length, loop, release = u16(b, e), u16(b, e + 2), u16(b, e + 4)
        if not 1 <= length <= ENV_MAX_LENGTH:
            fail("envelope length outside 1..256", e)
        if off + ENV_HEADER + length > size:
            fail("envelope outside the data size", e)
        if loop != NO_ENVELOPE and loop >= length:
            fail("envelope loop past its end", e + 2)
        if release != NO_ENVELOPE:
            if kind != 0:
                fail("release point on a non-volume envelope", e + 4)
            if loop == NO_ENVELOPE or release <= loop or release >= length:
                fail("volume release point needs a loop before it", e + 4)
        lo, hi = ENV_RANGE[kind]
        values = []
        for i in range(length):
            v = s8(b[e + ENV_HEADER + i])
            if not lo <= v <= hi:
                fail(("volume envelope value outside 0..15", "arpeggio or pitch envelope value outside -64..63",
                      "arpeggio or pitch envelope value outside -64..63", "mixer envelope value outside 0..3",
                      "noise envelope value outside 0..31")[kind], e + ENV_HEADER + i)
            values.append(v)
        return (values, None if loop == NO_ENVELOPE else loop, None if release == NO_ENVELOPE else release)

    def _instrument(self, n, fail):
        base = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (n - 1)
        p = self.data[base: base + INSTRUMENT_SIZE]
        if not any(p):
            return
        if n > self.instrument_count:
            fail("instrument record past the instrument count", base)
        flags = p[I_FLAGS]
        if not flags & FLAG_DEFINED or flags & 0x78:
            fail("instrument flags", base)
        auto = bool(flags & FLAG_AUTO_PERIOD)
        if flags & FLAG_SYNC and not auto:
            fail("sync needs the auto envelope period", base)
        shape = p[I_SHAPE]
        if shape != 0 and not 8 <= shape <= 15:
            fail("envelope shape must be 0 or 8..15", base + I_SHAPE)
        octave = s8(p[I_OCTAVE])
        if auto and not -8 <= octave <= 7:
            fail("auto octave outside -8..7", base + I_OCTAVE)
        if not auto and octave:
            fail("auto octave on a manual-period instrument", base + I_OCTAVE)
        if p[3]:
            fail("instrument reserved byte 3", base + 3)
        period = u16(p, I_PERIOD)
        if auto and period:
            fail("manual period on an auto-period instrument", base + I_PERIOD)
        envs = [self._envelope(base + I_ENV + 2 * k, k, fail) for k in range(5)]
        for i in (I_SWEEP_START, I_SWEEP_END):
            if not -96 <= s8(p[i]) <= 96:
                fail("sweep start/end outside -96..96 semitones", base + i)
        if any(p[19:INSTRUMENT_SIZE]):
            fail("instrument reserved bytes 19..31", base + 19)
        self.instruments[n] = {
            "relative": bool(flags & FLAG_PITCH_RELATIVE), "auto": auto, "sync": bool(flags & FLAG_SYNC),
            "shape": shape, "octave": octave, "period": period, "envs": envs,
            "sweep": (s8(p[I_SWEEP_START]), s8(p[I_SWEEP_END]), p[I_SWEEP_SPEED])}

    def _patterns(self, fail):
        """Every (order, channel) decodes rows(o) rows inside the used data (rows outer, channels inner, as
        ay_image.c walks them); returns the speed each order starts with in sequential straight play."""
        b, size = self.data, self.data_size
        speeds, speed = [], self.speed
        for o in range(self.orders):
            speeds.append(speed)
            offsets, runs = [], [0] * CHANNELS
            for c in range(CHANNELS):
                offsets.append(self.patterns[o][c])
                if offsets[c] >= size:
                    fail("pattern offset past the data size", OFF_ORDERS + 2 * (CHANNELS * o + c))
            for r in range(self.rows[o]):
                for c in range(CHANNELS):
                    if runs[c]:
                        runs[c] -= 1
                        continue
                    at = offsets[c]
                    if at >= size:
                        fail("pattern runs past the data size", OFF_DATA + at)
                    m = b[OFF_DATA + at]
                    at += 1
                    if m & 0x80:
                        runs[c] = m & 0x7F
                        offsets[c] = at
                        continue
                    effects = (m >> 2) & 7
                    if not m or m & 0x60 or effects > MAX_EFFECTS:
                        fail("row mask byte", OFF_DATA + at - 1)
                    need = (m & 1) + ((m >> 1) & 1) + 2 * effects
                    if at + need > size:
                        fail("row runs past the data size", OFF_DATA + at)
                    if m & 1:
                        note = b[OFF_DATA + at]
                        if note >= NOTES and note != NOTE_OFF:
                            fail("note byte", OFF_DATA + at)
                        at += 1
                    if m & 2:
                        if b[OFF_DATA + at] not in self.instruments:
                            fail("row names an instrument the image does not define", OFF_DATA + at)
                        at += 1
                    for _ in range(effects):
                        code, param = b[OFF_DATA + at], b[OFF_DATA + at + 1]
                        why = effect_problem(self.orders, code, param)
                        if why:
                            fail(why, OFF_DATA + at)
                        if code == FX["F"]:
                            speed = param  # rows, then channels, then columns: the last F wins
                        at += 2
                    offsets[c] = at
        return speeds

    def instrument(self, slot):
        return self.instruments.get(slot)

    def pattern_rows(self, order, channel):
        """The rows(order) rows of (order, channel): None (empty) or (note, instrument, [(code, param)])."""
        offset, n = self.patterns[order][channel], self.rows[order]
        key = (offset, n)
        rows = self._decoded.get(key)
        if rows is not None:
            return rows
        b, at, rows = self.data, OFF_DATA + offset, []
        while len(rows) < n:
            m = b[at]
            at += 1
            if m & 0x80:
                rows.extend([None] * ((m & 0x7F) + 1))
                continue
            note = inst = None
            if m & 1:
                note, at = b[at], at + 1
            if m & 2:
                inst, at = b[at], at + 1
            effects = []
            for _ in range((m >> 2) & 7):
                effects.append((b[at], b[at + 1]))
                at += 2
            rows.append((note, inst, effects))
        rows = rows[:n]  # a run longer than the order's remaining rows is cut there
        self._decoded[key] = rows
        return rows


# ---- player -------------------------------------------------------------------------------------------------------

ON, RELEASED, OFF = "on", "released", "off"


class Track:
    """State of one tracker channel (A, B, C)."""

    def __init__(self):
        self.row = None
        self.delay = None            # tick of a delayed row, None = no row pending
        self.cut = None
        self.slot = 0
        self.started = False         # some note keyed since reset (the channel is evaluated)
        self.phase = OFF
        self.stamp = 0
        self.base = self.target = 0
        self.fine = 0
        self.arp = 0
        self.arp_phase = 0
        self.slide_kind = None       # "up", "down", "porta"
        self.slide_speed = 0
        self.slide = 0
        self.vib = (0, 0)
        self.vib_phase = 0
        self.sweep = self.sweep_end = self.sweep_step = 0
        self.vol8 = 120
        self.volume_slide = 0
        self.envs = [None] * 5       # the keyed note's copies: (values, loop, release) or None
        self.pos = [0] * 5
        self.relative = False
        self.acc = 0
        # note settings
        self.shape = 0
        self.auto = True
        self.octave = 0
        self.period = 0
        self.sync = False
        self.mixer = None            # Vxx override
        self.noise = None            # Wxx override
        self.restart = False
        # this tick's evaluation
        self.sounds = self.uses_env = self.tone_on = self.noise_on = False
        self.nz = None
        self.tp = self.ep = self.amp = 0


class Player:
    def __init__(self, image):
        self.img = image
        self.variant = image.variant
        self.reset()

    def reset(self):
        self.regs = [0] * 13         # shadow of R0-R12
        self.last_r13 = None
        self.owner = None            # last tick's envelope owner
        self.stamps = 0
        self.tracks = [Track() for _ in range(CHANNELS)]
        self.speed = self.img.speed
        self.order = self.row = self.tick_in_row = 0
        self.next_pos = None
        self.auditioning = False
        self.out = []
        self.write(7, IO_BITS[self.variant] | 0x3F)
        self.batch = self.out
        return self.batch

    def write(self, reg, val):
        if self.regs[reg] != val:
            self.regs[reg] = val
            self.out.append((reg, val))

    # -- notes --
    def apply_setting(self, t, code, param, at_once):
        if code == FX["E"]:
            if at_once and param != t.shape:
                t.restart = True
            t.shape = param
        elif code == FX["H"]:
            t.period = param << 8 | (t.period & 0xFF)
            t.auto = False
        elif code == FX["I"]:
            t.period = (t.period & 0xFF00) | param
            t.auto = False
        elif code == FX["J"]:
            t.auto = True
            t.octave = s8(param)
        elif code == FX["V"]:
            t.mixer = param
        elif code == FX["W"]:
            t.noise = param

    def key_on(self, t, note, settings):
        inst = self.img.instrument(t.slot)
        if inst is None:
            return False
        t.envs = list(inst["envs"])  # copied: an instrument edit applies from the next note
        t.pos = [0] * 5
        t.acc = 0
        t.relative = inst["relative"]
        t.shape, t.auto, t.octave, t.period, t.sync = (inst["shape"], inst["auto"], inst["octave"], inst["period"],
                                                       inst["sync"])
        t.mixer = t.noise = None
        for code, param in settings:
            self.apply_setting(t, code, param, False)
        t.phase = ON
        t.base = t.target = note * UNITS_PER_SEMITONE
        t.slide = 0
        t.arp_phase = 0
        t.vib_phase = 0
        start, end, speed = inst["sweep"]
        if speed:
            t.sweep, t.sweep_end, t.sweep_step = start * 64, end * 64, speed * 4
        else:
            t.sweep = t.sweep_end = t.sweep_step = 0
        self.stamps += 1
        t.stamp = self.stamps
        t.restart = t.shape != 0
        t.started = True
        return True

    @staticmethod
    def key_off(t):
        if t.phase != ON:
            return
        vol = t.envs[0]
        if vol is not None and vol[2] is not None:
            t.pos[0] = vol[2]
            t.phase = RELEASED
        else:
            t.phase = OFF

    def run_row(self, ch):
        t = self.tracks[ch]
        note, inst, effects = t.row if t.row else (None, None, [])
        playing = note is not None and note < NOTES
        if playing:
            t.arp = 0
            t.fine = 0
        if inst:
            t.slot = inst
        settings = []
        for code, param in effects:
            hi, lo = param >> 4, param & 15
            if code == FX["0"]:
                t.arp, t.arp_phase = param, 0
            elif code in (FX["1"], FX["2"], FX["3"]):
                t.slide_kind = {FX["1"]: "up", FX["2"]: "down", FX["3"]: "porta"}[code] if param else None
                t.slide_speed = param
            elif code == FX["4"]:
                t.vib = (hi, lo)
            elif code == FX["A"]:
                t.volume_slide = param
            elif code == FX["C"]:
                t.vol8 = param * 8
            elif code == FX["P"]:
                t.fine = param - 0x80
            elif code == FX["S"]:
                t.cut = param
            elif code in SETTINGS:
                settings.append((code, param))
        keyed = False
        if note == NOTE_OFF:
            self.key_off(t)
        elif playing:
            if t.slide_kind == "porta" and t.phase == ON:
                t.target = note * UNITS_PER_SEMITONE
            else:
                keyed = self.key_on(t, note, settings)
        if not keyed:
            for code, param in settings:
                self.apply_setting(t, code, param, True)

    # -- evaluation --
    def evaluate(self, t):
        vol, arp, bend, mix, noi = (env[0][t.pos[k]] if env else None for k, env in enumerate(t.envs))
        level = 0 if t.phase == OFF else (15 if vol is None else vol)
        b = 4 * (bend or 0)
        if t.relative:
            t.acc = clamp(t.acc + b, -6144, 6144)
            offset = t.acc
        else:
            offset = b
        m = t.mixer if t.mixer is not None else (1 if mix is None else mix)
        t.nz = t.noise if t.noise is not None else noi
        fx = 0
        if t.arp and t.arp_phase:
            fx = (t.arp >> 4) if t.arp_phase == 1 else (t.arp & 15)
        vib = (SINE[t.vib_phase] * t.vib[1]) >> 4  # floor
        pitch = clamp(t.base + 64 * (fx + (arp or 0)) + t.fine + t.slide + vib + t.sweep + offset, 0, PITCH_MAX)
        v = (level * (t.vol8 >> 3) + 14) // 15
        t.sounds = t.phase != OFF and v > 0
        t.uses_env = t.sounds and t.shape != 0
        if t.uses_env:
            t.ep = auto_envelope_period(self.variant, pitch, t.octave, t.shape) if t.auto else t.period
        if t.uses_env and t.auto and t.sync:
            t.tp = synced_tone_period(t.ep, t.octave, t.shape)
        else:
            t.tp = tone_period(self.variant, pitch)
        t.amp = 0 if not t.sounds else (0x10 if t.uses_env else v)
        t.tone_on = t.sounds and bool(m & 1)
        t.noise_on = t.sounds and bool(m & 2)

    def emit(self, evaluated):
        tracks = self.tracks
        for c in evaluated:
            self.write(2 * c, tracks[c].tp & 0xFF)
            self.write(2 * c + 1, tracks[c].tp >> 8)
        noisy = [c for c in evaluated if tracks[c].noise_on and tracks[c].nz is not None]
        if noisy:
            self.write(6, tracks[max(noisy, key=lambda c: tracks[c].stamp)].nz)
        r7 = IO_BITS[self.variant]
        for c in range(CHANNELS):
            t = tracks[c]
            if not (c in evaluated and t.tone_on):
                r7 |= 1 << c
            if not (c in evaluated and t.noise_on):
                r7 |= 8 << c
        self.write(7, r7)
        for c in evaluated:
            self.write(8 + c, tracks[c].amp)
        users = [c for c in evaluated if tracks[c].uses_env]
        owner = max(users, key=lambda c: tracks[c].stamp) if users else None
        if owner is not None:
            t = tracks[owner]
            self.write(11, t.ep & 0xFF)
            self.write(12, t.ep >> 8)
            if t.restart or (owner != self.owner and t.shape != self.last_r13):
                self.out.append((13, t.shape))
                self.last_r13 = t.shape
        self.owner = owner
        # Silent notes retain their restart. Audible non-owners already hear the shared envelope, so an ownership
        # handoff must not replay their old key-on.
        for t in tracks:
            if t.uses_env or t.phase == OFF:
                t.restart = False

    @staticmethod
    def advance(t):
        if t.arp:
            t.arp_phase = (t.arp_phase + 1) % 3
        if t.slide_kind == "up":
            t.slide = min(6144, t.slide + t.slide_speed)
        elif t.slide_kind == "down":
            t.slide = max(-6144, t.slide - t.slide_speed)
        elif t.slide_kind == "porta":
            if t.base < t.target:
                t.base = min(t.target, t.base + t.slide_speed)
            elif t.base > t.target:
                t.base = max(t.target, t.base - t.slide_speed)
        if t.vib[0] and t.vib[1]:
            t.vib_phase = (t.vib_phase + t.vib[0]) % 64
        if t.sweep_step:
            if t.sweep < t.sweep_end:
                t.sweep = min(t.sweep_end, t.sweep + t.sweep_step)
            elif t.sweep > t.sweep_end:
                t.sweep = max(t.sweep_end, t.sweep - t.sweep_step)
        if t.volume_slide:
            t.vol8 = clamp(t.vol8 + (t.volume_slide >> 4) - (t.volume_slide & 15), 0, 120)
        released = t.phase == RELEASED
        for k, env in enumerate(t.envs):
            if env is None:
                continue
            values, loop, release = env
            nxt = t.pos[k] + 1
            if release is not None and not released and nxt == release:
                nxt = loop
            elif nxt >= len(values):
                nxt = loop if (loop is not None and release is None) else len(values) - 1
            t.pos[k] = nxt
        vol = t.envs[0]
        if released and t.pos[0] == len(vol[0]) - 1 and vol[0][-1] == 0:
            t.phase = OFF

    # -- sequencing --
    def derive_jump(self):
        b = d = None
        for t in self.tracks:
            for code, param in (t.row[2] if t.row else []):
                if code == FX["B"]:
                    b = param
                elif code == FX["D"]:
                    d = param
        if b is None and d is None:
            self.next_pos = None
        else:
            if b is None:
                b = self.order + 1 if self.order + 1 < self.img.orders else self.img.loop
            self.next_pos = (b, d or 0)

    @staticmethod
    def row_delay(row):
        g = 0
        for code, param in (row[2] if row else []):
            if code == FX["G"]:
                g = param
        return g

    def begin_row(self):
        for c, t in enumerate(self.tracks):
            t.row = self.img.pattern_rows(self.order, c)[self.row]
        for t in self.tracks:
            for code, param in (t.row[2] if t.row else []):
                if code == FX["F"]:
                    self.speed = param
        self.derive_jump()
        for c, t in enumerate(self.tracks):
            t.cut = None
            t.delay = None
            g = self.row_delay(t.row)
            if g:
                t.delay = g
            else:
                self.run_row(c)

    def next_row(self):
        img = self.img
        if self.next_pos is not None:
            o, r = self.next_pos
            self.order, self.row = o, min(r, img.rows[o] - 1)
            self.next_pos = None
        else:
            self.row += 1
            if self.row >= img.rows[self.order]:
                self.row = 0
                self.order = self.order + 1 if self.order + 1 < img.orders else img.loop
        if (self.order, self.row) == (img.loop, 0):
            self.speed = img.start_speed[self.order]  # every arrival at the loop order starts alike

    def tick(self):
        self.out = []
        if not self.auditioning:
            if self.tick_in_row == 0:
                self.begin_row()
            else:
                for c, t in enumerate(self.tracks):
                    if t.delay is not None and t.delay == self.tick_in_row:
                        t.delay = None
                        self.run_row(c)
            for t in self.tracks:
                if t.cut == self.tick_in_row:
                    self.key_off(t)
        evaluated = [c for c in range(CHANNELS) if self.tracks[c].started]
        for c in evaluated:
            self.evaluate(self.tracks[c])
        self.emit(evaluated)
        for c in evaluated:
            self.advance(self.tracks[c])
        if not self.auditioning:
            self.tick_in_row += 1
            if self.tick_in_row >= self.speed:
                self.tick_in_row = 0
                self.next_row()
        self.batch = self.out
        return self.batch

    def position(self):
        return self.order, self.row, self.tick_in_row

    # -- live edits --
    def speed_at(self, img, order, row, started):
        """Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before
        it (and of the row itself once it has started)."""
        s = img.start_speed[order]
        last = row if started else row - 1
        for r in range(last + 1):
            for c in range(CHANNELS):
                cell = img.pattern_rows(order, c)[r]
                for code, param in (cell[2] if cell else []):
                    if code == FX["F"]:
                        s = param
        return s

    def relocate(self, new):
        """apply_image at a tick boundary: the OPL3/YM2612 rules (positions clamped, speed rules, a started row
        re-reads its jump and its still-delayed channels)."""
        if self.auditioning:
            return
        old = self.img
        started = self.tick_in_row > 0
        old_speed = self.speed_at(old, self.order, self.row, started)
        clamped = False
        if self.order >= new.orders:
            self.order = new.orders - 1
            self.row = new.rows[self.order] - 1
            clamped = True
        elif self.row >= new.rows[self.order]:
            self.row = new.rows[self.order] - 1
            clamped = True
        self.img = new
        if clamped:
            self.next_pos = None
            for t in self.tracks:
                t.delay = None
            self.speed = self.speed_at(new, self.order, self.row, True)
            if not started:
                self.next_row()  # the clamped row counts as played: the next row follows it
            return
        new_speed = self.speed_at(new, self.order, self.row, started)
        if new_speed != old_speed:
            self.speed = new_speed
        if not started:
            return
        for c, t in enumerate(self.tracks):
            t.row = new.pattern_rows(self.order, c)[self.row]
            if t.delay is not None:
                g = self.row_delay(t.row)
                t.delay = g if g and g >= self.tick_in_row else None
        self.derive_jump()

    # -- audition --
    def audition(self, channel, note, slot, fine):
        """Reset, then key `note` on `channel` with `slot` (its own settings), `fine` added like Pxx. Returns the
        batch (the reset write: the key-on itself writes nothing until the first tick)."""
        if not (0 <= channel < CHANNELS and 0 <= note < NOTES and -128 <= fine <= 127
                and self.img.instrument(slot) is not None):
            raise ValueError("audition arguments")
        self.reset()
        self.auditioning = True
        t = self.tracks[channel]
        t.slot = slot
        self.key_on(t, note, [])
        t.fine = fine
        return self.batch

    def release(self, channel):
        """Audition release: the key-off, heard from the next tick (no writes now). 1 if it applied."""
        t = self.tracks[channel]
        if t.phase == OFF:
            return 0
        self.key_off(t)
        return 1


# ---- trace and VGM ------------------------------------------------------------------------------------------------

def trace(image, ticks, edit=None):
    """[((order, row, tick), [(reg, val)])] per tick from reset (the reset batch excluded). `edit` = (tick, image):
    apply_image of that image at the boundary before that tick index."""
    p = Player(image)
    out = []
    for k in range(ticks):
        if edit is not None and k == edit[0]:
            p.relocate(edit[1])
        pos = p.position()
        out.append((pos, list(p.tick())))
    return out


def format_tick(pos, writes):
    return "%d %d %d:" % pos + "".join(" %02X=%02X" % w for w in writes)


def vgm_wait(body, n):
    if n == 735:
        body.append(0x62)
    elif n == 882:
        body.append(0x63)
    elif 1 <= n <= 16:
        body.append(0x70 + n - 1)
    else:
        body.extend((0x61, n & 0xFF, n >> 8))


def max_ticks_default(variant):
    """Ten minutes of ticks at the variant's rate."""
    return 600 * MASTER_HZ[variant] // FRAME_DIV[variant]


def vgm(image, max_ticks):
    """VGM 1.71 bytes (no GD3), as docs/AUDIO_ENGINE.md "AY-3-8910 / YM2149: VGM export" specifies.
    Returns (bytes, info)."""
    v = image.variant
    p = Player(image)
    body = bytearray()

    def emit(writes):
        for reg, val in writes:
            body.extend((0xA0, reg, val))

    emit(p.batch)
    acc = samples = ticks = 0
    loop_at = loop_samples = None
    looped = False
    while ticks < max_ticks:
        if p.position() == (image.loop, 0, 0):
            if loop_at is not None:
                looped = True
                break
            loop_at, loop_samples = 0x100 + len(body), samples
            emit([(r, p.regs[r]) for r in range(13)])
        emit(p.tick())
        acc += VGM_SAMPLE_RATE * FRAME_DIV[v]
        n, acc = divmod(acc, MASTER_HZ[v])
        vgm_wait(body, n)
        samples += n
        ticks += 1
    body.append(0x66)
    header = bytearray(0x100)
    header[0:4] = b"Vgm "
    size = 0x100 + len(body)
    struct.pack_into("<I", header, 0x04, size - 4)
    struct.pack_into("<I", header, 0x08, 0x171)
    struct.pack_into("<I", header, 0x18, samples)
    if looped:
        struct.pack_into("<I", header, 0x1C, loop_at - 0x1C)
        struct.pack_into("<I", header, 0x20, samples - loop_samples)
    struct.pack_into("<I", header, 0x24, VGM_RATE[v])
    struct.pack_into("<I", header, 0x34, 0x100 - 0x34)
    struct.pack_into("<I", header, 0x74, VGM_CLOCK[v])
    header[0x78] = VGM_TYPE[v]
    header[0x79] = 0x01
    info = {"ticks": ticks, "samples": samples, "looped": looped,
            "loop_offset": loop_at if looped else 0, "loop_samples": samples - loop_samples if looped else 0}
    return bytes(header + body), info


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("image")
    ap.add_argument("--trace")
    ap.add_argument("--ticks", type=int, default=1500)
    ap.add_argument("--vgm")
    ap.add_argument("--max-ticks", type=int, help="default: ten minutes at the variant's tick rate")
    args = ap.parse_args(argv)
    with open(args.image, "rb") as f:
        image = Image(f.read())
    if args.trace:
        with open(args.trace, "w") as f:
            for pos, writes in trace(image, args.ticks):
                f.write(format_tick(pos, writes) + "\n")
    if args.vgm:
        data, info = vgm(image, args.max_ticks or max_ticks_default(image.variant))
        with open(args.vgm, "wb") as f:
            f.write(data)
        print("%s: %d bytes, %d ticks, %d samples%s" % (args.vgm, len(data), info["ticks"], info["samples"],
              ", loop %d samples at 0x%X" % (info["loop_samples"], info["loop_offset"]) if info["looped"]
              else ", no loop"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
