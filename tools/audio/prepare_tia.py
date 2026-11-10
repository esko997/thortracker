#!/usr/bin/env python3
"""Generates the Atari 2600 (TIA) engine's host-test fixtures and the Kotlin constants (pure Python 3, no
dependencies):

  app/src/test/cpp/tia_test_song.h           test images (tools/audio/tia/tia_song.py), the reference player's
                                             (tools/audio/tia/tia_ref.py) reset batch, per-tick writes and positions
                                             (the test song, its edited, tempo and PAL variants, and the song with the
                                             edited image applied live at TIA_REF_LIVE_AT), the reference chip's
                                             per-phase-1 channel levels and counter states for the first ticks, and
                                             byte offsets for the loader's single-byte corruption checks
  core/.../backend/TiaPlayerImage.kt         the image layout, limits, effect codes, pitch, clock, seek, waveform
                                             class, AUDF edge and mixing constants and the loader messages for the
                                             Kotlin lowering, validator and pitch display, parsed from the engine's C
                                             sources (app/src/main/cpp/tia)

    python3 tools/audio/prepare_tia.py
Fails (before writing anything) when a number the C sources and the Python reference share disagrees: every TI_*
layout constant, the pitch and seek limits, the clocks and event positions, the effect codes, TIP_CLASS, TIP_PERIOD,
TIP_AUDF_EDGE (against the spec's formula), TIP_SINE and TIM_MIX (against Stella's mixing formula). Rerun after
changing the player specification, the reference player, the test songs or the engine sources."""
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(HERE, "tia"))

import tia_ref  # noqa: E402
import tia_song  # noqa: E402

TICKS = 1500
LIVE_AT = 301          # the test song's order 2, row 3, tick 1 (a started row; slot 5 still sounding on TIA 2)
LEVEL_TICKS = 8
CPP = os.path.join(ROOT, "app/src/main/cpp")
FIXTURE = os.path.join(ROOT, "app/src/test/cpp/tia_test_song.h")
KOTLIN = os.path.join(ROOT, "core/src/commonMain/kotlin/org/thortracker/backend/TiaPlayerImage.kt")
CORRUPT_PARAMS = "BCFW"
HEADERS = ("tia/tia_image.h", "tia/tia_player.h", "tia/tia_machine.h")

