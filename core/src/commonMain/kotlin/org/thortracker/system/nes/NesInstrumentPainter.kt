package org.thortracker.system.nes

import kotlin.math.roundToInt
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.model.NumberText
import org.thortracker.system.ChannelKind
import org.thortracker.system.fm.FmEnvelopeSketch
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.fm.FmRouting
import org.thortracker.system.fm.OpnTiming
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.nes.NesInstrumentView.Row
import org.thortracker.system.ym2612.YmCurated
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.IdentityCache
import org.thortracker.ui.Surface

/**
 * NES instrument editor. Left: the 64 slots of the shared bank with how often each is used. Right: name, on songs
 * with an expansion chip the sound's chip (and a VRC6 sound's saw volume), the channel Listen plays on, page tabs,
 * and on an envelope page the shown envelope's length / loop / release / pitch mode, what each channel does with it,
 * and its steps as bars with the loop region and release point; on the DPCM page the shown key's mapping, the
 * sound's mapped keys and the song's samples with the selected one's waveform. An EPSM sound's SSG envelope page draws
 * the hardware envelope's shape, its FM pages the shared FM editor's algorithm diagram, envelope sketch and Expert grid
 * ([FmInstrumentPainter]), its rhythm page the six ROM drums with this sound's level and pan and the ROM's status.
 */
internal class NesInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private companion object {
        /** DPCM page rows drawn inside the sample box rather than as setting rows. */
        val BOX_ROWS = setOf(Row.SAMPLES, Row.SAMPLE_NAME, Row.SAMPLE_LEVEL, Row.ADD)

