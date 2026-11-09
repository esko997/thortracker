package org.thortracker.system

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.PceLowering
import org.thortracker.backend.PcePlayerImage
import org.thortracker.editor.Selection
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.pce.PceDemoSong
import org.thortracker.system.pce.PceEnvelope
import org.thortracker.system.pce.PceInstrument
import org.thortracker.system.pce.PceKind
import org.thortracker.system.pce.PcePresets
import org.thortracker.system.pce.PceRules
import org.thortracker.system.pce.PceSample
import org.thortracker.system.pce.PceSamples
import org.thortracker.system.pce.PceSharedState
import org.thortracker.system.pce.PceSound
import org.thortracker.system.pce.PceStarterKit
import org.thortracker.system.pce.PceSystem
import org.thortracker.system.pce.PceValidator
import org.thortracker.system.pce.pce

/**
 * The PC Engine system's model: notes and ticks, project files, which songs, sounds and cells load (the loader's
 * rules, as the play/export gate and the editors' checks see them), warnings and what clears them, arrangement, song
 * settings, the shared-state walk, fine pitch and the player's pitch arithmetic, the lowering's header, records and
 * data layout (patterns, then deduplicated envelopes, then deduplicated samples), `.wav` import arithmetic, the starter
 * kit and the demo. The songs here use their own sounds, waves and samples (not the presets), so they do not depend on
 * the preset list.
 */
class PceModelTest {

    private val sine = PcePresets.WAVES[0].second
    private val square = PcePresets.WAVES[3].second

    /** Slot 01: a tone on wave 0 (volume [31]). */
    private val tone = PceInstrument("tone", PceKind.TONE, wave = 0)

    /** Slot 02: noise; its wave envelope is not lowered (noise has no waves). */
    private val hat = PceInstrument("hat", PceKind.NOISE, volume = PceEnvelope(listOf(20, 10, 0)), waves = PceEnvelope(listOf(1, 1)))

    /** Slot 03: sample 0, looping from frame 2, relative pitch; its volume [31] is slot 01's bytes. */
    private val loop = PceInstrument(
        "loop", PceKind.SAMPLE, sample = 0, root = 48, loop = true, loopStart = 2,
        pitch = PceEnvelope(listOf(1, 2)), pitchRelative = true,
    )

    /** Slot 05 (after an empty 04): a tone on wave 1 with a looping wave envelope; its arpeggio [31] shares slot 01's volume bytes. */
    private val shimmer = PceInstrument(
        "shimmer", PceKind.TONE, wave = 1,
        volume = PceEnvelope(listOf(31, 20), loop = 0),
        arpeggio = PceEnvelope(listOf(31)),
        waves = PceEnvelope(listOf(0, 1), loop = 1),
    )

    private val samples = listOf(PceSample("a", listOf(16, 17, 18, 19)), PceSample("b", listOf(16, 17, 18, 19)), PceSample("c", listOf(0, 31)))

    private fun base(): Song = PceSystem.blank().copy(
        sound = PceSound(
            instruments = List(PceSystem.SLOTS) { when (it) { 0 -> tone; 1 -> hat; 2 -> loop; 4 -> shimmer; else -> null } },
            waves = listOf(sine, square),
            samples = samples,
        ),
    )

    private fun with(song: Song, order: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun blankWith(channel: Int, row: Int, cell: Cell) = with(base(), 0, channel, row, cell)

    private fun withSound(f: (PceSound) -> PceSound) = base().let { it.copy(sound = f(it.pce)) }

    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF
    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)
    private fun u32(image: ByteArray, at: Int) = u16(image, at) or (u16(image, at + 2) shl 16)

    /** The numbers a readout shows, in order (its wording is the UI's). */
    private fun numbers(text: String) = Regex("[+-]?\\d+(?:\\.\\d+)?").findAll(text).map { it.value.toDouble() }.toList()

    // ---- notes and ticks ---------------------------------------------------------------------------------------

    @Test
    fun notesAndTicksAreThePlayers() {
        assertEquals(9..95, PceSystem.noteRange)
        assertEquals("A-0", PceSystem.noteName(9))
        assertEquals("A-4", PceSystem.noteName(57))
        assertEquals("B-7", PceSystem.noteName(95))
        // The player takes absolute notes; it ticks at the 60 Hz frame times the song's multispeed.
        assertEquals(57, PceSystem.engine.note(base(), 0, 57, 1))
        assertEquals(60.0, PceSystem.ticksPerSecond(base()), 1e-9)
        assertEquals(180.0, PceSystem.ticksPerSecond(withSound { it.copy(multispeed = 3) }), 1e-9)
    }

    @Test
    fun blankSongIsValidWithWavesAndPresets() {
        val blank = PceSystem.blank()
        assertEquals(emptyList<String>(), PceSystem.errors(blank))
        assertEquals(emptyList<String>(), PceSystem.warnings(blank))
        val sound = blank.pce
        assertEquals(PceSystem.SLOTS, sound.instruments.size)
        assertEquals(PcePresets.WAVES.map { it.second }, sound.waves)
        assertTrue(sound.waves.all { w -> w.size == 32 && w.all { it in 0..31 } && w.minOrNull() == 0 && w.maxOrNull() == 31 })
        assertEquals(PcePresets.ALL.size, sound.instruments.count { it != null })
        // Each tone preset plays the built-in wave equal to its own.
        PcePresets.ALL.forEachIndexed { i, p ->
            val ins = sound.instrument(i + 1)!!
            assertEquals(p.name, ins.name)
            if (p.category == PcePresets.Category.TONE) assertEquals(p.wave, sound.waves[ins.wave])
            else assertEquals(PceKind.NOISE, ins.kind)
        }
        assertTrue(PcePresets.ALL.any { it.category == PcePresets.Category.NOISE })
        assertTrue(sound.samples.isEmpty())
        assertEquals(listOf(1, 0xFF, 0, 0, 0), listOf(sound.multispeed, sound.master, sound.lfoFrequency, sound.lfoControl, sound.timerReload))
        assertEquals(emptyList<String>(), PceSystem.errors(base()))
        assertEquals("tone", PceSystem.instrumentName(base(), 0, 1))
        assertEquals(PceSystem.EMPTY_NAME, PceSystem.instrumentName(base(), 0, 4))
        assertNull(PceSystem.instrumentName(base(), 1, 1))
        // Thumbnails: a tone's wave (the first step of its wave envelope); none for noise and samples.
        assertEquals(sine.map { it / 31f }, PceSystem.instrumentShape(base(), 0, 1))
        assertEquals(sine.map { it / 31f }, PceSystem.instrumentShape(base(), 0, 5))
        assertNull(PceSystem.instrumentShape(base(), 0, 2))
        assertNull(PceSystem.instrumentShape(base(), 0, 3))
        // Selecting a tone sound points the Wave view at its wave.
        assertEquals(1, PceSystem.selectSound(Selection(instrument = 5, wave = 0), base()).wave)
        assertEquals(1, PceSystem.selectSound(Selection(instrument = 2, wave = 1), base()).wave)
    }

