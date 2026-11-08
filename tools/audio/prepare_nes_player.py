#!/usr/bin/env python3
"""Prepare the embedded NES player images (FamiStudio sound engine 4.5.3) and the host-test songs.

Run once by a developer when the pinned engine, the player configuration or the image layout
changes; the outputs are committed, so app/Deck/CI builds need no 6502 assembler:

  app/src/main/cpp/generated/nes_player_image.h   player bytes at $8000, entry/engine addresses, image layout
  core/src/commonMain/kotlin/org/thortracker/backend/NesPlayerImage.kt
                                                  the same player bytes and layout numbers for Kotlin
                                                  (lowering, .nsf export)
  app/src/test/cpp/nes_ft_song.h                  host-test fixtures (below)
  app/src/test/cpp/nes_dpcm_song.h                DPCM host-test fixtures (below)
  app/src/main/cpp/generated/nes_vrc6_player_image.h
  core/src/commonMain/kotlin/org/thortracker/backend/NesVrc6PlayerImage.kt
                                                  the Konami VRC6 build of the same player (FAMISTUDIO_EXP_VRC6 = 1,
                                                  otherwise identical), its song base and engine addresses
  app/src/test/cpp/nes_vrc6_song.h                VRC6 host-test fixtures (below)
  app/src/main/cpp/generated/nes_mmc5_player_image.h
  core/src/commonMain/kotlin/org/thortracker/backend/NesMmc5PlayerImage.kt
                                                  the Nintendo MMC5 build (FAMISTUDIO_EXP_MMC5 = 1, otherwise
                                                  identical), its song base and engine addresses
  app/src/test/cpp/nes_mmc5_song.h                MMC5 host-test fixtures (below)

Steps (all checked):
  1. Check the vendored engine files (third_party/famistudio-4.5.3, unmodified copies from
     github.com/BleuBleu/FamiStudio tag 4.5.3) and the test song files against pinned sha256 values.
  2. Assemble tools/audio/nes/nes_player.s (FamiTracker-tempo config + fixed entry table + the
     engine) with ca65 -g and link it alone with ld65 (tools/audio/nes/nes_player.cfg): code at
     $8000, zero page at $00, engine RAM at $0200.
  3. Check the entry table, that the player ends below NES_SONG_BASE, that engine RAM ends below the
     audition scratch area, and read the engine's channel/envelope tables out of the image.
  4. Test song: tools/audio/nes/ft_test.txt (FamiStudio text, FamiTracker tempo, written for these
     tests) exported by FamiStudio 4.5.3 itself to tools/audio/nes/ft_test_ca65.s
     (`--famistudio DIR` re-runs that export and checks it is unchanged). The export is linked with
     ld65 at NES_SONG_BASE (the raw fixture) and also read by a small parser here, which must produce
     the same bytes; the parsed streams are then rearranged into the Thor Tracker image layout
     (docs/AUDIO_ENGINE.md, "Image layout") without changing a single row's bytes: the layout fixture
     plays FamiStudio's own data. Variants for the live-edit tests: the same song with every block
     moved, with one note and one envelope edited, and cut to two orders.
  5. DPCM test song: tools/audio/nes/dpcm_test.txt (samples, mappings with pitch/loop/initial levels, a
     DPCM channel with stop, delta counter, delay, cut and no-attack notes) exported the same way to
     dpcm_test_ca65.s + dpcm_test_ca65.dmc (its samples, for SAMPLE_BASE; the list bytes use
     FAMISTUDIO_DPCM_PTR = 0). Linked, parsed and rearranged likewise (plain: the .dmc where it is;
     moved: every block and sample elsewhere; edited: one Kick byte inverted), plus the DMC writes the text
     asks for, derived from the text alone.
  6. VRC6: the player assembled again with -D FAMISTUDIO_EXP_VRC6=1 (8 channels: the 2A03's five, then VRC6
     pulse 1, pulse 2, saw), its song base = the first page after it (checked), and the VRC6 test song
     tools/audio/nes/vrc6_test.txt exported by FamiStudio 4.5.3 to vrc6_test_ca65.s, linked, parsed and
     rearranged as in 4 (8 channel streams). FamiStudio 4.5.3's ca65 export prints the saw master volume
     operand of a Quarter instrument (`1 - Vrc6SawMasterVolume` = -1) as `$ffffffff`, which ca65 rejects;
     the committed export keeps FamiStudio's text and the link and the parser read that one token as $ff,
     as FamiStudio's own binary (NSF/ROM) export does (`valNum & 0xff`).
  7. MMC5: the player assembled again with -D FAMISTUDIO_EXP_MMC5=1 (7 channels: the 2A03's five, then MMC5 pulse 1
     and pulse 2, which play the 2A03 note table and plain 2A03 instruments; no MMC5 PCM: "extra DPCM not supported",
     famistudio_ca65.s line 107), its song base = the first page after it (the player is assembled once to measure
     it, then again at that base: the code does not depend on it), and the MMC5 test song
     tools/audio/nes/mmc5_test.txt exported by FamiStudio 4.5.3 to mmc5_test_ca65.s (`--famistudio DIR` writes it
     when it is missing, else checks it is unchanged), linked, parsed and rearranged as in 4 (7 channel streams). The
     MMC5 streams are checked to use only the 2A03 build's opcodes (the engine has no MMC5 opcode).
  8. Write the ten outputs. Two runs produce identical files.

Assembler: cc65's ca65/ld65 on PATH, else run through `distrobox enter ubuntu --` (install with
`distrobox enter ubuntu -- sudo apt-get install -y cc65`). Pinned: Ubuntu 24.04 cc65 2.19-1
("ca65 V2.18 - Ubuntu 2.19-1"); the script refuses other versions so outputs stay reproducible.
FamiStudio (only for --famistudio): the 4.5.3 Linux release, run with `dotnet` (also tried in the
ubuntu distrobox).
"""
import argparse
import hashlib
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
OUT_KOTLIN = ROOT / "core" / "src" / "commonMain" / "kotlin" / "org" / "thortracker" / "backend" / "NesPlayerImage.kt"
OUT_SONG = ROOT / "app" / "src" / "test" / "cpp" / "nes_ft_song.h"
OUT_DPCM = ROOT / "app" / "src" / "test" / "cpp" / "nes_dpcm_song.h"
OUT_VRC6_PLAYER = ROOT / "app" / "src" / "main" / "cpp" / "generated" / "nes_vrc6_player_image.h"
OUT_VRC6_KOTLIN = ROOT / "core" / "src" / "commonMain" / "kotlin" / "org" / "thortracker" / "backend" / "NesVrc6PlayerImage.kt"
OUT_VRC6_SONG = ROOT / "app" / "src" / "test" / "cpp" / "nes_vrc6_song.h"
OUT_MMC5_PLAYER = ROOT / "app" / "src" / "main" / "cpp" / "generated" / "nes_mmc5_player_image.h"
OUT_MMC5_KOTLIN = ROOT / "core" / "src" / "commonMain" / "kotlin" / "org" / "thortracker" / "backend" / "NesMmc5PlayerImage.kt"
OUT_MMC5_SONG = ROOT / "app" / "src" / "test" / "cpp" / "nes_mmc5_song.h"

FAMISTUDIO_TAG = "4.5.3"
FAMISTUDIO_COMMIT = "c09dca64788606c09fcb5e70db28b9dd1f34be78"
PINNED = {
    ENGINE_DIR / "famistudio_ca65.s": "e126a8f32b6c879d6ce29b39bcedf2b23c5e03419aec80357f3497739dbd71fe",
    ENGINE_DIR / "NoteTables/famistudio_note_table_lsb.bin": "b917b0ebbe96945bbf6237bf2c59c9d1cf35f64e4e8183bece0f45d32486ee41",
    ENGINE_DIR / "NoteTables/famistudio_note_table_msb.bin": "b52e4924c607af3369e3de8da5ea9001dc013e67b515d3b3b09dab17601169fb",
    ENGINE_DIR / "NoteTables/famistudio_saw_note_table_lsb.bin": "66d863068de002442655fc2d1e8b1d4e9f4e8d42789b327e5a7d9fb078b788aa",
    ENGINE_DIR / "NoteTables/famistudio_saw_note_table_msb.bin": "ae1463a4ff123b22073620e0f3df4336fedc4034b00f8872d9730f425d2e4583",
}
TEST_SONG_TXT = NES_DIR / "ft_test.txt"
TEST_SONG_CA65 = NES_DIR / "ft_test_ca65.s"
DPCM_SONG_TXT = NES_DIR / "dpcm_test.txt"
DPCM_SONG_CA65 = NES_DIR / "dpcm_test_ca65.s"
DPCM_SONG_DMC = NES_DIR / "dpcm_test_ca65.dmc"
VRC6_SONG_TXT = NES_DIR / "vrc6_test.txt"
VRC6_SONG_CA65 = NES_DIR / "vrc6_test_ca65.s"
MMC5_SONG_TXT = NES_DIR / "mmc5_test.txt"
MMC5_SONG_CA65 = NES_DIR / "mmc5_test_ca65.s"
CC65_VERSION = "ca65 V2.18 - Ubuntu 2.19-1"

PLAYER_BASE = 0x8000
SONG_BASE = 0x8C00  # the 2A03 player's song base (checked: past the player): song data grows upward from here
VRC6_SONG_BASE = 0x8E00  # the VRC6 player's: the first page after it (checked)
REGION_END = 0xFFFA  # song data and samples share SONG_BASE..REGION_END; the vectors follow
SAMPLE_BASE = 0xC000  # lowest sample address ($4012 addresses $C000 + 64 n); samples sit at the top of the region
SAMPLE_ALIGN = 64
MAX_SAMPLE_BYTES = 16 * 255 + 1
MAX_DPCM_MAPPINGS = 63  # FamiStudio without FAMISTUDIO_USE_DPCM_EXTENDED_RANGE
DPCM_PTR = (SAMPLE_BASE & 0x3FFF) >> 6  # FamiStudio's FAMISTUDIO_DPCM_PTR for samples at $C000
AUDITION_RAM = 0x07C0  # one-note audition streams written by the native machine; engine RAM must end below
ENTRY = {"nes_player_init": 0x8000, "nes_player_play": 0x8003, "nes_player_idle": 0x8006, "nes_player_trap": 0x8009}

# Image layout (docs/AUDIO_ENGINE.md): header, envelope directory, region = CPU memory SONG_BASE..REGION_END
# (song data from SONG_BASE up; samples in one block from a 64-aligned start >= SAMPLE_BASE; song end <= start).
HEADER_SIZE = 32
HEADER_DPCM_COUNT = 15  # u8: entries in the FamiStudio DPCM list (Thor layout)
HEADER_SAMPLE_SIZE = 22  # u16: bytes of the sample block (0: none)
HEADER_SAMPLE_START = 24  # u16: address of the sample block (0: none)
MAX_ENVELOPES = 512
DIRECTORY_SIZE = 4 * MAX_ENVELOPES
REGION_SIZE = REGION_END - SONG_BASE
IMAGE_SIZE = HEADER_SIZE + DIRECTORY_SIZE + REGION_SIZE
MAX_INSTRUMENTS = 64
MAX_ORDERS = 255
MAX_ROWS = 255
LAYOUT_RAW, LAYOUT_THOR = 0, 1
KIND_VOLUME, KIND_ARPEGGIO, KIND_DUTY, KIND_PITCH = 0, 1, 2, 3

