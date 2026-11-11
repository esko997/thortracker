package org.thortracker.system.saa

import org.thortracker.system.nes.NesEnvelope

internal enum class SaaSoundControl(val label: String, val maximum: Int) {
    SOURCE("Source", 3), CONTOUR("Contour", 3), POSITION("Position", 4), GRAIN("Grain", 3), MOTION("Motion", 4), LANE("Listen lane", 11)
}

/** Native SAA programs only. Shared resources are never presented as independent per-voice synthesis. */
internal object SaaSoundMacros {
    private val contours = arrayOf(NesEnvelope(listOf(15)), NesEnvelope(listOf(15, 12, 9, 6, 3, 0)),
        NesEnvelope(listOf(15, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 0)),
        NesEnvelope(listOf(0, 3, 6, 9, 12, 15)))
    private val motion = arrayOf(null,
        SaaHardwareEnvelope(shape = SaaHardwareEnvelope.SINGLE_DECAY, external = true, rate = 1),
        SaaHardwareEnvelope(shape = SaaHardwareEnvelope.REPETITIVE_TRIANGLE, external = true, rate = 1),
        SaaHardwareEnvelope(shape = SaaHardwareEnvelope.REPETITIVE_TRIANGLE, external = true, rate = 4),
        SaaHardwareEnvelope(shape = SaaHardwareEnvelope.REPETITIVE_TRIANGLE, invertRight = true, external = true, rate = 2))
    private val left = intArrayOf(15, 15, 15, 8, 0)
    private val right = intArrayOf(0, 8, 15, 15, 15)
    private val sourceNames = arrayOf("Mixer off", "Tone", "Noise", "Tone + noise")
    private val contourNames = arrayOf("Held", "Pluck", "Fade", "Swell")
    private val positionNames = arrayOf("Left", "Left centre", "Centre", "Right centre", "Right")
    private val grainNames = arrayOf("Fine", "Medium", "Coarse", "Follow group tone")
    private val motionNames = arrayOf("Off", "Strike", "Ripple", "Slow tide", "Stereo tide")
    fun enabled(c: SaaSoundControl, ins: SaaInstrument, lane: Int): Boolean = when (c) {
        SaaSoundControl.GRAIN -> ins.usesNoise
        SaaSoundControl.MOTION -> SaaSound.hasEnvelope(lane)
        else -> true
    }
    fun value(c: SaaSoundControl, ins: SaaInstrument, lane: Int): Int? = when (c) {
        SaaSoundControl.SOURCE -> when {
            ins.mixer.values.isEmpty() && ins.mixer.loop == -1 && ins.mixer.release == -1 -> 1
            ins.mixer.values.size == 1 && ins.mixer.loop == -1 && ins.mixer.release == -1 -> ins.mixer.values[0]
            else -> null
        }
        SaaSoundControl.CONTOUR -> if (ins.volume.values.isEmpty()) 0 else contours.indexOf(ins.volume).takeIf { it >= 0 }
        SaaSoundControl.POSITION -> left.indices.firstOrNull { ins.panLeft == left[it] && ins.panRight == right[it] }
        SaaSoundControl.GRAIN -> ins.noise
        SaaSoundControl.MOTION -> motion.indexOf(ins.envelope).takeIf { it >= 0 }
        SaaSoundControl.LANE -> lane
    }
    fun label(c: SaaSoundControl, n: Int, sound: SaaSound): String = when (c) {
        SaaSoundControl.SOURCE -> sourceNames[n]
        SaaSoundControl.CONTOUR -> contourNames[n]
        SaaSoundControl.POSITION -> positionNames[n]
        SaaSoundControl.GRAIN -> grainNames[n]
        SaaSoundControl.MOTION -> motionNames[n]
        SaaSoundControl.LANE -> sound.variant.laneNames[n]
    }
    fun hint(c: SaaSoundControl): String = when (c) {
        SaaSoundControl.SOURCE -> "Explicitly replaces this voice's mixer program."
        SaaSoundControl.CONTOUR -> "Voice level, also shaped by Motion."
        SaaSoundControl.POSITION -> "Centre: full amplitude on both sides."
        SaaSoundControl.GRAIN -> "Shared noise per triple; last note wins."
        SaaSoundControl.MOTION -> "Only lanes 3/6; external tick clock."
        SaaSoundControl.LANE -> "Audition lane only; no patch change."
    }
    fun set(c: SaaSoundControl, ins: SaaInstrument, requested: Int, lane: Int): SaaInstrument {
        if (!enabled(c, ins, lane)) return ins
        val n = requested.coerceIn(0, c.maximum)
        if (value(c, ins, lane) == n) return ins
        return when (c) {
            SaaSoundControl.SOURCE -> ins.copy(mixer = NesEnvelope(listOf(n)))
            SaaSoundControl.CONTOUR -> ins.copy(volume = contours[n])
            SaaSoundControl.POSITION -> ins.copy(panLeft = left[n], panRight = right[n])
            SaaSoundControl.GRAIN -> ins.copy(noise = n)
            SaaSoundControl.MOTION -> ins.copy(envelope = motion[n])
            SaaSoundControl.LANE -> ins
        }
    }
}
