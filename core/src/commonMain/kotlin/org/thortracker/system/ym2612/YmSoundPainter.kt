package org.thortracker.system.ym2612

import kotlin.math.cos
import kotlin.math.sin
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.system.panel.PixelLabels
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** A black FM tone console: one large tone dial, four supporting strips, and a useful named cartridge bank. */
internal class YmSoundPainter(v: PaneView) : Painter(v) {
    private companion object {
        const val SHELL = 0xFF17191D.toInt()
        const val RECESS = 0xFF343940.toInt()
        const val SILVER = 0xFFD9DDE2.toInt()
        const val BLUE = 0xFF4CA9F0.toInt()
        const val RED = 0xFFC95151.toInt()
        val dialX = FloatArray(128) { cos((2.35 + it / 127.0 * 4.72)).toFloat() }
        val dialY = FloatArray(128) { sin((2.35 + it / 127.0 * 4.72)).toFloat() }
        val scopes = arrayOf("Modulation, not volume", "Routing + voice balance", "Modulator harmonics", "Feedback bite", "Output envelope")
        val editLabels = Array(5) { "Set ${YmSoundControl.entries[it].label}" }
    }
    private val advanced = YmInstrumentPainter(v)
    private val modes = Array(2) { Rect() }
    private val body = Rect()
    private val nameRect = Rect()
    private val controls = Array(5) { Rect() }
    private val rails = Array(5) { Rect() }
    private val slots = Array(YmSystem.SLOTS) { Rect() }
    private val slotNames = Array(YmSystem.SLOTS) { "" }
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val previousControl = Rect()
    private val nextControl = Rect()
    private val values = Array(5) { "" }
    private val indices = IntArray(5)
    private val enabled = BooleanArray(5)
    private var cached: YmFmInstrument? = null
    private var cachedSlots: List<YmInstrument?>? = null
    private var cachedBankWidth = -1f
    private var cachedUnit = -1f
    private var slotTitle = ""
    private var cachedPanelWidth = -1f
    private var emptySlot = -1
    private var emptySound: YmFmInstrument? = null
    private var status = ""
    private var drawnSlot = -1
    private var advancedPointer = false
    internal var compact = false
        private set
    private class Target(val control: YmSoundControl, val slot: Int, val revision: Int, val left: Float, val right: Float,
                         val original: YmFmInstrument, var expected: YmFmInstrument?)
    private val drag = PanelDrag<Target>()
    private val cancelOwnedTouch: () -> Unit = { cancel() }

    private fun cache(ins: YmFmInstrument, bankWidth: Float, panelWidth: Float) {
        if (cached !== ins || drawnSlot != st.instrument || cachedPanelWidth != panelWidth || cachedUnit != ink.unit) {
            for (control in YmSoundControl.entries) {
                val i = control.ordinal
                enabled[i] = YmSoundMacros.enabled(control, ins)
                indices[i] = YmSoundMacros.value(control, ins) ?: -1
                values[i] = when {
                    !enabled[i] -> if (control == YmSoundControl.CONTOUR) "SSG: Advanced" else "No modulators"
                    indices[i] < 0 -> "Custom"
                    else -> YmSoundMacros.label(control, indices[i])
                }
            }
            slotTitle = ink.fit(if (YmInstrumentView.sound(song, st) == null) "Empty / Edit to create" else ins.name.ifEmpty { "Unnamed" },
                panelWidth, ink.unit * 1.05f, true)
            status = when {
                ins.hasCh3 -> "Custom tuning / Advanced"
                ins.operators.any { it.ssgEg and 8 != 0 } -> "SSG envelopes / Advanced"
                ins.operators.any { it.dt != 0 } -> "Detuned voice / Advanced"
                ins.sweep.active -> "Pitch sweep / Advanced"
                ins.ams != 0 || ins.fms != 0 -> "LFO depth / Advanced"
                else -> "FM tone console"
            }
            cached = ins
            drawnSlot = st.instrument
            cachedPanelWidth = panelWidth
        }
        if (cachedSlots !== song.ym.instruments || cachedBankWidth != bankWidth || cachedUnit != ink.unit) {
            for (i in slotNames.indices) {
                val item = song.ym.instrument(i + 1)
                val type = when (item) { is YmPsgInstrument -> "P "; is YmPcmInstrument -> "D "; else -> "" }
                slotNames[i] = ink.fit(hex2(i + 1) + " " + type + (item?.name?.ifEmpty { "Unnamed" } ?: "Empty"), bankWidth - ink.dp(12f), ink.unit * 0.85f)
            }
            cachedSlots = song.ym.instruments
            cachedBankWidth = bankWidth
            cachedUnit = ink.unit
        }
    }

