package org.thortracker.system.vsu

import org.thortracker.backend.VsuPlayerImage
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.NumberText
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.vsu.VsuInstrumentView.Page
import org.thortracker.system.vsu.VsuInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface
import kotlin.math.ln

/**
 * Virtual Boy instrument editor. Left: the 64 slots (Wave 5 sweep/modulation sounds marked ⁵) with how often each is
 * used. Right: name, Listen lane, page tabs, then the page's rows (fields the Listen lane ignores dimmed, with why),
 * beside or below a drawing: on Sound the envelope's level over one second (with the length's stop) and the wave the
 * sound plays, plus the pitch the note really gets on the Listen lane; on Sweep/Mod the frequency over half a second as
 * the hardware sweep or modulation moves it (the overflow stop marked); on Pitch sweep the player's sweep. The bank,
 * tabs and setting rows are the shared [FmInstrumentPainter]'s.
 */
internal class VsuInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val xs = FloatArray(POINTS)
    private val ys = FloatArray(POINTS)

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = VsuInstrumentView.sound(s, st)
        val page = VsuInstrumentView.page(st)
        val lane = st.listenChannel.coerceIn(0, VsuPlayerImage.CHANNELS - 1)
        val col = ink.palette.channel(lane)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = when {
            ins == null -> "empty slot"
            ins.freqMod.on -> "Wave 5 ${if (ins.freqMod.modulation) "modulation" else "sweep"}"
            else -> "wave ${ins.wave + 1}"
        }
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "VSU · $kind$locked  ${usageText(st.instrument)}")
        val row = VsuInstrumentView.row(st)
        val allRows = VsuInstrumentView.rows(st)
        rowRects.clear()
        gridCells.clear()

        val left = drawBank(c, area, top, VsuSystem.SLOTS, col, row == Row.SLOT) { i ->
            s.vsu.instrument(i)?.let { it.name.ifEmpty { "(unnamed)" } + if (it.freqMod.on) " ⁵" else "" }
        }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val fullW = area.right - pad - rx
        val side = fullW >= ink.unit * 50f
        val rw = if (side) minOf(fullW * 0.5f, ink.unit * 30f) else fullW
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT }
        val reserve = if (side) 0f else ink.unit * 8f
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve - ink.unit * 0.4f) / (rows.size + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        val tabLabels = Page.entries.map { it.label }
        for (r in rows) {
            val sel = r == row
            val h = if (r == Row.PAGE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, ink.palette.CURSOR_FILL)
            when {
                r == Row.NAME -> if (ins != null) drawName(c, ins.name, rx, vx, y, h, sel)
                    else setting(c, "Empty slot", "${ctl.k(Action.EDIT)} on Slot or any setting makes a sound here", rx, vx, y, h, sel, true)
                r == Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM)
                    val name = VsuRules.CHANNEL_NAMES[lane]
                    ink.label(c, name, vx, y, h, col, true)
                    val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                    val note = VsuInstrumentView.listenNote(ctl, lane)
                    ink.label(c, ink.fit(VsuPitch.readout(lane, note), rightEdge - nx, ink.unit * 0.85f), nx, y, h, ink.palette.PLAYHEAD, size = ink.unit * 0.85f)
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, tabLabels, st.envelope)
                ins == null -> setting(c, r.label, "—", rx, vx, y, h, sel, true)
                else -> {
                    val why = VsuInstrumentView.ignored(r, lane, ins)
                    setting(c, r.label, value(ins, r) + (why?.let { "   ($it)" } ?: ""), rx, vx, y, h, sel, why != null)
                }
            }
            if (r != Row.PAGE) rowRects += Rect(rx, y, rx + rw, y + h) to allRows.indexOf(r)
            if (sel && r != Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            if (sel && r == Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.2f, y + 1, rx + rw + ink.unit * 0.2f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }

        val box = if (side) Rect(rightEdge + pad, top + ink.unit * 0.3f, area.right - pad, area.bottom - ink.unit * 0.4f)
            else Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f)
        if (box.height() < ink.unit * 4f || ins == null) return
        when (page) {
            Page.SOUND -> {
                if (box.width() > box.height() * 1.6f) {
                    val mid = box.left + box.width() * 0.62f
                    drawLevels(c, Rect(box.left, box.top, mid - ink.unit * 0.25f, box.bottom), ins, col)
                    drawPlayedWave(c, Rect(mid + ink.unit * 0.25f, box.top, box.right, box.bottom), ins, lane, col)
                } else {
                    val mid = box.top + box.height() * 0.58f
                    drawLevels(c, Rect(box.left, box.top, box.right, mid - ink.unit * 0.25f), ins, col)
                    drawPlayedWave(c, Rect(box.left, mid + ink.unit * 0.25f, box.right, box.bottom), ins, lane, col)
                }
            }
            Page.WAVE5 -> drawFreqMod(c, box, ins, col)
            Page.PITCH -> {
                val sw = ins.sweep
                val root = VsuInstrumentView.listenNote(ctl, lane)
                val range = VsuRules.noteRange(lane)
                val title = if (!sw.active) "No pitch sweep: notes play at their pitch (set a speed to sweep)"
                    else "Pitch from ${VsuSystem.noteName((root + sw.start).coerceIn(range))} to ${VsuSystem.noteName((root + sw.end).coerceIn(range))} " +
                        "(note ${VsuSystem.noteName(root)} on ${VsuRules.CHANNEL_NAMES[lane]})"
                drawSweep(c, box, title, sw.start, sw.end, sw.speed, sw.active, VsuPlayerImage.TICK_HZ / 2, col)
            }
        }
    }

    /** A field's text: the value, then what it means. */
    private fun value(ins: VsuInstrument, r: Row): String {
        val fm = ins.freqMod
        val unit = if (fm.slowClock) VsuPlayerImage.SWEEP_SLOW_MS else VsuPlayerImage.EFFECTS_TICK_MS
        return when (r) {
            Row.PRESET, Row.FM_PRESET -> VsuPresets.ALL.firstOrNull { it.instrument == ins }?.let { p ->
                p.name + "   ${p.category.label}" + (p.note?.let { "; voiced at ${VsuSystem.noteName(it)}" } ?: "")
            } ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"
            Row.WAVE -> "${ins.wave + 1}" + (VsuWaves.name(song.vsu, ins.wave)?.let { " $it" } ?: " (drawn)")
            Row.LEVEL -> "${ins.level}   ${bar(ins.level, 15)}"
            Row.ENVELOPE -> if (ins.envelope) "on   ${VsuSystem.envelopeText(ins)}" else "off   the level holds"
            Row.DIRECTION -> if (ins.grow) "grows   toward 15" else "decays   toward 0"
            Row.INTERVAL -> "${ins.interval}   ${VsuSystem.msText((ins.interval + 1) * VsuPlayerImage.ENVELOPE_UNIT_MS)} per level step"
            Row.REPEAT -> if (ins.repeat) "on   restarts from level ${ins.level} at the end" else "off   holds at the end"
            Row.AUTO_LENGTH -> if (ins.autoLength) "on   the hardware stops the note" else "off   plays until OFF"
            Row.LENGTH -> "${ins.length}   ${VsuSystem.msText((ins.length + 1) * VsuPlayerImage.LENGTH_UNIT_MS)}"
            Row.NOISE_TAP -> "${ins.noiseTap}   ${VsuPitch.tapLength(ins.noiseTap)}-step sequence" + if (ins.noiseTap == 0) " (white)" else ""
            Row.FM_ON -> if (fm.on) "on" else "off"
            Row.FM_FUNCTION -> if (fm.modulation) "modulation   the song's table" else "sweep   F ± F>>shift"
            Row.FM_REPEAT -> if (fm.repeat) "on   the table loops" else "off   holds the last value"
            Row.FM_CLOCK -> if (fm.slowClock) "slow   7.68 ms steps" else "fast   0.96 ms steps"
            Row.FM_INTERVAL -> if (fm.interval == 0) "0   off" else "${fm.interval}   every ${NumberText.fixed(fm.interval * unit, 2)} ms"
            Row.FM_DIRECTION -> if (fm.up) "up   higher" else "down   lower"
            Row.FM_SHIFT -> "${fm.shift}   ×(1 ${if (fm.up) "+" else "−"} 1/${1 shl fm.shift}) per step"
            Row.SWEEP_START -> if (!ins.sweep.active) "${VsuRules.signed(ins.sweep.start)}   (sweep off: set a speed)" else "${VsuRules.signed(ins.sweep.start)} semitones from the note"
            Row.SWEEP_END -> "${VsuRules.signed(ins.sweep.end)} semitones"
            Row.SWEEP_SPEED -> if (!ins.sweep.active) "0   off" else "${ins.sweep.speed}   ${NumberText.fixed(ins.sweep.speed / 16.0, 2)} semitones per tick"
            else -> ""
        }
    }

    // ---- drawings -------------------------------------------------------------------------------------

    /** The envelope's level over [ENVELOPE_MS] (the hardware's steps), the length's stop marked. */
    private fun drawLevels(c: Surface, r: Rect, ins: VsuInstrument, col: Int) {
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val titleH = ink.unit * 1.4f
        val stop = if (ins.autoLength) (ins.length + 1) * VsuPlayerImage.LENGTH_UNIT_MS else Double.MAX_VALUE
        val title = "Level over 1 s: ${VsuSystem.envelopeText(ins)}" + if (ins.autoLength) " · stops at ${VsuSystem.msText(stop)}" else " · until OFF"
        ink.label(c, ink.fit(title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, ink.palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 1.6f, r.top + titleH + ink.unit * 0.2f, r.right - ink.unit * 0.6f, r.bottom - ink.unit * 0.5f)
        if (g.height() < ink.unit) return
        fun xOf(ms: Double) = g.left + g.width() * (ms / ENVELOPE_MS).toFloat()
        fun yOf(level: Int) = g.bottom - g.height() * level / 15f
        ink.line(c, g.left, g.bottom, g.right, g.bottom, ink.palette.RULE, ink.dp(1f))
        ink.label(c, "15", g.left - ink.dp(3f), g.top - ink.unit * 0.4f, ink.unit, ink.palette.DIM, size = ink.unit * 0.65f, align = Align.RIGHT)
        ink.label(c, "0", g.left - ink.dp(3f), g.bottom - ink.unit * 0.6f, ink.unit, ink.palette.DIM, size = ink.unit * 0.65f, align = Align.RIGHT)
        val frame = (ins.interval + 1) * VsuPlayerImage.ENVELOPE_UNIT_MS
        var level = ins.level
        var t = 0.0
        var n = 0
        while (n < POINTS - 2 && t < ENVELOPE_MS && t < stop) {
            val end = minOf(t + frame, ENVELOPE_MS, stop)
            xs[n] = xOf(t); ys[n] = yOf(level); n++
            xs[n] = xOf(end); ys[n] = yOf(level); n++
            t += frame
            if (!ins.envelope) continue
            // One envelope frame (Sacred Tech Scroll): step toward the limit; at the limit, Repeat reloads the start level.
            when {
                !ins.grow && level > 0 -> level--
                ins.grow && level < 15 -> level++
                ins.repeat -> level = ins.level
            }
        }
        c.polyline(xs, ys, n, col, ink.dp(2.5f))
        if (stop < ENVELOPE_MS) {
            val x = xOf(stop)
            ink.line(c, x, g.top, x, g.bottom, ink.palette.WRITE, ink.dp(1.5f))
            ink.label(c, "stop", x + ink.dp(3f), g.top, ink.unit, ink.palette.WRITE, size = ink.unit * 0.65f)
        }
    }

    /** The wave the sound plays (or the noise tap on Noise) and the pitch the Listen note really gets. */
    private fun drawPlayedWave(c: Surface, r: Rect, ins: VsuInstrument, lane: Int, col: Int) {
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val titleH = ink.unit * 1.4f
        val note = VsuInstrumentView.listenNote(ctl, lane)
        if (lane == VsuRules.NOISE_LANE) {
            ink.label(c, ink.fit("Noise tap ${ins.noiseTap}: ${VsuPitch.tapLength(ins.noiseTap)}-step sequence", r.width() - ink.unit),
                r.left + ink.unit * 0.5f, r.top, titleH, ink.palette.FAINT, size = ink.unit * 0.8f)
            ink.label(c, ink.fit(VsuPitch.readout(lane, note), r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top + titleH, titleH,
                ink.palette.DIM, size = ink.unit * 0.8f)
            return
        }
        val samples = song.vsu.waves.getOrNull(ins.wave) ?: return
        ink.label(c, ink.fit("Wave ${ins.wave + 1}" + (VsuWaves.name(song.vsu, ins.wave)?.let { " $it" } ?: "") + " (shared)", r.width() - ink.unit),
            r.left + ink.unit * 0.5f, r.top, titleH, ink.palette.FAINT, size = ink.unit * 0.8f)
        val lineH = ink.unit * 1.2f
        val g = Rect(r.left + ink.unit * 0.5f, r.top + titleH, r.right - ink.unit * 0.5f, r.bottom - lineH * 2 - ink.unit * 0.2f)
        if (g.height() > ink.unit) {
            val bw = g.width() / samples.size
            for ((i, v) in samples.withIndex()) {
                val h = g.height() * v / VsuPlayerImage.SAMPLE_MAX
                ink.rect(c, g.left + i * bw, g.bottom - h, g.left + (i + 1) * bw - 1, g.bottom, col)
            }
        }
        val small = ink.unit * 0.8f
        ink.label(c, ink.fit(VsuPitch.readout(lane, note), r.width() - ink.unit, small), r.left + ink.unit * 0.5f, r.bottom - lineH * 2, lineH, ink.palette.TEXT, size = small)
        if (note != 57) ink.label(c, ink.fit(VsuPitch.readout(lane, 57), r.width() - ink.unit, small), r.left + ink.unit * 0.5f, r.bottom - lineH, lineH, ink.palette.DIM, size = small)
    }

    /**
     * Wave 5's frequency from the Listen note (on Wave 5) over up to [FREQ_MOD_MS] (60 interval steps at most): the sweep's
     * F ± F>>shift per interval step until it passes 2047 (stopped), or the modulation table added to the written F
     * (wrapping 11 bits), once or looping.
     */
    private fun drawFreqMod(c: Surface, r: Rect, ins: VsuInstrument, col: Int) {
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val titleH = ink.unit * 1.4f
        val fm = ins.freqMod
        val note = VsuInstrumentView.listenNote(ctl, VsuRules.SWEEP_LANE)
        val f0 = VsuPitch.register(VsuRules.SWEEP_LANE, 64 * note)
        val unit = if (fm.slowClock) VsuPlayerImage.SWEEP_SLOW_MS else VsuPlayerImage.EFFECTS_TICK_MS
        val step = fm.interval * unit
        val table = song.vsu.modulation
        val window = if (fm.running) minOf(FREQ_MOD_MS, step * 60) else FREQ_MOD_MS
        var f = f0
        var stoppedAt = -1.0
        var t = 0.0
        var k = 0
        var n = 0
        fun semis(fv: Int): Double = 12.0 * ln((2048.0 - f0) / (2048.0 - fv)) / ln(2.0)
        val points = ArrayList<Pair<Double, Double>>()
        val stopsNow = !fm.modulation && fm.up && VsuPitch.sweepStops(f0, fm.shift)
        if (stopsNow) stoppedAt = 0.0
        else {
            points += 0.0 to 0.0
            if (fm.running) {
                while (t + step <= window && points.size < POINTS / 2 - 1) {
                    t += step
                    if (fm.modulation) {
                        val idx = if (k < table.size) k else if (fm.repeat) k % table.size else table.size - 1
                        f = (f0 + table[idx]) and 0x7FF
                        k++
                    } else {
                        f = if (fm.up) f + (f shr fm.shift) else f - (f shr fm.shift)
                        // The overflow check runs every 0.96 ms: a value whose next step passes 2047 barely sounds.
                        if (fm.up && VsuPitch.sweepStops(f, fm.shift)) { stoppedAt = t; break }
                    }
                    points += t to semis(f)
                }
            }
        }
        val title = when {
            stopsNow -> "${VsuSystem.noteName(note)} (F $f0) stops at once: sweeping up, F + F>>${fm.shift} passes 2047" +
                if (fm.running) "" else " (checked even with the sweep off)"
            !fm.on -> "Sweep/modulation off: Wave 5 holds ${VsuSystem.noteName(note)} (F $f0)"
            fm.interval == 0 -> "Interval 0: the unit never steps; Wave 5 holds ${VsuSystem.noteName(note)} (F $f0)"
            fm.modulation -> "Modulation from ${VsuSystem.noteName(note)} (F $f0): table ${VsuWaves.modulationName(song.vsu) ?: "drawn"}, " +
                (if (fm.repeat) "looping" else "once") + ", every ${NumberText.fixed(step, 2)} ms"
            else -> "Sweep ${if (fm.up) "up" else "down"} from ${VsuSystem.noteName(note)} (F $f0), shift ${fm.shift}, every ${NumberText.fixed(step, 2)} ms" +
                if (stoppedAt >= 0) " · stops at ${NumberText.fixed(stoppedAt, 0)} ms (F past 2047)" else ""
        }
        ink.label(c, ink.fit(title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, if (stopsNow) ink.palette.ERROR else ink.palette.FAINT, size = ink.unit * 0.8f)
        if (VsuInstrumentView.ignored(Row.FM_ON, st.listenChannel, ins) != null) {
            ink.label(c, ink.fit("Listen is on ${VsuRules.CHANNEL_NAMES[st.listenChannel]}: only Wave 5 plays this page", r.width() - ink.unit),
                r.left + ink.unit * 0.5f, r.top + titleH, titleH, ink.palette.PLAYHEAD, size = ink.unit * 0.8f)
        }
        val g = Rect(r.left + ink.unit * 2.6f, r.top + titleH * 2, r.right - ink.unit * 0.8f, r.bottom - ink.unit * 1.3f)
        if (g.height() <= ink.unit || points.isEmpty()) return
        val top = maxOf(points.maxOf { it.second }, 1.0) + 0.5
        val bottom = minOf(points.minOf { it.second }, -1.0) - 0.5
        fun yOf(sv: Double) = (g.bottom - g.height() * ((sv - bottom) / (top - bottom))).toFloat()
        fun xOf(ms: Double) = (g.left + g.width() * (ms / window)).toFloat()
        ink.line(c, g.left, yOf(0.0), g.right, yOf(0.0), ink.palette.RULE, ink.dp(1f))
        ink.label(c, "note", g.left - ink.dp(3f), yOf(0.0) - ink.unit * 0.5f, ink.unit, ink.palette.FAINT, size = ink.unit * 0.65f, align = Align.RIGHT)
        var prev = points.first()
        for (p in points.drop(1)) {
            if (n >= POINTS - 2) break
            xs[n] = xOf(prev.first); ys[n] = yOf(prev.second); n++
            xs[n] = xOf(p.first); ys[n] = yOf(prev.second); n++
            prev = p
        }
        if (n < POINTS - 1 && stoppedAt < 0) { xs[n] = g.right; ys[n] = yOf(prev.second); n++ }
        if (n < 2) { xs[0] = g.left; ys[0] = yOf(0.0); xs[1] = g.right; ys[1] = yOf(0.0); n = 2 }
        c.polyline(xs, ys, n, col, ink.dp(2.5f))
        if (stoppedAt >= 0) {
            val x = xOf(stoppedAt)
            ink.line(c, x, g.top, x, g.bottom, ink.palette.WRITE, ink.dp(1.5f))
            ink.label(c, "stop", x + ink.dp(3f), g.top, ink.unit, ink.palette.WRITE, size = ink.unit * 0.65f)
        }
        val ls = ink.unit * 0.7f
        ink.label(c, "${NumberText.fixed(top - 0.5, 1, plus = true)} st", g.left - ink.dp(3f), g.top - ink.unit * 0.3f, ink.unit, ink.palette.DIM, size = ls, align = Align.RIGHT)
        ink.label(c, "${NumberText.fixed(bottom + 0.5, 1, plus = true)} st", g.left - ink.dp(3f), g.bottom - ink.unit * 0.7f, ink.unit, ink.palette.DIM, size = ls, align = Align.RIGHT)
        ink.label(c, VsuSystem.msText(window), g.right, g.bottom + ink.dp(2f), ink.unit * 1.1f, ink.palette.FAINT, size = ls, align = Align.RIGHT)
    }

    // ---- touch ----------------------------------------------------------------------------------------

    override fun down(x: Float, y: Float): Boolean =
        downShared(x, y, VsuSystem.SLOTS, { VsuInstrumentView.showPage(ctl, it) }, { VsuInstrumentView.publish(ctl) })

    private companion object {
        const val POINTS = 130
        const val ENVELOPE_MS = 1000.0
        const val FREQ_MOD_MS = 500.0
    }
}
