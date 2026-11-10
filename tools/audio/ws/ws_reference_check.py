#!/usr/bin/env python3
"""Independent checks of the WonderSwan engines' evidence (written by app/src/test/cpp/ws_machine_test.c through
tools/audio/run_native_host_test.sh into its evidence directory), standard library only:

    python3 tools/audio/ws/ws_reference_check.py EVIDENCE_DIR [--vgm2wav PATH]

1. Traces: ws_trace.txt (ws_song.bin) and wsc_trace.txt (wsc_song.bin) against this directory's reference player
   (ws_ref.py), line by line: the reset batch, every tick's position, cycle and writes, every timer interrupt with its
   cycle and writes, every Sound DMA transfer.
2. VGMs: ws_song.vgm, wsc_vgm.vgm, ws_tone.vgm parsed from the VGM 1.71 format alone (header, waits, 0xBC/0xC6
   commands, the loop offset): total and loop samples recomputed from the waits; the decoded write stream equals the
   reference trace's VGM-loggable writes (ports $80-$9F, wave RAM, DMA transfers to $89) in order and each at sample
   floor(cycle * 44100 / 3072000); the loop repeats exactly (the writes from the loop offset to the end equal the
   reference's writes from tick Ab to Aa, compared over three expanded passes); and the bytes equal ws_ref.vgm()'s.
3. Pitch: the period of A-4 recomputed from the clock alone (3072000 / (32 N) with N = round(96000 / 440) = 218,
   440.367 Hz, +1.44 cents), the first frequency write of ws_tone.vgm decoded to the same, and ws_tone.wav's measured
   fundamental (rising zero crossings with interpolation over 2 s after the first 0.5 s). Tolerance 0.3 Hz;
   the verified native and libvgm measurements are recorded in docs/VERIFICATION.md.
4. --vgm2wav PATH: libvgm's vgm2wav (ValleyBell/libvgm, its WonderSwan core "in_wsr", a simpler model than the
   engine's: compared by pitch only, never by waveform) renders ws_tone.vgm; its fundamental must equal the engine's
   within the same tolerance. libvgm commit read for the command mapping: 0xBC aa dd -> port $80 + (aa & $7F),
   0xC6 mm ll dd -> RAM offset (mm << 8 | ll) & $7FFF (vgmplayer_cmdhandler.cpp Cmd_WSwan_Reg, Cmd_Ofs16_Data8).

Exit status 0 when every check passes."""
import argparse
import math
import os
import struct
import subprocess
import sys
import wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ws_ref as R  # noqa: E402

TOLERANCE_HZ = 0.3
failures = []


def check(ok, what):
    print(("ok    " if ok else "FAIL  ") + what)
    if not ok:
        failures.append(what)
    return ok


def load_image(path):
    with open(path, "rb") as f:
        return R.Image(f.read())


# ---- 1. traces ------------------------------------------------------------------------------------------------------

def reference_trace_lines(image, ticks):
    reset, out = R.trace(image, ticks)
    lines = ["R"] + ["W %d %04X %02X" % w for w in reset]
    for k, (pos, start, writes, irqs, transfers) in enumerate(out):
        lines.append("T %d %d %d %d %d" % (k, pos[0], pos[1], pos[2], start))
        lines += ["W %d %04X %02X" % w for w in writes]
        items = [(c, 0, w) for c, w in irqs] + [(c, 1, (t, d)) for c, t, d in transfers]
        for cycle, kind, payload in sorted(items, key=lambda x: (x[0], x[1])):
            if kind == 0:
                lines.append("I %d" % cycle)
                lines += ["W %d %04X %02X" % w for w in payload]
            else:
                lines.append("D %d %d %02X" % (cycle, payload[0], payload[1]))
    return lines


def check_trace(evidence, trace_name, image_name):
    path, ipath = os.path.join(evidence, trace_name), os.path.join(evidence, image_name)
    if not (os.path.exists(path) and os.path.exists(ipath)):
        return check(False, "%s / %s present" % (trace_name, image_name))
    with open(path) as f:
        engine = [line.strip() for line in f if line.strip()]
    ticks = sum(1 for line in engine if line.startswith("T "))
    ref = reference_trace_lines(load_image(ipath), ticks)
    first = next((i for i, (a, b) in enumerate(zip(engine, ref)) if a != b), None)
    if first is None and len(engine) != len(ref):
        first = min(len(engine), len(ref))
    detail = "" if first is None else " (first difference at line %d: engine %r, reference %r)" % (
        first + 1, engine[first] if first < len(engine) else None, ref[first] if first < len(ref) else None)
    return check(first is None, "%s: %d ticks equal the reference player's%s" % (trace_name, ticks, detail))


