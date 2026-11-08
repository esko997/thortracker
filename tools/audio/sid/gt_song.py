"""GoatTracker 2 song files (.sng, "GTS5") and their lowering into the Thor Tracker SID image layout.

Used by tools/audio/prepare_sid_player.py. The lowering is the reference for the Kotlin lowering of
SID step 2 (docs/AUDIO_ENGINE.md, "SID", "Image layout"): it produces GoatTracker's packed playroutine
data exactly as GoatTracker 2.77's packer (src/greloc.c, `relocator()` with "Disable optimization")
does, except that

  - every table row and every instrument of the song is kept (the packer drops rows and instruments
    no pattern reaches, and merges duplicate table parts): rows keep their numbers, so tables need no
    remapping, and unused instruments can still be auditioned;
  - everything sits at the fixed addresses of the image layout instead of following the player.

For a song whose tables are all reached, duplicate-free and whose instruments are all used, the packed
bytes equal the packer's (prepare_sid_player.py checks this against gt2reloc's output).
"""
from dataclasses import dataclass, field

# GoatTracker 2 constants (src/gcommon.h)
MAX_INSTR = 64
MAX_PATT = 208
MAX_TABLELEN = 255
MAX_PATTROWS = 128
MAX_SONGLEN = 254
MAX_NOTES = 96
REPEAT, TRANSDOWN, TRANSUP, LOOPSONG = 0xD0, 0xE0, 0xF0, 0xFF
ENDPATT = 0xFF
FX, FXONLY, FIRSTNOTE, LASTNOTE = 0x40, 0x50, 0x60, 0xBC
REST, KEYOFF, KEYON = 0xBD, 0xBE, 0xBF
WAVEDELAY, WAVELASTDELAY, WAVESILENT, WAVELASTSILENT, WAVECMD, WAVELASTCMD = 0x01, 0x0F, 0xE0, 0xEF, 0xF0, 0xFE
CMD_PORTAUP, CMD_PORTADOWN, CMD_TONEPORTA, CMD_VIBRATO = 1, 2, 3, 4
CMD_SETWAVEPTR, CMD_SETPULSEPTR, CMD_SETFILTERPTR = 8, 9, 10
CMD_SETMASTERVOL, CMD_FUNKTEMPO, CMD_SETTEMPO = 13, 14, 15
WTBL, PTBL, FTBL, STBL = 0, 1, 2, 3

# GoatTracker's default frequency table (src/gplay.c freqtbllo/freqtblhi): note 0 = C-0, 57 = A-4.
FREQ_LO = bytes([
    0x17, 0x27, 0x39, 0x4b, 0x5f, 0x74, 0x8a, 0xa1, 0xba, 0xd4, 0xf0, 0x0e,
    0x2d, 0x4e, 0x71, 0x96, 0xbe, 0xe8, 0x14, 0x43, 0x74, 0xa9, 0xe1, 0x1c,
    0x5a, 0x9c, 0xe2, 0x2d, 0x7c, 0xcf, 0x28, 0x85, 0xe8, 0x52, 0xc1, 0x37,
    0xb4, 0x39, 0xc5, 0x5a, 0xf7, 0x9e, 0x4f, 0x0a, 0xd1, 0xa3, 0x82, 0x6e,
    0x68, 0x71, 0x8a, 0xb3, 0xee, 0x3c, 0x9e, 0x15, 0xa2, 0x46, 0x04, 0xdc,
    0xd0, 0xe2, 0x14, 0x67, 0xdd, 0x79, 0x3c, 0x29, 0x44, 0x8d, 0x08, 0xb8,
    0xa1, 0xc5, 0x28, 0xcd, 0xba, 0xf1, 0x78, 0x53, 0x87, 0x1a, 0x10, 0x71,
    0x42, 0x89, 0x4f, 0x9b, 0x74, 0xe2, 0xf0, 0xa6, 0x0e, 0x33, 0x20, 0xff])
