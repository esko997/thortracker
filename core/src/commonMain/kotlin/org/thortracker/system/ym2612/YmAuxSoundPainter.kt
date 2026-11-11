package org.thortracker.system.ym2612

import org.thortracker.editor.ViewKind
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.system.panel.PixelLabels
import org.thortracker.ui.Align
import org.thortracker.ui.Painter
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** PSG's pulse monitor and XGM2's recording deck share only the existing slot bank and interaction grammar. */
internal class YmAuxSoundPainter(v: PaneView, private val advanced: YmInstrumentPainter) : Painter(v) {
    private val name = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val previous = Rect()
    private val next = Rect()
    private val scope = Rect()
    private val controls = Array(6) { Rect() }
    private val rails = Array(6) { Rect() }
    private val labels = Array(6) { "" }
    private val hints = Array(6) { "" }
    private val values = IntArray(6)
    private val available = BooleanArray(6)
    private var cached: YmInstrument? = null
    private var preview: YmInstrument? = null
    private var cachedSlot = -1
    private var cachedKey = -1
    private var cachedLane = -1
    private var cachedSamples: List<YmPcmSample>? = null
    private var title = ""
    private var nameSource: String? = null
    private var namePosition = -1
    private var nameWidth = -1f
    private var nameUnit = -1f
    private var nameEditing = false
    private var shownName = ""
    private var caretLeft = 0f
    private var caretWidth = 0f
    private val scopeValues = FloatArray(96)
    private class Target(val slot: Int, val index: Int, val key: Int, val revision: Int, val generation: Long,
                         val left: Float, val right: Float, val original: YmInstrument, val heldTouch: Boolean,
                         var expected: YmInstrument?)
    private val drag = PanelDrag<Target>()
    private val cancelOwned: () -> Unit = { cancel() }

    private fun refresh() {
        val actual = song.ymInstrument(st.instrument)
        if (cached === actual && cachedSlot == st.instrument && cachedKey == st.dpcmKey &&
            cachedLane == st.listenChannel && cachedSamples === song.ym.pcmSamples) return
        preview = actual ?: defaultYmInstrument(st.instrument, st.listenChannel)
        val ins = preview!!
        title = if (actual == null) "Empty / Edit to create" else ins.name.ifEmpty { "Unnamed" }
        for (i in 0 until YmAuxSoundControls.count(song, st)) {
            values[i] = YmAuxSoundControls.value(song, st, i)
            labels[i] = YmAuxSoundControls.label(song, st, i)
            hints[i] = YmAuxSoundControls.hint(song, st, i)
            available[i] = YmAuxSoundControls.enabled(song, st, i)
        }
        if (ins is YmPcmInstrument) {
            val recording = ins.pcm[st.dpcmKey]?.let { song.ym.pcmSamples.getOrNull(it.sample) }
            for (i in scopeValues.indices) scopeValues[i] = if (recording == null || recording.data.size == 0) 0f
                else recording.data.signed(i * (recording.data.size - 1) / scopeValues.lastIndex) / 128f
        } else if (ins is YmPsgInstrument) {
            // The stored volume contour, not a simulated carrier or noise waveform.
            val envelope = ins.volume.values
            for (i in scopeValues.indices) {
                val level = if (envelope.isEmpty()) 1f else envelope[minOf(i * envelope.size / scopeValues.size, envelope.lastIndex)] / 15f
                scopeValues[i] = level
            }
        }
        cached = actual; cachedSlot = st.instrument; cachedKey = st.dpcmKey
        cachedLane = st.listenChannel; cachedSamples = song.ym.pcmSamples
    }

    private fun drawName(c: Surface, text: Int, accent: Int) {
        val editing = st.instField == 1
        val raw = if (editing) preview?.name.orEmpty() else title
        val width = (name.width() - ink.dp(6f)).coerceAtLeast(0f)
        if (nameSource != raw || namePosition != st.textPos || nameWidth != width || nameUnit != ink.unit || nameEditing != editing) {
            var first = 0
            val padded = if (editing) raw.padEnd(st.textPos + 1, ' ') else raw
            if (editing) while (first < st.textPos && ink.labelWidth(padded.substring(first, st.textPos + 1), ink.unit, true) > width) first++
            shownName = ink.fit(padded.substring(first), width, ink.unit, true)
            caretLeft = if (editing) ink.labelWidth(padded.substring(first, st.textPos), ink.unit, true) else 0f
            caretWidth = if (editing) maxOf(ink.unit * 0.5f, ink.labelWidth(padded[st.textPos].toString(), ink.unit, true)) else 0f
            nameSource = raw; namePosition = st.textPos; nameWidth = width; nameUnit = ink.unit; nameEditing = editing
        }
        ink.label(c, shownName, name.left, name.top, name.height(), text, true)
        if (editing) {
            ink.outline(c, name, ink.palette.FOCUS, ink.dp(2f))
            val left = name.left + caretLeft
            c.fillRect(left, name.bottom - ink.dp(7f), minOf(name.right, left + caretWidth), name.bottom - ink.dp(4f),
                if (ctl.editHeld) ink.palette.WRITE else accent)
        }
    }

