package org.thortracker.system.ay

import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.panel.EnvelopeContour

internal enum class AySoundControl(val label: String, val maximum: Int, val hint: String) {
    SOURCE("Source", 3, "Tone, noise, both, or the bare shared buzzer"),
    CONTOUR("Contour", 2, "Explicit volume recipe; Advanced programs stay Custom"),
    COLOUR("Noise colour", 5, "One noise clock for A/B/C: latest request wins"),
    BUZZ("Buzzer", 8, "One envelope for A/B/C: latest requesting note wins"),
    REGISTER("Buzzer register", 15, "Choose a note-following octave; manual period is kept"),
}

/** Recipes own only the named native field. Equality is checked before adopting a recipe. */
internal object AySoundMacros {
    private val sources = intArrayOf(1, 2, 3, 0)
    private val sourceNames = arrayOf("Tone", "Hiss", "Tone + hiss", "Bare buzzer")
    private val colours = intArrayOf(-1, 1, 4, 8, 16, 31)
    private val colourNames = arrayOf("Inherit", "Air", "Bright", "Sand", "Dark", "Rumble")
    private val shapes = intArrayOf(0, 8, 10, 12, 14, 9, 13, 11, 15)
    private val shapeNames = arrayOf("Off", "Saw down", "Triangle down", "Saw up", "Triangle up", "Fall once", "Rise + hold", "Fall + hold", "Rise once")
    private fun constant(e: NesEnvelope, default: Int): Int? = when {
        e.values.isEmpty() -> default
        e.values.size == 1 && e.loop == -1 && e.release == -1 -> e.values[0]
        else -> null
    }
    fun enabled(c: AySoundControl, ins: AyInstrument): Boolean = when (c) {
        AySoundControl.CONTOUR -> !ins.envelope.on
        AySoundControl.REGISTER -> ins.envelope.on
        else -> true
    }
    fun value(c: AySoundControl, ins: AyInstrument): Int? = when (c) {
        AySoundControl.SOURCE -> constant(ins.mixer, 1)?.let { sources.indexOf(it) }
        AySoundControl.CONTOUR -> EnvelopeContour.read(ins.volume)?.shape?.ordinal
        AySoundControl.COLOUR -> constant(ins.noise, -1)?.let { colours.indexOf(it) }
        AySoundControl.BUZZ -> shapes.indexOf(ins.envelope.shape)
        AySoundControl.REGISTER -> if (ins.envelope.auto) ins.envelope.octave + 8 else null
    }?.takeIf { it >= 0 }
    fun label(c: AySoundControl, value: Int): String = when (c) {
        AySoundControl.SOURCE -> sourceNames[value]
        AySoundControl.CONTOUR -> EnvelopeContour.Shape.entries[value].label
        AySoundControl.COLOUR -> colourNames[value]
        AySoundControl.BUZZ -> shapeNames[value]
        AySoundControl.REGISTER -> "${value - 8} oct"
    }
    fun apply(c: AySoundControl, ins: AyInstrument, requested: Int): AyInstrument {
        val n = requested.coerceIn(0, c.maximum)
        if (!enabled(c, ins) || value(c, ins) == n) return ins
        return when (c) {
            AySoundControl.SOURCE -> ins.copy(mixer = NesEnvelope(values = listOf(sources[n])))
            AySoundControl.CONTOUR -> ins.copy(volume = EnvelopeContour.choose(ins.volume, EnvelopeContour.Shape.entries[n]))
            AySoundControl.COLOUR -> ins.copy(noise = if (n == 0) NesEnvelope() else NesEnvelope(values = listOf(colours[n])))
            AySoundControl.BUZZ -> ins.copy(envelope = ins.envelope.copy(shape = shapes[n]))
            AySoundControl.REGISTER -> ins.copy(envelope = ins.envelope.copy(auto = true, octave = n - 8))
        }
    }
    fun step(c: AySoundControl, ins: AyInstrument, delta: Int): AyInstrument =
        if (delta == 0) ins else apply(c, ins, ((value(c, ins) ?: if (delta > 0) -1 else c.maximum + 1) + delta).coerceIn(0, c.maximum))
}
