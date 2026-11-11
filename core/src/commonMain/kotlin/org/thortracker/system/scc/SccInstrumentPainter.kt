package org.thortracker.system.scc

import kotlin.math.roundToInt
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.editor.channelCount
import org.thortracker.model.Song
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.scc.SccInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * SCC instrument editor. Left: the 64 slots of the bank with how often each is used (empty slots say so). Right: name
 * (or, on an empty slot, how to make a sound), preset, the channel Listen plays on (with the SCC's shared wave RAM
 * noted on SCC 4 and 5), restart, page tabs, the shown envelope's length / loop / release / pitch mode, what the player
 * does with it, on the Wave page the waves the envelope plays as thumbnails, and its steps as bars ([EnvelopePlot]).
 * An empty slot shows what its first edit starts from.
 */
internal class SccInstrumentPainter(v: PaneView) : Painter(v) {
    private val tabs = ArrayList<Pair<Rect, Int>>()
    private val rowRects = ArrayList<Pair<Rect, Row>>()
    private var slotTop = 0f
    private var slotH = 0f
    private var slotRect = Rect()
    private var slotScroll = 0
    /** Right edge of the settings column, so long values are cut there. */
    private var rightEdge = 0f
    private val plot = EnvelopePlot(ink)

    private var usageSong: Song? = null
    private val usage = HashMap<Int, Int>()
    internal fun drawSidebar(c: Surface, area: Rect, col: Int, focused: Boolean): Rect {
        val s = song
        val sound = SccInstrumentView.sound(s, st)
        val row = if (focused) Row.SLOT else Row.NAME
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val chip = s.scc.variant
        val top = header(c, area, "Sound ${hex2(st.instrument)}",
            "${chip.label} (${chip.chip})${if (sound == null) " · empty slot" else ""}$locked  ${usageText(st.instrument)}")
        // left column: the bank, scrolled to keep the selected slot in view
        val leftW = minOf(area.width() * 0.3f, ink.unit * 14f)
        val left = Rect(area.left, top, area.left + leftW, area.bottom)
        ink.rect(c, left, ink.palette.LANE)
        slotTop = top + ink.unit * 0.2f
        slotH = ink.unit * 1.45f
        val visible = maxOf(1, ((area.bottom - slotTop) / slotH).toInt())
        slotScroll = slotScroll.coerceIn(maxOf(0, st.instrument - visible), minOf(st.instrument - 1, SccSound.SLOTS - visible).coerceAtLeast(0))
        slotRect = Rect(left.left, slotTop, left.right, slotTop + slotH * visible)
        for (k in 0 until minOf(visible, SccSound.SLOTS - slotScroll)) {
            val i = slotScroll + k + 1
            val y = slotTop + k * slotH
            val sel = i == st.instrument
            if (sel) {
                ink.rect(c, left.left, y, left.right, y + slotH, ink.palette.CURSOR_FILL)
                ink.rect(c, left.left, y, left.left + ink.dp(3f), y + slotH, col)
            }
            val sz = minOf(ink.unit, slotH * 0.72f)
            ink.mono(c, hex2(i), left.left + ink.unit * 0.6f, y, slotH, if (sel) col else ink.palette.FAINT, sel, sz)
            val slotSound = s.scc.instrument(i)
            val used = usage(i)
            val nameColor = when {
                slotSound == null -> ink.palette.FAINT
                sel -> ink.palette.TEXT
                used > 0 -> ink.palette.DIM
                else -> ink.palette.FAINT
            }
            val text = slotSound?.name?.ifEmpty { "(unnamed)" } ?: "· empty"
            ink.label(c, ink.fit(text, leftW - ink.unit * 4.6f, sz), left.left + ink.unit * 2.4f, y, slotH, nameColor, size = sz)
            if (used > 0) ink.mono(c, used.toString(), left.right - ink.unit * 0.5f, y, slotH, ink.palette.FAINT, size = sz * 0.8f, align = Align.RIGHT)
        }
        if (row == Row.SLOT) {
            val y = slotTop + (st.instrument - 1 - slotScroll) * slotH
            ink.outline(c, Rect(left.left + 2, y + 1, left.right - 2, y + slotH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }
        return left
    }

    internal fun slotAt(x: Float, y: Float): Int =
        if (slotRect.contains(x, y)) (((y - slotTop) / slotH).toInt() + slotScroll + 1).coerceIn(1, SccSound.SLOTS) else 0


    override fun draw(c: Surface, area: Rect) {
        val s = song
        val sound = SccInstrumentView.sound(s, st)
        val ins = SccInstrumentView.shown(s, st)
        val kind = SccInstrumentView.kind(st)
        val env = kind.of(ins)
        val channels = s.system.channels
        val listen = channels.getOrNull(st.listenChannel)
        val col = ink.palette.channel(st.listenChannel)
        val row = SccInstrumentView.row(st)
        rowRects.clear()
        val left = drawSidebar(c, area, col, row == Row.SLOT)
        val top = left.top


        // right column: settings rows (shrinking a little on short displays so the steps keep their room)
        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val rw = area.right - pad - rx
        rightEdge = rx + rw
        val rows = SccInstrumentView.rows(st).filter { it != Row.SLOT && it != Row.STEPS }
        val reserve = ink.unit * (if (kind == SccEnvelopeKind.WAVE) 10f else 7f)
        val rowH = ((area.bottom - top - reserve - ink.unit * 2.2f) / (rows.size + 0.6f)).coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.5f
        val empty = sound == null
        var y = top + ink.unit * 0.3f
        for (r in rows) {
            val sel = r == row
            val h = if (r == Row.PAGE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, ink.palette.CURSOR_FILL)
            when (r) {
                Row.NAME -> if (empty) {
                    setting(c, "Empty slot", "${ctl.k(Action.EDIT)} on Slot makes a sound; ${ctl.keys.held(Action.EDIT, ctl.lr())} on Preset picks one",
                        rx, vx, y, h, sel, true)
                } else {
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
                Row.PRESET -> {
                    val p = SccInstrumentView.presetIndex(sound)
                    setting(c, "Preset", when {
                        empty -> "none: ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"
                        p >= 0 -> "${SccPresets.ALL[p].name} (${p + 1}/${SccPresets.ALL.size})"
                        else -> "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"
                    }, rx, vx, y, h, sel, p < 0)
                }
                Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM)
                    val name = listen?.name ?: ""
                    ink.label(c, name, vx, y, h, col, true)
                    SccInstrumentView.channelNote(s, st.listenChannel)?.let { note ->
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rx + rw - nx, ink.unit * 0.85f), nx, y, h, ink.palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                Row.RESTART -> setting(c, "Restart", if (ins.restart) "on: each note starts its wave at sample 0" else "off: the wave runs on between notes",
                    rx, vx, y, h, sel, !ins.restart)
                Row.PAGE -> drawTabs(c, ins, Rect(rx, y, rx + rw, y + h), col)
                Row.LENGTH -> setting(c, "Length", when {
                    env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${ticks(env.values.size)})"
                    kind == SccEnvelopeKind.VOLUME -> "empty: volume 15 throughout"
                    else -> "empty: not used"
                }, rx, vx, y, h, sel, env.values.isEmpty())
                Row.LOOP -> setting(c, "Loop", if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}", rx, vx, y, h, sel, env.loop < 0)
                Row.RELEASE -> setting(c, "Release", when {
                    env.release < 0 -> "none: OFF stops the note"
                    env.loop < 0 -> "at step ${env.release}: holds step ${env.release - 1} until OFF, then plays on from here"
                    else -> "at step ${env.release}: after OFF the steps from here play out"
                }, rx, vx, y, h, sel, env.release < 0)
                Row.MODE -> setting(c, "Mode", if (ins.pitchRelative) "relative: offsets add up every tick" else "absolute: offset from the note",
                    rx, vx, y, h, sel, false)
                Row.SLOT, Row.STEPS -> Unit
            }
            if (r != Row.PAGE) rowRects += Rect(rx, y, rx + rw, y + h) to r
            if (sel) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }
        val small = ink.unit * 0.8f
        ink.label(c, ink.fit(SccInstrumentView.envelopeSummary(kind), rw, small), rx, y, rowH * 0.85f, ink.palette.FAINT, size = small)
        y += rowH * 0.85f

        // the waves a wave envelope plays, in order of first use, the one under the cursor marked
        if (kind == SccEnvelopeKind.WAVE) {
            val stripH = ink.unit * 2.6f
            if (area.bottom - y > stripH + ink.unit * 5f) {
                drawWaveStrip(c, Rect(rx, y + ink.unit * 0.2f, rx + rw, y + ink.unit * 0.2f + stripH), env, col)
                y += stripH + ink.unit * 0.3f
            }
        }

        // steps
        val plotTop = y + ink.unit * 0.3f
        if (area.bottom - plotTop > ink.unit * 4f) drawSteps(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), kind, env, col, row == Row.STEPS)
        else plot.hide()
    }

