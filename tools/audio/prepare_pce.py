#!/usr/bin/env python3
"""Generates the PC Engine engine's host-test fixtures and the Kotlin constants (pure Python 3, no dependencies):

  app/src/test/cpp/pce_test_song.h           test images (tools/audio/pce/pce_song.py), the reference player's
                                             (tools/audio/pce/pce_ref.py) reset batch, per-tick writes and positions
                                             and every timer interrupt's writes with its CPU cycle, its VGM's size,
                                             hash and loop, and byte offsets for the loader's single-byte corruption
                                             checks
  core/.../backend/PcePlayerImage.kt         the image layout, limits, effect codes, pitch, seek and GD3 constants
                                             and loader messages for the Kotlin lowering and validator, parsed from
                                             the engine's C sources (app/src/main/cpp/pce)

    python3 tools/audio/prepare_pce.py
Fails (before writing anything) when a number the C sources and the Python reference share disagrees: every PC_*
layout constant, the pitch limits, the clocks, the effect codes, the period table PCP_PERIOD_Q16 (against the
spec's formula) and the vibrato table PCP_SINE. Rerun after changing the player specification, the reference player,
the test songs or the engine sources."""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(HERE, "pce"))

import pce_ref  # noqa: E402
import pce_song  # noqa: E402

TICKS = 1500
VGM_MAX_TICKS = 36000
CPP = os.path.join(ROOT, "app/src/main/cpp")
FIXTURE = os.path.join(ROOT, "app/src/test/cpp/pce_test_song.h")
KOTLIN = os.path.join(ROOT, "core/src/commonMain/kotlin/org/thortracker/backend/PcePlayerImage.kt")
CORRUPT_PARAMS = "BCFMTW"

# PC_FX_* name -> effect letter (the spec's effect table)
FX_LETTERS = {
    "ARPEGGIO": "0", "SLIDE_UP": "1", "SLIDE_DOWN": "2", "PORTAMENTO": "3", "VIBRATO": "4", "PAN": "8",
    "VOLUME_SLIDE": "A", "JUMP": "B", "VOLUME": "C", "BREAK": "D", "SPEED": "F", "DELAY": "G", "LFO_FREQ": "L",
    "LFO_CTRL": "M", "FINE": "P", "CUT": "S", "TIMER": "T", "WAVE": "W", "MASTER": "X",
}


def c_bytes(name, data):
    lines = ["static const unsigned char %s[%d] = {" % (name, len(data))]
    for i in range(0, len(data), 24):
        lines.append("    " + ",".join("%d" % b for b in data[i:i + 24]) + ",")
    lines.append("};")
    return "\n".join(lines)


def c_list(kind, name, values, per_line=32):
    values = list(values) or [0]
    lines = ["static const %s %s[%d] = {" % (kind, name, len(values))]
    for i in range(0, len(values), per_line):
        lines.append("    " + ",".join(map(str, values[i:i + per_line])) + ",")
    lines.append("};")
    return "\n".join(lines)


def used(image):
    """Bytes up to the end of the data region in use (the rest of an image is zero)."""
    end = pce_song.OFF_DATA + int.from_bytes(image[20:24], "little")
    assert not any(image[end:])
    return image[:end]


