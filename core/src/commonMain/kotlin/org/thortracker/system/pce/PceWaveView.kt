package org.thortracker.system.pce

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.Hint
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The PC Engine's waves: the song's 0..[PceSound.MAX_WAVES] tables of 32 5-bit steps, drawn with a pen and shaped by
 * tools (the built-in shapes of [PcePresets.WAVES], invert, shift, smooth), assigned to the selected tone sound, added
 * and deleted (references renumbered, [deletePceWave]). State: [ViewState.wave], [ViewState.waveRow] (0 tools, 1
 * drawing grid), [ViewState.waveCol] (tool or step) and [ViewState.waveLevel] (pen height 0..31).
 */
object PceWaveView : SystemView() {
    enum class Tool(val label: String) {
        SHAPE("Shape"), INVERT("Invert"), SHIFT_LEFT("Shift left"), SHIFT_RIGHT("Shift right"), SMOOTH("Smooth"),
        USE("Use in sound"), NEW("New wave"), DELETE("Delete wave"),
    }

    val TOOLS = Tool.entries.toList()

    override val gestureLabel = "Draw wave"

    fun waveCount(song: Song): Int = song.pce.waves.size

    override fun normalise(v: ViewController) {
        val st = v.state
        val count = waveCount(v.session.song)
        st.wave = st.wave.coerceIn(0, maxOf(0, count - 1))
        st.waveRow = if (count == 0) 0 else st.waveRow.coerceIn(0, 1)
        st.waveCol = st.waveCol.coerceIn(0, if (st.waveRow == 0) TOOLS.size - 1 else PceSound.WAVE_SIZE - 1)
        st.waveLevel = st.waveLevel.coerceIn(0, PceSound.WAVE_MAX)
    }

    override fun subject(state: ViewState): Int = state.wave

    override fun applyLock(state: ViewState, subject: Int) {
        state.wave = subject.coerceIn(0, PceSound.MAX_WAVES - 1)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.wave = selection.wave
    }

    /** Publishes the wave; a following instrument view shows the tone sound that plays it, if any. */
    fun publish(v: ViewController) {
        v.syncLock()
        val st = v.state
        val slot = soundUsingWave(v, st.wave)
        val sel = v.session.selection
        v.session.select(
            if (slot > 0) sel.copy(wave = st.wave, bank = 0, instrument = slot, context = ViewKind.INSTRUMENT)
            else sel.copy(wave = st.wave, context = ViewKind.INSTRUMENT),
        )
    }

    /** The tone sound that plays [wave]: the selected sound if it does, else the first, else 0. */
    fun soundUsingWave(v: ViewController, wave: Int): Int {
        val song = v.session.song
        val sel = v.session.selection
        val selected = song.pceInstrument(sel.instrument)
        if (selected?.kind == PceKind.TONE && selected.wave == wave) return sel.instrument
        val idx = song.pce.instruments.indexOfFirst { it?.kind == PceKind.TONE && it.wave == wave }
        return if (idx < 0) 0 else idx + 1
    }

    /** The sound "Use in sound" assigns to: the selected sound. */
    fun targetSound(v: ViewController): Int = v.session.selection.instrument

    /** Sound slots playing [wave] (as their wave or in their wave sequence). */
    fun users(song: Song, wave: Int): List<Int> = song.pce.instruments.withIndex()
        .filter { (_, ins) -> ins != null && (ins.kind == PceKind.TONE && ins.wave == wave || wave in ins.waves.values) }
        .map { it.index + 1 }

