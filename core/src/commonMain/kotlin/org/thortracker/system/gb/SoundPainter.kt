package org.thortracker.system.gb

import kotlin.math.abs
import kotlin.math.ceil
import org.thortracker.backend.HugeLowering
import org.thortracker.editor.EditorSession.Companion.hex1
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.channelCount
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * The Game Boy Sound panel. Wide panes: the bank tabs and the 15 slots (each with a small drawing of its duty, wave or
 * noise and how often it is used) on the left; narrow panes put the tabs and slots in a grid on top. The panel itself
 * opens with the timeline — the volume the APU outputs from the note's start (envelope steps, length cut, Pulse 1's
 * sweep at the listening note and its overflow) — beside the sound's character (the duty's real 8-step pattern, the
 * wave at its output level, the LFSR's bits at the listening note's NR43), a line stating the timing exactly, then one
 * row per register setting: segments for short choices (tap one), a wave strip (tap or drag), sliders for ranges
 * (drag), and the bytes hUGEDriver writes. Rows grow to touch size when the pane has room and scroll to keep the
 * cursor in view when it has not; the timeline gives way first on short panes. Colours are the palette's roles only.
 */
internal class SoundPainter(v: PaneView) : Painter(v) {
    private companion object {
        const val SLOTS = HugeRules.INSTRUMENT_SLOTS
        const val MAX_ROWS = 10 // the name and the pulse bank's nine settings
        const val MAX_CELLS = 16 // the wave strip
        const val GRID_COLUMNS = 5
        const val NARROW_UNITS = 40f
        const val NOISE_BITS = 96

        /** First output bits after a trigger, 15-bit and 7-bit. */
        val NOISE = Array(2) { w -> BooleanArray(NOISE_BITS).also { SoundTimeline.lfsrBits(w, it) } }

        /** Output choices by rank (Mute, 25%, 50%, 100%) as a fraction of full scale, for their drawings. */
        val OUTPUT_AMP = floatArrayOf(0f, 0.25f, 0.5f, 1f)
    }

    /** A touch drag on one setting of one sound, pinned at touch-down. */
    private class DragTarget(val param: SoundParam, val kind: GbBank, val slot: Int, val row: Int)

    private val tabRects = Array(GbBank.entries.size) { Rect() }
    private val slotRects = Array(SLOTS) { Rect() }
    private val rowRects = Array(MAX_ROWS) { Rect() }
    private val rowFields = IntArray(MAX_ROWS)
    private var rowCount = 0
    private var rowScroll = 0
    private val railLeft = FloatArray(MAX_ROWS)
    private val railRight = FloatArray(MAX_ROWS)
    private val cellRects = Array(MAX_ROWS) { Array(MAX_CELLS) { Rect() } }
    private val cellCounts = IntArray(MAX_ROWS)
    private val panel = Rect()
    private val box = Rect()
    private val glyph = Rect()
    private val scratch = Rect()
    private val inner = Rect()

    /** Whether the last timeline had room for Pulse 1's pitch band (else the caption states the sweep). */
    private var pitchShown = false
    private val xs = FloatArray(520)
    private val ys = FloatArray(520)
    private val drag = PanelDrag<DragTarget>()

    /** Whether the last frame had room for the timeline. */
    internal var timelineShown = false
        private set

