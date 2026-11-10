package org.thortracker.backend

/**
 * The Virtual Boy VSU system's native contract: Thor Tracker's own player on a VSU core after Mednafen 1.32.1 (with the
 * Sacred Tech Scroll's amendments), engine `"vb-vsu"`. A hand-kept mirror of `app/src/main/cpp/vsu/vsu_image.h` and
 * the player's numbers (docs/AUDIO_ENGINE.md, "Virtual Boy VSU": the specification). Change both together. All
 * multi-byte image fields are little-endian.
 */
object VsuPlayerImage {
    /** Native engine id ([org.thortracker.system.PlaybackEngine.id]). */
    const val ENGINE_ID = "vb-vsu"

    /** One fixed-size image per song (`VB_IMAGE_SIZE`). */
    const val IMAGE_SIZE = 65536
    const val MAGIC = "TTVB"
    const val LAYOUT = 1

    /** Header field offsets (64-byte header; byte 11 and bytes 14..63 zero). */
    const val H_MAGIC = 0
    const val H_LAYOUT = 4
    const val H_SPEED = 5
    const val H_ORDERS = 6
    const val H_LOOP = 8
    const val H_INSTRUMENTS = 10
    const val H_DATA_SIZE = 12
    const val HEADER_SIZE = 64

    /**
     * Fixed tables and regions: rows of order o at [OFF_ROWS] + o (1..255, 0 = 256; Thor Tracker writes at most
     * [MAX_ROWS]); the pattern of (order o, lane c) as a u16 data-region offset at [OFF_ORDERS] + 2 (6 o + c);
     * instrument slot n (1..64) at [OFF_INSTRUMENTS] + [INSTRUMENT_SIZE] (n − 1); wave w (0..4) sample i at
     * [OFF_WAVES] + 32 w + i (0..63); the modulation table (32 s8) at [OFF_MODULATION]; pattern data from [OFF_DATA],
     * [DATA_CAPACITY] bytes.
     */
    const val OFF_ROWS = 64
    const val OFF_ORDERS = 320
    const val OFF_INSTRUMENTS = 3392
    const val INSTRUMENT_SIZE = 16
    const val OFF_WAVES = 4416
    const val OFF_MODULATION = 4576
    const val OFF_DATA = 4608
    const val DATA_CAPACITY = IMAGE_SIZE - OFF_DATA

    /** Song-wide wave RAM: [WAVES] waves of [WAVE_SAMPLES] 6-bit samples; modulation RAM: [MOD_VALUES] signed bytes. */
    const val WAVES = 5
    const val WAVE_SAMPLES = 32
    const val SAMPLE_MAX = 63
    const val MOD_VALUES = 32

    /**
     * Instrument record (16 bytes; all-zero = no instrument; records above [H_INSTRUMENTS] zero; bytes 11..15 zero):
     * flags ([FLAG_DEFINED], [FLAG_ENVELOPE], [FLAG_REPEAT], [FLAG_GROW], [FLAG_AUTO]; bits 4-6 zero), envelope start
     * level 0..15, envelope interval 0..7, length 0..31, wave 0..4, noise tap 0..7, frequency modification
     * ([FM_ON], [FM_MODULATION], [FM_REPEAT]; bits 3-7 zero), S5SWP ([SWP_SLOW], interval at [SWP_INTERVAL_SHIFT],
     * [SWP_UP], shift in [SWP_SHIFT_MASK]), software pitch sweep start/end (s8 semitones −96..96, both 0 when the speed
     * is 0) and speed (1/16 semitone per tick, 0 = none).
     */
    const val I_FLAGS = 0
    const val I_LEVEL = 1
    const val I_INTERVAL = 2
    const val I_LENGTH = 3
    const val I_WAVE = 4
    const val I_TAP = 5
    const val I_FREQ_MOD = 6
    const val I_SWP = 7
    const val I_SWEEP_START = 8
    const val I_SWEEP_END = 9
    const val I_SWEEP_SPEED = 10

