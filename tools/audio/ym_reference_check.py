#!/usr/bin/env python3
"""Independent checks of the YM2612 host test's evidence (developer evidence, not part of the build), for the test
song (ym_*) and the PCM + FM 3 song (ym_pcm_*) of tools/audio/run_native_host_test.sh EVIDENCE_DIR:

  1. The Python reference player (ym/ym_ref.py) on the test image (ym_image.bin) writes exactly the C player's
     per-tick trace (ym_trace.txt), PCM commands included.
  2. The VGM (the XGM2 driver's chip writes), parsed here from the file format alone: header fields, the YM2612 PCM
     data block and one 0x8n per byte of it, waits summing to the header's sample count, the loop point on the 0xE0
     that rewinds the PCM data, and the register state at every frame's end equal to the player's after that frame's
     writes ($A4-$A6/$AC-$AE without the driver's key flags; keys, timer and DAC registers left to the driver).
  3. The XGM2 exports, parsed here: .xgm (unpacked) and .xgc (packed) headers, sample tables and banks agree; every
     packed block decodes (literal-LZ, no block across a 256-byte page but by the page marker) to exactly the .xgm's
     stream bytes; both streams span the reference player's loop and return to the same frame.
  4. With --xgm2tool (SGDK's src/bin/xgm2tool.jar; needs java): the upstream compiler reads the engine's .xgm and
     emits .xgc; independently unpacking it recovers the same commands, loop targets and PCM bank.
  5. With --vgm2wav (libvgm's vgm2wav, env VGM2WAV_YM2612_CORE=NUKE|GPGX selecting its YM2612 core; MAME SN76489):
     the VGM rendered by that independent player against the engine's own 30 s render (ym_song.wav): loudness envelope
     correlation, log-spectrum correlation, dominant pitch class; Nuked-OPN2 checked, Genesis Plus GX reported.

    python3 tools/audio/ym_reference_check.py EVIDENCE_DIR [--xgm2tool JAR] [--vgm2wav PATH]   (5 needs numpy)
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
VGM_RATE = 44100
MCLK = {0: 53693175, 1: 53203424}
TICK_MCLK = {0: 3420 * 262, 1: 3420 * 313}
IGNORED = {(0, 0x24), (0, 0x25), (0, 0x26), (0, 0x27), (0, 0x28), (0, 0x2A), (0, 0x2B)}


def check(ok, what):
    global failures
    print(("PASS " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


def read(path):
    with open(path, "rb") as f:
        return f.read()


# ---- trace -------------------------------------------------------------------------------------------------------

def parse_tick(line):
    """(writes, pcm) of one trace line; the key-off bar dropped."""
    writes, pcm = [], [-1, -1, -1]
    for token in line.partition(":")[2].split():
        if token == "|":
            continue
        reg, _, val = token.partition("=")
        if reg == "PSG":
            writes.append((ym_ref.PSG, 0, int(val, 16)))
        elif reg.startswith("P"):
            pcm[int(reg[1:])] = int(val)
        else:
            writes.append((int(reg[0]), int(reg[1:], 16), int(val, 16)))
    return writes, pcm


class State:
    """YM register shadow (both ports) and PSG registers as the writes leave them."""

    def __init__(self):
        self.ym = {}
        self.psg = {}
        self.latch = None

    def write(self, kind, reg, val):
        if kind == ym_ref.PSG:
            if val & 0x80:
                self.latch = (val >> 5) & 3, (val >> 4) & 1
                ch, typ = self.latch
                old = self.psg.get(self.latch, 0)
                self.psg[self.latch] = val & (7 if (ch, typ) == (3, 0) else 15) if typ or ch == 3 else (old & 0x3F0) | (val & 15)
            elif self.latch and self.latch[1] == 0 and self.latch[0] < 3:
                self.psg[self.latch] = (self.psg.get(self.latch, 0) & 15) | (val & 0x3F) << 4
            return
        if (kind, reg) in IGNORED:  # the driver's timer, keys and DAC; TL and SL/RR equal the player's (attenuation 0)
            return
        if 0xA4 <= reg <= 0xAE and (reg & 4):
            val &= 0x3F
        self.ym[(kind, reg)] = val

    def compare(self, other):
        keys = set(self.ym) | set(other.ym)
        bad = [k for k in keys if self.ym.get(k) != other.ym.get(k)]
        bad += [k for k in set(self.psg) | set(other.psg) if self.psg.get(k) != other.psg.get(k)]
        return bad


def player_states(image, trace):
    """The player's state after each tick's writes, from the reset batch on."""
    s = State()
    for w in ym_ref.MACHINE_RESET + ym_ref.trace(image, 0)[0][1]:
        s.write(*w)
    out = []
    for line in trace:
        for w in parse_tick(line)[0]:
            s.write(*w)
        snap = State()
        snap.ym, snap.psg = dict(s.ym), dict(s.psg)
        out.append(snap)
    return out


# ---- VGM ---------------------------------------------------------------------------------------------------------

def parse_vgm(data):
    """(header, [(sample, kind, reg, val)], dac byte count used, offsets of commands, end, data block size, e0 offsets)."""
    h = {
        "eof": struct.unpack_from("<I", data, 4)[0] + 4,
        "version": struct.unpack_from("<I", data, 8)[0],
        "sn76489": struct.unpack_from("<I", data, 0x0C)[0],
        "samples": struct.unpack_from("<I", data, 0x18)[0],
        "loop_offset": struct.unpack_from("<I", data, 0x1C)[0],
        "loop_samples": struct.unpack_from("<I", data, 0x20)[0],
        "rate": struct.unpack_from("<I", data, 0x24)[0],
        "ym2612": struct.unpack_from("<I", data, 0x2C)[0],
        "data": 0x34 + struct.unpack_from("<I", data, 0x34)[0],
    }
    at, t, writes, dac, block, e0 = h["data"], 0, [], 0, b"", {}
    offsets = set()
    while True:
        offsets.add(at)
        cmd = data[at]
        if cmd in (0x52, 0x53):
            writes.append((t, cmd - 0x52, data[at + 1], data[at + 2]))
            at += 3
        elif cmd == 0x50:
            writes.append((t, ym_ref.PSG, 0, data[at + 1]))
            at += 2
        elif cmd == 0x61:
            t += struct.unpack_from("<H", data, at + 1)[0]
            at += 3
        elif cmd in (0x62, 0x63):
            t += 735 if cmd == 0x62 else 882
            at += 1
        elif 0x70 <= cmd <= 0x7F:
            t += (cmd & 15) + 1
            at += 1
        elif 0x80 <= cmd <= 0x8F:
            writes.append((t, 0, 0x2A, block[dac] if dac < len(block) else -1))
            dac += 1
            t += cmd & 15
            at += 1
        elif cmd == 0x67:
            assert data[at + 1] == 0x66 and data[at + 2] == 0x00, "YM2612 PCM data block"
            size = struct.unpack_from("<I", data, at + 3)[0]
            block = data[at + 7:at + 7 + size]
            at += 7 + size
        elif cmd == 0xE0:
            e0[at] = struct.unpack_from("<I", data, at + 1)[0]
            dac = e0[at]
            at += 5
        elif cmd == 0x66:
            break
        else:
            raise ValueError("unexpected VGM command %02X at %X" % (cmd, at))
    return h, writes, offsets, at + 1, len(block), e0, t


def frame_states(writes, region, frames, samples_per_frame=None):
    """Register state at each frame's end: frame f ends at sample (f + 1) * tick (the console's tick, or the VGM's)."""
    s, out, i = State(), [], 0
    for f in range(frames):
        end = (f + 1) * samples_per_frame if samples_per_frame else (f + 1) * TICK_MCLK[region] * VGM_RATE // MCLK[region]
        while i < len(writes) and writes[i][0] < end:
            s.write(*writes[i][1:])
            i += 1
        snap = State()
        snap.ym, snap.psg = dict(s.ym), dict(s.psg)
        out.append(snap)
    return out


