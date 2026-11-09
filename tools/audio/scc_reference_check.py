#!/usr/bin/env python3
"""Independent checks of the SCC host test's evidence (developer evidence, not part of the build):

  1. The Python reference player (scc/scc_ref.py) on the test image (scc_song.bin) writes exactly the C machine's
     batches (scc_trace.txt: the reset batch, then one line per tick) and produces the same VGM bytes for the SCC and
     SCC+ images (scc_song.vgm, scc_plus_song.vgm).
  2. A per-clock model of the chip (scc_ref.Chip: MAME's sound loop run one clock at a time, the test register bits of
     the specification) driven by the scripted writes of scc_chip_trace.txt reproduces every level-change event the
     C core's event renderer emitted, at the same clock.
  3. The VGMs, parsed here from the file format alone: header (version, data offset, rate, K051649 clock 1789773 with
     bit 31 for the K052539), waits summing to the header's sample count, the loop point on the register dump before
     the loop order, and the 0xD2 stream equal to the trace (engine trace for the SCC, reference trace for the SCC+).
  4. Pitch (needs numpy): scc_tone.wav (A-4 on the sine instrument) measured by FFT against 3579545 / (32 * 254) =
     440.397 Hz.
  5. With --vgm2wav (libvgm's vgm2wav, an independent VGM player with its own K051649 core): an A-4 tone VGM from the
     reference measured the same way on both chips (which also proves the header clock convention), and the song VGMs
     rendered against the engine's 30 s renders (scc_song.wav, scc_plus_song.wav): loudness envelope correlation,
     log-spectrum correlation, dominant pitch class.

    python3 tools/audio/scc_reference_check.py EVIDENCE_DIR [--vgm2wav PATH]   (4 and 5 need numpy)
Prints PASS/FAIL per check; exit status 1 on any failure."""
import argparse
import os
import struct
import subprocess
import sys
import tempfile
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "scc"))
import scc_ref  # noqa: E402
import scc_song  # noqa: E402

VGM_MAX_TICKS = 36000
TONE_HZ = scc_ref.hz(253)  # A-4: P 253
failures = 0


