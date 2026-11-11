package org.thortracker.system.cbm

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Native gate timing deck beside the precise editor's actual bank, never a second imitation list. */
internal class CbmSoundPainter(v: PaneView) : Painter(v) {
    private val advanced = CbmInstrumentPainter(v)
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
    private var cached: CbmInstrument? = null
    private var cachedLane = -1
    private var cachedSong: Any? = null
    private var emptySlot = -1
    private var empty: CbmInstrument? = null
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
    private class Target(val slot: Int, val revision: Int, val control: CbmSoundControl,
        val left: Float, val right: Float, val maximum: Int, val original: CbmInstrument, var expected: CbmInstrument?)
    private val drag = PanelDrag<Target>()
    private val cancelOwned: () -> Unit = { cancel() }

    override fun draw(c: Surface, area: Rect) {
        val p = ink.palette
        val panel = CbmSoundFace.panel(ctl)
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
        val actual = CbmInstrumentView.sound(song, st)
        if (emptySlot != st.instrument) { emptySlot = st.instrument; empty = defaultCbmInstrument(st.instrument) }
        val ins = actual ?: empty!!
        val list = CbmSoundMacros.controls(song.cbm.chip, ins)
        val bank = advanced.drawSidebar(c, body, p.channel(CbmInstrumentView.listenChannel(song, st, actual)), false)
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
                enabled[i] = CbmSoundMacros.enabled(control, song.cbm, ins)
                indices[i] = CbmSoundFace.value(ctl, control) ?: -1
                values[i] = if (!enabled[i]) when (control) {
                    CbmSoundControl.LANE -> "Voice 2 only"
                    CbmSoundControl.GATE -> "Mixed: use Advanced"
                    CbmSoundControl.SAMPLE -> "Add in Advanced"
                    CbmSoundControl.LOOP_START -> "Turn Repeat on"
                    else -> "Unavailable"
                } else if (indices[i] < 0) "Custom" else CbmSoundMacros.label(control, indices[i], song.cbm)
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
        compact = width < ink.dp(360f) || bottom - top < maxOf(ink.dp(180f), touch * (list.size - 1))
        if (compact) {
            val i = (st.instField - 2).coerceIn(0, list.lastIndex)
            previousControl.set(left, top, left + touch, bottom)
            nextControl.set(right - touch, top, right, bottom)
            controls[i].set(previousControl.right, top, nextControl.left, bottom)
            drawControl(c, i, ins, true)
            ink.label(c, "<", previousControl.centerX(), top, bottom - top, p.TEXT, true, align = Align.CENTER)
            ink.label(c, ">", nextControl.centerX(), top, bottom - top, p.TEXT, true, align = Align.CENTER)
        } else {
            // Gate / master strip dominates; audition and recording settings stay compact alongside it.
            val split = left + width * 0.56f
            val first = 0
            controls[first].set(left, top, split - pad, bottom)
            drawControl(c, first, ins, true)
            val h = (bottom - top) / (list.size - 1)
            for (i in 1 until list.size) {
                controls[i].set(split + pad, top + (i - 1) * h, right, top + i * h)
                drawControl(c, i, ins, false)
            }

        }
        val control = list[(st.instField - 2).coerceIn(0, list.lastIndex)]
        ink.label(c, ink.fit(CbmSoundMacros.hint(control, song.cbm.chip), width, ink.unit * 0.75f), left, bottom, ink.unit * 1.5f, p.DIM, size = ink.unit * 0.75f)
    }