def trace_arrays(prefix, image_bytes):
    """The reference timeline of TICKS ticks as C arrays: per tick t its writes regs/vals[offsets[t] .. offsets[t + 1]),
    pos[3t .. 3t + 2] = (order, row, tick) before it and irqs[t] = its timer interrupts (all, with or without writes);
    the interrupts that wrote anything, in order: events ifirst[t] .. ifirst[t + 1] belong to tick t, event e at CPU
    cycle (tick t's first cycle + idelay[e]) writes icount[e] pairs of iregs/ivals, tick t's first at iwfirst[t]."""
    image = pce_ref.Image(image_bytes)
    tr = pce_ref.trace(image, TICKS)
    offsets, regs, vals, pos, irqs = [0], [], [], [], []
    ifirst, iwfirst, idelay, icount, iregs, ivals = [0], [], [], [], [], []
    for k, (p, start, writes, interrupts) in enumerate(tr):
        assert start == pce_ref.tick_cycle(k, image.multispeed)
        for reg, val in writes:
            regs.append(reg)
            vals.append(val)
        offsets.append(len(regs))
        pos.extend(p)
        irqs.append(len(interrupts))
        iwfirst.append(len(iregs))
        for cycle, w in interrupts:
            if not w:
                continue
            assert 0 <= cycle - start < 2 ** 31 and len(w) < 256
            idelay.append(cycle - start)
            icount.append(len(w))
            for reg, val in w:
                iregs.append(reg)
                ivals.append(val)
        ifirst.append(len(idelay))
    assert len(offsets) == TICKS + 1 and len(pos) == 3 * TICKS and len(ifirst) == TICKS + 1
    out = [
        c_list("int", prefix + "_offsets", offsets),
        c_list("unsigned char", prefix + "_regs", regs),
        c_list("unsigned char", prefix + "_vals", vals),
        c_list("short", prefix + "_pos", pos),
        c_list("short", prefix + "_irqs", irqs),
        c_list("int", prefix + "_ifirst", ifirst),
        c_list("int", prefix + "_iwfirst", iwfirst),
        c_list("int", prefix + "_idelay", idelay, 24),
        c_list("unsigned char", prefix + "_icount", icount),
        c_list("unsigned char", prefix + "_iregs", iregs),
        c_list("unsigned char", prefix + "_ivals", ivals),
    ]
    return "\n".join(out), len(regs) + len(iregs)


