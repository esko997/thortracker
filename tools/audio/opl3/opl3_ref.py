#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's OPL3 player, written from the specification in
docs/AUDIO_ENGINE.md ("OPL3: player") independently of the C player (app/src/main/cpp/opl3/opl3_player.c).
The host test compares the C player's register writes with this one's, tick by tick.

    python3 tools/audio/opl3/opl3_ref.py IMAGE.bin --trace out.txt --ticks 1500
    python3 tools/audio/opl3/opl3_ref.py IMAGE.bin --vgm out.vgm [--max-ticks N]

Pure Python 3, no dependencies. Also defines the pitch and vibrato tables (generated into
app/src/main/cpp/generated/opl3_tables.h by prepare_opl3.py)."""
import argparse
import math
import struct
import sys

CLOCK = 14318180
NATIVE_RATE = CLOCK / 288
UNITS_PER_SEMITONE = 64
UNITS_PER_OCTAVE = 768
PITCH_MAX = 8 * UNITS_PER_OCTAVE - 1


def fnum_table():
    """F-number for each 1/64 semitone of an octave above C, and the index where the block switches.

    Below the split the F-number is for block = octave - 1 (690..1023), from it for block = octave."""
    c_at_octave_block = 440.0 * 2 ** (-57 / 12) * 2 ** 20 / NATIVE_RATE  # C-n at block n, ~344.88
    table, split = [], None
    for i in range(UNITS_PER_OCTAVE):
        x = 2 * c_at_octave_block * 2 ** (i / UNITS_PER_OCTAVE)
        low = math.floor(x + 0.5)
        if low < 1024:
            assert split is None, "the block split must be monotonic"
            table.append(low)
        else:
            split = i if split is None else split
            table.append(math.floor(x / 2 + 0.5))
    return table, split


def sine_table():
    return [math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64)]


FNUM, FNUM_SPLIT = fnum_table()
SINE = sine_table()

# YMF262 slot offsets within a register bank, by channel 0..8: (operator 1, operator 2).
SLOT_OFFSETS = [(0x00, 0x03), (0x01, 0x04), (0x02, 0x05), (0x08, 0x0B), (0x09, 0x0C), (0x0A, 0x0D),
                (0x10, 0x13), (0x11, 0x14), (0x12, 0x15)]
TRACKER_VOICE = [0, 1, 2, 9, 10, 11, 6, 7, 8]
POOL = [15, 16, 17, 3, 4, 5, 12, 13, 14]
SECONDARY_OF = {3: 0, 4: 1, 5: 2, 12: 3, 13: 4, 14: 5}  # pool voice -> tracker channel whose pair it completes
CARRIERS = {(False, 0): {2}, (False, 1): {1, 2}, (True, 0): {4}, (True, 1): {2, 4}, (True, 2): {1, 4},
            (True, 3): {1, 3, 4}}

FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}


def voice_reg(base, voice):
    return (0x100 if voice >= 9 else 0) + base + voice % 9


def slot_reg(base, voice, op):
    """Register of operator op (1 or 2) of a two-operator voice."""
    return (0x100 if voice >= 9 else 0) + base + SLOT_OFFSETS[voice % 9][op - 1]


def operator_reg(base, primary, op):
    """Register of operator op (1..4) of a note on `primary` (4-op: operators 3/4 on primary + 3)."""
    return slot_reg(base, primary if op <= 2 else primary + 3, 1 if op % 2 else 2)


def frequency(units):
    octave, frac = divmod(units, UNITS_PER_OCTAVE)
    fnum = FNUM[frac]
    block = octave - 1 if frac < FNUM_SPLIT else octave
    if block < 0:
        block, fnum = 0, fnum // 2
    return block, fnum


def floor_shift4(v):
    return v >> 4  # Python's >> is a floor shift, as the spec requires


class Image:
    def __init__(self, data):
        assert len(data) == 65536 and data[:4] == b"TTO3" and data[4] == 1
        self.data = data
        self.multispeed = data[5]
        self.speed = data[6]
        self.depth = data[7]
        self.orders, self.loop = struct.unpack_from("<HH", data, 8)
        self.instrument_count = data[12]
        self.shape_count = data[13]
        self.rows = [(data[64 + o] or 256) for o in range(self.orders)]
        self.patterns = [[struct.unpack_from("<H", data, 320 + 2 * (9 * o + c))[0] for c in range(9)]
                         for o in range(self.orders)]
        self._decoded = {}

    def instrument(self, slot):
        if not 1 <= slot <= self.instrument_count:
            return None
        rec = self.data[4928 + 32 * (slot - 1): 4928 + 32 * slot]
        if not rec[0] & 0x80:
            return None
        four = bool(rec[0] & 1)
        return {
            "four": four, "alg": rec[1], "fb": rec[2],
            "ops": [list(rec[4 + 5 * k: 9 + 5 * k]) for k in range(4 if four else 2)],
            "sweep": (struct.unpack("b", rec[24:25])[0], struct.unpack("b", rec[25:26])[0], rec[26]),
        }

    def shape(self, k):
        raw = struct.unpack_from("4b", self.data, 6976 + 4 * (k - 1))
        out = []
        for v in raw:
            if v == 0:
                break
            out.append(v)
        return out

    def pattern_rows(self, order, channel):
        """All rows of the pattern of (order, channel), decoded from its start."""
        offset, n = self.patterns[order][channel], self.rows[order]
        key = (offset, n)
        if key in self._decoded:
            return self._decoded[key]
        at, rows = 7232 + offset, []
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

    def start_speeds(self):
        speeds, s = [], self.speed
        for o in range(self.orders):
            speeds.append(s)
            for r in range(self.rows[o]):
                for c in range(9):
                    row = self.pattern_rows(o, c)[r]
                    for code, param in (row[2] if row else []):
                        if code == FX["F"]:
                            s = param
        return speeds


class Track:
    """State of one tracker channel."""

    def __init__(self):
        self.row = None
        self.delay = None          # tick of a delayed row, None = no row pending
        self.cut = None
        self.slot = 0
        self.sounding = False      # note keyed on
        self.started = False       # some note has been keyed since reset
        self.pair4 = False         # 4-op mode of its pair (channels 0-5)
        self.note = None           # the playing note's registers: dict(ops, alg, fb, nops, carriers)
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
        self.volume = 504
        self.volume_slide = 0
        self.pan = 0x30
        self.chord = []            # [(voice, interval)] in allocation order


class Player:
    def __init__(self, image):
        self.img = image
        self.start_speed = image.start_speeds()
        self.reset()

    # -- registers --
    def reset(self):
        self.regs = [0] * 512
        self.out, self.key_offs = [], []
        self.tracks = [Track() for _ in range(9)]
        self.owner = {v: None for v in POOL}
        self.age = {v: 0 for v in POOL}
        self.clock = 0
        self.order = self.row = self.tick_in_row = 0
        self.speed = self.img.speed
        self.next_pos = None
        self.audition = False
        self.write(0x105, 1)
        self.write(0xBD, self.img.depth)
        self.batch = self.key_offs + self.out
        self.split = len(self.key_offs)

    def write(self, reg, val):
        """Diffed write; a key-on bit going 1 -> 0 belongs to the batch's key-off phase."""
        old = self.regs[reg]
        if old != val:
            self.regs[reg] = val
            if 0xB0 <= reg & 0xFF <= 0xB8 and old & 0x20 and not val & 0x20:
                self.key_offs.append((reg, val))
            else:
                self.out.append((reg, val))

    def stamp(self):
        self.clock += 1
        return self.clock

    def key_off_voice(self, voice):
        self.write(voice_reg(0xB0, voice), self.regs[voice_reg(0xB0, voice)] & 0xDF)

    # -- voices --
    def pool_usable(self, voice):
        ch = SECONDARY_OF.get(voice)
        return ch is None or not self.tracks[ch].pair4

    def free_chord(self, ch):
        t = self.tracks[ch]
        for voice, _ in t.chord:
            self.key_off_voice(voice)
            self.owner[voice] = None
            self.age[voice] = self.stamp()
        t.chord = []

    def take_voice(self, ch):
        usable = [v for v in POOL if self.pool_usable(v)]
        free = [v for v in usable if self.owner[v] is None]
        if free:
            voice = min(free, key=lambda v: (self.age[v], POOL.index(v)))
        else:
            voice = min(usable, key=lambda v: (self.age[v], POOL.index(v)))
            victim = self.tracks[self.owner[voice]]
            victim.chord = [(v, i) for v, i in victim.chord if v != voice]
            self.key_off_voice(voice)
        self.owner[voice] = ch
        self.age[voice] = self.stamp()
        return voice

    def tl(self, t, op_index, r40):
        if op_index + 1 not in t.note["carriers"]:
            return r40
        return (r40 & 0xC0) | min(63, (r40 & 63) + 63 - (t.volume >> 3))

    def connection(self, t, voice, nops):
        n = t.note
        if nops == 4:
            self.write(voice_reg(0xC0, voice), t.pan | n["fb"] << 1 | n["alg"] >> 1)
            self.write(voice_reg(0xC0, voice + 3), t.pan | n["alg"] & 1)
        else:
            self.write(voice_reg(0xC0, voice), t.pan | n["fb"] << 1 | n["alg"] & 1)

    def program(self, t, voice, nops):
        for k in range(nops):
            r20, r40, r60, r80, wave = t.note["ops"][k]
            self.write(operator_reg(0x20, voice, k + 1), r20)
            self.write(operator_reg(0x40, voice, k + 1), self.tl(t, k, r40))
            self.write(operator_reg(0x60, voice, k + 1), r60)
            self.write(operator_reg(0x80, voice, k + 1), r80)
            self.write(operator_reg(0xE0, voice, k + 1), wave)
        self.connection(t, voice, nops)

    # -- notes --
    def note_off(self, ch):
        t = self.tracks[ch]
        if t.sounding:
            self.key_off_voice(TRACKER_VOICE[ch])
            t.sounding = False
        self.free_chord(ch)

    def note_on(self, ch, note, locks, intervals):
        t = self.tracks[ch]
        inst = self.img.instrument(t.slot)
        if inst is None:
            return
        self.note_off(ch)
        if ch < 6 and inst["four"] != t.pair4:
            secondary = TRACKER_VOICE[ch] + 3
            if inst["four"] and self.owner[secondary] is not None:
                victim = self.tracks[self.owner[secondary]]
                victim.chord = [(v, i) for v, i in victim.chord if v != secondary]
                self.key_off_voice(secondary)
                self.owner[secondary] = None
                self.age[secondary] = self.stamp()
            t.pair4 = inst["four"]
            self.write(0x104, sum(1 << i for i in range(6) if self.tracks[i].pair4))
            if not t.pair4:
                self.age[secondary] = self.stamp()
        nops = 4 if inst["four"] else 2
        carriers = CARRIERS[(inst["four"], inst["alg"])]
        ops = [list(o) for o in inst["ops"]]
        fb = inst["fb"]
        for code, param in locks:
            hi, lo = param >> 4, param & 15
            if code == FX["E"]:
                fb = param
            elif code == FX["I"]:
                for k in range(nops):
                    if k + 1 not in carriers:
                        ops[k][1] = (ops[k][1] & 0xC0) | (63 - param)
            elif code == FX["M"]:
                if hi <= nops:
                    ops[hi - 1][0] = (ops[hi - 1][0] & 0xF0) | lo
            elif code == FX["W"]:
                for k in range(nops):
                    if hi in (0, k + 1):
                        ops[k][4] = lo
        t.note = {"ops": ops, "alg": inst["alg"], "fb": fb, "nops": nops, "carriers": carriers}
        self.program(t, TRACKER_VOICE[ch], nops)
        t.base = t.target = note * UNITS_PER_SEMITONE
        t.slide = 0
        t.arp_phase = 0
        t.vib_phase = 0
        start, end, speed = inst["sweep"]
        if speed:
            t.sweep, t.sweep_end, t.sweep_step = start * 64, end * 64, speed * 4
        else:
            t.sweep = t.sweep_end = t.sweep_step = 0
        t.sounding = t.started = True
        if not inst["four"]:
            for interval in intervals:
                voice = self.take_voice(ch)
                t.chord.append((voice, interval))
                self.program(t, voice, 2)

    def run_row(self, ch):
        t = self.tracks[ch]
        note, inst, effects = t.row if t.row else (None, None, [])
        playing_note = note is not None and note < 96
        if playing_note:
            t.arp = 0
            t.fine = 0
        if inst:
            t.slot = inst
        intervals, locks = [], []
        for code, param in effects:
            hi, lo = param >> 4, param & 15
            if code == FX["0"]:
                t.arp, t.arp_phase = param, 0
            elif code in (FX["1"], FX["2"], FX["3"]):
                t.slide_kind = {FX["1"]: "up", FX["2"]: "down", FX["3"]: "porta"}[code] if param else None
                t.slide_speed = param
            elif code == FX["4"]:
                t.vib = (hi, lo)
            elif code == FX["8"]:
                t.pan = (0x10 if hi else 0) | (0x20 if lo else 0)
                if t.started:
                    self.connection(t, TRACKER_VOICE[ch], t.note["nops"])
                    for voice, _ in t.chord:
                        self.connection(t, voice, 2)
            elif code == FX["A"]:
                t.volume_slide = param
            elif code == FX["C"]:
                t.volume = param * 8
            elif code == FX["H"]:
                intervals = [v for v in (hi, lo) if v]
            elif code == FX["K"]:
                intervals = self.img.shape(param) if param else []
            elif code == FX["P"]:
                t.fine = param - 0x80
            elif code == FX["S"]:
                t.cut = param
            elif code in (FX["E"], FX["I"], FX["M"], FX["W"]):
                locks.append((code, param))
        if note == 0xFE:
            self.note_off(ch)
        elif playing_note:
            if t.slide_kind == "porta" and t.sounding:
                t.target = note * UNITS_PER_SEMITONE
            else:
                self.note_on(ch, note, locks, intervals)

    def refresh(self, ch):
        t = self.tracks[ch]
        if not t.started:
            return
        arp = 0
        if t.arp and t.arp_phase:
            arp = (t.arp >> 4) if t.arp_phase == 1 else (t.arp & 15)
        vib = floor_shift4(SINE[t.vib_phase] * t.vib[1]) if t.vib[1] else 0
        units = max(0, min(PITCH_MAX, t.base + arp * 64 + t.fine + t.slide + vib + t.sweep))
        voices = [(TRACKER_VOICE[ch], units, t.note["nops"], t.sounding)]
        voices += [(v, max(0, min(PITCH_MAX, units + i * 64)), 2, True) for v, i in t.chord]
        for voice, u, nops, keyed in voices:
            for k in range(nops):
                if k + 1 in t.note["carriers"]:
                    self.write(operator_reg(0x40, voice, k + 1), self.tl(t, k, t.note["ops"][k][1]))
            block, fnum = frequency(u)
            self.write(voice_reg(0xA0, voice), fnum & 0xFF)
            self.write(voice_reg(0xB0, voice), (0x20 if keyed else 0) | block << 2 | fnum >> 8)
        # effects advance after the writes
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
            t.volume = max(0, min(504, t.volume + (t.volume_slide >> 4) - (t.volume_slide & 15)))

    # -- sequencing --
    def begin_row(self):
        img = self.img
        for ch in range(9):
            self.tracks[ch].row = img.pattern_rows(self.order, ch)[self.row]
        jump_order = jump_row = None
        for ch in range(9):
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
        for ch in range(9):
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
        self.write(0xBD, self.img.depth)
        if not self.audition:
            if self.tick_in_row == 0:
                self.begin_row()
            else:
                for ch in range(9):
                    t = self.tracks[ch]
                    if t.delay is not None and t.delay == self.tick_in_row:
                        t.delay = None
                        self.run_row(ch)
            for ch in range(9):
                if self.tracks[ch].cut == self.tick_in_row:
                    self.note_off(ch)
        for ch in range(9):
            self.refresh(ch)
        if not self.audition:
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
                    self.speed = self.start_speed[self.order]  # every pass of the loop starts alike
        self.batch = self.key_offs + self.out
        self.split = len(self.key_offs)
        return self.batch

    def position(self):
        return self.order, self.row, self.tick_in_row


