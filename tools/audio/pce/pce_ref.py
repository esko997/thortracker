#!/usr/bin/env python3
"""Reference implementation of Thor Tracker's PC Engine (HuC6280 PSG) player, written from the specification in
docs/AUDIO_ENGINE.md ("PC Engine: Thor Tracker's own player on a HuC6280 PSG") independently of the C player
(app/src/main/cpp/pce/pce_player.c). The host test compares the C player's register writes with this one's, tick by
tick and timer interrupt by timer interrupt (with their CPU cycles), and its VGM bytes with vgm() here.

    python3 tools/audio/pce/pce_ref.py IMAGE.bin --trace out.txt [--ticks 1500]
    python3 tools/audio/pce/pce_ref.py IMAGE.bin --vgm out.vgm [--max-ticks N]

The trace file has one line per player tick (the reset batch is not part of it), in the host test's pce_trace.txt
format: "order row tick @cycle:" (the position before the tick and the tick's first CPU cycle), then " RR=VV" per
write of the tick's batch, then for every timer interrupt of the tick that wrote anything " | @cycle:" and its
writes. Registers and values are two hex digits. Pure Python 3, no dependencies."""
import argparse
import struct
import sys

# ---- chip and clocks (NTSC) -----------------------------------------------------------------------------------------
CPU_HZ_NUM, CPU_HZ_DEN = 78750000, 11        # 7159090.91 Hz: CPU, timer and PSG timestamps ("cycles")
PSG_CLOCK_NUM, PSG_CLOCK_DEN = 39375000, 11  # 3579545.45 Hz: the waveform counters
VGM_CLOCK = 3579545                          # VGM header 0xA4
CYCLES_NUM = 1312500                         # one 60 Hz frame = 1312500 / 11 cycles; a tick = that / multispeed
TIMER_DIVIDER = 1024                         # the CPU timer's prescaler
VGM_RATE_NUM, VGM_RATE_DEN = 4851, 787500    # 44100 / 7159090.91 = 4851 / 787500

# ---- image layout ---------------------------------------------------------------------------------------------------
IMAGE_SIZE = 131072
MAGIC = b"TTPC"
LAYOUT = 1
CHANNELS = 6
NOISE_FIRST = 4                  # channels 4 and 5 (PSG 5, PSG 6) have the noise generator
MAX_ORDERS = 256
MAX_INSTRUMENTS = 64
MAX_WAVES = 64
MAX_SAMPLES = 64
MAX_EFFECTS = 4
MAX_MULTISPEED = 8
MAX_TIMER = 127
MAX_SAMPLE_FRAMES = 65535
MAX_ENVELOPE = 255
WAVE_SIZE = 32
WAVE_MAX = 31

H_MAGIC, H_LAYOUT, H_MULTISPEED, H_SPEED, H_INSTRUMENTS, H_ORDERS, H_LOOP = 0, 4, 5, 6, 7, 8, 10
H_WAVES, H_SAMPLES, H_MASTER, H_LFO_FREQ, H_LFO_CTRL, H_TIMER, H_DATA_SIZE, HEADER_SIZE = 12, 13, 14, 15, 16, 17, 20, 64
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_WAVES, OFF_SAMPLES, OFF_DATA = 64, 320, 3392, 5440, 7488, 8000
INSTRUMENT_SIZE = 32
SAMPLE_ENTRY_SIZE = 8
DATA_CAPACITY = IMAGE_SIZE - OFF_DATA
I_FLAGS, I_WAVE, I_SAMPLE, I_ROOT, I_LOOP, I_LOOP_START, I_ENVELOPES = 0, 1, 2, 3, 4, 6, 8
ENVELOPE_BYTES = 6
ENV_VOLUME, ENV_ARPEGGIO, ENV_PITCH, ENV_WAVE, ENVELOPE_COUNT = 0, 1, 2, 3, 4
ENV_NONE = 0xFF
FLAG_DEFINED, FLAG_RELATIVE, KIND_MASK = 0x80, 0x04, 0x03
KIND_TONE, KIND_NOISE, KIND_SAMPLE = 0, 1, 2

NOTE_OFF = 0xFE
NOTE_MIN, NOTE_MAX = 9, 95       # A-0 .. B-7
UNITS_PER_SEMITONE = 64
PITCH_MIN = 569                  # the lowest pitch whose period fits 12 bits (4093)
PITCH_MAX = 6143                 # B-7 + 63/64
SLIDE_LIMIT = 6144               # slide offset and relative pitch accumulator: +-6144
VOL8_MAX = 248                   # channel volume 31 x 8

# The C-0 octave's period in 16.16 fixed point: round(39375000 / 11 / (32 * 440 * 2^((i / 64 - 57) / 12)) * 65536)
# (the PSG clock exactly; pce_player.c writes the same 768 numbers out as PCP_PERIOD_Q16).
PERIOD_Q16 = tuple(round(PSG_CLOCK_NUM / PSG_CLOCK_DEN / (32 * 440 * 2 ** ((i / 64 - 57) / 12)) * 65536)
                   for i in range(768))

# Vibrato: floor(127 sin(2 pi i / 64) + 0.5), written out (the OPL3 player's table; pce_player.c has the same literal).
SINE = (0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126,
        127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12,
        0, -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
        -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12)

LETTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
FX = {c: i for i, c in enumerate(LETTERS)}
EFFECTS = frozenset(FX[c] for c in "012348ABCDFGLMPSTWX")
ROW_START = frozenset(FX[c] for c in "BDFG")    # acted on at the row start, not when the row runs