    private fun paintSample(v: ViewController) {
        v.session.setPceWaveSample(v.state.wave, v.state.waveCol, v.state.waveLevel)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val count = waveCount(v.session.song)
        if (st.waveRow == 0) {
            when {
                v.editHeld && TOOLS[st.waveCol] == Tool.SHAPE -> { v.useEdit(); stepShape(v, dx + dy) }
                dy < 0 && count > 0 -> st.waveRow = 1
                dx != 0 -> st.waveCol = (st.waveCol + dx).coerceIn(0, TOOLS.size - 1)
            }
            return
        }
        when {
            v.clearHeld -> { v.useClear(); st.waveCol = (st.waveCol + 8 * dx).coerceIn(0, PceSound.WAVE_SIZE - 1) }
            v.editHeld -> {
                v.useEdit()
                st.waveCol = (st.waveCol + dx).coerceIn(0, PceSound.WAVE_SIZE - 1)
                st.waveLevel = (st.waveLevel + dy).coerceIn(0, PceSound.WAVE_MAX)
                paintSample(v)
            }
            dy > 0 && st.waveLevel == PceSound.WAVE_MAX -> { st.waveRow = 0; st.waveCol = st.waveCol.coerceIn(0, TOOLS.size - 1) }
            else -> {
                st.waveCol = (st.waveCol + dx).coerceIn(0, PceSound.WAVE_SIZE - 1)
                st.waveLevel = (st.waveLevel + dy).coerceIn(0, PceSound.WAVE_MAX)
            }
        }
    }