def trace(image, ticks):
    """[(order, row, tick), writes, key-off phase length] per tick; entry 0 is the reset batch (position None)."""
    p = Player(image)
    out = [(None, list(p.batch), p.split)]
    for _ in range(ticks):
        pos = p.position()
        writes = list(p.tick())
        out.append((pos, writes, p.split))
    return out


def format_tick(pos, writes, split):
    """One line of a trace file (the host test writes the same): "order row tick: REG=VAL ..." with " |" after
    the key-off phase when it is not empty; "reset:" for the reset batch."""
    where = "reset" if pos is None else "%d %d %d" % pos
    text = "".join(" %03X=%02X" % w for w in writes[:split]) + (" |" if split else "")
    return where + ":" + text + "".join(" %03X=%02X" % w for w in writes[split:])


def vgm(image, max_ticks):
    """VGM 1.71 bytes, as docs/AUDIO_ENGINE.md "OPL3: VGM export" specifies. Returns (bytes, info)."""
    p = Player(image)
    body = bytearray()

    def emit(writes):
        for reg, val in writes:
            body.extend((0x5F if reg & 0x100 else 0x5E, reg & 0xFF, val))

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

    def state_writes(regs):
        """Every register the player uses, so each pass of the loop starts from the same chip registers."""
        order = [0x105, 0x104, 0x08, 0xBD]
        for bank in (0, 0x100):
            for base in (0x20, 0x40, 0x60, 0x80, 0xE0):
                order += [bank + base + off for off in range(0x16) if off % 8 < 6]
        for base in (0xC0, 0xA0, 0xB0):
            order += [voice_reg(base, v) for v in range(18)]
        return [(r, regs[r]) for r in order]

    emit(p.batch)
    period = 60 * image.multispeed
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
            emit(state_writes(p.regs))
        writes = p.tick()
        acc += 44100
        n, acc = divmod(acc, period)
        samples += n
        # the key-off phase, one sample, then the rest (a key-off directly before a key-on must be seen by the chip)
        emit(writes[:p.split])
        if p.split:
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
    struct.pack_into("<I", header, 0x34, 0x100 - 0x34)
    struct.pack_into("<I", header, 0x5C, CLOCK)
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
