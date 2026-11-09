package org.thortracker.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import org.thortracker.backend.CbmImage
import org.thortracker.backend.CbmLowering
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.ProjectCodec
import org.thortracker.model.ProjectFormatException
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.cbm.CbmChip
import org.thortracker.system.cbm.CbmDemoSong
import org.thortracker.system.cbm.CbmEnvelope
import org.thortracker.system.cbm.CbmInstrument
import org.thortracker.system.cbm.CbmKind
import org.thortracker.system.cbm.CbmPresets
import org.thortracker.system.cbm.CbmRules
import org.thortracker.system.cbm.CbmSample
import org.thortracker.system.cbm.CbmSamples
import org.thortracker.system.cbm.CbmSharedState
import org.thortracker.system.cbm.CbmSound
import org.thortracker.system.cbm.CbmStarterKit
import org.thortracker.system.cbm.CbmSystem
import org.thortracker.system.cbm.CbmVideo
import org.thortracker.system.cbm.TedSystem
import org.thortracker.system.cbm.VicSystem
import org.thortracker.system.cbm.cbm

/**
 * The VIC-20 and C16/Plus/4 systems' model: lane ranges per machine, the validator's mirror
 * of the loader's reasons (placement, effects per lane, header ranges, data capacity and the 64 KB offset reach),
 * warnings, the lowering decoded back per docs/AUDIO_ENGINE.md (header, tables, pattern bytes, envelope and sample
 * sharing), the shared-state walk (master volume owner, level, D/A mode, DAC timer), the player's pitch arithmetic,
 * project files and the demos. The songs here use their own sounds, so they do not depend on the preset lists.
 */
class CbmModelTest {
    private val square = CbmInstrument("sq")

    /** A sound whose volume envelope takes the master volume. */
    private val master = CbmInstrument("master", volume = CbmEnvelope(listOf(15, 10)))

    private fun base(chip: CbmChip, instruments: Map<Int, CbmInstrument>, samples: List<CbmSample> = emptyList(),
                     video: CbmVideo = CbmVideo.PAL): Song {
        val system = chip.system
        return system.blank().copy(sound = CbmSound(chip, List(CbmSystem.SLOTS) { instruments[it + 1] }, samples, video))
    }

    private fun with(song: Song, channel: Int, row: Int, cell: Cell, order: Int = 0): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun vic(vararg cells: Triple<Int, Int, Cell>, video: CbmVideo = CbmVideo.PAL): Song =
        cells.fold(base(CbmChip.VIC, mapOf(1 to square, 2 to master), video = video)) { s, (ch, row, cell) -> with(s, ch, row, cell) }

    private val kick = CbmSample("kick", listOf(4, 8, 0, 4))
    private val sampleSound = CbmInstrument("smp", CbmKind.SAMPLE, sample = 0)
    private val noise = CbmInstrument("nz", wave = CbmImage.WAVE_NOISE)

    /** The TED's master volume runs 0..8. */
    private val tedMaster = CbmInstrument("master", volume = CbmEnvelope(listOf(8, 5)))

    private fun ted(vararg cells: Triple<Int, Int, Cell>): Song =
        cells.fold(base(CbmChip.TED, mapOf(1 to square, 2 to tedMaster, 3 to sampleSound, 4 to noise), listOf(kick))) { s, (ch, row, cell) -> with(s, ch, row, cell) }

