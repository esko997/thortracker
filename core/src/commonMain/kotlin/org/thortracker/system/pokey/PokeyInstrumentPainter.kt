package org.thortracker.system.pokey

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.pokey.PokeyInstrumentView.Page
import org.thortracker.system.pokey.PokeyInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * POKEY instrument editor. Left: the 64 slots (kind marked: ·16 16-bit, ·HP high-pass, ·16HP both, ·S samples; empty
 * "·") with how often each is used. Right: name, Listen channel, kind, page tabs, then the page — the Sound settings
 * beside the four POKEY channels (which ones a note on the Listen channel takes and what each does) and the tuning a
 * note really gets there; an envelope's steps (the NES editor's plot, distortions by name); or the Sample settings over
 * the song's samples (lengths, the shared memory, the selected one's levels, its name and the Add row). The bank, tabs
 * and setting rows are the shared [FmInstrumentPainter]'s.
 */
internal class PokeyInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val plot = EnvelopePlot(ink)
    private var sampleRect = Rect()
    private var sampleRowH = 0f
    private var sampleScroll = 0
    private var addRect = Rect()

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = PokeyInstrumentView.sound(s, st)
        val page = PokeyInstrumentView.page(s, st)
        val listen = PokeyInstrumentView.listenChannel(st, ins)
        val col = ink.palette.channel(listen)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = ins?.kind?.label ?: "empty slot"
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "POKEY · $kind$locked  ${usageText(st.instrument)}")
        val row = PokeyInstrumentView.row(s, st)
        val allRows = PokeyInstrumentView.rows(s, st)
        rowRects.clear()
        gridCells.clear()
        plot.hide()
        sampleRect = Rect()
        addRect = Rect()

        val left = drawBank(c, area, top, PokeySystem.SLOTS, col, row == Row.SLOT) { i ->
            s.pokey.instrument(i)?.let { it.name.ifEmpty { "(unnamed)" } + TAGS[it.kind.ordinal] }
        }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val fullW = area.right - pad - rx
        val side = fullW >= ink.unit * 50f && page == Page.SOUND
        val rw = if (side) minOf(fullW * 0.5f, ink.unit * 30f) else fullW
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT && it != Row.STEPS && it !in PokeyInstrumentView.BOX_ROWS }
        // Rows shrink (down to 1.15 units) before the drawings below them lose their room.
        val reserve = if (side) 0f else ink.unit * (if (page == Page.SAMPLE) 10f else 8f)
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve - ink.unit * 0.4f) / (rows.size + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        val pages = PokeyInstrumentView.pages(s, st)
        val tabLabels = pages.map { tabLabel(ins, it) }
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
                    listenNote(ins)?.let { note ->
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rightEdge - nx, ink.unit * 0.85f), nx, y, h, ink.palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, tabLabels, st.envelope.coerceIn(0, tabLabels.size - 1))
                r == Row.KIND -> setting(c, "Kind", ins?.let { "${it.kind.label}   plays on ${PokeyInstrumentView.channelsText(it.kind.lanes)}" }
                    ?: "empty   ${ctl.k(Action.EDIT)} makes an 8-bit sound", rx, vx, y, h, sel, ins == null)
                ins == null -> setting(c, r.label, "—", rx, vx, y, h, sel, true)
                else -> setting(c, r.label, value(ins, r, listen), rx, vx, y, h, sel, dim(ins, r, listen))
            }
            if (r != Row.PAGE) rowRects += Rect(rx, y, rx + rw, y + h) to allRows.indexOf(r)
            if (sel && r != Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            if (sel && r == Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.2f, y + 1, rx + rw + ink.unit * 0.2f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }

        page.envelope?.let { k ->
            val small = ink.unit * 0.8f
            ink.label(c, ink.fit(envelopeSummary(k), rw, small), rx, y, rowH * 0.85f, ink.palette.FAINT, size = small)
            y += rowH * 0.85f
            val plotTop = y + ink.unit * 0.3f
            if (area.bottom - plotTop > ink.unit * 4f) {
                val env = ins?.let { k.of(it) } ?: k.default
                val text = { value: Int -> k.valueText(value) }
                val title = EnvelopePlot.title(k.label, env.values, st.envStep, text, ctl.keys.label(Action.EDIT), ctl.keys.label(Action.UP))
                plot.draw(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), title, PLOT_KINDS.getValue(k), env, st.envStep, text,
                    ins == null, col, row == Row.STEPS, cursorColor(), ctl.editHeld, k.range)
            }
            return
        }
        if (page == Page.SAMPLE) {
            if (ins != null) drawSamples(c, Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f), ins, row, col, allRows)
            return
        }
        val box = if (side) Rect(rightEdge + pad, top + ink.unit * 0.3f, area.right - pad, area.bottom - ink.unit * 0.4f)
            else Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f)
        if (box.height() < ink.unit * 4f || ins == null) return
        drawChannels(c, box, ins, listen, col)
    }

    /** A page tab: envelopes carry their length, as on the NES. */
    private fun tabLabel(ins: PokeyInstrument?, p: Page): String {
        val k = p.envelope ?: return p.label
        val n = ins?.let { k.of(it).values.size } ?: 0
        return if (n == 0) p.label else "${p.label} ($n)"
    }

    /** What the Listen channel does with the sound: the channels it takes, or where it plays instead. */
    private fun listenNote(ins: PokeyInstrument?): String? {
        ins ?: return null
        if (st.listenChannel !in ins.kind.lanes) return "${ins.kind.label} plays on ${PokeyInstrumentView.channelsText(ins.kind.lanes)}"
        val taken = ins.kind.taken(st.listenChannel).drop(1)
        return if (taken.isEmpty()) null else "also takes ${PokeyInstrumentView.channelsText(taken)}"
    }

    private fun envelopeSummary(k: PokeyEnvelopeKind): String = when (k) {
        PokeyEnvelopeKind.VOLUME -> "Level 0-15 per tick (empty = 15), scaled by the channel volume. After OFF the release step on plays out"
        PokeyEnvelopeKind.ARPEGGIO -> "Semitones added to the note per tick"
        PokeyEnvelopeKind.PITCH -> "Sixteenths of a semitone per tick (relative: added up each tick)"
        PokeyEnvelopeKind.DISTORTION -> "AUDC distortion per tick: A/E pure, C buzz, 8 noise, 0 4 poly noises, 2 6 gated tone, V volume only (empty = A)"
    }

    // ---- values ---------------------------------------------------------------------------------------

    private fun presetText(ins: PokeyInstrument): String = PokeyInstrumentView.presets(song).firstOrNull { it.instrument == ins }?.name
        ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"

    private fun value(ins: PokeyInstrument, r: Row, lane: Int): String {
        val kind = PokeyInstrumentView.envelopeKind(song, st)
        val env = kind?.of(ins) ?: NesEnvelope()
        val samples = song.pokey.samples
        val sample = samples.getOrNull(ins.sample)
        return when (r) {
            Row.PRESET -> presetText(ins)
            Row.CLOCK -> when {
                !ins.fast -> "${PokeyInstrumentView.baseText(song)} base   the song's base clock (Song view, Kxx)"
                lane in ins.kind.fastLanes -> "1.79 MHz   the machine clock: exact high notes"
                else -> "1.79 MHz   only on ${PokeyInstrumentView.channelsText(ins.kind.fastLanes)}: ${PokeyInstrumentView.baseText(song)} base on POKEY ${lane + 1}"
            }
            Row.FILTER -> if (ins.difference) "${ins.filterOffset}   the clock counter is the tone's + ${ins.filterOffset}"
                else "${signed(ins.filterOffset)}   ${if (ins.filterOffset in -2..2) "slow phasing pulse" else "the clock counter = the tone's ${signed(ins.filterOffset)}"}"
            Row.DIFFERENCE -> if (ins.difference) "on   the note is the two rates' difference (saw)" else "off   high-pass pulse"
            Row.PARTNER_DIST -> PokeyDistortion.label(ins.partnerDistortion)
            Row.PARTNER_VOL -> if (ins.partnerVolume == 0) "0   silent (the usual)" else "${ins.partnerVolume}   the taken channels sound too"
            Row.MODE -> if (ins.pitchRelative) "relative: offsets add up every tick" else "absolute: offset from the note"
            Row.LENGTH -> when {
                env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${ticks(env.values.size)})"
                kind == PokeyEnvelopeKind.VOLUME -> "empty: full volume"
                kind == PokeyEnvelopeKind.DISTORTION -> "empty: A, pure tone"
                else -> "empty: not used"
            }
            Row.LOOP -> if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}"
            Row.ENV_RELEASE -> when {
                env.release < 0 -> "none: OFF releases nothing here"
                env.loop < 0 -> "at step ${env.release}: step ${env.release - 1} holds until OFF, then the rest plays"
                else -> "at step ${env.release}: after OFF the steps from here play out"
            }
            Row.SAMPLE -> when {
                samples.isEmpty() -> "none: the song has no samples (Add below)"
                sample == null -> "missing sample ${ins.sample + 1}: choose one"
                else -> "${ins.sample + 1} ${sample.name.ifEmpty { "(unnamed)" }}   ${PokeySampleMenu.levels(sample.levels.size)}"
            }
            Row.ROOT -> "${PokeySystem.noteName(ins.root)}   plays at the rate"
            Row.RATE -> "${ins.rate} Hz" + (sample?.let { "   ${PokeySampleMenu.seconds(it.levels.size, ins.rate)} on the root" } ?: "")
            Row.SAMPLE_LOOP -> when {
                ins.loop < 0 -> "off: plays once"
                sample == null -> "from level ${ins.loop}"
                else -> "from level ${ins.loop} of ${sample.levels.size}"
            }
            else -> ""
        }
    }

    private fun dim(ins: PokeyInstrument, r: Row, lane: Int): Boolean = when (r) {
        Row.CLOCK -> ins.fast && lane !in ins.kind.fastLanes
        Row.PARTNER_DIST -> ins.partnerVolume == 0
        Row.SAMPLE_LOOP -> ins.loop < 0
        else -> false
    }

    private fun tickHz(): Double = PokeySystem.ticksPerSecond(song)

    private fun ticks(n: Int) = "${(n * 1000.0 / tickHz()).toInt()} ms"

    // ---- drawings -------------------------------------------------------------------------------------

    /**
     * The four POKEY channels and what a note of [ins] on [lane] does to each (its own channel in the channel colour,
     * taken ones tinted, untouched ones faint), then the clock and the tuning a note really gets there.
     */
    private fun drawChannels(c: Surface, r: Rect, ins: PokeyInstrument, lane: Int, col: Int) {
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val small = ink.unit * 0.8f
        val titleH = ink.unit * 1.4f
        ink.label(c, ink.fit(PokeyInstrumentView.kindNote(ins.kind, lane), r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, ink.palette.DIM, size = small)
        val roles = PokeyInstrumentView.roles(ins.kind, lane)
        val gap = ink.unit * 0.4f
        val boxW = (r.width() - ink.unit - 3 * gap) / 4f
        val boxH = minOf(ink.unit * 3.2f, (r.height() - titleH) * 0.4f)
        val by = r.top + titleH
        for (ch in 0 until 4) {
            val bx = r.left + ink.unit * 0.5f + ch * (boxW + gap)
            val b = Rect(bx, by, bx + boxW, by + boxH)
            val role = roles[ch]
            val own = ch == lane
            ink.round(c, b, ink.dp(3f), when {
                own -> Palette.mix(ink.palette.BG, col, 0.30f)
                role != null -> Palette.mix(ink.palette.BG, col, 0.12f)
                else -> ink.palette.BG
            })
            if (own) ink.outline(c, b, col, ink.dp(2f), ink.dp(3f))
            ink.label(c, ink.fit("POKEY ${ch + 1}", boxW - ink.unit * 0.3f, small, true), b.centerX(), b.top, boxH * 0.5f,
                if (role != null) ink.palette.TEXT else ink.palette.FAINT, true, small, Align.CENTER)
            ink.label(c, ink.fit(role ?: "free", boxW - ink.unit * 0.3f, small), b.centerX(), b.top + boxH * 0.45f, boxH * 0.5f,
                if (own) col else if (role != null) ink.palette.DIM else ink.palette.FAINT, size = small, align = Align.CENTER)
        }
        var y = by + boxH + ink.unit * 0.4f
        val lineH = ink.unit * 1.25f
        val lines = ArrayList<Pair<String, Int>>()
        val fast = PokeyInstrumentView.fastOn(ins, lane)
        lines += "Clock on POKEY ${lane + 1}: " + (if (fast) "1.79 MHz" else "${PokeyInstrumentView.baseText(song)} base") +
            " · ${song.pokey.machine.label}" to ink.palette.DIM
        val a4 = 57
        lines += PokeyInstrumentView.tuningText(song, ins, lane, a4) to ink.palette.TEXT
        val heard = PokeyInstrumentView.listenNote(ctl, ins)
        if (heard != a4) lines += PokeyInstrumentView.tuningText(song, ins, lane, heard) to ink.palette.DIM
        val dists = if (ins.kind == PokeyKind.SAMPLE) emptyList() else ins.distortion.values.ifEmpty { listOf(PokeyDistortion.PURE) }
        if (song.pokey.base15 && !fast && dists.any { it == 2 || it == 6 })
            lines += "On the 15 kHz base, distortions C and 4 cannot play their whole patterns: other pitches" to ink.palette.PLAYHEAD
        for ((text, color) in lines) {
            if (y + lineH > r.bottom) break
            ink.label(c, ink.fit(text, r.width() - ink.unit, small), r.left + ink.unit * 0.5f, y, lineH, color, size = small)
            y += lineH
        }
    }

    /**
     * The song's samples with their lengths and the memory they share, the selected one's levels (the one the sound
     * plays in the channel colour, its loop marked), then its name and the Add row.
     */
    private fun drawSamples(c: Surface, r: Rect, ins: PokeyInstrument, row: Row, col: Int, allRows: List<Row>) {
        if (r.height() < ink.unit * 5f) return
        val samples = song.pokey.samples
        val small = ink.unit * 0.8f
        val titleH = ink.unit * 1.4f
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val used = PokeySamples.used(song)
        val over = used > PokeySample.MEMORY
        ink.label(c, ink.fit("Samples ${samples.size} of ${PokeySystem.MAX_SAMPLES} · ${used} of ${PokeySample.MEMORY} levels", r.width() * 0.62f),
            r.left + ink.unit * 0.5f, r.top, titleH, if (over) ink.palette.ERROR else ink.palette.DIM, size = small)
        val barL = r.left + r.width() * 0.64f
        val barR = r.right - ink.unit * 0.5f
        val barY = r.top + titleH * 0.42f
        ink.rect(c, barL, barY, barR, barY + ink.dp(5f), ink.palette.RULE)
        ink.rect(c, barL, barY, barL + (barR - barL) * minOf(1f, used.toFloat() / PokeySample.MEMORY), barY + ink.dp(5f), if (over) ink.palette.ERROR else col)

        val lineH = ink.unit * 1.35f
        addRect = Rect(r.left, r.bottom - lineH - ink.unit * 0.2f, r.right, r.bottom - ink.unit * 0.2f)
        val nameY = addRect.top - lineH
        val waveH = minOf(ink.unit * 3.2f, (nameY - r.top - titleH) * 0.45f)
        val waveRect = Rect(r.left + ink.unit * 0.5f, nameY - waveH - ink.unit * 0.2f, r.right - ink.unit * 0.5f, nameY - ink.unit * 0.2f)
        sampleRowH = ink.unit * 1.2f
        sampleRect = Rect(r.left, r.top + titleH, r.right, waveRect.top - ink.unit * 0.2f)
        val visible = maxOf(1, (sampleRect.height() / sampleRowH).toInt())
        sampleScroll = sampleScroll.coerceIn(maxOf(0, st.dpcmSample - visible + 1), st.dpcmSample.coerceAtLeast(0))
        sampleScroll = sampleScroll.coerceIn(0, maxOf(0, samples.size - visible))
        if (samples.isEmpty()) ink.label(c, ink.fit("None yet: use Add below", r.width() - ink.unit), r.left + ink.unit * 0.5f, sampleRect.top, sampleRowH, ink.palette.FAINT, size = small)
        for (i in sampleScroll until minOf(samples.size, sampleScroll + visible)) {
            val sample = samples[i]
            val y = sampleRect.top + (i - sampleScroll) * sampleRowH
            val on = i == st.dpcmSample
            val plays = i == ins.sample
            if (on) ink.rect(c, r.left, y, r.right, y + sampleRowH, ink.palette.CURSOR_FILL)
            ink.mono(c, (i + 1).toString().padStart(2, '0'), r.left + ink.unit * 0.5f, y, sampleRowH, if (plays) col else ink.palette.FAINT, plays, small)
            val users = song.pokeySampleUsers(i)
            val size = PokeySampleMenu.levels(sample.levels.size) + if (users.isEmpty()) "" else " · " + users.joinToString(" ") { hex2(it) }
            val sizeW = ink.labelWidth(size, small)
            ink.label(c, ink.fit(sample.name.ifEmpty { "(unnamed)" }, r.width() - ink.unit * 3.2f - sizeW), r.left + ink.unit * 2.2f, y, sampleRowH,
                if (on) ink.palette.TEXT else ink.palette.DIM, size = small)
            ink.label(c, size, r.right - ink.unit * 0.5f, y, sampleRowH, ink.palette.FAINT, size = small, align = Align.RIGHT)
            if (on && row == Row.SAMPLES) ink.outline(c, Rect(r.left + 2, y + 1, r.right - 2, y + sampleRowH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }
        rowRects += sampleRect to allRows.indexOf(Row.SAMPLES)

        // the selected sample's levels: one bar per column (the range of levels it covers)
        val sample = samples.getOrNull(st.dpcmSample)
        ink.rect(c, waveRect, ink.palette.BG)
        if (sample != null && sample.levels.isNotEmpty() && waveRect.width() > 4f && waveRect.height() > 4f) {
            val levels = sample.levels
            val columns = maxOf(1, waveRect.width().toInt() / 2)
            val per = levels.size.toFloat() / columns
            val cw = waveRect.width() / columns
            val color = if (st.dpcmSample == ins.sample) Palette.alpha(col, 200) else ink.palette.DIM
            for (k in 0 until columns) {
                val a = (k * per).toInt().coerceIn(0, levels.size - 1)
                val b = ((k + 1) * per).toInt().coerceIn(a + 1, levels.size)
                var lo = 15
                var hi = 0
                for (i in a until b) { val l = levels[i]; if (l < lo) lo = l; if (l > hi) hi = l }
                val y0 = waveRect.bottom - waveRect.height() * hi / 15f
                val y1 = waveRect.bottom - waveRect.height() * lo / 15f
                ink.rect(c, waveRect.left + k * cw, y0, waveRect.left + k * cw + maxOf(1f, cw - 0.5f), maxOf(y1, y0 + ink.dp(1f)), color)
            }
            if (st.dpcmSample == ins.sample && ins.loop in levels.indices) {
                val x = waveRect.left + waveRect.width() * ins.loop / levels.size
                ink.line(c, x, waveRect.top, x, waveRect.bottom, ink.palette.LISTEN, ink.dp(2f))
                ink.label(c, "loop", x + ink.dp(3f), waveRect.top, ink.unit, ink.palette.LISTEN, size = ink.unit * 0.65f)
            }
            ink.label(c, PokeySampleMenu.seconds(levels.size, ins.rate), waveRect.right - ink.dp(3f), waveRect.top, ink.unit, ink.palette.FAINT,
                size = ink.unit * 0.65f, align = Align.RIGHT)
        }

        val vx = r.left + ink.unit * 4.4f
        fun boxRow(y: Float, which: Row, label: String, value: String, off: Boolean) {
            val sel = row == which
            val rr = Rect(r.left + ink.unit * 0.2f, y, r.right - ink.unit * 0.2f, y + lineH)
            if (sel) ink.rect(c, rr, ink.palette.CURSOR_FILL)
            ink.label(c, label, r.left + ink.unit * 0.5f, y, lineH, if (sel) ink.palette.TEXT else ink.palette.DIM, size = small)
            ink.label(c, ink.fit(value, r.right - vx - ink.unit * 0.5f, small), vx, y, lineH,
                if (off) ink.palette.FAINT else if (sel) ink.palette.TEXT else ink.palette.DIM, sel, small)
            if (sel) ink.outline(c, rr, cursorColor(), ink.dp(2f), ink.dp(3f))
            rowRects += rr to allRows.indexOf(which)
        }
        boxRow(nameY, Row.SAMPLE_NAME, "Name", sample?.name?.ifEmpty { "(unnamed)" } ?: "—", sample == null)
        if (row == Row.SAMPLE_NAME && sample != null) {
            val before = sample.name.take(st.textPos).padEnd(st.textPos, ' ')
            val cx = vx + ink.labelWidth(before, small, true)
            val w = maxOf(ink.labelWidth(sample.name.getOrNull(st.textPos)?.toString() ?: " ", small, true), small * 0.5f)
            ink.outline(c, Rect(cx - 1, nameY + 3, cx + w + 1, nameY + lineH - 3), cursorColor(), ink.dp(2f), ink.dp(2f))
        }
        boxRow(addRect.top, Row.ADD, "Add", "starter kit or .wav file… · ${PokeySampleMenu.levels(maxOf(0, PokeySamples.free(song)))} free", false)
    }

    // ---- touch ----------------------------------------------------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = PokeyEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        val rows = PokeyInstrumentView.rows(song, st)
        if (addRect.contains(x, y)) {
            st.instField = rows.indexOf(Row.ADD)
            PokeySampleMenu.open(session, st.instrument)
            return true
        }
        if (sampleRect.contains(x, y) && sampleRowH > 0f) {
            val i = sampleScroll + ((y - sampleRect.top) / sampleRowH).toInt()
            if (i in song.pokey.samples.indices) st.dpcmSample = i
            st.instField = rows.indexOf(Row.SAMPLES)
            return true
        }
        if (downShared(x, y, PokeySystem.SLOTS, { PokeyInstrumentView.showPage(ctl, it) }, { PokeyInstrumentView.publish(ctl) })) return true
        val kind = PokeyInstrumentView.envelopeKind(song, st)
        if (kind != null && plot.contains(x, y) && song.pokeyInstrument(st.instrument) != null) {
            st.instField = rows.indexOf(Row.STEPS)
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
        val e = song.pokeyInstrument(drawSlot)?.let { drawKind.of(it) } ?: return
        val i = plot.stepAt(x, PokeyInstrumentView.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        session.editPokeyEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { PokeyEnvelopes.withValue(drawKind, it, i, value) }
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
        /** Bank column marks per [PokeyKind] (in declaration order). */
        val TAGS = listOf("", " ·16", " ·HP", " ·16HP", " ·S")

        /**
         * The plot's scale kind per envelope: [EnvelopePlot] draws signed ranges around zero and unsigned ones from the
         * bottom; the range itself is passed explicitly (distortion 0..8 has no NES counterpart).
         */
        val PLOT_KINDS = mapOf(
            PokeyEnvelopeKind.VOLUME to NesEnvelopeKind.VOLUME,
            PokeyEnvelopeKind.ARPEGGIO to NesEnvelopeKind.ARPEGGIO,
            PokeyEnvelopeKind.PITCH to NesEnvelopeKind.PITCH,
            PokeyEnvelopeKind.DISTORTION to NesEnvelopeKind.DUTY,
        )
    }
}
