package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.saa.SaaHardwareEnvelope
import org.thortracker.system.saa.SaaInstrument
import org.thortracker.system.saa.SaaSound
import org.thortracker.system.saa.SaaVariant

/**
 * The songs of `tools/audio/saa/saa_golden.py` written in Thor Tracker's model, for [SaaGoldenTest]: the host test's
 * SAM Coupé song (`saa_song.test_song`), its CMS song (`saa_song.cms_song`) and the features song. Kept in the
 * reference script's shape (instruments by slot, patterns by name, orders naming a pattern per channel) so the two
 * read alike.
 */
object SaaGoldenSongs {
    private const val OFF = 0xFE
    private val NONE = NesEnvelope()

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = NesEnvelope(values.toList(), loop, release)

    /**
     * An instrument as `saa_song.Instrument` writes it: absent envelopes are empty (the model's default volume is
     * `[15]`, the script's is none); [control] is the hardware envelope's register value (0 = none), [rate] its external
     * clock rate, [retrigger] its flag.
     */
    private fun ins(
        volume: NesEnvelope = NONE, arpeggio: NesEnvelope = NONE, pitch: NesEnvelope = NONE, mixer: NesEnvelope = NONE,
        relative: Boolean = false, noise: Int = 0, panLeft: Int = 15, panRight: Int = 15, control: Int = 0,
        rate: Int = 0, retrigger: Boolean = false,
    ) = SaaInstrument(
        name = "", volume = volume, arpeggio = arpeggio, pitch = pitch, mixer = mixer, pitchRelative = relative,
        noise = noise, panLeft = panLeft, panRight = panRight,
        envelope = SaaHardwareEnvelope.ofControl(control, if (rate == 0) 1 else rate)?.copy(retrigger = retrigger),
    )

    /** A cell as the script writes it: note (null none, [OFF]), instrument (null none), effects in columns 0... */
    private fun c(note: Int? = null, inst: Int? = null, vararg fx: Pair<Char, Int>): Cell {
        var cell = Cell(
            note = when (note) { null -> Cell.EMPTY; OFF -> Cell.CUT; else -> note },
            instrument = inst ?: 0,
        )
        fx.forEachIndexed { i, (letter, param) -> cell = cell.withEffect(i, code(letter), param) }
        return cell
    }

    private fun code(letter: Char) = (0..EffectSpec.MAX_CODE).first { EffectSpec.charOf(it) == letter }

    private class Order(val rows: Int, val patterns: List<String?>)

    private fun song(
        variant: SaaVariant, speed: Int, orders: List<Order>, patterns: Map<String, Map<Int, Cell>>,
        instruments: Map<Int, SaaInstrument>, loop: Int, multispeed: Int = 1,
    ): Song = Song(
        title = "Golden",
        ticksPerRow = speed,
        loopOrder = loop,
        patterns = orders.mapIndexed { o, order ->
            Pattern("Order $o", (0 until variant.channels).map { ch ->
                val rows = order.patterns[ch]?.let { patterns.getValue(it) } ?: emptyMap()
                List(Songs.ROWS) { rows[it] ?: Cell.BLANK }
            }, length = order.rows)
        },
        orders = orders.indices.toList(),
        sound = SaaSound(variant = variant, instruments = List(SaaSound.SLOTS) { instruments[it + 1] }, multispeed = multispeed),
    )