def check_vgm(name, vgm, image, trace, states):
    h, writes, offsets, end, block, e0, total = parse_vgm(vgm)
    pal = image.region
    check(vgm[:4] == b"Vgm " and h["version"] == 0x171 and h["ym2612"] == (7600489 if pal else 7670453)
          and h["data"] == 0x100, "%s: VGM 1.71 header, data at 0x100" % name)
    check(h["eof"] == len(vgm) and vgm[end:end + 4] in (b"Gd3 ", b""), "%s: end-of-file offset, GD3 after the data" % name)
    check(total == h["samples"], "%s: waits sum to %d samples (%.2f s)" % (name, h["samples"], h["samples"] / VGM_RATE))
    dacs = sum(1 for w in writes if w[2] == 0x2A and w[1] == 0)
    check(dacs == block, "%s: one 0x8n per PCM data byte (%d)" % (name, block))
    loop_at = 0x1C + h["loop_offset"]
    check(not h["loop_offset"] or (loop_at in offsets and loop_at in e0), "%s: loop point on the 0xE0 rewinding the PCM data"
          % name)
    ticks = round(total * MCLK[pal] / (VGM_RATE * TICK_MCLK[pal]))
    want = min(len(trace), len(states))
    got = frame_states(writes, pal, ticks)
    # Tick t executes the next frame, or none when the driver skipped it (its PCM buffer too low at the interrupt: the
    # frame executes a tick later, the chips holding the previous frame's state meanwhile).
    f, skipped, bad = 0, 0, []
    for t in range(ticks):
        if f >= want:
            break
        if not got[t].compare(states[f]):
            f += 1
        elif f and not got[t].compare(states[f - 1]):
            skipped += 1
        else:
            bad.append((t, f))
            f += 1
    check(not bad, "%s: register state after each executed frame = the player's (%d frames in %d ticks, %d skipped%s)"
          % (name, f, ticks, skipped, "" if not bad else ", first differing frame %d at tick %d: %s"
             % (bad[0][1], bad[0][0], got[bad[0][0]].compare(states[bad[0][1]])[:6])))
    if not image.pcm_variants:
        check(not skipped, "%s: without PCM the driver skips no frame (%d)" % (name, skipped))


