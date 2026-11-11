package org.thortracker.system.gb

import kotlin.math.roundToInt
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.system.panel.PixelLabels
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Four-shade LCD: pixel lettering, real signal cells and block selectors, not themed register sliders. */
internal object GbLcdColors {
    const val INK = 0xFF17260F.toInt()
    const val DARK = 0xFF3F5A2A.toInt()
    const val MID = 0xFF7D9650.toInt()
    const val LIGHT = 0xFFB8CA7B.toInt()
}

internal class GbLcdPainter(v: PaneView) : Painter(v) {
    private class Target(val control: Int, val kind: GbBank, val slot: Int, val revision: Int, val left: Float, val right: Float)
    private val drag = PanelDrag<Target>()
    private val cancelOwnedTouch: () -> Unit = { finish() }
    private val controls = Array(4) { Rect() }
    private val rails = Array(4) { Rect() }
    private val readout = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val diagram = Rect()
    private val values = Array(4) { "" }
    private val indices = IntArray(4)
    private val editLabels = Array(3) { bank -> Array(GbMusicalMacros.count(GbBank.entries[bank])) { "Set ${GbMusicalMacros.label(GbBank.entries[bank], it).lowercase()}" } }
    private val noise = Array(2) { width -> BooleanArray(64).also { SoundTimeline.lfsrBits(width, it) } }
    private var cachedSound: GbInstrument? = null
    private var cachedNote = ""
    private var cachedColumns = -1
    private var noteFirst = ""
    private var noteSecond = ""
    internal var compact = false
        private set

    private fun selected(): Int = (st.instField - 2).coerceIn(0, GbMusicalMacros.count(GbSoundView.kind(st)) - 1)
    private var pixel = 1f
    private val contrast: Boolean get() = ink.palette.id == Palette.CONTRAST.id
    private val light: Int get() = if (contrast) ink.palette.BG else GbLcdColors.LIGHT
    private val dark: Int get() = if (contrast) ink.palette.TEXT else GbLcdColors.INK
    private val shade: Int get() = if (contrast) ink.palette.RULE else GbLcdColors.MID

    private fun text(c: Surface, value: String, x: Float, y: Float, width: Float, color: Int = dark) =
        PixelLabels.draw(c, value, x, y, pixel, color, width)

    override fun draw(c: Surface, area: Rect) {
        val ins = GbSoundView.sound(song, st) ?: return
        val touch = ink.dp(48f)
        // Small panes sacrifice pixel magnification before sacrificing a physical control target.
        pixel = minOf((ink.unit / 9f).roundToInt(), ((area.width() - 8) / 120).toInt(),
            ((area.height() - touch * 2) / 35).toInt()).coerceAtLeast(1).toFloat()
        val count = GbMusicalMacros.count(ins.kind)
        if (cachedSound !== ins) {
            for (i in 0 until count) {
                val index = GbMusicalMacros.value(i, ins)
                indices[i] = index ?: -1
                values[i] = if (index == null) "Custom" else GbMusicalMacros.valueLabel(ins.kind, i, index)
            }
            cachedSound = ins
        }
        val pad = pixel * 4
        val line = pixel * 10
        val l = area.left + pad
        val r = area.right - pad
        val width = maxOf(0f, r - l)
        compact = area.height() < touch * 3 + line * 5 || width < ink.dp(300f)
        for (rect in controls) rect.set(0f, 0f, 0f, 0f)
        previous.set(0f, 0f, 0f, 0f)
        next.set(0f, 0f, 0f, 0f)
        ink.rect(c, area, light)
        val note = GbMusicalMacros.note(ins, selected())
        val columns = (width / (6 * pixel)).toInt().coerceAtLeast(1)
        if (note != cachedNote || columns != cachedColumns) {
            val split = if (note.length <= columns) note.length else note.lastIndexOf(' ', columns).coerceAtLeast(1)
            noteFirst = note.substring(0, split)
            noteSecond = if (split < note.length) note.substring(split + 1) else ""
            cachedNote = note
            cachedColumns = columns
        }
        val noteH = if (note.isEmpty()) 0f else if (noteSecond.isEmpty()) line else line * 2
        val bottom = area.bottom - pad - noteH
        val controlH = maxOf(touch, line * 3.4f)
        val controlsTop = bottom - if (compact) controlH + touch else controlH * 2
        val nameH = minOf(line * 1.5f, maxOf(0f, controlsTop - area.top - pad))
        readout.set(l, area.top + pad, r, area.top + pad + nameH)
        if (nameH >= pixel * 7) text(c, ins.name, l, readout.top, width)
        if (st.instField == 1) ink.outline(c, readout, dark, pixel)
        diagram.set(l, readout.bottom, r, controlsTop - pad)
        if (diagram.height() >= line * 3) drawDiagrams(c, ins)
        if (compact) {
            val i = selected()
            controls[i].set(l, controlsTop, r, bottom - touch)
            drawControl(c, i, ins.kind)
            previous.set(l, bottom - touch, (l + r) / 2, bottom)
            next.set(previous.right, previous.top, r, bottom)
            text(c, "< PREV", previous.left + pad, previous.centerY() - line / 2, previous.width() - pad)
            text(c, "NEXT >", next.left + pad, next.centerY() - line / 2, next.width() - pad)
        } else {
            val mid = (l + r) / 2
            for (i in 0 until count) {
                val left = if (i % 2 == 0) l else mid + pad
                val right = if (i % 2 == 0 && !(count == 3 && i == 2)) mid - pad else r
                controls[i].set(left, controlsTop + (i / 2) * controlH, right, controlsTop + (i / 2 + 1) * controlH)
                drawControl(c, i, ins.kind)
            }
        }
        if (note.isNotEmpty()) {
            text(c, noteFirst, l, bottom + pixel * 2, width)
            if (noteSecond.isNotEmpty()) text(c, noteSecond, l, bottom + line, width)
        }
    }

