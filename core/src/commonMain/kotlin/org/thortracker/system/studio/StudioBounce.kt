package org.thortracker.system.studio

import org.thortracker.audio.matchesTag
import org.thortracker.audio.u16le

import org.thortracker.audio.WavExport
import org.thortracker.audio.WavOptions
import org.thortracker.audio.WavRenderer
import org.thortracker.model.Song
import org.thortracker.model.Songs

/** A print is pinned to the immutable document that was confirmed, not the current transport or selection. */
class StudioBouncePlan internal constructor(val source: Song, val order: Int, val channel: Int, val slot: Int,
    val frames: Long, val label: String) {
    val decodedBytes: Int = (frames * 8).toInt()
    val spoolBytes: Int = (44 + frames * 4).toInt()
}

object StudioBounce {
    private val init = MonoPatch()

    fun slots(song: Song): List<Int> {
        val sound = song.sound as? StudioSound ?: return emptyList()
        val referenced = BooleanArray(StudioSound.SLOTS + 1)
        // Hidden rows and parked/unplayed patterns are user data, too. Slot 1 is implicit.
        for (p in song.patterns) for (track in p.tracks) for (cell in track) {
            if (cell.instrument in 1..StudioSound.SLOTS) referenced[cell.instrument] = true
        }
        return (2..StudioSound.SLOTS).filter { !referenced[it] && sound.patch(it) == init }
    }

    private fun reserve(assets: StudioAssets, frames: Long) {
        require(frames in 1L..(StudioPcm.MAX_BYTES / 8).toLong()) { "This pattern exceeds the 16 MiB stereo sample limit; shorten the pattern or reduce ticks per row" }
        require(frames * 8 <= StudioAssets.MAX_BYTES - assets.decodedBytes) { "Not enough room in the 32 MiB Studio sample bank" }
        require(assets.ids.size < StudioAssets.MAX_ASSETS) { "All Studio sample asset IDs are in use" }
    }

    fun plan(song: Song, order: Int, channel: Int, slot: Int): StudioBouncePlan {
        val sound = song.sound as? StudioSound ?: throw IllegalArgumentException("Bounce requires a Studio project")
        val lane = sound.tracks.getOrNull(channel) ?: throw IllegalArgumentException("Select a Studio track")
        require(lane.target == StudioTarget.MONO) { "Bounce requires an internal track; USB MIDI has no local audio" }
        val patternId = song.orders.getOrNull(order) ?: throw IllegalArgumentException("Select a pattern order")
        val pattern = song.patterns.getOrNull(patternId) ?: throw IllegalArgumentException("The selected order has no pattern")
        require(pattern.length in 1..Songs.ROWS && song.ticksPerRow in 1..255) { "Invalid Studio pattern length or speed" }
        require(slot in slots(song)) { "Choose an unreferenced stock Init instrument other than slot 01" }
        val frames = pattern.length.toLong() * song.ticksPerRow * 1000L
        reserve(sound.assets, frames)
        return StudioBouncePlan(song, order, channel, slot, frames,
            "Order ${fxHex(order)} · Pattern ${fxHex(patternId)} · Track ${lane.midi.id}")
    }

    /** The caller also checks EditorSession.documentVersion (Undo/Redo may restore the very same snapshot)
     * and rejects an active gesture before committing the returned document as one undo entry. */
    fun install(song: Song, plan: StudioBouncePlan, pcm: StudioPcm): Song {
        require(song === plan.source) { "The song changed; render this bounce again" }
        require(plan.slot in slots(song)) { "The destination is no longer an unused Init instrument" }
        require(pcm.sampleRate == WavExport.RATE && pcm.channels == 2 && pcm.frames.toLong() == plan.frames) { "Bounce PCM does not match the planned stereo recording" }
        val sound = song.studio
        reserve(sound.assets, plan.frames)
        val added = sound.assets.add(pcm)
        val patch = SamplerPatch(name = "Track ${sound.tracks[plan.channel].midi.id} · Order ${fxHex(plan.order)}".take(22),
            assetId = added.id, values = mapOf(4 to 0, 7 to 100))
        return song.copy(sound = sound.withPatch(plan.slot, patch).copy(assets = added.assets))
    }
}

