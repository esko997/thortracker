#!/usr/bin/env python3
"""Prepares the Master System engine ("sms-banjo"): the Banjo driver image the machine runs (Python 3, no
dependencies; WLA-DX from tools/audio/fetch_wladx.sh):

  app/src/main/cpp/generated/banjo_driver_image.h  the 16 KiB bank: Banjo (third_party/banjo, unmodified) with
                                                   Thor Tracker's host routines (tools/audio/sms/thor_banjo_host.asm)
                                                   assembled with wla-z80 -D BANJO_SYS=1 and linked with wlalink,
                                                   and the addresses of the labels the engine calls and reads

    python3 tools/audio/prepare_sms.py [--wladx DIR] [--banjo DIR]

--wladx DIR: a directory with bin/wla-z80 and bin/wlalink (default: tools/audio/fetch_wladx.sh's cache).
--banjo DIR: a joffb/banjo checkout at the pinned commit; the vendored files are checked byte for byte against it
(default: cloned into the cache; --no-banjo-check skips the check when offline).
Rerun after changing the driver pin or the host file."""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
VENDORED = os.path.join(ROOT, "third_party/banjo")
HOST = os.path.join(HERE, "sms")
OUT = os.path.join(ROOT, "app/src/main/cpp/generated/banjo_driver_image.h")

BANJO_COMMIT = "7aaf837ebbe67e567e019b5819b5467bf62253ad"
BANJO_URL = "https://github.com/joffb/banjo.git"
WLADX_VERSION = "v10.7 (91c52b1f4ef3cc8ba3c0638f7536539579af6a9f)"
BANK_SIZE = 0x4000

# Labels the machine (app/src/main/cpp/sms/sms_machine.c) calls or reads: C name -> WLA-DX label.
LABELS = [
    # entry points (banjo/init.inc, play.inc, update.inc, stop.inc)
    ("INIT", "banjo_init"),
    ("PLAY_SONG", "banjo_play_song"),
    ("UPDATE_SONG", "banjo_update_song"),
    ("SONG_STOP", "banjo_song_stop"),
    # per-chip routines a song's music_state points at (sn/, opll/, opll_drums/)
    ("INIT_SN", "banjo_init_sn"),
    ("UPDATE_CHANNELS_SN", "banjo_update_channels_sn"),
    ("MUTE_ALL_SN", "banjo_mute_all_sn"),
    ("INIT_OPLL", "banjo_init_opll"),
    ("INIT_OPLL_DRUMS", "banjo_init_opll_drums"),
    # Thor Tracker's host routines for songs on two chips and the mute tables (thor_banjo_host.asm)
    ("THOR_INIT_SN_OPLL", "thor_init_sn_opll"),
    ("THOR_UPDATE_SN_OPLL", "thor_update_sn_opll"),
    ("THOR_MUTE_ALL_SN_OPLL", "thor_mute_all_sn_opll"),
    ("THOR_INIT_SN_DRUMS", "thor_init_sn_drums"),
    ("THOR_UPDATE_SN_DRUMS", "thor_update_sn_drums"),
    ("THOR_MUTE_ALL_SN_DRUMS", "thor_mute_all_sn_drums"),
    ("THOR_MUTE_CALLS_SN", "thor_mute_calls_sn"),
    ("THOR_MUTE_CALLS_SN_OPLL", "thor_mute_calls_sn_opll"),
    ("THOR_MUTE_CALLS_SN_DRUMS", "thor_mute_calls_sn_drums"),
    # driver RAM (banjo.asm BANJO_RAM, the host's channel array)
    ("MUSIC_FRAMERATE", "music_framerate"),
    ("HAS_CHIPS", "banjo_has_chips"),
    ("SYSTEM_FLAGS", "banjo_system_flags"),
    ("MAX_CHANNELS", "banjo_max_channels"),
    ("SONG_PLAYING", "song_playing"),
    ("SONG_STATE", "song_state"),
    ("SONG_CHANNELS", "song_channels"),
    ("OPLL_DRUM_FIXED_PITCH_MODE", "opll_drum_fixed_pitch_mode"),
]

