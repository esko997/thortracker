package org.thortracker.system.ws

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.ws.WsInstrumentView.Page
import org.thortracker.system.ws.WsInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * WonderSwan instrument editor. Left: the 64 slots (noise sounds marked "ᴺ", CPU-fed samples "ˢ", DMA samples "ᴰ",
 * empty "·") with how often each is used. Right: name, Listen lane, kind, feed, preset, the kind's settings (a tone's
 * wave with a thumbnail and its CH3 sweep; a noise sound's tap mode and LFSR reset), page tabs, then the page: an
 * envelope's length / loop / release (pitch: mode) and its steps (the NES editor's plot), or the Sample page's settings
 * beside the song's samples with the selected one's waveform, its name and the Add row. The bank, tabs and setting rows
 * are the shared [FmInstrumentPainter]'s.
 */
internal class WsInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private companion object {
        /** Sample page rows drawn inside the sample box rather than as setting rows. */
        val BOX_ROWS = setOf(Row.SAMPLES, Row.SAMPLE_NAME, Row.ADD)

        /** The NES envelope kind whose plot defaults stand in for each WonderSwan envelope (the scale is always passed). */
        val PLOT_KINDS = mapOf(
            WsEnvelopeKind.VOLUME to NesEnvelopeKind.VOLUME, WsEnvelopeKind.ARPEGGIO to NesEnvelopeKind.ARPEGGIO,
            WsEnvelopeKind.PITCH to NesEnvelopeKind.PITCH, WsEnvelopeKind.WAVE to NesEnvelopeKind.DUTY,
            WsEnvelopeKind.TAP to NesEnvelopeKind.DUTY,
        )
    }

    private val plot = EnvelopePlot(ink)

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = WsInstrumentView.sound(s, st)
        val page = WsInstrumentView.page(s, st)
        val listen = WsInstrumentView.listenChannel(s, st, ins)
        val col = Palette.channel(listen)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = when (ins?.kind) {
            WsKind.TONE -> "tone sound"
            WsKind.NOISE -> "noise sound"
            WsKind.SAMPLE -> if (ins?.isDma == true) "DMA sample sound" else "sample sound"
            null -> "empty slot"
        }
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "${s.system.name} · $kind$locked  ${usageText(st.instrument)}")
        val row = WsInstrumentView.row(s, st)
        val allRows = WsInstrumentView.rows(s, st)
        rowRects.clear()
        gridCells.clear()
        plot.hide()
        sampleRect = Rect()
        addRect = Rect()

        val left = drawBank(c, area, top, WsRules.SLOTS, col, row == Row.SLOT) { i ->
            s.ws.instrument(i)?.let {
                it.name.ifEmpty { "(unnamed)" } + when (it.kind) {
                    WsKind.TONE -> ""
                    WsKind.NOISE -> " ᴺ"
                    WsKind.SAMPLE -> if (it.isDma) " ᴰ" else " ˢ"
                }
            }
        }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val rw = area.right - pad - rx
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT && it != Row.STEPS && it !in BOX_ROWS }
        // Rows shrink (down to 1.15 units) before the drawing below them loses its room.
        val reserve = ink.unit * 8f
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve) / (rows.size + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
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
                    listenNote(ins, listen)?.let { note ->
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rightEdge - nx, ink.unit * 0.85f), nx, y, h, Palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, WsInstrumentView.pages(s, st).map { tabLabel(ins, it) },
                    st.envelope.coerceIn(0, WsInstrumentView.pages(s, st).size - 1))
                r == Row.KIND -> setting(c, "Kind", when (ins?.kind) {
                    WsKind.TONE -> "Tone   its wave at the note's pitch, CH1-CH4"
                    WsKind.NOISE -> "Noise   channel 4's LFSR, CH4 only"
                    WsKind.SAMPLE -> "Sample   8-bit frames on CH2's voice" + if (s.ws.color) " or Hyper Voice" else ""
                    null -> "empty   ${ctl.k(Action.EDIT)} makes a tone sound"
                }, rx, vx, y, h, sel, ins == null)
                r == Row.WAVE -> drawWaveRow(c, ins, rx, vx, y, h, sel, col)
                ins == null -> setting(c, r.label, "—", rx, vx, y, h, sel, true)
                else -> setting(c, r.label, value(ins, r), rx, vx, y, h, sel, dim(ins, r))
            }
            if (r != Row.PAGE) rowRects += Rect(rx, y, rx + rw, y + h) to allRows.indexOf(r)
            if (sel && r != Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            if (sel && r == Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.2f, y + 1, rx + rw + ink.unit * 0.2f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }

        val k = page.envelope
        if (k == null) {
            val boxTop = y + ink.unit * 0.4f
            if (area.bottom - boxTop > ink.unit * 5f) drawSamples(c, Rect(rx, boxTop, rx + rw, area.bottom - ink.unit * 0.4f), ins, row, col)
            return
        }
        val small = ink.unit * 0.8f
        ink.label(c, ink.fit(WsInstrumentView.envelopeSummary(k, ins?.kind ?: WsKind.TONE), rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
        y += rowH * 0.85f
        val plotTop = y + ink.unit * 0.3f
        if (area.bottom - plotTop <= ink.unit * 4f) return
        val env = k.of(ins ?: defaultWsInstrument(st.instrument))
        val range = WsInstrumentView.range(s, k).let { if (it.first == it.last) it.first..it.first + 1 else it }
        val text = { value: Int -> WsInstrumentView.valueText(k, value) }
        val title = EnvelopePlot.title(k.label, env.values, st.envStep, text, ctl.keys.label(Action.EDIT), ctl.keys.label(Action.UP))
        plot.draw(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), title, PLOT_KINDS.getValue(k),
            NesEnvelope(env.values, env.loop, env.release), st.envStep, text, ins == null || listen !in s.system.channels.indices, col,
            row == Row.STEPS, cursorColor(), ctl.editHeld, range, WsEnvelope.MAX_LENGTH)
    }

    /** A page tab: envelopes carry their length, as on the NES. */
    private fun tabLabel(ins: WsInstrument?, p: Page): String {
        val k = p.envelope ?: return p.label
        val n = ins?.let { k.of(it).values.size } ?: 0
        return if (n == 0) p.label else "${p.label} ($n)"
    }

    /** Why the Listen lane plays the sound in a particular way: where it fits, CH3's sweep, the speaker and Hyper Voice. */
    private fun listenNote(ins: WsInstrument?, lane: Int): String? {
        val sound = song.ws
        return when {
            ins == null -> null
            lane != st.listenChannel -> "it cannot play on ${song.system.channels.getOrNull(st.listenChannel)?.name ?: "that lane"}"
            ins.kind == WsKind.TONE && ins.sweep != 0 && lane != WsRules.SWEEP_LANE -> "its sweep sounds on CH3 only"
            lane == WsRules.HYPER_VOICE && sound.output == WsOutput.SPEAKER -> "the song's output is the speaker: Hyper Voice is silent there"
            else -> null
        }
    }

    /** The Wave row: the wave's number and a thumbnail of its 32 steps. */
    private fun drawWaveRow(c: Surface, ins: WsInstrument?, rx: Float, vx: Float, y: Float, h: Float, sel: Boolean, col: Int) {
        val waves = song.ws.waves
        if (ins == null) { setting(c, "Wave", "—", rx, vx, y, h, sel, true); return }
        val wave = waves.getOrNull(ins.wave)
        val text = when {
            wave == null -> "${hex2(ins.wave)}   the song has no such wave"
            ins.waves.values.isNotEmpty() -> "${hex2(ins.wave)}   (the wave sequence picks per tick)"
            else -> hex2(ins.wave)
        }
        setting(c, "Wave", text, rx, vx, y, h, sel, wave == null || ins.waves.values.isNotEmpty())
        if (wave == null) return
        val tx = vx + ink.labelWidth(text, ink.unit, sel) + ink.unit
        val tw = minOf(ink.unit * 6f, rightEdge - tx)
        if (tw < ink.unit * 2f) return
        val r = Rect(tx, y + h * 0.15f, tx + tw, y + h * 0.85f)
        ink.rect(c, r, Palette.LANE)
        val bw = r.width() / wave.size
        for ((i, v) in wave.withIndex()) {
            val bh = r.height() * (v + 1) / (WsSound.WAVE_MAX + 1f)
            ink.rect(c, r.left + i * bw, r.bottom - bh, r.left + (i + 1) * bw, r.bottom, if (sel) col else Palette.alpha(col, 150))
        }
    }

    private fun value(ins: WsInstrument, r: Row): String {
        val s = song
        val kind = WsInstrumentView.envelopeKind(s, st)
        val env = kind?.of(ins) ?: WsEnvelope()
        val sample = s.ws.samples.getOrNull(ins.sample)
        return when (r) {
            Row.FEED -> if (ins.isDma) "Sound DMA   a fixed rate, the note only triggers; one DMA sample at a time"
                else "CPU   one frame per voice-timer interrupt (${WsSamples.timerRateHz(s.ws.timerReload)} Hz) on the root note"
            Row.PRESET -> WsPresets.presets(s.ws.color).getOrNull(WsInstrumentView.presetIndex(s, ins))?.name
                ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound, ${ctl.k(Action.EDIT)} lists them"
            Row.SWEEP -> if (ins.sweep == 0) "0   no sweep" else "${WsRules.signed(ins.sweep)}   added to the frequency register per step " +
                (if (ins.sweep > 0) "(rises)" else "(falls)") + ", CH3 only"
            Row.SWEEP_TIME -> "${ins.sweepTime}   a step every ${ins.sweepTime + 1}/375 s"
            Row.TAP -> "${ins.tap}   ${WsRules.TAP_LENGTHS[ins.tap.coerceIn(0, 7)]}-step sequence" +
                if (ins.tap == 0) " (white noise)" else " (buzzes at a pitch)"
            Row.LFSR_RESET -> if (ins.lfsrReset) "on: each note restarts the sequence" else "off: the sequence runs on"
            Row.LENGTH -> when {
                env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${ticks(env.values.size)})"
                kind == WsEnvelopeKind.VOLUME -> "empty: full volume"
                kind == WsEnvelopeKind.WAVE -> "empty: the sound's wave"
                kind == WsEnvelopeKind.TAP -> "empty: the sound's tap mode"
                else -> "empty: not used"
            }
            Row.LOOP -> if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}"
            Row.RELEASE -> when {
                env.release < 0 && kind == WsEnvelopeKind.VOLUME -> "none: OFF stops the note"
                env.release < 0 -> "none: holds through OFF"
                kind == WsEnvelopeKind.VOLUME -> "at step ${env.release}: OFF releases, the steps from here play out"
                ins.volume.release < 0 -> "at step ${env.release}: used only when the volume envelope has a release"
                else -> "at step ${env.release}: after OFF the steps from here play out"
            }
            Row.MODE -> if (ins.pitchRelative) "relative: offsets add up every tick" else "absolute: offset from the note"
            Row.SAMPLE -> when {
                s.ws.samples.isEmpty() -> "none: the song has no samples, Add below"
                sample == null -> "missing sample ${ins.sample + 1}"
                else -> "${hex2(ins.sample)} ${sample.name}   ${sample.frames} frames${if (sample.stereo) " stereo" else ""}, " +
                    WsSampleMenu.seconds(s, sample, ins)
            }
            Row.ROOT -> "${s.system.noteName(ins.root)}   plays one frame per interrupt (${WsSamples.timerRateHz(s.ws.timerReload)} Hz)"
            Row.RATE -> "${WsSamples.dmaRateHz(ins.rate)} bytes per second"
            Row.SAMPLE_LOOP -> when {
                !ins.loop -> "off: plays once"
                ins.isDma -> "on: the whole sample repeats until the next note or OFF"
                else -> "on: repeats from the loop start until the next note or OFF"
            }
            Row.LOOP_START -> if (sample == null) "frame ${ins.loopStart}" else "frame ${ins.loopStart} of ${sample.frames}"
            Row.BACKWARDS -> if (ins.backwards) "on: from the last byte to the first" else "off"
            else -> ""
        }
    }

    private fun dim(ins: WsInstrument, r: Row): Boolean = when (r) {
        Row.LOOP_START -> !ins.loop
        Row.SAMPLE -> ins.sample !in song.ws.samples.indices
        Row.PRESET -> WsInstrumentView.presetIndex(song, ins) < 0
        Row.SWEEP, Row.SWEEP_TIME -> ins.sweep == 0
        else -> false
    }

    private fun ticks(n: Int) = "${(n * 1000.0 / song.system.ticksPerSecond(song)).toInt()} ms"

    // ---- Sample page: the song's samples ----------------------------------------------------------------------------

    private var sampleRect = Rect()
    private var sampleRowH = 0f
    private var sampleScroll = 0
    private var addRect = Rect()
    private var nameRect = Rect()

    /**
     * The song's samples with their lengths and the data space left; the selected sample's waveform (8-bit bytes, both
     * sides of a stereo sample together, the shown sound's loop start marked when it plays it), its name and the Add row.
     */
    private fun drawSamples(c: Surface, r: Rect, ins: WsInstrument?, row: Row, col: Int) {
        val s = song
        val samples = s.ws.samples
        val small = ink.unit * 0.8f
        val titleH = ink.unit * 1.4f
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val free = WsSamples.free(s)
        ink.label(c, ink.fit("Samples · ${samples.size} of ${WsSound.MAX_SAMPLES} · ${WsSampleMenu.kb(free)} free", r.width() - ink.unit), r.left + ink.unit * 0.5f,
            r.top, titleH, if (free < 0) Palette.ERROR else Palette.DIM, size = small)

        val lineH = ink.unit * 1.35f
        addRect = Rect(r.left, r.bottom - lineH - ink.unit * 0.2f, r.right, r.bottom - ink.unit * 0.2f)
        nameRect = Rect(r.left, addRect.top - lineH, r.right, addRect.top)
        val listW = r.width() * 0.45f
        sampleRowH = ink.unit * 1.2f
        sampleRect = Rect(r.left, r.top + titleH, r.left + listW, nameRect.top - ink.unit * 0.2f)
        val waveRect = Rect(r.left + listW + ink.unit * 0.5f, r.top + titleH, r.right - ink.unit * 0.5f, nameRect.top - ink.unit * 0.3f)
        val visible = maxOf(1, (sampleRect.height() / sampleRowH).toInt())
        sampleScroll = sampleScroll.coerceIn(maxOf(0, st.dpcmSample - visible + 1), st.dpcmSample.coerceAtLeast(0))
        sampleScroll = sampleScroll.coerceIn(0, maxOf(0, samples.size - visible))
        val played = if (ins?.kind == WsKind.SAMPLE) ins.sample else -1
        if (samples.isEmpty()) ink.label(c, ink.fit("None yet: use Add below", listW - ink.unit), r.left + ink.unit * 0.5f, sampleRect.top, sampleRowH, Palette.FAINT, size = small)
        for (i in sampleScroll until minOf(samples.size, sampleScroll + visible)) {
            val sample = samples[i]
            val y = sampleRect.top + (i - sampleScroll) * sampleRowH
            val on = i == st.dpcmSample
            if (on) ink.rect(c, sampleRect.left, y, sampleRect.right, y + sampleRowH, Palette.CURSOR_FILL)
            ink.mono(c, hex2(i), r.left + ink.unit * 0.5f, y, sampleRowH, if (i == played) col else Palette.FAINT, i == played, small)
            val size = sample.frames.toString() + if (sample.stereo) " st" else ""
            ink.label(c, ink.fit(sample.name.ifEmpty { "(unnamed)" }, listW - ink.unit * 2.8f - ink.labelWidth(size, small) - ink.unit * 0.5f),
                r.left + ink.unit * 2.2f, y, sampleRowH, if (on) Palette.TEXT else Palette.DIM, size = small)
            ink.label(c, size, sampleRect.right - ink.unit * 0.4f, y, sampleRowH, Palette.FAINT, size = small, align = Align.RIGHT)
            if (on && row == Row.SAMPLES) ink.outline(c, Rect(sampleRect.left + 2, y + 1, sampleRect.right - 2, y + sampleRowH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }

        // the selected sample's bytes, min/max per column
        val sample = samples.getOrNull(st.dpcmSample)
        ink.rect(c, waveRect, Palette.BG)
        if (sample != null && waveRect.width() > 4f && waveRect.height() > 4f) {
            val data = sample.data
            val columns = maxOf(1, minOf(data.size, waveRect.width().toInt() / 2))
            val cw = waveRect.width() / columns
            val mid = waveRect.bottom - waveRect.height() * 128f / 255f
            ink.line(c, waveRect.left, mid, waveRect.right, mid, Palette.RULE, ink.dp(1f))
            for (k in 0 until columns) {
                val a = (k.toLong() * data.size / columns).toInt()
                val b = maxOf(a + 1, ((k + 1).toLong() * data.size / columns).toInt())
                var lo = 255
                var hi = 0
                for (i in a until b) { val l = data[i]; if (l < lo) lo = l; if (l > hi) hi = l }
                val y0 = waveRect.bottom - waveRect.height() * hi / 255f
                val y1 = waveRect.bottom - waveRect.height() * lo / 255f
                ink.rect(c, waveRect.left + k * cw, y0, waveRect.left + k * cw + maxOf(1f, cw - 0.5f), maxOf(y1, y0 + ink.dp(1f)), Palette.alpha(col, 200))
            }
            if (played == st.dpcmSample && ins != null && ins.loop && !ins.isDma) {
                val lx = waveRect.left + waveRect.width() * ins.loopStart / sample.frames
                ink.line(c, lx, waveRect.top, lx, waveRect.bottom, Palette.LISTEN, ink.dp(2f))
                ink.label(c, "loop", lx + ink.dp(3f), waveRect.top, ink.unit, Palette.LISTEN, size = ink.unit * 0.7f)
            }
            val shown = if (played == st.dpcmSample) ins else null
            ink.label(c, WsSampleMenu.seconds(s, sample, shown), waveRect.right - ink.dp(3f), waveRect.top, ink.unit, Palette.FAINT, size = ink.unit * 0.65f, align = Align.RIGHT)
        }

        val vx = r.left + ink.unit * 4.4f
        fun boxRow(rr: Rect, which: Row, label: String, value: String, off: Boolean) {
            val sel = row == which
            val inner = Rect(rr.left + ink.unit * 0.2f, rr.top, rr.right - ink.unit * 0.2f, rr.bottom)
            if (sel) ink.rect(c, inner, Palette.CURSOR_FILL)
            ink.label(c, label, r.left + ink.unit * 0.5f, rr.top, rr.height(), if (sel) Palette.TEXT else Palette.DIM, size = small)
            ink.label(c, ink.fit(value, r.right - vx - ink.unit * 0.5f, small), vx, rr.top, rr.height(),
                if (off) Palette.FAINT else if (sel) Palette.TEXT else Palette.DIM, sel, small)
            if (sel) ink.outline(c, inner, cursorColor(), ink.dp(2f), ink.dp(3f))
        }
        boxRow(nameRect, Row.SAMPLE_NAME, "Name", sample?.name?.ifEmpty { "(unnamed)" } ?: "—", sample == null)
        if (row == Row.SAMPLE_NAME && sample != null) {
            val before = sample.name.take(st.textPos).padEnd(st.textPos, ' ')
            val cx = vx + ink.labelWidth(before, small, true)
            val w = maxOf(ink.labelWidth(sample.name.getOrNull(st.textPos)?.toString() ?: " ", small, true), small * 0.5f)
            ink.outline(c, Rect(cx - 1, nameRect.top + 3, cx + w + 1, nameRect.bottom - 3), cursorColor(), ink.dp(2f), ink.dp(2f))
        }
        boxRow(addRect, Row.ADD, "Add", "starter kit or .wav file…", false)
    }

    // ---- touch: shared parts, drawing on the steps, the sample list ------------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = WsEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        val rows = WsInstrumentView.rows(song, st)
        if (downShared(x, y, WsRules.SLOTS, { WsInstrumentView.showPage(ctl, it) }, { WsInstrumentView.publish(ctl) })) return true
        val kind = WsInstrumentView.envelopeKind(song, st)
        if (kind != null && plot.contains(x, y)) {
            st.instField = rows.indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = kind
            drawing = session.beginGesture("Draw ${kind.label.lowercase()}")
            drawAt(x, y)
            return true
        }
        if (kind == null) {
            when {
                sampleRect.contains(x, y) && sampleRowH > 0f -> {
                    val i = sampleScroll + ((y - sampleRect.top) / sampleRowH).toInt()
                    if (i in song.ws.samples.indices) {
                        st.dpcmSample = i
                        st.instField = rows.indexOf(Row.SAMPLES)
                    }
                    return true
                }
                nameRect.contains(x, y) -> { st.instField = rows.indexOf(Row.SAMPLE_NAME); return true }
                addRect.contains(x, y) -> {
                    st.instField = rows.indexOf(Row.ADD)
                    WsSampleMenu.open(session, st.instrument)
                    return true
                }
            }
        }
        return false
    }

    /** Sets the step under [x] to the value at [y]; past the end it appends one step at a time. */
    private fun drawAt(x: Float, y: Float) {
        val ins = song.wsInstrument(drawSlot)
        val e = drawKind.of(ins ?: defaultWsInstrument(drawSlot))
        val i = plot.stepAt(x, WsEnvelopes.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        val range = WsInstrumentView.range(song, drawKind)
        session.editWsEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { WsEnvelopes.withValue(drawKind, it, i, value, range) }
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
}
