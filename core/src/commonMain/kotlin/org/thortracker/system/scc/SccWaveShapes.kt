package org.thortracker.system.scc

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Wave shapes for the SCC's 32-sample signed 8-bit wave RAMs. Pure functions: each returns exactly
 * [SccWave.SAMPLES] values in -128..127 (rounded half up), so the editor, presets and demos share one definition.
 */
object SccWaveShapes {
    private const val N = SccWave.SAMPLES

    private fun level(v: Double): Int = floor(v + 0.5).toInt().coerceIn(-128, 127)

    /** One sine cycle starting at 0 and rising: floor(127 sin(2πi/32) + 0.5). */
    fun sine(): List<Int> = List(N) { level(127.0 * sin(2.0 * PI * it / N)) }

    /** A triangle in phase with [sine]: 0 at sample 0, +127 at 8, 0 at 16, −127 at 24. */
    fun triangle(): List<Int> = List(N) {
        val x = it.toDouble() / N
        val t = when {
            x <= 0.25 -> 4 * x
            x <= 0.75 -> 2 - 4 * x
            else -> 4 * x - 4
        }
        level(127.0 * t)
    }

    /** A rising sawtooth from −128 to +127. */
    fun saw(): List<Int> = List(N) { level(-128.0 + it * 255.0 / (N - 1)) }

    /** 50% square: [pulse] 16. */
    fun square(): List<Int> = pulse(N / 2)

    /** [width] samples (1..31) at +127, the rest at −128: 16 = square, 8 = 25%, 4 = 12.5%. */
    fun pulse(width: Int): List<Int> {
        val w = width.coerceIn(1, N - 1)
        return List(N) { if (it < w) 127 else -128 }
    }

    /** [w] upside down (−128 becomes +127). */
    fun invert(w: List<Int>): List<Int> = w.map { (-it).coerceIn(-128, 127) }

    /** [w] started [by] samples later: sample i of the result is sample i + [by] (wrapping) of [w]. */
    fun rotate(w: List<Int>, by: Int): List<Int> = List(N) { w[(it + by).mod(N)] }

    /** [w] through a circular 1-2-1 low-pass: softer edges, the same period. */
    fun smooth(w: List<Int>): List<Int> = List(N) {
        val sum = w[(it - 1).mod(N)] + 2 * w[it] + w[(it + 1).mod(N)]
        (sum + 2).floorDiv(4).coerceIn(-128, 127)
    }

    /** [w] scaled so its largest magnitude is 127 (unchanged when silent). */
    fun normalize(w: List<Int>): List<Int> {
        val peak = w.maxOf { abs(it) }
        if (peak == 0) return w.toList()
        return w.map { level(it * 127.0 / peak) }
    }

    /**
     * A sum of sine harmonics normalised to ±127: each pair is (harmonic 1..16, amplitude). Organs, bells and soft basses
     * are built this way; the 32-sample period only holds whole harmonics.
     */
    fun additive(partials: List<Pair<Int, Double>>): List<Int> {
        val raw = DoubleArray(N) { i -> partials.sumOf { (h, a) -> a * sin(2.0 * PI * h * i / N) } }
        val peak = raw.maxOf { abs(it) }
        if (peak == 0.0) return List(N) { 0 }
        return List(N) { level(raw[it] * 127.0 / peak) }
    }

    /**
     * 32 pseudo-random levels from [seed] (a 31-bit linear congruential generator, so the same seed always gives the
     * same wave). Played high it buzzes like noise: the SCC has no noise generator, so snares and hats use waves like it.
     */
    fun noise(seed: Int): List<Int> {
        var state = seed.toLong() and 0x7FFFFFFFL
        return List(N) {
            state = (state * 1103515245L + 12345L) and 0x7FFFFFFFL
            ((state shr 16) and 0xFF).toInt() - 128
        }
    }
}