# The labels above that are driver variables (RAM, $C000-$DFFF); the rest are code or tables in the bank.
RAM_LABELS = {"MUSIC_FRAMERATE", "HAS_CHIPS", "SYSTEM_FLAGS", "MAX_CHANNELS", "SONG_PLAYING", "SONG_STATE",
              "SONG_CHANNELS", "OPLL_DRUM_FIXED_PITCH_MODE"}

# Struct sizes the engine and the Kotlin lowering assume (banjo_defines_wladx.inc).
SIZES = {"_sizeof_channel": 32, "_sizeof_music_state": 34, "_sizeof_instrument": 12}



def log(msg):
    print("prepare_sms: " + msg, file=sys.stderr)


def read(path):
    with open(path, "rb") as f:
        return f.read()


def run(cmd, cwd):
    log(" ".join(cmd))
    r = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if r.returncode != 0:
        sys.stderr.write(r.stdout)
        raise SystemExit("prepare_sms: %s failed (exit %d)" % (cmd[0], r.returncode))
    return r.stdout


def cache_dir():
    base = os.environ.get("THOR_TRACKER_CACHE") or os.path.join(
        os.environ.get("XDG_CACHE_HOME") or os.path.join(os.path.expanduser("~"), ".cache"), "thor-tracker")
    return base


def fetch_banjo():
    dest = os.path.join(cache_dir(), "banjo-" + BANJO_COMMIT[:8])
    if not os.path.isdir(os.path.join(dest, ".git")):
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        subprocess.check_call(["git", "clone", "-q", BANJO_URL, dest])
    subprocess.check_call(["git", "-C", dest, "checkout", "-q", "--detach", BANJO_COMMIT])
    head = subprocess.check_output(["git", "-C", dest, "rev-parse", "HEAD"], text=True).strip()
    if head != BANJO_COMMIT:
        raise SystemExit("prepare_sms: banjo checkout is %s, not %s" % (head, BANJO_COMMIT))
    return dest


def check_vendored(upstream):
    """Every vendored file equals the pinned upstream's (the driver is used unmodified)."""
    n = 0
    for dirpath, _, files in os.walk(VENDORED):
        for name in files:
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, VENDORED)
            other = os.path.join(upstream, rel)
            if not os.path.isfile(other):
                raise SystemExit("prepare_sms: vendored %s is not in banjo %s" % (rel, BANJO_COMMIT[:8]))
            if read(path) != read(other):
                raise SystemExit("prepare_sms: vendored %s differs from banjo %s" % (rel, BANJO_COMMIT[:8]))
            n += 1
    log("%d vendored banjo files match %s" % (n, BANJO_COMMIT[:8]))


def parse_symbols(text):
    """WLA symbol file (wlalink -S): [labels] 'BB:AAAA name', [definitions] 'VVVVVVVV name'."""
    labels, defs = {}, {}
    section = None
    for raw in text.splitlines():
        line = raw.split(";", 1)[0].strip()
        if not line:
            continue
        m = re.match(r"^\[(.+)\]$", line)
        if m:
            section = m.group(1)
            continue
        if section == "labels":
            m = re.match(r"^([0-9a-fA-F]+):([0-9a-fA-F]{4}) (\S+)$", line)
            if m:
                labels[m.group(3)] = int(m.group(2), 16)
        elif section == "definitions":
            m = re.match(r"^([0-9a-fA-F]{8}) (\S+)$", line)
            if m:
                defs[m.group(2)] = int(m.group(1), 16)
    return labels, defs


def assemble(wladx):
    wla = os.path.join(wladx, "bin", "wla-z80")
    link = os.path.join(wladx, "bin", "wlalink")
    with tempfile.TemporaryDirectory() as tmp:
        shutil.copytree(os.path.join(VENDORED, "music_driver"), os.path.join(tmp, "music_driver"))
        for name in ("thor_banjo_host.asm", "thor_banjo_host.link"):
            shutil.copy(os.path.join(HOST, name), tmp)
        run([wla, "-D", "BANJO_SYS=1", "-o", "thor_banjo_host.o", "thor_banjo_host.asm"], tmp)
        run([link, "-S", "-r", "thor_banjo_host.link", "thor_banjo_host.bin"], tmp)
        rom = read(os.path.join(tmp, "thor_banjo_host.bin"))
        sym = read(os.path.join(tmp, "thor_banjo_host.sym")).decode("ascii", "replace")
    return rom, sym


