package org.thortracker.system.saa

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope

/**
 * SAA1099 document edits for the Instrument view: the one bank of [SaaSound.SLOTS] sounds (empty slots are null), their
 * four envelopes and chip settings, as undoable [EditorSession] commands.
 */

/**
 * The four envelopes of an [SaaInstrument], with the values each holds (docs/AUDIO_ENGINE.md, "SAA1099", Envelope)
 * and the value a new step starts from. Only volume has a release point.
 */
enum class SaaEnvelopeKind(val label: String, val range: IntRange, val fallback: Int) {
    VOLUME("Volume", 0..15, 15),
    ARPEGGIO("Arpeggio", -64..63, 0),
    PITCH("Pitch", -64..63, 0),
    MIXER("Mixer", SaaInstrument.MIXER, SaaInstrument.MIXER_TONE);

    val canRelease: Boolean get() = this == VOLUME

    fun of(i: SaaInstrument): NesEnvelope = when (this) {
        VOLUME -> i.volume
        ARPEGGIO -> i.arpeggio
        PITCH -> i.pitch
        MIXER -> i.mixer
    }

    fun set(i: SaaInstrument, e: NesEnvelope): SaaInstrument = when (this) {
        VOLUME -> i.copy(volume = e)
        ARPEGGIO -> i.copy(arpeggio = e)
        PITCH -> i.copy(pitch = e)
        MIXER -> i.copy(mixer = e)
    }

    /**
     * [e] made legal for the SAA1099 player: at most [NesEnvelope.MAX_LENGTH] values clamped to [range], loop
     * 0..length - 1 or none, a release only on volume at 1..length - 1, with both the loop before the release, and an
     * empty envelope without loop or release. A release needs no loop: the note then holds the value before the
     * release point until OFF.
     */
    fun fixed(e: NesEnvelope): NesEnvelope {
        val clamped = if (e.values.all { it in range }) e.values else e.values.map { it.coerceIn(range) }
        val values = if (clamped.size > NesEnvelope.MAX_LENGTH) clamped.take(NesEnvelope.MAX_LENGTH) else clamped
        val last = values.size - 1
        val release = if (canRelease && e.release in 1..last) e.release else -1
        var loop = if (e.loop in 0..last) e.loop else -1
        if (release >= 0 && loop >= release) loop = release - 1
        val out = NesEnvelope(values, loop, release)
        return if (out == e) e else out
    }
}

/** Envelope step edits, kept legal by [SaaEnvelopeKind.fixed]. A step index of `size` appends. */
object SaaEnvelopes {
    /** [e] with step [step] set to [value]; [step] == size appends (up to [NesEnvelope.MAX_LENGTH]). */
    fun withValue(kind: SaaEnvelopeKind, e: NesEnvelope, step: Int, value: Int): NesEnvelope {
        val v = value.coerceIn(kind.range)
        val values = when {
            step in e.values.indices -> e.values.replaced(step, v)
            step == e.values.size && step < NesEnvelope.MAX_LENGTH -> e.values + v
            else -> return e
        }
        return kind.fixed(e.copy(values = values))
    }

    /** The value step [step] holds, or what an appended step starts from (the last value, else the default). */
    fun valueAt(kind: SaaEnvelopeKind, e: NesEnvelope, step: Int): Int =
        e.values.getOrNull(step) ?: e.values.lastOrNull() ?: kind.fallback

    /** [e] without step [step]; the loop and release points keep marking the same steps where they can. */
    fun removed(kind: SaaEnvelopeKind, e: NesEnvelope, step: Int): NesEnvelope {
        if (step !in e.values.indices) return e
        fun shift(point: Int) = if (point > step) point - 1 else point
        val values = e.values.toMutableList().also { it.removeAt(step) }
        return kind.fixed(NesEnvelope(values, shift(e.loop).coerceAtMost(values.size - 1), shift(e.release).coerceAtMost(values.size - 1)))
    }

    /** [e] cut or extended (repeating its last value) to [length] steps; a loop past the end moves to the last step. */
    fun withLength(kind: SaaEnvelopeKind, e: NesEnvelope, length: Int): NesEnvelope {
        val n = length.coerceIn(0, NesEnvelope.MAX_LENGTH)
        if (n == e.values.size) return e
        val values = if (n < e.values.size) e.values.take(n) else e.values + List(n - e.values.size) { valueAt(kind, e, e.values.size) }
        return kind.fixed(NesEnvelope(values, e.loop.coerceAtMost(n - 1), e.release))
    }

    /** [e] looping from [loop] (-1: holds its last value); kept before the release point. */
    fun withLoop(kind: SaaEnvelopeKind, e: NesEnvelope, loop: Int): NesEnvelope {
        val top = if (e.release >= 0) e.release - 1 else e.values.size - 1
        return kind.fixed(e.copy(loop = loop.coerceIn(-1, top)))
    }

    /**
     * [e] releasing at [release] (-1: none), after the loop point (or after step 0 without a loop). Moving below the
     * first legal step removes the release; moving up from none starts at the first legal step.
     */
    fun withRelease(kind: SaaEnvelopeKind, e: NesEnvelope, release: Int): NesEnvelope {
        if (!kind.canRelease || e.values.size < 2) return kind.fixed(e.copy(release = -1))
        val first = maxOf(e.loop, 0) + 1
        if (first > e.values.size - 1) return kind.fixed(e.copy(release = -1))
        val r = when {
            release < first -> if (e.release < 0 && release >= 0) first else -1
            else -> release.coerceAtMost(e.values.size - 1)
        }
        return kind.fixed(e.copy(release = r))
    }
}

