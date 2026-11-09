package org.thortracker.system.ym2612

import org.thortracker.backend.YmPcmLowering
import org.thortracker.backend.YmPlayerImage
import org.thortracker.editor.Clip
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.replaced
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind

/** Mega Drive document edits for the instrument editor: the one bank of [YmSystem.SLOTS] FM, PSG and PCM sounds. */

/** Instrument [slot] (1..[YmSystem.SLOTS]), or null when empty or out of range. */
fun Song.ymInstrument(slot: Int): YmInstrument? = ym.instrument(slot)

/** The FM voice an empty slot gets when it is first edited as FM: the Lead preset, named after the slot. */
fun defaultYmFm(slot: Int): YmFmInstrument =
    ((YmPresets.named("Lead")?.instrument ?: YmPresets.ALL.first { it.instrument is YmFmInstrument }.instrument) as YmFmInstrument)
        .copy(name = "Sound ${hex2(slot)}")

/** The PSG sound an empty slot gets when it is first edited as PSG: the PSG lead preset, named after the slot. */
fun defaultYmPsg(slot: Int): YmPsgInstrument =
    ((YmPresets.named("PSG lead")?.instrument ?: YmPresets.ALL.first { it.instrument is YmPsgInstrument }.instrument) as YmPsgInstrument)
        .copy(name = "Sound ${hex2(slot)}")

/** The PCM kit an empty slot gets when it is first edited as PCM: no keys yet, full level, named after the slot. */
fun defaultYmPcm(slot: Int): YmPcmInstrument = YmPcmInstrument(name = "Kit ${hex2(slot)}")

/** A new sound for [slot] of the kind [channel] plays: FM on FM 1-6, PSG on PSG 1-3 and Noise, a PCM kit on PCM 1-3. */
fun defaultYmInstrument(slot: Int, channel: Int): YmInstrument = when {
    YmRules.isFm(channel) -> defaultYmFm(slot)
    YmRules.isPcm(channel) -> defaultYmPcm(slot)
    else -> defaultYmPsg(slot)
}

/** The envelope [kind] (volume, arpeggio or pitch) of a PSG sound. */
fun YmPsgInstrument.envelope(kind: NesEnvelopeKind): NesEnvelope = when (kind) {
    NesEnvelopeKind.ARPEGGIO -> arpeggio
    NesEnvelopeKind.PITCH -> pitch
    else -> volume
}

/** This PSG sound with envelope [kind] replaced. */
fun YmPsgInstrument.withEnvelope(kind: NesEnvelopeKind, e: NesEnvelope): YmPsgInstrument = when (kind) {
    NesEnvelopeKind.ARPEGGIO -> copy(arpeggio = e)
    NesEnvelopeKind.PITCH -> copy(pitch = e)
    else -> copy(volume = e)
}

/** A copied Mega Drive sound, or one PSG envelope. */
sealed interface YmClip : Clip {
    /**
     * A copied sound. A PCM kit carries the sources its keys play in [samples], its mappings renumbered to index them,
     * so a paste binds the same recordings whatever the destination song's sample list holds then ([pasteYm]).
     */
    data class Sound(val instrument: YmInstrument, val samples: List<YmPcmSample> = emptyList()) : YmClip
    data class Envelope(val kind: NesEnvelopeKind, val envelope: NesEnvelope, val relative: Boolean) : YmClip
}

/** Replaces instrument [slot] ([instrument] null empties it). */
fun EditorSession.setYmInstrument(slot: Int, instrument: YmInstrument?, label: String): Boolean {
    if (slot !in 1..YmSystem.SLOTS) return false
    return apply(label) { s -> if (s.ym.instrument(slot) == instrument) s else s.copy(sound = s.ym.withInstrument(slot, instrument)) }
}

/** Edits FM voice [slot]; an empty slot starts from [defaultYmFm]. False (nothing changes) when the slot holds another kind. */
fun EditorSession.editYmFm(slot: Int, label: String, f: (YmFmInstrument) -> YmFmInstrument): Boolean {
    if (slot !in 1..YmSystem.SLOTS) return false
    val cur = song.ymInstrument(slot)
    if (cur != null && cur !is YmFmInstrument) return false
    val next = f((cur as YmFmInstrument?) ?: defaultYmFm(slot))
    return (cur == null || next != cur) && setYmInstrument(slot, next, label)
}

