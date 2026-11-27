package org.thortracker.system.studio

import kotlin.math.roundToInt
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.system.panel.PanelDrag
import org.thortracker.ui.*

/** Draw uses 64 independent endpoints; only the periodic Play preview connects point 64 back to 1. */
internal class StudioWaveCanvas {
    private val grid = Rect()
    private val rail = Rect()
    private val xs = FloatArray(65)
    private val ys = FloatArray(65)
    private val labels = arrayOf("A", "B", "C", "D")
    private val stroke = PanelDrag<Stroke>()

    private class Stroke(v: ViewController, val x: Float, val y: Float, var point: Int, var value: Int) {
        val state = v.state
        val slot = state.instrument
        val source = state.waveSource
        val revision = StudioSoundView.panel(state).revision
        var version = v.session.documentVersion
        var painting = false
    }

    fun draw(ink: Ink, c: Surface, area: Rect, p: WavetablePatch, source: Int, point: Int, drawing: Boolean, accent: Int) {
        val palette = ink.palette
        ink.rect(c, area, Palette.mix(palette.BG, accent, .07f))
        val pad = minOf(ink.unit * .6f, area.width() * .06f)
        val left = area.left + pad
        val right = area.right - pad
        val width = right - left
        val labelH = minOf(ink.unit * 1.6f, area.height() * .24f)
        val size = minOf(ink.unit, labelH * .8f)
        if (drawing) {
            val caption = "Editing ${labels[source]}   ${point + 1}/64   ${p.waves.point(source, point)}"
            ink.mono(c, ink.fit(caption, width, size), left, area.top, labelH, palette.TEXT, true, size)
            grid.set(left, area.top + labelH, right, area.bottom - pad)
            if (grid.height() < 1f || grid.width() < 1f) return
            val mid = grid.top + grid.height() * 127f / 255f
            c.line(left, mid, right, mid, palette.DIM, ink.dp(1f))
            for (i in 0..63 step 8) {
                val x = left + width * i / 63f
                c.line(x, grid.top, x, grid.bottom, palette.RULE, ink.dp(1f))
            }
            trace(c, p, source, 0, left, grid.top, width, grid.height(), false, accent, ink.dp(1.8f))
            c.line(xs[point], grid.top, xs[point], grid.bottom, palette.FOCUS, ink.dp(1f))
            c.fillCircle(xs[point], ys[point], ink.dp(3.3f), palette.FOCUS)
            return
        }
        val morph = p.value(0)
        val thumbH = area.height() * .24f
        val cell = width / 4f
        for (i in 0..3) {
            val x = left + i * cell
            val inset = minOf(ink.unit, cell * .28f)
            ink.mono(c, labels[i], x, area.top, thumbH, palette.DIM, true, minOf(size, inset))
            trace(c, p, i, 0, x + inset, area.top + thumbH * .12f, cell - inset - pad * .5f, thumbH * .76f, true, palette.DIM, ink.dp(1f))
        }
        val baseTop = area.top + thumbH + ink.dp(2f)
        val baseH = area.height() * .37f
        c.line(left, baseTop + baseH / 2, right, baseTop + baseH / 2, palette.RULE, ink.dp(1f))
        trace(c, p, morph / 100, morph % 100, left, baseTop, width, baseH, true, accent, ink.dp(2f))
        rail.set(left, baseTop + baseH, right, area.bottom)
        val y = rail.top + rail.height() * .30f
        val low = (morph - p.value(2) * 3).coerceAtLeast(0)
        val high = (morph + p.value(2) * 3).coerceAtMost(300)
        val active = p.value(1) != 0
        c.line(left, y, right, y, palette.RULE, ink.dp(1f))
        c.line(left + width * low / 300, y, left + width * high / 300, y, if (active) accent else palette.DIM, ink.dp(3f))
        for (i in 0..3) {
            val x = left + width * i / 3f
            c.line(x, y - ink.dp(2f), x, y + ink.dp(2f), palette.TEXT, ink.dp(1f))
            ink.mono(c, labels[i], x, y + ink.dp(2f), (rail.bottom - y - ink.dp(2f)).coerceAtLeast(0f),
                palette.TEXT, i * 100 == morph, size, when(i) { 0 -> Align.LEFT; 3 -> Align.RIGHT; else -> Align.CENTER })
        }
        c.fillCircle(left + width * morph / 300, y, minOf(ink.dp(3.6f), rail.height() * .27f), palette.FOCUS)
    }

    private fun trace(c: Surface, p: WavetablePatch, source: Int, blend: Int, left: Float, top: Float,
                      width: Float, height: Float, periodic: Boolean, color: Int, weight: Float) {
        val last = if (periodic) 64 else 63
        for (i in 0..last) {
            val index = i and 63
            val a = p.waves.point(source, index)
            val sample = if (blend == 0) a.toFloat() else a + (p.waves.point(source + 1, index) - a) * blend / 100f
            xs[i] = left + width * i / last
            ys[i] = top + height * (127f - sample) / 255f
        }
        c.polyline(xs, ys, last + 1, color, weight)
    }

    /** The line is continuous; the A–D labels below it recall exact anchors. */
    fun morphAt(x: Float, y: Float): Int? {
        if (rail.width() <= 0f || y < rail.top || y >= rail.bottom || x !in rail.left..rail.right) return null
        val ratio = (x - rail.left) / rail.width()
        return if (y >= rail.centerY()) 100 * (ratio * 3).roundToInt() else (ratio * 300).roundToInt()
    }
    val morphUnitsPerPixel: Float get() = 300f / rail.width()
    private fun pointAt(x: Float) = (63f * ((x - grid.left) / grid.width()).coerceIn(0f, 1f)).roundToInt()
    private fun heightAt(y: Float) = 127 - (255f * ((y - grid.top) / grid.height()).coerceIn(0f, 1f)).roundToInt()

    fun begin(v: ViewController, p: WavetablePatch, x: Float, y: Float): Boolean {
        if (!grid.contains(x,y)) return false
        val st = v.state
        val field = StudioModuleControls.fields(p, st).indexOf(StudioModuleControls.HEIGHT)
        if (field < 0) return false
        StudioSoundView.selectField(v, field + 2)
        st.wavePoint = pointAt(x)
        stroke.begin(v.session, "Draw wave ${labels[st.waveSource]}", Stroke(v, x, y, st.wavePoint, heightAt(y)))
        return true
    }

    val captured: Boolean get() = stroke.target != null
    fun finish(v: ViewController) { stroke.finish(v.session) }

    fun move(v: ViewController, x: Float, y: Float, slop: Float) {
        val d = stroke.live(v.session) ?: return
        val st = v.state
        if (st !== d.state || st.kind != ViewKind.INSTRUMENT || st.instrument != d.slot || st.waveSource != d.source ||
            st.wavePoint != d.point || st.wavetablePage != 1 || StudioSoundView.panel(st).advanced ||
            StudioSoundView.panel(st).revision != d.revision || v.session.documentVersion != d.version) { finish(v); return }
        val p = StudioSoundView.patch(v.song, st) as? WavetablePatch ?: run { finish(v); return }
        if (!d.painting) {
            val dx = x - d.x; val dy = y - d.y
            if (dx * dx + dy * dy < slop * slop) return
            d.painting = true
        }
        val point = pointAt(x); val value = heightAt(y)
        val waves = p.waves.withStroke(d.source, d.point, d.value, point, value)
        st.wavePoint = point; d.point = point; d.value = value
        if (waves !== p.waves && StudioInstruments.store(v.session, d.slot, p.copy(waves = waves), "Draw wave ${labels[d.source]}")) d.version++
    }
}
