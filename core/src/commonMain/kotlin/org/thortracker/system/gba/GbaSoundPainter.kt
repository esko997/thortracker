package org.thortracker.system.gba

import org.thortracker.editor.GestureToken
import org.thortracker.editor.ViewKind
import org.thortracker.model.Song
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.gba.GbaSoundView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface
import kotlin.math.abs
import kotlin.math.roundToInt

/** Indigo shell, silver controls, a wide PCM display; the shared instrument bank is deliberately unthemed. */
internal class GbaSoundPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val indigo = 0xff6f68ac.toInt()
    private val silver = 0xffc4c9da.toInt()
    private val screen = 0xff202636.toInt()
    private val loopColor = 0xff7ac8c4.toInt()
    private val displayInk = 0xffeef0f8.toInt()
    private val modes = Array(2) { Rect() }
    private val previous = Rect()
    private val next = Rect()
    private var graph = Rect()
    private var graphKind = 0
    private var graphTickMax = 12
    private val keys = ArrayList<Pair<Rect, Int>>()
    private var drawnSong: Song? = null
    private var drawnSlot = -1
    private var drawnField = -1
    private var drawnRevision = -1
    private var listScroll = 0
    private var gesture: GestureToken? = null
    private var expected: Song? = null
    private var dragSlot = -1
    private var dragSample = -1
    private var dragPage = -1
    private var dragRevision = -1
    private var dragPoint = -1
    private var dragRow = Row.LOOP_START
    private var dragGraph = Rect()
    private val cancelPointer: () -> Unit = { cancel() }
    private val lows = IntArray(256)
    private val highs = IntArray(256)
    private var cachedSample: GbaSample? = null
    private var cachedColumns = 0

    internal fun modeTarget(advanced: Boolean) = modes[if (advanced) 1 else 0]
    internal fun fieldTarget(row: Row): Rect? = rowRects.firstOrNull { it.second == GbaSoundView.rows(st).indexOf(row) }?.first
    internal fun waveformTarget() = graph
    internal fun navigationTarget(forward: Boolean) = if (forward) next else previous

    override fun draw(c: Surface, area: Rect) {
        drawnSong = null
        val advanced = GbaSoundView.advanced(st)
        val top = header(c, area, "Sound ${org.thortracker.editor.EditorSession.hex2(st.instrument)}",
            "GBA sampler · ${usageText(st.instrument)}")
        val left = drawBank(c, area, top, GbaSound.SLOTS, ink.palette.channel(st.listenChannel), false) { slot ->
            song.gba.instrument(slot)?.let { if ((it.name.isEmpty() || it.name == "Empty") && it.keymap.all { key -> key < 0 }) null else it.name.ifEmpty { "(unnamed)" } }
        }
        val pad = ink.unit * 0.65f
        val body = Rect(left.right + pad, top + pad * 0.4f, area.right - pad, area.bottom - pad * 0.4f)
        rowRects.clear(); tabs.clear(); keys.clear()
        graph = Rect()
        if (body.width() <= 0 || body.height() <= 0) return
        val modeH = ink.unit * 1.65f
        for (i in 0..1) {
            val r = modes[i]
            r.set(body.left + body.width() * i / 2, body.top, body.left + body.width() * (i + 1) / 2, body.top + modeH)
            val on = advanced == (i == 1)
            if (on) ink.rect(c, r, Palette.mix(ink.palette.BG, indigo, 0.25f))
            ink.label(c, if (i == 0) "Sound" else "Advanced", r.centerX(), r.top, modeH,
                if (on) ink.palette.TEXT else ink.palette.DIM, on, ink.unit, Align.CENTER)
            c.line(r.left, r.bottom, r.right, r.bottom, if (on) indigo else ink.palette.RULE, ink.dp(if (on) 3f else 1f))
            if (GbaSoundView.row(st) == Row.MODE && on) ink.outline(c, r, cursorColor(), ink.dp(2f))
        }
        val footerH = ink.unit * 1.65f
        val available = (body.height() - modeH - footerH).coerceAtLeast(0f)
        val graphH = minOf(ink.unit * (if (advanced) 6f else 7f), available * 0.36f)
        var y = body.top + modeH + ink.unit * 0.35f
        if (graphH >= ink.unit * 2.5f) {
            graph = Rect(body.left, y, body.right, y + graphH)
            graphKind = if (!advanced || st.envelope == 0) 0 else if (st.envelope == 1) 1 else 2
            when (graphKind) { 0 -> drawWave(c, graph); 1 -> drawKeys(c, graph); else -> drawEnvelope(c, graph) }
            y = graph.bottom + ink.unit * 0.35f
        }
        val rows = GbaSoundView.rows(st).filter { it != Row.MODE }
        val rowH = ink.unit * 1.7f
        val visible = maxOf(1, ((body.bottom - footerH - y) / rowH).toInt())
        val current = rows.indexOf(GbaSoundView.row(st)).coerceAtLeast(0)
        listScroll = listScroll.coerceIn(maxOf(0, current - visible + 1), current)
        listScroll = listScroll.coerceIn(0, maxOf(0, rows.size - visible))
        rightEdge = body.right - rowH
        for (i in listScroll until minOf(rows.size, listScroll + visible)) {
            val row = rows[i]
            val r = Rect(body.left, y, body.right, minOf(y + rowH, body.bottom - footerH))
            if (r.height() <= 0) break
            val selected = GbaSoundView.row(st) == row
            if (selected) ink.rect(c, r, ink.palette.CURSOR_FILL)
            val numerical = GbaSoundView.range(ctl, row)?.let { it.first != it.last } == true
            val x = r.left + if (numerical) rowH else ink.unit * 0.25f
            val vx = x + minOf(ink.unit * 8.3f, (r.width() - 2 * rowH) * 0.48f)
            setting(c, row.label, text(row), x, vx, y, r.height(), selected, false)
            if (numerical) {
                ink.label(c, "−", r.left + rowH / 2, y, r.height(), ink.palette.DIM, align = Align.CENTER)
                ink.label(c, "+", r.right - rowH / 2, y, r.height(), ink.palette.DIM, align = Align.CENTER)
            }
            if (selected) ink.outline(c, r, cursorColor(), ink.dp(2f), ink.dp(2f))
            rowRects += r to GbaSoundView.rows(st).indexOf(row)
            y += rowH
        }
        val nav = footerH * 1.3f
        previous.set(body.left, body.bottom - footerH, body.left + nav, body.bottom)
        next.set(body.right - nav, body.bottom - footerH, body.right, body.bottom)
        ink.label(c, "‹", previous.centerX(), previous.top, footerH, ink.palette.TEXT, align = Align.CENTER)
        ink.label(c, "›", next.centerX(), next.top, footerH, ink.palette.TEXT, align = Align.CENTER)
        ink.label(c, ink.fit(guidance(GbaSoundView.row(st)), body.width() - nav * 2 - ink.unit * 0.4f, ink.unit * 0.75f),
            previous.right + ink.unit * 0.2f, previous.top, footerH, ink.palette.DIM, size = ink.unit * 0.75f)
        drawnSong = song; drawnSlot = st.instrument; drawnField = st.instField; drawnRevision = st.soundPanel("gba-maxmod").revision
    }

    private fun text(row: Row): String {
        val sample = GbaSoundView.sample(song, st)
        val value = GbaSoundView.value(ctl, row)
        return when (row) {
            Row.MODE -> if (GbaSoundView.advanced(st)) "Advanced" else "Sound"
            Row.NAME -> GbaSoundView.instrument(song, st).name.ifEmpty { "(unnamed)" }
            Row.PAGE -> GbaSoundView.pages[st.envelope]
            Row.LISTEN -> "${value + 1}"
            Row.KEY, Row.ROOT -> song.system.noteName(value)
            Row.SAMPLE -> sample?.let { "$value ${it.name}" } ?: "Empty; open tools"
            Row.MAP -> if (value < 0) "Silent" else "$value ${song.gba.samples.getOrNull(value)?.name.orEmpty()}"
            Row.LOOP, Row.ENABLED -> if (value == 0) "Off" else "On"
            Row.SUSTAIN, Row.ENV_LOOP_START, Row.ENV_LOOP_END -> if (value < 0) "Off" else value.toString()
            Row.FADEOUT -> "${GbaSoundView.instrument(song, st).fadeout} (${value} ×32)"
            Row.TOOLS -> "Import / generate / shape…"
            Row.ADD_POINT -> "Append (12 max)"
            Row.REMOVE_POINT -> "Remove selected (2 min)"
            else -> value.toString()
        }
    }
    private fun guidance(row: Row) = when (row) {
        Row.SAMPLE -> "Browse PCM; Key mapping assigns it"
        Row.MAP -> "−1 = silent; 16 distinct samples per sound"
        Row.KEY -> "This key is used for mapping and held Listen"
        Row.RATE -> "Changes pitch; Tools / Resample preserves pitch"
        Row.PAN -> "0 left, 128 centre, 255 right; Maxmod rounds odd values"
        Row.LOOP_START, Row.LOOP_END, Row.LOOP -> "Drag waveform loop edges; edits affect all users"
        Row.TOOLS -> "All sample tools undo in one step"
        Row.FADEOUT -> "OFF release; export precision is 32"
        Row.TICK, Row.VALUE, Row.POINT -> "Drag a point; tick 0 stays fixed"
        Row.MODE -> "Presentation only; the patch stays unchanged"
        else -> "← → parameter   A+← → edit   X hold note"
    }
    private fun drawWave(c: Surface, r: Rect) {
        ink.round(c, r, ink.dp(5f), screen)
        val sample = GbaSoundView.sample(song, st)
        val titleH = ink.unit * 1.3f
        if (sample == null || sample.pcm.isEmpty()) {
            ink.label(c, "Add a WAV or original sample in Tools", r.centerX(), r.top, r.height(), silver,
                size = ink.unit * 0.85f, align = Align.CENTER)
            return
        }
        ink.label(c, ink.fit("${sample.pcm.size} frames / ${sample.sampleRate} Hz · ${sample.name}", r.width() - ink.unit, ink.unit * 0.75f),
            r.left + ink.unit * 0.5f, r.top, titleH, silver, size = ink.unit * 0.75f)
        val g = Rect(r.left + ink.unit * 0.5f, r.top + titleH, r.right - ink.unit * 0.5f, r.bottom - ink.unit * 0.5f)
        if (sample.loopEnd > sample.loopStart) {
            val x1 = g.left + g.width() * sample.loopStart / sample.pcm.size
            val x2 = g.left + g.width() * sample.loopEnd / sample.pcm.size
            ink.rect(c, x1, g.top, x2, g.bottom, Palette.alpha(loopColor, 40))
            c.line(x1, g.top, x1, g.bottom, loopColor, ink.dp(2f))
            c.line(x2, g.top, x2, g.bottom, loopColor, ink.dp(2f))
        }
        c.line(g.left, g.centerY(), g.right, g.centerY(), Palette.alpha(silver, 70), ink.dp(1f))
        val columns = g.width().toInt().coerceIn(1, 256)
        if (sample !== cachedSample || columns != cachedColumns) {
            for (i in 0 until columns) {
                val start = i * sample.pcm.size / columns
                val end = maxOf(start + 1, (i + 1) * sample.pcm.size / columns).coerceAtMost(sample.pcm.size)
                var low = 127; var high = -128
                for (j in start until end) { low = minOf(low, sample.pcm[j]); high = maxOf(high, sample.pcm[j]) }
                lows[i] = low; highs[i] = high
            }
            cachedSample = sample; cachedColumns = columns
        }
        for (i in 0 until columns) {
            val x = g.left + g.width() * i / columns
            c.line(x, g.centerY() - highs[i] * g.height() / 256f, x, g.centerY() - lows[i] * g.height() / 256f,
                displayInk, maxOf(1f, g.width() / columns))
        }
    }
    private fun drawKeys(c: Surface, r: Rect) {
        ink.round(c, r, ink.dp(4f), screen)
        val ins = GbaSoundView.instrument(song, st)
        val first = st.dpcmKey / 12 * 12
        val width = r.width() / 12
        for (i in 0..11) {
            val key = first + i
            val rect = Rect(r.left + i * width, r.top, r.left + (i + 1) * width, r.bottom)
            if (key == st.dpcmKey) ink.rect(c, rect, Palette.alpha(indigo, 120))
            c.line(rect.right, rect.top, rect.right, rect.bottom, silver, ink.dp(1f))
            ink.label(c, song.system.noteName(key), rect.centerX(), rect.top, rect.height() / 2, silver,
                size = ink.unit * 0.7f, align = Align.CENTER)
            ink.label(c, ins.keymap[key].let { if (it < 0) "−" else it.toString() }, rect.centerX(), rect.centerY(), rect.height() / 2,
                loopColor, size = ink.unit * 0.8f, align = Align.CENTER)
            keys += rect to key
        }
    }
    private fun drawEnvelope(c: Surface, r: Rect) {
        ink.round(c, r, ink.dp(4f), screen)
        val env = GbaSoundView.envelope(song, st)
        graphTickMax = maxOf(12, env.points.lastOrNull()?.tick ?: 12)
        val g = Rect(r.left + ink.unit * 0.5f, r.top + ink.unit * 0.5f, r.right - ink.unit * 0.5f, r.bottom - ink.unit * 0.5f)
        var lastX = g.left; var lastY = g.bottom
        for ((index, point) in env.points.withIndex()) {
            val x = g.left + point.tick * g.width() / graphTickMax
            val y = g.bottom - point.value * g.height() / 64
            if (index > 0) c.line(lastX, lastY, x, y, if (env.enabled) loopColor else silver, ink.dp(2f))
            val size = ink.dp(if (index == st.envStep) 4f else 2f)
            ink.rect(c, x - size, y - size, x + size, y + size, if (index == st.envStep) displayInk else silver)
            if (env.sustain == index) c.line(x, g.top, x, g.bottom, Palette.alpha(indigo, 170), ink.dp(1f))
            lastX = x; lastY = y
        }
    }

    override fun down(x: Float, y: Float): Boolean {
        cancel()
        val panel = st.soundPanel("gba-maxmod")
        if (drawnSong !== song || drawnSlot != st.instrument || drawnField != st.instField || drawnRevision != panel.revision) return true
        if (ctl.editHeld) { ctl.useEdit(); panel.editBlocked = true }
        for (i in 0..1) if (modes[i].contains(x, y)) { GbaSoundView.selectMode(ctl, i == 1); return true }
        if (slotRect.contains(x, y) && slotH > 0) {
            val slot = (slotScroll + ((y - slotTop) / slotH).toInt() + 1).coerceIn(1, GbaSound.SLOTS)
            GbaSoundView.moveSlot(ctl, slot - st.instrument); GbaSoundView.publish(ctl); return true
        }
        if (previous.contains(x, y) || next.contains(x, y)) {
            st.instField = (st.instField + if (next.contains(x, y)) 1 else GbaSoundView.rows(st).size - 1).mod(GbaSoundView.rows(st).size)
            return true
        }
        keys.firstOrNull { it.first.contains(x, y) }?.let { (_, key) ->
            GbaSoundView.set(ctl, Row.KEY, key); return true
        }
        rowRects.firstOrNull { it.first.contains(x, y) }?.let { (rect, field) ->
            st.instField = field
            val row = GbaSoundView.row(st)
            if (GbaSoundView.range(ctl, row)?.let { it.first != it.last } == true && (x < rect.left + rect.height() || x > rect.right - rect.height())) {
                GbaSoundView.adjust(ctl, if (x < rect.centerX()) -1 else 1)
            } else GbaSoundView.activate(ctl)
            return true
        }
        if (graph.contains(x, y) && graphKind != 1) {
            val sample = GbaSoundView.sample(song, st)
            if (graphKind == 0 && (sample == null || sample.loopEnd <= sample.loopStart)) {
                session.note("Enable Forward loop before dragging its edges"); return true
            }
            dragGraph = Rect(graph.left + ink.unit * 0.5f, graph.top + (if (graphKind == 0) ink.unit * 1.3f else ink.unit * 0.5f),
                graph.right - ink.unit * 0.5f, graph.bottom - ink.unit * 0.5f)
            if (dragGraph.width() <= 0 || dragGraph.height() <= 0) return true
            dragSlot = st.instrument; dragSample = st.dpcmSample; dragPage = st.envelope; dragRevision = panel.revision
            if (graphKind == 0) {
                val frame = ((x - dragGraph.left) / dragGraph.width() * sample!!.pcm.size).roundToInt()
                dragRow = if (abs(frame - sample.loopStart) <= abs(frame - sample.loopEnd)) Row.LOOP_START else Row.LOOP_END
            } else {
                val env = GbaSoundView.envelope(song, st)
                val tick = (x - dragGraph.left) / dragGraph.width() * graphTickMax
                dragPoint = env.points.indices.minByOrNull { abs(env.points[it].tick - tick) } ?: return true
                st.envStep = dragPoint
            }
            gesture = session.beginGesture(if (graphKind == 0) "Move loop edge" else "Shape envelope")
            expected = song
            panel.cancelTouch = cancelPointer
            move(x, y)
            return true
        }
        return false
    }
    override fun move(x: Float, y: Float) {
        val token = gesture ?: return
        if (!session.isGestureOpen(token) || expected !== song || st.instrument != dragSlot || st.dpcmSample != dragSample ||
            st.envelope != dragPage || st.soundPanel("gba-maxmod").revision != dragRevision || st.kind != ViewKind.INSTRUMENT) { cancel(); return }
        if (graphKind == 0) {
            val sample = GbaSoundView.sample(song, st) ?: return
            GbaSoundView.set(ctl, dragRow, ((x - dragGraph.left) / dragGraph.width() * sample.pcm.size).roundToInt())
        } else {
            st.envStep = dragPoint
            GbaSoundView.set(ctl, Row.TICK, ((x - dragGraph.left) / dragGraph.width() * graphTickMax).roundToInt())
            GbaSoundView.set(ctl, Row.VALUE, ((dragGraph.bottom - y) / dragGraph.height() * 64).roundToInt())
        }
        expected = song
    }
    override fun up(x: Float, y: Float) = cancel()
    override fun cancel() {
        gesture?.let { session.endGesture(it) }
        gesture = null; expected = null
        if (st.soundPanel("gba-maxmod").cancelTouch === cancelPointer) st.soundPanel("gba-maxmod").cancelTouch = null
    }
}
