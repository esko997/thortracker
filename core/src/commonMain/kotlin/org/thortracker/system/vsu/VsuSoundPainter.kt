package org.thortracker.system.vsu

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.system.panel.PixelLabels
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Two red optical viewports: stored wave on the left, actual hardware envelope on the right. */
internal class VsuSoundPainter(v: PaneView) : Painter(v) {
    private val advanced = VsuInstrumentPainter(v)
    private val modes = Array(2) { Rect() }
    private val body = Rect()
    private val name = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val controls = Array(VsuSoundMacros.COUNT) { Rect() }
    private val rails = Array(VsuSoundMacros.COUNT) { Rect() }
    private val values = Array(VsuSoundMacros.COUNT) { "" }
    private val hints = Array(VsuSoundMacros.COUNT) { "" }
    private val indices = IntArray(VsuSoundMacros.COUNT)
    private val enabled = BooleanArray(VsuSoundMacros.COUNT)
    private val eye = Rect()
    private var cached: VsuInstrument? = null
    private var cachedLane = -1
    private var emptySlot = -1
    private var empty: VsuInstrument? = null
    private var caretName: String? = null
    private var caretPos = -1
    private var caretUnit = -1f
    private var caretLeft = 0f
    private var caretWidth = 0f
    private var advancedPointer = false
    private class Target(val slot: Int, val lane: Int, val revision: Int, val control: Int, val original: VsuInstrument,
        var expected: VsuInstrument?, val left: Float, val right: Float)
    private val drag = PanelDrag<Target>()
    private val cancelTouch: () -> Unit = { cancel() }
    internal var compact = false
        private set

    override fun draw(c: Surface, area: Rect) {
        val p = ink.palette
        val panel = st.soundPanel("vsu")
        val touch = ink.dp(48f)
        val red = if (p.id == Palette.CONTRAST.id) p.FOCUS else 0xFFFF625D.toInt()
        c.fillRect(area.left, area.top, area.right, area.bottom, p.BG)
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        previous.set(0f, 0f, 0f, 0f); next.set(0f, 0f, 0f, 0f)
        val mw = minOf(area.width() / 2, touch * 2.4f)
        for (i in 0..1) {
            modes[i].set(area.left + i * mw, area.top, area.left + (i + 1) * mw, area.top + touch)
            PanelWidgets.segment(ink, c, modes[i], panel.advanced == (i == 1), red)
            ink.label(c, if (i == 0) "Sound" else "Advanced", modes[i].centerX(), area.top, touch, p.TEXT, true, align = Align.CENTER)
        }
        if (st.instField == -1) ink.outline(c, modes[if (panel.advanced) 1 else 0], p.FOCUS, ink.dp(2f))
        body.set(area.left, area.top + touch, area.right, area.bottom)
        if (panel.advanced) { advanced.draw(c, body); return }
        val actual = VsuInstrumentView.sound(song, st)
        if (empty == null || emptySlot != st.instrument) { empty = defaultVsuInstrument(st.instrument); emptySlot = st.instrument }
        val ins = actual ?: empty!!
        val bank = advanced.drawSidebar(c, body, p.channel(st.listenChannel), false)
        val left = bank.right + ink.dp(8f)
        val right = area.right - ink.dp(8f)
        nextSlot.set(right - touch, bank.top, right, bank.top + touch)
        previousSlot.set(nextSlot.left - touch, bank.top, nextSlot.left, nextSlot.bottom)
        name.set(left, bank.top, previousSlot.left, nextSlot.bottom)
        ink.label(c, ink.fit(if (actual == null) "Empty: edit to create" else ins.name, name.width(), ink.unit, true), left, bank.top, touch, p.TEXT, true)
        ink.label(c, "-", previousSlot.centerX(), bank.top, touch, p.TEXT, true, align = Align.CENTER)
        ink.label(c, "+", nextSlot.centerX(), bank.top, touch, p.TEXT, true, align = Align.CENTER)
        if (st.instField == 1) {
            ink.outline(c, name, p.FOCUS, ink.dp(2f))
            if (actual != null) drawCaret(c, actual.name)
        }
        if (cached !== ins || cachedLane != st.listenChannel) {
            for (i in controls.indices) {
                values[i] = VsuSoundMacros.label(i, ins, st.listenChannel)
                indices[i] = VsuSoundMacros.value(i, ins, st.listenChannel) ?: -1
                enabled[i] = VsuSoundMacros.reason(i, ins, st.listenChannel) == null
                hints[i] = VsuSoundMacros.hint(i, ins, st.listenChannel)
            }
            cached = ins; cachedLane = st.listenChannel
        }
        val top = name.bottom
        val bottom = area.bottom - ink.unit * 1.5f
        compact = right - left < ink.dp(370f) || bottom - top < touch * 4
        if (compact) {
            previous.set(left, top, left + touch, bottom)
            next.set(right - touch, top, right, bottom)
            val i = (st.instField - 2).coerceIn(0, controls.lastIndex)
            val displayBottom = maxOf(top, bottom - touch * 1.4f)
            val mid = (previous.right + next.left) / 2
            if (displayBottom - top >= ink.dp(24f)) {
                eye.set(previous.right, top, mid - ink.dp(3f), displayBottom)
                drawEye(c, ins, false, red)
                eye.set(mid + ink.dp(3f), top, next.left, displayBottom)
                drawEye(c, ins, true, red)
            }
            controls[i].set(previous.right, displayBottom, next.left, bottom)
            drawControl(c, i, red)
            ink.label(c, "<", previous.centerX(), top, previous.height(), p.TEXT, true, align = Align.CENTER)
            ink.label(c, ">", next.centerX(), top, next.height(), p.TEXT, true, align = Align.CENTER)
        } else {
            val split = top + (bottom - top) * 0.42f
            val mid = (left + right) / 2
            eye.set(left, top, mid - ink.dp(10f), split - ink.dp(10f))
            drawEye(c, ins, false, red)
            eye.set(mid + ink.dp(10f), top, right, split - ink.dp(10f))
            drawEye(c, ins, true, red)
            val w = (right - left) / 3
            val h = (bottom - split) / 2
            for (i in controls.indices) {
                controls[i].set(left + (i % 3) * w + ink.dp(5f), split + (i / 3) * h, left + (i % 3 + 1) * w - ink.dp(5f), split + (i / 3 + 1) * h)
                drawControl(c, i, red)
            }
        }
        val selected = (st.instField - 2).coerceIn(0, controls.lastIndex)
        ink.label(c, ink.fit(hints[selected], right - left, ink.unit * 0.75f), left, bottom, ink.unit * 1.5f, p.DIM, size = ink.unit * 0.75f)
    }

