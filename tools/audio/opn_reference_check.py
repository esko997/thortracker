#!/usr/bin/env python3
"""Independent checks of the OPN / OPNA evidence that app/src/test/cpp/opn_machine_test.c writes.

Python 3 standard library only, and nothing from the C code: the VGM files are parsed here from the VGM 1.71
specification, the register values they log are turned into frequencies with the chips' formulas below, and the pitch
of the engine's own renders (the WAV files, played by the hosts' ops table) is measured here and compared with them.

Usage:
  tools/audio/run_native_host_test.sh <dir>          # writes the evidence (default dir: evidence/audio)
  python3 tools/audio/opn_reference_check.py <dir>   # exit status 0 = every check passed

Inputs (listed in <dir>/opn_evidence.txt by the host test):
  tone <name> <chip> <part> <channel> <note>   <name>.vgm (the tone's VGM, init + key-on + held) and <name>.wav (the
                                               same image rendered by the engine, 48 kHz stereo 16-bit)
  song <name> <chip> <ticks>                   <name>.vgm (the test song's VGM with its loop) and <name>.bin (its image)

Formulas (fM = the VGM header clock: YM2203 $44 = 3993600 Hz, YM2608 $48 = 7987200 Hz, the PC-88 / PC-98 clocks, both
at the power-on prescaler):
  FM:     f = F-number x 2^(block - 1) x fM / (P x 2^20), P = 72 (YM2203: one FM sample per 72 clocks) or 144 (YM2608),
          F-number and block from $A0+c / $A4+c (port 1 for YM2608 FM 4-6); FM 3 operator 4 in special mode: $A2/$A6.
  SSG:    f = fM / (32 x TP) on the YM2203 and fM / (64 x TP) on the YM2608 (124800 / TP Hz on both PC clocks),
          TP = 12-bit tone period ($00+2i, $01+2i). Derivation: the SSG is an AY-3-8910 core, tone = SSG clock /
          (16 x TP), and at the power-on prescaler (/6 FM) its effective clock is 1996800 Hz on both chips: ymfm's
          ssg_effective_clock gives fM x 2 / 4 = 3993600 x 2 / 4 on the YM2203 and fM / 4 = 7987200 / 4 on the YM2608,
          so 1996800 / (16 x TP) = fM / (32 x TP) resp. fM / (64 x TP). (The YM2203 application manual's own
          fM / (64 x TP) wording is not reconciled with hardware here; the PC-88 tuning follows the 1996800 Hz clock.)
  ADPCM-B: playback rate = delta-N / 65536 x fM / 144 ($109/$10A), the YM2608 manual's formula; the expected tone is
          the rate times the cycles per sample of the sample itself, decoded here from the VGM's data block ($67 $66
          $81: the ADPCM-B memory) between the start ($102/$103) and end ($104/$105) registers in 32-byte units (8-bit
          DRAM mode, $101 bit 1) with the YM2608 manual's ADPCM-B decoder (step x {0.9 0.9 0.9 0.9 1.2 1.6 2.0 2.4}).

Checks per tone: VGM structure (below), the channel keyed / enabled, the registers' pitch within 3 cents of 12-TET
(A-4 = 440 Hz; SSG: the tone period is the nearest integer to the exact one), and the WAV's measured pitch (rising zero
crossings of the left channel after 100 ms) within 0.15 % (FM), 0.2 % (SSG) or 0.5 % (ADPCM-B) of the registers'.
ADPCM-B root and +12 renders: pitch ratio 2 within 0.5 %.
Checks per song: header (ident, version 1.71, EOF offset, the chip's clock and no other, data offset), only the chip's
write commands ($55 YM2203; $56 / $57 YM2608) with registers that exist on the chip, waits summing to the header's
total, which equals ticks x 16 x (256 - TB) native samples at 44.1 kHz within one sample (TB from the image), the loop
offset on a command boundary with the header's loop samples after it, the end command; on the YM2608 with samples
exactly one data block, type $81, first, memory size 262144, start 0, equal to the image's used ADPCM-B memory; else
none. With THOR_RHYTHM_ROM set to the user's YM2608 rhythm ROM (SHA-256 checked; never committed): no 32-byte window
of it in any VGM file.
"""
import array
import hashlib
import math
import os
import struct
import sys
import wave
from pathlib import Path