    const val FLAG_DEFINED = 0x80
    const val FLAG_ENVELOPE = 0x01
    const val FLAG_REPEAT = 0x02
    const val FLAG_GROW = 0x04
    const val FLAG_AUTO = 0x08

    const val FM_ON = 0x01
    const val FM_MODULATION = 0x02
    const val FM_REPEAT = 0x04

    const val SWP_SLOW = 0x80
    const val SWP_INTERVAL_SHIFT = 4
    const val SWP_UP = 0x08
    const val SWP_SHIFT_MASK = 0x07

    const val LEVEL_MAX = 15
    const val INTERVAL_MAX = 7
    const val LENGTH_MAX = 31
    const val TAP_MAX = 7
    const val SWEEP_LIMIT = 96

    /**
     * Pattern rows: [RUN] | k = k + 1 empty rows (k 0..127, [MAX_RUN] rows at most); else a mask byte m (0x01..0x0B):
     * [MASK_NOTE] (note byte follows), [MASK_INSTRUMENT] (slot byte follows), effect count 0..2 at bits 2-3
     * ([MASK_EFFECT_SHIFT]), bits 4-6 zero; then the note (absolute, or [NOTE_OFF]), the slot (1..I, defined) and per
     * effect column its code (0..35 = '0'..'9', 'A'..'Z') and parameter. Each (order, lane) decodes exactly
     * rows(order) rows inside the used data.
     */
    const val RUN = 0x80
    const val MAX_RUN = 128
    const val MASK_NOTE = 0x01
    const val MASK_INSTRUMENT = 0x02
    const val MASK_EFFECT_SHIFT = 2
    const val NOTE_OFF = 0xFE

    /** Limits. Lanes: Wave 1-4 (0..3), Wave 5 ([SWEEP_LANE], sweep/modulation) and Noise ([NOISE_LANE]). */
    const val CHANNELS = 6
    const val SWEEP_LANE = 4
    const val NOISE_LANE = 5
    const val MAX_ORDERS = 256
    const val MAX_ROWS = 64
    const val MAX_INSTRUMENTS = 64
    const val MAX_EFFECTS = 2

    /** Notes: absolute, 0 = C-0, 57 = A-4; wave lanes D#2..B7 ([WAVE_NOTE_LOW]..95), the noise lane C-0..B-7. */
    const val NOTES = 96
    const val WAVE_NOTE_LOW = 27

    /**
     * Pitch units: 1/64 semitone, 0 = C-0, note n = 64 n; at most [PITCH_MAX]. Wave lanes clamp to
     * [WAVE_PITCH_MIN]..[PITCH_MAX] (D-2 would need a negative frequency register). Fine pitch (Pxx, audition)
     * −128..127.
     */
    const val PITCH_UNITS_PER_SEMITONE = 64
    const val PITCH_MAX = 6143
    const val WAVE_PITCH_MIN = 1728
    const val PITCH_A4 = 3648

    /** Effect codes (column code byte); [EFFECT_LETTERS][code] is the name. */
    const val EFFECT_LETTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val FX_ARPEGGIO = 0 // 0
    const val FX_SLIDE_UP = 1 // 1
    const val FX_SLIDE_DOWN = 2 // 2
    const val FX_PORTAMENTO = 3 // 3
    const val FX_VIBRATO = 4 // 4
    const val FX_STEREO = 8 // 8
    const val FX_JUMP = 11 // B
    const val FX_LEVEL = 12 // C
    const val FX_BREAK = 13 // D
    const val FX_SPEED = 15 // F
    const val FX_DELAY = 16 // G
    const val FX_LENGTH = 21 // L
    const val FX_SWEEP_MOD = 22 // M
    const val FX_NOISE_TAP = 23 // N
    const val FX_FINE = 25 // P
    const val FX_CUT = 28 // S
    const val FX_WAVE = 32 // W

