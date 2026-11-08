#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's YM2612 + SN76489 player ("ym2612-fm"), written from the
specification in docs/AUDIO_ENGINE.md ("YM2612: player", "Effects", "Machine reset writes", "VGM export")
independently of the C player (app/src/main/cpp/ym/). The host test compares the C player's register writes with
this one's, tick by tick.

    python3 tools/audio/ym/ym_ref.py IMAGE.bin --trace out.txt --ticks 1500
    python3 tools/audio/ym/ym_ref.py IMAGE.bin --vgm out.vgm [--max-ticks N]

Pure Python 3, no dependencies. Also defines the pitch, PSG period and vibrato tables (generated into
app/src/main/cpp/generated/ym_tables.h).

Writes are records (kind, reg, val): kind 0 = YM2612 port 0, 1 = YM2612 port 1, 2 = PSG byte (reg 0).

Spec readings chosen where the text leaves room (each reported to the spec owner):
  - Image layout table lists "13 | 3 | 0" overlapping the data size at 14; read as byte 13 = 0 (as the note
    under the table says) and the data size at 14..15.
  - FM key-off clears a key-on still pending from the same tick (key-on and a cut at the same tick, e.g. S00):
    the $28 key-off is written (the channel was keyed) and no key-on follows, so the player's key state and the
    chip's agree. The test song avoids that case.
  - `8xy` writes $B4 at once whenever the channel has keyed a note since reset (its note's AMS/FMS), also during
    the release; before the first note it only sets the pan.
  - Audition/release batches (not specified for this system): audition() = reset batch + the key-on's register
    writes (the key-on $28 itself is written by the first tick's update); key_off() = the key-off at once."""
import argparse
import math
import struct
import sys

MCLK = {0: 53693175, 1: 53203424}
YM_CLOCK = {0: 7670453, 1: 7600489}          # MCLK / 7, the VGM header's YM2612 clock
PSG_CLOCK_VGM = {0: 3579545, 1: 3546895}     # MCLK / 15 rounded, the VGM header's SN76489 clock
VGM_RATE = {0: 60, 1: 50}
VGM_TICK_SAMPLES = {0: 735, 1: 882}
UNITS_PER_SEMITONE = 64
UNITS_PER_OCTAVE = 768
PITCH_MAX = 8 * UNITS_PER_OCTAVE - 1
C0_HZ = 440.0 * 2 ** (-57 / 12)

YM0, YM1, PSG = 0, 1, 2
NOTE_OFF = 0xFE
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}

# Image layout
IMAGE_SIZE = 65536
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 5440, 8000
INSTRUMENT_SIZE = 40
DATA_MAX = IMAGE_SIZE - OFF_DATA  # 57536
CHANNELS = 10

# Operator register offsets within a channel's, operators 1..4 (as in the algorithm diagrams).
OP_OFFSET = [0, 8, 4, 12]
OP_REGS = [0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90]
# Carriers by algorithm, as operator indices 0..3 (operator k + 1).
CARRIERS = {0: {3}, 1: {3}, 2: {3}, 3: {3}, 4: {1, 3}, 5: {1, 2, 3}, 6: {1, 2, 3}, 7: {0, 1, 2, 3}}

# Effects allowed per channel kind: "fm" 0-5, "psg" 6-8, "noise" 9.
_ALL = set("01234ABCDFGLPS")
ALLOWED = {"fm": {FX[c] for c in _ALL | set("8EIM")}, "psg": {FX[c] for c in _ALL},
           "noise": {FX[c] for c in _ALL | {"N"}}}


def channel_kind(c):
    return "fm" if c < 6 else ("psg" if c < 9 else "noise")


# ---- tables ------------------------------------------------------------------------------------------

def fnum_table(region):
    """F-number per 1/64 semitone of an octave above C, and the split: below it the F-number is for block =
    octave - 1, from it for block = octave (the value halved)."""
    fs = MCLK[region] / 1008
    # The doc writes F_C = 440 * 2^(-57/12) * 2^20 / fs but states F_C = 643.76 (NTSC) / 649.69 (PAL), split
    # 514 / 504 and A-4 = 1083 / 1093: those need 2^21 (YM2612: f = F-number * 2^block * fs / 2^21). The
    # numbers are taken as authoritative (asserted below).
    f_c = 440.0 * 2 ** (-57 / 12) * 2 ** 21 / fs
    table, split = [], None
    for f in range(UNITS_PER_OCTAVE):
        x = 2 * f_c * 2 ** (f / UNITS_PER_OCTAVE)
        low = math.floor(x + 0.5)
        if split is None and low > 2047:
            split = f
        table.append(low if split is None else math.floor(x / 2 + 0.5))
    return table, split


def psg_div_table(region):
    """256 x the PSG period of C at octave 0, per 1/64 semitone of an octave."""
    clock = MCLK[region] / 15
    return [math.floor(256 * clock / (32 * C0_HZ * 2 ** (f / UNITS_PER_OCTAVE)) + 0.5)
            for f in range(UNITS_PER_OCTAVE)]


def sine_table():
    return [math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64)]