    /** `saa_song.test_instruments()`. */
    private fun testInstruments(): Map<Int, SaaInstrument> = mapOf(
        1 to ins(),
        2 to ins(volume = env(15, 12, 10, 9, 8, 7, 6, 5, 5, 4, 3, 2, 1, 0, release = 9), panRight = 10),
        3 to ins(volume = env(15, 14, 13, 12), control = 0x86, retrigger = true),
        4 to ins(volume = env(6, 9, 12, 15, 14, 13, 12, 11, 12, 13, 10, 7, 4, 2, 0, loop = 4, release = 10),
            mixer = env(1), panRight = 9),
        5 to ins(volume = env(12), arpeggio = env(0, 0, 4, 4, 7, 7, loop = 0), panLeft = 9),
        6 to ins(volume = env(15, 15, 13, 11, 9, 7, 5, 3, 1, 0), pitch = env(-16, -12, -8, -6, -4, -2), relative = true,
            mixer = env(3, 1, 1), noise = 0),
        7 to ins(volume = env(14, 11, 8, 6, 4, 2, 0), arpeggio = env(12, 0), pitch = env(8, 4, 0, -4),
            mixer = env(2, 2, 3, 2), noise = 1),
        8 to ins(volume = env(10, 6, 3, 0), mixer = env(2), noise = 0, panRight = 7),
        9 to ins(volume = env(15, 15, 14, 14, 13)),
        10 to ins(volume = env(12), mixer = env(2), noise = 3),
        11 to ins(volume = env(15), control = 0xBB, rate = 2, retrigger = true),
        12 to ins(volume = env(15), control = 0xA4, rate = 2),
        13 to ins(volume = env(15), mixer = env(0), control = 0x8A, retrigger = true),
    )

    /** `saa_song.test_patterns()`. */
    private fun testPatterns(): Map<String, Map<Int, Cell>> {
        val p = HashMap<String, Map<Int, Cell>>()
        p["l0"] = mapOf(0 to c(60, 2, '4' to 0x24, '8' to 0xF4), 4 to c(62, null, 'P' to 0x90),
            6 to c(null, null, 'N' to 0x01, 'M' to 0x03), 8 to c(64, null, '4' to 0x30),
            10 to c(null, null, '1' to 0x04), 12 to c(65, 2, '2' to 0x02, 'G' to 0x02), 14 to c(OFF, null, '2' to 0x00))
        p["l1"] = mapOf(0 to c(48, 8), 2 to c(48, 7), 4 to c(57, 1), 8 to c(null, null, 'Y' to 0x01), 12 to c(52, 10),
            15 to c(OFF))
        p["l2"] = mapOf(0 to c(45, 3), 4 to c(null, null, 'E' to 0x8E), 8 to c(45, 13), 12 to c(null, null, 'E' to 0x00),
            13 to c(47, 3), 15 to c(OFF))
        p["d3"] = mapOf(0 to c(36, 6), 4 to c(50, 7), 8 to c(36, 6), 10 to c(36, 6, 'G' to 0x03),
            12 to c(50, 7, 'S' to 0x02), 14 to c(52, 7, 'G' to 0x01, 'S' to 0x04))
        p["b4"] = mapOf(0 to c(36, 9, 'C' to 0x0C), 2 to c(OFF), 4 to c(43, 2), 6 to c(null, null, '3' to 0x20), 7 to c(48),
            10 to c(36, null, '3' to 0x10), 12 to c(null, null, '3' to 0x00, 'S' to 0x03), 13 to c(OFF),
            14 to c(41, 2, 'C' to 0x0F), 15 to c(OFF))
        p["e5"] = mapOf(0 to c(57, 11), 8 to c(64, 12), 15 to c(OFF))
        p["pad1"] = mapOf(0 to c(60, 4), 12 to c(64, 4, 'C' to 0x0A, '8' to 0x5F), 16 to c(OFF),
            20 to c(67, 4, '4' to 0x46, 'P' to 0x70), 28 to c(OFF), 30 to c(OFF))
        p["clk1"] = mapOf(0 to c(55, 1, 'C' to 0x04), 8 to c(null, null, '1' to 0x08), 10 to c(null, null, '1' to 0x00),
            16 to c(62, 8), 18 to c(55, 1), 24 to c(null, null, 'Y' to 0x02), 26 to c(null, null, 'Y' to 0x00),
            31 to c(OFF))
        p["buzz1"] = mapOf(0 to c(43, 3), 8 to c(43, 3), 16 to c(46, 3, 'E' to 0xB3), 24 to c(OFF))
        p["drum1"] = mapOf(0 to c(36, 6), 4 to c(50, 7), 8 to c(36, 6), 12 to c(50, 7), 16 to c(36, 6), 20 to c(50, 7),
            24 to c(36, 6), 28 to c(50, 7, 'N' to 0x02), 30 to c(62, 8))
        p["bass1"] = mapOf(0 to c(24, 9), 4 to c(31), 8 to c(24, 9, '1' to 0x08), 10 to c(null, null, '1' to 0x00),
            12 to c(29, 9), 16 to c(24, 9, '2' to 0x04), 18 to c(null, null, '2' to 0x00), 20 to c(27, 9),
            24 to c(24, 9), 28 to c(31, 9, 'S' to 0x04))
        p["lead1"] = mapOf(2 to c(72, 11, '4' to 0x26), 6 to c(74), 10 to c(76, null, '0' to 0x0C), 14 to c(OFF),
            18 to c(79, 5, 'P' to 0x88, 'C' to 0x0B, '4' to 0x22, 'A' to 0x01),
            22 to c(77, null, '3' to 0x08), 24 to c(72), 26 to c(null, null, '3' to 0x00), 30 to c(OFF))
        p["o2a"] = mapOf(0 to c(60, 2, 'F' to 0x04, '4' to 0x48), 4 to c(62, null, '1' to 0x20),
            8 to c(64, null, 'F' to 0x05), 12 to c(65, null, 'D' to 0x02, '1' to 0x00))
        p["o2b"] = mapOf(0 to c(72, 8, 'Y' to 0x03), 2 to c(null, null, 'Y' to 0x00),
            6 to c(76, 10, 'P' to 0x90, 'A' to 0x01), 11 to c(OFF))
        p["o3a"] = mapOf(0 to c(55, 4, 'C' to 0x08), 2 to c(57, null, 'C' to 0x0F, 'G' to 0x01), 6 to c(OFF),
            8 to c(59, 2), 16 to c(60, 1, '4' to 0x13, 'Y' to 0x01), 23 to c(OFF, null, 'B' to 0x01))
        p["o3b"] = mapOf(2 to c(64, 5, '0' to 0x47), 9 to c(OFF), 12 to c(62, 7), 20 to c(60, 1, 'P' to 0x40))
        p["o3c"] = mapOf(2 to c(50, 13, 'M' to 0x01), 10 to c(OFF), 12 to c(52, 12), 20 to c(OFF))
        p["o3d"] = mapOf(0 to c(36, 6), 6 to c(36, 6), 12 to c(43, 6), 18 to c(36, 6, 'S' to 0x01))
        p["o3e"] = mapOf(4 to c(84, 7, '8' to 0xF0), 10 to c(OFF), 14 to c(86, 7, '8' to 0x0F))
        return p
    }

