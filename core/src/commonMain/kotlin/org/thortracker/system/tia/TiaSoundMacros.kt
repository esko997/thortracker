package org.thortracker.system.tia

import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.panel.EnvelopeContour

/** Cartridge voices: oscillator families and discrete note steps, never a promise of continuous tuning. */
internal object TiaSoundMacros {
    val labels = arrayOf("Character", "Tracking", "Contour", "Step motion")
    private val waves = intArrayOf(12, 4, 6, 14, 1, 7, 15, 2, 3, 8, 0)
    private val characters = arrayOf("Middle square", "High square", "Bass", "Deep bass", "Buzz", "Reed", "Grit", "Rumble", "Rough rumble", "Hiss", "DC level")
    private val moves = arrayOf(TiaEnvelope(), TiaEnvelope(listOf(0, 1, 2, 3)), TiaEnvelope(listOf(3, 2, 1, 0)), TiaEnvelope(listOf(0, 1), loop = 0))
    private val motionNames = arrayOf("Still", "Climb", "Drop", "Wobble")
    val maxima = intArrayOf(waves.lastIndex, 1, 2, moves.lastIndex)
    private fun envelope(i: TiaInstrument) = NesEnvelope(i.volume.values, i.volume.loop, i.volume.release)
    /** The first hardware divider of an unmodified audition, including the first envelope steps. */
    fun divider(i: TiaInstrument, region: TiaRegion, note: Int): Int? {
        val wave = i.keyOnWaveform()
        if (TiaRules.period(wave) == null) return null
        val step = i.arpeggio.values.firstOrNull() ?: 0
        return if (i.raw) TiaRules.rawAudf(note, step)
        else TiaRules.audf(region, wave, (note + step) * TiaRules.UNITS + (i.pitch.values.firstOrNull() ?: 0))
    }
    fun enabled(i: TiaInstrument, n: Int): Boolean = n != 0 || i.waveforms.values.isEmpty()
    fun value(i: TiaInstrument, n: Int): Int? = when (n) {
        0 -> if (!enabled(i, n)) null else waves.indexOf(when (i.waveform) { 5 -> 4; 10 -> 6; 11 -> 0; 13 -> 12; 9 -> 7; else -> i.waveform }).takeIf { it >= 0 }
        1 -> if (i.raw) 1 else 0
        2 -> if (i.volume.values.size == 1 && i.volume.loop == -1 && i.volume.release == -1) 0 else EnvelopeContour.read(envelope(i))?.shape?.ordinal
        else -> moves.indexOf(i.arpeggio).takeIf { it >= 0 }
    }
    fun text(i: TiaInstrument, n: Int): String {
        val v = value(i, n) ?: return "Custom"
        return when (n) { 0 -> characters[v]; 1 -> if (i.raw) "Divider steps" else "Nearest note"; 2 -> EnvelopeContour.Shape.entries[v].label; else -> motionNames[v] }
    }
    fun hint(i: TiaInstrument, n: Int): String = when (n) {
        0 -> if (!enabled(i, n)) "Waveform sequence owns Character: use Advanced." else "Each character has only 32 pitches; DC has none."
        1 -> if (i.raw) "Notes step the divider, repeating every 32; fine pitch is ignored." else "Nearest hardware divider, not equal temperament. See Advanced pitch guide."
        2 -> "Choosing a contour replaces only the volume sequence."
        else -> if (i.raw) "Replaces arpeggio with divider steps; wraps after 32." else "Replaces arpeggio with semitone steps; hardware pitch stays stepped."
    }
    fun apply(i: TiaInstrument, n: Int, requested: Int): TiaInstrument {
        if (!enabled(i, n)) return i
        val v = requested.coerceIn(0, maxima[n])
        if (value(i, n) == v) return i
        return when (n) {
            0 -> i.copy(waveform = waves[v])
            1 -> i.copy(raw = v == 1)
            2 -> EnvelopeContour.choose(envelope(i), EnvelopeContour.Shape.entries[v]).let { i.copy(volume = TiaEnvelope(it.values, it.loop, it.release)) }
            else -> i.copy(arpeggio = moves[v])
        }
    }
}
