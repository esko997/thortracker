package org.thortracker.system.pce

/**
 * Built-in DDA samples for PC Engine songs: a kick, snare, closed hi-hat and tom, synthesized here (no recordings, so
 * no third-party licences) at the song's DDA rate ([PceSamples.rateHz] of its timer reload, as `.wav` imports are
 * converted), as 5-bit frames 0..31 around the centre 16. Synthesis is integer-only (parabolic sine, fixed-point
 * decays, xorshift noise), so every platform makes the same frames. A sound playing one of them at its root note
 * ([instrument], C-4) at that rate plays it at its own pitch and length. Samples already in a song keep their frames.
 */
object PceStarterKit {
    const val KICK = 0
    const val SNARE = 1
    const val HAT = 2
    const val TOM = 3

    /**
     * The kit's samples, in the order of [KICK], [SNARE], [HAT], [TOM], synthesized at [rateHz] (one frame per DDA
     * timer interrupt at the root note): oscillators step by frequency / [rateHz], lengths are seconds × [rateHz], and
     * the decays keep their times (see [Voice.elapse]).
     */
    fun samples(rateHz: Int): List<PceSample> {
        require(rateHz > 0) { "Sample rate $rateHz Hz is not positive" }
        return listOf(
            PceSample("Kick", kick(rateHz)),
            PceSample("Snare", snare(rateHz)),
            PceSample("Hat", hat(rateHz)),
            PceSample("Tom", tom(rateHz)),
        )
    }

    /**
     * A sound playing sample [sample] (an index into the song's [PceSound.samples]) once at its own rate on its root
     * note, C-4, named [name], at full volume.
     */
    fun instrument(sample: Int, name: String): PceInstrument =
        PceInstrument(name = name, kind = PceKind.SAMPLE, sample = sample, root = PceInstrument.ROOT)

    /**
     * The rate the decay constants below are given at: one decay step per frame at the DDA rate of timer reload 0,
     * 7159090.9 / 1024 Hz rounded down.
     */
    private const val DECAY_HZ = 6991

    /** [seconds] / [per] of a second at [rate], at least one frame. */
    private fun frames(rate: Int, seconds: Int, per: Int) = maxOf(1, rate * seconds / per)

    /** 160 Hz falling to 45 Hz within ~30 ms, ~60 ms amplitude half-life; 0.25 s. */
    private fun kick(rate: Int): List<Int> = Voice(rate).run {
        List(frames(rate, 1, 4)) {
            val n = elapse()
            val f = 45_000 + (115_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 261, n)
            val v = sine(f) * amp shr 16
            amp = decay(amp, 109, n)
            frame(v * 31000 shr 15)
        }
    }

    /** A 190 Hz body under noise; the noise rings longer; 0.2 s. */
    private fun snare(rate: Int): List<Int> = Voice(rate).run {
        var noiseAmp = 1 shl 16
        List(frames(rate, 1, 5)) {
            val n = elapse()
            val body = sine(190_000) * amp shr 16
            amp = decay(amp, 218, n)
            val hiss = (noise() shr 16) * noiseAmp shr 16
            noiseAmp = decay(noiseAmp, 142, n)
            frame((body * 16000 shr 15) + (hiss * 22000 shr 15))
        }
    }

    /** High-passed noise (each value minus a running average), ~12 ms half-life; 60 ms. */
    private fun hat(rate: Int): List<Int> = Voice(rate).run {
        var low = 0
        List(frames(rate, 3, 50)) {
            val n = elapse()
            val white = noise() shr 16
            low += (white - low) shr 2
            val out = ((white - low).toLong() * amp shr 16).toInt()
            amp = decay(amp, 540, n)
            frame(out * 30000 shr 15)
        }
    }

    /** 140 Hz settling to 95 Hz, ~80 ms half-life; 0.3 s. */
    private fun tom(rate: Int): List<Int> = Voice(rate).run {
        List(frames(rate, 3, 10)) {
            val n = elapse()
            val f = 95_000 + (45_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 190, n)
            val v = sine(f) * amp shr 16
            amp = decay(amp, 81, n)
            frame(v * 30000 shr 15)
        }
    }

    /**
     * Oscillator, envelopes and noise state at [rate] frames per second; all fixed point (amplitudes Q16, phase 16.16
     * of a 65536 cycle).
     */
    private class Voice(private val rate: Int) {
        var phase = 0L
        var amp = 1 shl 16
        var sweep = 1 shl 16
        private var seed = 0x6280_1987
        private var at = 0L

        /**
         * Starts the next frame: returns the decay steps it takes, ⌊(i + 1) · [DECAY_HZ] / rate⌋ − ⌊i · [DECAY_HZ] /
         * rate⌋ for frame i (one at [DECAY_HZ]), so every envelope reaches the same value at the same time at any rate.
         */
        fun elapse(): Int {
            val steps = ((at + 1) * DECAY_HZ / rate - at * DECAY_HZ / rate).toInt()
            at++
            return steps
        }

        /** Phase step per frame for [milliHz], in 16.16 cycle units. */
        fun step(milliHz: Int): Long = milliHz.toLong() * 65536L * 65536L / (rate * 1000L)

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

    /** [n] exponential decay steps: [v] × (1 − [k] / 65536)ⁿ. */
    private fun decay(v: Int, k: Int, n: Int): Int {
        var out = v
        repeat(n) { out = ((out.toLong() * (65536 - k)) shr 16).toInt() }
        return out
    }

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
