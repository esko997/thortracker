package org.thortracker.system.ay

import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.Surface

/** Draw-operation capture for comparing the actual shared sidebar in the two faces. */
internal class SoundFaceCanvas : Surface {
    val ops = mutableListOf<String>()
    data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float)
    val lines = mutableListOf<Line>()
    var columnRight = 0f
    var columnTop = 0f
    private fun keep(x: Float, y: Float, op: () -> String) { if (x < columnRight - 0.01f && y >= columnTop - 0.01f) ops += op() }
    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = keep(l, t) { "fill $l $t $r $b $color" }
    override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = keep(l, t) { "round $l $t $r $b $radius $color" }
    override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = keep(l, t) { "stroke $l $t $r $b $color $width $radius" }
    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        lines += Line(x0, y0, x1, y1)
        keep(minOf(x0, x1), minOf(y0, y1)) { "line $x0 $y0 $x1 $y1 $color $width" }
    }
    override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = keep(cx - radius, cy - radius) { "circle $cx $cy $radius $color" }
    override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = keep(cx - radius, cy - radius) { "ring $cx $cy $radius $color $width" }
    override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {
        if (count > 0) keep((0 until count).minOf { xs[it] }, (0 until count).minOf { ys[it] }) { "poly ${xs.take(count)} ${ys.take(count)} $color $width" }
    }
    override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) = keep(x, top) { "text $s $x $top $h $color $face $bold $size $align" }
    override fun textWidth(s: String, face: Face, bold: Boolean, size: Float): Float = s.length * size / 2
}