    // ---- project files -------------------------------------------------------------------------------------

    @Test
    fun projectFilesRoundTripSoundsWavesSamplesAndSharedState() {
        val cell = Cell(57, 1, PceRules.FX_VOLUME, 0x10).withEffect(1, PceRules.FX_PAN, 0xF0).withEffect(3, PceRules.FX_FINE, 0x90)
        val song = blankWith(0, 0, cell).let {
            it.copy(sound = it.pce.copy(multispeed = 2, master = 0x5A, lfoFrequency = 0x10, lfoControl = 0x82, timerReload = 3))
        }
        val text = ProjectCodec.encode(song)
        assertTrue(text.contains("\"backend\":\"pce-huc6280\",\"backendVersion\":1"))
        assertTrue(text.contains("[57,1,12,16,8,240,0,0,25,144]"))
        val back = ProjectCodec.decode(text)
        assertEquals(song, back)
        assertEquals(text, ProjectCodec.encode(back))
        val sound = back.pce
        assertEquals(listOf(2, 0x5A, 0x10, 0x82, 3), listOf(sound.multispeed, sound.master, sound.lfoFrequency, sound.lfoControl, sound.timerReload))
        assertEquals(loop, sound.instrument(3))
        assertEquals(shimmer, sound.instrument(5))
        assertEquals(samples, sound.samples)
        assertEquals(listOf(sine, square), sound.waves)
        for (s in listOf(PceSystem.blank(), PceSystem.demo(), PceDemoSong.shortest())) {
            assertEquals(s, ProjectCodec.decode(ProjectCodec.encode(s)))
        }
    }

    // ---- validation ----------------------------------------------------------------------------------------

    /** Whether [song] may play and export: the gate the menus apply ([PceSystem.errors] empty). Its reasons are UI text. */
    private fun loads(song: Song) = PceSystem.errors(song).isEmpty()

    /** Whether [cell] on [channel] (row 0 of [song]'s first order) loads; the editor's cell check and the song's gate agree. */
    private fun cellLoads(channel: Int, cell: Cell, song: Song = base()): Boolean {
        val placed = with(song, 0, channel, 0, cell)
        val accepted = PceSystem.cellProblem(placed, channel, cell) == null
        assertEquals("cell check and song gate on $cell, channel $channel", accepted, loads(placed))
        return accepted
    }

    /** Whether [ins] loads in slot 06 of [sound]; the instrument view's check and the song's gate agree. */
    private fun soundLoads(ins: PceInstrument, sound: PceSound = base().pce): Boolean {
        val song = base().copy(sound = sound.withInstrument(6, ins))
        val accepted = PceValidator.instrumentProblem(song.pce, 6) == null
        assertEquals("sound check and song gate on $ins", accepted, loads(song))
        return accepted
    }

    /** Whether the settings, waves and samples [f] gives the song load; the sound's own check and the song's gate agree. */
    private fun settingsLoad(f: (PceSound) -> PceSound): Boolean {
        val song = withSound(f)
        val accepted = song.pce.problem() == null
        assertEquals("sound settings check and song gate", accepted, loads(song))
        return accepted
    }

    @Test
    fun cellsLoadOnlyWhereThePlayerAcceptsThem() {
        // Noise sounds on PSG 5 and PSG 6 only; sample and tone sounds on any channel.
        for (ch in 0..3) assertFalse(cellLoads(ch, Cell(60, 2)))
        assertTrue(cellLoads(4, Cell(60, 2)))
        assertTrue(cellLoads(5, Cell(60, 2)))
        assertTrue(cellLoads(0, Cell(60, 3)))
        assertTrue(cellLoads(5, Cell(60, 5)))
        // Parameter ranges at their boundaries, in any effect column.
        assertFalse(cellLoads(0, Cell(60, 1).withEffect(2, PceRules.FX_VOLUME, 0x20)))
        assertTrue(cellLoads(0, Cell(60, 1).withEffect(2, PceRules.FX_VOLUME, 0x1F)))
        assertFalse(cellLoads(0, Cell(60, 1, PceRules.FX_LFO_CTRL, 0x04)))
        assertFalse(cellLoads(0, Cell(60, 1, PceRules.FX_LFO_CTRL, 0x84)))
        assertTrue(cellLoads(0, Cell(60, 1, PceRules.FX_LFO_CTRL, 0x03)))
        assertTrue(cellLoads(0, Cell(60, 1, PceRules.FX_LFO_CTRL, 0x83)))
        assertFalse(cellLoads(0, Cell(60, 1, PceRules.FX_TIMER, 0x80)))
        assertTrue(cellLoads(0, Cell(60, 1, PceRules.FX_TIMER, 0x7F)))
        // The song has waves 00-01 and one order.
        assertFalse(cellLoads(0, Cell(60, 1, PceRules.FX_WAVE, 0x02)))
        assertTrue(cellLoads(0, Cell(60, 1, PceRules.FX_WAVE, 0x01)))
        assertFalse(cellLoads(0, Cell(60, 1, PceRules.FX_JUMP, 0x01)))
        assertTrue(cellLoads(0, Cell(60, 1, PceRules.FX_JUMP, 0x00)))
        assertFalse(cellLoads(0, Cell(60, 1, PceRules.FX_SPEED, 0)))
        assertTrue(cellLoads(0, Cell(60, 1, PceRules.FX_SPEED, 1)))
        assertTrue(cellLoads(0, Cell(60, 1, PceRules.FX_LFO_FREQ, 0xFF)))
        assertTrue(cellLoads(0, Cell(60, 1, PceRules.FX_MASTER, 0x00)))
        // Every effect column the pattern grid offers loads.
        val full = (0 until PceSystem.effectColumns(0)).fold(Cell(60, 1)) { c, i -> c.withEffect(i, PceRules.FX_VOLUME, 0x10) }
        assertTrue(cellLoads(0, full))
        // The effect codes the player knows, and only those.
        val unknown = setOf(5, 6, 7, 9, 14, 17, 18, 19, 20, 23, 24, 26, 27, 30, 31, 34, 35)
        assertEquals((0..35).toSet() - unknown, PceRules.EFFECTS.map { it.code }.toSet())
        for (code in unknown) assertFalse("code $code", cellLoads(0, Cell(60, 1, code, 1)))
        // Notes A-0..B-7; sounds 01-40 that are filled.
        assertFalse(cellLoads(0, Cell(8, 1)))
        assertFalse(cellLoads(0, Cell(96, 1)))
        assertTrue(cellLoads(0, Cell(9, 1)))
        assertTrue(cellLoads(0, Cell(95, 1)))
        assertFalse(cellLoads(0, Cell(60, 4)))
        assertFalse(cellLoads(0, Cell(60, 65)))
        val top = withSound { it.withInstrument(PceSystem.SLOTS, tone) }
        assertTrue(cellLoads(0, Cell(60, PceSystem.SLOTS), top))
        // Wxx in a song without waves (which otherwise loads).
        val noWaves = withSound { it.copy(waves = emptyList(), instruments = List(PceSystem.SLOTS) { null }) }
        assertFalse(cellLoads(0, Cell(effect = PceRules.FX_WAVE, param = 0), noWaves))
        assertTrue(cellLoads(0, Cell(effect = PceRules.FX_MASTER, param = 0), noWaves))
    }

