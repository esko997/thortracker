package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesEpsmSystem
import org.thortracker.system.nes.EpsmPatch
import org.thortracker.system.nes.NesSound
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
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
     * attribute for it): the text export refuses, `.nsf` does not.
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
        // A manual period of 3000: no text, the .nsf still exports.
        val bank = song.nes.instruments.toMutableList()
        bank[4] = bank[4].copy(epsmEnvelope = bank[4].epsmEnvelope.copy(period = 3000))
        val manual = song.copy(sound = song.nes.copy(instruments = bank))
        assertEquals(emptyList(), NesEpsmSystem.errors(manual))
        val e = assertFailsWith<IllegalArgumentException> { FamiStudioText.text(manual) }
        assertTrue(e.message!!.contains("Sound 05") && e.message!!.contains("3000") && e.message!!.contains("4000"), e.message)
        assertTrue(NsfExporter.export(manual).isNotEmpty())
        // An auto-period sound's period does not matter there.
        bank[4] = bank[4].copy(epsmEnvelope = bank[4].epsmEnvelope.copy(autoPitch = true))
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
        // Structure: EPSM fields on other chips, a duty envelope on an EPSM sound, an impossible patch or LFO.
        val sbank = bank.toMutableList()
        sbank[2] = NesInstrument("Stray mixer", mixer = NesEnvelope(listOf(1)))
        sbank[3] = NesInstrument("Stray patch", epsm = EpsmPatch(algorithm = 1))
        sbank[4] = NesInstrument("Duty", chip = NesChip.EPSM, duty = NesEnvelope(listOf(1)))
        sbank[5] = NesInstrument("Bad", chip = NesChip.EPSM, epsm = EpsmPatch(algorithm = 9), epsmEnvelope = S5bEnvelope(S5bShape.DECAY, octave = 9))
        val s = NesEpsmSystem.structuralErrors(blank.copy(sound = blank.nes.copy(instruments = sbank, epsmLfo = 5))).joinToString()
        for (want in listOf("only EPSM sounds have mixer and noise", "only EPSM sounds have an FM patch", "EPSM sounds have no duty envelope",
            "FM algorithm 9", "octave 9", "not off (0) or on with a rate")) assertTrue(s.contains(want), "$want: $s")
        assertTrue(NesVrc6System.structuralErrors(NesVrc6System.blank().let { it.copy(sound = it.nes.copy(epsmLfo = 0x08)) }).joinToString().contains("Only EPSM songs"))
        // An EPSM sound is not a plain NES song's.
        assertTrue(NesSystem.structuralErrors(blank).single().contains("NES + EPSM"))
    }

    /** Shared EPSM hardware: the SSG's one envelope and noise generators, the one LFO, the drums' one volume. */
    @Test
    fun epsmWarningsPointAtSharedHardware() {
        val base = NesEpsmSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[0] = NesInstrument("Saw env", chip = NesChip.EPSM, epsmEnvelope = S5bEnvelope(S5bShape.SAW_DOWN, autoPitch = false, period = 4000))
        bank[1] = NesInstrument("Tri env", chip = NesChip.EPSM, epsmEnvelope = S5bEnvelope(S5bShape.TRIANGLE, autoPitch = false, period = 900))
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
        val on = song.copy(sound = song.nes.copy(epsmLfo = NesSound.EPSM_LFO_ON or 2))
        assertFalse(NesEpsmSystem.warnings(on).joinToString().contains("LFO is off"))
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
