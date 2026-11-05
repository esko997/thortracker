#!/usr/bin/env python3
"""Prepare the embedded hUGEDriver player image for the native preview engine.

Run once by a developer when the pinned driver changes; the output header is committed and
compiled into libthortracker_audio.so. Nothing here runs at app runtime or per Play.

Steps (all verified, no guessing):
  1. Check the vendored driver: third_party/hUGEDriver-6.1.3/gbdk/hUGEDriver.lib must have
     the pinned sha256 (byte-identical to gbc-puzzler's third_party copy, release v6.1.3).
  2. Extract hUGEDriver.o with GBDK-2020 4.5.0 `sdar` and link it alone with `sdldgb`:
     _CODE at CODE_BASE, _DATA (driver WRAM variables) at DATA_BASE. The driver has no
     external references, so no crt0/runtime is linked; the native machine calls its
     entry points directly.
  3. Parse the Intel HEX and map output; check the object's area sizes and exported symbol
     offsets against the RGBDS source layout the native engine depends on.
  4. Write app/src/main/cpp/generated/huge_player_image.h.

GBDK location: $GBDK_HOME, else ../gbc-puzzler/.tools/gbdk (fetched by gbc-puzzler's
tools/fetch-gbdk; GBDK-2020 4.5.0, sha256 d7857a5f...605c of gbdk-linux64.tar.gz).
"""
import hashlib
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DRIVER_DIR = ROOT / "third_party" / "hUGEDriver-6.1.3"
LIB = DRIVER_DIR / "gbdk" / "hUGEDriver.lib"
LIB_SHA256 = "6c0a2664f7a6277293a9c1690f888dcecb086bdb9b9b4944831285bc5f9cac89"
ASM_SHA256 = "16870fd80f764e9d64aa098d5a7ebdc5d5d48033ca080a121a7d11c3e2e01d56"
OUT = ROOT / "app" / "src" / "main" / "cpp" / "generated" / "huge_player_image.h"
GBDK_VERSION = "4.5.0"

