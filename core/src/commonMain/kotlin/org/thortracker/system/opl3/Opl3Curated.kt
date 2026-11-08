package org.thortracker.system.opl3

import kotlin.math.abs
import kotlin.math.ln

/**
 * The OPL3 instrument editor's curated controls, in one place: short lists of algorithms, ratio sets and waveform
 * combinations, and macros (brightness, level, attack, decay, sustain, release, tone decay, key scaling, LFO) that each
 * read and write a fixed set of operator fields.
 *
 * **Rule.** The operator registers ([Opl3Operator], the Expert grid) are the instrument; every control is a view of
 * them. [controls] reads each control from the registers: a list control shows the entry the registers match, or —
 * when hand edits in the Expert grid match no entry — the nearest entry with its `exact` flag false (shown "≈"); a
 * macro reads a representative operator (the first carrier or modulator) and is `exact` when every operator it
 * writes agrees. Stepping a control (`with…`) writes its new value into exactly the fields it owns (listed per
 * function) and leaves every other field alone, so curated edits and Expert edits mix freely.
 */
object Opl3Curated {
    /** OPL3 waveform names, 0..7. */
    val WAVE_NAMES = listOf("Sine", "Half", "Abs", "Pulse", "Alt", "Camel", "Square", "Saw")

    /** Short waveform names for combination labels. */
    private val WAVE_SHORT = listOf("Sin", "Hlf", "Abs", "Pls", "Alt", "Cml", "Sqr", "Saw")

    /** The chip's real multiplier for register value [mult] (0 = ½; 11→10, 13→12, 14→15). */
    fun multValue(mult: Int): Double = when (mult) {
        0 -> 0.5
        11 -> 10.0
        13 -> 12.0
        14 -> 15.0
        else -> mult.toDouble()
    }

    /** "½", "1" … "15" (as the chip plays it). */
    fun multText(mult: Int): String = if (mult == 0) "½" else multValue(mult).toInt().toString()

    // ---- algorithms ---------------------------------------------------------------------------------------

    /** An algorithm: 2-op or 4-op [fourOp] with the chip's [algorithm] number, [name] and routing [diagram]. */
    data class Algorithm(val name: String, val fourOp: Boolean, val algorithm: Int, val diagram: String)

    val ALGORITHMS = listOf(
        Algorithm("2-op FM", false, 0, "1→2"),
        Algorithm("2-op additive", false, 1, "1+2"),
        Algorithm("4-op chain", true, 0, "1→2→3→4"),
        Algorithm("4-op two pairs", true, 1, "(1→2)+(3→4)"),
        Algorithm("4-op 1 + chain", true, 2, "1+(2→3→4)"),
        Algorithm("4-op 1 + pair + 4", true, 3, "1+(2→3)+4"),
    )

    // ---- ratio sets (multipliers of the played operators, operator 1 first) -------------------------------

    /** A set of multiplier register values, one per played operator. */
    data class Ratio(val mults: List<Int>) {
        val name: String get() = mults.joinToString(":") { multText(it) }
    }

    /** 2-op ratio sets: modulator : carrier for FM (carrier 1 keeps the note's pitch), partials for additive. */
    val RATIOS_2OP: List<Ratio> = listOf(
        listOf(1, 1), listOf(2, 1), listOf(3, 1), listOf(4, 1), listOf(5, 1), listOf(6, 1), listOf(7, 1), listOf(8, 1),
        listOf(9, 1), listOf(10, 1), listOf(12, 1), listOf(15, 1), listOf(0, 1), listOf(1, 2), listOf(3, 2),
        listOf(5, 2), listOf(1, 3), listOf(2, 3), listOf(4, 3), listOf(1, 4), listOf(3, 4), listOf(2, 2),
    ).map(::Ratio)

