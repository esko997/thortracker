package org.thortracker.system.nes

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.system.panel.MusicalPanelMode

internal object NesSoundFace {
    fun panel(st: ViewState) = st.soundPanel("nes")
    fun friendly(st: ViewState) = !panel(st).advanced
    fun voice(song: Song, st: ViewState): NesVoice = NesVoice.of(song.nes.expansion, st.listenChannel, NesInstrumentView.chip(song, st))
    fun controls(song: Song, st: ViewState) = NesSoundMacros.controls(voice(song, st))
    fun control(song: Song, st: ViewState) = controls(song, st).getOrNull(st.instField - 2)

    fun settle(song: Song, st: ViewState) {
        if (!friendly(st)) return
        val chip = NesInstrumentView.chip(song, st)
        if (NesRules.chip(song.nes.expansion, st.listenChannel) != chip)
            st.listenChannel = song.system.channels.indices.firstOrNull { NesRules.chip(song.nes.expansion, it) == chip } ?: 0
        if (st.instField == 0) st.instField = 2
        st.instField = st.instField.coerceIn(-1, controls(song, st).size + 1)
        st.dpcmKey = st.dpcmKey.coerceIn(NesSystem.noteRange)
        st.textPos = st.textPos.coerceIn(0, EditorSession.MAX_NAME - 1)
    }

    fun selectMode(v: ViewController, advanced: Boolean, keepFocus: Boolean = false) {
        MusicalPanelMode.select(v, panel(v.state), advanced, keepFocus)
        settle(v.session.song, v.state)
    }

    fun moveSlot(v: ViewController, delta: Int) {
        val field = v.state.instField
        NesInstrumentView.moveSlot(v, delta)
        v.state.instField = field
        settle(v.session.song, v.state)
        if (v.editHeld) { v.useEdit(); panel(v.state).editBlocked = true }
    }

    fun chooseSlot(v: ViewController, slot: Int) {
        if (slot == v.state.instrument) NesInstrumentView.publish(v) else moveSlot(v, slot - v.state.instrument)
    }

    fun maximum(c: NesSoundControl, song: Song, st: ViewState): Int = when (c) {
        NesSoundControl.VOICE -> song.system.channels.lastIndex
        NesSoundControl.KEY -> NesSystem.noteRange.last - NesSystem.noteRange.first
        NesSoundControl.SAMPLE -> if (voice(song, st) == NesVoice.PCM) song.nes.pcmSamples.size else song.nes.samples.size
        else -> c.maximum
    }

    fun value(c: NesSoundControl, song: Song, st: ViewState, ins: NesInstrument): Int? = when (c) {
        NesSoundControl.VOICE -> st.listenChannel
        NesSoundControl.KEY -> st.dpcmKey - NesSystem.noteRange.first
        else -> NesSoundMacros.value(c, ins, voice(song, st), st.dpcmKey)
    }

    fun label(c: NesSoundControl, song: Song, st: ViewState, value: Int): String = when (c) {
        NesSoundControl.VOICE -> song.system.channels[value].name
        NesSoundControl.KEY -> NesSystem.noteName(value + NesSystem.noteRange.first)
        NesSoundControl.SAMPLE -> if (value == 0) "Unmapped" else if (voice(song, st) == NesVoice.PCM)
            song.nes.pcmSamples.getOrNull(value - 1)?.name ?: "Missing sample"
            else song.nes.samples.getOrNull(value - 1)?.name ?: "Missing sample"
        else -> NesSoundMacros.label(c, value)
    }

