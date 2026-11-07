package org.thortracker.system.nes

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song

/** NES document edits: the one instrument bank and its envelopes, as undoable [EditorSession] commands. */

/**
 * The four envelopes of a [NesInstrument], with the values each holds ([NesEnvelope] ranges) and the value a new step
 * starts from. Only volume has a release point (FamiStudio's rule).
 */
enum class NesEnvelopeKind(val label: String, val range: IntRange, val fallback: Int) {
    VOLUME("Volume", NesEnvelope.VOLUME, 15),
    ARPEGGIO("Arpeggio", NesEnvelope.ARPEGGIO, 0),
    PITCH("Pitch", NesEnvelope.PITCH, 0),
    DUTY("Duty", NesEnvelope.DUTY, 0);

    val canRelease: Boolean get() = this == VOLUME

    fun of(i: NesInstrument): NesEnvelope = when (this) {
        VOLUME -> i.volume
        ARPEGGIO -> i.arpeggio
        PITCH -> i.pitch
        DUTY -> i.duty
    }

    fun set(i: NesInstrument, e: NesEnvelope): NesInstrument = when (this) {
        VOLUME -> i.copy(volume = e)
        ARPEGGIO -> i.copy(arpeggio = e)
        PITCH -> i.copy(pitch = e)
        DUTY -> i.copy(duty = e)
    }

    /** [e] made legal for this envelope: values clamped, release only on volume, loop before release. */
    fun fixed(e: NesEnvelope): NesEnvelope {
        val values = if (e.values.all { it in range }) e.values else e.values.map { it.coerceIn(range) }
        val last = values.size - 1
        val release = if (canRelease && e.release in 0..last) e.release else -1
        var loop = if (e.loop in 0..last) e.loop else -1
        if (release >= 0 && loop >= release) loop = release - 1
        val out = NesEnvelope(values, loop, release)
        return if (out == e) e else out
    }
}

/** Envelope step edits, kept legal by [NesEnvelopeKind.fixed]. A step index of `size` appends. */
object NesEnvelopes {
    /** [e] with step [step] set to [value]; [step] == size appends (up to [NesEnvelope.MAX_LENGTH]). */
    fun withValue(kind: NesEnvelopeKind, e: NesEnvelope, step: Int, value: Int): NesEnvelope {
        val v = value.coerceIn(kind.range)
        val values = when {
            step in e.values.indices -> e.values.replaced(step, v)
            step == e.values.size && step < NesEnvelope.MAX_LENGTH -> e.values + v
            else -> return e
        }
        return kind.fixed(e.copy(values = values))
    }

    /** The value step [step] holds, or what an appended step starts from (the last value, else the default). */
    fun valueAt(kind: NesEnvelopeKind, e: NesEnvelope, step: Int): Int =
        e.values.getOrNull(step) ?: e.values.lastOrNull() ?: kind.fallback

    /** [e] without step [step]; the loop and release points keep marking the same steps where they can. */
    fun removed(kind: NesEnvelopeKind, e: NesEnvelope, step: Int): NesEnvelope {
        if (step !in e.values.indices) return e
        fun shift(point: Int) = if (point > step) point - 1 else point
        val values = e.values.toMutableList().also { it.removeAt(step) }
        return kind.fixed(NesEnvelope(values, shift(e.loop).coerceAtMost(values.size - 1), shift(e.release).coerceAtMost(values.size - 1)))
    }

    /** [e] cut or extended (repeating its last value) to [length] steps; a loop past the end moves to the last step. */
    fun withLength(kind: NesEnvelopeKind, e: NesEnvelope, length: Int): NesEnvelope {
        val n = length.coerceIn(0, NesEnvelope.MAX_LENGTH)
        if (n == e.values.size) return e
        val values = if (n < e.values.size) e.values.take(n) else e.values + List(n - e.values.size) { valueAt(kind, e, e.values.size) }
        return kind.fixed(NesEnvelope(values, e.loop.coerceAtMost(n - 1), e.release))
    }

    /** [e] looping from [loop] (-1: holds its last value); kept before the release point. */
    fun withLoop(kind: NesEnvelopeKind, e: NesEnvelope, loop: Int): NesEnvelope {
        val top = if (e.release >= 0) e.release - 1 else e.values.size - 1
        return kind.fixed(e.copy(loop = loop.coerceIn(-1, top)))
    }

    /** [e] releasing at [release] (-1: none); only after the loop point. Moving below the first legal step removes it. */
    fun withRelease(kind: NesEnvelopeKind, e: NesEnvelope, release: Int): NesEnvelope {
        if (!kind.canRelease) return e
        val first = e.loop + 1
        val r = when {
            release < first -> if (e.release < 0 && release >= 0) first else -1
            else -> release.coerceAtMost(e.values.size - 1)
        }
        return kind.fixed(e.copy(release = if (r in first until e.values.size) r else -1))
    }
}

/** Instrument [slot] (1..[NesSystem.SLOTS]), or null. */
fun Song.nesInstrument(slot: Int): NesInstrument? = nes.instruments.getOrNull(slot - 1)

/** A new instrument for [slot], as a blank song has it. */
fun defaultNesInstrument(slot: Int) = NesInstrument(name = "Sound ${hex2(slot)}")

/** A copied NES instrument, or one of its envelopes. */
sealed interface NesClip : Clip {
    data class Sound(val instrument: NesInstrument) : NesClip
    data class Envelope(val kind: NesEnvelopeKind, val envelope: NesEnvelope, val relative: Boolean) : NesClip
}

fun EditorSession.setNesInstrument(slot: Int, instrument: NesInstrument, label: String = "Edit ${instrument.name.ifBlank { "sound" }}"): Boolean {
    if (slot !in 1..song.nes.instruments.size) return false
    return apply(label) { s -> s.copy(sound = s.nes.copy(instruments = s.nes.instruments.replaced(slot - 1, instrument))) }
}

fun EditorSession.editNesInstrument(slot: Int, label: String, f: (NesInstrument) -> NesInstrument): Boolean {
    val cur = song.nesInstrument(slot) ?: return false
    return setNesInstrument(slot, f(cur), label)
}

fun EditorSession.editNesEnvelope(slot: Int, kind: NesEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean =
    editNesInstrument(slot, label) { kind.set(it, f(kind.of(it))) }

fun EditorSession.copyNesInstrument(slot: Int) {
    val i = song.nesInstrument(slot) ?: return
    hold(NesClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyNesEnvelope(slot: Int, kind: NesEnvelopeKind) {
    val i = song.nesInstrument(slot) ?: return
    hold(NesClip.Envelope(kind, kind.of(i), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it; an envelope goes into [kind] (values clamped to its range,
 * the release kept only on volume), so a shape can move between envelopes.
 */
fun EditorSession.pasteNes(slot: Int, kind: NesEnvelopeKind): Boolean {
    when (val clip = clipboard) {
        is NesClip.Sound -> {
            val ok = setNesInstrument(slot, clip.instrument, "Paste sound")
            note("Pasted into sound ${hex2(slot)}")
            return ok
        }
        is NesClip.Envelope -> {
            val ok = editNesInstrument(slot, "Paste ${kind.label.lowercase()} envelope") {
                val with = kind.set(it, kind.fixed(clip.envelope))
                if (kind == NesEnvelopeKind.PITCH && clip.kind == NesEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
            }
            note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}")
            return ok
        }
        else -> { note("Clipboard holds no NES sound or envelope"); return false }
    }
}
