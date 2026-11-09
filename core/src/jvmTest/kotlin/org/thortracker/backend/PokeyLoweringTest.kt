package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.pokey.PokeyDemoSong
import org.thortracker.system.pokey.PokeyInstrument
import org.thortracker.system.pokey.PokeyKind
import org.thortracker.system.pokey.PokeyMachine
import org.thortracker.system.pokey.PokeyPresets
import org.thortracker.system.pokey.PokeySample
import org.thortracker.system.pokey.PokeySystem
import org.thortracker.system.pokey.pokey

/**
 * The POKEY lowering against the image layout of `pokey_image.h` (docs/AUDIO_ENGINE.md, "POKEY: Image layout"): exact
 * bytes of a hand-checked small song, and every condition `pk_load_image` checks, re-implemented here from the
 * specification ([loaderProblem]) and applied to the demo, the shortest song and a song using every kind.
 */
class PokeyLoweringTest {
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF

    @Test
    fun shortestSongLowersByteForByte() {
        val song = PokeyDemoSong.shortest()
        val image = PokeyLowering.lower(song)
        assertEquals(65536, image.size)
        assertEquals("TTPK", String(image.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(1, u8(image, 4)) // layout
        assertEquals(0, u8(image, 5)) // NTSC
        assertEquals(1, u8(image, 6)) // multispeed
        assertEquals(1, u8(image, 7)) // speed
        assertEquals(1, u16(image, 8)) // orders
        assertEquals(0, u16(image, 10)) // loop
        assertEquals(PokeyPresets.ALL.size, u8(image, 12)) // highest filled slot
        assertEquals(0, u8(image, 13)) // AUDCTL bits
        assertEquals(0, u16(image, 16)) // no samples
        assertEquals(0, u8(image, 18))
        for (i in 19 until 64) assertEquals("header byte $i", 0, u8(image, i))
        assertEquals(1, u8(image, 64)) // rows of order 0
        // POKEY 1: mask note+instrument, A-4 = 57, slot 1; POKEY 2-4: one empty row each, stored once.
        val data = 4544
        assertArrayEquals(byteArrayOf(0x03, 57, 1, 0x80.toByte()), image.copyOfRange(data, data + 4))
        assertEquals(listOf(0, 3, 3, 3), (0 until 4).map { u16(image, 320 + 2 * it) })
        // Slot 1 "Pure lead": defined, 8-bit, its volume envelope right after the patterns, the rest none.
        val ins = 2368
        assertEquals(0x80, u8(image, ins))
        for (i in 1..7) assertEquals("record byte $i", 0, u8(image, ins + i))
        assertEquals(0xFFFF, u16(image, ins + 8))
        assertEquals(4, u16(image, ins + 10))
        assertEquals(listOf(0xFFFF, 0xFFFF, 0xFFFF), listOf(12, 14, 16).map { u16(image, ins + it) })
        for (i in 18 until 32) assertEquals(0, u8(image, ins + i))
        val volume = PokeyPresets.ALL[0].instrument.volume
        assertEquals(volume.values.size, u16(image, data + 4))
        assertEquals(volume.loop, u16(image, data + 6))
        assertEquals(volume.release, u16(image, data + 8))
        assertEquals(volume.values, (0 until volume.values.size).map { u8(image, data + 10 + it) })
        assertEquals(PokeyLowering.dataSize(song), u16(image, 14))
        assertNull(loaderProblem(image))
    }

    @Test
    fun recordsWriteOnlyTheFieldsTheKindUses() {
        // The editor keeps every field across kind switches; the image must hold the loader's zeros instead.
        val tone = PokeyInstrument(name = "t", kind = PokeyKind.TONE, filterOffset = 5, difference = true, partnerVolume = 4,
            sample = 3, root = 60, rate = 12000, loop = 7, fast = true)
        val r = PokeyLowering.record(tone, listOf(0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF))
        assertEquals(0x80 or 0x08, u8(r, 0))
        assertEquals(listOf(0, 0, 0, 0, 0), (1..5).map { u8(r, it) })
        assertEquals(0, u16(r, 6))
        assertEquals(0xFFFF, u16(r, 8))

        val sample = PokeyInstrument(kind = PokeyKind.SAMPLE, sample = 2, root = 50, rate = 7990, loop = 10, filterOffset = -3,
            partnerVolume = 9, volume = NesEnvelope(listOf(15, 0)), distortion = NesEnvelope(listOf(5)), difference = true)
        val song = withInstrument(PokeySystem.blank(), 1, sample).let {
            it.copy(sound = it.pokey.copy(samples = List(3) { k -> PokeySample("s$k", List(20) { 3 }) }))
        }
        val image = PokeyLowering.lower(song)
        val rec = 2368
        assertEquals(0x80 or 0x04, u8(image, rec))
        assertEquals(0, u8(image, rec + 1))
        assertEquals(0, u8(image, rec + 2))
        assertEquals(2, u8(image, rec + 3))
        assertEquals(50, u8(image, rec + 4))
        assertEquals(7990, u16(image, rec + 6))
        assertEquals(10, u16(image, rec + 8))
        assertEquals(0xFFFF, u16(image, rec + 10)) // volume: not for samples
        assertEquals(0xFFFF, u16(image, rec + 16)) // distortion: not for samples
        assertNull(loaderProblem(image))

        val hp = PokeyInstrument(kind = PokeyKind.JOINED_FILTERED, difference = true, filterOffset = 4, fast = true,
            partnerDistortion = 8, partnerVolume = 3, pitchRelative = true)
        val h = PokeyLowering.record(hp, listOf(0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF))
        assertEquals(0x80 or 0x01 or 0x02 or 0x08 or 0x10 or 0x20, u8(h, 0))
        assertEquals(4, u8(h, 1))
        assertEquals(0x13, u8(h, 2)) // volume only, level 3
        val filtered = PokeyLowering.record(PokeyInstrument(kind = PokeyKind.FILTERED, filterOffset = -2), listOf(0, 0, 0, 0))
        assertEquals(0xFE, u8(filtered, 1))
        assertEquals(0x80 or 0x02, u8(filtered, 0))
    }

    @Test
    fun samplesPackTwoLevelsPerByteLowNibbleFirst() {
        val samples = listOf(PokeySample("a", listOf(1, 2, 3)), PokeySample("b", listOf(15, 14)))
        val song = PokeySystem.blank().let { it.copy(sound = it.pokey.copy(samples = samples)) }
        val image = PokeyLowering.lower(song)
        assertEquals(2, u8(image, 18))
        assertEquals(3, u16(image, 16))
        assertEquals(listOf(0, 3, 2, 2), listOf(4416, 4418, 4420, 4422).map { u16(image, it) })
        assertEquals(listOf(0x21, 0x03, 0xEF), (0 until 3).map { u8(image, 49152 + it) })
        assertNull(loaderProblem(image))
    }

    @Test
    fun envelopesAreSharedAndPatternsDeduplicated() {
        val song = PokeySystem.blank()
        val twice = withInstrument(song, 20, PokeyPresets.ALL[0].instrument)
        assertEquals(PokeyLowering.dataSize(song), PokeyLowering.dataSize(twice))
        val image = PokeyLowering.lower(twice)
        assertEquals(u16(image, 2368 + 10), u16(image, 2368 + 32 * 19 + 10))
        assertEquals(20, u8(image, 12))
    }

    @Test
    fun demoAndShortestLowerToLoadableImages() {
        for (song in listOf(PokeySystem.demo(), PokeyDemoSong.shortest(), everyKind())) {
            val image = ByteArray(PokeySystem.engine.imageSize) { 0x55 }
            PokeySystem.engine.lower(song, image)
            assertArrayEquals(PokeyLowering.lower(song), image)
            assertNull(song.title, loaderProblem(image))
            assertEquals(PokeyLowering.dataSize(song), u16(image, 14))
        }
        val demo = PokeyLowering.lower(PokeySystem.demo())
        assertEquals(6, u16(demo, 8))
        assertEquals(1, u16(demo, 10))
        assertEquals(4, u8(demo, 18))
    }

    @Test
    fun overfullSampleMemoryIsRejectedWithAMessage() {
        val big = List(2) { PokeySample("big$it", List(PokeySample.MAX_LENGTH) { 1 }) }
        val song = PokeySystem.blank().let { it.copy(sound = it.pokey.copy(samples = big)) }
        val e = assertThrows(IllegalArgumentException::class.java) { PokeyLowering.lower(song) }
        assertTrue(e.message!!, e.message!!.contains("sample memory"))
    }

    private fun withInstrument(song: Song, slot: Int, ins: PokeyInstrument) = song.copy(sound = song.pokey.withInstrument(slot, ins))

    /** A song with a sound of every kind on a channel it plays on, PAL, multispeed 2, 9-bit noise and 15 kHz at the start. */
    private fun everyKind(): Song {
        val blank = PokeySystem.blank()
        val kinds = listOf(
            0 to PokeyPresets.instrument("Pure lead"),
            1 to PokeyPresets.instrument("16-bit bass"),
            0 to PokeyPresets.instrument("Phasing lead"),
            1 to PokeyPresets.instrument("16-bit saw"),
            0 to PokeyPresets.drumKit(0)[0].instrument,
        )
        var sound = blank.pokey.copy(samples = listOf(PokeySample("k", List(99) { it % 16 })), machine = PokeyMachine.PAL,
            multispeed = 2, poly9 = true, base15 = true)
        kinds.forEachIndexed { i, (_, ins) -> sound = sound.withInstrument(40 + i, ins) }
        val tracks = List(4) { ch -> List(Songs.ROWS) { r ->
            val k = r / 8
            when {
                k < kinds.size && r % 8 == 0 && kinds[k].first == ch -> Cell(48 + r / 8, 40 + k).withEffect(1, 17, 0x02)
                ch == 3 && r == 63 -> Cell(Cell.CUT).withEffect(0, 31, 8).withEffect(1, 20, 0).withEffect(2, 23, 0).withEffect(3, 32, 2)
                else -> Cell.BLANK
            }
        } }
        return blank.copy(title = "every kind", sound = sound, patterns = listOf(Pattern("P", tracks, length = 64)), orders = listOf(0, 0))
    }

    /**
     * The conditions `pk_load_image` checks (docs/AUDIO_ENGINE.md "Image layout"), or null when [b] passes them all: the
     * header, rows and order tables, instrument records and their envelopes, sample entries and every pattern decoded
     * for exactly its order's rows inside the data region.
     */
    private fun loaderProblem(b: ByteArray): String? {
        if (b.size != 65536) return "size"
        if (String(b.copyOfRange(0, 4), Charsets.US_ASCII) != "TTPK") return "magic"
        if (u8(b, 4) != 1) return "layout"
        if (u8(b, 5) > 1) return "machine"
        if (u8(b, 6) !in 1..4) return "multispeed"
        if (u8(b, 7) !in 1..255) return "speed"
        val orders = u16(b, 8)
        if (orders !in 1..256) return "orders"
        if (u16(b, 10) >= orders) return "loop"
        val instruments = u8(b, 12)
        if (instruments > 64) return "instrument count"
        if (u8(b, 13) and 0x7E != 0) return "AUDCTL bits"
        val dataSize = u16(b, 14)
        if (dataSize > 44608) return "data size"
        val sampleSize = u16(b, 16)
        if (sampleSize > 16384) return "sample size"
        val samples = u8(b, 18)
        if (samples > 32) return "sample count"
        for (i in 19 until 64) if (b[i].toInt() != 0) return "header byte $i"
        for (o in 0 until 256) {
            if (o >= orders) {
                if (u8(b, 64 + o) != 0) return "rows past the order count"
                for (c in 0 until 4) if (u16(b, 320 + 2 * (4 * o + c)) != 0) return "patterns past the order count"
            } else if (u8(b, 64 + o) == 0) return "rows 0 (256) beyond Thor Tracker's 64"
        }
        val lengths = IntArray(samples)
        for (k in 0 until 32) {
            val off = u16(b, 4416 + 4 * k)
            val len = u16(b, 4418 + 4 * k)
            if (k >= samples) { if (off != 0 || len != 0) return "sample entry past the count"; continue }
            if (len !in 1..32768) return "sample length"
            if (off + (len + 1) / 2 > sampleSize) return "sample outside the region"
            lengths[k] = len
        }
        val defined = BooleanArray(65)
        for (n in 1..64) {
            val at = 2368 + 32 * (n - 1)
            val rec = b.copyOfRange(at, at + 32)
            if (rec.all { it.toInt() == 0 }) continue
            if (n > instruments) return "record past the instrument count"
            val flags = u8(rec, 0)
            if (flags and 0x80 == 0 || flags and 0xBF.inv() != 0) return "flags $n"
            val joined = flags and 1 != 0
            val filtered = flags and 2 != 0
            val sample = flags and 4 != 0
            if (sample && (joined || filtered)) return "sample with joined/filtered $n"
            if (flags and 0x20 != 0 && !filtered) return "difference without filtered $n"
            val offset = rec[1].toInt()
            if (!filtered && offset != 0) return "filter offset $n"
            if (flags and 0x20 != 0 && offset !in 1..127) return "difference offset $n"
            if (!joined && !filtered && u8(rec, 2) != 0) return "partner AUDC $n"
            if (sample) {
                if (u8(rec, 3) >= samples) return "sample index $n"
                if (u8(rec, 4) > 107) return "root $n"
                if (u16(rec, 6) !in 1000..32000) return "rate $n"
                val loop = u16(rec, 8)
                if (loop != 0xFFFF && loop >= lengths[u8(rec, 3)]) return "loop $n"
                if (u16(rec, 10) != 0xFFFF || u16(rec, 16) != 0xFFFF) return "sample volume/distortion envelope $n"
            } else {
                if (u8(rec, 3) != 0 || u8(rec, 4) != 0 || u16(rec, 6) != 0 || u16(rec, 8) != 0xFFFF) return "sample fields $n"
            }
            if (u8(rec, 5) != 0) return "byte 5 $n"
            for (i in 18 until 32) if (rec[i].toInt() != 0) return "reserved $n"
            val ranges = listOf(0..15, -64..63, -64..63, 0..8)
            for (e in 0 until 4) {
                val o = u16(rec, 10 + 2 * e)
                if (o == 0xFFFF) continue
                if (o + 6 > dataSize) return "envelope header $n/$e"
                val len = u16(b, 4544 + o)
                val loop = u16(b, 4544 + o + 2)
                val release = u16(b, 4544 + o + 4)
                if (len !in 1..256 || o + 6 + len > dataSize) return "envelope length $n/$e"
                if (loop != 0xFFFF && loop >= len) return "envelope loop $n/$e"
                if (release != 0xFFFF && (release !in 1 until len || (loop != 0xFFFF && release <= loop))) return "envelope release $n/$e"
                for (i in 0 until len) {
                    val v = if (e == 1 || e == 2) b[4544 + o + 6 + i].toInt() else u8(b, 4544 + o + 6 + i)
                    if (v !in ranges[e]) return "envelope value $n/$e"
                }
            }
            defined[n] = true
        }
        for (o in 0 until orders) {
            val rows = u8(b, 64 + o)
            for (c in 0 until 4) {
                var at = u16(b, 320 + 2 * (4 * o + c))
                var row = 0
                while (row < rows) {
                    if (at >= dataSize) return "pattern runs past the data size ($o, $c)"
                    val m = u8(b, 4544 + at++)
                    if (m and 0x80 != 0) { row += (m and 0x7F) + 1; continue }
                    if (m == 0 || m and 0xE0 != 0 || (m shr 2) > 4) return "row mask ($o, $c)"
                    val need = (m and 1) + (m shr 1 and 1) + 2 * (m shr 2)
                    if (at + need > dataSize) return "row past the data size ($o, $c)"
                    if (m and 1 != 0) { val note = u8(b, 4544 + at++); if (note > 107 && note != 0xFE) return "note ($o, $c)" }
                    if (m and 2 != 0) { val slot = u8(b, 4544 + at++); if (slot !in 1..64 || !defined[slot]) return "instrument ($o, $c)" }
                    repeat(m shr 2) {
                        val code = u8(b, 4544 + at++)
                        val p = u8(b, 4544 + at++)
                        val bad = when (code) {
                            0, 1, 2, 3, 4, 10, 13, 16, 17, 25, 28 -> false
                            11 -> p >= orders
                            12 -> p > 15
                            15 -> p == 0
                            20, 23 -> p > 1
                            27, 29 -> p != 0
                            31 -> p > 8
                            32 -> p > 2
                            else -> true
                        }
                        if (bad) return "effect $code/$p ($o, $c)"
                    }
                    row++
                }
                if (row != rows) return "a run past the order's rows ($o, $c)"
            }
        }
        return null
    }
}
