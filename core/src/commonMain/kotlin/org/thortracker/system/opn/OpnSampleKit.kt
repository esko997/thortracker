package org.thortracker.system.opn

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Thor Tracker's own starter samples for the YM2608's ADPCM-B channel: synthesized here from simple formulas (sine
 * sweeps, a fixed-seed noise generator, additive partials), no recorded or copied audio. Deterministic: the same kind
 * and rate always give the same bytes. The pitched kinds sound [ROOT] (C-4) at their recorded rate, the root note
 * [OpnPresets.adpcm] gives their sounds; the drums are unpitched hits (any note plays them, higher notes faster).
 */
object OpnSampleKit {
    /** The note pitched kinds sound at their recorded rate: C-4 (261.63 Hz). */
    const val ROOT = 48

    val KINDS: List<String> = listOf("Kick", "Snare", "Hat", "Bass", "Bell", "Voice")

    /** The default recording rate. */
    const val RATE = 16000

    /**
     * The starter sample [kind] (one of [KINDS]) recorded at [rate] Hz.
     * @throws IllegalArgumentException for an unknown kind or a rate the chip cannot play.
     */
    fun generate(kind: String, rate: Int = RATE): OpnaSample {
        require(rate in org.thortracker.backend.OpnPlayerImage.RATE_MIN..org.thortracker.backend.OpnPlayerImage.RATE_MAX) {
            "Sample rate $rate Hz is outside 1000-55466 Hz"
        }
        val wave: DoubleArray = when (kind) {
            "Kick" -> kick(rate)
            "Snare" -> snare(rate)
            "Hat" -> hat(rate)
            "Bass" -> bass(rate)
            "Bell" -> bell(rate)
            "Voice" -> voice(rate)
            else -> throw IllegalArgumentException("No starter sample \"$kind\"; the kit has ${KINDS.joinToString()}")
        }
        val pcm = ShortArray(wave.size) { (wave[it] * 26000).roundToInt().coerceIn(-32768, 32767).toShort() }
        return OpnaSample(kind, rate, AdpcmBytes(OpnAdpcm.encode(pcm)))
    }

    private val ROOT_HZ = 440.0 * 2.0.pow((ROOT - 57) / 12.0)

    private fun samples(rate: Int, seconds: Double) = (rate * seconds).roundToInt()

    /** A sine dropping from 150 Hz to 45 Hz with a short click and an exponential fade. */
    private fun kick(rate: Int): DoubleArray {
        val n = samples(rate, 0.32)
        var phase = 0.0
        return DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            val hz = 45.0 + 105.0 * exp(-t / 0.035)
            phase += 2 * PI * hz / rate
            val click = if (t < 0.004) 0.4 * (1 - t / 0.004) else 0.0
            (sin(phase) * exp(-t / 0.11) + click).coerceIn(-1.0, 1.0)
        }
    }

    /** Noise through a one-pole band of the body, over a falling 190 Hz tone. */
    private fun snare(rate: Int): DoubleArray {
        val n = samples(rate, 0.24)
        val noise = Noise(0x5EED)
        var low = 0.0
        val a = 1 - exp(-2 * PI * 5000.0 / rate)
        var phase = 0.0
        return DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            low += a * (noise.next() - low)
            phase += 2 * PI * (190.0 + 60.0 * exp(-t / 0.02)) / rate
            val body = sin(phase) * 0.45 * exp(-t / 0.045)
            (low * 0.9 * exp(-t / 0.07) + body).coerceIn(-1.0, 1.0)
        }
    }

    /** Noise high-passed (first difference), very short decay. */
    private fun hat(rate: Int): DoubleArray {
        val n = samples(rate, 0.08)
        val noise = Noise(0x0A7)
        var last = 0.0
        return DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            val v = noise.next()
            val high = (v - last) * 0.5
            last = v
            (high * exp(-t / 0.018)).coerceIn(-1.0, 1.0)
        }
    }

    /** A plucked bass at [ROOT]: the first eight harmonics of a saw, the upper ones fading faster. */
    private fun bass(rate: Int): DoubleArray {
        val n = samples(rate, 0.7)
        return DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            for (h in 1..8) v += sin(2 * PI * ROOT_HZ * h * t) / h * exp(-t * (1.5 + 2.5 * h))
            (v * 0.62).coerceIn(-1.0, 1.0)
        }
    }

    /** A bell at [ROOT]: inharmonic partials 1, 2.76, 5.40 and 8.93, the higher ones dying sooner. */
    private fun bell(rate: Int): DoubleArray {
        val n = samples(rate, 1.2)
        val partials = listOf(1.0 to 1.0, 2.76 to 0.55, 5.40 to 0.32, 8.93 to 0.18)
        return DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            for ((ratio, level) in partials) v += level * sin(2 * PI * ROOT_HZ * ratio * t) * exp(-t * (1.8 + ratio * 0.9))
            (v * 0.55).coerceIn(-1.0, 1.0)
        }
    }

    /**
     * A sung "ah" at [ROOT]: harmonics weighted by three formant peaks (700, 1220 and 2600 Hz), steady and a whole
     * number of periods long, so a looping sound repeats it without a step.
     */
    private fun voice(rate: Int): DoubleArray {
        val periods = (0.5 * ROOT_HZ).roundToInt()
        val n = (periods * rate / ROOT_HZ).roundToInt()
        val formants = listOf(Triple(700.0, 130.0, 1.0), Triple(1220.0, 140.0, 0.5), Triple(2600.0, 220.0, 0.22))
        val amplitude = DoubleArray(24) { k ->
            val f = ROOT_HZ * (k + 1)
            formants.sumOf { (centre, width, gain) -> gain / (1 + ((f - centre) / width).pow(2)) } + 0.02 / (k + 1)
        }
        val scale = 0.8 / amplitude.sum()
        // The period is resampled exactly onto the sample grid: the waveform ends where it began (phase 2π × periods).
        return DoubleArray(n) { i ->
            val phase = 2 * PI * periods * i / n
            var v = 0.0
            for (k in amplitude.indices) v += amplitude[k] * sin((k + 1) * phase)
            (v * scale).coerceIn(-1.0, 1.0)
        }
    }

    /** A fixed-seed 32-bit xorshift generator: −1..1, the same sequence on every platform. */
    private class Noise(seed: Int) {
        private var state = seed or 1

        fun next(): Double {
            var x = state
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            state = x
            return x / 2147483648.0
        }
    }
}
