#!/usr/bin/env python3
"""Generates the OPM engine's host-test fixtures and the Kotlin constants (pure Python 3, no dependencies):

  app/src/test/cpp/opm_test_song.h           test images (tools/audio/opm/opm_song.py), the reference player's
                                             (tools/audio/opm/opm_ref.py) reset batch, per-tick writes, key-off
                                             phase lengths and positions, its VGM's size, hash and loop, and byte
                                             offsets for the loader's single-byte corruption checks
  core/.../backend/OpmPlayerImage.kt         the image layout, limits, effect codes, pitch, seek and GD3 constants
                                             and loader messages for the Kotlin lowering and validator, parsed from
                                             the engine's C sources (app/src/main/cpp/opm)

    python3 tools/audio/prepare_opm.py
No table header is generated: the vibrato sine is written out in opm_player.c and opm_ref.py. Rerun after changing
the player specification, the reference player, the test song or the engine headers."""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(HERE, "opm"))

import opm_ref  # noqa: E402
import opm_song  # noqa: E402

TICKS = 1500
VGM_MAX_TICKS = 36000
CPP = os.path.join(ROOT, "app/src/main/cpp")
FIXTURE = os.path.join(ROOT, "app/src/test/cpp/opm_test_song.h")
KOTLIN = os.path.join(ROOT, "core/src/commonMain/kotlin/org/thortracker/backend/OpmPlayerImage.kt")
CORRUPT_PARAMS = "BCEFHIMNQTVWX"


def c_bytes(name, data):
    lines = ["static const unsigned char %s[%d] = {" % (name, len(data))]
    for i in range(0, len(data), 24):
        lines.append("    " + ",".join("%d" % b for b in data[i:i + 24]) + ",")
    lines.append("};")
    return "\n".join(lines)


def c_list(kind, name, values):
    return "static const %s %s[%d] = {%s};" % (kind, name, max(1, len(values)), ",".join(map(str, values)) or "0")


def used(image):
    """Bytes up to the end of the pattern data (the rest of an image is zero)."""
    end = opm_song.OFF_DATA + (image[14] | image[15] << 8)
    assert not any(image[end:])
    return image[:end]


def trace_arrays(prefix, image_bytes):
    tr = opm_ref.trace(opm_ref.Image(image_bytes), TICKS)
    offsets, regs, vals, pos, offs = [0], [], [], [], []
    for p, writes, split in tr:
        for reg, val in writes:
            regs.append(reg)
            vals.append(val)
        offsets.append(len(regs))
        offs.append(split)
        pos.extend(p)
    assert len(offsets) == TICKS + 1 and len(pos) == 3 * TICKS
    out = [
        c_list("int", prefix + "_offsets", offsets),
        c_list("unsigned char", prefix + "_regs", regs),
        c_list("unsigned char", prefix + "_vals", vals),
        c_list("short", prefix + "_pos", pos),
        c_list("unsigned char", prefix + "_offs", offs),
    ]
    return "\n".join(out), len(regs)


