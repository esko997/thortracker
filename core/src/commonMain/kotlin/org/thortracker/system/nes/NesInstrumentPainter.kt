package org.thortracker.system.nes

import kotlin.math.abs
import kotlin.math.roundToInt
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.editor.channelCount
import org.thortracker.model.Song
import org.thortracker.system.nes.NesInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * NES instrument editor. Left: the 64 slots of the shared bank with how often each is used. Right: name, the channel
 * Listen plays on, envelope tabs, the shown envelope's length / loop / release / pitch mode, what each channel does
 * with it, and its steps as bars with the loop region and release point.
 */
internal class NesInstrumentPainter(v: PaneView) : Painter(v) {
    private val tabs = ArrayList<Pair<Rect, Int>>()
    private var slotTop = 0f
    private var slotH = 0f
    private var slotRect = Rect()
    private var slotScroll = 0
    private var stepScroll = 0
    private var graph = Rect()
    private var cellW = 0f
    private var lo = 0
    private var hi = 15

    private var usageSong: Song? = null
    private val usage = HashMap<Int, Int>()

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = NesInstrumentView.sound(s, st) ?: return
        val kind = NesInstrumentView.kind(st)
        val env = kind.of(ins)
        val channels = s.system.channels
        val listen = channels.getOrNull(st.listenChannel)
        val col = Palette.channel(st.listenChannel)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "Instrument bank$locked  ${usageText(st.instrument)}")
        val row = NesInstrumentView.row(st)

        // left column: the bank, scrolled to keep the selected slot in view
        val leftW = minOf(area.width() * 0.3f, ink.unit * 14f)
        val left = Rect(area.left, top, area.left + leftW, area.bottom)
        ink.rect(c, left, Palette.LANE)
        slotTop = top + ink.unit * 0.2f
        slotH = ink.unit * 1.45f
        val visible = maxOf(1, ((area.bottom - slotTop) / slotH).toInt())
        slotScroll = slotScroll.coerceIn(maxOf(0, st.instrument - visible), minOf(st.instrument - 1, NesSystem.SLOTS - visible).coerceAtLeast(0))
        slotRect = Rect(left.left, slotTop, left.right, slotTop + slotH * visible)
        val bank = s.nes.instruments
        for (k in 0 until minOf(visible, NesSystem.SLOTS - slotScroll)) {
            val i = slotScroll + k + 1
            val y = slotTop + k * slotH
            val sel = i == st.instrument
            if (sel) {
                ink.rect(c, left.left, y, left.right, y + slotH, Palette.CURSOR_FILL)
                ink.rect(c, left.left, y, left.left + ink.dp(3f), y + slotH, col)
            }
            val sz = minOf(ink.unit, slotH * 0.72f)
            ink.mono(c, hex2(i), left.left + ink.unit * 0.6f, y, slotH, if (sel) col else Palette.FAINT, sel, sz)
            val used = usage(i)
            val nameColor = if (sel) Palette.TEXT else if (used > 0) Palette.DIM else Palette.FAINT
            ink.label(c, ink.fit(bank[i - 1].name, leftW - ink.unit * 4.6f, sz), left.left + ink.unit * 2.4f, y, slotH, nameColor, size = sz)
            if (used > 0) ink.mono(c, used.toString(), left.right - ink.unit * 0.5f, y, slotH, Palette.FAINT, size = sz * 0.8f, align = Align.RIGHT)
        }
        if (row == Row.SLOT) {
            val y = slotTop + (st.instrument - 1 - slotScroll) * slotH
            ink.outline(c, Rect(left.left + 2, y + 1, left.right - 2, y + slotH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }

        // right column: settings rows
        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val rw = area.right - pad - rx
        val rowH = ink.unit * 1.5f
        val vx = rx + ink.unit * 6.5f
        var y = top + ink.unit * 0.3f
        for (r in NesInstrumentView.rows(st)) {
            if (r == Row.SLOT || r == Row.STEPS) continue
            val sel = r == row
            val h = if (r == Row.ENVELOPE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, Palette.CURSOR_FILL)
            when (r) {
                Row.NAME -> {
                    ink.label(c, "Name", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    ink.label(c, ins.name.ifEmpty { "(unnamed)" }, vx, y, h, if (ins.name.isEmpty()) Palette.FAINT else Palette.TEXT, true)
                    if (sel) {
                        val before = ins.name.take(st.textPos).padEnd(st.textPos, ' ')
                        val cx = vx + ink.labelWidth(before, ink.unit, true)
                        val ch = ins.name.getOrNull(st.textPos)?.toString() ?: " "
                        val w = maxOf(ink.labelWidth(ch, ink.unit, true), ink.unit * 0.5f)
                        ink.outline(c, Rect(cx - 1, y + 2, cx + w + 1, y + h - 2), cursorColor(), ink.dp(2f), ink.dp(2f))
                    }
                }
                Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    val name = listen?.name ?: ""
                    ink.label(c, name, vx, y, h, col, true)
                    val note = listen?.let { NesInstrumentView.channelNote(kind, it.kind) }
                    if (note != null) {
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rx + rw - nx, ink.unit * 0.85f), nx, y, h, Palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                Row.ENVELOPE -> drawTabs(c, ins, Rect(rx, y, rx + rw, y + h), listen?.kind, col)
                Row.LENGTH -> setting(c, "Length", when {
                    env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${frames(env.values.size)})"
                    kind == NesEnvelopeKind.VOLUME -> "empty: full volume"
                    else -> "empty: not used"
                }, rx, vx, y, h, sel, env.values.isEmpty())
                Row.LOOP -> setting(c, "Loop", if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}", rx, vx, y, h, sel, env.loop < 0)
                Row.RELEASE -> setting(c, "Release", when {
                    env.release >= 0 -> "at step ${env.release}: after OFF the steps from here play out"
                    env.loop < 0 -> "none: OFF stops the note (a release needs a loop)"
                    else -> "none: OFF stops the note"
                }, rx, vx, y, h, sel, env.release < 0)
                Row.MODE -> setting(c, "Mode", if (ins.pitchRelative) "relative: offsets add up every frame" else "absolute: offset from the note", rx, vx, y, h, sel, false)
                else -> Unit
            }
            if (sel) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }
        val small = ink.unit * 0.8f
        ink.label(c, ink.fit(NesInstrumentView.envelopeSummary(kind), rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
        y += rowH * 0.85f

        // steps
        val plotTop = y + ink.unit * 0.3f
        if (area.bottom - plotTop > ink.unit * 4f) drawSteps(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), kind, env, listen?.kind, col, row == Row.STEPS)
        else graph = Rect()
    }

    private fun setting(c: Surface, label: String, value: String, x: Float, vx: Float, y: Float, h: Float, sel: Boolean, off: Boolean) {
        ink.label(c, label, x, y, h, if (sel) Palette.TEXT else Palette.DIM)
        ink.label(c, value, vx, y, h, if (sel) (if (off) Palette.DIM else Palette.TEXT) else (if (off) Palette.FAINT else Palette.DIM), sel)
    }

    /** Frames at the NES's 60 per second, as a duration. */
    private fun frames(n: Int) = "${(n * 1000.0 / 60.0).roundToInt()} ms"

    private fun cursorColor() = when {
        ctl.editHeld -> Palette.WRITE
        ctl.auditionHeld -> Palette.LISTEN
        v.controllerFocused -> Palette.TEXT
        else -> Palette.DIM
    }

    /** Envelope tabs: each with its length; an envelope the listening channel ignores is greyed. */
    private fun drawTabs(c: Surface, ins: NesInstrument, r: Rect, listen: org.thortracker.system.ChannelKind?, col: Int) {
        tabs.clear()
        val n = NesEnvelopeKind.entries.size
        val w = r.width() / n
        for (k in NesEnvelopeKind.entries) {
            val t = Rect(r.left + k.ordinal * w, r.top, r.left + (k.ordinal + 1) * w, r.bottom)
            val on = k.ordinal == st.envelope
            val ignored = listen != null && ignores(k, listen)
            val color = when {
                on -> col
                ignored -> Palette.FAINT
                else -> Palette.DIM
            }
            if (on) ink.rect(c, t, Palette.mix(Palette.BG, col, 0.18f))
            ink.rect(c, t.left, t.bottom - ink.dp(if (on) 3f else 1f), t.right, t.bottom, if (on) col else Palette.RULE)
            val size = k.of(ins).values.size
            val label = if (size == 0) k.label else "${k.label} ($size)"
            ink.label(c, ink.fit(label, w - ink.unit * 0.4f), t.centerX(), t.top, t.height(), color, on, align = Align.CENTER)
            tabs += t to k.ordinal
        }
    }

    /** True when [channel] plays nothing of envelope [kind]. */
    private fun ignores(kind: NesEnvelopeKind, channel: org.thortracker.system.ChannelKind) =
        channel == org.thortracker.system.ChannelKind.TRIANGLE && (kind == NesEnvelopeKind.DUTY || kind == NesEnvelopeKind.VOLUME)

    private fun drawSteps(c: Surface, r: Rect, kind: NesEnvelopeKind, env: NesEnvelope, listen: org.thortracker.system.ChannelKind?, col: Int, focused: Boolean) {
        val values = env.values
        val step = st.envStep
        val title = when {
            step < values.size -> "${kind.label} step $step of ${values.size}: ${NesInstrumentView.valueText(kind, values[step], listen)}"
            values.isEmpty() -> "${kind.label}: no steps; hold ${ctl.keys.label(org.thortracker.editor.Action.EDIT)} and press ${ctl.keys.label(org.thortracker.editor.Action.UP)} to add one"
            else -> "${kind.label}: ${values.size} steps; ${ctl.keys.label(org.thortracker.editor.Action.EDIT)} adds step ${values.size}"
        }
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
            ink.mono(c, NesInstrumentView.valueText(kind, value, listen).take(5), inner.left - ink.unit * 0.3f, yv - ls, ls * 2, Palette.FAINT, size = ls, align = Align.RIGHT)
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
        val muted = listen != null && ignores(kind, listen)
        val barColor = if (muted) Palette.FAINT else Palette.alpha(col, 170)
        val last = minOf(values.size, stepScroll + fit)
        for (i in stepScroll until last) {
            val x = xOf(i)
            val yv = yOf(values[i])
            val t = minOf(base, yv)
            val b = maxOf(base, yv)
            val on = i == step
            ink.rect(c, x + gap, minOf(t, b - ink.dp(2f)), x + cellW - gap, b, if (on && focused) (if (ctl.editHeld) Palette.WRITE else col) else barColor)
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
            ink.outline(c, Rect(x, inner.top, x + cellW, inner.bottom), if (focused) cursorColor() else Palette.FAINT, ink.dp(if (focused) 2f else 1f), ink.dp(2f))
        }
        // step numbers
        val every = when { cellW >= ink.unit * 2f -> 1; cellW >= ink.unit * 1.4f -> 2; else -> 4 }
        for (i in stepScroll until minOf(stepScroll + fit, values.size + 1)) {
            if (i % every != 0 && i != step) continue
            ink.mono(c, i.toString(), xOf(i) + cellW / 2, inner.bottom + ink.dp(2f), labelH, if (i == step) Palette.TEXT else Palette.FAINT, size = ink.unit * 0.65f, align = Align.CENTER)
        }
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

    // ---- touch: slots, tabs, and drawing on the steps -------------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = NesEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        tabs.firstOrNull { it.first.contains(x, y) }?.let { (_, k) ->
            NesInstrumentView.showEnvelope(ctl, k)
            return true
        }
        if (slotRect.contains(x, y)) {
            st.instrument = (((y - slotTop) / slotH).toInt() + slotScroll + 1).coerceIn(1, NesSystem.SLOTS)
            st.instField = 0
            NesInstrumentView.publish(ctl)
            return true
        }
        if (graph.contains(x, y) && cellW > 0f) {
            st.instField = NesInstrumentView.rows(st).indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = NesInstrumentView.kind(st)
            drawing = session.beginGesture("Draw ${drawKind.label.lowercase()}")
            drawAt(x, y)
            return true
        }
        return false
    }

    /** Sets the step under [x] to the value at [y]; past the end it appends one step at a time. */
    private fun drawAt(x: Float, y: Float) {
        val e = song.nesInstrument(drawSlot)?.let { drawKind.of(it) } ?: return
        val i = (stepScroll + ((x - graph.left) / cellW).toInt()).coerceIn(0, NesInstrumentView.lastStep(e))
        val t = ((graph.bottom - y) / graph.height()).coerceIn(0f, 1f)
        val value = (lo + t * (hi - lo)).roundToInt()
        st.envStep = i
        session.editNesEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { NesEnvelopes.withValue(drawKind, it, i, value) }
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