def fnv64(data):
    h = 0xCBF29CE484222325
    for b in data:
        h = ((h ^ b) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def pattern_bytes(image, img, order, channel):
    """Image offsets of every row of (order, channel): [(mask, note, slot, [(code_at, param_at)])], runs skipped."""
    at = pce_song.OFF_DATA + img.patterns[order][channel]
    rows, out = 0, []
    while rows < img.rows[order]:
        m = image[at]
        if m & 0x80:
            rows += (m & 0x7F) + 1
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
        out.append((mask, note, slot, effects))
        rows += 1
    return out


def byte_offsets(image):
    """Image offsets of the first mask, note, instrument and effect bytes, of the first parameter byte of each effect
    in CORRUPT_PARAMS, and of the first PSG 5 / PSG 6 instrument byte naming a noise instrument, for the host test's
    single-byte corruptions (orders, then channels)."""
    img = pce_ref.Image(image)
    found = {}
    for o in range(img.orders):
        for c in range(pce_ref.CHANNELS):
            for mask, note, slot, effects in pattern_bytes(image, img, o, c):
                found.setdefault("MASK", mask)
                if note is not None:
                    found.setdefault("NOTE", note)
                if slot is not None:
                    found.setdefault("INST", slot)
                    if c >= pce_ref.NOISE_FIRST and img.instrument(image[slot]).kind == pce_ref.KIND_NOISE:
                        found.setdefault("NOISE_INST", slot)
                for code_at, param_at in effects:
                    found.setdefault("EFFECT", code_at)
                    letter = pce_ref.LETTERS[image[code_at]]
                    if letter in CORRUPT_PARAMS:
                        found.setdefault("PARAM_" + letter, param_at)
    need = ["MASK", "NOTE", "INST", "EFFECT", "NOISE_INST"] + ["PARAM_" + c for c in CORRUPT_PARAMS]
    missing = [k for k in need if k not in found]
    assert not missing, "the test song lacks %s" % missing
    return {k: found[k] for k in need}


def write_fixture():
    images = {
        "song": pce_song.build(pce_song.test_song()),
        "moved": pce_song.build(pce_song.test_song(), relayout=True),
        "edited": pce_song.build(pce_song.edited_song()),
        "tempo": pce_song.build(pce_song.tempo_song()),
        "short": pce_song.build(pce_song.short_song()),
        "x2": pce_song.build(pce_song.x2_song()),
        "shared": pce_song.build(pce_song.shared_song()),
    }
    assert images["moved"] != images["song"]
    for data in images.values():
        pce_ref.Image(data)  # every fixture passes the reference loader
    parts = ["/* Generated by tools/audio/prepare_pce.py - do not edit. PC Engine host-test fixtures: the test song",
             " * (tools/audio/pce/pce_song.py) and its variants as images (the bytes up to the end of the data in use; the",
             " * rest is zero), and the reference player's (pce_ref.py) timeline per trace: tick t writes",
             " * regs/vals[offsets[t] .. offsets[t + 1]) at its first cycle, pos[3t .. 3t + 2] = (order, row, tick) before",
             " * it, irqs[t] = its timer interrupts; the interrupts that wrote anything are events ifirst[t] .. ifirst[t + 1]",
             " * of tick t, event e at CPU cycle (the tick's first cycle + idelay[e]) writing icount[e] pairs of iregs/ivals,",
             " * tick t's first at iwfirst[t]. The reset batch is separate. */",
             "#define PCE_REF_TICKS %d" % TICKS]
    for name, data in images.items():
        parts.append(c_bytes("pce_%s_image" % name, used(data)))
    reset = pce_ref.reset_writes(pce_ref.Image(images["song"]))
    parts.append("#define PCE_REF_RESET_WRITES %d" % len(reset))
    parts.append(c_list("unsigned char", "pce_ref_reset_regs", [r for r, _ in reset]))
    parts.append(c_list("unsigned char", "pce_ref_reset_vals", [v for _, v in reset]))
    total = 0
    for name in ("song", "edited", "tempo", "x2"):
        text, n = trace_arrays("pce_ref_" + name, images[name])
        parts.append(text)
        total += n
    data, info = pce_ref.vgm(pce_ref.Image(images["song"]), VGM_MAX_TICKS)
    assert info["looped"], "the test song's VGM must close its loop within %d ticks" % VGM_MAX_TICKS
    parts.append("#define PCE_REF_VGM_MAX_TICKS %d" % VGM_MAX_TICKS)
    parts.append("#define PCE_REF_VGM_SIZE %du" % len(data))
    parts.append("#define PCE_REF_VGM_FNV 0x%016XULL" % fnv64(data))
    parts.append("#define PCE_REF_VGM_TICKS %du" % info["ticks"])
    parts.append("#define PCE_REF_VGM_LOOP_OFFSET %du" % info["loop_offset"])
    parts.append("#define PCE_REF_VGM_LOOP_SAMPLES %du" % info["loop_samples"])
    parts.append("#define PCE_REF_VGM_SAMPLES %du" % info["samples"])
    for key, offset in byte_offsets(images["song"]).items():
        parts.append("#define PCE_OFFSET_%s %d" % (key, offset))
    with open(FIXTURE, "w") as f:
        f.write("\n".join(parts) + "\n")
    print("%s (%d reference writes; VGM %d bytes, %d ticks, loop %d samples)" % (FIXTURE, total, len(data),
                                                                                 info["ticks"], info["loop_samples"]))


def read_cpp(name):
    with open(os.path.join(CPP, name)) as f:
        return f.read()


def c_constants():
    """#define PC_/PCP_/PCM_ values and enum PC_FX_* codes of the engine headers, evaluated, with their source text
    (header, literal, trailing one-line comment) for the Kotlin file."""
    values, source = {}, {}
    for name in ("pce/pce_image.h", "pce/pce_player.h", "pce/pce_machine.h", "pce/pce_vgm.h"):
        for line in read_cpp(name).splitlines():
            m = re.match(r"#define (PC[MP]?_\w+) +([^/]+?)\s*(/\*.*)?$", line.rstrip())
            if m:
                literal = m.group(2)
                expr = re.sub(r"\b(\d+|0x[0-9A-Fa-f]+)u\b", r"\1", literal)
                values[m.group(1)] = eval(expr, {}, dict(values))
                comment = m.group(3) or ""
                comment = comment[2:-2].strip() if comment.endswith("*/") else ""
                source[m.group(1)] = (name, literal, comment)
            m = re.match(r"\s+(PC_FX_\w+) = (\d+),", line)
            if m:
                values[m.group(1)] = int(m.group(2))
    return values, source


def c_table(text, name):
    """The integers of `name[...] = { ... }` in C source text."""
    m = re.search(r"\b%s\s*\[\s*\d*\s*\]\s*=\s*\{([^}]*)\}" % re.escape(name), text)
    assert m, "%s not found in pce_player.c" % name
    return [int(v) for v in re.findall(r"-?\d+", re.sub(r"/\*.*?\*/", "", m.group(1), flags=re.S))]


def loader_problems():
    """Every reason string pc_load_image can report (pce_image.c), in source order."""
    text = read_cpp("pce/pce_image.c")
    found = []
    for m in re.finditer(r'(?:fail\(\s*image,\s*|why = )"([^"]+)"', text):
        if m.group(1) not in found:
            found.append(m.group(1))
    assert found, "no loader reasons found in pce_image.c"
    return found


def gd3_strings():
    for name in ("pce/pce_vgm.c", "pce/pce_export.c"):
        path = os.path.join(CPP, name)
        if not os.path.exists(path):
            continue
        text = read_cpp(name)
        found = {k: re.search(r'#define GD3_%s "([^"]+)"' % k, text) for k in ("SYSTEM", "CONVERTER")}
        if all(found.values()):
            return {k: v.group(1) for k, v in found.items()}
    raise AssertionError("GD3_SYSTEM / GD3_CONVERTER #defines not found in pce_vgm.c")


def kotlin_string(s):
    return '"%s"' % s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def check_reference(c):
    """The reference player and builder must use the C sources' numbers."""
    r, s = pce_ref, pce_song
    pairs = {
        "PC_IMAGE_SIZE": r.IMAGE_SIZE, "PC_CHANNELS": r.CHANNELS, "PC_NOISE_FIRST": r.NOISE_FIRST,
        "PC_MAX_ORDERS": r.MAX_ORDERS, "PC_MAX_INSTRUMENTS": r.MAX_INSTRUMENTS, "PC_MAX_WAVES": r.MAX_WAVES,
        "PC_MAX_SAMPLES": r.MAX_SAMPLES, "PC_MAX_EFFECTS": r.MAX_EFFECTS, "PC_MAX_MULTISPEED": r.MAX_MULTISPEED,
        "PC_MAX_TIMER": r.MAX_TIMER, "PC_LAYOUT": r.LAYOUT, "PC_WAVE_SIZE": r.WAVE_SIZE, "PC_WAVE_MAX": r.WAVE_MAX,
        "PC_MAX_ENVELOPE": r.MAX_ENVELOPE, "PC_MAX_SAMPLE_FRAMES": r.MAX_SAMPLE_FRAMES,
        "PC_H_MAGIC": r.H_MAGIC, "PC_H_LAYOUT": r.H_LAYOUT, "PC_H_MULTISPEED": r.H_MULTISPEED,
        "PC_H_SPEED": r.H_SPEED, "PC_H_INSTRUMENTS": r.H_INSTRUMENTS, "PC_H_ORDERS": r.H_ORDERS,
        "PC_H_LOOP": r.H_LOOP, "PC_H_WAVES": r.H_WAVES, "PC_H_SAMPLES": r.H_SAMPLES, "PC_H_MASTER": r.H_MASTER,
        "PC_H_LFO_FREQ": r.H_LFO_FREQ, "PC_H_LFO_CTRL": r.H_LFO_CTRL, "PC_H_TIMER": r.H_TIMER,
        "PC_H_DATA_SIZE": r.H_DATA_SIZE, "PC_HEADER_SIZE": r.HEADER_SIZE,
        "PC_OFF_ROWS": s.OFF_ROWS, "PC_OFF_ORDERS": s.OFF_ORDERS, "PC_OFF_INSTRUMENTS": s.OFF_INSTRUMENTS,
        "PC_INSTRUMENT_SIZE": s.INSTRUMENT_SIZE, "PC_OFF_WAVES": s.OFF_WAVES, "PC_OFF_SAMPLES": s.OFF_SAMPLES,
        "PC_SAMPLE_ENTRY_SIZE": r.SAMPLE_ENTRY_SIZE, "PC_OFF_DATA": s.OFF_DATA, "PC_DATA_CAPACITY": r.DATA_CAPACITY,
        "PC_I_FLAGS": r.I_FLAGS, "PC_I_WAVE": r.I_WAVE, "PC_I_SAMPLE": r.I_SAMPLE, "PC_I_ROOT": r.I_ROOT,
        "PC_I_LOOP": r.I_LOOP, "PC_I_LOOP_START": r.I_LOOP_START, "PC_I_ENVELOPES": r.I_ENVELOPES,
        "PC_ENVELOPE_BYTES": r.ENVELOPE_BYTES, "PC_ENVELOPE_COUNT": r.ENVELOPE_COUNT,
        "PC_ENV_VOLUME": r.ENV_VOLUME, "PC_ENV_ARPEGGIO": r.ENV_ARPEGGIO, "PC_ENV_PITCH": r.ENV_PITCH,
        "PC_ENV_WAVE": r.ENV_WAVE, "PC_ENV_NONE": r.ENV_NONE, "PC_FLAG_DEFINED": r.FLAG_DEFINED,
        "PC_FLAG_RELATIVE": r.FLAG_RELATIVE, "PC_KIND_MASK": r.KIND_MASK, "PC_KIND_TONE": r.KIND_TONE,
        "PC_KIND_NOISE": r.KIND_NOISE, "PC_KIND_SAMPLE": r.KIND_SAMPLE, "PC_NOTE_OFF": r.NOTE_OFF,
        "PC_NOTE_MIN": r.NOTE_MIN, "PC_NOTE_MAX": r.NOTE_MAX,
        "PCP_PITCH_MIN": r.PITCH_MIN, "PCP_PITCH_MAX": r.PITCH_MAX, "PCP_SLIDE_LIMIT": r.SLIDE_LIMIT,
        "PCP_TIMER_DIVIDER": r.TIMER_DIVIDER, "PCM_TIMER_DIVIDER": r.TIMER_DIVIDER, "PCM_CYCLES_NUM": r.CYCLES_NUM,
        "PCM_CPU_HZ_NUM": r.CPU_HZ_NUM, "PCM_CPU_HZ_DEN": r.CPU_HZ_DEN, "PCM_SEEK_TICKS": 3600,
        "PCM_MAX_TICK_FRAMES": 1601, "PCM_RELEASE_SECONDS": 10, "PC_VGM_CLOCK": r.VGM_CLOCK,
    }
    bad = ["%s: C %s, reference %s" % (k, c.get(k), v) for k, v in pairs.items() if c.get(k) != v]
    fx = {k[len("PC_FX_"):]: v for k, v in c.items() if k.startswith("PC_FX_")}
    if set(fx) != set(FX_LETTERS):
        bad.append("PC_FX_* names %s differ from the spec's effects %s" % (sorted(fx), sorted(FX_LETTERS)))
    for name, code in fx.items():
        if name in FX_LETTERS and code != r.FX[FX_LETTERS[name]]:
            bad.append("PC_FX_%s = %d, effect %s is %d" % (name, code, FX_LETTERS[name], r.FX[FX_LETTERS[name]]))
    if set(fx.values()) != set(r.EFFECTS):
        bad.append("the C effect codes and the reference player's differ")
    player = read_cpp("pce/pce_player.c")
    period = c_table(player, "PCP_PERIOD_Q16")
    if period != list(r.PERIOD_Q16):
        diff = [i for i in range(min(len(period), 768)) if period[i] != r.PERIOD_Q16[i]]
        bad.append("PCP_PERIOD_Q16 (%d entries) differs from round(39375000 / 11 / (32 * 440 * 2^((i / 64 - 57) / 12))"
                   " * 65536) at %s" % (len(period), diff[:8] if diff else "its length"))
    sine = c_table(player, "PCP_SINE")
    import math
    formula = [math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64)]
    if sine != list(r.SINE) or list(r.SINE) != formula:
        bad.append("PCP_SINE / pce_ref.SINE differ from floor(127 sin(2 pi i / 64) + 0.5)")
    below = (r.PERIOD_Q16[r.PITCH_MIN - 1] + (1 << 15)) >> 16  # pitch 568 (octave 0) without the clamp
    if r.period(57 * 64) != 254 or r.period(r.PITCH_MIN) != 4093 or below <= 4095:
        bad.append("period(): A-4 = %d (254), PITCH_MIN = %d (4093), pitch 568 unclamped = %d (> 4095)"
                   % (r.period(57 * 64), r.period(r.PITCH_MIN), below))
    assert not bad, "C sources and reference disagree:\n  " + "\n  ".join(bad)


