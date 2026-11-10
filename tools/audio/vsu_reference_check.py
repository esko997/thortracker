#!/usr/bin/env python3
"""Independent checks of the Virtual Boy VSU host test's evidence (developer evidence, not part of the build):

  1. Tables: VSU_PERIOD_Q recomputed with 60-digit decimals and the vibrato sine equal the C literals; the noise
     register's sequence lengths per tap equal the Tech Scroll's (vsu/vsu_ref.py --check-tables).
  2. The Python reference player (vsu/vsu_ref.py, written from the spec) reproduces vsu_trace.txt line for line from
     vsu_song.bin (the reset batch, then per tick its position and writes); the first difference is printed.
  3. The Python reference chip (written from the Tech Scroll in absolute clock times, not from vsu_chip.c) with the
     reference player gives exactly the engine's 10-bit digital output over the first 300 ticks (vsu_out.txt).
  4. The reference VGM equals vsu_song.vgm byte for byte (max ticks 36000, as the host test), and the VGM parsed from
     the file format alone: 1.71 header, VSU clock 5000000 at 0xC4, 50 Hz, data at 0x100, EOF offset, only 0xC7 /
     0x63 / 0x66 commands, every write to a mapped register, 0x63 x 882 = the header's samples, the loop point on a
     command boundary at the start of a tick, the loop length, and the writes = the trace's (reset batch, ticks).
  5. The chip: every case of vsu_chip_cases.txt replayed through the reference chip gives exactly the engine's 120-clock
     output and active-channel changes.
  6. With --vgm2wav PATH (libvgm's vgm2wav; its VSU core is Mednafen-derived, amended by Floogle in 2025, an
     independent decoder): the VGM rendered by that player against the engine's 30 s render vsu_song.wav: loudness
     envelope correlation (20 ms windows), log-spectrum correlation, dominant pitch class agreement, level ratio.
     Needs numpy. Expected differences (Thor Tracker's amendments, docs/AUDIO_ENGINE.md "Sources and the core"): libvgm
     resets the noise register to 1 (we: 0), wraps the envelope 0 <-> 15 on repeat (we: one frame at the limit, then
     the written value), copies the written frequency on S5INT (we: keep the current one), applies a "modulation lock"
     on S5FQ writes, models no RC high-pass and mixes per channel without the 10-bit truncation; its sample timing
     differs. These loose regression bounds do not establish hardware waveform equivalence.

    python3 tools/audio/vsu_reference_check.py EVIDENCE_DIR [--vgm2wav PATH]
Prints PASS/FAIL per check, then PASS or FAIL; exit status 1 on any failure."""
import argparse
import os
import struct
import subprocess
import sys
import tempfile
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "vsu"))
import vsu_ref  # noqa: E402

VGM_MAX_TICKS = 36000
OUT_TICKS = 300

failures = 0


def check(ok, what):
    global failures
    print(("PASS " if ok else "FAIL ") + what)
    failures += 0 if ok else 1
    return ok


def read_lines(path):
    with open(path) as f:
        return [line.rstrip("\n") for line in f if line.strip() and not line.startswith("#")]


def compare_lines(what, engine, ref, name):
    for k in range(max(len(engine), len(ref))):
        e = engine[k] if k < len(engine) else "<end>"
        r = ref[k] if k < len(ref) else "<end>"
        if e != r:
            print("  first difference at line %d of %s:" % (k + 1, name))
            for j in range(max(0, k - 3), k):
                print("    both:      %s" % engine[j])
            print("    engine:    %s" % e)
            print("    reference: %s" % r)
            return check(False, what)
    return check(True, what)


def parse_writes(line):
    return [(int(w.split("=")[0], 16), int(w.split("=")[1], 16)) for w in line.partition(":")[2].split()]


def mapped(r):
    if r < 0xC0 or r == vsu_ref.SSTOP:
        return True
    if r < 0x100 or r > 0x15F:
        return False
    c, k = (r - 0x100) >> 4, r & 15
    return (k <= vsu_ref.RAM and not (k == vsu_ref.RAM and c == 5)) or (k == vsu_ref.SWP and c == 4)


