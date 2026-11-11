package org.thortracker.system.sid

import kotlin.math.sqrt
import org.thortracker.editor.ViewKind
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.system.panel.PixelLabels
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** The mode switch is presentation only. The original complete program editor is kept behind Advanced. */
internal class SidInstrumentPainter(v: PaneView) : Painter(v) {
    private val advanced = SidAdvancedPainter(v)
    private val sound = SidSoundPainter(v)
    private val modes = Array(2) { Rect() }
    private val body = Rect()
    private var wasAdvanced = st.sidPanel.advanced

    override fun draw(c: Surface, area: Rect) {
        if (wasAdvanced != st.sidPanel.advanced) { cancel(); wasAdvanced = st.sidPanel.advanced }
        val h = ink.dp(48f)
        val w = minOf(area.width() / 2, maxOf(ink.dp(96f), ink.unit * 6f))
        for (i in 0..1) {
            val r = modes[i]
            r.set(area.right - (2 - i) * w, area.top, area.right - (1 - i) * w, area.top + h)
            val on = st.sidPanel.advanced == (i == 1)
            ink.rect(c, r, if (on) ink.palette.CURSOR_FILL else ink.palette.LANE)
            ink.label(c, if (i == 0) "Sound" else "Advanced", r.centerX(), r.top, r.height(),
                if (on) ink.palette.TEXT else ink.palette.DIM, on, align = Align.CENTER)
            if (on) ink.line(c, r.left, r.bottom - ink.dp(2f), r.right, r.bottom - ink.dp(2f),
                if (st.instField == -1) ink.palette.FOCUS else ink.palette.RULE, ink.dp(3f))
        }
        if (modes[0].left - area.left > ink.dp(100f)) {
            PixelLabels.draw(c, if (song.sid.chip == SidChip.MOS6581) "SID 6581" else "SID 8580",
                area.left + ink.dp(8f), area.top + ink.dp(17f), ink.dp(2f), ink.palette.TEXT,
                modes[0].left - area.left - ink.dp(16f))
        }
        body.set(area.left, area.top + h, area.right, area.bottom)
        if (st.sidPanel.advanced) advanced.draw(c, body) else sound.draw(c, body)
    }

    internal fun modeRect(advanced: Boolean): Rect = modes[if (advanced) 1 else 0]
    internal fun controlRect(index: Int): Rect? = if (st.sidPanel.advanced) null else sound.controlRect(index)
    internal fun valueX(index: Int, fraction: Float): Float = sound.valueX(index, fraction)
    internal fun navigationRect(forward: Boolean): Rect? = sound.navigationRect(forward)
    internal fun slotNavigationRect(forward: Boolean): Rect = sound.slotNavigationRect(forward)
    internal fun listenRect(): Rect = sound.listenRect()
    internal fun slotRect(slot: Int): Rect? = sound.slotRect(slot)

    override fun down(x: Float, y: Float): Boolean {
        cancel()
        for (i in 0..1) if (modes[i].contains(x, y)) {
            SidSoundFace.selectMode(ctl, i == 1)
            return true
        }
        return if (st.sidPanel.advanced) advanced.down(x, y) else sound.down(x, y)
    }
    override fun move(x: Float, y: Float) { if (!st.sidPanel.advanced) sound.move(x, y) else advanced.move(x, y) }
    override fun up(x: Float, y: Float) { sound.up(x, y); advanced.up(x, y) }
    override fun cancel() { sound.cancel(); advanced.cancel() }
}

/**
 * A small programmable synth: blue signal display, beige waveform keys, amber fader caps on one continuous deck.
 * Deliberately not five interchangeable cards. Traces show the patch's oscillator/ADSR, never an invented spectrum.
 */
