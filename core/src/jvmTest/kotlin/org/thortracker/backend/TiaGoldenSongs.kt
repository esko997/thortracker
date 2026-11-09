package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.tia.TiaEnvelope
import org.thortracker.system.tia.TiaInstrument
import org.thortracker.system.tia.TiaRegion
import org.thortracker.system.tia.TiaSound

/**
 * The songs of `tools/audio/tia/tia_golden.py` written in Thor Tracker's model, for [TiaGoldenTest]: the host test's
 * song (`tia_song.test_song`, NTSC) and the PAL features song. Kept in the reference script's shape (instruments by
 * slot, patterns by name, orders naming a pattern per channel) so the two read alike.
 */
object TiaGoldenSongs {
    private const val OFF = 0xFE

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = TiaEnvelope(values.toList(), loop, release)

    /** `tia_song.Instrument` with Thor Tracker's names; defaults as both (AUDC C, tonal, no envelopes). */
    private fun ins(
        waveform: Int = 12, raw: Boolean = false, relative: Boolean = false, volume: TiaEnvelope = env(),
        arpeggio: TiaEnvelope = env(), pitch: TiaEnvelope = env(), waveforms: TiaEnvelope = env(),
    ) = TiaInstrument(
        name = "Golden", waveform = waveform, raw = raw, relativePitch = relative, volume = volume, arpeggio = arpeggio,
        pitch = pitch, waveforms = waveforms,
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
        speed: Int, orders: List<Order>, patterns: Map<String, Map<Int, Cell>>, instruments: Map<Int, TiaInstrument>,
        loop: Int, region: TiaRegion = TiaRegion.NTSC,
    ): Song = Song(
        title = "Golden",
        ticksPerRow = speed,
        loopOrder = loop,
        patterns = orders.mapIndexed { o, order ->
            Pattern("Order $o", (0 until 2).map { ch ->
                val rows = order.patterns[ch]?.let { patterns.getValue(it) } ?: emptyMap()
                List(Songs.ROWS) { rows[it] ?: Cell.BLANK }
            }, length = order.rows)
        },
        orders = orders.indices.toList(),
        sound = TiaSound(region, List(64) { instruments[it + 1] }),
    )

    /** `tia_song.test_instruments()`: every AUDC 0..15 played by an instrument, a waveform envelope or `Wxx`. */
    private fun testInstruments(): Map<Int, TiaInstrument> = mapOf(
        1 to ins(waveform = 12),
        2 to ins(waveform = 4, volume = env(15, 14, 13, 12, 11, 10, loop = 4), pitch = env(0, 16, 32, 16, 0, -16, -32, -16, loop = 0)),
        3 to ins(waveform = 6, volume = env(4, 7, 10, 13, 12, 9, 6, 3, 0, loop = 3, release = 4), arpeggio = env(0, 0, 12, 12, loop = 0)),
        4 to ins(waveform = 12, volume = env(15, 13, 11, 9, 8), waveforms = env(12, 12, 4, 1, 7, 15, loop = 2)),
        5 to ins(waveform = 13, volume = env(15, 14, 13, 12, 8, 5, 2, 0, loop = 3, release = 4),
            pitch = env(4, 2, 2, -2, -2, -16, -16, loop = 1, release = 5), relative = true),
        6 to ins(waveform = 8, raw = true, volume = env(15, 11, 7, 4, 2, 1, 0), arpeggio = env(0, -3, 2)),
        7 to ins(waveform = 8, raw = true, volume = env(14, 13, 12, 9, 6, 3, 0, loop = 2, release = 3)),
        8 to ins(waveform = 15, raw = true, volume = env(15, 15, 12, 8, 4, 0), waveforms = env(3, 15, 14, 14),
            arpeggio = env(0, -2, -4, -6, -8)),
        9 to ins(waveform = 0, volume = env(15, 8, 0, 4, 2, 0)),
        10 to ins(waveform = 2),
        11 to ins(waveform = 1, volume = env(12)),
        12 to ins(waveform = 14),
        13 to ins(waveform = 5, volume = env(10, 9, 8, loop = 2)),
        14 to ins(waveform = 10),
        15 to ins(waveform = 11, raw = true, volume = env(9, 0, 9, 0, loop = 0)),
        16 to ins(waveform = 3, raw = true),
        17 to ins(waveform = 7),
        // byte-identical to slot 1: its own record (slot identity)
        18 to ins(waveform = 12),
    )

