package org.thortracker.system.gb

import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** A continuous console-like instrument face, not a second document or a bank of register aliases. */
internal class PulseSoundPainter(v: PaneView) : Painter(v) {
    private companion object {
        const val SHELL = 0xFFCECAC0.toInt()
        const val GRAPHITE = 0xFF30352D.toInt()
        const val LCD = 0xFFB3BE83.toInt()
        const val LCD_INK = 0xFF253220.toInt()
        const val BURGUNDY = 0xFF79394B.toInt()
    }

    private class Target(val control: GbPulseControl, val slot: Int, val revision: Int, val left: Float, val right: Float)
    private val drag = PanelDrag<Target>()
    private val cancelOwnedTouch: () -> Unit = { finish() }
    private val controls = Array(4) { Rect() }
    private val rails = Array(4) { Rect() }
    private val readout = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val labels = Array(4) { GbSoundView.controlLabel(GbPulseControl.entries[it]) }
    private val editLabels = Array(4) { "Set ${labels[it].lowercase()}" }
    private val helps = arrayOf(
        "Pick a flavour, then hold X to listen. Slide a control left or right, or hold A with a direction, to explore.",
        "Pluck fades away. Held stays steady. Swell grows louder. Try Length next to change its timing.",
        "Give a pluck more room to ring, a swell more time to grow, or a held tone a shorter ending. A swell keeps sounding after it grows.",
        "Keep the pitch steady, let it fall, or send it rising. Pulse 1 can do this; Pulse 2 stays steady. Very high rising notes can stop.",
    )
    private val values = arrayOfNulls<String>(4)
    private val descriptions = arrayOfNulls<String>(4)
    private val indices = IntArray(4)
    private val helpLines = Array(4) { "" }
    private var cachedSound: GbInstrument? = null
    private var cachedWidth = -1f
    private var cachedFocus = -1
    private var cachedUnit = -1f
    private var name = ""
    private var caption = ""
    internal var compact = false
        private set

    private fun selected(): Int = (st.instField - 2).coerceIn(0, 3)

    private fun cache(ins: GbInstrument, width: Float) {
        val focus = selected()
        if (cachedSound === ins && cachedWidth == width && cachedFocus == focus && cachedUnit == ink.unit) return
        if (cachedSound !== ins) {
            for (control in GbPulseControl.entries) {
                val i = control.ordinal
                val index = GbPulseMacros.value(control, ins)
                indices[i] = index ?: -1
                values[i] = if (index == null) "Custom" else GbPulseMacros.valueLabel(control, index)
                descriptions[i] = GbPulseMacros.describe(control, ins)
            }
        }
        name = ink.fit(ins.name, width, ink.unit * 1.05f)
        caption = ink.fit(descriptions[focus] ?: "", width, ink.unit * 0.8f)
        val text = if (focus == GbPulseControl.LENGTH.ordinal && indices[GbPulseControl.SHAPE.ordinal] < 0)
            "This is a custom shape. Changing Length makes a Pluck; Undo brings it back." else helps[focus]
        // Reflow only on immutable patch, focus or layout changes, never build collections in the renderer.
        var start = 0
        for (line in helpLines.indices) {
            var end = text.length
            while (end > start && ink.labelWidth(text.substring(start, end), ink.unit * 0.8f) > width) {
                val space = text.lastIndexOf(' ', end - 1)
                end = if (space > start) space else end - 1
            }
            helpLines[line] = text.substring(start, end)
            start = end
            while (start < text.length && text[start] == ' ') start++
        }
        cachedSound = ins
        cachedWidth = width
        cachedFocus = focus
        cachedUnit = ink.unit
    }

    override fun draw(c: Surface, area: Rect) {
        val ins = GbSoundView.sound(song, st) ?: return
        val u = ink.unit
        val p = ink.palette
        val contrast = p.id == Palette.CONTRAST.id
        val shell = if (contrast) p.LANE else SHELL
        val text = if (contrast) p.TEXT else GRAPHITE
        val lcd = if (contrast) p.BG else LCD
        val lcdText = if (contrast) p.TEXT else LCD_INK
        val accent = if (contrast) p.FOCUS else BURGUNDY
        val touch = ink.dp(48f)
        compact = area.height() < maxOf(touch * 5.5f, u * 10f + touch * 2) || area.width() < ink.dp(250f)
        val pad = minOf(if (compact) ink.dp(3f) else u * 0.7f, area.width() * 0.03f)
        val l = area.left + pad
        val r = area.right - pad
        for (rect in controls) rect.set(0f, 0f, 0f, 0f)
        previous.set(0f, 0f, 0f, 0f)
        next.set(0f, 0f, 0f, 0f)
        ink.round(c, area, u * 0.5f, shell)
        cache(ins, r - l - pad * 2)
        val lcdH = minOf(u * 3.4f, maxOf(0f, area.height() - touch * 2 - pad * 3))
        readout.set(l, area.top + pad, r, area.top + pad + lcdH)
        ink.round(c, readout, ink.dp(3f), lcd)
        ink.label(c, name, l + pad, readout.top, lcdH * 0.53f, lcdText, true, u * 1.05f)
        if (lcdH >= u * 2.5f) ink.label(c, caption, l + pad, readout.top + lcdH * 0.53f, lcdH * 0.47f, lcdText, size = u * 0.8f)
        if (st.instField == 1) ink.outline(c, readout, p.FOCUS, ink.dp(2f), ink.dp(3f))
        val top = readout.bottom + pad
        if (compact) {
            val navH = touch
            val bodyBottom = maxOf(top + touch, area.bottom - navH - pad)
            val i = selected()
            controls[i].set(l, top, r, bodyBottom)
            drawControl(c, i, text, accent, shell)
            previous.set(l, bodyBottom, l + (r - l) * 0.5f, bodyBottom + navH)
            next.set(previous.right, bodyBottom, r, bodyBottom + navH)
            ink.label(c, "Previous", previous.centerX(), previous.top, navH, text, align = Align.CENTER)
            ink.label(c, "Next control", next.centerX(), next.top, navH, text, align = Align.CENTER)
        } else {
            val helpH = u * 4.2f
            val bottom = area.bottom - helpH - pad
            val mid = (l + r) * 0.5f
            val half = (top + bottom) * 0.5f
            controls[0].set(l, top, mid - pad, half)
            controls[1].set(mid + pad, top, r, half)
            controls[2].set(l, half, mid - pad, bottom)
            controls[3].set(mid + pad, half, r, bottom)
            for (i in controls.indices) drawControl(c, i, text, accent, shell)
            for (i in helpLines.indices) ink.label(c, helpLines[i], l + pad, bottom + i * u, u, text, size = u * 0.8f)
        }
    }