# ---- XGM2 --------------------------------------------------------------------------------------------------------

FM_SIZE = {0x1: 2, 0x2: 31, 0x3: 3, 0x4: 1, 0x5: 1, 0x6: 1, 0x7: 1, 0x8: 3, 0x9: 2, 0xA: 2, 0xB: 2, 0xC: 2, 0xD: 2}


def fm_frames(data):
    """Frames the FM stream spans up to its loop command, the offsets where frames start, the loop target."""
    at, frames, starts = 0, 0, [0]
    while True:
        c = data[at]
        hi = c >> 4
        if c == 0xFF:
            frames += 1
            return frames, starts, struct.unpack_from("<I", data[at + 1:at + 4] + b"\0")[0], at
        if hi == 0:
            n, size = ((c & 15) + 1, 1) if c != 0x0F else (data[at + 1] + 16, 2)
            frames += n
            at += size
            starts.append(at)
            continue
        if hi == 0xE:
            at += 1 + 2 * ((c & 7) + 1)
        elif hi == 0xF:
            at += 2 if c in (0xF8, 0xF9) else 1
        else:
            at += FM_SIZE[hi]


def psg_frames(data):
    at, frames, starts = 0, 0, [0]
    while True:
        c = data[at]
        hi = c >> 4
        if c == 0x0F:
            frames += 1
            return frames, starts, struct.unpack_from("<I", data[at + 1:at + 4] + b"\0")[0], at
        if hi == 0:
            n, size = ((c & 15) + 1, 1) if c != 0x0E else (data[at + 1] + 15, 2)
            frames += n
            at += size
            starts.append(at)
            continue
        if hi in (1, 2, 3):
            end = (hi == 1 and c & 1) or hi == 3
            at += 2
        else:
            end = hi in (4, 5, 6, 7, 0xC, 0xD, 0xE, 0xF) and c & 8
            at += 1
        if end:
            frames += 1
            starts.append(at)


def unpack(data):
    """Decodes a packed stream block by block (the zero padding decodes to empty blocks): (bytes, stream offset of each
    block -> its unpacked offset, every block <= 255 bytes and no literal run across a 256-byte page)."""
    out, at, blocks, ok = bytearray(), 0, {}, True
    while at < len(data):
        blocks[at] = len(out)
        block = bytearray()
        while True:
            h = data[at]
            page = at & ~0xFF
            at += 1
            lit, mat = h >> 5, h & 31
            if not lit and not mat:
                break
            block += data[at:at + lit]
            at += lit
            if lit and (at - 1) & ~0xFF != page:
                ok = False
            if mat >= 2:
                off = data[at]
                at += 1
                for _ in range(mat):
                    index = len(out) + len(block) - (256 - off)
                    if index < 0:
                        raise ValueError("packed match precedes the stream")
                    block.append(out[index] if index < len(out) else block[index - len(out)])
        out += block
        ok = ok and len(block) <= 255
    return bytes(out), blocks, ok