    /** 4-op ratio sets, operators 1-4. */
    val RATIOS_4OP: List<Ratio> = listOf(
        listOf(1, 1, 1, 1), listOf(2, 1, 1, 1), listOf(1, 1, 2, 1), listOf(2, 1, 2, 1), listOf(1, 2, 1, 1),
        listOf(3, 1, 1, 1), listOf(1, 1, 3, 1), listOf(3, 1, 3, 1), listOf(4, 1, 1, 1), listOf(1, 1, 4, 1),
        listOf(2, 2, 1, 1), listOf(1, 2, 1, 2), listOf(2, 1, 1, 2), listOf(4, 2, 2, 1), listOf(14, 1, 1, 1),
        listOf(1, 1, 14, 1), listOf(3, 7, 1, 2), listOf(5, 1, 7, 1), listOf(0, 1, 1, 1), listOf(1, 0, 1, 1),
        listOf(8, 1, 2, 1), listOf(1, 3, 1, 5),
    ).map(::Ratio)

    fun ratios(fourOp: Boolean): List<Ratio> = if (fourOp) RATIOS_4OP else RATIOS_2OP

    // ---- waveform combinations ------------------------------------------------------------------------------

    /** Waveforms of the played operators, operator 1 first. */
    data class Waves(val waves: List<Int>) {
        val name: String get() = waves.joinToString("·") { WAVE_SHORT[it] }
    }

    /**
     * 2-op combinations: sine carriers under each modulator shape (the modulator colours, the carrier keeps the body),
     * then shaped carriers; combinations that sound alike or mostly cancel are left out.
     */
    val WAVES_2OP: List<Waves> = listOf(
        listOf(0, 0), listOf(1, 0), listOf(2, 0), listOf(3, 0), listOf(4, 0), listOf(5, 0), listOf(6, 0), listOf(7, 0),
        listOf(0, 1), listOf(0, 2), listOf(1, 1), listOf(2, 2), listOf(4, 1), listOf(6, 1), listOf(7, 1), listOf(5, 2),
        listOf(0, 6), listOf(6, 6), listOf(0, 7), listOf(3, 3),
    ).map(::Waves)

    /** 4-op combinations, operators 1-4. */
    val WAVES_4OP: List<Waves> = listOf(
        listOf(0, 0, 0, 0), listOf(1, 0, 0, 0), listOf(0, 0, 1, 0), listOf(1, 0, 1, 0), listOf(2, 0, 2, 0),
        listOf(4, 0, 0, 0), listOf(0, 0, 4, 0), listOf(6, 0, 0, 0), listOf(7, 0, 7, 0), listOf(5, 0, 5, 0),
        listOf(0, 1, 0, 1), listOf(1, 1, 1, 1), listOf(2, 2, 2, 2), listOf(0, 0, 0, 2), listOf(4, 0, 0, 5),
        listOf(6, 1, 0, 0), listOf(3, 0, 3, 0), listOf(0, 0, 6, 0), listOf(7, 1, 0, 0), listOf(0, 0, 0, 6),
    ).map(::Waves)

    fun waves(fourOp: Boolean): List<Waves> = if (fourOp) WAVES_4OP else WAVES_2OP

    // ---- key scaling and LFO --------------------------------------------------------------------------------

    /** Key scaling choices: what higher notes lose. Index into [KEY_SCALES]. */
    val KEY_SCALES = listOf("Off", "Soft", "Strong")

    /** LFO choices: the chip's vibrato (all operators) and tremolo (carriers). Index into [LFOS]. */
    val LFOS = listOf("None", "Vibrato", "Tremolo", "Both")

    // ---- reading -------------------------------------------------------------------------------------------

