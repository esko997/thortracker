package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.OpnPlayerImage.INSTRUMENT_SIZE
import org.thortracker.backend.OpnPlayerImage.MAX_CHANNELS
import org.thortracker.backend.OpnPlayerImage.OFF_ADPCM
import org.thortracker.backend.OpnPlayerImage.OFF_DATA
import org.thortracker.backend.OpnPlayerImage.OFF_INSTRUMENTS
import org.thortracker.backend.OpnPlayerImage.OFF_ORDERS
import org.thortracker.backend.OpnPlayerImage.OFF_ROWS
import org.thortracker.backend.OpnPlayerImage.OFF_SAMPLES
import org.thortracker.backend.OpnPlayerImage.SAMPLE_SIZE
import org.thortracker.model.Cell
import org.thortracker.model.Fx
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.opn.AdpcmBytes
import org.thortracker.system.opn.OpnAdpcmSound
import org.thortracker.system.opn.OpnChip
import org.thortracker.system.opn.OpnDemoSong
import org.thortracker.system.opn.OpnFm3Mode
import org.thortracker.system.opn.OpnFmSound
import org.thortracker.system.opn.OpnInstrument
import org.thortracker.system.opn.OpnRhythmSound
import org.thortracker.system.opn.OpnRules
import org.thortracker.system.opn.OpnSound
import org.thortracker.system.opn.OpnSsgSound
import org.thortracker.system.opn.OpnSystem
import org.thortracker.system.opn.OpnValidator
import org.thortracker.system.opn.OpnaSample
import org.thortracker.system.opn.OpnaSystem
import org.thortracker.system.opn.opn
import org.thortracker.system.opn.system
import org.thortracker.system.ym2612.YmOperator
import org.thortracker.system.ym2612.YmSweep

/**
 * The OPN / OPNA image layout as `opn/opn_image.h` defines it, byte for byte: header, rows and order tables, the
 * 48-byte sound records, pattern encoding, shared data, ADPCM-B placement and the limits. Tests never install the
 * rhythm ROM ([Ym2608RhythmRom.markInstalled] is one-way for the process).
 */
