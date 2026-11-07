#!/usr/bin/env python3
"""Prepare the embedded NES player image (FamiStudio sound engine 4.5.3) and the host-test song.

Run once by a developer when the pinned engine or the player configuration changes; the output
headers are committed, so app/Deck/CI builds need no 6502 assembler:

  app/src/main/cpp/generated/nes_player_image.h   player bytes at $8000 + entry/variable addresses
  app/src/test/cpp/nes_demo_song.h                FamiStudio's own export of a demo song, linked
                                                  at NES_SONG_BASE (native host test fixture)

Steps (all checked):
  1. Check the vendored engine files (third_party/famistudio-4.5.3, unmodified copies from
     github.com/BleuBleu/FamiStudio tag 4.5.3) against pinned sha256 values.
  2. Assemble tools/audio/nes/nes_player.s (config + fixed entry table + the engine) with
     ca65 -g and link it alone with ld65 (tools/audio/nes/nes_player.cfg): code at $8000,
     zero page at $00, engine RAM at $0200.
  3. Check the entry table, that the player ends below NES_SONG_BASE and that engine RAM stays
     clear of the stack page.
  4. Link the demo song export (DemoSource/song_shatterhand_ca65.s, "Final Area") into a song
     region at NES_SONG_BASE (tools/audio/nes/nes_song.cfg) and check its FamiStudio header.
  5. Write both headers.

Assembler: cc65's ca65/ld65 on PATH, else run through `distrobox enter ubuntu --` (install with
`distrobox enter ubuntu -- sudo apt-get install -y cc65`). Pinned: Ubuntu 24.04 cc65 2.19-1
("ca65 V2.18 - Ubuntu 2.19-1"); the script refuses other versions so outputs stay reproducible.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENGINE_DIR = ROOT / "third_party" / "famistudio-4.5.3"
NES_DIR = ROOT / "tools" / "audio" / "nes"
OUT_PLAYER = ROOT / "app" / "src" / "main" / "cpp" / "generated" / "nes_player_image.h"
OUT_SONG = ROOT / "app" / "src" / "test" / "cpp" / "nes_demo_song.h"

FAMISTUDIO_TAG = "4.5.3"
FAMISTUDIO_COMMIT = "c09dca64788606c09fcb5e70db28b9dd1f34be78"
PINNED = {
    "famistudio_ca65.s": "e126a8f32b6c879d6ce29b39bcedf2b23c5e03419aec80357f3497739dbd71fe",
    "NoteTables/famistudio_note_table_lsb.bin": "b917b0ebbe96945bbf6237bf2c59c9d1cf35f64e4e8183bece0f45d32486ee41",
    "NoteTables/famistudio_note_table_msb.bin": "b52e4924c607af3369e3de8da5ea9001dc013e67b515d3b3b09dab17601169fb",
    "DemoSource/song_shatterhand_ca65.s": "59c731310450674b71009d31dfa0a37ad606766e144a8b86cb64cbf9c7798c60",
}
CC65_VERSION = "ca65 V2.18 - Ubuntu 2.19-1"

PLAYER_BASE = 0x8000
SONG_BASE = 0xA000
SONG_END = 0xFFFA  # vectors follow
ENTRY = {"nes_player_init": 0x8000, "nes_player_play": 0x8003, "nes_player_idle": 0x8006, "nes_player_trap": 0x8009}
# Engine symbols the native machine and host test use.
SYMBOLS = [
    "famistudio_init", "famistudio_music_play", "famistudio_update", "famistudio_song_speed",
    "famistudio_chn_ptr_lo", "famistudio_chn_ptr_hi", "famistudio_note_table_lsb", "famistudio_note_table_msb",
]

# "Final Area" timing from FamiStudio 4.5.3's famistudio-txt-export of Demo Songs/Shatterhand.fms:
# Song Length="25" LoopPoint="0" PatternLength="16" NoteLength="9" Groove="9" (FamiStudio tempo,
# constant groove: every row lasts 9 frames). Not in the assembly export; the host test checks it
# against the loop period of the played register trace.
DEMO_TIMING = {"frames_per_row": 9, "rows_per_order": 16, "order_count": 25, "loop_order": 0}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def tool_prefix() -> list:
    if shutil.which("ca65") and shutil.which("ld65"):
        return []
    if shutil.which("distrobox"):
        return ["distrobox", "enter", "ubuntu", "--"]
    sys.exit("ca65/ld65 not found (install cc65, or the 'ubuntu' distrobox with cc65)")


def run(prefix: list, args: list, cwd: Path) -> str:
    res = subprocess.run(prefix + args, cwd=cwd, capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit(f"{' '.join(args)} failed:\n{res.stdout}{res.stderr}")
    return res.stdout + res.stderr


def labels(path: Path) -> dict:
    out = {}
    for line in path.read_text().splitlines():
        m = re.match(r"al ([0-9A-F]{6}) \.(\S+)$", line)
        if m:
            out[m.group(2)] = int(m.group(1), 16)
    return out


def segments(map_text: str) -> dict:
    segs = {}
    block = map_text.split("Segment list:", 1)[1].split("Exports list", 1)[0]
    for line in block.splitlines():
        m = re.match(r"(\w+)\s+([0-9A-F]{6})\s+([0-9A-F]{6})\s+([0-9A-F]{6})", line)
        if m:
            segs[m.group(1)] = (int(m.group(2), 16), int(m.group(4), 16))
    return segs


def c_bytes(data: bytes) -> str:
    rows = []
    for i in range(0, len(data), 16):
        rows.append("    " + " ".join(f"0x{b:02X}," for b in data[i:i + 16]))
    return "\n".join(rows)


def main() -> None:
    for rel, want in PINNED.items():
        got = sha256((ENGINE_DIR / rel).read_bytes())
        if got != want:
            sys.exit(f"{rel}: sha256 {got} != pinned {want}")

    prefix = tool_prefix()
    version = run(prefix, ["ca65", "--version"], ROOT).strip()
    if version != CC65_VERSION:
        sys.exit(f"assembler '{version}' != pinned '{CC65_VERSION}'")

    with tempfile.TemporaryDirectory(dir="/tmp") as tmp:
        t = Path(tmp)
        run(prefix, ["ca65", "-g", "-I", str(ENGINE_DIR), "--bin-include-dir", str(ENGINE_DIR),
                     "-D", f"NES_SONG_BASE=${SONG_BASE:04X}", str(NES_DIR / "nes_player.s"), "-o", str(t / "player.o")], t)
        run(prefix, ["ld65", "-C", str(NES_DIR / "nes_player.cfg"), "-o", str(t / "player.bin"),
                     "-Ln", str(t / "player.lbl"), "-m", str(t / "player.map"), str(t / "player.o")], t)
        player = (t / "player.bin").read_bytes()
        syms = labels(t / "player.lbl")
        segs = segments((t / "player.map").read_text())

        (t / "song.s").write_text('.segment "SONG"\n.include "song_shatterhand_ca65.s"\n')
        run(prefix, ["ca65", "-I", str(ENGINE_DIR / "DemoSource"), str(t / "song.s"), "-o", str(t / "song.o")], t)
        run(prefix, ["ld65", "-C", str(NES_DIR / "nes_song.cfg"), "-o", str(t / "song.bin"), str(t / "song.o")], t)
        song = (t / "song.bin").read_bytes()

    for name, addr in ENTRY.items():
        if syms.get(name) != addr:
            sys.exit(f"{name} at {syms.get(name)!r}, expected ${addr:04X}")
    if PLAYER_BASE + len(player) > SONG_BASE:
        sys.exit(f"player ends at ${PLAYER_BASE + len(player):04X}, past NES_SONG_BASE ${SONG_BASE:04X}")
    zp, bss = segs["ZEROPAGE"], segs["BSS"]
    if zp[0] != 0 or zp[1] > 0x100 or bss[0] != 0x200 or bss[0] + bss[1] > 0x800:
        sys.exit(f"unexpected RAM layout {segs}")
    missing = [s for s in SYMBOLS if s not in syms]
    if missing:
        sys.exit(f"missing engine symbols {missing}")
    if len(song) > SONG_END - SONG_BASE:
        sys.exit("demo song does not fit the song region")
    # FamiStudio music data header: song count, instruments, samples-4, then 5 channel pointers
    # and 4 tempo bytes per song (2A03 only, FamiStudio tempo).
    if song[0] != 1:
        sys.exit(f"demo song count {song[0]} != 1")
    for i in range(5):
        ptr = song[5 + 2 * i] | song[6 + 2 * i] << 8
        if not SONG_BASE <= ptr < SONG_BASE + len(song):
            sys.exit(f"demo channel {i} pointer ${ptr:04X} outside the song")

    player_sha = sha256(player)
    player_id = int.from_bytes(bytes.fromhex(player_sha)[:4], "little")
    sym_lines = "\n".join(f"#define NES_{s.upper()} 0x{syms[s]:04X}" for s in SYMBOLS)
    OUT_PLAYER.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
// FamiStudio sound engine {FAMISTUDIO_TAG} (github.com/BleuBleu/FamiStudio commit {FAMISTUDIO_COMMIT}),
// famistudio_ca65.s sha256 {PINNED['famistudio_ca65.s']}, unmodified, configured and wrapped by
// tools/audio/nes/nes_player.s; assembled with {CC65_VERSION}.
// Engine licence: "Copying and distribution of this file, with or without modification, are permitted in any
// medium without royalty provided the copyright notice and this notice are preserved" (c) 2019-2026 Mathieu Gauthier.
// image sha256 {player_sha}
#ifndef NES_PLAYER_IMAGE_H_
#define NES_PLAYER_IMAGE_H_
#include <stdint.h>

#define NES_PLAYER_BASE 0x{PLAYER_BASE:04X}
#define NES_PLAYER_SIZE 0x{len(player):04X}
#define NES_PLAYER_ID 0x{player_id:08X}u /* first 4 bytes of the image sha256, little-endian */
#define NES_PLAYER_INIT 0x{ENTRY['nes_player_init']:04X} /* A = song index */
#define NES_PLAYER_PLAY 0x{ENTRY['nes_player_play']:04X} /* one 60 Hz engine frame */
#define NES_PLAYER_IDLE 0x{ENTRY['nes_player_idle']:04X} /* return address of host-called routines */
#define NES_PLAYER_TRAP 0x{ENTRY['nes_player_trap']:04X} /* BRK/IRQ/NMI vector target: a fault */
#define NES_SONG_BASE 0x{SONG_BASE:04X}
#define NES_SONG_END 0x{SONG_END:04X}
#define NES_ZP_SIZE 0x{zp[1]:04X}
#define NES_RAM_BASE 0x{bss[0]:04X}
#define NES_RAM_SIZE 0x{bss[1]:04X}
{sym_lines}

static const uint8_t nes_player_code[NES_PLAYER_SIZE] = {{
{c_bytes(player)}
}};

#endif /* NES_PLAYER_IMAGE_H_ */
""")
    OUT_SONG.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
