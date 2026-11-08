#!/usr/bin/env python3
"""Independent checks of the AY-3-8910 / YM2149 host test's evidence (developer evidence, not part of the build):

  1. Pitch, from the tables alone (always): per variant the expected A-4, every note's tone period, frequency and
     cents error against 12-TET A440 from the variant's lowest note (the CPC: C-0) to B-7 (each period the nearest
     integer to the ideal one, the table's rounding aside), the top octave's coarseness (the CPC's 1 MHz clock gives
     B-7 a period of 16), and the buzzer: the auto envelope period of every note for the saw and triangle shapes at
     J = 0 and J = +1 (each the nearest to the ideal), and the synced tone of the sync buzzer.
  2. With EVIDENCE_DIR (files the host test writes per variant key k = zx128, msx, atari-st, cpc: ay_<k>_image.bin,
     ay_<k>_trace.txt, ay_<k>.vgm, ay_<k>.wav): the Python reference player (ay/ay_ref.py) on the test image writes
     exactly the C player's per-tick trace and produces the same VGM bytes.
  3. The VGM, parsed here from the file format alone: header fields (version, AY8910 clock, chip type, flags, rate,
     data offset), end of file, one wait per tick summing to the header's sample count, the loop point on a command
     boundary holding a dump of R0-R12 equal to the registers the trace has written so far, and per-tick register
     writes equal to the trace.
  4. With --vgm2wav (libvgm's vgm2wav, an independent VGM player and AY8910/YM2149 emulation; needs numpy): the VGM
     rendered by it against the engine's own render (ay_<k>.wav; the CPC's is stereo, left A + B and right B + C,
     while the VGM declares one mixed output: both renders are compared as the mean of their two sides): loudness
     envelope correlation, dominant pitch class, level ratio.

    python3 tools/audio/ay_reference_check.py [EVIDENCE_DIR] [--vgm2wav PATH] [--variant zx128|msx|atari-st|cpc]
Prints PASS/FAIL per check; exit status 1 on any failure."""
import argparse
import math
import os
import struct
import subprocess
import sys
import tempfile
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "ay"))
import ay_ref  # noqa: E402

failures = 0


