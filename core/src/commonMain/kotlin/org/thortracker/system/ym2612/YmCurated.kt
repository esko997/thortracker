package org.thortracker.system.ym2612

import kotlin.math.abs
import kotlin.math.ln

/**
 * The Mega Drive FM instrument editor's curated controls, in one place: the eight YM2612 algorithms with their routing,
 * ratio sets (multipliers with detune), SSG-EG presets, key scaling, the LFO depths and macros (brightness, level,
 * attack, decay, tone decay, sustain, sustain rate, release, feedback) that each read and write a fixed set of
 * operator fields. Same rule as the OPL3's ([org.thortracker.system.opl3.Opl3Curated]):
 *
 * **Rule.** The operator registers ([YmOperator], the Expert grid) are the instrument; every control is a view of
 * them. [controls] reads each control from the registers: a list control shows the entry the registers match, or —
 * when hand edits in the Expert grid match no entry — the nearest entry with its `exact` flag false (shown "≈"); a
 * macro reads a representative operator (the first carrier or modulator) and is `exact` when every operator it
 * writes agrees. Stepping a control (`with…`) writes its new value into exactly the fields it owns (listed per
 * function) and leaves every other field alone, so curated edits and Expert edits mix freely.
 */
object YmCurated {
    /** "½", "1" … "15". */
    fun multText(mult: Int): String = if (mult == 0) "½" else mult.toString()

    /** The multiplier's frequency factor (0 = ½). */
    fun multValue(mult: Int): Double = if (mult == 0) 0.5 else mult.toDouble()

    /** Detune register values 0..7 as signed steps: 0, +1, +2, +3, 0, −1, −2, −3. */
    fun detuneSteps(dt: Int): Int = when (dt and 7) {
        1, 2, 3 -> dt
        5, 6, 7 -> -(dt - 4)
        else -> 0
    }

