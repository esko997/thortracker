package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.pce.PceEnvelope
import org.thortracker.system.pce.PceInstrument
import org.thortracker.system.pce.PceKind
import org.thortracker.system.pce.PceSample
import org.thortracker.system.pce.PceSound

/**
 * The songs of `tools/audio/pce/pce_golden.py` written in Thor Tracker's model, for [PceGoldenTest]: the host test's
 * song (`pce_song.test_song`) and the features song. Kept in the reference script's shape (instruments by slot,
 * patterns by name, orders naming a pattern per channel, waves and samples from the same integer formulas) so the two
 * read alike.
 */
object PceGoldenSongs {
    private const val OFF = 0xFE

    /** floor(127 sin(2 pi i / 64) + 0.5), as `pce_song.SINE`. */
    private val SINE = intArrayOf(
        0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126,
        127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12,
        0, -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
        -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12,
    )

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = PceEnvelope(values.toList(), loop, release)

    /** `pce_song.Instrument` with Thor Tracker's names; defaults as both. */
    private fun ins(
        kind: PceKind = PceKind.TONE, wave: Int = 0, volume: PceEnvelope = env(31), arpeggio: PceEnvelope = env(),
        pitch: PceEnvelope = env(), relative: Boolean = false, waves: PceEnvelope = env(), sample: Int = 0, root: Int = 48,
        loop: Boolean = false, loopStart: Int = 0,
    ) = PceInstrument(
        name = "Golden", kind = kind, wave = wave, volume = volume, arpeggio = arpeggio, pitch = pitch,
        pitchRelative = relative, waves = waves, sample = sample, root = root, loop = loop, loopStart = loopStart,
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
        speed: Int, orders: List<Order>, patterns: Map<String, Map<Int, Cell>>, instruments: Map<Int, PceInstrument>,
        waves: List<List<Int>>, samples: List<List<Int>>, loop: Int, multispeed: Int = 1, master: Int = 0xFF,
        lfoFrequency: Int = 0, lfoControl: Int = 0, timer: Int = 0,
    ): Song = Song(
        title = "Golden",
        ticksPerRow = speed,
        loopOrder = loop,
        patterns = orders.mapIndexed { o, order ->
            Pattern("Order $o", (0 until 6).map { ch ->
                val rows = order.patterns[ch]?.let { patterns.getValue(it) } ?: emptyMap()
                List(Songs.ROWS) { rows[it] ?: Cell.BLANK }
            }, length = order.rows)
        },
        orders = orders.indices.toList(),
        sound = PceSound(
            instruments = List(64) { instruments[it + 1] },
            waves = waves,
            samples = samples.mapIndexed { i, data -> PceSample("Sample $i", data) },
            multispeed = multispeed,
            master = master,
            lfoFrequency = lfoFrequency,
            lfoControl = lfoControl,
            timerReload = timer,
        ),
    )

    /** `pce_song.test_waves()`: 0 sine, 1 square, 2 saw, 3 triangle, 4 25% pulse. */
    private fun testWaves(): List<List<Int>> = listOf(
        List(32) { (SINE[2 * it] + 127) * 31 / 254 },
        List(32) { if (it < 16) 31 else 0 },
        List(32) { it },
        List(32) { if (it < 16) 2 * it else 62 - 2 * it },
        List(32) { if (it < 8) 31 else 0 },
    )

    /** `pce_song.kick_sample()`. */
    private fun kickSample(): List<Int> {
        val n = 600
        return List(n) { i ->
            val phase = i * 4 * (n - i / 2) / n % 64
            val amp = n - i
            ((SINE[phase] + 127) * amp + 127 * (n - amp)) * 31 / (254 * n)
        }
    }

    /** `pce_song.buzz_sample()`. */
    private fun buzzSample(): List<Int> = List(256) { (it * 3) % 28 + (it / 8) % 4 }

