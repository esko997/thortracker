#!/usr/bin/env python3
"""Make the SID golden fixtures: GoatTracker 2.77's own packing of our GoatTracker song exports.

For every core/src/jvmTest/resources/sid-golden/<name>.sng (written by SidGoldenTest with THOR_UPDATE_SID_GOLDEN=1,
together with <name>.args, the packer options the song needs: multispeed -S and hard-restart envelope -A):

    gt2reloc <name>.sng <name>.sid -I0 -J1 <args>

("Disable optimization" as the Thor player is built; gt2reloc's -I case falls through into -J, so -J1 keeps full
buffering off). A song GoatTracker cannot load or pack fails this script, so it also proves GoatTracker opens our
files. gt2reloc keeps file names in 60-byte buffers, so it runs in a short temporary directory.

SidGoldenTest then requires our .sid export and <name>.sid to write the same SID registers in every frame.

Usage: tools/audio/sid_golden.py [--goattracker DIR]   (DIR holds gt2reloc; default: the pinned GoatTracker 2.77
release fetched, verified and built by tools/audio/fetch_goattracker.sh)
"""
import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "core" / "src" / "jvmTest" / "resources" / "sid-golden"
FETCH = ROOT / "tools" / "audio" / "fetch_goattracker.sh"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--goattracker", type=Path)
    args = ap.parse_args()
    if args.goattracker is None:
        fetched = subprocess.run([str(FETCH)], stdout=subprocess.PIPE, text=True, check=True).stdout
        args.goattracker = Path(fetched.splitlines()[-1])
    gt2reloc = args.goattracker / "gt2reloc"
    if not gt2reloc.exists():
        sys.exit(f"{gt2reloc} not found")
    songs = sorted(FIXTURES.glob("*.sng"))
    if not songs:
        sys.exit(f"no .sng fixtures in {FIXTURES}; run SidGoldenTest with THOR_UPDATE_SID_GOLDEN=1 first")
    for sng in songs:
        options = (sng.with_suffix(".args").read_text().split() if sng.with_suffix(".args").exists() else [])
        with tempfile.TemporaryDirectory(prefix="sidg") as tmp:
            shutil.copy(sng, Path(tmp) / "t.sng")
            r = subprocess.run([str(gt2reloc), "t.sng", "t.sid", "-I0", "-J1", *options], cwd=tmp, capture_output=True, text=True)
            out = Path(tmp) / "t.sid"
            if r.returncode != 0 or not out.exists() or out.read_bytes()[:4] != b"PSID":
                sys.exit(f"gt2reloc failed on {sng.name} ({r.returncode}):\n{r.stdout}\n{r.stderr}")
            shutil.copy(out, sng.with_suffix(".sid"))
        print(f"{sng.stem}: {sng.with_suffix('.sid').stat().st_size} bytes ({' '.join(options)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