# ---- 2. VGM ---------------------------------------------------------------------------------------------------------

def parse_vgm(data):
    """From the format alone: header fields and [(sample, kind, a, b)] with kind 'reg' (port, value) or 'ram'
    (offset, value), the loop position as an index into that list, the total samples."""
    assert data[0:4] == b"Vgm ", "magic"
    u32 = lambda at: struct.unpack_from("<I", data, at)[0]
    hdr = {"eof": u32(0x04) + 4, "version": u32(0x08), "total": u32(0x18), "loop_rel": u32(0x1C),
           "loop_samples": u32(0x20), "rate": u32(0x24), "data": 0x34 + u32(0x34), "ws_clock": u32(0xC0)}
    loop_at = hdr["loop_rel"] + 0x1C if hdr["loop_rel"] else None
    at, sample, events, loop_index = hdr["data"], 0, [], None
    while True:
        if at == loop_at:
            loop_index = len(events)
            loop_sample = sample
        cmd = data[at]
        if cmd == 0x66:
            at += 1
            break
        if cmd == 0x61:
            sample += data[at + 1] | data[at + 2] << 8
            at += 3
        elif cmd == 0x62:
            sample += 735
            at += 1
        elif cmd == 0x63:
            sample += 882
            at += 1
        elif 0x70 <= cmd <= 0x7F:
            sample += cmd - 0x6F
            at += 1
        elif cmd == 0xBC:
            assert not data[at + 1] & 0x80, "second-chip write in a single-chip file"
            events.append((sample, "reg", 0x80 + (data[at + 1] & 0x7F), data[at + 2]))
            at += 3
        elif cmd == 0xC6:
            off = data[at + 1] << 8 | data[at + 2]
            assert not off & 0x8000, "second-chip write in a single-chip file"
            events.append((sample, "ram", off, data[at + 3]))
            at += 4
        else:
            raise AssertionError("unexpected VGM command 0x%02X at 0x%X" % (cmd, at))
    hdr["end_sample"] = sample
    hdr["loop_index"] = loop_index
    hdr["loop_sample"] = loop_sample if loop_index is not None else None
    hdr["after_end"] = at
    return hdr, events


def reference_vgm_events(image, ticks):
    """The reference timeline's VGM-loggable writes [(sample, kind, a, b)] and the sample of each tick start."""
    reset, out = R.trace(image, ticks)
    events = []

    def add(cycle, writes):
        for space, addr, val in writes:
            if space == R.SPACE_RAM:
                events.append((R.vgm_sample(cycle), "ram", addr, val))
            elif 0x80 <= addr <= 0x9F:
                events.append((R.vgm_sample(cycle), "reg", addr, val))

    add(0, reset)
    starts = []
    for pos, start, writes, irqs, transfers in out:
        starts.append(len(events))
        items = [(start, 0, writes)] + [(c, 1, w) for c, w in irqs] + \
                [(c, 2, [(R.SPACE_PORT, 0x89, d)]) for c, t, d in transfers if t == 0]
        for cycle, _, w in sorted(items, key=lambda e: (e[0], e[1])):
            add(cycle, w)
    starts.append(len(events))
    return events, starts


def check_vgm(evidence, vgm_name, image_name):
    path, ipath = os.path.join(evidence, vgm_name), os.path.join(evidence, image_name)
    if not (os.path.exists(path) and os.path.exists(ipath)):
        return check(False, "%s / %s present" % (vgm_name, image_name))
    with open(path, "rb") as f:
        data = f.read()
    image = load_image(ipath)
    hdr, events = parse_vgm(data)
    check(hdr["eof"] == len(data), "%s: EOF offset = file size" % vgm_name)
    check(hdr["version"] == 0x171 and hdr["ws_clock"] == R.VGM_CLOCK and hdr["rate"] == 0,
          "%s: version 1.71, WonderSwan clock %d, no rate scaling" % (vgm_name, R.VGM_CLOCK))
    check(hdr["after_end"] == len(data), "%s: 0x66 ends the data (no GD3 in ws_vgm_render output)" % vgm_name)
    check(hdr["end_sample"] == hdr["total"], "%s: waits sum to the header's total samples" % vgm_name)
    looped = hdr["loop_index"] is not None
    if looped:
        check(hdr["total"] - hdr["loop_sample"] == hdr["loop_samples"], "%s: loop samples = total - loop point" % vgm_name)
    ref_bytes, (total, loop_offset, loop_samples, ticks) = R.vgm(image, 45283)
    check(data == ref_bytes, "%s: bytes equal ws_ref.vgm() (%d bytes, %d ticks)" % (vgm_name, len(ref_bytes), ticks))
    ref, starts = reference_vgm_events(image, ticks)
    check(events == ref[:len(events)] and len(events) == starts[ticks],
          "%s: decoded writes and their samples equal the reference timeline (%d writes)" % (vgm_name, len(events)))
    if looped:
        # Expand three passes from the file and compare with straight reference play over the same span.
        loop_tick = next((k for k in range(ticks) if starts[k] == hdr["loop_index"]
                          and R.vgm_sample(k * R.TICK_CYCLES) == hdr["loop_sample"]), None)
        if not check(loop_tick is not None, "%s: the loop offset is a tick start of the reference timeline" % vgm_name):
            return False
        period = ticks - loop_tick
        more, more_starts = reference_vgm_events(image, ticks + 3 * period)
        body = events[hdr["loop_index"]:]
        expanded = [(a, b, c) for _, a, b, c in events]
        for _ in range(3):
            expanded += [(a, b, c) for _, a, b, c in body]
        straight = [(a, b, c) for _, a, b, c in more[:more_starts[ticks + 3 * period]]]
        check(expanded == straight, "%s: three looped passes from tick %d equal straight play's writes exactly"
              % (vgm_name, loop_tick))
    return True


