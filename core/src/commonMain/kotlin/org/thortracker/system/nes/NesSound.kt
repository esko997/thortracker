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
    @SerialName("vrc7") VRC7(NesChip.VRC7, List(6) { "VRC7 FM ${it + 1}" }, List(6) { ChannelKind.VOICE }),

    /** The Famicom Disk System's 2C33: one wavetable channel with a modulation unit. */
    @SerialName("fds") FDS(NesChip.FDS, listOf("FDS"), listOf(ChannelKind.WAVE)),

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
    @SerialName("n163-8") N163_8(NesChip.N163, n163Names(8), n163Kinds(8)),

    /** The Sunsoft 5B (a YM2149F): three square tone channels sharing one noise and one envelope generator. */
    @SerialName("s5b") S5B(NesChip.S5B, List(3) { "5B Square ${it + 1}" }, List(3) { ChannelKind.PULSE }),

    /**
     * The EPSM (a Yamaha YMF288, OPN3-L): its SSG's three square tones (sharing one noise and one envelope generator, as
     * the 5B's), six 4-operator FM channels and the six fixed drums of its rhythm unit (the YM2608 rhythm ROM: bass drum,
     * snare, top cymbal, hi-hat, tom, rim shot), in FamiStudio's engine order.
     */
    @SerialName("epsm") EPSM(NesChip.EPSM,
        List(3) { "SSG ${it + 1}" } + List(6) { "FM ${it + 1}" } + listOf("Kick", "Snare", "Cymbal", "Hi-hat", "Tom", "Rim"),
        List(3) { ChannelKind.PULSE } + List(6) { ChannelKind.VOICE } + List(6) { ChannelKind.SAMPLE });

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
        FDS -> NesFdsSystem
        S5B -> NesS5bSystem
        EPSM -> NesEpsmSystem
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
 * the VRC7's FM patch, the N163's and the FDS's waves, the 5B's and the EPSM's SSG mixer, the EPSM's FM patch). [duty] is
 * the duty envelope's range; VRC7, N163, FDS, 5B and EPSM sounds have no duty envelope (it stays empty, [hasDuty]).
 */
@Serializable
enum class NesChip(val label: String, val duty: IntRange) {
    @SerialName("2a03") APU("2A03", 0..3),
    @SerialName("vrc6") VRC6("VRC6", 0..7),
    @SerialName("vrc7") VRC7("VRC7", 0..0),
    @SerialName("n163") N163("N163", 0..0),
    @SerialName("fds") FDS("FDS", 0..0),
    @SerialName("s5b") S5B("5B", 0..0),
    @SerialName("epsm") EPSM("EPSM", 0..0);

    /** Whether this chip's sounds have a duty envelope (the 2A03's pulses and noise mode, the VRC6's pulses). */
    val hasDuty: Boolean get() = this == APU || this == VRC6

    /**
     * Whether this chip's sounds play a YM2149-style SSG square (the 5B's squares, the EPSM's SSG 1-3): they then have
     * mixer and noise envelopes and a hardware envelope ([NesInstrument.s5b]).
     */
    val hasSsg: Boolean get() = this == S5B || this == EPSM
}

