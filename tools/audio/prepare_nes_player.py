#!/usr/bin/env python3
"""Prepare the embedded NES player image (FamiStudio sound engine 4.5.3) and the host-test songs.

Run once by a developer when the pinned engine, the player configuration or the image layout
changes; the outputs are committed, so app/Deck/CI builds need no 6502 assembler:

  app/src/main/cpp/generated/nes_player_image.h   player bytes at $8000, entry/engine addresses, image layout
  core/src/commonMain/kotlin/org/thortracker/backend/NesPlayerImage.kt
                                                  the same player bytes and layout numbers for Kotlin
                                                  (lowering, .nsf export)
  app/src/test/cpp/nes_ft_song.h                  host-test fixtures (below)
  app/src/test/cpp/nes_dpcm_song.h                DPCM host-test fixtures (below)

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
  6. Write the four outputs. Two runs produce identical files.

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

FAMISTUDIO_TAG = "4.5.3"
FAMISTUDIO_COMMIT = "c09dca64788606c09fcb5e70db28b9dd1f34be78"
PINNED = {
    ENGINE_DIR / "famistudio_ca65.s": "e126a8f32b6c879d6ce29b39bcedf2b23c5e03419aec80357f3497739dbd71fe",
    ENGINE_DIR / "NoteTables/famistudio_note_table_lsb.bin": "b917b0ebbe96945bbf6237bf2c59c9d1cf35f64e4e8183bece0f45d32486ee41",
    ENGINE_DIR / "NoteTables/famistudio_note_table_msb.bin": "b52e4924c607af3369e3de8da5ea9001dc013e67b515d3b3b09dab17601169fb",
}
TEST_SONG_TXT = NES_DIR / "ft_test.txt"
TEST_SONG_CA65 = NES_DIR / "ft_test_ca65.s"
DPCM_SONG_TXT = NES_DIR / "dpcm_test.txt"
DPCM_SONG_CA65 = NES_DIR / "dpcm_test_ca65.s"
DPCM_SONG_DMC = NES_DIR / "dpcm_test_ca65.dmc"
CC65_VERSION = "ca65 V2.18 - Ubuntu 2.19-1"

PLAYER_BASE = 0x8000
SONG_BASE = 0x8C00  # the page after the player (checked): FamiStudio data, below the samples
SONG_END = 0xC000
SAMPLE_BASE = 0xC000  # DPCM samples ($4012 addresses $C000 + 64 n), up to the vectors
SAMPLE_END = 0xFFFA
SAMPLE_ALIGN = 64
MAX_SAMPLE_BYTES = 16 * 255 + 1
MAX_DPCM_MAPPINGS = 63  # FamiStudio without FAMISTUDIO_USE_DPCM_EXTENDED_RANGE
DPCM_PTR = (SAMPLE_BASE & 0x3FFF) >> 6  # FamiStudio's FAMISTUDIO_DPCM_PTR for samples at $C000
AUDITION_RAM = 0x07C0  # one-note audition streams written by the native machine; engine RAM must end below
ENTRY = {"nes_player_init": 0x8000, "nes_player_play": 0x8003, "nes_player_idle": 0x8006, "nes_player_trap": 0x8009}

# Image layout (docs/AUDIO_ENGINE.md): header, envelope directory, region = CPU memory SONG_BASE..SAMPLE_END
# (song data from SONG_BASE, sample bytes from SAMPLE_BASE).
HEADER_SIZE = 32
HEADER_DPCM_COUNT = 15  # u8: entries in the FamiStudio DPCM list (Thor layout)
HEADER_SAMPLE_SIZE = 22  # u16: sample bytes used from SAMPLE_BASE
MAX_ENVELOPES = 512
DIRECTORY_SIZE = 4 * MAX_ENVELOPES
REGION_SIZE = SAMPLE_END - SONG_BASE
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

def parse_famistudio_ca65(text: str, base: int, name: str = "ft_test_ca65.s"):
    """Assembles FamiStudio's ca65 music export (labels, .byte, .word, .lobyte/.hibyte, DPCM list entries
    `$nn+.lobyte(FAMISTUDIO_DPCM_PTR)` for samples at SAMPLE_BASE) at `base`.
    Returns (bytes, {label: address}). Only the constructs FamiStudio emits for 2A03 songs."""
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

def read_entry(data: bytes, pos: int):
    """One row entry of a channel stream at region offset `pos`: prefix opcodes then one terminator.
    Returns (end offset, rows covered, offsets of envelope pointer operands within data)."""
    ptrs = []
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
            if b not in OPCODES or b in (0x41, 0x42, 0x44):
                sys.exit(f"unexpected opcode ${b:02X} inside a row at ${SONG_BASE + pos:04X}")
            size, ends = OPCODES[b]
            if b in (0x49, 0x4B):
                ptrs.append((pos + 1, KIND_PITCH if b == 0x49 else KIND_ARPEGGIO))
            pos += 1 + size
            if ends:
                return pos, 1, ptrs


def flatten_channel(data: bytes, start: int, loop: int):
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
            pos = u16(data, pos + 2) - SONG_BASE
            continue
        if b == 0x42:
            if ret is not None:
                sys.exit("loop inside a reference")
            if u16(data, pos + 1) - SONG_BASE != loop:
                sys.exit("loop target is not the channel's loop label")
            return entries, loop_index
        end, rows, ptrs = read_entry(data, pos)
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
                     sample_edits=None) -> bytes:
    """Lays `song` (instruments, envelopes, per-order channel entries, DPCM list and samples) out as a Thor
    Tracker image. arrangement "plain": table, DPCM list, envelopes, order streams, patterns, samples exactly
    where FamiStudio's .dmc has them; "moved": patterns (reversed), envelopes, order streams (shuffled), DPCM
    list, table, with gaps, and the samples in reverse order 64 bytes apart, so every address differs.
    Directory indices and mapping indices are identical in both (envelopes stay ascending by index, as the
    layout requires). edits: [(order, channel, entry index, old byte, new byte)] patched into that row's
    bytes; env_edits: {directory index: (old bytes, new bytes)}; sample_edits: {$4012 offset of a sample in
    the .dmc: (byte index, new byte)}."""
    envs = song["envelopes"]  # directory index -> (kind, bytes)
    instruments = song["instruments"]  # [(vol, arp, duty, pitch) directory indices]
    orders = song["orders"][:order_count]
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
    stream_size = 4 * len(orders) + 3
    names = (["header", "table", "dpcm"] + [f"env{i}" for i in sorted(envs)] + [f"ch{c}" for c in range(5)]
             + [f"pat{i}" for i in range(len(unique))])
    size_of = {"header": 19, "table": 8 * len(instruments), "dpcm": len(dpcm), **{f"env{i}": len(envs[i][1]) for i in envs},
               **{f"ch{c}": stream_size for c in range(5)}, **sizes}
    if arrangement == "moved":
        names = (["header"] + [f"pat{i}" for i in reversed(range(len(unique)))]
                 + [f"env{i}" for i in sorted(envs)] + [f"ch{c}" for c in (3, 1, 4, 0, 2)] + ["dpcm", "table"])
    pc = SONG_BASE
    for i, name in enumerate(names):
        addr[name] = pc
        pc += size_of[name] + (3 + i % 5 if arrangement == "moved" and name != "header" else 0)
    region = bytearray(pc - SONG_BASE)

    def put(at, data):
        region[at - SONG_BASE:at - SONG_BASE + len(data)] = data

    def word(v):
        return bytes((v & 0xFF, v >> 8))

    put(addr["header"], bytes([1]) + word(addr["table"]) + word(addr["dpcm"] - 4 if dpcm else SONG_BASE)
        + b"".join(word(addr[f"ch{c}"]) for c in range(5)) + song["tempo_words"])
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
    for c in range(5):
        stream = bytearray()
        for o in range(len(orders)):
            key = pattern_keys[o][c]
            stream += bytes([0x41, len(key)]) + word(addr[f"pat{unique.index(key)}"])
        stream += bytes([0x42]) + word(addr[f"ch{c}"] + 4 * loop)
        put(addr[f"ch{c}"], stream)
    for o, c, e, old, new in edits:
        key = pattern_keys[o][c]
        off = addr[f"pat{unique.index(key)}"] + sum(len(b) for b, _ in key[:e]) - SONG_BASE
        pos = key[e][0].rindex(old)
        if region[off + pos] != old:
            sys.exit("edit target mismatch")
        region[off + pos] = new
    for i, (old, new) in (env_edits or {}).items():
        at = addr[f"env{i}"] - SONG_BASE
        if bytes(region[at:at + len(old)]) != old or len(new) != len(old):
            sys.exit("envelope edit target mismatch")
        region[at:at + len(new)] = new
    # Samples: each list entry's (offset, length) range of the .dmc; ranges of different mappings coincide.
    dmc = bytearray(song["dmc"])
    for off, (i, b) in (sample_edits or {}).items():
        dmc[64 * off + i] = b
    ranges = sorted({(dpcm[k], 16 * dpcm[k + 1] + 1) for k in range(0, len(dpcm), 4)})
    if arrangement == "plain":
        samples, where = dmc, {off: off for off, _ in ranges}
    else:
        samples, where, at = bytearray(), {}, 1
        for off, length in reversed(ranges):
            where[off] = at
            samples[len(samples):] = bytes(64 * at - len(samples)) + dmc[64 * off:64 * off + length]
            at = (len(samples) + 63) // 64 + 1
    dpcm_moved = bytearray(dpcm)
    for k in range(0, len(dpcm), 4):
        dpcm_moved[k] = where[dpcm[k]]
    put(addr["dpcm"], dpcm_moved)
    if samples:
        region[len(region):] = bytes(SAMPLE_BASE - SONG_BASE - len(region)) + samples

    header = bytearray(HEADER_SIZE)
    header[0:4] = b"TTNS"
    header[4:8] = song["player_id"].to_bytes(4, "little")
    header[8:10] = word(SONG_BASE)
    header[10:12] = word(pc - SONG_BASE)
    header[12] = 0
    header[13] = LAYOUT_THOR
    header[14] = len(instruments)
    header[16:18] = word(max(envs) + 1)
    header[HEADER_DPCM_COUNT] = len(dpcm) // 4
    header[HEADER_SAMPLE_SIZE:HEADER_SAMPLE_SIZE + 2] = word(len(samples))
    directory = bytearray(DIRECTORY_SIZE)
    for i, (kind, data) in envs.items():
        directory[4 * i:4 * i + 4] = word(addr[f"env{i}"]) + bytes((len(data), kind))
    if pc > SONG_END or SAMPLE_BASE + len(samples) > SAMPLE_END:
        sys.exit("layout fixture does not fit the song or sample region")
    return bytes(header + directory + region)


def thor_song_from_export(region: bytes, labels_: dict, txt: str, player_id: int, dmc: bytes = b""):
    """FamiStudio's export of a test song (its .dmc samples `dmc`) re-expressed as the parts build_thor_image
    lays out."""
    song_attrs = re.search(r'^\tSong [^\n]*', txt, re.M).group(0)
    rows = int(re.search(r'PatternLength="(\d+)"', song_attrs).group(1))
    orders = int(re.search(r' Length="(\d+)"', song_attrs).group(1))
    loop = int(re.search(r'LoopPoint="(-?\d+)"', song_attrs).group(1))
    if region[0] != 1:
        sys.exit("test song: one song expected")
    table = u16(region, 1) - SONG_BASE
    env_labels = sorted((a - SONG_BASE, n) for n, a in labels_.items() if re.fullmatch(r"@env\d+", n))
    ends = [a for a, _ in env_labels[1:]] + [labels_["@samples"] - SONG_BASE]
    env_bytes = {SONG_BASE + a: region[a:e] for (a, _), e in zip(env_labels, ends)}
    # DPCM list: from @samples (the header points 4 bytes before it) to the next label.
    samples_at = labels_["@samples"]
    if u16(region, 3) != samples_at - 4:
        sys.exit("DPCM list pointer is not @samples-4")
    list_end = min(a for n, a in labels_.items() if a >= samples_at and n != "@samples")
    dpcm = bytes(region[samples_at - SONG_BASE:list_end - SONG_BASE])
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
    for c in range(5):
        start = u16(region, 5 + 2 * c) - SONG_BASE
        loop_name = f"@song0ch{c}loop"
        if loop_name not in labels_:  # FamiStudio shares identical channel streams under one label
            twin = next(k for k in range(5) if labels_.get(f"@song0ch{k}") == start + SONG_BASE and f"@song0ch{k}loop" in labels_)
            loop_name = f"@song0ch{twin}loop"
        loop_at = labels_[loop_name] - SONG_BASE
        entries, loop_index = flatten_channel(region, start, loop_at)
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
    return {
        "player_id": player_id,
        "tempo_words": bytes(region[15:19]),
        "instruments": instruments,
        "envelopes": envs,
        "effect_env_index": effect_index,
        "orders": [[per_channel[c][o] for c in range(5)] for o in range(orders)],
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


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--famistudio", type=Path,
                    help="FamiStudio 4.5.3 Linux release dir: re-export ft_test.txt and dpcm_test.txt and compare")
    args = ap.parse_args()

    for path, want in PINNED.items():
        got = sha256(path.read_bytes())
        if got != want:
            sys.exit(f"{path.relative_to(ROOT)}: sha256 {got} != pinned {want}")
    if args.famistudio:
        with tempfile.TemporaryDirectory(dir="/tmp") as tmp:
            for txt, ca65, dmc in ((TEST_SONG_TXT, TEST_SONG_CA65, None), (DPCM_SONG_TXT, DPCM_SONG_CA65, DPCM_SONG_DMC)):
                fresh = Path(tmp) / ca65.name
                famistudio_export(args.famistudio.resolve(), txt, fresh)
                if fresh.read_bytes() != ca65.read_bytes():
                    sys.exit(f"FamiStudio's export of {txt.name} differs from {ca65.relative_to(ROOT)}")
                if dmc and fresh.with_suffix(".dmc").read_bytes() != dmc.read_bytes():
                    sys.exit(f"FamiStudio's samples of {txt.name} differ from {dmc.relative_to(ROOT)}")
                print(f"FamiStudio re-export of {txt.name}: identical")

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

        (t / "song.s").write_text('.segment "SONG"\n.include "ft_test_ca65.s"\n')
        run(prefix, ["ca65", "-I", str(NES_DIR), str(t / "song.s"), "-o", str(t / "song.o")], t)
        run(prefix, ["ld65", "-C", str(NES_DIR / "nes_song.cfg"), "-o", str(t / "song.bin"), str(t / "song.o")], t)
        song = (t / "song.bin").read_bytes()
        (t / "dpcm.s").write_text(f'.segment "SONG"\nFAMISTUDIO_DPCM_PTR = ${DPCM_PTR:02X}\n.include "dpcm_test_ca65.s"\n')
        run(prefix, ["ca65", "-I", str(NES_DIR), str(t / "dpcm.s"), "-o", str(t / "dpcm.o")], t)
        run(prefix, ["ld65", "-C", str(NES_DIR / "nes_song.cfg"), "-o", str(t / "dpcm.bin"), str(t / "dpcm.o")], t)
        dpcm_song = (t / "dpcm.bin").read_bytes()

    for name, addr in ENTRY.items():
        if syms.get(name) != addr:
            sys.exit(f"{name} at {syms.get(name)!r}, expected ${addr:04X}")
    if PLAYER_BASE + len(player) > SONG_BASE:
        sys.exit(f"player ends at ${PLAYER_BASE + len(player):04X}, past NES_SONG_BASE ${SONG_BASE:04X}")
    zp, bss = segs["ZEROPAGE"], segs["BSS"]
    if zp[0] != 0 or zp[1] > 0x100 or bss[0] != 0x200 or bss[0] + bss[1] > AUDITION_RAM:
        sys.exit(f"unexpected RAM layout {segs} (engine RAM must end below ${AUDITION_RAM:04X})")
    missing = [s for s in SYMBOLS if s not in syms]
    if missing:
        sys.exit(f"missing engine symbols {missing}")
    num_envelopes = syms["famistudio_env_repeat"] - syms["famistudio_env_value"]
    num_pitch = syms["famistudio_pitch_env_value_hi"] - syms["famistudio_pitch_env_value_lo"]
    channels = syms["famistudio_chn_ptr_hi"] - syms["famistudio_chn_ptr_lo"]
    if (num_envelopes, num_pitch, channels) != (11, 3, 5):
        sys.exit(f"unexpected engine table sizes {num_envelopes} envelopes, {num_pitch} pitch, {channels} channels")
    rom = lambda a: player[a - PLAYER_BASE]  # noqa: E731
    channel_env = [rom(syms["famistudio_channel_env"] + c) for c in range(5)]
    channel_pitch = [rom(syms["famistudio_channel_to_pitch_env"] + c) for c in range(5)]
    if channel_env != [0, 3, 6, 8, 0xFF] or channel_pitch != [0, 1, 2, 0xFF, 0xFF]:
        sys.exit(f"unexpected channel tables {channel_env} {channel_pitch}")

    # Test song: the ld65 link and the parser must agree byte for byte.
    ca65_text = TEST_SONG_CA65.read_text()
    txt = TEST_SONG_TXT.read_text()
    parsed, song_labels = parse_famistudio_ca65(ca65_text, SONG_BASE)
    if parsed != song:
        sys.exit("parser output differs from the ld65 link of ft_test_ca65.s")
    if len(song) > SONG_END - SONG_BASE or song[0] != 1:
        sys.exit("test song: one song that fits the region expected")
    if "FAMISTUDIO_USE_FAMITRACKER_TEMPO" not in ca65_text:
        sys.exit("test song export is not FamiTracker tempo")

    player_sha = sha256(player)
    player_id = int.from_bytes(bytes.fromhex(player_sha)[:4], "little")
    thor, rows, orders, loop = thor_song_from_export(song, song_labels, txt, player_id)
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

    # DPCM test song: the same checks, plus its samples (.dmc at SAMPLE_BASE) and the DMC writes the text asks for.
    dpcm_text = DPCM_SONG_CA65.read_text()
    dpcm_txt = DPCM_SONG_TXT.read_text()
    dmc = DPCM_SONG_DMC.read_bytes()
    parsed, dpcm_labels = parse_famistudio_ca65(dpcm_text, SONG_BASE, DPCM_SONG_CA65.name)
    if parsed != dpcm_song:
        sys.exit("parser output differs from the ld65 link of dpcm_test_ca65.s")
    if SAMPLE_BASE + len(dmc) > SAMPLE_END or "FAMISTUDIO_USE_DELTA_COUNTER" not in dpcm_text:
        sys.exit("DPCM test song: samples that fit and the delta counter expected")
    dthor, drows, dorders, dloop = thor_song_from_export(dpcm_song, dpcm_labels, dpcm_txt, player_id, dmc)
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
#define NES_SONG_BASE 0x{SONG_BASE:04X} /* song data (FamiStudio format) from here ... */
#define NES_SONG_END 0x{SONG_END:04X} /* ... up to here (exclusive) */
#define NES_SAMPLE_BASE 0x{SAMPLE_BASE:04X} /* DPCM samples ($4012 = (address - $C000) / 64) from here ... */
#define NES_SAMPLE_END 0x{SAMPLE_END:04X} /* ... up to the vectors (exclusive) */
#define NES_ZP_SIZE 0x{zp[1]:04X}
#define NES_RAM_BASE 0x{bss[0]:04X}
#define NES_RAM_SIZE 0x{bss[1]:04X}
#define NES_AUDITION_RAM 0x{AUDITION_RAM:04X} /* free RAM up to $07FF: audition streams */
#define NES_NUM_ENVELOPES {num_envelopes}
#define NES_NUM_PITCH_ENVELOPES {num_pitch}

/* Image layout (docs/AUDIO_ENGINE.md, "Image layout"): header, envelope directory, region = CPU memory
 * NES_SONG_BASE..NES_SAMPLE_END (song data from NES_SONG_BASE, samples from NES_SAMPLE_BASE). */
#define NES_IMAGE_HEADER_SIZE {HEADER_SIZE}
#define NES_IMAGE_HEADER_DPCM_COUNT {HEADER_DPCM_COUNT} /* u8: DPCM list entries (Thor layout) */
#define NES_IMAGE_HEADER_SAMPLE_SIZE {HEADER_SAMPLE_SIZE} /* u16: sample bytes used from NES_SAMPLE_BASE */
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

    /** Song region: FamiStudio data placed at [SONG_BASE], at most up to [SONG_END] (exclusive; the samples follow). */
    const val SONG_BASE = 0x{SONG_BASE:04X}
    const val SONG_END = 0x{SONG_END:04X}

    /**
     * Sample region: DPCM samples from [SAMPLE_BASE] up to [SAMPLE_END] (exclusive; vectors follow), each starting on a
     * [SAMPLE_ALIGN] boundary (`$4012` = (address − [SAMPLE_BASE]) / 64) and [MAX_SAMPLE_BYTES] long at most
     * (`$4013` = (bytes − 1) / 16; the hardware plays 16 n + 1 bytes).
     */
    const val SAMPLE_BASE = 0x{SAMPLE_BASE:04X}
    const val SAMPLE_END = 0x{SAMPLE_END:04X}
    const val SAMPLE_ALIGN = {SAMPLE_ALIGN}
    const val MAX_SAMPLE_BYTES = {MAX_SAMPLE_BYTES}

    /** Entries of the FamiStudio DPCM list (without FamiStudio's extended DPCM range): stream note bytes 1..63. */
    const val MAX_DPCM_MAPPINGS = {MAX_DPCM_MAPPINGS}

    /** Header bytes 4..7 (little-endian): an image only plays on the player build it was lowered for. */
    const val PLAYER_ID = 0x{player_id:08X}.toInt()

    const val HEADER_SIZE = {HEADER_SIZE}

    /** Header byte: entries in the DPCM list. */
    const val HEADER_DPCM_COUNT = {HEADER_DPCM_COUNT}

    /** Header u16 (little-endian): sample bytes used from [SAMPLE_BASE] (zero after). */
    const val HEADER_SAMPLE_SIZE = {HEADER_SAMPLE_SIZE}

    const val MAX_ENVELOPES = {MAX_ENVELOPES}
    const val DIRECTORY_SIZE = {DIRECTORY_SIZE}
    const val MAX_INSTRUMENTS = {MAX_INSTRUMENTS}
    const val MAX_ORDERS = {MAX_ORDERS}
    const val MAX_ROWS = {MAX_ROWS}

    /**
     * Bytes of a lowered image: header + envelope directory + region, the CPU memory [SONG_BASE] until [SAMPLE_END]
     * (song data at region offset 0, samples at [SAMPLE_BASE] − [SONG_BASE]).
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

    def c_array(name, data):
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
#endif /* NES_FT_SONG_H_ */
""")

    region_at = HEADER_SIZE + DIRECTORY_SIZE

    def c_split(name, image):
        """An image as two arrays: header + directory + song data, and the sample bytes (at NES_SAMPLE_BASE)."""
        size = u16(image, 10)
        return c_array(name, image[:region_at + size]) + c_array(f"{name}_samples", image[region_at + SAMPLE_BASE - SONG_BASE:])

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
//   nes_dpcm_moved:  every block and every sample at another address (same directory and mapping indices).
//   nes_dpcm_edited: nes_dpcm_thor with byte NES_DPCM_EDITED_BYTE of the Kick sample inverted.
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
{c_array("nes_dpcm_dmc", dmc)}
{c_split("nes_dpcm_thor", dbase)}
{c_split("nes_dpcm_moved", dmoved)}
{c_split("nes_dpcm_edited", dedited)}
#endif /* NES_DPCM_SONG_H_ */
""")
    print(f"player: {len(player)} bytes at ${PLAYER_BASE:04X}, sha256 {player_sha}, id 0x{player_id:08X}")
    print(f"engine RAM: zp ${zp[0]:02X}+{zp[1]}, bss ${bss[0]:04X}+{bss[1]} (ends ${bss[0] + bss[1]:04X})")
    print(f"test song: {len(song)} bytes raw; Thor layout region {len(base) - HEADER_SIZE - DIRECTORY_SIZE} bytes, "
          f"{len(thor['instruments'])} instruments, {len(thor['envelopes'])} envelopes")
    print(f"DPCM test song: {len(dpcm_song)} bytes + {len(dmc)} sample bytes, {len(dpcm_list) // 4} mappings, "
          f"{len(events)} DMC events in the first pass")
    print(f"wrote {OUT_PLAYER.relative_to(ROOT)}, {OUT_KOTLIN.relative_to(ROOT)}, {OUT_SONG.relative_to(ROOT)} "
          f"and {OUT_DPCM.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
