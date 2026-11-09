#!/usr/bin/env python3
"""Independent Python reference of Thor Tracker's Virtual Boy VSU system ("vb-vsu"), for developer evidence only.

Written from docs/AUDIO_ENGINE.md ("Virtual Boy VSU") and, for the chip, from the Virtual Boy Sacred Tech Scroll's VSU
chapter (Guy Perfect, revision 2024-10-20) with the spec's amendments -- not translated from vsu_chip.c: the chip here
works in absolute clock times (a channel's effects ticks are at its start time + 4800 j, wave steps are counted between
events, the noise register's state is looked up in its precomputed sequence from the step count), where the C core
counts down per-channel dividers and steps the register. Both must give identical register writes per tick, identical
10-bit digital output and identical held state (tools/audio/vsu_reference_check.py compares them).

    python3 tools/audio/vsu/vsu_ref.py --check-tables
recomputes VSU_PERIOD_Q with 60-digit decimal arithmetic and the vibrato sine, compares them with the literals in
app/src/main/cpp/vsu/vsu_player.c (and the sine with generated/opl3_tables.h's O3_SINE), checks the noise register's
sequence lengths per tap against the Tech Scroll's table and prints A-4's frequency value and pitch.
Exit status 1 on any mismatch."""
import decimal
import math
import os
import re
import struct
import sys

CLOCK = 5000000
TICK_CLOCKS = 100000        # one 50 Hz VIP frame
SAMPLE_CLOCKS = 120         # the mixer's output rate, 41666.7 Hz
EFFECT_CLOCKS = 4800        # 0.96 ms
VGM_TICK_SAMPLES = 882
VGM_HEADER = 0x100
MAX_WRITES = 256
PITCH_MAX = 8 * 768 - 1
WAVE_PITCH_MIN = 27 * 64    # D#2
LANES = 6
WAVE5, NOISE = 4, 5
NOTE_OFF = 0xFE
REGISTERS = 0x161
SSTOP = 0x160
INT, LRV, FQL, FQH, EV0, EV1, RAM, SWP = range(8)

# Tech Scroll, "Noise": tap 0..7 = bit 14, 10, 13, 4, 8, 6, 9, 11; sequence lengths 32767, 1953, 254, 217, 73, 63, 42, 28.
TAP_BITS = (14, 10, 13, 4, 8, 6, 9, 11)
TECH_SCROLL_LENGTHS = (32767, 1953, 254, 217, 73, 63, 42, 28)

FX_ARP, FX_UP, FX_DOWN, FX_PORTA, FX_VIB, FX_STEREO = 0, 1, 2, 3, 4, 8
FX_JUMP, FX_LEVEL, FX_BREAK, FX_SPEED, FX_DELAY = 11, 12, 13, 15, 16
FX_LENGTH, FX_SWEEPMOD, FX_TAP, FX_FINE, FX_CUT, FX_WAVE = 21, 22, 23, 25, 28, 32

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))


def reg(c, r):
    return 0x100 + 0x10 * c + r


def s8(v):
    return v - 256 if v >= 128 else v


# ---- tables ----------------------------------------------------------------------------------------------------

def period_q_table():
    """VSU_PERIOD_Q[f] = round(65536 * 156250 / 440 * 2^((3648 - f) / 768)), 60 significant digits."""
    D = decimal.Decimal
    ctx = decimal.Context(prec=60)
    base = ctx.divide(ctx.multiply(D(65536), D(156250)), D(440))
    out = []
    for f in range(768):
        v = ctx.multiply(base, ctx.power(D(2), ctx.divide(D(3648 - f), D(768))))
        out.append(int(v.quantize(D(1), rounding=decimal.ROUND_HALF_UP)))
    return out


def sine_table():
    return [math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64)]


_Q = None
SINE = sine_table()


def Q():
    global _Q
    if _Q is None:
        _Q = period_q_table()
    return _Q


def frequency(lane, pitch):
    """The 11-bit frequency value of a pitch (1/64 semitone, clamped to the lane's range)."""
    pitch = max(0 if lane == NOISE else WAVE_PITCH_MIN, min(PITCH_MAX, pitch))
    o, f = divmod(pitch, 768)
    q = Q()[f]
    if lane == NOISE:
        unit = 1 << (16 + o)
        return 2048 - (q + 5 * unit) // (10 * unit)
    return 2048 - ((q + (1 << (15 + o))) >> (16 + o))


