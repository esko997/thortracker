package org.thortracker.backend

import org.thortracker.system.gb.gb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.audio.HugeImageLayout
import org.thortracker.model.Cell
import org.thortracker.system.gb.GbInstrument
import org.thortracker.system.gb.GbBank
import org.thortracker.model.Scale
import org.thortracker.model.ScaleKind
import org.thortracker.model.Song
import org.thortracker.model.Songs
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads exported files back with an independent parser of hUGETracker's v6 layout (TSongV6 in
 * hUGETracker 1.0.11 src/song.pas; field list in its manual "UGE file format").
 */
class UgeExporterTest {
    private class UgeCell(val note: Int, val instrument: Int, val volume: Int, val effect: Int, val param: Int)

    private class UgeInstrument(
        val type: Int, val name: String, val length: Int, val lengthEnabled: Boolean, val initialVolume: Int,
        val volSweepDirection: Int, val volSweepAmount: Int, val sweepTime: Int, val sweepIncDec: Int,
        val sweepShift: Int, val duty: Int, val outputLevel: Int, val waveform: Int, val counterStep: Int,
        val subpatternEnabled: Boolean, val subpattern: List<UgeCell>,
    )

    private class Uge(
        val version: Int, val name: String, val artist: String, val comment: String,
        val instruments: List<UgeInstrument>, val waves: List<List<Int>>, val ticksPerRow: Int,
        val timerEnabled: Boolean, val timerDivider: Int, val patterns: Map<Int, List<UgeCell>>,
        val orders: List<List<Int>>, val routines: List<String>,
    )

    private fun parse(bytes: ByteArray): Uge {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u8() = b.get().toInt() and 0xFF
        fun i32() = b.int
        fun shortString(): String {
            val length = u8()
            val raw = ByteArray(255).also { b.get(it) }
            assertTrue("ShortString padding must be zero", raw.drop(length).all { it.toInt() == 0 })
            return String(raw, 0, length, Charsets.UTF_8)
        }
        fun cell() = UgeCell(i32(), i32(), i32(), i32(), u8())
        val version = i32()
        val name = shortString()
        val artist = shortString()
        val comment = shortString()
        val instruments = List(45) {
            UgeInstrument(i32(), shortString(), i32(), u8() != 0, u8(), i32(), u8(), i32(), i32(), i32(), u8(),
                i32(), i32(), i32(), u8() != 0, List(64) { cell() })
        }
        val waves = List(16) { List(32) { u8() } }
        val ticks = i32()
        val timerEnabled = u8() != 0
        val timerDivider = i32()
        val patterns = LinkedHashMap<Int, List<UgeCell>>()
        repeat(i32()) { val key = i32(); patterns[key] = List(64) { cell() } }
        val orders = List(4) { List(i32()) { i32() } }
        val routines = List(16) { val n = i32(); String(ByteArray(n).also { b.get(it) }) }
        assertFalse("trailing bytes after routines", b.hasRemaining())
        return Uge(version, name, artist, comment, instruments, waves, ticks, timerEnabled, timerDivider, patterns, orders, routines)
    }

