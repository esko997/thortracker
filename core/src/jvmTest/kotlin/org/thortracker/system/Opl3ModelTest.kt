package org.thortracker.system

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.Opl3GoldenSongs
import org.thortracker.backend.Opl3Lowering
import org.thortracker.model.Cell
import org.thortracker.model.Fx
import org.thortracker.model.ProjectCodec
import org.thortracker.model.ProjectFormatException
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.opl3.Opl3Curated
import org.thortracker.system.opl3.Opl3Instrument
import org.thortracker.system.opl3.Opl3Presets
import org.thortracker.system.opl3.Opl3Rules
import org.thortracker.system.opl3.Opl3System
import org.thortracker.system.opl3.Opl3Vgm
import org.thortracker.system.opl3.Opl3VgmTags
import org.thortracker.system.opl3.Opl3VgmRenderer
import org.thortracker.system.opl3.opl3
import org.thortracker.system.sid.SidSystem

class Opl3ModelTest {
    @After
    fun noRenderer() {
        Opl3Vgm.renderer = null
    }

    private fun with(song: Song, order: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun blankWith(channel: Int, row: Int, cell: Cell) = with(Opl3System.blank(), 0, channel, row, cell)

    // ---- cells with several effect columns ---------------------------------------------------------------

    @Test
    fun effectColumnsAreCanonical() {
        val cell = Cell(60, 1).withEffect(3, Opl3Rules.FX_PAN, 0x10)
        assertEquals(listOf(Fx.BLANK, Fx.BLANK, Fx(Opl3Rules.FX_PAN, 0x10)), cell.more)
        assertEquals(3, cell.lastEffectColumn)
        assertTrue(cell.hasAnyEffect)
        assertFalse(cell.hasEffect)
        val cleared = cell.withEffect(3, 0, 0)
        assertEquals(Cell(60, 1), cleared)
        assertTrue(cleared.more.isEmpty())
        assertEquals(Cell(60, 1, 0xC, 0x20), Cell(60, 1).withEffect(0, 0xC, 0x20))
        assertThrows(IllegalArgumentException::class.java) { Cell(more = listOf(Fx.BLANK)) }
        assertThrows(IllegalArgumentException::class.java) { Cell(more = List(4) { Fx(1, 1) }) }
    }

    @Test
    fun oneColumnSystemsRejectExtraColumns() {
        val cell = Cell(48, 1).withEffect(1, 0xC, 0x08)
        for (system in listOf(GbSystem, NesSystem, SidSystem)) {
            assertEquals(1, system.effectColumns(0))
            val problem = system.cellProblem(system.blank(), 0, cell)
            assertTrue("${system.id}: $problem", problem!!.contains("2 effect columns"))
        }
        assertEquals(4, Opl3System.effectColumns(8))
    }

    @Test
    fun projectFilesStoreExtraColumnsAfterTheFourInts() {
        val cell = Cell(60, 2, 0xC, 0x20).withEffect(1, 8, 0x11).withEffect(3, 25, 0x90)
        val song = blankWith(0, 0, cell)
        val text = ProjectCodec.encode(song)
        assertTrue(text.contains("[60,2,12,32,8,17,0,0,25,144]"))
        assertTrue(text.contains("[-1,0,0,0]"))
        assertTrue(text.contains("\"backend\":\"opl3-fm\",\"backendVersion\":1"))
        assertEquals(song, ProjectCodec.decode(text))
        // A trailing blank column is not canonical; too many values are not a cell.
        for (bad in listOf("[60,2,12,32,8,17,0,0]", "[60,2,12,32,1,1,1,1,1,1,1,1]", "[60,2,12]")) {
            assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(text.replace("[60,2,12,32,8,17,0,0,25,144]", bad)) }
        }
    }

    @Test
    fun opl3SongsRoundTrip() {
        for (song in listOf(Opl3System.blank(), Opl3System.demo(), Opl3GoldenSongs.spike(), Opl3GoldenSongs.features())) {
            val text = ProjectCodec.encode(song)
            val back = ProjectCodec.decode(text)
            assertEquals(song, back)
            assertEquals(text, ProjectCodec.encode(back))
        }
    }

