package org.thortracker.ui

import kotlin.math.roundToLong
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.model.Songs

/**
 * Song sheet: title, artist, tempo and loop settings above the order list. Each order row carries a
 * density strip of its pattern (one band per channel, lit where notes start) so the arrangement can be
 * read at a glance. Order commands sit in a bar below the list and are reached with left/right.
 */
internal class SongPainter(v: PaneView) : Painter(v) {
    private var listTop = 0f
    private var rowH = 0f
    private var first = 0
    private var listBottom = 0f
    private var settingsTop = 0f
    private val chips = ArrayList<Pair<Rect, Int>>()

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val problems = v.problems()
        val status = if (problems.isEmpty()) "Ready to export" else "${problems.size} problem${if (problems.size == 1) "" else "s"} block export (see Help, Song check)"
        val top = header(c, area, s.title.ifBlank { "Untitled" }, status)
        rowH = ink.unit * 1.45f
        val pad = ink.unit * 0.7f
        val labelW = ink.unit * 7.5f
        val cw = ink.monoWidth("0")

        // settings rows
        val settings = listOf(
            "Title" to s.title,
            "Artist" to s.artist,
            "Ticks per row" to "${s.ticksPerRow}   about ${bpm(s.ticksPerRow)} BPM at 4 rows per beat",
            "Loop to" to "Order ${hex2(s.loopOrder)}" + if (s.loopOrder in s.parkedOrders) {
                val played = org.thortracker.model.HugeRules.playedOrders(s)
                "   parked, so the song loops to order ${hex2(played.firstOrNull { it > s.loopOrder } ?: played.firstOrNull() ?: 0)}"
            } else "",
            "New patterns" to "${s.newPatternScale.label}   scale guide for New (Clone and Repeat keep their own)",
        )
        var y = top + ink.unit * 0.3f
        settingsTop = y
        for ((i, pair) in settings.withIndex()) {
            val selected = st.songItem == i
            if (selected) ink.rect(c, area.left, y, area.right, y + rowH, Palette.CURSOR_FILL)
            ink.label(c, pair.first, area.left + pad, y, rowH, Palette.DIM)
            val vx = area.left + pad + labelW
            ink.label(c, pair.second.ifEmpty { if (i <= 1) "(empty)" else "" }, vx, y, rowH, if (pair.second.isEmpty()) Palette.FAINT else Palette.TEXT, i == 0)
            if (selected && i <= 1) {
                // character cursor
                val before = pair.second.take(st.textPos).padEnd(st.textPos, ' ')
                val cx = vx + ink.labelWidth(before, ink.unit, i == 0)
                val ch = pair.second.getOrNull(st.textPos)?.toString() ?: " "
                val w = maxOf(ink.labelWidth(ch, ink.unit, i == 0), ink.unit * 0.5f)
                ink.outline(c, Rect(cx - 1, y + 2, cx + w + 1, y + rowH - 2), cursorColor(), ink.dp(2f), ink.dp(2f))
            } else if (selected) {
                ink.outline(c, Rect(vx - ink.unit * 0.3f, y + 2, area.right - pad, y + rowH - 2), cursorColor(), ink.dp(2f), ink.dp(3f))
            }
            y += rowH
        }
        y += ink.unit * 0.3f
        ink.line(c, area.left, y, area.right, y, Palette.RULE, ink.dp(1f))

        // command bar at bottom
        val barH = ink.unit * 2.1f
        val barTop = area.bottom - barH
        drawCommandBar(c, Rect(area.left, barTop, area.right, area.bottom))

