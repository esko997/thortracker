package org.thortracker.system.studio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.*

internal class StudioStripPainter(v: PaneView) : Painter(v) {
    private val fields = Array(11) { Rect() }
    private val prev = Rect(); private val next = Rect(); private val action = Rect(); private val rail = Rect(); private val plot = Rect()
    private val badge = Rect()
    private val drag = PanelDrag<StripDrag>()
    private enum class Mode { RAIL, FADER, DIAL }
    private class StripDrag(
        val st: ViewState, val addr: Int, val pedal: Int, val field: Int, val range: IntRange,
        val mode: Mode, val l: Float, val r: Float, val startX: Float, val startY: Float, val startVal: Int,
        val tapActivate: Boolean, var moved: Boolean = false, var version: Long,
    )
    private var drawnVersion = -1L; private var drawnState: ViewState? = null
    private var drawnPedal = 0; private var drawnAddr = 0; private var drawnField = 0
    private var knobPedal: StudioInsert? = null
    private val knobBytes = IntArray(fields.size - 3)
    private val knobValues = Array(fields.size - 3) { "" }

    private fun bed(c: Surface, r: Rect, selected: Boolean, accent: Int, recessed: Boolean = false) {
        val p = ink.palette
        val fill = when {
            selected -> Palette.mix(p.BAR, accent, .2f)
            recessed -> Palette.mix(p.BG, p.LANE, .55f)
            else -> p.LANE
        }
        ink.round(c, r, ink.dp(3f), fill)
        if (selected) ink.outline(c, r, p.FOCUS, ink.dp(1.7f), ink.dp(3f))
        else if (recessed) ink.outline(c, r, p.RULE, ink.dp(1f), ink.dp(3f))
    }

    private fun box(c: Surface, r: Rect, text: String, selected: Boolean, accent: Int) {
        bed(c, r, selected, accent)
        val size = minOf(ink.unit, r.height() * .49f)
        ink.label(c, ink.fit(text, r.width() - ink.dp(8f), size, selected), r.centerX(), r.top, r.height(), ink.palette.TEXT, selected, size, Align.CENTER)
    }

    private fun drawLetterBadge(c: Surface, right: Float, top: Float, h: Float, letters: String) {
        val sz = minOf(ink.unit * .76f, h * .68f)
        val pad = sz * .38f
        val w = ink.monoWidth(letters, sz, true) + pad * 2f
        badge.set(right - w, top + (h - sz * 1.35f) * .5f, right, top + (h + sz * 1.35f) * .5f)
        ink.round(c, badge, ink.dp(2.5f), Palette.alpha(ink.palette.PLAYHEAD, 48))
        ink.mono(c, letters, badge.centerX(), top, h, ink.palette.PLAYHEAD, true, sz, Align.CENTER)
    }

