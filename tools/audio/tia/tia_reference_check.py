#!/usr/bin/env python3
"""Independent checks of the Atari 2600 (TIA) engine's output and of the pitch contract (pure Python 3, no
dependencies). Nothing here has been run yet; thresholds are assumptions to confirm on the first run.

    python3 tools/audio/tia/tia_reference_check.py DIR             # the native trace against tia_ref.py
    python3 tools/audio/tia/tia_reference_check.py DIR --pitch     # + A-4 in the native WAVs against A / (6 * 12)
    python3 tools/audio/tia/tia_reference_check.py --periods       # every AUDC's period and shape from the counters
    python3 tools/audio/tia/tia_reference_check.py --edges         # the AUDF edge table against a brute-force search

DIR is the evidence directory tools/audio/run_native_host_test.sh writes: tia_song.bin (the test song's image),
tia_trace.txt (the engine's per-tick player writes, one line per tick: "order row tick:" then " RR=VV" per write, the
reset batch excluded), tia_tone.wav (A-4 held on TIA 1 with AUDC C, NTSC) and, when present, tia_tone_pal.wav.

--periods checks, from tia_ref.ChipChannel (a separate port of the logic of Stella 7.0's AudioChannel), that every
AUDC repeats with the period of its class (TIP_PERIOD) times AUDF + 1, and that its output matches the pattern shape
Martin Korth's "Atari 2600 Specifications" lists for it (a cyclic substring; the AUDC 0/B shapes are constant).
--edges checks the threshold table the player uses (tia_ref.EDGE, = TIP_AUDF_EDGE by prepare_tia.py) against a
direct search for the divider nearest in log frequency, at every pitch the player can produce."""
import argparse
import math
import os
import struct
import sys
import wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tia_ref  # noqa: E402

# Korth, "Atari 2600 Specifications", "AUDC Pattern Shapes" ('-' high, '_' low), 70 output clocks each.
KORTH_SHAPES = {
    0x0: "----------------------------------------------------------------------",
    0x1: "----___-__--_-_----___-__--_-_----___-__--_-_----___-__--_-_----___-__",
    0x2: "--------------------------------------------------------------________",
    0x3: "------______-___---__-----___-------___----___--__--_____---------___-",
    0x4: "-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_",
    0x5: "-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_",
    0x6: "------------------_____________------------------_____________--------",
    0x7: "-----___--_---_-_-____-__-_--__-----___--_---_-_-____-__-_--__-----___",
    0x8: "---------_____----_-----___-_---__--__-_____-__-_-__---_--_-___----__-",
    0x9: "-----___--_---_-_-____-__-_--__-----___--_---_-_-____-__-_--__-----___",
    0xA: "------------------_____________------------------_____________--------",
    0xB: "----------------------------------------------------------------------",
    0xC: "---___---___---___---___---___---___---___---___---___---___---___---_",
    0xD: "---___---___---___---___---___---___---___---___---___---___---___---_",
    0xE: "-------------------------------------------------_____________________",
    0xF: "----------_____---_______----__________------___------____---------___",
}

PITCH_TOLERANCE_HZ = 0.3      # assumption (unverified): zero-crossing estimate over ~2 s of a 436 Hz square


def output_sequence(audc, audf, warm=4096, count=4096):
    ch = tia_ref.ChipChannel()
    ch.write(0, audc)
    ch.write(1, audf)
    ch.write(2, 15)
    seq = []
    for i in range(warm + count):
        ch.phase0()
        ch.phase1()
        if i >= warm:
            seq.append(ch.pulse & 1)
    return seq


