#!/usr/bin/env python3
"""AY-3-8910 / YM2149 image builder (the layout of docs/AUDIO_ENGINE.md "AY-3-8910 / YM2149: Image layout", as the
Kotlin lowering AyLowering must produce it byte for byte) and the hand-built test song of the native host test.

A song here: Song(variant, speed, orders=[Order(rows, [pattern name per channel x 3])], patterns={name: {row: Cell}},
instruments={slot: Instrument}, loop). build(song) returns the 65536-byte image; build(song, relayout=True) lays the
same song out differently (patterns in reverse order behind a 37-byte gap, so every offset changes) for the
live-edit test.

    python3 tools/audio/ay/ay_song.py OUT.bin [zx128|msx|atari-st|cpc] [test|edited|tempo|short|relayout]"""
import struct
import sys
from dataclasses import dataclass, field

IMAGE_SIZE = 65536
OFF_ROWS, OFF_ORDERS, OFF_INSTRUMENTS, OFF_DATA = 64, 320, 2048, 4096
INSTRUMENT_SIZE = 32
CHANNELS = 3
NOTE_OFF = 0xFE
FX = {c: i for i, c in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")}
KEYS = ("zx128", "msx", "atari-st", "cpc")
# The lowest note whose tone period fits 12 bits (ay_ref.lowest_note): A-0, A-0, B-0, C-0.
LOWEST_NOTE = (9, 9, 11, 0)


@dataclass
class Env:
    values: list              # volume 0..15, arpeggio -64..63, pitch -64..63 (1/16 semitone), mixer 0..3, noise 0..31
    loop: int = None          # index, None = hold the last value at the end
    release: int = None       # volume only: where key-off continues (needs loop < release)

    def encode(self):
        none = 0xFFFF
        return struct.pack("<HHH", len(self.values), none if self.loop is None else self.loop,
                           none if self.release is None else self.release) + struct.pack("%db" % len(self.values), *self.values)


@dataclass
class Instrument:
    volume: Env = None        # None = constant 15
    arpeggio: Env = None      # None = 0
    pitch: Env = None         # None = 0
    relative: bool = False    # the pitch envelope accumulates every tick
    mixer: Env = None         # None = tone only (1)
    noise: Env = None         # None = the note does not set R6
    shape: int = 0            # 0 = fixed volume, 8..15 = the hardware envelope shape (R13)
    auto: bool = True         # envelope period follows the note (else `period`)
    octave: int = 0           # auto: J, -8..7
    period: int = 0           # manual: 0..65535
    sync: bool = False        # tone synced to the envelope (auto only)
    sweep: tuple = (0, 0, 0)  # start, end (semitones), speed (1/16 semitone per tick)

    def envelopes(self):
        return [self.volume, self.arpeggio, self.pitch, self.mixer, self.noise]

    def record(self, offsets):
        """The 32-byte record; `offsets`: data offset of each envelope (None = none). auto/octave/period/sync are
        written whatever the shape (a row's Exx can switch the hardware envelope on); only the active one of octave
        and period is written; sync only with auto."""
        rec = bytearray(INSTRUMENT_SIZE)
        rec[0] = 0x80 | (1 if self.relative else 0) | (2 if self.auto else 0) | (4 if self.auto and self.sync else 0)
        rec[1] = self.shape
        struct.pack_into("b", rec, 2, self.octave if self.auto else 0)
        struct.pack_into("<H", rec, 4, 0 if self.auto else self.period)
        struct.pack_into("<HHHHH", rec, 6, *(0xFFFF if o is None else o for o in offsets))
        struct.pack_into("bbB", rec, 16, *self.sweep)
        return bytes(rec)


@dataclass
class Cell:
    note: int = None          # 0..95, NOTE_OFF
    inst: int = None
    fx: list = field(default_factory=list)  # [(letter, param)] per effect column; None or ("0", 0) = blank


@dataclass
class Order:
    rows: int
    patterns: list            # 3 pattern names (None = empty)


@dataclass
class Song:
    variant: int
    speed: int
    orders: list
    patterns: dict
    instruments: dict
    loop: int = 0


def encode_pattern(cells, rows):
    """Rows as the player reads them (YmLowering.encode): empty rows in runs, blank columns omitted."""
    out, empty = bytearray(), 0

    def flush():
        nonlocal empty
        while empty:
            k = min(empty, 128)
            out.append(0x80 | (k - 1))
            empty -= k

    for r in range(rows):
        c = cells.get(r)
        assert len(c.fx if c else []) <= 4
        fx = [e for e in (c.fx if c else []) if e is not None and e != ("0", 0)]
        if c is None or (c.note is None and c.inst is None and not fx):
            empty += 1
            continue
        flush()
        out.append((1 if c.note is not None else 0) | (2 if c.inst is not None else 0) | (len(fx) << 2))
        if c.note is not None:
            out.append(c.note)
        if c.inst is not None:
            out.append(c.inst)
        for letter, param in fx:
            out.extend((FX[letter], param))
    flush()
    return bytes(out)


def build(song, relayout=False):
    """The image. Data region: every distinct (order, channel) pattern in first-use order (orders, then channels),
    then the envelopes of slots 1..64 ascending (volume, arpeggio, pitch, mixer, noise), each deduplicated by its
    bytes (header included) against the envelopes before it; no padding. `relayout` moves everything."""
    img = bytearray(IMAGE_SIZE)
    img[0:4] = b"TTAY"
    img[4], img[5], img[6] = 1, song.variant, song.speed
    struct.pack_into("<HH", img, 8, len(song.orders), song.loop)
    img[12] = max(song.instruments) if song.instruments else 0
    blobs, uses = [], []
    for o, order in enumerate(song.orders):
        img[OFF_ROWS + o] = order.rows & 0xFF
        row = []
        for c in range(CHANNELS):
            name = order.patterns[c]
            blob = encode_pattern(song.patterns[name] if name else {}, order.rows)
            if blob not in blobs:
                blobs.append(blob)
            row.append(blob)
        uses.append(row)
    data, where = bytearray(), {}
    if relayout:
        data.extend(b"\x80" * 37)  # a gap of unused bytes moves every pattern
    for blob in (list(reversed(blobs)) if relayout else blobs):
        where[blob] = len(data)
        data.extend(blob)
    envelope_at = {}
    for slot in sorted(song.instruments):
        inst = song.instruments[slot]
        offsets = []
        for env in inst.envelopes():
            if env is None:
                offsets.append(None)
                continue
            blob = env.encode()
            if blob not in envelope_at:
                envelope_at[blob] = len(data)
                data.extend(blob)
            offsets.append(envelope_at[blob])
        at = OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1)
        img[at: at + INSTRUMENT_SIZE] = inst.record(offsets)
    assert len(data) <= IMAGE_SIZE - OFF_DATA
    struct.pack_into("<H", img, 14, len(data))
    img[OFF_DATA: OFF_DATA + len(data)] = data
    for o, row in enumerate(uses):
        for c, blob in enumerate(row):
            struct.pack_into("<H", img, OFF_ORDERS + 2 * (CHANNELS * o + c), where[blob])
    return bytes(img)


