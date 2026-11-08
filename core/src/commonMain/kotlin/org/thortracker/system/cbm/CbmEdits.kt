package org.thortracker.system.cbm

import org.thortracker.backend.CbmImage
import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song
import org.thortracker.system.fm.FmControls

/**
 * VIC-20 and C16/Plus/4 document edits for the instrument editor: the one bank of [CbmSystem.SLOTS] sounds, their
 * envelopes and (TED) the song's DAC samples, as undoable [EditorSession] commands.
 */

/** Instrument [slot] (1..[CbmSystem.SLOTS]), or null when empty or out of range. */
fun Song.cbmInstrument(slot: Int): CbmInstrument? = cbm.instrument(slot)

/** The sound an empty slot gets when it is first edited: a plain square (or sample) sound named after the slot. */
fun defaultCbmInstrument(slot: Int, kind: CbmKind = CbmKind.TONE): CbmInstrument = CbmInstrument(name = "Sound ${hex2(slot)}", kind = kind)

/**
 * The four envelopes of a [CbmInstrument], with the value a new step starts from on an empty envelope. OFF releases
 * (instead of stopping) when the volume or the wave envelope has a release point; then every envelope with one jumps to
 * it. [VOLUME] is the chip's **master** volume shape; [WAVE] the wave per tick (0 off, 1 square, 2 noise on the TED).
 */
enum class CbmEnvelopeKind(val label: String) {
    VOLUME("Master volume"),
    ARPEGGIO("Arpeggio"),
    PITCH("Pitch"),
    WAVE("Wave");

    /** The values this envelope holds on [chip]. */
    fun range(chip: CbmChip): IntRange = when (this) {
        VOLUME -> 0..chip.maxVolume
        ARPEGGIO -> CbmEnvelope.ARPEGGIO
        PITCH -> CbmEnvelope.PITCH
        WAVE -> CbmImage.WAVE_OFF..chip.maxWave
    }

    /** What a new step starts from on an empty envelope: the full master volume, no offset, the sound's own wave. */
    fun fallback(chip: CbmChip, ins: CbmInstrument?): Int = when (this) {
        VOLUME -> chip.maxVolume
        WAVE -> ins?.wave ?: CbmImage.WAVE_SQUARE
        else -> 0
    }

    fun of(i: CbmInstrument): CbmEnvelope = when (this) {
        VOLUME -> i.volume
        ARPEGGIO -> i.arpeggio
        PITCH -> i.pitch
        WAVE -> i.waves
    }

    fun set(i: CbmInstrument, e: CbmEnvelope): CbmInstrument = when (this) {
        VOLUME -> i.copy(volume = e)
        ARPEGGIO -> i.copy(arpeggio = e)
        PITCH -> i.copy(pitch = e)
        WAVE -> i.copy(waves = e)
    }

    /**
     * [e] made legal ([CbmEnvelope.problem]): values clamped into [range], at most [CbmEnvelope.MAX_LENGTH] steps, the
     * loop a step index, the release 1..length − 1 and after the loop (a loop at or past the release moves before it).
     */
    fun fixed(e: CbmEnvelope, range: IntRange): CbmEnvelope {
        val kept = if (e.values.size > CbmEnvelope.MAX_LENGTH) e.values.take(CbmEnvelope.MAX_LENGTH) else e.values
        val values = if (kept.all { it in range }) kept else kept.map { it.coerceIn(range) }
        val last = values.size - 1
        val release = if (e.release in 1..last) e.release else -1
        var loop = if (e.loop in 0..last) e.loop else -1
        if (release >= 0 && loop >= release) loop = release - 1
        val out = CbmEnvelope(values, loop, release)
        return if (out == e) e else out
    }
}

/** Envelope step edits, kept legal by [CbmEnvelopeKind.fixed]. A step index of `size` appends. */
object CbmEnvelopes {
    /** The furthest step a cursor reaches: the append position after the last step, while there is room. */
    fun lastStep(e: CbmEnvelope): Int = minOf(e.values.size, CbmEnvelope.MAX_LENGTH - 1)

