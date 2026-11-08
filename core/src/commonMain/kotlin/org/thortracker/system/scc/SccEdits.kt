package org.thortracker.system.scc

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope

/**
 * SCC document edits for the Instrument and Wave views: the one bank of [SccSound.SLOTS] sounds (empty slots are
 * null), their four envelopes and the [SccSound.WAVES] wavetables, as undoable [EditorSession] commands.
 */

/**
 * The four envelopes of an [SccInstrument], with the values each holds (docs/AUDIO_ENGINE.md, "SCC and SCC+",
 * Envelope), the value a new step starts from and the fewest steps it may have (the wave envelope always has one: a
 * note must play some wave). Only volume has a release point.
 */
enum class SccEnvelopeKind(val label: String, val range: IntRange, val fallback: Int, val minLength: Int) {
    VOLUME("Volume", 0..15, 15, 0),
    ARPEGGIO("Arpeggio", -64..63, 0, 0),
    PITCH("Pitch", -64..63, 0, 0),
    WAVE("Wave", 1..SccSound.WAVES, 1, 1);

    val canRelease: Boolean get() = this == VOLUME

    fun of(i: SccInstrument): NesEnvelope = when (this) {
        VOLUME -> i.volume
        ARPEGGIO -> i.arpeggio
        PITCH -> i.pitch
        WAVE -> i.wave
    }

    fun set(i: SccInstrument, e: NesEnvelope): SccInstrument = when (this) {
        VOLUME -> i.copy(volume = e)
        ARPEGGIO -> i.copy(arpeggio = e)
        PITCH -> i.copy(pitch = e)
        WAVE -> i.copy(wave = e)
    }

    /**
     * [e] made legal for the SCC player: at most [NesEnvelope.MAX_LENGTH] values clamped to [range] (a wave envelope
     * without values gets one, wave 1), loop 0..length - 1 or none, a release only on volume at 1..length - 1, and
     * with both the loop before the release. Unlike FamiStudio's NES envelopes a release needs no loop: the note then
     * holds the value before the release point until OFF.
     */
    fun fixed(e: NesEnvelope): NesEnvelope {
        val clamped = if (e.values.all { it in range }) e.values else e.values.map { it.coerceIn(range) }
        val sized = if (clamped.size > NesEnvelope.MAX_LENGTH) clamped.take(NesEnvelope.MAX_LENGTH) else clamped
        val values = if (sized.size < minLength) sized + List(minLength - sized.size) { fallback } else sized
        val last = values.size - 1
        val release = if (canRelease && e.release in 1..last) e.release else -1
        var loop = if (e.loop in 0..last) e.loop else -1
        if (release >= 0 && loop >= release) loop = release - 1
        val out = NesEnvelope(values, loop, release)
        return if (out == e) e else out
    }
}

/** Envelope step edits, kept legal by [SccEnvelopeKind.fixed]. A step index of `size` appends. */
object SccEnvelopes {
    /** [e] with step [step] set to [value]; [step] == size appends (up to [NesEnvelope.MAX_LENGTH]). */
    fun withValue(kind: SccEnvelopeKind, e: NesEnvelope, step: Int, value: Int): NesEnvelope {
        val v = value.coerceIn(kind.range)
        val values = when {
            step in e.values.indices -> e.values.replaced(step, v)
            step == e.values.size && step < NesEnvelope.MAX_LENGTH -> e.values + v
            else -> return e
        }
        return kind.fixed(e.copy(values = values))
    }

    /** The value step [step] holds, or what an appended step starts from (the last value, else the default). */
    fun valueAt(kind: SccEnvelopeKind, e: NesEnvelope, step: Int): Int =
        e.values.getOrNull(step) ?: e.values.lastOrNull() ?: kind.fallback

    /**
     * [e] without step [step]; the loop and release points keep marking the same steps where they can. The wave
     * envelope's last step is kept (see [SccEnvelopeKind.minLength]).
     */
    fun removed(kind: SccEnvelopeKind, e: NesEnvelope, step: Int): NesEnvelope {
        if (step !in e.values.indices || e.values.size <= kind.minLength) return e
        fun shift(point: Int) = if (point > step) point - 1 else point
        val values = e.values.toMutableList().also { it.removeAt(step) }
        return kind.fixed(NesEnvelope(values, shift(e.loop).coerceAtMost(values.size - 1), shift(e.release).coerceAtMost(values.size - 1)))
    }

    /** [e] cut or extended (repeating its last value) to [length] steps; a loop past the end moves to the last step. */
    fun withLength(kind: SccEnvelopeKind, e: NesEnvelope, length: Int): NesEnvelope {
        val n = length.coerceIn(kind.minLength, NesEnvelope.MAX_LENGTH)
        if (n == e.values.size) return e
        val values = if (n < e.values.size) e.values.take(n) else e.values + List(n - e.values.size) { valueAt(kind, e, e.values.size) }
        return kind.fixed(NesEnvelope(values, e.loop.coerceAtMost(n - 1), e.release))
    }

    /** [e] looping from [loop] (-1: holds its last value); kept before the release point. */
    fun withLoop(kind: SccEnvelopeKind, e: NesEnvelope, loop: Int): NesEnvelope {
        val top = if (e.release >= 0) e.release - 1 else e.values.size - 1
        return kind.fixed(e.copy(loop = loop.coerceIn(-1, top)))
    }

