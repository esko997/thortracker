package org.thortracker.backend

/**
 * The OPN / OPNA systems' native contract: Thor Tracker's own player on ymfm's YM2203 (engine `"ym2203-opn"`) and
 * YM2608 (engine `"ym2608-opna"`). The image layout the lowering writes, its limits and the effect codes, exactly as
 * `app/src/main/cpp/opn/opn_image.h` defines them (`docs/AUDIO_ENGINE.md`, "OPN and OPNA": the specification). Kept in
 * step with that header by hand: a lowered image that the native loader rejects is a bug the native host test's
 * Kotlin-lowered demo images (`wav_render_test`) and the loader's problem message expose. All multi-byte fields are
 * little-endian.
 */
object OpnPlayerImage {
    const val ENGINE_YM2203 = "ym2203-opn"
    const val ENGINE_YM2608 = "ym2608-opna"

    const val CHIP_YM2203 = 0
    const val CHIP_YM2608 = 1

    /** Master clocks: PC-88 / PC-98 (31.9488 MHz / 8 and / 4). */
    const val CLOCK_YM2203 = 3993600
    const val CLOCK_YM2608 = 7987200

    /** Native sample rate (one FM clock) on both chips: 3993600 / 72 = 7987200 / 144 Hz. */
    const val NATIVE_RATE = 3993600.0 / 72.0

    /** The SSG's effective clock on both chips; tone = [SSG_CLOCK] / (16 × TP). */
    const val SSG_CLOCK = 1996800

    const val CHANNELS_YM2203 = 6
    const val CHANNELS_YM2608 = 16
    const val MAX_CHANNELS = 16
    const val FM_YM2203 = 3
    const val FM_YM2608 = 6
    const val SSG = 3
    const val RHYTHM = 6
    const val OPNA_CH_SSG = 6
    const val OPNA_CH_RHYTHM = 9
    const val OPNA_CH_ADPCM = 15
    const val CH_FM3 = 2

    /** The song part of an image; a whole YM2203 image. */
    const val IMAGE_SIZE = 65536

    /** The YM2608's ADPCM-B memory: 256 KiB. */
    const val ADPCM_MEMORY = 262144

    /** A whole YM2608 image: the song part, then the ADPCM-B memory. */
    const val OPNA_IMAGE_SIZE = IMAGE_SIZE + ADPCM_MEMORY

    const val MAX_ORDERS = 256
    const val MAX_INSTRUMENTS = 64
    const val MAX_SAMPLES = 64
    const val MAX_EFFECTS = 4
    const val LAYOUT = 1

    // Header.
    const val H_MAGIC = 0
    const val H_LAYOUT = 4
    const val H_CHIP = 5
    const val H_SPEED = 6
    const val H_LFO = 7
    const val H_ORDERS = 8
    const val H_LOOP = 10
    const val H_INSTRUMENTS = 12
    const val H_FM3_MODE = 13
    const val H_DATA_SIZE = 14
    const val H_TIMER_A = 16
    const val H_TIMER_B = 18
    const val H_RHYTHM_LEVEL = 19
    const val H_SAMPLES = 20
    const val H_ADPCM_SIZE = 24
    const val HEADER_SIZE = 64
    const val MAGIC = "TTOP"

    const val FM3_NORMAL = 0
    const val FM3_SPECIAL = 1
    const val FM3_CSM = 2

    const val TIMER_B_MIN = 183
    const val TIMER_B_MAX = 250
    const val TIMER_B_DEFAULT = 198
    const val TIMER_A_MAX = 1023

    // Fixed tables.
    const val OFF_ROWS = 64
    const val OFF_ORDERS = 320
    const val OFF_INSTRUMENTS = 8512
    const val INSTRUMENT_SIZE = 48
    const val OFF_SAMPLES = 11584
    const val SAMPLE_SIZE = 16
    const val OFF_DATA = 12608
    const val DATA_CAPACITY = IMAGE_SIZE - OFF_DATA
    const val OFF_ADPCM = 65536