    /** `pce_song.test_instruments()`. */
    private fun testInstruments(): Map<Int, PceInstrument> = mapOf(
        1 to ins(),
        2 to ins(wave = 2, volume = env(31, 30, 28, 26, 24, 22, loop = 4), pitch = env(0, 4, 8, 4, 0, -4, -8, -4, loop = 0)),
        3 to ins(wave = 3, volume = env(8, 14, 20, 26, 24, 18, 12, 6, 0, loop = 3, release = 4), arpeggio = env(0, 0, 12, 12, loop = 0)),
        4 to ins(wave = 1, volume = env(31, 28, 25, 22, 20), waves = env(1, 1, 4, 4, 2, loop = 2)),
        5 to ins(wave = 1, volume = env(31, 30, 29, 28, 20, 12, 4, 0, loop = 3, release = 4),
            pitch = env(4, 2, 2, -2, -2, -16, -16, loop = 1, release = 5), relative = true),
        6 to ins(kind = PceKind.NOISE, volume = env(31, 24, 16, 10, 6, 3, 0), arpeggio = env(0, -3, 2)),
        7 to ins(kind = PceKind.NOISE, volume = env(28, 27, 26, 20, 12, 6, 0, loop = 2, release = 3)),
        8 to ins(kind = PceKind.SAMPLE, sample = 0, root = 48),
        9 to ins(kind = PceKind.SAMPLE, sample = 1, root = 57, loop = true, loopStart = 64),
    )

