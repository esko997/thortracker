package org.thortracker.system.cbm

import org.thortracker.backend.CbmImage
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.system.cbm.CbmInstrumentView.Page
import org.thortracker.system.cbm.CbmInstrumentView.Row
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * VIC-20 / C16-Plus/4 instrument editor. Left: the 64 slots (sounds owning the master volume marked "ᵛ", noise sounds
 * "ᴺ", sample sounds "ˢ", empty "·") with how often each is used. Right: name, Listen lane, (TED) kind and wave,
 * preset, page tabs, then the page: an envelope's length / loop / release (pitch: mode) and its steps (the NES
 * editor's plot), or the Sample page's settings beside the song's samples with the selected one's waveform, its name
 * and the Add row. The bank, tabs and setting rows are the shared [FmInstrumentPainter]'s.
 */
internal class CbmInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private companion object {
        /** Sample page rows drawn inside the sample box rather than as setting rows. */
        val BOX_ROWS = setOf(Row.SAMPLES, Row.SAMPLE_NAME, Row.ADD)

        /** The NES envelope kind whose plot defaults stand in for each envelope (the scale is always passed). */
        val PLOT_KINDS = mapOf(
            CbmEnvelopeKind.VOLUME to NesEnvelopeKind.VOLUME, CbmEnvelopeKind.ARPEGGIO to NesEnvelopeKind.ARPEGGIO,
            CbmEnvelopeKind.PITCH to NesEnvelopeKind.PITCH, CbmEnvelopeKind.WAVE to NesEnvelopeKind.DUTY,
        )
    }

    private val plot = EnvelopePlot(ink)

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val chip = s.cbm.chip
        val ins = CbmInstrumentView.sound(s, st)
        val page = CbmInstrumentView.page(s, st)
        val listen = CbmInstrumentView.listenChannel(s, st, ins)
        val col = ink.palette.channel(listen)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = when {
            ins == null -> "empty slot"
            ins.kind == CbmKind.SAMPLE -> "sample sound"
            !ins.volume.isEmpty -> "master volume sound"
            else -> "tone sound"
        }
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "${s.system.name} · $kind$locked  ${usageText(st.instrument)}")
        val row = CbmInstrumentView.row(s, st)
        val allRows = CbmInstrumentView.rows(s, st)
        rowRects.clear()
        gridCells.clear()
        plot.hide()
        sampleRect = Rect()
        addRect = Rect()

        val left = drawBank(c, area, top, CbmSystem.SLOTS, col, row == Row.SLOT) { i ->
            s.cbm.instrument(i)?.let {
                it.name.ifEmpty { "(unnamed)" } + when {
                    it.kind == CbmKind.SAMPLE -> " ˢ"
                    !it.volume.isEmpty -> " ᵛ"
                    chip == CbmChip.TED && it.playsNoise -> " ᴺ"
                    else -> ""
                }
            }
        }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val rw = area.right - pad - rx
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT && it != Row.STEPS && it !in BOX_ROWS }
        val reserve = ink.unit * 8f
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve) / (rows.size + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        for (r in rows) {
            val sel = r == row
            val h = if (r == Row.PAGE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, ink.palette.CURSOR_FILL)
            when {
                r == Row.NAME -> if (ins != null) drawName(c, ins.name, rx, vx, y, h, sel)
                    else setting(c, "Empty slot", "${ctl.k(Action.EDIT)} on Slot or any setting makes a sound here", rx, vx, y, h, sel, true)
                r == Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM)
                    val name = s.system.channels.getOrNull(listen)?.name.orEmpty()
                    ink.label(c, name, vx, y, h, col, true)
                    listenNote(ins, listen)?.let { note ->
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rightEdge - nx, ink.unit * 0.85f), nx, y, h, ink.palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, CbmInstrumentView.pages(s, st).map { tabLabel(ins, it) },
                    st.envelope.coerceIn(0, CbmInstrumentView.pages(s, st).size - 1))
                r == Row.KIND -> setting(c, "Kind", when (ins?.kind) {
                    CbmKind.TONE -> "Tone   a square (or Voice 2's noise) on the voices"
                    CbmKind.SAMPLE -> "Sample   the D/A at ${CbmSampleMenu.rateHz(s)} Hz on the root note, DAC lane"
                    null -> "empty   ${ctl.k(Action.EDIT)} makes a tone sound"
                }, rx, vx, y, h, sel, ins == null)
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
        ink.label(c, ink.fit(CbmInstrumentView.envelopeSummary(k, chip), rw, small), rx, y, rowH * 0.85f, ink.palette.FAINT, size = small)
        y += rowH * 0.85f
        val plotTop = y + ink.unit * 0.3f
        if (area.bottom - plotTop <= ink.unit * 4f) return
        val env = k.of(ins ?: defaultCbmInstrument(st.instrument))
        val range = CbmInstrumentView.range(s, k)
        val text = { value: Int -> CbmInstrumentView.valueText(k, value) }
        val title = EnvelopePlot.title(k.label, env.values, st.envStep, text, ctl.keys.label(Action.EDIT), ctl.keys.label(Action.UP))
        plot.draw(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), title, PLOT_KINDS.getValue(k),
            NesEnvelope(env.values, env.loop, env.release), st.envStep, text, ins == null || ignored(k, listen), col, row == Row.STEPS,
            cursorColor(), ctl.editHeld, range, CbmEnvelope.MAX_LENGTH)
    }

    /** A page tab: envelopes carry their length, as on the NES. */
    private fun tabLabel(ins: CbmInstrument?, p: Page): String {
        val k = p.envelope ?: return p.label
        val n = ins?.let { k.of(it).values.size } ?: 0
        return if (n == 0) p.label else "${p.label} ($n)"
    }

    /** True when the Listen lane plays nothing of envelope [k]: pitch on the VIC Noise lane. */
    private fun ignored(k: CbmEnvelopeKind, listen: Int): Boolean =
        k == CbmEnvelopeKind.PITCH && CbmRules.isVicNoise(song.cbm.chip, listen) || listen !in song.system.channels.indices

    /** Why the Listen lane plays the sound in a particular way. */
    private fun listenNote(ins: CbmInstrument?, ch: Int): String? {
        val chip = song.cbm.chip
        val range = CbmRules.laneRange(chip, song.cbm.video, ch)
        val sys = song.system
        return when {
            ins == null -> null
            CbmRules.isVicNoise(chip, ch) -> "noise: the note picks the rate"
            ins.kind == CbmKind.SAMPLE -> "samples play on the DAC lane, taking both voices"
            chip == CbmChip.TED && ins.playsNoise -> "noise plays on Voice 2 only"
            !ins.volume.isEmpty -> "its volume envelope shapes every voice"
            else -> "plays ${sys.noteName(range.first)}..${sys.noteName(range.last)}"
        }
    }

    private fun value(ins: CbmInstrument, r: Row): String {
        val s = song
        val chip = s.cbm.chip
        val kind = CbmInstrumentView.envelopeKind(s, st)
        val env = kind?.of(ins) ?: CbmEnvelope()
        val sample = s.cbm.samples.getOrNull(ins.sample)
        return when (r) {
            Row.WAVE -> CbmInstrumentView.waveText(chip, ins.wave) + if (!ins.waves.isEmpty) "   (the wave envelope picks per tick)" else ""
            Row.PRESET -> CbmPresets.forChip(chip).getOrNull(CbmInstrumentView.presetIndex(s, ins))?.name
                ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound, ${ctl.k(Action.EDIT)} lists them"
            Row.LENGTH -> when {
                env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${ticks(env.values.size)})"
                kind == CbmEnvelopeKind.VOLUME -> "empty: the song's master volume (no owner)"
                kind == CbmEnvelopeKind.WAVE -> "empty: the sound's wave"
                else -> "empty: not used"
            }
            Row.LOOP -> if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}"
            Row.RELEASE -> when {
                env.release >= 0 && (kind == CbmEnvelopeKind.VOLUME || kind == CbmEnvelopeKind.WAVE) -> "at step ${env.release}: OFF releases, the steps from here play out"
                env.release >= 0 && !ins.releases -> "at step ${env.release}: used only when the volume or wave envelope has a release"
                env.release >= 0 -> "at step ${env.release}: after OFF the steps from here play out"
                ins.releases -> "none: holds through OFF"
                else -> "none: OFF stops the note"
            }
            Row.MODE -> if (ins.pitchRelative) "relative: offsets add up every tick" else "absolute: offset from the note"
            Row.SAMPLE -> when {
                s.cbm.samples.isEmpty() -> "none: the song has no samples, Add below"
                sample == null -> "missing sample ${ins.sample + 1}"
                else -> "${hex2(ins.sample)} ${sample.name}   ${sample.data.size} frames, ${CbmSampleMenu.seconds(s, sample.data.size)}"
            }
            Row.ROOT -> "${s.system.noteName(ins.root)}   plays one frame per interrupt (${CbmSampleMenu.rateHz(s)} Hz)"
            Row.SAMPLE_LOOP -> if (ins.loop) "on: repeats from the loop start until the next note or OFF" else "off: plays once"
            Row.LOOP_START -> if (sample == null) "frame ${ins.loopStart}" else "frame ${ins.loopStart} of ${sample.data.size}"
            else -> ""
        }
    }

    private fun dim(ins: CbmInstrument, r: Row): Boolean = when (r) {
        Row.LOOP_START -> !ins.loop
        Row.SAMPLE -> ins.sample !in song.cbm.samples.indices
        Row.PRESET -> CbmInstrumentView.presetIndex(song, ins) < 0
        Row.WAVE -> !ins.waves.isEmpty
        else -> false
    }

    private fun ticks(n: Int) = "${(n * 1000.0 / song.system.ticksPerSecond(song)).toInt()} ms"

    // ---- Sample page: the song's samples ----------------------------------------------------------------------------

    private var sampleRect = Rect()
    private var sampleRowH = 0f
    private var sampleScroll = 0
    private var addRect = Rect()
    private var nameRect = Rect()

    /** The song's samples with their lengths and the data space left; the selected sample's frames, name and Add row. */
    private fun drawSamples(c: Surface, r: Rect, ins: CbmInstrument?, row: Row, col: Int) {
        val s = song
        val samples = s.cbm.samples
        val small = ink.unit * 0.8f
        val titleH = ink.unit * 1.4f
        val max = CbmImage.DAC_MAX
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val free = CbmSamples.free(s)
        ink.label(c, ink.fit("Samples · ${samples.size} of ${CbmSound.MAX_SAMPLES} · ${CbmSampleMenu.kb(free)} free", r.width() - ink.unit), r.left + ink.unit * 0.5f,
            r.top, titleH, if (free < 0) ink.palette.ERROR else ink.palette.DIM, size = small)

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
        val played = if (ins?.kind == CbmKind.SAMPLE) ins.sample else -1
        if (samples.isEmpty()) ink.label(c, ink.fit("None yet: use Add below", listW - ink.unit), r.left + ink.unit * 0.5f, sampleRect.top, sampleRowH, ink.palette.FAINT, size = small)
        for (i in sampleScroll until minOf(samples.size, sampleScroll + visible)) {
            val sample = samples[i]
            val y = sampleRect.top + (i - sampleScroll) * sampleRowH
            val on = i == st.dpcmSample
            if (on) ink.rect(c, sampleRect.left, y, sampleRect.right, y + sampleRowH, ink.palette.CURSOR_FILL)
            ink.mono(c, hex2(i), r.left + ink.unit * 0.5f, y, sampleRowH, if (i == played) col else ink.palette.FAINT, i == played, small)
            val size = sample.data.size.toString()
            ink.label(c, ink.fit(sample.name.ifEmpty { "(unnamed)" }, listW - ink.unit * 2.8f - ink.labelWidth(size, small) - ink.unit * 0.5f),
                r.left + ink.unit * 2.2f, y, sampleRowH, if (on) ink.palette.TEXT else ink.palette.DIM, size = small)
            ink.label(c, size, sampleRect.right - ink.unit * 0.4f, y, sampleRowH, ink.palette.FAINT, size = small, align = Align.RIGHT)
            if (on && row == Row.SAMPLES) ink.outline(c, Rect(sampleRect.left + 2, y + 1, sampleRect.right - 2, y + sampleRowH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }

        // the selected sample's frames, min/max per column
        val sample = samples.getOrNull(st.dpcmSample)
        ink.rect(c, waveRect, ink.palette.BG)
        if (sample != null && waveRect.width() > 4f && waveRect.height() > 4f) {
            val data = sample.data
            val columns = maxOf(1, minOf(data.size, waveRect.width().toInt() / 2))
            val cw = waveRect.width() / columns
            val mid = waveRect.bottom - waveRect.height() * (max / 2) / max
            ink.line(c, waveRect.left, mid, waveRect.right, mid, ink.palette.RULE, ink.dp(1f))
            for (k in 0 until columns) {
                val a = (k.toLong() * data.size / columns).toInt()
                val b = maxOf(a + 1, ((k + 1).toLong() * data.size / columns).toInt())
                var lo = max
                var hi = 0
                for (i in a until b) { val l = data[i]; if (l < lo) lo = l; if (l > hi) hi = l }
                val y0 = waveRect.bottom - waveRect.height() * hi / max
                val y1 = waveRect.bottom - waveRect.height() * lo / max
                ink.rect(c, waveRect.left + k * cw, y0, waveRect.left + k * cw + maxOf(1f, cw - 0.5f), maxOf(y1, y0 + ink.dp(1f)), Palette.alpha(col, 200))
            }
            if (played == st.dpcmSample && ins != null && ins.loop) {
                val lx = waveRect.left + waveRect.width() * ins.loopStart / data.size
                ink.line(c, lx, waveRect.top, lx, waveRect.bottom, ink.palette.LISTEN, ink.dp(2f))
                ink.label(c, "loop", lx + ink.dp(3f), waveRect.top, ink.unit, ink.palette.LISTEN, size = ink.unit * 0.7f)
            }
            ink.label(c, CbmSampleMenu.seconds(s, data.size), waveRect.right - ink.dp(3f), waveRect.top, ink.unit, ink.palette.FAINT, size = ink.unit * 0.65f, align = Align.RIGHT)
        }

        val vx = r.left + ink.unit * 4.4f
        fun boxRow(rr: Rect, which: Row, label: String, value: String, off: Boolean) {
            val sel = row == which
            val inner = Rect(rr.left + ink.unit * 0.2f, rr.top, rr.right - ink.unit * 0.2f, rr.bottom)
            if (sel) ink.rect(c, inner, ink.palette.CURSOR_FILL)
            ink.label(c, label, r.left + ink.unit * 0.5f, rr.top, rr.height(), if (sel) ink.palette.TEXT else ink.palette.DIM, size = small)
            ink.label(c, ink.fit(value, r.right - vx - ink.unit * 0.5f, small), vx, rr.top, rr.height(),
                if (off) ink.palette.FAINT else if (sel) ink.palette.TEXT else ink.palette.DIM, sel, small)
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
    private var drawKind = CbmEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        val rows = CbmInstrumentView.rows(song, st)
        if (downShared(x, y, CbmSystem.SLOTS, { CbmInstrumentView.showPage(ctl, it) }, { CbmInstrumentView.publish(ctl) })) return true
        val kind = CbmInstrumentView.envelopeKind(song, st)
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
                    if (i in song.cbm.samples.indices) {
                        st.dpcmSample = i
                        st.instField = rows.indexOf(Row.SAMPLES)
                    }
                    return true
                }
                nameRect.contains(x, y) -> { st.instField = rows.indexOf(Row.SAMPLE_NAME); return true }
                addRect.contains(x, y) -> {
                    st.instField = rows.indexOf(Row.ADD)
                    CbmSampleMenu.open(session, st.instrument)
                    return true
                }
            }
        }
        return false
    }

    /** Sets the step under [x] to the value at [y]; past the end it appends one step at a time. */
    private fun drawAt(x: Float, y: Float) {
        val ins = song.cbmInstrument(drawSlot)
        val e = drawKind.of(ins ?: defaultCbmInstrument(drawSlot))
        val i = plot.stepAt(x, CbmEnvelopes.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        val range = CbmInstrumentView.range(song, drawKind)
        session.editCbmEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { CbmEnvelopes.withValue(drawKind, it, i, value, range) }
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
