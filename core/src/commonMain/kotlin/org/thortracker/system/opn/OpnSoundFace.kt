package org.thortracker.system.opn

import org.thortracker.editor.ViewController
import org.thortracker.system.panel.SoundFaceBinding

internal object OpnSoundFace : SoundFaceBinding<OpnInstrument>("opn", OpnInstrumentView.NAME_MAX) {
    override fun current(v: ViewController) = OpnInstrumentView.sound(v.session.song, v.state)
    override fun empty(v: ViewController) = OpnEdits.newSound(v.session.song.opn.chip, OpnInstrumentView.part(v.session.song, v.state), v.state.instrument)
    override fun name(patch: OpnInstrument) = patch.name
    override fun named(patch: OpnInstrument, name: String) = patch.named(name)
    override fun store(v: ViewController, patch: OpnInstrument, label: String) = OpnEdits.set(v.session, v.state.instrument, patch, label)
    override fun move(v: ViewController, delta: Int) = OpnInstrumentView.moveSlot(v, delta)
    override fun publish(v: ViewController) = OpnInstrumentView.publish(v)
    override fun audition(v: ViewController) = OpnInstrumentView.audition(v)
    override fun count(patch: OpnInstrument) = OpnSoundMacros.labels(patch).size
    override fun controlCount(v: ViewController) = if (OpnInstrumentView.part(v.session.song, v.state) == OpnPart.RHYTHM) 2 else 5
    override fun label(patch: OpnInstrument, control: Int) = OpnSoundMacros.labels(patch)[control]
    override fun maximum(patch: OpnInstrument, control: Int) = OpnSoundMacros.maximum(patch, control)
    override fun value(patch: OpnInstrument, control: Int) = OpnSoundMacros.value(patch, control)
    override fun text(patch: OpnInstrument, control: Int) = OpnSoundMacros.text(patch, control)
    override fun hint(patch: OpnInstrument, control: Int) = OpnSoundMacros.hint(patch, control)
    override fun enabled(patch: OpnInstrument, control: Int) = OpnSoundMacros.enabled(patch, control)
    override fun set(patch: OpnInstrument, control: Int, value: Int) = OpnSoundMacros.set(patch, control, value)
}