    /**
     * Every curated control's current value read from [ins] (see the class rule). Indices are into the lists for the
     * instrument's operator count; macros are 0..15 (attack, decay, release, tone decay), 0..15 sustain (0 = percussive:
     * no sustain, 15 = held at full level), 0..63 brightness and level, 0..7 feedback.
     */
    data class Controls(
        val algorithm: Int,
        val ratio: Int,
        val ratioExact: Boolean,
        val waves: Int,
        val wavesExact: Boolean,
        /** 63 − the lowest modulator level; -1 for an instrument without modulators (2-op additive). */
        val brightness: Int,
        /** 63 − the lowest carrier level. */
        val level: Int,
        val attack: Int,
        val decay: Int,
        val sustain: Int,
        val release: Int,
        /** Modulator decay (how fast the brightness fades); -1 without modulators. */
        val toneDecay: Int,
        /** True when every operator agrees with attack, decay, sustain, release and tone decay as read. */
        val envelopeExact: Boolean,
        val feedback: Int,
        val keyScale: Int,
        val keyScaleExact: Boolean,
        val lfo: Int,
        val lfoExact: Boolean,
    )

    fun controls(ins: Opl3Instrument): Controls {
        val played = ins.operators.take(ins.operatorCount)
        val carriers = played.indices.filter { ins.isCarrier(it) }
        val modulators = played.indices.filter { !ins.isCarrier(it) }
        val c0 = played[carriers.first()]
        val m0 = modulators.firstOrNull()?.let { played[it] }
        val ratioList = ratios(ins.fourOp)
        val mults = played.map { it.mult }
        val ratio = ratioList.indexOfFirst { it.mults == mults }.takeIf { it >= 0 }
            ?: ratioList.indices.minBy { i -> ratioList[i].mults.zip(mults).sumOf { (a, b) -> abs(ln(multValue(a) / multValue(b))) } }
        val waveList = waves(ins.fourOp)
        val ws = played.map { it.wave }
        val waves = waveList.indexOfFirst { it.waves == ws }.takeIf { it >= 0 }
            ?: waveList.indices.maxBy { i -> waveList[i].waves.zip(ws).count { (a, b) -> a == b } * 100 - i }
        val sustain = sustainOf(c0)
        val envelopeExact = played.all { it.ar == c0.ar && it.rr == c0.rr && sustainOf(it) == sustain } &&
            carriers.all { played[it].dr == c0.dr } && modulators.all { played[it].dr == m0!!.dr }
        val keyScale = keyScaleOf(ins)
        val lfo = lfoOf(ins)
        return Controls(
            algorithm = ALGORITHMS.indexOfFirst { it.fourOp == ins.fourOp && it.algorithm == ins.algorithm }.coerceAtLeast(0),
            ratio = ratio, ratioExact = ratioList[ratio].mults == mults,
            waves = waves, wavesExact = waveList[waves].waves == ws,
            brightness = if (modulators.isEmpty()) -1 else 63 - modulators.minOf { played[it].tl },
            level = 63 - carriers.minOf { played[it].tl },
            attack = c0.ar, decay = c0.dr, sustain = sustain, release = c0.rr,
            toneDecay = m0?.dr ?: -1, envelopeExact = envelopeExact,
            feedback = ins.feedback,
            keyScale = keyScale ?: nearestKeyScale(ins), keyScaleExact = keyScale != null,
            lfo = lfo ?: nearestLfo(ins), lfoExact = lfo != null,
        )
    }

    /** Sustain macro of one operator: 0 = percussive (EGT off), else 15 − SL. */
    private fun sustainOf(op: Opl3Operator): Int = if (!op.sustain) 0 else maxOf(1, 15 - op.sl)

    private fun keyScaleOps(ins: Opl3Instrument, k: Int): List<Opl3Operator> =
        withKeyScale(ins, k).operators.take(ins.operatorCount)

    private fun keyScaleOf(ins: Opl3Instrument): Int? = KEY_SCALES.indices.firstOrNull { k ->
        keyScaleOps(ins, k).zip(ins.operators).all { (a, b) -> a.ksl == b.ksl && a.ksr == b.ksr }
    }

    private fun nearestKeyScale(ins: Opl3Instrument): Int = KEY_SCALES.indices.maxBy { k ->
        keyScaleOps(ins, k).zip(ins.operators).count { (a, b) -> a.ksl == b.ksl && a.ksr == b.ksr } * 10 - k
    }

