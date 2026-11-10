package org.thortracker.system

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.VsuPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.ProjectCodec
import org.thortracker.model.ProjectFormatException
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.vsu.VsuDemoSong
import org.thortracker.system.vsu.VsuFreqMod
import org.thortracker.system.vsu.VsuInstrument
import org.thortracker.system.vsu.VsuPitch
import org.thortracker.system.vsu.VsuPresets
import org.thortracker.system.vsu.VsuRules
import org.thortracker.system.vsu.VsuSystem
import org.thortracker.system.vsu.VsuVgm
import org.thortracker.system.vsu.VsuVgmRenderer
import org.thortracker.system.vsu.VsuVgmTags
import org.thortracker.system.vsu.vsu

/** The Virtual Boy model: project files, validation per lane, warnings, pitch, presets, the VGM seam and shared state. */
class VsuModelTest {
    @After
    fun noRenderer() {
        VsuVgm.renderer = null
    }

    private fun with(song: Song, order: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun blankWith(channel: Int, row: Int, cell: Cell) = with(VsuSystem.blank(), 0, channel, row, cell)

    private fun problem(channel: Int, cell: Cell): String? = VsuSystem.cellProblem(VsuSystem.blank(), channel, cell)

    private fun fx(code: Int, param: Int, note: Int = Cell.EMPTY, instrument: Int = 0) = Cell(note, instrument, code, param)

    // ---- project files ------------------------------------------------------------------------------------

    @Test
    fun systemIsRegisteredWithSixLanes() {
        assertEquals(VsuSystem, SoundSystems.of("vb-vsu"))
        assertEquals(listOf("Wave 1", "Wave 2", "Wave 3", "Wave 4", "Wave 5", "Noise"), VsuSystem.channels.map { it.name })
        assertTrue(VsuSystem.channels.take(5).all { it.tonal && it.kind == ChannelKind.WAVE })
        assertFalse(VsuSystem.channels[5].tonal)
        assertEquals(listOf(64), VsuSystem.banks.map { it.slots })
        assertEquals(VsuPlayerImage.IMAGE_SIZE, VsuSystem.engine.imageSize)
        assertEquals(12000, VsuSystem.engine.seekTicks)
        assertEquals(50.0, VsuSystem.tickHz, 0.0)
        // Other systems' files are unchanged by the new system.
        for (system in listOf(SoundSystems.of("gb-huge"), SoundSystems.of("opl3-fm"))) {
            val song = system.demo()
            assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
        }
    }

    @Test
    fun songsRoundTrip() {
        for (song in listOf(VsuSystem.blank(), VsuSystem.demo(), VsuDemoSong.shortest())) {
            val text = ProjectCodec.encode(song)
            assertTrue(text.contains("\"backend\":\"vb-vsu\",\"backendVersion\":1"))
            val back = ProjectCodec.decode(text)
            assertEquals(song, back)
            assertEquals(text, ProjectCodec.encode(back))
        }
    }

    /** The project text of a blank song with its `sound` object replaced by [f]'s. */
    private fun withSound(f: (JsonObject) -> JsonElement): String {
        val root = Json.parseToJsonElement(ProjectCodec.encode(VsuSystem.blank())).jsonObject
        val song = root.getValue("song").jsonObject
        val sound = song.getValue("sound").jsonObject
        return JsonObject(root + ("song" to JsonObject(song + ("sound" to f(sound))))).toString()
    }

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private fun ints(values: List<Int>) = JsonArray(values.map { JsonPrimitive(it) })

    private fun waves(change: (List<List<Int>>) -> List<List<Int>>) = withSound { s ->
        val waves = VsuSystem.blank().vsu.waves
        s.with("waves", JsonArray(change(waves).map { ints(it) }))
    }

    private fun modulation(values: List<Int>) = withSound { s -> s.with("modulation", ints(values)) }

    private fun firstInstrument(change: (JsonObject) -> JsonObject) = withSound { s ->
        val list = s.getValue("instruments").jsonArray
        s.with("instruments", JsonArray(listOf(change(list[0].jsonObject)) + list.drop(1)))
    }

    @Test
    fun decodingIsStrictAtTheBoundaries() {
        fun accepts(text: String) = ProjectCodec.decode(text)
        fun rejects(text: String) = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(text) }
        // Wave samples 0..63, exactly 5 waves of 32.
        accepts(waves { w -> w.mapIndexed { i, s -> if (i == 0) listOf(63) + s.drop(1) else s } })
        rejects(waves { w -> w.mapIndexed { i, s -> if (i == 0) listOf(64) + s.drop(1) else s } })
        rejects(waves { w -> w.mapIndexed { i, s -> if (i == 0) listOf(-1) + s.drop(1) else s } })
        rejects(waves { w -> w.mapIndexed { i, s -> if (i == 0) s + 0 else s } })
        rejects(waves { w -> w.take(4) })
        // Modulation −128..127, exactly 32.
        accepts(modulation(List(32) { if (it == 0) -128 else 127 }))
        rejects(modulation(List(32) { if (it == 0) 128 else 0 }))
        rejects(modulation(List(32) { if (it == 0) -129 else 0 }))
        rejects(modulation(List(31) { 0 }))
        rejects(withSound { s -> JsonObject(s - "modulation") })
        // Exactly 64 slots; instrument fields in range; no unknown keys.
        rejects(withSound { s -> s.with("instruments", JsonArray(s.getValue("instruments").jsonArray.drop(1))) })
        accepts(firstInstrument { it.with("level", JsonPrimitive(15)) })
        rejects(firstInstrument { it.with("level", JsonPrimitive(16)) })
        rejects(firstInstrument { it.with("length", JsonPrimitive(32)) })
        rejects(firstInstrument { it.with("wave", JsonPrimitive(5)) })
        rejects(firstInstrument { it.with("noiseTap", JsonPrimitive(8)) })
        rejects(firstInstrument { it.with("colour", JsonPrimitive(1)) })
        // A third effect column is not a VSU cell.
        val three = blankWith(0, 0, Cell(57, 1).withEffect(2, VsuRules.FX_LEVEL, 3))
        assertNotNull(VsuSystem.structuralErrors(three).firstOrNull())
        // Backend data from a newer version is refused.
        rejects(ProjectCodec.encode(VsuSystem.blank()).replace("\"backendVersion\":1", "\"backendVersion\":2"))
    }

