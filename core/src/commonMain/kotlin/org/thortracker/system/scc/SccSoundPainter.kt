package org.thortracker.system.scc

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** The cartridge's signed wave memory is the instrument: a large zero-centred trace, not a preset illustration. */
internal class SccSoundPainter(v: PaneView) : Painter(v) {
    private val advanced = SccInstrumentPainter(v)
    private val modes = Array(2) { Rect() }
    private val controls = Array(SccSoundMacros.COUNT) { Rect() }
    private val rails = Array(SccSoundMacros.COUNT) { Rect() }
    private val body = Rect()
    private val scope = Rect()
    private val name = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val slotPrevious = Rect()
    private val slotNext = Rect()
    private val empty = SccInstrument(name = "Empty / edit to create")
    private var advancedPointer = false
    private var cached: SccInstrument? = null
    private val texts = Array(SccSoundMacros.COUNT) { "" }
    private val hints = Array(SccSoundMacros.COUNT) { "" }
    private val values = IntArray(SccSoundMacros.COUNT)
    private var cachedListen = -1
    private var nameKey: String? = null
    private var namePos = -2
    private var nameWidth = -1f
    private var nameUnit = -1f
    private var shownName = ""
    private var caretLeft = 0f
    private var caretWidth = 0f

    private fun drawName(c: Surface, value: String, accent: Int) {
        val pos = if (st.instField == 1) st.textPos else -1
        val width = maxOf(0f, name.width() - ink.dp(8f))
        if (nameKey !== value || namePos != pos || nameWidth != width || nameUnit != ink.unit) {
            val padded = if (pos >= 0) value.padEnd(pos + 1, ' ') else value
            var start = 0
            while (start < pos && ink.labelWidth(padded.substring(start, pos + 1), ink.unit, true) > width) start++
            shownName = ink.fit(padded.substring(start), width, ink.unit, true)
            if (pos >= 0) {
                caretLeft = ink.labelWidth(padded.substring(start, pos), ink.unit, true)
                caretWidth = maxOf(ink.unit * .5f, ink.labelWidth(padded[pos].toString(), ink.unit, true))
            }
            nameKey = value; namePos = pos; nameWidth = width; nameUnit = ink.unit
        }
        ink.label(c, shownName, name.left + ink.dp(4f), name.top, name.height(), ink.palette.TEXT, true)
        if (pos >= 0) {
            ink.outline(c, name, ink.palette.FOCUS, ink.dp(2f))
            val x = name.left + ink.dp(4f) + caretLeft
            c.fillRect(x, name.bottom - ink.dp(8f), minOf(name.right, x + caretWidth),
                name.bottom - ink.dp(5f), if (ctl.editHeld) ink.palette.WRITE else accent)
        }
    }
    private class Target(val slot: Int, val control: Int, val revision: Int, val left: Float, val right: Float,
                         val original: SccInstrument, var expected: SccInstrument?)
    private val drag = PanelDrag<Target>()
    private val cancelTouch: () -> Unit = { cancel() }

    private fun cache(ins: SccInstrument) {
        if (cached === ins && cachedListen == st.listenChannel) return
        for (i in 0 until SccSoundMacros.COUNT) {
            values[i] = if (i == 4) st.listenChannel else SccSoundMacros.value(i, ins)
            texts[i] = if (!SccSoundMacros.enabled(i, ins)) "Sequence / Advanced" else SccSoundMacros.text(i, values[i])
            hints[i] = SccSoundMacros.hint(i, ins)
        }
        cached = ins; cachedListen = st.listenChannel
    }

