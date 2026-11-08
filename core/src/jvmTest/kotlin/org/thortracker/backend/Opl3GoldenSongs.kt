package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.opl3.Opl3Chord
import org.thortracker.system.opl3.Opl3Instrument
import org.thortracker.system.opl3.Opl3Operator
import org.thortracker.system.opl3.Opl3Sound
import org.thortracker.system.opl3.Opl3Sweep

/**
 * The songs of `tools/audio/opl3/opl3_golden.py` written in Thor Tracker's model, for [Opl3GoldenTest]: the spike's
 * test song (`opl3_song.test_song`, without the one explicit `000`) and the features song. Kept in the reference
 * script's shape (instruments by slot, patterns by name, orders naming a pattern per channel) so the two read alike.
 */
object Opl3GoldenSongs {
    private const val OFF = 0xFE

    private fun op(
        mult: Int = 1, tl: Int = 0, ar: Int = 15, dr: Int = 0, sl: Int = 0, rr: Int = 4, wave: Int = 0, ksl: Int = 0,
        am: Boolean = false, vib: Boolean = false, sustain: Boolean = true, ksr: Boolean = false,
    ) = Opl3Operator(mult, tl, ar, dr, sl, rr, wave, ksl, am, vib, sustain, ksr)

    private fun ins(ops: List<Opl3Operator>, alg: Int = 0, fb: Int = 0, sweep: Triple<Int, Int, Int> = Triple(0, 0, 0)): Opl3Instrument {
        val four = ops.size == 4
        val all = if (four) ops else ops + ops
        return Opl3Instrument("", four, alg, fb, all, Opl3Sweep(sweep.first, sweep.second, sweep.third))
    }

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
        speed: Int, orders: List<Order>, patterns: Map<String, Map<Int, Cell>>, instruments: Map<Int, Opl3Instrument>,
        shapes: List<List<Int>>, loop: Int, multispeed: Int = 1, depth: Int = 0,
    ): Song = Song(
        title = "Golden",
        ticksPerRow = speed,
        loopOrder = loop,
        patterns = orders.mapIndexed { o, order ->
            Pattern("Order $o", (0 until 9).map { ch ->
                val rows = order.patterns[ch]?.let { patterns.getValue(it) } ?: emptyMap()
                List(Songs.ROWS) { rows[it] ?: Cell.BLANK }
            }, length = order.rows)
        },
        orders = orders.indices.toList(),
        sound = Opl3Sound(
            instruments = List(64) { instruments[it + 1] },
            chords = shapes.map { Opl3Chord("", it) },
            multispeed = multispeed,
            deepTremolo = depth and 0x80 != 0,
            deepVibrato = depth and 0x40 != 0,
        ),
    )

    /** `opl3_golden.spike_song()`. */
    fun spike(): Song {
        val i = mapOf(
            1 to ins(listOf(op(mult = 1, tl = 30, ar = 13, dr = 3, sl = 4, rr = 5), op(mult = 2, tl = 34, ar = 12, dr = 2, sl = 3, rr = 5),
                op(mult = 1, tl = 28, ar = 12, dr = 2, sl = 3, rr = 5), op(mult = 1, tl = 4, ar = 11, dr = 2, sl = 2, rr = 6)), alg = 0, fb = 5),
            2 to ins(listOf(op(mult = 1, tl = 22, ar = 15, dr = 4, sl = 5, rr = 6, wave = 1), op(mult = 1, tl = 2, ar = 15, dr = 3, sl = 3, rr = 7)), alg = 0, fb = 6),
            3 to ins(listOf(op(mult = 2, tl = 12, ar = 9, dr = 2, sl = 2, rr = 4, wave = 2), op(mult = 1, tl = 6, ar = 8, dr = 1, sl = 2, rr = 4)), alg = 1, fb = 2),
            4 to ins(listOf(op(mult = 14, tl = 36, ar = 15, dr = 6, sl = 8, rr = 6, ksl = 1), op(mult = 1, tl = 4, ar = 15, dr = 3, sl = 5, rr = 6),
                op(mult = 1, tl = 24, ar = 15, dr = 4, sl = 6, rr = 6), op(mult = 1, tl = 8, ar = 15, dr = 2, sl = 4, rr = 6)), alg = 1, fb = 3),
            5 to ins(listOf(op(mult = 1, tl = 26, ar = 14, dr = 2, sl = 3, rr = 5, vib = true), op(mult = 1, tl = 3, ar = 13, dr = 2, sl = 2, rr = 5)), alg = 0, fb = 4),
            6 to ins(listOf(op(mult = 1, tl = 40, ar = 15, dr = 8, sl = 15, rr = 8), op(mult = 1, tl = 0, ar = 15, dr = 4, sl = 6, rr = 7, sustain = false)),
                alg = 0, fb = 0, sweep = Triple(24, 0, 40)),
            7 to ins(listOf(op(mult = 15, tl = 8, ar = 15, dr = 7, sl = 7, rr = 8, wave = 6), op(mult = 1, tl = 4, ar = 15, dr = 6, sl = 7, rr = 8, sustain = false)),
                alg = 0, fb = 7, sweep = Triple(7, -5, 12)),
            8 to ins(listOf(op(mult = 12, tl = 10, ar = 15, dr = 10, sl = 12, rr = 12, wave = 7), op(mult = 9, tl = 6, ar = 15, dr = 10, sl = 12, rr = 12, sustain = false)),
                alg = 0, fb = 6),
            9 to ins(listOf(op(mult = 1, tl = 63, ar = 15, dr = 0, sl = 0, rr = 15), op(mult = 1, tl = 0, ar = 15, dr = 0, sl = 0, rr = 6)), alg = 0, fb = 0),
            10 to ins(listOf(op(mult = 4, tl = 14, ar = 12, dr = 1, sl = 2, rr = 5, wave = 0), op(mult = 3, tl = 30, ar = 14, dr = 2, sl = 3, rr = 5),
                op(mult = 1, tl = 28, ar = 14, dr = 2, sl = 3, rr = 5), op(mult = 1, tl = 6, ar = 12, dr = 1, sl = 2, rr = 5)), alg = 2, fb = 1),
            11 to ins(listOf(op(mult = 3, tl = 16, ar = 15, dr = 5, sl = 9, rr = 5, wave = 4), op(mult = 7, tl = 26, ar = 15, dr = 5, sl = 7, rr = 5),
                op(mult = 1, tl = 10, ar = 15, dr = 4, sl = 8, rr = 5), op(mult = 2, tl = 12, ar = 15, dr = 6, sl = 10, rr = 5, wave = 5)), alg = 3, fb = 0),
            12 to ins(listOf(op(mult = 2, tl = 24, ar = 15, dr = 5, sl = 6, rr = 6), op(mult = 1, tl = 6, ar = 15, dr = 4, sl = 5, rr = 6)), alg = 0, fb = 3),
        )
        val shapes = listOf(listOf(4, 7, 12), listOf(3, 7, 10, 14), listOf(-5, 4, 7), listOf(7))
        val p = HashMap<String, Map<Int, Cell>>()
        p["brass0"] = mapOf(0 to c(48, 1, '4' to 0x00, '8' to 0x11), 4 to c(55, null, '4' to 0x34), 8 to c(52, null, 'P' to 0x88),
            12 to c(OFF), 14 to c(50, 1, '8' to 0x10, 'C' to 0x30))
        p["bass0"] = mapOf(0 to c(36, 2, '1' to 0x08), 3 to c(null, null, '1' to 0x00), 4 to c(36, null, '2' to 0x10),
            6 to c(null, null, '2' to 0x00), 8 to c(43, null, 'P' to 0x70), 12 to c(41, null), 15 to c(OFF))
        p["pad0"] = mapOf(0 to c(60, 3, 'H' to 0x47), 8 to c(57, null, 'K' to 0x01, '8' to 0x01), 15 to c(OFF))
        // Row 4's explicit 000 (arpeggio off) is the one cell Thor Tracker cannot store; opl3_golden.py drops it too.
        p["epiano0"] = mapOf(0 to c(64, 4, '0' to 0x37), 6 to c(67, null, '0' to 0x47), 10 to c(65, null), 13 to c(OFF))
        p["lead0"] = mapOf(0 to c(72, 5, '3' to 0x00, 'I' to 0x30), 2 to c(74, null, 'M' to 0x13, 'W' to 0x21),
            4 to c(null, null, '3' to 0x18), 5 to c(79, null), 8 to c(76, null, 'E' to 0x01, 'W' to 0x03),
            10 to c(null, null, '3' to 0x00, '4' to 0x26), 12 to c(77, null, 'I' to 0x3F, 'M' to 0x24, 'E' to 0x07, 'W' to 0x12))
        p["chords0"] = mapOf(0 to c(48, 12, 'K' to 0x02, 'C' to 0x2A), 4 to c(53, null, 'H' to 0x38, 'A' to 0x04),
            8 to c(55, null, 'K' to 0x03, 'A' to 0x30), 12 to c(OFF, null, 'A' to 0x00))
        p["kick"] = mapOf(0 to c(36, 6), 4 to c(36, 6), 8 to c(36, 6), 12 to c(36, 6), 14 to c(38, 6, 'G' to 0x02))
        p["snare"] = mapOf(4 to c(50, 7), 12 to c(50, 7, 'S' to 0x03), 15 to c(52, 7, 'G' to 0x01, 'S' to 0x04))
        p["hat"] = (0 until 16 step 2).associateWith { r -> if (r % 4 == 2) c(if (r % 4 != 0) 84 else 86, 8, 'S' to 0x02) else c(if (r % 4 != 0) 84 else 86, 8) }
        p["c1a"] = mapOf(0 to c(60, 12, 'K' to 0x02), 8 to c(62, 1), 16 to c(57, 12, 'K' to 0x01), 24 to c(59, 10), 31 to c(OFF))
        p["c1b"] = mapOf(2 to c(55, 12, 'K' to 0x01), 10 to c(52, 12, 'H' to 0x37), 18 to c(50, 4, '8' to 0x01), 26 to c(48, 12, 'K' to 0x04))
        p["c1c"] = mapOf(4 to c(64, 3, 'K' to 0x02, '8' to 0x10), 12 to c(67, null, 'H' to 0x59), 20 to c(OFF), 22 to c(69, 3, 'K' to 0x03))
        p["c1d"] = mapOf(6 to c(43, 12, 'K' to 0x01), 14 to c(45, 11, 'C' to 0x38), 22 to c(47, 12, 'H' to 0x47), 30 to c(OFF))
        p["c1e"] = mapOf(1 to c(72, 5, 'H' to 0x04, '4' to 0x23), 9 to c(74, null, 'H' to 0x30), 17 to c(76, 2, 'K' to 0x01),
            25 to c(79, 5, '0' to 0x47, 'H' to 0x0C))
        p["c1f"] = mapOf(3 to c(36, 2, 'H' to 0x07), 11 to c(38, 2, 'K' to 0x02, '1' to 0x02), 19 to c(40, 2, '2' to 0x01, 'H' to 0x0C),
            27 to c(OFF, null, '2' to 0x00))
        p["kick1"] = mapOf(0 to c(36, 6), 8 to c(36, 6), 16 to c(36, 6), 24 to c(36, 6), 28 to c(36, 6, 'G' to 0x03))
        p["stab1"] = mapOf(5 to c(60, 12, 'H' to 0x47), 13 to c(62, 12, 'H' to 0x38, '8' to 0x11), 21 to c(64, 12, 'K' to 0x04),
            29 to c(65, 12, 'H' to 0x37, 'S' to 0x02))
        p["hat1"] = (0 until 32 step 4).associateWith { r -> c(86, 8, 'C' to 0x3F - r) }
        p["o2lead"] = mapOf(0 to c(60, 5, 'F' to 0x04, '4' to 0x48), 4 to c(62, null, '1' to 0x20), 8 to c(64, null, 'F' to 0x05),
            12 to c(65, null, 'D' to 0x02, '1' to 0x00))
        p["o2bell"] = mapOf(0 to c(72, 11, '8' to 0x10), 6 to c(76, 11, '8' to 0x01, 'P' to 0x90), 11 to c(OFF))
        p["o2organ"] = mapOf(0 to c(48, 10, 'A' to 0x01), 8 to c(50, null, 'A' to 0x10))
        p["o3a"] = mapOf(0 to c(55, 10, 'C' to 0x20), 2 to c(57, null, 'C' to 0x3F, 'G' to 0x01), 6 to c(OFF), 8 to c(59, 11),
            16 to c(60, 1, '4' to 0x13), 23 to c(OFF, null, 'B' to 0x01))
        p["o3b"] = mapOf(2 to c(64, 2, 'H' to 0x47), 9 to c(OFF), 12 to c(62, 4), 20 to c(60, 9, 'P' to 0x40))
        p["o3c"] = mapOf(0 to c(36, 6, '8' to 0x10), 6 to c(36, 6, '8' to 0x01), 12 to c(43, 6), 18 to c(36, 6, '8' to 0x11, 'S' to 0x01))
        val orders = listOf(
            Order(16, listOf("brass0", "bass0", "pad0", "epiano0", "lead0", "chords0", "kick", "snare", "hat")),
            Order(32, listOf("c1a", "c1b", "c1c", "c1d", "c1e", "c1f", "kick1", "stab1", "hat1")),
            Order(16, listOf("o2organ", "bass0", "o2bell", null, "o2lead", null, "kick", "snare", "hat")),
            Order(24, listOf("o3a", "o3b", null, null, null, null, "o3c", null, null)),
        )
        return song(6, orders, p, i, shapes, loop = 1)
    }

    /**
     * `opl3_golden.features_song()`, with one more order parked between the first and second: the arrangement drops it
     * and renumbers the loop and every `Bxx`, which the script's song states directly.
     */
    fun features(): Song {
        val i = mapOf(
            1 to ins(listOf(op(mult = 4, tl = 14, ar = 12, dr = 1, sl = 2, rr = 5), op(mult = 3, tl = 30, ar = 14, dr = 2, sl = 3, rr = 5, ksl = 3, am = true),
                op(mult = 0, tl = 28, ar = 14, dr = 2, sl = 3, rr = 5, ksr = true), op(mult = 1, tl = 6, ar = 12, dr = 1, sl = 2, rr = 5, vib = true)), alg = 2, fb = 1),
            2 to ins(listOf(op(mult = 2, tl = 20, ar = 15, dr = 5, sl = 6, rr = 6, wave = 7), op(mult = 1, tl = 3, ar = 15, dr = 4, sl = 5, rr = 6, sustain = false)),
                alg = 0, fb = 4, sweep = Triple(-12, 5, 3)),
            3 to ins(listOf(op(mult = 1, tl = 10, ar = 10, rr = 3, wave = 3), op(mult = 2, tl = 12, ar = 9, rr = 3, wave = 5)), alg = 1, fb = 7),
            4 to ins(listOf(op(mult = 1, tl = 63), op(mult = 15, tl = 0, wave = 6), op(mult = 11, tl = 1, ksl = 2), op(mult = 13, tl = 2, wave = 4)),
                alg = 3, fb = 0, sweep = Triple(96, -96, 255)),
            64 to ins(listOf(op(mult = 1, tl = 40), op(mult = 1, tl = 0)), alg = 0, fb = 0),
        )
        val shapes = listOf(listOf(4, 7), listOf(3, 7, 10, 14), listOf(-12, -5, 4, 7), listOf(48), listOf(-48), listOf(7, 12, 19))
        val p = HashMap<String, Map<Int, Cell>>()
        p["a0"] = mapOf(0 to c(0, 1, '4' to 0x22), 8 to c(95, null), 16 to c(OFF, null, 'C' to 0x10), 40 to c(48, 1))
        p["a1"] = mapOf(0 to c(48, 4, 'W' to 0x07, 'M' to 0x4F, 'E' to 0x03, 'I' to 0x3F), 32 to c(OFF))
        p["a2"] = mapOf(
            0 to c(60, 2, 'C' to 0x20, '8' to 0x11, '4' to 0x37, 'P' to 0x90),
            // Blank columns before used ones are left out of the image.
            1 to Cell().withEffect(2, code('I'), 0x10),
            2 to Cell().withEffect(1, code('8'), 0x10).withEffect(3, code('A'), 0x0F),
            63 to c(OFF, null, 'A' to 0x00),
        )
        p["a3"] = mapOf(5 to c(null, 3), 6 to c(72, null, '0' to 0x47))
        p["a6"] = mapOf(0 to c(55, 2, 'H' to 0x40), 4 to c(57, null, 'H' to 0x07), 12 to c(59, null, 'K' to 0x03))
        p["a8"] = (0 until 6).associate { r -> r * 8 to c(60 + r, 64, 'K' to r + 1) }
        // Document orders: 0, parked 1, 2, 3; B03 = document order 3, which plays as order 2.
        p["b0"] = mapOf(0 to c(50, 2, 'K' to 0x02), 6 to c(52, 1), 15 to c(null, null, 'B' to 0x03, 'D' to 0x00))
        p["b5"] = mapOf(3 to c(64, 3, 'G' to 0x03, 'S' to 0x02), 10 to c(64, null, '3' to 0x10), 12 to c(67, null, '1' to 0x04, '2' to 0x00))
        p["c4"] = mapOf(0 to c(36, 2, 'F' to 0x04, 'B' to 0x02))
        p["parked"] = mapOf(0 to c(40, 2, 'B' to 0x00))
        val orders = listOf(
            Order(64, listOf("a0", "a1", "a2", "a3", null, null, "a6", null, "a8")),
            Order(8, listOf("parked", null, null, null, null, null, null, null, null)),
            Order(20, listOf("b0", null, null, null, null, "b5", null, null, null)),
            Order(1, listOf(null, null, null, null, "c4", null, null, null, null)),
        )
        return song(5, orders, p, i, shapes, loop = 2, multispeed = 2, depth = 0xC0).copy(parkedOrders = listOf(1))
    }
}
