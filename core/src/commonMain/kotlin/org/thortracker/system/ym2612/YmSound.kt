package org.thortracker.system.ym2612

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.thortracker.backend.YmPlayerImage
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound
import org.thortracker.system.nes.NesEnvelope

/**
 * One YM2612 (OPN2) operator: exactly the bits of its registers $30 (DT, MUL), $40 (TL), $50 (KS = rate scaling,
 * AR), $60 (AM, D1R), $70 (D2R), $80 (SL = D1L, RR) and $90 (SSG-EG). These fields are the Expert grid and the
 * instrument's source of truth; the curated controls ([YmCurated]) only read and write them.
 */
@Serializable
data class YmOperator(
    /** Detune 0..7: 0 none, 1-3 up (small to large), 4 none, 5-7 down. */
    val dt: Int = 0,
    /** Frequency multiplier 0..15 (0 = ×½). */
    val mul: Int = 1,
    /** Total level 0..127: attenuation in 0.75 dB steps (carriers: loudness; modulators: brightness). */
    val tl: Int = 0,
    /** Key scale (rate scaling) 0..3: faster envelopes on higher notes. */
    val ks: Int = 0,
    /** Attack rate 0..31 (31 = instant, 0 = never starts). */
    val ar: Int = 31,
    /** Amplitude modulation by the LFO (depth = the instrument's AMS). */
    val am: Boolean = false,
    /** First decay rate 0..31, from full level down to [sl]. */
    val d1r: Int = 0,
    /** Second decay ("sustain rate") 0..31 below [sl] while the key is held; 0 = held at [sl]. */
    val d2r: Int = 0,
    /** Sustain level (D1L) 0..15: 3 dB steps below full; 15 = −93 dB. */
    val sl: Int = 0,
    /** Release rate 0..15 after key off (15 = fastest). */
    val rr: Int = 15,
    /** SSG-EG 0..15: bit 3 switches it on (8..15), bits 0-2 the shape ([YmCurated.SSG_EG_NAMES]); 0..7 are off. */
    val ssgEg: Int = 0,
) {
    val reg30: Int get() = (dt shl 4) or mul
    val reg40: Int get() = tl
    val reg50: Int get() = (ks shl 6) or ar
    val reg60: Int get() = (if (am) 0x80 else 0) or d1r
    val reg70: Int get() = d2r
    val reg80: Int get() = (sl shl 4) or rr
    val reg90: Int get() = ssgEg

    /** Why a field is out of range, or null. */
    fun problem(): String? = when {
        dt !in 0..7 -> "detune $dt is outside 0-7"
        mul !in 0..15 -> "multiplier $mul is outside 0-15"
        tl !in 0..127 -> "level $tl is outside 0-127"
        ks !in 0..3 -> "key scale $ks is outside 0-3"
        ar !in 0..31 || d1r !in 0..31 || d2r !in 0..31 -> "attack and decay rates must be 0-31"
        sl !in 0..15 || rr !in 0..15 -> "sustain level and release must be 0-15"
        ssgEg !in 0..15 -> "SSG-EG $ssgEg is outside 0-15"
        else -> null
    }

    companion object {
        /** An operator from its seven register bytes ($30 … $90), reserved bits ignored. */
        fun ofRegisters(r30: Int, r40: Int, r50: Int, r60: Int, r70: Int, r80: Int, r90: Int) = YmOperator(
            dt = r30 shr 4 and 7, mul = r30 and 15, tl = r40 and 0x7F, ks = r50 shr 6 and 3, ar = r50 and 31,
            am = r60 and 0x80 != 0, d1r = r60 and 31, d2r = r70 and 31, sl = r80 shr 4 and 15, rr = r80 and 15, ssgEg = r90 and 15,
        )
    }
}

/**
 * The drum pitch sweep: each key-on starts [start] semitones from the note and moves toward [end] at [speed]/16
 * semitones per tick; [speed] 0 = no sweep ([start]/[end] ignored). Ranges −96..96 and 0..255.
 */
