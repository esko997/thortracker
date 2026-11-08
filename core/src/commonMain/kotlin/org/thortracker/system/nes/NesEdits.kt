package org.thortracker.system.nes

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song

/** NES document edits: the one instrument bank and its envelopes, as undoable [EditorSession] commands. */

/**
 * The envelopes of a [NesInstrument], with the values each holds ([NesEnvelope] ranges; duty depends on the sound's
 * [NesChip]) and the value a new step starts from: the four every sound has (volume, arpeggio, pitch, duty), then the
 * 5B's [MIXER] and [NOISE], which only 5B sounds hold ([belongsTo]). Only volume has a release point (FamiStudio's rule).
 */
enum class NesEnvelopeKind(val label: String, val range: IntRange, val fallback: Int) {
    VOLUME("Volume", NesEnvelope.VOLUME, 15),
    ARPEGGIO("Arpeggio", NesEnvelope.ARPEGGIO, 0),
    PITCH("Pitch", NesEnvelope.PITCH, 0),
    DUTY("Duty", NesEnvelope.DUTY, 0),
    MIXER("Mixer", NesEnvelope.MIXER, 2),
    NOISE("Noise", NesEnvelope.NOISE, 0);

    val canRelease: Boolean get() = this == VOLUME

    /** The values this envelope holds on a [chip] sound: the VRC6 has eight duties. */
    fun range(chip: NesChip): IntRange = if (this == DUTY) chip.duty else range

    /** Whether a [chip] sound has this envelope: duty only where the chip has duties, mixer and noise only on the 5B. */
    fun belongsTo(chip: NesChip): Boolean = when (this) {
        DUTY -> chip.hasDuty
        MIXER, NOISE -> chip == NesChip.S5B
        else -> true
    }

    fun of(i: NesInstrument): NesEnvelope = when (this) {
        VOLUME -> i.volume
        ARPEGGIO -> i.arpeggio
        PITCH -> i.pitch
        DUTY -> i.duty
        MIXER -> i.mixer
        NOISE -> i.noise
    }

    fun set(i: NesInstrument, e: NesEnvelope): NesInstrument = when (this) {
        VOLUME -> i.copy(volume = e)
        ARPEGGIO -> i.copy(arpeggio = e)
        PITCH -> i.copy(pitch = e)
        DUTY -> i.copy(duty = e)
        MIXER -> i.copy(mixer = e)
        NOISE -> i.copy(noise = e)
    }

    /**
     * [e] made legal for this envelope of a [chip] sound: values clamped, loop < length, and a release only on volume
     * and only after a loop (loop < release < length; FamiStudio drops a release without a loop). An envelope the
     * chip's sounds do not have ([belongsTo]: a VRC7 or 5B sound's duty, a mixer or noise envelope off the 5B) is
     * always empty.
     */
    fun fixed(e: NesEnvelope, chip: NesChip = NesChip.APU): NesEnvelope {
        if (!belongsTo(chip)) return if (e.values.isEmpty() && e.loop < 0 && e.release < 0) e else NesEnvelope()
        val range = range(chip)
        val values = if (e.values.all { it in range }) e.values else e.values.map { it.coerceIn(range) }
        val last = values.size - 1
        var release = if (canRelease && e.release in 1..last) e.release else -1
        var loop = if (e.loop in 0..last) e.loop else -1
        if (release >= 0 && loop >= release) loop = release - 1
        if (loop < 0) release = -1
        val out = NesEnvelope(values, loop, release)
        return if (out == e) e else out
    }
}

/** Envelope step edits, kept legal by [NesEnvelopeKind.fixed]. A step index of `size` appends. */
object NesEnvelopes {
    /** [e] with step [step] set to [value]; [step] == size appends (up to [NesEnvelope.MAX_LENGTH]). */
    fun withValue(kind: NesEnvelopeKind, e: NesEnvelope, step: Int, value: Int, chip: NesChip = NesChip.APU): NesEnvelope {
        val v = value.coerceIn(kind.range(chip))
        val values = when {
            step in e.values.indices -> e.values.replaced(step, v)
            step == e.values.size && step < NesEnvelope.MAX_LENGTH -> e.values + v
            else -> return e
        }
        return kind.fixed(e.copy(values = values), chip)
    }

    /** The value step [step] holds, or what an appended step starts from (the last value, else the default). */
    fun valueAt(kind: NesEnvelopeKind, e: NesEnvelope, step: Int): Int =
        e.values.getOrNull(step) ?: e.values.lastOrNull() ?: kind.fallback

    /** [e] without step [step]; the loop and release points keep marking the same steps where they can. */
    fun removed(kind: NesEnvelopeKind, e: NesEnvelope, step: Int, chip: NesChip = NesChip.APU): NesEnvelope {
        if (step !in e.values.indices) return e
        fun shift(point: Int) = if (point > step) point - 1 else point
        val values = e.values.toMutableList().also { it.removeAt(step) }
        return kind.fixed(NesEnvelope(values, shift(e.loop).coerceAtMost(values.size - 1), shift(e.release).coerceAtMost(values.size - 1)), chip)
    }

    /** [e] cut or extended (repeating its last value) to [length] steps; a loop past the end moves to the last step. */
    fun withLength(kind: NesEnvelopeKind, e: NesEnvelope, length: Int, chip: NesChip = NesChip.APU): NesEnvelope {
        val n = length.coerceIn(0, NesEnvelope.MAX_LENGTH)
        if (n == e.values.size) return e
        val values = if (n < e.values.size) e.values.take(n) else e.values + List(n - e.values.size) { valueAt(kind, e, e.values.size) }
        return kind.fixed(NesEnvelope(values, e.loop.coerceAtMost(n - 1), e.release), chip)
    }

