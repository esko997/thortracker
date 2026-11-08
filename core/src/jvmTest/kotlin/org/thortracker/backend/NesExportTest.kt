package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.nes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `.nsf` and FamiStudio text export, and the NES validation that guards them. */
class NesExportTest {
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    @Test
    fun nsfIsThePlayerAndTheLoweredData() {
        val song = NesSystem.demo()
        val nsf = NesSystem.exports.first { it.extension == "nsf" }.export(song)
        assertEquals("NESM", nsf.copyOfRange(0, 4).decodeToString())
        assertEquals(0x1a, nsf[4].toInt())
        assertEquals(1, nsf[5].toInt())
        assertEquals(1, nsf[6].toInt())
        assertEquals(1, nsf[7].toInt())
        assertEquals(NesPlayerImage.LOAD, u16(nsf, 8))
        assertEquals(NesPlayerImage.INIT, u16(nsf, 10))
        assertEquals(NesPlayerImage.PLAY, u16(nsf, 12))
        assertEquals("Thor Quest", nsf.copyOfRange(0x0e, 0x0e + 10).decodeToString())
        assertEquals(0, nsf[0x0e + 10].toInt())
        assertEquals(16639, u16(nsf, 0x6e))
        assertEquals(0, nsf[0x7a].toInt()) // NTSC
        assertEquals(0, nsf[0x7b].toInt()) // no expansion chips
        val body = nsf.copyOfRange(128, nsf.size)
        assertContentEquals(NesPlayerImage.BYTES, body.copyOfRange(0, NesPlayerImage.BYTES.size))
        val lowered = FamiStudioLowering.lower(song)
        val data = lowered.data
        val songAt = NesPlayerImage.SONG_BASE - NesPlayerImage.LOAD
        val samplesAt = NesPlayerImage.SAMPLE_BASE - NesPlayerImage.LOAD
        assertContentEquals(data, body.copyOfRange(songAt, songAt + data.size))
        assertTrue(body.copyOfRange(songAt + data.size, samplesAt).all { it == 0.toByte() })
        // The demo plays the starter kit: its samples end the file at $C000.
        assertTrue(lowered.samples.isNotEmpty())
        assertContentEquals(lowered.samples, body.copyOfRange(samplesAt, body.size))
        // The same bytes play: the NSF's song is what the tracer (and the preview engine) runs.
        val a = NesTracer(data)
        val b = NesTracer(body.copyOfRange(songAt, samplesAt))
        repeat(600) { assertContentEquals(a.frame(), b.frame()) }
    }

    @Test
    fun nsfTextFieldsAreAsciiAndTerminated() {
        val song = NesSystem.blank().copy(title = "Ünïcode title that is far longer than thirty-one", artist = "")
        val nsf = NsfExporter.export(song)
        val title = nsf.copyOfRange(0x0e, 0x2e)
        assertEquals(0, title[31].toInt())
        assertTrue(title.all { it.toInt() in 0..0x7e })
    }

    @Test
    fun textExportNamesInstrumentsArpeggiosAndPatterns() {
        val text = FamiStudioText.text(NesSystem.demo())
        val lines = text.lines()
        assertTrue(lines[0].startsWith("Project Version=\"4.5.3\" TempoMode=\"FamiTracker\" Name=\"Thor Quest\""))
        assertTrue(lines.any { it.trim() == "Instrument Name=\"01 Lead 50%\"" })
        assertTrue(lines.any { it.trim().startsWith("Envelope Type=\"Volume\" Length=\"12\" Loop=\"4\" Release=\"5\"") })
        assertTrue(lines.any { it.trim() == "Arpeggio Name=\"Arp 037\" Length=\"3\" Loop=\"0\" Values=\"0,3,7\"" })
        assertTrue(lines.any { it.trim().startsWith("Song Name=\"Thor Quest\" Length=\"5\" LoopPoint=\"1\" PatternLength=\"64\"") })
        assertEquals(listOf("Square1", "Square2", "Triangle", "Noise", "DPCM"), lines.filter { it.trim().startsWith("Channel") }.map { it.substringAfter("Type=\"").substringBefore('"') })
        // Unused default slots are not exported.
        assertFalse(text.contains("Sound 20"))
    }

    @Test
    fun textQuotesAreDoubled() {
        val song = NesSystem.blank().let { s ->
            val bank = s.nes.instruments.toMutableList()
            bank[0] = NesInstrument("Say \"hi\"")
            s.copy(title = "A \"quoted\" song", sound = s.nes.copy(instruments = bank))
        }
        val text = FamiStudioText.text(song)
        assertTrue(text.contains("Name=\"A \"\"quoted\"\" song\""))
        assertTrue(text.contains("Name=\"01 Say \"\"hi\"\"\""))
    }

    private fun withCell(song: Song, ch: Int, row: Int, cell: Cell): Song {
        val p = song.patterns[0]
        return song.copy(patterns = listOf(p.copy(tracks = p.tracks.mapIndexed { c, t -> if (c == ch) t.toMutableList().also { it[row] = cell } else t })))
    }

