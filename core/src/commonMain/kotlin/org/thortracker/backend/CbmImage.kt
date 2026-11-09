package org.thortracker.backend

/**
 * The image Thor Tracker's own VIC-20 / C16-Plus/4 player plays (native `"vic20-vic"` and `"c16-ted"`, `cbm/cbm_player.c`):
 * the constants of `cbm/cbm_image.h` without the `CB_` prefix, written by hand from docs/AUDIO_ENGINE.md ("VIC-20 and
 * C16/Plus/4: Image layout"); `tools/audio/prepare_cbm.py` checks every name and value against the C header and the
 * lane ranges against the Python reference. One layout for both chips: header byte [H_CHIP] tells them apart.
 */
object CbmImage {
    /** The native ops ids. */
    const val VIC_ENGINE_ID = "vic20-vic"
    const val TED_ENGINE_ID = "c16-ted"

    const val MAGIC = "TTCB"
    const val LAYOUT = 1
    const val CHIP_VIC = 0
    const val CHIP_TED = 1
    const val VIDEO_PAL = 0
    const val VIDEO_NTSC = 1

    const val IMAGE_SIZE = 131072
    const val HEADER_SIZE = 64
    const val MAX_CHANNELS = 4
    const val VIC_CHANNELS = 4
    const val TED_CHANNELS = 3
    const val MAX_ORDERS = 256
    const val MAX_INSTRUMENTS = 64
    const val MAX_SAMPLES = 64
    const val MAX_EFFECTS = 2
    const val MAX_MULTISPEED = 4
    const val INSTRUMENT_SIZE = 32
    const val SAMPLE_ENTRY_SIZE = 8
    const val MAX_ENVELOPE = 255
    const val MAX_SAMPLE_FRAMES = 65535
    const val VIC_MAX_VOLUME = 15
    const val TED_MAX_VOLUME = 8
    const val DAC_MAX = 8
    const val TED_NOISE_CHANNEL = 1
    const val TED_DAC_CHANNEL = 2
    const val VIC_NOISE_CHANNEL = 3

    // Header byte offsets.
    const val H_MAGIC = 0
    const val H_LAYOUT = 4
    const val H_CHIP = 5
    const val H_VIDEO = 6
    const val H_MULTISPEED = 7
    const val H_SPEED = 8
    const val H_INSTRUMENTS = 9
    const val H_ORDERS = 10
    const val H_LOOP = 12
    const val H_SAMPLES = 14
    const val H_VOLUME = 15
    const val H_TIMER = 16
    const val H_DATA_SIZE = 20

    // Tables.
    const val OFF_ROWS = 64
    const val OFF_ORDERS = 320
    const val OFF_INSTRUMENTS = 2368
    const val OFF_SAMPLES = 4416
    const val OFF_DATA = 5120
    const val DATA_CAPACITY = 125952

    // Instrument record.
    const val I_FLAGS = 0
    const val I_WAVE = 1
    const val I_SAMPLE = 2
    const val I_ROOT = 3
    const val I_LOOP = 4
    const val I_LOOP_START = 6
    const val I_ENVELOPES = 8
    const val ENVELOPE_BYTES = 6
    const val ENVELOPE_COUNT = 4
    const val ENV_VOLUME = 0
    const val ENV_ARPEGGIO = 1
    const val ENV_PITCH = 2
    const val ENV_WAVE = 3
    const val ENV_NONE = 0xFF
    const val FLAG_DEFINED = 0x80
    const val FLAG_RELATIVE = 0x04
    const val KIND_MASK = 0x03
    const val KIND_TONE = 0
    const val KIND_SAMPLE = 1
    const val WAVE_OFF = 0
    const val WAVE_SQUARE = 1
    const val WAVE_NOISE = 2

    // Pattern rows.
    const val RUN = 0x80
    const val MAX_RUN = 128
    const val MASK_NOTE = 0x01
    const val MASK_INSTRUMENT = 0x02
    const val MASK_EFFECT_SHIFT = 2
    const val NOTE_OFF = 0xFE

    /** `Pxx` without detune. */
    const val FINE_NONE = 0x80

    /** The VIC noise lane's highest rate index. */
    const val NOISE_RATE_MAX = 127

    /** A sample instrument's root note range (the DAC lane's notes). */
    const val ROOT_MIN = 24
    const val ROOT_MAX = 108

    // Effect codes ('0'..'9' = 0..9, 'A'..'Z' = 10..35); every other code is rejected by the loader.
    const val FX_ARPEGGIO = 0
    const val FX_SLIDE_UP = 1
    const val FX_SLIDE_DOWN = 2
    const val FX_PORTAMENTO = 3
    const val FX_VIBRATO = 4
    const val FX_VOLUME_SLIDE = 10
    const val FX_JUMP = 11
    const val FX_BREAK = 13
    const val FX_SPEED = 15
    const val FX_DELAY = 16
    const val FX_FINE = 25
    const val FX_CUT = 28
    const val FX_TIMER = 29
    const val FX_VOLUME = 31
    const val FX_WAVE = 32

    /** Most ticks a play-from-position replays from the song start (72 s on a PAL machine). */
    const val SEEK_TICKS = 3600

    /**
     * Lane note ranges (absolute, 0 = C-0, 57 = A-4): `[variant][channel]` as `first, last`, variant = chip · 2 +
     * video (0 VIC PAL, 1 VIC NTSC, 2 TED PAL, 3 TED NTSC). VIC: Bass, Alto, Soprano, Noise (rate indices); TED: Voice
     * 1, Voice 2, DAC, and no channel 3.
     */
    val LANE_MIN: Array<IntArray> = arrayOf(
        intArrayOf(13, 25, 37, 0),
        intArrayOf(11, 23, 35, 0),
        intArrayOf(33, 33, 24, 0),
        intArrayOf(33, 33, 24, 0),
    )
    val LANE_MAX: Array<IntArray> = arrayOf(
        intArrayOf(52, 64, 76, 127),
        intArrayOf(48, 60, 72, 127),
        intArrayOf(108, 127, 108, 0),
        intArrayOf(108, 127, 108, 0),
    )

    /** The notes [channel] of [chip] ([CHIP_VIC], [CHIP_TED]) on [video] plays; empty for a channel the chip lacks. */
    fun laneRange(chip: Int, video: Int, channel: Int): IntRange {
        val channels = if (chip == CHIP_VIC) VIC_CHANNELS else TED_CHANNELS
        if (channel !in 0 until channels) return IntRange.EMPTY
        val v = chip * 2 + video
        return LANE_MIN[v][channel]..LANE_MAX[v][channel]
    }
}
