package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.EffectSpec
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.scc.SccInstrument
import org.thortracker.system.scc.SccSound
import org.thortracker.system.scc.SccVariant
import org.thortracker.system.scc.SccWave
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * The songs of `tools/audio/scc/scc_golden.py` written in Thor Tracker's model, for [SccGoldenTest]: the host test's
 * song (`scc_song.test_song`) on the SCC and on the SCC+, and the features song. Kept in the reference script's shape
 * (instruments by slot, waves by number, patterns by name, orders naming a pattern per channel) so the two read alike.
 */
object SccGoldenSongs {
    private const val OFF = 0xFE
    private val NONE = NesEnvelope()

    private fun env(vararg values: Int, loop: Int = -1, release: Int = -1) = NesEnvelope(values.toList(), loop, release)

    private fun ins(
        wave: NesEnvelope, volume: NesEnvelope = NONE, arpeggio: NesEnvelope = NONE, pitch: NesEnvelope = NONE,
        relative: Boolean = false, restart: Boolean = false,
    ) = SccInstrument(name = "", volume = volume, arpeggio = arpeggio, pitch = pitch, pitchRelative = relative, wave = wave, restart = restart)

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
        variant: SccVariant, speed: Int, orders: List<Order>, patterns: Map<String, Map<Int, Cell>>,
        instruments: Map<Int, SccInstrument>, waves: Map<Int, List<Int>>, loop: Int, multispeed: Int = 1,
    ): Song = Song(
        title = "Golden",
        ticksPerRow = speed,
        loopOrder = loop,
        patterns = orders.mapIndexed { o, order ->
            Pattern("Order $o", (0 until SccSound.CHANNELS).map { ch ->
                val rows = order.patterns[ch]?.let { patterns.getValue(it) } ?: emptyMap()
                List(Songs.ROWS) { rows[it] ?: Cell.BLANK }
            }, length = order.rows)
        },
        orders = orders.indices.toList(),
        sound = SccSound(
            variant = variant,
            instruments = List(SccSound.SLOTS) { instruments[it + 1] },
            waves = List(SccSound.WAVES) { k -> SccWave("", waves[k + 1] ?: List(SccWave.SAMPLES) { 0 }) },
            multispeed = multispeed,
        ),
    )

    // ---- waves (scc_song.py's) ----

    private fun sineWave() = List(32) { floor(127 * sin(2 * PI * it / 32) + 0.5).toInt() }
    private fun squareWave() = List(32) { if (it < 16) 100 else -100 }
    private fun sawWave() = List(32) { -128 + 8 * it }
    private fun triangleWave() = List(32) { i -> if (i <= 8) 15 * i else if (i <= 24) 120 - 15 * (i - 8) else -120 + 15 * (i - 24) }
    private fun pulseWave() = List(32) { if (it < 8) 90 else -30 }
    private fun noiseWave(): List<Int> {
        var x = 1
        return List(32) {
            x = (x * 75 + 74) % 65537
            (x and 0xFF) - 128
        }
    }
    private fun organWave(): List<Int> {
        val s = sineWave()
        return List(32) { (s[it] + s[(2 * it) % 32]) shr 1 }
    }
    private fun halfSineWave(): List<Int> {
        val s = sineWave()
        return List(32) { 2 * maxOf(s[it], 0) - 127 }
    }

    private fun testWaves(): Map<Int, List<Int>> = mapOf(
        1 to sineWave(), 2 to squareWave(), 3 to sawWave(), 4 to triangleWave(), 5 to pulseWave(), 6 to noiseWave(),
        7 to organWave(), 8 to halfSineWave(),
    )

    /** `scc_song.test_instruments()`. */
    private fun testInstruments(): Map<Int, SccInstrument> = mapOf(
        1 to ins(wave = env(1)),
        2 to ins(wave = env(3), volume = env(15, 12, 10, 9, 8, 7, 6, 5, 5, 4, 3, 2, 1, 0, release = 9)),
        3 to ins(wave = env(2), volume = env(15, 14, 13, 12), restart = true),
        4 to ins(wave = env(4, 4, 1, 1, 7, 7, loop = 0),
            volume = env(6, 9, 12, 15, 14, 13, 12, 11, 12, 13, 10, 7, 4, 2, 0, loop = 4, release = 10)),
        5 to ins(wave = env(5), volume = env(12), arpeggio = env(0, 0, 4, 4, 7, 7, loop = 0)),
        6 to ins(wave = env(8, 8, 1), volume = env(15, 15, 13, 11, 9, 7, 5, 3, 1, 0),
            pitch = env(-16, -12, -8, -6, -4, -2), relative = true, restart = true),
        7 to ins(wave = env(6), volume = env(14, 11, 8, 6, 4, 2, 0), arpeggio = env(12, 0), pitch = env(8, 4, 0, -4)),
        8 to ins(wave = env(1, 7, 4, 3), volume = env(15, 13, 11, 9, 8, 7, 8, 9, loop = 4)),
        9 to ins(wave = env(8), volume = env(15, 15, 14, 14, 13)),
        10 to ins(wave = env(3), volume = env(12)),
    )

    /** `scc_song.test_song()` on [variant]. */
    private fun testSong(variant: SccVariant): Song {
        val p = HashMap<String, Map<Int, Cell>>()
        p["lead0"] = mapOf(0 to c(60, 3, '4' to 0x24), 4 to c(62, null, 'P' to 0x90), 6 to c(null, null, 'T' to 0x20),
            8 to c(64, null, '4' to 0x30), 10 to c(null, null, 'T' to 0x00, '1' to 0x04),
            12 to c(65, 3, '2' to 0x02, 'G' to 0x02), 14 to c(OFF, null, '2' to 0x00))
        p["bass0"] = mapOf(0 to c(36, 2, 'C' to 0x0C), 2 to c(OFF), 4 to c(43, 2), 6 to c(null, null, '3' to 0x20),
            7 to c(48), 10 to c(36, null, '3' to 0x10), 12 to c(null, null, '3' to 0x00, 'S' to 0x03),
            13 to c(OFF), 14 to c(41, 2, 'C' to 0x0F), 15 to c(OFF))
        p["chord0"] = mapOf(0 to c(48, 5, '0' to 0x37, 'A' to 0x02), 4 to c(null, null, '0' to 0x47),
            8 to c(53, 5, 'A' to 0x10, 'C' to 0x08), 12 to c(null, null, 'A' to 0x00, 'W' to 0x07), 15 to c(OFF))
        p["drums0"] = mapOf(0 to c(36, 6), 4 to c(50, 7), 8 to c(36, 6), 10 to c(36, 6, 'G' to 0x03),
            12 to c(50, 7, 'S' to 0x02), 14 to c(52, 7, 'G' to 0x01, 'S' to 0x04))
        p["hat0"] = mapOf(2 to c(72, 8), 6 to c(76, 8, 'W' to 0x05), 10 to c(79, 8), 14 to c(OFF))
        p["pad1"] = mapOf(0 to c(60, 4), 8 to c(null, null, 'T' to 0x40), 12 to c(64, 4, 'C' to 0x0A),
            14 to c(null, null, 'T' to 0x00), 16 to c(OFF), 20 to c(67, 4, '4' to 0x46, 'P' to 0x70),
            28 to c(OFF), 30 to c(OFF))
        p["bass1"] = mapOf(0 to c(24, 9), 4 to c(31), 8 to c(24, 9, '1' to 0x08), 10 to c(null, null, '1' to 0x00),
            12 to c(29, 9), 16 to c(24, 9, '2' to 0x04), 18 to c(null, null, '2' to 0x00), 20 to c(27, 9),
            24 to c(24, 9), 28 to c(31, 9, 'S' to 0x04))
        p["lead1"] = mapOf(2 to c(72, 3, '4' to 0x26), 6 to c(74), 10 to c(76, null, '0' to 0x0C), 14 to c(OFF),
            18 to c(79, 3, 'P' to 0x88, 'C' to 0x0B, '4' to 0x22, 'A' to 0x01),
            22 to c(77, null, '3' to 0x08), 24 to c(72), 26 to c(null, null, '3' to 0x00), 30 to c(OFF))
        p["sh4"] = mapOf(0 to c(48, 10), 8 to c(50, 10), 16 to c(52, 10, 'W' to 0x04), 24 to c(53, 10), 31 to c(OFF))
        p["sh5"] = mapOf(4 to c(60, 1), 12 to c(62, 1, 'W' to 0x02), 20 to c(64, 1), 28 to c(65, 6))
        p["o2lead"] = mapOf(0 to c(60, 3, 'F' to 0x04, '4' to 0x48), 4 to c(62, null, '1' to 0x20),
            8 to c(64, null, 'F' to 0x05), 12 to c(65, null, 'D' to 0x02, '1' to 0x00))
        p["o2bell"] = mapOf(0 to c(72, 8, 'T' to 0x02), 2 to c(null, null, 'T' to 0x01), 3 to c(null, null, 'T' to 0x00),
            6 to c(76, 8, 'P' to 0x90, 'A' to 0x01), 11 to c(OFF))
        p["o3a"] = mapOf(0 to c(55, 4, 'C' to 0x08), 2 to c(57, null, 'C' to 0x0F, 'G' to 0x01), 6 to c(OFF),
            8 to c(59, 2), 16 to c(60, 3, '4' to 0x13), 23 to c(OFF, null, 'B' to 0x01))
        p["o3b"] = mapOf(2 to c(64, 5, '0' to 0x47), 9 to c(OFF), 12 to c(62, 7), 20 to c(60, 1, 'P' to 0x40))
        p["o3c"] = mapOf(0 to c(36, 6), 6 to c(36, 6), 12 to c(43, 6), 18 to c(36, 6, 'S' to 0x01))
        p["o3d"] = mapOf(4 to c(84, 7, 'W' to 0x06), 10 to c(OFF), 14 to c(86, 7))
        val orders = listOf(
            Order(16, listOf("lead0", "bass0", "chord0", "drums0", "hat0")),
            Order(32, listOf("pad1", "bass1", "lead1", "sh4", "sh5")),
            Order(16, listOf("o2lead", "bass0", "o2bell", "drums0", "hat0")),
            Order(24, listOf("o3a", "o3b", null, "o3c", "o3d")),
        )
        return song(variant, 6, orders, p, testInstruments(), testWaves(), loop = 1)
    }

    /** `scc_golden.py` spike: `scc_song.test_song()` on the SCC. */
    fun spike(): Song = testSong(SccVariant.SCC)

    /** `scc_golden.py` plus: the same song on the SCC+. */
    fun plus(): Song = testSong(SccVariant.PLUS)

    /**
     * `scc_golden.features_song()`, with one more order parked between the first and second: the arrangement drops it
     * and renumbers the loop and every `Bxx`, which the script's song states directly.
     */
    fun features(): Song {
        val i = mapOf(
            1 to ins(
                volume = NesEnvelope(List(256) { (it * 7 + 3) % 16 }, loop = 16, release = 200),
                arpeggio = NesEnvelope(List(256) { (it * 5) % 128 - 64 }, loop = 0),
                pitch = NesEnvelope(List(256) { (it * 3) % 128 - 64 }, loop = 128), relative = true,
                wave = NesEnvelope(List(256) { 1 + it % 4 }, loop = 255), restart = true,
            ),
            2 to ins(wave = env(2), pitch = env(4, -4, loop = 0)),
            3 to ins(wave = env(64, 3, loop = 1), volume = env(15, 0, release = 1)),
            64 to ins(wave = env(5), volume = env(15, 10, loop = 0, release = 1), arpeggio = env(63, -64)),
        )
        val waves = mapOf(
            1 to sineWave(), 2 to squareWave(), 3 to sawWave(), 4 to triangleWave(), 5 to pulseWave(),
            64 to List(32) { if (it % 2 == 1) 127 else -128 },
        )
        val p = HashMap<String, Map<Int, Cell>>()
        p["a0"] = mapOf(0 to c(9, 1, '4' to 0x22), 8 to c(95), 16 to c(OFF, null, 'C' to 0x0A), 40 to c(48, 1),
            63 to c(OFF, null, 'A' to 0x00))
        p["a1"] = mapOf(
            0 to c(60, 2, 'C' to 0x08, 'P' to 0x90, '4' to 0x37, 'A' to 0x01),
            // Blank columns before used ones are left out of the image.
            1 to Cell().withEffect(2, code('W'), 0x40),
            2 to Cell().withEffect(1, code('1'), 0x10).withEffect(3, code('A'), 0x0F),
            32 to c(OFF),
        )
        p["a2"] = mapOf(5 to c(null, 64), 6 to c(72, null, '0' to 0x47), 20 to c(74, 3, 'T' to 0x80), 24 to c(null, null, 'T' to 0x00))
        p["a3"] = mapOf(0 to c(36, 3), 12 to c(38, 64, 'W' to 0x01))
        p["a4"] = mapOf(0 to c(84, 64, 'S' to 0x02), 4 to c(86, 1, 'G' to 0x02))
        // Document orders: 0, parked 1, 2, 3; B03 = document order 3, which plays as order 2.
        p["b0"] = mapOf(0 to c(50, 2, 'G' to 0x01), 6 to c(52, 1), 15 to c(null, null, 'B' to 0x03, 'D' to 0x00))
        p["b3"] = mapOf(3 to c(64, 3, 'G' to 0x03, 'S' to 0x02), 10 to c(64, null, '3' to 0x10), 12 to c(67, null, '1' to 0x04, '2' to 0x00))
        p["c4"] = mapOf(0 to c(36, 2, 'F' to 0x04, 'B' to 0x02))
        p["parked"] = mapOf(0 to c(40, 2, 'B' to 0x00))
        val orders = listOf(
            Order(64, listOf("a0", "a1", "a2", "a3", "a4")),
            Order(8, listOf("parked", null, null, null, null)),
            Order(20, listOf("b0", null, null, "b3", null)),
            Order(1, listOf(null, null, null, null, "c4")),
        )
        return song(SccVariant.SCC, 5, orders, p, i, waves, loop = 2, multispeed = 3).copy(parkedOrders = listOf(1))
    }
}
