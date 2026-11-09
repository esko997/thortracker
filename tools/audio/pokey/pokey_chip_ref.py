#!/usr/bin/env python3
"""Reference model of the Atari 8-bit POKEY's sound section, written for Thor Tracker's "atari-pokey" system from
docs/AUDIO_ENGINE.md ("POKEY: Thor Tracker's own player", "The chip"), the constants in
app/src/main/cpp/pokey/pokey_chip.h and the research behind them: the Altirra Hardware Reference Manual (Avery Lee,
chapter 5, hardware measurements), MAME's pokey.cpp (cycle structure) and Altirra 4.40's pokeytables.cpp (the
generator formulas). It is written independently of the C model (pokey_chip.c) and is only a test reference.

    python3 tools/audio/pokey/pokey_chip_ref.py pokey_chip_cases.txt     # replays every case, PASS/FAIL per case

Two ways to run the same per-cycle `_step`: `run_reference` steps every cycle (the cases), `run` jumps between the
cycles where something happens (a counter reaches its underflow, a fire, a two-tone resync) and only decrements the
counters in between (the player reference's interrupt times; `check_cases` requires both to agree).

Readings of the specification (agreed with the native slice, 2026-11-08):
  - Counter: a reload sets u = AUDF + 1 counts left; a count does u -= 1 and the count that reaches 0 is the
    underflow (u wraps to 256 and the counter keeps counting); the fire follows PK_FIRE_DELAY = 3 cycles later
    unless one is already pending. Only STIMER and two-tone resyncs drop pending fires.
  - Base clocks: pulses at exit + 19 + 28 k (64 kHz) and exit + 78 + 114 k (15 kHz), exit = the cycle of the SKCTL
    write that ended initialization mode; none while in initialization mode. AUDCTL bit 0 picks one at each cycle.
  - Generators: seq[k] = the output bit after k shifts from the all-zero register (seq[0] = 0; Altirra's buffer
    entry i is seq[i + 1]; the 17-bit one is tapped at bit 8). Channel i (0-based, POKEY channel i + 1) reads
    seq[(t - exit - (i + 1)) mod length] at cycle t; in initialization mode, or for a negative index, the 5-bit
    generator reads 1 and the others 0. Entering initialization mode acts at once on every channel.
  - High-pass flip-flops: an AUDCTL write with bit 2 / 1 clear sets the channel 1 / 2 flip-flop to 1 at once; a
    fire of channel 3 / 4 sets it to channel 1's / 2's output flip-flop (before the XOR) if the bit is set, else 1.
    Reset state 1 (AUDCTL 0: the filters are off).
  - Two-tone: a fire of timer 2 (also when linked) schedules a resync 2 cycles later while SKCTL bit 3 is set; a
    fire of unlinked timer 1 does too unless force break (bit 7). A due resync acts even if bit 3 was cleared
    since; several due in the same cycle act once; initialization mode keeps them, STIMER drops them.
  - Output: levels are reported (cycle, word) after every write and every stepped cycle that changes them; the
    reset reports nothing (levels 0).
Pure Python 3, no dependencies."""
import sys

CLOCK_NTSC = 315000000.0 / 176.0
CLOCK_PAL = 1773447.5
FRAME_CYCLES = (29868, 35568)  # NTSC 262 lines, PAL 312 lines of 114 cycles

FIRE_DELAY = 3
RESYNC_DELAY = 2
BASE64_FIRST, BASE64_PERIOD = 19, 28
BASE15_FIRST, BASE15_PERIOD = 78, 114

AUDF = (0x0, 0x2, 0x4, 0x6)
AUDC = (0x1, 0x3, 0x5, 0x7)
AUDCTL, STIMER, IRQEN, SKCTL = 0x8, 0x9, 0xE, 0xF
IRQ_BITS = (0x01, 0x02, 0x00, 0x04)  # timers 1, 2, 4 (timer 3 has no interrupt)


