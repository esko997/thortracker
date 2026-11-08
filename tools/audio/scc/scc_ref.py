#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's SCC / SCC+ player, written from the specification in
docs/AUDIO_ENGINE.md ("SCC and SCC+") independently of the C player (app/src/main/cpp/scc/scc_player.c).
The host test compares the C player's register writes with this one's, tick by tick.

    python3 tools/audio/scc/scc_ref.py IMAGE.bin --trace out.txt --ticks 1500
    python3 tools/audio/scc/scc_ref.py IMAGE.bin --vgm out.vgm [--max-ticks N]

Pure Python 3, no dependencies. Also defines the period and vibrato tables (generated into
app/src/main/cpp/generated/scc_tables.h by prepare_scc.py), an image loader that refuses malformed input, and a
per-clock model of the K051649 / K052539 (MAME's sound loop run one clock at a time, test register bits as the
specification gives them) used by tools/audio/scc_reference_check.py."""
import argparse
import math
import struct
import sys

CLOCK = 3579545
VGM_CLOCK = 1789773
VGM_CLOCK_PLUS = 0x80000000
UNITS_PER_SEMITONE = 64
UNITS_PER_OCTAVE = 768
PITCH_MAX = 8 * UNITS_PER_OCTAVE - 1
PERIOD_MAX = 4095
PERIOD_HALT = 8
DIV_SHIFT = 8
CHANNELS = 5
WAVE_BYTES = 32
NOTES = 96
NOTE_OFF = 0xFE
VOLUME_MAX = 240

IMAGE_SIZE = 65536
MAGIC = b"TTSC"
LAYOUT = 1
VARIANT_SCC, VARIANT_PLUS = 0, 1
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_WAVES, OFF_DATA = 64, 320, 2880, 3904, 5952
INSTRUMENT_SIZE = 16
DATA_CAPACITY = IMAGE_SIZE - OFF_DATA
ENV_NONE = 0xFFFF
ENV_HEADER = 6
MAX_ENVELOPE = 256
FLAG_DEFINED, FLAG_RESTART, FLAG_PITCH_RELATIVE = 0x80, 0x01, 0x02
I_VOLUME, I_ARPEGGIO, I_PITCH, I_WAVE = 2, 4, 6, 8
ENV_FIELDS = (I_VOLUME, I_ARPEGGIO, I_PITCH, I_WAVE)  # envelope kinds in update order: 0 vol, 1 arp, 2 pitch, 3 wave

# VGM 0xD2 ports (the core API's numbering)
PORT_WAVE, PORT_PERIOD, PORT_VOLUME, PORT_KEY, PORT_WAVE_PLUS, PORT_TEST = 0, 1, 2, 3, 4, 5

LETTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
FX = {c: i for i, c in enumerate(LETTERS)}
EFFECTS = set(FX[c] for c in "01234ABCDFGPSTW")


# ---- tables ------------------------------------------------------------------------------------------

def c0_divider():
    """D = (P + 1) of C-0: 3579545 / (32 * f(C-0)), f(C-0) = 440 * 2^(-57/12)."""
    return CLOCK / (32 * 440 * 2 ** (-57 / 12))


def div_table():
    """SC_DIV[f] = floor(D * 2^(-f/768) * 256 + 0.5): (P + 1) of C-0 + f/768 octave in Q8."""
    d = c0_divider()
    return [math.floor(d * 2 ** (-f / UNITS_PER_OCTAVE) * 256 + 0.5) for f in range(UNITS_PER_OCTAVE)]


def sine_table():
    """Vibrato: floor(127 sin(2 pi i / 64) + 0.5)."""
    return [math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64)]


DIV = div_table()
SINE = sine_table()


def period(pitch):
    """P of a pitch 0..6143 (1/64 semitones from C-0): ((DIV[f] + 2^(7+o)) >> (8+o)) - 1, at most 4095."""
    o, f = divmod(pitch, UNITS_PER_OCTAVE)
    return min(PERIOD_MAX, ((DIV[f] + (1 << (7 + o))) >> (DIV_SHIFT + o)) - 1)


def hz(p):
    return CLOCK / (32 * (p + 1))


def s8(b):
    return b - 256 if b >= 128 else b


def u16(data, at):
    return data[at] | data[at + 1] << 8


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
    """Raises LoadError for anything the specification's loader refuses (independent of scc_image.c's wording)."""
    def bad(reason, offset):
        raise LoadError(reason, offset)

    if len(data) != IMAGE_SIZE:
        bad("image size", -1)
    if data[0:4] != MAGIC:
        bad("magic", 0)
    if data[4] != LAYOUT:
        bad("layout", 4)
    if data[5] not in (VARIANT_SCC, VARIANT_PLUS):
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
    instruments, waves, size = data[12], data[13], u16(data, 14)
    if instruments > 64:
        bad("instrument count", 12)
    if not 1 <= waves <= 64:
        bad("wave count", 13)
    if size > DATA_CAPACITY:
        bad("data size", 14)
    for at in range(16, 64):
        if data[at]:
            bad("header padding", at)
    for o in range(orders, 256):
        if data[OFF_ROWS + o]:
            bad("rows beyond the order count", OFF_ROWS + o)
        for c in range(CHANNELS):
            at = OFF_ORDERS + 2 * (CHANNELS * o + c)
            if data[at] or data[at + 1]:
                bad("pattern beyond the order count", at)

    def envelope(at_field, kind):
        off = u16(data, at_field)
        if off == ENV_NONE:
            if kind == 3:
                bad("wave envelope missing", at_field)
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
            if kind == 3 and not 1 <= v <= waves:
                bad("wave envelope value", at)

    defined = set()
    for n in range(1, 65):
        rec = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (n - 1)
        body = data[rec:rec + INSTRUMENT_SIZE]
        if not any(body):
            continue
        if n > instruments:
            bad("instrument beyond the count", rec)
        if not body[0] & FLAG_DEFINED or body[0] & ~(FLAG_DEFINED | FLAG_RESTART | FLAG_PITCH_RELATIVE) & 0xFF:
            bad("instrument flags", rec)
        if body[1]:
            bad("instrument padding", rec + 1)
        for k in range(10, 16):
            if body[k]:
                bad("instrument padding", rec + k)
        for kind, field in enumerate(ENV_FIELDS):
            envelope(rec + field, kind)
        defined.add(n)
    for k in range(waves, 64):
        for i in range(WAVE_BYTES):
            if data[OFF_WAVES + WAVE_BYTES * k + i]:
                bad("wave beyond the count", OFF_WAVES + WAVE_BYTES * k + i)

    checked = set()
    for o in range(orders):
        rows = data[OFF_ROWS + o] or 256
        for c in range(CHANNELS):
            at_field = OFF_ORDERS + 2 * (CHANNELS * o + c)
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
                            (code == FX["F"] and param == 0) or (code == FX["W"] and not 1 <= param <= waves):
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
        self.plus = self.variant == VARIANT_PLUS
        self.multispeed = data[6]
        self.speed = data[7]
        self.orders, self.loop = u16(data, 8), u16(data, 10)
        self.instrument_count = data[12]
        self.wave_count = data[13]
        self.size = u16(data, 14)
        self.rows = [(data[OFF_ROWS + o] or 256) for o in range(self.orders)]
        self.patterns = [[u16(data, OFF_ORDERS + 2 * (CHANNELS * o + c)) for c in range(CHANNELS)]
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
                for c in range(CHANNELS):
                    x = self.row(o, c, r)
                    for code, param in (x[2] if x else []):
                        if code == FX["F"]:
                            s = param
        return speeds

    def speed_at(self, order, row, started):
        """Ticks per row straight play has at (order, row): the order's start speed, the Fxx of the rows before it
        and, once it has started, of the row itself."""
        s = self.start_speed[order]
        for r in range(row + 1 if started else row):
            for c in range(CHANNELS):
                x = self.row(order, c, r)
                for code, param in (x[2] if x else []):
                    if code == FX["F"]:
                        s = param
        return s

    def flags(self, slot):
        if not 1 <= slot <= 64:
            return 0
        return self.data[OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)]

    def defined(self, slot):
        return bool(self.flags(slot) & FLAG_DEFINED)

    def envelope(self, slot, kind):
        """Envelope kind 0..3 (volume, arpeggio, pitch, wave) of a defined slot, or None."""
        off = u16(self.data, OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1) + ENV_FIELDS[kind])
        if off == ENV_NONE:
            return None
        base = OFF_DATA + off
        length, lp, rel = u16(self.data, base), u16(self.data, base + 2), u16(self.data, base + 4)
        raw = self.data[base + ENV_HEADER: base + ENV_HEADER + length]
        values = [s8(v) for v in raw] if kind in (1, 2) else list(raw)
        return Envelope(values, -1 if lp == ENV_NONE else lp, -1 if rel == ENV_NONE else rel)

    def wave(self, k):
        """Wave k (1..64) as 32 unsigned bytes."""
        return self.data[OFF_WAVES + WAVE_BYTES * (k - 1): OFF_WAVES + WAVE_BYTES * k]


# ---- player ------------------------------------------------------------------------------------------

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
        self.env = [0, 0, 0, 0]
        self.acc = 0              # relative pitch envelope accumulator
        self.force = False        # next update writes the period forced
        self.restart = False      # ... wrapped in test | 0x20
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
        self.wave_lock = 0
        self.wave = 0             # wave number of the last update
        self.claim = False        # SCC: claimed wave RAM 3 this tick


class Player:
    def __init__(self, image):
        self.img = image
        self.reset()

    # -- writes --
    def protected(self, ram):
        return bool(self.sh_test & 0x40) or (not self.plus and ram == 3 and bool(self.sh_test & 0x80))

    def write(self, port, reg, val, forced=False):
        if port in (PORT_WAVE, PORT_WAVE_PLUS):
            ram = reg >> 5
            if self.protected(ram):
                return
            if not forced and self.sh_ram[ram][reg & 31] == val:
                return
            self.sh_ram[ram][reg & 31] = val
        elif port == PORT_PERIOD:
            if not forced and self.sh_period[reg] == val:
                return
            self.sh_period[reg] = val
        elif port == PORT_VOLUME:
            if not forced and self.sh_volume[reg] == val:
                return
            self.sh_volume[reg] = val
        elif port == PORT_KEY:
            if not forced and self.sh_key == val:
                return
            self.sh_key = val
        else:
            if not forced and self.sh_test == val:
                return
            self.sh_test = val
        self.batch.append((port, reg, val))

    # -- reset, placing --
    def reset(self):
        img = self.img
        self.plus = img.plus
        self.wave_port = PORT_WAVE_PLUS if self.plus else PORT_WAVE
        self.rams = 5 if self.plus else 4
        self.order = self.row = self.tick_in_row = 0
        self.speed = img.speed
        self.next_pos = None
        self.audition = False
        self.test = 0
        self.owner3 = -1
        self.ch = [Channel() for _ in range(CHANNELS)]
        self.sh_test = self.sh_key = 0
        self.sh_period = [0] * (2 * CHANNELS)
        self.sh_volume = [0] * CHANNELS
        self.sh_ram = [[0] * WAVE_BYTES for _ in range(CHANNELS)]
        self.batch = []
        self.write(PORT_TEST, 0, 0, True)
        self.write(PORT_KEY, 0, 0, True)
        for c in range(CHANNELS):
            self.write(PORT_PERIOD, 2 * c, 0, True)
            self.write(PORT_PERIOD, 2 * c + 1, 0, True)
        for c in range(CHANNELS):
            self.write(PORT_VOLUME, c, 0, True)
        for reg in range(WAVE_BYTES * self.rams):
            self.write(self.wave_port, reg, 0, True)
        return self.batch

    def place(self, order):
        """Seek fallback: the reset, then order `order` row 0 with the speed straight play starts it with."""
        self.reset()
        self.order = order
        self.speed = self.img.start_speed[order]
        return self.batch

    # -- notes --
    def key_on(self, c, note):
        ch = self.ch[c]
        flags = self.img.flags(ch.instrument)
        if not flags & FLAG_DEFINED:
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
        ch.force = True
        ch.restart = bool(flags & FLAG_RESTART)
        if not self.plus and c >= 3:
            ch.claim = True

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

    def set_test(self, value):
        old = self.test
        self.test = value
        self.write(PORT_TEST, 0, value)
        if (old ^ value) & 3:
            for ch in self.ch:
                if ch.active:
                    ch.force = True

    def run_row(self, c):
        ch = self.ch[c]
        note, inst, effects = ch.row if ch.row else (None, None, [])
        playing = note is not None and note < NOTES
        if playing:
            ch.arp = 0
            ch.fine = 0
            ch.wave_lock = 0
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
            elif code == FX["A"]:
                ch.volslide = param
            elif code == FX["C"]:
                ch.volume = param * 16
            elif code == FX["P"]:
                ch.fine = param - 0x80
            elif code == FX["S"]:
                ch.cut = param
            elif code == FX["T"]:
                self.set_test(param)
            elif code == FX["W"]:
                ch.wave_lock = param
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
        flags = img.flags(ch.slot)
        if not flags & FLAG_DEFINED:
            ch.keyed = ch.active = False
            return
        envs = [img.envelope(ch.slot, k) for k in range(4)]
        for k, env in enumerate(envs):
            if env is not None and ch.env[k] >= env.length:
                ch.env[k] = env.length - 1
        e = envs[0].values[ch.env[0]] if envs[0] else 15
        a = envs[1].values[ch.env[1]] if envs[1] else 0
        v = envs[2].values[ch.env[2]] if envs[2] else 0
        w = envs[3].values[ch.env[3]]
        if ch.wave_lock:
            w = ch.wave_lock
        if flags & FLAG_PITCH_RELATIVE:
            ch.acc = max(-6144, min(6144, ch.acc + 4 * v))
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
        p = period(pitch)
        if ch.force:
            if ch.restart:
                self.write(PORT_TEST, 0, self.test | 0x20)
            self.write(PORT_PERIOD, 2 * c, p & 0xFF, True)
            self.write(PORT_PERIOD, 2 * c + 1, p >> 8, True)
            if ch.restart:
                self.write(PORT_TEST, 0, self.test)
            ch.force = ch.restart = False
        else:
            self.write(PORT_PERIOD, 2 * c, p & 0xFF)
            self.write(PORT_PERIOD, 2 * c + 1, p >> 8)
        cv = ch.volume >> 4
        vol = e * cv // 15
        if e > 0 and cv > 0 and vol < 1:
            vol = 1
        self.write(PORT_VOLUME, c, vol)
        if not self.plus and c >= 3 and w != ch.wave:
            ch.claim = True
        ch.wave = w
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
            ch.slide = min(6144, ch.slide + ch.slide_speed)
        elif ch.slide_mode == 2:
            ch.slide = max(-6144, ch.slide - ch.slide_speed)
        elif ch.slide_mode == 3:
            if ch.base < ch.target:
                ch.base = min(ch.target, ch.base + ch.slide_speed)
            elif ch.base > ch.target:
                ch.base = max(ch.target, ch.base - ch.slide_speed)
        if ch.vib_speed and ch.vib_depth:
            ch.vib_phase = (ch.vib_phase + ch.vib_speed) % 64
        if ch.volslide:
            ch.volume = max(0, min(VOLUME_MAX, ch.volume + (ch.volslide >> 4) - (ch.volslide & 15)))

    def wave_pass(self):
        if not self.plus:
            if self.ch[4].claim:
                self.owner3 = 4
            elif self.ch[3].claim:
                self.owner3 = 3
            self.ch[3].claim = self.ch[4].claim = False
        for k in range(self.rams):
            owner = k if self.plus or k < 3 else self.owner3
            if owner < 0 or not self.ch[owner].active or self.protected(k):
                continue
            wave = self.img.wave(self.ch[owner].wave)
            for i in range(WAVE_BYTES):
                self.write(self.wave_port, WAVE_BYTES * k + i, wave[i])

    def key_value(self):
        return sum(1 << c for c in range(CHANNELS) if self.ch[c].keyed)

    # -- sequencing --
    def derive_jump(self):
        b = d = None
        for c in range(CHANNELS):
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
        for c in range(CHANNELS):
            self.ch[c].row = self.img.row(self.order, c, self.row)
        for c in range(CHANNELS):
            x = self.ch[c].row
            for code, param in (x[2] if x else []):
                if code == FX["F"]:
                    self.speed = param
        self.derive_jump()
        for c in range(CHANNELS):
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
                for c in range(CHANNELS):
                    ch = self.ch[c]
                    if ch.pending and ch.delay == self.tick_in_row:
                        ch.pending = False
                        self.run_row(c)
        for c in range(CHANNELS):  # Sxx cuts; in audition only the release armed by release()
            if self.ch[c].cut == self.tick_in_row:
                self.ch[c].cut = -1
                self.key_off(c)
        for c in range(CHANNELS):
            if self.ch[c].active:
                self.update(c)
        self.wave_pass()
        self.write(PORT_KEY, 0, self.key_value())
        if not self.audition:
            self.tick_in_row += 1
            if self.tick_in_row >= self.speed:
                self.tick_in_row = 0
                self.advance()
        return self.batch

    def position(self):
        return self.order, self.row, self.tick_in_row

    # -- live edits --
    def relocate(self, nxt):
        """Live edit at a tick boundary: continue at the same (order, row) in `nxt`."""
        if self.audition or nxt.variant != self.img.variant:
            return
        old = self.img
        started = self.tick_in_row > 0
        old_speed = old.speed_at(self.order, self.row, started)
        self.img = nxt
        for ch in self.ch:
            if ch.active and not nxt.defined(ch.slot):
                ch.keyed = ch.active = False
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
        for c in range(CHANNELS):
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
        if not 0 <= channel < CHANNELS or not 0 <= note < NOTES or not -128 <= fine <= 127 or not self.img.defined(slot):
            return False
        self.reset()
        self.audition = True
        self.ch[channel].instrument = slot
        self.key_on(channel, note)
        self.ch[channel].fine = fine
        return True

    def release(self, channel):
        """Audition release between ticks: arms a cut at the current tick, so the next tick's cut step keys the
        note off (release phase or cut) and that tick's batch carries the key register."""
        self.batch = []
        self.ch[channel].cut = self.tick_in_row

    def silent(self, channel):
        ch = self.ch[channel]
        if not ch.keyed or not ch.active:
            return True
        vol = self.img.envelope(ch.slot, 0)
        if vol is None or not ch.released or ch.env[0] < vol.length - 1:
            return False
        cv = ch.volume >> 4
        e = vol.values[vol.length - 1]
        return e == 0 or cv == 0


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
    """One line of scc_trace.txt: "T <index> <order> <row> <tick> :" then " p:rr=vv" per write."""
    o, r, t = pos if pos is not None else (-1, -1, -1)
    return "T %d %d %d %d :" % (index, o, r, t) + "".join(" %d:%02x=%02x" % w for w in writes)