    /** `pce_golden.spike_song()` = `pce_song.test_song()`. */
    fun spike(): Song {
        val p = HashMap<String, Map<Int, Cell>>()
        // Order 0 (16 rows)
        p["lead0"] = mapOf(0 to c(60, 2, '0' to 0x37), 4 to c(null, null, '4' to 0x34), 6 to c(62, null, '0' to 0x47, 'P' to 0x88),
            10 to c(64, null, 'W' to 0x03, 'C' to 0x18, '8' to 0xF8, 'A' to 0x02), 13 to c(OFF), 14 to c(65, 2, '4' to 0x30))
        p["bass0"] = mapOf(0 to c(36, 5, '1' to 0x08), 3 to c(null, null, '1' to 0x00), 4 to c(36, null, '2' to 0x10),
            6 to c(null, null, '2' to 0x00), 8 to c(43, null, 'P' to 0x70), 12 to c(41), 15 to c(OFF))
        p["pad0"] = mapOf(0 to c(60, 3), 8 to c(57, null, '8' to 0x5F), 15 to c(OFF))
        p["wave0"] = mapOf(0 to c(64, 4, '4' to 0x22), 4 to c(null, null, 'W' to 0x00), 6 to c(67, null, 'C' to 0x14),
            10 to c(65, null, 'A' to 0x04), 14 to c(OFF, null, 'A' to 0x00))
        p["drums0"] = mapOf(0 to c(48, 8), 4 to c(70, 6), 6 to c(72, 6, 'S' to 0x02), 8 to c(48, 8, 'G' to 0x02), 12 to c(70, 7),
            14 to c(null, null, '8' to 0xA5), 15 to c(OFF))
        p["loop0"] = mapOf(0 to c(57, 9), 6 to c(60, null, '3' to 0x20), 10 to c(null, null, '3' to 0x00), 12 to c(OFF))
        // Order 1 (32 rows, the loop)
        p["lead1"] = mapOf(0 to c(57, 1, 'M' to 0x01, 'L' to 0x10), 8 to c(null, null, 'M' to 0x02),
            16 to c(null, null, 'M' to 0x83), 20 to c(null, null, 'M' to 0x03),
            24 to c(59, null, 'M' to 0x00, 'X' to 0xFC), 31 to c(OFF, null, 'X' to 0xEE))
        p["lfo1"] = mapOf(0 to c(9, 1, 'C' to 0x00), 16 to c(21), 28 to c(OFF))
        p["pad1"] = mapOf(2 to c(55, 3, '0' to 0x47), 10 to c(52, null, '0' to 0x38), 18 to c(50), 26 to c(OFF))
        p["bend1"] = mapOf(0 to c(48, 5), 8 to c(OFF), 12 to c(50, 5, 'P' to 0x90), 20 to c(OFF), 24 to c(53, 4, 'G' to 0x07))
        p["kick1"] = mapOf(0 to c(48, 8), 8 to c(50, 8, 'T' to 0x03), 16 to c(48, 8, 'T' to 0x0F), 24 to c(45, 8, 'T' to 0x07))
        p["noise1"] = mapOf(0 to c(66, 6, '8' to 0xFF), 4 to c(68, 7), 8 to c(OFF), 12 to c(57, 9), 20 to c(OFF), 22 to c(66, 6),
            26 to c(70, 6, 'S' to 0x01), 28 to c(64, 1))
        // Order 2 (16 rows)
        p["o2lead"] = mapOf(0 to c(60, 2, 'F' to 0x04, '4' to 0x48), 4 to c(62, null, '1' to 0x20), 8 to c(64, null, 'F' to 0x05),
            12 to c(65, null, 'D' to 0x02, '1' to 0x00))
        p["o2bell"] = mapOf(0 to c(72, 4, '8' to 0xF0), 6 to c(76, 4, '8' to 0x0F, 'P' to 0x90), 11 to c(OFF))
        p["o2t"] = mapOf(0 to c(null, null, 'T' to 0x00), 2 to c(57, 9), 8 to c(null, null, 'T' to 0x07), 10 to c(OFF))
        // Order 3 (24 rows)
        p["o3a"] = mapOf(0 to c(55, 3, 'C' to 0x10), 2 to c(57, null, 'C' to 0x1F, 'G' to 0x01), 6 to c(OFF), 8 to c(59, 2),
            16 to c(60, 2, '4' to 0x13), 23 to c(OFF, null, 'B' to 0x01))
        p["o3b"] = mapOf(2 to c(64, 5, 'X' to 0xF7), 9 to c(OFF), 12 to c(62, 1, 'W' to 0x04), 20 to c(60, 4, 'P' to 0x40))
        p["o3c"] = mapOf(0 to c(48, 8, '8' to 0xF0), 6 to c(48, 8, '8' to 0x0F), 12 to c(55, 8), 18 to c(48, 8, '8' to 0xFF, 'S' to 0x01))
        p["o3n"] = mapOf(2 to c(67, 7, 'L' to 0x40, 'M' to 0x81), 10 to c(OFF), 14 to c(71, 6, 'M' to 0x00))
        val orders = listOf(
            Order(16, listOf("lead0", "bass0", "pad0", "wave0", "drums0", "loop0")),
            Order(32, listOf("lead1", "lfo1", "pad1", "bend1", "kick1", "noise1")),
            Order(16, listOf("o2lead", "bass0", "o2bell", null, "drums0", "o2t")),
            Order(24, listOf("o3a", "o3b", null, null, "o3c", "o3n")),
        )
        return song(6, orders, p, testInstruments(), testWaves(), listOf(kickSample(), buzzSample()), loop = 1,
            master = 0xEE, lfoFrequency = 0x20, lfoControl = 0, timer = 7)
    }

    /** `pce_golden.features_waves()`. */
    private fun featuresWaves(): List<List<Int>> = List(64) { w -> List(32) { i -> (i * (w % 7 + 1) + w) % 32 } }

    /** `pce_golden.features_samples()`: a 65535-frame sample, two identical small ones, a one-frame one. */
    private fun featuresSamples(): List<List<Int>> {
        val small = List(100) { (it * 5) % 32 }
        return listOf(List(65535) { (it * 13 + it / 7) % 32 }, small, small.toList(), listOf(31))
    }

