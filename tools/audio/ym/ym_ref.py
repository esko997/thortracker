#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's YM2612 + SN76489 player ("ym2612-fm"), written from the
specification in docs/AUDIO_ENGINE.md ("YM2612: player", "Effects", "Machine reset writes") independently of the C
player (app/src/main/cpp/ym/ym_player.c) and loader (ym_image.c). The host test compares the C player's register
writes and PCM commands with this one's, tick by tick.

    python3 tools/audio/ym/ym_ref.py IMAGE.bin --trace out.txt --ticks 1500

Pure Python 3, no dependencies. Also defines the pitch, PSG period and vibrato tables (generated into
app/src/main/cpp/generated/ym_tables.h).

Writes are records (kind, reg, val): kind 0 = YM2612 port 0, 1 = YM2612 port 1, 2 = PSG byte (reg 0). PCM channels
(PCM 1-3 = XGM2 PCM channels 0-2) make no writes: each batch carries one command per PCM channel, -1 none, 0 stop,
else the variant id the driver starts (one-shot; the last command of a tick wins). The VGM and XGM2 exports are the
XGM2 driver's own output (the machine's chip log and the encoder's frames), not the player's, so nothing here
renders them.

Spec readings chosen where the text leaves room (each reported to the spec owner):
  - FM key-off clears a key-on still pending from the same tick (key-on and a cut at the same tick, e.g. S00):
    the $28 key-off is written (the channel was keyed) and no key-on follows, so the player's key state and the
    chip's agree. On a PCM channel the same tick's start then stop leave the stop (net STOP).
  - `8xy` writes $B4 at once whenever the channel has keyed a note since reset (its note's AMS/FMS), also during
    the release; before the first note it only sets the pan.
  - A PCM note whose key the instrument leaves unmapped (or without a PCM instrument) is ignored: the sounding
    sample plays on and no command is made. A PCM note never keys off first: the driver's start replaces it.
  - Audition/release batches: audition() = reset batch + the key-on's register writes (the key-on $28 itself is
    written by the first tick's update; a PCM audition carries its start in the batch); key_off() = the key-off at
    once (a PCM stop)."""
import argparse
import math
import struct
import sys

MCLK = {0: 53693175, 1: 53203424}
UNITS_PER_SEMITONE = 64
UNITS_PER_OCTAVE = 768
PITCH_MAX = 8 * UNITS_PER_OCTAVE - 1
C0_HZ = 440.0 * 2 ** (-57 / 12)

YM0, YM1, PSG = 0, 1, 2
NOTE_OFF = 0xFE
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}

# Image layout 2 (app/src/main/cpp/ym/ym_image.h): the tonal prefix of layout 1, then the extensions.
IMAGE_SIZE = 1188864
LAYOUT = 2
H_FM3, H_PCM_VARIANTS, H_PCM_BANK_SIZE, H_PCM_DATA_SIZE = 16, 17, 18, 20
HEADER_SIZE = 64
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 5440, 8000
INSTRUMENT_SIZE = 40
TONAL_SIZE = 65536
DATA_MAX = TONAL_SIZE - OFF_DATA  # 57536
OFF_CH3 = 65536                   # slot n: 4 x s16 at + 8 (n - 1), operators 1..4 in diagram order
CH3_LIMIT = 3072
OFF_PCM_ORDERS = 66048            # (order o, PCM channel k) at + 2 (3 o + k)
OFF_PCM_KEYS = 67584              # (slot n, note x) at + 96 (n - 1) + x
PCM_KEYS = 96
OFF_PCM_VARIANTS = 73728          # id: u16 start page, u16 pages, u8 flags, 3 x 0 at + 8 (id - 1)
PCM_VARIANT_SIZE = 8
PCM_MAX_VARIANTS = 123
PCM_HALF_RATE = 0x01
OFF_PCM_DATA = 74752
PCM_DATA_MAX = 65536
OFF_PCM_BANK = 140288
PCM_BANK_SIZE = 1048576
PCM_ALIGN = 256
PCM_BANK_PAGES = PCM_BANK_SIZE // PCM_ALIGN
NO_ENVELOPE = 0xFFFF

CHANNELS = 13          # FM 1-6 (0-5), PSG 1-3 (6-8), Noise (9), PCM 1-3 (10-12)
TONAL_CHANNELS = 10    # the tonal order table's stride
CH_FM3 = 2
CH_NOISE = 9
CH_PCM = 10
PCM_CHANNELS = 3
FM3_NORMAL, FM3_SPECIAL = 0, 1
PCM_NONE, PCM_STOP = -1, 0

# Operator register offsets within a channel's, operators 1..4 (as in the algorithm diagrams).
OP_OFFSET = [0, 8, 4, 12]
OP_REGS = [0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90]
# Bits each operator register must leave 0 ($30 $40 $50 $60 $70 $80 $90).
OP_RESERVED = [0x80, 0x80, 0x20, 0x60, 0xE0, 0x00, 0xF0]
# Carriers by algorithm, as operator indices 0..3 (operator k + 1).
CARRIERS = {0: {3}, 1: {3}, 2: {3}, 3: {3}, 4: {1, 3}, 5: {1, 2, 3}, 6: {1, 2, 3}, 7: {0, 1, 2, 3}}
# FM 3 special mode: the frequency pair ($A4-style high register) of operators 1..3; operator 4 uses the channel's.
CH3_HI = [0xAD, 0xAE, 0xAC]
# Effects a PCM channel takes.
PCM_EFFECTS = {FX[c] for c in "BDFGLS"}


def channel_kind(c):
    """"fm" 0-5, "psg" 6-8, "noise" 9, "pcm" 10-12."""
    return "fm" if c < 6 else "psg" if c < 9 else "noise" if c == 9 else "pcm"


def instrument_kind(c):
    """The instrument kind channel c plays: "fm", "psg" (PSG 1-3 and Noise) or "pcm"."""
    k = channel_kind(c)
    return "psg" if k == "noise" else k


def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


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


def order_entry(order, channel):
    """Image offset of the pattern offset of (order, channel): the tonal or the PCM order table."""
    if channel < TONAL_CHANNELS:
        return OFF_ORDERS + 2 * (TONAL_CHANNELS * order + channel)
    return OFF_PCM_ORDERS + 2 * (PCM_CHANNELS * order + channel - CH_PCM)


def region_base(channel):
    """The data region channel's pattern offsets point into."""
    return OFF_DATA if channel < TONAL_CHANNELS else OFF_PCM_DATA


class Image:
    """A loaded layout-2 image: every check of ym_load_image, in its order (the first failure raises ImageError with
    the loader's reason and image offset)."""

    def __init__(self, data):
        data = bytes(data)
        self.data = data
        if len(data) != IMAGE_SIZE:
            raise ImageError("image size %d, not %d" % (len(data), IMAGE_SIZE))
        u16 = self.u16
        need = self._need
        need(data[:4] == b"TTYM", "not a YM2612 image (magic)", 0)
        need(data[4] == LAYOUT, "unknown layout", 4)
        self.region = data[5]
        need(self.region <= 1, "region must be 0 (NTSC) or 1 (PAL)", 5)
        self.speed = data[6]
        need(self.speed >= 1, "speed must be 1..255", 6)
        self.lfo = data[7]
        need(self.lfo == 0 or 0x08 <= self.lfo <= 0x0F, "LFO byte must be 0 or 8..15", 7)
        self.orders = u16(8)
        need(1 <= self.orders <= 256, "order count must be 1..256", 8)
        self.loop = u16(10)
        need(self.loop < self.orders, "loop order past the last order", 10)
        self.instrument_count = data[12]
        need(self.instrument_count <= 64, "instrument count above 64", 12)
        need(data[13] == 0, "header reserved byte 13", 13)
        self.data_size = u16(14)
        need(self.data_size <= DATA_MAX, "data size above the region", 14)
        self.fm3 = data[H_FM3]
        need(self.fm3 <= FM3_SPECIAL, "FM 3 mode must be 0 (normal) or 1 (special)", H_FM3)
        self.pcm_variants = data[H_PCM_VARIANTS]
        need(self.pcm_variants <= PCM_MAX_VARIANTS, "PCM variant count above 123", H_PCM_VARIANTS)
        self.pcm_pages = u16(H_PCM_BANK_SIZE)
        need(self.pcm_pages <= PCM_BANK_PAGES, "PCM bank size above 1 MiB", H_PCM_BANK_SIZE)
        self.pcm_data_size = struct.unpack_from("<I", data, H_PCM_DATA_SIZE)[0]
        need(self.pcm_data_size <= PCM_DATA_MAX, "PCM data size above the region", H_PCM_DATA_SIZE)
        need(self._zero(24, HEADER_SIZE), "header reserved bytes 24..63", 24)
        need(self._zero(OFF_DATA + self.data_size, TONAL_SIZE), "tonal data bytes past the data size",
             OFF_DATA + self.data_size)
        table_end = OFF_PCM_VARIANTS + PCM_VARIANT_SIZE * PCM_MAX_VARIANTS
        need(self._zero(table_end, OFF_PCM_DATA), "PCM variant table padding", table_end)
        need(self._zero(OFF_PCM_DATA + self.pcm_data_size, OFF_PCM_DATA + PCM_DATA_MAX),
             "PCM data bytes past the PCM data size", OFF_PCM_DATA + self.pcm_data_size)
        for o in range(self.orders, 256):
            need(data[OFF_ROWS + o] == 0, "rows of an order past the order count", OFF_ROWS + o)
            at, pcm = order_entry(o, 0), order_entry(o, CH_PCM)
            need(self._zero(at, at + 2 * TONAL_CHANNELS) and self._zero(pcm, pcm + 2 * PCM_CHANNELS),
                 "patterns of an order past the order count", at)
        self.rows = [(data[OFF_ROWS + o] or 256) for o in range(self.orders)]
        self.variants = {}
        self._check_variants()
        self.instruments = {}
        for slot in range(1, 65):
            self._check_instrument(slot)
        self.start_speed = self._check_patterns()
        self._decoded = {}

    # -- helpers --
    def u16(self, at):
        return self.data[at] | self.data[at + 1] << 8

    def _zero(self, start, end):
        return not any(self.data[start:end])

    @staticmethod
    def _need(ok, what, at):
        if not ok:
            raise ImageError("%s (byte %d)" % (what, at))

    def _size(self, channel):
        return self.data_size if channel < TONAL_CHANNELS else self.pcm_data_size

    # -- checks --
    def _check_variants(self):
        """Contiguous in id order from page 0, filling exactly the used bank; the unused bank, a half-rate variant's
        second half and the entries past the count zero."""
        data, need = self.data, self._need
        nxt = 0
        for vid in range(1, PCM_MAX_VARIANTS + 1):
            at = OFF_PCM_VARIANTS + PCM_VARIANT_SIZE * (vid - 1)
            if vid > self.pcm_variants:
                need(self._zero(at, at + PCM_VARIANT_SIZE), "PCM variant entry past the variant count", at)
                continue
            start, pages, flags = self.u16(at), self.u16(at + 2), data[at + 4]
            need(start == nxt, "PCM variants must follow each other in id order", at)
            need(pages >= 1, "PCM variant length must be at least one page", at + 2)
            need(not flags & ~PCM_HALF_RATE, "PCM variant flags: only bit 0", at + 4)
            need(self._zero(at + 5, at + 8), "PCM variant reserved bytes 5..7", at + 5)
            nxt = start + pages
            need(nxt <= self.pcm_pages, "PCM variant past the bank size", at + 2)
            if flags & PCM_HALF_RATE:
                half = pages * PCM_ALIGN // 2
                need(not pages & 1, "half-rate PCM variant needs an even page count", at + 2)
                second = OFF_PCM_BANK + start * PCM_ALIGN + half
                need(self._zero(second, second + half), "half-rate PCM variant needs its zeroed second half", second)
            self.variants[vid] = (start, pages, flags)
        need(nxt == self.pcm_pages, "PCM bank size differs from its variants", H_PCM_BANK_SIZE)
        used = OFF_PCM_BANK + self.pcm_pages * PCM_ALIGN
        need(self._zero(used, OFF_PCM_BANK + PCM_BANK_SIZE), "PCM bank bytes past the bank size", used)

    def _envelope(self, field, kind):
        """The envelope named by the u16 at image offset `field` as (values, loop, release), None for none; kind 0
        volume, 1 arpeggio, 2 pitch."""
        need, size = self._need, self.data_size
        off = self.u16(field)
        if off == NO_ENVELOPE:
            return None
        need(off + 6 <= size, "envelope outside the data size", field)
        e = OFF_DATA + off
        length, loop, release = struct.unpack_from("<HHH", self.data, e)
        need(1 <= length <= 256, "envelope length outside 1..256", e)
        need(off + 6 + length <= size, "envelope outside the data size", e)
        need(loop == NO_ENVELOPE or loop < length, "envelope loop past its end", e + 2)
        if release != NO_ENVELOPE:
            need(kind == 0, "release point on an arpeggio or pitch envelope", e + 4)
            need(loop != NO_ENVELOPE and loop < release < length, "volume release point needs a loop before it", e + 4)
        values = list(struct.unpack_from("%db" % length, self.data, e + 6))
        for i, v in enumerate(values):
            if kind == 0:
                need(0 <= v <= 15, "volume envelope value outside 0..15", e + 6 + i)
            else:
                need(-64 <= v <= 63, "arpeggio or pitch envelope value outside -64..63", e + 6 + i)
        return (values, None if loop == NO_ENVELOPE else loop, None if release == NO_ENVELOPE else release)

    def _check_instrument(self, slot):
        data, need = self.data, self._need
        base = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        rec = data[base: base + INSTRUMENT_SIZE]
        ch3 = OFF_CH3 + 8 * (slot - 1)
        keys = OFF_PCM_KEYS + PCM_KEYS * (slot - 1)
        flags = rec[0]
        need(flags == 0x82 or self._zero(keys, keys + PCM_KEYS), "PCM key map of a slot that is not a PCM instrument", keys)
        need(flags == 0x80 or self._zero(ch3, ch3 + 8), "FM 3 operator offsets of a slot that is not an FM instrument", ch3)
        if not any(rec):
            return
        need(slot <= self.instrument_count, "instrument record past the instrument count", base)
        need(flags in (0x80, 0x81, 0x82), "instrument flags", base)
        if flags == 0x82:
            need(not any(rec[1:]), "PCM instrument reserved bytes 1..39", base + 1)
            for x in range(PCM_KEYS):
                need(data[keys + x] <= self.pcm_variants, "PCM key maps a variant past the variant count", keys + x)
            self.instruments[slot] = {"kind": "pcm", "keys": data[keys: keys + PCM_KEYS]}
            return
        if flags == 0x81:
            need(rec[1] <= 7, "PSG noise mode above 7", base + 1)
            need(not rec[2] & ~1, "PSG flags: only bit 0", base + 2)
            need(rec[3] == 0, "PSG instrument reserved byte 3", base + 3)
            envs = [self._envelope(base + 4 + 2 * k, k) for k in range(3)]
            need(not any(rec[10:32]), "PSG instrument reserved bytes 10..31", base + 10)
            inst = {"kind": "psg", "noise": rec[1], "relative": bool(rec[2] & 1), "envs": envs}
        else:
            need(rec[1] <= 7, "FM algorithm above 7", base + 1)
            need(rec[2] <= 7, "FM feedback above 7", base + 2)
            need(not rec[3] & 0xC8, "FM AMS/FMS byte: only bits 0-2 and 4-5", base + 3)
            for k in range(4):
                for r in range(7):
                    at = 4 + 7 * k + r
                    need(not rec[at] & OP_RESERVED[r], "FM operator register with reserved bits set", base + at)
            offsets = list(struct.unpack_from("<4h", data, ch3))
            for k, v in enumerate(offsets):
                need(-CH3_LIMIT <= v <= CH3_LIMIT, "FM 3 operator offset outside -3072..3072", ch3 + 2 * k)
            inst = {"kind": "fm", "alg": rec[1], "fb": rec[2], "amsfms": rec[3],
                    "ops": [list(rec[4 + 7 * k: 11 + 7 * k]) for k in range(4)], "ch3": offsets}
        sweep = struct.unpack_from("bbB", rec, 32)
        need(-96 <= sweep[0] <= 96, "sweep start/end outside -96..96 semitones", base + 32)
        need(-96 <= sweep[1] <= 96, "sweep start/end outside -96..96 semitones", base + 33)
        need(not any(rec[35:40]), "instrument reserved bytes 35..39", base + 35)
        inst["sweep"] = sweep
        self.instruments[slot] = inst

    def _effect_problem(self, c, code, param):
        """Why `code` with `param` may not stand on channel c, else None (the loader's rules)."""
        x, y = param >> 4, param & 15
        kind = channel_kind(c)
        fm = kind == "fm"
        if kind == "pcm" and code not in PCM_EFFECTS:
            return "effect not available on a PCM channel (only B, D, F, G, L, S)"
        if code in (FX["0"], FX["1"], FX["2"], FX["3"], FX["4"], FX["A"], FX["D"], FX["G"], FX["P"], FX["S"]):
            return None
        if code == FX["8"]:
            return None if fm else "8xy pan on a PSG channel (FM only)"
        if code == FX["B"]:
            return "Bxx jumps past the last order" if param >= self.orders else None
        if code == FX["C"]:
            if param > (0x7F if fm else 0x0F):
                return "Cxx volume above 7F" if fm else "Cxx volume above 0F on a PSG channel"
            return None
        if code == FX["E"]:
            if not fm or param > 7:
                return "Exx feedback above 7" if fm else "Exx feedback on a PSG channel (FM only)"
            return None
        if code == FX["F"]:
            return "F00: speed must be 1..FF" if param == 0 else None
        if code == FX["I"]:
            if not fm or param > 0x7F:
                return "Ixx brightness above 7F" if fm else "Ixx brightness on a PSG channel (FM only)"
            return None
        if code == FX["L"]:
            return "Lxx LFO must be 00 or 08..0F" if param != 0 and not 0x08 <= param <= 0x0F else None
        if code == FX["M"]:
            if not fm or not 1 <= x <= 4:
                return "Mxy operator must be 1..4" if fm else "Mxy ratio on a PSG channel (FM only)"
            return None
        if code == FX["N"]:
            if c != CH_NOISE or x > 1 or y > 3:
                return "Nxy: x must be 0..1 and y 0..3" if c == CH_NOISE else "Nxy noise mode outside the Noise channel"
            return None
        return "unknown effect code"

    def _check_patterns(self):
        """Every (order, channel) decodes rows(o) rows inside its region's used data (rows, then channels, as the
        loader walks them). Returns the speed each order starts with in sequential straight play (header speed,
        then each F: rows, then channels, then columns, the last wins; jumps ignored)."""
        data, need = self.data, self._need
        speed, speeds = self.speed, []
        for o in range(self.orders):
            speeds.append(speed)
            offsets, runs = [], [0] * CHANNELS
            for c in range(CHANNELS):
                off = self.u16(order_entry(o, c))
                need(off < self._size(c), "pattern offset past the data size", order_entry(o, c))
                offsets.append(off)
            for r in range(self.rows[o]):
                for c in range(CHANNELS):
                    if runs[c]:
                        runs[c] -= 1
                        continue
                    size, base, kind = self._size(c), region_base(c), instrument_kind(c)
                    at = offsets[c]
                    need(at < size, "pattern runs past the data size", base + at)
                    m = data[base + at]
                    at += 1
                    if m & 0x80:
                        runs[c] = m & 0x7F
                        offsets[c] = at
                        continue
                    effects = (m >> 2) & 7
                    need(m and not m & 0x60 and effects <= 4, "row mask byte", base + at - 1)
                    need(at + (m & 1) + ((m >> 1) & 1) + 2 * effects <= size, "row runs past the data size", base + at)
                    if m & 1:
                        note = data[base + at]
                        need(note < 96 or note == NOTE_OFF, "note byte", base + at)
                        at += 1
                    if m & 2:
                        inst = self.instruments.get(data[base + at])
                        need(inst is not None, "row names an instrument the image does not define", base + at)
                        if inst["kind"] != kind:
                            if kind == "pcm":
                                why = "FM or PSG instrument on a PCM channel"
                            elif inst["kind"] == "pcm":
                                why = "PCM instrument on an FM or PSG channel"
                            else:
                                why = "FM instrument on a PSG channel" if kind == "psg" else "PSG instrument on an FM channel"
                            need(False, why, base + at)
                        at += 1
                    for _ in range(effects):
                        code, param = data[base + at], data[base + at + 1]
                        why = "effect code above Z" if code > 35 else self._effect_problem(c, code, param)
                        need(why is None, why, base + at)
                        if code == FX["F"]:
                            speed = param
                        at += 2
                    offsets[c] = at
        return speeds

    # -- reading --
    def instrument(self, slot):
        return self.instruments.get(slot)

    def pcm_variant(self, slot, note):
        """The variant slot maps note to, 0 = unmapped or not a PCM instrument."""
        inst = self.instruments.get(slot)
        if not 0 <= note < PCM_KEYS or inst is None or inst["kind"] != "pcm":
            return 0
        return inst["keys"][note]

    def pattern_rows(self, order, channel):
        """The rows(order) rows of the pattern of (order, channel): None (empty) or (note, inst, [(code, param)])."""
        base = region_base(channel)
        offset, n = self.u16(order_entry(order, channel)), self.rows[order]
        key = (base, offset, n)
        if key in self._decoded:
            return self._decoded[key]
        at, rows, data = base + offset, [], self.data
        while len(rows) < n:
            m = data[at]
            at += 1
            if m & 0x80:
                rows.extend([None] * ((m & 0x7F) + 1))
                continue
            note = inst = None
            if m & 1:
                note = data[at]
                at += 1
            if m & 2:
                inst = data[at]
                at += 1
            effects = []
            for _ in range((m >> 2) & 7):
                effects.append((data[at], data[at + 1]))
                at += 2
            rows.append((note, inst, effects))
        rows = rows[:n]
        self._decoded[key] = rows
        return rows


def speed_at(image, order, row, started):
    """Ticks per row straight play has at (order, row): the order's start speed and the Fxx of the rows before it
    (and of the row itself once it has started)."""
    s = image.start_speed[order]
    for r in range(row + 1 if started else row):
        for c in range(CHANNELS):
            cell = image.pattern_rows(order, c)[r]
            for code, param in (cell[2] if cell else []):
                if code == FX["F"]:
                    s = param
    return s


def row_delay(row):
    """The row's last Gxx parameter (0 = none)."""
    g = 0
    for code, param in (row[2] if row else []):
        if code == FX["G"]:
            g = param
    return g


def variant_bytes(image, vid):
    """Variant vid's compiled bytes, page count and flags (what the driver plays)."""
    start, pages, flags = image.variants[vid]
    at = OFF_PCM_BANK + start * PCM_ALIGN
    return image.data[at: at + pages * PCM_ALIGN], pages, flags


def same_kit(a, sa, b, sb):
    """Slot sa of a and slot sb of b are the same kit: both PCM, a key mapped, every key unmapped in both or mapped
    to variants with the same compiled bytes."""
    inst = b.instrument(sb)
    if inst is None or inst["kind"] != "pcm":
        return False
    mapped = False
    for x in range(PCM_KEYS):
        va, vb = a.pcm_variant(sa, x), b.pcm_variant(sb, x)
        if bool(va) != bool(vb) or (va and variant_bytes(a, va) != variant_bytes(b, vb)):
            return False
        mapped = mapped or bool(va)
    return mapped


def relocate_pcm(t, old, image):
    """A PCM track remembers its kit by slot and its sounding variant by id: an edit that renumbers the kits (or
    recompiles the bank) moves them to the slot whose every key names the same sounds and to the variant with the
    same bytes; a changed kit keeps its slot, a variant no longer compiled is forgotten."""
    s = t.slot
    inst = old.instrument(s) if s else None
    if inst is not None and inst["kind"] == "pcm" and not same_kit(old, s, image, s):
        for n in range(1, 65):
            if n != s and same_kit(old, s, image, n):
                t.slot = n
                break
    if t.pcm:
        want = variant_bytes(old, t.pcm)
        if t.pcm <= image.pcm_variants and variant_bytes(image, t.pcm) == want:
            return
        t.pcm = next((v for v in range(1, image.pcm_variants + 1) if variant_bytes(image, v) == want), 0)


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
        self.keyed = False         # FM/PSG: the note is held; PCM: a sample was started and not stopped
        self.pending_on = False    # FM: key-on written by the next update
        self.started = False       # some note has been keyed since reset
        self.note = None           # FM: dict(ops, alg, fb, amsfms, carriers) of the keyed note
        self.ch3 = [0, 0, 0, 0]    # FM: the keyed note's FM 3 special-mode offsets
        self.pcm = 0               # PCM: the variant last started, 0 none
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
        self.vol8 = 0x7F * 8 if self.kind == "fm" else 0 if self.kind == "pcm" else 0x0F * 8
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
        self.reset()

    # -- writes --
    def reset(self):
        """The machine reset's shadow, position (0, 0, 0) at the header speed; the batch = $22 (header LFO) and
        $27 (FM 3 mode) where they differ from the reset chip."""
        self.ym_regs = [[0] * 256, [0] * 256]
        for kind, reg, val in MACHINE_RESET:
            if kind != PSG and reg != 0x28:
                self.ym_regs[kind][reg] = val
        self.tone = [0, 0, 0]
        self.att = [15, 15, 15, 15]
        self.noise = 0
        self.out, self.key_offs = [], []
        self.pcm = [PCM_NONE] * PCM_CHANNELS
        self.tracks = [Track(c) for c in range(CHANNELS)]
        self.region = self.img.region
        self.lfo = self.img.lfo
        self.fm3 = self.img.fm3
        self.speed = self.img.speed
        self.order = self.row = self.tick_in_row = 0
        self.next_pos = None
        self.auditioning = False
        self.ym(YM0, 0x22, self.lfo)
        self.ym(YM0, 0x27, self.mode27())
        self._close_batch()

    def mode27(self):
        """Register $27: only the FM 3 mode (bit 6); the XGM2 driver owns the timer bits."""
        return 0x40 if self.fm3 == FM3_SPECIAL else 0x00

    def _begin_batch(self):
        self.out, self.key_offs = [], []
        self.pcm = [PCM_NONE] * PCM_CHANNELS

    def _close_batch(self):
        self.batch = self.key_offs + self.out
        self.split = len(self.key_offs)
        return self.batch

    def ym(self, port, reg, val):
        if self.ym_regs[port][reg] != val:
            self.ym_regs[port][reg] = val
            self.out.append((port, reg, val))

    def ym_pair(self, port, hi, a4, a0):
        """A frequency pair: the high register then the low one (hi - 4), both when either differs."""
        regs = self.ym_regs[port]
        if regs[hi] == a4 and regs[hi - 4] == a0:
            return
        regs[hi], regs[hi - 4] = a4, a0
        self.out += [(port, hi, a4), (port, hi - 4, a0)]

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
        if inst is None or inst["kind"] != "fm":
            return
        self.fm_key_off(t)
        ops = [list(o) for o in inst["ops"]]
        alg, fb, amsfms = inst["alg"], inst["fb"], inst["amsfms"]
        carriers = CARRIERS[alg]
        t.ch3 = list(inst["ch3"])  # copied at key-on (used on FM 3 in special mode only)
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
        if inst is None or inst["kind"] != "psg":
            return
        self.psg_key_off(t)
        t.envs = list(inst["envs"])  # copied: an instrument edit applies to the next note
        t.pos = [0, 0, 0]
        t.relative = inst["relative"]
        t.acc = 0
        t.phase = ON
        if t.kind == "noise":
            t.mode = inst["noise"] if noise_lock is None else noise_lock
        self.pitch_start(t, note, inst)
        t.keyed = t.started = True

    # -- PCM --
    def pcm_key_off(self, t):
        """OFF, Sxx, audition release: stop the channel's sample (if one was started and not stopped)."""
        if t.keyed:
            t.keyed = False
            t.pcm = 0
            self.pcm[t.ch - CH_PCM] = PCM_STOP

    def pcm_key_on(self, t, note):
        """Start the variant the instrument maps the note to on the channel's XGM2 PCM channel (it replaces whatever
        that channel played); an unmapped note or a missing PCM instrument does nothing."""
        v = self.img.pcm_variant(t.slot, note)
        if not v:
            return
        t.pcm = v
        self.pcm[t.ch - CH_PCM] = v
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
        elif t.kind == "pcm":
            self.pcm_key_off(t)
        else:
            self.psg_key_off(t)

    def key_on(self, t, note, locks, noise_lock):
        if t.kind == "fm":
            self.fm_key_on(t, note, locks)
        elif t.kind == "pcm":
            self.pcm_key_on(t, note)
        else:
            self.psg_key_on(t, note, noise_lock)

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
            else:
                self.key_on(t, note, locks, noise_lock)

    # -- update --
    @staticmethod
    def pitch(t, extra=0):
        arp = 0
        if t.arp and t.arp_phase:
            arp = (t.arp >> 4) if t.arp_phase == 1 else (t.arp & 15)
        vib = (SINE[t.vib_phase] * t.vib[1]) >> 4 if t.vib[1] else 0  # floor shift
        return clamp(t.base + arp * 64 + t.fine + t.slide + vib + t.sweep + extra, 0, PITCH_MAX)

    @staticmethod
    def advance_effects(t, vol_max):
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
        n = self.tracks[CH_NOISE]
        return n.started and n.mode & 3 == 3 and n.phase != OFF

    def update_fm(self, t):
        ch = t.ch
        p = self.pitch(t)
        port, off = self.port_off(ch)
        for k in range(4):
            if k in t.note["carriers"]:
                self.ym(port, 0x40 + OP_OFFSET[k] + off, self.carrier_tl(t, k))
        if ch == CH_FM3 and self.fm3 == FM3_SPECIAL:
            # Operators 1-3 on their own frequency pairs, operator 4 on the channel's: each at the note's pitch
            # (slides, arpeggio, vibrato, sweep and fine included) plus its offset, clamped.
            for k in range(3):
                self.ym_pair(YM0, CH3_HI[k], *fm_regs(clamp(p + t.ch3[k], 0, PITCH_MAX), self.region))
            a4, a0 = fm_regs(clamp(p + t.ch3[3], 0, PITCH_MAX), self.region)
        else:
            a4, a0 = fm_regs(p, self.region)
        self.ym_pair(port, 0xA4 + off, a4, a0)
        if t.pending_on:
            self.out.append((YM0, 0x28, 0xF0 | self.key_select(ch)))
            t.pending_on = False
        self.advance_effects(t, 1016)

    def update_psg(self, t):
        ch = t.ch
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
        self.advance_effects(t, 120)
        self.envelopes(t)

    def update(self, ch):
        """A channel's per-tick writes; a PCM channel has none (the driver plays the started sample)."""
        t = self.tracks[ch]
        if not t.started or t.kind == "pcm":
            return
        if t.kind == "fm":
            self.update_fm(t)
        else:
            self.update_psg(t)

    # -- sequencing --
    def derive_jump(self):
        """B/D of the current rows -> next_pos (B wins its order, D its row; the last channel's of each)."""
        img = self.img
        jump_order = jump_row = None
        for t in self.tracks:
            for code, param in (t.row[2] if t.row else []):
                if code == FX["B"]:
                    jump_order = param
                elif code == FX["D"]:
                    jump_row = param
        if jump_order is None and jump_row is None:
            self.next_pos = None
        else:
            if jump_order is None:
                jump_order = self.order + 1 if self.order + 1 < img.orders else img.loop
            self.next_pos = (jump_order, jump_row or 0)

    def begin_row(self):
        img = self.img
        for ch in range(CHANNELS):
            self.tracks[ch].row = img.pattern_rows(self.order, ch)[self.row]
        for t in self.tracks:
            for code, param in (t.row[2] if t.row else []):
                if code == FX["F"]:
                    self.speed = param
        self.derive_jump()
        for ch in range(CHANNELS):
            t = self.tracks[ch]
            t.cut = None
            t.delay = None
            g = row_delay(t.row)
            if g:
                t.delay = g
            else:
                self.run_row(ch)

    def advance(self):
        """The next row. Every arrival at the loop order's row 0 starts it at the speed straight play reaches it with."""
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
            self.speed = img.start_speed[self.order]

    def tick(self):
        self._begin_batch()
        self.ym(YM0, 0x22, self.lfo)
        self.ym(YM0, 0x27, self.mode27())
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
                self.advance()
        return self._close_batch()

    def position(self):
        return self.order, self.row, self.tick_in_row

    def place(self, order):
        """Start from (order, row 0) at the speed straight play starts that order with (seek fallback)."""
        self.reset()
        self.order = order
        self.speed = self.img.start_speed[order]

    def relocate(self, image):
        """Live edit at a tick boundary: the position kept (clamped into the new song), FM 3's mode and a changed
        header LFO taken over, the speed re-derived when the rows up to here changed it; a started row's jump and
        still-delayed rows re-read from the new data. Sounding notes go on."""
        if self.auditioning:
            return
        old = self.img
        if image.lfo != old.lfo:
            self.lfo = image.lfo
        self.fm3 = image.fm3
        for t in self.tracks[CH_PCM:]:
            relocate_pcm(t, old, image)
        started = self.tick_in_row > 0
        old_speed = speed_at(old, self.order, self.row, started)
        self.img = image
        clamped = False
        if self.order >= image.orders:
            self.order = image.orders - 1
            self.row = image.rows[self.order] - 1
            clamped = True
        elif self.row >= image.rows[self.order]:
            self.row = image.rows[self.order] - 1
            clamped = True
        if clamped:
            self.next_pos = None
            for t in self.tracks:
                t.delay = None
            self.speed = speed_at(image, self.order, self.row, True)
            if not started:
                self.advance()  # the clamped row counts as played: the next row follows it
            return
        new_speed = speed_at(image, self.order, self.row, started)
        if new_speed != old_speed:
            self.speed = new_speed
        if not started:
            return
        for t in self.tracks:
            t.row = image.pattern_rows(self.order, t.ch)[self.row]
            if t.delay is not None:
                g = row_delay(t.row)
                t.delay = g if g >= self.tick_in_row else None
        self.derive_jump()

    # -- audition --
    def audition(self, channel, note, slot, fine):
        """Reset, then key `note` on `channel` with `slot` (no locks), `fine` added like Pxx (ignored on PCM). The
        slot must be of the channel's kind and a PCM note mapped. Returns the batch (the reset writes and the
        instrument's registers; an FM key-on is written by the first tick; a PCM start is in self.pcm)."""
        if not (0 <= channel < CHANNELS and 0 <= note <= 95 and -128 <= fine <= 127):
            raise ImageError("audition arguments")
        kind = instrument_kind(channel)
        inst = self.img.instrument(slot)
        if inst is None or inst["kind"] != kind or (kind == "pcm" and not self.img.pcm_variant(slot, note)):
            raise ImageError("audition instrument or unmapped PCM note")
        self.reset()
        self.auditioning = True
        t = self.tracks[channel]
        t.slot = slot
        self.key_on(t, note, [], None)
        t.fine = fine
        # self.out still holds the reset writes (no key-off phase): the batch = reset + key-on registers
        return self._close_batch()

    def key_off(self, channel):
        """Audition release: the channel's key-off now, as a batch."""
        self._begin_batch()
        if 0 <= channel < CHANNELS:
            self.note_off(channel)
        return self._close_batch()


# ---- trace -----------------------------------------------------------------------------------------------

def trace(image, ticks):
    """[((order, row, tick), writes, key-off phase length, (pcm0, pcm1, pcm2))] per tick; entry 0 is the reset batch
    (position None, no PCM commands)."""
    p = Player(image)
    out = [(None, list(p.batch), p.split, tuple(p.pcm))]
    for _ in range(ticks):
        pos = p.position()
        writes = list(p.tick())
        out.append((pos, writes, p.split, tuple(p.pcm)))
    return out


def format_write(w):
    kind, reg, val = w
    return " PSG=%02X" % val if kind == PSG else " %d%02X=%02X" % (kind, reg, val)


def format_tick(pos, writes, split, pcm=(PCM_NONE,) * PCM_CHANNELS):
    """One line of a trace file (the host test writes the same): "order row tick:" (or "reset:"), then each write
    as " 0RR=VV" (port 0), " 1RR=VV" (port 1) or " PSG=VV", with " |" after a non-empty key-off phase, then
    " P<k>=<v>" for each PCM channel k with a command (0 stop, else the variant id)."""
    where = "reset" if pos is None else "%d %d %d" % pos
    text = "".join(format_write(w) for w in writes[:split]) + (" |" if split else "")
    cmds = "".join(" P%d=%d" % (k, v) for k, v in enumerate(pcm) if v != PCM_NONE)
    return where + ":" + text + "".join(format_write(w) for w in writes[split:]) + cmds


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("image")
    ap.add_argument("--trace", required=True)
    ap.add_argument("--ticks", type=int, default=1500)
    args = ap.parse_args(argv)
    with open(args.image, "rb") as f:
        image = Image(f.read())
    with open(args.trace, "w") as f:
        for entry in trace(image, args.ticks):
            f.write(format_tick(*entry) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
