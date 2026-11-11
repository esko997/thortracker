package org.thortracker.system.vsu

/** Native VSU envelope, gate and motion recipes; wave/modulation RAM is always left untouched. */
internal object VsuSoundMacros {
    const val COUNT = 6
    private val contours = arrayOf("Held", "Pluck", "Bloom", "Pulse")
    private val gates = intArrayOf(7, 15, 31)
    private val gateNames = arrayOf("31 ms", "61 ms", "123 ms", "Held")
    private val motionNames = arrayOf("Still", "Drop", "Rise", "Table once", "Table loop")
    private val drops = VsuSweep(12, 0, 16)
    private val rises = VsuSweep(-12, 0, 8)
    private val noiseTaps = intArrayOf(14, 10, 13, 4, 8, 6, 9, 11)

    /** The reset-state S6 trace, following the native VSU's 15-bit feedback register. */
    fun noiseStep(register: Int, tap: Int): Int =
        ((register shl 1) or (1 xor (register shr 7) xor (register shr noiseTaps[tap]) and 1)) and 0x7fff

    fun envelopeLevel(ins: VsuInstrument, milliseconds: Float): Int {
        if (ins.autoLength && milliseconds >= (ins.length + 1) * 3.84f) return 0
        if (!ins.envelope) return ins.level
        val step = (milliseconds / ((ins.interval + 1) * 15.36f)).toInt()
        return if (ins.grow) {
            if (ins.repeat) ins.level + step % (16 - ins.level) else (ins.level + step).coerceAtMost(15)
        } else {
            if (ins.repeat) ins.level - step % (ins.level + 1) else (ins.level - step).coerceAtLeast(0)
        }
    }

    fun title(i: Int, lane: Int): String = when (i) {
        0 -> "Listen lane"
        1 -> if (lane == VsuRules.NOISE_LANE) "Grain" else "Timbre"
        2 -> "Contour"
        3 -> "Gate"
        4 -> "Motion"
        else -> "Level"
    }
    fun maximum(i: Int, lane: Int): Int = when (i) {
        0 -> 5
        1 -> if (lane == VsuRules.NOISE_LANE) 7 else 4
        2, 3 -> 3
        4 -> if (lane == VsuRules.SWEEP_LANE) 4 else 2
        else -> 15
    }
    fun reason(i: Int, ins: VsuInstrument, lane: Int): String? = when {
        i == 4 && lane == VsuRules.SWEEP_LANE && ins.freqMod.running && ins.sweep.active -> "Two motion engines: edit separately in Advanced"
        else -> null
    }

    fun value(i: Int, ins: VsuInstrument, lane: Int): Int? = when (i) {
        0 -> lane
        1 -> if (lane == VsuRules.NOISE_LANE) ins.noiseTap else ins.wave
        2 -> when {
            !ins.envelope -> 0
            !ins.grow && !ins.repeat && ins.interval == 2 -> 1
            ins.grow && !ins.repeat && ins.interval == 3 -> 2
            !ins.grow && ins.repeat && ins.interval == 1 -> 3
            else -> null
        }
        3 -> if (!ins.autoLength) 3 else gates.indexOf(ins.length).takeIf { it >= 0 }
        4 -> when {
            // Wave 5 checks upward sweep overflow even with FM on=false. That is not a safe Still/Drop/Rise.
            lane == VsuRules.SWEEP_LANE && !ins.freqMod.modulation && ins.freqMod.up -> null
            lane == VsuRules.SWEEP_LANE && ins.freqMod.running -> {
                val f = ins.freqMod
                if (!ins.sweep.active && f.modulation && f.slowClock && f.interval == 2) (if (f.repeat) 4 else 3) else null
            }
            !ins.sweep.active -> 0
            ins.sweep == drops -> 1
            ins.sweep == rises -> 2
            else -> null
        }
        else -> ins.level
    }
    fun label(i: Int, ins: VsuInstrument, lane: Int): String {
        val v = value(i, ins, lane) ?: return "Custom"
        return when (i) {
            0 -> VsuRules.CHANNEL_NAMES[v]
            1 -> if (lane == VsuRules.NOISE_LANE) when (v) { 0 -> "Air"; 7 -> "Tonal"; else -> "Texture ${v + 1}" } else "Wave ${v + 1}"
            2 -> contours[v]
            3 -> gateNames[v]
            4 -> motionNames[v]
            else -> "$v / 15"
        }
    }
    fun hint(i: Int, ins: VsuInstrument, lane: Int): String = reason(i, ins, lane) ?: when (i) {
        0 -> "One shared instrument; audition on any of six lanes"
        1 -> if (lane == VsuRules.NOISE_LANE) "Noise texture; played notes set its clock" else "Choose shared wave RAM; edit the shape in Wave"
        2 -> if (value(i, ins, lane) == null) "Choosing replaces only envelope timing and direction" else if (ins.grow) "Bloom rises from Level; lower Level for a longer rise" else "Hardware steps; Level sets the starting loudness"
        3 -> "Short hardware cutoff, or hold until note OFF"
        4 -> if (value(i, ins, lane) == null) "Choosing replaces this lane's motion; RAM stays intact" else if (lane == VsuRules.SWEEP_LANE) "Table reads shared modulation RAM; Advanced has hardware sweep" else "Player pitch gesture; hardware modulation lives on Wave 5"
        else -> "Initial hardware volume; contour starts from here"
    }
    fun apply(i: Int, ins: VsuInstrument, requested: Int, lane: Int): VsuInstrument {
        if (reason(i, ins, lane) != null) return ins
        val v = requested.coerceIn(0, maximum(i, lane))
        if (value(i, ins, lane) == v) return ins
        return when (i) {
            1 -> if (lane == VsuRules.NOISE_LANE) ins.copy(noiseTap = v) else ins.copy(wave = v)
            2 -> when (v) {
                0 -> ins.copy(envelope = false)
                1 -> ins.copy(envelope = true, grow = false, repeat = false, interval = 2)
                2 -> ins.copy(envelope = true, grow = true, repeat = false, interval = 3)
                else -> ins.copy(envelope = true, grow = false, repeat = true, interval = 1)
            }
            3 -> if (v == 3) ins.copy(autoLength = false) else ins.copy(autoLength = true, length = gates[v])
            4 -> {
                val f = ins.freqMod
                when {
                    v >= 3 -> ins.copy(sweep = ins.sweep.copy(speed = 0), freqMod = f.copy(on = true, modulation = true, repeat = v == 4, slowClock = true, interval = 2))
                    else -> ins.copy(sweep = when (v) { 1 -> drops; 2 -> rises; else -> ins.sweep.copy(speed = 0) },
                        // Disabled modulation avoids the native sweep overflow check; retain its inactive timing/direction.
                        freqMod = if (lane == VsuRules.SWEEP_LANE) f.copy(on = false, modulation = true) else f)
                }
            }
            5 -> ins.copy(level = v)
            else -> ins
        }
    }
}
