package org.thortracker.system.opm

import kotlin.math.abs
import kotlin.math.ln
import org.thortracker.model.NumberText

/**
 * The OPM instrument editor's curated controls, in one place: the eight YM2151 algorithms with their routing, ratio sets
 * (multipliers with the fine detune DT1 and the coarse detune DT2, the OPM's inharmonic ratios), key scaling, the LFO
 * sensitivities, the noise generator and macros (brightness, level, attack, decay, tone decay, sustain, sustain rate,
 * release, feedback) that each read and write a fixed set of operator fields. Same rule as the Mega Drive's
 * ([org.thortracker.system.ym2612.YmCurated]):
 *
 * **Rule.** The operator registers ([OpmOperator], the Expert grid) are the instrument; every control is a view of
 * them. [controls] reads each control from the registers: a list control shows the entry the registers match, or —
 * when hand edits in the Expert grid match no entry — the nearest entry with its `exact` flag false (shown "≈"); a
 * macro reads a representative operator (the first carrier or modulator) and is `exact` when every operator it
 * writes agrees. Stepping a control (`with…`) writes its new value into exactly the fields it owns (listed per
 * function) and leaves every other field alone, so curated edits and Expert edits mix freely.
 */
object OpmCurated {
    /** "½", "1" … "15". */
    fun multText(mult: Int): String = if (mult == 0) "½" else mult.toString()

    /** The multiplier's frequency factor (0 = ½). */
    fun multValue(mult: Int): Double = if (mult == 0) 0.5 else mult.toDouble()

    /** DT1 register values 0..7 as signed steps: 0, +1, +2, +3, 0, −1, −2, −3. */
    fun detuneSteps(dt1: Int): Int = when (dt1 and 7) {
        1, 2, 3 -> dt1 and 7
        5, 6, 7 -> -((dt1 and 7) - 4)
        else -> 0
    }