    // ---- presets and curated controls -------------------------------------------------------------------

    @Test
    fun presetsAreValidAndIncludeSweptDrums() {
        assertTrue(Opl3Presets.ALL.size >= 12)
        for (p in Opl3Presets.ALL) assertNull(p.name, p.instrument.problem())
        val drums = Opl3Presets.ALL.filter { it.category == Opl3Presets.Category.DRUM }
        assertTrue(drums.count { it.instrument.sweep.active } >= 3)
        assertTrue(Opl3Presets.ALL.count { it.instrument.fourOp } >= 3)
        // A new song starts with them, the rest empty.
        val sound = Opl3System.blank().opl3
        assertEquals(Opl3Presets.ALL.map { it.instrument }, sound.instruments.take(Opl3Presets.ALL.size))
        assertTrue(sound.instruments.drop(Opl3Presets.ALL.size).all { it == null })
        assertEquals(emptyList<String>(), Opl3System.errors(Opl3System.blank()))
    }

    @Test
    fun curatedControlsReadBackWhatTheyWrite() {
        for (p in Opl3Presets.ALL) {
            val ins = p.instrument
            val c = Opl3Curated.controls(ins)
            for (i in Opl3Curated.ratios(ins.fourOp).indices) {
                val r = Opl3Curated.withRatio(ins, i)
                assertEquals(i, Opl3Curated.controls(r).ratio)
                assertTrue(Opl3Curated.controls(r).ratioExact)
                // Only MULT changed.
                assertEquals(ins.operators.map { it.copy(mult = 0) }, r.operators.mapIndexed { k, op -> if (k < ins.operatorCount) op.copy(mult = 0) else op.copy(mult = 0) })
            }
            for (i in Opl3Curated.waves(ins.fourOp).indices) assertEquals(i, Opl3Curated.controls(Opl3Curated.withWaves(ins, i)).waves)
            for (v in 0..15) {
                assertEquals(v, Opl3Curated.controls(Opl3Curated.withAttack(ins, v)).attack)
                assertEquals(v, Opl3Curated.controls(Opl3Curated.withRelease(ins, v)).release)
                assertEquals(v, Opl3Curated.controls(Opl3Curated.withSustain(ins, v)).sustain)
                assertEquals(v, Opl3Curated.controls(Opl3Curated.withDecay(ins, v)).decay)
            }
            for (k in Opl3Curated.KEY_SCALES.indices) assertEquals(k, Opl3Curated.controls(Opl3Curated.withKeyScale(ins, k)).keyScale)
            for (k in Opl3Curated.LFOS.indices) assertEquals(k, Opl3Curated.controls(Opl3Curated.withLfo(ins, k)).lfo)
            if (c.brightness >= 0) {
                val dim = Opl3Curated.withBrightness(ins, 10)
                assertEquals(10, Opl3Curated.controls(dim).brightness)
            }
            for (a in Opl3Curated.ALGORITHMS.indices) {
                val changed = Opl3Curated.withAlgorithm(ins, a)
                assertEquals(a, Opl3Curated.controls(changed).algorithm)
                assertNull(changed.problem())
                // Loudness (the lowest carrier level) survives a change of routing.
                assertEquals(c.level, Opl3Curated.controls(changed).level)
            }
        }
    }

    @Test
    fun brightnessShiftsModulatorsTogether() {
        val bell = Opl3Presets.instrument("Bell") // 4-op 1+(2→3)+4: operator 2 is the one modulator
        val organ = Opl3Presets.instrument("Organ") // 4-op 1+(2→3→4): modulators 2 and 3
        val c = Opl3Curated.controls(organ)
        assertEquals(63 - minOf(organ.operators[1].tl, organ.operators[2].tl), c.brightness)
        val brighter = Opl3Curated.withBrightness(organ, c.brightness + 4)
        assertEquals(organ.operators[1].tl - 4, brighter.operators[1].tl)
        assertEquals(organ.operators[2].tl - 4, brighter.operators[2].tl)
        assertEquals(organ.operators[0], brighter.operators[0])
        assertEquals(organ.operators[3], brighter.operators[3])
        assertEquals(63 - bell.operators[1].tl, Opl3Curated.controls(bell).brightness)
        // Additive 2-op has no modulator.
        assertEquals(-1, Opl3Curated.controls(Opl3Presets.instrument("Organ 2-op")).brightness)
    }