    private fun drawControl(c: Surface, i: Int, kind: GbBank) {
        val r = controls[i]
        val line = pixel * 10
        val focus = st.instField == i + 2
        text(c, GbMusicalMacros.label(kind, i), r.left + pixel * 2, r.top + pixel, r.width() - pixel * 4)
        val valueWidth = PixelLabels.width(values[i], pixel)
        if (r.width() > valueWidth + PixelLabels.width(GbMusicalMacros.label(kind, i), pixel) + pixel * 8)
            text(c, values[i], r.right - valueWidth - pixel * 2, r.top + pixel, valueWidth)
        else text(c, values[i], r.left + pixel * 2, r.top + line, r.width() - pixel * 4)
        val rail = rails[i]
        val steps = GbMusicalMacros.steps(kind, i)
        val cell = r.width() / steps
        val y = r.bottom - pixel * 9
        // Position means a discrete choice. Custom has no falsely positioned thumb.
        rail.set(r.left + cell / 2, y, r.right - cell / 2, y)
        for (k in 0 until steps) {
            val x = r.left + cell * k
            ink.rect(c, x + pixel, y, x + cell - pixel, y + pixel * 6, if (indices[i] == k) dark else shade)
            if (indices[i] == k) ink.rect(c, x + pixel * 2, y + pixel * 2, x + cell - pixel * 2, y + pixel * 3, light)
        }
        if (focus) {
            val color = if (contrast) when { ctl.editHeld || drag.target != null -> ink.palette.WRITE; ctl.auditionHeld -> ink.palette.LISTEN; else -> ink.palette.FOCUS } else dark
            ink.outline(c, r, color, pixel)
        }
    }

