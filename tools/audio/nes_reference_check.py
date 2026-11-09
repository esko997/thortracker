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
  5. EPSM (tools/audio/nes/epsm_test.txt; evidence from the host test's nes_epsm_test: nes_epsm.nsf, nes_epsm_trace.txt
     with every $4000-$4017 and $401C-$401F write, nes_epsm.wav, nes_epsm_tonal.wav with the rhythm channels muted):
     both NSFs carry the EPSM expansion bit ($7B bit 7 = $80, FamiStudio's NsfFile); py65 running our NSF must write
     exactly our trace (the EPSM register selects and data included); FamiStudio's own NSF export of epsm_test.txt (its
     own EPSM kernel and its NTSC data) run in py65 must write the same registers in the same order, values differing
     only in pitch registers (2A03 periods, SSG tone periods $00-$05 and the auto-period envelope $0B/$0C, FM block/F-number
     $A0-$A2/$A4-$A6 on both ports) on frames of slides, where FamiStudio's asm and NSF exports compute different slide
     steps (see 3); our SSG + FM + 2A03 audio is compared with FamiStudio's wav-export of channels 0-13. libgme has no
     EPSM, so FamiStudio is the only audio reference. Rhythm audio is compared only when the host test rendered it
     (nes_epsm_rhythm.wav, see below): with FamiStudio's wav-export of the rhythm channels 14-19 alone and of the full mix.

Private rhythm verification (the YM2608 rhythm ROM is never committed, copied into the repository or evidence, or
shipped): the host test reads the user's own ROM from a path given in THOR_RHYTHM_ROM (8192 bytes, SHA-256
53afd0fa9c62eda3e2be939e23f3adf48a2af8ad37bb1640261726c5d5adeba8; the test refuses anything else) and then also writes
nes_epsm_rhythm.wav. Keep the ROM and that evidence outside the repository, e.g.:
  THOR_RHYTHM_ROM=$HOME/private/ym2608_adpcm_rom.bin tools/audio/run_native_host_test.sh /tmp/thor-evidence
  tools/audio/nes_reference_check.py /tmp/thor-evidence
This script never reads the ROM; FamiStudio's wav-export plays rhythm with its own built-in copy (NesSndEmu
fmopn_2608rom.h). Without THOR_RHYTHM_ROM the EPSM rhythm audio is reported as not compared.

Requirements: python3 with numpy and py65 (`python3 -m venv /tmp/nesref && /tmp/nesref/bin/pip install
numpy py65`), libgme.so.0 (Fedora: game-music-emu, Ubuntu: libgme0).
Usage: tools/audio/nes_reference_check.py <evidence-dir> [--famistudio DIR]   (DIR overrides the fetched release)
Parent commands: tools/audio/run_native_host_test.sh /tmp/thor-evidence && /tmp/nesref/bin/python3
tools/audio/nes_reference_check.py /tmp/thor-evidence
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


APU_PORTS = range(0x4000, 0x4018)
EPSM_PORTS = list(range(0x4000, 0x4018)) + list(range(0x401C, 0x4020))  # + EPSM address/data A1 = 0 and A1 = 1


