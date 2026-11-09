#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's POKEY player ("atari-pokey"), written from the specification in
docs/AUDIO_ENGINE.md ("POKEY: Thor Tracker's own player on the Atari 8-bit POKEY") and the numbers in
app/src/main/cpp/pokey/pokey_image.h, independently of the C player (pokey_player.c, pokey_machine.c,
pokey_vgm.c). The machine around it (tick cycles, the timers' interrupts) runs on the reference chip model
pokey_chip_ref.py. tools/audio/pokey_reference_check.py compares its output with the native host test's.

    python3 tools/audio/pokey/pokey_ref.py IMAGE.bin --trace out.txt [--ticks 1500]
    python3 tools/audio/pokey/pokey_ref.py IMAGE.bin --vgm out.vgm [--max-ticks 36000]

Trace format (the host test writes the same): one line per event, register numbers and values in upper-case
hex, the other numbers decimal:
    T <tick> <order> <row> <tickInRow> <cycle>: R=VV ...   the tick's batch (position before the tick)
    I <cycle> <timers>: R=VV ...                           a timer interrupt (timers = PK_IRQ_* bits, one digit)
Tick k's batch is written at cycle k * frame cycles / multispeed; the tick then runs the chip to the next tick's
cycle (inclusive), so an interrupt in that cycle comes before the next T line. The reset batch is not a trace
line (it is the VGM's first data).

Readings of points the specification leaves open (agreed with the native slice and the parent, 2026-11-08):
  - Reset batch: SKCTL 00, AUDCTL = header byte 13, AUDF1/AUDC1 .. AUDF4/AUDC4 00, IRQEN 00, SKCTL 03, all
    written regardless of the shadow (the shadow then holds them), at cycle 0 before tick 0's batch.
  - STIMER is written as 00. The R pair and STIMER bypass the shadow; the shadow keeps the final SKCTL.
  - Lane volume is kept x8 (Cxx sets xx * 8, Axy slides it in 0..120); levels use vol = volume >> 3:
    level = ceil(env * vol / 15), sample levels ceil(level * vol / 15).
  - A sample voice starts at level index 0 (AUDC = 0x10 | its scaled level at the key-on tick).
  - Relative pitch envelope: the step's value is added to the accumulator, clamped to -1728..1728, then used.
  - Lane effects (arpeggio step, slides, portamento, vibrato phase, Axy) advance only while the lane holds a
    voice (released and silenced voices included).
  - A channel no voice holds gets AUDC 0; its AUDF stays at its shadow value.
  - OFF / Sxx on an unreleased voice: envelopes with a release point go to it; if the volume envelope has no
    release point (or the sound has none) the voice is silenced at once: own channel level 0, partners keep the
    partner AUDC, envelopes stop advancing (their steps are still read: a relative pitch accumulator keeps
    adding its frozen step), pitch still updates. Samples stop (AUDC 0, interrupt off). OFF on a released voice
    does nothing. Portamento glides only a held voice: not released, and a sample voice still playing (a one-shot
    sample the interrupt routine ran to its end keys on afresh); a synthesized voice whose envelopes ended is held.
  - Arrival at row 0 of the loop order (the wrap, a jump, the first pass) sets the speed, the shared AUDCTL bits
    and the two-tone state to those sequential straight play starts the loop order with.
  - 16-bit pitch search: N0 = the N whose period is nearest the target (floor(x + 0.5) of Pt - 7 or
    Pt / unit - 1), clamped; candidates max(0, N0 - 24) .. min(max, N0 + 24). Difference sounds: no coprime
    filter; the 16-bit estimate uses the period difference in cycles (d, or 28 d / 114 d on a base clock).
  - Interrupt routine: per fired timer 1, 2, 4: AUDC, then (if the sample stopped) IRQEN at once, both through
    the shadow, at the interrupt's cycle.
  - VGM: waits are exact integers samples(c) = c * 77 // 3125 (NTSC) or c * 88200 // 3546895 (PAL); 735 = 0x62,
    882 = 0x63, 1..16 = 0x7n, otherwise 0x61 in chunks of at most 65535. IRQEN is not logged. The loop point is
    placed after the wait to the loop tick's cycle and is followed by the dump SKCTL, AUDCTL, AUDF1, AUDC1 ..
    AUDF4, AUDC4 (shadow values), then that tick's batch. The file ends with the wait to the end tick's cycle.
Pure Python 3, no dependencies."""
import argparse
import math
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pokey_chip_ref as chipref  # noqa: E402

# pokey_image.h
IMAGE_SIZE = 65536
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_SAMPLES = 64, 320, 2368, 4416
OFF_DATA, OFF_SAMPLE_DATA = 4544, 49152
ENV_NONE = 0xFFFF
NOTE_OFF = 0xFE
F_DEFINED, F_JOINED, F_FILTERED, F_SAMPLE, F_FAST, F_RELATIVE, F_DIFFERENCE = 0x80, 0x01, 0x02, 0x04, 0x08, 0x10, \
    0x20
DIST_PURE, DIST_VOLUME_ONLY = 5, 8
PCM_MIN_PERIOD = 114
PITCH_MAX = 108 * 64 - 1
SLIDE_LIMIT = 6912
RELATIVE_LIMIT = 1728
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}
CLOCKS = (chipref.CLOCK_NTSC, chipref.CLOCK_PAL)
VGM_CLOCKS = (1789773, 1773448)
VGM_RATES = (60, 50)

SINE = [math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64)]

# Kinds: lanes they play on, lanes where `fast` is allowed, channels taken (own first).
TONE, JOINED, FILTERED, JOINED_FILTERED, SAMPLE = range(5)
LANES = {TONE: (0, 1, 2, 3), JOINED: (1, 3), FILTERED: (0, 1), JOINED_FILTERED: (1,), SAMPLE: (0, 1, 3)}
FAST_LANES = {TONE: (0, 2), JOINED: (1, 3), FILTERED: (0,), JOINED_FILTERED: (1,), SAMPLE: (0,)}
TIMER_BIT = {0: 0x01, 1: 0x02, 3: 0x04}  # sample lanes -> IRQEN bit
# (distortion) -> (D fires per waveform period, M pattern modulus)
DIVISORS = {0: (2, 31), 1: (62, 31), 2: (465, 465), 3: (62, 31), 4: (2, 1), 5: (2, 1), 6: (15, 15), 7: (2, 1),
            8: (2, 1)}


def kind_of(flags):
    if flags & F_SAMPLE:
        return SAMPLE
    j, f = flags & F_JOINED, flags & F_FILTERED
    return JOINED_FILTERED if j and f else JOINED if j else FILTERED if f else TONE


def taken(kind, lane):
    if kind in (TONE, SAMPLE):
        return [lane]
    if kind == JOINED:
        return [lane, lane - 1]
    if kind == FILTERED:
        return [lane, lane + 2]
    return [1, 0, 3, 2]


def s8(b):
    return b - 256 if b >= 128 else b


class Image:
    def __init__(self, data):
        if len(data) != IMAGE_SIZE or data[:4] != b"TTPK" or data[4] != 1:
            raise ValueError("not a POKEY image (layout 1, 65536 bytes)")
        self.data = bytes(data)
        self.machine, self.multispeed, self.speed = data[5], data[6], data[7]
        self.orders, self.loop = struct.unpack_from("<HH", data, 8)
        self.instrument_count = data[12]
        self.audctl = data[13]
        self.data_size, self.sample_size = struct.unpack_from("<HH", data, 14)
        self.sample_count = data[18]
        self.rows = [(data[OFF_ROWS + o] or 256) for o in range(self.orders)]
        self.patterns = [[self.u16(OFF_ORDERS + 2 * (4 * o + c)) for c in range(4)] for o in range(self.orders)]
        self._decoded = {}
        self.start_speed, self.start_audctl, self.start_two_tone = self._start_states()

    def u16(self, at):
        return self.data[at] | self.data[at + 1] << 8

    def instrument(self, slot):
        if not 1 <= slot <= self.instrument_count:
            return None
        at = OFF_INSTRUMENTS + 32 * (slot - 1)
        rec = self.data[at:at + 32]
        if not rec[0] & F_DEFINED:
            return None
        return {"flags": rec[0], "offset": s8(rec[1]), "partner": rec[2], "sample": rec[3], "root": rec[4],
                "rate": self.u16(at + 6), "loop": self.u16(at + 8),
                "env": [self.u16(at + 10 + 2 * k) for k in range(4)]}

    def envelope(self, offset):
        """(length, loop, release, absolute offset of the values) or None."""
        if offset == ENV_NONE:
            return None
        at = OFF_DATA + offset
        return self.u16(at), self.u16(at + 2), self.u16(at + 4), at + 6

    def sample(self, k):
        if k >= self.sample_count:
            return None
        at = OFF_SAMPLES + 4 * k
        return self.u16(at), self.u16(at + 2)

    def sample_level(self, offset, i):
        b = self.data[OFF_SAMPLE_DATA + offset + (i >> 1)]
        return (b >> 4) if i & 1 else (b & 15)

    def pattern_rows(self, order, channel):
        offset, n = self.patterns[order][channel], self.rows[order]
        key = (offset, n)
        if key in self._decoded:
            return self._decoded[key]
        at, rows = OFF_DATA + offset, []
        while len(rows) < n:
            m = self.data[at]
            at += 1
            if m & 0x80:
                rows.extend([None] * ((m & 0x7F) + 1))
                continue
            note = inst = None
            if m & 1:
                note = self.data[at]
                at += 1
            if m & 2:
                inst = self.data[at]
                at += 1
            effects = []
            for _ in range((m >> 2) & 7):
                effects.append((self.data[at], self.data[at + 1]))
                at += 2
            rows.append((note, inst, effects))
        rows = rows[:n]
        self._decoded[key] = rows
        return rows

    def _start_states(self):
        """Per order: speed, shared AUDCTL bits and two-tone state sequential straight play starts it with."""
        speeds, audctls, tones = [], [], []
        s, a, w = self.speed, self.audctl, 0
        for o in range(self.orders):
            speeds.append(s)
            audctls.append(a)
            tones.append(w)
            for r in range(self.rows[o]):
                for c in range(4):
                    row = self.pattern_rows(o, c)[r]
                    for code, param in (row[2] if row else []):
                        if code == FX["F"]:
                            s = param
                        elif code == FX["K"]:
                            a = (a & ~0x01) | (param & 1)
                        elif code == FX["N"]:
                            a = (a & ~0x80) | (0x80 if param & 1 else 0)
                        elif code == FX["W"]:
                            w = param
        return speeds, audctls, tones


# -- pitch to counter --

def period8(n, fast, unit):
    return n + 4 if fast else unit * (n + 1)


def period16(n, fast, unit):
    return n + 7 if fast else unit * (n + 1)


def nearest_n(pt, fast, unit, offset, nmax):
    x = pt - offset if fast else pt / unit - 1
    return max(0, min(nmax, math.floor(x + 0.5)))


def choose(cands, period, pt, modulus):
    """N minimising |P(N) - Pt| among candidates with P(N) coprime to M, else among all; ties: smaller N."""
    best_any = best_co = None
    for n in cands:
        p = period(n)
        e = abs(p - pt)
        if best_any is None or e < best_any[0]:
            best_any = (e, n)
        if math.gcd(p, modulus) == 1 and (best_co is None or e < best_co[0]):
            best_co = (e, n)
    return (best_co or best_any)[1]


def frequency(pitch):
    return 440.0 * 2.0 ** ((pitch - 3648) / 768.0)


class Pitch:
    """The specification's pitch-to-counter search for one machine clock (memoised: same inputs, same N)."""

    def __init__(self, clock):
        self.clock = clock
        self.memo = {}

    def tone(self, pitch, dist, wide, fast, unit):
        key = ("t", pitch, dist, wide, fast, unit)
        if key not in self.memo:
            d, m = DIVISORS[dist]
            pt = self.clock / (frequency(pitch) * d)
            self.memo[key] = self._search(pt, m, wide, fast, unit, 0)
        return self.memo[key]

    def sample(self, pitch, rate, root, fast, unit):
        key = ("s", pitch, rate, root, fast, unit)
        if key not in self.memo:
            r = rate * 2.0 ** ((pitch - 64 * root) / 768.0)
            self.memo[key] = self._search(self.clock / r, 1, False, fast, unit, PCM_MIN_PERIOD)
        return self.memo[key]

    def _search(self, pt, modulus, wide, fast, unit, min_period):
        if wide:
            n0 = nearest_n(pt, fast, unit, 7, 65535)
            cands = range(max(0, n0 - 24), min(65535, n0 + 24) + 1)
            return choose(cands, lambda n: period16(n, fast, unit), pt, modulus)
        cands = [n for n in range(256) if period8(n, fast, unit) >= min_period]
        return choose(cands, lambda n: period8(n, fast, unit), pt, modulus)

    def difference(self, pitch, d, wide, fast, unit):
        """N minimising |C/P(N) - C/P(N + d) - f| (the high-pass "saw")."""
        key = ("d", pitch, d, wide, fast, unit)
        if key in self.memo:
            return self.memo[key]
        c, f = self.clock, frequency(pitch)
        if wide:
            per = lambda n: period16(n, fast, unit)  # noqa: E731
            delta = d if fast else d * unit
            x = (math.sqrt(delta * delta + 4 * c * delta / f) - delta) / 2
            n0 = nearest_n(x, fast, unit, 7, 65535 - d)
            cands = range(max(0, n0 - 24), min(65535 - d, n0 + 24) + 1)
        else:
            per = lambda n: period8(n, fast, unit)  # noqa: E731
            cands = range(0, 256 - d)
        best = None
        for n in cands:
            e = abs(c / per(n) - c / per(n + d) - f)
            if best is None or e < best[0]:
                best = (e, n)
        self.memo[key] = best[1]
        return best[1]


# -- player --

class Voice:
    def __init__(self, lane, slot, inst):
        self.lane = lane
        self.slot = slot
        self.flags = inst["flags"]
        self.kind = kind_of(inst["flags"])
        self.offset = inst["offset"]
        self.partner = inst["partner"]
        self.sample = inst["sample"]
        self.rate = inst["rate"]
        self.root = inst["root"]
        self.loop = inst["loop"]
        self.channels = taken(self.kind, lane)
        self.steps = [0, 0, 0, 0]  # volume, arpeggio, pitch, distortion
        self.released = False
        self.silenced = False
        self.acc = 0
        self.index = 0
        self.playing = self.kind == SAMPLE


class Lane:
    def __init__(self):
        self.row = None
        self.delay = None
        self.cut = None
        self.slot = 0
        self.volume = 120
        self.volume_slide = 0
        self.arp = 0
        self.arp_step = 0
        self.slide_kind = None
        self.slide_speed = 0
        self.slide = 0
        self.base = self.target = 0
        self.vib = (0, 0)
        self.vib_phase = 0
        self.fine = 0
        self.dist = None   # Vxx override
        self.filter = None  # Hxx override
        self.voice = None


class Player:
    def __init__(self, image):
        self.img = image
        self.pitch = Pitch(CLOCKS[image.machine])
        self.reset()

    def reset(self):
        img = self.img
        self.regs = [0] * 16
        self.out = []
        self.lanes = [Lane() for _ in range(4)]
        self.holder = [None] * 4
        self.order = self.row = self.tick_in_row = 0
        self.speed = img.speed
        self.shared = img.audctl
        self.two_tone = 0
        self.next_pos = None
        self.restart = self.stimer = False
        for reg, val in [(0xF, 0x00), (0x8, img.audctl)] + [(r, 0) for r in range(8)] + [(0xE, 0), (0xF, 0x03)]:
            self.write(reg, val, force=True)
        self.batch = self.out

    def write(self, reg, val, force=False):
        if force or self.regs[reg] != val:
            self.regs[reg] = val
            self.out.append((reg, val))

    def position(self):
        return self.order, self.row, self.tick_in_row

    # -- voices --
    def end(self, voice):
        for ch in voice.channels:
            if self.holder[ch] == voice.lane:
                self.holder[ch] = None
        self.lanes[voice.lane].voice = None

    def key_on(self, lane_index, note):
        lane = self.lanes[lane_index]
        inst = self.img.instrument(lane.slot)
        if inst is None:
            return
        kind = kind_of(inst["flags"])
        if lane_index not in LANES[kind] or (inst["flags"] & F_FAST and lane_index not in FAST_LANES[kind]):
            return
        if lane.voice is not None:
            self.end(lane.voice)
        voice = Voice(lane_index, lane.slot, inst)
        for ch in voice.channels:
            h = self.holder[ch]
            if h is not None and self.lanes[h].voice is not None:
                self.end(self.lanes[h].voice)
        for ch in voice.channels:
            self.holder[ch] = lane_index
        lane.voice = voice
        lane.base = lane.target = 64 * note
        lane.slide = 0
        lane.arp_step = 0
        lane.vib_phase = 0

    def release(self, lane_index):
        voice = self.lanes[lane_index].voice
        if voice is None or voice.released:
            return
        voice.released = True
        inst = self.img.instrument(voice.slot)
        envs = inst["env"] if inst else [ENV_NONE] * 4
        for k in range(4):
            e = self.img.envelope(envs[k])
            if e is not None and e[2] != ENV_NONE:
                voice.steps[k] = e[2]
        if voice.kind == SAMPLE:
            voice.playing = False
        else:
            vol = self.img.envelope(envs[0])
            if vol is None or vol[2] == ENV_NONE:
                voice.silenced = True

    # -- envelopes --
    def env_value(self, offset, step, default, signed):
        e = self.img.envelope(offset)
        if e is None:
            return default
        length, _, _, at = e
        b = self.img.data[at + min(step, length - 1)]
        return s8(b) if signed else b

    def env_advance(self, offset, step, released):
        e = self.img.envelope(offset)
        if e is None:
            return step
        length, loop, release, _ = e
        s = step + 1
        if not released and release != ENV_NONE and s == release:
            return loop if loop != ENV_NONE else release - 1
        if s >= length:
            return loop if loop != ENV_NONE and release == ENV_NONE else length - 1
        return s

    # -- rows --
    def run_row(self, lane_index):
        lane = self.lanes[lane_index]
        note, inst, effects = lane.row if lane.row else (None, None, [])
        playing_note = note is not None and note < 108
        if playing_note:
            lane.arp = 0
            lane.fine = 0
            lane.dist = None
            lane.filter = None
        if inst:
            lane.slot = inst
        for code, param in effects:
            if code == FX["0"]:
                lane.arp, lane.arp_step = param, 0
            elif code in (FX["1"], FX["2"], FX["3"]):
                lane.slide_kind = {FX["1"]: "up", FX["2"]: "down", FX["3"]: "porta"}[code] if param else None
                lane.slide_speed = param
            elif code == FX["4"]:
                lane.vib = (param >> 4, param & 15)
            elif code == FX["A"]:
                lane.volume_slide = param
            elif code == FX["C"]:
                lane.volume = param * 8
            elif code == FX["H"]:
                lane.filter = s8(param)
            elif code == FX["P"]:
                lane.fine = param - 0x80
            elif code == FX["S"]:
                lane.cut = param
            elif code == FX["V"]:
                lane.dist = param
        if note == NOTE_OFF:
            self.release(lane_index)
        elif playing_note:
            v = lane.voice
            held = v is not None and not v.released and (v.kind != SAMPLE or v.playing)
            if lane.slide_kind == "porta" and held:
                lane.target = 64 * note
            else:
                self.key_on(lane_index, note)

    def begin_row(self):
        img = self.img
        for ch in range(4):
            self.lanes[ch].row = img.pattern_rows(self.order, ch)[self.row]
        jump_order = jump_row = None
        for ch in range(4):
            row = self.lanes[ch].row
            for code, param in (row[2] if row else []):
                if code == FX["F"]:
                    self.speed = param
                elif code == FX["B"]:
                    jump_order = param
                elif code == FX["D"]:
                    jump_row = param
                elif code == FX["K"]:
                    self.shared = (self.shared & ~0x01) | (param & 1)
                elif code == FX["N"]:
                    self.shared = (self.shared & ~0x80) | (0x80 if param & 1 else 0)
                elif code == FX["W"]:
                    self.two_tone = param
                elif code == FX["R"]:
                    self.restart = True
                elif code == FX["T"]:
                    self.stimer = True
        if jump_order is None and jump_row is None:
            self.next_pos = None
        else:
            if jump_order is None:
                jump_order = self.order + 1 if self.order + 1 < img.orders else img.loop
            self.next_pos = (jump_order, jump_row or 0)
        for ch in range(4):
            lane = self.lanes[ch]
            lane.cut = None
            lane.delay = None
            g = 0
            for code, param in (lane.row[2] if lane.row else []):
                if code == FX["G"]:
                    g = param
            if g:
                lane.delay = g
            else:
                self.run_row(ch)

    # -- per tick update --
    def update(self, lane_index, audf, audc):
        """Updates the lane's voice; fills the registers of its channels; returns its AUDCTL bits."""
        lane = self.lanes[lane_index]
        v = lane.voice
        inst = self.img.instrument(v.slot)
        envs = inst["env"] if inst else [ENV_NONE] * 4
        vol_env = self.env_value(envs[0], v.steps[0], 15, False)
        arp_env = self.env_value(envs[1], v.steps[1], 0, True)
        pitch_env = self.env_value(envs[2], v.steps[2], 0, True)
        dist_env = self.env_value(envs[3], v.steps[3], DIST_PURE, False)
        if v.flags & F_RELATIVE:
            v.acc = max(-RELATIVE_LIMIT, min(RELATIVE_LIMIT, v.acc + pitch_env))
            pitch_term = v.acc
        else:
            pitch_term = pitch_env
        arp_fx = 0
        if lane.arp and lane.arp_step:
            arp_fx = (lane.arp >> 4) if lane.arp_step == 1 else (lane.arp & 15)
        vib = (SINE[lane.vib_phase] * lane.vib[1]) >> 4 if lane.vib[1] else 0
        pitch = max(0, min(PITCH_MAX, lane.base + 64 * (arp_env + arp_fx) + lane.fine + lane.slide + vib
                           + pitch_term * 4))
        vol = lane.volume >> 3
        fast = bool(v.flags & F_FAST)
        unit = 114 if self.shared & 0x01 else 28
        own = v.channels[0]
        bits = 0
        if v.kind == SAMPLE:
            n = self.pitch.sample(pitch, v.rate, v.root, fast, unit)
            audf[own] = n
            smp = self.img.sample(v.sample)
            if v.playing and (smp is None or v.index >= smp[1]):  # the sample shrank under a live edit
                v.playing = False
            audc[own] = (0x10 | self.sample_scaled(v, vol)) if v.playing else 0
            bits = 0x40 if fast else 0
        else:
            level = 0 if v.silenced else (vol_env * vol + 14) // 15
            dist = lane.dist if lane.dist is not None else dist_env
            audc[own] = (0x10 if dist == DIST_VOLUME_ONLY else dist << 5) | level
            wide = v.kind in (JOINED, JOINED_FILTERED)
            maximum = 65535 if wide else 255
            if v.kind in (FILTERED, JOINED_FILTERED) and v.flags & F_DIFFERENCE:
                d = lane.filter if lane.filter is not None else v.offset
                d = max(1, min(127, d))
                n = self.pitch.difference(pitch, d, wide, fast, unit)
                n2 = n + d
            else:
                n = self.pitch.tone(pitch, dist, wide, fast, unit)
                off = lane.filter if lane.filter is not None else v.offset
                n2 = max(0, min(maximum, n + off))
            if v.kind == TONE:
                audf[own] = n
                bits = (0x40 if own == 0 else 0x20) if fast else 0
            elif v.kind == JOINED:
                low = own - 1
                audf[low], audf[own] = n & 0xFF, n >> 8
                audc[low] = v.partner
                bits = (0x10 | (0x40 if fast else 0)) if own == 1 else (0x08 | (0x20 if fast else 0))
            elif v.kind == FILTERED:
                clock = own + 2
                audf[own], audf[clock] = n, n2
                audc[clock] = v.partner
                bits = (0x04 | (0x60 if fast else 0)) if own == 0 else 0x02
            else:
                audf[0], audf[1], audf[2], audf[3] = n & 0xFF, n >> 8, n2 & 0xFF, n2 >> 8
                audc[0] = audc[2] = audc[3] = v.partner
                bits = 0x10 | 0x08 | 0x02 | (0x60 if fast else 0)
        # effects advance
        if lane.arp:
            lane.arp_step = (lane.arp_step + 1) % 3
        if lane.slide_kind == "up":
            lane.slide = min(SLIDE_LIMIT, lane.slide + lane.slide_speed)
        elif lane.slide_kind == "down":
            lane.slide = max(-SLIDE_LIMIT, lane.slide - lane.slide_speed)
        elif lane.slide_kind == "porta":
            if lane.base < lane.target:
                lane.base = min(lane.target, lane.base + lane.slide_speed)
            elif lane.base > lane.target:
                lane.base = max(lane.target, lane.base - lane.slide_speed)
        if lane.vib[0] and lane.vib[1]:
            lane.vib_phase = (lane.vib_phase + lane.vib[0]) % 64
        if lane.volume_slide:
            lane.volume = max(0, min(120, lane.volume + (lane.volume_slide >> 4) - (lane.volume_slide & 15)))
        # envelopes step
        if not v.silenced:
            for k in range(4):
                v.steps[k] = self.env_advance(envs[k], v.steps[k], v.released)
        return bits

    def sample_scaled(self, v, vol):
        off, _ = self.img.sample(v.sample)
        return (self.img.sample_level(off, v.index) * vol + 14) // 15

    def irqen(self):
        bits = 0
        for lane in self.lanes:
            v = lane.voice
            if v is not None and v.kind == SAMPLE and v.playing:
                bits |= TIMER_BIT[v.lane]
        return bits

    def tick(self):
        self.out = []
        self.restart = self.stimer = False
        if self.tick_in_row == 0:
            self.begin_row()
        else:
            for ch in range(4):
                lane = self.lanes[ch]
                if lane.delay is not None and lane.delay == self.tick_in_row:
                    lane.delay = None
                    self.run_row(ch)
        for ch in range(4):
            if self.lanes[ch].cut == self.tick_in_row:
                self.release(ch)
        audf = [self.regs[2 * c] for c in range(4)]
        audc = [0] * 4
        bits = self.shared
        for ch in range(4):
            if self.lanes[ch].voice is not None:
                bits |= self.update(ch, audf, audc)
        for c in range(4):  # channels no voice holds are silent
            if self.holder[c] is None:
                audc[c] = 0
        skctl = 0x03 | (0x08 if self.two_tone else 0) | (0x80 if self.two_tone == 2 else 0)
        if self.restart:
            self.write(0xF, skctl & ~0x03, force=True)
            self.write(0xF, skctl, force=True)
        else:
            self.write(0xF, skctl)
        self.write(0x8, bits)
        for c in range(4):
            self.write(2 * c, audf[c])
            self.write(2 * c + 1, audc[c])
        self.write(0xE, self.irqen())
        if self.stimer:
            self.write(0x9, 0x00, force=True)
        # advance the position
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
                self.speed = self.img.start_speed[self.order]
                self.shared = self.img.start_audctl[self.order]
                self.two_tone = self.img.start_two_tone[self.order]
        return self.out

    def irq(self, timers):
        """The timer interrupt routine: the next level of each fired timer's playing sample."""
        self.out = []
        for bit, ch in ((0x01, 0), (0x02, 1), (0x04, 3)):
            if not timers & bit:
                continue
            h = self.holder[ch]
            v = self.lanes[h].voice if h is not None else None
            if v is None or v.kind != SAMPLE or not v.playing or v.channels[0] != ch:
                continue
            smp = self.img.sample(v.sample)
            n = v.index + 1
            if smp is not None and n >= smp[1]:
                n = v.loop if v.loop != ENV_NONE and v.loop < smp[1] else None
            if smp is None or n is None:
                v.playing = False
                self.write(2 * ch + 1, 0)
                self.write(0xE, self.irqen())
                continue
            v.index = n
            self.write(2 * ch + 1, 0x10 | self.sample_scaled(v, self.lanes[h].volume >> 3))
        return self.out


# -- the machine: ticks, the chip's timers, interrupts --

class Machine:
    def __init__(self, image):
        self.img = image
        self.player = Player(image)
        self.chip = chipref.Chip()
        self.frame = chipref.FRAME_CYCLES[image.machine]
        self.apply(self.player.batch)

    def tick_cycle(self, k):
        return k * self.frame // self.img.multispeed

    def apply(self, writes):
        for reg, val in writes:
            self.chip.write(reg, val)

    def run_tick(self, k, on_batch, on_irq):
        """Tick k: its batch at its cycle (on_batch(cycle, position, writes)), then the chip to the next tick's
        cycle, each interrupt's writes at its cycle (on_irq(cycle, timers, writes))."""
        cycle = self.tick_cycle(k)
        pos = self.player.position()
        writes = self.player.tick()
        self.apply(writes)
        on_batch(cycle, pos, writes)
        end = self.tick_cycle(k + 1)
        while self.chip.run(end):
            timers = self.chip.take_irq()
            iw = self.player.irq(timers)
            self.apply(iw)
            on_irq(self.chip.now, timers, iw)


def fmt_writes(writes):
    return "".join(" %X=%02X" % w for w in writes)


def trace(image, ticks):
    """The trace lines for `ticks` ticks."""
    m = Machine(image)
    lines = []
    for k in range(ticks):
        m.run_tick(k, lambda c, pos, w: lines.append("T %d %d %d %d %d:%s" % ((k,) + pos + (c, fmt_writes(w)))),
                   lambda c, t, w: lines.append("I %d %X:%s" % (c, t, fmt_writes(w))))
    return lines


def vgm_samples(cycle, machine):
    """44.1 kHz samples elapsed at machine cycle `cycle` (floor of c * 44100 / C, exact)."""
    return cycle * 77 // 3125 if machine == 0 else cycle * 88200 // 3546895


def vgm(image, max_ticks):
    """VGM 1.71 bytes (no GD3) and {ticks, samples, looped, loop_offset, loop_samples, end_cycle, loop_cycle}."""
    m = Machine(image)
    body = bytearray()
    state = {"samples": 0}

    def wait_to(cycle):
        target = vgm_samples(cycle, image.machine)
        n = target - state["samples"]
        state["samples"] = target
        while n > 0:
            if n == 735:
                body.append(0x62)
                n = 0
            elif n == 882:
                body.append(0x63)
                n = 0
            elif n <= 16:
                body.append(0x70 | (n - 1))
                n = 0
            else:
                k = min(n, 65535)
                body.extend((0x61, k & 0xFF, k >> 8))
                n -= k

    def emit(cycle, writes):
        for reg, val in writes:
            if reg == 0xE:
                continue
            wait_to(cycle)
            body.extend((0xBB, reg, val))

    emit(0, m.player.batch)
    loop_at = loop_samples = loop_cycle = None
    looped = False
    k = 0
    while k < max_ticks:
        cycle = m.tick_cycle(k)
        if m.player.position() == (image.loop, 0, 0):
            if loop_at is not None:
                looped = True
                break
            wait_to(cycle)
            loop_at, loop_samples, loop_cycle = 0x100 + len(body), state["samples"], cycle
            for reg in (0xF, 0x8, 0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7):
                body.extend((0xBB, reg, m.player.regs[reg]))
        m.run_tick(k, lambda c, pos, w: emit(c, w), lambda c, t, w: emit(c, w))
        k += 1
    end = m.tick_cycle(k)
    wait_to(end)
    body.append(0x66)
    total = state["samples"]
    header = bytearray(0x100)
    header[0:4] = b"Vgm "
    struct.pack_into("<I", header, 0x04, 0x100 + len(body) - 4)
    struct.pack_into("<I", header, 0x08, 0x171)
    struct.pack_into("<I", header, 0x18, total)
    if looped:
        struct.pack_into("<I", header, 0x1C, loop_at - 0x1C)
        struct.pack_into("<I", header, 0x20, total - loop_samples)
    struct.pack_into("<I", header, 0x24, VGM_RATES[image.machine])
    struct.pack_into("<I", header, 0x34, 0x100 - 0x34)
    struct.pack_into("<I", header, 0xB0, VGM_CLOCKS[image.machine])
    info = {"ticks": k, "samples": total, "looped": looped, "loop_offset": loop_at if looped else 0,
            "loop_samples": total - loop_samples if looped else 0, "end_cycle": end,
            "loop_cycle": loop_cycle if looped else None}
    return bytes(header + body), info


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("image")
    ap.add_argument("--trace")
    ap.add_argument("--ticks", type=int, default=1500)
    ap.add_argument("--vgm")
    ap.add_argument("--max-ticks", type=int, default=36000)
    args = ap.parse_args(argv)
    with open(args.image, "rb") as f:
        image = Image(f.read())
    if args.trace:
        with open(args.trace, "w") as f:
            for line in trace(image, args.ticks):
                f.write(line + "\n")
    if args.vgm:
        data, info = vgm(image, args.max_ticks)
        with open(args.vgm, "wb") as f:
            f.write(data)
        print("%s: %d bytes, %d ticks, %d samples%s" % (args.vgm, len(data), info["ticks"], info["samples"],
              ", loop %d samples" % info["loop_samples"] if info["looped"] else ", no loop"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
