@file:OptIn(ExperimentalSerializationApi::class)

package org.thortracker.system.nes

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.ChannelKind
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
    /** The channels after the 2A03's five, in FamiStudio's engine order: their names and kinds. */
    val channelNames: List<String>,
    val channelKinds: List<ChannelKind>,
) {
    @SerialName("none") NONE(NesChip.APU, emptyList(), emptyList()),
    @SerialName("vrc6") VRC6(NesChip.VRC6, listOf("VRC6 Pulse 1", "VRC6 Pulse 2", "VRC6 Saw"),
        listOf(ChannelKind.PULSE, ChannelKind.PULSE, ChannelKind.SAW)),
    @SerialName("vrc7") VRC7(NesChip.VRC7, List(6) { "VRC7 FM ${it + 1}" }, List(6) { ChannelKind.VOICE }),

    /**
     * The Namco 163 with 1..8 wavetable channels enabled (FamiStudio's `NumN163Channels`; the chip's own register):
     * more channels share the chip's update time (each is updated once every 15 × n CPU cycles, so the note table and
     * the reachable range change) and its 128 bytes of RAM (the channel registers take 8 bytes each, the rest holds
     * waves), so every count is its own variant and engine build.
     */
    @SerialName("n163-1") N163_1(NesChip.N163, n163Names(1), n163Kinds(1)),
    @SerialName("n163-2") N163_2(NesChip.N163, n163Names(2), n163Kinds(2)),
    @SerialName("n163-3") N163_3(NesChip.N163, n163Names(3), n163Kinds(3)),
    @SerialName("n163-4") N163_4(NesChip.N163, n163Names(4), n163Kinds(4)),
    @SerialName("n163-5") N163_5(NesChip.N163, n163Names(5), n163Kinds(5)),
    @SerialName("n163-6") N163_6(NesChip.N163, n163Names(6), n163Kinds(6)),
    @SerialName("n163-7") N163_7(NesChip.N163, n163Names(7), n163Kinds(7)),
    @SerialName("n163-8") N163_8(NesChip.N163, n163Names(8), n163Kinds(8));

    /** Channels after the 2A03's five. */
    val extraChannels: Int get() = channelNames.size

    /** All channels of a song with this expansion. */
    val channels: Int get() = NesRules.APU_CHANNELS + extraChannels

    /** The N163 channels a song with this expansion enables (1..8), 0 without the N163. */
    val n163Channels: Int get() = if (chip == NesChip.N163) extraChannels else 0

    /** The system songs with this expansion belong to. */
    val system: NesFamiStudioSystem get() = when (this) {
        NONE -> NesSystem
        VRC6 -> NesVrc6System
        VRC7 -> NesVrc7System
        else -> NesN163System.of(n163Channels)
    }

    /** The chips whose instruments a song with this expansion may hold. */
    val chips: List<NesChip> get() = if (chip == NesChip.APU) listOf(NesChip.APU) else listOf(NesChip.APU, chip)

    companion object {
        /** The N163 expansion with [channels] (1..8) channels enabled. */
        fun n163(channels: Int): NesExpansion {
            require(channels in 1..NesRules.N163_MAX_CHANNELS) { "The N163 enables 1-8 channels, not $channels" }
            return entries.first { it.n163Channels == channels }
        }
    }
}

private fun n163Names(n: Int): List<String> = List(n) { "N163 ${it + 1}" }
private fun n163Kinds(n: Int): List<ChannelKind> = List(n) { ChannelKind.WAVE }

/**
 * Which chip's channels an [NesInstrument] plays (FamiStudio's instrument expansion): the 2A03's, or an expansion's.
 * Instruments do not cross chips, as in FamiStudio: the same envelope means different things (the VRC6's 8 duty steps,
 * the VRC7's FM patch, the N163's waves). [duty] is the duty envelope's range; VRC7 and N163 sounds have no duty
 * envelope (it stays empty).
 */
@Serializable
enum class NesChip(val label: String, val duty: IntRange) {
    @SerialName("2a03") APU("2A03", 0..3),
    @SerialName("vrc6") VRC6("VRC6", 0..7),
    @SerialName("vrc7") VRC7("VRC7", 0..0),
    @SerialName("n163") N163("N163", 0..0),
}

/**
 * A VRC7 sound's FM patch (FamiStudio's `Vrc7Patch`, `Vrc7PatchRegs`, `Vrc7OverrideRelease`, `Vrc7OverrideStop`). The
 * VRC7 (a YM2413 derivative: six 2-operator FM channels) holds 15 patches in ROM ([preset] 1-15, [Vrc7Presets]) and
 * one custom patch in its registers `$00-$07` ([preset] 0: [regs]), shared by every channel that plays preset 0, so
 * two different custom sounds cannot sound at once. [regs] is kept for presets too (a copy of the preset's ROM data, as
 * FamiStudio keeps it), so switching a preset to Custom starts from that sound.
 * [sustainRelease]: notes keep the chip's sustain flag, so OFF fades at the sustain rate (release rate 5) instead of
 * the patch's own release. [sustainStop]: a stop or cut sets the sustain flag, so the note fades at that rate instead
 * of ending at the patch's release. FamiStudio's engine keeps the two flags of the last VRC7 sound loaded for all six
 * channels.
 */
