package org.thortracker.system.ay

import kotlin.math.abs
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.model.NumberText
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.ay.AyInstrumentView.Page
import org.thortracker.system.ay.AyInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * AY-3-8910 / YM2149 instrument editor. Left: the 64 slots (empty "·") with how often each is used. Right: name,
 * Listen channel, page tabs, then the page — the curated Sound settings with what the Listen note writes (TP, EP and
 * their frequencies) and the shared-resource rule, beside the hardware envelope's shape and the drum sweep; a software
 * envelope's steps (the NES editor's plot); or the Envelope expert page with the chip's ramp at its resolution (16 or
 * 32 steps) and all 16 R13 values. The bank, tabs, rows and sweep curve are the shared [FmInstrumentPainter]'s.
 */
internal class AyInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val plot = EnvelopePlot(ink)
    private val stairXs = FloatArray(MAX_POINTS)
    private val stairYs = FloatArray(MAX_POINTS)

    /** Shape levels by (R13, steps, ramps): drawn every frame, computed once. */
    private val levels = HashMap<Int, IntArray>()

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val variant = s.ay.variant
        val ins = AyInstrumentView.sound(s, st)
        val page = AyInstrumentView.page(st)
        val listen = st.listenChannel.coerceIn(0, s.system.channels.size - 1)
        val col = Palette.channel(listen)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = when {
            ins == null -> "empty slot"
            ins.envelope.on -> "hardware envelope ${AyInstrumentView.shapeOutline(ins.envelope.shape)}"
            else -> "software volume"
        }
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "${variant.label} (${variant.chip}) · $kind$locked  ${usageText(st.instrument)}")
        val row = AyInstrumentView.row(st)
        val allRows = AyInstrumentView.rows(st)
        rowRects.clear()
        gridCells.clear()
        plot.hide()

        val left = drawBank(c, area, top, AySystem.SLOTS, col, row == Row.SLOT) { i -> s.ay.instrument(i)?.let { it.name.ifEmpty { "(unnamed)" } } }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val fullW = area.right - pad - rx
        val envelopePage = page.envelope != null
        val side = fullW >= ink.unit * 50f && !envelopePage
        val rw = if (side) minOf(fullW * 0.5f, ink.unit * 30f) else fullW
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT && it != Row.STEPS }
        val lines = if (envelopePage) 1f else 2f
        // Rows shrink (down to 1.15 units) before the drawings below them lose their room.
        val reserve = if (side) 0f else ink.unit * (if (envelopePage) 8f else 9f)
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve - ink.unit * 0.4f) / (rows.size + lines * 0.85f + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        val tabLabels = AyInstrumentView.PAGES.map { p -> tabLabel(ins, p) }
        for (r in rows) {
            val sel = r == row
            val h = if (r == Row.PAGE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, Palette.CURSOR_FILL)
            when {
                r == Row.NAME -> if (ins != null) drawName(c, ins.name, rx, vx, y, h, sel)
                    else setting(c, "Empty slot", "${ctl.k(Action.EDIT)} on Slot or any setting makes a sound here", rx, vx, y, h, sel, true)
                r == Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    val name = s.system.channels.getOrNull(listen)?.name.orEmpty()
                    ink.label(c, name, vx, y, h, col, true)
                    val note = "at ${s.system.noteName(ctl.defaultNote())}" + (AyInstrumentView.side(variant, listen)?.let { " · heard $it" } ?: "")
                    val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                    ink.label(c, ink.fit(note, rightEdge - nx, ink.unit * 0.85f), nx, y, h, Palette.PLAYHEAD, size = ink.unit * 0.85f)
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, tabLabels, st.envelope.coerceIn(0, tabLabels.size - 1))
                ins == null -> setting(c, r.label, "—", rx, vx, y, h, sel, true)
                else -> setting(c, r.label, value(ins, r), rx, vx, y, h, sel, dim(ins, r))
            }
            if (r != Row.PAGE) rowRects += Rect(rx, y, rx + rw, y + h) to allRows.indexOf(r)
            if (sel && r != Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            if (sel && r == Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.2f, y + 1, rx + rw + ink.unit * 0.2f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }

        val small = ink.unit * 0.8f
        if (envelopePage) {
            val k = page.envelope!!
            val sound = ins ?: defaultAyInstrument(st.instrument)
            ink.label(c, ink.fit(envelopeSummary(k), rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
            y += rowH * 0.85f
            val plotTop = y + ink.unit * 0.3f
            if (area.bottom - plotTop > ink.unit * 4f) {
                val env = sound.steps(k)
                val text = { value: Int -> valueText(k, value) }
                val title = EnvelopePlot.title(k.label, env.values, st.envStep, text, ctl.keys.label(Action.EDIT), ctl.keys.label(Action.UP))
                plot.draw(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), title, k.base, env, st.envStep, text,
                    ins == null, col, row == Row.STEPS, cursorColor(), ctl.editHeld, k.range)
            }
            return
        }

        // What the Listen note writes, and the shared-resource rule.
        if (ins != null) {
            ink.label(c, ink.fit(AyInstrumentView.readingText(s, ins, ctl.defaultNote()), rw, small), rx, y, rowH * 0.85f, Palette.PLAYHEAD, size = small)
            y += rowH * 0.85f
            ink.label(c, ink.fit(SHARED_NOTE, rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
            y += rowH * 0.85f
        }
        val box = if (side) Rect(rightEdge + pad, top + ink.unit * 0.3f, area.right - pad, area.bottom - ink.unit * 0.4f)
            else Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f)
        if (box.height() < ink.unit * 4f || ins == null) return
        val env = ins.envelope
        val steps = AyInstrumentView.rampSteps(variant)
        val wide = box.width() > box.height() * 1.6f
        val gap = ink.unit * 0.25f
        when (page) {
            Page.ENVELOPE -> {
                val split = box.top + box.height() * 0.55f
                drawShape(c, Rect(box.left, box.top, box.right, split - gap), rampTitle(env, steps), env.shape, steps, RAMPS, col, env.on)
                drawR13Table(c, Rect(box.left, split + gap, box.right, box.bottom), env.shape, col)
            }
            else -> {
                val first: Rect
                val second: Rect
                if (wide) {
                    val mid = box.left + box.width() * 0.5f
                    first = Rect(box.left, box.top, mid - gap, box.bottom)
                    second = Rect(mid + gap, box.top, box.right, box.bottom)
                } else {
                    val mid = box.top + box.height() * 0.5f
                    first = Rect(box.left, box.top, box.right, mid - gap)
                    second = Rect(box.left, mid + gap, box.right, box.bottom)
                }
                drawShape(c, first, shapeTitle(env), env.shape, steps, RAMPS, col, env.on)
                sweep(c, second, ins.sweep, col)
            }
        }
    }

    /** A page tab: software envelopes carry their length, as on the NES. */
    private fun tabLabel(ins: AyInstrument?, p: Page): String {
        val k = p.envelope ?: return p.label
        val n = ins?.steps(k)?.values?.size ?: 0
        return if (n == 0) p.label else "${p.label} ($n)"
    }

    private fun envelopeSummary(k: AyEnvelopeKind): String = when (k) {
        AyEnvelopeKind.VOLUME -> "Level 0-15 per tick (empty = 15); a hardware envelope note uses the shape instead, 0 still silences. After OFF the release step on plays out"
        AyEnvelopeKind.ARPEGGIO -> "Semitones added to the note per tick"
        AyEnvelopeKind.PITCH -> "Sixteenths of a semitone per tick (relative: added up each tick)"
        AyEnvelopeKind.MIXER -> "Gates per tick: 0 neither, 1 tone, 2 noise, 3 both (empty = tone only)"
        AyEnvelopeKind.NOISE -> "Noise period per tick, 0-31 (empty = none: whatever the chip has). Shared: the latest note asking for it takes it"
    }

    private fun valueText(k: AyEnvelopeKind, value: Int): String = when (k) {
        AyEnvelopeKind.ARPEGGIO, AyEnvelopeKind.PITCH -> AyRules.signed(value)
        AyEnvelopeKind.MIXER -> MIXER_TEXT.getOrElse(value) { value.toString() }
        else -> value.toString()
    }

    // ---- values ---------------------------------------------------------------------------------------

    private fun presetText(ins: AyInstrument): String = AyPresets.ALL.firstOrNull { it.instrument == ins }?.let { p ->
        "${p.name}   ${p.category}"
    } ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"

    private fun value(ins: AyInstrument, r: Row): String {
        val env = ins.envelope
        val variant = song.ay.variant
        val kind = AyInstrumentView.envelopeKind(st)
        val e = kind?.let { ins.steps(it) } ?: NesEnvelope()
        return when (r) {
            Row.PRESET -> presetText(ins)
            Row.GATES -> {
                val m = AyEnvelopes.first(AyEnvelopeKind.MIXER, ins.mixer) ?: 1
                if (AyEnvelopes.varies(ins.mixer)) "≈ ${AyInstrumentView.gateName(m)}   the Mixer envelope changes them"
                else AyInstrumentView.gateName(m) + when (m) {
                    0 -> if (env.on) "   the bare hardware envelope (buzzer)" else "   a constant level"
                    else -> ""
                }
            }
            Row.NOISE_PERIOD -> {
                val np = AyEnvelopes.first(AyEnvelopeKind.NOISE, ins.noise)
                when {
                    AyEnvelopes.varies(ins.noise) -> "≈ $np   the Noise envelope changes it"
                    np == null -> "none   plays the chip's current noise period"
                    else -> "$np   ${AyInstrumentView.hz(AyRules.toneHz(variant, maxOf(1, np)))} noise clock"
                }
            }
            Row.SHAPE -> if (!env.on) "Off   the Volume envelope sets the level" else "${NumberText.hex(env.shape)}  ${AyInstrumentView.shapeName(env.shape)}"
            Row.PERIOD -> if (env.auto) "Auto J${AyRules.signed(env.octave)}   ${octaveText(env.octave)}"
                else "Manual ${NumberText.hex(env.period, 4)}   ${env.period}"
            Row.SYNC -> when {
                !env.sync -> "off"
                env.auto -> "on   tone period locked to the envelope's"
                else -> "on   (auto period only)"
            }
            Row.RAW_SHAPE -> if (!env.on) "Off   no R13 write: the Volume envelope sets the level"
                else "${NumberText.hex(env.shape)}  ${AyInstrumentView.shapeName(env.shape)}" + when (env.shape) {
                    9 -> "   (R13 0-3 play the same)"
                    15 -> "   (R13 4-7 play the same)"
                    else -> ""
                }
            Row.AUTO -> if (env.auto) "Auto   the period follows the note" else "Manual   a fixed period"
            Row.OCTAVE -> "J${AyRules.signed(env.octave)}   ${octaveText(env.octave)}" + if (env.auto) "" else "   (kept for auto)"
            Row.RAW_PERIOD -> "${NumberText.hex(env.period, 4)}   R12 ${hex2(env.period shr 8)} R11 ${hex2(env.period and 255)}" +
                if (env.auto) "   (kept for manual)" else "   = ${env.period}"
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> sweepValue(ins.sweep, r)
            Row.LENGTH -> when {
                e.values.isNotEmpty() -> "${e.values.size} step${if (e.values.size == 1) "" else "s"} (${ticks(e.values.size)})"
                kind == AyEnvelopeKind.VOLUME -> "empty: full volume"
                kind == AyEnvelopeKind.MIXER -> "empty: tone only"
                kind == AyEnvelopeKind.NOISE -> "empty: no noise period asked for"
                else -> "empty: not used"
            }
            Row.LOOP -> if (e.loop < 0) "off: the last value holds" else "from step ${e.loop}"
            Row.ENV_RELEASE -> when {
                e.release >= 0 -> "at step ${e.release}: after OFF the steps from here play out"
                e.loop < 0 -> "none: OFF stops the note (a release needs a loop)"
                else -> "none: OFF stops the note"
            }
            Row.MODE -> if (ins.pitchRelative) "relative: offsets add up every tick" else "absolute: offset from the note"
            Row.SLOT, Row.NAME, Row.LISTEN, Row.PAGE, Row.STEPS -> ""
        }
    }

    private fun octaveText(j: Int): String = when {
        j == 0 -> "the note's own pitch"
        j > 0 -> "$j octave${if (j == 1) "" else "s"} up"
        else -> "${-j} octave${if (j == -1) "" else "s"} down"
    }

    private fun sweepValue(sw: AySweep, r: Row): String = when (r) {
        Row.SWEEP_START -> if (!sw.active) "${AyRules.signed(sw.start)}   (sweep off: set a speed)" else "${AyRules.signed(sw.start)} semitones from the note"
        Row.SWEEP_END -> "${AyRules.signed(sw.end)} semitones"
        else -> if (!sw.active) "0   off" else "${sw.speed}   ${NumberText.fixed(sw.speed / 16.0, 2)} semitones per tick · ${timeText(sweepMs(sw))}"
    }

    private fun dim(ins: AyInstrument, r: Row): Boolean {
        val env = ins.envelope
        return when (r) {
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> !ins.sweep.active
            Row.NOISE_PERIOD -> (ins.mixer.values.ifEmpty { listOf(1) }).none { it and 2 != 0 }
            Row.PERIOD, Row.AUTO -> !env.on
            Row.SYNC -> !env.on || !env.auto
            Row.OCTAVE -> !env.on || !env.auto
            Row.RAW_PERIOD -> !env.on || env.auto
            else -> false
        }
    }

    // ---- drawings -------------------------------------------------------------------------------------

    private fun shapeTitle(env: AyEnvelopeSettings): String = if (!env.on) "No hardware envelope: the Volume envelope sets the level"
        else "Hardware envelope ${AyInstrumentView.shapeName(env.shape)} · " +
            (if (env.auto) "auto J${AyRules.signed(env.octave)}" else "manual ${NumberText.hex(env.period, 4)}") +
            if (env.auto && env.sync) " · tone synced" else ""

    private fun rampTitle(env: AyEnvelopeSettings, steps: Int): String = "${song.ay.variant.chip}: $steps levels per ramp" +
        if (env.on) " · R13 ${NumberText.hex(env.shape)} ${AyInstrumentView.shapeName(env.shape)}" else " · envelope off"

    private fun levelsOf(r13: Int, steps: Int, ramps: Int): IntArray =
        levels.getOrPut((r13 shl 16) or (steps shl 8) or ramps) { AyInstrumentView.shapeLevels(r13, steps, ramps) }

    /** [r13]'s levels as a staircase over [ramps] ramps of [steps] levels in [r]; ramp boundaries ruled. */
    private fun drawShape(c: Surface, r: Rect, title: String, r13: Int, steps: Int, ramps: Int, col: Int, on: Boolean) {
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val titleH = ink.unit * 1.4f
        ink.label(c, ink.fit(title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, Palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 1.8f, r.top + titleH, r.right - ink.unit * 0.8f, r.bottom - ink.unit * 0.6f)
        if (g.height() <= ink.unit || !on) return
        val ls = ink.unit * 0.7f
        ink.line(c, g.left, g.bottom, g.right, g.bottom, Palette.RULE, ink.dp(1f))
        ink.line(c, g.left, g.top, g.right, g.top, Palette.RULE, ink.dp(1f))
        ink.mono(c, (steps - 1).toString(), g.left - ink.dp(3f), g.top - ls, ls * 2, Palette.FAINT, size = ls, align = Align.RIGHT)
        ink.mono(c, "0", g.left - ink.dp(3f), g.bottom - ls, ls * 2, Palette.FAINT, size = ls, align = Align.RIGHT)
        for (k in 1 until ramps) {
            val x = g.left + g.width() * k / ramps
            ink.line(c, x, g.top, x, g.bottom, Palette.RULE, ink.dp(1f))
        }
        stairs(c, g, levelsOf(r13, steps, ramps), steps, col, ink.dp(2f))
    }

    /** A staircase of [lv] (0..[steps] − 1) across [g]. */
    private fun stairs(c: Surface, g: Rect, lv: IntArray, steps: Int, col: Int, width: Float) {
        val n = minOf(lv.size, MAX_POINTS / 2)
        val cell = g.width() / n
        for (i in 0 until n) {
            val y = g.bottom - g.height() * lv[i] / (steps - 1)
            stairXs[2 * i] = g.left + i * cell
            stairYs[2 * i] = y
            stairXs[2 * i + 1] = g.left + (i + 1) * cell
            stairYs[2 * i + 1] = y
        }
        c.polyline(stairXs, stairYs, 2 * n, col, width)
    }

    /** All 16 R13 values, each with its shape; the 8 aliases (0-7) faint and marked with what they play as. */
    private fun drawR13Table(c: Surface, r: Rect, shape: Int, col: Int) {
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val titleH = ink.unit * 1.4f
        ink.label(c, ink.fit("R13: all 16 values (0-3 play as 9, 4-7 as F)", r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH,
            Palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 0.4f, r.top + titleH, r.right - ink.unit * 0.4f, r.bottom - ink.unit * 0.3f)
        val columns = if (g.width() > g.height() * 3f) 8 else 4
        val rowsN = 16 / columns
        val cw = g.width() / columns
        val ch = g.height() / rowsN
        if (cw < ink.unit * 2f || ch < ink.unit * 1.2f) return
        val ls = ink.unit * 0.7f
        for (v in 0 until 16) {
            val cell = Rect(g.left + (v % columns) * cw, g.top + (v / columns) * ch, g.left + (v % columns + 1) * cw, g.top + (v / columns + 1) * ch)
            val alias = v < 8
            val current = shape != 0 && v == shape
            if (current) ink.rect(c, cell, Palette.mix(Palette.BG, col, 0.18f))
            val label = NumberText.hex(v) + if (alias) "=${NumberText.hex(AyInstrumentView.canonicalShape(v))}" else ""
            ink.mono(c, label, cell.left + ink.unit * 0.2f, cell.top, ls * 1.6f, if (alias) Palette.FAINT else if (current) col else Palette.DIM, current, ls)
            val sg = Rect(cell.left + ink.unit * 0.3f, cell.top + ls * 1.7f, cell.right - ink.unit * 0.3f, cell.bottom - ink.unit * 0.25f)
            if (sg.height() > ink.dp(4f) && sg.width() > ink.dp(8f)) {
                stairs(c, sg, levelsOf(v, TABLE_STEPS, TABLE_RAMPS), TABLE_STEPS, if (alias) Palette.FAINT else if (current) col else Palette.DIM, ink.dp(1.2f))
            }
            if (current) ink.outline(c, cell, col, ink.dp(2f), ink.dp(3f))
        }
    }

    private fun sweep(c: Surface, r: Rect, sw: AySweep, col: Int) {
        val root = ctl.defaultNote()
        val range = song.system.noteRange
        val title = if (!sw.active) "No pitch sweep: notes play at their pitch (set a speed for a drum drop)"
            else "Pitch from ${song.system.noteName((root + sw.start).coerceIn(range))} to ${song.system.noteName((root + sw.end).coerceIn(range))} " +
                "in ${timeText(sweepMs(sw))} (note ${song.system.noteName(root)})"
        drawSweep(c, r, title, sw.start, sw.end, sw.speed, sw.active, (tickHz() / 2).toInt(), col)
    }

    private fun tickHz(): Double = song.system.ticksPerSecond(song)

    private fun ticks(n: Int) = "${(n * 1000.0 / tickHz()).toInt()} ms"

    private fun sweepMs(s: AySweep): Float = if (s.speed == 0) 0f else (abs(s.end - s.start) * 16f / s.speed * 1000f / tickHz()).toFloat()

    // ---- touch ----------------------------------------------------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = AyEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        if (downShared(x, y, AySystem.SLOTS, { AyInstrumentView.showPage(ctl, it) }, { AyInstrumentView.publish(ctl) })) return true
        val kind = AyInstrumentView.envelopeKind(st)
        if (kind != null && plot.contains(x, y) && song.ayInstrument(st.instrument) != null) {
            st.instField = AyInstrumentView.rows(st).indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = kind
            drawing = session.beginGesture("Draw ${kind.label.lowercase()}")
            drawAt(x, y)
            return true
        }
        return false
    }

    /** Sets the step under [x] to the value at [y]; past the end it appends one step at a time. */
    private fun drawAt(x: Float, y: Float) {
        val e = song.ayInstrument(drawSlot)?.steps(drawKind) ?: return
        val i = plot.stepAt(x, AyInstrumentView.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        session.editAyEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { AyEnvelopes.withValue(drawKind, it, i, value) }
    }

    override fun move(x: Float, y: Float) {
        val g = drawing ?: return
        if (!session.isGestureOpen(g)) { drawing = null; return }
        drawAt(x, y)
    }

    override fun up(x: Float, y: Float) = finish()
    override fun cancel() = finish()

    private fun finish() {
        drawing?.let { session.endGesture(it) }
        drawing = null
    }

    private companion object {
        /** Ramps the Sound and Envelope pages draw. */
        const val RAMPS = 4

        /** Stair points: two per level, 32 levels × [RAMPS] ramps at most. */
        const val MAX_POINTS = 2 * 32 * RAMPS

        /** The R13 table's small shapes: 16 levels, three ramps. */
        const val TABLE_STEPS = 16
        const val TABLE_RAMPS = 3

        val MIXER_TEXT = listOf("—", "T", "N", "TN")

        const val SHARED_NOTE = "Noise period and hardware envelope are one each for Channels A-C: the latest note asking for one takes it"
    }
}
