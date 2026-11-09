#!/usr/bin/env python3
"""Independent checks of the POKEY host test's evidence (developer evidence, not part of the build):

  1. The Python reference player (pokey/pokey_ref.py, on the reference chip pokey/pokey_chip_ref.py for the
     timers' interrupt cycles) reproduces the engine's traces line for line: pokey_song.bin -> pokey_trace.txt
     (NTSC), pokey_song_pal.bin -> pokey_trace_pal.txt (PAL) and pokey_porta.bin -> pokey_trace_porta.txt (sticky
     portamento over ended, playing and looping samples); the first difference is printed with context.
  2. The reference VGM equals pokey_song.vgm byte for byte (max ticks 36000, as the host test).
  3. The VGM parsed from the file format alone: 1.71 header, POKEY clock at 0xB0, frame rate, data at 0x100,
     end-of-file offset, waits summing to the header's samples, the loop point on a command boundary and followed
     by the register dump (SKCTL, AUDCTL, AUDF1, AUDC1 .. AUDF4, AUDC4) whose values equal the register state the
     engine's trace reaches before the loop order's first tick, the loop length; and the VGM's writes (all but
     IRQEN, with their 44.1 kHz times) equal the engine's reset batch and trace writes.
  4. The chip: every case of pokey_chip_cases.txt replayed through the reference chip model stepping every cycle
     (and event-driven: both must agree) gives exactly the engine's output changes; the reference's 4- and 5-bit
     generators give the patterns printed in the Altirra Hardware Reference Manual.
  5. With --vgm2wav PATH (libvgm's vgm2wav, whose POKEY core is MAME's, an independent emulation): the VGM
     rendered by that player against the engine's 30 s render pokey_song.wav: loudness envelope correlation
     (20 ms windows, DC removed), log-spectrum correlation, dominant pitch class agreement (100 ms windows),
     level ratio. Needs numpy.

    python3 tools/audio/pokey_reference_check.py EVIDENCE_DIR [--vgm2wav PATH]
Prints PASS/FAIL per check, then PASS or FAIL; exit status 1 on any failure."""
import argparse
import os
import struct
import subprocess
import sys
import tempfile
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "pokey"))
import pokey_chip_ref  # noqa: E402
import pokey_ref  # noqa: E402

VGM_MAX_TICKS = 36000
DUMP_ORDER = (0xF, 0x8, 0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7)

failures = 0


def check(ok, what):
    global failures
    print(("PASS " if ok else "FAIL ") + what)
    failures += 0 if ok else 1
    return ok


def read_lines(path):
    with open(path) as f:
        return [line.rstrip("\n") for line in f if line.strip()]


def parse_line(line):
    """("T", tick, (order, row, tick), cycle, writes) or ("I", None, None, cycle, writes, timers)."""
    head, _, rest = line.partition(":")
    f = head.split()
    writes = [(int(w.split("=")[0], 16), int(w.split("=")[1], 16)) for w in rest.split()]
    if f[0] == "T":
        return {"kind": "T", "tick": int(f[1]), "pos": (int(f[2]), int(f[3]), int(f[4])), "cycle": int(f[5]),
                "writes": writes}
    return {"kind": "I", "cycle": int(f[1]), "timers": int(f[2], 16), "writes": writes}


def compare_trace(name, image, path):
    engine = read_lines(path)
    ticks = sum(1 for line in engine if line.startswith("T "))
    ref = pokey_ref.trace(image, ticks)
    for k in range(max(len(engine), len(ref))):
        e = engine[k] if k < len(engine) else "<end>"
        r = ref[k] if k < len(ref) else "<end>"
        if e != r:
            print("  first difference at line %d of %s:" % (k + 1, os.path.basename(path)))
            for j in range(max(0, k - 3), k):
                print("    both:      %s" % engine[j])
            print("    engine:    %s" % e)
            print("    reference: %s" % r)
            return check(False, "%s: reference = engine trace (%d ticks, %d lines)" % (name, ticks, len(engine)))
    irqs = sum(1 for line in engine if line.startswith("I "))
    writes = sum(len(parse_line(line)["writes"]) for line in engine)
    return check(True, "%s: reference = engine trace, %d ticks, %d interrupts, %d writes (%d lines)"
                 % (name, ticks, irqs, writes, len(engine)))