/** Edits PSG sound [slot]; an empty slot starts from [defaultYmPsg]. False when the slot holds another kind. */
fun EditorSession.editYmPsg(slot: Int, label: String, f: (YmPsgInstrument) -> YmPsgInstrument): Boolean {
    if (slot !in 1..YmSystem.SLOTS) return false
    val cur = song.ymInstrument(slot)
    if (cur != null && cur !is YmPsgInstrument) return false
    val next = f((cur as YmPsgInstrument?) ?: defaultYmPsg(slot))
    return (cur == null || next != cur) && setYmInstrument(slot, next, label)
}

/** Edits PCM kit [slot]; an empty slot starts from [defaultYmPcm]. False when the slot holds another kind. */
fun EditorSession.editYmPcm(slot: Int, label: String, f: (YmPcmInstrument) -> YmPcmInstrument): Boolean {
    if (slot !in 1..YmSystem.SLOTS) return false
    val cur = song.ymInstrument(slot)
    if (cur != null && cur !is YmPcmInstrument) return false
    val next = f((cur as YmPcmInstrument?) ?: defaultYmPcm(slot))
    return (cur == null || next != cur) && setYmInstrument(slot, next, label)
}

/** Edits envelope [kind] of PSG sound [slot]; the result is made legal ([NesEnvelopeKind.fixed]). */
fun EditorSession.editYmEnvelope(slot: Int, kind: NesEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope): Boolean =
    editYmPsg(slot, label) { it.withEnvelope(kind, kind.fixed(f(it.envelope(kind)))) }

/** Renames [slot]'s sound (either kind). */
fun EditorSession.renameYm(slot: Int, f: (String) -> String): Boolean {
    val cur = song.ymInstrument(slot) ?: return false
    return setYmInstrument(slot, cur.named(f(cur.name)), "Rename sound")
}

/** Copies [slot]'s sound; a PCM kit takes the sources its keys play along ([YmClip.Sound]). False when nothing was copied. */
fun EditorSession.copyYmInstrument(slot: Int): Boolean {
    val i = song.ymInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return false }
    val clip = if (i is YmPcmInstrument) song.ymPcmClip(i) ?: run {
        note("Sound ${hex2(slot)} ${i.name} maps a missing PCM sample; map its keys again before copying it"); return false
    } else YmClip.Sound(i)
    hold(clip)
    note("Copied sound ${hex2(slot)} ${i.name}")
    return true
}

/** [kit] with the sources it plays, in this song's order, its mappings renumbered to them; null when one is missing. */
private fun Song.ymPcmClip(kit: YmPcmInstrument): YmClip.Sound? {
    val pool = ym.pcmSamples
    val used = kit.pcm.values.map { it.sample }.distinct().sorted()
    if (used.any { it !in pool.indices }) return null
    val pcm = kit.pcm.mapValues { (_, m) -> used.binarySearch(m.sample).let { if (it == m.sample) m else m.copy(sample = it) } }
    return YmClip.Sound(kit.copy(pcm = pcm), used.map { pool[it] })
}

