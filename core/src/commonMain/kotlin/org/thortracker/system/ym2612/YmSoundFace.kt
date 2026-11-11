package org.thortracker.system.ym2612

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.system.panel.MusicalPanelMode

/**
 * Pane-only presentation of the same FM slot edited by Curated/Expert/Ch3/Drum. In Sound, bare Up/Down chooses the
 * slot and bare Left/Right cycles mode, name and the musical controls; held Edit changes the focused one.
 */
internal object YmSoundFace {
    fun friendly(song: Song, st: ViewState): Boolean = !st.ymPanel.advanced
    fun count(song: Song, st: ViewState): Int = if (YmInstrumentView.fmPages(song, st))
        YmSoundControl.entries.size else YmAuxSoundControls.count(song, st)
    fun control(st: ViewState): YmSoundControl? = YmSoundControl.entries.getOrNull(st.instField - 2)

    /**
     * Sound has no slot field — Up/Down choose the slot — so Advanced's Slot row (field 0), a fresh view's default, lands
     * on the first musical control and Edit+direction shapes the sound at once.
     */
    fun settle(song: Song, st: ViewState) {
        if (st.instField == 0 && friendly(song, st)) st.instField = 2
    }

    fun selectMode(v: ViewController, advanced: Boolean, keepModeFocus: Boolean = false) {
        v.state.ymPanel.cancelTouch?.invoke()
        MusicalPanelMode.select(v, v.state.ymPanel, advanced, keepModeFocus)
        settle(v.session.song, v.state)
    }

    /** Another slot from Sound, keeping the focused field where the destination supports it. */
    fun moveSlot(v: ViewController, delta: Int) {
        val field = v.state.instField
        YmInstrumentView.moveSlot(v, delta)
        if (friendly(v.session.song, v.state)) { v.state.instField = field; settle(v.session.song, v.state) }
    }

    /** A touched bank slot: another slot keeps the focused field; the selected one is published for entry, as in Advanced. */
    fun chooseSlot(v: ViewController, slot: Int) {
        if (slot == v.state.instrument) YmInstrumentView.publish(v) else moveSlot(v, slot - v.state.instrument)
    }

    fun rename(v: ViewController) {
        val slot = v.state.instrument
        val actual = v.session.song.ymInstrument(slot)
        val original = actual ?: defaultYmInstrument(slot, v.state.listenChannel)
        v.state.instField = 1
        v.state.ymPanel.invalidate()
        if (v.editHeld) { v.useEdit(); v.state.ymPanel.editBlocked = true }
        v.session.requestText("Sound name", original.name, YmInstrumentView.NAME_MAX) { name ->
            if (v.session.song.ymInstrument(slot) === actual)
                v.session.setYmInstrument(slot, original.named(name), "Rename sound")
        }
    }

    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        if (st.ymPanel.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (st.ymPanel.advanced) {
            // Advanced keeps its precise rows; only the shared mode row above them is handled here.
            if (st.instField != -1) return false
            if (v.editHeld) v.useEdit()
            if (dx != 0) selectMode(v, dx > 0, true)
            else if (dy < 0) st.instField = 0
            return true
        }
        if (v.editHeld) {
            v.useEdit()
            when (st.instField) {
                -1 -> if (dx != 0) selectMode(v, dx > 0, true)
                // Field 0 never stays in Sound ([settle]); Up = previous letter, Down = next letter, within the limit.
                1 -> if (dx != 0) v.session.renameYm(st.instrument) { NameEdit.edit(it, st.textPos, dx, YmInstrumentView.NAME_MAX) }
                    else st.textPos = (st.textPos - dy).coerceIn(0, YmInstrumentView.NAME_MAX - 1)
                else -> if (!YmInstrumentView.fmPages(v.session.song, st)) {
                    YmAuxSoundControls.step(v, (st.instField - 2).coerceIn(0, count(v.session.song, st) - 1), dx + dy)
                } else control(st)?.let { control ->
                    val delta = dx + dy * if (control == YmSoundControl.BRIGHTNESS) 8 else 1
                    if (v.session.editYmFm(st.instrument, "Set ${control.label}") { YmSoundMacros.step(control, it, delta) }) heard(v)
                }
            }
        } else if (dy != 0) moveSlot(v, -dy)
        else MusicalPanelMode.cycleSoundField(v, st.ymPanel, count(v.session.song, st), dx)
        return true
    }

    fun heard(v: ViewController) {
        if (v.auditionHeld || v.editHeld && v.state.instField >= 2) YmInstrumentView.audition(v)
    }

    fun hints(v: ViewController, h: MutableList<Hint>) {
        val sound = !v.state.ymPanel.advanced
        when (v.state.instField) {
            -1 -> {
                h += Hint(if (sound) v.editLr() else v.lr(), "Sound / Advanced")
                h += Hint(v.k(Action.EDIT), "Switch face")
                if (sound) { h += Hint(v.lr(), "Parameter"); h += Hint(v.ud(), "Sound slot") }
            }
            1 -> {
                h += Hint(v.editLr(), "Change letter"); h += Hint(v.editUd(), "Previous / next letter")
                h += Hint(v.k(Action.CLEAR), "Delete letter")
                h += Hint(v.lr(), "Parameter"); h += Hint(v.ud(), "Sound slot")
            }
            else -> { h += Hint(v.editLr(), "Shape sound"); h += Hint(v.lr(), "Parameter"); h += Hint(v.ud(), "Sound slot") }
        }
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen pitch")
        h += Hint(v.copyPaste(), "Copy/paste sound")
    }
}
