package org.thortracker.system.scc

import org.thortracker.system.nes.NesEnvelope

/** Cartridge timbres: choosing a wave changes the pointer, never any shared wave samples. */
internal object SccSoundMacros {
    const val COUNT = 5
    val labels = arrayOf("Wave", "Contour", "Gesture", "Attack", "Listen")
    val contours = arrayOf("Held", "Pluck", "Organ", "Bloom")
    private val volume = arrayOf(NesEnvelope(listOf(15)), NesEnvelope(listOf(15, 13, 10, 7, 4, 2, 0)),
        NesEnvelope(listOf(15, 12, 10, 8, 6, 4, 2, 0), release = 1),
        NesEnvelope(listOf(0, 2, 4, 7, 10, 13, 15, 11, 7, 3, 0), release = 7))
    val motions = arrayOf("Still", "Vibrato", "Scoop", "Fall")
    private val pitch = arrayOf(NesEnvelope(), NesEnvelope(listOf(0, 1, 2, 1, 0, -1, -2, -1), loop = 0),
        NesEnvelope(listOf(-16, -12, -8, -4, 0)), NesEnvelope(listOf(-1), loop = 0))
    fun maximum(i: Int): Int = when (i) { 0 -> SccSound.WAVES - 1; 1, 2 -> 3; 3 -> 1; else -> 4 }
    fun enabled(i: Int, ins: SccInstrument): Boolean = i != 0 || ins.wave.values.size == 1
    fun value(i: Int, ins: SccInstrument): Int = when (i) {
        0 -> if (ins.wave.values.size == 1) ins.wave.values[0] - 1 else -1
        1 -> if (ins.volume.values.isEmpty()) 0 else volume.indexOf(ins.volume)
        2 -> pitch.indices.firstOrNull { pitch[it] == ins.pitch && (ins.pitch.values.isEmpty() || ins.pitchRelative == (it == 3)) } ?: -1
        3 -> if (ins.restart) 1 else 0
        else -> 0
    }
    fun text(i: Int, n: Int): String = if (n < 0) "Custom" else when (i) {
        0 -> "Wave ${n + 1}"
        1 -> contours[n]
        2 -> motions[n]
        3 -> if (n == 0) "Free phase" else "Same phase"
        else -> "SCC ${n + 1}"
    }
    fun hint(i: Int, ins: SccInstrument): String = when (i) {
        0 -> if (ins.wave.values.size != 1) "Wave sequence active: edit in Advanced." else "Select stored wave; shared samples stay untouched."
        1 -> "Replaces volume contour only; timing follows song ticks."
        2 -> "Replaces pitch gesture only; arpeggio stays."
        3 -> "Same phase restarts the wave for consistent attacks."
        else -> "SCC 4/5 share wave RAM; SCC+ has five independent waves."
    }
    fun apply(i: Int, ins: SccInstrument, requested: Int): SccInstrument {
        if (!enabled(i, ins) || i == 4) return ins
        val n = requested.coerceIn(0, maximum(i))
        if (value(i, ins) == n) return ins
        return when (i) {
            0 -> ins.copy(wave = ins.wave.copy(values = listOf(n + 1)))
            1 -> ins.copy(volume = volume[n])
            2 -> ins.copy(pitch = pitch[n], pitchRelative = n == 3)
            3 -> ins.copy(restart = n == 1)
            else -> ins
        }
    }
}
