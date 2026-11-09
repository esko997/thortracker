package org.thortracker.system.nes

import org.thortracker.editor.EditorSession
import org.thortracker.editor.replaced
import org.thortracker.model.NumberText
import org.thortracker.model.Song

/**
 * MMC5 PCM document edits: the song's PCM samples and instruments' note → PCM sample mappings, as undoable commands
 * (the PCM page, [NesPcmPage]). The same keys of a sound may also map DPCM samples: the two maps are independent.
 */

/** Instrument [slot]'s PCM mapping of absolute [note], or null. */
fun Song.pcmMapping(slot: Int, note: Int): PcmMapping? = nesInstrument(slot)?.pcm?.get(note)

/** Sets instrument [slot]'s PCM mapping of [note]; null removes it. */
fun EditorSession.setPcmMapping(slot: Int, note: Int, mapping: PcmMapping?, label: String): Boolean =
    editNesInstrument(slot, label) { ins ->
        val map = LinkedHashMap(ins.pcm)
        if (mapping == null) map.remove(note) else map[note] = mapping
        ins.copy(pcm = map)
    }

/** Edits instrument [slot]'s existing PCM mapping of [note]; false when the note has none. */
fun EditorSession.editPcmMapping(slot: Int, note: Int, label: String, f: (PcmMapping) -> PcmMapping): Boolean {
    val cur = song.pcmMapping(slot, note) ?: return false
    return setPcmMapping(slot, note, f(cur), label)
}

fun EditorSession.editPcmSample(index: Int, label: String, f: (PcmSample) -> PcmSample): Boolean {
    val cur = song.nes.pcmSamples.getOrNull(index) ?: return false
    return apply(label) { s -> s.copy(sound = s.nes.copy(pcmSamples = s.nes.pcmSamples.replaced(index, f(cur)))) }
}

/**
 * Deletes PCM sample [index] and every mapping that plays it; later samples move down one, and mappings naming them
 * follow. Returns how many mappings were removed, or -1 when there is no such sample.
 */
fun EditorSession.deletePcmSample(index: Int): Int {
    val nes = song.nes
    val sample = nes.pcmSamples.getOrNull(index) ?: return -1
    var removed = 0
    val instruments = nes.instruments.map { ins ->
        if (ins.pcm.isEmpty() || ins.pcm.values.none { it.sample >= index }) ins
        else {
            val map = LinkedHashMap<Int, PcmMapping>()
            for ((note, m) in ins.pcm) when {
                m.sample == index -> removed++
                m.sample > index -> map[note] = m.copy(sample = m.sample - 1)
                else -> map[note] = m
            }
            ins.copy(pcm = map)
        }
    }
    apply("Delete PCM sample ${sample.name}") { s ->
        s.copy(sound = s.nes.copy(instruments = instruments, pcmSamples = nes.pcmSamples.filterIndexed { i, _ -> i != index }))
    }
    return removed
}

/**
 * Adds PCM [sample] (an import) and maps it on instrument [slot] at [key] (or the next key up the sound does not map
 * yet) at pitch 0, as one undo step. A sample whose bytes, rate and loop start the song already holds is reused. A
 * blank sound is renamed after the sample. Returns the key mapped, or null when no key from [key] up is free.
 */
fun EditorSession.addAndMapPcmSample(slot: Int, sample: PcmSample, key: Int, label: String): Int? {
    val ins = song.nesInstrument(slot) ?: return null
    var at = key
    while (at in NesSystem.noteRange && at in ins.pcm) at++
    if (at !in NesSystem.noteRange) return null
    val all = song.nes.pcmSamples.toMutableList()
    var index = all.indexOfFirst { it.data == sample.data && it.rate == sample.rate && it.loopStart == sample.loopStart }
    if (index < 0) { all += sample; index = all.lastIndex }
    val map = LinkedHashMap(ins.pcm).also { it[at] = PcmMapping(index) }
    val renamed = if (ins.pcm.isEmpty() && ins.dpcm.isEmpty() && ins.name == defaultNesInstrument(slot).name) sample.name.ifEmpty { ins.name } else ins.name
    apply(label) { s ->
        s.copy(sound = s.nes.copy(pcmSamples = all, instruments = s.nes.instruments.replaced(slot - 1, ins.copy(name = renamed, pcm = map))))
    }
    return at
}

