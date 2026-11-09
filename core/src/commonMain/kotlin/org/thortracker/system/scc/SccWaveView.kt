package org.thortracker.system.scc

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.system.fm.FmControls
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The SCC's wavetables: [SccSound.WAVES] waves of [SccWave.SAMPLES] signed 8-bit samples (-128..127), drawn with a pen
 * and shaped by tools, each with a name. State: [ViewState.wave] (the wave's index = number - 1, as
 * [Selection.wave]), [ViewState.waveRow] ([TOOLS_ROW], [GRID_ROW] or [NAME_ROW]), [ViewState.waveCol] (the tool or the
 * sample), [ViewState.waveLevel] (the pen's level, -128..127) and [ViewState.textPos] (on the name).
 */
object SccWaveView : SystemView() {
    enum class Tool(val label: String) {
        SINE("Sine"), TRIANGLE("Triangle"), SAW("Saw"), SQUARE("Square"), PULSE("Pulse"), NOISE("Noise"), INVERT("Invert"),
        SHIFT_LEFT("Shift left"), SHIFT_RIGHT("Shift right"), SMOOTH("Smooth"), NORMALIZE("Normalise"), USE("Use in sound"),
    }

    /** Rows of the view; the numbers match the Game Boy's ([ViewState.waveRow] 0 tools, 1 grid), the name is above the tools. */
    const val TOOLS_ROW = 0
    const val GRID_ROW = 1
    const val NAME_ROW = 2

    /** Pen moves (and Clear-held sample moves) by this much with the coarse direction: 32 steps over the 256 levels. */
    const val COARSE = 8

    /** The pulse width a new pulse gets (8 of 32 samples high: 25 %). */
    const val DEFAULT_PULSE = 8

    val LOW = SccWave.LEVELS.first
    val HIGH = SccWave.LEVELS.last

    fun tool(state: ViewState): Tool = Tool.entries[state.waveCol.coerceIn(0, Tool.entries.size - 1)]

    /** The shown wave's number (1-based). */
    fun number(state: ViewState): Int = state.wave + 1

    fun wave(song: Song, state: ViewState): SccWave = song.scc.wave(number(state)) ?: SccWave()

    override val gestureLabel = "Draw wave"

    override fun normalise(v: ViewController) {
        val st = v.state
        st.wave = st.wave.coerceIn(0, SccSound.WAVES - 1)
        st.waveRow = st.waveRow.coerceIn(TOOLS_ROW, NAME_ROW)
        st.waveCol = st.waveCol.coerceIn(0, if (st.waveRow == GRID_ROW) SccWave.SAMPLES - 1 else Tool.entries.size - 1)
        st.waveLevel = st.waveLevel.coerceIn(LOW, HIGH)
    }

    override fun subject(state: ViewState): Int = state.wave

    override fun applyLock(state: ViewState, subject: Int) {
        state.wave = subject.coerceIn(0, SccSound.WAVES - 1)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.wave = selection.wave.coerceIn(0, SccSound.WAVES - 1)
    }

    /** The sounds (slots) whose wave envelope plays wave [number]. */
    fun users(song: Song, number: Int): List<Int> =
        song.scc.instruments.withIndex().filter { it.value?.waves?.contains(number) == true }.map { it.index + 1 }

    /** The sound that plays wave [number]: the selected sound if it does, else the first that does, else 0. */
    fun soundUsingWave(v: ViewController, number: Int): Int {
        val song = v.session.song
        val sel = v.session.selection.instrument
        if (song.sccInstrument(sel)?.waves?.contains(number) == true) return sel
        return users(song, number).firstOrNull() ?: 0
    }

    /** The sound "Use in sound" gives the wave: the selected sound. */
    fun targetSound(v: ViewController): Int = v.session.selection.instrument.coerceIn(1, SccSound.SLOTS)

    /**
     * Waves the SCC's shared wave RAM can hold: those SCC 4 or SCC 5 play in the arrangement (the waves of the sounds
     * their cells name, and their `Wxx` waves). Empty on the SCC+, whose channels each have their own RAM.
     */
    fun sharedWaves(song: Song): Set<Int> {
        if (!song.scc.variant.sharedWave) return emptySet()
        val out = HashSet<Int>()
        for (pat in song.orders.toSet()) {
            val tracks = song.patterns[pat].tracks
            for (ch in SccSound.SHARED_CHANNELS) for (cell in tracks.getOrNull(ch).orEmpty()) {
                if (cell.instrument > 0) song.sccInstrument(cell.instrument)?.let { out += it.waves }
                for (col in 0 until SccRules.EFFECT_COLUMNS) {
                    if (cell.effectCode(col) == SccRules.FX_WAVE && cell.effectParam(col) > 0) out += cell.effectParam(col)
                }
            }
        }
        return out
    }

    /** The note the view shows when wave [number] plays on SCC 4 or SCC 5 of an SCC song, or null. */
    fun sharedNote(song: Song, number: Int): String? =
        if (number in sharedWaves(song)) "On the SCC, SCC 4 and SCC 5 share one wave RAM: when both sound, both play the wave claimed last" else null

    /** Points the shared selection at this wave, and at a sound that plays it, for a following Instrument view. */
    fun publish(v: ViewController) {
        v.syncLock()
        val st = v.state
        val slot = soundUsingWave(v, number(st))
        val sel = v.session.selection
        v.session.select(if (slot > 0) sel.copy(wave = st.wave, bank = 0, instrument = slot, context = ViewKind.INSTRUMENT)
            else sel.copy(wave = st.wave, context = ViewKind.INSTRUMENT))
    }

    private fun paintSample(v: ViewController) {
        v.session.setSccWaveSample(number(v.state), v.state.waveCol, v.state.waveLevel)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        when (st.waveRow) {
            NAME_ROW -> when {
                v.editHeld -> {
                    v.useEdit()
                    v.session.editSccWave(number(st), "Rename wave") { it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, EditorSession.MAX_NAME)) }
                }
                dy < 0 -> { st.waveRow = TOOLS_ROW; st.waveCol = st.waveCol.coerceIn(0, Tool.entries.size - 1) }
                dx != 0 -> st.textPos = (st.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
            }
            TOOLS_ROW -> when {
                v.editHeld -> { v.useEdit(); adjustTool(v, dx + dy) }
                dy < 0 -> st.waveRow = GRID_ROW
                dy > 0 -> { st.waveRow = NAME_ROW; st.textPos = 0 }
                dx != 0 -> st.waveCol = (st.waveCol + dx).coerceIn(0, Tool.entries.size - 1)
            }
            else -> when {
                v.clearHeld -> {
                    v.useClear()
                    st.waveCol = (st.waveCol + COARSE * dx).coerceIn(0, SccWave.SAMPLES - 1)
                    st.waveLevel = (st.waveLevel + dy).coerceIn(LOW, HIGH)
                }
                v.editHeld -> {
                    v.useEdit()
                    st.waveCol = (st.waveCol + dx).coerceIn(0, SccWave.SAMPLES - 1)
                    st.waveLevel = (st.waveLevel + dy).coerceIn(LOW, HIGH)
                    paintSample(v)
                }
                dy > 0 && st.waveLevel == HIGH -> { st.waveRow = TOOLS_ROW; st.waveCol = st.waveCol.coerceIn(0, Tool.entries.size - 1) }
                else -> {
                    st.waveCol = (st.waveCol + dx).coerceIn(0, SccWave.SAMPLES - 1)
                    st.waveLevel = (st.waveLevel + COARSE * dy).coerceIn(LOW, HIGH)
                }
            }
        }
    }

    override fun editPress(v: ViewController) {
        if (v.state.waveRow == GRID_ROW) paintSample(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        if (tap && v.state.waveRow == TOOLS_ROW) applyTool(v, tool(v.state))
    }

    override fun clear(v: ViewController) {
        val st = v.state
        when (st.waveRow) {
            GRID_ROW -> v.session.setSccWaveSample(number(st), st.waveCol, 0)
            NAME_ROW -> v.session.editSccWave(number(st), "Rename wave") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            else -> Unit
        }
    }

    /** The width (1..31) of the pulse [samples] are, or null when they are no pulse. */
    fun pulseWidth(samples: List<Int>): Int? = (1..SccWave.SAMPLES - 1).firstOrNull { SccWaveShapes.pulse(it) == samples }

    /** Edit+direction on the tools: the pulse's width, another noise; the other tools have no setting. */
    private fun adjustTool(v: ViewController, d: Int) {
        if (d == 0) return
        val st = v.state
        val w = wave(v.session.song, st)
        when (tool(st)) {
            Tool.PULSE -> {
                val width = ((pulseWidth(w.samples) ?: (DEFAULT_PULSE - d)) + d).coerceIn(1, SccWave.SAMPLES - 1)
                v.session.setSccWaveSamples(number(st), SccWaveShapes.pulse(width), "Pulse wave")
                v.session.note("Pulse: $width of ${SccWave.SAMPLES} samples high (${width * 100 / SccWave.SAMPLES} %)")
            }
            Tool.NOISE -> v.session.setSccWaveSamples(number(st), SccWaveShapes.noise(w.samples.hashCode() + d), "Noise wave")
            else -> Unit
        }
    }

    fun applyTool(v: ViewController, tool: Tool) {
        val st = v.state
        val session = v.session
        val n = number(st)
        val w = wave(session.song, st).samples
        when (tool) {
            Tool.SINE -> session.setSccWaveSamples(n, SccWaveShapes.sine(), "Sine wave")
            Tool.TRIANGLE -> session.setSccWaveSamples(n, SccWaveShapes.triangle(), "Triangle wave")
            Tool.SAW -> session.setSccWaveSamples(n, SccWaveShapes.saw(), "Saw wave")
            Tool.SQUARE -> session.setSccWaveSamples(n, SccWaveShapes.square(), "Square wave")
            Tool.PULSE -> session.setSccWaveSamples(n, SccWaveShapes.pulse(pulseWidth(w) ?: DEFAULT_PULSE), "Pulse wave")
            Tool.NOISE -> session.setSccWaveSamples(n, SccWaveShapes.noise(w.hashCode()), "Noise wave")
            Tool.INVERT -> session.setSccWaveSamples(n, SccWaveShapes.invert(w), "Invert wave")
            Tool.SHIFT_LEFT -> session.setSccWaveSamples(n, SccWaveShapes.rotate(w, -1), "Shift wave")
            Tool.SHIFT_RIGHT -> session.setSccWaveSamples(n, SccWaveShapes.rotate(w, 1), "Shift wave")
            Tool.SMOOTH -> session.setSccWaveSamples(n, SccWaveShapes.smooth(w), "Smooth wave")
            Tool.NORMALIZE -> if (!session.setSccWaveSamples(n, SccWaveShapes.normalize(w), "Normalise wave")) {
                session.note("Wave ${hex2(n)} is already as loud as it gets (or silent)")
            }
            Tool.USE -> {
                val slot = targetSound(v)
                val had = session.song.sccInstrument(slot)?.wave?.values?.size ?: 0
                session.editSccInstrument(slot, "Assign wave") { it.copy(wave = NesEnvelope(listOf(n))) }
                session.note("Sound ${hex2(slot)} now plays wave ${hex2(n)}" + if (had > 1) " (its $had-step wave envelope replaced)" else "")
            }
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val session = v.session
        val n = number(st)
        val slot = soundUsingWave(v, n)
        if (slot > 0) {
            val ch = FmControls.mostUsedChannel(session.song, slot) ?: st.listenChannel
            v.startAudition(ch.coerceIn(0, session.song.system.channels.size - 1), v.defaultNote(), slot)
            return
        }
        val sel = session.selection.instrument
        if (session.song.sccInstrument(sel) != null) {
            session.note("No sound plays wave ${hex2(n)}: listening to sound ${hex2(sel)} (${Tool.USE.label} makes it play this wave)")
            v.startAudition(st.listenChannel.coerceIn(0, session.song.system.channels.size - 1), v.defaultNote(), sel)
        } else {
            session.note("No sound plays wave ${hex2(n)}: choose ${Tool.USE.label} first")
        }
    }

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val n = number(st)
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                st.wave = (st.wave + if (a == Action.NEXT_CHANNEL) 1 else -1).mod(SccSound.WAVES)
                publish(v)
            }
            Action.COPY -> session.copySccWave(n)
            Action.PASTE -> session.pasteSccWave(n)
            Action.CUT -> { session.copySccWave(n); session.setSccWave(n, SccWave(), "Cut wave") }
            Action.DUPLICATE -> {
                if (n >= SccSound.WAVES) { session.note("Wave ${hex2(n)} is the last wave"); return true }
                session.setSccWave(n + 1, wave(session.song, st), "Duplicate wave")
                st.wave++
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        val st = v.state
        when (st.waveRow) {
            NAME_ROW -> {
                h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter")
                h += Hint(v.k(Action.DOWN), "Tools")
            }
            TOOLS_ROW -> {
                val t = tool(st)
                h += Hint(v.lr(), "Tool")
                h += Hint(v.k(Action.EDIT), if (t == Tool.USE) "Sound ${hex2(targetSound(v))} plays this wave" else t.label)
                if (t == Tool.PULSE) h += Hint(v.editLr(), "Pulse width")
                if (t == Tool.NOISE) h += Hint(v.editLr(), "Another noise")
                h += Hint(v.k(Action.DOWN), "Drawing")
                h += Hint(v.k(Action.UP), "Name")
            }
            else -> {
                h += Hint(v.keys.dpad(), "Move pen (level ±$COARSE)")
                h += Hint(v.k(Action.EDIT), "Hold: draw at pen height (${v.ud()} ±1)")
                h += Hint(v.keys.held(Action.CLEAR, v.ud()), "Level ±1")
                h += Hint(v.k(Action.CLEAR), "Sample to 0")
                h += Hint("${v.k(Action.UP)} at top", "Tools")
            }
        }
        h += Hint(v.channels(), "Wave")
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen at ${v.session.song.system.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), "Copy/paste wave")
    }

    override fun painter(pane: PaneView): Painter = SccWavePainter(pane)
}
