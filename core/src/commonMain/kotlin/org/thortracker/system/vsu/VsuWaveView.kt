package org.thortracker.system.vsu

import org.thortracker.backend.VsuPlayerImage
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.system.fm.FmControls
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The Virtual Boy's shared wave RAM and modulation RAM: six pages, the five waves (32 samples 0..63, played by any lane)
 * and the modulation table (32 values −128..127, added to Wave 5's frequency by modulating sounds). A tool row (presets,
 * invert or negate, shift, smooth, use in the selected sound) above a drawing grid: the pen is a crosshair (sample,
 * level); holding Edit draws at the pen height. State: [ViewState.wave] (the page: 0..4 waves, [VsuWaves.MOD_PAGE] the
 * table), [ViewState.waveRow] (0 tools, 1 grid), [ViewState.waveCol] (tool or sample) and [ViewState.waveLevel] (pen
 * height: 0..63 on waves, −128..127 on the table).
 */
object VsuWaveView : SystemView() {
    enum class Tool(val label: String, val modLabel: String) {
        PRESET("Preset", "Preset"), INVERT("Invert", "Negate"), SHIFT_LEFT("Shift ←", "Shift ←"), SHIFT_RIGHT("Shift →", "Shift →"),
        SMOOTH("Smooth", "Smooth"), USE("Use in sound", "Use in sound"),
    }

    val TOOLS = Tool.entries

    override val gestureLabel = "Draw wave"

    fun isModulation(state: ViewState): Boolean = state.wave == VsuWaves.MOD_PAGE

    /** The pen's level range on [state]'s page. */
    fun levels(state: ViewState): IntRange = if (isModulation(state)) -128..127 else 0..VsuPlayerImage.SAMPLE_MAX

    /** The values of [state]'s page. */
    fun values(song: Song, state: ViewState): List<Int> = if (isModulation(state)) song.vsu.modulation else song.vsu.waves[state.wave]

    override fun normalise(v: ViewController) {
        val st = v.state
        st.wave = st.wave.coerceIn(0, VsuWaves.PAGES - 1)
        st.waveRow = st.waveRow.coerceIn(0, 1)
        st.waveCol = st.waveCol.coerceIn(0, if (st.waveRow == 0) TOOLS.size - 1 else VsuPlayerImage.WAVE_SAMPLES - 1)
        st.waveLevel = st.waveLevel.coerceIn(levels(st))
    }

    override fun subject(state: ViewState): Int = state.wave

    override fun applyLock(state: ViewState, subject: Int) {
        state.wave = subject.coerceIn(0, VsuWaves.PAGES - 1)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.wave = selection.wave.coerceIn(0, VsuWaves.PAGES - 1)
    }

    /** Wave view suggests the instrument view showing a sound that plays this wave. */
    fun publish(v: ViewController) {
        v.syncLock()
        val st = v.state
        val slot = targetSound(v)
        val sel = v.session.selection
        v.session.select(
            if (slot > 0) sel.copy(wave = st.wave, bank = 0, instrument = slot, context = ViewKind.INSTRUMENT)
            else sel.copy(wave = st.wave, context = ViewKind.INSTRUMENT),
        )
    }

    /**
     * The sound Listen plays the page with: the selected sound when it plays this wave (or modulates, on the table's
     * page), else the first that does; 0 when none.
     */
    fun soundUsing(v: ViewController): Int {
        val song = v.session.song
        val sel = v.session.selection
        val users = if (isModulation(v.state)) VsuWaves.soundsModulating(song.vsu) else VsuWaves.soundsUsing(song.vsu, v.state.wave)
        return if (sel.bank == 0 && sel.instrument in users) sel.instrument else users.firstOrNull() ?: 0
    }

    /** The sound "Use in sound" changes: the selected one, else the entry pen's. */
    fun targetSound(v: ViewController): Int {
        val sel = v.session.selection
        return if (sel.bank == 0) sel.instrument else v.pen(0)
    }