# Engine symbols the native machine and host test use (RAM variables, routines and tables).
SYMBOLS = [
    "famistudio_init", "famistudio_music_play", "famistudio_update",
    "famistudio_song_speed", "famistudio_tempo_step_lo", "famistudio_tempo_step_hi",
    "famistudio_tempo_acc_lo", "famistudio_tempo_acc_hi", "famistudio_song_list_lo", "famistudio_song_list_hi",
    "famistudio_instrument_lo", "famistudio_instrument_hi", "famistudio_dpcm_list_lo", "famistudio_dpcm_list_hi",
    "famistudio_chn_ptr_lo", "famistudio_chn_ptr_hi", "famistudio_chn_note", "famistudio_chn_repeat",
    "famistudio_chn_return_lo", "famistudio_chn_return_hi", "famistudio_chn_ref_len",
    "famistudio_chn_note_delay", "famistudio_chn_cut_delay",
    "famistudio_env_value", "famistudio_env_repeat", "famistudio_env_addr_lo", "famistudio_env_addr_hi",
    "famistudio_env_ptr",
    "famistudio_pitch_env_value_lo", "famistudio_pitch_env_value_hi", "famistudio_pitch_env_repeat",
    "famistudio_pitch_env_addr_lo", "famistudio_pitch_env_addr_hi", "famistudio_pitch_env_ptr",
    "famistudio_pitch_env_fine_value",
    "famistudio_dummy_envelope", "famistudio_dummy_pitch_envelope",
    "famistudio_channel_env", "famistudio_channel_to_pitch_env",
    "famistudio_note_table_lsb", "famistudio_note_table_msb",
]
# Of the VRC6 build only (famistudio_vrc6_saw_volume: the $55 opcode's operand; -1 Quarter, 0 Half, 1 Full).
VRC6_SYMBOLS = ["famistudio_vrc6_saw_volume", "famistudio_saw_note_table_lsb", "famistudio_saw_note_table_msb"]
# Of the MMC5 build only: the pulses' last period high byte written to $5003/$5007 (init $80; the engine rewrites the
# high byte, restarting the pulse's sequencer, only when it changes). Its note tables are the 2A03's.
MMC5_SYMBOLS = ["famistudio_mmc5_pulse1_prev", "famistudio_mmc5_pulse2_prev"]

# Stream opcodes ($40-$6F) of this build: operand bytes, and whether the opcode ends a row entry.
# Mirrors famistudio_advance_channel and the jump table for the configuration in nes_player.s.
OPCODES = {
    0x40: (1, True),   # extended note
    0x41: (3, False),  # reference (call): rows, address
    0x42: (2, False),  # loop: address
    0x43: (0, False),  # disable attack
    0x44: (0, False),  # end song
    0x45: (0, True),   # release
    0x46: (1, False),  # FamiTracker speed
    0x47: (1, False),  # note delay
    0x48: (1, False),  # cut delay
    0x49: (2, False),  # override pitch envelope (vibrato)
    0x4A: (0, False),  # clear pitch override
    0x4B: (2, False),  # override arpeggio envelope
    0x4C: (0, False),  # clear arpeggio override
    0x4D: (0, False),  # reset arpeggio
    0x4E: (1, False),  # fine pitch
    0x4F: (1, False),  # duty cycle
    0x50: (3, True),   # slide: step, from, to
    0x51: (2, False),  # volume slide: step, target
    0x52: (1, False),  # DMC counter
    0x53: (0, False),  # phase reset
    0x54: (1, False),  # extended instrument
}
VRC6_OPCODES = {**OPCODES, 0x55: (1, False)}  # + VRC6 saw master volume

# The builds: assembler defines, song base (MMC5: measured, see assemble_measured), engine channel count and the engine
# tables the native machine reads (first envelope slot per channel, pitch envelope slot per channel; $FF = none).
PLAYERS = {
    "2a03": {"defines": [], "song_base": SONG_BASE, "channels": 5, "opcodes": OPCODES,
             "envelopes": 11, "pitch_envelopes": 3, "channel_env": [0, 3, 6, 8, 0xFF], "channel_pitch": [0, 1, 2, 0xFF, 0xFF]},
    "vrc6": {"defines": ["FAMISTUDIO_EXP_VRC6=1"], "song_base": VRC6_SONG_BASE, "channels": 8, "opcodes": VRC6_OPCODES,
             "envelopes": 20, "pitch_envelopes": 6, "channel_env": [0, 3, 6, 8, 0xFF, 11, 14, 17],
             "channel_pitch": [0, 1, 2, 0xFF, 0xFF, 3, 4, 5]},
    "mmc5": {"defines": ["FAMISTUDIO_EXP_MMC5=1"], "song_base": None, "channels": 7, "opcodes": OPCODES,
             "envelopes": 17, "pitch_envelopes": 5, "channel_env": [0, 3, 6, 8, 0xFF, 11, 14],
             "channel_pitch": [0, 1, 2, 0xFF, 0xFF, 3, 4]},
}


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


def u16(data, at: int) -> int:
    return data[at] | data[at + 1] << 8


# ---- FamiStudio ca65 export parser ---------------------------------------------------------------

def ca65_fixed(text: str) -> str:
    """FamiStudio's ca65 export as ca65 accepts it: FamiStudio 4.5.3 prints the VRC6 saw master volume of a
    Quarter instrument (`$55` operand 1 - 2 = -1) as `$ffffffff`; its binary export writes the low byte."""
    return re.sub(r"(\$55,\s*)\$ffffffff\b", r"\1$ff", text)


def parse_famistudio_ca65(text: str, base: int, name: str = "ft_test_ca65.s"):
    """Assembles FamiStudio's ca65 music export (labels, .byte, .word, .lobyte/.hibyte, DPCM list entries
    `$nn+.lobyte(FAMISTUDIO_DPCM_PTR)` for samples at SAMPLE_BASE) at `base`.
    Returns (bytes, {label: address}). Only the constructs FamiStudio emits for 2A03 and VRC6 songs."""
    text = ca65_fixed(text)
    items = []  # (label or None, directive, [operands])
    for raw in text.splitlines():
        line = raw.split(";", 1)[0].strip()
        if not line or line.startswith((".export", ".global")):
            continue
        m = re.match(r"^([@\w]+):\s*(.*)$", line)
        if m:
            items.append((m.group(1), None, []))
            line = m.group(2).strip()
            if not line:
                continue
        m = re.match(r"^\.(byte|word)\s+(.*)$", line)
        if not m:
            sys.exit(f"{name}: unsupported line {raw!r}")
        items.append((None, m.group(1), [o.strip() for o in m.group(2).split(",")]))
    addrs, pc = {}, base
    for label, directive, ops in items:
        if label:
            addrs[label] = pc
        else:
            pc += len(ops) * (1 if directive == "byte" else 2)

    def value(op: str) -> int:
        m = re.match(r"^\$([0-9a-fA-F]{2})\+\.lobyte\(FAMISTUDIO_DPCM_PTR\)$", op)
        if m:
            return int(m.group(1), 16) + (DPCM_PTR & 0xFF)
        m = re.match(r"^\.(lo|hi)byte\((@?\w+)\)$", op)
        if m:
            v = addrs[m.group(2)]
            return v & 0xFF if m.group(1) == "lo" else v >> 8
        m = re.match(r"^(@?\w+)\s*([-+])\s*(\d+)$", op)
        if m and not m.group(1).isdigit():
            return addrs[m.group(1)] + (int(m.group(3)) if m.group(2) == "+" else -int(m.group(3)))
        if op.startswith("$"):
            return int(op[1:], 16)
        if op.isdigit():
            return int(op)
        return addrs[op]

    out = bytearray()
    for label, directive, ops in items:
        for op in ops:
            v = value(op) & 0xFFFF
            if directive == "byte":
                if v > 0xFF:
                    sys.exit(f"{name}: byte operand {op} = {v}")
                out.append(v)
            else:
                out += bytes((v & 0xFF, v >> 8))
    return bytes(out), addrs


# ---- stream decoding ------------------------------------------------------------------------------

def read_entry(data: bytes, pos: int, player: dict):
    """One row entry of a channel stream at region offset `pos`: prefix opcodes then one terminator.
    Returns (end offset, rows covered, offsets of envelope pointer operands within data)."""
    ptrs, opcodes = [], player["opcodes"]
    while True:
        b = data[pos]
        if b >= 0x80:
            if b & 1:
                return pos + 1, 1 + ((b & 0x7F) >> 1), ptrs
            pos += 1  # instrument
        elif b < 0x40:
            return pos + 1, 1, ptrs
        elif b >= 0x70:
            pos += 1  # volume track
        else:
            if b not in opcodes or b in (0x41, 0x42, 0x44):
                sys.exit(f"unexpected opcode ${b:02X} inside a row at ${player['song_base'] + pos:04X}")
            size, ends = opcodes[b]
            if b in (0x49, 0x4B):
                ptrs.append((pos + 1, KIND_PITCH if b == 0x49 else KIND_ARPEGGIO))
            pos += 1 + size
            if ends:
                return pos, 1, ptrs


def entry_ops(entry: bytes, player: dict) -> list:
    """The prefix tokens of one row entry (as read_entry walks it): [(opcode, operand bytes)]; an instrument change is
    (0x80, b""), a volume track byte (0x70, b"")."""
    out, pos = [], 0
    while True:
        b = entry[pos]
        if b >= 0x80:
            if b & 1:
                return out
            out.append((0x80, b""))
            pos += 1
        elif b < 0x40:
            return out
        elif b >= 0x70:
            out.append((0x70, b""))
            pos += 1
        else:
            size, ends = player["opcodes"][b]
            out.append((b, bytes(entry[pos + 1:pos + 1 + size])))
            pos += 1 + size
            if ends:
                return out


def flatten_channel(data: bytes, start: int, loop: int, player: dict):
    """The rows of one channel stream in play order through one pass (until the `$42` back to the
    loop label), following `$41` references. Returns ([(entry bytes, rows, ptr offsets in entry)],
    index of the entry that starts at the loop label)."""
    entries, loop_index = [], None
    pos, ret, ref_left = start, None, 0
    while True:
        if ret is None and pos == loop and loop_index is None:
            loop_index = len(entries)
        b = data[pos]
        if b == 0x41:
            if ret is not None:
                sys.exit("nested reference")
            ref_left, ret = data[pos + 1], pos + 4
            pos = u16(data, pos + 2) - player["song_base"]
            continue
        if b == 0x42:
            if ret is not None:
                sys.exit("loop inside a reference")
            if u16(data, pos + 1) - player["song_base"] != loop:
                sys.exit("loop target is not the channel's loop label")
            return entries, loop_index
        end, rows, ptrs = read_entry(data, pos, player)
        entries.append((data[pos:end], rows, [(o - pos, k) for o, k in ptrs]))
        pos = end
        if ret is not None:
            ref_left -= 1
            if ref_left == 0:
                pos, ret = ret, None


def split_orders(entries, rows_per_order: int, orders: int, channel: int):
    """Groups a channel's entries into orders of `rows_per_order` rows (entries never span orders)."""
    out, cur, rows = [], [], 0
    for e in entries:
        cur.append(e)
        rows += e[1]
        if rows > rows_per_order:
            sys.exit(f"channel {channel}: an entry spans an order boundary")
        if rows == rows_per_order:
            out.append(cur)
            cur, rows = [], 0
    if cur or len(out) != orders:
        sys.exit(f"channel {channel}: {len(out)} orders of {rows_per_order} rows, expected {orders}")
    return out


# ---- Thor Tracker layout builder ------------------------------------------------------------------

