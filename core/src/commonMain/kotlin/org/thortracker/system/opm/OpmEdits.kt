package org.thortracker.system.opm

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Song

/** OPM document edits for the instrument editor: the one bank of [OpmSystem.SLOTS] sounds. */

/** Instrument [slot] (1..[OpmSystem.SLOTS]), or null when empty or out of range. */
fun Song.opmInstrument(slot: Int): OpmInstrument? = opm.instrument(slot)

/**
 * The sound an empty slot gets when it is first edited: the Lead preset (no noise, so it plays on every channel), named
 * after the slot, so a new sound is musical at once.
 */
fun defaultOpmInstrument(slot: Int): OpmInstrument =
    (OpmPresets.named("Lead")?.instrument ?: OpmPresets.ALL.first { it.category == OpmPresets.Category.FM }.instrument)
        .copy(name = "Sound ${hex2(slot)}")

/** A copied OPM sound. */
sealed interface OpmClip : Clip {
    data class Sound(val instrument: OpmInstrument) : OpmClip
}

/** Replaces instrument [slot] ([instrument] null empties it). */
fun EditorSession.setOpmInstrument(slot: Int, instrument: OpmInstrument?, label: String): Boolean {
    if (slot !in 1..OpmSystem.SLOTS) return false
    return apply(label) { s -> if (s.opm.instrument(slot) == instrument) s else s.copy(sound = s.opm.withInstrument(slot, instrument)) }
}

/** Edits instrument [slot]; an empty slot starts from [defaultOpmInstrument]. */
fun EditorSession.editOpmInstrument(slot: Int, label: String, f: (OpmInstrument) -> OpmInstrument): Boolean {
    if (slot !in 1..OpmSystem.SLOTS) return false
    val cur = song.opmInstrument(slot)
    val next = f(cur ?: defaultOpmInstrument(slot))
    return (cur == null || next != cur) && setOpmInstrument(slot, next, label)
}

fun EditorSession.copyOpmInstrument(slot: Int) {
    val i = song.opmInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(OpmClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

/** Pastes a copied sound into [slot]. */
fun EditorSession.pasteOpm(slot: Int): Boolean = when (val clip = clipboard) {
    is OpmClip.Sound -> setOpmInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    else -> { note("Clipboard holds no OPM sound"); false }
}