def check(ok, what):
    global failures
    print(("PASS " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


def load_image(path):
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < scc_ref.IMAGE_SIZE:
        data += bytes(scc_ref.IMAGE_SIZE - len(data))
    return scc_ref.Image(data)


# ---- trace files -------------------------------------------------------------------------------------

def parse_batch(line):
    """(index, (order, row, tick) | None, [(port, reg, val)]) of a scc_trace.txt line."""
    head, _, rest = line.partition(":")
    fields = head.split()
    assert fields[0] == "T", line
    index, o, r, t = (int(v) for v in fields[1:5])
    writes = []
    for w in rest.split():
        port, _, rv = w.partition(":")
        reg, _, val = rv.partition("=")
        writes.append((int(port), int(reg, 16), int(val, 16)))
    return index, None if index < 0 else (o, r, t), writes


def read_lines(path):
    with open(path) as f:
        return [line.rstrip("\n") for line in f if line.strip()]


def check_trace(image, path):
    lines = read_lines(path)
    ref = [scc_ref.format_batch(*b) for b in scc_ref.trace(image, len(lines) - 1)]
    same = sum(1 for a, b in zip(lines, ref) if a == b)
    first = next((i for i, (a, b) in enumerate(zip(lines, ref)) if a != b), None)
    check(same == len(lines) == len(ref) and lines[0].startswith("T -1 -1 -1 -1 :"),
          "reference player = engine trace in %d/%d batches (reset batch included)%s"
          % (same, len(lines), "" if first is None else "; first difference at line %d:\n  engine    %s\n  reference %s"
             % (first, lines[first], ref[first])))
    return [parse_batch(line) for line in lines]


def check_chip(path):
    """Replays scc_chip_trace.txt's writes through the per-clock model and compares the level-change events."""
    lines = read_lines(path)
    head = lines[0].split()
    check(head[0] == "R" and head[1] in ("0", "1"), "chip trace header (R <plus>)")
    writes, events = [], []
    for line in lines[1:]:
        f = line.split()
        if f[0] == "W":
            writes.append(tuple(int(v) for v in f[1:5]))
        elif f[0] == "E":
            events.append(tuple(int(v) for v in f[1:4]))
        else:
            raise ValueError("unexpected chip trace line: " + line)
    end = max([w[0] for w in writes] + [e[0] for e in events]) + 1
    check(end <= 400000, "chip trace spans %d clocks" % end)
    chip = scc_ref.Chip(int(head[1]))
    mine, at = [], 0
    for t in range(end):
        while at < len(writes) and writes[at][0] == t:
            chip.write(*writes[at][1:])
            at += 1
        for c, level in chip.clock():
            mine.append((t, c, level))
    check(at == len(writes), "every write applied in clock order (%d writes)" % len(writes))
    expected = sorted(events)
    mine.sort()
    first = next((i for i, (a, b) in enumerate(zip(expected, mine)) if a != b), None)
    check(mine == expected, "per-clock chip model = core events: %d/%d events%s"
          % (sum(1 for a, b in zip(expected, mine) if a == b), len(expected),
             "" if first is None else "; first difference: core %s, model %s"
             % (expected[first] if first < len(expected) else None, mine[first] if first < len(mine) else None)))


# ---- VGM -------------------------------------------------------------------------------------------

def parse_vgm(data):
    """(header dict, events [("w", port, reg, val) | ("t", samples)] with adjacent waits merged, offsets, end)."""
    u32 = lambda at: struct.unpack_from("<I", data, at)[0]  # noqa: E731
    h = {"eof": u32(4) + 4, "version": u32(8), "gd3": u32(0x14), "samples": u32(0x18), "loop_offset": u32(0x1C),
         "loop_samples": u32(0x20), "rate": u32(0x24), "data": 0x34 + u32(0x34), "k051649": u32(0x9C)}
    others = [at for at in range(0x0C, 0x100, 4) if at not in (0x14, 0x18, 0x1C, 0x20, 0x24, 0x34, 0x9C) and u32(at)]
    h["other_fields"] = others
    at, events, offsets = h["data"], [], {}
    while True:
        offsets[at] = len(events)
        cmd = data[at]
        if cmd == 0xD2:
            events.append(("w", data[at + 1], data[at + 2], data[at + 3]))
            at += 4
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


class Registers:
    """Chip registers as the writes of a trace leave them (the player never writes a protected RAM)."""

    def __init__(self):
        self.test = self.key = 0
        self.period = [0] * 10
        self.volume = [0] * 5
        self.ram = [[0] * 32 for _ in range(5)]

    def apply(self, port, reg, val):
        if port in (0, 4):
            self.ram[reg >> 5][reg & 31] = val
        elif port == 1:
            self.period[reg] = val
        elif port == 2:
            self.volume[reg] = val
        elif port == 3:
            self.key = val
        elif port == 5:
            self.test = val


def check_vgm(name, image, data, batches):
    """Structure of a VGM from the format alone, and its stream against `batches` (parse_batch tuples)."""
    plus = image.plus
    h, events, offsets, end = parse_vgm(data)
    clock = scc_ref.VGM_CLOCK | (scc_ref.VGM_CLOCK_PLUS if plus else 0)
    check(data[:4] == b"Vgm " and h["version"] == 0x171 and h["data"] == 0x100 and h["rate"] == 60
          and h["k051649"] == clock and not h["other_fields"] and h["gd3"] == 0,
          "%s: VGM 1.71 header, %s at %d Hz%s, data at 0x100, rate 60, no other chip"
          % (name, "K052539" if plus else "K051649", h["k051649"] & 0x7FFFFFFF, " + bit 31" if plus else ""))
    check(h["eof"] == len(data) == end, "%s: end-of-file offset" % name)
    check(sum(e[1] for e in events if e[0] == "t") == h["samples"],
          "%s: waits sum to %d samples (%.2f s)" % (name, h["samples"], h["samples"] / 44100))
    loop_cmd = 0x1C + h["loop_offset"]
    check(h["loop_offset"] and loop_cmd in offsets, "%s: loop point on a command boundary" % name)
    # The expected stream: the reset batch, then per tick (at the first arrival at the loop order's row 0 the
    # register dump, which is built here from the trace's writes) its writes and its wait.
    regs = Registers()
    expected = []
    for w in batches[0][2]:
        regs.apply(*w)
        expected.append(("w",) + w)
    acc, period, dump_at, loop_samples, samples = 0, 60 * image.multispeed, None, None, 0
    ticks = 0
    for index, pos, writes in batches[1:]:
        if pos == (image.loop, 0, 0):
            if dump_at is not None:
                break
            dump_at, loop_samples = len(expected), samples
            expected += [("w",) + w for w in scc_ref.dump_writes(plus, regs.test, regs.key, regs.period, regs.volume,
                                                                 regs.ram)]
        for w in writes:
            regs.apply(*w)
            expected.append(("w",) + w)
        acc += 44100
        n, acc = divmod(acc, period)
        samples += n
        if expected[-1][0] == "t":
            expected[-1] = ("t", expected[-1][1] + n)
        else:
            expected.append(("t", n))
        ticks += 1
    complete = dump_at is not None and len(batches) - 1 > ticks
    check(complete, "%s: the trace reaches the loop point twice (%d ticks)" % (name, ticks))
    check(events == expected, "%s: VGM stream = trace: reset batch, register dump, per-tick writes and waits "
          "(%d ticks, %d events)" % (name, ticks, len(events)))
    check(dump_at is not None and offsets.get(loop_cmd) == dump_at and h["loop_samples"] == samples - loop_samples,
          "%s: loop point = the register dump at the loop order's first arrival, %d samples" % (name, h["loop_samples"]))


# ---- audio -------------------------------------------------------------------------------------------

def read_wav(path):
    import numpy as np
    with wave.open(path) as w:
        n, ch, rate, width = w.getnframes(), w.getnchannels(), w.getframerate(), w.getsampwidth()
        raw = w.readframes(n)
    if width == 2:
        x = np.frombuffer(raw, dtype="<i2").astype(float) / 32768
    elif width == 4:
        x = np.frombuffer(raw, dtype="<i4").astype(float) / 2147483648
    else:
        raise ValueError("%s: %d-byte samples" % (path, width))
    return x.reshape(-1, ch).mean(axis=1), rate


def measure_pitch(x, rate, lo=300.0, hi=600.0):
    """Frequency of the strongest component in lo..hi Hz of the longest sounding stretch (up to 2 s, from 50 ms
    after its onset): Hann window, 16x zero padding, parabolic interpolation on the log magnitude."""
    import numpy as np
    loud = np.abs(x) > 0.02 * np.max(np.abs(x))
    idx = np.flatnonzero(loud)
    start = int(idx[0]) + rate // 20
    stop = min(int(idx[-1]), start + 2 * rate)
    seg = x[start:stop] - np.mean(x[start:stop])
    n = len(seg)
    size = 16 * (1 << int(np.ceil(np.log2(n))))
    spec = np.abs(np.fft.rfft(seg * np.hanning(n), size))
    freqs = np.fft.rfftfreq(size, 1 / rate)
    band = np.flatnonzero((freqs > lo) & (freqs < hi))
    k = int(band[np.argmax(spec[band])])
    a, b, c = np.log(spec[k - 1:k + 2] + 1e-30)
    shift = 0.5 * (a - c) / (a - 2 * b + c)
    return (k + shift) * rate / size, n / rate


def check_pitch(path, label):
    import numpy as np
    x, rate = read_wav(path)
    f, seconds = measure_pitch(x, rate)
    cents = 1200 * np.log2(f / TONE_HZ)
    check(abs(f - TONE_HZ) < 0.1, "%s: A-4 measured %.3f Hz over %.2f s (expected %.3f Hz, %+.2f cents)"
          % (label, f, seconds, TONE_HZ, cents))


def vgm2wav(tool, vgm_path, out, rate, loops):
    subprocess.run([tool, "--samplerate", str(rate), "--loops", str(loops), "--fade", "0", vgm_path, out],
                   check=True, stdout=subprocess.DEVNULL)


def compare_audio(engine_wav, vgm_path, tool, label):
    import numpy as np
    a, rate = read_wav(engine_wav)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "vgm.wav")
        vgm2wav(tool, vgm_path, out, rate, 5)
        b, _ = read_wav(out)
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    win = rate // 50

    def envelope(x):  # rms per 20 ms window around the window's mean (the chip's DC is removed by blip_buf only)
        frames = x[:(len(x) // win) * win].reshape(-1, win)
        return np.sqrt(np.mean((frames - frames.mean(axis=1, keepdims=True)) ** 2, axis=1))

    ea, eb = envelope(a), envelope(b)
    m = min(len(ea), len(eb))
    ea, eb = ea[:m], eb[:m]
    lag = max(range(-5, 6), key=lambda k: np.corrcoef(ea[10 + k:m - 10 + k], eb[10:m - 10])[0, 1])
    env = np.corrcoef(ea[10 + lag:m - 10 + lag], eb[10:m - 10])[0, 1]
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
        sa = np.abs(np.fft.rfft((a[i:i + w] - a[i:i + w].mean()) * np.hanning(w)))
        sb = np.abs(np.fft.rfft((b[i:i + w] - b[i:i + w].mean()) * np.hanning(w)))
        if sa[band].max() < 1e-2 or sb[band].max() < 1e-2:
            continue
        spec.append(np.corrcoef(np.log(sa[band] + 1e-4), np.log(sb[band] + 1e-4))[0, 1])
        pa = freqs[band][np.argmax(sa[band])]
        pb = freqs[band][np.argmax(sb[band])]
        counted += 1
        same_pc += round(12 * np.log2(pa / pb)) % 12 == 0
    level = np.sqrt(np.mean((b[:n] - b[:n].mean()) ** 2)) / np.sqrt(np.mean((a[:n] - a[:n].mean()) ** 2))
    print("  %s: libvgm vs engine: envelope correlation %.3f (lag %d ms), log-spectrum correlation %.3f, same pitch "
          "class %.0f%% of %d windows, level ratio %.2f"
          % (label, env, lag * 20, float(np.mean(spec)), 100.0 * same_pc / max(1, counted), counted, level))
    check(env > 0.8, "%s: loudness envelope correlation > 0.8" % label)
    check(counted > 0 and np.mean(spec) > 0.8, "%s: log-spectrum correlation > 0.8" % label)
    check(counted > 0 and same_pc / counted > 0.7, "%s: dominant pitch class agrees in > 70%% of windows" % label)


def check_libvgm(tool, directory):
    with tempfile.TemporaryDirectory() as tmp:
        for variant, label in ((scc_song.SCC, "libvgm K051649"), (scc_song.PLUS, "libvgm K052539")):
            image = scc_ref.Image(scc_song.build(scc_song.tone_song(variant)))
            data, _ = scc_ref.vgm(image, VGM_MAX_TICKS)
            vgm_path, out = os.path.join(tmp, "tone.vgm"), os.path.join(tmp, "tone.wav")
            with open(vgm_path, "wb") as f:
                f.write(data)
            vgm2wav(tool, vgm_path, out, 48000, 1)
            check_pitch(out, label + " (reference tone VGM)")
    compare_audio(os.path.join(directory, "scc_song.wav"), os.path.join(directory, "scc_song.vgm"), tool, "SCC")
    compare_audio(os.path.join(directory, "scc_plus_song.wav"), os.path.join(directory, "scc_plus_song.vgm"), tool,
                  "SCC+")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--vgm2wav")
    args = ap.parse_args()
    d = args.dir
    image = load_image(os.path.join(d, "scc_song.bin"))
    plus = load_image(os.path.join(d, "scc_plus_song.bin"))
    check(not image.plus and plus.plus, "evidence images: scc_song.bin is SCC, scc_plus_song.bin is SCC+")
    batches = check_trace(image, os.path.join(d, "scc_trace.txt"))
    check_chip(os.path.join(d, "scc_chip_trace.txt"))
    for name, img, engine_batches in (("scc_song.vgm", image, batches), ("scc_plus_song.vgm", plus, None)):
        with open(os.path.join(d, name), "rb") as f:
            data = f.read()
        mine, info = scc_ref.vgm(img, VGM_MAX_TICKS)
        check(mine == data, "%s: reference VGM = engine VGM (%d bytes)" % (name, len(data)))
        if engine_batches is None:  # no engine trace for the SCC+: the reference player's (equal VGMs tie them)
            engine_batches = scc_ref.trace(img, info["ticks"] + 1)
        check_vgm(name, img, data, engine_batches)
    try:
        import numpy  # noqa: F401
        have_numpy = True
    except ImportError:
        have_numpy = False
        print("SKIP pitch and audio checks: numpy is not installed")
    if have_numpy:
        check_pitch(os.path.join(d, "scc_tone.wav"), "scc_tone.wav (engine)")
        if args.vgm2wav:
            check_libvgm(args.vgm2wav, d)
    print("FAIL" if failures else "PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
