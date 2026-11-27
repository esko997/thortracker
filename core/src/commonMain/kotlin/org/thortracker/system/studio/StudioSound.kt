package org.thortracker.system.studio

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.SystemSound
import org.thortracker.system.midi.MidiTrack

@Serializable @kotlinx.serialization.SerialName("mono")
data class MonoPatch(override val name: String = "Init", val waveform: Int = 0, val cutoff: Int = 60,
    val resonance: Int = 15, val envelope: Int = 45, val decay: Int = 40, val accent: Int = 50,
    val glide: Int = 15, val tune: Int = 0, val level: Int = 75) : StudioPatch {
    override val kind get() = StudioKind.MONO
    init {
        require(name.length <= 22 && waveform in 0..1 && tune in -24..24) { "Invalid mono patch name, wave or tune" }
        require(cutoff in 0..100 && resonance in 0..100 && envelope in 0..100 && decay in 0..100 &&
            accent in 0..100 && glide in 0..100 && level in 0..100) { "Mono patch controls must be 0–100" }
    }
}

@Serializable enum class StudioTarget { MONO, USB_MIDI }
@Serializable data class StudioTrack(val midi: MidiTrack, val target: StudioTarget = StudioTarget.MONO,
    val inserts: List<StudioInsert> = emptyList(), val gain: Int = 100, val pan: Int = 0,
    val sends: List<Int> = listOf(0,0), val bindings: Map<Int,StudioBinding> = emptyMap()) {
    init { require(inserts.size<=4 && gain in 0..200 && pan in -100..100 && sends.size==2 && sends.all { it in 0..100 })
        require(bindings.keys.all { it in 14..35 } && bindings.values.all { b -> b.instance==0 || inserts.any { it.id==b.instance && it.kind==b.kind } })
    }
}

@Serializable
data class StudioSound(val tracks: List<StudioTrack> = listOf(StudioTrack(MidiTrack(1, 0))),
    val patches: List<StudioPatch> = List(SLOTS) { MonoPatch() }, val clock: Boolean = false, val nextId: Int = 2,
    val buses: List<StudioBus> = emptyList(), val nextInstance: Int = 1,
    @Transient val assets: StudioAssets = StudioAssets.EMPTY,
    /** Played pattern IDs before an editor-only pattern loop. Chased once, never looped or saved. */
    @Transient val previewPrefix: List<Int> = emptyList()) : SystemSound {
    init {
        require(tracks.size in 1..MAX_TRACKS && tracks.distinctBy { it.midi.id }.size == tracks.size &&
            tracks.all { it.midi.id < nextId }) { "Invalid Studio track count or identifiers" }
        require(patches.size == SLOTS) { "Studio needs $SLOTS patch slots" }
        val ids = (tracks.flatMap { it.inserts } + buses.flatMap { it.inserts }).map { it.id }
        require(buses.size<=2 && nextInstance>0 && ids.all { it<nextInstance } && ids.distinct().size==ids.size) { "Invalid Studio bus count or insert identities" }
    }
    fun patch(slot: Int): StudioPatch? = patches.getOrNull(slot - 1)
    fun withPatch(slot: Int, patch: StudioPatch?): StudioSound {
        require(slot in 1..SLOTS)
        val value = patch ?: MonoPatch()
        if (value == patches[slot - 1]) return this
        val updated = patches.toMutableList().also { it[slot - 1] = value }
        val old = (patches[slot-1] as? SamplerPatch)?.assetId
        return copy(patches = updated, assets = if (old!=null && updated.none { (it as? SamplerPatch)?.assetId==old })
            assets.retain(assets.ids - old) else assets)
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

fun Song.cloneStudioTrack(index:Int):Song {
    val s=studio; val source=s.tracks[index]
    require(s.tracks.size < StudioSound.MAX_TRACKS && s.nextId < Int.MAX_VALUE) { "Studio track limit reached" }
    require(source.inserts.size <= Int.MAX_VALUE - s.nextInstance) { "Studio instance IDs exhausted" }
    val ids=source.inserts.mapIndexed { i, insert -> insert.id to s.nextInstance+i }.toMap()
    val lane=source.copy(midi=source.midi.copy(id=s.nextId), inserts=source.inserts.map { it.copy(id=ids.getValue(it.id)) },
        bindings=source.bindings.mapValues { (_,b) -> if (b.instance==0) b else b.copy(instance=ids.getValue(b.instance)) })
    return copy(sound=s.copy(tracks=s.tracks+lane,nextId=s.nextId+1,nextInstance=s.nextInstance+ids.size),
        patterns=patterns.map { it.copy(tracks=it.tracks+listOf(it.tracks[index])) })
}

/** Negative indices -1/-2 address return A/B. Returns never send to other returns. */
fun Song.addStudioInsert(channel:Int,kind:StudioKind):Song {
    val s=studio
    require(s.nextInstance<Int.MAX_VALUE) { "Studio instance IDs exhausted" }
    val insert=StudioInsert(s.nextInstance,kind)
    if(channel<0) {
        val bus=s.buses[-1-channel]; require(bus.inserts.size<4) { "Return already has four inserts" }
        return copy(sound=s.copy(buses=s.buses.mapIndexed { i,b -> if(i == -1-channel) b.copy(inserts=b.inserts+insert) else b },nextInstance=s.nextInstance+1))
    }
    val lane=s.tracks[channel]; require(lane.inserts.size<4) { "Track already has four inserts" }
    return copy(sound=s.copy(tracks=s.tracks.mapIndexed { i,t -> if(i==channel) t.copy(inserts=t.inserts+insert) else t },nextInstance=s.nextInstance+1))
}
fun Song.removeStudioInsert(channel:Int,id:Int,clearReferences:Boolean=false):Song {
    val s=studio
    if(channel<0) return copy(sound=s.copy(buses=s.buses.mapIndexed { i,b ->
        if(i == -1-channel) b.copy(inserts=b.inserts.filterNot { it.id==id }) else b }))
    val lane=s.tracks[channel]; val codes=lane.bindings.filterValues { it.instance==id }.keys
    require(codes.isEmpty() || clearReferences) { "Clear bindings ${codes.joinToString { ('A'+(it-10)).toString() }} and their pattern commands before removing this insert" }
    return copy(sound=s.copy(tracks=s.tracks.mapIndexed { i,t -> if (i!=channel) t else t.copy(
        inserts=t.inserts.filterNot { it.id==id },bindings=t.bindings-codes) }),
        patterns=if(codes.isEmpty()) patterns else patterns.map { p -> p.copy(tracks=p.tracks.mapIndexed { i,track ->
            if(i!=channel) track else track.map { cell -> var c=cell; for(col in 0..cell.more.size) if(cell.effectCode(col) in codes) c=c.withEffect(col,0,0); c }
        }) })
}