def _sequence(length, shift, out):
    """Output bits after 0..length-1 shifts of an XNOR LFSR from the all-zero register (Altirra's formulas)."""
    reg, bits = 0, [0]
    for _ in range(length - 1):
        reg = shift(reg)
        bits.append(out(reg))
    reg = shift(reg)
    assert reg == 0, "maximal length: back to the all-zero state"
    return bits


POLY4 = _sequence(15, lambda r: (r >> 1) + (~((r << 2) ^ (r << 3)) & 8), lambda r: r & 1)
POLY5 = _sequence(31, lambda r: (r >> 1) + (~((r << 2) ^ (r << 4)) & 16), lambda r: r & 1)
POLY9 = _sequence(511, lambda r: (r >> 1) + (~((r << 8) ^ (r << 3)) & 0x100), lambda r: r & 1)
POLY17 = _sequence(131071, lambda r: (r >> 1) + (~((r << 16) ^ (r << 11)) & 0x10000), lambda r: (r >> 8) & 1)
SEQUENCES = {4: POLY4, 5: POLY5, 9: POLY9, 17: POLY17}


class Chip:
    """One POKEY, cycle-exact at the machine clock. `now` = the last cycle stepped; writes act at `now`."""

    def __init__(self, on_output=None):
        self.on_output = on_output
        self.reset()

    def reset(self):
        """Power-on: every register 0 (SKCTL 0 = initialization mode), counters reloaded, outputs 0, cycle 0."""
        self.now = 0
        self.audf = [0] * 4
        self.audc = [0] * 4
        self.audctl = self.skctl = self.irqen = 0
        self.init = True
        self.exit = 0
        self.u = [1] * 4
        self.fire_at = [None] * 4
        self.out = [0] * 4
        self.hp = [1, 1]
        self.resyncs = []
        self.levels = 0
        self.irq = 0       # enabled timers that fired since take_irq
        self.irq_new = 0   # enabled timers that fired in the last stepped cycle

    # -- generators and clocks --
    def poly_bit(self, which, ch, t):
        if self.init:
            return 1 if which == 5 else 0
        k = t - self.exit - (ch + 1)
        if k < 0:
            return 1 if which == 5 else 0
        seq = SEQUENCES[which]
        return seq[k % len(seq)]

    def _base(self):
        return (BASE15_FIRST, BASE15_PERIOD) if self.audctl & 0x01 else (BASE64_FIRST, BASE64_PERIOD)

    def _base_pulse(self, t):
        if self.init:
            return False
        first, period = self._base()
        d = t - self.exit - first
        return d >= 0 and d % period == 0

    def _pulses_upto(self, t):
        """Base clock pulses at cycles exit .. t (the currently selected clock)."""
        first, period = self._base()
        d = t - self.exit - first
        return 0 if d < 0 else d // period + 1

    # -- counters --
    def _count(self, i, t):
        u = self.u[i] - 1
        if u == 0:
            u = 256
            if self.fire_at[i] is None:
                self.fire_at[i] = t + FIRE_DELAY
        self.u[i] = u

    def _reload(self, i):
        self.u[i] = self.audf[i] + 1

    def _schedule_resync(self, t):
        if t not in self.resyncs:
            self.resyncs.append(t)
            self.resyncs.sort()

    def _audio(self, i, t):
        c = self.audc[i]
        if c & 0x80 or self.poly_bit(5, i, t):
            if c & 0x20:
                self.out[i] ^= 1
            elif c & 0x40:
                self.out[i] = self.poly_bit(4, i, t)
            else:
                self.out[i] = self.poly_bit(9 if self.audctl & 0x80 else 17, i, t)

    def levels_now(self):
        word = 0
        for i in range(4):
            c = self.audc[i]
            on = self.out[i] ^ self.hp[i] if i < 2 else self.out[i]
            if c & 0x10 or on:
                word |= (c & 15) << (4 * i)
        return word

    def _report(self, t):
        word = self.levels_now()
        if word != self.levels:
            self.levels = word
            if self.on_output:
                self.on_output(t, word)

    def _step(self, t):
        a = self.audctl
        base = self._base_pulse(t)
        # counting
        if a & 0x40 or base:
            self._count(0, t)
        if a & 0x20 or base:
            self._count(2, t)
        if base:
            if not a & 0x10:
                self._count(1, t)
            if not a & 0x08:
                self._count(3, t)
        fired = 0
        # channel 3
        if self.fire_at[2] == t:
            self.fire_at[2] = None
            if a & 0x08:
                self._count(3, t)
            else:
                self._reload(2)
            self._audio(2, t)
            self.hp[0] = self.out[0] if a & 0x04 else 1
        # channel 4
        if self.fire_at[3] == t:
            self.fire_at[3] = None
            if a & 0x08:
                self._reload(2)
            self._reload(3)
            self._audio(3, t)
            self.hp[1] = self.out[1] if a & 0x02 else 1
            fired |= 0x04
        # two-tone resyncs due
        if self.resyncs and self.resyncs[0] == t:
            while self.resyncs and self.resyncs[0] == t:
                self.resyncs.pop(0)
            self._reload(0)
            self._reload(1)
            self.fire_at[0] = self.fire_at[1] = None
        # channel 1
        if self.fire_at[0] == t:
            self.fire_at[0] = None
            if a & 0x10:
                self._count(1, t)
            else:
                self._reload(0)
                if self.skctl & 0x88 == 0x08:
                    self._schedule_resync(t + RESYNC_DELAY)
            self._audio(0, t)
            fired |= 0x01
        # channel 2
        if self.fire_at[1] == t:
            self.fire_at[1] = None
            if a & 0x10:
                self._reload(0)
            self._reload(1)
            self._audio(1, t)
            if self.skctl & 0x08:
                self._schedule_resync(t + RESYNC_DELAY)
            fired |= 0x02
        self.irq_new = fired & self.irqen
        self.irq |= self.irq_new
        self._report(t)

    # -- interface --
    def write(self, reg, val):
        t = self.now
        if reg in AUDF:
            self.audf[reg >> 1] = val
        elif reg in AUDC:
            self.audc[reg >> 1] = val
        elif reg == AUDCTL:
            self.audctl = val
            if not val & 0x04:
                self.hp[0] = 1
            if not val & 0x02:
                self.hp[1] = 1
        elif reg == STIMER:
            for i in range(4):
                self._reload(i)
            self.fire_at = [None] * 4
            self.out = [1] * 4
            self.hp = [1, 1]
            self.resyncs = []
        elif reg == IRQEN:
            self.irqen = val
        elif reg == SKCTL:
            init = (val & 3) == 0
            if init and not self.init:
                self.init = True
            elif not init and self.init:
                self.init = False
                self.exit = t
            self.skctl = val
        self._report(t)

    def take_irq(self):
        bits, self.irq = self.irq, 0
        return bits

    def run_reference(self, until, stop_on_irq=True):
        """Steps every cycle now + 1 .. until; returns True early after a cycle in which an enabled timer fired."""
        while self.now < until:
            self.now += 1
            self._step(self.now)
            if stop_on_irq and self.irq_new:
                return True
        return False

    def _next_event(self):
        """First cycle > now in which a counter underflows, a fire or a resync is due (None: nothing pending)."""
        a = self.audctl
        events = [t for t in self.fire_at if t is not None]
        if self.resyncs:
            events.append(self.resyncs[0])
        fast = (a & 0x40, 0, a & 0x20, 0)
        on_base = (not a & 0x40, not a & 0x10, not a & 0x20, not a & 0x08)
        for i in range(4):
            if fast[i]:
                events.append(self.now + self.u[i])
            elif on_base[i] and not self.init:
                first, period = self._base()
                done = self._pulses_upto(self.now)  # pulse j (0-based) is at exit + first + period j
                events.append(self.exit + first + period * (done + self.u[i] - 1))
        return min(events) if events else None

    def _skip(self, to):
        """Cycles now + 1 .. to contain no event: only the counters move (none reaches its underflow)."""
        if to <= self.now:
            return
        a = self.audctl
        n = to - self.now
        pulses = 0 if self.init else self._pulses_upto(to) - self._pulses_upto(self.now)
        fast = (a & 0x40, 0, a & 0x20, 0)
        on_base = (not a & 0x40, not a & 0x10, not a & 0x20, not a & 0x08)
        for i in range(4):
            if fast[i]:
                self.u[i] -= n
            elif on_base[i]:
                self.u[i] -= pulses
            assert self.u[i] >= 1
        self.now = to

    def run(self, until, stop_on_irq=True):
        """As run_reference, stepping only the cycles where something happens."""
        while self.now < until:
            ev = self._next_event()
            if ev is None or ev > until:
                self._skip(until)
                return False
            self._skip(ev - 1)
            self.now = ev
            self._step(ev)
            if stop_on_irq and self.irq_new:
                return True
        return False


