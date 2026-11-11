package org.thortracker.system.saa

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Native stereo stage beside the precise editor's actual bank, never a second imitation list. */
internal class SaaSoundPainter(v: PaneView) : Painter(v) {
    private val advanced = SaaInstrumentPainter(v)
    private val modes = Array(2) { Rect() }
    private val body = Rect()
    private val nameRect = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val previousControl = Rect()
    private val nextControl = Rect()
    private val controls = Array(8) { Rect() }
    private val rails = Array(8) { Rect() }
    private val diagram = Rect()
    private val values = Array(8) { "" }
    private val indices = IntArray(8)
    private val enabled = BooleanArray(8)
    private var cached: SaaInstrument? = null
    private var cachedLane = -1
    private var cachedSong: Any? = null
    private var emptySlot = -1
    private var empty: SaaInstrument? = null
    private var title = ""
    private var titleWidth = -1f
    private var titleUnit = -1f
    private var caretName: String? = null
    private var caretPos = -1
    private var caretUnit = -1f
    private var caretLeft = 0f
    private var caretWidth = 0f
    private var advancedPointer = false
    internal var compact = false
        private set
    private class Target(val slot: Int, val revision: Int, val control: SaaSoundControl,
        val left: Float, val right: Float, val maximum: Int, var expected: SaaInstrument?)
    private val drag = PanelDrag<Target>()
    private val cancelOwned: () -> Unit = { cancel() }

    override fun draw(c: Surface, area: Rect) {
        val p = ink.palette
        val panel = SaaSoundFace.panel(ctl)
        val touch = ink.dp(48f)
        c.fillRect(area.left, area.top, area.right, area.bottom, p.BG)
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        previousControl.set(0f, 0f, 0f, 0f); nextControl.set(0f, 0f, 0f, 0f)
        val modeW = minOf(area.width() / 2, maxOf(touch * 2, ink.unit * 7))
        for (i in 0..1) {
            modes[i].set(area.left + i * modeW, area.top, area.left + (i + 1) * modeW, area.top + touch)
            PanelWidgets.segment(ink, c, modes[i], panel.advanced == (i == 1), p.FOCUS)
            ink.label(c, if (i == 0) "Sound" else "Advanced", modes[i].centerX(), area.top, touch, p.TEXT, true, align = Align.CENTER)
        }
        if (st.instField == -1) ink.outline(c, modes[if (panel.advanced) 1 else 0], p.FOCUS, ink.dp(2f))
        body.set(area.left, area.top + touch, area.right, area.bottom)
        if (panel.advanced) { advanced.draw(c, body); return }
        val actual = SaaInstrumentView.sound(song, st)
        if (emptySlot != st.instrument) { emptySlot = st.instrument; empty = defaultSaaInstrument(st.instrument) }
        val ins = actual ?: empty!!
        val list = SaaSoundControl.entries
        val bank = advanced.drawSidebar(c, body, p.channel(st.listenChannel), false)
        val pad = ink.dp(8f)
        val left = bank.right + pad
        val right = area.right - pad
        val width = right - left
        nextSlot.set(right - touch, bank.top, right, bank.top + touch)
        previousSlot.set(nextSlot.left - touch, bank.top, nextSlot.left, nextSlot.bottom)
        nameRect.set(left, bank.top, previousSlot.left, previousSlot.bottom)
        if (cached !== ins || cachedLane != st.listenChannel || cachedSong !== song || titleWidth != nameRect.width() || titleUnit != ink.unit) {
            for (i in list.indices) {
                val control = list[i]
                enabled[i] = SaaSoundMacros.enabled(control, ins, st.listenChannel)
                indices[i] = SaaSoundMacros.value(control, ins, st.listenChannel) ?: -1
                values[i] = if (!enabled[i]) when (control) {
                    SaaSoundControl.GRAIN -> "Choose Noise first"
                    SaaSoundControl.MOTION -> "Use lane 3 or 6"
                    else -> "Unavailable"
                } else if (indices[i] < 0) "Custom" else SaaSoundMacros.label(control, indices[i], song.saa)
            }
            title = ink.fit(if (actual == null) "Empty: edit to create" else ins.name.ifEmpty { "Unnamed" }, nameRect.width(), ink.unit, true)
            cached = ins; cachedLane = st.listenChannel; cachedSong = song; titleWidth = nameRect.width(); titleUnit = ink.unit
        }
        ink.label(c, title, left, bank.top, touch, p.TEXT, true)
        ink.label(c, "-", previousSlot.centerX(), bank.top, touch, p.TEXT, true, align = Align.CENTER)
        ink.label(c, "+", nextSlot.centerX(), bank.top, touch, p.TEXT, true, align = Align.CENTER)
        if (st.instField == 1) {
            ink.outline(c, nameRect, p.FOCUS, ink.dp(2f))
            if (caretName != ins.name || caretPos != st.textPos || caretUnit != ink.unit) {
                caretLeft = ink.labelWidth(ins.name.take(st.textPos).padEnd(st.textPos, ' '), ink.unit, true)
                caretWidth = maxOf(ink.labelWidth(ins.name.getOrNull(st.textPos)?.toString() ?: " ", ink.unit, true), ink.unit * 0.5f)
                caretName = ins.name; caretPos = st.textPos; caretUnit = ink.unit
            }
            if (actual != null && left + caretLeft + caretWidth <= nameRect.right)
                c.fillRect(left + caretLeft, nameRect.bottom - ink.dp(7f), left + caretLeft + caretWidth,
                    nameRect.bottom - ink.dp(4f), if (ctl.editHeld) p.WRITE else p.FOCUS)
        }
        val top = nameRect.bottom
        val bottom = body.bottom - ink.unit * 1.5f
        compact = width < ink.dp(360f) || bottom - top < ink.dp(225f)
        if (compact) {
            val i = (st.instField - 2).coerceIn(0, list.lastIndex)
            previousControl.set(left, top, left + touch, bottom)
            nextControl.set(right - touch, top, right, bottom)
            controls[i].set(previousControl.right, top, nextControl.left, bottom)
            drawControl(c, i, ins, true)
            ink.label(c, "<", previousControl.centerX(), top, bottom - top, p.TEXT, true, align = Align.CENTER)
            ink.label(c, ">", nextControl.centerX(), top, bottom - top, p.TEXT, true, align = Align.CENTER)
        } else {
            // One stereo stage spans the panel; two unequal columns separate voice from chip resources.
            diagram.set(left, top, right, top + ink.dp(58f))
            drawDiagram(c, diagram, ins)
            val start = diagram.bottom + pad
            val split = left + width * 0.53f
            val h = (bottom - start) / 3
            for (i in list.indices) {
                val column = if (i < 3) 0 else 1
                val row = i % 3
                controls[i].set(if (column == 0) left else split + pad, start + row * h,
                    if (column == 0) split - pad else right, start + (row + 1) * h)
                drawControl(c, i, ins, false)
            }

        }
        val control = list[(st.instField - 2).coerceIn(0, list.lastIndex)]
        ink.label(c, ink.fit(SaaSoundMacros.hint(control), width, ink.unit * 0.75f), left, bottom, ink.unit * 1.5f, p.DIM, size = ink.unit * 0.75f)
    }