    @Test
    fun instrumentFieldLimits() {
        assertNull(VsuInstrument(level = 15, interval = 7, length = 31, wave = 4, noiseTap = 7).problem())
        assertNotNull(VsuInstrument(interval = 8).problem())
        assertNotNull(VsuInstrument(freqMod = VsuFreqMod(shift = 8)).problem())
        assertNotNull(VsuInstrument(freqMod = VsuFreqMod(interval = 8)).problem())
        assertNotNull(VsuInstrument(sweep = org.thortracker.system.vsu.VsuSweep(97, 0, 1)).problem())
        assertNull(VsuInstrument(sweep = org.thortracker.system.vsu.VsuSweep(-96, 96, 255)).problem())
    }

    // ---- validation per lane --------------------------------------------------------------------------------

    @Test
    fun notesFollowEachLanesRange() {
        for (lane in 0..4) {
            assertNotNull("lane $lane", problem(lane, Cell(26, 1)))
            assertNull("lane $lane", problem(lane, Cell(27, 1)))
            assertNull("lane $lane", problem(lane, Cell(95, 1)))
        }
        assertNull(problem(5, Cell(0, 13)))
        assertNull(problem(5, Cell(95, 13)))
        assertNotNull(problem(5, Cell(96, 13)))
        // OFF is fine everywhere; an empty slot is not.
        assertNull(problem(0, Cell(Cell.CUT)))
        assertNotNull(problem(0, Cell(57, 40)))
        // The song check reports the same, with the place.
        assertTrue(VsuSystem.errors(blankWith(1, 3, Cell(26, 1))).single().contains("row 3"))
    }