    override fun draw(c: Surface, area: Rect) {
        val fm = YmInstrumentView.fmPages(song, st)
        val p = ink.palette
        val contrast = p.id == Palette.CONTRAST.id
        val shell = if (contrast) p.BG else SHELL
        val text = if (contrast) p.TEXT else SILVER
        val blue = if (contrast) p.FOCUS else BLUE
        val touch = ink.dp(48f)
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        for (r in slots) r.set(0f, 0f, 0f, 0f)
        previousControl.set(0f, 0f, 0f, 0f)
        nextControl.set(0f, 0f, 0f, 0f)
        if (!fm) {
            modes[0].set(0f, 0f, 0f, 0f); modes[1].set(0f, 0f, 0f, 0f)
            advanced.draw(c, area)
            return
        }
        c.fillRect(area.left, area.top, area.right, area.bottom, shell)
        val modeW = minOf(area.width() * 0.5f, maxOf(touch * 2, ink.unit * 7))
        modes[0].set(area.left, area.top, area.left + modeW, area.top + touch)
        modes[1].set(modes[0].right, area.top, modes[0].right + modeW, area.top + touch)
        for (i in 0..1) {
            PanelWidgets.segment(ink, c, modes[i], st.ymPanel.advanced == (i == 1), blue)
            ink.label(c, if (i == 0) "Sound" else "Advanced", modes[i].centerX(), area.top, touch, p.TEXT, true, align = Align.CENTER)
        }
        if (area.right - modes[1].right >= ink.dp(130f)) {
            PixelLabels.draw(c, "FM / YM2612", modes[1].right + ink.dp(12f), area.top + touch * 0.5f - ink.dp(7f),
                ink.dp(2f), blue, area.right - modes[1].right - ink.dp(24f))
        }
        if (st.instField == -1) ink.outline(c, modes[if (st.ymPanel.advanced) 1 else 0], p.FOCUS, ink.dp(2f))
        body.set(area.left, area.top + touch, area.right, area.bottom)
        if (st.ymPanel.advanced) { advanced.draw(c, body); return }
        val actual = YmInstrumentView.sound(song, st) as? YmFmInstrument
        if (actual == null && emptySlot != st.instrument) { emptySlot = st.instrument; emptySound = defaultYmFm(st.instrument) }
        val ins = actual ?: emptySound!!
        val pad = ink.dp(8f)
        val bankW = minOf(ink.dp(180f), maxOf(ink.dp(104f), area.width() * 0.24f)).coerceAtMost(area.width() * 0.4f)
        cache(ins, bankW, area.width() - bankW - pad * 2)
        val rowH = maxOf(touch, ink.unit * 1.65f)
        val count = ((body.height() - touch) / rowH).toInt().coerceIn(1, YmSystem.SLOTS)
        val start = ((st.instrument - 1) / count) * count
        for (i in 0 until count) {
            val slot = start + i
            if (slot >= YmSystem.SLOTS) break
            val r = slots[slot]
            r.set(area.left, body.top + i * rowH, area.left + bankW, minOf(body.bottom - touch, body.top + (i + 1) * rowH))
            if (slot + 1 == st.instrument) {
                c.fillRect(r.left, r.top, r.right, r.bottom, if (contrast) p.LANE else RECESS)
                c.fillRect(r.left, r.top, r.left + ink.dp(3f), r.bottom, blue)
            }
            ink.label(c, slotNames[slot], r.left + ink.dp(6f), r.top, r.height(), text, slot + 1 == st.instrument, ink.unit * 0.85f)
        }
        previousSlot.set(area.left, body.bottom - touch, area.left + bankW * 0.5f, body.bottom)
        nextSlot.set(previousSlot.right, previousSlot.top, area.left + bankW, body.bottom)
        ink.label(c, "-", previousSlot.centerX(), previousSlot.top, touch, text, true, align = Align.CENTER)
        ink.label(c, "+", nextSlot.centerX(), nextSlot.top, touch, text, true, align = Align.CENTER)
        val left = area.left + bankW + pad
        val right = area.right - pad
        val width = right - left
        nameRect.set(left, body.top, right, body.top + touch)
        // Dynamic names use the normal readable type; engraved fixed legends use the shared crisp pixel alphabet.
        ink.label(c, slotTitle, left, nameRect.top, touch, text, true, ink.unit * 1.05f)
        if (st.instField == 1) ink.outline(c, nameRect, p.FOCUS, ink.dp(2f))
        val top = nameRect.bottom
        val bottom = body.bottom - ink.unit * 1.5f
        compact = width < ink.dp(360f) || bottom - top < maxOf(touch * 4, ink.unit * 14.8f)
        if (compact) {
            val index = (st.instField - 2).coerceIn(0, 4)
            controls[index].set(left, top, right, maxOf(top, bottom - touch))
            drawControl(c, index, true, shell, text, blue, contrast)
            previousControl.set(left, bottom - touch, left + width * 0.5f, bottom)
            nextControl.set(previousControl.right, bottom - touch, right, bottom)
            ink.label(c, "Previous", previousControl.centerX(), previousControl.top, touch, text, align = Align.CENTER, size = ink.unit * 0.85f)
            ink.label(c, "Next", nextControl.centerX(), nextControl.top, touch, text, align = Align.CENTER, size = ink.unit * 0.85f)
        } else {
            val divide = left + width * 0.48f
            controls[0].set(left, top, divide - pad, bottom)
            drawControl(c, 0, true, shell, text, blue, contrast)
            val h = (bottom - top) / 4
            for (i in 1..4) {
                controls[i].set(divide + pad, top + (i - 1) * h, right, top + i * h)
                drawControl(c, i, false, shell, text, blue, contrast)
            }
        }
        ink.label(c, if (st.instField == YmSoundControl.STRUCTURE.ordinal + 2) scopes[YmSoundControl.STRUCTURE.ordinal] else status,
            left, bottom, ink.unit * 1.5f, text, size = ink.unit * 0.75f)
    }

