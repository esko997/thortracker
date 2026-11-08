package org.thortracker.system.sid

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/** The SID chip a song is written for (the player image is the same; reSID models the chip). */
@Serializable
enum class SidChip(val label: String) {
    /** The original SID: warmer, chip-dependent filter, louder combined waveforms. GoatTracker's default. */
    @SerialName("6581") MOS6581("MOS 6581"),

    /** The later SID: near-linear, consistent filter; recommended for new songs. */
    @SerialName("8580") MOS8580("MOS 8580"),
}

/**
 * One row of an instrument program: GoatTracker's left and right table bytes as its table editor shows them (not the
 * packed form), except where a row points somewhere ([SidTables] lowers these):
 * - a jump (left `$FF`): [right] is a row of the same program (1 = its first row), 0 = stop;
 * - a wave program command (left `$F1-$FD`): [right] is the parameter as the pattern effect of the same number takes
 *   it ([SidRules]), e.g. `F4` + `34` = vibrato speed 3, depth `$40`; `F9` + `05` = instrument 05's pulse program.
 * Byte meanings per table: [SidTables.describe].
 */
@Serializable(with = SidRowSerializer::class)
data class SidRow(val left: Int, val right: Int = 0) {
    val isJump: Boolean get() = left == SidTables.JUMP

    companion object {
        /** Wave program: [waveform] (`$10-$DF`, gate bit included) at the note plus [relative] semitones (-32..95). */
        fun wave(waveform: Int, relative: Int = 0) = SidRow(waveform, SidTables.relativeNote(relative))

        /** Wave program: [waveform] at absolute [note] (1..95: C#0..B-7, GoatTracker cannot name C-0 here). */
        fun waveAbsolute(waveform: Int, note: Int) = SidRow(waveform, SidTables.KEEP_FREQUENCY + note)

        /** Wave program: wait [ticks] (1..15) more ticks, frequency unchanged. */
        fun delay(ticks: Int) = SidRow(ticks, SidTables.KEEP_FREQUENCY)

        /** Pulse program: set the pulse width to [width] (0..`$FFF`). */
        fun pulseWidth(width: Int) = SidRow(0x80 or (width shr 8), width and 0xFF)

        /** Pulse or filter program: add signed [speed] (-128..127) each tick for [ticks] (1..127) ticks. */
        fun modulate(ticks: Int, speed: Int) = SidRow(ticks, speed and 0xFF)

        /** Filter program: [passband] (`SidTables.LOWPASS` …), [resonance] 0-15, [routing] bits voice 1/2/3 = 1/2/4. */
        fun filter(passband: Int, resonance: Int, routing: Int) = SidRow(0x80 or passband, (resonance shl 4) or routing)

        /** Filter program: set the cutoff (high 8 bits of the 11-bit register). */
        fun cutoff(value: Int) = SidRow(0x00, value)

        /** Any program: continue at [row] (1-based within the program; 0 = stop). */
        fun jump(row: Int) = SidRow(SidTables.JUMP, row)

        /** Wave program: run pattern command [command] (1-D except 8) with [param], frequency unchanged this tick. */
        fun command(command: Int, param: Int) = SidRow(SidTables.WAVE_COMMAND + command, param)
    }
}

/** Compact `[left, right]`. */
object SidRowSerializer : KSerializer<SidRow> {
    private val delegate = IntArraySerializer()

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("org.thortracker.system.sid.SidRow", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: SidRow) = encoder.encodeSerializableValue(delegate, intArrayOf(value.left, value.right))

    override fun deserialize(decoder: Decoder): SidRow {
        val v = decoder.decodeSerializableValue(delegate)
        if (v.size != 2) throw SerializationException("A SID table row must be [left, right], got ${v.size} values")
        return SidRow(v[0], v[1])
    }
}

/**
 * An instrument's program for one GoatTracker table (wave, pulse or filter): [rows] played from the first, each tick
 * (or as delays and modulation times say). When the last row is not a jump the program stops there (the lowering adds
 * GoatTracker's `$FF 00`). [at] is the song-wide table row (1..255) the program occupied when last placed: the
 * lowering keeps it there while it fits, so live edits and the raw table view keep their row numbers ([SidTables]).
 * Empty = no program (wave: the instrument keeps the waveform; pulse/filter: left as they are).
 */
@Serializable
data class SidProgram(val rows: List<SidRow> = emptyList(), val at: Int = 0) {
    /** Table rows the program takes: its rows plus the stop row the lowering adds. */
    val size: Int get() = if (rows.isEmpty()) 0 else rows.size + if (rows.last().isJump) 0 else 1

    /** Same rows, ignoring where they are placed. */
    fun sameRows(other: SidProgram): Boolean = rows == other.rows
}

/** How a note of an instrument starts ([SidInstrument.gateTimer] ticks before it). GoatTracker's gate timer bits. */
@Serializable
enum class SidRestart {
    /** Gate off and hard restart (the song's [SidSound.hardRestartAttackDecay]/SR) before the note. */
    @SerialName("hard") HARD,

