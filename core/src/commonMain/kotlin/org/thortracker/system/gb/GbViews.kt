package org.thortracker.system.gb

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.SystemView
import org.thortracker.system.panel.MusicalPanelMode
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The Game Boy's three typed banks share musical Sound and precise Advanced faces.
 * The mode and its cursors live only in the pane. Field -1 is the mode row, 0 bank/slot, 1 name,
 * and 2+ the current mode's controls. Holding Edit on a control listens and groups changes into one Undo.
 */
object GbSoundView : SystemView() {
    fun kind(state: ViewState): GbBank = GbSystem.kindOf(state.bank)

    fun friendly(state: ViewState): Boolean = !state.gbPanel.advanced

    private fun lastField(state: ViewState) = if (friendly(state)) 1 + GbMusicalMacros.count(kind(state)) else 1 + params(state).size

    /** Switching presentation never writes a patch or creates an undo step. Keep physical holds owned by input. */
    internal fun setAdvanced(v: ViewController, advanced: Boolean, keepModeFocus: Boolean = false) {
        MusicalPanelMode.select(v, v.state.gbPanel, advanced, keepModeFocus)
        normalise(v)
    }

    fun params(state: ViewState): List<SoundParam> = SoundParams.forKind(kind(state))

    fun sound(song: Song, state: ViewState): GbInstrument? = song.instrument(kind(state), state.instrument)

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        st.bank = st.bank.coerceIn(0, GbBank.entries.size - 1)
        st.instField = st.instField.coerceIn(-1, lastField(st))
    }

    override fun subject(state: ViewState): Int = state.bank * 16 + state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        if (this.subject(state) != subject) {
            state.gbPanel.invalidate()
        }
        state.bank = (subject / 16).coerceIn(0, GbBank.entries.size - 1)
        state.instrument = (subject % 16).coerceIn(1, HugeRules.INSTRUMENT_SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        if (state.bank != selection.bank || state.instrument != selection.instrument) {
            state.gbPanel.invalidate()
        }
        state.bank = selection.bank
        state.instrument = selection.instrument
    }

    fun publish(v: ViewController) {
        val st = v.state
        st.gbPanel.cancelTouch?.invoke()
        val kind = kind(st)
        v.syncLock()
        val session = v.session
        val wave = if (kind == GbBank.WAVE) sound(v.session.song, st)?.wave ?: session.selection.wave else session.selection.wave
        // Sound view suggests where the sound is heard: its waveform for wave sounds, else the keyboard.
        val context = if (kind == GbBank.WAVE) ViewKind.WAVE else ViewKind.KEYBOARD
        session.select(session.selection.copy(bank = GbSystem.bankOf(kind), instrument = st.instrument, wave = wave, context = context))
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        when {
            st.instField == -1 && dx != 0 -> {
                if (v.editHeld) v.useEdit()
                setAdvanced(v, dx > 0, keepModeFocus = true)
            }
            v.editHeld -> { v.useEdit(); if (!st.gbPanel.editBlocked) adjust(v, dx, dy) }
            dy != 0 -> navigateSetting(st, -dy)
            st.instField == 1 -> st.textPos = (st.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
            else -> {
                st.instrument = (st.instrument + dx).coerceIn(1, HugeRules.INSTRUMENT_SLOTS)
                st.gbPanel.invalidate()
                publish(v)
            }
        }
    }

    /** Shared controller/touch field order; navigation never edits the selected sound. */
    internal fun navigateSetting(st: ViewState, delta: Int) {
        st.instField = (st.instField + delta).coerceIn(-1, lastField(st))
        st.textPos = 0
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val kind = kind(st)
        when (st.instField) {
            -1 -> Unit
            0 -> {
                st.instrument = (st.instrument + dx).coerceIn(1, HugeRules.INSTRUMENT_SLOTS)
                st.gbPanel.invalidate()
                if (dy != 0) {
                    st.bank = (st.bank - dy).mod(GbBank.entries.size)
                    st.instField = 0
                }
                publish(v)
            }
            1 -> session.editInstrument(kind, st.instrument, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, EditorSession.MAX_NAME))
            }
            else -> {
                if (friendly(st)) {
                    val control = st.instField - 2
                    if (control !in 0 until GbMusicalMacros.count(kind)) return
                    val delta = dx + dy
                    if (delta == 0) return
                    val changed = session.editInstrument(kind, st.instrument, "Set ${GbMusicalMacros.label(kind, control).lowercase()}") {
                        val current = GbMusicalMacros.value(control, it)
                        val next = if (current == null) { if (delta > 0) 0 else GbMusicalMacros.steps(kind, control) - 1 }
                            else (current + delta).coerceIn(0, GbMusicalMacros.steps(kind, control) - 1)
                        GbMusicalMacros.apply(control, it, next)
                    }
                    if (kind == GbBank.WAVE && control == 0) publish(v)
                    if (changed) heard(v)
                    return
                }
                val p = params(st).getOrNull(st.instField - 2) ?: return
                val changed = session.editInstrument(kind, st.instrument, "Set ${p.label.lowercase()}") { p.adjust(it, dx + p.coarse * dy) }
                if (p === SoundParams.wave) publish(v)
                if (changed) heard(v)
            }
        }
    }

    /** After a change: a held Listen, or Edit held on a setting, sounds the changed sound again. */
    internal fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && v.state.instField >= 2)) audition(v)
    }

    override fun editPress(v: ViewController) {
        v.state.gbPanel.editBlocked = false
        if (v.state.instField >= 2) audition(v)
    }

    // Releasing Edit ends the audition its press started (ViewController stops it after this).
    override fun editRelease(v: ViewController, tap: Boolean) {
        val st = v.state
        if (st.gbPanel.editBlocked) { st.gbPanel.editBlocked = false; return }
        if (!tap) return
        when (st.instField) {
            -1 -> setAdvanced(v, !st.gbPanel.advanced, keepModeFocus = true)
            0 -> { publish(v); v.session.note("Sound ${EditorSession.hex2(st.instrument)} selected for entry") }
            1 -> Unit
            else -> {
                if (friendly(st)) return
                val p = params(st).getOrNull(st.instField - 2) ?: return
                if (p.max - p.min == 1) {
                    v.session.editInstrument(kind(st), st.instrument, "Toggle ${p.label.lowercase()}") {
                        p.set(it, if (p.get(it) == p.min) p.max else p.min)
                    }
                }
            }
        }
    }

    override fun clear(v: ViewController) {
        val st = v.state
        if (st.instField < 0 || st.gbPanel.editBlocked) return
        val session = v.session
        val kind = kind(st)
        val changed = when (st.instField) {
            0 -> session.setInstrument(kind, st.instrument, defaultSound(kind, st.instrument))
            1 -> session.editInstrument(kind, st.instrument, "Rename sound") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            else -> {
                if (friendly(st)) {
                    val control = st.instField - 2
                    if (control !in 0 until GbMusicalMacros.count(kind)) return
                    val changed = session.editInstrument(kind, st.instrument, "Reset ${GbMusicalMacros.label(kind, control).lowercase()}") {
                        GbMusicalMacros.reset(control, it)
                    }
                    if (kind == GbBank.WAVE && control == 0) publish(v)
                    if (changed) heard(v)
                    return
                }
                val p = params(st).getOrNull(st.instField - 2) ?: return
                val def = p.get(defaultSound(kind, st.instrument))
                session.editInstrument(kind, st.instrument, "Reset ${p.label.lowercase()}") { p.set(it, def) }
            }
        }
        if (changed && st.instField != 1) heard(v)
    }

    override fun audition(v: ViewController) {
        val ch = v.session.song.firstChannelOf(kind(v.state))
        if (ch >= 0) v.startAudition(ch, v.defaultNote(), v.state.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val kind = kind(st)
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                st.bank = (st.bank + if (a == Action.NEXT_CHANNEL) 1 else -1).mod(GbBank.entries.size)
                st.gbPanel.invalidate()
                if (v.editHeld) { v.useEdit(); st.gbPanel.editBlocked = true }
                st.instField = st.instField.coerceAtMost(lastField(st))
                publish(v)
            }
            Action.COPY -> session.copyInstrument(kind, st.instrument)
            Action.PASTE -> session.pasteInstrument(kind, st.instrument)
            Action.CUT -> {
                session.copyInstrument(kind, st.instrument)
                session.setInstrument(kind, st.instrument, defaultSound(kind, st.instrument))
            }
            Action.DUPLICATE -> {
                if (st.instrument >= HugeRules.INSTRUMENT_SLOTS) { session.note("Sound ${EditorSession.hex2(st.instrument)} is the last slot"); return true }
                val src = sound(session.song, st) ?: return true
                session.setInstrument(kind, st.instrument + 1, src.copy(name = (src.name + "+").take(EditorSession.MAX_NAME)))
                st.instrument++
                st.gbPanel.invalidate()
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        val st = v.state
        when (st.instField) {
            -1 -> {
                h += Hint(v.lr(), "Sound / Advanced (same patch)")
                h += Hint(v.k(Action.EDIT), "Switch mode")
            }
            0 -> { h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "Bank"); h += Hint(v.channels(), "Bank"); h += Hint(v.k(Action.CLEAR), "Reset sound") }
            1 -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            else -> {
                if (friendly(st)) {
                    val control = st.instField - 2
                    if (control in 0 until GbMusicalMacros.count(kind(st))) h += Hint(v.editLr(), GbMusicalMacros.help(kind(st), control))
                } else {
                    val p = params(st).getOrNull(st.instField - 2)
                    if (p != null) h += Hint(v.editLr(), p.help)
                }
                h += Hint(v.lr(), "Sound slot")
                h += Hint(v.k(Action.CLEAR), "Default value")
            }
        }
        h += Hint(v.ud(), "Controls; top row: Sound / Advanced")
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen at ${GbSystem.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), "Copy/paste sound")
    }

    override fun painter(pane: PaneView): Painter = SoundPainter(pane)

    private fun defaultSound(kind: GbBank, slot: Int) = GbInstrument(name = "Sound ${EditorSession.hex2(slot)}", kind = kind)
}

