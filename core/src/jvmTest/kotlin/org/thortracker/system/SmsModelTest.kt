package org.thortracker.system

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.SmsImageLayout
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.sms.OpllPatch
import org.thortracker.system.sms.SmsExport
import org.thortracker.system.sms.SmsFmInstrument
import org.thortracker.system.sms.SmsLayout
import org.thortracker.system.sms.SmsPresets
import org.thortracker.system.sms.SmsPsgInstrument
import org.thortracker.system.sms.SmsRules
import org.thortracker.system.sms.SmsSystem
import org.thortracker.system.sms.SmsVgmRenderer
import org.thortracker.system.sms.SmsVgmTags
import org.thortracker.system.sms.sms

class SmsModelTest {
    @After
    fun noRenderer() {
        SmsExport.renderer = null
    }

    private fun with(song: Song, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[0]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun slot(layout: SmsLayout, name: String) = SmsPresets.forLayout(layout).indexOfFirst { it.name == name } + 1

    @Test
    fun fourSystemsWithTheirChannelsBanksAndEffects() {
        assertEquals(SmsSystem.PSG, SoundSystems.of("sms-psg"))
        assertEquals(SmsSystem.GG, SoundSystems.of("gg-psg"))
        assertEquals(SmsSystem.FM, SoundSystems.of("sms-fm"))
        assertEquals(SmsSystem.FM_DRUMS, SoundSystems.of("sms-fm-drums"))
        assertEquals(listOf("Square 1", "Square 2", "Square 3", "Noise"), SmsSystem.PSG.channels.map { it.name })
        assertEquals(13, SmsSystem.FM.channels.size)
        assertEquals(listOf("FM 6", "Bass drum", "Snare", "Tom", "Cymbal", "Hi-hat"), SmsSystem.FM_DRUMS.channels.drop(9).map { it.name })
        assertFalse(SmsSystem.PSG.channels[3].tonal)
        assertTrue(SmsSystem.FM.channels[4].tonal)
        assertFalse(SmsSystem.FM_DRUMS.channels[10].tonal)
        assertEquals(listOf("Sounds"), SmsSystem.PSG.banks.map { it.name })
        assertEquals(listOf("PSG sounds", "FM voices", "Drum kits"), SmsSystem.FM_DRUMS.banks.map { it.name })
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2), SmsSystem.FM_DRUMS.channels.map { it.bank })
        val fx = { sys: SmsSystem, ch: Int -> sys.effectsFor(ch).map { it.char }.joinToString("") }
        assertEquals("01234BCDFGLOS", fx(SmsSystem.PSG, 0))
        assertEquals("012348BCDFGLOS", fx(SmsSystem.GG, 0))
        assertEquals("01234BCDFGLNOS", fx(SmsSystem.PSG, 3))
        assertEquals("01234BCDFGLOS", fx(SmsSystem.FM, 4))
        assertEquals("BCDFGOS", fx(SmsSystem.FM_DRUMS, 10))
        assertEquals(33..107, SmsSystem.PSG.noteRange)
        assertEquals(0..107, SmsSystem.FM.noteRange)
        assertEquals(59.92, SmsSystem.GG.tickHz, 0.005)
        assertEquals(listOf("asm", "s", "vgm"), SmsSystem.FM.exports.map { it.extension })
        assertEquals(4, SmsSystem.FM.effectColumns(12))
        assertEquals("A-4", SmsSystem.PSG.noteName(57))
        assertEquals(0, SmsSystem.PSG.fine.max)
    }

    @Test
    fun engineMapsNotesAndRefusesWhatBanjoCannotPlay() {
        val song = SmsSystem.FM.blank()
        val e = SmsSystem.FM.engine
        assertEquals(SmsImageLayout.ENGINE_ID, e.id)
        assertEquals(SmsImageLayout.IMAGE_SIZE, e.imageSize)
        assertEquals(33, e.note(song, 0, 57, 1))
        assertEquals(0, e.note(song, 0, 20, 1))
        assertEquals(57, e.note(song, 4, 57, 1))
        val psg = slot(SmsLayout.FM, "Square lead")
        val fm = slot(SmsLayout.FM, "Piano")
        assertNull(e.auditionProblem(song, 0, 57, psg, 0))
        assertNull(e.auditionProblem(song, 4, 57, fm, 0))
        assertTrue(e.auditionProblem(song, 0, 57, psg, 3)!!.contains("fine pitch"))
        assertTrue(e.auditionProblem(song, 0, 57, fm, 0)!!.contains("FM voice"))
        assertTrue(e.auditionProblem(song, 4, 100, fm, 0)!!.contains("B-7"))
        assertTrue(e.auditionProblem(song, 4, 57, 60, 0)!!.contains("empty"))
        assertEquals(fm - 2, SmsSystem.FM.defaultInstrument(song, 1)) // Violin, the first FM voice
        assertEquals(1, SmsSystem.FM.defaultInstrument(song, 0))
    }

    @Test
    fun projectsRoundTripPerLayoutAndKeepTheirLayout() {
        for (sys in SmsSystem.ALL) {
            val demo = sys.demo()
            val text = ProjectCodec.encode(demo)
            assertTrue(text.contains("\"backend\":\"${sys.id}\""))
            val back = ProjectCodec.decode(text)
            assertEquals(demo, back)
            assertEquals(sys, back.system)
        }
        // A song of one layout is not another's.
        assertTrue(SmsSystem.PSG.structuralErrors(SmsSystem.FM.blank()).single().contains("not the"))
        // Instrument kinds are stored with their type.
        val text = ProjectCodec.encode(SmsSystem.FM_DRUMS.blank())
        assertTrue(text.contains("\"type\":\"psg\"") && text.contains("\"type\":\"fm\"") && text.contains("\"type\":\"kit\""))
    }

    @Test
    fun opllPatchesRoundTripTheirRegisters() {
        for (p in 1..15) {
            val patch = OpllPatch.rom(p)
            assertArrayEquals(patch.registers(), OpllPatch.ofRegisters(patch.registers()).registers())
        }
        assertArrayEquals(intArrayOf(0x71, 0x61, 0x1E, 0x17, 0xD0, 0x78, 0x00, 0x17), OpllPatch.rom(1).registers())
        assertEquals(16, OpllPatch.PATCH_NAMES.size)
    }

    @Test
    fun instrumentChecks() {
        val both = SmsPsgInstrument("x", pitch = NesEnvelope(listOf(1)), noise = NesEnvelope(listOf(1)))
        assertTrue(both.problem()!!.contains("one extra envelope"))
        val long = SmsPsgInstrument("x", volume = NesEnvelope(List(252) { 15 }))
        assertTrue(long.problem()!!.contains("at most 251"))
        assertTrue(SmsFmInstrument("x", patch = 16).problem()!!.contains("patch"))
        assertNull(SmsPresets.ALL.firstNotNullOfOrNull { it.instrument.problem() })
    }

    @Test
    fun validationNamesBanjosLimits() {
        val psg = SmsSystem.PSG.blank()
        val lead = slot(SmsLayout.PSG, "Square lead")
        fun errs(s: Song) = s.system.errors(s).joinToString(" | ")
        assertTrue(errs(with(psg, 0, 0, Cell(57, lead, SmsRules.FX_DELAY, 6))).contains("fall a row behind"))
        assertTrue(errs(with(psg, 0, 0, Cell(57, lead, SmsRules.FX_DELAY, 5))).isEmpty())
        assertTrue(errs(with(psg, 0, 0, Cell(effect = SmsRules.FX_BREAK, param = 1))).contains("D00"))
        assertTrue(errs(with(psg, 0, 0, Cell(effect = SmsRules.FX_NOISE, param = 0x11))).contains("does not play on Square 1"))
        assertTrue(errs(with(psg, 0, 0, Cell(effect = SmsRules.FX_PAN, param = 0x11))).contains("does not exist"))
        assertTrue(errs(with(psg, 0, 0, Cell(effect = SmsRules.FX_VOLUME, param = 0x10))).contains("C00-C0F"))
        assertTrue(errs(with(psg, 0, 0, Cell(effect = SmsRules.FX_LEGATO, param = 2))).contains("L01"))
        assertTrue(errs(with(psg, 0, 0, Cell(57, 63))).contains("empty"))
        val fm = SmsSystem.FM.blank()
        val piano = slot(SmsLayout.FM, "Piano")
        assertTrue(errs(with(fm, 4, 0, Cell(100, piano))).contains("B-7"))
        assertTrue(errs(with(fm, 0, 0, Cell(57, piano))).contains("FM voice"))
        assertTrue(errs(with(fm, 4, 0, Cell(57, slot(SmsLayout.FM, "Square lead")))).contains("PSG sound"))
        // Warnings: two custom voices at once, PSG notes below A-2, two pitch effects on a row.
        val bell = slot(SmsLayout.FM, "FM bell")
        val square = slot(SmsLayout.FM, "FM square lead")
        val two = with(with(fm, 4, 0, Cell(57, square)), 5, 4, Cell(57, bell))
        assertTrue(SmsSystem.FM.warnings(two).joinToString().contains("one custom voice"))
        assertTrue(SmsSystem.FM.warnings(with(fm, 0, 0, Cell(24, slot(SmsLayout.FM, "Square lead")))).joinToString().contains("A-2"))
        val pitches = Cell(57, piano, SmsRules.FX_ARPEGGIO, 0x37).withEffect(1, SmsRules.FX_VIBRATO, 0x44)
        assertTrue(SmsSystem.FM.warnings(with(fm, 4, 0, pitches)).joinToString().contains("last column wins"))
        assertTrue(SmsRules.detail(with(fm, 4, 0, pitches), 0, 4, 0, 0)!!.contains("replaced"))
    }

    @Test
    fun demosAreCleanOnEveryLayout() {
        for (sys in SmsSystem.ALL) {
            val demo = sys.demo()
            assertTrue("${sys.id}: ${sys.errors(demo)}", sys.errors(demo).isEmpty())
            assertTrue("${sys.id}: ${sys.warnings(demo)}", sys.warnings(demo).isEmpty())
            val image = ByteArray(sys.engine.imageSize)
            sys.engine.lower(demo, image)
            assertEquals(sys.layout.code, image[SmsImageLayout.M_LAYOUT].toInt())
        }
        // The Game Gear demo pans; the Master System one does not.
        val gg = SmsSystem.GG.demo()
        assertTrue(gg.patterns.any { p -> p.tracks.any { t -> t.any { c -> (0..c.more.size).any { c.effectCode(it) == SmsRules.FX_PAN } } } })
        assertTrue(SmsSystem.FM_DRUMS.demo().patterns[1].tracks[10].any { it.note >= 0 })
    }

    @Test
    fun arrangementRenumbersJumpsAroundParkedOrders() {
        val blank = SmsSystem.PSG.blank()
        val p = blank.patterns[0]
        val jump = p.copy(tracks = p.tracks.mapIndexed { c, t -> if (c == 0) t.toMutableList().also { it[3] = Cell(effect = SmsRules.FX_JUMP, param = 2) } else t })
        val s = blank.copy(patterns = listOf(p, jump), orders = listOf(0, 0, 0, 1), parkedOrders = listOf(1))
        val a = SmsSystem.PSG.arranged(s)
        assertEquals(listOf(0, 0, 1), a.orders)
        assertEquals(1, SmsSystem.PSG.jumpTarget(a.patterns[1].tracks[0][3]))
    }

    @Test
    fun vgmGoesThroughTheRegisteredRenderer() {
        val song = SmsSystem.GG.demo()
        assertThrows(IllegalStateException::class.java) { SmsExport.export(song, SmsExport.Format.VGM) }
        var seen: SmsVgmTags? = null
        var size = 0
        SmsExport.renderer = SmsVgmRenderer { image, maxTicks, tags ->
            seen = tags
            size = image.size
            assertEquals(SmsExport.MAX_TICKS, maxTicks)
            byteArrayOf(1, 2, 3)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), SmsExport.export(song, SmsExport.Format.VGM))
        assertEquals("Harbour Lights", seen?.title)
        assertEquals(SmsImageLayout.IMAGE_SIZE, size)
        assertTrue(SmsExport.export(song, SmsExport.Format.WLA_DX).decodeToString().contains("banjo_init_sn"))
    }

    @Test
    fun newSongsHoldTheirLayoutsPresets() {
        assertEquals(12, SmsSystem.PSG.blank().sms.instruments.count { it != null })
        assertEquals(32, SmsSystem.FM.blank().sms.instruments.count { it != null })
        assertEquals(35, SmsSystem.FM_DRUMS.blank().sms.instruments.count { it != null })
        assertEquals(SmsSystem.SLOTS, SmsSystem.GG.blank().sms.instruments.size)
    }
}
