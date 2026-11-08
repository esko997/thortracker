package org.thortracker.system

import org.junit.After
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
import org.thortracker.editor.ViewKind
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
import org.thortracker.system.pce.PceSongSettings
import org.thortracker.system.pce.PceSound
import org.thortracker.system.pce.PceStarterKit
import org.thortracker.system.pce.PceSystem
import org.thortracker.system.pce.PceValidator
import org.thortracker.system.pce.PceVgm
import org.thortracker.system.pce.PceVgmRenderer
import org.thortracker.system.pce.PceVgmTags
import org.thortracker.system.pce.pce

/**
 * The PC Engine system's model: registration and shape, project files, the validator's mirror of the loader's
 * reasons, warnings, arrangement, song settings, the shared-state walk, fine pitch and the player's pitch arithmetic,
 * the lowering's header, records and data layout (patterns, then deduplicated envelopes, then deduplicated samples),
 * `.wav` import arithmetic, the starter kit, the VGM seam and the demo. The songs here use their own sounds, waves and
 * samples (not the presets), so they do not depend on the preset list.
 */
class PceModelTest {
    @After
    fun noRenderer() {
        PceVgm.renderer = null
    }

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

    // ---- system shape ------------------------------------------------------------------------------------------

    @Test
    fun systemIsRegisteredWithSixPsgChannels() {
        assertEquals(PceSystem, SoundSystems.of("pce-huc6280"))
        assertEquals("PC Engine (HuC6280)", PceSystem.name)
        assertNull(PceSystem.family)
        assertEquals((1..6).map { "PSG $it" }, PceSystem.channels.map { it.name })
        assertTrue(PceSystem.channels.all { it.tonal && it.kind == ChannelKind.WAVE && it.bank == 0 })
        assertEquals(listOf(BankSpec("Sounds", 64)), PceSystem.banks)
        assertEquals(9..95, PceSystem.noteRange)
        assertEquals("A-0", PceSystem.noteName(9))
        assertEquals("A-4", PceSystem.noteName(57))
        assertEquals("B-7", PceSystem.noteName(95))
        assertEquals(4, PceSystem.effectColumns(0))
        assertEquals(4, PceSystem.effectColumns(5))
        assertEquals(PceRules.EFFECTS, PceSystem.effectsFor(0))
        assertEquals(PceRules.EFFECTS, PceSystem.effectsFor(4))
        assertEquals(4..5, PceRules.NOISE_CHANNELS)
        assertTrue(ViewKind.WAVE in PceSystem.views && ViewKind.INSTRUMENT in PceSystem.views)
        assertEquals(listOf("vgm"), PceSystem.exports.map { it.extension })
        val engine = PceSystem.engine
        assertEquals("pce-huc6280", engine.id)
        assertEquals(131072, engine.imageSize)
        assertEquals(3600, engine.seekTicks)
        assertTrue(engine.driver.contains("Mednafen 1.32.1"))
        assertEquals(57, engine.note(base(), 0, 57, 1))
        assertEquals(60.0, PceSystem.tickHz, 0.0)
        assertEquals(180.0, PceSystem.ticksPerSecond(withSound { it.copy(multispeed = 3) }), 1e-9)
        assertEquals(PceSound.serializer(), PceSystem.soundSerializer)
        assertEquals(PceSystem, PceSystem.blank().sound.system)
        // The effect letters the loader knows, and only those.
        assertEquals("0 1 2 3 4 8 A B C D F G L M P S T W X", PceRules.EFFECTS.joinToString(" ") { it.char.toString() })
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
        assertEquals("Empty: choose a preset", PceSystem.instrumentSummary(base(), 0, 4))
        assertTrue(PceSystem.instrumentSummary(base(), 0, 2)!!.startsWith("Noise (PSG 5-6)"))
        assertEquals("Sample 00 a · 4 frames · root C-4 · loop from 2 · volume, relative pitch envelopes", PceSystem.instrumentSummary(base(), 0, 3))
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

    @Test
    fun cellValidationExplainsWhatTheLoaderRejects() {
        fun error(channel: Int, cell: Cell) = PceSystem.errors(blankWith(channel, 0, cell)).joinToString("\n")
        // Noise sounds on PSG 5 and PSG 6 only.
        for (ch in 0..3) assertTrue(error(ch, Cell(60, 2)).contains("noise sounds belong there"))
        assertEquals("", error(4, Cell(60, 2)))
        assertEquals("", error(5, Cell(60, 2)))
        // Sample and tone sounds on any channel.
        assertEquals("", error(0, Cell(60, 3)))
        assertEquals("", error(5, Cell(60, 5)))
        // Parameter checks, in any effect column.
        assertTrue(error(0, Cell(60, 1).withEffect(2, PceRules.FX_VOLUME, 0x20)).contains("volumes are C00-C1F"))
        assertEquals("", error(0, Cell(60, 1, PceRules.FX_VOLUME, 0x1F)))
        assertTrue(error(0, Cell(60, 1, PceRules.FX_LFO_CTRL, 0x04)).contains("M00-M03 or M80-M83"))
        assertTrue(error(0, Cell(60, 1, PceRules.FX_LFO_CTRL, 0x84)).contains("M00-M03 or M80-M83"))
        assertEquals("", error(0, Cell(60, 1, PceRules.FX_LFO_CTRL, 0x83)))
        assertTrue(error(0, Cell(60, 1, PceRules.FX_TIMER, 0x80)).contains("T00-T7F"))
        assertEquals("", error(0, Cell(60, 1, PceRules.FX_TIMER, 0x7F)))
        assertTrue(error(0, Cell(60, 1, PceRules.FX_WAVE, 0x02)).contains("the song's waves are W00-W01"))
        assertEquals("", error(0, Cell(60, 1, PceRules.FX_WAVE, 0x01)))
        assertTrue(error(0, Cell(60, 1, PceRules.FX_SPEED, 0)).contains("F00"))
        assertTrue(error(0, Cell(60, 1, PceRules.FX_JUMP, 0x01)).contains("jumps past the last order"))
        assertEquals("", error(0, Cell(60, 1, PceRules.FX_LFO_FREQ, 0xFF)))
        assertEquals("", error(0, Cell(60, 1, PceRules.FX_MASTER, 0x00)))
        // Codes without an effect.
        for (code in listOf(5, 6, 7, 9, 14, 17, 18, 19, 20, 23, 24, 26, 27, 30, 31, 34, 35)) {
            assertTrue("code $code", error(0, Cell(60, 1, code, 1)).contains("does not exist on the PC Engine"))
        }
        // Notes outside A-0..B-7, sounds outside the bank or empty.
        assertTrue(error(0, Cell(8, 1)).contains("outside A-0..B-7"))
        assertTrue(error(0, Cell(96, 1)).contains("outside A-0..B-7"))
        assertEquals("", error(0, Cell(9, 1)))
        assertEquals("", error(0, Cell(95, 1)))
        assertTrue(error(0, Cell(60, 4)).contains("sound 04 is empty"))
        assertTrue(error(0, Cell(60, 65)).contains("outside 0-64"))
        // Wxx without any waves.
        val noWaves = withSound { it.copy(waves = emptyList(), instruments = List(PceSystem.SLOTS) { null }) }
        assertTrue(PceSystem.errors(with(noWaves, 0, 0, 0, Cell(effect = PceRules.FX_WAVE, param = 0))).joinToString().contains("the song has no waves"))
    }

    @Test
    fun soundAndSongValidationExplainsWhatTheLoaderRejects() {
        fun songError(f: (PceSound) -> PceSound) = PceSystem.errors(withSound(f)).joinToString("\n")
        // Header values.
        assertTrue(songError { it.copy(multispeed = 9) }.contains("multispeed 9 is outside 1-8"))
        assertTrue(songError { it.copy(master = 0x100) }.contains("master balance 256 is outside 00-FF"))
        assertTrue(songError { it.copy(lfoControl = 4) }.contains("LFO control 04 is not 00-03 or 80-83"))
        assertEquals("", songError { it.copy(lfoControl = 0x83) })
        assertTrue(songError { it.copy(lfoFrequency = 256) }.contains("LFO frequency 256 is outside 0-255"))
        assertTrue(songError { it.copy(timerReload = 128) }.contains("DDA timer 128 is outside 0-127"))
        // Waves and samples.
        assertTrue(songError { it.copy(waves = listOf(sine.dropLast(1))) }.contains("wave 00 has 31 steps; waves have 32"))
        assertTrue(songError { it.copy(waves = listOf(sine, sine.map { 32 })) }.contains("wave 01 has the value 32"))
        assertTrue(songError { it.copy(waves = List(65) { sine }) }.contains("has 65 waves"))
        assertTrue(songError { it.copy(samples = listOf(PceSample("x", emptyList()))) }.contains("sample 00 \"x\" has no frames"))
        assertTrue(songError { it.copy(samples = listOf(PceSample("x", listOf(32)))) }.contains("frame value 32 is outside 0-31"))
        assertTrue(songError { it.copy(samples = listOf(PceSample("x", List(65536) { 16 }))) }.contains("samples have at most 65535"))
        assertTrue(songError { it.copy(samples = List(65) { samples[2] }) }.contains("has 65 samples"))
        // Envelope shapes.
        fun insError(ins: PceInstrument) = songError { it.withInstrument(6, ins) }
        assertTrue(insError(tone.copy(volume = PceEnvelope(List(256) { 31 }))).contains("volume envelope has 256 steps; envelopes have at most 255"))
        assertEquals("", insError(tone.copy(pitch = PceEnvelope(List(255) { -128 }))))
        assertTrue(insError(tone.copy(volume = PceEnvelope(listOf(32)))).contains("volume envelope step value 32 is outside 0..31"))
        assertTrue(insError(tone.copy(arpeggio = PceEnvelope(listOf(64)))).contains("arpeggio envelope step value 64 is outside -64..63"))
        assertTrue(insError(tone.copy(pitch = PceEnvelope(listOf(-129)))).contains("pitch envelope step value -129"))
        assertTrue(insError(tone.copy(volume = PceEnvelope(listOf(31, 20), loop = 2))).contains("loop point 2 is outside"))
        assertTrue(insError(tone.copy(volume = PceEnvelope(listOf(31, 20), release = 0))).contains("release point 0 must be 1-1"))
        assertTrue(insError(tone.copy(volume = PceEnvelope(listOf(31, 20, 10), loop = 1, release = 1))).contains("must come after the loop point"))
        assertEquals("", insError(tone.copy(volume = PceEnvelope(listOf(31, 20, 10), loop = 0, release = 1))))
        assertTrue(insError(tone.copy(arpeggio = PceEnvelope(emptyList(), loop = 0))).contains("has a loop or release point but no steps"))
        assertTrue(insError(tone.copy(root = 96)).contains("root note 96 is outside 9-95"))
        // References into the song's waves and samples (PceValidator.instrumentProblem: no slot prefix).
        fun problem(ins: PceInstrument) = PceValidator.instrumentProblem(base().pce.withInstrument(6, ins), 6)
        assertNull(problem(tone))
        assertNull(PceValidator.instrumentProblem(base().pce, 4))
        assertEquals("plays wave 02; the song's waves are 00-01", problem(tone.copy(wave = 2)))
        assertEquals("its wave envelope steps to wave 05; the song's waves are 00-01", problem(tone.copy(waves = PceEnvelope(listOf(0, 5)))))
        // A noise or sample sound's wave fields are not lowered, so they are not checked against the song's waves.
        assertNull(problem(hat.copy(wave = 9, waves = PceEnvelope(listOf(9)))))
        assertEquals("plays sample 03; the song's samples are 00-02", problem(loop.copy(sample = 3)))
        assertEquals("loops from frame 4, past the end of \"a\" (4 frames)", problem(loop.copy(loopStart = 4)))
        assertNull(problem(loop.copy(loop = false, loopStart = 4)))
        assertTrue(PceValidator.instrumentProblem(withSound { it.copy(samples = emptyList()) }.pce, 3)!!.contains("the song has no samples"))
        assertTrue(PceSystem.errors(withSound { it.withInstrument(6, tone.copy(wave = 2)) }).joinToString().contains("Sound 06 \"tone\": plays wave 02"))
        assertTrue(songError { it.copy(waves = emptyList()) }.contains("plays a wave, but the song has no waves"))
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
        assertEquals(emptyList<String>(), PceSystem.errors(twice))
        assertEquals(patternAndEnvelopes + PceSample.MAX_FRAMES, PceLowering.dataSize(twice))
        // Two different ones do not: 131070 bytes of samples > 123072 bytes of data.
        val over = withSound { it.copy(samples = listOf(PceSample("x", big), PceSample("y", other))) }
        assertTrue(PceSystem.errors(over).joinToString().contains("the PC Engine image holds"))
        assertThrows(IllegalArgumentException::class.java) { PceLowering.lower(over) }
        assertTrue(PceSamples.free(over) < 0)
        assertEquals(PcePlayerImage.DATA_CAPACITY - PceLowering.dataSize(twice), PceSamples.free(twice))
    }

    @Test
    fun auditionChecksNoiseChannelsRangesAndSounds() {
        val engine = PceSystem.engine
        val song = base()
        assertNull(engine.auditionProblem(song, 0, 57, 1, 0))
        assertNull(engine.auditionProblem(song, 4, 57, 2, 0))
        assertNull(engine.auditionProblem(song, 5, 57, 2, 0))
        assertNull(engine.auditionProblem(song, 2, 48, 3, -128))
        assertTrue(engine.auditionProblem(song, 3, 57, 2, 0)!!.contains("PSG 5 and PSG 6 only"))
        assertTrue(engine.auditionProblem(song, 0, 8, 1, 0)!!.contains("outside A-0..B-7"))
        assertTrue(engine.auditionProblem(song, 0, 96, 1, 0)!!.contains("outside A-0..B-7"))
        assertTrue(engine.auditionProblem(song, 0, 57, 4, 0)!!.contains("is empty"))
        assertTrue(engine.auditionProblem(song, 0, 57, 0, 0)!!.contains("Choose a sound"))
        assertTrue(engine.auditionProblem(song, 0, 57, 1, 128)!!.contains("Fine pitch"))
        assertTrue(engine.auditionProblem(song, 6, 57, 1, 0)!!.contains("Cannot audition"))
        val broken = withSound { it.withInstrument(1, tone.copy(wave = 7)) }
        assertTrue(engine.auditionProblem(broken, 0, 57, 1, 0)!!.contains("plays wave 07"))
    }

    @Test
    fun warningsCoverDelaysFirstNotesTheLfoAndTheTimer() {
        fun warnings(song: Song) = PceSystem.warnings(song).joinToString("\n")
        assertEquals("", warnings(base()))
        assertTrue(warnings(blankWith(0, 0, Cell(48, 1, PceRules.FX_DELAY, 6))).contains("the row never plays"))
        assertTrue(warnings(blankWith(0, 4, Cell(48))).contains("has no sound"))
        // The LFO on while PSG 2 never plays: from an effect or the song settings.
        assertTrue(warnings(blankWith(0, 0, Cell(48, 1, PceRules.FX_LFO_CTRL, 0x01))).contains("PSG 2 never plays a note"))
        assertTrue(warnings(withSound { it.copy(lfoControl = 2) }).contains("The song's LFO control (Song view) turns the LFO on"))
        val withSource = with(blankWith(0, 0, Cell(48, 1, PceRules.FX_LFO_CTRL, 0x01)), 0, 1, 0, Cell(33, 1, PceRules.FX_VOLUME, 0))
        assertFalse(warnings(withSource).contains("never plays a note"))
        assertFalse(warnings(blankWith(0, 0, Cell(48, 1, PceRules.FX_LFO_CTRL, 0x80))).contains("never plays a note"))
        // Txx without a sample sound playing.
        assertTrue(warnings(blankWith(0, 0, Cell(48, 1, PceRules.FX_TIMER, 0x01))).contains("no sample sound plays"))
        assertFalse(warnings(blankWith(0, 0, Cell(48, 3, PceRules.FX_TIMER, 0x01))).contains("no sample sound plays"))
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
        assertEquals(listOf("Multispeed", "Master left", "Master right", "LFO frequency", "LFO control", "DDA rate"), PceSystem.songSettings.map { it.label })
        assertEquals(PceSongSettings.ALL, PceSystem.songSettings)
        fun setting(label: String) = PceSongSettings.ALL.single { it.label == label }
        val left = setting("Master left")
        assertEquals(0xEF, left.adjust(song, -1, 0).pce.master)
        assertEquals(0x0F, left.adjust(song, 0, -10).pce.master)
        assertEquals(song, left.adjust(song, 1, 0))
        assertEquals(song, left.reset(left.adjust(song, -3, 0)))
        assertEquals(0xFB, setting("Master right").adjust(song, 0, -1).pce.master)
        val freq = setting("LFO frequency")
        assertEquals(0x10, freq.adjust(song, 0, 1).pce.lfoFrequency)
        assertEquals(255, freq.adjust(song, 0, 40).pce.lfoFrequency)
        assertTrue(freq.value(song).startsWith("00   PSG 2's wave steps 256× slower"))
        val control = setting("LFO control")
        assertEquals(1, control.adjust(song, 1, 0).pce.lfoControl)
        assertEquals(0x80, control.adjust(song, 0, 1).pce.lfoControl)
        assertEquals(0x83, control.adjust(song, -1, 0).pce.lfoControl)
        assertTrue(control.value(control.adjust(song, 2, 0)).startsWith("02   Medium (period ±64): PSG 2's wave bends PSG 1's pitch"))
        assertEquals(song, control.reset(control.adjust(song, 2, 0)))
        val dda = setting("DDA rate")
        assertTrue(dda.value(song).startsWith("00   6991 Hz"))
        val slower = dda.adjust(song, 1, 0)
        assertEquals(1, slower.pce.timerReload)
        assertTrue(dda.value(slower).startsWith("01   3496 Hz"))
        assertEquals(127, dda.adjust(song, 0, 20).pce.timerReload)
        assertEquals(4, setting("Multispeed").adjust(song, 3, 0).pce.multispeed)
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

        val start = PceSystem.sharedState(base(), 0, 0)
        assertEquals(listOf("Master L 15 · R 15", "LFO off", "DDA 6991 Hz (timer 00)"), start.lines)
        assertEquals(List<String?>(6) { null }, start.tags)
        assertEquals("Master L 5 · R 10", PceSystem.sharedState(speed6, 0, 0).lines[0])
        val lfo = PceSystem.sharedState(speed6, 0, 1)
        assertEquals("LFO shallow (period ±16) · PSG 2 ÷ 16", lfo.lines[1])
        assertEquals(listOf("mod", "lfo", null, null, null, null), lfo.tags)
        val halted = PceSystem.sharedState(speed6, 0, 2)
        assertEquals("LFO deep (period ±256), halted · PSG 2 ÷ 16", halted.lines[1])
        assertEquals(List<String?>(6) { null }, halted.tags)
        assertEquals("DDA 3496 Hz (timer 01)", halted.lines[2])
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
        assertEquals("Pxx", fine.name)
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
        // 256 orders of distinct dense patterns pass 64 KB of pattern data before the 120 KB budget.
        val start = base()
        val patterns = (0 until 256).map { p ->
            val tracks = List(6) { ch -> List(Songs.ROWS) { row -> Cell(9 + (p + row + ch) % 87, 1, PceRules.FX_VOLUME, (p + row) % 32) } }
            Songs.blankPattern(start).copy(tracks = tracks)
        }
        val song = start.copy(patterns = patterns, orders = patterns.indices.toList())
        val errors = PceSystem.errors(song).joinToString()
        assertTrue(errors, errors.contains("pass the 64 KB their offsets reach"))
        assertThrows(IllegalArgumentException::class.java) { PceLowering.lower(song) }
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
        val e = assertThrows(IllegalArgumentException::class.java) { PceSamples.fromWav(ByteArray(40), 6991) }
        assertTrue(e.message!!.contains("Not a .wav file"))
        assertEquals(listOf(6991, 3496, 55), listOf(0, 1, 127).map(PceSamples::rateHz))
        assertEquals(1.0, PceSamples.seconds(6991, 0), 0.001)
        assertEquals(16, PceSamples.level(0))
        assertEquals(31, PceSamples.level(32767))
    }

    @Test
    fun starterKitIsDeterministicFiveBitAudio() {
        val kit = PceStarterKit.samples()
        assertEquals(kit, PceStarterKit.samples())
        assertEquals(listOf("Kick", "Snare", "Hat", "Tom"), kit.map { it.name })
        assertEquals(listOf(1747, 1398, 419, 2097), kit.map { it.data.size })
        assertTrue(kit.all { s -> s.problem() == null })
        // Integer synthesis: these exact frames on every platform (List.hashCode of each sample).
        assertEquals(listOf(422833869, -617283206, 185658038, -176467660), kit.map { it.data.hashCode() })
        assertEquals(listOf(19, 21, 23, 25), kit[PceStarterKit.KICK].data.take(4))
        val ins = PceStarterKit.instrument(2, "Hat")
        assertEquals(PceInstrument(name = "Hat", kind = PceKind.SAMPLE, sample = 2, root = 48), ins)
        assertEquals(6991, PceStarterKit.RATE_HZ)
        // A one-shot kick at its root lasts 15 ticks at the default rate: three rows at speed 6.
        val song = base().let { s ->
            val sound = s.pce.copy(samples = kit).withInstrument(6, PceStarterKit.instrument(PceStarterKit.KICK, "Kick"))
            with(s.copy(sound = sound, ticksPerRow = 6), 0, 3, 0, Cell(48, 6))
        }
        assertEquals(3, PceSystem.soundingRows(song, 0, 3, 0))
        assertNull(PceSystem.soundingRows(with(song, 0, 3, 0, Cell(48, 3)), 0, 3, 0))
        assertNull(PceSystem.soundingRows(with(song, 0, 3, 0, Cell(48, 1)), 0, 3, 0))
    }

    // ---- effects, exports and demo ---------------------------------------------------------------------------

    @Test
    fun describeReadsSharedEffectsAndRanges() {
        assertEquals("DDA rate 3496 Hz, every channel", PceRules.describe(null, PceRules.FX_TIMER, 0x01))
        assertEquals("DDA rate: invalid (00-7F)", PceRules.describe(null, PceRules.FX_TIMER, 0x80))
        assertEquals("LFO medium (period ±64), halted, every channel", PceRules.describe(null, PceRules.FX_LFO_CTRL, 0x82))
        assertEquals("LFO control: invalid (00-03, 80-83)", PceRules.describe(null, PceRules.FX_LFO_CTRL, 0x04))
        assertTrue(PceRules.describe(null, PceRules.FX_LFO_FREQ, 0).startsWith("LFO frequency 256: "))
        assertEquals("Master balance: left 5, right 10 of 15, every channel", PceRules.describe(null, PceRules.FX_MASTER, 0x5A))
        assertEquals("Pan: left 15, right 0 of 15", PceRules.describe(null, PceRules.FX_PAN, 0xF0))
        assertEquals("Volume 1F of 1F", PceRules.describe(null, PceRules.FX_VOLUME, 0x1F))
        assertEquals("Fine pitch +16/64 semitone (+25.0¢)", PceRules.describe(null, PceRules.FX_FINE, 0x90))
        assertEquals("Wave 05: the song has no such wave", PceRules.describe(base(), PceRules.FX_WAVE, 5))
        assertEquals("Wave 01 for the sounding tone and later notes", PceSystem.describeEffect(base(), 0, PceRules.FX_WAVE, 1))
        var song = blankWith(0, 0, Cell(57, 1, PceRules.FX_ARPEGGIO, 0x47))
        song = with(song, 0, 0, 2, Cell(effect = PceRules.FX_ARPEGGIO, param = 0x37))
        assertEquals("A-4 → C#5 → E-5 every tick", PceSystem.effectDetail(song, 0, 0, 0, 0))
        assertEquals("A-4 → C-5 → E-5 every tick", PceSystem.effectDetail(song, 0, 0, 2, 0))
        assertNull(PceSystem.effectDetail(song, 0, 0, 1, 0))
    }

    @Test
    fun vgmExportCallsTheNativeRenderer() {
        val demo = PceSystem.demo()
        val vgm = PceSystem.exports.single { it.extension == "vgm" }
        assertThrows(IllegalStateException::class.java) { vgm.export(demo) }
        var seen: Triple<ByteArray, Int, PceVgmTags>? = null
        PceVgm.renderer = PceVgmRenderer { image, maxTicks, tags -> seen = Triple(image, maxTicks, tags); byteArrayOf(1, 2, 3) }
        assertArrayEquals(byteArrayOf(1, 2, 3), vgm.export(demo))
        val (image, maxTicks, tags) = seen!!
        assertArrayEquals(PceLowering.lower(demo), image)
        assertEquals(36000, maxTicks)
        assertEquals(PceVgmTags("Turbo Lanterns", "Thor Tracker"), tags)
        vgm.export(demo.copy(sound = demo.pce.copy(multispeed = 2)))
        assertEquals(72000, seen!!.second)
    }

    @Test
    fun demoIsCleanAndShowsTheSystem() {
        val demo = PceSystem.demo()
        assertEquals("Turbo Lanterns", demo.title)
        assertEquals(emptyList<String>(), PceSystem.errors(demo))
        assertEquals(emptyList<String>(), PceSystem.warnings(demo))
        val sound = demo.pce
        assertEquals(PceStarterKit.samples(), sound.samples)
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