    /** "0", "+1" … "−3" for the Expert grid. */
    fun detuneText(dt1: Int): String = detuneSteps(dt1).let { if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0" }

    /** DT2 0..3 as the frequency factor the YM2151 application manual gives: ×1, ×1.41 (+600¢), ×1.57 (+781¢), ×1.73 (+950¢). */
    val DT2_FACTORS = listOf(1.0, 1.41, 1.57, 1.73)

    /** DT2 0..3 as text: "×1", "×1.41", "×1.57", "×1.73". */
    val DT2_TEXT = listOf("×1", "×1.41", "×1.57", "×1.73")

    /** An operator's frequency factor for drawings and texts: "×2", "×2(+1)", "×3·1.41" (DT2 as its factor). */
    fun ratioText(op: OpmOperator): String = "×" + multText(op.mul) +
        (if (detuneSteps(op.dt1) != 0) "(${detuneText(op.dt1)})" else "") +
        (if (op.dt2 != 0) "·" + DT2_TEXT[op.dt2 and 3].drop(1) else "")

    // ---- algorithms -----------------------------------------------------------------------------------------

    /**
     * YM2151 algorithm [number] (register $20 bits 0-2; the OPN family's eight): [name], a one-line [diagram] and its
     * routing for drawing: [edges] (modulator operator → modulated operator, 1-based, operators 1-4 = M1, C1, M2, C2)
     * and the [carriers] (1-based) summed to the output. Operator 1 always has the feedback loop.
     */
    data class Algorithm(val number: Int, val name: String, val diagram: String, val edges: List<Pair<Int, Int>>) {
        val carriers: List<Int> get() = (1..4).filter { OpmInstrument.carriers(number) shr (it - 1) and 1 != 0 }
    }

    val ALGORITHMS = listOf(
        Algorithm(0, "Chain", "1→2→3→4", listOf(1 to 2, 2 to 3, 3 to 4)),
        Algorithm(1, "Two into chain", "(1+2)→3→4", listOf(1 to 3, 2 to 3, 3 to 4)),
        Algorithm(2, "1 and chain into 4", "(1+(2→3))→4", listOf(1 to 4, 2 to 3, 3 to 4)),
        Algorithm(3, "Pair and 3 into 4", "((1→2)+3)→4", listOf(1 to 2, 2 to 4, 3 to 4)),
        Algorithm(4, "Two pairs", "(1→2)+(3→4)", listOf(1 to 2, 3 to 4)),
        Algorithm(5, "One into three", "1→(2+3+4)", listOf(1 to 2, 1 to 3, 1 to 4)),
        Algorithm(6, "Pair and two sines", "(1→2)+3+4", listOf(1 to 2)),
        Algorithm(7, "Additive", "1+2+3+4", emptyList()),
    )

    // ---- ratio sets (multipliers, DT1 and DT2, operators 1-4) --------------------------------------------------

    /**
     * Multiplier register values [mults], fine detunes [dt1s] (0..7) and coarse detunes [dt2s] (0..3) of operators 1-4;
     * [tag] names a detune pattern.
     */
    data class Ratio(
        val mults: List<Int>,
        val dt1s: List<Int> = listOf(0, 0, 0, 0),
        val dt2s: List<Int> = listOf(0, 0, 0, 0),
        val tag: String = "",
    ) {
        val name: String get() = mults.joinToString(":") { multText(it) } + if (tag.isEmpty()) "" else " $tag"

        /** Operator [op]'s frequency factor (multiplier × DT2 factor; DT1 ignored). */
        fun factor(op: Int): Double = multValue(mults[op]) * DT2_FACTORS[dt2s[op]]
    }

    /** Pairs drift apart: operators 1-2 up 3, 3-4 down 3 (a chorus on algorithm 4's two pairs). */
    private val CHORUS = listOf(3, 3, 7, 7)

    /** A slight shimmer: modulators detuned against plain carriers. */
    private val SHIMMER = listOf(2, 0, 6, 0)

    /**
     * Harmonic sets (the Mega Drive editor's, the same OPN-style operators) and, last, the OPM's own: DT2 puts operators
     * 1 and 3 (modulators in algorithms 0-4) at ×1.41, ×1.57 or ×1.73 of their multiplier — ratios no multiplier
     * reaches, the inharmonic partials of bells, metal and gongs.
     */
    val RATIOS: List<Ratio> = listOf(
        Ratio(listOf(1, 1, 1, 1)), Ratio(listOf(1, 1, 1, 1), CHORUS, tag = "chorus"), Ratio(listOf(2, 1, 1, 1)),
        Ratio(listOf(1, 1, 2, 1)), Ratio(listOf(2, 1, 2, 1)), Ratio(listOf(2, 1, 2, 1), CHORUS, tag = "chorus"),
        Ratio(listOf(3, 1, 1, 1)), Ratio(listOf(1, 1, 3, 1)), Ratio(listOf(3, 1, 3, 1)), Ratio(listOf(4, 1, 1, 1)),
        Ratio(listOf(1, 2, 1, 2)), Ratio(listOf(1, 1, 1, 2)), Ratio(listOf(2, 2, 1, 1)), Ratio(listOf(4, 2, 2, 1)),
        Ratio(listOf(0, 1, 1, 1)), Ratio(listOf(1, 0, 1, 1)), Ratio(listOf(0, 1, 0, 1), CHORUS, tag = "chorus"),
        Ratio(listOf(14, 1, 1, 1)), Ratio(listOf(1, 1, 14, 1), SHIMMER, tag = "shimmer"), Ratio(listOf(7, 3, 0, 1)),
        Ratio(listOf(3, 7, 1, 2)), Ratio(listOf(5, 1, 7, 1)), Ratio(listOf(8, 1, 2, 1)), Ratio(listOf(1, 3, 1, 5)),
        Ratio(listOf(15, 1, 9, 1)), Ratio(listOf(1, 2, 4, 8)),
        Ratio(listOf(1, 1, 1, 1), dt2s = listOf(2, 0, 2, 0), tag = "bell"),
        Ratio(listOf(3, 1, 2, 1), dt2s = listOf(1, 0, 2, 0), tag = "chime"),
        Ratio(listOf(2, 1, 3, 1), dt2s = listOf(1, 0, 3, 0), tag = "metal"),
        Ratio(listOf(1, 1, 1, 1), dt2s = listOf(3, 0, 1, 0), tag = "gong"),
        Ratio(listOf(5, 1, 1, 1), SHIMMER, listOf(3, 0, 2, 0), tag = "glass"),
        Ratio(listOf(1, 1, 1, 1), dt2s = listOf(1, 0, 1, 0), tag = "tritone"),
    )

    // ---- key scaling and LFO --------------------------------------------------------------------------------

    /** Key scaling (rate scaling KS 0..3 on every operator): how much faster envelopes run on higher notes. */
    val KEY_SCALES = listOf("Off", "Soft", "Medium", "Strong")

    /**
     * PMS 0..7: vibrato depth as the YM2151 application manual gives it at the song's full pitch depth (PMD 127); the
     * song's PMD (Song view, `Vxx`) scales it down proportionally.
     */
    val PMS_TEXT = listOf("Off", "±5¢", "±10¢", "±20¢", "±50¢", "±100¢", "±400¢", "±700¢")

    /**
     * AMS 0..3: the deepest tremolo of operators with AM on, as the application manual gives it at the song's full
     * amplitude depth (AMD 127); the song's AMD (Song view, `Txx`) scales it down proportionally.
     */
    val AMS_TEXT = listOf("Off", "23.9 dB", "47.8 dB", "95.6 dB")

    // ---- noise ----------------------------------------------------------------------------------------------

    /** The noise frequency a sound gets when noise is switched on (it had none: 0 is stored without noise). */
    const val DEFAULT_NOISE_FREQUENCY = 24

    /** The noise generator's rate at NFRQ [nfrq] (4 MHz: 4000000 / (32 · (32 − NFRQ)) Hz), as "15.6 kHz". */
    fun noiseText(nfrq: Int): String {
        val hz = OpmRules.noiseHz(nfrq.coerceIn(0, 31))
        return if (hz >= 1000.0) "${NumberText.fixed(hz / 1000.0, 1)} kHz" else "${hz.toInt()} Hz"
    }

    // ---- reading -------------------------------------------------------------------------------------------

    /**
     * Every curated control's current value read from [ins] (see the class rule). [algorithm] 0..7; [ratio] indexes
     * [RATIOS]; macros: brightness and level 0..127, attack/decay/tone decay/sustain rate 0..31, sustain and release
     * 0..15, feedback 0..7; [keyScale] indexes [KEY_SCALES]; [vibrato] = PMS, [tremolo] = AMS; [noise] and
     * [noiseFrequency] as stored.
     */
    data class Controls(
        val algorithm: Int,
        val ratio: Int,
        val ratioExact: Boolean,
        /** 127 − the lowest modulator level; -1 for algorithm 7 (no modulators). */
        val brightness: Int,
        /** 127 − the lowest carrier level. */
        val level: Int,
        val attack: Int,
        val decay: Int,
        /** Modulator first decay (how fast the brightness fades); -1 without modulators. */
        val toneDecay: Int,
        /** 15 − D1L: 15 = held at full level, 0 = decays to silence. */
        val sustain: Int,
        /** D2R: how fast held notes keep fading below the sustain level (0 = held). */
        val sustainRate: Int,
        val release: Int,
        /** True when every operator agrees with attack, decay, tone decay, sustain, sustain rate and release as read. */
        val envelopeExact: Boolean,
        val feedback: Int,
        val keyScale: Int,
        val keyScaleExact: Boolean,
        val vibrato: Int,
        val tremolo: Int,
        /** True when operator AM is on exactly the carriers while tremolo is on (and off everywhere when it is off). */
        val tremoloExact: Boolean,
        val noise: Boolean,
        val noiseFrequency: Int,
    )

    fun controls(ins: OpmInstrument): Controls {
        val ops = ins.operators
        val carriers = ops.indices.filter { ins.isCarrier(it) }
        val modulators = ops.indices.filter { !ins.isCarrier(it) }
        val c0 = ops[carriers.first()]
        val m0 = modulators.firstOrNull()?.let { ops[it] }
        val mults = ops.map { it.mul }
        val dt1s = ops.map { it.dt1 }
        val dt2s = ops.map { it.dt2 }
        fun matches(r: Ratio) = r.mults == mults && r.dt1s == dt1s && r.dt2s == dt2s
        val ratio = RATIOS.indexOfFirst { matches(it) }.takeIf { it >= 0 }
            ?: RATIOS.indices.minBy { i ->
                val r = RATIOS[i]
                (0..3).sumOf { k -> abs(ln(r.factor(k) / (multValue(mults[k]) * DT2_FACTORS[dt2s[k]]))) } +
                    0.01 * r.dt1s.zip(dt1s).count { (a, b) -> a != b }
            }
        val envelopeExact = ops.all { it.ar == c0.ar && it.rr == c0.rr && it.d1l == c0.d1l && it.d2r == c0.d2r } &&
            carriers.all { ops[it].d1r == c0.d1r } && modulators.all { ops[it].d1r == m0!!.d1r }
        val keyScale = c0.ks
        return Controls(
            algorithm = ins.algorithm,
            ratio = ratio, ratioExact = matches(RATIOS[ratio]),
            brightness = if (modulators.isEmpty()) -1 else 127 - modulators.minOf { ops[it].tl },
            level = 127 - carriers.minOf { ops[it].tl },
            attack = c0.ar, decay = c0.d1r, toneDecay = m0?.d1r ?: -1,
            sustain = 15 - c0.d1l, sustainRate = c0.d2r, release = c0.rr, envelopeExact = envelopeExact,
            feedback = ins.feedback,
            keyScale = keyScale, keyScaleExact = ops.all { it.ks == keyScale },
            vibrato = ins.pms, tremolo = ins.ams, tremoloExact = withTremolo(ins, ins.ams).operators == ops,
            noise = ins.noise, noiseFrequency = ins.noiseFrequency,
        )
    }

    // ---- writing -------------------------------------------------------------------------------------------

    /** Brightness of a new modulator when the instrument had none (from algorithm 7). */
    const val DEFAULT_BRIGHTNESS = 90

    /**
     * Algorithm [index] (0..7). Owns the algorithm; operator levels follow the new roles: an operator that keeps its
     * role keeps its level, a new carrier takes the old level (lowest carrier TL) and a new modulator the old
     * brightness (lowest modulator TL, or [DEFAULT_BRIGHTNESS] when there was none); then carriers and modulators are
     * shifted together so level and brightness read as before. The sound keeps its loudness and brightness; feedback
     * and every other field are kept.
     */
    fun withAlgorithm(ins: OpmInstrument, index: Int): OpmInstrument {
        val a = index.coerceIn(0, 7)
        if (a == ins.algorithm) return ins
        val c = controls(ins)
        val brightness = if (c.brightness < 0) DEFAULT_BRIGHTNESS else c.brightness
        val next = ins.copy(algorithm = a)
        val roles = next.mapOperators { i, op ->
            val was = ins.isCarrier(i)
            val now = next.isCarrier(i)
            when {
                was == now -> op
                now -> op.copy(tl = 127 - c.level)
                else -> op.copy(tl = 127 - brightness)
            }
        }
        return withBrightness(withLevel(roles, c.level), brightness)
    }

    /** Ratio set [index] of [RATIOS]. Owns every operator's MUL, DT1 and DT2. */
    fun withRatio(ins: OpmInstrument, index: Int): OpmInstrument {
        val r = RATIOS[index.coerceIn(RATIOS.indices)]
        return ins.mapOperators { i, op -> op.copy(mul = r.mults[i], dt1 = r.dt1s[i], dt2 = r.dt2s[i]) }
    }

    /**
     * Brightness 0..127. Owns the modulators' TL: all move by the same step so the lowest becomes 127 − [value] and
     * their differences stay (clamped 0..127). No effect without modulators.
     */
    fun withBrightness(ins: OpmInstrument, value: Int): OpmInstrument {
        val c = controls(ins)
        if (c.brightness < 0) return ins
        val delta = c.brightness - value.coerceIn(0, 127)
        return ins.mapOperators { i, op -> if (ins.isCarrier(i)) op else op.copy(tl = (op.tl + delta).coerceIn(0, 127)) }
    }

    /** Level 0..127. Owns the carriers' TL, shifted together as [withBrightness] shifts modulators. */
    fun withLevel(ins: OpmInstrument, value: Int): OpmInstrument {
        val delta = controls(ins).level - value.coerceIn(0, 127)
        return ins.mapOperators { i, op -> if (ins.isCarrier(i)) op.copy(tl = (op.tl + delta).coerceIn(0, 127)) else op }
    }

    /** Attack 0..31 (31 = instant). Owns every operator's AR. */
    fun withAttack(ins: OpmInstrument, value: Int): OpmInstrument = ins.mapOperators { _, op -> op.copy(ar = value.coerceIn(0, 31)) }

    /** Decay 0..31 (31 = fastest) toward the sustain level. Owns the carriers' D1R. */
    fun withDecay(ins: OpmInstrument, value: Int): OpmInstrument =
        ins.mapOperators { i, op -> if (ins.isCarrier(i)) op.copy(d1r = value.coerceIn(0, 31)) else op }

    /** Tone decay 0..31: how fast the modulators (brightness) fade. Owns the modulators' D1R; none without modulators. */
    fun withToneDecay(ins: OpmInstrument, value: Int): OpmInstrument =
        ins.mapOperators { i, op -> if (ins.isCarrier(i)) op else op.copy(d1r = value.coerceIn(0, 31)) }

    /** Sustain 0..15: the level held notes settle at, 15 = full, 0 = silent (a percussive sound). Owns every D1L. */
    fun withSustain(ins: OpmInstrument, value: Int): OpmInstrument =
        ins.mapOperators { _, op -> op.copy(d1l = 15 - value.coerceIn(0, 15)) }

    /** Sustain rate 0..31: held notes keep fading below the sustain level (0 = held, like a pad). Owns every D2R. */
    fun withSustainRate(ins: OpmInstrument, value: Int): OpmInstrument =
        ins.mapOperators { _, op -> op.copy(d2r = value.coerceIn(0, 31)) }

    /** Release 0..15 (15 = fastest) after key off. Owns every operator's RR. */
    fun withRelease(ins: OpmInstrument, value: Int): OpmInstrument = ins.mapOperators { _, op -> op.copy(rr = value.coerceIn(0, 15)) }

    /** Feedback 0..7 of operator 1. Owns the instrument's feedback. */
    fun withFeedback(ins: OpmInstrument, value: Int): OpmInstrument = ins.copy(feedback = value.coerceIn(0, 7))

    /** Key scaling [index] of [KEY_SCALES]: KS = index on every operator. Owns every KS. */
    fun withKeyScale(ins: OpmInstrument, index: Int): OpmInstrument =
        ins.mapOperators { _, op -> op.copy(ks = index.coerceIn(KEY_SCALES.indices)) }

    /** Vibrato depth 0..7 (PMS; heard while the song's LFO pitch depth PMD is above 0). Owns PMS. */
    fun withVibrato(ins: OpmInstrument, value: Int): OpmInstrument = ins.copy(pms = value.coerceIn(0, 7))

    /**
     * Tremolo depth 0..3 (AMS; heard while the song's LFO amplitude depth AMD is above 0): AM on the carriers when on,
     * off everywhere when 0. Owns AMS and every operator's AM.
     */
    fun withTremolo(ins: OpmInstrument, value: Int): OpmInstrument {
        val v = value.coerceIn(0, 3)
        return ins.copy(ams = v).mapOperators { i, op -> op.copy(am = v > 0 && ins.isCarrier(i)) }
    }

    /**
     * Noise on or off: operator 4 plays the chip's noise generator instead of its sine (FM 8 only). Owns the noise flag
     * and the noise frequency: switching on sets [DEFAULT_NOISE_FREQUENCY], switching off clears it (a sound without
     * noise stores 0).
     */
    fun withNoise(ins: OpmInstrument, on: Boolean): OpmInstrument = when {
        on == ins.noise -> ins
        on -> ins.copy(noise = true, noiseFrequency = DEFAULT_NOISE_FREQUENCY)
        else -> ins.copy(noise = false, noiseFrequency = 0)
    }

    /** Noise frequency NFRQ 0..31 (31 highest). Owns the noise frequency; no effect without noise. */
    fun withNoiseFrequency(ins: OpmInstrument, value: Int): OpmInstrument =
        if (!ins.noise) ins else ins.copy(noiseFrequency = value.coerceIn(0, 31))

    // ---- summaries ---------------------------------------------------------------------------------------------

    /** One line for lists: "Two pairs · 1:1:1:1 bell · bright 82 · fb 2" or "Additive · … · noise 15.6 kHz". */
    fun summary(ins: OpmInstrument): String {
        val c = controls(ins)
        return listOfNotNull(
            ALGORITHMS[ins.algorithm.coerceIn(0, 7)].name,
            (if (c.ratioExact) "" else "≈") + RATIOS[c.ratio].name,
            if (c.brightness >= 0) "bright ${c.brightness}" else null,
            if (ins.feedback != 0) "fb ${ins.feedback}" else null,
            if (ins.noise) "noise ${noiseText(ins.noiseFrequency)} (FM 8)" else null,
            if (ins.sweep.active) "sweep ${signed(ins.sweep.start)}→${signed(ins.sweep.end)}" else null,
        ).joinToString(" · ")
    }

    /** "+3", "0", "-5". */
    fun signed(v: Int): String = if (v > 0) "+$v" else "$v"
}