    private fun pedalSlotCard(c: Surface, r: Rect, slotNum: Int, insert: StudioInsert?, selected: Boolean, trackAccent: Int) {
        val p = ink.palette
        if (insert == null) {
            bed(c, r, selected, trackAccent, recessed = true)
            val size = minOf(ink.unit * .96f, r.height() * .46f)
            ink.label(c, ink.fit("$slotNum  + Add pedal", r.width() - ink.dp(12f), size, selected),
                r.centerX(), r.top, r.height(), if (selected) p.TEXT else p.DIM, selected, size, Align.CENTER)
            return
        }
        val color = StudioGraphics.color(insert.kind, p)
        val tone = if (insert.bypass) p.DIM else color
        bed(c, r, selected, color)
        val barW = ink.dp(3.5f)
        c.fillRect(r.left + ink.dp(1f), r.top + ink.dp(3f), r.left + ink.dp(1f) + barW, r.bottom - ink.dp(3f), tone)
        val pad = ink.unit * .55f + barW
        val stateText = if (insert.bypass) "BYP" else "ON"
        val compact = r.height() < ink.unit * 2.55f
        val titleSize = minOf(ink.unit, r.height() * (if (compact) .52f else .42f))
        val badgeSize = minOf(ink.unit * .76f, titleSize * .84f)
        val badgeW = ink.monoWidth(stateText, badgeSize, true) + ink.dp(10f)
        val titleH = if (compact) r.height() else r.height() * .56f
        val bx = r.right - ink.unit * .45f
        badge.set(bx - badgeW, r.top + (titleH - badgeSize * 1.4f) * .5f, bx, r.top + (titleH + badgeSize * 1.4f) * .5f)
        ink.round(c, badge, ink.dp(2.5f), if (insert.bypass) p.BG else Palette.alpha(color, 44))
        ink.mono(c, stateText, badge.centerX(), r.top, titleH, tone, true, badgeSize, Align.CENTER)
        ink.label(c, ink.fit("$slotNum  ${insert.kind.faceName}", badge.left - (r.left + pad) - ink.dp(4f), titleSize, true),
            r.left + pad, r.top, titleH, if (insert.bypass) p.DIM else p.TEXT, true, titleSize)
        if (!compact) {
            val summary = insert.kind.parameters.take(2).joinToString(" · ") { spec -> spec.display(insert.value(spec.id)) }
            val subSize = minOf(ink.unit * .82f, r.height() * .32f)
            ink.mono(c, ink.fit(summary, r.width() - pad - ink.unit * .5f, subSize),
                r.left + pad, r.top + titleH * .92f, r.height() - titleH * .92f, p.DIM, size = subSize)
        }
    }

    private fun faderTile(c: Surface, r: Rect, title: String, valueText: String, value: Int?, range: IntRange?, selected: Boolean, accent: Int, mutedHint: Boolean = false) {
        val p = ink.palette
        bed(c, r, selected, accent, recessed = mutedHint)
        val pad = minOf(ink.unit * .55f, r.width() * .06f)
        if (value == null || range == null || r.height() < ink.unit * 1.9f) {
            val size = minOf(ink.unit * .92f, r.height() * .46f)
            val combined = if (mutedHint) "$title · $valueText" else "$title  $valueText"
            ink.label(c, ink.fit(combined, r.width() - 2 * pad, size, selected), r.centerX(), r.top, r.height(),
                if (mutedHint && !selected) p.DIM else p.TEXT, selected, size, Align.CENTER)
            return
        }
        val topH = r.height() * .58f
        val size = minOf(ink.unit * .96f, topH * .68f)
        val vw = ink.monoWidth(valueText, size, true) + ink.dp(6f)
        ink.label(c, ink.fit(title, r.width() - 2 * pad - vw, size, selected), r.left + pad, r.top, topH, p.TEXT, selected, size)
        ink.mono(c, valueText, r.right - pad, r.top, topH, accent, true, size, Align.RIGHT)
        val x0 = r.left + pad
        val x1 = r.right - pad
        val railY = r.top + r.height() * .77f
        val lineH = minOf(r.height() * .11f, ink.dp(4.5f)).coerceAtLeast(ink.dp(2.5f))
        c.fillRect(x0, railY - lineH * .5f, x1, railY + lineH * .5f, p.RULE)
        val bipolar = range.first < 0 && range.last == -range.first
        val pos = x0 + (x1 - x0) * (value - range.first).toFloat() / (range.last - range.first)
        if (bipolar) {
            val midX = (x0 + x1) * .5f
            c.fillRect(minOf(midX, pos), railY - lineH * .5f, maxOf(midX, pos), railY + lineH * .5f, accent)
            c.line(midX, railY - lineH * 1.15f, midX, railY + lineH * 1.15f, p.DIM, ink.dp(1f))
        } else {
            c.fillRect(x0, railY - lineH * .5f, pos, railY + lineH * .5f, accent)
            if (range.first == 0 && range.last == 200) {
                val unityX = (x0 + x1) * .5f
                c.line(unityX, railY + lineH * .6f, unityX, railY + lineH * 1.4f, p.DIM, ink.dp(1f))
            }
        }
        val thumbW = ink.dp(2.2f)
        c.fillRect(pos - thumbW, railY - lineH * 1.05f, pos + thumbW, railY + lineH * 1.05f, if (selected) p.FOCUS else accent)
    }

