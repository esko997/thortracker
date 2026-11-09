@file:OptIn(ExperimentalEncodingApi::class)

package org.thortracker.system.nes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Unsigned 8-bit PCM bytes (immutable, compared by content). Stored in project files as one base64 string, since a
 * sample may be hundreds of kilobytes.
 */
@Serializable(with = PcmData.Serializer::class)
class PcmData private constructor(private val bytes: ByteArray) {
    val size: Int get() = bytes.size

    /** Sample [i] as 0..255. */
    operator fun get(i: Int): Int = bytes[i].toInt() and 0xFF

    fun toByteArray(): ByteArray = bytes.copyOf()

    internal fun copyInto(out: ByteArray, at: Int) {
        bytes.copyInto(out, at)
    }

    private val hash by lazy { bytes.contentHashCode() }
    override fun equals(other: Any?): Boolean = other is PcmData && other.size == size && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = hash
    override fun toString(): String = "PcmData($size bytes)"

    companion object {
        fun of(bytes: ByteArray): PcmData = PcmData(bytes.copyOf())
        fun of(values: List<Int>): PcmData = PcmData(ByteArray(values.size) { values[it].toByte() })
    }

    object Serializer : KSerializer<PcmData> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("org.thortracker.PcmData", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: PcmData) = encoder.encodeString(Base64.encode(value.bytes))
        override fun deserialize(decoder: Decoder): PcmData = try {
            PcmData(Base64.decode(decoder.decodeString()))
        } catch (e: IllegalArgumentException) {
            throw SerializationException("PCM sample data is not base64: ${e.message}")
        }
    }
}

/**
 * One sample of the MMC5 PCM lane ([NesSound.pcmSamples]): unsigned 8-bit mono [data] (1..255 each: the MMC5's DAC
 * ignores $00), recorded at [rate] Hz (the rate it plays at with mapping pitch 0), and where a looping mapping goes
 * back to after the last byte ([loopStart], 0 = the whole sample).
 */
@Serializable
data class PcmSample(val name: String, val data: PcmData, val rate: Int, val loopStart: Int = 0)

/**
 * What a note plays on the MMC5 PCM lane: [sample] indexes [NesSound.pcmSamples], played [pitch] semitones above (or
 * below) its own rate, [loop] from its loop start until the next note, OFF, cut or stop.
 */
@Serializable
data class PcmMapping(val sample: Int, val pitch: Int = 0, val loop: Boolean = false)

/**
 * The MMC5 PCM lane's limits and its WAV import. The lane is Thor Tracker's own software player writing the MMC5's
 * 8-bit DAC on the emulated CPU's timebase (app/src/main/cpp/nes/nes_mmc5_pcm.h): the chip has no sample clock or
 * sample memory, so the rates, pitches and memory here are that player's, not register facts, and the exports that
 * run FamiStudio's engine (.nsf, FamiStudio text) cannot carry the lane.
 */
object Pcm {
    /** Bytes of sample data the player holds (all of a song's PCM samples together). */
    const val MEMORY = 256 * 1024

    /** Samples per song. */
    const val MAX_SAMPLES = 64

    /** Mappings per song: every key of every slot (the player's table holds them all). */
    const val MAX_MAPPINGS = 64 * 96

    /** Rates samples keep; a file above [MAX_RATE] is converted to it (disclosed at import). */
    const val MIN_RATE = 1000
    const val MAX_RATE = 48000

    /** Mapping pitch in semitones. */
    val PITCH = -48..24

    /** The DAC's centre: silence. Sample bytes are 1..255 ($00 would hold the DAC and trip the MMC5's IRQ). */
    const val CENTRE = 128
    const val LOWEST = 1

    /** Sample name limit (as DPCM samples). */
    const val MAX_NAME = 16

    /** The NES CPU clock (NTSC). */
    const val CPU_HZ = 1789773.0

    /** The player's period range: 16.16 CPU cycles per sample (nes_mmc5_pcm.h NES_PCM_PERIOD_MIN/MAX). */
    const val PERIOD_MIN = 8L shl 16
    const val PERIOD_MAX = 32768L shl 16

    /** The playback rate of [rate] Hz played [pitch] semitones up (Hz). */
    fun playRate(rate: Int, pitch: Int): Double = rate * 2.0.pow(pitch / 12.0)