FREQ_HI = bytes([
    0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02,
    0x02, 0x02, 0x02, 0x02, 0x02, 0x02, 0x03, 0x03, 0x03, 0x03, 0x03, 0x04,
    0x04, 0x04, 0x04, 0x05, 0x05, 0x05, 0x06, 0x06, 0x06, 0x07, 0x07, 0x08,
    0x08, 0x09, 0x09, 0x0a, 0x0a, 0x0b, 0x0c, 0x0d, 0x0d, 0x0e, 0x0f, 0x10,
    0x11, 0x12, 0x13, 0x14, 0x15, 0x17, 0x18, 0x1a, 0x1b, 0x1d, 0x1f, 0x20,
    0x22, 0x24, 0x27, 0x29, 0x2b, 0x2e, 0x31, 0x34, 0x37, 0x3a, 0x3e, 0x41,
    0x45, 0x49, 0x4e, 0x52, 0x57, 0x5c, 0x62, 0x68, 0x6e, 0x75, 0x7c, 0x83,
    0x8b, 0x93, 0x9c, 0xa5, 0xaf, 0xb9, 0xc4, 0xd0, 0xdd, 0xea, 0xf8, 0xff])

# ---- the Thor Tracker image layout (mirrored by generated/sid_player_image.h) ------------------
PLAYER_BASE = 0x1000
ZP_BASE = 0xFC
SID_BASE = 0xD400
REGION_BASE = 0x1800
REGION_END = 0xD000                    # exclusive: the region is C64 RAM $1800-$CFFF
LABELS = {                             # player data labels at fixed addresses
    "mt_freqtbllo": 0x1800, "mt_freqtblhi": 0x1860,
    "mt_songtbllo": 0x18C0, "mt_songtblhi": 0x18C3,
    "mt_patttbllo": 0x1900, "mt_patttblhi": 0x19D0,
    "mt_insad": 0x1AC0, "mt_inssr": 0x1B00, "mt_inswaveptr": 0x1B40, "mt_inspulseptr": 0x1B80,
    "mt_insfiltptr": 0x1BC0, "mt_insvibparam": 0x1C00, "mt_insvibdelay": 0x1C40,
    "mt_insgatetimer": 0x1C80, "mt_insfirstwave": 0x1CC0,
    "mt_wavetbl": 0x1D00, "mt_notetbl": 0x1E00,
    "mt_pulsetimetbl": 0x1F00, "mt_pulsespdtbl": 0x2000,
    "mt_filttimetbl": 0x2100, "mt_filtspdtbl": 0x2200,
    "mt_speedlefttbl": 0x2301, "mt_speedrighttbl": 0x2401,   # $2300 and $2400 stay 0 (index 0)
}
INSTR_COLUMNS = ["mt_insad", "mt_inssr", "mt_inswaveptr", "mt_inspulseptr", "mt_insfiltptr",
                 "mt_insvibparam", "mt_insvibdelay", "mt_insgatetimer", "mt_insfirstwave"]
TABLE_LEFT = ["mt_wavetbl", "mt_pulsetimetbl", "mt_filttimetbl", "mt_speedlefttbl"]
TABLE_RIGHT = ["mt_notetbl", "mt_pulsespdtbl", "mt_filtspdtbl", "mt_speedrighttbl"]
HEAP_BASE = 0x2500                     # orderlists, then patterns, up to REGION_END
IMAGE_MAGIC = b"TTSD"
IMAGE_HEADER_SIZE = 96
IMAGE_SIZE = IMAGE_HEADER_SIZE + (REGION_END - REGION_BASE)
LAYOUT_THOR = 1
MODEL_6581, MODEL_8580 = 0, 1


@dataclass
class Instrument:
    ad: int = 0
    sr: int = 0
    wave: int = 0
    pulse: int = 0
    filt: int = 0
    vibparam: int = 0
    vibdelay: int = 0
    gatetimer: int = 2
    firstwave: int = 0x09
    name: str = ""


@dataclass
class Song:
    """One GoatTracker song (subtune 0 only is lowered). Patterns are lists of (note, instr, cmd, param)
    rows; orderlists hold pattern numbers and commands without the RST endmark; `restart` per channel."""
    name: str = ""
    author: str = ""
    copyright: str = ""
    orderlists: list = field(default_factory=lambda: [[], [], []])
    restart: list = field(default_factory=lambda: [0, 0, 0])
    instruments: list = field(default_factory=list)           # instrument 1.. at index 0..
    tables: list = field(default_factory=lambda: [[], [], [], []])  # per table: list of (left, right)
    patterns: list = field(default_factory=list)