    @Test
    fun effectsBelongToTheirLanes() {
        fun codes(lane: Int) = VsuSystem.effectsFor(lane).map { it.code }.toSet()
        for (lane in 0..3) {
            assertFalse(VsuRules.FX_SWEEP_MOD in codes(lane))
            assertFalse(VsuRules.FX_NOISE_TAP in codes(lane))
            assertTrue(VsuRules.FX_WAVE in codes(lane))
        }
        assertTrue(VsuRules.FX_SWEEP_MOD in codes(4) && VsuRules.FX_WAVE in codes(4) && VsuRules.FX_NOISE_TAP !in codes(4))
        assertTrue(VsuRules.FX_NOISE_TAP in codes(5) && VsuRules.FX_SWEEP_MOD !in codes(5) && VsuRules.FX_WAVE !in codes(5))
        for (lane in 0..5) assertEquals(2, VsuSystem.effectColumns(lane))
        // The loader's lane checks.
        assertNotNull(problem(0, fx(VsuRules.FX_SWEEP_MOD, 0x12)))
        assertNull(problem(4, fx(VsuRules.FX_SWEEP_MOD, 0xFF)))
        assertNotNull(problem(4, fx(VsuRules.FX_NOISE_TAP, 0x01)))
        assertNull(problem(5, fx(VsuRules.FX_NOISE_TAP, 0x07)))
        assertNotNull(problem(5, fx(VsuRules.FX_WAVE, 0x01)))
        // An undeclared effect in the second column is found too.
        assertNotNull(problem(0, Cell(57, 1).withEffect(1, VsuRules.FX_NOISE_TAP, 1)))
    }

    @Test
    fun effectParametersFollowTheLoader() {
        assertNull(problem(0, fx(VsuRules.FX_LEVEL, 0x0F)))
        assertNotNull(problem(0, fx(VsuRules.FX_LEVEL, 0x10)))
        assertNull(problem(0, fx(VsuRules.FX_LENGTH, 0x20)))
        assertNotNull(problem(0, fx(VsuRules.FX_LENGTH, 0x21)))
        assertNotNull(problem(5, fx(VsuRules.FX_NOISE_TAP, 0x08)))
        assertNotNull(problem(0, fx(VsuRules.FX_WAVE, 0x00)))
        assertNull(problem(0, fx(VsuRules.FX_WAVE, 0x01)))
        assertNull(problem(0, fx(VsuRules.FX_WAVE, 0x05)))
        assertNotNull(problem(0, fx(VsuRules.FX_WAVE, 0x06)))
        assertNotNull(problem(0, fx(VsuRules.FX_SPEED, 0x00)))
        assertNull(problem(0, fx(VsuRules.FX_SPEED, 0x01)))
        // One order: B00 is the last target.
        assertNull(problem(0, fx(VsuRules.FX_JUMP, 0x00)))
        assertNotNull(problem(0, fx(VsuRules.FX_JUMP, 0x01)))
        // Two columns are the most.
        assertNull(problem(0, Cell(57, 1).withEffect(1, VsuRules.FX_STEREO, 0x8F)))
        assertNotNull(problem(0, Cell(57, 1).withEffect(2, VsuRules.FX_STEREO, 0x8F)))
    }

    @Test
    fun demoAndShortestAreValid() {
        val demo = VsuSystem.demo()
        assertEquals(emptyList<String>(), VsuSystem.errors(demo))
        assertEquals("Crimson Stereoscope", demo.title)
        // Every lane plays and the demo shows what the chip has: stereo, locks on their lanes, a loop.
        val cells = demo.patterns.flatMap { p -> p.tracks.mapIndexed { lane, t -> t.map { lane to it } }.flatten() }
        for (lane in 0..5) assertTrue("lane $lane", cells.any { (l, c) -> l == lane && c.note >= 0 })
        fun uses(code: Int) = cells.any { (_, c) -> (0..c.more.size).any { c.effectCode(it) == code } }
        for (code in listOf(VsuRules.FX_STEREO, VsuRules.FX_LENGTH, VsuRules.FX_SWEEP_MOD, VsuRules.FX_NOISE_TAP, VsuRules.FX_WAVE)) assertTrue("$code", uses(code))
        assertTrue(demo.loopOrder in 1 until demo.orders.size)
        val shortest = VsuDemoSong.shortest()
        assertEquals(emptyList<String>(), VsuSystem.errors(shortest))
        assertEquals(1, shortest.orders.size)
        assertEquals(1, shortest.patterns[shortest.orders[0]].length)
    }

