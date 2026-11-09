package org.thortracker.system

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.AyLowering
import org.thortracker.backend.AyPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.ay.AyAtariStSystem
import org.thortracker.system.ay.AyEnvelopeSettings
import org.thortracker.system.ay.AyInstrument
import org.thortracker.system.ay.AyInstrumentView
import org.thortracker.system.ay.AyMsxSystem
import org.thortracker.system.ay.AyPresets
import org.thortracker.system.ay.AyRules
import org.thortracker.system.ay.AySweep
import org.thortracker.system.ay.AySystem
import org.thortracker.system.ay.AyVariant
import org.thortracker.system.ay.AyZx128System
import org.thortracker.system.ay.ay
import org.thortracker.system.nes.NesEnvelope
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow

class AyModelTest {
    private val zx = AyZx128System

    private fun with(song: Song, order: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun blankWith(channel: Int, row: Int, cell: Cell, system: AySystem = zx) = with(system.blank(), 0, channel, row, cell)

    private fun slot(name: String) = AyPresets.ALL.indexOfFirst { it.name == name } + 1

    private fun withSound(song: Song, slot: Int, ins: AyInstrument) = song.copy(sound = song.ay.withInstrument(slot, ins))

    private fun cents(a: Double, b: Double) = 1200.0 * log2(a / b)

    private fun u16(image: ByteArray, at: Int) = (image[at].toInt() and 0xFF) or ((image[at + 1].toInt() and 0xFF) shl 8)

    // ---- project files -------------------------------------------------------------------------------------

    @Test
    fun songsRoundTripThroughProjectFilesOnEveryVariant() {
        for (variant in AyVariant.entries) {
            for (song in listOf(variant.system.blank(), variant.system.demo())) {
                val text = ProjectCodec.encode(song)
                val back = ProjectCodec.decode(text)
                assertEquals(song, back)
                assertEquals(variant.system, back.system)
                assertEquals(text, ProjectCodec.encode(back))
            }
        }
    }

    @Test
    fun aSoundOfAnotherVariantIsAStructuralError() {
        val zxSong = zx.blank()
        // A project whose backend names another computer than its sound is rejected.
        val text = ProjectCodec.encode(zxSong).replace("\"ay-zx128\"", "\"ay-atari-st\"")
        assertThrows(org.thortracker.model.ProjectFormatException::class.java) { ProjectCodec.decode(text) }
    }

    // ---- presets ---------------------------------------------------------------------------------------------

    @Test
    fun presetsAreValid() {
        for (p in AyPresets.ALL) {
            assertNull(p.name, p.instrument.problem())
            assertTrue(p.name, p.note in AyRules.NOTES)
        }
        for (variant in AyVariant.entries) {
            assertEquals(emptyList<String>(), variant.system.errors(variant.system.blank()))
        }
    }

    // ---- periods ---------------------------------------------------------------------------------------------

    @Test
    fun periodFunctionsFollowTheGeneratedTables() {
        val c0 = 440.0 * 2.0.pow(-57 / 12.0)
        val tables = mapOf(AyVariant.ZX128 to AyPlayerImage.DIV_ZX128, AyVariant.MSX to AyPlayerImage.DIV_MSX,
            AyVariant.ATARI_ST to AyPlayerImage.DIV_ATARI_ST, AyVariant.CPC to AyPlayerImage.DIV_CPC)
        assertEquals(AyVariant.entries.toSet(), tables.keys)
        // A whole period is at most half a step off: its error in cents, plus the table's own rounding.
        fun bound(period: Int) = 1200.0 * log2((period + 0.5) / period) + 1.5
        for ((variant, table) in tables) {
            assertEquals(768, table.size)
            val clock = AyPlayerImage.MASTER_HZ[variant.index].toDouble() / AyPlayerImage.AY_DIV[variant.index]
            for (f in 0 until 768) {
                val expected = floor(256.0 * clock / (16.0 * c0 * 2.0.pow(f / 768.0)) + 0.5).toLong()
                assertTrue("${variant.label} DIV[$f]", abs(table[f] - expected) <= 1)
            }
            for (pitch in 0..AyPlayerImage.PITCH_MAX) {
                val o = pitch / 768
                val raw = (table[pitch % 768].toLong() + (128L shl o)) shr (8 + o)
                assertEquals("${variant.label} pitch $pitch", raw.coerceIn(1L, 4095L).toInt(), AyRules.tonePeriod(variant, pitch))
            }
            for (note in 24..48) {
                // J00 on a saw: the envelope's cycle is the note itself; J01 an octave up.
                val hz = 440.0 * 2.0.pow((note - 57) / 12.0)
                val ep = AyRules.autoEnvelopePeriod(variant, note * 64, 0, 8)
                assertTrue("${variant.label} note $note", abs(cents(AyRules.envelopeHz(variant, ep, 8), hz)) < bound(ep))
                // Triangles take two ramps a cycle: half the period for the same note.
                val tri = AyRules.autoEnvelopePeriod(variant, note * 64, 0, 10)
                assertTrue("${variant.label} triangle $note", abs(cents(AyRules.envelopeHz(variant, tri, 10), hz)) < bound(tri))
                // Synced, the tone sits on the note within the envelope period's step.
                val tp = AyRules.syncedTonePeriod(variant, ep, 0, 8)
                assertEquals(ep * 16, tp)
                assertTrue(abs(cents(AyRules.toneHz(variant, tp), hz)) < bound(ep))
            }
            val a2 = 33 * 64
            val up = AyRules.envelopeHz(variant, AyRules.autoEnvelopePeriod(variant, a2, 1, 8), 8)
            val own = AyRules.envelopeHz(variant, AyRules.autoEnvelopePeriod(variant, a2, 0, 8), 8)
            assertEquals(2.0, up / own, 0.1)
            // Negative octaves: the tone is the envelope period times 2^(4 + t + J), rounded when that is a division.
            assertEquals(40 * 8, AyRules.syncedTonePeriod(variant, 40, -2, 10))
            assertEquals(5, AyRules.syncedTonePeriod(variant, 40, -7, 8))
            assertEquals(4095, AyRules.syncedTonePeriod(variant, 4000, 0, 8))
            assertEquals(AyRules.tonePeriod(variant, AyPlayerImage.PITCH_MAX), AyRules.tonePeriod(variant, AyPlayerImage.PITCH_MAX + 100))
        }
        assertEquals(9, AyRules.lowestNote(AyVariant.ZX128))
        assertEquals(9, AyRules.lowestNote(AyVariant.MSX))
        assertEquals(11, AyRules.lowestNote(AyVariant.ATARI_ST))
        assertEquals(0, AyRules.lowestNote(AyVariant.CPC))
        assertEquals(3822, AyRules.tonePeriod(AyVariant.CPC, 0))
        assertEquals(142, AyRules.tonePeriod(AyVariant.CPC, 57 * 64))
        assertEquals(16, AyRules.tonePeriod(AyVariant.CPC, 95 * 64))
        assertEquals(4095, AyRules.tonePeriod(AyVariant.ZX128, 0))
        assertEquals(setOf(8, 9, 10, 11, 12, 13, 14, 15), AyRules.SHAPE_NAMES.keys)
    }

    // ---- tuning realisation --------------------------------------------------------------------------------

    @Test
    fun fineWritesPxxInAFreeColumn() {
        val fine = zx.fine
        assertEquals(25.0, fine.cents(57, 16), 1e-9)
        val busy = Cell(57, 1, AyRules.FX_VIBRATO, 0x23)
        val tuned = fine.write(busy, 57, 16)!!
        assertEquals(AyRules.FX_FINE, tuned.effectCode(1))
        assertEquals(0x90, tuned.effectParam(1))
        assertEquals(16, fine.of(tuned))
        assertEquals(busy, fine.write(tuned, 57, 0))
        var full = Cell(57, 1)
        for (c in 0 until 4) full = full.withEffect(c, AyRules.FX_VOLUME, 0x0C)
        assertNull(fine.write(full, 57, 8))
        assertNull(fine.of(Cell(57, 1, AyRules.FX_FINE, 0x70)))
    }


    /** A buzzer on A, a bell on B taking the envelope from it, hats on C; then a snare on B taking the noise from C. */
    private fun sharedSong(): Song {
        var song = zx.blank()
        song = with(song, 0, 0, 0, Cell(33, slot("Buzzer bass")))
        song = with(song, 0, 1, 4, Cell(57, slot("Bell")))
        song = with(song, 0, 2, 4, Cell(60, slot("Closed hat")))
        song = with(song, 0, 2, 8, Cell(60, slot("Open hat")))
        song = with(song, 0, 1, 9, Cell(50, slot("Snare")))
        return song
    }

    @Test
    fun sharedStateTagsTheOwnersByTheMostRecentKeyOn() {
        val song = sharedSong()
        val start = zx.sharedState(song, 0, 0)!!
        assertEquals(listOf("ENV", null, null), start.tags)
        val taken = zx.sharedState(song, 0, 4)!!
        assertEquals(listOf("env", "ENV", "NZ"), taken.tags)
        // B's snare ends its bell: A's buzzer owns the envelope again; the snare takes the noise from the open hat.
        val noise = zx.sharedState(song, 0, 9)!!
        assertEquals(listOf("ENV", "NZ", "nz"), noise.tags)
        val warnings = zx.warnings(song)
        assertEquals(2, warnings.size)
        // An OFF on A before the bell: a clean handover, no warning.
        val clean = with(song, 0, 0, 3, Cell(Cell.CUT))
        assertEquals(1, zx.warnings(clean).size)
        assertEquals(listOf(null, "ENV", "NZ"), zx.sharedState(clean, 0, 4)!!.tags)
        // A parked order is read on its own.
        val parked = song.copy(patterns = song.patterns + Songs.blankPattern(song), orders = listOf(0, 1), parkedOrders = listOf(1))
        assertEquals(listOf(null, null, null), zx.sharedState(parked, 1, 0)!!.tags)
        assertNull(zx.sharedState(song, 5, 0))
    }


    /** A sound on the envelope (saw, auto) whose release holds level 10 for good, so a released note keeps sounding. */
    private val held = AyInstrument("held", volume = NesEnvelope(listOf(15, 15, 10), loop = 0, release = 1),
        envelope = AyEnvelopeSettings(shape = 8, auto = true))

    @Test
    fun portamentoGlidesOnlyOnAHeldNoteAndSlidesEndIt() {
        val buzzer = slot("Buzzer bass")
        // B keys on with 320; A's buzzer takes the envelope; B's next note only glides (no key-on), so A keeps it.
        var song = withSound(zx.blank(), 20, held)
        song = with(song, 0, 1, 0, Cell(57, 20, AyRules.FX_PORTAMENTO, 0x20))
        song = with(song, 0, 0, 2, Cell(33, buzzer))
        song = with(song, 0, 1, 4, Cell(60))
        assertEquals("ENV", zx.sharedState(song, 0, 4)!!.tags[0])
        // 1xx (or 2xx) ends the glide: the next note keys on and takes the envelope.
        for (code in listOf(AyRules.FX_SLIDE_UP, AyRules.FX_SLIDE_DOWN)) {
            val slid = with(song, 0, 1, 3, Cell(effect = code, param = 0))
            assertEquals(AyRules.effectText(code, 0), "ENV", zx.sharedState(slid, 0, 4)!!.tags[1])
        }
        // A released note does not glide either: the next note keys on.
        val released = with(song, 0, 1, 3, Cell(Cell.CUT))
        assertEquals("env", zx.sharedState(released, 0, 3)!!.tags[1])
        assertEquals("ENV", zx.sharedState(released, 0, 4)!!.tags[1])
    }

    @Test
    fun aSecondKeyOffLeavesAReleasedNoteAlone() {
        var song = withSound(zx.blank(), 20, held)
        song = with(song, 0, 1, 0, Cell(57, 20))
        song = with(song, 0, 1, 2, Cell(Cell.CUT))
        song = with(song, 0, 1, 4, Cell(Cell.CUT, effect = AyRules.FX_CUT, param = 2))
        // Released at row 2, it keeps sounding on its release level through the second OFF and the Sxx.
        for (row in 2..6) assertEquals("row $row", "ENV", zx.sharedState(song, 0, row)!!.tags[1])
    }


    // ---- arrangement, audition, lowering and exports -------------------------------------------------------

    @Test
    fun arrangementRenumbersJumpsInEveryColumn() {
        val base = zx.blank()
        var song = base.copy(patterns = List(3) { Songs.blankPattern(base) }, orders = listOf(0, 1, 2), parkedOrders = listOf(1))
        song = with(song, 2, 2, 5, Cell(48, 1).withEffect(2, AyRules.FX_JUMP, 2))
        val arranged = zx.arranged(song)
        assertEquals(listOf(0, 2), arranged.orders)
        assertEquals(1, zx.jumpTarget(arranged.patterns[2].tracks[2][5]))
        val loop = zx.patternLoop(song, 2)
        assertEquals(listOf(2), loop.orders)
        assertEquals(0, zx.jumpTarget(loop.patterns[2].tracks[2][5]))
    }

    @Test
    fun auditionChecksTheSlotAndArguments() {
        val song = zx.blank()
        val engine = zx.engine
        assertNull(engine.auditionProblem(song, 0, 57, 1, 0))
        assertNull(engine.auditionProblem(song, 2, 33, slot("Buzzer bass"), -128))
        assertNotNull(engine.auditionProblem(song, 0, 57, 60, 0))
        assertNotNull(engine.auditionProblem(song, 0, 57, 0, 0))
        assertNotNull(engine.auditionProblem(song, 3, 57, 1, 0))
        assertNotNull(engine.auditionProblem(song, 0, 57, 1, 128))
    }

    @Test
    fun loweringWritesTheVariantAndTheActiveEnvelopeMode() {
        for (variant in AyVariant.entries) {
            val image = AyLowering.lower(variant.system.blank())
            assertEquals("TTAY", String(image.copyOfRange(0, 4).map { it.toInt().toChar() }.toCharArray()))
            assertEquals(variant.index, image[AyPlayerImage.H_VARIANT].toInt())
            assertEquals(AyPresets.ALL.size, image[AyPlayerImage.H_INSTRUMENTS].toInt())
        }
        fun record(name: String): ByteArray {
            val image = AyLowering.lower(zx.blank())
            val at = AyPlayerImage.OFF_INSTRUMENTS + AyPlayerImage.INSTRUMENT_SIZE * (slot(name) - 1)
            return image.copyOfRange(at, at + AyPlayerImage.INSTRUMENT_SIZE)
        }
        val sync = record("Sync buzzer")
        assertEquals(AyPlayerImage.FLAG_DEFINED or AyPlayerImage.FLAG_AUTO_PERIOD or AyPlayerImage.FLAG_SYNC, sync[AyPlayerImage.I_FLAGS].toInt() and 0xFF)
        assertEquals(10, sync[AyPlayerImage.I_SHAPE].toInt())
        assertEquals(-2, sync[AyPlayerImage.I_OCTAVE].toInt())
        assertEquals(0, u16(sync, AyPlayerImage.I_PERIOD))
        val bell = record("Bell")
        assertEquals(AyPlayerImage.FLAG_DEFINED, bell[AyPlayerImage.I_FLAGS].toInt() and 0xFF)
        assertEquals(0x1000, u16(bell, AyPlayerImage.I_PERIOD))
        assertEquals(AyPlayerImage.NO_ENVELOPE, u16(bell, AyPlayerImage.I_ENV_VOLUME))
        // A manual sound keeps its octave and sync in the model; the image gets neither.
        val kept = AyInstrument("x", envelope = AyEnvelopeSettings(shape = 9, auto = false, octave = 5, period = 777, sync = true))
        val r = AyLowering.record(kept) { 0 }
        assertEquals(AyPlayerImage.FLAG_DEFINED, r[AyPlayerImage.I_FLAGS].toInt() and 0xFF)
        assertEquals(0, r[AyPlayerImage.I_OCTAVE].toInt())
        assertEquals(777, u16(r, AyPlayerImage.I_PERIOD))
        // Identical envelopes are stored once: Arp major and Arp minor share their volume envelope.
        val arps = AyLowering.lower(zx.blank())
        val major = AyPlayerImage.OFF_INSTRUMENTS + AyPlayerImage.INSTRUMENT_SIZE * (slot("Arp major") - 1)
        val minor = AyPlayerImage.OFF_INSTRUMENTS + AyPlayerImage.INSTRUMENT_SIZE * (slot("Arp minor") - 1)
        assertEquals(u16(arps, major + AyPlayerImage.I_ENV_VOLUME), u16(arps, minor + AyPlayerImage.I_ENV_VOLUME))
        assertFalse(u16(arps, major + AyPlayerImage.I_ENV_ARPEGGIO) == u16(arps, minor + AyPlayerImage.I_ENV_ARPEGGIO))
    }

    @Test
    fun zeroPeriodReadingMatchesEachChipsEnvelopeClock() {
        val ins = AyInstrument("zero", envelope = AyEnvelopeSettings(shape = 8, auto = false, period = 0))
        for (variant in AyVariant.entries) {
            val expected = variant.clockHz / (if (variant.ym) 256.0 else 128.0)
            assertEquals(expected, AyInstrumentView.reading(variant, ins, 57).envelopeHz!!, 1e-9)
            assertEquals(expected / 2, AyRules.envelopeHz(variant, 0, 10), 1e-9)
            val one = AyRules.envelopeHz(variant, 1, 8)
            assertEquals(if (variant.ym) 1.0 else 2.0, expected / one, 1e-9)
        }
    }

    @Test
    fun invalidRowsAndSoundsAreRejected() {
        val invalidCells = listOf(
            Cell(57, 1, AyRules.FX_ENV_SHAPE, 0x05),
            Cell(57, 1, AyRules.FX_ENV_AUTO, 0x08),
            Cell(57, 1, AyRules.FX_MIXER, 4),
            Cell(57, 1, AyRules.FX_NOISE, 0x20),
            Cell(57, 1).withEffect(3, AyRules.FX_VOLUME, 0x10),
            Cell(57, 1, AyRules.FX_SPEED, 0),
            Cell(57, 1, AyRules.FX_JUMP, 1),
            Cell(57, 60), Cell(96, 1), Cell(57, 1, 20, 1),
        )
        for (cell in invalidCells) assertFalse(cell.toString(), zx.errors(blankWith(0, 0, cell)).isEmpty())
        for ((code, param) in listOf(
            AyRules.FX_ENV_SHAPE to 0, AyRules.FX_ENV_SHAPE to 0x0F, AyRules.FX_ENV_AUTO to 0x07,
            AyRules.FX_ENV_AUTO to 0xF8, AyRules.FX_ENV_HIGH to 0xFF, AyRules.FX_ENV_LOW to 0xFF,
            AyRules.FX_MIXER to 0, AyRules.FX_MIXER to 3, AyRules.FX_NOISE to 0x1F,
            AyRules.FX_VOLUME to 0x0F, AyRules.FX_FINE to 0xFF,
        )) {
            assertEquals(AyRules.effectText(code, param), emptyList<String>(), zx.errors(blankWith(0, 0, Cell(57, 1, code, param))))
        }
        val invalidSounds = listOf(
            AyInstrument("x", volume = NesEnvelope(listOf(15, 16))),
            AyInstrument("x", mixer = NesEnvelope(listOf(4))),
            AyInstrument("x", noise = NesEnvelope(listOf(32))),
            AyInstrument("x", noise = NesEnvelope(listOf(3, 4), loop = 0, release = 1)),
            AyInstrument("x", envelope = AyEnvelopeSettings(shape = 3)),
            AyInstrument("x", envelope = AyEnvelopeSettings(octave = 8)),
            AyInstrument("x", envelope = AyEnvelopeSettings(period = 65536)),
            AyInstrument("x", sweep = AySweep(97, 0, 1)),
        )
        for (ins in invalidSounds) assertFalse(ins.toString(), zx.errors(withSound(zx.blank(), 1, ins)).isEmpty())
        assertEquals(emptyList<String>(), zx.errors(withSound(zx.blank(), 1,
            AyInstrument("x", envelope = AyEnvelopeSettings(shape = 9, auto = false, octave = 5, period = 777, sync = true)))))
        assertFalse(zx.structuralErrors(zx.blank().let {
            it.copy(sound = it.ay.copy(instruments = it.ay.instruments.take(10)))
        }).isEmpty())
    }

    @Test
    fun warningsClearWhenTheirCauseIsRemoved() {
        val lead = slot("Square lead")
        val pairs = listOf(
            blankWith(0, 4, Cell(48)) to blankWith(0, 4, Cell(48, lead)),
            blankWith(0, 0, Cell(5, lead)) to blankWith(0, 0, Cell(9, lead)),
            blankWith(0, 0, Cell(10, lead), AyAtariStSystem) to blankWith(0, 0, Cell(11, lead), AyAtariStSystem),
            blankWith(0, 0, Cell(48, lead, AyRules.FX_DELAY, 6)) to blankWith(0, 0, Cell(48, lead, AyRules.FX_DELAY, 5)),
            blankWith(0, 1, Cell(effect = AyRules.FX_MIXER, param = 2)) to blankWith(0, 1, Cell(57, lead, AyRules.FX_MIXER, 2)),
            blankWith(0, 0, Cell(57, lead, AyRules.FX_ENV_AUTO, 1)) to
                blankWith(0, 0, Cell(57, lead, AyRules.FX_ENV_AUTO, 1).withEffect(1, AyRules.FX_ENV_SHAPE, 8)),
        )
        for ((before, after) in pairs) {
            assertFalse(before.system.warnings(before).isEmpty())
            assertEquals(emptyList<String>(), after.system.warnings(after))
        }
        assertEquals(emptyList<String>(), zx.warnings(blankWith(0, 0, Cell(33, slot("Buzzer bass"), AyRules.FX_ENV_AUTO, 1))))
    }

    @Test
    fun displayedPeriodsUseTheImagesActiveMode() {
        val kept = AyInstrument("kept", envelope = AyEnvelopeSettings(shape = 9, auto = true, octave = 0, period = 0x0309))
        val song = with(withSound(zx.blank(), 21, kept), 0, 0, 0, Cell(57, 21, AyRules.FX_ENV_LOW, 0x40))
        val later = with(with(song, 0, 0, 0, Cell(57, 21)), 0, 0, 2, Cell(effect = AyRules.FX_ENV_LOW, param = 0x40))
        for ((document, row) in listOf(song to 0, later to 2)) {
            for (text in listOf(zx.sharedState(document, 0, row)!!.lines[0], zx.effectDetail(document, 0, 0, row, 0)!!)) {
                val periods = Regex("\\b[0-9A-F]{4}\\b").findAll(text).map { it.value }.toSet()
                assertEquals(setOf("0040"), periods)
            }
        }
        assertEquals(AyEnvelopeSettings(9, true, 0, 0x0309), kept.envelope)
    }

    @Test
    fun displayedArpeggiosUseTheHeldNoteAndCurrentIntervals() {
        var song = blankWith(0, 0, Cell(57, 1, AyRules.FX_ARPEGGIO, 0x47))
        song = with(song, 0, 0, 2, Cell(effect = AyRules.FX_ARPEGGIO, param = 0x37))
        fun notes(row: Int) = Regex("[A-G][#-][0-9]").findAll(zx.effectDetail(song, 0, 0, row, 0)!!)
            .map { it.value }.toList()
        assertEquals(listOf("A-4", "C#5", "E-5"), notes(0))
        assertEquals(listOf("A-4", "C-5", "E-5"), notes(2))
        assertNull(zx.effectDetail(song, 0, 0, 1, 0))
    }

    @Test
    fun demoTempoAndEnvelopeHandoverAgreeAcrossComputers() {
        for (variant in AyVariant.entries) {
            val system = variant.system
            val demo = system.demo()
            assertEquals(emptyList<String>(), system.errors(demo))
            assertEquals(emptyList<String>(), system.warnings(demo))
            assertEquals(10.0, system.tickHz / demo.ticksPerRow, 0.05)
            val bridge = demo.orders.indexOf(2)
            assertEquals("ENV", system.sharedState(demo, bridge, 0)!!.tags[0])
            assertEquals("ENV", system.sharedState(demo, 1, 0)!!.tags[1])
        }
    }


}
