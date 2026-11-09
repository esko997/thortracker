package org.thortracker.system.sms

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind

/** Master System document edits for the instrument editor: the one bank of [SmsSystem.SLOTS] PSG sounds, FM voices and drum kits. */

/** Instrument [slot] (1..[SmsSystem.SLOTS]), or null when empty or out of range. */
fun Song.smsInstrument(slot: Int): SmsInstrument? = sms.instrument(slot)

/** The sound an empty slot gets when it is first edited as [kind]: the kind's first preset, named after the slot. */
fun defaultSmsInstrument(slot: Int, kind: SmsInstrumentKind): SmsInstrument {
    val base = when (kind) {
        SmsInstrumentKind.PSG -> SmsPresets.instrument("Square lead")
        SmsInstrumentKind.FM -> SmsPresets.instrument("Piano")
        SmsInstrumentKind.KIT -> SmsPresets.instrument("Pitched kit")
    }
    return base.named("Sound ${hex2(slot)}")
}

/**
 * The envelopes an instrument page edits: PSG sounds have volume, arpeggio, pitch and noise (the noise mode as
 * [NesEnvelopeKind.DUTY]'s 0..3); FM voices volume and arpeggio.
 */
fun SmsInstrument.envelope(kind: NesEnvelopeKind): NesEnvelope? = when (this) {
    is SmsPsgInstrument -> when (kind) {
        NesEnvelopeKind.VOLUME -> volume
        NesEnvelopeKind.ARPEGGIO -> arpeggio
        NesEnvelopeKind.PITCH -> pitch
        NesEnvelopeKind.DUTY -> noise
        NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> null
    }
    is SmsFmInstrument -> when (kind) {
        NesEnvelopeKind.VOLUME -> volume
        NesEnvelopeKind.ARPEGGIO -> arpeggio
        else -> null
    }
    is SmsDrumKit -> null
}

/** This instrument with envelope [kind] replaced (unchanged where it has none). */
fun SmsInstrument.withEnvelope(kind: NesEnvelopeKind, e: NesEnvelope): SmsInstrument = when (this) {
    is SmsPsgInstrument -> when (kind) {
        NesEnvelopeKind.VOLUME -> copy(volume = e)
        NesEnvelopeKind.ARPEGGIO -> copy(arpeggio = e)
        NesEnvelopeKind.PITCH -> copy(pitch = e)
        NesEnvelopeKind.DUTY -> copy(noise = e)
        NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> this
    }
    is SmsFmInstrument -> when (kind) {
        NesEnvelopeKind.VOLUME -> copy(volume = e)
        NesEnvelopeKind.ARPEGGIO -> copy(arpeggio = e)
        else -> this
    }
    is SmsDrumKit -> this
}

/** A copied Master System sound, or one envelope. */
sealed interface SmsClip : Clip {
    data class Sound(val instrument: SmsInstrument) : SmsClip
    data class Envelope(val kind: NesEnvelopeKind, val envelope: NesEnvelope) : SmsClip
}

/** Replaces instrument [slot] ([instrument] null empties it). */
fun EditorSession.setSmsInstrument(slot: Int, instrument: SmsInstrument?, label: String): Boolean {
    if (slot !in 1..SmsSystem.SLOTS) return false
    return apply(label) { s -> if (s.sms.instrument(slot) == instrument) s else s.copy(sound = s.sms.withInstrument(slot, instrument)) }
}

/** Edits [slot]'s sound of [kind]; an empty slot starts from [defaultSmsInstrument]. False when the slot holds another kind. */
inline fun <reified T : SmsInstrument> EditorSession.editSms(slot: Int, kind: SmsInstrumentKind, label: String, f: (T) -> SmsInstrument): Boolean {
    if (slot !in 1..SmsSystem.SLOTS) return false
    val cur = song.smsInstrument(slot)
    if (cur != null && cur !is T) return false
    val start = (cur ?: defaultSmsInstrument(slot, kind)) as? T ?: return false
    val next = f(start)
    return (cur == null || next != cur) && setSmsInstrument(slot, next, label)
}

/**
 * Edits envelope [kind] of [slot] (a PSG sound or FM voice); the result is made legal ([NesEnvelopeKind.fixed]). A PSG
 * sound has one extra envelope: editing its pitch while its noise envelope has steps (or the other way round) is
 * refused with a note.
 */
fun EditorSession.editSmsEnvelope(slot: Int, kind: NesEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean {
    val cur = song.smsInstrument(slot) ?: return false
    val e = cur.envelope(kind) ?: return false
    if (cur is SmsPsgInstrument) {
        val other = when (kind) { NesEnvelopeKind.PITCH -> cur.noise; NesEnvelopeKind.DUTY -> cur.pitch; else -> null }
        if (other != null && other.values.isNotEmpty()) {
            note("A Banjo instrument has one extra envelope: empty the ${if (kind == NesEnvelopeKind.PITCH) "noise" else "pitch"} envelope first")
            return false
        }
    }
    val next = kind.fixed(f(e))
    return next != e && setSmsInstrument(slot, cur.withEnvelope(kind, next), label)
}

fun EditorSession.renameSms(slot: Int, f: (String) -> String): Boolean {
    val cur = song.smsInstrument(slot) ?: return false
    return setSmsInstrument(slot, cur.named(f(cur.name)), "Rename sound")
}

fun EditorSession.copySmsInstrument(slot: Int) {
    val i = song.smsInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(SmsClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copySmsEnvelope(slot: Int, kind: NesEnvelopeKind) {
    val e = song.smsInstrument(slot)?.envelope(kind) ?: return
    hold(SmsClip.Envelope(kind, e))
    note("Copied an envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it; an envelope goes into envelope [kind] of the slot's sound
 * (values clamped to its range, the release kept only on volume); null [kind] (a voice or kit page) takes sounds only.
 */
fun EditorSession.pasteSms(slot: Int, kind: NesEnvelopeKind?): Boolean = when (val clip = clipboard) {
    is SmsClip.Sound -> setSmsInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is SmsClip.Envelope -> if (kind == null || song.smsInstrument(slot)?.envelope(kind) == null) {
        note("An envelope pastes into an envelope page of a PSG sound or FM voice"); false
    } else editSmsEnvelope(slot, kind, "Paste envelope") { clip.envelope }.also { note("Pasted the envelope") }
    else -> { note("Clipboard holds no Master System sound or envelope"); false }
}
