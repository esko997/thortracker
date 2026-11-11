package org.thortracker.system.scc

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.system.panel.MusicalPanelMode

internal object SccSoundFace {
    fun settle(v: ViewController) {
        val st = v.state
        val p = st.soundPanel("scc")
        if (!p.advanced && st.instField == 0) st.instField = 2
        st.instField = st.instField.coerceIn(-1, if (p.advanced) SccInstrumentView.rows(st).lastIndex else SccSoundMacros.COUNT + 1)
    }
    fun mode(v: ViewController, advanced: Boolean, focus: Boolean = false) {
        MusicalPanelMode.select(v, v.state.soundPanel("scc"), advanced, focus)
        settle(v)
    }
    fun invalidate(v: ViewController) {
        v.state.soundPanel("scc").invalidate()
        if (v.editHeld) { v.useEdit(); v.state.soundPanel("scc").editBlocked = true }
    }
    fun moveSlot(v: ViewController, delta: Int) {
        val field = v.state.instField
        invalidate(v)
        SccInstrumentView.moveSlot(v, delta)
        v.state.instField = field
        settle(v)
    }
    fun set(v: ViewController, i: Int, n: Int) {
        val st = v.state
        if (i == 4) {
            st.listenChannel = n.coerceIn(0, 4)
            if (v.auditionHeld) SccInstrumentView.audition(v)
            return
        }
        val original = v.session.song.sccInstrument(st.instrument) ?: defaultSccInstrument(st.instrument)
        if (!SccSoundMacros.enabled(i, original)) return
        val next = SccSoundMacros.apply(i, original, n)
        if (next === original && v.session.song.sccInstrument(st.instrument) != null) return
        if (v.session.setSccInstrument(st.instrument, next, "Shape SCC sound")) {
            SccInstrumentView.publish(v)
            if (v.auditionHeld || v.editHeld) SccInstrumentView.audition(v)
        }
    }
    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val p = st.soundPanel("scc")
        if (p.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (p.advanced && st.instField != -1) return false
        if (p.advanced) {
            if (v.editHeld) v.useEdit()
            if (dx != 0) mode(v, dx > 0, true) else if (dy < 0) st.instField = 0
            return true
        }
        if (!v.editHeld) {
            if (dy != 0) moveSlot(v, -dy) else MusicalPanelMode.cycleSoundField(v, p, SccSoundMacros.COUNT, dx)
            return true
        }
        v.useEdit()
        when (st.instField) {
            -1 -> if (dx != 0) mode(v, dx > 0, true)
            1 -> if (dx != 0) v.session.editSccInstrument(st.instrument, "Rename sound") { it.copy(name = NameEdit.edit(it.name, st.textPos, dx, EditorSession.MAX_NAME)) }
                else st.textPos = (st.textPos - dy).coerceIn(0, EditorSession.MAX_NAME - 1)
            else -> {
                val i = st.instField - 2
                val ins = v.session.song.sccInstrument(st.instrument) ?: defaultSccInstrument(st.instrument)
                val value = if (i == 4) st.listenChannel else SccSoundMacros.value(i, ins)
                set(v, i, if (value < 0) 0 else value + dx + dy)
            }
        }
        return true
    }
    fun release(v: ViewController, tap: Boolean): Boolean {
        val p = v.state.soundPanel("scc")
        if (p.editBlocked) {
            p.editBlocked = false
            if (!v.auditionHeld) v.session.releaseAudition()
            return true
        }
        if (v.state.instField == -1) { if (tap) mode(v, !p.advanced, true); return true }
        if (p.advanced) return false
        if (!v.auditionHeld) v.session.releaseAudition()
        if (tap && v.state.instField in 2..5 && v.session.song.sccInstrument(v.state.instrument) == null) {
            val i = v.state.instField - 2
            val ins = v.session.song.sccInstrument(v.state.instrument) ?: defaultSccInstrument(v.state.instrument)
            set(v, i, maxOf(0, SccSoundMacros.value(i, ins)))
        }
        return true
    }
    fun rename(v: ViewController) {
        invalidate(v)
        val st = v.state
        st.instField = 1
        val session = v.session
        val slot = st.instrument
        val before = session.song
        val original = before.sccInstrument(slot) ?: run { session.note("Shape this empty slot before naming it."); return }
        val panel = st.soundPanel("scc")
        val revision = panel.revision
        val generation = session.documentGeneration
        session.requestText("Sound name", original.name, EditorSession.MAX_NAME) { name ->
            if (st.kind == ViewKind.INSTRUMENT && st.instrument == slot && !panel.advanced &&
                panel.revision == revision && session.documentGeneration == generation && session.song === before) {
                session.setSccInstrument(slot, original.copy(name = name.take(EditorSession.MAX_NAME)), "Rename sound")
            }
        }
    }

    fun command(v: ViewController, a: Action): Boolean {
        if (v.state.soundPanel("scc").editBlocked && v.editHeld) { v.useEdit(); return true }
        if (v.state.soundPanel("scc").advanced) return false
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> MusicalPanelMode.cycleSoundField(v, v.state.soundPanel("scc"), SccSoundMacros.COUNT, if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> v.session.copySccInstrument(v.state.instrument)
            Action.PASTE -> {
                invalidate(v)
                v.session.pasteSccSound(v.state.instrument, null)
            }
            Action.CUT -> { invalidate(v); v.session.copySccInstrument(v.state.instrument); v.session.setSccInstrument(v.state.instrument, null, "Cut sound") }
            // The native duplicate owns the copy and slot selection; terminate the Sound gesture first.
            Action.DUPLICATE -> { invalidate(v); return false }
            else -> return false
        }
        return true
    }
    fun hints(v: ViewController, h: MutableList<Hint>) {
        h += Hint(v.ud(), "Sound slot"); h += Hint(v.lr(), "Control / face / name")
        h += Hint(v.editLr(), "Shape sound"); h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen pitch")
        if (v.state.instField == 1) h += Hint(v.editUd(), "Name caret")
    }
}
