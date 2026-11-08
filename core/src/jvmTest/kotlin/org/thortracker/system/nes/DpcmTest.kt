package org.thortracker.system.nes

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The DPCM converter against FamiStudio 4.5.3 itself: src/jvmTest/resources/dpcm-reference holds generated .wav files
 * and FamiStudio's conversion of each (tools/audio/dpcm_reference.py runs its `WaveFile.Load` and
 * `DPCMSample.Process`), named `<wav stem>_r<rate>[_i<initial>].dmc`.
 */
class DpcmTest {
    private fun bytes(name: String): ByteArray = javaClass.getResource("/dpcm-reference/$name")!!.readBytes()

    @Test
    fun wavConversionIsFamiStudiosByteForByte() {
        val names = javaClass.getResource("/dpcm-reference")!!.let { java.io.File(it.toURI()).list()!!.toList() }
        val references = names.filter { it.endsWith(".dmc") }.sorted()
        assertEquals(5, references.size, "reference conversions: $references")
        for (ref in references) {
            val m = Regex("""(.+)_r(\d+)(?:_i(\d+))?\.dmc""").matchEntire(ref)!!
            val wav = bytes("${m.groupValues[1]}.wav")
            val rate = m.groupValues[2].toInt()
            val initial = m.groupValues[3].ifEmpty { "32" }.toInt()
            val theirs = bytes(ref).map { it.toInt() and 0xFF }
            val ours = Dpcm.fromWav(wav, rate, initial)
            assertEquals(theirs.size, ours.size, "$ref: length")
            assertContentEquals(theirs, ours, "$ref: bytes")
            assertEquals(1, ours.size % 16, "$ref: 16n + 1 bytes")
        }
    }

    @Test
    fun dmcFilesArePaddedTo16nPlus1AndCut() {
        assertEquals(listOf(0x12), Dpcm.fromDmc(byteArrayOf(0x12)))
        assertEquals(List(17) { if (it < 2) 0x12 else 0x55 }, Dpcm.fromDmc(byteArrayOf(0x12, 0x12)))
        // After a byte whose last bit rises, padding with $AA so the level keeps alternating.
        assertEquals(0xAA, Dpcm.fromDmc(byteArrayOf(1, 0x80.toByte()))[2])
        assertEquals(33, Dpcm.fromDmc(ByteArray(32)).size)
        assertEquals(33, Dpcm.fromDmc(ByteArray(33)).size)
        assertEquals(DpcmSample.MAX_BYTES, Dpcm.fromDmc(ByteArray(5000)).size)
        assertFailsWith<IllegalArgumentException> { Dpcm.fromDmc(ByteArray(0)) }
    }

    @Test
    fun badWavFilesHaveMessages() {
        assertTrue(assertFailsWith<IllegalArgumentException> { Dpcm.fromWav(ByteArray(10)) }.message!!.contains("RIFF"))
        val wav = bytes("tone_16m44k.wav")
        assertFailsWith<IllegalArgumentException> { Dpcm.fromWav(wav.copyOf(100)) }
        assertFailsWith<IllegalArgumentException> { Dpcm.fromWav(wav, rate = 16) }
        val adpcm = wav.copyOf().also { it[20] = 2 }
        assertTrue(assertFailsWith<IllegalArgumentException> { Dpcm.fromWav(adpcm) }.message!!.contains("PCM"))
    }

    @Test
    fun decodeFollowsTheDmcOutputUnit() {
        // Up, up, down, then saturating at 0 and 127.
        assertEquals(listOf(64, 66, 68, 66, 64, 62, 60, 58), Dpcm.decode(listOf(0x03)).toList())
        assertEquals(126, Dpcm.decode(listOf(0xFF), dmcInitial = 63).last())
        assertEquals(0, Dpcm.decode(List(3) { 0 }, dmcInitial = 0).max())
    }

    @Test
    fun starterKitIsPlayableAndCompact() {
        val kit = DpcmStarterKit.samples()
        assertEquals(listOf("Kick", "Snare", "Hat", "Tom", "Bass"), kit.map { it.name })
        for (s in kit) {
            assertEquals(1, s.data.size % 16, s.name)
            assertTrue(s.data.size <= DpcmSample.MAX_BYTES, s.name)
        }
        assertTrue(kit.sumOf { Dpcm.aligned(it.data.size) } < Dpcm.MEMORY / 2, "the kit leaves room for the song's own samples")
        assertEquals(kit, DpcmStarterKit.samples())
        val ins = DpcmStarterKit.instrument(3)
        assertEquals(DpcmStarterKit.NOTES.keys, ins.dpcm.keys)
        assertEquals(3 + DpcmStarterKit.TOM, ins.dpcm.getValue(43).sample)
    }
}
