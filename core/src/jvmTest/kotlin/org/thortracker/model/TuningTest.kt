package org.thortracker.model

import org.thortracker.system.gb.HugeRules
import org.thortracker.system.gb.GbSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log2

class TuningTest {
    private val c5 = 24

    /** Bend of [y] period units on [note] in cents, straight from the GB pitch formula and the driver table. */
    private fun bend(note: Int, y: Int): Double {
        val gap = 2048 - HugeRules.NOTE_PERIODS[note]
        return 1200.0 * log2(gap.toDouble() / (gap - y))
    }

    private fun equal(steps: Int, period: Double = 1200.0) = List(steps) { period * (it + 1) / steps }

    private fun rejects(text: String, fragment: String) {
        val e = assertThrows(IllegalArgumentException::class.java) { Scl.parse(text, "file") }
        assertTrue("\"${e.message}\" should mention \"$fragment\"", e.message!!.contains(fragment))
    }

    @Test
    fun notePeriodsMatchTheDriverTable() {
        val path = "third_party/hUGEDriver-6.1.3/include/hUGE_note_table.inc"
        val file = listOf(File("../$path"), File(path)).first { it.isFile }
        val table = file.readLines().map { it.trim() }.filter { it.startsWith("dw ") }.map { it.removePrefix("dw ").trim().toInt() }
        assertEquals(72, table.size)
        assertEquals(table, HugeRules.NOTE_PERIODS.toList())
    }

    @Test
    fun pitchHzAndDetuneOfFollowTheDriver() {
        assertEquals(131072.0 / (2048 - 1546), HugeRules.pitchHz(24, 0), 1e-9)
        assertEquals(131072.0 / (2048 - 1546 - 15), HugeRules.pitchHz(24, 15), 1e-9)
        assertEquals(0, HugeRules.detuneOf(Cell(24, 1)))
        assertEquals(9, HugeRules.detuneOf(Cell(24, 1, 0x4, 0x09)))
        assertEquals(0, HugeRules.detuneOf(Cell(24, 1, 0x4, 0x00)))
        assertEquals(null, HugeRules.detuneOf(Cell(24, 1, 0x4, 0x19))) // real vibrato
        assertEquals(null, HugeRules.detuneOf(Cell(24, 1, 0x0, 0x47)))
        assertEquals(null, HugeRules.detuneOf(Cell(24, 1, 0xC, 0x08)))
    }

    @Test
    fun parsesTwelveEqualInCents() {
        val text = """
            |! 12-edo.scl
            |!
            |12 tone equal temperament
            | 12
            |!
            |${(1..11).joinToString("\n") { " ${it * 100}.0" }}
            | 2/1
            |""".trimMargin()
        val t = Scl.parse(text, "file")
        assertEquals("12 tone equal temperament", t.name)
        assertEquals(equal(12), t.cents)
        assertEquals(24, t.root)
        assertEquals(5, t.tolerance)
    }

    @Test
    fun parsesJustRatiosWithCommentsAndTrailingText() {
        val text = "! just.scl\r\n!\r\n\r\n12 ! twelve\r\n" +
            "16/15\r\n9/8 major whole tone\r\n! a comment between degrees\r\n6/5\r\n5/4\r\n4/3\r\n45/32\r\n" +
            "  3/2\tfifth\r\n8/5\r\n5/3\r\n9/5\r\n15/8\r\n2\r\n"
        val t = Scl.parse(text, "  Just intonation  ")
        assertEquals("Just intonation", t.name) // empty description falls back to the trimmed file name
        val ratios = listOf(16.0 / 15, 9.0 / 8, 6.0 / 5, 5.0 / 4, 4.0 / 3, 45.0 / 32, 3.0 / 2, 8.0 / 5, 5.0 / 3, 9.0 / 5, 15.0 / 8, 2.0)
        assertEquals(12, t.cents.size)
        ratios.forEachIndexed { i, r -> assertEquals(1200.0 * log2(r), t.cents[i], 1e-9) }
        assertEquals(1200.0, t.period, 0.0)
    }

    @Test
    fun parsesCentsWithBareDotsAndLongNames() {
        val t = Scl.parse("${"x".repeat(300)}\n4\n.5\n150. cents\n700.25\n1200.000\n", "file")
        assertEquals(listOf(0.5, 150.0, 700.25, 1200.0), t.cents)
        assertEquals(Tuning.MAX_NAME, t.name.length)
    }