# ---- the noise register (Tech Scroll "Noise Procedure") --------------------------------------------------------

_NOISE = {}


def noise_sequence(tap):
    """States from 0 until the first repeat: (states, mu = index the cycle starts at, lam = cycle length)."""
    if tap not in _NOISE:
        bit = TAP_BITS[tap]
        seen, seq, s = {}, [], 0
        while s not in seen:
            seen[s] = len(seq)
            seq.append(s)
            fb = ((s >> 7) ^ (s >> bit) ^ 1) & 1   # bit 7 XOR the tap bit, inverted
            s = ((s << 1) & 0x7FFF) | fb           # shifted left, the new bit enters at bit 0
        _NOISE[tap] = (seq, seen[s], len(seq) - seen[s])
    return _NOISE[tap]


def noise_state(tap, n):
    seq, mu, lam = noise_sequence(tap)
    return seq[n] if n < len(seq) else seq[mu + (n - mu) % lam]


# ---- the chip ---------------------------------------------------------------------------------------------------

class Channel:
    def __init__(self):
        self.intv = self.lrv = self.ev0 = self.ev1 = self.ram = self.freq = self.cur = 0
        self.active = False
        self.env = 0
        self.env_left = 0     # envelope units left in the envelope frame
        self.length = 0       # length units left (counted only with Auto)
        self.start = 0        # clock of the SxINT write that started it
        self.fx = 0           # effects ticks it has had since then
        self.next_step = 0    # clock of its next wave/noise step
        self.pos = 0          # wave position
        self.steps = 0        # noise steps since the register restarted