    @Test
    fun presetsAreValidAndANewSongStartsWithThem() {
        for (p in VsuPresets.ALL) assertNull(p.name, p.instrument.problem())
        for (w in VsuPresets.WAVES) assertTrue(w.name, w.samples.size == 32 && w.samples.all { it in 0..63 })
        for (m in VsuPresets.MODULATIONS) assertTrue(m.name, m.values.size == 32 && m.values.all { it in -128..127 })
        val sound = VsuSystem.blank().vsu
        assertEquals(VsuPresets.STARTERS.map { it.instrument }, sound.instruments.take(VsuPresets.STARTERS.size))
        assertTrue(sound.instruments.drop(VsuPresets.STARTERS.size).all { it == null })
        assertEquals(5, sound.waves.size)
        assertEquals(emptyList<String>(), VsuSystem.errors(VsuSystem.blank()))
        // Wave 5 and noise drum presets exist (sweep, modulation, several taps).
        val w5 = VsuPresets.ALL.filter { it.category == VsuPresets.Category.WAVE5 }.map { it.instrument.freqMod }
        assertTrue(w5.any { it.modulation } && w5.any { !it.modulation && !it.up } && w5.any { !it.modulation && it.up })
        assertTrue(VsuPresets.ALL.filter { it.category == VsuPresets.Category.NOISE }.map { it.instrument.noiseTap }.toSet().size >= 3)
    }

    // ---- warnings ---------------------------------------------------------------------------------------------

    private fun wave5Song(freqMod: VsuFreqMod, note: Int): Song {
        val base = VsuSystem.blank()
        val sound = base.vsu.withInstrument(20, VsuInstrument("Up", wave = 3, freqMod = freqMod))
        return with(base.copy(sound = sound), 0, VsuRules.SWEEP_LANE, 0, Cell(note, 20))
    }

    @Test
    fun wave5SweepOverflowIsWarnedEvenWithTheSweepOff() {
        // A-4 is F 1693: with shift 0, F + F = 3386 > 2047.
        val off = wave5Song(VsuFreqMod(on = false, up = true, shift = 0), 57)
        assertTrue(VsuSystem.warnings(off).any { it.contains("2047") })
        // Downward, modulation, or a note low enough: no stop.
        assertFalse(VsuSystem.warnings(wave5Song(VsuFreqMod(up = false, shift = 0), 57)).any { it.contains("2047") })
        assertFalse(VsuSystem.warnings(wave5Song(VsuFreqMod(on = true, modulation = true, up = true, interval = 3), 57)).any { it.contains("2047") })
        assertFalse(VsuSystem.warnings(wave5Song(VsuFreqMod(up = true, shift = 0), 27)).any { it.contains("2047") })
        // The threshold note: the first that stops, the one below does not.
        val lowest = VsuPitch.lowestStoppingNote(0)!!
        assertTrue(VsuPitch.sweepStops(VsuPitch.register(4, 64 * lowest), 0))
        assertFalse(VsuPitch.sweepStops(VsuPitch.register(4, 64 * (lowest - 1)), 0))
        // An Mxx lock with the up bit on a sound without it stops too.
        val locked = with(wave5Song(VsuFreqMod(), 57), 0, VsuRules.SWEEP_LANE, 0, Cell(57, 20, VsuRules.FX_SWEEP_MOD, 0x08))
        assertTrue(VsuSystem.warnings(locked).any { it.contains("2047") })
    }