        /** How long the FM envelope sketch holds the key. */
        const val HOLD_MS = 500f
    }

    private val plot = EnvelopePlot(ink)

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = NesInstrumentView.sound(s, st) ?: return
        val kind = NesInstrumentView.kind(s, st)
        val env = kind.of(ins)
        val channels = s.system.channels
        val listen = channels.getOrNull(st.listenChannel)
        val col = Palette.channel(st.listenChannel)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "Instrument bank$locked  ${usageText(st.instrument)}")
        val row = NesInstrumentView.row(st, s)
        rowRects.clear()
        gridCells.clear()

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
        val dpcm = NesInstrumentView.dpcmPage(s, st)
        val ep = NesInstrumentView.epsmPage(s, st)
        val fmPage = ep == NesInstrumentView.EPSM_FM_PAGE || ep == NesInstrumentView.EPSM_EXPERT_PAGE
        val allRows = NesInstrumentView.rows(st, s)
        // The FM pages: rows beside the drawings when the pane is wide, and shrinking (down to 1.15 units) before the
        // drawings below them lose their room; every other page keeps full-width 1.5-unit rows.
        val side = fmPage && rw >= ink.unit * 50f
        val colW = if (side) minOf(rw * 0.5f, ink.unit * 30f) else rw
        rightEdge = rx + colW
        val rowH = if (!fmPage) ink.unit * 1.5f else {
            val shown = allRows.count { it != Row.SLOT }
            val reserve = if (side) 0f else ink.unit * 9f
            ((area.bottom - top - ink.unit * 1.1f - reserve) / (shown + 1.4f)).coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        }
        val vx = rx + ink.unit * 6.5f
        var y = top + ink.unit * 0.3f
        val mapping = if (dpcm) ins.dpcm[st.dpcmKey] else null
        var gridHeaderDrawn = false
        for (r in allRows) {
            if (r == Row.SLOT || r == Row.STEPS || r in BOX_ROWS) continue
            val sel = r == row
            if (r in NesEpsmPage.GRID) {
                if (!gridHeaderDrawn) { y = drawGridHeader(c, rx, vx, colW, y, rowH, col, { true }, { ins.epsm.isCarrier(it) }); gridHeaderDrawn = true }
                drawGridRow(c, NesEpsmPage.label(r), allRows.indexOf(r), rx, vx, colW, y, rowH, sel, NesEpsmPage.operator(st), { true }) { op ->
                    NesEpsmPage.gridText(ins.epsm.operators[op], r)
                }
                y += rowH
                continue
            }
            val h = if (r == Row.ENVELOPE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + colW + ink.unit * 0.4f, y + h, Palette.CURSOR_FILL)
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
                Row.CHIP -> plainSetting(c, "Chip", when (ins.chip) {
                    NesChip.APU -> "2A03: Pulse 1/2, Triangle, Noise, DPCM"
                    NesChip.VRC6 -> "VRC6: VRC6 Pulse 1/2 and Saw"
                    NesChip.EPSM -> "EPSM: SSG 1-3, FM 1-6, six rhythm drums"
                }, rx, vx, y, h, sel, false)
                Row.SAW -> plainSetting(c, "Saw volume", "${ins.sawVolume.label}: ${NesInstrumentView.sawText(ins.sawVolume)}", rx, vx, y, h, sel,
                    ins.sawVolume == Vrc6SawVolume.HALF)
                Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    val name = listen?.name ?: ""
                    ink.label(c, name, vx, y, h, col, true)
                    val note = when {
                        ep == null -> listen?.let {
                            NesInstrumentView.channelNote(kind, it.kind, ins.chip, ins.sawVolume, NesRules.chip(s.nes.expansion, st.listenChannel),
                                ins.epsmEnvelope.shape)
                        }
                        ep == NesInstrumentView.EPSM_RHYTHM_PAGE && NesRules.isEpsmRhythm(s.nes.expansion, st.listenChannel) ->
                            "fixed-pitch ROM drum: a note only triggers it"
                        else -> null
                    }
                    if (note != null) {
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rx + colW - nx, ink.unit * 0.85f), nx, y, h, Palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                Row.ENVELOPE -> drawPageTabs(c, ins, Rect(rx, y, rx + colW, y + h), listen?.kind, col)
                Row.LENGTH -> plainSetting(c, "Length", when {
                    env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${frames(env.values.size)})"
                    kind == NesEnvelopeKind.VOLUME -> "empty: full volume"
                    kind == NesEnvelopeKind.MIXER -> "empty: tone"
                    else -> "empty: not used"
                }, rx, vx, y, h, sel, env.values.isEmpty())
                Row.LOOP -> plainSetting(c, "Loop", if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}", rx, vx, y, h, sel, env.loop < 0)
                Row.RELEASE -> plainSetting(c, "Release", when {
                    env.release >= 0 -> "at step ${env.release}: after OFF the steps from here play out"
                    env.loop < 0 -> "none: OFF stops the note (a release needs a loop)"
                    else -> "none: OFF stops the note"
                }, rx, vx, y, h, sel, env.release < 0)
                Row.MODE -> plainSetting(c, "Mode", if (ins.pitchRelative) "relative: offsets add up every frame" else "absolute: offset from the note", rx, vx, y, h, sel, false)
                Row.KEY -> plainSetting(c, "Key", NesSystem.noteName(st.dpcmKey) + when {
                    ins.dpcm.isEmpty() -> "   no keys mapped yet"
                    mapping == null -> "   not mapped (${ins.dpcm.size} key${if (ins.dpcm.size == 1) "" else "s"} are)"
                    else -> "   key ${ins.dpcm.keys.count { it <= st.dpcmKey }} of ${ins.dpcm.size} mapped"
                }, rx, vx, y, h, sel, mapping == null)
                Row.SAMPLE -> plainSetting(c, "Sample", when {
                    mapping == null -> "none"
                    else -> s.nes.samples.getOrNull(mapping.sample)?.let { "${it.name}  (${it.data.size} bytes)" } ?: "missing sample ${mapping.sample + 1}"
                }, rx, vx, y, h, sel, mapping == null)
                Row.PITCH -> plainSetting(c, "Pitch", mapping?.let { "${it.pitch.toString(16).uppercase()}  ${NesSampleMenu.rateText(it.pitch)}" } ?: "—", rx, vx, y, h, sel, mapping == null)
                Row.SAMPLE_LOOP -> plainSetting(c, "Loop", when {
                    mapping == null -> "—"
                    mapping.loop -> "on: repeats until the next note or OFF"
                    else -> "off: plays once"
                }, rx, vx, y, h, sel, mapping?.loop != true)
                Row.LEVEL -> plainSetting(c, "Level", when {
                    mapping == null -> "—"
                    mapping.dmcInitial < 0 -> "the sample's own (${(s.nes.samples.getOrNull(mapping.sample)?.dmcInitial ?: DpcmSample.DEFAULT_DMC_INITIAL) * 2})"
                    else -> "${mapping.dmcInitial * 2} at note start"
                }, rx, vx, y, h, sel, mapping == null || mapping.dmcInitial < 0)
                in NesEpsmPage.ALL_ROWS -> {
                    setting(c, NesEpsmPage.label(r), NesEpsmPage.value(s, ins, r), rx, vx, y, h, sel, NesEpsmPage.dim(s, ins, r))
                    rowRects += Rect(rx, y, rx + colW, y + h) to allRows.indexOf(r)
                }
                else -> Unit
            }
            if (sel) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + colW + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }
        if (dpcm) {
            plot.hide()
            val boxTop = y + ink.unit * 0.4f
            if (area.bottom - boxTop > ink.unit * 6f) drawDpcm(c, Rect(rx, boxTop, rx + rw, area.bottom - ink.unit * 0.4f), ins, row, col)
            return
        }
        if (ep != null) {
            plot.hide()
            val box = if (side) Rect(rightEdge + pad, top + ink.unit * 0.3f, area.right - pad, area.bottom - ink.unit * 0.4f)
                else Rect(rx, y + ink.unit * 0.4f, rx + rw, area.bottom - ink.unit * 0.4f)
            if (box.height() < ink.unit * 4f) return
            when (ep) {
                NesInstrumentView.ENV_PAGE -> drawShape(c, box, ins.epsmEnvelope, col)
                NesInstrumentView.EPSM_FM_PAGE -> if (box.width() > box.height() * 1.6f) {
                    val mid = box.left + box.width() * 0.5f
                    algorithm(c, Rect(box.left, box.top, mid - ink.unit * 0.25f, box.bottom), ins.epsm, col, -1)
                    drawEnvelope(c, Rect(mid + ink.unit * 0.25f, box.top, box.right, box.bottom), fmEnvelope(ins.epsm), col)
                } else {
                    val mid = box.top + box.height() * 0.5f
                    algorithm(c, Rect(box.left, box.top, box.right, mid - ink.unit * 0.25f), ins.epsm, col, -1)
                    drawEnvelope(c, Rect(box.left, mid + ink.unit * 0.25f, box.right, box.bottom), fmEnvelope(ins.epsm), col)
                }
                NesInstrumentView.EPSM_EXPERT_PAGE -> algorithm(c, box, ins.epsm, col, NesEpsmPage.operator(st))
                else -> drawRhythm(c, box, ins, col)
            }
            return
        }
        val small = ink.unit * 0.8f
        ink.label(c, ink.fit(NesInstrumentView.envelopeSummary(kind, ins.chip), rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
        y += rowH * 0.85f

        // steps
        val plotTop = y + ink.unit * 0.3f
        if (area.bottom - plotTop > ink.unit * 4f) drawSteps(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), kind, env, listen?.kind, col, row == Row.STEPS, ins)
        else plot.hide()
    }

    /** A setting row as the NES pages have always drawn it (the value unfitted). */
    private fun plainSetting(c: Surface, label: String, value: String, x: Float, vx: Float, y: Float, h: Float, sel: Boolean, off: Boolean) {
        ink.label(c, label, x, y, h, if (sel) Palette.TEXT else Palette.DIM)
        ink.label(c, value, vx, y, h, if (sel) (if (off) Palette.DIM else Palette.TEXT) else (if (off) Palette.FAINT else Palette.DIM), sel)
    }

    /** Frames at the NES's 60 per second, as a duration. */
    private fun frames(n: Int) = "${(n * 1000.0 / 60.0).roundToInt()} ms"

    /**
     * Page tabs: each envelope with its length (greyed when the listening channel ignores it), then DPCM with its keys on
     * 2A03 sounds, or an EPSM sound's SSG envelope, FM, FM Expert and Rhythm pages.
     */
    private fun drawPageTabs(c: Surface, ins: NesInstrument, r: Rect, listen: ChannelKind?, col: Int) {
        tabs.clear()
        val n = NesInstrumentView.pages(song, st)
        val w = r.width() / n
        for (page in 0 until n) {
            val k = NesInstrumentView.pageKind(ins.chip, page)
            val t = Rect(r.left + page * w, r.top, r.left + (page + 1) * w, r.bottom)
            val on = page == st.envelope
            val ignored = listen != null && k != null && ignores(k, listen, ins)
            val color = when {
                on -> col
                ignored -> Palette.FAINT
                else -> Palette.DIM
            }
            if (on) ink.rect(c, t, Palette.mix(Palette.BG, col, 0.18f))
            ink.rect(c, t.left, t.bottom - ink.dp(if (on) 3f else 1f), t.right, t.bottom, if (on) col else Palette.RULE)
            val size = k?.of(ins)?.values?.size ?: if (ins.chip == NesChip.APU) ins.dpcm.size else 0
            val name = k?.label ?: when (page) {
                NesInstrumentView.ENV_PAGE -> if (ins.epsmEnvelope.shape == S5bShape.OFF) "SSG env" else "SSG env ✓"
                NesInstrumentView.EPSM_FM_PAGE -> "FM"
                NesInstrumentView.EPSM_EXPERT_PAGE -> "FM Expert"
                NesInstrumentView.EPSM_RHYTHM_PAGE -> "Rhythm"
                else -> "DPCM"
            }
            val label = if (size == 0) name else "$name ($size)"
            ink.label(c, ink.fit(label, w - ink.unit * 0.4f), t.centerX(), t.top, t.height(), color, on, align = Align.CENTER)
            tabs += t to page
        }
    }

    /**
     * True when [channel] plays nothing of envelope [kind] of [ins]: the triangle's volume and duty, the VRC6 saw's duty,
     * every envelope on DPCM; on the EPSM, everything but volume on the drums, the mixer and noise on FM, an SSG sound's
     * volume while its hardware envelope sets the level.
     */
    private fun ignores(kind: NesEnvelopeKind, channel: ChannelKind, ins: NesInstrument) = if (ins.chip == NesChip.EPSM) when (channel) {
        ChannelKind.SAMPLE -> kind != NesEnvelopeKind.VOLUME
        ChannelKind.VOICE -> kind == NesEnvelopeKind.MIXER || kind == NesEnvelopeKind.NOISE
        else -> kind == NesEnvelopeKind.VOLUME && ins.epsmEnvelope.shape != S5bShape.OFF
    } else {
        channel == ChannelKind.SAMPLE ||
            channel == ChannelKind.TRIANGLE && (kind == NesEnvelopeKind.DUTY || kind == NesEnvelopeKind.VOLUME) ||
            channel == ChannelKind.SAW && kind == NesEnvelopeKind.DUTY
    }

    // ---- EPSM pages -----------------------------------------------------------------------------------

    /** The FM patch's routing (the Mega Drive editor's OPN diagrams), operator [focus] outlined (-1: none). */
    private fun algorithm(c: Surface, r: Rect, p: EpsmPatch, col: Int, focus: Int) {
        val a = YmCurated.ALGORITHMS[p.algorithm.coerceIn(0, 7)]
        drawAlgorithm(c, r, "Algorithm ${a.number} ${a.name} ${a.diagram} · fb ${p.feedback}", FmRouting.opn(a.number, a.edges) { p.isCarrier(it) },
            p.feedback, col, focus,
            { op -> p.operators[op].let { "×${YmCurated.multText(it.mul)}" + if (YmCurated.detuneSteps(it.dt) != 0) "(${YmCurated.detuneText(it.dt)})" else "" } }, { 0 })
    }

    /** The first carrier's envelope in dB at the YMF288's envelope clock: attack, decay to SL, held while falling at D2R, release. */
    private fun fmEnvelope(p: EpsmPatch): FmEnvelopeSketch {
        val t = NesEpsmPage.TIMING
        val k = YmCurated.controls(p.asYm())
        val car = p.operators[(0..3).first { p.isCarrier(it) }]
        val sustainDb = OpnTiming.slDb(car.sl)
        val holdFall = if (car.d2r == 0) 0f else 96f * HOLD_MS / t.fallMs(2 * car.d2r, 96f)
        val ssg = if (car.ssgEg >= 8) car.ssgEg else -1
        val title = when {
            ssg >= 0 -> "Envelope · SSG-EG ${NesEpsmPage.ssgShape(ssg)} ${YmCurated.SSG_EG_NAMES[ssg]?.substringAfter(' ') ?: ""} until OFF"
            car.d2r > 0 -> "Envelope · decays to −${sustainDb.toInt()} dB, keeps fading while held"
            car.sl == 0 -> "Envelope · held at full level until OFF"
            else -> "Envelope · held at −${sustainDb.toInt()} dB until OFF"
        }
        return FmEnvelopeSketch(
            title = title,
            attackMs = t.attackMs(car.ar),
            decayMs = t.fallMs(2 * car.d1r, sustainDb),
            sustainDb = sustainDb,
            holdMs = HOLD_MS,
            releaseMs = t.fallMs(4 * car.rr + 2, 96f),
            toneDecayMs = if (k.toneDecay >= 0) t.fallMs(2 * k.toneDecay, 96f) else null,
            floorDb = 96f,
            heldEndDb = (sustainDb + holdFall).coerceAtMost(96f),
            ssgEg = ssg,
        )
    }

    /**
     * The SSG hardware envelope's level over five 32-step ramps from a restart (so repeats and holds both show), and the
     * sharing rules under it.
     */
    private fun drawShape(c: Surface, r: Rect, e: S5bEnvelope, col: Int) {
        val small = ink.unit * 0.8f
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val titleH = ink.unit * 1.3f
        val title = when {
            e.shape == S5bShape.OFF -> "No hardware envelope: the volume envelope sets the level"
            e.autoPitch -> "${e.shape.label}: ${EpsmSsg.octaveText(e.shape, e.octave)}"
            else -> "${e.shape.label}: ${EpsmSsg.periodText(e.shape, e.period)}"
        }
        ink.label(c, ink.fit(title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, Palette.DIM, size = small)
        val notes = listOf(
            "One generator for SSG 1-3: envelope sounds playing at once on several SSG channels share its shape and period.",
            "Each note with attack restarts it; legato notes keep it running. Its level replaces the volume envelope and Cxx.",
        )
        val lineH = ink.unit * 1.1f
        val plotRect = Rect(r.left + ink.unit * 0.5f, r.top + titleH, r.right - ink.unit * 0.5f, r.bottom - lineH * notes.size - ink.unit * 0.3f)
        if (plotRect.height() > ink.unit * 1.5f && plotRect.width() > ink.unit * 4f) {
            ink.rect(c, plotRect, Palette.BG)
            val levels = shapeLevels(e.shape)
            val dx = plotRect.width() / levels.size
            val color = if (e.shape == S5bShape.OFF) Palette.FAINT else col
            var px = plotRect.left
            var py = plotRect.bottom - plotRect.height() * levels[0] / 31f
            for ((i, level) in levels.withIndex()) {
                val x = plotRect.left + dx * (i + 1)
                val ly = plotRect.bottom - plotRect.height() * level / 31f
                ink.line(c, px, py, px, ly, color, ink.dp(2f))
                ink.line(c, px, ly, x, ly, color, ink.dp(2f))
                px = x
                py = ly
            }
        }
        var ny = r.bottom - lineH * notes.size - ink.unit * 0.2f
        for (n in notes) {
            ink.label(c, ink.fit(n, r.width() - ink.unit), r.left + ink.unit * 0.5f, ny, lineH, Palette.FAINT, size = small * 0.95f)
            ny += lineH
        }
    }

    /** The SSG envelope's levels (0..31) step by step from a restart: 160 steps (five ramps); off: full level. */
    private fun shapeLevels(shape: S5bShape): IntArray {
        if (shape == S5bShape.OFF) return IntArray(160) { 31 }
        val r = shape.register
        val cont = r and 8 != 0
        val attack = r and 4 != 0
        val alternate = r and 2 != 0
        val hold = r and 1 != 0
        var up = attack
        var level = if (attack) 0 else 31
        var held = false
        return IntArray(160) { i ->
            if (i > 0 && !held) {
                val next = level + if (up) 1 else -1
                if (next in 0..31) level = next
                else when {
                    !cont -> { level = 0; held = true }
                    hold -> { level = if (attack != alternate) 31 else 0; held = true }
                    alternate -> up = !up
                    else -> level = if (up) 0 else 31
                }
            }
            level
        }
    }

    /**
     * The rhythm page's box: the six drum channels with the level and pan this sound gives each, the rhythm ROM's state,
     * and what a drum takes from a sound.
     */
    private fun drawRhythm(c: Surface, r: Rect, ins: NesInstrument, col: Int) {
        val small = ink.unit * 0.8f
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val lineH = ink.unit * 1.15f
        var y = r.top
        val x = r.left + ink.unit * 0.5f
        val w = r.width() - ink.unit
        ink.label(c, ink.fit("Six fixed-pitch drums from the YMF288's rhythm ROM", w, small), x, y, ink.unit * 1.3f, Palette.DIM, size = small)
        y += ink.unit * 1.3f
        val expansion = song.nes.expansion
        val level = NesEpsmPage.rhythmLevel(ins)
        val pan = when {
            ins.epsm.left && ins.epsm.right -> "both"
            ins.epsm.left -> "left"
            ins.epsm.right -> "right"
            else -> "off"
        }
        for (i in NesEpsmPage.DRUM_REGISTERS.indices) {
            if (y + lineH > r.bottom - lineH * 3) break
            val ch = NesRules.EPSM_RHYTHM1 + i
            val on = ch == st.listenChannel
            ink.label(c, NesRules.channelName(expansion, ch), x, y, lineH, if (on) Palette.channel(ch) else Palette.TEXT, on, small)
            ink.label(c, ink.fit("${NesEpsmPage.DRUM_REGISTERS[i]} · level ${level * 2}/31 · pan $pan", w * 0.6f, small), x + w * 0.4f, y, lineH,
                Palette.DIM, size = small)
            y += lineH
        }
        val notes = listOf(
            (if (Ym2608RhythmRom.installed) "ROM installed (SHA-256 verified)" else "ROM missing: drums are refused until it is imported") to
                (if (Ym2608RhythmRom.installed) col else Palette.ERROR),
            "A note triggers its channel's drum; pitch, arpeggio and the FM patch do not change it." to Palette.FAINT,
        )
        var ny = r.bottom - lineH * notes.size - ink.unit * 0.2f
        for ((n, color) in notes) {
            ink.label(c, ink.fit(n, w), x, ny, lineH, color, size = small * 0.95f)
            ny += lineH
        }
    }

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

    private fun drawSteps(c: Surface, r: Rect, kind: NesEnvelopeKind, env: NesEnvelope, listen: ChannelKind?, col: Int, focused: Boolean,
                          ins: NesInstrument) {
        val chip = ins.chip
        val text = { value: Int -> NesInstrumentView.valueText(kind, value, listen, chip) }
        val title = EnvelopePlot.title(kind.label, env.values, st.envStep, text,
            ctl.keys.label(org.thortracker.editor.Action.EDIT), ctl.keys.label(org.thortracker.editor.Action.UP))
        plot.draw(c, r, title, kind, env, st.envStep, text, listen != null && ignores(kind, listen, ins), col, focused, cursorColor(), ctl.editHeld,
            kind.range(chip))
    }

    // ---- touch: slots, tabs, EPSM rows and grid, and drawing on the steps ----------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = NesEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        if (NesInstrumentView.dpcmPage(song, st)) dpcmDown(x, y)?.let { return it }
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
        // EPSM pages: a grid cell selects its field and operator, a setting row its row.
        gridCells.firstOrNull { it.first.contains(x, y) }?.let { (_, rowIndex, op) ->
            st.instField = rowIndex
            st.programField = op
            return true
        }
        rowRects.firstOrNull { it.first.contains(x, y) }?.let { (_, rowIndex) -> st.instField = rowIndex; return true }
        if (plot.contains(x, y)) {
            st.instField = NesInstrumentView.rows(st, song).indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = NesInstrumentView.kind(song, st)
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