internal class SidSoundPainter(v: PaneView) : Painter(v) {
    private companion object {
        const val BLUE = 0xFF25216B.toInt()
        const val VIOLET = 0xFF706DE8.toInt()
        const val SIGNAL = 0xFFB8C3FF.toInt()
        const val BEIGE = 0xFFD8CEB5.toInt()
        const val AMBER = 0xFFF0B35C.toInt()
        const val KEY_INK = 0xFF25213A.toInt()
        val SLOT_NUMBERS = Array(SidSystem.SLOTS) { org.thortracker.editor.EditorSession.hex2(it + 1) }
        val VOICES = arrayOf("Listen 1", "Listen 2", "Listen 3")
        val EDIT_LABELS = Array(5) { "Set SID ${SidSoundControl.entries[it].label.lowercase()}" }
    }
    private class Target(val slot: Int, val control: SidSoundControl, val revision: Int, val generation: Long,
                         val left: Float, val right: Float)
    private val drag = PanelDrag<Target>()
    private val cancelOwned: () -> Unit = { finish() }
    private val controls = Array(5) { Rect() }
    private val rails = Array(5) { Rect() }
    private val keys = Array(4) { Rect() }
    private val slotRows = Array(SidSystem.SLOTS) { Rect() }
    private val previous = Rect()
    private val next = Rect()
    private val slotPrevious = Rect()
    private val slotNext = Rect()
    private val nameRect = Rect()
    private val listen = Rect()
    private val screen = Rect()
    private val bank = Rect()
    private val deck = Rect()
    private val fittedNames = Array(SidSystem.SLOTS) { "" }
    private val values = Array(5) { "" }
    private val indices = IntArray(5)
    private val enabled = BooleanArray(5)
    private val overridden = BooleanArray(5)
    private var cachedIns: SidInstrument? = null
    private var cachedSound: SidSound? = null
    private var cachedWidth = -1f
    private var cachedTitleWidth = -1f
    private var cachedUnit = -1f
    private var name = ""
    private var slotStart = 1
    private var slotCount = 1
    private var compact = false
    private var waveform = -1
    private var pulseWidth = 0.5f
    private var pulseSweep = false
    private val envelopeX = FloatArray(5)
    private val envelopeY = FloatArray(5)
    private val pulseTrace = IntArray(48)
    private var pulseCount = 0

    private fun selected(): Int = (st.instField - 2).coerceIn(0, 4)

    private fun cache(ins: SidInstrument, width: Float) {
        val titleWidth = deck.width() - ink.dp(94.4f)
        if (cachedIns !== ins || cachedTitleWidth != titleWidth || cachedUnit != ink.unit) {
            name = ink.fit(ins.name.ifEmpty { "(unnamed)" }, titleWidth, ink.unit * 0.9f, true)
            cachedTitleWidth = titleWidth
        }
        if (cachedSound !== song.sid || cachedWidth != width || cachedUnit != ink.unit) {
            for (i in fittedNames.indices) fittedNames[i] = ink.fit(song.sid.instruments[i].name, width - ink.dp(30f), ink.unit * 0.8f)
            cachedSound = song.sid
            cachedWidth = width
            cachedUnit = ink.unit
        }
        if (cachedIns === ins) return
        for (control in SidSoundControl.entries) {
            val i = control.ordinal
            indices[i] = SidSoundMacros.value(control, ins) ?: -1
            values[i] = if (indices[i] < 0) "Custom" else SidSoundMacros.label(control, indices[i])
            enabled[i] = SidSoundFace.enabled(control, ins)
            overridden[i] = SidSoundMacros.overridden(control, ins)
        }
        waveform = indices[0]
        val first = ins.pulse.rows.firstOrNull()
        pulseWidth = if (first != null && first.left in 0x80..0x8F) (((first.left and 15) shl 8) or first.right) / 4096f else -1f
        pulseSweep = indices[1] >= 3
        pulseCount = 0
        if (indices[1] >= 1) {
            val trace = SidPrograms.trace(SidTables.PULSE, ins.pulse.rows, pulseTrace.size, 0)
            pulseCount = minOf(pulseTrace.size, trace.size)
            for (i in 0 until pulseCount) pulseTrace[i] = trace[i]
        }
        val a = sqrt(SidInstrumentView.ATTACK_MS[ins.attack].toFloat())
        val d = sqrt(3f * SidInstrumentView.ATTACK_MS[ins.decay])
        val r = sqrt(3f * SidInstrumentView.ATTACK_MS[ins.release])
        val total = a + d + r + 24f
        envelopeX[0] = 0f; envelopeX[1] = a / total; envelopeX[2] = (a + d) / total
        envelopeX[3] = (a + d + 24f) / total; envelopeX[4] = 1f
        envelopeY[0] = 0f; envelopeY[1] = 1f; envelopeY[2] = ins.sustain / 15f
        envelopeY[3] = envelopeY[2]; envelopeY[4] = 0f
        cachedIns = ins
    }