CODE_BASE = 0x0150  # after the cartridge header (0x0100-0x014F) of the preview ROM
DATA_BASE = 0xC000
# Sizes recorded in the object's area headers ("A _CODE size 79B", "A _DATA size 64").
EXPECT_CODE_SIZE = 0x79B
EXPECT_DATA_SIZE = 0x64
# Exported symbol offsets in hUGEDriver.o (sdnm / "S" records).
EXPECT_SYMBOLS = {
    "hUGE_init": ("_CODE", 0x002),
    "hUGE_mute_channel": ("_CODE", 0x05A),
    "hUGE_set_position": ("_CODE", 0x2AA),
    "hUGE_dosound": ("_CODE", 0x46F),
    "hUGE_current_wave": ("_DATA", 0x01C),
    "_hUGE_mute_mask": ("_DATA", 0x01D),
}
# "Playback variables" (WRAM0) offsets in the RGBDS source, non-PREVIEW_MODE build.
WRAM_OFFSETS = [
    ("OrderCnt", 0x00), ("Order1", 0x01), ("DutyInstruments", 0x09), ("WaveInstruments", 0x0B),
    ("NoiseInstruments", 0x0D), ("Routines", 0x0F), ("Waves", 0x11), ("Pattern1", 0x13),
    ("TicksPerRow", 0x1B), ("CurrentWaveVar", 0x1C), ("MuteChannels", 0x1D), ("Counter", 0x1E),
    ("Tick", 0x1F), ("RowBreak", 0x20), ("NextOrder", 0x21), ("Row", 0x22),
    ("CurrentOrder", 0x23), ("Channels", 0x24),
]
# Instruction byte sequences in the linked image that prove those offsets.
WRAM_EVIDENCE = [
    ("hUGE_init: ld a,[hl+]; ld [ticks_per_row],a", bytes([0x2A, 0xEA, 0x1B, 0xC0])),
    ("hUGE_init: ld de,order1 (descriptor copy)", bytes([0x0E, 0x12, 0x11, 0x01, 0xC0])),
    ("hUGE_init: zero start_zero=mute_channels..end_zero", bytes([0x0E, 0x47, 0x21, 0x1D, 0xC0])),
    ("hUGE_init: ld [order_cnt],a", bytes([0x1A, 0xEA, 0x00, 0xC0])),
    ("load_patterns: ld de,pattern1", bytes([0x11, 0x13, 0xC0])),
    ("get_current_row: ld a,[row]", bytes([0xFA, 0x22, 0xC0])),
    ("tick_time: ld hl,counter", bytes([0x21, 0x1E, 0xC0])),
    ("tick_time: ld [current_order],a", bytes([0xEA, 0x23, 0xC0])),
    ("hUGE_dosound: ld a,[tick]", bytes([0xFA, 0x1F, 0xC0])),
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def find_gbdk() -> Path:
    env = os.environ.get("GBDK_HOME")
    cand = Path(env) if env else ROOT.parent / "gbc-puzzler" / ".tools" / "gbdk"
    stamp = cand / ".version"
    if not (cand / "bin" / "sdldgb").exists():
        sys.exit(f"prepare_huge_player: GBDK-2020 {GBDK_VERSION} not found at {cand}; set GBDK_HOME")
    if stamp.exists() and stamp.read_text().strip() != GBDK_VERSION:
        sys.exit(f"prepare_huge_player: {cand} is GBDK {stamp.read_text().strip()}, need {GBDK_VERSION}")
    return cand


def parse_object_areas(obj: bytes):
    areas, symbols, current = {}, {}, None
    for line in obj.decode("latin-1").splitlines():
        m = re.match(r"A (\S+) size ([0-9A-Fa-f]+) ", line)
        if m:
            current = m.group(1)
            areas[current] = int(m.group(2), 16)
            continue
        m = re.match(r"S (\S+) Def([0-9A-Fa-f]+)", line)
        if m and current:
            symbols[m.group(1)] = (current, int(m.group(2), 16))
    return areas, symbols


def parse_ihx(text: str) -> dict:
    mem = {}
    upper = 0
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith(":"):
            continue
        raw = bytes.fromhex(line[1:])
        if sum(raw) & 0xFF:
            sys.exit("prepare_huge_player: bad ihx checksum")
        count, addr, rtype = raw[0], (raw[1] << 8) | raw[2], raw[3]
        data = raw[4:4 + count]
        if rtype == 0:
            for i, b in enumerate(data):
                mem[upper + addr + i] = b
        elif rtype == 1:
            break
        elif rtype == 4:
            upper = ((data[0] << 8) | data[1]) << 16
    return mem


def main() -> None:
    if sha256(LIB) != LIB_SHA256:
        sys.exit(f"prepare_huge_player: {LIB} sha256 mismatch (pinned {LIB_SHA256})")
    asm = DRIVER_DIR / "rgbds" / "hUGEDriver.asm"
    if sha256(asm) != ASM_SHA256:
        sys.exit(f"prepare_huge_player: {asm} sha256 mismatch (pinned {ASM_SHA256})")
    gbdk = find_gbdk()
    with tempfile.TemporaryDirectory() as tmp:
        tmpd = Path(tmp)
        subprocess.run([str(gbdk / "bin" / "sdar"), "x", str(LIB)], cwd=tmpd, check=True)
        obj = tmpd / "hUGEDriver.o"
        areas, symbols = parse_object_areas(obj.read_bytes())
        if areas.get("_CODE") != EXPECT_CODE_SIZE or areas.get("_DATA") != EXPECT_DATA_SIZE:
            sys.exit(f"prepare_huge_player: unexpected areas {areas}")
        for name, want in EXPECT_SYMBOLS.items():
            if symbols.get(name) != want:
                sys.exit(f"prepare_huge_player: symbol {name} is {symbols.get(name)}, expected {want}")
        subprocess.run(
            [str(gbdk / "bin" / "sdldgb"), "-n", "-m", "-w", "-i",
             "-b", f"_CODE=0x{CODE_BASE:04X}", "-b", f"_DATA=0x{DATA_BASE:04X}",
             "player.ihx", "hUGEDriver.o"],
            cwd=tmpd, check=True)
        mem = parse_ihx((tmpd / "player.ihx").read_text())
        map_text = (tmpd / "player.map").read_text(errors="replace")

    lo, hi = min(mem), max(mem)
    if lo != CODE_BASE or hi != CODE_BASE + EXPECT_CODE_SIZE - 1 or len(mem) != EXPECT_CODE_SIZE:
        sys.exit(f"prepare_huge_player: unexpected image span {lo:#x}..{hi:#x} ({len(mem)} bytes)")
    code = bytes(mem[a] for a in range(lo, hi + 1))
    # The native engine reads/patches driver WRAM variables that the library does not export
    # (row, current_order, ...). Their offsets come from the RGBDS source's "Playback
    # variables" section; prove the linked code uses exactly those addresses.
    for desc, needle in WRAM_EVIDENCE:
        if needle not in code:
            sys.exit(f"prepare_huge_player: WRAM layout check failed: {desc} ({needle.hex()})")
    # Cross-check linked addresses in the map file.
    for name, (area, off) in EXPECT_SYMBOLS.items():
        base = CODE_BASE if area == "_CODE" else DATA_BASE
        m = re.search(r"([0-9A-Fa-f]{8})\s+" + re.escape(name) + r"\b", map_text)
        if not m or int(m.group(1), 16) != base + off:
            sys.exit(f"prepare_huge_player: map address for {name} not {base + off:#06x}")

    def sym(name):
        area, off = EXPECT_SYMBOLS[name]
        return (CODE_BASE if area == "_CODE" else DATA_BASE) + off

    lines = [
        "// GENERATED by tools/audio/prepare_huge_player.py -- do not edit.",
        "// hUGEDriver 6.1.3 (public domain), prebuilt GBDK library from the v6.1.3 release",
        f"// hUGEDriver.lib sha256 {LIB_SHA256}",
        f"// linked alone with GBDK-2020 {GBDK_VERSION} sdldgb: _CODE=0x{CODE_BASE:04X} _DATA=0x{DATA_BASE:04X}",
        f"// image sha256 {hashlib.sha256(code).hexdigest()}",
        "#ifndef HUGE_PLAYER_IMAGE_H_",
        "#define HUGE_PLAYER_IMAGE_H_",
        "#include <stdint.h>",
        "",
        f"#define HUGE_CODE_BASE 0x{CODE_BASE:04X}",
        f"#define HUGE_CODE_SIZE 0x{len(code):04X}",
        f"#define HUGE_DATA_BASE 0x{DATA_BASE:04X}",
        f"#define HUGE_DATA_SIZE 0x{EXPECT_DATA_SIZE:04X}",
        f"#define HUGE_INIT 0x{sym('hUGE_init'):04X}           /* HL = song descriptor */",
        f"#define HUGE_DOSOUND 0x{sym('hUGE_dosound'):04X}",
        f"#define HUGE_MUTE_CHANNEL 0x{sym('hUGE_mute_channel'):04X}   /* B = channel, C = 1 mute / 0 unmute */",
        f"#define HUGE_CURRENT_WAVE 0x{sym('hUGE_current_wave'):04X}",
        f"#define HUGE_MUTE_MASK 0x{sym('_hUGE_mute_mask'):04X}",
        "/* Unexported WRAM variables (RGBDS source layout, verified in the linked image). */",
    ]
    lines += [f"#define HUGE_VAR_{n.upper()} (HUGE_DATA_BASE + 0x{off:02X})" for n, off in WRAM_OFFSETS]
    lines += ["", "static const uint8_t huge_player_code[HUGE_CODE_SIZE] = {"]
    for i in range(0, len(code), 16):
        lines.append("    " + ", ".join(f"0x{b:02X}" for b in code[i:i + 16]) + ",")
    lines += ["};", "", "#endif  /* HUGE_PLAYER_IMAGE_H_ */", ""]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines))
    print(f"prepare_huge_player: wrote {OUT.relative_to(ROOT)} ({len(code)} bytes)")


if __name__ == "__main__":
    main()
