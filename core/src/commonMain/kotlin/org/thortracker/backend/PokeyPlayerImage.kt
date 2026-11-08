package org.thortracker.backend

/**
 * The POKEY system's native contract: Thor Tracker's own player on its own cycle-exact POKEY model, engine
 * `"atari-pokey"`. A hand-kept mirror of `app/src/main/cpp/pokey/pokey_image.h` and the machine constants of
 * `pokey_chip.h` (docs/AUDIO_ENGINE.md, "POKEY": the specification). Change both together. All multi-byte image fields
 * are little-endian.
 */
object PokeyPlayerImage {
    /** Native engine id ([org.thortracker.system.PlaybackEngine.id]). */
    const val ENGINE_ID = "atari-pokey"

    /** One fixed-size image per song (`PK_IMAGE_SIZE`). */
    const val IMAGE_SIZE = 65536
    const val MAGIC = "TTPK"
    const val LAYOUT = 1

    const val MACHINE_NTSC = 0
    const val MACHINE_PAL = 1

    /** Header field offsets (64-byte header; bytes 19..63 zero). */
    const val H_MAGIC = 0
    const val H_LAYOUT = 4
    const val H_MACHINE = 5
    const val H_MULTISPEED = 6
    const val H_SPEED = 7
    const val H_ORDERS = 8
    const val H_LOOP = 10
    const val H_INSTRUMENTS = 12
    const val H_AUDCTL = 13
    const val H_DATA_SIZE = 14
    const val H_SAMPLE_SIZE = 16
    const val H_SAMPLES = 18
    const val HEADER_SIZE = 64

    /** Header byte [H_AUDCTL]: the shared AUDCTL bits at the song start; no others allowed. */
    const val AUDCTL_POLY9 = 0x80
    const val AUDCTL_BASE15 = 0x01

    /**
     * Fixed tables and regions: rows of order o at [OFF_ROWS] + o (1..255, 0 = 256); the pattern of (order o,
     * channel c) as a u16 data-region offset at [OFF_ORDERS] + 2 (4 o + c); instrument slot n (1..64) at
     * [OFF_INSTRUMENTS] + [INSTRUMENT_SIZE] (n − 1); sample k (0..31) at [OFF_SAMPLES] + [SAMPLE_ENTRY_SIZE] k (u16 byte
     * offset into the sample region, u16 length in levels); the data region (patterns and envelopes) from [OFF_DATA],
     * [DATA_CAPACITY] bytes; the sample region (two 4-bit levels per byte, the earlier in the low nibble) from
     * [OFF_SAMPLE_DATA], [SAMPLE_CAPACITY] bytes.
     */
    const val OFF_ROWS = 64
    const val OFF_ORDERS = 320
    const val OFF_INSTRUMENTS = 2368
    const val INSTRUMENT_SIZE = 32
    const val OFF_SAMPLES = 4416
    const val SAMPLE_ENTRY_SIZE = 4
    const val OFF_DATA = 4544
    const val OFF_SAMPLE_DATA = 49152
    const val DATA_CAPACITY = OFF_SAMPLE_DATA - OFF_DATA
    const val SAMPLE_CAPACITY = IMAGE_SIZE - OFF_SAMPLE_DATA
    const val MAX_SAMPLE_LENGTH = 32768

    /** Instrument record (32 bytes; all-zero = no instrument; records above [H_INSTRUMENTS] zero; bytes 5, 18..31 zero). */
    const val I_FLAGS = 0
    const val I_FILTER_OFFSET = 1
    const val I_PARTNER_AUDC = 2
    const val I_SAMPLE = 3
    const val I_ROOT = 4
    const val I_RATE = 6
    const val I_LOOP = 8
    const val I_ENV_VOLUME = 10
    const val I_ENV_ARPEGGIO = 12
    const val I_ENV_PITCH = 14
    const val I_ENV_DISTORTION = 16

    const val FLAG_DEFINED = 0x80
    const val FLAG_JOINED = 0x01
    const val FLAG_FILTERED = 0x02
    const val FLAG_SAMPLE = 0x04
    const val FLAG_FAST = 0x08
    const val FLAG_PITCH_RELATIVE = 0x10
    const val FLAG_DIFFERENCE = 0x20
    const val FLAG_ALL = 0xBF

    /** Distortion values (envelopes, `Vxx`): AUDC bits 7-5 = value for 0..7; 8 = volume only. */
    const val DISTORTIONS = 9
    const val DIST_PURE = 5
    const val DIST_VOLUME_ONLY = 8

    /** Envelope: u16 length 1..[MAX_ENVELOPE], u16 loop (< length or [ENV_NONE]), u16 release (1..length − 1, > loop, or [ENV_NONE]), values. */
    const val ENV_HEADER = 6
    const val ENV_NONE = 0xFFFF
    const val MAX_ENVELOPE = 256