# ---- the test song ------------------------------------------------------------------------------------------------

def test_instruments():
    """Slot 1 = plain square (A-4 measurement), slot 2 = buzzer (saw 8, auto J00, tone off: the envelope's pitch),
    slot 3 = sync buzzer (triangle 10 with synced tone). Every shape 8..15 is some slot's."""
    lead_volume = Env([10, 13, 15, 14, 12, 11, 10, 8, 6, 4, 2, 0], loop=6, release=7)
    I = {}
    I[1] = Instrument()
    I[2] = Instrument(mixer=Env([0]), shape=8, auto=True, octave=0)
    I[3] = Instrument(mixer=Env([1]), shape=10, auto=True, octave=0, sync=True)
    I[4] = Instrument(shape=9, auto=False, period=0x0800)                       # bell: decay at a manual period
    I[5] = Instrument(shape=14, auto=False, period=0x2000)                      # envelope tremolo
    I[6] = Instrument(volume=lead_volume, pitch=Env([0, 0, 0, 0, 1, 2, 1, 0, -1, -2, -1], loop=3))  # lead
    I[7] = Instrument(volume=Env([15, 14, 13, 12, 12]), arpeggio=Env([0, 4, 7], loop=0))            # arp major
    I[8] = Instrument(volume=Env([15, 14, 12, 10, 8, 5, 3, 0]), mixer=Env([3, 3, 1]), noise=Env([1]),
                      sweep=(24, -12, 48))                                       # kick
    I[9] = Instrument(volume=Env([15, 13, 11, 9, 7, 5, 3, 1, 0]), mixer=Env([3, 2]),
                      noise=Env([4, 6, 9, 12, 15]))                               # snare
    I[10] = Instrument(volume=Env([12, 8, 4, 0]), mixer=Env([2]), noise=Env([1]))  # closed hat
    I[11] = Instrument(volume=Env([8, 10, 12, 14, 15, 15, 10, 5, 0], loop=4, release=5), mixer=Env([2]),
                       noise=Env(list(range(31, 0, -1))))                        # noise riser, released tail
    I[12] = Instrument(volume=Env([15, 14, 13, 11, 9, 7, 5, 3, 1]), pitch=Env([-3], loop=0), relative=True,
                       sweep=(12, 0, 16))                                         # tom: relative pitch + sweep
    I[13] = Instrument(mixer=Env([1]), shape=12, auto=True, octave=-2)           # saw up, two octaves down
    I[14] = Instrument(shape=13, auto=False, period=0x0100)                      # attack and hold
    I[15] = Instrument(mixer=Env([0]), shape=11, auto=False, period=0x0040)      # decay then hold high, no tone
    I[16] = Instrument(mixer=Env([3]), shape=15, auto=True, octave=3, noise=Env([5]))  # attack then silence, noise
    I[17] = Instrument(volume=Env([15, 12, 10, 9, 8, 8, 6, 4, 2, 1, 0], loop=4, release=6))  # pluck, release tail
    I[18] = Instrument(volume=lead_volume, mixer=Env([1, 1, 3], loop=2))         # shares slot 6's volume bytes
    return I