    /** `tia_golden.spike_song()` = `tia_song.test_song()`. */
    fun spike(): Song {
        val p = HashMap<String, Map<Int, Cell>>()
        // Order 0 (16 rows)
        p["a0"] = mapOf(0 to c(60, 1, '0' to 0x37), 4 to c(null, null, '4' to 0x34), 6 to c(62, 2, '0' to 0x47, 'P' to 0x88),
            10 to c(64, null, 'W' to 0x0C, 'C' to 0x0B), 11 to c(null, null, 'A' to 0x02), 13 to c(OFF),
            14 to c(65, 3, '4' to 0x30, 'A' to 0x00))
        p["b0"] = mapOf(0 to c(36, 5, '1' to 0x08), 3 to c(null, null, '1' to 0x00), 4 to c(36, null, '2' to 0x10),
            6 to c(null, null, '2' to 0x00), 8 to c(48, 6), 9 to c(50, 7, 'S' to 0x02), 10 to c(43, 8, 'G' to 0x02),
            12 to c(41, 4, 'W' to 0x01), 15 to c(OFF))
        // Order 1 (32 rows, the loop)
        p["a1"] = mapOf(0 to c(57, 11), 4 to c(59, 12), 8 to c(0, 10), 10 to c(119, 13), 12 to c(60, 14, '3' to 0x20), 14 to c(72),
            16 to c(null, null, '3' to 0x00), 18 to c(55, 17, '4' to 0x2F), 22 to c(30, 16), 24 to c(66, 15),
            26 to c(70, 18), 28 to c(OFF), 30 to c(64, 9))
        p["b1"] = mapOf(0 to c(48, 8), 4 to c(66, 6), 8 to c(48, 8, 'C' to 0x08), 12 to c(68, 7), 14 to c(OFF),
            16 to c(48, 8, 'C' to 0x0F), 20 to c(66, 6), 24 to c(48, 8), 26 to c(57, 1, 'W' to 0x09),
            28 to c(null, null, 'W' to 0x0E), 30 to c(OFF))
        // Order 2 (16 rows)
        p["c2"] = mapOf(0 to c(60, 2, 'F' to 0x04, '4' to 0x48), 4 to c(62, null, '1' to 0x20), 8 to c(64, null, 'F' to 0x05),
            12 to c(65, null, 'D' to 0x02, '1' to 0x00))
        // Order 3 (24 rows)
        p["d3a"] = mapOf(0 to c(55, 3, 'C' to 0x0A), 2 to c(57, null, 'C' to 0x0F, 'G' to 0x01), 6 to c(OFF), 8 to c(59, 2),
            16 to c(60, 2, '4' to 0x13), 23 to c(OFF, null, 'B' to 0x01))
        p["d3b"] = mapOf(0 to c(36, 1), 4 to c(38, 5, 'G' to 0x07), 10 to c(40, 5, 'S' to 0x03), 14 to c(41, 1, 'A' to 0x01),
            20 to c(OFF, null, 'A' to 0x00))
        val orders = listOf(
            Order(16, listOf("a0", "b0")),
            Order(32, listOf("a1", "b1")),
            Order(16, listOf("c2", "b0")),
            Order(24, listOf("d3a", "d3b")),
        )
        return song(6, orders, p, testInstruments(), loop = 1)
    }

    /**
     * `tia_golden.features_song()` (PAL), with one more order parked between the first and second: the arrangement
     * drops it and renumbers the loop and every `Bxx`, which the script's song states directly.
     */
    fun features(): Song {
        val sharedVolume = env(0, 15, 7, loop = 1, release = 2)
        val i = mapOf(
            1 to ins(waveform = 6, volume = sharedVolume, arpeggio = env(-64, 63, 0, loop = 2),
                pitch = TiaEnvelope(List(255) { (it * 37) % 256 - 128 }, loop = 254), relative = true,
                waveforms = TiaEnvelope((0..15).toList(), loop = 0)),
            // two byte-identical sounds: two records, their envelope bytes stored once
            2 to ins(waveform = 4, volume = env(15, 9, 3, loop = 2), pitch = env(-128, 127, 0)),
            3 to ins(waveform = 4, volume = env(15, 9, 3, loop = 2), pitch = env(-128, 127, 0)),
            // the same volume values as slot 1 with another loop and release: one envelope in the data
            5 to ins(waveform = 12, volume = env(0, 15, 7, loop = 0, release = 1)),
            // raw noise
            6 to ins(waveform = 8, raw = true, volume = env(15, 8, 0), arpeggio = env(0, 5)),
            64 to ins(waveform = 1),
        )
        val p = HashMap<String, Map<Int, Cell>>()
        p["a0"] = mapOf(0 to c(0, 1, '4' to 0x22), 8 to c(119, null), 16 to c(OFF, null, 'C' to 0x07),
            40 to c(48, 5), 41 to c(null, null, 'W' to 0x0F), 63 to c(OFF, null, 'A' to 0x10))
        p["a1"] = mapOf(0 to c(60, 64, 'C' to 0x0F, 'P' to 0x90), 1 to c(null, 2), 2 to c(62, 3, '3' to 0x10),
            5 to c(null, null, 'S' to 0x02), 32 to c(70, 6, '0' to 0x47))
        // Document orders: 0, parked 1, 2, 3; B03 = document order 3, which plays as order 2 (the script's B02).
        p["b0"] = mapOf(0 to c(50, 5, 'G' to 0x01), 6 to c(52, 1), 15 to c(null, null, 'B' to 0x03, 'D' to 0x00))
        // Column 0 blank, column 1 = 104: lowered as the one effect (the script's [("1", 0x04)]).
        p["b1"] = mapOf(3 to Cell(note = 64, instrument = 2).withEffect(1, code('1'), 0x04),
            10 to c(64, null, '2' to 0x08, 'F' to 0x07))
        // B02 = document order 2, which plays as order 1 (the script's B01).
        p["c0"] = mapOf(0 to c(36, 3, 'F' to 0x04, 'B' to 0x02))
        p["parked"] = mapOf(0 to c(40, 5, 'B' to 0x00))
        val orders = listOf(
            Order(64, listOf("a0", "a1")),
            Order(8, listOf("parked", null)),
            Order(20, listOf("b0", "b1")),
            Order(1, listOf("c0", null)),
        )
        // The script's loop order 1 is document order 2.
        return song(5, orders, p, i, loop = 2, region = TiaRegion.PAL).copy(parkedOrders = listOf(1))
    }
}