    override fun draw(c: Surface, area: Rect) {
        val ins = SidInstrumentView.sound(song, st) ?: return
        val p = ink.palette
        val contrast = p.id == Palette.CONTRAST.id
        val blue = if (contrast) p.BG else BLUE
        val signal = if (contrast) p.TEXT else SIGNAL
        val violet = if (contrast) p.RULE else VIOLET
        val beige = if (contrast) p.LANE else BEIGE
        val touch = ink.dp(48f)
        val gap = ink.dp(8f)
        val leftW = minOf(maxOf(area.width() * 0.28f, ink.dp(96f)), ink.dp(172f), area.width() * 0.4f)
        bank.set(area.left, area.top, area.left + leftW, area.bottom)
        deck.set(bank.right + gap, area.top, area.right, area.bottom)
        ink.rect(c, bank, p.LANE)
        ink.rect(c, deck, blue)
        cache(ins, bank.width())
        drawBank(c, touch)
        val titleH = touch
        nameRect.set(deck.left + gap, deck.top, deck.right - touch * 1.8f, deck.top + titleH)
        listen.set(nameRect.right, deck.top, deck.right, deck.top + titleH)
        ink.label(c, name, nameRect.left, nameRect.top, nameRect.height(), signal, true, size = ink.unit * 0.9f)
        ink.label(c, VOICES[st.listenChannel.coerceIn(0, 2)], listen.centerX(), listen.top, listen.height(), signal, align = Align.CENTER, size = ink.unit * 0.8f)
        if (st.instField == 1) ink.outline(c, nameRect, p.FOCUS, ink.dp(2f))
        compact = deck.height() < touch * 6.4f || deck.width() < ink.dp(340f)
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        previous.set(0f, 0f, 0f, 0f); next.set(0f, 0f, 0f, 0f)
        val footer = ink.dp(36f)
        val controlsTop: Float
        if (compact) {
            val navY = deck.bottom - footer - touch
            previous.set(deck.left, navY, deck.left + touch, navY + touch)
            next.set(deck.right - touch, navY, deck.right, navY + touch)
            controlsTop = maxOf(nameRect.bottom, navY - touch * 1.5f)
            controls[selected()].set(deck.left + gap, controlsTop, deck.right - gap, navY)
            ink.label(c, "‹", previous.centerX(), previous.top, touch, signal, true, align = Align.CENTER)
            ink.label(c, "›", next.centerX(), next.top, touch, signal, true, align = Align.CENTER)
            ink.label(c, SidSoundControl.entries[selected()].label, deck.centerX(), navY, touch, signal, align = Align.CENTER)
        } else {
            controlsTop = deck.bottom - footer - touch * 4.1f
            controls[0].set(deck.left + gap, controlsTop, deck.right - gap, controlsTop + touch * 1.3f)
            val mid = deck.centerX()
            for (i in 1..4) {
                val col = (i - 1) % 2
                val row = (i - 1) / 2
                val top = controls[0].bottom + row * touch * 1.4f
                controls[i].set(if (col == 0) deck.left + gap else mid + gap, top,
                    if (col == 0) mid - gap else deck.right - gap, top + touch * 1.4f)
            }
        }
        screen.set(deck.left + gap, nameRect.bottom, deck.right - gap, controlsTop - gap)
        if (screen.height() >= ink.dp(24f)) drawSignals(c, screen, signal, violet)
        for (i in controls.indices) if (controls[i].height() > 0f) drawControl(c, i, beige, signal, contrast)
        // Always present, including while another control is paged in. Never implies an instrument-local filter.
        val footY = deck.bottom - footer
        ink.label(c, "Shared filter · last start wins", deck.left + gap, footY, footer / 2, signal, size = minOf(ink.unit * 0.65f, deck.width() / 20f))
        val detail = if (indices[4] == 0) "Leave: no new filter claim" else if (indices[4] < 0) "Custom routing · see Advanced" else "Color routes all 3 voices"
        ink.label(c, detail, deck.left + gap, footY + footer / 2, footer / 2, signal, size = ink.unit * 0.65f)
    }