    @Test
    fun rejectsMalformedScales() {
        rejects("", "no degree count")
        rejects("! only comments", "no description")
        rejects("name\nseven\n", "not a number of degrees")
        rejects("name\n0\n", "at least 1")
        rejects("name\n257\n" + (1..257).joinToString("\n") { "$it.0" }, "at most 256")
        rejects("name\n3\n100.0\n200.0\n", "declares 3 degrees but lists 2")
        rejects("name\n2\n100.0\n200.0\n300.0\n", "lists more")
        rejects("name\n2\n100.0\nabc\n", "\"abc\" is not a pitch")
        rejects("name\n2\n100.0\n1.2.3\n", "not a cents value")
        rejects("name\n2\n0/1\n2/1\n", "has a zero")
        rejects("name\n2\n3/0\n2/1\n", "has a zero")
        rejects("name\n2\n-3/2\n2/1\n", "negative")
        rejects("name\n2\n-5.0\n1200.0\n", "not above 1/1")
        rejects("name\n2\n0.0\n1200.0\n", "not above 1/1")
        rejects("name\n1\n1/1\n", "not above 1/1")
        rejects("name\n3\n3/2\n5/4\n2/1\n", "must ascend")
        rejects("name\n2\n700.0\n700.0\n", "must ascend")
        rejects("name\n3\n0.1\n0.2\n0.3\n", "average at least 1 cent")
    }

    @Test
    fun twelveEqualMapsToEveryPlainNote() {
        val map = TuningMap(Tuning("12", equal(12), root = c5), GbSystem)
        assertEquals(72, map.all.size)
        assertEquals(72, map.pitches.size)
        map.pitches.forEachIndexed { i, p ->
            assertEquals(i, p.note)
            assertEquals(i - c5, p.degree)
            assertEquals(0, p.detune)
            assertEquals(0.0, p.error, 1e-9)
            assertEquals(i, map.index(i, 0))
        }
        assertEquals("0/12", map.label(map.pitches[c5]))
        assertEquals("0/12 ·1", map.label(map.pitches[c5 + 12])) // C-6 stays C-6
        assertEquals("11/12 ·-1", map.label(map.pitches[c5 - 1]))
        // Rooted elsewhere, the same scale still covers every note.
        val onA = TuningMap(Tuning("12", equal(12), root = 9), GbSystem)
        assertEquals((0..71).toList(), onA.pitches.map { it.note })
    }

    @Test
    fun quarterTonesFollowTheAvailableBend() {
        val map = TuningMap(Tuning("24", equal(24), root = c5, tolerance = 5), GbSystem)
        assertEquals(-48, map.all.first().degree) // C-3
        assertEquals(95, map.all.last().degree) // B-8 + 50 cents
        // Independent expectation: plain degrees are exact; quarter tone 2k+1 can only be the note below bent ~50 cents.
        for (p in map.all) {
            val reachable = abs(p.error) <= 5.0
            if (p.degree % 2 == 0) {
                assertEquals(c5 + p.degree / 2, p.note)
                assertEquals(0, p.detune)
                assertTrue(reachable)
            } else {
                val below = c5 + Math.floorDiv(p.degree, 2)
                val possible = (0..15).any { y -> abs(bend(below, y) - 50.0) <= 5.0 }
                assertEquals("degree ${p.degree}", possible, reachable)
                if (reachable) assertEquals(below, p.note)
            }
        }
        fun reachableIn(notes: IntRange) = map.pitches.count { it.note in notes }
        // Octave 5 (C-5..B-5): a full bend is over 52 cents, so all 24 quarter-tone degrees play.
        assertTrue((24..35).all { bend(it, 15) > 52.0 })
        assertEquals(24, reachableIn(24..35))
        // Octave 3: at most ~26 cents of bend, so only the 12 plain notes.
        assertTrue((0..11).all { bend(it, 15) < 45.0 })
        assertEquals(12, reachableIn(0..11))
        assertTrue(map.pitches.filter { it.note in 0..11 }.all { it.detune == 0 })
    }