def parse_vgm(data):
    """(header, events [("w", reg, val, sample_time, offset) | ("t", n)], command offsets, end offset)."""
    h = {
        "eof": struct.unpack_from("<I", data, 0x04)[0] + 4,
        "version": struct.unpack_from("<I", data, 0x08)[0],
        "gd3": struct.unpack_from("<I", data, 0x14)[0],
        "samples": struct.unpack_from("<I", data, 0x18)[0],
        "loop_offset": struct.unpack_from("<I", data, 0x1C)[0],
        "loop_samples": struct.unpack_from("<I", data, 0x20)[0],
        "rate": struct.unpack_from("<I", data, 0x24)[0],
        "data": 0x34 + struct.unpack_from("<I", data, 0x34)[0],
        "pokey": struct.unpack_from("<I", data, 0xB0)[0],
    }
    at, time, writes, offsets = h["data"], 0, [], {}
    while True:
        offsets[at] = (len(writes), time)
        cmd = data[at]
        if cmd == 0xBB:
            writes.append((data[at + 1], data[at + 2], time, at))
            at += 3
            continue
        if cmd == 0x61:
            time += struct.unpack_from("<H", data, at + 1)[0]
            at += 3
        elif cmd == 0x62:
            time, at = time + 735, at + 1
        elif cmd == 0x63:
            time, at = time + 882, at + 1
        elif 0x70 <= cmd <= 0x7F:
            time, at = time + (cmd & 15) + 1, at + 1
        elif cmd == 0x66:
            break
        else:
            raise ValueError("unexpected VGM command %02X at %X" % (cmd, at))
    return h, writes, offsets, time, at + 1


def check_vgm(image, vgm, trace_path):
    mine, info = pokey_ref.vgm(image, VGM_MAX_TICKS)
    check(mine == vgm, "reference VGM = engine VGM (%d bytes vs %d)" % (len(mine), len(vgm)))
    h, writes, offsets, time, end = parse_vgm(vgm)
    check(vgm[:4] == b"Vgm " and h["version"] == 0x171 and h["data"] == 0x100 and h["gd3"] == 0,
          "VGM 1.71 header, data at 0x100, no GD3")
    check(h["pokey"] == pokey_ref.VGM_CLOCKS[image.machine] and h["rate"] == pokey_ref.VGM_RATES[image.machine],
          "POKEY clock %d Hz at 0xB0, rate %d" % (h["pokey"], h["rate"]))
    check(h["eof"] == len(vgm) == end, "end-of-file offset")
    check(time == h["samples"], "waits sum to %d samples (%.2f s)" % (h["samples"], h["samples"] / 44100))
    check(all(r <= 0x0F and r != 0x0E for r, _, _, _ in writes), "writes only to registers 0-F, never IRQEN")
    loop_cmd = 0x1C + h["loop_offset"]
    looped = h["loop_offset"] != 0 and loop_cmd in offsets
    check(looped, "loop point on a command boundary")
    dump_at = None
    if looped:
        dump_at, loop_time = offsets[loop_cmd]
        check(h["loop_samples"] == time - loop_time, "loop length %d samples (%.2f s)"
              % (h["loop_samples"], h["loop_samples"] / 44100))
        dump = writes[dump_at:dump_at + len(DUMP_ORDER)]
        check([w[0] for w in dump] == list(DUMP_ORDER) and all(w[2] == loop_time for w in dump),
              "the loop point is a register dump (SKCTL, AUDCTL, AUDF1, AUDC1 .. AUDF4, AUDC4)")
    # Expected writes from the engine itself: the reset batch at cycle 0, then every trace line's writes.
    reset = [(0xF, 0x00), (0x8, image.audctl)] + [(r, 0) for r in range(8)] + [(0xE, 0), (0xF, 0x03)]
    lines = [parse_line(line) for line in read_lines(trace_path)]
    expected, regs, loop_state = [], [0] * 16, None
    for reg, val in reset:
        regs[reg] = val
        if reg != 0xE:
            expected.append((reg, val, 0))
    for e in lines:
        if e["kind"] == "T" and loop_state is None and e["pos"] == (image.loop, 0, 0):
            loop_state = [regs[r] for r in DUMP_ORDER]
        for reg, val in e["writes"]:
            if reg != 9:
                regs[reg] = val
            if reg != 0xE:
                expected.append((reg, val, pokey_ref.vgm_samples(e["cycle"], image.machine)))
    stream = [(r, v, t) for k, (r, v, t, _) in enumerate(writes)
              if dump_at is None or not dump_at <= k < dump_at + len(DUMP_ORDER)]
    n = min(len(stream), len(expected))
    first = next((k for k in range(n) if stream[k] != expected[k]), None)
    if first is not None:
        print("  first difference at write %d: VGM %s, engine trace %s" % (first, stream[first], expected[first]))
    check(first is None and n > 0, "VGM writes and their times = the engine's reset batch and trace (%d writes "
          "compared%s)" % (n, ", the whole VGM" if n == len(stream) else ", the trace's span"))
    if looped:
        if loop_state is None:
            print("  (the loop order's first tick is beyond the trace: dump values not compared)")
        else:
            got = [w[1] for w in writes[dump_at:dump_at + len(DUMP_ORDER)]]
            check(got == loop_state, "dump values = the engine's registers before the loop order's first tick (%s)"
                  % " ".join("%02X" % v for v in got))
    return info


