package org.thortracker.system.pokey

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Right-hand musical console. The owning precise painter draws and hit-tests the one shared bank. */
internal class PokeySoundPainter(v: PaneView) : Painter(v) {
    private companion object {
        val CASE = 0xffc8baa1.toInt()
        val KEY = 0xffe2dac8.toInt()
        val INK = 0xff2d2722.toInt()
        val MUTED = 0xff5d4b3f.toInt()
        val ACCENT = 0xffb45d20.toInt()
        val FADER = 0xfff4ba66.toInt()
    }
    private val modes = Array(2) { Rect() }
    private val controls = Array(6) { Rect() }
    private val rails = Array(6) { Rect() }
    private val labels = Array(6) { "" }
    private val values = Array(6) { "" }
    private val hints = Array(6) { "" }
    private val indices = IntArray(6)
    private val maxima = IntArray(6)
    private val enabled = BooleanArray(6)
    private val name = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val signal = Rect()
    private var cached: PokeyInstrument? = null
    private var cachedSong: org.thortracker.model.Song? = null
    private var cachedSlot = -1
    private var cachedLane = -1
    private var empty = defaultPokeyInstrument(1)
    private var emptySlot = 1
    private var title = ""
    private val channelTaken = BooleanArray(4)
    private val channelLabels = Array(4) { "" }
    private class Target(val slot: Int, val control: Int, val revision: Int, val left: Float, val right: Float,
                         val original: PokeyInstrument, var expected: PokeyInstrument?)
    private val drag = PanelDrag<Target>()
    private val cancelOwned: () -> Unit = { cancel() }
    private fun instrument(): PokeyInstrument {
        val actual = PokeyInstrumentView.sound(song, st)
        if (actual != null) return actual
        if (emptySlot != st.instrument) {
            empty = defaultPokeyInstrument(st.instrument)
            emptySlot = st.instrument
        }
        return empty
    }
    private fun cache(ins: PokeyInstrument) {
        if (cached === ins && cachedSong === song && cachedSlot == st.instrument && cachedLane == st.listenChannel) return
        for (n in 0 until 6) {
            labels[n] = PokeySoundMacros.label(ins, n)
            values[n] = PokeySoundMacros.text(song, ins, n)
            indices[n] = PokeySoundMacros.value(song, ins, n) ?: -1
            maxima[n] = PokeySoundMacros.maximum(song, ins, n)
            enabled[n] = PokeySoundMacros.enabled(song, ins, n, PokeyInstrumentView.listenChannel(st, ins))
            hints[n] = PokeySoundMacros.hint(song, ins, n, PokeyInstrumentView.listenChannel(st, ins))
        }
        title = if (PokeyInstrumentView.sound(song, st) == null) "Empty · shape to create" else ins.name.ifEmpty { "Unnamed" }
        val lane = PokeyInstrumentView.listenChannel(st, ins)
        val taken = ins.kind.taken(lane)
        for (ch in 0..3) {
            channelTaken[ch] = ch in taken
            channelLabels[ch] = if (ch == lane) "${ch + 1} voice" else if (channelTaken[ch]) "${ch + 1} paired" else "${ch + 1} free"
        }
        cached = ins; cachedSong = song; cachedSlot = st.instrument; cachedLane = st.listenChannel
    }
    fun modes(c: Surface, area: Rect, top: Float): Float {
        val h = ink.dp(48f)
        val width = minOf(area.width() / 2f, ink.dp(140f))
        for (n in 0..1) {
            val r = modes[n]
            r.set(area.left + n * width, top, area.left + (n + 1) * width, top + h)
            val selected = PokeySoundFace.panel(st).advanced == (n == 1)
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
        val columns = if (area.width() >= maxOf(ink.dp(336f), ink.unit * 24)) 3 else 2
        val rows = controls.size / columns
        val wide = area.width() >= maxOf(ink.dp(224f), ink.unit * 16) &&
            area.height() >= touch + ink.dp(56f) + rows * maxOf(ink.dp(80f), ink.unit * 5)
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        ink.rect(c, area, CASE)
        previousSlot.set(area.right - touch * 2, area.top, area.right - touch, area.top + touch)
        nextSlot.set(area.right - touch, area.top, area.right, area.top + touch)
        name.set(area.left, area.top, previousSlot.left, area.top + touch)
        ink.label(c, ink.fit(title, name.width() - pad * 2), name.left + pad, name.top, touch, INK, true)
        if (st.instField == 1) {
            ink.outline(c, name, INK, ink.dp(2f))
            ink.label(c, "Letter ${st.textPos + 1}", name.left + pad, name.bottom - ink.dp(14f), ink.dp(14f), INK, size = ink.unit * 0.65f)
        }
        ink.label(c, "Slot −", previousSlot.left + pad, previousSlot.top, touch, MUTED, size = ink.unit * 0.75f)
        ink.label(c, "Slot +", nextSlot.left + pad, nextSlot.top, touch, MUTED, size = ink.unit * 0.75f)
        val focus = (st.instField - 2).coerceIn(0, 5)
        val footer = if (wide) ink.dp(32f) else touch
        val signalH = if (area.height() >= touch * 3.5f) ink.dp(24f) else 0f
        signal.set(area.left + pad, name.bottom, area.right - pad, name.bottom + signalH)
        if (signalH > 0f) feedback(c, signal)
        val top = name.bottom + signalH
        val bottom = area.bottom - footer
        if (wide) {
            val w = area.width() / columns
            val h = (bottom - top) / rows
            for (n in controls.indices) {
                controls[n].set(area.left + n % columns * w + pad, top + n / columns * h, area.left + (n % columns + 1) * w - pad, top + (n / columns + 1) * h - pad)
                drawControl(c, n)
            }
            previous.set(0f, 0f, 0f, 0f); next.set(0f, 0f, 0f, 0f)
            ink.label(c, ink.fit(hints[focus], area.width() - pad * 2, ink.unit * 0.75f), area.left + pad, bottom, footer, MUTED, size = ink.unit * 0.75f)
        } else {
            controls[focus].set(area.left + pad, top, area.right - pad, bottom)
            drawControl(c, focus)
            previous.set(area.left, bottom, area.left + touch, area.bottom)
            next.set(area.right - touch, bottom, area.right, area.bottom)
            ink.label(c, "‹", previous.left + pad, bottom, footer, INK, true)
            ink.label(c, "›", next.left + pad, bottom, footer, INK, true)
            ink.label(c, ink.fit(hints[focus], area.width() - touch * 2 - pad * 2, ink.unit * 0.7f), previous.right + pad, bottom, footer, MUTED, size = ink.unit * 0.7f)
        }
    }
    private fun feedback(c: Surface, area: Rect) {
        val w = area.width() / 4f
        for (ch in 0..3) {
            val x = area.left + ch * w
            c.fillRect(x + ink.dp(3f), area.top + ink.dp(4f), x + ink.dp(11f), area.top + ink.dp(12f), if (channelTaken[ch]) ACCENT else MUTED)
            ink.label(c, channelLabels[ch], x + ink.dp(16f), area.top, area.height(), INK, size = ink.unit * 0.65f)
        }
    }
    private fun drawControl(c: Surface, n: Int) {
        val r = controls[n]
        if (r.height() <= 0f) return
        val pad = ink.dp(10f)
        val focused = st.instField == n + 2
        val accent = if (enabled[n]) ACCENT else MUTED

        // Computer function-key strips: a broad value window over a tactile stepped travel rail.
        ink.rect(c, r, KEY)
        ink.rect(c, r.left, r.top, r.left + ink.dp(4f), r.bottom, accent)
        ink.label(c, labels[n], r.left + pad, r.top, ink.dp(23f), MUTED, size = ink.unit * 0.8f)
        ink.label(c, ink.fit(values[n], r.width() - pad * 2, ink.unit), r.left + pad, r.top + ink.dp(22f), ink.dp(23f), if (enabled[n]) INK else MUTED, true)
        rails[n].set(r.left + pad, r.bottom - ink.dp(16f), r.right - pad, r.bottom - ink.dp(16f))
        c.fillRect(rails[n].left - ink.dp(3f), rails[n].top - ink.dp(7f), rails[n].right + ink.dp(3f), rails[n].top + ink.dp(7f), INK)

        if (enabled[n] && maxima[n] > 0 && indices[n] >= 0) PanelWidgets.rail(ink, c, rails[n].left, rails[n].right, rails[n].top,
            indices[n].toFloat() / maxima[n], maxima[n], FADER, false, false)
        else c.line(rails[n].left, rails[n].top, rails[n].right, rails[n].top, KEY, ink.dp(2f))
        if (focused) ink.outline(c, r, INK, ink.dp(if (ctl.editHeld) 3f else 2f))
    }
    internal fun modeRect(advanced: Boolean) = modes[if (advanced) 1 else 0]
    internal fun controlRect(n: Int) = controls[n]
    internal fun navigationRect(forward: Boolean) = if (forward) next else previous
    internal fun valueX(n: Int, fraction: Float) = rails[n].left + rails[n].width() * fraction
    internal fun slotNavigationRect(forward: Boolean) = if (forward) nextSlot else previousSlot
    override fun down(x: Float, y: Float): Boolean {
        cancel()
        for (n in 0..1) if (modes[n].contains(x, y)) { PokeySoundFace.selectMode(ctl, n == 1); return true }
        if (!PokeySoundFace.friendly(st)) return false
        if (ctl.editHeld) { ctl.useEdit(); PokeySoundFace.panel(st).editBlocked = true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) { PokeySoundFace.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true }
        if (name.contains(x, y)) {
            st.instField = 1
            val slot = st.instrument
            val generation = session.documentGeneration
            val target = song.pokeyInstrument(slot)
            session.requestText("Sound name", instrument().name, PokeyInstrumentView.NAME_MAX) { value ->
                if (session.documentGeneration == generation && session.song.pokeyInstrument(slot) === target) {
                    session.editPokeyInstrument(slot, "Rename sound") { it.copy(name = value) }
                }
            }
            return true
        }
        if (previous.contains(x, y) || next.contains(x, y)) {
            st.instField = 2 + ((st.instField - 2).coerceIn(0, 5) + if (next.contains(x, y)) 1 else 5).mod(6)
            return true
        }
        for (n in 0 until 6) if (controls[n].contains(x, y)) {
            st.instField = n + 2
            val ins = instrument()
            if (!(PokeySoundMacros.enabled(song, ins, n, PokeyInstrumentView.listenChannel(st, ins)))) { session.note(hints[n]); return true }
            val value = PanelWidgets.railValue(x, rails[n].left, rails[n].right, 0, maxima[n])
            if (n == 0 || (n == 5 && ins.kind == PokeyKind.SAMPLE)) { PokeySoundFace.set(ctl, n, value); return true }
            drag.begin(session, "Shape sound", Target(st.instrument, n, PokeySoundFace.panel(st).revision, rails[n].left, rails[n].right, ins, PokeyInstrumentView.sound(song, st)))
            PokeySoundFace.panel(st).cancelTouch = cancelOwned
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val target = drag.live(session) ?: return
        if (!PokeySoundFace.friendly(st) || st.instrument != target.slot || PokeySoundFace.panel(st).revision != target.revision || PokeyInstrumentView.sound(song, st) !== target.expected) { cancel(); return }
        val value = PanelWidgets.railValue(x, target.left, target.right, 0, maxima[target.control])
        val next = PokeySoundMacros.apply(song, target.original, target.control, value, PokeyInstrumentView.listenChannel(st, target.original))
        if (next == target.expected) return
        if (session.setPokeyInstrument(target.slot, next, "Shape sound")) PokeySoundFace.heard(ctl)
        target.expected = PokeyInstrumentView.sound(song, st)
    }
    override fun move(x: Float, y: Float) = setFromX(x)
    override fun up(x: Float, y: Float) = cancel()
    override fun cancel() {
        if (PokeySoundFace.panel(st).cancelTouch === cancelOwned) PokeySoundFace.panel(st).cancelTouch = null
        drag.finish(session)
    }
}
