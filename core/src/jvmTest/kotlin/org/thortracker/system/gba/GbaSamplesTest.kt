package org.thortracker.system.gba

import org.junit.Assert.*
import org.junit.Test

class GbaSamplesTest {
    @Test fun wavImportRetainsSignedEdgesAndSourcePitch() {
        val sample = GbaSamples.fromWav(wav(byteArrayOf(0, 127, 128.toByte(), 255.toByte()), bits = 8), "Edges", 37)
        assertEquals(listOf(-128, -1, 0, 127), sample.pcm)
        assertEquals(8363, sample.sampleRate)
        assertEquals(37, sample.rootNote)
        assertEquals("Edges", sample.name)
        val stereo = GbaSamples.fromWav(wav(byteArrayOf(0, 128.toByte(), 0, 128.toByte(), 0, 0, 0, 0), channels = 2))
        assertEquals(listOf(-128, 0), stereo.pcm)
        val float = GbaSamples.fromWav(wav(byteArrayOf(0, 0, 128.toByte(), 63, 0, 0, 128.toByte(), 191.toByte()), bits = 32, encoding = 3))
        assertEquals(listOf(127, -128), float.pcm)
    }

    @Test fun malformedAndOversizedWavDoesNotSilentlyTruncate() {
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.fromWav(ByteArray(11)) }
        val good = wav(byteArrayOf(0, 0))
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.fromWav(good.copyOf(good.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.fromWav(wav(byteArrayOf(0, 0), rate = 96000)) }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.fromWav(wav(ByteArray(6), channels = 3)) }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.fromWav(wav(byteArrayOf(0, 0), encoding = 2)) }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.fromWav(wav(byteArrayOf(0, 0, 192.toByte(), 127), bits = 32, encoding = 3)) }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.fromWav(wav(ByteArray(GbaSound.MAX_PCM_BYTES + 1), bits = 8)) }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.fromWav(wav(byteArrayOf(0), bits = 16)) }
        val hugeChunk = good.copyOf().also { for (i in 40..43) it[i] = 255.toByte() }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.fromWav(hugeChunk) }
    }

    @Test fun trimAndResampleKeepExclusiveLoopsInsidePcm() {
        val source = GbaSample(pcm = listOf(0, 10, 20, 30, 40, 50, 60, 70), sampleRate = 8000, loopStart = 2, loopEnd = 7)
        val trimmed = GbaSamples.trim(source, 3, 8)
        assertEquals(listOf(30, 40, 50, 60, 70), trimmed.pcm)
        assertEquals(0, trimmed.loopStart)
        assertEquals(4, trimmed.loopEnd)
        val outside = GbaSamples.trim(source, 0, 2)
        assertEquals(0, outside.loopStart)
        assertEquals(0, outside.loopEnd)
        val doubled = GbaSamples.resample(source, 16000)
        assertEquals(16, doubled.pcm.size)
        assertEquals(5, doubled.pcm[1])
        assertEquals(4, doubled.loopStart)
        assertEquals(14, doubled.loopEnd)
        assertEquals(source.rootNote, doubled.rootNote)
        assertEquals(source.fineTune, doubled.fineTune)
        assertEquals(16000, doubled.sampleRate)
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.trim(source, 3, 3) }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.resample(source.copy(pcm = List(12) { 0 }, sampleRate = 1), 48000) }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.resample(source, 0) }
    }

    @Test fun gainNormaliseAndFadesAreBoundedNonDestructive() {
        val source = GbaSample(pcm = listOf(-64, -32, 0, 32, 64))
        assertEquals(listOf(-127, -63, 0, 64, 127), GbaSamples.normalise(source).pcm)
        assertEquals(listOf(-128, -128, 0, 127, 127), GbaSamples.gain(source, 8.0).pcm)
        assertEquals(listOf(0, -32, 0, 32, 0), GbaSamples.fade(source, 2, 2).pcm)
        assertEquals(listOf(-64, -32, 0, 32, 64), source.pcm)
        val silence = source.copy(pcm = listOf(0, 0))
        assertEquals(silence, GbaSamples.normalise(silence))
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.gain(source, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.gain(source, -1.0) }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.fade(source, 6, 0) }
    }

    @Test fun starterSamplesAreDeterministicAndWithinBudget() {
        for (kind in GbaSamples.KINDS) {
            val sample = GbaSamples.generate(kind)
            assertEquals(sample, GbaSamples.generate(kind))
            assertNull(GbaRules.sampleProblem(sample))
            assertTrue(sample.pcm.any { it != 0 })
        }
        assertTrue(GbaSamples.starterBank().samples.sumOf { it.pcm.size } < GbaSound.MAX_PCM_BYTES)
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.generate("Unknown") }
        assertThrows(IllegalArgumentException::class.java) { GbaSamples.generate("Pulse", 48001) }
    }

    private fun wav(data: ByteArray, bits: Int = 16, channels: Int = 1, rate: Int = 8363, encoding: Int = 1): ByteArray {
        val out = ByteArray(44 + data.size + (data.size and 1))
        fun text(offset: Int, value: String) { value.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() } }
        fun word(offset: Int, value: Int, count: Int) { repeat(count) { out[offset + it] = (value ushr (it * 8)).toByte() } }
        text(0, "RIFF"); word(4, out.size - 8, 4); text(8, "WAVE"); text(12, "fmt "); word(16, 16, 4)
        word(20, encoding, 2); word(22, channels, 2); word(24, rate, 4)
        word(28, rate * channels * bits / 8, 4); word(32, channels * bits / 8, 2); word(34, bits, 2)
        text(36, "data"); word(40, data.size, 4); data.copyInto(out, 44)
        return out
    }
}