    override fun draw(c: Surface, area: Rect) {
        val p = ink.palette
        val accent = if (p.id == Palette.CONTRAST.id) p.FOCUS else 0xFF66BCD0.toInt()
        val touch = ink.dp(48f)
        val panel = st.soundPanel("scc")
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        val modeW = minOf(area.width() / 2, touch * 2.5f)
        for (i in 0..1) {
            modes[i].set(area.left + modeW * i, area.top, area.left + modeW * (i + 1), area.top + touch)
            PanelWidgets.segment(ink, c, modes[i], panel.advanced == (i == 1), accent)
            ink.label(c, if (i == 0) "Sound" else "Advanced", modes[i].centerX(), area.top, touch, p.TEXT, true, align = Align.CENTER)
        }
        if (st.instField == -1) ink.outline(c, modes[if (panel.advanced) 1 else 0], p.FOCUS, ink.dp(2f))
        body.set(area.left, area.top + touch, area.right, area.bottom)
        if (panel.advanced) { advanced.draw(c, body); return }
        val ins = song.sccInstrument(st.instrument) ?: empty
        cache(ins)
        val bank = advanced.drawSidebar(c, body, p.channel(st.listenChannel), false)
        val left = bank.right + ink.dp(8f)
        val right = area.right - ink.dp(8f)
        val top = minOf(bank.top, area.bottom - touch * 3)
        c.fillRect(left, top, right, area.bottom, p.BG)
        slotPrevious.set(left, top, left + touch, top + touch)
        slotNext.set(right - touch, top, right, top + touch)
        name.set(slotPrevious.right, top, slotNext.left, top + touch)
        ink.label(c, "−", slotPrevious.centerX(), top, touch, p.TEXT, true, align = Align.CENTER)
        ink.label(c, "+", slotNext.centerX(), top, touch, p.TEXT, true, align = Align.CENTER)
        drawName(c, ins.name, accent)
        val footer = area.bottom - touch
        val available = footer - (top + touch)
        val wide = right - left >= ink.dp(420f) && available >= touch * SccSoundMacros.COUNT
        val focused = (st.instField - 2).coerceIn(0, SccSoundMacros.COUNT - 1)
        if (wide) {
            val middle = left + (right - left) * 0.52f
            scope.set(left, top + touch, middle - ink.dp(12f), footer)
            val h = available / SccSoundMacros.COUNT
            for (i in controls.indices) controls[i].set(middle, top + touch + i * h, right, top + touch + (i + 1) * h)
        } else {
            val scopeH = maxOf(0f, available - touch)
            scope.set(left, top + touch, right, top + touch + scopeH)
            controls[focused].set(left, scope.bottom, right, footer)
        }
        drawWave(c, scope, ins, accent)
        for (i in controls.indices) if (controls[i].height() > 0) drawControl(c, i, ins, accent)
        previous.set(left, footer, left + touch, area.bottom)
        next.set(right - touch, footer, right, area.bottom)
        ink.label(c, "‹", previous.centerX(), footer, touch, accent, true, align = Align.CENTER)
        ink.label(c, "›", next.centerX(), footer, touch, accent, true, align = Align.CENTER)
        ink.label(c, ink.fit(hints[focused], maxOf(0f, next.left - previous.right), ink.unit * 0.72f), previous.right,
            footer, touch, p.DIM, size = ink.unit * 0.72f)
    }