    private fun drawBank(c: Surface, touch: Float) {
        val p = ink.palette
        slotPrevious.set(bank.left, bank.top, bank.centerX(), bank.top + touch)
        slotNext.set(bank.centerX(), bank.top, bank.right, bank.top + touch)
        ink.label(c, "‹", slotPrevious.centerX(), slotPrevious.top, touch, p.TEXT, true, align = Align.CENTER)
        ink.label(c, "›", slotNext.centerX(), slotNext.top, touch, p.TEXT, true, align = Align.CENTER)
        slotCount = maxOf(1, ((bank.height() - touch) / touch).toInt())
        slotStart = slotStart.coerceIn(maxOf(1, st.instrument - slotCount + 1), st.instrument)
        slotStart = slotStart.coerceAtMost(maxOf(1, SidSystem.SLOTS - slotCount + 1))
        for (r in slotRows) r.set(0f, 0f, 0f, 0f)
        for (i in 0 until slotCount) {
            val slot = slotStart + i
            if (slot > SidSystem.SLOTS) break
            val r = slotRows[slot - 1]
            r.set(bank.left, slotPrevious.bottom + i * touch, bank.right, slotPrevious.bottom + (i + 1) * touch)
            val on = slot == st.instrument
            if (on) ink.rect(c, r, p.CURSOR_FILL)
            ink.mono(c, SLOT_NUMBERS[slot - 1], r.left + ink.dp(4f), r.top, r.height(), if (on) p.TEXT else p.FAINT, size = ink.unit * 0.75f)
            ink.label(c, fittedNames[slot - 1], r.left + ink.dp(28f), r.top, r.height(), if (on) p.TEXT else p.DIM, size = ink.unit * 0.8f)
            if (on && st.instField == 0) ink.outline(c, r, p.FOCUS, ink.dp(2f))
        }
    }

    private fun drawControl(c: Surface, i: Int, beige: Int, signal: Int, contrast: Boolean) {
        val r = controls[i]
        val control = SidSoundControl.entries[i]
        val labelH = minOf(ink.dp(24f), r.height() * 0.36f)
        val color = if (contrast) ink.palette.FOCUS else AMBER
        val selected = st.instField == i + 2
        ink.label(c, control.label, r.left, r.top, labelH, signal, selected, size = ink.unit * 0.8f)
        val value = if (overridden[i]) "Custom · Advanced" else if (!enabled[i]) {
            if (indices[i] < 0) "Custom · Pulse only" else "Pulse voice only"
        } else values[i]
        ink.label(c, value, r.right, r.top, labelH, signal, true, align = Align.RIGHT, size = ink.unit * 0.8f)
        val rail = rails[i]
        rail.set(r.left + ink.dp(10f), r.top + labelH, r.right - ink.dp(10f), r.bottom - ink.dp(8f))
        if (i == 0) {
            val keyWidth = r.width() / 4
            for (k in 0..3) {
                val key = keys[k]
                key.set(r.left + k * keyWidth + ink.dp(2f), r.top + labelH, r.left + (k + 1) * keyWidth - ink.dp(2f), r.bottom - ink.dp(4f))
                ink.rect(c, key, if (indices[0] == k) color else beige)
                ink.label(c, SidSoundMacros.label(control, k), key.centerX(), key.top, key.height(),
                    if (contrast) ink.palette.TEXT else KEY_INK, indices[0] == k, size = ink.unit * 0.75f, align = Align.CENTER)
            }
            rail.set(r.left + keyWidth / 2, rail.top, r.right - keyWidth / 2, rail.bottom)
        } else if (enabled[i]) {
            val cy = rail.centerY()
            ink.line(c, rail.left, cy, rail.right, cy, signal, ink.dp(2f))
            val count = SidSoundMacros.steps(control)
            for (n in 0 until count) {
                val x = rail.left + rail.width() * n / (count - 1)
                ink.line(c, x, cy - ink.dp(4f), x, cy + ink.dp(4f), signal, ink.dp(1f))
            }
            if (indices[i] >= 0) {
                val x = rail.left + rail.width() * indices[i] / (count - 1)
                ink.rect(c, x - ink.dp(6f), cy - ink.dp(9f), x + ink.dp(6f), cy + ink.dp(9f), color)
                ink.line(c, x, cy - ink.dp(6f), x, cy + ink.dp(6f), if (contrast) ink.palette.BG else KEY_INK, ink.dp(2f))
            }
        }
        if (selected) {
            // Workspace semantic focus is backed by workspace BG so it remains legible on the blue shell.
            ink.rect(c, r.left, r.bottom - ink.dp(3f), r.right, r.bottom, ink.palette.BG)
            ink.line(c, r.left, r.bottom - ink.dp(1f), r.right, r.bottom - ink.dp(1f),
                if (ctl.editHeld || drag.target != null) ink.palette.WRITE else ink.palette.FOCUS, ink.dp(2f))
        }
    }

