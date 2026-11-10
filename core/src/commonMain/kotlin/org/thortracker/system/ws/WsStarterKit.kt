package org.thortracker.system.ws

/**
 * Built-in 8-bit samples for WonderSwan songs: a kick, snare, closed hi-hat and tom, synthesized here (no recordings,
 * so no third-party licences) at [RATE_HZ] = 6000 Hz — a new song's voice-timer rate (reload 2) and Sound DMA's rate 1 —
 * as unsigned bytes around the centre 128. Synthesis is integer-only (parabolic sine, fixed-point decays, xorshift
 * noise), so every platform makes the same bytes. A CPU-fed sound playing one of them at its root note ([instrument],
 * C-4) at the default timer, or a DMA sound at rate 1, plays it at its own pitch.
 */
object WsStarterKit {
    /** The rate the kit is synthesized at: 12000 / 2 voice-timer interrupts per second, = Sound DMA rate 1. */
    const val RATE_HZ = 6000

    /** The Sound DMA rate code (0..3) that plays the kit at its own rate: 6000 bytes per second. */
    const val DMA_RATE = 1

    const val KICK = 0
    const val SNARE = 1
    const val HAT = 2
    const val TOM = 3

    /** The kit's samples (mono), in the order of [KICK], [SNARE], [HAT], [TOM]. */
    fun samples(): List<WsSample> = listOf(
        WsSample("Kick", kick(), rate = RATE_HZ),
        WsSample("Snare", snare(), rate = RATE_HZ),
        WsSample("Hat", hat(), rate = RATE_HZ),
        WsSample("Tom", tom(), rate = RATE_HZ),
    )

    /**
     * A sound playing sample [sample] (an index into the song's [WsSound.samples]) once, named [name], at full volume:
     * fed by the CPU on its root note C-4, or by Sound DMA at [DMA_RATE] when [feed] is [WsFeed.DMA].
     */
    fun instrument(sample: Int, name: String, feed: WsFeed = WsFeed.CPU): WsInstrument =
        WsInstrument(name = name, kind = WsKind.SAMPLE, sample = sample, feed = feed, root = WsInstrument.ROOT, rate = DMA_RATE)

    private fun frames(seconds: Int, per: Int) = RATE_HZ * seconds / per

    /** 160 Hz falling to 45 Hz within ~30 ms, ~60 ms amplitude half-life; 0.25 s. */
    private fun kick(): List<Int> = Voice().run {
        List(frames(1, 4)) {
            val f = 45_000 + (115_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 304)
            val v = sine(f) * amp shr 16
            amp = decay(amp, 127)
            frame(v * 31000 shr 15)
        }
    }

    /** A 190 Hz body under noise; the noise rings longer; 0.2 s. */
    private fun snare(): List<Int> = Voice().run {
        var noiseAmp = 1 shl 16
        List(frames(1, 5)) {
            val body = sine(190_000) * amp shr 16
            amp = decay(amp, 254)
            val hiss = (noise() shr 16) * noiseAmp shr 16
            noiseAmp = decay(noiseAmp, 165)
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
            amp = decay(amp, 630)
            frame(out * 30000 shr 15)
        }
    }

    /** 140 Hz settling to 95 Hz, ~80 ms half-life; 0.3 s. */
    private fun tom(): List<Int> = Voice().run {
        List(frames(3, 10)) {
            val f = 95_000 + (45_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 221)
            val v = sine(f) * amp shr 16
            amp = decay(amp, 95)
            frame(v * 30000 shr 15)
        }
    }

    /** Oscillator, envelopes and noise state; all fixed point (amplitudes Q16, phase 16.16 of a 65536 cycle). */
    private class Voice {
        var phase = 0L
        var amp = 1 shl 16
        var sweep = 1 shl 16
        private var seed = 0x5A_2000_1F

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

    /** A 16-bit value as an unsigned 8-bit byte, as `.wav` imports convert ([WsSamples.level]). */
    private fun frame(v: Int): Int = WsSamples.level(v)
}
