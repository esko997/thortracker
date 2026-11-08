#!/usr/bin/env python3
"""Prepare the embedded C64 SID player image (GoatTracker 2.77 playroutine) and the SID host-test song.

Run once by a developer when the pinned playroutine, its configuration or the image layout changes; the
outputs are committed, so app/Deck/CI builds need no 6502 assembler:

  app/src/main/cpp/generated/sid_player_image.h   player bytes at $1000, parameter patch addresses,
                                                  player variable addresses, image layout
  core/src/commonMain/kotlin/org/thortracker/backend/SidPlayerImage.kt
                                                  the same player bytes and layout/export numbers for Kotlin
  app/src/test/cpp/sid_gt_song.h                  host-test fixtures (below)
  tools/audio/sid/gt_test.sng                     the test song as a GoatTracker 2 song file
  tools/audio/sid/gt_test.sid                     GoatTracker's own packed PSID of it (gt2reloc)

Steps (all checked):
  1. Check third_party/goattracker-2.77/player.s (unmodified src/player.s of the GoatTracker 2.77
     release, identical to the copy embedded in its packer) against its pinned sha256.
  2. Assemble player.s with the assembler it is written for (Magnus Lind's, as embedded in
     GoatTracker: gtasm, built by tools/audio/fetch_goattracker.sh) at $1000 with the packer's
     "Disable optimization" configuration (every feature present; unbuffered writes, no sound effects,
     no ghost registers, no author info, pulse and realtime-command skipping on as in GoatTracker's
     defaults), one subtune, three channels, and the song data labels as equates at the fixed layout
     addresses (gt_song.LABELS) instead of data appended after the player.
  3. The five song-dependent assembly constants (FIRSTNOHRINSTR, FIRSTLEGATOINSTR, ADPARAM, SRPARAM,
     DEFAULTTEMPO) are immediate operands: assembling with two value sets must differ in exactly five
     bytes, one per constant; those addresses are where the machine writes each image's values.
     Player variables and code labels are read back from words appended after the player.
  4. The low-byte jump tables (tick-0 commands, continuous effects) must point into the page of the
     instructions that use them, and the player must end below the image region.
  5. Test song (tools/audio/sid/gt_test_song.py) written as gt_test.sng and packed by GoatTracker's own
     packer: `gt2reloc gt_test.sng gt_test.sid -I0 -J1` (PSID at $1000, optimizations disabled;
     gt2reloc's -I case falls through into -J and switches full buffering on, -J1 switches it off
     again). The packed PSID must equal the committed gt_test.sid (written on the first run).
  6. gt_song.lower() packs the song the same way into the Thor Tracker layout. Proof that it is the
     packer's data: the player source + the lowering's data with greloc.c's labels and order, assembled
     by gtasm, is byte-identical to gt2reloc's PSID payload.
  7. py65 (an independent 6502 emulator) runs gt2reloc's PSID (init, then one play call per PAL frame)
     and records every SID register write per frame; it also runs the Thor image on the player image
     (parameters patched) and must record the same writes. Row starts come from the packed player's
     own channel-1 counter. Both go into the fixture as the reference. The relocation fixture (the same
     song laid out anew: every instrument program moved, instruments and patterns renumbered, patterns
     and orderlists elsewhere) must also write the same registers on py65.
  8. Write the outputs (also the edit fixtures: a note edit, a tempo edit, a shortened song). Two runs
     produce identical files.

Requirements: python3 with py65 (`python3 -m venv /tmp/sidref && /tmp/sidref/bin/pip install py65`;
run the script with that python); GoatTracker 2.77's gt2reloc and gtasm (`--goattracker DIR`, else
tools/audio/fetch_goattracker.sh fetches and builds them).
"""
import argparse
import copy
import hashlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "audio" / "sid"))
import gt_song  # noqa: E402
import gt_test_song  # noqa: E402

PLAYER_S = ROOT / "third_party" / "goattracker-2.77" / "player.s"
PLAYER_S_SHA256 = "ee9ddbe99f6d4dca8029bc5ff74f8b9c1ddff68986cf99cfd07ff3af5fad609a"
GOATTRACKER_ZIP_SHA256 = "96c2bd6a6ab3aca2f5bb18b1c764ac6ea69ac245cae14002a72cd87c554561ef"
SID_DIR = ROOT / "tools" / "audio" / "sid"
TEST_SNG = SID_DIR / "gt_test.sng"
TEST_SID = SID_DIR / "gt_test.sid"
OUT_PLAYER = ROOT / "app" / "src" / "main" / "cpp" / "generated" / "sid_player_image.h"
OUT_SONG = ROOT / "app" / "src" / "test" / "cpp" / "sid_gt_song.h"
OUT_KOTLIN = ROOT / "core" / "src" / "commonMain" / "kotlin" / "org" / "thortracker" / "backend" / "SidPlayerImage.kt"
FETCH = ROOT / "tools" / "audio" / "fetch_goattracker.sh"

PAL_FRAME_CYCLES = 19656   # 63 cycles x 312 lines: the PSID VBI rate for PAL songs (50.1245 Hz)
IDLE = 0x0FF0              # return address of host calls (the machine stops at its fetch)
TRAP = 0x0FF3              # interrupt / BRK vector target: a fault
SPEED_CODE = bytes([0xA2, 0x00, 0x8E, 0x04, 0xDC, 0xA2, 0x00, 0x8E, 0x05, 0xDC])  # greloc.c speedcode[]
CIA_PAL_LATCH = 0x4CC7     # greloc.c: PAL CIA timer value, divided by the multiplier
PSID_HEADER_SIZE = 0x7C
EDIT_ORDER, EDIT_ROW = 3, 8  # sid_gt_edited: order 3, channel 1, row 8 F-4 -> G-4
TEMPO_EDIT = 9               # sid_gt_tempo: order 0 row 0 F06 -> F09
PROGRAM_SHIFT = 100          # sid_gt_moved: instrument programs moved down by this many rows
AUDITION_RAM = 0x0E00      # $0E00-$0EFF: audition orderlists and patterns (spare machines only)
REF_FRAMES = 1500          # 30 s of reference writes

