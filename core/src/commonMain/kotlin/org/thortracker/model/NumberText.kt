package org.thortracker.model

import kotlin.math.abs

/**
 * Number formatting for on-screen text, identical on every platform. It reproduces what `java.util.Formatter`
 * writes in `Locale.ROOT` for the patterns the core uses, so the text is unchanged from the JVM-only code.
 */
object NumberText {
    /**
     * `%.{decimals}f`, or `%+.{decimals}f` with [plus]. Like `java.util.Formatter`, it rounds half up on the
     * shortest decimal digits that identify [value] (so 0.15 gives "0.2"), and keeps the sign of negative values
     * that round to zero ("-0.0").
     */
    fun fixed(value: Double, decimals: Int, plus: Boolean = false): String {
        require(decimals >= 0) { "decimals must not be negative" }
        if (value.isNaN()) return "NaN"
        val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
        val sign = if (negative) "-" else if (plus) "+" else ""
        if (value.isInfinite()) return sign + "Infinity"
        val (digits, point) = shortestDigits(abs(value))
        // The integer value * 10^decimals, as decimal digits, rounded half up at the last kept digit.
        val keep = point + decimals
        val scaled = when {
            digits.isEmpty() || keep < 0 -> "0"
            keep >= digits.length -> digits + "0".repeat(keep - digits.length)
            keep == 0 -> if (digits[0] >= '5') "1" else "0"
            digits[keep] >= '5' -> increment(digits.substring(0, keep))
            else -> digits.substring(0, keep)
        }.trimStart('0').padStart(decimals + 1, '0')
        if (decimals == 0) return sign + scaled
        return sign + scaled.substring(0, scaled.length - decimals) + "." + scaled.substring(scaled.length - decimals)
    }

    /** `%X`, or `%0{width}X`: upper-case hexadecimal of the 32-bit two's-complement value. */
    fun hex(value: Int, width: Int = 1): String = value.toUInt().toString(16).uppercase().padStart(width, '0')

    /**
     * The significant digits of [value] (>= 0) from its round-trip text, without leading or trailing zeros, and
     * the position of the decimal point: value = 0.digits * 10^point. Zero has no digits.
     */
    private fun shortestDigits(value: Double): Pair<String, Int> {
        val text = value.toString()
        val e = text.indexOfFirst { it == 'E' || it == 'e' }
        val mantissa = if (e < 0) text else text.substring(0, e)
        val exponent = if (e < 0) 0 else text.substring(e + 1).removePrefix("+").toInt()
        val dot = mantissa.indexOf('.')
        val whole = if (dot < 0) mantissa else mantissa.substring(0, dot)
        val fraction = if (dot < 0) "" else mantissa.substring(dot + 1)
        val all = whole + fraction
        val leading = all.indexOfFirst { it != '0' }
        if (leading < 0) return "" to 0
        return all.substring(leading).trimEnd('0') to whole.length + exponent - leading
    }

    private fun increment(digits: String): String {
        val out = digits.toCharArray()
        var i = out.lastIndex
        while (i >= 0) {
            if (out[i] != '9') { out[i] = out[i] + 1; return out.concatToString() }
            out[i] = '0'
            i--
        }
        return "1" + out.concatToString()
    }
}
