package org.thortracker.system.ym2612

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind

/** Mega Drive document edits for the instrument editor: the one bank of [YmSystem.SLOTS] FM and PSG sounds. */

/** Instrument [slot] (1..[YmSystem.SLOTS]), or null when empty or out of range. */
fun Song.ymInstrument(slot: Int): YmInstrument? = ym.instrument(slot)

/** The FM voice an empty slot gets when it is first edited as FM: the Lead preset, named after the slot. */
fun defaultYmFm(slot: Int): YmFmInstrument =
    ((YmPresets.named("Lead")?.instrument ?: YmPresets.ALL.first { it.instrument is YmFmInstrument }.instrument) as YmFmInstrument)
        .copy(name = "Sound ${hex2(slot)}")

/** The PSG sound an empty slot gets when it is first edited as PSG: the PSG lead preset, named after the slot. */
fun defaultYmPsg(slot: Int): YmPsgInstrument =
    ((YmPresets.named("PSG lead")?.instrument ?: YmPresets.ALL.first { it.instrument is YmPsgInstrument }.instrument) as YmPsgInstrument)
        .copy(name = "Sound ${hex2(slot)}")

/** A new sound for [slot] of the kind [channel] plays: FM on FM 1-6, PSG on PSG 1-3 and Noise. */
fun defaultYmInstrument(slot: Int, channel: Int): YmInstrument = if (YmRules.isFm(channel)) defaultYmFm(slot) else defaultYmPsg(slot)

/** The envelope [kind] (volume, arpeggio or pitch) of a PSG sound. */
fun YmPsgInstrument.envelope(kind: NesEnvelopeKind): NesEnvelope = when (kind) {
    NesEnvelopeKind.ARPEGGIO -> arpeggio
    NesEnvelopeKind.PITCH -> pitch
    else -> volume
}

/** This PSG sound with envelope [kind] replaced. */
fun YmPsgInstrument.withEnvelope(kind: NesEnvelopeKind, e: NesEnvelope): YmPsgInstrument = when (kind) {
    NesEnvelopeKind.ARPEGGIO -> copy(arpeggio = e)
    NesEnvelopeKind.PITCH -> copy(pitch = e)
    else -> copy(volume = e)
}

/** A copied Mega Drive sound, or one PSG envelope. */
sealed interface YmClip : Clip {
    data class Sound(val instrument: YmInstrument) : YmClip
    data class Envelope(val kind: NesEnvelopeKind, val envelope: NesEnvelope, val relative: Boolean) : YmClip
}

/** Replaces instrument [slot] ([instrument] null empties it). */
fun EditorSession.setYmInstrument(slot: Int, instrument: YmInstrument?, label: String): Boolean {
    if (slot !in 1..YmSystem.SLOTS) return false
    return apply(label) { s -> if (s.ym.instrument(slot) == instrument) s else s.copy(sound = s.ym.withInstrument(slot, instrument)) }
}

/** Edits FM voice [slot]; an empty slot starts from [defaultYmFm]. False (nothing changes) when the slot holds a PSG sound. */
fun EditorSession.editYmFm(slot: Int, label: String, f: (YmFmInstrument) -> YmFmInstrument): Boolean {
    if (slot !in 1..YmSystem.SLOTS) return false
    val cur = song.ymInstrument(slot)
    if (cur is YmPsgInstrument) return false
    val next = f((cur as YmFmInstrument?) ?: defaultYmFm(slot))
    return (cur == null || next != cur) && setYmInstrument(slot, next, label)
}

/** Edits PSG sound [slot]; an empty slot starts from [defaultYmPsg]. False when the slot holds an FM voice. */
fun EditorSession.editYmPsg(slot: Int, label: String, f: (YmPsgInstrument) -> YmPsgInstrument): Boolean {
    if (slot !in 1..YmSystem.SLOTS) return false
    val cur = song.ymInstrument(slot)
    if (cur is YmFmInstrument) return false
    val next = f((cur as YmPsgInstrument?) ?: defaultYmPsg(slot))
    return (cur == null || next != cur) && setYmInstrument(slot, next, label)
}

/** Edits envelope [kind] of PSG sound [slot]; the result is made legal ([NesEnvelopeKind.fixed]). */
fun EditorSession.editYmEnvelope(slot: Int, kind: NesEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean =
    editYmPsg(slot, label) { it.withEnvelope(kind, kind.fixed(f(it.envelope(kind)))) }

/** Renames [slot]'s sound (either kind). */
fun EditorSession.renameYm(slot: Int, f: (String) -> String): Boolean {
    val cur = song.ymInstrument(slot) ?: return false
    return setYmInstrument(slot, cur.named(f(cur.name)), "Rename sound")
}

fun EditorSession.copyYmInstrument(slot: Int) {
    val i = song.ymInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(YmClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyYmEnvelope(slot: Int, kind: NesEnvelopeKind) {
    val i = song.ymInstrument(slot) as? YmPsgInstrument ?: return
    hold(YmClip.Envelope(kind, i.envelope(kind), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it; an envelope goes into PSG envelope [kind] (values clamped to
 * its range, the release kept only on volume), so a shape can move between envelopes; null [kind] (an FM page) takes
 * sounds only.
 */
fun EditorSession.pasteYm(slot: Int, kind: NesEnvelopeKind?): Boolean = when (val clip = clipboard) {
    is YmClip.Sound -> setYmInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is YmClip.Envelope -> if (kind == null || song.ymInstrument(slot) !is YmPsgInstrument) {
        note("An envelope pastes into a PSG sound's envelope page"); false
    } else editYmPsg(slot, "Paste ${kind.label.lowercase()} envelope") {
        val with = it.withEnvelope(kind, kind.fixed(clip.envelope))
        if (kind == NesEnvelopeKind.PITCH && clip.kind == NesEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
    }.also { note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    else -> { note("Clipboard holds no Mega Drive sound or envelope"); false }
}