    private fun knob(c: Surface, r: Rect, param: StudioParameter, shown: String, byte: Int, selected: Boolean, accent: Int, boundLetters: String?) {
        if (r.height() < ink.unit * 4f) {
            val prefix = if (boundLetters != null) "[$boundLetters] " else ""
            box(c, r, "$prefix${param.name} · $shown", selected, accent)
            return
        }
        bed(c, r, selected, accent)
        val p = ink.palette; val size = minOf(ink.unit, r.height() * .20f); val labelH = size * 1.3f
        val cx = r.centerX(); val cy = r.centerY()
        val tickR = minOf(r.width() * .30f, (r.height() - 2 * labelH) * .46f, ink.unit * 2.5f)
        val fraction = byte / 255f
        val titleMax = r.width() - ink.dp(if (boundLetters != null) 28f else 6f)
        ink.label(c, ink.fit(param.name, titleMax, size, selected), cx, r.top, labelH, p.TEXT, selected, size, Align.CENTER)
        if (boundLetters != null) drawLetterBadge(c, r.right - ink.dp(4f), r.top, labelH, boundLetters)
        for (i in 0..10) {
            val a = .375f + .075f * i; val x = StudioGraphics.sinCycle(a + .25f); val y = StudioGraphics.sinCycle(a)
            c.line(cx + x * tickR * .86f, cy + y * tickR * .86f, cx + x * tickR, cy + y * tickR, if (i * .1f <= fraction) accent else p.RULE, ink.dp(1.3f))
        }
        val radius = tickR * .72f
        c.fillCircle(cx, cy + ink.dp(1.5f), radius + ink.dp(1f), p.BG)
        c.fillCircle(cx, cy, radius, Palette.mix(p.BAR, accent, .12f))
        c.strokeCircle(cx, cy, radius, p.RULE, ink.dp(1.3f))
        val a = .375f + .75f * fraction; val x = StudioGraphics.sinCycle(a + .25f); val y = StudioGraphics.sinCycle(a)
        c.line(cx + x * radius * .30f, cy + y * radius * .30f, cx + x * radius * .86f, cy + y * radius * .86f, if (selected) p.FOCUS else accent, ink.dp(2f))
        ink.mono(c, ink.fit(shown, r.width() - ink.dp(6f), size, true), cx, r.bottom - labelH, labelH, accent, selected, size, Align.CENTER)
    }