FNUM, FNUM_SPLIT = {}, {}
for _r in (0, 1):
    FNUM[_r], FNUM_SPLIT[_r] = fnum_table(_r)
PSG_DIV = {r: psg_div_table(r) for r in (0, 1)}
SINE = sine_table()


def fm_regs(pitch, region):
    """(A4, A0) of a pitch in 1/64 semitones: block << 3 | F-number >> 8, F-number & 255."""
    octave, f = divmod(pitch, UNITS_PER_OCTAVE)
    fnum = FNUM[region][f]
    block = octave - 1 if f < FNUM_SPLIT[region] else octave
    if block < 0:
        block, fnum = 0, fnum >> 1
    return block << 3 | fnum >> 8, fnum & 0xFF


def psg_period(pitch, region):
    """SN76489 10-bit tone period N of a pitch in 1/64 semitones (clamped 1..1023)."""
    octave, f = divmod(pitch, UNITS_PER_OCTAVE)
    n = (PSG_DIV[region][f] + (128 << octave)) >> (8 + octave)
    return max(1, min(1023, n))


assert FNUM_SPLIT == {0: 514, 1: 504}, FNUM_SPLIT
assert fm_regs(57 * 64, 0) == (4 << 3 | 1083 >> 8, 1083 & 0xFF)
assert fm_regs(57 * 64, 1) == (4 << 3 | 1093 >> 8, 1093 & 0xFF)
assert psg_period(57 * 64, 0) == 254 and psg_period(57 * 64, 1) == 252
assert all(v <= 2047 for r in (0, 1) for v in FNUM[r])


# ---- machine reset (SGDK's YM2612_reset() + PSG_reset()) -------------------------------------------------

def machine_reset_writes():
    w = [(YM0, 0x22, 0), (YM0, 0x27, 0), (YM0, 0x2B, 0)]
    values = [0x00, 0x7F, 0x00, 0x00, 0x00, 0xFF, 0x00]  # $30..$90
    for port in (YM0, YM1):
        for ch in range(3):
            for slot in range(4):
                for reg, val in zip(OP_REGS, values):
                    w.append((port, reg + 4 * slot + ch, val))
    for port in (YM0, YM1):
        for ch in range(3):
            for reg, val in ((0xA0, 0), (0xA4, 0), (0xA8, 0), (0xAC, 0), (0xB0, 0), (0xB4, 0xC0)):
                w.append((port, reg + ch, val))
    for ch in range(3):
        w.append((YM0, 0x28, ch))
        w.append((YM0, 0x28, 4 | ch))
    for i in range(4):
        w += [(PSG, 0, 0x80 | i << 5), (PSG, 0, 0x00), (PSG, 0, 0x90 | i << 5 | 0x0F)]
    return w


MACHINE_RESET = machine_reset_writes()


# ---- image -----------------------------------------------------------------------------------------------

class ImageError(ValueError):
    pass


