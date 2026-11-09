#!/usr/bin/env python3
"""Checks the VIC-20 / C16-Plus/4 engine's shared numbers against the independent Python reference and writes the
native host test's fixture (docs/AUDIO_ENGINE.md "VIC-20 and C16/Plus/4", "Preparing and checking").

    python3 tools/audio/prepare_cbm.py [--check-only]

Fails (exit 1, nothing written) when any of these disagree with tools/audio/cbm/cbm_ref.py:
  - app/src/main/cpp/cbm/cbm_tables.c: CBP_PERIOD_Q16 (4 x 768), CBP_STEP_Q16 (768), CBP_SINE (64);
  - the reference's lane ranges vs the range rule recomputed from the tables (tonal lanes);
  - app/src/main/cpp/cbm/cbm_image.h: every CB_* constant the reference also defines (layout, offsets, limits,
    effect codes);
  - core/src/commonMain/kotlin/org/thortracker/backend/CbmImage.kt: every `const val` whose CB_-prefixed name the C
    header defines must have the header's value, and the constants the lowering needs must exist.
Then builds the test songs of tools/audio/cbm/cbm_song.py, validates them with the reference loader and writes
app/src/test/cpp/cbm_test_song.h: per song the image's leading bytes (header, tables, used data; the rest of the
131072 bytes is zero), the traced tick count and the reference's batches in the binary trace format (kind 'R', 'T'
or 'I'; u32 LE cycle; T only: u16 LE order, u8 row, u8 tick before the tick; u8 count; count x (reg, value)).
A second run must leave the output byte-identical. Pure Python 3."""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(HERE, "cbm"))

import cbm_ref as ref    # noqa: E402
import cbm_song         # noqa: E402

TABLES_C = os.path.join(ROOT, "app/src/main/cpp/cbm/cbm_tables.c")
IMAGE_H = os.path.join(ROOT, "app/src/main/cpp/cbm/cbm_image.h")
KOTLIN = os.path.join(ROOT, "core/src/commonMain/kotlin/org/thortracker/backend/CbmImage.kt")
FIXTURE = os.path.join(ROOT, "app/src/test/cpp/cbm_test_song.h")

