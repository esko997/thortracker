package org.thortracker.system

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.OpmLowering
import org.thortracker.backend.OpmPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.opm.OpmDemoSong
import org.thortracker.system.opm.OpmInstrument
import org.thortracker.system.opm.OpmOperator
import org.thortracker.system.opm.OpmRules
import org.thortracker.system.opm.OpmSharedState
import org.thortracker.system.opm.OpmSongSettings
import org.thortracker.system.opm.OpmSound
import org.thortracker.system.opm.OpmSweep
import org.thortracker.system.opm.OpmSystem
import org.thortracker.system.opm.OpmVgm
import org.thortracker.system.opm.OpmVgmRenderer
import org.thortracker.system.opm.OpmVgmTags
import org.thortracker.system.opm.opm

/**
 * The OPM system's model: registration and shape, project files, the validator's mirror of the loader's reasons,
 * warnings, arrangement, song settings, the shared-state walk, fine pitch, the lowering's header and records, the VGM
 * seam and the demo. The songs here use their own instruments (slot 01 a sine, 02 a noise hat, 03 an LFO pad), not
 * the presets, so they do not depend on the preset list.
 */
class OpmModelTest {
    @After
    fun noRenderer() {
        OpmVgm.renderer = null
    }

    private val silent = OpmOperator(tl = 127, rr = 15)
    private val sine = OpmInstrument("sine", algorithm = 7, operators = listOf(silent, silent, silent, OpmOperator(tl = 0, rr = 6)))
    private val hat = OpmInstrument(
        "noise hat", algorithm = 7, noise = true, noiseFrequency = 24,
        operators = listOf(silent, silent, silent, OpmOperator(tl = 4, d1r = 14, d1l = 15, rr = 15)),
    )
    private val lfoPad = OpmInstrument(
        "lfo pad", algorithm = 7, pms = 5, ams = 2,
        operators = List(4) { OpmOperator(tl = 20, am = true, rr = 6) },
    )

    private fun base(): Song = OpmSystem.blank().copy(
        sound = OpmSound(List(OpmSystem.SLOTS) { when (it) { 0 -> sine; 1 -> hat; 2 -> lfoPad; else -> null } }),
    )

