package org.thortracker.system.pce

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.inserted
import org.thortracker.editor.replaced
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.fm.FmControls

/**
 * PC Engine document edits for the instrument and wave editors: the one bank of [PceSystem.SLOTS] sounds and their
 * envelopes, the song's waves and its DDA samples, as undoable [EditorSession] commands. Deleting or inserting a wave
 * or a sample renumbers everything that names a later one (sounds, wave envelopes, pattern `Wxx`), so the song keeps
 * sounding as it did.
 */

/** Instrument [slot] (1..[PceSystem.SLOTS]), or null when empty or out of range. */
fun Song.pceInstrument(slot: Int): PceInstrument? = pce.instrument(slot)

/** The sound an empty slot gets when it is first edited: a flat full-volume [kind] sound named after the slot. */
fun defaultPceInstrument(slot: Int, kind: PceKind = PceKind.TONE): PceInstrument =
    PceInstrument(name = "Sound ${hex2(slot)}", kind = kind)

/**
 * The four envelopes of a [PceInstrument], with the value a new step starts from when the envelope is empty. Every
 * envelope may have a loop and a release point; OFF releases (instead of stopping) only when the volume envelope has
 * one, and then every envelope with a release point jumps to it. [WAVE] is a tone sound's wave per tick (indices into
 * the song's waves).
 */
enum class PceEnvelopeKind(val label: String, val fallback: Int) {
    VOLUME("Volume", 31),
    ARPEGGIO("Arpeggio", 0),
    PITCH("Pitch", 0),
    WAVE("Wave", 0);

    /** The values this envelope holds in a song with [waves] waves. */
    fun range(waves: Int): IntRange = when (this) {
        VOLUME -> PceEnvelope.VOLUME
        ARPEGGIO -> PceEnvelope.ARPEGGIO
        PITCH -> PceEnvelope.PITCH
        WAVE -> 0..maxOf(0, waves - 1)
    }

    fun of(i: PceInstrument): PceEnvelope = when (this) {
        VOLUME -> i.volume
        ARPEGGIO -> i.arpeggio
        PITCH -> i.pitch
        WAVE -> i.waves
    }

    fun set(i: PceInstrument, e: PceEnvelope): PceInstrument = when (this) {
        VOLUME -> i.copy(volume = e)
        ARPEGGIO -> i.copy(arpeggio = e)
        PITCH -> i.copy(pitch = e)
        WAVE -> i.copy(waves = e)
    }

    /**
     * [e] made legal ([PceEnvelope.problem]): values clamped into [range], at most [PceEnvelope.MAX_LENGTH] steps, the
     * loop a step index, the release 1..length − 1 and after the loop (a loop at or past the release moves before it).
     */
    fun fixed(e: PceEnvelope, range: IntRange): PceEnvelope {
        val kept = if (e.values.size > PceEnvelope.MAX_LENGTH) e.values.take(PceEnvelope.MAX_LENGTH) else e.values
        val values = if (kept.all { it in range }) kept else kept.map { it.coerceIn(range) }
        val last = values.size - 1
        val release = if (e.release in 1..last) e.release else -1
        var loop = if (e.loop in 0..last) e.loop else -1
        if (release >= 0 && loop >= release) loop = release - 1
        val out = PceEnvelope(values, loop, release)
        return if (out == e) e else out
    }
}

/** Envelope step edits, kept legal by [PceEnvelopeKind.fixed]. A step index of `size` appends. */
object PceEnvelopes {
    /** The furthest step a cursor reaches: the append position after the last step, while there is room. */
    fun lastStep(e: PceEnvelope): Int = minOf(e.values.size, PceEnvelope.MAX_LENGTH - 1)

    /** [e] with step [step] set to [value] (clamped into [range]); [step] == size appends while there is room. */
    fun withValue(kind: PceEnvelopeKind, e: PceEnvelope, step: Int, value: Int, range: IntRange): PceEnvelope {
        val v = value.coerceIn(range)
        val values = when {
            step in e.values.indices -> e.values.replaced(step, v)
            step == e.values.size && step < PceEnvelope.MAX_LENGTH -> e.values + v
            else -> return e
        }
        return kind.fixed(e.copy(values = values), range)
    }

    /** The value step [step] holds, or what an appended step starts from (the last value, else [fallback]). */
    fun valueAt(e: PceEnvelope, step: Int, fallback: Int): Int = e.values.getOrNull(step) ?: e.values.lastOrNull() ?: fallback