    private fun at(ch: Int, row: Int, cell: Cell) = Triple(ch, row, cell)

    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF
    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)
    private fun u32(image: ByteArray, at: Int) = u16(image, at) or (u16(image, at + 2) shl 16)
    private fun data(image: ByteArray, at: Int, n: Int) = List(n) { u8(image, CbmImage.OFF_DATA + at + it) }

    // ---- system shape ------------------------------------------------------------------------------------------



    @Test
    fun ticksPerSecondIsTheMachinesFrameRateTimesMultispeed() {
        fun rate(chip: CbmChip, video: CbmVideo, n: Int) =
            chip.system.ticksPerSecond(base(chip, emptyMap(), video = video).let { it.copy(sound = it.cbm.copy(multispeed = n)) })
        assertEquals(17_734_475.0 / 16 / 22152, rate(CbmChip.VIC, CbmVideo.PAL, 1), 1e-9)
        assertEquals(315_000_000.0 / 308 / 16965 * 2, rate(CbmChip.VIC, CbmVideo.NTSC, 2), 1e-9)
        assertEquals(886_723.75 / 17784 * 3, rate(CbmChip.TED, CbmVideo.PAL, 3), 1e-9)
        assertEquals(315_000_000.0 / 352 / 14934 * 4, rate(CbmChip.TED, CbmVideo.NTSC, 4), 1e-9)
        assertEquals(50.0363, VicSystem.tickHz, 1e-4)
        assertEquals(49.8608, TedSystem.tickHz, 1e-4)
    }

    // ---- lanes and pitch -----------------------------------------------------------------------------------------

    @Test
    fun laneRangesDependOnChipAndMachine() {
        val pal = base(CbmChip.VIC, emptyMap())
        val ntsc = base(CbmChip.VIC, emptyMap(), video = CbmVideo.NTSC)
        assertEquals(listOf(13..52, 25..64, 37..76, 0..127), (0..3).map { VicSystem.laneRange(pal, it) })
        assertEquals(listOf(11..48, 23..60, 35..72, 0..127), (0..3).map { VicSystem.laneRange(ntsc, it) })
        val ted = base(CbmChip.TED, emptyMap())
        assertEquals(listOf(33..108, 33..127, 24..108), (0..2).map { TedSystem.laneRange(ted, it) })
        assertTrue(CbmRules.laneRange(CbmChip.TED, CbmVideo.PAL, 3).isEmpty())
        for (v in 0..3) for (c in 0..3) {
            val chip = if (v < 2) CbmChip.VIC else CbmChip.TED
            val r = CbmRules.laneRange(chip, CbmVideo.entries[v % 2], c)
            if (!r.isEmpty()) assertEquals(CbmImage.LANE_MIN[v][c]..CbmImage.LANE_MAX[v][c], r)
        }
    }

    @Test
    fun notesOutsideTheLaneAreRefusedByMachine() {
        fun errors(song: Song) = song.system.errors(song)
        assertTrue(errors(vic(at(0, 0, Cell(13, 1)))).isEmpty())
        assertFalse(errors(vic(at(0, 0, Cell(12, 1)))).isEmpty())
        assertFalse(errors(vic(at(0, 0, Cell(53, 1)))).isEmpty())
        // NTSC: the bass reaches lower but not as high.
        assertTrue(errors(vic(at(0, 0, Cell(11, 1)), video = CbmVideo.NTSC)).isEmpty())
        assertFalse(errors(vic(at(0, 0, Cell(52, 1)), video = CbmVideo.NTSC)).isEmpty())
        assertFalse(errors(vic(at(2, 0, Cell(77, 1)))).isEmpty())
        assertTrue(errors(vic(at(2, 0, Cell(76, 1)))).isEmpty())
        // Noise rates 0..127, OFF anywhere.
        assertTrue(errors(vic(at(3, 0, Cell(0, 1)), at(3, 1, Cell(127, 1)), at(3, 2, Cell(Cell.CUT)))).isEmpty())
        // Out-of-lane notes are not structural: the song still saves (a machine switch may cause them).
        assertTrue(VicSystem.structuralErrors(vic(at(0, 0, Cell(12, 1)))).isEmpty())
        assertTrue(errors(ted(at(0, 0, Cell(108, 1)), at(1, 0, Cell(127, 4)), at(2, 0, Cell(24, 3)))).isEmpty())
        assertFalse(errors(ted(at(0, 0, Cell(109, 1)))).isEmpty())
        assertFalse(errors(ted(at(0, 0, Cell(32, 1)))).isEmpty())
        assertFalse(errors(ted(at(2, 0, Cell(23, 3)))).isEmpty())
        // The audition check mirrors it.
        val song = vic()
        assertNotNull(VicSystem.engine.auditionProblem(song, 0, 12, 1, 0))
        assertNull(VicSystem.engine.auditionProblem(song, 0, 13, 1, 0))
        assertNull(VicSystem.engine.auditionProblem(song, 3, 127, 1, 0))
        assertNotNull(VicSystem.engine.auditionProblem(song, 4, 57, 1, 0))
        assertNotNull(VicSystem.engine.auditionProblem(song, 2, 57, 3, 0))
        assertNotNull(VicSystem.engine.auditionProblem(song, 2, 57, 1, 128))
    }

    @Test
    fun periodsMatchTheSpecsA4() {
        assertEquals(39, CbmRules.period(CbmChip.VIC, CbmVideo.PAL, 2, 57 * 64))
        assertEquals(36, CbmRules.period(CbmChip.VIC, CbmVideo.NTSC, 2, 57 * 64))
        assertEquals(252, CbmRules.period(CbmChip.TED, CbmVideo.PAL, 0, 57 * 64))
        assertEquals(254, CbmRules.period(CbmChip.TED, CbmVideo.NTSC, 0, 57 * 64))
        // Bass and alto play the soprano's period of the same note two and one octaves up (their dividers are 4× and 2×).
        assertEquals(CbmRules.period(CbmChip.VIC, CbmVideo.PAL, 2, 69 * 64), CbmRules.period(CbmChip.VIC, CbmVideo.PAL, 1, 57 * 64))
        assertEquals(CbmRules.period(CbmChip.VIC, CbmVideo.PAL, 2, 81 * 64), CbmRules.period(CbmChip.VIC, CbmVideo.PAL, 0, 57 * 64))
        assertEquals(444.07, CbmRules.toneHz(CbmChip.VIC, CbmVideo.PAL, 2, 57), 0.01)
        assertEquals(439.84, CbmRules.toneHz(CbmChip.TED, CbmVideo.PAL, 0, 57), 0.01)
        // Sample steps: the root plays one frame per interrupt, an octave up two.
        assertEquals(65536L, CbmRules.sampleStep(48 * 64, 48))
        assertEquals(131072L, CbmRules.sampleStep(60 * 64, 48))
        assertEquals(32768L, CbmRules.sampleStep(36 * 64, 48))
        assertEquals(112, CbmRules.timerCycles(12))
        assertEquals(7917, CbmSamples.rateHz(CbmVideo.PAL, 12))
        assertEquals(13855, CbmSamples.rateHz(CbmVideo.PAL, 0))
    }

    // ---- validation ----------------------------------------------------------------------------------------------

    @Test
    fun placementRulesMirrorTheLoader() {
        fun errors(song: Song) = song.system.errors(song)
        // Noise-capable sounds only on TED voice 2; samples only on the DAC lane; tones never there.
        assertTrue(errors(ted(at(1, 0, Cell(60, 4)))).isEmpty())
        assertFalse(errors(ted(at(0, 0, Cell(60, 4)))).isEmpty())
        val envelopeNoise = base(CbmChip.TED, mapOf(1 to CbmInstrument("n", waves = CbmEnvelope(listOf(1, 2)))))
        assertFalse(errors(with(envelopeNoise, 0, 0, Cell(60, 1))).isEmpty())
        assertTrue(errors(with(envelopeNoise, 1, 0, Cell(60, 1))).isEmpty())
        assertFalse(errors(ted(at(0, 0, Cell(48, 3)))).isEmpty())
        assertFalse(errors(ted(at(2, 0, Cell(48, 1)))).isEmpty())
        assertTrue(errors(ted(at(2, 0, Cell(48, 3)))).isEmpty())
        // Every VIC tone plays on every lane (noise on the Noise lane).
        assertTrue(errors(vic(at(3, 0, Cell(60, 2)), at(1, 0, Cell(40, 2)))).isEmpty())
        // An empty slot is refused.
        assertFalse(errors(vic(at(2, 0, Cell(57, 9)))).isEmpty())
        // The VIC has no samples, sample sounds, DAC timer or noise wave.
        assertFalse(VicSystem.structuralErrors(vic().let { it.copy(sound = it.cbm.copy(samples = listOf(kick))) }).isEmpty())
        assertFalse(VicSystem.structuralErrors(vic().let { it.copy(sound = it.cbm.copy(dacTimer = 1)) }).isEmpty())
        assertFalse(VicSystem.structuralErrors(vic().let { it.copy(sound = it.cbm.withInstrument(5, sampleSound)) }).isEmpty())
        assertFalse(VicSystem.structuralErrors(vic().let { it.copy(sound = it.cbm.withInstrument(5, noise)) }).isEmpty())
        // A TED song cannot be checked as a VIC song.
        assertFalse(VicSystem.structuralErrors(ted()).isEmpty())
    }

    @Test
    fun effectChecksMirrorTheLoader() {
        fun ok(song: Song) = song.system.errors(song).isEmpty()
        fun fx(code: Int, param: Int, note: Int = Cell.EMPTY, slot: Int = 0) = Cell(note, slot, code, param)
        assertTrue(ok(vic(at(0, 0, fx(CbmRules.FX_VOLUME, 15)))))
        assertFalse(ok(vic(at(0, 0, fx(CbmRules.FX_VOLUME, 16)))))
        assertTrue(ok(ted(at(0, 0, fx(CbmRules.FX_VOLUME, 8)))))
        assertFalse(ok(ted(at(0, 0, fx(CbmRules.FX_VOLUME, 9)))))
        assertFalse(ok(vic(at(0, 0, fx(CbmRules.FX_TIMER, 1)))))
        assertFalse(ok(vic(at(0, 0, fx(CbmRules.FX_WAVE, 1)))))
        assertTrue(ok(ted(at(2, 0, fx(CbmRules.FX_TIMER, 0xFF)))))
        assertFalse(ok(ted(at(2, 0, fx(CbmRules.FX_WAVE, 1)))))
        assertTrue(ok(ted(at(0, 0, fx(CbmRules.FX_WAVE, 1)))))
        assertFalse(ok(ted(at(0, 0, fx(CbmRules.FX_WAVE, 2)))))
        assertTrue(ok(ted(at(1, 0, fx(CbmRules.FX_WAVE, 2)))))
        assertFalse(ok(ted(at(1, 0, fx(CbmRules.FX_WAVE, 3)))))
        assertFalse(ok(vic(at(0, 0, fx(CbmRules.FX_SPEED, 0)))))
        assertTrue(ok(vic(at(0, 0, fx(CbmRules.FX_JUMP, 0)))))
        assertFalse(ok(vic(at(0, 0, fx(CbmRules.FX_JUMP, 1)))))
        // C (12) and other codes the player does not know.
        assertFalse(ok(vic(at(0, 0, fx(12, 1)))))
        assertFalse(ok(ted(at(0, 0, fx(35, 1)))))
        // Three effect columns are more than the player reads.
        val three = Cell(57, 1, CbmRules.FX_FINE, 0x80).withEffect(1, CbmRules.FX_ARPEGGIO, 0x37).withEffect(2, CbmRules.FX_CUT, 3)
        assertFalse(VicSystem.structuralErrors(vic(at(2, 0, three))).isEmpty())
        // Pitch effects on the VIC noise lane load (the player ignores them): a warning, not an error.
        val ignored = vic(at(3, 0, Cell(60, 1, CbmRules.FX_SLIDE_UP, 4)))
        assertTrue(ok(ignored))
        assertEquals(1, VicSystem.warnings(ignored).size)
    }

    @Test
    fun headerRangesAndSamplesAreChecked() {
        fun structural(song: Song) = song.system.structuralErrors(song)
        val t = ted()
        assertTrue(structural(t).isEmpty())
        assertFalse(structural(t.copy(sound = t.cbm.copy(multispeed = 5))).isEmpty())
        assertFalse(structural(t.copy(sound = t.cbm.copy(volume = 9))).isEmpty())
        assertTrue(structural(t.copy(sound = t.cbm.copy(dacTimer = 255))).isEmpty())
        assertFalse(structural(t.copy(sound = t.cbm.copy(dacTimer = 256))).isEmpty())
        assertFalse(structural(t.copy(sound = t.cbm.copy(samples = listOf(CbmSample("x", listOf(9)))))).isEmpty())
        assertFalse(structural(t.copy(sound = t.cbm.copy(samples = listOf(CbmSample("x", emptyList()))))).isEmpty())
        assertFalse(structural(t.copy(ticksPerRow = 0)).isEmpty())
        val v = vic()
        assertTrue(structural(v.copy(sound = v.cbm.copy(volume = 15))).isEmpty())
        assertFalse(structural(v.copy(sound = v.cbm.copy(volume = 16))).isEmpty())
        // Instruments: envelope values per chip, loops and releases, root, loop start inside the sample.
        fun withSlot(song: Song, ins: CbmInstrument) = song.copy(sound = song.cbm.withInstrument(9, ins))
        assertTrue(structural(withSlot(v, CbmInstrument(volume = CbmEnvelope(listOf(15))))).isEmpty())
        assertFalse(structural(withSlot(t, CbmInstrument(volume = CbmEnvelope(listOf(9))))).isEmpty())
        assertFalse(structural(withSlot(v, CbmInstrument(waves = CbmEnvelope(listOf(2))))).isEmpty())
        assertTrue(structural(withSlot(t, CbmInstrument(waves = CbmEnvelope(listOf(2))))).isEmpty())
        assertFalse(structural(withSlot(v, CbmInstrument(arpeggio = CbmEnvelope(listOf(1, 2), loop = 2)))).isEmpty())
        assertFalse(structural(withSlot(v, CbmInstrument(arpeggio = CbmEnvelope(listOf(1, 2), release = 0)))).isEmpty())
        assertFalse(structural(withSlot(v, CbmInstrument(arpeggio = CbmEnvelope(listOf(1, 2, 3), loop = 1, release = 1)))).isEmpty())
        assertFalse(structural(withSlot(v, CbmInstrument(pitch = CbmEnvelope(List(256) { 0 })))).isEmpty())
        assertFalse(structural(withSlot(t, sampleSound.copy(root = 23))).isEmpty())
        assertFalse(structural(withSlot(t, sampleSound.copy(root = 109))).isEmpty())
        assertFalse(t.system.errors(withSlot(t, sampleSound.copy(loop = true, loopStart = 4))).isEmpty())
        assertTrue(t.system.errors(withSlot(t, sampleSound.copy(loop = true, loopStart = 3))).isEmpty())
        assertFalse(t.system.errors(withSlot(t, sampleSound.copy(sample = 1))).isEmpty())
    }

    // ---- warnings ------------------------------------------------------------------------------------------------

    @Test
    fun warningsCoverOwnerConflictsDacSilencingAndOutOfTuneSquares() {
        fun warnings(song: Song) = song.system.warnings(song)
        // Two lanes' volume envelopes overlapping: the second takes the master volume from the first.
        assertEquals(1, warnings(vic(at(0, 0, Cell(30, 2)), at(1, 0, Cell(40, 2)))).size)
        // After the first one's OFF, no conflict.
        assertEquals(0, warnings(vic(at(0, 0, Cell(30, 2)), at(0, 2, Cell(Cell.CUT)), at(1, 4, Cell(40, 2)))).size)
        // The same lane taking it again is no conflict.
        assertEquals(0, warnings(vic(at(0, 0, Cell(30, 2)), at(0, 4, Cell(32, 2)))).size)
        // A DAC note while a voice sounds silences it (D/A mode takes both voices).
        assertEquals(1, warnings(ted(at(0, 0, Cell(57, 1)), at(2, 4, Cell(48, 3)))).size)
        assertEquals(0, warnings(ted(at(0, 0, Cell(57, 1)), at(0, 2, Cell(Cell.CUT)), at(2, 4, Cell(48, 3)))).size)
        // Squares above C-9 on voice 2 are more than 50 cents out; noise is fine there.
        assertEquals(1, warnings(ted(at(1, 0, Cell(109, 1)))).size)
        assertEquals(0, warnings(ted(at(1, 0, Cell(127, 4)))).size)
        assertEquals(0, warnings(ted(at(1, 0, Cell(109, 1, CbmRules.FX_WAVE, 2)))).size)
        // Txx without samples.
        assertEquals(1, warnings(ted(at(0, 0, Cell(Cell.EMPTY, 0, CbmRules.FX_TIMER, 5)))).size)
    }

    // ---- lowering ------------------------------------------------------------------------------------------------

    /** A TED song exercising the layout (envelope sharing across kinds and slots, sample sharing, a gap slot). */
    private fun layoutSong(): Song {
        val a = CbmInstrument("a", volume = CbmEnvelope(listOf(8, 4), loop = 0))
        val b = CbmInstrument("b", wave = 2, volume = CbmEnvelope(listOf(8, 4), loop = 0), arpeggio = CbmEnvelope(listOf(0, 12), loop = 0),
            pitch = CbmEnvelope(listOf(-1), loop = 0), pitchRelative = true)
        val s = CbmInstrument("s", CbmKind.SAMPLE, sample = 1, root = 48, loop = true, loopStart = 2, arpeggio = CbmEnvelope(listOf(8, 4)),
            volume = CbmEnvelope(listOf(1, 2, 3)), waves = CbmEnvelope(listOf(1)))
        val e = CbmInstrument("e", waves = CbmEnvelope(listOf(1, 2, 0)))
        val samples = listOf(CbmSample("s0", listOf(4, 5, 6, 7)), CbmSample("s1", listOf(4, 5, 6, 7)), CbmSample("s2", listOf(0, 8)))
        val song = base(CbmChip.TED, mapOf(1 to a, 2 to b, 3 to s, 5 to e), samples).let {
            it.copy(ticksPerRow = 6, orders = listOf(0, 0), loopOrder = 1, sound = it.cbm.copy(multispeed = 2, volume = 7, dacTimer = 20))
        }
        return listOf(at(0, 0, Cell(57, 1, CbmRules.FX_VOLUME, 8)), at(1, 0, Cell(60, 2)), at(1, 4, Cell(Cell.CUT)))
            .fold(song) { acc, (ch, row, cell) -> with(acc, ch, row, cell) }
    }

    @Test
    fun loweringWritesTheHeaderAndTables() {
        val song = layoutSong()
        assertTrue(TedSystem.errors(song).isEmpty())
        val image = CbmLowering.lower(song)
        assertEquals(CbmImage.IMAGE_SIZE, image.size)
        assertEquals("TTCB", String(image.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(listOf(1, 1, 0, 2, 6, 5), (4..9).map { u8(image, it) })
        assertEquals(2, u16(image, CbmImage.H_ORDERS))
        assertEquals(1, u16(image, CbmImage.H_LOOP))
        assertEquals(3, u8(image, CbmImage.H_SAMPLES))
        assertEquals(7, u8(image, CbmImage.H_VOLUME))
        assertEquals(20, u8(image, CbmImage.H_TIMER))
        assertEquals(28, u32(image, CbmImage.H_DATA_SIZE))
        for (i in listOf(17, 18, 19) + (24 until 64)) assertEquals("reserved byte $i", 0, u8(image, i))
        assertEquals(listOf(64, 64, 0), (0..2).map { u8(image, CbmImage.OFF_ROWS + it) })
        // Order table: four entries per order, the TED's fourth 0, identical tracks shared.
        assertEquals(listOf(0, 6, 13, 0, 0, 6, 13, 0, 0), (0..8).map { u16(image, CbmImage.OFF_ORDERS + 2 * it) })
        // Pattern bytes.
        assertEquals(listOf(0x07, 57, 1, CbmRules.FX_VOLUME, 8, 0xBE), data(image, 0, 6))
        assertEquals(listOf(0x03, 60, 2, 0x82, 0x01, 0xFE, 0xBA), data(image, 6, 7))
        assertEquals(listOf(0xBF), data(image, 13, 1))
        // Envelopes after the patterns, byte-identical ones once: [8, 4] at 14, [0, 12] at 16, [-1] at 18, [1, 2, 0] at 19.
        assertEquals(listOf(8, 4, 0, 12, 0xFF, 1, 2, 0), data(image, 14, 8))
        // Samples after the envelopes; identical frames once.
        assertEquals(listOf(4, 5, 6, 7, 0, 8), data(image, 22, 6))
        assertEquals(listOf(22, 4, 22, 4, 26, 2, 0, 0), (0 until 8).map { u32(image, CbmImage.OFF_SAMPLES + 4 * it) })
    }

    @Test
    fun loweringWritesInstrumentRecords() {
        val image = CbmLowering.lower(layoutSong())
        fun record(slot: Int) = List(32) { u8(image, CbmImage.OFF_INSTRUMENTS + 32 * (slot - 1) + it) }
        val none = listOf(0, 0, 0, 0xFF, 0xFF, 0)
        // Slot 1: tone, square, volume [8, 4] looping from 0.
        assertEquals(listOf(0x80, 1, 0, 0, 0, 0, 0, 0) + listOf(14, 0, 2, 0, 0xFF, 0) + none + none + none, record(1))
        // Slot 2: relative pitch flag, noise wave, three envelopes.
        assertEquals(listOf(0x84, 2, 0, 0, 0, 0, 0, 0) + listOf(14, 0, 2, 0, 0xFF, 0) + listOf(16, 0, 2, 0, 0xFF, 0) +
            listOf(18, 0, 1, 0, 0xFF, 0) + none, record(2))
        // Slot 3: a sample sound: no volume or wave envelope lowered, its arpeggio shares the [8, 4] bytes.
        assertEquals(listOf(0x81, 0, 1, 48, 1, 0, 2, 0) + none + listOf(14, 0, 2, 0xFF, 0xFF, 0) + none + none, record(3))
        assertEquals(List(32) { 0 }, record(4))
        assertEquals(listOf(0x80, 1, 0, 0, 0, 0, 0, 0) + none + none + none + listOf(19, 0, 3, 0xFF, 0xFF, 0), record(5))
        assertEquals(List(32) { 0 }, record(6))
    }

    @Test
    fun vicImagesHaveFourLanesAndNoSamples() {
        val song = vic(at(3, 0, Cell(100, 1)), at(2, 1, Cell(57, 2)))
        val image = CbmLowering.lower(song)
        assertEquals(CbmImage.CHIP_VIC, u8(image, CbmImage.H_CHIP))
        assertEquals(0, u8(image, CbmImage.H_SAMPLES))
        assertEquals(0, u8(image, CbmImage.H_TIMER))
        assertEquals(15, u8(image, CbmImage.H_VOLUME))
        assertEquals(2, u8(image, CbmImage.H_INSTRUMENTS))
        // Bass and alto empty (shared), soprano, noise.
        val offsets = (0..3).map { u16(image, CbmImage.OFF_ORDERS + 2 * it) }
        assertEquals(offsets[0], offsets[1])
        assertEquals(listOf(0xBF), data(image, offsets[0], 1))
        assertEquals(listOf(0x80, 0x03, 57, 2, 0xBD), data(image, offsets[2], 5))
        assertEquals(listOf(0x03, 100, 1, 0xBE), data(image, offsets[3], 4))
        // NTSC is header byte 6.
        assertEquals(1, u8(CbmLowering.lower(song.copy(sound = song.cbm.copy(video = CbmVideo.NTSC))), CbmImage.H_VIDEO))
    }

    @Test
    fun parkedOrdersAndJumpsAreArranged() {
        val song = vic(at(0, 0, Cell(Cell.EMPTY, 0, CbmRules.FX_JUMP, 2))).let {
            it.copy(orders = listOf(0, 0, 0), parkedOrders = listOf(1))
        }
        val arranged = VicSystem.arranged(song)
        assertEquals(2, arranged.orders.size)
        assertEquals(1, VicSystem.jumpTarget(arranged.patterns[0].tracks[0][0]))
        assertEquals(2, u16(CbmLowering.lower(song), CbmImage.H_ORDERS))
        val loop = VicSystem.patternLoop(song, 2)
        assertEquals(listOf(0), loop.orders)
        assertEquals(0, VicSystem.jumpTarget(loop.patterns[0].tracks[0][0]))
    }

    @Test
    fun patternsAndEnvelopesMustStayInsideTheirOffsetsReach() {
        // Each pattern: lanes 1-2 (identical, stored once) 64 × 7 bytes, the DAC lane 64 × 5 bytes = 768 distinct bytes.
        fun song(patterns: Int): Song {
            val ted = ted()
            val list = List(patterns) { p ->
                val voice = List(Songs.ROWS) { r -> Cell(57, 1, CbmRules.FX_ARPEGGIO, p + 1).withEffect(1, CbmRules.FX_FINE, r) }
                val dac = List(Songs.ROWS) { r -> Cell(Cell.EMPTY, 0, CbmRules.FX_ARPEGGIO, p + 1).withEffect(1, CbmRules.FX_FINE, r) }
                Pattern("p$p", listOf(voice, voice, dac))
            }
            return ted.copy(patterns = list, orders = list.indices.toList())
        }
        val fits = song(80)
        // 80 patterns, then slot 02's two envelope bytes and the four sample frames.
        assertEquals(80 * 768 + 2 + 4, CbmLowering.dataSize(fits))
        assertNull(CbmLowering.sizeProblem(fits))
        assertTrue(TedSystem.errors(fits).isEmpty())
        // Pattern 86 would start at 86 × 768 = 66048, past the 65535 a u16 offset reaches.
        val over = song(100)
        assertTrue(CbmLowering.dataSize(over) <= CbmImage.DATA_CAPACITY)
        assertNotNull(CbmLowering.sizeProblem(over))
        assertFalse(TedSystem.errors(over).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { CbmLowering.lower(over) }
    }

    @Test
    fun samplesMustFitTheDataRegion() {
        val big = List(2) { i -> CbmSample("big$i", List(CbmSample.MAX_FRAMES) { (it + i) % 9 }) }
        val song = ted().let { it.copy(sound = it.cbm.copy(samples = big)) }
        assertTrue(TedSystem.structuralErrors(song).isEmpty())
        assertNotNull(CbmLowering.sizeProblem(song))
        assertFalse(TedSystem.errors(song).isEmpty())
        // The same frames twice are stored once and fit.
        val twice = ted().let { it.copy(sound = it.cbm.copy(samples = listOf(big[0], big[0].copy(name = "again")))) }
        assertNull(CbmLowering.sizeProblem(twice))
    }

    // ---- shared state ----------------------------------------------------------------------------------------------

    @Test
    fun theMasterVolumeOwnerFollowsKeyOnsAndStops() {
        val song = vic(
            at(0, 0, Cell(30, 1)), at(1, 0, Cell(40, 2)),
            at(1, 4, Cell(41, 1)),
            at(2, 8, Cell(50, 2)), at(2, 12, Cell(Cell.CUT)),
        )
        assertEquals(1, CbmSharedState.at(song, 0, 0).owner)
        assertEquals(2, CbmSharedState.at(song, 0, 0).ownerSlot)
        assertEquals(-1, CbmSharedState.at(song, 0, 4).owner)
        assertEquals(2, CbmSharedState.at(song, 0, 8).owner)
        assertEquals(2, CbmSharedState.at(song, 0, 11).owner)
        assertEquals(-1, CbmSharedState.at(song, 0, 12).owner)
        // A release point keeps the released note owning.
        val released = base(CbmChip.VIC, mapOf(1 to CbmInstrument("r", volume = CbmEnvelope(listOf(15, 8, 0), release = 1))))
        val r = with(with(released, 0, 0, Cell(30, 1)), 0, 2, Cell(Cell.CUT))
        assertEquals(0, CbmSharedState.at(r, 0, 3).owner)
        // The lane tag marks the owner.
        val state = VicSystem.sharedState(song, 0, 8)
        assertEquals(listOf(false, false, true, false), state.tags.map { it != null })
    }

    @Test
    fun theMasterVolumeFollowsVAndASlides() {
        val song = vic(
            at(3, 16, Cell(Cell.EMPTY, 0, CbmRules.FX_VOLUME, 5)),
            at(3, 20, Cell(Cell.EMPTY, 0, CbmRules.FX_VOLUME_SLIDE, 0x03)),
            at(3, 30, Cell(Cell.EMPTY, 0, CbmRules.FX_VOLUME_SLIDE, 0x10)),
        )
        assertEquals(15, CbmSharedState.at(song, 0, 0).level)
        assertEquals(5, CbmSharedState.at(song, 0, 16).level)
        // −3/8 per tick, 6 ticks per row, applied after each tick's control step.
        assertEquals(5, CbmSharedState.at(song, 0, 20).level)
        assertEquals(2, CbmSharedState.at(song, 0, 21).level)
        assertEquals(0, CbmSharedState.at(song, 0, 22).level)
        // +1/8 per tick from row 30: 6 eighths per row.
        assertEquals(0, CbmSharedState.at(song, 0, 30).level)
        assertEquals(3, CbmSharedState.at(song, 0, 34).level)
        // A Fxx speed change counts its own row.
        val fast = with(song, 0, 20, Cell(Cell.EMPTY, 0, CbmRules.FX_SPEED, 2))
        assertEquals(4, CbmSharedState.at(fast, 0, 21).level)
    }

    @Test
    fun dacModeAndTimerFollowTheDacLane() {
        val loop = sampleSound.copy(name = "loop", loop = true)
        val song = base(CbmChip.TED, mapOf(1 to sampleSound, 2 to loop), listOf(CbmSample("long", List(100) { 4 }))).let { s ->
            listOf(at(2, 0, Cell(48, 1)), at(2, 2, Cell(Cell.EMPTY, 0, CbmRules.FX_TIMER, 5)), at(2, 4, Cell(48, 2)), at(2, 8, Cell(Cell.CUT)))
                .fold(s) { acc, (ch, row, cell) -> with(acc, ch, row, cell) }
        }
        // 100 interrupts of 112 clocks: the last frame is followed by the interrupt that stops the one-shot.
        assertTrue(CbmSharedState.at(song, 0, 0).dacActive)
        assertFalse(CbmSharedState.at(song, 0, 1).dacActive)
        assertEquals(12, CbmSharedState.at(song, 0, 1).timer)
        assertEquals(5, CbmSharedState.at(song, 0, 2).timer)
        assertTrue(CbmSharedState.at(song, 0, 4).dacActive)
        assertTrue(CbmSharedState.at(song, 0, 7).dacActive)
        assertFalse(CbmSharedState.at(song, 0, 8).dacActive)
        val tags = TedSystem.sharedState(song, 0, 5).tags
        assertEquals(listOf(true, true, true), tags.map { it != null })
        assertEquals(listOf(false, false, false), TedSystem.sharedState(song, 0, 8).tags.map { it != null })
        assertEquals(2, TedSystem.sharedState(song, 0, 0).lines.size)
        assertEquals(1, VicSystem.sharedState(vic(), 0, 0).lines.size)
    }

    @Test
    fun sharedOwnerUsesThePlayingSlotNotTheSelectedSlot() {
        val release = CbmInstrument("release", volume = CbmEnvelope(listOf(15, 8, 0), release = 1))
        val song = base(CbmChip.VIC, mapOf(1 to release, 2 to square)).copy(ticksPerRow = 1)
        val selected = with(with(song, 0, 0, Cell(30, 1)), 0, 1, Cell(instrument = 2))
        assertEquals(1, CbmSharedState.at(selected, 0, 1).ownerSlot)
        val off = with(selected, 0, 2, Cell(Cell.CUT))
        // Native song playback holds the terminal zero; audition completion is a different rule.
        assertEquals(0, CbmSharedState.at(off, 0, 20).owner)
        assertEquals("VOL", VicSystem.sharedState(off, 0, 20).tags[0])
        val glide = with(off, 0, 21, Cell(36, 2, CbmRules.FX_PORTAMENTO, 4))
        assertEquals(1, CbmSharedState.at(glide, 0, 21).ownerSlot)
        val retrigger = with(glide, 0, 22, Cell(36, 2, CbmRules.FX_SLIDE_UP, 4))
        assertEquals(-1, CbmSharedState.at(retrigger, 0, 22).owner)
        // The inverse: selecting a release sound must not turn OFF of a plain playing sound into a release.
        val plain = base(CbmChip.VIC, mapOf(1 to master, 2 to release))
        val stopped = with(with(plain, 0, 0, Cell(30, 1)), 0, 1, Cell(Cell.CUT, 2))
        assertEquals(-1, CbmSharedState.at(stopped, 0, 1).owner)
    }

    private fun dacSong(frames: Int, timer: Int = 255, multispeed: Int = 4,
                        ins: CbmInstrument = sampleSound): Song =
        base(CbmChip.TED, mapOf(1 to ins), listOf(CbmSample("boundary", List(frames) { 4 }))).let {
            with(it.copy(ticksPerRow = 1, sound = it.cbm.copy(dacTimer = timer, multispeed = multispeed)), 2, 0, Cell(48, 1))
        }

    @Test
    fun dacEndUsesTheStopInterruptAndPreservesMachineTimerPhase() {
        // PAL 17784 clocks: IRQ 158 writes the last frame, IRQ 159 at 17808 stops it.
        val last = dacSong(159, timer = 12, multispeed = 1)
        assertTrue(CbmSharedState.at(last, 0, 1).dacActive)
        assertFalse(CbmSharedState.at(last, 0, 2).dacActive)
        // At 3x, tick 1 starts at 5928 = 57 * 104. Its player update precedes the coincident stop IRQ.
        val exact = dacSong(57, timer = 10, multispeed = 3)
        assertTrue(CbmSharedState.at(exact, 0, 1).dacActive)
        assertFalse(CbmSharedState.at(exact, 0, 2).dacActive)
        // T00 on the first row does not replace the already pending IRQ at 1084: only 53 IRQs before 4446.
        val first = with(dacSong(54), 2, 0, Cell(48, 1, CbmRules.FX_TIMER, 0))
        assertTrue(CbmSharedState.at(first, 0, 1).dacActive)
        assertFalse(CbmSharedState.at(first, 0, 2).dacActive)
        // A late key-on inherits the continuously running timer: 40 IRQs at 4480..8848, not 4558..8926.
        val late = with(with(dacSong(40, timer = 12), 2, 0, Cell()), 2, 1, Cell(48, 1))
        assertFalse(CbmSharedState.at(late, 0, 2).dacActive)
        assertEquals(listOf(null, null, null), TedSystem.sharedState(late, 0, 2).tags)
        assertEquals(1, CbmSharedState.oneShotTicks(last.cbm, 1, 48, 48))
    }

    @Test
    fun dacProgressFollowsTimerChangesAfterKeyOn() {
        val faster = with(dacSong(100), 0, 1, Cell(effect = CbmRules.FX_TIMER, param = 0))
        assertTrue(CbmSharedState.at(faster, 0, 2).dacActive)
        assertFalse(CbmSharedState.at(faster, 0, 3).dacActive)
        val slower = with(dacSong(100, timer = 0), 0, 1, Cell(effect = CbmRules.FX_TIMER, param = 255))
        assertTrue(CbmSharedState.at(slower, 0, 2).dacActive)
        assertEquals(listOf("D/A", "D/A", "DAC"), TedSystem.sharedState(slower, 0, 2).tags)
    }

    @Test
    fun dacProgressUsesPerTickPitchEffectsAndEnvelopes() {
        // At TFF, 4x PAL each of these first six ticks contains four interrupts.
        data class Case(val code: Int, val param: Int, val frames: Int, val row: Int, val active: Boolean)
        val cases = listOf(
            Case(CbmRules.FX_ARPEGGIO, 0xC0, 20, 4, false),
            Case(CbmRules.FX_FINE, 0xFF, 17, 4, false),
            Case(CbmRules.FX_SLIDE_UP, 255, 18, 4, false),
            Case(CbmRules.FX_SLIDE_DOWN, 255, 19, 5, true),
            Case(CbmRules.FX_VIBRATO, 0x4F, 25, 6, false),
        )
        for (c in cases) {
            val song = with(dacSong(c.frames), 2, 1, Cell(effect = c.code, param = c.param))
            assertEquals("effect ${c.code}", c.active, CbmSharedState.at(song, 0, c.row).dacActive)
        }
        for ((ins, frames, row) in listOf(
            Triple(sampleSound.copy(arpeggio = CbmEnvelope(listOf(0, 12))), 11, 2),
            Triple(sampleSound.copy(pitch = CbmEnvelope(listOf(0, 127))), 17, 4),
            Triple(sampleSound.copy(pitch = CbmEnvelope(listOf(64)), pitchRelative = true), 18, 4),
        )) assertFalse(CbmSharedState.at(dacSong(frames, ins = ins), 0, row).dacActive)
    }

    @Test
    fun dacPortamentoKeepsTheLatchedSampleAndDoesNotRestartItsPosition() {
        val first = dacSong(18).let {
            it.copy(sound = it.cbm.withInstrument(2, sampleSound.copy(root = 108, loop = true)))
        }
        val glide = with(first, 2, 1, Cell(60, 2, CbmRules.FX_PORTAMENTO, 255))
        assertTrue(CbmSharedState.at(glide, 0, 3).dacActive)
        assertFalse(CbmSharedState.at(glide, 0, 4).dacActive)
        // A finished sample is no longer sounding, so sticky portamento keys on the selected looping slot.
        val restart = with(glide, 2, 5, Cell(48))
        assertTrue(CbmSharedState.at(restart, 0, 20).dacActive)
        // An inactive tone's retained release envelope is not lowered for a sample and cannot keep DAC alive.
        val retained = sampleSound.copy(loop = true, volume = CbmEnvelope(listOf(8, 0), release = 1))
        val stopped = with(dacSong(18, ins = retained), 2, 1, Cell(Cell.CUT))
        assertFalse(CbmSharedState.at(stopped, 0, 1).dacActive)
    }

    // ---- one-shot rails ----------------------------------------------------------------------------------------------

    @Test
    fun oneShotDacNotesEndByThemselves() {
        // 1584 frames at the root: 1584 interrupts × 112 clocks = 177408 clocks; 17784 per tick: 10 ticks = 2 rows of 6.
        val song = base(CbmChip.TED, mapOf(1 to sampleSound), listOf(CbmSample("s", List(1584) { 4 })))
        val one = with(song, 2, 0, Cell(48, 1))
        assertEquals(2, TedSystem.soundingRows(one, 0, 2, 0))
        assertNull(TedSystem.soundingRows(one, 0, 0, 0))
        val looping = one.copy(sound = one.cbm.withInstrument(1, sampleSound.copy(loop = true)))
        assertNull(TedSystem.soundingRows(looping, 0, 2, 0))
    }

    // ---- project files -------------------------------------------------------------------------------------------

    @Test
    fun projectFilesRoundTripBothChips() {
        val song = layoutSong().let { it.copy(sound = it.cbm.copy(video = CbmVideo.NTSC)) }
        val text = ProjectCodec.encode(song)
        val back = ProjectCodec.decode(text)
        assertEquals(song, back)
        assertEquals(text, ProjectCodec.encode(back))
        for (s in listOf(VicSystem.blank(), TedSystem.blank(), VicSystem.demo(), TedSystem.demo(),
            CbmDemoSong.shortest(CbmChip.VIC), CbmDemoSong.shortest(CbmChip.TED))) {
            assertEquals(s, ProjectCodec.decode(ProjectCodec.encode(s)))
        }
        // A TED sound under the VIC's id is refused.
        assertThrows(ProjectFormatException::class.java) {
            ProjectCodec.decode(text.replace("\"c16-ted\"", "\"vic20-vic\""))
        }
        // No older project versions or backend data exist for these systems.
        assertThrows(IllegalArgumentException::class.java) { VicSystem.upgradeProject(JsonObject(emptyMap()), 8) }
    }

    // ---- demos, presets, kit ------------------------------------------------------------------------------------------

    @Test
    fun demosAndShortestSongsAreValid() {
        for (system in listOf(VicSystem, TedSystem)) {
            val demo = system.demo()
            assertEquals(emptyList<String>(), system.errors(demo))
            assertEquals(emptyList<String>(), system.warnings(demo))
            CbmLowering.lower(demo)
            val blank = system.blank()
            assertEquals(emptyList<String>(), system.errors(blank))
        }
        for (chip in CbmChip.entries) {
            val s = CbmDemoSong.shortest(chip)
            assertEquals(emptyList<String>(), chip.system.errors(s))
        }
    }

    @Test
    fun presetsAndTheKitAreValidForTheirChip() {
        for (chip in CbmChip.entries) for (p in CbmPresets.forChip(chip)) {
            assertNull("${chip.name} preset ${p.name}", p.instrument.problem(chip))
        }
        val kit = CbmStarterKit.samples()
        for (sample in kit) assertNull(sample.problem())
    }
}
