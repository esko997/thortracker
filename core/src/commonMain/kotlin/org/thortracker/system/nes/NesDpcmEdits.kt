package org.thortracker.system.nes

import org.thortracker.editor.EditorSession
import org.thortracker.editor.replaced
import org.thortracker.model.Song

/** NES DPCM document edits: the song's samples and instruments' note → sample mappings, as undoable commands. */

/** Instrument [slot]'s mapping of absolute [note], or null. */
fun Song.dpcmMapping(slot: Int, note: Int): DpcmMapping? = nesInstrument(slot)?.dpcm?.get(note)

/** Sets instrument [slot]'s mapping of [note]; null removes it. */
fun EditorSession.setDpcmMapping(slot: Int, note: Int, mapping: DpcmMapping?, label: String): Boolean =
    editNesInstrument(slot, label) { ins ->
        val map = LinkedHashMap(ins.dpcm)
        if (mapping == null) map.remove(note) else map[note] = mapping
        ins.copy(dpcm = map)
    }

/** Edits instrument [slot]'s existing mapping of [note]; false when the note has none. */
fun EditorSession.editDpcmMapping(slot: Int, note: Int, label: String, f: (DpcmMapping) -> DpcmMapping): Boolean {
    val cur = song.dpcmMapping(slot, note) ?: return false
    return setDpcmMapping(slot, note, f(cur), label)
}

/** Appends [samples] to the song as one undo step. */
fun EditorSession.addDpcmSamples(samples: List<DpcmSample>, label: String): Boolean =
    apply(label) { s -> s.copy(sound = s.nes.copy(samples = s.nes.samples + samples)) }

fun EditorSession.editDpcmSample(index: Int, label: String, f: (DpcmSample) -> DpcmSample): Boolean {
    val cur = song.nes.samples.getOrNull(index) ?: return false
    return apply(label) { s -> s.copy(sound = s.nes.copy(samples = s.nes.samples.replaced(index, f(cur)))) }
}

/**
 * Deletes sample [index] and every mapping that plays it; later samples move down one, and mappings naming them follow.
 * Returns how many mappings were removed, or -1 when there is no such sample.
 */
fun EditorSession.deleteDpcmSample(index: Int): Int {
    val nes = song.nes
    val sample = nes.samples.getOrNull(index) ?: return -1
    var removed = 0
    val instruments = nes.instruments.map { ins ->
        if (ins.dpcm.isEmpty()) ins
        else {
            val map = LinkedHashMap<Int, DpcmMapping>()
            for ((note, m) in ins.dpcm) when {
                m.sample == index -> removed++
                m.sample > index -> map[note] = m.copy(sample = m.sample - 1)
                else -> map[note] = m
            }
            if (map.size == ins.dpcm.size && ins.dpcm.values.none { it.sample > index }) ins else ins.copy(dpcm = map)
        }
    }
    apply("Delete sample ${sample.name}") { s -> s.copy(sound = s.nes.copy(instruments = instruments, samples = nes.samples.filterIndexed { i, _ -> i != index })) }
    return removed
}

/**
 * Adds DPCM [samples] (one import) and maps them onto instrument [slot] from key [from] upward: each sample takes the
 * next key the instrument does not map yet, at [pitch], as one undo step. Returns the keys mapped, in sample order.
 */
fun EditorSession.addAndMapDpcmSamples(slot: Int, samples: List<DpcmSample>, from: Int, pitch: Int, label: String): List<Int> {
    val mapped = song.nesInstrument(slot)?.dpcm ?: return emptyList()
    val layout = LinkedHashMap<Int, Pair<Int, Int>>()
    var key = from
    for (i in samples.indices) {
        while (key in NesSystem.noteRange && (key in mapped || key in layout)) key++
        if (key !in NesSystem.noteRange) break
        layout[key] = i to pitch
    }
    return addDpcmLayout(slot, samples, layout, label)
}