@Serializable
data class Vrc7Patch(
    val preset: Int = Vrc7Presets.BELL,
    val regs: List<Int> = Vrc7Presets.data(Vrc7Presets.BELL),
    val sustainRelease: Boolean = false,
    val sustainStop: Boolean = false,
) {
    /** The custom patch's registers as the engine loads them; for presets the ROM's (what plays). */
    val sounding: List<Int> get() = if (preset == Vrc7Presets.CUSTOM) regs else Vrc7Presets.data(preset)

    /** This patch on [preset]: a preset copies its ROM data into [regs]; Custom keeps them (starts from the last sound). */
    fun withPreset(preset: Int): Vrc7Patch = when (preset) {
        Vrc7Presets.CUSTOM -> copy(preset = preset)
        in Vrc7Presets.PRESETS -> copy(preset = preset, regs = Vrc7Presets.data(preset))
        else -> this
    }

    companion object {
        val DEFAULT = Vrc7Patch()
        const val REGS = 8
    }
}

/**
 * The VRC7's 15 ROM patches (FamiStudio 4.5.3's `Vrc7InstrumentPatch`, the VRC7 dump by Nuke.YKT that emu2413's
 * `OPLL_VRC7_TONE` set also holds) as 8 register bytes each, and their names.
 */
object Vrc7Presets {
    const val CUSTOM = 0
    const val BELL = 1
    val PRESETS = 1..15

    val NAMES = listOf("Custom", "Bell", "Guitar", "Piano", "Flute", "Clarinet", "Rattling bell", "Trumpet", "Reed organ",
        "Soft bell", "Xylophone", "Vibraphone", "Brass", "Bass guitar", "Synthesizer", "Chorus")

    private val DATA = listOf(
        intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        intArrayOf(0x03, 0x21, 0x05, 0x06, 0xe8, 0x81, 0x42, 0x27),
        intArrayOf(0x13, 0x41, 0x14, 0x0d, 0xd8, 0xf6, 0x23, 0x12),
        intArrayOf(0x11, 0x11, 0x08, 0x08, 0xfa, 0xb2, 0x20, 0x12),
        intArrayOf(0x31, 0x61, 0x0c, 0x07, 0xa8, 0x64, 0x61, 0x27),
        intArrayOf(0x32, 0x21, 0x1e, 0x06, 0xe1, 0x76, 0x01, 0x28),
        intArrayOf(0x02, 0x01, 0x06, 0x00, 0xa3, 0xe2, 0xf4, 0xf4),
        intArrayOf(0x21, 0x61, 0x1d, 0x07, 0x82, 0x81, 0x11, 0x07),
        intArrayOf(0x23, 0x21, 0x22, 0x17, 0xa2, 0x72, 0x01, 0x17),
        intArrayOf(0x35, 0x11, 0x25, 0x00, 0x40, 0x73, 0x72, 0x01),
        intArrayOf(0xb5, 0x01, 0x0f, 0x0f, 0xa8, 0xa5, 0x51, 0x02),
        intArrayOf(0x17, 0xc1, 0x24, 0x07, 0xf8, 0xf8, 0x22, 0x12),
        intArrayOf(0x71, 0x23, 0x11, 0x06, 0x65, 0x74, 0x18, 0x16),
        intArrayOf(0x01, 0x02, 0xd3, 0x05, 0xc9, 0x95, 0x03, 0x02),
        intArrayOf(0x61, 0x63, 0x0c, 0x00, 0x94, 0xc0, 0x33, 0xf6),
        intArrayOf(0x21, 0x72, 0x0d, 0x00, 0xc1, 0xd5, 0x56, 0x06),
    ).map { it.toList() }

    /** Patch [preset]'s 8 register bytes (Custom: zeros). */
    fun data(preset: Int): List<Int> = DATA[preset]
}

/**
 * One field of the VRC7's (YM2413's) 8 patch registers, per operator where it has one (0 = modulator, 1 = carrier):
 * `$00/$01` AM, VIB, EG (sustained), KSR, MULT; `$02` modulator KSL and TL (modulation level); `$03` carrier KSL, the
 * carrier (DC) and modulator (DM) half-sine bits and FB (feedback); `$04/$05` AR, DR; `$06/$07` SL, RR. [max] is the
 * field's largest value; [modulatorOnly] fields have no carrier column.
 */