    /** [e] without step [step]; the loop and release points keep marking the same steps where they can. */
    fun removed(kind: PceEnvelopeKind, e: PceEnvelope, step: Int, range: IntRange): PceEnvelope {
        if (step !in e.values.indices) return e
        fun shift(point: Int) = if (point > step) point - 1 else point
        val values = e.values.toMutableList().also { it.removeAt(step) }
        return kind.fixed(PceEnvelope(values, shift(e.loop).coerceAtMost(values.size - 1), shift(e.release).coerceAtMost(values.size - 1)), range)
    }

    /** [e] cut or extended (repeating its last value, else [fallback]) to [length] steps. */
    fun withLength(kind: PceEnvelopeKind, e: PceEnvelope, length: Int, range: IntRange, fallback: Int): PceEnvelope {
        val n = length.coerceIn(0, PceEnvelope.MAX_LENGTH)
        if (n == e.values.size) return e
        val values = if (n < e.values.size) e.values.take(n) else e.values + List(n - e.values.size) { valueAt(e, e.values.size, fallback) }
        return kind.fixed(PceEnvelope(values, e.loop.coerceAtMost(n - 1), e.release), range)
    }

    /** [e] looping from [loop] (-1: holds its last value); kept before the release point. */
    fun withLoop(kind: PceEnvelopeKind, e: PceEnvelope, loop: Int, range: IntRange): PceEnvelope {
        val top = if (e.release >= 0) e.release - 1 else e.values.size - 1
        return kind.fixed(e.copy(loop = loop.coerceIn(-1, top)), range)
    }

    /**
     * [e] releasing at [release] (-1: none): a step after the first and after the loop point. Moving below the first
     * legal step removes the release; moving up from none starts at the first legal step.
     */
    fun withRelease(kind: PceEnvelopeKind, e: PceEnvelope, release: Int, range: IntRange): PceEnvelope {
        if (e.values.size < 2) return kind.fixed(e.copy(release = -1), range)
        val first = maxOf(e.loop + 1, 1)
        val r = when {
            release < first -> if (e.release < 0 && release >= 0) first else -1
            else -> release.coerceAtMost(e.values.size - 1)
        }
        return kind.fixed(e.copy(release = r), range)
    }
}

/** A copied PC Engine sound, one of its envelopes, or a wave. */
sealed interface PceClip : Clip {
    data class Sound(val instrument: PceInstrument) : PceClip
    data class Envelope(val kind: PceEnvelopeKind, val envelope: PceEnvelope, val relative: Boolean) : PceClip
    data class Wave(val samples: List<Int>) : PceClip
}

// ---- sounds ---------------------------------------------------------------------------------------------------------

/** The song's sound with the first built-in wave added when a tone sound needs a wave and the song has none. */
private fun PceSound.withWaveFor(instrument: PceInstrument?): PceSound =
    if (instrument?.kind == PceKind.TONE && waves.isEmpty()) copy(waves = listOf(PcePresets.WAVES.first().second)) else this

/** Replaces instrument [slot] ([instrument] null empties it); a tone sound in a song without waves gets the first built-in one. */
fun EditorSession.setPceInstrument(slot: Int, instrument: PceInstrument?, label: String): Boolean {
    if (slot !in 1..PceSystem.SLOTS) return false
    return apply(label) { s ->
        if (s.pce.instrument(slot) == instrument) s else s.copy(sound = s.pce.withWaveFor(instrument).withInstrument(slot, instrument))
    }
}

/** Edits instrument [slot]; an empty slot starts from [defaultPceInstrument]. */
fun EditorSession.editPceInstrument(slot: Int, label: String, f: (PceInstrument) -> PceInstrument): Boolean {
    if (slot !in 1..PceSystem.SLOTS) return false
    val cur = song.pceInstrument(slot)
    val next = f(cur ?: defaultPceInstrument(slot))
    return (cur == null || next != cur) && setPceInstrument(slot, next, label)
}

/** Edits envelope [kind] of sound [slot]; the result is made legal ([PceEnvelopeKind.fixed]) for the song's waves. */
fun EditorSession.editPceEnvelope(slot: Int, kind: PceEnvelopeKind, label: String, f: (PceEnvelope) -> PceEnvelope): Boolean {
    val range = kind.range(song.pce.waves.size)
    return editPceInstrument(slot, label) { kind.set(it, kind.fixed(f(kind.of(it)), range)) }
}

/**
 * Makes sound [slot] a [kind] sound (an empty slot becomes a new one). Its other settings are kept, so switching back
 * restores them; only the kind's own settings play.
 */