    /** [e] with step [step] set to [value] (clamped into [range]); [step] == size appends while there is room. */
    fun withValue(kind: CbmEnvelopeKind, e: CbmEnvelope, step: Int, value: Int, range: IntRange): CbmEnvelope {
        val v = value.coerceIn(range)
        val values = when {
            step in e.values.indices -> e.values.replaced(step, v)
            step == e.values.size && step < CbmEnvelope.MAX_LENGTH -> e.values + v
            else -> return e
        }
        return kind.fixed(e.copy(values = values), range)
    }

    /** The value step [step] holds, or what an appended step starts from (the last value, else [fallback]). */
    fun valueAt(e: CbmEnvelope, step: Int, fallback: Int): Int = e.values.getOrNull(step) ?: e.values.lastOrNull() ?: fallback

    /** [e] without step [step]; the loop and release points keep marking the same steps where they can. */
    fun removed(kind: CbmEnvelopeKind, e: CbmEnvelope, step: Int, range: IntRange): CbmEnvelope {
        if (step !in e.values.indices) return e
        fun shift(point: Int) = if (point > step) point - 1 else point
        val values = e.values.toMutableList().also { it.removeAt(step) }
        return kind.fixed(CbmEnvelope(values, shift(e.loop).coerceAtMost(values.size - 1), shift(e.release).coerceAtMost(values.size - 1)), range)
    }

    /** [e] cut or extended (repeating its last value, else [fallback]) to [length] steps. */
    fun withLength(kind: CbmEnvelopeKind, e: CbmEnvelope, length: Int, range: IntRange, fallback: Int): CbmEnvelope {
        val n = length.coerceIn(0, CbmEnvelope.MAX_LENGTH)
        if (n == e.values.size) return e
        val values = if (n < e.values.size) e.values.take(n) else e.values + List(n - e.values.size) { valueAt(e, e.values.size, fallback) }
        return kind.fixed(CbmEnvelope(values, e.loop.coerceAtMost(n - 1), e.release), range)
    }

    /** [e] looping from [loop] (-1: holds its last value); kept before the release point. */
    fun withLoop(kind: CbmEnvelopeKind, e: CbmEnvelope, loop: Int, range: IntRange): CbmEnvelope {
        val top = if (e.release >= 0) e.release - 1 else e.values.size - 1
        return kind.fixed(e.copy(loop = loop.coerceIn(-1, top)), range)
    }

    /** [e] releasing at [release] (-1: none): a step after the first and after the loop point. */
    fun withRelease(kind: CbmEnvelopeKind, e: CbmEnvelope, release: Int, range: IntRange): CbmEnvelope {
        if (e.values.size < 2) return kind.fixed(e.copy(release = -1), range)
        val first = maxOf(e.loop + 1, 1)
        val r = when {
            release < first -> if (e.release < 0 && release >= 0) first else -1
            else -> release.coerceAtMost(e.values.size - 1)
        }
        return kind.fixed(e.copy(release = r), range)
    }
}

/** A copied VIC/TED sound or one of its envelopes. */
sealed interface CbmClip : Clip {
    data class Sound(val instrument: CbmInstrument) : CbmClip
    data class Envelope(val kind: CbmEnvelopeKind, val envelope: CbmEnvelope, val relative: Boolean) : CbmClip
}

// ---- sounds ---------------------------------------------------------------------------------------------------------

/** Replaces instrument [slot] ([instrument] null empties it). */
fun EditorSession.setCbmInstrument(slot: Int, instrument: CbmInstrument?, label: String): Boolean {
    if (slot !in 1..CbmSystem.SLOTS) return false
    return apply(label) { s -> if (s.cbm.instrument(slot) == instrument) s else s.copy(sound = s.cbm.withInstrument(slot, instrument)) }
}

