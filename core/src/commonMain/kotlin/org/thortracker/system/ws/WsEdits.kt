package org.thortracker.system.ws

import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.inserted
import org.thortracker.editor.replaced
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.fm.FmControls

/**
 * WonderSwan document edits for the instrument and wave editors: the one bank of [WsRules.SLOTS] sounds and their
 * envelopes, the song's waves and its samples, as undoable [EditorSession] commands. Deleting or inserting a wave or
 * deleting a sample renumbers everything that names a later one (sounds, wave sequences, pattern `Wxx`), so the song
 * keeps sounding as it did.
 */

/** Instrument [slot] (1..[WsRules.SLOTS]), or null when empty or out of range. */
fun Song.wsInstrument(slot: Int): WsInstrument? = ws.instrument(slot)

/** The sound an empty slot gets when it is first edited: a flat full-volume [kind] sound named after the slot. */
fun defaultWsInstrument(slot: Int, kind: WsKind = WsKind.TONE): WsInstrument =
    WsInstrument(name = "Sound ${hex2(slot)}", kind = kind)

/**
 * The envelopes of a [WsInstrument], with the value a new step starts from when the envelope is empty. Every envelope
 * may have a loop and a release point; OFF releases (instead of stopping) only when the volume envelope has one, and
 * then every envelope with a release point jumps to it. [WAVE] is a tone's wave per tick (indices into the song's
 * waves), [TAP] a noise sound's tap mode per tick; both are lowered into the record's fourth envelope.
 */
enum class WsEnvelopeKind(val label: String, val fallback: Int) {
    VOLUME("Volume", 15),
    ARPEGGIO("Arpeggio", 0),
    PITCH("Pitch", 0),
    WAVE("Wave", 0),
    TAP("Tap", 0);

    /** The values this envelope holds in a song with [waves] waves. */
    fun range(waves: Int): IntRange = when (this) {
        VOLUME -> WsEnvelope.VOLUME
        ARPEGGIO -> WsEnvelope.ARPEGGIO
        PITCH -> WsEnvelope.PITCH
        WAVE -> 0..maxOf(0, waves - 1)
        TAP -> WsEnvelope.TAPS
    }

    fun of(i: WsInstrument): WsEnvelope = when (this) {
        VOLUME -> i.volume
        ARPEGGIO -> i.arpeggio
        PITCH -> i.pitch
        WAVE -> i.waves
        TAP -> i.taps
    }

    fun set(i: WsInstrument, e: WsEnvelope): WsInstrument = when (this) {
        VOLUME -> i.copy(volume = e)
        ARPEGGIO -> i.copy(arpeggio = e)
        PITCH -> i.copy(pitch = e)
        WAVE -> i.copy(waves = e)
        TAP -> i.copy(taps = e)
    }

    /**
     * [e] made legal ([WsEnvelope.problem]): values clamped into [range], at most [WsEnvelope.MAX_LENGTH] steps, the
     * loop a step index, the release 1..length − 1 and after the loop (a loop at or past the release moves before it).
     */
    fun fixed(e: WsEnvelope, range: IntRange): WsEnvelope {
        val kept = if (e.values.size > WsEnvelope.MAX_LENGTH) e.values.take(WsEnvelope.MAX_LENGTH) else e.values
        val values = if (kept.all { it in range }) kept else kept.map { it.coerceIn(range) }
        val last = values.size - 1
        val release = if (e.release in 1..last) e.release else -1
        var loop = if (e.loop in 0..last) e.loop else -1
        if (release >= 0 && loop >= release) loop = release - 1
        val out = WsEnvelope(values, loop, release)
        return if (out == e) e else out
    }
}

/** Envelope step edits, kept legal by [WsEnvelopeKind.fixed]. A step index of `size` appends. */
object WsEnvelopes {
    /** The furthest step a cursor reaches: the append position after the last step, while there is room. */
    fun lastStep(e: WsEnvelope): Int = minOf(e.values.size, WsEnvelope.MAX_LENGTH - 1)

