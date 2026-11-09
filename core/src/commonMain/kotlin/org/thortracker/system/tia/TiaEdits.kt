package org.thortracker.system.tia

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song
import org.thortracker.system.fm.FmControls

/**
 * Atari 2600 document edits for the instrument editor: the one bank of [TiaSystem.SLOTS] sounds and their envelopes,
 * as undoable [EditorSession] commands. Every slot is its own record: copying or pasting never links two slots.
 */

/** Instrument [slot] (1..[TiaSystem.SLOTS]), or null when empty or out of range. */
fun Song.tiaInstrument(slot: Int): TiaInstrument? = tia.instrument(slot)

/** The sound an empty slot gets when it is first edited: a flat full-volume div 6 square named after the slot. */
fun defaultTiaInstrument(slot: Int): TiaInstrument = TiaInstrument(name = "Sound ${hex2(slot)}")

/**
 * The four envelopes of a [TiaInstrument], with the value a new step starts from when the envelope is empty. Every
 * envelope may have a loop and a release point; OFF releases (instead of stopping) only when the volume envelope has
 * one, and then every envelope with a release point jumps to it. [WAVEFORM] is the AUDC per tick.
 */
enum class TiaEnvelopeKind(val label: String, val range: IntRange, val fallback: Int) {
    VOLUME("Volume", TiaEnvelope.VOLUME, 15),
    ARPEGGIO("Arpeggio", TiaEnvelope.ARPEGGIO, 0),
    PITCH("Pitch", TiaEnvelope.PITCH, 0),
    WAVEFORM("Waveform", TiaEnvelope.WAVEFORM, TiaInstrument.DEFAULT_WAVEFORM);

    fun of(i: TiaInstrument): TiaEnvelope = when (this) {
        VOLUME -> i.volume
        ARPEGGIO -> i.arpeggio
        PITCH -> i.pitch
        WAVEFORM -> i.waveforms
    }

    fun set(i: TiaInstrument, e: TiaEnvelope): TiaInstrument = when (this) {
        VOLUME -> i.copy(volume = e)
        ARPEGGIO -> i.copy(arpeggio = e)
        PITCH -> i.copy(pitch = e)
        WAVEFORM -> i.copy(waveforms = e)
    }

    /**
     * [e] made legal ([TiaEnvelope.problem]): values clamped into [range], at most [TiaEnvelope.MAX_LENGTH] steps, the
     * loop a step index, the release 1..length − 1 and after the loop (a loop at or past the release moves before it).
     */
    fun fixed(e: TiaEnvelope): TiaEnvelope {
        val kept = if (e.values.size > TiaEnvelope.MAX_LENGTH) e.values.take(TiaEnvelope.MAX_LENGTH) else e.values
        val values = if (kept.all { it in range }) kept else kept.map { it.coerceIn(range) }
        val last = values.size - 1
        val release = if (e.release in 1..last) e.release else -1
        var loop = if (e.loop in 0..last) e.loop else -1
        if (release >= 0 && loop >= release) loop = release - 1
        val out = TiaEnvelope(values, loop, release)
        return if (out == e) e else out
    }
}

/** Envelope step edits, kept legal by [TiaEnvelopeKind.fixed]. A step index of `size` appends. */
object TiaEnvelopes {
    /** The furthest step a cursor reaches: the append position after the last step, while there is room. */
    fun lastStep(e: TiaEnvelope): Int = minOf(e.values.size, TiaEnvelope.MAX_LENGTH - 1)

    /** [e] with step [step] set to [value] (clamped); [step] == size appends while there is room. */
    fun withValue(kind: TiaEnvelopeKind, e: TiaEnvelope, step: Int, value: Int): TiaEnvelope {
        val v = value.coerceIn(kind.range)
        val values = when {
            step in e.values.indices -> e.values.replaced(step, v)
            step == e.values.size && step < TiaEnvelope.MAX_LENGTH -> e.values + v
            else -> return e
        }
        return kind.fixed(e.copy(values = values))
    }

    /** The value step [step] holds, or what an appended step starts from (the last value, else [fallback]). */
    fun valueAt(e: TiaEnvelope, step: Int, fallback: Int): Int = e.values.getOrNull(step) ?: e.values.lastOrNull() ?: fallback

    /** [e] without step [step]; the loop and release points keep marking the same steps where they can. */
    fun removed(kind: TiaEnvelopeKind, e: TiaEnvelope, step: Int): TiaEnvelope {
        if (step !in e.values.indices) return e
        fun shift(point: Int) = if (point > step) point - 1 else point
        val values = e.values.toMutableList().also { it.removeAt(step) }
        return kind.fixed(TiaEnvelope(values, shift(e.loop).coerceAtMost(values.size - 1), shift(e.release).coerceAtMost(values.size - 1)))
    }

    /** [e] cut or extended (repeating its last value, else [fallback]) to [length] steps. */
    fun withLength(kind: TiaEnvelopeKind, e: TiaEnvelope, length: Int, fallback: Int): TiaEnvelope {
        val n = length.coerceIn(0, TiaEnvelope.MAX_LENGTH)
        if (n == e.values.size) return e
        val values = if (n < e.values.size) e.values.take(n) else e.values + List(n - e.values.size) { valueAt(e, e.values.size, fallback) }
        return kind.fixed(TiaEnvelope(values, e.loop.coerceAtMost(n - 1), e.release))
    }

