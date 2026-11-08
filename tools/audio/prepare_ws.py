#!/usr/bin/env python3
"""Generates the WonderSwan engines' host-test fixtures and the Kotlin constants (pure Python 3, no dependencies):

  app/src/test/cpp/ws_test_song.h            the reference songs (tools/audio/ws/ws_song.py: ws_song, wsc_song,
                                             wsc_vgm, ws_tone) as images (the bytes up to the end of the data in use),
                                             the reference player's (tools/audio/ws/ws_ref.py) reset batch, per-tick
                                             writes and positions and every timer interrupt's writes with its cycle,
                                             the reference VGM's size, FNV-1a 64 hash and loop (songs without Hyper
                                             Voice notes), and single-byte corruptions the loader must reject
  core/.../backend/WsPlayerImage.kt          the image layout, limits, effect codes, pitch, seek, tick and GD3
                                             constants and loader messages for the Kotlin lowering and validator,
                                             parsed from the engine's C sources (app/src/main/cpp/ws)

    python3 tools/audio/prepare_ws.py

Fails (before writing anything) when a number the C sources and the Python reference share disagrees: every WS_*
layout constant, the pitch limits, the clocks, the effect codes, the period table WSP_PERIOD_Q16 (against the spec's
formula) and the vibrato table WSP_SINE. Rerun after changing the player specification, the reference player, the
reference songs or the engine sources (docs/AUDIO_ENGINE.md, "WonderSwan")."""
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(HERE, "ws"))

import ws_ref as R  # noqa: E402
import ws_song as S  # noqa: E402

TICKS = 1500
VGM_MAX_TICKS = 45283          # Kotlin's WsVgm.maxTicks (ten minutes)
CPP = os.path.join(ROOT, "app/src/main/cpp")
FIXTURE = os.path.join(ROOT, "app/src/test/cpp/ws_test_song.h")
KOTLIN = os.path.join(ROOT, "core/src/commonMain/kotlin/org/thortracker/backend/WsPlayerImage.kt")
VGM_SONGS = ("ws_song", "wsc_vgm", "ws_tone")

# WS_FX_* name -> effect letter (the spec's effect table)
FX_LETTERS = {
    "ARPEGGIO": "0", "SLIDE_UP": "1", "SLIDE_DOWN": "2", "PORTAMENTO": "3", "VIBRATO": "4", "PAN": "8",
    "VOLUME_SLIDE": "A", "JUMP": "B", "VOLUME": "C", "BREAK": "D", "SPEED": "F", "DELAY": "G", "HOLD": "H",
    "SHIFT": "K", "TAP": "N", "FINE": "P", "CUT": "S", "TIMER": "T", "SWEEP": "U", "SWEEP_TIME": "V", "WAVE": "W",
}


def c_bytes(name, data):
    lines = ["static const unsigned char %s[%d] = {" % (name, len(data))]
    for i in range(0, len(data), 24):
        lines.append("    " + ",".join("%d" % b for b in data[i:i + 24]) + ",")
    lines.append("};")
    return "\n".join(lines)


def c_list(kind, name, values, per_line=24):
    values = list(values) or [0]
    lines = ["static const %s %s[%d] = {" % (kind, name, len(values))]
    for i in range(0, len(values), per_line):
        lines.append("    " + ",".join(map(str, values[i:i + per_line])) + ",")
    lines.append("};")
    return "\n".join(lines)


def used(image):
    """Bytes up to the end of the data region in use (the rest of an image is zero)."""
    end = R.OFF_DATA + R.u32(image, R.H_DATA_SIZE)
    assert not any(image[end:])
    return image[:end]