    private fun drawControl(c: Surface, i: Int, ins: SaaInstrument, illustrated: Boolean) {
        val control = (SaaSoundControl.entries)[i]
        val r = controls[i]
        val p = ink.palette
        val h = minOf(ink.dp(23f), r.height() * 0.28f)
        ink.label(c, control.label, r.left, r.top, h, p.TEXT, true, ink.unit * 0.9f)
        ink.label(c, ink.fit(values[i], r.width(), ink.unit * 0.8f), r.left, r.top + h, h,
            if (enabled[i]) p.FOCUS else p.DIM, size = ink.unit * 0.8f)
        if (illustrated && r.height() > ink.dp(95f)) {
            diagram.set(r.left + ink.dp(4f), r.top + h * 2 + ink.dp(6f), r.right - ink.dp(4f), r.bottom - ink.dp(32f))
            drawDiagram(c, diagram, ins)
        }
        val rail = rails[i]
        rail.set(r.left + ink.dp(8f), r.bottom - ink.dp(15f), r.right - ink.dp(8f), r.bottom - ink.dp(15f))
        if (enabled[i] && indices[i] >= 0) {
            val max = SaaSoundFace.maximum(ctl, control)
            PanelWidgets.rail(ink, c, rail.left, rail.right, rail.top, indices[i].toFloat() / max.coerceAtLeast(1), max,
                p.FOCUS, st.instField == i + 2, ctl.editHeld || drag.target != null)
        } else c.line(rail.left, rail.top, rail.right, rail.top, p.FAINT, ink.dp(2f))
        if (st.instField == i + 2) c.line(r.left, r.bottom, r.right, r.bottom, p.FOCUS, ink.dp(2f))
    }
    private fun drawDiagram(c: Surface, r: Rect, ins: SaaInstrument) {
        // Stereo stage: two native amplitudes, with the triple's shared noise bus beneath.
        val centre = r.centerX()
        val half = r.width() * 0.42f
        val cy = r.centerY()
        c.line(centre - half, cy, centre + half, cy, ink.palette.RULE, ink.dp(3f))
        c.fillRect(centre - half * ins.panLeft / 15f, cy - ink.dp(7f), centre, cy + ink.dp(7f), ink.palette.FOCUS)
        c.fillRect(centre, cy - ink.dp(7f), centre + half * ins.panRight / 15f, cy + ink.dp(7f), ink.palette.PLAYHEAD)
        ink.label(c, "L", r.left, r.top, r.height(), ink.palette.TEXT, true)
        ink.label(c, "R", r.right, r.top, r.height(), ink.palette.TEXT, true, align = Align.RIGHT)
        val base = st.listenChannel / 3 * 3
        for (i in 0..2) {
            val x = r.left + r.width() * (i + 0.5f) / 3
            c.fillCircle(x, r.bottom - ink.dp(5f), ink.dp(3f), if (base + i == st.listenChannel) ink.palette.WRITE else ink.palette.RULE)
        }

    }
    internal fun modeRect(advanced: Boolean) = modes[if (advanced) 1 else 0]
    internal fun controlRect(index: Int) = controls[index]
    internal fun navigationRect(forward: Boolean) = if (forward) nextControl else previousControl
    internal fun slotNavigationRect(forward: Boolean) = if (forward) nextSlot else previousSlot
    internal fun valueX(index: Int, fraction: Float) = rails[index].let { it.left + it.width() * fraction }
    internal fun nameTarget() = nameRect