def _text(s: str) -> bytes:
    b = s.encode("latin-1")[:32]
    return b + bytes(32 - len(b))


def write_sng(song: Song) -> bytes:
    out = bytearray(b"GTS5")
    out += _text(song.name) + _text(song.author) + _text(song.copyright)
    out.append(1)  # subtunes
    for c in range(3):
        ol = list(song.orderlists[c]) + [LOOPSONG]
        out.append(len(ol))
        out += bytes(ol) + bytes([song.restart[c]])
    out.append(len(song.instruments))
    for ins in song.instruments:
        out += bytes([ins.ad, ins.sr, ins.wave, ins.pulse, ins.filt, ins.vibparam, ins.vibdelay,
                      ins.gatetimer, ins.firstwave])
        name = ins.name.encode("latin-1")[:16]
        out += name + bytes(16 - len(name))
    for t in song.tables:
        out.append(len(t))
        out += bytes(l for l, _ in t) + bytes(r for _, r in t)
    out.append(len(song.patterns))
    for p in song.patterns:
        out.append(len(p) + 1)
        for row in p:
            out += bytes(row)
        out += bytes([ENDPATT, 0, 0, 0])
    return bytes(out)


def read_sng(data: bytes) -> Song:
    if data[:4] != b"GTS5":
        raise ValueError("not a GTS5 song")
    pos = 4
    def take(n):
        nonlocal pos
        b = data[pos:pos + n]
        if len(b) != n:
            raise ValueError("truncated song")
        pos += n
        return b
    song = Song()
    song.name, song.author, song.copyright = (take(32).rstrip(b"\0").decode("latin-1") for _ in range(3))
    subtunes = take(1)[0]
    for t in range(subtunes):
        for c in range(3):
            n = take(1)[0]
            ol = take(n + 1)
            if t == 0:
                end = ol.index(LOOPSONG)
                song.orderlists[c] = list(ol[:end])
                song.restart[c] = ol[end + 1]
    for _ in range(take(1)[0]):
        b = take(9)
        song.instruments.append(Instrument(*b, name=take(16).rstrip(b"\0").decode("latin-1")))
    for t in range(4):
        n = take(1)[0]
        left, right = take(n), take(n)
        song.tables[t] = list(zip(left, right))
    for _ in range(take(1)[0]):
        n = take(1)[0]
        rows = take(4 * n)
        p = [tuple(rows[4 * i:4 * i + 4]) for i in range(n)]
        end = next(i for i, r in enumerate(p) if r[0] == ENDPATT)
        song.patterns.append(p[:end])
    return song


def pack_pattern(rows, instrmap) -> bytes:
    """greloc.c packpattern() with optimizations disabled (noeffects = 0, identity table maps)."""
    temp1 = []
    instr = 0
    for c, (note, ins, cmd, param) in enumerate(rows):
        if c and ins and ins == instr:
            ins = 0
        elif ins:
            instr = ins
        if cmd == CMD_SETMASTERVOL and param > 0x0F:  # timing marks need author info: dropped
            cmd, param = 0, 0
        if cmd == CMD_SETTEMPO and (param & 0x7F) >= 3:
            param -= 1
        temp1.append((note, ins, cmd, param))
    temp2 = []
    command = databyte = -1
    for note, ins, cmd, param in temp1:
        if ins:
            temp2.append(instrmap[ins])
        if note == REST:
            if (cmd, param) != (command, databyte):
                command, databyte = cmd, param
                temp2.append(FXONLY + cmd)
                if cmd:
                    temp2.append(param)
            else:
                temp2.append(REST)
        else:
            if (cmd, param) != (command, databyte):
                command, databyte = cmd, param
                temp2.append(FX + cmd)
                if cmd:
                    temp2.append(param)
            temp2.append(note)
    dest = []
    c = 0
    n = len(temp2)
    while c < n:
        packok = c != 0
        if temp2[c] < FX:
            dest.append(temp2[c]); c += 1
            packok = False
        if FXONLY <= temp2[c] < FIRSTNOTE:
            fx = temp2[c] - FXONLY
            dest.append(temp2[c]); c += 1
            if fx:
                dest.append(temp2[c]); c += 1
            continue
        if temp2[c] < FXONLY:
            fx = temp2[c] - FX
            dest.append(temp2[c]); c += 1
            if fx:
                dest.append(temp2[c]); c += 1
            packok = False
        if temp2[c] != REST:
            packok = False
        if not packok:
            dest.append(temp2[c]); c += 1
        else:
            d = c
            while d < n and temp2[d] == REST:
                d += 1
                if d - c == 64:
                    break
            d -= c
            if d > 1:
                dest.append((-d) & 0xFF); c += d
            else:
                dest.append(temp2[c]); c += 1
    if len(dest) > 256:
        raise ValueError("pattern too complex (over 256 bytes packed)")
    if len(dest) < 256:
        dest.append(0)
    return bytes(dest)