def read_wav(path):
    import numpy as np
    with wave.open(path) as w:
        n, ch, rate = w.getnframes(), w.getnchannels(), w.getframerate()
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
    starts = np.arange(0, n - win, win)
    # POKEY's output is a DC level per channel; the engine couples it to AC, MAME's core does not: the envelope
    # is the per-window standard deviation, which ignores the DC part.
    ea = np.array([a[i:i + win].std() for i in starts])
    eb = np.array([b[i:i + win].std() for i in starts])
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
    band = (freqs > 60) & (freqs < 4000)
    for i in range(0, n - w, w):
        xa, xb = a[i:i + w] - a[i:i + w].mean(), b[i:i + w] - b[i:i + w].mean()
        sa = np.abs(np.fft.rfft(xa * np.hanning(w)))
        sb = np.abs(np.fft.rfft(xb * np.hanning(w)))
        if sa.max() < 1e-3 or sb.max() < 1e-3:
            continue
        spec.append(np.corrcoef(np.log(sa[band] + 1e-4), np.log(sb[band] + 1e-4))[0, 1])
        pa = freqs[band][np.argmax(sa[band])]
        pb = freqs[band][np.argmax(sb[band])]
        counted += 1
        same_pc += round(12 * np.log2(pa / pb)) % 12 == 0
    level = (b[:n] - b[:n].mean()).std() / (a[:n] - a[:n].mean()).std()
    print("  libvgm (MAME POKEY) vs engine: envelope correlation %.3f (lag %d ms), log-spectrum correlation %.3f, "
          "same pitch class %.0f%% of %d windows, level ratio %.2f"
          % (env, lag * 20, float(np.mean(spec)) if spec else 0.0, 100.0 * same_pc / max(1, counted), counted,
             level))
    check(env > 0.8, "loudness envelope correlation > 0.8")
    check(bool(spec) and np.mean(spec) > 0.75, "log-spectrum correlation > 0.75")
    check(counted > 0 and same_pc / counted > 0.6, "dominant pitch class agrees in > 60% of windows")
    check(0.25 < level < 4, "level ratio within 0.25..4")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--vgm2wav")
    args = ap.parse_args()
    path = lambda name: os.path.join(args.dir, name)  # noqa: E731
    images = {}
    for name, image_file, trace_file in (("NTSC", "pokey_song.bin", "pokey_trace.txt"),
                                         ("PAL", "pokey_song_pal.bin", "pokey_trace_pal.txt")):
        with open(path(image_file), "rb") as f:
            images[name] = pokey_ref.Image(f.read())
        check(images[name].machine == (0 if name == "NTSC" else 1), "%s image declares %s" % (image_file, name))
        compare_trace(name, images[name], path(trace_file))
    with open(path("pokey_porta.bin"), "rb") as f:
        compare_trace("sticky portamento over samples", pokey_ref.Image(f.read()), path("pokey_trace_porta.txt"))
    with open(path("pokey_song.vgm"), "rb") as f:
        vgm = f.read()
    check_vgm(images["NTSC"], vgm, path("pokey_trace.txt"))
    check(pokey_chip_ref.manual_patterns_ok(), "4/5-bit generator patterns = the Altirra manual's")
    with open(path("pokey_chip_cases.txt")) as f:
        cases_text = f.read()
    report = lambda line: print("  " + line)  # noqa: E731
    bad, total = pokey_chip_ref.check_cases(cases_text, report)
    check(bad == 0 and total > 0, "chip cases: reference model = engine in %d/%d (per cycle and event-driven)"
          % (total - bad, total))
    if args.vgm2wav:
        compare_audio(path("pokey_song.wav"), path("pokey_song.vgm"), args.vgm2wav)
    print("FAIL" if failures else "PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