    private var usageSong: Song? = null
    private val usage = HashMap<Pair<GbBank, Int>, Int>()

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val kind = GbSoundView.kind(st)
        val ins = s.bank(kind).getOrNull(st.instrument - 1) ?: return
        val u = ink.unit
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "${bankName(kind)} bank$locked  ${usageText(kind, st.instrument)}")
        val col = accent(kind)
        val pad = u * 0.9f
        if (area.width() >= u * NARROW_UNITS) {
            val leftW = minOf(area.width() * 0.3f, u * 14f)
            drawBankColumn(c, area.left, top, area.left + leftW, area.bottom, kind, col)
            panel.set(area.left + leftW + pad, top + u * 0.4f, area.right - pad, area.bottom)
        } else {
            val bottom = drawBankGrid(c, area.left, top, area.right, area.bottom, kind, col)
            panel.set(area.left + pad * 0.6f, bottom + u * 0.3f, area.right - pad * 0.6f, area.bottom)
        }
        drawPanel(c, ins, kind, col)
    }

    private fun accent(kind: GbBank) = ink.palette.channel(maxOf(0, song.firstChannelOf(kind)))

    private fun cursorColor() = when {
        ctl.editHeld || drag.target != null -> ink.palette.WRITE
        ctl.auditionHeld -> ink.palette.LISTEN
        v.controllerFocused -> ink.palette.FOCUS
        else -> ink.palette.DIM
    }

    // ---- bank and slots ---------------------------------------------------------------------------------------

    private fun drawTab(c: Surface, k: GbBank, r: Rect, current: GbBank) {
        val on = k == current
        val kc = accent(k)
        if (on) ink.rect(c, r, Palette.mix(ink.palette.BG, kc, 0.18f))
        ink.rect(c, r.left, r.bottom - ink.dp(if (on) 3f else 1f), r.right, r.bottom, if (on) kc else ink.palette.RULE)
        ink.label(c, ink.fit(bankName(k), r.width() - ink.unit * 0.3f), r.centerX(), r.top, r.height(), if (on) kc else ink.palette.DIM, on, align = Align.CENTER)
    }

    /** Wide panes: tabs over a column of the 15 slots, which grow toward touch size when there is room. */
    private fun drawBankColumn(c: Surface, l: Float, t: Float, r: Float, b: Float, kind: GbBank, col: Int) {
        val u = ink.unit
        ink.rect(c, l, t, r, b, ink.palette.LANE)
        val tabH = u * 1.9f
        val tabW = (r - l) / GbBank.entries.size
        for (k in GbBank.entries) {
            tabRects[k.ordinal].set(l + k.ordinal * tabW, t, l + (k.ordinal + 1) * tabW, t + tabH)
            drawTab(c, k, tabRects[k.ordinal], kind)
        }
        val slotTop = t + tabH + u * 0.2f
        val slotH = minOf(maxOf(u * 1.5f, ink.dp(40f)), (b - slotTop) / SLOTS)
        val bank = song.bank(kind)
        for (i in 1..SLOTS) {
            val y = slotTop + (i - 1) * slotH
            val rect = slotRects[i - 1]
            rect.set(l, y, r, y + slotH)
            val sel = i == st.instrument
            if (sel) {
                ink.rect(c, rect, ink.palette.CURSOR_FILL)
                ink.rect(c, l, y, l + ink.dp(3f), y + slotH, col)
            }
            val sz = minOf(u, slotH * 0.72f)
            ink.mono(c, hex2(i), l + u * 0.6f, y, slotH, if (sel) col else ink.palette.FAINT, sel, sz)
            val used = usage(kind, i)
            val countW = if (used > 0) ink.monoWidth(used.toString(), sz * 0.8f) + u * 0.6f else u * 0.4f
            val glyphW = if (r - l >= u * 10f) minOf(u * 2.2f, slotH * 1.6f) else 0f
            if (glyphW > 0f) {
                glyph.set(r - countW - glyphW, y + slotH * 0.22f, r - countW - u * 0.3f, y + slotH * 0.78f)
                drawSlotGlyph(c, bank[i - 1], glyph, if (sel) col else ink.palette.FAINT)
            }
            val nameColor = if (sel) ink.palette.TEXT else if (used > 0) ink.palette.DIM else ink.palette.FAINT
            ink.label(c, ink.fit(bank[i - 1].name, r - l - u * 2.4f - countW - glyphW - u * 0.3f, sz), l + u * 2.4f, y, slotH, nameColor, size = sz)
            if (used > 0) ink.mono(c, used.toString(), r - u * 0.5f, y, slotH, ink.palette.FAINT, size = sz * 0.8f, align = Align.RIGHT)
        }
        if (st.instField == 0) {
            val rect = slotRects[st.instrument - 1]
            scratch.set(rect.left + 2, rect.top + 1, rect.right - 2, rect.bottom - 1)
            ink.outline(c, scratch, cursorColor(), ink.dp(2f), ink.dp(3f))
        }
    }

    /** Narrow panes: a row of bank tabs and the 15 slots as a 5 × 3 grid, all touch-sized when the pane is tall enough. */
    private fun drawBankGrid(c: Surface, l: Float, t: Float, r: Float, b: Float, kind: GbBank, col: Int): Float {
        val u = ink.unit
        val cellH = ((b - t) * 0.24f / 4f).coerceIn(u * 1.4f, maxOf(u * 1.6f, ink.dp(44f)))
        val gap = ink.dp(2f)
        val tabW = (r - l) / GbBank.entries.size
        for (k in GbBank.entries) {
            tabRects[k.ordinal].set(l + k.ordinal * tabW, t, l + (k.ordinal + 1) * tabW, t + cellH)
            drawTab(c, k, tabRects[k.ordinal], kind)
        }
        val gridTop = t + cellH + gap
        val cw = (r - l - gap * (GRID_COLUMNS - 1)) / GRID_COLUMNS
        val bank = song.bank(kind)
        for (i in 1..SLOTS) {
            val row = (i - 1) / GRID_COLUMNS
            val column = (i - 1) % GRID_COLUMNS
            val x = l + column * (cw + gap)
            val y = gridTop + row * (cellH + gap)
            val rect = slotRects[i - 1]
            rect.set(x, y, x + cw, y + cellH)
            val sel = i == st.instrument
            ink.round(c, rect, ink.dp(3f), if (sel) ink.palette.CURSOR_FILL else ink.palette.LANE)
            if (sel) ink.rect(c, x, rect.bottom - ink.dp(3f), x + cw, rect.bottom, col)
            val sz = minOf(u, cellH * 0.62f)
            val numW = ink.monoWidth("00", sz, true) + u * 0.5f
            ink.mono(c, hex2(i), x + u * 0.3f, y, cellH, if (sel) col else ink.palette.FAINT, sel, sz)
            ink.label(c, ink.fit(bank[i - 1].name, cw - numW - u * 0.3f, sz * 0.9f), x + numW, y, cellH,
                if (sel) ink.palette.TEXT else if (usage(kind, i) > 0) ink.palette.DIM else ink.palette.FAINT, size = sz * 0.9f)
            if (sel && st.instField == 0) ink.outline(c, rect, cursorColor(), ink.dp(2f), ink.dp(3f))
        }
        return gridTop + 3 * cellH + 2 * gap
    }

    /** A slot's character at a glance: its duty pattern, its wave or its noise texture. */
    private fun drawSlotGlyph(c: Surface, ins: GbInstrument, r: Rect, color: Int) {
        when (ins.kind) {
            GbBank.PULSE -> drawDutyCycle(c, r, ins.duty, color, 1)
            GbBank.WAVE -> drawWaveLine(c, r, song.gb.waves.getOrNull(ins.wave), 0, color)
            GbBank.NOISE -> drawBits(c, r, NOISE[ins.noiseWidth and 1], 12, color)
        }
    }

    // ---- the panel ---------------------------------------------------------------------------------------------

    private fun drawPanel(c: Surface, ins: GbInstrument, kind: GbBank, col: Int) {
        val u = ink.unit
        val params = GbSoundView.params(st)
        val n = 1 + params.size
        val footerH = u * 1.2f
        val captionH = u * 1.25f
        val gap = u * 0.4f
        val minRow = u * 1.35f
        val maxRow = maxOf(u * 1.6f, ink.dp(48f))
        val note = ctl.defaultNote()
        val avail = panel.height() - footerH
        val heroSpace = avail - n * minRow - captionH - gap * 2
        val heroH = if (heroSpace >= u * 4.5f) minOf(heroSpace, u * 9f) else 0f
        timelineShown = heroH > 0f
        pitchShown = false
        var y = panel.top
        if (heroH > 0f) {
            drawHero(c, panel.left, y, panel.right, y + heroH, ins, kind, note, col)
            y += heroH + gap * 0.5f
        }
        if (avail - n * minRow - heroH - gap >= captionH) {
            drawCaption(c, panel.left, y, panel.right, captionH, ins, note, !pitchShown)
            y += captionH + gap * 0.5f
        }

        // rows: as tall as the room allows up to touch size; scrolled to keep the cursor's row in view
        val rowsBottom = panel.bottom - footerH
        val rowH = ((rowsBottom - y) / n).coerceIn(minRow, maxRow)
        val visible = ((rowsBottom - y) / rowH).toInt().coerceIn(1, n)
        val selRow = st.instField - 1
        if (selRow >= 0) rowScroll = rowScroll.coerceIn(maxOf(0, selRow - visible + 1), minOf(selRow, n - visible))
        rowScroll = rowScroll.coerceIn(0, n - visible)
        rowCount = 0
        for (k in 0 until visible) {
            val field = rowScroll + k + 1
            drawRow(c, rowCount, field, panel.left, y + k * rowH, panel.right, rowH, ins, params, col)
            rowCount++
        }
        if (visible < n) drawScrollMarks(c, panel.right, y, y + visible * rowH, rowScroll > 0, rowScroll + visible < n)
        drawRegisters(c, panel.left, panel.bottom - footerH, panel.right, footerH, ins, kind, note)
    }

    private fun drawScrollMarks(c: Surface, right: Float, top: Float, bottom: Float, above: Boolean, below: Boolean) {
        val s = ink.dp(4f)
        val x = right - s
        if (above) { ink.line(c, x - s, top + s * 1.5f, x, top + s * 0.5f, ink.palette.DIM, ink.dp(1.5f)); ink.line(c, x, top + s * 0.5f, x + s, top + s * 1.5f, ink.palette.DIM, ink.dp(1.5f)) }
        if (below) { ink.line(c, x - s, bottom - s * 1.5f, x, bottom - s * 0.5f, ink.palette.DIM, ink.dp(1.5f)); ink.line(c, x, bottom - s * 0.5f, x + s, bottom - s * 1.5f, ink.palette.DIM, ink.dp(1.5f)) }
    }

    private fun drawCaption(c: Surface, l: Float, t: Float, r: Float, h: Float, ins: GbInstrument, note: Int, withSweep: Boolean) {
        val text = SoundTimeline.describe(ins)
        val sweep = if (withSweep) SoundTimeline.describeSweep(ins, note, GbSystem.noteName(note)) else null
        val line = if (sweep != null) "$text · $sweep" else text
        val size = ink.unit * 0.85f
        val color = if (text.startsWith("Silent")) ink.palette.ERROR else ink.palette.DIM
        ink.label(c, ink.fit(line, r - l, size), l, t, h, color, size = size)
    }

    private fun drawRow(c: Surface, i: Int, field: Int, l: Float, y: Float, r: Float, h: Float, ins: GbInstrument,
                        params: List<SoundParam>, col: Int) {
        val u = ink.unit
        val sel = field == st.instField
        rowFields[i] = field
        rowRects[i].set(l, y, r, y + h)
        cellCounts[i] = 0
        railLeft[i] = 0f
        railRight[i] = 0f
        if (sel) ink.rect(c, l - u * 0.4f, y, r + u * 0.4f, y + h, ink.palette.CURSOR_FILL)
        val labelW = minOf(u * 6.4f, (r - l) * 0.3f)
        val cx0 = l + labelW
        val label = if (field == 1) "Name" else params[field - 2].label
        ink.label(c, ink.fit(label, labelW - u * 0.3f, u, sel), l, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM, sel)
        if (field == 1) {
            drawName(c, ins.name, cx0, y, h, r, sel)
        } else {
            val p = params[field - 2]
            when {
                p === SoundParams.wave -> drawWaveStrip(c, i, cx0, y, r, h, ins, col)
                p.choice -> drawChoices(c, i, p, cx0, y, r, h, ins, col)
                else -> drawRail(c, i, p, cx0, y, r, h, ins, sel, col)
            }
        }
        if (sel) {
            scratch.set(l - u * 0.4f, y + 1, r + u * 0.4f, y + h - 1)
            ink.outline(c, scratch, cursorColor(), ink.dp(2f), ink.dp(3f))
        }
    }

    private fun drawName(c: Surface, name: String, x: Float, y: Float, h: Float, r: Float, sel: Boolean) {
        ink.label(c, ink.fit(name.ifEmpty { "(unnamed)" }, r - x, ink.unit, true), x, y, h, if (name.isEmpty()) ink.palette.FAINT else ink.palette.TEXT, true)
        if (!sel) return
        val before = name.take(st.textPos).padEnd(st.textPos, ' ')
        val cx = x + ink.labelWidth(before, ink.unit, true)
        val ch = name.getOrNull(st.textPos)?.toString() ?: " "
        val w = maxOf(ink.labelWidth(ch, ink.unit, true), ink.unit * 0.5f)
        scratch.set(cx - 1, y + h * 0.15f, cx + w + 1, y + h * 0.85f)
        ink.outline(c, scratch, cursorColor(), ink.dp(2f), ink.dp(2f))
    }

    private fun drawRail(c: Surface, i: Int, p: SoundParam, x0: Float, y: Float, r: Float, h: Float, ins: GbInstrument, sel: Boolean, col: Int) {
        val u = ink.unit
        val value = p.get(ins)
        val text = p.format(ins, value)
        // A fixed value column, so the rail never moves under a dragging finger as the text changes.
        val valueW = minOf(u * 9.5f, (r - x0) * 0.45f)
        val left = x0 + u * 0.4f
        val right = r - valueW - u * 0.6f
        railLeft[i] = left
        railRight[i] = right
        val fraction = if (p.max == p.min) 0f else (value - p.min).toFloat() / (p.max - p.min)
        PanelWidgets.rail(ink, c, left, right, y + h / 2, fraction, p.max - p.min, col, sel, sel && (ctl.editHeld || drag.target != null))
        ink.label(c, ink.fit(text, valueW, u, sel), r - valueW, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM, sel)
    }

    private fun drawChoices(c: Surface, i: Int, p: SoundParam, x0: Float, y: Float, r: Float, h: Float, ins: GbInstrument, col: Int) {
        val u = ink.unit
        val count = p.max - p.min + 1
        val cells = cellRects[i]
        box.set(x0, y + h * 0.1f, r, y + h * 0.9f)
        PanelWidgets.cells(box, count, ink.dp(3f), cells)
        cellCounts[i] = count
        val current = p.get(ins)
        val size = minOf(u * 0.85f, box.height() * 0.62f)
        for (k in 0 until count) {
            val cell = cells[k]
            val value = p.min + k
            val chosen = value == current
            PanelWidgets.segment(ink, c, cell, chosen, col)
            val text = p.format(ins, value)
            val tw = ink.labelWidth(text, size, chosen)
            val gh = minOf(cell.height() * 0.5f, u * 1.1f)
            val gw = gh * 1.7f
            val color = if (chosen) col else ink.palette.DIM
            if (cell.width() >= gw + tw + u * 1.2f) {
                val gx = cell.left + (cell.width() - gw - tw - u * 0.5f) / 2f
                glyph.set(gx, cell.centerY() - gh / 2, gx + gw, cell.centerY() + gh / 2)
                drawChoiceGlyph(c, p, value, glyph, color)
                ink.label(c, text, glyph.right + u * 0.5f, cell.top, cell.height(), if (chosen) ink.palette.TEXT else ink.palette.DIM, chosen, size)
            } else {
                ink.label(c, ink.fit(text, cell.width() - u * 0.3f, size, chosen), cell.centerX(), cell.top, cell.height(),
                    if (chosen) ink.palette.TEXT else ink.palette.DIM, chosen, size, Align.CENTER)
            }
        }
    }

    /** The drawing on a choice: each one shows what that register value does. */
    private fun drawChoiceGlyph(c: Surface, p: SoundParam, value: Int, r: Rect, color: Int) {
        val w = ink.dp(1.5f)
        when {
            p === SoundParams.duty -> drawDutyCycle(c, r, value, color, 1)
            p === SoundParams.envDir -> {
                // four volume steps falling (Fade) or rising (Swell)
                val bw = r.width() / 4f
                for (k in 0 until 4) {
                    val level = if (value == 0) 4 - k else k + 1
                    val x = r.left + k * bw
                    ink.rect(c, x + ink.dp(0.5f), r.bottom - r.height() * level / 4f, x + bw - ink.dp(0.5f), r.bottom, color)
                }
            }
            p === SoundParams.lengthOn -> {
                val hi = r.top + r.height() * 0.3f
                if (value == 0) ink.line(c, r.left, hi, r.right, hi, color, w)
                else {
                    val cut = r.left + r.width() * 0.6f
                    ink.line(c, r.left, hi, cut, hi, color, w)
                    ink.line(c, cut, hi, cut, r.bottom, color, w)
                    ink.line(c, cut, r.bottom, r.right, r.bottom, color, w)
                }
            }
            p === SoundParams.sweepDir -> {
                var n = 0
                for (k in 0..3) {
                    val x0 = r.left + r.width() * k / 4f
                    val x1 = r.left + r.width() * (k + 1) / 4f
                    val yk = if (value == 0) r.top + r.height() * k / 3f else r.bottom - r.height() * k / 3f
                    xs[n] = x0; ys[n] = yk; n++
                    xs[n] = x1; ys[n] = yk; n++
                }
                c.polyline(xs, ys, n, color, w)
            }
            p === SoundParams.waveVolume -> {
                // a triangle at the output level: mute flat, then 25, 50, 100%
                val amp = r.height() / 2f * OUTPUT_AMP[value.coerceIn(0, 3)]
                val mid = r.centerY()
                xs[0] = r.left; ys[0] = mid
                xs[1] = r.left + r.width() * 0.25f; ys[1] = mid - amp
                xs[2] = r.left + r.width() * 0.75f; ys[2] = mid + amp
                xs[3] = r.right; ys[3] = mid
                c.polyline(xs, ys, 4, color, w)
            }
            p === SoundParams.noiseWidth -> drawBits(c, r, NOISE[value and 1], 14, color)
        }
    }

    private fun drawWaveStrip(c: Surface, i: Int, x0: Float, y: Float, r: Float, h: Float, ins: GbInstrument, col: Int) {
        val u = ink.unit
        val cells = cellRects[i]
        box.set(x0, y + h * 0.1f, r, y + h * 0.9f)
        PanelWidgets.cells(box, HugeRules.WAVE_COUNT, ink.dp(2f), cells)
        cellCounts[i] = HugeRules.WAVE_COUNT
        val waves = song.gb.waves
        for (k in 0 until HugeRules.WAVE_COUNT) {
            val cell = cells[k]
            val chosen = k == ins.wave
            PanelWidgets.segment(ink, c, cell, chosen, col)
            val labelled = cell.width() >= u * 1.3f
            val labelH = if (labelled) minOf(cell.height() * 0.4f, u * 0.9f) else 0f
            if (labelled) ink.mono(c, hex1(k), cell.centerX(), cell.top, labelH, if (chosen) ink.palette.TEXT else ink.palette.FAINT, chosen, labelH * 0.8f, Align.CENTER)
            glyph.set(cell.left + ink.dp(2f), cell.top + labelH + ink.dp(1f), cell.right - ink.dp(2f), cell.bottom - ink.dp(4f))
            drawWaveLine(c, glyph, waves.getOrNull(k), 0, if (chosen) col else ink.palette.DIM)
        }
    }

    // ---- the timeline and the character box ----------------------------------------------------------------------

    private fun drawHero(c: Surface, l: Float, t: Float, r: Float, b: Float, ins: GbInstrument, kind: GbBank, note: Int, col: Int) {
        val u = ink.unit
        val gap = u * 0.6f
        val charW = if (r - l >= u * 24f) minOf((r - l) * 0.36f, u * 16f) else 0f
        val ribbonR = if (charW > 0f) r - charW - gap else r
        drawTimeline(c, l, t, ribbonR, b, ins, kind, note, col)
        if (charW > 0f) drawCharacter(c, ribbonR + gap, t, r, b, ins, kind, note, col)
    }

    private fun timeText(seconds: Double): String =
        if (seconds >= 1.0) "${NumberText.fixed(seconds, if (seconds % 1.0 == 0.0) 0 else 2)} s" else "${NumberText.fixed(seconds * 1000.0, 0)} ms"

    private fun drawTimeline(c: Surface, l: Float, t: Float, r: Float, b: Float, ins: GbInstrument, kind: GbBank, note: Int, col: Int) {
        val u = ink.unit
        box.set(l, t, r, b)
        ink.round(c, box, ink.dp(4f), ink.palette.LANE)
        val titleH = u * 1.3f
        val small = u * 0.75f
        val window = SoundTimeline.windowSeconds(ins, note)
        ink.label(c, ink.fit("Volume, first ${timeText(window)}", r - l - u), l + u * 0.5f, t, titleH, ink.palette.FAINT, size = small)
        val axisH = u * 1.1f
        val gl = l + u * 1.6f
        val gr = r - u * 0.6f
        val gt = t + titleH
        val gb = b - axisH
        if (gb - gt < u * 1.5f) return
        fun xOf(s: Double) = gl + ((s / window).coerceIn(0.0, 1.0) * (gr - gl)).toFloat()

        val cut = SoundTimeline.cutSeconds(ins)
        val sweeps = kind == GbBank.PULSE && (ins.sweepTime != 0 || ins.sweepShift != 0)
        val off = if (kind == GbBank.PULSE) SoundTimeline.sweepOffSeconds(ins, note, window) else null
        val end = minOf(cut ?: Double.MAX_VALUE, off ?: Double.MAX_VALUE)
        var volTop = gt
        if (sweeps && gb - gt >= u * 4f) {
            pitchShown = true
            val bandB = gt + (gb - gt) * 0.36f
            drawPitch(c, gl, gt, gr, bandB, ins, note, window, end, ::xOf)
            volTop = bandB + u * 0.3f
        }
        drawVolume(c, gl, volTop, gr, gb, ins, window, end, col, ::xOf)

        // time axis
        for (k in 0..4) {
            val x = gl + (gr - gl) * k / 4f
            ink.line(c, x, gb, x, gb + ink.dp(3f), ink.palette.FAINT, ink.dp(1f))
        }
        ink.label(c, "0", gl, gb, axisH, ink.palette.FAINT, size = small, align = Align.CENTER)
        ink.label(c, timeText(window / 2), (gl + gr) / 2, gb, axisH, ink.palette.FAINT, size = small, align = Align.CENTER)
        ink.label(c, timeText(window), gr, gb, axisH, ink.palette.FAINT, size = small, align = Align.RIGHT)

        // what silences the note, where
        if (cut != null && cut <= window) {
            val x = xOf(cut)
            ink.line(c, x, gt, x, gb, ink.palette.DIM, ink.dp(1.5f))
            ink.label(c, "cut ${SoundTimeline.ms(cut)}", minOf(x + u * 0.3f, gr - ink.labelWidth("cut ${SoundTimeline.ms(cut)}", small)), volTop, u, ink.palette.DIM, size = small)
        }
        if (off != null && off <= window && (cut == null || off < cut)) {
            val x = xOf(off)
            ink.line(c, x, gt, x, gb, ink.palette.ERROR, ink.dp(1.5f))
            val text = if (off == 0.0) "sweep overflow: silent" else "sweep overflow ${SoundTimeline.ms(off)}"
            ink.label(c, text, minOf(x + u * 0.3f, gr - ink.labelWidth(text, small)), gb - u * 1.1f, u, ink.palette.ERROR, size = small)
        }
    }

    /** The level as stepped bars: each envelope step's span filled, its top edge drawn, silence after [end]. */
    private fun drawVolume(c: Surface, l: Float, t: Float, r: Float, b: Float, ins: GbInstrument, window: Double, end: Double, col: Int,
                           xOf: (Double) -> Float) {
        val u = ink.unit
        fun yOf(level: Int) = b - (b - t) * level / 15f
        ink.line(c, l, yOf(15), r, yOf(15), ink.palette.RULE, ink.dp(1f))
        ink.line(c, l, yOf(0), r, yOf(0), ink.palette.RULE, ink.dp(1f))
        val small = u * 0.7f
        ink.mono(c, "15", l - u * 0.25f, yOf(15) - u * 0.45f, u * 0.9f, ink.palette.FAINT, size = small, align = Align.RIGHT)
        ink.mono(c, "0", l - u * 0.25f, yOf(0) - u * 0.45f, u * 0.9f, ink.palette.FAINT, size = small, align = Align.RIGHT)
        if (SoundTimeline.dacOff(ins)) {
            ink.label(c, ink.fit("DAC off: silent", r - l, u * 0.85f), (l + r) / 2, t, b - t, ink.palette.ERROR, size = u * 0.85f, align = Align.CENTER)
            return
        }
        val step = SoundTimeline.stepSeconds(ins)
        val steps = SoundTimeline.envelopeSteps(ins)
        val stop = minOf(end, window)
        val fill = Palette.alpha(col, 70)
        var n = 0
        var from = 0.0
        var level = SoundTimeline.level(ins, 0.0)
        var k = 1
        xs[n] = xOf(0.0); ys[n] = yOf(level); n++
        while (true) {
            val next = if (step != null && k <= steps) k * step else Double.MAX_VALUE
            val until = minOf(next, stop)
            if (level > 0) ink.rect(c, xOf(from), yOf(level), xOf(until), b, fill)
            xs[n] = xOf(until); ys[n] = yOf(level); n++
            if (until >= stop) break
            level = SoundTimeline.level(ins, next)
            xs[n] = xOf(next); ys[n] = yOf(level); n++
            from = next
            k++
        }
        if (stop < window) {
            xs[n] = xOf(stop); ys[n] = yOf(0); n++
            xs[n] = xOf(window); ys[n] = yOf(0); n++
        }
        c.polyline(xs, ys, n, col, ink.dp(2f))
    }

    /** Pulse 1's pitch in semitones from the listening note, clock by clock, until the note is silenced. */
    private fun drawPitch(c: Surface, l: Float, t: Float, r: Float, b: Float, ins: GbInstrument, note: Int, window: Double, end: Double,
                          xOf: (Double) -> Float) {
        val u = ink.unit
        val small = u * 0.7f
        scratch.set(l, t, r, b)
        ink.round(c, scratch, ink.dp(3f), Palette.mix(ink.palette.LANE, ink.palette.BG, 0.5f))
        val label = SoundTimeline.describeSweep(ins, note, GbSystem.noteName(note)) ?: return
        val start = SoundTimeline.notePeriod(note)
        val clock = SoundTimeline.sweepClockSeconds(ins)
        val stop = minOf(end, window)
        // the range: the largest change before the stop
        var most = 1.0
        if (clock != null && !SoundTimeline.sweepSilencesAtTrigger(ins, start)) {
            var p = start
            var tc = clock
            while (tc <= stop + 1e-9) {
                p = SoundTimeline.sweepClock(ins, p)
                if (p < 0) break
                most = maxOf(most, abs(SoundTimeline.semitones(start, p)))
                tc += clock
            }
        }
        val range = ceil(most)
        val mid = (t + b) / 2
        fun yOf(semi: Double) = (mid - (semi / range) * ((b - t) / 2 - ink.dp(2f))).toFloat()
        ink.line(c, l, mid, r, mid, ink.palette.RULE, ink.dp(1f))
        ink.label(c, ink.fit(label, r - l - u), l + u * 0.3f, t, u, ink.palette.DIM, size = small)
        ink.mono(c, "±${NumberText.fixed(range, 0)} st", r - u * 0.2f, b - u, u, ink.palette.FAINT, size = small, align = Align.RIGHT)
        if (SoundTimeline.sweepSilencesAtTrigger(ins, start) || stop <= 0.0) return
        var n = 0
        xs[n] = xOf(0.0); ys[n] = yOf(0.0); n++
        if (clock != null) {
            var p = start
            var tc = clock
            while (tc < stop - 1e-9 && n < xs.size - 3) {
                val next = SoundTimeline.sweepClock(ins, p)
                if (next < 0) break
                xs[n] = xOf(tc); ys[n] = yOf(SoundTimeline.semitones(start, p)); n++
                xs[n] = xOf(tc); ys[n] = yOf(SoundTimeline.semitones(start, next)); n++
                p = next
                tc += clock
            }
            xs[n] = xOf(stop); ys[n] = yOf(SoundTimeline.semitones(start, p)); n++
        } else {
            xs[n] = xOf(stop); ys[n] = yOf(0.0); n++
        }
        c.polyline(xs, ys, n, ink.palette.TEXT, ink.dp(1.5f))
    }

    /** The sound's voice: the duty pattern, the wave at its output level, or the LFSR bits at the listening note. */
    private fun drawCharacter(c: Surface, l: Float, t: Float, r: Float, b: Float, ins: GbInstrument, kind: GbBank, note: Int, col: Int) {
        val u = ink.unit
        box.set(l, t, r, b)
        ink.round(c, box, ink.dp(4f), ink.palette.LANE)
        val titleH = u * 1.3f
        val small = u * 0.75f
        inner.set(l + u * 0.5f, t + titleH + u * 0.2f, r - u * 0.5f, b - u * 0.5f)
        when (kind) {
            GbBank.PULSE -> {
                ink.label(c, ink.fit("Duty ${SoundParams.duty.format(ins, ins.duty)}, 2 cycles", r - l - u), l + u * 0.5f, t, titleH, ink.palette.FAINT, size = small)
                drawDutyCycle(c, inner, ins.duty, col, 2)
            }
            GbBank.WAVE -> {
                val shift = SoundTimeline.outputShift(ins)
                ink.label(c, ink.fit("Wave ${hex1(ins.wave)} at ${SoundParams.waveVolume.format(ins, SoundParams.waveVolume.get(ins))}", r - l - u),
                    l + u * 0.5f, t, titleH, ink.palette.FAINT, size = small)
                val samples = song.gb.waves.getOrNull(ins.wave) ?: return
                val cw = inner.width() / samples.size
                for ((k, sample) in samples.withIndex()) {
                    val x = inner.left + k * cw
                    ink.rect(c, x, inner.bottom - inner.height() * sample / 15f, x + cw - ink.dp(0.5f), inner.bottom, Palette.alpha(col, 45))
                    val out = sample shr shift
                    if (out > 0) ink.rect(c, x, inner.bottom - inner.height() * out / 15f, x + cw - ink.dp(0.5f), inner.bottom, col)
                }
            }
            GbBank.NOISE -> {
                val nr43 = SoundTimeline.noiseNr43(ins, note)
                val hz = SoundTimeline.noiseClockHz(nr43)
                val title = "${if (ins.noiseWidth == 1) "7-bit, repeats every 127" else "15-bit, repeats every 32767"} · NR43 ${hex2(nr43)} at ${GbSystem.noteName(note)}"
                ink.label(c, ink.fit(title, r - l - u), l + u * 0.5f, t, titleH, ink.palette.FAINT, size = small)
                if (hz == null) {
                    ink.label(c, ink.fit("Clock shift ${nr43 shr 4} never clocks the LFSR: silent", inner.width(), small), inner.centerX(), inner.top, inner.height(),
                        ink.palette.ERROR, size = small, align = Align.CENTER)
                } else {
                    val rateH = u * 1.1f
                    ink.mono(c, "${NumberText.fixed(hz, if (hz < 100) 1 else 0)} Hz", inner.right, inner.bottom - rateH, rateH, ink.palette.DIM, size = small, align = Align.RIGHT)
                    glyph.set(inner.left, inner.top, inner.right, inner.bottom - rateH)
                    drawBits(c, glyph, NOISE[ins.noiseWidth and 1], NOISE_BITS, col)
                }
            }
        }
    }

    /** [cycles] periods of duty [duty]'s 8-step pattern, as the APU plays it. */
    private fun drawDutyCycle(c: Surface, r: Rect, duty: Int, color: Int, cycles: Int) {
        val pattern = SoundTimeline.DUTY_STEPS[duty.coerceIn(0, 3)]
        val steps = 8 * cycles
        val sw = r.width() / steps
        var n = 0
        var prev: Boolean? = null
        for (k in 0 until steps) {
            val high = pattern[k % 8]
            val y = if (high) r.top else r.bottom
            val x = r.left + k * sw
            if (prev != high) {
                if (prev != null) { xs[n] = x; ys[n] = if (prev) r.top else r.bottom; n++ }
                xs[n] = x; ys[n] = y; n++
                prev = high
            }
        }
        xs[n] = r.right; ys[n] = if (prev == true) r.top else r.bottom; n++
        c.polyline(xs, ys, n, color, ink.dp(if (cycles > 1) 2f else 1.5f))
    }

    /** One cycle of a 4-bit wave, shifted right by [shift]. */
    private fun drawWaveLine(c: Surface, r: Rect, samples: List<Int>?, shift: Int, color: Int) {
        if (samples.isNullOrEmpty() || r.width() < 2f || r.height() < 2f) return
        val n = minOf(samples.size, xs.size)
        for (k in 0 until n) {
            xs[k] = r.left + r.width() * k / (n - 1).coerceAtLeast(1)
            ys[k] = r.bottom - r.height() * (samples[k] shr shift) / 15f
        }
        c.polyline(xs, ys, n, color, ink.dp(1.2f))
    }

    /** The first [count] noise output bits as bars: high bits full, low bits a thin baseline. */
    private fun drawBits(c: Surface, r: Rect, bits: BooleanArray, count: Int, color: Int) {
        val n = minOf(count, bits.size)
        val bw = r.width() / n
        for (k in 0 until n) {
            val x = r.left + k * bw
            if (bits[k]) ink.rect(c, x, r.top, x + maxOf(bw - ink.dp(0.5f), ink.dp(0.5f)), r.bottom, color)
            else ink.rect(c, x, r.bottom - ink.dp(1f), x + bw, r.bottom, Palette.alpha(color, 90))
        }
    }

    /** The bytes hUGEDriver's instrument record gives the channel's registers (HugeLowering's own encoding). */
    private fun drawRegisters(c: Surface, l: Float, t: Float, r: Float, h: Float, ins: GbInstrument, kind: GbBank, note: Int) {
        val size = ink.unit * 0.75f
        val text = when (kind) {
            GbBank.PULSE -> HugeLowering.dutyBytes(ins).let { b ->
                "NR10 ${hex2(b[0].toInt() and 0xFF)} (Pulse 1)  NR11 ${hex2(b[1].toInt() and 0xFF)}  NR12 ${hex2(b[2].toInt() and 0xFF)}  NR14 mask ${hex2(b[5].toInt() and 0xFF)}"
            }
            GbBank.WAVE -> HugeLowering.waveBytes(ins).let { b ->
                "NR31 ${hex2(b[0].toInt() and 0xFF)}  NR32 ${hex2(b[1].toInt() and 0xFF)}  wave ${hex1(b[2].toInt() and 0xFF)}  NR34 mask ${hex2(b[5].toInt() and 0xFF)}"
            }
            GbBank.NOISE -> HugeLowering.noiseBytes(ins).let { b ->
                val flags = b[3].toInt() and 0xFF
                "NR42 ${hex2(b[0].toInt() and 0xFF)}  NR41 ${hex2(flags and 0x3F)}  NR43 ${hex2(SoundTimeline.noiseNr43(ins, note))} at ${GbSystem.noteName(note)}  NR44 ${hex2(0x80 or (flags and 0x40))}"
            }
        }
        ink.label(c, "Registers", l, t, h, ink.palette.FAINT, size = size)
        val x = l + ink.labelWidth("Registers", size) + ink.unit * 0.6f
        // Fitted in the label face, so drawn in it: a monospaced line could outrun the fit.
        ink.label(c, ink.fit(text, r - x, size), x, t, h, ink.palette.DIM, size = size)
    }

    private fun bankName(k: GbBank) = when (k) {
        GbBank.PULSE -> "Pulse"
        GbBank.WAVE -> "Wave"
        GbBank.NOISE -> "Noise"
    }

    private fun usage(kind: GbBank, slot: Int): Int {
        val s = song
        if (s !== usageSong) {
            usage.clear()
            for (ch in 0 until s.channelCount()) {
                val k = HugeRules.channelKind(ch)
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

    private fun usageText(kind: GbBank, slot: Int): String {
        val n = usage(kind, slot)
        return if (n == 0) "not used in the arrangement yet" else "used on $n row${if (n == 1) "" else "s"}"
    }

    // ---- geometry, for touch and tests ----------------------------------------------------------------------------

    private fun rowOf(field: Int): Int {
        for (i in 0 until rowCount) if (rowFields[i] == field) return i
        return -1
    }

    /** The row of field [field] (1 name, 2+ settings) as last drawn, or null when it is scrolled out of view. */
    internal fun rowRect(field: Int): Rect? = rowOf(field).takeIf { it >= 0 }?.let { rowRects[it] }

    /** Choice or wave cell [index] of field [field], or null. */
    internal fun cellRect(field: Int, index: Int): Rect? {
        val i = rowOf(field)
        return if (i < 0 || index !in 0 until cellCounts[i]) null else cellRects[i][index]
    }

    /** The x of [fraction] (0..1) along field [field]'s slider, or null when it has none in view. */
    internal fun railX(field: Int, fraction: Float): Float? {
        val i = rowOf(field)
        if (i < 0 || railRight[i] <= railLeft[i]) return null
        return railLeft[i] + (railRight[i] - railLeft[i]) * fraction
    }

    internal fun slotRect(slot: Int): Rect = slotRects[slot - 1]

    internal fun tabRect(kind: GbBank): Rect = tabRects[kind.ordinal]

    // ---- touch: tabs, slots, rows, choices, the wave strip and sliders --------------------------------------------

    override fun down(x: Float, y: Float): Boolean {
        for (k in GbBank.entries) if (tabRects[k.ordinal].contains(x, y)) {
            st.bank = k.ordinal
            st.instField = st.instField.coerceAtMost(1 + GbSoundView.params(st).size)
            GbSoundView.publish(ctl)
            return true
        }
        for (i in 1..SLOTS) if (slotRects[i - 1].contains(x, y)) {
            st.instrument = i
            st.instField = 0
            GbSoundView.publish(ctl)
            return true
        }
        for (i in 0 until rowCount) {
            if (!rowRects[i].contains(x, y)) continue
            val field = rowFields[i]
            st.instField = field
            if (field == 1) return true
            val p = GbSoundView.params(st).getOrNull(field - 2) ?: return true
            val kind = GbSoundView.kind(st)
            when {
                p === SoundParams.wave -> {
                    drag.begin(session, "Set wave", DragTarget(p, kind, st.instrument, i))
                    setFromX(x)
                }
                p.choice -> {
                    val k = PanelWidgets.cellAt(cellRects[i], cellCounts[i], x, y)
                    if (k >= 0) write(p, kind, st.instrument, p.min + k, "Set ${p.label.lowercase()}")
                }
                railRight[i] > railLeft[i] && x >= railLeft[i] - ink.unit -> {
                    drag.begin(session, "Set ${p.label.lowercase()}", DragTarget(p, kind, st.instrument, i))
                    setFromX(x)
                }
            }
            return true
        }
        return false
    }

    /** Writes [value] of [p] into sound [slot] of [kind]; a held Listen (or Edit) hears the change. */
    private fun write(p: SoundParam, kind: GbBank, slot: Int, value: Int, label: String) {
        val clamped = value.coerceIn(p.min, p.max)
        if (session.editInstrument(kind, slot, label) { p.set(it, clamped) }) GbSoundView.heard(ctl)
    }

    private fun setFromX(x: Float) {
        val d = drag.live(session) ?: return
        val value = if (d.param === SoundParams.wave) {
            PanelWidgets.cellNearest(cellRects[d.row], cellCounts[d.row], x).coerceAtLeast(0)
        } else {
            PanelWidgets.railValue(x, railLeft[d.row], railRight[d.row], d.param.min, d.param.max)
        }
        write(d.param, d.kind, d.slot, value, "Set ${d.param.label.lowercase()}")
    }

    override fun move(x: Float, y: Float) = setFromX(x)

    override fun up(x: Float, y: Float) = finish()

    override fun cancel() = finish()

    private fun finish() {
        val d = drag.finish(session) ?: return
        // A following Wave view shows the wave the sound now plays.
        if (d.param === SoundParams.wave && GbSoundView.kind(st) == d.kind && st.instrument == d.slot) GbSoundView.publish(ctl)
    }
}