class Image:
    def __init__(self, data):
        data = bytes(data)

        def need(ok, what, at):
            if not ok:
                raise ImageError("%s (byte %d)" % (what, at))

        need(len(data) == IMAGE_SIZE, "image size", 0)
        need(data[:4] == b"TTYM", "magic", 0)
        need(data[4] == 1, "layout", 4)
        need(data[5] <= 1, "region", 5)
        need(data[6] >= 1, "speed", 6)
        need(data[7] == 0 or 0x08 <= data[7] <= 0x0F, "LFO", 7)
        self.data = data
        self.region = data[5]
        self.speed = data[6]
        self.lfo = data[7]
        self.orders, self.loop = struct.unpack_from("<HH", data, 8)
        need(1 <= self.orders <= 256, "order count", 8)
        need(self.loop < self.orders, "loop order", 10)
        self.instrument_count = data[12]
        need(self.instrument_count <= 64, "instrument count", 12)
        self.data_size = struct.unpack_from("<H", data, 14)[0]
        need(self.data_size <= DATA_MAX, "data size", 14)
        self.rows = [(data[OFF_ROWS + o] or 256) for o in range(self.orders)]
        self.patterns = [[struct.unpack_from("<H", data, OFF_ORDERS + 2 * (CHANNELS * o + c))[0]
                          for c in range(CHANNELS)] for o in range(self.orders)]
        self.instruments = {}
        for slot in range(1, 65):
            at = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
            rec = data[at: at + INSTRUMENT_SIZE]
            if not any(rec):
                continue
            need(slot <= self.instrument_count, "instrument above the count", at)
            need(rec[0] in (0x80, 0x81), "instrument flags", at)
            sweep = (struct.unpack("b", rec[32:33])[0], struct.unpack("b", rec[33:34])[0], rec[34])
            need(-96 <= sweep[0] <= 96 and -96 <= sweep[1] <= 96, "drum sweep", at + 32)
            if rec[0] == 0x80:
                need(rec[1] <= 7 and rec[2] <= 7 and not rec[3] & 0xC8, "FM instrument", at)
                self.instruments[slot] = {
                    "psg": False, "alg": rec[1], "fb": rec[2], "amsfms": rec[3],
                    "ops": [list(rec[4 + 7 * k: 11 + 7 * k]) for k in range(4)], "sweep": sweep}
            else:
                need(rec[1] <= 7 and rec[2] <= 1 and rec[3] == 0 and not any(rec[10:32]), "PSG instrument", at)
                envs = []
                for k in range(3):
                    off = struct.unpack_from("<H", rec, 4 + 2 * k)[0]
                    envs.append(None if off == 0xFFFF else self._envelope(off, k, at))
                self.instruments[slot] = {
                    "psg": True, "noise": rec[1], "relative": bool(rec[2] & 1), "envs": envs, "sweep": sweep}
        self._decoded = {}
        for o in range(self.orders):  # light validation: decode everything once
            for c in range(CHANNELS):
                self._check(o, c, self.pattern_rows(o, c))
        self.start_speed = self.start_speeds()

    def _envelope(self, off, kind, at):
        """(values, loop, release) of the envelope at data offset `off`; kind 0 volume, 1 arpeggio, 2 pitch."""
        if off + 6 > self.data_size:
            raise ImageError("envelope outside the data (byte %d)" % at)
        length, loop, release = struct.unpack_from("<HHH", self.data, OFF_DATA + off)
        if not 1 <= length <= 256 or off + 6 + length > self.data_size:
            raise ImageError("envelope length (data %d)" % off)
        loop = None if loop == 0xFFFF else loop
        release = None if release == 0xFFFF else release
        if loop is not None and loop >= length:
            raise ImageError("envelope loop (data %d)" % off)
        if release is not None and (kind != 0 or loop is None or not loop < release < length):
            raise ImageError("envelope release (data %d)" % off)
        values = list(struct.unpack_from("%db" % length, self.data, OFF_DATA + off + 6))
        if not all((0 <= v <= 15) if kind == 0 else (-64 <= v <= 63) for v in values):
            raise ImageError("envelope value (data %d)" % off)
        return values, loop, release

    def instrument(self, slot):
        return self.instruments.get(slot)

    def pattern_rows(self, order, channel):
        """The rows(order) rows of the pattern of (order, channel): None (empty) or (note, inst, [(code, param)])."""
        offset, n = self.patterns[order][channel], self.rows[order]
        key = (offset, n)
        if key in self._decoded:
            return self._decoded[key]
        at, rows, end = offset, [], self.data_size

        def byte():
            nonlocal at
            if at >= end:
                raise ImageError("pattern runs past the data (order %d channel %d)" % (order, channel))
            v = self.data[OFF_DATA + at]
            at += 1
            return v

        while len(rows) < n:
            m = byte()
            if m & 0x80:
                rows.extend([None] * ((m & 0x7F) + 1))
                continue
            if m == 0 or m & 0x60 or (m >> 2) & 7 > 4:
                raise ImageError("row byte %02X (data %d)" % (m, at - 1))
            note = byte() if m & 1 else None
            inst = byte() if m & 2 else None
            effects = [(byte(), byte()) for _ in range((m >> 2) & 7)]
            rows.append((note, inst, effects))
        rows = rows[:n]
        self._decoded[key] = rows
        return rows

    def _check(self, o, c, rows):
        kind = channel_kind(c)
        for r, row in enumerate(rows):
            if row is None:
                continue
            note, inst, effects = row
            where = "order %d row %d channel %d" % (o, r, c)
            if note is not None and not (note <= 95 or note == NOTE_OFF):
                raise ImageError("note %d at %s" % (note, where))
            if inst is not None:
                i = self.instruments.get(inst)
                if i is None or i["psg"] != (kind != "fm"):
                    raise ImageError("instrument %d at %s" % (inst, where))
            for code, p in effects:
                if code not in ALLOWED[kind]:
                    raise ImageError("effect %d on channel at %s" % (code, where))
                hi, lo = p >> 4, p & 15
                bad = ((code == FX["B"] and p >= self.orders)
                       or (code == FX["C"] and p > (0x7F if kind == "fm" else 0x0F))
                       or (code == FX["E"] and p > 7) or (code == FX["F"] and p == 0)
                       or (code == FX["I"] and p > 0x7F)
                       or (code == FX["L"] and not (p == 0 or 0x08 <= p <= 0x0F))
                       or (code == FX["M"] and not 1 <= hi <= 4)
                       or (code == FX["N"] and (hi > 1 or lo > 3)))
                if bad:
                    raise ImageError("effect %d parameter %02X at %s" % (code, p, where))

    def start_speeds(self):
        """The speed each order starts with in sequential straight play (header speed, then the last Fxx of
        each order: rows, then channels, then columns; jumps ignored)."""
        speeds, s = [], self.speed
        for o in range(self.orders):
            speeds.append(s)
            for r in range(self.rows[o]):
                for c in range(CHANNELS):
                    row = self.pattern_rows(o, c)[r]
                    for code, param in (row[2] if row else []):
                        if code == FX["F"]:
                            s = param
        return speeds