    private fun drawControl(c: Surface, i: Int, dial: Boolean, shell: Int, text: Int, blue: Int, contrast: Boolean) {
        val r = controls[i]
        if (r.height() <= 0f) return
        val p = ink.palette
        val accent = if (i == YmSoundControl.EDGE.ordinal) (if (contrast) p.WRITE else RED) else blue
        val focused = st.instField == i + 2
        val headerH = minOf(maxOf(ink.unit * 1.25f, ink.dp(19f)), maxOf(ink.dp(12f), (r.height() - ink.dp(24f)) * 0.5f))
        val pixel = minOf(ink.dp(2f), (headerH - ink.dp(5f)) / PixelLabels.HEIGHT).coerceAtLeast(1f)
        PixelLabels.draw(c, YmSoundControl.entries[i].label, r.left, r.top + ink.dp(3f), pixel, text, r.width())
        ink.label(c, values[i], r.left, r.top + headerH, headerH, text, true,
            minOf(ink.unit * 0.9f, headerH * 0.8f))
        val rail = rails[i]
        rail.set(r.left + ink.dp(12f), r.bottom - ink.dp(12f), r.right - ink.dp(12f), r.bottom - ink.dp(12f))
        if (dial) {
            val radius = minOf(r.width() * 0.34f, (r.height() - headerH * 3) * 0.42f).coerceAtLeast(0f)
            val cx = r.centerX()
            val cy = r.top + headerH * 2 + radius + ink.dp(5f)
            if (radius > ink.dp(8f)) {
                c.fillCircle(cx, cy, radius, if (contrast) p.LANE else RECESS)
                c.strokeCircle(cx, cy, radius, if (enabled[i]) accent else p.FAINT, ink.dp(2f))
                for (tick in 0..10) {
                    val k = tick * 127 / 10
                    c.line(cx + dialX[k] * radius * 1.09f, cy + dialY[k] * radius * 1.09f,
                        cx + dialX[k] * radius * 1.17f, cy + dialY[k] * radius * 1.17f, text, ink.dp(1f))
                }
                if (enabled[i] && indices[i] >= 0) {
                    val k = indices[i] * 127 / YmSoundControl.entries[i].maximum
                    c.line(cx + dialX[k] * radius * 0.35f, cy + dialY[k] * radius * 0.35f,
                        cx + dialX[k] * radius * 0.86f, cy + dialY[k] * radius * 0.86f, accent, ink.dp(4f))
                }
            }
            if (r.height() > headerH * 3 + radius * 2 + ink.dp(25f))
                ink.label(c, scopes[i], r.centerX(), r.bottom - headerH - ink.dp(25f), headerH, text, size = ink.unit * 0.75f, align = Align.CENTER)
        } else if (r.height() > headerH * 3 + ink.dp(20f)) {
            ink.label(c, scopes[i], r.left, r.top + headerH * 2, headerH, text, size = ink.unit * 0.75f)
        }
        if (enabled[i]) {
            if (indices[i] >= 0) PanelWidgets.rail(ink, c, rail.left, rail.right, rail.top,
                indices[i].toFloat() / YmSoundControl.entries[i].maximum, YmSoundControl.entries[i].maximum, accent, focused, drag.target != null || ctl.editHeld)
            else c.line(rail.left, rail.top, rail.right, rail.top, p.FAINT, ink.dp(2f))
        }
        if (focused) {
            c.fillRect(r.left, r.bottom - ink.dp(3f), r.right, r.bottom, p.BG)
            c.fillRect(r.left, r.bottom - ink.dp(2f), r.right, r.bottom, if (ctl.editHeld || drag.target != null) p.WRITE else p.FOCUS)
        }
        // Only a separator, never a repeated rounded-card container.
        c.line(r.left, r.bottom, r.right, r.bottom, if (contrast) p.RULE else shell, ink.dp(1f))
    }

