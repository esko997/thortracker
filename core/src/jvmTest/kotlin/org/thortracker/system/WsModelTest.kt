package org.thortracker.system

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.WsLowering
import org.thortracker.backend.WsPlayerImage
import org.thortracker.editor.ViewKind
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.ProjectFormatException
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.ws.WonderSwanSystemBase
import org.thortracker.system.ws.WsDemoSong
import org.thortracker.system.ws.WsEnvelope
import org.thortracker.system.ws.WsFeed
import org.thortracker.system.ws.WsInstrument
import org.thortracker.system.ws.WsKind
import org.thortracker.system.ws.WsOutput
import org.thortracker.system.ws.WsPresets
import org.thortracker.system.ws.WsRules
import org.thortracker.system.ws.WsSample
import org.thortracker.system.ws.WsSamples
import org.thortracker.system.ws.WsSharedState
import org.thortracker.system.ws.WsSongSettings
import org.thortracker.system.ws.WsSound
import org.thortracker.system.ws.WsStarterKit
import org.thortracker.system.ws.WsSystem
import org.thortracker.system.ws.WsValidator
import org.thortracker.system.ws.WsVgm
import org.thortracker.system.ws.WsVgmRenderer
import org.thortracker.system.ws.WsVgmTags
import org.thortracker.system.ws.WscSystem
import org.thortracker.system.ws.ws

/**
 * The WonderSwan systems' model: registration and lanes, project files, the validator's mirror of the loader's checks
 * (lane fit, Color-only facilities, effect lanes and parameters, sound records), the shared-state walk with the DMA
 * owner, sounding rows of one-shot samples, the player's pitch arithmetic, the lowering's header, signed copies and data
 * budget, `.wav` import, the starter kit, the VGM seam and the demos. Songs here use their own sounds, waves and
 * samples, not the presets.
 */
class WsModelTest {
    @After
    fun noRenderer() {
        WsVgm.renderer = null
    }

    private val ramp = List(32) { it / 2 }
    private val square = List(32) { if (it < 16) 15 else 0 }

    private val tone = WsInstrument("tone", WsKind.TONE, wave = 0)
    private val noise = WsInstrument("noise", WsKind.NOISE, tap = 3)
    private val cpuSample = WsInstrument("cpu", WsKind.SAMPLE, sample = 0, root = 48)
    private val dmaSample = WsInstrument("dma", WsKind.SAMPLE, sample = 0, feed = WsFeed.DMA, rate = 1)
    private val stereoSample = WsInstrument("stereo", WsKind.SAMPLE, sample = 1, feed = WsFeed.DMA, rate = 2)

    private val mono = WsSample("m", List(100) { (it * 7) % 256 })
    private val stereo = WsSample("s", List(40) { (it * 11) % 256 }, stereo = true)

    /** Slots: 01 tone, 02 noise, 03 CPU-fed sample 0, 04 DMA sample 0 (Color), 05 DMA stereo sample 1 (Color). */
    private fun base(color: Boolean): Song {
        val system: WonderSwanSystemBase = if (color) WscSystem else WsSystem
        val instruments = List(WsRules.SLOTS) {
            when (it) {
                0 -> tone
                1 -> noise
                2 -> cpuSample
                3 -> if (color) dmaSample else null
                4 -> if (color) stereoSample else null
                else -> null
            }
        }
        return system.blank().copy(sound = WsSound(color, instruments, listOf(ramp, square), if (color) listOf(mono, stereo) else listOf(mono)))
    }

    private fun with(song: Song, order: Int, lane: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != lane) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun withSound(song: Song, f: (WsSound) -> WsSound) = song.copy(sound = f(song.ws))