def dump_writes(plus, test, key, period_regs, volumes, rams):
    """The VGM loop point's register dump from a register state."""
    out = [(PORT_TEST, 0, test & 0x03)]
    port = PORT_WAVE_PLUS if plus else PORT_WAVE
    for k in range(5 if plus else 4):
        for i in range(WAVE_BYTES):
            out.append((port, WAVE_BYTES * k + i, rams[k][i]))
    for reg in range(2 * CHANNELS):
        out.append((PORT_PERIOD, reg, period_regs[reg]))
    for c in range(CHANNELS):
        out.append((PORT_VOLUME, c, volumes[c]))
    out.append((PORT_KEY, 0, key))
    out.append((PORT_TEST, 0, test))
    return out


def wait_bytes(n):
    out = bytearray()
    while n:
        if n == 735:
            out.append(0x62)
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
    """VGM 1.71 bytes, as docs/AUDIO_ENGINE.md "SCC and SCC+: VGM export" specifies. Returns (bytes, info)."""
    p = Player(image)
    body = bytearray()

    def emit(writes):
        for port, reg, val in writes:
            body.extend((0xD2, port, reg, val))

    emit(p.batch)
    period_n = 60 * image.multispeed
    acc = samples = 0
    loop_at = loop_samples = None
    ticks = 0
    looped = False
    while ticks < max_ticks:
        if p.position() == (image.loop, 0, 0):
            if loop_at is not None:
                looped = True
                break
            loop_at, loop_samples = 0x100 + len(body), samples
            emit(dump_writes(image.plus, p.sh_test, p.sh_key, p.sh_period, p.sh_volume, p.sh_ram))
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
    if looped:
        struct.pack_into("<I", header, 0x1C, loop_at - 0x1C)
        struct.pack_into("<I", header, 0x20, samples - loop_samples)
    struct.pack_into("<I", header, 0x24, 60)
    struct.pack_into("<I", header, 0x34, 0x100 - 0x34)
    struct.pack_into("<I", header, 0x9C, VGM_CLOCK | (VGM_CLOCK_PLUS if image.plus else 0))
    info = {"ticks": ticks, "samples": samples, "looped": looped,
            "loop_offset": loop_at if looped else 0, "loop_samples": samples - loop_samples if looped else 0}
    return bytes(header + body), info