/**
 * The 5B's envelope generator shape ([S5bEnvelope.shape]; register `$0D`): [register] `$08-$0F` (the repeating and
 * one-shot shapes FamiStudio offers as `S5BEnvelopeShape` 1-8, its value = register - 7), or [OFF] (the volume
 * envelope sets the level). [picture] draws one period of the level as the NESdev wiki does.
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
 * A 5B or EPSM SSG sound's use of the chip's one envelope generator (FamiStudio's `S5BEnvelopeShape`,
 * `S5BEnvelopeAutoPitch`, `S5BEnvelopeAutoPitchOctave`, `S5BEnvelopePitch`; on the EPSM its `EPSMSquareEnvelope*`,
 * the same YM2149 generator): with a [shape] the channel's level is the generator's (the volume envelope and `Cxx` no
 * longer matter) and every note with attack restarts it. Its period, 16 tone-clock cycles per step and 32 steps per ramp,
 * either follows the note ([autoPitch]: the note's tone period shifted right by [octave], left for a negative one, as
 * FamiStudio's engine does) or is [period] (manual; `Ixx`/`Jxx` change it until the next note that reloads a sound).
 * The three squares share the generator: the highest-numbered square sounding such a sound sets the shape and period
 * each frame. FamiStudio's defaults: off, auto, octave 3, period 1000 (the EPSM's: period 4000, [EPSM_DEFAULT]).
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

        /** The default envelope of a [chip] sound (the EPSM's manual period is 4000, every other chip's 1000). */
        fun defaultFor(chip: NesChip): S5bEnvelope = if (chip == NesChip.EPSM) EPSM_DEFAULT else DEFAULT

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
 * A VRC7 sound's FM patch (FamiStudio's `Vrc7Patch` and `Vrc7PatchRegs`). The VRC7 (a YM2413 derivative: six
 * 2-operator FM channels) holds 15 patches in ROM ([preset] 1-15, [Vrc7Presets]) and one custom patch in its registers
 * `$00-$07` ([preset] 0: [regs]), shared by every channel that plays preset 0, so two different custom sounds cannot
 * sound at once. [regs] is kept for presets too (a copy of the preset's ROM data, as FamiStudio keeps it), so switching
 * a preset to Custom starts from that sound. The release/stop sustain overrides are the song's ([Vrc7Sustain]).
 */
@Serializable
data class Vrc7Patch(
    val preset: Int = Vrc7Presets.BELL,
    val regs: List<Int> = Vrc7Presets.data(Vrc7Presets.BELL),
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
 * How the VRC7's FM notes fade when they end (FamiStudio's `Vrc7OverrideRelease` / `Vrc7OverrideStop`, instrument
 * settings there). FamiStudio's sound engine keeps one copy of them (`famistudio_chn_vrc7_sustain`, taken from the
 * last VRC7 instrument loaded on any FM channel), so a song has one setting that every VRC7 sound exports with.
 * [release]: notes keep the chip's sustain bit, so OFF (key off) fades at the sustain rate (5) instead of the patch's
 * release rate (sustained patches: RR; percussive ones: 7). [stop]: a stop or cut keys off with the sustain bit set,
 * so the note fades at rate 5 instead of the patch's release.
 */
@Serializable
data class Vrc7Sustain(val release: Boolean = false, val stop: Boolean = false) {
    /** Byte 6 of FamiStudio's VRC7 instrument (`OutputInstruments`): `$20` release override, `$80` stop override. */
    val engine: Int get() = (if (release) 0x20 else 0) or (if (stop) 0x80 else 0)
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
 * The FDS's master volume (`$4089` bits 0-1, FamiStudio's `FdsMasterVolume`): the whole channel's output × 2 / (2 +
 * [register]), NESdev "FDS audio". FamiStudio's engine writes it with each wave upload.
 */
@Serializable
enum class FdsMasterVolume(val label: String, val register: Int) {
    @SerialName("100") FULL("100%", 0),
    @SerialName("66") TWO_THIRDS("66%", 1),
    @SerialName("50") HALF("50%", 2),
    @SerialName("40") TWO_FIFTHS("40%", 3),
}

