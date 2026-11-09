package org.thortracker.system

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.SccLowering
import org.thortracker.backend.SccPlayerImage
import org.thortracker.editor.Selection
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
import org.thortracker.system.scc.SccPlusSystem
import org.thortracker.system.scc.SccPresets
import org.thortracker.system.scc.SccRules
import org.thortracker.system.scc.SccSharedState
import org.thortracker.system.scc.SccSound
import org.thortracker.system.scc.SccSystem
import org.thortracker.system.scc.SccValidator
import org.thortracker.system.scc.SccVariant
import org.thortracker.system.scc.SccWalk
import org.thortracker.system.scc.SccWave
import org.thortracker.system.scc.SccWaveShapes
import org.thortracker.system.scc.scc

/**
 * The SCC and SCC+ systems' model: pitch and timing, project files, what loads (the song gate, the editor's checks and
 * the lowering agree), audition acceptance, warnings and the causes that clear them (the SCC's shared wave RAM above
 * all), arrangement, the shared-state walk, fine pitch, the lowering's header, records, envelopes and sharing, waves,
 * presets and the demos. Slots are the presets' (a new song's): 01 Lead, 02 Bass, 03 Bell, 04 Pad, 05 Pluck, 06 Organ,
 * 07 Kick, 08 Snare, 09 Hat, 0A Arp lead, 0B Echo, 0C Morph.
 */
class SccModelTest {
    private val lead = slot("Lead")
    private val bell = slot("Bell")
    private val pluck = slot("Pluck")
    private val organ = slot("Organ")
    private val kick = slot("Kick")
    private val echo = slot("Echo")
    private val morph = slot("Morph")
    private val bass = slot("Bass")
    private val pad = slot("Pad")

    /** Slot 14: a release that ends above 0, so a released note stays keyed (audible) until a second OFF. */
    private val sustain = 0x14

    private fun withSustain(song: Song) = withSound(song) {
        it.withInstrument(sustain, SccInstrument("sustain", volume = NesEnvelope(listOf(15, 15, 6, 3), release = 2), wave = NesEnvelope(listOf(SccPresets.PULSE_25))))
    }

    private val systems = listOf(SccSystem, SccPlusSystem)

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

    // ---- notes and ticks ---------------------------------------------------------------------------------------

    @Test
    fun notesAndTicksAreThePlayers() {
        for (system in systems) {
            assertEquals("A-0", system.noteName(9))
            assertEquals("A-4", system.noteName(57))
            assertEquals("B-7", system.noteName(95))
            assertEquals(60.0, system.ticksPerSecond(system.blank()), 1e-9)
            assertEquals(180.0, system.ticksPerSecond(withSound(system.blank()) { it.copy(multispeed = 3) }), 1e-9)
        }
    }

    @Test
    fun instrumentThumbnailsNormalizeSignedWavesAndSelectionFollowsWave() {
        val song = withSound(SccSystem.blank()) {
            it.withInstrument(40, SccInstrument("Custom", wave = NesEnvelope(listOf(20))))
                .withInstrument(41, null)
                .withWave(20, SccWave(samples = List(8) { 127 } + List(24) { -128 }))
        }
        assertEquals(List(8) { 1f } + List(24) { 0f }, SccSystem.instrumentShape(song, 0, 40))
        assertNull(SccSystem.instrumentShape(song, 0, 41))
        assertEquals(19, SccSystem.selectSound(Selection(instrument = 40), song).wave)
        assertEquals(Selection(instrument = 41, wave = 5),
            SccSystem.selectSound(Selection(instrument = 41, wave = 5), song))
    }

    // ---- project files -------------------------------------------------------------------------------------

