package org.thortracker.system.saa

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.thortracker.backend.SaaPlayerImage
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound
import org.thortracker.system.nes.NesEnvelope

/**
 * Which machine a song is for (docs/AUDIO_ENGINE.md, "SAA1099"). [SAM]: the SAM Coupé, one SAA1099 at 8 MHz, six
 * lanes `SAA 1`-`SAA 6`, 50 player ticks per second. [CMS]: Creative's Music System / Game Blaster, two SAA1099s at
 * 7159090 Hz summed into one stereo output, twelve lanes `A1`-`A6` (chip A) and `B1`-`B6` (chip B), 60 ticks per
 * second. [noteRange] is the chips' real range (B-0..A#8 on the SAM, A-0..G#8 on the CMS); [variantByte] the image's.
 */
@Serializable
enum class SaaVariant(
    /** The machine's name. */
    val label: String,
    /** The sound hardware, for names and messages ("SAA1099", "2× SAA1099"). */
    val chipLabel: String,
    /** SAA1099s in the machine: 1 or 2. */
    val chips: Int,
    /** Player ticks per second at multispeed 1. */
    val rate: Int,
    /** The chips' clock in Hz. */
    val clock: Int,
    /** Notes the system offers (absolute, 0 = C-0). */
    val noteRange: IntRange,
    /** Lane names, one per tracker channel. */
    val laneNames: List<String>,
    /** The image header's variant byte. */
    val variantByte: Int,
) {
    @SerialName("sam") SAM(
        "SAM Coupé", "SAA1099", 1, SaaPlayerImage.RATE_SAM, SaaPlayerImage.CLOCK_SAM,
        SaaPlayerImage.LOWEST_NOTE_SAM..SaaPlayerImage.HIGHEST_NOTE_SAM,
        List(SaaPlayerImage.CHANNELS_SAM) { "SAA ${it + 1}" }, SaaPlayerImage.VARIANT_SAM,
    ),
    @SerialName("cms") CMS(
        "Creative Music System", "2× SAA1099", 2, SaaPlayerImage.RATE_CMS, SaaPlayerImage.CLOCK_CMS,
        SaaPlayerImage.LOWEST_NOTE_CMS..SaaPlayerImage.HIGHEST_NOTE_CMS,
        List(SaaPlayerImage.CHANNELS_CMS) { "${if (it < SaaSound.CHIP_CHANNELS) 'A' else 'B'}${it % SaaSound.CHIP_CHANNELS + 1}" },
        SaaPlayerImage.VARIANT_CMS,
    );

    /** Tracker channels: six per chip. */
    val channels: Int get() = chips * SaaSound.CHIP_CHANNELS

    /** True for the two-chip CMS (the generated pitch helpers' `cms` argument). */
    val cms: Boolean get() = this == CMS

    /** The system that plays this variant's songs. */
    val system: Saa1099System get() = when (this) {
        SAM -> SamCoupeSystem
        CMS -> CmsSystem
    }
}

/**
 * An instrument's optional hardware envelope (the chip's envelope controller; only lanes 3 and 6 of each chip have
 * one, docs/AUDIO_ENGINE.md "SAA1099: Channels and shared resources"). [shape] 0..7 (zero, maximum, single decay,
 * repetitive decay, single triangle, repetitive triangle, single attack, repetitive attack); [threeBit]: 8 levels
 * instead of 16; [invertRight]: the right output plays the inverse of the left; [external]: one step every [rate]
 * player ticks (1..255) instead of twice per period of lane 2's (5's) tone generator (internal clock; [rate] is then
 * unused and stored as 0); [retrigger]: each note restarts the envelope.
 */
