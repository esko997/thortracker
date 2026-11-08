package org.thortracker.system.opm

import kotlinx.serialization.Serializable
import org.thortracker.backend.OpmPlayerImage
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/**
 * One YM2151 (OPM) operator: exactly the bits of its registers $40 (DT1, MUL), $60 (TL), $80 (KS, AR), $A0
 * (AMS-EN, D1R), $C0 (DT2, D2R) and $E0 (D1L, RR). These fields are the Expert grid and the instrument's source of
 * truth; the curated controls ([OpmCurated]) only read and write them.
 */
@Serializable
data class OpmOperator(
    /** Fine detune DT1 0..7: 0 none, 1-3 up (small to large), 4 none, 5-7 down. */
    val dt1: Int = 0,
    /** Frequency multiplier 0..15 (0 = ×½). */
    val mul: Int = 1,
    /** Total level 0..127: attenuation in 0.75 dB steps (carriers: loudness; modulators: brightness). */
    val tl: Int = 0,
    /** Key scale (rate scaling) 0..3: faster envelopes on higher notes. */
    val ks: Int = 0,
    /** Attack rate 0..31 (31 = instant, 0 = never starts). */
    val ar: Int = 31,
    /** Amplitude modulation by the shared LFO (AMS-EN; depth = the instrument's AMS × the song's AMD). */
    val am: Boolean = false,
    /** First decay rate 0..31, from full level down to [d1l]. */
    val d1r: Int = 0,
    /** Coarse detune DT2 0..3: the frequency × 1, ×1.41 (+600 cents), ×1.57 (+781), ×1.73 (+950): inharmonic ratios. */
    val dt2: Int = 0,
    /** Second decay ("sustain rate") 0..31 below [d1l] while the key is held; 0 = held at [d1l]. */
    val d2r: Int = 0,
    /** First decay level (sustain level) 0..15: 3 dB steps below full; 15 = −93 dB. */
    val d1l: Int = 0,
    /** Release rate 0..15 after key off (15 = fastest). */
    val rr: Int = 15,
) {
    val reg40: Int get() = (dt1 shl 4) or mul
    val reg60: Int get() = tl
    val reg80: Int get() = (ks shl 6) or ar
    val regA0: Int get() = (if (am) 0x80 else 0) or d1r
    val regC0: Int get() = (dt2 shl 6) or d2r
    val regE0: Int get() = (d1l shl 4) or rr

    /** Why a field is out of range, or null. */
    fun problem(): String? = when {
        dt1 !in 0..7 -> "detune $dt1 is outside 0-7"
        mul !in 0..15 -> "multiplier $mul is outside 0-15"
        tl !in 0..127 -> "level $tl is outside 0-127"
        ks !in 0..3 -> "key scale $ks is outside 0-3"
        ar !in 0..31 || d1r !in 0..31 || d2r !in 0..31 -> "attack and decay rates must be 0-31"
        dt2 !in 0..3 -> "coarse detune $dt2 is outside 0-3"
        d1l !in 0..15 || rr !in 0..15 -> "sustain level and release must be 0-15"
        else -> null
    }

    companion object {
        /** An operator from its six register bytes ($40 … $E0), reserved bits ignored. */
        fun ofRegisters(r40: Int, r60: Int, r80: Int, rA0: Int, rC0: Int, rE0: Int) = OpmOperator(
            dt1 = r40 shr 4 and 7, mul = r40 and 15, tl = r60 and 0x7F, ks = r80 shr 6 and 3, ar = r80 and 31,
            am = rA0 and 0x80 != 0, d1r = rA0 and 31, dt2 = rC0 shr 6 and 3, d2r = rC0 and 31, d1l = rE0 shr 4 and 15, rr = rE0 and 15,
        )
    }
}

/**
 * The drum pitch sweep: each key-on starts [start] semitones from the note and moves toward [end] at [speed]/16
 * semitones per tick; [speed] 0 = no sweep ([start]/[end] ignored). Ranges −96..96 and 0..255.
 */
@Serializable
data class OpmSweep(val start: Int = 0, val end: Int = 0, val speed: Int = 0) {
    val active: Boolean get() = speed != 0

    fun problem(): String? = when {
        start !in -OpmPlayerImage.SWEEP_LIMIT..OpmPlayerImage.SWEEP_LIMIT || end !in -OpmPlayerImage.SWEEP_LIMIT..OpmPlayerImage.SWEEP_LIMIT ->
            "sweep start and end must be -96..96 semitones"
        speed !in 0..255 -> "sweep speed $speed is outside 0-255"
        else -> null
    }

    companion object {
        val NONE = OpmSweep()
    }
}

/**
 * A YM2151 voice: [algorithm] 0..7 (the chip's routing, the YM2612's eight; [OpmCurated.ALGORITHMS]), operator 1's
 * [feedback] 0..7, the channel's LFO sensitivities [pms] 0..7 (pitch) and [ams] 0..3 (amplitude, for operators with
 * [OpmOperator.am]) — how much of the song's one shared LFO this voice takes —, four [operators] numbered as in the
 * algorithm diagrams (1 = M1 with the feedback, 2 = C1, 3 = M2, 4 = C2), the drum [sweep], and [noise]: operator 4
 * plays the chip's noise generator at [noiseFrequency] 0..31 instead of its sine. The YM2151 has one noise generator,
 * wired to FM 8's operator 4 only, so a noise instrument plays on FM 8 alone (validators refuse it elsewhere).
 */
