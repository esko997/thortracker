package org.thortracker.system.nes

import org.thortracker.backend.NesDpcm
import org.thortracker.model.Song
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * DPCM sample data: importing `.dmc` files, converting `.wav` files the way FamiStudio 4.5.3 does (`WaveFile.Load`,
 * `DPCMSample.Process`, `WaveUtils.WaveToDpcm`; same float arithmetic, so the same bytes), decoding for display, and
 * the sample memory budget. Common code only: the Deck build is Kotlin/Native.
 *
 * One deliberate difference from FamiStudio's defaults: converted samples are padded to 16n + 1 bytes (FamiStudio's
 * "pad to 16 bytes + 1" mode) rather than 16n, because [DpcmSample.data] is exactly what the hardware plays (the length
 * register plays 16n + 1 bytes; a 16n-byte FamiStudio sample also plays the byte after it).
 */
object Dpcm {
    /** DMC rates in Hz for pitch 0..15 on NTSC (FamiStudio's `DPCMSampleRate.Frequencies`, as floats). */
    val RATES_HZ: FloatArray = floatArrayOf(
        4181.7124f, 4709.9287f, 5264.038f, 5593.0405f, 6257.9478f, 7046.3506f, 7919.3496f, 8363.425f,
        9419.857f, 11186.081f, 12604.035f, 13982.602f, 16884.65f, 21306.822f, 24857.959f, 33143.945f,
    )

    /**
     * Most bytes of samples without bank switching: whole 64-byte slots in $C000-$FFF9 (the CPU vectors follow). Song
     * data and samples share $8C00-$FFF9 ([REGION]): the samples sit at the top, the song must end below them.
     */
    const val MEMORY = (0xFFFA - 0xC000) / 64 * 64

    /** Bytes song data and samples share: $8C00-$FFF9. */
    const val REGION = 0xFFFA - 0x8C00

    /** Samples start on 64-byte boundaries ($4012 counts in 64 bytes). */
    const val ALIGN = 64

    /** Bytes [size] bytes of sample take in memory: rounded up to the next 64-byte slot. */
    fun aligned(size: Int): Int = (size + ALIGN - 1) / ALIGN * ALIGN

    /** Bytes of the samples [song] plays in 64-byte slots (identical samples count once), as the lowering places them. */
    fun memoryUsed(song: Song): Int = NesDpcm.memoryUsed(song)

    /**
     * A `.dmc` file (raw DMC bytes): padded to 16n + 1 bytes with FamiStudio's filler (`$55`, or `$AA` after a byte
     * whose last bit rises, so the level keeps still) and cut at [DpcmSample.MAX_BYTES] like FamiStudio's import.
     * @throws IllegalArgumentException for an empty file.
     */
    fun fromDmc(bytes: ByteArray): List<Int> {
        require(bytes.isNotEmpty()) { "The .dmc file is empty" }
        return padded(bytes.map { it.toInt() and 0xFF })
    }

    /**
     * A `.wav` file (PCM 8/16/24/32-bit or float 32/64-bit, mono or stereo, any rate) as DMC bytes for DMC rate
     * [rate] (0..15), starting from level [dmcInitial] × 2: FamiStudio's import (stereo mixed to mono, at most two
     * seconds of source) and conversion (linear resampling, then each bit steps toward the next sample), padded to
     * 16n + 1 bytes and cut at [DpcmSample.MAX_BYTES].
     * @throws IllegalArgumentException with a message for the user when the file cannot be read.
     */
    fun fromWav(bytes: ByteArray, rate: Int = 15, dmcInitial: Int = DpcmSample.DEFAULT_DMC_INITIAL): List<Int> {
        require(rate in DpcmMapping.PITCH) { "DMC rate $rate is outside 0-15" }
        require(dmcInitial in DpcmSample.DMC_INITIAL) { "DMC initial value $dmcInitial is outside 0-63" }
        val wav = Wav.read(bytes)
        // FamiStudio's sample import keeps the first two seconds.
        val samples = if (wav.samples.size > wav.rate * 2) wav.samples.copyOf(wav.rate * 2) else wav.samples
        require(samples.isNotEmpty()) { "The .wav file has no audio" }
        val encoded = waveToDpcm(samples, wav.rate.toFloat(), RATES_HZ[rate], dmcInitial)
        require(encoded.isNotEmpty()) { "The .wav file is too short to make a DPCM sample at this rate" }
        return padded(encoded.map { it.toInt() and 0xFF })
    }