def test_song(variant=0):
    """3 channels, 4 orders (16/32/16/24 rows), loop to order 1 (also by B01 on the last row), speed 6.

    Exercises every effect (0 1 2 3 4 A B C D E F G H I J P S V W) with 1-4 effect columns (blank columns before
    used ones), OFF cells, Sxx (S00 included), Gxx (one at or beyond the speed: never runs), all eight hardware
    envelope shapes, auto periods (J negative, zero and positive, triangles) and manual periods, sync, the note
    settings E H I J V W on rows with and without notes, mixer and noise envelopes, shared noise (two W on different
    channels, the later key-on wins) and the shared envelope handed between channels both with a restart (the shape
    differs) and without (a borrowed envelope with the same shape ends), a relative pitch envelope, release points,
    sweeps, notes at the variant's lowest note and B-7, empty runs and a pattern used in two orders."""
    lo = LOWEST_NOTE[variant]
    C = Cell
    OFF = NOTE_OFF
    P = {}
    # ---- Order 0 (16 rows): buzzer bass on A, envelope borrowed by B twice, noise kit on C.
    P["a0"] = {0: C(36, 2), 4: C(None, None, [("J", 0x01)]), 8: C(None, None, [None, ("E", 0x0C)]),
               10: C(None, None, [("E", 0x0C)]), 12: C(OFF), 14: C(lo, 1)}
    P["b0"] = {2: C(60, 4), 6: C(OFF), 9: C(64, 13), 11: C(OFF),
               13: C(67, 5, [("H", 0x01), ("I", 0x80)]),
               15: C(None, None, [("I", 0x40), ("V", 0x03), ("W", 0x1F)])}
    P["c0"] = {0: C(48, 8), 4: C(48, 9), 6: C(None, None, [("W", 0x02)]), 8: C(48, 10, [("S", 0x02)]),
               10: C(48, 9, [("G", 0x02)]), 12: C(48, 10, [("G", 0x07)]),  # G07 >= speed 6: never runs
               14: C(36, 16, [("W", 0x09), ("V", 0x03)])}
    # ---- Order 1 (32 rows, the loop; no Fxx): lead effects on A, envelope shapes and J on B, drums on C.
    P["a1"] = {0: C(60, 6), 4: C(64, None, [("0", 0x37)]), 8: C(67, None, [("4", 0x46)]), 12: C(OFF),
               16: C(72, 7, [("3", 0x00)]), 18: C(None, None, [("3", 0x20)]), 20: C(76),
               24: C(None, None, [("3", 0x00), ("1", 0x08)]), 26: C(None, None, [("2", 0x04)]),
               28: C(79, 17, [("2", 0x00), ("P", 0x90)]), 30: C(OFF)}
    P["b1"] = {0: C(43, 3), 8: C(43, None, [("J", 0xFF)]), 12: C(45, None, [("J", 0x02), ("E", 0x0E)]),
               16: C(48, 14), 20: C(None, None, [("H", 0x02)]), 24: C(50, 15, [None, None, ("V", 0x00)]),
               28: C(OFF)}
    P["c1"] = {0: C(36, 8), 2: C(42, 10), 4: C(38, 9, [("C", 0x0C)]), 6: C(42, 10), 8: C(36, 8),
               10: C(42, 10, [("A", 0x02)]), 12: C(38, 9, [("A", 0x00), ("C", 0x0F)]), 14: C(36, 12),
               16: C(48, 11), 22: C(OFF), 24: C(36, 8, [("W", 0x03)]), 26: C(42, 18),
               28: C(95, 1, [("A", 0x10)]), 30: C(OFF)}
    # ---- Order 2 (16 rows): a0 again; F04, then two Fxx in one row (the later channel wins), D02 + B03.
    P["b2"] = {0: C(55, 4, [("F", 0x04)]), 8: C(57, None, [("F", 0x09)]), 12: C(59, None, [("D", 0x02)])}
    P["c2"] = {0: C(36, 8), 8: C(38, 9, [("G", 0x02), ("F", 0x05)]), 12: C(None, None, [("B", 0x03)])}
    # ---- Order 3 (24 rows, entered at row 2): four columns, C00 on an envelope note, F07, S00, B01 at the end.
    P["a3"] = {2: C(48, 2, [("A", 0x01)]), 8: C(None, None, [("C", 0x00)]),
               10: C(50, 13, [("E", 0x08), ("J", 0xF8), ("V", 0x01), ("W", 0x1F)]),
               16: C(52, 4, [("F", 0x07)]), 23: C(OFF, None, [("B", 0x01)])}
    P["b3"] = {2: C(64, 16), 6: C(64, 16, [("J", 0x07)]), 12: C(OFF), 16: C(67, 5),
               20: C(None, None, [("E", 0x00)])}
    P["c3"] = {4: C(40, 9), 8: C(40, 9, [("S", 0x00)]), 12: C(45, 6, [("4", 0x24), ("P", 0x70)]),
               18: C(None, None, [("4", 0x20)]), 20: C(OFF)}
    orders = [Order(16, ["a0", "b0", "c0"]), Order(32, ["a1", "b1", "c1"]), Order(16, ["a0", "b2", "c2"]),
              Order(24, ["a3", "b3", "c3"])]
    return Song(variant=variant, speed=6, orders=orders, patterns=P, instruments=test_instruments(), loop=1)