/** Edits instrument [slot]; an empty slot starts from [defaultCbmInstrument]. */
fun EditorSession.editCbmInstrument(slot: Int, label: String, f: (CbmInstrument) -> CbmInstrument): Boolean {
    if (slot !in 1..CbmSystem.SLOTS) return false
    val cur = song.cbmInstrument(slot)
    val next = f(cur ?: defaultCbmInstrument(slot))
    return (cur == null || next != cur) && setCbmInstrument(slot, next, label)
}

/** Edits envelope [kind] of sound [slot]; the result is made legal ([CbmEnvelopeKind.fixed]) for the song's chip. */
fun EditorSession.editCbmEnvelope(slot: Int, kind: CbmEnvelopeKind, label: String, f: (CbmEnvelope) -> CbmEnvelope): Boolean {
    val range = kind.range(song.cbm.chip)
    return editCbmInstrument(slot, label) { kind.set(it, kind.fixed(f(kind.of(it)), range)) }
}

/**
 * Makes sound [slot] a [kind] sound (an empty slot becomes a new one); only the TED has sample sounds. Its other
 * settings are kept, so switching back restores them.
 */
fun EditorSession.setCbmKind(slot: Int, kind: CbmKind): Boolean {
    if (kind == CbmKind.SAMPLE && song.cbm.chip == CbmChip.VIC) return false
    val cur = song.cbmInstrument(slot)
    if (cur?.kind == kind) return false
    val label = if (kind == CbmKind.TONE) "Make tone sound" else "Make sample sound"
    return setCbmInstrument(slot, cur?.copy(kind = kind) ?: defaultCbmInstrument(slot, kind), label)
}

/** [instrument] made playable in [sound]: the chip's wave and envelope ranges, the sample references inside its samples. */
fun cbmInstrumentFor(sound: CbmSound, instrument: CbmInstrument): CbmInstrument {
    val chip = sound.chip
    val wave = CbmEnvelopeKind.WAVE.range(chip)
    return instrument.copy(
        kind = if (chip == CbmChip.VIC) CbmKind.TONE else instrument.kind,
        wave = instrument.wave.coerceIn(CbmImage.WAVE_SQUARE, chip.maxWave),
        volume = CbmEnvelopeKind.VOLUME.fixed(instrument.volume, CbmEnvelopeKind.VOLUME.range(chip)),
        waves = CbmEnvelopeKind.WAVE.fixed(instrument.waves, wave),
        sample = instrument.sample.coerceIn(0, maxOf(0, sound.samples.size - 1)),
        loopStart = instrument.loopStart.coerceIn(0, maxOf(0, (sound.samples.getOrNull(instrument.sample)?.data?.size ?: 1) - 1)),
    )
}

/** Replaces sound [slot] with [preset] (named after it). Returns false when nothing changed. */
fun EditorSession.applyCbmPreset(slot: Int, preset: CbmPresets.Preset): Boolean =
    setCbmInstrument(slot, cbmInstrumentFor(song.cbm, preset.instrument.copy(name = preset.name.take(FmControls.NAME_MAX))), "Preset ${preset.name}")