    /** [e] with step [step] set to [value] (clamped into [range]); [step] == size appends while there is room. */
    fun withValue(kind: WsEnvelopeKind, e: WsEnvelope, step: Int, value: Int, range: IntRange): WsEnvelope {
        val v = value.coerceIn(range)
        val values = when {
            step in e.values.indices -> e.values.replaced(step, v)
            step == e.values.size && step < WsEnvelope.MAX_LENGTH -> e.values + v
            else -> return e
        }
        return kind.fixed(e.copy(values = values), range)
    }

    /** The value step [step] holds, or what an appended step starts from (the last value, else [fallback]). */
    fun valueAt(e: WsEnvelope, step: Int, fallback: Int): Int = e.values.getOrNull(step) ?: e.values.lastOrNull() ?: fallback

    /** [e] without step [step]; the loop and release points keep marking the same steps where they can. */
    fun removed(kind: WsEnvelopeKind, e: WsEnvelope, step: Int, range: IntRange): WsEnvelope {
        if (step !in e.values.indices) return e
        fun shift(point: Int) = if (point > step) point - 1 else point
        val values = e.values.toMutableList().also { it.removeAt(step) }
        return kind.fixed(WsEnvelope(values, shift(e.loop).coerceAtMost(values.size - 1), shift(e.release).coerceAtMost(values.size - 1)), range)
    }

    /** [e] cut or extended (repeating its last value, else [fallback]) to [length] steps. */
    fun withLength(kind: WsEnvelopeKind, e: WsEnvelope, length: Int, range: IntRange, fallback: Int): WsEnvelope {
        val n = length.coerceIn(0, WsEnvelope.MAX_LENGTH)
        if (n == e.values.size) return e
        val values = if (n < e.values.size) e.values.take(n) else e.values + List(n - e.values.size) { valueAt(e, e.values.size, fallback) }
        return kind.fixed(WsEnvelope(values, e.loop.coerceAtMost(n - 1), e.release), range)
    }

    /** [e] looping from [loop] (-1: holds its last value); kept before the release point. */
    fun withLoop(kind: WsEnvelopeKind, e: WsEnvelope, loop: Int, range: IntRange): WsEnvelope {
        val top = if (e.release >= 0) e.release - 1 else e.values.size - 1
        return kind.fixed(e.copy(loop = loop.coerceIn(-1, top)), range)
    }

    /**
     * [e] releasing at [release] (-1: none): a step after the first and after the loop point. Moving below the first
     * legal step removes the release; moving up from none starts at the first legal step.
     */
    fun withRelease(kind: WsEnvelopeKind, e: WsEnvelope, release: Int, range: IntRange): WsEnvelope {
        if (e.values.size < 2) return kind.fixed(e.copy(release = -1), range)
        val first = maxOf(e.loop + 1, 1)
        val r = when {
            release < first -> if (e.release < 0 && release >= 0) first else -1
            else -> release.coerceAtMost(e.values.size - 1)
        }
        return kind.fixed(e.copy(release = r), range)
    }
}

/** A copied WonderSwan sound, one of its envelopes, or a wave. */
sealed interface WsClip : Clip {
    data class Sound(val instrument: WsInstrument) : WsClip
    data class Envelope(val kind: WsEnvelopeKind, val envelope: WsEnvelope, val relative: Boolean) : WsClip
    data class Wave(val samples: List<Int>) : WsClip
}

// ---- sounds ---------------------------------------------------------------------------------------------------------

/** The song's sound with the first built-in wave added when a tone sound needs a wave and the song has none. */
private fun WsSound.withWaveFor(instrument: WsInstrument?): WsSound =
    if (instrument?.kind == WsKind.TONE && waves.isEmpty()) copy(waves = listOf(WsPresets.WAVES.first().second)) else this

