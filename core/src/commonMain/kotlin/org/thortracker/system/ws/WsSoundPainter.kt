package org.thortracker.system.ws

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.system.panel.PixelLabels
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** A monochrome pocket display and stepped pads, surrounding the actual wave or recording. */
internal class WsSoundPainter(v: PaneView) : Painter(v) {
    private val advanced = WsInstrumentPainter(v)
    private val modes = Array(2) { Rect() }
    private val body = Rect()
    private val name = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val listen = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val controls = Array(WsSoundMacros.COUNT) { Rect() }
    private val rails = Array(WsSoundMacros.COUNT) { Rect() }
    private val scope = Rect()
    private val values = Array(WsSoundMacros.COUNT) { "" }
    private val hints = Array(WsSoundMacros.COUNT) { "" }
    private val indices = IntArray(WsSoundMacros.COUNT)
    private val enabled = BooleanArray(WsSoundMacros.COUNT)
    private var cached: WsInstrument? = null
    private var cachedSound: WsSound? = null
    private var cachedLane = -1
    private var emptySlot = -1
    private var empty: WsInstrument? = null
    private var caretName: String? = null
    private var caretPos = -1
    private var caretUnit = -1f
    private var caretLeft = 0f
    private var caretWidth = 0f
    private var advancedPointer = false
    private class Target(val slot: Int, val lane: Int, val revision: Int, val control: Int, val original: WsInstrument,
        var expected: WsInstrument?, val waves: List<List<Int>>, val samples: List<WsSample>, val left: Float, val right: Float)
    private val drag = PanelDrag<Target>()
    private val cancelTouch: () -> Unit = { cancel() }
    internal var compact = false
        private set

    private var hintKey = ""
    private var hintWidth = -1f
    private var hintSize = -1f
    private var hintFirst = ""
    private var hintSecond = ""

    private fun drawHint(c: Surface, text: String, left: Float, top: Float, right: Float, height: Float) {
        val width = maxOf(0f, right - left)
        val size = ink.unit * 0.75f
        if (text != hintKey || width != hintWidth || size != hintSize) {
            hintFirst = text
            hintSecond = ""
            if (ink.labelWidth(text, size) > width) {
                var split = text.lastIndexOf(' ')
                while (split > 0 && ink.labelWidth(text.substring(0, split), size) > width) split = text.lastIndexOf(' ', split - 1)
                if (split > 0) {
                    hintFirst = text.substring(0, split)
                    hintSecond = ink.fit(text.substring(split + 1), width, size)
                } else hintFirst = ink.fit(text, width, size)
            }
            hintKey = text; hintWidth = width; hintSize = size
        }
        val line = if (hintSecond.isEmpty()) height else height / 2
        ink.label(c, hintFirst, left, top, line, ink.palette.DIM, size = size)
        if (hintSecond.isNotEmpty()) ink.label(c, hintSecond, left, top + line, line, ink.palette.DIM, size = size)
    }

