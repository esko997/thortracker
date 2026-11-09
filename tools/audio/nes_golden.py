#!/usr/bin/env python3
"""Make the NES golden fixtures: FamiStudio 4.5.3's own export of our FamiStudio text exports.

For every core/src/jvmTest/resources/nes-golden/<name>.txt (written by NesGoldenTest with THOR_UPDATE_NES_GOLDEN=1):

1. FamiStudio imports the text and exports its sound-engine data:
   `dotnet FamiStudio.dll <name>.txt famistudio-asm-export <name>_ca65.s -famistudio-asm-format:ca65`
   (a failed import or export fails this script, so it also proves FamiStudio opens our text).
2. cc65's ca65/ld65 assemble that export alone at the song base of the player build the song needs ($8C00 for the 2A03
   build, tools/audio/nes/nes_song.cfg; a text with `Expansions="VRC6"`, `"VRC7"`, `"FDS"`, `"S5B"` or `"EPSM"` (e.g. the
   golden songs s5b_demo, s5b_effects, epsm_demo, epsm_effects and epsm_rhythm) at NES_<CHIP>_SONG_BASE from
   app/src/main/cpp/generated/nes_<chip>_player_image.h; a text with `Expansions="N163" NumN163Channels="n"` at
   NES_N163_<n>_SONG_BASE from nes_n163_<n>_player_image.h) into <name>.bin, with FAMISTUDIO_DPCM_PTR at the sample block
   Thor Tracker uses (the top of $C000-$FFF9, 64-aligned). A song with DPCM samples also gets FamiStudio's sample bank,
   <name>_ca65.dmc. FamiStudio 4.5.3 prints a few negative operands as 32-bit hex (a VRC6 Quarter saw volume, an S5B or
   EPSM instrument's negative auto-period octave: `$ffffffXX`), which ca65 rejects; the copy that is assembled reads them
   as their low byte, as FamiStudio's binary export does. An EPSM export wraps each EPSM channel's header word in
   `.if FAMISTUDIO_EXP_EPSM_...`: the assembly defines the configuration the EPSM player is built with (3 SSG, 6 FM,
   6 rhythm channels). Its expansion instruments point to 31-byte patch blocks (`instrument_epsm_extra_patchN`) that are
   part of <name>.bin like every other label.

NesGoldenTest then compares the APU register traces of <name>.bin and our lowering through the embedded player, and
our sample memory with <name>_ca65.dmc.

Usage: tools/audio/nes_golden.py [--famistudio DIR]   (DIR = FamiStudio 4.5.3 Linux release; default: the pinned
release fetched and verified into the cache by tools/audio/fetch_famistudio.sh)
dotnet (8) and ca65/ld65 are used from PATH, else through `distrobox enter ubuntu --` (as prepare_nes_player.py).

Parent commands (after tools/audio/prepare_nes_player.py has written the generated player headers):
  THOR_UPDATE_NES_GOLDEN=1 tools/build.sh :core:jvmTest --tests '*NesGoldenTest*'   # writes the <name>.txt texts
  tools/audio/nes_golden.py                                                          # FamiStudio's export of each
  tools/build.sh :core:jvmTest --tests '*NesGoldenTest*'                             # compares against them
No golden needs or contains the EPSM rhythm ROM: the comparison is of register writes, not rhythm audio.
"""
import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "core" / "src" / "jvmTest" / "resources" / "nes-golden"
SONG_CFG = ROOT / "tools" / "audio" / "nes" / "nes_song.cfg"
GENERATED = ROOT / "app" / "src" / "main" / "cpp" / "generated"
FETCH_FAMISTUDIO = ROOT / "tools" / "audio" / "fetch_famistudio.sh"
CC65_VERSION = "ca65 V2.18 - Ubuntu 2.19-1"
REGION_END = 0xFFFA
# The EPSM player's configuration (prepare_nes_player.py EPSM_CONFIG): the conditions of an EPSM export's header.
EPSM_CONFIG = {"FAMISTUDIO_EXP_EPSM_SSG_CHN_CNT": 3, "FAMISTUDIO_EXP_EPSM_FM_CHN_CNT": 6,
               **{f"FAMISTUDIO_EXP_EPSM_RHYTHM_CHN{i}_ENABLE": 1 for i in range(1, 7)}}


def song_cfg(text: str, tmp: Path) -> Path:
    """The ld65 layout for a song text: the 2A03 build's, or one at the song base of the VRC6 / VRC7 / FDS / S5B / EPSM
    build, or of the N163 build with the text's NumN163Channels (FamiStudio's default without it: 1)."""
    m = re.search(r'^Project [^\n]*Expansions="(VRC6|VRC7|N163|FDS|S5B|EPSM)"', text, re.M)
    if not m:
        return SONG_CFG
    chip = m.group(1)
    if chip == "N163":
        count = re.search(r'^Project [^\n]*NumN163Channels="([1-8])"', text, re.M)
        chip = f"N163_{count.group(1) if count else 1}"
    header = GENERATED / f"nes_{chip.lower()}_player_image.h"
    base = int(re.search(rf"#define NES_{chip}_SONG_BASE 0x([0-9A-F]{{4}})", header.read_text()).group(1), 16)
    cfg = tmp / f"nes_{chip.lower()}_song.cfg"
    cfg.write_text(f"MEMORY {{\n    SONG: start = ${base:04X}, size = ${REGION_END - base:04X}, type = ro, file = %O, fill = no;\n}}\n"
                   'SEGMENTS {\n    SONG: load = SONG, type = ro;\n}\n')
    return cfg


