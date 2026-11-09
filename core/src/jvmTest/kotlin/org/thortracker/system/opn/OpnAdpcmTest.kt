package org.thortracker.system.opn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Yamaha ADPCM-B as ymfm decodes it (`adpcm_b_channel::clock`), the encoder that models that decoder, the `.wav`
 * import and the generated starter samples.
 */
class OpnAdpcmTest {
    /**
     * Hand-computed with ymfm's arithmetic: delta = (2 × (code & 7) + 1) × step / 8 (integer division), minus for bit 3,
     * then step = clamp(step × {57,57,57,57,77,102,128,153}[code & 7] / 64, 127, 24576), from accumulator 0, step 127,
     * high nibble first.
     */
    @Test
    fun decoderMatchesYmfmOnHandComputedCodes() {
        // 0: +15 (step stays 127: 127 × 57 / 64 = 113 → 127); 8: −15; 7: +238 (step 303); F: −568 (step 724);
        // 4: +814 (step 871); C: −979 (step 1047).
        val decoded = OpnAdpcm.decode(AdpcmBytes(byteArrayOf(0x00, 0x08, 0x7F, 0x4C)))
        assertArrayEquals(shortArrayOf(15, 30, 45, 30, 268, -300, 514, -465), decoded)
    }

    @Test
    fun decoderClampsTheAccumulatorAndTheStep() {
        val up = OpnAdpcm.decode(AdpcmBytes(ByteArray(64) { 0x77 }))
        assertEquals(32767, up.max().toInt())
        assertEquals(32767, up.last().toInt())
        val down = OpnAdpcm.decode(AdpcmBytes(ByteArray(64) { 0xFF.toByte() }))
        assertEquals(-32768, down.last().toInt())
        // At the step ceiling 24576 the largest code adds 15 × 24576 / 8 = 46080: from −32768 two of them reach the ceiling.
        val swing = OpnAdpcm.decode(AdpcmBytes(ByteArray(32) { 0xFF.toByte() } + byteArrayOf(0x77)))
        assertEquals(32767, swing.last().toInt())
    }

    private fun sine(n: Int, hz: Double, rate: Int, amplitude: Double) =
        ShortArray(n) { (amplitude * sin(2 * PI * hz * it / rate)).roundToInt().toShort() }

    private fun snr(original: ShortArray, decoded: ShortArray): Double {
        var signal = 0.0
        var noise = 0.0
        for (i in original.indices) {
            signal += original[i].toDouble() * original[i]
            val e = original[i] - decoded[i].toDouble()
            noise += e * e
        }
        return 10 * log10(signal / noise)
    }

    /** Encoding then decoding keeps a tone recognisable: the encoder tracks the decoder's own state, so errors do not pile up. */
    @Test
    fun encodeDecodeRoundTripKeepsTheSignal() {
        for ((hz, amplitude) in listOf(440.0 to 20000.0, 110.0 to 30000.0, 1000.0 to 8000.0, 440.0 to 300.0)) {
            val pcm = sine(4000, hz, 16000, amplitude)
            val decoded = OpnAdpcm.decode(AdpcmBytes(OpnAdpcm.encode(pcm)))
            val ratio = snr(pcm, decoded.copyOf(pcm.size))
            assertTrue("$hz Hz at $amplitude: SNR $ratio dB", ratio > 20.0)
        }
    }

    /** The encoder models the decoder exactly: re-encoding the decoder's output reproduces the same codes. */
    @Test
    fun encoderIsTheDecodersExactInverse() {
        val codes = OpnAdpcm.encode(sine(1280, 330.0, 16000, 15000.0))
        val decoded = OpnAdpcm.decode(AdpcmBytes(codes))
        assertArrayEquals(codes, OpnAdpcm.encode(decoded))
        // Silence stays silence-like: it alternates the smallest steps around 0.
        val quiet = OpnAdpcm.decode(AdpcmBytes(OpnAdpcm.encode(ShortArray(256))))
        assertTrue(quiet.all { it in -15..15 })
    }

    /** Data is padded with code 0 to whole 32-byte units (64 codes), the chip's start/end address granularity. */
    @Test
    fun encodingPadsToThirtyTwoBytes() {
        assertEquals(0, OpnAdpcm.encode(ShortArray(0)).size)
        assertEquals(32, OpnAdpcm.encode(ShortArray(1)).size)
        assertEquals(32, OpnAdpcm.encode(ShortArray(64)).size)
        assertEquals(64, OpnAdpcm.encode(ShortArray(65)).size)
        val one = OpnAdpcm.encode(shortArrayOf(5000))
        assertTrue(one.drop(1).all { it == 0.toByte() })
        assertEquals(0, one[0].toInt() and 0x0F)
    }

    // ---- .wav import ---------------------------------------------------------------------------------------------