@Serializable
data class YmSweep(val start: Int = 0, val end: Int = 0, val speed: Int = 0) {
    val active: Boolean get() = speed != 0

    fun problem(): String? = when {
        start !in -YmPlayerImage.SWEEP_LIMIT..YmPlayerImage.SWEEP_LIMIT || end !in -YmPlayerImage.SWEEP_LIMIT..YmPlayerImage.SWEEP_LIMIT ->
            "sweep start and end must be -96..96 semitones"
        speed !in 0..255 -> "sweep speed $speed is outside 0-255"
        else -> null
    }

    companion object {
        val NONE = YmSweep()
    }
}

/**
 * A Mega Drive instrument slot's sound: an FM voice for FM 1-6 ([YmFmInstrument]) or a PSG sound for PSG 1-3 and the
 * Noise channel ([YmPsgInstrument]). Stored with a `"type"` of `"fm"` or `"psg"`.
 */
@Serializable
sealed interface YmInstrument {
    val name: String
    val sweep: YmSweep

    /** Why the instrument cannot be lowered, or null. */
    fun problem(): String?

    /** This instrument renamed. */
    fun named(name: String): YmInstrument
}

/**
 * A YM2612 FM voice: [algorithm] 0..7 (the chip's routing; [YmCurated.ALGORITHMS]), operator 1's [feedback] 0..7, the
 * LFO depths [ams] 0..3 (amplitude, for operators with [YmOperator.am]) and [fms] 0..7 (vibrato), four [operators]
 * numbered as in the algorithm diagrams (operator 1 has the feedback) and the drum [sweep].
 */
@Serializable
@SerialName("fm")
data class YmFmInstrument(
    override val name: String = "",
    val algorithm: Int = 0,
    val feedback: Int = 0,
    val ams: Int = 0,
    val fms: Int = 0,
    val operators: List<YmOperator> = List(4) { YmOperator() },
    override val sweep: YmSweep = YmSweep.NONE,
) : YmInstrument {
    /** True when operator [op] (0-based) is a carrier (heard; volume scales it) under the algorithm. */
    fun isCarrier(op: Int): Boolean = carriers(algorithm) shr op and 1 != 0

    /** Operator [op] (0-based) replaced. */
    fun withOperator(op: Int, value: YmOperator): YmFmInstrument =
        copy(operators = operators.toMutableList().also { it[op] = value })

    /** Every operator mapped through [f] (index, operator). */
    fun mapOperators(f: (Int, YmOperator) -> YmOperator): YmFmInstrument = copy(operators = operators.mapIndexed(f))

    /** Register $B4 without the pan bits. */
    val amsFms: Int get() = (ams shl 4) or fms

    override fun named(name: String): YmFmInstrument = copy(name = name)

    override fun problem(): String? {
        if (operators.size != 4) return "has ${operators.size} operators; FM instruments have 4"
        if (algorithm !in 0..7) return "algorithm $algorithm is outside 0-7"
        if (feedback !in 0..7) return "feedback $feedback is outside 0-7"
        if (ams !in 0..3) return "AMS $ams is outside 0-3"
        if (fms !in 0..7) return "FMS $fms is outside 0-7"
        sweep.problem()?.let { return it }
        operators.forEachIndexed { i, op -> op.problem()?.let { return "operator ${i + 1}: $it" } }
        return null
    }

    companion object {
        /** Carrier operators of [algorithm] as a bit set (bit k = operator k + 1), as the player's table has them. */
        fun carriers(algorithm: Int): Int = YmPlayerImage.CARRIERS[algorithm.coerceIn(0, 7)]
    }
}

