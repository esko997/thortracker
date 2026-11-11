package org.thortracker.system.pce

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** A HuCard-like wave window: stored five-bit steps above a tactile, paged control strip. */
internal class PceSoundPainter(v: PaneView) : Painter(v) {
    private val advanced = PceInstrumentPainter(v)
    private val modes = Array(2) { Rect() }
    private val controls = Array(PceSoundMacros.COUNT) { Rect() }
    private val rails = Array(PceSoundMacros.COUNT) { Rect() }
    private val body = Rect()
    private val scope = Rect()
    private val name = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val slotPrevious = Rect()
    private val slotNext = Rect()
    private val empty = PceInstrument(name = "Empty / edit to create")
    private var advancedPointer = false
    private var cached: PceInstrument? = null
    private var cachedData: PceSound? = null
    private val texts = Array(PceSoundMacros.COUNT) { "" }
    private val titles = Array(PceSoundMacros.COUNT) { "" }
    private val hints = Array(PceSoundMacros.COUNT) { "" }
    private val values = IntArray(PceSoundMacros.COUNT)
    private var cachedListen = -1
    private var status = ""
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
                         val original: PceInstrument, var expected: PceInstrument?)
    private val drag = PanelDrag<Target>()
    private val cancelTouch: () -> Unit = { cancel() }

    private fun cache(ins: PceInstrument) {
        if (cached === ins && cachedData === song.pce && cachedListen == st.listenChannel) return
        for (i in 0 until PceSoundMacros.COUNT) {
            values[i] = if (i == 5) PceSoundMacros.listenValue(ins, st.listenChannel) else PceSoundMacros.value(i, ins)
            titles[i] = PceSoundMacros.title(i, ins)
            texts[i] = if (!PceSoundMacros.enabled(i, song.pce, ins)) "Advanced / unavailable" else PceSoundMacros.text(i, values[i], ins)
            hints[i] = PceSoundMacros.hint(i, song.pce, ins)
        }
        status = when (ins.kind) {
            PceKind.TONE -> if (song.pce.lfoControl and 3 != 0 && song.pce.lfoControl and PceRules.LFO_HALT == 0)
                "Shared LFO on: PSG 2 wave bends PSG 1" else "32 steps / 5-bit wave memory"
            PceKind.NOISE -> "Noise: PSG 5/6 only / played note sets clock"
            PceKind.SAMPLE -> "DDA ${PceSamples.rateHz(song.pce.timerReload)} Hz / root rate"
        }
        cached = ins; cachedData = song.pce; cachedListen = st.listenChannel
    }

    override fun draw(c: Surface, area: Rect) {
        val p = ink.palette
        val accent = if (p.id == Palette.CONTRAST.id) p.FOCUS else 0xFFAA89DC.toInt()
        val touch = ink.dp(48f)
        val panel = st.soundPanel("pce")
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
        val ins = song.pceInstrument(st.instrument) ?: empty
        cache(ins)
        val bank = advanced.drawSidebar(c, body, p.channel(PceInstrumentView.listenChannel(st, ins)), false)
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
        val wide = right - left > ink.dp(420f) && available >= touch * 4
        val scopeH = if (wide) minOf(available * 0.45f, ink.dp(140f)) else maxOf(0f, available - touch)
        scope.set(left, top + touch, right, top + touch + scopeH)
        drawWave(c, scope, ins, accent)
        val focused = (st.instField - 2).coerceIn(0, PceSoundMacros.COUNT - 1)
        if (wide) {
            val w = (right - left) / 3
            val h = (footer - scope.bottom) / 2
            for (i in controls.indices) controls[i].set(left + (i % 3) * w, scope.bottom + (i / 3) * h, left + (i % 3 + 1) * w, scope.bottom + (i / 3 + 1) * h)
        } else controls[focused].set(left, scope.bottom, right, footer)
        for (i in controls.indices) if (controls[i].height() > 0) drawControl(c, i, ins, accent)
        previous.set(left, footer, left + touch, area.bottom)
        next.set(right - touch, footer, right, area.bottom)
        ink.label(c, "‹", previous.centerX(), footer, touch, accent, true, align = Align.CENTER)
        ink.label(c, "›", next.centerX(), footer, touch, accent, true, align = Align.CENTER)
        ink.label(c, ink.fit(hints[focused], maxOf(0f, next.left - previous.right), ink.unit * 0.72f), previous.right,
            footer, touch, p.DIM, size = ink.unit * 0.72f)
    }

    private fun drawWave(c: Surface, r: Rect, ins: PceInstrument, accent: Int) {
        if (r.height() < ink.dp(12f)) return
        val p = ink.palette
        c.fillRect(r.left, r.top, r.right, r.bottom, Palette.mix(p.BG, accent, 0.12f))
        val labelH = minOf(ink.dp(24f), r.height() / 2)
        ink.label(c, ink.fit(status, r.width() - ink.dp(12f), ink.unit * 0.75f), r.left + ink.dp(6f), r.top, labelH, p.TEXT, size = ink.unit * 0.75f)
        val data = when (ins.kind) {
            PceKind.TONE -> song.pce.waves.getOrNull(ins.waves.values.firstOrNull() ?: ins.wave)
            PceKind.SAMPLE -> song.pce.samples.getOrNull(ins.sample)?.data
            PceKind.NOISE -> null
        } ?: return
        if (data.isEmpty()) return
        val n = minOf(64, data.size)
        val w = r.width() / n
        for (i in 0 until n) {
            val value = data[i * data.size / n]
            val y = r.bottom - (r.height() - labelH) * value / 31f
            c.fillRect(r.left + i * w, y, r.left + (i + 1) * w - ink.dp(1f), r.bottom, accent)
        }
    }

    private fun drawControl(c: Surface, i: Int, ins: PceInstrument, accent: Int) {
        val r = controls[i]
        val p = ink.palette
        val enabled = PceSoundMacros.enabled(i, song.pce, ins)
        val selected = st.instField == i + 2
        val pad = ink.dp(12f)
        val h = minOf(ink.dp(24f), r.height() * 0.5f)
        val textWidth = (r.width() - pad * 2) / 2
        ink.label(c, ink.fit(titles[i], textWidth, ink.unit * 0.8f, selected), r.left + pad, r.top, h, if (selected) p.TEXT else p.DIM, selected, size = ink.unit * 0.8f)
        ink.label(c, ink.fit(texts[i], textWidth, ink.unit * 0.8f), r.right - pad, r.top, h, if (enabled) accent else p.FAINT, align = Align.RIGHT, size = ink.unit * 0.8f)
        rails[i].set(r.left + pad, r.top + h, r.right - pad, r.bottom)
        val max = PceSoundMacros.maximum(i, song.pce, ins)
        if (enabled && values[i] >= 0) PanelWidgets.rail(ink, c, rails[i].left, rails[i].right, rails[i].centerY(),
            if (max == 0) 0f else values[i].toFloat() / max, max, accent, selected, ctl.editHeld)
        else c.line(rails[i].left, rails[i].centerY(), rails[i].right, rails[i].centerY(), p.RULE, ink.dp(2f))
        if (selected) c.fillRect(r.left, r.bottom - ink.dp(2f), r.right, r.bottom, p.FOCUS)
    }

    internal fun controlRect(i: Int): Rect = controls[i]
    internal fun modeRect(advanced: Boolean): Rect = modes[if (advanced) 1 else 0]
    internal fun navigationRect(forward: Boolean): Rect = if (forward) next else previous
    internal fun slotNavigationRect(forward: Boolean): Rect = if (forward) slotNext else slotPrevious
    override fun down(x: Float, y: Float): Boolean {
        cancel()
        for (i in 0..1) if (modes[i].contains(x, y)) { PceSoundFace.mode(ctl, i == 1); return true }
        val panel = st.soundPanel("pce")
        if (panel.advanced) {
            advancedPointer = advanced.down(x, y)
            if (advancedPointer) panel.cancelTouch = cancelTouch
            return advancedPointer
        }
        if (ctl.editHeld) { ctl.useEdit(); panel.editBlocked = true }
        val slot = advanced.slotAt(x, y)
        if (slot > 0) { PceSoundFace.moveSlot(ctl, slot - st.instrument); return true }
        if (slotPrevious.contains(x, y) || slotNext.contains(x, y)) { PceSoundFace.moveSlot(ctl, if (slotNext.contains(x, y)) 1 else -1); return true }
        if (name.contains(x, y)) { PceSoundFace.rename(ctl); return true }
        if (previous.contains(x, y) || next.contains(x, y)) {
            st.instField = 2 + ((st.instField - 2).coerceIn(0, PceSoundMacros.COUNT - 1) + if (next.contains(x, y)) 1 else PceSoundMacros.COUNT - 1).mod(PceSoundMacros.COUNT)
            return true
        }
        for (i in controls.indices) if (controls[i].contains(x, y)) {
            st.instField = i + 2
            val actual = song.pceInstrument(st.instrument)
            val ins = actual ?: defaultPceInstrument(st.instrument)
            if (!PceSoundMacros.enabled(i, song.pce, ins)) return true
            drag.begin(session, "Shape PCE sound", Target(st.instrument, i, panel.revision, rails[i].left, rails[i].right, ins, actual))
            panel.cancelTouch = cancelTouch
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val t = drag.live(session) ?: return
        val p = st.soundPanel("pce")
        if (p.advanced || st.instrument != t.slot || p.revision != t.revision || song.pceInstrument(t.slot) !== t.expected) { cancel(); return }
        val n = PanelWidgets.railValue(x, t.left, t.right, 0, PceSoundMacros.maximum(t.control, song.pce, t.original))
        if (t.control == 5) { PceSoundFace.set(ctl, 5, n); return }
        val result = PceSoundMacros.apply(t.control, song.pce, t.original, n)
        if (result == t.expected) return
        val changedKind = t.control == 0 && result.kind != t.original.kind
        if (session.setPceInstrument(t.slot, result, "Shape PCE sound")) {
            t.expected = song.pceInstrument(t.slot)
            PceInstrumentView.publish(ctl)
            if (ctl.auditionHeld) PceInstrumentView.audition(ctl)
        }
        if (changedKind) PceSoundFace.invalidate(ctl)
    }
    override fun move(x: Float, y: Float) { if (advancedPointer) advanced.move(x, y) else setFromX(x) }
    override fun up(x: Float, y: Float) { if (advancedPointer) advanced.up(x, y); cancel() }
    override fun cancel() {
        val p = st.soundPanel("pce")
        if (p.cancelTouch === cancelTouch) p.cancelTouch = null
        drag.finish(session)
        if (advancedPointer) { advancedPointer = false; advanced.cancel() }
    }
}
