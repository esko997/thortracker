package org.thortracker.system.cbm

/**
 * Built-in TED DAC samples: a kick, snare, closed hi-hat and tom, synthesised here (no recordings, so no third-party
 * licences) at a new song's DAC rate, 7917 Hz (PAL, timer 12), as frames 0..8 around the centre 4. Integer-only
 * synthesis (parabolic sine, fixed-point decays, xorshift noise), so every platform makes the same frames. A sound
 * playing one at its root note ([instrument], C-4) plays it at its own pitch.
 */
object CbmStarterKit {
    /** The rate the kit is synthesised at: [CbmSamples.rateHz] of PAL at [CbmSound.DEFAULT_TIMER]. */
    const val RATE_HZ = 7917

    const val KICK = 0
    const val SNARE = 1
    const val HAT = 2
    const val TOM = 3

    /** The kit's samples, in the order of [KICK], [SNARE], [HAT], [TOM]. */
    fun samples(): List<CbmSample> = listOf(
        CbmSample("Kick", kick()),
        CbmSample("Snare", snare()),
        CbmSample("Hat", hat()),
        CbmSample("Tom", tom()),
    )

    /** A sound playing sample [sample] once at its own rate on its root note, C-4, named [name]. */
    fun instrument(sample: Int, name: String): CbmInstrument =
        CbmInstrument(name = name, kind = CbmKind.SAMPLE, sample = sample, root = CbmInstrument.ROOT)

    private fun frames(seconds: Int, per: Int) = RATE_HZ * seconds / per

    /** 150 Hz falling to 45 Hz within ~30 ms, ~50 ms amplitude half-life; 0.2 s. */
    private fun kick(): List<Int> = Voice().run {
        List(frames(1, 5)) {
            val f = 45_000 + (105_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 230)
            val v = sine(f) * amp shr 16
            amp = decay(amp, 115)
            frame(v)
        }
    }

    /** A 185 Hz body under noise that rings longer; 0.16 s. */
    private fun snare(): List<Int> = Voice().run {
        var noiseAmp = 1 shl 16
        List(frames(4, 25)) {
            val body = sine(185_000) * amp shr 16
            amp = decay(amp, 240)
            val hiss = (noise() shr 16) * noiseAmp shr 16
            noiseAmp = decay(noiseAmp, 150)
            frame((body * 14000 shr 15) + (hiss * 24000 shr 15))
        }
    }

    /** High-passed noise, ~10 ms half-life; 50 ms. */
    private fun hat(): List<Int> = Voice().run {
        var low = 0
        List(frames(1, 20)) {
            val n = noise() shr 16
            low += (n - low) shr 2
            val out = ((n - low).toLong() * amp shr 16).toInt()
            amp = decay(amp, 570)
            frame(out)
        }
    }

    /** 130 Hz settling to 90 Hz, ~70 ms half-life; 0.25 s. */
    private fun tom(): List<Int> = Voice().run {
        List(frames(1, 4)) {
            val f = 90_000 + (40_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 170)
            val v = sine(f) * amp shr 16
            amp = decay(amp, 82)
            frame(v)
        }
    }

    /** Oscillator, envelopes and noise state; fixed point (amplitudes Q16, phase 16.16 of a 65536 cycle). */
    private class Voice {
        var phase = 0L
        var amp = 1 shl 16
        var sweep = 1 shl 16
        private var seed = 0x7360_8360

        fun step(milliHz: Int): Long = milliHz.toLong() * 65536L * 65536L / (RATE_HZ * 1000L)

        /** The next value of a sine at [milliHz], -32767..32767. */
        fun sine(milliHz: Int): Int {
            phase += step(milliHz)
            return sin16((phase shr 16).toInt())
        }

        /** xorshift32 white noise over the whole Int range. */
        fun noise(): Int {
            var x = seed
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            seed = x
            return x
        }
    }

    /** One exponential decay step: [v] × (1 − [k] / 65536). */
    private fun decay(v: Int, k: Int): Int = ((v.toLong() * (65536 - k)) shr 16).toInt()

    /** Parabolic sine of a 16-bit phase: ±32767 peaks. */
    private fun sin16(phase: Int): Int {
        val p = phase and 0xFFFF
        val half = p and 0x7FFF
        val v = minOf(32767L, (half.toLong() * (32768 - half)) shr 13).toInt()
        return if (p < 0x8000) v else -v
    }

    /** A 16-bit value as a DAC frame, as `.wav` imports convert ([CbmSamples.level]). */
    private fun frame(v: Int): Int = CbmSamples.level(v)
}
