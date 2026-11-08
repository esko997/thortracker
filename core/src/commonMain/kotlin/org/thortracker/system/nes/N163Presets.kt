package org.thortracker.system.nes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin

/**
 * FamiStudio 4.5.3's N163 wave presets (`WavePresetType`, `Envelope.SetFromPreset` for `N163Waveform`, values 0..15),
 * with its float arithmetic and round-half-to-even, so a preset here makes the samples FamiStudio's preset makes.
 * The PWM presets change the pulse width from wave to wave over a sequence of several waves; the others repeat one wave.
 */
enum class N163Preset(val label: String) {
    SINE("Sine"),
    TRIANGLE("Triangle"),
    SAWTOOTH("Sawtooth"),
    SQUARE50("Square 50%"),
    SQUARE25("Square 25%"),
    FLAT("Flat (silent)"),
    PWM("PWM 1"),
    PWM2("PWM 2"),
    PWM3("PWM 3"),
    PWM4("PWM 4");

    /** True for the presets that differ from wave to wave. */
    val sweeps: Boolean get() = this == PWM || this == PWM2 || this == PWM3 || this == PWM4
}

object N163Presets {
    private const val MIN = 0f
    private const val MAX = 15f

    /** FamiStudio's `Utils.Lerp(float, float, float)`. */
    private fun lerp(t: Float): Float = MIN * (1.0f - t) + MAX * t

    /** C#'s `Math.Round` (half to even) of a float, as FamiStudio stores the sample. */
    private fun sample(x: Float): Int = round(x.toDouble()).toInt()

    /** One sine wave of [size] samples (FamiStudio's default N163 wave at 16). */
    fun sine(size: Int): List<Int> = wave(N163Preset.SINE, size, 0, 1)

    /** Wave [index] of [count] of [preset] with [size] samples. */
    fun wave(preset: N163Preset, size: Int, index: Int, count: Int): List<Int> {
        val n = size
        val j = index
        return when (preset) {
            N163Preset.SINE -> List(n) { i -> sample(lerp(sin((i * 2.0f).toDouble() * PI / n).toFloat() * -0.5f + 0.5f)) }
            N163Preset.TRIANGLE -> {
                val out = IntArray(n)
                for (i in 0 until n / 2) {
                    out[i] = sample(lerp(i / (n / 2 - 1).toFloat()))
                    out[n - i - 1] = out[i]
                }
                out.toList()
            }
            N163Preset.SAWTOOTH -> List(n) { i -> sample(lerp(i / (n - 1).toFloat())) }
            N163Preset.SQUARE50 -> List(n) { i -> if (i >= n / 2) 15 else 0 }
            N163Preset.SQUARE25 -> List(n) { i -> if (i >= n / 4) 15 else 0 }
            N163Preset.FLAT -> List(n) { round((MIN + MAX) / 2.0).toInt() }
            N163Preset.PWM -> List(n) { i -> if (i >= n / 2 + abs(n / 2 - (1 + (j + 1) * (n - 2) / count))) 15 else 0 }
            N163Preset.PWM2 -> List(n) { i -> if (i >= n / 2 + ((j + 1) * (n - 2) / count) / 2) 15 else 0 }
            N163Preset.PWM3 -> List(n) { i -> if (i >= n / 2 + (n / 2 - (1 + ((j + 1) * (n - 2) / count) / 2))) 15 else 0 }
            N163Preset.PWM4 -> List(n) { i -> if (i >= n - 1 - abs(n / 2 - (1 + (j + 1) * (n - 2) / count))) 15 else 0 }
        }
    }

    /** All [count] waves of [preset] at [size] samples. */
    fun waves(preset: N163Preset, size: Int, count: Int): List<List<Int>> = List(count) { wave(preset, size, it, count) }

    /** The preset [waves] were made from at [size], or null when they were drawn (FamiStudio's "Custom"). */
    fun presetOf(waves: List<List<Int>>, size: Int): N163Preset? =
        N163Preset.entries.firstOrNull { p -> waves(p, size, waves.size) == waves }
}
