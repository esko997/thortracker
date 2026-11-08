#!/usr/bin/env python3
"""Make the WAV → DPCM reference fixtures: FamiStudio 4.5.3's own conversion of generated .wav files.

1. Writes deterministic test .wav files (16-bit mono, 8-bit stereo, 24-bit mono longer than FamiStudio's two-second
   import limit, 32-bit float stereo in a WAVE_FORMAT_EXTENSIBLE header) into
   core/src/jvmTest/resources/dpcm-reference/.
2. Converts each at one or two DMC rates with FamiStudio's code (tools/audio/dpcm_reference/Program.cs, linked against
   the pinned FamiStudio.dll) into <stem>_r<rate>[_i<initial>].dmc beside them.

DpcmReferenceTest then requires Dpcm.fromWav to give exactly those bytes.

Usage: tools/audio/dpcm_reference.py [--famistudio DIR] [--dotnet PATH]
The harness needs a .NET 8 SDK (`dotnet build`); FamiStudio's release only needs the runtime. Default: the SDK in
${XDG_CACHE_HOME:-~/.cache}/thor-tracker/dotnet-sdk (install it with Microsoft's dotnet-install.sh --channel 8.0
--install-dir there), run through `distrobox enter ubuntu --` when no dotnet is on PATH.
"""
import argparse
import math
import os
import random
import shutil
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "core" / "src" / "jvmTest" / "resources" / "dpcm-reference"
HARNESS = ROOT / "tools" / "audio" / "dpcm_reference"
FETCH_FAMISTUDIO = ROOT / "tools" / "audio" / "fetch_famistudio.sh"
CACHE = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "thor-tracker"

# (file, [(rate, dmc initial ÷ 2)])
CONVERSIONS = {
    "tone_16m44k.wav": [(15, 32), (10, 32)],
    "drum_8s22k.wav": [(15, 20)],
    "long_24m11k.wav": [(6, 32)],
    "float_32s16k.wav": [(12, 32)],
}


def riff(fmt: bytes, data: bytes) -> bytes:
    body = b"WAVE" + b"fmt " + struct.pack("<I", len(fmt)) + fmt + b"data" + struct.pack("<I", len(data)) + data
    return b"RIFF" + struct.pack("<I", len(body)) + body


def pcm_fmt(channels: int, rate: int, bits: int, tag: int = 1) -> bytes:
    align = channels * bits // 8
    return struct.pack("<HHIIHH", tag, channels, rate, rate * align, align, bits)


def extensible_fmt(channels: int, rate: int, bits: int, subformat: int) -> bytes:
    guid_rest = bytes([0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71])
    return pcm_fmt(channels, rate, bits, 0xFFFE) + struct.pack("<HHIH", 22, bits, 0x3, subformat) + guid_rest


def make_wavs():
    rnd = random.Random(2003)
    # 16-bit mono 44.1 kHz, 0.6 s: a plucked 110 Hz + 440 Hz tone with a little noise.
    rate, n = 44100, int(44100 * 0.6)
    s = []
    for i in range(n):
        t = i / rate
        v = math.exp(-t * 5) * (0.6 * math.sin(2 * math.pi * 110 * t) + 0.3 * math.sin(2 * math.pi * 440 * t)) + rnd.uniform(-0.02, 0.02)
        s.append(max(-32768, min(32767, int(v * 32767))))
    yield "tone_16m44k.wav", riff(pcm_fmt(1, rate, 16), struct.pack(f"<{n}h", *s))
    # 8-bit unsigned stereo 22.05 kHz, 0.4 s: a snare-like burst, channels differ.
    rate, n = 22050, int(22050 * 0.4)
    data = bytearray()
    for i in range(n):
        t = i / rate
        env = math.exp(-t * 14)
        body = math.sin(2 * math.pi * 185 * t) * math.exp(-t * 30)
        left = 0.5 * body + 0.45 * env * rnd.uniform(-1, 1)
        right = 0.5 * body + 0.25 * env * rnd.uniform(-1, 1)
        data += bytes([max(0, min(255, int(128 + left * 127))), max(0, min(255, int(128 + right * 127)))])
    yield "drum_8s22k.wav", riff(pcm_fmt(2, rate, 8), bytes(data))
    # 24-bit mono 11.025 kHz, 2.5 s (FamiStudio keeps the first 2 s): a slow 60 -> 240 Hz sweep.
    rate, n = 11025, int(11025 * 2.5)
    data = bytearray()
    phase = 0.0
    for i in range(n):
        f = 60 * (4 ** (i / n))
        phase += 2 * math.pi * f / rate
        v = int(0.7 * math.sin(phase) * 8388607)
        data += struct.pack("<i", v)[:3]
    yield "long_24m11k.wav", riff(pcm_fmt(1, rate, 24), bytes(data))
    # 32-bit float stereo 16 kHz, 0.3 s, WAVE_FORMAT_EXTENSIBLE: a falling kick, slightly out of range at the start.
    rate, n = 16000, int(16000 * 0.3)
    vals = []
    phase = 0.0
    for i in range(n):
        t = i / rate
        f = 50 + 120 * math.exp(-t * 30)
        phase += 2 * math.pi * f / rate
        v = 1.1 * math.exp(-t * 9) * math.sin(phase)
        vals += [v, v * 0.8]
    yield "float_32s16k.wav", riff(extensible_fmt(2, rate, 32, 3), struct.pack(f"<{len(vals)}f", *vals))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--famistudio", type=Path)
    ap.add_argument("--dotnet", default=None)
    args = ap.parse_args()
    if args.famistudio is None:
        fetched = subprocess.run([str(FETCH_FAMISTUDIO)], stdout=subprocess.PIPE, text=True, check=True).stdout
        args.famistudio = Path(fetched.splitlines()[-1])
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*"):
        old.unlink()
    for name, data in make_wavs():
        (OUT / name).write_bytes(data)
    dotnet = [args.dotnet] if args.dotnet else (["dotnet"] if shutil.which("dotnet") and b"8." in subprocess.run(
        ["dotnet", "--list-sdks"], capture_output=True).stdout else ["distrobox", "enter", "ubuntu", "--", str(CACHE / "dotnet-sdk" / "dotnet")])
    jobs = [f"{OUT / f}:{r}:{i}" for f, conv in CONVERSIONS.items() for r, i in conv]
    cmd = dotnet + ["run", "--project", str(HARNESS), f"-p:FamiStudioDir={args.famistudio}", "--", str(args.famistudio), str(OUT)] + jobs
    r = subprocess.run(cmd, capture_output=True, text=True)
    print(r.stdout, end="")
    if r.returncode != 0:
        sys.exit(f"harness failed ({r.returncode}):\n{r.stderr}")
    for d in (HARNESS / "bin", HARNESS / "obj"):
        shutil.rmtree(d, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