    @Test
    fun soundsWavesSamplesAndSettingsLoadOnlyWithinThePlayersRanges() {
        // Header values at their boundaries.
        assertTrue(settingsLoad { it.copy(multispeed = 8) })
        assertFalse(settingsLoad { it.copy(multispeed = 9) })
        assertFalse(settingsLoad { it.copy(multispeed = 0) })
        assertTrue(settingsLoad { it.copy(master = 0) })
        assertFalse(settingsLoad { it.copy(master = 0x100) })
        assertTrue(settingsLoad { it.copy(lfoControl = 0x83) })
        assertFalse(settingsLoad { it.copy(lfoControl = 4) })
        assertTrue(settingsLoad { it.copy(lfoFrequency = 255) })
        assertFalse(settingsLoad { it.copy(lfoFrequency = 256) })
        assertTrue(settingsLoad { it.copy(timerReload = 127) })
        assertFalse(settingsLoad { it.copy(timerReload = 128) })
        // Waves: 32 steps of 0-31, at most 64 of them.
        assertFalse(settingsLoad { it.copy(waves = listOf(sine.dropLast(1), square)) })
        assertTrue(settingsLoad { it.copy(waves = listOf(sine, List(32) { 31 })) })
        assertFalse(settingsLoad { it.copy(waves = listOf(sine, List(32) { 32 })) })
        assertTrue(settingsLoad { it.copy(waves = List(64) { sine }) })
        assertFalse(settingsLoad { it.copy(waves = List(65) { sine }) })
        // Samples: 1-65535 frames of 0-31, at most 64 samples.
        assertFalse(settingsLoad { it.copy(samples = samples + PceSample("x", emptyList())) })
        assertTrue(settingsLoad { it.copy(samples = samples + PceSample("x", listOf(0, 31))) })
        assertFalse(settingsLoad { it.copy(samples = samples + PceSample("x", listOf(32))) })
        assertFalse(settingsLoad { it.copy(samples = samples + PceSample("x", List(65536) { 16 })) })
        assertTrue(settingsLoad { it.copy(samples = List(64) { samples[0] }) })
        assertFalse(settingsLoad { it.copy(samples = List(65) { samples[0] }) })
        // Envelopes: at most 255 steps inside the kind's range; loop and release points inside, the release after the loop.
        assertTrue(soundLoads(tone.copy(pitch = PceEnvelope(List(255) { -128 }))))
        assertFalse(soundLoads(tone.copy(volume = PceEnvelope(List(256) { 31 }))))
        assertTrue(soundLoads(tone.copy(volume = PceEnvelope(listOf(0, 31)))))
        assertFalse(soundLoads(tone.copy(volume = PceEnvelope(listOf(32)))))
        assertTrue(soundLoads(tone.copy(arpeggio = PceEnvelope(listOf(-64, 63)))))
        assertFalse(soundLoads(tone.copy(arpeggio = PceEnvelope(listOf(64)))))
        assertFalse(soundLoads(tone.copy(arpeggio = PceEnvelope(listOf(-65)))))
        assertTrue(soundLoads(tone.copy(pitch = PceEnvelope(listOf(-128, 127)))))
        assertFalse(soundLoads(tone.copy(pitch = PceEnvelope(listOf(-129)))))
        assertFalse(soundLoads(tone.copy(pitch = PceEnvelope(listOf(128)))))
        assertTrue(soundLoads(tone.copy(volume = PceEnvelope(listOf(31, 20), loop = 1))))
        assertFalse(soundLoads(tone.copy(volume = PceEnvelope(listOf(31, 20), loop = 2))))
        assertTrue(soundLoads(tone.copy(volume = PceEnvelope(listOf(31, 20), release = 1))))
        assertFalse(soundLoads(tone.copy(volume = PceEnvelope(listOf(31, 20), release = 0))))
        assertTrue(soundLoads(tone.copy(volume = PceEnvelope(listOf(31, 20, 10), loop = 0, release = 1))))
        assertFalse(soundLoads(tone.copy(volume = PceEnvelope(listOf(31, 20, 10), loop = 1, release = 1))))
        assertTrue(soundLoads(tone.copy(arpeggio = PceEnvelope(emptyList()))))
        assertFalse(soundLoads(tone.copy(arpeggio = PceEnvelope(emptyList(), loop = 0))))
        // Root notes A-0..B-7.
        assertTrue(soundLoads(loop.copy(root = 9)))
        assertTrue(soundLoads(loop.copy(root = 95)))
        assertFalse(soundLoads(loop.copy(root = 8)))
        assertFalse(soundLoads(loop.copy(root = 96)))
        // References: a tone's wave and wave envelope steps among the song's waves (00-01); a noise sound's wave fields
        // are not lowered, so not checked.
        assertTrue(soundLoads(tone.copy(wave = 1)))
        assertFalse(soundLoads(tone.copy(wave = 2)))
        assertTrue(soundLoads(tone.copy(waves = PceEnvelope(listOf(0, 1)))))
        assertFalse(soundLoads(tone.copy(waves = PceEnvelope(listOf(0, 5)))))
        assertTrue(soundLoads(hat.copy(wave = 9, waves = PceEnvelope(listOf(9)))))
        assertFalse(soundLoads(tone, base().pce.copy(waves = emptyList())))
        assertTrue(soundLoads(hat, base().pce.copy(waves = emptyList(), instruments = List(PceSystem.SLOTS) { null })))
        // A sample sound's sample among the song's samples (00-02) and its loop start inside it ("a": 4 frames).
        assertTrue(soundLoads(loop.copy(sample = 2, loop = false)))
        assertFalse(soundLoads(loop.copy(sample = 3)))
        assertTrue(soundLoads(loop.copy(loopStart = 3)))
        assertFalse(soundLoads(loop.copy(loopStart = 4)))
        assertTrue(soundLoads(loop.copy(loop = false, loopStart = 4)))
        assertFalse(soundLoads(loop, base().pce.copy(samples = emptyList())))
    }