@Serializable
data class SaaHardwareEnvelope(
    val shape: Int = REPETITIVE_DECAY,
    val threeBit: Boolean = false,
    val invertRight: Boolean = false,
    val external: Boolean = false,
    val rate: Int = 1,
    val retrigger: Boolean = true,
) {
    /**
     * The chip's envelope control register value: bit 7 on, bit 5 external, bit 4 3-bit, bits 3-1 [shape], bit 0
     * [invertRight] (the image's instrument byte 3 and `Exx`'s parameter).
     */
    val control: Int
        get() = SaaPlayerImage.ENV_ENABLE or (if (external) SaaPlayerImage.ENV_EXTERNAL else 0) or
            (if (threeBit) SaaPlayerImage.ENV_3BIT else 0) or (shape shl SaaPlayerImage.ENV_SHAPE_SHIFT) or
            (if (invertRight) SaaPlayerImage.ENV_INVERT else 0)

    /** Why the envelope cannot be stored, or null. */
    fun problem(): String? = when {
        shape !in SHAPES -> "its hardware envelope shape $shape is outside 0-7"
        rate !in RATES -> "its hardware envelope rate $rate is outside 1-255 ticks per step"
        else -> null
    }

    companion object {
        const val ZERO = 0
        const val MAXIMUM = 1
        const val SINGLE_DECAY = 2
        const val REPETITIVE_DECAY = 3
        const val SINGLE_TRIANGLE = 4
        const val REPETITIVE_TRIANGLE = 5
        const val SINGLE_ATTACK = 6
        const val REPETITIVE_ATTACK = 7

        val SHAPES = 0..7
        val RATES = 1..255

        /** Shape names, by shape number. */
        val SHAPE_NAMES = listOf(
            "zero", "maximum", "single decay", "repetitive decay", "single triangle", "repetitive triangle",
            "single attack", "repetitive attack",
        )

        /** True for the shapes that repeat (1, 3, 5, 7); the others end after one cycle. */
        fun looping(shape: Int): Boolean = shape and 1 == 1

        /** Phases of a cycle: 2 for the triangles, else 1. */
        fun phases(shape: Int): Int = if (shape == SINGLE_TRIANGLE || shape == REPETITIVE_TRIANGLE) 2 else 1

        /** Envelope steps of one cycle: 16 (or 8 at 3 bits) per phase. */
        fun steps(shape: Int, threeBit: Boolean): Int = phases(shape) * (if (threeBit) 8 else 16)

        /**
         * The left output's level at each step of one cycle (SAASound's `cs_EnvData`): 0..15, or at 3 bits the even
         * levels 0..14. After a one-shot shape's cycle the level is 0 (an internal clock); an external clock holds
         * the last step (docs/AUDIO_ENGINE.md, "Envelope pass").
         */
        fun levels(shape: Int, threeBit: Boolean): IntArray {
            val res = if (threeBit) 2 else 1
            val perPhase = 16 / res
            return IntArray(phases(shape) * perPhase) { k ->
                val phase = k / perPhase
                val pos = (k % perPhase) * res
                val v = when (shape) {
                    ZERO -> 0
                    MAXIMUM -> 15
                    SINGLE_DECAY, REPETITIVE_DECAY -> 15 - pos
                    SINGLE_TRIANGLE, REPETITIVE_TRIANGLE -> if (phase == 0) pos else 15 - pos
                    else -> pos
                }
                if (threeBit) v and 0x0E else v
            }
        }

        /** The right output's level for left level [left]: its inverse when inverted (15 or 14 minus it), else the same. */
        fun right(left: Int, threeBit: Boolean, invert: Boolean): Int = if (!invert) left else (if (threeBit) 14 else 15) - left

        /**
         * The envelope a control register value [control] (bit 7 set; `Exx`) describes, with [rate] for an external
         * clock; null for 0 (off) or a value without bit 7.
         */
        fun ofControl(control: Int, rate: Int = 1): SaaHardwareEnvelope? =
            if (control and SaaPlayerImage.ENV_ENABLE == 0) null
            else SaaHardwareEnvelope(
                shape = (control shr SaaPlayerImage.ENV_SHAPE_SHIFT) and 7,
                threeBit = control and SaaPlayerImage.ENV_3BIT != 0,
                invertRight = control and SaaPlayerImage.ENV_INVERT != 0,
                external = control and SaaPlayerImage.ENV_EXTERNAL != 0,
                rate = rate,
                retrigger = true,
            )
    }
}