/**
 * Adds DPCM [samples] and maps [layout] (key → index into [samples] and pitch) onto instrument [slot] as one undo
 * step, leaving keys the instrument already maps as they are. A sample whose bytes the song already holds is reused
 * rather than stored twice; samples no key ends up playing are not added. A blank sound is renamed [name].
 * Returns the keys mapped.
 */
fun EditorSession.addDpcmLayout(slot: Int, samples: List<DpcmSample>, layout: Map<Int, Pair<Int, Int>>, label: String, name: String = "Samples"): List<Int> {
    val ins = song.nesInstrument(slot) ?: return emptyList()
    val all = song.nes.samples.toMutableList()
    val map = LinkedHashMap(ins.dpcm)
    val keys = ArrayList<Int>()
    for ((key, use) in layout) {
        if (key in map || key !in NesSystem.noteRange) continue
        val sample = samples[use.first]
        var index = all.indexOfFirst { it.data == sample.data }
        if (index < 0) { all += sample; index = all.lastIndex }
        map[key] = DpcmMapping(index, pitch = use.second)
        keys += key
    }
    if (keys.isEmpty()) return keys
    val renamed = if (ins.dpcm.isEmpty() && ins.name == defaultNesInstrument(slot).name) name else ins.name
    apply(label) { s ->
        s.copy(sound = s.nes.copy(
            samples = all,
            instruments = s.nes.instruments.replaced(slot - 1, ins.copy(name = renamed, dpcm = map)),
        ))
    }
    return keys
}

/** "Sound 05" → status text for a mapping: "C-3 → Kick (rate F, loop)". */
fun dpcmMappingText(song: Song, note: Int, m: DpcmMapping): String {
    val name = song.nes.samples.getOrNull(m.sample)?.name ?: "missing sample ${m.sample + 1}"
    return "${NesSystem.noteName(note)} → $name (${dpcmDetail(m)})"
}

/** "rate F · loop · level 64": a mapping's settings, short. */
fun dpcmDetail(m: DpcmMapping): String = buildString {
    append("rate ").append(m.pitch.toString(16).uppercase())
    if (m.loop) append(" · loop")
    if (m.dmcInitial >= 0) append(" · level ").append(m.dmcInitial * 2)
}

/** Engine frames (ticks) a one-shot sample of [bytes] sounds at DMC rate [pitch]: 8 bits per byte, rounded up. */
fun dpcmTicks(bytes: Int, pitch: Int): Int = kotlin.math.ceil(bytes * 8.0 / Dpcm.RATES_HZ[pitch] * NesSystem.tickHz).toInt()

/**
 * Rows a one-shot DPCM note at [row] of pattern [pattern] sounds for, counting its own row: its sample's ticks
 * ([dpcmTicks]) over the rows' speeds, which start at the song's ticks per row and follow `Fxx` on any channel from
 * the pattern's first row (earlier orders' speed changes are not followed). Null for a looping mapping, a note
 * without a mapped sample, or a note past the pattern's rows, which sound until the next note or OFF.
 */
fun dpcmSoundingRows(song: Song, pattern: Int, row: Int): Int? {
    val p = song.patterns.getOrNull(pattern) ?: return null
    val track = p.tracks.getOrNull(NesRules.DPCM) ?: return null
    val cell = track.getOrNull(row)?.takeIf { it.note >= 0 } ?: return null
    // A note without a sound number keeps the instrument of the channel's previous note.
    val slot = if (cell.instrument > 0) cell.instrument else (row - 1 downTo 0).firstNotNullOfOrNull { r -> track[r].instrument.takeIf { it > 0 } } ?: return null
    val m = song.dpcmMapping(slot, cell.note)?.takeIf { !it.loop } ?: return null
    val sample = song.nes.samples.getOrNull(m.sample) ?: return null
    fun speedAfter(r: Int, speed: Int): Int {
        var s = speed
        for (t in p.tracks) t[r].let { if (it.effect == NesRules.FX_SPEED && it.param in NesRules.SPEEDS) s = it.param }
        return s
    }
    var speed = song.ticksPerRow
    for (r in 0 until row) speed = speedAfter(r, speed)
    var left = dpcmTicks(sample.data.size, m.pitch)
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
