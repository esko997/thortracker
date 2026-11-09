package org.thortracker.system.nes

/**
 * Three MMC5 PCM samples synthesized here in integer arithmetic (a parabolic sine, xorshift noise; no recordings, so no
 * third-party licences, and every platform makes the same bytes), used by the PCM demo ([NesMmc5DemoSong.pcmDemo]) and
 * the tests: a pitch-swept kick and a noise hat (one-shots, 22050 Hz) and a looping bass (11025 Hz: a 400-sample attack
 * whose brightness falls, then a 100-sample loop, 110.25 Hz, A-2 at pitch 0) that plays chromatically across keys. Bytes
 * are 1..255 (the MMC5 DAC ignores $00), centred on 128.
 */
object NesPcmKit {
    const val DRUM_RATE = 22050
    const val BASS_RATE = 11025

    /** The bass's loop (one 100-sample cycle at 11025 Hz) and where it starts. */
    const val BASS_CYCLE = 100
    const val BASS_LOOP = 400

    fun samples(): List<PcmSample> = listOf(
        PcmSample("PCM kick", PcmData.of(kick()), DRUM_RATE),
        PcmSample("PCM hat", PcmData.of(hat()), DRUM_RATE),
        PcmSample("PCM bass", PcmData.of(bass()), BASS_RATE, loopStart = BASS_LOOP),
    )

    /** Parabolic sine of a 16-bit phase: -32767..32767. */
    private fun sine(phase: Int): Int {
        val x = (phase and 0xFFFF) - 32768
        val ax = if (x < 0) -x else x
        return (-(x.toLong() * (32768 - ax)) shr 13).toInt().coerceIn(-32767, 32767)
    }

    /** 1..255 around 128 from a -32767..32767 value scaled by [amp] (0..127). */
    private fun byte(v: Int, amp: Int): Byte = (128 + (v.toLong() * amp / 32767L).toInt()).coerceIn(1, 255).toByte()

    private fun kick(): ByteArray {
        val n = DRUM_RATE / 4
        var phase = 0
        return ByteArray(n) { i ->
            val left = n - i
            val env = left * 127 / n // 127 → 0
            val amp = env * env / 127
            val hz = 45 + 115 * left / n // 160 Hz falling to 45 Hz
            phase += (hz.toLong() * 65536 / DRUM_RATE).toInt()
            byte(sine(phase), amp)
        }
    }

    private fun hat(): ByteArray {
        val n = DRUM_RATE * 8 / 100
        var x = 0x2545F491
        return ByteArray(n) { i ->
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            val left = n - i
            val amp = 90 * left * left / (n * n)
            byte(((x shr 16) and 0xFFFF) - 32768, amp)
        }
    }

    /** A cycle mixing a square and a falling saw; [bright] 0..64 shapes the saw's share. */
    private fun bassCycle(k: Int, bright: Int): Int {
        val saw = 32767 - k * 65534 / (BASS_CYCLE - 1)
        val square = if (k < BASS_CYCLE / 2) 24000 else -24000
        return (square * (64 - bright) + saw * bright) / 64
    }

    private fun bass(): ByteArray = ByteArray(BASS_LOOP + BASS_CYCLE) { i ->
        val k = i % BASS_CYCLE
        val bright = if (i < BASS_LOOP) 64 - i * 48 / BASS_LOOP else 16
        byte(bassCycle(k, bright), 110)
    }

    /** Absolute keys of the PCM kit sound: kick C-3, hat F#3; the bass sound's root A-2. */
    const val KICK_KEY = 36
    const val HAT_KEY = 42
    const val BASS_ROOT = 33

    /** A drum sound mapping the kick and hat ([firstSample]: the kit's first sample index in the song). */
    fun drums(firstSample: Int): NesInstrument = NesInstrument(
        name = "PCM drums",
        pcm = mapOf(KICK_KEY to PcmMapping(firstSample), HAT_KEY to PcmMapping(firstSample + 1)),
    )

    /** A bass sound: the looping bass sample on A-2 and chromatically an octave either way (pitch = key - A-2). */
    fun bassSound(firstSample: Int): NesInstrument = NesInstrument(
        name = "PCM bass",
        pcm = (BASS_ROOT - 12..BASS_ROOT + 12).associateWith { PcmMapping(firstSample + 2, pitch = it - BASS_ROOT, loop = true) },
    )
}