    private fun setting(c: Surface, label: String, value: String, x: Float, vx: Float, y: Float, h: Float, sel: Boolean, off: Boolean) {
        ink.label(c, label, x, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM)
        ink.label(c, ink.fit(value, maxOf(ink.unit, rightEdge - vx), ink.unit, sel), vx, y, h,
            if (sel) (if (off) ink.palette.DIM else ink.palette.TEXT) else (if (off) ink.palette.FAINT else ink.palette.DIM), sel)
    }

    /** Player ticks (60 per second at multispeed 1) as a duration. */
    private fun ticks(n: Int) = "${(n * 1000.0 / (60.0 * song.scc.multispeed)).roundToInt()} ms"

    private fun cursorColor() = when {
        ctl.editHeld -> ink.palette.WRITE
        ctl.auditionHeld -> ink.palette.LISTEN
        v.controllerFocused -> ink.palette.FOCUS
        else -> ink.palette.DIM
    }

    /** Page tabs: each envelope with its length. */
    private fun drawTabs(c: Surface, ins: SccInstrument, r: Rect, col: Int) {
        tabs.clear()
        val kinds = SccEnvelopeKind.entries
        val w = r.width() / kinds.size
        for (k in kinds) {
            val t = Rect(r.left + k.ordinal * w, r.top, r.left + (k.ordinal + 1) * w, r.bottom)
            val on = k.ordinal == st.envelope
            if (on) ink.rect(c, t, Palette.mix(ink.palette.BG, col, 0.18f))
            ink.rect(c, t.left, t.bottom - ink.dp(if (on) 3f else 1f), t.right, t.bottom, if (on) col else ink.palette.RULE)
            val size = k.of(ins).values.size
            val label = if (size == 0) k.label else "${k.label} ($size)"
            ink.label(c, ink.fit(label, w - ink.unit * 0.4f), t.centerX(), t.top, t.height(), if (on) col else ink.palette.DIM, on, align = Align.CENTER)
            tabs += t to k.ordinal
        }
    }