    /**
     * [e] releasing at [release] (-1: none), after the loop point (or after step 0 without a loop). Moving below the
     * first legal step removes the release; moving up from none starts at the first legal step.
     */
    fun withRelease(kind: SccEnvelopeKind, e: NesEnvelope, release: Int): NesEnvelope {
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

/** Instrument [slot] (1..[SccSound.SLOTS]), or null when empty or out of range. */
fun Song.sccInstrument(slot: Int): SccInstrument? = scc.instrument(slot)

/**
 * The sound an empty slot gets when it is first edited: the first preset (its waves are the starter waves a new song
 * holds), named after the slot, so a new sound is musical at once.
 */
fun defaultSccInstrument(slot: Int): SccInstrument = SccPresets.ALL.first().instrument.copy(name = "Sound ${hex2(slot)}")

/** A copied SCC sound, one of its envelopes, or a wavetable. */
sealed interface SccClip : Clip {
    data class Sound(val instrument: SccInstrument) : SccClip
    data class Envelope(val kind: SccEnvelopeKind, val envelope: NesEnvelope, val relative: Boolean) : SccClip
    data class Wave(val wave: SccWave) : SccClip
}

/** Replaces instrument [slot] ([instrument] null empties it); envelopes are made legal ([SccEnvelopeKind.fixed]). */
fun EditorSession.setSccInstrument(slot: Int, instrument: SccInstrument?, label: String): Boolean {
    if (slot !in 1..SccSound.SLOTS) return false
    val fixed = instrument?.let { i -> SccEnvelopeKind.entries.fold(i) { acc, k -> k.set(acc, k.fixed(k.of(acc))) } }
    return apply(label) { s -> if (s.scc.instrument(slot) == fixed) s else s.copy(sound = s.scc.withInstrument(slot, fixed)) }
}

/** Edits instrument [slot]; an empty slot starts from [defaultSccInstrument] (so the first edit makes the sound). */
fun EditorSession.editSccInstrument(slot: Int, label: String, f: (SccInstrument) -> SccInstrument): Boolean {
    if (slot !in 1..SccSound.SLOTS) return false
    val cur = song.sccInstrument(slot)
    val next = f(cur ?: defaultSccInstrument(slot))
    return (cur == null || next != cur) && setSccInstrument(slot, next, label)
}

/** Edits envelope [kind] of sound [slot]; the result is made legal ([SccEnvelopeKind.fixed]). */
fun EditorSession.editSccEnvelope(slot: Int, kind: SccEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean =
    editSccInstrument(slot, label) { kind.set(it, kind.fixed(f(kind.of(it)))) }

/** Replaces wave [number] (1..[SccSound.WAVES]); samples are clamped to -128..127 and must number [SccWave.SAMPLES]. */
fun EditorSession.setSccWave(number: Int, wave: SccWave, label: String): Boolean {
    if (number !in 1..SccSound.WAVES || wave.samples.size != SccWave.SAMPLES) return false
    val clamped = if (wave.samples.all { it in SccWave.LEVELS }) wave else wave.copy(samples = wave.samples.map { it.coerceIn(SccWave.LEVELS) })
    val named = if (clamped.name.length > EditorSession.MAX_NAME) clamped.copy(name = clamped.name.take(EditorSession.MAX_NAME)) else clamped
    return apply(label) { s -> if (s.scc.wave(number) == named) s else s.copy(sound = s.scc.withWave(number, named)) }
}

/** Edits wave [number]. */
fun EditorSession.editSccWave(number: Int, label: String, f: (SccWave) -> SccWave): Boolean {
    val cur = song.scc.wave(number) ?: return false
    return setSccWave(number, f(cur), label)
}

/** Replaces the samples of wave [number], keeping its name. */
fun EditorSession.setSccWaveSamples(number: Int, samples: List<Int>, label: String): Boolean =
    editSccWave(number, label) { it.copy(samples = samples) }

/** Sets sample [index] (0..31) of wave [number] to [value] (-128..127). */
fun EditorSession.setSccWaveSample(number: Int, index: Int, value: Int): Boolean {
    val w = song.scc.wave(number) ?: return false
    if (index !in w.samples.indices) return false
    return setSccWave(number, w.copy(samples = w.samples.replaced(index, value.coerceIn(SccWave.LEVELS))), "Draw wave")
}

fun EditorSession.copySccInstrument(slot: Int) {
    val i = song.sccInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(SccClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copySccEnvelope(slot: Int, kind: SccEnvelopeKind) {
    val i = song.sccInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(SccClip.Envelope(kind, kind.of(i), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

fun EditorSession.copySccWave(number: Int) {
    val w = song.scc.wave(number) ?: return
    hold(SccClip.Wave(w))
    note("Copied wave ${hex2(number)}${if (w.name.isEmpty()) "" else " ${w.name}"}")
}

/**
 * Pastes the clipboard into sound [slot]: a sound replaces it; an envelope goes into [kind] (values clamped to its
 * range, the release kept only on volume), so a shape can move between envelopes (a pitch envelope keeps its mode).
 */
fun EditorSession.pasteSccSound(slot: Int, kind: SccEnvelopeKind): Boolean = when (val clip = clipboard) {
    is SccClip.Sound -> setSccInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is SccClip.Envelope -> editSccInstrument(slot, "Paste ${kind.label.lowercase()} envelope") {
        val with = kind.set(it, kind.fixed(clip.envelope))
        if (kind == SccEnvelopeKind.PITCH && clip.kind == SccEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
    }.also { note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    else -> { note("Clipboard holds no SCC sound or envelope"); false }
}

/** Pastes a copied wavetable (name and samples) into wave [number]. */
fun EditorSession.pasteSccWave(number: Int): Boolean {
    val clip = clipboard as? SccClip.Wave ?: run { note("Clipboard holds no SCC wave"); return false }
    val ok = setSccWave(number, clip.wave, "Paste wave")
    note("Pasted into wave ${hex2(number)}")
    return ok
}