    private fun withCell(song: Song, pattern: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.patterns[pattern]
        val tracks = p.tracks.toMutableList().also { t -> t[channel] = t[channel].toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[pattern] = p.copy(tracks = tracks) })
    }

    @Test
    fun fileSizeMatchesV6Layout() {
        val song = Songs.demo()
        val bytes = UgeExporter.export(song)
        val data = HugeLowering.lower(song)
        val instrument = 4 + 256 + 4 + 1 + 1 + 4 + 1 + 4 + 4 + 4 + 1 + 4 + 4 + 4 + 1 + 64 * 17
        assertEquals(1385, instrument)
        val expected = 4 + 3 * 256 + 45 * instrument + 16 * 32 + 4 + 1 + 4 +
            4 + data.tracks.size * (4 + 64 * 17) + 4 * (4 + 4 * (song.orders.size + 1)) + 16 * 4
        assertEquals(expected, bytes.size)
    }

    @Test
    fun headerInstrumentsAndWaves() {
        val song = Songs.demo().copy(title = "Grüße", artist = "A", comment = "C")
        val uge = parse(UgeExporter.export(song))
        assertEquals(6, uge.version)
        assertEquals("Grüße", uge.name)
        assertEquals("A", uge.artist)
        assertEquals("C", uge.comment)
        assertEquals(List(15) { 0 } + List(15) { 1 } + List(15) { 2 }, uge.instruments.map { it.type })
        assertEquals(song.gb.pulse.map { it.name }, uge.instruments.take(15).map { it.name })
        assertTrue(uge.instruments.all { !it.subpatternEnabled && it.subpattern.all { c -> c.note == 90 && c.effect == 0 } })
        assertEquals(song.gb.waves, uge.waves)
        assertEquals(6, uge.ticksPerRow)
        assertFalse(uge.timerEnabled)
        assertEquals(0, uge.timerDivider)
        assertTrue(uge.routines.all { it.isEmpty() })

        val zap = uge.instruments[3] // Pulse 4 "Sweep zap": decreasing envelope and downward sweep
        assertEquals("Sweep zap", zap.name)
        assertEquals(15, zap.initialVolume)
        assertEquals(1, zap.volSweepDirection) // stDown
        assertEquals(2, zap.volSweepAmount)
        assertEquals(2, zap.sweepTime)
        assertEquals(1, zap.sweepIncDec) // stDown
        assertEquals(2, zap.sweepShift)
        assertEquals(2, zap.duty)
        val saw = uge.instruments[15 + 1] // Wave 2 "Saw bass"
        assertEquals(4, saw.waveform)
        assertEquals(2, saw.outputLevel)
    }

    @Test
    fun instrumentFieldEncodings() {
        val base = Songs.blank()
        val song = base.copy(sound = base.gb.copy(
            pulse = base.gb.pulse.toMutableList().also {
                it[0] = GbInstrument(name = "Up", volume = 3, envelopeDirection = 1, envelopePace = 4, sweepDirection = 1,
                    length = 33, lengthEnabled = true)
            },
            noise = base.gb.noise.toMutableList().also {
                it[14] = GbInstrument(name = "Metal", kind = GbBank.NOISE, noiseWidth = 1)
            },
        ))
        val uge = parse(UgeExporter.export(song))
        val up = uge.instruments[0]
        assertEquals(0, up.volSweepDirection) // stUp
        assertEquals(0, up.sweepIncDec) // stUp
        assertEquals(33, up.length)
        assertTrue(up.lengthEnabled)
        assertEquals(1, uge.instruments[44].counterStep) // swSeven
        assertEquals("Metal", uge.instruments[44].name)
    }

    @Test
    fun patternsAndOrdersUseTrackKeysWithTrailingFiller() {
        var song = Songs.blank().copy(
            patterns = List(2) { Songs.blankPattern(Songs.blank(), "P$it") },
            orders = listOf(1, 0, 1),
            loopOrder = 1,
        )
        song = withCell(song, 0, 1, 4, Cell(Cell.CUT))
        song = withCell(song, 1, 2, 0, Cell(69, 2, 0x9, 0x03))
        val uge = parse(UgeExporter.export(song))
        assertEquals((0..8).toList(), uge.patterns.keys.toList())
        // Each order column: entries, then hUGETracker's ignored trailing 0.
        assertEquals(listOf(4, 0, 8, 0), uge.orders[0]) // only the final order uses the loop variant of pattern 1 / Pulse 1
        assertEquals(listOf(5, 1, 5, 0), uge.orders[1])
        assertEquals(listOf(6, 2, 6, 0), uge.orders[2])
        assertEquals(listOf(7, 3, 7, 0), uge.orders[3])
        val cut = uge.patterns.getValue(1)[4]
        assertEquals(listOf(90, 0, 0, 0xE, 0), listOf(cut.note, cut.instrument, cut.volume, cut.effect, cut.param))
        val waveNote = uge.patterns.getValue(6)[0]
        assertEquals(listOf(33, 2, 0x9, 0x03), listOf(waveNote.note, waveNote.instrument, waveNote.effect, waveNote.param))
        val jump = uge.patterns.getValue(8)[63]
        assertEquals(listOf(90, 0xB, 0x02), listOf(jump.note, jump.effect, jump.param))
        val empty = uge.patterns.getValue(0)[0]
        assertEquals(listOf(90, 0, 0, 0, 0), listOf(empty.note, empty.instrument, empty.volume, empty.effect, empty.param))
    }

    @Test
    fun refusesUnexportableSongs() {
        val routine = withCell(Songs.blank(), 0, 0, 0, Cell(60, 1, 0x6, 0x01))
        assertThrows(IllegalArgumentException::class.java) { UgeExporter.export(routine) }
        val longName = Songs.blank().copy(comment = "x".repeat(256))
        assertThrows(IllegalArgumentException::class.java) { UgeExporter.export(longName) }
    }

    @Test
    fun patternScalesChangeNeitherExportNorPreviewBytes() {
        val demo = Songs.demo()
        val scaled = demo.copy(patterns = demo.patterns.mapIndexed { i, p ->
            p.copy(scale = Scale((i * 5) % 12, ScaleKind.entries[1 + i % (ScaleKind.entries.size - 1)]))
        })
        assertNotEquals(demo, scaled)
        assertArrayEquals(UgeExporter.export(demo), UgeExporter.export(scaled))
        fun image(song: Song) = ByteArray(HugeImageLayout.SIZE).also { HugeImageLayout.build(HugeLowering.lower(song), it) }
        assertArrayEquals(image(demo), image(scaled))
    }
}