    private fun lfoOf(ins: Opl3Instrument): Int? = LFOS.indices.firstOrNull { k ->
        withLfo(ins, k).operators == ins.operators
    }

    private fun nearestLfo(ins: Opl3Instrument): Int = LFOS.indices.maxBy { k ->
        withLfo(ins, k).operators.zip(ins.operators).count { (a, b) -> a == b } * 10 - k
    }

    // ---- writing -------------------------------------------------------------------------------------------

    /**
     * Algorithm [index] of [ALGORITHMS]. Owns the mode and algorithm; operator levels follow the new roles: an
     * operator that stays a carrier or stays a modulator keeps its level, a new carrier takes the old level (lowest
     * carrier TL) and a new modulator the old brightness (lowest modulator TL, or 63 − 40 when there was none), so the
     * sound keeps its loudness and brightness. Feedback and every other field are kept.
     */
    fun withAlgorithm(ins: Opl3Instrument, index: Int): Opl3Instrument {
        val a = ALGORITHMS[index.coerceIn(ALGORITHMS.indices)]
        if (a.fourOp == ins.fourOp && a.algorithm == ins.algorithm) return ins
        val c = controls(ins)
        val carrierTl = 63 - c.level
        val modulatorTl = if (c.brightness < 0) 63 - DEFAULT_BRIGHTNESS else 63 - c.brightness
        val next = ins.copy(fourOp = a.fourOp, algorithm = a.algorithm)
        return next.mapOperators { i, op ->
            val was = i < ins.operatorCount && ins.isCarrier(i)
            val now = next.isCarrier(i)
            when {
                i < ins.operatorCount && was == now -> op
                now -> op.copy(tl = carrierTl)
                else -> op.copy(tl = modulatorTl)
            }
        }
    }

    /** Brightness of a new modulator when the instrument had none (algorithm changes from additive). */
    const val DEFAULT_BRIGHTNESS = 40

    /** Ratio set [index] of [ratios] for the instrument's mode. Owns every played operator's MULT. */
    fun withRatio(ins: Opl3Instrument, index: Int): Opl3Instrument {
        val r = ratios(ins.fourOp).let { it[index.coerceIn(it.indices)] }
        return ins.mapOperators { i, op -> op.copy(mult = r.mults[i]) }
    }

    /** Waveform combination [index] of [waves] for the instrument's mode. Owns every played operator's waveform. */
    fun withWaves(ins: Opl3Instrument, index: Int): Opl3Instrument {
        val w = waves(ins.fourOp).let { it[index.coerceIn(it.indices)] }
        return ins.mapOperators { i, op -> op.copy(wave = w.waves[i]) }
    }

    /**
     * Brightness 0..63. Owns the modulators' TL: all move by the same step so the lowest becomes 63 − [value] and
     * their differences stay (clamped 0..63). No effect without modulators. KSL is the key-scale control's.
     */
    fun withBrightness(ins: Opl3Instrument, value: Int): Opl3Instrument {
        val c = controls(ins)
        if (c.brightness < 0) return ins
        val delta = c.brightness - value.coerceIn(0, 63)
        return ins.mapOperators { i, op -> if (ins.isCarrier(i)) op else op.copy(tl = (op.tl + delta).coerceIn(0, 63)) }
    }

    /** Level 0..63. Owns the carriers' TL, shifted together as [withBrightness] shifts modulators. */
    fun withLevel(ins: Opl3Instrument, value: Int): Opl3Instrument {
        val delta = controls(ins).level - value.coerceIn(0, 63)
        return ins.mapOperators { i, op -> if (ins.isCarrier(i)) op.copy(tl = (op.tl + delta).coerceIn(0, 63)) else op }
    }

    /** Attack 0..15 (15 = instant). Owns every played operator's AR. */
    fun withAttack(ins: Opl3Instrument, value: Int): Opl3Instrument =
        ins.mapOperators { _, op -> op.copy(ar = value.coerceIn(0, 15)) }