# greloc.c with "Disable optimization", our fixed choices (see the docstring).
FIXED_DEFINES = [
    ("base", gt_song.PLAYER_BASE), ("zpbase", gt_song.ZP_BASE), ("SIDBASE", gt_song.SID_BASE),
    ("SOUNDSUPPORT", 0), ("VOLSUPPORT", 0), ("BUFFEREDWRITES", 0), ("GHOSTREGS", 0), ("ZPGHOSTREGS", 0),
    ("FIXEDPARAMS", 0), ("SIMPLEPULSE", 0), ("PULSEOPTIMIZATION", 1), ("REALTIMEOPTIMIZATION", 1),
    ("NOAUTHORINFO", 1),
] + [(name, 0) for name in [
    "NOEFFECTS", "NOGATE", "NOFILTER", "NOFILTERMOD", "NOPULSE", "NOPULSEMOD", "NOWAVEDELAY", "NOWAVECMD",
    "NOREPEAT", "NOTRANS", "NOPORTAMENTO", "NOTONEPORTA", "NOVIB", "NOINSTRVIB", "NOSETAD", "NOSETSR",
    "NOSETWAVE", "NOSETWAVEPTR", "NOSETPULSEPTR", "NOSETFILTPTR", "NOSETFILTCTRL", "NOSETFILTCUTOFF",
    "NOSETMASTERVOL", "NOFUNKTEMPO", "NOGLOBALTEMPO", "NOCHANNELTEMPO", "NOFIRSTWAVECMD",
    "NOCALCULATEDSPEED", "NONORMALSPEED", "NOZEROSPEED"]] + [
    ("NUMCHANNELS", 3), ("NUMSONGS", 1), ("FIRSTNOTE", 0)]
PARAMS = ["FIRSTNOHRINSTR", "FIRSTLEGATOINSTR", "ADPARAM", "SRPARAM", "DEFAULTTEMPO"]
DEFAULT_PARAMS = {"FIRSTNOHRINSTR": 1, "FIRSTLEGATOINSTR": 1, "ADPARAM": 0x0F, "SRPARAM": 0x00, "DEFAULTTEMPO": 5}
PROBE_PARAMS = {"FIRSTNOHRINSTR": 0x21, "FIRSTLEGATOINSTR": 0x32, "ADPARAM": 0xA5, "SRPARAM": 0x5A, "DEFAULTTEMPO": 0x17}

# Player symbols the machine and tests use (value = label address).
VARIABLES = ["mt_chnsongptr", "mt_chntrans", "mt_chnrepeat", "mt_chnpattptr", "mt_chnpackedrest", "mt_chnnewfx",
             "mt_chnnewparam", "mt_chnfx", "mt_chnparam", "mt_chnnewnote", "mt_chnwaveptr", "mt_chnwave",
             "mt_chnpulseptr", "mt_chnpulsetime", "mt_chnsongnum", "mt_chnpattnum", "mt_chntempo", "mt_chncounter",
             "mt_chnnote", "mt_chninstr", "mt_chngate", "mt_chnvibtime", "mt_chnvibdelay", "mt_chnwavetime",
             "mt_chnfreqlo", "mt_chnfreqhi", "mt_chnpulselo", "mt_chnpulsehi", "mt_chnad", "mt_chnsr",
             "mt_chngatetimer", "mt_chnlastnote", "mt_initsongnum", "mt_filtstep", "mt_filttime", "mt_filtcutoff",
             "mt_filtctrl", "mt_filttype", "mt_masterfader", "mt_funktempotbl"]
TICK0 = ["mt_tick0_0", "mt_tick0_12", "mt_tick0_12", "mt_tick0_34", "mt_tick0_34", "mt_tick0_5", "mt_tick0_6",
         "mt_tick0_7", "mt_tick0_8", "mt_tick0_9", "mt_tick0_a", "mt_tick0_b", "mt_tick0_c", "mt_tick0_d",
         "mt_tick0_e", "mt_tick0_f"]
EFFECTS = ["mt_effect_0", "mt_effect_12", "mt_effect_12", "mt_effect_3", "mt_effect_4"]
CODE_LABELS = ["mt_init", "mt_play", "mt_tick0jumptbl", "mt_tick0jump1", "mt_tick0jump2", "mt_effectjumptbl",
               "mt_effectjump", "mt_execwavetick0jump"] + sorted(set(TICK0 + EFFECTS))
SYMBOLS = VARIABLES + CODE_LABELS


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fail(msg: str):
    sys.exit(f"prepare_sid_player: {msg}")


def define(name: str, value: int) -> str:
    return f"{name:<16} = {value}\n"  # greloc.c insertdefine()


def player_source(params: dict, counts=(1, 1, 1), equates=True, symbols=False, data: str = "") -> str:
    """greloc.c's source: defines, then player.s, then (here) the data. `counts` = NUMHRINSTR,
    NUMNOHRINSTR, NUMLEGATOINSTR (only compared with 0 by player.s)."""
    s = "".join(define(k, v) for k, v in FIXED_DEFINES)
    s += "".join(define(k, params[k]) for k in PARAMS)
    s += define("NUMHRINSTR", counts[0]) + define("NUMNOHRINSTR", counts[1]) + define("NUMLEGATOINSTR", counts[2])
    if equates:
        s += "".join(define(k, v) for k, v in gt_song.LABELS.items())
    s += PLAYER_S.read_bytes().decode("latin-1")
    s += data
    if symbols:
        s += "".join(f"                .WORD ({name})\n" for name in SYMBOLS)
    return s


