package org.thortracker.system.nes

/**
 * Built-in DPCM samples for NES songs: a kick, snare, closed hi-hat, tom and plucked bass, synthesized here (no
 * recordings, so no third-party licences) and encoded with [Dpcm]'s FamiStudio encoder at the top rate (33.1 kHz).
 * Synthesis is integer-only (parabolic sine, fixed-point decays, xorshift noise), so every platform makes the same
 * bytes. [instrument] maps them General MIDI style one octave up: kick C-3, snare D-3, hat F#-3, low tom G-3 (the
 * tom at a lower rate), tom A-3, bass C-4.
 */
object DpcmStarterKit {
    /** The DMC's top rate (pitch 15), the rate the kit is synthesized at: 1789773 / 54 Hz. */
    private const val RATE = 33144

    const val KICK = 0
    const val SNARE = 1
    const val HAT = 2
    const val TOM = 3
    const val BASS = 4

    /** The kit's samples, in the order [instrument] expects them. */
    fun samples(): List<DpcmSample> = listOf(
        DpcmSample("Kick", encode(kick())),
        DpcmSample("Snare", encode(snare())),
        DpcmSample("Hat", encode(hat())),
        DpcmSample("Tom", encode(tom())),
        DpcmSample("Bass", encode(bass())),
    )

    /** Absolute note → (kit sample, DMC rate) of [instrument]. */
    val NOTES: Map<Int, Pair<Int, Int>> = linkedMapOf(
        36 to (KICK to 15), 38 to (SNARE to 15), 42 to (HAT to 15), 43 to (TOM to 13), 45 to (TOM to 15), 48 to (BASS to 15),
    )

    /** An instrument playing the kit, whose samples start at index [firstSample] of the song's samples. */
    fun instrument(firstSample: Int, name: String = "Drum kit"): NesInstrument =
        NesInstrument(name = name, dpcm = NOTES.mapValues { (_, v) -> DpcmMapping(firstSample + v.first, pitch = v.second) })

    /** [sound] with the kit's samples appended and instrument [slot] (1-based) replaced by [instrument]. */
    fun addTo(sound: NesSound, slot: Int): NesSound = sound.copy(
        samples = sound.samples + samples(),
        instruments = sound.instruments.mapIndexed { i, ins -> if (i == slot - 1) instrument(sound.samples.size) else ins },
    )

    private fun encode(pcm: ShortArray): List<Int> {
        val rate = Dpcm.RATES_HZ[15]
        val bytes = Dpcm.waveToDpcm(pcm, rate, rate, DpcmSample.DEFAULT_DMC_INITIAL).map { it.toInt() and 0xFF }
        return Dpcm.padded(bytes)
    }

    private fun samples(seconds: Int, per: Int) = RATE * seconds / per

    /** 160 Hz falling to 45 Hz within ~30 ms, ~60 ms amplitude half-life. */
    private fun kick(): ShortArray = Voice().run {
        ShortArray(samples(9, 50)) { i ->
            val f = 45_000 + (115_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 55)
            val v = sine(f) * amp shr 16
            amp = decay(amp, 23)
            clip(v * 30000 shr 15)
        }
    }

    /** A 190 Hz body under noise (softened so the DMC can follow it); the noise rings longer. */
    private fun snare(): ShortArray = Voice().run {
        var noiseAmp = 1 shl 16
        var soft = 0
        ShortArray(samples(4, 25)) {
            val body = sine(190_000) * amp shr 16
            amp = decay(amp, 46)
            soft += ((noise() shr 16) - soft) shr 1
            val hiss = soft * noiseAmp shr 16
            noiseAmp = decay(noiseAmp, 30)
            clip((body * 18000 shr 15) + (hiss * 30000 shr 15))
        }
    }

    /**
     * Noise band-passed to roughly 1.5-6 kHz, ~12 ms half-life. The DMC moves its level by at most 2 a sample, so
     * brighter noise would come out quieter; the hat stays a soft tick under the noise channel's hats.
     */
    private fun hat(): ShortArray = Voice().run {
        var low = 0
        var lower = 0
        ShortArray(samples(3, 50)) {
            val n = noise() shr 16
            low += (n - low) shr 1
            lower += (low - lower) shr 3
            val out = (low - lower) * amp shr 16
            amp = decay(amp, 114)
            clip(out * 40000 shr 15)
        }
    }

    /** 140 Hz settling to 95 Hz, ~80 ms half-life. */
    private fun tom(): ShortArray = Voice().run {
        ShortArray(samples(1, 4)) {
            val f = 95_000 + (45_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 40)
            val v = sine(f) * amp shr 16
            amp = decay(amp, 17)
            clip(v * 26000 shr 15)
        }
    }

    /** A1 (55 Hz) with its octave, plucked: the octave fades first. */
    private fun bass(): ShortArray = Voice().run {
        var phase2 = 0L
        var amp2 = 1 shl 16
        ShortArray(samples(2, 5)) {
            val fundamental = sine(55_000) * amp shr 16
            phase2 += step(110_000)
            val octave = sin16((phase2 shr 16).toInt()) * amp2 shr 16
            amp = decay(amp, 9)
            amp2 = decay(amp2, 30)
            clip((fundamental * 20000 shr 15) + (octave * 9000 shr 15))
        }
    }

    /** Oscillator, envelopes and noise state; all fixed point (amplitudes Q16, phase 16.16 of a 65536 cycle). */
    private class Voice {
        var phase = 0L
        var amp = 1 shl 16
        var sweep = 1 shl 16
        private var seed = 0x2A03_1983

        /** Phase step per sample for [milliHz], in 16.16 cycle units. */
        fun step(milliHz: Int): Long = milliHz.toLong() * 65536L * 65536L / (RATE * 1000L)

        /** The next sample of a sine at [milliHz], -32768..32767. */
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

    private fun clip(v: Int): Short = v.coerceIn(-32768, 32767).toShort()
}