    @Test
    fun dataBudgetCountsSampleBytesOnce() {
        val big = List(PceSample.MAX_FRAMES) { it % 32 }
        val other = List(PceSample.MAX_FRAMES) { 31 - it % 32 }
        // Patterns and envelopes alone: 11 bytes (the samples follow them).
        val patternAndEnvelopes = PceLowering.dataSize(withSound { it.copy(samples = emptyList()) })
        assertEquals(11, patternAndEnvelopes)
        // Two identical samples are stored once and fit.
        val twice = withSound { it.copy(samples = listOf(PceSample("x", big), PceSample("y", big))) }
        assertTrue(loads(twice))
        assertEquals(patternAndEnvelopes + PceSample.MAX_FRAMES, PceLowering.dataSize(twice))
        // Two different ones do not: 131070 bytes of samples > 123072 bytes of data.
        val over = withSound { it.copy(samples = listOf(PceSample("x", big), PceSample("y", other))) }
        assertFalse(loads(over))
        assertThrows(IllegalArgumentException::class.java) { PceLowering.lower(over) }
        assertTrue(PceSamples.free(over) < 0)
        assertEquals(PcePlayerImage.DATA_CAPACITY - PceLowering.dataSize(twice), PceSamples.free(twice))
    }

    @Test
    fun auditionRefusesWhatCannotSound() {
        val song = base()
        fun plays(channel: Int, note: Int, instrument: Int, fine: Int = 0, s: Song = song) =
            PceSystem.engine.auditionProblem(s, channel, note, instrument, fine) == null
        assertTrue(plays(0, 57, 1))
        // Noise on PSG 5 and PSG 6 only; samples on any channel.
        assertTrue(plays(4, 57, 2))
        assertTrue(plays(5, 57, 2))
        for (ch in 0..3) assertFalse(plays(ch, 57, 2))
        assertTrue(plays(2, 48, 3, -128))
        // Notes A-0..B-7, fine pitch -128..127, channels PSG 1-6.
        assertTrue(plays(0, 9, 1))
        assertTrue(plays(0, 95, 1))
        assertFalse(plays(0, 8, 1))
        assertFalse(plays(0, 96, 1))
        assertTrue(plays(0, 57, 1, 127))
        assertFalse(plays(0, 57, 1, 128))
        assertFalse(plays(0, 57, 1, -129))
        assertFalse(plays(6, 57, 1))
        assertFalse(plays(-1, 57, 1))
        // Sounds 01-40 that are filled and load; repairing the sound lets it play.
        assertFalse(plays(0, 57, 0))
        assertFalse(plays(0, 57, 4))
        assertFalse(plays(0, 57, 65))
        assertFalse(plays(0, 57, 1, s = withSound { it.withInstrument(1, tone.copy(wave = 7)) }))
        assertTrue(plays(0, 57, 1, s = withSound { it.withInstrument(1, tone.copy(wave = 1)) }))
    }

    @Test
    fun warningsAppearWithTheirCauseAndClearWithIt() {
        fun warns(song: Song) = PceSystem.warnings(song).isNotEmpty()
        assertFalse(warns(base()))
        // A note delay the row's speed never reaches; a shorter delay, or a faster speed set on the row, clears it.
        val speed6 = base().copy(ticksPerRow = 6)
        assertTrue(warns(with(speed6, 0, 0, 0, Cell(48, 1, PceRules.FX_DELAY, 6))))
        assertFalse(warns(with(speed6, 0, 0, 0, Cell(48, 1, PceRules.FX_DELAY, 5))))
        assertFalse(warns(with(speed6, 0, 0, 0, Cell(48, 1, PceRules.FX_DELAY, 6).withEffect(1, PceRules.FX_SPEED, 7))))
        // An order's first note on a channel without a sound.
        assertTrue(warns(blankWith(0, 4, Cell(48))))
        assertFalse(warns(blankWith(0, 4, Cell(48, 1))))
        // The LFO on (from an effect or the song settings) while PSG 2, its source, never plays; a PSG 2 note clears it.
        val lfoByEffect = blankWith(0, 0, Cell(48, 1, PceRules.FX_LFO_CTRL, 0x01))
        val lfoBySettings = withSound { it.copy(lfoControl = 2) }
        val psg2Note = Cell(33, 1, PceRules.FX_VOLUME, 0)
        assertTrue(warns(lfoByEffect))
        assertTrue(warns(lfoBySettings))
        assertFalse(warns(with(lfoByEffect, 0, 1, 0, psg2Note)))
        assertFalse(warns(with(lfoBySettings, 0, 1, 0, psg2Note)))
        // Halted, the LFO bends nothing.
        assertFalse(warns(blankWith(0, 0, Cell(48, 1, PceRules.FX_LFO_CTRL, 0x81))))
        // Txx in a song where no sample sound plays; a sample sound clears it.
        assertTrue(warns(blankWith(0, 0, Cell(48, 1, PceRules.FX_TIMER, 0x01))))
        assertFalse(warns(blankWith(0, 0, Cell(48, 3, PceRules.FX_TIMER, 0x01))))
    }

    // ---- arrangement, settings and shared state -------------------------------------------------------------

    @Test
    fun arrangementRenumbersJumpsInEveryColumn() {
        val start = base()
        val patterns = List(3) { Songs.blankPattern(start) }
        var song = start.copy(patterns = patterns, orders = listOf(0, 1, 2), parkedOrders = listOf(1))
        song = with(song, 2, 3, 5, Cell(48, 1).withEffect(2, PceRules.FX_JUMP, 2))
        val arranged = PceSystem.arranged(song)
        assertEquals(listOf(0, 2), arranged.orders)
        assertEquals(1, PceSystem.jumpTarget(arranged.patterns[2].tracks[3][5]))
        val loop = PceSystem.patternLoop(song, 2)
        assertEquals(listOf(2), loop.orders)
        assertEquals(0, PceSystem.jumpTarget(loop.patterns[2].tracks[3][5]))
        // The lowering plays the arrangement: two orders.
        assertEquals(2, u16(PceLowering.lower(song), PcePlayerImage.H_ORDERS))
    }