    /** Channel selection is also the explicit chip selector; never retypes a sound just by opening the face. */
    fun set(v: ViewController, c: NesSoundControl, value: Int) {
        val st = v.state
        val song = v.session.song
        if (value !in 0..maximum(c, song, st)) return
        when (c) {
            NesSoundControl.VOICE -> {
                if (st.listenChannel == value) return
                panel(st).invalidate()
                val chip = NesRules.chip(song.nes.expansion, value)
                if (chip != NesInstrumentView.chip(song, st) && !v.session.setNesChip(st.instrument, chip)) return
                st.listenChannel = value
                st.envelope = when (voice(v.session.song, st)) {
                    NesVoice.DPCM -> NesInstrumentView.DPCM_PAGE
                    NesVoice.PCM -> NesInstrumentView.PCM_PAGE
                    NesVoice.VRC7 -> NesInstrumentView.FM_PAGE
                    NesVoice.N163, NesVoice.FDS -> NesInstrumentView.WAVE_PAGE
                    NesVoice.EPSM_FM -> NesInstrumentView.EPSM_FM_PAGE
                    NesVoice.RHYTHM -> NesInstrumentView.EPSM_RHYTHM_PAGE
                    else -> 0
                }
                st.instField = 2
                if (v.editHeld) { v.useEdit(); panel(st).editBlocked = true }
                NesInstrumentView.publish(v)
            }
            NesSoundControl.KEY -> {
                panel(st).invalidate()
                st.dpcmKey = value + NesSystem.noteRange.first
            }
            else -> if (v.session.editNesInstrument(st.instrument, "Set ${c.label}") {
                NesSoundMacros.apply(c, it, voice(song, st), value, st.dpcmKey, maximum(NesSoundControl.SAMPLE, song, st))
            }) heard(v)
        }
    }

    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val p = panel(st)
        if (p.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (p.advanced) {
            if (st.instField != -1) return false
            if (v.editHeld) v.useEdit()
            if (dx != 0) selectMode(v, dx > 0, true) else if (dy < 0) st.instField = 0
            return true
        }
        val song = v.session.song
        settle(song, st)
        if (!v.editHeld) {
            if (dy != 0) moveSlot(v, -dy) else MusicalPanelMode.cycleSoundField(v, p, controls(song, st).size, dx)
            return true
        }
        v.useEdit()
        when (st.instField) {
            -1 -> if (dx != 0) selectMode(v, dx > 0, true)
            1 -> if (dx != 0) v.session.editNesInstrument(st.instrument, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx, EditorSession.MAX_NAME))
            } else st.textPos = (st.textPos - dy).coerceIn(0, EditorSession.MAX_NAME - 1)
            else -> control(song, st)?.let { c ->
                val ins = NesInstrumentView.sound(song, st) ?: return true
                val delta = dx + dy * if (c == NesSoundControl.SPEED) 64 else if (maximum(c, song, st) > 31) 8 else 1
                if (delta != 0) {
                    val old = value(c, song, st, ins)
                    set(v, c, if (old == null) (if (delta > 0) 0 else maximum(c, song, st)) else (old + delta).coerceIn(0, maximum(c, song, st)))
                }
            }
        }
        return true
    }

    fun heard(v: ViewController) {
        if (v.auditionHeld || v.editHeld && !panel(v.state).editBlocked) NesInstrumentView.audition(v)
    }

    fun command(v: ViewController, a: Action): Boolean {
        if (!friendly(v.state)) return false
        if (panel(v.state).editBlocked && v.editHeld) { v.useEdit(); return true }
        val st = v.state
        val s = v.session
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                panel(st).invalidate()
                if (v.editHeld) { v.useEdit(); panel(st).editBlocked = true }
                st.instField = 2 + ((st.instField - 2).coerceAtLeast(0) + if (a == Action.NEXT_CHANNEL) 1 else -1).mod(controls(s.song, st).size)
            }
            Action.COPY -> s.copyNesInstrument(st.instrument)
            Action.PASTE -> {
                panel(st).invalidate()
                s.pasteNesInstrument(st.instrument)
                settle(s.song, st)
                if (v.editHeld) { v.useEdit(); panel(st).editBlocked = true }
            }
            Action.CUT -> {
                panel(st).invalidate()
                s.copyNesInstrument(st.instrument)
                s.setNesInstrument(st.instrument, defaultNesInstrument(st.instrument), "Cut sound")
                settle(s.song, st)
                if (v.editHeld) { v.useEdit(); panel(st).editBlocked = true }
            }
            Action.DUPLICATE -> {
                if (st.instrument >= NesSystem.SLOTS) return true
                val ins = NesInstrumentView.sound(s.song, st) ?: return true
                panel(st).invalidate()
                s.setNesInstrument(st.instrument + 1, ins.copy(name = (ins.name + "+").take(EditorSession.MAX_NAME)), "Duplicate sound")
                moveSlot(v, 1)
            }
            else -> return false
        }
        return true
    }

    fun hints(v: ViewController, h: MutableList<Hint>) {
        h += Hint(v.ud(), "Sound slot")
        h += Hint(v.lr(), "Parameter")
        h += Hint(v.editLr(), if (v.state.instField == -1) "Sound / Advanced" else if (v.state.instField == 1) "Change letter" else "Shape sound")
        if (v.state.instField == 1) h += Hint(v.editUd(), "Previous / next letter")
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen pitch")
        h += Hint(v.copyPaste(), "Copy/paste sound")
    }
}
