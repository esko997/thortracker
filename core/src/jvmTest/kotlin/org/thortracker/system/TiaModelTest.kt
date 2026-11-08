package org.thortracker.system

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.TiaLowering
import org.thortracker.backend.TiaPlayerImage
import org.thortracker.editor.ViewKind
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.ProjectCodec
import org.thortracker.model.ProjectFormatException
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.tia.TiaDemoSong
import org.thortracker.system.tia.TiaEnvelope
import org.thortracker.system.tia.TiaInstrument
import org.thortracker.system.tia.TiaNtscSystem
import org.thortracker.system.tia.TiaPalSystem
import org.thortracker.system.tia.TiaPresets
import org.thortracker.system.tia.TiaRegion
import org.thortracker.system.tia.TiaRules
import org.thortracker.system.tia.TiaSharedState
import org.thortracker.system.tia.TiaSound
import org.thortracker.system.tia.TiaSystem
import org.thortracker.system.tia.tia

/**
 * The Atari 2600 systems' model: registration as one family of two regions, project files, the validator's mirror of
 * the loader's reasons and its warnings, the honest AUDF quantization and spans, the lowering's header, records and
 * data layout (patterns, then deduplicated envelopes; slots never merged), the key-on registers the Pattern view shows,
 * fine pitch, arrangement and the demos. The songs here use their own sounds (not the presets).
 */
class TiaModelTest {
    /** Slot 01: a div 6 square (AUDC C), no envelopes. */
    private val square = TiaInstrument("square", 0xC)

    /** Slot 02: raw white noise with a volume envelope. */
    private val hat = TiaInstrument("hat", 0x8, raw = true, volume = TiaEnvelope(listOf(9, 5, 0)))

    /** Slot 03: a div 31 bass whose waveform envelope starts on AUDC 1. */
    private val seq = TiaInstrument("seq", 0x6, waveforms = TiaEnvelope(listOf(0x1, 0x6), loop = 1))

    /** Slot 05 (after an empty 04): a constant level (AUDC 0) clicking through its volume envelope. */
    private val click = TiaInstrument("click", 0x0, volume = TiaEnvelope(listOf(15, 0)))

    private fun base(system: TiaSystem = TiaNtscSystem): Song = system.blank().copy(
        sound = TiaSound(system.region, List(TiaSystem.SLOTS) { when (it) { 0 -> square; 1 -> hat; 2 -> seq; 4 -> click; else -> null } }),
    )

    private fun with(song: Song, order: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun blankWith(channel: Int, row: Int, cell: Cell, system: TiaSystem = TiaNtscSystem) = with(base(system), 0, channel, row, cell)

    private fun withSound(f: (TiaSound) -> TiaSound) = base().let { it.copy(sound = f(it.tia)) }

    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF
    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)
    private fun u32(image: ByteArray, at: Int) = u16(image, at) or (u16(image, at + 2) shl 16)

    private fun record(image: ByteArray, slot: Int) =
        image.copyOfRange(TiaPlayerImage.OFF_INSTRUMENTS + TiaPlayerImage.INSTRUMENT_SIZE * (slot - 1),
            TiaPlayerImage.OFF_INSTRUMENTS + TiaPlayerImage.INSTRUMENT_SIZE * slot)

    // ---- system shape ------------------------------------------------------------------------------------------

