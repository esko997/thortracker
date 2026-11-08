package org.thortracker.system.ym2612

import kotlin.math.abs
import kotlin.math.pow
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.model.NumberText
import org.thortracker.system.fm.FmEnvelopeSketch
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.fm.FmNode
import org.thortracker.system.fm.FmRouting
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.nes.NesEnvelopes
import org.thortracker.system.nes.NesInstrumentView
import org.thortracker.system.ym2612.YmInstrumentView.Page
import org.thortracker.system.ym2612.YmInstrumentView.Row
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * Mega Drive instrument editor. Left: the 64 slots (FM voices, PSG sounds marked "ᴾ", empty "·") with how often each
 * is used. Right: name, Listen channel, type, page tabs, then the page — for an FM voice the curated controls beside
 * the drawn algorithm (the eight OPN2 routings) and envelope (with SSG-EG shapes), the Expert register grid, or the
 * drum sweep; for a PSG sound an envelope's steps (the NES editor's plot) or the noise settings with the sweep. The
 * bank, tabs, grid and drawings are the shared [FmInstrumentPainter]'s.
 */
internal class YmInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val plot = EnvelopePlot(ink)

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = YmInstrumentView.sound(s, st)
        val page = YmInstrumentView.page(s, st)
        val listen = YmInstrumentView.listenChannel(st, ins)
        val col = Palette.channel(listen)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = when (ins) {
            is YmFmInstrument -> "FM · " + YmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)].name
            is YmPsgInstrument -> "PSG sound"
            null -> "empty slot"
        }
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "Mega Drive · $kind$locked  ${usageText(st.instrument)}")
        val row = YmInstrumentView.row(s, st)
        val allRows = YmInstrumentView.rows(s, st)
        rowRects.clear()
        gridCells.clear()
        plot.hide()

        val left = drawBank(c, area, top, YmSystem.SLOTS, col, row == Row.SLOT) { i ->
            s.ym.instrument(i)?.let { it.name.ifEmpty { "(unnamed)" } + if (it is YmPsgInstrument) " ᴾ" else "" }
        }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val fullW = area.right - pad - rx
        val envelopePage = page.envelope != null
        val side = fullW >= ink.unit * 50f && !envelopePage
        val rw = if (side) minOf(fullW * 0.5f, ink.unit * 30f) else fullW
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT && it != Row.STEPS }
        val gridRows = rows.count { it in YmInstrumentView.GRID }
        val plain = rows.size - gridRows
        // Rows shrink (down to 1.15 units) before the drawings below them lose their room.
        val reserve = if (side) 0f else ink.unit * (if (envelopePage) 8f else 9f)
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve - ink.unit * 0.4f) / (plain + gridRows + (if (gridRows > 0) 1 else 0) + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        val tabLabels = YmInstrumentView.pages(s, st).map { p -> tabLabel(ins, p) }
        var gridHeaderDrawn = false
        for (r in rows) {
            val sel = r == row
            if (r in YmInstrumentView.GRID && ins is YmFmInstrument) {
                if (!gridHeaderDrawn) { y = drawGridHeader(c, rx, vx, rw, y, rowH, col, { true }, { ins.isCarrier(it) }); gridHeaderDrawn = true }
                drawGridRow(c, r.label, allRows.indexOf(r), rx, vx, rw, y, rowH, sel, YmInstrumentView.operator(st), { true }) { op -> gridText(ins.operators[op], r) }
                rowRects += Rect(rx, y, rx + rw, y + rowH) to allRows.indexOf(r)
                y += rowH
                continue
            }
            val h = if (r == Row.PAGE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, Palette.CURSOR_FILL)
            when {
                r == Row.NAME -> if (ins != null) drawName(c, ins.name, rx, vx, y, h, sel)
                    else setting(c, "Empty slot", "${ctl.k(Action.EDIT)} on Slot or any setting makes a sound here", rx, vx, y, h, sel, true)
                r == Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    val name = s.system.channels.getOrNull(listen)?.name.orEmpty()
                    ink.label(c, name, vx, y, h, col, true)
                    listenNote(ins, listen)?.let { note ->
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rightEdge - nx, ink.unit * 0.85f), nx, y, h, Palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, tabLabels, st.envelope.coerceIn(0, tabLabels.size - 1))
                r == Row.TYPE -> setting(c, "Type", when (ins) {
                    is YmFmInstrument -> "FM voice   plays on FM 1-6"
                    is YmPsgInstrument -> "PSG sound   plays on PSG 1-3 and Noise"
                    null -> "empty   ${ctl.k(Action.EDIT)} makes ${if (YmInstrumentView.fmPages(s, st)) "an FM voice" else "a PSG sound"}"
                }, rx, vx, y, h, sel, ins == null)
                ins == null -> setting(c, r.label, "—", rx, vx, y, h, sel, true)
                else -> setting(c, r.label, value(ins, r), rx, vx, y, h, sel, dim(ins, r))
            }
            if (r != Row.PAGE) rowRects += Rect(rx, y, rx + rw, y + h) to allRows.indexOf(r)
            if (sel && r != Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            if (sel && r == Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.2f, y + 1, rx + rw + ink.unit * 0.2f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }

        if (envelopePage) {
            val k = page.envelope!!
            val psg = ins as? YmPsgInstrument ?: defaultYmPsg(st.instrument)
            val small = ink.unit * 0.8f
            ink.label(c, ink.fit(envelopeSummary(k), rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
            y += rowH * 0.85f
            val plotTop = y + ink.unit * 0.3f
            if (area.bottom - plotTop > ink.unit * 4f) {
                val env = psg.envelope(k)
                val text = { value: Int -> NesInstrumentView.valueText(k, value, null) }
                val title = EnvelopePlot.title(k.label, env.values, st.envStep, text, ctl.keys.label(Action.EDIT), ctl.keys.label(Action.UP))
                plot.draw(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), title, k, env, st.envStep, text,
                    ins == null, col, row == Row.STEPS, cursorColor(), ctl.editHeld)
            }
            return
        }
        val box = if (side) Rect(rightEdge + pad, top + ink.unit * 0.3f, area.right - pad, area.bottom - ink.unit * 0.4f)
            else Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f)
        if (box.height() < ink.unit * 4f || ins == null) return
        when (page) {
            Page.SOUND -> if (ins is YmFmInstrument) {
                if (box.width() > box.height() * 1.6f) {
                    val mid = box.left + box.width() * 0.5f
                    algorithm(c, Rect(box.left, box.top, mid - ink.unit * 0.25f, box.bottom), ins, col, -1)
                    drawEnvelope(c, Rect(mid + ink.unit * 0.25f, box.top, box.right, box.bottom), envelope(ins), col)
                } else {
                    val mid = box.top + box.height() * 0.5f
                    algorithm(c, Rect(box.left, box.top, box.right, mid - ink.unit * 0.25f), ins, col, -1)
                    drawEnvelope(c, Rect(box.left, mid + ink.unit * 0.25f, box.right, box.bottom), envelope(ins), col)
                }
            }
            Page.EXPERT -> if (ins is YmFmInstrument) algorithm(c, box, ins, col, YmInstrumentView.operator(st))
            else -> sweep(c, box, ins.sweep, col)
        }
    }

    /** A page tab: PSG envelopes carry their length, as on the NES. */
    private fun tabLabel(ins: YmInstrument?, p: Page): String {
        val k = p.envelope ?: return p.label
        val n = (ins as? YmPsgInstrument)?.envelope(k)?.values?.size ?: 0
        return if (n == 0) p.label else "${p.label} ($n)"
    }

    /** Why the Listen channel plays the sound differently: a fixed-rate noise ignores pitch; PSG sounds on Noise play noise. */
    private fun listenNote(ins: YmInstrument?, ch: Int): String? = when {
        ins is YmPsgInstrument && ch == YmRules.NOISE ->
            if (ins.noise and 3 == YmPsgInstrument.PITCHED) "pitched noise: PSG 3 is silent while it sounds" else "fixed-rate noise: notes do not change it"
        ins is YmPsgInstrument -> "tone (the noise settings apply on Noise)"
        else -> null
    }

    private fun envelopeSummary(k: NesEnvelopeKind): String = when (k) {
        NesEnvelopeKind.VOLUME -> "Level 0-15 per tick (2 dB steps; empty = 15). After OFF the release step on plays out"
        NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per tick. Noise: only in pitched mode"
        else -> "Sixteenths of a semitone per tick (relative: added up each tick)"
    }

    // ---- values ---------------------------------------------------------------------------------------

    private fun value(ins: YmInstrument, r: Row): String = when (ins) {
        is YmFmInstrument -> fmValue(ins, r)
        is YmPsgInstrument -> psgValue(ins, r)
    }

    private fun presetText(ins: YmInstrument): String = YmPresets.ALL.firstOrNull { it.instrument == ins }?.let { p ->
        p.name + (p.note?.let { "   drum, voiced at ${YmSystem.noteName(it)}" } ?: "")
    } ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"

    private fun fmValue(ins: YmFmInstrument, r: Row): String {
        val k = YmCurated.controls(ins)
        val lfoOff = song.ym.lfo == 0
        fun approx(exact: Boolean) = if (exact) "" else "≈ "
        val slDb = slDb(15 - k.sustain)
        return when (r) {
            Row.PRESET, Row.DRUM_PRESET -> presetText(ins)
            Row.ALGORITHM -> YmCurated.ALGORITHMS[k.algorithm].let { "${it.number} ${it.name}   ${it.diagram}" }
            Row.RATIO -> approx(k.ratioExact) + YmCurated.RATIOS[k.ratio].name + if (k.ratioExact) "" else "   (now " +
                ins.operators.joinToString(":") { YmCurated.multText(it.mul) + if (YmCurated.detuneSteps(it.dt) != 0) YmCurated.detuneText(it.dt) else "" } + ")"
            Row.BRIGHTNESS -> if (k.brightness < 0) "—   additive: no modulators" else "${k.brightness}   ${bar(k.brightness, 127)}"
            Row.LEVEL -> "${k.level}   ${bar(k.level, 127)}"
            Row.FEEDBACK -> "${k.feedback}   ${if (k.feedback == 0) "pure" else bar(k.feedback, 7)}"
            Row.ATTACK -> approx(k.envelopeExact) + "${k.attack}   ${timeText(attackMs(k.attack))} to full"
            Row.DECAY -> approx(k.envelopeExact) + "${k.decay}   ${timeText(fallMs(2 * k.decay, slDb))} to sustain"
            Row.SUSTAIN -> approx(k.envelopeExact) + when (k.sustain) {
                15 -> "15   held at full level"
                0 -> "0   percussive: decays to silence"
                else -> "${k.sustain}   at −${slDb.toInt()} dB"
            }
            Row.SUSTAIN_RATE -> approx(k.envelopeExact) + if (k.sustainRate == 0) "0   held while the key is down" else "${k.sustainRate}   keeps fading: −96 dB in ${timeText(fallMs(2 * k.sustainRate, 96f))}"
            Row.RELEASE -> approx(k.envelopeExact) + "${k.release}   ${timeText(fallMs(4 * k.release + 2, 96f))} after OFF"
            Row.TONE_DECAY -> if (k.toneDecay < 0) "—   additive: no modulators" else approx(k.envelopeExact) + "${k.toneDecay}   brightness fades in ${timeText(fallMs(2 * k.toneDecay, 96f))}"
            Row.KEY_SCALE -> approx(k.keyScaleExact) + YmCurated.KEY_SCALES[k.keyScale.coerceIn(0, 3)]
            Row.VIBRATO -> YmCurated.FMS_TEXT[k.vibrato] + if (k.vibrato > 0 && lfoOff) "   (song LFO off)" else ""
            Row.TREMOLO -> approx(k.tremoloExact) + YmCurated.AMS_TEXT[k.tremolo] + if (k.tremolo > 0 && lfoOff) "   (song LFO off)" else ""
            Row.SSG -> approx(k.ssgExact) + YmCurated.SSG_PRESETS[k.ssg].let { p ->
                p.name + listOfNotNull(
                    if (p.modulators != 0) "mod ${ssgShape(p.modulators)}" else null,
                    if (p.carriers != 0) "car ${ssgShape(p.carriers)}" else null,
                ).joinToString(" · ").let { if (it.isEmpty()) "" else "   $it" }
            }
            Row.RAW_ALGORITHM -> "${ins.algorithm}   ${YmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)].diagram}"
            Row.RAW_FEEDBACK -> "${ins.feedback}   operator 1"
            Row.AMS -> "${ins.ams}   ${YmCurated.AMS_TEXT[ins.ams.coerceIn(0, 3)]} tremolo on AM operators"
            Row.FMS -> "${ins.fms}   ${YmCurated.FMS_TEXT[ins.fms.coerceIn(0, 7)]} vibrato"
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> sweepValue(ins.sweep, r)
            else -> ""
        }
    }

    private fun psgValue(ins: YmPsgInstrument, r: Row): String {
        val kind = YmInstrumentView.envelopeKind(song, st)
        val env = kind?.let { ins.envelope(it) } ?: NesEnvelope()
        return when (r) {
            Row.PRESET -> presetText(ins)
            Row.LENGTH -> when {
                env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${ticks(env.values.size)})"
                kind == NesEnvelopeKind.VOLUME -> "empty: full volume"
                else -> "empty: not used"
            }
            Row.LOOP -> if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}"
            Row.ENV_RELEASE -> when {
                env.release >= 0 -> "at step ${env.release}: after OFF the steps from here play out"
                env.loop < 0 -> "none: OFF stops the note (a release needs a loop)"
                else -> "none: OFF stops the note"
            }
            Row.MODE -> if (ins.pitchRelative) "relative: offsets add up every tick" else "absolute: offset from the note"
            Row.NOISE_TYPE -> if (ins.noise and YmPsgInstrument.WHITE != 0) "white   hiss (snares, hats)" else "periodic   buzz (tonal)"
            Row.NOISE_RATE -> when (ins.noise and 3) {
                YmPsgInstrument.PITCHED -> "pitched   the note sets it (takes PSG 3's period)"
                else -> "${YmRules.NOISE_RATES[ins.noise and 3]}   fixed: ${NOISE_HZ[ins.noise and 3]} (notes do not change it)"
            }
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> sweepValue(ins.sweep, r)
            else -> ""
        }
    }

    private fun sweepValue(sw: YmSweep, r: Row): String = when (r) {
        Row.SWEEP_START -> if (!sw.active) "${YmRules.signed(sw.start)}   (sweep off: set a speed)" else "${YmRules.signed(sw.start)} semitones from the note"
        Row.SWEEP_END -> "${YmRules.signed(sw.end)} semitones"
        else -> if (!sw.active) "0   off" else "${sw.speed}   ${NumberText.fixed(sw.speed / 16.0, 2)} semitones per tick · ${timeText(sweepMs(sw))}"
    }

    private fun dim(ins: YmInstrument, r: Row): Boolean = when (r) {
        Row.BRIGHTNESS, Row.TONE_DECAY -> ins is YmFmInstrument && YmCurated.controls(ins).brightness < 0
        Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> !ins.sweep.active
        Row.VIBRATO, Row.TREMOLO -> song.ym.lfo == 0
        Row.NOISE_TYPE, Row.NOISE_RATE -> YmInstrumentView.listenChannel(st, ins) != YmRules.NOISE
        else -> false
    }

    /** One register field of operator [o] as the Expert grid shows it. */
    private fun gridText(o: YmOperator, r: Row): String = when (r) {
        Row.OP_DT -> "${o.dt} ${YmCurated.detuneText(o.dt)}"
        Row.OP_MUL -> "${NumberText.hex(o.mul)} ×${YmCurated.multText(o.mul)}"
        Row.OP_TL -> "${hex2(o.tl)} ${NumberText.fixed(o.tl * 0.75, 1)}dB"
        Row.OP_KS -> o.ks.toString()
        Row.OP_AR -> hex2(o.ar)
        Row.OP_AM -> if (o.am) "■ on" else "·"
        Row.OP_D1R -> hex2(o.d1r)
        Row.OP_D2R -> hex2(o.d2r)
        Row.OP_SL -> "${NumberText.hex(o.sl)} −${slDb(o.sl).toInt()}dB"
        Row.OP_RR -> NumberText.hex(o.rr)
        Row.OP_SSG -> if (o.ssgEg < 8) "·" else "${NumberText.hex(o.ssgEg)} ${ssgShape(o.ssgEg)}"
        else -> ""
    }

    // ---- drawings -------------------------------------------------------------------------------------

    /** The algorithm's routing: operators placed by depth (modulators left of what they modulate), carriers on the right. */
    private fun routing(ins: YmFmInstrument): FmRouting {
        val alg = ins.algorithm.coerceIn(0, 7)
        val layout = LAYOUTS[alg]
        val nodes = layout.mapIndexed { op, (column, row) -> FmNode(op, column, row, ins.isCarrier(op)) }
            .sortedWith(compareBy({ it.column }, { it.row }))
        val edges = YmCurated.ALGORITHMS[alg].edges.map { (a, b) -> (a - 1) to (b - 1) }
        return FmRouting(layout.maxOf { it.first } + 1, ROWS[alg], nodes, edges)
    }

    private fun algorithm(c: Surface, r: Rect, ins: YmFmInstrument, col: Int, focus: Int) {
        val a = YmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)]
        drawAlgorithm(c, r, "Algorithm ${a.number} ${a.name} ${a.diagram} · fb ${ins.feedback}", routing(ins), ins.feedback, col, focus,
            { op -> ins.operators[op].let { "×${YmCurated.multText(it.mul)}" + if (YmCurated.detuneSteps(it.dt) != 0) YmCurated.detuneText(it.dt) else "" } }, { 0 })
    }

    /** The first carrier's envelope in dB: attack, decay to SL, held while falling at D2R, release; SSG-EG when the carriers use it. */
    private fun envelope(ins: YmFmInstrument): FmEnvelopeSketch {
        val k = YmCurated.controls(ins)
        val car = ins.operators[(0..3).first { ins.isCarrier(it) }]
        val sustainDb = slDb(car.sl)
        val holdFall = if (car.d2r == 0) 0f else 96f * HOLD_MS / fallMs(2 * car.d2r, 96f)
        val ssg = if (car.ssgEg >= 8) car.ssgEg else -1
        val title = when {
            ssg >= 0 -> "Envelope · SSG-EG ${ssgShape(ssg)} ${YmCurated.SSG_EG_NAMES[ssg]?.substringAfter(' ') ?: ""} until OFF"
            car.d2r > 0 -> "Envelope · decays to −${sustainDb.toInt()} dB, keeps fading while held"
            car.sl == 0 -> "Envelope · held at full level until OFF"
            else -> "Envelope · held at −${sustainDb.toInt()} dB until OFF"
        }
        return FmEnvelopeSketch(
            title = title,
            attackMs = attackMs(car.ar),
            decayMs = fallMs(2 * car.d1r, sustainDb),
            sustainDb = sustainDb,
            holdMs = HOLD_MS,
            releaseMs = fallMs(4 * car.rr + 2, 96f),
            toneDecayMs = if (k.toneDecay >= 0) fallMs(2 * k.toneDecay, 96f) else null,
            floorDb = 96f,
            heldEndDb = (sustainDb + holdFall).coerceAtMost(96f),
            ssgEg = ssg,
        )
    }

    private fun sweep(c: Surface, r: Rect, sw: YmSweep, col: Int) {
        val root = ctl.defaultNote()
        val title = if (!sw.active) "No pitch sweep: notes play at their pitch (set a speed for a drum drop)"
            else "Pitch from ${YmSystem.noteName((root + sw.start).coerceIn(YmRules.NOTES))} to ${YmSystem.noteName((root + sw.end).coerceIn(YmRules.NOTES))} " +
                "in ${timeText(sweepMs(sw))} (note ${YmSystem.noteName(root)})"
        drawSweep(c, r, title, sw.start, sw.end, sw.speed, sw.active, (tickHz() / 2).toInt(), col)
    }

    private fun tickHz(): Double = YmSystem.ticksPerSecond(song)

    private fun ticks(n: Int) = "${(n * 1000.0 / tickHz()).toInt()} ms"

    private fun sweepMs(s: YmSweep): Float = if (s.speed == 0) 0f else (abs(s.end - s.start) * 16f / s.speed * 1000f / tickHz()).toFloat()

    // ---- touch ----------------------------------------------------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = NesEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        if (downShared(x, y, YmSystem.SLOTS, { YmInstrumentView.showPage(ctl, it) }, { YmInstrumentView.publish(ctl) })) return true
        val kind = YmInstrumentView.envelopeKind(song, st)
        if (kind != null && plot.contains(x, y) && song.ymInstrument(st.instrument) is YmPsgInstrument) {
            st.instField = YmInstrumentView.rows(song, st).indexOf(Row.STEPS)
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
        val e = (song.ymInstrument(drawSlot) as? YmPsgInstrument)?.envelope(drawKind) ?: return
        val i = plot.stepAt(x, YmInstrumentView.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        session.editYmEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { NesEnvelopes.withValue(drawKind, it, i, value) }
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
        /** How long the envelope sketch holds the key. */
        const val HOLD_MS = 500f

        /** The envelope generator's clock: the chip's sample rate (NTSC 53267 Hz) / 3. */
        const val EG_HZ = 53267f / 3f

        /** Operator positions (column, row) per algorithm, matching [YmCurated.Algorithm.edges]; carriers in the last column. */
        val LAYOUTS: List<List<Pair<Int, Float>>> = listOf(
            listOf(0 to 0f, 1 to 0f, 2 to 0f, 3 to 0f),          // 1→2→3→4
            listOf(0 to 0f, 0 to 1f, 1 to 0.5f, 2 to 0.5f),      // (1+2)→3→4
            listOf(1 to 0f, 0 to 1f, 1 to 1f, 2 to 0.5f),        // (1+(2→3))→4
            listOf(0 to 0f, 1 to 0f, 1 to 1f, 2 to 0.5f),        // ((1→2)+3)→4
            listOf(0 to 0f, 1 to 0f, 0 to 1f, 1 to 1f),          // (1→2)+(3→4)
            listOf(0 to 1f, 1 to 0f, 1 to 1f, 1 to 2f),          // 1→(2+3+4)
            listOf(0 to 0f, 1 to 0f, 1 to 1f, 1 to 2f),          // (1→2)+3+4
            listOf(0 to 0f, 0 to 1f, 0 to 2f, 0 to 3f),          // 1+2+3+4
        )
        val ROWS = listOf(1, 2, 2, 2, 2, 3, 3, 4)

        /** The fixed noise rates at the NTSC PSG clock (3579545 / 32 / divider). */
        val NOISE_HZ = listOf("6991 Hz", "3496 Hz", "1748 Hz")

        /** D1L/SL [sl] as decibels below full: 3 dB steps, 15 = 93 dB. */
        fun slDb(sl: Int): Float = if (sl >= 15) 93f else sl * 3f

        /**
         * Time to fall [db] at envelope rate [rate] (0..63, a register rate ×2 plus its own offset; 0 never): the OPN2
         * steps its 10-bit attenuation every 2^(11 − rate/4) EG clocks by 1, 1.25, 1.5 or 1.75 (rate mod 4); key
         * scaling ignored.
         */
        fun fallMs(rate: Int, db: Float): Float {
            if (rate <= 0) return Float.POSITIVE_INFINITY
            val r = rate.coerceAtMost(63)
            val perClock = (1f + 0.25f * (r % 4)) * 2f.pow(r / 4 - 11)
            return 1024f / perClock / EG_HZ * 1000f * (db / 96f)
        }

        /** Attack to full at register rate [ar] (0 never, 31 instant): about a fourteenth of the same rate's full decay. */
        fun attackMs(ar: Int): Float = when {
            ar <= 0 -> Float.POSITIVE_INFINITY
            ar >= 31 -> 0f
            else -> fallMs(2 * ar, 96f) * 0.072f
        }

        /** An SSG-EG value's outline ("\\\\", "/‾‾‾"…). */
        fun ssgShape(v: Int): String = YmCurated.SSG_EG_NAMES[v]?.substringBefore(' ') ?: "?"
    }
}
