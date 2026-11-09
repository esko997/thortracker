package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.PcmData
import org.thortracker.system.ym2612.YmFm3Mode
import org.thortracker.system.ym2612.YmFmInstrument
import org.thortracker.system.ym2612.YmInstrument
import org.thortracker.system.ym2612.YmOperator
import org.thortracker.system.ym2612.YmPcmInstrument
import org.thortracker.system.ym2612.YmPcmMapping
import org.thortracker.system.ym2612.YmPcmSample
import org.thortracker.system.ym2612.YmPsgInstrument
import org.thortracker.system.ym2612.YmRules
import org.thortracker.system.ym2612.YmSound
import org.thortracker.system.ym2612.YmSweep

/**
 * The songs of `tools/audio/ym/ym_golden.py` written in Thor Tracker's model, for [YmGoldenTest]: the native host
 * test's song (`ym_song.test_song`), the features song and the XGM2 song (FM 3 special mode, PCM kits and the compiled
 * bank). Kept in the reference script's shape (instruments by slot, patterns by name, orders naming a pattern per
 * channel: FM 1-6, PSG 1-3, Noise, PCM 1-3) so the two read alike.
 */
object YmGoldenSongs {
    private const val OFF = 0xFE

    private fun op(
        dt: Int = 0, mul: Int = 1, tl: Int = 0, rs: Int = 0, ar: Int = 31, am: Boolean = false, d1r: Int = 0, d2r: Int = 0,
        d1l: Int = 0, rr: Int = 15, ssg: Int = 0,
    ) = YmOperator(dt, mul, tl, rs, ar, am, d1r, d2r, d1l, rr, ssg)