    /** Sample sounds: levels per second at the root note. */
    const val MIN_RATE = 1000
    const val MAX_RATE = 32000
    const val LOOP_NONE = 0xFFFF

    /**
     * Pattern rows: [RUN] | k = k + 1 empty rows (k 0..127, [MAX_RUN] rows at most); else a mask byte ([MASK_NOTE],
     * [MASK_INSTRUMENT], effect count 0..4 at bits 2-4), then the note (0..107 absolute or [NOTE_OFF]), the slot (1..64)
     * and per effect column code (0..35) and parameter.
     */
    const val RUN = 0x80
    const val MAX_RUN = 128
    const val MASK_NOTE = 0x01
    const val MASK_INSTRUMENT = 0x02
    const val MASK_EFFECT_SHIFT = 2
    const val NOTE_OFF = 0xFE
    const val NOTES = 108

    /** Limits. */
    const val CHANNELS = 4
    const val MAX_ORDERS = 256
    const val MAX_INSTRUMENTS = 64
    const val MAX_SAMPLES = 32
    const val MAX_EFFECTS = 4
    const val MAX_MULTISPEED = 4

    /** Effect codes (column code byte; '0'..'9' = 0..9, 'A'..'Z' = 10..35). */
    const val FX_ARPEGGIO = 0 // 0
    const val FX_SLIDE_UP = 1 // 1
    const val FX_SLIDE_DOWN = 2 // 2
    const val FX_PORTAMENTO = 3 // 3
    const val FX_VIBRATO = 4 // 4
    const val FX_VOLUME_SLIDE = 10 // A
    const val FX_JUMP = 11 // B
    const val FX_VOLUME = 12 // C
    const val FX_BREAK = 13 // D
    const val FX_SPEED = 15 // F
    const val FX_DELAY = 16 // G
    const val FX_FILTER = 17 // H
    const val FX_CLOCK = 20 // K
    const val FX_POLY = 23 // N
    const val FX_FINE = 25 // P
    const val FX_RESTART = 27 // R
    const val FX_CUT = 28 // S
    const val FX_TIMERS = 29 // T
    const val FX_DISTORTION = 31 // V
    const val FX_TWO_TONE = 32 // W
    const val FINE_CENTRE = 0x80

    /** Pitch units: 1/64 semitone, 0 = C-0, note n = 64 n; A-4 = 3648 = 440 Hz; at most [PITCH_MAX]. */
    const val PITCH_UNITS_PER_SEMITONE = 64
    const val PITCH_A4 = 3648
    const val PITCH_MAX = NOTES * PITCH_UNITS_PER_SEMITONE - 1

    /** Machine clocks (`PK_CLOCK_*`) and video frames in machine cycles (114 per line, 262 / 312 lines). */
    const val CLOCK_NTSC = 315_000_000.0 / 176.0
    const val CLOCK_PAL = 1_773_447.5
    const val FRAME_CYCLES_NTSC = 29868
    const val FRAME_CYCLES_PAL = 35568

    /** Audition `release()`: the voice is cut after this long at the latest (`PKM_RELEASE_SECONDS`). */
    const val RELEASE_LIMIT_SECONDS = 10

    /** The relative pitch envelope's accumulator is clamped to ± this many sixteenths (`PKP_RELATIVE_LIMIT`). */
    const val RELATIVE_LIMIT = 1728

    /** Most register writes in one tick's batch (`PKP_MAX_WRITES`). */
    const val MAX_WRITES = 16

    /** Most host frames one tick renders at 96 kHz (`PKM_MAX_TICK_FRAMES`) and the blip_buf step scale (`PKM_MIX_SCALE`). */
    const val MAX_TICK_FRAMES = 1928
    const val MIX_SCALE = 16384

    /** Counter periods: (N + 1) × [BASE_64] or × [BASE_15] cycles on a base clock; N + [FAST_8] / N + [FAST_16] at 1.79 MHz. */
    const val BASE_64 = 28
    const val BASE_15 = 114
    const val FAST_8 = 4
    const val FAST_16 = 7

    /** Samples: no counter period shorter than one scan line (one level per line at most). */
    const val PCM_MIN_PERIOD = 114

    /** [org.thortracker.system.PlaybackEngine.seekTicks]: ticks a play-from-position may replay, fully clocked. */
    const val SEEK_TICKS = 12000

    /** VGM export (native `pk_export_vgm`): the header's integer POKEY clocks and the GD3 system name. */
    const val VGM_CLOCK_NTSC = 1789773
    const val VGM_CLOCK_PAL = 1773448
    const val GD3_SYSTEM = "Atari 8-bit POKEY (Thor Tracker)"
}