    /** [e] looping from [loop] (-1: holds its last value); kept before the release point. */
    fun withLoop(kind: NesEnvelopeKind, e: NesEnvelope, loop: Int, chip: NesChip = NesChip.APU): NesEnvelope {
        val top = if (e.release >= 0) e.release - 1 else e.values.size - 1
        return kind.fixed(e.copy(loop = loop.coerceIn(-1, top)), chip)
    }

    /**
     * [e] releasing at [release] (-1: none), after the loop point. A release needs a loop: setting one on an envelope
     * without a loop loops it from step 0. Moving below the first legal step removes the release.
     */
    fun withRelease(kind: NesEnvelopeKind, e: NesEnvelope, release: Int): NesEnvelope {
        if (!kind.canRelease || e.values.size < 2) return kind.fixed(e.copy(release = -1))
        val loop = maxOf(e.loop, 0)
        val first = loop + 1
        val r = when {
            release < first -> if (e.release < 0 && release >= 0) first else -1
            else -> release.coerceAtMost(e.values.size - 1)
        }
        return kind.fixed(if (r < 0) e.copy(release = -1) else e.copy(loop = loop, release = r))
    }
}

/** Instrument [slot] (1..[NesSystem.SLOTS]), or null. */
fun Song.nesInstrument(slot: Int): NesInstrument? = nes.instruments.getOrNull(slot - 1)

/** A new instrument for [slot], as a blank song has it. */
fun defaultNesInstrument(slot: Int) = NesInstrument(name = "Sound ${hex2(slot)}")

/**
 * Sound [slot] made a [chip] sound: duty values clamped to that chip's (VRC7 and 5B sounds have none), the saw volume,
 * the FM patch and the 5B's mixer, noise and hardware envelope back to their defaults when it leaves that chip.
 * Refused (with a note) when the chip is not the song's or the sound still maps DPCM samples, which only 2A03 sounds
 * play.
 */
fun EditorSession.setNesChip(slot: Int, chip: NesChip): Boolean {
    val ins = song.nesInstrument(slot) ?: return false
    if (ins.chip == chip) return false
    if (chip !in song.nes.expansion.chips) { note("This song has no ${chip.label} chip"); return false }
    if (chip != NesChip.APU && ins.dpcm.isNotEmpty()) {
        note("Sound ${hex2(slot)} maps DPCM samples, which only 2A03 sounds play; clear its keys first")
        return false
    }
    return setNesInstrument(slot, ins.forChip(chip), "Make sound ${chip.label}")
}

/** This sound as a [chip] sound: its envelopes kept (duty clamped, none on the VRC7 and 5B), other chips' settings reset. */
fun NesInstrument.forChip(chip: NesChip): NesInstrument = copy(
    chip = chip,
    duty = NesEnvelopeKind.DUTY.fixed(duty, chip),
    sawVolume = if (chip == NesChip.VRC6) sawVolume else Vrc6SawVolume.HALF,
    fm = if (chip == NesChip.VRC7) fm else Vrc7Patch.DEFAULT,
    mixer = NesEnvelopeKind.MIXER.fixed(mixer, chip),
    noise = NesEnvelopeKind.NOISE.fixed(noise, chip),
    s5b = if (chip == NesChip.S5B) s5b else S5bEnvelope.DEFAULT,
)

/** [instrument] as [song] can hold it: a sound of a chip the song lacks becomes a 2A03 sound (pasted across songs). */
fun nesInstrumentFor(song: Song, instrument: NesInstrument): NesInstrument =
    if (instrument.chip in song.nes.expansion.chips) instrument else instrument.forChip(NesChip.APU)

/** Edits the FM patch of VRC7 sound [slot]. */
fun EditorSession.editNesFm(slot: Int, label: String, f: (Vrc7Patch) -> Vrc7Patch): Boolean =
    editNesInstrument(slot, label) { if (it.chip == NesChip.VRC7) it.copy(fm = f(it.fm)) else it }

/** Edits the hardware envelope settings of 5B sound [slot], kept in range (octave -8..8, period 0..65535). */
fun EditorSession.editNesS5b(slot: Int, label: String, f: (S5bEnvelope) -> S5bEnvelope): Boolean =
    editNesInstrument(slot, label) {
        if (it.chip != NesChip.S5B) it
        else f(it.s5b).let { e -> it.copy(s5b = e.copy(octave = e.octave.coerceIn(S5bEnvelope.OCTAVES), period = e.period.coerceIn(S5bEnvelope.PERIODS))) }
    }

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

/** Edits envelope [kind] of sound [slot]; the result is made legal for the sound's chip ([NesEnvelopeKind.fixed]). */
fun EditorSession.editNesEnvelope(slot: Int, kind: NesEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean =
    editNesInstrument(slot, label) { kind.set(it, kind.fixed(f(kind.of(it)), it.chip)) }

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
            val ok = setNesInstrument(slot, nesInstrumentFor(song, clip.instrument), "Paste sound")
            note("Pasted into sound ${hex2(slot)}")
            return ok
        }
        is NesClip.Envelope -> {
            val ok = editNesInstrument(slot, "Paste ${kind.label.lowercase()} envelope") {
                val with = kind.set(it, kind.fixed(clip.envelope, it.chip))
                if (kind == NesEnvelopeKind.PITCH && clip.kind == NesEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
            }
            note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}")
            return ok
        }
        else -> { note("Clipboard holds no NES sound or envelope"); return false }
    }
}
