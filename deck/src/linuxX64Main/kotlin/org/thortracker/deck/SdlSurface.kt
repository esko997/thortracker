@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import decknative.*
import kotlinx.cinterop.*
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.Surface

/** The shared [Surface] over SDL3 (native/deck_draw.c): triangle geometry for shapes, stb_truetype text. */
class SdlSurface : Surface {
    private val px = nativeHeap.allocArray<FloatVar>(MAX_POINTS)
    private val py = nativeHeap.allocArray<FloatVar>(MAX_POINTS)

    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = dd_fill_rect(l, t, r, b, color.toUInt())

    override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) =
        dd_fill_round(l, t, r, b, radius, color.toUInt())

    override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) =
        dd_stroke_rect(l, t, r, b, color.toUInt(), width, radius)

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) =
        dd_line(x0, y0, x1, y1, color.toUInt(), width)

    override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = dd_fill_circle(cx, cy, radius, color.toUInt())

    override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) =
        dd_stroke_circle(cx, cy, radius, color.toUInt(), width)

    override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {
        val n = minOf(count, MAX_POINTS)
        for (i in 0 until n) { px[i] = xs[i]; py[i] = ys[i] }
        dd_polyline(px, py, n, color.toUInt(), width)
    }

    override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
        val f = face.code()
        val b = if (bold) 1 else 0
        val left = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - dd_text_width(f, b, size, s) / 2f
            Align.RIGHT -> x - dd_text_width(f, b, size, s)
        }
        dd_text(f, b, size, s, left, top + h / 2f + dd_text_center(f, b, size), color.toUInt())
    }

    override fun textWidth(s: String, face: Face, bold: Boolean, size: Float): Float = dd_text_width(face.code(), if (bold) 1 else 0, size, s)

    private fun Face.code() = if (this == Face.MONO) DD_MONO else DD_SANS

    private companion object { const val MAX_POINTS = 1024 }
}