    private fun drawEye(c: Surface, ins: VsuInstrument, envelope: Boolean, red: Int) {
        val contrast = ink.palette.id == Palette.CONTRAST.id
        val bg = if (contrast) ink.palette.BG else 0xFF250D12.toInt()
        c.fillRect(eye.left, eye.top, eye.right, eye.bottom, bg)
        ink.outline(c, eye, red, ink.dp(2f))
        if (!compact) PixelLabels.draw(c, if (envelope) "CONTOUR / 1S" else if (st.listenChannel == VsuRules.NOISE_LANE) "NOISE / RESET" else "WAVE MEMORY",
            eye.left + ink.dp(10f), eye.top + ink.dp(10f), ink.dp(2f), red, eye.width() - ink.dp(20f))
        val l = eye.left + ink.dp(12f)
        val r = eye.right - ink.dp(12f)
        val t = eye.top + ink.dp(if (compact) 4f else 36f)
        val b = eye.bottom - ink.dp(if (compact) 4f else 12f)
        if (b <= t) return
        val wave = song.vsu.waves.getOrNull(ins.wave)
        var oldY = b
        var oldX = l
        var register = 0
        for (i in 0..63) {
            val fraction = if (envelope) {
                VsuSoundMacros.envelopeLevel(ins, i * 1000f / 63) / 15f
            } else if (st.listenChannel == VsuRules.NOISE_LANE) {
                val level = (register and 1).toFloat()
                register = VsuSoundMacros.noiseStep(register, ins.noiseTap)
                level
            } else (wave?.getOrNull(i % 32) ?: 0) / 63f
            val x = l + (r - l) * i / 63
            val y = b - (b - t) * fraction
            if (i > 0) { c.line(oldX, oldY, x, oldY, red, ink.dp(2f)); c.line(x, oldY, x, y, red, ink.dp(2f)) }
            oldX = x; oldY = y
        }
    }
    private fun drawControl(c: Surface, i: Int, red: Int) {
        val r = controls[i]
        val h = minOf(ink.dp(24f), r.height() * 0.3f)
        ink.label(c, VsuSoundMacros.title(i, st.listenChannel), r.left, r.top, h, ink.palette.TEXT, true, ink.unit * 0.9f)
        ink.label(c, ink.fit(values[i], r.width(), ink.unit * 0.85f), r.left, r.top + h, h, if (enabled[i]) ink.palette.TEXT else ink.palette.DIM, size = ink.unit * 0.85f)
        rails[i].set(r.left + ink.dp(10f), r.bottom - ink.dp(14f), r.right - ink.dp(10f), r.bottom - ink.dp(14f))
        val rail = rails[i]
        val max = VsuSoundMacros.maximum(i, st.listenChannel)
        if (enabled[i] && indices[i] >= 0) PanelWidgets.rail(ink, c, rail.left, rail.right, rail.top, indices[i].toFloat() / max, max, red, st.instField == i + 2, ctl.editHeld || drag.target != null)
        else c.line(rail.left, rail.top, rail.right, rail.top, ink.palette.FAINT, ink.dp(2f))
        if (st.instField == i + 2) c.line(r.left, r.bottom, r.right, r.bottom, ink.palette.FOCUS, ink.dp(2f))
    }