fun EditorSession.copyYmEnvelope(slot: Int, kind: NesEnvelopeKind) {
    val i = song.ymInstrument(slot) as? YmPsgInstrument ?: return
    hold(YmClip.Envelope(kind, i.envelope(kind), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it; an envelope goes into PSG envelope [kind] (values clamped to
 * its range, the release kept only on volume), so a shape can move between envelopes; null [kind] (an FM page) takes
 * sounds only. A PCM kit's sources are found in this song by their bytes and rate, the ones it lacks added (within
 * [YmPcm.MAX_SAMPLES] and [YmPcm.MEMORY]), and its keys renumbered to them, as one undo step ([pasteYmPcmKit]).
 */
fun EditorSession.pasteYm(slot: Int, kind: NesEnvelopeKind?): Boolean = when (val clip = clipboard) {
    is YmClip.Sound -> if (clip.instrument is YmPcmInstrument && clip.instrument.pcm.isNotEmpty()) {
        pasteYmPcmKit(slot, clip.instrument, clip.samples)
    } else setYmInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is YmClip.Envelope -> if (kind == null || song.ymInstrument(slot) !is YmPsgInstrument) {
        note("An envelope pastes into a PSG sound's envelope page"); false
    } else editYmPsg(slot, "Paste ${kind.label.lowercase()} envelope") {
        val with = it.withEnvelope(kind, kind.fixed(clip.envelope))
        if (kind == NesEnvelopeKind.PITCH && clip.kind == NesEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
    }.also { note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    else -> { note("Clipboard holds no Mega Drive sound or envelope"); false }
}

/**
 * Pastes copied PCM [kit] (its keys indexing [samples]) into [slot] as one undo step: each source becomes this song's
 * sample with the same name, bytes and rate, else its first with the same bytes and rate, else a new sample at the end,
 * and the keys are renumbered to them. Refused, changing nothing, when the new samples would pass [YmPcm.MAX_SAMPLES]
 * or [YmPcm.MEMORY].
 */
private fun EditorSession.pasteYmPcmKit(slot: Int, kit: YmPcmInstrument, samples: List<YmPcmSample>): Boolean {
    if (slot !in 1..YmSystem.SLOTS) return false
    if (kit.pcm.values.any { it.sample !in samples.indices }) {
        note("The copied kit's PCM samples are missing; copy the kit again"); return false
    }
    val pool = song.ym.pcmSamples
    val added = ArrayList<YmPcmSample>()
    val at = IntArray(samples.size) { i ->
        val s = samples[i]
        val same = { o: YmPcmSample -> o.data == s.data && o.rate == s.rate }
        pool.indexOf(s).takeIf { it >= 0 }
            ?: pool.indexOfFirst(same).takeIf { it >= 0 }
            ?: added.indexOfFirst(same).takeIf { it >= 0 }?.let { pool.size + it }
            ?: (pool.size + added.size).also { added += s }
    }
    val plural = if (added.size == 1) "sample" else "samples"
    if (added.isNotEmpty()) {
        val count = pool.size + added.size
        val newBytes = added.sumOf { it.data.size }
        val bytes = YmPcm.stored(song.ym) + newBytes
        val over = when {
            count > YmPcm.MAX_SAMPLES -> "$count of the ${YmPcm.MAX_SAMPLES} PCM samples a song holds"
            bytes > YmPcm.MEMORY -> "${YmPcm.kb(bytes)} of the ${YmPcm.kb(YmPcm.MEMORY)} PCM sample memory"
            else -> null
        }
        if (over != null) {
            note("Cannot paste kit ${kit.name}: it plays ${added.size} PCM $plural (${YmPcm.kb(newBytes)}) this song does not " +
                "hold, which would make $over. Delete unused PCM samples first")
            return false
        }
    }
    val pcm = kit.pcm.mapValues { (_, m) -> at[m.sample].let { if (it == m.sample) m else m.copy(sample = it) } }
    val ok = apply("Paste sound") { s ->
        s.copy(sound = s.ym.copy(pcmSamples = if (added.isEmpty()) pool else pool + added).withInstrument(slot, kit.copy(pcm = pcm)))
    }
    note("Pasted into sound ${hex2(slot)}" + if (added.isEmpty()) "" else "; added the ${added.size} PCM $plural it plays")
    return ok
}

// ---- PCM kits: samples and key mappings ----------------------------------------------------------------------------

/** PCM kit [slot]'s mapping of absolute [note], or null. */
fun Song.ymPcmMapping(slot: Int, note: Int): YmPcmMapping? = (ymInstrument(slot) as? YmPcmInstrument)?.pcm?.get(note)

/** Sets PCM kit [slot]'s mapping of [note]; null removes it. */
fun EditorSession.setYmPcmMapping(slot: Int, note: Int, mapping: YmPcmMapping?, label: String): Boolean =
    editYmPcm(slot, label) { kit ->
        val map = LinkedHashMap(kit.pcm)
        if (mapping == null) map.remove(note) else map[note] = mapping
        kit.copy(pcm = map)
    }

/** Edits PCM kit [slot]'s existing mapping of [note]; false when the key has none. */
fun EditorSession.editYmPcmMapping(slot: Int, note: Int, label: String, f: (YmPcmMapping) -> YmPcmMapping): Boolean {
    val cur = song.ymPcmMapping(slot, note) ?: return false
    return setYmPcmMapping(slot, note, f(cur), label)
}

/** Edits PCM source sample [index] (its name); false when there is no such sample. */
fun EditorSession.editYmPcmSample(index: Int, label: String, f: (YmPcmSample) -> YmPcmSample): Boolean {
    val cur = song.ym.pcmSamples.getOrNull(index) ?: return false
    val next = f(cur)
    return next != cur && apply(label) { s -> s.copy(sound = s.ym.copy(pcmSamples = s.ym.pcmSamples.replaced(index, next))) }
}

/**
 * Deletes PCM source sample [index] and every kit key that plays it; later samples move down one and keys naming
 * them follow, as one undo step. Returns how many keys were unmapped, or -1 when there is no such sample.
 */
fun EditorSession.deleteYmPcmSample(index: Int): Int {
    val ym = song.ym
    val sample = ym.pcmSamples.getOrNull(index) ?: return -1
    var removed = 0
    val instruments = ym.instruments.map { ins ->
        if (ins !is YmPcmInstrument || ins.pcm.values.none { it.sample >= index }) ins
        else {
            val map = LinkedHashMap<Int, YmPcmMapping>()
            for ((note, m) in ins.pcm) when {
                m.sample == index -> removed++
                m.sample > index -> map[note] = m.copy(sample = m.sample - 1)
                else -> map[note] = m
            }
            ins.copy(pcm = map)
        }
    }
    apply("Delete PCM sample ${sample.name}") { s ->
        s.copy(sound = s.ym.copy(instruments = instruments, pcmSamples = ym.pcmSamples.filterIndexed { i, _ -> i != index }))
    }
    return removed
}

/**
 * Adds PCM source [sample] (an import) and maps it on kit [slot] at [key] (or the next key up the kit does not map
 * yet) at pitch 0, as one undo step; an empty slot becomes a kit. A sample whose bytes and rate the song already holds
 * is reused. A blank kit is renamed after the sample. Returns the key mapped, or null when the slot holds another kind
 * or no key from [key] up is free.
 */
fun EditorSession.addAndMapYmPcmSample(slot: Int, sample: YmPcmSample, key: Int, label: String): Int? {
    val cur = song.ymInstrument(slot)
    if (cur != null && cur !is YmPcmInstrument) return null
    val kit = cur as YmPcmInstrument? ?: defaultYmPcm(slot)
    var at = key
    while (at in YmRules.NOTES && at in kit.pcm) at++
    if (at !in YmRules.NOTES) return null
    val all = song.ym.pcmSamples.toMutableList()
    var index = all.indexOfFirst { it.data == sample.data && it.rate == sample.rate }
    if (index < 0) { all += sample; index = all.lastIndex }
    val map = LinkedHashMap(kit.pcm).also { it[at] = YmPcmMapping(index) }
    val renamed = if (kit.pcm.isEmpty() && kit.name == defaultYmPcm(slot).name) sample.name.ifEmpty { kit.name } else kit.name
    apply(label) { s -> s.copy(sound = s.ym.copy(pcmSamples = all).withInstrument(slot, kit.copy(name = renamed, pcm = map))) }
    return at
}

/**
 * Maps [key]'s sample chromatically over [below] keys under and [above] keys over it (pitch = the distance from [key]
 * plus the key's own pitch, within [YmPcm.PITCH]), keeping keys the kit already maps: a pitched instrument from one
 * sample, as one undo step. Returns the keys added.
 */
fun EditorSession.spreadYmPcmMapping(slot: Int, key: Int, below: Int, above: Int): List<Int> {
    val kit = song.ymInstrument(slot) as? YmPcmInstrument ?: return emptyList()
    val root = kit.pcm[key] ?: return emptyList()
    val map = LinkedHashMap(kit.pcm)
    val added = ArrayList<Int>()
    for (k in key - below..key + above) {
        if (k !in YmRules.NOTES || k in map) continue
        val pitch = root.pitch + (k - key)
        if (pitch !in YmPcm.PITCH) continue
        map[k] = root.copy(pitch = pitch)
        added += k
    }
    if (added.isNotEmpty()) editYmPcm(slot, "Spread ${YmSystem.noteName(key)} chromatically") { it.copy(pcm = map) }
    return added
}

/** "C-3 → Kick (+0 st · 22.05 kHz · 0.84 s)": status text for a PCM mapping. */
fun ymPcmMappingText(song: Song, note: Int, m: YmPcmMapping): String {
    val sample = song.ym.pcmSamples.getOrNull(m.sample) ?: return "${YmSystem.noteName(note)} → missing PCM sample ${m.sample + 1}"
    return "${YmSystem.noteName(note)} → ${sample.name} (${ymPcmDetail(sample, m)})"
}

/** "+3 st · 26.22 kHz · 0.84 s": a PCM mapping's pitch, the rate it plays [sample] at and its one-shot length, short. */
fun ymPcmDetail(sample: YmPcmSample, m: YmPcmMapping): String =
    "${if (m.pitch > 0) "+" else ""}${m.pitch} st · ${YmPcm.rateText(YmPcm.playRate(sample.rate, m.pitch))} · ${YmPcm.seconds(sample, m.pitch)}"

/** How long a one-shot PCM note sounds in the pattern view. */
internal object YmPcmRows {
    /**
     * Ticks the driver plays [sample] mapped at [pitch] for, rounded up: its compiled length ([YmPcmLowering.length]) at
     * the full PCM rate, MCLK / [YmPlayerImage.PCM_FULL_DIVIDER], over the tick's [YmPlayerImage.MCLK_PER_LINE] × lines
     * master clocks (the clock cancels). [pitch] must be in [YmPcm.PITCH] and [sample] valid.
     */
    fun ticks(sample: YmPcmSample, pitch: Int, pal: Boolean): Int {
        val length = YmPcmLowering.length(sample.data.size, YmPcmLowering.step(sample.rate, pitch, YmPcmLowering.mclk(pal)))
        val tick = YmPlayerImage.MCLK_PER_LINE.toLong() * (if (pal) YmPlayerImage.LINES_PAL else YmPlayerImage.LINES_NTSC)
        return ((length.toLong() * YmPlayerImage.PCM_FULL_DIVIDER + tick - 1) / tick).toInt()
    }

    /**
     * Rows a PCM note at [row] of pattern [pattern] on PCM lane [channel] sounds for, counting its own row, as the player
     * runs it: from its row's `Gxx` delay for [ticks], or until the row's own `Sxx` stops it (an `Sxx` fires at or after
     * the delay, before the row ends), over the rows' speeds: the song's ticks per row, then `Fxx` on any channel from the
     * pattern's first row (earlier orders' speed changes are not followed). A note whose delay outlasts its row never
     * plays: 1. Null for a note without a mapped, valid sample or one that outlasts the pattern.
     */
    fun soundingRows(song: Song, pattern: Int, channel: Int, row: Int): Int? {
        val p = song.patterns.getOrNull(pattern) ?: return null
        val track = p.tracks.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row)?.takeIf { it.note >= 0 } ?: return null
        // A note without a sound number keeps the kit of the lane's previous note.
        val slot = if (cell.instrument > 0) cell.instrument else (row - 1 downTo 0).firstNotNullOfOrNull { r -> track[r].instrument.takeIf { it > 0 } } ?: return null
        val m = song.ymPcmMapping(slot, cell.note)?.takeIf { it.pitch in YmPcm.PITCH } ?: return null
        val sample = song.ym.pcmSamples.getOrNull(m.sample)?.takeIf { it.problem() == null } ?: return null
        fun speedAfter(r: Int, speed: Int): Int {
            var s = speed
            for (t in p.tracks) {
                val c = t[r]
                for (col in 0..c.more.size) if (c.effectCode(col) == YmRules.FX_SPEED && c.effectParam(col) > 0) s = c.effectParam(col)
            }
            return s
        }
        var speed = song.ticksPerRow
        for (r in 0..row) speed = speedAfter(r, speed)
        var delay = 0
        var cut = -1
        for (col in 0..cell.more.size) when (cell.effectCode(col)) {
            YmRules.FX_DELAY -> delay = cell.effectParam(col)
            YmRules.FX_CUT -> cut = cell.effectParam(col)
        }
        if (delay >= speed) return 1
        var left = delay + ticks(sample, m.pitch, song.ym.pal)
        if (cut in delay until speed) left = minOf(left, cut)
        var rows = 0
        var r = row
        while (true) {
            left -= speed
            rows++
            r++
            if (left <= 0) return rows
            if (r >= p.length) return null
            speed = speedAfter(r, speed)
        }
    }
}
