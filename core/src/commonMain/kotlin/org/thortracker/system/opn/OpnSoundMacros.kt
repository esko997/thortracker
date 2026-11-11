package org.thortracker.system.opn

import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.panel.EnvelopeContour
import org.thortracker.system.ym2612.YmOperator
import org.thortracker.system.ym2612.YmSweep

/** Native PC-88/98 voices. Software programs, FM3 offsets and shared chip generators are never normalized. */
internal object OpnSoundMacros {
    private val fmLabels = arrayOf("Colour", "Overtone", "Contour", "Bite", "Fall")
    private val ssgLabels = arrayOf("Source", "Contour", "Time", "Grain", "Buzz")
    private val drumLabels = arrayOf("Level", "Stage")
    private val sampleLabels = arrayOf("Level", "Root", "Repeat", "Stage", "Fall")
    private val fmMax = intArrayOf(127, 15, 3, 7, 3)
    private val ssgMax = intArrayOf(3, 2, 4, 31, 8)
    private val sampleMax = intArrayOf(255, 95, 1, 3, 3)
    private val drumMax = intArrayOf(31, 3)
    private val fmHints = arrayOf("Modulator strength; carrier levels stay intact", "Modulator harmonics; carrier pitch stays intact",
        "Carrier envelope only; SSG-EG needs Advanced", "Feedback on operator 1; not a brightness guarantee", "Pitch sweep; FM3 offsets and LFO stay intact")
    private val ssgHints = arrayOf("Explicit constant mixer; replaces only its program", "Volume recipe only; custom pitch and arpeggio stay intact",
        "Length of a recognized pluck or swell", "Shared noise period; other SSG channels can override it", "Shared hardware envelope shape; period remains in Advanced")
    private val sampleHints = arrayOf("Playback level; sample bytes unchanged", "Note playing at the recorded rate", "Repeat from the start while held",
        "Output routing; sample and song mix stay intact", "Pitch sweep only; import, trim and sample choice in Advanced")
    private val drumHints = arrayOf("Drum playback level; requires imported rhythm ROM", "Output routing; drum selection follows the listen channel")
    private val names = arrayOf("Held", "Pluck", "Chime", "Bloom")
    private val stages = arrayOf("Silent", "Left", "Right", "Both")
    private val drops = arrayOf(YmSweep.NONE, YmSweep(12, 0, 32), YmSweep(24, 0, 64), YmSweep(36, 0, 96))
    private val dropNames = arrayOf("None", "Tap", "Tom", "Kick")
    private val sourceNames = arrayOf("Tone + noise", "Noise", "Tone", "Silent")
    private data class Envelope(val ar: Int, val d1r: Int, val d2r: Int, val sl: Int, val rr: Int)
    private val envelopes = arrayOf(Envelope(31, 0, 0, 0, 8), Envelope(31, 20, 9, 8, 12),
        Envelope(31, 10, 6, 10, 7), Envelope(14, 0, 0, 0, 6))
    fun labels(p: OpnInstrument) = when (p) { is OpnFmSound -> fmLabels; is OpnSsgSound -> ssgLabels; is OpnRhythmSound -> drumLabels; is OpnAdpcmSound -> sampleLabels }
    fun maximum(p: OpnInstrument, i: Int): Int = when (p) { is OpnFmSound -> fmMax; is OpnSsgSound -> ssgMax; is OpnRhythmSound -> drumMax; is OpnAdpcmSound -> sampleMax }[i]
    fun hint(p: OpnInstrument, i: Int): String = when (p) { is OpnFmSound -> fmHints; is OpnSsgSound -> ssgHints; is OpnRhythmSound -> drumHints; is OpnAdpcmSound -> sampleHints }[i]
    fun enabled(p: OpnInstrument, i: Int): Boolean = when (p) {
        is OpnFmSound -> when (i) { 0, 1 -> p.algorithm != 7; 2 -> (0..3).none { p.isCarrier(it) && p.operators[it].ssgEg and 8 != 0 }; else -> true }
        is OpnSsgSound -> when (i) {
            1 -> p.envelope.shape == S5bShape.OFF
            2 -> p.envelope.shape == S5bShape.OFF && EnvelopeContour.read(p.volume)?.shape?.let { it != EnvelopeContour.Shape.HELD } == true
            else -> true
        }
        else -> true
    }
    private fun colour(p: OpnFmSound): Int? {
        var level = -1
        for (i in 0..3) if (!p.isCarrier(i)) level = maxOf(level, 127 - p.operators[i].tl)
        return level.takeIf { it >= 0 }
    }
    private fun uniform(p: OpnFmSound, get: (YmOperator) -> Int): Int? {
        var found = -1
        for (i in 0..3) if (!p.isCarrier(i)) {
            val n = get(p.operators[i]); if (found >= 0 && n != found) return null; found = n
        }
        return found.takeIf { it >= 0 }
    }
    private fun constant(e: NesEnvelope, empty: Int? = null): Int? = when {
        e.values.isEmpty() && e.loop == -1 && e.release == -1 -> empty
        e.values.size == 1 && e.loop in -1..0 && e.release == -1 -> e.values[0]
        else -> null
    }
    private fun stage(left: Boolean, right: Boolean) = (if (left) 1 else 0) + (if (right) 2 else 0)
    fun value(p: OpnInstrument, i: Int): Int? = when (p) {
        is OpnFmSound -> when (i) {
            0 -> colour(p); 1 -> uniform(p) { it.mul }
            2 -> envelopes.indexOfFirst { e -> (0..3).all { n -> !p.isCarrier(n) || p.operators[n].let {
                it.ar == e.ar && it.d1r == e.d1r && it.d2r == e.d2r && it.sl == e.sl && it.rr == e.rr
            } } }.takeIf { it >= 0 }
            3 -> p.feedback; else -> drops.indexOf(p.sweep).takeIf { it >= 0 }
        }
        is OpnSsgSound -> when (i) {
            0 -> constant(p.mixer, 2)
            1 -> EnvelopeContour.read(p.volume)?.shape?.ordinal
            2 -> EnvelopeContour.read(p.volume)?.let { EnvelopeContour.lengths.indexOf(it.ticks).takeIf { it >= 0 } }
            3 -> constant(p.noise)
            else -> p.envelope.shape.ordinal
        }
        is OpnRhythmSound -> if (i == 0) p.level else stage(p.left, p.right)
        is OpnAdpcmSound -> when (i) { 0 -> p.level; 1 -> p.root; 2 -> if (p.loop) 1 else 0; 3 -> stage(p.left, p.right); else -> drops.indexOf(p.sweep).takeIf { it >= 0 } }
    }
    fun text(p: OpnInstrument, i: Int): String {
        if (!enabled(p, i)) return when {
            p is OpnFmSound && i == 2 -> "SSG-EG / Advanced"
            p is OpnFmSound -> "No modulators"
            p is OpnSsgSound && p.envelope.shape != S5bShape.OFF -> "Hardware envelope"
            else -> "Choose pluck or swell"
        }
        val n = value(p, i) ?: return "Custom"
        return when (p) {
            is OpnFmSound -> when (i) { 1 -> if (n == 0) "Half harmonic" else "Harmonic $n"; 2 -> names[n]; 4 -> dropNames[n]; else -> "$n / ${maximum(p, i)}" }
            is OpnSsgSound -> when (i) { 0 -> sourceNames[n]; 1 -> EnvelopeContour.Shape.entries[n].label; 2 -> "${EnvelopeContour.lengths[n]} ticks"; 4 -> S5bShape.entries[n].label; else -> "$n / 31" }
            is OpnRhythmSound -> if (i == 1) stages[n] else "$n / 31"
            is OpnAdpcmSound -> when (i) { 1 -> "Note $n"; 2 -> if (n == 1) "Loop" else "One shot"; 3 -> stages[n]; 4 -> dropNames[n]; else -> "$n / 255" }
        }
    }
    fun set(p: OpnInstrument, i: Int, value: Int): OpnInstrument {
        if (!enabled(p, i)) return p
        val n = value.coerceIn(0, maximum(p, i))
        if (value(p, i) == n) return p
        return when (p) {
            is OpnFmSound -> when (i) {
                0 -> {
                    val delta = n - (colour(p) ?: n)
                    p.copy(operators = p.operators.mapIndexed { k, op -> if (!p.isCarrier(k)) op.copy(tl = (op.tl - delta).coerceIn(0, 127)) else op })
                }
                1 -> p.copy(operators = p.operators.mapIndexed { k, op -> if (!p.isCarrier(k)) op.copy(mul = n) else op })
                2 -> envelopes[n].let { e -> p.copy(operators = p.operators.mapIndexed { k, op -> if (p.isCarrier(k))
                    op.copy(ar = e.ar, d1r = e.d1r, d2r = e.d2r, sl = e.sl, rr = e.rr) else op }) }
                3 -> p.copy(feedback = n); else -> p.copy(sweep = drops[n])
            }
            is OpnSsgSound -> when (i) {
                0 -> p.copy(mixer = NesEnvelope(listOf(n)))
                1 -> p.copy(volume = EnvelopeContour.choose(p.volume, EnvelopeContour.Shape.entries[n]))
                2 -> p.copy(volume = EnvelopeContour.duration(p.volume, EnvelopeContour.lengths[n]))
                3 -> p.copy(noise = NesEnvelope(listOf(n)))
                else -> p.copy(envelope = p.envelope.copy(shape = S5bShape.entries[n]))
            }
            is OpnRhythmSound -> if (i == 0) p.copy(level = n) else p.copy(left = n and 1 != 0, right = n and 2 != 0)
            is OpnAdpcmSound -> when (i) { 0 -> p.copy(level = n); 1 -> p.copy(root = n); 2 -> p.copy(loop = n == 1);
                3 -> p.copy(left = n and 1 != 0, right = n and 2 != 0); else -> p.copy(sweep = drops[n]) }
        }
    }
}