    override fun draw(c: Surface, area: Rect) {
        drawnState = null
        val s = song.studio; val pedal = StudioStripView.pedal(s, st); val u = ink.unit; val p = ink.palette
        val active = if (pedal == null) st.stripField else st.pedalField
        val accent = pedal?.let { StudioGraphics.color(it.kind, p) } ?: p.channel(st.channel)
        val top = header(c, area, StudioStripView.routeName(s, st),
            if (pedal != null) "${pedal.kind.faceName}  /  ${if (pedal.bypass) "Bypassed" else "Engaged"}"
            else if (StudioStripView.usb(ctl)) "USB MIDI · strip inactive"
            else if (st.stripBus >= 0) "Shared stereo return" else "Internal stereo · ${s.tracks.size} track${if (s.tracks.size == 1) "" else "s"}")
        val pad = u * .48f; val left = area.left + pad; val right = area.right - pad; val h = area.bottom - top
        if (h <= 0 || right <= left) return
        val foot = minOf(u * 4.5f, h * .31f); val bottom = area.bottom - pad; val split = bottom - foot - pad
        val tabH = minOf(u * 2.7f, h * .2f); val gap = ink.dp(2f)
        for (r in fields) r.set(0f, 0f, 0f, 0f)
        val n = if (pedal == null) StudioStripView.lastStripField(st) + 1 else pedal.kind.parameters.size + 3
        val chain = StudioStripView.chain(s, st)
        if (pedal == null) {
            // A vertical signal trace joins the four numbered sockets. The right side is its post-fader branch.
            val mid = left + (right - left) * .54f; val y = top + pad
            fields[0].set(left, y, mid - gap, y + tabH); fields[1].set(mid + gap, y, right, y + tabH)
            val rackTop = y + tabH + pad; val rh = (split - rackTop) / 4; val x = left + u * .8f
            c.line(x, rackTop + rh / 2, x, rackTop + 3.5f * rh, accent, ink.dp(2f))
            for (i in 0..3) {
                val cy = rackTop + (i + .5f) * rh
                val ins = chain.getOrNull(i)
                c.fillCircle(x, cy, ink.dp(3.4f), if (ins != null) (if (ins.bypass) p.DIM else StudioGraphics.color(ins.kind, p)) else p.RULE)
                fields[2 + i].set(x + u * .7f, rackTop + i * rh + gap, mid - gap, rackTop + (i + 1) * rh - gap)
            }
            val outputRows = n - 6; val oh = (split - rackTop) / outputRows
            for (i in 0 until outputRows) fields[6 + i].set(mid + gap, rackTop + i * oh + gap, right, rackTop + (i + 1) * oh - gap)
        } else {
            fields[0].set(left, top + pad, left + (right - left) * .34f - gap, top + pad + tabH)
            fields[1].set(fields[0].right + gap * 2, top + pad, right - (right - left) * .32f - gap, top + pad + tabH)
            fields[n - 1].set(fields[1].right + gap * 2, top + pad, right, top + pad + tabH)
            val y = fields[0].bottom + pad
            val graphH = (split - y) * .53f
            plot.set(left, y, right, y + graphH)
            StudioGraphics.pedal(ink, c, plot, pedal, accent)
            val count = pedal.kind.parameters.size
            val cols = if (right - left < ink.dp(400f)) minOf(3, count) else count
            val rows = (count + cols - 1) / cols; val w = (right - left) / cols; val rh = (split - plot.bottom) / rows
            for (i in 0 until count) fields[i + 2].set(left + (i % cols) * w + gap, plot.bottom + (i / cols) * rh + gap, left + (i % cols + 1) * w - gap, plot.bottom + (i / cols + 1) * rh - gap)
        }
        if (pedal != null && pedal !== knobPedal) {
            for (i in pedal.kind.parameters.indices) {
                val param = pedal.kind.parameters[i]; val value = pedal.value(param.id)
                knobBytes[i] = param.nearestByte(value); knobValues[i] = param.display(value)
            }
            knobPedal = pedal
        }
        val tr = s.tracks[st.channel]
        for (i in 0 until n) {
            val label = StudioStripView.label(ctl, i)
            val param = pedal?.kind?.parameters?.getOrNull(i - 2)
            when {
                param != null -> {
                    val letters = if (st.stripBus < 0) StudioAssignments.boundLetters(song, st.channel, pedal.id, pedal.kind, param.id) else null
                    knob(c, fields[i], param, knobValues[i - 2], knobBytes[i - 2], active == i, accent, letters)
                }
                pedal == null && i in 2..5 -> pedalSlotCard(c, fields[i], i - 1, chain.getOrNull(i - 2), active == i, accent)
                pedal == null && i == StudioStripView.GAIN -> {
                    val v = if (st.stripBus < 0) tr.gain else s.buses[st.stripBus].gain
                    faderTile(c, fields[i], label, "$v %", v, 0..200, active == i, accent)
                }
                pedal == null && i == StudioStripView.PAN -> {
                    val panText = when { tr.pan == 0 -> "C"; tr.pan < 0 -> "L${-tr.pan}"; else -> "R${tr.pan}" }
                    faderTile(c, fields[i], label, panText, tr.pan, -100..100, active == i, accent)
                }
                pedal == null && i in StudioStripView.SEND_A..StudioStripView.SEND_B -> {
                    val busIdx = i - StudioStripView.SEND_A
                    if (busIdx in s.buses.indices) {
                        val sv = tr.sends[busIdx]
                        faderTile(c, fields[i], label, "$sv %", sv, 0..100, active == i, accent)
                    } else {
                        faderTile(c, fields[i], label, "Add Return ${'A' + busIdx}", null, null, active == i, accent, mutedHint = true)
                    }
                }
                pedal == null && i == StudioStripView.WRITE -> {
                    val boundCount = tr.bindings.size
                    val writeLabel = if (boundCount > 0) "Automation · $boundCount E–Z" else "Write parameter…"
                    box(c, fields[i], writeLabel, active == i, accent)
                }
                else -> box(c, fields[i], if (pedal == null && i == StudioStripView.ROUTE) "$label ▾" else label, active == i, accent)
            }
        }
        val arrow = minOf(foot, (right - left) * .13f)
        prev.set(left, split + pad, left + arrow, bottom); next.set(right - arrow, split + pad, right, bottom)
        val value = StudioStripView.value(ctl); val x0 = prev.right + pad; val x1 = next.left - pad
        action.set(x0, split + pad, x1, if (value == null) bottom else bottom - foot * .48f)
        val label = StudioStripView.label(ctl, active)
        val param = pedal?.kind?.parameters?.getOrNull(active - 2)
        val shown = when {
            param != null && value != null -> "${param.display(value)}  /  ${fxHex(param.nearestByte(value))}"
            pedal == null && active == StudioStripView.PAN && value != null -> when { value == 0 -> "Center"; value < 0 -> "Left ${-value}%"; else -> "Right $value%" }
            else -> "$value %"
        }
        val actionText = when {
            value != null -> "$label   $shown"
            pedal == null && active == StudioStripView.ROUTE -> "Switch track, return bus or add track…"
            pedal == null && active == StudioStripView.SOURCE -> "Open $label…"
            pedal == null && active in 2..5 -> chain.getOrNull(active - 2)?.let { "Open ${it.kind.faceName} controls…" } ?: "Insert pedal into slot ${active - 1}…"
            else -> "$label…"
        }
        box(c, action, actionText, false, accent)
        box(c, prev, "‹", false, accent); box(c, next, "›", false, accent)
        rail.set(x0, action.bottom, x1, bottom)
        if (value != null && rail.width() > 0f) {
            val range = StudioStripView.range(ctl)!!
            PanelWidgets.rail(ink, c, rail.left + pad, rail.right - pad, rail.centerY(),
                (value - range.first).toFloat() / (range.last - range.first), range.last - range.first, accent, true, ctl.editHeld)
        }
        drawnState = st; drawnVersion = session.documentVersion; drawnAddr = StudioStripView.address(st); drawnPedal = st.stripPedal; drawnField = active
    }