def py65_trace(nsf: bytes, frames: int, ports=APU_PORTS) -> list:
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

    mem.subscribe_to_write(ports, on_write)
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
    """FamiStudio's own wav-export (all channels and the `part_channels` bit mask, bit n = channel n; FamiStudio reads
    the option as hexadecimal) and nsf-export of tools/audio/nes/<name>.txt."""
    song = Path(__file__).resolve().parent / "nes" / f"{name}.txt"
    cmd = ["dotnet", str(app / "FamiStudio.dll")]
    prefix = [] if shutil.which("dotnet") else ["distrobox", "enter", "ubuntu", "--"]
    wav, part, nsf = (out / f"famistudio_{name}.wav", out / f"famistudio_{name}_part.wav", out / f"famistudio_{name}.nsf")
    export = [str(song), "wav-export"]
    opts = ["-wav-export-rate:48000", f"-wav-export-duration:{SECONDS}"]
    subprocess.run(prefix + cmd + export + [str(wav)] + opts, check=True, capture_output=True)
    subprocess.run(prefix + cmd + export + [str(part)] + opts + [f"-wav-export-channels:{part_channels:x}"], check=True,
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


# EPSM pitch registers: (port 0 = A1 0 via $401C/$401D, 1 = A1 1 via $401E/$401F, register) and 2A03 periods.
EPSM_PITCH = ({(0, r) for r in (0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x0B, 0x0C)}
              | {(p, r) for p in (0, 1) for r in (0xA0, 0xA1, 0xA2, 0xA4, 0xA5, 0xA6)})
APU_PITCH = {0x4002, 0x4003, 0x4006, 0x4007, 0x400A, 0x400B}


def resolved(trace: list) -> list:
    """A trace's writes per frame as (target, value): an APU address, ("sel", port) for an EPSM address write, or
    (port, register) for an EPSM data write (the register its port last selected; selects persist across frames)."""
    sel, out = {0: None, 1: None}, []
    for frame in trace:
        writes = []
        for w in frame:
            a, v = (int(x, 16) for x in w.split("="))
            if a in (0x401C, 0x401E):
                sel[(a - 0x401C) // 2] = v
                writes.append((("sel", (a - 0x401C) // 2), v))
            elif a in (0x401D, 0x401F):
                port = (a - 0x401D) // 2
                writes.append(((port, sel[port]), v))
            else:
                writes.append((a, v))
        out.append(writes)
    return out


def famistudio_epsm(app: Path, out: Path) -> dict:
    """FamiStudio's own exports of tools/audio/nes/epsm_test.txt: wav-export of every channel, of channels 0-13 (2A03,
    SSG, FM) and of the rhythm channels 14-19 (channel masks are hexadecimal), and nsf-export."""
    song = Path(__file__).resolve().parent / "nes" / "epsm_test.txt"
    cmd = ([] if shutil.which("dotnet") else ["distrobox", "enter", "ubuntu", "--"]) + ["dotnet", str(app / "FamiStudio.dll")]
    opts = ["-wav-export-rate:48000", f"-wav-export-duration:{SECONDS}"]
    files = {}
    for part, mask in (("all", None), ("tonal", (1 << 14) - 1), ("rhythm", ((1 << 6) - 1) << 14)):
        files[part] = out / f"famistudio_epsm_test_{part}.wav"
        subprocess.run(cmd + [str(song), "wav-export", str(files[part])] + opts
                       + ([f"-wav-export-channels:{mask:x}"] if mask else []), check=True, capture_output=True)
    files["nsf"] = out / "famistudio_epsm_test.nsf"
    subprocess.run(cmd + [str(song), "nsf-export", str(files["nsf"])], check=True, capture_output=True)
    return files


def check_epsm(d: Path, app: Path) -> bool:
    """The EPSM test song: NSF expansion bytes, our trace against py65 on our NSF and on FamiStudio's own NSF (pitch
    registers may differ on slide frames), our audio against FamiStudio's wav-export (rhythm only with the user's ROM)."""
    print("EPSM test song (epsm_test.txt):")
    ok = True
    ours_nsf = (d / "nes_epsm.nsf").read_bytes()
    files = famistudio_epsm(app, d)
    fs_nsf = files["nsf"].read_bytes()
    if ours_nsf[0x7B] != 0x80 or not fs_nsf[0x7B] & 0x80:
        ok = False
        print(f"NSF expansion byte $7B: ours ${ours_nsf[0x7B]:02X} (expected $80), FamiStudio's ${fs_nsf[0x7B]:02X}")
    else:
        print(f"NSF expansion byte $7B: ours $80 (EPSM), FamiStudio's ${fs_nsf[0x7B]:02X}")
    ours_trace = read_trace(d / "nes_epsm_trace.txt")
    ref_trace = py65_trace(ours_nsf, len(ours_trace), EPSM_PORTS)
    mismatch = next((i for i, (a, b) in enumerate(zip(ours_trace, ref_trace)) if a != b), None)
    epsm = sum(1 for f in ours_trace for w in f if w[:4] in ("401C", "401D", "401E", "401F"))
    if mismatch is None:
        print(f"py65 6502 reference: {len(ours_trace)} frames, {sum(len(f) for f in ours_trace)} writes ({epsm} to "
              f"$401C-$401F), identical order and values")
    else:
        ok = False
        print(f"py65 6502 reference: first mismatch at frame {mismatch}:\n  ours {ours_trace[mismatch]}\n  py65 {ref_trace[mismatch]}")
    fs_trace = py65_trace(fs_nsf, len(ours_trace), EPSM_PORTS)
    ours_r, fs_r = resolved(ours_trace), resolved(fs_trace)
    pitch_frames, other = 0, None
    for i, (a, b) in enumerate(zip(ours_r, fs_r)):
        if a == b:
            continue
        same_shape = len(a) == len(b) and all(x[0] == y[0] for x, y in zip(a, b))
        pitch_only = same_shape and all(x[1] == y[1] or x[0] in EPSM_PITCH or x[0] in APU_PITCH for x, y in zip(a, b))
        if pitch_only:
            pitch_frames += 1
        elif other is None:
            other = i
    if other is None:
        print(f"FamiStudio's own NSF (its EPSM kernel + its NTSC data) in py65: {len(ours_trace)} frames, the same writes in "
              f"the same order, values identical except pitch registers in {pitch_frames} frames (slide steps)")
    else:
        ok = False
        print(f"FamiStudio's own NSF in py65: frame {other} differs beyond pitch registers:\n  ours {ours_trace[other]}\n"
              f"  FamiStudio {fs_trace[other]}")
    # Thresholds: our YMF288 (ymfm) against FamiStudio's YM2608 emulation through its own output filters; no second EPSM
    # reference exists to measure their agreement with each other, so these are the parent's first-run bounds.
    r = compare("2A03 + SSG + FM (rhythm muted), FamiStudio 4.5.3 wav-export", read_wav(d / "nes_epsm_tonal.wav", SECONDS),
                read_wav(files["tonal"], SECONDS))
    ok &= r["envelope_r"] > 0.85 and r["spectrum_r"] > 0.8 and r["peak_pitch_agree"] > 0.75
    rhythm = d / "nes_epsm_rhythm.wav"
    if rhythm.exists():
        r = compare("rhythm only (user's ROM), FamiStudio 4.5.3 wav-export", read_wav(rhythm, SECONDS),
                    read_wav(files["rhythm"], SECONDS))
        ok &= r["envelope_r"] > 0.85 and r["spectrum_r"] > 0.7
        r = compare("all channels (user's ROM), FamiStudio 4.5.3 wav-export", read_wav(d / "nes_epsm.wav", SECONDS),
                    read_wav(files["all"], SECONDS))
        ok &= r["envelope_r"] > 0.85 and r["spectrum_r"] > 0.8
    else:
        print("EPSM rhythm audio: not compared (no nes_epsm_rhythm.wav: run the host test with THOR_RHYTHM_ROM)")
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
    ok &= check_epsm(d, args.famistudio)
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
