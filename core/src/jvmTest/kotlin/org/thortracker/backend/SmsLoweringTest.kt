package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.sms.SmsDrumKit
import org.thortracker.system.sms.SmsLayout
import org.thortracker.system.sms.SmsPresets
import org.thortracker.system.sms.SmsRules
import org.thortracker.system.sms.SmsSystem
import org.thortracker.system.sms.sms

/**
 * The Banjo song data the Master System lowering writes, byte for byte as the driver's sources read it (process_new_line.inc,
 * the macro and instrument routines): header, macros (loop points as the driver reads them), instrument records, track
 * commands for every effect, OFF as release or note off, the order jumps for short orders and the loop, and the image meta.
 */
class SmsLoweringTest {
    private fun ints(vararg v: Int) = v

    private fun song(system: SmsSystem, length: Int = Songs.ROWS, speed: Int = 6, edit: (Array<Array<Cell>>) -> Unit = {}): Song {
        val blank = system.blank()
        val tracks = Array(system.channels.size) { Array(Songs.ROWS) { Cell.BLANK } }
        edit(tracks)
        return blank.copy(ticksPerRow = speed, patterns = listOf(Pattern("P", tracks.map { it.toList() }, length = length)))
    }

    private fun slot(layout: SmsLayout, name: String) = SmsPresets.forLayout(layout).indexOfFirst { it.name == name } + 1

    @Test
    fun macrosAreEncodedAsTheDriverReadsThem() {
        // Volume: [sustain length, sustain loop, release length, release loop] + attenuations; positions count the header.
        assertArrayEquals(ints(12, 10, 17, 0, 0, 1, 2, 3, 4, 4, 5, 5, 7, 9, 11, 13, 15),
            SmsLowering.volumeMacro(NesEnvelope(listOf(15, 14, 13, 12, 11, 11, 10, 10, 8, 6, 4, 2, 0), 6, 8)))
        assertArrayEquals(ints(7, 5, 7, 5, 0, 5, 10), SmsLowering.volumeMacro(NesEnvelope(listOf(15, 10, 5), 1)))
        assertArrayEquals(ints(7, 0, 7, 0, 0, 5, 10), SmsLowering.volumeMacro(NesEnvelope(listOf(15, 10, 5))))
        // Arpeggio and extra: [length, loop index + 2] (music_update_arp_macro reads the loop position's own byte).
        assertArrayEquals(ints(8, 2, 0, 0, 4, 4, 7, 7), SmsLowering.arpeggioMacro(NesEnvelope(listOf(0, 0, 4, 4, 7, 7), 0)))
        assertArrayEquals(ints(4, 0, 0x7F, 0x3F), SmsLowering.arpeggioMacro(NesEnvelope(listOf(-1, 63))))
        assertArrayEquals(ints(5, 3, 0, 16, 0xF0), SmsLowering.pitchMacro(NesEnvelope(listOf(0, 8, -8), 1)))
        // Noise mode: bit 7 marks a change.
        assertArrayEquals(ints(5, 0, 0x81, 0x01, 0x83), SmsLowering.noiseMacro(NesEnvelope(listOf(1, 1, 3))))
        // Fixed kit: F-number low, block << 1 | F-number bit 8 (Banjo's table).
        assertArrayEquals(ints(0x22, 0x05, 0xAC, 0x06, 0xE6, 0x02),
            SmsLowering.kitPitches(SmsDrumKit("k", fixed = true, kick = 33, snareHat = 36, tomCymbal = 17)))
    }