    override fun down(x: Float, y: Float): Boolean {
        if (drawnState !== st || st.kind != ViewKind.STUDIO_STRIP || drawnVersion != session.documentVersion ||
            drawnAddr != StudioStripView.address(st) || drawnPedal != st.stripPedal ||
            drawnField != (if (st.stripPedal == 0) st.stripField else st.pedalField)) return false
        StudioSoundView.blockHeldEdit(ctl)
        if (prev.contains(x, y) || next.contains(x, y)) {
            StudioStripView.step(ctl, if (next.contains(x, y)) 1 else -1)
            return true
        }
        if (action.contains(x, y)) {
            StudioStripView.activate(ctl)
            return true
        }
        val pad = ink.unit * .48f
        if (StudioStripView.value(ctl) != null && rail.contains(x, y)) {
            val range = StudioStripView.range(ctl)!!
            drag.begin(session, "Edit ${StudioStripView.label(ctl, drawnField)}",
                StripDrag(st, drawnAddr, drawnPedal, drawnField, range, Mode.RAIL, rail.left + pad, rail.right - pad,
                    x, y, StudioStripView.value(ctl)!!, tapActivate = false, moved = true, version = drawnVersion))
            move(x, y)
            return true
        }
        val i = fields.indexOfFirst { it.width() > 0 && it.contains(x, y) }
        if (i < 0) return false
        val wasFocused = i == drawnField
        StudioStripView.focus(ctl, i)
        val uncreatedSend = st.stripPedal == 0 && i in StudioStripView.SEND_A..StudioStripView.SEND_B &&
            i - StudioStripView.SEND_A !in song.studio.buses.indices
        val currentVal = StudioStripView.value(ctl)
        val currentRange = StudioStripView.range(ctl)
        if (uncreatedSend || currentVal == null || currentRange == null || StudioStripView.usb(ctl)) {
            StudioStripView.activate(ctl)
            return true
        }
        val card = fields[i]
        val isPedalKnob = st.stripPedal != 0 && i >= 2
        val mode = if (isPedalKnob) Mode.DIAL else Mode.FADER
        val tilePad = minOf(ink.unit * .55f, card.width() * .06f)
        drag.begin(session, "Edit ${StudioStripView.label(ctl, i)}",
            StripDrag(st, drawnAddr, drawnPedal, i, currentRange, mode, card.left + tilePad, card.right - tilePad,
                x, y, currentVal, tapActivate = wasFocused, moved = false, version = session.documentVersion))
        return true
    }

