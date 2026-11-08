#!/usr/bin/env python3
"""Compare the native SID machine's output with independent references (developer evidence, not CI).

Inputs come from the host test (tools/audio/run_native_host_test.sh <dir>): <dir>/sid_gt.sid (GoatTracker
2.77's own packing of the test song, gt2reloc -I0 -J1), <dir>/sid_trace.txt (the SID writes of the Thor
Tracker image of the same song on our machine, per player tick from power-on) and <dir>/sid_gt_8580.wav /
sid_gt_6581.wav (30 s from row 0, 48 kHz).

  1. CPU reference: py65 (an independent 6502 emulator) runs the PSID's init/play routines once per PAL
     frame and logs SID register writes; they must equal sid_trace.txt frame for frame.
  2. Audio reference: sidplayfp (libsidplayfp's C64 environment and its reSID build, `--resid`, and reSIDfp,
     `--residfp`) renders the PSID with the chip model forced. After aligning the renders (sidplayfp starts
     from the C64's reset; ours from row 0), the 20 ms loudness envelopes must correlate, the log spectra
     must match, and the strongest partial must fall in the same pitch class in most windows.

Requirements: python3 with numpy and py65 (`python3 -m venv /tmp/sidref && /tmp/sidref/bin/pip install numpy
py65`); sidplayfp on PATH or in the `ubuntu` distrobox (`sudo apt-get install -y sidplayfp`, 2.6.x).
Usage: tools/audio/sid_reference_check.py <evidence-dir>
Exit status 1 if a comparison is below its threshold.
"""
import argparse
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

RATE = 48000
SID_BASE = 0xD400
IDLE = 0x0FF0
# Gated against sidplayfp's reSID (the same emulator lineage); its 6581 filter is configured differently
# (libsidplayfp's filter bias / curve), so the 6581 bounds are looser. reSIDfp results are reported only.
THRESHOLDS = {"8580": {"envelope": 0.90, "spectrum": 0.80, "pitch": 0.90},
              "6581": {"envelope": 0.80, "spectrum": 0.80, "pitch": 0.90}}


def read_trace(path: Path) -> list:
    frames = []
    for line in path.read_text().splitlines():
        if line.startswith("#"):
            continue
        _, rest = line.split(":", 1)
        frames.append(rest.split())
    return frames


def py65_trace(psid: bytes, frames: int) -> list:
    from py65.devices.mpu6502 import MPU
    from py65.memory import ObservableMemory

    off = int.from_bytes(psid[6:8], "big")
    load = int.from_bytes(psid[8:10], "big")
    init = int.from_bytes(psid[10:12], "big")
    play = int.from_bytes(psid[12:14], "big")
    data = psid[off:]
    if load == 0:
        load = data[0] | data[1] << 8
        data = data[2:]
    mem = ObservableMemory()
    for i, b in enumerate(data):
        mem[load + i] = b
    writes = []
    mem.subscribe_to_write(range(SID_BASE, SID_BASE + 0x20), lambda a, v: writes.append(f"{a - SID_BASE:02X}={v:02X}"))
    mpu = MPU(memory=mem)
    mpu.sp = 0xFD

    def call(addr, a):
        ret = IDLE - 1
        mpu.stPush(ret >> 8)
        mpu.stPush(ret & 0xFF)
        mpu.a = a
        mpu.pc = addr
        while mpu.pc != IDLE:
            mpu.step()

    call(init, int.from_bytes(psid[16:18], "big") - 1)
    writes.clear()
    out = []
    for _ in range(frames):
        call(play, 0)
        out.append(list(writes))
        writes.clear()
    return out


def load_wav(path: Path) -> np.ndarray:
    with wave.open(str(path)) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
        if w.getframerate() != RATE:
            sys.exit(f"{path}: {w.getframerate()} Hz, expected {RATE}")
        return x[::w.getnchannels()]


def sidplayfp(psid: Path, engine: str, model: str, out: Path):
    args = ["sidplayfp", f"--{engine}", f"-m{model}f", "-vpf", f"-f{RATE}", "-m", "-t34", f"-w{out}", str(psid)]
    if shutil.which("sidplayfp"):
        subprocess.run(args, check=True, capture_output=True)
    else:
        subprocess.run(["distrobox", "enter", "ubuntu", "--", "bash", "-c", " ".join(f"'{a}'" for a in args)],
                       check=True, capture_output=True)