    @Test
    fun aPsgNoteLowersToHeaderInstrumentAndTrack() {
        val lead = slot(SmsLayout.PSG, "Square lead")
        val s = song(SmsSystem.PSG, length = 4, speed = 3) { it[0][0] = Cell(57, lead) }
        val b = SmsLowering.build(s)
        assertEquals(4, b.patternLength)
        assertEquals(listOf(listOf(0x02, lead - 1, 0x0B, 0xA1, 0x43), listOf(0x43)), b.tracks.map { it.toList() })
        assertEquals(listOf(0, 1, 1, 1), b.orders.single().toList())
        assertEquals(SmsPresets.forLayout(SmsLayout.PSG).size, b.instruments.size)
        val ins = b.instruments[lead - 1]
        assertEquals(0x88, ins.flags) // volume macro + extra (pitch)
        assertEquals(0x01, ins.exType)
        val region = b.region().map { it.toInt() and 0xFF }
        assertEquals(listOf(0xBA, 0, 4, 2, 0, 0, 1, 0xFF, 0xFF, 1, 3, 3, 4, 1), region.take(14))
        assertEquals(listOf(0x2C, 0x40, 0x22, 0x40), region.subList(14, 18)) // instruments at $402C, order table at $4022
        assertEquals(listOf(4, 4, 0, 0xFF, 0, 0xFF), region.subList(18, 24)) // tic = speed + 1, line, order, jump, noise, pan
        assertTrue(region.subList(24, 34).all { it == 0 }) // the call fields the machine fills
        assertEquals(listOf(0x24, 0x40), region.subList(34, 36)) // order 0's row at $4024
    }

    @Test
    fun everyEffectLowersToItsCommand() {
        val lead = slot(SmsLayout.PSG, "Square lead")
        val s = song(SmsSystem.PSG, length = 9) {
            it[0][0] = Cell(57, lead, SmsRules.FX_VIBRATO, 0x63)
            it[0][1] = Cell(effect = SmsRules.FX_SPEED, param = 5)
            it[0][2] = Cell(60, 0, SmsRules.FX_DELAY, 2)
            it[0][3] = Cell(Cell.CUT)
            it[0][4] = Cell(57, 0, SmsRules.FX_ARPEGGIO, 0x37)
            it[0][5] = Cell(59)
            it[0][6] = Cell(effect = SmsRules.FX_VOLUME, param = 0x0A).withEffect(1, SmsRules.FX_CUT, 3)
            it[0][7] = Cell(effect = SmsRules.FX_LEGATO, param = 1)
            it[0][8] = Cell(effect = SmsRules.FX_JUMP, param = 0)
        }
        assertTrue(SmsSystem.PSG.errors(s).toString(), SmsSystem.PSG.errors(s).isEmpty())
        val track = SmsLowering.build(s).tracks.first().toList()
        assertEquals(listOf(
            0x02, lead - 1, 0x0C, 0x36, 0xA1, 0x40, // instrument, vibrato (depth 3 << 4 | speed 6), A-4
            0x1A, 0x05, 0x1B, 0x05, 0x40,           // both speeds
            0x1D, 0x02, 0xA4, 0x40,                 // the delay first, then C-5 (the vibrato stopped no arpeggio)
            0x1E, 0x40,                             // OFF: Square lead has a release part
            0x0A, 0x37, 0xA1, 0x40,                 // arpeggio
            0x0B, 0xA3, 0x40,                       // the next note without 0xy plays plain
            0x2A, 0x1D, 0x03, 0x1E, 0x40,           // volume, then the cut at tick 3
            0x0E, 0x40,                             // legato on
            0x19, 0x00, 0x40,                       // the row's own jump: no extra one at the order's end
        ), track)
        assertEquals(10, SmsLowering.build(s).patternLength) // 9 rows, rounded up to even
    }

    @Test
    fun offWithoutReleaseIsANoteOffAndOddSpeedSetsSpeed2() {
        val pluck = slot(SmsLayout.PSG, "Square pluck")
        val s = song(SmsSystem.PSG, length = 2) {
            it[1][0] = Cell(57, pluck, SmsRules.FX_ODD_SPEED, 3)
            it[1][1] = Cell(Cell.CUT)
        }
        assertEquals(listOf(0x1B, 3, 0x02, pluck - 1, 0x0B, 0xA1, 0x40, 0x01, 0x40), SmsLowering.build(s).tracks[1].toList())
    }

    @Test
    fun gameGearPanAndNoiseModes() {
        val s = song(SmsSystem.GG) {
            it[1][0] = Cell(effect = SmsRules.FX_PAN, param = 0x10)
            it[3][0] = Cell(effect = SmsRules.FX_NOISE, param = 0x10)
            it[3][1] = Cell(effect = SmsRules.FX_NOISE, param = 0x01)
        }
        val b = SmsLowering.build(s)
        assertEquals(listOf(0x10, 0x20, 0xDD, 0x7F), b.tracks[b.orders[0][1]].toList())
        assertEquals(listOf(0x05, 0x01, 0x40, 0x05, 0x02, 0x7E), b.tracks[b.orders[0][3]].toList())
    }