        // order list
        listTop = y + ink.unit * 0.2f
        listBottom = barTop
        val visible = maxOf(1, ((listBottom - listTop) / rowH).toInt())
        val cur = st.songItem - ViewState.SONG_HEADER_ITEMS
        val playOrder = if (v.playback.playing) v.playback.order else -1
        val target = if (cur >= 0) cur else first
        if (!v.touchScrolled) {
            if (target < first + 1) first = target - 1
            if (target > first + visible - 2) first = target - visible + 2
        }
        first = first.coerceIn(0, maxOf(0, s.orders.size - visible))
        val stripLeft = area.left + pad + cw * 3.5f + ink.unit * 11f
        val stripRight = area.right - pad
        for (i in 0 until visible) {
            val o = first + i
            if (o >= s.orders.size) break
            val ry = listTop + i * rowH
            val pat = s.orders[o]
            val selected = o == cur
            if (selected) ink.rect(c, area.left, ry, area.right, ry + rowH, Palette.CURSOR_FILL)
            if (o == playOrder) {
                ink.rect(c, area.left, ry, area.right, ry + rowH, Palette.alpha(Palette.PLAYHEAD, 46))
                ink.rect(c, area.left, ry, area.left + ink.dp(4f), ry + rowH, Palette.PLAYHEAD)
            }
            val parked = o in s.parkedOrders
            ink.mono(c, hex2(o), area.left + pad, ry, rowH, if (selected) Palette.TEXT else Palette.FAINT)
            val px = area.left + pad + cw * 3.5f
            val patText = hex2(pat)
            ink.mono(c, patText, px, ry, rowH, if (parked) Palette.FAINT else Palette.TEXT, true)
            if (selected && st.songField == 0) {
                ink.outline(c, Rect(px - cw * 0.3f, ry + 2, px + cw * 2.3f, ry + rowH - 2), cursorColor(), ink.dp(2f), ink.dp(3f))
            }
            val loopMark = if (o == s.loopOrder) "↻ " else ""
            val rows = s.patterns[pat].length
            val name = (if (parked) "Parked · " else "") + s.patterns[pat].name + if (rows < Songs.ROWS) " · $rows" else ""
            ink.label(c, ink.fit(loopMark + name, ink.unit * 9.5f), px + cw * 3f, ry, rowH,
                when { parked -> Palette.FAINT; o == s.loopOrder -> Palette.PLAYHEAD; else -> Palette.DIM })
            if (stripRight - stripLeft > ink.unit * 4) {
                v.strip.draw(c, stripLeft, ry + ink.dp(3f), stripRight, ry + rowH - ink.dp(3f), s.patterns[pat], if (o == playOrder) v.playback.row else -1)
                // Parked orders keep their preview, veiled so the played arrangement reads at a glance.
                if (parked) ink.rect(c, stripLeft, ry + ink.dp(2f), stripRight, ry + rowH - ink.dp(2f), Palette.alpha(Palette.LANE, 170))
            }
        }
        if (s.orders.size > visible) {
            val trackH = listBottom - listTop
            val thumbH = maxOf(ink.unit, trackH * visible / s.orders.size)
            val thumbY = listTop + (trackH - thumbH) * first / maxOf(1, s.orders.size - visible)
            ink.rect(c, area.right - ink.dp(4f), thumbY, area.right - ink.dp(1f), thumbY + thumbH, Palette.FAINT)
        }
    }

    private fun cursorColor() = when {
        ctl.editHeld -> Palette.WRITE
        v.controllerFocused -> Palette.TEXT
        else -> Palette.DIM
    }

    private fun drawCommandBar(c: Surface, r: Rect) {
        chips.clear()
        ink.rect(c, r, Palette.LANE)
        ink.line(c, r.left, r.top, r.right, r.top, Palette.RULE, ink.dp(1f))
        val cur = st.songItem - ViewState.SONG_HEADER_ITEMS
        val size = ink.unit * 0.9f
        var x = r.left + ink.unit * 0.7f
        if (cur < 0) {
            ink.label(c, "Move down to the order list to arrange patterns", x, r.top, r.height(), Palette.FAINT, size = size)
            return
        }
        ink.label(c, "Order ${hex2(cur)}", x, r.top, r.height(), Palette.DIM, true, size)
        x += ink.labelWidth("Order 00", size, true) + ink.unit * 0.8f
        for (f in 1 until ViewController.ORDER_FIELDS.size) {
            val text = if (f == ViewController.PARK_FIELD && cur in song.parkedOrders) "Unpark" else ViewController.ORDER_FIELDS[f]
            val w = ink.labelWidth(text, size) + ink.unit * 1.1f
            if (x + w > r.right) break
            val chip = Rect(x, r.top + r.height() * 0.17f, x + w, r.bottom - r.height() * 0.17f)
            val sel = st.songField == f
            val danger = f == 4
            ink.round(c, chip, ink.dp(4f), if (sel) Palette.alpha(if (danger) Palette.WRITE else Palette.TEXT, 50) else Palette.RULE)
            if (sel) ink.outline(c, chip, if (danger) Palette.WRITE else cursorColor(), ink.dp(2f), ink.dp(4f))
            ink.label(c, text, chip.centerX(), r.top, r.height(), if (sel) Palette.TEXT else Palette.DIM, sel, size, Align.CENTER)
            chips += chip to f
            x += w + ink.unit * 0.4f
        }
    }

    /** GB vblank tick rate (59.73 Hz) with four rows per beat. */
    private fun bpm(ticks: Int): Int = (59.73 * 60.0 / (ticks * 4)).roundToLong().toInt()

    // ---- touch ---------------------------------------------------------------------------------------

    private var touchStartY = 0f
    private var touchStartFirst = 0
    private var dragging = false
    private var downItem = -1
    private var downChip = -1

    override fun down(x: Float, y: Float): Boolean {
        touchStartY = y
        touchStartFirst = first
        dragging = false
        downChip = chips.firstOrNull { it.first.contains(x, y) }?.second ?: -1
        downItem = when {
            downChip >= 0 -> -1
            y >= listTop && y < listBottom -> ViewState.SONG_HEADER_ITEMS + first + ((y - listTop) / rowH).toInt()
            y >= settingsTop && y < settingsTop + rowH * ViewState.SONG_HEADER_ITEMS -> ((y - settingsTop) / rowH).toInt()
            else -> -1
        }
        return downChip >= 0 || downItem >= 0
    }

    override fun move(x: Float, y: Float) {
        if (downItem < ViewState.SONG_HEADER_ITEMS) return
        val dy = y - touchStartY
        if (!dragging && kotlin.math.abs(dy) > rowH * 0.6f) dragging = true
        if (dragging) { v.touchScrolled = true; first = touchStartFirst - (dy / rowH).toInt() }
    }

    override fun up(x: Float, y: Float) {
        if (downChip >= 0) {
            st.songField = downChip
            ctl.press(org.thortracker.editor.Action.EDIT)
            ctl.release(org.thortracker.editor.Action.EDIT)
        } else if (!dragging && downItem >= 0) {
            val max = ViewState.SONG_HEADER_ITEMS + song.orders.size - 1
            if (downItem <= max) {
                st.songItem = downItem
                v.touchScrolled = false
                if (downItem >= ViewState.SONG_HEADER_ITEMS) {
                    st.songField = 0
                    ctl.press(org.thortracker.editor.Action.EDIT)
                    ctl.release(org.thortracker.editor.Action.EDIT)
                }
            }
        }
        dragging = false
    }

    override fun cancel() { dragging = false }
}
