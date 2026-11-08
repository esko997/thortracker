#!/usr/bin/env python3
"""Prepares the YM2612 engine ("ym2612-fm"): the XGM driver image, tables, the Kotlin contract and the host test's
fixtures (Python 3, no dependencies; SGDK's sjasm and xgmtool from tools/audio/fetch_sgdk.sh):

  app/src/main/cpp/generated/xgm_driver_image.h  SGDK's XGM Z80 driver (third_party/sgdk-xgm/drv_xgm.s80, unmodified)
                                                 assembled with SGDK's sjasm; checked byte for byte against the
                                                 driver in SGDK's prebuilt lib/libmd.a
  app/src/main/cpp/generated/ym_tables.h         pitch (F-number, PSG period) and vibrato tables per region
  app/src/test/cpp/ym_test_song.h                test images (tools/audio/ym/ym_song.py), the reference player's
                                                 writes and VGM (tools/audio/ym/ym_ref.py), and xgmtool's XGM/XGC
                                                 of that VGM
  core/.../backend/YmPlayerImage.kt              the image layout, limits, effect codes and loader messages for the
                                                 Kotlin lowering and validator, parsed from the engine's C headers

    python3 tools/audio/prepare_ym.py [--sgdk DIR]
Rerun after changing the driver pin, the player specification, the reference player or the test song."""
import argparse
import hashlib
import os
import re
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(HERE, "ym"))

import ym_ref  # noqa: E402
import ym_song  # noqa: E402

TICKS = 1500
VGM_MAX_TICKS = 36000
CPP = os.path.join(ROOT, "app/src/main/cpp")
VENDORED = os.path.join(ROOT, "third_party/sgdk-xgm")
DRIVER_FILES = {  # vendored name -> path in the SGDK checkout
    "drv_xgm.s80": "src/snd/drv_xgm.s80",
    "z80_def.i80": "inc/snd/z80_def.i80",
    "z80_mac.i80": "inc/snd/z80_mac.i80",
    "z80_fct.i80": "inc/snd/z80_fct.i80",
    "xgm.txt": "bin/xgm.txt",
    "LICENSE.txt": "license.txt",
}
SGDK_COMMIT = "9a78a44143a867cd8c5053b95cd061a21f0b73c2"
KOTLIN = os.path.join(ROOT, "core/src/commonMain/kotlin/org/thortracker/backend/YmPlayerImage.kt")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    with open(path, "rb") as f:
        return f.read()


