package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.opm.OpmInstrument
import org.thortracker.system.opm.OpmOperator
import org.thortracker.system.opm.OpmSound
import org.thortracker.system.opm.OpmSweep

/**
 * The songs of `tools/audio/opm/opm_golden.py` written in Thor Tracker's model, for [OpmGoldenTest]: the host test's
 * song (`opm_song.test_song`, without the one explicit `000`) and the features song. Kept in the reference script's
 * shape (instruments by slot, patterns by name, orders naming a pattern per channel) so the two read alike.
 */
object OpmGoldenSongs {
    private const val OFF = 0xFE
    private const val NOISE_SLOT = 2

    private fun op(
        dt1: Int = 0, mul: Int = 1, tl: Int = 0, ks: Int = 0, ar: Int = 31, am: Boolean = false, d1r: Int = 0,
        dt2: Int = 0, d2r: Int = 0, d1l: Int = 0, rr: Int = 15,
    ) = OpmOperator(dt1 = dt1, mul = mul, tl = tl, ks = ks, ar = ar, am = am, d1r = d1r, dt2 = dt2, d2r = d2r, d1l = d1l, rr = rr)

    private fun ins(
        ops: List<OpmOperator>, alg: Int = 0, fb: Int = 0, pms: Int = 0, ams: Int = 0, noise: Boolean = false,
        nfrq: Int = 0, sweep: Triple<Int, Int, Int> = Triple(0, 0, 0),
    ) = OpmInstrument(
        name = "", algorithm = alg, feedback = fb, pms = pms, ams = ams, operators = ops, noise = noise,
        noiseFrequency = nfrq, sweep = OpmSweep(sweep.first, sweep.second, sweep.third),
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
        speed: Int, orders: List<Order>, patterns: Map<String, Map<Int, Cell>>, instruments: Map<Int, OpmInstrument>,
        loop: Int, multispeed: Int = 1, lfoRate: Int = 0, lfoWave: Int = 0, amd: Int = 0, pmd: Int = 0,
        csm: Boolean = false, timerA: Int = 0,
    ): Song = Song(
        title = "Golden",
        ticksPerRow = speed,
        loopOrder = loop,
        patterns = orders.mapIndexed { o, order ->
            Pattern("Order $o", (0 until 8).map { ch ->
                val rows = order.patterns[ch]?.let { patterns.getValue(it) } ?: emptyMap()
                List(Songs.ROWS) { rows[it] ?: Cell.BLANK }
            }, length = order.rows)
        },
        orders = orders.indices.toList(),
        sound = OpmSound(
            instruments = List(64) { instruments[it + 1] },
            multispeed = multispeed,
            lfoRate = lfoRate,
            lfoWave = lfoWave,
            amd = amd,
            pmd = pmd,
            csm = csm,
            timerA = timerA,
        ),
    )