    /** Effect parameter limits the loader checks: Cxx ≤ [LEVEL_MAX], Lxx ≤ [LENGTH_LOCK_MAX], Nxx ≤ [TAP_MAX], Wxx 1..[WAVES]. */
    const val LENGTH_LOCK_MAX = 0x20

    /**
     * Chip and timing: the 5 MHz VSU clock; one tick = one VIP frame (50.0 Hz) = [TICK_CLOCKS] clocks; output sampled every
     * [SAMPLE_CLOCKS] clocks (41666.7 Hz). Wave channels step a sample every 2048 − F clocks (32 samples per period:
     * [WAVE_BASE_HZ] / (2048 − F) Hz); noise steps its shift register every 10 (2048 − F) clocks ([NOISE_BASE_HZ] /
     * (2048 − F) Hz). Effects tick 4800 clocks (0.96 ms), length unit 3.84 ms, envelope unit 15.36 ms, sweep/modulation
     * unit 0.96 ms or 7.68 ms.
     */
    const val CLOCK = 5000000
    const val TICK_HZ = 50
    const val TICK_CLOCKS = 100000
    const val SAMPLE_CLOCKS = 120
    const val WAVE_BASE_HZ = 156250.0
    const val NOISE_BASE_HZ = 500000.0
    const val FREQ_MAX = 2047
    const val EFFECTS_TICK_MS = 0.96
    const val LENGTH_UNIT_MS = 3.84
    const val ENVELOPE_UNIT_MS = 15.36
    const val SWEEP_SLOW_MS = 7.68

    /** Noise tap sequences: the shift register's period per tap (S6EV1 bits 4-6). */
    val TAP_LENGTHS: IntArray get() = intArrayOf(32767, 1953, 254, 217, 73, 63, 42, 28)

    /** Seek: [org.thortracker.system.PlaybackEngine.seekTicks] budget (4 minutes); the output path runs for the last [PREROLL_TICKS]. */
    const val SEEK_TICKS = 12000
    const val PREROLL_TICKS = 50

    /** Most register writes the player makes in one tick (`VBP_MAX_WRITES`). */
    const val MAX_WRITES = 256

    /** VGM export (native `vb_export_vgm`): the GD3 system name the file carries. */
    const val GD3_SYSTEM = "Virtual Boy VSU (Thor Tracker)"

    /**
     * The load-time checks the specification lists for `vb_load_image` (docs/AUDIO_ENGINE.md, "Image layout"), in
     * this mirror's words; the native loader reports its own reason text and the offending byte. A song the Kotlin
     * validator accepts must pass every one ([org.thortracker.system.vsu.VsuValidator] mirrors each with a user-facing
     * message).
     */
    val LOADER_CHECKS: List<String> = listOf(
        "magic \"TTVB\" and layout 1",
        "speed 1..255",
        "order count 1..256, loop order below it",
        "instrument count 0..64; header bytes 11 and 14..63 zero",
        "data size at most 60928",
        "rows and pattern offsets of orders past the count zero",
        "instrument records above the count zero; defined records flagged, flag bits 4-6 zero",
        "instrument level 0..15, interval 0..7, length 0..31, wave 0..4, noise tap 0..7",
        "frequency modification bits 3-7 zero",
        "software sweep start/end -96..96, both 0 without a speed; record bytes 11..15 zero",
        "wave samples 0..63",
        "pattern offsets and every decoded row inside [0, data size)",
        "row mask non-zero, bits 4-6 zero, at most 2 effects",
        "note: wave lanes 27..95, noise lane 0..95, or OFF",
        "instrument byte: a defined slot 1..I",
        "effect codes the lane declares; Bxx below the order count; Cxx at most 0F; F00 rejected",
        "Lxx at most 20; Mxx only on Wave 5; Nxx at most 07 and only on Noise; Wxx 01..05 and only on wave lanes",
    )
}