    /** "0", "+1" … "−3" for the Expert grid. */
    fun detuneText(dt: Int): String = detuneSteps(dt).let { if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0" }

    // ---- algorithms -----------------------------------------------------------------------------------------

    /**
     * YM2612 algorithm [number] (register $B0 bits 0-2): [name], a one-line [diagram] and its routing for drawing:
     * [edges] (modulator operator → modulated operator, 1-based) and the [carriers] (1-based) summed to the output.
     * Operator 1 always has the feedback loop.
     */
    data class Algorithm(val number: Int, val name: String, val diagram: String, val edges: List<Pair<Int, Int>>) {
        val carriers: List<Int> get() = (1..4).filter { YmFmInstrument.carriers(number) shr (it - 1) and 1 != 0 }
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

    // ---- ratio sets (multipliers and detunes, operators 1-4) -------------------------------------------------

    /** Multiplier register values [mults] and detunes [dts] (0..7) of operators 1-4; [tag] names a detune pattern. */
    data class Ratio(val mults: List<Int>, val dts: List<Int> = listOf(0, 0, 0, 0), val tag: String = "") {
        val name: String get() = mults.joinToString(":") { multText(it) } + if (tag.isEmpty()) "" else " $tag"
    }

    /** Pairs drift apart: operators 1-2 up 3, 3-4 down 3 (a chorus on algorithm 4's two pairs). */
    private val CHORUS = listOf(3, 3, 7, 7)

    /** A slight shimmer: modulators detuned against plain carriers. */
    private val SHIMMER = listOf(2, 0, 6, 0)

    val RATIOS: List<Ratio> = listOf(
        Ratio(listOf(1, 1, 1, 1)), Ratio(listOf(1, 1, 1, 1), CHORUS, "chorus"), Ratio(listOf(2, 1, 1, 1)),
        Ratio(listOf(1, 1, 2, 1)), Ratio(listOf(2, 1, 2, 1)), Ratio(listOf(2, 1, 2, 1), CHORUS, "chorus"),
        Ratio(listOf(3, 1, 1, 1)), Ratio(listOf(1, 1, 3, 1)), Ratio(listOf(3, 1, 3, 1)), Ratio(listOf(4, 1, 1, 1)),
        Ratio(listOf(1, 2, 1, 2)), Ratio(listOf(1, 1, 1, 2)), Ratio(listOf(2, 2, 1, 1)), Ratio(listOf(4, 2, 2, 1)),
        Ratio(listOf(0, 1, 1, 1)), Ratio(listOf(1, 0, 1, 1)), Ratio(listOf(0, 1, 0, 1), CHORUS, "chorus"),
        Ratio(listOf(14, 1, 1, 1)), Ratio(listOf(1, 1, 14, 1), SHIMMER, "shimmer"), Ratio(listOf(7, 3, 0, 1)),
        Ratio(listOf(3, 7, 1, 2)), Ratio(listOf(5, 1, 7, 1)), Ratio(listOf(8, 1, 2, 1)), Ratio(listOf(1, 3, 1, 5)),
        Ratio(listOf(15, 1, 9, 1)), Ratio(listOf(1, 2, 4, 8)),
    )

    // ---- SSG-EG ------------------------------------------------------------------------------------------------

    /** The SSG-EG register's shapes, values 8..15 (0 = off; 1-7 are not used), as their envelope outlines. */
    val SSG_EG_NAMES: Map<Int, String> = mapOf(
        0 to "Off", 8 to "\\\\\\\\ repeat", 9 to "\\___ once", 10 to "\\/\\/ bounce", 11 to "\\‾‾‾ once, high",
        12 to "//// repeat up", 13 to "/‾‾‾ up, hold", 14 to "/\\/\\ bounce up", 15 to "/___ up, low",
    )

    /** An SSG-EG choice: the mode [modulators] and [carriers] get (0 = off). */
    data class Ssg(val name: String, val modulators: Int, val carriers: Int)

    /**
     * SSG-EG presets: off, repeating shapes on the modulators (the tone cycles: wah, buzz) and on the carriers (the
     * volume cycles or holds). Values are register $90's.
     */
    val SSG_PRESETS = listOf(
        Ssg("Off", 0, 0),
        Ssg("Tone saw", 8, 0), Ssg("Tone bounce", 10, 0), Ssg("Tone rise", 12, 0), Ssg("Tone bounce up", 14, 0),
        Ssg("Pulse saw", 0, 8), Ssg("Pulse bounce", 0, 10), Ssg("Pulse rise", 0, 12), Ssg("Swell and hold", 0, 13),
        Ssg("Hold high", 0, 11), Ssg("All bounce", 10, 10),
    )

    // ---- key scaling and LFO --------------------------------------------------------------------------------

    /** Key scaling (rate scaling KS 0..3 on every operator): how much faster envelopes run on higher notes. */
    val KEY_SCALES = listOf("Off", "Soft", "Medium", "Strong")

    /** AMS 0..3: tremolo depth of operators with AM on. */
    val AMS_TEXT = listOf("Off", "1.4 dB", "5.9 dB", "11.8 dB")

    /** FMS 0..7: vibrato depth. */
    val FMS_TEXT = listOf("Off", "±3.4¢", "±6.7¢", "±10¢", "±14¢", "±20¢", "±40¢", "±80¢")

    // ---- reading -------------------------------------------------------------------------------------------

    /**
     * Every curated control's current value read from [ins] (see the class rule). [algorithm] 0..7; [ratio] and [ssg]
     * index [RATIOS] and [SSG_PRESETS]; macros: brightness and level 0..127, attack/decay/tone decay/sustain rate
     * 0..31, sustain and release 0..15, feedback 0..7; [keyScale] indexes [KEY_SCALES]; [vibrato] = FMS, [tremolo] = AMS.
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
        /** 15 − SL: 15 = held at full level, 0 = decays to silence. */
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
        val ssg: Int,
        val ssgExact: Boolean,
    )

    fun controls(ins: YmFmInstrument): Controls {
        val ops = ins.operators
        val carriers = ops.indices.filter { ins.isCarrier(it) }
        val modulators = ops.indices.filter { !ins.isCarrier(it) }
        val c0 = ops[carriers.first()]
        val m0 = modulators.firstOrNull()?.let { ops[it] }
        val mults = ops.map { it.mul }
        val dts = ops.map { it.dt }
        val ratio = RATIOS.indexOfFirst { it.mults == mults && it.dts == dts }.takeIf { it >= 0 }
            ?: RATIOS.indices.minBy { i ->
                RATIOS[i].mults.zip(mults).sumOf { (a, b) -> abs(ln(multValue(a) / multValue(b))) } +
                    0.01 * RATIOS[i].dts.zip(dts).count { (a, b) -> a != b }
            }
        val envelopeExact = ops.all { it.ar == c0.ar && it.rr == c0.rr && it.sl == c0.sl && it.d2r == c0.d2r } &&
            carriers.all { ops[it].d1r == c0.d1r } && modulators.all { ops[it].d1r == m0!!.d1r }
        val keyScale = c0.ks
        val ssg = ssgOf(ins)
        return Controls(
            algorithm = ins.algorithm,
            ratio = ratio, ratioExact = RATIOS[ratio].let { it.mults == mults && it.dts == dts },
            brightness = if (modulators.isEmpty()) -1 else 127 - modulators.minOf { ops[it].tl },
            level = 127 - carriers.minOf { ops[it].tl },
            attack = c0.ar, decay = c0.d1r, toneDecay = m0?.d1r ?: -1,
            sustain = 15 - c0.sl, sustainRate = c0.d2r, release = c0.rr, envelopeExact = envelopeExact,
            feedback = ins.feedback,
            keyScale = keyScale, keyScaleExact = ops.all { it.ks == keyScale },
            vibrato = ins.fms, tremolo = ins.ams, tremoloExact = withTremolo(ins, ins.ams).operators == ops,
            ssg = ssg ?: nearestSsg(ins), ssgExact = ssg != null,
        )
    }

    private fun ssgOf(ins: YmFmInstrument): Int? = SSG_PRESETS.indices.firstOrNull { withSsg(ins, it).operators == ins.operators }

    private fun nearestSsg(ins: YmFmInstrument): Int = SSG_PRESETS.indices.maxBy { k ->
        withSsg(ins, k).operators.zip(ins.operators).count { (a, b) -> a.ssgEg == b.ssgEg } * 10 - k
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
    fun withAlgorithm(ins: YmFmInstrument, index: Int): YmFmInstrument {
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

    /** Ratio set [index] of [RATIOS]. Owns every operator's MUL and DT. */
    fun withRatio(ins: YmFmInstrument, index: Int): YmFmInstrument {
        val r = RATIOS[index.coerceIn(RATIOS.indices)]
        return ins.mapOperators { i, op -> op.copy(mul = r.mults[i], dt = r.dts[i]) }
    }

    /**
     * Brightness 0..127. Owns the modulators' TL: all move by the same step so the lowest becomes 127 − [value] and
     * their differences stay (clamped 0..127). No effect without modulators.
     */
    fun withBrightness(ins: YmFmInstrument, value: Int): YmFmInstrument {
        val c = controls(ins)
        if (c.brightness < 0) return ins
        val delta = c.brightness - value.coerceIn(0, 127)
        return ins.mapOperators { i, op -> if (ins.isCarrier(i)) op else op.copy(tl = (op.tl + delta).coerceIn(0, 127)) }
    }

    /** Level 0..127. Owns the carriers' TL, shifted together as [withBrightness] shifts modulators. */
    fun withLevel(ins: YmFmInstrument, value: Int): YmFmInstrument {
        val delta = controls(ins).level - value.coerceIn(0, 127)
        return ins.mapOperators { i, op -> if (ins.isCarrier(i)) op.copy(tl = (op.tl + delta).coerceIn(0, 127)) else op }
    }

    /** Attack 0..31 (31 = instant). Owns every operator's AR. */
    fun withAttack(ins: YmFmInstrument, value: Int): YmFmInstrument = ins.mapOperators { _, op -> op.copy(ar = value.coerceIn(0, 31)) }

    /** Decay 0..31 (31 = fastest) toward the sustain level. Owns the carriers' D1R. */
    fun withDecay(ins: YmFmInstrument, value: Int): YmFmInstrument =
        ins.mapOperators { i, op -> if (ins.isCarrier(i)) op.copy(d1r = value.coerceIn(0, 31)) else op }

    /** Tone decay 0..31: how fast the modulators (brightness) fade. Owns the modulators' D1R; none without modulators. */
    fun withToneDecay(ins: YmFmInstrument, value: Int): YmFmInstrument =
        ins.mapOperators { i, op -> if (ins.isCarrier(i)) op else op.copy(d1r = value.coerceIn(0, 31)) }

    /** Sustain 0..15: the level held notes settle at, 15 = full, 0 = silent (a percussive sound). Owns every SL. */
    fun withSustain(ins: YmFmInstrument, value: Int): YmFmInstrument =
        ins.mapOperators { _, op -> op.copy(sl = 15 - value.coerceIn(0, 15)) }

    /** Sustain rate 0..31: held notes keep fading below the sustain level (0 = held, like a pad). Owns every D2R. */
    fun withSustainRate(ins: YmFmInstrument, value: Int): YmFmInstrument =
        ins.mapOperators { _, op -> op.copy(d2r = value.coerceIn(0, 31)) }

    /** Release 0..15 (15 = fastest) after key off. Owns every operator's RR. */
    fun withRelease(ins: YmFmInstrument, value: Int): YmFmInstrument = ins.mapOperators { _, op -> op.copy(rr = value.coerceIn(0, 15)) }

    /** Feedback 0..7 of operator 1. Owns the instrument's feedback. */
    fun withFeedback(ins: YmFmInstrument, value: Int): YmFmInstrument = ins.copy(feedback = value.coerceIn(0, 7))

    /** Key scaling [index] of [KEY_SCALES]: KS = index on every operator. Owns every KS. */
    fun withKeyScale(ins: YmFmInstrument, index: Int): YmFmInstrument =
        ins.mapOperators { _, op -> op.copy(ks = index.coerceIn(KEY_SCALES.indices)) }

    /** Vibrato depth 0..7 (FMS; heard while the song's LFO is on). Owns FMS. */
    fun withVibrato(ins: YmFmInstrument, value: Int): YmFmInstrument = ins.copy(fms = value.coerceIn(0, 7))

    /**
     * Tremolo depth 0..3 (AMS; heard while the song's LFO is on): AM on the carriers when on, off everywhere when 0.
     * Owns AMS and every operator's AM.
     */
    fun withTremolo(ins: YmFmInstrument, value: Int): YmFmInstrument {
        val v = value.coerceIn(0, 3)
        return ins.copy(ams = v).mapOperators { i, op -> op.copy(am = v > 0 && ins.isCarrier(i)) }
    }

    /** SSG-EG preset [index] of [SSG_PRESETS]. Owns every operator's SSG-EG. */
    fun withSsg(ins: YmFmInstrument, index: Int): YmFmInstrument {
        val s = SSG_PRESETS[index.coerceIn(SSG_PRESETS.indices)]
        return ins.mapOperators { i, op -> op.copy(ssgEg = if (ins.isCarrier(i)) s.carriers else s.modulators) }
    }

    // ---- summaries ---------------------------------------------------------------------------------------------

    /** One line for lists: "Two pairs · 14:1:1:1 · bright 82 · fb 2", "PSG · vol 12 steps · arp · white /1024" or "PCM kit · 3 keys · level 15". */
    fun summary(ins: YmInstrument): String = when (ins) {
        is YmFmInstrument -> {
            val c = controls(ins)
            listOfNotNull(
                ALGORITHMS[ins.algorithm.coerceIn(0, 7)].name,
                (if (c.ratioExact) "" else "≈") + RATIOS[c.ratio].name,
                if (c.brightness >= 0) "bright ${c.brightness}" else null,
                if (ins.feedback != 0) "fb ${ins.feedback}" else null,
                if (c.ssg != 0 || !c.ssgExact) "SSG-EG" else null,
                if (ins.sweep.active) "sweep ${YmRules.signed(ins.sweep.start)}→${YmRules.signed(ins.sweep.end)}" else null,
                if (ins.hasCh3) "FM 3 operator pitches" else null,
            ).joinToString(" · ")
        }
        is YmPsgInstrument -> listOfNotNull(
            "PSG",
            if (ins.volume.values.isEmpty()) "vol 15" else "vol ${ins.volume.values.size} step${if (ins.volume.values.size == 1) "" else "s"}" +
                (if (ins.volume.loop >= 0) " loop" else ""),
            if (ins.arpeggio.values.isNotEmpty()) "arp" else null,
            if (ins.pitch.values.isNotEmpty()) "pitch" else null,
            "noise ${YmRules.noiseText(ins.noise)}",
            if (ins.sweep.active) "sweep ${YmRules.signed(ins.sweep.start)}→${YmRules.signed(ins.sweep.end)}" else null,
        ).joinToString(" · ")
        is YmPcmInstrument -> listOf(
            "PCM kit",
            if (ins.pcm.isEmpty()) "no keys yet" else "${ins.pcm.size} key${if (ins.pcm.size == 1) "" else "s"}",
            "level ${ins.level}",
        ).joinToString(" · ")
    }
}