enum class Vrc7Param(val label: String, val max: Int, val modulatorOnly: Boolean = false) {
    MULT("Multiplier", 15),
    WAVE("Half-sine", 1),
    LEVEL("Mod. level", 63, modulatorOnly = true),
    FEEDBACK("Feedback", 7, modulatorOnly = true),
    AR("Attack", 15),
    DR("Decay", 15),
    SL("Sustain level", 15),
    RR("Release", 15),
    EG("Sustained", 1),
    KSL("Key scale level", 3),
    KSR("Key scale rate", 1),
    AM("Tremolo", 1),
    VIB("Vibrato", 1);

    /** (register, shift, width) of this field for operator [op]. */
    private fun at(op: Int): Triple<Int, Int, Int> = when (this) {
        MULT -> Triple(op, 0, 4)
        KSR -> Triple(op, 4, 1)
        EG -> Triple(op, 5, 1)
        VIB -> Triple(op, 6, 1)
        AM -> Triple(op, 7, 1)
        KSL -> Triple(2 + op, 6, 2)
        LEVEL -> Triple(2, 0, 6)
        WAVE -> Triple(3, if (op == 0) 3 else 4, 1)
        FEEDBACK -> Triple(3, 0, 3)
        AR -> Triple(4 + op, 4, 4)
        DR -> Triple(4 + op, 0, 4)
        SL -> Triple(6 + op, 4, 4)
        RR -> Triple(6 + op, 0, 4)
    }

    /** The field's value for operator [op] in the 8 patch registers [regs]. */
    fun get(regs: List<Int>, op: Int): Int {
        val (reg, shift, width) = at(if (modulatorOnly) 0 else op)
        return (regs[reg] shr shift) and ((1 shl width) - 1)
    }

    /** [regs] with the field of operator [op] set to [value] (clamped to 0..[max]). */
    fun set(regs: List<Int>, op: Int, value: Int): List<Int> {
        val (reg, shift, width) = at(if (modulatorOnly) 0 else op)
        val mask = ((1 shl width) - 1) shl shift
        val v = (value.coerceIn(0, max) shl shift) and mask
        return regs.mapIndexed { i, r -> if (i == reg) (r and mask.inv()) or v else r }
    }
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
 * An N163 sound's waves (FamiStudio 4.5.3's `N163WaveSize`, `N163WavePos`, `N163WaveCount`, the `N163Waveform`
 * envelope and its `WaveformRepeat` envelope). The Namco 163 plays 4-bit samples (0..15, 8 = silence) from its 128 bytes
 * of RAM, two samples per byte (the even sample in the low nibble): a channel plays [size] samples starting at sample
 * [position], a multiple of 4 and of 2 (FamiStudio uploads whole bytes). [waves] is a sequence of 1..[maxWaves] waves of
 * [size] samples each: wave i plays for [repeat]\[i] frames (1..15), then the next; after the last the sequence goes back
 * to [loop] (-1: the last wave holds); a released note (OFF) continues at [release] (needs a loop, loop < release:
 * the loop then runs from [loop] to just before [release], as FamiStudio's volume envelopes do). FamiStudio's engine
 * uploads a wave to the RAM at [position] whenever the sequence moves to another wave, so channels playing sounds whose
 * RAM ranges overlap overwrite each other's waves (the check warns); identical waves at the same place share safely.
 * The note tables are tuned for 16-sample waves: a [size] of 32 sounds an octave lower, 8 an octave higher, other sizes
 * by 12 log2(16 / [size]) semitones ([NesRules.n163Transpose]).
 */
@Serializable
data class N163Wave(
    val size: Int = 16,
    val position: Int = 0,
    val waves: List<List<Int>> = listOf(N163Presets.sine(16)),
    val repeat: List<Int> = listOf(1),
    val loop: Int = -1,
    val release: Int = -1,
) {
    /** The RAM samples the waves occupy: [position] until [position] + [size] (exclusive). */
    val ram: IntRange get() = position until position + size

    companion object {
        /** A new N163 sound: one 16-sample sine at the start of the RAM, as FamiStudio's default instrument. */
        val DEFAULT = N163Wave()
        const val MIN_SIZE = 4
        val SAMPLE = 0..15
        val REPEAT = 1..15

        /** FamiStudio keeps at most 1024 samples of waves per instrument and 64 waves. */
        const val MAX_SAMPLES = 1024
        const val MAX_WAVES = 64

        /** The most waves of [size] samples one sound holds. */
        fun maxWaves(size: Int): Int = minOf(MAX_WAVES, MAX_SAMPLES / maxOf(MIN_SIZE, size))
    }
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
 * 8/16, and the saw, whose volume [sawVolume] scales; [NesChip.VRC7]: the six FM channels, which play the FM patch [fm]
 * shaped by the volume, arpeggio and pitch envelopes, no duty; [NesChip.N163]: the N163's channels, which play the
 * waves [wave] shaped by the volume, arpeggio and pitch envelopes, no duty). These are left out of project files at
 * their defaults, so 2A03 instruments are stored as before.
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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fm: Vrc7Patch = Vrc7Patch.DEFAULT,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val wave: N163Wave = N163Wave.DEFAULT,
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
