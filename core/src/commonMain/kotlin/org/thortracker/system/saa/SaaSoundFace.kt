package org.thortracker.system.saa

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.system.panel.MusicalPanelMode

internal object SaaSoundFace {
    private val defaults = Array(SaaSound.SLOTS) { defaultSaaInstrument(it + 1) }
    fun panel(v: ViewController) = v.state.soundPanel("saa")
    fun friendly(v: ViewController) = !panel(v).advanced
    fun shown(v: ViewController) = SaaInstrumentView.sound(v.session.song, v.state) ?: defaults[v.state.instrument.coerceIn(1, SaaSound.SLOTS) - 1]
    fun controls(v: ViewController) = SaaSoundControl.entries
    fun settle(v: ViewController) {
        val st = v.state
        st.textPos = st.textPos.coerceIn(0, EditorSession.MAX_NAME - 1)
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
        SaaInstrumentView.moveSlot(v, delta)
        if (friendly(v)) { v.state.instField = field; settle(v) }
    }
    fun maximum(v: ViewController, c: SaaSoundControl): Int {
        return if (c == SaaSoundControl.LANE) v.session.song.system.channels.lastIndex else c.maximum
    }
    fun set(v: ViewController, c: SaaSoundControl, n: Int): Boolean {
        if (!SaaSoundMacros.enabled(c, shown(v), v.state.listenChannel)) return false
        if (c == SaaSoundControl.LANE) {
            panel(v).invalidate()
            v.state.listenChannel = n.coerceIn(0, maximum(v, c))
            if (v.editHeld) { v.useEdit(); panel(v).editBlocked = true }
            if (v.auditionHeld) SaaInstrumentView.audition(v)
            return false
        }
        val changed = v.session.editSaaInstrument(v.state.instrument, "Set ${c.label}") { SaaSoundMacros.set(c, it, n, v.state.listenChannel) }
        if (changed) {
            heard(v)
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
                1 -> if (dx != 0) v.session.editSaaInstrument(st.instrument, "Rename sound") {
                    it.copy(name = NameEdit.edit(it.name, st.textPos, dx, EditorSession.MAX_NAME))
                } else st.textPos = (st.textPos - dy).coerceIn(0, EditorSession.MAX_NAME - 1)
                else -> controls(v).getOrNull(st.instField - 2)?.let { c ->
                    if (dx == 0 && c == SaaSoundControl.LANE) return@let
                    val ins = shown(v)
                    val current = SaaSoundMacros.value(c, ins, v.state.listenChannel)
                    set(v, c, ((current ?: if (dx + dy < 0) maximum(v, c) + 1 else -1) + dx + dy).coerceIn(0, maximum(v, c)))
                }
            }
        } else if (dy != 0) moveSlot(v, -dy)
        else MusicalPanelMode.cycleSoundField(v, p, controls(v).size, dx)
        return true
    }
    fun heard(v: ViewController) {
        if (v.auditionHeld || v.editHeld && !panel(v).editBlocked) SaaInstrumentView.audition(v)
    }
    fun command(v: ViewController, a: Action): Boolean {
        if (!friendly(v)) return false
        if (panel(v).editBlocked && v.editHeld) { v.useEdit(); return true }
        val slot = v.state.instrument
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                set(v, SaaSoundControl.LANE,
                    (v.state.listenChannel + if (a == Action.NEXT_CHANNEL) 1 else -1).mod(v.session.song.system.channels.size))
            }
            Action.COPY -> v.session.copySaaInstrument(slot)
            Action.PASTE -> {
                panel(v).invalidate()
                val clip = v.session.clipboard
                if (clip is SaaClip.Sound) v.session.setSaaInstrument(slot, clip.instrument, "Paste sound")
                else v.session.note("Paste an envelope on its Advanced page")
                settle(v)
            }
            Action.CUT -> { panel(v).invalidate(); v.session.copySaaInstrument(slot); v.session.setSaaInstrument(slot, null, "Cut sound") }
            Action.DUPLICATE -> {
                val ins = SaaInstrumentView.sound(v.session.song, v.state) ?: return true
                if (slot < SaaSound.SLOTS) { v.session.setSaaInstrument(slot + 1, ins.copy(name = (ins.name + "+").take(EditorSession.MAX_NAME)), "Duplicate sound"); moveSlot(v, 1) }
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
