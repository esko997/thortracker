package org.thortracker.system.opm

import org.thortracker.editor.ViewController
import org.thortracker.system.panel.SoundFaceBinding

internal object OpmSoundFace : SoundFaceBinding<OpmInstrument>("opm", OpmInstrumentView.NAME_MAX) {
    override fun current(v: ViewController) = OpmInstrumentView.sound(v.session.song, v.state)
    override fun empty(v: ViewController) = defaultOpmInstrument(v.state.instrument)
    override fun name(patch: OpmInstrument) = patch.name
    override fun named(patch: OpmInstrument, name: String) = patch.copy(name = name)
    override fun store(v: ViewController, patch: OpmInstrument, label: String) = v.session.setOpmInstrument(v.state.instrument, patch, label)
    override fun move(v: ViewController, delta: Int) = OpmInstrumentView.moveSlot(v, delta)
    override fun publish(v: ViewController) = OpmInstrumentView.publish(v)
    override fun audition(v: ViewController) = OpmInstrumentView.audition(v)
    override fun count(patch: OpmInstrument) = OpmSoundMacros.labels.size
    override fun controlCount(v: ViewController) = OpmSoundMacros.labels.size
    override fun label(patch: OpmInstrument, control: Int) = OpmSoundMacros.labels[control]
    override fun maximum(patch: OpmInstrument, control: Int) = OpmSoundMacros.maxima[control]
    override fun value(patch: OpmInstrument, control: Int) = OpmSoundMacros.value(patch, control)
    override fun text(patch: OpmInstrument, control: Int) = if (enabled(patch, control)) OpmSoundMacros.text(control, value(patch, control)) else "No modulators"
    override fun hint(patch: OpmInstrument, control: Int) = OpmSoundMacros.hints[control]
    override fun enabled(patch: OpmInstrument, control: Int) = OpmSoundMacros.enabled(patch, control)
    override fun set(patch: OpmInstrument, control: Int, value: Int) = OpmSoundMacros.set(patch, control, value)
}