/**
 * The Game Boy's wave tables: 16 waveforms of 32 4-bit samples, drawn with a pen and shaped by tools. State:
 * [ViewState.wave], [ViewState.waveRow] (0 tools, 1 drawing grid), [ViewState.waveCol] (tool or sample) and
 * [ViewState.waveLevel] (pen height).
 */
object GbWaveView : SystemView() {
    val TOOLS = listOf("Sine", "Triangle", "Saw", "Square", "Invert", "Shift left", "Shift right", "Smooth", "Use in sound")

    override val gestureLabel = "Draw wave"

    override fun normalise(v: ViewController) {
        val st = v.state
        st.wave = st.wave.coerceIn(0, v.session.song.gb.waves.size - 1)
        st.waveRow = st.waveRow.coerceIn(0, 1)
        st.waveCol = st.waveCol.coerceIn(0, if (st.waveRow == 0) TOOLS.size - 1 else WaveShapes.SAMPLES - 1)
        st.waveLevel = st.waveLevel.coerceIn(0, WaveShapes.MAX)
    }

    override fun subject(state: ViewState): Int = state.wave

    override fun applyLock(state: ViewState, subject: Int) {
        state.wave = subject.coerceIn(0, HugeRules.WAVE_COUNT - 1)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.wave = selection.wave
    }