/** Replaces instrument [slot] ([instrument] null empties it); a tone sound in a song without waves gets the first built-in one. */
fun EditorSession.setWsInstrument(slot: Int, instrument: WsInstrument?, label: String): Boolean {
    if (slot !in 1..WsRules.SLOTS) return false
    return apply(label) { s ->
        if (s.ws.instrument(slot) == instrument) s else s.copy(sound = s.ws.withWaveFor(instrument).withInstrument(slot, instrument))
    }
}

/** Edits instrument [slot]; an empty slot starts from [defaultWsInstrument]. */
fun EditorSession.editWsInstrument(slot: Int, label: String, f: (WsInstrument) -> WsInstrument): Boolean {
    if (slot !in 1..WsRules.SLOTS) return false
    val cur = song.wsInstrument(slot)
    val next = f(cur ?: defaultWsInstrument(slot))
    return (cur == null || next != cur) && setWsInstrument(slot, next, label)
}

/** Edits envelope [kind] of sound [slot]; the result is made legal ([WsEnvelopeKind.fixed]) for the song's waves. */
fun EditorSession.editWsEnvelope(slot: Int, kind: WsEnvelopeKind, label: String, f: (WsEnvelope) -> WsEnvelope): Boolean {
    val range = kind.range(song.ws.waves.size)
    return editWsInstrument(slot, label) { kind.set(it, kind.fixed(f(kind.of(it)), range)) }
}

/**
 * Makes sound [slot] a [kind] sound (an empty slot becomes a new one). Its other settings are kept, so switching back
 * restores them; only the kind's own settings play.
 */
fun EditorSession.setWsKind(slot: Int, kind: WsKind): Boolean {
    val cur = song.wsInstrument(slot)
    if (cur?.kind == kind) return false
    val label = when (kind) {
        WsKind.TONE -> "Make tone sound"
        WsKind.NOISE -> "Make noise sound"
        WsKind.SAMPLE -> "Make sample sound"
    }
    return setWsInstrument(slot, cur?.copy(kind = kind) ?: defaultWsInstrument(slot, kind), label)
}

/**
 * Makes sample sound [slot] fed by [feed] (an empty slot becomes a sample sound). Refused (false) for Sound DMA on the
 * original WonderSwan. Root, loop start, rate and backwards are all kept; each feed plays its own.
 */
fun EditorSession.setWsFeed(slot: Int, feed: WsFeed): Boolean {
    if (feed == WsFeed.DMA && !song.ws.color) return false
    val cur = song.wsInstrument(slot)
    if (cur?.kind == WsKind.SAMPLE && cur.feed == feed) return false
    val base = cur ?: defaultWsInstrument(slot, WsKind.SAMPLE)
    return setWsInstrument(slot, base.copy(kind = WsKind.SAMPLE, feed = feed),
        if (feed == WsFeed.DMA) "Feed by Sound DMA" else "Feed by the CPU")
}

/**
 * [instrument] with its wave and sample references kept inside [sound]'s waves and samples, and fed by the CPU on the
 * original model (pasted across songs).
 */
fun wsInstrumentFor(sound: WsSound, instrument: WsInstrument): WsInstrument {
    val range = WsEnvelopeKind.WAVE.range(sound.waves.size)
    val sample = instrument.sample.coerceIn(0, maxOf(0, sound.samples.size - 1))
    return instrument.copy(
        wave = instrument.wave.coerceIn(range),
        waves = WsEnvelopeKind.WAVE.fixed(instrument.waves, range),
        sample = sample,
        feed = if (sound.color) instrument.feed else WsFeed.CPU,
        loopStart = instrument.loopStart.coerceIn(0, maxOf(0, (sound.samples.getOrNull(sample)?.frames ?: 1) - 1)),
    )
}

/**
 * Replaces sound [slot] with [preset]: a tone preset plays the song's wave equal to its own, or its wave added to the
 * song (when the song has room; else wave 0); a sample preset plays the song's sample with its bytes, or its sample
 * added to the song (when there is room; else it is refused). Returns false when nothing changed.
 */
