package org.thortracker.system.pokey

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.system.panel.MusicalPanelMode

internal object PokeySoundFace {
    const val COUNT = 6
    fun panel(st: ViewState) = st.soundPanel("pokey")
    fun friendly(st: ViewState) = !panel(st).advanced
    fun settle(st: ViewState) { if (friendly(st) && st.instField == 0) st.instField = 2 }
    fun selectMode(v: ViewController, advanced: Boolean, focus: Boolean = false) {
        MusicalPanelMode.select(v, panel(v.state), advanced, focus)
        settle(v.state)
    }
    fun invalidate(v: ViewController) {
        panel(v.state).invalidate()
        if (v.editHeld) { v.useEdit(); panel(v.state).editBlocked = true }
    }
    fun moveSlot(v: ViewController, delta: Int) {
        val field = v.state.instField
        invalidate(v)
        PokeyInstrumentView.moveSlot(v, delta)
        if (friendly(v.state)) { v.state.instField = field; settle(v.state) }
    }
    fun set(v: ViewController, n: Int, value: Int) {
        val ins = PokeyInstrumentView.sound(v.session.song, v.state) ?: defaultPokeyInstrument(v.state.instrument)

        if ((n == 5 && ins.kind == PokeyKind.SAMPLE) || (n == 0 && value == 4 && v.session.song.pokey.samples.isEmpty())) {
            invalidate(v)
            PokeySampleMenu.open(v.session, v.state.instrument)
            return
        }
        if (!PokeySoundMacros.enabled(v.session.song, ins, n, PokeyInstrumentView.listenChannel(v.state, ins))) return
        val next = PokeySoundMacros.apply(v.session.song, ins, n, value, PokeyInstrumentView.listenChannel(v.state, ins))
        if (next === ins && PokeyInstrumentView.sound(v.session.song, v.state) != null) return
        // A kind change blocks the next held edit, not the audition of this accepted change.
        val restart = v.auditionHeld || (v.editHeld && !panel(v.state).editBlocked && v.state.instField >= 2)
        if (n == 0 && next != ins) invalidate(v)
        if (v.session.setPokeyInstrument(v.state.instrument, next, "Shape sound") && restart) PokeyInstrumentView.audition(v)
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
        if (!v.editHeld) {
            if (dy != 0) moveSlot(v, -dy) else MusicalPanelMode.cycleSoundField(v, p, COUNT, dx)
        } else {
            v.useEdit()
            when (st.instField) {
                -1 -> if (dx != 0) selectMode(v, dx > 0, true)
                1 -> if (dx != 0) v.session.editPokeyInstrument(st.instrument, "Rename sound") {
                    it.copy(name = NameEdit.edit(it.name, st.textPos, dx, PokeyInstrumentView.NAME_MAX))
                } else st.textPos = (st.textPos - dy).coerceIn(0, PokeyInstrumentView.NAME_MAX - 1)
                else -> {
                    val n = (st.instField - 2).coerceIn(0, COUNT - 1)
                    val ins = PokeyInstrumentView.sound(v.session.song, st) ?: defaultPokeyInstrument(st.instrument)
                    val old = PokeySoundMacros.value(v.session.song, ins, n)
                    val delta = dx + dy * (if (ins.kind == PokeyKind.SAMPLE && n == 2) 100 else if (ins.kind == PokeyKind.SAMPLE && n == 3) 12 else 1)
                    if (delta != 0) set(v, n, if (old == null) 0 else old + delta)
                }
            }
        }
        return true
    }
    fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && !panel(v.state).editBlocked && v.state.instField >= 2)) PokeyInstrumentView.audition(v)
    }
    fun command(v: ViewController, a: Action): Boolean {
        if (panel(v.state).editBlocked && v.editHeld) { v.useEdit(); return true }
        if (!friendly(v.state)) return false
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                invalidate(v)
                v.state.instField = 2 + ((v.state.instField - 2).coerceAtLeast(0) + if (a == Action.NEXT_CHANNEL) 1 else COUNT - 1).mod(COUNT)
                return true
            }
            Action.COPY -> { v.session.copyPokeyInstrument(v.state.instrument); return true }
            Action.PASTE -> { invalidate(v); v.session.pastePokey(v.state.instrument, null); return true }
            Action.CUT -> { invalidate(v); v.session.copyPokeyInstrument(v.state.instrument); v.session.setPokeyInstrument(v.state.instrument, null, "Cut sound"); return true }
            else -> return false
        }
    }
    fun hints(v: ViewController, h: MutableList<Hint>) {
        h += Hint(v.ud(), "Sound slot")
        h += Hint(v.lr(), "Mode / name / controls")
        h += Hint(v.editLr(), if (v.state.instField == 1) "Change letter" else "Shape sound")
        if (v.state.instField == 1) h += Hint(v.editUd(), "Letter position")
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen pitch")
    }
}