    /** Straight engraved tracks and a dial pointer suggest a pocket instrument; gestures stay linear. */
    private fun drawControl(c: Surface, i: Int, text: Int, accent: Int, shell: Int) {
        val r = controls[i]
        val u = ink.unit
        val line = minOf(u * 1.3f, r.height() * 0.3f)
        val focus = st.instField == i + 2
        val p = ink.palette
        val label = when {
            i == GbPulseControl.LENGTH.ordinal && indices[GbPulseControl.SHAPE.ordinal] < 0 -> "Length (adopts Pluck)"
            i == GbPulseControl.MOTION.ordinal -> "Motion (Pulse 1)"
            else -> labels[i]
        }
        ink.label(c, label, r.left + u * 0.25f, r.top, line, text, true, size = u * 0.85f)
        ink.label(c, values[i] ?: "Custom", r.left + u * 0.25f, r.top + line, line, text, size = u)
        val rail = rails[i]
        val inset = minOf(u * 0.8f, r.width() * 0.08f)
        val y = r.bottom - maxOf(u * 0.7f, r.height() * 0.22f)
        rail.set(r.left + inset, y, r.right - inset, y)
        c.line(rail.left, y, rail.right, y, text, ink.dp(2f))
        val steps = GbPulseMacros.steps(GbPulseControl.entries[i])
        for (k in 0 until steps) {
            val x = rail.left + rail.width() * k / (steps - 1)
            c.line(x, y - ink.dp(3f), x, y + ink.dp(3f), text, ink.dp(1f))
        }
        // Custom is deliberately unpositioned: merely opening Sound never snaps an Advanced patch.
        if (indices[i] >= 0) {
            val x = rail.left + rail.width() * indices[i] / (steps - 1)
            val radius = minOf(u * 0.46f, r.height() * 0.15f)
            c.fillCircle(x, y, radius, shell)
            c.strokeCircle(x, y, radius, accent, ink.dp(2f))
            c.line(x, y - radius * 0.65f, x, y + radius * 0.2f, accent, ink.dp(2f))
        }
        if (focus) {
            val color = when { ctl.editHeld || drag.target != null -> p.WRITE; ctl.auditionHeld -> p.LISTEN; else -> p.FOCUS }
            // Semantic focus lives on a theme-coloured band so even Slate's pale focus contrasts with the shell.
            ink.rect(c, r.left, r.bottom - ink.dp(4f), r.right, r.bottom, p.BG)
            ink.rect(c, r.left + ink.dp(2f), r.bottom - ink.dp(3f), r.right - ink.dp(2f), r.bottom - ink.dp(1f), color)
        }
    }

    internal fun controlRect(control: GbPulseControl): Rect? = controls[control.ordinal].takeIf { it.width() > 0f }
    internal fun railX(control: GbPulseControl, fraction: Float): Float = rails[control.ordinal].let { it.left + it.width() * fraction }
    internal fun navigationRect(forward: Boolean): Rect? = (if (forward) next else previous).takeIf { it.width() > 0f }

    override fun down(x: Float, y: Float): Boolean {
        finish()
        if (!GbSoundView.friendly(st)) return false
        if (ctl.editHeld) ctl.useEdit()
        if (previous.contains(x, y) || next.contains(x, y)) {
            st.instField = 2 + (selected() + if (next.contains(x, y)) 1 else 3).mod(4)
            return true
        }
        if (readout.contains(x, y)) { st.instField = 1; return true }
        for (control in GbPulseControl.entries) {
            val i = control.ordinal
            if (!controls[i].contains(x, y)) continue
            st.instField = i + 2
            val rail = rails[i]
            drag.begin(session, editLabels[i], Target(control, st.instrument, st.gbSoundRevision, rail.left, rail.right))
            st.cancelGbSoundTouch = cancelOwnedTouch
            setFromX(x)
            return true
        }
        return false
    }

    private fun setFromX(x: Float) {
        val target = drag.live(session) ?: return
        if (!GbSoundView.friendly(st) || st.instrument != target.slot || st.gbSoundRevision != target.revision) {
            finish()
            return
        }
        val index = PanelWidgets.railValue(x, target.left, target.right, 0, GbPulseMacros.steps(target.control) - 1)
        if (session.editInstrument(GbBank.PULSE, target.slot, editLabels[target.control.ordinal]) {
                GbPulseMacros.apply(target.control, it, index)
            }) GbSoundView.heard(ctl)
    }

    override fun move(x: Float, y: Float) = setFromX(x)
    override fun up(x: Float, y: Float) = finish()
    override fun cancel() = finish()

    private fun finish() {
        if (st.cancelGbSoundTouch === cancelOwnedTouch) st.cancelGbSoundTouch = null
        drag.finish(session)
    }
}