def build_thor_image(song, order_count: int, loop: int, arrangement: str = "plain", edits=(), env_edits=None,
                     sample_edits=None, samples_top: bool = False, song_end: int = 0) -> bytes:
    """Lays `song` (instruments, envelopes, per-order channel entries, DPCM list and samples) out as a Thor
    Tracker image. arrangement "plain": table, DPCM list, envelopes, order streams, patterns, samples exactly
    where FamiStudio's .dmc has them ($C000); "moved": patterns (reversed), envelopes, order streams (shuffled),
    DPCM list, table, with gaps, and the samples in reverse order packed at the top of the region, so every
    address differs. samples_top packs the samples at the top with a plain arrangement; song_end (an address)
    puts a zero gap before the patterns so the song data ends exactly there (0: no gap; "sample start": at
    the sample block). Directory indices and mapping indices are identical in all (envelopes stay ascending by
    index, as the layout requires). edits: [(order, channel, entry index, old byte, new byte)] patched into
    that row's bytes; env_edits: {directory index: (old bytes, new bytes)}; sample_edits: {$4012 offset of a
    sample in the .dmc: (byte index, new byte)}."""
    envs = song["envelopes"]  # directory index -> (kind, bytes)
    instruments = song["instruments"]  # [(vol, arp, duty, pitch) directory indices]
    orders = song["orders"][:order_count]
    base, channels = song["player"]["song_base"], song["player"]["channels"]
    addr = {}

    # Patterns: entry bytes with envelope operands patched later; identical patterns share bytes.
    pattern_keys = []
    for o, chans in enumerate(orders):
        row = []
        for c, entries in enumerate(chans):
            key = tuple((bytes(b), tuple(p)) for b, _, p in entries)
            row.append(key)
        pattern_keys.append(row)
    unique = []
    for row in pattern_keys:
        for key in row:
            if key not in unique:
                unique.append(key)
    sizes = {f"pat{i}": sum(len(b) for b, _ in key) for i, key in enumerate(unique)}
    dpcm = song["dpcm"]  # FamiStudio list entries (4 bytes each, $4012 offsets as in song["dmc"])
    # Samples: each list entry's (offset, length) range of the .dmc; ranges of different mappings coincide.
    dmc = bytearray(song["dmc"])
    for off, (i, b) in (sample_edits or {}).items():
        dmc[64 * off + i] = b
    ranges = sorted({(dpcm[k], 16 * dpcm[k + 1] + 1) for k in range(0, len(dpcm), 4)})
    if not dpcm:
        samples, start, where = bytearray(), 0, {}
    elif arrangement == "plain" and not samples_top:
        samples, start, where = dmc, SAMPLE_BASE, {off: off for off, _ in ranges}
    else:  # one block at the top: each sample from a 64-byte boundary, the block's start as high as it fits
        samples, rel = bytearray(), {}
        for off, length in reversed(ranges):
            rel[off] = (len(samples) + 63) // 64 * 64
            samples[len(samples):] = bytes(rel[off] - len(samples)) + dmc[64 * off:64 * off + length]
        start = (REGION_END - len(samples)) // 64 * 64
        where = {off: (start - SAMPLE_BASE + r) // 64 for off, r in rel.items()}
    if song_end == "sample start":
        song_end = start
    stream_size = 4 * len(orders) + 3
    names = (["header", "table", "dpcm"] + [f"env{i}" for i in sorted(envs)] + [f"ch{c}" for c in range(channels)]
             + [f"pat{i}" for i in range(len(unique))])
    size_of = {"header": 5 + 2 * channels + 4, "table": 8 * len(instruments), "dpcm": len(dpcm),
               **{f"env{i}": len(envs[i][1]) for i in envs}, **{f"ch{c}": stream_size for c in range(channels)}, **sizes}
    if arrangement == "moved":
        shuffled = {5: (3, 1, 4, 0, 2), 7: (3, 6, 1, 4, 0, 5, 2), 8: (3, 6, 1, 4, 7, 0, 5, 2)}[channels]
        names = (["header"] + [f"pat{i}" for i in reversed(range(len(unique)))]
                 + [f"env{i}" for i in sorted(envs)] + [f"ch{c}" for c in shuffled] + ["dpcm", "table"])
    pc = base
    for i, name in enumerate(names):
        addr[name] = pc
        pc += size_of[name] + (3 + i % 5 if arrangement == "moved" and name != "header" else 0)
    if song_end:  # the patterns move up so the data ends at song_end
        gap = song_end - pc
        if gap < 0:
            sys.exit("song data does not fit below song_end")
        first = names.index(next(n for n in names if n.startswith("pat")))
        for name in names[first:]:
            addr[name] += gap
        pc = song_end
    region = bytearray(pc - base)

    def put(at, data):
        region[at - base:at - base + len(data)] = data

    def word(v):
        return bytes((v & 0xFF, v >> 8))

    put(addr["header"], bytes([1]) + word(addr["table"]) + word(addr["dpcm"] - 4 if dpcm else base)
        + b"".join(word(addr[f"ch{c}"]) for c in range(channels)) + song["tempo_words"])
    put(addr["table"], b"".join(word(addr[f"env{i}"]) for ins in instruments for i in ins))
    for i in envs:
        put(addr[f"env{i}"], envs[i][1])
    env_index = song["effect_env_index"]  # (raw address, kind) -> directory index
    for u, key in enumerate(unique):
        at = addr[f"pat{u}"]
        for b, ptrs in key:
            b = bytearray(b)
            for off, kind, raw in ((p[0], p[1], b[p[0]] | b[p[0] + 1] << 8) for p in ptrs):
                b[off:off + 2] = word(addr[f"env{env_index[(raw, kind)]}"])
            put(at, b)
            at += len(b)
    for c in range(channels):
        stream = bytearray()
        for o in range(len(orders)):
            key = pattern_keys[o][c]
            stream += bytes([0x41, len(key)]) + word(addr[f"pat{unique.index(key)}"])
        stream += bytes([0x42]) + word(addr[f"ch{c}"] + 4 * loop)
        put(addr[f"ch{c}"], stream)
    for o, c, e, old, new in edits:
        key = pattern_keys[o][c]
        off = addr[f"pat{unique.index(key)}"] + sum(len(b) for b, _ in key[:e]) - base
        pos = key[e][0].rindex(old)
        if region[off + pos] != old:
            sys.exit("edit target mismatch")
        region[off + pos] = new
    for i, (old, new) in (env_edits or {}).items():
        at = addr[f"env{i}"] - base
        if bytes(region[at:at + len(old)]) != old or len(new) != len(old):
            sys.exit("envelope edit target mismatch")
        region[at:at + len(new)] = new
    dpcm_moved = bytearray(dpcm)
    for k in range(0, len(dpcm), 4):
        dpcm_moved[k] = where[dpcm[k]]
    put(addr["dpcm"], dpcm_moved)
    if samples:
        region[len(region):] = bytes(start - base - len(region)) + samples
    region[len(region):] = bytes(REGION_END - base - len(region))

    header = bytearray(HEADER_SIZE)
    header[0:4] = b"TTNS"
    header[4:8] = song["player"]["player_id"].to_bytes(4, "little")
    header[8:10] = word(base)
    header[10:12] = word(pc - base)
    header[12] = 0
    header[13] = LAYOUT_THOR
    header[14] = len(instruments)
    header[16:18] = word(max(envs) + 1)
    header[HEADER_DPCM_COUNT] = len(dpcm) // 4
    header[HEADER_SAMPLE_SIZE:HEADER_SAMPLE_SIZE + 2] = word(len(samples))
    header[HEADER_SAMPLE_START:HEADER_SAMPLE_START + 2] = word(start)
    directory = bytearray(DIRECTORY_SIZE)
    for i, (kind, data) in envs.items():
        directory[4 * i:4 * i + 4] = word(addr[f"env{i}"]) + bytes((len(data), kind))
    if pc > (start or REGION_END) or start + len(samples) > REGION_END or (samples and start < SAMPLE_BASE):
        sys.exit("layout fixture does not fit the region")
    return bytes(header + directory + region)


def thor_song_from_export(region: bytes, labels_: dict, txt: str, player: dict, dmc: bytes = b""):
    """FamiStudio's export of a test song (its .dmc samples `dmc`) for `player` (PLAYERS entry + "player_id")
    re-expressed as the parts build_thor_image lays out."""
    base, channels = player["song_base"], player["channels"]
    song_attrs = re.search(r'^\tSong [^\n]*', txt, re.M).group(0)
    rows = int(re.search(r'PatternLength="(\d+)"', song_attrs).group(1))
    orders = int(re.search(r' Length="(\d+)"', song_attrs).group(1))
    loop = int(re.search(r'LoopPoint="(-?\d+)"', song_attrs).group(1))
    if region[0] != 1:
        sys.exit("test song: one song expected")
    table = u16(region, 1) - base
    env_labels = sorted((a - base, n) for n, a in labels_.items() if re.fullmatch(r"@env\d+", n))
    ends = [a for a, _ in env_labels[1:]] + [labels_["@samples"] - base]
    env_bytes = {base + a: region[a:e] for (a, _), e in zip(env_labels, ends)}
    # DPCM list: from @samples (the header points 4 bytes before it) to the next label.
    samples_at = labels_["@samples"]
    if u16(region, 3) != samples_at - 4:
        sys.exit("DPCM list pointer is not @samples-4")
    list_end = min(a for n, a in labels_.items() if a >= samples_at and n != "@samples")
    dpcm = bytes(region[samples_at - base:list_end - base])
    if len(dpcm) % 4 or len(dpcm) > 4 * MAX_DPCM_MAPPINGS:
        sys.exit("unexpected DPCM list")
    for k in range(0, len(dpcm), 4):
        if 64 * dpcm[k] + 16 * dpcm[k + 1] + 1 > len(dmc):
            sys.exit("DPCM list entry outside the .dmc")
    count = (env_labels[0][0] - table) // 8
    envs, instruments = {}, []
    for i in range(count):
        ptrs = [u16(region, table + 8 * i + 2 * k) for k in range(4)]
        instruments.append(tuple(4 * i + k for k in range(4)))
        for k, p in enumerate(ptrs):
            envs[4 * i + k] = (k, env_bytes[p])
    per_channel, effect_index = [], {}
    for c in range(channels):
        start = u16(region, 5 + 2 * c) - base
        loop_name = f"@song0ch{c}loop"
        if loop_name not in labels_:  # FamiStudio shares identical channel streams under one label
            twin = next(k for k in range(channels)
                        if labels_.get(f"@song0ch{k}") == start + base and f"@song0ch{k}loop" in labels_)
            loop_name = f"@song0ch{twin}loop"
        loop_at = labels_[loop_name] - base
        entries, loop_index = flatten_channel(region, start, loop_at, player)
        groups = split_orders(entries, rows, orders, c)
        if sum(len(g) for g in groups[:loop]) != loop_index:
            sys.exit(f"channel {c}: loop label is not at order {loop}")
        for g in groups:
            for b, _, ptrs in g:
                for off, kind in ptrs:
                    raw = b[off] | b[off + 1] << 8
                    if (raw, kind) not in effect_index:
                        effect_index[(raw, kind)] = 256 + len(effect_index)
                        envs[effect_index[(raw, kind)]] = (kind, env_bytes[raw])
        per_channel.append(groups)
    tempo = 5 + 2 * channels
    return {
        "player": player,
        "tempo_words": bytes(region[tempo:tempo + 4]),
        "instruments": instruments,
        "envelopes": envs,
        "effect_env_index": effect_index,
        "orders": [[per_channel[c][o] for c in range(channels)] for o in range(orders)],
        "dpcm": dpcm,
        "dmc": bytes(dmc),
    }, rows, orders, loop


def row_speeds(txt: str, rows: int, orders: int):
    """Speed in effect at each row of the first pass, from the FamiStudio text (Speed effects on any
    channel, FamiTrackerSpeed initially): the timing the engine must reproduce, independent of the export."""
    speed = int(re.search(r'FamiTrackerSpeed="(\d+)"', txt).group(1))
    changes = {}
    for chan in re.split(r'\n\t\tChannel ', txt)[1:]:
        patterns = {}
        for pat in re.split(r'\n\t\t\tPattern ', chan)[1:]:
            name = re.match(r'Name="([^"]+)"', pat).group(1)
            patterns[name] = [(int(t), int(s)) for t, s in
                              re.findall(r'Note Time="(\d+)"[^\n]*? Speed="(\d+)"', pat)]
        for t, name in re.findall(r'PatternInstance Time="(\d+)" Pattern="([^"]+)"', chan):
            for row, s in patterns.get(name, []):
                changes[(int(t), row)] = s
    table = []
    for o in range(orders):
        line = []
        for r in range(rows):
            speed = changes.get((o, r), speed)
            line.append(speed)
        table.append(line)
    return table


DPCM_PLAY, DPCM_STOP, DPCM_DELTA = 1, 2, 3


def dpcm_events(txt: str):
    """The DMC register writes the DPCM channel of the FamiStudio text asks for in the first pass, read from the
    text alone (samples packed as FamiStudio's .dmc does: project order, each from a 64-byte boundary):
    [(order, row, frame in row, kind, $4010, $4012, $4013, $4011 or -1)]."""
    def attrs(line):
        return dict(re.findall(r'(\w+)="([^"]*)"', line))
    samples, at = {}, 0
    for line in re.findall(r'^\tDPCMSample [^\n]*', txt, re.M):
        a = attrs(line)
        size = len(a["Data"]) // 2
        samples[a["Name"]] = (at // 64, size >> 4, 2 * int(a.get("DmcInitialValue", "32")))
        at += (size + 63) // 64 * 64
    mappings, instrument = {}, None
    for line in txt.splitlines():
        if line.startswith("\tInstrument "):
            instrument = attrs(line)["Name"]
        elif line.startswith("\t\tDPCMMapping "):
            a = attrs(line)
            start, length, init = samples[a["Sample"]]
            if "DmcInitialValue" in a:
                init = 2 * int(a["DmcInitialValue"])
            ctrl = int(a["Pitch"]) | (0x40 if a["Loop"] == "True" else 0)
            mappings[(instrument, a["Note"])] = (ctrl, start, length, init)
    chan = re.split(r'\n\t\tChannel ', txt)
    dpcm = next(c for c in chan if c.startswith('Type="DPCM"'))
    patterns = {}
    for pat in re.split(r'\n\t\t\tPattern ', dpcm)[1:]:
        lines = pat.split("\n")
        patterns[attrs(lines[0])["Name"]] = [attrs(l) for l in lines[1:] if l.startswith("\t\t\t\tNote ")]
    events = []
    for order, name in re.findall(r'PatternInstance Time="(\d+)" Pattern="([^"]+)"', dpcm):
        for n in patterns[name]:
            o, r = int(order), int(n["Time"])
            value = n.get("Value")
            if value == "Stop":
                events.append((o, r, int(n.get("NoteDelay", 0)), DPCM_STOP, 0, 0, 0, -1))
            elif value:
                ctrl, start, length, init = mappings[(n["Instrument"], value)]
                level = int(n["DeltaCounter"]) if "DeltaCounter" in n else -1 if n.get("Attack") == "False" else init
                events.append((o, r, int(n.get("NoteDelay", 0)), DPCM_PLAY, ctrl, start, length, level))
            elif "DeltaCounter" in n:
                events.append((o, r, 0, DPCM_DELTA, 0, 0, 0, int(n["DeltaCounter"])))
            if "CutDelay" in n:
                events.append((o, r, int(n["CutDelay"]), DPCM_STOP, 0, 0, 0, -1))
    return sorted(events)


def famistudio_export(app: Path, txt: Path, out: Path) -> None:
    dll = app / "FamiStudio.dll"
    if not dll.exists():
        sys.exit(f"{dll} not found")
    cmd = ["dotnet", str(dll), str(txt), "famistudio-asm-export", str(out), "-famistudio-asm-format:ca65"]
    if not shutil.which("dotnet"):
        cmd = ["distrobox", "enter", "ubuntu", "--"] + cmd
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0 or not out.exists():
        sys.exit(f"FamiStudio export failed:\n{res.stdout}{res.stderr}")


def kotlin_hex(data: bytes) -> str:
    text = data.hex().upper()
    return "\n".join(f'        "{text[i:i + 96]}" +' for i in range(0, len(text), 96)).rstrip(" +")


def assemble_player(prefix: list, t: Path, name: str, info: dict):
    """Assembles and links nes_player.s as the PLAYERS entry `name` and checks the result: entry table, the
    song base past the player (VRC6, MMC5: the first page after it), engine RAM below the audition area, the engine
    symbols and the channel/envelope tables. Returns (player bytes, symbols, segments)."""
    base = info["song_base"]
    defines = [a for d in info["defines"] for a in ("-D", d)]
    run(prefix, ["ca65", "-g", "-I", str(ENGINE_DIR), "--bin-include-dir", str(ENGINE_DIR), *defines,
                 "-D", f"NES_SONG_BASE=${base:04X}", str(NES_DIR / "nes_player.s"), "-o", str(t / f"{name}.o")], t)
    run(prefix, ["ld65", "-C", str(NES_DIR / "nes_player.cfg"), "-o", str(t / f"{name}.bin"),
                 "-Ln", str(t / f"{name}.lbl"), "-m", str(t / f"{name}.map"), str(t / f"{name}.o")], t)
    player = (t / f"{name}.bin").read_bytes()
    syms = labels(t / f"{name}.lbl")
    segs = segments((t / f"{name}.map").read_text())
    for entry, addr in ENTRY.items():
        if syms.get(entry) != addr:
            sys.exit(f"{name}: {entry} at {syms.get(entry)!r}, expected ${addr:04X}")
    end = PLAYER_BASE + len(player)
    if end > base or (name != "2a03" and (end + 0xFF) & ~0xFF != base):
        sys.exit(f"{name}: player ends at ${end:04X}; song base ${base:04X} must be past it"
                 + ("" if name == "2a03" else " on the first page after it"))
    zp, bss = segs["ZEROPAGE"], segs["BSS"]
    if zp[0] != 0 or zp[1] > 0x100 or bss[0] != 0x200 or bss[0] + bss[1] > AUDITION_RAM:
        sys.exit(f"{name}: unexpected RAM layout {segs} (engine RAM must end below ${AUDITION_RAM:04X})")
    missing = [s for s in SYMBOLS + {"vrc6": VRC6_SYMBOLS, "mmc5": MMC5_SYMBOLS}.get(name, []) if s not in syms]
    if missing:
        sys.exit(f"{name}: missing engine symbols {missing}")
    num_envelopes = syms["famistudio_env_repeat"] - syms["famistudio_env_value"]
    num_pitch = syms["famistudio_pitch_env_value_hi"] - syms["famistudio_pitch_env_value_lo"]
    channels = syms["famistudio_chn_ptr_hi"] - syms["famistudio_chn_ptr_lo"]
    if (num_envelopes, num_pitch, channels) != (info["envelopes"], info["pitch_envelopes"], info["channels"]):
        sys.exit(f"{name}: unexpected engine table sizes {num_envelopes} envelopes, {num_pitch} pitch, {channels} channels")
    rom = lambda a: player[a - PLAYER_BASE]  # noqa: E731
    channel_env = [rom(syms["famistudio_channel_env"] + c) for c in range(channels)]
    channel_pitch = [rom(syms["famistudio_channel_to_pitch_env"] + c) for c in range(channels)]
    if channel_env != info["channel_env"] or channel_pitch != info["channel_pitch"]:
        sys.exit(f"{name}: unexpected channel tables {channel_env} {channel_pitch}")
    return player, syms, segs


def assemble_measured(prefix: list, t: Path, name: str):
    """The MMC5 build: assembled once at a provisional song base to measure the player (the base is only an operand of
    init's famistudio_init call, so the code's size does not depend on it), then at the first page after it.
    Returns (PLAYERS[name] with its song base, player bytes, symbols, segments)."""
    defines = [a for d in PLAYERS[name]["defines"] for a in ("-D", d)]
    run(prefix, ["ca65", "-I", str(ENGINE_DIR), "--bin-include-dir", str(ENGINE_DIR), *defines, "-D", "NES_SONG_BASE=$C000",
                 str(NES_DIR / "nes_player.s"), "-o", str(t / f"{name}_probe.o")], t)
    run(prefix, ["ld65", "-C", str(NES_DIR / "nes_player.cfg"), "-o", str(t / f"{name}_probe.bin"),
                 str(t / f"{name}_probe.o")], t)
    end = PLAYER_BASE + len((t / f"{name}_probe.bin").read_bytes())
    info = {**PLAYERS[name], "song_base": (end + 0xFF) & ~0xFF}
    player, syms, segs = assemble_player(prefix, t, name, info)
    return info, player, syms, segs


def link_song(prefix: list, t: Path, name: str, ca65_text: str, base: int, preamble: str = "") -> bytes:
    """Links a FamiStudio ca65 export (made acceptable to ca65 by ca65_fixed) alone at `base`."""
    (t / f"{name}_ca65.s").write_text(ca65_fixed(ca65_text))
    (t / f"{name}_song.s").write_text(f'.segment "SONG"\n{preamble}.include "{name}_ca65.s"\n')
    (t / f"{name}_song.cfg").write_text(
        f"MEMORY {{\n    SONG: start = ${base:04X}, size = ${REGION_END - base:04X}, type = ro, file = %O, fill = no;\n}}\n"
        "SEGMENTS {\n    SONG: load = SONG, type = ro;\n}\n")
    run(prefix, ["ca65", "-I", str(t), str(t / f"{name}_song.s"), "-o", str(t / f"{name}_song.o")], t)
    run(prefix, ["ld65", "-C", str(t / f"{name}_song.cfg"), "-o", str(t / f"{name}_song.bin"), str(t / f"{name}_song.o")], t)
    return (t / f"{name}_song.bin").read_bytes()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--famistudio", type=Path,
                    help="FamiStudio 4.5.3 Linux release dir: re-export the test songs and compare (a missing export, "
                         "e.g. mmc5_test_ca65.s on first use, is written)")
    args = ap.parse_args()

    for path, want in PINNED.items():
        got = sha256(path.read_bytes())
        if got != want:
            sys.exit(f"{path.relative_to(ROOT)}: sha256 {got} != pinned {want}")
    if args.famistudio:
        with tempfile.TemporaryDirectory(dir="/tmp") as tmp:
            for txt, ca65, dmc in ((TEST_SONG_TXT, TEST_SONG_CA65, None), (DPCM_SONG_TXT, DPCM_SONG_CA65, DPCM_SONG_DMC),
                                   (VRC6_SONG_TXT, VRC6_SONG_CA65, None), (MMC5_SONG_TXT, MMC5_SONG_CA65, None)):
                fresh = Path(tmp) / ca65.name
                famistudio_export(args.famistudio.resolve(), txt, fresh)
                if not ca65.exists():
                    ca65.write_bytes(fresh.read_bytes())
                    print(f"FamiStudio export of {txt.name}: written to {ca65.relative_to(ROOT)}")
                    continue
                if fresh.read_bytes() != ca65.read_bytes():
                    sys.exit(f"FamiStudio's export of {txt.name} differs from {ca65.relative_to(ROOT)}")
                if dmc and fresh.with_suffix(".dmc").read_bytes() != dmc.read_bytes():
                    sys.exit(f"FamiStudio's samples of {txt.name} differ from {dmc.relative_to(ROOT)}")
                print(f"FamiStudio re-export of {txt.name}: identical")
    if not MMC5_SONG_CA65.exists():
        sys.exit(f"{MMC5_SONG_CA65.relative_to(ROOT)} is missing: run tools/audio/prepare_nes_player.py "
                 "--famistudio \"$(tools/audio/fetch_famistudio.sh)\" once to export it")

    prefix = tool_prefix()
    version = run(prefix, ["ca65", "--version"], ROOT).strip()
    if version != CC65_VERSION:
        sys.exit(f"assembler '{version}' != pinned '{CC65_VERSION}'")

    with tempfile.TemporaryDirectory(dir="/tmp") as tmp:
        t = Path(tmp)
        player, syms, segs = assemble_player(prefix, t, "2a03", PLAYERS["2a03"])
        vplayer, vsyms, vsegs = assemble_player(prefix, t, "vrc6", PLAYERS["vrc6"])
        mmc5_info, mplayer, msyms, msegs = assemble_measured(prefix, t, "mmc5")
        mmc5_base = mmc5_info["song_base"]
        song = link_song(prefix, t, "ft_test", TEST_SONG_CA65.read_text(), SONG_BASE)
        dpcm_song = link_song(prefix, t, "dpcm_test", DPCM_SONG_CA65.read_text(), SONG_BASE,
                              f"FAMISTUDIO_DPCM_PTR = ${DPCM_PTR:02X}\n")
        vrc6_song = link_song(prefix, t, "vrc6_test", VRC6_SONG_CA65.read_text(), VRC6_SONG_BASE)
        mmc5_song = link_song(prefix, t, "mmc5_test", MMC5_SONG_CA65.read_text(), mmc5_base)
    zp, bss = segs["ZEROPAGE"], segs["BSS"]
    vzp, vbss = vsegs["ZEROPAGE"], vsegs["BSS"]
    mzp, mbss = msegs["ZEROPAGE"], msegs["BSS"]
    num_envelopes, num_pitch = PLAYERS["2a03"]["envelopes"], PLAYERS["2a03"]["pitch_envelopes"]

    # Test song: the ld65 link and the parser must agree byte for byte.
    ca65_text = TEST_SONG_CA65.read_text()
    txt = TEST_SONG_TXT.read_text()
    parsed, song_labels = parse_famistudio_ca65(ca65_text, SONG_BASE)
    if parsed != song:
        sys.exit("parser output differs from the ld65 link of ft_test_ca65.s")
    if len(song) > REGION_SIZE or song[0] != 1:
        sys.exit("test song: one song that fits the region expected")
    if "FAMISTUDIO_USE_FAMITRACKER_TEMPO" not in ca65_text:
        sys.exit("test song export is not FamiTracker tempo")

    player_sha = sha256(player)
    player_id = int.from_bytes(bytes.fromhex(player_sha)[:4], "little")
    plain = {**PLAYERS["2a03"], "player_id": player_id}
    thor, rows, orders, loop = thor_song_from_export(song, song_labels, txt, plain)
    speeds = row_speeds(txt, rows, orders)
    base = build_thor_image(thor, orders, loop)
    moved = build_thor_image(thor, orders, loop, "moved")
    # Edit: order 2's pulse 1 note at row 8 (B4, byte $30) becomes D5 ($33); the "Lead" volume
    # envelope's sustain (12, 12) becomes (8, 8) with the same structure.
    lead = 2
    lead_vol = thor["envelopes"][4 * lead][1]
    if lead_vol[3:5] != b"\xcc\xcc":
        sys.exit("unexpected Lead volume envelope")
    env_edit = {4 * lead: (lead_vol, lead_vol[:3] + b"\xc8\xc8" + lead_vol[5:])}
    entry = [i for i, (b, _, _) in enumerate(thor["orders"][2][0]) if b.endswith(b"\x53\x30")]
    if len(entry) != 1:
        sys.exit("edit target row not found")
    edited = build_thor_image(thor, orders, loop, edits=[(2, 0, entry[0], 0x30, 0x33)], env_edits=env_edit)
    short = build_thor_image(thor, 2, 1)
    # Big: the patterns at the top, so the song data fills the whole region ($8C00-$FFF9, no samples): more than
    # 0.9.0's 24570-byte song region, with its patterns read from above $C000.
    big = build_thor_image(thor, orders, loop, song_end=REGION_END)

    # DPCM test song: the same checks, plus its samples (.dmc at SAMPLE_BASE) and the DMC writes the text asks for.
    dpcm_text = DPCM_SONG_CA65.read_text()
    dpcm_txt = DPCM_SONG_TXT.read_text()
    dmc = DPCM_SONG_DMC.read_bytes()
    parsed, dpcm_labels = parse_famistudio_ca65(dpcm_text, SONG_BASE, DPCM_SONG_CA65.name)
    if parsed != dpcm_song:
        sys.exit("parser output differs from the ld65 link of dpcm_test_ca65.s")
    if SAMPLE_BASE + len(dmc) > REGION_END or "FAMISTUDIO_USE_DELTA_COUNTER" not in dpcm_text:
        sys.exit("DPCM test song: samples that fit and the delta counter expected")
    dthor, drows, dorders, dloop = thor_song_from_export(dpcm_song, dpcm_labels, dpcm_txt, plain, dmc)
    dspeeds = row_speeds(dpcm_txt, drows, dorders)
    events = dpcm_events(dpcm_txt)
    for o, r, f, kind, ctrl, start, length, level in events:
        k = next((k for k in range(0, len(dthor["dpcm"]), 4) if dthor["dpcm"][k:k + 3] == bytes((start, length, ctrl))), None)
        if kind == DPCM_PLAY and k is None:
            sys.exit(f"DPCM event at {o}/{r} has no list entry in the export")
    dbase = build_thor_image(dthor, dorders, dloop)
    dmoved = build_thor_image(dthor, dorders, dloop, "moved")
    # Edit: one byte in the middle of the Kick (the sample at .dmc offset 0), inverted.
    edited_byte = 100
    dedited = build_thor_image(dthor, dorders, dloop, sample_edits={0: (edited_byte, dmc[edited_byte] ^ 0xFF)})
    # Boundary: samples packed at the top, song data (patterns moved up) ending exactly at the sample block.
    dboundary = build_thor_image(dthor, dorders, dloop, samples_top=True, song_end="sample start")

    # VRC6 test song: the same checks with the VRC6 player (8 channel streams, song base VRC6_SONG_BASE).
    vplayer_sha = sha256(vplayer)
    vplayer_id = int.from_bytes(bytes.fromhex(vplayer_sha)[:4], "little")
    vrc6 = {**PLAYERS["vrc6"], "player_id": vplayer_id}
    vrc6_text = VRC6_SONG_CA65.read_text()
    vrc6_txt = VRC6_SONG_TXT.read_text()
    parsed, vrc6_labels = parse_famistudio_ca65(vrc6_text, VRC6_SONG_BASE, VRC6_SONG_CA65.name)
    if parsed != vrc6_song:
        sys.exit("parser output differs from the ld65 link of vrc6_test_ca65.s")
    if len(vrc6_song) > REGION_END - VRC6_SONG_BASE or "FAMISTUDIO_EXP_VRC6 = 1" not in vrc6_text \
            or "FAMISTUDIO_USE_FAMITRACKER_TEMPO" not in vrc6_text:
        sys.exit("VRC6 test song: a FamiTracker-tempo VRC6 export that fits the region expected")
    if [v for v in re.findall(r"\$55, (\$\w+)", ca65_fixed(vrc6_text))] != ["$01", "$ff", "$01", "$00"]:
        sys.exit("VRC6 test song: saw master volumes Full, Quarter, Full, Half expected")
    vthor, vrows, vorders, vloop = thor_song_from_export(vrc6_song, vrc6_labels, vrc6_txt, vrc6)
    vspeeds = row_speeds(vrc6_txt, vrows, vorders)
    vbase = build_thor_image(vthor, vorders, vloop)
    vmoved = build_thor_image(vthor, vorders, vloop, "moved")
    # Edit: order 2's VRC6 pulse 1 note at row 8 (E4 with VThin, $8E $29) becomes G4 ($2C); the "VPulse" volume
    # envelope's sustain (12, 12) becomes (8, 8).
    vpulse = 0
    vpulse_vol = vthor["envelopes"][4 * vpulse][1]
    if vpulse_vol[3:5] != b"\xcc\xcc":
        sys.exit("unexpected VPulse volume envelope")
    venv_edit = {4 * vpulse: (vpulse_vol, vpulse_vol[:3] + b"\xc8\xc8" + vpulse_vol[5:])}
    entry = [i for i, (b, _, _) in enumerate(vthor["orders"][2][5]) if b.endswith(b"\x8e\x29")]
    if len(entry) != 1:
        sys.exit("VRC6 edit target row not found")
    vedited = build_thor_image(vthor, vorders, vloop, edits=[(2, 5, entry[0], 0x29, 0x2C)], env_edits=venv_edit)

    # MMC5 test song: the same checks with the MMC5 player (7 channel streams, song base mmc5_base).
    mplayer_sha = sha256(mplayer)
    mplayer_id = int.from_bytes(bytes.fromhex(mplayer_sha)[:4], "little")
    mmc5 = {**mmc5_info, "player_id": mplayer_id}
    mmc5_text = MMC5_SONG_CA65.read_text()
    mmc5_txt = MMC5_SONG_TXT.read_text()
    parsed, mmc5_labels = parse_famistudio_ca65(mmc5_text, mmc5_base, MMC5_SONG_CA65.name)
    if parsed != mmc5_song:
        sys.exit("parser output differs from the ld65 link of mmc5_test_ca65.s")
    if len(mmc5_song) > REGION_END - mmc5_base or "FAMISTUDIO_EXP_MMC5 = 1" not in mmc5_text \
            or "FAMISTUDIO_USE_FAMITRACKER_TEMPO" not in mmc5_text:
        sys.exit("MMC5 test song: a FamiTracker-tempo MMC5 export that fits the region expected")
    mthor, mrows, morders, mloop = thor_song_from_export(mmc5_song, mmc5_labels, mmc5_txt, mmc5)
    # The MMC5 pulses' streams hold only the 2A03 build's opcodes: thor_song_from_export walked every row with read_entry,
    # which exits on any opcode outside mmc5["opcodes"] = OPCODES (FamiStudio's expansion opcodes $55-$5F included).
    # They must also hold the effects the test song asks for there: extended notes (A0, B7), release, note delay, cut,
    # vibrato, arpeggio, fine pitch, duty, slide and volume slide.
    mops = {op for o in mthor["orders"] for c in (5, 6) for b, _, _ in o[c] for op, _ in entry_ops(b, mmc5)}
    missing_ops = {0x40, 0x45, 0x47, 0x48, 0x49, 0x4B, 0x4E, 0x4F, 0x50, 0x51} - mops
    if missing_ops:
        sys.exit(f"MMC5 test song: the MMC5 pulses lack opcodes {[f'${op:02X}' for op in sorted(missing_ops)]}")
    mnames = re.findall(r"^\t\.word @env\d+,@env\d+,@env\d+,@env\d+ ; ([0-9a-f]{2}) : (\w+)$", mmc5_text, re.M)
    mindex = {n: int(i, 16) for i, n in mnames}
    if len(mnames) != len(mthor["instruments"]) or "MPulse" not in mindex:
        sys.exit("instrument names not found in the MMC5 export")
    mspeeds = row_speeds(mmc5_txt, mrows, morders)
    mbase = build_thor_image(mthor, morders, mloop)
    mmoved = build_thor_image(mthor, morders, mloop, "moved")
    # Edit: order 2's MMC5 pulse 1 note at row 8 (E4 with the instrument change to MPulse, $80 + 2 × index, $29) becomes
    # G4 ($2C); the "MPulse" volume envelope's sustain (12, 12) becomes (8, 8).
    mpulse = mindex["MPulse"]
    mpulse_vol = mthor["envelopes"][4 * mpulse][1]
    if mpulse_vol[3:5] != b"\xcc\xcc":
        sys.exit("unexpected MPulse volume envelope")
    menv_edit = {4 * mpulse: (mpulse_vol, mpulse_vol[:3] + b"\xc8\xc8" + mpulse_vol[5:])}
    starts, at = [], 0
    for _, n, _ in mthor["orders"][2][5]:
        starts.append(at)
        at += n
    entry = [i for i, s in enumerate(starts)
             if s == 8 and mthor["orders"][2][5][i][0].endswith(bytes((0x80 | mpulse << 1, 0x29)))]
    if len(entry) != 1:
        sys.exit("MMC5 edit target row not found")
    medited = build_thor_image(mthor, morders, mloop, edits=[(2, 5, entry[0], 0x29, 0x2C)], env_edits=menv_edit)

    sym_lines = "\n".join(f"#define NES_{s.upper()} 0x{syms[s]:04X}" for s in SYMBOLS)
    OUT_PLAYER.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
// FamiStudio sound engine {FAMISTUDIO_TAG} (github.com/BleuBleu/FamiStudio commit {FAMISTUDIO_COMMIT}),
// famistudio_ca65.s sha256 {PINNED[ENGINE_DIR / 'famistudio_ca65.s']}, unmodified, configured (FamiTracker tempo)
// and wrapped by tools/audio/nes/nes_player.s; assembled with {CC65_VERSION}.
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
#define NES_SONG_BASE 0x{SONG_BASE:04X} /* song data (FamiStudio format) from here upward */
#define NES_REGION_END 0x{REGION_END:04X} /* song data and samples share NES_SONG_BASE..here (exclusive; vectors follow) */
#define NES_SAMPLE_BASE 0x{SAMPLE_BASE:04X} /* lowest sample address ($4012 = (address - $C000) / 64) */
#define NES_ZP_SIZE 0x{zp[1]:04X}
#define NES_RAM_BASE 0x{bss[0]:04X}
#define NES_RAM_SIZE 0x{bss[1]:04X}
#define NES_AUDITION_RAM 0x{AUDITION_RAM:04X} /* free RAM up to $07FF: audition streams */
#define NES_NUM_ENVELOPES {num_envelopes}
#define NES_NUM_PITCH_ENVELOPES {num_pitch}

/* Image layout (docs/AUDIO_ENGINE.md, "Image layout"): header, envelope directory, region = CPU memory
 * NES_SONG_BASE..NES_REGION_END: song data from NES_SONG_BASE, the sample block at its start (header). */
#define NES_IMAGE_HEADER_SIZE {HEADER_SIZE}
#define NES_IMAGE_HEADER_DPCM_COUNT {HEADER_DPCM_COUNT} /* u8: DPCM list entries (Thor layout) */
#define NES_IMAGE_HEADER_SAMPLE_SIZE {HEADER_SAMPLE_SIZE} /* u16: bytes of the sample block (0: none) */
#define NES_IMAGE_HEADER_SAMPLE_START {HEADER_SAMPLE_START} /* u16: its address (0: none; 64-aligned, >= NES_SAMPLE_BASE) */
#define NES_IMAGE_MAX_ENVELOPES {MAX_ENVELOPES}
#define NES_IMAGE_DIRECTORY_SIZE {DIRECTORY_SIZE}
#define NES_IMAGE_MAX_INSTRUMENTS {MAX_INSTRUMENTS}
#define NES_IMAGE_MAX_ORDERS {MAX_ORDERS}
#define NES_IMAGE_MAX_ROWS {MAX_ROWS}
#define NES_IMAGE_MAX_DPCM_MAPPINGS {MAX_DPCM_MAPPINGS}
#define NES_IMAGE_SAMPLE_ALIGN {SAMPLE_ALIGN}
#define NES_IMAGE_MAX_SAMPLE_BYTES {MAX_SAMPLE_BYTES} /* $4013 = $FF: 16 * 255 + 1 */
#define NES_IMAGE_REGION_SIZE {REGION_SIZE}
#define NES_IMAGE_SIZE {IMAGE_SIZE}

{sym_lines}

static const uint8_t nes_player_code[NES_PLAYER_SIZE] = {{
{c_bytes(player)}
}};

#endif /* NES_PLAYER_IMAGE_H_ */
""")

    OUT_KOTLIN.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
package org.thortracker.backend

/**
 * The NES preview player: FamiStudio sound engine {FAMISTUDIO_TAG} (FamiTracker tempo) behind an NSF-style entry
 * table, assembled once by `tools/audio/prepare_nes_player.py`, and the numbers of the native image layout
 * (`docs/AUDIO_ENGINE.md`, "Image layout"). The same bytes play in the app (native
 * `app/src/main/cpp/generated/nes_player_image.h`) and form an `.nsf` with the song region.
 * Image sha256 `{player_sha}`.
 * Engine licence: all-permissive, notice in NOTICE.txt.
 */
object NesPlayerImage {{
    /** Load, init (A = song index) and play addresses; the player starts at [LOAD]. */
    const val LOAD = 0x{PLAYER_BASE:04X}
    const val INIT = 0x{ENTRY['nes_player_init']:04X}
    const val PLAY = 0x{ENTRY['nes_player_play']:04X}

    /**
     * Region: song data from [SONG_BASE] upward and the samples share CPU memory [SONG_BASE] until [REGION_END]
     * (exclusive; vectors follow), [REGION_SIZE] bytes. A song without samples may use all of it; otherwise the
     * song data must end at or below the sample block's start.
     */
    const val SONG_BASE = 0x{SONG_BASE:04X}
    const val REGION_END = 0x{REGION_END:04X}
    const val REGION_SIZE = {REGION_SIZE}

    /**
     * Samples: one block from a [SAMPLE_ALIGN]-aligned start at or above [SAMPLE_BASE] (the lowest address `$4012`
     * reaches: `$4012` = (address − [SAMPLE_BASE]) / 64), each sample starting on a [SAMPLE_ALIGN] boundary and
     * [MAX_SAMPLE_BYTES] long at most (`$4013` = (bytes − 1) / 16; the hardware plays 16 n + 1 bytes).
     */
    const val SAMPLE_BASE = 0x{SAMPLE_BASE:04X}
    const val SAMPLE_ALIGN = {SAMPLE_ALIGN}
    const val MAX_SAMPLE_BYTES = {MAX_SAMPLE_BYTES}

    /** Entries of the FamiStudio DPCM list (without FamiStudio's extended DPCM range): stream note bytes 1..63. */
    const val MAX_DPCM_MAPPINGS = {MAX_DPCM_MAPPINGS}

    /** Header bytes 4..7 (little-endian): an image only plays on the player build it was lowered for. */
    const val PLAYER_ID = 0x{player_id:08X}.toInt()

    const val HEADER_SIZE = {HEADER_SIZE}

    /** Header byte: entries in the DPCM list. */
    const val HEADER_DPCM_COUNT = {HEADER_DPCM_COUNT}

    /** Header u16 (little-endian): bytes of the sample block (0: no samples). */
    const val HEADER_SAMPLE_SIZE = {HEADER_SAMPLE_SIZE}

    /** Header u16 (little-endian): address of the sample block (0: no samples). */
    const val HEADER_SAMPLE_START = {HEADER_SAMPLE_START}

    const val MAX_ENVELOPES = {MAX_ENVELOPES}
    const val DIRECTORY_SIZE = {DIRECTORY_SIZE}
    const val MAX_INSTRUMENTS = {MAX_INSTRUMENTS}
    const val MAX_ORDERS = {MAX_ORDERS}
    const val MAX_ROWS = {MAX_ROWS}

    /**
     * Bytes of a lowered image: header + envelope directory + region, the CPU memory [SONG_BASE] until [REGION_END]
     * (address a at region offset a − [SONG_BASE]).
     */
    const val IMAGE_SIZE = {IMAGE_SIZE}

    /** Header byte 13. */
    const val LAYOUT_THOR = {LAYOUT_THOR}

    /** Envelope directory kinds. */
    const val KIND_VOLUME = {KIND_VOLUME}
    const val KIND_ARPEGGIO = {KIND_ARPEGGIO}
    const val KIND_DUTY = {KIND_DUTY}
    const val KIND_PITCH = {KIND_PITCH}

    /** The player image ({len(player)} bytes at [LOAD]); a fresh copy per call. */
    val BYTES: ByteArray get() = ByteArray(HEX.length / 2) {{ HEX.substring(2 * it, 2 * it + 2).toInt(16).toByte() }}

    private const val HEX =
{kotlin_hex(player)}
}}
""")

    def c_array(name, data, trim=True):
        if trim:  # images: the loader zero-fills the rest
            data = data[:max(len(data.rstrip(b"\0")), 1)]
        return f"#define {name.upper()}_SIZE {len(data)}\nstatic const uint8_t {name}[{name.upper()}_SIZE] = {{\n{c_bytes(data)}\n}};\n"

    names = re.findall(r"^\t\.word @env\d+,@env\d+,@env\d+,@env\d+ ; ([0-9a-f]{2}) : (\w+)$", ca65_text, re.M)
    if len(names) != len(thor["instruments"]):
        sys.exit("instrument names not found in the export")
    instrument_defines = "\n".join(f"#define NES_FT_INSTRUMENT_{n.upper()} {int(i, 16)}" for i, n in names)
    speed_rows = ",\n".join("    {" + ", ".join(str(s) for s in line) + "}" for line in speeds)
    OUT_SONG.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