# Edit fixtures (host test: live edits applied before the edited row is reached).
NOTE_EDIT = (1, 16, 1)   # order, row, channel
TEMPO_EDIT = (1, 4)      # order, row (channel C)


def edited_song(variant=0):
    """The test song with one cell edited in order 1 (row 16 of channel B: another note, sound and setting)."""
    s = test_song(variant)
    s.patterns["b1"] = dict(s.patterns["b1"])
    s.patterns["b1"][16] = Cell(53, 4, [("I", 0x20)])
    return s


def tempo_song(variant=0):
    """The test song with an Fxx added on order 1 row 4 (F09 on channel C)."""
    s = test_song(variant)
    s.patterns["c1"] = dict(s.patterns["c1"])
    s.patterns["c1"][4] = Cell(38, 9, [("C", 0x0C), ("F", 0x09)])
    return s


def short_song(variant=0):
    """Only the first two orders, loop 1 (a live edit that removes the playing order)."""
    s = test_song(variant)
    s.orders = s.orders[:2]
    s.loop = 1
    return s


SONGS = {"test": test_song, "edited": edited_song, "tempo": tempo_song, "short": short_song}


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if not 1 <= len(argv) <= 3 or (len(argv) >= 2 and argv[1] not in KEYS) or (
            len(argv) == 3 and argv[2] not in list(SONGS) + ["relayout"]):
        sys.exit("usage: ay_song.py OUT.bin [%s] [%s|relayout]" % ("|".join(KEYS), "|".join(SONGS)))
    variant = KEYS.index(argv[1]) if len(argv) >= 2 else 0
    which = argv[2] if len(argv) == 3 else "test"
    data = build(test_song(variant), relayout=True) if which == "relayout" else build(SONGS[which](variant))
    with open(argv[0], "wb") as f:
        f.write(data)
    return 0


if __name__ == "__main__":
    sys.exit(main())
