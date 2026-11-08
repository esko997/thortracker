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
        val laneInner = cw * 10f
        val lanePad = cw * 1.2f
        val minLane = laneInner + lanePad
        val wantInspector = area.width() - rulerW - channels * minLane > ink.unit * 15f
        laneW = if (wantInspector) minOf(cw * 15f, (area.width() - rulerW - ink.unit * 15f) / channels)
        else (area.width() - rulerW) / channels
        val inspectorW = if (wantInspector) area.width() - rulerW - laneW * channels else 0f
        gridLeft = area.left + rulerW
        laneTop = headerBottom
        val laneHeaderH = ink.unit * 1.7f
        gridTop = laneTop + laneHeaderH
        visibleRows = maxOf(1, ((area.bottom - gridTop) / rowH).toInt())

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
        for (ch in 0 until channels) {
            val x = gridLeft + ch * laneW
            val col = Palette.channel(ch)
            val heard = if (ch != auditionChannel) 0f else if (auditionLit) 1f else motion.fade(auditionEnd, 320)
            val lamp = maxOf(playLamp(ch), heard)
            ink.rect(c, x, laneTop, x + laneW, gridTop, Palette.mix(Palette.BG, col, 0.10f + 0.12f * lamp))
            ink.rect(c, x, laneTop, x + laneW, laneTop + ink.dp(3f), if (lamp > 0f) Palette.mix(col, Palette.TEXT, 0.6f * lamp) else col)
            val pen = ctl.pen(s.system.channels[ch].bank)
            val penName = s.system.instrumentName(s, s.system.channels[ch].bank, pen) ?: ""
            ink.label(c, names[ch], x + cw * 0.6f, laneTop, laneHeaderH, col, true)
            val nw = ink.labelWidth(names[ch], ink.unit, true)
            ink.label(c, ink.fit("${hex2(pen)} $penName", laneW - nw - cw * 2f, ink.unit * 0.8f), x + cw * 1.2f + nw, laneTop, laneHeaderH, Palette.DIM, size = ink.unit * 0.8f)
        }

        // rows background + ruler
        for (r in first..last) {
            val y = gridTop + (r - first) * rowH
            val bg = when {
                r % 16 == 0 -> Palette.BAR
                r % 4 == 0 -> Palette.BEAT
                else -> Palette.BG
            }
            ink.rect(c, area.left, y, gridLeft + laneW * channels, y + rowH, bg)
            val rc = if (r % 4 == 0) Palette.TEXT else Palette.FAINT
            ink.mono(c, hex2(r), area.left + cw * 0.5f, y, rowH, rc, r % 16 == 0)
        }
        for (ch in 0 until channels) {
            val x = gridLeft + ch * laneW
            ink.line(c, x, laneTop, x, gridTop + (last - first + 1) * rowH, Palette.RULE, ink.dp(1f))
        }

        // playhead band
        if (playHere && v.playback.row in first..last) {
            val y = gridTop + (v.playback.row - first) * rowH
            ink.rect(c, area.left, y, gridLeft + laneW * channels, y + rowH, Palette.alpha(Palette.PLAYHEAD, 54))
            ink.rect(c, area.left, y, area.left + ink.dp(4f), y + rowH, Palette.PLAYHEAD)
        } else if (samePatternPlaying && v.playback.row in first..last) {
            val y = gridTop + (v.playback.row - first) * rowH
            ink.outline(c, Rect(area.left + 1, y + 1, gridLeft + laneW * channels - 1, y + rowH - 1), Palette.alpha(Palette.PLAYHEAD, 120), ink.dp(1f))
        }

        // mark
        if (st.mark) {
            val b = ctl.block()
            val r0 = maxOf(b.firstRow, first)
            val r1 = minOf(b.lastRow, last)
            if (r0 <= r1) {
                val rect = Rect(gridLeft + b.firstChannel * laneW, gridTop + (r0 - first) * rowH, gridLeft + (b.lastChannel + 1) * laneW, gridTop + (r1 - first + 1) * rowH)
                ink.rect(c, rect, Palette.alpha(Palette.LISTEN, 34))
                ink.outline(c, rect, Palette.alpha(Palette.LISTEN, 160), ink.dp(1.5f))
            }
        }

        // cursor row band
        if (st.row in first..last) {
            val y = gridTop + (st.row - first) * rowH
            ink.rect(c, gridLeft, y, gridLeft + laneW * channels, y + rowH, Palette.alpha(Palette.TEXT, 14))
        }

        // edit afterglow: the cells the last change touched, behind their text
        val glow = motion.fade(flashStart, 420)
        if (glow > 0f) {
            val r0 = maxOf(flashRow0, first)
            val r1 = minOf(flashRow1, last)
            if (r0 <= r1) for (ch in flashCh0..minOf(flashCh1, channels - 1)) {
                val x = gridLeft + ch * laneW
                ink.rect(c, x + 1, gridTop + (r0 - first) * rowH, x + laneW, gridTop + (r1 - first + 1) * rowH, Palette.alpha(Palette.channel(ch), (glow * 92).toInt()))
            }
        }

        // cells + sustain rails; the cursor cell may show an explicitly chosen arpeggio 000
        val zeroHere = ctl.chosenArpeggio()
        for (ch in 0 until channels) {
            val x = gridLeft + ch * laneW + cw * 0.7f
            val col = Palette.channel(ch)
            val track = p.tracks[ch]
            var sounding = false
            for (r in 0 until first) sounding = nextSounding(sounding, track[r])
            for (r in first..last) {
                val cell = track[r]
                val y = gridTop + (r - first) * rowH
                val wasSounding = sounding
                sounding = nextSounding(sounding, cell)
                val (top, bottom) = when {
                    cell.note >= 0 -> y + rowH * 0.5f to y + rowH
                    cell.note == Cell.CUT && wasSounding -> y to y + rowH * 0.5f
                    cell.note == Cell.EMPTY && wasSounding -> y to y + rowH
                    else -> 0f to 0f
                }
                if (bottom > top) ink.rect(c, x - cw * 0.45f, top, x - cw * 0.45f + ink.dp(2f), bottom, Palette.alpha(col, 120))
                if (cell.note >= 0) session.fittedFrom(pattern, ch, r)?.let { from -> drawFitMark(c, ch, r - first, pitchOrder(cell) > pitchOrder(from)) }
                drawCell(c, cell, x, y, col, ch, zeroHere && ch == st.channel && r == st.row)
            }
        }

        // cursor
        if (st.row in first..last) {
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
            drawCell(c, cell, gridLeft + st.channel * laneW + cw * 0.7f, gridTop + (st.row - first) * rowH, Palette.channel(st.channel), st.channel, zeroHere)
        }

        // inspector
        inspector = if (wantInspector) Rect(area.right - inspectorW, laneTop, area.right, area.bottom).also { drawInspector(c, it) } else null

        // root/scale panel over the grid
        scalePanel = if (st.scalePanel) drawScalePanel(c, area) else null
    }

    private fun nextSounding(sounding: Boolean, cell: Cell): Boolean = when {
        cell.note >= 0 -> true
        cell.note == Cell.CUT -> false
        else -> sounding
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

    private fun fieldRect(ch: Int, field: Int, visRow: Int): Rect {
        val x = gridLeft + ch * laneW + cw * 0.7f
        val y = gridTop + visRow * rowH
        val (start, len) = when (field) { 0 -> 0f to 3f; 1 -> 4f to 2f; 2 -> 7f to 1f; else -> 8f to 2f }
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

    /** [zero]: show this 000 as the explicitly chosen effect 0 (arpeggio) rather than as no effect. */
    private fun drawCell(c: Surface, cell: Cell, x: Float, y: Float, col: Int, ch: Int, zero: Boolean = false) {
        val noteText = when {
            cell.note >= 0 -> song.system.noteName(cell.note)
            cell.note == Cell.CUT -> "OFF"
            else -> "···"
        }
        val noteColor = when {
            cell.note >= 0 -> col
            cell.note == Cell.CUT -> Palette.WRITE
            else -> Palette.FAINT
        }
        ink.mono(c, noteText, x, y, rowH, noteColor, cell.note >= 0)
        ink.mono(c, if (cell.instrument > 0) hex2(cell.instrument) else "··", x + cw * 4, y, rowH, if (cell.instrument > 0) Palette.TEXT else Palette.FAINT)
        val hasFx = cell.hasEffect
        val shown = hasFx || zero
        ink.mono(c, if (shown) EffectSpec.textOf(cell.effect) else "·", x + cw * 7, y, rowH, if (shown) Palette.PLAYHEAD else Palette.FAINT, hasFx)
        ink.mono(c, if (shown) hex2(cell.param) else "··", x + cw * 8, y, rowH, if (shown) Palette.DIM else Palette.FAINT)
        if ((cell.note != Cell.EMPTY || cell.instrument != 0 || hasFx) && hasProblem(ch, cell)) {
            ink.rect(c, x - cw * 0.2f, y + rowH - ink.dp(3f), x + cw * 10.2f, y + rowH - ink.dp(1f), Palette.ERROR)
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
        if (cell.hasEffect) {
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
        val h = pad + titleH + keyH + u * 0.4f + (lineH + u * 0.3f) * 3 + u * 0.2f + noteH * panelNote.size + u * 0.4f + lineH + pad * 0.6f
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
            y < gridTop -> 2
            x >= gridLeft && x < gridLeft + laneW * song.channelCount() -> 1
            x < gridLeft && y >= gridTop -> 1
            else -> 0
        }
        return touchTarget != 0
    }

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
                    val ch = ((x - gridLeft) / laneW).toInt().coerceIn(0, s.channelCount() - 1)
                    val local = (x - gridLeft - ch * laneW - cw * 0.7f) / cw
                    st.channel = ch
                    st.field = when { local < 3.5f -> 0; local < 6.5f -> 1; local < 7.6f -> 2; else -> 3 }
                }
                v.touchScrolled = false
                ctl.publishPattern()
            }
            2 -> {
                val ch = ((x - gridLeft) / laneW).toInt()
                if (ch in 0 until s.channelCount()) { st.channel = ch; ctl.publishPattern() }
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
                    rootRow.contains(x, y) -> st.scaleField = 0
                    scaleRow.contains(x, y) -> st.scaleField = 1
                    lengthRow.contains(x, y) -> st.scaleField = 2
                    fitRow.contains(x, y) -> st.scaleField = ViewController.SCALE_FIT_FIELD
                }
            }
        }
        dragging = false
    }

    override fun cancel() { dragging = false }

    private companion object {
        const val LAMP_ROWS = 4
        /** Pitch classes on black piano keys, C = 0. */
        val BLACK_KEYS = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)
    }
}