// FamiStudio {FAMISTUDIO_TAG}'s own ca65 export of Demo Songs/Shatterhand.fms ("Final Area"), as committed
// in SoundEngine/DemoSource/song_shatterhand_ca65.s (sha256 {PINNED['DemoSource/song_shatterhand_ca65.s']}),
// linked at NES_SONG_BASE 0x{SONG_BASE:04X}. Timing from FamiStudio's text export of the same song.
// song sha256 {sha256(song)}
#ifndef NES_DEMO_SONG_H_
#define NES_DEMO_SONG_H_
#include <stdint.h>

#define NES_DEMO_SONG_BASE 0x{SONG_BASE:04X}
#define NES_DEMO_SONG_SIZE {len(song)}
#define NES_DEMO_FRAMES_PER_ROW {DEMO_TIMING['frames_per_row']}
#define NES_DEMO_ROWS_PER_ORDER {DEMO_TIMING['rows_per_order']}
#define NES_DEMO_ORDER_COUNT {DEMO_TIMING['order_count']}
#define NES_DEMO_LOOP_ORDER {DEMO_TIMING['loop_order']}

static const uint8_t nes_demo_song[NES_DEMO_SONG_SIZE] = {{
{c_bytes(song)}
}};

#endif /* NES_DEMO_SONG_H_ */
""")
    print(f"player: {len(player)} bytes at ${PLAYER_BASE:04X}, sha256 {player_sha}, id 0x{player_id:08X}")
    print(f"engine RAM: zp ${zp[0]:02X}+{zp[1]}, bss ${bss[0]:04X}+{bss[1]}")
    print(f"demo song: {len(song)} bytes at ${SONG_BASE:04X}, sha256 {sha256(song)}")
    print(f"wrote {OUT_PLAYER.relative_to(ROOT)} and {OUT_SONG.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