def assemble(gtasm: Path, source: str) -> bytes:
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "p.s"
        out = Path(tmp) / "p.bin"
        src.write_bytes(source.encode("latin-1"))
        r = subprocess.run([str(gtasm), str(src), str(out)], capture_output=True, text=True)
        if r.returncode:
            fail(f"gtasm failed:\n{r.stdout}{r.stderr}")
        return out.read_bytes()


def greloc_data(low: gt_song.Lowered, song: gt_song.Song) -> str:
    """The data part of greloc.c's source for `low` (same bytes, greloc's labels and order)."""
    def lines(label, data):
        out = f"{label}:\n" if label else ""
        for i in range(0, len(data), 16):
            out += "                .BYTE (" + ",".join(f"${b:02x}" for b in data[i:i + 16]) + ")\n"
        return out
    region = low.region
    def get(label, n):
        a = gt_song.LABELS[label] - gt_song.REGION_BASE
        return bytes(region[a:a + n])
    s = lines("mt_freqtbllo", gt_song.FREQ_LO) + lines("mt_freqtblhi", gt_song.FREQ_HI)
    s += "mt_songtbllo:\n" + "".join(f"                .BYTE (mt_song{c} % 256)\n" for c in range(3))
    s += "mt_songtblhi:\n" + "".join(f"                .BYTE (mt_song{c} / 256)\n" for c in range(3))
    npatt = len(low.patterns)
    s += "mt_patttbllo:\n" + "".join(f"                .BYTE (mt_patt{k} % 256)\n" for k in range(npatt))
    s += "mt_patttblhi:\n" + "".join(f"                .BYTE (mt_patt{k} / 256)\n" for k in range(npatt))
    ni = len(song.instruments)
    for col in gt_song.INSTR_COLUMNS:
        s += lines(col, get(col, ni))
    for t in range(4):
        n = len(song.tables[t])
        if t == gt_song.STBL:
            s += lines("", b"\0")
        s += lines(gt_song.TABLE_LEFT[t], get(gt_song.TABLE_LEFT[t], n))
        if t == gt_song.STBL:
            s += lines("", b"\0")
        s += lines(gt_song.TABLE_RIGHT[t], get(gt_song.TABLE_RIGHT[t], n))
    for c in range(3):
        s += lines(f"mt_song{c}", low.orderlists[c])
    for p, (k, packed, _addr) in sorted(low.patterns.items(), key=lambda e: e[1][0]):
        s += lines(f"mt_patt{k}", packed)
    return s


def read_psid(data: bytes):
    if data[:4] != b"PSID":
        fail("gt2reloc output is not a PSID")
    off = int.from_bytes(data[6:8], "big")
    init = int.from_bytes(data[10:12], "big")
    play = int.from_bytes(data[12:14], "big")
    payload = data[off:]
    load = int.from_bytes(data[8:10], "big")
    if load == 0:
        load = payload[0] | payload[1] << 8
        payload = payload[2:]
    return load, init, play, payload


def py65_run(memory_init, init: int, play: int, frames: int, counter_addr=None, init_a: int = 0):
    """Runs init (A = song) then `frames` play calls; returns per-frame SID writes [(reg, value)] and,
    with `counter_addr`, the frames whose call starts a row on channel 1 (counter 1 before the call,
    after the init call and the first tick 0 that only loads patterns)."""
    from py65.devices.mpu6502 import MPU
    from py65.memory import ObservableMemory

    mem = ObservableMemory()
    for addr, data in memory_init:
        for i, b in enumerate(data):
            mem[addr + i] = b
    writes = []

    def on_write(address, value):
        writes.append((address - gt_song.SID_BASE, value))
        return None

    mem.subscribe_to_write(range(gt_song.SID_BASE, gt_song.SID_BASE + 0x20), on_write)
    mpu = MPU(memory=mem)

    def call(addr, a):
        ret = IDLE - 1
        mpu.stPush(ret >> 8)
        mpu.stPush(ret & 0xFF)
        mpu.a, mpu.x, mpu.y = a, 0, 0
        mpu.pc = addr
        steps = 0
        while mpu.pc != IDLE:
            mpu.step()
            steps += 1
            if steps > 100000:
                fail(f"py65: runaway call to ${addr:04X}")

    mpu.sp = 0xFD
    call(init, init_a)
    init_writes = list(writes)
    writes.clear()
    out, starts, ticks0 = [], [], 0
    for f in range(frames):
        if counter_addr is not None and mem[counter_addr] == 1:
            ticks0 += 1
            if ticks0 > 1:
                starts.append(f)
        call(play, 0)
        out.append(list(writes))
        writes.clear()
    return init_writes, out, starts


def c_bytes(name: str, data: bytes, const_type="uint8_t") -> str:
    out = f"static const {const_type} {name}[{len(data)}] = {{\n"
    for i in range(0, len(data), 24):
        out += "    " + ", ".join(f"0x{b:02X}" for b in data[i:i + 24]) + ",\n"
    return out + "};\n"


def kotlin_hex(data: bytes) -> str:
    text = data.hex().upper()
    return "\n".join(f'        "{text[i:i + 96]}" +' for i in range(0, len(text), 96)).rstrip(" +")