    private val chipA = listOf(
        listOf("l0", "l1", "l2", "d3", "b4", "e5"),
        listOf("pad1", "clk1", "buzz1", "drum1", "bass1", "lead1"),
        listOf("o2a", "o2b", "l2", "d3", "b4", null),
        listOf("o3a", "o3b", "o3c", "o3d", null, "o3e"),
    )
    private val rows = listOf(16, 32, 16, 24)

    /** `saa_golden.py` sam: `saa_song.test_song()` (SAM Coupé). */
    fun sam(): Song = song(SaaVariant.SAM, 6, rows.indices.map { Order(rows[it], chipA[it]) }, testPatterns(),
        testInstruments(), loop = 1)

    /** `saa_golden.py` cms: `saa_song.cms_song()`: chip A = the SAM song's lanes, chip B the same features elsewhere. */
    fun cms(): Song {
        val chipB = listOf(
            listOf("b4", "d3", "l2", "pad1", null, "e5"),
            listOf("bass1", "drum1", "buzz1", "l0", null, "lead1"),
            listOf("b4", "d3", null, "pad1", null, null),
            listOf("o3b", "o3d", "o3c", null, null, "o3e"),
        )
        return song(SaaVariant.CMS, 6, rows.indices.map { Order(rows[it], chipA[it] + chipB[it]) }, testPatterns(),
            testInstruments(), loop = 1)
    }

