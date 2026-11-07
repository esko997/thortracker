package org.thortracker.model

import org.thortracker.system.gb.GbSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Scala files shipped in assets/tunings are offered by Menu → Tuning → Built-in tunings. */
class BuiltInTuningsTest {
    private val dir = listOf(File("../app/src/main/assets/tunings"), File("app/src/main/assets/tunings")).first { it.isDirectory }
    private fun load(name: String) = Scl.parse(File(dir, name).readText(), name.removeSuffix(".scl"))

    @Test fun everyBundledFileIsAUsableTuningWithReachableDegrees() {
        val files = dir.listFiles { f -> f.name.endsWith(".scl") }!!.map { it.name }.sorted()
        assertEquals(listOf("19-tet.scl", "22-edo.scl", "24-tet.scl", "just-5-limit.scl"), files)
        for (f in files) {
            val t = load(f)
            assertEquals(f, emptyList<String>(), t.problems(GbSystem.noteRange))
            assertTrue(f, TuningMap(t, GbSystem).pitches.isNotEmpty())
        }
    }

    /** 22-EDO uses inline `! k\22` comments after each value; the parser must ignore them. */
    @Test fun twentyTwoEdoReadsItsDescriptionDegreesAndPeriod() {
        val t = load("22-edo.scl")
        assertEquals("22 equal divisions of the octave", t.name)
        assertEquals(22, t.steps)
        t.cents.forEachIndexed { i, c -> assertEquals(1200.0 * (i + 1) / 22, c, 1e-5) }
        assertEquals(1200.0, t.period, 1e-9)
    }
}
