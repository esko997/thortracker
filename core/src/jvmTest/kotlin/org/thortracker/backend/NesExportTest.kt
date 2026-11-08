package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesMmc5DemoSong
import org.thortracker.system.nes.NesMmc5System
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
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
        val samplesAt = lowered.sampleStart - NesPlayerImage.LOAD
        assertContentEquals(data, body.copyOfRange(songAt, songAt + data.size))
        assertTrue(body.copyOfRange(songAt + data.size, samplesAt).all { it == 0.toByte() })
        // The demo plays the starter kit: its samples end the file, at the top of \$C000-\$FFF9.
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

    /** NES + VRC6: the `.nsf` carries the VRC6 player and names the chip; the text names the expansion. */
    @Test
    fun vrc6ExportsNameTheChip() {
        val song = NesVrc6System.demo()
        assertEquals(emptyList(), NesVrc6System.errors(song))
        assertEquals(emptyList(), NesVrc6System.warnings(song))
        val nsf = NesVrc6System.exports.first { it.extension == "nsf" }.export(song)
        assertEquals(NesVrc6PlayerImage.LOAD, u16(nsf, 8))
        assertEquals(NesVrc6PlayerImage.INIT, u16(nsf, 10))
        assertEquals(NesVrc6PlayerImage.PLAY, u16(nsf, 12))
        assertEquals(1, nsf[0x7b].toInt()) // expansion chips: VRC6
        val body = nsf.copyOfRange(128, nsf.size)
        assertContentEquals(NesVrc6PlayerImage.BYTES, body.copyOfRange(0, NesVrc6PlayerImage.BYTES.size))
        val lowered = FamiStudioLowering.lower(song)
        val songAt = NesVrc6PlayerImage.SONG_BASE - NesVrc6PlayerImage.LOAD
        assertContentEquals(lowered.data, body.copyOfRange(songAt, songAt + lowered.data.size))
        assertContentEquals(lowered.samples, body.copyOfRange(lowered.sampleStart - NesVrc6PlayerImage.LOAD, body.size))
        // The NSF's song plays like the lowered data, VRC6 writes included.
        val a = NesTracer(lowered.data, NesPlayer.VRC6)
        val b = NesTracer(body.copyOfRange(songAt, lowered.sampleStart - NesVrc6PlayerImage.LOAD), NesPlayer.VRC6)
        var vrc6 = 0
        repeat(600) {
            val f = a.frame()
            assertContentEquals(f, b.frame())
            vrc6 += f.count { (it shr 8) >= 0x9000 }
        }
        assertTrue(vrc6 > 600, "the demo writes the VRC6 ($vrc6 writes in 600 frames)")
        val text = FamiStudioText.text(song)
        val lines = text.lines()
        assertTrue(lines[0].endsWith("Expansions=\"VRC6\""), lines[0])
        assertTrue(lines.any { it.trim().startsWith("Instrument Name=\"07 Saw lead (Full)\" Expansion=\"VRC6\" Vrc6SawMasterVolume=\"Full\"") })
        assertTrue(lines.any { it.trim() == "Instrument Name=\"01 Echo 25%\"" })
        assertEquals(listOf("Square1", "Square2", "Triangle", "Noise", "DPCM", "VRC6Square1", "VRC6Square2", "VRC6Saw"),
            lines.filter { it.trim().startsWith("Channel") }.map { it.substringAfter("Type=\"").substringBefore('"') })
        // 2A03 songs name no expansion.
        assertFalse(FamiStudioText.text(NesSystem.demo()).lines()[0].contains("Expansions"))
    }

    @Test
    fun vrc6ValidationKeepsSoundsOnTheirChip() {
        val base = NesVrc6System.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[1] = NesInstrument("Saw", chip = NesChip.VRC6, duty = NesEnvelope(listOf(7)))
        val blank = base.copy(sound = base.nes.copy(instruments = bank))
        fun errors(ch: Int, cell: Cell) = NesVrc6System.errors(withCell(blank, ch, 0, cell)).joinToString()
        assertEquals("", errors(NesRules.VRC6_PULSE1, Cell(57, 2)))
        assertEquals("", errors(NesRules.VRC6_SAW, Cell(12, 2))) // the VRC6 reaches C-1
        assertTrue(errors(NesRules.VRC6_PULSE1, Cell(57, 1)).contains("2A03 sound"))
        assertTrue(errors(0, Cell(57, 2)).contains("VRC6 sound"))
        assertEquals("", errors(NesRules.VRC6_PULSE2, Cell(57, 2, NesRules.FX_DUTY, 0x07)))
        assertTrue(errors(NesRules.VRC6_PULSE2, Cell(57, 2, NesRules.FX_DUTY, 0x08)).contains("V00-V07"))
        assertTrue(errors(NesRules.VRC6_SAW, Cell(57, 2, NesRules.FX_DUTY, 0x01)).contains("VRC6 Saw"))
        assertTrue(errors(0, Cell(57, 1, NesRules.FX_DUTY, 0x07)).contains("V00-V03"))
        // Structure: a VRC6 sound in a 2A03 song, a 2A03 duty of 7, DPCM keys on a VRC6 sound.
        val plain = NesSystem.blank()
        val plainBank = plain.nes.instruments.toMutableList()
        plainBank[0] = NesInstrument("Stray", chip = NesChip.VRC6)
        plainBank[1] = NesInstrument("Wide", duty = NesEnvelope(listOf(7)))
        val e = NesSystem.structuralErrors(plain.copy(sound = plain.nes.copy(instruments = plainBank))).joinToString()
        assertTrue(e.contains("this song has no VRC6 chip"), e)
        assertTrue(e.contains("duty value 7 is outside 0..3"), e)
        val keyed = bank.toMutableList().also { it[1] = it[1].copy(dpcm = mapOf(48 to org.thortracker.system.nes.DpcmMapping(0))) }
        assertTrue(NesVrc6System.structuralErrors(base.copy(sound = base.nes.copy(instruments = keyed))).joinToString().contains("only 2A03 sounds map DPCM"))
        // Each variant's songs only: a VRC6 song is not a plain NES song.
        assertTrue(NesSystem.structuralErrors(blank).single().contains("NES + VRC6"))
    }

    /**
     * NES + MMC5: the `.nsf` carries the MMC5 player and sets `$7B` bit 3; the text names the expansion and its two
     * square channels, and its instruments (2A03 sounds) carry no expansion.
     */
    @Test
    fun mmc5ExportsNameTheChip() {
        val song = NesMmc5System.demo()
        assertEquals(emptyList(), NesMmc5System.errors(song))
        assertEquals(emptyList(), NesMmc5System.warnings(song))
        val nsf = NesMmc5System.exports.first { it.extension == "nsf" }.export(song)
        assertEquals(NesMmc5PlayerImage.LOAD, u16(nsf, 8))
        assertEquals(NesMmc5PlayerImage.INIT, u16(nsf, 10))
        assertEquals(NesMmc5PlayerImage.PLAY, u16(nsf, 12))
        assertEquals(0x08, nsf[0x7b].toInt()) // expansion chips: MMC5
        val body = nsf.copyOfRange(128, nsf.size)
        assertContentEquals(NesMmc5PlayerImage.BYTES, body.copyOfRange(0, NesMmc5PlayerImage.BYTES.size))
        val lowered = FamiStudioLowering.lower(song)
        assertEquals(NesPlayer.MMC5, lowered.player)
        val songAt = NesMmc5PlayerImage.SONG_BASE - NesMmc5PlayerImage.LOAD
        assertContentEquals(lowered.data, body.copyOfRange(songAt, songAt + lowered.data.size))
        assertContentEquals(lowered.samples, body.copyOfRange(lowered.sampleStart - NesMmc5PlayerImage.LOAD, body.size))
        // The NSF's song plays like the lowered data, MMC5 writes included.
        val a = NesTracer(lowered.data, NesPlayer.MMC5)
        val b = NesTracer(body.copyOfRange(songAt, lowered.sampleStart - NesMmc5PlayerImage.LOAD), NesPlayer.MMC5)
        var mmc5 = 0
        repeat(600) {
            val f = a.frame()
            assertContentEquals(f, b.frame())
            mmc5 += f.count { (it shr 8) in 0x5000..0x5015 }
        }
        assertTrue(mmc5 > 600, "the demo writes the MMC5 ($mmc5 writes in 600 frames)")
        val lines = FamiStudioText.text(song).lines()
        assertTrue(lines[0].endsWith("Expansions=\"MMC5\""), lines[0])
        assertTrue(lines.any { it.trim() == "Instrument Name=\"07 MMC5 lead 50>25%\"" })
        assertFalse(lines.any { it.contains("Expansion=") })
        assertEquals(listOf("Square1", "Square2", "Triangle", "Noise", "DPCM", "MMC5Square1", "MMC5Square2"),
            lines.filter { it.trim().startsWith("Channel") }.map { it.substringAfter("Type=\"").substringBefore('"') })
        // At speed 6 the demo plays at least 30 seconds before it loops.
        val frames = song.orders.sumOf { song.patterns[it].length } * song.ticksPerRow
        assertTrue(frames / NesMmc5System.tickHz >= 30.0, "${frames / NesMmc5System.tickHz} s")
    }

    /** MMC5 pulses: 2A03 sounds, A-1 as their lowest note, duties 00-03, no DPCM-only effects; one chip in the bank. */
    @Test
    fun mmc5ValidationTreatsItsPulsesAsPulses() {
        val blank = NesMmc5System.blank()
        fun errors(ch: Int, cell: Cell) = NesMmc5System.errors(withCell(blank, ch, 0, cell)).joinToString()
        assertEquals("", errors(NesRules.MMC5_PULSE1, Cell(57, 1)))
        assertEquals("", errors(NesRules.MMC5_PULSE2, Cell(NesRules.LOWEST_TONAL, 1))) // A-1
        assertTrue(errors(NesRules.MMC5_PULSE1, Cell(NesRules.LOWEST_TONAL - 1, 1)).contains("MMC5 Pulse 1 cannot play below A-1"))
        assertTrue(errors(NesRules.MMC5_PULSE2, Cell(30, 1, NesRules.FX_SLIDE_DOWN, 0x0A)).contains("below A-1, which MMC5 Pulse 2 cannot play"))
        assertEquals("", errors(NesRules.MMC5_PULSE1, Cell(57, 1, NesRules.FX_DUTY, 0x03)))
        assertTrue(errors(NesRules.MMC5_PULSE1, Cell(57, 1, NesRules.FX_DUTY, 0x04)).contains("V00-V03"))
        assertTrue(errors(NesRules.MMC5_PULSE2, Cell(57, 1, NesRules.FX_DELTA_COUNTER, 0x10)).contains("has no effect on MMC5 Pulse 2"))
        for (fx in listOf(NesRules.FX_ARPEGGIO to 0x37, NesRules.FX_VIBRATO to 0x44, NesRules.FX_VOLUME_SLIDE to 0x3F, NesRules.FX_VOLUME to 0x08,
            NesRules.FX_FINE_PITCH to 0x84, NesRules.FX_SLIDE_UP to 0x02, NesRules.FX_NOTE_CUT to 0x02)) {
            assertEquals("", errors(NesRules.MMC5_PULSE1, Cell(57, 1, fx.first, fx.second)), "${fx.first}")
        }
        // Every 2A03 pulse effect, and the MMC5 duty help names the phase reset.
        assertEquals(NesSystem.effectsFor(0).map { it.code }, NesMmc5System.effectsFor(NesRules.MMC5_PULSE1).map { it.code })
        assertTrue(NesMmc5System.effectsFor(NesRules.MMC5_PULSE2).single { it.code == NesRules.FX_DUTY }.description.contains("phase reset"))
        assertEquals("Duty 75%", NesMmc5System.describeEffect(blank, NesRules.MMC5_PULSE1, NesRules.FX_DUTY, 3))
        // MMC5 songs hold 2A03 sounds only, and are not plain NES or VRC6 songs.
        val bank = blank.nes.instruments.toMutableList().also { it[0] = NesInstrument("Stray", chip = NesChip.VRC6) }
        assertTrue(NesMmc5System.structuralErrors(blank.copy(sound = blank.nes.copy(instruments = bank))).joinToString().contains("this song has no VRC6 chip"))
        assertTrue(NesSystem.structuralErrors(blank).single().contains("NES + MMC5"))
        assertTrue(NesVrc6System.structuralErrors(blank).single().contains("NES + MMC5"))
        // The shortest song the WAV test renders: one one-row order at speed 1, a held note on MMC5 Pulse 1.
        val shortest = NesMmc5DemoSong.shortest()
        assertEquals(emptyList(), NesMmc5System.errors(shortest))
        assertEquals(1, shortest.patterns.single().length)
        assertEquals(1, shortest.ticksPerRow)
        assertEquals(57, shortest.patterns[0].tracks[NesRules.MMC5_PULSE1][0].note)
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