    /** Gate off only (`$80`): faster attack, may click or miss notes on the 6581's ADSR bug. */
    @SerialName("gateOff") GATE_OFF,

    /** Neither (`$40`): the note changes pitch and programs under a running envelope. */
    @SerialName("legato") LEGATO,
}

/**
 * Instrument vibrato (GoatTracker's vibrato parameter and delay): after [delay] ticks (0 = no vibrato), the pitch moves
 * by [depth] each tick and turns every [speed] ticks. [noteIndependent]: depth is the note's semitone step shifted right
 * by [depth] (same width on every note) instead of frequency units. One speedtable row, derived by the lowering.
 */
@Serializable
data class SidVibrato(val delay: Int = 0, val speed: Int = 0, val depth: Int = 0, val noteIndependent: Boolean = false) {
    val active: Boolean get() = delay > 0

    companion object {
        val DELAY = 0..255
        val SPEED = 1..127
        val DEPTH = 0..255

        /** Note-independent depths: right shifts of the semitone step. */
        val SHIFT = 0..8
    }
}

/**
 * A GoatTracker 2 instrument: the ADSR envelope (each 0-15), how notes start ([restart], [gateTimer] ticks before the
 * note, [firstWave] on its first tick: usually `$09` test + gate; `$00` keep the waveform, `$FE`/`$FF` keep it with gate
 * off/on), [vibrato], and its [wave], [pulse] and [filter] programs. The last instrument to start a filter program owns
 * the chip's one filter. Ranges: [SidSystem]'s checks.
 */
@Serializable
data class SidInstrument(
    val name: String = "Sound",
    val attack: Int = 0,
    val decay: Int = 9,
    val sustain: Int = 10,
    val release: Int = 9,
    val restart: SidRestart = SidRestart.HARD,
    val gateTimer: Int = 2,
    val firstWave: Int = 0x09,
    val vibrato: SidVibrato = SidVibrato(),
    val wave: SidProgram = SidProgram(listOf(SidRow.wave(SidTables.SAW or SidTables.GATE))),
    val pulse: SidProgram = SidProgram(),
    val filter: SidProgram = SidProgram(),
) {
    /** GoatTracker's attack/decay byte. */
    val attackDecay: Int get() = (attack shl 4) or decay

    /** GoatTracker's sustain/release byte. */
    val sustainRelease: Int get() = (sustain shl 4) or release

    /** GoatTracker's gate timer byte: ticks plus `$80` (no hard restart) or `$40` (legato). */
    val gateTimerByte: Int get() = gateTimer or when (restart) {
        SidRestart.HARD -> 0
        SidRestart.GATE_OFF -> 0x80
        SidRestart.LEGATO -> 0x40
    }

    /** The program of [table] ([SidTables.WAVE], [SidTables.PULSE], [SidTables.FILTER]). */
    fun program(table: Int): SidProgram = when (table) {
        SidTables.WAVE -> wave
        SidTables.PULSE -> pulse
        SidTables.FILTER -> filter
        else -> throw IllegalArgumentException("table $table has no instrument programs")
    }

    fun withProgram(table: Int, program: SidProgram): SidInstrument = when (table) {
        SidTables.WAVE -> copy(wave = program)
        SidTables.PULSE -> copy(pulse = program)
        SidTables.FILTER -> copy(filter = program)
        else -> throw IllegalArgumentException("table $table has no instrument programs")
    }

    companion object {
        val NIBBLE = 0..15

        /** Gate timer ticks (GoatTracker's low 6 bits); must stay below the song's tempo. */
        val GATE_TIMER = 1..63
        const val NAME_MAX = 16

        val DEFAULT = SidInstrument()
    }
}

/**
 * A C64 SID song's chip data: exactly [SidSystem.SLOTS] instruments shared by the three voices (slot n = index n-1),
 * the [chip] model, the [multispeed] factor (player calls per PAL frame, 1-16: tempos and gate timers count these
 * faster ticks) and the hard-restart envelope every [SidRestart.HARD] note starts from (GoatTracker's `-A`, default
 * `$0F00`). Tables are not stored: the instruments' programs and the effects make them ([SidTables]); tunings play
 * through the song's frequency table ([SidTuning]). Stored in project files as the song's `sound` object.
 */
@Serializable
data class SidSound(
    val instruments: List<SidInstrument>,
    val chip: SidChip = SidChip.MOS8580,
    val multispeed: Int = 1,
    val hardRestartAttackDecay: Int = 0x0F,
    val hardRestartSustainRelease: Int = 0x00,
) : SystemSound {
    override val system: SoundSystem get() = SidSystem

    companion object {
        val MULTISPEED = 1..16

        fun blank(): SidSound = SidSound(List(SidSystem.SLOTS) { i -> SidInstrument(name = "Sound ${NumberText.hex(i + 1, 2)}") })
    }
}

/** This SID song's chip data. Only SID code calls it, on SID songs. */
val Song.sid: SidSound get() = sound as SidSound