class Chip:
    """The VSU as the Tech Scroll describes it, plus the spec's amendments 1-8. Absolute clock times."""

    def __init__(self):
        self.now = 0
        self.ch = [Channel() for _ in range(6)]
        self.wave = [[0] * 32 for _ in range(5)]
        self.mod = [0] * 32
        self.swp = 0
        self.mod_pos = 0
        self.mod_ticks = 0
        self.mod_len = 0

    @staticmethod
    def frame_len(swp):
        return ((swp >> 4) & 7) * (8 if swp & 0x80 else 1)

    @staticmethod
    def sweep_next(cur, swp):
        d = cur >> (swp & 7)
        return cur + d if swp & 8 else cur - d

    @staticmethod
    def period(c, cur):
        return 10 * (2048 - cur) if c == 5 else 2048 - cur

    def write(self, r, val):
        """A write at the current clock (after that clock's events)."""
        if r < 0xA0:
            if not any(ch.active for ch in self.ch):          # wave RAM: only while every channel is off
                self.wave[r >> 5][r & 31] = val & 0x3F
            return
        if r < 0xC0:
            if not self.ch[4].active:                          # modulation RAM: only while channel 5 is off
                self.mod[r - 0xA0] = val
            return
        if r == SSTOP:
            if val & 1:
                for ch in self.ch:
                    ch.active = False
            return
        if r < 0x100 or r > 0x15F:
            return
        c, k = (r - 0x100) >> 4, r & 15
        ch = self.ch[c]
        if k == INT:
            ch.intv = val & 0xBF
            if not val & 0x80:
                ch.active = False
                return
            # Tech Scroll "Channel Control": the delay counter, wave position, envelope frame, modification frame and
            # position, and the noise register restart; the current frequency does not change.
            ch.active = True
            ch.start = self.now
            ch.fx = 0
            ch.next_step = self.now + self.period(c, ch.cur)
            ch.pos = 0
            ch.steps = 0
            ch.length = (val & 0x1F) + 1
            ch.env_left = (ch.ev0 & 7) + 1
            if c == 4:
                self.mod_pos = self.mod_ticks = 0
                self.mod_len = self.frame_len(self.swp)
                # a frame starts: an overflowing next sweep value stops channel 5 before it plays (amendment 5)
                if not ch.ev1 & 0x10 and self.sweep_next(ch.cur, self.swp) > 2047:
                    ch.active = False
        elif k == LRV:
            ch.lrv = val
        elif k == FQL:
            ch.freq = (ch.freq & 0x700) | val
            ch.cur = (ch.cur & 0x700) | val
        elif k == FQH:
            ch.freq = (ch.freq & 0xFF) | (val & 7) << 8
            ch.cur = (ch.cur & 0xFF) | (val & 7) << 8
        elif k == EV0:
            ch.ev0 = val
            ch.env = val >> 4
        elif k == EV1:
            ch.ev1 = val & (0x73 if c >= 4 else 0x03)
            if c == 5:
                ch.steps = 0
        elif k == RAM:
            if c < 5:
                ch.ram = val & 0x0F
        elif k == SWP:
            if c == 4:
                self.swp = val
                n = self.frame_len(val)
                if n > self.mod_ticks:   # amendment 7: else the running frame ends at its old interval
                    self.mod_len = n

    def run_to(self, t):
        for c in range(6):
            self._advance(c, t)
        self.now = t

    def _advance(self, c, t):
        ch = self.ch[c]
        while ch.active:
            next_fx = ch.start + EFFECT_CLOCKS * (ch.fx + 1)
            lim = min(next_fx, t)
            if ch.next_step <= lim:   # steps up to and including lim come before an effects tick at lim
                p = self.period(c, ch.cur)
                k = 1 + (lim - ch.next_step) // p
                ch.next_step += k * p
                if c == 5:
                    ch.steps += k
                else:
                    ch.pos = (ch.pos + k) & 31
            if next_fx > t:
                return
            ch.fx += 1
            self._effects_tick(c)

    def _effects_tick(self, c):
        ch = self.ch[c]
        j = ch.fx
        if j % 4 == 0:   # a 3.84 ms length unit
            if ch.intv & 0x20:
                ch.length -= 1
                if ch.length == 0:
                    ch.active = False
                    return
            if j % 16 == 0:   # a 15.36 ms envelope unit
                ch.env_left -= 1
                if ch.env_left == 0:
                    ch.env_left = (ch.ev0 & 7) + 1
                    if ch.ev1 & 1:   # Tech Scroll "Envelope Procedure"
                        grow = ch.ev0 & 8
                        if not grow and ch.env != 0:
                            ch.env -= 1
                        elif grow and ch.env != 15:
                            ch.env += 1
                        elif ch.ev1 & 2:
                            ch.env = ch.ev0 >> 4
        if c != 4:
            return
        modulation = ch.ev1 & 0x10
        if not modulation and self.sweep_next(ch.cur, self.swp) > 2047:   # amendment 5, any interval / Enb
            ch.active = False
            return
        if not self.mod_len:
            return
        self.mod_ticks += 1
        if self.mod_ticks < self.mod_len:
            return
        self.mod_ticks = 0
        if ch.ev1 & 0x40 and self.swp & 0x70:
            if not modulation:
                ch.cur = max(0, self.sweep_next(ch.cur, self.swp))
            else:   # amendment 6
                if self.mod_pos >= 32 and ch.ev1 & 0x20:
                    self.mod_pos = 0
                if self.mod_pos < 32:
                    ch.cur = (ch.freq + s8(self.mod[self.mod_pos])) & 0x7FF
                    self.mod_pos += 1
        self.mod_len = self.frame_len(self.swp)
        # the next frame starts now: its value is computed at once (amendment 5)
        if not modulation and self.sweep_next(ch.cur, self.swp) > 2047:
            ch.active = False

    def sample(self, mute=0):
        """Tech Scroll "Output Procedure" (amendment 8): the 10-bit left and right output now."""
        out = [0, 0]
        for c, ch in enumerate(self.ch):
            if not ch.active or mute >> c & 1:
                continue
            if c == 5:
                s = 63 if noise_state((ch.ev1 >> 4) & 7, ch.steps) & 1 else 0
            else:
                s = self.wave[ch.ram][ch.pos] if ch.ram <= 4 else 0
            for side, level in enumerate((ch.lrv >> 4, ch.lrv & 15)):
                a = level * ch.env
                if a:
                    a = (a >> 3) + 1
                out[side] += s * a
        return out[0] >> 4, out[1] >> 4

    def active_mask(self):
        return sum(1 << c for c, ch in enumerate(self.ch) if ch.active)

    def held(self):
        """The held state the VGM loop compares (vsu_held_equal)."""
        chans = tuple((ch.intv, ch.lrv, ch.ev0, ch.ev1, ch.ram, ch.freq, ch.active, ch.env, ch.env_left, ch.length, ch.cur)
                      for ch in self.ch)
        return chans, tuple(map(tuple, self.wave)), tuple(self.mod), self.swp, self.mod_pos, self.mod_ticks, self.mod_len