def move_programs(song: gt_song.Song, shift: int) -> gt_song.Song:
    """`song` with every instrument's wave, pulse and filter program (its start row up to its jump row)
    moved `shift` rows down and every reference to those rows rewritten (instrument pointers, jump
    targets, wavetable commands 8-A, pattern commands 8-A); the vacated rows get other content.
    Programs reached only by commands stay. It plays the same."""
    s = copy.deepcopy(song)
    maps = []
    for t, attr in enumerate(("wave", "pulse", "filt")):
        rows = {}
        for ins in s.instruments:
            start = getattr(ins, attr)
            if start:
                end = start
                while s.tables[t][end - 1][0] != 0xFF:
                    end += 1
                rows.update({r: r + shift for r in range(start, end + 1)})
        maps.append(rows)
    junk = [(0x81, 0x30), (0x8F, 0xF0), (0x00, 0xFF)]
    for t in range(3):
        old = s.tables[t]
        new = list(old) + [(0, 0)] * (max(maps[t].values()) - len(old))
        for r, (left, right) in enumerate(old, start=1):
            if left == 0xFF and right:
                right = maps[t].get(right, right)
            elif t == gt_song.WTBL and gt_song.WAVECMD <= left <= gt_song.WAVELASTCMD and 8 <= left & 0x0F <= 10:
                right = maps[(left & 0x0F) - 8].get(right, right)
            if r in maps[t]:
                new[maps[t][r] - 1] = (left, right)
                new[r - 1] = junk[t]
            else:
                new[r - 1] = (left, right)
        s.tables[t] = new
    for ins in s.instruments:
        ins.wave, ins.pulse, ins.filt = (maps[t].get(v, v) for t, v in enumerate((ins.wave, ins.pulse, ins.filt)))
    for p in s.patterns:
        for i, (note, ins, cmd, param) in enumerate(p):
            if 8 <= cmd <= 10 and param:
                p[i] = (note, ins, cmd, maps[cmd - 8].get(param, param))
    return s


