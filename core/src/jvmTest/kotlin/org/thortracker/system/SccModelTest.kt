package org.thortracker.system

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.SccLowering
import org.thortracker.backend.SccPlayerImage
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.ProjectCodec
import org.thortracker.model.ProjectFormatException
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.scc.KonamiSccSystem
import org.thortracker.system.scc.SccDemoSong
import org.thortracker.system.scc.SccInstrument
import org.thortracker.system.scc.SccInstrumentView
import org.thortracker.system.scc.SccPlusSystem
import org.thortracker.system.scc.SccPresets
import org.thortracker.system.scc.SccRules
import org.thortracker.system.scc.SccSharedState
import org.thortracker.system.scc.SccSongSettings
import org.thortracker.system.scc.SccSound
import org.thortracker.system.scc.SccSystem
import org.thortracker.system.scc.SccValidator
import org.thortracker.system.scc.SccVariant
import org.thortracker.system.scc.SccVgm
import org.thortracker.system.scc.SccVgmRenderer
import org.thortracker.system.scc.SccVgmTags
import org.thortracker.system.scc.SccWave
import org.thortracker.system.scc.SccWaveShapes
import org.thortracker.system.scc.SccWaveView
import org.thortracker.system.scc.scc

/**
 * The SCC and SCC+ systems' model: registration and shape, project files, the validator's mirror of the loader's
 * reasons, warnings (the SCC's shared wave RAM above all), arrangement, the shared-state walk, fine pitch, the
 * lowering's header, records, envelopes and sharing, the VGM seam, waves, presets and the demos. Slots are the presets'
 * (a new song's): 01 Lead, 02 Bass, 03 Bell, 04 Pad, 05 Pluck, 06 Organ, 07 Kick, 08 Snare, 09 Hat, 0A Arp lead,
 * 0B Echo, 0C Morph.
 */
class SccModelTest {
    @After
    fun noRenderer() {
        SccVgm.renderer = null
    }

    private val lead = slot("Lead")
    private val bell = slot("Bell")
    private val pluck = slot("Pluck")
    private val organ = slot("Organ")
    private val kick = slot("Kick")
    private val echo = slot("Echo")
    private val morph = slot("Morph")
    private val bass = slot("Bass")

    private fun slot(name: String) = SccPresets.ALL.indexOfFirst { it.name == name } + 1