// Test song tools/audio/nes/ft_test.txt (sha256 {sha256(TEST_SONG_TXT.read_bytes())}, FamiTracker tempo),
// as exported by FamiStudio {FAMISTUDIO_TAG} itself (tools/audio/nes/ft_test_ca65.s, sha256 {sha256(TEST_SONG_CA65.read_bytes())}).
//   nes_ft_raw:    that export linked at NES_SONG_BASE (song region bytes; raw layout).
//   nes_ft_thor:   the same rows rearranged into the Thor Tracker layout (header + envelope directory + region);
//                  every row's bytes are FamiStudio's.
//   nes_ft_moved:  nes_ft_thor with every block at another address (same directory indices).
//   nes_ft_edited: nes_ft_thor with order 2 pulse 1 row 8 B4 -> D5 and the Lead volume sustain 12,12 -> 8,8.
//   nes_ft_short:  nes_ft_thor cut to orders 0-1 (loop 1).
//   nes_ft_big:    nes_ft_thor with its patterns moved to the top: song data fills the whole region (no samples).
#ifndef NES_FT_SONG_H_
#define NES_FT_SONG_H_
#include <stdint.h>

#define NES_FT_ROWS {rows}
#define NES_FT_ORDERS {orders}
#define NES_FT_LOOP {loop}
#define NES_FT_INSTRUMENTS {len(thor['instruments'])}
#define NES_FT_TEMPO_NTSC {u16(song, 17)}
{instrument_defines}