    private fun errors(song: Song) = song.system.errors(song)

    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF
    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)
    private fun u32(image: ByteArray, at: Int) = u16(image, at) or (u16(image, at + 2) shl 16)

    // ---- shape -----------------------------------------------------------------------------------------------------

    @Test
    fun bothModelsAreRegisteredWithTheirLanes() {
        assertEquals(WsSystem, SoundSystems.of("ws-wonderswan"))
        assertEquals(WscSystem, SoundSystems.of("ws-wonderswan-color"))
        assertEquals(WsSystem.family, WscSystem.family)
        assertEquals(4, WsSystem.channels.size)
        assertEquals(5, WscSystem.channels.size)
        assertTrue((WsSystem.channels + WscSystem.channels).all { it.tonal && it.bank == 0 })
        assertEquals(19..95, WsSystem.noteRange)
        assertEquals(19..95, WscSystem.noteRange)
        for (lane in 0 until 5) assertEquals(4, WscSystem.effectColumns(lane))
        assertTrue(ViewKind.WAVE in WsSystem.views && ViewKind.INSTRUMENT in WscSystem.views)
        assertEquals(3072000.0 / 40704, WsSystem.tickHz, 1e-9)
        for (system in listOf(WsSystem, WscSystem)) {
            val engine = system.engine
            assertEquals(system.id, engine.id)
            assertEquals(131072, engine.imageSize)
            assertEquals(4528, engine.seekTicks)
            assertEquals(57, engine.note(base(system.color), 0, 57, 1))
            assertEquals(-17, engine.fine(0, 57, -17))
            assertEquals(WsSound.serializer(), system.soundSerializer)
            assertEquals(system, system.blank().system)
        }
    }

    @Test
    fun effectsAreOfferedOnlyWhereTheirHardwareIs() {
        fun letters(system: WonderSwanSystemBase, lane: Int) = system.effectsFor(lane).map { it.char }.toSet()
        assertTrue('N' in letters(WsSystem, 3) && 'N' !in letters(WsSystem, 2))
        assertTrue('U' in letters(WsSystem, 2) && 'V' in letters(WsSystem, 2) && 'U' !in letters(WsSystem, 0))
        assertTrue('W' in letters(WsSystem, 0) && 'W' !in letters(WscSystem, 4))
        assertTrue('H' in letters(WscSystem, 1) && 'H' in letters(WscSystem, 4) && 'H' !in letters(WscSystem, 0))
        assertTrue(WsSystem.effects.none { it.code == WsRules.FX_HOLD })
        assertTrue(WscSystem.effects.any { it.code == WsRules.FX_HOLD })
        for (lane in 0 until 4) assertFalse('H' in letters(WsSystem, lane))
        // The spec's codes and only those.
        assertEquals("0 1 2 3 4 8 A B C D F G H K N P S T U V W", WscSystem.effects.joinToString(" ") { it.char.toString() })
    }

    @Test
    fun blankSongsAreValidAndUseTheBuiltInWaves() {
        for (system in listOf(WsSystem, WscSystem)) {
            val blank = system.blank()
            assertEquals(emptyList<String>(), system.errors(blank))
            assertEquals(emptyList<String>(), system.warnings(blank))
            assertEquals(WsPresets.WAVES.map { it.second }, blank.ws.waves)
            assertTrue(blank.ws.waves.all { w -> w.size == 32 && w.all { it in 0..15 } && w.min() == 0 && w.max() == 15 })
            assertTrue(blank.ws.instruments.filterNotNull().none { it.kind == WsKind.SAMPLE })
            assertEquals(emptyList<String>(), system.errors(base(system.color)))
        }
    }

    // ---- project files -------------------------------------------------------------------------------------------

    @Test
    fun projectFilesRoundTripBothModels() {
        for (color in listOf(false, true)) {
            val cell = Cell(57, 1, WsRules.FX_VOLUME, 0x0C).withEffect(1, WsRules.FX_PAN, 0xF0).withEffect(3, WsRules.FX_FINE, 0x90)
            val song = withSound(with(base(color), 0, 0, 0, cell)) {
                it.copy(output = WsOutput.SPEAKER, speakerShift = 1, timerReload = 7)
                    .withInstrument(6, tone.copy(sweep = -40, sweepTime = 31, waves = WsEnvelope(listOf(0, 1), loop = 0)))
                    .withInstrument(7, noise.copy(lfsrReset = true, taps = WsEnvelope(listOf(7, 0), release = 1)))
            }
            val back = ProjectCodec.decode(ProjectCodec.encode(song))
            assertEquals(song, back)
            assertEquals(song.system, back.system)
        }
        for (s in listOf(WsSystem.demo(), WscSystem.demo(), WsDemoSong.shortest(false), WsDemoSong.shortest(true))) {
            assertEquals(s, ProjectCodec.decode(ProjectCodec.encode(s)))
        }
        // A Color song's data under the original's backend is refused.
        val text = ProjectCodec.encode(base(true)).replace("\"ws-wonderswan-color\"", "\"ws-wonderswan\"")
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(text) }
    }

    // ---- validation ----------------------------------------------------------------------------------------------

    @Test
    fun soundsPlayOnlyOnTheLanesThatFitThem() {
        val color = base(true)
        fun problem(lane: Int, slot: Int) = WsValidator.cellProblem(color, lane, Cell(60, slot))
        for (lane in 0..3) assertNull(problem(lane, 1))
        assertNotNull(problem(4, 1))
        assertNull(problem(3, 2))
        for (lane in listOf(0, 1, 2, 4)) assertNotNull(problem(lane, 2))
        assertNull(problem(1, 3))
        assertNull(problem(4, 3))
        for (lane in listOf(0, 2, 3)) assertNotNull(problem(lane, 3))
        assertNull(problem(1, 4))
        assertNull(problem(4, 4))
        // Stereo samples: Hyper Voice only.
        assertNotNull(problem(1, 5))
        assertNull(problem(4, 5))
        assertTrue(errors(with(color, 0, 1, 0, Cell(60, 5))).isNotEmpty())
        // fits() is the same rule.
        assertTrue(WsValidator.fits(color.ws, stereoSample, 4))
        assertFalse(WsValidator.fits(color.ws, stereoSample, 1))
        assertFalse(WsValidator.fits(color.ws, noise, 0))
    }

    @Test
    fun theOriginalModelRefusesColorOnlyFacilities() {
        val song = base(false)
        // A DMA-fed sound is an error on the original (in a slot, played or not), and cannot fit any lane.
        val dma = withSound(song) { it.withInstrument(4, dmaSample) }
        assertTrue(errors(dma).isNotEmpty())
        assertNotNull(WsValidator.instrumentProblem(dma.ws, 4))
        for (lane in 0 until 4) assertFalse(WsValidator.fits(dma.ws, dmaSample, lane))
        assertNotNull(WsValidator.laneProblem(dma.ws, dmaSample, 1))
        // Hxx exists only on the Color.
        assertTrue(errors(with(song, 0, 1, 0, Cell(effect = WsRules.FX_HOLD, param = 1))).isNotEmpty())
        assertTrue(errors(with(base(true), 0, 1, 0, Cell(effect = WsRules.FX_HOLD, param = 1))).isEmpty())
        // Its patterns have four lanes.
        assertTrue(WsSystem.structuralErrors(song.copy(patterns = listOf(org.thortracker.model.Pattern.blank(5)))).isNotEmpty())
        // A Color song is not an original-model song.
        assertTrue(WsSystem.structuralErrors(base(true)).isNotEmpty())
    }

    @Test
    fun effectLanesAndParametersAreTheLoaders() {
        val song = base(true)
        fun ok(lane: Int, code: Int, param: Int) = errors(with(song, 0, lane, 0, Cell(effect = code, param = param))).isEmpty()
        assertTrue(ok(0, WsRules.FX_VOLUME, 0x0F)); assertFalse(ok(0, WsRules.FX_VOLUME, 0x10))
        assertFalse(ok(0, WsRules.FX_SPEED, 0)); assertTrue(ok(0, WsRules.FX_SPEED, 1))
        assertFalse(ok(0, WsRules.FX_TIMER, 0)); assertTrue(ok(4, WsRules.FX_TIMER, 0xFF))
        assertTrue(ok(4, WsRules.FX_HOLD, 1)); assertFalse(ok(4, WsRules.FX_HOLD, 2)); assertFalse(ok(2, WsRules.FX_HOLD, 0))
        assertTrue(ok(4, WsRules.FX_SHIFT, 3)); assertFalse(ok(0, WsRules.FX_SHIFT, 4))
        assertTrue(ok(3, WsRules.FX_TAP, 7)); assertFalse(ok(3, WsRules.FX_TAP, 8)); assertFalse(ok(2, WsRules.FX_TAP, 0))
        assertTrue(ok(2, WsRules.FX_SWEEP, 0x80)); assertFalse(ok(1, WsRules.FX_SWEEP, 1))
        assertTrue(ok(2, WsRules.FX_SWEEP_TIME, 0x1F)); assertFalse(ok(2, WsRules.FX_SWEEP_TIME, 0x20)); assertFalse(ok(3, WsRules.FX_SWEEP_TIME, 0))
        assertTrue(ok(3, WsRules.FX_WAVE, 1)); assertFalse(ok(3, WsRules.FX_WAVE, 2)); assertFalse(ok(4, WsRules.FX_WAVE, 0))
        assertFalse(ok(0, WsRules.FX_JUMP, 1)); assertTrue(ok(0, WsRules.FX_JUMP, 0))
        for (code in listOf(5, 6, 7, 9, 14, 18, 19, 21, 22, 24, 26, 27, 33, 34, 35)) assertFalse("code $code", ok(0, code, 1))
        // Notes G-1..B-7, defined sounds.
        assertTrue(errors(with(song, 0, 0, 0, Cell(19, 1))).isEmpty())
        assertTrue(errors(with(song, 0, 0, 0, Cell(95, 1))).isEmpty())
        assertTrue(errors(with(song, 0, 0, 0, Cell(18, 1))).isNotEmpty())
        assertTrue(errors(with(song, 0, 0, 0, Cell(96, 1))).isNotEmpty())
        assertTrue(errors(with(song, 0, 0, 0, Cell(60, 9))).isNotEmpty())
    }

    @Test
    fun soundRecordsAreCheckedAsTheLoaderDoes() {
        val song = base(true)
        fun bad(ins: WsInstrument) = errors(withSound(song) { it.withInstrument(9, ins) }).isNotEmpty()
        assertFalse(bad(tone))
        assertTrue(bad(tone.copy(volume = WsEnvelope(listOf(16)))))
        assertTrue(bad(tone.copy(volume = WsEnvelope(List(256) { 15 }))))
        assertFalse(bad(tone.copy(pitch = WsEnvelope(List(255) { -128 }))))
        assertTrue(bad(tone.copy(arpeggio = WsEnvelope(listOf(64)))))
        assertTrue(bad(tone.copy(volume = WsEnvelope(listOf(15, 10, 5), loop = 1, release = 1))))
        assertTrue(bad(tone.copy(wave = 2)))
        assertTrue(bad(tone.copy(waves = WsEnvelope(listOf(0, 2)))))
        assertTrue(bad(tone.copy(sweep = 128)))
        assertTrue(bad(tone.copy(sweepTime = 32)))
        assertTrue(bad(noise.copy(tap = 8)))
        assertTrue(bad(noise.copy(taps = WsEnvelope(listOf(8)))))
        assertFalse(bad(noise.copy(wave = 40)))
        assertTrue(bad(cpuSample.copy(sample = 2)))
        assertTrue(bad(cpuSample.copy(loop = true, loopStart = 100)))
        assertFalse(bad(cpuSample.copy(loop = false, loopStart = 100)))
        assertFalse(bad(dmaSample.copy(loop = true, loopStart = 500)))
        assertTrue(bad(dmaSample.copy(rate = 4)))
        assertTrue(bad(cpuSample.copy(root = 18)))
        // Song-level values.
        assertTrue(errors(withSound(song) { it.copy(timerReload = 0) }).isNotEmpty())
        assertTrue(errors(withSound(song) { it.copy(speakerShift = 4) }).isNotEmpty())
        assertTrue(errors(withSound(song) { it.copy(waves = listOf(List(32) { 16 })) }).isNotEmpty())
        assertTrue(errors(withSound(song) { it.copy(samples = listOf(mono, WsSample("odd", listOf(1, 2, 3), stereo = true))) }).isNotEmpty())
        assertTrue(errors(withSound(song) { it.copy(samples = listOf(mono, WsSample("big", List(256) { 256 }))) }).isNotEmpty())
        assertTrue(errors(withSound(song) { it.copy(samples = listOf(mono, WsSample("long", List(65536) { 128 }))) }).isNotEmpty())
        assertNull(WsSample("stereo max", List(2 * 65535) { 128 }, stereo = true).problem())
        assertNotNull(WsSample("stereo over", List(2 * 65536) { 128 }, stereo = true).problem())
    }

    @Test
    fun auditionFollowsTheLaneFit() {
        val color = base(true)
        val engine = WscSystem.engine
        assertNull(engine.auditionProblem(color, 0, 57, 1, 0))
        assertNotNull(engine.auditionProblem(color, 4, 57, 1, 0))
        assertNull(engine.auditionProblem(color, 3, 57, 2, 0))
        assertNotNull(engine.auditionProblem(color, 1, 57, 2, 0))
        assertNull(engine.auditionProblem(color, 4, 48, 5, 0))
        assertNotNull(engine.auditionProblem(color, 1, 48, 5, 0))
        assertNotNull(engine.auditionProblem(color, 0, 18, 1, 0))
        assertNotNull(engine.auditionProblem(color, 0, 57, 9, 0))
        assertNotNull(engine.auditionProblem(color, 0, 57, 1, 128))
        assertNotNull(WsSystem.engine.auditionProblem(base(false), 4, 48, 3, 0))
    }

    // ---- shared state, sounding rows -----------------------------------------------------------------------------

    @Test
    fun sharedStateFollowsTimerShiftAndTheDmaOwner() {
        var song = base(true).copy(ticksPerRow = 6)
        song = with(song, 0, 0, 0, Cell(effect = WsRules.FX_TIMER, param = 5).withEffect(1, WsRules.FX_SHIFT, 1))
        song = with(song, 0, 1, 1, Cell(48, 4))
        song = with(song, 0, 4, 2, Cell(48, 5))
        song = with(song, 0, 4, 3, Cell(Cell.CUT))
        song = with(song, 0, 1, 4, Cell(48, 4))
        song = with(song, 0, 1, 5, Cell(48, 3))
        // A delayed row the speed reaches sets the timer; one it never reaches does not; an invalid T is skipped.
        song = with(song, 0, 2, 6, Cell(effect = WsRules.FX_TIMER, param = 9).withEffect(1, WsRules.FX_DELAY, 3))
        song = with(song, 0, 3, 7, Cell(effect = WsRules.FX_TIMER, param = 11).withEffect(1, WsRules.FX_DELAY, 8))
        song = with(song, 0, 0, 8, Cell(effect = WsRules.FX_TIMER, param = 0))
        assertEquals(WsSharedState.start(song.ws), WsSharedState.at(song, 7, 0))
        assertEquals(WsSharedState.Shared(5, 1, -1), WsSharedState.at(song, 0, 0))
        assertEquals(1, WsSharedState.at(song, 0, 1).dmaOwner)
        assertEquals(4, WsSharedState.at(song, 0, 2).dmaOwner)
        assertEquals(-1, WsSharedState.at(song, 0, 3).dmaOwner)
        assertEquals(1, WsSharedState.at(song, 0, 4).dmaOwner)
        assertEquals(-1, WsSharedState.at(song, 0, 5).dmaOwner)
        assertEquals(9, WsSharedState.at(song, 0, 6).timer)
        assertEquals(9, WsSharedState.at(song, 0, 7).timer)
        assertEquals(9, WsSharedState.at(song, 0, 8).timer)
        assertEquals(listOf(null, "dma", null, null, null), WscSystem.sharedState(song, 0, 1).tags)
        assertEquals(listOf(null, null, null, null, null), WscSystem.sharedState(song, 0, 3).tags)
        // The original has no DMA line.
        assertEquals(WscSystem.sharedState(song, 0, 0).lines.size - 1, WsSystem.sharedState(base(false), 0, 0).lines.size)
    }

    @Test
    fun oneShotSamplesSoundForTheirLength() {
        val kit = WsStarterKit.samples()
        val kick = kit[WsStarterKit.KICK]
        assertEquals(1500, kick.frames)
        fun song(ins: WsInstrument, lane: Int, note: Int = 48): Song {
            val s = withSound(base(true).copy(ticksPerRow = 6)) { it.copy(samples = kit).withInstrument(9, ins) }
            return with(s, 0, lane, 0, Cell(note, 9))
        }
        // 1500 frames at 6000 Hz on the root: 0.25 s = 19 ticks = 4 rows at speed 6.
        val cpu = WsStarterKit.instrument(WsStarterKit.KICK, "kick")
        assertEquals(WsSound.DEFAULT_TIMER, base(true).ws.timerReload)
        assertEquals(4, WscSystem.soundingRows(song(cpu, 1), 0, 1, 0))
        assertEquals(4, WscSystem.soundingRows(song(cpu, 4), 0, 4, 0))
        // An octave up plays twice as fast: 10 ticks, 2 rows.
        assertEquals(2, WscSystem.soundingRows(song(cpu, 1, 60), 0, 1, 0))
        // DMA: 1500 bytes at 6000 bytes per second whatever the note.
        val dma = WsStarterKit.instrument(WsStarterKit.KICK, "kick", WsFeed.DMA)
        assertEquals(4, WscSystem.soundingRows(song(dma, 1, 72), 0, 1, 0))
        assertEquals(2, WscSystem.soundingRows(song(dma.copy(rate = 2), 1), 0, 1, 0))
        assertNull(WscSystem.soundingRows(song(dma.copy(loop = true), 1), 0, 1, 0))
        assertNull(WscSystem.soundingRows(song(cpu, 0), 0, 0, 0))
        assertNull(WscSystem.soundingRows(song(tone, 2), 0, 2, 0))
    }

    // ---- pitch ---------------------------------------------------------------------------------------------------

    @Test
    fun pitchArithmeticIsThePlayers() {
        assertEquals(218, WsRules.period(57 * 64))
        assertEquals(96000.0 / 218, WsRules.toneHz(57), 1e-9)
        assertTrue(WsRules.period(WsPlayerImage.PITCH_MIN) <= 2048)
        assertTrue(WsRules.period(WsPlayerImage.PITCH_MIN - 1) == WsRules.period(WsPlayerImage.PITCH_MIN))
        assertTrue(WsRules.period(19 * 64) <= 2048)
        assertTrue(WsRules.period(95 * 64) >= 1)
        assertEquals(65536L, WsRules.sampleStep(48 * 64, 48))
        assertEquals(131072L, WsRules.sampleStep(60 * 64, 48))
        assertEquals(32768L, WsRules.sampleStep(36 * 64, 48))
        assertEquals(listOf(6000, 12000, 47), listOf(2, 1, 255).map(WsSamples::timerRateHz))
        // Fine pitch rides on Pxx in a free column.
        val fine = WsSystem.fine
        val busy = Cell(57, 1, WsRules.FX_VIBRATO, 0x23)
        val tuned = fine.write(busy, 57, 16)!!
        assertEquals(WsRules.FX_FINE, tuned.effectCode(1))
        assertEquals(0x90, tuned.effectParam(1))
        assertEquals(16, fine.of(tuned))
        assertEquals(busy, fine.write(tuned, 57, 0))
        assertNull(fine.of(Cell(57, 1, WsRules.FX_FINE, 0x70)))
    }

    // ---- lowering ------------------------------------------------------------------------------------------------

    @Test
    fun theHeaderCarriesModelSettingsAndCounts() {
        for (color in listOf(false, true)) {
            val song = withSound(base(color).copy(ticksPerRow = 5)) { it.copy(output = WsOutput.SPEAKER, speakerShift = 2, timerReload = 0xFF) }
            val image = WsLowering.lower(song)
            assertEquals("TTWS", image.copyOfRange(0, 4).decodeToString())
            assertEquals(1, u8(image, 4))
            assertEquals(if (color) 1 else 0, u8(image, 5))
            assertEquals(5, u8(image, 6))
            assertEquals(if (color) 5 else 3, u8(image, 7))
            assertEquals(1, u16(image, 8))
            assertEquals(0, u16(image, 10))
            assertEquals(2, u8(image, 12))
            assertEquals(if (color) 2 else 1, u8(image, 13))
            assertEquals(0xFF, u8(image, 14))
            assertEquals(2, u8(image, 15))
            assertEquals(1, u8(image, 16))
            for (i in 17 until 20) assertEquals(0, u8(image, i))
            assertEquals(WsLowering.dataSize(song), u32(image, 20))
            for (i in 24 until 64) assertEquals("header byte $i", 0, u8(image, i))
        }
    }

    @Test
    fun signedCopiesAreWrittenForDmaPlayedSamplesOnly() {
        val song = base(true)
        val image = WsLowering.lower(song)
        val entry = { s: Int -> WsPlayerImage.OFF_SAMPLES + WsPlayerImage.SAMPLE_ENTRY_SIZE * s }
        assertEquals(WsPlayerImage.SAMPLE_SIGNED, u8(image, entry(0) + WsPlayerImage.S_FLAGS))
        assertEquals(WsPlayerImage.SAMPLE_STEREO or WsPlayerImage.SAMPLE_SIGNED, u8(image, entry(1) + WsPlayerImage.S_FLAGS))
        assertEquals(20, u32(image, entry(1) + WsPlayerImage.S_FRAMES))
        val unsigned = u32(image, entry(0))
        val signed = u32(image, entry(0) + WsPlayerImage.S_SIGNED_OFFSET)
        for (i in 0 until mono.data.size) {
            assertEquals(mono.data[i], u8(image, WsPlayerImage.OFF_DATA + unsigned + i))
            assertEquals(mono.data[i] xor 0x80, u8(image, WsPlayerImage.OFF_DATA + signed + i))
        }
        // Without DMA instruments: no copies, and the data shrinks by exactly their bytes.
        val cpuOnly = withSound(song) { it.withInstrument(4, null).withInstrument(5, null) }
        val plain = WsLowering.lower(cpuOnly)
        assertEquals(0, u8(plain, entry(0) + WsPlayerImage.S_FLAGS))
        assertEquals(0, u32(plain, entry(0) + WsPlayerImage.S_SIGNED_OFFSET))
        assertEquals(mono.data.size + stereo.data.size, WsLowering.dataSize(song) - WsLowering.dataSize(cpuOnly))
        assertEquals(mono.data.size + stereo.data.size, WsLowering.signedCopyBytes(song))
        // The original model never writes copies.
        assertEquals(0, WsLowering.signedCopyBytes(base(false)))
    }

    @Test
    fun theDataBudgetCountsSamplesOnceAndTheirCopies() {
        val big = WsSample("big", List(WsSample.MAX_FRAMES) { it % 256 })
        val other = WsSample("other", List(WsSample.MAX_FRAMES) { 255 - it % 256 })
        // Without the DMA sounds, so no sample gets a signed copy until one is added below.
        val song = withSound(base(true)) { it.withInstrument(4, null).withInstrument(5, null) }
        // Two identical long samples fit: stored once.
        val twice = withSound(song) { it.copy(samples = listOf(big, big.copy(name = "again"))) }
        assertEquals(emptyList<String>(), errors(twice))
        assertEquals(WsPlayerImage.DATA_CAPACITY - WsLowering.dataSize(twice), WsSamples.free(twice))
        // Two different ones do not (131070 > 122880 bytes).
        val over = withSound(song) { it.copy(samples = listOf(big, other)) }
        assertTrue(errors(over).isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { WsLowering.lower(over) }
        assertTrue(WsSamples.free(over) < 0)
        // One long sample fits CPU-fed but not with its signed copy for Sound DMA.
        val one = withSound(song) { it.copy(samples = listOf(big, stereo)).withInstrument(4, null) }
        assertTrue(errors(one).isEmpty())
        val dma = withSound(one) { it.withInstrument(4, dmaSample) }
        assertTrue(errors(dma).isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { WsLowering.lower(dma) }
    }

    @Test
    fun patternsAndEnvelopesMustStartWithinTheirU16Offsets() {
        val start = base(false)
        val patterns = (0 until 256).map { p ->
            val tracks = List(4) { ch -> List(Songs.ROWS) { row -> Cell(19 + (p + row + ch) % 77, 1, WsRules.FX_VOLUME, (p + row) % 16) } }
            Songs.blankPattern(start).copy(tracks = tracks)
        }
        val song = start.copy(patterns = patterns, orders = patterns.indices.toList())
        assertTrue(errors(song).isNotEmpty())
        assertNotNull(WsLowering.sizeProblem(song))
        assertThrows(IllegalArgumentException::class.java) { WsLowering.lower(song) }
    }

    @Test
    fun arrangementRenumbersJumpsInEveryColumn() {
        val start = base(true)
        val patterns = List(3) { Songs.blankPattern(start) }
        var song = start.copy(patterns = patterns, orders = listOf(0, 1, 2), parkedOrders = listOf(1))
        song = with(song, 2, 3, 5, Cell(48, 2).withEffect(2, WsRules.FX_JUMP, 2))
        val arranged = WscSystem.arranged(song)
        assertEquals(listOf(0, 2), arranged.orders)
        assertEquals(1, WscSystem.jumpTarget(arranged.patterns[2].tracks[3][5]))
        assertEquals(0, WscSystem.jumpTarget(WscSystem.patternLoop(song, 2).patterns[2].tracks[3][5]))
        assertEquals(2, u16(WsLowering.lower(song), WsPlayerImage.H_ORDERS))
    }

    @Test
    fun songSettingsStepTheOutputShiftAndTimer() {
        val song = base(false)
        fun setting(label: String) = WsSongSettings.ALL.single { it.label == label }
        assertEquals(WsSongSettings.ALL, WsSystem.songSettings)
        val output = setting("Output")
        assertEquals(WsOutput.SPEAKER, output.adjust(song, 1, 0).ws.output)
        assertEquals(song, output.adjust(output.adjust(song, 1, 0), -1, 0))
        assertEquals(song, output.reset(output.adjust(song, 1, 0)))
        val shift = setting("Speaker shift")
        assertEquals(0, shift.adjust(song, -9, 0).ws.speakerShift)
        assertEquals(3, shift.adjust(song, 9, 0).ws.speakerShift)
        val timer = setting("Voice timer")
        assertEquals(1, timer.adjust(song, -9, 0).ws.timerReload)
        assertEquals(255, timer.adjust(song, 0, 40).ws.timerReload)
        assertEquals(song, timer.reset(timer.adjust(song, 3, 0)))
    }

    // ---- samples ---------------------------------------------------------------------------------------------------

    /** A 16-bit PCM `.wav` of [values] (interleaved when [channels] is 2) at [rate] Hz. */
    private fun wav(rate: Int, channels: Int, values: IntArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) { for (k in 0 until 4) out.write(v shr (8 * k) and 0xFF) }
        fun u16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); u32(36 + 2 * values.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); u32(16); u16(1); u16(channels); u32(rate); u32(rate * 2 * channels); u16(2 * channels); u16(16)
        out.write("data".toByteArray()); u32(2 * values.size)
        for (v in values) u16(v and 0xFFFF)
        return out.toByteArray()
    }

    @Test
    fun wavImportMakesUnsignedBytesAndKeepsStereoOnlyWhenAsked() {
        // The same rate passes values through the levels: (v + 32896) >> 8.
        val m = WsSamples.fromWav(wav(6000, 1, intArrayOf(-32768, 0, 32767)), "m", 6000, stereo = false)
        assertEquals(listOf(0, 128, 255), m.data)
        assertFalse(m.stereo)
        // Twice the rate: the mean of two source samples.
        assertEquals(listOf(132, 0), WsSamples.fromWav(wav(12000, 1, intArrayOf(0, 2048, -32768, -32768)), "m", 6000, stereo = false).data)
        // Stereo kept: left, right pairs; mixed when not asked.
        val lr = intArrayOf(32767, -32768, 0, 0)
        val s = WsSamples.fromWav(wav(6000, 2, lr), "s", 6000, stereo = true)
        assertTrue(s.stereo)
        assertEquals(listOf(255, 0, 128, 128), s.data)
        assertEquals(2, s.frames)
        val mixed = WsSamples.fromWav(wav(6000, 2, lr), "s", 6000, stereo = false)
        assertFalse(mixed.stereo)
        assertEquals(2, mixed.frames)
        assertThrows(IllegalArgumentException::class.java) { WsSamples.fromWav(ByteArray(40), "x", 6000, stereo = false) }
        assertEquals(128, WsSamples.level(0))
    }

    @Test
    fun sharedWavReaderKeepsChannelCountWithoutAllocatingUnusedStereoCopies() {
        val bytes = wav(6000, 2, intArrayOf(12000, -4000, -16000, 8000))
        val mixed = org.thortracker.system.nes.Dpcm.Wav.read(bytes)
        assertEquals(2, mixed.channels)
        assertArrayEquals(shortArrayOf(4000, -4000), mixed.samples)
        assertTrue(mixed.channelSamples.isEmpty())
        val kept = org.thortracker.system.nes.Dpcm.Wav.read(bytes, preserveChannels = true)
        assertEquals(2, kept.channels)
        assertArrayEquals(mixed.samples, kept.samples)
        assertArrayEquals(shortArrayOf(12000, -16000), kept.channelSamples[0])
        assertArrayEquals(shortArrayOf(-4000, 8000), kept.channelSamples[1])
    }

    @Test
    fun starterKitIsDeterministicEightBitAudio() {
        val kit = WsStarterKit.samples()
        assertEquals(kit, WsStarterKit.samples())
        assertEquals(listOf(1500, 1200, 360, 1800), kit.map { it.frames })
        assertTrue(kit.all { s -> s.problem() == null && !s.stereo && s.rate == 6000 })
        assertTrue(kit.all { s -> s.data.any { it > 160 } && s.data.any { it < 96 } })
        assertEquals(WsSamples.timerRateHz(WsSound.DEFAULT_TIMER), WsStarterKit.RATE_HZ)
        assertEquals(WsSamples.dmaRateHz(WsStarterKit.DMA_RATE), WsStarterKit.RATE_HZ)
    }

    // ---- exports and demos ---------------------------------------------------------------------------------------

    @Test
    fun vgmExportRefusesHyperVoiceAndCallsTheNativeRenderer() {
        val demo = WsSystem.demo()
        val vgm = WsSystem.exports.single { it.extension == "vgm" }
        assertThrows(IllegalStateException::class.java) { vgm.export(demo) }
        var seen: Triple<ByteArray, Int, WsVgmTags>? = null
        WsVgm.renderer = WsVgmRenderer { image, maxTicks, tags -> seen = Triple(image, maxTicks, tags); byteArrayOf(1, 2, 3) }
        assertArrayEquals(byteArrayOf(1, 2, 3), vgm.export(demo))
        val (image, maxTicks, tags) = seen!!
        assertArrayEquals(WsLowering.lower(demo), image)
        assertEquals(45283, maxTicks)
        assertEquals(WsVgmTags(demo.title, demo.artist), tags)
        // A Hyper Voice note refuses the export; an OFF or effects alone on Hyper Voice do not.
        val color = base(true)
        assertThrows(IllegalArgumentException::class.java) { WscSystem.exports.single().export(with(color, 0, 4, 3, Cell(48, 3))) }
        assertArrayEquals(byteArrayOf(1, 2, 3), WscSystem.exports.single().export(with(color, 0, 4, 3, Cell(Cell.CUT, 0, WsRules.FX_HOLD, 1))))
        assertArrayEquals(byteArrayOf(1, 2, 3), WscSystem.exports.single().export(with(color, 0, 1, 0, Cell(48, 4))))
        // A note on a row past the pattern's length is not played, so not refused.
        val short = color.copy(patterns = color.patterns.map { it.copy(length = 8) })
        assertArrayEquals(byteArrayOf(1, 2, 3), WscSystem.exports.single().export(with(short, 0, 4, 20, Cell(48, 3))))
    }

    @Test
    fun demosAreCleanAndShowTheirModel() {
        fun uses(song: Song, code: Int, lane: Int? = null): Boolean = song.orders.toSet().any { p ->
            song.patterns[p].tracks.withIndex().any { (ch, track) ->
                (lane == null || ch == lane) && track.any { c -> (0..c.more.size).any { c.effectCode(it) == code && (code != 0 || c.effectParam(it) != 0) } }
            }
        }
        fun kinds(song: Song, lane: Int): Set<Pair<WsKind, WsFeed>> = song.orders.toSet().flatMap { p ->
            song.patterns[p].tracks[lane].filter { it.instrument != 0 }.map { song.ws.instrument(it.instrument)!!.let { i -> i.kind to i.feed } }
        }.toSet()
        val mono = WsSystem.demo()
        assertEquals(emptyList<String>(), WsSystem.errors(mono))
        assertEquals(emptyList<String>(), WsSystem.warnings(mono))
        assertFalse(mono.ws.color)
        assertTrue(uses(mono, WsRules.FX_WAVE, 0) && uses(mono, WsRules.FX_TAP, 3) && uses(mono, WsRules.FX_SWEEP, 2) && uses(mono, WsRules.FX_SWEEP_TIME, 2))
        assertTrue((WsKind.SAMPLE to WsFeed.CPU) in kinds(mono, 1))
        assertTrue(kinds(mono, 3).all { it.first == WsKind.NOISE })
        assertTrue(mono.orders.toSet().any { p -> mono.patterns[p].tracks[2].any { c -> c.instrument != 0 && mono.ws.instrument(c.instrument)!!.sweep != 0 } })

        val color = WscSystem.demo()
        assertEquals(emptyList<String>(), WscSystem.errors(color))
        assertEquals(emptyList<String>(), WscSystem.warnings(color))
        assertTrue(color.ws.color)
        assertTrue(uses(color, WsRules.FX_HOLD, 1))
        assertTrue((WsKind.SAMPLE to WsFeed.DMA) in kinds(color, 1))
        assertTrue((WsKind.SAMPLE to WsFeed.DMA) in kinds(color, 4) && (WsKind.SAMPLE to WsFeed.CPU) in kinds(color, 4))
        val hvSounds = color.orders.toSet().flatMap { p -> color.patterns[p].tracks[4].filter { it.instrument != 0 }.map { color.ws.instrument(it.instrument)!! } }
        assertTrue(hvSounds.any { it.isDma && color.ws.samples[it.sample].stereo })
        assertTrue(hvSounds.any { it.isDma && it.backwards })
        assertTrue(WsValidator.usesHyperVoice(color))
        assertFalse(WsValidator.usesHyperVoice(mono))

        for (c in listOf(false, true)) {
            val shortest = WsDemoSong.shortest(c)
            val system: WonderSwanSystemBase = if (c) WscSystem else WsSystem
            assertEquals(emptyList<String>(), system.errors(shortest))
            assertEquals(1, shortest.ticksPerRow)
            assertEquals(1, shortest.patterns.single().length)
            assertEquals(Cell(57, 1), shortest.patterns.single().tracks[0][0])
            assertEquals(WsKind.TONE, shortest.ws.instrument(1)!!.kind)
            assertEquals(c, shortest.ws.color)
        }
    }
}
