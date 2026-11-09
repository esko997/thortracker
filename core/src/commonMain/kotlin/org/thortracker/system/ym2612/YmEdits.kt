package org.thortracker.system.ym2612

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
    data class Sound(val instrument: YmInstrument) : YmClip
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

fun EditorSession.copyYmInstrument(slot: Int) {
    val i = song.ymInstrument(slot) ?: run { note("Sound ${hex2(slot)} is empty"); return }
    hold(YmClip.Sound(i))
    note("Copied sound ${hex2(slot)} ${i.name}")
}

fun EditorSession.copyYmEnvelope(slot: Int, kind: NesEnvelopeKind) {
    val i = song.ymInstrument(slot) as? YmPsgInstrument ?: return
    hold(YmClip.Envelope(kind, i.envelope(kind), i.pitchRelative))
    note("Copied the ${kind.label.lowercase()} envelope of sound ${hex2(slot)}")
}

/**
 * Pastes the clipboard into [slot]: a sound replaces it; an envelope goes into PSG envelope [kind] (values clamped to
 * its range, the release kept only on volume), so a shape can move between envelopes; null [kind] (an FM page) takes
 * sounds only.
 */
fun EditorSession.pasteYm(slot: Int, kind: NesEnvelopeKind?): Boolean = when (val clip = clipboard) {
    is YmClip.Sound -> if (clip.instrument is YmPcmInstrument && clip.instrument.pcm.values.any { it.sample !in song.ym.pcmSamples.indices }) {
        note("That PCM kit maps samples this song does not hold; copy kits within one song"); false
    } else setYmInstrument(slot, clip.instrument, "Paste sound").also { note("Pasted into sound ${hex2(slot)}") }
    is YmClip.Envelope -> if (kind == null || song.ymInstrument(slot) !is YmPsgInstrument) {
        note("An envelope pastes into a PSG sound's envelope page"); false
    } else editYmPsg(slot, "Paste ${kind.label.lowercase()} envelope") {
        val with = it.withEnvelope(kind, kind.fixed(clip.envelope))
        if (kind == NesEnvelopeKind.PITCH && clip.kind == NesEnvelopeKind.PITCH) with.copy(pitchRelative = clip.relative) else with
    }.also { note("Pasted the ${clip.kind.label.lowercase()} envelope into ${kind.label.lowercase()}") }
    else -> { note("Clipboard holds no Mega Drive sound or envelope"); false }
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

/** "C-3 → Kick (+0 st, 22.05 kHz)": status text for a PCM mapping. */
fun ymPcmMappingText(song: Song, note: Int, m: YmPcmMapping): String {
    val sample = song.ym.pcmSamples.getOrNull(m.sample) ?: return "${YmSystem.noteName(note)} → missing PCM sample ${m.sample + 1}"
    return "${YmSystem.noteName(note)} → ${sample.name} (${if (m.pitch > 0) "+" else ""}${m.pitch} st, " +
        "${YmPcm.rateText(YmPcm.playRate(sample.rate, m.pitch))}, ${YmPcm.seconds(sample, m.pitch)})"
}