# PSG registers: 0 select, 1 master balance, 2/3 period low/high, 4 control, 5 balance, 6 data, 7 noise, 8/9 LFO.
R_SELECT, R_MASTER, R_FREQ_LO, R_FREQ_HI, R_CONTROL, R_BALANCE, R_DATA, R_NOISE, R_LFO_FREQ, R_LFO_CTRL = range(10)
CHANNEL_SHADOWED = (R_FREQ_LO, R_FREQ_HI, R_CONTROL, R_BALANCE, R_NOISE)


def period(pitch):
    """The 12-bit period N of a pitch in 1/64 semitones (0 = C-0), clamped to PITCH_MIN..PITCH_MAX."""
    p = max(PITCH_MIN, min(PITCH_MAX, pitch))
    o, i = divmod(p, 768)
    return (PERIOD_Q16[i] + (1 << (15 + o))) >> (16 + o)


def sample_step(pitch, root_note):
    """Sample frames per timer interrupt in 16.16 for `pitch` (already clamped) on a sample whose root note plays one
    frame per interrupt: Q(root * 64) / Q(pitch) * 65536 with Q(p) = T[p mod 768] / 2^(p / 768), truncating."""
    rp = root_note * UNITS_PER_SEMITONE
    d = pitch // 768 - rp // 768
    if d >= 0:
        return ((PERIOD_Q16[rp % 768] << (16 + d)) // PERIOD_Q16[pitch % 768]) & 0xFFFFFFFF
    return (((PERIOD_Q16[rp % 768] << 16) // PERIOD_Q16[pitch % 768]) >> -d) & 0xFFFFFFFF


def tick_cycle(k, multispeed):
    """The first CPU cycle of tick k (from the song start or audition) at `multispeed` ticks per 60 Hz frame."""
    return k * CYCLES_NUM // (CPU_HZ_DEN * multispeed)


def vgm_sample(cycle):
    """The 44.1 kHz sample of an event at an absolute CPU cycle."""
    return cycle * VGM_RATE_NUM // VGM_RATE_DEN


class ImageError(ValueError):
    def __init__(self, reason, offset):
        super().__init__("%s (byte %d)" % (reason, offset))
        self.reason, self.offset = reason, offset


def _param_problem(code, param, orders, waves):
    """The reason pc_load_image rejects effect `code` (0..35) with `param` (pce_image.c effect_problem), or None."""
    if code not in EFFECTS:
        return "unknown effect code"
    if code == FX["B"]:
        return "Bxx jumps past the last order" if param >= orders else None
    if code == FX["C"]:
        return "Cxx volume above 1F" if param > 0x1F else None
    if code == FX["F"]:
        return "F00: speed must be 1..FF" if param == 0 else None
    if code == FX["M"]:
        return "Mxx LFO control must be 00..03 or 80..83" if param & 0x7C else None
    if code == FX["T"]:
        return "Txx timer above 7F" if param > MAX_TIMER else None
    if code == FX["W"]:
        return "Wxx names a wave the image does not have" if param >= waves else None
    return None


class Envelope:
    __slots__ = ("values", "loop", "release")

    def __init__(self, values, loop, release):
        self.values, self.loop, self.release = values, loop, release   # loop/release None = none


class Instrument:
    __slots__ = ("kind", "relative", "wave", "sample", "root", "loop", "loop_start", "env")


class Image:
    """A validated PC Engine image: the checks of pc_load_image (pce_image.c) in its order, raising
    ImageError(reason, offset) on the first problem with the C loader's reason string and byte offset (header, the
    order tables past the order count, waves, sample entries, instruments, patterns), and the decoded tables."""

    def __init__(self, data):
        data = bytes(data)
        if len(data) != IMAGE_SIZE:
            raise ImageError("image size", 0)
        self.data = data
        if data[0:4] != MAGIC:
            raise ImageError("not a PC Engine image (magic)", 0)
        if data[H_LAYOUT] != LAYOUT:
            raise ImageError("unknown layout", H_LAYOUT)
        self.multispeed = data[H_MULTISPEED]
        if not 1 <= self.multispeed <= MAX_MULTISPEED:
            raise ImageError("multispeed must be 1..8", H_MULTISPEED)
        self.speed = data[H_SPEED]
        if self.speed < 1:
            raise ImageError("speed must be 1..255", H_SPEED)
        self.instrument_count = data[H_INSTRUMENTS]
        if self.instrument_count > MAX_INSTRUMENTS:
            raise ImageError("instrument count above 64", H_INSTRUMENTS)
        self.orders, self.loop = struct.unpack_from("<HH", data, H_ORDERS)
        if not 1 <= self.orders <= MAX_ORDERS:
            raise ImageError("order count must be 1..256", H_ORDERS)
        if self.loop >= self.orders:
            raise ImageError("loop order past the last order", H_LOOP)
        self.waves = data[H_WAVES]
        if self.waves > MAX_WAVES:
            raise ImageError("wave count above 64", H_WAVES)
        self.samples = data[H_SAMPLES]
        if self.samples > MAX_SAMPLES:
            raise ImageError("sample count above 64", H_SAMPLES)
        self.master = data[H_MASTER]
        self.lfo_freq = data[H_LFO_FREQ]
        self.lfo_ctrl = data[H_LFO_CTRL]
        if self.lfo_ctrl & 0x7C:
            raise ImageError("LFO control must be 00..03 or 80..83", H_LFO_CTRL)
        self.timer = data[H_TIMER]
        if self.timer > MAX_TIMER:
            raise ImageError("timer reload above 127", H_TIMER)
        if data[18] or data[19]:
            raise ImageError("header reserved bytes 18..19", 18)
        self.data_size = struct.unpack_from("<I", data, H_DATA_SIZE)[0]
        if self.data_size > DATA_CAPACITY:
            raise ImageError("data size above the region", H_DATA_SIZE)
        if any(data[24:HEADER_SIZE]):
            raise ImageError("header reserved bytes 24..63", 24)
        self.rows, self.patterns = [], []
        for o in range(MAX_ORDERS):
            at = OFF_ORDERS + 2 * CHANNELS * o
            entry = list(struct.unpack_from("<%dH" % CHANNELS, data, at))
            if o < self.orders:
                self.rows.append(data[OFF_ROWS + o] or 256)
                self.patterns.append(entry)
                continue
            if data[OFF_ROWS + o]:
                raise ImageError("rows of an order past the order count", OFF_ROWS + o)
            if any(entry):
                raise ImageError("patterns of an order past the order count", at)
        for w in range(MAX_WAVES):
            at = OFF_WAVES + WAVE_SIZE * w
            if w >= self.waves:
                if any(data[at: at + WAVE_SIZE]):
                    raise ImageError("wave past the wave count", at)
                continue
            for j in range(WAVE_SIZE):
                if data[at + j] > WAVE_MAX:
                    raise ImageError("wave sample above 31", at + j)
        self.sample_table = []
        for s in range(MAX_SAMPLES):
            at = OFF_SAMPLES + SAMPLE_ENTRY_SIZE * s
            offset, length = struct.unpack_from("<II", data, at)
            if s >= self.samples:
                if offset or length:
                    raise ImageError("sample entry past the sample count", at)
                continue
            if length < 1:
                raise ImageError("sample without frames", at + 4)
            if length > MAX_SAMPLE_FRAMES:
                raise ImageError("sample longer than 65535 frames", at + 4)
            if offset > self.data_size or length > self.data_size - offset:
                raise ImageError("sample runs past the data size", at)
            for j in range(length):
                if data[OFF_DATA + offset + j] > WAVE_MAX:
                    raise ImageError("sample frame above 31", OFF_DATA + offset + j)
            self.sample_table.append((offset, length))
        self._instruments = {}
        for slot in range(1, MAX_INSTRUMENTS + 1):
            self._check_instrument(slot)
        self._decoded = {}
        self.start_speed = self._check_patterns()

    def _check_instrument(self, slot):
        base = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        rec = self.data[base: base + INSTRUMENT_SIZE]
        if not any(rec):
            return
        if slot > self.instrument_count:
            raise ImageError("instrument record past the instrument count", base)
        flags = rec[I_FLAGS]
        kind = flags & KIND_MASK
        if not flags & FLAG_DEFINED or flags & ~(FLAG_DEFINED | FLAG_RELATIVE | KIND_MASK):
            raise ImageError("instrument flags", base + I_FLAGS)
        if kind == 3:
            raise ImageError("instrument kind 3", base + I_FLAGS)
        loop_start = struct.unpack_from("<H", rec, I_LOOP_START)[0]
        if kind == KIND_TONE:
            if rec[I_WAVE] >= self.waves:
                raise ImageError("tone instrument names a wave the image does not have", base + I_WAVE)
        elif rec[I_WAVE]:
            raise ImageError("wave byte of a noise or sample instrument", base + I_WAVE)
        if kind == KIND_SAMPLE:
            if rec[I_SAMPLE] >= self.samples:
                raise ImageError("sample instrument names a sample the image does not have", base + I_SAMPLE)
            if not NOTE_MIN <= rec[I_ROOT] <= NOTE_MAX:
                raise ImageError("sample root note outside 9..95", base + I_ROOT)
            if rec[I_LOOP] > 1:
                raise ImageError("sample loop flag must be 0 or 1", base + I_LOOP)
            length = self.sample_table[rec[I_SAMPLE]][1]
            if (loop_start >= length) if rec[I_LOOP] else loop_start:
                raise ImageError("sample loop start past the sample (or set without loop)", base + I_LOOP_START)
        elif rec[I_SAMPLE] or rec[I_ROOT] or rec[I_LOOP] or loop_start:
            raise ImageError("sample fields of a tone or noise instrument", base + I_SAMPLE)
        if rec[5]:
            raise ImageError("instrument reserved byte 5", base + 5)
        ins = Instrument()
        ins.kind, ins.relative = kind, bool(flags & FLAG_RELATIVE)
        ins.wave, ins.sample, ins.root = rec[I_WAVE], rec[I_SAMPLE], rec[I_ROOT]
        ins.loop, ins.loop_start = bool(rec[I_LOOP]), loop_start
        ins.env = []
        for k in range(ENVELOPE_COUNT):
            at = I_ENVELOPES + ENVELOPE_BYTES * k
            offset = struct.unpack_from("<H", rec, at)[0]
            length, loop, release, zero = rec[at + 2], rec[at + 3], rec[at + 4], rec[at + 5]
            if zero:
                raise ImageError("envelope reserved byte", base + at + 5)
            if length == 0:
                if offset or loop != ENV_NONE or release != ENV_NONE:
                    raise ImageError("empty envelope: offset 0, loop and release FF", base + at)
                ins.env.append(None)
                continue
            if k == ENV_WAVE and kind != KIND_TONE:
                raise ImageError("wave envelope on a noise or sample instrument", base + at + 2)
            if loop != ENV_NONE and loop >= length:
                raise ImageError("envelope loop past its length", base + at + 3)
            if release != ENV_NONE and not 1 <= release < length:
                raise ImageError("envelope release must be 1..length - 1", base + at + 4)
            if loop != ENV_NONE and release != ENV_NONE and release <= loop:
                raise ImageError("envelope release must follow its loop", base + at + 4)
            if offset + length > self.data_size:
                raise ImageError("envelope runs past the data size", base + at)
            raw = self.data[OFF_DATA + offset: OFF_DATA + offset + length]
            values = [b - 256 if b > 127 else b for b in raw] if k in (ENV_ARPEGGIO, ENV_PITCH) else list(raw)
            for j, v in enumerate(values):
                if k == ENV_VOLUME and v > WAVE_MAX:
                    raise ImageError("volume envelope value above 31", OFF_DATA + offset + j)
                if k == ENV_ARPEGGIO and not -64 <= v <= 63:
                    raise ImageError("arpeggio envelope value outside -64..63", OFF_DATA + offset + j)
                if k == ENV_WAVE and v >= self.waves:
                    raise ImageError("wave envelope names a wave the image does not have", OFF_DATA + offset + j)
            ins.env.append(Envelope(values, None if loop == ENV_NONE else loop,
                                    None if release == ENV_NONE else release))
        self._instruments[slot] = ins

    def _check_patterns(self):
        """The loader's last check, as pc_load_image runs it: per order, every channel's pattern offset inside the used
        data, then rows(o) rows decoded row by row across the channels, every byte inside the used data and valid.
        Returns the ticks per row each order starts with in sequential straight play: the header speed, then the last
        Fxx of each order (rows, then channels, then columns; jumps ignored)."""
        data, size = self.data, self.data_size
        speeds, speed = [], self.speed
        for o in range(self.orders):
            speeds.append(speed)
            offsets, runs = list(self.patterns[o]), [0] * CHANNELS
            for c in range(CHANNELS):
                if offsets[c] >= size:
                    raise ImageError("pattern offset past the data size", OFF_ORDERS + 2 * (CHANNELS * o + c))
            for _ in range(self.rows[o]):
                for c in range(CHANNELS):
                    if runs[c]:
                        runs[c] -= 1
                        continue
                    at = offsets[c]
                    if at >= size:
                        raise ImageError("pattern runs past the data size", OFF_DATA + at)
                    m = data[OFF_DATA + at]
                    at += 1
                    if m & 0x80:
                        runs[c], offsets[c] = m & 0x7F, at
                        continue
                    effects = (m >> 2) & 7
                    if m == 0 or m & 0x60 or effects > MAX_EFFECTS:
                        raise ImageError("row mask byte", OFF_DATA + at - 1)
                    if at + (m & 1) + ((m >> 1) & 1) + 2 * effects > size:
                        raise ImageError("row runs past the data size", OFF_DATA + at)
                    if m & 1:
                        note = data[OFF_DATA + at]
                        if note != NOTE_OFF and not NOTE_MIN <= note <= NOTE_MAX:
                            raise ImageError("note byte", OFF_DATA + at)
                        at += 1
                    if m & 2:
                        ins = self.instrument(data[OFF_DATA + at])
                        if ins is None:
                            raise ImageError("row names an instrument the image does not define", OFF_DATA + at)
                        if c < NOISE_FIRST and ins.kind == KIND_NOISE:
                            raise ImageError("noise instrument off PSG 5-6 (only channels 5 and 6 have noise)",
                                             OFF_DATA + at)
                        at += 1
                    for _ in range(effects):
                        code, param = data[OFF_DATA + at], data[OFF_DATA + at + 1]
                        why = "effect code above Z" if code > 35 else _param_problem(code, param, self.orders,
                                                                                      self.waves)
                        if why:
                            raise ImageError(why, OFF_DATA + at)
                        if code == FX["F"]:
                            speed = param  # rows, then channels, then columns: the last F wins
                        at += 2
                    offsets[c] = at
        return speeds

    def instrument(self, slot):
        """The decoded record of a defined slot 1..64, else None."""
        return self._instruments.get(slot)

    def wave(self, w):
        at = OFF_WAVES + WAVE_SIZE * w
        return self.data[at: at + WAVE_SIZE]

    def sample_frame(self, s, f):
        return self.data[OFF_DATA + self.sample_table[s][0] + f]

    def pattern_rows(self, order, channel):
        """Every row of (order, channel) decoded from the pattern's start (the image is validated): None (empty) or
        (note, slot, effects) with note None / 9..95 / NOTE_OFF, slot 0 = none and effects [(code, param)] in column
        order. A run longer than the order's remaining rows is cut there."""
        offset, n = self.patterns[order][channel], self.rows[order]
        key = (offset, n)
        if key in self._decoded:
            return self._decoded[key]
        data, rows, at = self.data, [], OFF_DATA + offset
        while len(rows) < n:
            m = data[at]
            at += 1
            if m & 0x80:
                rows.extend([None] * min((m & 0x7F) + 1, n - len(rows)))
                continue
            note, slot, effects = None, 0, []
            if m & 1:
                note = data[at]
                at += 1
            if m & 2:
                slot = data[at]
                at += 1
            for _ in range((m >> 2) & 7):
                effects.append((data[at], data[at + 1]))
                at += 2
            rows.append((note, slot, effects))
        self._decoded[key] = rows
        return rows


class Channel:
    def __init__(self):
        self.row = None
        self.delay = None          # tick of a delayed row (Gxx), None = none pending
        self.cut = None            # Sxx tick, None = none
        self.instrument = 0        # the slot rows set
        self.latched = 0           # the slot of the sounding note (key-on)
        self.kind = KIND_TONE
        self.sounding = False
        self.fresh = False
        self.released = False
        self.dda = False
        self.pos = 0               # sample position, 16.16 frames
        self.step = 0              # frames per timer interrupt, 16.16 (the last update's)
        self.loaded = -1           # the wave in the channel's waveform RAM, -1 = unknown
        self.env_idx = [0] * ENVELOPE_COUNT
        self.rel_acc = 0
        self.base = self.target = 0
        self.fine = 0
        self.arp = self.arp_step = 0
        self.slide_mode = None     # "up", "down", "porta"
        self.slide_speed = 0
        self.slide = 0
        self.vib_speed = self.vib_depth = self.vib_phase = 0
        self.vol8 = VOL8_MAX
        self.volslide = 0
        self.pan = 0xFF
        self.wave_override = -1
        self.shadow = {R_FREQ_LO: 0, R_FREQ_HI: 0, R_CONTROL: 0, R_BALANCE: 0, R_NOISE: 0}


class Player:
    def __init__(self, image):
        self.img = image
        self.reset()

    # -- writes --
    def put(self, reg, val):
        self.batch.append((reg, val))

    def select_channel(self, c):
        if self.select != c:
            self.put(R_SELECT, c)
            self.select = c

    def write(self, c, reg, val):
        """A channel register (2-7) of channel c: diffed through the shadow (R6 never), R0 first when needed."""
        t = self.ch[c]
        if reg in CHANNEL_SHADOWED:
            if t.shadow[reg] == val:
                return
            t.shadow[reg] = val
        self.select_channel(c)
        self.put(reg, val)

    def write_global(self, reg, val):
        if self.gshadow[reg] != val:
            self.gshadow[reg] = val
            self.put(reg, val)

    def globals(self):
        self.write_global(R_MASTER, self.master)
        self.write_global(R_LFO_FREQ, self.lfo_freq)
        self.write_global(R_LFO_CTRL, self.lfo_ctrl)

    def wave_load(self, c, w):
        """R4 = $40, R4 = $00 (both emitted: bit 6 falling resets the write index), 32 x R6, shadow R4 = $00."""
        t = self.ch[c]
        self.select_channel(c)
        self.put(R_CONTROL, 0x40)
        self.put(R_CONTROL, 0x00)
        for v in self.img.wave(w):
            self.put(R_DATA, v)
        t.shadow[R_CONTROL] = 0x00
        t.loaded = w

    def reset(self):
        img = self.img
        self.batch = []
        self.ch = [Channel() for _ in range(CHANNELS)]
        self.master, self.lfo_freq, self.lfo_ctrl, self.timer = img.master, img.lfo_freq, img.lfo_ctrl, img.timer
        self.speed = img.speed
        self.order = self.row = self.tick_in_row = 0
        self.jump = None
        # The reset batch: explicit, every write emitted, setting the shadows.
        self.gshadow = {R_MASTER: img.master, R_LFO_FREQ: img.lfo_freq, R_LFO_CTRL: img.lfo_ctrl}
        self.put(R_MASTER, img.master)
        self.put(R_LFO_FREQ, img.lfo_freq)
        self.put(R_LFO_CTRL, img.lfo_ctrl)
        for c in range(CHANNELS):
            self.put(R_SELECT, c)
            for reg, val in ((R_FREQ_LO, 0), (R_FREQ_HI, 0), (R_CONTROL, 0), (R_BALANCE, 0xFF)):
                self.put(reg, val)
                self.ch[c].shadow[reg] = val
            if c >= NOISE_FIRST:
                self.put(R_NOISE, 0)
        self.select = CHANNELS - 1

    # -- notes --
    def key_on(self, c, note):
        t = self.ch[c]
        ins = self.img.instrument(t.instrument)
        if ins is None or (ins.kind == KIND_NOISE and c < NOISE_FIRST):
            return
        t.kind, t.latched = ins.kind, t.instrument
        t.env_idx = [0] * ENVELOPE_COUNT
        t.released = False
        t.rel_acc = 0
        t.base = t.target = note * UNITS_PER_SEMITONE
        t.slide = 0
        t.arp_step = 0
        t.vib_phase = 0
        t.sounding = True
        t.fresh = True
        t.dda = ins.kind == KIND_SAMPLE
        if t.dda:
            t.pos = 0x10000

    def stop(self, c):
        t = self.ch[c]
        t.sounding = False
        t.dda = False
        self.write(c, R_CONTROL, 0x40 if t.kind == KIND_SAMPLE else 0x00)

    def off(self, c):
        t = self.ch[c]
        if not t.sounding:
            return
        ins = self.img.instrument(t.latched)
        vol = ins.env[ENV_VOLUME] if ins else None
        if vol is not None and vol.release is not None:
            t.released = True
            for k, e in enumerate(ins.env):
                if e is not None and e.release is not None:
                    t.env_idx[k] = e.release
        else:
            self.stop(c)

    def run_row(self, c):
        t = self.ch[c]
        note, slot, effects = t.row if t.row else (None, 0, ())
        playing = note is not None and note != NOTE_OFF
        if playing:
            t.arp = 0
            t.fine = 0
            t.wave_override = -1
        if slot:
            t.instrument = slot
        for code, param in effects:
            x, y = param >> 4, param & 15
            if code in ROW_START:
                continue
            if code == FX["0"]:
                t.arp, t.arp_step = param, 0
            elif code in (FX["1"], FX["2"], FX["3"]):
                t.slide_mode = {FX["1"]: "up", FX["2"]: "down", FX["3"]: "porta"}[code] if param else None
                t.slide_speed = param
            elif code == FX["4"]:
                t.vib_speed, t.vib_depth = x, y
            elif code == FX["8"]:
                t.pan = param
            elif code == FX["A"]:
                t.volslide = param
            elif code == FX["C"]:
                t.vol8 = param * 8
            elif code == FX["P"]:
                t.fine = param - 0x80
            elif code == FX["S"]:
                t.cut = param
            elif code == FX["W"]:
                t.wave_override = param
            elif code == FX["T"]:
                self.timer = param
            elif code in (FX["L"], FX["M"], FX["X"]):
                if code == FX["L"]:
                    self.lfo_freq = param
                elif code == FX["M"]:
                    self.lfo_ctrl = param
                else:
                    self.master = param
                self.globals()
        if note == NOTE_OFF:
            self.off(c)
        elif playing:
            if t.slide_mode == "porta" and t.sounding and not t.released and t.kind in (KIND_TONE, KIND_SAMPLE):
                t.target = note * UNITS_PER_SEMITONE
            else:
                self.key_on(c, note)

    def env_value(self, t, ins, k):
        e = ins.env[k] if ins else None
        if e is None:
            return None
        return e.values[min(t.env_idx[k], len(e.values) - 1)]

    def advance_envelopes(self, t, ins):
        for k in range(ENVELOPE_COUNT):
            e = ins.env[k] if ins else None
            if e is None:
                continue
            n = len(e.values)
            idx = min(t.env_idx[k], n - 1)
            if t.released and e.release is not None:
                t.env_idx[k] = min(idx + 1, n - 1)
            else:
                held = e.release if e.release is not None else n
                nxt = idx + 1
                if nxt >= held:
                    nxt = e.loop if e.loop is not None else held - 1
                t.env_idx[k] = nxt

    def update(self, c):
        t = self.ch[c]
        ins = self.img.instrument(t.latched)
        v = self.env_value(t, ins, ENV_VOLUME)
        volume_envelope = ins.env[ENV_VOLUME] if ins else None
        if t.released and volume_envelope is not None and v == 0 and t.env_idx[ENV_VOLUME] >= len(volume_envelope.values) - 1:
            self.stop(c)
            return
        vol = max(0, (31 if v is None else v) + (t.vol8 >> 3) - 31)
        arp = 0
        if t.arp_step == 1:
            arp = t.arp >> 4
        elif t.arp_step == 2:
            arp = t.arp & 15
        a = self.env_value(t, ins, ENV_ARPEGGIO)
        semitones = arp + (a or 0)
        pv = self.env_value(t, ins, ENV_PITCH)
        if pv is None:
            pitch_value = 0
        elif ins.relative:
            t.rel_acc = max(-SLIDE_LIMIT, min(SLIDE_LIMIT, t.rel_acc + pv))
            pitch_value = t.rel_acc
        else:
            pitch_value = pv
        vib = (SINE[t.vib_phase] * t.vib_depth) >> 4  # Python's >> floors, as the spec's arithmetic shift
        pitch = max(PITCH_MIN, min(PITCH_MAX, t.base + 64 * semitones + t.fine + t.slide + vib + pitch_value))
        if t.kind == KIND_TONE:
            we = self.env_value(t, ins, ENV_WAVE)
            if we is not None:
                w = we
            elif t.wave_override >= 0:
                w = t.wave_override
            else:
                w = ins.wave if ins else 0
            if t.loaded != w:
                self.wave_load(c, w)
            n = period(pitch)
            self.write(c, R_FREQ_LO, n & 0xFF)
            self.write(c, R_FREQ_HI, n >> 8)
            if c >= NOISE_FIRST:
                self.write(c, R_NOISE, 0x00)
            self.write(c, R_BALANCE, t.pan)
            self.write(c, R_CONTROL, 0x80 | vol)
        elif t.kind == KIND_NOISE:
            nf = ((t.base >> 6) + semitones) & 31
            self.write(c, R_NOISE, 0x80 | nf)
            self.write(c, R_BALANCE, t.pan)
            self.write(c, R_CONTROL, 0x80 | vol)
        else:
            t.step = sample_step(pitch, ins.root if ins else NOTE_MIN)
            if c >= NOISE_FIRST:
                self.write(c, R_NOISE, 0x00)
            self.write(c, R_BALANCE, t.pan)
            self.write(c, R_CONTROL, 0xC0 | vol)
            if t.fresh and ins:
                self.write(c, R_DATA, self.img.sample_frame(ins.sample, 0))
        t.fresh = False
        # the envelopes and effects advance after the writes
        self.advance_envelopes(t, ins)
        if t.arp:
            t.arp_step = (t.arp_step + 1) % 3
        if t.slide_mode == "up":
            t.slide = min(SLIDE_LIMIT, t.slide + t.slide_speed)
        elif t.slide_mode == "down":
            t.slide = max(-SLIDE_LIMIT, t.slide - t.slide_speed)
        elif t.slide_mode == "porta":
            if t.base < t.target:
                t.base = min(t.target, t.base + t.slide_speed)
            elif t.base > t.target:
                t.base = max(t.target, t.base - t.slide_speed)
        if t.vib_speed and t.vib_depth:
            t.vib_phase = (t.vib_phase + t.vib_speed) % 64
        if t.volslide:
            t.vol8 = max(0, min(VOL8_MAX, t.vol8 + (t.volslide >> 4) - (t.volslide & 15)))

    # -- sequencing --
    def begin_row(self):
        img = self.img
        for c in range(CHANNELS):
            self.ch[c].row = img.pattern_rows(self.order, c)[self.row]
        jump_order = jump_row = None
        for c in range(CHANNELS):
            row = self.ch[c].row
            for code, param in (row[2] if row else ()):
                if code == FX["F"]:
                    self.speed = param
                elif code == FX["B"]:
                    jump_order = param
                elif code == FX["D"]:
                    jump_row = param
        if jump_order is None and jump_row is None:
            self.jump = None
        else:
            if jump_order is None:
                jump_order = self.order + 1 if self.order + 1 < img.orders else img.loop
            self.jump = (jump_order, jump_row or 0)
        for c in range(CHANNELS):
            t = self.ch[c]
            t.cut = None
            t.delay = None
            g = 0
            for code, param in (t.row[2] if t.row else ()):
                if code == FX["G"]:
                    g = param
            if g:
                t.delay = g
            else:
                self.run_row(c)

    def tick(self):
        """One player tick; returns its batch [(reg, val)]."""
        self.batch = []
        self.globals()
        if self.tick_in_row == 0:
            self.begin_row()
        else:
            for c in range(CHANNELS):
                t = self.ch[c]
                if t.delay is not None and t.delay == self.tick_in_row:
                    t.delay = None
                    self.run_row(c)
        for c in range(CHANNELS):
            if self.ch[c].cut == self.tick_in_row:
                self.off(c)
        for c in range(CHANNELS):
            if self.ch[c].sounding:
                self.update(c)
        self.tick_in_row += 1
        if self.tick_in_row >= self.speed:
            img = self.img
            self.tick_in_row = 0
            if self.jump is not None:
                o, r = self.jump
                self.order, self.row = o, min(r, img.rows[o] - 1)
                self.jump = None
            else:
                self.row += 1
                if self.row >= img.rows[self.order]:
                    self.row = 0
                    self.order = self.order + 1 if self.order + 1 < img.orders else img.loop
            if (self.order, self.row) == (img.loop, 0):
                self.speed = img.start_speed[img.loop]  # every pass of the loop starts alike
        return self.batch

    def timer_interrupt(self):
        """One DDA timer interrupt (pcp_timer); returns its batch."""
        self.batch = []
        for c in range(CHANNELS):
            t = self.ch[c]
            if not t.dda:
                continue
            ins = self.img.instrument(t.latched)
            if ins is None or ins.kind != KIND_SAMPLE:
                t.dda = t.sounding = False
                self.write(c, R_CONTROL, 0x40)
                continue
            length = self.img.sample_table[ins.sample][1]
            f = t.pos >> 16
            if f >= length:
                if ins.loop:
                    span = (length - ins.loop_start) << 16
                    t.pos = (ins.loop_start << 16) + (t.pos - (length << 16)) % span
                    f = t.pos >> 16
                else:
                    t.dda = t.sounding = False
                    self.write(c, R_CONTROL, 0x40)
                    continue
            self.write(c, R_DATA, self.img.sample_frame(ins.sample, f))
            t.pos += t.step
        return self.batch

    def timer_cycles(self):
        """CPU cycles from one timer interrupt (or the reset) to the next: 1024 (R + 1)."""
        return TIMER_DIVIDER * (self.timer + 1)

    def position(self):
        return self.order, self.row, self.tick_in_row


class Timeline:
    """The player and the DDA timer on the CPU-cycle clock, as the machine and the VGM writer run them: tick k's batch
    at its first cycle, then the timer interrupts with start <= cycle < the next tick's start, each reloading with
    the player's timer value at that moment (the first interrupt 1024 (R + 1) cycles after the reset)."""

    def __init__(self, image):
        self.img = image
        self.p = Player(image)
        self.reset_batch = list(self.p.batch)
        self.k = 0
        self.next_irq = self.p.timer_cycles()

    def tick(self):
        """((order, row, tick) before, start cycle, tick writes, [(cycle, writes)] of every interrupt, count)."""
        n = self.img.multispeed
        start, end = tick_cycle(self.k, n), tick_cycle(self.k + 1, n)
        pos = self.p.position()
        writes = list(self.p.tick())
        irqs = []
        while self.next_irq < end:
            irqs.append((self.next_irq, list(self.p.timer_interrupt())))
            self.next_irq += self.p.timer_cycles()
        self.k += 1
        return pos, start, writes, irqs


def reset_writes(image):
    """The reset batch: R1, R8, R9 from the header, then per channel R0, R2, R3, R4, R5 (and R7 on channels 4-5)."""
    return list(Player(image).batch)


def trace(image, ticks):
    """[(pos, start cycle, tick writes, [(cycle, writes)] of all interrupts)] for `ticks` ticks after the reset."""
    tl = Timeline(image)
    return [tl.tick() for _ in range(ticks)]


def format_writes(writes):
    return "".join(" %02X=%02X" % w for w in writes)


def format_tick(pos, start, writes, irqs):
    """One line of a trace file (the host test's pce_trace.txt)."""
    text = "%d %d %d @%d:" % (pos + (start,)) + format_writes(writes)
    for cycle, w in irqs:
        if w:
            text += " | @%d:" % cycle + format_writes(w)
    return text


def dump_writes(p):
    """The chip state as the player's shadows hold it (the VGM loop point): R1, R8, R9; per channel R0, a wave load
    of the loaded wave, R2, R3, R5, R7 (channels 4-5), R4; then R0 = the player's select."""
    out = [(R_MASTER, p.gshadow[R_MASTER]), (R_LFO_FREQ, p.gshadow[R_LFO_FREQ]), (R_LFO_CTRL, p.gshadow[R_LFO_CTRL])]
    for c in range(CHANNELS):
        t = p.ch[c]
        out.append((R_SELECT, c))
        if t.loaded >= 0:
            out += [(R_CONTROL, 0x40), (R_CONTROL, 0x00)] + [(R_DATA, v) for v in p.img.wave(t.loaded)]
        out += [(R_FREQ_LO, t.shadow[R_FREQ_LO]), (R_FREQ_HI, t.shadow[R_FREQ_HI]), (R_BALANCE, t.shadow[R_BALANCE])]
        if c >= NOISE_FIRST:
            out.append((R_NOISE, t.shadow[R_NOISE]))
        out.append((R_CONTROL, t.shadow[R_CONTROL]))
    if p.select >= 0:
        out.append((R_SELECT, p.select))
    return out


def vgm(image, max_ticks):
    """VGM 1.71 bytes (no GD3 tag), as docs/AUDIO_ENGINE.md "PC Engine: VGM export" specifies. Returns (bytes, info)
    with info = {ticks, samples, looped, loop_offset (file offset, 0 = none), loop_samples (0 = none)}.
    An event (a tick's batch, a timer interrupt) without writes writes nothing, not even its wait."""
    tl = Timeline(image)
    p = tl.p
    body = bytearray()
    now = 0

    def emit(writes):
        for reg, val in writes:
            body.extend((0xB9, reg, val))

    def wait_to(sample):
        nonlocal now
        n, now = sample - now, sample
        while n:
            if n == 735:
                body.append(0x62)
                n = 0
            elif n == 882:
                body.append(0x63)
                n = 0
            elif n <= 16:
                body.append(0x70 | (n - 1))
                n = 0
            else:
                k = min(n, 65535)
                body.extend((0x61, k & 0xFF, k >> 8))
                n -= k

    emit(tl.reset_batch)
    loop_at = loop_samples = None
    looped = False
    ticks = 0
    while ticks < max_ticks:
        start = tick_cycle(tl.k, image.multispeed)
        if p.position() == (image.loop, 0, 0):
            if loop_at is not None:
                looped = True
                break
            wait_to(vgm_sample(start))
            loop_at, loop_samples = 0x100 + len(body), now
            emit(dump_writes(p))
        _, start, writes, irqs = tl.tick()
        if writes:
            wait_to(vgm_sample(start))
            emit(writes)
        for cycle, w in irqs:
            if w:
                wait_to(vgm_sample(cycle))
                emit(w)
        ticks += 1
    wait_to(vgm_sample(tick_cycle(tl.k, image.multispeed)))
    body.append(0x66)
    header = bytearray(0x100)
    header[0:4] = b"Vgm "
    size = 0x100 + len(body)
    struct.pack_into("<I", header, 0x04, size - 4)
    struct.pack_into("<I", header, 0x08, 0x171)
    struct.pack_into("<I", header, 0x18, now)
    if looped:
        struct.pack_into("<I", header, 0x1C, loop_at - 0x1C)
        struct.pack_into("<I", header, 0x20, now - loop_samples)
    struct.pack_into("<I", header, 0x24, 60)
    struct.pack_into("<I", header, 0x34, 0x100 - 0x34)
    struct.pack_into("<I", header, 0xA4, VGM_CLOCK)
    info = {"ticks": ticks, "samples": now, "looped": looped,
            "loop_offset": loop_at if looped else 0, "loop_samples": now - loop_samples if looped else 0}
    return bytes(header + body), info


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("image")
    ap.add_argument("--trace")
    ap.add_argument("--ticks", type=int, default=1500)
    ap.add_argument("--vgm")
    ap.add_argument("--max-ticks", type=int, default=60 * 60 * 10)
    args = ap.parse_args(argv)
    with open(args.image, "rb") as f:
        image = Image(f.read())
    if args.trace:
        with open(args.trace, "w") as f:
            for t in trace(image, args.ticks):
                f.write(format_tick(*t) + "\n")
    if args.vgm:
        data, info = vgm(image, args.max_ticks)
        with open(args.vgm, "wb") as f:
            f.write(data)
        print("%s: %d bytes, %d ticks, %d samples%s" % (args.vgm, len(data), info["ticks"], info["samples"],
              ", loop %d samples" % info["loop_samples"] if info["looped"] else ", no loop"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