def envelope(x: np.ndarray, win: int = RATE // 50) -> np.ndarray:
    n = len(x) // win
    return np.sqrt(np.mean(x[:n * win].reshape(n, win) ** 2, axis=1))


def compare(ours: np.ndarray, ref: np.ndarray) -> dict:
    """Aligns ref to ours by envelope cross-correlation (20 ms, then 1 ms windows), then compares 20 ms
    loudness envelopes, log spectra and pitch classes."""
    eo, er = envelope(ours), envelope(ref)
    coarse, lag = -2.0, 0
    for k in range(0, min(len(er) - len(eo) // 2, 300)):
        n = min(len(eo), len(er) - k)
        c = np.corrcoef(eo[:n], er[k:k + n])[0, 1]
        if c > coarse:
            coarse, lag = c, k
    ms = RATE // 1000
    fo, fr = envelope(ours, ms), envelope(ref, ms)
    fine, shift_ms = -2.0, lag * 20
    for k in range(max(0, lag * 20 - 40), lag * 20 + 41):
        n = min(len(fo), len(fr) - k)
        c = np.corrcoef(fo[:n], fr[k:k + n])[0, 1]
        if c > fine:
            fine, shift_ms = c, k
    shift = shift_ms * ms
    n = min(len(ours), len(ref) - shift)
    a, b = ours[:n], ref[shift:shift + n]
    best = np.corrcoef(envelope(a), envelope(b))[0, 1]
    win = 4096
    spec_c, same_pc, count = [], 0, 0
    for s in range(0, n - win, win):
        fa = np.abs(np.fft.rfft(a[s:s + win] * np.hanning(win)))
        fb = np.abs(np.fft.rfft(b[s:s + win] * np.hanning(win)))
        if fa.sum() < 1e-3 or fb.sum() < 1e-3:
            continue
        spec_c.append(np.corrcoef(np.log(fa + 1e-6), np.log(fb + 1e-6))[0, 1])
        lo = int(40 * win / RATE)
        pa, pb = lo + np.argmax(fa[lo:]), lo + np.argmax(fb[lo:])
        ha, hb = pa * RATE / win, pb * RATE / win
        same_pc += round(12 * np.log2(ha / hb)) % 12 == 0
        count += 1
    return {"lag_s": shift / RATE, "envelope": best, "spectrum": float(np.mean(spec_c)), "pitch": same_pc / max(count, 1)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("evidence", type=Path)
    args = ap.parse_args()
    d = args.evidence
    ok = True
    psid = (d / "sid_gt.sid").read_bytes()
    ours = read_trace(d / "sid_trace.txt")
    ref = py65_trace(psid, len(ours))
    same = sum(o == r for o, r in zip(ours, ref))
    writes = sum(len(f) for f in ref)
    print(f"py65 running sid_gt.sid: {same}/{len(ours)} frames identical ({writes} SID writes)")
    ok &= same == len(ours)
    with tempfile.TemporaryDirectory(dir="/tmp") as tmp:
        for engine in ["resid", "residfp"]:
            for model, ours_wav in [("n", "sid_gt_8580.wav"), ("o", "sid_gt_6581.wav")]:
                out = Path(tmp) / f"{engine}_{model}.wav"
                sidplayfp(d / "sid_gt.sid", engine, model, out)
                r = compare(load_wav(d / ours_wav), load_wav(out))
                name = f"{'8580' if model == 'n' else '6581'} vs sidplayfp --{engine}"
                passed = all(r[k] >= v for k, v in THRESHOLDS[name[:4]].items())
                ok &= passed or engine == "residfp"  # reSIDfp is another filter model: reported, not gated
                print(f"{name}: lag {r['lag_s']:.3f} s, envelope {r['envelope']:.3f}, log-spectrum {r['spectrum']:.3f}, "
                      f"same pitch class {100 * r['pitch']:.0f}%{'' if passed else ' (below threshold)'}")
    print("PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