/* Speed in effect at each row of the first pass, read from ft_test.txt (not from the export). */
static const uint8_t nes_ft_speed[NES_FT_ORDERS][NES_FT_ROWS] = {{
{speed_rows}
}};

{c_array("nes_ft_raw", song)}
{c_array("nes_ft_thor", base)}
{c_array("nes_ft_moved", moved)}
{c_array("nes_ft_edited", edited)}
{c_array("nes_ft_short", short)}
{c_array("nes_ft_big", big)}
#endif /* NES_FT_SONG_H_ */
""")

    region_at = HEADER_SIZE + DIRECTORY_SIZE

    def c_split(name, image):
        """An image as header + directory + song data, and its sample block (NAME_SAMPLE_START)."""
        size, start, count = u16(image, 10), u16(image, HEADER_SAMPLE_START), u16(image, HEADER_SAMPLE_SIZE)
        at = region_at + start - SONG_BASE
        return (f"#define {name.upper()}_SAMPLE_START 0x{start:04X}\n" + c_array(name, image[:region_at + size])
                + c_array(f"{name}_samples", image[at:at + count], trim=False))

    dpcm_list = dthor["dpcm"]
    sample_names = re.findall(r"^\t\.byte \$[0-9a-f]{2}\+\.lobyte\(FAMISTUDIO_DPCM_PTR\),[^;]*; ([0-9a-f]{2}) (\w+) \(Pitch:(\d+)\)$",
                              dpcm_text, re.M)
    if len(sample_names) != len(dpcm_list) // 4:
        sys.exit("DPCM mapping names not found in the export")
    mapping_defines = "\n".join(f"#define NES_DPCM_MAP_{n.upper()}_{p} {int(i, 16) + 1}" for i, n, p in sample_names)
    dspeed_rows = ",\n".join("    {" + ", ".join(str(s) for s in line) + "}" for line in dspeeds)
    event_rows = ",\n".join("    {" + ", ".join(str(v) for v in e) + "}" for e in events)
    OUT_DPCM.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
// DPCM test song tools/audio/nes/dpcm_test.txt (sha256 {sha256(DPCM_SONG_TXT.read_bytes())}, FamiTracker tempo),
// as exported by FamiStudio {FAMISTUDIO_TAG} itself (tools/audio/nes/dpcm_test_ca65.s, sha256 {sha256(DPCM_SONG_CA65.read_bytes())};
// samples dpcm_test_ca65.dmc, sha256 {sha256(dmc)}).
//   nes_dpcm_raw / nes_dpcm_dmc: that export linked at NES_SONG_BASE and its samples for NES_SAMPLE_BASE (raw layout).
//   nes_dpcm_thor:   the same rows, DPCM list and samples (where the .dmc has them) in the Thor Tracker layout;
//                    every row's bytes are FamiStudio's. Arrays: header + directory + song data, then *_samples.
//   nes_dpcm_moved:  every block and every sample at another address, the samples packed at the top (same
//                    directory and mapping indices).
//   nes_dpcm_edited: nes_dpcm_thor with byte NES_DPCM_EDITED_BYTE of the Kick sample inverted.
//   nes_dpcm_boundary: samples packed at the top, the patterns moved up so the song data ends exactly at them.
#ifndef NES_DPCM_SONG_H_
#define NES_DPCM_SONG_H_
#include <stdint.h>

#define NES_DPCM_ROWS {drows}
#define NES_DPCM_ORDERS {dorders}
#define NES_DPCM_LOOP {dloop}
#define NES_DPCM_MAPPINGS {len(dpcm_list) // 4}
#define NES_DPCM_EDITED_BYTE {edited_byte}
/* Mapping (stream note byte, 1-based list index) of each sample/pitch in the export. */
{mapping_defines}

/* Speed in effect at each row of the first pass, read from dpcm_test.txt (not from the export). */
static const uint8_t nes_dpcm_speed[NES_DPCM_ORDERS][NES_DPCM_ROWS] = {{
{dspeed_rows}
}};

/* The DMC writes the DPCM channel of dpcm_test.txt asks for in the first pass, from the text alone (samples
 * packed like FamiStudio's .dmc): order, row, frame in the row, kind (1 play: $4015=$0F, $4012, $4013,
 * $4010, [$4011], $4015=$1F; 2 stop: $4015=$0F; 3 delta counter: $4011), $4010, $4012, $4013, $4011 or -1. */
#define NES_DPCM_EVENTS {len(events)}
static const int16_t nes_dpcm_events[NES_DPCM_EVENTS][8] = {{
{event_rows}
}};

{c_array("nes_dpcm_raw", dpcm_song)}
{c_array("nes_dpcm_dmc", dmc, trim=False)}
{c_split("nes_dpcm_thor", dbase)}
{c_split("nes_dpcm_moved", dmoved)}
{c_split("nes_dpcm_edited", dedited)}
{c_split("nes_dpcm_boundary", dboundary)}
#endif /* NES_DPCM_SONG_H_ */
""")

    vsym_lines = "\n".join(f"#define NES_VRC6_{s.upper()} 0x{vsyms[s]:04X}" for s in SYMBOLS + VRC6_SYMBOLS)
    vregion_size = REGION_END - VRC6_SONG_BASE
    vimage_size = HEADER_SIZE + DIRECTORY_SIZE + vregion_size
    OUT_VRC6_PLAYER.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
