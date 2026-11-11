package org.thortracker.system.pce

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.system.panel.MusicalPanelMode

internal object PceSoundFace {
    fun settle(v: ViewController) {
        val st = v.state
        val p = st.soundPanel("pce")
        if (!p.advanced && st.instField == 0) st.instField = 2
        st.instField = st.instField.coerceIn(-1, if (p.advanced) PceInstrumentView.rows(v.session.song, st).lastIndex else PceSoundMacros.COUNT + 1)
    }
    fun mode(v: ViewController, advanced: Boolean, focus: Boolean = false) {
        MusicalPanelMode.select(v, v.state.soundPanel("pce"), advanced, focus)
        settle(v)
    }
    fun invalidate(v: ViewController) {
        v.state.soundPanel("pce").invalidate()
        if (v.editHeld) { v.useEdit(); v.state.soundPanel("pce").editBlocked = true }
    }
    fun moveSlot(v: ViewController, delta: Int) {
        val field = v.state.instField
        invalidate(v)
        PceInstrumentView.moveSlot(v, delta)
        v.state.instField = field
        settle(v)
    }
    fun set(v: ViewController, i: Int, n: Int) {
        val st = v.state
        if (i == 5) {
            val ins = v.session.song.pceInstrument(st.instrument) ?: defaultPceInstrument(st.instrument)
            st.listenChannel = PceSoundMacros.listenChannel(ins, n)
            if (v.auditionHeld) PceInstrumentView.audition(v)
            return
        }
        val original = v.session.song.pceInstrument(st.instrument) ?: defaultPceInstrument(st.instrument)
        val next = PceSoundMacros.apply(i, v.session.song.pce, original, n)
        if (next === original && v.session.song.pceInstrument(st.instrument) != null) return
        if (!PceSoundMacros.enabled(i, v.session.song.pce, original)) return
        if (i == 0 && next.kind != original.kind) invalidate(v)
        if (v.session.setPceInstrument(st.instrument, next, "Shape PCE sound")) {
            PceInstrumentView.publish(v)
            if (v.auditionHeld || v.editHeld) PceInstrumentView.audition(v)
        }
    }
    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val p = st.soundPanel("pce")
        if (p.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (p.advanced && st.instField != -1) return false
        if (p.advanced) {
            if (v.editHeld) v.useEdit()
            if (dx != 0) mode(v, dx > 0, true) else if (dy < 0) st.instField = 0
            return true
        }
        if (!v.editHeld) {
            if (dy != 0) moveSlot(v, -dy) else MusicalPanelMode.cycleSoundField(v, p, PceSoundMacros.COUNT, dx)
            return true
        }
        v.useEdit()
        when (st.instField) {
            -1 -> if (dx != 0) mode(v, dx > 0, true)
            1 -> if (dx != 0) v.session.editPceInstrument(st.instrument, "Rename sound") { it.copy(name = NameEdit.edit(it.name, st.textPos, dx, PceInstrumentView.NAME_MAX)) }
                else st.textPos = (st.textPos - dy).coerceIn(0, PceInstrumentView.NAME_MAX - 1)
            else -> {
                val i = st.instField - 2
                val ins = v.session.song.pceInstrument(st.instrument) ?: defaultPceInstrument(st.instrument)
                val value = if (i == 5) PceSoundMacros.listenValue(ins, st.listenChannel) else PceSoundMacros.value(i, ins)
                set(v, i, if (value < 0) 0 else value + dx + dy)
            }
        }
        return true
    }
    fun release(v: ViewController, tap: Boolean): Boolean {
        val p = v.state.soundPanel("pce")
        if (p.editBlocked) {
            p.editBlocked = false
            if (!v.auditionHeld) v.session.releaseAudition()
            return true
        }
        if (v.state.instField == -1) { if (tap) mode(v, !p.advanced, true); return true }
        if (p.advanced) return false
        if (!v.auditionHeld) v.session.releaseAudition()
        if (tap && v.state.instField in 2..6 && v.session.song.pceInstrument(v.state.instrument) == null) {
            val i = v.state.instField - 2
            val ins = v.session.song.pceInstrument(v.state.instrument) ?: defaultPceInstrument(v.state.instrument)
            set(v, i, maxOf(0, PceSoundMacros.value(i, ins)))
        }
        return true
    }
    fun command(v: ViewController, a: Action): Boolean {
        if (v.state.soundPanel("pce").advanced) return false
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> MusicalPanelMode.cycleSoundField(v, v.state.soundPanel("pce"), PceSoundMacros.COUNT, if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> v.session.copyPceInstrument(v.state.instrument)
            Action.PASTE -> { invalidate(v); v.session.pastePce(v.state.instrument, null) }
            Action.CUT -> { invalidate(v); v.session.copyPceInstrument(v.state.instrument); v.session.setPceInstrument(v.state.instrument, null, "Cut sound") }
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