fun EditorSession.applyWsPreset(slot: Int, preset: WsPresets.Preset): Boolean {
    if (slot !in 1..WsRules.SLOTS) return false
    if (preset.instrument.isDma && !song.ws.color) return false
    return apply("Preset ${preset.name}") { s ->
        var sound = s.ws
        var instrument = preset.instrument.copy(name = preset.name.take(FmControls.NAME_MAX))
        val wave = preset.wave
        if (preset.instrument.kind == WsKind.TONE && wave != null) {
            var index = sound.waves.indexOf(wave)
            if (index < 0 && sound.waves.size < WsSound.MAX_WAVES) {
                sound = sound.copy(waves = sound.waves + listOf(wave))
                index = sound.waves.size - 1
            }
            instrument = instrument.copy(wave = maxOf(0, index))
        }
        val sample = preset.sample
        if (sample != null) {
            var index = sound.samples.indexOfFirst { it.data == sample.data && it.stereo == sample.stereo }
            if (index < 0) {
                if (sound.samples.size >= WsSound.MAX_SAMPLES) return@apply s
                sound = sound.copy(samples = sound.samples + sample)
                index = sound.samples.size - 1
            }
            instrument = instrument.copy(sample = index)
        }
        if (sound.instrument(slot) == instrument && sound === s.ws) s
        else s.copy(sound = sound.withWaveFor(instrument).withInstrument(slot, instrument))
    }
}

