package org.thortracker.system.opm

/** The workstation's texture section owns modulators; its contour section owns carriers. Shared LFO is untouched. */
internal object OpmSoundMacros {
    val labels = arrayOf("Colour", "Alloy", "Contour", "Bite", "Noise", "Fall")
    val maxima = intArrayOf(127, 3, 3, 7, 32, 3)
    val hints = arrayOf("Modulator strength, not output loudness", "Modulator detuning; carrier tuning stays intact",
        "Carrier envelope recipe; Custom remains exact until chosen", "Operator one's feedback; can change loudness",
        "Noise replaces operator 4 on FM 8 only", "Pitch sweep only; existing LFO and envelopes stay intact")
    private val contours = arrayOf("Held", "Pluck", "Chime", "Bloom")
    private val alloy = arrayOf("Harmonic", "Glass", "Metal", "Ceramic")
    private data class Envelope(val ar: Int, val d1r: Int, val d2r: Int, val d1l: Int, val rr: Int)
    private val envelopes = arrayOf(Envelope(31, 0, 0, 0, 8), Envelope(31, 20, 9, 8, 12),
        Envelope(31, 10, 6, 10, 7), Envelope(14, 0, 0, 0, 6))
    private val sweeps = arrayOf(OpmSweep.NONE, OpmSweep(12, 0, 32), OpmSweep(24, 0, 64), OpmSweep(36, 0, 96))
    private val dropNames = arrayOf("None", "Tap", "Tom", "Kick")
    fun enabled(ins: OpmInstrument, control: Int): Boolean = control !in 0..1 || ins.algorithm != 7
    private fun colour(ins: OpmInstrument): Int? {
        var level = -1
        for (i in 0..3) if (!ins.isCarrier(i)) level = maxOf(level, 127 - ins.operators[i].tl)
        return level.takeIf { it >= 0 }
    }
    private fun uniform(ins: OpmInstrument, carrier: Boolean, get: (OpmOperator) -> Int): Int? {
        var found = -1
        for (i in 0..3) if (ins.isCarrier(i) == carrier) {
            val n = get(ins.operators[i]); if (found >= 0 && found != n) return null; found = n
        }
        return found.takeIf { it >= 0 }
    }
    fun value(ins: OpmInstrument, control: Int): Int? = when (control) {
        0 -> colour(ins)
        1 -> uniform(ins, false) { it.dt2 }
        2 -> envelopes.indexOfFirst { e -> (0..3).all { i -> !ins.isCarrier(i) || ins.operators[i].let {
            it.ar == e.ar && it.d1r == e.d1r && it.d2r == e.d2r && it.d1l == e.d1l && it.rr == e.rr
        } } }.takeIf { it >= 0 }
        3 -> ins.feedback
        4 -> if (ins.noise) ins.noiseFrequency + 1 else 0
        else -> sweeps.indexOf(ins.sweep).takeIf { it >= 0 }
    }
    fun text(control: Int, value: Int?): String = if (value == null) "Custom" else when (control) {
        1 -> alloy[value]; 2 -> contours[value]
        4 -> if (value == 0) "Sine / all channels" else "Noise ${value - 1} / FM 8"
        5 -> dropNames[value]
        else -> "$value / ${maxima[control]}"
    }
    fun set(ins: OpmInstrument, control: Int, value: Int): OpmInstrument {
        if (!enabled(ins, control)) return ins
        val n = value.coerceIn(0, maxima[control])
        if (value(ins, control) == n) return ins
        return when (control) {
            0 -> {
                val delta = n - (colour(ins) ?: n)
                ins.mapOperators { i, op -> if (!ins.isCarrier(i)) op.copy(tl = (op.tl - delta).coerceIn(0, 127)) else op }
            }
            1 -> ins.mapOperators { i, op -> if (!ins.isCarrier(i)) op.copy(dt2 = n) else op }
            2 -> envelopes[n].let { e -> ins.mapOperators { i, op -> if (ins.isCarrier(i))
                op.copy(ar = e.ar, d1r = e.d1r, d2r = e.d2r, d1l = e.d1l, rr = e.rr) else op } }
            3 -> ins.copy(feedback = n)
            4 -> if (n == 0) ins.copy(noise = false) else ins.copy(noise = true, noiseFrequency = n - 1)
            else -> ins.copy(sweep = sweeps[n])
        }
    }
}
