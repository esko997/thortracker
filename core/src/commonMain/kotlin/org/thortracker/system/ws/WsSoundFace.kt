package org.thortracker.system.ws

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.system.panel.MusicalPanelMode

internal object WsSoundFace {
    fun friendly(st: ViewState) = !st.soundPanel("ws").advanced
    fun settle(st: ViewState) { if (friendly(st) && st.instField == 0) st.instField = 2 }
    fun selectMode(v: ViewController, advanced: Boolean, focus: Boolean = false) {
        MusicalPanelMode.select(v, v.state.soundPanel("ws"), advanced, focus)
        settle(v.state)
    }
    fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val field = st.instField
        if (v.editHeld) { v.useEdit(); st.soundPanel("ws").editBlocked = true }
        WsInstrumentView.moveSlot(v, delta)
        st.instField = field
        settle(st)
    }
    fun lane(v: ViewController, next: Int) {
        val st = v.state
        val sound = v.session.song.ws
        val ins = WsInstrumentView.sound(v.session.song, st) ?: defaultWsInstrument(st.instrument)
        val n = next.coerceIn(0, WsRules.laneCount(sound.color) - 1)
        if (!WsValidator.fits(sound, ins, n) || st.listenChannel == n) return
        st.soundPanel("ws").invalidate()
        if (v.editHeld) { v.useEdit(); st.soundPanel("ws").editBlocked = true }
        st.listenChannel = n
        WsInstrumentView.publish(v)
        if (v.auditionHeld) WsInstrumentView.audition(v)
    }
    fun cycleLane(v: ViewController, delta: Int) {
        val ins = WsInstrumentView.sound(v.session.song, v.state) ?: defaultWsInstrument(v.state.instrument)
        val count = v.session.song.system.channels.size
        val current = WsInstrumentView.listenChannel(v.session.song, v.state, ins)
        for (step in 1..count) {
            val next = (current + step * delta).mod(count)
            if (WsValidator.fits(v.session.song.ws, ins, next)) { lane(v, next); return }
        }
    }
    fun set(v: ViewController, i: Int, value: Int) {
        val st = v.state
        val actual = WsInstrumentView.sound(v.session.song, st)
        val ins = actual ?: defaultWsInstrument(st.instrument)
        if (i == 4 && ins.kind != WsKind.SAMPLE) { lane(v, if (ins.kind == WsKind.NOISE) WsRules.NOISE_LANE else value.coerceIn(0, 3)); return }
        val next = WsSoundMacros.apply(i, ins, value, v.session.song.ws, st.listenChannel)
        if (next == ins && actual != null) return
        if (next === ins && WsSoundMacros.reason(i, ins, v.session.song.ws, st.listenChannel) != null) return
        if (i == 0 && value >= 2 && v.session.song.ws.samples.isEmpty()) {
            v.session.note("Add a recording in Advanced / Sample first")
            return
        }
        if (v.session.setWsInstrument(st.instrument, next, "Set ${WsSoundMacros.title(i, ins)}")) {
            if (next.kind != ins.kind || next.feed != ins.feed) {
                st.soundPanel("ws").invalidate()
                if (v.editHeld) st.soundPanel("ws").editBlocked = true
            }
            st.listenChannel = WsInstrumentView.listenChannel(v.session.song, st, next)
            heard(v)
        }
    }
    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val panel = st.soundPanel("ws")
        if (panel.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (panel.advanced) {
            if (st.instField == 0 && dy > 0 && !v.editHeld) { st.instField = -1; return true }
            if (st.instField != -1) return false
            if (v.editHeld) v.useEdit()
            if (dx != 0) selectMode(v, dx > 0, true) else if (dy < 0) st.instField = 0
            return true
        }
        if (!v.editHeld) {
            if (dy != 0) moveSlot(v, -dy) else MusicalPanelMode.cycleSoundField(v, panel, WsSoundMacros.COUNT, dx)
            return true
        }
        v.useEdit()
        when (st.instField) {
            -1 -> if (dx != 0) selectMode(v, dx > 0, true)
            1 -> if (dx != 0) v.session.editWsInstrument(st.instrument, "Rename sound") { it.copy(name = NameEdit.edit(it.name, st.textPos, dx, WsInstrumentView.NAME_MAX)) }
                else st.textPos = (st.textPos - dy).coerceIn(0, WsInstrumentView.NAME_MAX - 1)
            else -> {
                val i = (st.instField - 2).coerceIn(0, WsSoundMacros.COUNT - 1)
                val ins = WsInstrumentView.sound(v.session.song, st) ?: defaultWsInstrument(st.instrument)
                val old = WsSoundMacros.value(i, ins, st.listenChannel)
                val delta = dx + dy
                if (delta != 0) set(v, i, if (old == null) (if (delta > 0) 0 else WsSoundMacros.maximum(i, ins, v.session.song.ws)) else old + delta)
            }
        }
        return true
    }
    fun press(v: ViewController): Boolean {
        v.state.soundPanel("ws").editBlocked = false
        if (v.state.instField == -1) return true
        if (!friendly(v.state)) return false
        if (v.state.instField >= 2 && WsInstrumentView.sound(v.session.song, v.state) != null) WsInstrumentView.audition(v)
        return true
    }
    fun release(v: ViewController, tap: Boolean): Boolean {
        val panel = v.state.soundPanel("ws")
        if (panel.editBlocked) { panel.editBlocked = false; if (!v.auditionHeld) v.session.releaseAudition(); return true }
        if (v.state.instField == -1) { if (tap) selectMode(v, !panel.advanced, true); return true }
        if (!friendly(v.state)) return false
        if (!v.auditionHeld) v.session.releaseAudition()
        if (tap && v.state.instField in 2..5 && WsInstrumentView.sound(v.session.song, v.state) == null)
            v.session.setWsInstrument(v.state.instrument, defaultWsInstrument(v.state.instrument), "New sound")
        return true
    }
    fun clear(v: ViewController): Boolean {
        if (v.state.instField == -1 || v.state.soundPanel("ws").editBlocked) return true
        if (!friendly(v.state)) return false
        if (v.state.instField == 1) v.session.editWsInstrument(v.state.instrument, "Rename sound") {
            it.copy(name = ViewController.deleteChar(it.name, v.state.textPos))
        }
        return true
    }
    fun heard(v: ViewController) { if (v.auditionHeld || (v.editHeld && !v.state.soundPanel("ws").editBlocked)) WsInstrumentView.audition(v) }
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