# ---- player ----------------------------------------------------------------------------------------------

ON, RELEASED, OFF = "on", "released", "off"


class Track:
    """State of one tracker channel."""

    def __init__(self, ch):
        self.ch = ch
        self.kind = channel_kind(ch)
        self.row = None
        self.delay = None          # tick of a delayed row, None = no row pending
        self.cut = None
        self.slot = 0
        self.keyed = False
        self.pending_on = False    # FM: key-on written by the next update
        self.started = False       # some note has been keyed since reset
        self.note = None           # FM: dict(ops, alg, fb, amsfms, carriers) of the keyed note
        self.base = self.target = 0
        self.fine = 0
        self.arp = 0
        self.arp_phase = 0
        self.slide_kind = None     # "up", "down", "porta"
        self.slide_speed = 0
        self.slide = 0
        self.vib = (0, 0)
        self.vib_phase = 0
        self.sweep = self.sweep_end = self.sweep_step = 0
        self.vol8 = 0x7F * 8 if self.kind == "fm" else 0x0F * 8
        self.volume_slide = 0
        self.pan = 0xC0
        # PSG / Noise envelopes: the keyed note's (values, loop, release) or None, per volume, arpeggio, pitch
        self.envs = [None, None, None]
        self.pos = [0, 0, 0]
        self.relative = False
        self.acc = 0               # relative pitch envelope accumulator
        self.phase = OFF
        self.mode = 0              # Noise: the note's noise mode