fun EditorSession.copyCbmInstrument(slot: Int) {
    val i = song.cbmInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(CbmClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyCbmEnvelope(slot: Int, kind: CbmEnvelopeKind) {
    val i = song.cbmInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(CbmClip.Envelope(kind, kind.of(i), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it (made playable on the song's chip, [cbmInstrumentFor]); an
 * envelope goes into [kind] (values clamped to its range), when [kind] is not null.
 */
fun EditorSession.pasteCbm(slot: Int, kind: CbmEnvelopeKind?): Boolean = when (val clip = clipboard) {
    is CbmClip.Sound -> setCbmInstrument(slot, cbmInstrumentFor(song.cbm, clip.instrument), "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is CbmClip.Envelope -> if (kind == null) { note("Choose an envelope page to paste the ${clip.kind.label.lowercase()} envelope into"); false }
        else editCbmInstrument(slot, "Paste ${kind.label.lowercase()} envelope") {
            val with = kind.set(it, kind.fixed(clip.envelope, kind.range(song.cbm.chip)))
            if (kind == CbmEnvelopeKind.PITCH && clip.kind == CbmEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
        }.also { note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    else -> { note("Clipboard holds no VIC-20 / C16 sound or envelope"); false }
}

// ---- samples (TED) ----------------------------------------------------------------------------------------------------

fun EditorSession.editCbmSample(index: Int, label: String, f: (CbmSample) -> CbmSample): Boolean {
    val cur = song.cbm.samples.getOrNull(index) ?: return false
    return apply(label) { s -> s.copy(sound = s.cbm.copy(samples = s.cbm.samples.replaced(index, f(cur)))) }
}

/**
 * Adds [samples] (one import or the starter kit) as one undo step and gives each a sound: the first plays on sound
 * [slot] (a sample sound keeps its settings and changes its sample; any other or an empty slot becomes [newSound]'s
 * sound), the others on the next empty slots after it. Samples whose frames the song already holds are reused;
 * samples beyond [CbmSound.MAX_SAMPLES] or without a free slot are left out. Returns the slots given a sample, in order.
 * Nothing on the VIC (no D/A).
 */
fun EditorSession.addCbmSamples(slot: Int, samples: List<CbmSample>, label: String,
                                newSound: (sample: Int, name: String) -> CbmInstrument): List<Int> {
    if (slot !in 1..CbmSystem.SLOTS || samples.isEmpty() || song.cbm.chip != CbmChip.TED) return emptyList()
    var sound = song.cbm
    val all = sound.samples.toMutableList()
    val slots = ArrayList<Int>()
    var next = slot
    for (sample in samples) {
        val target = if (slots.isEmpty()) slot
            else ((next + 1)..CbmSystem.SLOTS).firstOrNull { sound.instrument(it) == null } ?: break
        var index = all.indexOfFirst { it.data == sample.data }
        if (index < 0) {
            if (all.size >= CbmSound.MAX_SAMPLES) break
            all += sample
            index = all.lastIndex
        }
        val cur = sound.instrument(target)
        val ins = if (cur?.kind == CbmKind.SAMPLE) cur.copy(sample = index, loopStart = 0) else newSound(index, sample.name)
        sound = sound.copy(samples = all.toList()).withInstrument(target, ins)
        slots += target
        next = target
    }
    if (slots.isEmpty()) return slots
    apply(label) { s -> s.copy(sound = sound) }
    return slots
}

/**
 * Deletes sample [index]. Sounds playing a later sample follow it down one; sounds that played the deleted one play the
 * sample before it (the first for sample 0), their loop start kept inside it. Returns how many sample sounds played the
 * deleted sample, or -1 when there is no such sample.
 */
fun EditorSession.deleteCbmSample(index: Int): Int {
    val sound = song.cbm
    val sample = sound.samples.getOrNull(index) ?: return -1
    val samples = sound.samples.filterIndexed { i, _ -> i != index }
    var users = 0
    val instruments = sound.instruments.map { ins ->
        if (ins == null) null
        else {
            if (ins.kind == CbmKind.SAMPLE && ins.sample == index) users++
            val moved = when {
                ins.sample < index -> ins.sample
                ins.sample == index -> maxOf(0, index - 1)
                else -> ins.sample - 1
            }
            val length = samples.getOrNull(moved)?.data?.size ?: 1
            val next = ins.copy(sample = moved, loopStart = ins.loopStart.coerceIn(0, length - 1))
            if (next == ins) ins else next
        }
    }
    apply("Delete sample ${sample.name}") { s -> s.copy(sound = s.cbm.copy(instruments = instruments, samples = samples)) }
    return users
}