    private fun with(song: Song, order: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun blankWith(channel: Int, row: Int, cell: Cell, system: KonamiSccSystem = SccSystem) = with(system.blank(), 0, channel, row, cell)

    private fun plus(song: Song): Song = song.copy(sound = song.scc.copy(variant = SccVariant.PLUS))

    private fun withSound(song: Song, f: (SccSound) -> SccSound) = song.copy(sound = f(song.scc))

    private fun u16(image: ByteArray, at: Int) = (image[at].toInt() and 0xFF) or ((image[at + 1].toInt() and 0xFF) shl 8)

    // ---- system shape ------------------------------------------------------------------------------------------

    @Test
    fun bothChipsAreRegisteredWithFiveWaveChannels() {
        assertEquals(SccSystem, SoundSystems.of("msx-scc"))
        assertEquals(SccPlusSystem, SoundSystems.of("msx-scc-plus"))
        assertEquals("Konami SCC (K051649)", SccSystem.name)
        assertEquals("Konami SCC+ (K052539)", SccPlusSystem.name)
        for (system in listOf(SccSystem, SccPlusSystem)) {
            assertEquals("MSX SCC", system.family)
            assertEquals((1..5).map { "SCC $it" }, system.channels.map { it.name })
            assertTrue(system.channels.all { it.tonal && it.kind == ChannelKind.WAVE && it.bank == 0 })
            assertEquals(listOf(BankSpec("Instruments", 64)), system.banks)
            assertEquals(9..95, system.noteRange)
            assertEquals("A-0", system.noteName(9))
            assertEquals("A-4", system.noteName(57))
            assertEquals("B-7", system.noteName(95))
            assertEquals(4, system.effectColumns(4))
            assertEquals(ViewKind.entries.toSet(), system.views)
            assertSame(SccInstrumentView, system.view(ViewKind.INSTRUMENT))
            assertSame(SccWaveView, system.view(ViewKind.WAVE))
            assertNull(system.view(ViewKind.PATTERN))
            assertNull(system.offEffect)
            assertEquals(256, system.maxOrders)
            assertEquals(1, system.backendVersion)
            assertEquals(listOf("0", "1", "2", "3", "4", "A", "B", "C", "D", "F", "G", "P", "S", "T", "W"),
                system.effectsFor(2).map { it.char.toString() })
            val engine = system.engine
            assertEquals(system.id, engine.id)
            assertEquals(65536, engine.imageSize)
            assertEquals(SccPlayerImage.SEEK_TICKS, engine.seekTicks)
            assertEquals(57, engine.note(system.blank(), 0, 57, 1))
            assertEquals(16, engine.fine(0, 57, 16))
            assertEquals(SccSound.serializer(), system.soundSerializer)
            assertEquals(system, system.blank().sound.system)
            assertEquals(180.0, system.ticksPerSecond(withSound(system.blank()) { it.copy(multispeed = 3) }), 1e-9)
            assertEquals(listOf("vgm"), system.exports.map { it.extension })
        }
        assertEquals("Thor Tracker's SCC player on a K051649 core ported from MAME (20d4667), blip_buf 1.1.0", SccSystem.engine.driver)
        assertEquals("Thor Tracker's SCC player on a K052539 core ported from MAME (20d4667), blip_buf 1.1.0", SccPlusSystem.engine.driver)
        assertEquals(SccSystem, SccVariant.SCC.system)
        assertEquals(SccPlusSystem, SccVariant.PLUS.system)
    }

    @Test
    fun blankSongsStartWithPresetsAndStarterWaves() {
        for (system in listOf(SccSystem, SccPlusSystem)) {
            val blank = system.blank()
            assertEquals(emptyList<String>(), system.errors(blank))
            assertEquals(emptyList<String>(), system.warnings(blank))
            val sound = blank.scc
            assertEquals(system.variant, sound.variant)
            assertEquals(SccPresets.ALL.map { it.instrument }, sound.instruments.take(SccPresets.ALL.size))
            assertTrue(sound.instruments.drop(SccPresets.ALL.size).all { it == null })
            assertEquals(64, sound.instruments.size)
            assertEquals(SccPresets.WAVES, sound.waves.take(SccPresets.WAVES.size))
            assertTrue(sound.waves.drop(SccPresets.WAVES.size).all { w -> w.samples == List(32) { 0 } })
            assertEquals(64, sound.waves.size)
            assertEquals(1, sound.multispeed)
        }
        val blank = SccSystem.blank()
        assertEquals("Lead", SccSystem.instrumentName(blank, 0, lead))
        assertEquals(KonamiSccSystem.EMPTY_NAME, SccSystem.instrumentName(blank, 0, 40))
        assertNull(SccSystem.instrumentName(blank, 1, 1))
        assertEquals("Empty: choose a preset", SccSystem.instrumentSummary(blank, 0, 40))
        assertTrue(SccSystem.instrumentSummary(blank, 0, lead)!!.startsWith("Wave 05 Pulse 25% · vol 15→0, release"))
        assertTrue(SccSystem.instrumentSummary(blank, 0, kick)!!.contains("pitch sweep"))
        assertTrue(SccSystem.instrumentSummary(blank, 0, morph)!!.startsWith("Waves 01 01 01 02"))
        // The thumbnail is the first wave (Lead: the 25% pulse) as 0..1.
        val shape = SccSystem.instrumentShape(blank, 0, lead)!!
        assertEquals(List(8) { 1f } + List(24) { 0f }, shape)
        assertNull(SccSystem.instrumentShape(blank, 0, 40))
        // The Wave view follows the selected instrument's first wave (0-based).
        assertEquals(SccPresets.BELL - 1, SccSystem.selectSound(Selection(instrument = bell), blank).wave)
        assertEquals(Selection(instrument = 40, wave = 5), SccSystem.selectSound(Selection(instrument = 40, wave = 5), blank))
    }

    // ---- project files -------------------------------------------------------------------------------------

    @Test
    fun projectFilesRoundTripBothVariants() {
        val custom = SccInstrument(
            "custom", volume = NesEnvelope(listOf(15, 12, 9, 6, 3, 0), loop = 1, release = 4), arpeggio = NesEnvelope(listOf(0, 7, -12), loop = 0),
            pitch = NesEnvelope(listOf(-64, 63)), pitchRelative = true, wave = NesEnvelope(listOf(20, 21), loop = 1), restart = true,
        )
        val cell = Cell(57, 40, SccRules.FX_VOLUME, 0x0C).withEffect(1, SccRules.FX_WAVE, 0x14).withEffect(3, SccRules.FX_FINE, 0x90)
        for (system in listOf(SccSystem, SccPlusSystem)) {
            val song = withSound(blankWith(3, 0, cell, system)) {
                it.copy(multispeed = 2)
                    .withInstrument(40, custom)
                    .withInstrument(2, null)
                    .withWave(20, SccWave("edge", List(32) { i -> if (i == 0) -128 else 127 }))
                    .withWave(21, SccWave("", SccWaveShapes.noise(7)))
            }
            assertEquals(emptyList<String>(), system.errors(song))
            val text = ProjectCodec.encode(song)
            assertTrue(text.contains("\"backend\":\"${system.id}\",\"backendVersion\":1"))
            assertTrue(text.contains("[57,40,12,12,32,20,0,0,25,144]"))
            val back = ProjectCodec.decode(text)
            assertEquals(song, back)
            assertEquals(text, ProjectCodec.encode(back))
            assertNull(back.scc.instrument(2))
            assertEquals(custom, back.scc.instrument(40))
            assertEquals(system.variant, back.scc.variant)
        }
        for (s in listOf(SccSystem.demo(), SccPlusSystem.demo(), SccDemoSong.shortest(SccVariant.SCC), SccDemoSong.shortest(SccVariant.PLUS))) {
            assertEquals(s, ProjectCodec.decode(ProjectCodec.encode(s)))
        }
        // A project whose backend says SCC but whose chip data is for the SCC+ is refused.
        val scc = ProjectCodec.encode(SccSystem.blank())
        assertTrue(scc.contains("\"variant\":\"scc\""))
        val mixed = scc.replace("\"variant\":\"scc\"", "\"variant\":\"scc-plus\"")
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(mixed) }
        assertTrue(e.message!!.contains("not the SCC (K051649)"))
    }

    // ---- validation ----------------------------------------------------------------------------------------

