package org.thortracker.system.pce

import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.MenuContext
import org.thortracker.model.NumberText
import org.thortracker.model.Song

/**
 * Adding DDA samples from the Sample page's Add row: the [PceStarterKit] (one sound per drum: the shown slot, then the
 * next empty slots) or a `.wav` file converted on the device by [PceSamples.fromWav] at the song's DDA rate (the CPU
 * timer, Song view or `Txx`), so its root note plays it at its own pitch. The shown sound plays the first new sample.
 */
internal object PceSampleMenu {
    /** Largest file offered for import: minutes of CD audio, far more than a sample can hold. */
    private const val MAX_FILE = 32 * 1024 * 1024

    fun open(session: EditorSession, slot: Int) = session.requestMenu { menu ->
        val kit = PceStarterKit.samples()
        val rate = PceSamples.rateHz(session.song.pce.timerReload)
        menu.menu("Add samples to sound ${hex2(slot)} (${kb(PceSamples.free(session.song))} free)", listOf(
            "Starter kit: ${kit.joinToString(", ") { it.name.lowercase() }}" to { addKit(session, menu, slot, kit) },
            "Import .wav file… (converted at $rate Hz, the song's DDA rate)" to {
                menu.pickFile("Import sample", listOf(".wav"), MAX_FILE) { name, _, bytes -> importWav(session, menu, slot, name, bytes) }
            },
        ))
    }

    /** "2.3 KB" of the player's data region (1 KB = 1024 bytes; negative: over). */
    fun kb(bytes: Int): String = "${NumberText.fixed(bytes / 1024.0, 1)} KB"

    /** "0.42 s": [frames] at the song's DDA rate (the root note's speed). */
    fun seconds(song: Song, frames: Int): String = "${NumberText.fixed(PceSamples.seconds(frames, song.pce.timerReload), 2)} s"

    private fun addKit(session: EditorSession, menu: MenuContext, slot: Int, kit: List<PceSample>) {
        val before = session.song
        val slots = session.addPceSamples(slot, kit, "Add starter kit") { sample, name -> PceStarterKit.instrument(sample, name) }
        if (slots.isEmpty()) return menu.error("No room: the song has ${PceSound.MAX_SAMPLES} samples")
        val names = slots.mapIndexed { i, s -> "${kit[i].name} ${hex2(s)}" }
        menu.status("Starter kit: ${names.joinToString(" · ")}" + (if (slots.size < kit.size) " (no free slot for the rest)" else "") +
            " · ${kb(PceSamples.free(session.song))} free")
        overflowNote(session, menu, before)
    }

    private fun importWav(session: EditorSession, menu: MenuContext, slot: Int, name: String, bytes: ByteArray) {
        val before = session.song
        val rate = PceSamples.rateHz(session.song.pce.timerReload)
        val data = try { PceSamples.fromWav(bytes, rate) } catch (e: IllegalArgumentException) { return menu.error("Not imported: ${e.message}") }
        val sample = PceSample(name.take(PceSample.MAX_NAME), data)
        val slots = session.addPceSamples(slot, listOf(sample), "Import sample ${sample.name}") { index, n -> PceStarterKit.instrument(index, n) }
        if (slots.isEmpty()) return menu.error("No room: the song has ${PceSound.MAX_SAMPLES} samples")
        menu.status("${sample.name}: ${data.size} frames (${seconds(session.song, data.size)} at $rate Hz) on sound ${hex2(slots.first())}" +
            (if (data.size >= PceSample.MAX_FRAMES) ", cut to the longest sample" else "") + " · ${kb(PceSamples.free(session.song))} free")
        overflowNote(session, menu, before)
    }

    /** Warns when the addition left the song larger than the player's data region (the song check reports it too). */
    private fun overflowNote(session: EditorSession, menu: MenuContext, before: Song) {
        val free = PceSamples.free(session.song)
        if (free < 0 && free < PceSamples.free(before)) menu.error("The song now needs ${kb(-free)} more than the player's data space. " +
            "Delete or shorten samples before playing or exporting.")
    }
}
