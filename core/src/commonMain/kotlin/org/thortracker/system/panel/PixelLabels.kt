package org.thortracker.system.panel

import kotlin.math.roundToInt
import org.thortracker.ui.Surface

/** Original 5x7 display lettering. Integer pixels stay crisp on Android and both SDL frontends. */
internal object PixelLabels {
    const val HEIGHT = 7
    fun width(text: String, pixel: Float): Float = if (text.isEmpty()) 0f else (text.length * 6 - 1) * pixel.roundToInt().coerceAtLeast(1)

    fun draw(c: Surface, text: String, left: Float, top: Float, pixel: Float, color: Int,
             maxWidth: Float = Float.POSITIVE_INFINITY) {
        val step = pixel.roundToInt().coerceAtLeast(1).toFloat()
        val origin = left.roundToInt().toFloat()
        val y0 = top.roundToInt().toFloat()
        for (index in text.indices) {
            val x0 = origin + index * 6 * step
            if (x0 + 5 * step > origin + maxWidth) break
            val glyph = bits(text[index].uppercaseChar())
            for (row in 0..6) for (column in 0..4) {
                if (glyph and (1L shl (34 - row * 5 - column)) != 0L) {
                    val x = x0 + column * step
                    val y = y0 + row * step
                    c.fillRect(x, y, x + step, y + step, color)
                }
            }
        }
    }

    private fun bits(char: Char): Long = when (char) {
        'A' -> 0x3A31FC631L
        'B' -> 0x7A31F463EL
        'C' -> 0x3E108420FL
        'D' -> 0x7A318C63EL
        'E' -> 0x7E10F421FL
        'F' -> 0x7E10F4210L
        'G' -> 0x3E10BC62EL
        'H' -> 0x4631FC631L
        'I' -> 0x7C842109FL
        'J' -> 0x1C4214A4CL
        'K' -> 0x4654C5251L
        'L' -> 0x42108421FL
        'M' -> 0x4775AC631L
        'N' -> 0x47359C631L
        'O' -> 0x3A318C62EL
        'P' -> 0x7A31F4210L
        'Q' -> 0x3A318D64DL
        'R' -> 0x7A31F5251L
        'S' -> 0x3E107043EL
        'T' -> 0x7C8421084L
        'U' -> 0x46318C62EL
        'V' -> 0x46318C544L
        'W' -> 0x4631AD771L
        'X' -> 0x462A22A31L
        'Y' -> 0x462A21084L
        'Z' -> 0x7C222221FL
        '0' -> 0x3A33AE62EL
        '1' -> 0x11842108EL
        '2' -> 0x3A211111FL
        '3' -> 0x78217043EL
        '4' -> 0x08CA97C42L
        '5' -> 0x7E10F043EL
        '6' -> 0x3A10F462EL
        '7' -> 0x7C2222108L
        '8' -> 0x3A317462EL
        '9' -> 0x3A317842EL
        '-' -> 0x0000F8000L
        '+' -> 0x0084F9080L
        '.' -> 0x0000000C6L
        '/' -> 0x042222210L
        ':' -> 0x008401080L
        '?' -> 0x3A2111004L
        ' ' -> 0x000000000L
        else -> 0x3A2111004L
    }
}
