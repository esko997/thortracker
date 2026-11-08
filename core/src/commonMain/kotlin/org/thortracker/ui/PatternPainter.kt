package org.thortracker.ui

import org.thortracker.model.NumberText
import org.thortracker.editor.Action
import org.thortracker.editor.ViewController
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.cellAt
import org.thortracker.editor.channelCount
import org.thortracker.editor.patternAt
import org.thortracker.editor.patternUses
import org.thortracker.editor.rowCount
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Songs
import org.thortracker.model.Scale
import org.thortracker.system.EffectSpec

/**
 * Pattern grid: hex row ruler, one lane per channel with note / sound / effect columns, sustain rails
 * showing how long each note keeps sounding, the cursor, block mark and amber playhead. When wide
 * enough an inspector explains the cell under the cursor and lists the orders.
 */
internal class PatternPainter(v: PaneView) : Painter(v) {
    private var gridTop = 0f
    private var gridLeft = 0f
    private var rowH = 0f
    private var laneW = 0f
    private var cw = 0f
    private var visibleRows = 0
    private var headerBottom = 0f
    private var laneTop = 0f
    private var inspector: Rect? = null
    private var orderListTop = 0f
    private var orderRowH = 0f
    private var orderScroll = 0
    private val headerChips = ArrayList<Pair<Rect, Int>>()

    // feedback: the cells the last edit changed, and the auditioned lane's lamp
    private var seenPattern: Pattern? = null
    private var seenPatternIndex = -1
    private var flashCh0 = 0
    private var flashCh1 = -1
    private var flashRow0 = 0
    private var flashRow1 = -1
    private var flashStart = Motion.OFF
    private var auditionLit = false
    private var auditionChannel = -1
    private var auditionEnd = Motion.OFF

    // touch
    private var touchStartY = 0f
    private var touchStartScroll = 0
    private var dragging = false
    private var touchTarget = 0

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val pattern = s.patternAt(st.order)
        val rows = s.rowCount(pattern)
        val channels = s.channelCount()
        val names = s.system.channels.map { it.name }
        val playing = v.playback.playing
        val playHere = playing && v.playback.order == st.order
        val samePatternPlaying = playing && !playHere && v.playback.order in s.orders.indices && s.orders[v.playback.order] == pattern

        // header
        val p = s.patterns[pattern]
        noticeEdit(pattern, p)
        val uses = s.patternUses(pattern)
        val detail = buildString {
            append("Pattern ${hex2(pattern)}  ${p.name}")
            if (uses > 1) append("  (plays in $uses orders)")
            if (v.playback.looping && v.playback.order == st.order) append("  · Looping")
            if (st.order in s.parkedOrders) append("  · Parked (skipped by the song)")
        }
        headerBottom = header(c, area, "Order ${hex2(st.order)} of ${hex2(s.orders.size - 1)}", detail, chipsWidth())
        drawHeaderChips(c, area)

        cw = ink.monoWidth("0")
        rowH = ink.unit * 1.2f // with the 12dp unit: about 24 rows on the Thor's top screen after the 1-row shell
        val rulerW = cw * 3.4f
        gridLeft = area.left + rulerW
        multi = ctl.multiColumn()
        laneCount = channels
        val wantInspector: Boolean
        val inspectorW: Float
        if (!multi) {
            val laneInner = cw * 10f
            val lanePad = cw * 1.2f
            val minLane = laneInner + lanePad
            wantInspector = area.width() - rulerW - channels * minLane > ink.unit * 15f
            laneW = if (wantInspector) minOf(cw * 15f, (area.width() - rulerW - ink.unit * 15f) / channels)
            else (area.width() - rulerW) / channels
            inspectorW = if (wantInspector) area.width() - rulerW - laneW * channels else 0f
            firstLane = 0
            lastLane = channels - 1
        } else {
            wantInspector = layoutLanes(area, rulerW, channels)
            inspectorW = if (wantInspector) area.right - laneRight(lastLane) else 0f
        }
        laneTop = headerBottom
        val laneHeaderH = ink.unit * 1.7f
        gridTop = laneTop + laneHeaderH
        visibleRows = maxOf(1, ((area.bottom - gridTop) / rowH).toInt())
        val gridRight = gridRight()

        // scroll: keep playhead (following) or cursor in view
        val target = if (playHere && st.followPlayback && !ctl.holding) v.playback.row else st.row
        if (!v.touchScrolled && !v.touching) {
            val margin = minOf(3, visibleRows / 4)
            if (target < st.scroll + margin) st.scroll = target - margin
            if (target > st.scroll + visibleRows - 1 - margin) st.scroll = target - visibleRows + 1 + margin
        }
        st.scroll = st.scroll.coerceIn(0, maxOf(0, rows - visibleRows))
        val first = st.scroll
        val last = minOf(rows - 1, first + visibleRows - 1)

        // lane headers; the tint lifts while the channel sounds (playback steps it down row by row)
        val auditioning = ctl.auditionHeld || (ctl.editHeld && st.field == 0)
        if (auditioning != auditionLit) {
            auditionLit = auditioning
            if (auditioning) auditionChannel = st.channel else auditionEnd = motion.start()
        }
        // The chip's shared state (SID: which voice owns the filter) where the song is heard: the playhead, else the cursor.
        val shared = if (playing) s.system.sharedState(s, v.playback.order, v.playback.row) else s.system.sharedState(s, st.order, st.row)
        for (ch in firstLane..lastLane) {
            val x = laneLeft(ch)
            val laneW = laneWidth(ch)
            val col = Palette.channel(ch)
            val heard = if (ch != auditionChannel) 0f else if (auditionLit) 1f else motion.fade(auditionEnd, 320)
            val lamp = maxOf(playLamp(ch), heard)
            ink.rect(c, x, laneTop, x + laneW, gridTop, Palette.mix(Palette.BG, col, 0.10f + 0.12f * lamp))
            ink.rect(c, x, laneTop, x + laneW, laneTop + ink.dp(3f), if (lamp > 0f) Palette.mix(col, Palette.TEXT, 0.6f * lamp) else col)
            val pen = ctl.pen(s.system.channels[ch].bank)
            val penName = s.system.instrumentName(s, s.system.channels[ch].bank, pen) ?: ""
            ink.label(c, names[ch], x + cw * 0.6f, laneTop, laneHeaderH, col, true)
            val nw = ink.labelWidth(names[ch], ink.unit, true)
            // Hidden effect columns that hold effects (they still play): "+n" in the lane header.
            val hidden = if (multi) ctl.usedColumns(ch) - ctl.shownColumns(ch) else 0
            val tag = shared?.tags?.getOrNull(ch) ?: if (hidden > 0) "+$hidden fx" else null
            val tagW = if (tag == null) 0f else ink.labelWidth(tag, ink.unit * 0.7f, true) + cw * 1.2f
            if (tag != null) {
                val tr = Rect(x + laneW - tagW, laneTop + laneHeaderH * 0.2f, x + laneW - cw * 0.4f, laneTop + laneHeaderH * 0.8f)
                ink.round(c, tr, ink.dp(3f), Palette.alpha(Palette.PLAYHEAD, 60))
                ink.label(c, tag, tr.centerX(), tr.top, tr.height(), Palette.PLAYHEAD, true, ink.unit * 0.7f, Align.CENTER)
            }
            ink.label(c, ink.fit("${hex2(pen)} $penName", laneW - nw - cw * 2f - tagW, ink.unit * 0.8f), x + cw * 1.2f + nw, laneTop, laneHeaderH, Palette.DIM, size = ink.unit * 0.8f)
        }
        if (multi) drawLaneScroll(c, area, laneHeaderH)