    override fun draw(c: Surface, area: Rect) {
        val p = ink.palette
        val panel = st.soundPanel("ws")
        val touch = ink.dp(48f)
        val contrast = p.id == Palette.CONTRAST.id
        val paper = if (contrast) p.BG else 0xFFD4D8CC.toInt()
        val dark = if (contrast) p.TEXT else 0xFF242C28.toInt()
        c.fillRect(area.left, area.top, area.right, area.bottom, p.BG)
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        previous.set(0f, 0f, 0f, 0f); next.set(0f, 0f, 0f, 0f)
        val mw = minOf(area.width() / 2, touch * 2.4f)
        for (i in 0..1) {
            modes[i].set(area.left + i * mw, area.top, area.left + (i + 1) * mw, area.top + touch)
            PanelWidgets.segment(ink, c, modes[i], panel.advanced == (i == 1), p.FOCUS)
            ink.label(c, if (i == 0) "Sound" else "Advanced", modes[i].centerX(), area.top, touch, p.TEXT, true, align = Align.CENTER)
        }
        if (st.instField == -1) ink.outline(c, modes[if (panel.advanced) 1 else 0], p.FOCUS, ink.dp(2f))
        body.set(area.left, area.top + touch, area.right, area.bottom)
        if (panel.advanced) { advanced.draw(c, body); return }
        val actual = WsInstrumentView.sound(song, st)
        if (empty == null || emptySlot != st.instrument) { empty = defaultWsInstrument(st.instrument); emptySlot = st.instrument }
        val ins = actual ?: empty!!
        val lane = WsInstrumentView.listenChannel(song, st, ins)
        val bank = advanced.drawSidebar(c, body, p.channel(lane), false)
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
        if (cached !== ins || cachedSound !== song.ws || cachedLane != st.listenChannel) {
            for (i in controls.indices) {
                values[i] = WsSoundMacros.label(i, ins, song.ws, st.listenChannel)
                indices[i] = WsSoundMacros.value(i, ins, st.listenChannel) ?: -1
                enabled[i] = WsSoundMacros.reason(i, ins, song.ws, st.listenChannel) == null
                hints[i] = WsSoundMacros.hint(i, ins, song.ws, lane)
            }
            cached = ins; cachedSound = song.ws; cachedLane = st.listenChannel
        }
        val top = name.bottom
        val bottom = area.bottom - ink.unit * 2.6f
        val strip = right - left >= ink.dp(420f) && bottom - top >= touch * 3 && bottom - top < touch * WsSoundMacros.COUNT
        compact = !strip && (right - left < ink.dp(370f) || bottom - top < touch * WsSoundMacros.COUNT)
        listen.set(0f, 0f, 0f, 0f)
        if (compact || strip) {
            listen.set(modes[1].right, area.top, area.right, area.top + touch)
            ink.label(c, "${song.system.channels[lane].name} >", listen.centerX(), listen.top, touch, p.TEXT, align = Align.CENTER)
        }
        if (strip) {
            val displayBottom = bottom - touch * 1.6f
            scope.set(left, top, right, displayBottom - ink.dp(8f))
            c.fillRect(scope.left, scope.top, scope.right, scope.bottom, paper)
            PixelLabels.draw(c, if (ins.kind == WsKind.SAMPLE) "VOICE MEMORY" else "POCKET WAVE", scope.left + ink.dp(10f), scope.top + ink.dp(12f), ink.dp(2f), dark, scope.width() - ink.dp(20f))
            drawSignal(c, ins, dark)
            val w = (right - left) / WsSoundMacros.COUNT
            for (i in controls.indices) {
                controls[i].set(left + i * w + ink.dp(4f), displayBottom, left + (i + 1) * w - ink.dp(4f), bottom)
                drawControl(c, i, ins, p.TEXT, p.FOCUS)
            }
        } else if (compact) {
            previous.set(left, top, left + touch, bottom)
            next.set(right - touch, top, right, bottom)
            val i = (st.instField - 2).coerceIn(0, controls.lastIndex)
            val displayBottom = maxOf(top, bottom - touch * 1.4f)
            scope.set(previous.right, top, next.left, displayBottom)
            if (scope.height() >= ink.dp(24f)) {
                c.fillRect(scope.left, scope.top, scope.right, scope.bottom, paper)
                drawSignal(c, ins, dark)
            }
            controls[i].set(previous.right, displayBottom, next.left, bottom)
            drawControl(c, i, ins, p.TEXT, p.FOCUS)
            ink.label(c, "<", previous.centerX(), top, previous.height(), p.TEXT, true, align = Align.CENTER)
            ink.label(c, ">", next.centerX(), top, next.height(), p.TEXT, true, align = Align.CENTER)
        } else {
            val displayRight = left + (right - left) * 0.47f
            scope.set(left, top, displayRight - ink.dp(8f), bottom - touch)
            c.fillRect(scope.left, scope.top, scope.right, scope.bottom, paper)
            PixelLabels.draw(c, if (ins.kind == WsKind.SAMPLE) "VOICE MEMORY" else "POCKET WAVE", scope.left + ink.dp(10f), scope.top + ink.dp(12f), ink.dp(2f), dark, scope.width() - ink.dp(20f))
            drawSignal(c, ins, dark)
            listen.set(scope.left, scope.bottom, scope.right, bottom)
            ink.label(c, "Listen: ${song.system.channels[lane].name} >", listen.centerX(), listen.top, touch, p.TEXT, align = Align.CENTER)
            val h = (bottom - top) / WsSoundMacros.COUNT
            for (i in controls.indices) {
                controls[i].set(displayRight + ink.dp(8f), top + i * h, right, top + (i + 1) * h)
                drawControl(c, i, ins, p.TEXT, p.FOCUS)
            }
        }
        val selected = (st.instField - 2).coerceIn(0, controls.lastIndex)
        drawHint(c, hints[selected], left, bottom, right, area.bottom - bottom)
    }