    /** Thumbnails of the waves [env] plays (each once, in order of first use); the cursor step's wave outlined. */
    private fun drawWaveStrip(c: Surface, r: Rect, env: NesEnvelope, col: Int) {
        val numbers = env.values.distinct()
        if (numbers.isEmpty()) return
        val current = env.values.getOrNull(st.envStep) ?: env.values.last()
        val shared = SccWaveView.sharedWaves(song)
        val tw = minOf(ink.unit * 4f, r.width() / numbers.size)
        val shown = maxOf(1, (r.width() / tw).toInt())
        // Keep the cursor step's wave in view when more waves are used than fit.
        val first = (numbers.indexOf(current) - shown + 1).coerceAtLeast(0)
        for (k in 0 until minOf(shown, numbers.size - first)) {
            val n = numbers[first + k]
            val t = Rect(r.left + k * tw + 2, r.top, r.left + (k + 1) * tw - 2, r.bottom)
            val on = n == current
            ink.round(c, t, ink.dp(3f), if (on) ink.palette.CURSOR_FILL else ink.palette.LANE)
            val samples = song.scc.wave(n)?.samples.orEmpty()
            drawSccWaveThumb(ink, c, Rect(t.left + 2, t.top + ink.unit * 0.85f, t.right - 2, t.bottom - 2), samples, if (on) col else ink.palette.FAINT)
            ink.mono(c, hex2(n), t.left + ink.dp(3f), t.top, ink.unit * 0.85f, if (on) ink.palette.TEXT else ink.palette.DIM, size = ink.unit * 0.65f)
            if (n in shared) ink.mono(c, "4/5", t.right - ink.dp(3f), t.top, ink.unit * 0.85f, ink.palette.PLAYHEAD, size = ink.unit * 0.55f, align = Align.RIGHT)
            if (on) ink.outline(c, t, col, ink.dp(2f), ink.dp(3f))
        }
    }

    private fun drawSteps(c: Surface, r: Rect, kind: SccEnvelopeKind, env: NesEnvelope, col: Int, focused: Boolean) {
        val text = { value: Int -> SccInstrumentView.valueText(kind, value) }
        val titleText = if (kind == SccEnvelopeKind.WAVE) { value: Int -> SccRules.waveLabel(song, value) } else text
        val title = EnvelopePlot.title(kind.label, env.values, st.envStep, titleText, ctl.keys.label(Action.EDIT), ctl.keys.label(Action.UP))
        plot.draw(c, r, title, kind.range, env, st.envStep, text, false, col, focused, cursorColor(), ctl.editHeld)
    }

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

    // ---- touch: slots, tabs, rows, and drawing on the steps --------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = SccEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        tabs.firstOrNull { it.first.contains(x, y) }?.let { (_, k) ->
            SccInstrumentView.showPage(ctl, k)
            return true
        }
        if (slotRect.contains(x, y)) {
            st.instrument = (((y - slotTop) / slotH).toInt() + slotScroll + 1).coerceIn(1, SccSound.SLOTS)
            st.instField = 0
            SccInstrumentView.publish(ctl)
            return true
        }
        rowRects.firstOrNull { it.first.contains(x, y) }?.let { (_, r) ->
            st.instField = SccInstrumentView.rows(st).indexOf(r).coerceAtLeast(0)
            return true
        }
        if (plot.contains(x, y)) {
            st.instField = SccInstrumentView.rows(st).indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = SccInstrumentView.kind(st)
            drawing = session.beginGesture("Draw ${drawKind.label.lowercase()}")
            drawAt(x, y)
            return true
        }
        return false
    }

    /** Sets the step under [x] to the value at [y]; past the end it appends one step at a time. */
    private fun drawAt(x: Float, y: Float) {
        val ins = song.sccInstrument(drawSlot) ?: defaultSccInstrument(drawSlot)
        val e = drawKind.of(ins)
        val i = plot.stepAt(x, SccInstrumentView.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        session.editSccEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { SccEnvelopes.withValue(drawKind, it, i, value) }
    }

    override fun move(x: Float, y: Float) {
        val g = drawing ?: return
        if (!session.isGestureOpen(g)) { drawing = null; return }
        drawAt(x, y)
    }

    override fun up(x: Float, y: Float) = finish()
    override fun cancel() = finish()

    private fun finish() {
        drawing?.let { session.endGesture(it) }
        drawing = null
    }
}
