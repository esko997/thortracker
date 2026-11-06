package org.thortracker.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/** The shared [Surface] over an Android [Canvas]: anti-aliased paints, monospace and condensed sans faces. */
class CanvasSurface : Surface {
    /** The canvas being drawn; set by the view for each draw. Measuring needs none. */
    var canvas: Canvas? = null

    private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val sans: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val sansBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** Reused for every text draw; [Paint.getFontMetrics] with an argument does not allocate. */
    private val metrics = Paint.FontMetrics()

    private val c: Canvas get() = canvas ?: error("CanvasSurface used outside a draw")

    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = color
        c.drawRect(l, t, r, b, fill)
    }

    override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        fill.color = color
        c.drawRoundRect(l, t, r, b, radius, radius, fill)
    }

    override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        if (radius > 0f) c.drawRoundRect(l, t, r, b, radius, radius, stroke) else c.drawRect(l, t, r, b, stroke)
    }

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        c.drawLine(x0, y0, x1, y1, stroke)
    }

    override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {
        fill.color = color
        c.drawCircle(cx, cy, radius, fill)
    }

    override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        c.drawCircle(cx, cy, radius, stroke)
    }

    override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {
        if (count < 2) return
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until count) path.lineTo(xs[i], ys[i])
        stroke.color = color
        stroke.strokeWidth = width
        c.drawPath(path, stroke)
    }

    private fun face(face: Face, bold: Boolean, size: Float) {
        text.typeface = when (face) { Face.MONO -> if (bold) monoBold else mono; Face.SANS -> if (bold) sansBold else sans }
        text.textSize = size
    }

    override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
        face(face, bold, size)
        text.color = color
        text.textAlign = when (align) { Align.LEFT -> Paint.Align.LEFT; Align.CENTER -> Paint.Align.CENTER; Align.RIGHT -> Paint.Align.RIGHT }
        text.getFontMetrics(metrics)
        c.drawText(s, x, top + h / 2f - (metrics.ascent + metrics.descent) / 2f, text)
    }

    override fun textWidth(s: String, face: Face, bold: Boolean, size: Float): Float {
        face(face, bold, size)
        return text.measureText(s)
    }
}
