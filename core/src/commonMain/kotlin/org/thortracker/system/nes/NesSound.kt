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
import org.thortracker.system.ym2612.YmFmInstrument
import org.thortracker.system.ym2612.YmOperator

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

    /**
     * The EPSM (a Yamaha YMF288, OPN3-L): its SSG's three square tones (sharing one noise and one envelope generator), six
     * 4-operator FM channels and the six fixed drums of its rhythm unit (the YM2608 rhythm ROM: bass drum, snare, top
     * cymbal, hi-hat, tom, rim shot), in FamiStudio's engine order.
     */
    @SerialName("epsm") EPSM(NesChip.EPSM,
        List(3) { "SSG ${it + 1}" } + List(6) { "FM ${it + 1}" } + listOf("Kick", "Snare", "Cymbal", "Hi-hat", "Tom", "Rim"),
        List(3) { ChannelKind.PULSE } + List(6) { ChannelKind.VOICE } + List(6) { ChannelKind.SAMPLE });

    /** Channels after the 2A03's five. */
    val extraChannels: Int get() = channelNames.size

    /** All channels of a song with this expansion. */
    val channels: Int get() = NesRules.APU_CHANNELS + extraChannels

    /** The system songs with this expansion belong to. */
    val system: NesFamiStudioSystem get() = when (this) {
        NONE -> NesSystem
        VRC6 -> NesVrc6System
        EPSM -> NesEpsmSystem
    }

    /** The chips whose instruments a song with this expansion may hold. */
    val chips: List<NesChip> get() = if (chip == NesChip.APU) listOf(NesChip.APU) else listOf(NesChip.APU, chip)
}

/**
 * Which chip's channels an [NesInstrument] plays (FamiStudio's instrument expansion): the 2A03's, or an expansion's.
 * Instruments do not cross chips, as in FamiStudio: the same envelope means different things (the VRC6's 8 duty steps,
 * the EPSM's FM patch and SSG mixer). [duty] is the duty envelope's range; EPSM sounds have no duty envelope (it stays
 * empty, [hasDuty]).
 */
@Serializable
enum class NesChip(val label: String, val duty: IntRange) {
    @SerialName("2a03") APU("2A03", 0..3),
    @SerialName("vrc6") VRC6("VRC6", 0..7),
    @SerialName("epsm") EPSM("EPSM", 0..0);

    /** Whether this chip's sounds have a duty envelope (the 2A03's pulses and noise mode, the VRC6's pulses). */
    val hasDuty: Boolean get() = this == APU || this == VRC6
}

/**
 * The 5B's envelope generator shape ([S5bEnvelope.shape]; register `$0D`): [register] `$08-$0F` (the repeating and
 * one-shot shapes FamiStudio offers as `S5BEnvelopeShape` 1-8, its value = register - 7), or [OFF] (the volume
 * envelope sets the level). [picture] draws one period of the level as the NESdev wiki does.
 * The EPSM's SSG (YM2149-compatible) has the same generator and register (`EPSMSquareEnvelopeShape`).
 */
@Serializable
enum class S5bShape(val label: String, val register: Int, val picture: String) {
    @SerialName("off") OFF("Off", 0, ""),
    @SerialName("saw-down") SAW_DOWN("Saw down", 0x8, "\\\\\\\\"),
    @SerialName("decay") DECAY("Decay", 0x9, "\\___"),
    @SerialName("triangle") TRIANGLE("Triangle", 0xA, "\\/\\/"),
    @SerialName("decay-high") DECAY_HIGH("Decay, then high", 0xB, "\\¯¯¯"),
    @SerialName("saw-up") SAW_UP("Saw up", 0xC, "////"),
    @SerialName("attack") ATTACK("Attack, hold", 0xD, "/¯¯¯"),
    @SerialName("triangle-up") TRIANGLE_UP("Triangle up", 0xE, "/\\/\\"),
    @SerialName("attack-drop") ATTACK_DROP("Attack, then off", 0xF, "/___");

    /** FamiStudio's `S5BEnvelopeShape`: 0 off, 1-8 = `$08-$0F`. */
    val famiStudio: Int get() = if (this == OFF) 0 else register - 7

    /** Shapes that repeat while the note holds (saw and triangle): the envelope is then a waveform of its own. */
    val repeats: Boolean get() = this == SAW_DOWN || this == TRIANGLE || this == SAW_UP || this == TRIANGLE_UP

    /** Triangles take two ramps per cycle, so the same period sounds an octave lower than a saw. */
    val triangle: Boolean get() = this == TRIANGLE || this == TRIANGLE_UP
}