# ---- images -----------------------------------------------------------------------------------------------------

def u16(b, at):
    return b[at] | b[at + 1] << 8


class Image:
    """A loaded "vb-vsu" image (the C loader validated it; this only reads it)."""

    def __init__(self, data):
        b = bytes(data)
        if len(b) != 65536 or b[:4] != b"TTVB":
            raise ValueError("not a VSU image")
        self.b = b
        self.speed = b[5]
        self.orders = u16(b, 6)
        self.loop = u16(b, 8)
        self.instruments = b[10]
        self.rows = [b[64 + o] or 256 for o in range(self.orders)]
        self.waves = b[4416:4576]
        self.mod = b[4576:4608]
        self.patterns = [[self._decode(u16(b, 320 + 2 * (6 * o + c)), self.rows[o]) for c in range(6)]
                         for o in range(self.orders)]
        self.start_speed = []
        s = self.speed
        for o in range(self.orders):
            self.start_speed.append(s)
            for r in range(self.rows[o]):
                for c in range(6):
                    for code, param in self.patterns[o][c][r][2]:
                        if code == FX_SPEED:
                            s = param

    def _decode(self, offset, rows):
        """Rows (note or None, instrument, [(code, param)]) of a pattern."""
        b, at, out = self.b, 4608 + offset, []
        while len(out) < rows:
            m = b[at]
            at += 1
            if m & 0x80:
                out.extend([(None, 0, [])] * ((m & 0x7F) + 1))
                continue
            note = inst = None
            if m & 1:
                note = b[at]
                at += 1
            if m & 2:
                inst = b[at]
                at += 1
            fx = []
            for _ in range((m >> 2) & 3):
                fx.append((b[at], b[at + 1]))
                at += 2
            out.append((note, inst or 0, fx))
        return out[:rows]

    def instrument(self, slot):
        if 1 <= slot <= self.instruments:
            rec = self.b[3392 + 16 * (slot - 1):3392 + 16 * slot]
            if rec[0] & 0x80:
                return rec
        return None


# ---- the player (docs/AUDIO_ENGINE.md "Player", "Effects") -------------------------------------------------------

def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


class Lane:
    def __init__(self):
        self.row = (None, 0, [])
        self.pending = False
        self.delay = 0
        self.cut = -1
        self.instrument = 0
        self.active = False
        self.keyed = False
        self.base = self.target = self.fine = 0
        self.arp = self.arp_step = 0
        self.slide_mode = self.slide_speed = self.slide = 0
        self.vib_speed = self.vib_depth = self.vib_phase = 0
        self.sweep = self.sweep_end = self.sweep_step = 0
        self.stereo = 0xFF

    def state(self):
        return (self.instrument, self.active, self.keyed, self.base, self.target, self.fine, self.arp, self.arp_step,
                self.slide_mode, self.slide_speed, self.slide, self.vib_speed, self.vib_depth, self.vib_phase,
                self.sweep, self.sweep_end, self.sweep_step, self.stereo)


