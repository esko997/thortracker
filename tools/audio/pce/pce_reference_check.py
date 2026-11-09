#!/usr/bin/env python3
"""Independent checks of the PC Engine host test's evidence (developer evidence, not part of the build):

  1. The Python reference player (pce_ref.py) on the test image (pce_song.bin) writes exactly the C machine's trace
     (pce_trace.txt: per tick its first CPU cycle and writes, every timer interrupt that wrote with its cycle) and
     produces the same VGM bytes (pce_song.vgm).
  2. The VGM, parsed here from the file format alone: header fields (VGM 1.71, HuC6280 at 3579545 Hz at 0xA4, data at
     0x100, rate 60), end-of-file offset, waits summing to the header's sample count, the loop point on a command
     boundary, the 0xB9 command stream equal to the engine trace (the reset batch, then every tick's writes and every
     interrupt's writes, each after a wait to floor(cycle * 44100 / 7159090.91)), and the register dump at the loop
     point equal to the chip state the stream itself established before it (registers per channel, the select
     register, and the waveform RAM replayed with the HuC6280's write-index rules).
  3. With --pitch: the period of every playable note A-0..B-7 from the player's formula against the period computed
     here from the PSG clock (39375000 / 11 Hz, f = clock / (32 N)) for 12-TET A440: A-4 = N 254 = 440.40 Hz
     (+1.56 cents), the worst error (19.2 cents at B-7; 4.2 cents up to C-6), each N the nearest integer to the ideal,
     the clamps, and the sample step's octave relation.
  4. With --vgm2wav (libvgm's vgm2wav, its own HuC6280 emulation, not Mednafen): the VGM rendered by that independent
     player and core against the engine's own render (pce_song.wav): loudness envelope correlation, log-spectrum
     correlation, dominant pitch class.

    python3 tools/audio/pce/pce_reference_check.py EVIDENCE_DIR [--pitch] [--vgm2wav PATH]   (4 needs numpy)
Prints PASS/FAIL per check; exit status 1 on any failure."""
import argparse
import math
import os
import re
import struct
import subprocess
import sys
import tempfile
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pce_ref  # noqa: E402

VGM_MAX_TICKS = 36000
PSG_CLOCK = 39375000 / 11
failures = 0


