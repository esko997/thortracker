package org.thortracker.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.ProjectCodec

/**
 * External check of the `.uge` boundary: `src/test/resources/uge2source/song_thor_groove.c` is the output of the
 * pinned hUGETracker 1.0.11 `uge2source` (gbc-puzzler `.tools/uge2source`) for the adjacent `thor-groove.thor` fixture.
 * The preview data from [HugeLowering] must equal what the real converter produced from that file: rows,
 * order lists, `order_cnt`, tempo, instrument structs and waves.
 *
 * The source project is frozen independently of the application's editable demo. To replace both fixtures:
 * `./gradlew :app:exportSamples`, copy `app/build/samples/thor-groove.thor` alongside this C fixture, then
 * `.tools/uge2source app/build/samples/thor-groove.uge -b 255 song_thor_groove app/src/test/resources/uge2source/song_thor_groove.c`.
 */
class Uge2sourceFixtureTest {
    private val source: String = javaClass.getResource("/uge2source/song_thor_groove.c")!!.readText()

    private fun block(header: String): String {
        val start = source.indexOf(header)
        assertTrue("missing $header", start >= 0)
        return source.substring(source.indexOf('{', start) + 1, source.indexOf("};", start))
    }

    private fun structs(header: String): List<List<Int>> =
        Regex("""\{([0-9,]+)\}""").findAll(block(header)).map { m -> m.groupValues[1].split(',').map { it.toInt() } }.toList()

    /** Note names of hUGEDriver.h (`C_3`, `Cs3`, ..., `___` = 90). */
    private fun note(name: String): Int {
        if (name == "___") return 90
        val names = listOf("C_", "Cs", "D_", "Ds", "E_", "F_", "Fs", "G_", "Gs", "A_", "As", "B_")
        val index = names.indexOf(name.substring(0, 2))
        assertTrue("unknown note $name", index >= 0)
        return (name.substring(2).toInt() - 3) * 12 + index
    }

    /** hUGEDriver.h `DN(A, B, C)`. */
    private fun dn(note: Int, instrument: Int, effect: Int) = listOf(
        (note or ((instrument and 0x10) shl 3)) and 0xFF,
        ((instrument shl 4) and 0xFF) or (effect shr 8),
        effect and 0xFF,
    )

    private fun patterns(): Map<String, List<Int>> =
        Regex("""static const unsigned char (P\d+)\[\] = \{(.*?)\};""", RegexOption.DOT_MATCHES_ALL).findAll(source)
            .associate { m ->
                val rows = Regex("""DN\((\w+),(\d+),0x([0-9A-F]{3})\)""").findAll(m.groupValues[2]).toList()
                assertEquals(64, rows.size)
                m.groupValues[1] to rows.flatMap { r ->
                    dn(note(r.groupValues[1]), r.groupValues[2].toInt(), r.groupValues[3].toInt(16))
                }
            }

    private fun ByteArray.unsigned() = map { it.toInt() and 0xFF }

    @Test
    fun loweredProjectEqualsPinnedUge2sourceOutput() {
        val data = HugeLowering.lower(ProjectCodec.decode(javaClass.getResource("/uge2source/thor-groove.thor")!!.readText()))
        val tempo = Regex("""song_thor_groove = \{(\d+),""").find(source)!!.groupValues[1].toInt()
        assertEquals(data.ticksPerRow, tempo)
        val orderCount = Regex("""order_cnt = (\d+);""").find(source)!!.groupValues[1].toInt()
        assertEquals(data.orderCountByte, orderCount)

        val patterns = patterns()
        for (ch in 0 until 4) {
            val order = block("order${ch + 1}[]").split(',').map { it.trim() }
            assertEquals(data.orders[ch].size, order.size)
            order.forEachIndexed { position, name ->
                assertEquals("channel $ch order $position ($name)",
                    patterns.getValue(name), data.tracks[data.orders[ch][position]].unsigned())
            }
        }

        // uge2source emits instruments up to the highest used slot: {sweep,len_duty,envelope,subpattern,highmask}.
        val duty = structs("duty_instruments[]")
        assertTrue(duty.isNotEmpty())
        duty.forEachIndexed { i, s ->
            val b = data.duty[i].unsigned()
            assertEquals(listOf(b[0], b[1], b[2], b[3] or (b[4] shl 8), b[5]), s)
        }
        val wave = structs("wave_instruments[]")
        assertTrue(wave.isNotEmpty())
        wave.forEachIndexed { i, s ->
            val b = data.wave[i].unsigned()
            assertEquals(listOf(b[0], b[1], b[2], b[3] or (b[4] shl 8), b[5]), s)
        }
        // {envelope, subpattern, highmask, unused1, unused2}
        val noise = structs("noise_instruments[]")
        assertTrue(noise.isNotEmpty())
        noise.forEachIndexed { i, s ->
            val b = data.noise[i].unsigned()
            assertEquals(listOf(b[0], b[1] or (b[2] shl 8), b[3], b[4], b[5]), s)
        }

        val waves = block("waves[]").split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }
        assertEquals(0, waves.size % 16)
        waves.chunked(16).forEachIndexed { i, packed -> assertEquals("wave $i", data.waves[i].unsigned(), packed) }
    }
}