# -- chip cases (pokey_chip_cases.txt, written by the native host test) --

def parse_cases(text):
    """[{name, machine, cycles, writes [(cycle, reg, val)], expected [(cycle, levels)]}] in file order."""
    cases, cur = [], None
    for number, line in enumerate(text.splitlines(), 1):
        f = line.split()
        if not f or f[0].startswith("#"):
            continue
        if f[0] == "CASE":
            cur = {"name": f[1], "machine": f[2], "cycles": int(f[3]), "writes": [], "expected": [], "line": number}
        elif f[0] == "W":
            cur["writes"].append((int(f[1]), int(f[2], 16), int(f[3], 16)))
        elif f[0] == "E":
            cur["expected"].append((int(f[1]), int(f[2], 16)))
        elif f[0] == "END":
            cases.append(cur)
            cur = None
        else:
            raise ValueError("line %d: unknown record %r" % (number, f[0]))
    if cur is not None:
        raise ValueError("case %s has no END" % cur["name"])
    return cases


def replay(case, event_driven=False):
    """The (cycle, levels) changes of one case."""
    got = []
    chip = Chip(lambda t, levels: got.append((t, levels)))
    run = chip.run if event_driven else chip.run_reference
    for cycle, reg, val in case["writes"]:
        run(cycle, stop_on_irq=False)
        chip.write(reg, val)
    run(case["cycles"], stop_on_irq=False)
    return got