    @Test
    fun reachablePitchesAreAccurateUniqueAndIndexed() {
        val just = Scl.parse("just\n12\n16/15\n9/8\n6/5\n5/4\n4/3\n45/32\n3/2\n8/5\n5/3\n9/5\n15/8\n2/1\n", "f")
        for (tol in Tuning.TOLERANCES) for (tuning in listOf(just, Tuning("24", equal(24)), Tuning("31", equal(31), root = 30))) {
            val map = TuningMap(tuning.copy(tolerance = tol), GbSystem)
            assertTrue(map.pitches.isNotEmpty())
            assertEquals(map.pitches.size, map.pitches.map { it.note to it.detune }.toSet().size)
            for (p in map.all) {
                assertTrue(p.note in 0..71 && p.detune in 0..15)
                // Error is the nominal grid pitch minus the target.
                assertEquals(100.0 * (p.note - tuning.root) + bend(p.note, p.detune) - p.cents, p.error, 1e-9)
                // Only the note below the target or the next one up is used.
                val below = tuning.root + Math.floor(p.cents / 100.0).toInt()
                assertTrue(p.note == below.coerceIn(0, 71) || p.note == (below + 1).coerceIn(0, 71))
            }
            map.pitches.forEachIndexed { i, p ->
                assertTrue(abs(p.error) <= tol + 1e-9)
                assertEquals(i, map.index(p.note, p.detune))
                assertEquals(i, map.nearest(p.note, p.detune))
                if (i > 0) assertTrue(map.cents(p.note, p.detune) >= map.cents(map.pitches[i - 1].note, map.pitches[i - 1].detune))
            }
            val unreachable = map.all.filter { abs(it.error) > tol }
            for (p in unreachable) {
                if (map.pitches.none { it.note == p.note && it.detune == p.detune }) assertEquals(-1, map.index(p.note, p.detune))
            }
            // Every driver pitch snaps to the closest tuned pitch.
            for (note in 0..71) for (y in 0..15) {
                val n = map.nearest(note, y)
                val c = map.cents(note, y)
                val d = abs(map.cents(map.pitches[n].note, map.pitches[n].detune) - c)
                assertTrue(map.pitches.all { abs(map.cents(it.note, it.detune) - c) >= d - 1e-12 })
            }
        }
        assertEquals(-1, TuningMap(Tuning("12", equal(12)), GbSystem).index(24, 3))
        assertEquals(-1, TuningMap(Tuning("12", equal(12)), GbSystem).index(72, 0))
    }

    @Test
    fun justIntervalsUseTheBendThatFitsBest() {
        val map = TuningMap(Tuning("fifth", listOf(1200.0 * log2(1.5), 1200.0), root = c5, tolerance = 5), GbSystem)
        val fifth = map.all.single { it.degree == 1 }
        // 3/2 = 701.955 cents: G-5 is the note below, bent by whichever y lands closest.
        assertEquals(c5 + 7, fifth.note)
        val ys = (0..15).minBy { abs(700.0 + bend(c5 + 7, it) - 1200.0 * log2(1.5)) }
        assertEquals(ys, fifth.detune)
        assertTrue(abs(fifth.error) <= 5.0)
    }

    @Test
    fun nonOctavePeriodsRepeatAtTheirPeriod() {
        val tritave = 1200.0 * log2(3.0)
        val bp = Tuning("Bohlen-Pierce", equal(13, tritave), root = c5, tolerance = 10)
        val map = TuningMap(bp, GbSystem)
        assertEquals(13, map.steps)
        for (p in map.all) {
            val q = Math.floorDiv(p.degree, 13)
            val r = Math.floorMod(p.degree, 13)
            assertEquals(q * tritave + (if (r == 0) 0.0 else bp.cents[r - 1]), p.cents, 1e-9)
            assertTrue(p.cents >= 100.0 * (0 - c5) - 10.0 && p.cents < 100.0 * (71 - c5) + 100.0)
        }
        assertEquals(0, map.all.first { it.degree == 0 }.detune)
        assertEquals(c5, map.all.first { it.degree == 0 }.note)
        // 3/1 sits 1901.955 cents up: G-6 (1900 on the grid) plain is 1.955 flat, so it is reachable at 10 cents.
        val tritaveUp = map.all.single { it.degree == 13 }
        assertTrue(abs(tritaveUp.error) <= 10.0)
        assertEquals("0/13 ·1", map.label(tritaveUp))
        assertEquals("12/13 ·-1", map.label(map.all.single { it.degree == -1 }))
        assertTrue(map.pitches.all { abs(it.error) <= 10.0 + 1e-9 })
        assertFalse(map.pitches.isEmpty())
    }

    @Test
    fun invalidTuningsAreReported() {
        val ok = Tuning("ok", equal(12))
        assertEquals(emptyList<String>(), ok.problems(GbSystem.noteRange))
        for (bad in listOf(
            ok.copy(root = -1), ok.copy(root = 72), ok.copy(tolerance = 7), ok.copy(cents = emptyList()),
            ok.copy(cents = listOf(200.0, 100.0)), ok.copy(cents = listOf(-1.0, 1200.0)), ok.copy(cents = listOf(Double.NaN)),
            ok.copy(cents = equal(257)), ok.copy(name = "n".repeat(256)),
        )) {
            assertEquals("$bad", 1, bad.problems(GbSystem.noteRange).size)
            assertThrows(IllegalArgumentException::class.java) { TuningMap(bad, GbSystem) }
        }
    }
}
