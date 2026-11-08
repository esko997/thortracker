package org.thortracker.system.nes

import kotlin.math.abs
import kotlin.math.roundToInt
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.editor.channelCount
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.nes.NesInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.IdentityCache
import org.thortracker.ui.Surface

/**
 * NES instrument editor. Left: the 64 slots of the shared bank with how often each is used. Right: name, the channel
 * Listen plays on, page tabs, and on an envelope page the shown envelope's length / loop / release / pitch mode, what
 * each channel does with it, and its steps as bars with the loop region and release point; on the DPCM page the shown
 * key's mapping, the sound's mapped keys and the song's samples with the selected one's waveform.
 */
internal class NesInstrumentPainter(v: PaneView) : Painter(v) {
    private companion object {
        /** DPCM page rows drawn inside the sample box rather than as setting rows. */
        val BOX_ROWS = setOf(Row.SAMPLES, Row.SAMPLE_NAME, Row.SAMPLE_LEVEL, Row.ADD)
    }

    private val tabs = ArrayList<Pair<Rect, Int>>()
    private var slotTop = 0f
    private var slotH = 0f
    private var slotRect = Rect()
    private var slotScroll = 0
    private var stepScroll = 0
    private var graph = Rect()
    private var cellW = 0f
    private var lo = 0
    private var hi = 15