    @Test
    fun bothRegionsAreRegisteredAsOneFamilyWithTwoChannels() {
        assertEquals(TiaNtscSystem, SoundSystems.of("atari2600-tia-ntsc"))
        assertEquals(TiaPalSystem, SoundSystems.of("atari2600-tia-pal"))
        assertEquals("Atari 2600 NTSC (TIA)", TiaNtscSystem.name)
        assertEquals("Atari 2600 PAL (TIA)", TiaPalSystem.name)
        for (system in listOf(TiaNtscSystem, TiaPalSystem)) {
            assertEquals("Atari 2600", system.family)
            assertEquals(listOf("TIA 1", "TIA 2"), system.channels.map { it.name })
            assertTrue(system.channels.all { it.tonal && it.kind == ChannelKind.VOICE && it.bank == 0 })
            assertEquals(listOf(BankSpec("Sounds", 64)), system.banks)
            assertEquals(0..119, system.noteRange)
            assertEquals("C-0", system.noteName(0))
            assertEquals("A-4", system.noteName(57))
            assertEquals("B-9", system.noteName(119))
            assertEquals(2, system.effectColumns(0))
            assertEquals(2, system.effectColumns(1))
            assertEquals(TiaRules.EFFECTS, system.effectsFor(1))
            assertFalse(ViewKind.WAVE in system.views)
            assertTrue(ViewKind.INSTRUMENT in system.views)
            assertEquals(emptyList<String>(), system.exports.map { it.extension })
            assertEquals(system.id, system.engine.id)
            assertEquals(65536, system.engine.imageSize)
            assertEquals(7200, system.engine.seekTicks)
            assertTrue(system.engine.driver.contains("Stella 7.0"))
            assertEquals(57, system.engine.note(base(system), 0, 57, 1))
            assertEquals(-40, system.engine.fine(0, 57, -40))
            assertEquals(system, system.blank().sound.system)
            assertEquals(system.tickHz, system.ticksPerSecond(system.blank()), 0.0)
        }
        assertEquals(39375000.0 / 11 / (262 * 228), TiaNtscSystem.tickHz, 1e-9)
        assertEquals(3546894.0 / (312 * 228), TiaPalSystem.tickHz, 1e-9)
        assertEquals(TiaRegion.PAL, TiaPalSystem.blank().tia.region)
        // The effect letters the loader knows, and only those.
        assertEquals("0 1 2 3 4 A B C D F G P S W", TiaRules.EFFECTS.joinToString(" ") { it.char.toString() })
    }

    @Test
    fun blankSongsAreValidWithHonestPresets() {
        for (system in listOf(TiaNtscSystem, TiaPalSystem)) {
            val blank = system.blank()
            assertEquals(emptyList<String>(), system.errors(blank))
            assertEquals(emptyList<String>(), system.warnings(blank))
            assertEquals(TiaSystem.SLOTS, blank.tia.instruments.size)
            assertEquals(TiaPresets.ALL.size, blank.tia.instruments.count { it != null })
        }
        assertTrue(TiaPresets.ALL.size >= 16)
        assertEquals(TiaPresets.ALL.size, TiaPresets.ALL.map { it.name }.toSet().size)
        TiaPresets.ALL.forEach { p ->
            assertNull(p.name, p.instrument.problem())
            assertEquals(p.category == TiaPresets.Category.RAW, p.instrument.raw)
        }
        assertEquals("square", TiaNtscSystem.instrumentName(base(), 0, 1))
        assertEquals(TiaSystem.EMPTY_NAME, TiaNtscSystem.instrumentName(base(), 0, 4))
        assertNull(TiaNtscSystem.instrumentName(base(), 1, 1))
        assertEquals("Empty: choose a preset", TiaNtscSystem.instrumentSummary(base(), 0, 4))
        assertEquals("AUDC C div 6 square · tonal E-3..D#8", TiaNtscSystem.instrumentSummary(base(), 0, 1))
        assertTrue(TiaNtscSystem.instrumentSummary(base(), 0, 2)!!.startsWith("AUDC 8 9-bit poly (noise) · raw"))
        // Thumbnails: the squares and the constant level; none for noise.
        assertEquals(List(32) { if (it < 16) 1f else 0f }, TiaNtscSystem.instrumentShape(base(), 0, 1))
        assertEquals(List(32) { 1f }, TiaNtscSystem.instrumentShape(base(), 0, 5))
        assertNull(TiaNtscSystem.instrumentShape(base(), 0, 2))
    }

    // ---- project files -------------------------------------------------------------------------------------