    @Test
    fun validationExplainsWhatTheLoaderRejects() {
        fun error(cell: Cell, channel: Int = 0) = SccSystem.errors(blankWith(channel, 0, cell)).joinToString("\n")
        assertTrue(error(Cell(8, lead)).contains("outside A-0..B-7"))
        assertTrue(error(Cell(96, lead)).contains("outside A-0..B-7"))
        assertEquals("", error(Cell(9, lead)))
        assertEquals("", error(Cell(95, lead)))
        assertEquals("", error(Cell(Cell.CUT)))
        assertTrue(error(Cell(57, 40)).contains("instrument 28 is empty"))
        assertTrue(error(Cell(57, 65)).contains("instrument 65 is outside 0-64"))
        // Parameters, in any column.
        assertTrue(error(Cell(57, lead).withEffect(2, SccRules.FX_VOLUME, 0x10)).contains("volumes are C00-C0F"))
        assertEquals("", error(Cell(57, lead, SccRules.FX_VOLUME, 0x0F)))
        assertTrue(error(Cell(57, lead, SccRules.FX_SPEED, 0)).contains("F00"))
        assertTrue(error(Cell(57, lead, SccRules.FX_WAVE, 0)).contains("waves are W01-W40"))
        assertTrue(error(Cell(57, lead, SccRules.FX_WAVE, 0x41)).contains("waves are W01-W40"))
        assertEquals("", error(Cell(57, lead, SccRules.FX_WAVE, 0x40)))
        assertTrue(error(Cell(57, lead, SccRules.FX_JUMP, 1)).contains("jumps past the last order"))
        assertEquals("", error(Cell(57, lead, SccRules.FX_TEST, 0xFF)))
        assertEquals("", error(Cell(57, lead, SccRules.FX_DELAY, 0xFF)))
        assertTrue(error(Cell(57, lead, 5, 1)).contains("does not exist on the SCC"))
        assertTrue(error(Cell(57, lead, 33, 1)).contains("does not exist on the SCC"))
        // Instruments, envelopes and waves.
        fun songError(f: (SccSound) -> SccSound) = SccSystem.errors(withSound(SccSystem.blank(), f)).joinToString("\n")
        fun insError(ins: SccInstrument) = songError { it.withInstrument(20, ins) }
        assertTrue(insError(SccInstrument(wave = NesEnvelope())).contains("Instrument 14: it plays no wave"))
        assertTrue(insError(SccInstrument(wave = NesEnvelope(listOf(65)))).contains("names wave 65"))
        assertTrue(insError(SccInstrument(wave = NesEnvelope(listOf(0)))).contains("names wave 0"))
        assertTrue(insError(SccInstrument(volume = NesEnvelope(listOf(16)))).contains("value 16 outside 0..15"))
        assertTrue(insError(SccInstrument(arpeggio = NesEnvelope(listOf(64)))).contains("value 64 outside -64..63"))
        assertTrue(insError(SccInstrument(pitch = NesEnvelope(listOf(-65)))).contains("value -65 outside -64..63"))
        assertTrue(insError(SccInstrument(arpeggio = NesEnvelope(listOf(0, 1), release = 1))).contains("only volume envelopes have one"))
        assertTrue(insError(SccInstrument(volume = NesEnvelope(listOf(15, 0), release = 0))).contains("release point 0 is outside steps 1-1"))
        assertTrue(insError(SccInstrument(volume = NesEnvelope(listOf(15), release = 1))).contains("too short for a release point"))
        assertTrue(insError(SccInstrument(volume = NesEnvelope(listOf(15, 9, 0), loop = 2, release = 2))).contains("the loop must come first"))
        assertTrue(insError(SccInstrument(volume = NesEnvelope(listOf(15, 9), loop = 2))).contains("loops to step 2"))
        assertTrue(insError(SccInstrument(pitch = NesEnvelope(loop = 0))).contains("loop or release point but no values"))
        assertTrue(insError(SccInstrument(volume = NesEnvelope(List(257) { 15 }))).contains("at most 256"))
        assertEquals("", insError(SccInstrument(volume = NesEnvelope(List(256) { 15 }, loop = 3, release = 255))))
        assertTrue(songError { it.withWave(3, SccWave("short", List(31) { 0 })) }.contains("Wave 03 has 31 samples"))
        assertTrue(songError { it.withWave(3, SccWave("loud", List(32) { 128 })) }.contains("Wave 03 has a sample outside -128..127"))
        assertTrue(songError { it.copy(multispeed = 9) }.contains("Multispeed 9 is outside 1-8"))
        assertTrue(songError { it.copy(instruments = it.instruments.drop(1)) }.contains("exactly 64"))
        assertTrue(songError { it.copy(waves = it.waves.drop(1)) }.contains("exactly 64"))
        // A song of the other chip is not this system's.
        assertTrue(SccSystem.errors(plus(SccSystem.blank())).single().contains("for the SCC+ (K052539), not the SCC (K051649)"))
        // Every rejection the lowering can still meet throws the same message.
        val bad = withSound(SccSystem.blank()) { it.withInstrument(20, SccInstrument(wave = NesEnvelope())) }
        assertTrue(assertThrows(IllegalArgumentException::class.java) { SccLowering.lower(bad) }.message!!.contains("plays no wave"))
    }

    @Test
    fun dataBeyondTheImageIsAnErrorAndLoweringRefusesIt() {
        // 20 distinct full patterns: four effect columns on every row of every channel overflow the 58 KB region.
        val base = SccSystem.blank()
        val patterns = List(20) { p ->
            Pattern("P$p", List(5) { ch -> List(Songs.ROWS) { row ->
                Cell(12 + (p + ch + row) % 80, 1, SccRules.FX_VOLUME, row % 16)
                    .withEffect(1, SccRules.FX_DELAY, p).withEffect(2, SccRules.FX_FINE, 0x80 + ch).withEffect(3, SccRules.FX_CUT, row)
            } })
        }
        val song = base.copy(patterns = patterns, orders = patterns.indices.toList())
        assertTrue(SccLowering.dataSize(song) > SccPlayerImage.DATA_CAPACITY)
        assertTrue(SccSystem.errors(song).joinToString().contains("the SCC image holds"))
        assertThrows(IllegalArgumentException::class.java) { SccLowering.lower(song) }
    }

    @Test
    fun auditionChecksSlotNoteAndFine() {
        val engine = SccSystem.engine
        val song = SccSystem.blank()
        assertNull(engine.auditionProblem(song, 0, 57, lead, 0))
        assertNull(engine.auditionProblem(song, 4, 9, kick, -128))
        assertTrue(engine.auditionProblem(song, 0, 8, lead, 0)!!.contains("outside A-0..B-7"))
        assertTrue(engine.auditionProblem(song, 0, 96, lead, 0)!!.contains("outside A-0..B-7"))
        assertTrue(engine.auditionProblem(song, 0, 57, 40, 0)!!.contains("is empty"))
        assertTrue(engine.auditionProblem(song, 0, 57, 0, 0)!!.contains("Choose an instrument"))
        assertTrue(engine.auditionProblem(song, 5, 57, lead, 0)!!.contains("channel 5"))
        assertTrue(engine.auditionProblem(song, 0, 57, lead, 128)!!.contains("Fine pitch"))
        val broken = withSound(song) { it.withInstrument(20, SccInstrument(wave = NesEnvelope())) }
        assertTrue(engine.auditionProblem(broken, 0, 57, 20, 0)!!.contains("cannot play: it plays no wave"))
    }

    // ---- warnings ------------------------------------------------------------------------------------------