    @Test
    fun locksThatDoNothingAreWarned() {
        // L on a row without a note.
        val lengthAlone = blankWith(0, 4, fx(VsuRules.FX_LENGTH, 0x05)).let { with(it, 0, 0, 0, Cell(57, 1)) }
        assertTrue(VsuSystem.warnings(lengthAlone).any { it.contains("row 4") && it.contains("L05") })
        assertFalse(VsuSystem.warnings(blankWith(0, 0, Cell(57, 1, VsuRules.FX_LENGTH, 0x05))).any { it.contains("L05") })
        // A wave lock where nothing sounds.
        assertTrue(VsuSystem.warnings(blankWith(1, 2, fx(VsuRules.FX_WAVE, 0x02))).any { it.contains("W02") })
        // A lock on an OFF row.
        val offLock = with(blankWith(0, 0, Cell(57, 1)), 0, 0, 2, Cell(Cell.CUT, 0, VsuRules.FX_LEVEL, 3))
        assertTrue(VsuSystem.warnings(offLock).any { it.contains("C03") })
        // A glide row (3xx on a sounding lane) does not key on: its wave lock is written at once (no warning, and the
        // lane now plays wave 2), its length lock does nothing (warned).
        var glide = with(blankWith(0, 0, Cell(57, 1, VsuRules.FX_PORTAMENTO, 0x10)), 0, 0, 4,
            Cell(60, 0, VsuRules.FX_WAVE, 0x02).withEffect(1, VsuRules.FX_LENGTH, 0x04))
        val glideWarnings = VsuSystem.warnings(glide)
        assertFalse(glideWarnings.any { it.contains("W02") })
        assertTrue(glideWarnings.any { it.contains("L04") && it.contains("glides") })
        assertEquals("W2", VsuSystem.sharedState(glide, 0, 4)!!.tags[0])
        glide = with(glide, 0, 0, 4, Cell(60, 0, VsuRules.FX_WAVE, 0x02))
        assertTrue(VsuSystem.warnings(glide).none { it.contains("row 4") })
        // A delay the speed never reaches.
        val delayed = blankWith(0, 0, Cell(57, 1, VsuRules.FX_DELAY, 6))
        assertTrue(VsuSystem.warnings(delayed).any { it.contains("G06") })
        assertFalse(VsuSystem.warnings(blankWith(0, 0, Cell(57, 1, VsuRules.FX_DELAY, 5))).any { it.contains("G05") })
        // A first note without an instrument.
        assertTrue(VsuSystem.warnings(blankWith(2, 0, Cell(57))).any { it.contains("no instrument") })
    }

    // ---- pitch ------------------------------------------------------------------------------------------------

    @Test
    fun periodTableAndRegistersMatchTheSpecification() {
        assertEquals(626238494, VsuPitch.q(0))
        assertEquals(313401976, VsuPitch.q(767))
        // A-4 = F 1693 = 440.14 Hz.
        assertEquals(1693, VsuPitch.register(0, 64 * 57))
        assertEquals(440.14, VsuPitch.hz(0, 64 * 57), 0.01)
        // Wave lanes clamp below D#2; Noise reaches C-0 (N = 956).
        assertEquals(VsuPitch.register(0, VsuPlayerImage.WAVE_PITCH_MIN), VsuPitch.register(0, 0))
        assertTrue(VsuPitch.register(0, VsuPlayerImage.WAVE_PITCH_MIN) >= 0)
        assertEquals(2048 - 956, VsuPitch.register(5, 0))
        assertTrue(VsuPitch.register(0, VsuPlayerImage.PITCH_MAX) <= 2047)
    }

    @Test
    fun finePitchRidesOnPxx() {
        val fine = VsuSystem.fine
        val cell = fine.write(Cell(57, 1), 57, 5)!!
        assertEquals(VsuRules.FX_FINE, cell.effect)
        assertEquals(0x85, cell.param)
        assertEquals(5, fine.of(cell))
        assertEquals(Cell(57, 1), fine.write(cell, 57, 0))
        // A free second column takes it; with both columns used there is no room.
        val stereo = Cell(57, 1, VsuRules.FX_STEREO, 0xFF)
        assertEquals(VsuRules.FX_FINE, fine.write(stereo, 57, 3)!!.effectCode(1))
        assertNull(fine.write(stereo.withEffect(1, VsuRules.FX_LEVEL, 3), 57, 3))
        assertEquals(0, VsuSystem.engine.fine(0, 57, 0))
        assertEquals(-12, VsuSystem.engine.fine(4, 57, -12))
    }

