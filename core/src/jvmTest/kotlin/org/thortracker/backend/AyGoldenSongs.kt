package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.ay.AyEnvelopeSettings
import org.thortracker.system.ay.AyInstrument
import org.thortracker.system.ay.AySound
import org.thortracker.system.ay.AySweep
import org.thortracker.system.ay.AyVariant
import org.thortracker.system.nes.NesEnvelope

/**
 * The songs of `tools/audio/ay/ay_golden.py` written in Thor Tracker's model, for [AyGoldenTest]: the native host
 * test's song (`ay_song.test_song`) for each variant and the features song. Kept in the reference script's shape
 * (instruments by slot, patterns by name, orders naming a pattern per channel) so the two read alike.
 */
object AyGoldenSongs {
    private const val OFF = 0xFE

    /** `ay_song.LOWEST_NOTE`: the lowest note per variant (A-0, A-0, B-0, C-0) the script's songs use. */
    val LOWEST_NOTE = intArrayOf(9, 9, 11, 0)

    private fun env(values: List<Int>, loop: Int? = null, release: Int? = null) = NesEnvelope(values, loop ?: -1, release ?: -1)

    /** `ay_song.Instrument`: absent envelopes are empty ones. */
    private fun ins(
        volume: NesEnvelope? = null, arpeggio: NesEnvelope? = null, pitch: NesEnvelope? = null, relative: Boolean = false,
        mixer: NesEnvelope? = null, noise: NesEnvelope? = null, shape: Int = 0, auto: Boolean = true, octave: Int = 0,
        period: Int = 0, sync: Boolean = false, sweep: Triple<Int, Int, Int> = Triple(0, 0, 0),
    ) = AyInstrument(
        name = "",
        volume = volume ?: NesEnvelope(),
        arpeggio = arpeggio ?: NesEnvelope(),
        pitch = pitch ?: NesEnvelope(),
        pitchRelative = relative,
        mixer = mixer ?: NesEnvelope(),
        noise = noise ?: NesEnvelope(),
        envelope = AyEnvelopeSettings(shape = shape, auto = auto, octave = octave, period = period, sync = sync),
        sweep = AySweep(sweep.first, sweep.second, sweep.third),
    )

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
        variant: AyVariant, speed: Int, orders: List<Order>, patterns: Map<String, Map<Int, Cell>>,
        instruments: Map<Int, AyInstrument>, loop: Int,
    ): Song = Song(
        title = "Golden",
        ticksPerRow = speed,
        loopOrder = loop,
        patterns = orders.mapIndexed { o, order ->
            Pattern("Order $o", (0 until 3).map { ch ->
                val rows = order.patterns[ch]?.let { patterns.getValue(it) } ?: emptyMap()
                List(Songs.ROWS) { rows[it] ?: Cell.BLANK }
            }, length = order.rows)
        },
        orders = orders.indices.toList(),
        sound = AySound(variant = variant, instruments = List(64) { instruments[it + 1] }),
    )

    /** `ay_song.test_instruments()`. */
    private fun testInstruments(): Map<Int, AyInstrument> {
        val leadVolume = env(listOf(10, 13, 15, 14, 12, 11, 10, 8, 6, 4, 2, 0), loop = 6, release = 7)
        return mapOf(
            1 to ins(),
            2 to ins(mixer = env(listOf(0)), shape = 8, auto = true, octave = 0),
            3 to ins(mixer = env(listOf(1)), shape = 10, auto = true, octave = 0, sync = true),
            4 to ins(shape = 9, auto = false, period = 0x0800),
            5 to ins(shape = 14, auto = false, period = 0x2000),
            6 to ins(volume = leadVolume, pitch = env(listOf(0, 0, 0, 0, 1, 2, 1, 0, -1, -2, -1), loop = 3)),
            7 to ins(volume = env(listOf(15, 14, 13, 12, 12)), arpeggio = env(listOf(0, 4, 7), loop = 0)),
            8 to ins(volume = env(listOf(15, 14, 12, 10, 8, 5, 3, 0)), mixer = env(listOf(3, 3, 1)), noise = env(listOf(1)),
                sweep = Triple(24, -12, 48)),
            9 to ins(volume = env(listOf(15, 13, 11, 9, 7, 5, 3, 1, 0)), mixer = env(listOf(3, 2)),
                noise = env(listOf(4, 6, 9, 12, 15))),
            10 to ins(volume = env(listOf(12, 8, 4, 0)), mixer = env(listOf(2)), noise = env(listOf(1))),
            11 to ins(volume = env(listOf(8, 10, 12, 14, 15, 15, 10, 5, 0), loop = 4, release = 5), mixer = env(listOf(2)),
                noise = env((31 downTo 1).toList())),
            12 to ins(volume = env(listOf(15, 14, 13, 11, 9, 7, 5, 3, 1)), pitch = env(listOf(-3), loop = 0), relative = true,
                sweep = Triple(12, 0, 16)),
            13 to ins(mixer = env(listOf(1)), shape = 12, auto = true, octave = -2),
            14 to ins(shape = 13, auto = false, period = 0x0100),
            15 to ins(mixer = env(listOf(0)), shape = 11, auto = false, period = 0x0040),
            16 to ins(mixer = env(listOf(3)), shape = 15, auto = true, octave = 3, noise = env(listOf(5))),
            17 to ins(volume = env(listOf(15, 12, 10, 9, 8, 8, 6, 4, 2, 1, 0), loop = 4, release = 6)),
            18 to ins(volume = leadVolume, mixer = env(listOf(1, 1, 3), loop = 2)),
        )
    }

    /** `ay_song.test_song(variant)`: the native host test's song (every effect, all eight shapes, shared noise and envelope). */
    fun test(variant: AyVariant): Song {
        val lo = LOWEST_NOTE[variant.index]
        val p = HashMap<String, Map<Int, Cell>>()
        p["a0"] = mapOf(0 to c(36, 2), 4 to c(null, null, 'J' to 0x01), 8 to c(null, null, null, 'E' to 0x0C),
            10 to c(null, null, 'E' to 0x0C), 12 to c(OFF), 14 to c(lo, 1))
        p["b0"] = mapOf(2 to c(60, 4), 6 to c(OFF), 9 to c(64, 13), 11 to c(OFF),
            13 to c(67, 5, 'H' to 0x01, 'I' to 0x80),
            15 to c(null, null, 'I' to 0x40, 'V' to 0x03, 'W' to 0x1F))
        p["c0"] = mapOf(0 to c(48, 8), 4 to c(48, 9), 6 to c(null, null, 'W' to 0x02), 8 to c(48, 10, 'S' to 0x02),
            10 to c(48, 9, 'G' to 0x02), 12 to c(48, 10, 'G' to 0x07),
            14 to c(36, 16, 'W' to 0x09, 'V' to 0x03))
        p["a1"] = mapOf(0 to c(60, 6), 4 to c(64, null, '0' to 0x37), 8 to c(67, null, '4' to 0x46), 12 to c(OFF),
            16 to c(72, 7, '3' to 0x00), 18 to c(null, null, '3' to 0x20), 20 to c(76),
            24 to c(null, null, '3' to 0x00, '1' to 0x08), 26 to c(null, null, '2' to 0x04),
            28 to c(79, 17, '2' to 0x00, 'P' to 0x90), 30 to c(OFF))
        p["b1"] = mapOf(0 to c(43, 3), 8 to c(43, null, 'J' to 0xFF), 12 to c(45, null, 'J' to 0x02, 'E' to 0x0E),
            16 to c(48, 14), 20 to c(null, null, 'H' to 0x02), 24 to c(50, 15, null, null, 'V' to 0x00),
            28 to c(OFF))
        p["c1"] = mapOf(0 to c(36, 8), 2 to c(42, 10), 4 to c(38, 9, 'C' to 0x0C), 6 to c(42, 10), 8 to c(36, 8),
            10 to c(42, 10, 'A' to 0x02), 12 to c(38, 9, 'A' to 0x00, 'C' to 0x0F), 14 to c(36, 12),
            16 to c(48, 11), 22 to c(OFF), 24 to c(36, 8, 'W' to 0x03), 26 to c(42, 18),
            28 to c(95, 1, 'A' to 0x10), 30 to c(OFF))
        p["b2"] = mapOf(0 to c(55, 4, 'F' to 0x04), 8 to c(57, null, 'F' to 0x09), 12 to c(59, null, 'D' to 0x02))
        p["c2"] = mapOf(0 to c(36, 8), 8 to c(38, 9, 'G' to 0x02, 'F' to 0x05), 12 to c(null, null, 'B' to 0x03))
        p["a3"] = mapOf(2 to c(48, 2, 'A' to 0x01), 8 to c(null, null, 'C' to 0x00),
            10 to c(50, 13, 'E' to 0x08, 'J' to 0xF8, 'V' to 0x01, 'W' to 0x1F),
            16 to c(52, 4, 'F' to 0x07), 23 to c(OFF, null, 'B' to 0x01))
        p["b3"] = mapOf(2 to c(64, 16), 6 to c(64, 16, 'J' to 0x07), 12 to c(OFF), 16 to c(67, 5),
            20 to c(null, null, 'E' to 0x00))
        p["c3"] = mapOf(4 to c(40, 9), 8 to c(40, 9, 'S' to 0x00), 12 to c(45, 6, '4' to 0x24, 'P' to 0x70),
            18 to c(null, null, '4' to 0x20), 20 to c(OFF))
        val orders = listOf(
            Order(16, listOf("a0", "b0", "c0")),
            Order(32, listOf("a1", "b1", "c1")),
            Order(16, listOf("a0", "b2", "c2")),
            Order(24, listOf("a3", "b3", "c3")),
        )
        return song(variant, 6, orders, p, testInstruments(), loop = 1)
    }

    /**
     * `ay_golden.features_song()` (MSX), with one more order parked between the first and second: the arrangement
     * drops it and renumbers the loop and every `Bxx`, which the script's song states directly.
     */
    fun features(): Song {
        val shared = env(listOf(15, 12, 9, 6, 3, 0), loop = 1, release = 4)
        val i = mapOf(
            1 to ins(volume = shared, arpeggio = env(listOf(0, -12, 12, -64, 63), loop = 0),
                pitch = env(listOf(1, -1, 0, -64, 63)), relative = true, mixer = env(listOf(0, 1, 2, 3), loop = 3),
                noise = env(listOf(0, 31, 15), loop = 1), shape = 15, auto = true, octave = -8, sync = true,
                sweep = Triple(96, -96, 255)),
            2 to ins(shape = 8, auto = true, octave = 7),
            3 to ins(volume = env(listOf(15, 12, 9, 6, 3, 0), loop = 1, release = 4), shape = 9, auto = false, period = 65535,
                sweep = Triple(-12, 5, 3)),
            4 to ins(),
            // Settings of the inactive mode are kept in the model and left out of the image.
            5 to ins(shape = 0, auto = false, octave = 5, period = 0x1234, sync = true),
            6 to ins(shape = 0, auto = true, octave = 3, period = 777, sync = true),
            63 to ins(volume = env(List(256) { it % 16 }, loop = 255), noise = env(List(256) { it % 32 })),
            64 to ins(mixer = env(listOf(2)), noise = env(listOf(31)), shape = 12, auto = false, period = 0),
        )
        val lo = LOWEST_NOTE[AyVariant.MSX.index]
        val p = HashMap<String, Map<Int, Cell>>()
        p["a0"] = mapOf(0 to c(lo, 1, '4' to 0x22), 8 to c(95), 16 to c(OFF, null, 'C' to 0x00), 40 to c(48, 64),
            63 to c(null, null, 'W' to 0x1F))
        p["b0"] = mapOf(
            0 to c(48, 2, 'E' to 0x0F, 'J' to 0xF8, 'V' to 0x03, 'W' to 0x1F),
            // Blank columns before used ones are left out of the image.
            1 to c(null, null, null, null, 'H' to 0xFF),
            2 to c(null, null, null, 'I' to 0xFF, null, 'A' to 0xF0),
            3 to c(null, 5),
            32 to c(OFF),
            63 to c(OFF, null, 'S' to 0x00),
        )
        p["c0"] = mapOf(0 to c(60, 63, 'C' to 0x0F, '0' to 0x47, 'P' to 0xFF, 'A' to 0x0F), 4 to c(57, null, '3' to 0xFF),
            12 to c(95, 6, 'P' to 0x00), 13 to c(null, 4),
            20 to c(30, 3, 'G' to 0x01, 'S' to 0x03, '4' to 0xFF, 'E' to 0x00),
            30 to c(40, 64, '1' to 0xFF, '2' to 0x00, 'J' to 0x07, 'V' to 0x00))
        // Document orders: 0, parked 1, 2, 3; B03 = document order 3, which plays as order 2.
        p["b2"] = mapOf(0 to c(50, 2), 6 to c(52, 1, 'W' to 0x00, 'E' to 0x08), 15 to c(null, null, 'B' to 0x03, 'D' to 0x00))
        p["c2"] = mapOf(3 to c(64, 3, 'G' to 0x03, 'S' to 0x02), 10 to c(64, null, '3' to 0x10),
            12 to c(67, null, '1' to 0x04, '2' to 0x00), 18 to c(null, null, 'D' to 0x05))
        p["a3"] = mapOf(0 to c(36, 2, 'F' to 0xFF, 'B' to 0x02))
        p["parked"] = mapOf(0 to c(40, 2, 'B' to 0x00))
        val orders = listOf(
            Order(64, listOf("a0", "b0", "c0")),
            Order(8, listOf("parked", null, null)),
            Order(20, listOf(null, "b2", "c2")),
            Order(1, listOf("a3", null, null)),
        )
        return song(AyVariant.MSX, 5, orders, p, i, loop = 2).copy(parkedOrders = listOf(1))
    }
}