/**
 * Maps the shown key's sample chromatically over [below] keys under and [above] keys over [key] (pitch = the distance
 * from [key] plus the key's own pitch, within [Pcm.PITCH]), keeping keys the sound already maps: a pitched instrument
 * from one sample, as one undo step. Returns the keys added.
 */
fun EditorSession.spreadPcmMapping(slot: Int, key: Int, below: Int, above: Int): List<Int> {
    val ins = song.nesInstrument(slot) ?: return emptyList()
    val root = ins.pcm[key] ?: return emptyList()
    val map = LinkedHashMap(ins.pcm)
    val added = ArrayList<Int>()
    for (k in key - below..key + above) {
        if (k !in NesSystem.noteRange || k in map) continue
        val pitch = root.pitch + (k - key)
        if (pitch !in Pcm.PITCH) continue
        map[k] = root.copy(pitch = pitch)
        added += k
    }
    if (added.isNotEmpty()) editNesInstrument(slot, "Spread ${NesSystem.noteName(key)} chromatically") { it.copy(pcm = map) }
    return added
}

/** "C-3 → Kick (+0 st, 22.05 kHz, loop)": status text for a PCM mapping. */
fun pcmMappingText(song: Song, note: Int, m: PcmMapping): String {
    val sample = song.nes.pcmSamples.getOrNull(m.sample) ?: return "${NesSystem.noteName(note)} → missing PCM sample ${m.sample + 1}"
    return "${NesSystem.noteName(note)} → ${sample.name} (${pcmDetail(sample, m)})"
}

/** "+3 st · 26.22 kHz · loop": a PCM mapping's settings, short. */
fun pcmDetail(sample: PcmSample, m: PcmMapping): String = buildString {
    append(if (m.pitch > 0) "+${m.pitch}" else m.pitch.toString()).append(" st · ")
    append(Pcm.rateText(Pcm.playRate(sample.rate, m.pitch)))
    if (m.loop) append(" · loop") else append(" · once")
}

/** A sample's length as time at its own rate: "0.84 s". */
fun pcmSeconds(sample: PcmSample, pitch: Int = 0): String = "${NumberText.fixed(sample.data.size / Pcm.playRate(sample.rate, pitch), 2)} s"

/** How long a one-shot PCM note sounds in the pattern view. */
internal object NesPcmRows {
    /** Engine frames (ticks) a one-shot [sample] sounds at [pitch], rounded up. */
    fun ticks(sample: PcmSample, pitch: Int): Int = kotlin.math.ceil(sample.data.size / Pcm.playRate(sample.rate, pitch) * NesSystem.tickHz).toInt()

    /**
     * Rows a one-shot PCM note at [row] of pattern [pattern] sounds for, counting its own row, as [dpcmSoundingRows]
     * counts a DPCM note's (the song speed and the pattern's `Fxx` from its first row). Null for a looping mapping, a
     * note without a mapped sample or one that outlasts the pattern.
     */
    fun soundingRows(song: Song, pattern: Int, row: Int): Int? {
        val p = song.patterns.getOrNull(pattern) ?: return null
        val track = p.tracks.getOrNull(NesRules.MMC5_PCM) ?: return null
        val cell = track.getOrNull(row)?.takeIf { it.note >= 0 } ?: return null
        val slot = if (cell.instrument > 0) cell.instrument else (row - 1 downTo 0).firstNotNullOfOrNull { r -> track[r].instrument.takeIf { it > 0 } } ?: return null
        val m = song.pcmMapping(slot, cell.note)?.takeIf { !it.loop } ?: return null
        val sample = song.nes.pcmSamples.getOrNull(m.sample) ?: return null
        fun speedAfter(r: Int, speed: Int): Int {
            var s = speed
            for (t in p.tracks) t[r].let { if (it.effect == NesRules.FX_SPEED && it.param in NesRules.SPEEDS) s = it.param }
            return s
        }
        var speed = song.ticksPerRow
        for (r in 0 until row) speed = speedAfter(r, speed)
        var left = ticks(sample, m.pitch)
        var rows = 0
        var r = row
        while (left > 0 && r < p.length) {
            speed = speedAfter(r, speed)
            left -= speed
            rows++
            r++
        }
        return if (left > 0) null else rows
    }
}