/**
 * An SAA1099 instrument: FamiStudio-style envelopes ([NesEnvelope]: a value per player tick, a loop point and, for
 * [volume] only, a release point) run by Thor Tracker's player, plus the chip settings a note takes.
 * [volume] 0..15 (empty = constant 15), scaled by the channel volume (`Cxx`); [arpeggio] semitone offsets -64..63
 * (empty = none); [pitch] offsets in 1/16 semitone -64..63 (empty = none), accumulated every tick when
 * [pitchRelative] (sweeps for drums); [mixer] per tick 0 off, 1 tone, 2 noise, 3 tone + noise (empty = tone).
 * [noise] the noise clock a note with noise claims for its lane triple's generator: 0 = clock / 256, 1 = / 512,
 * 2 = / 1024, 3 = twice lane 1's (4's) tone frequency. [panLeft], [panRight] 0..15: the chip's amplitude controllers.
 * [envelope]: the hardware envelope (lanes 3 and 6 only), or null.
 */
@Serializable
data class SaaInstrument(
    val name: String = "",
    val volume: NesEnvelope = NesEnvelope(listOf(15)),
    val arpeggio: NesEnvelope = NesEnvelope(),
    val pitch: NesEnvelope = NesEnvelope(),
    val mixer: NesEnvelope = NesEnvelope(),
    val pitchRelative: Boolean = false,
    val noise: Int = 0,
    val panLeft: Int = 15,
    val panRight: Int = 15,
    val envelope: SaaHardwareEnvelope? = null,
) {
    /** The image's pan byte: left level in bits 0-3, right in 4-7. */
    val pan: Int get() = (panLeft and 0x0F) or ((panRight and 0x0F) shl 4)

    /** True when some tick of the mixer envelope sounds noise. */
    val usesNoise: Boolean get() = mixer.values.any { it and MIXER_NOISE != 0 }

    companion object {
        const val MIXER_OFF = 0
        const val MIXER_TONE = 1
        const val MIXER_NOISE = 2
        const val MIXER_BOTH = 3

        /** Mixer values. */
        val MIXER = 0..3

        /** Noise clocks. */
        val NOISE = 0..3

        /** Pan (amplitude) levels. */
        val PAN = 0..15
    }
}

/**
 * An SAA1099 song's chip data: the [variant] (it must match the song's backend), [instruments] = exactly [SLOTS] slots
 * (slot n = index n - 1; null = empty, not playable) and [multispeed] 1..8 player ticks per 50 Hz (SAM) or 60 Hz (CMS)
 * frame.
 */
@Serializable
data class SaaSound(
    val variant: SaaVariant,
    val instruments: List<SaaInstrument?>,
    val multispeed: Int = 1,
) : SystemSound {
    override val system: SoundSystem get() = variant.system

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): SaaInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: SaaInstrument?): SaaSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    companion object {
        const val SLOTS = 64

        /** Lanes per chip. */
        const val CHIP_CHANNELS = 6

        /** Chip (0 = A, 1 = B) of tracker [channel]. */
        fun chipOf(channel: Int): Int = channel / CHIP_CHANNELS

        /** The chip's channel 0..5 of tracker [channel]. */
        fun laneOf(channel: Int): Int = channel % CHIP_CHANNELS

        /** The noise generator (0: lanes 1-3, 1: lanes 4-6 of its chip) [channel] uses. */
        fun generatorOf(channel: Int): Int = laneOf(channel) / 3

        /** The tracker channels sharing generator [generator] of [chip]. */
        fun generatorLanes(chip: Int, generator: Int): IntRange = (chip * CHIP_CHANNELS + 3 * generator).let { it..it + 2 }

        /** True on lanes 3 and 6 of a chip, the two with a hardware envelope. */
        fun hasEnvelope(channel: Int): Boolean = laneOf(channel) % 3 == 2

        /** The tracker channel whose tone generator clocks [channel]'s envelope internally (lane 2 or 5). */
        fun envelopeClockLane(channel: Int): Int = channel - 1

        /** The tracker channel whose tone generator clocks [channel]'s noise generator at clock 3 (lane 1 or 4). */
        fun noiseClockLane(channel: Int): Int = generatorLanes(chipOf(channel), generatorOf(channel)).first

        /** A new song's chip data for [variant]: the presets ([SaaPresets.ALL]) in slots 01-12, the rest empty; multispeed 1. */
        fun blank(variant: SaaVariant): SaaSound = SaaSound(
            variant = variant,
            instruments = List(SLOTS) { SaaPresets.ALL.getOrNull(it)?.instrument },
        )
    }
}

/** The SAA1099 data of a SAM Coupé or CMS song. Only SAA1099 code calls it, on SAA1099 songs. */
val Song.saa: SaaSound get() = sound as SaaSound