# CB_* names the reference defines, with their values (the spec's constants).
REFERENCE_CONSTANTS = {
    "CB_IMAGE_SIZE": ref.IMAGE_SIZE, "CB_LAYOUT": ref.LAYOUT, "CB_HEADER_SIZE": ref.HEADER_SIZE,
    "CB_CHIP_VIC": ref.CHIP_VIC, "CB_CHIP_TED": ref.CHIP_TED, "CB_VIDEO_PAL": ref.VIDEO_PAL,
    "CB_VIDEO_NTSC": ref.VIDEO_NTSC, "CB_MAX_CHANNELS": ref.MAX_CHANNELS, "CB_VIC_CHANNELS": ref.VIC_CHANNELS,
    "CB_TED_CHANNELS": ref.TED_CHANNELS, "CB_MAX_ORDERS": ref.MAX_ORDERS, "CB_MAX_INSTRUMENTS": ref.MAX_INSTRUMENTS,
    "CB_MAX_SAMPLES": ref.MAX_SAMPLES, "CB_MAX_EFFECTS": ref.MAX_EFFECTS, "CB_MAX_MULTISPEED": ref.MAX_MULTISPEED,
    "CB_INSTRUMENT_SIZE": ref.INSTRUMENT_SIZE, "CB_SAMPLE_ENTRY_SIZE": ref.SAMPLE_ENTRY_SIZE,
    "CB_MAX_ENVELOPE": ref.MAX_ENVELOPE, "CB_MAX_SAMPLE_FRAMES": ref.MAX_SAMPLE_FRAMES,
    "CB_VIC_MAX_VOLUME": ref.VIC_MAX_VOLUME, "CB_TED_MAX_VOLUME": ref.TED_MAX_VOLUME, "CB_DAC_MAX": ref.DAC_MAX,
    "CB_VIC_NOISE_CHANNEL": ref.VIC_NOISE_CHANNEL, "CB_TED_NOISE_CHANNEL": ref.TED_NOISE_CHANNEL,
    "CB_TED_DAC_CHANNEL": ref.TED_DAC_CHANNEL,
    "CB_H_MAGIC": ref.H_MAGIC, "CB_H_LAYOUT": ref.H_LAYOUT, "CB_H_CHIP": ref.H_CHIP, "CB_H_VIDEO": ref.H_VIDEO,
    "CB_H_MULTISPEED": ref.H_MULTISPEED, "CB_H_SPEED": ref.H_SPEED, "CB_H_INSTRUMENTS": ref.H_INSTRUMENTS,
    "CB_H_ORDERS": ref.H_ORDERS, "CB_H_LOOP": ref.H_LOOP, "CB_H_SAMPLES": ref.H_SAMPLES,
    "CB_H_VOLUME": ref.H_VOLUME, "CB_H_TIMER": ref.H_TIMER, "CB_H_DATA_SIZE": ref.H_DATA_SIZE,
    "CB_OFF_ROWS": ref.OFF_ROWS, "CB_OFF_ORDERS": ref.OFF_ORDERS, "CB_OFF_INSTRUMENTS": ref.OFF_INSTRUMENTS,
    "CB_OFF_SAMPLES": ref.OFF_SAMPLES, "CB_OFF_DATA": ref.OFF_DATA, "CB_DATA_CAPACITY": ref.DATA_CAPACITY,
    "CB_I_FLAGS": ref.I_FLAGS, "CB_I_WAVE": ref.I_WAVE, "CB_I_SAMPLE": ref.I_SAMPLE, "CB_I_ROOT": ref.I_ROOT,
    "CB_I_LOOP": ref.I_LOOP, "CB_I_LOOP_START": ref.I_LOOP_START, "CB_I_ENVELOPES": ref.I_ENVELOPES,
    "CB_ENV_VOLUME": ref.ENV_VOLUME, "CB_ENV_WAVE": ref.ENV_WAVE, "CB_ENV_NONE": ref.ENV_NONE,
    "CB_FLAG_DEFINED": ref.FLAG_DEFINED, "CB_FLAG_RELATIVE": ref.FLAG_RELATIVE, "CB_KIND_TONE": ref.KIND_TONE,
    "CB_KIND_SAMPLE": ref.KIND_SAMPLE, "CB_WAVE_SQUARE": ref.WAVE_SQUARE, "CB_WAVE_NOISE": ref.WAVE_NOISE,
    "CB_NOTE_OFF": ref.NOTE_OFF,
}
REFERENCE_CONSTANTS.update({"CB_FX_" + name: ref.FX[letter] for name, letter in (
    ("ARPEGGIO", "0"), ("SLIDE_UP", "1"), ("SLIDE_DOWN", "2"), ("PORTAMENTO", "3"), ("VIBRATO", "4"),
    ("VOLUME_SLIDE", "A"), ("JUMP", "B"), ("BREAK", "D"), ("SPEED", "F"), ("DELAY", "G"), ("FINE", "P"),
    ("CUT", "S"), ("TIMER", "T"), ("VOLUME", "V"), ("WAVE", "W"))})

# Kotlin constants the lowering and validator need (names without the CB_ prefix).
KOTLIN_REQUIRED = ("IMAGE_SIZE", "OFF_ROWS", "OFF_ORDERS", "OFF_INSTRUMENTS", "OFF_SAMPLES", "OFF_DATA",
                   "DATA_CAPACITY", "H_CHIP", "H_VIDEO", "H_VOLUME", "H_TIMER", "H_DATA_SIZE", "NOTE_OFF",
                   "INSTRUMENT_SIZE", "MAX_EFFECTS")


def parse_number(text):
    text = text.strip().rstrip("uUlL")
    if text.startswith("'") and text.endswith("'") and len(text) == 3:
        return ord(text[1])
    return int(text, 0)


