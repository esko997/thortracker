package org.thortracker.system.ay

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.system.panel.MusicalPanelMode

internal object AySoundFace {
    fun friendly(st: ViewState) = !st.soundPanel("ay").advanced
    fun settle(st: ViewState) { if (friendly(st) && st.instField == 0) st.instField = 2 }
    fun selectMode(v: ViewController, advanced: Boolean, keep: Boolean = false) {
        MusicalPanelMode.select(v, v.state.soundPanel("ay"), advanced, keep)
        settle(v.state)
    }
    fun moveSlot(v: ViewController, delta: Int) {
        val field = v.state.instField
        val panel = v.state.soundPanel("ay")
        if (v.editHeld) { v.useEdit(); panel.editBlocked = true }
        AyInstrumentView.moveSlot(v, delta)
        v.state.instField = field
        settle(v.state)
    }
    fun listen(v: ViewController, delta: Int) {
        val panel = v.state.soundPanel("ay")
        panel.invalidate()
        if (v.editHeld) { v.useEdit(); panel.editBlocked = true }
        v.state.listenChannel = (v.state.listenChannel + delta).mod(3)
        if (v.auditionHeld) AyInstrumentView.audition(v)
    }
    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val panel = st.soundPanel("ay")
        if (panel.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (panel.advanced) {
            if (st.instField != -1) return false
            if (v.editHeld) v.useEdit()
            if (dx != 0) selectMode(v, dx > 0, true) else if (dy < 0) st.instField = 0
            return true
        }
        if (v.editHeld) {
            v.useEdit()
            when (st.instField) {
                -1 -> if (dx != 0) selectMode(v, dx > 0, true)
                1 -> if (dx != 0) {
                    val ins = AyInstrumentView.sound(v.session.song, st) ?: defaultAyInstrument(st.instrument)
                    v.session.setAyInstrument(st.instrument, ins.copy(name = NameEdit.edit(ins.name, st.textPos, dx, AyInstrumentView.NAME_MAX)), "Rename sound")
                } else st.textPos = (st.textPos - dy).coerceIn(0, AyInstrumentView.NAME_MAX - 1)
                else -> AySoundControl.entries.getOrNull(st.instField - 2)?.let { c ->
                    val ins = AyInstrumentView.sound(v.session.song, st) ?: defaultAyInstrument(st.instrument)
                    val next = AySoundMacros.step(c, ins, dx + dy)
                    if (next !== ins && v.session.setAyInstrument(st.instrument, next, "Set ${c.label}")) heard(v)
                }
            }
        } else if (dy != 0) moveSlot(v, -dy)
        else MusicalPanelMode.cycleSoundField(v, panel, AySoundControl.entries.size, dx)
        return true
    }
    fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && !v.state.soundPanel("ay").editBlocked)) AyInstrumentView.audition(v)
    }
    fun command(v: ViewController, action: Action): Boolean {
        val panel = v.state.soundPanel("ay")
        if (panel.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (!friendly(v.state)) return false
        when (action) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> listen(v, if (action == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> v.session.copyAyInstrument(v.state.instrument)
            Action.PASTE -> { panel.invalidate(); v.session.pasteAy(v.state.instrument, null) }
            Action.CUT -> { panel.invalidate(); v.session.copyAyInstrument(v.state.instrument); v.session.setAyInstrument(v.state.instrument, null, "Cut sound") }
            else -> return false
        }
        return true
    }
    fun hints(v: ViewController, h: MutableList<Hint>) {
        h += Hint(v.ud(), "Sound slot"); h += Hint(v.lr(), "Control / Name / face")
        h += Hint(v.editLr(), if (v.state.instField == 1) "Change letter" else "Shape sound")
        if (v.state.instField == 1) h += Hint(v.editUd(), "Move letter cursor")
        h += Hint(v.channels(), "Listen channel A/B/C")
        h += Hint(v.k(Action.AUDITION), "Listen"); h += Hint(v.octaves(), "Listen pitch")
    }
}
