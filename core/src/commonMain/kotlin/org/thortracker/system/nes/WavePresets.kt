package org.thortracker.system.nes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin

/**
 * FamiStudio 4.5.3's wave presets (`WavePresetType`, `Envelope.SetFromPreset`), shared by the N163's and the FDS's waves
 * and the FDS modulation table, with its float arithmetic and round-half-to-even, so a preset here makes the values
 * FamiStudio's preset makes. The PWM presets change the pulse width from wave to wave over a sequence of several waves;
 * the others repeat one wave. FamiStudio offers the PWM presets for waves only, not for the FDS modulation table.
 */
enum class WavePreset(val label: String) {
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

/** `Envelope.SetFromPreset` for one chunk: value range [min]..[max], [n] values, chunk [j] of [count]. */
private fun presetValues(preset: WavePreset, n: Int, j: Int, count: Int, min: Int, max: Int): List<Int> {
    val lo = min.toFloat()
    val hi = max.toFloat()
    // FamiStudio's `Utils.Lerp(float, float, float)`, then C#'s `Math.Round` (half to even) as it stores the value.
    fun lerp(t: Float): Int = round((lo * (1.0f - t) + hi * t).toDouble()).toInt()
    return when (preset) {
        WavePreset.SINE -> List(n) { i -> lerp(sin((i * 2.0f).toDouble() * PI / n).toFloat() * -0.5f + 0.5f) }
        WavePreset.TRIANGLE -> {
            val out = IntArray(n)
            for (i in 0 until n / 2) {
                out[i] = lerp(i / (n / 2 - 1).toFloat())
                out[n - i - 1] = out[i]
            }
            out.toList()
        }
        WavePreset.SAWTOOTH -> List(n) { i -> lerp(i / (n - 1).toFloat()) }
        WavePreset.SQUARE50 -> List(n) { i -> if (i >= n / 2) max else min }
        WavePreset.SQUARE25 -> List(n) { i -> if (i >= n / 4) max else min }
        WavePreset.FLAT -> List(n) { round((min + max) / 2.0).toInt() }
        WavePreset.PWM -> List(n) { i -> if (i >= n / 2 + abs(n / 2 - (1 + (j + 1) * (n - 2) / count))) max else min }
        WavePreset.PWM2 -> List(n) { i -> if (i >= n / 2 + ((j + 1) * (n - 2) / count) / 2) max else min }
        WavePreset.PWM3 -> List(n) { i -> if (i >= n / 2 + (n / 2 - (1 + ((j + 1) * (n - 2) / count) / 2))) max else min }
        WavePreset.PWM4 -> List(n) { i -> if (i >= n - 1 - abs(n / 2 - (1 + (j + 1) * (n - 2) / count))) max else min }
    }
}

/*
 * Value bounds as constants, not read from [N163Wave.SAMPLE], [FdsSound.SAMPLE] or [FdsSound.MOD_TARGET]: those classes'
 * companions build their DEFAULT from these presets, so reading their ranges here would run before they are set.
 */
private const val N163_LOW = 0
private const val N163_HIGH = 15
private const val FDS_LOW = 0
private const val FDS_HIGH = 63
private const val MOD_LOW = -64
private const val MOD_HIGH = 63

/** The N163's wave presets: samples 0..15 at any wave size. */
object N163Presets {
    /** One sine wave of [size] samples (FamiStudio's default N163 wave at 16). */
    fun sine(size: Int): List<Int> = wave(WavePreset.SINE, size, 0, 1)

    /** Wave [index] of [count] of [preset] with [size] samples. */
    fun wave(preset: WavePreset, size: Int, index: Int, count: Int): List<Int> =
        presetValues(preset, size, index, count, N163_LOW, N163_HIGH)

    /** All [count] waves of [preset] at [size] samples. */
    fun waves(preset: WavePreset, size: Int, count: Int): List<List<Int>> = List(count) { wave(preset, size, it, count) }

    /** The preset [waves] were made from at [size], or null when they were drawn (FamiStudio's "Custom"). */
    fun presetOf(waves: List<List<Int>>, size: Int): WavePreset? =
        WavePreset.entries.firstOrNull { p -> waves(p, size, waves.size) == waves }
}

/**
 * The FDS's presets: waves of [FdsSound.WAVE_SIZE] samples 0..63 (`FdsWaveform`, every preset) and the modulation
 * table's [FdsSound.MOD_SIZE] targets −64..63 (`FdsModulation`, the presets up to Flat).
 */
object FdsPresets {
    /** Wave [index] of [count] of [preset]. */
    fun wave(preset: WavePreset, index: Int, count: Int): List<Int> =
        presetValues(preset, FdsSound.WAVE_SIZE, index, count, FDS_LOW, FDS_HIGH)

    /** All [count] waves of [preset]. */
    fun waves(preset: WavePreset, count: Int): List<List<Int>> = List(count) { wave(preset, it, count) }

    /** One sine wave, FamiStudio's default FDS wave. */
    val SINE: List<Int> = wave(WavePreset.SINE, 0, 1)

    /** The preset [waves] were made from, or null when they were drawn (FamiStudio's "Custom"). */
    fun presetOf(waves: List<List<Int>>): WavePreset? = WavePreset.entries.firstOrNull { p -> waves(p, waves.size) == waves }

    /** The presets FamiStudio offers for the modulation table. */
    val MOD_PRESETS: List<WavePreset> = WavePreset.entries.filter { !it.sweeps }

    /** The modulation targets of [preset] (one of [MOD_PRESETS]). */
    fun modulation(preset: WavePreset): List<Int> {
        require(preset in MOD_PRESETS) { "FamiStudio has no ${preset.label} modulation preset" }
        return presetValues(preset, FdsSound.MOD_SIZE, 0, 1, MOD_LOW, MOD_HIGH)
    }

    /** FamiStudio's default modulation table (Flat: every target 0). */
    val FLAT_MOD: List<Int> = modulation(WavePreset.FLAT)

    /** The preset the modulation targets [values] were made from, or null when drawn. */
    fun modPresetOf(values: List<Int>): WavePreset? = MOD_PRESETS.firstOrNull { modulation(it) == values }
}
