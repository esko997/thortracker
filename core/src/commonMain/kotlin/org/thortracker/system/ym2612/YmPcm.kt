package org.thortracker.system.ym2612

import kotlinx.serialization.Serializable
import org.thortracker.backend.YmPlayerImage
import org.thortracker.model.NumberText
import org.thortracker.system.nes.Dpcm
import org.thortracker.system.nes.PcmData
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * One PCM source sample of a Mega Drive song ([YmSound.pcmSamples]): signed 8-bit mono [data] (two's complement,
 * 0 = silence; [PcmData.signed]) recorded at [rate] Hz, the rate it plays at with mapping pitch 0. Sources are never
 * played as they are: each kit mapping compiles them to the XGM2 driver's rate ([org.thortracker.backend.YmPcmLowering]).
 */
@Serializable
data class YmPcmSample(val name: String, val data: PcmData, val rate: Int) {
    fun problem(): String? = when {
        data.size == 0 -> "the sample is empty"
        rate !in YmPcm.MIN_RATE..YmPcm.MAX_RATE -> "rate $rate Hz is outside ${YmPcm.MIN_RATE}-${YmPcm.MAX_RATE} Hz"
        name.length > YmPcm.MAX_NAME -> "the name is longer than ${YmPcm.MAX_NAME} characters"
        else -> null
    }
}

/** What a key of a PCM kit plays: [sample] indexes [YmSound.pcmSamples], [pitch] semitones above (or below) its own rate. */
@Serializable
data class YmPcmMapping(val sample: Int, val pitch: Int = 0)

/**
 * The Mega Drive PCM lanes' authoring limits and WAV import. PCM 1-3 are the SGDK XGM2 driver's three PCM channels,
 * software-mixed on the Z80 into the YM2612's DAC, which replaces FM 6's output while it is on. The driver plays
 * one-shot signed 8-bit samples at its fixed rate (MCLK / [YmPlayerImage.PCM_FULL_DIVIDER], about 13.3 kHz), so every
 * kit mapping is compiled ahead of time at that rate with its pitch and the kit's level baked in.
 * [MEMORY] and [MAX_SAMPLES] bound the song's sources; [YmPlayerImage.PCM_BANK_SIZE] and
 * [YmPlayerImage.PCM_MAX_VARIANTS] bound the compiled bank: Thor Tracker's authoring limits, not cartridge facts.
 */
object YmPcm {
    /** Bytes of source sample data a song holds (all of its PCM samples together). */
    const val MEMORY = 256 * 1024

    /** Source samples per song. */
    const val MAX_SAMPLES = 64

    /** Rates sources keep; a file above [MAX_RATE] is converted to it (disclosed at import). */
    const val MIN_RATE = 1000
    const val MAX_RATE = 48000

    /** Mapping pitch in semitones. */
    val PITCH = -48..24

    /** Kit levels: [LEVEL_MAX] plays the sample as recorded, each step 2 dB quieter, 0 silent. */
    const val LEVEL_MAX = 15

    /** Sample name limit. */
    const val MAX_NAME = 16

    /** The bytes all of a song's PCM sources take. */
    fun stored(sound: YmSound): Int = sound.pcmSamples.sumOf { it.data.size }

    /** The driver's PCM rate in [pal] or NTSC clocks (Hz): 13316.8 / 13195.3. */
    fun fullRate(pal: Boolean): Double =
        (if (pal) YmPlayerImage.MCLK_PAL else YmPlayerImage.MCLK_NTSC).toDouble() / YmPlayerImage.PCM_FULL_DIVIDER

    /** The rate [rate] Hz played [pitch] semitones up (Hz), before compilation to the driver's rate. */
    fun playRate(rate: Int, pitch: Int): Double = rate * 2.0.pow(pitch / 12.0)

    /** "2.3 KB". */
    fun kb(bytes: Int): String = "${NumberText.fixed(bytes / 1024.0, 1)} KB"

    /** "22.05 kHz". */
    fun rateText(rate: Double): String = "${NumberText.fixed(rate / 1000.0, 2)} kHz"

    /** "0.84 s": a source's length at its own rate shifted [pitch] semitones. */
    fun seconds(sample: YmPcmSample, pitch: Int = 0): String = "${NumberText.fixed(sample.data.size / playRate(sample.rate, pitch), 2)} s"

    /** A converted file: the sample, the file's own rate and channel count and what the conversion did. */
    class Imported(val sample: YmPcmSample, val sourceRate: Int, val sourceChannels: Int, val resampled: Boolean) {
        val downmixed: Boolean get() = sourceChannels > 1
    }

    /**
     * A `.wav` file (read by FamiStudio's reader, [Dpcm.Wav]: PCM 8-32 bit or float, mono, or stereo mixed to mono) as
     * a signed 8-bit source: each 16-bit value's high byte (v >> 8, arithmetic), so 0 stays silence and the whole
     * range −128..127 is used. Rates up to [MAX_RATE] are kept; a higher rate is converted to [MAX_RATE] by linear
     * interpolation ([Imported.resampled]) and a stereo file is mixed down ([Imported.downmixed]): the UI says both.
     * Nothing is cut: a file longer than [free] bytes after conversion is refused.
     * @throws IllegalArgumentException with a message for the user.
     */
    fun fromWav(bytes: ByteArray, name: String, free: Int): Imported {
        val wav = Dpcm.Wav.read(bytes)
        require(wav.samples.isNotEmpty()) { "The .wav file has no audio" }
        require(wav.rate >= MIN_RATE) { "The .wav file's rate is ${wav.rate} Hz; Mega Drive PCM samples take $MIN_RATE-$MAX_RATE Hz" }
        val resampled = wav.rate > MAX_RATE
        val rate = if (resampled) MAX_RATE else wav.rate
        val source = wav.samples
        val count = if (resampled) ((source.size.toLong() * MAX_RATE + wav.rate / 2) / wav.rate).toInt().coerceAtLeast(1) else source.size
        require(count <= free) {
            "${name.ifEmpty { "The file" }} needs ${kb(count)} at ${rateText(rate.toDouble())} " +
                "(${NumberText.fixed(count.toDouble() / rate, 2)} s); ${kb(maxOf(0, free))} of the ${kb(MEMORY)} PCM sample " +
                "memory is free. Trim the file, use a lower rate or delete samples"
        }
        val out = ByteArray(count) { i ->
            val v = if (!resampled) source[i].toInt() else {
                val pos = i.toDouble() * wav.rate / MAX_RATE
                val a = pos.toInt().coerceAtMost(source.size - 1)
                val b = (a + 1).coerceAtMost(source.size - 1)
                val f = pos - a
                (source[a] * (1.0 - f) + source[b] * f).roundToLong().toInt().coerceIn(-32768, 32767)
            }
            (v shr 8).toByte()
        }
        return Imported(YmPcmSample(name.take(MAX_NAME), PcmData.of(out), rate), wav.rate, wav.channels, resampled)
    }
}
