package org.thortracker.system.ym2612

import org.thortracker.system.nes.PcmData

/**
 * Four Mega Drive PCM samples synthesized here in integer arithmetic (a parabolic sine and xorshift noise; no
 * recordings, so no third-party licences, and every platform makes the same bytes), used by the XGM2 demo
 * ([YmDemoSong.xgm2Demo]), the shortest PCM fixture and the tests: a pitch-swept kick, a tone-and-noise snare and a
 * closed hat (22050 Hz) and a one-shot bass (11025 Hz: a 100-sample cycle, 110.25 Hz, A-2 at pitch 0, whose brightness
 * and level fall over 0.6 s) that plays chromatically across keys. Bytes are signed, 0 = silence.
 */
object YmPcmKit {
    const val DRUM_RATE = 22050
    const val BASS_RATE = 11025

    /** The bass's cycle (110.25 Hz at 11025 Hz) and its length. */
    const val BASS_CYCLE = 100
    const val BASS_LENGTH = BASS_RATE * 6 / 10

    /** Absolute keys: kick C-3, snare D-3, hat F#3; the bass kit's root A-2 (pitch 0), mapped an octave either way. */
    const val KICK_KEY = 36
    const val SNARE_KEY = 38
    const val HAT_KEY = 42
    const val BASS_ROOT = 33

    /** The samples in song order: kick, snare, hat, bass. */
    fun samples(): List<YmPcmSample> = listOf(
        YmPcmSample("PCM kick", PcmData.of(kick()), DRUM_RATE),
        YmPcmSample("PCM snare", PcmData.of(snare()), DRUM_RATE),
        YmPcmSample("PCM hat", PcmData.of(hat()), DRUM_RATE),
        YmPcmSample("PCM bass", PcmData.of(bass()), BASS_RATE),
    )

    /** A drum kit mapping the kick, snare and hat at [level] ([firstSample]: the kit's first sample index in the song). */
    fun drums(firstSample: Int, level: Int = YmPcm.LEVEL_MAX, name: String = "PCM drums"): YmPcmInstrument = YmPcmInstrument(
        name = name,
        level = level,
        pcm = mapOf(KICK_KEY to YmPcmMapping(firstSample), SNARE_KEY to YmPcmMapping(firstSample + 1), HAT_KEY to YmPcmMapping(firstSample + 2)),
    )

    /** The bass on A-2 and chromatically an octave either way (pitch = key − A-2). */
    fun bassKit(firstSample: Int): YmPcmInstrument = YmPcmInstrument(
        name = "PCM bass",
        level = 13,
        pcm = (BASS_ROOT - 12..BASS_ROOT + 12).associateWith { YmPcmMapping(firstSample + 3, pitch = it - BASS_ROOT) },
    )

    /** Parabolic sine of a 16-bit phase: -32767..32767. */
    private fun sine(phase: Int): Int {
        val x = (phase and 0xFFFF) - 32768
        val ax = if (x < 0) -x else x
        return (-(x.toLong() * (32768 - ax)) shr 13).toInt().coerceIn(-32767, 32767)
    }

    /** −127..127 from a -32767..32767 value scaled by [amp] (0..127). */
    private fun byte(v: Int, amp: Int): Byte = (v.toLong() * amp / 32767L).toInt().coerceIn(-127, 127).toByte()

    private class Noise(private var x: Int) {
        /** Next value -32768..32767. */
        fun next(): Int {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            return ((x shr 16) and 0xFFFF) - 32768
        }
    }

    private fun kick(): ByteArray {
        val n = DRUM_RATE / 4
        var phase = 0
        return ByteArray(n) { i ->
            val left = n - i
            val env = left * 127 / n
            val amp = env * env / 127
            val hz = 45 + 115 * left / n // 160 Hz falling to 45 Hz
            phase += (hz.toLong() * 65536 / DRUM_RATE).toInt()
            byte(sine(phase), amp)
        }
    }

    private fun snare(): ByteArray {
        val n = DRUM_RATE * 18 / 100
        val noise = Noise(0x1F2E3D4C)
        var phase = 0
        return ByteArray(n) { i ->
            val left = n - i
            val tone = 70 * left * left / (n * n) // the body fades fast
            val hiss = 100 * left / n
            phase += (185L * 65536 / DRUM_RATE).toInt()
            val v = (sine(phase).toLong() * tone + noise.next().toLong() * hiss) / 127
            byte(v.toInt().coerceIn(-32767, 32767), 120)
        }
    }

    private fun hat(): ByteArray {
        val n = DRUM_RATE * 6 / 100
        val noise = Noise(0x2545F491)
        var last = 0
        return ByteArray(n) { i ->
            val left = n - i
            val amp = 90 * left * left / (n * n)
            // A first difference tilts the noise towards the treble.
            val x = noise.next()
            val v = (x - last) / 2
            last = x
            byte(v, amp)
        }
    }

    /** A cycle mixing a square and a falling saw; [bright] 0..64 shapes the saw's share. */
    private fun bassCycle(k: Int, bright: Int): Int {
        val saw = 32767 - k * 65534 / (BASS_CYCLE - 1)
        val square = if (k < BASS_CYCLE / 2) 24000 else -24000
        return (square * (64 - bright) + saw * bright) / 64
    }

    private fun bass(): ByteArray = ByteArray(BASS_LENGTH) { i ->
        val k = i % BASS_CYCLE
        val bright = 64 - i * 48 / BASS_LENGTH
        val amp = 110 - i * 70 / BASS_LENGTH
        byte(bassCycle(k, bright), amp)
    }
}
