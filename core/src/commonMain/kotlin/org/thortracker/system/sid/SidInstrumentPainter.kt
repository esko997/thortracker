package org.thortracker.system.sid

import kotlin.math.exp
import kotlin.math.sqrt
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.channelCount
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.sid.SidInstrumentView.Page
import org.thortracker.system.sid.SidInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * SID instrument editor. Left: the 63 slots of the shared bank with how often each is used. Right: name, the voice
 * Listen plays on, page tabs, then the page: the envelope settings over the drawn ADSR curve; a program as rows of
 * columns (the cursor on one column), its loops marked, with the pulse width or cutoff it plays over time; vibrato
 * and hard restart; or the song's tables as the player reads them, each row with the program that owns it.
 */
internal class SidInstrumentPainter(v: PaneView) : Painter(v) {
    private val tabs = ArrayList<Pair<Rect, Int>>()
    private val tableTabs = ArrayList<Pair<Rect, Int>>()
    private var slotTop = 0f
    private var slotH = 0f
    private var slotRect = Rect()
    private var slotScroll = 0
    private var listRect = Rect()
    private var listRowH = 0f
    private var listScroll = 0
    private var rawScroll = 0
    /** Right end of the settings column, for fitting values. */
    private var rightEdge = 0f

    private var usageSong: Song? = null
    private val usage = HashMap<Int, Int>()

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = SidInstrumentView.sound(s, st) ?: return
        val page = SidInstrumentView.page(st)
        val col = ink.palette.channel(st.listenChannel)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "C64 SID · ${s.sid.chip.label}$locked  ${usageText(st.instrument)}")
        val row = SidInstrumentView.row(st)

        // left column: the bank, scrolled to keep the selected slot in view
        val leftW = minOf(area.width() * 0.28f, ink.unit * 13f)
        val left = Rect(area.left, top, area.left + leftW, area.bottom)
        ink.rect(c, left, ink.palette.LANE)
        slotTop = top + ink.unit * 0.2f
        slotH = ink.unit * 1.45f
        val visible = maxOf(1, ((area.bottom - slotTop) / slotH).toInt())
        slotScroll = slotScroll.coerceIn(maxOf(0, st.instrument - visible), minOf(st.instrument - 1, SidSystem.SLOTS - visible).coerceAtLeast(0))
        slotRect = Rect(left.left, slotTop, left.right, slotTop + slotH * visible)
        val bank = s.sid.instruments
        for (k in 0 until minOf(visible, SidSystem.SLOTS - slotScroll)) {
            val i = slotScroll + k + 1
            val y = slotTop + k * slotH
            val sel = i == st.instrument
            if (sel) {
                ink.rect(c, left.left, y, left.right, y + slotH, ink.palette.CURSOR_FILL)
                ink.rect(c, left.left, y, left.left + ink.dp(3f), y + slotH, col)
            }
            val sz = minOf(ink.unit, slotH * 0.72f)
            ink.mono(c, hex2(i), left.left + ink.unit * 0.6f, y, slotH, if (sel) col else ink.palette.FAINT, sel, sz)
            val used = usage(i)
            val nameColor = if (sel) ink.palette.TEXT else if (used > 0) ink.palette.DIM else ink.palette.FAINT
            ink.label(c, ink.fit(bank[i - 1].name, leftW - ink.unit * 4.6f, sz), left.left + ink.unit * 2.4f, y, slotH, nameColor, size = sz)
            if (used > 0) ink.mono(c, used.toString(), left.right - ink.unit * 0.5f, y, slotH, ink.palette.FAINT, size = sz * 0.8f, align = Align.RIGHT)
        }
        if (row == Row.SLOT) {
            val y = slotTop + (st.instrument - 1 - slotScroll) * slotH
            ink.outline(c, Rect(left.left + 2, y + 1, left.right - 2, y + slotH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }

        // right column: settings rows
        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val rw = area.right - pad - rx
        rightEdge = rx + rw
        val rowH = ink.unit * 1.5f
        val vx = rx + ink.unit * 6.5f
        var y = top + ink.unit * 0.3f
        listRect = Rect()
        for (r in SidInstrumentView.rows(st)) {
            if (r == Row.SLOT || r == Row.STEPS || r == Row.RAW) continue
            val sel = r == row
            val h = if (r == Row.PAGE || r == Row.TABLE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, ink.palette.CURSOR_FILL)
            val vib = ins.vibrato
            when (r) {
                Row.NAME -> {
                    ink.label(c, "Name", rx, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM)
                    ink.label(c, ins.name.ifEmpty { "(unnamed)" }, vx, y, h, if (ins.name.isEmpty()) ink.palette.FAINT else ink.palette.TEXT, true)
                    if (sel) {
                        val before = ins.name.take(st.textPos).padEnd(st.textPos, ' ')
                        val cx = vx + ink.labelWidth(before, ink.unit, true)
                        val ch = ins.name.getOrNull(st.textPos)?.toString() ?: " "
                        val w = maxOf(ink.labelWidth(ch, ink.unit, true), ink.unit * 0.5f)
                        ink.outline(c, Rect(cx - 1, y + 2, cx + w + 1, y + h - 2), cursorColor(), ink.dp(2f), ink.dp(2f))
                    }
                }
                Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM)
                    ink.label(c, s.system.channels.getOrNull(st.listenChannel)?.name ?: "", vx, y, h, col, true)
                }
                Row.PAGE -> drawTabs(c, ins, Rect(rx, y, rx + rw, y + h), col)
                Row.PRESET -> {
                    val i = SidPresets.indexOf(ins, st.listenChannel)
                    setting(c, "Preset", if (i < 0) "custom   ${ctl.keys.held(org.thortracker.editor.Action.EDIT, ctl.lr())} picks one to start from"
                        else "${SidPresets.ALL[i].name}   ${SidPresets.ALL[i].description}", rx, vx, y, h, sel, i < 0)
                }
                Row.ATTACK -> setting(c, "Attack", "${NumberText.hex(ins.attack)}   ${SidInstrumentView.msText(SidInstrumentView.ATTACK_MS[ins.attack])} to full", rx, vx, y, h, sel, false)
                Row.DECAY -> setting(c, "Decay", "${NumberText.hex(ins.decay)}   ${SidInstrumentView.msText(3 * SidInstrumentView.ATTACK_MS[ins.decay])} to sustain", rx, vx, y, h, sel, false)
                Row.SUSTAIN -> setting(c, "Sustain", "${NumberText.hex(ins.sustain)}   level ${ins.sustain * 100 / 15}% while held", rx, vx, y, h, sel, false)
                Row.RELEASE -> setting(c, "Release", "${NumberText.hex(ins.release)}   ${SidInstrumentView.msText(3 * SidInstrumentView.ATTACK_MS[ins.release])} after OFF", rx, vx, y, h, sel, false)
                Row.LENGTH -> {
                    val p = ins.program(page.table)
                    setting(c, "Program", when {
                        p.rows.isEmpty() -> when (page.table) {
                            SidTables.WAVE -> "none: the waveform stays as it is"
                            SidTables.PULSE -> "none: the pulse width stays as it is"
                            else -> "none: the filter is left alone"
                        }
                        else -> "${p.rows.size} row${if (p.rows.size == 1) "" else "s"}  · table rows ${hex2(p.at)}-${hex2(p.at + p.size - 1)}"
                    }, rx, vx, y, h, sel, p.rows.isEmpty())
                }
                Row.RESTART -> setting(c, "Restart", when (ins.restart) {
                    SidRestart.HARD -> "hard restart: silenced with the song's HR envelope first (clean attacks)"
                    SidRestart.GATE_OFF -> "gate off only: no hard restart (faster, may click)"
                    SidRestart.LEGATO -> "legato: no gate off, the envelope keeps running"
                }, rx, vx, y, h, sel, false)
                Row.GATE_TIMER -> setting(c, "Gate timer", "${ins.gateTimer} tick${if (ins.gateTimer == 1) "" else "s"} before the note (keep it below the tempo)", rx, vx, y, h, sel, false)
                Row.FIRST_WAVE -> setting(c, "First wave", SidInstrumentView.firstWaveText(ins.firstWave), rx, vx, y, h, sel, false)
                Row.VIB_DELAY -> setting(c, "Vibrato", if (!vib.active) "off (set a delay to start one)" else "after ${vib.delay} tick${if (vib.delay == 1) "" else "s"}", rx, vx, y, h, sel, !vib.active)
                Row.VIB_SPEED -> setting(c, "Speed", if (!vib.active) "—" else "${vib.speed} tick${if (vib.speed == 1) "" else "s"} per turn", rx, vx, y, h, sel, !vib.active)
                Row.VIB_DEPTH -> setting(c, "Depth", when {
                    !vib.active -> "—"
                    vib.noteIndependent -> "${vib.depth}   1/${1 shl vib.depth} semitone per tick"
                    else -> "${hex2(vib.depth)}   frequency units per tick"
                }, rx, vx, y, h, sel, !vib.active)
                Row.VIB_MODE -> setting(c, "Depth in", if (vib.noteIndependent) "semitone steps (same width on every note)" else "frequency units (wider on high notes)", rx, vx, y, h, sel, !vib.active)
                Row.TABLE -> drawTableTabs(c, Rect(rx, y, rx + rw, y + h), col)
                else -> Unit
            }
            if (sel) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }
        val box = Rect(rx, y + ink.unit * 0.4f, rx + rw, area.bottom - ink.unit * 0.4f)
        if (box.height() < ink.unit * 4f) return
        when (page) {
            Page.ENVELOPE -> drawEnvelope(c, box, ins, col)
            Page.WAVE, Page.PULSE, Page.FILTER -> drawProgram(c, box, ins, page.table, col, row == Row.STEPS)
            Page.START -> drawVibrato(c, box, ins, col)
            Page.TABLES -> drawRaw(c, box, row == Row.RAW)
        }
    }

    private fun setting(c: Surface, label: String, value: String, x: Float, vx: Float, y: Float, h: Float, sel: Boolean, off: Boolean) {
        ink.label(c, label, x, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM)
        val max = rightEdge - vx
        ink.label(c, ink.fit(value, max, ink.unit, sel), vx, y, h, if (sel) (if (off) ink.palette.DIM else ink.palette.TEXT) else (if (off) ink.palette.FAINT else ink.palette.DIM), sel)
    }

    private fun cursorColor() = when {
        ctl.editHeld -> ink.palette.WRITE
        ctl.auditionHeld -> ink.palette.LISTEN
        v.controllerFocused -> ink.palette.FOCUS
        else -> ink.palette.DIM
    }

    /** Page tabs; program tabs carry their row count. */
    private fun drawTabs(c: Surface, ins: SidInstrument, r: Rect, col: Int) {
        tabs.clear()
        val pages = Page.entries
        val w = r.width() / pages.size
        for ((i, p) in pages.withIndex()) {
            val t = Rect(r.left + i * w, r.top, r.left + (i + 1) * w, r.bottom)
            val on = i == st.envelope
            if (on) ink.rect(c, t, Palette.mix(ink.palette.BG, col, 0.18f))
            ink.rect(c, t.left, t.bottom - ink.dp(if (on) 3f else 1f), t.right, t.bottom, if (on) col else ink.palette.RULE)
            val n = if (p.table >= 0) ins.program(p.table).rows.size else 0
            val label = if (n == 0) p.label else "${p.label} ($n)"
            val empty = p.table >= 0 && n == 0 || p == Page.START && !ins.vibrato.active && ins.restart == SidRestart.HARD
            ink.label(c, ink.fit(label, w - ink.unit * 0.4f, ink.unit * 0.9f), t.centerX(), t.top, t.height(),
                if (on) col else if (empty) ink.palette.FAINT else ink.palette.DIM, on, ink.unit * 0.9f, Align.CENTER)
            tabs += t to i
        }
    }

    private fun drawTableTabs(c: Surface, r: Rect, col: Int) {
        tableTabs.clear()
        val w = r.width() / SidTables.NAMES.size
        val sound = song.sid
        for ((i, name) in SidTables.NAMES.withIndex()) {
            val t = Rect(r.left + i * w, r.top, r.left + (i + 1) * w, r.bottom)
            val on = i == st.rawTable
            if (on) ink.rect(c, t, Palette.mix(ink.palette.BG, col, 0.18f))
            ink.rect(c, t.left, t.bottom - ink.dp(if (on) 3f else 1f), t.right, t.bottom, if (on) col else ink.palette.RULE)
            val used = if (i == SidTables.SPEED) speeds().size else placement(i).used
            ink.label(c, ink.fit("$name $used/${SidTables.ROWS}", w - ink.unit * 0.4f, ink.unit * 0.85f), t.centerX(), t.top, t.height(),
                if (on) col else ink.palette.DIM, on, ink.unit * 0.85f, Align.CENTER)
            tableTabs += t to i
        }
    }

    // ---- envelope -------------------------------------------------------------------------------------

    private val xs = FloatArray(64)
    private val ys = FloatArray(64)

    /** The ADSR curve: attack, decay to the sustain level, a held stretch, release after OFF; segment widths ∝ √time. */
    private fun drawEnvelope(c: Surface, r: Rect, ins: SidInstrument, col: Int) {
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val titleH = ink.unit * 1.4f
        val a = SidInstrumentView.ATTACK_MS[ins.attack].toFloat()
        val d = 3f * SidInstrumentView.ATTACK_MS[ins.decay]
        val rel = 3f * SidInstrumentView.ATTACK_MS[ins.release]
        val hold = 400f
        val level = ins.sustain / 15f
        ink.label(c, ink.fit("Envelope: ${ins.attackDecay.let { hex2(it) }}${hex2(ins.sustainRelease)} · sustain ${ins.sustain * 100 / 15}% · OFF releases", r.width() - ink.unit),
            r.left + ink.unit * 0.5f, r.top, titleH, ink.palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 0.8f, r.top + titleH, r.right - ink.unit * 0.8f, r.bottom - ink.unit * 1.4f)
        if (g.height() <= 0f) return
        val wa = sqrt(a); val wd = sqrt(d); val wh = sqrt(hold) * 1.2f; val wr = sqrt(rel)
        val total = wa + wd + wh + wr
        val x0 = g.left
        val xA = x0 + g.width() * wa / total
        val xD = xA + g.width() * wd / total
        val xH = xD + g.width() * wh / total
        fun yOf(l: Float) = g.bottom - g.height() * l
        // sustain and segment guides
        ink.line(c, g.left, yOf(level), g.right, yOf(level), ink.palette.RULE, ink.dp(1f))
        for (x in listOf(xA, xD, xH)) ink.line(c, x, g.top, x, g.bottom, ink.palette.RULE, ink.dp(1f))
        var n = 0
        fun add(x: Float, l: Float) { if (n < xs.size) { xs[n] = x; ys[n] = yOf(l); n++ } }
        add(x0, 0f); add(xA, 1f)
        // decay and release fall exponentially, as the SID's envelope counter slows down
        val steps = 12
        for (i in 1..steps) { val t = i.toFloat() / steps; add(xA + (xD - xA) * t, level + (1f - level) * exp(-4f * t)) }
        add(xH, level)
        for (i in 1..steps) { val t = i.toFloat() / steps; add(xH + (g.right - xH) * t, level * exp(-4f * t) * (1f - t * 0.02f)) }
        ink.rect(c, x0, g.bottom - ink.dp(1f), g.right, g.bottom, ink.palette.RULE)
        c.polyline(xs, ys, n, col, ink.dp(2.5f))
        val ls = ink.unit * 0.7f
        val ly = g.bottom + ink.dp(2f)
        val lh = ink.unit * 1.2f
        ink.label(c, "A ${SidInstrumentView.msText(a.toInt())}", (x0 + xA) / 2, ly, lh, ink.palette.DIM, size = ls, align = Align.CENTER)
        ink.label(c, "D ${SidInstrumentView.msText(d.toInt())}", (xA + xD) / 2, ly, lh, ink.palette.DIM, size = ls, align = Align.CENTER)
        ink.label(c, "held · S ${NumberText.hex(ins.sustain)}", (xD + xH) / 2, ly, lh, ink.palette.DIM, size = ls, align = Align.CENTER)
        ink.label(c, "R ${SidInstrumentView.msText(rel.toInt())}", (xH + g.right) / 2, ly, lh, ink.palette.DIM, size = ls, align = Align.CENTER)
        ink.label(c, "OFF", xH + ink.dp(3f), g.top, ink.unit, ink.palette.WRITE, size = ls)
    }

    // ---- programs -------------------------------------------------------------------------------------

    /**
     * The program's rows: number, its columns (the cursor's outlined), what it does, problems in red; a rail marks the
     * rows a jump loops over. Pulse and filter programs also show the width / cutoff they play over 2 seconds.
     */
    private fun drawProgram(c: Surface, box: Rect, ins: SidInstrument, table: Int, col: Int, focused: Boolean) {
        val s = song
        var r = box
        val small = ink.unit * 0.85f
        if (table == SidTables.FILTER) {
            // The chip has one filter: which voice holds it where the song is heard.
            val order = if (v.playback.playing) v.playback.order else session.selection.order
            val prow = if (v.playback.playing) v.playback.row else session.selection.row
            val line = SidFilterOwner.state(s, order, prow).lines.firstOrNull() ?: ""
            ink.label(c, ink.fit(line, r.width(), small), r.left, r.top, ink.unit * 1.2f, ink.palette.PLAYHEAD, size = small)
            r = Rect(r.left, r.top + ink.unit * 1.3f, r.right, r.bottom)
        }
        val p = ins.program(table)
        val graphW = if (table == SidTables.WAVE || r.width() < ink.unit * 30f) 0f else minOf(r.width() * 0.34f, ink.unit * 16f)
        val list = Rect(r.left, r.top, r.right - graphW - (if (graphW > 0f) ink.unit * 0.5f else 0f), r.bottom)
        ink.round(c, list, ink.dp(4f), ink.palette.LANE)
        val titleH = ink.unit * 1.4f
        val step = st.envStep
        val fields = SidInstrumentView.fields(s, st)
        val title = when {
            step < p.rows.size -> "${SidTables.NAMES[table]} program row ${step + 1} of ${p.rows.size}" +
                (fields.getOrNull(st.programField)?.let { " · ${it.label}" } ?: "")
            p.rows.isEmpty() -> "No ${SidTables.NAMES[table].lowercase()} program: ${ctl.k(org.thortracker.editor.Action.EDIT)} adds its first row"
            else -> "${p.rows.size} rows; ${ctl.k(org.thortracker.editor.Action.EDIT)} adds row ${p.rows.size + 1}"
        }
        ink.label(c, ink.fit(title, list.width() - ink.unit), list.left + ink.unit * 0.5f, list.top, titleH, if (focused) ink.palette.DIM else ink.palette.FAINT, size = small)
        listRowH = ink.unit * 1.3f
        listRect = Rect(list.left, list.top + titleH, list.right, list.bottom - ink.unit * 0.2f)
        val fit = maxOf(1, (listRect.height() / listRowH).toInt())
        val lines = p.rows.size + 1
        listScroll = listScroll.coerceIn(maxOf(0, step - fit + 1), step).coerceAtMost(maxOf(0, lines - fit))
        val numX = list.left + ink.unit * 1.1f
        val firstX = list.left + ink.unit * 2.6f
        val gap = ink.unit * 0.55f
        // loop rails: the rows from a jump's target to the jump
        for ((i, row) in p.rows.withIndex()) {
            if (!row.isJump || row.right == 0) continue
            val from = row.right - 1
            val y0 = listRect.top + (from - listScroll) * listRowH
            val y1 = listRect.top + (i - listScroll + 1) * listRowH
            val top = maxOf(listRect.top, y0)
            val bottom = minOf(listRect.bottom, y1)
            if (bottom > top) ink.rect(c, list.left + ink.dp(3f), top + ink.dp(2f), list.left + ink.dp(6f), bottom - ink.dp(2f), ink.palette.LISTEN)
        }
        for (k in 0 until minOf(fit, lines - listScroll)) {
            val i = listScroll + k
            val y = listRect.top + k * listRowH
            val on = i == step
            if (on) ink.rect(c, list.left, y, list.right, y + listRowH, if (focused) ink.palette.CURSOR_FILL else Palette.alpha(ink.palette.CURSOR_FILL, 120))
            if (i == p.rows.size) {
                ink.mono(c, "+", numX, y, listRowH, ink.palette.FAINT, size = small, align = Align.RIGHT)
                ink.label(c, if (p.rows.isEmpty()) "add the first row" else "add a row", firstX, y, listRowH, ink.palette.FAINT, size = small)
                if (on && focused) ink.outline(c, Rect(firstX - gap / 2, y + 1, list.right - ink.unit * 0.3f, y + listRowH - 1), cursorColor(), ink.dp(2f), ink.dp(2f))
                continue
            }
            val row = p.rows[i]
            ink.mono(c, (i + 1).toString(), numX, y, listRowH, if (on) ink.palette.TEXT else ink.palette.FAINT, on, small, Align.RIGHT)
            val cols = SidPrograms.fields(table, row, p.rows.size)
            var x = firstX
            for ((f, field) in cols.withIndex()) {
                val bold = f == 0
                val w = ink.monoWidth(field.text, small, bold)
                val color = when {
                    field.toggle && field.text == "·" -> ink.palette.FAINT
                    f == 0 -> col
                    else -> if (on) ink.palette.TEXT else ink.palette.DIM
                }
                ink.mono(c, field.text, x, y, listRowH, color, bold, small)
                if (on && focused && f == st.programField) ink.outline(c, Rect(x - gap * 0.4f, y + 1, x + w + gap * 0.4f, y + listRowH - 1), cursorColor(), ink.dp(2f), ink.dp(2f))
                x += w + gap
            }
            val problem = SidTables.rowProblem(table, p.rows, i)
            val text = problem ?: SidTables.describe(table, row)
            val room = list.right - ink.unit * 0.4f - x - gap
            if (room > ink.unit * 3f) {
                ink.label(c, ink.fit(text, room, ink.unit * 0.75f), list.right - ink.unit * 0.4f, y, listRowH,
                    if (problem != null) ink.palette.ERROR else ink.palette.FAINT, size = ink.unit * 0.75f, align = Align.RIGHT)
            } else if (problem != null) {
                ink.rect(c, list.right - ink.dp(5f), y + ink.dp(3f), list.right - ink.dp(2f), y + listRowH - ink.dp(3f), ink.palette.ERROR)
            }
        }
        if (graphW > 0f) drawTrace(c, Rect(list.right + ink.unit * 0.5f, r.top, r.right, r.bottom), p, table, col)
    }

    private val traceXs = FloatArray(TRACE_TICKS)
    private val traceYs = FloatArray(TRACE_TICKS)

    /** Pulse width or cutoff over the program's first [TRACE_TICKS] ticks (2 s at 50 Hz). */
    private fun drawTrace(c: Surface, r: Rect, p: SidProgram, table: Int, col: Int) {
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val titleH = ink.unit * 1.4f
        val pulse = table == SidTables.PULSE
        ink.label(c, ink.fit(if (pulse) "Pulse width, 2 s" else "Cutoff, 2 s", r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, ink.palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 0.5f, r.top + titleH, r.right - ink.unit * 0.5f, r.bottom - ink.unit * 0.5f)
        if (g.height() <= ink.unit || p.rows.isEmpty()) return
        val max = if (pulse) 0xFFF else 0xFF
        val values = SidPrograms.trace(table, p.rows, TRACE_TICKS, if (pulse) 0x800 else 0x80)
        if (pulse) ink.line(c, g.left, g.top + g.height() / 2, g.right, g.top + g.height() / 2, ink.palette.RULE, ink.dp(1f))
        for (t in 0 until TRACE_TICKS) {
            traceXs[t] = g.left + g.width() * t / (TRACE_TICKS - 1)
            traceYs[t] = g.bottom - g.height() * values[t] / max
        }
        c.polyline(traceXs, traceYs, TRACE_TICKS, col, ink.dp(2f))
    }

    /** Vibrato drawn as the pitch over the first second, and the hard restart before the note. */
    private fun drawVibrato(c: Surface, r: Rect, ins: SidInstrument, col: Int) {
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val titleH = ink.unit * 1.4f
        val vib = ins.vibrato
        val hr = song.sid
        val restart = when (ins.restart) {
            SidRestart.HARD -> "${ins.gateTimer} tick${if (ins.gateTimer == 1) "" else "s"} before each note: gate off, envelope ${hex2(hr.hardRestartAttackDecay)}${hex2(hr.hardRestartSustainRelease)} (Song view)"
            SidRestart.GATE_OFF -> "${ins.gateTimer} tick${if (ins.gateTimer == 1) "" else "s"} before each note: gate off"
            SidRestart.LEGATO -> "notes glide under a running envelope"
        }
        ink.label(c, ink.fit(restart, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, ink.palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 0.8f, r.top + titleH + ink.unit * 0.3f, r.right - ink.unit * 0.8f, r.bottom - ink.unit * 0.5f)
        if (g.height() <= ink.unit) return
        val mid = g.top + g.height() / 2
        ink.line(c, g.left, mid, g.right, mid, ink.palette.RULE, ink.dp(1f))
        if (!vib.active) {
            ink.label(c, "No vibrato", g.centerX(), g.top, g.height(), ink.palette.FAINT, size = ink.unit * 0.85f, align = Align.CENTER)
            return
        }
        // A triangle wobble after the delay: it turns every speed ticks (one second shown).
        val ticks = 50
        val period = 2f * maxOf(1, vib.speed)
        for (t in 0 until ticks) {
            val wobble = if (t < vib.delay) 0f else {
                val f = ((t - vib.delay) / period + 0.25f) % 1f
                if (f < 0.5f) 4f * f - 1f else 3f - 4f * f
            }
            traceXs[t] = g.left + g.width() * t / (ticks - 1)
            traceYs[t] = mid - (g.height() / 2 * 0.9f) * wobble
        }
        c.polyline(traceXs, traceYs, ticks, col, ink.dp(2f))
        val dx = g.left + g.width() * minOf(vib.delay, ticks - 1) / (ticks - 1)
        ink.line(c, dx, g.top, dx, g.bottom, ink.palette.LISTEN, ink.dp(1f))
        ink.label(c, "delay", dx + ink.dp(3f), g.top, ink.unit, ink.palette.LISTEN, size = ink.unit * 0.7f)
        ink.label(c, "1 s", g.right, g.bottom - ink.unit, ink.unit, ink.palette.FAINT, size = ink.unit * 0.7f, align = Align.RIGHT)
    }

    // ---- raw tables -----------------------------------------------------------------------------------

    private var placementSong: Song? = null
    private val placements = arrayOfNulls<SidTables.Placement>(3)
    private var speedRows: List<Int> = emptyList()

    private fun refresh() {
        val s = song
        if (s === placementSong) return
        placementSong = s
        for (t in 0..2) placements[t] = SidTables.placement(s.sid, t)
        speedRows = SidSpeeds.of(s)
    }

    private fun placement(table: Int): SidTables.Placement { refresh(); return placements[table]!! }
    private fun speeds(): List<Int> { refresh(); return speedRows }

    /**
     * Table [SidTables] rows 01-FF as the player reads them: left and right bytes in GoatTracker's editor form (jumps
     * to table rows), the owning sound and its program row, and the meaning. Free rows are dim.
     */
    private fun drawRaw(c: Surface, box: Rect, focused: Boolean) {
        val s = song
        ink.round(c, box, ink.dp(4f), ink.palette.LANE)
        val table = st.rawTable
        val small = ink.unit * 0.85f
        val titleH = ink.unit * 1.4f
        val note = if (table == SidTables.SPEED) "vibratos and effects 1-4, E make these rows" else "programs make these rows; ${ctl.k(org.thortracker.editor.Action.EDIT)} edits the row's program"
        ink.label(c, ink.fit("${SidTables.NAMES[table]} table · $note", box.width() - ink.unit), box.left + ink.unit * 0.5f, box.top, titleH, ink.palette.FAINT, size = ink.unit * 0.8f)
        listRowH = ink.unit * 1.25f
        val list = Rect(box.left, box.top + titleH, box.right, box.bottom - ink.unit * 0.2f)
        listRect = list
        val fit = maxOf(1, (list.height() / listRowH).toInt())
        val step = st.envStep
        rawScroll = rawScroll.coerceIn(maxOf(0, step - fit + 1), step).coerceAtMost(maxOf(0, SidTables.ROWS - fit))
        val cw = ink.monoWidth("0", small)
        for (k in 0 until minOf(fit, SidTables.ROWS - rawScroll)) {
            val i = rawScroll + k
            val tableRow = i + 1
            val y = list.top + k * listRowH
            val on = i == step
            if (on) ink.rect(c, list.left, y, list.right, y + listRowH, if (focused) ink.palette.CURSOR_FILL else Palette.alpha(ink.palette.CURSOR_FILL, 120))
            var x = list.left + ink.unit * 0.5f
            ink.mono(c, hex2(tableRow), x, y, listRowH, if (on) ink.palette.TEXT else ink.palette.FAINT, on, small)
            x += cw * 3.2f
            if (table == SidTables.SPEED) {
                val value = speeds().getOrNull(i)
                if (value == null) { ink.mono(c, "·· ··", x, y, listRowH, ink.palette.FAINT, size = small); continue }
                ink.mono(c, "${hex2(value shr 8)} ${hex2(value and 0xFF)}", x, y, listRowH, ink.palette.TEXT, size = small)
                ink.label(c, ink.fit(speedText(value), list.right - x - cw * 7f, ink.unit * 0.75f), x + cw * 6.5f, y, listRowH, ink.palette.DIM, size = ink.unit * 0.75f)
                continue
            }
            val pl = placement(table)
            val owner = pl.owner[tableRow]
            if (owner == 0) { ink.mono(c, "·· ··", x, y, listRowH, ink.palette.FAINT, size = small); continue }
            val prog = s.sid.instruments[owner - 1].program(table)
            val index = pl.index[tableRow]
            val row = prog.rows.getOrNull(index) ?: SidRow.jump(0) // the stop row the lowering adds
            val right = if (row.isJump && row.right > 0) pl.start[owner] + row.right - 1 else row.right
            ink.mono(c, "${hex2(row.left)} ${hex2(right)}", x, y, listRowH, ink.palette.TEXT, size = small)
            x += cw * 6.5f
            val who = "${hex2(owner)}.${index + 1}"
            ink.mono(c, who, x, y, listRowH, ink.palette.channel(0), size = small)
            x += cw * (who.length + 1.5f)
            val meaning = if (index >= prog.rows.size) "Stop (end of program)" else SidTables.describe(table, row).let {
                if (row.isJump && row.right > 0) "Go to table row ${hex2(right)}" else it
            }
            ink.label(c, ink.fit("${s.sid.instruments[owner - 1].name}: $meaning", list.right - x - ink.unit * 0.4f, ink.unit * 0.75f), x, y, listRowH, ink.palette.DIM, size = ink.unit * 0.75f)
        }
    }

    private fun speedText(value: Int): String {
        val l = value shr 8
        val r = value and 0xFF
        return "portamento ${NumberText.hex(value, 3)}/tick · or vibrato speed ${l and 0x7F}${if (l >= 0x80) " (note-independent)" else ""}, depth ${hex2(r)}" +
            " · or funktempo $l/$r"
    }

    // ---- usage ----------------------------------------------------------------------------------------

    private fun usage(slot: Int): Int {
        val s = song
        if (s !== usageSong) {
            usage.clear()
            val counted = HashSet<Int>()
            for (pat in s.orders) {
                if (!counted.add(pat)) continue
                for (ch in 0 until s.channelCount()) for (cell in s.patterns[pat].tracks[ch]) {
                    if (cell.instrument > 0) usage[cell.instrument] = (usage[cell.instrument] ?: 0) + 1
                }
            }
            usageSong = s
        }
        return usage[slot] ?: 0
    }

    private fun usageText(slot: Int): String {
        val n = usage(slot)
        return if (n == 0) "not used in the arrangement yet" else "used on $n row${if (n == 1) "" else "s"}"
    }

    // ---- touch ----------------------------------------------------------------------------------------

    override fun down(x: Float, y: Float): Boolean {
        tabs.firstOrNull { it.first.contains(x, y) }?.let { (_, i) -> SidInstrumentView.showPage(ctl, i); return true }
        tableTabs.firstOrNull { it.first.contains(x, y) }?.let { (_, i) ->
            st.rawTable = i
            st.instField = SidInstrumentView.rows(st).indexOf(Row.TABLE)
            return true
        }
        if (slotRect.contains(x, y)) {
            st.instrument = (((y - slotTop) / slotH).toInt() + slotScroll + 1).coerceIn(1, SidSystem.SLOTS)
            st.instField = 0
            SidInstrumentView.publish(ctl)
            return true
        }
        if (listRect.contains(x, y) && listRowH > 0f) {
            val raw = SidInstrumentView.page(st) == Page.TABLES
            val i = (if (raw) rawScroll else listScroll) + ((y - listRect.top) / listRowH).toInt()
            st.instField = SidInstrumentView.rows(st).indexOf(if (raw) Row.RAW else Row.STEPS)
            st.envStep = i
            st.programField = 0
            return true
        }
        return false
    }

    private companion object {
        const val TRACE_TICKS = 100
    }
}