def write_kotlin():
    c, source = c_constants()
    check_reference(c)
    fx = {k[len("PC_FX_"):]: v for k, v in c.items() if k.startswith("PC_FX_")}
    letters = pce_ref.LETTERS
    gd3 = gd3_strings()

    def kdoc(comment):
        return ["    /** %s */" % comment.replace("*/", "* /")] if comment else []

    lines = [
        "// GENERATED by tools/audio/prepare_pce.py from the engine sources (app/src/main/cpp/pce) -- do not edit.",
        "package org.thortracker.backend",
        "",
        "/**",
        " * The PC Engine system's native contract: Thor Tracker's own player on the HuC6280 PSG (Mednafen), engine",
        " * `\"pce-huc6280\"`. The image layout the lowering writes, its limits, the effect codes and the loader's rejection",
        " * messages, as `pce_image.h`, `pce_player.h` and `pce_machine.h` define them (`docs/AUDIO_ENGINE.md`, \"PC Engine\":",
        " * the specification). All multi-byte fields are little-endian.",
        " */",
        "object PcePlayerImage {",
        "    /** Native engine id ([org.thortracker.system.PlaybackEngine.id]). */",
        '    const val ENGINE_ID = "pce-huc6280"',
        '    const val MAGIC = "TTPC"',
        "",
        "    /** Every `PC_*` constant of `pce_image.h`, without its prefix. */",
    ]
    for name, (header, literal, comment) in source.items():
        if header != "pce/pce_image.h" or not name.startswith("PC_"):
            continue
        value = c[name]
        text = "0x%X" % value if literal.lower().startswith("0x") else str(value)
        lines += kdoc(comment)
        lines.append("    const val %s = %s" % (name[len("PC_"):], text))
    lines += [
        "",
        "    /**",
        "     * Pattern rows: [RUN] | k = k + 1 empty rows (k 0..127, [MAX_RUN] rows at most); else a mask byte: [MASK_NOTE]",
        "     * (note byte follows), [MASK_INSTRUMENT] (slot byte follows), effect count 0..4 at bits 2-4 ([MASK_EFFECT_SHIFT]);",
        "     * then the note ([NOTE_MIN]..[NOTE_MAX] absolute, [NOTE_OFF]), the slot, and per effect column code (0..35 =",
        "     * '0'..'9', 'A'..'Z') and parameter.",
        "     */",
        "    const val RUN = 0x80",
        "    const val MAX_RUN = 128",
        "    const val MASK_NOTE = 0x01",
        "    const val MASK_INSTRUMENT = 0x02",
        "    const val MASK_EFFECT_SHIFT = 2",
        "",
        "    /** Pitch units: 1/64 semitone, 0 = C-0, note n = 64 n; the player clamps to [PITCH_MIN]..[PITCH_MAX]. */",
        "    const val PITCH_UNITS_PER_SEMITONE = %d" % pce_ref.UNITS_PER_SEMITONE,
        "    const val PITCH_MIN = %d" % c["PCP_PITCH_MIN"],
        "    const val PITCH_MAX = %d" % c["PCP_PITCH_MAX"],
        "",
        "    /** Seek: [org.thortracker.system.PlaybackEngine.seekTicks] budget of clocked ticks. */",
        "    const val SEEK_TICKS = %d" % c["PCM_SEEK_TICKS"],
        "",
        "    /** Effect codes (column code byte); [EFFECT_LETTERS][code] is the name. */",
        '    const val EFFECT_LETTERS = "%s"' % letters,
    ]
    for name, v in sorted(fx.items(), key=lambda kv: kv[1]):
        lines.append("    const val FX_%s = %d // %s" % (name, v, letters[v]))
    lines += [
        "",
        "    /** VGM export (native `pc_export_vgm`): GD3 system and converter names the file carries. */",
        "    const val GD3_SYSTEM = %s" % kotlin_string(gd3["SYSTEM"]),
        "    const val GD3_CONVERTER = %s" % kotlin_string(gd3["CONVERTER"]),
        "",
        "    /**",
        "     * Every reason the native loader (`pc_load_image`) rejects an image with (`pce_image.c` order). A song the",
        "     * Kotlin validator accepts must never produce one; mirror the checks with user-facing messages.",
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
    # check the C sources against the reference before writing either output
    check_reference(c_constants()[0])
    write_fixture()
    write_kotlin()
