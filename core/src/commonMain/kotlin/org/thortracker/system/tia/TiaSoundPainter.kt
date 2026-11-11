package org.thortracker.system.tia

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Right-hand musical console. The owning precise painter draws and hit-tests the one shared bank. */
internal class TiaSoundPainter(v: PaneView) : Painter(v) {
    private companion object {
        val CONSOLE = 0xff242220.toInt()
        val CHEEK = 0xff78503a.toInt()
        val TEXT = 0xffece3cf.toInt()
        val MUTED = 0xffbdac92.toInt()
        val ACCENT = 0xffe4b056.toInt()
    }
    private val modes = Array(2) { Rect() }
    private val controls = Array(4) { Rect() }
    private val rails = Array(4) { Rect() }
    private val labels = Array(4) { "" }
    private val values = Array(4) { "" }
    private val hints = Array(4) { "" }
    private val indices = IntArray(4)
    private val maxima = IntArray(4)
    private val enabled = BooleanArray(4)
    private val needleX = FloatArray(4)
    private val needleY = FloatArray(4)
    private val name = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val signal = Rect()
    private var cached: TiaInstrument? = null
    private var cachedSong: org.thortracker.model.Song? = null
    private var cachedSlot = -1
    private var cachedNote = -1
    private var empty = defaultTiaInstrument(1)
    private var emptySlot = 1
    private var title = ""
    private var divider: Int? = null
    private var dividerText = ""
    private class Target(val slot: Int, val control: Int, val revision: Int, val left: Float, val right: Float,
                         val original: TiaInstrument, var expected: TiaInstrument?)
    private val drag = PanelDrag<Target>()
    private val cancelOwned: () -> Unit = { cancel() }
    private fun instrument(): TiaInstrument {
        val actual = TiaInstrumentView.sound(song, st)
        if (actual != null) return actual
        if (emptySlot != st.instrument) {
            empty = defaultTiaInstrument(st.instrument)
            emptySlot = st.instrument
        }
        return empty
    }
    private fun cache(ins: TiaInstrument) {
        if (cached === ins && cachedSong === song && cachedSlot == st.instrument && cachedNote == ctl.defaultNote()) return
        for (n in 0 until 4) {
            labels[n] = TiaSoundMacros.labels[n]
            values[n] = TiaSoundMacros.text(ins, n)
            indices[n] = TiaSoundMacros.value(ins, n) ?: -1
            maxima[n] = TiaSoundMacros.maxima[n]
            enabled[n] = TiaSoundMacros.enabled(ins, n)
            hints[n] = TiaSoundMacros.hint(ins, n)
            val fraction = indices[n].coerceAtLeast(0).toDouble() / maxima[n].coerceAtLeast(1)
            val angle = 2.4 + fraction * 4.6
            needleX[n] = kotlin.math.cos(angle).toFloat() * 0.8f
            needleY[n] = kotlin.math.sin(angle).toFloat() * 0.8f
        }
        title = if (TiaInstrumentView.sound(song, st) == null) "Empty · shape to create" else ins.name.ifEmpty { "Unnamed" }
        divider = TiaSoundMacros.divider(ins, song.tia.region, ctl.defaultNote())
        dividerText = divider?.let { "Key-on ÷${it + 1}" } ?: "DC: no pitch"
        cached = ins; cachedSong = song; cachedSlot = st.instrument; cachedNote = ctl.defaultNote()
    }
    fun modes(c: Surface, area: Rect, top: Float): Float {
        val h = ink.dp(48f)
        val width = minOf(area.width() / 2f, ink.dp(140f))
        for (n in 0..1) {
            val r = modes[n]
            r.set(area.left + n * width, top, area.left + (n + 1) * width, top + h)
            val selected = TiaSoundFace.panel(st).advanced == (n == 1)
            PanelWidgets.segment(ink, c, r, selected, ink.palette.FOCUS)
            ink.label(c, if (n == 0) "Sound" else "Advanced", r.left + ink.dp(12f), r.top, h, ink.palette.TEXT, selected)
            if (st.instField == -1 && selected) ink.outline(c, r, ink.palette.FOCUS, ink.dp(2f))
        }
        return top + h
    }
    override fun draw(c: Surface, area: Rect) {
        val ins = instrument()
        cache(ins)
        val touch = ink.dp(48f)
        val pad = ink.dp(8f)
        val wide = area.width() >= maxOf(ink.dp(280f), ink.unit * 18) &&
            area.height() >= touch + ink.dp(56f) + 2 * maxOf(ink.dp(86f), ink.unit * 5.5f)
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        ink.rect(c, area, CONSOLE)
        c.fillRect(area.left, area.top, area.left + ink.dp(3f), area.bottom, CHEEK)
        c.fillRect(area.right - ink.dp(3f), area.top, area.right, area.bottom, CHEEK)
        previousSlot.set(area.right - touch * 2, area.top, area.right - touch, area.top + touch)
        nextSlot.set(area.right - touch, area.top, area.right, area.top + touch)
        name.set(area.left, area.top, previousSlot.left, area.top + touch)
        ink.label(c, ink.fit(title, name.width() - pad * 2), name.left + pad, name.top, touch, TEXT, true)
        if (st.instField == 1) {
            ink.outline(c, name, ACCENT, ink.dp(2f))
            ink.label(c, "Letter ${st.textPos + 1}", name.left + pad, name.bottom - ink.dp(14f), ink.dp(14f), ACCENT, size = ink.unit * 0.65f)
        }
        ink.label(c, "Slot −", previousSlot.left + pad, previousSlot.top, touch, MUTED, size = ink.unit * 0.75f)
        ink.label(c, "Slot +", nextSlot.left + pad, nextSlot.top, touch, MUTED, size = ink.unit * 0.75f)
        val focus = (st.instField - 2).coerceIn(0, 3)
        val footer = if (wide) ink.dp(32f) else touch
        val signalH = if (area.height() >= touch * 3.5f) ink.dp(24f) else 0f
        signal.set(area.left + pad, name.bottom, area.right - pad, name.bottom + signalH)
        if (signalH > 0f) feedback(c, signal)
        val top = name.bottom + signalH
        val bottom = area.bottom - footer
        if (wide) {
            val w = area.width() / 2f
            val h = (bottom - top) / 2f
            for (n in 0 until 4) {
                controls[n].set(area.left + n % 2 * w + pad, top + n / 2 * h, area.left + (n % 2 + 1) * w - pad, top + (n / 2 + 1) * h - pad)
                drawControl(c, n)
            }
            previous.set(0f, 0f, 0f, 0f); next.set(0f, 0f, 0f, 0f)
            ink.label(c, ink.fit(hints[focus], area.width() - pad * 2, ink.unit * 0.75f), area.left + pad, bottom, footer, MUTED, size = ink.unit * 0.75f)
        } else {
            controls[focus].set(area.left + pad, top, area.right - pad, bottom)
            drawControl(c, focus)
            previous.set(area.left, bottom, area.left + touch, area.bottom)
            next.set(area.right - touch, bottom, area.right, area.bottom)
            ink.label(c, "‹", previous.left + pad, bottom, footer, TEXT, true)
            ink.label(c, "›", next.left + pad, bottom, footer, TEXT, true)
            ink.label(c, ink.fit(hints[focus], area.width() - touch * 2 - pad * 2, ink.unit * 0.7f), previous.right + pad, bottom, footer, MUTED, size = ink.unit * 0.7f)
        }
    }
    private fun feedback(c: Surface, area: Rect) {
        val left = area.left + ink.dp(84f)
        ink.label(c, dividerText, area.left, area.top, area.height(), MUTED, size = ink.unit * 0.65f)
        val w = (area.right - left) / 32f
        for (k in 0..31) c.fillRect(left + k * w, area.bottom - ink.dp(if (k == divider) 14f else 5f),
            left + (k + 1) * w - ink.dp(0.5f), area.bottom, if (k == divider) ACCENT else CHEEK)
    }
    private fun drawControl(c: Surface, n: Int) {
        val r = controls[n]
        if (r.height() <= 0f) return
        val pad = ink.dp(10f)
        val focused = st.instField == n + 2
        val accent = if (enabled[n]) ACCENT else MUTED

        // A paddle arc is the cartridge console's focal shape, not a bank of register boxes.
        val radius = minOf(ink.dp(46f), r.height() * 0.28f)
        val cx = r.left + radius + pad
        val cy = r.centerY()
        c.strokeCircle(cx, cy, radius, accent, ink.dp(3f))
        c.line(cx, cy, cx + needleX[n] * radius, cy + needleY[n] * radius,
            if (indices[n] < 0) MUTED else TEXT, ink.dp(3f))
        val tx = cx + radius + pad
        ink.label(c, labels[n], tx, r.top, ink.dp(24f), MUTED, size = ink.unit * 0.75f)
        ink.label(c, ink.fit(values[n], r.right - tx - pad, ink.unit * 0.85f), tx, r.top + ink.dp(24f), ink.dp(24f), if (enabled[n]) TEXT else MUTED, true, ink.unit * 0.85f)
        rails[n].set(r.left + pad, r.bottom - ink.dp(12f), r.right - pad, r.bottom - ink.dp(12f))

        if (enabled[n] && maxima[n] > 0 && indices[n] >= 0) PanelWidgets.rail(ink, c, rails[n].left, rails[n].right, rails[n].top,
            indices[n].toFloat() / maxima[n], maxima[n], accent, false, false)
        else c.line(rails[n].left, rails[n].top, rails[n].right, rails[n].top, CHEEK, ink.dp(2f))
        if (focused) ink.outline(c, r, ACCENT, ink.dp(if (ctl.editHeld) 3f else 2f))
    }
    internal fun modeRect(advanced: Boolean) = modes[if (advanced) 1 else 0]
    internal fun controlRect(n: Int) = controls[n]
    internal fun navigationRect(forward: Boolean) = if (forward) next else previous
    internal fun valueX(n: Int, fraction: Float) = rails[n].left + rails[n].width() * fraction
    internal fun slotNavigationRect(forward: Boolean) = if (forward) nextSlot else previousSlot
    override fun down(x: Float, y: Float): Boolean {
        cancel()
        for (n in 0..1) if (modes[n].contains(x, y)) { TiaSoundFace.selectMode(ctl, n == 1); return true }
        if (!TiaSoundFace.friendly(st)) return false
        if (ctl.editHeld) { ctl.useEdit(); TiaSoundFace.panel(st).editBlocked = true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) { TiaSoundFace.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true }
        if (name.contains(x, y)) {
            st.instField = 1
            val slot = st.instrument
            val generation = session.documentGeneration
            val target = song.tiaInstrument(slot)
            session.requestText("Sound name", instrument().name, TiaInstrumentView.NAME_MAX) { value ->
                if (session.documentGeneration == generation && session.song.tiaInstrument(slot) === target) {
                    session.editTiaInstrument(slot, "Rename sound") { it.copy(name = value) }
                }
            }
            return true
        }
        if (previous.contains(x, y) || next.contains(x, y)) {
            st.instField = 2 + ((st.instField - 2).coerceIn(0, 3) + if (next.contains(x, y)) 1 else 3).mod(4)
            return true
        }
        for (n in 0 until 4) if (controls[n].contains(x, y)) {
            st.instField = n + 2
            val ins = instrument()
            if (!(TiaSoundMacros.enabled(ins, n))) { session.note(hints[n]); return true }
            drag.begin(session, "Shape sound", Target(st.instrument, n, TiaSoundFace.panel(st).revision, rails[n].left, rails[n].right, ins, TiaInstrumentView.sound(song, st)))
            TiaSoundFace.panel(st).cancelTouch = cancelOwned
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val target = drag.live(session) ?: return
        if (!TiaSoundFace.friendly(st) || st.instrument != target.slot || TiaSoundFace.panel(st).revision != target.revision || TiaInstrumentView.sound(song, st) !== target.expected) { cancel(); return }
        val value = PanelWidgets.railValue(x, target.left, target.right, 0, maxima[target.control])
        val next = TiaSoundMacros.apply(target.original, target.control, value)
        if (next == target.expected) return
        if (session.setTiaInstrument(target.slot, next, "Shape sound")) TiaSoundFace.heard(ctl)
        target.expected = TiaInstrumentView.sound(song, st)
    }
    override fun move(x: Float, y: Float) = setFromX(x)
    override fun up(x: Float, y: Float) = cancel()
    override fun cancel() {
        if (TiaSoundFace.panel(st).cancelTouch === cancelOwned) TiaSoundFace.panel(st).cancelTouch = null
        drag.finish(session)
    }
}