    @Test
    fun handEditedGridShowsTheNearestEntryAsApproximate() {
        val lead = Opl3Presets.instrument("Lead")
        val edited = lead.withOperator(0, lead.operators[0].copy(mult = 11)) // the chip plays 10
        val c = Opl3Curated.controls(edited)
        assertFalse(c.ratioExact)
        assertEquals(listOf(10, 1), Opl3Curated.RATIOS_2OP[c.ratio].mults)
        // Stepping from there writes a list entry again.
        assertTrue(Opl3Curated.controls(Opl3Curated.withRatio(edited, c.ratio)).ratioExact)
        // Presets are voiced per operator (the Lead's modulator sustains lower), so the envelope reads approximate…
        assertFalse(Opl3Curated.controls(lead).envelopeExact)
        // …until a macro writes every operator it owns.
        val even = Opl3Curated.withSustain(lead, 13)
        assertTrue(Opl3Curated.controls(even).envelopeExact)
        val odd = even.withOperator(1, even.operators[1].copy(ar = 3))
        assertFalse(Opl3Curated.controls(odd).envelopeExact)
        assertTrue(Opl3Curated.controls(Opl3Curated.withAttack(odd, 15)).envelopeExact)
    }

    // ---- tuning realisation ------------------------------------------------------------------------------

    @Test
    fun fineWritesPxxInAFreeColumn() {
        val fine = Opl3System.fine
        assertEquals(25.0, fine.cents(57, 16), 1e-9)
        val busy = Cell(57, 1, Opl3Rules.FX_VIBRATO, 0x23)
        val tuned = fine.write(busy, 57, 16)!!
        assertEquals(Opl3Rules.FX_FINE, tuned.effectCode(1))
        assertEquals(0x90, tuned.effectParam(1))
        assertEquals(16, fine.of(tuned))
        assertEquals(busy, fine.write(tuned, 57, 0))
        var full = Cell(57, 1)
        for (c in 0 until 4) full = full.withEffect(c, Opl3Rules.FX_VOLUME, 0x30)
        assertNull(fine.write(full, 57, 8))
        assertNull(fine.of(Cell(57, 1, Opl3Rules.FX_FINE, 0x70))) // a downward bend is not a tuning
        assertEquals(16, Opl3System.engine.fine(0, 57, 16))
    }

    // ---- validation --------------------------------------------------------------------------------------

    @Test
    fun validationExplainsWhatTheLoaderRejects() {
        val epiano = Opl3Presets.ALL.indexOfFirst { it.name == "E-piano" } + 1
        fun error(channel: Int, cell: Cell) = Opl3System.errors(blankWith(channel, 0, cell)).joinToString("\n")
        assertTrue(error(6, Cell(48, epiano)).contains("is 4-op; channels FM 7-9 play 2-op instruments only"))
        assertTrue(error(0, Cell(48, 40)).contains("instrument 28 is empty"))
        assertTrue(error(0, Cell(48, 1, Opl3Rules.FX_SHAPE, 0x20)).contains("names chord shape 32; the song has 16"))
        assertTrue(error(0, Cell(48, 1, Opl3Rules.FX_JUMP, 0x01)).contains("jumps past the last order"))
        assertTrue(error(0, Cell(48, 1).withEffect(2, Opl3Rules.FX_VOLUME, 0x40)).contains("volumes are C00-C3F"))
        assertTrue(error(0, Cell(48, 1, Opl3Rules.FX_SPEED, 0)).contains("F00"))
        assertTrue(error(0, Cell(48, 1, Opl3Rules.FX_MULT, 0x51)).contains("operator digit is 1-4"))
        assertTrue(error(0, Cell(48, 1, Opl3Rules.FX_WAVE, 0x18)).contains("waveform 0-7"))
        assertTrue(error(0, Cell(96, 1)).contains("outside C-0..B-7"))
        assertTrue(error(0, Cell(48, 1, 19, 1)).contains("does not exist on the OPL3"))
    }