    private fun drawControl(c: Surface, i: Int, ins: CbmInstrument, illustrated: Boolean) {
        val control = (CbmSoundMacros.controls(song.cbm.chip, ins))[i]
        val r = controls[i]
        val p = ink.palette
        val h = minOf(ink.dp(23f), r.height() * 0.28f)
        ink.label(c, control.label, r.left, r.top, h, p.TEXT, true, ink.unit * 0.9f)
        ink.label(c, ink.fit(values[i], r.width(), ink.unit * 0.8f), r.left, r.top + h, h,
            if (enabled[i]) p.FOCUS else p.DIM, size = ink.unit * 0.8f)
        if (illustrated && r.height() > ink.dp(95f)) {
            diagram.set(r.left + ink.dp(4f), r.top + h * 2 + ink.dp(6f), r.right - ink.dp(4f), r.bottom - ink.dp(32f))
            drawDiagram(c, diagram, ins, control)
        }
        val rail = rails[i]
        rail.set(r.left + ink.dp(8f), r.bottom - ink.dp(15f), r.right - ink.dp(8f), r.bottom - ink.dp(15f))
        if (enabled[i] && indices[i] >= 0) {
            val max = CbmSoundFace.maximum(ctl, control)
            PanelWidgets.rail(ink, c, rail.left, rail.right, rail.top, indices[i].toFloat() / max.coerceAtLeast(1), max,
                p.FOCUS, st.instField == i + 2, ctl.editHeld || drag.target != null)
        } else c.line(rail.left, rail.top, rail.right, rail.top, p.FAINT, ink.dp(2f))
        if (st.instField == i + 2) c.line(r.left, r.bottom, r.right, r.bottom, p.FOCUS, ink.dp(2f))
    }
    private fun drawDiagram(c: Surface, r: Rect, ins: CbmInstrument, control: CbmSoundControl) {
        // A native timing strip: master volume and gate are deliberately distinct programs.
        val master = control == CbmSoundControl.MASTER
        val recording = ins.kind == CbmKind.SAMPLE
        val wave = if (recording) song.cbm.samples.getOrNull(ins.sample)?.data
            else if (master) ins.volume.values else ins.waves.values
        val n = wave?.size ?: 0
        val middle = r.top + r.height() * 0.5f
        val max = if (recording) 8f else if (master) song.cbm.chip.maxVolume.toFloat() else song.cbm.chip.maxWave.toFloat()
        for (k in 0 until 32) {
            val v = if (n == 0) (if (master) song.cbm.volume else ins.wave) else wave!![k * n / 32]
            val x = r.left + r.width() * k / 32
            val y = r.bottom - ink.dp(6f) - (r.height() - ink.dp(12f)) * v / max
            c.line(x, y, x + r.width() / 32, y, ink.palette.FOCUS, ink.dp(2f))
            if (k % 4 == 0) c.line(x, middle, x, r.bottom, ink.palette.RULE, ink.dp(1f))
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
        val p = CbmSoundFace.panel(ctl)
        for (i in 0..1) if (modes[i].contains(x, y)) { CbmSoundFace.selectMode(ctl, i == 1); return true }
        if (p.advanced) { advancedPointer = advanced.down(x, y); if (advancedPointer) p.cancelTouch = cancelOwned; return advancedPointer }
        if (ctl.editHeld) { ctl.useEdit(); p.editBlocked = true }
        val slot = advanced.slotAt(x, y)
        if (slot > 0) { if (slot == st.instrument) CbmInstrumentView.publish(ctl) else CbmSoundFace.moveSlot(ctl, slot - st.instrument); return true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) { CbmSoundFace.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true }
        if (nameRect.contains(x, y)) { st.instField = 1; return true }
        val list = CbmSoundFace.controls(ctl)
        if (previousControl.contains(x, y) || nextControl.contains(x, y)) {
            st.instField = 2 + ((st.instField - 2).coerceIn(0, list.lastIndex) + if (nextControl.contains(x, y)) 1 else list.lastIndex).mod(list.size)
            return true
        }
        val ins = CbmSoundFace.shown(ctl)
        for (i in list.indices) if (controls[i].contains(x, y)) {
            st.instField = i + 2
            val control = list[i]
            if (!(CbmSoundMacros.enabled(control, song.cbm, ins))) return true
            if (control == CbmSoundControl.LANE) {
                CbmSoundFace.set(ctl, control, PanelWidgets.railValue(x, rails[i].left, rails[i].right, 0, CbmSoundFace.maximum(ctl, control)))
                return true
            }
            drag.begin(session, "Set ${control.label}", Target(st.instrument, p.revision, control, rails[i].left, rails[i].right,
                CbmSoundFace.maximum(ctl, control), ins, CbmInstrumentView.sound(song, st)))
            p.cancelTouch = cancelOwned
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val t = drag.live(session) ?: return
        val p = CbmSoundFace.panel(ctl)
        if (p.advanced || p.revision != t.revision || st.instrument != t.slot || CbmInstrumentView.sound(song, st) !== t.expected) { cancel(); return }
        CbmSoundFace.set(ctl, t.control, PanelWidgets.railValue(x, t.left, t.right, 0, t.maximum), t.original)
        t.expected = CbmInstrumentView.sound(song, st)
    }
    override fun move(x: Float, y: Float) { if (advancedPointer) advanced.move(x, y) else setFromX(x) }
    override fun up(x: Float, y: Float) { if (advancedPointer) advanced.up(x, y); cancel() }
    override fun cancel() {
        val p = CbmSoundFace.panel(ctl)
        if (p.cancelTouch === cancelOwned) p.cancelTouch = null
        drag.finish(session)
        if (advancedPointer) { advancedPointer = false; advanced.cancel() }
    }
}