    override fun editPress(v: ViewController) {
        if (v.state.waveRow == 1) paintSample(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        if (tap && v.state.waveRow == 0) applyTool(v, TOOLS[v.state.waveCol.coerceIn(0, TOOLS.size - 1)])
    }

    override fun clear(v: ViewController) {
        if (v.state.waveRow == 1) v.session.setPceWaveSample(v.state.wave, v.state.waveCol, 0)
    }

    /** The built-in shape [delta] steps from the one the wave is (or the first), replacing the wave. */
    private fun stepShape(v: ViewController, delta: Int) {
        val st = v.state
        val shapes = PcePresets.WAVES
        val w = v.session.song.pce.waves.getOrNull(st.wave) ?: return noWaves(v)
        if (shapes.isEmpty() || delta == 0) return
        val cur = PceWaveShapes.presetOf(w)
        val next = if (cur < 0) (if (delta < 0) shapes.size - 1 else 0) else (cur + delta).mod(shapes.size)
        v.session.setPceWave(st.wave, shapes[next].second, "${shapes[next].first} wave")
        v.session.note("Wave ${hex2(st.wave)}: ${shapes[next].first}")
    }

    private fun noWaves(v: ViewController) = v.session.note("The song has no waves: ${v.k(Action.EDIT)} on New wave makes one")

    fun applyTool(v: ViewController, tool: Tool) {
        val st = v.state
        val session = v.session
        val song = session.song
        val w = song.pce.waves.getOrNull(st.wave)
        if (w == null && tool != Tool.NEW) return noWaves(v)
        when (tool) {
            Tool.SHAPE -> stepShape(v, 1)
            Tool.INVERT -> session.setPceWave(st.wave, PceWaveShapes.invert(w!!), "Invert wave")
            Tool.SHIFT_LEFT -> session.setPceWave(st.wave, PceWaveShapes.rotate(w!!, -1), "Shift wave")
            Tool.SHIFT_RIGHT -> session.setPceWave(st.wave, PceWaveShapes.rotate(w!!, 1), "Shift wave")
            Tool.SMOOTH -> session.setPceWave(st.wave, PceWaveShapes.smooth(w!!), "Smooth wave")
            Tool.USE -> {
                val slot = targetSound(v)
                if (session.usePceWave(slot, st.wave)) session.note("Sound ${hex2(slot)} now plays wave ${hex2(st.wave)}")
                else if (song.pceInstrument(slot)?.kind == PceKind.TONE) session.note("Sound ${hex2(slot)} already plays wave ${hex2(st.wave)}")
                else session.note("Sound ${hex2(slot)} is not a tone sound: select a tone sound (Instrument view or a pattern note) first")
            }
            Tool.NEW -> {
                val index = session.addPceWave(PcePresets.WAVES.first().second, "New wave")
                if (index < 0) session.note("The song has ${PceSound.MAX_WAVES} waves, the most the player holds")
                else { st.wave = index; publish(v); session.note("Wave ${hex2(index)}: a new ${PcePresets.WAVES.first().first.lowercase()} wave") }
            }
            Tool.DELETE -> {
                val index = st.wave
                val moved = session.deletePceWave(index)
                if (moved < 0) return
                normalise(v)
                publish(v)
                session.note("Deleted wave ${hex2(index)}" + when {
                    moved > 0 -> "; its $moved use${if (moved == 1) "" else "s"} now play wave ${hex2(maxOf(0, index - 1))}, later waves moved down"
                    index < waveCount(session.song) -> "; later waves moved down"
                    else -> ""
                })
            }
        }
    }

    /** The channel the wave view listens on: the selected channel, which plays any tone sound. */
    private fun listenChannel(v: ViewController): Int =
        v.session.selection.channel.coerceIn(0, v.session.song.system.channels.size - 1)

    override fun audition(v: ViewController) {
        val st = v.state
        val slot = soundUsingWave(v, st.wave)
        if (slot > 0) v.startAudition(listenChannel(v), v.defaultNote(), slot)
        else v.session.note("No tone sound plays wave ${hex2(st.wave)}: choose Use in sound first")
    }

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val count = waveCount(session.song)
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                if (count == 0) { noWaves(v); return true }
                st.wave = (st.wave + if (a == Action.NEXT_CHANNEL) 1 else -1).mod(count)
                publish(v)
            }
            Action.COPY -> session.copyPceWave(st.wave)
            Action.PASTE -> session.pastePceWave(st.wave)
            Action.CUT -> if (count > 0) {
                session.copyPceWave(st.wave)
                session.setPceWave(st.wave, List(PceSound.WAVE_SIZE) { 0 }, "Cut wave")
            } else noWaves(v)
            Action.DUPLICATE -> {
                if (count == 0) { noWaves(v); return true }
                val index = session.duplicatePceWave(st.wave)
                if (index < 0) { session.note("The song has ${PceSound.MAX_WAVES} waves, the most the player holds"); return true }
                session.note("Wave ${hex2(index)}: a copy of wave ${hex2(st.wave)}" + if (index < waveCount(session.song) - 1) "; later waves moved up" else "")
                st.wave = index
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        val st = v.state
        if (st.waveRow == 0) {
            val tool = TOOLS[st.waveCol.coerceIn(0, TOOLS.size - 1)]
            h += Hint(v.lr(), "Tool")
            h += Hint(v.k(Action.EDIT), when (tool) {
                Tool.SHAPE -> "Next built-in shape"
                Tool.USE -> "Sound ${hex2(targetSound(v))} plays this wave"
                else -> tool.label
            })
            if (tool == Tool.SHAPE) h += Hint(v.editLr(), "Built-in shapes")
            if (waveCount(v.session.song) > 0) h += Hint(v.k(Action.DOWN), "Back to drawing")
        } else {
            h += Hint(v.keys.dpad(), "Move pen")
            h += Hint(v.k(Action.EDIT), "Hold: draw at pen height")
            h += Hint(v.k(Action.CLEAR), "Step to 0")
            h += Hint("${v.k(Action.UP)} at top", "Tools")
        }
        h += Hint(v.channels(), "Wave")
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen at ${PceSystem.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), "Copy/paste wave")
    }

    /** Help rows for this view (heading, then key → meaning), named with the current [keys]. */
    fun helpSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        return listOf(
            "Waves" to listOf(
                channels to "The song's waves (up to ${PceSound.MAX_WAVES}): 32 steps of 0-31 each, played in order at the note's pitch",
                keys.dpad() to "Move the pen over the steps; hold $edit to draw at the pen height, $clear sets a step to 0",
                "${keys.label(Action.UP)} at top" to "The tools: $lr chooses, $edit applies",
                "Shape" to "The built-in shapes (${PcePresets.WAVES.joinToString(", ") { it.first.lowercase() }}); ${keys.held(Action.EDIT, lr)} steps through them",
                "Use in sound" to "The selected tone sound plays this wave",
                "New / Delete" to "Add a wave (sine to start from) or delete this one: sounds, wave sequences and Wxx naming later waves follow, " +
                    "uses of the deleted wave move to the one before it",
                keys.label(Action.DUPLICATE) to "Insert a copy after this wave (later waves move up, their uses follow)",
                "PSG 2" to "With the song's LFO on (Mxx, Song view), PSG 2's wave is the LFO's shape: it bends PSG 1's pitch",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = PceWavePainter(pane)
}
