package org.thortracker.system.gba

import org.thortracker.backend.GbaExportConstraints
import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.replaced

private fun EditorSession.commitGbaSound(sound: GbaSound, label: String): Boolean {
    GbaExportConstraints.errors(sound, checkPcm = false).firstOrNull()?.let { throw IllegalArgumentException(it) }
    return apply(label) { it.copy(sound = sound) }
}

/** All sampler changes use the document history; navigation and face changes never do. */
internal fun EditorSession.editGbaInstrument(slot: Int, label: String, edit: (GbaInstrument) -> GbaInstrument): Boolean {
    val sound = song.gba
    val old = sound.instrument(slot) ?: return false
    val next = edit(old)
    if (next == old) return false
    GbaRules.instrumentProblem(next, sound.samples.size)?.let { throw IllegalArgumentException(it) }
    return commitGbaSound(sound.copy(instruments = sound.instruments.replaced(slot - 1, next)), label)
}

internal fun EditorSession.editGbaSample(index: Int, label: String, edit: (GbaSample) -> GbaSample): Boolean {
    val sound = song.gba
    val old = sound.samples.getOrNull(index) ?: return false
    val next = edit(old)
    if (next == old) return false
    if (next.pcm !== old.pcm) GbaRules.sampleProblem(next)?.let { throw IllegalArgumentException(it) }
    return commitGbaSound(sound.copy(samples = sound.samples.replaced(index, next)), label)
}

/** Adds one melodic sample, or a kit on consecutive keys; the existing instrument's envelopes are retained. */
internal fun EditorSession.addGbaSamples(slot: Int, samples: List<GbaSample>, firstKey: Int? = null): Boolean {
    val sound = song.gba
    val instrument = sound.instrument(slot) ?: return false
    require(samples.isNotEmpty()) { "No samples to add" }
    samples.forEach { sample -> GbaRules.sampleProblem(sample)?.let { throw IllegalArgumentException(it) } }
    require(sound.samples.size + samples.size <= GbaSound.MAX_SAMPLES) { "The song already has too many samples (maximum 128)." }
    require(sound.samples.sumOf { it.pcm.size.toLong() } + samples.sumOf { it.pcm.size.toLong() } <= GbaSound.MAX_PCM_BYTES) {
        "Samples exceed the 512 KiB backend budget. Trim or resample first."
    }
    require(firstKey != null || samples.size == 1) { "A kit needs a first key" }
    if (firstKey != null) require(firstKey >= 0 && firstKey + samples.size <= 96) { "The kit would extend past B7. Choose a lower first key." }
    val map = if (firstKey == null) List(96) { sound.samples.size } else instrument.keymap.toMutableList().also { keys ->
        samples.indices.forEach { keys[firstKey + it] = sound.samples.size + it }
    }
    require(map.distinct().size <= 16) { "XM allows 16 samples per instrument, including silence. Clear unused mappings first." }
    val name = if (instrument.name.isEmpty() || (instrument.name == "Empty" && instrument.keymap.all { it < 0 }))
        if (samples.size == 1) samples[0].name else "Drum kit" else instrument.name
    val next = instrument.copy(name = name, keymap = map)
    return commitGbaSound(sound.copy(samples = sound.samples + samples, instruments = sound.instruments.replaced(slot - 1, next)),
        if (samples.size == 1) "Add sample" else "Add drum kit")
}

/** Deleted samples become silent, never a different sound; all later indices follow their original data. */
internal fun EditorSession.deleteGbaSample(index: Int): Boolean {
    val sound = song.gba
    if (index !in sound.samples.indices) return false
    val instruments = sound.instruments.map { ins -> ins.copy(keymap = ins.keymap.map { key ->
        when { key == index -> -1; key > index -> key - 1; else -> key }
    }) }
    require(instruments.all { it.keymap.distinct().size <= 16 }) {
        "Deleting would need a seventeenth silent XM sample. Unmap another sample in that instrument first."
    }
    return commitGbaSound(sound.copy(samples = sound.samples.filterIndexed { i, _ -> i != index }, instruments = instruments), "Delete sample")
}

internal data class GbaInstrumentClip(val instrument: GbaInstrument, val samples: List<GbaSample>) : Clip

internal fun EditorSession.copyGbaInstrument(slot: Int) {
    val ins = song.gba.instrument(slot) ?: return
    val indices = ins.keymap.filter { it >= 0 }.distinct()
    hold(GbaInstrumentClip(ins.copy(keymap = ins.keymap.map { indices.indexOf(it) }), indices.map { song.gba.samples[it] }))
}

internal fun EditorSession.pasteGbaInstrument(slot: Int): Boolean {
    val clip = clipboard as? GbaInstrumentClip ?: return false
    val sound = song.gba
    if (sound.instrument(slot) == null) return false
    val samples = sound.samples.toMutableList()
    val indices = clip.samples.map { sample -> samples.indexOf(sample).takeIf { it >= 0 } ?: samples.size.also { samples += sample } }
    require(samples.size <= GbaSound.MAX_SAMPLES && samples.sumOf { it.pcm.size.toLong() } <= GbaSound.MAX_PCM_BYTES) {
        "Not enough sample space to paste this instrument."
    }
    val ins = clip.instrument.copy(keymap = clip.instrument.keymap.map { if (it < 0) -1 else indices[it] })
    return commitGbaSound(sound.copy(samples = samples, instruments = sound.instruments.replaced(slot - 1, ins)), "Paste sound")
}

/** Strictly increasing XM points; index markers follow their point when another point is removed. */
internal object GbaEnvelopeEdits {
    fun remove(env: GbaEnvelope, index: Int): GbaEnvelope {
        if (env.points.size <= 2 || index == 0 || index !in env.points.indices) return env
        fun moved(value: Int) = when { value == index -> -1; value > index -> value - 1; else -> value }
        val start = moved(env.loopStart)
        val end = moved(env.loopEnd)
        return env.copy(points = env.points.filterIndexed { i, _ -> i != index }, sustain = moved(env.sustain),
            loopStart = if (start < 0 || end < 0) -1 else start, loopEnd = if (start < 0 || end < 0) -1 else end)
    }

    fun append(env: GbaEnvelope): GbaEnvelope {
        if (env.points.size >= 12) return env
        val last = env.points.lastOrNull()
        val tick = if (last == null) 0 else (last.tick + 12).coerceAtMost(255)
        if (last != null && tick <= last.tick) return env
        return env.copy(points = env.points + GbaEnvelopePoint(tick, last?.value ?: 64))
    }
}