    internal fun modeRect(advanced: Boolean): Rect? = modes[if (advanced) 1 else 0].takeIf { it.width() > 0 }
    internal fun controlRect(index: Int): Rect? = controls.getOrNull(index)?.takeIf { it.width() > 0 }
    internal fun valueX(index: Int, fraction: Float): Float = rails[index].let { it.left + it.width() * fraction }
    internal fun navigationRect(forward: Boolean): Rect? = (if (forward) nextControl else previousControl).takeIf { it.width() > 0 }
    internal fun slotRect(slot: Int): Rect? = slots.getOrNull(slot - 1)?.takeIf { it.width() > 0 }
    internal fun slotNavigationRect(forward: Boolean): Rect = if (forward) nextSlot else previousSlot

    override fun down(x: Float, y: Float): Boolean {
        cancel()
        if (YmInstrumentView.fmPages(song, st)) for (i in 0..1) if (modes[i].contains(x, y)) {
            YmSoundFace.selectMode(ctl, i == 1)
            return true
        }
        if (!YmSoundFace.friendly(song, st)) {
            advancedPointer = advanced.down(x, y)
            if (advancedPointer) st.ymPanel.cancelTouch = cancelOwnedTouch
            return advancedPointer
        }
        if (ctl.editHeld) { ctl.useEdit(); st.ymPanel.editBlocked = true }
        for (i in slots.indices) if (slots[i].contains(x, y)) {
            st.instField = 0
            YmInstrumentView.moveSlot(ctl, i + 1 - st.instrument)
            return true
        }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) {
            st.instField = 0
            YmInstrumentView.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1)
            return true
        }
        if (nameRect.contains(x, y)) { st.instField = 1; return true }
        if (previousControl.contains(x, y) || nextControl.contains(x, y)) {
            st.instField = 2 + ((st.instField - 2).coerceIn(0, 4) + if (nextControl.contains(x, y)) 1 else 4).mod(5)
            return true
        }
        for (control in YmSoundControl.entries) {
            val i = control.ordinal
            if (!controls[i].contains(x, y)) continue
            st.instField = i + 2
            val actual = YmInstrumentView.sound(song, st) as? YmFmInstrument
            val ins = actual ?: defaultYmFm(st.instrument)
            if (!YmSoundMacros.enabled(control, ins)) return true
            drag.begin(session, editLabels[i], Target(control, st.instrument, st.ymPanel.revision, rails[i].left, rails[i].right, ins, actual))
            st.ymPanel.cancelTouch = cancelOwnedTouch
            setFromX(x)
            return true
        }
        return false
    }

    private fun setFromX(x: Float) {
        val target = drag.live(session) ?: return
        if (!YmSoundFace.friendly(song, st) || st.instrument != target.slot || st.ymPanel.revision != target.revision ||
            YmInstrumentView.sound(song, st) !== target.expected) { cancel(); return }
        val value = PanelWidgets.railValue(x, target.left, target.right, 0, target.control.maximum)
        val next = YmSoundMacros.apply(target.control, target.original, value)
        if (next == target.expected || (target.expected == null && next === target.original)) return
        if (session.setYmInstrument(target.slot, next, editLabels[target.control.ordinal])) YmSoundFace.heard(ctl)
        target.expected = session.song.ymInstrument(target.slot) as? YmFmInstrument
    }

    override fun move(x: Float, y: Float) { if (advancedPointer) advanced.move(x, y) else setFromX(x) }
    override fun up(x: Float, y: Float) { if (advancedPointer) advanced.up(x, y); cancel() }
    override fun cancel() {
        if (st.ymPanel.cancelTouch === cancelOwnedTouch) st.ymPanel.cancelTouch = null
        drag.finish(session)
        if (advancedPointer) { advancedPointer = false; advanced.cancel() }
    }
}
