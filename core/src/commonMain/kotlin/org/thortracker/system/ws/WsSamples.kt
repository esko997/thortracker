package org.thortracker.system.ws

import org.thortracker.backend.WsLowering
import org.thortracker.backend.WsPlayerImage
import org.thortracker.model.Song
import org.thortracker.system.nes.Dpcm

/**
 * WonderSwan sample data: `.wav` import as 8-bit unsigned bytes, the rates samples play at and the data budget. Common
 * code only (the Deck build is Kotlin/Native); integer arithmetic, so every platform makes the same bytes.
 */
object WsSamples {
    /** A new sample's nominal rate: the starter kit's, 6000 Hz. */
    const val DEFAULT_RATE_HZ = WsStarterKit.RATE_HZ

    /** The voice timer's rate at reload [timer] (1..255) in whole Hz, rounded to nearest: 12000 at 1, 6000 at 2, 47 at 255. */
    fun timerRateHz(timer: Int): Int {
        val t = timer.coerceIn(1, WsPlayerImage.MAX_TIMER)
        return (12000 + t / 2) / t
    }

    /** Sound DMA's rate code [rate] (0..3) in bytes per second. */
    fun dmaRateHz(rate: Int): Int = WsRules.DMA_RATES[rate.coerceIn(0, WsPlayerImage.MAX_DMA_RATE)]

    /**
     * The rate [ins] plays its sample at on its root note: Sound DMA's rate, or the song's voice timer ([timer]) for a
     * CPU-fed sound.
     */
    fun playRateHz(ins: WsInstrument, timer: Int): Int = if (ins.isDma) dmaRateHz(ins.rate) else timerRateHz(timer)

    /** Seconds [sample] lasts when played at [rateHz] frames per second (a stereo DMA sample: bytes per second). */
    fun seconds(sample: WsSample, rateHz: Int, dma: Boolean): Double =
        (if (dma) sample.data.size else sample.frames).toDouble() / maxOf(1, rateHz)

    /** Bytes of the image's data region still free for [song] (patterns, envelopes, samples, DMA copies); negative when over. */
    fun free(song: Song): Int = WsPlayerImage.DATA_CAPACITY - WsLowering.dataSize(song)

    /**
     * A `.wav` file (PCM 8/16/24/32-bit or float 32/64-bit, mono or stereo, any rate; read by the NES import's reader)
     * as an unsigned 8-bit sample named [name] at [rateHz]; a stereo file stays stereo when [stereo] (the WonderSwan
     * Color, for Hyper Voice), else its channels are mixed:
     *
     * - Resampling, in integers, per channel: the output has ⌊n · rateHz / sourceHz⌋ frames (at least 1) for n source
     *   samples. When the source is faster (down-sampling), frame i is the mean of source samples [⌊i · sourceHz /
     *   rateHz⌋, ⌊(i + 1) · sourceHz / rateHz⌋) (at least one, rounding toward negative infinity): a box filter that keeps
     *   most aliasing out. When it is not, frame i interpolates linearly at position i · sourceHz / rateHz in 16.16 fixed
     *   point: s[k] + (s[k + 1] − s[k]) · frac / 65536, rounding toward negative infinity (the last sample repeats).
     * - Levels: each 16-bit value v becomes (v + 32768 + 128) >> 8, clamped to 0..255 (nearest of 256 steps; 0 → 128).
     * - Cut at [WsSample.MAX_FRAMES] frames.
     *
     * @throws IllegalArgumentException with a message for the user when the file cannot be read or holds no audio.
     */
    fun fromWav(bytes: ByteArray, name: String, rateHz: Int, stereo: Boolean): WsSample {
        require(rateHz > 0) { "Sample rate $rateHz Hz is not positive" }
        val wav = Dpcm.Wav.read(bytes, preserveChannels = stereo)
        require(wav.samples.isNotEmpty()) { "The .wav file has no audio" }
        val keep = stereo && wav.channels == 2
        val sources = if (keep) wav.channelSamples else listOf(wav.samples)
        val converted = sources.map { resample(it, wav.rate.toLong(), rateHz.toLong()) }
        val data = if (keep) List(converted[0].size * 2) { converted[it % 2][it / 2] } else converted[0]
        return WsSample(name.take(WsSample.MAX_NAME), data, keep, rateHz)
    }

    private fun resample(source: ShortArray, sourceHz: Long, rate: Long): List<Int> {
        val count = maxOf(1L, source.size * rate / sourceHz).coerceAtMost(WsSample.MAX_FRAMES.toLong()).toInt()
        val last = source.size - 1
        return List(count) { i ->
            val v = if (sourceHz > rate) {
                val start = (i * sourceHz / rate).toInt().coerceAtMost(last)
                val end = ((i + 1) * sourceHz / rate).toInt().coerceIn(start + 1, source.size)
                var sum = 0L
                for (k in start until end) sum += source[k]
                sum.floorDiv((end - start).toLong()).toInt()
            } else {
                val pos = i * sourceHz * 65536 / rate
                val k = (pos shr 16).toInt().coerceAtMost(last)
                val frac = pos and 0xFFFF
                val a = source[k].toInt()
                val b = source[minOf(k + 1, last)].toInt()
                a + ((b - a) * frac shr 16).toInt()
            }
            level(v)
        }
    }

    /** A 16-bit value as an unsigned 8-bit byte: (v + 32768 + 128) >> 8, clamped to 0..255. */
    fun level(v: Int): Int = ((v.coerceIn(-32768, 32767) + 32768 + 128) shr 8).coerceIn(0, 255)
}