class Player:
    def __init__(self, image):
        self.img = image
        self.reset()

    # writes
    def emit(self, r, val):
        self.shadow[r] = val
        if len(self.writes) < MAX_WRITES:
            self.writes.append((r, val))

    def wr(self, r, val):
        if self.shadow[r] != val:
            self.emit(r, val)

    def reset(self):
        img = self.img
        self.order = self.row = self.tick_in_row = 0
        self.speed = img.speed
        self.jump, self.jump_order, self.jump_row = False, 0, 0
        self.audition = False
        self.lanes = [Lane() for _ in range(LANES)]
        self.shadow = [0] * REGISTERS
        self.writes = []
        self.emit(SSTOP, 1)
        for i in range(160):
            self.wr(i, img.waves[i])
        for i in range(32):
            self.wr(0xA0 + i, img.mod[i])

    def place(self, order):
        self.reset()
        self.order = order
        self.speed = self.img.start_speed[order]

    def loop_state(self):
        return (self.order, self.row, self.tick_in_row, self.speed, self.audition, tuple(self.shadow),
                tuple(l.state() for l in self.lanes))

    def write_frequency(self, c, pitch, keyon):
        f = frequency(c, pitch)
        lo, hi = reg(c, FQL), reg(c, FQH)
        if keyon or (c == WAVE5 and (self.shadow[lo] != f & 0xFF or self.shadow[hi] != f >> 8)):
            self.emit(lo, f & 0xFF)
            self.emit(hi, f >> 8)
        else:
            self.wr(lo, f & 0xFF)
            self.wr(hi, f >> 8)

    def key_off(self, c):
        l = self.lanes[c]
        if l.keyed:
            self.emit(reg(c, INT), 0)
            l.keyed = False

    def key_on(self, c, note, locks):
        l = self.lanes[c]
        rec = self.img.instrument(l.instrument)
        if rec is None:
            return False
        l.base = l.target = note * 64
        l.slide = l.arp_step = l.vib_phase = 0
        speed = rec[10]
        l.sweep = s8(rec[8]) * 64 if speed else 0
        l.sweep_end = s8(rec[9]) * 64 if speed else 0
        l.sweep_step = speed * 4
        flags = rec[0]
        self.wr(reg(c, LRV), l.stereo)                                                     # 1
        self.write_frequency(c, l.base + l.fine + l.sweep, True)                          # 2
        level = locks["C"] if locks["C"] >= 0 else rec[1]
        self.emit(reg(c, EV0), level << 4 | (8 if flags & 4 else 0) | rec[2])              # 3
        ev1 = (2 if flags & 2 else 0) | (1 if flags & 1 else 0)                            # 4
        if c == WAVE5:
            fm = rec[6]
            ev1 |= (0x40 if fm & 1 else 0) | (0x20 if fm & 4 else 0) | (0x10 if fm & 2 else 0)
        if c == NOISE:
            ev1 |= (locks["N"] if locks["N"] >= 0 else rec[5]) << 4
        self.wr(reg(c, EV1), ev1)
        if c != NOISE:                                                                     # 5
            self.wr(reg(c, RAM), locks["W"] if locks["W"] >= 0 else rec[4])
        if c == WAVE5:                                                                     # 6
            self.wr(reg(c, SWP), locks["M"] if locks["M"] >= 0 else rec[7])
        if locks["L"] >= 0:                                                                # 7
            auto, length = locks["L"] > 0, max(0, locks["L"] - 1)
        else:
            auto, length = bool(flags & 8), rec[3]
        self.emit(reg(c, INT), 0x80 | (0x20 | length if auto else 0))
        l.keyed = l.active = True
        return True

    def write_locks(self, c, locks):
        l = self.lanes[c]
        if locks["C"] >= 0:
            rec = self.img.instrument(l.instrument)
            low = ((8 if rec[0] & 4 else 0) | rec[2]) if rec is not None else 0
            self.emit(reg(c, EV0), locks["C"] << 4 | low)
        if locks["N"] >= 0:
            self.wr(reg(c, EV1), (self.shadow[reg(c, EV1)] & 0x0F) | locks["N"] << 4)
        if locks["W"] >= 0:
            self.wr(reg(c, RAM), locks["W"])
        if locks["M"] >= 0:
            self.wr(reg(c, SWP), locks["M"])

    def process_row(self, c):
        l = self.lanes[c]
        note, inst, fx = l.row
        has_note = note is not None and note < 96
        if has_note:
            l.arp = 0
            l.fine = 0
        if inst:
            l.instrument = inst
        locks = {"C": -1, "L": -1, "M": -1, "N": -1, "W": -1}
        for code, v in fx:
            x, y = v >> 4, v & 15
            if code == FX_ARP:
                l.arp, l.arp_step = v, 0
            elif code == FX_UP:
                l.slide_mode, l.slide_speed = (1 if v else 0), v
            elif code == FX_DOWN:
                l.slide_mode, l.slide_speed = (2 if v else 0), v
            elif code == FX_PORTA:
                l.slide_mode, l.slide_speed = (3 if v else 0), v
            elif code == FX_VIB:
                l.vib_speed, l.vib_depth = x, y
            elif code == FX_STEREO:
                l.stereo = v
                if l.active:
                    self.wr(reg(c, LRV), v)
            elif code == FX_LEVEL:
                locks["C"] = v
            elif code == FX_LENGTH:
                locks["L"] = v
            elif code == FX_SWEEPMOD:
                locks["M"] = v
            elif code == FX_TAP:
                locks["N"] = v
            elif code == FX_WAVE:
                locks["W"] = v - 1
            elif code == FX_FINE:
                l.fine = v - 0x80
            elif code == FX_CUT:
                l.cut = v
        keyed_on = False
        if has_note:
            if l.slide_mode == 3 and l.keyed:
                l.target = note * 64
            else:
                keyed_on = self.key_on(c, note, locks)
        if not keyed_on:
            self.write_locks(c, locks)
        if note == NOTE_OFF:
            self.key_off(c)

    def pitch(self, c):
        l = self.lanes[c]
        arp = 0
        if l.arp and l.arp_step:
            arp = l.arp >> 4 if l.arp_step == 1 else l.arp & 15
        vib = (SINE[l.vib_phase] * l.vib_depth) >> 4 if l.vib_depth else 0
        return clamp(l.base + arp * 64 + l.fine + l.slide + vib + l.sweep, 0 if c == NOISE else WAVE_PITCH_MIN, PITCH_MAX)

    def update(self, c):
        l = self.lanes[c]
        if not l.active:
            return
        self.write_frequency(c, self.pitch(c), False)
        if l.arp:
            l.arp_step = (l.arp_step + 1) % 3
        if l.slide_mode == 1:
            l.slide = clamp(l.slide + l.slide_speed, -6144, 6144)
        if l.slide_mode == 2:
            l.slide = clamp(l.slide - l.slide_speed, -6144, 6144)
        if l.slide_mode == 3:
            if l.base < l.target:
                l.base = clamp(l.base + l.slide_speed, 0, l.target)
            elif l.base > l.target:
                l.base = clamp(l.base - l.slide_speed, l.target, PITCH_MAX)
        if l.vib_depth and l.vib_speed:
            l.vib_phase = (l.vib_phase + l.vib_speed) & 63
        if l.sweep_step:
            if l.sweep < l.sweep_end:
                l.sweep = clamp(l.sweep + l.sweep_step, l.sweep, l.sweep_end)
            elif l.sweep > l.sweep_end:
                l.sweep = clamp(l.sweep - l.sweep_step, l.sweep_end, l.sweep)

    def row_start(self):
        img = self.img
        for c in range(LANES):
            self.lanes[c].row = img.patterns[self.order][c][self.row]
        for c in range(LANES):
            for code, param in self.lanes[c].row[2]:
                if code == FX_SPEED:
                    self.speed = param
        has_b = has_d = False
        b = d = 0
        for c in range(LANES):
            for code, param in self.lanes[c].row[2]:
                if code == FX_JUMP:
                    has_b, b = True, param
                if code == FX_BREAK:
                    has_d, d = True, param
        self.jump = has_b or has_d
        self.jump_order = b if has_b else (self.order + 1 if self.order + 1 < img.orders else img.loop)
        self.jump_row = d if has_d else 0
        for c in range(LANES):
            l = self.lanes[c]
            l.cut = -1
            l.pending = False
            g = 0
            for code, param in l.row[2]:
                if code == FX_DELAY:
                    g = param
            if g:
                l.pending, l.delay = True, g
            else:
                self.process_row(c)

    def advance(self):
        img = self.img
        if self.jump:
            self.order = self.jump_order
            self.row = self.jump_row if self.jump_row < img.rows[self.order] else img.rows[self.order] - 1
            self.jump = False
        else:
            self.row += 1
            if self.row >= img.rows[self.order]:
                self.row = 0
                self.order += 1
                if self.order >= img.orders:
                    self.order = img.loop
        if self.order == img.loop and self.row == 0:
            self.speed = img.start_speed[self.order]

    def tick(self):
        self.writes = []
        if not self.audition:
            if self.tick_in_row == 0:
                self.row_start()
            else:
                for c in range(LANES):
                    l = self.lanes[c]
                    if l.pending and l.delay == self.tick_in_row:
                        l.pending = False
                        self.process_row(c)
            for c in range(LANES):
                if self.lanes[c].cut == self.tick_in_row:
                    self.key_off(c)
        for c in range(LANES):
            self.update(c)
        if not self.audition:
            self.tick_in_row += 1
            if self.tick_in_row >= self.speed:
                self.tick_in_row = 0
                self.advance()

    def start_audition(self, lane, note, slot, fine):
        if not 0 <= lane < LANES or not -128 <= fine <= 127:
            return False
        if not (0 if lane == NOISE else 27) <= note < 96 or self.img.instrument(slot) is None:
            return False
        self.reset()
        self.audition = True
        self.lanes[lane].instrument = slot
        self.lanes[lane].fine = fine
        self.key_on(lane, note, {"C": -1, "L": -1, "M": -1, "N": -1, "W": -1})
        return True


