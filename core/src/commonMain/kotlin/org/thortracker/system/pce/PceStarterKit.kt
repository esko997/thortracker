package org.thortracker.system.pce

/**
 * Built-in DDA samples for PC Engine songs: a kick, snare, closed hi-hat and tom, synthesized here (no recordings, so
 * no third-party licences) at the default DDA rate, 6991 Hz (timer reload 0), as 5-bit frames 0..31 around the centre
 * 16. Synthesis is integer-only (parabolic sine, fixed-point decays, xorshift noise), so every platform makes the same
 * frames. A sound playing one of them at its root note ([instrument], C-4) plays it at its own pitch.
 */
object PceStarterKit {
    /** The rate the kit is synthesized at: the DDA timer at reload 0, 7159090.9 / 1024 Hz, rounded down. */
    const val RATE_HZ = 6991

    const val KICK = 0
    const val SNARE = 1
    const val HAT = 2
    const val TOM = 3

    /** The kit's samples, in the order of [KICK], [SNARE], [HAT], [TOM]. */
    fun samples(): List<PceSample> = listOf(
        PceSample("Kick", kick()),
        PceSample("Snare", snare()),
        PceSample("Hat", hat()),
        PceSample("Tom", tom()),
    )

    /**
     * A sound playing sample [sample] (an index into the song's [PceSound.samples]) once at its own rate on its root
     * note, C-4, named [name], at full volume.
     */
    fun instrument(sample: Int, name: String): PceInstrument =
        PceInstrument(name = name, kind = PceKind.SAMPLE, sample = sample, root = PceInstrument.ROOT)

    private fun frames(seconds: Int, per: Int) = RATE_HZ * seconds / per

    /** 160 Hz falling to 45 Hz within ~30 ms, ~60 ms amplitude half-life; 0.25 s. */
    private fun kick(): List<Int> = Voice().run {
        List(frames(1, 4)) {
            val f = 45_000 + (115_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 261)
            val v = sine(f) * amp shr 16
            amp = decay(amp, 109)
            frame(v * 31000 shr 15)
        }
    }

    /** A 190 Hz body under noise; the noise rings longer; 0.2 s. */
    private fun snare(): List<Int> = Voice().run {
        var noiseAmp = 1 shl 16
        List(frames(1, 5)) {
            val body = sine(190_000) * amp shr 16
            amp = decay(amp, 218)
            val hiss = (noise() shr 16) * noiseAmp shr 16
            noiseAmp = decay(noiseAmp, 142)
            frame((body * 16000 shr 15) + (hiss * 22000 shr 15))
        }
    }

    /** High-passed noise (each value minus a running average), ~12 ms half-life; 60 ms. */
    private fun hat(): List<Int> = Voice().run {
        var low = 0
        List(frames(3, 50)) {
            val n = noise() shr 16
            low += (n - low) shr 2
            val out = ((n - low).toLong() * amp shr 16).toInt()
            amp = decay(amp, 540)
            frame(out * 30000 shr 15)
        }
    }

    /** 140 Hz settling to 95 Hz, ~80 ms half-life; 0.3 s. */
    private fun tom(): List<Int> = Voice().run {
        List(frames(3, 10)) {
            val f = 95_000 + (45_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 190)
            val v = sine(f) * amp shr 16
            amp = decay(amp, 81)
            frame(v * 30000 shr 15)
        }
    }

    /** Oscillator, envelopes and noise state; all fixed point (amplitudes Q16, phase 16.16 of a 65536 cycle). */
    private class Voice {
        var phase = 0L
        var amp = 1 shl 16
        var sweep = 1 shl 16
        private var seed = 0x6280_1987

        /** Phase step per frame for [milliHz], in 16.16 cycle units. */
        fun step(milliHz: Int): Long = milliHz.toLong() * 65536L * 65536L / (RATE_HZ * 1000L)

        /** The next value of a sine at [milliHz], -32767..32767. */
        fun sine(milliHz: Int): Int {
            phase += step(milliHz)
            return sin16((phase shr 16).toInt())
        }

        /** xorshift32: deterministic white noise over the whole Int range. */
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

    /** A 16-bit value as a 5-bit D/A frame, as `.wav` imports convert ([PceSamples.level]). */
    private fun frame(v: Int): Int = PceSamples.level(v)
}