/** The exact one-lane offline score. Keep original patch/insert IDs, but do not transport unrelated PCM
 * or prepare unused wave families. The played prefix is chased, never written to the spool. */
fun StudioBouncePlan.lower(): ByteArray {
    val preview = source.studio.system.patternLoop(source, order)
    val state = preview.studio
    val prefix = state.previewPrefix
    val target = preview.orders.single()
    val occurrences = prefix + target
    val definitions = occurrences.distinct()
    val remap = definitions.withIndex().associate { it.value to it.index }
    val used = BooleanArray(StudioSound.SLOTS)
    // Initial slot 1 is selected by the engine even without an explicit instrument command.
    // Only retain it if an actual event can read it before the first explicit assignment.
    var inherited = 1
    for (pid in occurrences) {
        val pattern = source.patterns[pid]
        for (row in 0 until pattern.length) {
            val cell = pattern.tracks[channel][row]
            if (cell.isBlank) continue
            if (cell.instrument != 0) inherited = cell.instrument
            require(inherited in 1..StudioSound.SLOTS) { "Invalid instrument in bounced track" }
            used[inherited - 1] = true
        }
    }
    val empty = MonoPatch()
    val patches = state.patches.mapIndexed { i, p -> if (used[i]) p else empty }
    val assets = state.assets.retain(patches.mapNotNull { (it as? SamplerPatch)?.assetId }.toSet())
    val lane = state.tracks[channel].copy(sends = listOf(0, 0))
    val isolated = preview.copy(orders = listOf(remap.getValue(target)), patterns = definitions.map { id ->
        source.patterns[id].let { it.copy(tracks = listOf(it.tracks[channel])) }
    }, sound = state.copy(tracks = listOf(lane), buses = emptyList(), patches = patches, assets = assets,
        clock = false, previewPrefix = prefix.map(remap::getValue)))
    val wire = ByteArray(StudioEngine.imageSize)
    StudioEngine.lowerOnce(isolated, wire)
    return StudioEngine.withResources(wire,isolated.studio,includePcm=assets.decodedBytes != 0)
}

/** The worker owns open/run/status/close exclusively. The host removes its private spool in finally,
 * including a completed spool discarded by a concurrent cancellation or failed integrity check. */
fun StudioBouncePlan.render(renderer: WavRenderer, spool: String, cancelled: () -> Boolean, progress: (Long) -> Unit): Boolean {
    if (cancelled()) return false
    val wire = lower()
    if (cancelled()) return false
    val seconds = ((frames + WavExport.RATE - 1) / WavExport.RATE).toInt()
    val job = renderer.open(StudioEngine.id, wire, WavOptions(loops = 0, fadeSeconds = 0, maxSeconds = seconds), spool)
    try {
        var previous = 0L
        while (!cancelled()) {
            val done = job.run(WavExport.STEP_TICKS)
            val status = job.status()
            check(!status.capped && status.frames in previous..frames) { "Bounce renderer exceeded the planned pattern or stopped at its limit" }
            if (status.frames != previous) progress(status.frames)
            previous = status.frames
            if (done) {
                check(status.frames == frames && status.size == spoolBytes.toLong()) { "Incomplete bounce: expected $frames stereo frames" }
                return !cancelled()
            }
        }
        return false
    } finally { job.close() }
}

fun StudioBouncePlan.decode(bytes: ByteArray): StudioPcm {
    require(bytes.size == spoolBytes) { "Incomplete bounce WAV: expected $spoolBytes bytes" }
    require(bytes.matchesTag(12, "fmt ") && bytes.u16le(20) == 1 && bytes.u16le(34) == 16 &&
        bytes.matchesTag(36, "data")) { "Expected the renderer’s stereo PCM16 WAV" }
    return StudioWave.decode(bytes).also {
        require(it.channels == 2 && it.sampleRate == WavExport.RATE && it.frames.toLong() == frames) { "Bounce WAV is not the planned 48 kHz stereo recording" }
    }
}