    private fun drawSignals(c: Surface, r: Rect, signal: Int, grid: Int) {
        val middle = r.left + r.width() * 0.4f
        val envRight = r.left + r.width() * 0.72f
        val bottom = r.bottom - ink.dp(8f)
        val top = r.top + ink.dp(8f)
        val height = bottom - top
        ink.line(c, r.left, r.centerY(), middle - ink.dp(8f), r.centerY(), grid, ink.dp(1f))
        ink.line(c, middle, bottom, envRight, bottom, grid, ink.dp(1f))
        val waveW = (middle - r.left - ink.dp(16f)) / 2
        if (waveform in 0..2 && (waveform != 2 || pulseWidth >= 0f)) {
            for (period in 0..1) {
                val x = r.left + period * waveW
                when (waveform) {
                    0 -> { ink.line(c, x, bottom, x + waveW / 2, top, signal, ink.dp(2f)); ink.line(c, x + waveW / 2, top, x + waveW, bottom, signal, ink.dp(2f)) }
                    1 -> { ink.line(c, x, bottom, x + waveW, top, signal, ink.dp(2f)); ink.line(c, x + waveW, top, x + waveW, bottom, signal, ink.dp(2f)) }
                    2 -> {
                        val edge = x + waveW * pulseWidth
                        ink.line(c, x, bottom, x, top, signal, ink.dp(2f)); ink.line(c, x, top, edge, top, signal, ink.dp(2f))
                        ink.line(c, edge, top, edge, bottom, signal, ink.dp(2f)); ink.line(c, edge, bottom, x + waveW, bottom, signal, ink.dp(2f))
                    }
                }
            }
        } else ink.label(c, if (waveform == 3) "Noise" else "Custom program", r.left, r.top, r.height(), signal, size = ink.unit * 0.85f)
        if (pulseSweep && waveform == 2 && pulseCount > 1) {
            // Width trajectory from the real table interpreter, below the first-cycle pulse shape.
            for (i in 1 until pulseCount) ink.line(c, r.left + (i - 1) * waveW * 2 / (pulseCount - 1), bottom - height * pulseTrace[i - 1] / 4095f,
                r.left + i * waveW * 2 / (pulseCount - 1), bottom - height * pulseTrace[i] / 4095f, grid, ink.dp(1f))
        }
        if (overridden[2] || overridden[3]) {
            ink.label(c, "Custom envelope", middle, r.top, r.height(), signal, size = ink.unit * 0.8f)
        } else for (i in 1..4) ink.line(c, middle + (envRight - middle) * envelopeX[i - 1], bottom - height * envelopeY[i - 1],
            middle + (envRight - middle) * envelopeX[i], bottom - height * envelopeY[i], signal, ink.dp(2f))
        drawFilterRoute(c, envRight + ink.dp(12f), r.right, top, bottom, signal, grid)
    }