    /**
     * The player's period for [rate] played [pitch] semitones up: CPU cycles per sample step in 16.16 fixed point,
     * rounded. Every rate [MIN_RATE]..[MAX_RATE] at every [PITCH] gives 9.3..28636 cycles, inside [PERIOD_MIN]..[PERIOD_MAX].
     * @throws IllegalArgumentException outside that range (it cannot happen for a valid song).
     */
    fun period(rate: Int, pitch: Int): Long {
        val p = (CPU_HZ * 65536.0 / playRate(rate, pitch)).roundToLong()
        require(p in PERIOD_MIN..PERIOD_MAX) { "PCM rate $rate Hz at pitch $pitch is outside the player's 54.6 Hz-223.7 kHz" }
        return p
    }

    /** The bytes all of a song's PCM samples take. */
    fun stored(sound: NesSound): Int = sound.pcmSamples.sumOf { it.data.size }

    /** "2.3 KB". */
    fun kb(bytes: Int): String = "${org.thortracker.model.NumberText.fixed(bytes / 1024.0, 1)} KB"

    /** "22.05 kHz". */
    fun rateText(rate: Double): String = "${org.thortracker.model.NumberText.fixed(rate / 1000.0, 2)} kHz"

    /** A converted file: the sample, the file's own rate and what the conversion did. */
    class Imported(val sample: PcmSample, val sourceRate: Int, val resampled: Boolean, val raised: Int)

    /**
     * A `.wav` file (read by FamiStudio's reader, [Dpcm.Wav]: PCM 8-32 bit or float, mono or stereo mixed to mono) as an
     * unsigned 8-bit sample: each 16-bit value's high byte with the sign flipped ((v + 32768) >> 8), so 0 is the centre
     * 128; values that would be 0 (the bottom 1/256 of the range) become 1, counted in [Imported.raised], because the
     * MMC5's DAC holds its value on $00. Rates up to [MAX_RATE] are kept; a higher rate is converted to [MAX_RATE] by
     * linear interpolation ([Imported.resampled]: the UI says so). Nothing is cut: a file longer than [free] bytes after
     * conversion is refused.
     * @throws IllegalArgumentException with a message for the user.
     */
    fun fromWav(bytes: ByteArray, name: String, free: Int): Imported {
        val wav = Dpcm.Wav.read(bytes)
        require(wav.samples.isNotEmpty()) { "The .wav file has no audio" }
        require(wav.rate >= MIN_RATE) { "The .wav file's rate is ${wav.rate} Hz; the MMC5 PCM player takes $MIN_RATE-$MAX_RATE Hz" }
        val resampled = wav.rate > MAX_RATE
        val rate = if (resampled) MAX_RATE else wav.rate
        val source = wav.samples
        val count = if (resampled) ((source.size.toLong() * MAX_RATE + wav.rate / 2) / wav.rate).toInt().coerceAtLeast(1) else source.size
        require(count <= free) {
            "${name.ifEmpty { "The file" }} needs ${kb(count)} at ${rateText(rate.toDouble())} " +
                "(${org.thortracker.model.NumberText.fixed(count.toDouble() / rate, 2)} s); ${kb(maxOf(0, free))} of the " +
                "${kb(MEMORY)} PCM memory is free. Trim the file, use a lower rate or delete samples"
        }
        var raised = 0
        val out = ByteArray(count) { i ->
            val v = if (!resampled) source[i].toInt() else {
                val pos = i.toDouble() * wav.rate / MAX_RATE
                val a = pos.toInt().coerceAtMost(source.size - 1)
                val b = (a + 1).coerceAtMost(source.size - 1)
                val f = pos - a
                (source[a] * (1.0 - f) + source[b] * f).roundToLong().toInt()
            }
            var u = (v + 32768) shr 8
            if (u < LOWEST) { u = LOWEST; raised++ }
            u.toByte()
        }
        return Imported(PcmSample(name.take(MAX_NAME), PcmData.of(out), rate), wav.rate, resampled, raised)
    }

    /** Samples a level of [value] 0..255 shows on a 0..1 scale (for waveforms). */
    fun level(value: Int): Float = value / 255f
}
