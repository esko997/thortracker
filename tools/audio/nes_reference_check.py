#!/usr/bin/env python3
"""Compare the native NES machine's output with independent references (developer evidence, not CI).

Inputs come from the host test (tools/audio/run_native_host_test.sh <dir>): <dir>/nes_demo.nsf (the
player image + demo song as an NSF), <dir>/nes_trace.txt (per-frame APU writes) and <dir>/nes_demo.wav.

  1. CPU reference: py65 (an independent 6502 emulator) runs the NSF's init/play routines with the
     same calling convention and logs APU register writes per frame; they must equal nes_trace.txt.
  2. Audio reference: libgme (Game_Music_Emu: its own 6502 + blargg's Nes_Apu) renders the NSF;
     its loudness envelope and spectra are compared with nes_demo.wav.
  3. Optional, with --famistudio <dir of the FamiStudio 4.5.3 Linux release> (needs `dotnet`, e.g.
     in the ubuntu distrobox): FamiStudio's own WAV export and own NSF export of Shatterhand.fms,
     compared the same way (FamiStudio's player and NSF kernel are separate code paths from ours).

Requirements: python3 with numpy and py65 (`python3 -m venv /tmp/nesref && /tmp/nesref/bin/pip install
numpy py65`), libgme.so.0 (Fedora: game-music-emu, Ubuntu: libgme0).
Usage: tools/audio/nes_reference_check.py <evidence-dir> [--famistudio DIR]
Exit status 1 if a comparison is below its threshold.
"""
import argparse
import ctypes
import shutil
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

RATE = 48000
SECONDS = 30
FRAME_HZ = 1789773 / 29780.5


def read_trace(path: Path) -> list:
    frames = []
    for line in path.read_text().splitlines():
        if line.startswith("#"):
            continue
        _, rest = line.split(":", 1)
        frames.append(rest.split())
    return frames


def py65_trace(nsf: bytes, frames: int) -> list:
    from py65.devices.mpu6502 import MPU
    from py65.memory import ObservableMemory

    load = nsf[8] | nsf[9] << 8
    init = nsf[10] | nsf[11] << 8
    play = nsf[12] | nsf[13] << 8
    mem = ObservableMemory()
    data = nsf[128:]
    for i, b in enumerate(data):
        mem[load + i] = b
    log = []

    def on_write(addr, value):
        log.append(f"{addr:04X}={value:02X}")

    mem.subscribe_to_write(range(0x4000, 0x4018), on_write)
    mpu = MPU(memory=mem)
    sentinel = 0x8006  # NES_PLAYER_IDLE

    def call(addr, a):
        mpu.sp = 0xFD
        ret = sentinel - 1
        mem[0x1FD] = ret >> 8
        mem[0x1FC] = ret & 0xFF
        mpu.sp = 0xFB
        mpu.a, mpu.x, mpu.y = a, 0, 0
        mpu.p = 0x24
        mpu.pc = addr
        steps = 0
        while mpu.pc != sentinel:
            mpu.step()
            steps += 1
            if steps > 100000:
                raise RuntimeError(f"runaway call at ${addr:04X}")

    call(init, 0)
    log.clear()  # the host test trace starts at the first play frame
    out = []
    for _ in range(frames):
        call(play, 0)
        out.append(list(log))
        log.clear()
    return out


def gme_render(path: Path, seconds: int, mute_mask: int = 0) -> np.ndarray:
    """Renders track 0; mute_mask bits: 0 square 1, 1 square 2, 2 triangle, 3 noise, 4 DMC."""
    lib = ctypes.CDLL("libgme.so.0")
    lib.gme_open_file.restype = ctypes.c_char_p
    lib.gme_start_track.restype = ctypes.c_char_p
    lib.gme_play.restype = ctypes.c_char_p
    emu = ctypes.c_void_p()
    err = lib.gme_open_file(str(path).encode(), ctypes.byref(emu), RATE)
    if err:
        raise RuntimeError(err.decode())
    lib.gme_ignore_silence(emu, 1)
    lib.gme_mute_voices(emu, mute_mask)
    err = lib.gme_start_track(emu, 0)
    if err:
        raise RuntimeError(err.decode())
    n = RATE * seconds * 2
    buf = (ctypes.c_short * n)()
    err = lib.gme_play(emu, n, buf)
    if err:
        raise RuntimeError(err.decode())
    lib.gme_delete(emu)
    return np.frombuffer(buf, dtype=np.int16).reshape(-1, 2).mean(axis=1) / 32768.0


def read_wav(path: Path, seconds: int) -> np.ndarray:
    with wave.open(str(path)) as w:
        rate, ch = w.getframerate(), w.getnchannels()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768.0
    data = data.reshape(-1, ch).mean(axis=1)
    if rate != RATE:
        raise RuntimeError(f"{path}: {rate} Hz, expected {RATE}")
    return data[: RATE * seconds]


def envelope(x: np.ndarray) -> np.ndarray:
    hop = RATE // 60
    n = len(x) // hop
    return np.sqrt((x[: n * hop].reshape(n, hop) ** 2).mean(axis=1))