def check_periods():
    bad = []
    for audc in range(16):
        cls = tia_ref.CLASS[audc]
        expected = 1 if cls < 0 else tia_ref.PERIOD[cls]
        for f in (0, 1, 7, 31):
            p = tia_ref.measure_period(audc, f, span=8192 if expected * (f + 1) < 2048 else 40000)
            want = 1 if cls < 0 else expected * (f + 1)
            if p != want:
                bad.append("AUDC %X AUDF %d: period %s, expected %d" % (audc, f, p, want))
        seq = output_sequence(audc, 0)
        cyc = "".join("-" if b else "_" for b in seq[:expected]) * (70 // expected + 3)
        inverted = cyc.translate(str.maketrans("-_", "_-"))
        shape = KORTH_SHAPES[audc]
        if shape not in cyc:
            # an inverted waveform sounds the same; report it, but only a shape found neither way fails
            if cls >= 0 and shape in inverted:
                print("periods: AUDC %X matches Korth's shape inverted (same sound, opposite polarity)" % audc)
            else:
                bad.append("AUDC %X: output does not match Korth's shape %s" % (audc, shape))
    print("periods: %s" % ("ok (16 AUDC x 4 AUDF, Korth's shapes)" if not bad else "FAILED"))
    for b in bad:
        print("  " + b)
    return not bad


def check_edges():
    """At every pitch the player can produce (PITCH_MIN..PITCH_MAX) and every class and region, the AUDF of the
    edge table equals the divider nearest in log frequency (ties within 1e-9 octave skipped)."""
    c0 = 440 * 2 ** (-57 / 12)
    bad, ties = [], 0
    for region in range(tia_ref.REGIONS):
        a = tia_ref.audio_clock(region)
        for cls, period in enumerate(tia_ref.PERIOD):
            logs = [math.log2(a / (period * d)) for d in range(1, 33)]
            for pitch in range(tia_ref.PITCH_MIN, tia_ref.PITCH_MAX + 1):
                lf = math.log2(c0) + pitch / 768
                dist = [abs(lf - x) for x in logs]
                best = min(range(32), key=lambda d: dist[d])
                second = sorted(dist)[1]
                if abs(second - dist[best]) < 1e-9:
                    ties += 1
                    continue
                got = tia_ref.audf(region, cls, pitch)
                if got != best:
                    bad.append("region %d class %d pitch %d: table AUDF %d, nearest %d" % (region, cls, pitch, got,
                                                                                          best))
    print("edges: %s (%d ties skipped)" % ("ok" if not bad else "FAILED", ties))
    for b in bad[:20]:
        print("  " + b)
    return not bad


def check_trace(directory):
    with open(os.path.join(directory, "tia_song.bin"), "rb") as f:
        image = tia_ref.Image(f.read())
    with open(os.path.join(directory, "tia_trace.txt")) as f:
        lines = [line.rstrip("\n") for line in f if line.strip()]
    ref = [tia_ref.format_tick(pos, writes) for pos, writes in tia_ref.trace(image, len(lines))]
    for k, (got, want) in enumerate(zip(lines, ref)):
        if got != want:
            print("trace: FAILED at tick %d\n  engine:    %s\n  reference: %s" % (k, got, want))
            return False
    print("trace: ok (%d ticks)" % len(lines))
    return True


def wav_frequency(path, skip_seconds=0.2):
    """The fundamental of a held tone from rising zero crossings (left channel), interpolated."""
    with wave.open(path, "rb") as w:
        rate, channels, width, n = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        raw = w.readframes(n)
    assert width == 2, "16-bit WAV expected"
    samples = struct.unpack("<%dh" % (len(raw) // 2), raw)[::channels]
    start = int(skip_seconds * rate)
    crossings = []
    for i in range(start + 1, len(samples)):
        a, b = samples[i - 1], samples[i]
        if a < 0 <= b:
            crossings.append(i - 1 + (-a) / (b - a) if b != a else i)
    if len(crossings) < 3:
        return None, rate
    return (len(crossings) - 1) * rate / (crossings[-1] - crossings[0]), rate


def check_pitch(directory):
    ok = True
    for name, region in (("tia_tone.wav", tia_ref.REGION_NTSC), ("tia_tone_pal.wav", tia_ref.REGION_PAL)):
        path = os.path.join(directory, name)
        if not os.path.exists(path):
            if region == tia_ref.REGION_NTSC:
                print("pitch: %s missing" % name)
                ok = False
            continue
        f = tia_ref.audf(region, tia_ref.CLASS[0xC], 57 * 64)
        expected = tia_ref.frequency(region, 0xC, f)
        got, _ = wav_frequency(path)
        cents = 1200 * math.log2(expected / 440)
        good = got is not None and abs(got - expected) <= PITCH_TOLERANCE_HZ
        print("pitch %s: A-4 on AUDC C = AUDF %d, expected %.3f Hz (%+.1f cents from 440), measured %s: %s"
              % (name, f, expected, cents, "%.3f Hz" % got if got else "none", "ok" if good else "FAILED"))
        ok &= good
    return ok


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir", nargs="?")
    ap.add_argument("--periods", action="store_true")
    ap.add_argument("--edges", action="store_true")
    ap.add_argument("--pitch", action="store_true")
    args = ap.parse_args(argv)
    ok = True
    if args.periods:
        ok &= check_periods()
    if args.edges:
        ok &= check_edges()
    if args.dir:
        ok &= check_trace(args.dir)
        if args.pitch:
            ok &= check_pitch(args.dir)
    elif args.pitch:
        ap.error("--pitch needs DIR")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
