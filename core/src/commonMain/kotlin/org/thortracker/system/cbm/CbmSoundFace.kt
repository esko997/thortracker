package org.thortracker.system.cbm

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.system.panel.MusicalPanelMode

internal object CbmSoundFace {
    private val defaults = Array(CbmSystem.SLOTS) { defaultCbmInstrument(it + 1) }
    fun panel(v: ViewController) = v.state.soundPanel("cbm")
    fun friendly(v: ViewController) = !panel(v).advanced
    fun shown(v: ViewController) = CbmInstrumentView.sound(v.session.song, v.state) ?: defaults[v.state.instrument.coerceIn(1, CbmSystem.SLOTS) - 1]
    fun controls(v: ViewController) = CbmSoundMacros.controls(v.session.song.cbm.chip, shown(v))
    fun settle(v: ViewController) {
        val st = v.state
        st.textPos = st.textPos.coerceIn(0, CbmInstrumentView.NAME_MAX - 1)
        if (friendly(v)) {
            st.instField = st.instField.coerceIn(-1, controls(v).size + 1)
            if (st.instField == 0) st.instField = 2
        }
    }
    fun selectMode(v: ViewController, advanced: Boolean, focus: Boolean = false) {
        MusicalPanelMode.select(v, panel(v), advanced, focus)
        settle(v)
    }
    fun moveSlot(v: ViewController, delta: Int) {
        val field = v.state.instField
        panel(v).invalidate()
        if (v.editHeld) { v.useEdit(); panel(v).editBlocked = true }
        CbmInstrumentView.moveSlot(v, delta)
        if (friendly(v)) { v.state.instField = field; settle(v) }
    }
    fun maximum(v: ViewController, c: CbmSoundControl): Int {
        val ins = shown(v)
        return CbmSoundMacros.maximum(c, v.session.song.cbm, ins)
    }
    fun value(v: ViewController, c: CbmSoundControl): Int? {
        val ins = shown(v)
        return CbmSoundMacros.value(c, v.session.song.cbm, ins,
            CbmInstrumentView.listenChannel(v.session.song, v.state, ins))
    }
    private fun blockEdit(v: ViewController) {
        if (v.editHeld) { v.useEdit(); panel(v).editBlocked = true }
    }
    fun set(v: ViewController, c: CbmSoundControl, n: Int, original: CbmInstrument? = null): Boolean {
        if (!CbmSoundMacros.enabled(c, v.session.song.cbm, shown(v))) return false
        if (c == CbmSoundControl.LANE) {
            panel(v).invalidate()
            v.state.listenChannel = n.coerceIn(0, maximum(v, c))
            if (v.editHeld) { v.useEdit(); panel(v).editBlocked = true }
            if (v.auditionHeld) CbmInstrumentView.audition(v)
            return false
        }
        val changed = v.session.editCbmInstrument(v.state.instrument, "Set ${c.label}") {
            CbmSoundMacros.set(c, v.session.song.cbm, original ?: it, n)
        }
        if (changed) {
            heard(v)
            if (c == CbmSoundControl.VOICE) { panel(v).invalidate(); blockEdit(v); settle(v) }
        }
        return changed
    }
    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val p = panel(v)
        val st = v.state
        if (p.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (p.advanced) {
            if (st.instField == 0 && dy > 0 && !v.editHeld) { st.instField = -1; return true }
            if (st.instField != -1) return false
            if (v.editHeld) v.useEdit()
            if (dx != 0) selectMode(v, dx > 0, true) else if (dy < 0) st.instField = 0
            return true
        }
        if (v.editHeld) {
            v.useEdit()
            when (st.instField) {
                -1 -> if (dx != 0) selectMode(v, dx > 0, true)
                1 -> if (dx != 0) v.session.editCbmInstrument(st.instrument, "Rename sound") {
                    it.copy(name = NameEdit.edit(it.name, st.textPos, dx, CbmInstrumentView.NAME_MAX))
                } else st.textPos = (st.textPos - dy).coerceIn(0, CbmInstrumentView.NAME_MAX - 1)
                else -> controls(v).getOrNull(st.instField - 2)?.let { c ->
                    if (dx == 0 && c == CbmSoundControl.LANE) return@let
                    val current = value(v, c)
                    set(v, c, ((current ?: if (dx + dy < 0) maximum(v, c) + 1 else -1) + dx + dy).coerceIn(0, maximum(v, c)))
                }
            }
        } else if (dy != 0) moveSlot(v, -dy)
        else MusicalPanelMode.cycleSoundField(v, p, controls(v).size, dx)
        return true
    }
    fun heard(v: ViewController) {
        if (v.auditionHeld || v.editHeld) CbmInstrumentView.audition(v)
    }
    fun command(v: ViewController, a: Action): Boolean {
        if (!friendly(v)) return false
        if (panel(v).editBlocked && v.editHeld) { v.useEdit(); return true }
        val slot = v.state.instrument
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                val ins = shown(v)
                if (v.session.song.cbm.chip == CbmChip.VIC || ins.kind == CbmKind.TONE && !ins.playsNoise) {
                    val current = CbmInstrumentView.listenChannel(v.session.song, v.state, ins)
                    set(v, CbmSoundControl.LANE, (current + if (a == Action.NEXT_CHANNEL) 1 else -1)
                        .mod(maximum(v, CbmSoundControl.LANE) + 1))
                }
            }
            Action.COPY -> v.session.copyCbmInstrument(slot)
            Action.PASTE -> {
                panel(v).invalidate()
                v.session.pasteCbm(slot, null)
                blockEdit(v)
                settle(v)
            }
            Action.CUT -> {
                panel(v).invalidate()
                v.session.copyCbmInstrument(slot)
                v.session.setCbmInstrument(slot, null, "Cut sound")
                blockEdit(v)
                settle(v)
            }
            Action.DUPLICATE -> {
                val ins = CbmInstrumentView.sound(v.session.song, v.state) ?: return true
                if (slot < CbmSystem.SLOTS) { v.session.setCbmInstrument(slot + 1, ins.copy(name = (ins.name + "+").take(CbmInstrumentView.NAME_MAX)), "Duplicate sound"); moveSlot(v, 1) }
            }
            else -> return false
        }
        return true
    }
    fun hints(v: ViewController, h: MutableList<Hint>) {
        h += Hint(v.lr(), "Parameter")
        h += Hint(v.ud(), "Sound slot")
        h += Hint(v.editLr(), if (v.state.instField == -1) "Sound / Advanced" else if (v.state.instField == 1) "Change letter" else "Shape sound")
        if (v.state.instField == 1) h += Hint(v.editUd(), "Letter position")
        h += Hint(v.channels(), "Listen lane")
        h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen pitch")
    }
}
