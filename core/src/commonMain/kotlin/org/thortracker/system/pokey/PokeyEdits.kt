package org.thortracker.system.pokey

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope

/**
 * POKEY document edits for the instrument editor: the one bank of [PokeySystem.SLOTS] sounds, their envelopes and the
 * song's samples, as undoable [EditorSession] commands (one undo step each). Sounds are edited in place: no edit
 * renumbers a slot, so the pattern rows naming a sound keep naming it.
 */

/**
 * The four envelopes of a [PokeyInstrument] and the values each holds (docs/AUDIO_ENGINE.md, POKEY image layout):
 * volume 0..15, arpeggio and pitch −64..63, distortion the [PokeyDistortion] values 0..8. [fallback] is what an
 * appended step starts from on an empty envelope (what the player uses when the envelope is not used). Unlike the
 * FamiStudio rule of [org.thortracker.system.nes.NesEnvelopeKind], every POKEY envelope may have a release point, and
 * a release needs no loop (the player holds the step before it until OFF).
 */
enum class PokeyEnvelopeKind(val label: String, val range: IntRange, val fallback: Int) {
    VOLUME("Volume", PokeyInstrument.VOLUME, 15),
    ARPEGGIO("Arpeggio", NesEnvelope.ARPEGGIO, 0),
    PITCH("Pitch", NesEnvelope.PITCH, 0),
    DISTORTION("Distortion", PokeyDistortion.RANGE, PokeyDistortion.PURE);

    fun of(i: PokeyInstrument): NesEnvelope = when (this) {
        VOLUME -> i.volume
        ARPEGGIO -> i.arpeggio
        PITCH -> i.pitch
        DISTORTION -> i.distortion
    }

    fun set(i: PokeyInstrument, e: NesEnvelope): PokeyInstrument = when (this) {
        VOLUME -> i.copy(volume = e)
        ARPEGGIO -> i.copy(arpeggio = e)
        PITCH -> i.copy(pitch = e)
        DISTORTION -> i.copy(distortion = e)
    }

    /** The envelope a new sound has: full volume, the others unused. */
    val default: NesEnvelope get() = if (this == VOLUME) NesEnvelope(listOf(15)) else NesEnvelope()

    /** A step value as the editor writes it: distortions by name ("A", "V"), offsets signed. */
    fun valueText(value: Int): String = when (this) {
        VOLUME -> value.toString()
        ARPEGGIO, PITCH -> if (value > 0) "+$value" else value.toString()
        DISTORTION -> PokeyDistortion.NAMES.getOrElse(value) { "?" }
    }

    /**
     * [e] made legal for this envelope: values clamped, at most [NesEnvelope.MAX_LENGTH] steps, loop −1 or < length,
     * release −1 or 1..length − 1 and after the loop (a loop at or past the release moves just before it).
     */
    fun fixed(e: NesEnvelope): NesEnvelope {
        val clipped = if (e.values.size > NesEnvelope.MAX_LENGTH) e.values.take(NesEnvelope.MAX_LENGTH) else e.values
        val values = if (clipped.all { it in range }) clipped else clipped.map { it.coerceIn(range) }
        val last = values.size - 1
        val release = if (e.release in 1..last) e.release else -1
        var loop = if (e.loop in 0..last) e.loop else -1
        if (release >= 0 && loop >= release) loop = release - 1
        val out = NesEnvelope(values, loop, release)
        return if (out == e) e else out
    }
}

/** Envelope step edits for POKEY sounds, kept legal by [PokeyEnvelopeKind.fixed]. A step index of `size` appends. */
object PokeyEnvelopes {
    /** [e] with step [step] set to [value] (clamped to the kind's range); [step] == size appends. */
    fun withValue(kind: PokeyEnvelopeKind, e: NesEnvelope, step: Int, value: Int): NesEnvelope {
        val v = value.coerceIn(kind.range)
        val values = when {
            step in e.values.indices -> e.values.replaced(step, v)
            step == e.values.size && step < NesEnvelope.MAX_LENGTH -> e.values + v
            else -> return e
        }
        return kind.fixed(e.copy(values = values))
    }