    override fun draw(c: Surface, area: Rect) {
        refresh()
        val p = ink.palette
        val highContrast = p.id == Palette.CONTRAST.id
        val shell = if (highContrast) p.BG else 0xFF222329.toInt()
        val paper = if (highContrast) p.TEXT else 0xFFE4DFD0.toInt()
        val accent = if (highContrast) p.FOCUS else if (YmAuxSoundControls.pcm(song, st)) 0xFFE4A25F.toInt() else 0xFF64CBB7.toInt()
        val bank = advanced.drawSidebar(c, area, p.channel(YmInstrumentView.listenChannel(st, preview)), false)
        c.fillRect(bank.right, bank.top, area.right, area.bottom, shell)
        val touch = ink.dp(48f)
        val pad = ink.dp(8f)
        val left = bank.right + pad
        val right = area.right - pad
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        previous.set(0f, 0f, 0f, 0f); next.set(0f, 0f, 0f, 0f)
        nextSlot.set(right - touch, bank.top, right, bank.top + touch)
        previousSlot.set(nextSlot.left - touch, bank.top, nextSlot.left, nextSlot.bottom)
        name.set(left, bank.top, previousSlot.left, previousSlot.bottom)
        drawName(c, paper, accent)
        ink.label(c, "-", previousSlot.centerX(), bank.top, touch, paper, true, align = Align.CENTER)
        ink.label(c, "+", nextSlot.centerX(), bank.top, touch, paper, true, align = Align.CENTER)
        val compact = right - left < ink.dp(380f) || area.bottom - name.bottom < touch * 5
        val footer = ink.unit * 1.7f
        val bottom = area.bottom - footer
        val count = YmAuxSoundControls.count(song, st)
        val selected = (st.instField - 2).coerceIn(0, count - 1)
        if (compact) {
            previous.set(left, name.bottom, left + touch, bottom)
            next.set(right - touch, name.bottom, right, bottom)
            controls[selected].set(previous.right, name.bottom, next.left, bottom)
            ink.label(c, "<", previous.centerX(), previous.top, previous.height(), paper, true, align = Align.CENTER)
            ink.label(c, ">", next.centerX(), next.top, next.height(), paper, true, align = Align.CENTER)
            drawControl(c, selected, accent, paper)
        } else {
            val monitorRight = left + (right - left) * 0.46f
            scope.set(left + pad, name.bottom + pad, monitorRight - pad, bottom - pad)
            drawScope(c, accent, paper)
            val h = (bottom - name.bottom) / count
            for (i in 0 until count) {
                controls[i].set(monitorRight + pad, name.bottom + i * h, right, name.bottom + (i + 1) * h)
                drawControl(c, i, accent, paper)
            }
        }
        ink.label(c, ink.fit(hints[selected], right - left, ink.unit * 0.72f), left, bottom, footer, paper, size = ink.unit * 0.72f)
    }

    private fun drawControl(c: Surface, index: Int, accent: Int, text: Int) {
        val r = controls[index]
        val h = minOf(ink.dp(24f), r.height() * 0.29f)
        PixelLabels.draw(c, YmAuxSoundControls.name(song, st, index), r.left + ink.dp(4f), r.top + ink.dp(3f),
            minOf(ink.dp(2f), (h - ink.dp(4f)) / PixelLabels.HEIGHT).coerceAtLeast(1f), text, r.width() - ink.dp(8f))
        ink.label(c, ink.fit(labels[index], r.width() - ink.dp(8f), ink.unit * 0.9f), r.left + ink.dp(4f), r.top + h, h, text, true, size = ink.unit * 0.9f)
        val rail = rails[index]
        rail.set(r.left + ink.dp(12f), r.bottom - ink.dp(14f), r.right - ink.dp(12f), r.bottom - ink.dp(14f))
        if (available[index] && values[index] >= 0) {
            val maximum = YmAuxSoundControls.maximum(song, st, index)
            PanelWidgets.rail(ink, c, rail.left, rail.right, rail.top, values[index].toFloat() / maxOf(1, maximum),
                maximum, accent, st.instField == index + 2, ctl.editHeld || drag.target != null)
        } else c.line(rail.left, rail.top, rail.right, rail.top, ink.palette.FAINT, ink.dp(2f))
        if (st.instField == index + 2) c.line(r.left, r.bottom - ink.dp(2f), r.right, r.bottom - ink.dp(2f),
            if (ctl.editHeld) ink.palette.WRITE else accent, ink.dp(2f))
    }