# ---- per-clock chip model ----------------------------------------------------------------------------

class Chip:
    """K051649 (plus=False) / K052539 in SCC+ mode (plus=True), one clock at a time: MAME's sound loop
    (k051649.cpp sound_stream_update) with the specification's test register bits 0, 1, 5, 6, 7."""

    def __init__(self, plus):
        self.plus = bool(plus)
        self.test = 0
        self.key = 0
        self.ram = [[0] * WAVE_BYTES for _ in range(5)]
        self.period = [0] * CHANNELS
        self.effective = [0] * CHANNELS
        self.clk = [0] * CHANNELS
        self.pos = [0] * CHANNELS
        self.volume = [15] * CHANNELS
        self.latch = [0] * CHANNELS

    def write(self, port, reg, val):
        if port == PORT_WAVE:
            if self.plus or reg > 0x7F or self.test & 0x40 or (self.test & 0x80 and reg >= 0x60):
                return
            self.ram[reg >> 5][reg & 31] = s8(val)
        elif port == PORT_WAVE_PLUS:
            if not self.plus or reg > 0x9F or self.test & 0x40:
                return
            self.ram[reg >> 5][reg & 31] = s8(val)
        elif port == PORT_PERIOD:
            if reg >= 2 * CHANNELS:
                return
            c = reg >> 1
            if reg & 1:
                self.period[c] = (self.period[c] & 0x0FF) | (val & 0x0F) << 8
            else:
                self.period[c] = (self.period[c] & 0xF00) | val
            p = self.period[c]
            self.effective[c] = p & 0xFF if self.test & 0x02 else p >> 8 if self.test & 0x01 else p
            if self.test & 0x20:
                self.pos[c] = 0
            self.clk[c] = -1
        elif port == PORT_VOLUME:
            if reg < CHANNELS:
                self.volume[reg] = val & 0x0F
        elif port == PORT_KEY:
            self.key = val & 0x1F
        elif port == PORT_TEST:
            self.test = val

    def ram_of(self, c):
        return 3 if not self.plus and c == 4 else c

    def level(self, c):
        return self.latch[c] >> 4

    def clock(self):
        """One clock; returns [(channel, new level)] for the channels whose level changed."""
        changes = []
        for c in range(CHANNELS):
            e = self.effective[c]
            if e <= PERIOD_HALT:
                continue
            self.clk[c] += 1
            if self.clk[c] > e:
                self.pos[c] = (self.pos[c] + 1) & 31
                self.clk[c] = 0
            if self.clk[c] == 0:
                before = self.latch[c] >> 4
                sample = self.ram[self.ram_of(c)][self.pos[c]] if self.key >> c & 1 else 0
                self.latch[c] = sample * self.volume[c]
                if self.latch[c] >> 4 != before:
                    changes.append((c, self.latch[c] >> 4))
        return changes

    def output(self):
        return sum(self.latch[c] >> 4 for c in range(CHANNELS))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("image")
    ap.add_argument("--trace")
    ap.add_argument("--ticks", type=int, default=1500)
    ap.add_argument("--vgm")
    ap.add_argument("--max-ticks", type=int, default=60 * 60 * 10)
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