    /** The value step [step] holds, or what an appended step starts from (the last value, else the kind's fallback). */
    fun valueAt(kind: PokeyEnvelopeKind, e: NesEnvelope, step: Int): Int =
        e.values.getOrNull(step) ?: e.values.lastOrNull() ?: kind.fallback

    /** [e] without step [step]; the loop and release points keep marking the same steps where they can. */
    fun removed(kind: PokeyEnvelopeKind, e: NesEnvelope, step: Int): NesEnvelope {
        if (step !in e.values.indices) return e
        fun shift(point: Int) = if (point > step) point - 1 else point
        val values = e.values.toMutableList().also { it.removeAt(step) }
        return kind.fixed(NesEnvelope(values, shift(e.loop).coerceAtMost(values.size - 1), shift(e.release).coerceAtMost(values.size - 1)))
    }

    /** [e] cut or extended (repeating its last value) to [length] steps, 0..[NesEnvelope.MAX_LENGTH]. */
    fun withLength(kind: PokeyEnvelopeKind, e: NesEnvelope, length: Int): NesEnvelope {
        val n = length.coerceIn(0, NesEnvelope.MAX_LENGTH)
        if (n == e.values.size) return e
        val values = if (n < e.values.size) e.values.take(n) else e.values + List(n - e.values.size) { valueAt(kind, e, e.values.size) }
        return kind.fixed(NesEnvelope(values, e.loop.coerceAtMost(n - 1), e.release))
    }

    /** [e] looping from [loop] (−1: the last value holds), kept before the release point. */
    fun withLoop(kind: PokeyEnvelopeKind, e: NesEnvelope, loop: Int): NesEnvelope {
        val top = if (e.release >= 0) e.release - 1 else e.values.size - 1
        return kind.fixed(e.copy(loop = loop.coerceIn(-1, top)))
    }

    /**
     * [e] releasing at [release] (−1: none): from step 1 and after the loop, at most the last step. Moving below the
     * first legal step removes the release; moving up from none sets the first legal step.
     */
    fun withRelease(kind: PokeyEnvelopeKind, e: NesEnvelope, release: Int): NesEnvelope {
        val first = maxOf(1, e.loop + 1)
        val last = e.values.size - 1
        if (first > last) return kind.fixed(e.copy(release = -1))
        val r = when {
            release < first -> if (e.release < 0 && release >= 0) first else -1
            else -> release.coerceAtMost(last)
        }
        return kind.fixed(e.copy(release = r))
    }
}

/** Instrument [slot] (1..[PokeySystem.SLOTS]), or null when empty or out of range. */
fun Song.pokeyInstrument(slot: Int): PokeyInstrument? = pokey.instrument(slot)

/** The sound an empty slot gets when it is first edited: a full-volume pure tone of [kind], named after the slot. */
fun defaultPokeyInstrument(slot: Int, kind: PokeyKind = PokeyKind.TONE): PokeyInstrument = PokeyInstrument(name = "Sound ${hex2(slot)}", kind = kind)

/** A copied POKEY sound, or one envelope (with the pitch mode, for pitch envelopes). */
sealed interface PokeyClip : Clip {
    data class Sound(val instrument: PokeyInstrument) : PokeyClip
    data class Envelope(val kind: PokeyEnvelopeKind, val envelope: NesEnvelope, val relative: Boolean) : PokeyClip
}

/** Replaces instrument [slot] ([instrument] null empties it), in place. */
fun EditorSession.setPokeyInstrument(slot: Int, instrument: PokeyInstrument?, label: String): Boolean {
    if (slot !in 1..PokeySystem.SLOTS) return false
    return apply(label) { s -> if (s.pokey.instrument(slot) == instrument) s else s.copy(sound = s.pokey.withInstrument(slot, instrument)) }
}

/** Edits sound [slot]; an empty slot starts from [defaultPokeyInstrument]. */
fun EditorSession.editPokeyInstrument(slot: Int, label: String, f: (PokeyInstrument) -> PokeyInstrument): Boolean {
    if (slot !in 1..PokeySystem.SLOTS) return false
    val cur = song.pokeyInstrument(slot)
    val next = f(cur ?: defaultPokeyInstrument(slot))
    return (cur == null || next != cur) && setPokeyInstrument(slot, next, label)
}