def compare(name: str, ours: np.ndarray, ref: np.ndarray) -> dict:
    a, b = envelope(ours), envelope(ref)
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    best = (-2.0, 0)
    for lag in range(-30, 31):
        x = a[max(0, lag): n + min(0, lag)]
        y = b[max(0, -lag): n - max(0, lag)]
        r = float(np.corrcoef(x, y)[0, 1])
        if r > best[0]:
            best = (r, lag)
    env_r, lag = best
    shift = lag * (RATE // 60)
    xo = ours[max(0, shift):]
    yo = ref[max(0, -shift):]
    m = min(len(xo), len(yo))
    xo, yo = xo[:m], yo[:m]
    win, hop = 4096, 2048
    w = np.hanning(win)
    spec_r, pitch_same, windows = [], 0, 0
    freqs = np.fft.rfftfreq(win, 1 / RATE)
    band = (freqs > 40) & (freqs < 8000)
    for s in range(0, m - win, hop):
        fa = np.abs(np.fft.rfft(xo[s: s + win] * w))[band]
        fb = np.abs(np.fft.rfft(yo[s: s + win] * w))[band]
        if fa.sum() < 1e-3 or fb.sum() < 1e-3:
            continue
        windows += 1
        spec_r.append(float(np.corrcoef(np.log1p(fa * 100), np.log1p(fb * 100))[0, 1]))
        pa, pb = freqs[band][fa.argmax()], freqs[band][fb.argmax()]
        cents = abs(1200 * np.log2(pa / pb)) % 1200 if pa > 0 and pb > 0 else 600.0
        pitch_same += min(cents, 1200 - cents) < 30
    res = {
        "envelope_r": env_r,
        "lag_frames": lag,
        "spectrum_r": float(np.mean(spec_r)) if spec_r else 0.0,
        "peak_pitch_agree": pitch_same / windows if windows else 0.0,
        "rms_ours": float(np.sqrt((xo ** 2).mean())),
        "rms_ref": float(np.sqrt((yo ** 2).mean())),
    }
    print(f"{name}: envelope r {res['envelope_r']:.3f} (lag {lag} frames), log-spectrum r {res['spectrum_r']:.3f}, "
          f"strongest partial agrees (same pitch class within 30 cents) in {100 * res['peak_pitch_agree']:.0f}% of "
          f"windows, rms ours {res['rms_ours']:.4f} / ref {res['rms_ref']:.4f}")
    return res


def famistudio(app: Path, out: Path) -> tuple:
    song = app / "Demo Songs" / "Shatterhand.fms"
    cmd = ["dotnet", str(app / "FamiStudio.dll")]
    prefix = [] if shutil.which("dotnet") else ["distrobox", "enter", "ubuntu", "--"]
    wav, tonal, nsf = (out / "famistudio_shatterhand.wav", out / "famistudio_shatterhand_tonal.wav",
                       out / "famistudio_shatterhand.nsf")
    export = [str(song), "wav-export"]
    opts = ["-wav-export-rate:48000", f"-wav-export-duration:{SECONDS}"]
    subprocess.run(prefix + cmd + export + [str(wav)] + opts, check=True, capture_output=True)
    subprocess.run(prefix + cmd + export + [str(tonal)] + opts + ["-wav-export-channels:7"], check=True, capture_output=True)
    subprocess.run(prefix + cmd + [str(song), "nsf-export", str(nsf)], check=True, capture_output=True)
    return wav, tonal, nsf


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dir", type=Path)
    ap.add_argument("--famistudio", type=Path)
    args = ap.parse_args()
    d = args.dir
    ok = True

    ours_trace = read_trace(d / "nes_trace.txt")
    ref_trace = py65_trace((d / "nes_demo.nsf").read_bytes(), len(ours_trace))
    mismatch = next((i for i, (a, b) in enumerate(zip(ours_trace, ref_trace)) if a != b), None)
    writes = sum(len(f) for f in ours_trace)
    if mismatch is None:
        print(f"py65 6502 reference: {len(ours_trace)} frames, {writes} APU writes, identical order and values")
    else:
        ok = False
        print(f"py65 6502 reference: first mismatch at frame {mismatch}:\n  ours {ours_trace[mismatch]}\n  py65 {ref_trace[mismatch]}")

    # Thresholds sit below the agreement of the two references with each other (printed with
    # --famistudio): noise is random, and every player filters its output differently.
    ours = read_wav(d / "nes_demo.wav", SECONDS)
    gme = gme_render(d / "nes_demo.nsf", SECONDS)
    r = compare("libgme rendering the same NSF", ours, gme)
    ok &= r["envelope_r"] > 0.85 and r["spectrum_r"] > 0.65
    tonal = read_wav(d / "nes_demo_tonal.wav", SECONDS)
    gme_tonal = gme_render(d / "nes_demo.nsf", SECONDS, mute_mask=0x18)
    r = compare("pulses + triangle only, libgme on the same NSF", tonal, gme_tonal)
    ok &= r["envelope_r"] > 0.9 and r["spectrum_r"] > 0.85 and r["peak_pitch_agree"] > 0.85

    if args.famistudio:
        wav, fs_tonal, nsf = famistudio(args.famistudio, d)
        fs = read_wav(wav, SECONDS)
        r = compare("FamiStudio 4.5.3 wav-export", ours, fs)
        ok &= r["envelope_r"] > 0.85
        fs_t = read_wav(fs_tonal, SECONDS)
        r = compare("pulses + triangle only, FamiStudio 4.5.3 wav-export", tonal, fs_t)
        ok &= r["envelope_r"] > 0.9 and r["spectrum_r"] > 0.85 and r["peak_pitch_agree"] > 0.85
        r = compare("libgme rendering FamiStudio 4.5.3 nsf-export", ours, gme_render(nsf, SECONDS))
        ok &= r["envelope_r"] > 0.85
        compare("baseline, reference vs reference: libgme(our NSF) vs FamiStudio wav-export", gme, fs)
        compare("baseline, reference vs reference, pulses + triangle", gme_tonal, fs_t)
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
