package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.NesVrc7System
import org.thortracker.system.nes.Vrc7Patch
import org.thortracker.system.nes.Vrc7Presets
import org.thortracker.system.nes.Vrc7Sustain
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

    /** NES + VRC7: the `.nsf` carries the VRC7 player and names the chip; the text names the expansion and the patches. */
    @Test
    fun vrc7ExportsNameTheChipAndPatches() {
        val song = NesVrc7System.demo()
        assertEquals(emptyList(), NesVrc7System.errors(song))
        assertEquals(emptyList(), NesVrc7System.warnings(song))
        val nsf = NesVrc7System.exports.first { it.extension == "nsf" }.export(song)
        assertEquals(NesVrc7PlayerImage.LOAD, u16(nsf, 8))
        assertEquals(NesVrc7PlayerImage.INIT, u16(nsf, 10))
        assertEquals(NesVrc7PlayerImage.PLAY, u16(nsf, 12))
        assertEquals(2, nsf[0x7b].toInt()) // expansion chips: VRC7
        val body = nsf.copyOfRange(128, nsf.size)
        assertContentEquals(NesVrc7PlayerImage.BYTES, body.copyOfRange(0, NesVrc7PlayerImage.BYTES.size))
        val lowered = FamiStudioLowering.lower(song)
        val songAt = NesVrc7PlayerImage.SONG_BASE - NesVrc7PlayerImage.LOAD
        assertContentEquals(lowered.data, body.copyOfRange(songAt, songAt + lowered.data.size))
        // The NSF's song plays like the lowered data, FM register writes ($9010 select, $9030 data) included.
        val a = NesTracer(lowered.data, NesPlayer.VRC7)
        val b = NesTracer(body.copyOfRange(songAt, lowered.sampleStart - NesVrc7PlayerImage.LOAD), NesPlayer.VRC7)
        var fm = 0
        repeat(600) {
            val f = a.frame()
            assertContentEquals(f, b.frame())
            fm += f.count { (it shr 8) == 0x9030 }
        }
        assertTrue(fm > 600, "the demo writes the VRC7 ($fm data writes in 600 frames)")
        val lines = FamiStudioText.text(song).lines()
        assertTrue(lines[0].endsWith("Expansions=\"VRC7\""), lines[0])
        // The custom patch carries its eight registers; a built-in patch only its number.
        assertTrue(lines.any {
            it.trim() == "Instrument Name=\"07 Soft lead (custom)\" Expansion=\"VRC7\" Vrc7Patch=\"0\" Vrc7Reg0=\"49\" Vrc7Reg1=\"97\" " +
                "Vrc7Reg2=\"20\" Vrc7Reg3=\"5\" Vrc7Reg4=\"168\" Vrc7Reg5=\"100\" Vrc7Reg6=\"97\" Vrc7Reg7=\"39\" " +
                "Vrc7OverrideRelease=\"False\" Vrc7OverrideStop=\"False\""
        })
        assertTrue(lines.any { it.trim() == "Instrument Name=\"08 Bass guitar\" Expansion=\"VRC7\" Vrc7Patch=\"13\" Vrc7OverrideRelease=\"False\" Vrc7OverrideStop=\"False\"" })
        assertEquals(listOf("Square1", "Square2", "Triangle", "Noise", "DPCM") + (1..6).map { "VRC7FM$it" },
            lines.filter { it.trim().startsWith("Channel") }.map { it.substringAfter("Type=\"").substringBefore('"') })
        // The song's sustain overrides go on every VRC7 instrument (FamiStudio's engine keeps the last loaded one's).
        val sustained = song.copy(sound = song.nes.copy(vrc7Sustain = Vrc7Sustain(release = true)))
        val fmLines = FamiStudioText.text(sustained).lines().filter { "Expansion=\"VRC7\"" in it }
        assertEquals(5, fmLines.size)
        assertTrue(fmLines.all { it.endsWith("Vrc7OverrideRelease=\"True\" Vrc7OverrideStop=\"False\"") }, fmLines.toString())
    }

    @Test
    fun vrc7ValidationKeepsSoundsAndPatchesConsistent() {
        val base = NesVrc7System.blank()
        val fm = NesRules.VRC7_FM1
        val lead = Vrc7Patch(Vrc7Presets.CUSTOM, listOf(0x21, 0x61, 0x1A, 0x07, 0xF0, 0x80, 0x0F, 0x26))
        val bank = base.nes.instruments.toMutableList()
        bank[1] = NesInstrument("Piano", chip = NesChip.VRC7, fm = Vrc7Patch().withPreset(3))
        bank[2] = NesInstrument("Lead", chip = NesChip.VRC7, fm = lead)
        bank[3] = NesInstrument("Other", chip = NesChip.VRC7, fm = lead.copy(regs = lead.regs.mapIndexed { i, r -> if (i == 0) r xor 1 else r }))
        bank[4] = NesInstrument("Lead copy", chip = NesChip.VRC7, fm = lead)
        val blank = base.copy(sound = base.nes.copy(instruments = bank))
        fun errors(ch: Int, cell: Cell) = NesVrc7System.errors(withCell(blank, ch, 0, cell)).joinToString()
        assertEquals("", errors(fm, Cell(12, 2))) // C-1: the FM channels reach every note
        assertEquals("", errors(fm + 5, Cell(57, 2, NesRules.FX_FINE_PITCH, 0x90)))
        assertTrue(errors(fm, Cell(57, 1)).contains("2A03 sound"))
        assertTrue(errors(0, Cell(57, 2)).contains("VRC7 sound"))
        assertTrue(errors(fm, Cell(57, 2, NesRules.FX_DUTY, 0x01)).contains("VRC7 FM 1"))
        // Structure: a built-in patch keeps its ROM registers, VRC7 sounds have no duty, sustain only on VRC7 songs.
        val odd = bank.toMutableList().also {
            it[1] = it[1].copy(fm = Vrc7Patch(3, Vrc7Presets.data(4)))
            it[5] = NesInstrument("Duty", chip = NesChip.VRC7, duty = NesEnvelope(listOf(1)))
        }
        val e = NesVrc7System.structuralErrors(base.copy(sound = base.nes.copy(instruments = odd))).joinToString()
        assertTrue(e.contains("keeps its ROM registers"), e)
        assertTrue(e.contains("no duty envelope"), e)
        val plain = NesSystem.blank()
        assertTrue(NesSystem.structuralErrors(plain.copy(sound = plain.nes.copy(vrc7Sustain = Vrc7Sustain(stop = true)))).joinToString().contains("Only VRC7 songs"))
        // The chip's one custom patch: another custom sound starting while FM 1 holds one changes it; warned once. A
        // sound with the same registers, or after FM 1's note ended, is not a conflict.
        fun conflicts(second: Int, offRow: Int = -1): List<String> {
            val p = blank.patterns[0]
            val tracks = p.tracks.mapIndexed { ch, t ->
                t.toMutableList().also {
                    if (ch == fm) { it[0] = Cell(57, 3); if (offRow >= 0) it[offRow] = Cell(Cell.CUT, 0) }
                    if (ch == fm + 1) { it[4] = Cell(60, second); it[12] = Cell(62, second) }
                }
            }
            return NesVrc7System.warnings(blank.copy(patterns = listOf(p.copy(tracks = tracks)))).filter { "custom patch" in it }
        }
        assertEquals(1, conflicts(4).size, conflicts(4).toString())
        assertTrue(conflicts(4).single().contains("VRC7 FM 1 still holds custom sound 03"), conflicts(4).toString())
        assertEquals(emptyList(), conflicts(5))
        assertEquals(emptyList(), conflicts(4, offRow = 2))
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
