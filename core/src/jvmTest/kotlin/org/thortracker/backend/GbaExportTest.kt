package org.thortracker.backend

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.gba.GbaEnvelope
import org.thortracker.system.gba.GbaEnvelopePoint
import org.thortracker.system.gba.GbaSample
import org.thortracker.system.gba.GbaSound

class GbaExportTest {
    private fun u8(b: ByteArray, p: Int) = b[p].toInt() and 255
    private fun u16(b: ByteArray, p: Int) = u8(b, p) or (u8(b, p + 1) shl 8)
    private fun u32(b: ByteArray, p: Int) = u16(b, p) or (u16(b, p + 2) shl 16)
    private fun module(b: ByteArray) = u32(b, 12 + 4 * u16(b, 0)) + 8
    private fun pointer(b: ByteArray, index: Int) = module(b).let { it + u32(b, it + 276 + index * 4) }
    private fun firstXmInstrument(b: ByteArray): Int {
        var offset = 336
        repeat(u16(b, 70)) { offset += 9 + u16(b, offset + 7) }
        return offset
    }
    private fun reject(message: String, block: () -> Unit) {
        try { block(); fail("Expected rejection containing: $message") } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(message)) }
    }

    @Test fun xmHasRealHeadersDeltaPcmAndEnvelopeRelease() {
        val song = GbaGoldenSongs.features()
        val b = GbaXmExporter.export(song)
        assertEquals("Extended Module: ", b.copyOfRange(0, 17).decodeToString())
        assertEquals(0x104, u16(b, 58))
        assertEquals(276, u32(b, 60))
        assertEquals(2, u16(b, 64)) // parked order removed
        assertEquals(1, u16(b, 66)) // restart renumbered
        assertEquals(8, u16(b, 68))
        assertEquals(2, u16(b, 70))
        assertEquals(1, u16(b, 74)) // linear periods
        val inst = firstXmInstrument(b)
        assertEquals(263, u32(b, inst))
        assertEquals(1, u16(b, inst + 27))
        assertEquals(3, u8(b, inst + 225))
        assertEquals(1, u8(b, inst + 227)) // sustain node
        assertEquals(3, u8(b, inst + 233)) // volume on+sustain
        assertEquals(5, u8(b, inst + 234)) // panning on+loop
        assertEquals(96, u16(b, inst + 239)) // explicitly canonical fadeout
        val sample = inst + 263
        assertEquals(17, u32(b, sample))
        assertEquals(3, u32(b, sample + 4))
        assertEquals(7, u32(b, sample + 8))
        assertEquals(1, u8(b, sample + 14))
        assertEquals(190, u8(b, sample + 15))
        var value = 0
        val pcm = (song.sound as GbaSound).samples[0].pcm
        pcm.forEachIndexed { i, expected ->
            value = (value + u8(b, sample + 40 + i)).toByte().toInt()
            assertEquals(expected, value)
        }
    }

    @Test fun nativeBankContainsExpandedUnsignedLoopsAndDeduplicatedPitchVariants() {
        val b = GbaLowering.soundbank(GbaGoldenSongs.features())
        assertEquals("*maxmod*", b.copyOfRange(4, 12).decodeToString())
        assertEquals(3, u16(b, 0)) // loop, one-shot and unmapped silence
        assertEquals(1, u16(b, 2))
        assertEquals(-1, u32(b, 12 + 4 * (u16(b, 0) + 1)))
        val m = module(b)
        assertEquals(0x18, u8(b, m - 3))
        assertEquals(2, u8(b, m)); assertEquals(64, u8(b, m + 1)); assertEquals(5, u8(b, m + 2))
        assertEquals(0x1E, u8(b, m + 4))
        assertEquals(1, u8(b, m + 8))
        assertEquals(255, u8(b, m + 76 + 2))
        val record = u32(b, 12)
        val data = record + 20
        assertEquals(528, u32(b, record + 8))
        assertEquals(525, u32(b, record + 12))
        val source = (GbaGoldenSongs.features().sound as GbaSound).samples[0].pcm
        repeat(528) { i -> assertEquals(source[if (i < 10) i else 3 + (i - 10) % 7] + 128, u8(b, data + i)) }
        repeat(4) { i -> assertEquals(u8(b, data + 3 + i), u8(b, data + 528 + i)) }
        val first = pointer(b, 64)
        val pitchVariant = pointer(b, 68)
        assertEquals(u16(b, first + 10), u16(b, pitchVariant + 10))
        assertTrue(u16(b, first + 2) != u16(b, pitchVariant + 2))
        val instrument = pointer(b, 0)
        assertEquals(27, u8(b, instrument + 5))
        assertEquals(3, u8(b, instrument + 1))
        assertEquals(0, u16(b, instrument + 12 + 8 + 8 + 2)) // last node has zero range/value
    }

    private data class Event(val note: Int, val instrument: Int, val effect: Int, val param: Int)
    private fun pattern(b: ByteArray, index: Int): List<List<Event?>> {
        val m = module(b)
        var at = pointer(b, u8(b, m + 1) + u8(b, m + 2) + index)
        val rows = u8(b, at++) + 1
        val masks = IntArray(32)
        val notes = IntArray(32) { 250 }
        val instruments = IntArray(32)
        val effects = IntArray(32)
        val params = IntArray(32)
        return List(rows) {
            val row = MutableList<Event?>(8) { null }
            while (true) {
                val channel = u8(b, at++)
                if (channel == 0) break
                val ch = (channel and 127) - 1
                if (channel and 128 != 0) masks[ch] = u8(b, at++)
                val mask = masks[ch]
                var explicitNote = 250
                if (mask and 1 != 0) { notes[ch] = u8(b, at++); explicitNote = notes[ch] }
                if (mask and 2 != 0) instruments[ch] = u8(b, at++)
                if (mask and 4 != 0) at++
                if (mask and 8 != 0) { effects[ch] = u8(b, at++); params[ch] = u8(b, at++) }
                row[ch] = Event(if (mask and 16 != 0) notes[ch] else explicitNote, if (mask and 34 != 0) instruments[ch] else 0, if (mask and 128 != 0) effects[ch] else 0, if (mask and 128 != 0) params[ch] else 0)
            }
            row
        }
    }

    @Test fun compressedRowsKeepHeldEffectsKeyoffAndRenumberedJumps() {
        val b = GbaLowering.soundbank(GbaGoldenSongs.features())
        val first = pattern(b, 0)
        assertEquals(Event(60, 1, 0, 0), first[0][0])
        assertEquals(first[0][0], first[1][0])
        assertEquals(Event(250, 0, 8, 0x24), first[2][0])
        assertEquals(Event(250, 0, 8, 0), first[3][0]) // vibrato memory, NOT cleared effect
        assertEquals(Event(255, 0, 0, 0), first[4][0])
        assertEquals(Event(250, 0, 19, 0xB0), first[7][0])
        assertEquals(Event(250, 0, 3, 3), first[12][0])
        val second = pattern(b, 1)
        assertEquals(Event(107, 1, 0, 0), second[3][0])
        assertEquals(Event(250, 0, 2, 1), second[9][0])
    }

    @Test fun effectMappingUsesMaxmodIdentifiersAndFineSlideMemory() {
        val actual = pattern(GbaLowering.soundbank(GbaGoldenSongs.effects()), 0)
        val expected = listOf(0x0A37, 0x0612, 0x0600, 0x0508, 0x0710, 0x0847, 0x0C10, 0x0B02, 0x1234, 0x18C0, 0x0F01, 0x0410, 0x1B20, 0x06F3, 0x0600, 0x05F4, 0x0500, 0x1334, 0x1346, 0x18C0, 0x1323, 0x1302, 0x1312, 0x13C3, 0x13D2, 0x13E1, 0x0103, 0x1496)
        expected.forEachIndexed { i, code -> assertEquals("effect row $i", code, actual[i][0]!!.let { (it.effect shl 8) or it.param }) }
    }

    @Test fun sourceRateAndRootHaveOneCanonicalPitchMeaning() {
        val a = GbaExportConstraints.pitch(GbaSample(pcm = listOf(0), sampleRate = 8363, rootNote = 48))
        val b = GbaExportConstraints.pitch(GbaSample(pcm = listOf(0), sampleRate = 8363, rootNote = 60))
        assertEquals(0, a.relativeNote); assertEquals(0, a.fineTune)
        assertEquals(-12, b.relativeNote); assertEquals(4181, b.frequency)
        assertTrue(abs(a.centsError) < 1)
        assertTrue(GbaExportConstraints.warnings(GbaGoldenSongs.features().sound as GbaSound).any { "fadeout" in it })
    }

    @Test fun malformedDataFailsWithoutPartiallyPublishingAnImage() {
        val song = GbaGoldenSongs.effects()
        val sound = song.sound as GbaSound
        fun invalid(sample: GbaSample): Song = song.copy(sound = sound.copy(samples = listOf(sample)))
        reject("signed 8-bit") { GbaXmExporter.export(invalid(sound.samples[0].copy(pcm = listOf(128)))) }
        reject("forward-loop") { GbaXmExporter.export(invalid(sound.samples[0].copy(loopStart = 7, loopEnd = 7))) }
        reject("rate") { GbaXmExporter.export(invalid(sound.samples[0].copy(sampleRate = 0))) }
        val badInstrument = sound.instruments[0].copy(volumeEnvelope = GbaEnvelope(points = listOf(GbaEnvelopePoint(0, 64)), enabled = true))
        reject("two points") { GbaXmExporter.export(song.copy(sound = sound.copy(instruments = listOf(badInstrument) + sound.instruments.drop(1)))) }
        val map = sound.instruments[0].keymap.toMutableList().also { it[95] = -1 }
        reject("C7..B7") { GbaXmExporter.export(song.copy(sound = sound.copy(instruments = listOf(sound.instruments[0].copy(keymap = map)) + sound.instruments.drop(1)))) }
        reject("F00") { GbaLowering.effect(15, 0) }
        reject("preserve slides") { GbaLowering.effect(1, 0xE0) }
        reject("one direction") { GbaLowering.effect(10, 0x11) }
        reject("unsupported") { GbaLowering.effect(14, 0x50) }
        val out = ByteArray(GbaPlayerImage.SIZE) { 0x55 }
        reject("speed") { GbaLowering.lower(song.copy(ticksPerRow = 0), out) }
        assertTrue(out.all { it == 0x55.toByte() })
    }

    @Test fun sampleLimitsCountInstrumentLocalCopiesAndUnmappedSilence() {
        val base = GbaGoldenSongs.effects()
        val sound = base.sound as GbaSound
        val samples = List(16) { GbaSample(name = "Sample $it", pcm = listOf(it)) }
        val keys = List(96) { if (it in 1..16) it - 1 else -1 }
        val tooMany = sound.instruments[0].copy(keymap = keys)
        assertTrue(GbaExportConstraints.errors(sound.copy(samples = samples, instruments = listOf(tooMany) + sound.instruments.drop(1))).any { "16 local samples" in it })
        val four = sound.instruments[0].copy(keymap = List(96) { if (it in 1..3) it else 0 })
        assertTrue(GbaExportConstraints.errors(sound.copy(samples = samples, instruments = List(64) { four })).any { "at most 255" in it })
    }

    @Test fun sampleOffsetRejectsConverterIndexAmbiguityAndOutOfRangeEndpoints() {
        val simple = GbaGoldenSongs.effects().sound as GbaSound
        assertEquals(null, GbaExportConstraints.sampleOffsetError(simple, Cell(48, 1, 9, 1)))
        assertTrue(GbaExportConstraints.sampleOffsetError(simple, Cell(48, 1, 9, 4))!!.contains("beyond"))
        assertTrue(GbaExportConstraints.sampleOffsetError(simple, Cell(effect = 9, param = 1))!!.contains("explicit"))
        val kit = GbaGoldenSongs.features().sound as GbaSound
        assertTrue(GbaExportConstraints.sampleOffsetError(kit, Cell(48, 2, 9, 1))!!.contains("multisample"))
    }

    @Test fun imageWrapsExactlyTheExportedBankWithZeroReservedBytes() {
        val song = GbaGoldenSongs.features()
        val out = ByteArray(GbaPlayerImage.SIZE) { -1 }
        GbaLowering.lower(song, out)
        val bank = GbaLowering.soundbank(song)
        assertEquals("GBAM", out.copyOfRange(0, 4).decodeToString())
        assertEquals(64 + bank.size, u32(out, 8))
        assertEquals(bank.size, u32(out, 16))
        assertArrayEquals(bank, out.copyOfRange(64, 64 + bank.size))
        assertTrue(out.copyOfRange(24, 64).all { it == 0.toByte() })
        assertTrue(out.copyOfRange(64 + bank.size, out.size).all { it == 0.toByte() })
    }
}