    // ---- playback seams ---------------------------------------------------------------------------------------

    @Test
    fun auditionChecksLaneRangeAndSlot() {
        val song = VsuSystem.blank()
        val e = VsuSystem.engine
        assertNull(e.auditionProblem(song, 0, 27, 1, 0))
        assertNotNull(e.auditionProblem(song, 0, 26, 1, 0))
        assertNull(e.auditionProblem(song, 5, 0, 13, 0))
        assertNotNull(e.auditionProblem(song, 0, 57, 40, 0))
        assertNotNull(e.auditionProblem(song, 6, 57, 1, 0))
        assertNotNull(e.auditionProblem(song, 0, 57, 1, 128))
        assertNull(e.auditionProblem(song, 0, 57, 1, -128))
        assertEquals(57, e.note(song, 3, 57, 1))
    }

    @Test
    fun patternLoopStartsWithTheOrdersSpeedAndJumpsHome() {
        val base = VsuSystem.blank()
        val second = Pattern.blank(6, Songs.ROWS, "B")
        val song = base.copy(patterns = base.patterns + second, orders = listOf(0, 1))
            .let { with(it, 0, 3, 5, fx(VsuRules.FX_SPEED, 0x03)) }
            .let { with(it, 1, 0, 7, fx(VsuRules.FX_JUMP, 0x00)) }
        assertEquals(3, VsuSystem.startSpeed(song, 1))
        val loop = VsuSystem.patternLoop(song, 1)
        assertEquals(listOf(1), loop.orders)
        assertEquals(3, loop.ticksPerRow)
        assertEquals(0, VsuSystem.jumpTarget(loop.patterns[1].tracks[0][7]))
        // A parked order plays alone: the song's speed.
        assertEquals(base.ticksPerRow, VsuSystem.startSpeed(song.copy(parkedOrders = listOf(1)), 1))
    }

    @Test
    fun vgmExportNeedsTheNativeRendererAndPassesTheImage() {
        val song = VsuSystem.demo()
        assertThrows(IllegalStateException::class.java) { VsuVgm.export(song) }
        var seen: Triple<Int, Int, VsuVgmTags>? = null
        VsuVgm.renderer = VsuVgmRenderer { image, maxTicks, tags -> seen = Triple(image.size, maxTicks, tags); byteArrayOf(1, 2) }
        assertTrue(VsuVgm.export(song).contentEquals(byteArrayOf(1, 2)))
        assertEquals(VsuPlayerImage.IMAGE_SIZE, seen!!.first)
        assertEquals(50 * 600, seen!!.second)
        assertEquals(VsuVgmTags(song.title, song.artist), seen!!.third)
        assertEquals("vgm", VsuSystem.exports.single().extension)
    }

    @Test
    fun sharedStateShowsTheWaveEachLanePlays() {
        // Wave 1: Square lead (wave 4); Wave 2: Square lead with W02; Wave 5: the vibrato lead; Noise: a tap lock.
        var song = blankWith(0, 0, Cell(57, 1))
        song = with(song, 0, 1, 0, Cell(57, 1, VsuRules.FX_WAVE, 0x02))
        song = with(song, 0, 4, 0, Cell(57, 8))
        song = with(song, 0, 5, 0, Cell(60, 14, VsuRules.FX_NOISE_TAP, 0x03))
        song = with(song, 0, 0, 4, Cell(Cell.CUT))
        val at0 = VsuSystem.sharedState(song, 0, 0)!!
        assertEquals("W4", at0.tags[0])
        assertEquals("W2", at0.tags[1])
        assertNull(at0.tags[2])
        assertTrue(at0.tags[4]!!.contains("mod"))
        assertEquals("tap 3", at0.tags[5])
        // A wave lock on a row without a note switches the sounding channel; OFF silences it.
        song = with(song, 0, 1, 2, fx(VsuRules.FX_WAVE, 0x05))
        assertEquals("W5", VsuSystem.sharedState(song, 0, 2)!!.tags[1])
        assertNull(VsuSystem.sharedState(song, 0, 4)!!.tags[0])
    }
}
