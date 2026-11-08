package org.thortracker.system.sms

import kotlin.math.pow
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.model.NumberText
import org.thortracker.system.fm.FmEnvelopeSketch
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.fm.FmRouting
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.nes.NesEnvelopes
import org.thortracker.system.sms.SmsInstrumentView.Page
import org.thortracker.system.sms.SmsInstrumentView.Row
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * Master System instrument editor. Left: the 64 slots (PSG sounds, FM voices marked "ᶠᴹ", drum kits "ᴷ", empty "·")
 * with how often each is used. Right: name, Listen channel, type, page tabs, then the page: an envelope's steps (the
 * NES editor's plot), the FM voice's instrument and register grid (modulator and carrier side by side, built-in
 * instruments shown dimmed with their registers) beside its drawn routing and carrier envelope, or the drum kit.
 */
internal class SmsInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val plot = EnvelopePlot(ink)

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val layout = s.sms.layout
        val ins = SmsInstrumentView.sound(s, st)
        val page = SmsInstrumentView.page(s, st)
        val listen = SmsInstrumentView.listenChannel(s, st, ins)
        val col = Palette.channel(listen)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kindText = when (ins) {
            is SmsPsgInstrument -> "PSG sound"
            is SmsFmInstrument -> "FM · " + if (ins.isCustom) "custom voice" else OpllPatch.PATCH_NAMES[ins.patch.coerceIn(1, 15)]
            is SmsDrumKit -> "drum kit"
            null -> "empty slot"
        }
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "${layout.displayName.substringBefore(" (")} · $kindText$locked  ${usageText(st.instrument)}")
        val row = SmsInstrumentView.row(s, st)
        val allRows = SmsInstrumentView.rows(s, st)
        rowRects.clear()
        gridCells.clear()
        plot.hide()

        val left = drawBank(c, area, top, SmsSystem.SLOTS, col, row == Row.SLOT) { i ->
            s.sms.instrument(i)?.let {
                it.name.ifEmpty { "(unnamed)" } + when (it) { is SmsFmInstrument -> " ᶠᴹ"; is SmsDrumKit -> " ᴷ"; else -> "" }
            }
        }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val fullW = area.right - pad - rx
        val envelopePage = page.envelope != null
        val side = fullW >= ink.unit * 50f && page == Page.VOICE
        val rw = if (side) minOf(fullW * 0.5f, ink.unit * 30f) else fullW
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT && it != Row.STEPS }
        val reserve = if (side || page == Page.KIT) 0f else ink.unit * (if (envelopePage) 8f else 7f)
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve) / (rows.size + 1.4f)
        val rowH = fit.coerceIn(ink.unit * 1.1f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        val tabLabels = SmsInstrumentView.pages(s, st).map { p -> tabLabel(ins, p) }
        val fm = ins as? SmsFmInstrument
        val shown = fm?.let { if (it.isCustom) it.custom else OpllPatch.rom(it.patch) }
        var gridHeader = false
        for (r in rows) {
            val sel = r == row
            if (r in SmsInstrumentView.GRID && shown != null) {
                if (!gridHeader) { y = drawOperatorHeader(c, rx, vx, rw, y, rowH, col); gridHeader = true }
                drawOperatorRow(c, r, allRows.indexOf(r), rx, vx, rw, y, rowH, sel, fm?.isCustom == true, shown)
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
                    listenNote(ins, listen, layout)?.let { note ->
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rightEdge - nx, ink.unit * 0.85f), nx, y, h, Palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, tabLabels, st.envelope.coerceIn(0, tabLabels.size - 1))
                r == Row.TYPE -> setting(c, "Type", when (ins) {
                    null -> "empty   ${ctl.k(Action.EDIT)} makes ${SmsInstrumentView.typeText(SmsInstrumentView.kind(s, st))}"
                    else -> SmsInstrumentView.typeText(ins.kind).replaceFirstChar { it.uppercase() } +
                        if (layout.kinds.size > 1) "   ${ctl.k(Action.EDIT)} switches" else ""
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
            val small = ink.unit * 0.8f
            ink.label(c, ink.fit(envelopeSummary(k, ins), rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
            y += rowH * 0.85f
            val plotTop = y + ink.unit * 0.3f
            if (area.bottom - plotTop > ink.unit * 4f) {
                val env = SmsInstrumentView.envelope(s, st) ?: NesEnvelope()
                val text = { value: Int -> valueText(k, value) }
                val title = EnvelopePlot.title(pageLabel(k), env.values, st.envStep, text, ctl.keys.label(Action.EDIT), ctl.keys.label(Action.UP))
                plot.draw(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), title, k, env, st.envStep, text,
                    ins == null, col, row == Row.STEPS, cursorColor(), ctl.editHeld)
            }
            return
        }
        if (page != Page.VOICE || shown == null) return
        val voice = fm ?: return
        val box = if (side) Rect(rightEdge + pad, top + ink.unit * 0.3f, area.right - pad, area.bottom - ink.unit * 0.4f)
            else Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f)
        if (box.height() < ink.unit * 4f) return
        val routing = FmRouting.chains(listOf(listOf(0, 1)))
        val title = (if (voice.isCustom) "Custom voice" else "${OpllPatch.PATCH_NAMES[voice.patch]} (built-in, measured)") + " · fb ${shown.feedback}"
        if (box.width() > box.height() * 1.6f) {
            val mid = box.left + box.width() * 0.5f
            drawAlgorithm(c, Rect(box.left, box.top, mid - ink.unit * 0.25f, box.bottom), title, routing, shown.feedback, col, SmsInstrumentView.operator(st),
                { op -> "×" + SmsPresets.multText(operator(shown, op).mult) }, { op -> if (operator(shown, op).rectified) 1 else 0 })
            drawEnvelope(c, Rect(mid + ink.unit * 0.25f, box.top, box.right, box.bottom), envelope(shown), col)
        } else {
            val mid = box.top + box.height() * 0.5f
            drawAlgorithm(c, Rect(box.left, box.top, box.right, mid - ink.unit * 0.25f), title, routing, shown.feedback, col, SmsInstrumentView.operator(st),
                { op -> "×" + SmsPresets.multText(operator(shown, op).mult) }, { op -> if (operator(shown, op).rectified) 1 else 0 })
            drawEnvelope(c, Rect(box.left, mid + ink.unit * 0.25f, box.right, box.bottom), envelope(shown), col)
        }
    }

    private fun operator(p: OpllPatch, op: Int): OpllOperator = if (op == 0) p.modulator else p.carrier

    private fun pageLabel(k: NesEnvelopeKind): String = if (k == NesEnvelopeKind.DUTY) "Noise" else k.label

    /** A page tab: envelopes carry their length. */
    private fun tabLabel(ins: SmsInstrument?, p: Page): String {
        val k = p.envelope ?: return p.label
        val n = ins?.envelope(k)?.values?.size ?: 0
        return if (n == 0) p.label else "${p.label} ($n)"
    }

    private fun listenNote(ins: SmsInstrument?, ch: Int, layout: SmsLayout): String? = when {
        ins is SmsPsgInstrument && ch == SmsLayout.NOISE -> "noise: the note picks the fixed rate (C, C#, D-B) or Square 3's pitch"
        ins is SmsPsgInstrument -> "tone (a noise envelope sets the Noise channel's mode)"
        ins is SmsDrumKit -> "the kit's pitches apply to all five drums"
        ins is SmsFmInstrument && ins.isCustom -> "one custom voice for all FM channels"
        else -> null
    }

    private fun envelopeSummary(k: NesEnvelopeKind, ins: SmsInstrument?): String = when (k) {
        NesEnvelopeKind.VOLUME -> "Level 0-15 per tick (empty = 15). After OFF the loop runs out into the release part"
        NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per tick"
        NesEnvelopeKind.PITCH -> "1/128 semitone per tick (±½ semitone), from the note" + if ((ins as? SmsPsgInstrument)?.noise?.values?.isNotEmpty() == true) ": empty the noise envelope first" else ""
        NesEnvelopeKind.DUTY -> "Noise mode per tick: 0 periodic, 1 white, +2 pitched by Square 3" + if ((ins as? SmsPsgInstrument)?.pitch?.values?.isNotEmpty() == true) ": empty the pitch envelope first" else ""
    }

    private fun valueText(k: NesEnvelopeKind, value: Int): String = when (k) {
        NesEnvelopeKind.VOLUME -> value.toString()
        NesEnvelopeKind.ARPEGGIO -> SmsRules.signed(value)
        NesEnvelopeKind.PITCH -> SmsRules.signed((value * 100 + (if (value < 0) -64 else 64)) / 128) + "¢"
        NesEnvelopeKind.DUTY -> listOf("peri", "white", "p·sq3", "w·sq3").getOrElse(value) { value.toString() }
    }

    // ---- values ---------------------------------------------------------------------------------------

    private fun value(ins: SmsInstrument, r: Row): String {
        if (r == Row.PRESET) {
            return SmsPresets.ALL.firstOrNull { it.instrument == ins }?.let { p ->
                p.name + (p.note?.let { "   voiced at ${song.system.noteName(it)}" } ?: "")
            } ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"
        }
        val env = SmsInstrumentView.envelopeKind(song, st)?.let { ins.envelope(it) } ?: NesEnvelope()
        return when (r) {
            Row.LENGTH -> when {
                env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${(env.values.size * 1000.0 / song.system.tickHz).toInt()} ms)"
                SmsInstrumentView.envelopeKind(song, st) == NesEnvelopeKind.VOLUME -> "empty: full volume"
                else -> "empty: not used"
            }
            Row.LOOP -> if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}"
            Row.ENV_RELEASE -> when {
                env.release >= 0 -> "at step ${env.release}: after OFF the loop runs out and the steps from here play"
                env.loop < 0 -> "none: OFF stops the note (a release needs a loop)"
                else -> "none: OFF stops the note"
            }
            Row.PATCH -> (ins as? SmsFmInstrument)?.let { if (it.isCustom) "0 Custom   the chip's one custom voice" else "${it.patch} ${OpllPatch.PATCH_NAMES[it.patch]}   built-in" } ?: ""
            Row.LEVEL -> (ins as? SmsFmInstrument)?.let { f -> val p = if (f.isCustom) f.custom else OpllPatch.rom(f.patch); "${p.level}   ${NumberText.fixed(p.level * 0.75, 1)} dB on the modulator" } ?: ""
            Row.FEEDBACK -> (ins as? SmsFmInstrument)?.let { f -> val p = if (f.isCustom) f.custom else OpllPatch.rom(f.patch); "${p.feedback}   ${if (p.feedback == 0) "pure" else bar(p.feedback, 7)}" } ?: ""
            Row.KIT_MODE -> (ins as? SmsDrumKit)?.let { if (it.fixed) "fixed   the kit sets the three pitches" else "pitched by the notes   each drum note sets its pitch" } ?: ""
            Row.KIT_KICK -> (ins as? SmsDrumKit)?.let { song.system.noteName(it.kick) } ?: ""
            Row.KIT_SNARE -> (ins as? SmsDrumKit)?.let { song.system.noteName(it.snareHat) } ?: ""
            Row.KIT_TOM -> (ins as? SmsDrumKit)?.let { song.system.noteName(it.tomCymbal) } ?: ""
            else -> ""
        }
    }

    private fun dim(ins: SmsInstrument, r: Row): Boolean = when (r) {
        Row.LEVEL, Row.FEEDBACK -> (ins as? SmsFmInstrument)?.isCustom == false
        Row.KIT_KICK, Row.KIT_SNARE, Row.KIT_TOM -> (ins as? SmsDrumKit)?.fixed == false
        else -> false
    }

    // ---- voice grid -----------------------------------------------------------------------------------

    private fun colX(vx: Float, rx: Float, rw: Float, op: Int): Float = vx + op * (rx + rw - vx) / 2f

    private fun drawOperatorHeader(c: Surface, rx: Float, vx: Float, rw: Float, y: Float, h: Float, col: Int): Float {
        val size = ink.unit * 0.8f
        ink.label(c, "Register", rx, y, h, Palette.FAINT, size = size)
        ink.label(c, "Modulator", colX(vx, rx, rw, 0), y, h, Palette.DIM, true, size)
        ink.label(c, "♪ Carrier", colX(vx, rx, rw, 1), y, h, col, true, size)
        return y + h
    }

    private fun drawOperatorRow(c: Surface, r: Row, rowIndex: Int, rx: Float, vx: Float, rw: Float, y: Float, h: Float, sel: Boolean, custom: Boolean, p: OpllPatch) {
        val colW = (rx + rw - vx) / 2f
        if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, Palette.alpha(Palette.CURSOR_FILL, 160))
        ink.mono(c, r.label, rx, y, h, if (sel) Palette.TEXT else Palette.DIM, sel, ink.unit * 0.9f)
        val current = SmsInstrumentView.operator(st)
        for (op in 0 until 2) {
            val x = colX(vx, rx, rw, op)
            val text = gridText(operator(p, op), r)
            ink.mono(c, ink.fit(text, colW - ink.unit * 0.3f, ink.unit * 0.85f), x, y, h,
                if (!custom) Palette.FAINT else if (sel && op == current) Palette.TEXT else Palette.DIM, size = ink.unit * 0.85f)
            val cell = Rect(x - ink.unit * 0.2f, y + 1, x + colW - ink.unit * 0.2f, y + h - 1)
            gridCells += Triple(cell, rowIndex, op)
            if (sel && op == current) ink.outline(c, cell, cursorColor(), ink.dp(2f), ink.dp(3f))
        }
    }

    private fun gridText(o: OpllOperator, r: Row): String = when (r) {
        Row.OP_MULT -> "${NumberText.hex(o.mult)} ×${SmsPresets.multText(o.mult)}"
        Row.OP_AM -> if (o.am) "■ on" else "·"
        Row.OP_VIB -> if (o.vibrato) "■ on" else "·"
        Row.OP_EG -> if (o.sustained) "sustained" else "percussive"
        Row.OP_KSR -> if (o.ksr) "■ on" else "·"
        Row.OP_KSL -> listOf("off", "1.5dB/oct", "3dB/oct", "6dB/oct")[o.ksl.coerceIn(0, 3)]
        Row.OP_WAVE -> if (o.rectified) "half sine" else "sine"
        Row.OP_AR -> "${NumberText.hex(o.ar)} ${timeText(attackMs(o.ar))}"
        Row.OP_DR -> NumberText.hex(o.dr)
        Row.OP_SL -> "${NumberText.hex(o.sl)} −${o.sl * 3}dB"
        Row.OP_RR -> NumberText.hex(o.rr)
        else -> ""
    }

    /** The carrier's envelope: attack, decay to SL, held (sustained EG) or decaying at RR (percussive), release. */
    private fun envelope(p: OpllPatch): FmEnvelopeSketch {
        val car = p.carrier
        val sustainDb = car.sl * 3f
        val title = if (car.sustained) "Carrier · held at −${sustainDb.toInt()} dB until OFF" else "Carrier · percussive: keeps decaying at the release rate"
        return FmEnvelopeSketch(
            title = title,
            attackMs = attackMs(car.ar),
            decayMs = decayMs(car.dr, car.sl),
            sustainDb = sustainDb,
            holdMs = if (car.sustained) HOLD_MS else 0f,
            releaseMs = decayMs(car.rr, 32),
            toneDecayMs = if (p.modulator.dr > 0) decayMs(p.modulator.dr, 32) else null,
            floorDb = 48f,
        )
    }

    // ---- touch ----------------------------------------------------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = NesEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        if (downShared(x, y, SmsSystem.SLOTS, { SmsInstrumentView.showPage(ctl, it) }, { SmsInstrumentView.publish(ctl) })) return true
        val kind = SmsInstrumentView.envelopeKind(song, st)
        if (kind != null && plot.contains(x, y) && song.smsInstrument(st.instrument)?.envelope(kind) != null) {
            st.instField = SmsInstrumentView.rows(song, st).indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = kind
            drawing = session.beginGesture("Draw envelope")
            drawAt(x, y)
            return true
        }
        return false
    }

    private fun drawAt(x: Float, y: Float) {
        val e = song.smsInstrument(drawSlot)?.envelope(drawKind) ?: return
        val i = plot.stepAt(x, SmsInstrumentView.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        session.editSmsEnvelope(drawSlot, drawKind, "Draw envelope") { NesEnvelopes.withValue(drawKind, it, i, value) }
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
        const val HOLD_MS = 500f

        /** YM2413 attack to full for rate [ar] (0 never, 15 instant; each step about halves, as on the OPL). */
        fun attackMs(ar: Int): Float = when (ar) { 0 -> Float.POSITIVE_INFINITY; 15 -> 0f; else -> 2826f / 2f.pow(ar - 1) }

        /** Time to fall [steps] × 3 dB at decay/release rate [rate] (0 never; rate 1 falls 96 dB in 39 s; each step halves). */
        fun decayMs(rate: Int, steps: Int): Float = if (rate == 0) Float.POSITIVE_INFINITY else 39280f / 2f.pow(rate - 1) * (steps * 3f / 96f)
    }
}