/** Edits envelope [kind] of sound [slot]; the result is made legal ([PokeyEnvelopeKind.fixed]). */
fun EditorSession.editPokeyEnvelope(slot: Int, kind: PokeyEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean =
    editPokeyInstrument(slot, label) { kind.set(it, kind.fixed(f(kind.of(it)))) }

/** Renames sound [slot] (at most [POKEY_NAME_MAX] characters). */
fun EditorSession.renamePokey(slot: Int, f: (String) -> String): Boolean {
    val cur = song.pokeyInstrument(slot) ?: return false
    return setPokeyInstrument(slot, cur.copy(name = f(cur.name).take(POKEY_NAME_MAX)), "Rename sound")
}

/** Longest sound name. */
const val POKEY_NAME_MAX = 16

/**
 * Makes sound [slot] a [kind] sound. Every other setting is kept (a kind ignores what it does not use), so switching
 * back is lossless; a difference sound's offset is brought into 1..127 when the new kind is high-pass.
 */
fun EditorSession.setPokeyKind(slot: Int, kind: PokeyKind): Boolean =
    editPokeyInstrument(slot, "Make sound ${kind.label}") {
        val offset = if (kind.filtered && it.difference) it.filterOffset.coerceIn(PokeyInstrument.DIFFERENCE_OFFSET) else it.filterOffset
        it.copy(kind = kind, filterOffset = offset)
    }

/** The 1.79 MHz machine clock on or off (it plays where the kind allows: [PokeyKind.fastLanes]). */
fun EditorSession.setPokeyFast(slot: Int, fast: Boolean): Boolean =
    editPokeyInstrument(slot, if (fast) "Clock 1.79 MHz" else "Clock base") { it.copy(fast = fast) }

/** The high-pass clock channel's counter offset: −128..127, or 1..127 for a difference sound. */
fun EditorSession.setPokeyFilterOffset(slot: Int, offset: Int): Boolean =
    editPokeyInstrument(slot, "Set high-pass offset") {
        it.copy(filterOffset = offset.coerceIn(if (it.difference) PokeyInstrument.DIFFERENCE_OFFSET else PokeyInstrument.FILTER_OFFSET))
    }

/** Difference mode (the note is the two counters' rate difference); turning it on brings the offset into 1..127. */
fun EditorSession.setPokeyDifference(slot: Int, on: Boolean): Boolean =
    editPokeyInstrument(slot, if (on) "Difference on" else "Difference off") {
        it.copy(difference = on, filterOffset = if (on) it.filterOffset.coerceIn(PokeyInstrument.DIFFERENCE_OFFSET) else it.filterOffset)
    }

/** The distortion of the channels the sound takes besides its own (0..8). */
fun EditorSession.setPokeyPartnerDistortion(slot: Int, distortion: Int): Boolean =
    editPokeyInstrument(slot, "Set partner distortion") { it.copy(partnerDistortion = distortion.coerceIn(PokeyDistortion.RANGE)) }

/** The volume of the channels the sound takes besides its own (0 = silent). */
fun EditorSession.setPokeyPartnerVolume(slot: Int, volume: Int): Boolean =
    editPokeyInstrument(slot, "Set partner volume") { it.copy(partnerVolume = volume.coerceIn(PokeyInstrument.VOLUME)) }

/** Pitch envelope relative (added up each tick) or absolute (an offset from the note). */
fun EditorSession.setPokeyPitchRelative(slot: Int, relative: Boolean): Boolean =
    editPokeyInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = relative) }

/** The song sample a sample sound plays: [index] clamped to the song's samples; false when the song has none. */
fun EditorSession.setPokeySample(slot: Int, index: Int): Boolean {
    val count = song.pokey.samples.size
    if (count == 0) return false
    val i = index.coerceIn(0, count - 1)
    return editPokeyInstrument(slot, "Play sample ${song.pokey.samples[i].name}") {
        val len = song.pokey.samples[i].levels.size
        it.copy(sample = i, loop = if (it.loop >= len) -1 else it.loop)
    }
}