    override fun move(x: Float, y: Float) {
        val d = drag.live(session) ?: return
        if (d.st !== st || st.kind != ViewKind.STUDIO_STRIP || d.addr != StudioStripView.address(st) ||
            d.pedal != st.stripPedal || d.field != (if (st.stripPedal == 0) st.stripField else st.pedalField) ||
            d.version != session.documentVersion) { cancel(); return }
        if (!d.moved) {
            val dist = if (d.mode == Mode.DIAL) abs(x - d.startX) + abs(y - d.startY) else abs(x - d.startX)
            if (dist < ink.dp(4f)) return
            d.moved = true
        }
        val next = when (d.mode) {
            Mode.RAIL -> PanelWidgets.railValue(x, d.l, d.r, d.range.first, d.range.last)
            Mode.FADER -> d.startVal + ((x - d.startX) * (d.range.last - d.range.first) / maxOf(1f, d.r - d.l)).roundToInt()
            Mode.DIAL -> d.startVal + ((x - d.startX + d.startY - y) * (d.range.last - d.range.first) / ink.dp(120f)).roundToInt()
        }
        StudioStripView.set(ctl, next)
        d.version = session.documentVersion
    }

    override fun up(x: Float, y: Float) {
        move(x, y)
        val d = drag.live(session)
        val activateTap = d != null && !d.moved && d.tapActivate && d.st === st &&
            st.kind == ViewKind.STUDIO_STRIP && d.version == session.documentVersion
        cancel()
        if (activateTap) StudioStripView.activate(ctl)
    }

    override fun cancel() { drag.finish(session) }
}