ROM_SHA256 = "53afd0fa9c62eda3e2be939e23f3adf48a2af8ad37bb1640261726c5d5adeba8"
CLOCK = {"YM2203": 3993600, "YM2608": 7987200}
CLOCK_AT = {"YM2203": 0x44, "YM2608": 0x48}
FM_DIV = {"YM2203": 72, "YM2608": 144}
SSG_DIV = {"YM2203": 32, "YM2608": 64}
SSG_FIRST = {"YM2203": 3, "YM2608": 6}
TOLERANCE = {"fm": 0.0015, "fm3op4": 0.0015, "ssg": 0.002, "adpcm": 0.005}

failures = 0
checks = 0


def expect(ok, message):
    global failures, checks
    checks += 1
    if not ok:
        failures += 1
        print("  FAIL " + message)
    return ok


def u32(b, at):
    return struct.unpack_from("<I", b, at)[0]


class Vgm:
    """A parsed VGM file: the commands in order, the final register state per port, waits, data blocks, loop."""

    def __init__(self, data, chip):
        self.data = data
        self.chip = chip
        self.problems = []
        self.regs = [[0] * 256, [0] * 256]
        self.writes = []  # (port, reg, value)
        self.samples = 0
        self.loop_samples = 0
        self.loop_seen = False
        self.blocks = []  # (offset of the command, type, payload)
        self.first_command = None
        self.end_at = None
        if len(data) < 0x100 or data[:4] != b"Vgm ":
            self.problems.append("not a VGM file")
            return
        self.eof = u32(data, 4) + 4
        self.version = u32(data, 8)
        self.total = u32(data, 0x18)
        self.loop = 0x1C + u32(data, 0x1C) if u32(data, 0x1C) else 0
        self.loop_total = u32(data, 0x20)
        self.gd3 = 0x14 + u32(data, 0x14) if u32(data, 0x14) else 0
        start = 0x34 + u32(data, 0x34)
        self.first_command = start
        allowed = {0x55} if chip == "YM2203" else {0x56, 0x57}
        i = start
        while i < len(data):
            after = self.loop and i >= self.loop
            if self.loop and i == self.loop:
                self.loop_seen = True
            c = data[i]
            wait = 0
            if c in (0x55, 0x56, 0x57):
                if c not in allowed:
                    self.problems.append("command %02X at %d is not the %s's" % (c, i, chip))
                port = 1 if c == 0x57 else 0
                reg, val = data[i + 1], data[i + 2]
                self.writes.append((port, reg, val))
                self.regs[port][reg] = val
                i += 3
            elif c == 0x61:
                wait = data[i + 1] | data[i + 2] << 8
                i += 3
            elif c == 0x62:
                wait, i = 735, i + 1
            elif c == 0x63:
                wait, i = 882, i + 1
            elif 0x70 <= c <= 0x7F:
                wait, i = (c & 15) + 1, i + 1
            elif c == 0x67:
                if data[i + 1] != 0x66:
                    self.problems.append("bad data block at %d" % i)
                    return
                size = u32(data, i + 3)
                self.blocks.append((i, data[i + 2], data[i + 7:i + 7 + size]))
                i += 7 + size
            elif c == 0x66:
                self.end_at = i
                break
            else:
                self.problems.append("unknown command %02X at %d" % (c, i))
                return
            self.samples += wait
            if after:
                self.loop_samples += wait

    def structure(self, name):
        """Header and command stream checks common to every file."""
        ok = expect(not self.problems, "%s: commands parse (%s)" % (name, "; ".join(self.problems)))
        if self.problems or self.end_at is None:
            return expect(False, "%s: ends with the end command $66" % name)
        tail_ok = self.end_at + 1 == (self.gd3 or len(self.data))
        ok &= expect(tail_ok, "%s: the end command is the last before %s" % (name, "the GD3 tag" if self.gd3 else "EOF"))
        ok &= expect(self.eof == len(self.data), "%s: EOF offset %d = size %d" % (name, self.eof, len(self.data)))
        ok &= expect(self.version == 0x171, "%s: version %X (1.71)" % (name, self.version))
        other = "YM2608" if self.chip == "YM2203" else "YM2203"
        ok &= expect(u32(self.data, CLOCK_AT[self.chip]) == CLOCK[self.chip] and u32(self.data, CLOCK_AT[other]) == 0,
                     "%s: %s clock %d at $%02X, none for the %s" % (name, self.chip, u32(self.data, CLOCK_AT[self.chip]),
                                                                    CLOCK_AT[self.chip], other))
        ok &= expect(self.samples == self.total, "%s: waits %d = header total %d" % (name, self.samples, self.total))
        if self.loop:
            ok &= expect(self.loop_seen and self.loop_samples == self.loop_total,
                         "%s: loop offset %d on a command, %d samples after it (header %d)" % (
                             name, self.loop, self.loop_samples, self.loop_total))
        else:
            ok &= expect(self.loop_total == 0, "%s: no loop offset, no loop samples" % name)
        bad = [(p, r) for p, r, _ in self.writes if not register_exists(self.chip, p, r)]
        ok &= expect(not bad, "%s: every written register exists on the %s (%s)" % (
            name, self.chip, ", ".join("%d:$%02X" % b for b in bad[:8])))
        types = {t for _, t, _ in self.blocks}
        ok &= expect(types <= {0x81}, "%s: no data block other than $81 (%s)" % (name, sorted(types)))
        return ok