    /** Wave view suggests the sound view showing the wave sound that plays this wave. */
    fun publish(v: ViewController) {
        v.syncLock()
        val st = v.state
        val slot = soundUsingWave(v, st.wave)
        val sel = v.session.selection
        v.session.select(
            if (slot > 0) sel.copy(wave = st.wave, bank = GbSystem.bankOf(GbBank.WAVE), instrument = slot, context = ViewKind.INSTRUMENT)
            else sel.copy(wave = st.wave, context = ViewKind.INSTRUMENT),
        )
    }

    /** Wave sound slot that plays [wave]: the selected wave sound if it does, else the first, else 0. */
    fun soundUsingWave(v: ViewController, wave: Int): Int {
        val sel = v.session.selection
        val song = v.session.song
        val waveBank = GbSystem.bankOf(GbBank.WAVE)
        if (sel.bank == waveBank && song.instrument(GbBank.WAVE, sel.instrument)?.wave == wave) return sel.instrument
        val idx = song.gb.wave.indexOfFirst { it.wave == wave }
        return if (idx < 0) 0 else idx + 1
    }

    /** Wave sound the "Use in sound" tool assigns to. */
    fun targetSound(v: ViewController): Int {
        val sel = v.session.selection
        val waveBank = GbSystem.bankOf(GbBank.WAVE)
        return if (sel.bank == waveBank) sel.instrument else v.pen(waveBank)
    }