    @Test
    fun projectFilesRoundTripBothVariants() {
        val custom = SccInstrument(
            "custom", volume = NesEnvelope(listOf(15, 12, 9, 6, 3, 0), loop = 1, release = 4), arpeggio = NesEnvelope(listOf(0, 7, -12), loop = 0),
            pitch = NesEnvelope(listOf(-64, 63)), pitchRelative = true, wave = NesEnvelope(listOf(20, 21), loop = 1), restart = true,
        )
        val cell = Cell(57, 40, SccRules.FX_VOLUME, 0x0C).withEffect(1, SccRules.FX_WAVE, 0x14).withEffect(3, SccRules.FX_FINE, 0x90)
        for (system in systems) {
            val song = withSound(blankWith(3, 0, cell, system)) {
                it.copy(multispeed = 2)
                    .withInstrument(40, custom)
                    .withInstrument(2, null)
                    .withWave(20, SccWave("edge", List(32) { i -> if (i == 0) -128 else 127 }))
                    .withWave(21, SccWave("", SccWaveShapes.noise(7)))
            }
            assertEquals(emptyList<String>(), system.errors(song))
            val text = ProjectCodec.encode(song)
            val back = ProjectCodec.decode(text)
            assertEquals(song, back)
        }
        for (s in listOf(SccSystem.demo(), SccPlusSystem.demo(), SccDemoSong.shortest(SccVariant.SCC), SccDemoSong.shortest(SccVariant.PLUS))) {
            assertEquals(s, ProjectCodec.decode(ProjectCodec.encode(s)))
        }
        // A project whose backend says SCC but whose chip data is for the SCC+ is refused.
        val scc = ProjectCodec.encode(SccSystem.blank())
        val mixed = scc.replace("\"variant\":\"scc\"", "\"variant\":\"scc-plus\"")
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(mixed) }
    }

    // ---- validation ----------------------------------------------------------------------------------------

    /** Whether [song] may play and export: the gate the menus apply (its system's errors empty). Its reasons are UI text. */
    private fun loads(song: Song) = song.system.errors(song).isEmpty()

    /** Whether the lowering (the image every consumer plays) accepts [song]; it refuses rather than writing a bad image. */
    private fun lowers(song: Song): Boolean = try {
        SccLowering.lower(song)
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    /** Whether [cell] on [channel] (row 0 of [song]'s first order) loads; the editor's cell check and the song's gate agree. */
    private fun cellLoads(channel: Int, cell: Cell, song: Song = SccSystem.blank()): Boolean {
        val placed = with(song, 0, channel, 0, cell)
        val accepted = placed.system.cellProblem(placed, channel, cell) == null
        assertEquals("cell check and song gate on $cell, channel $channel", accepted, loads(placed))
        return accepted
    }

    /** Whether [ins] loads in slot 14; the instrument check, the song's gate and the lowering agree. */
    private fun soundLoads(ins: SccInstrument): Boolean {
        val song = withSound(SccSystem.blank()) { it.withInstrument(0x14, ins) }
        val accepted = SccValidator.instrumentProblem(ins) == null
        assertEquals("instrument check and song gate on $ins", accepted, loads(song))
        assertEquals("instrument check and lowering on $ins", accepted, lowers(song))
        return accepted
    }

    /** Whether the chip data [f] gives a blank song loads; the song's gate and the lowering agree. */
    private fun settingsLoad(f: (SccSound) -> SccSound): Boolean {
        val song = withSound(SccSystem.blank(), f)
        val accepted = loads(song)
        assertEquals("song gate and lowering", accepted, lowers(song))
        return accepted
    }

    @Test
    fun cellsLoadOnlyWhereThePlayerAcceptsThem() {
        for (system in systems) {
            val blank = system.blank()
            fun ok(cell: Cell, channel: Int = 0) = cellLoads(channel, cell, blank)
            // Notes A-0..B-7; OFF.
            assertFalse(ok(Cell(8, lead)))
            assertFalse(ok(Cell(96, lead)))
            assertTrue(ok(Cell(9, lead)))
            assertTrue(ok(Cell(95, lead)))
            assertTrue(ok(Cell(Cell.CUT)))
            // Instruments 01-40 that are filled.
            assertFalse(ok(Cell(57, 40)))
            assertFalse(ok(Cell(57, 65)))
            assertFalse(ok(Cell(57, SccSound.SLOTS)))
            assertTrue(cellLoads(4, Cell(57, SccSound.SLOTS), withSound(blank) { it.withInstrument(SccSound.SLOTS, SccPresets.ALL[0].instrument) }))
            // Parameters at their boundaries, in any effect column.
            assertFalse(ok(Cell(57, lead).withEffect(2, SccRules.FX_VOLUME, 0x10)))
            assertTrue(ok(Cell(57, lead).withEffect(2, SccRules.FX_VOLUME, 0x0F)))
            assertFalse(ok(Cell(57, lead, SccRules.FX_SPEED, 0)))
            assertTrue(ok(Cell(57, lead, SccRules.FX_SPEED, 1)))
            assertFalse(ok(Cell(57, lead, SccRules.FX_WAVE, 0)))
            assertTrue(ok(Cell(57, lead, SccRules.FX_WAVE, 0x01)))
            assertTrue(ok(Cell(57, lead, SccRules.FX_WAVE, 0x40)))
            assertFalse(ok(Cell(57, lead, SccRules.FX_WAVE, 0x41)))
            // The song has one order.
            assertTrue(ok(Cell(57, lead, SccRules.FX_JUMP, 0)))
            assertFalse(ok(Cell(57, lead, SccRules.FX_JUMP, 1)))
            assertTrue(ok(Cell(57, lead, SccRules.FX_TEST, 0xFF)))
            assertTrue(ok(Cell(57, lead, SccRules.FX_DELAY, 0xFF)))
            // Every effect column the pattern grid offers loads.
            val full = (0 until system.effectColumns(0)).fold(Cell(57, lead)) { c, i -> c.withEffect(i, SccRules.FX_VOLUME, 0x0C) }
            assertTrue(ok(full, 4))
        }
        // The effect codes the player knows, and only those.
        val known = setOf(0, 1, 2, 3, 4, 10, 11, 12, 13, 15, 16, 25, 28, 29, 32)
        assertEquals(known, SccRules.EFFECTS.map { it.code }.toSet())
        for (code in (0..EffectSpec.MAX_CODE).toSet() - known) assertFalse("code $code", cellLoads(0, Cell(57, lead, code, 1)))
    }

    @Test
    fun instrumentsWavesAndSettingsLoadOnlyWithinThePlayersRanges() {
        // The wave envelope names at least one wave, each 01-40.
        assertFalse(soundLoads(SccInstrument(wave = NesEnvelope())))
        assertTrue(soundLoads(SccInstrument(wave = NesEnvelope(listOf(1)))))
        assertTrue(soundLoads(SccInstrument(wave = NesEnvelope(listOf(64)))))
        assertFalse(soundLoads(SccInstrument(wave = NesEnvelope(listOf(65)))))
        assertFalse(soundLoads(SccInstrument(wave = NesEnvelope(listOf(0)))))
        // Values: volume 0..15, arpeggio and pitch -64..63.
        assertTrue(soundLoads(SccInstrument(volume = NesEnvelope(listOf(0, 15)))))
        assertFalse(soundLoads(SccInstrument(volume = NesEnvelope(listOf(16)))))
        assertFalse(soundLoads(SccInstrument(volume = NesEnvelope(listOf(-1)))))
        assertTrue(soundLoads(SccInstrument(arpeggio = NesEnvelope(listOf(-64, 63)))))
        assertFalse(soundLoads(SccInstrument(arpeggio = NesEnvelope(listOf(64)))))
        assertFalse(soundLoads(SccInstrument(arpeggio = NesEnvelope(listOf(-65)))))
        assertTrue(soundLoads(SccInstrument(pitch = NesEnvelope(listOf(-64, 63)))))
        assertFalse(soundLoads(SccInstrument(pitch = NesEnvelope(listOf(-65)))))
        assertFalse(soundLoads(SccInstrument(pitch = NesEnvelope(listOf(64)))))
        // Release points: volume only, at step 1..length-1, after the loop.
        assertFalse(soundLoads(SccInstrument(arpeggio = NesEnvelope(listOf(0, 1), release = 1))))
        assertTrue(soundLoads(SccInstrument(volume = NesEnvelope(listOf(15, 0), release = 1))))
        assertFalse(soundLoads(SccInstrument(volume = NesEnvelope(listOf(15, 0), release = 0))))
        assertFalse(soundLoads(SccInstrument(volume = NesEnvelope(listOf(15, 0), release = 2))))
        assertFalse(soundLoads(SccInstrument(volume = NesEnvelope(listOf(15), release = 1))))
        assertTrue(soundLoads(SccInstrument(volume = NesEnvelope(listOf(15, 9, 0), loop = 1, release = 2))))
        assertFalse(soundLoads(SccInstrument(volume = NesEnvelope(listOf(15, 9, 0), loop = 2, release = 2))))
        // Loop points inside the envelope; an empty envelope has neither point.
        assertTrue(soundLoads(SccInstrument(volume = NesEnvelope(listOf(15, 9), loop = 1))))
        assertFalse(soundLoads(SccInstrument(volume = NesEnvelope(listOf(15, 9), loop = 2))))
        assertTrue(soundLoads(SccInstrument(pitch = NesEnvelope())))
        assertFalse(soundLoads(SccInstrument(pitch = NesEnvelope(loop = 0))))
        // At most 256 steps.
        assertTrue(soundLoads(SccInstrument(volume = NesEnvelope(List(256) { 15 }, loop = 3, release = 255))))
        assertFalse(soundLoads(SccInstrument(volume = NesEnvelope(List(257) { 15 }))))
        // Waves: exactly 32 samples of -128..127.
        assertTrue(settingsLoad { it.withWave(3, SccWave("edge", List(32) { i -> if (i % 2 == 0) -128 else 127 })) })
        assertFalse(settingsLoad { it.withWave(3, SccWave("short", List(31) { 0 })) })
        assertFalse(settingsLoad { it.withWave(3, SccWave("long", List(33) { 0 })) })
        assertFalse(settingsLoad { it.withWave(3, SccWave("loud", List(32) { 128 })) })
        assertFalse(settingsLoad { it.withWave(3, SccWave("low", List(32) { -129 })) })
        assertTrue(SccWave("edge", List(32) { -128 }).problem() == null)
        assertNotNull(SccWave("loud", List(32) { 128 }).problem())
        // Multispeed 1-8; exactly 64 instrument slots and 64 waves.
        assertTrue(settingsLoad { it.copy(multispeed = 8) })
        assertFalse(settingsLoad { it.copy(multispeed = 9) })
        assertFalse(settingsLoad { it.copy(multispeed = 0) })
        assertFalse(settingsLoad { it.copy(instruments = it.instruments.drop(1)) })
        assertFalse(settingsLoad { it.copy(waves = it.waves.drop(1)) })
        // A song of the other chip is not this system's; on its own system it loads.
        assertFalse(SccSystem.errors(plus(SccSystem.blank())).isEmpty())
        assertTrue(SccPlusSystem.errors(plus(SccSystem.blank())).isEmpty())
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
        assertFalse(loads(song))
        assertThrows(IllegalArgumentException::class.java) { SccLowering.lower(song) }
        // Fewer orders of the same patterns fit, load and lower.
        val fewer = song.copy(orders = listOf(0, 1))
        assertTrue(SccLowering.dataSize(fewer) <= SccPlayerImage.DATA_CAPACITY)
        assertTrue(loads(fewer))
        assertTrue(lowers(fewer))
    }

    @Test
    fun auditionRefusesWhatCannotSound() {
        for (system in systems) {
            val song = system.blank()
            fun plays(channel: Int, note: Int, instrument: Int, fine: Int = 0, s: Song = song) =
                system.engine.auditionProblem(s, channel, note, instrument, fine) == null
            assertTrue(plays(0, 57, lead))
            assertTrue(plays(4, 9, kick, -128))
            // Notes A-0..B-7, fine pitch -128..127, channels SCC 1-5.
            assertTrue(plays(0, 95, lead, 127))
            assertFalse(plays(0, 8, lead))
            assertFalse(plays(0, 96, lead))
            assertFalse(plays(0, 57, lead, 128))
            assertFalse(plays(0, 57, lead, -129))
            assertFalse(plays(5, 57, lead))
            assertFalse(plays(-1, 57, lead))
            // Instruments 01-40 that are filled and load; repairing the instrument lets it play.
            assertFalse(plays(0, 57, 0))
            assertFalse(plays(0, 57, 40))
            assertFalse(plays(0, 57, 65))
            assertFalse(plays(0, 57, 20, s = withSound(song) { it.withInstrument(20, SccInstrument(wave = NesEnvelope())) }))
            assertTrue(plays(0, 57, 20, s = withSound(song) { it.withInstrument(20, SccInstrument(wave = NesEnvelope(listOf(1)))) }))
        }
    }

    // ---- warnings ------------------------------------------------------------------------------------------

    private fun warns(song: Song) = song.system.warnings(song).isNotEmpty()

    /** The shared-state walk after row [row] of order [order]: who holds the SCC's shared wave RAM and why. */
    private fun walked(song: Song, row: Int, order: Int = 0): SccWalk = SccSharedState.walk(song, order, row)

    private fun claim(channel: Int, row: Int, slot: Int, wave: Int, byNote: Boolean, order: Int = 0) =
        SccWalk.Claim(channel, order, row, slot, wave, byNote)

    @Test
    fun sharedWaveConflictsWarnOnTheSccUntilTheirCauseClears() {
        fun warnings(song: Song) = song.system.warnings(song)
        // Saw pluck on SCC 4 and square echo on SCC 5 at once: SCC 5 claims last, so both play its square.
        val clash = with(blankWith(3, 0, Cell(48, pluck)), 0, 4, 0, Cell(55, echo))
        val atClash = walked(clash, 0)
        assertTrue(atClash.keyed[3] && atClash.keyed[4])
        assertEquals(listOf(SccPresets.SAW), atClash.waveSet(3))
        assertEquals(listOf(SccPresets.SQUARE), atClash.waveSet(4))
        assertEquals(claim(4, 0, echo, SccPresets.SQUARE, byNote = true), atClash.claim)
        assertEquals(SccPresets.SQUARE, atClash.held)
        // Both stay keyed to the pattern's end: one warning for the stretch, not one per row.
        assertEquals(1, warnings(clash).size)
        // The SCC+ has a RAM per channel: nothing is claimed, nothing warns.
        assertFalse(warns(plus(clash)))
        assertNull(walked(plus(clash), 0).claim)
        assertEquals(0, SccSharedState.sharedWave(plus(clash), 0, 0))
        // The same instrument on both: one wave, no conflict.
        assertFalse(warns(with(blankWith(3, 0, Cell(48, organ)), 0, 4, 0, Cell(52, organ))))
        // Alternating phrases: SCC 4's pluck ends with OFF (no release point: keyed off) before SCC 5 enters.
        var alternating = with(blankWith(3, 0, Cell(48, pluck)), 0, 3, 4, Cell(Cell.CUT))
        alternating = with(alternating, 0, 4, 8, Cell(55, echo))
        assertFalse(walked(alternating, 4).keyed[3])
        assertFalse(warns(alternating))
        // An OFF on a note whose release ends above 0 only releases it: still keyed when SCC 5 enters at row 8.
        var released = withSustain(with(blankWith(3, 0, Cell(48, sustain)), 0, 3, 4, Cell(Cell.CUT)))
        released = with(released, 0, 4, 8, Cell(55, echo))
        assertTrue(walked(released, 4).keyed[3])
        assertFalse(walked(released, 7).keyed[4])
        assertTrue(walked(released, 8).keyed[3] && walked(released, 8).keyed[4])
        assertEquals(claim(4, 8, echo, SccPresets.SQUARE, byNote = true), walked(released, 8).claim)
        assertEquals(1, warnings(released).size)
        // A second OFF keys the released note off before SCC 5 enters: the conflict clears.
        val keyedOff = with(released, 0, 3, 5, Cell(Cell.CUT))
        assertFalse(walked(keyedOff, 5).keyed[3])
        assertFalse(warns(keyedOff))
        // A release that plays its final 0 keys the note off at that tick: Lead's OFF at row 4 reaches its 0 four ticks
        // later, inside row 4 (speed 6), so SCC 5's note at row 8 meets no conflict.
        var faded = with(blankWith(3, 0, Cell(48, lead)), 0, 3, 4, Cell(Cell.CUT))
        faded = with(faded, 0, 4, 8, Cell(55, echo))
        assertTrue(walked(faded, 3).keyed[3])
        assertFalse(walked(faded, 4).keyed[3])
        assertFalse(warns(faded))
        // A W lock on one note counts: Organ on both, but SCC 5's note plays the saw.
        val locked = with(blankWith(3, 0, Cell(48, organ)), 0, 4, 0, Cell(52, organ, SccRules.FX_WAVE, SccPresets.SAW))
        assertEquals(listOf(SccPresets.SAW), walked(locked, 0).waveSet(4))
        assertEquals(claim(4, 0, organ, SccPresets.SAW, byNote = true), walked(locked, 0).claim)
        assertTrue(warns(locked))
        // Two wave numbers with the same samples are the same sound.
        val twin = withSound(with(blankWith(3, 0, Cell(48, pluck)), 0, 4, 0, Cell(55, 20))) {
            it.withWave(30, SccWave("saw copy", SccWaveShapes.saw())).withInstrument(20, SccInstrument("twin", wave = NesEnvelope(listOf(30))))
        }
        assertNotEquals(walked(twin, 0).waveSet(3), walked(twin, 0).waveSet(4))
        assertFalse(warns(twin))
        // A wave envelope's waves are compared as a set: Morph's four against Organ's one.
        val envelope = with(blankWith(3, 0, Cell(48, morph)), 0, 4, 0, Cell(55, organ))
        assertEquals(listOf(SccPresets.SINE, SccPresets.TRIANGLE, SccPresets.SAW, SccPresets.SQUARE), walked(envelope, 0).waveSet(3))
        assertTrue(warns(envelope))
    }

    @Test
    fun sharedWaveWarningsIncludeReleaseOverlapInsideARow() {
        // Consumer APIs only: this regression must also compile against the pre-fix row walk.
        // Release at row 4 tick 4 = absolute tick 28; its final 0 is tick 31 = row 5 tick 1.
        fun song(tailChannel: Int, stepping: Boolean, delay: Int = 0, cut: Int = 4): Song {
            val tail = SccInstrument("tail", volume = NesEnvelope(listOf(15, 12, 8, 4, 0), release = 1),
                wave = NesEnvelope(if (stepping) listOf(1, 2) else listOf(1), loop = 0))
            val steady = SccInstrument("steady", volume = NesEnvelope(listOf(15)), wave = NesEnvelope(listOf(3)))
            var s = withSound(SccSystem.blank().copy(ticksPerRow = 6)) {
                it.withInstrument(20, tail).withInstrument(21, steady)
                    .withWave(1, SccWave("positive", List(32) { 64 }))
                    .withWave(2, SccWave("negative", List(32) { -64 }))
                    .withWave(3, SccWave("alternating", List(32) { i -> if (i % 2 == 0) 64 else -64 }))
            }
            s = with(s, 0, tailChannel, 0, Cell(48, 20))
            s = with(s, 0, tailChannel, 4, Cell(effect = SccRules.FX_CUT, param = cut))
            return with(s, 0, 7 - tailChannel, 5, Cell(55, 21, SccRules.FX_DELAY, delay))
        }
        for (tailChannel in 3..4) for (stepping in listOf(false, true)) {
            val overlap = song(tailChannel, stepping)
            assertEquals("one tick of overlap, channel $tailChannel, stepping $stepping", 1, overlap.system.warnings(overlap).size)
            val independent = plus(overlap)
            assertTrue(independent.system.warnings(independent).isEmpty())
            assertNull(independent.system.sharedState(independent, 0, 5))
            // G01 starts on the final-0 tick, G02 after it: neither voice overlaps an update of the tail.
            for (delay in 1..2) {
                val delayed = song(tailChannel, stepping, delay)
                assertTrue("G0$delay after release", delayed.system.warnings(delayed).isEmpty())
                assertEquals("W03", delayed.system.sharedState(delayed, 0, 5)!!.tags[3])
            }
            // Starting release a tick earlier puts its terminal 0 at row 5 tick 0, before the shared-wave update.
            val terminalZero = song(tailChannel, stepping, cut = 3)
            assertTrue(terminalZero.system.warnings(terminalZero).isEmpty())
            assertEquals("W03", terminalZero.system.sharedState(terminalZero, 0, 5)!!.tags[3])
            // A second OFF at tick 0 also ends the tail before the entering voice's update.
            val secondOff = with(overlap, 0, tailChannel, 5, Cell(Cell.CUT))
            assertTrue(secondOff.system.warnings(secondOff).isEmpty())
            assertEquals("W03", secondOff.system.sharedState(secondOff, 0, 5)!!.tags[3])
            // Without a release point the first OFF cuts at tick 0 as well.
            val noRelease = withSound(secondOff) {
                it.withInstrument(20, SccInstrument("held", volume = NesEnvelope(listOf(15)),
                    wave = NesEnvelope(if (stepping) listOf(1, 2) else listOf(1), loop = 0)))
            }
            val firstOff = with(noRelease, 0, tailChannel, 4, Cell())
            assertTrue(firstOff.system.warnings(firstOff).isEmpty())
            assertEquals("W03", firstOff.system.sharedState(firstOff, 0, 5)!!.tags[3])
        }
        // The tail may take RAM back in the overlap tick. Its ending does not restore the steady voice's wave:
        // preserve the uncertainty in subsequent empty rows, until a fresh definite claim replaces it.
        val uncertain = song(4, stepping = true)
        for (row in 5..6) {
            val state = uncertain.system.sharedState(uncertain, 0, row)!!
            assertEquals("W03+", state.tags[3])
            assertEquals(state.tags[3], state.tags[4])
        }
        val reclaimed = with(uncertain, 0, 3, 7, Cell(55, 21))
        assertEquals("W03", reclaimed.system.sharedState(reclaimed, 0, 7)!!.tags[3])
    }

    @Test
    fun testRegisterWarningsAndFreezesDependOnTheBits() {
        fun song(param: Int, system: KonamiSccSystem = SccSystem) = blankWith(0, 0, Cell(57, lead, SccRules.FX_TEST, param), system)
        // Restart (bit 5) and normal operation are silent; frequency modes, unknown bits and freezes warn.
        for (system in systems) {
            assertFalse(warns(song(0x20, system)))
            assertFalse(warns(song(0x00, system)))
            for (bits in listOf(0x01, 0x02, 0x04, 0x40, 0x80)) assertTrue("T%02X".format(bits), warns(song(bits, system)))
        }
        // Bit 6 freezes every wave RAM; bit 7 freezes the SCC's shared one, which the SCC+ does not have.
        assertTrue(walked(song(0x40), 0).frozen)
        assertTrue(walked(song(0x40, SccPlusSystem), 0).frozen)
        assertTrue(walked(song(0x80), 0).frozen)
        assertFalse(walked(song(0x80, SccPlusSystem), 0).frozen)
        assertFalse(walked(song(0x20), 0).frozen)
    }

    @Test
    fun otherWarningsAppearWithTheirCauseAndClearWithIt() {
        // Wxx on a row where nothing sounds; on a sounding note it is fine.
        assertTrue(warns(blankWith(0, 3, Cell(effect = SccRules.FX_WAVE, param = 4))))
        assertFalse(warns(with(blankWith(0, 0, Cell(57, lead)), 0, 0, 3, Cell(effect = SccRules.FX_WAVE, param = 4))))
        // After OFF on a note without a release point nothing sounds, so the W warns; on SCC 4 it still claims the RAM.
        var afterOff = with(blankWith(3, 0, Cell(57, pluck)), 0, 3, 2, Cell(Cell.CUT))
        afterOff = with(afterOff, 0, 3, 3, Cell(effect = SccRules.FX_WAVE, param = 4))
        assertTrue(warns(afterOff))
        val w = walked(afterOff, 3)
        assertFalse(w.keyed[3])
        assertEquals(claim(3, 3, pluck, SccPresets.SQUARE, byNote = false), w.claim)
        assertEquals(SccPresets.SQUARE, w.held)
        // A note delay the speed (6) never reaches; a shorter one is fine.
        assertTrue(warns(blankWith(0, 0, Cell(48, lead, SccRules.FX_DELAY, 6))))
        assertFalse(warns(blankWith(0, 0, Cell(48, lead, SccRules.FX_DELAY, 5))))
        // An order's first note without an instrument.
        assertTrue(warns(blankWith(0, 4, Cell(48))))
        assertFalse(warns(blankWith(0, 4, Cell(48, lead))))
        // Notes above G#6, where the 12-bit divider's error grows.
        assertTrue(warns(blankWith(0, 0, Cell(95, lead))))
        assertTrue(warns(blankWith(0, 0, Cell(81, lead))))
        assertFalse(warns(blankWith(0, 0, Cell(80, lead))))
        assertEquals(19.2, SccRules.cents(95), 0.05)
        assertEquals(27, SccPlayerImage.period(95 * 64))
        assertEquals(1.6, SccRules.cents(57), 0.05)
        assertEquals(253, SccPlayerImage.period(57 * 64))
    }

    @Test
    fun releasesEndAtTheirFinalZeroAndPortamentoGlidesOnlyIntoHeldNotes() {
        // Pad's release (from step 8) reaches its final 0 nine ticks after the OFF: OFF at row 1 tick 0 ends at row 2
        // tick 3 (speed 6), so the note is keyed after row 1 and keyed off after row 2.
        val padded = with(blankWith(3, 0, Cell(48, pad)), 0, 3, 1, Cell(Cell.CUT))
        assertTrue(walked(padded, 1).keyed[3])
        assertFalse(walked(padded, 2).keyed[3])
        // A release ending above 0 never keys the note off by itself.
        val held = withSustain(with(blankWith(3, 0, Cell(48, sustain)), 0, 3, 1, Cell(Cell.CUT)))
        assertTrue(walked(held, 63).keyed[3])

        // 3xx glides into a held note: no key-on, the claim stays the first note's.
        val glide = with(blankWith(3, 0, Cell(48, lead, SccRules.FX_PORTAMENTO, 0x10)), 0, 3, 3, Cell(55, lead))
        assertEquals(claim(3, 0, lead, SccPresets.PULSE_25, byNote = true), walked(glide, 3).claim)
        // After the release has ended (Lead's OFF at row 2 reaches its final 0 in that row) the next note keys on and claims.
        val afterRelease = with(glide, 0, 3, 2, Cell(Cell.CUT))
        assertFalse(walked(afterRelease, 2).keyed[3])
        assertEquals(claim(3, 3, lead, SccPresets.PULSE_25, byNote = true), walked(afterRelease, 3).claim)
        // During an audible release (still keyed) the next note keys on too rather than gliding.
        var duringRelease = with(blankWith(3, 0, Cell(48, sustain, SccRules.FX_PORTAMENTO, 0x10)), 0, 3, 2, Cell(Cell.CUT))
        duringRelease = withSustain(with(duringRelease, 0, 3, 3, Cell(55, sustain)))
        assertTrue(walked(duringRelease, 2).keyed[3])
        assertEquals(claim(3, 3, sustain, SccPresets.PULSE_25, byNote = true), walked(duringRelease, 3).claim)

        // Wxx after a release has ended finds no sounding note; during a release ending above 0 it still has one.
        fun waveAfterOff(slot: Int) = withSustain(with(with(blankWith(0, 0, Cell(57, slot)), 0, 0, 1, Cell(Cell.CUT)), 0, 0, 2,
            Cell(effect = SccRules.FX_WAVE, param = SccPresets.SQUARE)))
        assertFalse(walked(waveAfterOff(lead), 1).keyed[0])
        assertTrue(warns(waveAfterOff(lead)))
        assertTrue(walked(waveAfterOff(sustain), 1).keyed[0])
        assertFalse(warns(waveAfterOff(sustain)))
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
    fun multispeedSettingAdjustsClampsAndResets() {
        val song = SccPlusSystem.blank()
        val m = SccPlusSystem.songSettings.single()
        assertEquals(2, m.adjust(song, 1, 0).scc.multispeed)
        assertEquals(5, m.adjust(song, 0, 1).scc.multispeed)
        assertEquals(8, m.adjust(song, 0, 5).scc.multispeed)
        assertEquals(1, m.adjust(song, -3, 0).scc.multispeed)
        assertEquals(180.0, SccPlusSystem.ticksPerSecond(m.adjust(song, 2, 0)), 1e-9)
        assertEquals(song, m.reset(m.adjust(song, 2, 0)))
    }


    @Test
    fun sharedStateFollowsTheClaimsOfTheSharedRam() {
        var song = SccSystem.blank()
        song = with(song, 0, 3, 0, Cell(48, organ))
        song = with(song, 0, 4, 2, Cell(55, pluck))
        song = with(song, 0, 3, 4, Cell(effect = SccRules.FX_WAVE, param = 5))
        song = with(song, 0, 0, 6, Cell(effect = SccRules.FX_TEST, param = 0x40))
        song = with(song, 0, 4, 7, Cell(43, bass))
        song = with(song, 0, 0, 8, Cell(effect = SccRules.FX_TEST, param = 0x00))
        song = with(song, 0, 4, 10, Cell(55, morph))
        song = with(song, 0, 4, 12, Cell(effect = SccRules.FX_CUT, param = 3)) // S03 releases Morph; its final 0 falls on row 13
        song = with(song, 0, 4, 13, Cell(Cell.CUT)) // OFF on the released note keys it off
        song = with(song, 0, 3, 14, Cell(48, organ))
        song = with(song, 0, 4, 15, Cell(effect = SccRules.FX_WAVE, param = 3)) // W03 on the keyed-off SCC 5

        // Nothing claimed yet: nothing held, no lane tags.
        assertNull(walked(SccSystem.blank(), 0).claim)
        assertEquals(0, SccSharedState.sharedWave(SccSystem.blank(), 0, 0))
        assertEquals(List<String?>(5) { null }, SccSystem.sharedState(SccSystem.blank(), 0, 0)!!.tags)

        // SCC 4's note claims it; SCC 5 is silent.
        val first = walked(song, 0)
        assertEquals(claim(3, 0, organ, SccPresets.ORGAN, byNote = true), first.claim)
        assertEquals(SccPresets.ORGAN, first.held)
        assertFalse(first.keyed[4])

        // SCC 5's note takes it while SCC 4 still sounds with its own wave: the readout gains that fact.
        val second = walked(song, 2)
        assertEquals(claim(4, 2, pluck, SccPresets.SAW, byNote = true), second.claim)
        assertEquals(SccPresets.SAW, second.held)
        assertTrue(second.keyed[3])
        assertEquals(listOf(SccPresets.ORGAN), second.waveSet(3))

        // A W on SCC 4's sounding note re-claims the RAM (a wave change, not a note).
        val change = walked(song, 4)
        assertEquals(claim(3, 4, organ, SccPresets.PULSE_25, byNote = false), change.claim)
        assertEquals(SccPresets.PULSE_25, change.held)

        // Frozen by T40: SCC 5's claim does not reach the RAM until T00.
        val frozen = walked(song, 7)
        assertTrue(frozen.frozen)
        assertEquals(0x40, frozen.test)
        assertEquals(claim(4, 7, bass, SccPresets.BASS, byNote = true), frozen.claim)
        assertEquals(SccPresets.PULSE_25, frozen.held)
        val thawed = walked(song, 8)
        assertFalse(thawed.frozen)
        assertEquals(SccPresets.BASS, thawed.held)
        assertEquals(SccPresets.BASS, SccSharedState.sharedWave(song, 0, 8))

        // A wave envelope claims with its first wave and is marked as stepping while keyed.
        val stepping = walked(song, 10)
        assertEquals(claim(4, 10, morph, SccPresets.SINE, byNote = true), stepping.claim)
        assertEquals(SccPresets.SINE, stepping.held)
        assertTrue(stepping.keyed[4])
        // Released (its final 0 not yet reached), the note is still keyed and still owns the RAM.
        val released = walked(song, 12)
        assertTrue(released.keyed[4])
        assertEquals(stepping.claim, released.claim)
        // Keyed off, it keeps the RAM (same claim, same wave), but its steps no longer reach it: the readout changes.
        val keyedOff = walked(song, 13)
        assertFalse(keyedOff.keyed[4])
        assertEquals(stepping.claim, keyedOff.claim)
        assertEquals(SccPresets.SINE, keyedOff.held)
        // SCC 4's next note claims it; the keyed-off Morph does not take it back, so nothing steps any more.
        val organAgain = walked(song, 14)
        assertEquals(claim(3, 14, organ, SccPresets.ORGAN, byNote = true), organAgain.claim)
        assertEquals(SccPresets.ORGAN, organAgain.held)
        assertFalse(organAgain.keyed[4])
        // An explicit W on the keyed-off SCC 5 still claims it.
        val explicit = walked(song, 15)
        assertEquals(claim(4, 15, morph, SccPresets.SAW, byNote = false), explicit.claim)
        assertEquals(SccPresets.SAW, explicit.held)

        // The SCC+ shares nothing: no claims, no lane tags; only a test register that is not 0 shows.
        val p = plus(song)
        assertNull(SccPlusSystem.sharedState(p, 0, 2))
        assertNull(walked(p, 10).claim)
        assertEquals(0, SccSharedState.sharedWave(p, 0, 10))
        val test = SccPlusSystem.sharedState(p, 0, 6)!!
        assertEquals(List<String?>(5) { null }, test.tags)
        assertTrue(walked(p, 6).frozen)
    }

    // ---- tuning realisation ----------------------------------------------------------------------------------

    @Test
    fun fineWritesPxxInAFreeColumn() {
        val fine = SccSystem.fine
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
        assertEquals(SccPlayerImage.IMAGE_SIZE, SccPlusSystem.engine.imageSize)
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

    // ---- demos and effect readings -------------------------------------------------------------------------

    @Test
    fun demosAreCleanAndShowTheirChips() {
        val dusk = SccSystem.demo()
        val prism = SccPlusSystem.demo()
        for (demo in listOf(dusk, prism)) {
            val system = demo.system
            assertEquals(emptyList<String>(), system.errors(demo))
            assertEquals(emptyList<String>(), system.warnings(demo))
            val image = SccLowering.lower(demo)
            assertEquals(SccLowering.dataSize(demo), u16(image, SccPlayerImage.H_DATA_SIZE))
            assertTrue(SccLowering.dataSize(demo) < SccPlayerImage.DATA_CAPACITY)
        }
        // The SCC demo keeps SCC 4 and SCC 5 on one wave or alternating; the SCC+ demo's waves would clash on the SCC:
        // somewhere both are keyed with different-sounding waves, and the song check says so.
        val prismOnScc = prism.copy(sound = prism.scc.copy(variant = SccVariant.SCC))
        fun sounds(w: SccWalk, channel: Int) = w.waveSet(channel).map { prismOnScc.scc.wave(it)?.samples }.toSet()
        val clashes = prismOnScc.orders.indices.any { o ->
            (0 until prismOnScc.patterns[prismOnScc.orders[o]].length).any { r ->
                walked(prismOnScc, r, o).let { w -> w.keyed[3] && w.keyed[4] && sounds(w, 3) != sounds(w, 4) }
            }
        }
        assertTrue(clashes)
        assertTrue(warns(prismOnScc))
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
        assertTrue(SccPresets.WAVES.all { it.problem() == null })
        for (p in SccPresets.ALL) {
            assertNull(p.name, SccValidator.instrumentProblem(p.instrument))
            assertTrue(p.name, p.instrument.waves.all { it in 1..SccPresets.WAVES.size })
        }
    }
}
