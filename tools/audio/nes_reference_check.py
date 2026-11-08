#!/usr/bin/env python3
"""Compare the native NES machine's output with independent references (developer evidence, not CI).

Inputs come from the host test (tools/audio/run_native_host_test.sh <dir>): <dir>/nes_ft.nsf (the
player image + FamiStudio's export of tools/audio/nes/ft_test.txt as an NSF), <dir>/nes_trace.txt
(per-frame APU writes; the host test proves the Thor Tracker layout of the song writes the same) and
<dir>/nes_ft.wav / nes_ft_tonal.wav; for the DPCM song tools/audio/nes/dpcm_test.txt the same as
nes_dpcm.nsf (samples at $C000), nes_dpcm_trace.txt, nes_dpcm.wav and nes_dpcm_dmc.wav (the DMC alone).

  1. CPU reference: py65 (an independent 6502 emulator) runs the NSF's init/play routines with the
     same calling convention and logs APU register writes per frame; they must equal nes_trace.txt.
  2. Audio reference: libgme (Game_Music_Emu: its own 6502 + blargg's Nes_Apu) renders the NSF;
     its loudness envelope and spectra are compared with nes_ft.wav.
  3. FamiStudio 4.5.3 (the pinned Linux release, fetched and verified into the cache by
     tools/audio/fetch_famistudio.sh, or the release directory given with --famistudio; needs
     `dotnet`, e.g. in the ubuntu distrobox): FamiStudio's own NSF export of ft_test.txt (its own
     FamiTracker-tempo NSF kernel and its own data) run in py65 must write the same APU registers
     frame by frame; FamiStudio's WAV export and libgme rendering its NSF are compared like 2.
  4. DPCM: 1 and 3 for dpcm_test.txt (FamiStudio's NSF places its samples itself), and the DMC alone
     against FamiStudio's WAV export of the DPCM channel (its NesSndEmu DMC) and libgme's.

Requirements: python3 with numpy and py65 (`python3 -m venv /tmp/nesref && /tmp/nesref/bin/pip install
numpy py65`), libgme.so.0 (Fedora: game-music-emu, Ubuntu: libgme0).
Usage: tools/audio/nes_reference_check.py <evidence-dir> [--famistudio DIR]   (DIR overrides the fetched release)
Exit status 1 if a comparison is below its threshold.
"""
import argparse
import ctypes
import re
import shutil
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

RATE = 48000
SECONDS = 30
FRAME_HZ = 1789773 / 29780.5
FETCH_FAMISTUDIO = Path(__file__).resolve().parent / "fetch_famistudio.sh"


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
    banks = list(nsf[0x70:0x78])
    mem = ObservableMemory()
    data = nsf[128:]
    if any(banks):
        # Bank-switched NSF (FamiStudio's export): 4 KiB banks, the first one padded by load & $FFF.
        data = bytes(load & 0xFFF) + data

        def map_bank(slot, bank):
            chunk = data[bank * 4096:(bank + 1) * 4096]
            for i in range(4096):
                mem[0x8000 + slot * 4096 + i] = chunk[i] if i < len(chunk) else 0

        for slot, bank in enumerate(banks):
            map_bank(slot, bank)
        mem.subscribe_to_write(range(0x5FF8, 0x6000), lambda addr, value: map_bank(addr - 0x5FF8, value))
    else:
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


def famistudio(app: Path, out: Path, name: str = "ft_test", part_channels: int = 7) -> tuple:
    """FamiStudio's own wav-export (all channels and the `part_channels` mask) and nsf-export of tools/audio/nes/<name>.txt."""
    song = Path(__file__).resolve().parent / "nes" / f"{name}.txt"
    cmd = ["dotnet", str(app / "FamiStudio.dll")]
    prefix = [] if shutil.which("dotnet") else ["distrobox", "enter", "ubuntu", "--"]
    wav, part, nsf = (out / f"famistudio_{name}.wav", out / f"famistudio_{name}_part.wav", out / f"famistudio_{name}.nsf")
    export = [str(song), "wav-export"]
    opts = ["-wav-export-rate:48000", f"-wav-export-duration:{SECONDS}"]
    subprocess.run(prefix + cmd + export + [str(wav)] + opts, check=True, capture_output=True)
    subprocess.run(prefix + cmd + export + [str(part)] + opts + [f"-wav-export-channels:{part_channels}"], check=True,
                   capture_output=True)
    subprocess.run(prefix + cmd + [str(song), "nsf-export", str(nsf)], check=True, capture_output=True)
    return wav, part, nsf