    /**
     * The 7-bit output level (0..127) the DMC holds while playing each bit of [data] (8 per byte, least significant
     * first), starting from [dmcInitial] × 2: a 1 bit adds 2 unless above 125, a 0 bit subtracts 2 unless below 2.
     */
    fun decode(data: List<Int>, dmcInitial: Int = DpcmSample.DEFAULT_DMC_INITIAL): IntArray {
        val out = IntArray(data.size * 8)
        var level = dmcInitial * 2
        for ((i, b) in data.withIndex()) for (bit in 0 until 8) {
            out[i * 8 + bit] = level
            if ((b shr bit) and 1 != 0) { if (level <= 125) level += 2 } else if (level >= 2) level -= 2
        }
        return out
    }

    /** FamiStudio's `PadTo16BytesPlusOne` padding, then its length clamp. */
    internal fun padded(data: List<Int>): List<Int> {
        val size = (data.size - 1 + 15) / 16 * 16 + 1
        val fill = if ((data.last() and 0x80) != 0) 0xAA else 0x55
        val padded = if (size > data.size) data + List(size - data.size) { fill } else data
        return if (padded.size > DpcmSample.MAX_BYTES) padded.subList(0, DpcmSample.MAX_BYTES) else padded
    }

    /** FamiStudio's `WaveUtils.WaveToDpcm` without rounding (`WaveToDpcmRoundingMode.None`), in its float arithmetic. */
    internal fun waveToDpcm(wave: ShortArray, waveRate: Float, dpcmRate: Float, counterStart: Int): ByteArray {
        val count = round((wave.size.toFloat() * (dpcmRate / waveRate)).toDouble()).toInt()
        val resampled = resample(wave, count)
        val dpcm = ByteArray((count + 7) / 8)
        var counter = counterStart
        var lastBit = 0
        for (i in resampled.indices) {
            val up = if (i != resampled.size - 1) {
                val distUp = abs(level(min(counter + 1, 63)) - resampled[i + 1])
                val distDown = abs(level(max(counter - 1, 0)) - resampled[i + 1])
                distUp < distDown
            } else {
                level(counter) < resampled[i]
            }
            if (up) {
                dpcm[i / 8] = (dpcm[i / 8].toInt() or (1 shl (i and 7))).toByte()
                counter = min(counter + 1, 63)
                lastBit = 1
            } else {
                counter = max(counter - 1, 0)
                lastBit = 0
            }
        }
        // The last byte's unused bits alternate so the level holds.
        for (i in resampled.size until (resampled.size + 7) / 8 * 8) {
            if (lastBit == 0) dpcm[i / 8] = (dpcm[i / 8].toInt() or (1 shl (i and 7))).toByte()
            lastBit = lastBit xor 1
        }
        return dpcm
    }

    /** `WaveUtils.DpcmCounterToWaveSample`: FamiStudio's 6-bit counter as a 16-bit sample. */
    private fun level(counter: Int) = counter * (65536 / 64) - 32768

    /** `WaveUtils.Resample`: linear interpolation (float), truncated to 16 bits. */
    private fun resample(source: ShortArray, size: Int): IntArray {
        val ratio = source.size / size.toFloat()
        return IntArray(size) { i ->
            val pos = i * ratio
            val i0 = min(floor(pos.toDouble()).toInt(), source.size - 1)
            val i1 = min(kotlin.math.ceil(pos.toDouble()).toInt(), source.size - 1)
            val alpha = pos - pos.toInt()
            val s0 = source[i0].toFloat()
            val s1 = source[i1].toFloat()
            (s0 * (1.0f - alpha) + s1 * alpha).toInt().toShort().toInt()
        }
    }