    // Instrument record.
    const val I_FLAGS = 0
    const val FLAG_DEFINED = 0x80
    const val KIND_FM = 0
    const val KIND_SSG = 1
    const val KIND_RHYTHM = 2
    const val KIND_ADPCM = 3
    const val I_ALG = 1
    const val I_FEEDBACK = 2
    const val I_AMS_FMS = 3
    const val I_OPS = 4
    const val OP_BYTES = 7
    const val I_CH3 = 32
    const val CH3_LIMIT = 3072
    const val I_SSG_FLAGS = 1
    const val SSG_PITCH_RELATIVE = 0x01
    const val I_SSG_SHAPE = 2
    const val I_SSG_AUTO = 3
    const val I_SSG_OCTAVE = 4
    const val I_SSG_PERIOD = 5
    const val I_ENV_VOLUME = 8
    const val I_ENV_ARPEGGIO = 10
    const val I_ENV_PITCH = 12
    const val I_ENV_MIXER = 14
    const val I_ENV_NOISE = 16
    const val NO_ENVELOPE = 0xFFFF
    const val MIX_BOTH = 0
    const val MIX_NOISE = 1
    const val MIX_TONE = 2
    const val MIX_NONE = 3
    const val ENV_HEADER = 6
    const val ENV_MAX_LENGTH = 256
    const val PITCH_UNIT = 4
    const val I_RHYTHM_LEVEL = 1
    const val I_PAN = 2
    const val I_ADPCM_LEVEL = 3
    const val I_ADPCM_SAMPLE = 4
    const val I_ADPCM_ROOT = 5
    const val I_ADPCM_FLAGS = 6
    const val ADPCM_LOOP = 0x01
    const val I_SWEEP_START = 40
    const val I_SWEEP_END = 41
    const val I_SWEEP_SPEED = 42
    const val SWEEP_LIMIT = 96

    // ADPCM-B sample table.
    const val S_START = 0
    const val S_END = 4
    const val S_RATE = 8
    const val ADPCM_ALIGN = 32
    const val RATE_MIN = 1000
    const val RATE_MAX = 55466

    // Pattern rows.
    const val RUN = 0x80
    const val MAX_RUN = 128
    const val MASK_NOTE = 0x01
    const val MASK_INSTRUMENT = 0x02
    const val MASK_EFFECT_SHIFT = 2
    const val NOTE_OFF = 0xFE
    const val NOTES = 96
    const val SSG_LOWEST = 11

    // Effect codes.
    const val FX_ARPEGGIO = 0
    const val FX_SLIDE_UP = 1
    const val FX_SLIDE_DOWN = 2
    const val FX_PORTAMENTO = 3
    const val FX_VIBRATO = 4
    const val FX_PAN = 8
    const val FX_VOLUME_SLIDE = 10
    const val FX_JUMP = 11
    const val FX_VOLUME = 12
    const val FX_BREAK = 13
    const val FX_FEEDBACK = 14
    const val FX_SPEED = 15
    const val FX_DELAY = 16
    const val FX_ENV_SHAPE = 17
    const val FX_BRIGHTNESS = 18
    const val FX_ENV_PERIOD = 19
    const val FX_LFO = 21
    const val FX_MULT = 22
    const val FX_NOISE = 23
    const val FX_FINE = 25
    const val FX_RHYTHM_LEVEL = 27
    const val FX_CUT = 28
    const val FX_TIMER_A = 29

    /** Carrier operators (bit k = operator k + 1) per OPN algorithm 0..7. */
    val CARRIERS = intArrayOf(0x8, 0x8, 0x8, 0x8, 0xA, 0xE, 0xE, 0xF)

    /** Player ticks a play-from-position may spend fast-forwarding from the song start (~3.3 min at 60 Hz). */
    const val SEEK_TICKS = 12000

    /** Ticks per second at timer B value [timerB]: one tick per timer B period, 16 × (256 − TB) native samples. */
    fun tickHz(timerB: Int): Double = NATIVE_RATE / (16.0 * (256 - timerB))

    /** Bytes of a whole image of [chip]. */
    fun imageSize(chip: Int): Int = if (chip == CHIP_YM2608) OPNA_IMAGE_SIZE else IMAGE_SIZE
}
