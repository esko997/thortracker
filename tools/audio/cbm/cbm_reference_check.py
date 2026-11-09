#!/usr/bin/env python3
"""Independent evidence for the VIC-20 / C16-Plus/4 engine (docs/AUDIO_ENGINE.md "VIC-20 and C16/Plus/4").

    python3 tools/audio/cbm/cbm_reference_check.py DIR [--images EXPORT_DIR] [--pitch WAV=HZ ...]

1. Every native text trace DIR/cbm_trace_<name>.txt (written by app/src/test/cpp/cbm_machine_test.c) is compared
   line by line with this reference's trace of the same image for the same number of ticks: <name> is a test song of
   cbm_song.py, or an image EXPORT_DIR/<name>.cbimg exported by the Kotlin lowering (SampleExportTool: vic-demo,
   vic-shortest, ted-demo, ted-shortest), which the reference loader must also accept.
2. A-4 from the clocks in closed form (no tables): VIC soprano a = round(phi2 / (64 * 440)), TED P = round(sound clock
   / (2 * 440)), against the reference's table period, per variant; and the lane ranges from the tables.
3. --pitch WAV=HZ: the fundamental of a rendered WAV (16-bit PCM, first channel; the middle half of the file) by rising
   zero crossings after removing the mean, within 0.5 % of HZ (e.g. a render of vic-shortest.cbimg = 444.07 Hz on
   PAL, ted-shortest.cbimg = 439.84 Hz). Thresholds are unverified until the parent runs this.
Exit status 1 on any mismatch. Pure Python 3."""
import argparse
import glob
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cbm_ref as ref    # noqa: E402
import cbm_song          # noqa: E402


def compare_trace(path, image):
    with open(path) as f:
        native = [line.rstrip("\n") for line in f if line.strip()]
    ticks = sum(1 for line in native if line.startswith("T "))
    expected = ref.trace_lines(image, ticks)
    for i, (a, b) in enumerate(zip(native, expected)):
        if a != b:
            return "line %d differs:\n  native    %s\n  reference %s" % (i + 1, a, b)
    if len(native) != len(expected):
        return "%d native lines, %d reference lines" % (len(native), len(expected))
    return None


def closed_form_checks(problems):
    for chip in (ref.CHIP_VIC, ref.CHIP_TED):
        for video in (ref.VIDEO_PAL, ref.VIDEO_NTSC):
            v = ref.variant(chip, video)
            num, den = ref.PERIOD_CLOCK[v]
            want = round(num / den / (ref.PERIOD_DIVIDER[v] * 440))
            got = ref.period(v, 57 * 64, 0)
            hz = num / den / (ref.PERIOD_DIVIDER[v] * got)
            print("A-4 %s %s: period %d = %.2f Hz" % (("VIC soprano", "TED")[chip], ("PAL", "NTSC")[video], got, hz))
            if want != got:
                problems.append("A-4 period %d from the table, %d in closed form (variant %d)" % (got, want, v))
            for c in range(3 if chip == ref.CHIP_VIC else 1):
                if ref.lane_range_from_tables(chip, video, c) != ref.lane_range(chip, video, c):
                    problems.append("lane range %d/%d/%d" % (chip, video, c))


def wav_fundamental(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("not a WAV file")
    at, fmt, pcm = 12, None, None
    while at + 8 <= len(data):
        cid, size = data[at:at + 4], struct.unpack_from("<I", data, at + 4)[0]
        body = data[at + 8: at + 8 + size]
        if cid == b"fmt ":
            fmt = struct.unpack_from("<HHIIHH", body, 0)
        elif cid == b"data":
            pcm = body
        at += 8 + size + (size & 1)
    if fmt is None or pcm is None or fmt[0] != 1 or fmt[5] != 16:
        raise ValueError("16-bit PCM WAV expected")
    channels, rate = fmt[1], fmt[2]
    frames = len(pcm) // (2 * channels)
    x = [struct.unpack_from("<h", pcm, 2 * channels * i)[0] for i in range(frames // 4, 3 * frames // 4)]
    mean = sum(x) / len(x)
    rising = [i for i in range(1, len(x)) if x[i - 1] - mean < 0 <= x[i] - mean]
    if len(rising) < 3:
        raise ValueError("no periodic signal")
    return rate * (len(rising) - 1) / (rising[-1] - rising[0])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--images", default=None)
    ap.add_argument("--pitch", nargs="*", default=[])
    args = ap.parse_args(argv)
    problems = []
    closed_form_checks(problems)
    traces = sorted(glob.glob(os.path.join(args.dir, "cbm_trace_*.txt")))
    if not traces:
        problems.append("no cbm_trace_*.txt in %s" % args.dir)
    for path in traces:
        name = os.path.basename(path)[len("cbm_trace_"):-len(".txt")]
        if name in cbm_song.SONGS:
            data = cbm_song.build(cbm_song.SONGS[name][0])
        else:
            src = os.path.join(args.images, name + ".cbimg") if args.images else None
            if not src or not os.path.exists(src):
                problems.append("%s: no image (pass --images with the exported %s.cbimg)" % (name, name))
                continue
            with open(src, "rb") as f:
                data = f.read()
        try:
            image = ref.Image(data)
        except ref.ImageError as e:
            problems.append("%s: the reference loader rejects the image: %s" % (name, e))
            continue
        why = compare_trace(path, image)
        print("%s: %s" % (name, "trace equal to the reference" if why is None else "MISMATCH"))
        if why:
            problems.append("%s: %s" % (name, why))
    for item in args.pitch:
        wav, hz = item.rsplit("=", 1)
        f0 = wav_fundamental(wav)
        ok = abs(f0 / float(hz) - 1) <= 0.005
        print("%s: %.2f Hz (expected %s Hz) %s" % (wav, f0, hz, "ok" if ok else "OFF"))
        if not ok:
            problems.append("%s fundamental %.2f Hz, expected %s" % (wav, f0, hz))
    for p in problems:
        print("cbm_reference_check: " + p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