def parse_xgm2(data, compiled):
    base = 0 if compiled else 4
    if not compiled:
        assert data[:4] == b"XGM2"
    version, flags = data[base], data[base + 1]
    slen, fmlen, psglen = (struct.unpack_from("<H", data, base + 2 + 2 * i)[0] * 256 for i in range(3))
    sid = [struct.unpack_from("<H", data, base + 8 + 2 * i)[0] for i in range(124)]
    at = base + 0x100
    return {"version": version, "flags": flags, "sid": sid, "samples": data[at:at + slen],
            "fm": data[at + slen:at + slen + fmlen], "psg": data[at + slen + fmlen:at + slen + fmlen + psglen]}


def pcm_samples(image):
    samples = {}
    for i, (start, end) in enumerate(zip(image["sid"], image["sid"][1:])):
        if start != 0xFFFF and end != 0xFFFF:
            assert 0 <= start * 256 <= end * 256 <= len(image["samples"])
            samples[i + 1] = image["samples"][start * 256:end * 256]
    return samples


def check_xgm2(name, xgm, xgc, ticks):
    a, b = parse_xgm2(xgm, False), parse_xgm2(xgc, True)
    check(a["version"] == b["version"] == 0x10 and a["flags"] & 0x08 == 0 and b["flags"] & 0x08 and a["flags"] & 0x04,
          "%s: XGM2 version 0x10, .xgm unpacked with GD3, .xgc packed" % name)
    check(pcm_samples(a) == pcm_samples(b), "%s: same sample IDs and PCM bytes (%d-byte bank)"
          % (name, len(a["samples"])))
    fm_n, fm_starts, fm_loop, fm_end = fm_frames(a["fm"])
    psg_n, psg_starts, psg_loop, psg_end = psg_frames(a["psg"])
    check(fm_n == psg_n == ticks, "%s: streams span the reference's %d musical frames (FM %d, PSG %d)" % (name, ticks, fm_n, psg_n))
    check(fm_loop == 0xFFFFFF or (fm_loop in fm_starts and psg_loop in psg_starts), "%s: both loops land on a frame start" % name)
    for key, loop, end, frames in (("fm", fm_loop, fm_end, fm_frames), ("psg", psg_loop, psg_end, psg_frames)):
        out, blocks, ok = unpack(b[key])
        n, _, packed_loop, packed_end = frames(out)
        check(n == ticks and packed_end == end and out[:end + 1] == a[key][:end + 1] and ok,
              "%s: packed %s stream decodes to the .xgm's commands, blocks <= 255 bytes, no page crossed" % (name, key.upper()))
        check(loop == 0xFFFFFF == packed_loop or blocks.get(packed_loop) == loop,
              "%s: packed %s loop -> the unpacked loop frame" % (name, key.upper()))


# ---- upstream xgm2tool -------------------------------------------------------------------------------------------

def check_xgm2tool(name, jar, xgm_path, xgm, ticks):
    # SGDK 1.08's XGM-to-VGM path casts a parsed base command to LoopCommand and throws even on its own files.
    # Exercise the consumer path instead: XGM-to-packed-XGC, with no patches to the upstream tool.
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "song.xgc")
        r = subprocess.run(["java", "-jar", jar, xgm_path, out], capture_output=True, text=True)
        ok = r.returncode == 0 and os.path.exists(out)
        check(ok, "%s: unmodified xgm2tool compiles the engine's .xgm" % name)
        if not ok:
            print(r.stdout, r.stderr)
            return
        compiled = read(out)
    check_xgm2(name + " via xgm2tool", xgm, compiled, ticks)


# ---- audio -------------------------------------------------------------------------------------------------------

def read_wav(path):
    import numpy as np
    with wave.open(path) as w:
        n, ch, rate = w.getnframes(), w.getnchannels(), w.getframerate()
        x = np.frombuffer(w.readframes(n), dtype=np.int16).reshape(-1, ch).astype(float) / 32768
    return x.mean(axis=1), rate


def highpass(x, rate):
    """First-order 20 Hz high-pass: libvgm's Nuked core keeps the DAC offset the engine's resampler removes."""
    import numpy as np
    k = np.exp(-2 * np.pi * 20 / rate)
    y = np.empty_like(x)
    prev_x = prev_y = 0.0
    for i, v in enumerate(x):
        prev_y = k * (prev_y + v - prev_x)
        prev_x = v
        y[i] = prev_y
    return y