def check_vgm(image, vgm, trace_lines):
    mine, info = vsu_ref.vgm(image, VGM_MAX_TICKS)
    if not check(mine == vgm, "reference VGM = vsu_song.vgm byte for byte (%d bytes, %d ticks)" % (len(vgm), info["ticks"])):
        n = min(len(mine), len(vgm))
        k = next((i for i in range(n) if mine[i] != vgm[i]), n)
        print("  first differing byte at 0x%X (reference %d bytes, engine %d)" % (k, len(mine), len(vgm)))
    u32 = lambda at: struct.unpack_from("<I", vgm, at)[0]  # noqa: E731
    check(vgm[:4] == b"Vgm " and u32(4) == len(vgm) - 4 and u32(8) == 0x171 and u32(0x24) == 50 and u32(0x34) == 0xCC
          and u32(0xC4) == 5000000, "header: 1.71, EOF offset, 50 Hz, data at 0x100, VSU clock 5000000")
    reset_batch = len(parse_writes(trace_lines[0]))
    at, writes, ticks, tick_start, bad, unmapped, ended, offsets = 0x100, [], 0, [reset_batch], False, 0, False, {}
    while at < len(vgm):
        offsets[at] = (len(writes), ticks)
        cmd = vgm[at]
        if cmd == 0xC7:
            r = vgm[at + 1] << 8 | vgm[at + 2]
            unmapped += not mapped(r)
            writes.append((r, vgm[at + 3]))
            at += 4
        elif cmd == 0x63:
            ticks += 1
            tick_start.append(len(writes))
            at += 1
        elif cmd == 0x66:
            ended = True
            at += 1
            break
        else:
            bad = True
            break
    check(ended and not bad and at == len(vgm), "only 0xC7, 0x63 and 0x66 commands, 0x66 at the end")
    check(unmapped == 0, "every write to a mapped VSU register")
    check(ticks * vsu_ref.VGM_TICK_SAMPLES == u32(0x18), "%d ticks x 882 = the header's %d samples" % (ticks, u32(0x18)))
    if u32(0x1C):
        loop_at = 0x1C + u32(0x1C)
        where = offsets.get(loop_at)
        check(where is not None, "the loop point is on a command boundary (0x%X)" % loop_at)
        if where is not None:
            check(where[0] == tick_start[where[1]] if where[1] < len(tick_start) else False,
                  "the loop point starts tick %d" % where[1])
            check(u32(0x20) == (ticks - where[1]) * vsu_ref.VGM_TICK_SAMPLES, "loop length %d samples" % u32(0x20))
    else:
        print("  (no loop within %d ticks)" % VGM_MAX_TICKS)
    engine = parse_writes(trace_lines[0])
    for line in trace_lines[1:min(len(trace_lines), ticks + 1)]:
        engine.extend(parse_writes(line))
    n = len(engine)
    check(writes[:n] == engine, "the VGM's writes = the trace's reset batch and %d ticks of writes" % (min(len(trace_lines) - 1, ticks)))


def read_wav(path):
    import numpy as np
    with wave.open(path) as w:
        n, ch, rate = w.getnframes(), w.getnchannels(), w.getframerate()
        x = np.frombuffer(w.readframes(n), dtype=np.int16).reshape(-1, ch).astype(float) / 32768
    return x.mean(axis=1), rate


def compare_audio(engine_wav, vgm_path, vgm2wav):
    import numpy as np
    a, rate = read_wav(engine_wav)
    # Undo only the documented 1/8 host headroom gain for this diagnostic comparison. Raw chip/VGM traces are
    # unchanged; production output is never amplified here. Keep the original correlation and level thresholds.
    a *= 8.0
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "vgm.wav")
        subprocess.run([vgm2wav, "--samplerate", str(rate), "--loops", "5", "--fade", "0", vgm_path, out],
                       check=True, stdout=subprocess.DEVNULL)
        b, _ = read_wav(out)
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    win = rate // 50
    starts = np.arange(0, n - win, win)
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
    print("  libvgm (Mednafen-derived VSU) vs engine: envelope correlation %.3f (lag %d ms), log-spectrum correlation "
          "%.3f, same pitch class %.0f%% of %d windows, headroom-normalized level ratio %.2f"
          % (env, lag * 20, float(np.mean(spec)) if spec else 0.0, 100.0 * same_pc / max(1, counted), counted, level))
    print("  (expected differences: noise reset 1 vs 0, envelope repeat wrap, S5INT frequency copy, modulation lock, "
          "no RC filter or 10-bit mixer; engine x8 for host headroom normalization; regression bounds only)")
    check(env > 0.7, "loudness envelope correlation > 0.7")
    check(bool(spec) and np.mean(spec) > 0.7, "log-spectrum correlation > 0.7")
    check(counted > 0 and same_pc / counted > 0.6, "dominant pitch class agrees in > 60% of windows")
    check(0.2 < level < 5, "level ratio within 0.2..5")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--vgm2wav")
    args = ap.parse_args()
    path = lambda name: os.path.join(args.dir, name)  # noqa: E731
    check(vsu_ref.check_tables(), "tables (above)")
    with open(path("vsu_song.bin"), "rb") as f:
        image = vsu_ref.Image(f.read())
    trace_lines = read_lines(path("vsu_trace.txt"))
    ticks = sum(1 for line in trace_lines if line.startswith("T "))
    compare_lines("reference player = engine trace (%d ticks)" % ticks, trace_lines, vsu_ref.trace(image, ticks),
                  "vsu_trace.txt")
    engine_out = read_lines(path("vsu_out.txt"))
    ref_out = ["%d %d %d" % x for x in vsu_ref.output(image, OUT_TICKS)]
    compare_lines("reference chip + player = engine digital output (%d ticks, %d changes)" % (OUT_TICKS, len(engine_out)),
                  engine_out, ref_out, "vsu_out.txt")
    with open(path("vsu_song.vgm"), "rb") as f:
        check_vgm(image, f.read(), trace_lines)
    with open(path("vsu_chip_cases.txt")) as f:
        results = vsu_ref.chip_cases(f.read())
    bad = 0
    for name, engine, ref in results:
        if engine != ref:
            bad += 1
            k = next((i for i in range(min(len(engine), len(ref))) if engine[i] != ref[i]), min(len(engine), len(ref)))
            print("  case %s: first difference at change %d: engine %s, reference %s"
                  % (name, k, engine[k] if k < len(engine) else "<end>", ref[k] if k < len(ref) else "<end>"))
    check(bad == 0 and results, "chip cases: reference chip = engine in %d/%d" % (len(results) - bad, len(results)))
    if args.vgm2wav:
        compare_audio(path("vsu_song.wav"), path("vsu_song.vgm"), args.vgm2wav)
    print("FAIL" if failures else "PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