    private fun drawSignal(c: Surface, ins: WsInstrument, color: Int) {
        val data = when (ins.kind) { WsKind.TONE -> song.ws.waves.getOrNull(ins.waves.values.firstOrNull() ?: ins.wave); WsKind.SAMPLE -> song.ws.samples.getOrNull(ins.sample)?.data; WsKind.NOISE -> null }
        val l = scope.left + ink.dp(12f)
        val r = scope.right - ink.dp(12f)
        val t = scope.top + ink.dp(if (compact) 4f else 48f)
        val b = scope.bottom - ink.dp(if (compact) 4f else 20f)
        if (b <= t || r <= l) return
        if (ins.kind == WsKind.NOISE) {
            var register = 0
            var y = b
            val tap = ins.taps.values.firstOrNull() ?: ins.tap
            for (i in 1..64) {
                register = WsSoundMacros.noiseStep(register, tap)
                val x0 = l + (r - l) * (i - 1) / 64
                val x1 = l + (r - l) * i / 64
                val ny = if (register and 1 == 0) b else t
                c.line(x0, y, x1, y, color, ink.dp(1f)); c.line(x1, y, x1, ny, color, ink.dp(1f))
                y = ny
            }
            return
        }
        if (data == null || data.isEmpty()) {
            ink.label(c, "No source", l, t, b - t, color)
            return
        }
        val n = minOf(data.size, 128)
        val max = if (ins.kind == WsKind.SAMPLE) 255f else 15f
        var y = b - data[0] / max * (b - t)
        for (i in 1 until n) {
            val x0 = l + (r - l) * (i - 1) / (n - 1)
            val x1 = l + (r - l) * i / (n - 1)
            val ny = b - data[i * (data.size - 1) / (n - 1)] / max * (b - t)
            c.line(x0, y, x1, y, color, ink.dp(2f)); c.line(x1, y, x1, ny, color, ink.dp(2f)); y = ny
        }
    }

