package org.thortracker.system.opl3

/** Organ registration, not a second patch model. Every gesture owns only the fields documented here. */
internal object Opl3SoundMacros {
    val labels = arrayOf("Voicing", "Drawbar", "Reed", "Contour", "Release", "Drop")
    val hints = arrayOf("Routing only; operators keep their settings", "Modulator strength; output levels stay intact",
        "Carrier waveform; harmonics without retuning", "Output envelope only; Custom is never guessed",
        "Output tail after note off", "Explicit pitch-drop recipe; Advanced keeps custom sweeps")
    val maxima = intArrayOf(5, 63, 7, 3, 15, 3)
    private val voicings = arrayOf("Two: serial", "Two: blend", "Four: chain", "Four: pairs", "Four: reed + chain", "Four: choir")
    private val waves = arrayOf("Round", "Hollow", "Bright", "Reedy", "Double", "Thin", "Square", "Buzzy")
    private val contours = arrayOf("Organ", "Pluck", "Bell", "Swell")
    private val drops = arrayOf(Opl3Sweep.NONE, Opl3Sweep(12, 0, 32), Opl3Sweep(24, 0, 64), Opl3Sweep(36, 0, 96))
    private val dropNames = arrayOf("None", "Tap", "Tom", "Kick")
    private data class Envelope(val ar: Int, val dr: Int, val sl: Int, val sustain: Boolean)
    private val envelopes = arrayOf(Envelope(15, 0, 0, true), Envelope(15, 9, 8, false),
        Envelope(15, 5, 12, false), Envelope(7, 0, 0, true))

    private fun drawbar(ins: Opl3Instrument): Int? {
        var level = -1
        for (i in 0 until ins.operatorCount) if (!ins.isCarrier(i)) level = maxOf(level, 63 - ins.operators[i].tl)
        return level.takeIf { it >= 0 }
    }
    fun enabled(ins: Opl3Instrument, control: Int): Boolean = control != 1 || (0 until ins.operatorCount).any { !ins.isCarrier(it) }
    private fun uniform(ins: Opl3Instrument, carrier: Boolean, get: (Opl3Operator) -> Int): Int? {
        var found = -1
        for (i in 0 until ins.operatorCount) if (ins.isCarrier(i) == carrier) {
            val n = get(ins.operators[i])
            if (found >= 0 && found != n) return null
            found = n
        }
        return found.takeIf { it >= 0 }
    }
    fun value(ins: Opl3Instrument, control: Int): Int? = when (control) {
        0 -> if (ins.fourOp) 2 + ins.algorithm else ins.algorithm
        1 -> drawbar(ins)
        2 -> uniform(ins, true) { it.wave }
        3 -> envelopes.indexOfFirst { e -> (0 until ins.operatorCount).all { i -> !ins.isCarrier(i) || ins.operators[i].let {
            it.ar == e.ar && it.dr == e.dr && it.sl == e.sl && it.sustain == e.sustain
        } } }.takeIf { it >= 0 }
        4 -> uniform(ins, true) { 15 - it.rr }
        else -> drops.indexOf(ins.sweep).takeIf { it >= 0 }
    }
    fun text(control: Int, value: Int?): String = if (value == null) "Custom" else when (control) {
        0 -> voicings[value]; 2 -> waves[value]; 3 -> contours[value]
        4 -> if (value == 0) "Short" else if (value == 15) "Long" else "$value / 15"
        5 -> dropNames[value]
        else -> "$value / 63"
    }
    fun set(ins: Opl3Instrument, control: Int, value: Int): Opl3Instrument {
        if (!enabled(ins, control)) return ins
        val n = value.coerceIn(0, maxima[control])
        if (value(ins, control) == n) return ins
        return when (control) {
            0 -> ins.copy(fourOp = n >= 2, algorithm = if (n >= 2) n - 2 else n)
            1 -> {
                val delta = n - (drawbar(ins) ?: n)
                ins.mapOperators { i, op -> if (!ins.isCarrier(i)) op.copy(tl = (op.tl - delta).coerceIn(0, 63)) else op }
            }
            2 -> ins.mapOperators { i, op -> if (ins.isCarrier(i)) op.copy(wave = n) else op }
            3 -> envelopes[n].let { e -> ins.mapOperators { i, op -> if (ins.isCarrier(i))
                op.copy(ar = e.ar, dr = e.dr, sl = e.sl, sustain = e.sustain) else op } }
            4 -> ins.mapOperators { i, op -> if (ins.isCarrier(i)) op.copy(rr = 15 - n) else op }
            else -> ins.copy(sweep = drops[n])
        }
    }
}
