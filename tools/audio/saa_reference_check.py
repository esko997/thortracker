#!/usr/bin/env python3
"""Independent checks of the SAA1099 host test's evidence (developer evidence, not part of the build):

  1. The Python reference player (saa/saa_ref.py) on the SAM test image (saa_sam_song.bin) writes exactly the C
     machine's batches (saa_trace.txt: the reset batch, then one line per tick), and produces the same VGM bytes for the
     SAM Coupé and CMS images (saa_sam_song.vgm, saa_cms_song.vgm).
  2. A per-clock model of one chip (saa_ref.Chip: the specification's "One clock" steps run literally, SAASound's
     envelope state machine and levels) driven by the scripted writes of saa_chip_trace.txt reproduces every output
     event the C core's event renderer emitted, at the same clock.
  3. The VGMs, parsed here from the file format alone: header (version 1.71, data offset, rate, SAA1099 clock 8000000
     or 7159090 with bit 30 for two chips, no other chip), waits summing to the header's sample count, the 0xBD stream
     equal to the trace (engine trace for the SAM Coupé, reference trace for the CMS), the loop point at an arrival at
     the loop order's row 0 and the next pass of the trace repeating the looped writes exactly.
  4. Pitch (needs numpy): saa_tone_sam.wav and saa_tone_cms.wav (A-4 on the plain tone instrument) measured by FFT
     against 8000000 * 8 / (512 * 284) = 440.141 Hz and 7159090 * 16 / (512 * 508) = 440.400 Hz.
  5. With --vgm2wav (libvgm's vgm2wav, an independent VGM player with its own SAA1099 cores): A-4 tone VGMs from the
     reference measured the same way on both machines (which also proves the header clocks and the dual-chip bit),
     and, for information only, the song VGMs against the engine's 30 s renders (the cores differ by design where
     docs/AUDIO_ENGINE.md "SAA1099: VGM export" says: preset noise clocks, envelope routing, mixer-off levels).

    python3 tools/audio/saa_reference_check.py EVIDENCE_DIR [--vgm2wav PATH]   (4 and 5 need numpy)
Prints PASS/FAIL per check; exit status 1 on any failure."""
import argparse
import os
import subprocess
import sys
import tempfile
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "saa"))
import saa_ref  # noqa: E402
import saa_song  # noqa: E402

VGM_MAX_TICKS = 50 * 600
TONE_SAM = saa_ref.hz(*saa_ref.octave_offset(57 * 64, False), False)
TONE_CMS = saa_ref.hz(*saa_ref.octave_offset(57 * 64, True), True)
failures = 0