    private fun with(song: Song, order: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun blankWith(channel: Int, row: Int, cell: Cell) = with(base(), 0, channel, row, cell)

    private fun u16(image: ByteArray, at: Int) = (image[at].toInt() and 0xFF) or ((image[at + 1].toInt() and 0xFF) shl 8)

    // ---- system shape ------------------------------------------------------------------------------------------

    @Test
    fun systemIsRegisteredWithEightFmChannels() {
        assertEquals(OpmSystem, SoundSystems.of("ym2151-opm"))
        assertEquals("OPM FM (YM2151)", OpmSystem.name)
        assertEquals((1..8).map { "FM $it" }, OpmSystem.channels.map { it.name })
        assertTrue(OpmSystem.channels.all { it.tonal && it.kind == ChannelKind.VOICE && it.bank == 0 })
        assertEquals(3..98, OpmSystem.noteRange)
        assertEquals("A-4", OpmSystem.noteName(57))
        assertEquals("D#0", OpmSystem.noteName(OpmSystem.noteRange.first))
        assertEquals(4, OpmSystem.effectColumns(7))
        // Nxx (the noise generator) is offered on FM 8 only.
        assertFalse(OpmSystem.effectsFor(0).any { it.code == OpmRules.FX_NOISE })
        assertTrue(OpmSystem.effectsFor(7).any { it.code == OpmRules.FX_NOISE })
        assertEquals(OpmSystem.effects.size - 1, OpmSystem.effectsFor(3).size)
        val engine = OpmSystem.engine
        assertEquals("ym2151-opm", engine.id)
        assertEquals(65536, engine.imageSize)
        assertEquals(OpmPlayerImage.SEEK_TICKS, engine.seekTicks)
        assertEquals(57, engine.note(base(), 0, 57, 1))
        assertEquals(180.0, OpmSystem.ticksPerSecond(base().copy(sound = base().opm.copy(multispeed = 3))), 1e-9)
        assertEquals(OpmSound.serializer(), OpmSystem.soundSerializer)
        assertEquals(OpmSystem, OpmSystem.blank().sound.system)
    }

    @Test
    fun blankSongIsValid() {
        val blank = OpmSystem.blank()
        assertEquals(emptyList<String>(), OpmSystem.errors(blank))
        assertEquals(OpmSystem.SLOTS, blank.opm.instruments.size)
        assertEquals(1, blank.opm.multispeed)
        assertFalse(blank.opm.csm)
        assertEquals(emptyList<String>(), OpmSystem.errors(base()))
        assertEquals("sine", OpmSystem.instrumentName(base(), 0, 1))
        assertEquals(OpmSystem.EMPTY_NAME, OpmSystem.instrumentName(base(), 0, 40))
        assertNull(OpmSystem.instrumentName(base(), 1, 1))
    }

    // ---- project files -------------------------------------------------------------------------------------

    @Test
    fun projectFilesRoundTripTheSharedState() {
        val cell = Cell(57, 1, OpmRules.FX_VOLUME, 0x60).withEffect(1, OpmRules.FX_PAN, 0x10).withEffect(3, OpmRules.FX_FINE, 0x90)
        val song = blankWith(0, 0, cell).let {
            it.copy(sound = it.opm.copy(multispeed = 2, lfoRate = 0xC8, lfoWave = 2, amd = 40, pmd = 20, csm = true, timerA = 900))
        }
        val text = ProjectCodec.encode(song)
        assertTrue(text.contains("\"backend\":\"ym2151-opm\",\"backendVersion\":1"))
        assertTrue(text.contains("[57,1,12,96,8,16,0,0,25,144]"))
        val back = ProjectCodec.decode(text)
        assertEquals(song, back)
        assertEquals(text, ProjectCodec.encode(back))
        val sound = back.opm
        assertEquals(listOf(2, 0xC8, 2, 40, 20, 900), listOf(sound.multispeed, sound.lfoRate, sound.lfoWave, sound.amd, sound.pmd, sound.timerA))
        assertTrue(sound.csm)
        assertEquals(hat, sound.instrument(2))
        for (s in listOf(OpmSystem.blank(), OpmSystem.demo(), OpmDemoSong.shortest())) {
            val t = ProjectCodec.encode(s)
            assertEquals(s, ProjectCodec.decode(t))
        }
    }

    // ---- validation ----------------------------------------------------------------------------------------

    @Test
    fun validationExplainsWhatTheLoaderRejects() {
        fun error(channel: Int, cell: Cell) = OpmSystem.errors(blankWith(channel, 0, cell)).joinToString("\n")
        // A noise instrument plays on FM 8 only; Nxx too.
        assertTrue(error(0, Cell(60, 2)).contains("noise instruments belong on FM 8"))
        assertEquals("", error(7, Cell(60, 2)))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_NOISE, 0x10)).contains("only FM 8 plays noise"))
        assertEquals("", error(7, Cell(60, 2, OpmRules.FX_NOISE, 0x10)))
        assertTrue(error(7, Cell(60, 2, OpmRules.FX_NOISE, 0x20)).contains("N00-N1F"))
        // Parameter ranges, in any effect column.
        assertTrue(error(0, Cell(60, 1).withEffect(2, OpmRules.FX_VOLUME, 0x80)).contains("volumes are C00-C7F"))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_SENSITIVITY, 0x80)).contains("PMS (x) is 0-7 and AMS (y) 0-3"))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_SENSITIVITY, 0x04)).contains("PMS (x) is 0-7 and AMS (y) 0-3"))
        assertEquals("", error(0, Cell(60, 1, OpmRules.FX_SENSITIVITY, 0x73)))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_LFO_WAVE, 0x04)).contains("LFO waveforms are W00"))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_CSM, 0x02)).contains("CSM is X00 (off) or X01 (on)"))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_AMD, 0x80)).contains("amplitude depths are T00-T7F"))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_PMD, 0x80)).contains("pitch depths are V00-V7F"))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_FEEDBACK, 0x08)).contains("feedback is E00-E07"))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_BRIGHTNESS, 0x80)).contains("brightness is I00-I7F"))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_MULT, 0x51)).contains("operator digit is 1-4"))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_SPEED, 0)).contains("F00"))
        assertTrue(error(0, Cell(60, 1, OpmRules.FX_JUMP, 0x01)).contains("jumps past the last order"))
        assertEquals("", error(0, Cell(60, 1, OpmRules.FX_TIMER_A, 0xFF)))
        assertEquals("", error(0, Cell(60, 1, OpmRules.FX_LFO_RATE, 0xFF)))
        assertTrue(error(0, Cell(60, 1, 19, 1)).contains("does not exist on the OPM"))
        // Notes outside what the 4 MHz chip sounds, empty slots.
        assertTrue(error(0, Cell(2, 1)).contains("outside D#0..D-8"))
        assertTrue(error(0, Cell(99, 1)).contains("outside D#0..D-8"))
        assertEquals("", error(0, Cell(3, 1)))
        assertEquals("", error(0, Cell(98, 1)))
        assertTrue(error(0, Cell(60, 40)).contains("instrument 28 is empty"))
        // Song-level header values.
        fun songError(f: (OpmSound) -> OpmSound) = OpmSystem.errors(base().let { it.copy(sound = f(it.opm)) }).joinToString("\n")
        assertTrue(songError { it.copy(lfoWave = 4) }.contains("LFO waveform 4 is outside 0-3"))
        assertTrue(songError { it.copy(amd = 128) }.contains("outside 0-127"))
        assertTrue(songError { it.copy(timerA = 1024) }.contains("timer A 1024 is outside 0-1023"))
        assertTrue(songError { it.copy(multispeed = 9) }.contains("multispeed 9 is outside 1-8"))
        // Instrument records.
        assertTrue(songError { it.withInstrument(4, sine.copy(noiseFrequency = 3)) }.contains("noise frequency without noise"))
        assertTrue(songError { it.withInstrument(4, sine.copy(sweep = OpmSweep(start = 97, speed = 1))) }.contains("-96..96"))
    }

    @Test
    fun auditionChecksNoiseChannelAndRange() {
        val engine = OpmSystem.engine
        val song = base()
        assertNull(engine.auditionProblem(song, 0, 57, 1, 0))
        assertNull(engine.auditionProblem(song, 7, 57, 2, 0))
        assertTrue(engine.auditionProblem(song, 0, 57, 2, 0)!!.contains("audition it on FM 8"))
        assertTrue(engine.auditionProblem(song, 0, 2, 1, 0)!!.contains("outside D#0..D-8"))
        assertTrue(engine.auditionProblem(song, 0, 99, 1, 0)!!.contains("outside D#0..D-8"))
        assertTrue(engine.auditionProblem(song, 0, 57, 40, 0)!!.contains("is empty"))
        assertTrue(engine.auditionProblem(song, 0, 57, 1, 200)!!.contains("Fine pitch"))
    }

    @Test
    fun warningsCoverLocksDelaysAndTheSharedLfo() {
        fun warnings(song: Song) = OpmSystem.warnings(song).joinToString("\n")
        assertEquals("", warnings(base()))
        assertTrue(warnings(blankWith(0, 1, Cell(effect = OpmRules.FX_BRIGHTNESS, param = 0x20))).contains("Ixx applies when a note starts"))
        assertTrue(warnings(blankWith(0, 0, Cell(48, 1, OpmRules.FX_DELAY, 6))).contains("the row never plays"))
        assertTrue(warnings(blankWith(0, 4, Cell(48))).contains("has no instrument"))
        // An LFO-sensitive instrument while both depths stay 0 in the whole song.
        val pad = blankWith(0, 0, Cell(48, 3))
        val lfo = warnings(pad)
        assertTrue(lfo.contains("AMD) is 0 in the whole song"))
        assertTrue(lfo.contains("PMD) is 0 in the whole song"))
        // A depth set anywhere (header or effect) silences that warning.
        val withDepths = with(pad, 0, 1, 8, Cell(effect = OpmRules.FX_PMD, param = 0x10)).let { it.copy(sound = it.opm.copy(amd = 10)) }
        assertFalse(warnings(withDepths).contains("is 0 in the whole song"))
        // Hxy taking the LFO counts too.
        assertTrue(warnings(blankWith(0, 0, Cell(48, 1, OpmRules.FX_SENSITIVITY, 0x40))).contains("PMD) is 0 in the whole song"))
        // Timer A without CSM.
        assertTrue(warnings(blankWith(0, 0, Cell(48, 1, OpmRules.FX_TIMER_A, 0x80))).contains("CSM is never on"))
        val csm = with(blankWith(0, 0, Cell(48, 1, OpmRules.FX_TIMER_A, 0x80)), 0, 1, 0, Cell(effect = OpmRules.FX_CSM, param = 1))
        assertFalse(warnings(csm).contains("CSM is never on"))
    }

    // ---- arrangement, settings and shared state -------------------------------------------------------------

    @Test
    fun arrangementRenumbersJumpsInEveryColumn() {
        val start = base()
        val patterns = List(3) { Songs.blankPattern(start) }
        var song = start.copy(patterns = patterns, orders = listOf(0, 1, 2), parkedOrders = listOf(1))
        song = with(song, 2, 3, 5, Cell(48, 1).withEffect(2, OpmRules.FX_JUMP, 2))
        val arranged = OpmSystem.arranged(song)
        assertEquals(listOf(0, 2), arranged.orders)
        assertEquals(1, OpmSystem.jumpTarget(arranged.patterns[2].tracks[3][5]))
        val loop = OpmSystem.patternLoop(song, 2)
        assertEquals(listOf(2), loop.orders)
        assertEquals(0, OpmSystem.jumpTarget(loop.patterns[2].tracks[3][5]))
    }

    @Test
    fun songSettingsAdjustResetAndLower() {
        val song = base()
        assertEquals(listOf("Multispeed", "LFO rate", "LFO wave", "AMD", "PMD", "CSM", "Timer A"), OpmSystem.songSettings.map { it.label })
        assertEquals(OpmSongSettings.ALL, OpmSystem.songSettings)
        fun setting(label: String) = OpmSongSettings.ALL.single { it.label == label }
        val rate = setting("LFO rate")
        assertEquals(1, rate.adjust(song, 1, 0).opm.lfoRate)
        assertEquals(0x10, rate.adjust(song, 0, 1).opm.lfoRate)
        assertEquals(255, rate.adjust(song, 0, 40).opm.lfoRate)
        assertEquals(0, rate.adjust(song, -1, 0).opm.lfoRate)
        val fast = rate.adjust(song, 8, 12)
        assertEquals(0xC8, fast.opm.lfoRate)
        assertTrue(rate.value(fast).contains("5.72 Hz"))
        assertEquals(song, rate.reset(fast))
        val wave = setting("LFO wave")
        assertEquals(3, wave.adjust(song, -1, 0).opm.lfoWave)
        assertEquals(2, wave.adjust(song, 2, 0).opm.lfoWave)
        assertTrue(wave.value(wave.adjust(song, 2, 0)).startsWith("Triangle"))
        assertEquals(127, setting("AMD").adjust(song, 0, 10).opm.amd)
        assertEquals(17, setting("PMD").adjust(song, 1, 1).opm.pmd)
        val csm = setting("CSM")
        val on = csm.adjust(song, 1, 0)
        assertTrue(on.opm.csm)
        assertEquals(song, csm.adjust(song, 1, 1))
        assertEquals(song, csm.reset(on))
        val timer = setting("Timer A")
        assertEquals(1023, timer.adjust(song, 0, 20).opm.timerA)
        val t896 = timer.adjust(song, 0, 14)
        assertEquals(896, t896.opm.timerA)
        assertTrue(timer.value(t896).contains("488 Hz"))
        assertEquals(4, setting("Multispeed").adjust(song, 3, 0).opm.multispeed)
        // The header carries every one of them.
        val all = song.copy(ticksPerRow = 5, sound = song.opm.copy(multispeed = 2, lfoRate = 0xC8, lfoWave = 2, amd = 40, pmd = 20, csm = true, timerA = 900))
        val image = OpmLowering.lower(all)
        assertEquals("TTOM", image.copyOfRange(OpmPlayerImage.H_MAGIC, OpmPlayerImage.H_MAGIC + 4).decodeToString())
        assertEquals(OpmPlayerImage.LAYOUT, image[OpmPlayerImage.H_LAYOUT].toInt())
        assertEquals(2, image[OpmPlayerImage.H_MULTISPEED].toInt())
        assertEquals(5, image[OpmPlayerImage.H_SPEED].toInt())
        assertEquals(2, image[OpmPlayerImage.H_LFO_WAVE].toInt())
        assertEquals(1, u16(image, OpmPlayerImage.H_ORDERS))
        assertEquals(0, u16(image, OpmPlayerImage.H_LOOP))
        assertEquals(3, image[OpmPlayerImage.H_INSTRUMENTS].toInt())
        assertEquals(1, image[OpmPlayerImage.H_CSM].toInt())
        assertEquals(0xC8, image[OpmPlayerImage.H_LFO_RATE].toInt() and 0xFF)
        assertEquals(40, image[OpmPlayerImage.H_AMD].toInt())
        assertEquals(20, image[OpmPlayerImage.H_PMD].toInt())
        assertEquals(900, u16(image, OpmPlayerImage.H_TIMER_A))
        assertEquals(0, image[19].toInt())
        for (i in 22 until OpmPlayerImage.HEADER_SIZE) assertEquals("header byte $i", 0, image[i].toInt())
        assertEquals(OpmLowering.dataSize(all), u16(image, OpmPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun sharedStateFollowsTheSharedEffectsInPlayOrder() {
        var song = base()
        song = with(song, 0, 3, 0, Cell(effect = OpmRules.FX_LFO_RATE, param = 0xC8).withEffect(1, OpmRules.FX_LFO_WAVE, 2))
        song = with(song, 0, 0, 1, Cell(effect = OpmRules.FX_AMD, param = 40).withEffect(1, OpmRules.FX_PMD, 20))
        song = with(song, 0, 4, 2, Cell(effect = OpmRules.FX_CSM, param = 1).withEffect(1, OpmRules.FX_TIMER_A, 0xE0))
        song = with(song, 0, 7, 3, Cell(60, 2))
        song = with(song, 0, 7, 4, Cell(effect = OpmRules.FX_NOISE, param = 0x10))
        song = with(song, 0, 0, 5, Cell(effect = OpmRules.FX_CSM, param = 0))
        song = with(song, 0, 7, 6, Cell(60, 1))
        // Row 7: CSM on from a delayed row the speed reaches; row 8: off from one it never reaches (G09 at speed 6).
        song = with(song, 0, 1, 7, Cell(effect = OpmRules.FX_CSM, param = 1).withEffect(1, OpmRules.FX_DELAY, 3))
        song = with(song, 0, 2, 8, Cell(effect = OpmRules.FX_CSM, param = 0).withEffect(1, OpmRules.FX_DELAY, 9))
        // Row 9: two channels set the waveform; the later channel wins.
        song = with(song, 0, 0, 9, Cell(effect = OpmRules.FX_LFO_WAVE, param = 0))
        song = with(song, 0, 5, 9, Cell(effect = OpmRules.FX_LFO_WAVE, param = 3))
        // Row 10: FM 8's noise instrument with an N lock; row 11: a portamento note while keyed does not key on.
        song = with(song, 0, 7, 10, Cell(60, 2, OpmRules.FX_NOISE, 0x08))
        song = with(song, 0, 7, 11, Cell(64, 1, OpmRules.FX_PORTAMENTO, 0x10))
        val speed6 = song.copy(ticksPerRow = 6)

        assertEquals("LFO saw 0.0009 Hz · AMD 0 · PMD 0", OpmSystem.sharedState(base(), 0, 0).lines[0])
        assertEquals(List<String?>(8) { null }, OpmSystem.sharedState(base(), 0, 0).tags)
        assertEquals("LFO triangle 5.72 Hz · AMD 0 · PMD 0", OpmSystem.sharedState(speed6, 0, 0).lines[0])
        assertEquals("LFO triangle 5.72 Hz · AMD 40 · PMD 20", OpmSystem.sharedState(speed6, 0, 1).lines[0])
        assertEquals("CSM off", OpmSystem.sharedState(speed6, 0, 1).lines[1])
        assertEquals("CSM on · 488 Hz (timer A 896)", OpmSystem.sharedState(speed6, 0, 2).lines[1])
        val noise = OpmSystem.sharedState(speed6, 0, 3)
        assertEquals("noise", noise.tags[7])
        assertEquals("Noise on FM 8 · 15.6 kHz", noise.lines[2])
        assertEquals("Noise on FM 8 · 7.8 kHz", OpmSystem.sharedState(speed6, 0, 4).lines[2])
        assertEquals("CSM off", OpmSystem.sharedState(speed6, 0, 5).lines[1])
        val off = OpmSystem.sharedState(speed6, 0, 6)
        assertNull(off.tags[7])
        assertEquals("Noise off", off.lines[2])
        assertEquals(16, OpmSharedState.at(speed6, 0, 6).noiseFrequency)
        assertTrue(OpmSharedState.at(speed6, 0, 7).csm)
        assertTrue(OpmSharedState.at(speed6, 0, 8).csm)
        assertEquals(3, OpmSharedState.at(speed6, 0, 9).lfoWave)
        assertTrue(OpmSystem.sharedState(speed6, 0, 9).lines[0].startsWith("LFO noise "))
        val locked = OpmSharedState.at(speed6, 0, 10)
        assertTrue(locked.noiseOn)
        assertEquals(8, locked.noiseFrequency)
        // The glide on row 11 keeps the noise instrument's register: no key-on with the sine.
        assertTrue(OpmSharedState.at(speed6, 0, 11).noiseOn)
        // A position past the song's orders reads the header.
        assertEquals(OpmSharedState.start(speed6.opm), OpmSharedState.at(speed6, 5, 0))
    }

    // ---- tuning realisation ----------------------------------------------------------------------------------

    @Test
    fun fineWritesPxxInAFreeColumn() {
        val fine = OpmSystem.fine
        assertEquals(25.0, fine.cents(57, 16), 1e-9)
        val busy = Cell(57, 1, OpmRules.FX_VIBRATO, 0x23)
        val tuned = fine.write(busy, 57, 16)!!
        assertEquals(OpmRules.FX_FINE, tuned.effectCode(1))
        assertEquals(0x90, tuned.effectParam(1))
        assertEquals(16, fine.of(tuned))
        assertEquals(busy, fine.write(tuned, 57, 0))
        var full = Cell(57, 1)
        for (c in 0 until 4) full = full.withEffect(c, OpmRules.FX_VOLUME, 0x30)
        assertNull(fine.write(full, 57, 8))
        assertNull(fine.of(Cell(57, 1, OpmRules.FX_FINE, 0x70))) // a downward bend is not a tuning
        assertEquals(16, OpmSystem.engine.fine(0, 57, 16))
    }

    // ---- lowering ----------------------------------------------------------------------------------------------

    @Test
    fun recordsAndRowsAreTheSpecifiedBytes() {
        val r = OpmLowering.record(hat)
        assertEquals(OpmPlayerImage.INSTRUMENT_SIZE, r.size)
        assertEquals(0x81, r[OpmPlayerImage.I_FLAGS].toInt() and 0xFF)
        assertEquals(7, r[OpmPlayerImage.I_CONNECT].toInt())
        assertEquals(0, r[OpmPlayerImage.I_SENS].toInt())
        assertEquals(24, r[OpmPlayerImage.I_NOISE].toInt())
        val op4 = OpmPlayerImage.I_OPS + 3 * OpmPlayerImage.OPERATOR_BYTES
        assertArrayEquals(byteArrayOf(0x01, 4, 0x1F, 14, 0, 0xFF.toByte()), r.copyOfRange(op4, op4 + 6))
        val swept = sine.copy(feedback = 5, pms = 3, ams = 2, sweep = OpmSweep(start = -12, end = 3, speed = 40),
            operators = sine.operators.mapIndexed { k, op -> if (k == 0) op.copy(dt1 = 5, mul = 15, ks = 2, am = true, dt2 = 3, d2r = 7) else op })
        val s = OpmLowering.record(swept)
        assertEquals(0x80, s[OpmPlayerImage.I_FLAGS].toInt() and 0xFF)
        assertEquals((5 shl 3) or 7, s[OpmPlayerImage.I_CONNECT].toInt())
        assertEquals(0x32, s[OpmPlayerImage.I_SENS].toInt())
        assertEquals(0, s[OpmPlayerImage.I_NOISE].toInt())
        assertArrayEquals(
            byteArrayOf(0x5F, 127, (0x80 or 31).toByte(), (0x80 or 0).toByte(), (0xC0 or 7).toByte(), 0x0F),
            s.copyOfRange(OpmPlayerImage.I_OPS, OpmPlayerImage.I_OPS + 6),
        )
        assertEquals(-12, s[OpmPlayerImage.I_SWEEP_START].toInt())
        assertEquals(3, s[OpmPlayerImage.I_SWEEP_END].toInt())
        assertEquals(40, s[OpmPlayerImage.I_SWEEP_SPEED].toInt())
        assertEquals(0, s[31].toInt())
        // Rows: an empty run, a full cell with four columns (blank column skipped), OFF, the trailing run.
        val cell = Cell(57, 2, OpmRules.FX_VOLUME, 0x40).withEffect(2, OpmRules.FX_CSM, 1).withEffect(3, OpmRules.FX_NOISE, 0x1F)
        val track = List(Songs.ROWS) { when (it) { 3 -> cell; 4 -> Cell(Cell.CUT); else -> Cell.BLANK } }
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), (1 or 2 or (3 shl 2)).toByte(), 57, 2, 12, 0x40, 33, 1, 23, 0x1F, 1, 0xFE.toByte(), (0x80 or 58).toByte()),
            OpmLowering.encode(track, Songs.ROWS),
        )
        // Instruments land at their slots; slots above the highest stay zero.
        val image = OpmLowering.lower(base())
        assertArrayEquals(OpmLowering.record(hat), image.copyOfRange(OpmPlayerImage.OFF_INSTRUMENTS + OpmPlayerImage.INSTRUMENT_SIZE, OpmPlayerImage.OFF_INSTRUMENTS + 2 * OpmPlayerImage.INSTRUMENT_SIZE))
        assertTrue(image.copyOfRange(OpmPlayerImage.OFF_INSTRUMENTS + 3 * OpmPlayerImage.INSTRUMENT_SIZE, OpmPlayerImage.OFF_DATA).all { it == 0.toByte() })
    }

    // ---- exports and demo ----------------------------------------------------------------------------------

    @Test
    fun vgmExportCallsTheNativeRenderer() {
        val demo = OpmSystem.demo()
        val vgm = OpmSystem.exports.single { it.extension == "vgm" }
        assertThrows(IllegalStateException::class.java) { vgm.export(demo) }
        var seen: Triple<ByteArray, Int, OpmVgmTags>? = null
        OpmVgm.renderer = OpmVgmRenderer { image, maxTicks, tags -> seen = Triple(image, maxTicks, tags); byteArrayOf(1, 2, 3) }
        assertArrayEquals(byteArrayOf(1, 2, 3), vgm.export(demo))
        val (image, maxTicks, tags) = seen!!
        assertArrayEquals(OpmLowering.lower(demo), image)
        assertEquals(36000, maxTicks)
        assertEquals(OpmVgmTags("Sharp Corners", "Thor Tracker"), tags)
        vgm.export(demo.copy(sound = demo.opm.copy(multispeed = 2)))
        assertEquals(72000, seen!!.second)
    }

    @Test
    fun demoIsCleanAndShowsTheSystem() {
        val demo = OpmSystem.demo()
        assertEquals("Sharp Corners", demo.title)
        assertEquals(emptyList<String>(), OpmSystem.errors(demo))
        assertEquals(emptyList<String>(), OpmSystem.warnings(demo))
        assertTrue(demo.orders.size in 4..6)
        val sound = demo.opm
        assertTrue(sound.lfoRate != 0 && (sound.amd != 0 || sound.pmd != 0))
        val cells = demo.orders.toSet().flatMap { demo.patterns[it].tracks.flatten() }
        fun uses(code: Int, param: Int? = null) = cells.any { c -> (0..c.more.size).any { c.effectCode(it) == code && (param == null || c.effectParam(it) == param) } }
        for (code in listOf(OpmRules.FX_LFO_RATE, OpmRules.FX_LFO_WAVE, OpmRules.FX_AMD, OpmRules.FX_PMD, OpmRules.FX_TIMER_A,
                OpmRules.FX_SENSITIVITY, OpmRules.FX_PAN, OpmRules.FX_NOISE)) {
            assertTrue("effect ${OpmRules.effectText(code, 0)[0]}", uses(code))
        }
        assertTrue(uses(OpmRules.FX_CSM, 1))
        assertTrue(uses(OpmRules.FX_CSM, 0))
        // A DT2 instrument, a noise instrument on FM 8, every channel playing.
        assertTrue(cells.any { it.instrument != 0 && sound.instrument(it.instrument)!!.operators.any { op -> op.dt2 != 0 } })
        assertTrue(demo.patterns.any { p -> p.tracks[7].any { it.instrument != 0 && sound.instrument(it.instrument)!!.noise } })
        for (ch in 0 until 8) assertTrue("channel $ch", demo.patterns.any { p -> p.tracks[ch].any { it.note >= 0 } })
        val image = OpmLowering.lower(demo)
        assertEquals(OpmLowering.dataSize(demo), u16(image, OpmPlayerImage.H_DATA_SIZE))
        // The shortest song: one row at speed 1 holding A-4 on FM 1 with slot 01.
        val shortest = OpmDemoSong.shortest()
        assertEquals(emptyList<String>(), OpmSystem.errors(shortest))
        assertEquals(1, shortest.ticksPerRow)
        assertEquals(1, shortest.patterns.single().length)
        assertEquals(Cell(57, 1), shortest.patterns.single().tracks[0][0])
    }

    @Test
    fun effectDetailNamesArpeggioNotesAndIgnoredLocks() {
        var song = blankWith(0, 0, Cell(57, 1, OpmRules.FX_ARPEGGIO, 0x47))
        song = with(song, 0, 0, 2, Cell(effect = OpmRules.FX_ARPEGGIO, param = 0x37))
        song = with(song, 0, 0, 3, Cell(effect = OpmRules.FX_BRIGHTNESS, param = 0x40))
        assertEquals("A-4 → C#5 → E-5 every tick", OpmSystem.effectDetail(song, 0, 0, 0, 0))
        assertEquals("A-4 → C-5 → E-5 every tick", OpmSystem.effectDetail(song, 0, 0, 2, 0))
        assertTrue(OpmSystem.effectDetail(song, 0, 0, 3, 0)!!.contains("ignored"))
        assertNull(OpmSystem.effectDetail(song, 0, 0, 1, 0))
    }

    @Test
    fun describeGivesRatesInHertz() {
        assertEquals("LFO rate C8: 5.72 Hz, every channel", OpmRules.describe(null, 0, OpmRules.FX_LFO_RATE, 0xC8))
        assertEquals("Noise frequency 10: 7.8 kHz", OpmRules.describe(null, 7, OpmRules.FX_NOISE, 0x10))
        assertEquals("Timer A 896: CSM rate 488 Hz", OpmRules.describe(base(), 0, OpmRules.FX_TIMER_A, 0xE0))
        assertEquals("Fine pitch +16/64 semitone (+25.0¢)", OpmRules.describe(null, 0, OpmRules.FX_FINE, 0x90))
        assertEquals(59.1, OpmRules.lfoHz(255), 0.05)
        assertEquals(61.0, OpmRules.csmHz(0), 0.05)
        assertEquals(62500.0, OpmRules.csmHz(1023), 1e-9)
        assertEquals(3906.25, OpmRules.noiseHz(0), 1e-9)
    }
}