    private fun fm(ops: List<YmOperator>, alg: Int = 0, fb: Int = 0, ams: Int = 0, fms: Int = 0, sweep: Triple<Int, Int, Int> = Triple(0, 0, 0),
                   ch3: List<Int> = listOf(0, 0, 0, 0)) =
        YmFmInstrument("", alg, fb, ams, fms, ops, YmSweep(sweep.first, sweep.second, sweep.third), ch3)

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
        loop: Int, region: Int = 0, lfo: Int = 0, fm3: YmFm3Mode = YmFm3Mode.NORMAL, samples: List<YmPcmSample> = emptyList(),
    ): Song = Song(
        title = "Golden",
        ticksPerRow = speed,
        loopOrder = loop,
        patterns = orders.mapIndexed { o, order ->
            Pattern("Order $o", (0 until YmRules.CHANNELS).map { ch ->
                val rows = order.patterns.getOrNull(ch)?.let { patterns.getValue(it) } ?: emptyMap()
                List(Songs.ROWS) { rows[it] ?: Cell.BLANK }
            }, length = order.rows)
        },
        orders = orders.indices.toList(),
        sound = YmSound(instruments = List(64) { instruments[it + 1] }, pal = region == 1, lfo = lfo, fm3Mode = fm3, pcmSamples = samples),
    )

    /** `ym_song.test_song()`: the native host test's song (all 8 algorithms, LFO, SSG-EG, every effect, PSG envelopes, noise modes). */
    fun spike(): Song {
        val i = mapOf(
            1 to fm(listOf(op(tl = 127), op(tl = 127), op(tl = 127), op(rr = 6)), alg = 7, fb = 0),
            2 to psg(volume = env(listOf(15, 14, 14, 13, 13, 12, 12, 11, 11, 10, 10, 9, 9, 8, 8, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0, 0), loop = 0, release = 1), noise = 0),
            3 to fm(listOf(op(dt = 3, tl = 35, d1r = 12, d2r = 3, d1l = 4, rr = 8), op(dt = 7, mul = 2, tl = 44, ar = 28, d1r = 10, d2r = 2, d1l = 3, rr = 8), op(tl = 26, d1r = 8, d2r = 2, d1l = 5, rr = 8), op(dt = 1, tl = 2, rs = 1, d1r = 6, d2r = 1, d1l = 2, rr = 9)), alg = 0, fb = 5),
            4 to fm(listOf(op(dt = 2, tl = 32, ar = 24, d1r = 6, d2r = 1, d1l = 2, rr = 6), op(dt = 6, tl = 38, ar = 22, d1r = 5, d2r = 1, d1l = 2, rr = 6), op(mul = 2, tl = 28, ar = 26, d1r = 4, d2r = 1, d1l = 3, rr = 6), op(tl = 4, ar = 20, d1r = 3, d1l = 1, rr = 7)), alg = 1, fb = 4),
            5 to fm(listOf(op(dt = 1, mul = 3, tl = 40, d1r = 4, d1l = 2, rr = 5), op(dt = 5, tl = 48, am = true, d1r = 5, d2r = 1, d1l = 3, rr = 5), op(mul = 2, tl = 36, d1r = 3, d1l = 2, rr = 5), op(dt = 2, tl = 6, ar = 29, am = true, d1r = 2, d1l = 1, rr = 6)), alg = 2, fb = 6, ams = 2, fms = 3),
            6 to fm(listOf(op(mul = 5, tl = 34, d1r = 14, d2r = 6, d1l = 6, rr = 9, ssg = 8), op(dt = 4, tl = 42, d1r = 12, d2r = 5, d1l = 5, rr = 9), op(dt = 3, mul = 3, tl = 38, d1r = 10, d2r = 4, d1l = 4, rr = 9, ssg = 14), op(tl = 5, rs = 2, d1r = 8, d2r = 3, d1l = 3, rr = 10)), alg = 3, fb = 3),
            7 to fm(listOf(op(dt = 3, mul = 14, tl = 46, d1r = 16, d2r = 4, d1l = 8, rr = 7), op(tl = 8, d1r = 6, d2r = 2, d1l = 4, rr = 7), op(dt = 7, tl = 36, d1r = 8, d2r = 3, d1l = 5, rr = 7), op(dt = 3, tl = 10, d1r = 5, d2r = 2, d1l = 3, rr = 7)), alg = 4, fb = 2),
            8 to fm(listOf(op(mul = 2, tl = 38, d1r = 2, d1l = 1, rr = 6), op(dt = 1, tl = 16, ar = 28, d1r = 1, d1l = 1, rr = 6), op(dt = 5, mul = 2, tl = 20, ar = 28, am = true, d1r = 1, d1l = 1, rr = 6), op(mul = 4, tl = 24, ar = 28, d1r = 1, d1l = 1, rr = 6)), alg = 5, fb = 6, ams = 1, fms = 2),
            9 to fm(listOf(op(dt = 2, mul = 7, tl = 36, d1r = 7, d2r = 3, d1l = 6, rr = 5), op(mul = 3, tl = 12, d1r = 6, d2r = 3, d1l = 6, rr = 5), op(dt = 6, mul = 0, tl = 24, d1r = 5, d2r = 2, d1l = 5, rr = 5), op(tl = 16, d1r = 4, d2r = 2, d1l = 4, rr = 5)), alg = 6, fb = 1),
            10 to fm(listOf(op(tl = 20, rr = 7), op(dt = 1, mul = 2, tl = 24, rr = 7), op(mul = 4, tl = 30, am = true, rr = 7), op(dt = 2, mul = 8, tl = 36, rr = 7)), alg = 7, fb = 7, ams = 3, fms = 5),
            11 to fm(listOf(op(tl = 48, d1r = 20, d2r = 10, d1l = 15), op(tl = 127), op(tl = 127), op(d1r = 12, d2r = 8, d1l = 6, rr = 12)), alg = 0, fb = 0, sweep = Triple(36, 0, 64)),
            12 to psg(volume = env(listOf(4, 8, 12, 15, 13, 11, 9, 8, 6, 4, 2, 0), loop = 7, release = 8), pitch = env(listOf(0, 1, 2, 1, 0, -1, -2, -1), loop = 0), noise = 0),
            13 to psg(volume = env(listOf(3, 6, 9, 10, 8, 6, 5, 4, 3, 2, 1, 0), loop = 3, release = 4), arpeggio = env(listOf(0, 4, 7), loop = 0), noise = 0),
            14 to psg(volume = env(listOf(15, 13, 11, 9, 7, 5, 3, 1, 0)), arpeggio = env(listOf(12, 0)), noise = 0),
            15 to psg(volume = env(listOf(15, 12, 9, 7, 5, 4)), noise = 5),
            16 to psg(volume = env(listOf(12, 8, 4, 0)), noise = 4),
            17 to psg(volume = env(listOf(8, 12, 11, 9, 6, 3, 0), loop = 1, release = 3), noise = 2),
            18 to psg(volume = env(listOf(15, 13, 11, 9, 8, 7, 6)), pitch = env(listOf(-4), loop = 0), relative = true, noise = 7, sweep = Triple(12, -12, 24)),
            19 to psg(volume = env(listOf(15, 14, 14, 13, 13, 12, 12, 11, 11, 10, 10, 9, 9, 8, 8, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0, 0), loop = 0, release = 1), noise = 0, sweep = Triple(24, 0, 32)),
            20 to psg(noise = 0),
        )
        val p = HashMap<String, Map<Int, Cell>>()
        p["fm0_bass"] = mapOf(0 to c(24, 3), 1 to c(24), 2 to c(24, null, '1' to 0x06), 4 to c(null, null, '1' to 0x00), 6 to c(31, null, null, '2' to 0x0C), 8 to c(null, null, '2' to 0x00), 10 to c(27, null, 'P' to 0x90), 12 to c(29), 14 to c(OFF), 15 to c(24, 3, 'S' to 0x03))
        p["fm1_brass"] = mapOf(0 to c(48, 4), 4 to c(52, null, '4' to 0x36), 8 to c(55, null, '4' to 0x30), 12 to c(OFF), 13 to c(53, 4, 'E' to 0x07, 'C' to 0x60))
        p["fm2_lead"] = mapOf(0 to c(60, 5, '3' to 0x00), 4 to c(null, null, '3' to 0x10), 5 to c(64), 8 to c(67), 10 to c(null, null, '3' to 0x00), 11 to c(65, null, 'I' to 0x60, 'M' to 0x23), 15 to c(OFF))
        p["fm3_clav"] = mapOf(0 to c(55, 6, '0' to 0x37), 2 to c(null, null, '0' to 0x47), 4 to c(57), 6 to c(55, null, '0' to 0x0C, 'P' to 0x70), 8 to c(OFF), 10 to c(59, 6, 'A' to 0x04), 14 to c(null, null, 'A' to 0x00, 'C' to 0x50))
        p["fm4_ep"] = mapOf(0 to c(64, 7, '8' to 0x10), 4 to c(67, null, '8' to 0x00), 6 to c(null, null, '8' to 0x01), 8 to c(69, null, '8' to 0x11), 12 to c(OFF))
        p["fm5_organ"] = mapOf(0 to c(43, 8, 'L' to 0x0E), 8 to c(45, null, 'G' to 0x02), 12 to c(47, 8, null, null, 'G' to 0x07))
        p["psg_lead"] = mapOf(0 to c(60, 12), 3 to c(OFF), 4 to c(62, null, '0' to 0x37), 8 to c(64, null, '4' to 0x24), 11 to c(OFF), 12 to c(67, 14), 14 to c(OFF))
        p["psg_pad"] = mapOf(0 to c(48, 13, 'C' to 0x0C), 8 to c(52, null, 'A' to 0x02), 12 to c(null, null, 'A' to 0x10), 15 to c(OFF))
        p["psg3_bass"] = mapOf(0 to c(36, 2), 4 to c(36), 5 to c(36), 8 to c(41, null, '1' to 0x04), 10 to c(null, null, '1' to 0x00), 12 to c(43, null, 'P' to 0x88))
        p["noise0"] = mapOf(0 to c(36, 16), 2 to c(36, 16), 4 to c(36, 15), 6 to c(36, 16), 8 to c(24, 18), 10 to c(OFF), 12 to c(36, 15, 'S' to 0x02), 14 to c(30, 17, 'N' to 0x03), 15 to c(OFF))
        p["kick1"] = mapOf(0 to c(24, 11), 8 to c(24, 11), 16 to c(24, 11), 24 to c(24, 11), 28 to c(26, 11, 'G' to 0x03))
        p["bell1"] = mapOf(0 to c(72, 9), 6 to c(76, null, 'M' to 0x47), 12 to c(79, 9, '8' to 0x01), 16 to c(77, 9, 'I' to 0x20), 22 to c(OFF), 24 to c(74, 9, '8' to 0x11, 'S' to 0x04))
        p["add1"] = mapOf(0 to c(48, 10, 'C' to 0x70), 8 to c(50, null, 'E' to 0x02), 16 to c(52, null, 'I' to 0x7F), 24 to c(53, null, 'A' to 0x08), 30 to c(OFF, null, 'A' to 0x00))
        p["clav1"] = mapOf(2 to c(55, 6), 3 to c(55), 10 to c(58, 6, '2' to 0x04), 14 to c(null, null, '2' to 0x00), 18 to c(60, 4), 26 to c(62, 4, '4' to 0x58), 31 to c(OFF))
        p["ep1"] = mapOf(4 to c(64, 7), 12 to c(66, 5, '1' to 0x02), 20 to c(null, null, '3' to 0x00), 28 to c(OFF))
        p["fm6_1"] = mapOf(0 to c(36, 8), 8 to c(OFF), 10 to c(38, 3, '0' to 0x0C), 16 to c(36, 8, 'L' to 0x00), 24 to c(40, 8, 'L' to 0x0B))
        p["psg1_1"] = mapOf(0 to c(60, 19), 4 to c(64, 19), 8 to c(67, 12, '3' to 0x00), 12 to c(null, null, '3' to 0x20), 13 to c(72), 16 to c(69), 20 to c(OFF, null, '3' to 0x00), 24 to c(65, 14, 'C' to 0x0A), 28 to c(65, 14, 'C' to 0x0F))
        p["psg2_1"] = mapOf(0 to c(55, 13), 16 to c(57, 13, 'P' to 0x60), 31 to c(OFF))
        p["psg3_1"] = mapOf(0 to c(45, 2, '4' to 0x4F), 8 to c(45, 2, '4' to 0x40), 16 to c(48, 2, 'S' to 0x03), 24 to c(43, 2))
        p["noise1"] = mapOf(0 to c(36, 16), 2 to c(36, 16), 4 to c(36, 15), 6 to c(36, 16), 9 to c(26, 18), 11 to c(OFF), 12 to c(36, 15), 14 to c(36, 16, 'A' to 0x01), 16 to c(null, null, 'A' to 0x00, 'C' to 0x0F), 20 to c(36, 15), 22 to c(36, 17), 23 to c(36, 17, 'N' to 0x11), 26 to c(28, 17, 'N' to 0x03), 28 to c(36, 15), 30 to c(OFF))
        p["ep2"] = mapOf(0 to c(60, 7, 'F' to 0x04), 4 to c(63), 8 to c(65, null, 'F' to 0x09), 12 to c(67, null, 'D' to 0x02))
        p["lead2"] = mapOf(0 to c(72, 5, '4' to 0x48, '8' to 0x10), 4 to c(74, null, '1' to 0x20), 8 to c(null, null, '1' to 0x00, 'L' to 0x08))
        p["bell2"] = mapOf(0 to c(79, 9, 'E' to 0x05, 'M' to 0x11, 'I' to 0x40), 6 to c(OFF))
        p["fm6_2"] = mapOf(0 to c(36, 10), 8 to c(36, 10, 'G' to 0x02, 'F' to 0x05), 12 to c(null, null, 'B' to 0x03))
        p["psg3_2"] = mapOf(0 to c(50, 2, '2' to 0x08), 6 to c(null, null, '2' to 0x00), 8 to c(53, null, null, null, null, '0' to 0x47))
        p["o3a"] = mapOf(0 to c(60, 10), 2 to c(55, 4, 'C' to 0x60), 4 to c(57, null, 'C' to 0x7F, 'G' to 0x01), 8 to c(OFF), 10 to c(59, 6, 'E' to 0x03, 'I' to 0x50, 'M' to 0x12, '4' to 0x22), 16 to c(57, 1, 'F' to 0x07), 23 to c(OFF, null, 'B' to 0x01))
        p["o3b"] = mapOf(2 to c(36, 3, 'A' to 0x10), 9 to c(OFF), 12 to c(31, 3, 'P' to 0xA0), 20 to c(29, 3, 'S' to 0x02))
        p["o3d"] = mapOf(2 to c(48, 8, '8' to 0x01), 12 to c(50, 8, '8' to 0x10), 20 to c(OFF))
        p["o3psg1"] = mapOf(2 to c(72, 14), 3 to c(72, 14), 4 to c(74, 14, '0' to 0x37), 8 to c(76, 12, 'S' to 0x03), 12 to c(null, null, 'L' to 0x0D))
        p["o3psg2"] = mapOf(2 to c(57, 20, 'A' to 0x01), 16 to c(null, null, 'A' to 0x00), 20 to c(OFF))
        p["o3psg3"] = mapOf(4 to c(40, 13), 18 to c(OFF))
        p["o3noise"] = mapOf(2 to c(24, 15, 'N' to 0x13), 6 to c(OFF), 8 to c(36, 16), 16 to c(36, 15, 'G' to 0x02, 'S' to 0x04))
        val orders = listOf(
            Order(16, listOf("fm0_bass", "fm1_brass", "fm2_lead", "fm3_clav", "fm4_ep", "fm5_organ", "psg_lead", "psg_pad", "psg3_bass", "noise0")),
            Order(32, listOf("kick1", "bell1", "add1", "clav1", "ep1", "fm6_1", "psg1_1", "psg2_1", "psg3_1", "noise1")),
            Order(16, listOf("fm0_bass", "ep2", "lead2", null, "bell2", "fm6_2", "psg_lead", "psg_pad", "psg3_2", "noise0")),
            Order(24, listOf("o3a", "o3b", null, "o3d", null, "kick1", "o3psg1", "o3psg2", "o3psg3", "o3noise")),
        )
        return song(6, orders, p, i, loop = 1, region = 0, lfo = 0x0B)
    }

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

    /**
     * `ym_golden.xgm2_song()`: FM 3 special mode with operator offsets on two voices (one also on FM 1, which ignores
     * them), three PCM kits over three formula samples — a duplicate source and a repeated (sample, pitch, level) that
     * share variants, pitches −48..+12, levels 15, 7 and 0 (silent) — notes without an instrument, OFF, Gxx, Sxx, Fxx
     * and Lxx on the PCM lanes, and one PCM track shared by two lanes.
     * Samples: "ramp" 8000 Hz, 300 bytes, value (7 i) mod 256 as a signed byte; "ramp copy" the same bytes; "square"
     * 22050 Hz, 1000 bytes, +100 for 25 bytes then −100 for 25.
     */
    fun xgm2(): Song {
        val ramp = PcmData.of(ByteArray(300) { i -> (i * 7).toByte() })
        val square = PcmData.of(ByteArray(1000) { i -> (if ((i / 25) % 2 == 0) 100 else -100).toByte() })
        val samples = listOf(YmPcmSample("ramp", ramp, 8000), YmPcmSample("ramp copy", ramp, 8000), YmPcmSample("square", square, 22050))
        val voice = listOf(op(tl = 30), op(tl = 32, mul = 2), op(tl = 34), op(tl = 36, mul = 3))
        val i = mapOf(
            1 to fm(voice, alg = 7, ch3 = listOf(0, 448, -64, 3072)),
            2 to YmPcmInstrument("", 15, mapOf(
                36 to YmPcmMapping(0), 37 to YmPcmMapping(1), 38 to YmPcmMapping(2), 40 to YmPcmMapping(2, -12),
                41 to YmPcmMapping(2, 12), 42 to YmPcmMapping(0),
            )),
            3 to YmPcmInstrument("", 7, mapOf(36 to YmPcmMapping(2), 48 to YmPcmMapping(0, -48))),
            4 to fm(voice, alg = 4, fb = 3, ch3 = listOf(-3072, 0, 1, -1)),
            5 to YmPcmInstrument("", 0, mapOf(50 to YmPcmMapping(2, 3))),
        )
        val p = HashMap<String, Map<Int, Cell>>()
        p["fm1"] = mapOf(0 to c(60, 1), 8 to c(OFF))
        p["fm3"] = mapOf(0 to c(45, 1), 4 to c(48, null, '1' to 0x10), 8 to c(52, 4, '0' to 0x37), 12 to c(OFF))
        p["pcm1"] = mapOf(0 to c(36, 2), 2 to c(37), 4 to c(OFF), 5 to c(38, 2, 'G' to 0x02), 8 to c(40, 2, 'S' to 0x02), 10 to c(41), 12 to c(42))
        p["pcm2"] = mapOf(0 to c(36, 3), 3 to c(48, 3), 6 to c(OFF), 9 to c(null, null, 'L' to 0x08))
        p["pcm3"] = mapOf(1 to c(50, 5), 7 to c(null, null, 'F' to 0x04))
        val orders = listOf(
            Order(16, listOf("fm1", null, "fm3", null, null, null, null, null, null, null, "pcm1", "pcm2", "pcm3")),
            Order(8, listOf(null, null, null, null, null, null, null, null, null, null, "pcm2", "pcm2", null)),
        )
        return song(3, orders, p, i, loop = 0, fm3 = YmFm3Mode.SPECIAL, samples = samples)
    }
}
