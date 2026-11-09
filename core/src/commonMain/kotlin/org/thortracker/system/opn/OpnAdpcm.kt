package org.thortracker.system.opn

import org.thortracker.backend.OpnPlayerImage
import org.thortracker.system.nes.Dpcm
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Yamaha ADPCM-B, the YM2608's sample format, exactly as ymfm's decoder (`adpcm_b_channel::clock`, vendored
 * `ymfm_adpcm.cpp`) plays it: each 4-bit code (high nibble of a byte first) adds ±(2 × (code & 7) + 1) × step / 8 to a
 * 16-bit accumulator (bit 3 = minus; clamped to −32768..32767), then the step is scaled by {57, 57, 57, 57, 77, 102,
 * 128, 153} / 64 of its magnitude bits and clamped to 127..24576. Every key-on (and every repeat) starts from
 * accumulator 0, step 127.
 *
 * [encode] models that decoder exactly: at each sample it picks the code whose decoded value lands nearest the input
 * from the decoder's actual state, so the error never accumulates.
 */
object OpnAdpcm {
    const val STEP_MIN = 127
    const val STEP_MAX = 24576

    private val STEP_SCALE = intArrayOf(57, 57, 57, 57, 77, 102, 128, 153)

    /** Recording rates the import offers; any 1000..55466 Hz is valid. */
    val RATES = listOf(8000, 11025, 16000, 18500, 22050, 32000, 44100, 55466)

    /** Codes per padding unit: 32 bytes, the chip's start/end address granularity. */
    private const val PAD_CODES = OpnPlayerImage.ADPCM_ALIGN * 2

    /** The decoder's state: the accumulator and the step. */
    private class State {
        var accumulator = 0
        var step = STEP_MIN

        /** The accumulator after [code] from this state (the state is unchanged). */
        fun peek(code: Int): Int {
            var delta = (2 * (code and 7) + 1) * step / 8
            if (code and 8 != 0) delta = -delta
            return (accumulator + delta).coerceIn(-32768, 32767)
        }

        /** Applies [code]; returns the new accumulator. */
        fun feed(code: Int): Int {
            accumulator = peek(code)
            step = (step * STEP_SCALE[code and 7] / 64).coerceIn(STEP_MIN, STEP_MAX)
            return accumulator
        }
    }

    /**
     * [pcm] (16-bit) as ADPCM-B, two codes per byte, high nibble first, padded with code 0 to a multiple of 64 codes
     * (32 bytes, a whole number of the chip's address units).
     */
    fun encode(pcm: ShortArray): ByteArray {
        val codes = (pcm.size + PAD_CODES - 1) / PAD_CODES * PAD_CODES
        val out = ByteArray(codes / 2)
        val state = State()
        for (i in pcm.indices) {
            val target = pcm[i].toInt()
            var best = 0
            var bestError = Int.MAX_VALUE
            for (code in 0..15) {
                val error = abs(state.peek(code) - target)
                if (error < bestError) {
                    bestError = error
                    best = code
                }
            }
            state.feed(best)
            val byte = i shr 1
            out[byte] = if (i and 1 == 0) (best shl 4).toByte() else (out[byte].toInt() or best).toByte()
        }
        // The padding codes are 0: already in [out].
        return out
    }

    /** The samples [adpcm] decodes to (two per byte), as ymfm's decoder produces them before its output interpolation. */
    fun decode(adpcm: AdpcmBytes): ShortArray {
        val out = ShortArray(adpcm.size * 2)
        val state = State()
        for (i in 0 until adpcm.size) {
            val b = adpcm[i]
            out[2 * i] = state.feed(b shr 4).toShort()
            out[2 * i + 1] = state.feed(b and 15).toShort()
        }
        return out
    }

    /**
     * A sample from the `.wav` file [bytes] named [name]: mono (stereo is mixed down), resampled to [rate] Hz, or kept
     * at the file's rate clamped to 1000..55466 Hz.
     * @throws IllegalArgumentException with an actionable message for files that cannot be imported.
     */
    fun importWav(name: String, bytes: ByteArray, rate: Int? = null): OpnaSample {
        val wav = Dpcm.Wav.read(bytes)
        require(wav.samples.isNotEmpty()) { "The .wav file has no audio" }
        val target = rate ?: wav.rate.coerceIn(OpnPlayerImage.RATE_MIN, OpnPlayerImage.RATE_MAX)
        require(target in OpnPlayerImage.RATE_MIN..OpnPlayerImage.RATE_MAX) {
            "A sample rate of $target Hz cannot be played; the YM2608 plays samples recorded at ${OpnPlayerImage.RATE_MIN}-${OpnPlayerImage.RATE_MAX} Hz"
        }
        val pcm = resample(wav.samples, wav.rate, target)
        val bytesNeeded = (pcm.size + PAD_CODES - 1) / PAD_CODES * PAD_CODES / 2
        require(bytesNeeded <= OpnPlayerImage.ADPCM_MEMORY) {
            val fits = OpnPlayerImage.ADPCM_MEMORY * 2.0 / target
            "The sound is ${seconds(pcm.size.toDouble() / target)} long at $target Hz; the YM2608's 256 KiB of sample memory " +
                "hold ${seconds(fits)} at that rate. Trim the file or import it at a lower rate"
        }
        return OpnaSample(name.take(OpnaSample.MAX_NAME), target, AdpcmBytes(encode(pcm)))
    }

    private fun seconds(s: Double): String = "${(s * 10).roundToInt() / 10.0} s"

    /**
     * [pcm] recorded at [from] Hz as [to] Hz: linear interpolation when raising the rate; when lowering it, each output
     * sample is the mean of the input samples it spans (a box filter against aliasing).
     */
    fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        require(from > 0 && to > 0) { "Sample rates must be positive" }
        if (from == to || pcm.isEmpty()) return pcm.copyOf()
        val count = maxOf(1, (pcm.size.toLong() * to / from).toInt())
        val ratio = from.toDouble() / to
        return if (to > from) {
            ShortArray(count) { i ->
                val pos = i * ratio
                val a = pos.toInt().coerceAtMost(pcm.size - 1)
                val b = (a + 1).coerceAtMost(pcm.size - 1)
                val f = pos - a
                (pcm[a] + (pcm[b] - pcm[a]) * f).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        } else {
            ShortArray(count) { i ->
                val start = (i * ratio).toInt().coerceAtMost(pcm.size - 1)
                val end = ((i + 1) * ratio).toInt().coerceIn(start + 1, pcm.size)
                var sum = 0L
                for (k in start until end) sum += pcm[k]
                (sum.toDouble() / (end - start)).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }
}
