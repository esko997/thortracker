package org.thortracker.system.opl3

import kotlinx.serialization.Serializable
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/**
 * One YMF262 operator: exactly the bits of its registers 0x20 (AM, VIB, EGT = [sustain], KSR, MULT), 0x40 (KSL, TL),
 * 0x60 (AR, DR), 0x80 (SL, RR) and 0xE0 (waveform). These fields are the Expert grid and the instrument's source of
 * truth; the curated controls ([Opl3Curated]) only read and write them.
 */
@Serializable
data class Opl3Operator(
    /** Frequency multiplier 0..15 (0 = ×½; the chip plays 11 as 10, 13 as 12 and 14 as 15). */
    val mult: Int = 1,
    /** Total level 0..63: attenuation in 0.75 dB steps (carriers: loudness; modulators: brightness). */
    val tl: Int = 0,
    val ar: Int = 15,
    val dr: Int = 0,
    /** Sustain level 0..15: 3 dB steps below full (15 = silent). */
    val sl: Int = 0,
    val rr: Int = 4,
    /** Waveform 0..7 ([Opl3Curated.WAVE_NAMES]). */
    val wave: Int = 0,
    /** Key scale level 0..3 (0, 3, 1.5, 6 dB per octave in the chip's bit order). */
    val ksl: Int = 0,
    /** Tremolo (the chip's 3.7 Hz amplitude LFO). */
    val am: Boolean = false,
    /** Vibrato (the chip's 6.1 Hz pitch LFO). */
    val vib: Boolean = false,
    /** EGT: hold at [sl] until key off; false = percussive (keeps decaying through [sl] while held). */
    val sustain: Boolean = true,
    /** Key scale rate: faster envelopes on higher notes. */
    val ksr: Boolean = false,
) {
    val reg20: Int get() = (if (am) 0x80 else 0) or (if (vib) 0x40 else 0) or (if (sustain) 0x20 else 0) or (if (ksr) 0x10 else 0) or mult
    val reg40: Int get() = (ksl shl 6) or tl
    val reg60: Int get() = (ar shl 4) or dr
    val reg80: Int get() = (sl shl 4) or rr
    val regE0: Int get() = wave

    /** Why a field is out of range, or null. */
    fun problem(): String? = when {
        mult !in 0..15 -> "multiplier $mult is outside 0-15"
        tl !in 0..63 -> "level $tl is outside 0-63"
        ar !in 0..15 || dr !in 0..15 || sl !in 0..15 || rr !in 0..15 -> "envelope values must be 0-15"
        wave !in 0..7 -> "waveform $wave is outside 0-7"
        ksl !in 0..3 -> "key scale level $ksl is outside 0-3"
        else -> null
    }
}

/**
 * The drum pitch sweep: each key-on starts [start] semitones from the note and moves toward [end] at [speed]/16
 * semitones per tick; [speed] 0 = no sweep ([start]/[end] ignored). Ranges −96..96 and 0..255.
 */
@Serializable
data class Opl3Sweep(val start: Int = 0, val end: Int = 0, val speed: Int = 0) {
    val active: Boolean get() = speed != 0

    companion object {
        val NONE = Opl3Sweep()
    }
}

/**
 * An OPL3 instrument: 2-op or 4-op ([fourOp]), [algorithm] (2-op: 0 FM 1→2, 1 additive 1+2; 4-op: 0 1→2→3→4,
 * 1 (1→2)+(3→4), 2 1+(2→3→4), 3 1+(2→3)+4), operator 1's [feedback] 0..7, always four [operators] (a 2-op
 * instrument keeps operators 3 and 4 for when it becomes 4-op; they are not played or lowered), and the drum [sweep].
 */
