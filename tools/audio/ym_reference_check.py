#!/usr/bin/env python3
"""Independent checks of the YM2612 host test's evidence (developer evidence, not part of the build):

  1. The Python reference player (ym/ym_ref.py) on the test image (ym_image.bin) writes exactly the C player's
     per-tick trace (ym_trace.txt) and produces the same VGM bytes (ym_song.vgm).
  2. The VGM, parsed here from the file format alone: header fields, loop point on a command boundary, waits
     summing to the header's sample count, and per-tick register writes equal to the trace.
  3. With --xgmtool (SGDK xgmtool, tools/audio/fetch_sgdk.sh): its conversion of that VGM equals the engine's .xgm
     and .bin (XGC) exports (ym_song.xgm, ym_song.bin).
  4. With --vgm2wav (libvgm's vgm2wav with a local patch: env VGM2WAV_YM2612_CORE=NUKE|GPGX|GENS selects its YM2612
     core; its SN76489 is MAME's; played from the VGM without a driver): the VGM rendered by that independent player
     and chip cores against the engine's own 30 s render (ym_song.wav; ymfm and Thor Tracker's PSG behind SGDK's XGM
     driver): loudness envelope correlation, log-spectrum correlation, dominant pitch class, level ratio; checked
     against Nuked-OPN2, Genesis Plus GX reported.

    python3 tools/audio/ym_reference_check.py EVIDENCE_DIR [--xgmtool PATH] [--vgm2wav PATH]   (4 needs numpy)
Prints PASS/FAIL per check; exit status 1 on any failure."""
import argparse
import os
import struct
import subprocess
import sys
import tempfile
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "ym"))
import ym_ref  # noqa: E402

failures = 0


