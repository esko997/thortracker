package org.thortracker.system.ay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.thortracker.backend.AyPlayerImage
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.ym2612.YmPsgInstrument

/**
 * The stock computer an AY song plays on (docs/AUDIO_ENGINE.md, "AY-3-8910 / YM2149: Variants"): its chip, clock,
 * frame interrupt and output wiring. [index] is the image's variant byte; [ym] marks the YM2149's 32-level DAC and
 * envelope (the AY chips have 16 levels). Clocks and tick rates are exact ratios of the machine's master clock, from
 * [AyPlayerImage].
 */
@Serializable
enum class AyVariant(
    /** The image's variant byte (`AY_VARIANT_*`). */
    val index: Int,
    /** The computer, e.g. "ZX Spectrum 128". */
    val label: String,
    /** The chip it carries, e.g. "AY-3-8912". */
    val chip: String,
    /** True for the YM2149 (32 levels); false for the GI AY-3-8910/8912 (16 levels). */
    val ym: Boolean,
    /** How file names and sample files name the variant ("zx128"); also its serialized name. */
    val fileKey: String,
) {
    @SerialName("zx128") ZX128(AyPlayerImage.VARIANT_ZX128, "ZX Spectrum 128", "AY-3-8912", false, "zx128"),
    @SerialName("msx") MSX(AyPlayerImage.VARIANT_MSX, "MSX", "AY-3-8910", false, "msx"),
    @SerialName("atari-st") ATARI_ST(AyPlayerImage.VARIANT_ATARI_ST, "Atari ST", "YM2149F", true, "atari-st"),
    @SerialName("cpc") CPC(AyPlayerImage.VARIANT_CPC, "Amstrad CPC", "AY-3-8912", false, "cpc");

    /** The native engine's id ("ay-zx128"). */
    val engineId: String = AyPlayerImage.ENGINE_IDS[index]

    /** Player ticks (frame interrupts) per second: master clock / frame divider. */
    val tickHz: Double = AyPlayerImage.MASTER_HZ[index].toDouble() / AyPlayerImage.FRAME_DIV[index]

    /** The chip clock in Hz: master clock / the chip's divider. */
    val clockHz: Double = AyPlayerImage.MASTER_HZ[index].toDouble() / AyPlayerImage.AY_DIV[index]

    /**
     * True for the Amstrad CPC's stock stereo socket (left = A + B, right = B + C); false for the machines whose output
     * is one mono mix of the three channels.
     */
    val stereo: Boolean = AyPlayerImage.STEREO[index] != 0

    /** The system songs on this computer belong to. */
    val system: AySystem
        get() = when (this) {
            ZX128 -> AyZx128System
            MSX -> AyMsxSystem
            ATARI_ST -> AyAtariStSystem
            CPC -> AyCpcSystem
        }

    companion object {
        /** The variant with image byte [index], or null. */
        fun of(index: Int): AyVariant? = entries.firstOrNull { it.index == index }
    }
}

/**
 * The drum pitch sweep, as the Mega Drive's: each key-on starts [start] semitones from the note and moves toward [end]
 * at [speed]/16 semitones per tick; [speed] 0 = no sweep ([start]/[end] ignored). Ranges −96..96 and 0..255.
 */
@Serializable
data class AySweep(val start: Int = 0, val end: Int = 0, val speed: Int = 0) {
    val active: Boolean get() = speed != 0

    fun problem(): String? = when {
        start !in -AyPlayerImage.SWEEP_LIMIT..AyPlayerImage.SWEEP_LIMIT || end !in -AyPlayerImage.SWEEP_LIMIT..AyPlayerImage.SWEEP_LIMIT ->
            "sweep start and end must be -${AyPlayerImage.SWEEP_LIMIT}..${AyPlayerImage.SWEEP_LIMIT} semitones"
        speed !in 0..255 -> "sweep speed $speed is outside 0-255"
        else -> null
    }

    companion object {
        val NONE = AySweep()
    }
}

/**
 * What a sound asks of the chip's one hardware envelope: [shape] 0 = none (the fixed volume), 8..15 = the shape written
 * to R13 ([AyRules.SHAPE_NAMES]); its period either follows the note ([auto]: [octave] −8..7 octaves above the note's
 * own pitch, see [AyRules.autoEnvelopePeriod]) or is the fixed [period] 0..65535. Both [octave] and [period] are kept
 * while the other mode is chosen (lowering writes only the active one). [sync] (auto mode only) sets the tone period
 * to an exact multiple of the envelope's, so the buzz and the tone never beat ([AyRules.syncedTonePeriod]).
 */