def edited_songs(song: gt_song.Song):
    """Edit fixtures: a note edit ahead (order EDIT_ORDER, channel 1, row EDIT_ROW: F-4 -> G-4), a tempo
    edit at the song start (order 0 row 0 F06 -> F0<TEMPO_EDIT>), and the song cut to orders 0-1."""
    edited = copy.deepcopy(song)
    p = edited.patterns[edited.orderlists[0][EDIT_ORDER]]
    note, ins, cmd, param = p[EDIT_ROW]
    if note != 0x60 + 12 * 4 + 5:
        fail("edit fixture: expected F-4 at the edited cell")
    p[EDIT_ROW] = (note + 2, ins, cmd, param)
    tempo = copy.deepcopy(song)
    p = tempo.patterns[tempo.orderlists[0][0]]
    if p[0][2:] != (0xF, 0x06):
        fail("tempo fixture: expected F06 on order 0 row 0")
    p[0] = p[0][:3] + (TEMPO_EDIT,)
    short = copy.deepcopy(song)
    short.orderlists = [ol[:2] for ol in short.orderlists]
    return edited, tempo, short


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--goattracker", type=Path, help="directory with gt2reloc and gtasm (default: fetch_goattracker.sh)")
    args = ap.parse_args()
    try:
        import py65  # noqa: F401
    except ImportError:
        fail("needs py65 (python3 -m venv /tmp/sidref && /tmp/sidref/bin/pip install py65; run with that python)")

    if sha256(PLAYER_S.read_bytes()) != PLAYER_S_SHA256:
        fail(f"{PLAYER_S} does not match the pinned GoatTracker 2.77 player.s")
    tools = args.goattracker or Path(subprocess.run([str(FETCH)], check=True, capture_output=True, text=True)
                                     .stdout.strip().splitlines()[-1])
    gtasm, gt2reloc = tools / "gtasm", tools / "gt2reloc"

    # ---- 2-4: the player -----------------------------------------------------------------------
    base = gt_song.PLAYER_BASE
    image = assemble(gtasm, player_source(DEFAULT_PARAMS))
    probe = assemble(gtasm, player_source(PROBE_PARAMS))
    if len(image) != len(probe):
        fail("parameter values changed the player size")
    diffs = [i for i in range(len(image)) if image[i] != probe[i]]
    patch = {}
    for name in PARAMS:
        hits = [i for i in diffs if image[i] == DEFAULT_PARAMS[name] and probe[i] == PROBE_PARAMS[name]]
        if len(hits) != 1:
            fail(f"{name}: expected one immediate operand, found offsets {hits}")
        patch[name] = base + hits[0]
    if sorted(patch.values()) != [base + i for i in diffs]:
        fail(f"unexpected parameter-dependent bytes at {[hex(base + i) for i in diffs]}")
    with_symbols = assemble(gtasm, player_source(DEFAULT_PARAMS, symbols=True))
    if with_symbols[:len(image)] != image or len(with_symbols) != len(image) + 2 * len(SYMBOLS):
        fail("appending the symbol words changed the player")
    sym = {name: int.from_bytes(with_symbols[len(image) + 2 * k:len(image) + 2 * k + 2], "little")
           for k, name in enumerate(SYMBOLS)}
    jumps = bytes([0x4C, sym["mt_init"] & 0xFF, sym["mt_init"] >> 8, 0x4C, sym["mt_play"] & 0xFF, sym["mt_play"] >> 8])
    if image[:6] != jumps:
        fail("the player does not start with jmp mt_init, jmp mt_play")
    end = base + len(image)
    if end > gt_song.REGION_BASE:
        fail(f"player ends at ${end:04X}, above the region ${gt_song.REGION_BASE:04X}")

    def at(addr, n=1):
        return image[addr - base:addr - base + n]
    for table, n, users, targets in [("mt_tick0jumptbl", 16, ["mt_tick0jump1", "mt_tick0jump2", "mt_execwavetick0jump"], TICK0),
                                     ("mt_effectjumptbl", 5, ["mt_effectjump"], EFFECTS)]:
        lows = at(sym[table], n)
        for u in users:
            hi = at(sym[u] + 2)[0]
            for k in range(n):
                if (hi << 8 | lows[k]) != sym[targets[k]]:
                    fail(f"{table}[{k}] does not reach {targets[k]} through {u}")
    player_sha = sha256(image)
    player_id = bytes.fromhex(player_sha)[:4]

    # ---- 5-6: the test song --------------------------------------------------------------------
    song = gt_test_song.build()
    sng = gt_song.write_sng(song)
    if gt_song.write_sng(gt_song.read_sng(sng)) != sng:
        fail("song file round trip")
    if TEST_SNG.exists() and TEST_SNG.read_bytes() != sng:
        fail(f"{TEST_SNG} differs from gt_test_song.py (delete it to regenerate on purpose)")
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "t.sng").write_bytes(sng)
        r = subprocess.run([str(gt2reloc), "t.sng", "t.sid", "-I0", "-J1"], cwd=tmp, capture_output=True, text=True)
        if r.returncode or not (Path(tmp) / "t.sid").exists():
            fail(f"gt2reloc failed:\n{r.stdout}{r.stderr}")
        packed_sid = (Path(tmp) / "t.sid").read_bytes()
    if TEST_SID.exists() and TEST_SID.read_bytes() != packed_sid:
        fail(f"gt2reloc's packing differs from the committed {TEST_SID}")
    load, init, play, payload = read_psid(packed_sid)
    if (load, init, play) != (base, base, base + 3):
        fail(f"unexpected PSID addresses {load:04X} {init:04X} {play:04X}")

    low = gt_song.lower(song, player_id)
    counts = [0, 0, 0]
    for ins in song.instruments:
        counts[2 if ins.gatetimer & 0x40 else 1 if ins.gatetimer & 0x80 else 0] += 1
    if 0 in counts:
        fail("the test song needs hard-restart, no-hard-restart and legato instruments")
    song_params = {"FIRSTNOHRINSTR": low.firstnohr, "FIRSTLEGATOINSTR": low.firstlegato,
                   "ADPARAM": 0x0F, "SRPARAM": 0x00, "DEFAULTTEMPO": low.tempo}
    greloc_src = player_source(song_params, counts=tuple(counts), equates=False, data=greloc_data(low, song))
    rebuilt = assemble(gtasm, greloc_src)
    if rebuilt != payload:
        first = next((i for i in range(min(len(rebuilt), len(payload))) if rebuilt[i] != payload[i]), None)
        fail(f"the lowering's data with greloc's labels does not reassemble to gt2reloc's PSID "
             f"(sizes {len(rebuilt)}/{len(payload)}, first difference at ${base + (first or 0):04X})")
    gt_syms_src = player_source(song_params, counts=tuple(counts), equates=False, data=greloc_data(low, song),
                                symbols=True)
    gt_syms_bin = assemble(gtasm, gt_syms_src)
    gt_sym = {name: int.from_bytes(gt_syms_bin[len(payload) + 2 * k:len(payload) + 2 * k + 2], "little")
              for k, name in enumerate(SYMBOLS)}

    # ---- 7: py65 reference ---------------------------------------------------------------------
    init_w, ref, ref_starts = py65_run([(load, payload)], init, play, REF_FRAMES, counter_addr=gt_sym["mt_chncounter"])
    thor_player = bytearray(image)
    for name, addr in patch.items():
        thor_player[addr - base] = song_params[name]
    thor_init_w, thor_ref, thor_starts = py65_run(
        [(base, bytes(thor_player)), (gt_song.REGION_BASE, bytes(low.region))], base, base + 3, REF_FRAMES,
        counter_addr=sym["mt_chncounter"])
    if (thor_init_w, thor_ref, thor_starts) != (init_w, ref, ref_starts):
        frame = next((f for f in range(REF_FRAMES) if thor_ref[f] != ref[f]), None)
        fail(f"py65: the Thor image writes differently from gt2reloc's PSID (first at frame {frame})")
    if init_w:
        fail("init wrote SID registers")
    rows = [len(song.patterns[song.orderlists[0][o]]) for o in range(len(song.orderlists[0]))]
    # The relocation fixture: same music, everything moved; py65 must hear no difference.
    moved = gt_song.lower(move_programs(song, PROGRAM_SHIFT), player_id, relayout=True)
    if (moved.firstnohr, moved.firstlegato, moved.tempo) != (low.firstnohr, low.firstlegato, low.tempo):
        fail("the relocation fixture changed the player parameters")
    if moved.image[32:96] == low.image[32:96] or moved.region == low.region:
        fail("the relocation fixture did not move anything")
    moved_init_w, moved_ref, moved_starts = py65_run(
        [(base, bytes(thor_player)), (gt_song.REGION_BASE, bytes(moved.region))], base, base + 3, REF_FRAMES,
        counter_addr=sym["mt_chncounter"])
    if (moved_init_w, moved_ref, moved_starts) != (init_w, ref, ref_starts):
        frame = next((f for f in range(REF_FRAMES) if moved_ref[f] != ref[f]), None)
        fail(f"py65: the relocation fixture writes differently (first at frame {frame})")
    variants = {"moved": moved}
    for name, s in zip(("edited", "tempo", "short"), edited_songs(song)):
        variants[name] = gt_song.lower(s, player_id)

    # ---- 8: outputs ----------------------------------------------------------------------------
    TEST_SNG.write_bytes(sng)
    TEST_SID.write_bytes(packed_sid)
    write_player_header(image, player_sha, sym, patch)
    write_song_fixture(low, packed_sid, ref, ref_starts, rows, song, variants)
    write_kotlin(image, player_sha, patch)
    print(f"player ${base:04X}-${end - 1:04X} ({len(image)} bytes), id {player_id.hex()}, data end ${low.data_end:04X}, "
          f"{sum(len(f) for f in ref)} reference writes in {REF_FRAMES} frames, {len(ref_starts)} row starts; "
          f"relocation fixture data end ${moved.data_end:04X}, same writes on py65")