def first_difference(expected, got):
    for k in range(max(len(expected), len(got))):
        e = expected[k] if k < len(expected) else None
        g = got[k] if k < len(got) else None
        if e != g:
            return k, e, g
    return None


def check_cases(text, report=print):
    """Replays every case per cycle and event-driven; returns the number of failing cases."""
    failures = 0
    cases = parse_cases(text)
    for case in cases:
        ref = replay(case)
        fast = replay(case, event_driven=True)
        diff = first_difference(case["expected"], ref)
        same = ref == fast
        ok = diff is None and same
        failures += 0 if ok else 1
        text_ = "%s %s (%s, %d cycles, %d writes, %d changes)" % (
            "PASS" if ok else "FAIL", case["name"], case["machine"], case["cycles"], len(case["writes"]),
            len(case["expected"]))
        if diff is not None:
            k, e, g = diff
            fmt = lambda x: "none" if x is None else "E %d %04X" % x  # noqa: E731
            text_ += "; change %d: engine %s, reference %s" % (k, fmt(e), fmt(g))
        if not same:
            text_ += "; the event-driven reference differs from the per-cycle one"
        report(text_)
    return failures, len(cases)


def manual_patterns_ok():
    """The 4- and 5-bit generators' patterns as the Altirra Hardware Reference Manual prints them (5.5)."""
    p4 = "".join(map(str, POLY4)) == "000011101100101"
    p5 = "".join(map(str, POLY5)) == "0000011100100010101111011010011"
    return p4 and p5


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 1:
        print(__doc__)
        return 2
    print(("PASS" if manual_patterns_ok() else "FAIL") + " 4/5-bit generator patterns = the manual's")
    with open(argv[0]) as f:
        failures, total = check_cases(f.read())
    print("%s: %d/%d cases" % ("FAIL" if failures or not manual_patterns_ok() else "PASS", total - failures, total))
    return 1 if failures or not manual_patterns_ok() else 0


if __name__ == "__main__":
    sys.exit(main())
