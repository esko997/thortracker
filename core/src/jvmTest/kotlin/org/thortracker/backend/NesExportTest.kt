package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.EpsmPatch
import org.thortracker.system.nes.NesEpsmSystem
import org.thortracker.system.nes.NesFdsSystem
import org.thortracker.system.nes.NesMmc5DemoSong
import org.thortracker.system.nes.NesMmc5System
import org.thortracker.system.nes.NesSound
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.NesS5bSystem
import org.thortracker.system.nes.NesVrc7System
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.nes.Vrc7Patch
import org.thortracker.system.nes.Vrc7Presets
import org.thortracker.system.nes.Vrc7Sustain
import org.thortracker.system.nes.nes
import kotlin.test.assertFailsWith
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

    /** NES + FDS: the `.nsf` carries the FDS player and names the chip ($7B bit 2); the text names the expansion. */
    @Test
    fun fdsExportsNameTheChip() {
        val song = NesFdsSystem.demo()
        assertEquals(emptyList(), NesFdsSystem.errors(song))
        val nsf = NesFdsSystem.exports.first { it.extension == "nsf" }.export(song)
        assertEquals(NesFdsPlayerImage.LOAD, u16(nsf, 8))
        assertEquals(NesFdsPlayerImage.INIT, u16(nsf, 10))
        assertEquals(NesFdsPlayerImage.PLAY, u16(nsf, 12))
        assertEquals("Side B", nsf.copyOfRange(0x0e, 0x0e + 6).decodeToString())
        assertEquals(4, nsf[0x7b].toInt()) // expansion chips: FDS
        val body = nsf.copyOfRange(128, nsf.size)
        assertContentEquals(NesFdsPlayerImage.BYTES, body.copyOfRange(0, NesFdsPlayerImage.BYTES.size))
        val lowered = FamiStudioLowering.lower(song)
        val songAt = NesFdsPlayerImage.SONG_BASE - NesFdsPlayerImage.LOAD
        assertContentEquals(lowered.data, body.copyOfRange(songAt, songAt + lowered.data.size))
        assertContentEquals(lowered.samples, body.copyOfRange(lowered.sampleStart - NesFdsPlayerImage.LOAD, body.size))
        // The NSF's song plays like the lowered data, FDS writes included (volume, frequency, modulation every frame).
        val a = NesTracer(lowered.data, NesPlayer.FDS)
        val b = NesTracer(body.copyOfRange(songAt, lowered.sampleStart - NesFdsPlayerImage.LOAD), NesPlayer.FDS)
        var fds = 0
        repeat(600) {
            val f = a.frame()
            assertContentEquals(f, b.frame())
            fds += f.count { (it shr 8) in 0x4040..0x408A }
        }
        assertTrue(fds > 600, "the demo writes the FDS ($fds writes in 600 frames)")
        val lines = FamiStudioText.text(song).lines()
        assertTrue(lines[0].endsWith("Expansions=\"FDS\""), lines[0])
        assertTrue(lines.any { it.trim().startsWith("Instrument Name=\"09 FM bass\" Expansion=\"FDS\" FdsWaveCount=\"1\" FdsWavePreset=\"Sine\"") })
        assertTrue(lines.any { it.trim().startsWith("Instrument Name=\"0A Vibrato lead\" Expansion=\"FDS\" FdsWaveCount=\"1\" FdsWavePreset=\"Custom\"") })
        assertEquals(listOf("Square1", "Square2", "Triangle", "Noise", "DPCM", "FDS"),
            lines.filter { it.trim().startsWith("Channel") }.map { it.substringAfter("Type=\"").substringBefore('"') })
        assertEquals(emptyList(), NesFdsSystem.warnings(song))
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

    /**
     * NES + Sunsoft 5B: the `.nsf` carries the 5B player and names the chip (bit 5), plays like the lowered data with the
     * 5B's register writes; the text names the expansion, its squares and the sounds' 5B settings; 5B-only settings stay
     * on 5B sounds.
     */
    @Test
    fun s5bExportsNameTheChipAndKeepSettingsOnItsSounds() {
        val song = NesS5bSystem.demo()
        assertEquals(emptyList(), NesS5bSystem.errors(song))
        val nsf = NesS5bSystem.exports.first { it.extension == "nsf" }.export(song)
        assertEquals(NesS5bPlayerImage.LOAD, u16(nsf, 8))
        assertEquals(NesS5bPlayerImage.INIT, u16(nsf, 10))
        assertEquals(NesS5bPlayerImage.PLAY, u16(nsf, 12))
        assertEquals(0x20, nsf[0x7b].toInt()) // expansion chips: Sunsoft 5B
        val body = nsf.copyOfRange(128, nsf.size)
        assertContentEquals(NesS5bPlayerImage.BYTES, body.copyOfRange(0, NesS5bPlayerImage.BYTES.size))
        val lowered = FamiStudioLowering.lower(song)
        val songAt = NesS5bPlayerImage.SONG_BASE - NesS5bPlayerImage.LOAD
        assertContentEquals(lowered.data, body.copyOfRange(songAt, songAt + lowered.data.size))
        val end = if (lowered.samples.isEmpty()) songAt + lowered.data.size else lowered.sampleStart - NesS5bPlayerImage.LOAD
        val a = NesTracer(lowered.data, NesPlayer.S5B)
        val b = NesTracer(body.copyOfRange(songAt, end), NesPlayer.S5B)
        var s5b = 0
        repeat(600) {
            val f = a.frame()
            assertContentEquals(f, b.frame())
            s5b += f.count { (it shr 8) >= 0xC000 }
        }
        assertTrue(s5b > 600, "the demo writes the 5B ($s5b register writes in 600 frames)")
        val lines = FamiStudioText.text(song).lines()
        assertTrue(lines[0].endsWith("Expansions=\"S5B\""), lines[0])
        assertEquals(listOf("Square1", "Square2", "Triangle", "Noise", "DPCM") + (1..3).map { "S5BSquare$it" },
            lines.filter { it.trim().startsWith("Channel") }.map { it.substringAfter("Type=\"").substringBefore('"') })
        assertTrue(lines.any { "Expansion=\"S5B\" S5BEnvelopeShape=" in it })
        // 5B settings belong to 5B sounds: a 5B sound has no duty, a 2A03 sound no mixer, noise or hardware envelope.
        val base = NesS5bSystem.blank()
        fun errorsWith(ins: NesInstrument): List<String> {
            val bank = base.nes.instruments.toMutableList().also { it[0] = ins }
            return NesS5bSystem.structuralErrors(base.copy(sound = base.nes.copy(instruments = bank)))
        }
        assertTrue(errorsWith(NesInstrument("D", chip = NesChip.S5B, duty = NesEnvelope(listOf(1)))).isNotEmpty())
        assertTrue(errorsWith(NesInstrument("M", mixer = NesEnvelope(listOf(0)))).isNotEmpty())
        assertTrue(errorsWith(NesInstrument("E", s5b = S5bEnvelope(S5bShape.DECAY))).isNotEmpty())
        assertTrue(errorsWith(NesInstrument("N", chip = NesChip.S5B, noise = NesEnvelope(listOf(32)))).isNotEmpty())
        assertEquals(emptyList(), errorsWith(NesInstrument("Ok", chip = NesChip.S5B, mixer = NesEnvelope(listOf(0, 1, 2, 3)), noise = NesEnvelope(listOf(31)))))
    }

    /** NES + EPSM: the `.nsf` carries the EPSM player and names the chip (`$7B` bit 7); the text names the expansion. */
    @Test
    fun epsmExportsNameTheChip() {
        val song = NesEpsmSystem.demo()
        assertEquals(emptyList(), NesEpsmSystem.errors(song))
        assertEquals(emptyList(), NesEpsmSystem.warnings(song))
        val nsf = NesEpsmSystem.exports.first { it.extension == "nsf" }.export(song)
        assertEquals(NesEpsmPlayerImage.LOAD, u16(nsf, 8))
        assertEquals(NesEpsmPlayerImage.INIT, u16(nsf, 10))
        assertEquals(NesEpsmPlayerImage.PLAY, u16(nsf, 12))
        assertEquals(0x80, nsf[0x7b].toInt() and 0xff) // expansion chips: EPSM
        val body = nsf.copyOfRange(128, nsf.size)
        assertContentEquals(NesEpsmPlayerImage.BYTES, body.copyOfRange(0, NesEpsmPlayerImage.BYTES.size))
        val lowered = FamiStudioLowering.lower(song)
        val songAt = NesEpsmPlayerImage.SONG_BASE - NesEpsmPlayerImage.LOAD
        assertContentEquals(lowered.data, body.copyOfRange(songAt, songAt + lowered.data.size))
        val end = if (lowered.samples.isEmpty()) songAt + lowered.data.size else lowered.sampleStart - NesEpsmPlayerImage.LOAD
        // The NSF's song plays like the lowered data, YMF288 writes included.
        val a = NesTracer(lowered.data, NesPlayer.EPSM)
        val b = NesTracer(body.copyOfRange(songAt, end), NesPlayer.EPSM)
        var epsm = 0
        repeat(600) {
            val f = a.frame()
            assertContentEquals(f, b.frame())
            epsm += f.count { (it shr 8) in NesTracer.EPSM_PORTS }
        }
        assertTrue(epsm > 600, "the demo writes the EPSM ($epsm writes in 600 frames)")
        val lines = FamiStudioText.text(song).lines()
        assertTrue(lines[0].endsWith("Expansions=\"EPSM\""), lines[0])
        assertEquals(listOf("Square1", "Square2", "Triangle", "Noise", "DPCM") + (1..3).map { "EPSMSquare$it" } +
            (1..6).map { "EPSMFM$it" } + (1..6).map { "EPSMRythm$it" },
            lines.filter { it.trim().startsWith("Channel") }.map { it.substringAfter("Type=\"").substringBefore('"') })
    }

    /**
     * The EPSM text: sounds as FamiStudio's Custom patch with all 31 registers (30 = the song's LFO), the SSG envelope
     * settings, mixer and noise envelopes; SSG and FM notes an octave above the 2A03's byte for the same pitch; the SSG
     * envelope period on SSG 1's notes. A manual period other than 4000 cannot be written (FamiStudio's text has no
     * attribute for it): the text export refuses rather than silently playing 4000; `.nsf` keeps the period.
     */
    @Test
    fun epsmTextCarriesSoundsAndRefusesManualPeriods() {
        val song = NesGoldenSongs.epsmEffects()
        val text = FamiStudioText.text(song)
        val lfo = song.nes.epsmLfo
        val bass = song.nes.instruments[6]
        val regs = bass.epsm.registers(lfo).withIndex().joinToString("") { (i, r) -> " EpsmReg$i=\"$r\"" }
        assertTrue(text.contains("Instrument Name=\"07 FM bass\" Expansion=\"EPSM\" EpsmPatch=\"0\"$regs EPSMSquareEnvelopeShape=\"0\" " +
            "EPSMSquareEnvelopeAutoPitch=\"True\" EPSMSquareEnvelopeAutoPitchOctave=\"3\""), text)
        assertTrue(text.contains("EpsmReg30=\"$lfo\""))
        assertTrue(text.contains("Name=\"05 SSG env manual\" Expansion=\"EPSM\"") && text.contains("EPSMSquareEnvelopeShape=\"3\""))
        assertTrue(text.contains("Envelope Type=\"MixerSettings\" Length=\"1\" Values=\"1\""), text)
        assertTrue(text.contains("Envelope Type=\"NoiseFreq\" Length=\"3\" Values=\"4,8,12\""), text)
        assertTrue(text.contains("EnvelopePeriod=\"64\"") && text.contains("EnvelopePeriod=\"640\"") && text.contains("EnvelopePeriod=\"2560\""))
        // EPSM sounds carry no duty envelope (FamiStudio's EPSM instruments have none): only the 2A03 lead's.
        assertEquals(1, text.lines().count { it.trim().startsWith("Envelope Type=\"DutyCycle\"") })
        // SSG 1's first note C-4 is FamiStudio's 49 ("C4"); the 2A03's C-5 is 49 too.
        val ssg1 = text.substringAfter("Channel Type=\"EPSMSquare1\"").substringBefore("Channel Type=")
        assertTrue(ssg1.contains("Note Time=\"0\" Value=\"C4\""), ssg1)
        val fs = FsSong.of(song)
        assertEquals(49, fs.notes[NesRules.EPSM_SSG1][0]!!.value)
        assertEquals(60 - NesRules.NOTE_OFFSET, fs.notes[0][0]!!.value)
        // Drum OFF is a stop; FM OFF a release.
        assertEquals(FsNote.RELEASE, fs.notes[NesRules.EPSM_FM1][12]!!.value)
        assertEquals(FsNote.STOP, fs.notes[NesRules.EPSM_FM1][14]!!.value)
        val rhythm = FsSong.of(NesGoldenSongs.epsmRhythm())
        assertEquals(FsNote.STOP, rhythm.notes[NesRules.EPSM_RHYTHM1 + 4][40]!!.value)
        // A manual period of 3000: no text (and the check warns first); the .nsf keeps the period in the sound's
        // expansion instrument entry (bytes 12-13).
        val bank = song.nes.instruments.toMutableList()
        bank[4] = bank[4].copy(s5b = bank[4].s5b.copy(period = 3000))
        val manual = song.copy(sound = song.nes.copy(instruments = bank))
        assertEquals(emptyList(), NesEpsmSystem.errors(manual))
        val e = assertFailsWith<IllegalArgumentException> { FamiStudioText.text(manual) }
        assertTrue(e.message!!.contains("Sound 05") && e.message!!.contains("3000") && e.message!!.contains("4000"), e.message)
        assertTrue(NesEpsmSystem.warnings(manual).any { "3000" in it && "4000" in it }, NesEpsmSystem.warnings(manual).toString())
        val nsf = NsfExporter.export(manual)
        val data = FamiStudioLowering.lower(manual).data
        val songAt = 128 + NesEpsmPlayerImage.SONG_BASE - NesEpsmPlayerImage.LOAD
        assertContentEquals(data, nsf.copyOfRange(songAt, songAt + data.size))
        val expTable = u16(data, 3) - NesEpsmPlayerImage.SONG_BASE
        assertEquals(3000, u16(data, expTable + 4 * FamiStudioLowering.EXP_INSTRUMENT_SIZE + 12))
        assertEquals(0x80, data[expTable + 4 * FamiStudioLowering.EXP_INSTRUMENT_SIZE + 11].toInt() and 0xff) // manual period
        // An auto-period sound's period does not matter there.
        bank[4] = bank[4].copy(s5b = bank[4].s5b.copy(autoPitch = true))
        FamiStudioText.text(song.copy(sound = song.nes.copy(instruments = bank)))
    }

    /** EPSM cell rules, per channel group: chips, ranges, effects (FamiStudio's `Channel.SupportsEffect`), envelope periods. */
    @Test
    fun epsmValidationKeepsSoundsAndEffectsOnTheirChannels() {
        val base = NesEpsmSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[1] = NesInstrument("EPSM", chip = NesChip.EPSM)
        val blank = base.copy(sound = base.nes.copy(instruments = bank))
        fun errors(ch: Int, cell: Cell) = NesEpsmSystem.errors(withCell(blank, ch, 0, cell)).joinToString()
        val ssg = NesRules.EPSM_SSG1
        val fm = NesRules.EPSM_FM1
        val kick = NesRules.EPSM_RHYTHM1
        assertEquals("", errors(ssg, Cell(12, 2))) // C-1
        assertEquals("", errors(fm, Cell(95, 2))) // B-7
        assertTrue(errors(fm, Cell(96, 2)).contains("cannot play above B-7"))
        assertTrue(errors(ssg + 2, Cell(107, 2)).contains("B-7"))
        assertEquals("", errors(kick, Cell(107, 2))) // drums: any note
        assertTrue(errors(fm, Cell(57, 1)).contains("2A03 sound"))
        assertTrue(errors(0, Cell(57, 2)).contains("EPSM sound"))
        assertTrue(errors(ssg, Cell(57, 2, NesRules.FX_DUTY, 0x01)).contains("no effect on SSG 1"))
        assertTrue(errors(fm, Cell(57, 2, NesRules.FX_ENV_PERIOD_HI, 0x01)).contains("no effect on FM 1"))
        assertEquals("", errors(ssg + 1, Cell(57, 2, NesRules.FX_ENV_PERIOD_LO, 0x20)))
        assertEquals("", errors(fm, Cell(57, 2, NesRules.FX_FINE_PITCH, 0x90)))
        for ((fx, param) in listOf(NesRules.FX_VOLUME to 0x08, NesRules.FX_VOLUME_SLIDE to 0xF2, NesRules.FX_SPEED to 0x04,
            NesRules.FX_NOTE_DELAY to 0x02, NesRules.FX_NOTE_CUT to 0x02)) assertEquals("", errors(kick, Cell(57, 2, fx, param)), "drum ${NesRules.effectText(fx, param)}")
        for ((fx, param) in listOf(NesRules.FX_ARPEGGIO to 0x37, NesRules.FX_VIBRATO to 0x44, NesRules.FX_FINE_PITCH to 0x90,
            NesRules.FX_SLIDE_UP to 0x02, NesRules.FX_SLIDE_DOWN to 0x02, NesRules.FX_DUTY to 0x01, NesRules.FX_ENV_PERIOD_HI to 0x01)) {
            assertTrue(errors(kick, Cell(57, 2, fx, param)).contains("fixed-pitch drum"), "drum ${NesRules.effectText(fx, param)}")
        }
        assertEquals(listOf("Volume slide", "Volume", "Speed", "Note delay", "Note cut"), NesEpsmSystem.effectsFor(kick).map { it.name })
        assertTrue(NesEpsmSystem.effectsFor(ssg).any { it.code == NesRules.FX_ENV_PERIOD_HI } && NesEpsmSystem.effectsFor(fm).none { it.code == NesRules.FX_ENV_PERIOD_HI })
        assertTrue(NesEpsmSystem.effectsFor(fm).none { it.code == NesRules.FX_DUTY })
        // Two different high bytes on one row of two squares are ambiguous.
        val p = blank.patterns[0]
        val clash = blank.copy(patterns = listOf(p.copy(tracks = p.tracks.mapIndexed { c, t ->
            when (c) {
                ssg -> t.toMutableList().also { it[0] = Cell(Cell.EMPTY, 0, NesRules.FX_ENV_PERIOD_HI, 0x12) }
                ssg + 2 -> t.toMutableList().also { it[0] = Cell(Cell.EMPTY, 0, NesRules.FX_ENV_PERIOD_HI, 0x13) }
                else -> t
            }
        })))
        assertTrue(NesEpsmSystem.errors(clash).any { "I12 and I13" in it }, NesEpsmSystem.errors(clash).toString())
        // Structure: SSG and EPSM fields on other chips, a duty envelope on an EPSM sound, an impossible patch or LFO.
        val sbank = bank.toMutableList()
        sbank[2] = NesInstrument("Stray mixer", mixer = NesEnvelope(listOf(1)))
        sbank[3] = NesInstrument("Stray patch", epsm = EpsmPatch(algorithm = 1))
        sbank[4] = NesInstrument("Duty", chip = NesChip.EPSM, duty = NesEnvelope(listOf(1)))
        sbank[5] = NesInstrument("Bad", chip = NesChip.EPSM, epsm = EpsmPatch(algorithm = 9), s5b = S5bEnvelope(S5bShape.DECAY, octave = 9))
        val s = NesEpsmSystem.structuralErrors(blank.copy(sound = blank.nes.copy(instruments = sbank, epsmLfo = 5))).joinToString()
        for (want in listOf("only 5B and EPSM sounds have mixer and noise", "only EPSM sounds have an EPSM FM patch",
            "EPSM sounds have no duty envelope", "FM algorithm 9", "SSG envelope octave 9", "not off (0) or on with a rate")) {
            assertTrue(s.contains(want), "$want: $s")
        }
        assertTrue(NesVrc6System.structuralErrors(NesVrc6System.blank().let { it.copy(sound = it.nes.copy(epsmLfo = 0x08)) }).joinToString().contains("Only EPSM songs"))
        // An EPSM sound is not a plain NES song's.
        assertTrue(NesSystem.structuralErrors(blank).single().contains("NES + EPSM"))
    }

    /** Shared EPSM hardware: the SSG's one envelope and noise generators, the one LFO, the drums' one volume. */
    @Test
    fun epsmWarningsPointAtSharedHardware() {
        val base = NesEpsmSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[0] = NesInstrument("Saw env", chip = NesChip.EPSM, s5b = S5bEnvelope(S5bShape.SAW_DOWN, autoPitch = false, period = 4000))
        bank[1] = NesInstrument("Tri env", chip = NesChip.EPSM, s5b = S5bEnvelope(S5bShape.TRIANGLE, autoPitch = false, period = 900))
        bank[2] = NesInstrument("Wobble", chip = NesChip.EPSM, epsm = EpsmPatch(pms = 3))
        bank[3] = NesInstrument("Drum steps", chip = NesChip.EPSM, volume = NesEnvelope(listOf(15, 10, 5)))
        val p = base.patterns[0]
        val song = base.copy(patterns = listOf(p.copy(tracks = p.tracks.mapIndexed { c, t ->
            when (c) {
                NesRules.EPSM_SSG1 -> t.toMutableList().also { it[0] = Cell(48, 1) }
                NesRules.EPSM_SSG1 + 1 -> t.toMutableList().also { it[0] = Cell(52, 2) }
                NesRules.EPSM_FM1 -> t.toMutableList().also { it[0] = Cell(48, 3) }
                NesRules.EPSM_RHYTHM1 -> t.toMutableList().also { it[0] = Cell(48, 4) }
                else -> t
            }
        })), sound = base.nes.copy(instruments = bank))
        assertEquals(emptyList(), NesEpsmSystem.errors(song))
        val w = NesEpsmSystem.warnings(song).joinToString("\n")
        assertTrue(w.contains("share the SSG's one envelope generator"), w)
        assertTrue(w.contains("the song's LFO is off"), w)
        assertTrue(w.contains("EPSM drums play only its first value"), w)
        // "Tri env"'s manual period of 900 cannot be FamiStudio text: the check says so before the export refuses.
        assertTrue(w.contains("manual SSG envelope period of 900"), w)
        val on = song.copy(sound = song.nes.copy(epsmLfo = NesSound.EPSM_LFO_ON or 2))
        assertFalse(NesEpsmSystem.warnings(on).joinToString().contains("LFO is off"))
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
        assertFalse(lines.any { it.contains("Expansion=") })
        assertEquals(listOf("Square1", "Square2", "Triangle", "Noise", "DPCM", "MMC5Square1", "MMC5Square2"),
            lines.filter { it.trim().startsWith("Channel") }.map { it.substringAfter("Type=\"").substringBefore('"') })
    }

    /** MMC5 pulses: 2A03 sounds, A-1 as their lowest note, duties 00-03, no DMC level; one chip in the bank. */
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
        // Every 2A03 pulse effect, with the 2A03's duties.
        assertEquals(NesSystem.effectsFor(0).map { it.code }, NesMmc5System.effectsFor(NesRules.MMC5_PULSE1).map { it.code })
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