    @Test
    fun songSettingsAdjustResetAndLower() {
        val song = base()
        // Each header value has exactly one Song-view setting changing it.
        fun setting(part: (PceSound) -> Int) = PceSystem.songSettings.single { s ->
            listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (f, c) -> part(s.adjust(song, f, c).pce) != part(song.pce) }
        }
        val left = setting { it.master shr 4 }
        assertEquals(0xEF, left.adjust(song, -1, 0).pce.master)
        assertEquals(0x0F, left.adjust(song, 0, -10).pce.master)
        assertEquals(song, left.adjust(song, 1, 0))
        assertEquals(song, left.reset(left.adjust(song, -3, 0)))
        assertEquals(0xFB, setting { it.master and 0x0F }.adjust(song, 0, -1).pce.master)
        val freq = setting { it.lfoFrequency }
        assertEquals(0x10, freq.adjust(song, 0, 1).pce.lfoFrequency)
        assertEquals(255, freq.adjust(song, 0, 40).pce.lfoFrequency)
        assertTrue(256.0 in numbers(freq.value(song))) // L00: PSG 2's wave steps 256× slower
        val control = setting { it.lfoControl }
        assertEquals(1, control.adjust(song, 1, 0).pce.lfoControl)
        assertEquals(0x80, control.adjust(song, 0, 1).pce.lfoControl)
        assertEquals(0x83, control.adjust(song, -1, 0).pce.lfoControl)
        assertTrue(64.0 in numbers(control.value(control.adjust(song, 2, 0)))) // depth 2: period ±64
        assertEquals(song, control.reset(control.adjust(song, 2, 0)))
        val dda = setting { it.timerReload }
        assertTrue(6991.0 in numbers(dda.value(song)))
        val slower = dda.adjust(song, 1, 0)
        assertEquals(1, slower.pce.timerReload)
        assertTrue(3496.0 in numbers(dda.value(slower)))
        assertEquals(127, dda.adjust(song, 0, 20).pce.timerReload)
        assertEquals(4, setting { it.multispeed }.adjust(song, 3, 0).pce.multispeed)
        // The header carries every one of them.
        val all = song.copy(ticksPerRow = 5, sound = song.pce.copy(multispeed = 2, master = 0x5A, lfoFrequency = 0xFF, lfoControl = 0x83, timerReload = 0x7F))
        val image = PceLowering.lower(all)
        assertEquals("TTPC", image.copyOfRange(0, 4).decodeToString())
        assertEquals(1, u8(image, 4))
        assertEquals(2, u8(image, 5))
        assertEquals(5, u8(image, 6))
        assertEquals(5, u8(image, 7))
        assertEquals(1, u16(image, 8))
        assertEquals(0, u16(image, 10))
        assertEquals(2, u8(image, 12))
        assertEquals(3, u8(image, 13))
        assertEquals(0x5A, u8(image, 14))
        assertEquals(0xFF, u8(image, 15))
        assertEquals(0x83, u8(image, 16))
        assertEquals(0x7F, u8(image, 17))
        assertEquals(0, u16(image, 18))
        assertEquals(PceLowering.dataSize(all), u32(image, 20))
        for (i in 24 until 64) assertEquals("header byte $i", 0, image[i].toInt())
    }

    @Test
    fun sharedStateFollowsTheSharedEffectsInPlayOrder() {
        var song = base()
        song = with(song, 0, 3, 0, Cell(effect = PceRules.FX_MASTER, param = 0x5A).withEffect(1, PceRules.FX_LFO_FREQ, 0x10))
        song = with(song, 0, 0, 1, Cell(effect = PceRules.FX_LFO_CTRL, param = 0x01))
        song = with(song, 0, 4, 2, Cell(effect = PceRules.FX_LFO_CTRL, param = 0x83))
        song = with(song, 0, 5, 2, Cell(effect = PceRules.FX_TIMER, param = 0x01))
        // Row 3: two channels set the master balance; the later channel wins.
        song = with(song, 0, 0, 3, Cell(effect = PceRules.FX_MASTER, param = 0x12))
        song = with(song, 0, 5, 3, Cell(effect = PceRules.FX_MASTER, param = 0x34))
        // Row 4: from a delayed row the speed reaches; row 5: one it never reaches (G09 at speed 6).
        song = with(song, 0, 1, 4, Cell(effect = PceRules.FX_LFO_CTRL, param = 0x02).withEffect(1, PceRules.FX_DELAY, 3))
        song = with(song, 0, 2, 5, Cell(effect = PceRules.FX_LFO_CTRL, param = 0x00).withEffect(1, PceRules.FX_DELAY, 9))
        // Row 6: an out-of-range T (rejected by the loader) changes nothing.
        song = with(song, 0, 0, 6, Cell(effect = PceRules.FX_TIMER, param = 0x80))
        val speed6 = song.copy(ticksPerRow = 6)

        // The values straight play reaches, and the readouts showing them (their numbers; the wording is the UI's).
        fun shown(state: SharedState) = numbers(state.lines.joinToString(" "))
        val start = PceSystem.sharedState(base(), 0, 0)
        assertEquals(PceSharedState.start(base().pce), PceSharedState.at(base(), 0, 0))
        assertTrue(listOf(15.0, 6991.0).all { it in shown(start) })
        assertTrue(start.tags.all { it == null })
        assertEquals(PceSharedState.Shared(0x5A, 0x10, 0x00, 0), PceSharedState.at(speed6, 0, 0))
        assertTrue(listOf(5.0, 10.0).all { it in shown(PceSystem.sharedState(speed6, 0, 0)) })
        // While the LFO bends, PSG 1 (bent) and PSG 2 (its source) carry distinct tags.
        assertEquals(PceSharedState.Shared(0x5A, 0x10, 0x01, 0), PceSharedState.at(speed6, 0, 1))
        assertTrue(PceSharedState.at(speed6, 0, 1).lfoActive)
        val lfo = PceSystem.sharedState(speed6, 0, 1)
        assertEquals(listOf(true, true, false, false, false, false), lfo.tags.map { it != null })
        assertTrue(lfo.tags[0] != lfo.tags[1])
        // Halted, it bends nothing; T01 halves the DDA rate.
        assertEquals(PceSharedState.Shared(0x5A, 0x10, 0x83, 1), PceSharedState.at(speed6, 0, 2))
        assertFalse(PceSharedState.at(speed6, 0, 2).lfoActive)
        val halted = PceSystem.sharedState(speed6, 0, 2)
        assertTrue(halted.tags.all { it == null })
        assertTrue(listOf(256.0, 3496.0).all { it in shown(halted) })
        assertEquals(0x34, PceSharedState.at(speed6, 0, 3).master)
        assertEquals(2, PceSharedState.at(speed6, 0, 4).lfoControl)
        assertEquals(2, PceSharedState.at(speed6, 0, 5).lfoControl)
        assertEquals(1, PceSharedState.at(speed6, 0, 6).timer)
        // A position past the song's orders reads the header.
        assertEquals(PceSharedState.start(speed6.pce), PceSharedState.at(speed6, 5, 0))
    }

    // ---- pitch -------------------------------------------------------------------------------------------------

    @Test
    fun fineWritesPxxInAFreeColumnAndThePitchArithmeticIsThePlayers() {
        val fine = PceSystem.fine
        assertEquals(25.0, fine.cents(57, 16), 1e-9)
        val busy = Cell(57, 1, PceRules.FX_VIBRATO, 0x23)
        val tuned = fine.write(busy, 57, 16)!!
        assertEquals(PceRules.FX_FINE, tuned.effectCode(1))
        assertEquals(0x90, tuned.effectParam(1))
        assertEquals(16, fine.of(tuned))
        assertEquals(busy, fine.write(tuned, 57, 0))
        var full = Cell(57, 1)
        for (c in 0 until 4) full = full.withEffect(c, PceRules.FX_VOLUME, 0x10)
        assertNull(fine.write(full, 57, 8))
        assertNull(fine.of(Cell(57, 1, PceRules.FX_FINE, 0x70))) // a downward bend is not a tuning
        assertEquals(16, PceSystem.engine.fine(0, 57, 16))
        // The spec's periods: A-4 = 254 (440.40 Hz), A-0 = 4068, B-7 = 28; the clamp's lowest pitch fits 12 bits.
        assertEquals(254, PceRules.period(57 * 64))
        assertEquals(440.40, PceRules.toneHz(57), 0.005)
        assertEquals(4068, PceRules.period(9 * 64))
        assertEquals(28, PceRules.period(95 * 64))
        assertEquals(4093, PceRules.period(PcePlayerImage.PITCH_MIN))
        assertEquals(4093, PceRules.period(0))
        assertEquals(448329831L, PceRules.periodQ16(0))
        // Sample steps: the root plays a frame per interrupt; an octave up two, down a half.
        assertEquals(65536L, PceRules.sampleStep(48 * 64, 48))
        assertEquals(131072L, PceRules.sampleStep(60 * 64, 48))
        assertEquals(32768L, PceRules.sampleStep(36 * 64, 48))
        // Noise: the note mod 32 picks the rate; rate 31 steps every 64 cycles.
        assertEquals(31, PceRules.noiseRate(63))
        assertEquals(PceRules.CPU_HZ / 64, PceRules.noiseHz(63), 1e-6)
        assertEquals(PceRules.CPU_HZ / (3 * 128), PceRules.noiseHz(60), 1e-6)
        assertEquals(6991.30, PceRules.timerHz(0), 0.005)
    }

    // ---- lowering ----------------------------------------------------------------------------------------------

    @Test
    fun dataRegionHoldsPatternsThenSharedEnvelopesThenSharedSamples() {
        val image = PceLowering.lower(base())
        val data = PcePlayerImage.OFF_DATA
        // One blank pattern: six identical 64-row runs stored once at 0.
        for (c in 0 until 6) assertEquals(0, u16(image, PcePlayerImage.OFF_ORDERS + 2 * c))
        assertEquals(0xBF, u8(image, data))
        assertEquals(64, u8(image, PcePlayerImage.OFF_ROWS))
        // Envelopes in slot order (volume, arpeggio, pitch, wave), identical bytes once, across envelope kinds.
        val expect = byteArrayOf(0xBF.toByte(), 31, 20, 10, 0, 1, 2, 31, 20, 0, 1, 16, 17, 18, 19, 0, 31)
        assertArrayEquals(expect, image.copyOfRange(data, data + expect.size))
        assertEquals(expect.size, u32(image, PcePlayerImage.H_DATA_SIZE))
        assertEquals(expect.size, PceLowering.dataSize(base()))
        assertTrue(image.copyOfRange(data + expect.size, PcePlayerImage.IMAGE_SIZE).all { it == 0.toByte() })
        fun record(slot: Int) = image.copyOfRange(PcePlayerImage.OFF_INSTRUMENTS + 32 * (slot - 1), PcePlayerImage.OFF_INSTRUMENTS + 32 * slot)
        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        val none = intArrayOf(0, 0, 0, 0xFF, 0xFF, 0)
        // Slot 01: tone, wave 0, volume [31] at 1.
        assertArrayEquals(bytes(0x80, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0xFF, 0xFF, 0, *none, *none, *none), record(1))
        // Slot 02: noise; its wave envelope is not written.
        assertArrayEquals(bytes(0x81, 0, 0, 0, 0, 0, 0, 0, 2, 0, 3, 0xFF, 0xFF, 0, *none, *none, *none), record(2))
        // Slot 03: sample 0, root 48, loop from 2, relative pitch [1, 2] at 5; volume shares 1.
        assertArrayEquals(bytes(0x86, 0, 0, 48, 1, 0, 2, 0, 1, 0, 1, 0xFF, 0xFF, 0, *none, 5, 0, 2, 0xFF, 0xFF, 0, *none), record(3))
        // Slot 04 empty; slot 05: tone wave 1, volume [31, 20] loop 0 at 7, arpeggio [31] = slot 01's volume bytes, waves at 9.
        assertTrue(record(4).all { it == 0.toByte() })
        assertArrayEquals(bytes(0x80, 1, 0, 0, 0, 0, 0, 0, 7, 0, 2, 0, 0xFF, 0, 1, 0, 1, 0xFF, 0xFF, 0, *none, 9, 0, 2, 1, 0xFF, 0), record(5))
        assertEquals(5, u8(image, PcePlayerImage.H_INSTRUMENTS))
        assertTrue(image.copyOfRange(PcePlayerImage.OFF_INSTRUMENTS + 5 * 32, PcePlayerImage.OFF_WAVES).all { it == 0.toByte() })
        // Waves in order; above W zero.
        assertArrayEquals(bytes(*sine.toIntArray(), *square.toIntArray()), image.copyOfRange(PcePlayerImage.OFF_WAVES, PcePlayerImage.OFF_WAVES + 64))
        assertTrue(image.copyOfRange(PcePlayerImage.OFF_WAVES + 64, PcePlayerImage.OFF_SAMPLES).all { it == 0.toByte() })
        // Samples: "a" and the identical "b" at 11, "c" at 15; entries above S zero.
        val s = PcePlayerImage.OFF_SAMPLES
        assertEquals(listOf(11, 4, 11, 4, 15, 2), (0 until 6).map { u32(image, s + 4 * it) })
        assertTrue(image.copyOfRange(s + 24, data).all { it == 0.toByte() })
        // The record function alone, with a release point.
        val released = PceLowering.record(tone.copy(volume = PceEnvelope(listOf(31, 20, 10), loop = 0, release = 2)), intArrayOf(300, 0, 0, 0))
        assertArrayEquals(bytes(0x2C, 0x01, 3, 0, 2, 0), released.copyOfRange(8, 14))
        assertEquals(5, PceLowering.highestSlot(base().pce))
        assertEquals(0, PceLowering.highestSlot(base().pce.copy(instruments = List(64) { null })))
    }

    @Test
    fun rowsAreTheSpecifiedBytes() {
        val cell = Cell(57, 2, PceRules.FX_VOLUME, 0x10).withEffect(2, PceRules.FX_MASTER, 0x5A).withEffect(3, PceRules.FX_WAVE, 1)
        val track = List(Songs.ROWS) { when (it) { 3 -> cell; 4 -> Cell(Cell.CUT); else -> Cell.BLANK } }
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), (1 or 2 or (3 shl 2)).toByte(), 57, 2, 12, 0x10, 33, 0x5A, 32, 1, 1, 0xFE.toByte(), (0x80 or 58).toByte()),
            PceLowering.encode(track, Songs.ROWS),
        )
        // An instrument byte without a note; a 1-row order.
        val instrumentOnly = List(Songs.ROWS) { if (it == 0) Cell(instrument = 5) else Cell.BLANK }
        assertArrayEquals(byteArrayOf(2, 5), PceLowering.encode(instrumentOnly, 1))
        // Runs longer than 128 rows are never needed (64-row patterns), but a 64-row empty track is one run.
        assertArrayEquals(byteArrayOf(0xBF.toByte()), PceLowering.encode(List(Songs.ROWS) { Cell.BLANK }, Songs.ROWS))
    }

    @Test
    fun patternsAndEnvelopesMustStayWithinTheirU16Offsets() {
        // Distinct dense patterns: every track is 64 rows of five bytes (mask, note, sound, C, parameter), 320 bytes,
        // none shared. With 34 patterns the last track starts at 64960 and the envelopes follow below 65536; with 35 it
        // starts at 66880, past the u16 offsets though the data region still has room.
        val start = base()
        fun song(count: Int): Song {
            val patterns = (0 until count).map { p ->
                val tracks = List(6) { ch -> List(Songs.ROWS) { row -> Cell(9 + (p + row + ch) % 87, 1, PceRules.FX_VOLUME, (p + row) % 32) } }
                Songs.blankPattern(start).copy(tracks = tracks)
            }
            return start.copy(patterns = patterns, orders = patterns.indices.toList())
        }
        val fits = song(34)
        assertTrue(loads(fits))
        assertEquals(34, u16(PceLowering.lower(fits), PcePlayerImage.H_ORDERS))
        val over = song(35)
        assertTrue(PceLowering.dataSize(over) <= PcePlayerImage.DATA_CAPACITY)
        assertFalse(loads(over))
        assertThrows(IllegalArgumentException::class.java) { PceLowering.lower(over) }
    }

    // ---- samples -------------------------------------------------------------------------------------------

    /** A 16-bit mono PCM `.wav` of [values] at [rate] Hz. */
    private fun wav(rate: Int, values: IntArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) { for (k in 0 until 4) out.write(v shr (8 * k) and 0xFF) }
        fun u16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); u32(36 + 2 * values.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); u32(16); u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16)
        out.write("data".toByteArray()); u32(2 * values.size)
        for (v in values) u16(v and 0xFFFF)
        return out.toByteArray()
    }

    @Test
    fun wavImportResamplesAndQuantisesAsDocumented() {
        // Twice the DDA rate: each frame is the mean of two source samples, then (v + 33792) >> 11.
        assertEquals(listOf(17, 0, 31, 17), PceSamples.fromWav(wav(13982, intArrayOf(0, 2048, -32768, -32768, 32767, 32767, 1000, 3000)), 6991))
        // Half the rate: linear interpolation in 16.16.
        assertEquals(listOf(16, 17, 18, 18), PceSamples.fromWav(wav(3495, intArrayOf(0, 4096)), 6991))
        // At least one frame; the same rate passes values through the levels.
        assertEquals(listOf(16), PceSamples.fromWav(wav(44100, intArrayOf(0)), 6991))
        assertEquals(listOf(0, 16, 31), PceSamples.fromWav(wav(6991, intArrayOf(-32768, 0, 32767)), 6991))
        assertThrows(IllegalArgumentException::class.java) { PceSamples.fromWav(ByteArray(40), 6991) }
        assertEquals(listOf(6991, 3496, 55), listOf(0, 1, 127).map(PceSamples::rateHz))
        assertEquals(1.0, PceSamples.seconds(6991, 0), 0.001)
        assertEquals(16, PceSamples.level(0))
        assertEquals(31, PceSamples.level(32767))
    }

    @Test
    fun starterKitIsDeterministicFiveBitAudio() {
        val kit = PceStarterKit.samples(PceSamples.rateHz(0))
        assertEquals(kit, PceStarterKit.samples(6991))
        assertEquals(listOf(1747, 1398, 419, 2097), kit.map { it.data.size })
        assertTrue(kit.all { s -> s.problem() == null })
        // Integer synthesis: these exact frames on every platform (List.hashCode of each sample).
        assertEquals(listOf(422833869, -617283206, 185658038, -176467660), kit.map { it.data.hashCode() })
        assertEquals(listOf(19, 21, 23, 25), kit[PceStarterKit.KICK].data.take(4))
        val ins = PceStarterKit.instrument(2, "Hat")
        assertEquals(PceInstrument(name = "Hat", kind = PceKind.SAMPLE, sample = 2, root = 48), ins)
        // A one-shot kick at its root lasts 15 ticks at the default rate: three rows at speed 6.
        val song = base().let { s ->
            val sound = s.pce.copy(samples = kit).withInstrument(6, PceStarterKit.instrument(PceStarterKit.KICK, "Kick"))
            with(s.copy(sound = sound, ticksPerRow = 6), 0, 3, 0, Cell(48, 6))
        }
        assertEquals(3, PceSystem.soundingRows(song, 0, 3, 0))
        assertNull(PceSystem.soundingRows(with(song, 0, 3, 0, Cell(48, 3)), 0, 3, 0))
        assertNull(PceSystem.soundingRows(with(song, 0, 3, 0, Cell(48, 1)), 0, 3, 0))
    }

    @Test
    fun starterKitKeepsItsLengthsAndPitchAtEveryDdaRate() {
        // Rising crossings of the centre with hysteresis (from ≤ 15 to ≥ 17), so quantisation flicker is not counted.
        fun cycles(data: List<Int>): Int {
            var low = false
            var n = 0
            for (f in data) {
                if (f <= 15) low = true
                else if (f >= 17 && low) { n++; low = false }
            }
            return n
        }
        val lengths = listOf(0.25, 0.2, 0.06, 0.3)
        for (timer in listOf(0, 1, 3)) {
            val rate = PceSamples.rateHz(timer)
            val kit = PceStarterKit.samples(rate)
            assertTrue(kit.all { s -> s.problem() == null })
            // Each drum lasts as long at the song's rate (within one frame) as at the default one.
            kit.forEachIndexed { i, s ->
                assertEquals("${s.name} at timer $timer", lengths[i], PceSamples.seconds(s.data.size, timer), 1.0 / rate)
            }
            // The tom (140 Hz settling to 95 Hz, about 30.7 cycles in 0.3 s) keeps its pitch: about 102 Hz on average.
            val tom = kit[PceStarterKit.TOM].data
            val hz = cycles(tom) / PceSamples.seconds(tom.size, timer)
            assertTrue("tom at timer $timer: $hz Hz", hz in 95.0..106.0)
        }
    }

    // ---- effects, exports and demo ---------------------------------------------------------------------------

    @Test
    fun effectReadoutsShowCalculatedValuesAndHeldArpeggios() {
        fun notes(text: String?) = Regex("[A-G](?:#|-)[0-9]").findAll(text.orEmpty()).map { it.value }.toList()
        assertEquals(3496.0, numbers(PceRules.describe(null, PceRules.FX_TIMER, 0x01)).first(), 0.0)
        assertEquals(64.0, numbers(PceRules.describe(null, PceRules.FX_LFO_CTRL, 0x82)).first(), 0.0)
        assertEquals(256.0, numbers(PceRules.describe(null, PceRules.FX_LFO_FREQ, 0)).first(), 0.0)
        assertEquals(listOf(5.0, 10.0), numbers(PceRules.describe(null, PceRules.FX_MASTER, 0x5A)).take(2))
        assertEquals(listOf(15.0, 0.0), numbers(PceRules.describe(null, PceRules.FX_PAN, 0xF0)).take(2))
        assertEquals(listOf(16.0, 64.0, 25.0), numbers(PceRules.describe(null, PceRules.FX_FINE, 0x90)))
        var song = blankWith(0, 0, Cell(57, 1, PceRules.FX_ARPEGGIO, 0x47))
        song = with(song, 0, 0, 2, Cell(effect = PceRules.FX_ARPEGGIO, param = 0x37))
        assertEquals(listOf("A-4", "C#5", "E-5"), notes(PceSystem.effectDetail(song, 0, 0, 0, 0)))
        assertEquals(listOf("A-4", "C-5", "E-5"), notes(PceSystem.effectDetail(song, 0, 0, 2, 0)))
        assertNull(PceSystem.effectDetail(song, 0, 0, 1, 0))
    }


    @Test
    fun demoIsCleanAndShowsTheSystem() {
        val demo = PceSystem.demo()
        assertEquals(emptyList<String>(), PceSystem.errors(demo))
        assertEquals(emptyList<String>(), PceSystem.warnings(demo))
        val sound = demo.pce
        assertEquals(PceStarterKit.samples(PceSamples.rateHz(sound.timerReload)), sound.samples)
        val played = demo.orders.toSet().map { demo.patterns[it] }
        val cells = played.flatMap { it.tracks.flatten() }
        fun uses(code: Int) = cells.any { c -> (0..c.more.size).any { c.effectCode(it) == code && (code != 0 || c.effectParam(it) != 0) } }
        for (code in listOf(PceRules.FX_ARPEGGIO, PceRules.FX_VIBRATO, PceRules.FX_PORTAMENTO, PceRules.FX_PAN, PceRules.FX_VOLUME,
                PceRules.FX_LFO_FREQ, PceRules.FX_LFO_CTRL, PceRules.FX_TIMER, PceRules.FX_WAVE, PceRules.FX_MASTER)) {
            assertTrue("effect ${PceRules.effectText(code, 0)[0]}", uses(code))
        }
        fun kindOn(channel: Int) = played.flatMap { p -> p.tracks[channel].filter { it.instrument != 0 }.map { sound.instrument(it.instrument)!!.kind } }.toSet()
        // A tone with a wave envelope; noise on PSG 5 and 6 only; D/A samples; every channel playing.
        assertTrue(cells.any { it.instrument != 0 && !sound.instrument(it.instrument)!!.waves.isEmpty })
        assertTrue(PceKind.NOISE in kindOn(4) && PceKind.NOISE in kindOn(5))
        for (ch in 0..3) assertFalse(PceKind.NOISE in kindOn(ch))
        assertTrue(PceKind.SAMPLE in kindOn(3))
        for (ch in 0 until 6) assertTrue("channel $ch", played.any { p -> p.tracks[ch].any { it.note >= 0 } })
        // The drift sets the LFO on with notes on PSG 2.
        assertTrue(cells.any { c -> (0..c.more.size).any { c.effectCode(it) == PceRules.FX_LFO_CTRL && c.effectParam(it) and 3 != 0 } })
        val image = PceLowering.lower(demo)
        assertEquals(PceLowering.dataSize(demo), u32(image, PcePlayerImage.H_DATA_SIZE))
        assertEquals(sound.waves.size, u8(image, PcePlayerImage.H_WAVES))
        assertEquals(4, u8(image, PcePlayerImage.H_SAMPLES))
        // The shortest song: one row at speed 1 holding A-4 on PSG 1 with slot 01, a tone on wave 0.
        val shortest = PceDemoSong.shortest()
        assertEquals(emptyList<String>(), PceSystem.errors(shortest))
        assertEquals(1, shortest.ticksPerRow)
        assertEquals(1, shortest.patterns.single().length)
        assertEquals(Cell(57, 1), shortest.patterns.single().tracks[0][0])
        assertEquals(PceKind.TONE, shortest.pce.instrument(1)!!.kind)
        assertEquals(0, shortest.pce.instrument(1)!!.wave)
        assertEquals(1, shortest.pce.waves.size)
    }
}