def write_player_header(image: bytes, player_sha: str, sym: dict, patch: dict):
    L = gt_song.LABELS
    pid = int.from_bytes(bytes.fromhex(player_sha)[:4], "little")
    h = f"""// GENERATED by tools/audio/prepare_sid_player.py -- do not edit.
// GoatTracker 2.77 playroutine (third_party/goattracker-2.77/player.s, sha256 {PLAYER_S_SHA256},
// unmodified; release GoatTracker_2.77.zip sha256 {GOATTRACKER_ZIP_SHA256}), assembled at $1000 by
// GoatTracker's own assembler (Magnus Lind's, from Exomizer) with the packer's "Disable optimization"
// configuration and the song data labels at the fixed layout addresses below.
// Playroutine licence (player.s): "This playroutine source code does not fall under the GPL license! Use it,
// or song binaries created from it freely for any purpose, commercial or noncommercial." (Lasse Oorni)
// image sha256 {player_sha}
#ifndef SID_PLAYER_IMAGE_H_
#define SID_PLAYER_IMAGE_H_
#include <stdint.h>

#define SID_PLAYER_BASE 0x{gt_song.PLAYER_BASE:04X}
#define SID_PLAYER_SIZE 0x{len(image):04X}
#define SID_PLAYER_ID 0x{pid:08X}u /* first 4 bytes of the image sha256, little-endian */
#define SID_PLAYER_INIT 0x{gt_song.PLAYER_BASE:04X} /* jmp mt_init; A = subtune (0) */
#define SID_PLAYER_PLAY 0x{gt_song.PLAYER_BASE + 3:04X} /* jmp mt_play: one player tick */
#define SID_PLAYER_IDLE 0x{IDLE:04X} /* return address of host calls (RAM; the machine stops at its fetch) */
#define SID_PLAYER_TRAP 0x{TRAP:04X} /* BRK/IRQ/NMI vector target: a fault */
#define SID_AUDITION_RAM 0x{AUDITION_RAM:04X} /* $0E00-$0EFF: audition orderlists and patterns */
#define SID_ZP_BASE 0x{gt_song.ZP_BASE:02X} /* mt_temp1, mt_temp2 */
#define SID_CHIP_BASE 0x{gt_song.SID_BASE:04X}
#define SID_PAL_FRAME_CYCLES {PAL_FRAME_CYCLES}u /* 63 x 312: PAL VBI, 50.1245 Hz at 985248 Hz */
#define SID_PAL_CPU_HZ 985248u
#define SID_CIA_PAL_LATCH 0x4CC7 /* gt2reloc's CIA timer value for multispeed PSIDs (divided by the multiplier) */

/* Parameter operands: the machine writes each image's value here (greloc.c defines). */
#define SID_PATCH_FIRSTNOHRINSTR 0x{patch['FIRSTNOHRINSTR']:04X}
#define SID_PATCH_FIRSTLEGATOINSTR 0x{patch['FIRSTLEGATOINSTR']:04X}
#define SID_PATCH_ADPARAM 0x{patch['ADPARAM']:04X}
#define SID_PATCH_SRPARAM 0x{patch['SRPARAM']:04X}
#define SID_PATCH_DEFAULTTEMPO 0x{patch['DEFAULTTEMPO']:04X}

/* Player variables (channel n at +7n) and self-modified operands (+1 = operand). */
"""
    for name in VARIABLES:
        h += f"#define SID_{name.upper()} 0x{sym[name]:04X}\n"
    h += f"""
/* Image layout (docs/AUDIO_ENGINE.md, SID "Image layout"): header, then the region = C64 RAM
 * SID_REGION_BASE..SID_REGION_END (exclusive): fixed tables, then orderlists and patterns from SID_HEAP_BASE. */
#define SID_REGION_BASE 0x{gt_song.REGION_BASE:04X}
#define SID_REGION_END 0x{gt_song.REGION_END:04X}
#define SID_HEAP_BASE 0x{gt_song.HEAP_BASE:04X}
"""
    for name, addr in L.items():
        h += f"#define SID_{name.upper()} 0x{addr:04X}\n"
    h += f"""#define SID_IMAGE_HEADER_SIZE {gt_song.IMAGE_HEADER_SIZE}
#define SID_IMAGE_SIZE {gt_song.IMAGE_SIZE}
#define SID_IMAGE_MAGIC "TTSD"
#define SID_LAYOUT_THOR {gt_song.LAYOUT_THOR}
#define SID_MAX_INSTRUMENTS 63
#define SID_MAX_PATTERNS {gt_song.MAX_PATT}
#define SID_MAX_ORDERS {gt_song.MAX_SONGLEN}
#define SID_MAX_ROWS {gt_song.MAX_PATTROWS}

static const uint8_t sid_player_code[SID_PLAYER_SIZE] = {{
"""
    for i in range(0, len(image), 24):
        h += "    " + ", ".join(f"0x{b:02X}" for b in image[i:i + 24]) + ",\n"
    h += "};\n\n#endif /* SID_PLAYER_IMAGE_H_ */\n"
    OUT_PLAYER.write_text(h)