/** Plots describe the stored controls, not a fabricated real-time signal or a CPU meter. */
internal object StudioGraphics {
    private val waves = FloatArray(256) { sin(it * 2.0 * PI / 256).toFloat() }
    fun sinCycle(cycles: Float) = waves[(cycles * 256).roundToInt() and 255]
    fun color(k: StudioKind, p: Palette): Int {
        if (p === Palette.CONTRAST) return p.LISTEN
        val color = when (k) {
            StudioKind.SAMPLER, StudioKind.DELAY -> 0xff79d8c6.toInt()
            StudioKind.FM, StudioKind.CHORUS -> 0xffc9a9ff.toInt()
            StudioKind.PERCUSSION, StudioKind.DRIVE -> 0xffffa66c.toInt()
            StudioKind.REVERB -> 0xff8ebcf8.toInt()
            else -> 0xffefcf69.toInt()
        }
        return if (p === Palette.PAPER) Palette.mix(color, 0xff102035.toInt(), .65f) else color
    }
    fun pedal(ink: Ink, c: Surface, r: Rect, p: StudioInsert, accent: Int) {
        if (r.width() < 1 || r.height() < 1) return
        val muted = if (p.bypass) ink.palette.DIM else accent
        ink.round(c, r, ink.dp(3f), Palette.mix(ink.palette.BG, muted, .09f))
        val title = when (p.kind) {
            StudioKind.DELAY -> "Echo spacing"; StudioKind.DRIVE -> "Saturation"
            StudioKind.CRUSHER -> "Sample / hold"; StudioKind.REVERB -> "Room decay"; else -> "Stereo sweep"
        }
        val pad = ink.unit * .55f; val h = minOf(ink.unit * 1.4f, r.height() * .28f)
        ink.label(c, title, r.left + pad, r.top, h, muted, true, minOf(ink.unit, h * .86f))
        val left = r.left + pad; val w = r.width() - 2 * pad; val mid = (r.top + h + r.bottom) / 2; val amp = (r.bottom - r.top - h) * .39f
        if (w <= 0 || amp <= 0) return
        c.line(left, mid, left + w, mid, ink.palette.RULE, ink.dp(1f))
        when (p.kind) {
            StudioKind.DELAY -> {
                val clk = p.value(0); val beat = when (clk) { 1 -> .5f; 2 -> .75f; 3 -> 1f / 3; 4 -> 1f; 5 -> 1.5f; 6 -> 2f / 3; 7 -> .25f; 8 -> .375f; 9 -> 1f / 6; else -> p.value(1) / 1000f }
                val stride = w * (beat * .20f).coerceIn(.014f, .5f); var vol = 1f; var x = left
                for (i in 0..24) { if (x > left + w) break; c.line(x, mid - amp * vol, x, mid + amp * vol * .65f, muted, ink.dp(2.5f)); x += stride; vol = if (i == 0) p.value(4) / 100f else vol * p.value(2) / 100f }
            }
            StudioKind.REVERB -> {
                val gap = w * p.value(3) / 1000f; val length = .17f + .83f * p.value(1) / 8000f; val peaks = 12 + p.value(0) / 3
                for (i in 0..peaks) { val t = i.toFloat() / peaks; val x = left + gap + (w - gap) * t * length; val mag = (1f - t) * (1f - t) * amp * (.6f + .4f * ((i * 13) % 17) / 16f); c.line(x, mid - mag, x, mid + mag, muted, ink.dp(1.6f)) }
            }
            else -> {
                fun y(t: Float, other: Boolean = false): Float = when (p.kind) {
                    StudioKind.DRIVE -> { val z = (t * 2 - 1) * (1 + p.value(0) / 5f); mid - amp * z / (1 + abs(z)) }
                    StudioKind.CRUSHER -> { val levels = 1 shl minOf(12, p.value(0) - 1); val steps = 5 + 39 * p.value(1) / 48000; val held = (t * steps).toInt().toFloat() / steps; mid - amp * (sinCycle(held) * levels).roundToInt() / levels }
                    else -> mid - amp * sinCycle(t * (1f + p.value(0) / 150f) + (if (other) .25f else 0f)) * (.12f + .88f * p.value(1) / 10000f)
                }
                for (trace in 0 until if (p.kind == StudioKind.CHORUS) 2 else 1) {
                    var py = y(0f, trace == 1)
                    for (i in 1..96) {
                        val t = i / 96f; val ny = y(t, trace == 1); val px = left + w * (i - 1) / 96f; val nx = left + w * t
                        if (p.kind == StudioKind.CRUSHER) { c.line(px, py, nx, py, muted, ink.dp(1.7f)); c.line(nx, py, nx, ny, muted, ink.dp(1.4f)) }
                        else c.line(px, py, nx, ny, if (trace == 1) ink.palette.DIM else muted, ink.dp(1.7f)); py = ny
                    }
                }
            }
        }
    }
}
