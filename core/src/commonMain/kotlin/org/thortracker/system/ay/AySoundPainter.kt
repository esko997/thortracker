package org.thortracker.system.ay

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Home-computer patchboard: source keys and stepped contours beside the chip's shared buzzer controls. */
internal class AySoundPainter(v: PaneView) : Painter(v) {
    private val advanced = AyInstrumentPainter(v)
    private val modes = Array(2) { Rect() }
    private val controls = Array(5) { Rect() }
    private val rails = Array(5) { Rect() }
    private val body = Rect()
    private val name = Rect()
    private val listen = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val values = Array(5) { "" }
    private val indices = IntArray(5)
    private val enabled = BooleanArray(5)
    private var cached: AyInstrument? = null
    private var emptySlot = -1
    private var emptySound = AyInstrument()
    private var advancedPointer = false
    private var shownSlot = -1
    internal var compact = false
        private set
    private class Target(val control: AySoundControl, val slot: Int, val revision: Int, val left: Float, val right: Float,
                         val original: AyInstrument, var expected: AyInstrument?)
    private val drag = PanelDrag<Target>()
    private val cancelOwned: () -> Unit = { cancel() }
    private fun actual() = AyInstrumentView.sound(song, st)
    private fun shown(): AyInstrument {
        actual()?.let { return it }
        if (emptySlot != st.instrument) { emptySlot = st.instrument; emptySound = defaultAyInstrument(st.instrument) }
        return emptySound
    }
    private fun cache(ins: AyInstrument) {
        if (cached === ins) return
        for (control in AySoundControl.entries) {
            val i = control.ordinal
            indices[i] = AySoundMacros.value(control, ins) ?: -1
            enabled[i] = AySoundMacros.enabled(control, ins)
            values[i] = when {
                !enabled[i] -> if (control == AySoundControl.CONTOUR) "Buzzer owns level" else "Buzzer is off"
                indices[i] < 0 -> if (control == AySoundControl.REGISTER) "Manual / Custom" else "Custom"
                else -> AySoundMacros.label(control, indices[i])
            }
        }
        cached = ins
    }
    override fun draw(c: Surface, area: Rect) {
        val p = ink.palette
        val panel = st.soundPanel("ay")
        if (shownSlot != st.instrument) { cancel(); shownSlot = st.instrument }
        val touch = ink.dp(48f)
        val modeWidth = minOf((area.width() - touch) / 2, maxOf(touch * 2, ink.unit * 7))
        for (i in 0..1) {
            modes[i].set(area.left + i * modeWidth, area.top, area.left + (i + 1) * modeWidth, area.top + touch)
            PanelWidgets.segment(ink, c, modes[i], panel.advanced == (i == 1), p.FOCUS)
            ink.label(c, if (i == 0) "Sound" else "Advanced", modes[i].centerX(), area.top, touch, p.TEXT, true, align = Align.CENTER)
        }
        if (st.instField == -1) ink.outline(c, modes[if (panel.advanced) 1 else 0], p.FOCUS, ink.dp(2f))
        listen.set(area.right - touch, area.top, area.right, area.top + touch)
        ink.label(c, "Listen", listen.centerX(), listen.top, touch / 2, p.TEXT, size = ink.unit * 0.7f, align = Align.CENTER)
        ink.label(c, "${('A'.code + st.listenChannel).toChar()}", listen.centerX(), listen.top + touch / 2, touch / 2, p.channel(st.listenChannel), true, align = Align.CENTER)
        body.set(area.left, area.top + touch, area.right, area.bottom)
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        previous.set(0f, 0f, 0f, 0f); next.set(0f, 0f, 0f, 0f)
        if (panel.advanced) { advanced.draw(c, body); return }
        val bank = advanced.drawSidebar(c, body, false)
        val pad = ink.dp(8f)
        val left = bank.right + pad
        val right = area.right - pad
        val ins = shown()
        cache(ins)
        val accent = p.channel(st.listenChannel)
        val board = if (p.id == Palette.CONTRAST.id) p.BG else Palette.mix(p.BG, p.TEXT, 0.08f)
        c.fillRect(bank.right, bank.top, area.right, area.bottom, board)
        nextSlot.set(right - touch, bank.top, right, bank.top + touch)
        previousSlot.set(nextSlot.left - touch, bank.top, nextSlot.left, nextSlot.bottom)
        name.set(left, bank.top, previousSlot.left, nextSlot.bottom)
        ink.label(c, ink.fit(if (actual() == null) "Empty / edit" else ins.name.ifEmpty { "Unnamed" }, name.width(), ink.unit, true), left, name.top, touch, p.TEXT, true)
        ink.label(c, "-", previousSlot.centerX(), previousSlot.top, touch, p.TEXT, true, align = Align.CENTER)
        ink.label(c, "+", nextSlot.centerX(), nextSlot.top, touch, p.TEXT, true, align = Align.CENTER)
        if (st.instField == 1) {
            ink.outline(c, name, p.FOCUS, ink.dp(2f))
            ink.label(c, "Letter ${st.textPos + 1}", name.left, name.bottom - ink.dp(15f), ink.dp(14f), p.FOCUS, size = ink.unit * 0.65f)
        }
        compact = right - left < ink.dp(480f) || area.bottom - name.bottom < ink.dp(240f)
        val bottom = area.bottom - ink.dp(34f)
        val top = name.bottom
        if (compact) {
            val i = (st.instField - 2).coerceIn(0, 4)
            previous.set(left, top, left + touch, bottom)
            next.set(right - touch, top, right, bottom)
            controls[i].set(previous.right, top, next.left, bottom)
            drawKey(c, i, accent)
            ink.label(c, "<", previous.centerX(), top, previous.height(), p.TEXT, true, align = Align.CENTER)
            ink.label(c, ">", next.centerX(), top, next.height(), p.TEXT, true, align = Align.CENTER)
        } else {
            val width = (right - left) / 5
            for (i in 0..4) {
                controls[i].set(left + i * width + pad / 2, top, left + (i + 1) * width - pad / 2, bottom)
                drawKey(c, i, accent)
            }
        }
        val control = AySoundControl.entries[(st.instField - 2).coerceIn(0, 4)]
        ink.label(c, ink.fit(control.hint, right - left, ink.unit * 0.7f), left, bottom, ink.dp(17f), p.TEXT, size = ink.unit * 0.7f)
        ink.label(c, ink.fit("Shared buzzer + noise: latest note wins", right - left, ink.unit * 0.7f), left, bottom + ink.dp(17f), ink.dp(17f), p.DIM, size = ink.unit * 0.7f)
    }
    private fun drawKey(c: Surface, i: Int, accent: Int) {
        val r = controls[i]
        if (r.width() <= 0 || r.height() <= 0) return
        val p = ink.palette
        val control = AySoundControl.entries[i]
        val focused = st.instField == i + 2
        c.fillRect(r.left, r.top, r.right, r.bottom, if (focused) p.CURSOR_FILL else p.LANE)
        c.fillRect(r.left, r.top, r.right, r.top + ink.dp(4f), if (enabled[i]) accent else p.FAINT)
        ink.label(c, ink.fit(control.label, r.width() - ink.dp(8f), ink.unit * 0.9f, true), r.left + ink.dp(4f), r.top + ink.dp(5f), ink.dp(24f), p.TEXT, true, ink.unit * 0.9f)
        ink.label(c, ink.fit(values[i], r.width() - ink.dp(8f), ink.unit * 0.8f), r.left + ink.dp(4f), r.top + ink.dp(29f), ink.dp(24f), p.TEXT, size = ink.unit * 0.8f)
        // Native source gates, macro steps and chip-resolution R13 ramps, never a fabricated audio waveform.
        val ins = shown()
        val y = r.top + ink.dp(61f)
        val h = r.height() - ink.dp(93f)
        if (h > ink.dp(12f)) {
            val env = if (control == AySoundControl.COLOUR) ins.noise.values else ins.volume.values
            val n = env.size
            if (control == AySoundControl.SOURCE) {
                val gates = ins.mixer.values.firstOrNull() ?: 1
                for (source in 0..1) {
                    val x = r.left + r.width() * (source + 1) / 3
                    val col = if (gates and (1 shl source) != 0) accent else p.FAINT
                    c.strokeCircle(x, y + h * 0.25f, ink.dp(6f), col, ink.dp(2f))
                    c.line(x, y + h * 0.25f + ink.dp(6f), x, y + h * 0.65f, col, ink.dp(2f))
                    c.line(x, y + h * 0.65f, r.centerX(), y + h * 0.65f, col, ink.dp(2f))
                    ink.label(c, if (source == 0) "Tone" else "Hiss", x, y + h * 0.8f, ink.dp(16f), p.TEXT, size = ink.unit * 0.65f, align = Align.CENTER)
                }
                c.fillCircle(r.centerX(), y + h * 0.65f, ink.dp(4f), if (ins.envelope.on) accent else p.DIM)
            } else if (n > 0 && (control == AySoundControl.CONTOUR || control == AySoundControl.COLOUR)) {
                for (k in 0 until n) {
                    val x = r.left + ink.dp(8f) + (r.width() - ink.dp(16f)) * k / n
                    val x2 = r.left + ink.dp(8f) + (r.width() - ink.dp(16f)) * (k + 1) / n
                    val yy = y + h * (1f - env[k] / (if (control == AySoundControl.COLOUR) 31f else 15f))
                    c.line(x, yy, x2, yy, accent, ink.dp(2f))
                }
            } else if (control == AySoundControl.BUZZ || control == AySoundControl.REGISTER) {
                val levels = shapeLevels(ins)
                for (k in levels.indices) {
                    val x = r.left + ink.dp(8f) + (r.width() - ink.dp(16f)) * k / levels.size
                    val x2 = r.left + ink.dp(8f) + (r.width() - ink.dp(16f)) * (k + 1) / levels.size
                    val yy = y + h * (1f - levels[k] / (AyInstrumentView.rampSteps(song.ay.variant) - 1f))
                    c.line(x, yy, x2, yy, if (ins.envelope.on) accent else p.FAINT, ink.dp(2f))
                }
            }
        }
        rails[i].set(r.left + ink.dp(12f), r.bottom - ink.dp(17f), r.right - ink.dp(12f), r.bottom - ink.dp(17f))
        val rail = rails[i]
        if (enabled[i] && indices[i] >= 0) PanelWidgets.rail(ink, c, rail.left, rail.right, rail.top, indices[i].toFloat() / control.maximum, control.maximum, accent, focused, ctl.editHeld || drag.target != null)
        else c.line(rail.left, rail.top, rail.right, rail.top, p.FAINT, ink.dp(2f))
        if (focused) ink.outline(c, r, p.FOCUS, ink.dp(2f))
    }
    private var shapeKey = -1
    private var shape = IntArray(0)
    private fun shapeLevels(ins: AyInstrument): IntArray {
        val steps = AyInstrumentView.rampSteps(song.ay.variant)
        val key = ins.envelope.shape * 64 + steps
        if (shapeKey != key) { shapeKey = key; shape = AyInstrumentView.shapeLevels(ins.envelope.shape, steps, 2) }
        return shape
    }
    internal fun modeRect(isAdvanced: Boolean) = modes[if (isAdvanced) 1 else 0]
    internal fun controlRect(i: Int) = controls[i].takeIf { it.width() > 0 }
    internal fun navigationRect(forward: Boolean) = (if (forward) next else previous).takeIf { it.width() > 0 }
    internal fun slotNavigationRect(forward: Boolean) = if (forward) nextSlot else previousSlot
    internal fun listenRect() = listen
    internal fun slotRect(slot: Int) = advanced.slotBounds(slot)
    override fun down(x: Float, y: Float): Boolean {
        cancel()
        for (i in 0..1) if (modes[i].contains(x, y)) { AySoundFace.selectMode(ctl, i == 1); return true }
        val panel = st.soundPanel("ay")
        if (listen.contains(x, y)) { AySoundFace.listen(ctl, 1); return true }
        if (panel.advanced) { advancedPointer = advanced.down(x, y); if (advancedPointer) panel.cancelTouch = cancelOwned; return advancedPointer }
        if (ctl.editHeld) { ctl.useEdit(); panel.editBlocked = true }
        val slot = advanced.slotAt(x, y)
        if (slot > 0) { AySoundFace.moveSlot(ctl, slot - st.instrument); AyInstrumentView.publish(ctl); return true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) { AySoundFace.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true }
        if (name.contains(x, y)) {
            st.instField = 1
            val chosen = st.instrument
            val expected = actual()
            val original = shown()
            val panel = st.soundPanel("ay")
            panel.invalidate()
            val revision = panel.revision
            session.requestText("Sound name", original.name, AyInstrumentView.NAME_MAX) { text ->
                if (st.instrument == chosen && panel.revision == revision && actual() === expected)
                    session.setAyInstrument(chosen, original.copy(name = text), "Rename sound")
            }
            return true
        }
        if (previous.contains(x, y) || next.contains(x, y)) {
            st.instField = 2 + ((st.instField - 2).coerceIn(0, 4) + if (next.contains(x, y)) 1 else 4).mod(5)
            return true
        }
        for (control in AySoundControl.entries) {
            val i = control.ordinal
            if (!controls[i].contains(x, y)) continue
            st.instField = i + 2
            val ins = shown()
            if (!AySoundMacros.enabled(control, ins)) return true
            drag.begin(session, "Set ${control.label}", Target(control, st.instrument, panel.revision, rails[i].left, rails[i].right, ins, actual()))
            panel.cancelTouch = cancelOwned
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val t = drag.live(session) ?: return
        if (!AySoundFace.friendly(st) || st.instrument != t.slot || st.soundPanel("ay").revision != t.revision || actual() !== t.expected) { cancel(); return }
        val n = PanelWidgets.railValue(x, t.left, t.right, 0, t.control.maximum)
        val ins = AySoundMacros.apply(t.control, t.original, n)
        if (ins == t.expected || (t.expected == null && ins === t.original)) return
        if (session.setAyInstrument(t.slot, ins, "Set ${t.control.label}")) AySoundFace.heard(ctl)
        t.expected = actual()
    }
    override fun move(x: Float, y: Float) { if (advancedPointer) advanced.move(x, y) else setFromX(x) }
    override fun up(x: Float, y: Float) { if (advancedPointer) advanced.up(x, y); cancel() }
    override fun cancel() {
        if (st.soundPanel("ay").cancelTouch === cancelOwned) st.soundPanel("ay").cancelTouch = null
        drag.finish(session)
        if (advancedPointer) { advancedPointer = false; advanced.cancel() }
    }
}
