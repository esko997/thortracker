package org.thortracker.system.nes

import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.fm.FmRouting
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.panel.PanelDrag
import org.thortracker.system.panel.PanelWidgets
import org.thortracker.system.panel.PixelLabels
import org.thortracker.system.ym2612.YmCurated
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** A cartridge workbench: native signal memory on the left, playable shaping controls on the right. */
internal class NesSoundPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val precise = NesInstrumentPainter(v)
    private val modes = Array(2) { Rect() }
    private val body = Rect()
    private val nameBox = Rect()
    private val previousSlot = Rect()
    private val nextSlot = Rect()
    private val previousControl = Rect()
    private val nextControl = Rect()
    private val picture = Rect()
    private val controls = Array(6) { Rect() }
    private val rails = Array(6) { Rect() }
    private val texts = Array(6) { "" }
    private val values = IntArray(6)
    private val maxima = IntArray(6)
    private val available = BooleanArray(6)
    private val signal = FloatArray(128)
    private val modulation = FloatArray(FdsSound.MOD_SIZE)
    private var signalCount = 0
    private var signalCaption = ""
    private var status = ""
    private var cached: NesInstrument? = null
    private var cachedSong: NesSound? = null
    private var cachedChannel = -1
    private var cachedKey = -1
    private var voice = NesVoice.PULSE
    private var specifications = NesSoundMacros.controls(voice)
    private var routing = FmRouting.chains(listOf(listOf(0, 1)))
    private val twoOperators = routing
    private var advancedPointer = false
    internal var compact = false
        private set
    private class Target(val control: NesSoundControl, val slot: Int, val channel: Int, val key: Int, val revision: Int,
                         val left: Float, val right: Float, val maximum: Int, val original: NesInstrument,
                         var expected: NesInstrument)
    private val drag = PanelDrag<Target>()
    private val cancelOwned: () -> Unit = { cancel() }

    private fun cache(ins: NesInstrument) {
        if (cached === ins && cachedSong === song.nes && cachedChannel == st.listenChannel && cachedKey == st.dpcmKey) return
        voice = NesSoundFace.voice(song, st)
        specifications = NesSoundMacros.controls(voice)
        for (i in specifications.indices) {
            val spec = specifications[i]
            values[i] = NesSoundFace.value(spec, song, st, ins) ?: -1
            maxima[i] = NesSoundFace.maximum(spec, song, st)
            available[i] = NesSoundMacros.enabled(spec, ins, voice, st.dpcmKey)
            texts[i] = if (!available[i]) when (spec) {
                NesSoundControl.SPEED -> "Auto / Advanced"
                NesSoundControl.CONTOUR -> "Hardware envelope"
                NesSoundControl.FM_CONTOUR -> "SSG / Advanced"
                NesSoundControl.FM_BRIGHTNESS, NesSoundControl.FM_HARMONICS -> "No modulators"
                else -> "Map a sample first"
            } else if (values[i] < 0) "Custom" else NesSoundFace.label(spec, song, st, values[i])
        }
        signalCount = 0
        signalCaption = ""
        status = when (voice) {
            NesVoice.SSG -> "Noise + envelope are shared by all three squares"
            NesVoice.VRC7 -> "One custom FM patch shared by all six voices"
            NesVoice.N163 -> "${song.nes.expansion.n163Channels} voices share wave RAM; ${ins.wave.size} samples"
            NesVoice.FDS -> if (ins.fds.autoMod) "Auto modulation / Advanced" else "Wave memory and modulation counter"
            NesVoice.RHYTHM -> if (Ym2608RhythmRom.installed) "Fixed ROM drum; notes trigger, not tune" else "Import rhythm ROM in Advanced"
            NesVoice.DPCM, NesVoice.PCM -> "Existing samples only; import and mapping tools in Advanced"
            NesVoice.TRIANGLE -> "Fixed triangle amplitude; contour is an on/off gate"
            NesVoice.SAW -> "Full drive overflows above volume 10"
            else -> "Stored shape / no simulated audio"
        }
        if (voice == NesVoice.FDS) {
            val curve = ins.fds.modCurve
            for (i in modulation.indices) modulation[i] = (curve.getOrElse(i) { 0 } + 64) / 127f
        }
        when (voice) {
            NesVoice.N163, NesVoice.FDS -> {
                val samples = if (voice == NesVoice.FDS) ins.fds.waves.first() else ins.wave.waves.first()
                val top = if (voice == NesVoice.FDS) 63f else 15f
                signalCount = minOf(samples.size, signal.size)
                for (i in 0 until signalCount) signal[i] = samples[i * samples.size / signalCount] / top
                signalCaption = "First stored wave"
            }
            NesVoice.PULSE, NesVoice.VRC6_PULSE -> {
                val duty = ins.duty.values.firstOrNull() ?: 0
                val width = if (voice == NesVoice.PULSE) when (duty) { 0 -> 2; 1 -> 4; 2 -> 8; else -> 12 } else duty + 1
                signalCount = 64
                for (i in 0 until signalCount) signal[i] = if (i % 32 < width * 2) 0.85f else 0.15f
                signalCaption = if (ins.duty.values.size > 1) "First duty step / sequence retained" else "Pulse width"
            }
            NesVoice.TRIANGLE -> {
                signalCount = 64
                for (i in 0 until signalCount) signal[i] = (if (i % 32 < 16) i % 16 else 15 - i % 16) / 15f
                signalCaption = "Fixed triangle / gate below"
            }
            NesVoice.SAW -> {
                signalCount = 56
                val volume = ins.volume.values.firstOrNull() ?: 15
                val rate = volume shl ins.sawVolume.shift
                for (i in 0 until signalCount) signal[i] = (((i % 14) / 2 * rate) and 255) / 255f
                signalCaption = "Accumulator at first volume step"
            }
            NesVoice.NOISE, NesVoice.SSG -> {
                val envelope = if (voice == NesVoice.NOISE) ins.duty else ins.noise
                signalCount = minOf(envelope.values.size, signal.size)
                for (i in 0 until signalCount) signal[i] = envelope.values[i] / if (voice == NesVoice.NOISE) 3f else 31f
                signalCaption = if (voice == NesVoice.NOISE) "Noise mode sequence" else "Shared noise clock sequence"
            }
            NesVoice.PCM -> {
                val sample = ins.pcm[st.dpcmKey]?.let { song.nes.pcmSamples.getOrNull(it.sample) }
                if (sample != null && sample.data.size > 0) {
                    signalCount = signal.size
                    for (i in signal.indices) signal[i] = sample.data[i * sample.data.size / signal.size] / 255f
                }
                signalCaption = "Stored PCM sample"
            }
            NesVoice.DPCM -> {
                val mapping = ins.dpcm[st.dpcmKey]
                val sample = mapping?.let { song.nes.samples.getOrNull(it.sample) }
                if (sample != null && sample.data.isNotEmpty()) {
                    var level = (if (mapping.dmcInitial < 0) sample.dmcInitial else mapping.dmcInitial) * 2
                    val bits = sample.data.size * 8
                    var bit = 0
                    signalCount = signal.size
                    for (i in signal.indices) {
                        val stop = (i + 1) * bits / signal.size
                        while (bit < stop) {
                            val up = sample.data[bit / 8] shr (bit % 8) and 1 != 0
                            if (up && level <= 125) level += 2 else if (!up && level >= 2) level -= 2
                            bit++
                        }
                        signal[i] = level / 127f
                    }
                }
                signalCaption = "Decoded delta sample"
            }
            NesVoice.EPSM_FM -> routing = FmRouting.opn(ins.epsm.algorithm, YmCurated.ALGORITHMS[ins.epsm.algorithm].edges, ins.epsm::isCarrier)
            NesVoice.VRC7 -> routing = twoOperators
            NesVoice.RHYTHM -> signalCaption = "ROM hit / fixed pitch"
        }
        cached = ins; cachedSong = song.nes; cachedChannel = st.listenChannel; cachedKey = st.dpcmKey
    }

    override fun draw(c: Surface, area: Rect) {
        val p = ink.palette
        val touch = ink.dp(48f)
        val accent = p.channel(st.listenChannel)
        c.fillRect(area.left, area.top, area.right, area.bottom, p.BG)
        val modeWidth = minOf(area.width() / 2, maxOf(touch * 2, ink.unit * 7))
        for (i in 0..1) {
            modes[i].set(area.left + i * modeWidth, area.top, area.left + (i + 1) * modeWidth, area.top + touch)
            PanelWidgets.segment(ink, c, modes[i], NesSoundFace.panel(st).advanced == (i == 1), accent)
            ink.label(c, if (i == 0) "Sound" else "Advanced", modes[i].centerX(), area.top, touch, p.TEXT, true, align = Align.CENTER)
        }
        if (st.instField == -1) ink.outline(c, modes[if (NesSoundFace.friendly(st)) 0 else 1], p.FOCUS, ink.dp(2f))
        body.set(area.left, area.top + touch, area.right, area.bottom)
        if (!NesSoundFace.friendly(st)) { precise.draw(c, body); return }
        val ins = NesInstrumentView.sound(song, st) ?: return
        cache(ins)
        val bank = precise.drawSidebar(c, body, accent, false)
        val pad = ink.dp(8f)
        val left = bank.right + pad
        val right = area.right - pad
        val width = right - left
        nextSlot.set(right - touch, bank.top, right, bank.top + touch)
        previousSlot.set(nextSlot.left - touch, bank.top, nextSlot.left, nextSlot.bottom)
        nameBox.set(left, bank.top, previousSlot.left, previousSlot.bottom)
        ink.label(c, ink.fit(ins.name.ifEmpty { "Unnamed" }, nameBox.width(), ink.unit, true), left, bank.top, touch, p.TEXT, true)
        ink.label(c, "-", previousSlot.centerX(), bank.top, touch, p.TEXT, true, align = Align.CENTER)
        ink.label(c, "+", nextSlot.centerX(), bank.top, touch, p.TEXT, true, align = Align.CENTER)
        if (st.instField == 1) {
            ink.outline(c, nameBox, p.FOCUS, ink.dp(2f))
            val caretX = left + ink.labelWidth(ins.name.take(st.textPos).padEnd(st.textPos, ' '), ink.unit, true)
            if (caretX < nameBox.right) c.line(caretX, nameBox.bottom - ink.dp(5f), minOf(caretX + ink.unit * 0.7f, nameBox.right), nameBox.bottom - ink.dp(5f), p.WRITE, ink.dp(2f))
        }
        for (r in controls) r.set(0f, 0f, 0f, 0f)
        previousControl.set(0f, 0f, 0f, 0f); nextControl.set(0f, 0f, 0f, 0f)
        val top = nameBox.bottom
        val footer = maxOf(ink.dp(22f), ink.unit * 1.5f)
        val bottom = body.bottom - footer
        val rowCount = (specifications.size + 1) / 2
        compact = width < ink.dp(420f) || bottom - top < ink.dp(64f) * rowCount
        if (compact) {
            val index = (st.instField - 2).coerceIn(0, specifications.lastIndex)
            previousControl.set(left, top, left + touch, bottom)
            nextControl.set(right - touch, top, right, bottom)
            controls[index].set(previousControl.right, top, nextControl.left, bottom)
            drawControl(c, index, accent)
            ink.label(c, "<", previousControl.centerX(), top, bottom - top, p.TEXT, true, align = Align.CENTER)
            ink.label(c, ">", nextControl.centerX(), top, bottom - top, p.TEXT, true, align = Align.CENTER)
        } else {
            picture.set(left, top, left + width * 0.44f, bottom)
            drawSignal(c, picture, ins, accent)
            val controlLeft = picture.right + pad * 2
            val h = (bottom - top) / rowCount
            val columnWidth = (right - controlLeft - pad) / 2
            for (i in specifications.indices) {
                val x = controlLeft + (i % 2) * (columnWidth + pad)
                val y = top + (i / 2) * h
                controls[i].set(x, y, x + columnWidth, y + h)
                drawControl(c, i, accent)
            }
        }
        val selected = specifications.getOrNull(st.instField - 2)
        val hint = selected?.hint ?: status
        ink.label(c, ink.fit(hint, width, ink.unit * 0.75f), left, bottom, footer, p.DIM, size = ink.unit * 0.75f)
    }

    private fun drawControl(c: Surface, i: Int, accent: Int) {
        val r = controls[i]
        val p = ink.palette
        val textH = minOf(ink.dp(25f), r.height() * 0.3f)
        val focus = st.instField == i + 2
        ink.label(c, specifications[i].label, r.left, r.top, textH, if (focus) p.TEXT else p.DIM, true)
        ink.label(c, ink.fit(texts[i], r.width(), ink.unit * 0.85f), r.left, r.top + textH, textH, if (available[i]) accent else p.FAINT, size = ink.unit * 0.85f)
        val y = r.bottom - ink.dp(14f)
        rails[i].set(r.left + ink.dp(10f), y, r.right - ink.dp(10f), y)
        if (available[i] && values[i] >= 0 && maxima[i] > 0)
            PanelWidgets.rail(ink, c, rails[i].left, rails[i].right, y, values[i].toFloat() / maxima[i], maxima[i], accent, focus, ctl.editHeld || drag.target != null)
        else c.line(rails[i].left, y, rails[i].right, y, p.FAINT, ink.dp(2f))
        if (focus) c.line(r.left, r.bottom - ink.dp(2f), r.right, r.bottom - ink.dp(2f), if (ctl.editHeld) p.WRITE else p.FOCUS, ink.dp(2f))
        if (compact && r.height() > ink.dp(145f)) {
            picture.set(r.left, r.top + textH * 2 + ink.dp(4f), r.right, y - ink.dp(14f))
            cached?.let { drawSignal(c, picture, it, accent, small = true) }
        }
    }

    private fun drawSignal(c: Surface, r: Rect, ins: NesInstrument, accent: Int, small: Boolean = false) {
        val p = ink.palette
        c.fillRect(r.left, r.top, r.right, r.bottom, p.LANE)
        val titleH = if (small) 0f else ink.dp(28f)
        if (!small) PixelLabels.draw(c, voice.title, r.left + ink.dp(8f), r.top + ink.dp(7f), ink.dp(1.5f), p.TEXT, r.width() - ink.dp(16f))
        if (voice == NesVoice.VRC7 || voice == NesVoice.EPSM_FM) {
            picture.set(r.left, r.top + titleH, r.right, r.bottom)
            val vrc7 = voice == NesVoice.VRC7
            drawAlgorithm(c, picture, if (vrc7) "Modulator to carrier" else "Routing + output", routing,
                if (vrc7) Vrc7Param.FEEDBACK.get(ins.fm.sounding, 0) else ins.epsm.feedback, accent, -1,
                { op -> if (vrc7) NesInstrumentView.fmValueText(Vrc7Param.MULT, Vrc7Param.MULT.get(ins.fm.sounding, op)) else YmCurated.multText(ins.epsm.operators[op].mul) },
                { op -> if (vrc7) Vrc7Param.WAVE.get(ins.fm.sounding, op) else 0 })
            return
        }
        val pad = ink.dp(10f)
        val left = r.left + pad
        val right = r.right - pad
        val top = r.top + titleH + pad
        val bottom = if (small) r.bottom - pad else r.top + r.height() * 0.58f
        c.line(left, (top + bottom) / 2, right, (top + bottom) / 2, p.RULE, ink.dp(1f))
        if (signalCount > 0) for (i in 1 until signalCount) {
            val x0 = left + (right - left) * (i - 1) / maxOf(1, signalCount - 1)
            val x1 = left + (right - left) * i / maxOf(1, signalCount - 1)
            val y0 = bottom - (bottom - top) * signal[i - 1]
            val y1 = bottom - (bottom - top) * signal[i]
            c.line(x0, y0, x1, y0, accent, ink.dp(2f))
            c.line(x1, y0, x1, y1, accent, ink.dp(2f))
        }
        if (!small) {
            ink.label(c, ink.fit(signalCaption, right - left, ink.unit * 0.8f), left, bottom, ink.dp(24f), p.DIM, size = ink.unit * 0.8f)
            val e = ins.volume.values
            val envTop = bottom + ink.dp(30f)
            val envBottom = r.bottom - ink.dp(25f)
            if (voice == NesVoice.FDS && envBottom > envTop) {
                for (i in 1 until modulation.size) {
                    val x0 = left + (right - left) * (i - 1) / (modulation.size - 1)
                    val x1 = left + (right - left) * i / (modulation.size - 1)
                    val y0 = envBottom - (envBottom - envTop) * modulation[i - 1]
                    val y1 = envBottom - (envBottom - envTop) * modulation[i]
                    c.line(x0, y0, x1, y0, p.DIM, ink.dp(2f))
                    c.line(x1, y0, x1, y1, p.DIM, ink.dp(2f))
                }
                ink.label(c, "Encoded modulation counter", left, envBottom, ink.dp(22f), p.DIM, size = ink.unit * 0.75f)
            } else if (voice != NesVoice.DPCM && voice != NesVoice.PCM && voice != NesVoice.RHYTHM && envBottom > envTop) {
                val n = maxOf(1, e.size)
                for (i in 0 until n) {
                    val x = left + (right - left) * i / n
                    val level = e.getOrNull(i) ?: 15
                    c.fillRect(x, envBottom - (envBottom - envTop) * level / 15f, left + (right - left) * (i + 1) / n, envBottom, p.DIM)
                }
                ink.label(c, "Stored volume steps", left, envBottom, ink.dp(22f), p.DIM, size = ink.unit * 0.75f)
            } else ink.label(c, ink.fit(status, right - left, ink.unit * 0.8f), left, envTop, ink.dp(25f), p.DIM, size = ink.unit * 0.8f)
        }
    }

    internal fun modeRect(advanced: Boolean) = modes[if (advanced) 1 else 0]
    internal fun controlRect(index: Int): Rect? = controls.getOrNull(index)?.takeIf { it.width() > 0 }
    internal fun slotBounds(slot: Int) = precise.slotBounds(slot)
    internal fun navigationRect(forward: Boolean) = if (forward) nextControl else previousControl
    internal fun slotNavigationRect(forward: Boolean) = if (forward) nextSlot else previousSlot
    internal fun nameTarget() = nameBox
    internal fun valueX(index: Int, fraction: Float) = rails[index].left + rails[index].width() * fraction

    override fun down(x: Float, y: Float): Boolean {
        cancel()
        for (i in 0..1) if (modes[i].contains(x, y)) { NesSoundFace.selectMode(ctl, i == 1); return true }
        if (!NesSoundFace.friendly(st)) {
            advancedPointer = precise.down(x, y)
            if (advancedPointer) NesSoundFace.panel(st).cancelTouch = cancelOwned
            return advancedPointer
        }
        if (ctl.editHeld) { ctl.useEdit(); NesSoundFace.panel(st).editBlocked = true }
        val slot = precise.slotAt(x, y)
        if (slot > 0) { NesSoundFace.chooseSlot(ctl, slot); return true }
        if (previousSlot.contains(x, y) || nextSlot.contains(x, y)) {
            NesSoundFace.moveSlot(ctl, if (nextSlot.contains(x, y)) 1 else -1); return true
        }
        if (nameBox.contains(x, y)) {
            st.instField = 1
            val chosen = st.instrument
            val original = NesInstrumentView.sound(song, st) ?: return true
            val panel = NesSoundFace.panel(st)
            panel.invalidate()
            val revision = panel.revision
            session.requestText("Sound name", original.name, org.thortracker.editor.EditorSession.MAX_NAME) { text ->
                if (st.instrument == chosen && panel.revision == revision && NesInstrumentView.sound(song, st) === original)
                    session.setNesInstrument(chosen, original.copy(name = text), "Rename sound")
            }
            return true
        }
        if (previousControl.contains(x, y) || nextControl.contains(x, y)) {
            val d = if (nextControl.contains(x, y)) 1 else -1
            st.instField = 2 + ((st.instField - 2).coerceAtLeast(0) + d).mod(specifications.size)
            return true
        }
        for (i in specifications.indices) if (controls[i].contains(x, y)) {
            st.instField = i + 2
            val ins = NesInstrumentView.sound(song, st) ?: return true
            val spec = specifications[i]
            if (!NesSoundMacros.enabled(spec, ins, voice, st.dpcmKey)) return true
            if (spec == NesSoundControl.VOICE || spec == NesSoundControl.KEY) {
                NesSoundFace.set(ctl, spec, PanelWidgets.railValue(x, rails[i].left, rails[i].right, 0, maxima[i]))
                return true
            }
            drag.begin(session, "Set ${spec.label}", Target(spec, st.instrument, st.listenChannel, st.dpcmKey,
                NesSoundFace.panel(st).revision, rails[i].left, rails[i].right, maxima[i], ins, ins))
            NesSoundFace.panel(st).cancelTouch = cancelOwned
            setFromX(x)
            return true
        }
        return false
    }

    private fun setFromX(x: Float) {
        val t = drag.live(session) ?: return
        if (!NesSoundFace.friendly(st) || st.instrument != t.slot || st.listenChannel != t.channel || st.dpcmKey != t.key ||
            NesSoundFace.panel(st).revision != t.revision || NesInstrumentView.sound(song, st) !== t.expected) { cancel(); return }
        val value = PanelWidgets.railValue(x, t.left, t.right, 0, t.maximum)
        val next = NesSoundMacros.apply(t.control, t.original, voice, value, t.key, NesSoundFace.maximum(NesSoundControl.SAMPLE, song, st))
        if (next != t.expected && session.setNesInstrument(t.slot, next, "Set ${t.control.label}")) NesSoundFace.heard(ctl)
        t.expected = NesInstrumentView.sound(song, st) ?: t.expected
    }

    override fun move(x: Float, y: Float) { if (advancedPointer) precise.move(x, y) else setFromX(x) }
    override fun up(x: Float, y: Float) = cancel()
    override fun cancel() {
        if (NesSoundFace.panel(st).cancelTouch === cancelOwned) NesSoundFace.panel(st).cancelTouch = null
        drag.finish(session)
        if (advancedPointer) { advancedPointer = false; precise.cancel() }
    }
}