# ---- what the host test writes -------------------------------------------------------------------------------------

def format_writes(writes):
    return "".join(" %03X=%02X" % w for w in writes)


def trace(image, ticks):
    """vsu_trace.txt: the reset batch, then per tick its position and writes."""
    p = Player(image)
    lines = ["R:" + format_writes(p.writes)]
    for k in range(ticks):
        pos = (p.order, p.row, p.tick_in_row)
        p.tick()
        lines.append("T %d %d %d %d:" % ((k,) + pos) + format_writes(p.writes))
    return lines


def output(image, ticks):
    """vsu_out.txt: (clock, left, right) at every change of the digital output over the first `ticks` ticks."""
    p, chip = Player(image), Chip()
    for r, v in p.writes:
        chip.write(r, v)
    out, last = [], (0, 0)
    for k in range(ticks):
        start = k * TICK_CLOCKS
        p.tick()
        for r, v in p.writes:
            chip.write(r, v)
        t = (start // SAMPLE_CLOCKS + 1) * SAMPLE_CLOCKS
        while t <= start + TICK_CLOCKS:
            chip.run_to(t)
            s = chip.sample()
            if s != last:
                out.append((t,) + s)
                last = s
            t += SAMPLE_CLOCKS
        chip.run_to(start + TICK_CLOCKS)
    return out


def vgm(image, max_ticks):
    """The VGM 1.71 file vb_vgm_render writes (no GD3) and (ticks, samples, loop_offset, loop_samples, loop_tick)."""
    p, chip = Player(image), Chip()
    out = bytearray(VGM_HEADER)

    def put_batch():
        for r, v in p.writes:
            chip.write(r, v)
            out.extend((0xC7, r >> 8, r & 0xFF, v))

    put_batch()
    t = 0
    previous = None
    looped = False
    loop_offset = loop_tick = arrival_offset = arrival_tick = 0
    while True:
        if p.order == image.loop and p.row == 0 and p.tick_in_row == 0:
            state = (p.loop_state(), chip.held())
            if previous is not None and state == previous:
                looped, loop_offset, loop_tick = True, arrival_offset, arrival_tick
                break
            previous, arrival_offset, arrival_tick = state, len(out), t
        if t >= max_ticks:
            break
        p.tick()
        put_batch()
        chip.run_to(chip.now + TICK_CLOCKS)
        out.append(0x63)
        t += 1
    out.append(0x66)
    samples = t * VGM_TICK_SAMPLES
    struct.pack_into("<4sII", out, 0, b"Vgm ", len(out) - 4, 0x171)
    struct.pack_into("<I", out, 0x18, samples)
    loop_samples = (t - loop_tick) * VGM_TICK_SAMPLES if looped else 0
    if looped:
        struct.pack_into("<II", out, 0x1C, loop_offset - 0x1C, loop_samples)
    struct.pack_into("<I", out, 0x24, 50)
    struct.pack_into("<I", out, 0x34, VGM_HEADER - 0x34)
    struct.pack_into("<I", out, 0xC4, CLOCK)
    return bytes(out), {"ticks": t, "samples": samples, "loop_offset": loop_offset if looped else 0,
                        "loop_samples": loop_samples, "loop_tick": loop_tick if looped else 0}


def chip_cases(text):
    """Replays vsu_chip_cases.txt: [(name, engine O lines, reference O lines)]."""
    results = []
    lines = [line.split() for line in text.splitlines() if line.strip()]
    i = 0
    while i < len(lines):
        f = lines[i]
        if f[0] != "CASE":
            i += 1
            continue
        name, clocks = f[1], int(f[2])
        writes, engine = [], []
        i += 1
        while lines[i][0] != "END":
            g = lines[i]
            if g[0] == "W":
                writes.append((int(g[1]), int(g[2], 16), int(g[3], 16)))
            elif g[0] == "O":
                engine.append(tuple(int(x) for x in g[1:5]))
            i += 1
        i += 1
        chip, ref, k, last = Chip(), [], 0, (0, 0, 0)
        for t in range(SAMPLE_CLOCKS, clocks + 1, SAMPLE_CLOCKS):
            while k < len(writes) and writes[k][0] < t:
                chip.run_to(writes[k][0])
                chip.write(writes[k][1], writes[k][2])
                k += 1
            chip.run_to(t)
            s = chip.sample() + (chip.active_mask(),)
            if s != last:
                ref.append((t,) + s)
            last = s
        results.append((name, engine, ref))
    return results


# ---- --check-tables ------------------------------------------------------------------------------------------------

def c_array(path, name):
    with open(path) as f:
        text = f.read()
    m = re.search(r"%s\[\d+\]\s*=\s*\{([^}]*)\}" % re.escape(name), text)
    return [int(v.rstrip("uU")) for v in re.findall(r"-?\d+u?", m.group(1))] if m else None


def check_tables():
    ok = True
    q = Q()
    lit = c_array(os.path.join(ROOT, "app/src/main/cpp/vsu/vsu_player.c"), "VSU_PERIOD_Q")
    same = lit == q
    ok &= same
    print("%s VSU_PERIOD_Q: 768 values, q[0] = %d, q[767] = %d, the C literals %s" %
          ("PASS" if same else "FAIL", q[0], q[767], "agree" if same else "DIFFER"))
    if lit and not same:
        bad = [f for f in range(min(len(lit), 768)) if lit[f] != q[f]]
        print("  first difference at f = %d: C %d, decimal %d" % (bad[0], lit[bad[0]], q[bad[0]]) if bad else
              "  C table has %d values" % len(lit))
    sine_c = c_array(os.path.join(ROOT, "app/src/main/cpp/vsu/vsu_player.c"), "SINE")
    sine_o3 = c_array(os.path.join(ROOT, "app/src/main/cpp/generated/opl3_tables.h"), "O3_SINE")
    same = sine_c == SINE and sine_o3 == SINE
    ok &= same
    print("%s SINE = floor(127 sin(2 pi i / 64) + 0.5) = vsu_player.c's = O3_SINE" % ("PASS" if same else "FAIL"))
    lengths = tuple(noise_sequence(t)[2] for t in range(8))
    same = lengths == TECH_SCROLL_LENGTHS
    ok &= same
    print("%s noise sequence lengths from 0 per tap %s (Tech Scroll %s)" % ("PASS" if same else "FAIL", lengths,
                                                                             TECH_SCROLL_LENGTHS))
    f = frequency(0, 57 * 64)
    hz = CLOCK / (32 * (2048 - f))
    same = f == 1693
    ok &= same
    print("%s A-4: F %d = %.3f Hz (%+.2f cents)" % ("PASS" if same else "FAIL", f, hz, 1200 * math.log2(hz / 440)))
    worst = max(((n, 1200 * math.log2(CLOCK / (32 * (2048 - frequency(0, n * 64))) / (440 * 2 ** ((n - 57) / 12))))
                 for n in range(27, 96)), key=lambda x: abs(x[1]))
    print("     worst wave note: %d at %+.2f cents" % worst)
    return ok


if __name__ == "__main__":
    if "--check-tables" in sys.argv[1:]:
        sys.exit(0 if check_tables() else 1)
    print(__doc__)
    sys.exit(2)