@Serializable
data class OpmInstrument(
    val name: String = "",
    val algorithm: Int = 0,
    val feedback: Int = 0,
    val pms: Int = 0,
    val ams: Int = 0,
    val operators: List<OpmOperator> = List(4) { OpmOperator() },
    val noise: Boolean = false,
    val noiseFrequency: Int = 0,
    val sweep: OpmSweep = OpmSweep.NONE,
) {
    /** True when operator [op] (0-based) is a carrier (heard; volume scales it) under the algorithm. */
    fun isCarrier(op: Int): Boolean = carriers(algorithm) shr op and 1 != 0

    /** Operator [op] (0-based) replaced. */
    fun withOperator(op: Int, value: OpmOperator): OpmInstrument =
        copy(operators = operators.toMutableList().also { it[op] = value })

    /** Every operator mapped through [f] (index, operator). */
    fun mapOperators(f: (Int, OpmOperator) -> OpmOperator): OpmInstrument = copy(operators = operators.mapIndexed(f))

    /** Register $20 without the pan bits: feedback << 3 | algorithm. */
    val connect: Int get() = (feedback shl 3) or algorithm

    /** Register $38: PMS << 4 | AMS. */
    val sens: Int get() = (pms shl 4) or ams

    /** Why the instrument cannot be lowered, or null. */
    fun problem(): String? {
        if (operators.size != 4) return "has ${operators.size} operators; OPM instruments have 4"
        if (algorithm !in 0..7) return "algorithm $algorithm is outside 0-7"
        if (feedback !in 0..7) return "feedback $feedback is outside 0-7"
        if (pms !in 0..7) return "PMS $pms is outside 0-7"
        if (ams !in 0..3) return "AMS $ams is outside 0-3"
        if (noiseFrequency !in 0..31) return "noise frequency $noiseFrequency is outside 0-31"
        if (!noise && noiseFrequency != 0) return "has a noise frequency without noise"
        sweep.problem()?.let { return it }
        operators.forEachIndexed { i, op -> op.problem()?.let { return "operator ${i + 1}: $it" } }
        return null
    }

    companion object {
        /** Carrier operators of [algorithm] as a bit set (bit k = operator k + 1), as the player's table has them. */
        fun carriers(algorithm: Int): Int = CARRIERS[algorithm.coerceIn(0, 7)]

        /** Carriers per algorithm (the YM2151's routing, identical to the OPN family's). */
        private val CARRIERS = intArrayOf(0x8, 0x8, 0x8, 0x8, 0xA, 0xE, 0xE, 0xF)
    }
}

/**
 * An OPM song's chip data: [instruments] = exactly [OpmSystem.SLOTS] slots (slot n = index n − 1; null = empty, not
 * playable), [multispeed] 1..8 player ticks per 60 Hz frame, and the chip's **shared** state at the song start — one
 * of each on the YM2151, so it is song data, not instrument data; effects change it while playing: the LFO's
 * [lfoRate] (LFRQ 0..255, `Lxx`), [lfoWave] (0 saw, 1 square, 2 triangle, 3 noise; `Wxx`), its amplitude depth [amd]
 * (0..127, `Txx`) and pitch depth [pmd] (0..127, `Vxx`), and CSM: [csm] on keys on every channel's four operators at
 * each overflow of timer A ([timerA] 0..1023: 62500 / (1024 − timerA) Hz at 4 MHz; `Xxx`, `Qxx`).
 */
@Serializable
data class OpmSound(
    val instruments: List<OpmInstrument?>,
    val multispeed: Int = 1,
    val lfoRate: Int = 0,
    val lfoWave: Int = 0,
    val amd: Int = 0,
    val pmd: Int = 0,
    val csm: Boolean = false,
    val timerA: Int = 0,
) : SystemSound {
    override val system: SoundSystem get() = OpmSystem

    /** Instrument [slot] (1-based), or null when empty or out of range. */
    fun instrument(slot: Int): OpmInstrument? = instruments.getOrNull(slot - 1)

    /** [slot] (1-based) set to [instrument] (null empties it). */
    fun withInstrument(slot: Int, instrument: OpmInstrument?): OpmSound =
        copy(instruments = instruments.toMutableList().also { it[slot - 1] = instrument })

    /** Why a song-level value is out of range, or null. */
    fun problem(): String? = when {
        instruments.size != OpmSystem.SLOTS -> "has ${instruments.size} instrument slots; OPM songs have ${OpmSystem.SLOTS}"
        multispeed !in 1..OpmSystem.MAX_MULTISPEED -> "multispeed $multispeed is outside 1-${OpmSystem.MAX_MULTISPEED}"
        lfoRate !in 0..255 -> "LFO rate $lfoRate is outside 0-255"
        lfoWave !in 0..3 -> "LFO waveform $lfoWave is outside 0-3"
        amd !in 0..127 -> "LFO amplitude depth $amd is outside 0-127"
        pmd !in 0..127 -> "LFO pitch depth $pmd is outside 0-127"
        timerA !in 0..1023 -> "timer A $timerA is outside 0-1023"
        else -> null
    }

    companion object {
        /** A new song's sound: the presets in slots 01.. ([OpmPresets.ALL]), the rest empty; LFO and CSM off. */
        fun blank(): OpmSound = OpmSound(List(OpmSystem.SLOTS) { OpmPresets.ALL.getOrNull(it)?.instrument })
    }
}

/** The OPM data of a YM2151 song. */
val Song.opm: OpmSound get() = sound as OpmSound
