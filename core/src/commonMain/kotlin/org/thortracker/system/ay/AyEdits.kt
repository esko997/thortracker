package org.thortracker.system.ay

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.nes.NesEnvelopes

/** AY-3-8910 / YM2149 document edits for the instrument editor: the one bank of [AySystem.SLOTS] sounds and their envelopes. */

/**
 * The five software envelopes of an [AyInstrument] ([AyRules] ranges): [empty] is what an envelope without steps plays
 * (null: the noise envelope then claims no noise period), [fallback] the value a first new step starts from. Steps,
 * loop and release follow the NES envelopes' rules through [base] (only volume has a release point).
 */
enum class AyEnvelopeKind(val label: String, val range: IntRange, val empty: Int?, val fallback: Int, val base: NesEnvelopeKind) {
    VOLUME("Volume", AyRules.VOLUME, 15, 15, NesEnvelopeKind.VOLUME),
    ARPEGGIO("Arpeggio", AyRules.ARPEGGIO, 0, 0, NesEnvelopeKind.ARPEGGIO),
    PITCH("Pitch", AyRules.PITCH, 0, 0, NesEnvelopeKind.PITCH),
    MIXER("Mixer", AyRules.MIXER, 1, 1, NesEnvelopeKind.ARPEGGIO),
    NOISE("Noise", AyRules.NOISE, null, 0, NesEnvelopeKind.ARPEGGIO);

    val canRelease: Boolean get() = base.canRelease

    /** [e] made legal: the NES step rules (loop before the end, a release only on volume and after a loop), values in [range]. */
    fun fixed(e: NesEnvelope): NesEnvelope {
        val f = base.fixed(e)
        return if (f.values.all { it in range }) f else f.copy(values = f.values.map { it.coerceIn(range) })
    }
}

/** Envelope step edits ([NesEnvelopes] on each kind's [AyEnvelopeKind.base]), kept legal by [AyEnvelopeKind.fixed]. */
object AyEnvelopes {
    /** [e] with step [step] set to [value]; [step] == size appends (up to [NesEnvelope.MAX_LENGTH]). */
    fun withValue(kind: AyEnvelopeKind, e: NesEnvelope, step: Int, value: Int): NesEnvelope =
        kind.fixed(NesEnvelopes.withValue(kind.base, e, step, value.coerceIn(kind.range)))

    /** The value step [step] holds, or what an appended step starts from (the last value, else [AyEnvelopeKind.fallback]). */
    fun valueAt(kind: AyEnvelopeKind, e: NesEnvelope, step: Int): Int = e.values.getOrNull(step) ?: e.values.lastOrNull() ?: kind.fallback

    /** [e] without step [step]; loop and release keep marking the same steps where they can. */
    fun removed(kind: AyEnvelopeKind, e: NesEnvelope, step: Int): NesEnvelope = kind.fixed(NesEnvelopes.removed(kind.base, e, step))

    /** [e] cut or extended (repeating its last value, or [AyEnvelopeKind.fallback] when empty) to [length] steps. */
    fun withLength(kind: AyEnvelopeKind, e: NesEnvelope, length: Int): NesEnvelope {
        val n = length.coerceIn(0, NesEnvelope.MAX_LENGTH)
        if (e.values.isEmpty() && n > 0) return kind.fixed(NesEnvelope(List(n) { kind.fallback }))
        return kind.fixed(NesEnvelopes.withLength(kind.base, e, n))
    }

    /** [e] looping from [loop] (-1: holds its last value). */
    fun withLoop(kind: AyEnvelopeKind, e: NesEnvelope, loop: Int): NesEnvelope = kind.fixed(NesEnvelopes.withLoop(kind.base, e, loop))

    /** [e] releasing at [release] (-1: none); volume only. */
    fun withRelease(kind: AyEnvelopeKind, e: NesEnvelope, release: Int): NesEnvelope = kind.fixed(NesEnvelopes.withRelease(kind.base, e, release))

    /** True when [e]'s steps hold more than one value (the Sound page's one-value controls show "≈"). */
    fun varies(e: NesEnvelope): Boolean = e.values.any { it != e.values[0] }

    /** The value [e] starts with: its first step, else what an empty envelope of [kind] plays (null: no noise claim). */
    fun first(kind: AyEnvelopeKind, e: NesEnvelope): Int? = e.values.firstOrNull() ?: kind.empty

    /** A one-value envelope playing [value] (null: none); empty when that is what an empty envelope plays anyway. */
    fun constant(kind: AyEnvelopeKind, value: Int?): NesEnvelope =
        if (value == null || value == kind.empty) NesEnvelope() else NesEnvelope(listOf(value.coerceIn(kind.range)))
}

/** Software envelope [kind] of this sound (named apart from the hardware [AyInstrument.envelope] settings). */
fun AyInstrument.steps(kind: AyEnvelopeKind): NesEnvelope = when (kind) {
    AyEnvelopeKind.VOLUME -> volume
    AyEnvelopeKind.ARPEGGIO -> arpeggio
    AyEnvelopeKind.PITCH -> pitch
    AyEnvelopeKind.MIXER -> mixer
    AyEnvelopeKind.NOISE -> noise
}