fun EditorSession.copyWsInstrument(slot: Int) {
    val i = song.wsInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(WsClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyWsEnvelope(slot: Int, kind: WsEnvelopeKind) {
    val i = song.wsInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(WsClip.Envelope(kind, kind.of(i), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it (its wave and sample kept inside the song's); an envelope goes
 * into [kind] (values clamped to its range, so a shape can move between envelopes), when [kind] is not null.
 */
fun EditorSession.pasteWs(slot: Int, kind: WsEnvelopeKind?): Boolean = when (val clip = clipboard) {
    is WsClip.Sound -> setWsInstrument(slot, wsInstrumentFor(song.ws, clip.instrument), "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is WsClip.Envelope -> if (kind == null) { note("Choose an envelope page to paste the ${clip.kind.label.lowercase()} envelope into"); false }
        else editWsInstrument(slot, "Paste ${kind.label.lowercase()} envelope") {
            val with = kind.set(it, kind.fixed(clip.envelope, kind.range(song.ws.waves.size)))
            if (kind == WsEnvelopeKind.PITCH && clip.kind == WsEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
        }.also { note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    else -> { note("Clipboard holds no WonderSwan sound or envelope"); false }
}

// ---- waves ----------------------------------------------------------------------------------------------------------

/** A wave's [WAVE_SIZE][WsSound.WAVE_SIZE] values clamped to 0..[WAVE_MAX][WsSound.WAVE_MAX]. */
private fun clampedWave(samples: List<Int>): List<Int> =
    if (samples.all { it in 0..WsSound.WAVE_MAX }) samples else samples.map { it.coerceIn(0, WsSound.WAVE_MAX) }

/** Replaces wave [index] by [samples] (exactly [WsSound.WAVE_SIZE] values, clamped). */
fun EditorSession.setWsWave(index: Int, samples: List<Int>, label: String = "Edit wave"): Boolean {
    if (samples.size != WsSound.WAVE_SIZE || index !in song.ws.waves.indices) return false
    val clamped = clampedWave(samples)
    return apply(label) { s -> s.copy(sound = s.ws.copy(waves = s.ws.waves.replaced(index, clamped))) }
}

/** Sets one step of wave [index]. */
fun EditorSession.setWsWaveSample(index: Int, step: Int, value: Int): Boolean {
    val w = song.ws.waves.getOrNull(index) ?: return false
    if (step !in w.indices) return false
    return setWsWave(index, w.replaced(step, value.coerceIn(0, WsSound.WAVE_MAX)), "Draw wave")
}

/** Appends [samples] as a new wave; returns its index, or -1 when the song already has [WsSound.MAX_WAVES]. */
fun EditorSession.addWsWave(samples: List<Int>, label: String = "Add wave"): Int {
    if (song.ws.waves.size >= WsSound.MAX_WAVES || samples.size != WsSound.WAVE_SIZE) return -1
    val clamped = clampedWave(samples)
    apply(label) { s -> s.copy(sound = s.ws.copy(waves = s.ws.waves + listOf(clamped))) }
    return song.ws.waves.size - 1
}

/**
 * Inserts a copy of wave [index] after it; every reference to a later wave (sounds, wave sequences, `Wxx`) moves up one
 * so nothing changes its sound. Returns the copy's index, or -1 when there is no such wave or no room.
 */
fun EditorSession.duplicateWsWave(index: Int): Int {
    val waves = song.ws.waves
    if (index !in waves.indices || waves.size >= WsSound.MAX_WAVES) return -1
    apply("Duplicate wave") { s ->
        renumberWaves(s) { if (it > index) it + 1 else it }.let { r -> r.copy(sound = r.ws.copy(waves = r.ws.waves.inserted(index + 1, waves[index]))) }
    }
    return index + 1
}

/**
 * Deletes wave [index]. References to later waves move down one; references to the deleted wave (sounds, wave sequence
 * steps, `Wxx`) move to the wave before it (wave 0 for the first). Refused (-1) when it is the song's only wave and a
 * tone sound plays it. Returns how many references named the deleted wave.
 */
fun EditorSession.deleteWsWave(index: Int): Int {
    val sound = song.ws
    if (index !in sound.waves.indices) return -1
    if (sound.waves.size == 1 && sound.instruments.any { it?.kind == WsKind.TONE }) {
        note("Wave ${hex2(index)} is the song's only wave: tone sounds need one")
        return -1
    }
    val users = waveReferences(song, index)
    val target = maxOf(0, index - 1)
    apply("Delete wave ${hex2(index)}") { s ->
        renumberWaves(s) { when { it < index -> it; it == index -> target; else -> it - 1 } }
            .let { r -> r.copy(sound = r.ws.copy(waves = r.ws.waves.filterIndexed { i, _ -> i != index })) }
    }
    return users
}

/** How many tone sounds, wave sequence steps and `Wxx` effects of [song] name wave [index]. */
fun waveReferences(song: Song, index: Int): Int {
    var n = 0
    for (ins in song.ws.instruments) {
        if (ins == null) continue
        if (ins.kind == WsKind.TONE && ins.wave == index) n++
        n += ins.waves.values.count { it == index }
    }
    for (p in song.patterns) for (track in p.tracks) for (cell in track) {
        for (c in 0..cell.more.size) if (cell.effectCode(c) == WsRules.FX_WAVE && cell.effectParam(c) == index) n++
    }
    return n
}

/** [song] with every wave reference (sounds' waves, wave sequence steps, `Wxx` parameters) passed through [map]. */
private fun renumberWaves(song: Song, map: (Int) -> Int): Song {
    val sound = song.ws
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
        if (cell.effectCode(c) != WsRules.FX_WAVE) continue
        val param = cell.effectParam(c)
        val mapped = if (param in 0 until 0x100) map(param).coerceIn(0, 0xFF) else param
        if (mapped != param) out = out.withEffect(c, WsRules.FX_WAVE, mapped)
    }
    return out
}

/** Makes tone sound [slot] play wave [wave]; false when the slot holds no tone sound. */
fun EditorSession.useWsWave(slot: Int, wave: Int): Boolean {
    val ins = song.wsInstrument(slot)
    if (ins?.kind != WsKind.TONE || wave !in song.ws.waves.indices) return false
    return setWsInstrument(slot, ins.copy(wave = wave), "Assign wave")
}

fun EditorSession.copyWsWave(index: Int) {
    val w = song.ws.waves.getOrNull(index) ?: run { note("The song has no waves"); return }
    hold(WsClip.Wave(w))
    note("Copied wave ${hex2(index)}")
}

fun EditorSession.pasteWsWave(index: Int): Boolean {
    val clip = clipboard as? WsClip.Wave ?: run { note("Clipboard holds no wave"); return false }
    if (index !in song.ws.waves.indices) { note("The song has no waves: make one first"); return false }
    val ok = setWsWave(index, clip.samples, "Paste wave")
    note("Pasted into wave ${hex2(index)}")
    return ok
}

/** Wave shapes the Wave view's tools make: [WsPresets.WAVES] and edits of a drawn wave. */
object WsWaveShapes {
    const val SIZE = WsSound.WAVE_SIZE
    const val MAX = WsSound.WAVE_MAX

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
    fun presetOf(w: List<Int>): Int = WsPresets.WAVES.indexOfFirst { it.second == w }
}

// ---- samples --------------------------------------------------------------------------------------------------------

fun EditorSession.editWsSample(index: Int, label: String, f: (WsSample) -> WsSample): Boolean {
    val cur = song.ws.samples.getOrNull(index) ?: return false
    return apply(label) { s -> s.copy(sound = s.ws.copy(samples = s.ws.samples.replaced(index, f(cur)))) }
}

/**
 * Adds [samples] (one import or the starter kit) as one undo step and gives each a sound: the first plays on sound
 * [slot] (a sample sound keeps its settings and changes its sample; any other or an empty slot becomes [newSound]'s
 * sound), the others on the next empty slots after it. Samples whose bytes the song already holds are reused, not
 * stored twice; samples beyond [WsSound.MAX_SAMPLES] or without a free slot are left out. Returns the slots given a
 * sample, in sample order.
 */
fun EditorSession.addWsSamples(slot: Int, samples: List<WsSample>, label: String,
                               newSound: (sample: Int, name: String) -> WsInstrument): List<Int> {
    if (slot !in 1..WsRules.SLOTS || samples.isEmpty()) return emptyList()
    var sound = song.ws
    val all = sound.samples.toMutableList()
    val slots = ArrayList<Int>()
    var next = slot
    for (sample in samples) {
        val target = if (slots.isEmpty()) slot
            else ((next + 1)..WsRules.SLOTS).firstOrNull { sound.instrument(it) == null } ?: break
        var index = all.indexOfFirst { it.data == sample.data && it.stereo == sample.stereo }
        if (index < 0) {
            if (all.size >= WsSound.MAX_SAMPLES) break
            all += sample
            index = all.lastIndex
        }
        val cur = sound.instrument(target)
        val ins = if (cur?.kind == WsKind.SAMPLE) cur.copy(sample = index, loopStart = 0) else newSound(index, sample.name)
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
fun EditorSession.deleteWsSample(index: Int): Int {
    val sound = song.ws
    val sample = sound.samples.getOrNull(index) ?: return -1
    val samples = sound.samples.filterIndexed { i, _ -> i != index }
    var users = 0
    val instruments = sound.instruments.map { ins ->
        if (ins == null) null
        else {
            if (ins.kind == WsKind.SAMPLE && ins.sample == index) users++
            val moved = when {
                ins.sample < index -> ins.sample
                ins.sample == index -> maxOf(0, index - 1)
                else -> ins.sample - 1
            }
            val length = samples.getOrNull(moved)?.frames ?: 1
            val next = ins.copy(sample = moved, loopStart = ins.loopStart.coerceIn(0, maxOf(0, length - 1)))
            if (next == ins) ins else next
        }
    }
    apply("Delete sample ${sample.name}") { s -> s.copy(sound = s.ws.copy(instruments = instruments, samples = samples)) }
    return users
}