    /**
     * `pce_golden.features_song()`, with one more order parked between the first and second: the arrangement drops it
     * and renumbers the loop and every `Bxx`, which the script's song states directly.
     */
    fun features(): Song {
        val i = mapOf(
            1 to ins(wave = 63, volume = env(0, 31, 15, loop = 1, release = 2), arpeggio = env(-64, 63, 0, loop = 2),
                pitch = PceEnvelope(List(255) { (it * 37) % 256 - 128 }, loop = 254), relative = true,
                waves = env(0, 63, 31, loop = 0)),
            // noise: the model's wave 5 and wave envelope are not lowered
            2 to ins(kind = PceKind.NOISE, wave = 5, volume = env(31), arpeggio = env(31, -31), pitch = env(5), waves = env(1, 2)),
            3 to ins(kind = PceKind.SAMPLE, volume = env(31, 20), sample = 0, root = 9, loop = true, loopStart = 65534),
            // loop start kept in the model without a loop: lowered as 0
            4 to ins(kind = PceKind.SAMPLE, sample = 2, root = 95, loop = false, loopStart = 50),
            // the same volume values as slot 1 with another loop and release: one envelope in the data
            5 to ins(wave = 0, volume = env(0, 31, 15, loop = 0, release = 1)),
            6 to ins(kind = PceKind.SAMPLE, sample = 3, root = 48, loop = true, loopStart = 0),
            64 to ins(wave = 1),
        )
        val p = HashMap<String, Map<Int, Cell>>()
        p["a0"] = mapOf(0 to c(9, 1, '4' to 0x22), 8 to c(95, null), 16 to c(OFF, null, 'C' to 0x10), 40 to c(48, 5))
        p["a1"] = mapOf(0 to c(60, 64, 'X' to 0x5A, 'L' to 0xFF, 'M' to 0x83, 'T' to 0x7F), 32 to c(OFF))
        p["a2"] = mapOf(
            0 to c(60, 1, 'C' to 0x1F, '8' to 0x11, '4' to 0x37, 'P' to 0x90),
            // Blank columns before used ones are left out of the image.
            1 to Cell().withEffect(2, code('W'), 0x3F),
            2 to Cell().withEffect(1, code('8'), 0x10).withEffect(3, code('A'), 0x0F),
            63 to c(OFF, null, 'A' to 0x00),
        )
        p["a3"] = mapOf(5 to c(null, 64), 6 to c(72, null, '0' to 0x47))
        p["a4"] = mapOf(0 to c(70, 2, 'S' to 0x01), 4 to c(57, 3), 12 to c(60, 4, '3' to 0x10), 20 to c(null, null, 'T' to 0x00))
        p["a5"] = mapOf(0 to c(66, 2), 8 to c(50, 6), 16 to c(OFF))
        // Document orders: 0, parked 1, 2, 3; B03 = document order 3, which plays as order 2 (and B02 as 1).
        p["b0"] = mapOf(0 to c(50, 5, 'G' to 0x01), 6 to c(52, 1), 15 to c(null, null, 'B' to 0x03, 'D' to 0x00))
        p["b5"] = mapOf(3 to c(64, 2, 'G' to 0x03, 'S' to 0x02), 10 to c(64, null, '3' to 0x10), 12 to c(67, null, '1' to 0x04, '2' to 0x00))
        p["c4"] = mapOf(0 to c(36, 3, 'F' to 0x04, 'B' to 0x02))
        p["parked"] = mapOf(0 to c(40, 5, 'B' to 0x00))
        val orders = listOf(
            Order(64, listOf("a0", "a1", "a2", "a3", "a4", "a5")),
            Order(8, listOf("parked", null, null, null, null, null)),
            Order(20, listOf("b0", null, null, null, null, "b5")),
            Order(1, listOf(null, null, null, null, "c4", null)),
        )
        return song(5, orders, p, i, featuresWaves(), featuresSamples(), loop = 2, multispeed = 2, master = 0x5A,
            lfoFrequency = 0xFF, lfoControl = 0x83, timer = 0x7F).copy(parkedOrders = listOf(1))
    }
}
