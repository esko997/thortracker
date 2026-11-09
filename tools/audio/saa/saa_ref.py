#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's SAA1099 player (SAM Coupé: one chip, Creative Music System: two),
written from the specification in docs/AUDIO_ENGINE.md ("SAA1099") independently of the C player
(app/src/main/cpp/saa/saa_player.c). The host test compares the C player's register writes with this one's, tick by
tick, and its VGM bytes with this one's.

    python3 tools/audio/saa/saa_ref.py IMAGE.bin --trace out.txt --ticks 1500
    python3 tools/audio/saa/saa_ref.py IMAGE.bin --vgm out.vgm [--max-ticks N]

Pure Python 3, no dependencies. Also defines the divider and vibrato tables (generated into
app/src/main/cpp/generated/saa_tables.h by prepare_saa.py), an image loader that refuses malformed input, and a
per-clock model of one SAA1099 (the specification's "One clock" steps run literally, SAASound's envelope state
machine and output levels) used by tools/audio/saa_reference_check.py."""
import argparse
import math
import struct
import sys

CLOCK_SAM = 8000000
CLOCK_CMS = 7159090
RATE_SAM, RATE_CMS = 50, 60
VGM_DUAL = 0x40000000
UNITS_PER_SEMITONE = 64
UNITS_PER_OCTAVE = 768
NOTES = 120
PITCH_MAX = NOTES * UNITS_PER_SEMITONE - 1   # 7679
ACC_LIMIT = 7680
NOTE_OFF = 0xFE
VOLUME_MAX = 240

IMAGE_SIZE = 65536
MAGIC = b"TTSA"
LAYOUT = 1
VARIANT_SAM, VARIANT_CMS = 0, 1
CHANNELS_SAM, CHANNELS_CMS = 6, 12
MAX_CHANNELS = 12
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 6464, 7488
INSTRUMENT_SIZE = 16
DATA_CAPACITY = IMAGE_SIZE - OFF_DATA   # 58048
ENV_NONE = 0xFFFF
ENV_HEADER = 6
MAX_ENVELOPE = 256
FLAG_DEFINED, FLAG_PITCH_RELATIVE, FLAG_ENV_RETRIGGER = 0x80, 0x01, 0x02
I_FLAGS, I_NOISE, I_PAN, I_ENVELOPE, I_RATE = 0, 1, 2, 3, 4
I_VOLUME, I_ARPEGGIO, I_PITCH, I_MIXER = 6, 8, 10, 12
ENV_FIELDS = (I_VOLUME, I_ARPEGGIO, I_PITCH, I_MIXER)  # kinds: 0 volume, 1 arpeggio, 2 pitch, 3 mixer
ENV_ENABLE, ENV_EXTERNAL, ENV_3BIT, ENV_INVERT = 0x80, 0x20, 0x10, 0x01

LETTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
FX = {c: i for i, c in enumerate(LETTERS)}
EFFECT_LETTERS = "012348ABCDEFGMNPSY"
EFFECTS = set(FX[c] for c in EFFECT_LETTERS)

# Registers
R_AMP, R_OFFSET, R_OCTAVE, R_TONE, R_NOISE_EN, R_NOISE, R_ENV, R_CONTROL = 0x00, 0x08, 0x10, 0x14, 0x15, 0x16, 0x18, 0x1C
SHADOWED = list(range(0x00, 0x06)) + list(range(0x08, 0x0E)) + [0x10, 0x11, 0x12, 0x14, 0x15, 0x16, 0x18, 0x19, 0x1C]


# ---- tables ------------------------------------------------------------------------------------------

def div_table(clock):
    """SA_DIV[r] = floor(K / (512 * f(r)) * 256 + 0.5), f(r) = 440 * 2^((r/64 - 57)/12): the divider 511 - N of
    C-0 + r/64 semitones at octave 0, in Q8."""
    out = []
    for r in range(UNITS_PER_OCTAVE):
        f = 440.0 * 2 ** ((r / UNITS_PER_SEMITONE - 57) / 12)
        out.append(math.floor(clock / (512 * f) * 256 + 0.5))
    return out


def sine_table():
    """Vibrato: floor(127 sin(2 pi i / 64) + 0.5)."""
    return [math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64)]


DIV_SAM = div_table(CLOCK_SAM)
DIV_CMS = div_table(CLOCK_CMS)
SINE = sine_table()


def octave_offset(pitch, cms):
    """(O, N) of a pitch 0..7679 on the variant's clock, clamped to the chip's range."""
    k, r = divmod(pitch, UNITS_PER_OCTAVE)
    t = (DIV_CMS if cms else DIV_SAM)[r]
    if t >= 131072:
        s, d = -1, (t + 256) >> 9
    else:
        s, d = 0, (t + 128) >> 8
        if d >= 512:
            s, d = -1, (t + 256) >> 9
    o = k + s
    if o < 0:
        return 0, 0
    if o > 7:
        return 7, 255
    return o, 511 - d


def hz(octave, offset, cms):
    return (CLOCK_CMS if cms else CLOCK_SAM) * (1 << octave) / (512 * (511 - offset))


def octave_unclamped(pitch, cms):
    """The octave O the conversion picks before clamping (may be < 0 or > 7)."""
    k, r = divmod(pitch, UNITS_PER_OCTAVE)
    t = (DIV_CMS if cms else DIV_SAM)[r]
    if t >= 131072 or (t + 128) >> 8 >= 512:
        return k - 1
    return k


def note_range(cms):
    """(lowest, highest) note the player converts without clamping: SAM 11..106 (B-0..A#8), CMS 9..104."""
    ok = [n for n in range(NOTES) if 0 <= octave_unclamped(n * UNITS_PER_SEMITONE, cms) <= 7]
    return ok[0], ok[-1]


def s8(b):
    return b - 256 if b >= 128 else b


def u16(data, at):
    return data[at] | data[at + 1] << 8


def envelope_length(value):
    """Steps of one full cycle of a hardware envelope control value: phases x (16, or 8 at 3-bit)."""
    shape = (value >> 1) & 7
    return (2 if shape in (4, 5) else 1) * (8 if value & ENV_3BIT else 16)


def envelope_loops(value):
    return ((value >> 1) & 7) in (1, 3, 5, 7)


# ---- image -------------------------------------------------------------------------------------------

class LoadError(ValueError):
    def __init__(self, reason, offset):
        super().__init__("%s (byte %d)" % (reason, offset))
        self.reason, self.offset = reason, offset


class Envelope:
    __slots__ = ("values", "loop", "release")

    def __init__(self, values, loop, release):
        self.values, self.loop, self.release = values, loop, release

    @property
    def length(self):
        return len(self.values)


def validate(data):
    """Raises LoadError for anything the specification's loader refuses (independent of saa_image.c's wording)."""
    def bad(reason, offset):
        raise LoadError(reason, offset)

    if len(data) != IMAGE_SIZE:
        bad("image size", -1)
    if data[0:4] != MAGIC:
        bad("magic", 0)
    if data[4] != LAYOUT:
        bad("layout", 4)
    if data[5] not in (VARIANT_SAM, VARIANT_CMS):
        bad("variant", 5)
    if not 1 <= data[6] <= 8:
        bad("multispeed", 6)
    if data[7] == 0:
        bad("speed", 7)
    orders, loop = u16(data, 8), u16(data, 10)
    if not 1 <= orders <= 256:
        bad("order count", 8)
    if loop >= orders:
        bad("loop order", 10)
    instruments, channels, size = data[12], data[13], u16(data, 14)
    if instruments > 64:
        bad("instrument count", 12)
    if channels != (CHANNELS_CMS if data[5] == VARIANT_CMS else CHANNELS_SAM):
        bad("channel count", 13)
    if size > DATA_CAPACITY:
        bad("data size", 14)
    for at in range(16, 64):
        if data[at]:
            bad("header padding", at)
    for o in range(256):
        if o >= orders and data[OFF_ROWS + o]:
            bad("rows beyond the order count", OFF_ROWS + o)
        for c in range(MAX_CHANNELS):
            if o < orders and c < channels:
                continue
            at = OFF_ORDERS + 2 * (MAX_CHANNELS * o + c)
            if data[at] or data[at + 1]:
                bad("pattern beyond the order count or channels", at)

    def envelope(at_field, kind):
        off = u16(data, at_field)
        if off == ENV_NONE:
            return
        if off + ENV_HEADER > size:
            bad("envelope outside the data", at_field)
        base = OFF_DATA + off
        length, lp, rel = u16(data, base), u16(data, base + 2), u16(data, base + 4)
        if not 1 <= length <= MAX_ENVELOPE:
            bad("envelope length", base)
        if lp != ENV_NONE and lp >= length:
            bad("envelope loop", base + 2)
        if rel != ENV_NONE:
            if kind != 0:
                bad("release point outside a volume envelope", base + 4)
            if not 1 <= rel < length:
                bad("envelope release", base + 4)
            if lp != ENV_NONE and lp >= rel:
                bad("envelope loop after its release", base + 2)
        if off + ENV_HEADER + length > size:
            bad("envelope outside the data", base)
        for i in range(length):
            at = base + ENV_HEADER + i
            v = data[at]
            if kind == 0 and v > 15:
                bad("volume envelope value", at)
            if kind in (1, 2) and not -64 <= s8(v) <= 63:
                bad("arpeggio or pitch envelope value", at)
            if kind == 3 and v > 3:
                bad("mixer envelope value", at)

    defined = set()
    for n in range(1, 65):
        rec = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (n - 1)
        body = data[rec:rec + INSTRUMENT_SIZE]
        if not any(body):
            if n == instruments:
                bad("highest instrument missing", rec)
            continue
        if n > instruments:
            bad("instrument beyond the count", rec)
        flags = body[I_FLAGS]
        if not flags & FLAG_DEFINED or flags & ~(FLAG_DEFINED | FLAG_PITCH_RELATIVE | FLAG_ENV_RETRIGGER) & 0xFF:
            bad("instrument flags", rec)
        if body[I_NOISE] > 3:
            bad("noise clock", rec + I_NOISE)
        env = body[I_ENVELOPE]
        if env and (not env & ENV_ENABLE or env & 0x40):
            bad("hardware envelope", rec + I_ENVELOPE)
        if flags & FLAG_ENV_RETRIGGER and not env:
            bad("instrument flags", rec)
        if env & ENV_EXTERNAL:
            if body[I_RATE] == 0:
                bad("envelope rate", rec + I_RATE)
        elif body[I_RATE]:
            bad("envelope rate", rec + I_RATE)
        for k in (5, 14, 15):
            if body[k]:
                bad("instrument padding", rec + k)
        for kind, field in enumerate(ENV_FIELDS):
            envelope(rec + field, kind)
        defined.add(n)

    checked = set()
    for o in range(orders):
        rows = data[OFF_ROWS + o] or 256
        for c in range(channels):
            at_field = OFF_ORDERS + 2 * (MAX_CHANNELS * o + c)
            off = u16(data, at_field)
            if (off, rows) in checked:
                continue
            checked.add((off, rows))
            if off >= size:
                bad("pattern outside the data", at_field)
            at, r = off, 0

            def byte(i):
                if i >= size:
                    bad("pattern runs past the data", OFF_DATA + min(i, DATA_CAPACITY - 1))
                return data[OFF_DATA + i]

            while r < rows:
                m = byte(at)
                if m & 0x80:
                    r += (m & 0x7F) + 1
                    at += 1
                    continue
                if m == 0 or m & 0x60 or (m >> 2) & 7 > 4:
                    bad("row mask", OFF_DATA + at)
                at += 1
                if m & 1:
                    note = byte(at)
                    if note >= NOTES and note != NOTE_OFF:
                        bad("note", OFF_DATA + at)
                    at += 1
                if m & 2:
                    inst = byte(at)
                    if inst not in defined:
                        bad("instrument", OFF_DATA + at)
                    at += 1
                for _ in range((m >> 2) & 7):
                    code, param = byte(at), byte(at + 1)
                    if code not in EFFECTS:
                        bad("effect", OFF_DATA + at)
                    if (code == FX["B"] and param >= orders) or (code == FX["C"] and param > 15) or \
                            (code == FX["F"] and param == 0) or \
                            (code == FX["E"] and param != 0 and (param & 0xC0) != 0x80) or \
                            (code in (FX["M"], FX["N"], FX["Y"]) and param > 3):
                        bad("effect parameter", OFF_DATA + at + 1)
                    at += 2
                r += 1


class Image:
    """A loaded image (validated unless check=False)."""

    def __init__(self, data, check=True):
        data = bytes(data)
        if check:
            validate(data)
        self.data = data
        self.variant = data[5]
        self.cms = self.variant == VARIANT_CMS
        self.channels = CHANNELS_CMS if self.cms else CHANNELS_SAM
        self.chips = self.channels // 6
        self.rate = RATE_CMS if self.cms else RATE_SAM
        self.clock = CLOCK_CMS if self.cms else CLOCK_SAM
        self.multispeed = data[6]
        self.speed = data[7]
        self.orders, self.loop = u16(data, 8), u16(data, 10)
        self.instrument_count = data[12]
        self.size = u16(data, 14)
        self.rows = [(data[OFF_ROWS + o] or 256) for o in range(self.orders)]
        self.patterns = [[u16(data, OFF_ORDERS + 2 * (MAX_CHANNELS * o + c)) for c in range(self.channels)]
                         for o in range(self.orders)]
        self._decoded = {}
        self.start_speed = self._start_speeds()

    def pattern_rows(self, order, channel):
        """Every row of (order, channel): None (empty) or (note | None, instrument | None, [(code, param)])."""
        offset, n = self.patterns[order][channel], self.rows[order]
        key = (offset, n)
        if key in self._decoded:
            return self._decoded[key]
        d, at, rows = self.data, OFF_DATA + offset, []
        while len(rows) < n:
            m = d[at]
            at += 1
            if m & 0x80:
                rows.extend([None] * ((m & 0x7F) + 1))
                continue
            note = inst = None
            if m & 1:
                note = d[at]
                at += 1
            if m & 2:
                inst = d[at]
                at += 1
            effects = []
            for _ in range((m >> 2) & 7):
                effects.append((d[at], d[at + 1]))
                at += 2
            rows.append((note, inst, effects))
        rows = rows[:n]
        self._decoded[key] = rows
        return rows

    def row(self, order, channel, row):
        return self.pattern_rows(order, channel)[row]

    def _start_speeds(self):
        speeds, s = [], self.speed
        for o in range(self.orders):
            speeds.append(s)
            for r in range(self.rows[o]):
                for c in range(self.channels):
                    x = self.row(o, c, r)
                    for code, param in (x[2] if x else []):
                        if code == FX["F"]:
                            s = param
        return speeds

    def speed_at(self, order, row, started):
        s = self.start_speed[order]
        for r in range(row + 1 if started else row):
            for c in range(self.channels):
                x = self.row(order, c, r)
                for code, param in (x[2] if x else []):
                    if code == FX["F"]:
                        s = param
        return s

    def record(self, slot):
        if not 1 <= slot <= 64:
            return bytes(INSTRUMENT_SIZE)
        at = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        return self.data[at:at + INSTRUMENT_SIZE]

    def flags(self, slot):
        return self.record(slot)[I_FLAGS]

    def defined(self, slot):
        return bool(self.flags(slot) & FLAG_DEFINED)

    def envelope(self, slot, kind):
        """Envelope kind 0..3 (volume, arpeggio, pitch, mixer) of a defined slot, or None."""
        off = u16(self.record(slot), ENV_FIELDS[kind])
        if off == ENV_NONE:
            return None
        base = OFF_DATA + off
        length, lp, rel = u16(self.data, base), u16(self.data, base + 2), u16(self.data, base + 4)
        raw = self.data[base + ENV_HEADER: base + ENV_HEADER + length]
        values = [s8(v) for v in raw] if kind in (1, 2) else list(raw)
        return Envelope(values, -1 if lp == ENV_NONE else lp, -1 if rel == ENV_NONE else rel)


# ---- player ------------------------------------------------------------------------------------------

# Channel fields that tick 0 overwrites before reading them (excluded from the VGM loop's state comparison).
TRANSIENT = ("row", "pending", "delay", "cut")


class Channel:
    def __init__(self):
        self.row = None
        self.pending = False      # row delayed by Gxx to tick `delay`
        self.delay = 0
        self.cut = -1             # Sxx tick, -1 none
        self.instrument = 0       # slot for the next note
        self.slot = 0             # the sounding note's slot
        self.active = False
        self.keyed = False
        self.released = False
        self.keyon = False        # keyed on during this tick (cleared at its end)
        self.env = [0, 0, 0, 0]
        self.acc = 0              # relative pitch envelope accumulator
        self.base = self.target = 0
        self.fine = 0
        self.arp = 0
        self.arp_step = 0
        self.slide_mode = 0       # 0 none, 1 up, 2 down, 3 portamento
        self.slide_speed = 0
        self.slide = 0
        self.vib_speed = self.vib_depth = self.vib_phase = 0
        self.volume = VOLUME_MAX  # channel volume in 1/16 steps
        self.volslide = 0
        self.pan = -1             # 8xy override, -1 none
        self.mixer_lock = -1      # Mxx
        self.noise_lock = -1      # Nxx
        self.env_lock = -1        # Exx
        self.mixer = 0            # mixer value of this tick (for the passes)
        self.last_mixer = 0
        self.last_noise = 0
        self.ext_counter = 0      # external envelope clocking (lanes 3 and 6)
        self.ext_clocks = 0

    def state(self):
        return tuple((k, (list(v) if isinstance(v, list) else v)) for k, v in sorted(vars(self).items())
                     if k not in TRANSIENT).__repr__()


class Player:
    def __init__(self, image):
        self.img = image
        self.reset()

    # -- writes --
    def write(self, chip, reg, val, forced=False):
        sh = self.shadow[chip]
        if not forced and sh[reg] == val:
            return
        sh[reg] = val
        self.batch.append((chip, reg, val))

    # -- reset, placing --
    def reset(self):
        img = self.img
        self.cms = img.cms
        self.channels = img.channels
        self.chips = img.chips
        self.order = self.row = self.tick_in_row = 0
        self.speed = img.speed
        self.next_pos = None
        self.audition = False
        self.ch = [Channel() for _ in range(self.channels)]
        self.noise = [[0, 0] for _ in range(self.chips)]
        self.sync_cmd = [-1] * self.chips
        self.shadow = [dict((r, 0) for r in SHADOWED) for _ in range(self.chips)]
        self.batch = []
        for k in range(self.chips):
            self.write(k, R_CONTROL, 0x02, True)
            for reg in range(0x00, 0x06):
                self.write(k, reg, 0, True)
            for reg in range(0x08, 0x0E):
                self.write(k, reg, 0, True)
            for reg in (0x10, 0x11, 0x12, 0x14, 0x15, 0x16, 0x18, 0x19):
                self.write(k, reg, 0, True)
            self.write(k, R_CONTROL, 0x01, True)
        return self.batch

    def place(self, order):
        """Seek fallback: the reset, then order `order` row 0 with the speed straight play starts it with."""
        self.reset()
        self.order = order
        self.speed = self.img.start_speed[order]
        return self.batch

    def state(self):
        """Everything but the write buffer and the row state tick 0 overwrites (the VGM loop's comparison)."""
        return (self.order, self.row, self.tick_in_row, self.speed, self.next_pos, self.audition,
                tuple(ch.state() for ch in self.ch), repr(self.noise),
                repr([sorted(sh.items()) for sh in self.shadow]))

    # -- notes --
    def key_on(self, c, note):
        ch = self.ch[c]
        if not self.img.defined(ch.instrument):
            return
        ch.slot = ch.instrument
        ch.env = [0, 0, 0, 0]
        ch.released = False
        ch.acc = 0
        ch.base = ch.target = note * UNITS_PER_SEMITONE
        ch.slide = 0
        ch.arp_step = 0
        ch.vib_phase = 0
        ch.keyed = ch.active = True
        ch.keyon = True
        ch.ext_counter = ch.ext_clocks = 0

    def key_off(self, c):
        ch = self.ch[c]
        if not ch.keyed:
            return
        vol = self.img.envelope(ch.slot, 0) if self.img.defined(ch.slot) else None
        if vol is not None and vol.release >= 0 and not ch.released:
            ch.released = True
            ch.env[0] = vol.release
        else:
            ch.keyed = False

    def run_row(self, c):
        ch = self.ch[c]
        note, inst, effects = ch.row if ch.row else (None, None, [])
        playing = note is not None and note < NOTES
        if playing:
            ch.arp = 0
            ch.fine = 0
            ch.pan = ch.mixer_lock = ch.noise_lock = ch.env_lock = -1
        if inst:
            ch.instrument = inst
        for code, param in effects:
            hi, lo = param >> 4, param & 15
            if code == FX["0"]:
                ch.arp, ch.arp_step = param, 0
            elif code in (FX["1"], FX["2"], FX["3"]):
                ch.slide_mode = code if param else 0
                ch.slide_speed = param
            elif code == FX["4"]:
                ch.vib_speed, ch.vib_depth = hi, lo
            elif code == FX["8"]:
                ch.pan = param
            elif code == FX["A"]:
                ch.volslide = param
            elif code == FX["C"]:
                ch.volume = param * 16
            elif code == FX["E"]:
                ch.env_lock = param
            elif code == FX["M"]:
                ch.mixer_lock = param
            elif code == FX["N"]:
                ch.noise_lock = param
            elif code == FX["P"]:
                ch.fine = param - 0x80
            elif code == FX["S"]:
                ch.cut = param
            elif code == FX["Y"]:
                self.sync_cmd[c // 6] = param
        if note == NOTE_OFF:
            self.key_off(c)
        elif playing:
            if ch.slide_mode == 3 and ch.keyed:
                ch.target = note * UNITS_PER_SEMITONE
            else:
                self.key_on(c, note)

    def update(self, c):
        ch = self.ch[c]
        img = self.img
        chip, lane = divmod(c, 6)
        if ch.active and not img.defined(ch.slot):
            ch.keyed = ch.active = False
        envs = None
        m = 0
        e = 0
        if ch.active:
            rec = img.record(ch.slot)
            envs = [img.envelope(ch.slot, k) for k in range(4)]
            for k, env in enumerate(envs):
                if env is not None and ch.env[k] >= env.length:
                    ch.env[k] = env.length - 1
            e = envs[0].values[ch.env[0]] if envs[0] else 15
            a = envs[1].values[ch.env[1]] if envs[1] else 0
            v = envs[2].values[ch.env[2]] if envs[2] else 0
            m = envs[3].values[ch.env[3]] if envs[3] else 1
            if ch.mixer_lock >= 0:
                m = ch.mixer_lock
            if rec[I_FLAGS] & FLAG_PITCH_RELATIVE:
                ch.acc = max(-ACC_LIMIT, min(ACC_LIMIT, ch.acc + 4 * v))
                q = ch.acc
            else:
                q = 4 * v
            arp = 0
            if ch.arp_step == 1:
                arp = ch.arp >> 4
            elif ch.arp_step == 2:
                arp = ch.arp & 15
            vib = (SINE[ch.vib_phase] * ch.vib_depth) >> 4
            pitch = max(0, min(PITCH_MAX, ch.base + 64 * (a + arp) + ch.fine + ch.slide + vib + q))
            o, n = octave_offset(pitch, self.cms)
            self.write(chip, R_OFFSET + lane, n)
            octave_reg = R_OCTAVE + lane // 2
            old = self.shadow[chip][octave_reg]
            if lane & 1:
                val = (old & 0x07) | o << 4
            else:
                val = (old & 0x70) | o
            self.write(chip, octave_reg, val)
        amp = 0
        if ch.active and ch.keyed:
            # (left, right): 8xy has x = left, y = right; instrument byte 2 has left in its low nibble
            pan = ((ch.pan >> 4) | (ch.pan & 15) << 4) if ch.pan >= 0 else rec[I_PAN]
            cv = ch.volume >> 4
            sides = []
            for p in (pan & 15, pan >> 4):
                x = e * cv * p // 225
                if e > 0 and cv > 0 and p > 0 and x < 1:
                    x = 1
                sides.append(x)
            amp = sides[0] | sides[1] << 4
        self.write(chip, R_AMP + lane, amp)
        if ch.active and ch.keyed:
            ch.mixer = m
            clock = ch.noise_lock if ch.noise_lock >= 0 else rec[I_NOISE]
            if m & 2 and (ch.keyon or not ch.last_mixer & 2 or clock != ch.last_noise):
                self.noise[chip][lane // 3] = clock
            ch.last_mixer, ch.last_noise = m, clock
        else:
            ch.mixer = 0
            ch.last_mixer = ch.last_noise = 0
        if not ch.active:
            return
        # advance
        for k, env in enumerate(envs):
            if env is None:
                continue
            end = env.release if k == 0 and env.release >= 0 and not ch.released else env.length
            i = ch.env[k]
            if i + 1 < end:
                i += 1
            elif env.loop >= 0 and not ch.released:
                i = env.loop
            else:
                i = end - 1
            ch.env[k] = i
        if ch.arp:
            ch.arp_step = (ch.arp_step + 1) % 3
        if ch.slide_mode == 1:
            ch.slide = min(ACC_LIMIT, ch.slide + ch.slide_speed)
        elif ch.slide_mode == 2:
            ch.slide = max(-ACC_LIMIT, ch.slide - ch.slide_speed)
        elif ch.slide_mode == 3:
            if ch.base < ch.target:
                ch.base = min(ch.target, ch.base + ch.slide_speed)
            elif ch.base > ch.target:
                ch.base = max(ch.target, ch.base - ch.slide_speed)
        if ch.vib_speed and ch.vib_depth:
            ch.vib_phase = (ch.vib_phase + ch.vib_speed) % 64
        if ch.volslide:
            ch.volume = max(0, min(VOLUME_MAX, ch.volume + (ch.volslide >> 4) - (ch.volslide & 15)))

    def chip_passes(self, k):
        lanes = self.ch[6 * k: 6 * k + 6]
        self.write(k, R_TONE, sum(1 << l for l, ch in enumerate(lanes) if ch.mixer & 1))
        self.write(k, R_NOISE_EN, sum(1 << l for l, ch in enumerate(lanes) if ch.mixer & 2))
        self.write(k, R_NOISE, self.noise[k][0] | self.noise[k][1] << 4)
        for e in (0, 1):
            reg = R_ENV + e
            ch = lanes[3 * e + 2]
            if not (ch.active and ch.keyed):
                continue
            rec = self.img.record(ch.slot)
            value = ch.env_lock if ch.env_lock >= 0 else rec[I_ENVELOPE]
            rate = rec[I_RATE] or 1
            sh = self.shadow[k]
            if ch.keyon:
                retrigger = rec[I_FLAGS] & FLAG_ENV_RETRIGGER or ch.env_lock >= 0
                if value and retrigger and sh[reg] & ENV_ENABLE:
                    self.write(k, reg, 0)
                self.write(k, reg, value)
                ch.ext_counter = ch.ext_clocks = 0
            elif value != sh[reg]:
                self.write(k, reg, value)
                ch.ext_counter = ch.ext_clocks = 0
            elif value & (ENV_ENABLE | ENV_EXTERNAL) == ENV_ENABLE | ENV_EXTERNAL:
                ch.ext_counter += 1
                if ch.ext_counter >= rate:
                    ch.ext_counter = 0
                    if envelope_loops(value) or ch.ext_clocks < envelope_length(value) - 1:
                        self.write(k, reg, value, True)
                        ch.ext_clocks += 1
        cmd = self.sync_cmd[k]
        if cmd >= 0:
            self.sync_cmd[k] = -1
            if cmd == 0:
                self.write(k, R_CONTROL, 0x01)
            elif cmd == 1:
                self.write(k, R_CONTROL, 0x03, True)
                self.write(k, R_CONTROL, 0x01, True)
            elif cmd == 2:
                self.write(k, R_CONTROL, 0x03)
            else:
                self.write(k, R_CONTROL, 0x00)

    # -- sequencing --
    def derive_jump(self):
        b = d = None
        for c in range(self.channels):
            x = self.ch[c].row
            for code, param in (x[2] if x else []):
                if code == FX["B"]:
                    b = param
                elif code == FX["D"]:
                    d = param
        if b is None and d is None:
            self.next_pos = None
        else:
            if b is None:
                b = self.order + 1 if self.order + 1 < self.img.orders else self.img.loop
            self.next_pos = (b, d if d is not None else 0)

    @staticmethod
    def row_delay(x):
        g = 0
        for code, param in (x[2] if x else []):
            if code == FX["G"]:
                g = param
        return g

    def begin_row(self):
        for c in range(self.channels):
            self.ch[c].row = self.img.row(self.order, c, self.row)
        for c in range(self.channels):
            x = self.ch[c].row
            for code, param in (x[2] if x else []):
                if code == FX["F"]:
                    self.speed = param
        self.derive_jump()
        for c in range(self.channels):
            ch = self.ch[c]
            ch.cut = -1
            ch.pending = False
            g = self.row_delay(ch.row)
            if g:
                ch.pending, ch.delay = True, g
            else:
                self.run_row(c)

    def advance(self):
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
        if self.order == img.loop and self.row == 0:
            self.speed = img.start_speed[self.order]

    def tick(self):
        self.batch = []
        if not self.audition:
            if self.tick_in_row == 0:
                self.begin_row()
            else:
                for c in range(self.channels):
                    ch = self.ch[c]
                    if ch.pending and ch.delay == self.tick_in_row:
                        ch.pending = False
                        self.run_row(c)
        for c in range(self.channels):  # Sxx cuts; in audition only the release armed by release()
            if self.ch[c].cut == self.tick_in_row:
                self.ch[c].cut = -1
                self.key_off(c)
        for c in range(self.channels):
            self.update(c)
        for k in range(self.chips):
            self.chip_passes(k)
        for ch in self.ch:
            ch.keyon = False
        if not self.audition:
            self.tick_in_row += 1
            if self.tick_in_row >= self.speed:
                self.tick_in_row = 0
                self.advance()
        return self.batch

    def position(self):
        return self.order, self.row, self.tick_in_row

    # -- live edits (the SCC's rules) --
    def relocate(self, nxt):
        """Live edit at a tick boundary: continue at the same (order, row) in `nxt`."""
        if self.audition or nxt.variant != self.img.variant:
            return
        old = self.img
        started = self.tick_in_row > 0
        old_speed = old.speed_at(self.order, self.row, started)
        self.img = nxt
        clamped = False
        if self.order >= nxt.orders:
            self.order = nxt.orders - 1
            self.row = nxt.rows[self.order] - 1
            clamped = True
        elif self.row >= nxt.rows[self.order]:
            self.row = nxt.rows[self.order] - 1
            clamped = True
        if clamped:
            self.next_pos = None
            for ch in self.ch:
                ch.pending = False
            self.speed = nxt.speed_at(self.order, self.row, True)
            if not started:
                self.advance()  # the clamped row counts as played: the next row follows it
            return
        new_speed = nxt.speed_at(self.order, self.row, started)
        if new_speed != old_speed:
            self.speed = new_speed
        if not started:
            return
        for c in range(self.channels):
            ch = self.ch[c]
            x = nxt.row(self.order, c, self.row)
            ch.row = x
            if ch.pending:
                g = self.row_delay(x)
                ch.pending = g >= self.tick_in_row
                ch.delay = g
        self.derive_jump()

    # -- audition --
    def audition_note(self, channel, note, slot, fine):
        """Reset, then key `note` on `channel` with `slot` and fine pitch `fine`. Returns False on bad arguments."""
        if not 0 <= channel < self.img.channels or not 0 <= note < NOTES or not -128 <= fine <= 127 or \
                not self.img.defined(slot):
            return False
        self.reset()
        self.audition = True
        self.ch[channel].instrument = slot
        self.key_on(channel, note)
        self.ch[channel].fine = fine
        return True

    def release(self, channel):
        """Audition release between ticks: arms a cut at the current tick (the next tick's cut step keys it off)."""
        self.batch = []
        self.ch[channel].cut = self.tick_in_row

    def silent(self, channel):
        ch = self.ch[channel]
        if not ch.keyed or not ch.active:
            return True
        vol = self.img.envelope(ch.slot, 0)
        if vol is None or not ch.released or ch.env[0] < vol.length - 1:
            return False
        return vol.values[vol.length - 1] == 0 or ch.volume >> 4 == 0


# ---- trace and VGM -----------------------------------------------------------------------------------

def trace(image, ticks):
    """[(index, (order, row, tick) | None, writes)]: the reset batch (index -1), then one batch per tick."""
    p = Player(image)
    out = [(-1, None, list(p.batch))]
    for k in range(ticks):
        pos = p.position()
        out.append((k, pos, list(p.tick())))
    return out


def format_batch(index, pos, writes):
    """One line of saa_trace.txt: "T <index> <order> <row> <tick> :" then " chip:rr=vv" per write."""
    o, r, t = pos if pos is not None else (-1, -1, -1)
    return "T %d %d %d %d :" % (index, o, r, t) + "".join(" %d:%02x=%02x" % w for w in writes)


def wait_bytes(n):
    out = bytearray()
    while n:
        if n == 735:
            out.append(0x62)
            n = 0
        elif n == 882:
            out.append(0x63)
            n = 0
        elif n <= 16:
            out.append(0x70 | (n - 1))
            n = 0
        else:
            k = min(n, 65535)
            out.extend((0x61, k & 0xFF, k >> 8))
            n -= k
    return bytes(out)


def vgm(image, max_ticks):
    """VGM 1.71 bytes, as docs/AUDIO_ENGINE.md "SAA1099: VGM export" specifies. Returns (bytes, info)."""
    p = Player(image)
    body = bytearray()

    def emit(writes):
        for chip, reg, val in writes:
            body.extend((0xBD, reg | (0x80 if chip else 0), val))

    emit(p.batch)
    period_n = image.rate * image.multispeed
    acc = samples = 0
    prev = None   # (state, body offset, samples) of the previous arrival at the loop point
    loop = None
    ticks = 0
    while ticks < max_ticks:
        if p.position() == (image.loop, 0, 0):
            st = p.state()
            if prev is not None and prev[0] == st:
                loop = (prev[1], prev[2])
                break
            prev = (st, 0x100 + len(body), samples)
        writes = p.tick()
        acc += 44100
        n, acc = divmod(acc, period_n)
        samples += n
        emit(writes)
        body.extend(wait_bytes(n))
        ticks += 1
    body.append(0x66)
    header = bytearray(0x100)
    header[0:4] = b"Vgm "
    size = 0x100 + len(body)
    struct.pack_into("<I", header, 0x04, size - 4)
    struct.pack_into("<I", header, 0x08, 0x171)
    struct.pack_into("<I", header, 0x18, samples)
    if loop is not None:
        struct.pack_into("<I", header, 0x1C, loop[0] - 0x1C)
        struct.pack_into("<I", header, 0x20, samples - loop[1])
    struct.pack_into("<I", header, 0x24, image.rate)
    struct.pack_into("<I", header, 0x34, 0x100 - 0x34)
    struct.pack_into("<I", header, 0xC8, image.clock | (VGM_DUAL if image.cms else 0))
    info = {"ticks": ticks, "samples": samples, "looped": loop is not None,
            "loop_offset": loop[0] if loop else 0, "loop_samples": samples - loop[1] if loop else 0}
    return bytes(header + body), info


def parse_vgm(data):
    """The VGM's writes as tick batches split at waits: (header dict, [(writes, wait samples)], loop index | None).
    Read from the format alone (tools/audio/saa_reference_check.py)."""
    if data[0:4] != b"Vgm ":
        raise ValueError("not a VGM")
    hdr = {
        "eof": struct.unpack_from("<I", data, 0x04)[0] + 4,
        "version": struct.unpack_from("<I", data, 0x08)[0],
        "samples": struct.unpack_from("<I", data, 0x18)[0],
        "loop_offset": struct.unpack_from("<I", data, 0x1C)[0],
        "loop_samples": struct.unpack_from("<I", data, 0x20)[0],
        "rate": struct.unpack_from("<I", data, 0x24)[0],
        "data_offset": struct.unpack_from("<I", data, 0x34)[0] + 0x34,
        "saa_clock": struct.unpack_from("<I", data, 0xC8)[0],
    }
    loop_at = hdr["loop_offset"] + 0x1C if hdr["loop_offset"] else None
    at = hdr["data_offset"]
    batches, cur, loop_index = [], [], None
    while True:
        if loop_at is not None and at == loop_at:
            if cur:
                batches.append((cur, 0))
                cur = []
            loop_index = len(batches)
        cmd = data[at]
        if cmd == 0xBD:
            aa, dd = data[at + 1], data[at + 2]
            cur.append((aa >> 7, aa & 0x7F, dd))
            at += 3
        elif cmd == 0x61:
            batches.append((cur, data[at + 1] | data[at + 2] << 8))
            cur = []
            at += 3
        elif cmd in (0x62, 0x63):
            batches.append((cur, 735 if cmd == 0x62 else 882))
            cur = []
            at += 1
        elif 0x70 <= cmd <= 0x7F:
            batches.append((cur, (cmd & 15) + 1))
            cur = []
            at += 1
        elif cmd == 0x66:
            if cur:
                batches.append((cur, 0))
            break
        else:
            raise ValueError("unexpected VGM command 0x%02X at 0x%X" % (cmd, at))
    return hdr, batches, loop_index


# ---- per-clock chip model ----------------------------------------------------------------------------

# SAASound's cs_EnvData: (phases, looping, levels[resolution 4-bit/3-bit][phase][position])
_UP4 = list(range(16))
_DOWN4 = list(range(15, -1, -1))
_UP3 = [v & ~1 for v in range(16)]
_DOWN3 = [14 - (v & ~1) for v in range(16)]
_Z = [0] * 16
ENV_DATA = [
    (1, False, ((_Z, _Z), (_Z, _Z))),
    (1, True, (([15] * 16, [15] * 16), ([14] * 16, [14] * 16))),
    (1, False, ((_DOWN4, _Z), (_DOWN3, _Z))),
    (1, True, ((_DOWN4, _Z), (_DOWN3, _Z))),
    (2, False, ((_UP4, _DOWN4), (_UP3, _DOWN3))),
    (2, True, ((_UP4, _DOWN4), (_UP3, _DOWN3))),
    (1, False, ((_UP4, _Z), (_UP3, _Z))),
    (1, True, ((_UP4, _Z), (_UP3, _Z))),
]

# SAASound's EffectiveAmplitude table (amplitude >> 1, envelope level); output = PDM[a][e] * 4 * (2 - x).
PDM = [
    [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8],
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
    [0, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18, 20, 21, 23],
    [0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30],
    [0, 3, 5, 8, 10, 13, 15, 18, 20, 23, 25, 28, 30, 33, 35, 38],
    [0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39, 42, 45],
    [0, 4, 7, 11, 14, 18, 21, 25, 28, 32, 35, 39, 42, 46, 49, 53],
]

LFSR_SEED = 0x3FFFF
LFSR_TAPS = 0x20400


class EnvGen:
    """SAASound's CSAAEnv, transcribed."""

    def __init__(self):
        self.enabled = False
        self.invert = False
        self.phase = 0
        self.pos = 0
        self.ended = True
        self.looping = False
        self.phases = 1
        self.resolution = 1
        self.new_data = False
        self.next_data = 0
        self.external = False
        self.shape = 0
        self.left = self.right = 0

    def set_levels(self):
        if self.ended and not self.looping:
            self.left = 0
        else:
            self.left = ENV_DATA[self.shape][2][self.resolution - 1][self.phase][self.pos]
        if self.invert:
            self.right = (15 if self.resolution == 1 else 14) - self.left
        else:
            self.right = self.left

    def set_new(self, d):
        self.phase = 0
        self.pos = 0
        self.shape = (d >> 1) & 7
        self.invert = bool(d & 0x01)
        self.external = bool(d & 0x20)
        self.phases, self.looping = ENV_DATA[self.shape][0], ENV_DATA[self.shape][1]
        self.resolution = 2 if d & 0x10 else 1
        self.enabled = bool(d & 0x80)
        if self.enabled:
            self.ended = False
        else:
            self.ended = True
            self.phase = 0
            self.pos = 0
        self.set_levels()

    def control(self, d):
        en = bool(d & 0x80)
        if not en and not self.enabled:
            return
        self.enabled = en
        if not self.enabled:
            self.ended = True
            return
        res = 2 if d & 0x10 else 1
        if self.resolution == 1 and res == 2:
            self.pos &= 0xE
        elif self.resolution == 2 and res == 1:
            self.pos |= 0x1
        self.resolution = res
        if self.ended:
            self.set_new(d)
            self.new_data = False
        else:
            self.set_levels()
            self.new_data = True
            self.next_data = d

    def tick(self):
        if not self.enabled:
            self.ended = True
            self.phase = 0
            self.pos = 0
            return
        if self.ended:
            return
        self.pos += self.resolution
        process = False
        if self.pos >= 16:
            self.phase += 1
            if self.phase == self.phases:
                if not self.looping:
                    self.ended = True
                    process = True
                else:
                    self.ended = False
                    self.phase = 0
                    self.pos -= 16
                    process = True
            else:
                self.ended = False
                self.pos -= 16
        else:
            self.ended = False
        if self.new_data and process:
            self.new_data = False
            self.set_new(self.next_data)
        else:
            self.set_levels()


class Chip:
    """One SAA1099, one clock at a time: the specification's "One clock" steps, writes and output."""

    def __init__(self):
        self.enable = False
        self.sync = False
        self.prescale = 0
        self.oct = [0] * 6
        self.off = [0] * 6
        self.next_oct = [0] * 6
        self.next_off = [0] * 6
        self.pending = [False] * 6
        self.level = [1] * 6
        self.half = [self.H(0, 0)] * 6
        self.count = [self.H(0, 0)] * 6
        self.mode = [0, 0]
        self.lfsr = [LFSR_SEED, LFSR_SEED]
        self.env = [EnvGen(), EnvGen()]
        self.amp_l = [0] * 6
        self.amp_r = [0] * 6
        self.tone = [0] * 6
        self.noise = [0] * 6
        self.out = [(0, 0)] * 6

    @staticmethod
    def H(o, n):
        return (511 - n) << (8 - o)

    def shift(self, g):
        if self.lfsr[g] & 1:
            self.lfsr[g] = (self.lfsr[g] >> 1) ^ LFSR_TAPS
        else:
            self.lfsr[g] >>= 1

    def write(self, reg, val):
        reg &= 0x1F
        # address write
        if reg in (0x18, 0x19):
            e = self.env[reg - 0x18]
            if e.enabled and e.external:
                e.tick()
        # data write
        if reg <= 0x05:
            self.amp_l[reg], self.amp_r[reg] = val & 15, val >> 4
        elif 0x08 <= reg <= 0x0D:
            c = reg - 0x08
            if self.sync:
                self.off[c] = self.next_off[c] = val
                self.oct[c] = self.next_oct[c]
                self.half[c] = self.H(self.oct[c], self.off[c])
                self.pending[c] = False
            else:
                self.next_off[c] = val
                self.pending[c] = True
        elif 0x10 <= reg <= 0x12:
            for c, o in ((2 * (reg - 0x10), val & 7), (2 * (reg - 0x10) + 1, (val >> 4) & 7)):
                if self.sync:
                    self.oct[c] = self.next_oct[c] = o
                    self.off[c] = self.next_off[c]
                    self.half[c] = self.H(self.oct[c], self.off[c])
                    self.pending[c] = False
                else:
                    self.next_oct[c] = o
                    self.pending[c] = True
        elif reg == 0x14:
            self.tone = [(val >> c) & 1 for c in range(6)]
        elif reg == 0x15:
            self.noise = [(val >> c) & 1 for c in range(6)]
        elif reg == 0x16:
            self.mode = [val & 3, (val >> 4) & 3]
        elif reg in (0x18, 0x19):
            self.env[reg - 0x18].control(val)
        elif reg == 0x1C:
            self.enable = bool(val & 1)
            sync = bool(val & 2)
            if sync and not self.sync:
                for c in range(6):
                    self.level[c] = 1
                    self.oct[c], self.off[c] = self.next_oct[c], self.next_off[c]
                    self.pending[c] = False
                    self.half[c] = self.H(self.oct[c], self.off[c])
                self.prescale = 0
            elif self.sync and not sync:
                for c in range(6):
                    self.count[c] = self.half[c]
            self.sync = sync

    def clock(self):
        if self.sync:
            return
        self.prescale = (self.prescale + 1) & 1023
        for c in range(6):
            self.count[c] -= 1
            if self.count[c] == 0:
                self.level[c] ^= 1
                if c in (0, 3) and self.mode[c // 3] == 3:
                    self.shift(c // 3)
                if c in (1, 4):
                    e = self.env[c // 3]
                    if e.enabled and not e.external:
                        e.tick()
                if self.pending[c]:
                    self.oct[c], self.off[c] = self.next_oct[c], self.next_off[c]
                    self.pending[c] = False
                self.half[c] = self.H(self.oct[c], self.off[c])
                self.count[c] = self.half[c]
        for g in (0, 1):
            m = self.mode[g]
            if m < 3 and self.prescale % (256 << m) == 0:
                self.shift(g)

    def output(self, c):
        if self.sync or not self.enable:
            return 0, 0
        t = self.level[c]
        n = self.lfsr[c // 3] & 1
        if self.tone[c] and self.noise[c]:
            x = t * (2 - n)
        elif self.tone[c]:
            x = 2 * t
        elif self.noise[c]:
            x = 2 * n
        else:
            x = 0
        e = self.env[c // 3]
        if c in (2, 5) and e.enabled:
            return (PDM[self.amp_l[c] >> 1][e.left] * 4 * (2 - x),
                    PDM[self.amp_r[c] >> 1][e.right] * 4 * (2 - x))
        return self.amp_l[c] * x * 16, self.amp_r[c] * x * 16

    def render(self, clocks):
        """Advances `clocks` clocks; returns [(offset, channel, left, right)] (the core's event convention)."""
        events = []
        for c in range(6):
            o = self.output(c)
            if o != self.out[c]:
                self.out[c] = o
                events.append((0, c) + o)
        for i in range(clocks):
            self.clock()
            for c in range(6):
                o = self.output(c)
                if o != self.out[c]:
                    self.out[c] = o
                    events.append((i, c) + o)
        return events


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("image")
    ap.add_argument("--trace")
    ap.add_argument("--ticks", type=int, default=1500)
    ap.add_argument("--vgm")
    ap.add_argument("--max-ticks", type=int, default=60 * 8 * 600)
    args = ap.parse_args(argv)
    with open(args.image, "rb") as f:
        data = f.read()
    data = data + bytes(IMAGE_SIZE - len(data)) if len(data) < IMAGE_SIZE else data
    try:
        image = Image(data)
    except LoadError as e:
        print("%s: rejected: %s" % (args.image, e), file=sys.stderr)
        return 1
    if args.trace:
        with open(args.trace, "w") as f:
            for index, pos, writes in trace(image, args.ticks):
                f.write(format_batch(index, pos, writes) + "\n")
    if args.vgm:
        data, info = vgm(image, args.max_ticks)
        with open(args.vgm, "wb") as f:
            f.write(data)
        print("%s: %d bytes, %d ticks, %d samples%s" % (args.vgm, len(data), info["ticks"], info["samples"],
              ", loop %d samples" % info["loop_samples"] if info["looped"] else ", no loop"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
