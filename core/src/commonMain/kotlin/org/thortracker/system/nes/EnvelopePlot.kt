package org.thortracker.system.nes

import kotlin.math.abs
import kotlin.math.roundToInt
import org.thortracker.ui.Align
import org.thortracker.ui.Ink
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * The step editor of a [NesEnvelope] (FamiStudio-style software envelopes: the NES instruments and the Mega Drive's
 * PSG sounds): its steps as bars on the value scale of [NesEnvelopeKind.range], the loop region and release point, the
 * append position, the cursor and step numbers, scrolled to keep the cursor in view. Remembers where it drew so a touch
 * can be turned into a step and value ([stepAt], [valueAt]).
 */
internal class EnvelopePlot(private val ink: Ink) {
    /** Where the bars were drawn (empty when they were not). */
    var graph = Rect()
        private set
    var cellW = 0f
        private set
    private var lo = 0
    private var hi = 15
    private var stepScroll = 0

    fun hide() { graph = Rect() }

    /**
     * Draws [env] of [kind] in [r] under [title], cursor at [step]; [valueText] names a value on the scale, [muted]
     * greys bars the listening channel ignores, [focused] when the cursor is on the steps ([cursor] its colour,
     * [editing] while Edit is held).
     */
    fun draw(c: Surface, r: Rect, title: String, kind: NesEnvelopeKind, env: NesEnvelope, step: Int, valueText: (Int) -> String,
             muted: Boolean, col: Int, focused: Boolean, cursor: Int, editing: Boolean) {
        val values = env.values
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        ink.label(c, ink.fit(title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, ink.unit * 1.4f, if (focused) Palette.DIM else Palette.FAINT, size = ink.unit * 0.8f)
        val labelH = ink.unit * 1.1f
        val inner = Rect(r.left + ink.unit * 2.2f, r.top + ink.unit * 1.5f, r.right - ink.unit * 0.5f, r.bottom - ink.unit * 0.3f - labelH)
        graph = inner
        if (inner.height() <= 0f) return

        // value scale: offsets span the largest written magnitude, at least an octave / 12 period units
        if (kind.range.first < 0) {
            val span = minOf(kind.range.last + 1, maxOf(12, values.maxOfOrNull { abs(it) } ?: 0))
            lo = -span; hi = span
        } else { lo = kind.range.first; hi = kind.range.last }
        fun yOf(value: Int) = inner.bottom - inner.height() * (value - lo).toFloat() / (hi - lo)

        // columns: at least 24, wide enough to read; scroll to keep the cursor in view
        val minCell = ink.unit * 1.15f
        val columns = maxOf(24, values.size + 1)
        cellW = maxOf(minCell, inner.width() / columns)
        val fit = maxOf(1, (inner.width() / cellW).toInt())
        stepScroll = stepScroll.coerceIn(maxOf(0, step - fit + 1), step)
        stepScroll = stepScroll.coerceAtMost(maxOf(0, columns - fit))

        // scale labels and zero / limit rules
        val ls = ink.unit * 0.7f
        for (value in listOf(hi, if (lo < 0) 0 else -1, lo)) {
            if (value < lo) continue
            val yv = yOf(value)
            ink.line(c, inner.left, yv, inner.right, yv, if (value == 0 && lo < 0) Palette.BAR else Palette.RULE, ink.dp(1f))
            ink.mono(c, valueText(value).take(5), inner.left - ink.unit * 0.3f, yv - ls, ls * 2, Palette.FAINT, size = ls, align = Align.RIGHT)
        }

        // loop region (the part that repeats while held) and release point
        val loopEnd = if (env.release >= 0) env.release else values.size
        fun xOf(i: Int) = inner.left + (i - stepScroll) * cellW
        if (env.loop >= 0) {
            val x0 = maxOf(inner.left, xOf(env.loop))
            val x1 = minOf(inner.right, xOf(loopEnd))
            if (x1 > x0) {
                ink.rect(c, x0, inner.top, x1, inner.bottom, Palette.alpha(Palette.LISTEN, 26))
                ink.rect(c, x0, inner.top, x1, inner.top + ink.dp(3f), Palette.LISTEN)
                ink.label(c, "loop", x0 + ink.dp(3f), inner.top + ink.dp(3f), ink.unit, Palette.LISTEN, size = ink.unit * 0.7f)
            }
        }
        if (env.release >= 0) {
            val x = xOf(env.release)
            if (x >= inner.left && x <= inner.right) {
                ink.line(c, x, inner.top, x, inner.bottom, Palette.WRITE, ink.dp(2f))
                ink.label(c, "release", x + ink.dp(3f), inner.bottom - ink.unit, ink.unit, Palette.WRITE, size = ink.unit * 0.7f)
            }
        }

        // bars
        val base = if (lo < 0) yOf(0) else inner.bottom
        val gap = maxOf(1f, cellW * 0.12f)
        val barColor = if (muted) Palette.FAINT else Palette.alpha(col, 170)
        val last = minOf(values.size, stepScroll + fit)
        for (i in stepScroll until last) {
            val x = xOf(i)
            val yv = yOf(values[i])
            val t = minOf(base, yv)
            val b = maxOf(base, yv)
            val on = i == step
            ink.rect(c, x + gap, minOf(t, b - ink.dp(2f)), x + cellW - gap, b, if (on && focused) (if (editing) Palette.WRITE else col) else barColor)
        }
        // append position
        if (values.size < NesEnvelope.MAX_LENGTH && values.size in stepScroll until stepScroll + fit) {
            val x = xOf(values.size)
            ink.outline(c, Rect(x + gap, inner.top + gap, x + cellW - gap, inner.bottom - gap), Palette.FAINT, ink.dp(1f), ink.dp(2f))
            ink.mono(c, "+", x + cellW / 2, inner.top, inner.height(), Palette.FAINT, size = ink.unit * 0.8f, align = Align.CENTER)
        }
        // cursor
        if (step in stepScroll until stepScroll + fit) {
            val x = xOf(step)
            ink.outline(c, Rect(x, inner.top, x + cellW, inner.bottom), if (focused) cursor else Palette.FAINT, ink.dp(if (focused) 2f else 1f), ink.dp(2f))
        }
        // step numbers
        val every = when { cellW >= ink.unit * 2f -> 1; cellW >= ink.unit * 1.4f -> 2; else -> 4 }
        for (i in stepScroll until minOf(stepScroll + fit, values.size + 1)) {
            if (i % every != 0 && i != step) continue
            ink.mono(c, i.toString(), xOf(i) + cellW / 2, inner.bottom + ink.dp(2f), labelH, if (i == step) Palette.TEXT else Palette.FAINT, size = ink.unit * 0.65f, align = Align.CENTER)
        }
    }

    /** True when a touch at ([x], [y]) is on the bars. */
    fun contains(x: Float, y: Float): Boolean = graph.contains(x, y) && cellW > 0f

    /** The step under [x], at most [last]. */
    fun stepAt(x: Float, last: Int): Int = (stepScroll + ((x - graph.left) / cellW).toInt()).coerceIn(0, last)

    /** The value at height [y] on the last drawn scale. */
    fun valueAt(y: Float): Int {
        val t = ((graph.bottom - y) / graph.height()).coerceIn(0f, 1f)
        return (lo + t * (hi - lo)).roundToInt()
    }

    companion object {
        /** The plot's title for an envelope named [kindLabel] at [step]: the step's value, or how to add steps. */
        fun title(kindLabel: String, values: List<Int>, step: Int, valueText: (Int) -> String, edit: String, up: String): String = when {
            step < values.size -> "$kindLabel step $step of ${values.size}: ${valueText(values[step])}"
            values.isEmpty() -> "$kindLabel: no steps; hold $edit and press $up to add one"
            else -> "$kindLabel: ${values.size} steps; $edit adds step ${values.size}"
        }
    }
}