fun EditorSession.setPceKind(slot: Int, kind: PceKind): Boolean {
    val cur = song.pceInstrument(slot)
    if (cur?.kind == kind) return false
    val label = when (kind) {
        PceKind.TONE -> "Make tone sound"
        PceKind.NOISE -> "Make noise sound"
        PceKind.SAMPLE -> "Make sample sound"
    }
    return setPceInstrument(slot, cur?.copy(kind = kind) ?: defaultPceInstrument(slot, kind), label)
}

/** [instrument] with its wave and sample references kept inside [sound]'s waves and samples (pasted across songs). */
fun pceInstrumentFor(sound: PceSound, instrument: PceInstrument): PceInstrument {
    val range = PceEnvelopeKind.WAVE.range(sound.waves.size)
    return instrument.copy(
        wave = instrument.wave.coerceIn(range),
        waves = PceEnvelopeKind.WAVE.fixed(instrument.waves, range),
        sample = instrument.sample.coerceIn(0, maxOf(0, sound.samples.size - 1)),
        loopStart = instrument.loopStart.coerceIn(0, maxOf(0, (sound.samples.getOrNull(instrument.sample)?.data?.size ?: 1) - 1)),
    )
}

/**
 * Replaces sound [slot] with [preset]: a tone preset plays the song's wave equal to its own, or its wave added to the
 * song (when the song has room; else wave 0). Returns false when nothing changed.
 */
fun EditorSession.applyPcePreset(slot: Int, preset: PcePresets.Preset): Boolean {
    if (slot !in 1..PceSystem.SLOTS) return false
    return apply("Preset ${preset.name}") { s ->
        var sound = s.pce
        var instrument = preset.instrument.copy(name = preset.name.take(FmControls.NAME_MAX))
        val wave = preset.wave
        if (preset.instrument.kind == PceKind.TONE && wave != null) {
            var index = sound.waves.indexOf(wave)
            if (index < 0 && sound.waves.size < PceSound.MAX_WAVES) {
                sound = sound.copy(waves = sound.waves + listOf(wave))
                index = sound.waves.size - 1
            }
            instrument = instrument.copy(wave = maxOf(0, index))
        }
        if (sound.instrument(slot) == instrument && sound === s.pce) s
        else s.copy(sound = sound.withWaveFor(instrument).withInstrument(slot, instrument))
    }
}