# ---- 3./4. pitch ----------------------------------------------------------------------------------------------------

def read_wav(path):
    with wave.open(path, "rb") as w:
        channels, rate, n = w.getnchannels(), w.getframerate(), w.getnframes()
        assert w.getsampwidth() == 2
        pcm = w.readframes(n)
    samples = struct.unpack_from("<%dh" % (n * channels), pcm)
    mono = [sum(samples[i * channels:(i + 1) * channels]) / channels for i in range(n)]
    return rate, mono


def fundamental(rate, x, start_s=0.5, span_s=2.0):
    seg = x[int(start_s * rate):int((start_s + span_s) * rate)]
    mean = sum(seg) / len(seg)
    seg = [v - mean for v in seg]
    crossings = []
    for i in range(1, len(seg)):
        if seg[i - 1] < 0 <= seg[i]:
            crossings.append(i - 1 + (-seg[i - 1]) / (seg[i] - seg[i - 1]))
    if len(crossings) < 3:
        return 0.0
    return (len(crossings) - 1) * rate / (crossings[-1] - crossings[0])


def check_pitch(evidence, vgm2wav):
    n = round(96000 / 440)
    hz = 3072000 / (32 * n)
    cents = 1200 * math.log2(hz / 440)
    check(n == 218 and R.period(57 * 64) == n and abs(cents - 1.44) < 0.01,
          "A-4: N = %d from the clock alone = the reference table's (%.3f Hz, %+.2f cents)" % (n, hz, cents))
    vpath = os.path.join(evidence, "ws_tone.vgm")
    if os.path.exists(vpath):
        with open(vpath, "rb") as f:
            _, events = parse_vgm(f.read())
        lo = next((b for _, k, a, b in events if k == "reg" and a == 0x80 and b), None)
        hi = next((b for _, k, a, b in events if k == "reg" and a == 0x81 and b), None)
        d = (hi or 0) << 8 | (lo or 0)
        check(2048 - d == n, "ws_tone.vgm: CH1 frequency register %d = 2048 - %d" % (d, n))
    wpath = os.path.join(evidence, "ws_tone.wav")
    if check(os.path.exists(wpath), "ws_tone.wav present"):
        rate, x = read_wav(wpath)
        f0 = fundamental(rate, x)
        check(abs(f0 - hz) <= TOLERANCE_HZ, "ws_tone.wav: fundamental %.3f Hz (expected %.3f)" % (f0, hz))
        if vgm2wav and os.path.exists(vpath):
            out = os.path.join(evidence, "ws_tone_libvgm.wav")
            subprocess.run([vgm2wav, vpath, out], check=True)
            rate2, y = read_wav(out)
            f1 = fundamental(rate2, y)
            check(abs(f1 - f0) <= TOLERANCE_HZ, "libvgm vgm2wav: fundamental %.3f Hz = the engine's %.3f" % (f1, f0))


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("evidence")
    ap.add_argument("--vgm2wav")
    a = ap.parse_args(argv)
    check_trace(a.evidence, "ws_trace.txt", "ws_song.bin")
    check_trace(a.evidence, "wsc_trace.txt", "wsc_song.bin")
    for vgm_name, image_name in (("ws_song.vgm", "ws_song.bin"), ("wsc_vgm.vgm", "wsc_vgm.bin"),
                                 ("ws_tone.vgm", "ws_tone.bin")):
        check_vgm(a.evidence, vgm_name, image_name)
    check_pitch(a.evidence, a.vgm2wav)
    print("%d failure(s)" % len(failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