    private fun drawControl(c: Surface, i: Int, ins: WsInstrument, text: Int, accent: Int) {
        val r = controls[i]
        val h = minOf(ink.dp(24f), r.height() * 0.3f)
        ink.label(c, ink.fit(WsSoundMacros.title(i, ins), r.width(), ink.unit * 0.9f, true), r.left, r.top, h, text, true, ink.unit * 0.9f)
        ink.label(c, ink.fit(values[i], r.width(), ink.unit * 0.85f), r.left, r.top + h, h, if (enabled[i]) text else ink.palette.DIM, size = ink.unit * 0.85f)
        rails[i].set(r.left + ink.dp(10f), r.bottom - ink.dp(14f), r.right - ink.dp(10f), r.bottom - ink.dp(14f))
        val rail = rails[i]
        val max = WsSoundMacros.maximum(i, ins, song.ws)
        if (enabled[i] && indices[i] >= 0) PanelWidgets.rail(ink, c, rail.left, rail.right, rail.top, indices[i].toFloat() / max.coerceAtLeast(1), max, accent, st.instField == i + 2, ctl.editHeld || drag.target != null)
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
    internal fun listenRect() = listen
    internal fun valueX(i: Int, fraction: Float) = rails[i].left + rails[i].width() * fraction

    override fun down(x: Float, y: Float): Boolean {
        cancel()
        for (i in 0..1) if (modes[i].contains(x, y)) { WsSoundFace.selectMode(ctl, i == 1); return true }
        if (!WsSoundFace.friendly(st)) { advancedPointer = advanced.down(x, y); if (advancedPointer) st.soundPanel("ws").cancelTouch = cancelTouch; return advancedPointer }
        if (ctl.editHeld) { ctl.useEdit(); st.soundPanel("ws").editBlocked = true }
        val slot = advanced.slotAt(x, y)
        if (slot > 0) { WsSoundFace.moveSlot(ctl, slot - st.instrument); return true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) { WsSoundFace.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true }
        if (name.contains(x, y)) { WsSoundFace.rename(ctl); return true }
        if (listen.contains(x, y)) { WsSoundFace.cycleLane(ctl, 1); return true }
        if (previous.contains(x, y) || next.contains(x, y)) { st.instField = 2 + ((st.instField - 2).coerceIn(0, controls.lastIndex) + if (next.contains(x, y)) 1 else controls.lastIndex).mod(controls.size); return true }
        for (i in controls.indices) if (controls[i].contains(x, y)) {
            st.instField = i + 2
            val actual = WsInstrumentView.sound(song, st)
            val ins = actual ?: defaultWsInstrument(st.instrument)
            if (WsSoundMacros.reason(i, ins, song.ws, st.listenChannel) != null) return true
            if (i == 0 || i == 4 && ins.kind != WsKind.SAMPLE) {
                WsSoundFace.set(ctl, i, PanelWidgets.railValue(x, rails[i].left, rails[i].right, 0, WsSoundMacros.maximum(i, ins, song.ws)))
                return true
            }
            drag.begin(session, "Set ${WsSoundMacros.title(i, ins)}", Target(st.instrument, st.listenChannel, st.soundPanel("ws").revision, i, ins, actual, song.ws.waves, song.ws.samples, rails[i].left, rails[i].right))
            st.soundPanel("ws").cancelTouch = cancelTouch
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val t = drag.live(session) ?: return
        if (!WsSoundFace.friendly(st) || st.instrument != t.slot || st.listenChannel != t.lane || st.soundPanel("ws").revision != t.revision || song.wsInstrument(t.slot) !== t.expected || song.ws.waves !== t.waves || song.ws.samples !== t.samples) { cancel(); return }
        val value = PanelWidgets.railValue(x, t.left, t.right, 0, WsSoundMacros.maximum(t.control, t.original, song.ws))
        val changed = WsSoundMacros.apply(t.control, t.original, value, song.ws, t.lane)
        if (changed == t.expected) return
        if (session.setWsInstrument(t.slot, changed, "Set ${WsSoundMacros.title(t.control, t.original)}")) WsSoundFace.heard(ctl)
        t.expected = song.wsInstrument(t.slot)
    }
    override fun move(x: Float, y: Float) { if (advancedPointer) advanced.move(x, y) else setFromX(x) }
    override fun up(x: Float, y: Float) { if (advancedPointer) advanced.up(x, y); cancel() }
    override fun cancel() {
        if (st.soundPanel("ws").cancelTouch === cancelTouch) st.soundPanel("ws").cancelTouch = null
        drag.finish(session)
        if (advancedPointer) { advancedPointer = false; advanced.cancel() }
    }
}
