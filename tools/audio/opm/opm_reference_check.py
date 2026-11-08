#!/usr/bin/env python3
"""Independent checks of the OPM host test's evidence (developer evidence, not part of the build):

  1. The Python reference player (opm_ref.py) on the test image (opm_song.bin) writes exactly the C machine's
     per-tick trace (opm_trace.txt) and produces the same VGM bytes (opm_song.vgm).
  2. The VGM, parsed here from the file format alone: header fields (VGM 1.71, YM2151 at 4000000 Hz, data at
     0x100), end-of-file offset, waits summing to the header's sample count, the loop point on a command boundary,
     the 0x54 command stream equal to the engine's trace (reset batch, then per tick the key-off phase, a one-sample
     wait when that phase is not empty, the other writes and the rest of the tick), and the register dump at the
     loop point equal to the register state the stream itself wrote before it.
  3. With --pitch: the 4 MHz pitch of every playable note, from ymfm's phase-step table (s_phase_step in
     third_party/ymfm/ymfm_fm.ipp, David Viens' chip-verified table) and the player's key-code formula, against
     12-TET A440: A-4's frequency, each note's error in cents, the worst error, and the clock relation behind
     OMP_PITCH_OFFSET (1200 log2(4000000 / 3579545) cents = 123.05 sixty-fourths of a semitone).
  4. With --vgm2wav (libvgm's vgm2wav, its own YM2151 emulation, not ymfm): the VGM rendered by that independent
     player and core against the engine's own render (opm_song.wav): loudness envelope correlation, log-spectrum
     correlation, dominant pitch class. VGM players' CSM support depends on their core (docs/AUDIO_ENGINE.md).

    python3 tools/audio/opm/opm_reference_check.py EVIDENCE_DIR [--pitch] [--vgm2wav PATH]   (4 needs numpy)
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
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, HERE)
import opm_ref  # noqa: E402

YMFM_FM = os.path.join(ROOT, "app/src/main/cpp/third_party/ymfm/ymfm_fm.ipp")
VGM_MAX_TICKS = 36000
MANUAL_CLOCK = 3579545
failures = 0


def check(ok, what):
    global failures
    print(("PASS " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


def read_trace(path):
    with open(path) as f:
        return [line.rstrip("\n") for line in f]


def trace_writes(line):
    """(key-off phase writes, other writes) of one trace line."""
    rest = line.partition(":")[2]
    first, bar, second = rest.partition("|")
    parse = lambda text: [(int(w[:2], 16), int(w[3:], 16)) for w in text.split()]  # noqa: E731
    return (parse(first), parse(second)) if bar else ([], parse(first))


def parse_vgm(data):
    """(header dict, events [("w", reg, val) | ("t", samples)] with adjacent waits merged, command offsets, end)."""
    h = {
        "eof": struct.unpack_from("<I", data, 4)[0] + 4,
        "version": struct.unpack_from("<I", data, 8)[0],
        "samples": struct.unpack_from("<I", data, 0x18)[0],
        "loop_offset": struct.unpack_from("<I", data, 0x1C)[0],
        "loop_samples": struct.unpack_from("<I", data, 0x20)[0],
        "rate": struct.unpack_from("<I", data, 0x24)[0],
        "ym2151": struct.unpack_from("<I", data, 0x30)[0],
        "data": 0x34 + struct.unpack_from("<I", data, 0x34)[0],
    }
    at, events, offsets = h["data"], [], {}
    while True:
        offsets[at] = len(events)
        cmd = data[at]
        if cmd == 0x54:
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


def replay_state(events):
    """Registers ($19 split into AMD and PMD) and per-channel key state as a chip would hold them after `events`."""
    regs, pmd, keyed = [0] * 256, 0, [False] * opm_ref.CHANNELS
    for e in events:
        if e[0] != "w":
            continue
        _, reg, val = e
        if reg == 0x08:
            keyed[val & 7] = bool(val & 0x78)
        elif reg == 0x19 and val & 0x80:
            pmd = val & 0x7F
        else:
            regs[reg] = val
    return regs, pmd, keyed


def check_vgm(image, trace, vgm, info):
    h, events, offsets, end = parse_vgm(vgm)
    check(vgm[:4] == b"Vgm " and h["version"] == 0x171 and h["ym2151"] == opm_ref.CLOCK and h["data"] == 0x100
          and h["rate"] == 60, "VGM 1.71 header, YM2151 at %d Hz, data at 0x100, rate 60" % opm_ref.CLOCK)
    check(h["eof"] == len(vgm) == end, "end-of-file offset")
    check(sum(e[1] for e in events if e[0] == "t") == h["samples"],
          "waits sum to %d samples (%.2f s)" % (h["samples"], h["samples"] / 44100))
    loop_cmd = 0x1C + h["loop_offset"]
    check(h["loop_offset"] and loop_cmd in offsets and h["loop_samples"] == info["loop_samples"],
          "loop point on a command boundary, %d samples" % h["loop_samples"])
    dump_regs = list(opm_ref.DUMP_GLOBALS[:4]) + [0x19, 0x19] + list(opm_ref.DUMP_GLOBALS[6:]) + \
        list(range(0x20, 0x100)) + [0x08] * opm_ref.CHANNELS
    dump_len = len(dump_regs)
    expected = [("w", r, v) for r, v in opm_ref.reset_writes(image)]
    acc, period, dump_at = 0, 60 * image.multispeed, None
    for k in range(info["ticks"]):
        if dump_at is None and trace[k].startswith("%d 0 0:" % image.loop):
            dump_at = len(expected)
            expected += [None] * dump_len
        offs, rest = trace_writes(trace[k])
        acc += 44100
        n, acc = divmod(acc, period)
        expected += [("w", r, v) for r, v in offs]
        if offs:
            expected.append(("t", 1))
            n -= 1
        expected += [("w", r, v) for r, v in rest]
        if expected[-1] and expected[-1][0] == "t":
            expected[-1] = ("t", expected[-1][1] + n)
        elif n:
            expected.append(("t", n))
    got = [None if dump_at is not None and dump_at <= i < dump_at + dump_len else e for i, e in enumerate(events)]
    check(got == expected, "VGM stream = engine trace: key-off phase, 1-sample gap, writes, waits (%d ticks, %d events)"
          % (info["ticks"], len(events)))
    check(dump_at is not None and offsets.get(loop_cmd) == dump_at,
          "loop point = the register dump before the loop order's first tick")
    if dump_at is None:
        return
    dump = events[dump_at: dump_at + dump_len]
    check([e[0] == "w" and e[1] == r for e, r in zip(dump, dump_regs)] == [True] * dump_len,
          "register dump order: $0F $10 $11 $18 $19 $19 $1B $14, $20-$FF, $08 per channel")
    regs, pmd, keyed = replay_state(events[:dump_at])
    want = [regs[0x0F], regs[0x10], regs[0x11], regs[0x18], regs[0x19], 0x80 | pmd, regs[0x1B], regs[0x14]]
    want += regs[0x20:0x100]
    want += [(0x78 if keyed[c] else 0) | c for c in range(opm_ref.CHANNELS)]
    check([e[2] for e in dump] == want, "register dump = the state the stream wrote before it (%d registers)" % dump_len)


def phase_step_table():
    with open(YMFM_FM) as f:
        text = f.read()
    m = re.search(r"s_phase_step\[12\s*\*\s*64\]\s*=\s*\{([^}]*)\}", text)
    table = [int(v) for v in re.findall(r"\d+", m.group(1))]
    assert len(table) == 768
    return table


def opm_frequency(table, kc, kf, clock=opm_ref.CLOCK):
    """Frequency of key code `kc` ($28 value) and key fraction `kf` (0..63) with MUL 1, DT1/DT2 0, no PM: ymfm's
    opm_key_code_to_phase_step (block bits 10-12, gappy code bits 6-9, fraction bits 0-5), a 20-bit phase."""
    block, code = kc >> 4 & 7, kc & 15
    eff = (code - (code >> 2)) << 6 | kf
    step = table[eff] >> (block ^ 7)
    return step * (clock / opm_ref.NATIVE_DIVIDER) / (1 << 20)


def check_pitch():
    table = phase_step_table()
    offset_cents = 1200 * math.log2(opm_ref.CLOCK / MANUAL_CLOCK)
    units = offset_cents * opm_ref.UNITS_PER_SEMITONE / 100
    print("  4 MHz over the manual's %d Hz: %.2f cents = %.2f sixty-fourths of a semitone (player: %d)"
          % (MANUAL_CLOCK, offset_cents, units, opm_ref.PITCH_OFFSET))
    check(round(units) == opm_ref.PITCH_OFFSET, "PITCH_OFFSET = round(64 x 12 log2(4000000 / 3579545))")
    worst, worst_note = 0.0, None
    names = ["C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-"]
    for note in range(opm_ref.NOTE_MIN, opm_ref.NOTE_MAX + 1):
        kc, kf4 = opm_ref.key_code(note * opm_ref.UNITS_PER_SEMITONE)
        f = opm_frequency(table, kc, kf4 >> 2)
        target = 440.0 * 2 ** ((note - 57) / 12)
        cents = 1200 * math.log2(f / target)
        print("  %s%d  kc $%02X kf %2d  %10.3f Hz  %+6.2f cents" % (names[note % 12], note // 12, kc, kf4 >> 2, f, cents))
        if abs(cents) > abs(worst):
            worst, worst_note = cents, note
    kc, kf4 = opm_ref.key_code(57 * opm_ref.UNITS_PER_SEMITONE)
    a4 = opm_frequency(table, kc, kf4 >> 2)
    check(kc == 0x48 and kf4 >> 2 == 5 and abs(a4 - 439.88) < 0.005,
          "A-4 = key code $48 fraction 5 = %.2f Hz (%+.2f cents)" % (a4, 1200 * math.log2(a4 / 440)))
    check(abs(worst) <= 4.4, "every note D#0..D-8 within 4.4 cents of 12-TET (worst %+.2f at %s%d)"
          % (worst, names[worst_note % 12], worst_note // 12))
    lo = opm_ref.key_code(opm_ref.PITCH_MIN)
    hi = opm_ref.key_code(opm_ref.PITCH_MAX)
    check(lo == (0x00, 0) and hi == (0x7E, 63 << 2),
          "pitch clamp = key code C#0 fraction 0 .. C-8 ($7E) fraction 63")


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
    a, b = a[:n], b[:n]
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
    print("  libvgm vs engine (ymfm): envelope correlation %.3f (lag %d ms), log-spectrum correlation %.3f, same "
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
    with open(os.path.join(args.dir, "opm_song.bin"), "rb") as f:
        image = opm_ref.Image(f.read())
    trace = read_trace(os.path.join(args.dir, "opm_trace.txt"))
    ref = [opm_ref.format_tick(*t) for t in opm_ref.trace(image, len(trace))]
    same = sum(1 for a, b in zip(trace, ref) if a == b)
    first = next((k for k, (a, b) in enumerate(zip(trace, ref)) if a != b), None)
    check(same == len(trace), "reference player = engine trace in %d/%d ticks%s"
          % (same, len(trace), "" if first is None else " (first difference at tick %d)" % first))
    if first is not None:
        print("  engine:    " + trace[first])
        print("  reference: " + ref[first])
    with open(os.path.join(args.dir, "opm_song.vgm"), "rb") as f:
        vgm = f.read()
    mine, info = opm_ref.vgm(image, VGM_MAX_TICKS)
    check(mine == vgm, "reference VGM = engine VGM (%d bytes)" % len(vgm))
    if len(trace) < info["ticks"]:
        # the stream check needs a trace as long as the VGM: extend the engine's with the reference's own ticks
        print("  (the trace has %d ticks, the VGM %d: the rest of the stream is checked against the reference player)"
              % (len(trace), info["ticks"]))
        trace = trace + [opm_ref.format_tick(*t) for t in opm_ref.trace(image, info["ticks"])[len(trace):]]
    check_vgm(image, trace, vgm, info)
    if args.pitch:
        check_pitch()
    if args.vgm2wav:
        compare_audio(os.path.join(args.dir, "opm_song.wav"), os.path.join(args.dir, "opm_song.vgm"), args.vgm2wav)
    print("FAIL" if failures else "PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