@Serializable
data class Opl3Instrument(
    val name: String = "",
    val fourOp: Boolean = false,
    val algorithm: Int = 0,
    val feedback: Int = 0,
    val operators: List<Opl3Operator> = List(4) { Opl3Operator() },
    val sweep: Opl3Sweep = Opl3Sweep.NONE,
) {
    /** Operators the chip plays: 2 or 4. */
    val operatorCount: Int get() = if (fourOp) 4 else 2

    /** True when operator [op] (0-based) is a carrier (heard; volume scales it) under the algorithm. */
    fun isCarrier(op: Int): Boolean = op in carriers(fourOp, algorithm)

    /** Operator [op] (0-based) replaced. */
    fun withOperator(op: Int, value: Opl3Operator): Opl3Instrument =
        copy(operators = operators.toMutableList().also { it[op] = value })

    /** Every played operator mapped through [f] (index, operator); unplayed ones unchanged. */
    fun mapOperators(f: (Int, Opl3Operator) -> Opl3Operator): Opl3Instrument =
        copy(operators = operators.mapIndexed { i, op -> if (i < operatorCount) f(i, op) else op })

    /** Why the instrument cannot be lowered, or null. */
    fun problem(): String? {
        if (operators.size != 4) return "has ${operators.size} operators; instruments store 4"
        if (algorithm !in 0..(if (fourOp) 3 else 1)) return "algorithm $algorithm is outside 0-${if (fourOp) 3 else 1}"
        if (feedback !in 0..7) return "feedback $feedback is outside 0-7"
        if (sweep.start !in -96..96 || sweep.end !in -96..96) return "sweep start and end must be -96..96 semitones"
        if (sweep.speed !in 0..255) return "sweep speed ${sweep.speed} is outside 0-255"
        operators.forEachIndexed { i, op -> op.problem()?.let { return "operator ${i + 1}: $it" } }
        return null
    }

    companion object {
        /** Carrier operators (0-based) per mode and algorithm, from Nuked-OPL3's routing (docs/AUDIO_ENGINE.md). */
        fun carriers(fourOp: Boolean, algorithm: Int): Set<Int> = if (!fourOp) {
            if (algorithm == 1) setOf(0, 1) else setOf(1)
        } else when (algorithm) {
            1 -> setOf(1, 3)
            2 -> setOf(0, 3)
            3 -> setOf(0, 2, 3)
            else -> setOf(3)
        }

        /** A 2-op instrument; operators 3/4 copy 1/2 so switching it to 4-op starts from the same sound. */
        fun twoOp(name: String, algorithm: Int, feedback: Int, op1: Opl3Operator, op2: Opl3Operator, sweep: Opl3Sweep = Opl3Sweep.NONE) =
            Opl3Instrument(name, false, algorithm, feedback, listOf(op1, op2, op1, op2), sweep)

        fun fourOp(name: String, algorithm: Int, feedback: Int, ops: List<Opl3Operator>, sweep: Opl3Sweep = Opl3Sweep.NONE) =
            Opl3Instrument(name, true, algorithm, feedback, ops, sweep)
    }
}

/** A numbered chord shape (`Kxx`): 1..4 non-zero [intervals] in semitones from the note, −48..48 (negative: inversions). */
@Serializable
data class Opl3Chord(val name: String = "", val intervals: List<Int>) {
    /** Why the shape cannot be lowered, or null. */
    fun problem(): String? = when {
        intervals.isEmpty() -> "has no intervals"
        intervals.size > 4 -> "has ${intervals.size} intervals; at most 4"
        intervals.any { it == 0 } -> "has a 0 interval (the note itself always sounds)"
        intervals.any { it !in -48..48 } -> "has an interval outside -48..48"
        else -> null
    }
}

/**
 * An OPL3 song's chip data: [instruments] = exactly [Opl3System.SLOTS] slots (slot n = index n − 1; null = empty, not
 * playable), the chord-shape table [chords] (`K01` = first, at most [Opl3System.MAX_CHORDS]), [multispeed] 1..8 player
 * ticks per 60 Hz frame, and the chip's global LFO depths ([deepTremolo] 4.8 dB instead of 1 dB, [deepVibrato] 14
 * instead of 7 cents; register 0xBD).
 */
@Serializable
data class Opl3Sound(
    val instruments: List<Opl3Instrument?>,
    val chords: List<Opl3Chord> = Opl3Chords.DEFAULT,
    val multispeed: Int = 1,
    val deepTremolo: Boolean = false,
    val deepVibrato: Boolean = false,
) : SystemSound {
    override val system: SoundSystem get() = Opl3System

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): Opl3Instrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: Opl3Instrument?): Opl3Sound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    /** Register 0xBD as the image header stores it. */
    val depthByte: Int get() = (if (deepTremolo) 0x80 else 0) or (if (deepVibrato) 0x40 else 0)

    companion object {
        /** A new song's sound: the presets in slots 01.. ([Opl3Presets.ALL]), the rest empty. */
        fun blank(): Opl3Sound = Opl3Sound(List(Opl3System.SLOTS) { Opl3Presets.ALL.getOrNull(it)?.instrument })
    }
}

/** The OPL3 data of an OPL3 song. */
val Song.opl3: Opl3Sound get() = sound as Opl3Sound

/** The default chord-shape table of new songs. */
object Opl3Chords {
    val DEFAULT: List<Opl3Chord> = listOf(
        Opl3Chord("maj", listOf(4, 7)),
        Opl3Chord("min", listOf(3, 7)),
        Opl3Chord("7", listOf(4, 7, 10)),
        Opl3Chord("maj7", listOf(4, 7, 11)),
        Opl3Chord("m7", listOf(3, 7, 10)),
        Opl3Chord("sus2", listOf(2, 7)),
        Opl3Chord("sus4", listOf(5, 7)),
        Opl3Chord("dim", listOf(3, 6)),
        Opl3Chord("aug", listOf(4, 8)),
        Opl3Chord("add9", listOf(4, 7, 14)),
        Opl3Chord("m9", listOf(3, 7, 10, 14)),
        Opl3Chord("5+8", listOf(7, 12)),
        Opl3Chord("maj/1st", listOf(-8, -5)),
        Opl3Chord("maj/2nd", listOf(-5, 4)),
        Opl3Chord("min/1st", listOf(-9, -5)),
        Opl3Chord("min/2nd", listOf(-5, 3)),
    )
}
