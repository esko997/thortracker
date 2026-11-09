package org.thortracker.system.cbm

import org.thortracker.backend.CbmImage
import org.thortracker.backend.CbmLowering
import org.thortracker.model.Song
import org.thortracker.system.nes.Dpcm

/**
 * TED DAC sample data: `.wav` import for the 9-level D/A (the volume register in D/A mode), the DAC rate and the data
 * budget. Common code only (the Deck build is Kotlin/Native); integer arithmetic, so every platform makes the same frames.
 */
object CbmSamples {
    /**
     * The DAC rate at timer [timer] on [video] in whole Hz: the TED single clock (17734475 / 20 Hz PAL, 315000000 / 352
     * Hz NTSC) over 64 + 4 T clocks, rounded to nearest: 7917 Hz PAL at T = 12, 13855 Hz at T = 0.
     */
    fun rateHz(video: CbmVideo, timer: Int): Int {
        val cycles = CbmRules.timerCycles(timer).toLong()
        val (num, den) = if (video == CbmVideo.PAL) 17_734_475L to 20L else 315_000_000L to 352L
        val d = den * cycles
        return ((num + d / 2) / d).toInt()
    }

    /** Seconds [frames] play for at [song]'s DAC rate on the root note (one frame per timer interrupt). */
    fun seconds(song: Song, frames: Int): Double = frames / CbmRules.dacHz(song.cbm.video, song.cbm.dacTimer)

    /** Bytes of the image's data region still free for [song] (patterns, envelopes, samples); negative when over. */
    fun free(song: Song): Int = CbmImage.DATA_CAPACITY - CbmLowering.dataSize(song)

    /**
     * A `.wav` file (PCM 8/16/24/32-bit or float, mono or stereo, any rate; read by the NES import's reader, stereo
     * mixed to mono) as DAC frames 0..8 at [rateHz]:
     *
     * - Resampling, in integers: ⌊n · rateHz / sourceHz⌋ frames (at least 1) for n source samples. Down-sampling: frame
     *   i is the mean of source samples [⌊i · sourceHz / rateHz⌋, ⌊(i + 1) · sourceHz / rateHz⌋) (at least one), a box
     *   filter. Otherwise frame i interpolates linearly at i · sourceHz / rateHz in 16.16 fixed point (rounding toward
     *   negative infinity; the last sample repeats past the end).
     * - Levels: [level].
     * - Cut at [CbmSample.MAX_FRAMES] frames.
     *
     * @throws IllegalArgumentException with a message for the user when the file cannot be read or holds no audio.
     */
    fun fromWav(bytes: ByteArray, rateHz: Int): List<Int> {
        require(rateHz > 0) { "Sample rate $rateHz Hz is not positive" }
        val wav = Dpcm.Wav.read(bytes)
        val source = wav.samples
        require(source.isNotEmpty()) { "The .wav file has no audio" }
        val sourceHz = wav.rate.toLong()
        val rate = rateHz.toLong()
        val count = maxOf(1L, source.size * rate / sourceHz).coerceAtMost(CbmSample.MAX_FRAMES.toLong()).toInt()
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

    /** A 16-bit value as a DAC frame: ((v + 32768) · 8 + 32768) >> 16, the nearest of the 9 levels (0 → 4, the centre). */
    fun level(v: Int): Int = (((v.coerceIn(-32768, 32767) + 32768) * 8 + 32768) shr 16).coerceIn(0, CbmImage.DAC_MAX)
}