def check(ok, what):
    global failures
    print(("PASS " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


NOTE_NAMES = ["C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-"]


def note_name(n):
    return "%s%d" % (NOTE_NAMES[n % 12], n // 12)


def note_hz(n):
    return 440.0 * 2 ** ((n - 57) / 12)


def cents(f, ref):
    return 1200 * math.log2(f / ref)


# ---- 1. pitch -----------------------------------------------------------------------------------------------------

def pitch_evidence(variant):
    v = variant
    clock = ay_ref.clock_hz(v)
    tolerance = 0.5 + 1.0 / 256  # the period's rounding plus the table's (1/256 of a period at octave 0)
    lo = ay_ref.lowest_note(v)
    print("%s: chip clock %.2f Hz (%d / %d), tick %.4f Hz (%d master clocks)" % (
        ay_ref.NAMES[v], clock, ay_ref.MASTER_HZ[v], ay_ref.AY_DIV[v], ay_ref.MASTER_HZ[v] / ay_ref.FRAME_DIV[v],
        ay_ref.FRAME_DIV[v]))
    tp = ay_ref.tone_period(v, 57 * 64)
    a4 = ay_ref.tone_hz(v, tp)
    print("  A-4: TP %d = %.2f Hz (%+.2f cents)" % (tp, a4, cents(a4, 440.0)))
    check(abs(cents(a4, 440.0)) < 5, "%s: A-4 within 5 cents of 440 Hz" % ay_ref.KEYS[v])
    if lo == 0:  # the whole range is reachable (the CPC's 1 MHz clock)
        check(ay_ref.tone_period(v, 0) <= ay_ref.TONE_PERIOD_MAX
              and (ay_ref.DIV[v][0] + 128) >> 8 <= ay_ref.TONE_PERIOD_MAX,
              "%s: lowest note C-0 (TP %d, no clamping anywhere)" % (ay_ref.KEYS[v], ay_ref.tone_period(v, 0)))
    else:
        check(ay_ref.tone_period(v, (lo - 1) * 64) == ay_ref.TONE_PERIOD_MAX
              and (ay_ref.DIV[v][((lo - 1) * 64) % 768] + (128 << ((lo - 1) * 64 // 768))) >> (8 + (lo - 1) * 64 // 768)
              > ay_ref.TONE_PERIOD_MAX,
              "%s: lowest note %s (the note below clamps at period 4095)" % (ay_ref.KEYS[v], note_name(lo)))
    print("  note  TP     Hz        cents   saw EP (J0)  cents   tri EP (J0)  cents   sync TP (tri J0)")
    worst_tone = worst_saw = worst_tri = worst_top = 0.0
    tone_ok = env_ok = sync_ok = True
    for n in range(lo, ay_ref.NOTES):
        p = n * 64
        f = note_hz(n)
        t = ay_ref.tone_period(v, p)
        ideal = clock / (16 * f)
        tone_ok &= abs(t - ideal) <= tolerance
        hz = ay_ref.tone_hz(v, t)
        worst_tone = max(worst_tone, abs(cents(hz, f)))
        if n >= 84:
            worst_top = max(worst_top, abs(cents(hz, f)))
        row = "  %-4s %5d %9.2f %+8.2f" % (note_name(n), t, hz, cents(hz, f))
        for shape, j in ((8, 0), (10, 0), (8, 1), (14, 1)):
            ep = ay_ref.auto_envelope_period(v, p, j, shape)
            target = f * 2 ** j
            cycle = 512 if ay_ref.triangle(shape) else 256
            ideal_ep = clock / (cycle * target)
            if 1 < ep < ay_ref.ENV_PERIOD_MAX:
                env_ok &= abs(ep - ideal_ep) <= tolerance
            e = cents(ay_ref.envelope_hz(v, ep, shape), target)
            if j == 0 and shape == 8:
                worst_saw = max(worst_saw, abs(e))
                row += "  %6d %+8.2f" % (ep, e)
            elif j == 0:
                worst_tri = max(worst_tri, abs(e))
                row += "  %6d %+8.2f" % (ep, e)
        ep = ay_ref.auto_envelope_period(v, p, 0, 10)
        st = ay_ref.synced_tone_period(ep, 0, 10)
        sync_ok &= st == min(ay_ref.TONE_PERIOD_MAX, ep * 32)
        row += "  %6d (%.2f Hz)" % (st, ay_ref.tone_hz(v, st))
        print(row)
    check(tone_ok, "%s: every note %s..B-7 has the nearest tone period (worst %.2f cents)" % (
        ay_ref.KEYS[v], note_name(lo), worst_tone))
    top = [ay_ref.tone_period(v, n * 64) for n in (84, 95)]
    print("  top octave C-7..B-7: tone periods %d..%d, worst %.2f cents (12-bit periods are coarse up high: one period "
          "step at B-7 is %.0f cents)" % (top[0], top[1], worst_top, cents(top[1], top[1] - 1)))
    check(worst_top <= cents(top[1], top[1] - 0.5) + 0.01,
          "%s: top octave within half a period step of 12-TET (worst %.2f cents)" % (ay_ref.KEYS[v], worst_top))
    check(env_ok, "%s: every auto envelope period (saw/triangle, J 0 and +1) is the nearest (worst %.2f / %.2f cents "
          "at J 0)" % (ay_ref.KEYS[v], worst_saw, worst_tri))
    check(sync_ok, "%s: the synced tone period is 32 x the triangle's envelope period (one exact ratio)" % ay_ref.KEYS[v])
    ep = ay_ref.auto_envelope_period(v, 57 * 64, 0, 8)
    print("  buzzer (shape 8, J00) at A-4: EP %d = %.2f Hz; J01: EP %d = %.2f Hz" % (
        ep, ay_ref.envelope_hz(v, ep, 8), ay_ref.auto_envelope_period(v, 57 * 64, 1, 8),
        ay_ref.envelope_hz(v, ay_ref.auto_envelope_period(v, 57 * 64, 1, 8), 8)))


# ---- 2/3. trace and VGM -------------------------------------------------------------------------------------------

def read_trace(path):
    with open(path) as f:
        return [line.rstrip("\n") for line in f if line.strip()]


def parse_trace_line(line):
    """((order, row, tick), [(reg, val)]) of one trace line."""
    head, _, rest = line.partition(":")
    pos = tuple(int(x) for x in head.split())
    writes = []
    for token in rest.split():
        reg, _, val = token.partition("=")
        writes.append((int(reg, 16), int(val, 16)))
    return pos, writes


def parse_vgm(data):
    """(header dict, events [("w", reg, val) | ("t", samples)], command offset -> event index, end)."""
    h = {
        "eof": struct.unpack_from("<I", data, 0x04)[0] + 4,
        "version": struct.unpack_from("<I", data, 0x08)[0],
        "samples": struct.unpack_from("<I", data, 0x18)[0],
        "loop_offset": struct.unpack_from("<I", data, 0x1C)[0],
        "loop_samples": struct.unpack_from("<I", data, 0x20)[0],
        "rate": struct.unpack_from("<I", data, 0x24)[0],
        "data": 0x34 + struct.unpack_from("<I", data, 0x34)[0],
        "ay_clock": struct.unpack_from("<I", data, 0x74)[0],
        "ay_type": data[0x78],
        "ay_flags": data[0x79],
        "other_clocks": [struct.unpack_from("<I", data, at)[0] for at in range(0x0C, 0x18, 4)]
                        + [struct.unpack_from("<I", data, at)[0] for at in range(0x2C, 0x34, 4)],
    }
    at, events, offsets = h["data"], [], {}
    while True:
        offsets[at] = len(events)
        cmd = data[at]
        if cmd == 0xA0:
            events.append(("w", data[at + 1], data[at + 2]))
            at += 3
        elif cmd == 0x61:
            events.append(("t", struct.unpack_from("<H", data, at + 1)[0]))
            at += 3
        elif cmd == 0x62:
            events.append(("t", 735))
            at += 1
        elif cmd == 0x63:
            events.append(("t", 882))
            at += 1
        elif 0x70 <= cmd <= 0x7F:
            events.append(("t", (cmd & 15) + 1))
            at += 1
        elif cmd == 0x66:
            break
        else:
            raise ValueError("unexpected VGM command %02X at %X" % (cmd, at))
    return h, events, offsets, at + 1


def check_variant(directory, key):
    v = ay_ref.KEYS.index(key)
    image_path = os.path.join(directory, "ay_%s_image.bin" % key)
    if not os.path.exists(image_path):
        print("SKIP %s: no %s" % (key, image_path))
        return
    with open(image_path, "rb") as f:
        image = ay_ref.Image(f.read(), v)
    trace = read_trace(os.path.join(directory, "ay_%s_trace.txt" % key))
    ref = [ay_ref.format_tick(pos, w) for pos, w in ay_ref.trace(image, len(trace))]
    same = sum(1 for a, b in zip(trace, ref) if a == b)
    first = next((k for k, (a, b) in enumerate(zip(trace, ref)) if a != b), None)
    check(same == len(trace) == len(ref), "%s: reference player = engine trace in %d/%d ticks%s" % (
        key, same, len(trace), "" if first is None else " (first difference at tick %d:\n  engine    %s\n  reference %s)"
        % (first, trace[first], ref[first])))

    vgm_path = os.path.join(directory, "ay_%s.vgm" % key)
    with open(vgm_path, "rb") as f:
        vgm = f.read()
    h, events, offsets, end = parse_vgm(vgm)
    max_ticks = sum(1 for e in events if e[0] == "t")
    mine, info = ay_ref.vgm(image, max_ticks if not h["loop_offset"] else max(max_ticks + 1, 100000))
    check(mine == vgm, "%s: reference VGM = engine VGM (%d bytes, %d ticks)" % (key, len(vgm), max_ticks))
    check(vgm[:4] == b"Vgm " and h["version"] == 0x171 and h["data"] == 0x100 and h["ay_clock"] == ay_ref.VGM_CLOCK[v]
          and h["ay_type"] == ay_ref.VGM_TYPE[v] and h["ay_flags"] == 0x01 and h["rate"] == ay_ref.VGM_RATE[v]
          and not any(h["other_clocks"]) and vgm[0x7A] == 0 and vgm[0x7B] == 0,
          "%s: VGM 1.71 header: AY8910 type 0x%02X, %d Hz, legacy mono output, %d Hz rate, data at 0x100, no other chip"
          % (key, ay_ref.VGM_TYPE[v], ay_ref.VGM_CLOCK[v], ay_ref.VGM_RATE[v]))
    check(h["eof"] == len(vgm) == end, "%s: end-of-file offset" % key)
    waits = [e[1] for e in events if e[0] == "t"]
    check(sum(waits) == h["samples"], "%s: %d waits sum to %d samples (%.2f s)" % (
        key, len(waits), h["samples"], h["samples"] / ay_ref.VGM_SAMPLE_RATE))
    # Each tick's wait from the exact ratio: 44100 frame_div / master per tick, accumulated.
    acc, ok = 0, True
    for n in waits:
        acc += ay_ref.VGM_SAMPLE_RATE * ay_ref.FRAME_DIV[v]
        ok &= n == acc // ay_ref.MASTER_HZ[v]
        acc %= ay_ref.MASTER_HZ[v]
    check(ok, "%s: per-tick waits = 44100 x %d / %d accumulated exactly (%s samples)" % (
        key, ay_ref.FRAME_DIV[v], ay_ref.MASTER_HZ[v], "/".join(str(x) for x in sorted(set(waits)))))
    loop_cmd = 0x1C + h["loop_offset"]
    check(h["loop_offset"] and loop_cmd in offsets
          and h["loop_samples"] == sum(e[1] for e in events[offsets[loop_cmd]:] if e[0] == "t"),
          "%s: loop point on a command boundary, %d samples to the end" % (key, h["loop_samples"]))

    # Expected stream from the engine's trace: the reset batch (R7 = io | 0x3F), then per tick (dump of R0-R12 at the
    # first arrival at the loop order's row 0) its writes and one wait.
    regs = [0] * 13
    expected = [("w", 7, ay_ref.IO_BITS[v] | 0x3F)]
    regs[7] = ay_ref.IO_BITS[v] | 0x3F
    loop_seen = False
    ticks = min(len(trace), len(waits))
    for k in range(ticks):
        pos, writes = parse_trace_line(trace[k])
        if pos == (image.loop, 0, 0) and not loop_seen:
            loop_seen = True
            if offsets.get(loop_cmd) != len(expected):
                check(False, "%s: loop point at the dump before tick %d" % (key, k))
            expected += [("w", r, regs[r]) for r in range(13)]
        for reg, val in writes:
            expected.append(("w", reg, val))
            if reg < 13:
                regs[reg] = val
        expected.append(("t", waits[k]))
    check(events[:len(expected)] == expected,
          "%s: VGM stream = engine trace: reset batch, loop dump of R0-R12, writes, one wait per tick (%d ticks, %d events)"
          % (key, ticks, len(expected)))
    check(not any(e[0] == "w" and e[1] > 13 for e in events), "%s: only R0-R13 are written" % key)
    r13 = [e for e in events if e[0] == "w" and e[1] == 13]
    check(all(8 <= e[2] <= 15 for e in r13), "%s: %d R13 writes, all shapes 8..15 (%s)" % (
        key, len(r13), " ".join("%X" % s for s in sorted({e[2] for e in r13}))))
    return vgm_path


# ---- 4. libvgm --------------------------------------------------------------------------------------------------

def read_wav(path):
    import numpy as np
    with wave.open(path) as w:
        n, ch, rate = w.getnframes(), w.getnchannels(), w.getframerate()
        x = np.frombuffer(w.readframes(n), dtype=np.int16).reshape(-1, ch).astype(float) / 32768
    return x.mean(axis=1), rate


def compare_audio(key, engine_wav, vgm_path, vgm2wav):
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
    freqs = np.fft.rfftfreq(w, 1 / rate)
    band = (freqs > 40) & (freqs < 4000)
    same_pc = counted = 0
    for i in range(0, n - w, w):
        sa = np.abs(np.fft.rfft(a[i:i + w] * np.hanning(w)))
        sb = np.abs(np.fft.rfft(b[i:i + w] * np.hanning(w)))
        if sa.max() < 1e-3 or sb.max() < 1e-3:
            continue
        pa = freqs[band][np.argmax(sa[band])]
        pb = freqs[band][np.argmax(sb[band])]
        counted += 1
        same_pc += round(12 * np.log2(pa / pb)) % 12 == 0
    level = np.sqrt(np.mean(b[:n] ** 2)) / max(1e-9, np.sqrt(np.mean(a[:n] ** 2)))
    pc = same_pc / max(1, counted)
    print("  %s: libvgm (the VGM, its own AY/YM emulation) vs engine: envelope correlation %.3f (lag %d ms), same pitch "
          "class %.0f%% of %d windows, level ratio %.2f" % (key, env, lag * 20, 100 * pc, counted, level))
    check(env > 0.8, "%s: loudness envelope correlation with libvgm > 0.8" % key)
    check(pc > 0.7, "%s: dominant pitch class agrees in > 70%% of windows" % key)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir", nargs="?")
    ap.add_argument("--vgm2wav")
    ap.add_argument("--variant", choices=ay_ref.KEYS)
    args = ap.parse_args()
    keys = [args.variant] if args.variant else list(ay_ref.KEYS)
    for key in keys:
        pitch_evidence(ay_ref.KEYS.index(key))
    if args.dir:
        for key in keys:
            vgm_path = check_variant(args.dir, key)
            if vgm_path and args.vgm2wav:
                wav = os.path.join(args.dir, "ay_%s.wav" % key)
                try:
                    import numpy  # noqa: F401
                except ImportError:
                    print("SKIP %s: libvgm comparison needs numpy" % key)
                    continue
                if not os.path.exists(wav):
                    print("SKIP %s: no %s" % (key, wav))
                    continue
                compare_audio(key, wav, vgm_path, args.vgm2wav)
    print("FAIL" if failures else "PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