/**
 * A 5B sound's use of the chip's one envelope generator (FamiStudio's `S5BEnvelopeShape`, `S5BEnvelopeAutoPitch`,
 * `S5BEnvelopeAutoPitchOctave`, `S5BEnvelopePitch`): with a [shape] the channel's level is the generator's (the volume
 * envelope and `Cxx` no longer matter) and every note with attack restarts it. Its period, 16 CPU cycles per step and
 * 32 steps per ramp, either follows the note ([autoPitch]: the note's tone period shifted right by [octave], left for a
 * negative one, as FamiStudio's engine does) or is [period] (manual; `Ixx`/`Jxx` change it until the next note that
 * reloads a sound). The three squares share the generator: the highest-numbered square sounding such a sound sets the
 * shape and period each frame. FamiStudio's defaults: off, auto, octave 3, period 1000.
 * An EPSM sound's SSG envelope ([NesInstrument.epsmEnvelope], FamiStudio's `EPSMSquareEnvelope*`) is the same, with
 * FamiStudio's EPSM default period 4000 ([EPSM_DEFAULT]).
 */
@Serializable
data class S5bEnvelope(
    val shape: S5bShape = S5bShape.OFF,
    val autoPitch: Boolean = true,
    val octave: Int = 3,
    val period: Int = 1000,
) {
    companion object {
        val DEFAULT = S5bEnvelope()

        /** FamiStudio's EPSM SSG envelope defaults: off, auto, octave 3, manual period 4000. */
        val EPSM_DEFAULT = S5bEnvelope(period = 4000)

        /** FamiStudio's auto-period octave range. */
        val OCTAVES = -8..8

        /** The 16-bit period register (`$0B-$0C`). */
        val PERIODS = 0..0xFFFF
    }
}

/**
 * An EPSM sound's FM patch (FamiStudio's `EpsmPatchRegs`, always stored as its Custom patch 0): one 4-operator OPN
 * voice — [algorithm] 0..7, operator 1's [feedback] 0..7, the [left]/[right] outputs, the LFO depths [ams] 0..3
 * (amplitude, operators with [YmOperator.am]) and [pms] 0..7 (vibrato), four [operators] numbered as in the algorithm
 * diagrams (operator 1 has the feedback). The chip's one LFO is the song's ([NesSound.epsmLfo]). On the rhythm
 * channels only [left]/[right] matter (the drum's pan). The defaults are FamiStudio's "Default" preset.
 */
@Serializable
data class EpsmPatch(
    val algorithm: Int = 4,
    val feedback: Int = 0,
    val left: Boolean = true,
    val right: Boolean = true,
    val ams: Int = 0,
    val pms: Int = 0,
    val operators: List<YmOperator> = DEFAULT_OPERATORS,
) {
    /** True when operator [op] (0-based) is a carrier (heard; volume scales it) under the algorithm. */
    fun isCarrier(op: Int): Boolean = YmFmInstrument.carriers(algorithm) shr op and 1 != 0

    /**
     * FamiStudio's 31 `EpsmPatchRegs`: [0] `$B0` (feedback, algorithm), [1] `$B4` (L, R, AMS, PMS), then operators 1-4 each
     * `$30, $40, $50, $60, $70, $80, $90`, and [30] `$22`, the chip LFO: [lfo] (the song's, [NesSound.epsmLfo]).
     */
    fun registers(lfo: Int): List<Int> = buildList {
        add((feedback shl 3) or algorithm)
        add((if (left) 0x80 else 0) or (if (right) 0x40 else 0) or (ams shl 4) or pms)
        for (op in operators) {
            add(op.reg30); add(op.reg40); add(op.reg50); add(op.reg60); add(op.reg70); add(op.reg80); add(op.reg90)
        }
        add(lfo)
    }

    /** Why the patch cannot be played, or null. */
    fun problem(): String? {
        if (operators.size != 4) return "has ${operators.size} FM operators; EPSM patches have 4"
        if (algorithm !in 0..7) return "FM algorithm $algorithm is outside 0-7"
        if (feedback !in 0..7) return "FM feedback $feedback is outside 0-7"
        if (ams !in 0..3) return "FM AMS $ams is outside 0-3"
        if (pms !in 0..7) return "FM PMS $pms is outside 0-7"
        operators.forEachIndexed { i, op -> op.problem()?.let { return "FM operator ${i + 1}: $it" } }
        return null
    }

    companion object {
        /** Bytes of FamiStudio's `EpsmPatchRegs`. */
        const val REGS = 31

        /** FamiStudio 4.5.3's "Default" EPSM preset (`EpsmInstrumentPatch.Infos[1]`). */
        private val DEFAULT_REGS = listOf(
            0x04, 0xc0,
            0x00, 0x20, 0x1f, 0x00, 0x00, 0x07, 0x00,
            0x00, 0x00, 0x1f, 0x00, 0x00, 0x07, 0x00,
            0x00, 0x20, 0x1f, 0x00, 0x00, 0x07, 0x00,
            0x00, 0x00, 0x1f, 0x00, 0x00, 0x07, 0x00,
            0x00,
        )

        private val DEFAULT_OPERATORS: List<YmOperator> = List(4) { op ->
            val r = DEFAULT_REGS.subList(2 + 7 * op, 9 + 7 * op)
            YmOperator.ofRegisters(r[0], r[1], r[2], r[3], r[4], r[5], r[6])
        }

        /** A patch from FamiStudio's 31 `EpsmPatchRegs` (reserved bits ignored; [30], the LFO, is the song's). */
        fun ofRegisters(regs: List<Int>): EpsmPatch {
            require(regs.size >= REGS - 1) { "EPSM patches have $REGS registers, not ${regs.size}" }
            return EpsmPatch(
                algorithm = regs[0] and 7, feedback = regs[0] shr 3 and 7,
                left = regs[1] and 0x80 != 0, right = regs[1] and 0x40 != 0, ams = regs[1] shr 4 and 3, pms = regs[1] and 7,
                operators = List(4) { op ->
                    val r = regs.subList(2 + 7 * op, 9 + 7 * op)
                    YmOperator.ofRegisters(r[0], r[1], r[2], r[3], r[4], r[5], r[6])
                },
            )
        }

        val DEFAULT = EpsmPatch()
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

        /**
         * EPSM SSG mixer per frame (FamiStudio's `S5BMixer`): 0 tone and noise (the square gated by the noise), 1 noise
         * only, 2 tone only (the default of an empty envelope), 3 neither (the channel holds its level: a constant).
         */
        val MIXER = 0..3

        /** EPSM SSG noise generator period per frame (FamiStudio's `S5BNoiseFreq`): 0-31, higher is lower. */
        val NOISE = 0..31
    }
}