    /** `opm_song.test_instruments()`. */
    private fun testInstruments(): Map<Int, OpmInstrument> = mapOf(
        1 to ins(listOf(op(tl = 127, rr = 6), op(tl = 127, rr = 6), op(tl = 127, rr = 6), op(tl = 0, rr = 6)), alg = 7),
        2 to ins(listOf(op(tl = 127), op(tl = 127), op(tl = 127), op(tl = 4, ar = 31, d1r = 14, d2r = 0, d1l = 15, rr = 15)),
            alg = 7, noise = true, nfrq = 24),
        3 to ins(listOf(op(mul = 1, tl = 34, ar = 18, d1r = 2, d1l = 2, rr = 5), op(mul = 1, tl = 6, ar = 16, d1r = 1, d1l = 1, rr = 5, am = true),
            op(dt1 = 3, mul = 2, tl = 38, ar = 18, d1r = 2, d1l = 2, rr = 5), op(dt1 = 7, mul = 1, tl = 8, ar = 16, d1r = 1, d1l = 1, rr = 5, am = true)),
            alg = 4, fb = 3, pms = 5, ams = 2),
        4 to ins(listOf(op(mul = 1, tl = 30, ar = 31, d1r = 18, d1l = 15, rr = 12), op(mul = 1, tl = 127), op(mul = 1, tl = 127),
            op(mul = 1, tl = 0, ar = 31, d1r = 12, d2r = 8, d1l = 6, rr = 10)), alg = 0, fb = 0, sweep = Triple(24, 0, 40)),
        5 to ins(listOf(op(dt1 = 1, mul = 1, tl = 28, ks = 1, ar = 28, d1r = 6, d2r = 2, d1l = 4, rr = 6), op(mul = 2, tl = 34, ar = 26, d1r = 4, d1l = 3, rr = 6),
            op(dt1 = 5, mul = 1, tl = 30, ar = 26, d1r = 5, d1l = 3, rr = 6), op(mul = 1, tl = 4, ar = 24, d1r = 3, d2r = 1, d1l = 2, rr = 7)),
            alg = 0, fb = 7),
        6 to ins(listOf(op(mul = 0, tl = 24, ar = 31, d1r = 8, d1l = 5, rr = 8), op(dt2 = 1, mul = 1, tl = 30, ar = 31, d1r = 6, d1l = 4, rr = 8),
            op(mul = 1, tl = 26, ks = 2, ar = 31, d1r = 7, d1l = 4, rr = 8), op(mul = 1, tl = 2, ar = 31, d1r = 4, d2r = 3, d1l = 3, rr = 9)),
            alg = 1, fb = 6),
        7 to ins(listOf(op(mul = 15, tl = 40, ar = 31, d1r = 10, d1l = 8, rr = 7), op(dt1 = 2, mul = 3, tl = 32, ar = 30, d1r = 6, d1l = 5, rr = 7),
            op(dt2 = 2, mul = 1, tl = 28, ar = 30, d1r = 5, d1l = 4, rr = 7), op(mul = 1, tl = 6, ks = 3, ar = 28, d1r = 4, d1l = 3, rr = 7)),
            alg = 2, fb = 5),
        8 to ins(listOf(op(mul = 1, tl = 30, ar = 24, d1r = 3, d1l = 2, rr = 6), op(dt1 = 6, mul = 1, tl = 36, ar = 24, d1r = 3, d1l = 2, rr = 6),
            op(dt2 = 3, mul = 2, tl = 34, ar = 24, d1r = 3, d1l = 2, rr = 6), op(mul = 1, tl = 5, ar = 22, d1r = 2, d1l = 1, rr = 6)),
            alg = 3, fb = 4),
        9 to ins(listOf(op(mul = 4, tl = 26, ar = 31, d1r = 12, d1l = 9, rr = 9), op(mul = 1, tl = 8, ar = 31, d1r = 9, d2r = 5, d1l = 7, rr = 9),
            op(dt1 = 1, dt2 = 1, mul = 15, tl = 20, ar = 31, d1r = 14, d1l = 10, rr = 10), op(mul = 1, tl = 10, ks = 1, ar = 31, d1r = 8, d1l = 6, rr = 9)),
            alg = 4, fb = 3),
        10 to ins(listOf(op(mul = 2, tl = 22, ar = 26, d1r = 2, d1l = 2, rr = 5), op(dt1 = 3, mul = 1, tl = 10, ar = 24, d1r = 2, d1l = 2, rr = 5),
            op(dt1 = 7, mul = 1, tl = 12, ar = 24, d1r = 2, d1l = 2, rr = 5), op(mul = 1, tl = 12, ar = 24, d1r = 2, d1l = 2, rr = 5)),
            alg = 5, fb = 2),
        11 to ins(listOf(op(mul = 1, tl = 18, ar = 31, d1r = 6, d1l = 4, rr = 6), op(mul = 1, tl = 8, ar = 31, d1r = 5, d1l = 3, rr = 6),
            op(dt2 = 1, mul = 2, tl = 14, ks = 2, ar = 31, d1r = 6, d1l = 4, rr = 6), op(mul = 0, tl = 10, ar = 31, d1r = 4, d1l = 3, rr = 6)),
            alg = 6, fb = 1),
        12 to ins(listOf(op(mul = 1, tl = 14, ar = 20, d1r = 1, d1l = 1, rr = 4), op(dt1 = 2, mul = 2, tl = 16, ar = 20, d1r = 1, d1l = 1, rr = 4),
            op(mul = 4, tl = 20, ar = 20, d1r = 2, d1l = 2, rr = 4), op(dt2 = 2, mul = 8, tl = 24, ks = 3, ar = 20, d1r = 3, d1l = 3, rr = 4)),
            alg = 7, fb = 0),
    )

