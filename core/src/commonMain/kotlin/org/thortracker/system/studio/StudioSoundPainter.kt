package org.thortracker.system.studio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.system.studio.StudioSoundView.Param
import org.thortracker.ui.Align
import org.thortracker.ui.Palette
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** A steel faceplate with a recessed oscillator strip, two dial banks and an output section. */
internal class StudioSoundPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val modes = Array(2) { Rect() }
    private val controls = Array(Param.entries.size) { Rect() }
    private val waves = Array(2) { Rect() }
    private val rails = Array(Param.entries.size) { Rect() }
    private val name = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val body = Rect()
    private val work = Rect()
    private val group = Rect()
    private val focus = Rect()
    private val drag = PanelDrag<Drag>()
    private val cancelPointer: () -> Unit = { cancel() }
    private var drawnState: ViewState? = null
    private var drawnVersion = -1L
    private var drawnRevision = -1
    private var drawnSlot = -1
    private var drawnLane = -1
    private var drawnAdvanced = false

    private var palette: Palette? = null
    private var signal = 0
    private var plate = 0
    private var edge = 0
    private var well = 0
    private var steel = 0
    private var cachedPatch: MonoPatch? = null
    private val exactValues = Array(Param.entries.size) { "" }
    private var tuneText = ""

    private enum class Travel { SWITCH, DIAL, RAIL }
    private class Drag(val state: ViewState, val slot: Int, val lane: Int, val revision: Int, val param: Param,
        val travel: Travel, val startValue: Int, val startX: Float, val startY: Float, val left: Float, val right: Float,
        val split: Float, val preview: Boolean, var version: Long)

    internal fun modeTarget(advanced: Boolean) = modes[if (advanced) 1 else 0]
    internal fun fieldTarget(param: Param) = controls[param.ordinal]
    internal fun waveTarget(wave: Int) = waves[wave]
    internal fun nameTarget() = name

    override fun draw(c: Surface, area: Rect) {
        drawnState = null
        val p = ink.palette
        if (palette !== p) {
            palette = p
            signal = when (p.id) {
                Palette.PAPER.id -> 0xFF20518A.toInt()
                Palette.CONTRAST.id -> p.LISTEN
                else -> 0xFF8FBEF4.toInt()
            }
            plate = Palette.mix(p.LANE, signal, 0.08f)
            edge = Palette.mix(p.RULE, signal, 0.27f)
            well = Palette.mix(p.BG, signal, 0.045f)
            steel = Palette.mix(p.BAR, p.TEXT, 0.24f)
        }
        val panel = StudioSoundView.panel(st)
        val top = header(c, area, "Sound", "Studio mono synth")
        val bank = drawBank(c, area, top, StudioSound.SLOTS, p.channel(st.listenChannel), false) { slot ->
            song.studio.patch(slot)?.name?.ifEmpty { "(unnamed)" }
        }
        val pad = ink.unit * 0.55f
        body.set(bank.right + pad, top + pad * 0.5f, area.right - pad,
            minOf(area.bottom - pad, top + ink.unit * 37f))
        if (body.width() <= 0f || body.height() <= 0f) return
        val patch = StudioSoundView.patch(song, st)
        if (cachedPatch !== patch) {
            for (param in Param.entries) {
                val value = param.read(patch)
                exactValues[param.ordinal] = when (param) {
                    Param.WAVE -> StudioSoundView.waveName(value)
                    Param.TUNE -> signed(value)
                    else -> value.toString()
                }
            }
            tuneText = "${exactValues[Param.TUNE.ordinal]} st"
            cachedPatch = patch
        }
        for (r in rails) r.set(0f, 0f, 0f, 0f)
        for (r in waves) r.set(0f, 0f, 0f, 0f)
        ink.round(c, body, ink.dp(4f), plate)
        ink.outline(c, body, edge, ink.dp(1f), ink.dp(4f))
        val left = body.left + pad
        val right = body.right - pad
        var y = body.top + pad
        val tabH = minOf(ink.dp(44f), body.height() * 0.13f)
        for (i in modes.indices) {
            val r = modes[i]
            r.set(if (i == 0) left else body.centerX(), y, if (i == 0) body.centerX() else right, y + tabH)
            val on = panel.advanced == (i == 1)
            PanelWidgets.segment(ink, c, r, on, signal)
            val label = if (i == 0) "Sound" else "Advanced"
            ink.label(c, ink.fit(label, r.width() - pad, ink.unit, on), r.centerX(), r.top, r.height(),
                if (on) p.TEXT else p.DIM, on, align = Align.CENTER)
            if (on && st.instField == StudioSoundView.MODE) outline(c, r)
        }
        y += tabH + pad * 0.45f
        val nameH = minOf(ink.dp(44f), body.height() * 0.125f)
        val arrowW = minOf(nameH, (right - left) * 0.17f)
        name.set(left, y, right - 2f * arrowW, y + nameH)
        previousSlot.set(name.right, y, right - arrowW, y + nameH)
        nextSlot.set(previousSlot.right, y, right, y + nameH)
        drawPatchName(c, patch.name, pad)
        drawButton(c, previousSlot, "‹", st.instrument > 1)
        drawButton(c, nextSlot, "›", st.instrument < StudioSound.SLOTS)
        y += nameH + pad * 0.5f
        val footH = minOf(ink.unit * 2.2f, body.height() * 0.12f)
        work.set(left, y, right, body.bottom - footH - pad * 0.25f)
        if (panel.advanced) drawAdvanced(c) else drawSound(c, patch)
        drawGuidance(c, footH)
        drawnState = st
        drawnVersion = session.documentVersion
        drawnRevision = panel.revision
        drawnSlot = st.instrument
        drawnLane = st.listenChannel
        drawnAdvanced = panel.advanced
    }

    private fun drawPatchName(c: Surface, value: String, pad: Float) {
        ink.round(c, name, ink.dp(2f), well)
        val selected = st.instField == StudioSoundView.NAME
        val size = minOf(ink.unit, name.height() * 0.8f)
        val x = name.left + pad
        val width = name.width() - 2f * pad
        if (!selected) {
            ink.label(c, ink.fit(value.ifEmpty { "(unnamed)" }, width, size, true), x, name.top, name.height(), ink.palette.TEXT, true, size)
            return
        }
        val pos = st.textPos
        val char = value.getOrNull(pos)?.toString() ?: " "
        val charW = maxOf(ink.labelWidth(char, size, true), size * 0.5f)
        val padded = value.padEnd(pos + 1)
        var start = 0
        var before = padded.substring(0, pos)
        var beforeW = ink.labelWidth(before, size, true)
        // Keep the currently edited letter visible even for 22-character names on portrait panes.
        while (start < pos && beforeW + charW + size > width) {
            start++
            before = padded.substring(start, pos)
            beforeW = ink.labelWidth(before, size, true)
        }
        ink.label(c, ink.fit(padded.substring(start), width, size, true), x, name.top, name.height(), ink.palette.TEXT, true, size)
        c.line(x + beforeW, name.centerY() + size * 0.56f, x + beforeW + charW, name.centerY() + size * 0.56f,
            cursorColor(), ink.dp(2f))
        outline(c, name)
    }

    private fun drawSound(c: Surface, patch: MonoPatch) {
        val y0 = work.top
        val h = work.height()
        group.set(work.left, y0, work.right, y0 + h * 0.21f)
        drawOscillator(c, patch.waveform)
        group.set(work.left, group.bottom, work.right, y0 + h * 0.48f)
        drawGroup(c, "Filter", Param.CUTOFF, 3, patch, dial = true)
        group.set(work.left, group.bottom, work.right, y0 + h * 0.75f)
        drawGroup(c, "Motion", Param.DECAY, 3, patch, dial = true)
        group.set(work.left, group.bottom, work.right, work.bottom)
        drawGroup(c, "Output", Param.TUNE, 2, patch, dial = false)
    }

    private fun drawOscillator(c: Surface, wave: Int) {
        val r = fieldTarget(Param.WAVE)
        r.set(group.left, group.top, group.right, group.bottom - ink.unit * 0.32f)
        ink.round(c, r, ink.dp(3f), well)
        PanelWidgets.cells(r, 2, ink.dp(2f), waves)
        val p = ink.palette
        for (i in waves.indices) {
            val cell = waves[i]
            val on = i == wave
            val text = StudioSoundView.waveName(i)
            val size = minOf(ink.unit, cell.height() * 0.45f)
            val pad = ink.unit * 0.65f
            val col = if (on) signal else p.DIM
            if (on) ink.rect(c, cell, Palette.mix(well, signal, 0.14f))
            val left = cell.left + pad
            ink.label(c, text, left, cell.top, cell.height(), if (on) p.TEXT else p.DIM, on, size)
            val x = left + ink.labelWidth(text, size, on) + pad
            val end = cell.right - pad
            val cy = cell.centerY()
            val a = minOf((end - x) * 0.34f, minOf(cell.height() * 0.23f, ink.unit * 0.88f))
            if (a > 0f) {
                val w = ink.dp(if (on) 2f else 1.25f)
                val mid = (x + end) / 2f
                c.line(x, cy, end, cy, edge, ink.dp(0.75f))
                if (i == 0) {
                    c.line(x, cy + a, mid, cy - a, col, w)
                    c.line(mid, cy - a, mid, cy + a, col, w)
                    c.line(mid, cy + a, end, cy - a, col, w)
                } else {
                    c.line(x, cy - a, mid, cy - a, col, w)
                    c.line(mid, cy - a, mid, cy + a, col, w)
                    c.line(mid, cy + a, end, cy + a, col, w)
                }
            }
            if (on) {
                c.line(cell.left + 1f, cell.bottom - ink.dp(2f), cell.right - 1f, cell.bottom - ink.dp(2f), signal, ink.dp(2f))
                if (st.instField == Param.WAVE.field) outline(c, cell)
            }
        }
    }

    private fun drawGroup(c: Surface, title: String, first: Param, count: Int, patch: MonoPatch, dial: Boolean) {
        val titleH = minOf(ink.unit * 1.4f, group.height() * 0.26f)
        val size = minOf(ink.unit * 0.79f, titleH * 0.84f)
        ink.label(c, title, group.left + ink.unit * 0.2f, group.top, titleH, ink.palette.TEXT, true, size)
        val titleEnd = group.left + ink.labelWidth(title, size, true) + ink.unit * 0.9f
        if (titleEnd < group.right) c.line(titleEnd, group.top + titleH / 2f, group.right, group.top + titleH / 2f, edge, ink.dp(1f))
        val gap = ink.unit * 0.42f
        val w = (group.width() - (count - 1) * gap) / count
        for (i in 0 until count) {
            val param = Param.entries[first.ordinal + i]
            val l = group.left + i * (w + gap)
            val r = fieldTarget(param)
            r.set(l, group.top + titleH, l + w, group.bottom - ink.unit * 0.32f)
            if (dial && r.height() >= ink.unit * 3.05f) drawDial(c, r, param, param.read(patch))
            else drawRail(c, r, param, param.read(patch))
        }
    }

    private fun drawDial(c: Surface, r: Rect, param: Param, value: Int) {
        val size = minOf(ink.unit, r.height() * 0.17f)
        val labelH = size * 1.25f
        val cy = r.centerY()
        val cx = r.centerX()
        val tickR = minOf(r.width() * 0.29f, minOf((r.height() - 2f * labelH) * 0.46f, ink.unit * 2.15f))
        val selected = st.instField == param.field
        if (selected) {
            ink.round(c, r, ink.dp(3f), well)
            outline(c, r)
        }
        val p = ink.palette
        ink.label(c, ink.fit(param.label, r.width() - ink.unit * 0.5f, size, selected), cx, r.top, labelH,
            p.TEXT, selected, size, Align.CENTER)
        for (i in 0..100 step 10) {
            val x = dialX[i]
            val y = dialY[i]
            val inner = tickR * if (i % 50 == 0) 0.82f else 0.89f
            c.line(cx + x * inner, cy + y * inner, cx + x * tickR, cy + y * tickR, if (i <= value) signal else edge, ink.dp(1.4f))
        }
        val radius = tickR * 0.72f
        c.fillCircle(cx, cy + ink.dp(1.5f), radius + ink.dp(1f), well)
        c.fillCircle(cx, cy, radius, steel)
        c.strokeCircle(cx, cy, radius, edge, ink.dp(1.4f))
        val dx = dialX[value]
        val dy = dialY[value]
        c.line(cx + dx * radius * 0.34f, cy + dy * radius * 0.34f, cx + dx * radius * 0.88f,
            cy + dy * radius * 0.88f, if (selected) focusColor() else p.TEXT, ink.dp(2.25f))
        ink.mono(c, exactValues[param.ordinal], cx, r.bottom - labelH, labelH, signal, bold = selected, size = size, align = Align.CENTER)
    }

    private fun drawRail(c: Surface, r: Rect, param: Param, value: Int) {
        val p = ink.palette
        val chosen = st.instField == param.field
        val size = minOf(ink.unit, r.height() * 0.42f)
        val x = r.left + ink.unit * 0.65f
        val right = r.right - ink.unit * 0.65f
        val valueText = if (param == Param.TUNE) tuneText else exactValues[param.ordinal]
        val valW = ink.monoWidth(valueText, size, chosen)
        if (chosen) ink.round(c, r, ink.dp(3f), well)
        ink.label(c, ink.fit(param.label, right - x - valW - ink.unit * 0.25f, size, chosen), x, r.top, r.height() * 0.5f,
            p.TEXT, chosen, size)
        ink.mono(c, valueText, right, r.top, r.height() * 0.5f, signal, chosen, size, Align.RIGHT)
        val cy = r.top + r.height() * 0.72f
        rails[param.ordinal].set(x, cy, right, cy)
        val fraction = (value - param.min).toFloat() / (param.max - param.min)
        if (r.height() >= ink.dp(28f)) {
            PanelWidgets.rail(ink, c, x, right, cy, fraction, param.max - param.min, signal,
                chosen, ctl.editHeld || StudioSoundView.panel(st).cancelTouch != null)
        } else {
            // Short panes still show each control without making a full-size thumb overlap its readout.
            val stop = x + (right - x) * fraction
            c.line(x, cy, right, cy, edge, ink.dp(2f))
            c.line(x, cy, stop, cy, signal, ink.dp(2f))
            c.fillCircle(stop, cy, ink.dp(2.8f), if (chosen) focusColor() else signal)
        }
        if (param == Param.TUNE) c.line((x + right) / 2f, cy + ink.dp(3f), (x + right) / 2f, r.bottom - ink.dp(1f), edge, ink.dp(1f))
        if (chosen) outline(c, r)
    }

    private fun drawAdvanced(c: Surface) {
        val n = Param.entries.size
        val minRow = ink.unit * 2.45f
        val split = work.height() < n * minRow && work.width() >= ink.unit * 15f
        val rows = if (split) (n + 1) / 2 else n
        val h = minOf(work.height() / rows, ink.unit * 3.45f)
        val w = work.width() / if (split) 2f else 1f
        val p = ink.palette
        for (param in Param.entries) {
            val i = param.ordinal
            val left = work.left + (i / rows) * w
            val top = work.top + (i % rows) * h
            val r = fieldTarget(param)
            r.set(left, top, work.left + (i / rows + 1) * w, work.top + (i % rows + 1) * h)
            val selected = st.instField == param.field
            if (selected) ink.rect(c, r, p.CURSOR_FILL)
            else if (i % 2 == 0) ink.rect(c, r, well)
            val size = minOf(ink.unit, h * 0.54f)
            val button = stepWidth(r)
            val labelX = r.left + button + size * 0.4f
            val vx = r.right - button - size * 0.4f
            val text = exactValues[i]
            val valueW = ink.monoWidth(text, size, selected)
            val stacking = vx - labelX < ink.labelWidth(param.label, size, selected) + valueW + size
            if (stacking) {
                val small = minOf(size * 0.88f, h * 0.4f)
                ink.label(c, ink.fit(param.label, vx - labelX, small, selected), r.centerX(), top, h / 2f, p.TEXT, selected, small, Align.CENTER)
                ink.mono(c, text, r.centerX(), top + h / 2f, h / 2f, signal, selected, small, Align.CENTER)
            } else {
                ink.label(c, param.label, labelX, top, h, p.TEXT, selected, size)
                ink.mono(c, text, vx, top, h, signal, selected, size, Align.RIGHT)
            }
            ink.label(c, "−", r.left + button / 2f, top, h, p.TEXT, false, size, Align.CENTER)
            ink.label(c, "+", r.right - button / 2f, top, h, p.TEXT, false, size, Align.CENTER)
            if (selected) outline(c, r)
        }
    }

    private fun stepWidth(r: Rect) = minOf(ink.dp(44f), minOf(r.height() * 0.9f, r.width() * 0.19f))

    private fun drawGuidance(c: Surface, h: Float) {
        val advanced = StudioSoundView.panel(st).advanced
        val text = when (st.instField) {
            StudioSoundView.MODE -> "Two views, one patch."
            StudioSoundView.NAME -> "Name this patch."
            else -> StudioSoundView.param(st)?.help ?: ""
        }
        val size = minOf(ink.unit * 0.88f, h * 0.68f)
        val y = body.bottom - h
        val x = work.left + ink.unit * 0.2f
        ink.label(c, ink.fit(text, work.right - x, size), x, y, h, ink.palette.TEXT, size = size)
        // Shorter Advanced rows end immediately above this rule; tall panes keep the same footer anchor.
        if (advanced) c.line(work.left, y, work.right, y, edge, ink.dp(1f))
    }

    private fun drawButton(c: Surface, r: Rect, text: String, enabled: Boolean) {
        val p = ink.palette
        ink.rect(c, r.left + ink.dp(1f), r.top, r.right, r.bottom, p.BAR)
        ink.label(c, text, r.centerX(), r.top, r.height(), if (enabled) p.TEXT else p.FAINT, true,
            minOf(ink.unit * 1.35f, r.height() * 0.77f), Align.CENTER)
    }

    private fun focusColor() = if (StudioSoundView.panel(st).cancelTouch != null) ink.palette.WRITE else cursorColor()

    private fun outline(c: Surface, r: Rect) {
        focus.set(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f)
        ink.outline(c, focus, focusColor(), ink.dp(1.65f), ink.dp(3f))
    }

    override fun down(x: Float, y: Float): Boolean {
        val p = StudioSoundView.panel(st)
        if (drawnState !== st || st.kind != ViewKind.INSTRUMENT || drawnVersion != session.documentVersion ||
            drawnRevision != p.revision || drawnSlot != st.instrument || drawnLane != st.listenChannel || drawnAdvanced != p.advanced) return false
        cancel()
        for (i in modes.indices) if (modes[i].contains(x, y)) {
            StudioSoundView.blockHeldEdit(ctl)
            StudioSoundView.selectMode(ctl, i == 1)
            return true
        }
        if (slotRect.contains(x, y)) {
            val slot = slotScroll + ((y - slotTop) / slotH).toInt() + 1
            if (slot > StudioSound.SLOTS) return false
            StudioSoundView.selectSlot(ctl, slot)
            return true
        }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) {
            StudioSoundView.selectSlot(ctl, st.instrument + if (nextSlot.contains(x, y)) 1 else -1)
            return true
        }
        if (name.contains(x, y)) {
            StudioSoundView.selectField(ctl, StudioSoundView.NAME)
            StudioSoundView.blockHeldEdit(ctl)
            StudioSoundView.requestEntry(ctl)
            return true
        }
        val param = Param.entries.firstOrNull { fieldTarget(it).contains(x, y) } ?: return false
        val preview = ctl.editHeld && !p.editBlocked && StudioSoundView.param(st) != null
        StudioSoundView.selectField(ctl, param.field)
        StudioSoundView.blockHeldEdit(ctl)
        if (p.advanced) {
            val r = fieldTarget(param)
            val button = stepWidth(r)
            when {
                x < r.left + button -> StudioSoundView.adjust(ctl, param, -1, preview)
                x >= r.right - button -> StudioSoundView.adjust(ctl, param, 1, preview)
                else -> StudioSoundView.requestEntry(ctl)
            }
        } else {
            val rail = rails[param.ordinal]
            val travel = when {
                param == Param.WAVE -> Travel.SWITCH
                rail.width() > 0f -> Travel.RAIL
                else -> Travel.DIAL
            }
            drag.begin(session, param.undoLabel, Drag(st, st.instrument, st.listenChannel, p.revision,
                param, travel, param.read(StudioSoundView.patch(song, st)), x, y, rail.left, rail.right,
                fieldTarget(param).centerX(), preview, session.documentVersion))
            p.cancelTouch = cancelPointer
            if (travel != Travel.DIAL) move(x, y)
        }
        return true
    }

    override fun move(x: Float, y: Float) {
        if (drag.target == null) return
        val d = drag.live(session) ?: run { cancel(); return }
        val p = StudioSoundView.panel(st)
        if (d.state !== st || st.kind != ViewKind.INSTRUMENT || p.advanced || p.revision != d.revision ||
            st.instrument != d.slot || st.listenChannel != d.lane || st.instField != d.param.field || session.documentVersion != d.version) {
            cancel()
            return
        }
        val param = d.param
        val value = when (d.travel) {
            Travel.SWITCH -> if (x < d.split) 0 else 1
            Travel.DIAL -> d.startValue + ((x - d.startX + d.startY - y) * (param.max - param.min) / ink.dp(112f)).roundToInt()
            Travel.RAIL -> PanelWidgets.railValue(x, d.left, d.right, param.min, param.max)
        }
        StudioSoundView.set(ctl, param, value, d.preview)
        d.version = session.documentVersion
    }

    override fun up(x: Float, y: Float) = cancel()

    override fun cancel() {
        val p = StudioSoundView.panel(drag.target?.state ?: st)
        if (p.cancelTouch === cancelPointer) p.cancelTouch = null
        drag.finish(session)
    }

    companion object {
        private fun dialAngle(value: Int) = (135.0 + value * 2.7) * PI / 180.0
        private val dialX = FloatArray(101) { cos(dialAngle(it)).toFloat() }
        private val dialY = FloatArray(101) { sin(dialAngle(it)).toFloat() }
    }
}