/**
 * A PSG sound for PSG 1-3 and the Noise channel: FamiStudio-style software envelopes, one value per tick (vertical
 * interrupt) — [volume] 0..15 (empty = 15; its release index is where a released note continues), [arpeggio]
 * −64..63 semitones from the note (empty = 0), [pitch] −64..63 sixteenths of a semitone (absolute per tick, or
 * accumulated each tick when [pitchRelative]; empty = 0) — the Noise channel's [noise] mode (bit 2 white, bits 0-1
 * the rate: clock/512, /1024, /2048 or 3 = pitched by PSG 3's period; ignored on PSG 1-3) and the drum [sweep].
 * Envelope loop/release semantics are [NesEnvelope]'s.
 */
@Serializable
@SerialName("psg")
data class YmPsgInstrument(
    override val name: String = "",
    val volume: NesEnvelope = NesEnvelope(),
    val arpeggio: NesEnvelope = NesEnvelope(),
    val pitch: NesEnvelope = NesEnvelope(),
    val pitchRelative: Boolean = false,
    val noise: Int = WHITE or 1,
    override val sweep: YmSweep = YmSweep.NONE,
) : YmInstrument {
    override fun named(name: String): YmPsgInstrument = copy(name = name)

    override fun problem(): String? {
        envelopeProblem("volume", volume, NesEnvelope.VOLUME, releasable = true)?.let { return it }
        envelopeProblem("arpeggio", arpeggio, NesEnvelope.ARPEGGIO, releasable = false)?.let { return it }
        envelopeProblem("pitch", pitch, NesEnvelope.PITCH, releasable = false)?.let { return it }
        if (noise !in 0..7) return "noise mode $noise is outside 0-7"
        return sweep.problem()
    }

    companion object {
        /** [noise] bit 2: white noise (else periodic). */
        const val WHITE = 4

        /** [noise] rate 3: PSG 3's period, set by the note. */
        const val PITCHED = 3

        /** Why [env] cannot be lowered as the [what] envelope, or null (the native loader's envelope checks). */
        fun envelopeProblem(what: String, env: NesEnvelope, range: IntRange, releasable: Boolean): String? {
            if (env.values.isEmpty()) {
                return if (env.loop != -1 || env.release != -1) "the empty $what envelope has a loop or release point" else null
            }
            val n = env.values.size
            return when {
                n > NesEnvelope.MAX_LENGTH -> "the $what envelope has $n values; at most ${NesEnvelope.MAX_LENGTH}"
                env.values.any { it !in range } -> "$what envelope values must be ${range.first}..${range.last}"
                env.loop != -1 && env.loop !in 0 until n -> "the $what envelope's loop point ${env.loop} is past its end"
                env.release != -1 && !releasable -> "only the volume envelope has a release point"
                env.release != -1 && env.loop == -1 -> "the $what envelope's release point needs a loop before it"
                env.release != -1 && (env.release <= env.loop || env.release >= n) ->
                    "the $what envelope's release point must lie after its loop point and before its end"
                env.release < -1 || env.loop < -1 -> "the $what envelope's loop and release points must be -1 (none) or an index"
                else -> null
            }
        }
    }
}

/**
 * A Mega Drive song's chip data: [instruments] = exactly [YmSystem.SLOTS] slots (slot n = index n − 1; null = empty,
 * not playable), the [pal] region (50 Hz ticks and PAL clocks; NTSC 60 Hz by default) and the chip's [lfo] at the song
 * start as register $22 holds it: 0 = off, $08-$0F = on at rate 0-7 (`Lxx` changes it while playing).
 */
@Serializable
data class YmSound(
    val instruments: List<YmInstrument?>,
    val pal: Boolean = false,
    val lfo: Int = 0,
) : SystemSound {
    override val system: SoundSystem get() = YmSystem

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): YmInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: YmInstrument?): YmSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    companion object {
        /** A new song's sound: the presets in slots 01.. ([YmPresets.ALL]), the rest empty. */
        fun blank(): YmSound = YmSound(List(YmSystem.SLOTS) { YmPresets.ALL.getOrNull(it)?.instrument })
    }
}

/** The Mega Drive data of a YM2612 song. */
val Song.ym: YmSound get() = sound as YmSound