/**
 * A FamiStudio instrument: four envelopes. [arpeggio] offsets the note in semitones, [pitch] in note-table
 * period units (absolute per frame, or accumulated each frame when [pitchRelative]), [duty] sets the pulse duty or
 * the noise mode. The triangle ignores [duty] and plays any [volume] above 0 at full level.
 * On the DPCM channel only [dpcm] matters: which sample each note plays (FamiStudio 4.5.3 "DPCM instrument" mappings,
 * keyed by absolute note); a DPCM note the instrument does not map is an error.
 * [chip] says which channels play it ([NesChip.APU]: the 2A03's; [NesChip.VRC6]: the VRC6 pulses, duty 0-7 = 1/16 to
 * 8/16, and the saw, whose volume [sawVolume] scales; [NesChip.EPSM]: every EPSM channel, no duty — the FM channels
 * play the FM patch [epsm] shaped by the volume, arpeggio and pitch envelopes; the SSG squares the volume, arpeggio and
 * pitch envelopes plus a [mixer] envelope (per frame 0 tone + noise, 1 noise, 2 tone, 3 neither; empty = tone), a
 * [noise] envelope (the shared noise generator's period 0-31 per frame) and the hardware envelope [epsmEnvelope]; the
 * rhythm drums only the volume envelope's first value (empty = 15) and [epsm]'s left/right pan). These are left out of
 * project files at their defaults, so 2A03 instruments are stored as before.
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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mixer: NesEnvelope = NesEnvelope(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val noise: NesEnvelope = NesEnvelope(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val epsmEnvelope: S5bEnvelope = S5bEnvelope.EPSM_DEFAULT,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val epsm: EpsmPatch = EpsmPatch.DEFAULT,
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
 * [epsmLfo] is the EPSM's one LFO register `$22` (0 = off; bit 3 on, bits 0-2 the rate), shared by all FM channels:
 * a song setting, written with every FM sound the engine loads (left out of project files at 0).
 * Stored in project files as the song's `sound` object.
 */
@Serializable
data class NesSound(
    val instruments: List<NesInstrument>,
    val samples: List<DpcmSample> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expansion: NesExpansion = NesExpansion.NONE,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val epsmLfo: Int = 0,
) : SystemSound {
    override val system: SoundSystem get() = expansion.system

    companion object {
        /** `$22` with the LFO on: bit 3, plus the rate 0-7 in bits 0-2. */
        const val EPSM_LFO_ON = 0x08

        fun blank(expansion: NesExpansion = NesExpansion.NONE): NesSound =
            NesSound(List(NesSystem.SLOTS) { i -> NesInstrument(name = "Sound ${NumberText.hex(i + 1, 2)}") }, expansion = expansion)
    }
}

/** This NES song's chip data. Only NES code calls it, on NES songs. */
val Song.nes: NesSound get() = sound as NesSound
