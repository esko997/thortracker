package org.thortracker.system.nes

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
 * NES instrument editor. Left: the 64 slots of the shared bank with how often each is used. Right: name, on songs
 * with an expansion chip the sound's chip (and a VRC6 sound's saw volume), the channel Listen plays on, page tabs,
 * and on an envelope page the shown envelope's length / loop / release / pitch mode, what each channel does with it,
 * and its steps as bars with the loop region and release point; on the DPCM page the shown key's mapping, the
 * sound's mapped keys and the song's samples with the selected one's waveform; on a VRC7 sound's FM page the patch,
 * the sustain overrides and the patch's fields for the modulator and the carrier; on an N163 sound's Waves page the
 * preset, size (and what it does to the pitch), RAM position, the shown wave, its repeat, the loop and release, the
 * song's N163 RAM map, the sequence of waves and the shown wave's samples.
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
    private val plot = EnvelopePlot(ink)

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
        val row = NesInstrumentView.row(st, s)

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
        val dpcm = NesInstrumentView.dpcmPage(st)
        val fm = NesInstrumentView.fmPage(s, st)
        val wavePage = NesInstrumentView.wavePage(s, st)
        // The Waves page has more settings: its rows are a little lower so the RAM map and the samples fit.
        val rowH = ink.unit * (if (wavePage) 1.3f else 1.5f)
        val vx = rx + ink.unit * 6.5f
        var y = top + ink.unit * 0.3f
        val mapping = if (dpcm) ins.dpcm[st.dpcmKey] else null
        val n163 = s.nes.expansion.n163Channels
        val wave = ins.wave
        val shown = NesInstrumentView.waveIndex(st, wave)
        if (!wavePage) { waveRect = Rect(); thumbs.clear() }
        for (r in NesInstrumentView.rows(st, s)) {
            if (r == Row.SLOT || r == Row.STEPS || r == Row.WAVE_SAMPLES || r in BOX_ROWS || r in NesInstrumentView.FM_PARAMS) continue
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
                Row.CHIP -> setting(c, "Chip", when (ins.chip) {
                    NesChip.APU -> "2A03: Pulse 1/2, Triangle, Noise, DPCM"
                    NesChip.VRC6 -> "VRC6: VRC6 Pulse 1/2 and Saw"
                    NesChip.VRC7 -> "VRC7: FM 1-6"
                    NesChip.N163 -> "N163: " + if (n163 <= 1) "N163 1" else "N163 1-$n163"
                }, rx, vx, y, h, sel, false)
                Row.PATCH -> setting(c, "Patch", when (ins.fm.preset) {
                    Vrc7Presets.CUSTOM -> "Custom: the chip's one programmable patch (shared by all FM channels)"
                    else -> "${Vrc7Presets.NAMES.getOrElse(ins.fm.preset) { "?" }}: built-in (${ins.fm.preset} of 15); edit a field to make it Custom"
                }, rx, vx, y, h, sel, false)
                Row.SUSTAIN_RELEASE -> setting(c, "Release", if (ins.fm.sustainRelease) "sustain: OFF fades slowly (rate 5)" else "the patch's release after OFF",
                    rx, vx, y, h, sel, !ins.fm.sustainRelease)
                Row.SUSTAIN_STOP -> setting(c, "Stop", if (ins.fm.sustainStop) "sustain: a stop or cut fades slowly (rate 5)" else "a stop or cut ends with the patch's release",
                    rx, vx, y, h, sel, !ins.fm.sustainStop)
                Row.SAW -> setting(c, "Saw volume", "${ins.sawVolume.label}: ${NesInstrumentView.sawText(ins.sawVolume)}", rx, vx, y, h, sel,
                    ins.sawVolume == Vrc6SawVolume.HALF)
                Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    val name = listen?.name ?: ""
                    ink.label(c, name, vx, y, h, col, true)
                    val note = when {
                        fm -> null
                        // On the Waves page only a channel of another chip has something to say.
                        wavePage -> listen?.takeIf { NesRules.chip(s.nes.expansion, st.listenChannel) != ins.chip }?.let {
                            NesInstrumentView.channelNote(kind, it.kind, ins.chip, ins.sawVolume, NesRules.chip(s.nes.expansion, st.listenChannel))
                        }
                        else -> listen?.let {
                            NesInstrumentView.channelNote(kind, it.kind, ins.chip, ins.sawVolume, NesRules.chip(s.nes.expansion, st.listenChannel),
                                maxOf(1, n163))
                        }
                    }
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
                Row.WAVE_PRESET -> setting(c, "Preset", N163Presets.presetOf(wave.waves, wave.size)?.let {
                    "${it.label}: FamiStudio's preset on ${if (wave.waves.size == 1) "the wave" else "all ${wave.waves.size} waves"}"
                } ?: "Custom: drawn waves", rx, vx, y, h, sel, false)
                Row.WAVE_SIZE -> setting(c, "Size", ink.fit(NesInstrumentView.sizeText(wave.size), rx + rw - vx), rx, vx, y, h, sel, false)
                Row.WAVE_POSITION -> setting(c, "Position", ink.fit(NesInstrumentView.ramText(wave, maxOf(1, n163)), rx + rw - vx), rx, vx, y, h, sel, false)
                Row.WAVE_INDEX -> setting(c, "Wave", "${shown + 1} of ${wave.waves.size}" +
                    if (N163Waves.full(wave)) "   (the most of ${wave.size} samples)" else "", rx, vx, y, h, sel, false)
                Row.WAVE_REPEAT -> wave.repeat.getOrElse(shown) { 1 }.let { n ->
                    setting(c, "Repeat", "wave ${shown + 1} plays $n frame${if (n == 1) "" else "s"} (${frames(n)})", rx, vx, y, h, sel, n == 1)
                }
                Row.WAVE_LOOP -> setting(c, "Loop", if (wave.loop < 0) "off: the last wave holds" else "back to wave ${wave.loop + 1}",
                    rx, vx, y, h, sel, wave.loop < 0)
                Row.WAVE_RELEASE -> setting(c, "Release", when {
                    wave.release >= 0 -> "at wave ${wave.release + 1}: after OFF the waves from here play out"
                    wave.loop < 0 -> "none (a release needs a loop)"
                    else -> "none: after OFF the waves go on as before"
                }, rx, vx, y, h, sel, wave.release < 0)
                else -> Unit
            }
            if (sel) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }
        if (dpcm) {
            plot.hide()
            val boxTop = y + ink.unit * 0.4f
            if (area.bottom - boxTop > ink.unit * 6f) drawDpcm(c, Rect(rx, boxTop, rx + rw, area.bottom - ink.unit * 0.4f), ins, row, col)
            return
        }
        if (fm) {
            plot.hide()
            drawFm(c, Rect(rx, y + ink.unit * 0.3f, rx + rw, area.bottom - ink.unit * 0.4f), ins.fm, row, col)
            return
        }
        if (wavePage) {
            plot.hide()
            drawWaves(c, Rect(rx, y + ink.unit * 0.2f, rx + rw, area.bottom - ink.unit * 0.4f), ins, row, col)
            return
        }
        val small = ink.unit * 0.8f
        ink.label(c, ink.fit(NesInstrumentView.envelopeSummary(kind, ins.chip), rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
        y += rowH * 0.85f

        // steps
        val plotTop = y + ink.unit * 0.3f
        if (area.bottom - plotTop > ink.unit * 4f) drawSteps(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), kind, env, listen?.kind, col, row == Row.STEPS, ins.chip)
        else plot.hide()
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

    /** Page tabs: each envelope with its length (greyed when the listening channel ignores it), then DPCM with its keys on 2A03 sounds. */
    private fun drawTabs(c: Surface, ins: NesInstrument, r: Rect, listen: org.thortracker.system.ChannelKind?, col: Int) {
        tabs.clear()
        val n = NesInstrumentView.pages(song, st)
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
            val fmTab = k == NesEnvelopeKind.DUTY && ins.chip == NesChip.VRC7
            val waveTab = k == NesEnvelopeKind.DUTY && ins.chip == NesChip.N163
            val size = when {
                fmTab -> 0
                waveTab -> ins.wave.waves.size
                else -> k?.of(ins)?.values?.size ?: ins.dpcm.size
            }
            val name = when {
                fmTab -> "FM patch"
                waveTab -> "Waves"
                else -> k?.label ?: "DPCM"
            }
            val label = if (size == 0) name else "$name ($size)"
            ink.label(c, ink.fit(label, w - ink.unit * 0.4f), t.centerX(), t.top, t.height(), color, on, align = Align.CENTER)
            tabs += t to page
        }
    }

    // ---- FM page: the patch's register fields, modulator and carrier side by side ---------------------------------

    private var fmScroll = 0
    private var fmRect = Rect()
    private var fmRowH = 0f
    private var fmColX = FloatArray(3)
    private var fmRows: List<Row> = emptyList()

    /**
     * One line per patch field: its name, the modulator's and the carrier's value (modulator-only fields: one value),
     * the cursor on the selected field and operator, scrolled to keep it in view; a built-in patch shows its ROM values
     * greyed (editing one makes the sound Custom).
     */
    private fun drawFm(c: Surface, r: Rect, patch: Vrc7Patch, row: Row, col: Int) {
        val small = ink.unit * 0.85f
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val titleH = ink.unit * 1.3f
        val custom = patch.preset == Vrc7Presets.CUSTOM
        val regs = patch.sounding
        fmColX = floatArrayOf(r.left + ink.unit * 0.5f, r.left + r.width() * 0.45f, r.left + r.width() * 0.72f)
        ink.label(c, ink.fit(if (custom) "Custom patch" else "${Vrc7Presets.NAMES[patch.preset]} (built-in)", fmColX[1] - fmColX[0], small),
            fmColX[0], r.top, titleH, Palette.DIM, size = small)
        ink.label(c, "Modulator", fmColX[1], r.top, titleH, Palette.DIM, size = small)
        ink.label(c, "Carrier", fmColX[2], r.top, titleH, Palette.DIM, size = small)
        fmRowH = ink.unit * 1.15f
        fmRect = Rect(r.left, r.top + titleH, r.right, r.bottom)
        fmRows = NesInstrumentView.FM_ROWS.filter { it in NesInstrumentView.FM_PARAMS }
        val visible = maxOf(1, (fmRect.height() / fmRowH).toInt())
        val selected = fmRows.indexOf(row)
        if (selected >= 0) fmScroll = fmScroll.coerceIn(maxOf(0, selected - visible + 1), selected)
        fmScroll = fmScroll.coerceIn(0, maxOf(0, fmRows.size - visible))
        for (i in fmScroll until minOf(fmRows.size, fmScroll + visible)) {
            val rr = fmRows[i]
            val param = NesInstrumentView.FM_PARAMS.getValue(rr)
            val y = fmRect.top + (i - fmScroll) * fmRowH
            val on = rr == row
            if (on) ink.rect(c, r.left, y, r.right, y + fmRowH, Palette.CURSOR_FILL)
            ink.label(c, param.label, fmColX[0], y, fmRowH, if (on) Palette.TEXT else Palette.DIM, size = small)
            for (op in 0..1) {
                if (param.modulatorOnly && op == 1) continue
                val text = NesInstrumentView.fmValueText(param, param.get(regs, op))
                val cell = op == NesInstrumentView.fmOperator(st, param)
                ink.label(c, text, fmColX[1 + op], y, fmRowH, when {
                    !custom -> Palette.FAINT
                    on && cell -> col
                    else -> Palette.TEXT
                }, on && cell, small)
                if (on && cell) {
                    val w = maxOf(ink.labelWidth(text, small, true), ink.unit * 1.5f)
                    ink.outline(c, Rect(fmColX[1 + op] - ink.dp(4f), y + 1, fmColX[1 + op] + w + ink.dp(4f), y + fmRowH - 1), cursorColor(),
                        ink.dp(2f), ink.dp(3f))
                }
            }
        }
    }

    /** Taps on an FM field select it and its operator column. Null: not on the grid. */
    private fun fmDown(x: Float, y: Float): Boolean? {
        if (!NesInstrumentView.fmPage(song, st) || !fmRect.contains(x, y) || fmRowH <= 0f) return null
        val row = fmRows.getOrNull(fmScroll + ((y - fmRect.top) / fmRowH).toInt()) ?: return true
        st.instField = NesInstrumentView.rows(st, song).indexOf(row)
        st.programField = if (x >= fmColX[2]) 1 else 0
        return true
    }

    /**
     * True when [channel] plays nothing of envelope [kind]: the triangle's volume and duty, the VRC6 saw's duty, every
     * envelope on DPCM.
     */
    private fun ignores(kind: NesEnvelopeKind, channel: org.thortracker.system.ChannelKind) =
        channel == org.thortracker.system.ChannelKind.SAMPLE ||
            channel == org.thortracker.system.ChannelKind.TRIANGLE && (kind == NesEnvelopeKind.DUTY || kind == NesEnvelopeKind.VOLUME) ||
            channel == org.thortracker.system.ChannelKind.SAW && kind == NesEnvelopeKind.DUTY

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

    private fun drawSteps(c: Surface, r: Rect, kind: NesEnvelopeKind, env: NesEnvelope, listen: org.thortracker.system.ChannelKind?, col: Int, focused: Boolean,
                          chip: NesChip) {
        val text = { value: Int -> NesInstrumentView.valueText(kind, value, listen, chip) }
        val title = EnvelopePlot.title(kind.label, env.values, st.envStep, text,
            ctl.keys.label(org.thortracker.editor.Action.EDIT), ctl.keys.label(org.thortracker.editor.Action.UP))
        plot.draw(c, r, title, kind, env, st.envStep, text, listen != null && ignores(kind, listen), col, focused, cursorColor(), ctl.editHeld,
            kind.range(chip))
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

    // ---- Waves page (N163 sounds): the RAM map, the sequence of waves and the shown wave's samples ----------------

    /** Where the shown wave's samples were drawn (empty when they were not) and one sample's width. */
    private var waveRect = Rect()
    private var waveCellW = 0f

    /** The sequence's wave boxes as drawn, with their wave index; scrolled to keep the shown wave in view. */
    private val thumbs = ArrayList<Pair<Rect, Int>>()
    private var thumbScroll = 0

    /**
     * The RAM map (the N163's 256 samples: this sound's range on top, every other N163 sound's range in lanes below,
     * overlaps with this one in the error colour, the channel registers greyed), a line on the overlaps, the sequence of
     * waves (each as a small plot with its number and repeat, the loop region marked above and the release as a line)
     * and the shown wave's samples as bars around the centre level 8, with the sample cursor.
     */
    private fun drawWaves(c: Surface, r: Rect, ins: NesInstrument, row: Row, col: Int) {
        val s = song
        val w = ins.wave
        val small = ink.unit * 0.8f
        val tiny = ink.unit * 0.65f
        val channels = maxOf(1, s.nes.expansion.n163Channels)
        val ram = NesRules.n163WaveRam(channels)
        val shown = NesInstrumentView.waveIndex(st, w)
        val sample = NesInstrumentView.sampleIndex(st, w)
        var y = r.top

        // RAM map
        val mapH = ink.unit * 1.6f
        ink.label(c, "RAM", r.left, y, mapH, Palette.DIM, size = small)
        val map = Rect(r.left + ink.unit * 2.6f, y + ink.dp(2f), r.right, y + mapH - ink.dp(2f))
        ink.rect(c, map, Palette.LANE)
        fun xOf(at: Int) = map.left + map.width() * at / NesRules.N163_RAM_SAMPLES.toFloat()
        val regs = Rect(xOf(ram), map.top, map.right, map.bottom)
        ink.rect(c, regs, Palette.alpha(Palette.FAINT, 120))
        if (regs.width() > ink.labelWidth("registers", tiny) + ink.dp(4f)) {
            ink.label(c, "registers", regs.centerX(), map.top, map.height(), Palette.TEXT, size = tiny, align = Align.CENTER)
        }
        val conflicts = NesInstrumentView.n163Overlaps(s, st.instrument).toSet()
        val others = s.nes.instruments.withIndex()
            .filter { it.index + 1 != st.instrument && it.value.chip == NesChip.N163 }
            .sortedBy { it.value.wave.position }
        val half = map.top + map.height() / 2
        val laneEnds = IntArray(3) { -1 }
        val laneH = (map.bottom - half) / laneEnds.size
        for ((index, other) in others) {
            val o = other.wave
            val lane = laneEnds.indices.firstOrNull { laneEnds[it] < o.position } ?: laneEnds.indices.minBy { laneEnds[it] }
            laneEnds[lane] = maxOf(laneEnds[lane], o.position + o.size - 1)
            val ly = half + lane * laneH
            ink.rect(c, xOf(o.position), ly + ink.dp(1f), maxOf(xOf(o.position + o.size), xOf(o.position) + ink.dp(2f)), ly + laneH,
                if (index + 1 in conflicts) Palette.ERROR else Palette.alpha(Palette.DIM, 160))
        }
        val mine = Rect(xOf(w.position), map.top, maxOf(xOf(w.position + w.size), xOf(w.position) + ink.dp(2f)), half)
        ink.rect(c, mine, col)
        if (row == Row.WAVE_POSITION || row == Row.WAVE_SIZE) {
            ink.outline(c, Rect(mine.left - 1, map.top - 1, mine.right + 1, map.bottom + 1), cursorColor(), ink.dp(2f), ink.dp(2f))
        }
        y += mapH
        val lineH = ink.unit * 1.1f
        val overlapText = when {
            conflicts.isNotEmpty() -> "Overlaps sound ${conflicts.sorted().joinToString(", ") { hex2(it) }}: played at once, they overwrite each other's waves"
            others.isEmpty() -> "No other N163 sound uses the RAM"
            else -> "No overlap with the other ${others.size} N163 sound${if (others.size == 1) "" else "s"}"
        }
        ink.label(c, ink.fit(overlapText, r.width(), small), r.left, y, lineH, if (conflicts.isNotEmpty()) Palette.ERROR else Palette.FAINT, size = small)
        y += lineH

        // the sequence of waves
        val count = w.waves.size
        val seqH = ink.unit * 2.4f
        val seq = Rect(r.left, y + ink.dp(2f), r.right, y + seqH)
        thumbs.clear()
        val boxW = (seq.width() / count).coerceIn(ink.unit * 1.8f, ink.unit * 3.6f)
        val fit = maxOf(1, (seq.width() / boxW).toInt())
        thumbScroll = thumbScroll.coerceIn(maxOf(0, shown - fit + 1), shown)
        thumbScroll = thumbScroll.coerceIn(0, maxOf(0, count - fit))
        val markH = ink.dp(3f)
        val loopEnd = if (w.release >= 0) w.release else count
        for (i in thumbScroll until minOf(count, thumbScroll + fit)) {
            val bx = seq.left + (i - thumbScroll) * boxW
            val box = Rect(bx + ink.dp(1f), seq.top + markH + ink.dp(1f), bx + boxW - ink.dp(1f), seq.bottom)
            val on = i == shown
            if (w.loop in 0..i && i < loopEnd) ink.rect(c, bx, seq.top, bx + boxW, seq.top + markH, Palette.LISTEN)
            ink.rect(c, box, if (on) Palette.CURSOR_FILL else Palette.BG)
            miniWave(c, box, w.waves[i], if (on) col else Palette.alpha(col, 140))
            if (i == w.release) ink.line(c, bx, seq.top, bx, seq.bottom, Palette.WRITE, ink.dp(2f))
            ink.mono(c, (i + 1).toString(), box.left + ink.dp(2f), box.top, tiny * 1.3f, if (on) Palette.TEXT else Palette.FAINT, size = tiny)
            ink.label(c, "×${w.repeat.getOrElse(i) { 1 }}", box.right - ink.dp(2f), box.bottom - tiny * 1.3f, tiny * 1.3f, Palette.FAINT,
                size = tiny, align = Align.RIGHT)
            if (on) {
                val focus = row == Row.WAVE_INDEX || row == Row.WAVE_REPEAT
                ink.outline(c, box, if (focus) cursorColor() else col, ink.dp(if (focus) 2f else 1f), ink.dp(2f))
            }
            thumbs += box to i
        }
        y += seqH + ink.unit * 0.3f

        // the shown wave's samples
        val box = Rect(r.left, y, r.right, r.bottom)
        if (box.height() < ink.unit * 3f) { waveRect = Rect(); return }
        ink.round(c, box, ink.dp(4f), Palette.LANE)
        val focused = row == Row.WAVE_SAMPLES
        val samples = w.waves[shown]
        val title = "Wave ${shown + 1} of $count · sample $sample of ${w.size}: ${samples[sample]}"
        ink.label(c, ink.fit(title, box.width() - ink.unit, small), box.left + ink.unit * 0.5f, box.top, ink.unit * 1.3f,
            if (focused) Palette.DIM else Palette.FAINT, size = small)
        val labelH = ink.unit
        val inner = Rect(box.left + ink.unit * 1.6f, box.top + ink.unit * 1.4f, box.right - ink.unit * 0.5f, box.bottom - ink.unit * 0.2f - labelH)
        if (inner.height() <= 0f || inner.width() <= 0f) { waveRect = Rect(); return }
        waveRect = inner
        waveCellW = inner.width() / w.size
        val cellH = inner.height() / 16f
        for (level in listOf(15, 8, 0)) {
            val yl = inner.bottom - (level + 0.5f) * cellH
            ink.mono(c, level.toString(), inner.left - ink.unit * 0.3f, yl - tiny, tiny * 2, Palette.FAINT, size = tiny, align = Align.RIGHT)
        }
        val centre = inner.bottom - 8 * cellH
        ink.line(c, inner.left, centre, inner.right, centre, Palette.BAR, ink.dp(1f))
        val gap = if (waveCellW >= ink.dp(4f)) maxOf(1f, waveCellW * 0.12f) else 0f
        val barColor = Palette.alpha(col, 170)
        for (j in 0 until w.size) {
            val v = samples[j]
            val x = inner.left + j * waveCellW
            val top = if (v >= N163Waves.CENTRE) inner.bottom - (v + 1) * cellH else centre
            val bottom = if (v >= N163Waves.CENTRE) centre else inner.bottom - v * cellH
            val on = j == sample && focused
            ink.rect(c, x + gap, top, maxOf(x + gap + 1f, x + waveCellW - gap), bottom, if (on) (if (ctl.editHeld) Palette.WRITE else col) else barColor)
        }
        val cx = inner.left + sample * waveCellW
        ink.outline(c, Rect(cx, inner.top, cx + maxOf(waveCellW, ink.dp(2f)), inner.bottom), if (focused) cursorColor() else Palette.FAINT,
            ink.dp(if (focused) 2f else 1f), ink.dp(2f))
        var every = 1
        while (every * waveCellW < ink.unit * 1.4f && every < w.size) every *= 2
        for (j in 0 until w.size) {
            if (j % every != 0 && j != sample) continue
            ink.mono(c, j.toString(), inner.left + (j + 0.5f) * waveCellW, inner.bottom + ink.dp(2f), labelH,
                if (j == sample) Palette.TEXT else Palette.FAINT, size = tiny, align = Align.CENTER)
        }
    }

    /** One wave as a small dot plot of its levels in [box]. */
    private fun miniWave(c: Surface, box: Rect, samples: List<Int>, color: Int) {
        val inner = Rect(box.left + ink.dp(2f), box.top + ink.dp(2f), box.right - ink.dp(2f), box.bottom - ink.dp(2f))
        if (inner.width() <= 0f || inner.height() <= 0f || samples.isEmpty()) return
        val columns = maxOf(1, minOf(samples.size, (inner.width() / ink.dp(1.5f)).toInt()))
        val cw = inner.width() / columns
        val dot = maxOf(ink.dp(1.5f), inner.height() / 16f)
        for (k in 0 until columns) {
            val v = samples[k * samples.size / columns]
            val yv = inner.bottom - inner.height() * (v + 1) / 16f
            ink.rect(c, inner.left + k * cw, yv, inner.left + (k + 1) * cw, minOf(inner.bottom, yv + dot), color)
        }
    }

    // ---- touch: slots, tabs, drawing on the steps and on the waves --------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = NesEnvelopeKind.VOLUME

    /** True while a touch draws on an N163 wave ([drawWave] of [drawSlot]) rather than on an envelope. */
    private var drawingWave = false
    private var drawWave = 0

    override fun down(x: Float, y: Float): Boolean {
        if (NesInstrumentView.dpcmPage(st)) dpcmDown(x, y)?.let { return it }
        fmDown(x, y)?.let { return it }
        waveDown(x, y)?.let { return it }
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
        if (plot.contains(x, y)) {
            st.instField = NesInstrumentView.rows(st, song).indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = NesInstrumentView.kind(st)
            drawingWave = false
            drawing = session.beginGesture("Draw ${drawKind.label.lowercase()}")
            drawAt(x, y)
            return true
        }
        return false
    }

    /** Sets the step under [x] to the value at [y]; past the end it appends one step at a time. */
    private fun drawAt(x: Float, y: Float) {
        val ins = song.nesInstrument(drawSlot) ?: return
        val e = drawKind.of(ins)
        val i = plot.stepAt(x, NesInstrumentView.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        session.editNesEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { NesEnvelopes.withValue(drawKind, it, i, value, ins.chip) }
    }

    /** Waves page taps: a wave box shows that wave; on the samples a touch draws (one undo step per stroke). Null: not on them. */
    private fun waveDown(x: Float, y: Float): Boolean? {
        if (!NesInstrumentView.wavePage(song, st)) return null
        val rows = NesInstrumentView.rows(st, song)
        thumbs.firstOrNull { it.first.contains(x, y) }?.let { (_, i) ->
            st.wave = i
            st.instField = rows.indexOf(Row.WAVE_INDEX)
            return true
        }
        if (!waveRect.contains(x, y) || waveCellW <= 0f) return null
        val w = NesInstrumentView.n163Wave(song, st) ?: return null
        st.instField = rows.indexOf(Row.WAVE_SAMPLES)
        drawSlot = st.instrument
        drawWave = NesInstrumentView.waveIndex(st, w)
        drawingWave = true
        drawing = session.beginGesture("Draw wave")
        drawWaveAt(x, y)
        return true
    }

    /** Sets the sample under [x] to the level at [y]. */
    private fun drawWaveAt(x: Float, y: Float) {
        val w = song.nesInstrument(drawSlot)?.takeIf { it.chip == NesChip.N163 }?.wave ?: return
        val j = ((x - waveRect.left) / waveCellW).toInt().coerceIn(0, w.size - 1)
        val level = ((waveRect.bottom - y) / (waveRect.height() / 16f)).toInt().coerceIn(N163Wave.SAMPLE)
        st.waveCol = j
        val ram = N163Waves.ram(song)
        session.editNesWave(drawSlot, "Draw wave") { N163Waves.withSample(it, drawWave, j, level, ram) }
    }

    override fun move(x: Float, y: Float) {
        val g = drawing ?: return
        if (!session.isGestureOpen(g)) { drawing = null; drawingWave = false; return }
        if (drawingWave) drawWaveAt(x, y) else drawAt(x, y)
    }

    override fun up(x: Float, y: Float) = finish()
    override fun cancel() = finish()

    private fun finish() {
        drawing?.let { session.endGesture(it) }
        drawing = null
        drawingWave = false
    }

    /** DPCM page taps: a mapped key shows it, a sample selects it, Add opens the add menu. Null: not on them. */
    private fun dpcmDown(x: Float, y: Float): Boolean? {
        val rows = NesInstrumentView.rows(st, song)
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