/** This sound with software envelope [kind] replaced. */
fun AyInstrument.withSteps(kind: AyEnvelopeKind, e: NesEnvelope): AyInstrument = when (kind) {
    AyEnvelopeKind.VOLUME -> copy(volume = e)
    AyEnvelopeKind.ARPEGGIO -> copy(arpeggio = e)
    AyEnvelopeKind.PITCH -> copy(pitch = e)
    AyEnvelopeKind.MIXER -> copy(mixer = e)
    AyEnvelopeKind.NOISE -> copy(noise = e)
}

/** Instrument [slot] (1..[AySystem.SLOTS]), or null when empty or out of range. */
fun Song.ayInstrument(slot: Int): AyInstrument? = ay.instrument(slot)

/** The preset an empty slot starts from on its first edit. */
const val AY_STARTING_PRESET = "Square lead"

/** The sound an empty slot gets when it is first edited: the Square lead preset, named after the slot. */
fun defaultAyInstrument(slot: Int): AyInstrument =
    (AyPresets.ALL.firstOrNull { it.name == AY_STARTING_PRESET } ?: AyPresets.ALL.first()).instrument.copy(name = "Sound ${hex2(slot)}")

/** A copied AY sound, one of its software envelopes, or its hardware envelope settings. */
sealed interface AyClip : Clip {
    data class Sound(val instrument: AyInstrument) : AyClip
    data class Envelope(val kind: AyEnvelopeKind, val envelope: NesEnvelope, val relative: Boolean) : AyClip
    data class Hardware(val settings: AyEnvelopeSettings) : AyClip
}

/** Replaces instrument [slot] ([instrument] null empties it). */
fun EditorSession.setAyInstrument(slot: Int, instrument: AyInstrument?, label: String): Boolean {
    if (slot !in 1..AySystem.SLOTS) return false
    return apply(label) { s -> if (s.ay.instrument(slot) == instrument) s else s.copy(sound = s.ay.withInstrument(slot, instrument)) }
}

/** Edits sound [slot] as one undo step; an empty slot starts from [defaultAyInstrument]. */
fun EditorSession.editAy(slot: Int, label: String, f: (AyInstrument) -> AyInstrument): Boolean {
    if (slot !in 1..AySystem.SLOTS) return false
    val cur = song.ayInstrument(slot)
    val next = f(cur ?: defaultAyInstrument(slot))
    return (cur == null || next != cur) && setAyInstrument(slot, next, label)
}

/** Edits envelope [kind] of sound [slot]; the result is made legal ([AyEnvelopeKind.fixed]). */
fun EditorSession.editAyEnvelope(slot: Int, kind: AyEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean =
    editAy(slot, label) { it.withSteps(kind, kind.fixed(f(it.steps(kind)))) }

/** Edits the hardware envelope settings of sound [slot]. */
fun EditorSession.editAyHardware(slot: Int, label: String, f: (AyEnvelopeSettings) -> AyEnvelopeSettings): Boolean =
    editAy(slot, label) { it.copy(envelope = f(it.envelope)) }

/** Renames [slot]'s sound. */
fun EditorSession.renameAy(slot: Int, f: (String) -> String): Boolean {
    val cur = song.ayInstrument(slot) ?: return false
    return setAyInstrument(slot, cur.copy(name = f(cur.name)), "Rename sound")
}

fun EditorSession.copyAyInstrument(slot: Int) {
    val i = song.ayInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(AyClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyAyEnvelope(slot: Int, kind: AyEnvelopeKind) {
    val i = song.ayInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(AyClip.Envelope(kind, i.steps(kind), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

fun EditorSession.copyAyHardware(slot: Int) {
    val i = song.ayInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(AyClip.Hardware(i.envelope))
    note("Copied the hardware envelope settings of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it; a software envelope goes into envelope [kind] (values clamped
 * to its range, the release kept only on volume), so a shape can move between envelopes, and null [kind] (a page
 * without one) refuses it; hardware envelope settings replace the sound's on any page.
 */
fun EditorSession.pasteAy(slot: Int, kind: AyEnvelopeKind?): Boolean = when (val clip = clipboard) {
    is AyClip.Sound -> setAyInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is AyClip.Envelope -> if (kind == null) {
        note("An envelope pastes into an envelope page (Volume, Arpeggio, Pitch, Mixer, Noise)"); false
    } else editAy(slot, "Paste ${kind.label.lowercase()} envelope") {
        val with = it.withSteps(kind, kind.fixed(clip.envelope))
        if (kind == AyEnvelopeKind.PITCH && clip.kind == AyEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
    }.also { note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    is AyClip.Hardware -> editAyHardware(slot, "Paste hardware envelope") { clip.settings }
        .also { note("Pasted the hardware envelope settings into sound ${hex2(slot)}") }
    else -> { note("Clipboard holds no AY sound or envelope"); false }
}