    private var usageSong: Song? = null
    private val usage = HashMap<Int, Int>()

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = NesInstrumentView.sound(s, st) ?: return
        val kind = NesInstrumentView.kind(st)
        val env = kind.of(ins)
        val channels = s.system.channels
        val listen = channels.getOrNull(st.listenChannel)
        val col = Palette.channel(st.listenChannel)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "Instrument bank$locked  ${usageText(st.instrument)}")
        val row = NesInstrumentView.row(st)

        // left column: the bank, scrolled to keep the selected slot in view
        val leftW = minOf(area.width() * 0.3f, ink.unit * 14f)
        val left = Rect(area.left, top, area.left + leftW, area.bottom)
        ink.rect(c, left, Palette.LANE)
        slotTop = top + ink.unit * 0.2f
        slotH = ink.unit * 1.45f
        val visible = maxOf(1, ((area.bottom - slotTop) / slotH).toInt())
        slotScroll = slotScroll.coerceIn(maxOf(0, st.instrument - visible), minOf(st.instrument - 1, NesSystem.SLOTS - visible).coerceAtLeast(0))
        slotRect = Rect(left.left, slotTop, left.right, slotTop + slotH * visible)
        val bank = s.nes.instruments
        for (k in 0 until minOf(visible, NesSystem.SLOTS - slotScroll)) {
            val i = slotScroll + k + 1
            val y = slotTop + k * slotH
            val sel = i == st.instrument
            if (sel) {
                ink.rect(c, left.left, y, left.right, y + slotH, Palette.CURSOR_FILL)
                ink.rect(c, left.left, y, left.left + ink.dp(3f), y + slotH, col)
            }
            val sz = minOf(ink.unit, slotH * 0.72f)
            ink.mono(c, hex2(i), left.left + ink.unit * 0.6f, y, slotH, if (sel) col else Palette.FAINT, sel, sz)
            val used = usage(i)
            val nameColor = if (sel) Palette.TEXT else if (used > 0) Palette.DIM else Palette.FAINT
            ink.label(c, ink.fit(bank[i - 1].name, leftW - ink.unit * 4.6f, sz), left.left + ink.unit * 2.4f, y, slotH, nameColor, size = sz)
            if (used > 0) ink.mono(c, used.toString(), left.right - ink.unit * 0.5f, y, slotH, Palette.FAINT, size = sz * 0.8f, align = Align.RIGHT)
        }
        if (row == Row.SLOT) {
            val y = slotTop + (st.instrument - 1 - slotScroll) * slotH
            ink.outline(c, Rect(left.left + 2, y + 1, left.right - 2, y + slotH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }

        // right column: settings rows
        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val rw = area.right - pad - rx
        val rowH = ink.unit * 1.5f
        val vx = rx + ink.unit * 6.5f
        var y = top + ink.unit * 0.3f
        val dpcm = NesInstrumentView.dpcmPage(st)
        val mapping = if (dpcm) ins.dpcm[st.dpcmKey] else null
        for (r in NesInstrumentView.rows(st)) {
            if (r == Row.SLOT || r == Row.STEPS || r in BOX_ROWS) continue
            val sel = r == row
            val h = if (r == Row.ENVELOPE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, Palette.CURSOR_FILL)
            when (r) {
                Row.NAME -> {
                    ink.label(c, "Name", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    ink.label(c, ins.name.ifEmpty { "(unnamed)" }, vx, y, h, if (ins.name.isEmpty()) Palette.FAINT else Palette.TEXT, true)
                    if (sel) {
                        val before = ins.name.take(st.textPos).padEnd(st.textPos, ' ')
                        val cx = vx + ink.labelWidth(before, ink.unit, true)
                        val ch = ins.name.getOrNull(st.textPos)?.toString() ?: " "
                        val w = maxOf(ink.labelWidth(ch, ink.unit, true), ink.unit * 0.5f)
                        ink.outline(c, Rect(cx - 1, y + 2, cx + w + 1, y + h - 2), cursorColor(), ink.dp(2f), ink.dp(2f))
                    }
                }
                Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    val name = listen?.name ?: ""
                    ink.label(c, name, vx, y, h, col, true)
                    val note = listen?.let { NesInstrumentView.channelNote(kind, it.kind) }
                    if (note != null) {
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rx + rw - nx, ink.unit * 0.85f), nx, y, h, Palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                Row.ENVELOPE -> drawTabs(c, ins, Rect(rx, y, rx + rw, y + h), listen?.kind, col)
                Row.LENGTH -> setting(c, "Length", when {
                    env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${frames(env.values.size)})"
                    kind == NesEnvelopeKind.VOLUME -> "empty: full volume"
                    else -> "empty: not used"
                }, rx, vx, y, h, sel, env.values.isEmpty())
                Row.LOOP -> setting(c, "Loop", if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}", rx, vx, y, h, sel, env.loop < 0)
                Row.RELEASE -> setting(c, "Release", when {
                    env.release >= 0 -> "at step ${env.release}: after OFF the steps from here play out"
                    env.loop < 0 -> "none: OFF stops the note (a release needs a loop)"
                    else -> "none: OFF stops the note"
                }, rx, vx, y, h, sel, env.release < 0)
                Row.MODE -> setting(c, "Mode", if (ins.pitchRelative) "relative: offsets add up every frame" else "absolute: offset from the note", rx, vx, y, h, sel, false)
                Row.KEY -> setting(c, "Key", NesSystem.noteName(st.dpcmKey) + when {
                    ins.dpcm.isEmpty() -> "   no keys mapped yet"
                    mapping == null -> "   not mapped (${ins.dpcm.size} key${if (ins.dpcm.size == 1) "" else "s"} are)"
                    else -> "   key ${ins.dpcm.keys.count { it <= st.dpcmKey }} of ${ins.dpcm.size} mapped"
                }, rx, vx, y, h, sel, mapping == null)
                Row.SAMPLE -> setting(c, "Sample", when {
                    mapping == null -> "none"
                    else -> s.nes.samples.getOrNull(mapping.sample)?.let { "${it.name}  (${it.data.size} bytes)" } ?: "missing sample ${mapping.sample + 1}"
                }, rx, vx, y, h, sel, mapping == null)
                Row.PITCH -> setting(c, "Pitch", mapping?.let { "${it.pitch.toString(16).uppercase()}  ${NesSampleMenu.rateText(it.pitch)}" } ?: "—", rx, vx, y, h, sel, mapping == null)
                Row.SAMPLE_LOOP -> setting(c, "Loop", when {
                    mapping == null -> "—"
                    mapping.loop -> "on: repeats until the next note or OFF"
                    else -> "off: plays once"
                }, rx, vx, y, h, sel, mapping?.loop != true)
                Row.LEVEL -> setting(c, "Level", when {
                    mapping == null -> "—"
                    mapping.dmcInitial < 0 -> "the sample's own (${(s.nes.samples.getOrNull(mapping.sample)?.dmcInitial ?: DpcmSample.DEFAULT_DMC_INITIAL) * 2})"
                    else -> "${mapping.dmcInitial * 2} at note start"
                }, rx, vx, y, h, sel, mapping == null || mapping.dmcInitial < 0)
                else -> Unit
            }
            if (sel) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }
        if (dpcm) {
            graph = Rect()
            val boxTop = y + ink.unit * 0.4f
            if (area.bottom - boxTop > ink.unit * 6f) drawDpcm(c, Rect(rx, boxTop, rx + rw, area.bottom - ink.unit * 0.4f), ins, row, col)
            return
        }
        val small = ink.unit * 0.8f
        ink.label(c, ink.fit(NesInstrumentView.envelopeSummary(kind), rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
        y += rowH * 0.85f

        // steps
        val plotTop = y + ink.unit * 0.3f
        if (area.bottom - plotTop > ink.unit * 4f) drawSteps(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), kind, env, listen?.kind, col, row == Row.STEPS)
        else graph = Rect()
    }

    private fun setting(c: Surface, label: String, value: String, x: Float, vx: Float, y: Float, h: Float, sel: Boolean, off: Boolean) {
        ink.label(c, label, x, y, h, if (sel) Palette.TEXT else Palette.DIM)
        ink.label(c, value, vx, y, h, if (sel) (if (off) Palette.DIM else Palette.TEXT) else (if (off) Palette.FAINT else Palette.DIM), sel)
    }

    /** Frames at the NES's 60 per second, as a duration. */
    private fun frames(n: Int) = "${(n * 1000.0 / 60.0).roundToInt()} ms"

    private fun cursorColor() = when {
        ctl.editHeld -> Palette.WRITE
        ctl.auditionHeld -> Palette.LISTEN
        v.controllerFocused -> Palette.TEXT
        else -> Palette.DIM
    }

    /** Page tabs: each envelope with its length (greyed when the listening channel ignores it), then DPCM with its keys. */
    private fun drawTabs(c: Surface, ins: NesInstrument, r: Rect, listen: org.thortracker.system.ChannelKind?, col: Int) {
        tabs.clear()
        val n = NesInstrumentView.DPCM_PAGE + 1
        val w = r.width() / n
        for (page in 0 until n) {
            val k = NesEnvelopeKind.entries.getOrNull(page)
            val t = Rect(r.left + page * w, r.top, r.left + (page + 1) * w, r.bottom)
            val on = page == st.envelope
            val ignored = listen != null && k != null && ignores(k, listen)
            val color = when {
                on -> col
                ignored -> Palette.FAINT
                else -> Palette.DIM
            }
            if (on) ink.rect(c, t, Palette.mix(Palette.BG, col, 0.18f))
            ink.rect(c, t.left, t.bottom - ink.dp(if (on) 3f else 1f), t.right, t.bottom, if (on) col else Palette.RULE)
            val size = k?.of(ins)?.values?.size ?: ins.dpcm.size
            val name = k?.label ?: "DPCM"
            val label = if (size == 0) name else "$name ($size)"
            ink.label(c, ink.fit(label, w - ink.unit * 0.4f), t.centerX(), t.top, t.height(), color, on, align = Align.CENTER)
            tabs += t to page
        }
    }

    /** True when [channel] plays nothing of envelope [kind]: the triangle's volume and duty, every envelope on DPCM. */
    private fun ignores(kind: NesEnvelopeKind, channel: org.thortracker.system.ChannelKind) =
        channel == org.thortracker.system.ChannelKind.SAMPLE ||
            channel == org.thortracker.system.ChannelKind.TRIANGLE && (kind == NesEnvelopeKind.DUTY || kind == NesEnvelopeKind.VOLUME)

    // ---- DPCM page: mapped keys and the song's samples ------------------------------------------------

    private var keyRect = Rect()
    private var keyRowH = 0f
    private var keyScroll = 0
    private var keyList: List<Int> = emptyList()
    private var sampleRect = Rect()
    private var sampleRowH = 0f
    private var sampleScroll = 0
    private var addRect = Rect()

    /** Decoded level curves of drawn samples, by identity (samples are immutable). */
    private val curves = IdentityCache<DpcmSample, IntArray>(64)

    private fun curve(sample: DpcmSample): IntArray = curves.getOrPut(sample) { Dpcm.decode(sample.data, sample.dmcInitial) }

    /**
     * Left: every key the sound maps (key, sample, pitch, loop), the shown key marked. Right: the song's samples with
     * their sizes and the memory they share, the selected sample's waveform (its DMC output level), its name and
     * initial level, and the Add row.
     */
    private fun drawDpcm(c: Surface, r: Rect, ins: NesInstrument, row: Row, col: Int) {
        val s = song
        val samples = s.nes.samples
        val gap = ink.unit * 0.6f
        val small = ink.unit * 0.8f
        val titleH = ink.unit * 1.4f
        val left = Rect(r.left, r.top, r.left + (r.width() - gap) * 0.42f, r.bottom)
        val right = Rect(left.right + gap, r.top, r.right, r.bottom)

        // mapped keys
        ink.round(c, left, ink.dp(4f), Palette.LANE)
        ink.label(c, ink.fit("Keys of sound ${hex2(st.instrument)}", left.width() - ink.unit), left.left + ink.unit * 0.5f, left.top, titleH, Palette.DIM, size = small)
        keyList = ins.dpcm.keys.sorted()
        keyRowH = ink.unit * 1.25f
        keyRect = Rect(left.left, left.top + titleH, left.right, left.bottom - ink.unit * 0.3f)
        val keyRows = maxOf(1, (keyRect.height() / keyRowH).toInt())
        val shown = keyList.indexOf(st.dpcmKey)
        if (shown >= 0) keyScroll = keyScroll.coerceIn(maxOf(0, shown - keyRows + 1), shown)
        keyScroll = keyScroll.coerceIn(0, maxOf(0, keyList.size - keyRows))
        if (keyList.isEmpty()) {
            ink.label(c, ink.fit("No keys yet: Add, then map", left.width() - ink.unit), left.left + ink.unit * 0.5f, keyRect.top, keyRowH, Palette.FAINT, size = small)
        }
        val keyFocus = row == Row.KEY || row == Row.SAMPLE || row == Row.PITCH || row == Row.SAMPLE_LOOP || row == Row.LEVEL
        for (i in keyScroll until minOf(keyList.size, keyScroll + keyRows)) {
            val key = keyList[i]
            val m = ins.dpcm.getValue(key)
            val y = keyRect.top + (i - keyScroll) * keyRowH
            val on = key == st.dpcmKey
            if (on) ink.rect(c, left.left, y, left.right, y + keyRowH, if (keyFocus) Palette.CURSOR_FILL else Palette.alpha(Palette.CURSOR_FILL, 140))
            if (on) ink.rect(c, left.left, y, left.left + ink.dp(3f), y + keyRowH, col)
            ink.mono(c, NesSystem.noteName(key), left.left + ink.unit * 0.5f, y, keyRowH, if (on) col else Palette.DIM, on, small)
            val tail = m.pitch.toString(16).uppercase() + if (m.loop) " ↻" else ""
            val tailW = ink.labelWidth(tail, small) + ink.unit * 0.5f
            val name = samples.getOrNull(m.sample)?.name ?: "missing"
            ink.label(c, ink.fit(name, left.width() - ink.unit * 3.6f - tailW, small), left.left + ink.unit * 3.2f, y, keyRowH,
                if (m.sample in samples.indices) (if (on) Palette.TEXT else Palette.DIM) else Palette.ERROR, size = small)
            ink.label(c, tail, left.right - ink.unit * 0.4f, y, keyRowH, Palette.FAINT, size = small, align = Align.RIGHT)
        }

        // samples
        ink.round(c, right, ink.dp(4f), Palette.LANE)
        val used = NesSampleMenu.stored(s)
        val over = used > Dpcm.MEMORY
        ink.label(c, ink.fit("Samples · ${NesSampleMenu.kb(used)} of ${NesSampleMenu.kb(Dpcm.MEMORY)}", right.width() * 0.62f), right.left + ink.unit * 0.5f, right.top, titleH,
            if (over) Palette.ERROR else Palette.DIM, size = small)
        val barL = right.left + right.width() * 0.64f
        val barR = right.right - ink.unit * 0.5f
        val barY = right.top + titleH * 0.42f
        ink.rect(c, barL, barY, barR, barY + ink.dp(5f), Palette.RULE)
        ink.rect(c, barL, barY, barL + (barR - barL) * minOf(1f, used.toFloat() / Dpcm.MEMORY), barY + ink.dp(5f), if (over) Palette.ERROR else col)

        val lineH = ink.unit * 1.35f
        addRect = Rect(right.left, right.bottom - lineH - ink.unit * 0.2f, right.right, right.bottom - ink.unit * 0.2f)
        val levelY = addRect.top - lineH
        val nameY = levelY - lineH
        val waveH = minOf(ink.unit * 3.2f, (nameY - right.top - titleH) * 0.45f)
        val waveRect = Rect(right.left + ink.unit * 0.5f, nameY - waveH - ink.unit * 0.2f, right.right - ink.unit * 0.5f, nameY - ink.unit * 0.2f)
        sampleRowH = ink.unit * 1.2f
        sampleRect = Rect(right.left, right.top + titleH, right.right, waveRect.top - ink.unit * 0.2f)
        val sampleRows = maxOf(1, (sampleRect.height() / sampleRowH).toInt())
        sampleScroll = sampleScroll.coerceIn(maxOf(0, st.dpcmSample - sampleRows + 1), st.dpcmSample.coerceAtLeast(0))
        sampleScroll = sampleScroll.coerceIn(0, maxOf(0, samples.size - sampleRows))
        val keyed = ins.dpcm[st.dpcmKey]?.sample ?: -1
        if (samples.isEmpty()) ink.label(c, ink.fit("None yet: use Add below", right.width() - ink.unit), right.left + ink.unit * 0.5f, sampleRect.top, sampleRowH, Palette.FAINT, size = small)
        for (i in sampleScroll until minOf(samples.size, sampleScroll + sampleRows)) {
            val sample = samples[i]
            val y = sampleRect.top + (i - sampleScroll) * sampleRowH
            val on = i == st.dpcmSample
            if (on) ink.rect(c, right.left, y, right.right, y + sampleRowH, Palette.CURSOR_FILL)
            ink.mono(c, (i + 1).toString().padStart(2, '0'), right.left + ink.unit * 0.5f, y, sampleRowH, if (i == keyed) col else Palette.FAINT, i == keyed, small)
            val size = "${sample.data.size} B"
            ink.label(c, ink.fit(sample.name.ifEmpty { "(unnamed)" }, right.width() - ink.unit * 2.6f - ink.labelWidth(size, small) - ink.unit), right.left + ink.unit * 2.2f, y, sampleRowH,
                if (on) Palette.TEXT else Palette.DIM, size = small)
            ink.label(c, size, right.right - ink.unit * 0.5f, y, sampleRowH, Palette.FAINT, size = small, align = Align.RIGHT)
            if (on && row == Row.SAMPLES) ink.outline(c, Rect(right.left + 2, y + 1, right.right - 2, y + sampleRowH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }

        // the selected sample: waveform, name, initial level
        val sample = samples.getOrNull(st.dpcmSample)
        ink.rect(c, waveRect, Palette.BG)
        if (sample != null && waveRect.width() > 4f && waveRect.height() > 4f) {
            val levels = curve(sample)
            val columns = maxOf(1, waveRect.width().toInt() / 2)
            val per = levels.size.toFloat() / columns
            val cw = waveRect.width() / columns
            val mid = waveRect.top + waveRect.height() / 2
            ink.line(c, waveRect.left, mid, waveRect.right, mid, Palette.RULE, ink.dp(1f))
            for (k in 0 until columns) {
                val a = (k * per).toInt().coerceIn(0, levels.size - 1)
                val b = ((k + 1) * per).toInt().coerceIn(a + 1, levels.size)
                var lo = 127
                var hi = 0
                for (i in a until b) { val l = levels[i]; if (l < lo) lo = l; if (l > hi) hi = l }
                val y0 = waveRect.bottom - waveRect.height() * hi / 127f
                val y1 = waveRect.bottom - waveRect.height() * lo / 127f
                ink.rect(c, waveRect.left + k * cw, y0, waveRect.left + k * cw + maxOf(1f, cw - 0.5f), maxOf(y1, y0 + ink.dp(1f)), Palette.alpha(col, 200))
            }
            val secs = NumberText.fixed(sample.data.size * 8.0 / Dpcm.RATES_HZ[ins.dpcm[st.dpcmKey]?.pitch ?: DpcmMapping.PITCH.last], 2)
            ink.label(c, "$secs s", waveRect.right - ink.dp(3f), waveRect.top, ink.unit, Palette.FAINT, size = ink.unit * 0.65f, align = Align.RIGHT)
        }
        val vx = right.left + ink.unit * 4.4f
        fun boxRow(y: Float, which: Row, label: String, value: String, off: Boolean) {
            val sel = row == which
            val rr = Rect(right.left + ink.unit * 0.2f, y, right.right - ink.unit * 0.2f, y + lineH)
            if (sel) ink.rect(c, rr, Palette.CURSOR_FILL)
            ink.label(c, label, right.left + ink.unit * 0.5f, y, lineH, if (sel) Palette.TEXT else Palette.DIM, size = small)
            ink.label(c, ink.fit(value, right.right - vx - ink.unit * 0.5f, small), vx, y, lineH,
                if (off) Palette.FAINT else if (sel) Palette.TEXT else Palette.DIM, sel, small)
            if (sel) ink.outline(c, rr, cursorColor(), ink.dp(2f), ink.dp(3f))
        }
        boxRow(nameY, Row.SAMPLE_NAME, "Name", sample?.name?.ifEmpty { "(unnamed)" } ?: "—", sample == null)
        if (row == Row.SAMPLE_NAME && sample != null) {
            val before = sample.name.take(st.textPos).padEnd(st.textPos, ' ')
            val cx = vx + ink.labelWidth(before, small, true)
            val w = maxOf(ink.labelWidth(sample.name.getOrNull(st.textPos)?.toString() ?: " ", small, true), small * 0.5f)
            ink.outline(c, Rect(cx - 1, nameY + 3, cx + w + 1, nameY + lineH - 3), cursorColor(), ink.dp(2f), ink.dp(2f))
        }
        boxRow(levelY, Row.SAMPLE_LEVEL, "Level", sample?.let { "${it.dmcInitial * 2} at start (\$4011)" } ?: "—", sample == null)
        boxRow(addRect.top, Row.ADD, "Add", "starter kit or .wav / .dmc file…", false)
    }

    private fun drawSteps(c: Surface, r: Rect, kind: NesEnvelopeKind, env: NesEnvelope, listen: org.thortracker.system.ChannelKind?, col: Int, focused: Boolean) {
        val values = env.values
        val step = st.envStep
        val title = when {
            step < values.size -> "${kind.label} step $step of ${values.size}: ${NesInstrumentView.valueText(kind, values[step], listen)}"
            values.isEmpty() -> "${kind.label}: no steps; hold ${ctl.keys.label(org.thortracker.editor.Action.EDIT)} and press ${ctl.keys.label(org.thortracker.editor.Action.UP)} to add one"
            else -> "${kind.label}: ${values.size} steps; ${ctl.keys.label(org.thortracker.editor.Action.EDIT)} adds step ${values.size}"
        }
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        ink.label(c, ink.fit(title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, ink.unit * 1.4f, if (focused) Palette.DIM else Palette.FAINT, size = ink.unit * 0.8f)
        val labelH = ink.unit * 1.1f
        val inner = Rect(r.left + ink.unit * 2.2f, r.top + ink.unit * 1.5f, r.right - ink.unit * 0.5f, r.bottom - ink.unit * 0.3f - labelH)
        graph = inner
        if (inner.height() <= 0f) return

        // value scale: offsets span the largest written magnitude, at least an octave / 12 period units
        if (kind.range.first < 0) {
            val span = minOf(kind.range.last + 1, maxOf(12, values.maxOfOrNull { abs(it) } ?: 0))
            lo = -span; hi = span
        } else { lo = kind.range.first; hi = kind.range.last }
        fun yOf(value: Int) = inner.bottom - inner.height() * (value - lo).toFloat() / (hi - lo)

        // columns: at least 24, wide enough to read; scroll to keep the cursor in view
        val minCell = ink.unit * 1.15f
        val columns = maxOf(24, values.size + 1)
        cellW = maxOf(minCell, inner.width() / columns)
        val fit = maxOf(1, (inner.width() / cellW).toInt())
        stepScroll = stepScroll.coerceIn(maxOf(0, step - fit + 1), step)
        stepScroll = stepScroll.coerceAtMost(maxOf(0, columns - fit))

        // scale labels and zero / limit rules
        val ls = ink.unit * 0.7f
        for (value in listOf(hi, if (lo < 0) 0 else -1, lo)) {
            if (value < lo) continue
            val yv = yOf(value)
            ink.line(c, inner.left, yv, inner.right, yv, if (value == 0 && lo < 0) Palette.BAR else Palette.RULE, ink.dp(1f))
            ink.mono(c, NesInstrumentView.valueText(kind, value, listen).take(5), inner.left - ink.unit * 0.3f, yv - ls, ls * 2, Palette.FAINT, size = ls, align = Align.RIGHT)
        }

        // loop region (the part that repeats while held) and release point
        val loopEnd = if (env.release >= 0) env.release else values.size
        fun xOf(i: Int) = inner.left + (i - stepScroll) * cellW
        if (env.loop >= 0) {
            val x0 = maxOf(inner.left, xOf(env.loop))
            val x1 = minOf(inner.right, xOf(loopEnd))
            if (x1 > x0) {
                ink.rect(c, x0, inner.top, x1, inner.bottom, Palette.alpha(Palette.LISTEN, 26))
                ink.rect(c, x0, inner.top, x1, inner.top + ink.dp(3f), Palette.LISTEN)
                ink.label(c, "loop", x0 + ink.dp(3f), inner.top + ink.dp(3f), ink.unit, Palette.LISTEN, size = ink.unit * 0.7f)
            }
        }
        if (env.release >= 0) {
            val x = xOf(env.release)
            if (x >= inner.left && x <= inner.right) {
                ink.line(c, x, inner.top, x, inner.bottom, Palette.WRITE, ink.dp(2f))
                ink.label(c, "release", x + ink.dp(3f), inner.bottom - ink.unit, ink.unit, Palette.WRITE, size = ink.unit * 0.7f)
            }
        }

        // bars
        val base = if (lo < 0) yOf(0) else inner.bottom
        val gap = maxOf(1f, cellW * 0.12f)
        val muted = listen != null && ignores(kind, listen)
        val barColor = if (muted) Palette.FAINT else Palette.alpha(col, 170)
        val last = minOf(values.size, stepScroll + fit)
        for (i in stepScroll until last) {
            val x = xOf(i)
            val yv = yOf(values[i])
            val t = minOf(base, yv)
            val b = maxOf(base, yv)
            val on = i == step
            ink.rect(c, x + gap, minOf(t, b - ink.dp(2f)), x + cellW - gap, b, if (on && focused) (if (ctl.editHeld) Palette.WRITE else col) else barColor)
        }
        // append position
        if (values.size < NesEnvelope.MAX_LENGTH && values.size in stepScroll until stepScroll + fit) {
            val x = xOf(values.size)
            ink.outline(c, Rect(x + gap, inner.top + gap, x + cellW - gap, inner.bottom - gap), Palette.FAINT, ink.dp(1f), ink.dp(2f))
            ink.mono(c, "+", x + cellW / 2, inner.top, inner.height(), Palette.FAINT, size = ink.unit * 0.8f, align = Align.CENTER)
        }
        // cursor
        if (step in stepScroll until stepScroll + fit) {
            val x = xOf(step)
            ink.outline(c, Rect(x, inner.top, x + cellW, inner.bottom), if (focused) cursorColor() else Palette.FAINT, ink.dp(if (focused) 2f else 1f), ink.dp(2f))
        }
        // step numbers
        val every = when { cellW >= ink.unit * 2f -> 1; cellW >= ink.unit * 1.4f -> 2; else -> 4 }
        for (i in stepScroll until minOf(stepScroll + fit, values.size + 1)) {
            if (i % every != 0 && i != step) continue
            ink.mono(c, i.toString(), xOf(i) + cellW / 2, inner.bottom + ink.dp(2f), labelH, if (i == step) Palette.TEXT else Palette.FAINT, size = ink.unit * 0.65f, align = Align.CENTER)
        }
    }

    private fun usage(slot: Int): Int {
        val s = song
        if (s !== usageSong) {
            usage.clear()
            val counted = HashSet<Int>()
            for (pat in s.orders) {
                if (!counted.add(pat)) continue
                for (ch in 0 until s.channelCount()) for (cell in s.patterns[pat].tracks[ch]) {
                    if (cell.instrument > 0) usage[cell.instrument] = (usage[cell.instrument] ?: 0) + 1
                }
            }
            usageSong = s
        }
        return usage[slot] ?: 0
    }

    private fun usageText(slot: Int): String {
        val n = usage(slot)
        return if (n == 0) "not used in the arrangement yet" else "used on $n row${if (n == 1) "" else "s"}"
    }

    // ---- touch: slots, tabs, and drawing on the steps -------------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = NesEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        if (NesInstrumentView.dpcmPage(st)) dpcmDown(x, y)?.let { return it }
        tabs.firstOrNull { it.first.contains(x, y) }?.let { (_, k) ->
            NesInstrumentView.showEnvelope(ctl, k)
            return true
        }
        if (slotRect.contains(x, y)) {
            st.instrument = (((y - slotTop) / slotH).toInt() + slotScroll + 1).coerceIn(1, NesSystem.SLOTS)
            st.instField = 0
            NesInstrumentView.publish(ctl)
            return true
        }
        if (graph.contains(x, y) && cellW > 0f) {
            st.instField = NesInstrumentView.rows(st).indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = NesInstrumentView.kind(st)
            drawing = session.beginGesture("Draw ${drawKind.label.lowercase()}")
            drawAt(x, y)
            return true
        }
        return false
    }

    /** Sets the step under [x] to the value at [y]; past the end it appends one step at a time. */
    private fun drawAt(x: Float, y: Float) {
        val e = song.nesInstrument(drawSlot)?.let { drawKind.of(it) } ?: return
        val i = (stepScroll + ((x - graph.left) / cellW).toInt()).coerceIn(0, NesInstrumentView.lastStep(e))
        val t = ((graph.bottom - y) / graph.height()).coerceIn(0f, 1f)
        val value = (lo + t * (hi - lo)).roundToInt()
        st.envStep = i
        session.editNesEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { NesEnvelopes.withValue(drawKind, it, i, value) }
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

    /** DPCM page taps: a mapped key shows it, a sample selects it, Add opens the add menu. Null: not on them. */
    private fun dpcmDown(x: Float, y: Float): Boolean? {
        val rows = NesInstrumentView.rows(st)
        when {
            keyRect.contains(x, y) && keyRowH > 0f -> {
                val key = keyList.getOrNull(keyScroll + ((y - keyRect.top) / keyRowH).toInt()) ?: return true
                st.dpcmKey = key
                st.instField = rows.indexOf(Row.KEY)
                song.dpcmMapping(st.instrument, key)?.let { st.dpcmSample = it.sample }
            }
            sampleRect.contains(x, y) && sampleRowH > 0f -> {
                val i = sampleScroll + ((y - sampleRect.top) / sampleRowH).toInt()
                if (i !in song.nes.samples.indices) return true
                st.dpcmSample = i
                st.instField = rows.indexOf(Row.SAMPLES)
            }
            addRect.contains(x, y) -> {
                st.instField = rows.indexOf(Row.ADD)
                NesSampleMenu.open(session, st.instrument, st.dpcmKey)
            }
            else -> return null
        }
        return true
    }
}