    /** `opm_golden.spike_song()`: `opm_song.test_song()` without its explicit `000`. */
    fun spike(): Song {
        val p = HashMap<String, Map<Int, Cell>>()
        // Row 4's explicit 000 (arpeggio off) is the one cell Thor Tracker cannot store; opm_golden.py drops it too.
        p["lead0"] = mapOf(0 to c(60, 5, '0' to 0x37, 'I' to 0x20), 6 to c(62, null, '0' to 0x47, 'M' to 0x23),
            10 to c(64, null, 'E' to 0x03, 'M' to 0x1F, 'I' to 0x7F, 'H' to 0x52), 13 to c(OFF), 14 to c(65, 5, 'P' to 0x88))
        p["bass0"] = mapOf(0 to c(36, 6, '1' to 0x08), 3 to c(null, null, '1' to 0x00), 4 to c(36, null, '2' to 0x10),
            6 to c(null, null, '2' to 0x00), 8 to c(43, null, 'P' to 0x70), 12 to c(41), 15 to c(OFF))
        p["pad0"] = mapOf(0 to c(60, 3), 8 to c(57, null, '8' to 0x01, 'H' to 0x73), 15 to c(OFF))
        p["keys0"] = mapOf(0 to c(64, 7, '4' to 0x34), 4 to c(null, null, '4' to 0x30), 6 to c(67, null, 'C' to 0x60),
            10 to c(65, null, 'A' to 0x04), 14 to c(OFF, null, 'A' to 0x00))
        p["brass0"] = mapOf(0 to c(48, 8, 'C' to 0x50, '8' to 0x10), 8 to c(50, null, '8' to 0x11, 'A' to 0x30),
            12 to c(OFF, null, 'A' to 0x00))
        p["kick"] = mapOf(0 to c(36, 4), 4 to c(36, 4), 8 to c(36, 4), 12 to c(36, 4), 14 to c(38, 4, 'G' to 0x02))
        p["snare"] = mapOf(4 to c(50, 9), 12 to c(50, 9, 'S' to 0x03), 15 to c(52, 9, 'G' to 0x01, 'S' to 0x04))
        p["hat"] = mapOf(0 to c(84, NOISE_SLOT), 2 to c(86, NOISE_SLOT, 'S' to 0x02), 4 to c(84, NOISE_SLOT, 'N' to 0x10),
            6 to c(86, NOISE_SLOT, 'S' to 0x02), 8 to c(84, NOISE_SLOT), 10 to c(86, NOISE_SLOT, 'S' to 0x02),
            12 to c(84, NOISE_SLOT, 'N' to 0x18), 14 to c(86, NOISE_SLOT, 'S' to 0x02))
        p["lead1"] = mapOf(0 to c(72, 10, '3' to 0x00), 2 to c(74, null, '3' to 0x18), 4 to c(77), 8 to c(null, null, '3' to 0x00),
            10 to c(79, null, '4' to 0x26), 16 to c(OFF), 18 to c(72, 10, '1' to 0x04), 22 to c(null, null, '2' to 0x06),
            26 to c(null, null, '2' to 0x00), 28 to c(76, null, 'P' to 0x90), 31 to c(OFF))
        p["bass1"] = mapOf(0 to c(36, 11, 'H' to 0x41), 4 to c(null, null, 'H' to 0x22), 8 to c(43), 12 to c(41, null, 'C' to 0x40),
            16 to c(36, null, 'C' to 0x7F), 20 to c(38, null, 'A' to 0x01), 24 to c(40, null, 'A' to 0x10),
            28 to c(OFF, null, 'A' to 0x00))
        p["pad1"] = mapOf(0 to c(64, 3, 'L' to 0xC0, 'T' to 0x40), 8 to c(null, null, 'V' to 0x50, 'W' to 0x00),
            16 to c(67, null, 'W' to 0x01), 24 to c(null, null, 'W' to 0x03, 'L' to 0x80),
            30 to c(OFF, null, 'W' to 0x02, 'T' to 0x10, 'V' to 0x20))
        p["keys1"] = mapOf(2 to c(55, 12), 10 to c(52, 12, 'I' to 0x40), 18 to c(50, 7, '8' to 0x01), 26 to c(48, 1))
        p["chords1"] = mapOf(4 to c(60, 8, '0' to 0x47), 12 to c(62, null, '0' to 0x38), 20 to c(64), 28 to c(OFF))
        p["kick1"] = mapOf(0 to c(36, 4), 8 to c(36, 4), 16 to c(36, 4), 24 to c(36, 4), 28 to c(36, 4, 'G' to 0x03))
        p["stab1"] = mapOf(5 to c(60, 9, 'E' to 0x05), 13 to c(62, 9, 'E' to 0x00, '8' to 0x11), 21 to c(64, 5, 'M' to 0x40),
            29 to c(65, 6, 'S' to 0x02))
        p["noise1"] = mapOf(0 to c(84, NOISE_SLOT, 'N' to 0x08), 4 to c(null, null, 'N' to 0x14), 8 to c(86, NOISE_SLOT),
            12 to c(60, 1), 16 to c(84, NOISE_SLOT, 'N' to 0x1F, 'C' to 0x60), 20 to c(86, NOISE_SLOT, 'S' to 0x01),
            24 to c(84, NOISE_SLOT), 28 to c(86, NOISE_SLOT, 'G' to 0x02))
        p["o2lead"] = mapOf(0 to c(60, 5, 'F' to 0x04, '4' to 0x48), 4 to c(62, null, '1' to 0x20), 8 to c(64, null, 'F' to 0x05),
            12 to c(65, null, 'D' to 0x02, '1' to 0x00))
        p["o2csm"] = mapOf(0 to c(null, null, 'X' to 0x01, 'Q' to 0xF0), 4 to c(null, null, 'Q' to 0xC8), 10 to c(null, null, 'X' to 0x00))
        p["o2bell"] = mapOf(0 to c(72, 7, '8' to 0x10), 6 to c(76, 7, '8' to 0x01, 'P' to 0x90), 11 to c(OFF))
        p["o3a"] = mapOf(0 to c(55, 10, 'C' to 0x40), 2 to c(57, null, 'C' to 0x7F, 'G' to 0x01), 6 to c(OFF), 8 to c(59, 11),
            16 to c(60, 5, '4' to 0x13), 23 to c(OFF, null, 'B' to 0x01))
        p["o3b"] = mapOf(2 to c(64, 6, 'H' to 0x31), 9 to c(OFF), 12 to c(62, 8), 20 to c(60, 12, 'P' to 0x40))
        p["o3c"] = mapOf(0 to c(36, 4, '8' to 0x10), 6 to c(36, 4, '8' to 0x01), 12 to c(43, 4), 18 to c(36, 4, '8' to 0x11, 'S' to 0x01))
        p["o3n"] = mapOf(2 to c(84, NOISE_SLOT, 'N' to 0x04, 'E' to 0x07, 'I' to 0x10, 'M' to 0x4F), 10 to c(OFF), 14 to c(86, NOISE_SLOT))
        val orders = listOf(
            Order(16, listOf("lead0", "bass0", "pad0", "keys0", "brass0", "kick", "snare", "hat")),
            Order(32, listOf("lead1", "bass1", "pad1", "keys1", "chords1", "kick1", "stab1", "noise1")),
            Order(16, listOf("o2lead", "bass0", "o2csm", "o2bell", null, "kick", "snare", "hat")),
            Order(24, listOf("o3a", "o3b", null, null, null, "o3c", null, "o3n")),
        )
        return song(6, orders, p, testInstruments(), loop = 1, lfoRate = 0x9C, lfoWave = 2, amd = 0x18, pmd = 0x24,
            csm = false, timerA = 0x1F1)
    }

