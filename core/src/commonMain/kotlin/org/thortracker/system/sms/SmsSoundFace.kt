package org.thortracker.system.sms

import org.thortracker.editor.Action
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.system.panel.MusicalPanelMode

internal object SmsSoundFace {
    fun friendly(st: ViewState) = !st.soundPanel("sms").advanced
    fun controls(song: Song, st: ViewState) = SmsSoundMacros.controls(SmsInstrumentView.kind(song, st))
    fun settle(song: Song, st: ViewState) {
        if (!friendly(st)) return
        if (st.instField == 0) st.instField = 2
        st.instField = st.instField.coerceIn(-1, controls(song, st).size + 1)
    }
    fun selectMode(v: ViewController, advanced: Boolean, keep: Boolean = false) {
        MusicalPanelMode.select(v, v.state.soundPanel("sms"), advanced, keep)
        settle(v.session.song, v.state)
    }
    fun moveSlot(v: ViewController, delta: Int) {
        val field = v.state.instField
        if (v.editHeld) { v.useEdit(); v.state.soundPanel("sms").editBlocked = true }
        SmsInstrumentView.moveSlot(v, delta)
        v.state.instField = field
        settle(v.session.song, v.state)
    }
    fun listen(v: ViewController, delta: Int) {
        val st = v.state
        val layout = v.session.song.sms.layout
        val ins = SmsInstrumentView.sound(v.session.song, st)
        st.soundPanel("sms").invalidate()
        if (v.editHeld) { v.useEdit(); st.soundPanel("sms").editBlocked = true }
        var channel = st.listenChannel
        repeat(layout.channelCount) {
            channel = (channel + delta).mod(layout.channelCount)
            if (ins == null || layout.instrumentKind(channel) == ins.kind) {
                st.listenChannel = channel
                settle(v.session.song, st)
                if (v.auditionHeld) SmsInstrumentView.audition(v)
                return
            }
        }
    }
    fun direction(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val panel = st.soundPanel("sms")
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
                    val ins = SmsInstrumentView.sound(v.session.song, st) ?: defaultSmsInstrument(st.instrument, SmsInstrumentView.kind(v.session.song, st))
                    v.session.setSmsInstrument(st.instrument, ins.named(NameEdit.edit(ins.name, st.textPos, dx, SmsInstrumentView.NAME_MAX)), "Rename sound")
                } else st.textPos = (st.textPos - dy).coerceIn(0, SmsInstrumentView.NAME_MAX - 1)
                else -> controls(v.session.song, st).getOrNull(st.instField - 2)?.let { c ->
                    val actual = SmsInstrumentView.sound(v.session.song, st)
                    val ins = actual ?: defaultSmsInstrument(st.instrument, SmsInstrumentView.kind(v.session.song, st))
                    val next = SmsSoundMacros.step(c, ins, dx + dy * if (c.maximum > 15) 4 else 1)
                    if (next !== ins && v.session.setSmsInstrument(st.instrument, next, "Set ${c.label}")) heard(v)
                }
            }
        } else if (dy != 0) moveSlot(v, -dy)
        else MusicalPanelMode.cycleSoundField(v, panel, controls(v.session.song, st).size, dx)
        return true
    }
    fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && !v.state.soundPanel("sms").editBlocked)) SmsInstrumentView.audition(v)
    }
    fun command(v: ViewController, action: Action): Boolean {
        val panel = v.state.soundPanel("sms")
        if (panel.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (action == Action.PASTE || action == Action.CUT || action == Action.DUPLICATE) {
            panel.invalidate()
            if (v.editHeld) { v.useEdit(); panel.editBlocked = true }
        }
        if (!friendly(v.state)) return false
        when (action) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> listen(v, if (action == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> v.session.copySmsInstrument(v.state.instrument)
            Action.PASTE -> { v.session.pasteSms(v.state.instrument, null); settle(v.session.song, v.state) }
            Action.CUT -> { v.session.copySmsInstrument(v.state.instrument); v.session.setSmsInstrument(v.state.instrument, null, "Cut sound"); settle(v.session.song, v.state) }
            else -> return false
        }
        return true
    }
    fun hints(v: ViewController, h: MutableList<Hint>) {
        h += Hint(v.ud(), "Sound slot"); h += Hint(v.lr(), "Control / Name / face")
        h += Hint(v.editLr(), if (v.state.instField == 1) "Change letter" else "Shape sound")
        if (v.state.instField == 1) h += Hint(v.editUd(), "Move letter cursor")
        h += Hint(v.channels(), "Listen channel"); h += Hint(v.k(Action.AUDITION), "Listen")
        h += Hint(v.octaves(), "Listen pitch")
    }
}
