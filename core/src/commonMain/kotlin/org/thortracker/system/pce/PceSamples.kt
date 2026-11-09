package org.thortracker.system.pce

import org.thortracker.backend.PceLowering
import org.thortracker.backend.PcePlayerImage
import org.thortracker.model.Song
import org.thortracker.system.nes.Dpcm

/**
 * DDA sample data: `.wav` import for the HuC6280's 5-bit direct D/A, the DDA rate and the data budget. Common code
 * only (the Deck build is Kotlin/Native); integer arithmetic, so every platform makes the same frames.
 */
object PceSamples {
    /**
     * The DDA rate at timer reload [timer] (0..127) in whole Hz: the CPU timer interrupts every 1024 · (R + 1) cycles
     * of 7159090.9 Hz, so 7159091 / (1024 · (R + 1)) rounded to nearest: 6991 Hz at 0, 3496 at 1, 55 at 127.
     */
    fun rateHz(timer: Int): Int {
        val d = 1024L * (timer.coerceIn(0, PceSound.MAX_TIMER) + 1)
        return ((CPU_HZ_ROUNDED + d / 2) / d).toInt()
    }

    /** Seconds [frames] play for at timer reload [timer] on the root note (one frame per interrupt). */
    fun seconds(frames: Int, timer: Int): Double = frames / PceRules.timerHz(timer)

    /** Bytes of the image's data region still free for [song] (patterns, envelopes, samples); negative when over. */
    fun free(song: Song): Int = PcePlayerImage.DATA_CAPACITY - PceLowering.dataSize(song)

    /**
     * A `.wav` file (PCM 8/16/24/32-bit or float 32/64-bit, mono or stereo, any rate; read by the NES import's reader,
     * stereo mixed to mono) as 5-bit DDA frames at [rateHz]:
     *
     * - Resampling, in integers: the output has ⌊n · rateHz / sourceHz⌋ frames (at least 1) for n source samples. When
     *   the source is faster (down-sampling), frame i is the mean of source samples [⌊i · sourceHz / rateHz⌋,
     *   ⌊(i + 1) · sourceHz / rateHz⌋) (at least one): a box filter that keeps most aliasing out. When it is not, frame
     *   i interpolates linearly at position i · sourceHz / rateHz in 16.16 fixed point: s[k] + (s[k + 1] − s[k]) · frac
     *   / 65536, rounding toward negative infinity (the last sample repeats past the end).
     * - Levels: each 16-bit value v becomes (v + 32768 + 1024) >> 11, clamped to 0..31 (nearest of 32 steps; 0 → 16).
     * - Cut at [PceSample.MAX_FRAMES] frames.
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
        val count = maxOf(1L, source.size * rate / sourceHz).coerceAtMost(PceSample.MAX_FRAMES.toLong()).toInt()
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

    /** A 16-bit value as a 5-bit D/A frame: (v + 32768 + 1024) >> 11, clamped to 0..31. */
    fun level(v: Int): Int = ((v.coerceIn(-32768, 32767) + 32768 + 1024) shr 11).coerceIn(0, PceSound.WAVE_MAX)

    /** 78750000 / 11 Hz rounded: the CPU clock the timer counts. */
    private const val CPU_HZ_ROUNDED = 7_159_091L
}