def fnv64(data):
    h = 0xCBF29CE484222325
    for b in data:
        h = ((h ^ b) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def c_bytes(name, data):
    lines = ["static const unsigned char %s[%d] = {" % (name, len(data))]
    for i in range(0, len(data), 24):
        lines.append("    " + ",".join("%d" % b for b in data[i:i + 24]) + ",")
    lines.append("};")
    return "\n".join(lines)


# ---- the driver ------------------------------------------------------------------------------------------------

def sgdk_dir(given):
    if given:
        return given
    out = subprocess.run([os.path.join(HERE, "fetch_sgdk.sh")], check=True, capture_output=True, text=True).stdout
    return out.strip().splitlines()[-1]


def libmd_driver(libmd):
    """The bytes of symbol drv_xgm in drv_xgm.o inside SGDK's lib/libmd.a (an ar archive of ELF32 m68k objects)."""
    data = read(libmd)
    assert data[:8] == b"!<arch>\n"
    at, names = 8, None
    while at < len(data):
        name, size = data[at:at + 16].decode().strip(), int(data[at + 48:at + 58])
        body = data[at + 60:at + 60 + size]
        if name == "//":
            names = body
        elif name.startswith("/") and name[1:].isdigit() and names is not None:
            name = names[int(name[1:]):].split(b"/\n")[0].decode() + "/"
        if name.rstrip("/") == "drv_xgm.o":
            return elf_symbol(body, "drv_xgm")
        at += 60 + size + (size & 1)
    raise SystemExit("drv_xgm.o not found in %s" % libmd)


def elf_symbol(elf, symbol):
    shoff, = struct.unpack(">I", elf[0x20:0x24])
    shentsize, shnum, shstrndx = struct.unpack(">HHH", elf[0x2E:0x34])
    secs = [struct.unpack(">IIIIIIIIII", elf[shoff + i * shentsize: shoff + i * shentsize + 40]) for i in range(shnum)]
    for s in secs:
        if s[1] != 2:  # SHT_SYMTAB
            continue
        strtab = secs[s[6]]
        for k in range(s[5] // 16):
            n, value, size, info, other, shndx = struct.unpack(">IIIBBH", elf[s[4] + 16 * k: s[4] + 16 * k + 16])
            name = elf[strtab[4] + n: elf.index(b"\0", strtab[4] + n)].decode()
            if name == symbol:
                sec = secs[shndx]
                return elf[sec[4] + value: sec[4] + sec[5]]
    raise SystemExit("symbol %s not found" % symbol)


def write_driver(sgdk):
    src = os.path.join(sgdk, "src")
    for name, rel in DRIVER_FILES.items():
        if read(os.path.join(VENDORED, name)) != read(os.path.join(src, rel)):
            raise SystemExit("third_party/sgdk-xgm/%s differs from SGDK %s %s" % (name, SGDK_COMMIT[:8], rel))
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run([os.path.join(sgdk, "bin/sjasm"), "-i" + VENDORED, os.path.join(VENDORED, "drv_xgm.s80"),
                        os.path.join(tmp, "drv_xgm.o80"), os.path.join(tmp, "out.lst")], check=True,
                       capture_output=True)
        image = read(os.path.join(tmp, "drv_xgm.o80"))
    shipped = libmd_driver(os.path.join(src, "lib/libmd.a"))
    if shipped.rstrip(b"\0") != image.rstrip(b"\0") or len(shipped) != len(image):
        raise SystemExit("assembled driver differs from drv_xgm in SGDK's lib/libmd.a")
    lines = [
        "// GENERATED by tools/audio/prepare_ym.py -- do not edit.",
        "// SGDK's XGM Z80 driver (Stephane Dallongeville, MIT; github.com/Stephane-D/SGDK commit %s):" % SGDK_COMMIT,
        "// third_party/sgdk-xgm/drv_xgm.s80 (sha256 %s), unmodified, assembled with SGDK's" % sha256(read(os.path.join(VENDORED, "drv_xgm.s80"))),
        "// sjasm (tools/sjasm, SjASM 0.39j) at $0000; identical to the drv_xgm the same commit ships in lib/libmd.a.",
        "// image sha256 %s" % sha256(image),
        "#ifndef XGM_DRIVER_IMAGE_H_",
        "#define XGM_DRIVER_IMAGE_H_",
        "#include <stdint.h>",
        "",
        "#define XGM_DRIVER_SIZE %d" % len(image),
        "static const uint8_t XGM_DRIVER[%d] = {" % len(image),
    ]
    for i in range(0, len(image), 24):
        lines.append("    " + ",".join("0x%02X" % b for b in image[i:i + 24]) + ",")
    lines += ["};", "", "#endif /* XGM_DRIVER_IMAGE_H_ */", ""]
    path = os.path.join(CPP, "generated/xgm_driver_image.h")
    with open(path, "w") as f:
        f.write("\n".join(lines))
    print("%s (%d bytes, sha256 %s)" % (path, len(image), sha256(image)))


# ---- tables -----------------------------------------------------------------------------------------------------

def write_tables():
    out = ["/* Generated by tools/audio/prepare_ym.py from tools/audio/ym/ym_ref.py - do not edit. */",
           "#ifndef THORTRACKER_YM_TABLES_H_", "#define THORTRACKER_YM_TABLES_H_", "", "#include <stdint.h>", "",
           "/* F-number of pitch C + i/768 octave per region (0 NTSC, 1 PAL; i = 0..767, pitch units = 1/64 semitone): for",
           " * i < YM_FNUM_SPLIT at block = octave - 1, from it at block = octave. 12-TET, A-4 = 440 Hz, chip rate MCLK / 1008. */",
           "static const int YM_FNUM_SPLIT[2] = {%d, %d};" % (ym_ref.FNUM_SPLIT[0], ym_ref.FNUM_SPLIT[1]),
           "static const uint16_t YM_FNUM[2][768] = {"]
    for r in (0, 1):
        out.append("    {")
        for i in range(0, 768, 16):
            out.append("        " + ", ".join("%4d" % v for v in ym_ref.FNUM[r][i:i + 16]) + ",")
        out.append("    },")
    out += ["};", "",
            "/* PSG period x 256 at octave 0: floor(256 PSG_clock / (32 C0 2^(i/768)) + 0.5), PSG clock = MCLK / 15. */",
            "static const uint32_t YM_PSG_DIV[2][768] = {"]
    for r in (0, 1):
        out.append("    {")
        for i in range(0, 768, 12):
            out.append("        " + ", ".join("%7d" % v for v in ym_ref.PSG_DIV[r][i:i + 12]) + ",")
        out.append("    },")
    out += ["};", "", "/* Vibrato: floor(127 sin(2 pi i / 64) + 0.5). */", "static const int8_t YM_SINE[64] = {"]
    for i in range(0, 64, 16):
        out.append("    " + ", ".join("%4d" % v for v in ym_ref.SINE[i:i + 16]) + ",")
    out += ["};", "", "#endif /* THORTRACKER_YM_TABLES_H_ */", ""]
    path = os.path.join(CPP, "generated/ym_tables.h")
    with open(path, "w") as f:
        f.write("\n".join(out))
    print(path)


# ---- fixtures ---------------------------------------------------------------------------------------------------

def used(image):
    """Bytes up to the end of the pattern data (the rest of an image is zero)."""
    size = image[14] | image[15] << 8
    end = ym_song.OFF_DATA + size
    assert not any(image[end:])
    return image[:end]


def trace_arrays(prefix, image_bytes):
    tr = ym_ref.trace(ym_ref.Image(image_bytes), TICKS)
    offsets, kinds, regs, vals, pos, splits = [0], [], [], [], [], []
    for p, writes, split in tr:
        for kind, reg, val in writes:
            kinds.append(kind)
            regs.append(reg)
            vals.append(val)
        offsets.append(len(regs))
        splits.append(split)
        pos.extend(p if p is not None else (-1, -1, -1))
    out = ["static const int %s_offsets[%d] = {%s};" % (prefix, len(offsets), ",".join(map(str, offsets))),
           "static const unsigned char %s_kinds[%d] = {%s};" % (prefix, max(1, len(kinds)), ",".join(map(str, kinds)) or "0"),
           "static const unsigned char %s_regs[%d] = {%s};" % (prefix, max(1, len(regs)), ",".join(map(str, regs)) or "0"),
           "static const unsigned char %s_vals[%d] = {%s};" % (prefix, max(1, len(vals)), ",".join(map(str, vals)) or "0"),
           "static const short %s_pos[%d] = {%s};" % (prefix, len(pos), ",".join(map(str, pos))),
           "static const unsigned char %s_offs[%d] = {%s};" % (prefix, len(splits), ",".join(map(str, splits)))]
    return "\n".join(out), len(regs)


def byte_offsets(image):
    """Image offsets of the first mask, note, instrument and effect bytes and of the first parameter of some effects,
    for the host test's single-byte corruptions (channel kind: the first FM, PSG and Noise use)."""
    img = ym_ref.Image(image)
    found = {}
    size = image[14] | image[15] << 8
    for o in range(img.orders):
        for c in range(10):
            at = ym_song.OFF_DATA + img.patterns[o][c]
            rows = 0
            while rows < img.rows[o] and at < ym_song.OFF_DATA + size:
                m = image[at]
                if m & 0x80:
                    rows += (m & 0x7F) + 1
                    at += 1
                    continue
                kind = "FM" if c < 6 else "NOISE" if c == 9 else "PSG"
                found.setdefault("MASK", at)
                at += 1
                if m & 1:
                    found.setdefault("NOTE", at)
                    at += 1
                if m & 2:
                    found.setdefault("INST_" + kind, at)
                    at += 1
                for _ in range((m >> 2) & 7):
                    found.setdefault("EFFECT", at)
                    letter = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"[image[at]]
                    found.setdefault("PARAM_%s_%s" % (letter, kind), at + 1)
                    at += 2
                rows += 1
    return found


def xgmtool(sgdk, vgm, ext):
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = os.path.join(tmp, "song.vgm"), os.path.join(tmp, "song." + ext)
        with open(src, "wb") as f:
            f.write(vgm)
        subprocess.run([os.path.join(sgdk, "bin/xgmtool"), src, dst, "-s"], check=True, capture_output=True, cwd=tmp)
        return read(dst)


def write_fixture(sgdk):
    images = {
        "song": ym_song.build(ym_song.test_song()),
        "moved": ym_song.build(ym_song.test_song(), relayout=True),
        "edited": ym_song.build(ym_song.edited_song()),
        "tempo": ym_song.build(ym_song.tempo_song()),
        "short": ym_song.build(ym_song.short_song()),
        "pal": ym_song.build(ym_song.pal_song()),
    }
    assert images["moved"] != images["song"]
    parts = ["/* Generated by tools/audio/prepare_ym.py - do not edit. YM2612 host-test fixtures: the test song",
             " * (tools/audio/ym/ym_song.py) as images, the reference player's (ym_ref.py) writes and VGM, and SGDK",
             " * xgmtool's XGM and XGC of that VGM (commit %s). */" % SGDK_COMMIT[:8],
             "#define YM_REF_TICKS %d" % TICKS]
    for name, data in images.items():
        parts.append(c_bytes("ym_%s_image" % name, used(data)))
    total = 0
    for name in ("song", "edited", "tempo", "pal"):
        text, n = trace_arrays("ym_ref_" + name, images[name])
        parts.append(text)
        total += n
    for name in ("song", "pal"):
        data, info = ym_ref.vgm(ym_ref.Image(images[name]), VGM_MAX_TICKS)
        key = name.upper()
        parts.append("#define YM_REF_%s_VGM_SIZE %du" % (key, len(data)))
        parts.append("#define YM_REF_%s_VGM_FNV 0x%016XULL" % (key, fnv64(data)))
        parts.append("#define YM_REF_%s_VGM_TICKS %du" % (key, info["ticks"]))
        parts.append("#define YM_REF_%s_VGM_LOOP_OFFSET %du" % (key, info["loop_offset"]))
        parts.append("#define YM_REF_%s_VGM_LOOP_SAMPLES %du" % (key, info["loop_samples"]))
        parts.append("#define YM_REF_%s_VGM_SAMPLES %du" % (key, info["samples"]))
        xgm, xgc = xgmtool(sgdk, data, "xgm"), xgmtool(sgdk, data, "bin")
        parts.append("#define YM_XGMTOOL_%s_XGM_SIZE %du" % (key, len(xgm)))
        parts.append("#define YM_XGMTOOL_%s_XGM_FNV 0x%016XULL" % (key, fnv64(xgm)))
        parts.append("#define YM_XGMTOOL_%s_XGC_SIZE %du" % (key, len(xgc)))
        parts.append("#define YM_XGMTOOL_%s_XGC_FNV 0x%016XULL" % (key, fnv64(xgc)))
        if name == "song":
            parts.append(c_bytes("ym_xgmtool_song_xgc", xgc))
            print("song: VGM %d bytes, %d ticks, loop %d samples; xgmtool XGM %d bytes, XGC %d bytes" % (
                len(data), info["ticks"], info["loop_samples"], len(xgm), len(xgc)))
    for key, offset in sorted(byte_offsets(images["song"]).items()):
        parts.append("#define YM_OFFSET_%s %d" % (key, offset))
    path = os.path.join(ROOT, "app/src/test/cpp/ym_test_song.h")
    with open(path, "w") as f:
        f.write("\n".join(parts) + "\n")
    print("%s (%d reference writes)" % (path, total))


# ---- Kotlin -----------------------------------------------------------------------------------------------------

def c_constants():
    """#define YM*_NAME values and enum YM_FX_* codes of the YM engine headers, evaluated."""
    values = {}
    for name in ("ym/ym_image.h", "ym/ym_player.h", "ym/ym_machine.h"):
        with open(os.path.join(CPP, name)) as f:
            for line in f:
                m = re.match(r"#define (YM[MP]?_\w+) +([^/]+?)\s*(/\*.*)?$", line.rstrip())
                if m and not m.group(1).endswith("_H_"):
                    expr = re.sub(r"\b(\d+|0x[0-9A-Fa-f]+)u\b", r"\1", m.group(2))
                    try:
                        values[m.group(1)] = eval(expr, {}, dict(values))
                    except (NameError, SyntaxError):
                        pass
                m = re.match(r"\s+(YM_FX_\w+) = (\d+),", line)
                if m:
                    values[m.group(1)] = int(m.group(2))
    return values


def loader_problems():
    """Every reason string ym_load_image can report (ym_image.c), in source order."""
    with open(os.path.join(CPP, "ym/ym_image.c")) as f:
        text = f.read()
    found = []
    for line in text.splitlines():
        if "fail(image," in line or "why =" in line:
            for s in re.findall(r'"([^"]+)"', line):
                if s != "TTYM" and s not in found:
                    found.append(s)
    return found


def gd3_strings():
    with open(os.path.join(CPP, "ym/ym_vgm.c")) as f:
        text = f.read()
    return {k: re.search(r'#define GD3_%s "([^"]+)"' % k, text).group(1) for k in ("SYSTEM", "CONVERTER")}


def write_kotlin():
    c = c_constants()
    fx = {k[len("YM_FX_"):]: v for k, v in c.items() if k.startswith("YM_FX_")}
    letters = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    gd3 = gd3_strings()

    def kt(name, key=None):
        return "    const val %s = %s" % (name, c[key or "YM_" + name])

    def table(name, values, per=24):
        lines = ["    val %s: IntArray get() = intArrayOf(" % name]
        for i in range(0, len(values), per):
            lines.append("        " + ", ".join(str(v) for v in values[i:i + per]) + ",")
        return lines + ["    )"]

    lines = [
        "// GENERATED by tools/audio/prepare_ym.py from the engine headers (app/src/main/cpp/ym) -- do not edit.",
        "package org.thortracker.backend",
        "",
        "/**",
        " * The YM2612 (Mega Drive) system's native contract: Thor Tracker's own player streamed through SGDK's XGM",
        " * driver on a Z80 + YM2612 + SN76489, engine `\"ym2612-fm\"`. The image layout the lowering writes, its limits,",
        " * the effect codes and the loader's rejection messages, as `ym_image.h`, `ym_player.h` and `ym_machine.h`",
        " * define them (`docs/AUDIO_ENGINE.md`, \"YM2612\": the specification). All multi-byte fields are little-endian.",
        " */",
        "object YmPlayerImage {",
        "    /** Native engine id ([org.thortracker.system.PlaybackEngine.id]). */",
        '    const val ENGINE_ID = "ym2612-fm"',
        "",
        "    /** One fixed-size image per song. */",
        kt("IMAGE_SIZE"),
        '    const val MAGIC = "TTYM"',
        kt("LAYOUT"),
        "",
        "    /** Header field offsets (64-byte header; byte 13 and bytes 16..63 zero). */",
    ]
    for name in ("H_MAGIC", "H_LAYOUT", "H_REGION", "H_SPEED", "H_LFO", "H_ORDERS", "H_LOOP", "H_INSTRUMENTS",
                 "H_DATA_SIZE", "HEADER_SIZE"):
        lines.append(kt(name))
    lines += [
        "",
        "    /** Header byte [H_REGION]: NTSC (60 Hz, the default) or PAL (50 Hz). [H_LFO]: register $22, 0 = off or",
        "     * [LFO_ON] | rate 0..7. */",
        "    const val NTSC = 0",
        "    const val PAL = 1",
        "    const val LFO_ON = 0x08",
        "",
        "    /**",
        "     * Fixed tables: rows of order o at [OFF_ROWS] + o (1..255, 0 = 256); the pattern of (order o, channel c) as a",
        "     * u16 offset into the data region at [OFF_ORDERS] + 2 (10 o + c); instrument slot n (1..64) at",
        "     * [OFF_INSTRUMENTS] + [INSTRUMENT_SIZE] (n - 1); pattern data from [OFF_DATA], [DATA_CAPACITY] bytes.",
        "     */",
    ]
    for name in ("OFF_ROWS", "OFF_ORDERS", "OFF_INSTRUMENTS", "INSTRUMENT_SIZE", "OFF_DATA"):
        lines.append(kt(name))
    lines.append("    const val DATA_CAPACITY = %d" % c["YM_DATA_CAPACITY"])
    lines += [
        "",
        "    /**",
        "     * Instrument record: flags ([FLAG_DEFINED], [FLAG_PSG]; all-zero = no instrument). FM: algorithm 0..7,",
        "     * operator-1 feedback 0..7, AMS/FMS (register $B4 without pan), operator k (0..3 = operators 1..4) at [I_OPS] +",
        "     * 7 k = registers $30 $40 $50 $60 $70 $80 $90 (reserved bits 0). PSG: attack, decay (ticks per",
        "     * envelope step), sustain level 0..15, release, noise mode (bit 2 white, bits 0-1 source; 3 = PSG 3's period),",
        "     * bytes 6..31 zero. Both: drum sweep start/end (s8 semitones -96..96 from the note) and speed (1/16 semitone",
        "     * per tick, 0 = none) at [I_SWEEP_START]..[I_SWEEP_SPEED], bytes 35..39 zero.",
        "     */",
    ]
    for name in ("I_FLAGS", "FLAG_DEFINED", "FLAG_PSG", "I_ALG", "I_FEEDBACK", "I_AMS_FMS", "I_OPS", "OP_BYTES",
                 "I_ATTACK", "I_DECAY", "I_SUSTAIN", "I_RELEASE", "I_NOISE", "I_SWEEP_START", "I_SWEEP_END",
                 "I_SWEEP_SPEED"):
        lines.append(kt(name))
    lines += [
        "    const val SWEEP_LIMIT = 96",
        "    /** Register offsets of operators 1..4 from the channel's. */",
        "    val OPERATOR_OFFSET: IntArray get() = intArrayOf(0, 8, 4, 12)",
        "    /** Carrier operators per algorithm (bit k = operator k + 1). */",
        "    val CARRIERS: IntArray get() = intArrayOf(8, 8, 8, 8, 10, 14, 14, 15)",
        "",
        "    /**",
        "     * Pattern rows: [RUN] | k = k + 1 empty rows (k 0..127, [MAX_RUN] rows at most); else a mask byte: [MASK_NOTE]",
        "     * (note byte follows), [MASK_INSTRUMENT] (slot byte follows), effect count 0..4 at bits 2-4 ([MASK_EFFECT_SHIFT]);",
        "     * then the note (0..95 absolute, [NOTE_OFF]), the slot, and per effect column code (0..35 = '0'..'9', 'A'..'Z')",
        "     * and parameter. Each (order, channel) decodes exactly rows(order) rows inside the used data.",
        "     */",
        "    const val RUN = 0x80",
        "    const val MAX_RUN = 128",
        "    const val MASK_NOTE = 0x01",
        "    const val MASK_INSTRUMENT = 0x02",
        "    const val MASK_EFFECT_SHIFT = 2",
        kt("NOTE_OFF"),
        kt("NOTES"),
        "",
        "    /** Limits. Channels 0..5 FM 1-6 (FM slots), 6..8 PSG 1-3 and 9 Noise (PSG slots). */",
        kt("CHANNELS"),
        kt("FM_CHANNELS"),
        kt("CH_PSG"),
        kt("CH_NOISE"),
        kt("MAX_ORDERS"),
        kt("MAX_INSTRUMENTS"),
        kt("MAX_EFFECTS"),
        "",
        "    /** Pitch units: 1/64 semitone, 0 = C-0, note n = 64 n; [PITCH_MAX] just below C-8. Fine pitch (Pxx, audition) -128..127. */",
        "    const val PITCH_UNITS_PER_SEMITONE = 64",
        kt("PITCH_MAX", "YMP_PITCH_MAX"),
        "",
        "    /** Effect codes (column code byte); [EFFECT_LETTERS][code] is the name. */",
        '    const val EFFECT_LETTERS = "%s"' % letters,
    ]
    for name, v in sorted(fx.items(), key=lambda kv: kv[1]):
        lines.append("    const val FX_%s = %d // %s" % (name, v, letters[v]))
    lines += [
        "",
        "    /** Timing: one tick per vertical interrupt, [MCLK_PER_LINE] x [LINES_NTSC] (or [LINES_PAL]) master clocks. */",
        kt("MCLK_NTSC", "YMM_MCLK_NTSC"),
        kt("MCLK_PAL", "YMM_MCLK_PAL"),
        kt("MCLK_PER_LINE", "YMM_MCLK_PER_LINE"),
        kt("LINES_NTSC", "YMM_LINES_NTSC"),
        kt("LINES_PAL", "YMM_LINES_PAL"),
        "    /** Seek: [org.thortracker.system.PlaybackEngine.seekTicks] budget; the last [PREROLL_TICKS] ticks stream through the driver. */",
        "    const val SEEK_TICKS = 12000",
        kt("PREROLL_TICKS", "YMM_PREROLL_TICKS"),
        "",
        "    /** Exports (native `ym_export`): formats and the GD3 system and converter names the files carry. */",
        "    const val EXPORT_VGM = 0",
        "    const val EXPORT_XGM = 1",
        "    const val EXPORT_XGC = 2",
        '    const val GD3_SYSTEM = "%s"' % gd3["SYSTEM"],
        '    const val GD3_CONVERTER = "%s"' % gd3["CONVERTER"],
        "",
        "    /**",
        "     * F-number of pitch C + i/768 octave per region: below the split at block = octave - 1, from it at block = octave",
        "     * (block -1 = block 0 with the F-number halved). Frequency = F-number x (MCLK / 1008) / 2^(21 - block).",
        "     */",
        "    const val FNUM_SPLIT_NTSC = %d" % ym_ref.FNUM_SPLIT[0],
        "    const val FNUM_SPLIT_PAL = %d" % ym_ref.FNUM_SPLIT[1],
    ]
    lines += table("FNUM_NTSC", ym_ref.FNUM[0]) + table("FNUM_PAL", ym_ref.FNUM[1])
    lines += [
        "",
        "    /**",
        "     * Every reason the native loader (`ym_load_image`) rejects an image with (`ym_image.c` order). A song the Kotlin",
        "     * validator accepts must never produce one; mirror the checks with user-facing messages.",
        "     */",
        "    val LOADER_PROBLEMS: List<String> = listOf(",
    ]
    for s in loader_problems():
        lines.append('        "%s",' % s)
    lines += ["    )", "}", ""]
    with open(KOTLIN, "w") as f:
        f.write("\n".join(lines))
    print(KOTLIN)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--sgdk", help="cache directory of tools/audio/fetch_sgdk.sh (src/, bin/sjasm, bin/xgmtool)")
    args = ap.parse_args(argv)
    sgdk = sgdk_dir(args.sgdk)
    write_driver(sgdk)
    write_tables()
    write_fixture(sgdk)
    write_kotlin()
    return 0


if __name__ == "__main__":
    sys.exit(main())