    private fun drawDiagrams(c: Surface, ins: GbInstrument) {
        val gap = pixel * 6
        val width = (diagram.width() - gap) / 2
        val line = pixel * 10
        text(c, if (ins.kind == GbBank.WAVE) "Wave RAM" else "Timbre", diagram.left, diagram.top, width)
        text(c, "Level / 2 s", diagram.left + width + gap, diagram.top, width)
        val top = diagram.top + line
        val rows = 16
        val cellW = (width / 32).toInt().coerceAtLeast(1).toFloat()
        val cellH = ((diagram.bottom - top) / rows).toInt().coerceAtLeast(1).toFloat()
        val wave = song.gb.waves.getOrNull(ins.wave)
        val cut = SoundTimeline.cutSeconds(ins)
        val sweepCut = if (ins.kind == GbBank.PULSE) SoundTimeline.sweepOffSeconds(ins, ctl.defaultNote()) else null
        for (x in 0 until 32) {
            val signal = when (ins.kind) {
                GbBank.WAVE -> wave?.getOrNull(x)?.coerceIn(0, 15) ?: 0
                GbBank.PULSE -> if (SoundTimeline.DUTY_STEPS[ins.duty.coerceIn(0, 3)][x % 8]) 13 else 2
                GbBank.NOISE -> if (noise[ins.noiseWidth.coerceIn(0, 1)][x]) 13 else 2
            }
            val seconds = x * 2.0 / 31
            val level = if ((cut != null && seconds >= cut) || (sweepCut != null && seconds >= sweepCut)) 0 else SoundTimeline.level(ins, seconds)
            for (y in 0 until rows) {
                val xx = (diagram.left + x * cellW).roundToInt().toFloat()
                val yy = (top + (15 - y) * cellH).roundToInt().toFloat()
                val dotW = maxOf(1f, cellW - pixel)
                val dotH = maxOf(1f, cellH - pixel)
                ink.rect(c, xx, yy, xx + dotW, yy + dotH, if (y == signal) dark else shade)
                val levelX = (xx + width + gap).roundToInt().toFloat()
                ink.rect(c, levelX, yy, levelX + dotW, yy + dotH,
                    if (y < level) { if (contrast) dark else GbLcdColors.DARK } else shade)
            }
        }
    }

    internal fun controlRect(index: Int): Rect? = controls.getOrNull(index)?.takeIf { it.width() > 0f }
    internal fun valueX(index: Int, fraction: Float): Float = rails[index].let { it.left + it.width() * fraction }
    internal fun navigationRect(forward: Boolean): Rect? = (if (forward) next else previous).takeIf { it.width() > 0f }

    override fun down(x: Float, y: Float): Boolean {
        finish()
        if (!GbSoundView.friendly(st)) return false
        if (ctl.editHeld) ctl.useEdit()
        if (previous.contains(x, y) || next.contains(x, y)) {
            val count = GbMusicalMacros.count(GbSoundView.kind(st))
            st.instField = 2 + (selected() + if (next.contains(x, y)) 1 else count - 1).mod(count)
            return true
        }
        if (readout.contains(x, y)) { st.instField = 1; return true }
        if (st.gbPanel.editBlocked) return true
        val kind = GbSoundView.kind(st)
        for (i in 0 until GbMusicalMacros.count(kind)) {
            if (!controls[i].contains(x, y)) continue
            st.instField = i + 2
            val rail = rails[i]
            drag.begin(session, editLabels[kind.ordinal][i], Target(i, kind, st.instrument, st.gbPanel.revision, rail.left, rail.right))
            st.gbPanel.cancelTouch = cancelOwnedTouch
            setFromX(x)
            return true
        }
        return false
    }

    private fun setFromX(x: Float) {
        val target = drag.live(session) ?: return
        if (!GbSoundView.friendly(st) || GbSoundView.kind(st) != target.kind || st.instrument != target.slot || st.gbPanel.revision != target.revision) {
            finish()
            return
        }
        val index = PanelWidgets.railValue(x, target.left, target.right, 0, GbMusicalMacros.steps(target.kind, target.control) - 1)
        if (session.editInstrument(target.kind, target.slot, editLabels[target.kind.ordinal][target.control]) {
                GbMusicalMacros.apply(target.control, it, index)
            }) GbSoundView.heard(ctl)
    }

    override fun move(x: Float, y: Float) = setFromX(x)
    override fun up(x: Float, y: Float) = finish()
    override fun cancel() = finish()

    private fun finish() {
        if (st.gbPanel.cancelTouch === cancelOwnedTouch) st.gbPanel.cancelTouch = null
        val target = drag.finish(session) ?: return
        if (target.kind == GbBank.WAVE && target.control == 0 && st.bank == target.kind.ordinal && st.instrument == target.slot)
            GbSoundView.publish(ctl)
    }
}
