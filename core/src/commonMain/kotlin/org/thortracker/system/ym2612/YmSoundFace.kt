package org.thortracker.system.ym2612

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.system.panel.MusicalPanelMode

/** Pane-only presentation of the same FM slot edited by Curated/Expert/Ch3/Drum. */
internal object YmSoundFace {
    fun friendly(song: Song, st: ViewState): Boolean = YmInstrumentView.fmPages(song, st) && !st.ymPanel.advanced
    fun control(st: ViewState): YmSoundControl? = YmSoundControl.entries.getOrNull(st.instField - 2)

    fun selectMode(v: ViewController, advanced: Boolean, keepModeFocus: Boolean = false) {
        if (!YmInstrumentView.fmPages(v.session.song, v.state)) return
        MusicalPanelMode.select(v, v.state.ymPanel, advanced, keepModeFocus)
    }

    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        if (st.ymPanel.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (!YmInstrumentView.fmPages(v.session.song, st)) return false
        if (st.instField == -1) {
            if (v.editHeld) v.useEdit()
            if (dx != 0) selectMode(v, dx > 0, true)
            else if (dy < 0) st.instField = 0
            return true
        }
        if (!friendly(v.session.song, st)) return false
        if (v.editHeld) {
            v.useEdit()
            when (st.instField) {
                0 -> YmInstrumentView.moveSlot(v, dx - dy * 8)
                1 -> v.session.renameYm(st.instrument) { NameEdit.edit(it, st.textPos, dx + 26 * dy, YmInstrumentView.NAME_MAX) }
                else -> control(st)?.let { control ->
                    val delta = dx + dy * if (control == YmSoundControl.BRIGHTNESS) 8 else 1
                    if (v.session.editYmFm(st.instrument, "Set ${control.label}") { YmSoundMacros.step(control, it, delta) }) heard(v)
                }
            }
        } else if (dy != 0) {
            st.instField = (st.instField - dy).coerceIn(-1, YmSoundControl.entries.size + 1)
            st.textPos = 0
        } else if (st.instField == 1) st.textPos = (st.textPos + dx).coerceIn(0, YmInstrumentView.NAME_MAX - 1)
        else YmInstrumentView.moveSlot(v, dx)
        return true
    }

    fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && !v.state.ymPanel.editBlocked && v.state.instField >= 2)) YmInstrumentView.audition(v)
    }

    fun hints(v: ViewController, h: MutableList<Hint>) {
        when (v.state.instField) {
            -1 -> h += Hint(v.lr(), "Sound / Advanced")
            0 -> { h += Hint(v.lr(), "Sound slot"); h += Hint(v.k(Action.EDIT), "Create / select"); h += Hint(v.k(Action.CLEAR), "Empty slot") }
            1 -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            else -> {
                h += Hint(v.editLr(), "Shape sound")
                h += Hint(v.lr(), "Sound slot")
                h += Hint(v.channels(), "Control")
            }
        }
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen pitch")
        h += Hint(v.copyPaste(), "Copy/paste sound")
    }
}