def c_array(source, name):
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    m = re.search(re.escape(name) + r"(\[[^\]]*\])+\s*=\s*\{(.*?)\};", source, re.S)
    if not m:
        raise SystemExit("prepare_cbm: %s not found" % name)
    return [parse_number(x) for x in re.findall(r"-?\d+[uU]?", m.group(2))]


def header_constants(path):
    """CB_* values of the C header: #defines with a plain number (optionally parenthesised or with a u suffix), and
    enum entries `CB_X = n`."""
    with open(path) as f:
        text = re.sub(r"/\*.*?\*/", "", f.read(), flags=re.S)
    out = {}
    for name, value in re.findall(r"#define\s+(CB_[A-Z0-9_]+)\s+\(?\s*(0[xX][0-9A-Fa-f]+|\d+)[uU]?\s*\)?\s*$",
                                  text, re.M):
        out[name] = int(value, 0)
    for name, value in re.findall(r"\b(CB_[A-Z0-9_]+)\s*=\s*(0[xX][0-9A-Fa-f]+|\d+)", text):
        out.setdefault(name, int(value, 0))
    return out


def kotlin_constants(path):
    with open(path) as f:
        text = f.read()
    return {name: int(value.replace("_", ""), 0) for name, value in
            re.findall(r"const\s+val\s+([A-Z0-9_]+)\s*(?::\s*\w+)?\s*=\s*(0[xX][0-9A-Fa-f_]+|\d[\d_]*)\b", text)}


def check(problems):
    with open(TABLES_C) as f:
        tables = f.read()
    period = c_array(tables, "CBP_PERIOD_Q16")
    expected = [x for v in range(4) for x in ref.PERIOD_Q16[v]]
    if period != expected:
        bad = next(i for i in range(min(len(period), len(expected))) if period[i] != expected[i]) \
            if len(period) == len(expected) else len(period)
        problems.append("CBP_PERIOD_Q16 differs from the reference (first at entry %d)" % bad)
    if c_array(tables, "CBP_STEP_Q16") != list(ref.STEP_Q16):
        problems.append("CBP_STEP_Q16 differs from the reference")
    if c_array(tables, "CBP_SINE") != list(ref.SINE):
        problems.append("CBP_SINE differs from the reference")
    for video in (ref.VIDEO_PAL, ref.VIDEO_NTSC):
        for c in range(3):
            got = ref.lane_range_from_tables(ref.CHIP_VIC, video, c)
            if got != ref.VIC_RANGES[video][c]:
                problems.append("VIC video %d lane %d range %s, tables give %s" % (video, c, ref.VIC_RANGES[video][c],
                                                                                  got))
        got = ref.lane_range_from_tables(ref.CHIP_TED, video, 0)
        if got != ref.TED_RANGES[0]:
            problems.append("TED video %d voice 1 range %s, tables give %s" % (video, ref.TED_RANGES[0], got))
        if got[0] != ref.TED_RANGES[1][0]:
            problems.append("TED voice 2 lowest note %d, tables give %d" % (ref.TED_RANGES[1][0], got[0]))
    header = header_constants(IMAGE_H)
    for name, value in REFERENCE_CONSTANTS.items():
        if name not in header:
            problems.append("cbm_image.h lacks %s" % name)
        elif header[name] != value:
            problems.append("cbm_image.h %s = %d, the reference %d" % (name, header[name], value))
    kotlin = kotlin_constants(KOTLIN)
    for name in KOTLIN_REQUIRED:
        if name not in kotlin:
            problems.append("CbmImage.kt lacks %s" % name)
    for name, value in kotlin.items():
        c_name = "CB_" + name
        if c_name in header and header[c_name] != value:
            problems.append("CbmImage.kt %s = %d, cbm_image.h %d" % (name, value, header[c_name]))
        if c_name in REFERENCE_CONSTANTS and REFERENCE_CONSTANTS[c_name] != value:
            problems.append("CbmImage.kt %s = %d, the reference %d" % (name, value, REFERENCE_CONSTANTS[c_name]))
    if kotlin.get("SEEK_TICKS", ref.SEEK_TICKS) != ref.SEEK_TICKS:
        problems.append("CbmImage.kt SEEK_TICKS differs from the spec's 3600")
    with open(KOTLIN) as f:
        text = f.read()
    for name, pick in (("LANE_MIN", 0), ("LANE_MAX", 1)):
        m = re.search(r"val\s+%s\b[^=]*=\s*arrayOf\((.*?)\n\s*\)" % name, text, re.S)
        rows = [[int(x) for x in re.findall(r"-?\d+", r)] for r in re.findall(r"intArrayOf\(([^)]*)\)",
                                                                                m.group(1))] if m else []
        for chip in (ref.CHIP_VIC, ref.CHIP_TED):
            for video in (ref.VIDEO_PAL, ref.VIDEO_NTSC):
                v = ref.variant(chip, video)
                want = [ref.lane_range(chip, video, c)[pick] for c in range(ref.channels_of(chip))]
                if len(rows) != 4 or rows[v][:len(want)] != want:
                    problems.append("CbmImage.kt %s for variant %d differs from the reference %s" % (name, v, want))


