package org.thortracker.ui

import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.editor.channelCount
import org.thortracker.editor.patternAt
import org.thortracker.editor.Selection
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * Note entry keyboard. Two octaves plus the top C. The mode switch is the dominant element: in Listen
 * mode keys only play; in Write mode a key writes the note at the shared pattern cursor and advances
 * by the step. The whole keyboard is tinted with the mode colour so the two cannot be confused.
 */
internal class KeyboardPainter(v: PaneView) : Painter(v) {
    private val stripCells = Array(ViewController.STRIP_FIELDS.size) { Rect() to it }
    private val keyRects = Array(ViewController.KEYS) { Rect() }
    private val whiteKeys = keyRects.indices.filter { !isBlack(it) }.map { keyRects[it] to it }.toTypedArray()
    private val blackKeys = keyRects.indices.filter { isBlack(it) }.map { keyRects[it] to it }.toTypedArray()
    private val keys = Rect()
    private val cursor = Rect()
    private val weights = floatArrayOf(1.3f, 1.2f, 1.9f, 0.7f, 0.7f)
    private val totalWeight = weights.sum()
    private var touchKey = -1
    private var touchStrip = -1
    private var touchStripRect = Rect()
    private var keyGesture: GestureToken? = null

    // feedback: a struck key flashes, a released key keeps a short afterglow, a written note lights the header
    private var litKey = -1
    private var strikeStart = Motion.OFF
    private var releasedKey = -1
    private var releaseStart = Motion.OFF
    private var writeStart = Motion.OFF

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ch = st.channel.coerceIn(0, s.channelCount() - 1)
        val col = ink.palette.channel(ch)
        val mode = if (st.entryMode) ink.palette.WRITE else ink.palette.LISTEN
        val sel = session.selection
        val target = if (st.entryMode) "Writes to order ${hex2(sel.order)} row ${hex2(sel.row)} (pattern ${hex2(s.patternAt(sel.order))})" else "Plays only; nothing is written"
        val written = motion.fade(writeStart, 380)
        val top = header(c, area, if (st.entryMode) "Write" else "Listen", target, glow = if (written > 0f) Palette.alpha(ink.palette.WRITE, (written * 64).toInt()) else 0)
        ink.rect(c, area.left, top - ink.dp(3f), area.right, top, if (written > 0f) Palette.mix(mode, ink.palette.TEXT, 0.5f * written) else mode)

        // settings strip
        val stripH = ink.unit * 2.6f
        val pad = ink.unit * 0.6f
        val values = listOf(
            if (st.entryMode) "Write notes" else "Listen only",
            s.system.channels[ch].name,
            "${hex2(st.instrument)} ${s.system.instrumentName(s, s.system.channels[ch].bank, st.instrument) ?: ""}",
            "${st.octave}",
            "${session.step}",
        )
        var x = area.left + pad
        val avail = area.width() - pad * 2
        for (i in values.indices) {
            val w = avail * weights[i] / totalWeight
            val r = stripCells[i].first
            r.set(x + 2, top + pad, x + w - 2, top + pad + stripH)
            val selected = st.kbRow == 0 && st.kbField == i
            val bg = if (i == 0) Palette.alpha(mode, 60) else ink.palette.LANE
            ink.round(c, r, ink.dp(4f), bg)
            ink.label(c, ViewController.STRIP_FIELDS[i], r.left + ink.unit * 0.5f, r.top + stripH * 0.04f, stripH * 0.42f, ink.palette.DIM, size = ink.unit * 0.8f)
            val vc = if (i == 0) mode else if (i == 1) col else ink.palette.TEXT
            ink.label(c, ink.fit(values[i], r.width() - ink.unit * 2.6f), r.left + ink.unit * 0.5f, r.top + stripH * 0.36f, stripH * 0.64f, vc, true)
            if (i > 0) {
                ink.label(c, "‹", r.right - ink.unit * 1.7f, r.top, stripH, ink.palette.FAINT, true, ink.unit * 1.1f)
                ink.label(c, "›", r.right - ink.unit * 0.8f, r.top, stripH, ink.palette.FAINT, true, ink.unit * 1.1f)
            }
            if (selected) ink.outline(c, r, cursorColor(), ink.dp(2f), ink.dp(4f))
            x += w
        }

