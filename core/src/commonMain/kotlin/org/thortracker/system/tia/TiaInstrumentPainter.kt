package org.thortracker.system.tia

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.tia.TiaInstrumentView.Page
import org.thortracker.system.tia.TiaInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * Atari 2600 instrument editor. Left: the 64 slots (raw sounds marked "ʳ", empty "·") with how often each is used.
 * Right: name, Listen channel, waveform (with the span it reaches on the song's TV standard), pitch mode, preset, page
 * tabs, then the page: an envelope's length / loop / release (pitch: mode) and its steps (the NES editor's plot), or the
 * pitch guide: the waveform's 32 dividers with their frequency, nearest note and error, the cursor on one. The bank,
 * tabs and setting rows are the shared [FmInstrumentPainter]'s.
 */
internal class TiaInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private companion object {
        /** The NES envelope kind whose plot defaults stand in for each TIA envelope (the scale is always passed). */
        val PLOT_KINDS = mapOf(
            TiaEnvelopeKind.VOLUME to NesEnvelopeKind.VOLUME, TiaEnvelopeKind.ARPEGGIO to NesEnvelopeKind.ARPEGGIO,
            TiaEnvelopeKind.PITCH to NesEnvelopeKind.PITCH, TiaEnvelopeKind.WAVEFORM to NesEnvelopeKind.DUTY,
        )
    }

    private val plot = EnvelopePlot(ink)

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = TiaInstrumentView.sound(s, st)
        val region = s.tia.region
        val col = ink.palette.channel(st.listenChannel)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = when {
            ins == null -> "empty slot"
            ins.raw -> "raw sound"
            else -> "tonal sound"
        }
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "Atari 2600 ${region.label} · $kind$locked  ${usageText(st.instrument)}")
        val row = TiaInstrumentView.row(st)
        val allRows = TiaInstrumentView.rows(st)
        rowRects.clear()
        gridCells.clear()
        plot.hide()
        ladderRect = Rect()

        val left = drawBank(c, area, top, TiaSystem.SLOTS, col, row == Row.SLOT) { i ->
            s.tia.instrument(i)?.let { it.name.ifEmpty { "(unnamed)" } + if (it.raw) " ʳ" else "" }
        }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val rw = area.right - pad - rx
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT && it != Row.STEPS && it != Row.LADDER }
        val reserve = ink.unit * 8f
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve) / (rows.size + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        for (r in rows) {
            val sel = r == row
            val h = if (r == Row.PAGE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, ink.palette.CURSOR_FILL)
            when {
                r == Row.NAME -> if (ins != null) drawName(c, ins.name, rx, vx, y, h, sel)
                    else setting(c, "Empty slot", "${ctl.k(Action.EDIT)} on Slot or any setting makes a sound here", rx, vx, y, h, sel, true)
                r == Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM)
                    ink.label(c, s.system.channels.getOrNull(st.listenChannel)?.name.orEmpty(), vx, y, h, col, true)
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, TiaInstrumentView.PAGES.map { tabLabel(ins, it) },
                    st.envelope.coerceIn(0, TiaInstrumentView.PAGES.size - 1))
                ins == null -> setting(c, r.label, "—", rx, vx, y, h, sel, true)
                else -> setting(c, r.label, value(ins, r), rx, vx, y, h, sel, dim(ins, r))
            }
            if (r != Row.PAGE) rowRects += Rect(rx, y, rx + rw, y + h) to allRows.indexOf(r)
            if (sel && r != Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            if (sel && r == Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.2f, y + 1, rx + rw + ink.unit * 0.2f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }

        val k = TiaInstrumentView.envelopeKind(st)
        if (k == null) {
            val boxTop = y + ink.unit * 0.4f
            if (area.bottom - boxTop > ink.unit * 4f) drawGuide(c, Rect(rx, boxTop, rx + rw, area.bottom - ink.unit * 0.4f), ins, row == Row.LADDER, col)
            return
        }
        val small = ink.unit * 0.8f
        ink.label(c, ink.fit(TiaInstrumentView.envelopeSummary(k, ins?.raw == true), rw, small), rx, y, rowH * 0.85f, ink.palette.FAINT, size = small)
        y += rowH * 0.85f
        val plotTop = y + ink.unit * 0.3f
        if (area.bottom - plotTop <= ink.unit * 4f) return
        val env = k.of(ins ?: defaultTiaInstrument(st.instrument))
        val text = { value: Int -> TiaInstrumentView.valueText(k, value) }
        val title = EnvelopePlot.title(k.label, env.values, st.envStep, text, ctl.keys.label(Action.EDIT), ctl.keys.label(Action.UP))
        val muted = ins == null || (ins.raw && k == TiaEnvelopeKind.PITCH)
        plot.draw(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), title, PLOT_KINDS.getValue(k),
            NesEnvelope(env.values, env.loop, env.release), st.envStep, text, muted, col, row == Row.STEPS,
            cursorColor(), ctl.editHeld, k.range, TiaEnvelope.MAX_LENGTH)
    }

    /** A page tab: envelopes carry their length, as on the NES. */
    private fun tabLabel(ins: TiaInstrument?, p: Page): String {
        val k = p.envelope ?: return p.label
        val n = ins?.let { k.of(it).values.size } ?: 0
        return if (n == 0) p.label else "${p.label} ($n)"
    }

    private fun value(ins: TiaInstrument, r: Row): String {
        val s = song
        val region = s.tia.region
        val kind = TiaInstrumentView.envelopeKind(st)
        val env = kind?.of(ins) ?: TiaEnvelope()
        return when (r) {
            Row.WAVEFORM -> {
                val w = TiaRules.WAVEFORMS[ins.waveform and 15]
                val span = TiaRules.reach(region, ins.waveform)
                    ?.let { "   ${TiaSystem.noteText(it.first)}..${TiaSystem.noteText(it.last)} on ${region.label}" } ?: "   no pitch"
                "${TiaRules.audcText(ins.waveform)}  ${w.short}$span" + if (ins.waveforms.values.isNotEmpty()) " (the sequence picks per frame)" else ""
            }
            Row.MODE -> if (ins.raw) "Raw   the note is the divider: C-0 = AUDF 31, up one step per semitone"
                else "Tonal   the note picks the nearest divider"
            Row.PRESET -> TiaPresets.ALL.getOrNull(TiaInstrumentView.presetIndex(ins))?.name
                ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound, ${ctl.k(Action.EDIT)} lists them"
            Row.LENGTH -> when {
                env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${ticks(env.values.size)})"
                kind == TiaEnvelopeKind.VOLUME -> "empty: full volume"
                kind == TiaEnvelopeKind.WAVEFORM -> "empty: the sound's waveform"
                else -> "empty: not used"
            }
            Row.LOOP -> if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}"
            Row.RELEASE -> when {
                env.release < 0 && kind == TiaEnvelopeKind.VOLUME -> "none: OFF stops the note"
                env.release < 0 -> "none: holds through OFF"
                kind == TiaEnvelopeKind.VOLUME -> "at step ${env.release}: OFF releases, the steps from here play out"
                ins.volume.release < 0 -> "at step ${env.release}: used only when the volume envelope has a release"
                else -> "at step ${env.release}: after OFF the steps from here play out"
            }
            Row.RELATIVE -> if (ins.relativePitch) "relative: offsets add up every frame" else "absolute: offset from the note"
            else -> ""
        }
    }

    private fun dim(ins: TiaInstrument, r: Row): Boolean = when (r) {
        Row.PRESET -> TiaInstrumentView.presetIndex(ins) < 0
        Row.RELATIVE -> ins.raw
        else -> false
    }

    private fun ticks(n: Int) = "${(n * 1000.0 / song.system.ticksPerSecond(song)).toInt()} ms"

    // ---- pitch guide ------------------------------------------------------------------------------------------------

    private var ladderRect = Rect()
    private var ladderRowH = 0f
    private var ladderScroll = 0

    /**
     * The waveform the sound keys on with: its name, period and span on the song's TV standard, then its dividers AUDF
     * 0..31 (frequency, nearest note, cents), the cursor's highlighted and the one the Listen note plays marked.
     */
    private fun drawGuide(c: Surface, r: Rect, ins: TiaInstrument?, focused: Boolean, col: Int) {
        val s = song
        val region = s.tia.region
        val small = ink.unit * 0.8f
        val titleH = ink.unit * 1.4f
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val w = TiaInstrumentView.guideWaveform(s, st)
        val wf = TiaRules.WAVEFORMS[w]
        val period = TiaRules.period(w)
        val span = TiaRules.reach(region, w)
        val head = "AUDC ${TiaRules.audcText(w)} · ${wf.long}" + (period?.let { " · period $it" } ?: "") +
            (span?.let { " · ${region.label} ${TiaSystem.noteText(it.first)}..${TiaSystem.noteText(it.last)}" } ?: "")
        ink.label(c, ink.fit(head, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, ink.palette.DIM, size = small)
        if (period == null && ins?.raw != true) {
            ink.label(c, ink.fit("A constant level: no divider, no pitch. The volume (envelope, Cxx) is the output level; its steps click at the frame rate.",
                r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top + titleH, titleH, ink.palette.FAINT, size = small)
            return
        }
        ladderRowH = ink.unit * 1.15f
        ladderRect = Rect(r.left, r.top + titleH, r.right, r.bottom - ink.unit * 0.2f)
        val visible = maxOf(1, (ladderRect.height() / ladderRowH).toInt())
        val cursor = st.envStep.coerceIn(0, TiaRules.AUDF_MAX)
        ladderScroll = ladderScroll.coerceIn(maxOf(0, cursor - visible + 1), cursor)
        ladderScroll = ladderScroll.coerceIn(0, maxOf(0, TiaRules.AUDF_MAX + 1 - visible))
        val listenNote = ctl.defaultNote()
        val heard = when {
            ins == null -> -1
            ins.raw -> TiaRules.rawAudf(listenNote)
            else -> TiaRules.audf(region, w, listenNote * TiaRules.UNITS) ?: -1
        }
        val x0 = r.left + ink.unit * 0.5f
        val x1 = x0 + ink.unit * 4.5f
        val x2 = x1 + ink.unit * 6.5f
        val x3 = x2 + ink.unit * 4f
        for (a in ladderScroll until minOf(TiaRules.AUDF_MAX + 1, ladderScroll + visible)) {
            val y = ladderRect.top + (a - ladderScroll) * ladderRowH
            val on = a == cursor
            if (on) ink.rect(c, ladderRect.left, y, ladderRect.right, y + ladderRowH, ink.palette.CURSOR_FILL)
            val hz = TiaRules.frequency(region, w, a)
            ink.mono(c, "AUDF ${a.toString().padStart(2)}", x0, y, ladderRowH, if (a == heard) col else ink.palette.DIM, a == heard, small)
            ink.mono(c, hz?.let { TiaRules.hzText(it) } ?: "—", x1, y, ladderRowH, if (on) ink.palette.TEXT else ink.palette.DIM, size = small)
            if (ins?.raw == true) {
                val note = TiaInstrumentView.ladderNote(region, ins, a, listenNote)?.first
                ink.label(c, ink.fit("raw: note ${note?.let { TiaSystem.noteText(it) } ?: "—"} (and every 32 semitones)", r.right - x2 - ink.unit * 0.4f, small),
                    x2, y, ladderRowH, ink.palette.FAINT, size = small)
            } else if (hz != null) {
                val note = TiaRules.nearestNote(hz)
                val name = if (note in TiaRules.NOTES) TiaSystem.noteText(note) else "—"
                val cents = TiaRules.cents(hz, note)
                ink.mono(c, name, x2, y, ladderRowH, if (on) ink.palette.TEXT else ink.palette.DIM, size = small)
                ink.mono(c, TiaRules.centsText(cents), x3, y, ladderRowH, if (kotlin.math.abs(cents) > 25) ink.palette.ERROR else ink.palette.FAINT, size = small)
            }
            if (on && focused) ink.outline(c, Rect(ladderRect.left + 2, y + 1, ladderRect.right - 2, y + ladderRowH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }
        if (heard >= 0) ink.label(c, "${TiaSystem.noteText(listenNote)} plays AUDF $heard", r.right - ink.unit * 0.5f, r.top, titleH * 0.6f,
            col, size = ink.unit * 0.65f, align = Align.RIGHT)
    }

    // ---- touch: shared parts, drawing on the steps, the ladder ------------------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = TiaEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        val rows = TiaInstrumentView.rows(st)
        if (downShared(x, y, TiaSystem.SLOTS, { TiaInstrumentView.showPage(ctl, it) }, { TiaInstrumentView.publish(ctl) })) return true
        val kind = TiaInstrumentView.envelopeKind(st)
        if (kind != null && plot.contains(x, y)) {
            st.instField = rows.indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = kind
            drawing = session.beginGesture("Draw ${kind.label.lowercase()}")
            drawAt(x, y)
            return true
        }
        if (kind == null && ladderRect.contains(x, y) && ladderRowH > 0f) {
            st.instField = rows.indexOf(Row.LADDER)
            st.envStep = (ladderScroll + ((y - ladderRect.top) / ladderRowH).toInt()).coerceIn(0, TiaRules.AUDF_MAX)
            return true
        }
        return false
    }

    /** Sets the step under [x] to the value at [y]; past the end it appends one step at a time. */
    private fun drawAt(x: Float, y: Float) {
        val ins = song.tiaInstrument(drawSlot)
        val e = drawKind.of(ins ?: defaultTiaInstrument(drawSlot))
        val i = plot.stepAt(x, TiaEnvelopes.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        session.editTiaEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { TiaEnvelopes.withValue(drawKind, it, i, value) }
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