class Player:
    def __init__(self, image):
        self.img = image
        self.region = image.region
        self.reset()

    # -- writes --
    def reset(self):
        self.ym_regs = [[0] * 256, [0] * 256]
        for kind, reg, val in MACHINE_RESET:
            if kind != PSG and reg != 0x28:
                self.ym_regs[kind][reg] = val
        self.tone = [0, 0, 0]
        self.att = [15, 15, 15, 15]
        self.noise = 0
        self.out, self.key_offs = [], []
        self.tracks = [Track(c) for c in range(CHANNELS)]
        self.lfo = self.img.lfo
        self.speed = self.img.speed
        self.order = self.row = self.tick_in_row = 0
        self.next_pos = None
        self.auditioning = False
        self.ym(YM0, 0x22, self.lfo)
        self._close_batch()

    def _close_batch(self):
        self.batch = self.key_offs + self.out
        self.split = len(self.key_offs)
        return self.batch

    def ym(self, port, reg, val):
        if self.ym_regs[port][reg] != val:
            self.ym_regs[port][reg] = val
            self.out.append((port, reg, val))

    def psg_tone(self, i, n):
        old = self.tone[i]
        if old == n:
            return
        self.out.append((PSG, 0, 0x80 | i << 5 | n & 15))
        if old >> 4 != n >> 4:
            self.out.append((PSG, 0, n >> 4))
        self.tone[i] = n

    def psg_att(self, i, a):
        if self.att[i] != a:
            self.att[i] = a
            self.out.append((PSG, 0, 0x90 | i << 5 | a))

    def psg_noise(self, mode):
        if self.noise != mode:
            self.noise = mode
            self.out.append((PSG, 0, 0xE0 | mode))

    # -- FM --
    @staticmethod
    def port_off(ch):
        return ch // 3, ch % 3

    @staticmethod
    def key_select(ch):
        return 4 * (ch // 3) + ch % 3

    def carrier_tl(self, t, k):
        return min(127, t.note["ops"][k][1] + 127 - (t.vol8 >> 3))

    def fm_key_off(self, t):
        if t.keyed:
            self.key_offs.append((YM0, 0x28, self.key_select(t.ch)))
            t.keyed = False
        t.pending_on = False  # reading: a cut in the key-on's own tick leaves the chip keyed off

    def fm_key_on(self, t, note, locks):
        inst = self.img.instrument(t.slot)
        if inst is None or inst["psg"]:
            return
        self.fm_key_off(t)
        ops = [list(o) for o in inst["ops"]]
        alg, fb, amsfms = inst["alg"], inst["fb"], inst["amsfms"]
        carriers = CARRIERS[alg]
        for code, param in locks:
            hi, lo = param >> 4, param & 15
            if code == FX["E"]:
                fb = param
            elif code == FX["I"]:
                for k in range(4):
                    if k not in carriers:
                        ops[k][1] = 0x7F - param
            elif code == FX["M"]:
                ops[hi - 1][0] = (ops[hi - 1][0] & 0x70) | lo
        t.note = {"ops": ops, "alg": alg, "fb": fb, "amsfms": amsfms, "carriers": carriers}
        port, off = self.port_off(t.ch)
        for k in range(4):
            for j, base in enumerate(OP_REGS):
                val = self.carrier_tl(t, k) if (j == 1 and k in carriers) else ops[k][j]
                self.ym(port, base + OP_OFFSET[k] + off, val)
        self.ym(port, 0xB0 + off, fb << 3 | alg)
        self.ym(port, 0xB4 + off, t.pan | amsfms)
        self.pitch_start(t, note, inst)
        t.keyed = t.started = True
        t.pending_on = True

    # -- PSG --
    def psg_key_off(self, t):
        """Key-off: a volume envelope with a release point plays its tail from there, anything else is silent."""
        if t.keyed:
            t.keyed = False
            vol = t.envs[0]
            if vol is not None and vol[2] is not None:
                t.pos[0] = vol[2]
                t.phase = RELEASED
            else:
                t.phase = OFF

    def psg_key_on(self, t, note, noise_lock):
        inst = self.img.instrument(t.slot)
        if inst is None or not inst["psg"]:
            return
        t.envs = list(inst["envs"])  # copied: an instrument edit applies to the next note
        t.pos = [0, 0, 0]
        t.relative = inst["relative"]
        t.acc = 0
        t.phase = ON
        if t.kind == "noise":
            t.mode = inst["noise"] if noise_lock is None else noise_lock
        self.pitch_start(t, note, inst)
        t.keyed = t.started = True

    # -- notes --
    @staticmethod
    def pitch_start(t, note, inst):
        t.base = t.target = note * UNITS_PER_SEMITONE
        t.slide = 0
        t.arp_phase = 0
        t.vib_phase = 0
        start, end, speed = inst["sweep"]
        if speed:
            t.sweep, t.sweep_end, t.sweep_step = start * 64, end * 64, speed * 4
        else:
            t.sweep = t.sweep_end = t.sweep_step = 0

    def note_off(self, ch):
        t = self.tracks[ch]
        if t.kind == "fm":
            self.fm_key_off(t)
        else:
            self.psg_key_off(t)

    def run_row(self, ch):
        t = self.tracks[ch]
        note, inst, effects = t.row if t.row else (None, None, [])
        playing_note = note is not None and note < 96
        if playing_note:
            t.arp = 0
            t.fine = 0
        if inst:
            t.slot = inst
        locks, noise_lock = [], None
        for code, param in effects:
            hi, lo = param >> 4, param & 15
            if code == FX["0"]:
                t.arp, t.arp_phase = param, 0
            elif code in (FX["1"], FX["2"], FX["3"]):
                t.slide_kind = {FX["1"]: "up", FX["2"]: "down", FX["3"]: "porta"}[code] if param else None
                t.slide_speed = param
            elif code == FX["4"]:
                t.vib = (hi, lo)
            elif code == FX["8"] and t.kind == "fm":
                t.pan = (0x80 if hi else 0) | (0x40 if lo else 0)
                if t.started:
                    port, off = self.port_off(ch)
                    self.ym(port, 0xB4 + off, t.pan | t.note["amsfms"])
            elif code == FX["A"]:
                t.volume_slide = param
            elif code == FX["C"]:
                t.vol8 = param * 8
            elif code == FX["L"]:
                self.lfo = param
                self.ym(YM0, 0x22, param)
            elif code == FX["P"]:
                t.fine = param - 0x80
            elif code == FX["S"]:
                t.cut = param
            elif code in (FX["E"], FX["I"], FX["M"]) and t.kind == "fm":
                locks.append((code, param))
            elif code == FX["N"] and t.kind == "noise":
                noise_lock = hi << 2 | lo
        if note == NOTE_OFF:
            self.note_off(ch)
        elif playing_note:
            if t.slide_kind == "porta" and t.keyed:
                t.target = note * UNITS_PER_SEMITONE
            elif t.kind == "fm":
                self.fm_key_on(t, note, locks)
            else:
                self.psg_key_on(t, note, noise_lock)

    # -- update --
    @staticmethod
    def pitch(t, extra=0):
        arp = 0
        if t.arp and t.arp_phase:
            arp = (t.arp >> 4) if t.arp_phase == 1 else (t.arp & 15)
        vib = (SINE[t.vib_phase] * t.vib[1]) >> 4 if t.vib[1] else 0  # floor shift
        return max(0, min(PITCH_MAX, t.base + arp * 64 + t.fine + t.slide + vib + t.sweep + extra))

    @staticmethod
    def advance(t, vol_max):
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
            t.vol8 = max(0, min(vol_max, t.vol8 + (t.volume_slide >> 4) - (t.volume_slide & 15)))

    @staticmethod
    def envelopes(t):
        """Steps the three envelopes (FamiStudio's rules): before key-off the volume envelope loops back from its
        release point; after it the tail plays and holds its last value; otherwise an envelope loops at its end or
        holds its last value. A released note holding volume 0 is off."""
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

    @staticmethod
    def psg_attenuation(t, level):
        return 15 - (level * (t.vol8 >> 3) + 7) // 15

    def noise_owns_tone3(self):
        n = self.tracks[9]
        return n.started and n.mode & 3 == 3 and n.phase != OFF

    def update(self, ch):
        t = self.tracks[ch]
        if not t.started:
            return
        if t.kind == "fm":
            p = self.pitch(t)
            port, off = self.port_off(ch)
            for k in range(4):
                if k in t.note["carriers"]:
                    self.ym(port, 0x40 + OP_OFFSET[k] + off, self.carrier_tl(t, k))
            a4, a0 = fm_regs(p, self.region)
            if self.ym_regs[port][0xA4 + off] != a4 or self.ym_regs[port][0xA0 + off] != a0:
                self.ym_regs[port][0xA4 + off] = a4
                self.ym_regs[port][0xA0 + off] = a0
                self.out += [(port, 0xA4 + off, a4), (port, 0xA0 + off, a0)]
            if t.pending_on:
                self.out.append((YM0, 0x28, 0xF0 | self.key_select(ch)))
                t.pending_on = False
            self.advance(t, 1016)
            return
        vol, arp, bend = (env[0][t.pos[k]] if env else None for k, env in enumerate(t.envs))
        level = 0 if t.phase == OFF else (15 if vol is None else vol)
        offset = 64 * (arp or 0)
        if t.relative:
            t.acc = max(-6144, min(6144, t.acc + 4 * (bend or 0)))
            offset += t.acc
        else:
            offset += 4 * (bend or 0)
        p = self.pitch(t, offset)
        if t.kind == "psg":
            i = ch - 6
            if ch == 8 and self.noise_owns_tone3():
                self.psg_att(i, 15)
            else:
                self.psg_tone(i, psg_period(p, self.region))
                self.psg_att(i, self.psg_attenuation(t, level))
        else:
            self.psg_noise(t.mode)
            if t.mode & 3 == 3:
                self.psg_tone(2, psg_period(p + 48 * 64, self.region))
            self.psg_att(3, self.psg_attenuation(t, level))
        self.advance(t, 120)
        self.envelopes(t)

    # -- sequencing --
    def begin_row(self):
        img = self.img
        for ch in range(CHANNELS):
            self.tracks[ch].row = img.pattern_rows(self.order, ch)[self.row]
        jump_order = jump_row = None
        for ch in range(CHANNELS):
            row = self.tracks[ch].row
            for code, param in (row[2] if row else []):
                if code == FX["F"]:
                    self.speed = param
                elif code == FX["B"]:
                    jump_order = param
                elif code == FX["D"]:
                    jump_row = param
        if jump_order is None and jump_row is None:
            self.next_pos = None
        else:
            if jump_order is None:
                jump_order = self.order + 1 if self.order + 1 < img.orders else img.loop
            self.next_pos = (jump_order, jump_row or 0)
        for ch in range(CHANNELS):
            t = self.tracks[ch]
            t.cut = None
            t.delay = None
            g = 0
            for code, param in (t.row[2] if t.row else []):
                if code == FX["G"]:
                    g = param
            if g:
                t.delay = g
            else:
                self.run_row(ch)

    def tick(self):
        self.out, self.key_offs = [], []
        self.ym(YM0, 0x22, self.lfo)
        if not self.auditioning:
            if self.tick_in_row == 0:
                self.begin_row()
            else:
                for ch in range(CHANNELS):
                    t = self.tracks[ch]
                    if t.delay is not None and t.delay == self.tick_in_row:
                        t.delay = None
                        self.run_row(ch)
            for ch in range(CHANNELS):
                if self.tracks[ch].cut == self.tick_in_row:
                    self.note_off(ch)
        for ch in range(CHANNELS):
            self.update(ch)
        if not self.auditioning:
            self.tick_in_row += 1
            if self.tick_in_row >= self.speed:
                self.tick_in_row = 0
                if self.next_pos is not None:
                    o, r = self.next_pos
                    self.order, self.row = o, min(r, self.img.rows[o] - 1)
                    self.next_pos = None
                else:
                    self.row += 1
                    if self.row >= self.img.rows[self.order]:
                        self.row = 0
                        self.order = self.order + 1 if self.order + 1 < self.img.orders else self.img.loop
                if (self.order, self.row) == (self.img.loop, 0):
                    self.speed = self.img.start_speed[self.order]  # every pass of the loop starts alike
        return self._close_batch()

    def position(self):
        return self.order, self.row, self.tick_in_row

    # -- audition --
    def audition(self, channel, note, slot, fine):
        """Reset, then key `note` on `channel` with `slot` (no locks), `fine` added like Pxx. Returns the batch
        (the reset writes and the instrument's registers; an FM key-on is written by the first tick)."""
        inst = self.img.instrument(slot)
        if not (0 <= channel < CHANNELS and 0 <= note <= 95 and -128 <= fine <= 127 and inst is not None
                and inst["psg"] == (channel >= 6)):
            raise ImageError("audition arguments")
        self.reset()
        self.auditioning = True
        t = self.tracks[channel]
        t.slot = slot
        if t.kind == "fm":
            self.fm_key_on(t, note, [])
        else:
            self.psg_key_on(t, note, None)
        t.fine = fine
        # self.out still holds the reset writes (no key-off phase): the batch = reset + key-on registers
        return self._close_batch()

    def key_off(self, channel):
        """Audition release: the channel's key-off now, as a batch."""
        self.out, self.key_offs = [], []
        self.note_off(channel)
        return self._close_batch()


# ---- trace and VGM ---------------------------------------------------------------------------------------

def trace(image, ticks):
    """[(order, row, tick), writes, key-off phase length] per tick; entry 0 is the reset batch (position None)."""
    p = Player(image)
    out = [(None, list(p.batch), p.split)]
    for _ in range(ticks):
        pos = p.position()
        writes = list(p.tick())
        out.append((pos, writes, p.split))
    return out


def format_write(w):
    kind, reg, val = w
    return " PSG=%02X" % val if kind == PSG else " %d%02X=%02X" % (kind, reg, val)


def format_tick(pos, writes, split):
    """One line of a trace file (the host test writes the same): "order row tick:" (or "reset:"), then each write
    as " 0RR=VV" (port 0), " 1RR=VV" (port 1) or " PSG=VV", with " |" after a non-empty key-off phase."""
    where = "reset" if pos is None else "%d %d %d" % pos
    text = "".join(format_write(w) for w in writes[:split]) + (" |" if split else "")
    return where + ":" + text + "".join(format_write(w) for w in writes[split:])


def state_writes(p):
    """The player's register state, as the VGM's loop dump writes it (all values, zeros included)."""
    w = [(YM0, 0x22, p.ym_regs[0][0x22])]
    for ch in range(6):
        port, off = ch // 3, ch % 3
        regs = p.ym_regs[port]
        for k in range(4):
            for base in OP_REGS:
                w.append((port, base + OP_OFFSET[k] + off, regs[base + OP_OFFSET[k] + off]))
        for base in (0xB0, 0xB4, 0xA4, 0xA0):
            w.append((port, base + off, regs[base + off]))
    for i in range(3):
        n = p.tone[i]
        w += [(PSG, 0, 0x80 | i << 5 | n & 15), (PSG, 0, n >> 4), (PSG, 0, 0x90 | i << 5 | p.att[i])]
    w += [(PSG, 0, 0xE0 | p.noise), (PSG, 0, 0xF0 | p.att[3])]
    return w


def vgm(image, max_ticks):
    """VGM 1.71 bytes, as docs/AUDIO_ENGINE.md "YM2612: VGM export" specifies. Returns (bytes, info)."""
    p = Player(image)
    region = image.region
    body = bytearray()

    def emit(writes):
        for kind, reg, val in writes:
            if kind == PSG:
                body.extend((0x50, val))
            else:
                body.extend((0x52 + kind, reg, val))

    def wait(n):
        if n == 735:
            body.append(0x62)
        elif n == 882:
            body.append(0x63)
        else:
            body.extend((0x61, n & 0xFF, n >> 8))

    emit(MACHINE_RESET)
    emit(p.batch)
    per_tick = VGM_TICK_SAMPLES[region]
    samples = 0
    loop_at = loop_samples = None
    ticks = 0
    looped = False
    while ticks < max_ticks:
        if p.position() == (image.loop, 0, 0):
            if loop_at is not None:
                looped = True
                break
            loop_at, loop_samples = 0x100 + len(body), samples
            emit(state_writes(p))
        writes = p.tick()
        emit(writes[:p.split])
        n = per_tick
        if p.split:
            body.append(0x70)  # one sample: the key-off reaches the chip before a key-on (a retrigger)
            n -= 1
        emit(writes[p.split:])
        wait(n)
        samples += per_tick
        ticks += 1
    body.append(0x66)
    header = bytearray(0x100)
    header[0:4] = b"Vgm "
    size = 0x100 + len(body)
    struct.pack_into("<I", header, 0x04, size - 4)
    struct.pack_into("<I", header, 0x08, 0x171)
    struct.pack_into("<I", header, 0x0C, PSG_CLOCK_VGM[region])
    struct.pack_into("<I", header, 0x18, samples)
    if looped:
        struct.pack_into("<I", header, 0x1C, loop_at - 0x1C)
        struct.pack_into("<I", header, 0x20, samples - loop_samples)
    struct.pack_into("<I", header, 0x24, VGM_RATE[region])
    struct.pack_into("<HBB", header, 0x28, 0x0009, 16, 0)
    struct.pack_into("<I", header, 0x2C, YM_CLOCK[region])
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
              ", loop %d samples at 0x%X" % (info["loop_samples"], info["loop_offset"]) if info["looped"]
              else ", no loop"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
