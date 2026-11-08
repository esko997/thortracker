@file:OptIn(ExperimentalSerializationApi::class)

package org.thortracker.system.nes

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/**
 * The sound chip an NES song adds to the 2A03 (FamiStudio's project expansion): one per song and per [NesSystem]
 * variant, because FamiStudio builds its engine for one expansion and each variant's channels are fixed. See
 * docs/BACKENDS.md, "NES expansion chips".
 */
@Serializable
enum class NesExpansion(
    /** The chip whose instruments the expansion's channels play. */
    val chip: NesChip,
    /** Channels after the 2A03's five, in FamiStudio's engine order. */
    val extraChannels: Int,
) {
    @SerialName("none") NONE(NesChip.APU, 0),
    @SerialName("vrc6") VRC6(NesChip.VRC6, 3);

    /** All channels of a song with this expansion. */
    val channels: Int get() = NesRules.APU_CHANNELS + extraChannels

    /** The system songs with this expansion belong to. */
    val system: NesFamiStudioSystem get() = when (this) {
        NONE -> NesSystem
        VRC6 -> NesVrc6System
    }

    /** The chips whose instruments a song with this expansion may hold. */
    val chips: List<NesChip> get() = if (chip == NesChip.APU) listOf(NesChip.APU) else listOf(NesChip.APU, chip)
}

/**
 * Which chip's channels an [NesInstrument] plays (FamiStudio's instrument expansion): the 2A03's, or an expansion's.
 * Instruments do not cross chips, as in FamiStudio: the same envelope means different things (the VRC6's 8 duty steps).
 */
@Serializable
enum class NesChip(val label: String, val duty: IntRange) {
    @SerialName("2a03") APU("2A03", 0..3),
    @SerialName("vrc6") VRC6("VRC6", 0..7),
}

/**
 * The VRC6 saw's master volume (FamiStudio's `Vrc6SawMasterVolume`): the volume envelope's 0-15, times the volume
 * track, is shifted into the saw's 6-bit accumulator rate by [shift]. Rates above 42 overflow the accumulator and
 * distort, so [FULL] distorts above volume 10. The engine's opcode `$55` takes [engine].
 */
@Serializable
enum class Vrc6SawVolume(val label: String, val shift: Int, val engine: Int) {
    @SerialName("full") FULL("Full", 2, 0x01),
    @SerialName("half") HALF("Half", 1, 0x00),
    @SerialName("quarter") QUARTER("Quarter", 0, 0xFF),
}

/**
 * One FamiStudio instrument envelope: a value per engine frame (60.1 Hz). Playback runs through the values, then
 * jumps back to [loop] (a value index) or holds the last value when [loop] is -1. [release] (volume only) is where
 * a released note (OFF) continues: the loop then runs from [loop] to just before [release]. Empty = the envelope is
 * not used (FamiStudio's defaults: volume 15, no arpeggio, no pitch offset, duty untouched).
 * Ranges and limits are FamiStudio 4.5.3's (`Envelope.cs`); see [NesSystem] for the checks.
 */
@Serializable
data class NesEnvelope(val values: List<Int> = emptyList(), val loop: Int = -1, val release: Int = -1) {
    companion object {
        /** FamiStudio's longest envelope. */
        const val MAX_LENGTH = 256
        val VOLUME = 0..15
        val ARPEGGIO = -64..63
        val PITCH = -64..63

        /** 2A03: 12.5%, 25%, 50%, 75% on the pulses; on Noise 0 = long (hiss), 1 = short (metallic) LFSR. VRC6: [NesChip.duty]. */
        val DUTY = 0..3
    }
}

