package org.thortracker.system.studio

import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext

/** Open the host's WAV picker. No song changes before a validated result arrives for this slot/document. */
fun importStudioSample(session: EditorSession, menu: MenuContext, slot: Int) {
    val sound = session.song.sound as? StudioSound ?: return
    val original = sound.patch(slot) as? SamplerPatch ?: return
    val generation = session.documentGeneration
    val title = if (original.assetId == null) "Import Studio WAV" else "Replace Studio WAV"
    menu.pickFileDecoded(title, listOf(".wav"), StudioWave.MAX_FILE_BYTES, StudioWave::decode) { name, _, pcm ->
        val song = session.song
        val current = song.sound as? StudioSound
        if (session.documentGeneration != generation || current == null || current.patch(slot) !== original) {
            return@pickFileDecoded menu.status("Sample not loaded: the target instrument changed")
        }
        val next = try {
            val oldId = original.assetId
            val old = oldId?.let { current.assets[it] }
            if (old == pcm) return@pickFileDecoded menu.status("Instrument $slot already uses this recording")
            val shared = oldId != null && current.patches.withIndex().any { (index, patch) ->
                index + 1 != slot && (patch as? SamplerPatch)?.assetId == oldId
            }
            // The replaced recording is not charged twice if no other sampler is using it.
            val bank = if (oldId != null && !shared) current.assets.retain(current.assets.ids - oldId) else current.assets
            val added = bank.add(pcm)
            val patch = if (old == null) original.copy(
                name = if (original.name == "Sampler") name.take(22).ifBlank { original.name } else original.name,
                assetId = added.id,
            ) else original.rescaleRecording(old.frames, pcm.frames, added.id)
            song.copy(sound = current.copy(assets = added.assets).withPatch(slot, patch)).also { candidate ->
                val problem = candidate.system.structuralErrors(candidate).firstOrNull()
                require(problem == null) { problem ?: "Invalid sampler project" }
            }
        } catch (e: Exception) {
            return@pickFileDecoded menu.error("Sample not loaded: ${e.message}")
        }
        if (session.apply(title) { next }) {
            val detail = if (pcm.channels == 2) "stereo" else "mono"
            val history = session.message?.let { " · $it" }.orEmpty()
            menu.status("Instrument $slot: $detail · ${pcm.sampleRate} Hz · ${pcm.frames} frames$history")
        }
    }
}

/** Keep stable pattern references and relative geometry when changing the recording itself. */
private fun SamplerPatch.rescaleRecording(before: Int, after: Int, id: Int): SamplerPatch {
    fun frame(value: Int): Int = ((value.toLong() * after + before / 2) / before).toInt()
    val newStart = frame(start)
    val newEnd = if (end == 0) 0 else frame(end)
    val newLoopStart = frame(loopStart)
    val newLoopEnd = if (loopEnd == 0) 0 else frame(loopEnd)
    val regionEnd = if (end == 0) after else newEnd
    require(newStart < regionEnd && newLoopStart < regionEnd && (loopEnd == 0 || newLoopStart < newLoopEnd)) {
        "Replacement is too short to keep the Play/Loop bounds"
    }
    val slices = slices.map { slice ->
        val start = frame(slice.start)
        val end = frame(slice.end)
        require(start < end) { "Replacement is too short to keep slice ${slice.id.toString(16).uppercase().padStart(2, '0')}" }
        slice.copy(start = start, end = end)
    }
    return copy(assetId = id, start = newStart, end = newEnd, loopStart = newLoopStart, loopEnd = newLoopEnd, slices = slices)
}