def fnv64(data):
    h = 0xCBF29CE484222325
    for b in data:
        h = ((h ^ b) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def trace_arrays(name, image_bytes):
    """The reference timeline of TICKS ticks as C arrays (see the file comment)."""
    image = R.Image(image_bytes)
    reset, ticks = R.trace(image, TICKS)
    offsets, space, addr, vals, pos, irqs = [0], [], [], [], [], []
    ifirst, iwfirst, idelay, icount, ispace, iaddr, ivals = [0], [], [], [], [], [], []
    for k, (p, start, writes, interrupts, _) in enumerate(ticks):
        assert start == k * R.TICK_CYCLES
        for s_, a, v in writes:
            space.append(s_)
            addr.append(a)
            vals.append(v)
        offsets.append(len(addr))
        pos.extend(p)
        irqs.append(len(interrupts))
        iwfirst.append(len(iaddr))
        for cycle, w in interrupts:
            if not w:
                continue
            assert 0 <= cycle - start < R.TICK_CYCLES and len(w) < 256
            idelay.append(cycle - start)
            icount.append(len(w))
            for s_, a, v in w:
                ispace.append(s_)
                iaddr.append(a)
                ivals.append(v)
        ifirst.append(len(idelay))
    out = [
        "#define %s_reset_n %d" % (name, len(reset)),
        c_list("unsigned char", name + "_reset_space", [w[0] for w in reset]),
        c_list("unsigned short", name + "_reset_addr", [w[1] for w in reset]),
        c_list("unsigned char", name + "_reset_vals", [w[2] for w in reset]),
        c_list("int", name + "_offsets", offsets),
        c_list("unsigned char", name + "_space", space),
        c_list("unsigned short", name + "_addr", addr),
        c_list("unsigned char", name + "_vals", vals),
        c_list("short", name + "_pos", pos),
        c_list("short", name + "_irqs", irqs),
        c_list("int", name + "_ifirst", ifirst),
        c_list("int", name + "_iwfirst", iwfirst),
        c_list("int", name + "_idelay", idelay),
        c_list("unsigned char", name + "_icount", icount),
        c_list("unsigned char", name + "_ispace", ispace),
        c_list("unsigned short", name + "_iaddr", iaddr),
        c_list("unsigned char", name + "_ivals", ivals),
    ]
    return "\n".join(out), len(addr) + len(iaddr)


def pattern_bytes(image, img, order, lane):
    """Image offsets of every row of (order, lane): [(mask, note, slot, [(code_at, param_at)])], runs skipped."""
    at = R.OFF_DATA + R.u16(image, R.OFF_ORDERS + 2 * (R.CHANNELS * order + lane))
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


def corruptions(image):
    """[(offset, value)]: single-byte changes the loader must reject (each alone, on a fresh copy)."""
    img = R.Image(image)
    out = [(0, ord("X")), (R.H_LAYOUT, 2), (R.H_MODEL, 2), (R.H_SPEED, 0), (R.H_INSTRUMENTS, 65), (R.H_ORDERS + 1, 1),
           (R.H_LOOP, img.orders), (R.H_WAVES, 65), (R.H_SAMPLES, 65), (R.H_TIMER, 0), (R.H_SHIFT, 4),
           (R.H_OUTPUT, 2), (17, 1), (24, 1), (R.H_DATA_SIZE + 3, 1), (8000, 1), (R.OFF_DATA - 1, 1)]
    if img.orders < R.MAX_ORDERS:
        out.append((R.OFF_ROWS + img.orders, 1))
        out.append((R.OFF_ORDERS + 2 * R.CHANNELS * img.orders, 1))
    if not img.color:
        out.append((R.OFF_ORDERS + 2 * R.HYPER_VOICE, 1))
    # instruments: slot 1 is defined in every reference song
    rec1 = R.OFF_INSTRUMENTS
    out.append((rec1 + R.I_FLAGS, image[rec1] | 3))
    out.append((rec1 + R.I_FLAGS, image[rec1] | 0x40))
    if img.instruments < R.MAX_INSTRUMENTS:
        out.append((R.OFF_INSTRUMENTS + R.INSTRUMENT_SIZE * img.instruments, 0x80))
    kind1 = image[rec1] & R.KIND_MASK
    if kind1 == R.KIND_TONE:
        out.append((rec1 + R.I_LOOP, 1))
        out.append((rec1 + R.I_LOOP_START + 1, 1))
    env = rec1 + R.I_ENVELOPES + R.ENVELOPE_BYTES * R.ENV_VOLUME
    if image[env + 2]:
        out.append((env + 3, image[env + 2]))                       # loop = length
        out.append((R.OFF_DATA + R.u16(image, env), 16))             # a volume above 15
        out.append((env + 5, 1))                                     # the reserved envelope byte
    # waves and samples
    if img.waves:
        out.append((R.OFF_WAVES, 16))
    if img.waves < R.MAX_WAVES:
        out.append((R.OFF_WAVES + R.WAVE_SIZE * img.waves, 1))
    if img.samples:
        out.append((R.OFF_SAMPLES + R.S_FLAGS, 4))
        out.append((R.OFF_SAMPLES + R.S_FRAMES + 2, 1))
    if img.samples < R.MAX_SAMPLES:
        out.append((R.OFF_SAMPLES + R.SAMPLE_ENTRY_SIZE * img.samples, 1))
    # patterns: the first of each kind of byte, orders then lanes
    found = {}
    for o in range(img.orders):
        for c in range(img.lanes):
            for mask, note, slot, effects in pattern_bytes(image, img, o, c):
                found.setdefault("mask", mask)
                if note is not None and R.NOTE_MIN <= image[note] <= R.NOTE_MAX:
                    found.setdefault("note", note)
                if slot is not None:
                    found.setdefault("slot", slot)
                    if c == 0:
                        found.setdefault("slot_lane0", slot)
                for code_at, param_at in effects:
                    found.setdefault("code", code_at)
                    found.setdefault("param_" + R.LETTERS[image[code_at]], param_at)
    out += [(found["mask"], image[found["mask"]] | 0x20), (found["note"], R.NOTE_MIN - 1),
            (found["note"], R.NOTE_MAX + 1), (found["slot"], 63)]
    if "code" in found:
        out.append((found["code"], R.FX["5"]))
    noise_slots = [n for n in range(1, img.instruments + 1)
                   if img.instrument(n) is not None and img.instrument(n)[R.I_FLAGS] & R.KIND_MASK == R.KIND_NOISE]
    if noise_slots and "slot_lane0" in found:
        out.append((found["slot_lane0"], noise_slots[0]))
    bad_param = {"C": 0x10, "F": 0, "T": 0, "N": 8, "V": 0x20, "K": 4, "W": img.waves, "B": img.orders, "H": 2}
    for letter, value in bad_param.items():
        if "param_" + letter in found:
            out.append((found["param_" + letter], value))
    if not img.color and "code" in found:
        out.append((found["code"], R.FX["H"]))
    seen, result = set(), []
    for off, val in out:
        if (off, val) not in seen and image[off] != val:
            seen.add((off, val))
            result.append((off, val))
    return result


def vgm_lines(name, image_bytes):
    data, (total, loop_offset, loop_samples, ticks) = R.vgm(R.Image(image_bytes), VGM_MAX_TICKS)
    assert loop_offset, "%s: the reference VGM must close its loop within %d ticks" % (name, VGM_MAX_TICKS)
    return [
        "static const unsigned %s_vgm_size = %du;" % (name, len(data)),
        "static const unsigned long long %s_vgm_fnv64 = 0x%016XULL;" % (name, fnv64(data)),
        "static const unsigned %s_vgm_loop_offset = %du;" % (name, loop_offset),
        "static const unsigned %s_vgm_total_samples = %du;" % (name, total),
        "static const unsigned %s_vgm_loop_samples = %du;" % (name, loop_samples),
        "static const unsigned %s_vgm_ticks = %du;" % (name, ticks),
    ], len(data)


def write_fixture():
    images = {name: S.build(fn()) for name, fn in S.SONGS.items()}
    for data in images.values():
        R.Image(data)  # every fixture passes the reference loader
    parts = ["/* Generated by tools/audio/prepare_ws.py - do not edit. WonderSwan host-test fixtures (docs/AUDIO_ENGINE.md,",
             " * \"WonderSwan\": \"Preparing the fixtures and constants\"): the reference songs of tools/audio/ws/ws_song.py as",
             " * images (NAME_image: the bytes up to the end of the data in use; the rest of the 131072 bytes is zero) and the",
             " * reference player's (ws_ref.py) timeline: the reset batch NAME_reset_*; tick t writes NAME_space/addr/vals",
             " * [NAME_offsets[t] .. NAME_offsets[t + 1]) at cycle 40704 t, NAME_pos[3t .. 3t + 2] = (order, row, tick) before",
             " * it, NAME_irqs[t] = its timer interrupts; the interrupts that wrote anything are events NAME_ifirst[t] ..",
             " * NAME_ifirst[t + 1], event e at cycle 40704 t + NAME_idelay[e] writing NAME_icount[e] writes of NAME_ispace/",
             " * iaddr/ivals, tick t's first at NAME_iwfirst[t]. Reference VGMs (no GD3) rendered with WS_TEST_VGM_MAX_TICKS.",
             " * NAME_corrupt_off/val: single-byte changes (each alone) the loader rejects. */",
             "#define WS_TEST_TICKS %d" % TICKS,
             "#define WS_TEST_VGM_MAX_TICKS %d" % VGM_MAX_TICKS]
    total = 0
    for name, data in images.items():
        u = used(data)
        parts.append(c_bytes(name + "_image", u))
        parts.append("static const unsigned %s_image_size = %du;" % (name, len(u)))
        text, n = trace_arrays(name, data)
        parts.append(text)
        total += n
        if name in VGM_SONGS:
            lines, _ = vgm_lines(name, data)
            parts += lines
        bad = corruptions(data)
        parts.append("#define %s_corrupt_n %d" % (name, len(bad)))
        parts.append(c_list("int", name + "_corrupt_off", [o for o, _ in bad]))
        parts.append(c_list("unsigned char", name + "_corrupt_val", [v for _, v in bad]))
    with open(FIXTURE, "w") as f:
        f.write("\n".join(parts) + "\n")
    print("%s (%d reference writes)" % (FIXTURE, total))


def read_cpp(name):
    with open(os.path.join(CPP, name)) as f:
        return f.read()


def c_constants():
    """#define WS_/WSP_/WSM_ values and enum WS_FX_* codes of the engine headers, evaluated, with their source text."""
    values, source = {}, {}
    for name in ("ws/ws_image.h", "ws/ws_player.h", "ws/ws_machine.h", "ws/ws_vgm.h"):
        for line in read_cpp(name).splitlines():
            m = re.match(r"#define (WS[MP]?_\w+) +([^/]+?)\s*(/\*.*)?$", line.rstrip())
            if m and not m.group(2).startswith('"'):
                literal = m.group(2)
                expr = re.sub(r"\b(\d+|0x[0-9A-Fa-f]+)u\b", r"\1", literal)
                try:
                    values[m.group(1)] = eval(expr, {}, dict(values))
                except (NameError, SyntaxError):
                    continue  # function-like or non-numeric macros are not constants
                comment = m.group(3) or ""
                comment = comment[2:-2].strip() if comment.endswith("*/") else ""
                source[m.group(1)] = (name, literal, comment)
            m = re.match(r"\s+(WS_FX_\w+) = (\d+),", line)
            if m:
                values[m.group(1)] = int(m.group(2))
    return values, source


def c_table(text, name):
    """The integers of `name[...] = { ... }` in C source text."""
    m = re.search(r"\b%s\s*\[\s*\d*\s*\]\s*=\s*\{([^}]*)\}" % re.escape(name), text)
    assert m, "%s not found in ws_player.c" % name
    return [int(v) for v in re.findall(r"-?\d+", re.sub(r"/\*.*?\*/", "", re.sub(r"\b(\d+)u\b", r"\1", m.group(1)), flags=re.S))]


def loader_problems():
    """Every reason ws_load_image can report (ws_image.c), in source order."""
    text = read_cpp("ws/ws_image.c")
    found = []
    for m in re.finditer(r'(?:fail\(\s*image,\s*|why = )"([^"]+)"', text):
        if m.group(1) not in found:
            found.append(m.group(1))
    assert found, "no loader reasons found in ws_image.c"
    return found


def gd3_strings():
    text = read_cpp("ws/ws_vgm.c")
    found = {k: re.search(r'#define GD3_%s "([^"]+)"' % k, text) for k in ("SYSTEM_MONO", "SYSTEM_COLOR", "CONVERTER")}
    assert all(found.values()), "GD3_SYSTEM_MONO / GD3_SYSTEM_COLOR / GD3_CONVERTER #defines not found in ws_vgm.c"
    return {k: v.group(1) for k, v in found.items()}


def kotlin_string(s):
    return '"%s"' % s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def check_reference(c):
    """The reference player and builder must use the C sources' numbers."""
    pairs = {
        "WS_IMAGE_SIZE": R.IMAGE_SIZE, "WS_LAYOUT": R.LAYOUT, "WS_MODEL_MONO": R.MODEL_MONO,
        "WS_MODEL_COLOR": R.MODEL_COLOR, "WS_CHANNELS": R.CHANNELS, "WS_CHANNELS_MONO": R.CHANNELS_MONO,
        "WS_HYPER_VOICE": R.HYPER_VOICE, "WS_VOICE_CHANNEL": R.VOICE_CHANNEL, "WS_SWEEP_CHANNEL": R.SWEEP_CHANNEL,
        "WS_NOISE_CHANNEL": R.NOISE_CHANNEL, "WS_MAX_ORDERS": R.MAX_ORDERS, "WS_MAX_INSTRUMENTS": R.MAX_INSTRUMENTS,
        "WS_MAX_WAVES": R.MAX_WAVES, "WS_MAX_SAMPLES": R.MAX_SAMPLES, "WS_MAX_EFFECTS": R.MAX_EFFECTS,
        "WS_MAX_TIMER": R.MAX_TIMER, "WS_MAX_SHIFT": R.MAX_SHIFT, "WS_MAX_ENVELOPE": R.MAX_ENVELOPE,
        "WS_MAX_SAMPLE_FRAMES": R.MAX_SAMPLE_FRAMES, "WS_MAX_TAP": R.MAX_TAP, "WS_MAX_SWEEP_TIME": R.MAX_SWEEP_TIME,
        "WS_MAX_DMA_RATE": R.MAX_DMA_RATE, "WS_DMA_BASE": R.DMA_BASE, "WS_WAVE_RAM": R.WAVE_RAM,
        "WS_WAVE_SIZE": R.WAVE_SIZE, "WS_WAVE_MAX": R.WAVE_MAX, "WS_NOTE_OFF": R.NOTE_OFF, "WS_NOTE_MIN": R.NOTE_MIN,
        "WS_NOTE_MAX": R.NOTE_MAX, "WS_OUTPUT_HEADPHONES": R.OUTPUT_HEADPHONES, "WS_OUTPUT_SPEAKER": R.OUTPUT_SPEAKER,
        "WS_H_MAGIC": R.H_MAGIC, "WS_H_LAYOUT": R.H_LAYOUT, "WS_H_MODEL": R.H_MODEL, "WS_H_SPEED": R.H_SPEED,
        "WS_H_INSTRUMENTS": R.H_INSTRUMENTS, "WS_H_ORDERS": R.H_ORDERS, "WS_H_LOOP": R.H_LOOP, "WS_H_WAVES": R.H_WAVES,
        "WS_H_SAMPLES": R.H_SAMPLES, "WS_H_TIMER": R.H_TIMER, "WS_H_SHIFT": R.H_SHIFT, "WS_H_OUTPUT": R.H_OUTPUT,
        "WS_H_DATA_SIZE": R.H_DATA_SIZE, "WS_HEADER_SIZE": R.HEADER_SIZE, "WS_OFF_ROWS": R.OFF_ROWS,
        "WS_OFF_ORDERS": R.OFF_ORDERS, "WS_OFF_INSTRUMENTS": R.OFF_INSTRUMENTS, "WS_INSTRUMENT_SIZE": R.INSTRUMENT_SIZE,
        "WS_OFF_WAVES": R.OFF_WAVES, "WS_OFF_SAMPLES": R.OFF_SAMPLES, "WS_SAMPLE_ENTRY_SIZE": R.SAMPLE_ENTRY_SIZE,
        "WS_OFF_DATA": R.OFF_DATA, "WS_DATA_CAPACITY": R.DATA_CAPACITY, "WS_S_OFFSET": R.S_OFFSET,
        "WS_S_FRAMES": R.S_FRAMES, "WS_S_SIGNED_OFFSET": R.S_SIGNED_OFFSET, "WS_S_FLAGS": R.S_FLAGS,
        "WS_SAMPLE_STEREO": R.SAMPLE_STEREO, "WS_SAMPLE_SIGNED": R.SAMPLE_SIGNED, "WS_I_FLAGS": R.I_FLAGS,
        "WS_I_SOURCE": R.I_SOURCE, "WS_I_ROOT": R.I_ROOT, "WS_I_LOOP": R.I_LOOP, "WS_I_LOOP_START": R.I_LOOP_START,
        "WS_I_SWEEP": R.I_SWEEP, "WS_I_SWEEP_TIME": R.I_SWEEP_TIME, "WS_I_ENVELOPES": R.I_ENVELOPES,
        "WS_ENVELOPE_BYTES": R.ENVELOPE_BYTES, "WS_ENVELOPE_COUNT": R.ENVELOPE_COUNT, "WS_ENV_VOLUME": R.ENV_VOLUME,
        "WS_ENV_ARPEGGIO": R.ENV_ARPEGGIO, "WS_ENV_PITCH": R.ENV_PITCH, "WS_ENV_WAVE": R.ENV_WAVE,
        "WS_ENV_NONE": R.ENV_NONE, "WS_FLAG_DEFINED": R.FLAG_DEFINED, "WS_FLAG_RELATIVE": R.FLAG_RELATIVE,
        "WS_FLAG_DMA": R.FLAG_DMA, "WS_FLAG_LFSR_RESET": R.FLAG_LFSR_RESET, "WS_FLAG_REVERSE": R.FLAG_REVERSE,
        "WS_KIND_MASK": R.KIND_MASK, "WS_KIND_TONE": R.KIND_TONE, "WS_KIND_NOISE": R.KIND_NOISE,
        "WS_KIND_SAMPLE": R.KIND_SAMPLE, "WS_SPACE_PORT": R.SPACE_PORT, "WS_SPACE_RAM": R.SPACE_RAM,
        "WS_CPU_HZ": R.CPU_HZ, "WS_TICK_CYCLES": R.TICK_CYCLES, "WS_LINE_CYCLES": R.LINE_CYCLES,
        "WS_BLOCK_CYCLES": R.BLOCK_CYCLES, "WS_DMA_TRANSFER_CYCLE": R.DMA_TRANSFER_CYCLE,
        "WSP_PITCH_MIN": R.PITCH_MIN, "WSP_PITCH_MAX": R.PITCH_MAX, "WSP_SLIDE_LIMIT": R.SLIDE_LIMIT,
        "WSM_SEEK_TICKS": 4528, "WSM_RELEASE_SECONDS": 10, "WSM_MAX_TICK_FRAMES": 1273, "WS_VGM_CLOCK": R.VGM_CLOCK,
    }
    bad = ["%s: C %s, reference %s" % (k, c.get(k), v) for k, v in pairs.items() if c.get(k) != v]
    fx = {k[len("WS_FX_"):]: v for k, v in c.items() if k.startswith("WS_FX_")}
    if set(fx) != set(FX_LETTERS):
        bad.append("WS_FX_* names %s differ from the spec's effects %s" % (sorted(fx), sorted(FX_LETTERS)))
    for name, code in fx.items():
        if name in FX_LETTERS and code != R.FX[FX_LETTERS[name]]:
            bad.append("WS_FX_%s = %d, effect %s is %d" % (name, code, FX_LETTERS[name], R.FX[FX_LETTERS[name]]))
    if set(fx.values()) != set(R.EFFECTS):
        bad.append("the C effect codes and the reference player's differ")
    player = read_cpp("ws/ws_player.c")
    period = c_table(player, "WSP_PERIOD_Q16")
    if period != list(R.PERIOD_Q16):
        diff = [i for i in range(min(len(period), 768)) if period[i] != R.PERIOD_Q16[i]]
        bad.append("WSP_PERIOD_Q16 (%d entries) differs from round(96000 / (440 * 2^((i / 64 - 57) / 12)) * 65536)"
                   " at %s" % (len(period), diff[:8] if diff else "its length"))
    sine = c_table(player, "WSP_SINE")
    formula = [math.floor(127 * math.sin(2 * math.pi * i / 64) + 0.5) for i in range(64)]
    if sine != list(R.SINE) or list(R.SINE) != formula:
        bad.append("WSP_SINE / ws_ref.SINE differ from floor(127 sin(2 pi i / 64) + 0.5)")
    below = (R.PERIOD_Q16[R.PITCH_MIN - 1] + (1 << 16)) >> 17  # pitch 1166 (octave 1) without the clamp
    if R.period(57 * 64) != 218 or R.period(R.PITCH_MIN) != 2048 or below <= 2048:
        bad.append("period(): A-4 = %d (218), PITCH_MIN = %d (2048), pitch 1166 unclamped = %d (> 2048)"
                   % (R.period(57 * 64), R.period(R.PITCH_MIN), below))
    assert not bad, "C sources and reference disagree:\n  " + "\n  ".join(bad)


def write_kotlin():
    c, source = c_constants()
    check_reference(c)
    fx = {k[len("WS_FX_"):]: v for k, v in c.items() if k.startswith("WS_FX_")}
    gd3 = gd3_strings()

    def kdoc(comment):
        return ["    /** %s */" % comment.replace("*/", "* /")] if comment else []

    lines = [
        "// GENERATED by tools/audio/prepare_ws.py from the engine sources (app/src/main/cpp/ws) -- do not edit.",
        "package org.thortracker.backend",
        "",
        "/**",
        " * The WonderSwan systems' native contract: Thor Tracker's own player on the WonderSwan sound unit (a C port of",
        " * ares's APU), engines `\"ws-wonderswan\"` and `\"ws-wonderswan-color\"`. The image layout the lowering writes, its",
        " * limits, the effect codes and the loader's rejection messages, as `ws_image.h`, `ws_player.h` and `ws_machine.h`",
        " * define them (`docs/AUDIO_ENGINE.md`, \"WonderSwan\": the specification). Multi-byte fields are little-endian.",
        " */",
        "object WsPlayerImage {",
        "    /** Native engine ids ([org.thortracker.system.PlaybackEngine.id]). */",
        '    const val ENGINE_ID_MONO = "ws-wonderswan"',
        '    const val ENGINE_ID_COLOR = "ws-wonderswan-color"',
        '    const val MAGIC = "TTWS"',
        "",
        "    /** Every `WS_*` constant of `ws_image.h`, without its prefix. */",
    ]
    for name, (header, literal, comment) in source.items():
        if header != "ws/ws_image.h" or not name.startswith("WS_"):
            continue
        value = c[name]
        text = "0x%X" % value if literal.lower().startswith("0x") else str(value)
        lines += kdoc(comment)
        lines.append("    const val %s = %s" % (name[len("WS_"):], text))
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
        "    const val PITCH_UNITS_PER_SEMITONE = %d" % R.UNITS_PER_SEMITONE,
        "    const val PITCH_MIN = %d" % c["WSP_PITCH_MIN"],
        "    const val PITCH_MAX = %d" % c["WSP_PITCH_MAX"],
        "",
        "    /** Seek: [org.thortracker.system.PlaybackEngine.seekTicks] budget of clocked ticks. */",
        "    const val SEEK_TICKS = %d" % c["WSM_SEEK_TICKS"],
        "",
        "    /** Player ticks per second: one per display frame, 3072000 / 40704 Hz. */",
        "    const val TICKS_PER_SECOND = %r" % (R.CPU_HZ / R.TICK_CYCLES),
        "",
        "    /** Effect codes (column code byte); [EFFECT_LETTERS][code] is the name. */",
        '    const val EFFECT_LETTERS = "%s"' % R.LETTERS,
    ]
    for name, v in sorted(fx.items(), key=lambda kv: kv[1]):
        lines.append("    const val FX_%s = %d // %s" % (name, v, R.LETTERS[v]))
    lines += [
        "",
        "    /** VGM export (native `ws_export_vgm`): GD3 system and converter names the file carries. */",
        "    const val GD3_SYSTEM_MONO = %s" % kotlin_string(gd3["SYSTEM_MONO"]),
        "    const val GD3_SYSTEM_COLOR = %s" % kotlin_string(gd3["SYSTEM_COLOR"]),
        "    const val GD3_CONVERTER = %s" % kotlin_string(gd3["CONVERTER"]),
        "",
        "    /**",
        "     * Every reason the native loader (`ws_load_image`) rejects an image with (`ws_image.c` order). A song the",
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
