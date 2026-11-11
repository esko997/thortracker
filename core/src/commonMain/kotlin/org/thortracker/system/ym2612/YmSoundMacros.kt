package org.thortracker.system.ym2612

/** These are projections of the actual patch, never a second preset or hidden musical state. */
internal enum class YmSoundControl(val label: String, val maximum: Int) {
    BRIGHTNESS("Brightness", 127), STRUCTURE("Structure", 7), HARMONICS("Harmonics", 4),
    EDGE("Edge", 7), CONTOUR("Contour", 3),
}

internal object YmSoundMacros {
    private val harmonics = intArrayOf(1, 2, 3, 5, 7)
    private val harmonicNames = arrayOf("Round", "Hollow", "Reedy", "Metal", "Glass")
    private val structures = arrayOf("Deep chain", "Twin core", "Split core", "Layered core", "Twin voices", "Triple voices", "Tone + sines", "Four sines")
    private val contours = arrayOf("Pluck", "Held", "Bloom", "Short")
    private data class Contour(val ar: Int, val d1r: Int, val sl: Int, val d2r: Int, val rr: Int)
    private val shapes = arrayOf(Contour(31, 12, 8, 7, 7), Contour(31, 0, 0, 0, 7), Contour(12, 0, 0, 0, 5), Contour(31, 22, 15, 12, 12))

    fun enabled(control: YmSoundControl, ins: YmFmInstrument): Boolean = when (control) {
        YmSoundControl.BRIGHTNESS, YmSoundControl.HARMONICS -> ins.algorithm != 7
        // SSG envelopes can invert/repeat/hold. A conventional output contour would be a false description.
        YmSoundControl.CONTOUR -> ins.operators.indices.none { ins.isCarrier(it) && ins.operators[it].ssgEg and 8 != 0 }
        else -> true
    }

    /** Null is Custom (or unavailable), not a nearby preset falsely presented as the current sound. */
    fun value(control: YmSoundControl, ins: YmFmInstrument): Int? {
        if (!enabled(control, ins)) return null
        return when (control) {
            YmSoundControl.STRUCTURE -> ins.algorithm
            YmSoundControl.EDGE -> ins.feedback
            YmSoundControl.BRIGHTNESS -> {
                var lowest = 127
                for (i in 0..3) if (!ins.isCarrier(i)) lowest = minOf(lowest, ins.operators[i].tl)
                127 - lowest
            }
            YmSoundControl.HARMONICS -> {
                harmonics.indices.firstOrNull { h ->
                    ins.operators.indices.all { ins.isCarrier(it) || ins.operators[it].mul == harmonics[h] }
                }
            }
            YmSoundControl.CONTOUR -> shapes.indices.firstOrNull { s ->
                val shape = shapes[s]
                ins.operators.indices.all { i ->
                    val o = ins.operators[i]
                    !ins.isCarrier(i) || (o.ar == shape.ar && o.d1r == shape.d1r && o.sl == shape.sl && o.d2r == shape.d2r && o.rr == shape.rr)
                }
            }
        }
    }

    fun label(control: YmSoundControl, value: Int): String = when (control) {
        YmSoundControl.STRUCTURE -> structures[value]
        YmSoundControl.HARMONICS -> harmonicNames[value]
        YmSoundControl.CONTOUR -> contours[value]
        YmSoundControl.BRIGHTNESS -> "$value"
        YmSoundControl.EDGE -> "$value"
    }

    /**
     * Explicit ownership:
     * Structure: routing and role-aware TL balance, using the existing curated algorithm transition.
     * Brightness: modulator TL only, preserving inter-modulator differences until a register limit is reached.
     * Harmonics: modulator MUL only. Carrier pitch, all DT and CH3 tuning are deliberately retained.
     * Edge: feedback only (operator 1's real feedback, including when it is an additive carrier).
     * Contour: carrier AR/D1R/SL/D2R/RR only. Modulator envelopes, KS, AM and SSG-EG stay untouched.
     * Everything else, including name, sweep, AMS/FMS and CH3 offsets, is preserved in every case.
     */
    fun apply(control: YmSoundControl, ins: YmFmInstrument, value: Int): YmFmInstrument {
        if (!enabled(control, ins) || value !in 0..control.maximum || YmSoundMacros.value(control, ins) == value) return ins
        return when (control) {
            YmSoundControl.STRUCTURE -> YmCurated.withAlgorithm(ins, value)
            YmSoundControl.BRIGHTNESS -> YmCurated.withBrightness(ins, value)
            YmSoundControl.HARMONICS -> ins.mapOperators { i, op -> if (ins.isCarrier(i)) op else op.copy(mul = harmonics[value]) }
            YmSoundControl.EDGE -> YmCurated.withFeedback(ins, value)
            YmSoundControl.CONTOUR -> {
                val s = shapes[value]
                ins.mapOperators { i, op -> if (!ins.isCarrier(i)) op else op.copy(ar = s.ar, d1r = s.d1r, sl = s.sl, d2r = s.d2r, rr = s.rr) }
            }
        }
    }

    fun step(control: YmSoundControl, ins: YmFmInstrument, delta: Int): YmFmInstrument {
        if (delta == 0) return ins
        val old = value(control, ins)
        val next = if (old == null) (if (delta > 0) 0 else control.maximum) else (old + delta).coerceIn(0, control.maximum)
        return apply(control, ins, next)
    }
}