def header(rom, labels, defs):
    missing = [label for _, label in LABELS if label not in labels]
    if missing:
        raise SystemExit("prepare_sms: labels missing from the link: " + ", ".join(missing))
    for name, size in SIZES.items():
        if name not in defs:
            raise SystemExit("prepare_sms: required structure size %s is missing from the link definitions" % name)
        elif defs[name] != size:
            raise SystemExit("prepare_sms: %s is %d, the engine assumes %d" % (name, defs[name], size))
    if len(rom) != BANK_SIZE:
        raise SystemExit("prepare_sms: the ROM is %d bytes, expected one %d-byte bank" % (len(rom), BANK_SIZE))
    for c_name, label in LABELS:
        addr = labels[label]
        ram = c_name in RAM_LABELS
        # The machine keeps its audition song at $D000 and the stack under $DFF0 (sms_machine.h): the driver's
        # variables and the 15 channel structs (480 bytes) must lie below.
        if ram and not 0xC000 <= addr < 0xCE00:
            raise SystemExit("prepare_sms: %s at $%04X is not in the driver's RAM ($C000-$CDFF)" % (label, addr))
        if not ram and addr >= BANK_SIZE:
            raise SystemExit("prepare_sms: %s at $%04X is outside the driver bank" % (label, addr))
    used = len(rom.rstrip(b"\xff"))
    out = []
    out.append("/* Generated by tools/audio/prepare_sms.py - do not edit.")
    out.append(" * Banjo sound driver (joffb/banjo %s, MIT, third_party/banjo, unmodified) with Thor Tracker's host" % BANJO_COMMIT)
    out.append(" * routines (tools/audio/sms/thor_banjo_host.asm), assembled with WLA-DX %s:" % WLADX_VERSION)
    out.append(" * wla-z80 -D BANJO_SYS=1, wlalink -S -r. Bank 0 ($0000-$3FFF, %d bytes used, the rest $FF)." % used)
    out.append(" * sha256 of the bank: %s */" % hashlib.sha256(rom).hexdigest())
    out.append("#ifndef THORTRACKER_BANJO_DRIVER_IMAGE_H_")
    out.append("#define THORTRACKER_BANJO_DRIVER_IMAGE_H_")
    out.append("")
    out.append("#include <stdint.h>")
    out.append("")
    out.append('#define BANJO_COMMIT "%s"' % BANJO_COMMIT)
    out.append("#define BANJO_DRIVER_SIZE 0x%04X" % BANK_SIZE)
    out.append("#define BANJO_DRIVER_USED 0x%04X" % used)
    out.append("")
    for c_name, label in LABELS:
        out.append("#define BANJO_%s 0x%04X /* %s */" % (c_name, labels[label], label))
    out.append("")
    out.append("static const uint8_t BANJO_DRIVER[BANJO_DRIVER_SIZE] = {")
    for i in range(0, BANK_SIZE, 16):
        out.append("    " + ", ".join("0x%02X" % b for b in rom[i:i + 16]) + ",")
    out.append("};")
    out.append("")
    out.append("#endif /* THORTRACKER_BANJO_DRIVER_IMAGE_H_ */")
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--wladx", help="directory with bin/wla-z80 and bin/wlalink")
    ap.add_argument("--banjo", help="joffb/banjo checkout at %s" % BANJO_COMMIT[:8])
    ap.add_argument("--no-banjo-check", action="store_true", help="skip comparing the vendored driver with upstream")
    args = ap.parse_args()
    if not args.no_banjo_check:
        check_vendored(args.banjo or fetch_banjo())
    wladx = args.wladx
    if not wladx:
        wladx = subprocess.check_output([os.path.join(HERE, "fetch_wladx.sh")], text=True).strip().splitlines()[-1]
    rom, sym = assemble(wladx)
    labels, defs = parse_symbols(sym)
    text = header(rom, labels, defs)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        f.write(text)
    log("wrote %s (%d driver bytes used)" % (os.path.relpath(OUT, ROOT), len(rom.rstrip(b"\xff"))))


if __name__ == "__main__":
    main()