        // rows background + ruler
        for (r in first..last) {
            val y = gridTop + (r - first) * rowH
            val bg = when {
                r % 16 == 0 -> Palette.BAR
                r % 4 == 0 -> Palette.BEAT
                else -> Palette.BG
            }
            ink.rect(c, area.left, y, gridRight, y + rowH, bg)
            val rc = if (r % 4 == 0) Palette.TEXT else Palette.FAINT
            ink.mono(c, hex2(r), area.left + cw * 0.5f, y, rowH, rc, r % 16 == 0)
        }
        for (ch in firstLane..lastLane) {
            val x = laneLeft(ch)
            ink.line(c, x, laneTop, x, gridTop + (last - first + 1) * rowH, Palette.RULE, ink.dp(1f))
        }

        // playhead band
        if (playHere && v.playback.row in first..last) {
            val y = gridTop + (v.playback.row - first) * rowH
            ink.rect(c, area.left, y, gridRight, y + rowH, Palette.alpha(Palette.PLAYHEAD, 54))
            ink.rect(c, area.left, y, area.left + ink.dp(4f), y + rowH, Palette.PLAYHEAD)
        } else if (samePatternPlaying && v.playback.row in first..last) {
            val y = gridTop + (v.playback.row - first) * rowH
            ink.outline(c, Rect(area.left + 1, y + 1, gridRight - 1, y + rowH - 1), Palette.alpha(Palette.PLAYHEAD, 120), ink.dp(1f))
        }

        // mark
        if (st.mark) {
            val b = ctl.block()
            val r0 = maxOf(b.firstRow, first)
            val r1 = minOf(b.lastRow, last)
            val c0 = maxOf(b.firstChannel, firstLane)
            val c1 = minOf(b.lastChannel, lastLane)
            if (r0 <= r1 && c0 <= c1) {
                val rect = Rect(laneLeft(c0), gridTop + (r0 - first) * rowH, laneRight(c1), gridTop + (r1 - first + 1) * rowH)
                ink.rect(c, rect, Palette.alpha(Palette.LISTEN, 34))
                ink.outline(c, rect, Palette.alpha(Palette.LISTEN, 160), ink.dp(1.5f))
            }
        }

        // cursor row band
        if (st.row in first..last) {
            val y = gridTop + (st.row - first) * rowH
            ink.rect(c, gridLeft, y, gridRight, y + rowH, Palette.alpha(Palette.TEXT, 14))
        }

        // edit afterglow: the cells the last change touched, behind their text
        val glow = motion.fade(flashStart, 420)
        if (glow > 0f) {
            val r0 = maxOf(flashRow0, first)
            val r1 = minOf(flashRow1, last)
            if (r0 <= r1) for (ch in maxOf(flashCh0, firstLane)..minOf(flashCh1, lastLane)) {
                val x = laneLeft(ch)
                ink.rect(c, x + 1, gridTop + (r0 - first) * rowH, x + laneWidth(ch), gridTop + (r1 - first + 1) * rowH, Palette.alpha(Palette.channel(ch), (glow * 92).toInt()))
            }
        }

        // cells + sustain rails; the cursor cell may show an explicitly chosen arpeggio 000
        val zeroHere = ctl.chosenArpeggio()
        val zeroColumn = ctl.cursorColumn()
        for (ch in firstLane..lastLane) {
            val x = laneLeft(ch) + cw * 0.7f
            val col = Palette.channel(ch)
            val track = p.tracks[ch]
            // Rows still sounding after the current one: a note sounds until the next note or OFF, unless the system
            // says it ends by itself (a one-shot sample).
            var left = 0
            for (r in 0 until first) left = nextSounding(left, track[r], pattern, ch, r)
            for (r in first..last) {
                val cell = track[r]
                val y = gridTop + (r - first) * rowH
                val wasSounding = left > 0
                left = nextSounding(left, cell, pattern, ch, r)
                val (top, bottom) = when {
                    cell.note >= 0 -> y + rowH * 0.5f to y + rowH
                    cell.note == Cell.CUT && wasSounding -> y to y + rowH * 0.5f
                    cell.note == Cell.EMPTY && wasSounding -> y to y + rowH
                    else -> 0f to 0f
                }
                if (bottom > top) ink.rect(c, x - cw * 0.45f, top, x - cw * 0.45f + ink.dp(2f), bottom, Palette.alpha(col, 120))
                if (cell.note >= 0) session.fittedFrom(pattern, ch, r)?.let { from -> drawFitMark(c, ch, r - first, pitchOrder(cell) > pitchOrder(from)) }
                drawCell(c, cell, x, y, col, ch, if (zeroHere && ch == st.channel && r == st.row) zeroColumn else -1)
            }
        }

        // cursor
        if (st.row in first..last && st.channel in firstLane..lastLane) {
            val fr = fieldRect(st.channel, st.field, st.row - first)
            val outline = when {
                ctl.editHeld -> Palette.WRITE
                ctl.auditionHeld -> Palette.LISTEN
                v.controllerFocused -> Palette.TEXT
                else -> Palette.DIM
            }
            ink.round(c, fr, ink.dp(3f), Palette.alpha(outline, 46))
            ink.outline(c, fr, outline, if (v.controllerFocused) ink.dp(2f) else ink.dp(1f), ink.dp(3f))
            val cell = p.tracks[st.channel][st.row]
            drawCell(c, cell, laneLeft(st.channel) + cw * 0.7f, gridTop + (st.row - first) * rowH, Palette.channel(st.channel), st.channel, if (zeroHere) zeroColumn else -1)
        }

        // inspector
        inspector = if (wantInspector) Rect(area.right - inspectorW, laneTop, area.right, area.bottom).also { drawInspector(c, it) } else null

