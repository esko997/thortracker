package org.thortracker.system.pokey

import org.thortracker.model.Song
import org.thortracker.system.nes.Dpcm
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * POKEY sample data: `.wav` import to 4-bit levels, the built-in drum kit and the song's sample memory.
 *
 * Levels are what a sample voice writes to AUDC in volume-only mode (0..15, the channel's DAC). The mapping is
 * **linear** in amplitude (the chip's mild DAC nonlinearity, docs/AUDIO_ENGINE.md "Mix and output", is not
 * pre-compensated) and **envelope-biased**: each sample value s is offset by a peak-following envelope e ≥ |s| (instant
 * attack, ~5 ms half-life), level = round((s + e) · 15 / (2 · max e)). Silence maps to level 0, the level the channel
 * rests at between notes, so samples start and end without a click; the slow envelope offset is a low-frequency
 * component the output's AC coupling mostly removes.
 */
object PokeySamples {
    /**
     * Offered import rates (levels per second at the root note): the rates the NTSC 64 kHz clock plays exactly
     * (1789773 / 28 / (N + 1), N = 4, 5, 7, 9, 11, 15). 12784 Hz (N = 4, 140 cycles) is the fastest a 64 kHz sample
     * channel plays within the player's 114-cycle minimum; faster rates need POKEY 1 at 1.79 MHz or the 15 kHz clock,
     * and the rate row reaches them by hand.
     */
    val IMPORT_RATES: List<Int> = listOf(3995, 5327, 6392, 7990, 10653, 12784)

    /** The rate [starterKit] is made at (= [PokeyInstrument.DEFAULT_RATE]). */
    const val KIT_RATE = PokeyInstrument.DEFAULT_RATE

    /**
     * A `.wav` file (PCM 8/16/24/32-bit or float, mono or stereo, any rate; stereo mixed to mono) as 4-bit levels at
     * [rate]: linear resampling, then the envelope-biased mapping above, cut at [PokeySample.MAX_LENGTH] levels.
     * @throws IllegalArgumentException with a message for the user when the file cannot be read or holds silence.
     */
    fun fromWav(bytes: ByteArray, rate: Int): List<Int> {
        require(rate in PokeyInstrument.RATES) { "Sample rate $rate is outside ${PokeyInstrument.RATES.first}-${PokeyInstrument.RATES.last} levels per second" }
        val wav = Dpcm.Wav.read(bytes)
        require(wav.samples.isNotEmpty()) { "The .wav file has no audio" }
        val count = min(PokeySample.MAX_LENGTH.toLong(), (wav.samples.size.toLong() * rate + wav.rate / 2) / wav.rate).toInt()
        require(count > 0) { "The .wav file is too short to make a sample at $rate levels per second" }
        val pcm = IntArray(count) { i ->
            val pos = i.toDouble() * wav.rate / rate
            val i0 = min(floor(pos).toInt(), wav.samples.size - 1)
            val i1 = min(i0 + 1, wav.samples.size - 1)
            val a = pos - floor(pos)
            (wav.samples[i0] * (1 - a) + wav.samples[i1] * a).roundToInt()
        }
        val levels = levels(pcm, rate)
        require(levels.any { it != 0 }) { "The .wav file is silent" }
        return levels
    }

    /** [pcm] (16-bit values at [rate]) as envelope-biased linear levels 0..15. */
    internal fun levels(pcm: IntArray, rate: Int): List<Int> {
        // Peak follower: instant attack, release halving every ~5 ms.
        val keep = 1.0 - 0.6931 / (0.005 * rate)
        val envelope = DoubleArray(pcm.size)
        var e = 0.0
        for (i in pcm.indices) {
            e = max(abs(pcm[i]).toDouble(), e * keep)
            envelope[i] = e
        }
        val peak = envelope.maxOrNull() ?: 0.0
        if (peak <= 0.0) return List(pcm.size) { 0 }
        return List(pcm.size) { i -> ((pcm[i] + envelope[i]) * 15.0 / (2.0 * peak)).roundToInt().coerceIn(0, 15) }
    }

    /** Sample memory [song]'s samples take, in levels; an odd-length sample also takes the rest of its last byte. */
    fun used(song: Song): Int = used(song.pokey.samples)

    fun used(samples: List<PokeySample>): Int = samples.sumOf { it.levels.size + (it.levels.size and 1) }

    /** Sample memory left ([PokeySample.MEMORY] − [used]). */
    fun free(song: Song): Int = PokeySample.MEMORY - used(song)

    /**
     * The built-in digi drums, synthesised here (no recordings, no third-party licences) at [KIT_RATE] with integer-only
     * arithmetic so every platform makes the same levels: [KICK], [SNARE], [HAT], [TOM]. Each is one-sided (silence =
     * level 0), decays, and fades to 0 over its last [FADE] levels. [PokeyPresets.drumKit] makes the sounds that play them.
     */
    fun starterKit(): List<PokeySample> = listOf(
        PokeySample("Kick", fadedOut(kick())),
        PokeySample("Snare", fadedOut(snare())),
        PokeySample("Hat", fadedOut(hat())),
        PokeySample("Tom", fadedOut(tom())),
    )

    const val KICK = 0
    const val SNARE = 1
    const val HAT = 2
    const val TOM = 3

    /** Levels the kit's samples fade out over (4 ms at the kit rate). */
    private const val FADE = 32

    /** [levels] scaled linearly down to 0 over the last [FADE] levels (integer, rounding down). */
    private fun fadedOut(levels: List<Int>): List<Int> =
        levels.mapIndexed { i, v -> (levels.size - 1 - i).let { left -> if (left >= FADE) v else v * left / FADE } }

    /** Levels for [seconds100] hundredths of a second at the kit rate. */
    private fun length(seconds100: Int) = KIT_RATE * seconds100 / 100

    /** 150 Hz falling to 48 Hz within ~40 ms, ~50 ms amplitude half-life: 0.25 s. */
    private fun kick(): List<Int> = Voice().run {
        List(length(25)) {
            val f = 48_000 + (102_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 120)
            val v = sine(f) * amp shr 16
            val a = amp
            amp = decay(amp, 90)
            level(v, a)
        }
    }

    /** A 185 Hz body under noise; the noise rings longer: 0.2 s. */
    private fun snare(): List<Int> = Voice().run {
        var noiseAmp = 1 shl 16
        List(length(20)) {
            val body = sine(185_000) * amp shr 16
            val a = amp
            amp = decay(amp, 180)
            val hiss = (noise() shr 16) * noiseAmp shr 16
            val n = noiseAmp
            noiseAmp = decay(noiseAmp, 110)
            level((body * 9 + hiss * 7) / 16, (a * 9 + n * 7) / 16)
        }
    }

    /** High-passed noise (the difference of successive values), ~10 ms half-life: 0.06 s. */
    private fun hat(): List<Int> = Voice().run {
        var last = 0
        List(length(6)) {
            val n = noise() shr 17
            val v = (n - last) * amp shr 16
            last = n
            val a = amp
            amp = decay(amp, 570)
            level(v, a)
        }
    }

    /** 130 Hz settling to 92 Hz, ~70 ms half-life: 0.3 s. */
    private fun tom(): List<Int> = Voice().run {
        List(length(30)) {
            val f = 92_000 + (38_000L * sweep shr 16).toInt()
            sweep = decay(sweep, 60)
            val v = sine(f) * amp shr 16
            val a = amp
            amp = decay(amp, 82)
            level(v, a)
        }
    }

    /** A signed value [v] (−32768..32767 at full amplitude) riding on its amplitude [amp] (Q16): (v + a) · 15 / 2a_full. */
    private fun level(v: Int, amp: Int): Int {
        val bias = (amp.toLong() * 32767 shr 16).toInt()
        return (((v + bias).toLong() * 15 + 32767) / 65534).toInt().coerceIn(0, 15)
    }

    /** Oscillator, envelopes and noise state; fixed point (amplitudes Q16, phase 16.16 of a 65536 cycle). */
    private class Voice {
        var phase = 0L
        var amp = 1 shl 16
        var sweep = 1 shl 16
        private var seed = 0x0C01_2294

        fun step(milliHz: Int): Long = milliHz.toLong() * 65536L * 65536L / (KIT_RATE * 1000L)

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
}
