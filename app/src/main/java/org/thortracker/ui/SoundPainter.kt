package org.thortracker.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.thortracker.editor.EditorSession
import org.thortracker.editor.GestureToken
import org.thortracker.editor.EditorSession.Companion.hex1
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.SoundParam
import org.thortracker.editor.bank
import org.thortracker.editor.channelCount
import org.thortracker.editor.firstChannelOf
import org.thortracker.model.Instrument
import org.thortracker.model.InstrumentKind
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * Sound (instrument) editor. Left: bank tabs and the 15 slots with how often each is used. Right: the
 * name, one slider per GB register setting, and plots computed from those settings — the volume
 * envelope with its length cut, the pulse duty shape, the assigned wave or the noise LFSR output.
 */
internal class SoundPainter(v: TrackerView) : Painter(v) {
    private val tabs = ArrayList<Pair<RectF, Int>>()
    private var slotTop = 0f
    private var slotH = 0f
    private var slotRect = RectF()
    private var paramTop = 0f
    private var paramH = 0f
    private var paramRect = RectF()
    private var barLeft = 0f
    private var barRight = 0f
    private val path = Path()

    private var usageSong: Song? = null
    private val usage = HashMap<Pair<InstrumentKind, Int>, Int>()

    override fun draw(c: Canvas, area: RectF) {
        val s = song
        val kind = st.bankKind
        val ins = s.bank(kind).getOrNull(st.instrument - 1) ?: return
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "${bankName(kind)} bank$locked  ${usageText(kind, st.instrument)}")
        val col = Palette.channel(maxOf(0, s.firstChannelOf(kind)))

