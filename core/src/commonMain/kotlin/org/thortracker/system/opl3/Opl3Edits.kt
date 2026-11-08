package org.thortracker.system.opl3

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Song

/** OPL3 document edits for the instrument editor: the one bank of [Opl3System.SLOTS] sounds and the chord shapes. */

/** Instrument [slot] (1..[Opl3System.SLOTS]), or null when empty or out of range. */
fun Song.opl3Instrument(slot: Int): Opl3Instrument? = opl3.instrument(slot)

/**
 * The sound an empty slot gets when it is first edited: the Lead preset (2-op, so chords work), named after the slot,
 * so a new sound is musical at once.
 */
fun defaultOpl3Instrument(slot: Int): Opl3Instrument = (Opl3Presets.named("Lead")?.instrument ?: Opl3Presets.ALL.first().instrument)
    .copy(name = "Sound ${hex2(slot)}")

/** A copied OPL3 sound, or the chord-shape table. */
sealed interface Opl3Clip : Clip {
    data class Sound(val instrument: Opl3Instrument) : Opl3Clip
    data class Chords(val chords: List<Opl3Chord>) : Opl3Clip
}

/** Replaces instrument [slot] ([instrument] null empties it). */
fun EditorSession.setOpl3Instrument(slot: Int, instrument: Opl3Instrument?, label: String): Boolean {
    if (slot !in 1..Opl3System.SLOTS) return false
    return apply(label) { s -> if (s.opl3.instrument(slot) == instrument) s else s.copy(sound = s.opl3.withInstrument(slot, instrument)) }
}

/** Edits instrument [slot]; an empty slot starts from [defaultOpl3Instrument]. */
fun EditorSession.editOpl3Instrument(slot: Int, label: String, f: (Opl3Instrument) -> Opl3Instrument): Boolean {
    if (slot !in 1..Opl3System.SLOTS) return false
    val cur = song.opl3Instrument(slot)
    val next = f(cur ?: defaultOpl3Instrument(slot))
    return (cur == null || next != cur) && setOpl3Instrument(slot, next, label)
}

/** Edits the chord-shape table (at most [Opl3System.MAX_CHORDS] shapes). */
fun EditorSession.editOpl3Chords(label: String, f: (List<Opl3Chord>) -> List<Opl3Chord>): Boolean = apply(label) { s ->
    val chords = f(s.opl3.chords).take(Opl3System.MAX_CHORDS)
    if (chords == s.opl3.chords) s else s.copy(sound = s.opl3.copy(chords = chords))
}

fun EditorSession.copyOpl3Instrument(slot: Int) {
    val i = song.opl3Instrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(Opl3Clip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyOpl3Chords() {
    hold(Opl3Clip.Chords(song.opl3.chords))
    note("Copied the ${song.opl3.chords.size} chord shapes")
}

/** Pastes a copied sound into [slot], or a copied chord-shape table over the song's. */
fun EditorSession.pasteOpl3(slot: Int): Boolean = when (val clip = clipboard) {
    is Opl3Clip.Sound -> setOpl3Instrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is Opl3Clip.Chords -> editOpl3Chords("Paste chord shapes") { clip.chords }.also { note("Pasted ${clip.chords.size} chord shapes") }
    else -> { note("Clipboard holds no OPL3 sound or chord shapes"); false }
}