def check(ok, what):
    global failures
    print(("PASS " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


def load_image(path):
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < saa_ref.IMAGE_SIZE:
        data += bytes(saa_ref.IMAGE_SIZE - len(data))
    return saa_ref.Image(data)


# ---- trace files -------------------------------------------------------------------------------------

def parse_batch(line):
    """(index, (order, row, tick) | None, [(chip, reg, val)]) of a saa_trace.txt line."""
    head, _, rest = line.partition(":")
    fields = head.split()
    assert fields[0] == "T", line
    index, o, r, t = (int(v) for v in fields[1:5])
    writes = []
    for w in rest.split():
        chip, _, rv = w.partition(":")
        reg, _, val = rv.partition("=")
        writes.append((int(chip), int(reg, 16), int(val, 16)))
    return index, None if index < 0 else (o, r, t), writes


def read_lines(path):
    with open(path) as f:
        return [line.rstrip("\n") for line in f if line.strip() and not line.startswith("#")]


def check_trace(image, path):
    lines = read_lines(path)
    ref = [saa_ref.format_batch(*b) for b in saa_ref.trace(image, len(lines) - 1)]
    same = sum(1 for a, b in zip(lines, ref) if a == b)
    first = next((i for i, (a, b) in enumerate(zip(lines, ref)) if a != b), None)
    check(same == len(lines) == len(ref) and lines[0].startswith("T -1 -1 -1 -1 :"),
          "reference player = engine trace in %d/%d batches (reset batch included)%s"
          % (same, len(lines), "" if first is None else "; first difference at line %d:\n  engine    %s\n  reference %s"
             % (first, lines[first], ref[first])))
    return [parse_batch(line) for line in lines]


def check_chip(path):
    """Replays saa_chip_trace.txt's writes through the per-clock model and compares the output events."""
    writes, events = [], []
    for line in read_lines(path):
        f = line.split()
        if f[0] == "W":
            writes.append(tuple(int(v) for v in f[1:4]))
        elif f[0] == "E":
            events.append(tuple(int(v) for v in f[1:5]))
        else:
            raise ValueError("unexpected chip trace line: " + line)
    end = max([w[0] for w in writes] + [e[0] for e in events]) + 1
    check(end <= 4000000, "chip trace spans %d clocks" % end)
    chip = saa_ref.Chip()
    mine, at = [], 0
    for t in range(end):
        wrote = False
        while at < len(writes) and writes[at][0] == t:
            chip.write(*writes[at][1:])
            at += 1
            wrote = True
        if wrote:  # a render starts here: changes made by the writes are emitted at its offset 0
            for c in range(6):
                o = chip.output(c)
                if o != chip.out[c]:
                    chip.out[c] = o
                    mine.append((t, c) + o)
        chip.clock()
        for c in range(6):
            o = chip.output(c)
            if o != chip.out[c]:
                chip.out[c] = o
                mine.append((t, c) + o)
    check(at == len(writes), "every write applied in clock order (%d writes)" % len(writes))
    expected = sorted(events)
    mine.sort()
    first = next((i for i, (a, b) in enumerate(zip(expected, mine)) if a != b), None)
    check(mine == expected, "per-clock chip model = core events: %d/%d events%s"
          % (sum(1 for a, b in zip(expected, mine) if a == b), len(expected),
             "" if first is None and len(mine) == len(expected) else "; first difference: core %s, model %s"
             % (expected[first] if first is not None and first < len(expected) else None,
                mine[first] if first is not None and first < len(mine) else None)))


# ---- VGM -------------------------------------------------------------------------------------------

def check_vgm(name, image, data, batches):
    """Structure of a VGM from the format alone, and its stream against `batches` (parse_batch tuples)."""
    import struct
    hdr, vgm_batches, loop_index = saa_ref.parse_vgm(data)
    u32 = lambda at: struct.unpack_from("<I", data, at)[0]  # noqa: E731
    others = [at for at in range(0x0C, 0x100, 4)
              if at not in (0x04, 0x08, 0x14, 0x18, 0x1C, 0x20, 0x24, 0x34, 0xC8) and u32(at)]
    clock = image.clock | (saa_ref.VGM_DUAL if image.cms else 0)
    check(data[:4] == b"Vgm " and hdr["version"] == 0x171 and hdr["data_offset"] == 0x100
          and hdr["rate"] == image.rate and hdr["saa_clock"] == clock and not others,
          "%s: VGM 1.71 header, SAA1099 at %d Hz%s, data at 0x100, rate %d, no other chip"
          % (name, image.clock, " x2 (bit 30)" if image.cms else "", image.rate))
    check(hdr["eof"] == len(data) and data[-1] == 0x66, "%s: end-of-file offset and end command" % name)
    check(sum(w for _, w in vgm_batches) == hdr["samples"],
          "%s: waits sum to %d samples (%.2f s)" % (name, hdr["samples"], hdr["samples"] / 44100))
    # Expected: the reset batch (no wait), then per tick its writes and wait, until the VGM ends.
    period = image.rate * image.multispeed
    acc, expected = 0, []
    reset = batches[0][2]
    for index, pos, writes in batches[1:len(vgm_batches) + 1]:
        acc += 44100
        n, acc = divmod(acc, period)
        expected.append((list(writes), n, pos))
    flat_vgm = [w for ws, _ in vgm_batches for w in ws]
    flat_exp = list(reset) + [w for ws, _, _ in expected[:len(vgm_batches)] for w in ws]
    check(flat_vgm == flat_exp[:len(flat_vgm)] and len(flat_vgm) <= len(flat_exp),
          "%s: VGM writes = trace writes, in order (%d writes)" % (name, len(flat_vgm)))
    waits = [n for _, n in vgm_batches if n]
    check(waits == [n for _, n, _ in expected[:len(waits)]], "%s: one wait per tick, exact accumulation" % name)
    if not hdr["loop_offset"]:
        check(False, "%s: the test song loops" % name)
        return
    # The loop: the batch index (= tick) where it starts, at an arrival at (loop, 0, 0); the trace's next pass repeats.
    ticks = len(waits)
    loop_tick = sum(1 for ws, n in vgm_batches[:loop_index] if n)
    check(expected[loop_tick][2] == (image.loop, 0, 0),
          "%s: loop point at an arrival at (loop order %d, row 0, tick 0): tick %d" % (name, image.loop, loop_tick))
    check(len(batches) - 1 > ticks and batches[1 + ticks][1] == (image.loop, 0, 0),
          "%s: the file ends at the next arrival at the loop point (tick %d)" % (name, ticks))
    length = ticks - loop_tick
    samples = sum(n for _, n, _ in expected[loop_tick:ticks])
    check(hdr["loop_samples"] == samples, "%s: loop samples %d" % (name, samples))
    if len(batches) - 1 >= ticks + length:
        again = [list(b[2]) for b in batches[1 + ticks:1 + ticks + length]]
        looped = [w for w, _, _ in expected[loop_tick:ticks]]
        check(again == looped, "%s: the trace's next pass sends exactly the looped writes (%d ticks)" % (name, length))
    else:
        print("SKIP %s: the trace is too short to see a further pass" % name)


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


def check_pitch(path, label, expected):
    import numpy as np
    x, rate = read_wav(path)
    f, seconds = measure_pitch(x, rate)
    cents = 1200 * np.log2(f / expected)
    check(abs(f - expected) < 0.1, "%s: A-4 measured %.3f Hz over %.2f s (expected %.3f Hz, %+.2f cents)"
          % (label, f, seconds, expected, cents))


def vgm2wav(tool, vgm_path, out, rate, loops):
    subprocess.run([tool, "--samplerate", str(rate), "--loops", str(loops), "--fade", "0", vgm_path, out],
                   check=True, stdout=subprocess.DEVNULL)


def compare_audio(engine_wav, vgm_path, tool, label):
    """Information only (the cores differ by design): loudness envelope and log-spectrum correlation."""
    import numpy as np
    a, rate = read_wav(engine_wav)
    with tempfile.TemporaryDirectory(dir=os.path.dirname(os.path.abspath(engine_wav))) as tmp:
        out = os.path.join(tmp, "vgm.wav")
        vgm2wav(tool, vgm_path, out, rate, 5)
        b, _ = read_wav(out)
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    win = rate // 50

    def envelope(x):
        frames = x[:(len(x) // win) * win].reshape(-1, win)
        return np.sqrt(np.mean((frames - frames.mean(axis=1, keepdims=True)) ** 2, axis=1))

    ea, eb = envelope(a), envelope(b)
    m = min(len(ea), len(eb))
    env = np.corrcoef(ea[10:m - 10], eb[10:m - 10])[0, 1]
    w = rate // 10
    freqs = np.fft.rfftfreq(w, 1 / rate)
    band = (freqs > 60) & (freqs < 4000)
    spec = []
    for i in range(0, n - w, w):
        sa = np.abs(np.fft.rfft((a[i:i + w] - a[i:i + w].mean()) * np.hanning(w)))
        sb = np.abs(np.fft.rfft((b[i:i + w] - b[i:i + w].mean()) * np.hanning(w)))
        if sa[band].max() < 1e-2 or sb[band].max() < 1e-2:
            continue
        spec.append(np.corrcoef(np.log(sa[band] + 1e-4), np.log(sb[band] + 1e-4))[0, 1])
    print("INFO %s: libvgm vs engine: loudness envelope correlation %.3f, log-spectrum correlation %.3f (%d windows)"
          % (label, env, float(np.mean(spec)) if spec else float("nan"), len(spec)))


def check_libvgm(tool, directory):
    with tempfile.TemporaryDirectory(dir=os.path.abspath(directory)) as tmp:
        for variant, label, expected in ((saa_song.SAM, "libvgm SAA1099 (SAM Coupé)", TONE_SAM),
                                         (saa_song.CMS, "libvgm 2x SAA1099 (CMS)", TONE_CMS)):
            image = saa_ref.Image(saa_song.build(saa_song.tone_song(variant)))
            data, _ = saa_ref.vgm(image, VGM_MAX_TICKS)
            vgm_path, out = os.path.join(tmp, "tone.vgm"), os.path.join(tmp, "tone.wav")
            with open(vgm_path, "wb") as f:
                f.write(data)
            vgm2wav(tool, vgm_path, out, 48000, 1)
            check_pitch(out, label + " (reference tone VGM)", expected)
    compare_audio(os.path.join(directory, "saa_sam_song.wav"), os.path.join(directory, "saa_sam_song.vgm"), tool,
                  "SAM Coupé")
    compare_audio(os.path.join(directory, "saa_cms_song.wav"), os.path.join(directory, "saa_cms_song.vgm"), tool,
                  "CMS")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--vgm2wav")
    args = ap.parse_args()
    d = args.dir
    sam = load_image(os.path.join(d, "saa_sam_song.bin"))
    cms = load_image(os.path.join(d, "saa_cms_song.bin"))
    check(not sam.cms and cms.cms, "evidence images: saa_sam_song.bin is SAM Coupé, saa_cms_song.bin is CMS")
    batches = check_trace(sam, os.path.join(d, "saa_trace.txt"))
    check_chip(os.path.join(d, "saa_chip_trace.txt"))
    for name, img, engine_batches in (("saa_sam_song.vgm", sam, batches), ("saa_cms_song.vgm", cms, None)):
        with open(os.path.join(d, name), "rb") as f:
            data = f.read()
        mine, info = saa_ref.vgm(img, VGM_MAX_TICKS)
        check(mine == data, "%s: reference VGM = engine VGM (%d bytes)" % (name, len(data)))
        if engine_batches is None or len(engine_batches) - 1 < 2 * info["ticks"]:
            # the CMS has no engine trace (equal VGMs tie the players); take a reference trace long enough to see the
            # pass after the loop
            engine_batches = saa_ref.trace(img, 2 * info["ticks"] + 1)
        check_vgm(name, img, data, engine_batches)
    try:
        import numpy  # noqa: F401
        have_numpy = True
    except ImportError:
        have_numpy = False
        print("SKIP pitch and audio checks: numpy is not installed")
    if have_numpy:
        check_pitch(os.path.join(d, "saa_tone_sam.wav"), "saa_tone_sam.wav (engine)", TONE_SAM)
        check_pitch(os.path.join(d, "saa_tone_cms.wav"), "saa_tone_cms.wav (engine)", TONE_CMS)
        if args.vgm2wav:
            check_libvgm(args.vgm2wav, d)
    print("FAIL" if failures else "PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