/** The note a sample plays at its rate on (0..107). */
fun EditorSession.setPokeyRoot(slot: Int, root: Int): Boolean =
    editPokeyInstrument(slot, "Set root note") { it.copy(root = root.coerceIn(PokeySystem.noteRange)) }

/** A sample sound's rate on its root note, in levels per second ([PokeyInstrument.RATES]). */
fun EditorSession.setPokeyRate(slot: Int, rate: Int): Boolean =
    editPokeyInstrument(slot, "Set sample rate") { it.copy(rate = rate.coerceIn(PokeyInstrument.RATES)) }

/** Where a sample sound loops from (−1: plays once), within its sample; false when the sound plays no sample. */
fun EditorSession.setPokeyLoop(slot: Int, loop: Int): Boolean {
    val ins = song.pokeyInstrument(slot) ?: return false
    val len = song.pokey.samples.getOrNull(ins.sample)?.levels?.size ?: return false
    return editPokeyInstrument(slot, if (loop < 0) "Loop off" else "Set loop start") { it.copy(loop = loop.coerceIn(-1, len - 1)) }
}

/** Renames song sample [index] (at most [PokeySample.MAX_NAME] characters). */
fun EditorSession.renamePokeySample(index: Int, f: (String) -> String): Boolean {
    val cur = song.pokey.samples.getOrNull(index) ?: return false
    return apply("Rename sample") { s -> s.copy(sound = s.pokey.copy(samples = s.pokey.samples.replaced(index, cur.copy(name = f(cur.name).take(PokeySample.MAX_NAME))))) }
}

/** The sounds (slots) of kind [PokeyKind.SAMPLE] that play sample [index]. */
fun Song.pokeySampleUsers(index: Int): List<Int> =
    pokey.instruments.withIndex().filter { (_, ins) -> ins != null && ins.kind == PokeyKind.SAMPLE && ins.sample == index }.map { it.index + 1 }

/**
 * Deletes song sample [index] when no sample sound plays it (else a note names those sounds and nothing changes).
 * Later samples move down one and every sound naming them follows, so no sound changes what it plays.
 */
fun EditorSession.deletePokeySample(index: Int): Boolean {
    val sample = song.pokey.samples.getOrNull(index) ?: return false
    val users = song.pokeySampleUsers(index)
    if (users.isNotEmpty()) {
        note("${sample.name} is played by sound${if (users.size == 1) "" else "s"} ${users.joinToString(", ") { hex2(it) }}: give them another sample first")
        return false
    }
    return apply("Delete sample ${sample.name}") { s ->
        val p = s.pokey
        s.copy(sound = p.copy(
            samples = p.samples.filterIndexed { i, _ -> i != index },
            instruments = p.instruments.map { ins ->
                when {
                    ins == null -> null
                    ins.sample > index -> ins.copy(sample = ins.sample - 1)
                    ins.sample == index -> ins.copy(sample = 0)
                    else -> ins
                }
            },
        ))
    }
}

/**
 * Adds [sample] (or finds the song's sample with the same levels) and makes sound [slot] a sample sound playing it at
 * [rate] on its root note, once through; one undo step. False (with a note) when the song already has
 * [PokeySystem.MAX_SAMPLES] samples.
 */
fun EditorSession.importPokeySample(slot: Int, sample: PokeySample, rate: Int): Boolean {
    if (slot !in 1..PokeySystem.SLOTS) return false
    val p = song.pokey
    var index = p.samples.indexOfFirst { it.levels == sample.levels }
    val samples = if (index >= 0) p.samples else {
        if (p.samples.size >= PokeySystem.MAX_SAMPLES) { note("The song has ${PokeySystem.MAX_SAMPLES} samples, the most it can hold: delete one first"); return false }
        index = p.samples.size
        p.samples + sample.copy(name = sample.name.take(PokeySample.MAX_NAME), levels = sample.levels.take(PokeySample.MAX_LENGTH))
    }
    val ins = (p.instrument(slot) ?: defaultPokeyInstrument(slot)).copy(kind = PokeyKind.SAMPLE, sample = index, rate = rate.coerceIn(PokeyInstrument.RATES), loop = -1)
    return apply("Import sample ${sample.name}") { s -> s.copy(sound = s.pokey.copy(samples = samples).withInstrument(slot, ins)) }
}