# TI_FX_* name -> effect letter (the spec's effect table)
FX_LETTERS = {
    "ARPEGGIO": "0", "SLIDE_UP": "1", "SLIDE_DOWN": "2", "PORTAMENTO": "3", "VIBRATO": "4", "VOLUME_SLIDE": "A",
    "JUMP": "B", "VOLUME": "C", "BREAK": "D", "SPEED": "F", "DELAY": "G", "FINE": "P", "CUT": "S", "WAVEFORM": "W",
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
    end = tia_song.OFF_DATA + int.from_bytes(image[20:24], "little")
    assert not any(image[end:])
    return image[:end]


def trace_arrays(prefix, entries):
    """A reference timeline [(pos, writes)] of TICKS ticks as C arrays: tick t writes regs/vals[offsets[t] ..
    offsets[t + 1]) at its first colour clock, pos[3t .. 3t + 2] = (order, row, tick) before it."""
    assert len(entries) == TICKS
    offsets, regs, vals, pos = [0], [], [], []
    for p, writes in entries:
        for reg, val in writes:
            regs.append(reg)
            vals.append(val)
        offsets.append(len(regs))
        pos.extend(p)
    out = [
        c_list("int", prefix + "_offsets", offsets),
        c_list("unsigned char", prefix + "_regs", regs),
        c_list("unsigned char", prefix + "_vals", vals),
        c_list("short", prefix + "_pos", pos),
    ]
    return "\n".join(out), len(regs)


def pattern_bytes(image, img, order, channel):
    """Image offsets of every row of (order, channel): [(mask, note, slot, [(code_at, param_at)])], runs skipped."""
    at = tia_song.OFF_DATA + img.patterns[order][channel]
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
    """Image offsets of the first mask, note, instrument and effect bytes and of the first parameter byte of each effect
    in CORRUPT_PARAMS, for the host test's single-byte corruptions (orders, then channels)."""
    img = tia_ref.Image(image)
    found = {}
    for o in range(img.orders):
        for c in range(tia_ref.CHANNELS):
            for mask, note, slot, effects in pattern_bytes(image, img, o, c):
                found.setdefault("MASK", mask)
                if note is not None:
                    found.setdefault("NOTE", note)
                if slot is not None:
                    found.setdefault("INST", slot)
                for code_at, param_at in effects:
                    found.setdefault("EFFECT", code_at)
                    letter = tia_ref.LETTERS[image[code_at]]
                    if letter in CORRUPT_PARAMS:
                        found.setdefault("PARAM_" + letter, param_at)
    need = ["MASK", "NOTE", "INST", "EFFECT"] + ["PARAM_" + c for c in CORRUPT_PARAMS]
    missing = [k for k in need if k not in found]
    assert not missing, "the test song lacks %s" % missing
    return {k: found[k] for k in need}


def write_fixture():
    images = {
        "song": tia_song.build(tia_song.test_song()),
        "moved": tia_song.build(tia_song.test_song(), relayout=True),
        "edited": tia_song.build(tia_song.edited_song()),
        "tempo": tia_song.build(tia_song.tempo_song()),
        "short": tia_song.build(tia_song.short_song()),
        "pal": tia_song.build(tia_song.pal_song()),
    }
    assert images["moved"] != images["song"]
    ref = {name: tia_ref.Image(data) for name, data in images.items()}  # every fixture passes the reference loader
    assert ref["pal"].region == tia_ref.REGION_PAL and ref["song"].region == tia_ref.REGION_NTSC
    # The live edit must land on a started row with slot 5 sounding on TIA 2 (the edit removes it).
    p = tia_ref.Player(ref["song"])
    for _ in range(LIVE_AT):
        p.tick()
    assert p.tick_in_row > 0 and p.ch[1].sounding and p.ch[1].latched == 5, \
        "LIVE_AT no longer lands where the edit is meant to (%s, TIA 2 slot %d)" % (p.position(), p.ch[1].latched)
    parts = ["/* Generated by tools/audio/prepare_tia.py - do not edit. Atari 2600 (TIA) host-test fixtures: the test",
             " * song (tools/audio/tia/tia_song.py) and its variants as images (the bytes up to the end of the data in",
             " * use; the rest is zero), and the reference player's (tia_ref.py) timeline per trace: tick t writes",
             " * regs/vals[offsets[t] .. offsets[t + 1]) at its first colour clock, pos[3t .. 3t + 2] = (order, row, tick)",
             " * before it. The reset batch is separate. Trace `live` plays tia_song_image and applies tia_edited_image",
             " * (apply_image) before tick TIA_REF_LIVE_AT. Levels: one byte per phase-1 event (v0 | v1 << 4, the channels'",
             " * unmuted output after the event) for the first TIA_REF_LEVEL_TICKS ticks after the reset; state: both",
             " * channels' ten Stella AudioChannel::save bytes after them. */",
             "#define TIA_REF_TICKS %d" % TICKS,
             "#define TIA_REF_LIVE_AT %d" % LIVE_AT,
             "#define TIA_REF_LEVEL_TICKS %d" % LEVEL_TICKS]
    for name, data in images.items():
        parts.append(c_bytes("tia_%s_image" % name, used(data)))
    reset = tia_ref.reset_writes(ref["song"])
    parts.append("#define TIA_REF_RESET_WRITES %d" % len(reset))
    parts.append(c_list("unsigned char", "tia_ref_reset_regs", [r for r, _ in reset]))
    parts.append(c_list("unsigned char", "tia_ref_reset_vals", [v for _, v in reset]))
    traces = {
        "song": tia_ref.trace(ref["song"], TICKS),
        "edited": tia_ref.trace(ref["edited"], TICKS),
        "tempo": tia_ref.trace(ref["tempo"], TICKS),
        "pal": tia_ref.trace(ref["pal"], TICKS),
        "live": tia_ref.trace(ref["song"], TICKS, edit=ref["edited"], edit_at=LIVE_AT),
    }
    assert tia_ref.trace(ref["moved"], TICKS) == traces["song"], "the relaid-out song must play identically"
    total = 0
    for name, entries in traces.items():
        text, n = trace_arrays("tia_ref_" + name, entries)
        parts.append(text)
        total += n
    for name in ("song", "pal"):
        lv, state = tia_ref.levels(ref[name], LEVEL_TICKS)
        assert len(lv) == LEVEL_TICKS * 2 * tia_ref.LINES[ref[name].region]
        assert len(state) == 20
        parts.append(c_list("unsigned char", "tia_ref_%s_levels" % name, lv))
        parts.append(c_list("unsigned char", "tia_ref_%s_state" % name, state))
    for key, offset in byte_offsets(images["song"]).items():
        parts.append("#define TIA_OFFSET_%s %d" % (key, offset))
    with open(FIXTURE, "w") as f:
        f.write("\n".join(parts) + "\n")
    print("%s (%d reference writes)" % (FIXTURE, total))


def read_cpp(name):
    with open(os.path.join(CPP, name)) as f:
        return f.read()


def c_constants():
    """#define TI_/TIP_/TIM_ integer values and enum TI_FX_* codes of the engine headers, evaluated, with their source
    text (header, literal, trailing one-line comment) for the Kotlin file."""
    values, source = {}, {}
    for name in HEADERS:
        for line in read_cpp(name).splitlines():
            m = re.match(r"#define (TI[MP]?_\w+) +([^/]+?)\s*(/\*.*)?$", line.rstrip())
            if m:
                literal = m.group(2)
                expr = re.sub(r"\b(\d+|0x[0-9A-Fa-f]+)[uU]\b", r"\1", literal)
                try:
                    value = eval(expr, {"__builtins__": {}}, dict(values))
                except Exception:  # not an integer constant (e.g. a string or a cast)
                    continue
                if not isinstance(value, int):
                    continue
                values[m.group(1)] = value
                comment = m.group(3) or ""
                comment = comment[2:-2].strip() if comment.endswith("*/") else ""
                source[m.group(1)] = (name, literal, comment)
            m = re.match(r"\s+(TI_FX_\w+) = (\d+),", line)
            if m:
                values[m.group(1)] = int(m.group(2))
    return values, source


def c_table(text, name):
    """The integers of `name[...]... = { ... }` in C source text (nested braces allowed, comments ignored)."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    m = re.search(r"\b%s\s*(?:\[[^\]]*\]\s*)+=\s*\{" % re.escape(name), text)
    assert m, "%s not found" % name
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(text[i], 0)
        i += 1
    return [int(v) for v in re.findall(r"-?\b\d+\b", text[m.end(): i - 1])]


def loader_problems():
    """Every reason ti_load_image can report (tia_image.c, written as TI_PROBLEM("...")), in source order."""
    text = read_cpp("tia/tia_image.c")
    found = []
    for m in re.finditer(r'TI_PROBLEM\("([^"]+)"\)', text):
        if m.group(1) not in found:
            found.append(m.group(1))
    assert found, "no TI_PROBLEM reasons found in tia_image.c"
    return found


def kotlin_string(s):
    return '"%s"' % s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def stella_mix():
    """Stella 7.0 Audio.cxx mixingTableEntry(v, 0x1e) with R_MAX 30, R 1, evaluated as Stella does (doubles)."""
    return [math.floor(0x7fff * v / 30.0 * (30.0 + 1.0 * 30) / (30.0 + 1.0 * v)) for v in range(31)]


def check_reference(c):
    """The reference player and builder must use the C sources' numbers."""
    r, s = tia_ref, tia_song
    pairs = {
        "TI_IMAGE_SIZE": r.IMAGE_SIZE, "TI_LAYOUT": r.LAYOUT, "TI_CHANNELS": r.CHANNELS, "TI_REGIONS": r.REGIONS,
        "TI_REGION_NTSC": r.REGION_NTSC, "TI_REGION_PAL": r.REGION_PAL, "TI_MAX_ORDERS": r.MAX_ORDERS,
        "TI_MAX_ROWS": r.MAX_ROWS, "TI_MAX_INSTRUMENTS": r.MAX_INSTRUMENTS, "TI_MAX_EFFECTS": r.MAX_EFFECTS,
        "TI_MAX_ENVELOPE": r.MAX_ENVELOPE, "TI_H_MAGIC": r.H_MAGIC, "TI_H_LAYOUT": r.H_LAYOUT,
        "TI_H_REGION": r.H_REGION, "TI_H_SPEED": r.H_SPEED, "TI_H_INSTRUMENTS": r.H_INSTRUMENTS,
        "TI_H_ORDERS": r.H_ORDERS, "TI_H_LOOP": r.H_LOOP, "TI_H_DATA_SIZE": r.H_DATA_SIZE,
        "TI_HEADER_SIZE": r.HEADER_SIZE, "TI_OFF_ROWS": s.OFF_ROWS, "TI_OFF_ORDERS": s.OFF_ORDERS,
        "TI_OFF_INSTRUMENTS": s.OFF_INSTRUMENTS, "TI_INSTRUMENT_SIZE": s.INSTRUMENT_SIZE, "TI_OFF_DATA": s.OFF_DATA,
        "TI_DATA_CAPACITY": r.DATA_CAPACITY, "TI_I_FLAGS": r.I_FLAGS, "TI_I_WAVEFORM": r.I_WAVEFORM,
        "TI_I_ENVELOPES": r.I_ENVELOPES, "TI_ENVELOPE_BYTES": r.ENVELOPE_BYTES, "TI_ENVELOPE_COUNT": r.ENVELOPE_COUNT,
        "TI_ENV_VOLUME": r.ENV_VOLUME, "TI_ENV_ARPEGGIO": r.ENV_ARPEGGIO, "TI_ENV_PITCH": r.ENV_PITCH,
        "TI_ENV_WAVEFORM": r.ENV_WAVEFORM, "TI_ENV_NONE": r.ENV_NONE, "TI_FLAG_DEFINED": r.FLAG_DEFINED,
        "TI_FLAG_RAW": r.FLAG_RAW, "TI_FLAG_RELATIVE": r.FLAG_RELATIVE, "TI_NOTE_MIN": r.NOTE_MIN,
        "TI_NOTE_MAX": r.NOTE_MAX, "TI_NOTE_OFF": r.NOTE_OFF, "TI_VOLUME_MAX": r.VOLUME_MAX,
        "TI_WAVEFORM_MAX": r.WAVEFORM_MAX, "TI_AUDF_MAX": r.AUDF_MAX, "TI_REG_AUDC0": r.REG_AUDC0,
        "TI_REG_AUDC1": r.REG_AUDC1, "TI_REG_AUDF0": r.REG_AUDF0, "TI_REG_AUDF1": r.REG_AUDF1,
        "TI_REG_AUDV0": r.REG_AUDV0, "TI_REG_AUDV1": r.REG_AUDV1,
        "TIP_PITCH_MIN": r.PITCH_MIN, "TIP_PITCH_MAX": r.PITCH_MAX, "TIP_SLIDE_LIMIT": r.SLIDE_LIMIT,
        "TIP_CLASSES": r.CLASSES, "TIP_EDGES": r.EDGES,
        "TIM_SEEK_TICKS": r.SEEK_TICKS, "TIM_RELEASE_SECONDS": r.RELEASE_SECONDS,
        "TIM_MAX_TICK_FRAMES": r.MAX_TICK_FRAMES, "TIM_MAX_SAMPLE_RATE": 96000,
        "TIM_CLOCKS_PER_LINE": r.CLOCKS_PER_LINE, "TIM_AUDIO_DIVIDER": r.AUDIO_DIVIDER,
        "TIM_LINES_NTSC": r.LINES[0], "TIM_LINES_PAL": r.LINES[1],
        "TIM_CLOCK_NTSC_NUM": r.CLOCK_NUM[0], "TIM_CLOCK_NTSC_DEN": r.CLOCK_DEN[0],
        "TIM_CLOCK_PAL_NUM": r.CLOCK_NUM[1], "TIM_CLOCK_PAL_DEN": r.CLOCK_DEN[1],
        "TIM_PHASE0_A": r.PHASE0[0], "TIM_PHASE0_B": r.PHASE0[1], "TIM_PHASE1_A": r.PHASE1[0],
        "TIM_PHASE1_B": r.PHASE1[1],
    }
    bad = ["%s: C %s, reference %s" % (k, c.get(k), v) for k, v in pairs.items() if c.get(k) != v]
    # the largest tick at 96 kHz (PAL) must fit, with the resampler's carry
    worst = max(96000 * r.frame_clocks(g) * r.CLOCK_DEN[g] / r.CLOCK_NUM[g] for g in range(r.REGIONS))
    if not math.ceil(worst) + 1 <= r.MAX_TICK_FRAMES <= 2048:
        bad.append("TIM_MAX_TICK_FRAMES %d does not fit a %.2f-frame tick (+1) within CHIP_MAX_TICK_FRAMES"
                   % (r.MAX_TICK_FRAMES, worst))
    fx = {k[len("TI_FX_"):]: v for k, v in c.items() if k.startswith("TI_FX_")}
    if set(fx) != set(FX_LETTERS):
        bad.append("TI_FX_* names %s differ from the spec's effects %s" % (sorted(fx), sorted(FX_LETTERS)))
    for name, code in fx.items():
        if name in FX_LETTERS and code != r.FX[FX_LETTERS[name]]:
            bad.append("TI_FX_%s = %d, effect %s is %d" % (name, code, FX_LETTERS[name], r.FX[FX_LETTERS[name]]))
    if set(fx.values()) != set(r.EFFECTS):
        bad.append("the C effect codes and the reference player's differ")
    player = read_cpp("tia/tia_player.c")
    if c_table(player, "TIP_CLASS") != list(r.CLASS):
        bad.append("TIP_CLASS differs from the reference's AUDC classes %s" % (r.CLASS,))
    if c_table(player, "TIP_PERIOD") != list(r.PERIOD):
        bad.append("TIP_PERIOD differs from the reference's periods %s" % (r.PERIOD,))
    edge = c_table(player, "TIP_AUDF_EDGE")
    flat = [e for region in r.EDGE for cls in region for e in cls]
    if edge != flat:
        diff = [i for i in range(min(len(edge), len(flat))) if edge[i] != flat[i]]
        bad.append("TIP_AUDF_EDGE (%d entries) differs from ceil(768 log2(A / (P C0 sqrt((k + 1)(k + 2))))) at %s"
                   % (len(edge), diff[:8] if diff else "its length"))
    sine = c_table(player, "TIP_SINE")
    formula = [math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64)]
    if sine != list(r.SINE) or list(r.SINE) != formula:
        bad.append("TIP_SINE / tia_ref.SINE differ from floor(127 sin(2 pi i / 64) + 0.5)")
    mix = c_table(read_cpp("tia/tia_machine.c"), "TIM_MIX")
    if mix != list(r.MIX) or list(r.MIX) != stella_mix():
        bad.append("TIM_MIX / tia_ref.MIX differ from Stella's mixingTableEntry(s, 30)")
    # spot checks of the pitch contract (docs/AUDIO_ENGINE.md): A-4 on AUDC C is AUDF 11 in both regions; edges
    # descend; a pitch at an edge picks that divider, one unit below the next one
    if r.audf(0, r.CLASS[12], 57 * 64) != 11 or r.audf(1, r.CLASS[12], 57 * 64) != 11:
        bad.append("A-4 on AUDC C must be AUDF 11 (NTSC and PAL)")
    for region in r.EDGE:
        for cls in region:
            if any(cls[k] <= cls[k + 1] for k in range(len(cls) - 1)):
                bad.append("AUDF edges must descend")
    assert not bad, "C sources and reference disagree:\n  " + "\n  ".join(bad)