def check_dpcm(d: Path, app: Path) -> bool:
    """The DPCM test song: our trace against py65 on our NSF and on FamiStudio's own NSF; our audio (all channels and
    the DMC alone) against FamiStudio's wav-export and libgme."""
    print("DPCM test song (dpcm_test.txt, samples at $C000):")
    ok = True
    ours_trace = read_trace(d / "nes_dpcm_trace.txt")
    ref_trace = py65_trace((d / "nes_dpcm.nsf").read_bytes(), len(ours_trace))
    mismatch = next((i for i, (a, b) in enumerate(zip(ours_trace, ref_trace)) if a != b), None)
    dmc = sum(1 for f in ours_trace for w in f if w.split("=")[0] in ("4010", "4011", "4012", "4013", "4015"))
    if mismatch is None:
        print(f"py65 6502 reference: {len(ours_trace)} frames, {sum(len(f) for f in ours_trace)} APU writes "
              f"({dmc} to $4010-$4013/$4015), identical order and values")
    else:
        ok = False
        print(f"py65 6502 reference: first mismatch at frame {mismatch}:\n  ours {ours_trace[mismatch]}\n  py65 {ref_trace[mismatch]}")
    wav, dmc_wav, nsf = famistudio(app, d, "dpcm_test", 16)
    fs_trace = py65_trace(nsf.read_bytes(), len(ours_trace))
    # FamiStudio's NSF kernel places the samples itself (one constant $4012 offset from ours): compare $4012 relative to it.
    shifts = {(int(b.split("=")[1], 16) - int(a.split("=")[1], 16)) & 0xFF for fa, fb in zip(ours_trace, fs_trace)
              for a, b in zip(fa, fb) if a.startswith("4012=") and b.startswith("4012=")}
    shift = shifts.pop() if len(shifts) == 1 else None
    moved = lambda frame: [f"4012={(int(w[5:], 16) - shift) & 0xFF:02X}" if w.startswith("4012=") else w for w in frame]  # noqa: E731
    differing = [i for i, (a, b) in enumerate(zip(ours_trace, fs_trace)) if shift is None or a != moved(b)]
    if not differing:
        print(f"FamiStudio's own NSF (its kernel and data, samples at ${0xC000 + 64 * shift:04X}) in py65: {len(ours_trace)} "
              f"frames, identical APU writes ($4012 relative to the sample base)")
    else:
        ok = False
        i = differing[0]
        print(f"FamiStudio's own NSF in py65: {len(differing)} frames differ (sample offsets {shifts or shift}), first {i}:\n"
              f"  ours {ours_trace[i]}\n  FamiStudio {fs_trace[i]}")
    # Thresholds: below the agreement of the two references with each other (printed last); libgme's DMC is blargg's
    # Nes_Apu, FamiStudio's WAV export runs its NesSndEmu DMC through its own output filters.
    ours, ours_dmc = read_wav(d / "nes_dpcm.wav", SECONDS), read_wav(d / "nes_dpcm_dmc.wav", SECONDS)
    fs, fs_dmc = read_wav(wav, SECONDS), read_wav(dmc_wav, SECONDS)
    r = compare("FamiStudio 4.5.3 wav-export", ours, fs)
    ok &= r["envelope_r"] > 0.85 and r["spectrum_r"] > 0.85
    r = compare("DMC only, FamiStudio 4.5.3 wav-export", ours_dmc, fs_dmc)
    ok &= r["envelope_r"] > 0.9 and r["spectrum_r"] > 0.75
    gme_dmc = gme_render(d / "nes_dpcm.nsf", SECONDS, mute_mask=0x0F)
    r = compare("DMC only, libgme rendering our NSF", ours_dmc, gme_dmc)
    ok &= r["envelope_r"] > 0.95 and r["spectrum_r"] > 0.9 and r["peak_pitch_agree"] > 0.8
    compare("baseline, reference vs reference: DMC only, libgme(our NSF) vs FamiStudio wav-export", gme_dmc, fs_dmc)
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dir", type=Path)
    ap.add_argument("--famistudio", type=Path)
    args = ap.parse_args()
    if args.famistudio is None:
        # Progress goes to stderr (shown); the last stdout line is the release directory.
        fetched = subprocess.run([str(FETCH_FAMISTUDIO)], stdout=subprocess.PIPE, text=True, check=True).stdout
        args.famistudio = Path(fetched.splitlines()[-1])
    d = args.dir
    ok = True

    ours_trace = read_trace(d / "nes_trace.txt")
    ref_trace = py65_trace((d / "nes_ft.nsf").read_bytes(), len(ours_trace))
    mismatch = next((i for i, (a, b) in enumerate(zip(ours_trace, ref_trace)) if a != b), None)
    writes = sum(len(f) for f in ours_trace)
    if mismatch is None:
        print(f"py65 6502 reference: {len(ours_trace)} frames, {writes} APU writes, identical order and values")
    else:
        ok = False
        print(f"py65 6502 reference: first mismatch at frame {mismatch}:\n  ours {ours_trace[mismatch]}\n  py65 {ref_trace[mismatch]}")

    # Thresholds sit below the agreement of the two references with each other (printed below):
    # noise is random, and every player filters its output differently. On
    # ft_test libgme agrees with FamiStudio's own WAV export no better than with ours (strongest
    # partial in the same pitch class in ~45% of windows for both), so libgme only gets loose bounds;
    # FamiStudio's WAV export is the strict audio reference.
    ours = read_wav(d / "nes_ft.wav", SECONDS)
    gme = gme_render(d / "nes_ft.nsf", SECONDS)
    r = compare("libgme rendering the same NSF", ours, gme)
    ok &= r["envelope_r"] > 0.75 and r["spectrum_r"] > 0.75
    tonal = read_wav(d / "nes_ft_tonal.wav", SECONDS)
    gme_tonal = gme_render(d / "nes_ft.nsf", SECONDS, mute_mask=0x18)
    r = compare("pulses + triangle only, libgme on the same NSF", tonal, gme_tonal)
    ok &= r["envelope_r"] > 0.75 and r["spectrum_r"] > 0.8

    wav, fs_tonal, nsf = famistudio(args.famistudio, d)
    fs_trace = py65_trace(nsf.read_bytes(), len(ours_trace))
    # FamiStudio's asm export computes slide-note steps for NTSC and PAL together (the larger
    # step of the two), its NSF export for NTSC only, so the two data sets differ exactly in
    # those step bytes; frames may then differ only in pulse/triangle period writes.
    steps = lambda data: re.findall(rb"\x50(.)(.)(.)", data)  # noqa: E731
    ours_steps = [(m[0][0], m[1][0], m[2][0]) for m in steps(bytes(int(x, 16) for x in re.findall(
        r"\$([0-9a-f]{2})", (Path(__file__).resolve().parent / "nes" / "ft_test_ca65.s").read_text())))]
    fs_bytes = nsf.read_bytes()
    diff_steps = [(f, t, a, next((m[0][0] for m in steps(fs_bytes) if (m[1][0], m[2][0]) == (f, t)), None))
                  for a, f, t in ours_steps]
    period = {"4002", "4003", "4006", "4007", "400A", "400B"}
    differing, other = 0, None
    for i, (a, b) in enumerate(zip(ours_trace, fs_trace)):
        if a != b:
            differing += 1
            regs = {x.split("=")[0] for x in set(a) ^ set(b)}
            if not regs <= period and other is None:
                other = i
    print("slide steps (from, to, asm export, NSF export): " +
          ", ".join(f"${f:02X}->${t:02X} ${a:02X}/${n:02X}" for f, t, a, n in diff_steps if n is not None))
    if other is None:
        print(f"FamiStudio's own NSF (its kernel + its NTSC data) in py65: {len(ours_trace)} frames, identical APU "
              f"writes except {differing} frames of pulse/triangle period during slides with a different step")
    else:
        ok = False
        print(f"FamiStudio's own NSF in py65: frame {other} differs beyond slide pitch:\n  ours {ours_trace[other]}\n"
              f"  FamiStudio {fs_trace[other]}")
    fs = read_wav(wav, SECONDS)
    r = compare("FamiStudio 4.5.3 wav-export", ours, fs)
    ok &= r["envelope_r"] > 0.85
    fs_t = read_wav(fs_tonal, SECONDS)
    r = compare("pulses + triangle only, FamiStudio 4.5.3 wav-export", tonal, fs_t)
    ok &= r["envelope_r"] > 0.9 and r["spectrum_r"] > 0.85 and r["peak_pitch_agree"] > 0.85
    r = compare("libgme rendering FamiStudio 4.5.3 nsf-export", ours, gme_render(nsf, SECONDS))
    ok &= r["envelope_r"] > 0.75
    compare("baseline, reference vs reference: libgme(our NSF) vs FamiStudio wav-export", gme, fs)
    compare("baseline, reference vs reference, pulses + triangle", gme_tonal, fs_t)
    ok &= check_dpcm(d, args.famistudio)
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