def register_exists(chip, port, reg):
    """The register map of the YM2203 / YM2608 manuals (port 1 = the YM2608's $100-$1FF)."""
    opna = chip == "YM2608"
    if port == 1 and not opna:
        return False
    if port == 0:
        if reg <= 0x0D:  # SSG
            return True
        if 0x10 <= reg <= 0x1D:  # rhythm: key/dump, total level, per-drum pan + level
            return opna and (reg <= 0x11 or reg >= 0x18)
        if 0x21 <= reg <= 0x2F:  # global: LFO (YM2608), timers, mode, key on, 6-channel mode (YM2608), prescaler
            return reg in (0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x2F) or opna and reg in (0x22, 0x29)
    elif reg <= 0x10:  # ADPCM-B
        return True
    if not 0x30 <= reg <= 0xB6 or (reg & 3) == 3:
        return False
    if 0xA8 <= reg <= 0xAE:  # FM 3's per-operator frequencies: port 0 only
        return port == 0
    if 0xB4 <= reg:  # pan / AMS / FMS: YM2608 only
        return opna
    return reg < 0xA8 or 0xB0 <= reg


def wav_left(path):
    with wave.open(str(path), "rb") as w:
        if w.getnchannels() != 2 or w.getsampwidth() != 2:
            raise ValueError("%s: not 16-bit stereo" % path)
        rate = w.getframerate()
        pcm = array.array("h")
        pcm.frombytes(w.readframes(w.getnframes()))
    if sys.byteorder != "little":
        pcm.byteswap()
    return pcm[0::2], rate


def rising_crossings(x, start=0):
    """Cycles per sample from rising zero crossings (mean removed, linear interpolation)."""
    x = x[start:]
    if len(x) < 2:
        return 0.0
    mean = sum(x) / len(x)
    first = last = None
    count = 0
    prev = x[0] - mean
    for i in range(1, len(x)):
        cur = x[i] - mean
        if prev < 0 <= cur:
            t = (i - 1) + prev / (prev - cur)
            if first is None:
                first = t
            else:
                last = t
                count += 1
        prev = cur
    return count / (last - first) if count and last > first else 0.0