    /** Three voice wires converge on one filter. Cutoff bar is the stored control value, not a claimed response. */
    private fun drawFilterRoute(c: Surface, left: Float, right: Float, top: Float, bottom: Float, signal: Int, grid: Int) {
        val color = indices[4]
        if (color < 1) {
            ink.label(c, if (color < 0) "Custom" else "Leave", left, top, bottom - top, signal, size = ink.unit * 0.75f)
            return
        }
        val program = cachedIns?.filter ?: return
        val routing = program.rows[0].right and 7
        val cutoff = program.rows[1].right / 255f
        val x = left + (right - left) * 0.35f
        val end = left + (right - left) * 0.83f
        val cy = (top + bottom) / 2
        for (voice in 0..2) {
            val y = cy + (voice - 1) * ink.dp(6f)
            val inkColor = if (routing and (1 shl voice) != 0) signal else grid
            ink.rect(c, left, y - ink.dp(1f), left + ink.dp(3f), y + ink.dp(2f), inkColor)
            ink.line(c, left + ink.dp(5f), y, x, y, inkColor, ink.dp(1f))
        }
        ink.line(c, x, cy - ink.dp(9f), end, cy - ink.dp(9f), signal, ink.dp(1f))
        ink.line(c, x, cy + ink.dp(9f), end, cy + ink.dp(9f), signal, ink.dp(1f))
        ink.line(c, x, cy - ink.dp(9f), x, cy + ink.dp(9f), signal, ink.dp(1f))
        ink.line(c, end, cy - ink.dp(9f), end, cy + ink.dp(9f), signal, ink.dp(1f))
        ink.line(c, end, cy, right, cy, signal, ink.dp(1f))
        ink.label(c, if (color <= 2) "LP" else if (color == 3) "BP" else "HP", (x + end) / 2,
            cy - ink.dp(9f), ink.dp(18f), signal, size = ink.unit * 0.6f, align = Align.CENTER)
        ink.line(c, left, bottom, right, bottom, grid, ink.dp(2f))
        ink.line(c, left, bottom, left + (right - left) * cutoff, bottom, signal, ink.dp(2f))
    }

    internal fun controlRect(index: Int): Rect? = controls.getOrNull(index)?.takeIf { it.height() > 0f }
    internal fun valueX(index: Int, fraction: Float): Float = rails[index].let { it.left + it.width() * fraction }
    internal fun navigationRect(forward: Boolean): Rect? = (if (forward) next else previous).takeIf { it.width() > 0f }
    internal fun slotNavigationRect(forward: Boolean): Rect = if (forward) slotNext else slotPrevious
    internal fun listenRect(): Rect = listen
    internal fun slotRect(slot: Int): Rect? = slotRows.getOrNull(slot - 1)?.takeIf { it.height() > 0f }

    override fun down(x: Float, y: Float): Boolean {
        finish()
        if (st.sidPanel.advanced) return false
        if (ctl.editHeld) ctl.useEdit()
        if (slotPrevious.contains(x, y) || slotNext.contains(x, y)) {
            SidInstrumentView.moveSlot(ctl, if (slotNext.contains(x, y)) slotCount else -slotCount)
            st.instField = 0
            return true
        }
        for (i in slotRows.indices) if (slotRows[i].contains(x, y)) {
            SidInstrumentView.moveSlot(ctl, i + 1 - st.instrument)
            st.instField = 0
            return true
        }
        if (previous.contains(x, y) || next.contains(x, y)) {
            st.sidPanel.invalidate()
            st.instField = 2 + (selected() + if (next.contains(x, y)) 1 else 4).mod(5)
            return true
        }
        if (listen.contains(x, y)) {
            st.sidPanel.invalidate()
            st.listenChannel = (st.listenChannel + 1).mod(3)
            if (ctl.auditionHeld) SidInstrumentView.audition(ctl)
            return true
        }
        if (nameRect.contains(x, y)) { st.instField = 1; return true }
        val ins = SidInstrumentView.sound(song, st) ?: return false
        for (control in SidSoundControl.entries) {
            val i = control.ordinal
            if (!controls[i].contains(x, y)) continue
            st.instField = i + 2
            if (!SidSoundFace.enabled(control, ins)) return true
            val rail = rails[i]
            drag.begin(session, EDIT_LABELS[i], Target(st.instrument, control, st.sidPanel.revision, session.documentGeneration, rail.left, rail.right))
            st.sidPanel.cancelTouch = cancelOwned
            setFromX(x)
            return true
        }
        return false
    }
    private fun setFromX(x: Float) {
        val target = drag.live(session) ?: return
        if (st.kind != ViewKind.INSTRUMENT || st.sidPanel.advanced || st.instrument != target.slot ||
            st.sidPanel.revision != target.revision || session.documentGeneration != target.generation) { finish(); return }
        val index = PanelWidgets.railValue(x, target.left, target.right, 0, SidSoundMacros.steps(target.control) - 1)
        SidSoundFace.set(ctl, target.slot, target.control, index)
    }
    override fun move(x: Float, y: Float) = setFromX(x)
    override fun up(x: Float, y: Float) = finish()
    override fun cancel() = finish()
    private fun finish() {
        if (st.sidPanel.cancelTouch === cancelOwned) st.sidPanel.cancelTouch = null
        drag.finish(session)
    }
}