    /**
     * `opm_golden.features_song()`, with one more order parked between the first and second: the arrangement drops it
     * and renumbers the loop and every `Bxx`, which the script's song states directly.
     */
    fun features(): Song {
        val i = mapOf(
            1 to ins(listOf(op(dt1 = 1, mul = 4, tl = 14, ks = 1, ar = 24, d1r = 1, d1l = 2, rr = 5),
                op(dt1 = 6, mul = 3, tl = 30, ks = 3, ar = 28, am = true, d1r = 2, dt2 = 3, d2r = 31, d1l = 3, rr = 5),
                op(mul = 0, tl = 28, ar = 26, d1r = 2, dt2 = 1, d1l = 3, rr = 5),
                op(mul = 15, tl = 6, ar = 31, am = true, d1r = 31, d2r = 4, d1l = 15, rr = 1)),
                alg = 4, fb = 6, pms = 7, ams = 3),
            2 to ins(listOf(op(tl = 127), op(tl = 127), op(tl = 127), op(tl = 0, ar = 31, d1r = 20, d1l = 15, rr = 15)),
                alg = 7, noise = true, nfrq = 31),
            3 to ins(listOf(op(mul = 2, tl = 20, ar = 31, d1r = 5, d1l = 6, rr = 6), op(mul = 1, tl = 24, ar = 31, d1r = 4, d1l = 5, rr = 6),
                op(mul = 1, tl = 127), op(mul = 1, tl = 3, ar = 31, d1r = 4, d1l = 5, rr = 6)),
                alg = 0, fb = 4, sweep = Triple(-12, 5, 3)),
            4 to ins(listOf(op(mul = 1, tl = 127), op(mul = 15, tl = 0, dt2 = 2), op(mul = 11, tl = 1, ks = 2), op(mul = 13, tl = 2, dt1 = 4)),
                alg = 5, fb = 0, sweep = Triple(96, -96, 255)),
            64 to ins(listOf(op(tl = 127), op(tl = 127), op(tl = 127), op(tl = 0)), alg = 7),
        )
        val p = HashMap<String, Map<Int, Cell>>()
        p["a0"] = mapOf(0 to c(3, 1, '4' to 0x22), 8 to c(98, null), 16 to c(OFF, null, 'C' to 0x10), 40 to c(48, 1))
        p["a1"] = mapOf(0 to c(48, 4, 'E' to 0x07, 'M' to 0x4F, 'I' to 0x3F, 'H' to 0x73), 32 to c(OFF))
        p["a2"] = mapOf(
            0 to c(60, 3, 'C' to 0x20, '8' to 0x11, '4' to 0x37, 'P' to 0x90),
            // Blank columns before used ones are left out of the image.
            1 to Cell().withEffect(2, code('I'), 0x10),
            2 to Cell().withEffect(1, code('8'), 0x10).withEffect(3, code('A'), 0x0F),
            63 to c(OFF, null, 'A' to 0x00),
        )
        p["a3"] = mapOf(5 to c(null, 64), 6 to c(72, null, '0' to 0x47))
        p["a6"] = mapOf(0 to c(55, 1, 'L' to 0xFF, 'W' to 0x03), 4 to c(57, null, 'T' to 0x7F, 'V' to 0x7F),
            12 to c(59, null, 'Q' to 0x80, 'X' to 0x01))
        p["a7"] = mapOf(0 to c(84, 2, 'N' to 0x1F), 8 to c(null, null, 'N' to 0x00), 16 to c(86, 2, 'S' to 0x01), 24 to c(60, 64))
        // Document orders: 0, parked 1, 2, 3; B03 = document order 3, which plays as order 2.
        p["b0"] = mapOf(0 to c(50, 3, 'G' to 0x01), 6 to c(52, 1), 15 to c(null, null, 'B' to 0x03, 'D' to 0x00))
        p["b5"] = mapOf(3 to c(64, 4, 'G' to 0x03, 'S' to 0x02), 10 to c(64, null, '3' to 0x10), 12 to c(67, null, '1' to 0x04, '2' to 0x00))
        p["c4"] = mapOf(0 to c(36, 3, 'F' to 0x04, 'B' to 0x02))
        p["parked"] = mapOf(0 to c(40, 3, 'B' to 0x00))
        val orders = listOf(
            Order(64, listOf("a0", "a1", "a2", "a3", null, null, "a6", "a7")),
            Order(8, listOf("parked", null, null, null, null, null, null, null)),
            Order(20, listOf("b0", null, null, null, null, "b5", null, null)),
            Order(1, listOf(null, null, null, null, "c4", null, null, null)),
        )
        return song(5, orders, p, i, loop = 2, multispeed = 2, lfoRate = 0xB7, lfoWave = 2, amd = 0x33, pmd = 0x45,
            csm = true, timerA = 1023).copy(parkedOrders = listOf(1))
    }
}