fun EditorSession.copyPceInstrument(slot: Int) {
    val i = song.pceInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(PceClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyPceEnvelope(slot: Int, kind: PceEnvelopeKind) {
    val i = song.pceInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(PceClip.Envelope(kind, kind.of(i), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it (its wave and sample kept inside the song's); an envelope goes
 * into [kind] (values clamped to its range, so a shape can move between envelopes), when [kind] is not null.
 */
fun EditorSession.pastePce(slot: Int, kind: PceEnvelopeKind?): Boolean = when (val clip = clipboard) {
    is PceClip.Sound -> setPceInstrument(slot, pceInstrumentFor(song.pce, clip.instrument), "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is PceClip.Envelope -> if (kind == null) { note("Choose an envelope page to paste the ${clip.kind.label.lowercase()} envelope into"); false }
        else editPceInstrument(slot, "Paste ${kind.label.lowercase()} envelope") {
            val with = kind.set(it, kind.fixed(clip.envelope, kind.range(song.pce.waves.size)))
            if (kind == PceEnvelopeKind.PITCH && clip.kind == PceEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
        }.also { note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    else -> { note("Clipboard holds no PC Engine sound or envelope"); false }
}

// ---- waves ----------------------------------------------------------------------------------------------------------

/** A wave's [WAVE_SIZE][PceSound.WAVE_SIZE] values clamped to 0..[WAVE_MAX][PceSound.WAVE_MAX]. */
private fun clampedWave(samples: List<Int>): List<Int> =
    if (samples.all { it in 0..PceSound.WAVE_MAX }) samples else samples.map { it.coerceIn(0, PceSound.WAVE_MAX) }

/** Replaces wave [index] by [samples] (exactly [PceSound.WAVE_SIZE] values, clamped). */
fun EditorSession.setPceWave(index: Int, samples: List<Int>, label: String = "Edit wave"): Boolean {
    if (samples.size != PceSound.WAVE_SIZE || index !in song.pce.waves.indices) return false
    val clamped = clampedWave(samples)
    return apply(label) { s -> s.copy(sound = s.pce.copy(waves = s.pce.waves.replaced(index, clamped))) }
}

/** Sets one step of wave [index]. */
fun EditorSession.setPceWaveSample(index: Int, step: Int, value: Int): Boolean {
    val w = song.pce.waves.getOrNull(index) ?: return false
    if (step !in w.indices) return false
    return setPceWave(index, w.replaced(step, value.coerceIn(0, PceSound.WAVE_MAX)), "Draw wave")
}

/** Appends [samples] as a new wave; returns its index, or -1 when the song already has [PceSound.MAX_WAVES]. */
fun EditorSession.addPceWave(samples: List<Int>, label: String = "Add wave"): Int {
    if (song.pce.waves.size >= PceSound.MAX_WAVES || samples.size != PceSound.WAVE_SIZE) return -1
    val clamped = clampedWave(samples)
    apply(label) { s -> s.copy(sound = s.pce.copy(waves = s.pce.waves + listOf(clamped))) }
    return song.pce.waves.size - 1
}

/**
 * Inserts a copy of wave [index] after it; every reference to a later wave (sounds, wave envelopes, `Wxx`) moves up one
 * so nothing changes its sound. Returns the copy's index, or -1 when there is no such wave or no room.
 */
fun EditorSession.duplicatePceWave(index: Int): Int {
    val waves = song.pce.waves
    if (index !in waves.indices || waves.size >= PceSound.MAX_WAVES) return -1
    apply("Duplicate wave") { s ->
        renumberWaves(s, { if (it > index) it + 1 else it }).let { r -> r.copy(sound = r.pce.copy(waves = r.pce.waves.inserted(index + 1, waves[index]))) }
    }
    return index + 1
}

/**
 * Deletes wave [index]. References to later waves move down one; references to the deleted wave (sounds, wave
 * envelope steps, `Wxx`) move to the wave before it (wave 0 for the first). Refused (-1) when it is the song's only
 * wave and a tone sound plays it. Returns how many references named the deleted wave.
 */
fun EditorSession.deletePceWave(index: Int): Int {
    val sound = song.pce
    if (index !in sound.waves.indices) return -1
    if (sound.waves.size == 1 && sound.instruments.any { it?.kind == PceKind.TONE }) {
        note("Wave ${hex2(index)} is the song's only wave: tone sounds need one")
        return -1
    }
    val users = waveReferences(song, index)
    val target = maxOf(0, index - 1)
    apply("Delete wave ${hex2(index)}") { s ->
        renumberWaves(s, { when { it < index -> it; it == index -> target; else -> it - 1 } })
            .let { r -> r.copy(sound = r.pce.copy(waves = r.pce.waves.filterIndexed { i, _ -> i != index })) }
    }
    return users
}

/** How many sounds, wave envelope steps and `Wxx` effects of [song] name wave [index]. */
fun waveReferences(song: Song, index: Int): Int {
    var n = 0
    for (ins in song.pce.instruments) {
        if (ins == null) continue
        if (ins.kind == PceKind.TONE && ins.wave == index) n++
        n += ins.waves.values.count { it == index }
    }
    for (p in song.patterns) for (track in p.tracks) for (cell in track) {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == PceRules.FX_WAVE && cell.effectParam(c) == index) n++
    }
    return n
}

/** [song] with every wave reference (sounds' waves, wave envelope steps, `Wxx` parameters) passed through [map]. */
private fun renumberWaves(song: Song, map: (Int) -> Int): Song {
    val sound = song.pce
    val instruments = sound.instruments.map { ins ->
        ins?.let {
            val waves = it.waves.copy(values = it.waves.values.map(map))
            val next = it.copy(wave = map(it.wave), waves = waves)
            if (next == it) it else next
        }
    }
    val patterns = song.patterns.map { p ->
        val tracks = p.tracks.map { track -> track.map { cell -> renumberCell(cell, map) } }
        if (tracks == p.tracks) p else p.copy(tracks = tracks)
    }
    return song.copy(patterns = patterns, sound = sound.copy(instruments = instruments))
}

private fun renumberCell(cell: Cell, map: (Int) -> Int): Cell {
    var out = cell
    for (c in 0..cell.more.size) {
        if (cell.effectCode(c) != PceRules.FX_WAVE) continue
        val param = cell.effectParam(c)
        val mapped = if (param in 0 until 0x100) map(param).coerceIn(0, 0xFF) else param
        if (mapped != param) out = out.withEffect(c, PceRules.FX_WAVE, mapped)
    }
    return out
}

/** Makes tone sound [slot] play wave [wave]; false when the slot holds no tone sound. */
fun EditorSession.usePceWave(slot: Int, wave: Int): Boolean {
    val ins = song.pceInstrument(slot)
    if (ins?.kind != PceKind.TONE || wave !in song.pce.waves.indices) return false
    return setPceInstrument(slot, ins.copy(wave = wave), "Assign wave")
}

fun EditorSession.copyPceWave(index: Int) {
    val w = song.pce.waves.getOrNull(index) ?: run { note("The song has no waves"); return }
    hold(PceClip.Wave(w))
    note("Copied wave ${hex2(index)}")
}

fun EditorSession.pastePceWave(index: Int): Boolean {
    val clip = clipboard as? PceClip.Wave ?: run { note("Clipboard holds no wave"); return false }
    if (index !in song.pce.waves.indices) { note("The song has no waves: make one first"); return false }
    val ok = setPceWave(index, clip.samples, "Paste wave")
    note("Pasted into wave ${hex2(index)}")
    return ok
}

/** Wave shapes the Wave view's tools make: [PcePresets.WAVES] and edits of a drawn wave. */
object PceWaveShapes {
    const val SIZE = PceSound.WAVE_SIZE
    const val MAX = PceSound.WAVE_MAX

    fun invert(w: List<Int>): List<Int> = w.map { MAX - it }

    fun rotate(w: List<Int>, by: Int): List<Int> = List(w.size) { i -> w[(i - by).mod(w.size)] }

    /** Three-tap circular smoothing, rounded half up. */
    fun smooth(w: List<Int>): List<Int> = List(w.size) { i ->
        val a = w[(i - 1).mod(w.size)]
        val b = w[i]
        val c = w[(i + 1) % w.size]
        ((a + 2 * b + c + 2) / 4).coerceIn(0, MAX)
    }

    /** Index of the built-in wave [w] equals, or -1. */
    fun presetOf(w: List<Int>): Int = PcePresets.WAVES.indexOfFirst { it.second == w }
}

// ---- samples --------------------------------------------------------------------------------------------------------

fun EditorSession.editPceSample(index: Int, label: String, f: (PceSample) -> PceSample): Boolean {
    val cur = song.pce.samples.getOrNull(index) ?: return false
    return apply(label) { s -> s.copy(sound = s.pce.copy(samples = s.pce.samples.replaced(index, f(cur)))) }
}

/**
 * Adds [samples] (one import or the starter kit) as one undo step and gives each a sound: the first plays on sound
 * [slot] (a sample sound keeps its settings and changes its sample; any other or an empty slot becomes [newSound]'s
 * sound), the others on the next empty slots after it. Samples whose frames the song already holds are reused, not
 * stored twice; samples beyond [PceSound.MAX_SAMPLES] or without a free slot are left out. Returns the slots given a
 * sample, in sample order.
 */
fun EditorSession.addPceSamples(slot: Int, samples: List<PceSample>, label: String,
                                newSound: (sample: Int, name: String) -> PceInstrument): List<Int> {
    if (slot !in 1..PceSystem.SLOTS || samples.isEmpty()) return emptyList()
    var sound = song.pce
    val all = sound.samples.toMutableList()
    val slots = ArrayList<Int>()
    var next = slot
    for (sample in samples) {
        val target = if (slots.isEmpty()) slot
            else ((next + 1)..PceSystem.SLOTS).firstOrNull { sound.instrument(it) == null } ?: break
        var index = all.indexOfFirst { it.data == sample.data }
        if (index < 0) {
            if (all.size >= PceSound.MAX_SAMPLES) break
            all += sample
            index = all.lastIndex
        }
        val cur = sound.instrument(target)
        val ins = if (cur?.kind == PceKind.SAMPLE) cur.copy(sample = index, loopStart = 0) else newSound(index, sample.name)
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
 * sample before it (the first for sample 0), their loop start kept inside it. Returns how many sample sounds played
 * the deleted sample, or -1 when there is no such sample.
 */
fun EditorSession.deletePceSample(index: Int): Int {
    val sound = song.pce
    val sample = sound.samples.getOrNull(index) ?: return -1
    val samples = sound.samples.filterIndexed { i, _ -> i != index }
    var users = 0
    val instruments = sound.instruments.map { ins ->
        if (ins == null) null
        else {
            if (ins.kind == PceKind.SAMPLE && ins.sample == index) users++
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
    apply("Delete sample ${sample.name}") { s -> s.copy(sound = s.pce.copy(instruments = instruments, samples = samples)) }
    return users
}