    private fun paintSample(v: ViewController) {
        val st = v.state
        if (isModulation(st)) v.session.setVsuModValue(st.waveCol, st.waveLevel) else v.session.setVsuWaveSample(st.wave, st.waveCol, st.waveLevel)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val range = levels(st)
        if (st.waveRow == 0) {
            when {
                v.editHeld && st.waveCol == Tool.PRESET.ordinal && dx != 0 -> { v.useEdit(); applyPreset(v, dx) }
                dy < 0 -> st.waveRow = 1
                dx != 0 -> st.waveCol = (st.waveCol + dx).coerceIn(0, TOOLS.size - 1)
            }
            return
        }
        when {
            v.clearHeld -> {
                v.useClear()
                st.waveCol = (st.waveCol + 8 * dx).coerceIn(0, VsuPlayerImage.WAVE_SAMPLES - 1)
                st.waveLevel = (st.waveLevel + COARSE * dy).coerceIn(range)
            }
            v.editHeld -> {
                v.useEdit()
                st.waveCol = (st.waveCol + dx).coerceIn(0, VsuPlayerImage.WAVE_SAMPLES - 1)
                st.waveLevel = (st.waveLevel + dy).coerceIn(range)
                paintSample(v)
            }
            dy > 0 && st.waveLevel == range.last -> { st.waveRow = 0; st.waveCol = st.waveCol.coerceIn(0, TOOLS.size - 1) }
            else -> {
                st.waveCol = (st.waveCol + dx).coerceIn(0, VsuPlayerImage.WAVE_SAMPLES - 1)
                st.waveLevel = (st.waveLevel + dy).coerceIn(range)
            }
        }
    }