/** [PokeySamples.starterKit], made once (it is synthesised, and deterministic). */
private val starterKit: List<PokeySample> by lazy { PokeySamples.starterKit() }

/** Index of the starter kit's first sample where the song holds the whole kit in order (same levels), or −1. */
fun pokeyKitStart(song: Song): Int {
    val kit = starterKit
    val s = song.pokey.samples
    for (i in 0..s.size - kit.size) if (kit.indices.all { s[i + it].levels == kit[it].levels }) return i
    return -1
}

/**
 * Adds the starter kit as one undo step: its samples (unless the song already holds them in order) and its sounds
 * ([PokeyPresets.drumKit]): sound [slot] becomes the first when it is empty or plays no sample of the song, the others
 * go into the empty slots after [slot]. No sound that exists is replaced or moved. Returns the slots that got a kit
 * sound (empty, with a note, when nothing could be added).
 */
fun EditorSession.addPokeyKit(slot: Int): List<Int> {
    if (slot !in 1..PokeySystem.SLOTS) return emptyList()
    val p = song.pokey
    var start = pokeyKitStart(song)
    val samples = if (start >= 0) p.samples else {
        if (p.samples.size + starterKit.size > PokeySystem.MAX_SAMPLES) {
            note("The kit's ${starterKit.size} samples do not fit: the song holds at most ${PokeySystem.MAX_SAMPLES}")
            return emptyList()
        }
        start = p.samples.size
        p.samples + starterKit
    }
    val presets = PokeyPresets.drumKit(start)
    val cur = p.instrument(slot)
    val takesThis = cur == null || (cur.kind == PokeyKind.SAMPLE && cur.sample !in p.samples.indices)
    val targets = (if (takesThis) listOf(slot) else emptyList()) + (slot + 1..PokeySystem.SLOTS).filter { p.instrument(it) == null }
    val slots = targets.take(presets.size)
    if (slots.isEmpty() && samples === p.samples) { note("The kit is in the song and there is no empty slot after ${hex2(slot)} for its sounds"); return emptyList() }
    var instruments = p.instruments
    for ((i, target) in slots.withIndex()) instruments = instruments.replaced(target - 1, presets[i].instrument)
    apply("Add starter kit") { s -> s.copy(sound = s.pokey.copy(samples = samples, instruments = instruments)) }
    return slots
}

fun EditorSession.copyPokeyInstrument(slot: Int) {
    val i = song.pokeyInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(PokeyClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyPokeyEnvelope(slot: Int, kind: PokeyEnvelopeKind) {
    val i = song.pokeyInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(PokeyClip.Envelope(kind, kind.of(i), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it (in place); an envelope goes into envelope [kind] (values
 * clamped to its range, so a shape can move between envelopes; a pitch envelope brings its mode). Null [kind] (a page
 * without an envelope) takes sounds only.
 */
fun EditorSession.pastePokey(slot: Int, kind: PokeyEnvelopeKind?): Boolean = when (val clip = clipboard) {
    is PokeyClip.Sound -> setPokeyInstrument(slot, clip.instrument, "Paste sound").also { if (it) note("Pasted into sound ${hex2(slot)}") }
    is PokeyClip.Envelope -> if (kind == null) {
        note("An envelope pastes into an envelope page"); false
    } else editPokeyInstrument(slot, "Paste ${kind.label.lowercase()} envelope") {
        val with = kind.set(it, kind.fixed(clip.envelope))
        if (kind == PokeyEnvelopeKind.PITCH && clip.kind == PokeyEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
    }.also { if (it) note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    else -> { note("Clipboard holds no POKEY sound or envelope"); false }
}
