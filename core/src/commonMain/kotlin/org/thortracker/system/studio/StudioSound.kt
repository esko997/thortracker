package org.thortracker.system.studio

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.SystemSound
import org.thortracker.system.midi.MidiTrack

@Serializable
data class MonoPatch(val name: String = "Init", val waveform: Int = 0, val cutoff: Int = 60,
    val resonance: Int = 15, val envelope: Int = 45, val decay: Int = 40, val accent: Int = 50,
    val glide: Int = 15, val tune: Int = 0, val level: Int = 75) {
    init {
        require(name.length <= 22 && waveform in 0..1 && tune in -24..24) { "Invalid mono patch name, wave or tune" }
        require(cutoff in 0..100 && resonance in 0..100 && envelope in 0..100 && decay in 0..100 &&
            accent in 0..100 && glide in 0..100 && level in 0..100) { "Mono patch controls must be 0–100" }
    }
}

@Serializable enum class StudioTarget { MONO, USB_MIDI }
@Serializable data class StudioTrack(val midi: MidiTrack, val target: StudioTarget = StudioTarget.MONO)

@Serializable
data class StudioSound(val tracks: List<StudioTrack> = listOf(StudioTrack(MidiTrack(1, 0))),
    val patches: List<MonoPatch> = List(SLOTS) { MonoPatch() }, val clock: Boolean = false, val nextId: Int = 2) : SystemSound {
    init {
        require(tracks.size in 1..MAX_TRACKS && tracks.distinctBy { it.midi.id }.size == tracks.size &&
            tracks.all { it.midi.id < nextId }) { "Invalid Studio track count or identifiers" }
        require(patches.size == SLOTS) { "Studio needs $SLOTS patch slots" }
    }
    fun patch(slot: Int): MonoPatch? = patches.getOrNull(slot - 1)
    fun withPatch(slot: Int, patch: MonoPatch?): StudioSound {
        require(slot in 1..SLOTS)
        val value = patch ?: MonoPatch()
        if (value == patches[slot - 1]) return this
        return copy(patches = patches.toMutableList().also { it[slot - 1] = value })
    }
    @Transient override val system: StudioSystem = StudioSystem(this)
    companion object { const val SLOTS = 32; const val MAX_TRACKS = 8 }
}

val Song.studio: StudioSound get() = sound as StudioSound

fun Song.addStudioTrack(): Song {
    val s = studio
    if (s.tracks.size == StudioSound.MAX_TRACKS || s.nextId == Int.MAX_VALUE) return this
    val ch = (0..15).first { channel -> s.tracks.none { it.midi.channel == channel } }
    return copy(sound = s.copy(tracks = s.tracks + StudioTrack(MidiTrack(s.nextId, ch)), nextId = s.nextId + 1),
        patterns = patterns.map { it.copy(tracks = it.tracks + listOf(List(Songs.ROWS) { Cell.BLANK })) })
}

fun Song.removeStudioTrack(index: Int): Song {
    val s = studio
    if (s.tracks.size == 1 || index !in s.tracks.indices) return this
    return copy(sound = s.copy(tracks = s.tracks.filterIndexed { i, _ -> i != index }),
        patterns = patterns.map { p -> p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != index }) })
}