@Serializable
data class AyEnvelopeSettings(
    val shape: Int = 0,
    val auto: Boolean = true,
    val octave: Int = 0,
    val period: Int = 0,
    val sync: Boolean = false,
) {
    /** True when the sound uses the hardware envelope. */
    val on: Boolean get() = shape != 0

    /** The auto octave the image holds and a note starts from: [octave] in auto mode, else 0. */
    val imageOctave: Int get() = if (auto) octave else 0

    /** The manual period the image holds and a note starts from: [period] in manual mode, else 0. */
    val imagePeriod: Int get() = if (auto) 0 else period

    /** The sync flag the image holds: [sync] in auto mode only. */
    val imageSync: Boolean get() = auto && sync

    fun problem(): String? = when {
        shape != 0 && shape !in AyRules.SHAPES -> "envelope shape $shape must be 0 (off) or 8-15"
        octave !in AyRules.OCTAVES -> "envelope octave $octave is outside ${AyRules.OCTAVES.first}..${AyRules.OCTAVES.last}"
        period !in AyRules.PERIOD -> "envelope period $period is outside 0-65535"
        else -> null
    }

    companion object {
        val OFF = AyEnvelopeSettings()
    }
}

/**
 * An AY sound: five FamiStudio-style software envelopes, one value per tick (frame interrupt) — [volume] 0..15 (empty
 * = 15; its release index is where a released note continues), [arpeggio] −64..63 semitones from the note (empty = 0),
 * [pitch] −64..63 sixteenths of a semitone (absolute per tick, or accumulated each tick when [pitchRelative]; empty =
 * 0), [mixer] 0..3 (bit 0 tone, bit 1 noise; empty = tone only) and [noise] the noise period 0..31 this sound asks for
 * (empty = none: the note does not set R6) — plus the hardware [envelope] and the drum [sweep]. The noise period and the
 * hardware envelope are shared by the three channels (docs/AUDIO_ENGINE.md, "Shared noise and envelope").
 * Envelope loop/release semantics are [NesEnvelope]'s.
 */
@Serializable
data class AyInstrument(
    val name: String = "",
    val volume: NesEnvelope = NesEnvelope(),
    val arpeggio: NesEnvelope = NesEnvelope(),
    val pitch: NesEnvelope = NesEnvelope(),
    val pitchRelative: Boolean = false,
    val mixer: NesEnvelope = NesEnvelope(),
    val noise: NesEnvelope = NesEnvelope(),
    val envelope: AyEnvelopeSettings = AyEnvelopeSettings(),
    val sweep: AySweep = AySweep(),
) {
    /** Why the instrument cannot be lowered, or null (the native loader's instrument and envelope checks). */
    fun problem(): String? {
        YmPsgInstrument.envelopeProblem("volume", volume, AyRules.VOLUME, releasable = true)?.let { return it }
        YmPsgInstrument.envelopeProblem("arpeggio", arpeggio, AyRules.ARPEGGIO, releasable = false)?.let { return it }
        YmPsgInstrument.envelopeProblem("pitch", pitch, AyRules.PITCH, releasable = false)?.let { return it }
        YmPsgInstrument.envelopeProblem("mixer", mixer, AyRules.MIXER, releasable = false)?.let { return it }
        YmPsgInstrument.envelopeProblem("noise", noise, AyRules.NOISE, releasable = false)?.let { return it }
        envelope.problem()?.let { return it }
        return sweep.problem()
    }
}

/**
 * An AY song's chip data: its [variant] (the computer; must be the project's system's) and [instruments] = exactly
 * [AySystem.SLOTS] slots (slot n = index n − 1; null = empty, not playable).
 */
@Serializable
data class AySound(
    val variant: AyVariant,
    val instruments: List<AyInstrument?>,
) : SystemSound {
    override val system: SoundSystem get() = variant.system

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): AyInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: AyInstrument?): AySound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    companion object {
        /** A new song's sound for [variant]: the presets in slots 01.. ([AyPresets.ALL]), the rest empty. */
        fun blank(variant: AyVariant): AySound = AySound(variant, List(AySystem.SLOTS) { AyPresets.ALL.getOrNull(it)?.instrument })
    }
}

/** The AY data of an AY-3-8910 / YM2149 song. */
val Song.ay: AySound get() = sound as AySound