    @Test
    fun sharedWaveConflictsWarnOnTheSccOnly() {
        fun warnings(song: Song) = song.system.warnings(song).joinToString("\n")
        val shared = "one shared wave RAM"
        // Saw pluck on SCC 4 and square echo on SCC 5 at once.
        val clash = with(blankWith(3, 0, Cell(48, pluck)), 0, 4, 0, Cell(55, echo))
        val text = warnings(clash)
        assertTrue(text, text.contains("Order 00 row 0: SCC 4 (wave 03 \"Saw\") and SCC 5 (wave 04 \"Square\") sound together"))
        assertTrue(text.contains(shared))
        assertTrue(text.contains("both play the latest claim: wave 04 \"Square\" from SCC 5's note at order 00 row 0"))
        assertTrue(text.contains("switch the song to SCC+ (K052539)"))
        // Reported once per stretch, not every row.
        assertEquals(1, Regex(shared).findAll(text).count())
        // The SCC+ has a RAM per channel.
        assertFalse(warnings(plus(clash)).contains(shared))
        // The same instrument on both: one wave, no conflict.
        assertFalse(warnings(with(blankWith(3, 0, Cell(48, organ)), 0, 4, 0, Cell(52, organ))).contains(shared))
        // Alternating phrases: SCC 4's pluck ends with OFF (no release point) before SCC 5 enters.
        var alternating = with(blankWith(3, 0, Cell(48, pluck)), 0, 3, 4, Cell(Cell.CUT))
        alternating = with(alternating, 0, 4, 8, Cell(55, echo))
        assertFalse(warnings(alternating).contains(shared))
        // An OFF on a note with a release point only releases it: the release still sounds while SCC 5 plays.
        var released = with(blankWith(3, 0, Cell(48, lead)), 0, 3, 4, Cell(Cell.CUT))
        released = with(released, 0, 4, 8, Cell(55, echo))
        assertTrue(warnings(released).contains("Order 00 row 8: SCC 4 (wave 05 \"Pulse 25%\")"))
        // A W lock on one note counts: Organ on both, but SCC 5's note plays the saw.
        val locked = with(blankWith(3, 0, Cell(48, organ)), 0, 4, 0, Cell(52, organ, SccRules.FX_WAVE, SccPresets.SAW))
        assertTrue(warnings(locked).contains("SCC 5 (wave 03 \"Saw\")"))
        // Two wave numbers with the same samples are the same sound.
        val twin = withSound(with(blankWith(3, 0, Cell(48, pluck)), 0, 4, 0, Cell(55, 20))) {
            it.withWave(30, SccWave("saw copy", SccWaveShapes.saw())).withInstrument(20, SccInstrument("twin", wave = NesEnvelope(listOf(30))))
        }
        assertFalse(warnings(twin).contains(shared))
        // A wave envelope's waves are compared as a set.
        assertTrue(warnings(with(blankWith(3, 0, Cell(48, morph)), 0, 4, 0, Cell(55, organ))).contains("waves 01, 02, 03, 04 of a wave envelope"))
    }

    @Test
    fun testRegisterWarningsDependOnTheBits() {
        fun warnings(param: Int, system: KonamiSccSystem = SccSystem) =
            blankWith(0, 0, Cell(57, lead, SccRules.FX_TEST, param), system).let { system.warnings(it) }.joinToString("\n")
        assertEquals("", warnings(0x20))
        assertEquals("", warnings(0x00))
        assertTrue(warnings(0x01).contains("unverified frequency mode"))
        assertTrue(warnings(0x02).contains("VGM players ignore it"))
        assertTrue(warnings(0x04).contains("bits 2-4 have no known function"))
        assertTrue(warnings(0x40).contains("freezes every wave RAM"))
        assertTrue(warnings(0x40, SccPlusSystem).contains("freezes every wave RAM"))
        assertTrue(warnings(0x80).contains("freezes the wave RAM SCC 4 and SCC 5 share"))
        val plus80 = warnings(0x80, SccPlusSystem)
        assertTrue(plus80.contains("the SCC+ ignores it"))
        assertFalse(plus80.contains("freezes"))
    }

    @Test
    fun otherWarningsCoverWavesDelaysInstrumentsAndHighNotes() {
        fun warnings(song: Song) = SccSystem.warnings(song).joinToString("\n")
        // Wxx on a row where nothing sounds; on a sounding note it is fine.
        assertTrue(warnings(blankWith(0, 3, Cell(effect = SccRules.FX_WAVE, param = 4))).contains("no note sounds here"))
        assertEquals("", warnings(with(blankWith(0, 0, Cell(57, lead)), 0, 0, 3, Cell(effect = SccRules.FX_WAVE, param = 4))))
        // After OFF on a note without a release point nothing sounds; on SCC 4 the W still claims the shared RAM.
        var afterOff = with(blankWith(3, 0, Cell(57, pluck)), 0, 3, 2, Cell(Cell.CUT))
        afterOff = with(afterOff, 0, 3, 3, Cell(effect = SccRules.FX_WAVE, param = 4))
        assertTrue(warnings(afterOff).contains("it still claims the wave RAM SCC 4 and SCC 5 share"))
        assertTrue(warnings(blankWith(0, 0, Cell(48, lead, SccRules.FX_DELAY, 6))).contains("the row never plays"))
        assertEquals("", warnings(blankWith(0, 0, Cell(48, lead, SccRules.FX_DELAY, 5))))
        assertTrue(warnings(blankWith(0, 4, Cell(48))).contains("has no instrument"))
        // Notes above G#6 with the 12-bit divider's real error.
        val high = warnings(blankWith(0, 0, Cell(95, lead)))
        assertTrue(high, high.contains("Notes above G#6"))
        assertTrue(high, high.contains("B-7 +19.2¢ (period 27)"))
        assertEquals("", warnings(blankWith(0, 0, Cell(80, lead))))
        assertEquals(1.6, SccRules.cents(57), 0.05)
        assertEquals(253, SccPlayerImage.period(57 * 64))
    }

    // ---- arrangement, settings and shared state -------------------------------------------------------------

    @Test
    fun arrangementRenumbersJumpsInEveryColumn() {
        val start = SccSystem.blank()
        val patterns = List(3) { Songs.blankPattern(start) }
        var song = start.copy(patterns = patterns, orders = listOf(0, 1, 2), parkedOrders = listOf(1))
        song = with(song, 2, 3, 5, Cell(48, 1).withEffect(2, SccRules.FX_JUMP, 2))
        val arranged = SccSystem.arranged(song)
        assertEquals(listOf(0, 2), arranged.orders)
        assertEquals(1, SccSystem.jumpTarget(arranged.patterns[2].tracks[3][5]))
        val loop = SccSystem.patternLoop(song, 2)
        assertEquals(listOf(2), loop.orders)
        assertEquals(0, SccSystem.jumpTarget(loop.patterns[2].tracks[3][5]))
        // The lowering plays the arrangement: two orders.
        assertEquals(2, u16(SccLowering.lower(song), SccPlayerImage.H_ORDERS))
    }