// FamiStudio sound engine {FAMISTUDIO_TAG} (github.com/BleuBleu/FamiStudio commit {FAMISTUDIO_COMMIT}),
// famistudio_ca65.s sha256 {PINNED[ENGINE_DIR / 'famistudio_ca65.s']}, unmodified, configured (FamiTracker tempo)
// and wrapped by tools/audio/nes/nes_player.s like nes_player_image.h, assembled with FAMISTUDIO_EXP_VRC6 = 1
// (Konami VRC6: two pulses and a saw after the 2A03's five channels); saw note tables
// NoteTables/famistudio_saw_note_table_lsb/msb.bin (MIT, FamiStudio); assembled with {CC65_VERSION}.
// Engine licence: "Copying and distribution of this file, with or without modification, are permitted in any
// medium without royalty provided the copyright notice and this notice are preserved" (c) 2019-2026 Mathieu Gauthier.
// image sha256 {vplayer_sha}
#ifndef NES_VRC6_PLAYER_IMAGE_H_
#define NES_VRC6_PLAYER_IMAGE_H_
#include <stdint.h>

#define NES_VRC6_PLAYER_BASE 0x{PLAYER_BASE:04X}
#define NES_VRC6_PLAYER_SIZE 0x{len(vplayer):04X}
#define NES_VRC6_PLAYER_ID 0x{vplayer_id:08X}u /* first 4 bytes of the image sha256, little-endian */
#define NES_VRC6_PLAYER_INIT 0x{ENTRY['nes_player_init']:04X} /* A = song index */
#define NES_VRC6_PLAYER_PLAY 0x{ENTRY['nes_player_play']:04X} /* one 60 Hz engine frame */
#define NES_VRC6_PLAYER_IDLE 0x{ENTRY['nes_player_idle']:04X} /* return address of host-called routines */
#define NES_VRC6_PLAYER_TRAP 0x{ENTRY['nes_player_trap']:04X} /* BRK/IRQ/NMI vector target: a fault */
#define NES_VRC6_SONG_BASE 0x{VRC6_SONG_BASE:04X} /* song data (FamiStudio format) from here upward: the page after the player */
#define NES_VRC6_ZP_SIZE 0x{vzp[1]:04X}
#define NES_VRC6_RAM_BASE 0x{vbss[0]:04X}
#define NES_VRC6_RAM_SIZE 0x{vbss[1]:04X}
#define NES_VRC6_NUM_ENVELOPES {PLAYERS['vrc6']['envelopes']}
#define NES_VRC6_NUM_PITCH_ENVELOPES {PLAYERS['vrc6']['pitch_envelopes']}
#define NES_VRC6_CHANNELS {PLAYERS['vrc6']['channels']} /* 2A03 pulse 1, pulse 2, triangle, noise, DPCM, VRC6 pulse 1, pulse 2, saw */

