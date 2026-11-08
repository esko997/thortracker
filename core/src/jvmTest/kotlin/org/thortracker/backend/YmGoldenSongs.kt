package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.ym2612.YmFmInstrument
import org.thortracker.system.ym2612.YmInstrument
import org.thortracker.system.ym2612.YmOperator
import org.thortracker.system.ym2612.YmPsgInstrument
import org.thortracker.system.ym2612.YmSound
import org.thortracker.system.ym2612.YmSweep

/**
 * The songs of `tools/audio/ym/ym_golden.py` written in Thor Tracker's model, for [YmGoldenTest]: the native host
 * test's song (`ym_song.test_song`) and the features song. Kept in the reference script's shape (instruments by slot,
 * patterns by name, orders naming a pattern per channel) so the two read alike.
 */
object YmGoldenSongs {
    private const val OFF = 0xFE

    private fun op(
        dt: Int = 0, mul: Int = 1, tl: Int = 0, rs: Int = 0, ar: Int = 31, am: Boolean = false, d1r: Int = 0, d2r: Int = 0,
        d1l: Int = 0, rr: Int = 15, ssg: Int = 0,
    ) = YmOperator(dt, mul, tl, rs, ar, am, d1r, d2r, d1l, rr, ssg)

    private fun fm(ops: List<YmOperator>, alg: Int = 0, fb: Int = 0, ams: Int = 0, fms: Int = 0, sweep: Triple<Int, Int, Int> = Triple(0, 0, 0)) =
        YmFmInstrument("", alg, fb, ams, fms, ops, YmSweep(sweep.first, sweep.second, sweep.third))

    private fun env(values: List<Int>, loop: Int? = null, release: Int? = null) = NesEnvelope(values, loop ?: -1, release ?: -1)

    private fun psg(
        volume: NesEnvelope? = null, arpeggio: NesEnvelope? = null, pitch: NesEnvelope? = null, relative: Boolean = false,
        noise: Int = 0, sweep: Triple<Int, Int, Int> = Triple(0, 0, 0),
    ) = YmPsgInstrument("", volume ?: NesEnvelope(), arpeggio ?: NesEnvelope(), pitch ?: NesEnvelope(), relative, noise,
        YmSweep(sweep.first, sweep.second, sweep.third))

    /** A cell as the script writes it: note (null none, [OFF]), instrument (null none), effects in columns 0... (null = blank). */
    private fun c(note: Int? = null, inst: Int? = null, vararg fx: Pair<Char, Int>?): Cell {
        var cell = Cell(
            note = when (note) { null -> Cell.EMPTY; OFF -> Cell.CUT; else -> note },
            instrument = inst ?: 0,
        )
        fx.forEachIndexed { i, e -> if (e != null) cell = cell.withEffect(i, code(e.first), e.second) }
        return cell
    }

    private fun code(letter: Char) = (0..EffectSpec.MAX_CODE).first { EffectSpec.charOf(it) == letter }

    private class Order(val rows: Int, val patterns: List<String?>)

    private fun song(
        speed: Int, orders: List<Order>, patterns: Map<String, Map<Int, Cell>>, instruments: Map<Int, YmInstrument>,
        loop: Int, region: Int = 0, lfo: Int = 0,
    ): Song = Song(
        title = "Golden",
        ticksPerRow = speed,
        loopOrder = loop,
        patterns = orders.mapIndexed { o, order ->
            Pattern("Order $o", (0 until 10).map { ch ->
                val rows = order.patterns[ch]?.let { patterns.getValue(it) } ?: emptyMap()
                List(Songs.ROWS) { rows[it] ?: Cell.BLANK }
            }, length = order.rows)
        },
        orders = orders.indices.toList(),
        sound = YmSound(instruments = List(64) { instruments[it + 1] }, pal = region == 1, lfo = lfo),
    )

