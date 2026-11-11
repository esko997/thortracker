package org.thortracker.system.opl3

import org.thortracker.editor.ViewController
import org.thortracker.system.panel.SoundFaceBinding

internal object Opl3SoundFace : SoundFaceBinding<Opl3Instrument>("opl3", Opl3InstrumentView.NAME_MAX) {
    override fun current(v: ViewController) = Opl3InstrumentView.sound(v.session.song, v.state)
    override fun empty(v: ViewController) = defaultOpl3Instrument(v.state.instrument)
    override fun name(patch: Opl3Instrument) = patch.name
    override fun named(patch: Opl3Instrument, name: String) = patch.copy(name = name)
    override fun store(v: ViewController, patch: Opl3Instrument, label: String) = v.session.setOpl3Instrument(v.state.instrument, patch, label)
    override fun move(v: ViewController, delta: Int) = Opl3InstrumentView.moveSlot(v, delta)
    override fun publish(v: ViewController) = Opl3InstrumentView.publish(v)
    override fun audition(v: ViewController) = Opl3InstrumentView.audition(v)
    override fun count(patch: Opl3Instrument) = Opl3SoundMacros.labels.size
    override fun controlCount(v: ViewController) = Opl3SoundMacros.labels.size
    override fun label(patch: Opl3Instrument, control: Int) = Opl3SoundMacros.labels[control]
    override fun maximum(patch: Opl3Instrument, control: Int) = Opl3SoundMacros.maxima[control]
    override fun value(patch: Opl3Instrument, control: Int) = Opl3SoundMacros.value(patch, control)
    override fun text(patch: Opl3Instrument, control: Int) = if (enabled(patch, control)) Opl3SoundMacros.text(control, value(patch, control)) else "No modulators"
    override fun hint(patch: Opl3Instrument, control: Int) = Opl3SoundMacros.hints[control]
    override fun enabled(patch: Opl3Instrument, control: Int) = Opl3SoundMacros.enabled(patch, control)
    override fun set(patch: Opl3Instrument, control: Int, value: Int) = Opl3SoundMacros.set(patch, control, value)
}