/* Image layout: nes_player_image.h's (header, envelope directory, limits, samples from NES_SAMPLE_BASE, region end
 * NES_REGION_END) with the region = CPU memory NES_VRC6_SONG_BASE..NES_REGION_END. */
#define NES_VRC6_IMAGE_REGION_SIZE {vregion_size}
#define NES_VRC6_IMAGE_SIZE {vimage_size}

{vsym_lines}

static const uint8_t nes_vrc6_player_code[NES_VRC6_PLAYER_SIZE] = {{
{c_bytes(vplayer)}
}};

#endif /* NES_VRC6_PLAYER_IMAGE_H_ */
""")

    OUT_VRC6_KOTLIN.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
package org.thortracker.backend

/**
 * The NES + Konami VRC6 preview player: the [NesPlayerImage] player assembled with `FAMISTUDIO_EXP_VRC6 = 1`
 * (FamiStudio sound engine {FAMISTUDIO_TAG}, FamiTracker tempo, the same NSF-style entry table) by
 * `tools/audio/prepare_nes_player.py`. Images use [NesPlayerImage]'s layout (header, envelope directory, limits,
 * samples, kinds) with this player's [PLAYER_ID], [SONG_BASE] and [CHANNELS] channel streams in the song header
 * (`docs/AUDIO_ENGINE.md`, "VRC6"). The same bytes play in the app (native
 * `app/src/main/cpp/generated/nes_vrc6_player_image.h`) and form an `.nsf` (expansion byte VRC6) with the song region.
 * Image sha256 `{vplayer_sha}`.
 * Engine licence: all-permissive, notice in NOTICE.txt.
 */
object NesVrc6PlayerImage {{
    /** Load, init (A = song index) and play addresses; the player starts at [LOAD]. */
    const val LOAD = 0x{PLAYER_BASE:04X}
    const val INIT = 0x{ENTRY['nes_player_init']:04X}
    const val PLAY = 0x{ENTRY['nes_player_play']:04X}

    /**
     * Region: song data from [SONG_BASE] upward and the samples share CPU memory [SONG_BASE] until [REGION_END]
     * (exclusive; vectors follow), [REGION_SIZE] bytes, as for [NesPlayerImage] but from the first page after this
     * larger player.
     */
    const val SONG_BASE = 0x{VRC6_SONG_BASE:04X}
    const val REGION_END = 0x{REGION_END:04X}
    const val REGION_SIZE = {vregion_size}

    /** Header bytes 4..7 (little-endian): an image only plays on the player build it was lowered for. */
    const val PLAYER_ID = 0x{vplayer_id:08X}.toInt()

    /** Bytes of a lowered image: header + envelope directory + region (address a at region offset a − [SONG_BASE]). */
    const val IMAGE_SIZE = {vimage_size}

    /**
     * Channel streams in the song header, in engine order: 2A03 pulse 1, pulse 2, triangle, noise, DPCM, then VRC6
     * pulse 1, pulse 2, saw.
     */
    const val CHANNELS = {PLAYERS['vrc6']['channels']}

    /** The player image ({len(vplayer)} bytes at [LOAD]); a fresh copy per call. */
    val BYTES: ByteArray get() = ByteArray(HEX.length / 2) {{ HEX.substring(2 * it, 2 * it + 2).toInt(16).toByte() }}

    private const val HEX =
{kotlin_hex(vplayer)}
}}
""")

    vnames = re.findall(r"^\t\.word @env\d+,@env\d+,@env\d+,@env\d+ ; ([0-9a-f]{2}) : (\w+)$", vrc6_text, re.M)
    if len(vnames) != len(vthor["instruments"]):
        sys.exit("instrument names not found in the VRC6 export")
    vinstrument_defines = "\n".join(f"#define VRC6_TEST_INSTRUMENT_{n.upper()} {int(i, 16)}" for i, n in vnames)
    vspeed_rows = ",\n".join("    {" + ", ".join(str(s) for s in line) + "}" for line in vspeeds)
    OUT_VRC6_SONG.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
// VRC6 test song tools/audio/nes/vrc6_test.txt (sha256 {sha256(VRC6_SONG_TXT.read_bytes())}, FamiTracker tempo),
// as exported by FamiStudio {FAMISTUDIO_TAG} itself (tools/audio/nes/vrc6_test_ca65.s, sha256 {sha256(VRC6_SONG_CA65.read_bytes())};
// its `$55, $ffffffff` read as `$55, $ff`, as FamiStudio's binary export writes it).
//   nes_vrc6_raw:    that export linked at NES_VRC6_SONG_BASE (song region bytes; raw layout, 8 channel streams).
//   nes_vrc6_thor:   the same rows rearranged into the Thor Tracker layout for the VRC6 player; every row's bytes
//                    are FamiStudio's.
//   nes_vrc6_moved:  nes_vrc6_thor with every block at another address (same directory indices).
//   nes_vrc6_edited: nes_vrc6_thor with order 2 VRC6 pulse 1 row 8 E4 -> G4 and the VPulse volume sustain
//                    12,12 -> 8,8.
#ifndef NES_VRC6_SONG_H_
#define NES_VRC6_SONG_H_
#include <stdint.h>

#define VRC6_TEST_ROWS {vrows}
#define VRC6_TEST_ORDERS {vorders}
#define VRC6_TEST_LOOP {vloop}
#define VRC6_TEST_INSTRUMENTS {len(vthor['instruments'])}
#define VRC6_TEST_TEMPO_NTSC {u16(vrc6_song, 5 + 2 * PLAYERS['vrc6']['channels'] + 2)}
{vinstrument_defines}