def c_bytes(name, data):
    lines = ["static const uint8_t %s[%d] = {" % (name, max(1, len(data)))]
    for i in range(0, len(data), 24):
        lines.append("    " + ",".join("%d" % b for b in data[i:i + 24]) + ",")
    if not data:
        lines.append("    0,")
    lines.append("};")
    return "\n".join(lines)


def fixture():
    parts = ["/* Generated by tools/audio/prepare_cbm.py from tools/audio/cbm/cbm_song.py and the reference player",
             " * tools/audio/cbm/cbm_ref.py. Do not edit. Format: docs/AUDIO_ENGINE.md \"VIC-20 and C16/Plus/4\" and",
             " * prepare_cbm.py's docstring. */",
             "#ifndef THORTRACKER_CBM_TEST_SONG_H_",
             "#define THORTRACKER_CBM_TEST_SONG_H_",
             "",
             "#include <stdint.h>",
             "",
             "typedef struct CbtSong {",
             "    const char* name;",
             "    const uint8_t* image;  /* leading bytes; the rest of CB_IMAGE_SIZE is zero */",
             "    uint32_t image_bytes;",
             "    uint32_t ticks;        /* traced ticks */",
             "    const uint8_t* trace;  /* the reference's batches */",
             "    uint32_t trace_bytes;",
             "} CbtSong;",
             ""]
    entries = []
    for name, (song, ticks) in cbm_song.SONGS.items():
        image = cbm_song.build(song)
        decoded = ref.Image(image)          # raises on any loader problem
        used = ref.OFF_DATA + decoded.data_size
        trace = ref.trace_bytes(decoded, ticks)
        parts.append(c_bytes("cbt_image_" + name, image[:used]))
        parts.append(c_bytes("cbt_trace_" + name, trace))
        parts.append("")
        entries.append('    {"%s", cbt_image_%s, %d, %d, cbt_trace_%s, %d},' % (name, name, used, ticks, name,
                                                                                len(trace)))
    parts.append("static const CbtSong CBT_SONGS[] = {")
    parts.extend(entries)
    parts.append("};")
    parts.append("#define CBT_SONG_COUNT %d" % len(entries))
    parts.append("")
    parts.append("#endif /* THORTRACKER_CBM_TEST_SONG_H_ */")
    return "\n".join(parts) + "\n"


def main(argv):
    problems = []
    check(problems)
    if problems:
        for p in problems:
            print("prepare_cbm: " + p, file=sys.stderr)
        return 1
    print("prepare_cbm: tables, lane ranges and constants agree with the reference")
    if "--check-only" in argv:
        return 0
    text = fixture()
    with open(FIXTURE, "w") as f:
        f.write(text)
    print("prepare_cbm: wrote %s (%d bytes)" % (os.path.relpath(FIXTURE, ROOT), len(text)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