    private fun drawScope(c: Surface, accent: Int, text: Int) {
        val r = scope
        c.fillRect(r.left, r.top, r.right, r.bottom, ink.palette.BG)
        val h = ink.dp(24f)
        PixelLabels.draw(c, if (YmAuxSoundControls.pcm(song, st)) "XGM2 / PCM" else "PSG / GATE", r.left + ink.dp(8f),
            r.top + ink.dp(8f), ink.dp(2f), accent, r.width() - ink.dp(16f))
        val mid = (r.top + h + r.bottom) * 0.5f
        val amplitude = (r.bottom - r.top - h) * 0.36f
        c.line(r.left + ink.dp(8f), mid, r.right - ink.dp(8f), mid, ink.palette.RULE, ink.dp(1f))
        val width = r.width() - ink.dp(16f)
        for (i in 1 until scopeValues.size) c.line(r.left + ink.dp(8f) + width * (i - 1) / scopeValues.lastIndex,
            mid - scopeValues[i - 1] * amplitude, r.left + ink.dp(8f) + width * i / scopeValues.lastIndex,
            mid - scopeValues[i] * amplitude, accent, ink.dp(2f))
        ink.label(c, if (YmAuxSoundControls.pcm(song, st)) "Source recording" else "Volume contour", r.centerX(),
            r.bottom - h, h, text, size = ink.unit * 0.72f, align = Align.CENTER)
    }

    override fun down(x: Float, y: Float): Boolean {
        val blockedAtEntry = st.ymPanel.editBlocked
        cancel()
        if (ctl.editHeld) { ctl.useEdit(); st.ymPanel.editBlocked = true }
        advanced.slotAt(x, y).takeIf { it > 0 }?.let { YmSoundFace.chooseSlot(ctl, it); return true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) {
            YmSoundFace.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true
        }
        if (name.contains(x, y)) { YmSoundFace.rename(ctl); return true }
        if (previous.contains(x, y) || next.contains(x, y)) {
            val count = YmAuxSoundControls.count(song, st)
            st.instField = 2 + ((st.instField - 2).coerceIn(0, count - 1) + if (next.contains(x, y)) 1 else count - 1).mod(count)
            return true
        }
        if (blockedAtEntry) return true
        for (i in 0 until YmAuxSoundControls.count(song, st)) if (controls[i].contains(x, y)) {
            st.instField = i + 2
            if (!available[i]) return true
            val rail = rails[i]
            drag.begin(session, "Set ${YmAuxSoundControls.name(song, st, i)}", Target(st.instrument, i, st.dpcmKey,
                st.ymPanel.revision, session.documentGeneration, rail.left, rail.right, preview!!, ctl.editHeld,
                song.ymInstrument(st.instrument)))
            st.ymPanel.cancelTouch = cancelOwned
            setFromX(x)
            return true
        }
        return false
    }

    private fun setFromX(x: Float) {
        val target = drag.live(session) ?: return
        if (st.kind != ViewKind.INSTRUMENT || st.ymPanel.advanced || YmInstrumentView.fmPages(song, st) || st.instrument != target.slot ||
            st.ymPanel.revision != target.revision || session.documentGeneration != target.generation ||
            song.ymInstrument(target.slot) !== target.expected ||
            (YmAuxSoundControls.pcm(song, st) && target.index != 0 && st.dpcmKey != target.key)) { cancel(); return }
        YmAuxSoundControls.set(ctl, target.index, PanelWidgets.railValue(x, target.left, target.right, 0,
            YmAuxSoundControls.maximum(song, st, target.index)), target.original, target.heldTouch)
        target.expected = song.ymInstrument(target.slot)
    }

    override fun move(x: Float, y: Float) = setFromX(x)
    override fun up(x: Float, y: Float) = cancel()
    override fun cancel() {
        if (st.ymPanel.cancelTouch === cancelOwned) st.ymPanel.cancelTouch = null
        drag.finish(session)
    }

    internal fun controlRect(index: Int): Rect? = controls.getOrNull(index)?.takeIf { it.width() > 0 }
    internal fun valueX(index: Int, fraction: Float): Float = rails[index].let { it.left + it.width() * fraction }
    internal fun navigationRect(forward: Boolean): Rect? = (if (forward) next else previous).takeIf { it.width() > 0 }
    internal fun slotNavigationRect(forward: Boolean): Rect? = (if (forward) nextSlot else previousSlot).takeIf { it.width() > 0 }
    internal fun nameTarget(): Rect? = name.takeIf { it.width() > 0 }
}