        // keys; on a sample channel each mapped key names its sample
        val mapped = s.system.mappedNotes(s, ch, st.instrument)
        val keysTop = top + pad * 2 + stripH
        keys.set(area.left + pad, keysTop, area.right - pad, area.bottom - pad)
        val ww = keys.width() / whiteKeys.size
        // A held on the settings strip changes a setting; only key presses and X sound a key.
        val sounding = if ((ctl.editHeld && st.kbRow == 1) || ctl.auditionHeld || touchKey >= 0) st.kbKey else -1
        if (sounding != litKey) {
            if (litKey >= 0) { releasedKey = litKey; releaseStart = motion.start() }
            if (sounding >= 0) strikeStart = motion.start()
            litKey = sounding
        }
        val strike = motion.fade(strikeStart, 200)
        val after = if (releasedKey >= 0) motion.fade(releaseStart, 260) else 0f
        var wi = 0
        for (k in 0 until ViewController.KEYS) {
            if (isBlack(k)) {
                val bx = keys.left + wi * ww
                keyRects[k].set(bx - ww * 0.32f, keys.top, bx + ww * 0.32f, keys.top + keys.height() * 0.6f)
            } else {
                val r = keyRects[k]
                r.set(keys.left + wi * ww + 1, keys.top, keys.left + (wi + 1) * ww - 1, keys.bottom)
                val playable = ctl.keyPlayable(k)
                val fill = when {
                    !playable -> ink.palette.LANE
                    k == sounding -> if (strike > 0f) Palette.mix(mode, ink.palette.TEXT, 0.55f * strike) else mode
                    k == releasedKey && after > 0f -> Palette.mix(Palette.mix(ink.palette.KEY_WHITE, mode, 0.08f), mode, 0.7f * after)
                    else -> Palette.mix(ink.palette.KEY_WHITE, mode, 0.08f)
                }
                ink.round(c, r, ink.dp(4f), fill)
                val labelColor = if (!playable) ink.palette.FAINT else if (k == sounding) ink.palette.BG else ink.palette.KEY_WHITE_TEXT
                val label = if (playable) song.system.noteName(ctl.keyNote(k)) else "—"
                ink.label(c, label, r.centerX(), r.bottom - ink.unit * 1.8f, ink.unit * 1.4f, labelColor, k % 12 == 0, ink.unit * 0.8f, Align.CENTER)
                if (playable && mapped != null) sampleLabel(c, r, r.bottom - ink.unit * 3.1f, ch, ctl.keyNote(k), labelColor)
                wi++
            }
        }
        for ((r, k) in blackKeys) {
            val fill = when {
                !ctl.keyPlayable(k) -> ink.palette.RULE
                k == sounding -> if (strike > 0f) Palette.mix(mode, ink.palette.TEXT, 0.55f * strike) else mode
                k == releasedKey && after > 0f -> Palette.mix(ink.palette.KEY_BLACK, mode, 0.7f * after)
                else -> ink.palette.KEY_BLACK
            }
            ink.round(c, r, ink.dp(3f), fill)
            if (mapped != null && ctl.keyPlayable(k)) sampleLabel(c, r, r.top + ink.unit * 0.4f, ch, ctl.keyNote(k), if (k == sounding) ink.palette.BG else ink.palette.KEY_BLACK_TEXT)
        }
        // controller key cursor
        if (st.kbRow == 1) {
            val r = keyRects.getOrNull(st.kbKey)
            if (r != null) {
                cursor.set(r.left + 3, r.top + 3, r.right - 3, r.bottom - 3)
                ink.outline(c, cursor, if (v.controllerFocused) cursorColor() else ink.palette.DIM, ink.dp(3f), ink.dp(4f))
            }
        }
    }

    private fun cursorColor() = when {
        ctl.editHeld -> if (st.entryMode) ink.palette.WRITE else ink.palette.LISTEN
        ctl.auditionHeld -> ink.palette.LISTEN
        v.controllerFocused -> ink.palette.FOCUS_ACCENT
        else -> ink.palette.DIM
    }

    /** A mapped key's sample name at [y] on the key (unmapped keys of a sample channel stay bare). */
    private fun sampleLabel(c: Surface, r: Rect, y: Float, ch: Int, note: Int, color: Int) {
        val sample = song.system.noteSample(song, ch, note, st.instrument) ?: return
        val size = ink.unit * 0.7f
        ink.label(c, ink.fit(sample.name, r.width() - ink.dp(4f), size), r.centerX(), y, ink.unit * 1.2f, color, size = size, align = Align.CENTER)
    }

    private fun isBlack(k: Int) = when (k % 12) { 1, 3, 6, 8, 10 -> true; else -> false }

    /** A note or cut this view wrote changes the cell at the write position as it stood before the input. */
    override fun edited(before: Song, selection: Selection) {
        if (!st.entryMode) return
        val now = song
        if (selection.order !in before.orders.indices || selection.order !in now.orders.indices) return
        val p = before.orders[selection.order]
        if (p != now.orders[selection.order]) return
        val was = before.patterns.getOrNull(p)?.tracks?.getOrNull(st.channel)?.getOrNull(selection.row) ?: return
        val isNow = now.patterns.getOrNull(p)?.tracks?.getOrNull(st.channel)?.getOrNull(selection.row) ?: return
        if (was != isNow) writeStart = motion.start()
    }

    // ---- touch ---------------------------------------------------------------------------------------

    override fun down(x: Float, y: Float): Boolean {
        stripCells.firstOrNull { it.first.contains(x, y) }?.let { (r, i) ->
            touchStrip = i
            touchStripRect = r
            return true
        }
        val k = keyAt(x, y)
        if (k >= 0) {
            touchKey = k
            // Only Write mode edits; Listen-mode touches never open (or close) an undo group.
            if (st.entryMode) keyGesture = session.beginGesture("Write note")
            ctl.keyDown(k)
            return true
        }
        return false
    }

    override fun move(x: Float, y: Float) {
        if (touchKey < 0) return
        // Undo or redo closed the written note's group; the glide only plays, so it carries on unowned.
        keyGesture?.let { if (!session.isGestureOpen(it)) keyGesture = null }
        val k = keyAt(x, y)
        if (k >= 0 && k != touchKey) {
            // Glide: hear the new key but only the first touched key is written.
            touchKey = k
            ctl.glideTo(k)
        }
    }

    override fun up(x: Float, y: Float) {
        if (touchStrip >= 0) {
            val i = touchStrip
            touchStrip = -1
            if (!touchStripRect.contains(x, y)) return
            val d = if (x > touchStripRect.centerX()) 1 else -1
            when (i) {
                0 -> st.entryMode = !st.entryMode
                1 -> ctl.setKeyboardChannel(st.channel + d)
                2 -> ctl.setKeyboardSound(st.instrument + d)
                3 -> st.octave = (st.octave + d).coerceIn(ctl.keyboardOctaves())
                4 -> session.step += d
            }
            return
        }
        endKey()
    }

    override fun cancel() {
        touchStrip = -1
        endKey()
    }

    private fun endKey() {
        if (touchKey < 0) return
        touchKey = -1
        ctl.touchKeyUp()
        keyGesture?.let { session.endGesture(it) }
        keyGesture = null
    }

    private fun keyAt(x: Float, y: Float): Int {
        blackKeys.firstOrNull { it.first.contains(x, y) }?.let { return it.second }
        whiteKeys.firstOrNull { it.first.contains(x, y) }?.let { return it.second }
        return -1
    }
}
