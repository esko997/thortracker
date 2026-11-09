#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's OPM (YM2151) player, written from the specification in
docs/AUDIO_ENGINE.md ("OPM: Thor Tracker's own player on a YM2151") independently of the C player
(app/src/main/cpp/opm/opm_player.c). The host test compares the C player's register writes with this one's, tick
by tick, and its VGM bytes with vgm() here.

    python3 tools/audio/opm/opm_ref.py IMAGE.bin --trace out.txt [--ticks 1500]
    python3 tools/audio/opm/opm_ref.py IMAGE.bin --vgm out.vgm [--max-ticks N]

The trace file has one line per player tick (the reset batch is not part of it), in the host test's
opm_trace.txt format: "order row tick:" (the position before the tick), then " RR=VV" per write, with " |" after
the key-off phase when it is not empty. Pure Python 3, no dependencies."""
import argparse
import struct
import sys

CLOCK = 4000000                       # the X68000's YM2151 clock (16 MHz crystal / 4)
NATIVE_DIVIDER = 64
NATIVE_RATE = CLOCK / NATIVE_DIVIDER  # 62500 Hz
IMAGE_SIZE = 65536
MAGIC = b"TTOM"
LAYOUT = 1
CHANNELS = 8
NOISE_CHANNEL = 7
MAX_ORDERS = 256
MAX_INSTRUMENTS = 64
MAX_EFFECTS = 4
MAX_MULTISPEED = 8

H_MULTISPEED, H_SPEED, H_LFO_WAVE, H_ORDERS, H_LOOP, H_INSTRUMENTS, H_CSM = 5, 6, 7, 8, 10, 12, 13
H_DATA_SIZE, H_LFO_RATE, H_AMD, H_PMD, H_TIMER_A, HEADER_SIZE = 14, 16, 17, 18, 20, 64
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 4416, 6464
INSTRUMENT_SIZE = 32
DATA_CAPACITY = IMAGE_SIZE - OFF_DATA
I_FLAGS, I_CONNECT, I_SENS, I_NOISE, I_OPS = 0, 1, 2, 3, 4
OPERATOR_BYTES = 6
I_SWEEP_START, I_SWEEP_END, I_SWEEP_SPEED = 28, 29, 30
FLAG_DEFINED, FLAG_NOISE = 0x80, 0x01
SWEEP_LIMIT = 96

NOTE_OFF = 0xFE
NOTE_MIN, NOTE_MAX = 3, 98
UNITS_PER_SEMITONE = 64
PITCH_OFFSET = 123   # round(64 * 12 * log2(4000000 / 3579545)) = round(123.05)
PITCH_MIN = 187      # key code C#0, key fraction 0, at 4 MHz
PITCH_MAX = 6330     # key code C-8, key fraction 63
SLIDE_LIMIT = 6144
VOL8_MAX = 1016      # volume 7F x 8
KEY_ALL = 0x78       # $08 operator mask: all four operators
SHADOW_PMD = 0x1A    # shadow slot of $19's PMD value (stored as the 7-bit PMD)

# Operator k (0..3 = operators 1..4 = M1, C1, M2, C2) of channel c sits at bank + 8 * OPERATOR_SLOTS[k] + c.
OPERATOR_SLOTS = (0, 2, 1, 3)
# Carrier operators per algorithm: bit k = operator k + 1 (the OPN family's routing).
CARRIERS = (0x8, 0x8, 0x8, 0x8, 0xA, 0xE, 0xE, 0xF)
# Operator register banks in record order: $40 DT1/MUL, $60 TL, $80 KS/AR, $A0 AMS-EN/D1R, $C0 DT2/D2R, $E0 D1L/RR.
OPERATOR_BANKS = (0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

# Vibrato: floor(127 sin(2 pi i / 64) + 0.5), written out (the C player has the same literal table).
SINE = (0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126,
        127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12,
        0, -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
        -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12)

LETTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
FX = {c: i for i, c in enumerate(LETTERS)}
EFFECTS = frozenset(FX[c] for c in "012348ABCDEFGHILMNPQSTVWX")
LOCKS = frozenset(FX[c] for c in "EIMHN")       # applied again at a key-on of the same row, in column order
SHARED = frozenset(FX[c] for c in "LTVWXQ")     # the chip's one resource: set, then the globals at once
ROW_START = frozenset(FX[c] for c in "BDFG")    # acted on at the row start, not when the row runs

# The dump of the register state at the VGM loop point, in the spec's order (PMD is written as $19).
DUMP_GLOBALS = (0x0F, 0x10, 0x11, 0x18, 0x19, SHADOW_PMD, 0x1B, 0x14)


def operator_reg(bank, channel, k):
    return bank + 8 * OPERATOR_SLOTS[k] + channel


def key_code(pitch):
    """($28 key code, $30 key fraction << 2) of a pitch in 1/64 semitones (0 = C-0), clamped to the 4 MHz range."""
    p = max(PITCH_MIN, min(PITCH_MAX, pitch))
    c = p - PITCH_OFFSET
    n, kf = c >> 6, c & 63
    octave, s = divmod(n - 1, 12)
    return octave << 4 | (s + s // 3), kf << 2


class ImageError(ValueError):
    def __init__(self, reason, offset):
        super().__init__("%s (byte %d)" % (reason, offset))
        self.reason, self.offset = reason, offset


def _param_problem(code, param, orders, channel):
    x, y = param >> 4, param & 15
    if code == FX["B"]:
        return "Bxx jumps past the last order" if param >= orders else None
    if code in (FX["C"], FX["I"], FX["T"], FX["V"]):
        return "%sxx above 7F" % LETTERS[code] if param > 0x7F else None
    if code == FX["E"]:
        return "Exx feedback above 7" if param > 7 else None
    if code == FX["F"]:
        return "F00: speed must be 1..FF" if param == 0 else None
    if code == FX["H"]:
        return "Hxy PMS above 7 or AMS above 3" if x > 7 or y > 3 else None
    if code == FX["M"]:
        return "Mxy operator must be 1..4" if not 1 <= x <= 4 else None
    if code == FX["N"]:
        if channel != NOISE_CHANNEL:
            return "Nxx off FM 8"
        return "Nxx noise frequency above 1F" if param > 0x1F else None
    if code == FX["W"]:
        return "Wxx waveform above 3" if param > 3 else None
    if code == FX["X"]:
        return "Xxx CSM above 1" if param > 1 else None
    if code not in EFFECTS:
        return "unknown effect code"
    return None


class Image:
    """A validated OPM image: the checks of om_load_image (opm_image.c) as the specification lists them, raising
    ImageError(reason, offset) on the first problem, and the decoded tables."""

    def __init__(self, data):
        data = bytes(data)
        if len(data) != IMAGE_SIZE:
            raise ImageError("image size", 0)
        self.data = data
        if data[0:4] != MAGIC:
            raise ImageError("not an OPM image (magic)", 0)
        if data[4] != LAYOUT:
            raise ImageError("unknown layout", 4)
        self.multispeed = data[H_MULTISPEED]
        if not 1 <= self.multispeed <= MAX_MULTISPEED:
            raise ImageError("multispeed outside 1..8", H_MULTISPEED)
        self.speed = data[H_SPEED]
        if self.speed < 1:
            raise ImageError("speed 0", H_SPEED)
        self.lfo_wave = data[H_LFO_WAVE]
        if self.lfo_wave > 3:
            raise ImageError("LFO waveform above 3", H_LFO_WAVE)
        self.orders, self.loop = struct.unpack_from("<HH", data, H_ORDERS)
        if not 1 <= self.orders <= MAX_ORDERS:
            raise ImageError("order count outside 1..256", H_ORDERS)
        if self.loop >= self.orders:
            raise ImageError("loop order past the last order", H_LOOP)
        self.instrument_count = data[H_INSTRUMENTS]
        if self.instrument_count > MAX_INSTRUMENTS:
            raise ImageError("instrument count above 64", H_INSTRUMENTS)
        self.csm = data[H_CSM]
        if self.csm > 1:
            raise ImageError("CSM flag above 1", H_CSM)
        self.data_size = struct.unpack_from("<H", data, H_DATA_SIZE)[0]
        if self.data_size > DATA_CAPACITY:
            raise ImageError("data size above the data region", H_DATA_SIZE)
        self.lfo_rate, self.amd, self.pmd = data[H_LFO_RATE], data[H_AMD], data[H_PMD]
        if self.amd > 0x7F:
            raise ImageError("AMD above 127", H_AMD)
        if self.pmd > 0x7F:
            raise ImageError("PMD above 127", H_PMD)
        if data[19]:
            raise ImageError("header reserved byte", 19)
        self.timer_a = struct.unpack_from("<H", data, H_TIMER_A)[0]
        if self.timer_a > 1023:
            raise ImageError("timer A above 1023", H_TIMER_A)
        for i in range(22, HEADER_SIZE):
            if data[i]:
                raise ImageError("header reserved bytes", i)
        self.rows = []
        for o in range(MAX_ORDERS):
            if o < self.orders:
                self.rows.append(data[OFF_ROWS + o] or 256)
            elif data[OFF_ROWS + o]:
                raise ImageError("rows of an order past the order count", OFF_ROWS + o)
        self.patterns = []
        for o in range(MAX_ORDERS):
            entry = [struct.unpack_from("<H", data, OFF_ORDERS + 2 * (CHANNELS * o + c))[0] for c in range(CHANNELS)]
            if o < self.orders:
                self.patterns.append(entry)
            elif any(entry):
                raise ImageError("pattern of an order past the order count", OFF_ORDERS + 2 * CHANNELS * o)
        self._instruments = {}
        for slot in range(1, MAX_INSTRUMENTS + 1):
            self._check_instrument(slot)
        if self.instrument_count and self.instrument(self.instrument_count) is None:
            raise ImageError("instrument count names an empty slot", H_INSTRUMENTS)
        self._decoded = {}
        for o in range(self.orders):
            for c in range(CHANNELS):
                self.pattern_rows(o, c, check=True)
        self.start_speed = self._start_speeds()

    def _check_instrument(self, slot):
        base = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        rec = self.data[base: base + INSTRUMENT_SIZE]
        if not any(rec):
            return
        if slot > self.instrument_count:
            raise ImageError("instrument record past the instrument count", base)
        if rec[I_FLAGS] & ~(FLAG_DEFINED | FLAG_NOISE) or not rec[I_FLAGS] & FLAG_DEFINED:
            raise ImageError("instrument flags", base + I_FLAGS)
        noise = bool(rec[I_FLAGS] & FLAG_NOISE)
        if rec[I_CONNECT] & 0xC0:
            raise ImageError("instrument connection bits 6-7", base + I_CONNECT)
        if rec[I_SENS] & 0x8C:
            raise ImageError("instrument sensitivity reserved bits", base + I_SENS)
        if rec[I_NOISE] > 31 or (rec[I_NOISE] and not noise):
            raise ImageError("instrument noise frequency", base + I_NOISE)
        reserved = (0x80, 0x80, 0x20, 0x60, 0x20, 0x00)
        for k in range(4):
            for j in range(OPERATOR_BYTES):
                at = I_OPS + OPERATOR_BYTES * k + j
                if rec[at] & reserved[j]:
                    raise ImageError("operator reserved bits", base + at)
        for at in (I_SWEEP_START, I_SWEEP_END):
            v = struct.unpack_from("b", rec, at)[0]
            if not -SWEEP_LIMIT <= v <= SWEEP_LIMIT:
                raise ImageError("sweep start/end outside -96..96 semitones", base + at)
        if rec[31]:
            raise ImageError("instrument reserved byte 31", base + 31)
        self._instruments[slot] = {
            "noise": noise,
            "connect": rec[I_CONNECT],
            "sens": rec[I_SENS],
            "nfrq": rec[I_NOISE],
            "ops": [list(rec[I_OPS + OPERATOR_BYTES * k: I_OPS + OPERATOR_BYTES * (k + 1)]) for k in range(4)],
            "sweep": (struct.unpack_from("b", rec, I_SWEEP_START)[0], struct.unpack_from("b", rec, I_SWEEP_END)[0],
                      rec[I_SWEEP_SPEED]),
        }

    def instrument(self, slot):
        """The decoded record of a defined slot 1..64, else None."""
        return self._instruments.get(slot)

    def pattern_rows(self, order, channel, check=False):
        """Every row of (order, channel) decoded from the pattern's start: None (empty) or (note, slot, effects)
        with note None / 3..98 / NOTE_OFF, slot 0 = none and effects [(code, param)] in column order."""
        offset, n = self.patterns[order][channel], self.rows[order]
        key = (offset, n, channel)
        if key in self._decoded:
            return self._decoded[key]
        end = self.data_size
        rows, at = [], offset

        def byte():
            nonlocal at
            if at >= end:
                raise ImageError("pattern data past the data size", OFF_DATA + at)
            b = self.data[OFF_DATA + at]
            at += 1
            return b

        while len(rows) < n:
            where = OFF_DATA + at
            m = byte()
            if m & 0x80:
                rows.extend([None] * min((m & 0x7F) + 1, n - len(rows)))
                continue
            if m == 0 or m & 0x60 or (m >> 2) & 7 > MAX_EFFECTS:
                raise ImageError("row mask", where)
            note, slot, effects = None, 0, []
            if m & 1:
                where = OFF_DATA + at
                note = byte()
                if note != NOTE_OFF and not NOTE_MIN <= note <= NOTE_MAX:
                    raise ImageError("note outside D#0..D-8", where)
            if m & 2:
                where = OFF_DATA + at
                slot = byte()
                ins = self.instrument(slot)
                if ins is None:
                    raise ImageError("row names an undefined instrument", where)
                if ins["noise"] and channel != NOISE_CHANNEL:
                    raise ImageError("noise instrument off FM 8", where)
            for _ in range((m >> 2) & 7):
                where = OFF_DATA + at
                code = byte()
                if code > 35:
                    raise ImageError("effect code above Z", where)
                param = byte()
                if check:
                    why = _param_problem(code, param, self.orders, channel)
                    if why:
                        raise ImageError(why, where + 1)
                effects.append((code, param))
            rows.append((note, slot, effects))
        self._decoded[key] = rows
        return rows

    def _start_speeds(self):
        """Ticks per row each order starts with in sequential straight play: the header speed, then the last Fxx of
        each order (rows, then channels, then columns; jumps ignored)."""
        speeds, s = [], self.speed
        for o in range(self.orders):
            speeds.append(s)
            for r in range(self.rows[o]):
                for c in range(CHANNELS):
                    row = self.pattern_rows(o, c)[r]
                    for code, param in (row[2] if row else ()):
                        if code == FX["F"]:
                            s = param
        return speeds


class Channel:
    def __init__(self):
        self.row = None
        self.delay = None          # tick of a delayed row (Gxx), None = none pending
        self.cut = None            # Sxx tick, None = none
        self.instrument = 0
        self.active = False        # a note has been keyed since reset
        self.keyed = False         # what $08 last said on the chip
        self.keyon = False         # a key-on is pending (the update writes it)
        self.ops = [[0] * OPERATOR_BYTES for _ in range(4)]
        self.connect = 0
        self.sens = 0
        self.carriers = 0
        self.base = self.target = 0
        self.fine = 0
        self.arp = self.arp_step = 0
        self.slide_mode = None     # "up", "down", "porta"
        self.slide_speed = 0
        self.slide = 0
        self.vib_speed = self.vib_depth = self.vib_phase = 0
        self.sweep = self.sweep_end = self.sweep_step = 0
        self.vol8 = VOL8_MAX
        self.volslide = 0
        self.pan = 0xC0


class Player:
    def __init__(self, image):
        self.img = image
        self.reset()

    # -- writes --
    def write(self, reg, val):
        """Diffed through the 256-register shadow; $19 with bit 7 set is PMD, shadowed at $1A."""
        slot, value = (SHADOW_PMD, val & 0x7F) if reg == 0x19 and val & 0x80 else (reg, val)
        if self.shadow[slot] != value:
            self.shadow[slot] = value
            self.rest.append((reg, val))

    def globals(self):
        self.write(0x18, self.lfo_rate)
        self.write(0x1B, self.lfo_wave)
        self.write(0x19, self.amd)
        self.write(0x19, 0x80 | self.pmd)
        self.write(0x0F, self.noise)
        self.write(0x10, self.timer_a >> 2)
        self.write(0x11, self.timer_a & 3)
        self.write(0x14, 0x81 if self.csm else 0x00)

    def finish(self):
        self.batch = self.offs + self.rest
        self.split = len(self.offs)
        return self.batch

    def reset(self):
        img = self.img
        self.shadow = [0] * 256
        self.offs, self.rest = [], []
        self.ch = [Channel() for _ in range(CHANNELS)]
        self.lfo_rate, self.lfo_wave, self.amd, self.pmd = img.lfo_rate, img.lfo_wave, img.amd, img.pmd
        self.csm, self.timer_a = img.csm, img.timer_a
        self.noise = 0
        self.speed = img.speed
        self.order = self.row = self.tick_in_row = 0
        self.jump = None
        for c in range(CHANNELS):
            self.write(0x20 + c, 0xC0)
        self.globals()
        self.finish()

    # -- notes --
    def tl(self, t, k):
        if t.carriers >> k & 1:
            return min(127, (t.ops[k][1] & 0x7F) + 127 - (t.vol8 >> 3))
        return t.ops[k][1]

    def key_off(self, c):
        t = self.ch[c]
        t.keyon = False
        if t.keyed:
            self.offs.append((0x08, c))
            t.keyed = False

    def key_on(self, c, note, locks):
        t = self.ch[c]
        ins = self.img.instrument(t.instrument)
        if ins is None:
            return
        self.key_off(c)
        t.ops = [list(op) for op in ins["ops"]]
        t.connect, t.sens = ins["connect"], ins["sens"]
        t.carriers = CARRIERS[t.connect & 7]
        if c == NOISE_CHANNEL:
            self.noise = 0x80 | ins["nfrq"] if ins["noise"] else self.noise & 0x1F
        for code, param in locks:
            x, y = param >> 4, param & 15
            if code == FX["E"]:
                t.connect = (t.connect & 7) | param << 3
            elif code == FX["I"]:
                for k in range(4):
                    if not t.carriers >> k & 1:
                        t.ops[k][1] = 0x7F - param
            elif code == FX["M"]:
                t.ops[x - 1][0] = (t.ops[x - 1][0] & 0x70) | y
            elif code == FX["H"]:
                t.sens = param
            elif code == FX["N"] and c == NOISE_CHANNEL:
                self.noise = (self.noise & 0x80) | param
        for k in range(4):
            for j, bank in enumerate(OPERATOR_BANKS):
                self.write(operator_reg(bank, c, k), self.tl(t, k) if j == 1 else t.ops[k][j])
        self.write(0x20 + c, t.pan | t.connect)
        self.write(0x38 + c, t.sens)
        if c == NOISE_CHANNEL:
            self.globals()
        t.base = t.target = note * UNITS_PER_SEMITONE
        t.slide = 0
        t.arp_step = 0
        t.vib_phase = 0
        start, end, speed = ins["sweep"]
        if speed:
            t.sweep, t.sweep_end, t.sweep_step = start * 64, end * 64, speed * 4
        else:
            t.sweep = t.sweep_end = t.sweep_step = 0
        t.keyon = True
        t.active = True

    def run_row(self, c):
        t = self.ch[c]
        note, slot, effects = t.row if t.row else (None, 0, ())
        playing = note is not None and note != NOTE_OFF
        if playing:
            t.arp = 0
            t.fine = 0
        if slot:
            t.instrument = slot
        locks = []
        for code, param in effects:
            x, y = param >> 4, param & 15
            if code in ROW_START:
                continue
            if code == FX["0"]:
                t.arp, t.arp_step = param, 0
            elif code in (FX["1"], FX["2"], FX["3"]):
                t.slide_mode = {FX["1"]: "up", FX["2"]: "down", FX["3"]: "porta"}[code] if param else None
                t.slide_speed = param
            elif code == FX["4"]:
                t.vib_speed, t.vib_depth = x, y
            elif code == FX["8"]:
                t.pan = (0x40 if x else 0) | (0x80 if y else 0)
                if t.active:
                    self.write(0x20 + c, t.pan | t.connect)
            elif code == FX["A"]:
                t.volslide = param
            elif code == FX["C"]:
                t.vol8 = param * 8
            elif code == FX["H"]:
                t.sens = x << 4 | y
                if t.active:
                    self.write(0x38 + c, t.sens)
                locks.append((code, param))
            elif code == FX["N"]:
                self.noise = (self.noise & 0x80) | param
                self.globals()
                locks.append((code, param))
            elif code == FX["P"]:
                t.fine = param - 0x80
            elif code == FX["S"]:
                t.cut = param
            elif code in (FX["E"], FX["I"], FX["M"]):
                locks.append((code, param))
            elif code in SHARED:
                if code == FX["L"]:
                    self.lfo_rate = param
                elif code == FX["T"]:
                    self.amd = param
                elif code == FX["V"]:
                    self.pmd = param
                elif code == FX["W"]:
                    self.lfo_wave = param
                elif code == FX["X"]:
                    self.csm = param
                else:  # Q
                    self.timer_a = (param << 2) | (self.timer_a & 3)
                self.globals()
        if note == NOTE_OFF:
            self.key_off(c)
        elif playing:
            if t.slide_mode == "porta" and t.keyed:
                t.target = note * UNITS_PER_SEMITONE
            else:
                self.key_on(c, note, locks)

    def update(self, c):
        t = self.ch[c]
        arp = 0
        if t.arp and t.arp_step:
            arp = t.arp >> 4 if t.arp_step == 1 else t.arp & 15
        vib = (SINE[t.vib_phase] * t.vib_depth) >> 4  # Python's >> floors, as the spec's arithmetic shift
        pitch = t.base + 64 * arp + t.fine + t.slide + vib + t.sweep
        for k in range(4):
            if t.carriers >> k & 1:
                self.write(operator_reg(0x60, c, k), self.tl(t, k))
        kc, kf = key_code(pitch)
        self.write(0x28 + c, kc)
        self.write(0x30 + c, kf)
        if t.keyon:
            self.rest.append((0x08, KEY_ALL | c))
            t.keyed = True
            t.keyon = False
        # the effects advance after the writes
        if t.arp:
            t.arp_step = (t.arp_step + 1) % 3
        if t.slide_mode == "up":
            t.slide = min(SLIDE_LIMIT, t.slide + t.slide_speed)
        elif t.slide_mode == "down":
            t.slide = max(-SLIDE_LIMIT, t.slide - t.slide_speed)
        elif t.slide_mode == "porta":
            if t.base < t.target:
                t.base = min(t.target, t.base + t.slide_speed)
            elif t.base > t.target:
                t.base = max(t.target, t.base - t.slide_speed)
        if t.vib_speed and t.vib_depth:
            t.vib_phase = (t.vib_phase + t.vib_speed) % 64
        if t.sweep_step:
            if t.sweep < t.sweep_end:
                t.sweep = min(t.sweep_end, t.sweep + t.sweep_step)
            elif t.sweep > t.sweep_end:
                t.sweep = max(t.sweep_end, t.sweep - t.sweep_step)
        if t.volslide:
            t.vol8 = max(0, min(VOL8_MAX, t.vol8 + (t.volslide >> 4) - (t.volslide & 15)))

    # -- sequencing --
    def begin_row(self):
        img = self.img
        for c in range(CHANNELS):
            self.ch[c].row = img.pattern_rows(self.order, c)[self.row]
        jump_order = jump_row = None
        for c in range(CHANNELS):
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
        for c in range(CHANNELS):
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
        self.offs, self.rest = [], []
        self.globals()
        if self.tick_in_row == 0:
            self.begin_row()
        else:
            for c in range(CHANNELS):
                t = self.ch[c]
                if t.delay is not None and t.delay == self.tick_in_row:
                    t.delay = None
                    self.run_row(c)
        for c in range(CHANNELS):
            if self.ch[c].cut == self.tick_in_row:
                self.key_off(c)
        for c in range(CHANNELS):
            if self.ch[c].active:
                self.update(c)
        self.tick_in_row += 1
        if self.tick_in_row >= self.speed:
            img = self.img
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
                self.speed = img.start_speed[img.loop]  # every pass of the loop starts alike
        return self.finish()

    def position(self):
        return self.order, self.row, self.tick_in_row


def reset_writes(image):
    """The reset batch: $20-$27 = $C0, then the globals from the header (diffed against the all-zero chip)."""
    return list(Player(image).batch)


def trace(image, ticks):
    """[((order, row, tick) before the tick, [(reg, val)], key-off phase length)] for `ticks` ticks after the reset."""
    p = Player(image)
    out = []
    for _ in range(ticks):
        pos = p.position()
        writes = list(p.tick())
        out.append((pos, writes, p.split))
    return out


def format_tick(pos, writes, split):
    """One line of a trace file (the host test's opm_trace.txt): "order row tick:" then " RR=VV" per write, with
    " |" after the key-off phase when it is not empty."""
    text = "%d %d %d:" % pos
    text += "".join(" %02X=%02X" % w for w in writes[:split]) + (" |" if split else "")
    return text + "".join(" %02X=%02X" % w for w in writes[split:])


def dump_writes(p):
    """The register state as the player's shadow holds it (the VGM loop point), then $08 for every channel."""
    out = []
    for reg in DUMP_GLOBALS:
        out.append((0x19, 0x80 | p.shadow[SHADOW_PMD]) if reg == SHADOW_PMD else (reg, p.shadow[reg]))
    out += [(reg, p.shadow[reg]) for reg in range(0x20, 0x100)]
    out += [(0x08, (KEY_ALL if p.ch[c].keyed else 0) | c) for c in range(CHANNELS)]
    return out


def vgm(image, max_ticks):
    """VGM 1.71 bytes (no GD3 tag), as docs/AUDIO_ENGINE.md "OPM: VGM export" specifies. Returns (bytes, info)
    with info = {ticks, samples, looped, loop_offset (file offset, 0 = none), loop_samples (0 = none)}."""
    p = Player(image)
    body = bytearray()

    def emit(writes):
        for reg, val in writes:
            body.extend((0x54, reg, val))

    def wait(n):
        while n:
            if n == 735:
                body.append(0x62)
                n = 0
            elif n <= 16:
                body.append(0x70 | (n - 1))
                n = 0
            else:
                k = min(n, 65535)
                body.extend((0x61, k & 0xFF, k >> 8))
                n -= k

    emit(p.batch)
    period = 60 * image.multispeed
    acc = samples = ticks = 0
    loop_at = loop_samples = None
    looped = False
    while ticks < max_ticks:
        dump_off = False
        if p.position() == (image.loop, 0, 0):
            if loop_at is not None:
                looped = True
                break
            loop_at, loop_samples = 0x100 + len(body), samples
            dump = dump_writes(p)
            emit(dump)
            # a dump key-off belongs to the loop tick's key-off phase: on later passes the channel may still sound
            dump_off = any(reg == 0x08 and not val & KEY_ALL for reg, val in dump)
        writes = p.tick()
        acc += 44100
        n, acc = divmod(acc, period)
        samples += n
        # the key-off phase, one sample, then the rest (the chip samples the key state once per sample); the sample
        # comes out of the tick's wait
        emit(writes[:p.split])
        if p.split or dump_off:
            wait(1)
            n -= 1
        emit(writes[p.split:])
        wait(n)
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
    struct.pack_into("<I", header, 0x30, CLOCK)
    struct.pack_into("<I", header, 0x34, 0x100 - 0x34)
    info = {"ticks": ticks, "samples": samples, "looped": looped,
            "loop_offset": loop_at if looped else 0, "loop_samples": samples - loop_samples if looped else 0}
    return bytes(header + body), info


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("image")
    ap.add_argument("--trace")
    ap.add_argument("--ticks", type=int, default=1500)
    ap.add_argument("--vgm")
    ap.add_argument("--max-ticks", type=int, default=60 * 60 * 10)
    args = ap.parse_args(argv)
    with open(args.image, "rb") as f:
        image = Image(f.read())
    if args.trace:
        with open(args.trace, "w") as f:
            for pos, writes, split in trace(image, args.ticks):
                f.write(format_tick(pos, writes, split) + "\n")
    if args.vgm:
        data, info = vgm(image, args.max_ticks)
        with open(args.vgm, "wb") as f:
            f.write(data)
        print("%s: %d bytes, %d ticks, %d samples%s" % (args.vgm, len(data), info["ticks"], info["samples"],
              ", loop %d samples" % info["loop_samples"] if info["looped"] else ", no loop"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