    /** [e] looping from [loop] (-1: holds its last value); kept before the release point. */
    fun withLoop(kind: TiaEnvelopeKind, e: TiaEnvelope, loop: Int): TiaEnvelope {
        val top = if (e.release >= 0) e.release - 1 else e.values.size - 1
        return kind.fixed(e.copy(loop = loop.coerceIn(-1, top)))
    }

    /**
     * [e] releasing at [release] (-1: none): a step after the first and after the loop point. Moving below the first
     * legal step removes the release; moving up from none starts at the first legal step.
     */
    fun withRelease(kind: TiaEnvelopeKind, e: TiaEnvelope, release: Int): TiaEnvelope {
        if (e.values.size < 2) return kind.fixed(e.copy(release = -1))
        val first = maxOf(e.loop + 1, 1)
        val r = when {
            release < first -> if (e.release < 0 && release >= 0) first else -1
            else -> release.coerceAtMost(e.values.size - 1)
        }
        return kind.fixed(e.copy(release = r))
    }
}

/** A copied Atari 2600 sound or one of its envelopes. */
sealed interface TiaClip : Clip {
    data class Sound(val instrument: TiaInstrument) : TiaClip
    data class Envelope(val kind: TiaEnvelopeKind, val envelope: TiaEnvelope, val relative: Boolean) : TiaClip
}

/** Replaces instrument [slot] ([instrument] null empties it). */
fun EditorSession.setTiaInstrument(slot: Int, instrument: TiaInstrument?, label: String): Boolean {
    if (slot !in 1..TiaSystem.SLOTS) return false
    return apply(label) { s -> if (s.tia.instrument(slot) == instrument) s else s.copy(sound = s.tia.withInstrument(slot, instrument)) }
}

/** Edits instrument [slot]; an empty slot starts from [defaultTiaInstrument]. */
fun EditorSession.editTiaInstrument(slot: Int, label: String, f: (TiaInstrument) -> TiaInstrument): Boolean {
    if (slot !in 1..TiaSystem.SLOTS) return false
    val cur = song.tiaInstrument(slot)
    val next = f(cur ?: defaultTiaInstrument(slot))
    return (cur == null || next != cur) && setTiaInstrument(slot, next, label)
}

/** Edits envelope [kind] of sound [slot]; the result is made legal ([TiaEnvelopeKind.fixed]). */
fun EditorSession.editTiaEnvelope(slot: Int, kind: TiaEnvelopeKind, label: String, f: (TiaEnvelope) -> TiaEnvelope): Boolean =
    editTiaInstrument(slot, label) { kind.set(it, kind.fixed(f(kind.of(it)))) }

/** Sets sound [slot]'s waveform (AUDC, clamped to 0-F). */
fun EditorSession.setTiaWaveform(slot: Int, waveform: Int): Boolean =
    editTiaInstrument(slot, "Set waveform") { it.copy(waveform = waveform.coerceIn(TiaEnvelope.WAVEFORM)) }

/** Switches sound [slot] between tonal pitch (the note picks the nearest divider) and raw (the note is the divider). */
fun EditorSession.toggleTiaRaw(slot: Int): Boolean =
    editTiaInstrument(slot, "Set pitch mode") { it.copy(raw = !it.raw) }

/** Replaces sound [slot] with [preset] (named after it). Returns false when nothing changed. */
fun EditorSession.applyTiaPreset(slot: Int, preset: TiaPresets.Preset): Boolean =
    setTiaInstrument(slot, preset.instrument.copy(name = preset.name.take(FmControls.NAME_MAX)), "Preset ${preset.name}")

fun EditorSession.copyTiaInstrument(slot: Int) {
    val i = song.tiaInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(TiaClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyTiaEnvelope(slot: Int, kind: TiaEnvelopeKind) {
    val i = song.tiaInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(TiaClip.Envelope(kind, kind.of(i), i.relativePitch))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it (its own record: the slot it came from stays separate); an
 * envelope goes into [kind] (values clamped to its range, so a shape can move between envelopes), when [kind] is not
 * null.
 */
fun EditorSession.pasteTia(slot: Int, kind: TiaEnvelopeKind?): Boolean = when (val clip = clipboard) {
    is TiaClip.Sound -> setTiaInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is TiaClip.Envelope -> if (kind == null) { note("Choose an envelope page to paste the ${clip.kind.label.lowercase()} envelope into"); false }
        else editTiaInstrument(slot, "Paste ${kind.label.lowercase()} envelope") {
            val with = kind.set(it, kind.fixed(clip.envelope))
            if (kind == TiaEnvelopeKind.PITCH && clip.kind == TiaEnvelopeKind.PITCH) with.copy(relativePitch = clip.relative) else with
        }.also { note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    else -> { note("Clipboard holds no Atari 2600 sound or envelope"); false }
}