def write_song_fixture(low, packed_sid: bytes, ref, ref_starts, rows, song, variants: dict):
    def used_bytes(lowered):
        return gt_song.IMAGE_HEADER_SIZE + (lowered.data_end - gt_song.REGION_BASE)
    used = used_bytes(low)
    writes = bytearray()
    index = [0]
    for f in ref:
        for reg, val in f:
            writes += bytes([reg, val])
        index.append(len(writes) // 2)
    h = f"""// GENERATED by tools/audio/prepare_sid_player.py -- do not edit.
// The SID host-test song (tools/audio/sid/gt_test_song.py -> gt_test.sng): GoatTracker 2.77's own packing
// of it (gt2reloc -I0 -J1 -> gt_test.sid), the Thor-layout image of it (gt_song.lower, proven to be the
// packer's data), and the reference: py65 running gt_test.sid (init, then one play call per frame).
// Live-edit fixtures (Thor layout, images cut after their data end like sid_gt_image):
//   sid_gt_moved:  the same song laid out anew (gt_song.lower relayout: instruments renumbered within their
//                  hard-restart groups, pattern numbers reversed, patterns stored backwards before the
//                  orderlists; every instrument's wave/pulse/filter program {PROGRAM_SHIFT} rows further down,
//                  references rewritten, vacated rows refilled); py65 checked: same writes as the reference.
//   sid_gt_edited: order {EDIT_ORDER}, channel 1, row {EDIT_ROW}: F-4 -> G-4.
//   sid_gt_tempo:  order 0 row 0 F06 -> F{TEMPO_EDIT:02X} (stored minus one, as the packer does).
//   sid_gt_short:  orders 0-1 only (restart 1).
#ifndef SID_GT_SONG_H_
#define SID_GT_SONG_H_
#include <stdint.h>

"""
    h += f"#define SID_GT_IMAGE_USED {used} /* bytes; the rest of the SID_IMAGE_SIZE image is zero */\n"
    h += f"#define SID_GT_ORDERS {len(rows)}\n#define SID_GT_LOOP {song.restart[0]}\n"
    h += f"static const int sid_gt_rows[SID_GT_ORDERS] = {{{', '.join(map(str, rows))}}};\n"
    h += f"#define SID_GT_DATA_END 0x{low.data_end:04X}\n"
    h += f"#define SID_GT_TONE_SLOT 6 /* \"Tone\": triangle, A/D 00, S/R F0 */\n"
    h += f"#define SID_GT_REF_FRAMES {len(ref)}\n"
    h += f"#define SID_GT_REF_STARTS {len(ref_starts)}\n"
    h += f"#define SID_GT_EDIT_ORDER {EDIT_ORDER}\n#define SID_GT_EDIT_ROW {EDIT_ROW}\n"
    h += f"#define SID_GT_TEMPO_EDIT {TEMPO_EDIT - 1} /* mt_chntempo after the edited F command */\n"
    h += f"#define SID_GT_PROGRAM_SHIFT {PROGRAM_SHIFT}\n"
    h += c_bytes("sid_gt_image", low.image[:used])
    for name, lowered in variants.items():
        h += f"#define SID_GT_{name.upper()}_USED {used_bytes(lowered)}\n"
        h += c_bytes(f"sid_gt_{name}", lowered.image[:used_bytes(lowered)])
    h += c_bytes("sid_gt_psid", packed_sid)
    h += f"static const uint32_t sid_gt_ref_index[SID_GT_REF_FRAMES + 1] = {{\n"
    for i in range(0, len(index), 16):
        h += "    " + ", ".join(map(str, index[i:i + 16])) + ",\n"
    h += "};\n"
    h += c_bytes("sid_gt_ref_writes", bytes(writes))
    h += "/* frames (play calls after init, from 0) whose call starts a row: the packed player's channel-1 counter */\n"
    h += "static const uint16_t sid_gt_ref_starts[SID_GT_REF_STARTS] = {\n"
    for i in range(0, len(ref_starts), 16):
        h += "    " + ", ".join(map(str, ref_starts[i:i + 16])) + ",\n"
    h += "};\n\n#endif /* SID_GT_SONG_H_ */\n"
    OUT_SONG.write_text(h)


def write_kotlin(image: bytes, player_sha: str, patch: dict):
    L = gt_song.LABELS
    pid = int.from_bytes(bytes.fromhex(player_sha)[:4], "little")
    labels = "\n".join(f"    const val MT_{name[3:].upper()} = 0x{addr:04X}" for name, addr in L.items())
    OUT_KOTLIN.write_text(f"""// GENERATED by tools/audio/prepare_sid_player.py -- do not edit.
package org.thortracker.backend

/**
 * The C64 SID player: GoatTracker 2.77's playroutine (`third_party/goattracker-2.77/player.s`, unmodified)
 * assembled once at [LOAD] by `tools/audio/prepare_sid_player.py` with the packer's "Disable optimization"
 * configuration and the song data labels at fixed addresses, and the numbers of the native image layout
 * (`docs/AUDIO_ENGINE.md`, SID "Image layout"). The same bytes play in the app (native
 * `app/src/main/cpp/generated/sid_player_image.h`) and form `.sid`/`.prg` exports: [BYTES] with the five
 * header parameters written at the `PATCH_` addresses, zeros up to [REGION_BASE], then the image region up to
 * its data end. Image sha256 `{player_sha}`.
 * Playroutine licence (player.s): "This playroutine source code does not fall under the GPL license! Use it,
 * or song binaries created from it freely for any purpose, commercial or noncommercial." (Lasse Öörni)
 */
object SidPlayerImage {{
    /** Load, init (A = subtune 0: `jmp mt_init`) and play (`jmp mt_play`, one tick) addresses. */
    const val LOAD = 0x{gt_song.PLAYER_BASE:04X}
    const val INIT = 0x{gt_song.PLAYER_BASE:04X}
    const val PLAY = 0x{gt_song.PLAYER_BASE + 3:04X}

    /** Header bytes 4..7 (little-endian): an image only plays on the player build it was lowered for. */
    const val PLAYER_ID = 0x{pid:08X}.toInt()

    /** Immediate operands of the song-dependent player constants (greloc.c defines): header bytes 16, 17, 18, 19, 15. */
    const val PATCH_FIRSTNOHRINSTR = 0x{patch['FIRSTNOHRINSTR']:04X}
    const val PATCH_FIRSTLEGATOINSTR = 0x{patch['FIRSTLEGATOINSTR']:04X}
    const val PATCH_ADPARAM = 0x{patch['ADPARAM']:04X}
    const val PATCH_SRPARAM = 0x{patch['SRPARAM']:04X}
    const val PATCH_DEFAULTTEMPO = 0x{patch['DEFAULTTEMPO']:04X}

    /**
     * Bytes of a lowered image: [HEADER_SIZE] header bytes, then the region = C64 RAM [REGION_BASE] until
     * [REGION_END] (exclusive; address a at image offset [HEADER_SIZE] + a − [REGION_BASE]): the fixed tables
     * at the `MT_` labels, then orderlists and patterns from [HEAP_BASE] up to the data end.
     */
    const val IMAGE_SIZE = {gt_song.IMAGE_SIZE}
    const val HEADER_SIZE = {gt_song.IMAGE_HEADER_SIZE}
    const val REGION_BASE = 0x{gt_song.REGION_BASE:04X}
    const val REGION_END = 0x{gt_song.REGION_END:04X}
    const val HEAP_BASE = 0x{gt_song.HEAP_BASE:04X}

    /** Header fields (offsets; u16 little-endian): "TTSD", player id, region base, data end, then bytes. */
    const val MAGIC = "TTSD"
    const val HEADER_PLAYER_ID = 4
    const val HEADER_REGION_BASE = 8
    const val HEADER_DATA_END = 10
    const val HEADER_LAYOUT = 12
    const val HEADER_MODEL = 13
    const val HEADER_MULTIPLIER = 14
    const val HEADER_TEMPO = 15
    const val HEADER_FIRSTNOHR = 16
    const val HEADER_FIRSTLEGATO = 17
    const val HEADER_HR_AD = 18
    const val HEADER_HR_SR = 19
    const val HEADER_INSTRUMENTS = 20
    /** 64 bytes: player instrument number of each instrument slot (slot 0: 0; 0 = no instrument). */
    const val HEADER_SLOT_MAP = 32

    /** Header byte [HEADER_LAYOUT]. */
    const val LAYOUT_THOR = {gt_song.LAYOUT_THOR}
    /** Header byte [HEADER_MODEL]. */
    const val MODEL_6581 = 0
    const val MODEL_8580 = 1

    const val MAX_INSTRUMENTS = 63
    const val MAX_PATTERNS = {gt_song.MAX_PATT}
    const val MAX_ORDERS = {gt_song.MAX_SONGLEN}
    const val MAX_ROWS = {gt_song.MAX_PATTROWS}
    const val MAX_PATTERN_BYTES = 256
    const val TABLE_ROWS = {gt_song.MAX_TABLELEN}

    /** Player data labels (instrument n at column + n − 1; table row n at label + n − 1). */
{labels}

    /** PAL timing: CPU clock, cycles per frame (speed 1 tick), CIA timer value for multispeed (÷ multiplier). */
    const val CPU_HZ = 985248
    const val PAL_FRAME_CYCLES = {PAL_FRAME_CYCLES}
    const val CIA_PAL_LATCH = 0x{CIA_PAL_LATCH:04X}

    /**
     * PSID v2 as gt2reloc writes it: [PSID_HEADER_SIZE]-byte header (big-endian words: version 2, data offset,
     * load 0 = in the data, init, play, 1 song, start song 1, speed word 0 or all ones for multispeed, 32-byte
     * name/author/released, flags = PAL | model), then the load address little-endian and the data. Multispeed
     * songs load and init at [SPEED_CODE_ADDRESS]: [SPEED_CODE] with [CIA_PAL_LATCH] / multiplier at bytes 1
     * (low) and 6 (high) programs the CIA timer, then falls into [INIT]. `.prg`: [LOAD] little-endian + data.
     */
    const val PSID_HEADER_SIZE = 0x{PSID_HEADER_SIZE:02X}
    const val PSID_FLAGS_PAL = 0x04
    const val PSID_FLAGS_6581 = 0x10
    const val PSID_FLAGS_8580 = 0x20
    const val SPEED_CODE_ADDRESS = 0x{gt_song.PLAYER_BASE - len(SPEED_CODE):04X}
    val SPEED_CODE: ByteArray get() = hex("{SPEED_CODE.hex().upper()}")

    /** GoatTracker's default frequency table (notes 0 = C-0 .. 95 = B-7; A-4 = 57 = $1D46, 440.09 Hz). */
    val FREQ_LO: ByteArray get() = hex("{gt_song.FREQ_LO.hex().upper()}")
    val FREQ_HI: ByteArray get() = hex("{gt_song.FREQ_HI.hex().upper()}")

    /** The player image ({len(image)} bytes at [LOAD], parameters unwritten); a fresh copy per call. */
    val BYTES: ByteArray get() = hex(HEX)

    private fun hex(s: String) = ByteArray(s.length / 2) {{ s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }}

    private const val HEX =
{kotlin_hex(image)}
}}
""")


if __name__ == "__main__":
    main()