def kotlin_ints(values, per_line=16):
    values = list(values)
    lines = []
    for i in range(0, len(values), per_line):
        lines.append("        " + ", ".join(map(str, values[i:i + per_line])) + ",")
    return lines


def write_kotlin():
    c, source = c_constants()
    check_reference(c)
    fx = {k[len("TI_FX_"):]: v for k, v in c.items() if k.startswith("TI_FX_")}
    r = tia_ref

    def kdoc(comment):
        return ["    /** %s */" % comment.replace("*/", "* /")] if comment else []

    lines = [
        "// GENERATED by tools/audio/prepare_tia.py from the engine sources (app/src/main/cpp/tia) -- do not edit.",
        "package org.thortracker.backend",
        "",
        "/**",
        " * The Atari 2600 systems' native contract: Thor Tracker's own player on the TIA (Stella 7.0's AudioChannel),",
        " * engines `\"atari2600-tia-ntsc\"` and `\"atari2600-tia-pal\"`. The image layout the lowering writes, its limits,",
        " * the effect codes, the pitch quantization tables and the loader's rejection messages, as `tia_image.h`,",
        " * `tia_player.h`/`.c` and `tia_machine.h`/`.c` define them (`docs/AUDIO_ENGINE.md`, \"Atari 2600\": the",
        " * specification). All multi-byte fields are little-endian.",
        " */",
        "object TiaPlayerImage {",
        "    /** Native engine ids ([org.thortracker.system.PlaybackEngine.id]) of the NTSC and PAL consoles. */",
        '    const val ENGINE_ID_NTSC = "atari2600-tia-ntsc"',
        '    const val ENGINE_ID_PAL = "atari2600-tia-pal"',
        '    const val MAGIC = "TTIA"',
        "",
        "    /** Every `TI_*` constant of `tia_image.h`, without its prefix. */",
    ]
    for name, (header, literal, comment) in source.items():
        if header != "tia/tia_image.h" or not name.startswith("TI_") or name.startswith("TI_FX_"):
            continue
        value = c[name]
        text = "0x%X" % value if literal.lower().startswith("0x") else str(value)
        lines += kdoc(comment)
        lines.append("    const val %s = %s" % (name[len("TI_"):], text))
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
        "    const val PITCH_UNITS_PER_SEMITONE = %d" % r.UNITS_PER_SEMITONE,
        "    const val PITCH_MIN = %d" % c["TIP_PITCH_MIN"],
        "    const val PITCH_MAX = %d" % c["TIP_PITCH_MAX"],
        "    const val SLIDE_LIMIT = %d" % c["TIP_SLIDE_LIMIT"],
        "",
        "    /** Seek: [org.thortracker.system.PlaybackEngine.seekTicks] budget of clocked ticks. */",
        "    const val SEEK_TICKS = %d" % c["TIM_SEEK_TICKS"],
        "",
        "    /**",
        "     * Timing: colour clock = CLOCK_*_NUM / CLOCK_*_DEN Hz; a tick is one frame of LINES_* scanlines of",
        "     * [CLOCKS_PER_LINE] colour clocks; the audio clock is the colour clock / [AUDIO_DIVIDER].",
        "     */",
        "    const val CLOCKS_PER_LINE = %d" % c["TIM_CLOCKS_PER_LINE"],
        "    const val AUDIO_DIVIDER = %d" % c["TIM_AUDIO_DIVIDER"],
        "    const val LINES_NTSC = %d" % c["TIM_LINES_NTSC"],
        "    const val LINES_PAL = %d" % c["TIM_LINES_PAL"],
        "    const val CLOCK_NTSC_NUM = %d" % c["TIM_CLOCK_NTSC_NUM"],
        "    const val CLOCK_NTSC_DEN = %d" % c["TIM_CLOCK_NTSC_DEN"],
        "    const val CLOCK_PAL_NUM = %d" % c["TIM_CLOCK_PAL_NUM"],
        "    const val CLOCK_PAL_DEN = %d" % c["TIM_CLOCK_PAL_DEN"],
        "",
        "    /** Effect codes (column code byte); [EFFECT_LETTERS][code] is the name. */",
        '    const val EFFECT_LETTERS = "%s"' % r.LETTERS,
    ]
    for name, v in sorted(fx.items(), key=lambda kv: kv[1]):
        lines.append("    const val FX_%s = %d // %s" % (name, v, r.LETTERS[v]))
    lines += [
        "",
        "    /** Waveform class of each AUDC value 0..15 (`TIP_CLASS`); -1 = constant output (AUDC 0, B). */",
        "    val CLASS: IntArray = intArrayOf(",
    ] + kotlin_ints(r.CLASS) + [
        "    )",
        "",
        "    /** Pattern period of each class in audio clocks (`TIP_PERIOD`): fundamental = audio clock / (period (AUDF + 1)). */",
        "    val PERIOD: IntArray = intArrayOf(",
    ] + kotlin_ints(r.PERIOD) + [
        "    )",
        "",
        "    /**",
        "     * `TIP_AUDF_EDGE` per region, flattened at class * 31 + k: the lowest pitch nearer divider k + 1 than k + 2; a",
        "     * tonal pitch plays the smallest k with pitch >= edge, else AUDF 31.",
        "     */",
        "    val AUDF_EDGE_NTSC: IntArray = intArrayOf(",
    ] + kotlin_ints([e for cls in r.EDGE[0] for e in cls]) + [
        "    )",
        "    val AUDF_EDGE_PAL: IntArray = intArrayOf(",
    ] + kotlin_ints([e for cls in r.EDGE[1] for e in cls]) + [
        "    )",
        "",
        "    /** The mono output level of the two channels' volume sum 0..30 (`TIM_MIX`, Stella's mixing). */",
        "    val MIX: IntArray = intArrayOf(",
    ] + kotlin_ints(r.MIX) + [
        "    )",
        "",
        "    /**",
        "     * Every reason the native loader (`ti_load_image`) rejects an image with (`tia_image.c` order). A song the",
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