def fnv64(data):
    h = 0xCBF29CE484222325
    for b in data:
        h = ((h ^ b) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def rows_with_offsets(image, start, end):
    """Every encoded row from data offset `start` up to `end`: (mask, note, slot, [(code, param)]) as image
    offsets of each byte (None where absent). Runs are skipped."""
    at = opm_song.OFF_DATA + start
    stop = opm_song.OFF_DATA + end
    while at < stop:
        m = image[at]
        if m & 0x80:
            at += 1
            continue
        mask, at = at, at + 1
        note = slot = None
        if m & 1:
            note, at = at, at + 1
        if m & 2:
            slot, at = at, at + 1
        effects = []
        for _ in range((m >> 2) & 7):
            effects.append((at, at + 1))
            at += 2
        yield mask, note, slot, effects


def byte_offsets(image):
    """Image offsets of the first mask, note, instrument and effect bytes, of the first parameter byte of each
    effect in CORRUPT_PARAMS, and of the first FM 8 instrument byte naming a noise instrument, for the host test's
    single-byte corruptions."""
    img = opm_ref.Image(image)
    found = {}
    for mask, note, slot, effects in rows_with_offsets(image, 0, img.data_size):
        found.setdefault("MASK", mask)
        if note is not None:
            found.setdefault("NOTE", note)
        if slot is not None:
            found.setdefault("INST", slot)
        for code_at, param_at in effects:
            found.setdefault("EFFECT", code_at)
            letter = opm_ref.LETTERS[image[code_at]]
            if letter in CORRUPT_PARAMS:
                found.setdefault("PARAM_" + letter, param_at)
    for o in range(img.orders):
        if "NOISE_INST" in found:
            break
        start = img.patterns[o][opm_ref.NOISE_CHANNEL]
        rows = 0
        at = opm_song.OFF_DATA + start
        while rows < img.rows[o]:
            m = image[at]
            if m & 0x80:
                rows += (m & 0x7F) + 1
                at += 1
                continue
            slot_at = at + 1 + (m & 1) if m & 2 else None
            if slot_at is not None:
                ins = img.instrument(image[slot_at])
                if ins["noise"]:
                    found["NOISE_INST"] = slot_at
                    break
            at += 1 + (m & 1) + (m >> 1 & 1) + 2 * ((m >> 2) & 7)
            rows += 1
    need = ["MASK", "NOTE", "INST", "EFFECT", "NOISE_INST"] + ["PARAM_" + c for c in CORRUPT_PARAMS]
    missing = [k for k in need if k not in found]
    assert not missing, "the test song lacks %s" % missing
    return {k: found[k] for k in need}


def write_fixture():
    images = {
        "song": opm_song.build(opm_song.test_song()),
        "moved": opm_song.build(opm_song.test_song(), relayout=True),
        "edited": opm_song.build(opm_song.edited_song()),
        "tempo": opm_song.build(opm_song.tempo_song()),
        "short": opm_song.build(opm_song.short_song()),
        "x2": opm_song.build(opm_song.x2_song()),
        "lfo": opm_song.build(opm_song.lfo_song()),
    }
    assert images["moved"] != images["song"]
    for data in images.values():
        opm_ref.Image(data)  # every fixture passes the reference loader
    parts = ["/* Generated by tools/audio/prepare_opm.py - do not edit. OPM host-test fixtures: the test song",
             " * (tools/audio/opm/opm_song.py) as images, and the reference player's (opm_ref.py) writes: per tick t,",
             " * writes regs/vals[offsets[t] .. offsets[t + 1]) of which the first offs[t] are the key-off phase,",
             " * pos[3t .. 3t + 2] = (order, row, tick) before the tick. The reset batch is separate. */",
             "#define OPM_REF_TICKS %d" % TICKS]
    for name, data in images.items():
        parts.append(c_bytes("opm_%s_image" % name, used(data)))
    reset = opm_ref.reset_writes(opm_ref.Image(images["song"]))
    parts.append("#define OPM_REF_RESET_WRITES %d" % len(reset))
    parts.append(c_list("unsigned char", "opm_ref_reset_regs", [r for r, _ in reset]))
    parts.append(c_list("unsigned char", "opm_ref_reset_vals", [v for _, v in reset]))
    total = 0
    for name in ("song", "edited", "tempo"):
        text, n = trace_arrays("opm_ref_" + name, images[name])
        parts.append(text)
        total += n
    data, info = opm_ref.vgm(opm_ref.Image(images["song"]), VGM_MAX_TICKS)
    parts.append("#define OPM_REF_VGM_SIZE %du" % len(data))
    parts.append("#define OPM_REF_VGM_FNV 0x%016XULL" % fnv64(data))
    parts.append("#define OPM_REF_VGM_TICKS %du" % info["ticks"])
    parts.append("#define OPM_REF_VGM_LOOP_OFFSET %du" % info["loop_offset"])
    parts.append("#define OPM_REF_VGM_LOOP_SAMPLES %du" % info["loop_samples"])
    parts.append("#define OPM_REF_VGM_SAMPLES %du" % info["samples"])
    for key, offset in byte_offsets(images["song"]).items():
        parts.append("#define OPM_OFFSET_%s %d" % (key, offset))
    with open(FIXTURE, "w") as f:
        f.write("\n".join(parts) + "\n")
    print("%s (%d reference writes; VGM %d bytes, %d ticks, loop %d samples)" % (FIXTURE, total, len(data),
                                                                                 info["ticks"], info["loop_samples"]))


def c_constants():
    """#define OM_/OMP_/OMM_ values and enum OM_FX_* codes of the OPM engine headers, evaluated."""
    values = {}
    for name in ("opm/opm_image.h", "opm/opm_player.h", "opm/opm_machine.h"):
        with open(os.path.join(CPP, name)) as f:
            for line in f:
                m = re.match(r"#define (OM[MP]?_\w+) +([^/]+?)\s*(/\*.*)?$", line.rstrip())
                if m:
                    expr = re.sub(r"\b(\d+|0x[0-9A-Fa-f]+)u\b", r"\1", m.group(2))
                    values[m.group(1)] = eval(expr, {}, dict(values))
                m = re.match(r"\s+(OM_FX_\w+) = (\d+),", line)
                if m:
                    values[m.group(1)] = int(m.group(2))
    return values


def loader_problems():
    """Every reason string om_load_image can report (opm_image.c), in source order."""
    with open(os.path.join(CPP, "opm/opm_image.c")) as f:
        text = f.read()
    found = []
    for m in re.finditer(r'(?:fail\(image, |why = )"([^"]+)"', text):
        if m.group(1) not in found:
            found.append(m.group(1))
    assert found, "no loader reasons found in opm_image.c"
    return found


def gd3_strings():
    with open(os.path.join(CPP, "opm/opm_vgm.c")) as f:
        text = f.read()
    return {k: re.search(r'#define GD3_%s "([^"]+)"' % k, text).group(1) for k in ("SYSTEM", "CONVERTER")}


def kotlin_string(s):
    return '"%s"' % s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def check_reference(c):
    """The reference player and builder must use the C headers' numbers."""
    pairs = {
        "OM_IMAGE_SIZE": opm_ref.IMAGE_SIZE, "OM_LAYOUT": opm_ref.LAYOUT, "OM_CHANNELS": opm_ref.CHANNELS,
        "OM_NOISE_CHANNEL": opm_ref.NOISE_CHANNEL, "OM_MAX_ORDERS": opm_ref.MAX_ORDERS,
        "OM_MAX_INSTRUMENTS": opm_ref.MAX_INSTRUMENTS, "OM_MAX_EFFECTS": opm_ref.MAX_EFFECTS,
        "OM_MAX_MULTISPEED": opm_ref.MAX_MULTISPEED, "OM_H_MULTISPEED": opm_ref.H_MULTISPEED,
        "OM_H_SPEED": opm_ref.H_SPEED, "OM_H_LFO_WAVE": opm_ref.H_LFO_WAVE, "OM_H_ORDERS": opm_ref.H_ORDERS,
        "OM_H_LOOP": opm_ref.H_LOOP, "OM_H_INSTRUMENTS": opm_ref.H_INSTRUMENTS, "OM_H_CSM": opm_ref.H_CSM,
        "OM_H_DATA_SIZE": opm_ref.H_DATA_SIZE, "OM_H_LFO_RATE": opm_ref.H_LFO_RATE, "OM_H_AMD": opm_ref.H_AMD,
        "OM_H_PMD": opm_ref.H_PMD, "OM_H_TIMER_A": opm_ref.H_TIMER_A, "OM_HEADER_SIZE": opm_ref.HEADER_SIZE,
        "OM_OFF_ROWS": opm_song.OFF_ROWS, "OM_OFF_ORDERS": opm_song.OFF_ORDERS,
        "OM_OFF_INSTRUMENTS": opm_song.OFF_INSTRUMENTS, "OM_OFF_DATA": opm_song.OFF_DATA,
        "OM_INSTRUMENT_SIZE": opm_song.INSTRUMENT_SIZE, "OM_DATA_CAPACITY": opm_ref.DATA_CAPACITY,
        "OM_I_FLAGS": opm_ref.I_FLAGS, "OM_I_CONNECT": opm_ref.I_CONNECT, "OM_I_SENS": opm_ref.I_SENS,
        "OM_I_NOISE": opm_ref.I_NOISE, "OM_I_OPS": opm_ref.I_OPS, "OM_OPERATOR_BYTES": opm_ref.OPERATOR_BYTES,
        "OM_I_SWEEP_START": opm_ref.I_SWEEP_START, "OM_I_SWEEP_END": opm_ref.I_SWEEP_END,
        "OM_I_SWEEP_SPEED": opm_ref.I_SWEEP_SPEED, "OM_FLAG_DEFINED": opm_ref.FLAG_DEFINED,
        "OM_FLAG_NOISE": opm_ref.FLAG_NOISE, "OM_NOTE_OFF": opm_ref.NOTE_OFF, "OM_NOTE_MIN": opm_ref.NOTE_MIN,
        "OM_NOTE_MAX": opm_ref.NOTE_MAX, "OMP_PITCH_OFFSET": opm_ref.PITCH_OFFSET,
        "OMP_PITCH_MIN": opm_ref.PITCH_MIN, "OMP_PITCH_MAX": opm_ref.PITCH_MAX, "OMP_KEY_ALL": opm_ref.KEY_ALL,
        "OMP_SHADOW_PMD": opm_ref.SHADOW_PMD, "OMM_CLOCK": opm_ref.CLOCK,
        "OMM_NATIVE_DIVIDER": opm_ref.NATIVE_DIVIDER,
    }
    bad = ["%s: C %s, reference %s" % (k, c.get(k), v) for k, v in pairs.items() if c.get(k) != v]
    for name, code in c.items():
        if name.startswith("OM_FX_"):
            if code not in opm_ref.EFFECTS:
                bad.append("%s = %d is not an effect of the reference player" % (name, code))
    if len([k for k in c if k.startswith("OM_FX_")]) != len(opm_ref.EFFECTS):
        bad.append("the C effect list and the reference player's differ in length")
    assert not bad, "C headers and reference disagree:\n  " + "\n  ".join(bad)


def write_kotlin():
    c = c_constants()
    check_reference(c)
    fx = {k[len("OM_FX_"):]: v for k, v in c.items() if k.startswith("OM_FX_")}
    letters = opm_ref.LETTERS
    gd3 = gd3_strings()

    def kt(name, key=None):
        return "    const val %s = %s" % (name, c[key or "OM_" + name])

    def ints(values):
        return "intArrayOf(%s)" % ", ".join(str(v) for v in values)

    lines = [
        "// GENERATED by tools/audio/prepare_opm.py from the engine sources (app/src/main/cpp/opm) -- do not edit.",
        "package org.thortracker.backend",
        "",
        "/**",
        " * The OPM system's native contract: Thor Tracker's own player on a YM2151 (ymfm) at the X68000's 4 MHz, engine",
        " * `\"ym2151-opm\"`. The image layout the lowering writes, its limits, the effect codes and the loader's rejection",
        " * messages, as `opm_image.h`, `opm_player.h` and `opm_machine.h` define them (`docs/AUDIO_ENGINE.md`, \"OPM\": the",
        " * specification). All multi-byte fields are little-endian.",
        " */",
        "object OpmPlayerImage {",
        "    /** Native engine id ([org.thortracker.system.PlaybackEngine.id]). */",
        '    const val ENGINE_ID = "ym2151-opm"',
        "",
        "    /** One fixed-size image per song. */",
        kt("IMAGE_SIZE"),
        '    const val MAGIC = "TTOM"',
        kt("LAYOUT"),
        "",
        "    /**",
        "     * Header field offsets (64 bytes; byte 19 and bytes 22..63 zero): multispeed 1..8, speed 1..255, LFO waveform",
        "     * 0..3, u16 order count and loop order, instrument count (highest slot), CSM 0/1, u16 data size, LFO rate",
        "     * (LFRQ), AMD 0..127, PMD 0..127, u16 timer A 0..1023: the chip's shared state at the song start.",
        "     */",
    ]
    for name in ("H_MAGIC", "H_LAYOUT", "H_MULTISPEED", "H_SPEED", "H_LFO_WAVE", "H_ORDERS", "H_LOOP",
                 "H_INSTRUMENTS", "H_CSM", "H_DATA_SIZE", "H_LFO_RATE", "H_AMD", "H_PMD", "H_TIMER_A", "HEADER_SIZE"):
        lines.append(kt(name))
    lines += [
        "",
        "    /**",
        "     * Fixed tables: rows of order o at [OFF_ROWS] + o (1..255, 0 = 256); the pattern of (order o, channel c) as a",
        "     * u16 offset into the data region at [OFF_ORDERS] + 2 (8 o + c); instrument slot n (1..64) at [OFF_INSTRUMENTS]",
        "     * + [INSTRUMENT_SIZE] (n - 1); pattern data from [OFF_DATA], [DATA_CAPACITY] bytes.",
        "     */",
    ]
    for name in ("OFF_ROWS", "OFF_ORDERS", "OFF_INSTRUMENTS", "INSTRUMENT_SIZE", "OFF_DATA"):
        lines.append(kt(name))
    lines.append("    const val DATA_CAPACITY = %d" % c["OM_DATA_CAPACITY"])
    lines += [
        "",
        "    /**",
        "     * Instrument record: flags ([FLAG_DEFINED], [FLAG_NOISE]; all-zero = no instrument), register 0x20 bits 0-5",
        "     * (feedback << 3 | algorithm), register 0x38 (PMS << 4 | AMS), noise frequency 0..31 (0 without noise),",
        "     * operator k (0..3 = operators 1..4 = M1, C1, M2, C2) at [I_OPS] + [OPERATOR_BYTES] k = registers 0x40, 0x60,",
        "     * 0x80, 0xA0, 0xC0, 0xE0, drum sweep start/end (s8 semitones -96..96 from the note) and speed (1/16 semitone",
        "     * per tick, 0 = none), byte 31 zero.",
        "     */",
    ]
    for name in ("I_FLAGS", "I_CONNECT", "I_SENS", "I_NOISE", "I_OPS", "OPERATOR_BYTES", "I_SWEEP_START",
                 "I_SWEEP_END", "I_SWEEP_SPEED", "FLAG_DEFINED", "FLAG_NOISE"):
        lines.append(kt(name))
    lines += [
        "    const val SWEEP_LIMIT = %d" % opm_ref.SWEEP_LIMIT,
        "",
        "    /**",
        "     * Pattern rows: [RUN] | k = k + 1 empty rows (k 0..127, [MAX_RUN] rows at most); else a mask byte: [MASK_NOTE]",
        "     * (note byte follows), [MASK_INSTRUMENT] (slot byte follows), effect count 0..4 at bits 2-4 ([MASK_EFFECT_SHIFT]);",
        "     * then the note ([NOTE_MIN]..[NOTE_MAX] absolute, [NOTE_OFF]), the slot, and per effect column code (0..35 =",
        "     * '0'..'9', 'A'..'Z') and parameter. Each (order, channel) decodes exactly rows(order) rows inside the used data.",
        "     */",
        "    const val RUN = 0x80",
        "    const val MAX_RUN = 128",
        "    const val MASK_NOTE = 0x01",
        "    const val MASK_INSTRUMENT = 0x02",
        "    const val MASK_EFFECT_SHIFT = 2",
        kt("NOTE_OFF"),
        kt("NOTE_MIN"),
        kt("NOTE_MAX"),
        "",
        "    /** Limits. [NOISE_CHANNEL] (FM 8) is the only channel that may play noise instruments and `Nxx`. */",
        kt("CHANNELS"),
        kt("NOISE_CHANNEL"),
        kt("MAX_ORDERS"),
        kt("MAX_INSTRUMENTS"),
        kt("MAX_EFFECTS"),
        kt("MAX_MULTISPEED"),
        "",
        "    /**",
        "     * Pitch units: 1/64 semitone, 0 = C-0, note n = 64 n. The player subtracts [PITCH_OFFSET] (the 4 MHz clock's",
        "     * 192.27 cents over the key-code table's 3.579545 MHz) and clamps to [PITCH_MIN]..[PITCH_MAX] (key code C#0",
        "     * fraction 0 .. C-8 fraction 63). Fine pitch (Pxx, audition) -128..127.",
        "     */",
        "    const val PITCH_UNITS_PER_SEMITONE = %d" % opm_ref.UNITS_PER_SEMITONE,
        kt("PITCH_OFFSET", "OMP_PITCH_OFFSET"),
        kt("PITCH_MIN", "OMP_PITCH_MIN"),
        kt("PITCH_MAX", "OMP_PITCH_MAX"),
        "",
        "    /** Register slot of operator k (0..3 = operators 1..4 = M1, C1, M2, C2) within a channel's operator bank. */",
        "    val OPERATOR_SLOTS: IntArray = %s" % ints(opm_ref.OPERATOR_SLOTS),
        "",
        "    /** Carrier operators per algorithm 0..7: bit k = operator k + 1. */",
        "    val CARRIERS: IntArray = %s" % ints(opm_ref.CARRIERS),
        "",
        "    /** Effect codes (column code byte); [EFFECT_LETTERS][code] is the name. */",
        '    const val EFFECT_LETTERS = "%s"' % letters,
    ]
    for name, v in sorted(fx.items(), key=lambda kv: kv[1]):
        lines.append("    const val FX_%s = %d // %s" % (name, v, letters[v]))
    lines += [
        "",
        "    /** Chip and timing: YM2151 clock, native rate = [CLOCK] / [NATIVE_DIVIDER]; one tick = 1/60 s / multispeed. */",
        kt("CLOCK", "OMM_CLOCK"),
        kt("NATIVE_DIVIDER", "OMM_NATIVE_DIVIDER"),
        "    /** Seek: [org.thortracker.system.PlaybackEngine.seekTicks] budget; the chip is clocked through the last [PREROLL_TICKS] x multispeed ticks. */",
        "    const val SEEK_TICKS = 12000",
        kt("PREROLL_TICKS", "OMM_PREROLL_TICKS"),
        "",
        "    /** VGM export (native `om_export_vgm`): GD3 system and converter names the file carries. */",
        "    const val GD3_SYSTEM = %s" % kotlin_string(gd3["SYSTEM"]),
        "    const val GD3_CONVERTER = %s" % kotlin_string(gd3["CONVERTER"]),
        "",
        "    /**",
        "     * Every reason the native loader (`om_load_image`) rejects an image with (`opm_image.c` order). A song the Kotlin",
        "     * validator accepts must never produce one; mirror the checks with user-facing messages.",
        "     */",
        "    val LOADER_PROBLEMS: List<String> = listOf(",
    ]
    for s in loader_problems():
        lines.append("        %s," % kotlin_string(s))
    lines += ["    )", "}", ""]
    with open(KOTLIN, "w") as f:
        f.write("\n".join(lines))
    print(KOTLIN)


if __name__ == "__main__":
    write_fixture()
    write_kotlin()