    /** Decay 0..15 (15 = fastest) toward the sustain level. Owns the carriers' DR. */
    fun withDecay(ins: Opl3Instrument, value: Int): Opl3Instrument =
        ins.mapOperators { i, op -> if (ins.isCarrier(i)) op.copy(dr = value.coerceIn(0, 15)) else op }

    /** Tone decay 0..15: how fast the modulators (brightness) fade. Owns the modulators' DR; none without modulators. */
    fun withToneDecay(ins: Opl3Instrument, value: Int): Opl3Instrument =
        ins.mapOperators { i, op -> if (ins.isCarrier(i)) op else op.copy(dr = value.coerceIn(0, 15)) }

    /**
     * Sustain 0..15: 0 = percussive (EGT off: the sound decays to silence while held), 1..15 = held at SL 15 − value
     * (15 = full level). Owns every played operator's EGT and SL.
     */
    fun withSustain(ins: Opl3Instrument, value: Int): Opl3Instrument {
        val v = value.coerceIn(0, 15)
        return ins.mapOperators { _, op -> if (v == 0) op.copy(sustain = false, sl = 15) else op.copy(sustain = true, sl = 15 - v) }
    }

    /** Release 0..15 (15 = fastest) after key off. Owns every played operator's RR. */
    fun withRelease(ins: Opl3Instrument, value: Int): Opl3Instrument =
        ins.mapOperators { _, op -> op.copy(rr = value.coerceIn(0, 15)) }

    /** Feedback 0..7 of operator 1. Owns the instrument's feedback. */
    fun withFeedback(ins: Opl3Instrument, value: Int): Opl3Instrument = ins.copy(feedback = value.coerceIn(0, 7))

    /**
     * Key scaling [index] of [KEY_SCALES] (the chip's KSL codes: 1 = 3 dB/oct, 2 = 1.5 dB/oct): Off = no KSL or KSR;
     * Soft = modulators KSL 2 (higher notes slightly less bright); Strong = modulators KSL 1, carriers KSL 2 (higher
     * notes also quieter, like acoustic instruments) and KSR on (faster envelopes up high). Owns every played
     * operator's KSL and KSR.
     */
    fun withKeyScale(ins: Opl3Instrument, index: Int): Opl3Instrument {
        val k = index.coerceIn(KEY_SCALES.indices)
        return ins.mapOperators { i, op ->
            val carrier = ins.isCarrier(i)
            when (k) {
                0 -> op.copy(ksl = 0, ksr = false)
                1 -> op.copy(ksl = if (carrier) 0 else 2, ksr = false)
                else -> op.copy(ksl = if (carrier) 2 else 1, ksr = true)
            }
        }
    }

    /** LFO [index] of [LFOS]: vibrato sets VIB on every played operator, tremolo AM on the carriers. Owns AM and VIB. */
    fun withLfo(ins: Opl3Instrument, index: Int): Opl3Instrument {
        val k = index.coerceIn(LFOS.indices)
        val vib = k == 1 || k == 3
        val trem = k == 2 || k == 3
        return ins.mapOperators { i, op -> op.copy(vib = vib, am = trem && ins.isCarrier(i)) }
    }

    /** One line for lists: "2-op FM · 2:1 · Hlf·Sin · bright 41 · fb 5" (sweep shown for drums). */
    fun summary(ins: Opl3Instrument): String {
        val c = controls(ins)
        val parts = listOfNotNull(
            ALGORITHMS[c.algorithm].name,
            (if (c.ratioExact) "" else "≈") + ratios(ins.fourOp)[c.ratio].name,
            (if (c.wavesExact) "" else "≈") + waves(ins.fourOp)[c.waves].name,
            if (c.brightness >= 0) "bright ${c.brightness}" else null,
            if (ins.feedback != 0) "fb ${ins.feedback}" else null,
            if (ins.sweep.active) "sweep ${Opl3Rules.signed(ins.sweep.start)}→${Opl3Rules.signed(ins.sweep.end)}" else null,
        )
        return parts.joinToString(" · ")
    }
}
