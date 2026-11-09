#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's Atari 2600 (TIA) player and of the TIA audio counters, written from the
specification in docs/AUDIO_ENGINE.md ("Atari 2600: Thor Tracker's own player on the TIA") independently of the C
player (app/src/main/cpp/tia/tia_player.c) and machine (tia_machine.c). The host test compares the C player's
register writes with this one's, tick by tick, and the chip's per-phase-1 channel levels and counter states with
Chip here (a separate Python port of the logic of Stella 7.0's AudioChannel, which the engine runs unmodified).

    python3 tools/audio/tia/tia_ref.py IMAGE.bin --trace out.txt [--ticks 1500]
    python3 tools/audio/tia/tia_ref.py IMAGE.bin --levels out.txt [--ticks 8]

The trace file has one line per player tick (the reset batch is not part of it): "order row tick:" (the position
before the tick), then " RR=VV" per write of the tick's batch (two hex digits each). The levels file has one line per
tick: the tick's phase-1 levels "v0v1" as two hex digits per event. Pure Python 3, no dependencies."""
import argparse
import math
import struct
import sys

# ---- clocks and timing ----------------------------------------------------------------------------------------------
REGION_NTSC, REGION_PAL = 0, 1
CLOCK_NUM = (39375000, 3546894)          # colour clock = NUM / DEN Hz
CLOCK_DEN = (11, 1)
LINES = (262, 312)                       # scanlines per frame (one player tick)
CLOCKS_PER_LINE = 228
AUDIO_DIVIDER = 114                      # the audio clock: two phase pairs per scanline = colour clock / 114
PHASE0 = (9, 81)                         # line clocks of the two phase-0 events (Stella 7.0 Audio::tick)
PHASE1 = (37, 149)                       # line clocks of the two phase-1 events
SEEK_TICKS = 7200
RELEASE_SECONDS = 10
MAX_TICK_FRAMES = 1927                   # 96 kHz PAL: 96000 * 71136 / 3546894 = 1925.4, + carry


def frame_clocks(region):
    return LINES[region] * CLOCKS_PER_LINE


def audio_clock(region):
    """The audio clock in Hz (31399.52 NTSC, 31113.11 PAL)."""
    return CLOCK_NUM[region] / CLOCK_DEN[region] / AUDIO_DIVIDER


# ---- image layout ---------------------------------------------------------------------------------------------------
IMAGE_SIZE = 65536
MAGIC = b"TTIA"
LAYOUT = 1
CHANNELS = 2
REGIONS = 2
MAX_ORDERS = 256
MAX_ROWS = 256
MAX_INSTRUMENTS = 64
MAX_EFFECTS = 2
MAX_ENVELOPE = 255
H_MAGIC, H_LAYOUT, H_REGION, H_SPEED, H_INSTRUMENTS, H_ORDERS, H_LOOP, H_DATA_SIZE, HEADER_SIZE = \
    0, 4, 5, 6, 7, 8, 10, 20, 64
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 1344, 3392
INSTRUMENT_SIZE = 32
DATA_CAPACITY = IMAGE_SIZE - OFF_DATA
I_FLAGS, I_WAVEFORM, I_ENVELOPES = 0, 1, 8
ENVELOPE_BYTES = 6
ENV_VOLUME, ENV_ARPEGGIO, ENV_PITCH, ENV_WAVEFORM, ENVELOPE_COUNT = 0, 1, 2, 3, 4
ENV_NONE = 0xFF
FLAG_DEFINED, FLAG_RAW, FLAG_RELATIVE = 0x80, 0x01, 0x04
NOTE_OFF = 0xFE
NOTE_MIN, NOTE_MAX = 0, 119       # C-0 .. B-9
VOLUME_MAX, WAVEFORM_MAX, AUDF_MAX = 15, 15, 31
REG_AUDC0, REG_AUDC1, REG_AUDF0, REG_AUDF1, REG_AUDV0, REG_AUDV1 = 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A

UNITS_PER_SEMITONE = 64
PITCH_MIN, PITCH_MAX = 0, 7679    # C-0 .. B-9 + 63/64
SLIDE_LIMIT = 7680                # slide offset and relative pitch accumulator: +-7680
VOL8_MAX = 120                    # channel volume 15 x 8

# AUDC -> waveform class (-1: constant output, AUDC 0 and B) and class -> pattern period in audio clocks
# (Korth's "AUDC Pattern Shapes"; tia_reference_check.py --periods measures them from Chip).
CLASS = (-1, 2, 5, 5, 0, 0, 3, 3, 6, 3, 3, -1, 1, 1, 4, 4)
PERIOD = (2, 6, 15, 31, 93, 465, 511)
CLASSES = len(PERIOD)
EDGES = 31


def _edges():
    """EDGE[region][class][k] = ceil(768 log2(A / (P C0 sqrt((k + 1)(k + 2))))): the lowest pitch nearer divider k + 1
    than k + 2 (in log frequency), IEEE double."""
    c0 = 440 * 2 ** (-57 / 12)
    out = []
    for r in range(REGIONS):
        a = CLOCK_NUM[r] / CLOCK_DEN[r] / AUDIO_DIVIDER
        out.append(tuple(tuple(math.ceil(768 * math.log2(a / (p * c0 * math.sqrt((k + 1) * (k + 2)))))
                               for k in range(EDGES)) for p in PERIOD))
    return tuple(out)


EDGE = _edges()

# Vibrato: floor(127 sin(2 pi i / 64) + 0.5), written out (the OPL3 player's table).
SINE = (0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126,
        127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12,
        0, -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
        -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12)

# The mono output level of channel volume sum s (0..30): floor(32767 * 60 s / (30 (30 + s))), exact.
MIX = tuple((32767 * 60 * s) // (30 * (30 + s)) for s in range(31))

LETTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
FX = {c: i for i, c in enumerate(LETTERS)}
EFFECTS = frozenset(FX[c] for c in "01234ABCDFGPSW")
ROW_START = frozenset(FX[c] for c in "BDFG")    # acted on at the row start, not when the row runs


def audf(region, cls, pitch):
    """The AUDF a tonal pitch plays on waveform class `cls`: the smallest k with pitch >= EDGE[k], else 31."""
    for k, edge in enumerate(EDGE[region][cls]):
        if pitch >= edge:
            return k
    return AUDF_MAX


def frequency(region, audc, f):
    """The fundamental in Hz of AUDC `audc` with AUDF `f` (None for the constant waveforms)."""
    cls = CLASS[audc]
    return None if cls < 0 else audio_clock(region) / (PERIOD[cls] * (f + 1))


class ImageError(ValueError):
    def __init__(self, reason, offset):
        super().__init__("%s (byte %d)" % (reason, offset))
        self.reason, self.offset = reason, offset


def _param_problem(code, param, orders):
    if code not in EFFECTS:
        return "unknown effect code"
    if code == FX["B"]:
        return "Bxx jumps past the last order" if param >= orders else None
    if code == FX["C"]:
        return "Cxx volume above 0F" if param > VOLUME_MAX else None
    if code == FX["F"]:
        return "F00: speed must be 1..FF" if param == 0 else None
    if code == FX["W"]:
        return "Wxx waveform above 0F" if param > WAVEFORM_MAX else None
    return None


class Envelope:
    __slots__ = ("values", "loop", "release")

    def __init__(self, values, loop, release):
        self.values, self.loop, self.release = values, loop, release   # loop/release None = none


class Instrument:
    __slots__ = ("raw", "relative", "waveform", "env")


class Image:
    """A validated TIA image: the checks of the spec's "Load-time checks", raising ImageError(reason, offset) on the
    first problem found (header, the order tables past the order count, instruments, patterns), and the decoded
    tables. `region` (0/1) is the loading engine's; None accepts either."""

    def __init__(self, data, region=None):
        data = bytes(data)
        if len(data) != IMAGE_SIZE:
            raise ImageError("image size", 0)
        self.data = data
        if data[0:4] != MAGIC:
            raise ImageError("not a TIA image (magic)", 0)
        if data[H_LAYOUT] != LAYOUT:
            raise ImageError("unknown layout", H_LAYOUT)
        self.region = data[H_REGION]
        if self.region >= REGIONS or (region is not None and self.region != region):
            raise ImageError("region", H_REGION)
        self.speed = data[H_SPEED]
        if self.speed < 1:
            raise ImageError("speed must be 1..255", H_SPEED)
        self.instrument_count = data[H_INSTRUMENTS]
        if self.instrument_count > MAX_INSTRUMENTS:
            raise ImageError("instrument count above 64", H_INSTRUMENTS)
        self.orders, self.loop = struct.unpack_from("<HH", data, H_ORDERS)
        if not 1 <= self.orders <= MAX_ORDERS:
            raise ImageError("order count must be 1..256", H_ORDERS)
        if self.loop >= self.orders:
            raise ImageError("loop order past the last order", H_LOOP)
        if any(data[12:H_DATA_SIZE]):
            raise ImageError("header reserved bytes 12..19", 12)
        self.data_size = struct.unpack_from("<I", data, H_DATA_SIZE)[0]
        if self.data_size > DATA_CAPACITY:
            raise ImageError("data size above the region", H_DATA_SIZE)
        if any(data[24:HEADER_SIZE]):
            raise ImageError("header reserved bytes 24..63", 24)
        self.rows, self.patterns = [], []
        for o in range(MAX_ORDERS):
            at = OFF_ORDERS + 2 * CHANNELS * o
            entry = list(struct.unpack_from("<%dH" % CHANNELS, data, at))
            if o < self.orders:
                self.rows.append(data[OFF_ROWS + o] or MAX_ROWS)
                self.patterns.append(entry)
                continue
            if data[OFF_ROWS + o]:
                raise ImageError("rows of an order past the order count", OFF_ROWS + o)
            if any(entry):
                raise ImageError("patterns of an order past the order count", at)
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
            raise ImageError("instrument record past the instrument count", base)
        flags = rec[I_FLAGS]
        if not flags & FLAG_DEFINED or flags & ~(FLAG_DEFINED | FLAG_RAW | FLAG_RELATIVE):
            raise ImageError("instrument flags", base + I_FLAGS)
        if rec[I_WAVEFORM] > WAVEFORM_MAX:
            raise ImageError("instrument waveform above 15", base + I_WAVEFORM)
        for j in range(2, I_ENVELOPES):
            if rec[j]:
                raise ImageError("instrument reserved bytes 2..7", base + j)
        ins = Instrument()
        ins.raw, ins.relative, ins.waveform = bool(flags & FLAG_RAW), bool(flags & FLAG_RELATIVE), rec[I_WAVEFORM]
        ins.env = []
        for k in range(ENVELOPE_COUNT):
            at = I_ENVELOPES + ENVELOPE_BYTES * k
            offset = struct.unpack_from("<H", rec, at)[0]
            length, loop, release, zero = rec[at + 2], rec[at + 3], rec[at + 4], rec[at + 5]
            if zero:
                raise ImageError("envelope reserved byte", base + at + 5)
            if length == 0:
                if offset or loop != ENV_NONE or release != ENV_NONE:
                    raise ImageError("empty envelope: offset 0, loop and release FF", base + at)
                ins.env.append(None)
                continue
            if loop != ENV_NONE and loop >= length:
                raise ImageError("envelope loop past its length", base + at + 3)
            if release != ENV_NONE and not 1 <= release < length:
                raise ImageError("envelope release must be 1..length - 1", base + at + 4)
            if loop != ENV_NONE and release != ENV_NONE and release <= loop:
                raise ImageError("envelope release must follow its loop", base + at + 4)
            if offset + length > self.data_size:
                raise ImageError("envelope runs past the data size", base + at)
            raw = self.data[OFF_DATA + offset: OFF_DATA + offset + length]
            values = [b - 256 if b > 127 else b for b in raw] if k in (ENV_ARPEGGIO, ENV_PITCH) else list(raw)
            for j, v in enumerate(values):
                if k == ENV_VOLUME and v > VOLUME_MAX:
                    raise ImageError("volume envelope value above 15", OFF_DATA + offset + j)
                if k == ENV_ARPEGGIO and not -64 <= v <= 63:
                    raise ImageError("arpeggio envelope value outside -64..63", OFF_DATA + offset + j)
                if k == ENV_WAVEFORM and v > WAVEFORM_MAX:
                    raise ImageError("waveform envelope value above 15", OFF_DATA + offset + j)
            ins.env.append(Envelope(values, None if loop == ENV_NONE else loop,
                                    None if release == ENV_NONE else release))
        self._instruments[slot] = ins

    def _check_patterns(self):
        """Per order, every channel's pattern offset inside the used data, then rows(o) rows decoded row by row across
        the channels, every byte inside the used data and valid. Returns the ticks per row each order starts with in
        sequential straight play: the header speed, then the last Fxx of each order (rows, then channels, then
        columns; jumps ignored)."""
        data, size = self.data, self.data_size
        speeds, speed = [], self.speed
        for o in range(self.orders):
            speeds.append(speed)
            offsets, runs = list(self.patterns[o]), [0] * CHANNELS
            for c in range(CHANNELS):
                if offsets[c] >= size:
                    raise ImageError("pattern offset past the data size", OFF_ORDERS + 2 * (CHANNELS * o + c))
            for _ in range(self.rows[o]):
                for c in range(CHANNELS):
                    if runs[c]:
                        runs[c] -= 1
                        continue
                    at = offsets[c]
                    if at >= size:
                        raise ImageError("pattern runs past the data size", OFF_DATA + at)
                    m = data[OFF_DATA + at]
                    at += 1
                    if m & 0x80:
                        runs[c], offsets[c] = m & 0x7F, at
                        continue
                    effects = (m >> 2) & 7
                    if m == 0 or m & 0x60 or effects > MAX_EFFECTS:
                        raise ImageError("row mask byte", OFF_DATA + at - 1)
                    if at + (m & 1) + ((m >> 1) & 1) + 2 * effects > size:
                        raise ImageError("row runs past the data size", OFF_DATA + at)
                    if m & 1:
                        note = data[OFF_DATA + at]
                        if note != NOTE_OFF and not NOTE_MIN <= note <= NOTE_MAX:
                            raise ImageError("note byte", OFF_DATA + at)
                        at += 1
                    if m & 2:
                        if self.instrument(data[OFF_DATA + at]) is None:
                            raise ImageError("row names an instrument the image does not define", OFF_DATA + at)
                        at += 1
                    for _ in range(effects):
                        code, param = data[OFF_DATA + at], data[OFF_DATA + at + 1]
                        why = "effect code above Z" if code > 35 else _param_problem(code, param, self.orders)
                        if why:
                            raise ImageError(why, OFF_DATA + at)
                        if code == FX["F"]:
                            speed = param  # rows, then channels, then columns: the last F wins
                        at += 2
                    offsets[c] = at
        return speeds

    def instrument(self, slot):
        """The decoded record of a defined slot 1..64, else None."""
        return self._instruments.get(slot)

    def pattern_rows(self, order, channel):
        """Every row of (order, channel) decoded from the pattern's start (the image is validated): None (empty) or
        (note, slot, effects) with note None / 0..119 / NOTE_OFF, slot 0 = none and effects [(code, param)] in column
        order. A run longer than the order's remaining rows is cut there."""
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
            for _ in range((m >> 2) & 7):
                effects.append((data[at], data[at + 1]))
                at += 2
            rows.append((note, slot, effects))
        self._decoded[key] = rows
        return rows


# ---- the player -----------------------------------------------------------------------------------------------------

class Channel:
    def __init__(self):
        self.row = None
        self.delay = None          # tick of a delayed row (Gxx), None = none pending
        self.cut = None            # Sxx tick, None = none
        self.instrument = 0        # the slot rows set
        self.latched = 0           # the slot of the sounding note (key-on)
        self.sounding = False
        self.released = False
        self.env_idx = [0] * ENVELOPE_COUNT
        self.rel_acc = 0
        self.base = self.target = 0
        self.fine = 0
        self.arp = self.arp_step = 0
        self.slide_mode = None     # "up", "down", "porta"
        self.slide_speed = 0
        self.slide = 0
        self.vib_speed = self.vib_depth = self.vib_phase = 0
        self.vol8 = VOL8_MAX
        self.volslide = 0
        self.waveform_override = -1


class Player:
    def __init__(self, image):
        self.img = image
        self.reset()

    # -- writes --
    def write(self, reg, val):
        """A register write through its shadow (equal values are not emitted)."""
        if self.shadow[reg] != val:
            self.shadow[reg] = val
            self.batch.append((reg, val))

    def reset(self):
        self.batch = []
        self.ch = [Channel() for _ in range(CHANNELS)]
        self.speed = self.img.speed
        self.order = self.row = self.tick_in_row = 0
        self.jump = None
        # The reset batch: every write emitted, setting the shadows.
        self.shadow = {}
        for c in range(CHANNELS):
            for reg in (REG_AUDC0 + c, REG_AUDF0 + c, REG_AUDV0 + c):
                self.shadow[reg] = 0
                self.batch.append((reg, 0))

    def place(self, order):
        """Reset, then start from (order, row 0) with the speed straight play starts that order with."""
        self.reset()
        self.order = order
        self.speed = self.img.start_speed[order]

    # -- notes --
    def key_on(self, c, note):
        t = self.ch[c]
        if self.img.instrument(t.instrument) is None:
            return
        t.latched = t.instrument
        t.env_idx = [0] * ENVELOPE_COUNT
        t.released = False
        t.rel_acc = 0
        t.base = t.target = note * UNITS_PER_SEMITONE
        t.slide = 0
        t.arp_step = 0
        t.vib_phase = 0
        t.sounding = True

    def stop(self, c):
        self.ch[c].sounding = False
        self.write(REG_AUDV0 + c, 0)

    def off(self, c):
        t = self.ch[c]
        if not t.sounding:
            return
        ins = self.img.instrument(t.latched)
        vol = ins.env[ENV_VOLUME] if ins else None
        if vol is not None and vol.release is not None:
            t.released = True
            for k, e in enumerate(ins.env):
                if e is not None and e.release is not None:
                    t.env_idx[k] = e.release
        else:
            self.stop(c)

    def run_row(self, c):
        t = self.ch[c]
        note, slot, effects = t.row if t.row else (None, 0, ())
        playing = note is not None and note != NOTE_OFF
        if playing:
            t.arp = 0
            t.fine = 0
            t.waveform_override = -1
        if slot:
            t.instrument = slot
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
            elif code == FX["A"]:
                t.volslide = param
            elif code == FX["C"]:
                t.vol8 = param * 8
            elif code == FX["P"]:
                t.fine = param - 0x80
            elif code == FX["S"]:
                t.cut = param
            elif code == FX["W"]:
                t.waveform_override = param
        if note == NOTE_OFF:
            self.off(c)
        elif playing:
            latched = self.img.instrument(t.latched)
            if t.slide_mode == "porta" and t.sounding and not (latched is not None and latched.raw):
                t.target = note * UNITS_PER_SEMITONE
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
        t = self.ch[c]
        ins = self.img.instrument(t.latched)
        if ins is None:  # a live edit removed the sounding note's slot
            self.stop(c)
            return
        v = self.env_value(t, ins, ENV_VOLUME)
        vol = max(0, (VOLUME_MAX if v is None else v) + (t.vol8 >> 3) - VOLUME_MAX)
        arp = 0
        if t.arp_step == 1:
            arp = t.arp >> 4
        elif t.arp_step == 2:
            arp = t.arp & 15
        a = self.env_value(t, ins, ENV_ARPEGGIO)
        semitones = arp + (a or 0)
        pv = self.env_value(t, ins, ENV_PITCH)
        if pv is None:
            pitch_value = 0
        elif ins.relative:
            t.rel_acc = max(-SLIDE_LIMIT, min(SLIDE_LIMIT, t.rel_acc + pv))
            pitch_value = t.rel_acc
        else:
            pitch_value = pv
        we = self.env_value(t, ins, ENV_WAVEFORM)
        if we is not None:
            w = we
        elif t.waveform_override >= 0:
            w = t.waveform_override
        else:
            w = ins.waveform
        if ins.raw:
            f = AUDF_MAX - (((t.base >> 6) + semitones) % 32)
        elif CLASS[w] < 0:
            f = None
        else:
            vib = (SINE[t.vib_phase] * t.vib_depth) >> 4  # Python's >> floors, as the spec's arithmetic shift
            pitch = max(PITCH_MIN, min(PITCH_MAX, t.base + 64 * semitones + t.fine + t.slide + vib + pitch_value))
            f = audf(self.img.region, CLASS[w], pitch)
        self.write(REG_AUDC0 + c, w)
        if f is not None:
            self.write(REG_AUDF0 + c, f)
        self.write(REG_AUDV0 + c, vol)
        # the envelopes and effects advance after the writes
        self.advance_envelopes(t, ins)
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
        if t.volslide:
            t.vol8 = max(0, min(VOL8_MAX, t.vol8 + (t.volslide >> 4) - (t.volslide & 15)))

    # -- sequencing --
    def derive_jump(self, img):
        jump_order = jump_row = None
        for c in range(CHANNELS):
            row = self.ch[c].row
            for code, param in (row[2] if row else ()):
                if code == FX["B"]:
                    jump_order = param
                elif code == FX["D"]:
                    jump_row = param
        if jump_order is None and jump_row is None:
            self.jump = None
        else:
            if jump_order is None:
                jump_order = self.order + 1 if self.order + 1 < img.orders else img.loop
            self.jump = (jump_order, jump_row or 0)

    @staticmethod
    def row_delay(row):
        g = 0
        for code, param in (row[2] if row else ()):
            if code == FX["G"]:
                g = param
        return g

    def begin_row(self):
        img = self.img
        for c in range(CHANNELS):
            self.ch[c].row = img.pattern_rows(self.order, c)[self.row]
        for c in range(CHANNELS):
            row = self.ch[c].row
            for code, param in (row[2] if row else ()):
                if code == FX["F"]:
                    self.speed = param
        self.derive_jump(img)
        for c in range(CHANNELS):
            t = self.ch[c]
            t.cut = None
            t.delay = None
            g = self.row_delay(t.row)
            if g:
                t.delay = g
            else:
                self.run_row(c)

    def advance(self, img):
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

    def tick(self):
        """One player tick; returns its batch [(reg, val)]."""
        self.batch = []
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
                self.off(c)
        for c in range(CHANNELS):
            if self.ch[c].sounding:
                self.update(c)
        self.tick_in_row += 1
        if self.tick_in_row >= self.speed:
            self.tick_in_row = 0
            self.advance(self.img)
        return self.batch

    # -- live edits --
    @staticmethod
    def speed_at(img, order, row, started):
        """Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it
        (and of the row itself once it has started)."""
        s = img.start_speed[order]
        last = row if started else row - 1
        for r in range(last + 1):
            for c in range(CHANNELS):
                cell = img.pattern_rows(order, c)[r]
                for code, param in (cell[2] if cell else ()):
                    if code == FX["F"]:
                        s = param
        return s

    def relocate(self, new):
        """Live edit at a tick boundary (the spec's "Position, seek and live edits", apply_image)."""
        old = self.img
        self.img = new
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
        if clamped:
            self.jump = None
            for t in self.ch:
                t.delay = None
            self.speed = self.speed_at(new, self.order, self.row, True)
            if not started:
                self.advance(new)  # the clamped row counts as played: the next row follows it
            return
        new_speed = self.speed_at(new, self.order, self.row, started)
        if new_speed != old_speed:
            self.speed = new_speed
        if not started:
            return
        # The row has started: its jump and any still-delayed rows come from the new data.
        for c in range(CHANNELS):
            t = self.ch[c]
            t.row = new.pattern_rows(self.order, c)[self.row]
            if t.delay is not None:
                g = self.row_delay(t.row)
                t.delay = g if g >= self.tick_in_row else None
        self.derive_jump(new)

    def position(self):
        return self.order, self.row, self.tick_in_row


# ---- the chip: the TIA's audio counters (logic of Stella 7.0's AudioChannel, ported separately) ----------------------

class ChipChannel:
    """One TIA audio channel: the AUDF divider, the 5-bit "noise" counter and the 4-bit "pulse" counter whose
    feedback and hold AUDC selects; output = pulse bit 0 x AUDV."""

    def __init__(self):
        self.audc = self.audf = self.audv = 0
        self.clock_enable = self.noise_feedback = self.noise_bit4 = self.pulse_hold = False
        self.div = self.pulse = self.noise = 0

    def write(self, kind, value):
        if kind == 0:
            self.audc = value & 0x0F
        elif kind == 1:
            self.audf = value & 0x1F
        else:
            self.audv = value & 0x0F

    def phase0(self):
        if self.clock_enable:
            self.noise_bit4 = bool(self.noise & 0x01)
            mode = self.audc & 0x03
            if mode in (0, 1):
                self.pulse_hold = False
            elif mode == 2:
                self.pulse_hold = (self.noise & 0x1E) != 0x02
            else:
                self.pulse_hold = not self.noise_bit4
            if mode == 0:
                self.noise_feedback = bool(((self.pulse ^ self.noise) & 0x01)
                                           or not (self.noise or self.pulse != 0x0A)
                                           or not (self.audc & 0x0C))
            else:
                self.noise_feedback = bool(((1 if self.noise & 0x04 else 0) ^ (self.noise & 0x01)) or self.noise == 0)
        self.clock_enable = self.div == self.audf
        if self.div == self.audf or self.div == 0x1F:
            self.div = 0
        else:
            self.div += 1

    def phase1(self):
        if not self.clock_enable:
            return
        sel = self.audc >> 2
        if sel == 0:
            fb = bool(((1 if self.pulse & 0x02 else 0) ^ (self.pulse & 0x01)) and self.pulse != 0x0A
                      and (self.audc & 0x03))
        elif sel == 1:
            fb = not (self.pulse & 0x08)
        elif sel == 2:
            fb = not self.noise_bit4
        else:
            fb = not ((self.pulse & 0x02) or not (self.pulse & 0x0E))
        self.noise >>= 1
        if self.noise_feedback:
            self.noise |= 0x10
        if not self.pulse_hold:
            self.pulse = ~(self.pulse >> 1) & 0x07
            if fb:
                self.pulse |= 0x08

    def volume(self):
        return (self.pulse & 0x01) * self.audv

    def state(self):
        """The ten bytes Stella's AudioChannel::save writes, in its order."""
        return bytes((self.audc, self.audf, self.audv, int(self.clock_enable), int(self.noise_feedback),
                      int(self.noise_bit4), int(self.pulse_hold), self.div, self.pulse, self.noise))


class Chip:
    """Both channels on the colour-clock timeline: write(batch) at a tick's clock 0, then run_frame(lines)."""

    def __init__(self):
        self.ch = [ChipChannel(), ChipChannel()]

    def write(self, batch):
        for reg, val in batch:
            i = reg - REG_AUDC0
            assert 0 <= i < 6, "not a TIA audio register: %02X" % reg
            self.ch[i % 2].write(i // 2, val)

    def run_frame(self, lines, levels=None):
        """`lines` scanlines: phase 0 at clocks 9 and 81, phase 1 at 37 and 149; after each phase 1 the levels
        (v0 | v1 << 4) are appended to `levels`."""
        a, b = self.ch
        for _ in range(lines):
            for _half in range(2):
                a.phase0()
                b.phase0()
                a.phase1()
                b.phase1()
                if levels is not None:
                    levels.append(a.volume() | b.volume() << 4)

    def state(self):
        return self.ch[0].state() + self.ch[1].state()


# ---- traces ---------------------------------------------------------------------------------------------------------

def reset_writes(image):
    """The reset batch: AUDC0, AUDF0, AUDV0, AUDC1, AUDF1, AUDV1 = 0."""
    return list(Player(image).batch)


def trace(image, ticks, edit=None, edit_at=None):
    """[(pos, writes)] for `ticks` ticks after the reset; with `edit` (an Image) applied as a live edit before tick
    `edit_at`."""
    p = Player(image)
    out = []
    for k in range(ticks):
        if edit is not None and k == edit_at:
            p.relocate(edit)
        pos = p.position()
        out.append((pos, list(p.tick())))
    return out


def levels(image, ticks):
    """(the phase-1 levels of the first `ticks` ticks, both channels' state bytes after them): the reset batch and
    every tick's batch at the tick's clock 0, then the frame's scanlines."""
    p = Player(image)
    chip = Chip()
    chip.write(p.batch)
    out = []
    for _ in range(ticks):
        chip.write(p.tick())
        chip.run_frame(LINES[image.region], out)
    return out, chip.state()


def measure_period(audc, f=0, warm=4096, span=8192):
    """The repetition period (in audio clocks: phase pairs) of AUDC `audc` at AUDF `f` from Chip alone, after `warm`
    clocks from the reset state at full volume; 1 for a constant output."""
    ch = ChipChannel()
    ch.write(0, audc)
    ch.write(1, f)
    ch.write(2, 15)
    seq = bytearray()
    for _ in range(warm + span):
        ch.phase0()
        ch.phase1()
        seq.append(ch.pulse & 1)
    seq = bytes(seq[warm:])
    for p in range(1, span // 2):
        if seq[p:] == seq[:-p]:  # the sequence equals itself shifted by p over the whole span
            return p
    return None


def format_tick(pos, writes):
    return "%d %d %d:" % pos + "".join(" %02X=%02X" % w for w in writes)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("image")
    ap.add_argument("--trace")
    ap.add_argument("--levels")
    ap.add_argument("--ticks", type=int)
    args = ap.parse_args(argv)
    with open(args.image, "rb") as f:
        image = Image(f.read())
    if args.trace:
        with open(args.trace, "w") as f:
            for pos, writes in trace(image, args.ticks or 1500):
                f.write(format_tick(pos, writes) + "\n")
    if args.levels:
        lv, _ = levels(image, args.ticks or 8)
        per = 2 * LINES[image.region]
        with open(args.levels, "w") as f:
            for k in range(0, len(lv), per):
                f.write("".join("%02X" % v for v in lv[k: k + per]) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
