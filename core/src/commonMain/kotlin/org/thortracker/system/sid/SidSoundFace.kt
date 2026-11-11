package org.thortracker.system.sid

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.system.panel.MusicalPanelMode

/** Controller and touch share the same musical controls and document edit path. */
internal object SidSoundFace {
    private val editLabels = Array(SidSoundControl.entries.size) { "Set SID ${SidSoundControl.entries[it].label.lowercase()}" }
    fun selectMode(v: ViewController, advanced: Boolean, keepModeFocus: Boolean = false) =
        MusicalPanelMode.select(v, v.state.sidPanel, advanced, keepModeFocus)

    fun enabled(control: SidSoundControl, ins: SidInstrument): Boolean =
        !SidSoundMacros.overridden(control, ins) &&
            (control != SidSoundControl.PULSE || SidSoundMacros.value(SidSoundControl.VOICE, ins) == 2)

    fun set(v: ViewController, slot: Int, control: SidSoundControl, index: Int): Boolean {
        val ins = v.session.song.sidInstrument(slot) ?: return false
        if (!enabled(control, ins)) return false
        val changed = v.session.editSidInstrument(slot, editLabels[control.ordinal]) { SidSoundMacros.apply(control, it, index) }
        if (changed) heard(v)
        return changed
    }

    fun heard(v: ViewController) {
        if (v.auditionHeld || v.editHeld) SidInstrumentView.audition(v)
    }

    private fun step(v: ViewController, delta: Int) {
        val control = SidSoundControl.entries.getOrNull(v.state.instField - 2) ?: return
        val ins = SidInstrumentView.sound(v.session.song, v.state) ?: return
        val value = SidSoundMacros.value(control, ins)
        val next = if (value == null) (if (delta < 0) SidSoundMacros.steps(control) - 1 else 0)
            else (value + delta).coerceIn(0, SidSoundMacros.steps(control) - 1)
        set(v, v.state.instrument, control, next)
    }

    /** Handles the shared mode row in either face, and all navigation in Sound. */
    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val panel = st.sidPanel
        panel.invalidate()
        if (panel.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (st.instField == -1) {
            if (v.editHeld) v.useEdit()
            if (dx != 0) selectMode(v, dx > 0, true)
            else if (dy < 0) st.instField = 0
            return true
        }
        if (panel.advanced) {
            if (!v.editHeld && st.instField == 0 && dy > 0) { st.instField = -1; return true }
            return false
        }
        if (v.editHeld) {
            v.useEdit()
            when (st.instField) {
                0 -> SidInstrumentView.moveSlot(v, dx + dy * 8)
                1 -> v.session.editSidInstrument(st.instrument, "Rename sound") {
                    it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, SidInstrument.NAME_MAX))
                }
                else -> step(v, dx + dy)
            }
        } else if (dy != 0) {
            st.instField = (st.instField - dy).coerceIn(-1, SidSoundControl.entries.size + 1)
            st.textPos = 0
        } else if (st.instField == 1) {
            st.textPos = (st.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
        } else SidInstrumentView.moveSlot(v, dx)
        return true
    }

    fun editPress(v: ViewController): Boolean {
        val st = v.state
        if (st.sidPanel.editBlocked || st.instField == -1) return true
        if (st.sidPanel.advanced) return false
        if (st.instField >= 2) SidInstrumentView.audition(v)
        return true
    }

    fun editRelease(v: ViewController, tap: Boolean): Boolean {
        val st = v.state
        if (st.sidPanel.editBlocked) {
            st.sidPanel.editBlocked = false
            if (!v.auditionHeld) v.session.releaseAudition()
            return true
        }
        if (st.instField == -1) {
            if (tap) selectMode(v, !st.sidPanel.advanced, true)
            return true
        }
        if (st.sidPanel.advanced) return false
        if (!v.auditionHeld) v.session.releaseAudition()
        if (tap && st.instField == 0) SidInstrumentView.publish(v)
        return true
    }

    fun clear(v: ViewController): Boolean {
        if (v.state.instField == -1) return true
        if (v.state.sidPanel.advanced) return false
        v.state.sidPanel.invalidate()
        // A musical control never resets unrelated programs. Sound reset remains an Advanced operation.
        if (v.state.instField == 1) v.session.editSidInstrument(v.state.instrument, "Rename sound") {
            it.copy(name = ViewController.deleteChar(it.name, v.state.textPos))
        }
        return true
    }

    fun hints(v: ViewController, h: MutableList<Hint>): Boolean {
        if (v.state.instField == -1) {
            h += Hint(v.lr(), "Sound / Advanced")
            h += Hint(v.k(Action.EDIT), "Switch face")
            return true
        }
        if (v.state.sidPanel.advanced) return false
        h += Hint(v.ud(), "Choose control")
        h += Hint(v.editLr(), if (v.state.instField == 1) "Change letter" else "Shape sound")
        h += Hint(v.lr(), if (v.state.instField == 1) "Letter position" else "Sound slot")
        h += Hint(v.channels(), "Listen voice")
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen pitch")
        return true
    }
}
