package org.thortracker.system.panel

import org.thortracker.system.nes.NesEnvelope

/** Small, explicit recipes for software-volume envelopes; no hidden macro state or approximate recognition. */
internal object EnvelopeContour {
    enum class Shape(val label: String) { HELD("Held"), PLUCK("Pluck"), SWELL("Swell") }
    data class Recognized(val shape: Shape, val ticks: Int, val peak: Int)
    val lengths = intArrayOf(4, 8, 16, 32, 64)

    fun make(shape: Shape, ticks: Int, peak: Int): NesEnvelope {
        val n = ticks.coerceIn(1, NesEnvelope.MAX_LENGTH - 2)
        return when (shape) {
            Shape.HELD -> NesEnvelope(listOf(peak, 0), loop = 0, release = 1)
            Shape.PLUCK -> NesEnvelope(List(n + 1) { peak * (n - it) / n })
            Shape.SWELL -> NesEnvelope(List(n + 2) { if (it > n) 0 else peak * it / n }, loop = n, release = n + 1)
        }
    }

    /** A custom sequence is never rounded to the nearest recipe. Empty means the driver's full held level. */
    fun read(envelope: NesEnvelope, maximum: Int = 15): Recognized? {
        val values = envelope.values
        if (values.isEmpty()) return if (envelope.loop == -1 && envelope.release == -1)
            Recognized(Shape.HELD, 16, maximum) else null
        val peak = values.maxOrNull() ?: return null
        if (values.any { it !in 0..maximum }) return null
        if (values.size == 2 && envelope.loop == 0 && envelope.release == 1 && values[1] == 0)
            return Recognized(Shape.HELD, 16, peak)
        val rising = envelope.loop == values.size - 2 && envelope.release == values.size - 1 && values.last() == 0
        val falling = envelope.loop == -1 && envelope.release == -1
        if (!rising && !falling) return null
        val n = values.size - if (rising) 2 else 1
        if (n < 1 || n > NesEnvelope.MAX_LENGTH - 2) return null
        for (i in 0..n) if (values[i] != peak * (if (rising) i else n - i) / n) return null
        return Recognized(if (rising) Shape.SWELL else Shape.PLUCK, n, peak)
    }

    /** Deliberately choosing a contour replaces only that envelope, preserving its current peak and recognized time. */
    fun choose(envelope: NesEnvelope, shape: Shape, maximum: Int = 15): NesEnvelope {
        val old = read(envelope, maximum)
        if (old?.shape == shape) return envelope
        val peak = if (envelope.values.isEmpty()) maximum else (envelope.values.maxOrNull() ?: maximum).coerceIn(0, maximum)
        return make(shape, old?.ticks ?: 16, peak)
    }

    /** Time cannot truthfully control a custom program or a held contour; leave either byte-exact. */
    fun duration(envelope: NesEnvelope, ticks: Int, maximum: Int = 15): NesEnvelope {
        val old = read(envelope, maximum) ?: return envelope
        if (old.shape == Shape.HELD || old.ticks == ticks) return envelope
        return make(old.shape, ticks, old.peak)
    }
}