    /**
     * `ym_golden.features_song()`, with one more order parked between the first and second: the arrangement drops it
     * and renumbers the loop and every `Bxx`, which the script's song states directly.
     */
    fun features(): Song {
        val shared = env(listOf(15, 12, 9, 6, 3, 0), loop = 1, release = 4)
        val i = mapOf(
            1 to fm(listOf(op(dt = 7, mul = 15, tl = 127, rs = 3, ar = 0, am = true, d1r = 31, d2r = 31, d1l = 15, rr = 0, ssg = 15),
                op(dt = 4, mul = 0, tl = 0, rs = 0, ar = 31, d1r = 0, d2r = 0, d1l = 0, rr = 15, ssg = 8),
                op(dt = 1, mul = 7, tl = 64, rs = 2, ar = 12, am = true, d1r = 5, d2r = 1, d1l = 7, rr = 7, ssg = 10),
                op(dt = 5, mul = 3, tl = 20, rs = 1, ar = 20, d1r = 9, d2r = 4, d1l = 3, rr = 9, ssg = 13)),
                alg = 7, fb = 7, ams = 3, fms = 7, sweep = Triple(96, -96, 255)),
            2 to fm(listOf(op(mul = 2, tl = 30), op(), op(mul = 3, tl = 40, ssg = 12), op()), alg = 4, fb = 0, sweep = Triple(-12, 5, 3)),
            3 to psg(volume = shared, arpeggio = env(listOf(0, -12, 12, -64, 63), loop = 0), pitch = env(listOf(1, -1, 0)),
                relative = true, noise = 7),
            4 to psg(noise = 0),
            5 to psg(volume = env(listOf(15, 12, 9, 6, 3, 0), loop = 1, release = 4), arpeggio = env(listOf(0, 7), loop = 1), noise = 2),
            63 to psg(volume = env(List(256) { it % 16 }, loop = 255), noise = 6),
            64 to fm(listOf(op(mul = 1, tl = 40), op(mul = 2, tl = 36), op(mul = 1, tl = 50), op(mul = 1, tl = 0)), alg = 3, fb = 3),
        )
        val p = HashMap<String, Map<Int, Cell>>()
        p["a0"] = mapOf(0 to c(0, 1, '4' to 0x22), 8 to c(95), 16 to c(OFF, null, 'C' to 0x10), 40 to c(48, 64),
            63 to c(null, null, 'L' to 0x0F))
        p["a1"] = mapOf(0 to c(48, 2, 'M' to 0x4F, 'E' to 0x03, 'I' to 0x7F, '8' to 0x10), 32 to c(OFF))
        p["a5"] = mapOf(
            0 to c(60, 64, 'C' to 0x7F, '8' to 0x01, 'P' to 0x90, 'A' to 0x0F),
            // Blank columns before used ones are left out of the image.
            1 to c(null, null, null, null, 'I' to 0x10),
            2 to c(null, null, null, '8' to 0x11, null, 'A' to 0x00),
            63 to c(OFF, null, 'S' to 0x00),
        )
        p["a6"] = mapOf(0 to c(45, 3, 'C' to 0x0F, '0' to 0x47), 4 to c(57, null, '3' to 0x10), 12 to c(95, 5, 'P' to 0x70),
            13 to c(null, 4))
        p["a8"] = mapOf(0 to c(72, 63, 'C' to 0x00, 'G' to 0x01, 'S' to 0x03, '4' to 0xFF))
        p["a9"] = mapOf(0 to c(36, 4, 'N' to 0x00), 4 to c(36, 3, 'N' to 0x13), 8 to c(40, 5), 12 to c(OFF),
            16 to c(24, 63, 'N' to 0x12, 'C' to 0x08, 'A' to 0x01, 'L' to 0x00))
        // Document orders: 0, parked 1, 2, 3; B03 = document order 3, which plays as order 2.
        p["b0"] = mapOf(0 to c(50, 2), 6 to c(52, 1), 15 to c(null, null, 'B' to 0x03, 'D' to 0x00))
        p["b7"] = mapOf(3 to c(64, 3, 'G' to 0x03, 'S' to 0x02), 10 to c(64, null, '3' to 0x10), 12 to c(67, null, '1' to 0x04, '2' to 0x00))
        p["c4"] = mapOf(0 to c(36, 2, 'F' to 0x04, 'B' to 0x02))
        p["parked"] = mapOf(0 to c(40, 2, 'B' to 0x00))
        val orders = listOf(
            Order(64, listOf("a0", "a1", null, null, null, "a5", "a6", null, "a8", "a9")),
            Order(8, listOf("parked", null, null, null, null, null, null, null, null, null)),
            Order(20, listOf("b0", null, null, null, null, null, null, "b7", null, null)),
            Order(1, listOf(null, null, null, null, "c4", null, null, null, null, null)),
        )
        return song(5, orders, p, i, loop = 2, region = 1, lfo = 0x0F).copy(parkedOrders = listOf(1))
    }
}