    @Test
    fun projectFilesRoundTripSoundsAndRegion() {
        val cell = Cell(57, 1, TiaRules.FX_VOLUME, 0x0A).withEffect(1, TiaRules.FX_WAVEFORM, 0x04)
        val song = blankWith(0, 0, cell, TiaPalSystem)
        val text = ProjectCodec.encode(song)
        assertTrue(text.contains("\"backend\":\"atari2600-tia-pal\",\"backendVersion\":1"))
        assertTrue(text.contains("\"region\":\"pal\""))
        assertTrue(text.contains("[57,1,12,10,32,4]"))
        val back = ProjectCodec.decode(text)
        assertEquals(song, back)
        assertEquals(TiaPalSystem, back.system)
        assertEquals(text, ProjectCodec.encode(back))
        assertEquals(seq, back.tia.instrument(3))
        assertEquals(hat, back.tia.instrument(2))
        for (s in listOf(TiaNtscSystem.blank(), TiaPalSystem.blank(), TiaNtscSystem.demo(), TiaPalSystem.demo(), TiaDemoSong.shortest())) {
            assertEquals(s, ProjectCodec.decode(ProjectCodec.encode(s)))
        }
        // A PAL song filed under the NTSC backend is refused, naming both.
        val swapped = text.replace("\"atari2600-tia-pal\"", "\"atari2600-tia-ntsc\"")
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(swapped) }
        assertTrue(e.message!!.contains("Atari 2600 PAL (TIA), not Atari 2600 NTSC (TIA)"))
    }

    // ---- pitch: honest AUDF quantization ---------------------------------------------------------------------

    @Test
    fun tonalPitchPicksTheNearestDivider() {
        // A-4 on div 6 (AUDC C) = AUDF 11 on both standards: 436.10 Hz NTSC, 432.13 Hz PAL.
        assertEquals(11, TiaRules.audf(TiaRegion.NTSC, 0xC, 57 * 64))
        assertEquals(11, TiaRules.audf(TiaRegion.PAL, 0xC, 57 * 64))
        assertEquals(436.10, TiaRules.frequency(TiaRegion.NTSC, 0xC, 11)!!, 0.005)
        assertEquals(432.13, TiaRules.frequency(TiaRegion.PAL, 0xC, 11)!!, 0.005)
        val played = TiaRules.played(TiaRegion.NTSC, 0xC, 57)!!
        assertEquals(11, played.audf)
        assertTrue(played.cents in -16.0..-15.0)
        // The edge: the lowest pitch nearer AUDF 11 than 12 is the table's (NTSC div 6: 3594).
        assertEquals(11, TiaRules.audf(TiaRegion.NTSC, 0xC, 3594))
        assertEquals(12, TiaRules.audf(TiaRegion.NTSC, 0xC, 3593))
        // Equal periods share dividers; the constant waveforms have none.
        assertEquals(TiaRules.audf(TiaRegion.NTSC, 0xC, 4000), TiaRules.audf(TiaRegion.NTSC, 0xD, 4000))
        assertEquals(TiaRules.audf(TiaRegion.NTSC, 0x6, 2000), TiaRules.audf(TiaRegion.NTSC, 0xA, 2000))
        assertNull(TiaRules.audf(TiaRegion.NTSC, 0x0, 3648))
        assertNull(TiaRules.audf(TiaRegion.NTSC, 0xB, 3648))
        assertNull(TiaRules.frequency(TiaRegion.NTSC, 0xB, 3))
        // Out of range pitches clamp to the player's 0..7679 and play the ends.
        assertEquals(31, TiaRules.audf(TiaRegion.NTSC, 0x4, -500))
        assertEquals(0, TiaRules.audf(TiaRegion.NTSC, 0x4, 99999))
        assertEquals(31, TiaRules.audf(TiaRegion.NTSC, 0xC, 0))
        // F-4 is the same frequency on div 6 (AUDF 14) and the 4-bit poly (AUDF 5): 6 × 15 = 15 × 6.
        assertEquals(14, TiaRules.audf(TiaRegion.NTSC, 0xC, 53 * 64))
        assertEquals(5, TiaRules.audf(TiaRegion.NTSC, 0x1, 53 * 64))
        assertEquals(TiaRules.frequency(TiaRegion.NTSC, 0xC, 14)!!, TiaRules.frequency(TiaRegion.NTSC, 0x1, 5)!!, 1e-9)
        // Raw: C-0 = AUDF 31, one step per semitone, every 32.
        assertEquals(31, TiaRules.rawAudf(0))
        assertEquals(0, TiaRules.rawAudf(31))
        assertEquals(31, TiaRules.rawAudf(32))
        assertEquals(0, TiaRules.rawAudf(0, -1))
        assertEquals(6, TiaRules.rawAudf(57))
    }

    @Test
    fun spansAreTheNotesBetweenAudf31AndAudf0() {
        assertEquals(40..99, TiaRules.reach(TiaRegion.NTSC, 0xC)) // E-3..D#8
        assertEquals(40..99, TiaRules.reach(TiaRegion.PAL, 0xC))
        assertEquals(59..118, TiaRules.reach(TiaRegion.NTSC, 0x4)) // B-4..A#9
        assertEquals(12..71, TiaRules.reach(TiaRegion.NTSC, 0x6)) // C-1..B-5
        assertEquals(24..84, TiaRules.reach(TiaRegion.NTSC, 0x1)) // C-2..C-7 (C-2 is 0.3 cents below AUDF 31)
        assertNull(TiaRules.reach(TiaRegion.NTSC, 0x0))
        // The ladder: 32 dividers, AUDF 0 the highest; AUDF 11 of div 6 is A-4 15 cents flat.
        val ladder = TiaRules.ladder(TiaRegion.NTSC, 0xC)
        assertEquals((0..31).toList(), ladder.map { it.audf })
        assertTrue(ladder.zipWithNext().all { (a, b) -> a.hz > b.hz })
        assertEquals(57, ladder[11].note)
        assertTrue(ladder[11].cents in -16.0..-15.0)
        assertEquals(emptyList<TiaRules.Rung>(), TiaRules.ladder(TiaRegion.NTSC, 0xB))
    }

    // ---- validation ----------------------------------------------------------------------------------------

    @Test
    fun cellValidationExplainsWhatTheLoaderRejects() {
        fun error(cell: Cell) = TiaNtscSystem.errors(blankWith(0, 0, cell)).joinToString("\n")
        assertEquals("", error(Cell(0, 1)))
        assertEquals("", error(Cell(119, 1)))
        assertTrue(error(Cell(120, 1)).contains("outside C-0..B-9"))
        assertTrue(error(Cell(57, 4)).contains("sound 04 is empty"))
        assertTrue(error(Cell(57, 65)).contains("outside 0-64"))
        assertEquals("", error(Cell(57, 1, TiaRules.FX_VOLUME, 0x0F)))
        assertTrue(error(Cell(57, 1).withEffect(1, TiaRules.FX_VOLUME, 0x10)).contains("volumes are C00-C0F"))
        assertEquals("", error(Cell(57, 1, TiaRules.FX_WAVEFORM, 0x0F)))
        assertTrue(error(Cell(57, 1, TiaRules.FX_WAVEFORM, 0x10)).contains("W00-W0F"))
        assertTrue(error(Cell(57, 1, TiaRules.FX_SPEED, 0)).contains("F00"))
        assertTrue(error(Cell(57, 1, TiaRules.FX_JUMP, 1)).contains("jumps past the last order"))
        assertEquals("", error(Cell(57, 1, TiaRules.FX_JUMP, 0)))
        // Any parameter on the other effects.
        for (code in listOf(0, 1, 2, 3, 4, 10, 13, 16, 25, 28)) assertEquals("code $code", "", error(Cell(57, 1, code, 0xFF)))
        // Codes without an effect.
        for (code in listOf(5, 6, 7, 8, 9, 14, 17, 18, 19, 20, 21, 22, 23, 24, 26, 27, 29, 30, 31, 33, 34, 35)) {
            assertTrue("code $code", error(Cell(57, 1, code, 1)).contains("does not exist on the Atari 2600"))
        }
        // At most two effect columns.
        assertTrue(error(Cell(57, 1).withEffect(2, TiaRules.FX_VOLUME, 1)).contains("at most 2"))
    }

    @Test
    fun soundValidationExplainsWhatTheLoaderRejects() {
        fun insError(ins: TiaInstrument) = TiaNtscSystem.errors(withSound { it.withInstrument(6, ins) }).joinToString("\n")
        assertEquals("", insError(square.copy(pitch = TiaEnvelope(List(255) { -128 }))))
        assertTrue(insError(square.copy(volume = TiaEnvelope(List(256) { 15 }))).contains("volume envelope has 256 steps; envelopes have at most 255"))
        assertTrue(insError(square.copy(volume = TiaEnvelope(listOf(16)))).contains("volume envelope step value 16 is outside 0..15"))
        assertTrue(insError(square.copy(arpeggio = TiaEnvelope(listOf(-65)))).contains("arpeggio envelope step value -65"))
        assertEquals("", insError(square.copy(arpeggio = TiaEnvelope(listOf(-64, 63)))))
        assertTrue(insError(square.copy(pitch = TiaEnvelope(listOf(128)))).contains("pitch envelope step value 128"))
        assertTrue(insError(square.copy(waveforms = TiaEnvelope(listOf(16)))).contains("waveform envelope step value 16"))
        assertTrue(insError(square.copy(waveform = 16)).contains("waveform 16 is outside 0-15"))
        assertTrue(insError(square.copy(volume = TiaEnvelope(listOf(15, 9), loop = 2))).contains("loop point 2 is outside"))
        assertTrue(insError(square.copy(volume = TiaEnvelope(listOf(15, 9), release = 0))).contains("release point 0 must be 1-1"))
        assertTrue(insError(square.copy(volume = TiaEnvelope(listOf(15, 9, 3), loop = 1, release = 1))).contains("must come after the loop point"))
        assertEquals("", insError(square.copy(volume = TiaEnvelope(listOf(15, 9, 3), loop = 0, release = 1))))
        assertTrue(insError(square.copy(arpeggio = TiaEnvelope(emptyList(), loop = 0))).contains("has a loop or release point but no steps"))
        assertTrue(TiaNtscSystem.errors(withSound { it.copy(instruments = it.instruments.dropLast(1)) }).joinToString().contains("63 sound slots"))
        assertTrue(TiaNtscSystem.errors(base().copy(ticksPerRow = 0)).joinToString().contains("ticks per row must be 1-255"))
    }

    @Test
    fun theDataBudgetIsTheImagesDataRegion() {
        // Every row of both channels: a note, a sound and two effects (7 bytes), different per pattern: 896 bytes an order.
        fun dense(orders: Int): Song {
            val start = base()
            val patterns = List(orders) { p ->
                Pattern("P$p", List(2) { ch ->
                    List(Songs.ROWS) { r -> Cell(r % 100, 1, TiaRules.FX_VOLUME, (p + ch) % 16).withEffect(1, TiaRules.FX_SLIDE_UP, p) }
                })
            }
            return start.copy(patterns = patterns, orders = patterns.indices.toList())
        }
        val fits = dense(69)
        // Patterns, then the envelopes: the hat's volume (3 bytes), the seq's waveforms (2) and the click's volume (2).
        assertEquals(69 * 2 * 64 * 7 + 7, TiaLowering.dataSize(fits))
        assertTrue(TiaLowering.dataSize(fits) <= TiaPlayerImage.DATA_CAPACITY)
        assertEquals(emptyList<String>(), TiaNtscSystem.errors(fits).filter { it.contains("image holds") })
        val over = dense(70)
        assertTrue(TiaLowering.dataSize(over) > TiaPlayerImage.DATA_CAPACITY)
        assertTrue(TiaNtscSystem.errors(over).joinToString().contains("the Atari 2600 image holds"))
        assertThrows(IllegalArgumentException::class.java) { TiaLowering.lower(over) }
    }

    @Test
    fun auditionChecksSlotsAndRangesButPlaysEveryNote() {
        val engine = TiaNtscSystem.engine
        val song = base()
        assertNull(engine.auditionProblem(song, 0, 57, 1, 0))
        assertNull(engine.auditionProblem(song, 1, 0, 1, -128)) // C-0 is outside div 6's span: it plays AUDF 31
        assertNull(engine.auditionProblem(song, 1, 119, 2, 127))
        assertTrue(engine.auditionProblem(song, 0, 120, 1, 0)!!.contains("outside C-0..B-9"))
        assertTrue(engine.auditionProblem(song, 0, 57, 4, 0)!!.contains("is empty"))
        assertTrue(engine.auditionProblem(song, 0, 57, 0, 0)!!.contains("Choose a sound"))
        assertTrue(engine.auditionProblem(song, 0, 57, 1, 128)!!.contains("Fine pitch"))
        assertTrue(engine.auditionProblem(song, 2, 57, 1, 0)!!.contains("Cannot audition"))
    }

    @Test
    fun warningsCoverSpansIgnoredWaveformsDelaysAndFirstNotes() {
        fun warnings(song: Song) = TiaNtscSystem.warnings(song).joinToString("\n")
        assertEquals("", warnings(base()))
        // C-0 on div 6 plays its lowest divider; on a raw sound it is just AUDF 31.
        assertTrue(warnings(blankWith(0, 0, Cell(0, 1))).contains("C-0 is outside waveform C's span E-3..D#8"))
        assertEquals("", warnings(blankWith(0, 0, Cell(0, 2))))
        assertEquals("", warnings(blankWith(0, 0, Cell(40, 1))))
        // The waveform a note keys on with: Wxx on the row, or the waveform envelope's first step (AUDC 1 for slot 3).
        assertTrue(warnings(blankWith(0, 0, Cell(40, 1, TiaRules.FX_WAVEFORM, 0x6))).isEmpty())
        assertTrue(warnings(blankWith(0, 0, Cell(100, 1, TiaRules.FX_WAVEFORM, 0x6))).contains("outside waveform 6's span C-1..B-5"))
        assertTrue(warnings(blankWith(0, 0, Cell(12, 3))).contains("outside waveform 1's span"))
        // Wxx on a sound with a waveform envelope is ignored.
        assertTrue(warnings(blankWith(0, 0, Cell(36, 3, TiaRules.FX_WAVEFORM, 0x4))).contains("W04 is ignored"))
        assertTrue(warnings(blankWith(0, 0, Cell(48, 1, TiaRules.FX_DELAY, 6))).contains("the row never plays"))
        assertTrue(warnings(blankWith(0, 4, Cell(48))).contains("has no sound"))
        // The constant waveform has no span.
        assertEquals("", warnings(blankWith(0, 0, Cell(0, 5))))
    }

    // ---- lowering ------------------------------------------------------------------------------------------

    @Test
    fun loweringWritesHeaderRecordsPatternsThenSharedEnvelopes() {
        val song = blankWith(0, 0, Cell(57, 1, TiaRules.FX_VOLUME, 0x0A).withEffect(1, TiaRules.FX_WAVEFORM, 0x04), TiaPalSystem)
        val image = TiaLowering.lower(song)
        assertEquals("TTIA", image.copyOfRange(0, 4).decodeToString())
        assertEquals(1, u8(image, 4))
        assertEquals(TiaPlayerImage.REGION_PAL, u8(image, 5))
        assertEquals(6, u8(image, 6))
        assertEquals(5, u8(image, 7))
        assertEquals(1, u16(image, 8))
        assertEquals(0, u16(image, 10))
        for (i in 12 until 20) assertEquals("header byte $i", 0, image[i].toInt())
        for (i in 24 until 64) assertEquals("header byte $i", 0, image[i].toInt())
        assertEquals(64, u8(image, TiaPlayerImage.OFF_ROWS))
        // TIA 1: mask (note, sound, two effects), A-4, slot 1, C0A, W04, then 63 empty rows; TIA 2: 64 empty rows.
        val d = TiaPlayerImage.OFF_DATA
        val tia1 = byteArrayOf(0x0B, 57, 1, 12, 0x0A, 32, 4, (0x80 or 62).toByte())
        assertArrayEquals(tia1, image.copyOfRange(d, d + tia1.size))
        assertEquals(0, u16(image, TiaPlayerImage.OFF_ORDERS))
        assertEquals(tia1.size, u16(image, TiaPlayerImage.OFF_ORDERS + 2))
        assertEquals(0x80 or 63, u8(image, d + tia1.size))
        // Envelopes after the patterns, in slot order: the hat's volume [9, 5, 0], the seq's waveforms [1, 6], the click's [15, 0].
        val env = tia1.size + 1
        val volumeHat = TiaPlayerImage.OFF_INSTRUMENTS + TiaPlayerImage.INSTRUMENT_SIZE + TiaPlayerImage.I_ENVELOPES
        assertEquals(env, u16(image, volumeHat))
        assertEquals(3, u8(image, volumeHat + 2))
        assertEquals(0xFF, u8(image, volumeHat + 3))
        assertEquals(0xFF, u8(image, volumeHat + 4))
        assertArrayEquals(byteArrayOf(9, 5, 0, 1, 6, 15, 0), image.copyOfRange(d + env, d + env + 7))
        assertEquals(env + 7, u32(image, TiaPlayerImage.H_DATA_SIZE))
        // Records: flags (defined, raw), waveform; envelopes without steps: offset 0, length 0, loop and release $FF.
        assertEquals(0x80, u8(image, TiaPlayerImage.OFF_INSTRUMENTS))
        assertEquals(0x0C, u8(image, TiaPlayerImage.OFF_INSTRUMENTS + 1))
        assertArrayEquals(byteArrayOf(0, 0, 0, -1, -1, 0), record(image, 1).copyOfRange(8, 14))
        assertEquals(TiaPlayerImage.FLAG_DEFINED or TiaPlayerImage.FLAG_RAW, u8(image, TiaPlayerImage.OFF_INSTRUMENTS + 32))
        assertEquals(1, u8(record(image, 3), TiaPlayerImage.I_ENVELOPES + 6 * TiaPlayerImage.ENV_WAVEFORM + 3)) // loop 1
        assertArrayEquals(ByteArray(32), record(image, 4))
    }

    @Test
    fun byteIdenticalSoundsInTwoSlotsStayTwoRecords() {
        val twin = withSound { it.withInstrument(4, square) }
        val image = TiaLowering.lower(twin)
        assertEquals(5, u8(image, TiaPlayerImage.H_INSTRUMENTS))
        assertArrayEquals(record(image, 1), record(image, 4))
        // A live edit of one slot changes only that slot's record: the other keeps playing its own.
        val edited = TiaLowering.lower(twin.copy(sound = twin.tia.withInstrument(4, square.copy(waveform = 0x4))))
        assertArrayEquals(record(image, 1), record(edited, 1))
        assertNotEquals(record(image, 4).toList(), record(edited, 4).toList())
        assertEquals(0x04, u8(record(edited, 4), TiaPlayerImage.I_WAVEFORM))
        // Equal envelope values are stored once; loop and release stay per record.
        val shared = withSound {
            it.withInstrument(1, square.copy(volume = TiaEnvelope(listOf(15, 8, 2), loop = 1)))
                .withInstrument(4, square.copy(volume = TiaEnvelope(listOf(15, 8, 2), release = 2)))
        }
        val s = TiaLowering.lower(shared)
        val v1 = TiaPlayerImage.I_ENVELOPES + 6 * TiaPlayerImage.ENV_VOLUME
        assertEquals(u16(record(s, 1), v1), u16(record(s, 4), v1))
        assertEquals(1, u8(record(s, 1), v1 + 3))
        assertEquals(0xFF, u8(record(s, 4), v1 + 3))
        assertEquals(2, u8(record(s, 4), v1 + 4))
    }

    @Test
    fun arrangementRenumbersJumpsInBothColumns() {
        val start = base()
        val patterns = List(3) { Songs.blankPattern(start) }
        var song = start.copy(patterns = patterns, orders = listOf(0, 1, 2), parkedOrders = listOf(1), loopOrder = 2)
        song = with(song, 2, 1, 5, Cell(48, 1).withEffect(1, TiaRules.FX_JUMP, 2))
        val arranged = TiaNtscSystem.arranged(song)
        assertEquals(listOf(0, 2), arranged.orders)
        assertEquals(1, arranged.loopOrder)
        assertEquals(1, TiaNtscSystem.jumpTarget(arranged.patterns[2].tracks[1][5]))
        val loop = TiaNtscSystem.patternLoop(song, 2)
        assertEquals(listOf(2), loop.orders)
        assertEquals(0, TiaNtscSystem.jumpTarget(loop.patterns[2].tracks[1][5]))
        assertEquals(2, u16(TiaLowering.lower(song), TiaPlayerImage.H_ORDERS))
    }

    // ---- key-on registers ----------------------------------------------------------------------------------

    @Test
    fun sharedStateShowsTheKeyOnRegistersStraightPlayReaches() {
        var song = base()
        song = with(song, 0, 0, 0, Cell(57, 1))                                  // A-4, div 6: AUDF 11
        song = with(song, 0, 1, 0, Cell(63, 2))                                  // raw hat: D#5 = AUDF 0
        song = with(song, 0, 0, 2, Cell(effect = TiaRules.FX_WAVEFORM, param = 1)) // no note: the key-on registers stay
        song = with(song, 0, 0, 4, Cell(60, 0, TiaRules.FX_WAVEFORM, 1))          // C-5 on the 4-bit poly: AUDF 3
        song = with(song, 0, 1, 4, Cell(Cell.CUT))                               // OFF without a release: silent
        song = with(song, 0, 0, 6, Cell(57, 0, TiaRules.FX_FINE, 0x80 + 40))      // a note row without W: div 6 again; +40/64
        song = with(song, 0, 1, 6, Cell(30, 5))                                  // the constant level: no AUDF
        song = with(song, 0, 0, 8, Cell(50, 3))                                  // the waveform envelope's first step: AUDC 1

        fun tags(row: Int) = TiaNtscSystem.sharedState(song, 0, row).tags
        fun lines(row: Int) = TiaNtscSystem.sharedState(song, 0, row).lines
        assertEquals(listOf("C·11", "8·0"), tags(0))
        assertEquals(listOf("C·11", "8·0"), tags(2))
        assertTrue(lines(0).any { it.startsWith("TIA 1: A-4 · AUDC C div 6 square · AUDF 11 · 436.1 Hz · −15.") })
        assertTrue(lines(0).any { it.startsWith("TIA 2: D#5 · AUDC 8 9-bit poly (noise) · AUDF 0 (raw)") })
        assertEquals(listOf("1·3", null), tags(4))
        assertTrue(lines(4).contains("TIA 2: silent"))
        // A-4 + 40/64 semitone: past the edge to the next divider up (AUDF 10, 475.8 Hz, 73 cents sharp of the request).
        assertEquals(listOf("C·10", "0·DC"), tags(6))
        assertTrue(lines(6).any { it.contains("no AUDF: a DC level") })
        assertEquals("1·", tags(8)[0]!!.take(2))
        // PAL: the same divider, about 16 cents lower.
        val pal = song.copy(sound = song.tia.copy(region = TiaRegion.PAL))
        assertEquals("C·11", TiaPalSystem.sharedState(pal, 0, 0).tags[0])
        assertTrue(TiaPalSystem.sharedState(pal, 0, 0).lines.any { it.contains("432.1 Hz") })
        // The key-on records directly.
        val k = TiaSharedState.at(song, 0, 0)[0]!!
        assertEquals(57, k.note)
        assertEquals(0xC, k.waveform)
        assertFalse(k.raw)
    }

    // ---- fine pitch and the demos --------------------------------------------------------------------------

    @Test
    fun finePitchRidesOnPxx() {
        val fine = TiaNtscSystem.fine
        assertEquals("Pxx", fine.name)
        assertEquals(0..63, fine.range(57))
        assertEquals(50.0, fine.cents(57, 32), 1e-9)
        val written = fine.write(Cell(instrument = 1), 57, 20)!!
        assertEquals(TiaRules.FX_FINE, written.effect)
        assertEquals(0x80 + 20, written.param)
        assertEquals(20, fine.of(written))
        assertEquals(Cell(57, 1), fine.write(written, 57, 0))
        // Both columns taken by other effects: no room.
        assertNull(fine.write(Cell(57, 1, TiaRules.FX_VOLUME, 1).withEffect(1, TiaRules.FX_WAVEFORM, 1), 57, 5))
    }

    @Test
    fun demosAreValidAndUseTheChipsHonestPitch() {
        val ntsc = TiaNtscSystem.demo()
        val pal = TiaPalSystem.demo()
        for ((system, demo) in listOf(TiaNtscSystem to ntsc, TiaPalSystem to pal)) {
            assertEquals(TiaDemoSong.TITLE, demo.title)
            assertEquals(system, demo.system)
            assertEquals(emptyList<String>(), system.errors(demo))
            assertEquals(emptyList<String>(), system.warnings(demo))
        }
        // The same composition, slower frames per row on PAL.
        assertEquals(7, ntsc.ticksPerRow)
        assertEquals(6, pal.ticksPerRow)
        assertEquals(ntsc.patterns, pal.patterns)
        assertEquals(ntsc.orders, pal.orders)
        assertEquals(ntsc.tia.instruments, pal.tia.instruments)
        // It uses a W timbre switch, slides and a portamento, raw drums and a waveform envelope.
        val cells = ntsc.patterns.flatMap { p -> p.tracks.flatten() }
        val codes = cells.flatMap { c -> (0..c.more.size).map { c.effectCode(it) } }.toSet()
        assertTrue(codes.containsAll(listOf(TiaRules.FX_WAVEFORM, TiaRules.FX_SLIDE_UP, TiaRules.FX_SLIDE_DOWN, TiaRules.FX_PORTAMENTO, TiaRules.FX_ARPEGGIO)))
        assertTrue(ntsc.tia.instruments.filterNotNull().any { it.raw && it.waveforms.values.isNotEmpty() })
        // The shortest song: one row, A-4 on TIA 1 with a div 6 square without envelopes.
        val shortest = TiaDemoSong.shortest()
        assertEquals(emptyList<String>(), TiaNtscSystem.errors(shortest))
        assertEquals(1, shortest.patterns.single().length)
        assertEquals(Cell(57, 1), shortest.patterns.single().tracks[0][0])
        assertEquals(TiaInstrument("Square", 0xC), shortest.tia.instrument(1))
        assertEquals(1, TiaLowering.highestSlot(shortest.tia))
    }
}