    @Test
    fun validationExplainsWhatTheEngineCannotPlay() {
        val blank = NesSystem.blank()
        fun errors(ch: Int, cell: Cell) = NesSystem.errors(withCell(blank, ch, 0, cell)).joinToString()
        assertTrue(errors(0, Cell(20, 1)).contains("below A-1"))
        assertTrue(errors(2, Cell(20, 1)).contains("below A-1"))
        assertEquals("", errors(3, Cell(12, 1)))
        assertTrue(errors(3, Cell(40, 1, NesRules.FX_VIBRATO, 0x44)).contains("Noise"))
        assertTrue(errors(2, Cell(40, 1, NesRules.FX_VOLUME, 0x04)).contains("Triangle"))
        assertTrue(errors(0, Cell(Cell.EMPTY, 0, NesRules.FX_ARPEGGIO, 0x37)).contains("needs a note"))
        assertTrue(errors(0, Cell(40, 1, NesRules.FX_VIBRATO, 0xD4)).contains("1-C"))
        assertTrue(errors(0, Cell(40, 1, NesRules.FX_SLIDE_UP, 0x70)).contains("outside"))
        assertTrue(errors(0, Cell(40, 1, NesRules.FX_NOTE_DELAY, 0x00)).contains("G00"))
        assertTrue(errors(0, Cell(40, 1, NesRules.FX_DUTY, 0x04)).contains("V00-V03"))
        assertTrue(errors(3, Cell(40, 1, NesRules.FX_DUTY, 0x02)).contains("V01"))
        assertTrue(errors(0, Cell(Cell.CUT, 3)).contains("OFF"))
        val bank = blank.nes.instruments.toMutableList()
        bank[0] = NesInstrument("Bad", volume = NesEnvelope(listOf(5, 4, 3), release = 1), arpeggio = NesEnvelope(listOf(1, 2), loop = 0, release = 1))
        val errs = NesSystem.errors(blank.copy(sound = blank.nes.copy(instruments = bank))).joinToString()
        assertTrue(errs.contains("needs a loop"), errs)
        assertTrue(errs.contains("only volume envelopes have a release"), errs)
        assertTrue(NesSystem.errors(blank.copy(ticksPerRow = 40)).joinToString().contains("1-31"))
        assertEquals(emptyList(), NesSystem.errors(NesSystem.demo()))
        assertEquals(emptyList(), NesSystem.warnings(NesSystem.demo()))
        for ((name, build) in NesGoldenSongs.ALL) assertEquals(emptyList(), NesSystem.errors(build()), name)
    }

    @Test
    fun structuralErrorsRejectBadShapes() {
        val blank = NesSystem.blank()
        val bank = blank.nes.instruments.toMutableList()
        bank[3] = NesInstrument("Loud", volume = NesEnvelope(listOf(16)))
        val e = NesSystem.structuralErrors(blank.copy(sound = blank.nes.copy(instruments = bank))).joinToString()
        assertTrue(e.contains("volume value 16"), e)
        assertTrue(NesSystem.structuralErrors(withCell(blank, 0, 0, Cell(57, 65))).joinToString().contains("0-64"))
        assertTrue(NesSystem.structuralErrors(withCell(blank, 0, 0, Cell(57, 1, 11, 1))).joinToString().contains("does not exist"))
    }

    @Test
    fun parkedOrdersAndPatternLoops() {
        val song = NesSystem.demo().copy(parkedOrders = listOf(1), loopOrder = 1)
        val arranged = NesSystem.arranged(song)
        assertEquals(listOf(0, 2, 1, 2), arranged.orders)
        assertEquals(1, arranged.loopOrder)
        val loop = NesSystem.patternLoop(song, 2)
        assertEquals(listOf(2), loop.orders)
        assertEquals(0, loop.loopOrder)
    }

    @Test
    fun finePitchRealisesTunings() {
        val fine = NesSystem.fine
        assertEquals(0, fine.of(Cell(57, 1)))
        assertEquals(5, fine.of(Cell(57, 1, NesRules.FX_FINE_PITCH, 0x85)))
        assertEquals(null, fine.of(Cell(57, 1, NesRules.FX_VIBRATO, 0x44)))
        assertEquals(Cell(57, 1, NesRules.FX_FINE_PITCH, 0x83), fine.write(Cell(55, 1), 57, 3))
        assertEquals(Cell(57, 1), fine.write(Cell(57, 1, NesRules.FX_FINE_PITCH, 0x83), 57, 0))
        assertEquals(null, fine.write(Cell(57, 1, NesRules.FX_VOLUME, 4), 57, 3))
        // A-4 is period 253 on the pulses: one unit up is ~6.8 cents.
        assertTrue(fine.cents(57, 1) in 6.5..7.1, "${fine.cents(57, 1)}")
        assertEquals(0..5, fine.range(NesSystem.noteRange.last))
    }
}