def instrument_map(song: Song, multiplier: int = 1, reverse_groups: bool = False):
    """greloc.c's instrument order (normal, then no-hard-restart ($80), then legato ($40), each by
    number) for every instrument of the song, and the player's FIRSTNOHRINSTR / FIRSTLEGATOINSTR.
    In multispeed (multiplier > 1) gt2reloc raises FIRSTLEGATOINSTR by one (it increments its no-HR
    count after numbering); that is mirrored so exports equal GoatTracker's. `reverse_groups` numbers
    each group backwards except player number 1, which channels use before their first instrument
    byte (an equally valid order that plays the same; test fixtures)."""
    groups = ([], [], [])
    for i, ins in enumerate(song.instruments, start=1):
        g = 2 if ins.gatetimer & 0x40 else 1 if ins.gatetimer & 0x80 else 0
        groups[g].append(i)
    if reverse_groups:
        first = next(g for g in groups if g)
        groups = tuple(g[:1] + g[:0:-1] if g is first else g[::-1] for g in groups)
    order = groups[0] + groups[1] + groups[2]
    instrmap = {num: k + 1 for k, num in enumerate(order)}
    nohr = len(groups[1]) + (1 if multiplier > 1 else 0)
    return instrmap, order, len(groups[0]) + 1, len(groups[0]) + nohr + 1


def default_tempo(song: Song, multiplier: int = 1) -> int:
    """greloc.c DEFAULTTEMPO: the attack/decay of instrument 63 minus one when that instrument is a
    tempo holder (A/D >= 2 and no wavetable), else 6 ticks per row times the multiplier, minus one."""
    if len(song.instruments) >= 63:
        last = song.instruments[62]
        if last.ad >= 2 and not last.wave:
            return last.ad - 1
    return multiplier * 6 - 1 if multiplier else 5


def convert_tables(song: Song):
    """Left/right columns as greloc.c writes them (NOOPT: wave delays kept, full pulse), all rows."""
    out = []
    for t in range(4):
        left, right = [], []
        for l, r in song.tables[t]:
            if t == WTBL:
                w = l
                if WAVESILENT <= l <= WAVELASTSILENT:
                    w &= 0x0F
                if WAVELASTDELAY < l <= WAVELASTSILENT:
                    w += 0x10
                left.append(w)
                right.append(r if (WAVECMD <= l <= WAVELASTCMD or l == 0xFF) else r ^ 0x80)
            elif t == FTBL:
                left.append(((l & 0x70) >> 1) | 0x80 if (l != 0xFF and l > 0x80) else l)
                right.append(r)
            else:
                left.append(l)
                right.append(r)
        out.append((bytes(left), bytes(right)))
    return out


@dataclass
class Lowered:
    image: bytes
    region: bytearray
    instrmap: dict
    firstnohr: int
    firstlegato: int
    tempo: int
    patterns: dict          # song pattern number -> (packed number, packed bytes, address)
    orderlists: list        # per channel: packed bytes (with $FF, restart)
    data_end: int