def check(ok, what):
    global failures
    print(("PASS " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


def read_trace(path):
    with open(path) as f:
        return [line.rstrip("\n") for line in f]


def parse_write(token):
    reg, _, val = token.partition("=")
    if reg == "PSG":
        return (ym_ref.PSG, 0, int(val, 16))
    return (int(reg[0]), int(reg[1:], 16), int(val, 16))


def trace_writes(line):
    """(key-off phase writes, other writes) of one trace line."""
    rest = line.partition(":")[2]
    first, bar, second = rest.partition("|")
    parse = lambda text: [parse_write(w) for w in text.split()]  # noqa: E731
    return (parse(first), parse(second)) if bar else ([], parse(first))


def parse_vgm(data):
    """(header dict, events [("w", kind, reg, val) | ("t", samples)] with adjacent waits merged, command offsets, end)."""
    h = {
        "eof": struct.unpack_from("<I", data, 4)[0] + 4,
        "version": struct.unpack_from("<I", data, 8)[0],
        "sn76489": struct.unpack_from("<I", data, 0x0C)[0],
        "samples": struct.unpack_from("<I", data, 0x18)[0],
        "loop_offset": struct.unpack_from("<I", data, 0x1C)[0],
        "loop_samples": struct.unpack_from("<I", data, 0x20)[0],
        "rate": struct.unpack_from("<I", data, 0x24)[0],
        "sn_feedback": struct.unpack_from("<H", data, 0x28)[0],
        "sn_width": data[0x2A],
        "ym2612": struct.unpack_from("<I", data, 0x2C)[0],
        "data": 0x34 + struct.unpack_from("<I", data, 0x34)[0],
    }
    at, events, offsets = h["data"], [], {}
    while True:
        offsets[at] = len(events)
        cmd = data[at]
        if cmd in (0x52, 0x53):
            events.append(("w", cmd - 0x52, data[at + 1], data[at + 2]))
            at += 3
            continue
        if cmd == 0x50:
            events.append(("w", ym_ref.PSG, 0, data[at + 1]))
            at += 2
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


def read_wav(path):
    import numpy as np
    with wave.open(path) as w:
        n, ch, rate = w.getnframes(), w.getnchannels(), w.getframerate()
        x = np.frombuffer(w.readframes(n), dtype=np.int16).reshape(-1, ch).astype(float) / 32768
    return x.mean(axis=1), rate


def highpass(x, rate):
    """First-order 20 Hz high-pass: libvgm's Nuked core keeps the YM2612 DAC's offset (DC) that the engine's resampler
    removes."""
    import numpy as np
    k = np.exp(-2 * np.pi * 20 / rate)
    y = np.empty_like(x)
    prev_x = prev_y = 0.0
    for i, v in enumerate(x):  # small enough for 30 s at 48 kHz
        prev_y = k * (prev_y + v - prev_x)
        prev_x = v
        y[i] = prev_y
    return y


def compare_audio(engine_wav, vgm_path, vgm2wav, core, seconds):
    import numpy as np
    a, rate = read_wav(engine_wav)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "vgm.wav")
        subprocess.run([vgm2wav, "--samplerate", str(rate), "--loops", "5", "--fade", "0", vgm_path, out],
                       check=True, stdout=subprocess.DEVNULL, env=dict(os.environ, VGM2WAV_YM2612_CORE=core))
        b, _ = read_wav(out)
    # The engine ticks at the console's vertical interrupt (59.92 Hz), the VGM at 60 Hz: map the engine's time onto the
    # VGM's (t -> t x 59.92 / 60) so the two renders stay aligned over the 30 s.
    speed = 53693175 / (3420 * 262) / 60
    t = np.arange(len(a)) * speed
    b = np.interp(t[t < len(b) - 1], np.arange(len(b)), b)
    n = min(len(a), len(b), int(seconds * rate))
    a, b = highpass(a[:n], rate), highpass(b[:n], rate)
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
    print("  libvgm %s YM2612 + MAME SN76489 (the VGM, no driver) vs engine (ymfm + PSG behind the XGM driver): "
          "envelope correlation %.3f (lag %d ms), log-spectrum correlation %.3f, same pitch class %.0f%% of %d windows, "
          "level ratio %.2f" % (core, env, lag * 20, float(np.mean(spec)), 100.0 * same_pc / counted, counted, level))
    return env, float(np.mean(spec)), same_pc / counted


def xgmtool_convert(xgmtool, vgm_path, ext):
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "song.vgm")
        with open(vgm_path, "rb") as f, open(src, "wb") as g:
            g.write(f.read())
        out = os.path.join(tmp, "song." + ext)
        subprocess.run([xgmtool, src, out, "-s"], check=True, stdout=subprocess.DEVNULL, cwd=tmp)
        with open(out, "rb") as f:
            return f.read()


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--xgmtool")
    ap.add_argument("--vgm2wav")
    args = ap.parse_args()
    with open(os.path.join(args.dir, "ym_image.bin"), "rb") as f:
        image = ym_ref.Image(f.read())
    trace = read_trace(os.path.join(args.dir, "ym_trace.txt"))
    ref = [ym_ref.format_tick(*t) for t in ym_ref.trace(image, len(trace))[1:]]
    same = sum(1 for a, b in zip(trace, ref) if a == b)
    check(same == len(trace), "reference player = engine trace in %d/%d ticks" % (same, len(trace)))
    vgm_path = os.path.join(args.dir, "ym_song.vgm")
    with open(vgm_path, "rb") as f:
        vgm = f.read()
    mine, info = ym_ref.vgm(image, 36000)
    check(mine == vgm, "reference VGM = engine VGM (%d bytes)" % len(vgm))
    h, events, offsets, end = parse_vgm(vgm)
    check(vgm[:4] == b"Vgm " and h["version"] == 0x171 and h["ym2612"] == 7670453 and h["sn76489"] == 3579545
          and h["sn_feedback"] == 9 and h["sn_width"] == 16 and h["rate"] == 60 and h["data"] == 0x100,
          "VGM 1.71 header: YM2612 7670453 Hz, SN76489 3579545 Hz (feedback 9, width 16), 60 Hz, data at 0x100")
    check(h["eof"] == len(vgm) == end, "end-of-file offset")
    check(sum(e[1] for e in events if e[0] == "t") == h["samples"],
          "waits sum to %d samples (%.2f s)" % (h["samples"], h["samples"] / 44100))
    loop_cmd = 0x1C + h["loop_offset"]
    check(h["loop_offset"] and loop_cmd in offsets and h["loop_samples"] == info["loop_samples"],
          "loop point on a command boundary, %d samples" % h["loop_samples"])
    # Expected stream from the engine's trace: the machine reset writes and the reset batch, then per tick its
    # key-off phase, a one-sample wait if that phase is not empty, the other writes and the rest of the tick; the loop
    # point adds a dump of the register state (checked by count and position only here).
    expected = [("w",) + w for w in ym_ref.MACHINE_RESET + ym_ref.trace(image, 0)[0][1]]
    dump_at, dump_len = None, 1 + 6 * 32 + 3 * 3 + 2
    for k in range(info["ticks"]):
        if trace[k].startswith("%d 0 0:" % image.loop) and dump_at is None:
            dump_at = len(expected)
            expected += [None] * dump_len
        offs, rest = trace_writes(trace[k])
        n = 735
        expected += [("w",) + w for w in offs]
        if offs:
            expected.append(("t", 1))
            n -= 1
        expected += [("w",) + w for w in rest]
        if expected[-1] and expected[-1][0] == "t":
            expected[-1] = ("t", expected[-1][1] + n)
        else:
            expected.append(("t", n))
    got = [None if dump_at is not None and dump_at <= i < dump_at + dump_len else e for i, e in enumerate(events)]
    check(got == expected, "VGM stream = engine trace: key-off phase, 1-sample gap, writes, waits (%d ticks, %d events)"
          % (info["ticks"], len(events)))
    check(dump_at is not None and offsets[loop_cmd] == dump_at, "loop point = the register dump before the loop order")
    if args.xgmtool:
        for ext, name in (("xgm", "ym_song.xgm"), ("bin", "ym_song.bin")):
            with open(os.path.join(args.dir, name), "rb") as f:
                engine = f.read()
            theirs = xgmtool_convert(args.xgmtool, vgm_path, ext)
            check(theirs == engine, "xgmtool's %s of the VGM = the engine's export (%d bytes)" % (ext.upper(), len(engine)))
    if args.vgm2wav:
        wav = os.path.join(args.dir, "ym_song.wav")
        # Nuked-OPN2 (die-accurate) is the reference; Genesis Plus GX (libvgm's default) differs from both on some
        # operator setups (the test song's bell and E-piano on FM 2: louder there by up to 3x), so it is informational.
        # The song's first pass (the VGM's length before the loop wraps): from there the VGM replays its first pass and
        # the engine's player plays on (slides, volumes and LFO left running across the loop differ, by design).
        first = h["samples"] / 44100
        compare_audio(wav, vgm_path, args.vgm2wav, "GPGX", first)
        env, spec, pc = compare_audio(wav, vgm_path, args.vgm2wav, "NUKE", first)
        # Other emulations, the VGM's 60 Hz against the console's 59.92 Hz, the driver's write pacing within a frame and
        # its lag behind heavy frames (start, loop point): envelopes and spectra agree closely, samples do not.
        check(env > 0.8, "loudness envelope correlation with Nuked-OPN2 > 0.8")
        check(spec > 0.75, "log-spectrum correlation with Nuked-OPN2 > 0.75 (cores differ in detail; per channel 0.52-0.82)")
        check(pc > 0.7, "dominant pitch class agrees in > 70% of windows")
    print("FAIL" if failures else "PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