/** Hardware envelope setting edits, kept within the chip's ranges. */
object SaaHardwareEnvelopes {
    /** [e] with its shape stepped by [delta], wrapping through the eight shapes. */
    fun withShape(e: SaaHardwareEnvelope, delta: Int): SaaHardwareEnvelope =
        e.copy(shape = (e.shape + delta).mod(SaaHardwareEnvelope.SHAPES.last + 1))

    /** [e] with its external rate stepped by [delta], kept to 1..255. */
    fun withRate(e: SaaHardwareEnvelope, delta: Int): SaaHardwareEnvelope =
        e.copy(rate = (e.rate + delta).coerceIn(SaaHardwareEnvelope.RATES))
}

/** Instrument [slot] (1..[SaaSound.SLOTS]), or null when empty or out of range. */
fun Song.saaInstrument(slot: Int): SaaInstrument? = saa.instrument(slot)

/** The sound an empty slot gets when it is first edited: the first preset, named after the slot. */
fun defaultSaaInstrument(slot: Int): SaaInstrument = SaaPresets.ALL.first().instrument.copy(name = "Sound ${hex2(slot)}")

/** A copied SAA1099 sound or one of its envelopes. */
sealed interface SaaClip : Clip {
    data class Sound(val instrument: SaaInstrument) : SaaClip
    data class Envelope(val kind: SaaEnvelopeKind, val envelope: NesEnvelope, val relative: Boolean) : SaaClip
}

/**
 * Replaces instrument [slot] ([instrument] null empties it); envelopes are made legal ([SaaEnvelopeKind.fixed]), the
 * noise clock, pan and hardware envelope kept within the chip's ranges.
 */
fun EditorSession.setSaaInstrument(slot: Int, instrument: SaaInstrument?, label: String): Boolean {
    if (slot !in 1..SaaSound.SLOTS) return false
    val fixed = instrument?.let { i ->
        SaaEnvelopeKind.entries.fold(i) { acc, k -> k.set(acc, k.fixed(k.of(acc))) }.let {
            val env = it.envelope?.let { e ->
                val legal = e.copy(shape = e.shape.coerceIn(SaaHardwareEnvelope.SHAPES), rate = e.rate.coerceIn(SaaHardwareEnvelope.RATES))
                if (legal == e) e else legal
            }
            val named = if (it.name.length > EditorSession.MAX_NAME) it.name.take(EditorSession.MAX_NAME) else it.name
            val legal = it.copy(name = named, noise = it.noise.coerceIn(SaaInstrument.NOISE), panLeft = it.panLeft.coerceIn(SaaInstrument.PAN),
                panRight = it.panRight.coerceIn(SaaInstrument.PAN), envelope = env)
            if (legal == it) it else legal
        }
    }
    return apply(label) { s -> if (s.saa.instrument(slot) == fixed) s else s.copy(sound = s.saa.withInstrument(slot, fixed)) }
}

/** Edits instrument [slot]; an empty slot starts from [defaultSaaInstrument] (so the first edit makes the sound). */
fun EditorSession.editSaaInstrument(slot: Int, label: String, f: (SaaInstrument) -> SaaInstrument): Boolean {
    if (slot !in 1..SaaSound.SLOTS) return false
    val cur = song.saaInstrument(slot)
    val next = f(cur ?: defaultSaaInstrument(slot))
    return (cur == null || next != cur) && setSaaInstrument(slot, next, label)
}

/** Edits envelope [kind] of sound [slot]; the result is made legal ([SaaEnvelopeKind.fixed]). */
fun EditorSession.editSaaEnvelope(slot: Int, kind: SaaEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean =
    editSaaInstrument(slot, label) { kind.set(it, kind.fixed(f(kind.of(it)))) }

/**
 * Edits the hardware envelope of sound [slot]; a sound without one starts from a default envelope (repetitive decay,
 * internal clock), so the first edit switches it on.
 */
fun EditorSession.editSaaHardwareEnvelope(slot: Int, label: String, f: (SaaHardwareEnvelope) -> SaaHardwareEnvelope): Boolean =
    editSaaInstrument(slot, label) { it.copy(envelope = f(it.envelope ?: SaaHardwareEnvelope())) }

fun EditorSession.copySaaInstrument(slot: Int) {
    val i = song.saaInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(SaaClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copySaaEnvelope(slot: Int, kind: SaaEnvelopeKind) {
    val i = song.saaInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(SaaClip.Envelope(kind, kind.of(i), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into sound [slot]: a sound replaces it; an envelope goes into [kind] (values clamped to its
 * range, the release kept only on volume), so a shape can move between envelopes (a pitch envelope keeps its mode).
 */
fun EditorSession.pasteSaaSound(slot: Int, kind: SaaEnvelopeKind): Boolean = when (val clip = clipboard) {
    is SaaClip.Sound -> setSaaInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is SaaClip.Envelope -> editSaaInstrument(slot, "Paste ${kind.label.lowercase()} envelope") {
        val with = kind.set(it, kind.fixed(clip.envelope))
        if (kind == SaaEnvelopeKind.PITCH && clip.kind == SaaEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
    }.also { note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    else -> { note("Clipboard holds no SAA1099 sound or envelope"); false }
}