/**
 * An FDS sound (FamiStudio 4.5.3's FDS instrument: the `FdsWaveform`, `WaveformRepeat` and `FdsModulation` envelopes,
 * `FdsMasterVolume`, `FdsModSpeed`, `FdsModDepth`, `FdsModDelay`, `FdsAutoMod`, `FdsAutoModNumer`, `FdsAutoModDenom`).
 *
 * The 2C33 plays a 64-step wave of 6-bit samples ([SAMPLE]) from its wave RAM (`$4040-$407F`). [waves] is a sequence of
 * 1..[MAX_WAVES] waves of [WAVE_SIZE] samples: wave i plays for [repeat]\[i] frames (1..15), then the next; after the last
 * the sequence goes back to [loop] (-1: the last wave holds); a released note (OFF) continues at [release] (needs a loop,
 * loop < release), as an N163 sound's. FamiStudio's engine uploads a wave whenever the sequence moves to another one.
 *
 * The modulation unit bends the wave's frequency by a 7-bit counter × the mod depth: it steps through a 32-entry table
 * at the mod speed ([modSpeed], 12-bit `$4086/$4087`; the table runs once per 2^22 / speed CPU cycles, so speed = the
 * note's frequency value is FM at the note's pitch), each entry changing the counter by 0, ±1, ±2, ±4 or resetting it.
 * [modulation] is FamiStudio's model of that table: 32 targets −64..63 for the counter, encoded into the chip's 3-bit
 * steps with FamiStudio's greedy `BuildFdsModulationTable` ([modTable]; [modCurve] is what the chip then plays).
 * [modDepth] (0..63, `$4084`) scales the bend; 0 turns modulation off. [modDelay] frames after a note's attack the
 * modulation starts (the engine keeps it reset before). With [autoMod] the engine sets the mod speed every frame to the
 * note's frequency × [autoModNumer] / [autoModDenom] (1..32 each; FamiStudio reduces the fraction): FM that follows the
 * pitch; [modSpeed] is then unused.
 */