    @Test
    fun multispeedSettingAdjustsAndResets() {
        val song = SccPlusSystem.blank()
        assertEquals(listOf("Multispeed"), SccPlusSystem.songSettings.map { it.label })
        val m = SccSongSettings.ALL.single()
        assertEquals(2, m.adjust(song, 1, 0).scc.multispeed)
        assertEquals(5, m.adjust(song, 0, 1).scc.multispeed)
        assertEquals(8, m.adjust(song, 0, 5).scc.multispeed)
        assertEquals(1, m.adjust(song, -3, 0).scc.multispeed)
        assertTrue(m.value(m.adjust(song, 2, 0)).contains("180 ticks per second"))
        assertEquals(song, m.reset(m.adjust(song, 2, 0)))
    }

    @Test
    fun sharedStateNamesTheWaveTheSharedRamHolds() {
        var song = SccSystem.blank()
        song = with(song, 0, 3, 0, Cell(48, organ))
        song = with(song, 0, 4, 2, Cell(55, pluck))
        song = with(song, 0, 3, 4, Cell(effect = SccRules.FX_WAVE, param = 5))
        song = with(song, 0, 0, 6, Cell(effect = SccRules.FX_TEST, param = 0x40))
        song = with(song, 0, 4, 7, Cell(43, bass))
        song = with(song, 0, 0, 8, Cell(effect = SccRules.FX_TEST, param = 0x00))
        song = with(song, 0, 4, 10, Cell(55, morph))

        val start = SccSystem.sharedState(SccSystem.blank(), 0, 0)!!
        assertEquals(List<String?>(5) { null }, start.tags)
        assertTrue(start.lines[0].contains("not claimed yet"))

        val first = SccSystem.sharedState(song, 0, 0)!!
        assertEquals(listOf(null, null, null, "W07", "W07"), first.tags)
        assertEquals("Shared wave RAM (SCC 4 + SCC 5) holds wave 07 \"Organ\" from SCC 4's note at order 00 row 0; both channels play it", first.lines[0])
        assertEquals(1, first.lines.size)

        val second = SccSystem.sharedState(song, 0, 2)!!
        assertEquals("W03", second.tags[3])
        assertTrue(second.lines[0].contains("wave 03 \"Saw\" from SCC 5's note at order 00 row 2"))
        assertTrue(second.lines.contains("SCC 4 is sounding too: it plays this wave, not its own wave 07 \"Organ\""))

        // A W on SCC 4's sounding note re-claims the RAM.
        val change = SccSystem.sharedState(song, 0, 4)!!
        assertEquals("W05", change.tags[4])
        assertTrue(change.lines[0].contains("from SCC 4's wave change at order 00 row 4"))

        // Frozen by T40: SCC 5's claim does not reach the RAM until T00.
        val frozen = SccSystem.sharedState(song, 0, 7)!!
        assertEquals("W05", frozen.tags[3])
        assertTrue(frozen.lines[0].contains("frozen by T40; the claim was for wave 09 \"Bass\""))
        assertTrue(frozen.lines.last().startsWith("Test register T40: every wave RAM is frozen"))
        assertEquals(9, SccSharedState.sharedWave(song, 0, 8))
        assertEquals("W09", SccSystem.sharedState(song, 0, 8)!!.tags[3])

        // A wave envelope: the tag marks it, a line says it re-claims at every step.
        val stepping = SccSystem.sharedState(song, 0, 10)!!
        assertEquals("W01+", stepping.tags[4])
        assertTrue(stepping.lines.any { it.contains("steps through waves 01, 02, 03, 04") })

        // The SCC+ shares nothing: only the test register shows.
        val p = plus(song)
        assertNull(SccPlusSystem.sharedState(p, 0, 2))
        val test = SccPlusSystem.sharedState(p, 0, 6)!!
        assertEquals(List<String?>(5) { null }, test.tags)
        assertEquals(listOf("Test register T40: every wave RAM is frozen (wave changes are not written)"), test.lines)
        assertEquals(0, SccSharedState.sharedWave(p, 0, 10))
    }

    // ---- tuning realisation ----------------------------------------------------------------------------------

    @Test
    fun fineWritesPxxInAFreeColumn() {
        val fine = SccSystem.fine
        assertEquals("Pxx", fine.name)
        assertEquals(25.0, fine.cents(57, 16), 1e-9)
        val busy = Cell(57, 1, SccRules.FX_VIBRATO, 0x23)
        val tuned = fine.write(busy, 57, 16)!!
        assertEquals(SccRules.FX_FINE, tuned.effectCode(1))
        assertEquals(0x90, tuned.effectParam(1))
        assertEquals(16, fine.of(tuned))
        assertEquals(busy, fine.write(tuned, 57, 0))
        var full = Cell(57, 1)
        for (c in 0 until 4) full = full.withEffect(c, SccRules.FX_VOLUME, 0x0C)
        assertNull(fine.write(full, 57, 8))
        assertNull(fine.of(Cell(57, 1, SccRules.FX_FINE, 0x70))) // a downward bend is not a tuning
    }

    // ---- lowering ----------------------------------------------------------------------------------------------