    private fun drawCaret(c: Surface, text: String) {
        if (caretName !== text || caretPos != st.textPos || caretUnit != ink.unit) {
            caretLeft = ink.labelWidth(text.take(st.textPos).padEnd(st.textPos, ' '), ink.unit, true)
            caretWidth = maxOf(ink.labelWidth(text.getOrNull(st.textPos)?.toString() ?: " ", ink.unit, true), ink.unit * 0.5f)
            caretName = text; caretPos = st.textPos; caretUnit = ink.unit
        }
        val x = name.left + caretLeft
        if (x + caretWidth <= name.right) c.fillRect(x, name.bottom - ink.dp(8f), x + caretWidth,
            name.bottom - ink.dp(5f), if (ctl.editHeld) ink.palette.WRITE else ink.palette.FOCUS)
    }
    internal fun modeRect(advanced: Boolean) = modes[if (advanced) 1 else 0]
    internal fun controlRect(i: Int) = controls[i]
    internal fun navigationRect(forward: Boolean) = if (forward) next else previous
    internal fun slotNavigationRect(forward: Boolean) = if (forward) nextSlot else previousSlot
    internal fun nameTarget() = name
    internal fun valueX(i: Int, fraction: Float) = rails[i].left + rails[i].width() * fraction

    override fun down(x: Float, y: Float): Boolean {
        cancel()
        for (i in 0..1) if (modes[i].contains(x, y)) { VsuSoundFace.selectMode(ctl, i == 1); return true }
        if (!VsuSoundFace.friendly(st)) { advancedPointer = advanced.down(x, y); if (advancedPointer) st.soundPanel("vsu").cancelTouch = cancelTouch; return advancedPointer }
        if (ctl.editHeld) { ctl.useEdit(); st.soundPanel("vsu").editBlocked = true }
        val slot = advanced.slotAt(x, y)
        if (slot > 0) { VsuSoundFace.moveSlot(ctl, slot - st.instrument); return true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) { VsuSoundFace.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true }
        if (name.contains(x, y)) { VsuSoundFace.rename(ctl); return true }
        if (previous.contains(x, y) || next.contains(x, y)) { st.instField = 2 + ((st.instField - 2).coerceIn(0, controls.lastIndex) + if (next.contains(x, y)) 1 else controls.lastIndex).mod(controls.size); return true }
        for (i in controls.indices) if (controls[i].contains(x, y)) {
            st.instField = i + 2
            val actual = VsuInstrumentView.sound(song, st)
            val ins = actual ?: defaultVsuInstrument(st.instrument)
            if (VsuSoundMacros.reason(i, ins, st.listenChannel) != null) return true
            if (i == 0) { VsuSoundFace.lane(ctl, PanelWidgets.railValue(x, rails[i].left, rails[i].right, 0, 5)); return true }
            drag.begin(session, "Set ${VsuSoundMacros.title(i, st.listenChannel)}", Target(st.instrument, st.listenChannel, st.soundPanel("vsu").revision, i, ins, actual, rails[i].left, rails[i].right))
            st.soundPanel("vsu").cancelTouch = cancelTouch
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val t = drag.live(session) ?: return
        if (!VsuSoundFace.friendly(st) || st.instrument != t.slot || st.listenChannel != t.lane || st.soundPanel("vsu").revision != t.revision || song.vsuInstrument(t.slot) !== t.expected) { cancel(); return }
        val value = PanelWidgets.railValue(x, t.left, t.right, 0, VsuSoundMacros.maximum(t.control, t.lane))
        val changed = VsuSoundMacros.apply(t.control, t.original, value, t.lane)
        if (changed == t.expected) return
        if (session.setVsuInstrument(t.slot, changed, "Set ${VsuSoundMacros.title(t.control, t.lane)}")) VsuSoundFace.heard(ctl)
        t.expected = song.vsuInstrument(t.slot)
    }
    override fun move(x: Float, y: Float) { if (advancedPointer) advanced.move(x, y) else setFromX(x) }
    override fun up(x: Float, y: Float) { if (advancedPointer) advanced.up(x, y); cancel() }
    override fun cancel() {
        if (st.soundPanel("vsu").cancelTouch === cancelTouch) st.soundPanel("vsu").cancelTouch = null
        drag.finish(session)
        if (advancedPointer) { advancedPointer = false; advanced.cancel() }
    }
}