    /** A RIFF/WAVE file of 16-bit [channels]-channel [pcm] (interleaved) at [rate] Hz. */
    private fun wav(pcm: ShortArray, rate: Int, channels: Int = 1): ByteArray {
        val data = pcm.size * 2
        val out = ByteArray(44 + data)
        fun u16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte() }
        fun u32(at: Int, v: Int) { u16(at, v and 0xFFFF); u16(at + 2, v ushr 16) }
        fun tag(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        tag(0, "RIFF"); u32(4, 36 + data); tag(8, "WAVE")
        tag(12, "fmt "); u32(16, 16); u16(20, 1); u16(22, channels); u32(24, rate); u32(28, rate * channels * 2)
        u16(32, channels * 2); u16(34, 16)
        tag(36, "data"); u32(40, data)
        pcm.forEachIndexed { i, v -> u16(44 + 2 * i, v.toInt()) }
        return out
    }

    @Test
    fun wavImportKeepsOrConvertsTheRate() {
        val tone = sine(8000, 440.0, 8000, 12000.0)
        val kept = OpnAdpcm.importWav("A very long sample name", wav(tone, 8000))
        assertEquals(8000, kept.rate)
        assertEquals("A very long samp", kept.name)
        assertEquals(8000 / 2, kept.data.size)
        assertNull(OpnValidator.sampleProblem(kept))
        assertTrue(snr(tone, OpnAdpcm.decode(kept.data).copyOf(tone.size)) > 20.0)
        // Converted on import: twice the rate, twice the codes.
        val doubled = OpnAdpcm.importWav("Up", wav(tone, 8000), rate = 16000)
        assertEquals(16000, doubled.rate)
        assertEquals(16000 / 2, doubled.data.size)
        // Files below or above the chip's rates are clamped into them.
        assertEquals(1000, OpnAdpcm.importWav("Low", wav(ShortArray(500) { 1000 }, 500)).rate)
        assertEquals(55466, OpnAdpcm.importWav("High", wav(ShortArray(960) { 1000 }, 96000)).rate)
        // Stereo is mixed down to mono.
        val stereo = ShortArray(256) { if (it % 2 == 0) 8000 else 0 }
        val mixed = OpnAdpcm.decode(OpnAdpcm.importWav("St", wav(stereo, 8000, channels = 2)).data)
        assertTrue(mixed.take(128).drop(16).all { it in 3000..5000 })
    }

    @Test
    fun wavImportExplainsWhatItRefuses() {
        assertTrue(assertThrows(IllegalArgumentException::class.java) { OpnAdpcm.importWav("x", ByteArray(40)) }.message!!.contains("Not a .wav"))
        // An empty data chunk (a byte follows it, so the reader reaches the chunk).
        val empty = wav(ShortArray(0), 8000) + byteArrayOf(0)
        assertTrue(assertThrows(IllegalArgumentException::class.java) { OpnAdpcm.importWav("x", empty) }.message!!.contains("no audio"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { OpnAdpcm.importWav("x", wav(ShortArray(10), 8000), rate = 999) }.message!!.contains("1000-55466"))
        // 30 seconds at 22050 Hz need 330 KB; the chip holds 256 KiB.
        val long = wav(ShortArray(22050 * 30), 22050)
        val e = assertThrows(IllegalArgumentException::class.java) { OpnAdpcm.importWav("x", long) }
        assertTrue(e.message, e.message!!.contains("256 KiB") && e.message!!.contains("lower rate"))
        // The same file at 8000 Hz fits.
        assertEquals(8000, OpnAdpcm.importWav("x", long, rate = 8000).rate)
    }

    @Test
    fun resamplingInterpolatesUpAndAveragesDown() {
        val ramp = ShortArray(8) { (it * 100).toShort() }
        assertArrayEquals(ramp, OpnAdpcm.resample(ramp, 8000, 8000))
        assertArrayEquals(shortArrayOf(0, 50, 100, 150, 200, 250, 300, 350, 400, 450, 500, 550, 600, 650, 700, 700),
            OpnAdpcm.resample(ramp, 8000, 16000))
        assertArrayEquals(shortArrayOf(50, 250, 450, 650), OpnAdpcm.resample(ramp, 16000, 8000))
        assertEquals(3, OpnAdpcm.resample(ShortArray(4), 4, 3).size)
    }

    // ---- the starter kit -----------------------------------------------------------------------------------------

    @Test
    fun starterSamplesAreDeterministicAndPlayable() {
        for (kind in OpnSampleKit.KINDS) {
            val a = OpnSampleKit.generate(kind)
            assertEquals(a, OpnSampleKit.generate(kind))
            assertEquals(kind, a.name)
            assertEquals(OpnSampleKit.RATE, a.rate)
            assertNull(kind, OpnValidator.sampleProblem(a))
            assertEquals(0, a.data.size % 32)
            // Audible: the decoded sample reaches a real level.
            assertTrue(kind, OpnAdpcm.decode(a.data).maxOf { kotlin.math.abs(it.toInt()) } > 4000)
        }
        assertEquals(22050, OpnSampleKit.generate("Bell", 22050).rate)
        assertThrows(IllegalArgumentException::class.java) { OpnSampleKit.generate("Cowbell") }
        assertThrows(IllegalArgumentException::class.java) { OpnSampleKit.generate("Kick", 500) }
        // The whole kit is small next to the 256 KiB of sample memory.
        assertTrue(OpnSampleKit.KINDS.sumOf { OpnSampleKit.generate(it).stored } < 64 * 1024)
    }
}