    /**
     * `saa_golden.features_song()`, with one more order parked between the first and second: the arrangement drops it
     * and renumbers the loop and every `Bxx`, which the script's song states directly.
     */
    fun features(): Song {
        val i = mapOf(
            1 to ins(
                volume = NesEnvelope(List(256) { (it * 7 + 3) % 16 }, loop = 16, release = 200),
                arpeggio = NesEnvelope(List(256) { (it * 5) % 128 - 64 }, loop = 0),
                pitch = NesEnvelope(List(256) { (it * 3) % 128 - 64 }, loop = 128), relative = true,
                mixer = NesEnvelope(List(256) { it % 4 }, loop = 255), noise = 3, panLeft = 0, panRight = 15,
            ),
            2 to ins(pitch = env(4, -4, loop = 0), panLeft = 15, panRight = 0),
            3 to ins(volume = env(15, 0, release = 1), mixer = env(1, 3, 2, loop = 1), noise = 2, control = 0xBF, rate = 255),
            64 to ins(volume = env(15, 10, loop = 0, release = 1), arpeggio = env(63, -64), control = 0x8E,
                retrigger = true, noise = 1),
        )
        val p = HashMap<String, Map<Int, Cell>>()
        p["a0"] = mapOf(0 to c(11, 1, '4' to 0x22), 8 to c(106), 16 to c(OFF, null, 'C' to 0x0A), 40 to c(48, 1),
            63 to c(OFF, null, 'A' to 0x00))
        p["a1"] = mapOf(
            0 to c(60, 2, 'C' to 0x08, 'P' to 0x90, '4' to 0x37, 'A' to 0x01),
            // Blank columns before used ones are left out of the image.
            1 to Cell().withEffect(2, code('8'), 0x0F),
            2 to Cell().withEffect(1, code('1'), 0x10).withEffect(3, code('M'), 0x03),
            32 to c(OFF),
        )
        p["a2"] = mapOf(5 to c(null, 64), 6 to c(72, null, '0' to 0x47, 'E' to 0xBF), 20 to c(74, 3, 'E' to 0x00),
            24 to c(null, null, 'E' to 0x80, 'N' to 0x03))
        p["a3"] = mapOf(0 to c(36, 3, 'Y' to 0x03), 12 to c(38, 64, 'Y' to 0x00, 'M' to 0x00))
        p["a4"] = mapOf(0 to c(84, 64, 'S' to 0x02, '8' to 0xF0), 4 to c(86, 1, 'G' to 0x02, 'N' to 0x00))
        p["a5"] = mapOf(0 to c(55, 3, 'Y' to 0x01), 9 to c(null, null, 'Y' to 0x02), 10 to c(null, null, 'Y' to 0x00))
        // Document orders: 0, parked 1, 2, 3; B03 = document order 3, which plays as order 2.
        p["b0"] = mapOf(0 to c(50, 2, 'G' to 0x01), 6 to c(52, 1), 15 to c(null, null, 'B' to 0x03, 'D' to 0x00))
        p["b3"] = mapOf(3 to c(64, 3, 'G' to 0x03, 'S' to 0x02), 10 to c(64, null, '3' to 0x10),
            12 to c(67, null, '1' to 0x04, '2' to 0x00))
        p["c4"] = mapOf(0 to c(36, 2, 'F' to 0x04, 'B' to 0x02))
        p["parked"] = mapOf(0 to c(40, 2, 'B' to 0x00))
        val orders = listOf(
            Order(64, listOf("a0", "a1", "a2", "a3", "a4", "a5")),
            Order(8, listOf("parked", null, null, null, null, null)),
            Order(20, listOf("b0", null, null, "b3", null, null)),
            Order(1, listOf(null, null, null, null, "c4", null)),
        )
        return song(SaaVariant.SAM, 5, orders, p, i, loop = 2, multispeed = 3).copy(parkedOrders = listOf(1))
    }
}
