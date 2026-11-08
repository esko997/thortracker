"""The SID host-test song, written for these tests as a GoatTracker 2 song (gt_test.sng).

Shape: the Thor Tracker layout (every channel has one pattern per order and the patterns of an order
have equal lengths), four orders of 16/32/16/24 rows looping to order 1. It is packed by GoatTracker's
own packer (gt2reloc, optimizations disabled) and lowered by gt_song.lower; both must play the same.

Coverage: hard-restart, no-hard-restart ($80) and legato ($40, first wave $00 / $FF) instruments;
instrument vibrato (normal and note-independent speeds), every pattern command 1-F (portamento up and
down, toneportamento, vibrato, set AD/SR/wave/wave pointer/pulse pointer/filter pointer/filter control/
cutoff/master volume, funktempo and tempo), key off/on, wavetable delays, "keep frequency", relative and
absolute notes, an inaudible waveform and wavetable commands (vibrato, pulse pointer), pulse and filter
modulation with loops, packed rests, a pattern reused in two orders, and a held A-4 on a triangle
(440 Hz) for 16 rows. Every table row is reached and no table part is duplicated, so the packer keeps
all of them and its bytes can be compared with the lowering's.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gt_song import KEYOFF, KEYON, REST, Instrument, Song  # noqa: E402

NAMES = {"C-": 0, "C#": 1, "D-": 2, "D#": 3, "E-": 4, "F-": 5, "F#": 6, "G-": 7, "G#": 8, "A-": 9, "A#": 10, "B-": 11}


def n(name: str) -> int:
    """GoatTracker pattern note byte: $60 + 12 * octave + semitone (C-0 = $60, A-4 = $60 + 57)."""
    return 0x60 + 12 * int(name[2]) + NAMES[name[:2]]


def pattern(rows: int, cells: dict) -> list:
    """`cells`: row -> (note or REST/KEYOFF/KEYON, instrument, command, parameter)."""
    out = []
    for r in range(rows):
        cell = cells.get(r, (REST, 0, 0, 0))
        note = n(cell[0]) if isinstance(cell[0], str) else cell[0]
        out.append((note,) + tuple(cell[1:]))
    return out


def build() -> Song:
    song = Song(name="Thor Tracker SID test", author="Thor Tracker", copyright="2026 GPL-3.0-or-later")
    song.instruments = [
        Instrument(0x0A, 0xA9, wave=1, pulse=1, filt=0, vibparam=1, vibdelay=8, gatetimer=2, firstwave=0x09, name="Lead"),
        Instrument(0x06, 0xC8, wave=3, pulse=5, filt=1, gatetimer=2, firstwave=0x09, name="Bass"),
        Instrument(0x00, 0xD9, wave=6, pulse=7, gatetimer=2, firstwave=0x09, name="Snare"),
        Instrument(0x00, 0xA8, wave=12, pulse=0, gatetimer=0x82, firstwave=0x09, name="Arp no HR"),
        Instrument(0x5A, 0x9A, wave=17, pulse=0, vibparam=5, vibdelay=4, gatetimer=0x42, firstwave=0x00, name="Pad legato"),
        Instrument(0x00, 0xF0, wave=22, gatetimer=2, firstwave=0x09, name="Tone"),
        Instrument(0x22, 0x88, wave=24, gatetimer=0x42, firstwave=0xFF, name="Legato gate on"),
    ]
    wave = [
        (0x41, 0x00), (0xFF, 0x00),                                            # 1  lead pulse
        (0x81, 0xD0), (0x21, 0x00), (0xFF, 0x00),                              # 3  bass: noise attack, saw
        (0x81, 0xD4), (0x41, 0xAA), (0x41, 0xA4), (0x80, 0xD4), (0x80, 0xD1), (0xFF, 0x00),  # 6 snare
        (0x41, 0x00), (0x00, 0x04), (0x00, 0x07), (0x00, 0x0C), (0xFF, 0x0D),  # 12 arp loop
        (0x11, 0x00), (0x03, 0x80), (0x21, 0x80), (0xF4, 0x02), (0xFF, 0x00),  # 17 pad: delay, keep freq, cmd 4
        (0x11, 0x00), (0xFF, 0x00),                                            # 22 tone: triangle
        (0xE9, 0x00), (0x41, 0x0C), (0xF9, 0x09), (0xFF, 0x00),                # 24 inaudible, +12, cmd 9
    ]
    pulse = [
        (0x88, 0x00), (0x20, 0x20), (0x20, 0xE0), (0xFF, 0x02),                # 1  lead PWM loop
        (0x84, 0x00), (0xFF, 0x00),                                            # 5  bass
        (0x8C, 0x00), (0xFF, 0x00),                                            # 7  snare
        (0x82, 0x00), (0x10, 0x40), (0xFF, 0x00),                              # 9  from wave cmd F9 09
    ]
    filt = [
        (0x90, 0x82), (0x00, 0x30), (0x20, 0x02), (0x20, 0xFE), (0xFF, 0x03),  # 1  bass lowpass sweep
        (0xA0, 0xF2), (0x00, 0x60), (0xFF, 0x00),                              # 6  bandpass via A06
    ]
    speed = [
        (0x03, 0x30),  # 1 lead vibrato
        (0x04, 0x20),  # 2 vibrato (wave cmd F4 02, pattern 402)
        (0x00, 0x40),  # 3 portamento $0040
        (0x01, 0x00),  # 4 portamento $0100
        (0x83, 0x03),  # 5 note-independent vibrato
        (0x06, 0x04),  # 6 funktempo 6/4
        (0x80, 0x01),  # 7 note-independent portamento
    ]
    song.tables = [wave, pulse, filt, speed]
    P = []
    # order 0
    P.append(pattern(16, {0: ("C-4", 1, 0xF, 0x06), 2: (REST, 0, 0xD, 0x0F), 4: ("E-4", 0, 0, 0),
                          8: ("G-4", 0, 0x4, 0x05), 12: (KEYOFF, 0, 0, 0), 14: (KEYON, 0, 0, 0)}))
    P.append(pattern(16, {0: ("C-2", 2, 0, 0), 4: ("C-2", 0, 0, 0), 6: (REST, 0, 0xA, 0x06),
                          8: ("G-1", 0, 0x2, 0x03), 12: ("C-2", 0, 0xB, 0x00), 14: (REST, 0, 0xC, 0x40)}))
    P.append(pattern(16, {0: ("C-5", 4, 0, 0), 4: ("D-3", 3, 0, 0), 8: ("A-4", 4, 0, 0), 12: ("D-3", 3, 0, 0)}))
    # order 1
    P.append(pattern(32, {0: ("A-4", 6, 0, 0), 16: ("C-5", 1, 0x1, 0x04), 20: ("E-5", 0, 0x3, 0x03),
                          24: (REST, 0, 0x5, 0x0A), 26: (REST, 0, 0x6, 0xF8), 28: (REST, 0, 0x7, 0x21),
                          30: (REST, 0, 0x8, 0x01)}))
    P.append(pattern(32, {0: ("C-3", 5, 0, 0), 8: ("E-3", 0, 0, 0), 16: ("G-3", 0, 0, 0),
                          24: ("C-3", 0, 0x9, 0x05), 28: (KEYOFF, 0, 0, 0)}))
    P.append(pattern(32, {0: ("C-4", 4, 0xE, 0x06), 8: ("D-3", 3, 0, 0), 16: ("D#4", 4, 0, 0),
                          24: ("D-3", 3, 0, 0), 31: (REST, 0, 0xF, 0x06)}))
    # order 2
    P.append(pattern(16, {0: ("G-4", 7, 0xF, 0x05), 4: (REST, 0, 0xC, 0x50), 8: ("A#4", 0, 0xD, 0x0C),
                          12: (REST, 0, 0xD, 0x0F)}))
    # order 3
    P.append(pattern(24, {0: ("D-5", 1, 0x4, 0x02), 8: ("F-4", 0, 0x2, 0x07), 16: ("A-4", 0, 0, 0)}))
    P.append(pattern(24, {0: ("D-2", 2, 0x1, 0x03), 8: ("F-2", 0, 0, 0), 16: ("A-1", 0, 0x3, 0x04)}))
    P.append(pattern(24, {0: ("D-3", 3, 0, 0), 6: ("F-4", 4, 0, 0), 12: ("D-3", 3, 0, 0), 18: ("A-4", 4, 0xF, 0x07)}))
    song.patterns = P
    song.orderlists = [[0, 3, 6, 7], [1, 4, 1, 8], [2, 5, 2, 9]]
    song.restart = [1, 1, 1]
    return song