    @Test
    fun shortOrdersAndTheLoopJumpExplicitly() {
        val sys = SmsSystem.PSG
        val blank = sys.blank()
        val short = Pattern.blank(4, Songs.ROWS, "short").copy(length = 16)
        val long = Pattern.blank(4, Songs.ROWS, "long")
        val s = blank.copy(patterns = listOf(short, long), orders = listOf(0, 1), loopOrder = 1)
        val b = SmsLowering.build(s)
        assertEquals(64, b.patternLength)
        assertEquals(listOf(0x4E, 0x19, 0x01, 0x40), b.tracks[b.orders[0][0]].toList()) // 16 rows then on to order 1
        assertEquals(listOf(0x7F - 1, 0x19, 0x01, 0x40), b.tracks[b.orders[1][0]].toList()) // the last order loops to 1
        assertEquals(listOf(0x4F), b.tracks[b.orders[0][1]].toList())
        // Looping to order 0 with full-length orders needs no jump: the driver wraps by itself.
        val wrap = blank.copy(patterns = listOf(long), orders = listOf(0, 0), loopOrder = 0)
        assertEquals(listOf(0x7F), SmsLowering.build(wrap).tracks.single().toList())
    }

    @Test
    fun fmVoicesCustomPatchesAndKits() {
        val layout = SmsLayout.FM_DRUMS
        val piano = slot(layout, "Piano")
        val custom = slot(layout, "FM bell")
        val kit = slot(layout, "Standard kit")
        val s = song(SmsSystem.FM_DRUMS) {
            it[4][0] = Cell(48, piano)
            it[5][0] = Cell(48, custom)
            it[10][0] = Cell(33, kit)
        }
        val b = SmsLowering.build(s)
        assertEquals(0x30, b.instruments[piano - 1].fmPreset)
        assertEquals(-1, b.instruments[piano - 1].patch)
        val c = b.instruments[custom - 1]
        assertEquals(0x00, c.fmPreset)
        val bell = SmsPresets.instrument("FM bell") as org.thortracker.system.sms.SmsFmInstrument
        assertArrayEquals(bell.custom.registers(), b.patches[c.patch])
        val k = b.instruments[kit - 1]
        assertEquals(0xFF, k.fmPreset)
        assertEquals(0, k.flags)
        assertEquals(listOf(0x02, piano - 1, 0x0B, 0x80 or 48, 0x7F), b.tracks[b.orders[0][4]].toList())
        // Rhythm channels load kits with FM_DRUM and take the absolute note; no arpeggio-off on drums.
        assertEquals(listOf(0x04, kit - 1, 0x80 or 33, 0x7F), b.tracks[b.orders[0][10]].toList())
        assertEquals(3, s.sms.layout.hasChips)
    }

    @Test
    fun theImageCarriesMetaAndTheRegion() {
        val s = SmsSystem.FM.demo()
        val image = SmsLowering.lower(s)
        assertEquals("TTSB", image.copyOfRange(0, 4).decodeToString())
        assertEquals(SmsImageLayout.VERSION, image[SmsImageLayout.M_VERSION].toInt())
        assertEquals(SmsLayout.FM.code, image[SmsImageLayout.M_LAYOUT].toInt())
        assertEquals(13, image[SmsImageLayout.M_CHANNELS].toInt())
        assertEquals(s.orders.size, image[SmsImageLayout.M_ORDERS].toInt())
        assertEquals(s.loopOrder, image[SmsImageLayout.M_LOOP].toInt())
        val b = SmsLowering.build(s)
        val region = b.region()
        assertEquals(region.size, (image[SmsImageLayout.M_DATA_SIZE].toInt() and 0xFF) or ((image[SmsImageLayout.M_DATA_SIZE + 1].toInt() and 0xFF) shl 8))
        assertArrayEquals(region, image.copyOfRange(SmsImageLayout.META_SIZE, SmsImageLayout.META_SIZE + region.size))
        for (o in s.orders.indices) {
            assertEquals(s.patterns[s.orders[o]].length, image[SmsImageLayout.M_ROWS + o].toInt())
            assertEquals(s.ticksPerRow, image[SmsImageLayout.M_SPEED1 + o].toInt())
        }
        assertEquals(SmsImageLayout.IMAGE_SIZE, image.size)
    }

}