    private fun drawWave(c: Surface, r: Rect, ins: SccInstrument, accent: Int) {
        if (r.height() < ink.dp(12f)) return
        val p = ink.palette
        c.fillRect(r.left, r.top, r.right, r.bottom, Palette.mix(p.BG, accent, 0.12f))
        val labelH = minOf(ink.dp(28f), r.height() / 3)
        val shared = song.scc.variant.sharedWave && st.listenChannel in SccSound.SHARED_CHANNELS
        val caption = if (shared) "SCC 4/5: last wave wins" else if (ins.wave.values.size > 1) "Wave sequence / first stored wave" else "Signed wave memory / 32 steps"
        ink.label(c, ink.fit(caption, r.width() - ink.dp(8f), ink.unit * 0.75f), r.left + ink.dp(4f), r.top, labelH,
            if (shared) p.PLAYHEAD else p.TEXT, size = ink.unit * 0.75f)
        val samples = song.scc.wave(ins.wave.values.firstOrNull() ?: 1)?.samples ?: return
        if (samples.isEmpty()) return
        val center = (r.top + labelH + r.bottom) / 2
        val amplitude = (r.bottom - r.top - labelH) * 0.45f
        val w = r.width() / samples.size
        c.line(r.left, center, r.right, center, p.RULE, ink.dp(1f))
        for (i in samples.indices) {
            val x = r.left + i * w
            val y = center - samples[i] / 128f * amplitude
            c.line(x, y, x + w, y, accent, ink.dp(2f))
            if (i > 0) c.line(x, center - samples[i - 1] / 128f * amplitude, x, y, accent, ink.dp(2f))
        }
    }
    private fun drawControl(c: Surface, i: Int, ins: SccInstrument, accent: Int) {
        val r = controls[i]
        val p = ink.palette
        val selected = st.instField == i + 2
        val enabled = SccSoundMacros.enabled(i, ins)
        val pad = ink.dp(12f)
        val h = minOf(ink.dp(24f), r.height() * 0.5f)
        val textWidth = (r.width() - pad * 2) / 2
        ink.label(c, ink.fit(SccSoundMacros.labels[i], textWidth, ink.unit * 0.8f, selected), r.left + pad, r.top, h, if (selected) p.TEXT else p.DIM, selected, size = ink.unit * 0.8f)
        ink.label(c, ink.fit(texts[i], textWidth, ink.unit * 0.8f), r.right - pad, r.top, h, if (enabled) accent else p.FAINT, align = Align.RIGHT, size = ink.unit * 0.8f)
        rails[i].set(r.left + pad, r.top + h, r.right - pad, r.bottom)
        val max = SccSoundMacros.maximum(i)
        if (enabled && values[i] >= 0) PanelWidgets.rail(ink, c, rails[i].left, rails[i].right, rails[i].centerY(),
            values[i].toFloat() / max, max, accent, selected, ctl.editHeld)
        else c.line(rails[i].left, rails[i].centerY(), rails[i].right, rails[i].centerY(), p.RULE, ink.dp(2f))
        if (selected) c.fillRect(r.left, r.top, r.left + ink.dp(3f), r.bottom, p.FOCUS)
    }
    internal fun controlRect(i: Int): Rect = controls[i]
    internal fun modeRect(advanced: Boolean): Rect = modes[if (advanced) 1 else 0]
    internal fun navigationRect(forward: Boolean): Rect = if (forward) next else previous
    internal fun slotNavigationRect(forward: Boolean): Rect = if (forward) slotNext else slotPrevious
    override fun down(x: Float, y: Float): Boolean {
        cancel()
        for (i in 0..1) if (modes[i].contains(x, y)) { SccSoundFace.mode(ctl, i == 1); return true }
        val panel = st.soundPanel("scc")
        if (panel.advanced) {
            advancedPointer = advanced.down(x, y)
            if (advancedPointer) panel.cancelTouch = cancelTouch
            return advancedPointer
        }
        if (ctl.editHeld) { ctl.useEdit(); panel.editBlocked = true }
        val slot = advanced.slotAt(x, y)
        if (slot > 0) { SccSoundFace.moveSlot(ctl, slot - st.instrument); return true }
        if (slotPrevious.contains(x, y) || slotNext.contains(x, y)) { SccSoundFace.moveSlot(ctl, if (slotNext.contains(x, y)) 1 else -1); return true }
        if (name.contains(x, y)) { SccSoundFace.rename(ctl); return true }
        if (previous.contains(x, y) || next.contains(x, y)) {
            st.instField = 2 + ((st.instField - 2).coerceIn(0, SccSoundMacros.COUNT - 1) + if (next.contains(x, y)) 1 else SccSoundMacros.COUNT - 1).mod(SccSoundMacros.COUNT)
            return true
        }
        for (i in controls.indices) if (controls[i].contains(x, y)) {
            st.instField = i + 2
            val actual = song.sccInstrument(st.instrument)
            val ins = actual ?: defaultSccInstrument(st.instrument)
            if (!SccSoundMacros.enabled(i, ins)) return true
            drag.begin(session, "Shape SCC sound", Target(st.instrument, i, panel.revision, rails[i].left, rails[i].right, ins, actual))
            panel.cancelTouch = cancelTouch
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val t = drag.live(session) ?: return
        val p = st.soundPanel("scc")
        if (p.advanced || st.instrument != t.slot || p.revision != t.revision || song.sccInstrument(t.slot) !== t.expected) { cancel(); return }
        val n = PanelWidgets.railValue(x, t.left, t.right, 0, SccSoundMacros.maximum(t.control))
        if (t.control == 4) { SccSoundFace.set(ctl, 4, n); return }
        val result = SccSoundMacros.apply(t.control, t.original, n)
        if (result == t.expected) return
        if (session.setSccInstrument(t.slot, result, "Shape SCC sound")) {
            t.expected = song.sccInstrument(t.slot)
            SccInstrumentView.publish(ctl)
            if (ctl.auditionHeld || ctl.editHeld) SccInstrumentView.audition(ctl)
        }
    }
    override fun move(x: Float, y: Float) { if (advancedPointer) advanced.move(x, y) else setFromX(x) }
    override fun up(x: Float, y: Float) { if (advancedPointer) advanced.up(x, y); cancel() }
    override fun cancel() {
        val p = st.soundPanel("scc")
        if (p.cancelTouch === cancelTouch) p.cancelTouch = null
        drag.finish(session)
        if (advancedPointer) { advancedPointer = false; advanced.cancel() }
    }
}