    override fun editPress(v: ViewController) {
        if (v.state.waveRow == 1) paintSample(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        if (tap && v.state.waveRow == 0) applyTool(v, TOOLS[v.state.waveCol.coerceIn(0, TOOLS.size - 1)])
    }

    /** Clear on the grid: the sample to the centre (32 on a wave, 0 in the table). */
    override fun clear(v: ViewController) {
        val st = v.state
        if (st.waveRow != 1) return
        if (isModulation(st)) v.session.setVsuModValue(st.waveCol, 0) else v.session.setVsuWaveSample(st.wave, st.waveCol, 32)
    }

    /** The page's preset [delta] steps from the one it matches (or the first). */
    fun applyPreset(v: ViewController, delta: Int) {
        val st = v.state
        val session = v.session
        if (isModulation(st)) {
            val list = VsuPresets.MODULATIONS
            val p = list[FmControls.presetStep(list.indexOfFirst { it.values == session.song.vsu.modulation }, delta, list.size)]
            session.setVsuModulation(p.values, "Modulation ${p.name}")
            session.note("Modulation table: ${p.name}")
        } else {
            val list = VsuPresets.WAVES
            val p = list[FmControls.presetStep(list.indexOfFirst { it.samples == session.song.vsu.waves[st.wave] }, delta, list.size)]
            session.setVsuWave(st.wave, p.samples, "Wave ${p.name}")
            session.note("Wave ${st.wave + 1}: ${p.name}")
        }
    }

    fun applyTool(v: ViewController, tool: Tool) {
        val st = v.state
        val session = v.session
        val mod = isModulation(st)
        val values = values(session.song, st)
        fun set(next: List<Int>, label: String) {
            if (mod) session.setVsuModulation(next, label) else session.setVsuWave(st.wave, next, label)
        }
        when (tool) {
            Tool.PRESET -> applyPreset(v, 1)
            Tool.INVERT -> if (mod) set(VsuWaves.negate(values), "Negate modulation") else set(VsuWaves.invert(values), "Invert wave")
            Tool.SHIFT_LEFT -> set(VsuWaves.rotate(values, -1), "Shift ${if (mod) "modulation" else "wave"}")
            Tool.SHIFT_RIGHT -> set(VsuWaves.rotate(values, 1), "Shift ${if (mod) "modulation" else "wave"}")
            Tool.SMOOTH -> set(VsuWaves.smooth(values, levels(st)), "Smooth ${if (mod) "modulation" else "wave"}")
            Tool.USE -> {
                val slot = targetSound(v)
                if (mod) {
                    session.editVsuInstrument(slot, "Use modulation") {
                        it.copy(freqMod = it.freqMod.copy(on = true, modulation = true, interval = it.freqMod.interval.takeIf { i -> i > 0 } ?: 6))
                    }
                    session.note("Sound ${EditorSession.hex2(slot)} now modulates Wave 5 with the table (it plays on Wave 5 only)")
                } else {
                    session.editVsuInstrument(slot, "Assign wave") { it.copy(wave = st.wave) }
                    session.note("Sound ${EditorSession.hex2(slot)} now plays wave ${st.wave + 1}")
                }
            }
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val slot = soundUsing(v)
        if (slot == 0) {
            v.session.note(if (isModulation(st)) "No sound modulates Wave 5: choose Use in sound first" else "No sound plays wave ${st.wave + 1}: choose Use in sound first")
            return
        }
        val lane = if (isModulation(st)) VsuRules.SWEEP_LANE else 0
        v.startAudition(lane, v.defaultNote().coerceIn(VsuRules.noteRange(lane)), slot)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                st.wave = (st.wave + if (a == Action.NEXT_CHANNEL) 1 else -1).mod(VsuWaves.PAGES)
                st.waveLevel = st.waveLevel.coerceIn(levels(st))
                publish(v)
            }
            Action.COPY -> session.copyVsuWavePage(st.wave)
            Action.PASTE -> session.pasteVsuWavePage(st.wave)
            Action.CUT -> {
                session.copyVsuWavePage(st.wave)
                if (isModulation(st)) session.setVsuModulation(List(VsuPlayerImage.MOD_VALUES) { 0 }, "Cut modulation")
                else session.setVsuWave(st.wave, VsuPresets.DEFAULT_WAVES[st.wave].samples, "Cut wave")
            }
            Action.DUPLICATE -> {
                if (st.wave >= VsuPlayerImage.WAVES - 1) { session.note(if (isModulation(st)) "The modulation table has no copy page" else "Wave 5 is the last wave"); return true }
                session.setVsuWave(st.wave + 1, session.song.vsu.waves[st.wave], "Duplicate wave")
                st.wave++
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        val st = v.state
        val mod = isModulation(st)
        if (st.waveRow == 0) {
            val tool = TOOLS[st.waveCol.coerceIn(0, TOOLS.size - 1)]
            h += Hint(v.lr(), "Tool")
            h += Hint(v.k(Action.EDIT), when (tool) {
                Tool.USE -> if (mod) "Sound ${EditorSession.hex2(targetSound(v))} modulates with the table" else "Sound ${EditorSession.hex2(targetSound(v))} plays this wave"
                Tool.PRESET -> "Next preset"
                else -> if (mod) tool.modLabel else tool.label
            })
            if (tool == Tool.PRESET) h += Hint(v.editLr(), "Step presets")
            h += Hint(v.k(Action.DOWN), "Back to drawing")
        } else {
            h += Hint(v.keys.dpad(), "Move pen")
            h += Hint(v.keys.held(Action.CLEAR, v.keys.dpad()), "Pen by 8")
            h += Hint(v.k(Action.EDIT), "Hold: draw at pen height")
            h += Hint(v.k(Action.CLEAR), if (mod) "Value to 0" else "Sample to 32")
            h += Hint("${v.k(Action.UP)} at top", "Tools")
        }
        h += Hint(v.channels(), "Wave / table")
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen at ${VsuSystem.noteName(v.defaultNote())}")
    }

    /** Help rows for this view (heading, then key → meaning), named with the current [keys]. */
    fun helpSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        return listOf(
            "Waves" to listOf(
                channels to "Pages: waves 1-5 (shared by every lane: an edit changes every lane and sound playing the wave) and the modulation table",
                keys.dpad() to "Move the pen (sample, level); ${keys.held(Action.CLEAR, keys.dpad())} by 8; ${keys.label(Action.UP)} at the top reaches the tools",
                "$edit held" to "Draw at the pen height while moving",
                "Tools" to "Preset (tap: next; ${keys.held(Action.EDIT, keys.pair(Action.LEFT, Action.RIGHT))}: step), invert (the table: negate), shift, " +
                    "smooth, use in the selected sound (the table: switch its Wave 5 modulation on)",
                "Modulation" to "32 values added to Wave 5's frequency register, one per interval step of a modulating sound: the same value bends " +
                    "high notes further than low ones",
                "Hardware" to "The VSU accepts wave writes only while every channel is silent; the player loads them before the song, and edits are " +
                    "heard at once through the editor",
            ),
        )
    }

    private const val COARSE = 8

    override fun painter(pane: PaneView): Painter = VsuWavePainter(pane)
}
