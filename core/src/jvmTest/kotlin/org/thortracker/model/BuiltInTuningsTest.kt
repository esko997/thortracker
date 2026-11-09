package org.thortracker.model

import org.thortracker.system.gb.GbSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bundled catalogues are structurally readable, and every shipped scale can be used by the actual mapper. */
class BuiltInTuningsTest {
    private val dir = listOf(File("../app/src/main/assets/tunings"), File("app/src/main/assets/tunings")).first { it.isDirectory }
    private fun load(name: String) = Scl.parse(File(dir, name).readText(), name.removeSuffix(".scl"))

    @Test fun everyBundledEntryIsAUsableTuningWithReachableDegrees() {
        val packs = dir.listFiles { f -> f.extension == "thorscales" }!!.map { TuningPackCodec.decode(it.readText()) }
        assertTrue(packs.isNotEmpty())
        assertEquals(packs.size, packs.map { it.id }.toSet().size)
        for (pack in packs) for (entry in pack.entries) {
            val identity = "${pack.id}/${entry.id}"
            val t = Scl.parse(entry.scl, entry.name)
            assertEquals(identity, emptyList<String>(), t.problems(GbSystem))
            assertTrue(identity, TuningMap(t, GbSystem).pitches.isNotEmpty())
        }
    }

    /** 22-EDO uses inline `! k\22` comments after each value; the parser must ignore them. */
    @Test fun twentyTwoEdoReadsItsDescriptionDegreesAndPeriod() {
        val t = load("22-edo.scl")
        assertEquals(22, t.steps)
        t.cents.forEachIndexed { i, c -> assertEquals(1200.0 * (i + 1) / 22, c, 1e-5) }
        assertEquals(1200.0, t.period, 1e-9)
    }
}