@Serializable
data class FdsSound(
    val waves: List<List<Int>> = listOf(FdsPresets.SINE),
    val repeat: List<Int> = listOf(1),
    val loop: Int = -1,
    val release: Int = -1,
    val masterVolume: FdsMasterVolume = FdsMasterVolume.FULL,
    val modulation: List<Int> = FdsPresets.FLAT_MOD,
    val modSpeed: Int = 0,
    val modDepth: Int = 0,
    val modDelay: Int = 0,
    val autoMod: Boolean = false,
    val autoModNumer: Int = 1,
    val autoModDenom: Int = 1,
) {
    /** The 32 bytes FamiStudio's engine writes to `$4088`: [modulation] encoded by [encodeModulation]. */
    val modTable: List<Int> get() = encodeModulation(modulation)

    /** The mod counter after each table entry as the chip applies it (twice per entry), from 0: what [modTable] plays. */
    val modCurve: List<Int> get() = modTableCurve(modTable)

    /** [autoModNumer] / [autoModDenom] reduced, as FamiStudio's export writes them (`Utils.SimplifyFraction`). */
    val autoModFraction: Pair<Int, Int> get() {
        var a = autoModNumer
        var b = autoModDenom
        while (b != 0) { val t = a % b; a = b; b = t }
        return autoModNumer / a to autoModDenom / a
    }

    companion object {
        /** A new FDS sound: one sine wave at full master volume, no modulation, as FamiStudio's default instrument. */
        val DEFAULT = FdsSound()
        const val WAVE_SIZE = 64
        val SAMPLE = 0..63
        val REPEAT = 1..15

        /** FamiStudio keeps at most 16 waves per FDS instrument (1024 samples). */
        const val MAX_WAVES = 16
        const val MOD_SIZE = 32

        /** FamiStudio's targets for the mod counter (the chip's 7-bit signed counter). */
        val MOD_TARGET = -64..63
        val MOD_SPEED = 0..4095
        val MOD_DEPTH = 0..63
        val MOD_DELAY = 0..255
        val AUTO_MOD = 1..32

        /** The counter step of each 3-bit table value (4 resets the counter to 0), NESdev "FDS audio". */
        val MOD_STEPS = listOf(0, 1, 2, 4, 0, -4, -2, -1)
        const val MOD_RESET = 4

        /**
         * FamiStudio 4.5.3's `Envelope.BuildFdsModulationTable`: entry 0 resets the counter; then for each target, 0
         * resets, anything else takes the step whose doubled value lands nearest (first of equals, staying in −64..63),
         * from the counter the previous entries reached.
         */
        fun encodeModulation(targets: List<Int>): List<Int> {
            val out = IntArray(targets.size)
            var prev = 0
            if (out.isNotEmpty()) out[0] = MOD_RESET
            for (i in 1 until targets.size) {
                val v = targets[i]
                if (v == 0) {
                    out[i] = MOD_RESET
                    prev = 0
                    continue
                }
                var best = Int.MAX_VALUE
                for (j in MOD_STEPS.indices) {
                    if (j == MOD_RESET) continue
                    val next = prev + MOD_STEPS[j] * 2
                    val diff = kotlin.math.abs(next - v)
                    if (diff < best && next in MOD_TARGET) {
                        best = diff
                        out[i] = j
                    }
                }
                prev += MOD_STEPS[out[i]] * 2
            }
            return out.toList()
        }

        /** The counter after each entry of [table] (each applied twice, the 7-bit counter wrapping), from 0. */
        fun modTableCurve(table: List<Int>): List<Int> {
            var c = 0
            return table.map { v ->
                c = if (v == MOD_RESET) 0 else wrap7(wrap7(c + MOD_STEPS[v]) + MOD_STEPS[v])
                c
            }
        }

        private fun wrap7(v: Int): Int = ((v + 64) and 0x7F) - 64
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

        /**
         * 5B and EPSM SSG mixer per frame (FamiStudio's `S5BMixer`): 0 tone and noise (the square gated by the noise), 1
         * noise only, 2 tone only (the default of an empty envelope), 3 neither (the channel holds its level: a constant).
         */
        val MIXER = 0..3

        /** 5B and EPSM SSG noise generator period per frame (FamiStudio's `S5BNoiseFreq`): 0-31, higher is lower. */
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
 * 8/16, and the saw, whose volume [sawVolume] scales; [NesChip.VRC7]: the six FM channels, which play the FM patch [fm]
 * shaped by the volume, arpeggio and pitch envelopes, no duty; [NesChip.N163]: the N163's channels, which play the
 * waves [wave] shaped by the volume, arpeggio and pitch envelopes, no duty; [NesChip.FDS]: the FDS channel, which plays
 * the waves and modulation [fds] shaped by the volume, arpeggio and pitch envelopes, no duty; [NesChip.S5B]: the 5B
 * squares, no duty but a [mixer] envelope (per frame 0 tone + noise, 1 noise, 2 tone, 3 neither; empty = tone,
 * FamiStudio's default), a [noise] envelope (the shared noise generator's period 0-31 per frame) and the hardware
 * envelope [s5b]; [NesChip.EPSM]: every EPSM channel, no duty — the SSG squares play it as a 5B sound ([mixer], [noise],
 * [s5b]: the same YM2149 SSG), the FM channels play the FM patch [epsm] shaped by the volume, arpeggio and pitch
 * envelopes, the rhythm drums take only the volume envelope's first value (empty = 15) and [epsm]'s left/right pan).
 * These are left out of project files at their defaults, so 2A03 instruments are stored as before.
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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fds: FdsSound = FdsSound.DEFAULT,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mixer: NesEnvelope = NesEnvelope(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val noise: NesEnvelope = NesEnvelope(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val s5b: S5bEnvelope = S5bEnvelope.DEFAULT,
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
 * mappings), its [expansion] chip, with the VRC7 how its FM notes fade ([vrc7Sustain]) and with the EPSM the chip's one
 * LFO ([epsmLfo]: register `$22`, 0 = off, bit 3 on with the rate 0-7 in bits 0-2, shared by all FM channels and written
 * with every FM sound the engine loads); those are left out of project files at their defaults, so 2A03 songs are stored
 * as before.
 * Stored in project files as the song's `sound` object.
 */
@Serializable
data class NesSound(
    val instruments: List<NesInstrument>,
    val samples: List<DpcmSample> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expansion: NesExpansion = NesExpansion.NONE,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val vrc7Sustain: Vrc7Sustain = Vrc7Sustain(),
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