    /**
     * A decoded `.wav`: mono 16-bit samples (stereo mixed) and their rate; [channels] keeps each channel as read (one
     * array for mono files, left then right for stereo ones) for importers that use stereo (the WonderSwan Color's).
     */
    internal class Wav(val samples: ShortArray, val rate: Int, val channels: List<ShortArray> = listOf(samples)) {
        companion object {
            /** FamiStudio's `WaveFile.LoadInternal`, including how it walks chunks and converts formats. */
            fun read(b: ByteArray): Wav {
                fun u8(at: Int) = b[at].toInt() and 0xFF
                fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
                fun i32(at: Int) = u16(at) or (u16(at + 2) shl 16)
                fun tag(at: Int) = (0 until 4).map { b[at + it].toInt().toChar() }.joinToString("")
                if (b.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") throw IllegalArgumentException("Not a .wav file (no RIFF/WAVE header)")
                var chunk = 12
                var fmt = -1
                var data = -1
                var dataSize = -1
                while (chunk + 8 < b.size && (fmt < 0 || data < 0)) {
                    val size = i32(chunk + 4)
                    when (tag(chunk)) {
                        "fmt " -> fmt = chunk + 8
                        "data" -> { data = chunk + 8; dataSize = size }
                    }
                    if (size < 0) break
                    chunk += size + 8
                }
                if (fmt < 0 || data < 0) throw IllegalArgumentException("The .wav file has no format or data chunk")
                if (fmt + 18 > b.size) throw IllegalArgumentException("The .wav format chunk is cut short")
                var format = u16(fmt)
                val channels = u16(fmt + 2)
                val rate = i32(fmt + 4)
                var bits = u16(fmt + 14)
                if (format == 0xFFFE && u16(fmt + 16) == 22 && fmt + 18 + 22 <= b.size) {
                    val guid = (0 until 14).map { u8(fmt + 18 + 8 + it) }
                    if (guid == EXTENSIBLE_GUID_REST) format = u16(fmt + 18 + 6)
                }
                if (format != 1 && format != 3) throw IllegalArgumentException("Only uncompressed PCM or float .wav files can be imported")
                if (channels !in 1..2) throw IllegalArgumentException("The .wav file has $channels channels; only mono and stereo can be imported")
                if (rate <= 0) throw IllegalArgumentException("The .wav file has no sample rate")
                if (dataSize < 0 || data + dataSize > b.size) throw IllegalArgumentException("The .wav data chunk is cut short")
                val values: ShortArray = if (format == 1) {
                    bits = (bits + 7) and 0x07.inv()
                    when (bits) {
                        8 -> ShortArray(dataSize) { i -> ((u8(data + i) shl 8) - 32768 + u8(data + i)).toShort() }
                        16 -> ShortArray(dataSize shr 1) { i -> u16(data + 2 * i).toShort() }
                        else -> {
                            val step = bits shr 3
                            if (step < 2) throw IllegalArgumentException("The .wav file has $bits-bit samples")
                            ShortArray(dataSize / step) { i -> ((u8(data + i * step + step - 1) shl 8) or u8(data + i * step + step - 2)).toShort() }
                        }
                    }
                } else when (bits) {
                    32 -> ShortArray(dataSize shr 2) { i -> toShortAsDotNet((Float.fromBits(i32(data + 4 * i)) * 32767f).toDouble()) }
                    64 -> ShortArray(dataSize shr 3) { i ->
                        val lo = i32(data + 8 * i).toLong() and 0xFFFFFFFFL
                        val hi = i32(data + 8 * i + 4).toLong()
                        toShortAsDotNet(Double.fromBits((hi shl 32) or lo) * 32767)
                    }
                    else -> throw IllegalArgumentException("Only 32- and 64-bit float .wav files can be imported")
                }
                val mono = if (channels == 2) ShortArray(values.size / 2) { i -> ((values[2 * i] + values[2 * i + 1]) / 2).toShort() } else values
                if (channels == 2) {
                    val left = ShortArray(values.size / 2) { i -> values[2 * i] }
                    val right = ShortArray(values.size / 2) { i -> values[2 * i + 1] }
                    return Wav(mono, rate, listOf(left, right))
                }
                return Wav(mono, rate)
            }

            /**
             * FamiStudio scales by 32767 (float input in single precision) and casts to a 16-bit integer the way .NET 8
             * on x64 does: truncate to 32 bits (out of that range: 0x80000000), keep the low 16. So input beyond ±1.0
             * wraps around rather than clipping, exactly as in FamiStudio.
             */
            private fun toShortAsDotNet(v: Double): Short =
                (if (v.isNaN() || v >= 2147483648.0 || v < -2147483648.0) Int.MIN_VALUE else v.toInt()).toShort()

            private val EXTENSIBLE_GUID_REST = listOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71)
        }
    }
}
