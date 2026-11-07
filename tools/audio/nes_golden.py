#!/usr/bin/env python3
"""Make the NES golden fixtures: FamiStudio 4.5.3's own export of our FamiStudio text exports.

For every core/src/jvmTest/resources/nes-golden/<name>.txt (written by NesGoldenTest with THOR_UPDATE_NES_GOLDEN=1):

1. FamiStudio imports the text and exports its sound-engine data:
   `dotnet FamiStudio.dll <name>.txt famistudio-asm-export <name>_ca65.s -famistudio-asm-format:ca65`
   (a failed import or export fails this script, so it also proves FamiStudio opens our text).
2. cc65's ca65/ld65 assemble that export alone at the song base $A000 (tools/audio/nes/nes_song.cfg) into <name>.bin.

NesGoldenTest then compares the APU register traces of <name>.bin and our lowering through the embedded player.

Usage: tools/audio/nes_golden.py [--famistudio DIR]   (DIR = FamiStudio 4.5.3 Linux release; default: the pinned
release fetched and verified into the cache by tools/audio/fetch_famistudio.sh)
dotnet (8) and ca65/ld65 are used from PATH, else through `distrobox enter ubuntu --` (as prepare_nes_player.py).
"""
import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "core" / "src" / "jvmTest" / "resources" / "nes-golden"
SONG_CFG = ROOT / "tools" / "audio" / "nes" / "nes_song.cfg"
FETCH_FAMISTUDIO = ROOT / "tools" / "audio" / "fetch_famistudio.sh"
CC65_VERSION = "ca65 V2.18 - Ubuntu 2.19-1"


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
            (t / f"{name}_song.s").write_text(f'.segment "SONG"\n.include "{name}_ca65.s"\n')
            run(cc65 + ["ca65", "-o", f"{name}.o", f"{name}_song.s"], t)
            run(cc65 + ["ld65", "-C", str(SONG_CFG), "-o", f"{name}.bin", f"{name}.o"], t)
            shutil.copy(asm, FIXTURES / asm.name)
            shutil.copy(t / f"{name}.bin", FIXTURES / f"{name}.bin")
            print(f"{name}: {(FIXTURES / f'{name}.bin').stat().st_size} bytes of FamiStudio data")
    return 0


if __name__ == "__main__":
    sys.exit(main())