    override fun down(x: Float, y: Float): Boolean {
        cancel()
        val p = SaaSoundFace.panel(ctl)
        for (i in 0..1) if (modes[i].contains(x, y)) { SaaSoundFace.selectMode(ctl, i == 1); return true }
        if (p.advanced) { advancedPointer = advanced.down(x, y); if (advancedPointer) p.cancelTouch = cancelOwned; return advancedPointer }
        if (ctl.editHeld) { ctl.useEdit(); p.editBlocked = true }
        val slot = advanced.slotAt(x, y)
        if (slot > 0) { if (slot == st.instrument) SaaInstrumentView.publish(ctl) else SaaSoundFace.moveSlot(ctl, slot - st.instrument); return true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) { SaaSoundFace.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true }
        if (nameRect.contains(x, y)) {
            st.instField = 1
            val chosen = st.instrument
            val expected = SaaInstrumentView.sound(song, st)
            val original = SaaSoundFace.shown(ctl)
            p.invalidate()
            val revision = p.revision
            session.requestText("Sound name", original.name, org.thortracker.editor.EditorSession.MAX_NAME) { text ->
                if (st.instrument == chosen && p.revision == revision && SaaInstrumentView.sound(song, st) === expected)
                    session.setSaaInstrument(chosen, original.copy(name = text), "Rename sound")
            }
            return true
        }
        val list = SaaSoundFace.controls(ctl)
        if (previousControl.contains(x, y) || nextControl.contains(x, y)) {
            st.instField = 2 + ((st.instField - 2).coerceIn(0, list.lastIndex) + if (nextControl.contains(x, y)) 1 else list.lastIndex).mod(list.size)
            return true
        }
        val ins = SaaSoundFace.shown(ctl)
        for (i in list.indices) if (controls[i].contains(x, y)) {
            st.instField = i + 2
            val control = list[i]
            if (!(SaaSoundMacros.enabled(control, ins, st.listenChannel))) return true
            if (control == SaaSoundControl.LANE) {
                SaaSoundFace.set(ctl, control, PanelWidgets.railValue(x, rails[i].left, rails[i].right, 0, SaaSoundFace.maximum(ctl, control)))
                return true
            }
            drag.begin(session, "Set ${control.label}", Target(st.instrument, p.revision, control, rails[i].left, rails[i].right,
                SaaSoundFace.maximum(ctl, control), SaaInstrumentView.sound(song, st)))
            p.cancelTouch = cancelOwned
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val t = drag.live(session) ?: return
        val p = SaaSoundFace.panel(ctl)
        if (p.advanced || p.revision != t.revision || st.instrument != t.slot || SaaInstrumentView.sound(song, st) !== t.expected) { cancel(); return }
        SaaSoundFace.set(ctl, t.control, PanelWidgets.railValue(x, t.left, t.right, 0, t.maximum))
        t.expected = SaaInstrumentView.sound(song, st)
    }
    override fun move(x: Float, y: Float) { if (advancedPointer) advanced.move(x, y) else setFromX(x) }
    override fun up(x: Float, y: Float) { if (advancedPointer) advanced.up(x, y); cancel() }
    override fun cancel() {
        val p = SaaSoundFace.panel(ctl)
        if (p.cancelTouch === cancelOwned) p.cancelTouch = null
        drag.finish(session)
        if (advancedPointer) { advancedPointer = false; advanced.cancel() }
    }
}