def prefix_for(*tools: str) -> list:
    if all(shutil.which(t) for t in tools):
        return []
    return ["distrobox", "enter", "ubuntu", "--"]


def run(cmd: list, cwd: Path) -> str:
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"{' '.join(cmd)} failed ({r.returncode}):\n{r.stdout}\n{r.stderr}")
    return r.stdout + r.stderr


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--famistudio", type=Path)
    args = ap.parse_args()
    if args.famistudio is None:
        # Progress goes to stderr (shown); the last stdout line is the release directory.
        fetched = subprocess.run([str(FETCH_FAMISTUDIO)], stdout=subprocess.PIPE, text=True, check=True).stdout
        args.famistudio = Path(fetched.splitlines()[-1])
    dll = args.famistudio / "FamiStudio.dll"
    if not dll.exists():
        sys.exit(f"{dll} not found (FamiStudio 4.5.3 Linux release)")
    texts = sorted(FIXTURES.glob("*.txt"))
    if not texts:
        sys.exit(f"no .txt fixtures in {FIXTURES}; run NesGoldenTest with THOR_UPDATE_NES_GOLDEN=1 first")
    dotnet = prefix_for("dotnet") + ["dotnet", str(dll)]
    cc65 = prefix_for("ca65", "ld65")
    version = run(cc65 + ["ca65", "--version"], ROOT).strip()
    if version != CC65_VERSION:
        sys.exit(f"unexpected assembler {version!r}; expected {CC65_VERSION!r}")
    with tempfile.TemporaryDirectory(prefix="nes-golden-", dir="/tmp") as tmp:
        t = Path(tmp)
        for txt in texts:
            name = txt.stem
            shutil.copy(txt, t / txt.name)
            log = run(dotnet + [txt.name, "famistudio-asm-export", f"{name}_ca65.s", "-famistudio-asm-format:ca65"], t)
            asm = t / f"{name}_ca65.s"
            if not asm.exists() or "Error" in log:
                sys.exit(f"FamiStudio did not export {txt.name}:\n{log}")
            # Thor Tracker puts the sample block at the top of $C000-$FFF9 (NesDpcm.startFor); FamiStudio's list bytes
            # are offsets from FAMISTUDIO_DPCM_PTR, so point it there.
            dmc = t / f"{name}_ca65.dmc"
            size = dmc.stat().st_size if dmc.exists() else 0
            start = (0xFFFA - size) // 64 * 64 if size else 0xC000
            # FamiStudio 4.5.3 prints a VRC6 Quarter saw volume (`$55` operand 1 - 2 = -1) as `$ffffffff`, and a negative
            # S5B or EPSM auto-period octave (the second byte of the instrument's `.byte shape, octave` row) as
            # `$ffffffXX`, which ca65 rejects; its binary export writes the low byte. It also ends each VRC7 instrument's
            # patch `.byte` row with a comma. Assemble a copy with these fixed (the fixture keeps the export).
            fixed = t / f"{name}_ca65_fixed.s"
            text = re.sub(r"(\$55,\s*)\$ffffffff\b", r"\1$ff", asm.read_text())
            text = re.sub(r"^(\t\.byte \$[0-9a-f]{2},\s*)\$ffffff(f[89a-f])[ \t]*$", r"\1$\2", text, flags=re.M)
            fixed.write_text(re.sub(r"^(\t\.byte .*?),[ \t]*$", r"\1", text, flags=re.M))
            # An EPSM export's header words sit inside `.if` on the player's configuration: define it.
            epsm = re.search(r'^Project [^\n]*Expansions="EPSM"', txt.read_text(), re.M)
            config = "".join(f"{k} = {v}\n" for k, v in EPSM_CONFIG.items()) if epsm else ""
            (t / f"{name}_song.s").write_text(
                f'FAMISTUDIO_DPCM_PTR = ${(start & 0x3FFF) >> 6:02x}\n{config}.segment "SONG"\n.include "{fixed.name}"\n')
            run(cc65 + ["ca65", "-o", f"{name}.o", f"{name}_song.s"], t)
            run(cc65 + ["ld65", "-C", str(song_cfg(txt.read_text(), t)), "-o", f"{name}.bin", f"{name}.o"], t)
            shutil.copy(asm, FIXTURES / asm.name)
            shutil.copy(t / f"{name}.bin", FIXTURES / f"{name}.bin")
            (FIXTURES / dmc.name).unlink(missing_ok=True)
            if dmc.exists():
                shutil.copy(dmc, FIXTURES / dmc.name)
            print(f"{name}: {(FIXTURES / f'{name}.bin').stat().st_size} bytes of FamiStudio data"
                  + (f", {dmc.stat().st_size} bytes of samples" if dmc.exists() else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
