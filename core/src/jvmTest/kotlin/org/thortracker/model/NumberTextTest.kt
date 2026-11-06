package org.thortracker.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/** [NumberText] must write exactly what `java.util.Formatter` wrote before the core became multiplatform. */
class NumberTextTest {
    private fun check(value: Double) {
        for (decimals in 0..3) {
            assertEquals("%.${decimals}f of $value", String.format(Locale.ROOT, "%.${decimals}f", value), NumberText.fixed(value, decimals))
            assertEquals("%+.${decimals}f of $value", String.format(Locale.ROOT, "%+.${decimals}f", value), NumberText.fixed(value, decimals, plus = true))
        }
    }

    @Test
    fun fixedMatchesJavaFormatter() {
        val special = listOf(
            0.0, -0.0, 0.5, 1.5, 2.5, -0.5, -2.5, 0.05, 0.15, 0.25, 0.35, 1.005, 2.675, 0.0005, 0.00049, 0.0096, 0.00096,
            9.95, 9.995, 99.5, 999.9996, -0.04, -0.0004, 1e-7, 123456789.125, 1e20, 1.0e23, 4.35, 1200.0, 701.955,
            Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        )
        special.forEach(::check)
        // The values the editor shows: envelope/length/sweep timings, and cents errors.
        for (v in 0..256) {
            check(v * 1000.0 / 64); check(v * 1000.0 / 256); check(v * 1000.0 / 128)
        }
        val random = Random(1234)
        repeat(20_000) {
            check((random.nextDouble() - 0.5) * 200.0)
            check((random.nextInt(-100_000, 100_000)) / 1000.0)
            check((random.nextInt(-100_000, 100_000)) / 200.0)
        }
    }

    @Test
    fun hexMatchesJavaFormatter() {
        for (v in listOf(0, 1, 9, 10, 15, 16, 0xAB, 0xFF, 0x100, 0xFFFF, Int.MAX_VALUE, -1, -16, Int.MIN_VALUE)) {
            assertEquals(String.format("%X", v), NumberText.hex(v))
            assertEquals(String.format("%02X", v), NumberText.hex(v, 2))
        }
    }
}
