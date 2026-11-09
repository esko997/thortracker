package org.thortracker.system.saa

import kotlin.math.roundToInt
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.editor.channelCount
import org.thortracker.model.Song
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.saa.SaaInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * SAA1099 instrument editor. Left: the 64 slots of the bank with how often each is used (empty slots say so). Right:
 * name (or, on an empty slot, how to make a sound), preset, the lane Listen plays on (with what that lane does
 * differently), pan left and right side by side, the noise clock (with the variant's frequencies), the hardware
 * envelope switch and, while on, its shape (named and drawn: the left level over a cycle, the right dashed when
 * inverted), resolution, inversion, clock (internal: the lane whose tone clocks it), rate and retrigger; then the page
 * tabs, the shown envelope's length / loop / release / pitch mode, what the player does with it, and its steps as bars
 * ([EnvelopePlot]). Rows a lane or sound cannot use say so. An empty slot shows what its first edit starts from.
 */
internal class SaaInstrumentPainter(v: PaneView) : Painter(v) {
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

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val sound = SaaInstrumentView.sound(s, st)
        val ins = SaaInstrumentView.shown(s, st)
        val kind = SaaInstrumentView.kind(st)
        val env = kind.of(ins)
        val variant = s.saa.variant
        val channels = s.system.channels
        val listen = channels.getOrNull(st.listenChannel)
        val col = Palette.channel(st.listenChannel)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val top = header(c, area, "Sound ${hex2(st.instrument)}",
            "${variant.label} (${variant.chipLabel})${if (sound == null) " · empty slot" else ""}$locked  ${usageText(st.instrument)}")
        val row = SaaInstrumentView.row(s, st)
        rowRects.clear()

        // left column: the bank, scrolled to keep the selected slot in view
        val leftW = minOf(area.width() * 0.3f, ink.unit * 14f)
        val left = Rect(area.left, top, area.left + leftW, area.bottom)
        ink.rect(c, left, Palette.LANE)
        slotTop = top + ink.unit * 0.2f
        slotH = ink.unit * 1.45f
        val visible = maxOf(1, ((area.bottom - slotTop) / slotH).toInt())
        slotScroll = slotScroll.coerceIn(maxOf(0, st.instrument - visible), minOf(st.instrument - 1, SaaSound.SLOTS - visible).coerceAtLeast(0))
        slotRect = Rect(left.left, slotTop, left.right, slotTop + slotH * visible)
        for (k in 0 until minOf(visible, SaaSound.SLOTS - slotScroll)) {
            val i = slotScroll + k + 1
            val y = slotTop + k * slotH
            val sel = i == st.instrument
            if (sel) {
                ink.rect(c, left.left, y, left.right, y + slotH, Palette.CURSOR_FILL)
                ink.rect(c, left.left, y, left.left + ink.dp(3f), y + slotH, col)
            }
            val sz = minOf(ink.unit, slotH * 0.72f)
            ink.mono(c, hex2(i), left.left + ink.unit * 0.6f, y, slotH, if (sel) col else Palette.FAINT, sel, sz)
            val slotSound = s.saa.instrument(i)
            val used = usage(i)
            val nameColor = when {
                slotSound == null -> Palette.FAINT
                sel -> Palette.TEXT
                used > 0 -> Palette.DIM
                else -> Palette.FAINT
            }
            val text = slotSound?.name?.ifEmpty { "(unnamed)" } ?: "· empty"
            ink.label(c, ink.fit(text, leftW - ink.unit * 4.6f, sz), left.left + ink.unit * 2.4f, y, slotH, nameColor, size = sz)
            if (used > 0) ink.mono(c, used.toString(), left.right - ink.unit * 0.5f, y, slotH, Palette.FAINT, size = sz * 0.8f, align = Align.RIGHT)
        }
        if (row == Row.SLOT) {
            val y = slotTop + (st.instrument - 1 - slotScroll) * slotH
            ink.outline(c, Rect(left.left + 2, y + 1, left.right - 2, y + slotH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }

        // right column: settings lines (pairs side by side), shrinking on short displays so the steps keep their room
        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val rw = area.right - pad - rx
        rightEdge = rx + rw
        val lines = displayLines(SaaInstrumentView.rows(s, st))
        val reserve = ink.unit * 7f
        val fixed = ink.unit * 1.9f + (if (ins.envelope != null) ink.unit * 0.6f else 0f)
        val rowH = ((area.bottom - top - reserve - fixed - ink.unit * 1.5f) / (lines.size + 0.6f)).coerceIn(ink.unit * 1.0f, ink.unit * 1.5f)
        val text = minOf(ink.unit, rowH * 0.85f)
        val empty = sound == null
        var y = top + ink.unit * 0.3f
        for (line in lines) {
            val h = when (line.first()) {
                Row.PAGE -> ink.unit * 1.9f
                Row.SHAPE -> rowH + ink.unit * 0.6f
                else -> rowH
            }
            val w = rw / line.size
            line.forEachIndexed { k, r ->
                val x = rx + k * w
                val cellRight = x + w - (if (k < line.size - 1) ink.unit * 0.6f else 0f)
                val sel = r == row
                if (sel) ink.rect(c, x - ink.unit * 0.4f, y, cellRight + ink.unit * 0.4f, y + h, Palette.CURSOR_FILL)
                drawRow(c, r, ins, empty, x, cellRight, y, h, sel, text, col, listen?.name.orEmpty())
                if (r != Row.PAGE) rowRects += Rect(x, y, cellRight, y + h) to r
                if (sel) ink.outline(c, Rect(x - ink.unit * 0.4f, y + 1, cellRight + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            }
            y += h
        }
        val small = ink.unit * 0.8f
        ink.label(c, ink.fit(SaaInstrumentView.envelopeSummary(kind), rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
        y += rowH * 0.85f

        // steps
        val plotTop = y + ink.unit * 0.3f
        if (area.bottom - plotTop > ink.unit * 4f) drawSteps(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), kind, env, col, row == Row.STEPS)
        else plot.hide()
    }

    /** Rows grouped into display lines: the pan pair, resolution with inversion and clock with rate share a line. */
    private fun displayLines(rows: List<Row>): List<List<Row>> {
        val out = ArrayList<List<Row>>()
        var i = 0
        while (i < rows.size) {
            val r = rows[i]
            if (r == Row.SLOT || r == Row.STEPS) { i++; continue }
            val next = rows.getOrNull(i + 1)
            val pair = (r == Row.PAN_LEFT && next == Row.PAN_RIGHT) || (r == Row.RESOLUTION && next == Row.INVERT) ||
                (r == Row.CLOCK && next == Row.RATE)
            if (pair && next != null) { out += listOf(r, next); i += 2 } else { out += listOf(r); i++ }
        }
        return out
    }

    private fun drawRow(c: Surface, r: Row, ins: SaaInstrument, empty: Boolean, x: Float, right: Float, y: Float, h: Float,
                        sel: Boolean, size: Float, col: Int, listenName: String) {
        val s = song
        val variant = s.saa.variant
        val vx = x + ink.unit * (if (right - x < ink.unit * 14f) 4.2f else 6.5f)
        val e = ins.envelope
        val laneHasEnvelope = SaaSound.hasEnvelope(st.listenChannel)
        fun setting(value: String, off: Boolean) {
            ink.label(c, r.label, x, y, h, if (sel) Palette.TEXT else Palette.DIM, size = size)
            ink.label(c, ink.fit(value, maxOf(ink.unit, right - vx), size, sel), vx, y, h,
                if (sel) (if (off) Palette.DIM else Palette.TEXT) else (if (off) Palette.FAINT else Palette.DIM), sel, size)
        }
        when (r) {
            Row.NAME -> if (empty) {
                ink.label(c, "Empty slot", x, y, h, if (sel) Palette.TEXT else Palette.DIM, size = size)
                ink.label(c, ink.fit("${ctl.k(Action.EDIT)} on Slot makes a sound; ${ctl.keys.held(Action.EDIT, ctl.lr())} on Preset picks one",
                    maxOf(ink.unit, right - vx), size), vx, y, h, Palette.FAINT, size = size)
            } else {
                ink.label(c, "Name", x, y, h, if (sel) Palette.TEXT else Palette.DIM, size = size)
                ink.label(c, ins.name.ifEmpty { "(unnamed)" }, vx, y, h, if (ins.name.isEmpty()) Palette.FAINT else Palette.TEXT, true, size)
                if (sel) {
                    val before = ins.name.take(st.textPos).padEnd(st.textPos, ' ')
                    val cx = vx + ink.labelWidth(before, size, true)
                    val ch = ins.name.getOrNull(st.textPos)?.toString() ?: " "
                    val w = maxOf(ink.labelWidth(ch, size, true), ink.unit * 0.5f)
                    ink.outline(c, Rect(cx - 1, y + 2, cx + w + 1, y + h - 2), cursorColor(), ink.dp(2f), ink.dp(2f))
                }
            }
            Row.PRESET -> {
                val p = SaaInstrumentView.presetIndex(if (empty) null else ins)
                setting(when {
                    empty -> "none: ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"
                    p >= 0 -> "${SaaPresets.ALL[p].name} (${p + 1}/${SaaPresets.ALL.size})"
                    else -> "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"
                }, p < 0)
            }
            Row.LISTEN -> {
                ink.label(c, "Listen on", x, y, h, if (sel) Palette.TEXT else Palette.DIM, size = size)
                ink.label(c, listenName, vx, y, h, col, true, size)
                SaaInstrumentView.channelNote(s, st.listenChannel, ins)?.let { note ->
                    val nx = vx + ink.labelWidth(listenName, size, true) + ink.unit
                    ink.label(c, ink.fit(note, right - nx, ink.unit * 0.8f), nx, y, h, Palette.PLAYHEAD, size = ink.unit * 0.8f)
                }
            }
            Row.PAN_LEFT -> setting("${ins.panLeft}${if (ins.panLeft == 0) " (silent left)" else ""}", ins.panLeft == 0)
            Row.PAN_RIGHT -> setting("${ins.panRight}${if (ins.panRight == 0) " (silent right)" else ""}", ins.panRight == 0)
            Row.NOISE -> {
                val unused = SaaInstrumentView.noiseNote(ins)
                val clock = "${ins.noise}: " + SaaRules.noiseText(ins.noise, variant, st.listenChannel)
                setting(if (unused == null) clock else "$clock · $unused", unused != null)
            }
            Row.HARDWARE -> setting(when {
                e == null -> "off"
                !laneHasEnvelope -> "on · ${s.system.channels.getOrNull(st.listenChannel)?.name.orEmpty()} has none: lanes 3 and 6 play it"
                else -> "on · control ${hex2(e.control)}"
            }, e == null || !laneHasEnvelope)
            Row.SHAPE -> if (e != null) {
                val name = "${e.shape}: ${SaaHardwareEnvelope.SHAPE_NAMES[e.shape]}"
                ink.label(c, r.label, x, y, h, if (sel) Palette.TEXT else Palette.DIM, size = size)
                val nameW = minOf(ink.labelWidth(name, size, sel), (right - vx) * 0.45f)
                ink.label(c, ink.fit(name, nameW + 1f, size, sel), vx, y, h, if (sel) Palette.TEXT else Palette.DIM, sel, size)
                val gx = vx + nameW + ink.unit
                if (right - gx > ink.unit * 3f) drawShape(c, Rect(gx, y + ink.unit * 0.2f, right, y + h - ink.unit * 0.2f), e, col)
            }
            Row.RESOLUTION -> if (e != null) setting(if (e.threeBit) "3-bit (8 levels)" else "4-bit (16 levels)", false)
            Row.INVERT -> if (e != null) setting(if (e.invertRight) "on: right = 15 − left" else "off", !e.invertRight)
            Row.CLOCK -> if (e != null) setting(when {
                e.external -> "external (player)"
                laneHasEnvelope -> "internal: ${s.system.channels[SaaSound.envelopeClockLane(st.listenChannel)].name}'s tone"
                else -> "internal: lane 2's or 5's tone"
            }, false)
            Row.RATE -> if (e != null) setting(if (e.external) "${e.rate} tick${if (e.rate == 1) "" else "s"} per step" else "unused (internal)", !e.external)
            Row.RETRIGGER -> if (e != null) setting(if (e.retrigger) "on: each note restarts it" else "off: it runs on across notes", !e.retrigger)
            Row.PAGE -> drawTabs(c, ins, Rect(x, y, right, y + h), col)
            Row.LENGTH -> {
                val env = SaaInstrumentView.kind(st).of(ins)
                setting(when {
                    env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${ticks(env.values.size)})"
                    SaaInstrumentView.kind(st) == SaaEnvelopeKind.VOLUME -> "empty: volume 15 throughout"
                    SaaInstrumentView.kind(st) == SaaEnvelopeKind.MIXER -> "empty: tone throughout"
                    else -> "empty: not used"
                }, env.values.isEmpty())
            }
            Row.LOOP -> {
                val env = SaaInstrumentView.kind(st).of(ins)
                setting(if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}", env.loop < 0)
            }
            Row.RELEASE -> {
                val env = SaaInstrumentView.kind(st).of(ins)
                setting(when {
                    env.release < 0 -> "none: OFF stops the note"
                    env.loop < 0 -> "at step ${env.release}: holds step ${env.release - 1} until OFF, then plays on from here"
                    else -> "at step ${env.release}: after OFF the steps from here play out"
                }, env.release < 0)
            }
            Row.MODE -> setting(if (ins.pitchRelative) "relative: offsets add up every tick" else "absolute: offset from the note", false)
            Row.SLOT, Row.STEPS -> Unit
        }
    }

    /**
     * The hardware envelope's left level over two cycles of a repeating shape, or one cycle and its hold of a one-shot
     * (0 on the internal clock; the last step on the external clock), as steps; the right level dashed when inverted.
     */
    private fun drawShape(c: Surface, r: Rect, e: SaaHardwareEnvelope, col: Int) {
        ink.round(c, r, ink.dp(3f), Palette.LANE)
        val levels = SaaHardwareEnvelope.levels(e.shape, e.threeBit)
        val looping = SaaHardwareEnvelope.looping(e.shape)
        val n = levels.size
        val total = if (looping) 2 * n else n + n / 2
        val inner = Rect(r.left + ink.dp(3f), r.top + ink.dp(3f), r.right - ink.dp(3f), r.bottom - ink.dp(3f))
        val stepW = inner.width() / total
        fun level(k: Int, right: Boolean): Int {
            val left = when {
                k < n -> levels[k]
                looping -> levels[k % n]
                e.external -> levels[n - 1]
                else -> 0
            }
            return if (right) SaaHardwareEnvelope.right(left, e.threeBit, true) else left
        }
        fun yOf(v: Int) = inner.bottom - inner.height() * v / 15f
        val sides = if (e.invertRight) 2 else 1
        for (side in 0 until sides) {
            val right = side == 1
            val color = if (right) Palette.DIM else col
            val width = ink.dp(if (right) 1f else 2f)
            for (k in 0 until total) {
                val x0 = inner.left + k * stepW
                val y0 = yOf(level(k, right))
                if (right && k % 2 == 1) continue // dashed: every other step
                ink.line(c, x0, y0, x0 + stepW, y0, color, width)
                if (k + 1 < total && !right) ink.line(c, x0 + stepW, y0, x0 + stepW, yOf(level(k + 1, false)), color, width)
            }
        }
        if (!looping) ink.line(c, inner.left + n * stepW, inner.top, inner.left + n * stepW, inner.bottom, Palette.RULE, ink.dp(1f))
    }

    /** Player ticks (50 or 60 per second at multispeed 1) as a duration. */
    private fun ticks(n: Int) = "${(n * 1000.0 / (song.saa.variant.rate * song.saa.multispeed)).roundToInt()} ms"

    private fun cursorColor() = when {
        ctl.editHeld -> Palette.WRITE
        ctl.auditionHeld -> Palette.LISTEN
        v.controllerFocused -> Palette.TEXT
        else -> Palette.DIM
    }

    /** Page tabs: each envelope with its length. */
    private fun drawTabs(c: Surface, ins: SaaInstrument, r: Rect, col: Int) {
        tabs.clear()
        val kinds = SaaEnvelopeKind.entries
        val w = r.width() / kinds.size
        for (k in kinds) {
            val t = Rect(r.left + k.ordinal * w, r.top, r.left + (k.ordinal + 1) * w, r.bottom)
            val on = k.ordinal == st.envelope
            if (on) ink.rect(c, t, Palette.mix(Palette.BG, col, 0.18f))
            ink.rect(c, t.left, t.bottom - ink.dp(if (on) 3f else 1f), t.right, t.bottom, if (on) col else Palette.RULE)
            val size = k.of(ins).values.size
            val label = if (size == 0) k.label else "${k.label} ($size)"
            ink.label(c, ink.fit(label, w - ink.unit * 0.4f), t.centerX(), t.top, t.height(), if (on) col else Palette.DIM, on, align = Align.CENTER)
            tabs += t to k.ordinal
        }
    }

    private fun drawSteps(c: Surface, r: Rect, kind: SaaEnvelopeKind, env: NesEnvelope, col: Int, focused: Boolean) {
        val text = { value: Int -> SaaInstrumentView.valueText(kind, value) }
        val titleText = if (kind == SaaEnvelopeKind.MIXER) { value: Int -> SaaRules.mixerText(value) } else text
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
    private var drawKind = SaaEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        tabs.firstOrNull { it.first.contains(x, y) }?.let { (_, k) ->
            SaaInstrumentView.showPage(ctl, k)
            return true
        }
        if (slotRect.contains(x, y)) {
            st.instrument = (((y - slotTop) / slotH).toInt() + slotScroll + 1).coerceIn(1, SaaSound.SLOTS)
            st.instField = 0
            SaaInstrumentView.publish(ctl)
            return true
        }
        rowRects.firstOrNull { it.first.contains(x, y) }?.let { (_, r) ->
            st.instField = SaaInstrumentView.rows(song, st).indexOf(r).coerceAtLeast(0)
            return true
        }
        if (plot.contains(x, y)) {
            st.instField = SaaInstrumentView.rows(song, st).indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = SaaInstrumentView.kind(st)
            drawing = session.beginGesture("Draw ${drawKind.label.lowercase()}")
            drawAt(x, y)
            return true
        }
        return false
    }

    /** Sets the step under [x] to the value at [y]; past the end it appends one step at a time. */
    private fun drawAt(x: Float, y: Float) {
        val ins = song.saaInstrument(drawSlot) ?: defaultSaaInstrument(drawSlot)
        val e = drawKind.of(ins)
        val i = plot.stepAt(x, SaaInstrumentView.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        session.editSaaEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { SaaEnvelopes.withValue(drawKind, it, i, value) }
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