def lower(song: Song, player_id: bytes, model: int = MODEL_8580, multiplier: int = 1,
          hr_ad: int = 0x0F, hr_sr: int = 0x00, relayout: bool = False) -> Lowered:
    """Thor Tracker image of subtune 0 (docs/AUDIO_ENGINE.md, SID "Image layout"). `relayout` lays the
    same song out differently (instrument numbers reversed within their groups, pattern numbers
    reversed, patterns stored backwards with gaps before the orderlists): it plays the same, and live
    edits between the two layouts must relocate (host-test fixture)."""
    n = len(song.orderlists[0])
    for c in range(3):
        ol = song.orderlists[c]
        if len(ol) != n or song.restart[c] != song.restart[0]:
            raise ValueError("Thor layout: every channel has the same number of orders and the same restart")
        if any(e >= REPEAT for e in ol):
            raise ValueError("Thor layout: orderlists hold pattern numbers only (no repeat/transpose)")
    if not 1 <= n <= MAX_SONGLEN or song.restart[0] >= n:
        raise ValueError("bad orderlist length or restart")
    if not 1 <= len(song.instruments) <= 63:
        raise ValueError("1..63 instruments")
    instrmap, order, firstnohr, firstlegato = instrument_map(song, multiplier, reverse_groups=relayout)
    region = bytearray(REGION_END - REGION_BASE)

    def put(addr, data):
        region[addr - REGION_BASE:addr - REGION_BASE + len(data)] = data

    put(LABELS["mt_freqtbllo"], FREQ_LO)
    put(LABELS["mt_freqtblhi"], FREQ_HI)
    for k, num in enumerate(order):
        ins = song.instruments[num - 1]
        vib = (ins.vibparam, ins.vibdelay - 1) if ins.vibdelay else (0, 0)
        cols = [ins.ad, ins.sr, ins.wave, ins.pulse, ins.filt, vib[0], vib[1], ins.gatetimer & 0x3F, ins.firstwave]
        for col, v in zip(INSTR_COLUMNS, cols):
            region[LABELS[col] + k - REGION_BASE] = v
    for t, (left, right) in enumerate(convert_tables(song)):
        put(LABELS[TABLE_LEFT[t]], left)
        put(LABELS[TABLE_RIGHT[t]], right)
    # patterns used by the orderlists, numbered in pattern-number order (greloc's pattmap)
    used = sorted({p for ol in song.orderlists for p in ol})
    pattmap = {p: (len(used) - 1 - k if relayout else k) for k, p in enumerate(used)}
    if len(used) > MAX_PATT:
        raise ValueError("too many patterns")
    heap = HEAP_BASE
    orderlists = []
    patterns = {}

    def place_orderlists():
        nonlocal heap
        for c in range(3):
            ol = bytes([pattmap[p] for p in song.orderlists[c]] + [LOOPSONG, song.restart[c]])
            orderlists.append(ol)
            region[LABELS["mt_songtbllo"] + c - REGION_BASE] = heap & 0xFF
            region[LABELS["mt_songtblhi"] + c - REGION_BASE] = heap >> 8
            put(heap, ol)
            heap += len(ol)

    def place_patterns():
        nonlocal heap
        for p in (used[::-1] if relayout else used):
            packed = pack_pattern(song.patterns[p], instrmap)
            k = pattmap[p]
            region[LABELS["mt_patttbllo"] + k - REGION_BASE] = heap & 0xFF
            region[LABELS["mt_patttblhi"] + k - REGION_BASE] = heap >> 8
            put(heap, packed)
            patterns[p] = (k, packed, heap)
            heap += len(packed) + (5 if relayout else 0)

    if relayout:
        place_patterns()
        place_orderlists()
    else:
        place_orderlists()
        place_patterns()
    if heap > REGION_END:
        raise ValueError("song data exceeds the region")
    tempo = default_tempo(song, multiplier)
    header = bytearray(IMAGE_HEADER_SIZE)
    header[0:4] = IMAGE_MAGIC
    header[4:8] = player_id
    header[8:10] = REGION_BASE.to_bytes(2, "little")
    header[10:12] = heap.to_bytes(2, "little")
    header[12] = LAYOUT_THOR
    header[13] = model
    header[14] = multiplier
    header[15] = tempo
    header[16] = firstnohr
    header[17] = firstlegato
    header[18] = hr_ad
    header[19] = hr_sr
    header[20] = len(song.instruments)
    for slot, num in instrmap.items():
        header[32 + slot] = num
    return Lowered(bytes(header) + bytes(region), region, instrmap, firstnohr, firstlegato, tempo,
                   patterns, orderlists, heap)