    @Test
    fun warningsCoverLocksChordsDelaysAndThePool() {
        val epiano = Opl3Presets.ALL.indexOfFirst { it.name == "E-piano" } + 1
        val pad = Opl3Presets.ALL.indexOfFirst { it.name == "Pad" } + 1
        fun warnings(song: Song) = Opl3System.warnings(song).joinToString("\n")
        assertTrue(warnings(blankWith(0, 0, Cell(48, epiano, Opl3Rules.FX_CHORD, 0x47))).contains("chords play only with 2-op instruments"))
        assertTrue(warnings(blankWith(0, 1, Cell(instrument = 0, effect = Opl3Rules.FX_BRIGHTNESS, param = 0x20))).contains("Ixx applies when a note starts"))
        assertTrue(warnings(blankWith(0, 0, Cell(48, pad, Opl3Rules.FX_DELAY, 6))).contains("the row never plays"))
        assertTrue(warnings(blankWith(0, 4, Cell(48))).contains("has no instrument"))
        // Nine channels each sounding a 3-note chord need 18 spare voices; the pool has 9.
        var song = Opl3System.blank()
        for (ch in 0 until 9) song = with(song, 0, ch, 0, Cell(48 + ch, pad, Opl3Rules.FX_SHAPE, 3))
        assertTrue(warnings(song).contains("chords need 27 spare voices but 9 are free"))
    }

    @Test
    fun demoIsCleanAndShowsTheSystem() {
        val demo = Opl3System.demo()
        assertEquals(emptyList<String>(), Opl3System.errors(demo))
        assertEquals(emptyList<String>(), Opl3System.warnings(demo))
        val cells = demo.orders.toSet().flatMap { demo.patterns[it].tracks.flatten() }
        val sound = demo.opl3
        assertTrue(cells.any { it.instrument != 0 && sound.instrument(it.instrument)!!.fourOp })
        assertTrue(cells.any { it.instrument != 0 && sound.instrument(it.instrument)!!.sweep.active })
        assertTrue(cells.any { c -> (0..c.more.size).any { c.effectCode(it) == Opl3Rules.FX_SHAPE } })
        assertTrue(cells.any { c -> (0..c.more.size).any { c.effectCode(it) == Opl3Rules.FX_CHORD } })
        assertTrue(cells.any { c -> (0..c.more.size).any { c.effectCode(it) == Opl3Rules.FX_PAN } })
        assertTrue(cells.any { it.more.size >= 2 })
    }

    // ---- arrangement and VGM -----------------------------------------------------------------------------

    @Test
    fun arrangementRenumbersJumpsInEveryColumn() {
        val base = Opl3System.blank()
        val patterns = List(3) { Songs.blankPattern(base) }
        var song = base.copy(patterns = patterns, orders = listOf(0, 1, 2), parkedOrders = listOf(1))
        song = with(song, 2, 3, 5, Cell(48, 1).withEffect(2, Opl3Rules.FX_JUMP, 2))
        val arranged = Opl3System.arranged(song)
        assertEquals(listOf(0, 2), arranged.orders)
        assertEquals(1, Opl3System.jumpTarget(arranged.patterns[2].tracks[3][5]))
        val loop = Opl3System.patternLoop(song, 2)
        assertEquals(0, Opl3System.jumpTarget(loop.patterns[2].tracks[3][5]))
    }

    @Test
    fun vgmExportCallsTheNativeRenderer() {
        val demo = Opl3System.demo()
        val vgm = Opl3System.exports.single { it.extension == "vgm" }
        assertThrows(IllegalStateException::class.java) { vgm.export(demo) }
        var seen: Triple<ByteArray, Int, Opl3VgmTags>? = null
        Opl3Vgm.renderer = Opl3VgmRenderer { image, maxTicks, tags -> seen = Triple(image, maxTicks, tags); byteArrayOf(1, 2, 3) }
        assertArrayEquals(byteArrayOf(1, 2, 3), vgm.export(demo))
        val (image, maxTicks, tags) = seen!!
        assertArrayEquals(Opl3Lowering.lower(demo), image)
        assertEquals(36000, maxTicks)
        assertEquals(Opl3VgmTags("Glass Harbour", "Thor Tracker"), tags)
    }
}