def adpcm_b_decode(data):
    """The YM2608 manual's ADPCM-B decoder: 4-bit codes, high nibble first, 16-bit accumulator."""
    scale = (57, 57, 57, 57, 77, 102, 128, 153)
    acc, step, out = 0, 127, []
    for byte in data:
        for code in (byte >> 4, byte & 15):
            delta = (2 * (code & 7) + 1) * step // 8
            acc = max(-32768, min(32767, acc - delta if code & 8 else acc + delta))
            step = max(127, min(24576, step * scale[code & 7] // 64))
            out.append(acc)
    return out


def keyed(vgm, ch):
    for port, reg, val in reversed(vgm.writes):
        if port == 0 and reg == 0x28 and (val & 7) == (ch % 3) | (4 if ch >= 3 else 0):
            return val & 0xF0 != 0
    return False


def check_tone(d, name, chip, part, ch, note, measured_hz):
    vgm = Vgm((d / (name + ".vgm")).read_bytes(), chip)
    vgm.structure(name + ".vgm")
    r0, r1 = vgm.regs
    fm = CLOCK[chip]
    if part in ("fm", "fm3op4"):
        if part == "fm":
            port, i = (ch // 3, ch % 3) if chip == "YM2608" else (0, ch)
            hi, lo = (r1 if port else r0)[0xA4 + i], (r1 if port else r0)[0xA0 + i]
            target = note
        else:
            expect(r0[0x27] & 0xC0 == 0x40, "%s: FM 3 special mode ($27 = %02X)" % (name, r0[0x27]))
            hi, lo = r0[0xA6], r0[0xA2]
            target = note + 12  # operator 4's offset in the test instrument: +768 units = +1 octave
        fnum, block = (hi & 7) << 8 | lo, hi >> 3 & 7
        expected = fnum * 2.0 ** (block - 1) * fm / (FM_DIV[chip] * 2 ** 20)
        expect(keyed(vgm, ch), "%s: FM channel %d keyed on" % (name, ch + 1))
        what = "block %d F-number %d" % (block, fnum)
    elif part == "ssg":
        i = ch - SSG_FIRST[chip]
        tp = r0[2 * i] | (r0[2 * i + 1] & 15) << 8
        expected = fm / (SSG_DIV[chip] * tp) if tp else 0.0
        exact = fm / (SSG_DIV[chip] * 440.0 * 2 ** ((note - 57) / 12))
        expect(tp == round(exact), "%s: tone period %d is the nearest to %.2f" % (name, tp, exact))
        expect(not (r0[7] >> i & 1) and (r0[8 + i] & 0x1F) != 0, "%s: tone enabled ($07 = %02X), level %02X" % (
            name, r0[7], r0[8 + i]))
        target = note
        what = "TP %d" % tp
    else:
        expect(r1[0x01] & 0x02, "%s: 8-bit DRAM mode ($101 = %02X)" % (name, r1[0x01]))
        start = (r1[0x03] << 8 | r1[0x02]) * 32
        end = ((r1[0x05] << 8 | r1[0x04]) + 1) * 32
        dn = r1[0x0A] << 8 | r1[0x09]
        memory = vgm.blocks[0][2] if vgm.blocks else b""
        ok = expect(len(memory) >= 8 and u32(memory, 0) == 262144 and u32(memory, 4) == 0 and
                    start < end <= len(memory) - 8, "%s: the sample $%X-$%X lies in the data block" % (name, start, end))
        pcm = adpcm_b_decode(memory[8 + start:8 + end]) if ok else []
        cps = rising_crossings(pcm, 64)
        rate = dn / 65536.0 * fm / 144.0
        expected = cps * rate
        target = None
        what = "delta-N %d (%.1f Hz playback), sample %.5f cycles per sample" % (dn, rate, cps)
    if target is not None and expected > 0:
        cents = 1200 * math.log2(expected / (440.0 * 2 ** ((target - 57) / 12)))
        expect(abs(cents) < 3.0, "%s: %s = %.3f Hz, %+.2f cents from 12-TET" % (name, what, expected, cents))
    tol = TOLERANCE[part]
    expect(expected > 0 and abs(measured_hz / expected - 1) < tol,
           "%s: WAV %.3f Hz vs %.3f Hz from the VGM's registers (%s; tolerance %.2f %%)" % (
               name, measured_hz, expected, what, tol * 100))
    print("  %-18s %s: %10.3f Hz from the VGM, %10.3f Hz measured (%+.3f %%)" % (
        name, what, expected, measured_hz, (measured_hz / expected - 1) * 100 if expected else 0))
    return measured_hz


def check_song(d, name, chip, ticks):
    image = (d / (name + ".bin")).read_bytes()
    vgm = Vgm((d / (name + ".vgm")).read_bytes(), chip)
    ok = vgm.structure(name + ".vgm")
    expect(image[:4] == b"TTOP" and image[5] == (1 if chip == "YM2608" else 0), "%s.bin: a %s image" % (name, chip))
    expect(len(image) == (327680 if chip == "YM2608" else 65536), "%s.bin: %d bytes" % (name, len(image)))
    tb = image[18]
    native = ticks * 16 * (256 - tb)
    want = native * 44100 * FM_DIV[chip] / CLOCK[chip]
    expect(abs(vgm.samples - want) <= 1, "%s: %d samples = %d ticks x 16 x (256 - %d) native samples at 44.1 kHz (%.1f)" % (
        name, vgm.samples, ticks, tb, want))
    expect(vgm.loop != 0, "%s: has a loop" % name)
    used = u32(image, 24)
    if chip == "YM2608" and used:
        b = vgm.blocks
        expect(len(b) == 1 and b[0][0] == vgm.first_command and b[0][1] == 0x81 and len(b[0][2]) == 8 + used and
               u32(b[0][2], 0) == 262144 and u32(b[0][2], 4) == 0 and b[0][2][8:] == image[65536:65536 + used],
               "%s: one data block $81 first: memory size 262144, start 0, the image's %d bytes of ADPCM-B memory" % (
                   name, used))
    else:
        expect(not vgm.blocks, "%s: no data block" % name)
    keyons = sum(1 for p, r, v in vgm.writes if p == 0 and r == 0x28 and v & 0xF0)
    expect(keyons > 0, "%s: FM key-ons logged (%d)" % (name, keyons))
    print("  %-18s %d bytes, %d writes, %d samples (%.2f s), loop %d samples, %d data block(s)" % (
        name, len(vgm.data), len(vgm.writes), vgm.samples, vgm.samples / 44100.0, vgm.loop_samples, len(vgm.blocks)))


def check_rom_absent(d):
    path = os.environ.get("THOR_RHYTHM_ROM")
    if not path:
        print("rhythm ROM absence: not checked (THOR_RHYTHM_ROM unset; the ROM is not part of Thor Tracker)")
        return
    rom = Path(path).read_bytes()
    if not expect(hashlib.sha256(rom).hexdigest() == ROM_SHA256 and len(rom) == 8192,
                  "THOR_RHYTHM_ROM is the YM2608 rhythm ROM (8192 bytes, SHA-256)"):
        return
    windows = [rom[at:at + 32] for at in range(0, 8192 - 32 + 1, 256)]
    for f in sorted(d.glob("*.vgm")):
        data = f.read_bytes()
        found = sum(1 for w in windows if w in data)
        expect(found == 0, "%s: no rhythm ROM bytes (%d of %d windows found)" % (f.name, found, len(windows)))
    print("rhythm ROM absence: checked in %d VGM files" % len(list(d.glob("*.vgm"))))


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    d = Path(sys.argv[1])
    manifest = d / "opn_evidence.txt"
    if not manifest.exists():
        print("%s missing: run tools/audio/run_native_host_test.sh %s first" % (manifest, d))
        return 2
    tones = {}
    print("OPN / OPNA tones: the VGM's registers vs the engine's WAV")
    for line in manifest.read_text().splitlines():
        f = line.split()
        if not f or f[0].startswith("#"):
            continue
        if f[0] == "tone":
            name, chip, part, ch, note = f[1], f[2], f[3], int(f[4]), int(f[5])
            left, rate = wav_left(d / (name + ".wav"))
            measured = rising_crossings(left, rate // 10) * rate
            tones[name] = check_tone(d, name, chip, part, ch, note, measured)
    print("OPN / OPNA songs: VGM structure")
    for line in manifest.read_text().splitlines():
        f = line.split()
        if f and f[0] == "song":
            check_song(d, f[1], f[2], int(f[3]))
    if "opna_adpcm_root" in tones and "opna_adpcm_up12" in tones and tones["opna_adpcm_root"]:
        ratio = tones["opna_adpcm_up12"] / tones["opna_adpcm_root"]
        expect(abs(ratio / 2 - 1) < 0.005, "ADPCM-B +12 / root measured pitch ratio %.4f (2)" % ratio)
    expect(len(tones) >= 14, "every tone of the host test present (%d)" % len(tones))
    check_rom_absent(d)
    print("OPN / OPNA reference check: %d checks, %d failures" % (checks, failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
