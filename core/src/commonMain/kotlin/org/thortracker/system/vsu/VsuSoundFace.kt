package org.thortracker.system.vsu

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.system.panel.MusicalPanelMode

internal object VsuSoundFace {
    fun friendly(st: ViewState) = !st.soundPanel("vsu").advanced
    fun settle(st: ViewState) { if (friendly(st) && st.instField == 0) st.instField = 2 }
    fun selectMode(v: ViewController, advanced: Boolean, focus: Boolean = false) {
        MusicalPanelMode.select(v, v.state.soundPanel("vsu"), advanced, focus)
        settle(v.state)
    }
    fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val field = st.instField
        if (v.editHeld) { v.useEdit(); st.soundPanel("vsu").editBlocked = true }
        VsuInstrumentView.moveSlot(v, delta)
        st.instField = field
        settle(st)
    }
    fun lane(v: ViewController, next: Int) {
        val st = v.state
        val n = next.coerceIn(0, 5)
        if (st.listenChannel == n) return
        st.soundPanel("vsu").invalidate()
        if (v.editHeld) { v.useEdit(); st.soundPanel("vsu").editBlocked = true }
        st.listenChannel = n
        VsuInstrumentView.publish(v)
        if (v.auditionHeld) VsuInstrumentView.audition(v)
    }
    fun set(v: ViewController, i: Int, value: Int) {
        if (i == 0) { lane(v, value); return }
        val st = v.state
        val actual = VsuInstrumentView.sound(v.session.song, st)
        val ins = actual ?: defaultVsuInstrument(st.instrument)
        if (VsuSoundMacros.reason(i, ins, st.listenChannel) != null) return
        val next = VsuSoundMacros.apply(i, ins, value, st.listenChannel)
        if (actual != null && next == actual) return
        if (v.session.setVsuInstrument(st.instrument, next, "Set ${VsuSoundMacros.title(i, st.listenChannel)}")) heard(v)
    }
    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val panel = st.soundPanel("vsu")
        if (panel.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (panel.advanced) {
            if (st.instField == 0 && dy > 0 && !v.editHeld) { st.instField = -1; return true }
            if (st.instField != -1) return false
            if (v.editHeld) v.useEdit()
            if (dx != 0) selectMode(v, dx > 0, true) else if (dy < 0) st.instField = 0
            return true
        }
        if (!v.editHeld) {
            if (dy != 0) moveSlot(v, -dy) else MusicalPanelMode.cycleSoundField(v, panel, VsuSoundMacros.COUNT, dx)
            return true
        }
        v.useEdit()
        when (st.instField) {
            -1 -> if (dx != 0) selectMode(v, dx > 0, true)
            1 -> if (dx != 0) v.session.editVsuInstrument(st.instrument, "Rename sound") { it.copy(name = NameEdit.edit(it.name, st.textPos, dx, VsuInstrumentView.NAME_MAX)) }
                else st.textPos = (st.textPos - dy).coerceIn(0, VsuInstrumentView.NAME_MAX - 1)
            else -> {
                val i = (st.instField - 2).coerceIn(0, VsuSoundMacros.COUNT - 1)
                val ins = VsuInstrumentView.sound(v.session.song, st) ?: defaultVsuInstrument(st.instrument)
                val old = VsuSoundMacros.value(i, ins, st.listenChannel)
                val delta = dx + dy
                if (delta != 0) set(v, i, if (old == null) (if (delta > 0) 0 else VsuSoundMacros.maximum(i, st.listenChannel)) else old + delta)
            }
        }
        return true
    }
    fun press(v: ViewController): Boolean {
        v.state.soundPanel("vsu").editBlocked = false
        if (v.state.instField == -1) return true
        if (!friendly(v.state)) return false
        if (v.state.instField >= 2 && VsuInstrumentView.sound(v.session.song, v.state) != null) VsuInstrumentView.audition(v)
        return true
    }
    fun release(v: ViewController, tap: Boolean): Boolean {
        val panel = v.state.soundPanel("vsu")
        if (panel.editBlocked) { panel.editBlocked = false; if (!v.auditionHeld) v.session.releaseAudition(); return true }
        if (v.state.instField == -1) { if (tap) selectMode(v, !panel.advanced, true); return true }
        if (!friendly(v.state)) return false
        if (!v.auditionHeld) v.session.releaseAudition()
        if (tap && v.state.instField > 2 && VsuInstrumentView.sound(v.session.song, v.state) == null)
            v.session.setVsuInstrument(v.state.instrument, defaultVsuInstrument(v.state.instrument), "New sound")
        return true
    }
    fun clear(v: ViewController): Boolean {
        if (v.state.instField == -1 || v.state.soundPanel("vsu").editBlocked) return true
        if (!friendly(v.state)) return false
        if (v.state.instField == 1) v.session.editVsuInstrument(v.state.instrument, "Rename sound") {
            it.copy(name = ViewController.deleteChar(it.name, v.state.textPos))
        }
        return true
    }
    fun heard(v: ViewController) { if (v.auditionHeld || (v.editHeld && !v.state.soundPanel("vsu").editBlocked)) VsuInstrumentView.audition(v) }
    fun hints(v: ViewController, h: MutableList<Hint>) {
        h += Hint(v.ud(), "Sound slot")
        h += Hint(v.lr(), "Parameter / face")
        h += Hint(v.editLr(), if (v.state.instField == 1) "Change letter" else "Shape sound")
        h += Hint(v.editUd(), if (v.state.instField == 1) "Previous / next letter" else "Shape sound")
        h += Hint(v.channels(), "Listen lane")
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen pitch")
        h += Hint(v.copyPaste(), "Copy/paste sound")
    }
}
