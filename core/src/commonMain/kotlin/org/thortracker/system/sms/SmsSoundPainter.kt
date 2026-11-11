package org.thortracker.system.sms

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Cartridge module: a large voice window beside horizontal console faders, not the operator register grid. */
internal class SmsSoundPainter(v: PaneView) : Painter(v) {
    private val advanced = SmsInstrumentPainter(v)
    private val modes = Array(2) { Rect() }
    private val controls = Array(5) { Rect() }
    private val rails = Array(5) { Rect() }
    private val body = Rect()
    private val moduleBounds = Rect()
    private val name = Rect()
    private val listen = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val values = Array(5) { "" }
    private val indices = IntArray(5)
    private val available = BooleanArray(5)
    private var cached: SmsInstrument? = null
    private var emptySlot = -1
    private var emptyKind: SmsInstrumentKind? = null
    private var emptySound: SmsInstrument = SmsPsgInstrument()
    private var advancedPointer = false
    private var drawnSlot = -1
    internal var compact = false
        private set
    private class Target(val control: SmsSoundControl, val slot: Int, val revision: Int, val left: Float, val right: Float,
                         val original: SmsInstrument, var expected: SmsInstrument?)
    private val drag = PanelDrag<Target>()
    private val cancelOwned: () -> Unit = { cancel() }
    private fun actual() = SmsInstrumentView.sound(song, st)
    private fun shown(): SmsInstrument {
        actual()?.let { return it }
        val kind = SmsInstrumentView.kind(song, st)
        if (emptySlot != st.instrument || emptyKind != kind) {
            emptySlot = st.instrument; emptyKind = kind; emptySound = defaultSmsInstrument(st.instrument, kind)
        }
        return emptySound
    }
    private fun cache(ins: SmsInstrument) {
        if (cached === ins) return
        val list = SmsSoundMacros.controls(ins.kind)
        for (i in list.indices) {
            val control = list[i]
            indices[i] = SmsSoundMacros.value(control, ins) ?: -1
            available[i] = SmsSoundMacros.enabled(control, ins)
            values[i] = when {
                !available[i] -> when (ins) {
                    is SmsFmInstrument -> "Select Custom voice"
                    is SmsDrumKit -> "Choose Fixed kit"
                    else -> "Pitch macro / Advanced"
                }
                indices[i] < 0 -> "Custom"
                else -> SmsSoundMacros.label(control, indices[i])
            }
        }
        cached = ins
    }
    override fun draw(c: Surface, area: Rect) {
        val p = ink.palette
        val panel = st.soundPanel("sms")
        if (drawnSlot != st.instrument) { cancel(); drawnSlot = st.instrument }
        val touch = ink.dp(48f)
        val mw = minOf((area.width() - touch) / 2, maxOf(touch * 2, ink.unit * 7))
        val accent = if (song.sms.layout.stereo) p.channel(1) else p.channel(0)
        for (i in 0..1) {
            modes[i].set(area.left + i * mw, area.top, area.left + (i + 1) * mw, area.top + touch)
            PanelWidgets.segment(ink, c, modes[i], panel.advanced == (i == 1), accent)
            ink.label(c, if (i == 0) "Sound" else "Advanced", modes[i].centerX(), area.top, touch, p.TEXT, true, align = Align.CENTER)
        }
        if (st.instField == -1) ink.outline(c, modes[if (panel.advanced) 1 else 0], p.FOCUS, ink.dp(2f))
        listen.set(area.right - touch, area.top, area.right, area.top + touch)
        ink.label(c, "Listen", listen.centerX(), listen.top, touch / 2, p.TEXT, size = ink.unit * 0.7f, align = Align.CENTER)
        ink.label(c, "${SmsInstrumentView.listenChannel(song, st, actual()) + 1}", listen.centerX(), listen.top + touch / 2, touch / 2, accent, true, align = Align.CENTER)
        body.set(area.left, area.top + touch, area.right, area.bottom)
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        previous.set(0f, 0f, 0f, 0f); next.set(0f, 0f, 0f, 0f)
        if (panel.advanced) { advanced.draw(c, body); return }
        val bank = advanced.drawSidebar(c, body, false)
        val pad = ink.dp(8f)
        val left = bank.right + pad
        val right = area.right - pad
        val shell = if (p.id == Palette.CONTRAST.id) p.BG else Palette.mix(p.BG, accent, 0.07f)
        c.fillRect(bank.right, bank.top, area.right, area.bottom, shell)
        val ins = shown()
        cache(ins)
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
        val list = SmsSoundMacros.controls(ins.kind)
        val top = name.bottom
        val bottom = area.bottom - ink.dp(34f)
        val rackRows = if (list.size > 3) 3 else list.size
        compact = right - left < ink.dp(480f) || bottom - top < ink.dp(64f) * rackRows
        if (compact) {
            val i = (st.instField - 2).coerceIn(0, list.lastIndex)
            previous.set(left, top, left + touch, bottom)
            next.set(right - touch, top, right, bottom)
            controls[i].set(previous.right, top, next.left, bottom)
            drawFader(c, i, list[i], accent)
            ink.label(c, "<", previous.centerX(), top, previous.height(), p.TEXT, true, align = Align.CENTER)
            ink.label(c, ">", next.centerX(), top, next.height(), p.TEXT, true, align = Align.CENTER)
        } else {
            val divide = left + (right - left) * if (list.size > 3) 0.30f else 0.38f
            moduleBounds.set(left, top, divide - pad, bottom)
            module(c, moduleBounds, ins, accent)
            val rackLeft = divide + pad
            val h = (bottom - top) / rackRows
            val middle = (rackLeft + right) / 2
            for (i in list.indices) {
                if (list.size <= 3) {
                    controls[i].set(rackLeft, top + i * h, right, top + (i + 1) * h)
                } else if (i == 0) {
                    // Voice / kit spans the rack; its dependent controls sit in two columns below.
                    controls[i].set(rackLeft, top, right, top + h)
                } else {
                    val row = (i - 1) / 2 + 1
                    val column = (i - 1) % 2
                    controls[i].set(if (column == 0) rackLeft else middle + pad, top + row * h,
                        if (column == 0) middle - pad else right, top + (row + 1) * h)
                }
                drawFader(c, i, list[i], accent)
            }
        }
        val control = list[(st.instField - 2).coerceIn(0, list.lastIndex)]
        ink.label(c, ink.fit(control.hint, right - left, ink.unit * 0.7f), left, bottom, ink.dp(17f), p.TEXT, size = ink.unit * 0.7f)
        val note = when (ins) {
            is SmsFmInstrument -> "One custom voice: latest instrument load wins"
            is SmsDrumKit -> "Five drums / three shared pitch registers"
            else -> if (song.sms.layout.stereo) "Game Gear stereo: pattern pan, not a patch control" else "Square 1 / 2 / 3 + noise clock"
        }
        ink.label(c, ink.fit(note, right - left, ink.unit * 0.7f), left, bottom + ink.dp(17f), ink.dp(17f), p.DIM, size = ink.unit * 0.7f)
    }
    private fun drawFader(c: Surface, i: Int, control: SmsSoundControl, accent: Int) {
        val r = controls[i]
        if (r.width() <= 0 || r.height() <= 0) return
        val p = ink.palette
        val focused = st.instField == i + 2
        val header = minOf(ink.dp(24f), r.height() * 0.3f)
        ink.label(c, ink.fit(control.label, r.width(), ink.unit * 0.9f, true), r.left, r.top, header, p.TEXT, true, minOf(ink.unit * 0.9f, header * 0.8f))
        ink.label(c, ink.fit(values[i], r.width(), ink.unit * 0.8f), r.left, r.top + header, header, if (available[i]) accent else p.DIM, size = minOf(ink.unit * 0.8f, header * 0.8f))
        val rail = rails[i]
        rail.set(r.left + ink.dp(10f), r.bottom - ink.dp(14f), r.right - ink.dp(10f), r.bottom - ink.dp(14f))
        if (available[i] && indices[i] >= 0) PanelWidgets.rail(ink, c, rail.left, rail.right, rail.top, indices[i].toFloat() / control.maximum, control.maximum, accent, focused, ctl.editHeld || drag.target != null)
        else c.line(rail.left, rail.top, rail.right, rail.top, p.FAINT, ink.dp(2f))
        if (focused) c.fillRect(r.left, r.bottom - ink.dp(2f), r.right, r.bottom, p.FOCUS)
    }
    private fun module(c: Surface, r: Rect, ins: SmsInstrument, accent: Int) {
        val p = ink.palette
        c.fillRound(r.left, r.top, r.right, r.bottom, ink.dp(8f), p.LANE)
        val title = when (ins) { is SmsFmInstrument -> "FM Sound Unit"; is SmsDrumKit -> "Rhythm unit"; else -> if (song.sms.layout.stereo) "Pocket stereo" else "Square console" }
        ink.label(c, ink.fit(title, r.width() - ink.dp(16f), ink.unit, true), r.centerX(), r.top + ink.dp(8f), ink.dp(32f), p.TEXT, true, align = Align.CENTER)
        val cy = r.top + r.height() * 0.48f
        when (ins) {
            is SmsFmInstrument -> {
                val radius = minOf(r.width() / 7, r.height() / 8)
                val x1 = r.left + r.width() / 3
                val x2 = r.left + r.width() * 2 / 3
                c.strokeCircle(x1, cy, radius, accent, ink.dp(3f)); c.strokeCircle(x2, cy, radius, p.TEXT, ink.dp(3f))
                c.line(x1 + radius, cy, x2 - radius, cy, accent, ink.dp(3f))
                ink.label(c, "Tone", x1, cy - ink.dp(12f), ink.dp(24f), p.TEXT, size = ink.unit * 0.7f, align = Align.CENTER)
                ink.label(c, "Out", x2, cy - ink.dp(12f), ink.dp(24f), p.TEXT, size = ink.unit * 0.7f, align = Align.CENTER)
            }
            is SmsDrumKit -> for (i in 0..2) {
                val x = r.left + r.width() * (i + 0.5f) / 3
                val radius = r.width() * (if (i == 0) 0.12f else 0.09f)
                c.strokeCircle(x, cy, radius, accent, ink.dp(3f))
                if (i > 0) c.line(x - radius, cy, x + radius, cy, p.TEXT, ink.dp(2f))
            }
            else -> {
                val x = r.left + r.width() * 0.12f
                val width = r.width() * 0.76f / 8
                for (i in 0..7) {
                    val y = cy + if (i % 2 == 0) -ink.dp(18f) else ink.dp(18f)
                    c.line(x + i * width, y, x + (i + 1) * width, y, accent, ink.dp(3f))
                    if (i < 7) c.line(x + (i + 1) * width, y, x + (i + 1) * width, cy * 2 - y, accent, ink.dp(3f))
                }
            }
        }
        ink.label(c, when (ins) { is SmsFmInstrument -> if (ins.isCustom) "Shared custom patch" else OpllPatch.PATCH_NAMES[ins.patch]; is SmsDrumKit -> if (ins.fixed) "Fixed tuning" else "Note tuning"; else -> "SN76489" }, r.centerX(), r.bottom - ink.dp(55f), ink.dp(24f), p.TEXT, size = ink.unit * 0.8f, align = Align.CENTER)
        // Cartridge edge contacts are static artwork, deliberately distinct from AY's shared bus and keybed.
        for (i in 0..7) {
            val x = r.left + r.width() * (i + 1) / 9
            c.fillRect(x - ink.dp(2f), r.bottom - ink.dp(16f), x + ink.dp(2f), r.bottom, accent)
        }
    }
    internal fun modeRect(isAdvanced: Boolean) = modes[if (isAdvanced) 1 else 0]
    internal fun controlRect(i: Int) = controls[i].takeIf { it.width() > 0 }
    internal fun navigationRect(forward: Boolean) = (if (forward) next else previous).takeIf { it.width() > 0 }
    internal fun slotNavigationRect(forward: Boolean) = if (forward) nextSlot else previousSlot
    internal fun listenRect() = listen
    internal fun slotRect(slot: Int) = advanced.slotBounds(slot)
    override fun down(x: Float, y: Float): Boolean {
        cancel()
        for (i in 0..1) if (modes[i].contains(x, y)) { SmsSoundFace.selectMode(ctl, i == 1); return true }
        val panel = st.soundPanel("sms")
        if (listen.contains(x, y)) { SmsSoundFace.listen(ctl, 1); return true }
        if (panel.advanced) { advancedPointer = advanced.down(x, y); if (advancedPointer) panel.cancelTouch = cancelOwned; return advancedPointer }
        if (ctl.editHeld) { ctl.useEdit(); panel.editBlocked = true }
        val slot = advanced.slotAt(x, y)
        if (slot > 0) { SmsSoundFace.moveSlot(ctl, slot - st.instrument); SmsInstrumentView.publish(ctl); return true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) { SmsSoundFace.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true }
        if (name.contains(x, y)) {
            st.instField = 1
            val chosen = st.instrument
            val expected = actual()
            val original = shown()
            val panel = st.soundPanel("sms")
            panel.invalidate()
            val revision = panel.revision
            session.requestText("Sound name", original.name, SmsInstrumentView.NAME_MAX) { text ->
                if (st.instrument == chosen && panel.revision == revision && actual() === expected)
                    session.setSmsInstrument(chosen, original.named(text), "Rename sound")
            }
            return true
        }
        val list = SmsSoundFace.controls(song, st)
        if (previous.contains(x, y) || next.contains(x, y)) {
            st.instField = 2 + ((st.instField - 2).coerceIn(0, list.lastIndex) + if (next.contains(x, y)) 1 else list.lastIndex).mod(list.size)
            return true
        }
        for (i in list.indices) {
            if (!controls[i].contains(x, y)) continue
            st.instField = i + 2
            val control = list[i]
            val ins = shown()
            if (!SmsSoundMacros.enabled(control, ins)) return true
            drag.begin(session, "Set ${control.label}", Target(control, st.instrument, panel.revision, rails[i].left, rails[i].right, ins, actual()))
            panel.cancelTouch = cancelOwned
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val t = drag.live(session) ?: return
        if (!SmsSoundFace.friendly(st) || st.instrument != t.slot || st.soundPanel("sms").revision != t.revision || actual() !== t.expected) { cancel(); return }
        val n = PanelWidgets.railValue(x, t.left, t.right, 0, t.control.maximum)
        val ins = SmsSoundMacros.apply(t.control, t.original, n)
        if (ins == t.expected || (t.expected == null && ins === t.original)) return
        if (session.setSmsInstrument(t.slot, ins, "Set ${t.control.label}")) SmsSoundFace.heard(ctl)
        t.expected = actual()
    }
    override fun move(x: Float, y: Float) { if (advancedPointer) advanced.move(x, y) else setFromX(x) }
    override fun up(x: Float, y: Float) { if (advancedPointer) advanced.up(x, y); cancel() }
    override fun cancel() {
        if (st.soundPanel("sms").cancelTouch === cancelOwned) st.soundPanel("sms").cancelTouch = null
        drag.finish(session)
        if (advancedPointer) { advancedPointer = false; advanced.cancel() }
    }
}