def check(ok, what):
    global failures
    print(("PASS " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


def read_trace(path):
    with open(path) as f:
        return [line.rstrip("\n") for line in f]


def parse_trace_line(line):
    """((order, row, tick), start cycle, writes, [(cycle, writes)]) of one trace line."""
    parts = line.split(" | ")
    m = re.match(r"(\d+) (\d+) (\d+) @(\d+):(.*)$", parts[0])
    parse = lambda text: [(int(w[:2], 16), int(w[3:], 16)) for w in text.split()]  # noqa: E731
    pos = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
    irqs = []
    for p in parts[1:]:
        k = re.match(r"@(\d+):(.*)$", p)
        irqs.append((int(k.group(1)), parse(k.group(2))))
    return pos, int(m.group(4)), parse(m.group(5)), irqs


def parse_vgm(data):
    """(header dict, events [("w", reg, val) | ("t", samples)] with adjacent waits merged, command offsets, end)."""
    h = {
        "eof": struct.unpack_from("<I", data, 4)[0] + 4,
        "version": struct.unpack_from("<I", data, 8)[0],
        "samples": struct.unpack_from("<I", data, 0x18)[0],
        "loop_offset": struct.unpack_from("<I", data, 0x1C)[0],
        "loop_samples": struct.unpack_from("<I", data, 0x20)[0],
        "rate": struct.unpack_from("<I", data, 0x24)[0],
        "data": 0x34 + struct.unpack_from("<I", data, 0x34)[0],
        "huc6280": struct.unpack_from("<I", data, 0xA4)[0],
        "others": any(data[0x0C:0x18]) or any(data[0x28:0x34]) or any(data[0x38:0xA4]) or any(data[0xA8:0x100]),
    }
    at, events, offsets = h["data"], [], {}
    while True:
        offsets[at] = len(events)
        cmd = data[at]
        if cmd == 0xB9:
            events.append(("w", data[at + 1], data[at + 2]))
            at += 3
            continue
        if cmd == 0x61:
            n = struct.unpack_from("<H", data, at + 1)[0]
            at += 3
        elif cmd == 0x62:
            n, at = 735, at + 1
        elif cmd == 0x63:
            n, at = 882, at + 1
        elif 0x70 <= cmd <= 0x7F:
            n, at = (cmd & 15) + 1, at + 1
        elif cmd == 0x66:
            break
        else:
            raise ValueError("unexpected VGM command %02X at %X" % (cmd, at))
        if events and events[-1][0] == "t":
            events[-1] = ("t", events[-1][1] + n)
        else:
            events.append(("t", n))
    return h, events, offsets, at + 1


class ChipState:
    """The HuC6280 PSG registers as a chip holds them after a write stream (Mednafen's / the data sheet's rules for
    the waveform RAM: R4 bit 6 falling resets the write index; R4 bit 7 rising with bit 6 clear advances it; R6 writes
    the RAM when bit 6 is clear and advances the index when bits 6-7 are both clear)."""

    def __init__(self):
        self.select = 0
        self.globals = {pce_ref.R_MASTER: 0, pce_ref.R_LFO_FREQ: 0, pce_ref.R_LFO_CTRL: 0}
        self.regs = [{r: 0 for r in (2, 3, 4, 5, 7)} for _ in range(pce_ref.CHANNELS)]
        self.wave = [[0] * 32 for _ in range(pce_ref.CHANNELS)]
        self.index = [0] * pce_ref.CHANNELS
        self.waved = [False] * pce_ref.CHANNELS  # a complete wave load seen

    def write(self, reg, val):
        if reg == 0:
            self.select = val & 7
            return
        if reg in self.globals:
            self.globals[reg] = val
            return
        c = self.select
        if c >= pce_ref.CHANNELS:
            return
        r = self.regs[c]
        if reg == 4:
            old = r[4]
            if old & 0x40 and not val & 0x40:
                self.index[c] = 0
            if not old & 0x80 and val & 0x80 and not val & 0x40:
                self.index[c] = (self.index[c] + 1) & 31
            r[4] = val
        elif reg == 6:
            if not r[4] & 0x40:
                self.wave[c][self.index[c]] = val & 31
            if not r[4] & 0xC0:
                self.index[c] = (self.index[c] + 1) & 31
        elif reg in r:
            r[reg] = val

    def replay(self, events):
        writes = [(e[1], e[2]) for e in events if e[0] == "w"]
        for i, (reg, val) in enumerate(writes):
            self.write(reg, val)
            # a wave load: R4 = $40, R4 = $00 and 32 R6
            if reg == 4 and val == 0x40 and writes[i + 1: i + 2] == [(4, 0x00)] and \
                    all(w[0] == 6 for w in writes[i + 2: i + 34]) and len(writes) >= i + 34:
                self.waved[self.select] = True
        return self


def expected_stream(reset, lines, ticks, multispeed, loop):
    """The VGM event list the specification derives from the engine trace alone: [("w", reg, val) | ("t", n)] with one
    ("dump",) placeholder where the loop point's register dump goes (checked separately; waits never merge across
    it), plus its index, the loop tick's index in `lines` and the total samples."""
    out = [("w", r, v) for r, v in reset]
    now = 0
    dump_at = loop_line = None

    def wait_to(cycle):
        nonlocal now
        s = cycle * pce_ref.VGM_RATE_NUM // pce_ref.VGM_RATE_DEN
        if s > now:
            if out and out[-1][0] == "t":
                out[-1] = ("t", out[-1][1] + s - now)
            else:
                out.append(("t", s - now))
            now = s

    for k in range(ticks):
        pos, start, writes, irqs = parse_trace_line(lines[k])
        if pos == (loop, 0, 0) and dump_at is None:
            wait_to(start)
            dump_at, loop_line = len(out), k
            out.append(("dump",))
        if writes:
            wait_to(start)
            out += [("w", r, v) for r, v in writes]
        for cycle, w in irqs:
            wait_to(cycle)
            out += [("w", r, v) for r, v in w]
    wait_to(pce_ref.tick_cycle(ticks, multispeed))
    return out, dump_at, loop_line, now


def check_vgm(image, lines, vgm, info):
    h, events, offsets, end = parse_vgm(vgm)
    check(vgm[:4] == b"Vgm " and h["version"] == 0x171 and h["huc6280"] == pce_ref.VGM_CLOCK and h["data"] == 0x100
          and h["rate"] == 60 and not h["others"],
          "VGM 1.71 header, HuC6280 at %d Hz (0xA4), data at 0x100, rate 60, other chips 0" % pce_ref.VGM_CLOCK)
    check(h["eof"] == len(vgm) == end, "end-of-file offset")
    check(sum(e[1] for e in events if e[0] == "t") == h["samples"],
          "waits sum to %d samples (%.2f s)" % (h["samples"], h["samples"] / 44100))
    loop_cmd = 0x1C + h["loop_offset"]
    check(h["loop_offset"] and loop_cmd in offsets and h["loop_samples"] == info["loop_samples"],
          "loop point on a command boundary, %d samples" % h["loop_samples"])
    want, dump_at, loop_line, total = expected_stream(pce_ref.reset_writes(image), lines, info["ticks"],
                                                      image.multispeed, image.loop)
    check(total == h["samples"], "the header's samples = the end tick's start (%d)" % total)
    if dump_at is None:
        check(False, "the trace reaches the loop order")
        return
    # the dump: the events from the loop point up to where the expected stream after the placeholder resumes
    dump_end = offsets.get(loop_cmd, -1)
    tail = want[dump_at + 1:]
    n_dump = len(events) - dump_end - len(tail) if dump_end >= 0 else -1
    got = events[:dump_end] + events[dump_end + n_dump:] if n_dump >= 0 else events
    want = want[:dump_at] + tail
    got_tail = events[dump_end:] if dump_end >= 0 else []
    check(n_dump > 0 and got == want, "VGM stream = engine trace: reset batch, tick writes and interrupt writes after "
          "their waits (%d ticks, %d events, dump of %d writes)" % (info["ticks"], len(events), max(0, n_dump)))
    check(dump_end == dump_at, "loop point = the register dump before the loop order's first tick")
    if n_dump <= 0:
        return
    dump = got_tail[:n_dump]
    check(all(e[0] == "w" for e in dump), "the dump holds writes only")
    if not all(e[0] == "w" for e in dump):
        return
    chip = ChipState().replay(events[:dump_end])
    expect = [(1, chip.globals[1]), (8, chip.globals[8]), (9, chip.globals[9])]
    for c in range(pce_ref.CHANNELS):
        r = chip.regs[c]
        expect.append((0, c))
        if chip.waved[c]:
            expect += [(4, 0x40), (4, 0x00)] + [(6, v) for v in chip.wave[c]]
        expect += [(2, r[2]), (3, r[3]), (5, r[5])] + ([(7, r[7])] if c >= pce_ref.NOISE_FIRST else []) + [(4, r[4])]
    expect.append((0, chip.select))
    check([(e[1], e[2]) for e in dump] == expect,
          "register dump = the chip state the stream wrote before it (globals, per channel the replayed waveform RAM, "
          "R2 R3 R5 R7 R4, then the select register; %d writes)" % len(dump))


def check_pitch():
    names = ["C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-"]
    worst = worst_low = 0.0
    worst_note = None
    nearest = True
    for note in range(pce_ref.NOTE_MIN, pce_ref.NOTE_MAX + 1):
        n = pce_ref.period(note * pce_ref.UNITS_PER_SEMITONE)
        target = 440.0 * 2 ** ((note - 57) / 12)
        ideal = PSG_CLOCK / (32 * target)
        f = PSG_CLOCK / (32 * n)
        cents = 1200 * math.log2(f / target)
        nearest &= abs(n - ideal) <= 0.5 + 1e-6
        print("  %s%d  N %4d (ideal %9.3f)  %10.3f Hz  %+6.2f cents" % (names[note % 12], note // 12, n, ideal, f, cents))
        if abs(cents) > abs(worst):
            worst, worst_note = cents, note
        if note <= 72 and abs(cents) > abs(worst_low):
            worst_low = cents
    a4 = PSG_CLOCK / (32 * pce_ref.period(57 * 64))
    check(pce_ref.period(57 * 64) == 254 and abs(a4 - 440.40) < 0.005,
          "A-4 = N 254 = %.2f Hz (%+.2f cents)" % (a4, 1200 * math.log2(a4 / 440)))
    check(nearest, "every note's period is the nearest integer to clock / (32 f)")
    check(abs(worst) <= 19.25, "every note A-0..B-7 within 19.2 cents of 12-TET (worst %+.2f at %s%d)"
          % (worst, names[worst_note % 12], worst_note // 12))
    check(abs(worst_low) <= 4.2, "A-0..C-6 within 4.2 cents (worst %+.2f)" % worst_low)
    check(pce_ref.period(9 * 64) == 4068 and pce_ref.period(95 * 64) == 28, "A-0 = 4068, B-7 = 28")
    check(pce_ref.period(pce_ref.PITCH_MIN) == 4093 and pce_ref.period(0) == 4093
          and round(PSG_CLOCK / 32 / (440 * 2 ** ((pce_ref.PITCH_MIN - 1) / 64 / 12 - 57 / 12))) > 4095
          and pce_ref.period(10 ** 6) == pce_ref.period(pce_ref.PITCH_MAX),
          "clamps: pitch 569 is the lowest whose period fits 12 bits (4093), the top clamps to B-7 + 63/64")
    steps = [pce_ref.sample_step(r * 64 + d, r) for r in (9, 48, 57, 83) for d in (0, 768, -768) if
             pce_ref.PITCH_MIN <= r * 64 + d <= pce_ref.PITCH_MAX]
    want = [s for r in (9, 48, 57, 83) for d, s in ((0, 65536), (768, 131072), (-768, 32768)) if
            pce_ref.PITCH_MIN <= r * 64 + d <= pce_ref.PITCH_MAX]
    check(steps == want, "sample step: the root plays 65536 (one frame per interrupt), an octave up 2x, down 1/2")


def read_wav(path):
    import numpy as np
    with wave.open(path) as w:
        n, ch = w.getnframes(), w.getnchannels()
        rate = w.getframerate()
        x = np.frombuffer(w.readframes(n), dtype=np.int16).reshape(-1, ch).astype(float) / 32768
    return x.mean(axis=1), rate


def compare_audio(engine_wav, vgm_path, vgm2wav):
    import numpy as np
    a, rate = read_wav(engine_wav)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "vgm.wav")
        subprocess.run([vgm2wav, "--samplerate", str(rate), "--loops", "5", "--fade", "0", vgm_path, out],
                       check=True, stdout=subprocess.DEVNULL)
        b, _ = read_wav(out)
    n = min(len(a), len(b))
    a, b = a[:n] - a[:n].mean(), b[:n] - b[:n].mean()
    win = rate // 50
    ea = np.sqrt(np.add.reduceat(a ** 2, np.arange(0, n - win, win)) / win)
    eb = np.sqrt(np.add.reduceat(b ** 2, np.arange(0, n - win, win)) / win)
    lag = max(range(-5, 6), key=lambda k: np.corrcoef(ea[10 + k:len(ea) - 10 + k], eb[10:len(eb) - 10])[0, 1])
    env = np.corrcoef(ea[10 + lag:len(ea) - 10 + lag], eb[10:len(eb) - 10])[0, 1]
    shift = lag * win
    if shift > 0:
        a = a[shift:]
    elif shift < 0:
        b = b[-shift:]
    n = min(len(a), len(b))
    w = rate // 10
    spec, same_pc, counted = [], 0, 0
    freqs = np.fft.rfftfreq(w, 1 / rate)
    band = (freqs > 60) & (freqs < 2000)
    for i in range(0, n - w, w):
        sa = np.abs(np.fft.rfft(a[i:i + w] * np.hanning(w)))
        sb = np.abs(np.fft.rfft(b[i:i + w] * np.hanning(w)))
        if sa.max() < 1e-3 or sb.max() < 1e-3:
            continue
        spec.append(np.corrcoef(np.log(sa + 1e-4), np.log(sb + 1e-4))[0, 1])
        pa = freqs[band][np.argmax(sa[band])]
        pb = freqs[band][np.argmax(sb[band])]
        counted += 1
        same_pc += round(12 * np.log2(pa / pb)) % 12 == 0
    level = np.sqrt(np.mean(b[:n] ** 2)) / np.sqrt(np.mean(a[:n] ** 2))
    print("  libvgm vs engine (Mednafen): envelope correlation %.3f (lag %d ms), log-spectrum correlation %.3f, same "
          "pitch class %.0f%% of %d windows, level ratio %.2f"
          % (env, lag * 20, float(np.mean(spec)), 100.0 * same_pc / max(1, counted), counted, level))
    check(env > 0.8, "loudness envelope correlation > 0.8")
    check(np.mean(spec) > 0.8, "log-spectrum correlation > 0.8")
    check(counted and same_pc / counted > 0.7, "dominant pitch class agrees in > 70% of windows")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--vgm2wav")
    ap.add_argument("--pitch", action="store_true")
    args = ap.parse_args()
    with open(os.path.join(args.dir, "pce_song.bin"), "rb") as f:
        image = pce_ref.Image(f.read())
    lines = read_trace(os.path.join(args.dir, "pce_trace.txt"))
    ref = [pce_ref.format_tick(*t) for t in pce_ref.trace(image, len(lines))]
    same = sum(1 for a, b in zip(lines, ref) if a == b)
    first = next((k for k, (a, b) in enumerate(zip(lines, ref)) if a != b), None)
    check(same == len(lines), "reference player = engine trace (ticks and timer interrupts with cycles) in %d/%d "
          "ticks%s" % (same, len(lines), "" if first is None else " (first difference at tick %d)" % first))
    if first is not None:
        print("  engine:    " + lines[first][:400])
        print("  reference: " + ref[first][:400])
    with open(os.path.join(args.dir, "pce_song.vgm"), "rb") as f:
        vgm = f.read()
    mine, info = pce_ref.vgm(image, VGM_MAX_TICKS)
    check(mine == vgm, "reference VGM = engine VGM (%d bytes)" % len(vgm))
    if len(lines) < info["ticks"]:
        # the stream check needs a trace as long as the VGM: extend the engine's with the reference's own ticks
        print("  (the trace has %d ticks, the VGM %d: the rest of the stream is checked against the reference player)"
              % (len(lines), info["ticks"]))
        lines = lines + [pce_ref.format_tick(*t) for t in pce_ref.trace(image, info["ticks"])[len(lines):]]
    check_vgm(image, lines, vgm, info)
    if args.pitch:
        check_pitch()
    if args.vgm2wav:
        compare_audio(os.path.join(args.dir, "pce_song.wav"), os.path.join(args.dir, "pce_song.vgm"), args.vgm2wav)
    print("FAIL" if failures else "PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