/**
 * A FamiStudio instrument: four envelopes. [arpeggio] offsets the note in semitones, [pitch] in note-table
 * period units (absolute per frame, or accumulated each frame when [pitchRelative]), [duty] sets the pulse duty or
 * the noise mode. The triangle ignores [duty] and plays any [volume] above 0 at full level.
 * On the DPCM channel only [dpcm] matters: which sample each note plays (FamiStudio 4.5.3 "DPCM instrument" mappings,
 * keyed by absolute note); a DPCM note the instrument does not map is an error.
 * [chip] says which channels play it ([NesChip.APU]: the 2A03's; [NesChip.VRC6]: the VRC6 pulses, duty 0-7 = 1/16 to
 * 8/16, and the saw, whose volume [sawVolume] scales). Both are left out of project files at their defaults, so 2A03
 * instruments are stored as before.
 */
@Serializable
data class NesInstrument(
    val name: String = "Sound",
    val volume: NesEnvelope = NesEnvelope(listOf(15)),
    val arpeggio: NesEnvelope = NesEnvelope(),
    val pitch: NesEnvelope = NesEnvelope(),
    val pitchRelative: Boolean = false,
    val duty: NesEnvelope = NesEnvelope(),
    val dpcm: Map<Int, DpcmMapping> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chip: NesChip = NesChip.APU,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sawVolume: Vrc6SawVolume = Vrc6SawVolume.HALF,
) {
    companion object {
        /** A new slot: flat full volume, nothing else. */
        val DEFAULT = NesInstrument()
    }
}

/**
 * One DPCM sample: the bytes the 2A03's delta modulation channel reads, as the hardware plays them (each bit moves
 * the output level up or down by 2, least significant bit first), [data].size = 16n + 1 bytes (1..[MAX_BYTES]).
 * [dmcInitial] is FamiStudio's "DMC initial value ÷ 2" (0..63): a note starting this sample first sets the output
 * level ($4011) to twice it, unless its mapping overrides it ([DpcmMapping.dmcInitial]) or `Zxx` set the level.
 */
@Serializable
data class DpcmSample(val name: String, val data: List<Int>, val dmcInitial: Int = DEFAULT_DMC_INITIAL) {
    companion object {
        /** FamiStudio's `NesApu.DACDefaultValueDiv2`: level 64, the middle of the 7-bit range. */
        const val DEFAULT_DMC_INITIAL = 32
        val DMC_INITIAL = 0..63

        /** The longest sample the hardware plays: length register $FF → 255 × 16 + 1 bytes. */
        const val MAX_BYTES = 255 * 16 + 1

        /** FamiStudio's sample name limit. */
        const val MAX_NAME = 16
    }
}

/**
 * What a note plays on the DPCM channel (FamiStudio's `DPCMSampleMapping`): [sample] indexes [NesSound.samples],
 * [pitch] is the DMC rate 0..15 (15 = 33.1 kHz, the rate samples are usually encoded for), [loop] restarts the
 * sample until the next note or stop, [dmcInitial] -1 = the sample's own initial value, else an override 0..63
 * (÷ 2, as [DpcmSample.dmcInitial]).
 */
@Serializable
data class DpcmMapping(val sample: Int, val pitch: Int = 15, val loop: Boolean = false, val dmcInitial: Int = -1) {
    companion object {
        val PITCH = 0..15
    }
}

/**
 * An NES song's chip data: one bank of exactly [NesSystem.SLOTS] instruments shared by all channels (slot n =
 * index n-1), the song's DPCM [samples] (at most [NesSystem.MAX_SAMPLES], referenced by instruments' DPCM
 * mappings) and its [expansion] chip (left out of project files when none, so 2A03 songs are stored as before).
 * Stored in project files as the song's `sound` object.
 */
@Serializable
data class NesSound(
    val instruments: List<NesInstrument>,
    val samples: List<DpcmSample> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expansion: NesExpansion = NesExpansion.NONE,
) : SystemSound {
    override val system: SoundSystem get() = expansion.system

    companion object {
        fun blank(expansion: NesExpansion = NesExpansion.NONE): NesSound =
            NesSound(List(NesSystem.SLOTS) { i -> NesInstrument(name = "Sound ${NumberText.hex(i + 1, 2)}") }, expansion = expansion)
    }
}

/** This NES song's chip data. Only NES code calls it, on NES songs. */
val Song.nes: NesSound get() = sound as NesSound