        // root/scale panel over the grid
        scalePanel = if (st.scalePanel) drawScalePanel(c, area) else null
    }

    // ---- lanes ---------------------------------------------------------------------------------------

    /** Several effect columns (the OPL3): lanes differ in width and scroll sideways to keep the cursor's in view. */
    private var multi = false
    private var laneCount = 0
    private val laneXs = FloatArray(MAX_LANES)
    private val laneWs = FloatArray(MAX_LANES)
    private var firstLane = 0
    private var lastLane = 0
    /** First lane shown while the lanes do not all fit (several-column systems only). */
    private var laneScroll = 0
    private val scrollLeft = Rect()
    private val scrollRight = Rect()

    private fun laneLeft(ch: Int): Float = if (multi) laneXs[ch] else gridLeft + ch * laneW

    private fun laneWidth(ch: Int): Float = if (multi) laneWs[ch] else laneW

    private fun laneRight(ch: Int): Float = if (multi) laneXs[ch] + laneWs[ch] else gridLeft + (ch + 1) * laneW

    private fun gridRight(): Float = if (multi) laneRight(lastLane) else gridLeft + laneW * laneCount

    /**
     * Lays out lanes of `note sound` plus 4 characters per shown effect column. The text keeps its size on every
     * screen (9 channels of 2-4 columns cannot shrink to fit a 1240-pixel display legibly): when the lanes do not all
     * fit, they scroll by whole lanes to keep the cursor's channel in view, as FamiTracker and Furnace do, and narrowing
     * a channel to fewer columns (Pattern panel → Columns) shows more of them. Returns whether the inspector shows: when
     * it fits beside every lane, or on wide screens (60 text units: the Thor's top display, the Deck) beside scrolled lanes.
     */
    private fun layoutLanes(area: Rect, rulerW: Float, channels: Int): Boolean {
        var total = 0f
        for (ch in 0 until channels) {
            laneWs[ch] = cw * (6f + 4f * ctl.shownColumns(ch) + 1.2f)
            total += laneWs[ch]
        }
        val room = area.width() - rulerW
        val inspectorMin = ink.unit * 15f
        val inspector: Boolean
        if (total <= room) {
            inspector = total + inspectorMin <= room
            // Without the inspector, spare width widens every lane as the one-column layout does.
            if (!inspector) { val extra = (room - total) / channels; for (ch in 0 until channels) laneWs[ch] += extra }
            laneScroll = 0
            firstLane = 0
            lastLane = channels - 1
        } else {
            inspector = area.width() >= ink.unit * 60f
            val avail = room - (if (inspector) inspectorMin else 0f)
            fun span(from: Int, to: Int): Float { var w = 0f; for (ch in from..to) w += laneWs[ch]; return w }
            laneScroll = laneScroll.coerceIn(0, channels - 1)
            val cursor = st.channel.coerceIn(0, channels - 1)
            if (cursor < laneScroll) laneScroll = cursor
            while (laneScroll < cursor && span(laneScroll, cursor) > avail) laneScroll++
            while (laneScroll > 0 && span(laneScroll - 1, channels - 1) <= avail) laneScroll--
            firstLane = laneScroll
            lastLane = firstLane
            while (lastLane + 1 < channels && span(firstLane, lastLane + 1) <= avail) lastLane++
        }
        var x = gridLeft
        for (ch in 0 until channels) {
            laneXs[ch] = x
            if (ch >= firstLane) x += laneWs[ch]
        }
        return inspector
    }

    /** "◀ 2" / "3 ▶" beside the lane headers when lanes are scrolled out of view (tap to move there). */
    private fun drawLaneScroll(c: Surface, area: Rect, h: Float) {
        scrollLeft.set(0f, 0f, 0f, 0f)
        scrollRight.set(0f, 0f, 0f, 0f)
        val size = ink.unit * 0.75f
        if (firstLane > 0) {
            scrollLeft.set(area.left, laneTop, gridLeft, laneTop + h)
            ink.rect(c, scrollLeft, Palette.alpha(Palette.PLAYHEAD, 40))
            ink.label(c, "◀$firstLane", scrollLeft.centerX(), laneTop, h, Palette.PLAYHEAD, true, size, Align.CENTER)
        }
        val more = laneCount - 1 - lastLane
        if (more > 0) {
            val right = laneRight(lastLane)
            val w = ink.labelWidth("$more▶", size, true) + cw
            scrollRight.set(right - w, laneTop + h * 0.1f, right - cw * 0.2f, laneTop + h * 0.9f)
            ink.round(c, scrollRight, ink.dp(3f), Palette.alpha(Palette.PLAYHEAD, 90))
            ink.label(c, "$more▶", scrollRight.centerX(), scrollRight.top, scrollRight.height(), Palette.TEXT, true, size, Align.CENTER)
        }
    }

    /** Rows still sounding after row [r] holding [cell], given [left] after the row before. */
    private fun nextSounding(left: Int, cell: Cell, pattern: Int, ch: Int, r: Int): Int = when {
        cell.note >= 0 -> song.system.soundingRows(song, pattern, ch, r)?.let { it - 1 } ?: Int.MAX_VALUE
        cell.note == Cell.CUT -> 0
        left > 0 -> left - 1
        else -> 0
    }

    /** Notices an edit of the shown pattern (here, on the other display, or undo) and lights what changed. */
    private fun noticeEdit(index: Int, p: Pattern) {
        val prev = seenPattern
        val sameSubject = index == seenPatternIndex
        seenPattern = p
        seenPatternIndex = index
        if (prev == null || prev === p || !sameSubject || prev.tracks.size != p.tracks.size) return
        // A freshly loaded document has no history: nothing was edited, so nothing lights.
        if (!session.canUndo && !session.canRedo) return
        var c0 = Int.MAX_VALUE
        var c1 = -1
        var r0 = Int.MAX_VALUE
        var r1 = -1
        for (ch in p.tracks.indices) {
            val a = prev.tracks[ch]
            val b = p.tracks[ch]
            if (a === b) continue
            if (a.size != b.size) return
            for (r in b.indices) {
                if (a[r] == b[r]) continue
                if (ch < c0) c0 = ch
                if (ch > c1) c1 = ch
                if (r < r0) r0 = r
                if (r > r1) r1 = r
            }
        }
        if (c1 < 0) return
        flashCh0 = c0; flashCh1 = c1; flashRow0 = r0; flashRow1 = r1
        flashStart = motion.start()
    }

    /** How brightly [ch] is sounding at the playhead: full on a new note, fading over [LAMP_ROWS] rows. */
    private fun playLamp(ch: Int): Float {
        val pb = v.playback
        if (!pb.playing) return 0f
        val s = song
        if (pb.order !in s.orders.indices) return 0f
        val track = s.patterns[s.orders[pb.order]].tracks.getOrNull(ch) ?: return 0f
        if (pb.row !in track.indices) return 0f
        for (k in 0 until LAMP_ROWS) {
            val r = pb.row - k
            if (r < 0) return 0f
            val n = track[r].note
            if (n >= 0) return 1f - k.toFloat() / LAMP_ROWS
            if (n == Cell.CUT) return 0f
        }
        return 0f
    }

    /** A cursor field's box: note, sound, then per effect column its type (1 character) and value (2), 4 characters apart. */
    private fun fieldRect(ch: Int, field: Int, visRow: Int): Rect {
        val x = laneLeft(ch) + cw * 0.7f
        val y = gridTop + visRow * rowH
        val column = ViewController.columnOf(field)
        val (start, len) = when {
            field == 0 -> 0f to 3f
            field == 1 -> 4f to 2f
            !ViewController.isValueField(field) -> 7f + 4 * column to 1f
            else -> 8f + 4 * column to 2f
        }
        return Rect(x + start * cw - cw * 0.2f, y + 1, x + (start + len) * cw + cw * 0.2f, y + rowH - 1)
    }

    /** A note a Fit moved: an amber field and an up/down tick at its right edge, until the cells are edited. */
    private fun drawFitMark(c: Surface, ch: Int, visRow: Int, up: Boolean) {
        val f = fieldRect(ch, 0, visRow)
        ink.round(c, f, ink.dp(3f), Palette.alpha(Palette.PLAYHEAD, 64))
        val size = rowH * 0.62f
        ink.label(c, if (up) "▴" else "▾", f.right + cw * 0.05f, f.top, f.height(), Palette.PLAYHEAD, true, size)
    }

    /** Orders cells by sounding pitch: note, then its fine pitch (another effect plays the plain note). */
    private fun pitchOrder(cell: Cell): Int {
        val fine = song.system.fine
        return cell.note * (fine.max + 1) + (fine.of(cell) ?: 0)
    }

    /** " · fitted from A-5" (with "+3" for a bent original), or "" when the cell carries no Fit mark. */
    private fun fittedFromText(pattern: Int, ch: Int, row: Int): String {
        val from = session.fittedFrom(pattern, ch, row)?.takeIf { it.note >= 0 } ?: return ""
        val bend = song.system.fine.of(from)?.takeIf { it > 0 }?.let { "+$it" } ?: ""
        return " · fitted from ${song.system.noteName(from.note)}$bend"
    }

    /** [zero]: the effect column whose 000 shows as the explicitly chosen effect 0 (arpeggio) rather than as no effect; -1 none. */
    private fun drawCell(c: Surface, cell: Cell, x: Float, y: Float, col: Int, ch: Int, zero: Int = -1) {
        val noteText = when {
            cell.note >= 0 -> song.system.noteName(cell.note)
            cell.note == Cell.CUT -> "OFF"
            cell.note == song.system.keyOn -> song.system.noteName(cell.note)
            else -> "···"
        }
        val noteColor = when {
            cell.note >= 0 -> col
            cell.note == Cell.CUT || cell.note == song.system.keyOn -> Palette.WRITE
            else -> Palette.FAINT
        }
        ink.mono(c, noteText, x, y, rowH, noteColor, cell.note >= 0)
        ink.mono(c, if (cell.instrument > 0) hex2(cell.instrument) else "··", x + cw * 4, y, rowH, if (cell.instrument > 0) Palette.TEXT else Palette.FAINT)
        val columns = if (multi) ctl.shownColumns(ch) else 1
        for (k in 0 until columns) {
            val code = cell.effectCode(k)
            val param = cell.effectParam(k)
            val hasFx = code != 0 || param != 0
            val shown = hasFx || zero == k
            val fx = x + cw * (7 + 4 * k)
            ink.mono(c, if (shown) EffectSpec.textOf(code) else "·", fx, y, rowH, if (shown) Palette.PLAYHEAD else Palette.FAINT, hasFx)
            ink.mono(c, if (shown) hex2(param) else "··", x + cw * (8 + 4 * k), y, rowH, if (shown) Palette.DIM else Palette.FAINT)
        }
        // Effects in hidden columns still play: a dot after the last shown column says so.
        if (multi && cell.lastEffectColumn >= columns) {
            ink.rect(c, x + cw * (6 + 4 * columns) - ink.dp(2f), y + rowH * 0.4f, x + cw * (6 + 4 * columns) + ink.dp(1f), y + rowH * 0.6f, Palette.PLAYHEAD)
        }
        if ((cell.note != Cell.EMPTY || cell.instrument != 0 || cell.hasAnyEffect) && hasProblem(ch, cell)) {
            ink.rect(c, x - cw * 0.2f, y + rowH - ink.dp(3f), x + cw * (if (columns == 1) 10.2f else 6.2f + 4 * columns), y + rowH - ink.dp(1f), Palette.ERROR)
        }
    }

    /** Cell problems depend only on the song and channel; cached until the document changes. */
    private var problemSong: org.thortracker.model.Song? = null
    private var problemCache: Array<HashMap<Cell, Boolean>> = emptyArray()

    private fun hasProblem(ch: Int, cell: Cell): Boolean {
        val s = song
        if (s !== problemSong || problemCache.size != s.channelCount()) {
            problemSong = s
            problemCache = Array(s.channelCount()) { HashMap() }
        }
        return problemCache[ch].getOrPut(cell) { s.system.cellProblem(s, ch, cell) != null }
    }

    /** Rightmost first: the pattern scale, then follow playback, entry step, octave. */
    private fun chips(): List<Pair<String, Int>> = listOf(
        (song.tuning?.let { "Tuning ${if (it.name.length > 18) it.name.take(17) + "…" else it.name}" } ?: "Scale ${ctl.patternScale.label}") +
            (if (ctl.patternLength < Songs.ROWS) " · ${ctl.patternLength} rows" else "") to 3,
        (if (st.followPlayback) "Follows playback" else "Stays put during playback") to 0,
        "Step ${session.step}" to 1,
        "Octave ${st.octave}" to 2,
    )

    private fun chipsWidth(): Float =
        chips().sumOf { (ink.labelWidth(it.first, ink.unit * 0.8f) + ink.unit * 1.4f).toDouble() }.toFloat()

    /** Touchable header toggles: scale panel, follow playback, entry step, octave. */
    private fun drawHeaderChips(c: Surface, area: Rect) {
        headerChips.clear()
        val h = ink.unit * 2f
        val size = ink.unit * 0.8f
        var x = area.right - ink.unit * 0.6f
        for ((text, id) in chips()) {
            val w = ink.labelWidth(text, size) + ink.unit
            val r = Rect(x - w, area.top + h * 0.18f, x, area.top + h * 0.82f)
            val on = (id == 0 && st.followPlayback) || (id == 3 && st.scalePanel)
            ink.round(c, r, ink.dp(4f), if (on) Palette.alpha(Palette.PLAYHEAD, 60) else Palette.RULE)
            ink.label(c, text, r.centerX(), area.top, h, if (on) Palette.PLAYHEAD else Palette.DIM, size = size, align = Align.CENTER)
            headerChips += r to id
            x -= w + ink.unit * 0.4f
        }
    }

    /** The inspector's sound summary and waveform, kept until the song or the shown sound changes. */
    private var summarySong: org.thortracker.model.Song? = null
    private var summaryKey = -1
    private var summaryText: String? = null
    private var summaryShape: List<Float>? = null

    private fun drawInspector(c: Surface, r: Rect) {
        val s = song
        ink.rect(c, r, Palette.LANE)
        ink.line(c, r.left, r.top, r.left, r.bottom, Palette.RULE, ink.dp(1f))
        val pad = ink.unit * 0.7f
        val x = r.left + pad
        val w = r.width() - pad * 2
        var y = r.top + ink.unit * 0.4f
        val line = ink.unit * 1.35f
        val ch = st.channel
        val col = Palette.channel(ch)
        val cell = s.cellAt(s.patternAt(st.order), ch, st.row)
        val system = s.system
        val bank = system.channels[ch].bank

        ink.label(c, "${system.channels[ch].name}  row ${hex2(st.row)}", x, y, line, col, true)
        y += line
        val tuning = ctl.tuningFor(ch)
        val noteLine = when {
            cell.note >= 0 && tuning != null -> {
                // A row holding another effect plays the plain note (bend 0): in tune when that is a degree.
                val detune = system.fine.of(cell)
                val i = tuning.index(cell.note, detune ?: 0)
                when {
                    i < 0 && detune == null -> "Note ${song.system.noteName(cell.note)}: untuned, effect slot taken"
                    i < 0 -> "Note ${song.system.noteName(cell.note)}: outside ${tuning.tuning.name}"
                    else -> tuning.pitches[i].let { p -> "Note ${song.system.noteName(cell.note)} · ${tuning.label(p)} ${NumberText.fixed(p.error, 1, plus = true)}¢" } +
                        fittedFromText(s.patternAt(st.order), ch, st.row)
                }
            }
            cell.note >= 0 && system.mappedNotes(s, ch, if (cell.instrument > 0) cell.instrument else ctl.pen(bank)) != null -> {
                val slot = if (cell.instrument > 0) cell.instrument else ctl.pen(bank)
                "Note ${system.noteName(cell.note)} · " + (system.noteSample(s, ch, cell.note, slot)?.let { "${it.name} (${it.detail})" }
                    ?: "no sample mapped in sound ${hex2(slot)}")
            }
            cell.note >= 0 -> "Note ${song.system.noteName(cell.note)}" + fittedFromText(s.patternAt(st.order), ch, st.row)
            cell.note == Cell.CUT -> system.help.offInspector
            cell.note == system.keyOn -> "${system.noteName(cell.note)}: key on, the gate opens again (no new attack)"
            else -> "No note: the previous note keeps sounding"
        }
        ink.label(c, ink.fit(noteLine, w), x, y, line, Palette.TEXT)
        y += line
        val shownSlot = if (cell.instrument > 0) cell.instrument else if (cell.note >= 0) 0 else ctl.pen(bank)
        val soundLine = when {
            cell.instrument > 0 -> "Sound ${hex2(cell.instrument)} ${system.instrumentName(s, bank, cell.instrument) ?: ""}"
            cell.note >= 0 -> "No sound number: legato, changes pitch without restarting the sound"
            else -> "Pen sound ${hex2(ctl.pen(bank))} ${system.instrumentName(s, bank, ctl.pen(bank)) ?: ""}"
        }
        ink.label(c, ink.fit(soundLine, w), x, y, line, Palette.DIM)
        y += line
        val key = bank * 256 + shownSlot
        if (s !== summarySong || key != summaryKey) {
            summarySong = s
            summaryKey = key
            summaryText = system.instrumentSummary(s, bank, shownSlot)
            summaryShape = system.instrumentShape(s, bank, shownSlot)
        }
        summaryText?.let { summary ->
            // What the sound number means, so its settings are visible without switching view.
            val small = ink.unit * 0.85f
            val shape = summaryShape
            if (shape != null) {
                val pw = ink.unit * 4.5f
                val bw = pw / shape.size
                val top = y + line * 0.2f
                val h = line * 0.6f
                for ((i, level) in shape.withIndex()) {
                    ink.rect(c, x + i * bw, top + h * (1 - level), x + (i + 1) * bw, top + h, col)
                }
                ink.label(c, ink.fit(summary, w - pw - ink.unit * 0.5f, small), x + pw + ink.unit * 0.5f, y, line, Palette.DIM, size = small)
            } else {
                ink.label(c, ink.fit(summary, w, small), x, y, line, Palette.DIM, size = small)
            }
            y += line
        }
        val cursorEmpty = ctl.cursorColumn().let { cell.effectCode(it) == 0 && cell.effectParam(it) == 0 }
        if (multi && cell.hasAnyEffect) {
            // Every column holding an effect (hidden ones too); the cursor's column (else the first) with its full reading.
            val focus = if (st.field >= 2 && !cursorEmpty) ctl.cursorColumn()
                else (0..cell.lastEffectColumn).first { cell.effectCode(it) != 0 || cell.effectParam(it) != 0 }
            for (k in 0..cell.lastEffectColumn) {
                val code = cell.effectCode(k)
                val param = cell.effectParam(k)
                if (code == 0 && param == 0) continue
                val info = system.effects.firstOrNull { it.code == code }
                val on = k == focus
                val hidden = if (k >= ctl.shownColumns(ch)) " (hidden column)" else ""
                ink.label(c, ink.fit("${k + 1}: ${EffectSpec.charOf(code)}${hex2(param)} ${info?.name ?: ""}$hidden", w), x, y, line,
                    if (on) Palette.PLAYHEAD else Palette.DIM, on)
                y += line
                if (!on) continue
                for (part in wrap(ctl.effectReading(ch, st.row, k, code, param), w, ink.unit * 0.85f)) {
                    ink.label(c, part, x, y, line * 0.85f, Palette.DIM, size = ink.unit * 0.85f)
                    y += line * 0.85f
                }
            }
        }
        if (multi && cell.hasAnyEffect && !(st.field >= 2 && cursorEmpty)) Unit
        else if (!multi && cell.hasEffect) {
            val info = system.effects.firstOrNull { it.code == cell.effect }
            ink.label(c, ink.fit("${EffectSpec.charOf(cell.effect)}${hex2(cell.param)} ${info?.name ?: ""}", w), x, y, line, Palette.PLAYHEAD, true)
            y += line
            for (part in wrap(system.describeEffect(s, ch, cell.effect, cell.param), w, ink.unit * 0.85f)) {
                ink.label(c, part, x, y, line * 0.85f, Palette.DIM, size = ink.unit * 0.85f)
                y += line * 0.85f
            }
        } else if (ctl.chosenArpeggio()) {
            ink.label(c, ink.fit("000 Arpeggio, no value yet", w), x, y, line, Palette.PLAYHEAD, true)
            y += line
            val text = "Set the value xy: the note alternates with note+x and note+y semitones (047 = major chord). " +
                "000 itself plays no effect."
            for (part in wrap(text, w, ink.unit * 0.85f)) {
                ink.label(c, part, x, y, line * 0.85f, Palette.DIM, size = ink.unit * 0.85f)
                y += line * 0.85f
            }
        } else if (st.field >= 2) {
            val info = system.effectsFor(ch)
            ink.label(c, "Effects (hold ${v.keymap.label(Action.EDIT)} to pick)", x, y, line, Palette.DIM)
            y += line
            ink.label(c, "· No effect", x, y, line * 0.8f, Palette.FAINT, size = ink.unit * 0.8f)
            y += line * 0.8f
            for (e in info) {
                if (y > r.top + r.height() * 0.6f) break
                ink.label(c, ink.fit("${e.char} ${e.name}  ${e.param}", w), x, y, line * 0.8f, Palette.FAINT, size = ink.unit * 0.8f)
                y += line * 0.8f
            }
        }
        s.system.cellProblem(s, ch, cell)?.let { problem ->
            for (part in wrap(problem, w, ink.unit * 0.85f)) {
                ink.label(c, part, x, y, line * 0.85f, Palette.ERROR, size = ink.unit * 0.85f)
                y += line * 0.85f
            }
        }
        val playing = v.playback.playing
        val shared = if (playing) system.sharedState(s, v.playback.order, v.playback.row) else system.sharedState(s, st.order, st.row)
        shared?.lines?.forEach { text ->
            for (part in wrap(text, w, ink.unit * 0.85f)) {
                ink.label(c, part, x, y, line * 0.85f, Palette.PLAYHEAD, size = ink.unit * 0.85f)
                y += line * 0.85f
            }
        }
        val clip = session.clipboard
        if (clip is org.thortracker.editor.Clip.Cells) {
            ink.label(c, "Clipboard ${clip.width}×${clip.height} cells", x, y, line, Palette.FAINT, size = ink.unit * 0.8f)
            y += line
        }

        // order list
        y += ink.unit * 0.4f
        ink.line(c, r.left, y, r.right, y, Palette.RULE, ink.dp(1f))
        orderListTop = y + ink.unit * 0.3f
        orderRowH = ink.unit * 1.3f
        val n = maxOf(1, ((r.bottom - orderListTop) / orderRowH).toInt())
        if (st.order < orderScroll) orderScroll = st.order
        if (st.order >= orderScroll + n) orderScroll = st.order - n + 1
        orderScroll = orderScroll.coerceIn(0, maxOf(0, s.orders.size - n))
        val small = ink.unit * 0.85f
        val nameW = minOf(ink.unit * 9f, w - cw * 3f)
        val stripLeft = x + cw * 2.8f + nameW + ink.unit * 0.5f
        val stripRight = r.right - pad
        for (i in 0 until n) {
            val o = orderScroll + i
            if (o >= s.orders.size) break
            val oy = orderListTop + i * orderRowH
            val current = o == st.order
            val playingHere = v.playback.playing && v.playback.order == o
            if (current) ink.rect(c, r.left, oy, r.right, oy + orderRowH, Palette.CURSOR_FILL)
            if (playingHere) ink.rect(c, r.left, oy, r.left + ink.dp(4f), oy + orderRowH, Palette.PLAYHEAD)
            val pat = s.orders[o]
            val loop = if (o == s.loopOrder) " ↻" else ""
            val parked = o in s.parkedOrders
            ink.mono(c, hex2(o), x, oy, orderRowH, if (current) Palette.TEXT else Palette.FAINT, size = small)
            ink.label(c, ink.fit("${if (parked) "‖ " else ""}${hex2(pat)} ${s.patterns[pat].name}$loop", nameW, small), x + cw * 2.8f, oy, orderRowH,
                if (current) Palette.TEXT else if (parked) Palette.FAINT else Palette.DIM, size = small)
            if (stripRight - stripLeft > ink.unit * 4) {
                v.strip.draw(c, stripLeft, oy + ink.dp(3f), stripRight, oy + orderRowH - ink.dp(3f), s.patterns[pat], if (playingHere) v.playback.row else -1)
            }
        }
    }

    private fun wrap(text: String, width: Float, size: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (ink.labelWidth(next, size) > width && line.isNotEmpty()) { out += line; line = word } else line = next
        }
        if (line.isNotEmpty()) out += line
        return out.take(4)
    }

    // ---- root / scale panel ------------------------------------------------------------------------

    private var scalePanel: Rect? = null
    private val rootKeys = Array(12) { Rect() }
    private val rootRow = Rect()
    private val scaleRow = Rect()
    private val lengthRow = Rect()
    private val lengthPrev = Rect()
    private val lengthNext = Rect()
    private val fitRow = Rect()
    private val fitButton = Rect()
    private val scalePrev = Rect()
    private val scaleNext = Rect()
    private val scaleDone = Rect()
    private val columnsRow = Rect()
    private val columnsPrev = Rect()
    private val columnsNext = Rect()
    private var panelNote: List<String> = emptyList()
    private var panelNoteWidth = -1f
    private var panelNoteTuned = false

    /**
     * The pattern's editor-only root and scale, drawn over the top right of the grid: a one-octave key strip
     * lights the scale's pitch classes (tap a key for the root), arrows step the scale, Done closes. The
     * focused row follows [org.thortracker.editor.ViewState.scaleField] for the controller.
     */
    private fun drawScalePanel(c: Surface, area: Rect): Rect {
        val u = ink.unit
        val scale = ctl.patternScale
        val small = u * 0.8f
        val pad = u * 0.7f
        val w = minOf(u * 24f, area.width() - u)
        val labelW = u * 3.6f
        val textW = w - pad * 2
        val tuned = song.tuning != null
        if (textW != panelNoteWidth || tuned != panelNoteTuned) {
            panelNoteWidth = textW
            panelNoteTuned = tuned
            val help = song.system.help
            panelNote = wrap((if (tuned) "The song tuning replaces this scale for ${help.tonalChannels} note edits (Menu → Tuning). "
                else "${help.tonalChannels} note edits land only on scale notes; ${help.untonalNote} ") +
                "Written notes stay until you edit them; the scale is not exported or heard. Length sets how many rows " +
                "every order using this pattern plays; later rows are hidden, not deleted.", textW, small)
        }
        val titleH = u * 1.9f
        val keyH = u * 2.6f
        val lineH = u * 2.1f
        val noteH = u * 1.1f
        val h = pad + titleH + keyH + u * 0.4f + (lineH + u * 0.3f) * 3 + u * 0.2f + noteH * panelNote.size + u * 0.4f + lineH + pad * 0.6f +
            (if (multi) lineH + u * 0.3f else 0f)
        val left = area.right - w - u * 0.5f
        val top = (headerBottom + u * 0.3f).coerceAtMost(maxOf(area.top, area.bottom - h))
        val panel = Rect(left, top, left + w, top + h)
        ink.round(c, panel, ink.dp(6f), Palette.LANE)
        ink.outline(c, panel, Palette.RULE, ink.dp(1f), ink.dp(6f))
        ink.rect(c, panel.left + ink.dp(6f), panel.top, panel.right - ink.dp(6f), panel.top + ink.dp(2f), Palette.PLAYHEAD)

        val x = left + pad
        var y = top + pad * 0.6f
        ink.label(c, "Pattern ${hex2(song.patternAt(st.order))}", x, y, titleH, Palette.TEXT, true)
        ink.label(c, ink.fit(scale.label, textW * 0.5f, bold = true), panel.right - pad, y, titleH, Palette.PLAYHEAD, true, align = Align.RIGHT)
        y += titleH

        // Root: a one-octave strip lighting the scale's pitch classes, the root outlined.
        rootRow.set(left + pad * 0.4f, y, panel.right - pad * 0.4f, y + keyH)
        focusRow(c, rootRow, 0)
        ink.label(c, "Root", x, y, keyH, Palette.DIM)
        val stripL = x + labelW
        val kw = (panel.right - pad - stripL) / 12f
        for (k in 0 until 12) {
            val r = rootKeys[k]
            r.set(stripL + k * kw + ink.dp(1f), y + keyH * 0.14f, stripL + (k + 1) * kw - ink.dp(1f), y + keyH * 0.86f)
            val inScale = scale.kind.hasInterval(k - scale.root)
            val base = if (BLACK_KEYS[k]) Palette.BG else Palette.BAR
            ink.round(c, r, ink.dp(3f), if (inScale && !scale.isChromatic) Palette.mix(base, Palette.PLAYHEAD, 0.38f) else base)
            val isRoot = k == scale.root
            if (isRoot) ink.outline(c, r, if (scale.isChromatic) Palette.DIM else Palette.PLAYHEAD, ink.dp(2f), ink.dp(3f))
            val name = Scale.ROOT_NAMES[k]
            ink.label(c, name, r.centerX(), r.top, r.height(), when {
                isRoot && !scale.isChromatic -> Palette.TEXT
                inScale && !scale.isChromatic -> Palette.TEXT
                else -> Palette.FAINT
            }, isRoot, if (name.length > 1) u * 0.62f else u * 0.75f, Align.CENTER)
        }
        y += keyH + u * 0.4f

        // Scale: previous / name / next.
        scaleRow.set(left + pad * 0.4f, y, panel.right - pad * 0.4f, y + lineH)
        focusRow(c, scaleRow, 1)
        ink.label(c, "Scale", x, y, lineH, Palette.DIM)
        val bw = u * 2.2f
        scalePrev.set(stripL, y + lineH * 0.12f, stripL + bw, y + lineH * 0.88f)
        scaleNext.set(panel.right - pad - bw, scalePrev.top, panel.right - pad, scalePrev.bottom)
        ink.round(c, scalePrev, ink.dp(4f), Palette.RULE)
        ink.round(c, scaleNext, ink.dp(4f), Palette.RULE)
        ink.label(c, "◀", scalePrev.centerX(), y, lineH, Palette.TEXT, size = small, align = Align.CENTER)
        ink.label(c, "▶", scaleNext.centerX(), y, lineH, Palette.TEXT, size = small, align = Align.CENTER)
        ink.label(c, ink.fit(scale.kind.label, scaleNext.left - scalePrev.right - u * 0.6f), (scalePrev.right + scaleNext.left) / 2f, y, lineH,
            Palette.TEXT, true, align = Align.CENTER)
        y += lineH + u * 0.3f

        // Length: previous / rows / next.
        lengthRow.set(left + pad * 0.4f, y, panel.right - pad * 0.4f, y + lineH)
        focusRow(c, lengthRow, 2)
        ink.label(c, "Length", x, y, lineH, Palette.DIM)
        lengthPrev.set(stripL, y + lineH * 0.12f, stripL + bw, y + lineH * 0.88f)
        lengthNext.set(panel.right - pad - bw, lengthPrev.top, panel.right - pad, lengthPrev.bottom)
        ink.round(c, lengthPrev, ink.dp(4f), Palette.RULE)
        ink.round(c, lengthNext, ink.dp(4f), Palette.RULE)
        ink.label(c, "◀", lengthPrev.centerX(), y, lineH, Palette.TEXT, size = small, align = Align.CENTER)
        ink.label(c, "▶", lengthNext.centerX(), y, lineH, Palette.TEXT, size = small, align = Align.CENTER)
        val len = ctl.patternLength
        ink.label(c, if (len < Songs.ROWS) "$len rows" else "$len rows (full)", (lengthPrev.right + lengthNext.left) / 2f, y, lineH,
            if (len < Songs.ROWS) Palette.PLAYHEAD else Palette.TEXT, true, align = Align.CENTER)
        y += lineH + u * 0.3f

        // Fit: nudge the played notes of tonal channels onto the scale (one undo step).
        fitRow.set(left + pad * 0.4f, y, panel.right - pad * 0.4f, y + lineH)
        focusRow(c, fitRow, ViewController.SCALE_FIT_FIELD)
        ink.label(c, "Notes", x, y, lineH, Palette.DIM)
        val canFit = !scale.isChromatic && !tuned
        fitButton.set(stripL, y + lineH * 0.12f, panel.right - pad, y + lineH * 0.88f)
        ink.round(c, fitButton, ink.dp(4f), if (canFit) Palette.alpha(Palette.PLAYHEAD, 60) else Palette.RULE)
        ink.label(c, ink.fit(if (canFit) "Fit notes to ${scale.label}" else if (tuned) "Fit via Menu → Tuning" else "Fit notes: choose a scale",
            fitButton.width() - u, small), fitButton.centerX(), y, lineH, if (canFit) Palette.PLAYHEAD else Palette.DIM, canFit, small, Align.CENTER)
        y += lineH + u * 0.5f

        // Columns (several-column systems): the effect columns shown on the cursor's channel, this display only.
        columnsRow.set(0f, 0f, 0f, 0f)
        if (multi) {
            y -= u * 0.2f
            columnsRow.set(left + pad * 0.4f, y, panel.right - pad * 0.4f, y + lineH)
            focusRow(c, columnsRow, ViewController.SCALE_COLUMNS_FIELD)
            ink.label(c, "Columns", x, y, lineH, Palette.DIM)
            columnsPrev.set(stripL, y + lineH * 0.12f, stripL + bw, y + lineH * 0.88f)
            columnsNext.set(panel.right - pad - bw, columnsPrev.top, panel.right - pad, columnsPrev.bottom)
            ink.round(c, columnsPrev, ink.dp(4f), Palette.RULE)
            ink.round(c, columnsNext, ink.dp(4f), Palette.RULE)
            ink.label(c, "◀", columnsPrev.centerX(), y, lineH, Palette.TEXT, size = small, align = Align.CENTER)
            ink.label(c, "▶", columnsNext.centerX(), y, lineH, Palette.TEXT, size = small, align = Align.CENTER)
            val ch = st.channel
            val auto = st.effectColumns.getOrElse(ch) { 0 } == 0
            val text = "${song.system.channels[ch].name}: ${ctl.shownColumns(ch)} of ${song.system.effectColumns(ch)}${if (auto) " (auto)" else ""}"
            ink.label(c, ink.fit(text, columnsNext.left - columnsPrev.right - u * 0.6f, bold = true), (columnsPrev.right + columnsNext.left) / 2f, y, lineH,
                Palette.channel(ch), true, align = Align.CENTER)
            y += lineH + u * 0.5f
        }

        for (part in panelNote) {
            ink.label(c, part, x, y, noteH, Palette.DIM, size = small)
            y += noteH
        }
        y += u * 0.4f
        val doneW = ink.labelWidth("Done", small, true) + u * 1.6f
        scaleDone.set(panel.right - pad - doneW, y + lineH * 0.1f, panel.right - pad, y + lineH * 0.9f)
        ink.round(c, scaleDone, ink.dp(4f), Palette.alpha(Palette.PLAYHEAD, 60))
        ink.label(c, "Done", scaleDone.centerX(), y, lineH, Palette.PLAYHEAD, true, small, Align.CENTER)
        val footer = if (st.scaleField == ViewController.SCALE_FIT_FIELD)
            "${v.keymap.label(Action.EDIT)} fit  ${v.keymap.label(Action.SCALE, st.kind)} close"
            else "${v.keymap.label(Action.EDIT)} done  ${v.keymap.label(Action.CLEAR)} reset"
        ink.label(c, footer, x, y, lineH, Palette.FAINT, size = small)
        return panel
    }

    /** Marks the panel row the controller is on. */
    private fun focusRow(c: Surface, r: Rect, field: Int) {
        if (st.scaleField != field) return
        ink.round(c, r, ink.dp(4f), Palette.CURSOR_FILL)
        ink.outline(c, r, if (v.controllerFocused) Palette.TEXT else Palette.DIM, if (v.controllerFocused) ink.dp(1.5f) else ink.dp(1f), ink.dp(4f))
    }

    // ---- touch -------------------------------------------------------------------------------------

    override fun down(x: Float, y: Float): Boolean {
        touchStartY = y
        touchStartScroll = st.scroll
        dragging = false
        for ((r, id) in headerChips) if (r.contains(x, y)) { touchTarget = 10 + id; return true }
        val panel = scalePanel
        if (panel != null) {
            if (panel.contains(x, y)) { touchTarget = 20; return true }
            ctl.closeScalePanel() // a touch elsewhere puts the panel away and acts as usual
        }
        val insp = inspector
        touchTarget = when {
            insp != null && insp.contains(x, y) -> if (y >= orderListTop) 3 else 0
            y < headerBottom -> 0
            multi && scrollLeft.contains(x, y) -> 4
            multi && scrollRight.contains(x, y) -> 5
            y < gridTop -> 2
            x >= gridLeft && x < gridRight() -> 1
            x < gridLeft && y >= gridTop -> 1
            else -> 0
        }
        return touchTarget != 0
    }

    /** The shown lane under [x] (several-column layout), or null. */
    private fun laneAt(x: Float): Int? = (firstLane..lastLane).firstOrNull { x >= laneLeft(it) && x < laneRight(it) }

    override fun move(x: Float, y: Float) {
        if (touchTarget != 1) return
        val dy = y - touchStartY
        if (!dragging && kotlin.math.abs(dy) > rowH * 0.6f) dragging = true
        if (dragging) {
            v.touchScrolled = true
            st.scroll = touchStartScroll - (dy / rowH).toInt()
        }
    }

    override fun up(x: Float, y: Float) {
        val s = song
        when (touchTarget) {
            1 -> if (!dragging) {
                val row = st.scroll + ((y - gridTop) / rowH).toInt()
                if (y < gridTop || row >= s.rowCount(s.patternAt(st.order))) return
                st.row = row
                if (x >= gridLeft) {
                    if (!multi) {
                        val ch = ((x - gridLeft) / laneW).toInt().coerceIn(0, s.channelCount() - 1)
                        val local = (x - gridLeft - ch * laneW - cw * 0.7f) / cw
                        st.channel = ch
                        st.field = when { local < 3.5f -> 0; local < 6.5f -> 1; local < 7.6f -> 2; else -> 3 }
                    } else {
                        val ch = laneAt(x) ?: return
                        val local = (x - laneLeft(ch) - cw * 0.7f) / cw
                        val k = ((local - 6.5f) / 4f).toInt().coerceIn(0, ctl.shownColumns(ch) - 1)
                        st.channel = ch
                        st.field = when {
                            local < 3.5f -> 0
                            local < 6.5f -> 1
                            local - 6.5f - 4 * k < 1.1f -> 2 + 2 * k
                            else -> 3 + 2 * k
                        }
                    }
                }
                v.touchScrolled = false
                ctl.publishPattern()
            }
            2 -> {
                val ch = if (multi) laneAt(x) ?: -1 else ((x - gridLeft) / laneW).toInt()
                if (ch in 0 until s.channelCount()) { st.channel = ch; ctl.publishPattern() }
            }
            4, 5 -> {
                st.channel = (if (touchTarget == 4) firstLane - 1 else lastLane + 1).coerceIn(0, s.channelCount() - 1)
                ctl.normalise()
                ctl.publishPattern()
            }
            3 -> {
                val o = orderScroll + ((y - orderListTop) / orderRowH).toInt()
                if (o in s.orders.indices) { st.order = o; st.mark = false; v.touchScrolled = false; ctl.normalise(); ctl.publishPattern() }
            }
            10 -> st.followPlayback = !st.followPlayback
            11 -> session.step = if (session.step >= 4) 0 else session.step + 1
            12 -> st.octave = if (st.octave >= 7) 3 else st.octave + 1
            13 -> if (st.scalePanel) ctl.closeScalePanel() else ctl.openScalePanel()
            20 -> {
                val key = rootKeys.indexOfFirst { it.contains(x, y) }
                when {
                    key >= 0 -> ctl.stepScale(0, key - ctl.patternScale.root)
                    scalePrev.contains(x, y) -> ctl.stepScale(1, -1)
                    scaleNext.contains(x, y) -> ctl.stepScale(1, 1)
                    lengthPrev.contains(x, y) -> ctl.stepLength(-1)
                    lengthNext.contains(x, y) -> ctl.stepLength(1)
                    scaleDone.contains(x, y) -> ctl.closeScalePanel()
                    fitButton.contains(x, y) -> ctl.fitPatternToScale()
                    multi && columnsPrev.contains(x, y) -> ctl.stepShownColumns(-1)
                    multi && columnsNext.contains(x, y) -> ctl.stepShownColumns(1)
                    rootRow.contains(x, y) -> st.scaleField = 0
                    scaleRow.contains(x, y) -> st.scaleField = 1
                    lengthRow.contains(x, y) -> st.scaleField = 2
                    fitRow.contains(x, y) -> st.scaleField = ViewController.SCALE_FIT_FIELD
                    multi && columnsRow.contains(x, y) -> st.scaleField = ViewController.SCALE_COLUMNS_FIELD
                }
            }
        }
        dragging = false
    }

    override fun cancel() { dragging = false }

    private companion object {
        const val LAMP_ROWS = 4
        /** Most channels a system has room for in the lane arrays (the OPL3 has 9). */
        const val MAX_LANES = 16
        /** Pitch classes on black piano keys, C = 0. */
        val BLACK_KEYS = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)
    }
}