class OpnLoweringTest {
    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF
    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)
    private fun u32(image: ByteArray, at: Int) = u16(image, at) or (u16(image, at + 2) shl 16)
    private fun s16(image: ByteArray, at: Int) = u16(image, at).toShort().toInt()

    private fun with(song: Song, pattern: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.patterns[pattern]
        val tracks = p.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[pattern] = p.copy(tracks = tracks) })
    }

    /** [song] with exactly [sounds] in slots 1.. and [samples]. */
    private fun sounds(song: Song, sounds: List<OpnInstrument>, samples: List<OpnaSample> = song.opn.samples) =
        song.copy(sound = song.opn.copy(instruments = List(OpnSound.SLOTS) { sounds.getOrNull(it) }, samples = samples))

    private fun order(image: ByteArray, o: Int, c: Int) = u16(image, OFF_ORDERS + 2 * (MAX_CHANNELS * o + c))

    // ---- header and tables --------------------------------------------------------------------------------------

    @Test
    fun headerAndOrderTablesFollowTheLayout() {
        var song = OpnaSystem.blank()
        song = song.copy(patterns = song.patterns + Pattern.blank(16, Songs.ROWS, "Short").copy(length = 32))
        song = with(song, 0, 0, 0, Cell(57, 1))
        song = song.copy(
            ticksPerRow = 3, orders = listOf(0, 1, 0, 1), loopOrder = 1, parkedOrders = listOf(3),
            sound = song.opn.copy(timerB = 200, timerA = 300, fm3Mode = OpnFm3Mode.CSM, lfo = 0x0A, rhythmLevel = 50),
        )
        val image = OpnLowering.lower(song)
        assertEquals(OpnPlayerImage.OPNA_IMAGE_SIZE, image.size)
        assertEquals("TTOP", String(image.copyOf(4).map { it.toInt().toChar() }.toCharArray()))
        assertEquals(1, u8(image, OpnPlayerImage.H_LAYOUT))
        assertEquals(OpnPlayerImage.CHIP_YM2608, u8(image, OpnPlayerImage.H_CHIP))
        assertEquals(3, u8(image, OpnPlayerImage.H_SPEED))
        assertEquals(0x0A, u8(image, OpnPlayerImage.H_LFO))
        assertEquals(3, u16(image, OpnPlayerImage.H_ORDERS)) // the parked fourth order is left out
        assertEquals(1, u16(image, OpnPlayerImage.H_LOOP))
        assertEquals(song.opn.instruments.indexOfLast { it != null } + 1, u8(image, OpnPlayerImage.H_INSTRUMENTS))
        assertEquals(OpnPlayerImage.FM3_CSM, u8(image, OpnPlayerImage.H_FM3_MODE))
        assertEquals(300, u16(image, OpnPlayerImage.H_TIMER_A))
        assertEquals(200, u8(image, OpnPlayerImage.H_TIMER_B))
        assertEquals(50, u8(image, OpnPlayerImage.H_RHYTHM_LEVEL))
        assertEquals(song.opn.samples.size, u8(image, OpnPlayerImage.H_SAMPLES))
        assertEquals(song.opn.adpcmUsed, u32(image, OpnPlayerImage.H_ADPCM_SIZE))
        assertEquals(OpnLowering.dataSize(song), u16(image, OpnPlayerImage.H_DATA_SIZE))
        for (at in (21..23) + (28 until 64)) assertEquals("header byte $at", 0, u8(image, at))
        // Rows per order (64 → 64), the unused orders 0.
        assertEquals(listOf(64, 32, 64, 0), (0 until 4).map { u8(image, OFF_ROWS + it) })
        // Orders 0 and 2 play the same pattern: the same data; FM 1 differs from the blank channels.
        for (c in 0 until 16) assertEquals(order(image, 0, c), order(image, 2, c))
        assertNotEquals(order(image, 0, 0), order(image, 0, 1))
        assertTrue((1 until 16).all { order(image, 0, it) == order(image, 0, 1) })
        assertTrue((0 until 16).all { order(image, 3, it) == 0 })
    }

    @Test
    fun ym2203ImagesHaveNoYm2608Part() {
        val song = OpnSystem.blank().let { it.copy(sound = it.opn.copy(rhythmLevel = 63)) }
        val image = OpnLowering.lower(song)
        assertEquals(OpnPlayerImage.IMAGE_SIZE, image.size)
        assertEquals(OpnPlayerImage.CHIP_YM2203, u8(image, OpnPlayerImage.H_CHIP))
        assertEquals(0, u8(image, OpnPlayerImage.H_LFO))
        assertEquals(0, u8(image, OpnPlayerImage.H_RHYTHM_LEVEL))
        assertEquals(0, u8(image, OpnPlayerImage.H_SAMPLES))
        assertEquals(0, u32(image, OpnPlayerImage.H_ADPCM_SIZE))
        assertEquals(OpnPlayerImage.TIMER_B_DEFAULT, u8(image, OpnPlayerImage.H_TIMER_B))
        // Channels past the YM2203's six have no pattern.
        val blankRun = order(image, 0, 0)
        assertTrue((6 until 16).all { order(image, 0, it) == 0 })
        assertEquals(0xBF, u8(image, OFF_DATA + blankRun))
        // The playback engine writes the same image into a buffer of its size.
        val out = ByteArray(OpnSystem.engine.imageSize) { 0x55 }
        OpnSystem.engine.lower(song, out)
        assertArrayEquals(image, out)
    }

    // ---- sound records ----------------------------------------------------------------------------------------

    @Test
    fun soundRecordsHoldEveryKindsFields() {
        val fm = OpnFmSound("FM", algorithm = 4, feedback = 6, ams = 2, fms = 3,
            operators = listOf(YmOperator(dt = 3, mul = 5, tl = 33, ks = 2, ar = 29, am = true, d1r = 7, d2r = 4, sl = 9, rr = 11, ssgEg = 10)) +
                List(3) { YmOperator() },
            ch3 = listOf(0, 256, -448, 3072), sweep = YmSweep(12, -24, 40))
        val volume = NesEnvelope(listOf(15, 10, 5), loop = 1, release = 2)
        val ssg = OpnSsgSound("SSG", volume = volume, arpeggio = NesEnvelope(listOf(0, 12)), pitchRelative = true,
            mixer = NesEnvelope(listOf(2, 1)), envelope = S5bEnvelope(S5bShape.TRIANGLE, autoPitch = false, octave = -3, period = 0x1234))
        val rhythm = OpnRhythmSound("Rim", level = 21, left = true, right = false)
        val adpcm = OpnAdpcmSound("Pad", sample = 1, root = 60, level = 200, left = false, right = true, loop = true, sweep = YmSweep(0, 12, 4))
        val twin = OpnSsgSound("Twin", volume = volume)
        val noSweep = OpnFmSound("Flat", sweep = YmSweep(5, 6, 0))
        val song = sounds(OpnaSystem.blank(), listOf(fm, ssg, rhythm, adpcm, twin, noSweep))
        val image = OpnLowering.lower(song)
        assertEquals(6, u8(image, OpnPlayerImage.H_INSTRUMENTS))
        fun record(slot: Int) = image.copyOfRange(OFF_INSTRUMENTS + INSTRUMENT_SIZE * (slot - 1), OFF_INSTRUMENTS + INSTRUMENT_SIZE * slot)

        val f = record(1)
        assertEquals(0x80, u8(f, 0))
        assertEquals(4, u8(f, 1))
        assertEquals(6, u8(f, 2))
        assertEquals(0x23, u8(f, 3))
        assertEquals(listOf(0x35, 33, 0x9D, 0x87, 4, 0x9B, 10), (4 until 11).map { u8(f, it) })
        assertEquals(listOf(0x01, 0, 0x1F, 0, 0, 0x0F, 0), (11 until 18).map { u8(f, it) })
        assertEquals(listOf(0, 256, -448, 3072), (0 until 4).map { s16(f, 32 + 2 * it) })
        assertEquals(listOf(12, -24, 40), listOf(f[40].toInt(), f[41].toInt(), u8(f, 42)))
        assertTrue((43 until 48).all { f[it] == 0.toByte() })

        val s = record(2)
        assertEquals(0x81, u8(s, 0))
        assertEquals(OpnPlayerImage.SSG_PITCH_RELATIVE, u8(s, 1))
        assertEquals(0x0A, u8(s, 2))
        assertEquals(0, u8(s, 3))
        assertEquals(-3, s[4].toInt())
        assertEquals(0x1234, u16(s, 5))
        assertEquals(0, u8(s, 7))
        assertEquals(OpnPlayerImage.NO_ENVELOPE, u16(s, OpnPlayerImage.I_ENV_PITCH))
        assertEquals(OpnPlayerImage.NO_ENVELOPE, u16(s, OpnPlayerImage.I_ENV_NOISE))
        assertTrue((18 until 48).all { s[it] == 0.toByte() })
        // Envelopes follow the patterns in the data region: length, loop, release, values.
        val vol = u16(s, OpnPlayerImage.I_ENV_VOLUME)
        assertEquals(listOf(3, 0, 1, 0, 2, 0, 15, 10, 5), (0 until 9).map { u8(image, OFF_DATA + vol + it) })
        val arp = u16(s, OpnPlayerImage.I_ENV_ARPEGGIO)
        assertEquals(listOf(2, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0, 12), (0 until 8).map { u8(image, OFF_DATA + arp + it) })
        val mixer = u16(s, OpnPlayerImage.I_ENV_MIXER)
        assertEquals(listOf(2, 1), (6 until 8).map { u8(image, OFF_DATA + mixer + it) })
        // An identical envelope is stored once.
        assertEquals(vol, u16(record(5), OpnPlayerImage.I_ENV_VOLUME))
        assertEquals(OpnPlayerImage.NO_ENVELOPE, u16(record(5), OpnPlayerImage.I_ENV_ARPEGGIO))

        val r = record(3)
        assertEquals(listOf(0x82, 21, 0x80), (0 until 3).map { u8(r, it) })
        assertTrue((3 until 48).all { r[it] == 0.toByte() })

        val a = record(4)
        assertEquals(listOf(0x83, 0, 0x40, 200, 1, 60, OpnPlayerImage.ADPCM_LOOP), (0 until 7).map { u8(a, it) })
        assertTrue((7 until 40).all { a[it] == 0.toByte() })
        assertEquals(listOf(0, 12, 4), (40 until 43).map { u8(a, it) })

        // Without a sweep speed the sweep bytes stay 0.
        assertTrue((40 until 48).all { record(6)[it] == 0.toByte() })
        // Empty slots past the highest are all 0.
        assertTrue(record(7).all { it == 0.toByte() })
    }

    // ---- patterns -----------------------------------------------------------------------------------------------

    @Test
    fun patternsAreEncodedAsThePlayerReadsThem() {
        val track = MutableList(Songs.ROWS) { Cell.BLANK }
        track[0] = Cell(57, 1, OpnRules.FX_VOLUME, 0x40, listOf(Fx.BLANK, Fx(OpnRules.FX_FINE, 0x90)))
        track[4] = Cell(Cell.CUT)
        assertArrayEquals(
            byteArrayOf(0x0B, 57, 1, 12, 0x40, 25, 0x90.toByte(), 0x82.toByte(), 0x01, 0xFE.toByte(), 0xBA.toByte()),
            OpnLowering.encode(track, Songs.ROWS),
        )
        // Only the played rows: a pattern of length 5 ends after the OFF.
        assertArrayEquals(byteArrayOf(0x0B, 57, 1, 12, 0x40, 25, 0x90.toByte(), 0x82.toByte(), 0x01, 0xFE.toByte()), OpnLowering.encode(track, 5))
        assertArrayEquals(byteArrayOf(0xBF.toByte()), OpnLowering.encode(List(Songs.ROWS) { Cell.BLANK }, Songs.ROWS))
        // An instrument without a note, and a run of exactly 128 never happens within 64 rows.
        assertArrayEquals(byteArrayOf(0x02, 7, 0x80.toByte()), OpnLowering.encode(listOf(Cell(instrument = 7), Cell.BLANK), 2))
    }

    // ---- ADPCM-B memory ---------------------------------------------------------------------------------------------

    @Test
    fun samplesArePlacedInOrderAtThirtyTwoByteBoundaries() {
        val samples = listOf(
            OpnaSample("A", 8000, AdpcmBytes(ByteArray(33) { (it + 1).toByte() })),
            OpnaSample("B", 16000, AdpcmBytes(ByteArray(64) { 0x77 })),
            OpnaSample("C", 55466, AdpcmBytes(byteArrayOf(0x5A))),
        )
        val song = sounds(OpnaSystem.blank(), listOf(OpnFmSound("FM"), OpnAdpcmSound("C", sample = 2)), samples)
        assertEquals(emptyList<String>(), OpnaSystem.errors(song))
        val image = OpnLowering.lower(song)
        assertEquals(3, u8(image, OpnPlayerImage.H_SAMPLES))
        assertEquals(160, u32(image, OpnPlayerImage.H_ADPCM_SIZE))
        fun entry(k: Int) = (0 until 3).map { u32(image, OFF_SAMPLES + SAMPLE_SIZE * k + 4 * it) }
        assertEquals(listOf(0, 64, 8000), entry(0))
        assertEquals(listOf(64, 128, 16000), entry(1))
        assertEquals(listOf(128, 160, 55466), entry(2))
        assertTrue((0 until 3).all { k -> (12 until 16).all { image[OFF_SAMPLES + SAMPLE_SIZE * k + it] == 0.toByte() } })
        assertTrue((OFF_SAMPLES + 3 * SAMPLE_SIZE until OFF_SAMPLES + 64 * SAMPLE_SIZE).all { image[it] == 0.toByte() })
        // The memory: each sample's bytes at its start, the padding and the rest 0.
        assertArrayEquals(samples[0].data.toByteArray(), image.copyOfRange(OFF_ADPCM, OFF_ADPCM + 33))
        assertTrue((OFF_ADPCM + 33 until OFF_ADPCM + 64).all { image[it] == 0.toByte() })
        assertArrayEquals(samples[1].data.toByteArray(), image.copyOfRange(OFF_ADPCM + 64, OFF_ADPCM + 128))
        assertEquals(0x5A, u8(image, OFF_ADPCM + 128))
        assertTrue((OFF_ADPCM + 129 until image.size).all { image[it] == 0.toByte() })
    }

    // ---- limits ---------------------------------------------------------------------------------------------------

    @Test
    fun limitsAreRefusedWithActionableMessages() {
        // Twenty different full patterns, every track its own (6 channels × 64 rows × 11 bytes), past the 52928-byte data region.
        val base = OpnSystem.blank()
        val fm = 1
        val ssg = base.opn.instruments.indexOfFirst { it is OpnSsgSound } + 1
        val patterns = (0 until 20).map { p ->
            Pattern("P$p", List(6) { ch ->
                List(Songs.ROWS) { row ->
                    Cell(57 + row % 12, if (ch < 3) fm else ssg, OpnRules.FX_ARPEGGIO, 0x37,
                        listOf(Fx(OpnRules.FX_VOLUME_SLIDE, 0x11), Fx(OpnRules.FX_FINE, 0x80 + p), Fx(OpnRules.FX_SLIDE_UP, p * 6 + ch + 1)))
                }
            })
        }
        val big = base.copy(patterns = patterns, orders = patterns.indices.toList())
        val e = assertThrows(IllegalArgumentException::class.java) { OpnLowering.lower(big) }
        assertTrue(e.message, e.message!!.contains("Reuse patterns"))
        assertTrue(OpnSystem.errors(big).single().contains("Reuse patterns"))
        // The same patterns reused fit.
        OpnLowering.lower(big.copy(orders = List(20) { 0 }))

        val blank = OpnaSystem.blank()
        val large = OpnaSample("L", 16000, AdpcmBytes(ByteArray(200_000)))
        val memory = assertThrows(IllegalArgumentException::class.java) { OpnLowering.lower(blank.copy(sound = blank.opn.copy(samples = blank.opn.samples + large + large))) }
        assertTrue(memory.message, memory.message!!.contains("256.0 KiB"))
        val many = sounds(blank, listOf(OpnFmSound("FM")), List(65) { OpnaSample("S$it", 8000, AdpcmBytes(ByteArray(32))) })
        assertTrue(assertThrows(IllegalArgumentException::class.java) { OpnLowering.lower(many) }.message!!.contains("at most 64"))
        assertThrows(IllegalArgumentException::class.java) { OpnLowering.lower(blank, ByteArray(OpnPlayerImage.IMAGE_SIZE)) }
    }

    /** The YM2203 refuses the YM2608's sounds and features rather than writing an image its loader rejects. */
    @Test
    fun ym2203RefusesYm2608Features() {
        val base = OpnSystem.blank()
        val free = base.opn.instruments.indexOfFirst { it == null } + 1
        fun refused(song: Song, text: String) {
            val e = assertThrows(IllegalArgumentException::class.java) { OpnLowering.lower(song) }
            assertTrue(e.message, e.message!!.contains(text))
            assertTrue(OpnSystem.errors(song).any { it.contains(text) })
        }
        refused(base.copy(sound = base.opn.withInstrument(free, OpnAdpcmSound("A"))), "no ADPCM-B channel")
        refused(base.copy(sound = base.opn.withInstrument(free, OpnRhythmSound("R"))), "no rhythm unit")
        refused(base.copy(sound = base.opn.withInstrument(free, OpnFmSound("V", ams = 1))), "uses the LFO")
        refused(base.copy(sound = base.opn.copy(lfo = 0x09)), "no LFO")
        refused(base.copy(sound = base.opn.copy(samples = listOf(OpnaSample("S", 8000, AdpcmBytes(ByteArray(32)))))), "no ADPCM-B channel")
        // The YM2608 takes the LFO voice.
        val opna = OpnaSystem.blank()
        OpnLowering.lower(opna.copy(sound = opna.opn.withInstrument(opna.opn.instruments.indexOfFirst { it == null } + 1, OpnFmSound("V", ams = 1))))
    }

    // ---- the rhythm ROM ----------------------------------------------------------------------------------------------

    /**
     * The rhythm ROM is the user's: a YM2608 song whose played orders hold a drum note does not lower for playback without
     * it (the actionable [Ym2608RhythmRom.MISSING]); the image itself, the VGM export's lowering, songs without drums and
     * drums only in parked orders or past a pattern's length do.
     */
    @Test
    fun rhythmNeedsTheRomOnlyForPlayback() {
        assertFalse(Ym2608RhythmRom.installed)
        val blank = OpnaSystem.blank()
        val kick = blank.opn.instruments.indexOfFirst { it is OpnRhythmSound } + 1
        val drums = with(blank, 0, 9, 0, Cell(36, kick))
        assertEquals(emptyList<String>(), OpnaSystem.errors(drums))
        assertTrue(OpnValidator.playsRhythm(drums))
        val out = ByteArray(OpnaSystem.engine.imageSize)
        val e = assertThrows(IllegalArgumentException::class.java) { OpnaSystem.engine.lower(drums, out) }
        assertEquals(Ym2608RhythmRom.MISSING, e.message)
        // The image names the drum: FM 1's empty track differs from the Kick channel's.
        val image = OpnLowering.lower(drums)
        assertNotEquals(order(image, 0, 0), order(image, 0, 9))

        OpnaSystem.engine.lower(blank, out)
        val parked = drums.copy(patterns = listOf(blank.patterns[0], drums.patterns[0]), orders = listOf(0, 1), parkedOrders = listOf(1))
        assertFalse(OpnValidator.playsRhythm(parked))
        OpnaSystem.engine.lower(parked, out)
        val hidden = with(blank, 0, 9, 40, Cell(36, kick)).let { s -> s.copy(patterns = listOf(s.patterns[0].copy(length = 32))) }
        assertFalse(OpnValidator.playsRhythm(hidden))
        OpnaSystem.engine.lower(hidden, out)
    }

    // ---- demos -----------------------------------------------------------------------------------------------------

    @Test
    fun demosAndTheShortestSongsLower() {
        for (system in listOf(OpnSystem, OpnaSystem)) {
            val demo = system.demo()
            val image = ByteArray(system.engine.imageSize)
            system.engine.lower(demo, image)
            assertEquals(system.chip.code, u8(image, OpnPlayerImage.H_CHIP))
            assertEquals(demo.orders.size, u16(image, OpnPlayerImage.H_ORDERS))
            assertEquals(demo.ticksPerRow, u8(image, OpnPlayerImage.H_SPEED))
            assertEquals(demo.opn.timerB, u8(image, OpnPlayerImage.H_TIMER_B))
            assertEquals(OpnPlayerImage.FM3_SPECIAL, u8(image, OpnPlayerImage.H_FM3_MODE))
        }
        for (chip in OpnChip.entries) {
            val song = OpnDemoSong.shortest(chip)
            assertEquals(emptyList<String>(), chip.system().errors(song))
            val image = ByteArray(chip.imageSize)
            chip.system().engine.lower(song, image)
            assertEquals(1, u16(image, OpnPlayerImage.H_ORDERS))
            assertEquals(0, u16(image, OpnPlayerImage.H_LOOP))
            assertEquals(1, u8(image, OpnPlayerImage.H_SPEED))
            assertEquals(1, u8(image, OFF_ROWS))
            // FM 1: note A-4 with the first FM voice; the other channels one empty row.
            val fm1 = OFF_DATA + order(image, 0, 0)
            assertEquals(listOf(0x03, 57, 1), (0 until 3).map { u8(image, fm1 + it) })
            assertTrue((1 until chip.channelCount).all { u8(image, OFF_DATA + order(image, 0, it)) == 0x80 })
            assertEquals(song.opn.adpcmUsed, u32(image, OpnPlayerImage.H_ADPCM_SIZE))
        }
    }
}