    private fun paintSample(v: ViewController) {
        v.session.setWaveSample(v.state.wave, v.state.waveCol, v.state.waveLevel)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        if (st.waveRow == 0) {
            when {
                dy < 0 -> st.waveRow = 1
                dx != 0 -> st.waveCol = (st.waveCol + dx).coerceIn(0, TOOLS.size - 1)
            }
            return
        }
        when {
            v.clearHeld -> { v.useClear(); st.waveCol = (st.waveCol + 8 * dx).coerceIn(0, WaveShapes.SAMPLES - 1) }
            v.editHeld -> {
                v.useEdit()
                st.waveCol = (st.waveCol + dx).coerceIn(0, WaveShapes.SAMPLES - 1)
                st.waveLevel = (st.waveLevel + dy).coerceIn(0, WaveShapes.MAX)
                paintSample(v)
            }
            dy > 0 && st.waveLevel == WaveShapes.MAX -> { st.waveRow = 0; st.waveCol = st.waveCol.coerceIn(0, TOOLS.size - 1) }
            else -> {
                st.waveCol = (st.waveCol + dx).coerceIn(0, WaveShapes.SAMPLES - 1)
                st.waveLevel = (st.waveLevel + dy).coerceIn(0, WaveShapes.MAX)
            }
        }
    }

    override fun editPress(v: ViewController) {
        if (v.state.waveRow == 1) paintSample(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        if (tap && v.state.waveRow == 0) applyTool(v, v.state.waveCol)
    }

    override fun clear(v: ViewController) {
        if (v.state.waveRow == 1) v.session.setWaveSample(v.state.wave, v.state.waveCol, 0)
    }

    fun applyTool(v: ViewController, tool: Int) {
        val st = v.state
        val session = v.session
        val w = session.song.gb.waves[st.wave]
        when (tool) {
            0 -> session.setWave(st.wave, WaveShapes.sine(), "Sine wave")
            1 -> session.setWave(st.wave, WaveShapes.triangle(), "Triangle wave")
            2 -> session.setWave(st.wave, WaveShapes.saw(), "Saw wave")
            3 -> session.setWave(st.wave, WaveShapes.square(), "Square wave")
            4 -> session.setWave(st.wave, WaveShapes.invert(w), "Invert wave")
            5 -> session.setWave(st.wave, WaveShapes.rotate(w, -1), "Shift wave")
            6 -> session.setWave(st.wave, WaveShapes.rotate(w, 1), "Shift wave")
            7 -> session.setWave(st.wave, WaveShapes.smooth(w), "Smooth wave")
            8 -> {
                val slot = targetSound(v)
                session.editInstrument(GbBank.WAVE, slot, "Assign wave") { it.copy(wave = st.wave) }
                session.note("Sound ${EditorSession.hex2(slot)} now plays wave ${EditorSession.hex1(st.wave)}")
            }
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val slot = soundUsingWave(v, st.wave)
        val ch = v.session.song.firstChannelOf(GbBank.WAVE)
        if (slot > 0 && ch >= 0) v.startAudition(ch, v.defaultNote(), slot)
        else v.session.note("No wave sound plays wave ${EditorSession.hex1(st.wave)}: choose Use in sound first")
    }

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val song = session.song
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                st.wave = (st.wave + if (a == Action.NEXT_CHANNEL) 1 else -1).mod(song.gb.waves.size)
                publish(v)
            }
            Action.COPY -> session.copyWave(st.wave)
            Action.PASTE -> session.pasteWave(st.wave)
            Action.CUT -> { session.copyWave(st.wave); session.setWave(st.wave, List(WaveShapes.SAMPLES) { 0 }, "Cut wave") }
            Action.DUPLICATE -> {
                if (st.wave >= song.gb.waves.size - 1) { session.note("Wave ${EditorSession.hex1(st.wave)} is the last wave"); return true }
                session.setWave(st.wave + 1, song.gb.waves[st.wave], "Duplicate wave")
                st.wave++
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        val st = v.state
        if (st.waveRow == 0) {
            h += Hint(v.lr(), "Tool")
            h += Hint(v.k(Action.EDIT), if (st.waveCol == 8) "Wave sound ${EditorSession.hex2(targetSound(v))} plays this wave" else TOOLS[st.waveCol])
            h += Hint(v.k(Action.DOWN), "Back to drawing")
        } else {
            h += Hint(v.keys.dpad(), "Move pen")
            h += Hint(v.k(Action.EDIT), "Hold: draw at pen height")
            h += Hint(v.k(Action.CLEAR), "Sample to 0")
            h += Hint("${v.k(Action.UP)} at top", "Tools")
        }
        h += Hint(v.channels(), "Wave")
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen at ${GbSystem.noteName(v.defaultNote())}")
    }

    override fun painter(pane: PaneView): Painter = WavePainter(pane)
}