def compare_audio(engine_wav, vgm_path, vgm2wav, core, seconds):
    """Both renders run at the console's 59.92 Hz (the VGM holds the driver's own timing)."""
    import numpy as np
    a, rate = read_wav(engine_wav)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "vgm.wav")
        subprocess.run([vgm2wav, "--samplerate", str(rate), "--loops", "5", "--fade", "0", vgm_path, out],
                       check=True, stdout=subprocess.DEVNULL, env=dict(os.environ, VGM2WAV_YM2612_CORE=core))
        b, _ = read_wav(out)
    n = min(len(a), len(b), int(seconds * rate))
    a, b = highpass(a[:n], rate), highpass(b[:n], rate)
    win = rate // 50
    ea = np.sqrt(np.add.reduceat(a ** 2, np.arange(0, n - win, win)) / win)
    eb = np.sqrt(np.add.reduceat(b ** 2, np.arange(0, n - win, win)) / win)
    lag = max(range(-5, 6), key=lambda k: np.corrcoef(ea[10 + k:len(ea) - 10 + k], eb[10:len(eb) - 10])[0, 1])
    env = np.corrcoef(ea[10 + lag:len(ea) - 10 + lag], eb[10:len(eb) - 10])[0, 1]
    shift = lag * win
    a, b = (a[shift:], b) if shift > 0 else (a, b[-shift:]) if shift < 0 else (a, b)
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
        pa, pb = freqs[band][np.argmax(sa[band])], freqs[band][np.argmax(sb[band])]
        counted += 1
        same_pc += round(12 * np.log2(pa / pb)) % 12 == 0
    print("  libvgm %s YM2612 + MAME SN76489 (the driver's VGM) vs engine: envelope correlation %.3f (lag %d ms), "
          "log-spectrum correlation %.3f, same pitch class %.0f%% of %d windows"
          % (core, env, lag * 20, float(np.mean(spec)), 100.0 * same_pc / max(1, counted), counted))
    return env, float(np.mean(spec)), same_pc / max(1, counted)


def stream_ticks(image):
    """Musical frames through the second loop arrival, independent of the driver's physical refill delays."""
    player = ym_ref.Player(image)
    seen = False
    for ticks in range(200000):
        if player.position() == (image.loop, 0, 0):
            if seen:
                return ticks
            seen = True
        player.tick()
    raise ValueError("reference player did not reach the loop twice")


def check_song(prefix, args):
    d = args.dir
    image = ym_ref.Image(read(os.path.join(d, prefix + "_image.bin")))
    with open(os.path.join(d, prefix + "_trace.txt")) as f:
        trace = [line.rstrip("\n") for line in f]
    ref = [ym_ref.format_tick(*t) for t in ym_ref.trace(image, len(trace))[1:]]
    same = sum(1 for a, b in zip(trace, ref) if a == b)
    check(same == len(trace), "%s: reference player = engine trace in %d/%d ticks" % (prefix, same, len(trace)))
    states = player_states(image, trace)
    vgm_path = os.path.join(d, prefix + "_song.vgm")
    vgm = read(vgm_path)
    check_vgm(prefix, vgm, image, trace, states)
    xgm_path = os.path.join(d, prefix + "_song.xgm")
    xgm, xgc = read(xgm_path), read(os.path.join(d, prefix + "_song.xgc"))
    h = parse_vgm(vgm)[0]
    ticks = stream_ticks(image)
    check_xgm2(prefix, xgm, xgc, ticks)
    if args.xgm2tool:
        check_xgm2tool(prefix, args.xgm2tool, xgm_path, xgm, ticks)
    if args.vgm2wav and prefix == "ym":
        wav = os.path.join(d, "ym_song.wav")
        first = h["samples"] / VGM_RATE
        compare_audio(wav, vgm_path, args.vgm2wav, "GPGX", first)
        env, spec, pc = compare_audio(wav, vgm_path, args.vgm2wav, "NUKE", first)
        check(env > 0.8, "loudness envelope correlation with Nuked-OPN2 > 0.8")
        check(spec > 0.75, "log-spectrum correlation with Nuked-OPN2 > 0.75")
        check(pc > 0.7, "dominant pitch class agrees in > 70% of windows")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--xgm2tool")
    ap.add_argument("--vgm2wav")
    args = ap.parse_args()
    for prefix in ("ym", "ym_pcm"):
        check_song(prefix, args)
    print("FAIL" if failures else "PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