/* Speed in effect at each row of the first pass, read from vrc6_test.txt (not from the export). */
static const uint8_t nes_vrc6_speed[VRC6_TEST_ORDERS][VRC6_TEST_ROWS] = {{
{vspeed_rows}
}};

{c_array("nes_vrc6_raw", vrc6_song)}
{c_array("nes_vrc6_thor", vbase)}
{c_array("nes_vrc6_moved", vmoved)}
{c_array("nes_vrc6_edited", vedited)}
#endif /* NES_VRC6_SONG_H_ */
""")

    msym_lines = "\n".join(f"#define NES_MMC5_{s.upper()} 0x{msyms[s]:04X}" for s in SYMBOLS + MMC5_SYMBOLS)
    mregion_size = REGION_END - mmc5_base
    mimage_size = HEADER_SIZE + DIRECTORY_SIZE + mregion_size
    OUT_MMC5_PLAYER.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
// FamiStudio sound engine {FAMISTUDIO_TAG} (github.com/BleuBleu/FamiStudio commit {FAMISTUDIO_COMMIT}),
// famistudio_ca65.s sha256 {PINNED[ENGINE_DIR / 'famistudio_ca65.s']}, unmodified, configured (FamiTracker tempo)
// and wrapped by tools/audio/nes/nes_player.s like nes_player_image.h, assembled with FAMISTUDIO_EXP_MMC5 = 1
// (Nintendo MMC5: two pulses after the 2A03's five channels, on the 2A03 note table; no MMC5 PCM, "extra DPCM not
// supported", famistudio_ca65.s line 107); assembled with {CC65_VERSION}.
// Engine licence: "Copying and distribution of this file, with or without modification, are permitted in any
// medium without royalty provided the copyright notice and this notice are preserved" (c) 2019-2026 Mathieu Gauthier.
// image sha256 {mplayer_sha}
#ifndef NES_MMC5_PLAYER_IMAGE_H_
#define NES_MMC5_PLAYER_IMAGE_H_
#include <stdint.h>

#define NES_MMC5_PLAYER_BASE 0x{PLAYER_BASE:04X}
#define NES_MMC5_PLAYER_SIZE 0x{len(mplayer):04X}
#define NES_MMC5_PLAYER_ID 0x{mplayer_id:08X}u /* first 4 bytes of the image sha256, little-endian */
#define NES_MMC5_PLAYER_INIT 0x{ENTRY['nes_player_init']:04X} /* A = song index */
#define NES_MMC5_PLAYER_PLAY 0x{ENTRY['nes_player_play']:04X} /* one 60 Hz engine frame */
#define NES_MMC5_PLAYER_IDLE 0x{ENTRY['nes_player_idle']:04X} /* return address of host-called routines */
#define NES_MMC5_PLAYER_TRAP 0x{ENTRY['nes_player_trap']:04X} /* BRK/IRQ/NMI vector target: a fault */
#define NES_MMC5_SONG_BASE 0x{mmc5_base:04X} /* song data (FamiStudio format) from here upward: the page after the player (measured) */
#define NES_MMC5_ZP_SIZE 0x{mzp[1]:04X}
#define NES_MMC5_RAM_BASE 0x{mbss[0]:04X}
#define NES_MMC5_RAM_SIZE 0x{mbss[1]:04X}
#define NES_MMC5_NUM_ENVELOPES {PLAYERS['mmc5']['envelopes']}
#define NES_MMC5_NUM_PITCH_ENVELOPES {PLAYERS['mmc5']['pitch_envelopes']}
#define NES_MMC5_CHANNELS {PLAYERS['mmc5']['channels']} /* 2A03 pulse 1, pulse 2, triangle, noise, DPCM, MMC5 pulse 1, pulse 2 */

/* Image layout: nes_player_image.h's (header, envelope directory, limits, samples from NES_SAMPLE_BASE, region end
 * NES_REGION_END) with the region = CPU memory NES_MMC5_SONG_BASE..NES_REGION_END. */
#define NES_MMC5_IMAGE_REGION_SIZE {mregion_size}
#define NES_MMC5_IMAGE_SIZE {mimage_size}

{msym_lines}

static const uint8_t nes_mmc5_player_code[NES_MMC5_PLAYER_SIZE] = {{
{c_bytes(mplayer)}
}};

#endif /* NES_MMC5_PLAYER_IMAGE_H_ */
""")

    OUT_MMC5_KOTLIN.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
package org.thortracker.backend

/**
 * The NES + Nintendo MMC5 preview player: the [NesPlayerImage] player assembled with `FAMISTUDIO_EXP_MMC5 = 1`
 * (FamiStudio sound engine {FAMISTUDIO_TAG}, FamiTracker tempo, the same NSF-style entry table) by
 * `tools/audio/prepare_nes_player.py`. Images use [NesPlayerImage]'s layout (header, envelope directory, limits,
 * samples, kinds) with this player's [PLAYER_ID], [SONG_BASE] and [CHANNELS] channel streams in the song header
 * (`docs/AUDIO_ENGINE.md`, "MMC5"). The MMC5 pulses play the 2A03 note table and 2A03 instruments; the engine has no
 * MMC5 PCM. The same bytes play in the app (native `app/src/main/cpp/generated/nes_mmc5_player_image.h`) and form an
 * `.nsf` (expansion byte MMC5) with the song region.
 * Image sha256 `{mplayer_sha}`.
 * Engine licence: all-permissive, notice in NOTICE.txt.
 */
object NesMmc5PlayerImage {{
    /** Load, init (A = song index) and play addresses; the player starts at [LOAD]. */
    const val LOAD = 0x{PLAYER_BASE:04X}
    const val INIT = 0x{ENTRY['nes_player_init']:04X}
    const val PLAY = 0x{ENTRY['nes_player_play']:04X}

    /**
     * Region: song data from [SONG_BASE] upward and the samples share CPU memory [SONG_BASE] until [REGION_END]
     * (exclusive; vectors follow), [REGION_SIZE] bytes, as for [NesPlayerImage] but from the first page after this
     * larger player.
     */
    const val SONG_BASE = 0x{mmc5_base:04X}
    const val REGION_END = 0x{REGION_END:04X}
    const val REGION_SIZE = {mregion_size}

    /** Header bytes 4..7 (little-endian): an image only plays on the player build it was lowered for. */
    const val PLAYER_ID = 0x{mplayer_id:08X}.toInt()

    /** Bytes of a lowered image: header + envelope directory + region (address a at region offset a − [SONG_BASE]). */
    const val IMAGE_SIZE = {mimage_size}

    /**
     * Channel streams in the song header, in engine order: 2A03 pulse 1, pulse 2, triangle, noise, DPCM, then MMC5
     * pulse 1, pulse 2.
     */
    const val CHANNELS = {PLAYERS['mmc5']['channels']}

    /** The player image ({len(mplayer)} bytes at [LOAD]); a fresh copy per call. */
    val BYTES: ByteArray get() = ByteArray(HEX.length / 2) {{ HEX.substring(2 * it, 2 * it + 2).toInt(16).toByte() }}

    private const val HEX =
{kotlin_hex(mplayer)}
}}
""")

    minstrument_defines = "\n".join(f"#define MMC5_TEST_INSTRUMENT_{n.upper()} {int(i, 16)}" for i, n in mnames)
    mspeed_rows = ",\n".join("    {" + ", ".join(str(s) for s in line) + "}" for line in mspeeds)
    OUT_MMC5_SONG.write_text(f"""// GENERATED by tools/audio/prepare_nes_player.py -- do not edit.
// MMC5 test song tools/audio/nes/mmc5_test.txt (sha256 {sha256(MMC5_SONG_TXT.read_bytes())}, FamiTracker tempo),
// as exported by FamiStudio {FAMISTUDIO_TAG} itself (tools/audio/nes/mmc5_test_ca65.s, sha256 {sha256(MMC5_SONG_CA65.read_bytes())}).
//   nes_mmc5_raw:    that export linked at NES_MMC5_SONG_BASE (song region bytes; raw layout, 7 channel streams).
//   nes_mmc5_thor:   the same rows rearranged into the Thor Tracker layout for the MMC5 player; every row's bytes
//                    are FamiStudio's.
//   nes_mmc5_moved:  nes_mmc5_thor with every block at another address (same directory indices).
//   nes_mmc5_edited: nes_mmc5_thor with order 2 MMC5 pulse 1 row 8 E4 -> G4 and the MPulse volume sustain
//                    12,12 -> 8,8.
#ifndef NES_MMC5_SONG_H_
#define NES_MMC5_SONG_H_
#include <stdint.h>

#define MMC5_TEST_ROWS {mrows}
#define MMC5_TEST_ORDERS {morders}
#define MMC5_TEST_LOOP {mloop}
#define MMC5_TEST_INSTRUMENTS {len(mthor['instruments'])}
#define MMC5_TEST_TEMPO_NTSC {u16(mmc5_song, 5 + 2 * PLAYERS['mmc5']['channels'] + 2)}
{minstrument_defines}

/* Speed in effect at each row of the first pass, read from mmc5_test.txt (not from the export). */
static const uint8_t nes_mmc5_speed[MMC5_TEST_ORDERS][MMC5_TEST_ROWS] = {{
{mspeed_rows}
}};

{c_array("nes_mmc5_raw", mmc5_song)}
{c_array("nes_mmc5_thor", mbase)}
{c_array("nes_mmc5_moved", mmoved)}
{c_array("nes_mmc5_edited", medited)}
#endif /* NES_MMC5_SONG_H_ */
""")
    print(f"player: {len(player)} bytes at ${PLAYER_BASE:04X}, sha256 {player_sha}, id 0x{player_id:08X}")
    print(f"engine RAM: zp ${zp[0]:02X}+{zp[1]}, bss ${bss[0]:04X}+{bss[1]} (ends ${bss[0] + bss[1]:04X})")
    print(f"test song: {len(song)} bytes raw; Thor layout region {len(base) - HEADER_SIZE - DIRECTORY_SIZE} bytes, "
          f"{len(thor['instruments'])} instruments, {len(thor['envelopes'])} envelopes")
    print(f"DPCM test song: {len(dpcm_song)} bytes + {len(dmc)} sample bytes, {len(dpcm_list) // 4} mappings, "
          f"{len(events)} DMC events in the first pass")
    print(f"VRC6 player: {len(vplayer)} bytes at ${PLAYER_BASE:04X} (song base ${VRC6_SONG_BASE:04X}), sha256 {vplayer_sha}, "
          f"id 0x{vplayer_id:08X}; engine RAM zp ${vzp[0]:02X}+{vzp[1]}, bss ${vbss[0]:04X}+{vbss[1]}")
    print(f"VRC6 test song: {len(vrc6_song)} bytes raw, {len(vthor['instruments'])} instruments, {len(vthor['envelopes'])} envelopes")
    print(f"MMC5 player: {len(mplayer)} bytes at ${PLAYER_BASE:04X} (song base ${mmc5_base:04X}), sha256 {mplayer_sha}, "
          f"id 0x{mplayer_id:08X}; engine RAM zp ${mzp[0]:02X}+{mzp[1]}, bss ${mbss[0]:04X}+{mbss[1]}")
    print(f"MMC5 test song: {len(mmc5_song)} bytes raw, {len(mthor['instruments'])} instruments, {len(mthor['envelopes'])} envelopes")
    print("wrote " + ", ".join(str(p.relative_to(ROOT)) for p in
                               (OUT_PLAYER, OUT_KOTLIN, OUT_SONG, OUT_DPCM, OUT_VRC6_PLAYER, OUT_VRC6_KOTLIN, OUT_VRC6_SONG,
                                OUT_MMC5_PLAYER, OUT_MMC5_KOTLIN, OUT_MMC5_SONG)))


if __name__ == "__main__":
    main()
