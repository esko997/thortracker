package org.thortracker.system.panel

import kotlin.math.roundToInt
import org.thortracker.editor.EditorSession
import org.thortracker.editor.GestureToken
import org.thortracker.ui.Ink
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * Drawing and hit-testing pieces instrument panels share: a slider rail for a bounded value and a row of equal cells
 * for a short choice. They only draw in the workspace palette's roles through [Surface] (so Android and SDL render
 * them alike) and write their geometry into caller-owned [Rect]s, so a frame allocates nothing.
 */
internal object PanelWidgets {
    /**
     * A rail across [left]..[right] centred on [cy]: track, the part up to [fraction] (0..1) in [accent], a detent per
     * value when there are at most 16 [steps] (the value count − 1), and the knob. [selected] enlarges the knob;
     * [editing] colours it as a held edit.
     */
    fun rail(ink: Ink, c: Surface, left: Float, right: Float, cy: Float, fraction: Float, steps: Int, accent: Int,
             selected: Boolean, editing: Boolean) {
        val h = ink.dp(4f)
        c.fillRound(left, cy - h / 2, right, cy + h / 2, h / 2, ink.palette.RULE)
        val kx = left + (right - left) * fraction.coerceIn(0f, 1f)
        if (kx > left) c.fillRound(left, cy - h / 2, kx, cy + h / 2, h / 2, Palette.alpha(accent, 190))
        if (steps in 1..16) {
            for (i in 0..steps) {
                val x = left + (right - left) * i / steps
                c.fillRect(x - ink.dp(0.75f), cy + h, x + ink.dp(0.75f), cy + h * 2, ink.palette.FAINT)
            }
        }
        val knob = ink.dp(if (selected) 8f else 6f)
        c.fillCircle(kx, cy, knob, if (selected) (if (editing) ink.palette.WRITE else ink.palette.TEXT) else accent)
        if (selected) c.strokeCircle(kx, cy, knob, ink.palette.BG, ink.dp(1.5f))
    }

    /** The value of [min]..[max] under [x] on a rail across [left]..[right] (clamped at the ends). */
    fun railValue(x: Float, left: Float, right: Float, min: Int, max: Int): Int {
        if (right <= left || max <= min) return min
        val t = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return min + (t * (max - min)).roundToInt()
    }

    /** Lays [count] equal cells across [r] with [gap] between them into the first [count] of [cells]. */
    fun cells(r: Rect, count: Int, gap: Float, cells: Array<Rect>) {
        if (count <= 0) return
        val w = (r.width() - gap * (count - 1)) / count
        for (i in 0 until count) {
            val l = r.left + i * (w + gap)
            cells[i].set(l, r.top, l + w, r.bottom)
        }
    }

    /** The index of the cell of the first [count] [cells] containing ([x], [y]), or -1. */
    fun cellAt(cells: Array<Rect>, count: Int, x: Float, y: Float): Int {
        for (i in 0 until count) if (cells[i].contains(x, y)) return i
        return -1
    }

    /**
     * The index of the cell nearest [x] among the first [count] [cells] laid out by [cells], clamped to the ends: lets
     * a finger drag along a strip of choices without dropping out in the gaps.
     */
    fun cellNearest(cells: Array<Rect>, count: Int, x: Float): Int {
        if (count <= 0) return -1
        for (i in 0 until count) if (x < cells[i].right) return i
        return count - 1
    }

    /** One choice cell: a quiet bed, a tinted fill and an [accent] underline when [chosen]. */
    fun segment(ink: Ink, c: Surface, r: Rect, chosen: Boolean, accent: Int) {
        val radius = ink.dp(3f)
        c.fillRound(r.left, r.top, r.right, r.bottom, radius, if (chosen) Palette.mix(ink.palette.BG, accent, 0.28f) else ink.palette.LANE)
        if (chosen) c.fillRect(r.left + radius, r.bottom - ink.dp(3f), r.right - radius, r.bottom, accent)
    }
}

/**
 * One touch drag that edits the document, pinned to its [target] (which sound, which control) at touch-down so nothing
 * redirects it, and grouped into one undo step. Undo or redo during the drag close that group: [live] then ends the
 * drag instead of writing outside any group.
 */
internal class PanelDrag<T : Any> {
    var target: T? = null
        private set
    private var token: GestureToken? = null

    /** Starts a drag on [target] with undo label [label], ending any previous one first. */
    fun begin(session: EditorSession, label: String, target: T): T {
        finish(session)
        this.target = target
        token = session.beginGesture(label)
        return target
    }

    /** The target while the drag's undo group is still open, or null (and the drag is over). */
    fun live(session: EditorSession): T? {
        val t = token ?: return null
        if (!session.isGestureOpen(t)) {
            token = null
            target = null
            return null
        }
        return target
    }

    /** Ends the drag (closing its undo group) and returns its target, or null when none was active. */
    fun finish(session: EditorSession): T? {
        val t = target
        token?.let { session.endGesture(it) }
        token = null
        target = null
        return t
    }
}