    @Test
    fun headerTablesAndRecordsAreTheCanonicalBytes() {
        val a = SccInstrument("a", volume = NesEnvelope(listOf(15, 12, 8, 0), release = 2), wave = NesEnvelope(listOf(1)))
        val b = SccInstrument("b", volume = NesEnvelope(), arpeggio = NesEnvelope(listOf(0, -12), loop = 0),
            pitch = NesEnvelope(listOf(-40, 8)), pitchRelative = true, wave = NesEnvelope(listOf(1)), restart = true)
        val sound = SccSound(
            SccVariant.PLUS,
            List(SccSound.SLOTS) { when (it) { 0 -> a; 4 -> b; else -> null } },
            List(SccSound.WAVES) { if (it == 0) SccWave("sine", SccWaveShapes.sine()) else if (it == 63) SccWave("top", SccWaveShapes.saw()) else SccWave() },
            multispeed = 2,
        )
        val song = SccPlusSystem.blank().copy(ticksPerRow = 5, sound = sound)
        val image = SccLowering.lower(song)
        assertEquals("TTSC", image.copyOfRange(SccPlayerImage.H_MAGIC, SccPlayerImage.H_MAGIC + 4).decodeToString())
        assertEquals(SccPlayerImage.LAYOUT, image[SccPlayerImage.H_LAYOUT].toInt())
        assertEquals(SccPlayerImage.VARIANT_PLUS, image[SccPlayerImage.H_VARIANT].toInt())
        assertEquals(2, image[SccPlayerImage.H_MULTISPEED].toInt())
        assertEquals(5, image[SccPlayerImage.H_SPEED].toInt())
        assertEquals(1, u16(image, SccPlayerImage.H_ORDERS))
        assertEquals(0, u16(image, SccPlayerImage.H_LOOP))
        assertEquals(5, image[SccPlayerImage.H_INSTRUMENTS].toInt())
        assertEquals(64, image[SccPlayerImage.H_WAVES].toInt())
        for (i in 16 until SccPlayerImage.HEADER_SIZE) assertEquals("header byte $i", 0, image[i].toInt())
        assertEquals(64, image[SccPlayerImage.OFF_ROWS].toInt())
        assertEquals(0, image[SccPlayerImage.OFF_ROWS + 1].toInt())
        // Waves 1..64 as two's complement; silent waves are zero.
        assertArrayEquals(SccWaveShapes.sine().map { it.toByte() }.toByteArray(),
            image.copyOfRange(SccPlayerImage.OFF_WAVES, SccPlayerImage.OFF_WAVES + 32))
        val top = SccPlayerImage.OFF_WAVES + 63 * SccPlayerImage.WAVE_BYTES
        assertArrayEquals(SccWaveShapes.saw().map { it.toByte() }.toByteArray(), image.copyOfRange(top, top + 32))
        assertTrue(image.copyOfRange(SccPlayerImage.OFF_WAVES + 32, top).all { it == 0.toByte() })
        // Five empty tracks share one run byte at offset 0 (64 empty rows = 0x80 | 63).
        for (c in 0 until 5) assertEquals(0, u16(image, SccPlayerImage.OFF_ORDERS + 2 * c))
        val data = SccPlayerImage.OFF_DATA
        assertEquals(0xBF, image[data].toInt() and 0xFF)
        // Then slot 1's envelopes: volume (with its release) and wave; slot 5's arpeggio, pitch and the shared wave.
        val ra = SccPlayerImage.OFF_INSTRUMENTS
        assertEquals(0x80, image[ra + SccPlayerImage.I_FLAGS].toInt() and 0xFF)
        assertEquals(0, image[ra + 1].toInt())
        assertEquals(1, u16(image, ra + SccPlayerImage.I_VOLUME))
        assertEquals(SccPlayerImage.ENV_NONE, u16(image, ra + SccPlayerImage.I_ARPEGGIO))
        assertEquals(SccPlayerImage.ENV_NONE, u16(image, ra + SccPlayerImage.I_PITCH))
        assertEquals(11, u16(image, ra + SccPlayerImage.I_WAVE))
        assertArrayEquals(byteArrayOf(4, 0, 0xFF.toByte(), 0xFF.toByte(), 2, 0, 15, 12, 8, 0), image.copyOfRange(data + 1, data + 11))
        assertArrayEquals(byteArrayOf(1, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1), image.copyOfRange(data + 11, data + 18))
        for (i in 10 until 16) assertEquals(0, image[ra + i].toInt())
        val rb = SccPlayerImage.OFF_INSTRUMENTS + 4 * SccPlayerImage.INSTRUMENT_SIZE
        assertEquals(0x83, image[rb + SccPlayerImage.I_FLAGS].toInt() and 0xFF)
        assertEquals(SccPlayerImage.ENV_NONE, u16(image, rb + SccPlayerImage.I_VOLUME))
        assertEquals(18, u16(image, rb + SccPlayerImage.I_ARPEGGIO))
        assertEquals(26, u16(image, rb + SccPlayerImage.I_PITCH))
        assertEquals(11, u16(image, rb + SccPlayerImage.I_WAVE)) // the same bytes as slot 1's wave envelope
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0xF4.toByte()), image.copyOfRange(data + 18, data + 26))
        assertArrayEquals(byteArrayOf(2, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xD8.toByte(), 8), image.copyOfRange(data + 26, data + 34))
        assertEquals(34, u16(image, SccPlayerImage.H_DATA_SIZE))
        assertEquals(34, SccLowering.dataSize(song))
        // Empty slots between and above are zero records.
        assertTrue(image.copyOfRange(ra + SccPlayerImage.INSTRUMENT_SIZE, rb).all { it == 0.toByte() })
        assertTrue(image.copyOfRange(rb + SccPlayerImage.INSTRUMENT_SIZE, SccPlayerImage.OFF_WAVES).all { it == 0.toByte() })
        assertTrue(image.copyOfRange(data + 34, SccPlayerImage.IMAGE_SIZE).all { it == 0.toByte() })
        // The SCC variant byte; the engine's lower overwrites a dirty buffer completely.
        assertEquals(SccPlayerImage.VARIANT_SCC, SccLowering.lower(SccSystem.blank())[SccPlayerImage.H_VARIANT].toInt())
        val dirty = ByteArray(SccPlayerImage.IMAGE_SIZE) { 0x55 }
        SccPlusSystem.engine.lower(song, dirty)
        assertArrayEquals(image, dirty)
    }

    @Test
    fun rowsEncodeAsThePlayerReadsThem() {
        // An empty run, a full cell with three effects (a blank column skipped), OFF, the trailing run.
        val cell = Cell(57, 2, SccRules.FX_VOLUME, 0x0C).withEffect(2, SccRules.FX_TEST, 0x20).withEffect(3, SccRules.FX_WAVE, 0x40)
        val track = List(Songs.ROWS) { when (it) { 3 -> cell; 4 -> Cell(Cell.CUT); else -> Cell.BLANK } }
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), (1 or 2 or (3 shl 2)).toByte(), 57, 2, 12, 0x0C, 29, 0x20, 32, 0x40, 1, 0xFE.toByte(), (0x80 or 58).toByte()),
            SccLowering.encode(track, Songs.ROWS),
        )
        // A pattern shorter than 64 rows encodes only its rows; an effect-only row has no note or instrument byte.
        val short = List(Songs.ROWS) { if (it == 1) Cell(effect = SccRules.FX_SPEED, param = 3) else Cell.BLANK }
        assertArrayEquals(byteArrayOf(0x80.toByte(), (1 shl 2).toByte(), 15, 3, 0x81.toByte()), SccLowering.encode(short, 4))
        // An identical track in another order is stored once: orders 1 and 2 point where order 0 does. Order 0's SCC 2
        // track (the note) follows the empty-track run byte; the other channels share that byte.
        val base = SccSystem.blankWith()
        val song = base.copy(patterns = base.patterns + base.patterns[0].copy(name = "copy"), orders = listOf(0, 1, 0))
        val image = SccLowering.lower(song)
        fun at(o: Int, c: Int) = u16(image, SccPlayerImage.OFF_ORDERS + 2 * (5 * o + c))
        assertEquals(listOf(0, 1, 0, 0, 0), (0 until 5).map { at(0, it) })
        for (o in 1 until 3) for (c in 0 until 5) assertEquals(at(0, c), at(o, c))
        assertArrayEquals(byteArrayOf(0xBF.toByte(), 3, 57, 1, 0xBE.toByte()),
            image.copyOfRange(SccPlayerImage.OFF_DATA, SccPlayerImage.OFF_DATA + 5))
    }

    private fun KonamiSccSystem.blankWith(): Song = with(blank(), 0, 1, 0, Cell(57, 1))

    // ---- exports and demos ---------------------------------------------------------------------------------

    @Test
    fun vgmExportCallsTheNativeRenderer() {
        val demo = SccSystem.demo()
        val vgm = SccSystem.exports.single { it.extension == "vgm" }
        assertThrows(IllegalStateException::class.java) { vgm.export(demo) }
        var seen: Triple<ByteArray, Int, SccVgmTags>? = null
        SccVgm.renderer = SccVgmRenderer { image, maxTicks, tags -> seen = Triple(image, maxTicks, tags); byteArrayOf(1, 2, 3) }
        assertArrayEquals(byteArrayOf(1, 2, 3), vgm.export(demo))
        val (image, maxTicks, tags) = seen!!
        assertArrayEquals(SccLowering.lower(demo), image)
        assertEquals(36000, maxTicks)
        assertEquals(SccVgmTags("Cartridge Dusk", "Thor Tracker"), tags)
        val plusDemo = SccPlusSystem.demo()
        SccPlusSystem.exports.single().export(withSound(plusDemo) { it.copy(multispeed = 2) })
        assertEquals(72000, seen!!.second)
        assertEquals(SccPlayerImage.VARIANT_PLUS, seen!!.first[SccPlayerImage.H_VARIANT].toInt())
    }

    @Test
    fun demosAreCleanAndShowTheirChips() {
        val dusk = SccSystem.demo()
        val prism = SccPlusSystem.demo()
        assertEquals("Cartridge Dusk", dusk.title)
        assertEquals("Prism Lane", prism.title)
        for (demo in listOf(dusk, prism)) {
            val system = demo.system
            assertEquals(emptyList<String>(), system.errors(demo))
            assertEquals(emptyList<String>(), system.warnings(demo))
            assertTrue(demo.loopOrder > 0)
            val cells = demo.orders.toSet().flatMap { demo.patterns[it].tracks.flatten() }
            fun uses(code: Int) = cells.any { c -> (0..c.more.size).any { c.effectCode(it) == code && (code != 0 || c.effectParam(it) != 0) } }
            for (code in listOf(SccRules.FX_ARPEGGIO, SccRules.FX_VIBRATO, SccRules.FX_VOLUME, SccRules.FX_SPEED, SccRules.FX_DELAY, SccRules.FX_WAVE)) {
                assertTrue("${demo.title} uses ${SccRules.effectText(code, 0)[0]}", uses(code))
            }
            assertTrue(cells.any { it.more.size >= 2 })
            assertTrue(cells.any { it.note == Cell.CUT })
            for (ch in 0 until 5) assertTrue("${demo.title} channel $ch", demo.patterns.any { p -> p.tracks[ch].any { it.note >= 0 } })
            // A wave envelope with several waves sounds.
            assertTrue(cells.any { it.instrument != 0 && demo.scc.instrument(it.instrument)!!.wave.values.distinct().size > 1 })
            val image = SccLowering.lower(demo)
            assertEquals(SccLowering.dataSize(demo), u16(image, SccPlayerImage.H_DATA_SIZE))
            assertTrue(SccLowering.dataSize(demo) < SccPlayerImage.DATA_CAPACITY)
        }
        // The SCC demo keeps SCC 4 and SCC 5 on one wave or alternating; the SCC+ demo's five waves would clash on the SCC.
        val prismOnScc = prism.copy(sound = prism.scc.copy(variant = SccVariant.SCC))
        assertTrue(SccSystem.warnings(prismOnScc).any { it.contains("one shared wave RAM") })
        assertTrue(prism.patterns.any { p -> p.tracks.any { t -> t.any { c -> (0..c.more.size).any { c.effectCode(it) == SccRules.FX_TEST && c.effectParam(it) == 0x20 } } } })
        assertTrue(prism.scc.waves.size == 64 && prism.scc.wave(SccPresets.WAVES.size + 1)!!.name == "Glass")
        // The shortest songs: one row at speed 1 holding A-4 on SCC 1 with slot 01, a sine.
        for (variant in SccVariant.entries) {
            val shortest = SccDemoSong.shortest(variant)
            assertEquals(variant.system, shortest.system)
            assertEquals(emptyList<String>(), variant.system.errors(shortest))
            assertEquals(1, shortest.ticksPerRow)
            assertEquals(1, shortest.patterns.single().length)
            assertEquals(listOf(0), shortest.orders)
            assertEquals(Cell(57, 1), shortest.patterns.single().tracks[0][0])
            assertEquals(listOf(1), shortest.scc.instrument(1)!!.wave.values)
            assertEquals(SccWaveShapes.sine(), shortest.scc.wave(1)!!.samples)
            assertEquals(1, SccLowering.lower(shortest)[SccPlayerImage.OFF_ROWS].toInt())
        }
    }

    @Test
    fun effectDetailExplainsWavesTestBitsAndArpeggios() {
        var song = blankWith(3, 0, Cell(57, lead, SccRules.FX_ARPEGGIO, 0x47).withEffect(1, SccRules.FX_WAVE, 3))
        song = with(song, 0, 3, 2, Cell(effect = SccRules.FX_WAVE, param = 4))
        song = with(song, 0, 0, 0, Cell(effect = SccRules.FX_TEST, param = 0xA1))
        assertEquals("A-4 → C#5 → E-5 every tick", SccSystem.effectDetail(song, 0, 3, 0, 0))
        assertEquals("The note on this row plays wave 03 \"Saw\" instead of its instrument's wave envelope; on the SCC this " +
            "claims the wave RAM SCC 4 and SCC 5 share, so SCC 5 plays it too", SccSystem.effectDetail(song, 0, 3, 0, 1))
        assertTrue(SccSystem.effectDetail(song, 0, 3, 2, 0)!!.startsWith("The sounding note switches to wave 04 \"Square\""))
        assertEquals("The sounding note switches to wave 04 \"Square\" until its next note", SccPlusSystem.effectDetail(plus(song), 0, 3, 2, 0))
        val t = SccSystem.effectDetail(song, 0, 0, 0, 0)!!
        assertTrue(t, t.startsWith("Test register A1 for every channel: waveforms restart"))
        assertTrue(t.contains("the shared wave RAM of SCC 4 and SCC 5 is frozen"))
        assertTrue(t.contains("4-bit frequency mode"))
        assertNull(SccSystem.effectDetail(song, 0, 3, 1, 0))
        assertEquals("Volume C of F", SccRules.describe(song, 0, SccRules.FX_VOLUME, 0x0C))
        assertEquals("Fine pitch +16/64 semitone (+25.0¢)", SccRules.describe(null, 0, SccRules.FX_FINE, 0x90))
        assertEquals("Wave 07 \"Organ\" for this note (SCC 4 and SCC 5 both play it: one wave RAM)", SccRules.describe(song, 4, SccRules.FX_WAVE, 7))
        assertEquals("Wave 07 \"Organ\" for this note", SccRules.describe(plus(song), 4, SccRules.FX_WAVE, 7))
    }

    // ---- waves and presets ---------------------------------------------------------------------------------

    @Test
    fun waveShapesAreThirtyTwoSignedSamples() {
        val sine = SccWaveShapes.sine()
        assertEquals(List(32) { i -> kotlin.math.floor(127 * kotlin.math.sin(2 * Math.PI * i / 32) + 0.5).toInt() }, sine)
        assertEquals(listOf(0, 127, 0, -127), listOf(sine[0], sine[8], sine[16], sine[24]))
        val tri = SccWaveShapes.triangle()
        assertEquals(listOf(0, 127, 0, -127), listOf(tri[0], tri[8], tri[16], tri[24]))
        assertEquals(-128, SccWaveShapes.saw().first())
        assertEquals(127, SccWaveShapes.saw().last())
        assertEquals(List(8) { 127 } + List(24) { -128 }, SccWaveShapes.pulse(8))
        assertEquals(SccWaveShapes.pulse(16), SccWaveShapes.square())
        assertEquals(SccWaveShapes.pulse(31), SccWaveShapes.pulse(99))
        assertEquals(List(32) { if (it < 8) -127 else 127 }, SccWaveShapes.invert(SccWaveShapes.pulse(8)))
        assertEquals(sine.drop(8) + sine.take(8), SccWaveShapes.rotate(sine, 8))
        assertEquals(sine, SccWaveShapes.rotate(sine, -32))
        assertEquals(List(32) { 40 }, SccWaveShapes.smooth(List(32) { 40 }))
        assertEquals(127, SccWaveShapes.normalize(List(32) { if (it == 3) -50 else 25 }).maxOf { kotlin.math.abs(it) })
        assertEquals(List(32) { 0 }, SccWaveShapes.normalize(List(32) { 0 }))
        assertEquals(SccWaveShapes.noise(3), SccWaveShapes.noise(3))
        assertFalse(SccWaveShapes.noise(3) == SccWaveShapes.noise(4))
        for (w in listOf(sine, tri, SccWaveShapes.saw(), SccWaveShapes.noise(9), SccWaveShapes.smooth(SccWaveShapes.square()),
                SccWaveShapes.additive(listOf(1 to 1.0, 3 to 0.5)))) {
            assertEquals(32, w.size)
            assertTrue(w.all { it in -128..127 })
        }
    }

    @Test
    fun presetsAreValidAndUseTheStarterWaves() {
        assertEquals(listOf("Sine", "Triangle", "Saw", "Square", "Pulse 25%", "Pulse 12.5%", "Organ", "Bell", "Bass", "Noise"),
            SccPresets.WAVES.map { it.name })
        assertTrue(SccPresets.WAVES.all { it.problem() == null })
        assertEquals(12, SccPresets.ALL.size)
        for (p in SccPresets.ALL) {
            assertEquals(p.name, p.instrument.name)
            assertNull(p.name, SccValidator.instrumentProblem(p.instrument))
            assertTrue(p.name, p.instrument.waves.all { it in 1..SccPresets.WAVES.size })
        }
        assertEquals(SccPresets.ALL[kick - 1], SccPresets.named("Kick"))
        assertTrue(SccPresets.named("Kick")!!.instrument.pitchRelative)
        assertTrue(SccPresets.named("Snare")!!.instrument.waves.contains(SccPresets.NOISE))
        assertTrue(SccPresets.named("Bell")!!.instrument.volume.release > 0)
        assertTrue(SccPresets.named("Morph")!!.instrument.wave.values.distinct().size > 1)
        assertNull(SccPresets.named("Nope"))
        assertNotNull(SccPresets.named("Arp lead")!!.instrument.arpeggio.values.firstOrNull())
    }
}
