package org.thortracker.system.studio

import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.system.gba.GbaSamples
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSampleAssetsTest {
    @Test fun wavesKeepStereoRateAndEverySupportedSourceBit() {
        val source = wave(24, intArrayOf(-8_388_608, 1, 8_388_607, -1), channels = 2, rate = 44_100, oddChunk = true)
        val stereo = StudioWave.decode(source)
        assertEquals(2, stereo.channels)
        assertEquals(44_100, stereo.sampleRate)
        assertEquals(2, stereo.frames)
        assertContentEquals(floatArrayOf(-1f, 1f / 8388608, 8388607f / 8388608, -1f / 8388608), values(stereo))
        // GBA deliberately mixes and quantizes this same input. The shared RIFF parser has not changed that path.
        assertEquals(listOf(-64, 64), GbaSamples.fromWav(source).pcm)

        val packedInto32 = wave(32, intArrayOf(-8_388_608 shl 8, 1 shl 8), channels = 2, fmt = 0xfffe, validBits = 24)
        assertContentEquals(floatArrayOf(-1f, 1f / 8388608), values(StudioWave.decode(packedInto32)))
        val float = StudioWave.decode(wave(32, intArrayOf((-0f).toRawBits(), Float.MIN_VALUE.toRawBits()), fmt = 3))
        assertEquals((-0f).toRawBits(), float.sample(0).toRawBits())
        assertEquals(Float.MIN_VALUE.toRawBits(), float.sample(1).toRawBits())
        assertContentEquals(floatArrayOf(-1f, 127f / 128), values(StudioWave.decode(wave(8, intArrayOf(0, 255)))))
        assertContentEquals(floatArrayOf(-1f, 32767f / 32768), values(StudioWave.decode(wave(16, intArrayOf(-32768, 32767)))))
        for (rate in intArrayOf(8000, 192_000)) {
            assertEquals(rate, StudioWave.decode(wave(16, intArrayOf(1), rate = rate)).sampleRate)
        }
    }

    @Test fun malformedUnsupportedOrTooLargeWaveIsRejectedRatherThanReduced() {
        fun reject(input: ByteArray, word: String) {
            val exception = assertFailsWith<IllegalArgumentException> { StudioWave.decode(input) }
            assertTrue(exception.message.orEmpty().contains(word, ignoreCase = true), exception.message)
        }
        val valid = wave(16, intArrayOf(1, 2), oddChunk = true)
        reject(valid.copyOf(valid.size - 1), "truncated")
        reject(wave(32, intArrayOf(1)), "precision")
        reject(wave(64, intArrayOf(0), fmt = 3), "precision")
        reject(wave(32, intArrayOf(Float.NaN.toRawBits()), fmt = 3), "finite")
        reject(wave(32, intArrayOf(1.25f.toRawBits()), fmt = 3), "within")
        reject(wave(16, intArrayOf(1), rate = 7999), "8000")
        reject(wave(16, intArrayOf(1), rate = 192_001), "192000")
        reject(wave(16, intArrayOf(1), channels = 2), "alignment")
        reject(wave(32, intArrayOf(1), fmt = 0xfffe, validBits = 24), "padding")
        // 8-bit on disk can still exceed the decoded Float32 budget. Size alone cannot be the import gate.
        reject(wave(8, IntArray(StudioPcm.MAX_BYTES / Float.SIZE_BYTES + 1)), "16 MiB")
    }

    @Test fun assetsDeduplicateWithoutExposingPcmAndWaveformRangesAreExact() {
        val raw = FloatArray(80_000) { .125f }
        raw[200] = -.75f
        raw[207] = .875f
        raw[208] = -1f
        val pcm = StudioPcm.of(2, 48_000, raw)
        val first = StudioAssets.EMPTY.add(pcm)
        val same = first.assets.add(StudioPcm.of(2, 48_000, raw))
        assertSame(first.assets, same.assets)
        assertEquals(first.id, same.id)
        assertSame(pcm, same.assets[same.id])
        raw.fill(1f)
        assertEquals(-.75f, pcm.waveformMin(99, 104))
        assertEquals(.875f, pcm.waveformMax(99, 104))
        assertEquals(-1f, pcm.waveformMin(104, 105))
        assertEquals(-1f, pcm.waveformMin(0))
        assertEquals(.125f, pcm.waveformMax(110, 37_333))
        val copied = FloatArray(pcm.sampleCount + 3)
        pcm.copySamplesTo(copied, 3)
        assertEquals(-.75f, copied[203])
        copied[203] = .5f
        assertEquals(-.75f, pcm.sample(200))
    }

    @Test fun pickerCancelFailureAndStaleCompletionCannotEditEitherSong() {
        val sound = StudioSound().withPatch(1, SamplerPatch())
        val song = sound.system.blank().copy(sound = sound)
        val session = EditorSession(song)
        val picker = Picker()
        importStudioSample(session, picker, 1)
        picker.cancel()
        assertSame(song, session.song)
        assertFalse(session.canUndo)
        importStudioSample(session, picker, 1)
        picker.complete(byteArrayOf(1, 2, 3))
        assertNotNull(picker.failure)
        assertSame(song, session.song)
        assertFalse(session.canUndo)
        val valid = wave(16, intArrayOf(-16_384, 16_384), channels = 2)
        importStudioSample(session, picker, 1)
        val another = song.copy(title = "Opened while importing")
        session.replaceSong(another)
        picker.complete(valid)
        assertSame(another, session.song)
        assertFalse(session.canUndo)
        importStudioSample(session, picker, 1)
        picker.complete(valid)
        val patch = session.song.studio.patch(1) as SamplerPatch
        val pcm = assertNotNull(session.song.studio.assets[assertNotNull(patch.assetId)])
        assertEquals(2, pcm.channels)
        assertContentEquals(floatArrayOf(-.5f, .5f), values(pcm))
        session.undo()
        assertSame(another, session.song)
    }

    @Test fun replacingRescalesSliceGeometryWithoutChangingIdsOrOrphaningCommands() {
        val sample = StudioWave.decode(wave(16, IntArray(10)))
        val bank = StudioAssets.EMPTY.add(sample)
        val old = SamplerPatch("Chop", bank.id, loopStart = 2, loopEnd = 8,
            slices = listOf(StudioSlice(4, 0, 4), StudioSlice(25, 4, 10)), defaultSlice = 25)
        val sound = StudioSound(assets = bank.assets).withPatch(1, old).withPatch(2, SamplerPatch(assetId = bank.id))
        val song = sound.system.blank().copy(sound = sound)
        val session = EditorSession(song)
        val picker = Picker()
        importStudioSample(session, picker, 1)
        picker.complete(wave(16, intArrayOf(1)))
        assertSame(song, session.song)
        assertTrue(picker.failure.orEmpty().contains("too short"))
        importStudioSample(session, picker, 1)
        picker.complete(wave(16, IntArray(20) { 1 }))
        val patch = session.song.studio.patch(1) as SamplerPatch
        assertEquals(25, patch.defaultSlice)
        assertEquals(listOf(StudioSlice(4, 0, 8), StudioSlice(25, 8, 20)), patch.slices)
        assertEquals(4, patch.loopStart)
        assertEquals(16, patch.loopEnd)
        assertSame(sample, session.song.studio.assets[bank.id])
        session.undo()
        assertSame(song, session.song)
    }

    @Test fun sampleHistoryKeepsRecentImportsAndSharedKnobEditsWithinBudget() {
        val session = EditorSession(StudioSound().system.blank())
        fun load(n: Int) {
            val sample = StudioPcm.decode(1, 48_000, StudioPcm.MAX_BYTES / Float.SIZE_BYTES) { n / 16f }
            val bank = StudioAssets.EMPTY.add(sample)
            session.apply("Import $n") { s -> s.copy(sound = s.studio.copy(assets = bank.assets).withPatch(1, SamplerPatch(assetId = bank.id))) }
        }
        fun value() = assertNotNull(session.song.studio.assets[1]).sample(0)
        fun verify(n: Int) = assertEquals(n / 16f, value())
        for (n in 1..6) load(n)
        assertTrue(session.message.orEmpty().contains("64 MiB"))
        verify(6)
        val shared = session.song.studio.assets[1]
        repeat(12) { level ->
            session.apply("Level") { song ->
                val studio = song.studio
                song.copy(sound = studio.withPatch(1, assertNotNull(studio.patch(1)).withValue(7, level)))
            }
        }
        assertSame(shared, session.song.studio.assets[1])
        repeat(12) { session.undo(); verify(6) }
        session.undo(); verify(5)
        session.undo(); verify(4)
        session.undo(); verify(3)
        assertFalse(session.canUndo)
        session.redo(); verify(4)
        load(7)
        assertFalse(session.canRedo)
        verify(7)
        session.undo(); verify(4)
        session.redo(); verify(7)
    }

    private class Picker : MenuContext {
        private var read: ((String, String, ByteArray) -> Unit)? = null
        var failure: String? = null
        fun cancel() { read = null }
        fun complete(bytes: ByteArray) { val callback = assertNotNull(read); read = null; callback("Picked", ".wav", bytes) }
        override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) = Unit
        override fun text(title: String, initial: String, maxLength: Int, accepted: (String) -> Unit) = Unit
        override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) { read = picked }
        override fun status(message: String) = Unit
        override fun error(message: String) { failure = message }
        override fun installRhythmRom(bytes: ByteArray): String? = null
    }

    private fun values(pcm: StudioPcm) = FloatArray(pcm.sampleCount) { pcm.sample(it) }

    private fun wave(bits: Int, samples: IntArray, channels: Int = 1, rate: Int = 48_000, fmt: Int = 1,
        validBits: Int = bits, oddChunk: Boolean = false): ByteArray {
        val width = bits / 8
        val format = ByteArray(if (fmt == 0xfffe) 40 else 16)
        format.put(0, fmt, 2)
        format.put(2, channels, 2)
        format.put(4, rate, 4)
        format.put(8, rate * width * channels, 4)
        format.put(12, width * channels, 2)
        format.put(14, bits, 2)
        if (fmt == 0xfffe) {
            format.put(16, 22, 2)
            format.put(18, validBits, 2)
            format.put(20, if (channels == 1) 4 else 3, 4)
            byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71)
                .copyInto(format, 24)
        }
        val dataBytes = samples.size * width
        val data = 12 + 8 + format.size + (if (oddChunk) 10 else 0)
        return ByteArray(data + 8 + dataBytes + (dataBytes and 1)).apply {
            "RIFF".encodeToByteArray().copyInto(this, 0)
            put(4, size - 8, 4)
            "WAVEfmt ".encodeToByteArray().copyInto(this, 8)
            put(16, format.size, 4)
            format.copyInto(this, 20)
            if (oddChunk) {
                "JUNK".encodeToByteArray().copyInto(this, data - 10)
                put(data - 6, 1, 4)
                this[data - 2] = 0x7f
            }
            "data".encodeToByteArray().copyInto(this, data)
            put(data + 4, dataBytes, 4)
            for (i in samples.indices) put(data + 8 + i * width, samples[i], minOf(4, width))
        }
    }

    private fun ByteArray.put(offset: Int, value: Int, width: Int) {
        for (i in 0 until width) this[offset + i] = (value ushr (i * 8)).toByte()
    }
}