        // left column: tabs + slots
        val leftW = minOf(area.width() * 0.34f, ink.unit * 15f)
        val left = RectF(area.left, top, area.left + leftW, area.bottom)
        ink.rect(c, left, Palette.LANE)
        val tabH = ink.unit * 1.9f
        tabs.clear()
        val tabW = leftW / InstrumentKind.entries.size
        for (k in InstrumentKind.entries) {
            val r = RectF(left.left + k.ordinal * tabW, top, left.left + (k.ordinal + 1) * tabW, top + tabH)
            val on = k == kind
            val kc = Palette.channel(maxOf(0, s.firstChannelOf(k)))
            if (on) ink.rect(c, r, Palette.mix(Palette.BG, kc, 0.18f))
            ink.rect(c, r.left, r.bottom - ink.dp(if (on) 3f else 1f), r.right, r.bottom, if (on) kc else Palette.RULE)
            ink.label(c, bankName(k), r.centerX(), r.top, r.height(), if (on) kc else Palette.DIM, on, align = Paint.Align.CENTER)
            tabs += r to k.ordinal
        }
        slotTop = top + tabH + ink.unit * 0.2f
        slotH = minOf(ink.unit * 1.5f, (area.bottom - slotTop) / EditorSession.BANK_SIZE)
        slotRect = RectF(left.left, slotTop, left.right, slotTop + slotH * EditorSession.BANK_SIZE)
        val bank = s.bank(kind)
        for (i in 1..EditorSession.BANK_SIZE) {
            val y = slotTop + (i - 1) * slotH
            val sel = i == st.instrument
            if (sel) {
                ink.rect(c, left.left, y, left.right, y + slotH, Palette.CURSOR_FILL)
                ink.rect(c, left.left, y, left.left + ink.dp(3f), y + slotH, col)
            }
            val sz = minOf(ink.unit, slotH * 0.72f)
            ink.mono(c, hex2(i), left.left + ink.unit * 0.6f, y, slotH, if (sel) col else Palette.FAINT, sel, sz)
            val used = usage(kind, i)
            val nameColor = if (sel) Palette.TEXT else if (used > 0) Palette.DIM else Palette.FAINT
            ink.label(c, ink.fit(bank[i - 1].name, leftW - ink.unit * 4.6f, sz), left.left + ink.unit * 2.4f, y, slotH, nameColor, size = sz)
            if (used > 0) ink.mono(c, used.toString(), left.right - ink.unit * 0.5f, y, slotH, Palette.FAINT, size = sz * 0.8f, align = Paint.Align.RIGHT)
        }
        if (st.instField == 0) {
            val y = slotTop + (st.instrument - 1) * slotH
            ink.outline(c, RectF(left.left + 2, y + 1, left.right - 2, y + slotH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }

        // right column: name + params
        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val rw = area.right - pad - rx
        val params = ctl.soundParams
        paramH = ink.unit * 1.55f
        var y = top + ink.unit * 0.4f
        // name
        val nameSel = st.instField == 1
        ink.label(c, "Name", rx, y, paramH, Palette.DIM)
        val nx = rx + ink.unit * 6.5f
        ink.label(c, ins.name.ifEmpty { "(unnamed)" }, nx, y, paramH, if (ins.name.isEmpty()) Palette.FAINT else Palette.TEXT, true)
        if (nameSel) {
            val before = ins.name.take(st.textPos).padEnd(st.textPos, ' ')
            val cx = nx + ink.labelWidth(before, ink.unit, true)
            val ch = ins.name.getOrNull(st.textPos)?.toString() ?: " "
            val w = maxOf(ink.labelWidth(ch, ink.unit, true), ink.unit * 0.5f)
            ink.outline(c, RectF(cx - 1, y + 2, cx + w + 1, y + paramH - 2), cursorColor(), ink.dp(2f), ink.dp(2f))
        }
        y += paramH
        paramTop = y
        barLeft = rx + ink.unit * 6.5f
        barRight = rx + rw - ink.unit * 9f
        for ((i, p) in params.withIndex()) {
            drawParam(c, p, ins, rx, y, rw, i + 2 == st.instField, col)
            y += paramH
        }
        paramRect = RectF(rx, paramTop, rx + rw, y)
        if (kind == InstrumentKind.PULSE && s.firstChannelOf(kind) >= 0) {
            ink.label(c, "Sweep plays only on ${Songs.channelNames(s)[s.firstChannelOf(kind)]}", rx, y, paramH * 0.8f, Palette.FAINT, size = ink.unit * 0.8f)
            y += paramH * 0.8f
        }

        // plots
        val plotTop = y + ink.unit * 0.5f
        if (area.bottom - plotTop > ink.unit * 4f) {
            val plots = RectF(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f)
            drawPlots(c, plots, ins, kind, col)
        }
    }

    private fun cursorColor() = when {
        ctl.editHeld -> Palette.WRITE
        ctl.auditionHeld -> Palette.LISTEN
        v.controllerFocused -> Palette.TEXT
        else -> Palette.DIM
    }

    private fun drawParam(c: Canvas, p: SoundParam, ins: Instrument, x: Float, y: Float, w: Float, selected: Boolean, col: Int) {
        if (selected) ink.rect(c, x - ink.unit * 0.4f, y, x + w + ink.unit * 0.4f, y + paramH, Palette.CURSOR_FILL)
        ink.label(c, p.label, x, y, paramH, if (selected) Palette.TEXT else Palette.DIM)
        val value = p.get(ins)
        val cy = y + paramH / 2
        val h = ink.dp(4f)
        ink.round(c, RectF(barLeft, cy - h / 2, barRight, cy + h / 2), h, Palette.RULE)
        val t = if (p.max == p.min) 0f else (value - p.min).toFloat() / (p.max - p.min)
        val kx = barLeft + (barRight - barLeft) * t
        ink.round(c, RectF(barLeft, cy - h / 2, kx, cy + h / 2), h, Palette.alpha(col, 170))
        // detents for small ranges
        if (p.max - p.min in 1..15) {
            for (i in 0..(p.max - p.min)) {
                val dx = barLeft + (barRight - barLeft) * i / (p.max - p.min)
                ink.rect(c, dx - 1, cy + h, dx + 1, cy + h * 2, Palette.FAINT)
            }
        }
        val knob = ink.dp(if (selected) 8f else 6f)
        ink.fill.color = if (selected) (if (ctl.editHeld) Palette.WRITE else Palette.TEXT) else col
        c.drawCircle(kx, cy, knob, ink.fill)
        ink.label(c, p.format(ins, value), barRight + ink.unit * 0.8f, y, paramH, if (selected) Palette.TEXT else Palette.DIM, selected)
        if (selected) ink.outline(c, RectF(x - ink.unit * 0.4f, y + 1, x + w + ink.unit * 0.4f, y + paramH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
    }

    private fun drawPlots(c: Canvas, r: RectF, ins: Instrument, kind: InstrumentKind, col: Int) {
        val gap = ink.unit
        val leftW = (r.width() - gap) * 0.6f
        val env = RectF(r.left, r.top, r.left + leftW, r.bottom)
        val shape = RectF(env.right + gap, r.top, r.right, r.bottom)
        drawEnvelope(c, env, ins, kind, col)
        when (kind) {
            InstrumentKind.PULSE -> drawDuty(c, shape, ins.duty, col)
            InstrumentKind.WAVE -> drawWave(c, shape, ins, col)
            InstrumentKind.NOISE -> drawNoise(c, shape, ins.noiseWidth, col)
        }
    }

    private fun frame(c: Canvas, r: RectF, title: String): RectF {
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        ink.label(c, title, r.left + ink.unit * 0.5f, r.top, ink.unit * 1.4f, Palette.FAINT, size = ink.unit * 0.8f)
        return RectF(r.left + ink.unit * 0.5f, r.top + ink.unit * 1.4f, r.right - ink.unit * 0.5f, r.bottom - ink.unit * 0.5f)
    }

    /** Volume over the first second, following the GB envelope (step every pace/64 s) and length timer. */
    private fun drawEnvelope(c: Canvas, r: RectF, ins: Instrument, kind: InstrumentKind, col: Int) {
        val inner = frame(c, r, "Volume over 1 second")
        val seconds = 1.0
        val cut = if (ins.lengthEnabled) {
            if (kind == InstrumentKind.WAVE) (256 - ins.length) / 256.0 else (64 - ins.length) / 256.0
        } else Double.MAX_VALUE
        path.reset()
        val steps = 256
        for (i in 0..steps) {
            val t = seconds * i / steps
            val vol = if (t >= cut) 0.0 else volumeAt(ins, kind, t)
            val x = inner.left + inner.width() * i / steps
            val y = inner.bottom - inner.height() * (vol / 15.0).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        ink.stroke.color = col
        ink.stroke.strokeWidth = ink.dp(2f)
        c.drawPath(path, ink.stroke)
        if (cut < seconds) {
            val x = inner.left + inner.width() * (cut / seconds).toFloat()
            ink.line(c, x, inner.top, x, inner.bottom, Palette.WRITE, ink.dp(1.5f))
            ink.label(c, "length cut ${"%.0f".format(cut * 1000)} ms", minOf(x + ink.unit * 0.3f, inner.right - ink.unit * 8f), inner.top, ink.unit * 1.2f, Palette.WRITE, size = ink.unit * 0.75f)
        }
    }

    private fun volumeAt(ins: Instrument, kind: InstrumentKind, t: Double): Double {
        if (kind == InstrumentKind.WAVE) {
            return when (ins.waveVolume) { 1 -> 15.0; 2 -> 7.5; 3 -> 3.75; else -> 0.0 }
        }
        if (ins.envelopePace == 0) return ins.volume.toDouble()
        val stepsTaken = (t / (ins.envelopePace / 64.0)).toInt()
        val level = if (ins.envelopeDirection == 1) ins.volume + stepsTaken else ins.volume - stepsTaken
        return level.coerceIn(0, 15).toDouble()
    }

    private fun drawDuty(c: Canvas, r: RectF, duty: Int, col: Int) {
        val inner = frame(c, r, "Pulse shape, 2 cycles")
        val frac = floatArrayOf(0.125f, 0.25f, 0.5f, 0.75f)[duty.coerceIn(0, 3)]
        path.reset()
        val hi = inner.top + inner.height() * 0.15f
        val lo = inner.bottom - inner.height() * 0.15f
        val cyc = inner.width() / 2
        path.moveTo(inner.left, lo)
        for (k in 0 until 2) {
            val x0 = inner.left + k * cyc
            path.lineTo(x0, hi)
            path.lineTo(x0 + cyc * frac, hi)
            path.lineTo(x0 + cyc * frac, lo)
            path.lineTo(x0 + cyc, lo)
        }
        ink.stroke.color = col
        ink.stroke.strokeWidth = ink.dp(2f)
        c.drawPath(path, ink.stroke)
    }

    private fun drawWave(c: Canvas, r: RectF, ins: Instrument, col: Int) {
        val w = song.waves.getOrNull(ins.wave) ?: return
        val inner = frame(c, r, "Wave ${hex1(ins.wave)} at output level")
        val shift = when (ins.waveVolume) { 1 -> 0; 2 -> 1; 3 -> 2; else -> 4 }
        val cw = inner.width() / w.size
        for ((i, sample) in w.withIndex()) {
            val level = sample shr shift
            val x = inner.left + i * cw
            val y = inner.bottom - inner.height() * level / 15f
            ink.rect(c, x, inner.bottom - inner.height() * sample / 15f, x + cw - 1, inner.bottom, Palette.alpha(col, 40))
            ink.rect(c, x, y, x + cw - 1, inner.bottom, col)
        }
    }

    /** First 128 output bits of the GB noise LFSR (15-bit or 7-bit width) from its reset state. */
    private fun drawNoise(c: Canvas, r: RectF, width: Int, col: Int) {
        val inner = frame(c, r, if (width == 1) "7-bit noise pattern repeats every 127 steps" else "15-bit noise pattern")
        var lfsr = 0x7FFF
        val n = 128
        val bw = inner.width() / n
        for (i in 0 until n) {
            val bit = (lfsr xor (lfsr shr 1)) and 1
            lfsr = (lfsr shr 1) or (bit shl 14)
            if (width == 1) lfsr = (lfsr and (1 shl 6).inv()) or (bit shl 6)
            val out = (lfsr and 1) xor 1
            val x = inner.left + i * bw
            if (out == 1) ink.rect(c, x, inner.top + inner.height() * 0.2f, x + maxOf(bw - 0.5f, 1f), inner.bottom - inner.height() * 0.2f, col)
        }
    }

    private fun bankName(k: InstrumentKind) = when (k) {
        InstrumentKind.PULSE -> "Pulse"
        InstrumentKind.WAVE -> "Wave"
        InstrumentKind.NOISE -> "Noise"
    }

    private fun usage(kind: InstrumentKind, slot: Int): Int {
        val s = song
        if (s !== usageSong) {
            usage.clear()
            for (ch in 0 until s.channelCount()) {
                val k = Songs.channelKind(s, ch)
                val counted = HashSet<Int>()
                for (pat in s.orders) {
                    if (!counted.add(pat)) continue
                    for (cell in s.patterns[pat].tracks[ch]) {
                        if (cell.instrument > 0) usage[k to cell.instrument] = (usage[k to cell.instrument] ?: 0) + 1
                    }
                }
            }
            usageSong = s
        }
        return usage[kind to slot] ?: 0
    }

    private fun usageText(kind: InstrumentKind, slot: Int): String {
        val n = usage(kind, slot)
        return if (n == 0) "not used in the arrangement yet" else "used on $n row${if (n == 1) "" else "s"}"
    }

    // ---- touch ---------------------------------------------------------------------------------------

    /** The slider being dragged, pinned to its sound at touch-down so nothing can redirect the drag. */
    private var dragParam: SoundParam? = null
    private var dragKind = InstrumentKind.PULSE
    private var dragSlot = 0

    override fun down(x: Float, y: Float): Boolean {
        tabs.firstOrNull { it.first.contains(x, y) }?.let { (_, k) ->
            st.bank = k
            st.instField = st.instField.coerceAtMost(1 + ctl.soundParams.size)
            ctl.publishSound()
            return true
        }
        if (slotRect.contains(x, y)) {
            st.instrument = (((y - slotTop) / slotH).toInt() + 1).coerceIn(1, EditorSession.BANK_SIZE)
            st.instField = 0
            ctl.publishSound()
            return true
        }
        if (paramRect.contains(x, y)) {
            val i = ((y - paramTop) / paramH).toInt()
            val p = ctl.soundParams.getOrNull(i) ?: return false
            st.instField = i + 2
            if (x >= barLeft - ink.unit) {
                dragParam = p
                dragKind = st.bankKind
                dragSlot = st.instrument
                dragGesture = session.beginGesture("Set ${p.label.lowercase()}")
                setFromX(p, x)
            } else if (p.max - p.min == 1) {
                session.editInstrument(st.bankKind, st.instrument, "Toggle ${p.label.lowercase()}") { p.set(it, if (p.get(it) == p.min) p.max else p.min) }
            }
            return true
        }
        return false
    }

    private fun setFromX(p: SoundParam, x: Float) {
        val t = ((x - barLeft) / (barRight - barLeft)).coerceIn(0f, 1f)
        val value = p.min + Math.round(t * (p.max - p.min))
        session.editInstrument(dragKind, dragSlot, "Set ${p.label.lowercase()}") { p.set(it, value) }
    }

    override fun move(x: Float, y: Float) {
        val p = dragParam ?: return
        // Undo or redo closed the drag's group: end the drag instead of writing outside any group.
        val g = dragGesture
        if (g == null || !session.isGestureOpen(g)) { dragParam = null; dragGesture = null; return }
        setFromX(p, x)
    }

    override fun up(x: Float, y: Float) = finish()
    override fun cancel() = finish()

    private var dragGesture: GestureToken? = null

    private fun finish() {
        val p = dragParam ?: return
        dragParam = null
        dragGesture?.let { session.endGesture(it) }
        dragGesture = null
        if (p.label == "Wave" && st.bankKind == dragKind && st.instrument == dragSlot) ctl.publishSound()
    }
}
