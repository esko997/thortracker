package org.thortracker.system.ws

import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.MenuContext
import org.thortracker.model.NumberText
import org.thortracker.model.Song

/**
 * Adding samples from the Sample page's Add row: the [WsStarterKit] (one sound per drum: the shown slot, then the next
 * empty slots, fed as the shown sound is) or a `.wav` file converted on the device by [WsSamples.fromWav] at the rate
 * the shown sound plays at — Sound DMA's rate for a DMA sound, else the song's voice timer — so it plays at its own
 * pitch (CPU-fed: on its root note). Stereo files stay stereo on the WonderSwan Color (Hyper Voice plays them) and are
 * mixed to mono on the original. The shown sound plays the first new sample.
 */
internal object WsSampleMenu {
    /** Largest file offered for import: minutes of audio, far more than a sample can hold. */
    private const val MAX_FILE = 32 * 1024 * 1024

    /** The rate a sample for [slot]'s sound is converted at (its DMA rate, or the song's voice timer). */
    fun rateFor(song: Song, slot: Int): Int {
        val ins = song.ws.instrument(slot)
        return if (ins != null && ins.isDma) WsSamples.dmaRateHz(ins.rate) else WsSamples.timerRateHz(song.ws.timerReload)
    }

    fun open(session: EditorSession, slot: Int) = session.requestMenu { menu ->
        val kit = WsStarterKit.samples()
        val song = session.song
        val rate = rateFor(song, slot)
        val dma = song.ws.instrument(slot)?.isDma == true
        menu.menu("Add samples to sound ${hex2(slot)} (${kb(WsSamples.free(song))} free)", listOf(
            "Starter kit: ${kit.joinToString(", ") { it.name.lowercase() }}" to { addKit(session, menu, slot, kit) },
            "Import .wav file… (converted at $rate Hz, ${if (dma) "the sound's DMA rate" else "the song's voice timer"}" +
                "${if (song.ws.color) "; stereo kept" else "; stereo mixed to mono"})" to {
                menu.pickFile("Import sample", listOf(".wav"), MAX_FILE) { name, _, bytes -> importWav(session, menu, slot, name, bytes) }
            },
        ))
    }

    /** "2.3 KB" of the player's data region (1 KB = 1024 bytes; negative: over). */
    fun kb(bytes: Int): String = "${NumberText.fixed(bytes / 1024.0, 1)} KB"

    /** "0.42 s": [sample] as sound [ins] plays it on its root note in [song]. */
    fun seconds(song: Song, sample: WsSample, ins: WsInstrument?): String {
        val dma = ins?.isDma == true
        val rate = if (ins != null) WsSamples.playRateHz(ins, song.ws.timerReload) else WsSamples.timerRateHz(song.ws.timerReload)
        return "${NumberText.fixed(WsSamples.seconds(sample, rate, dma), 2)} s"
    }

    private fun addKit(session: EditorSession, menu: MenuContext, slot: Int, kit: List<WsSample>) {
        val before = session.song
        val feed = if (session.song.ws.instrument(slot)?.isDma == true) WsFeed.DMA else WsFeed.CPU
        val slots = session.addWsSamples(slot, kit, "Add starter kit") { sample, name -> WsStarterKit.instrument(sample, name, feed) }
        if (slots.isEmpty()) return menu.error("No room: the song has ${WsSound.MAX_SAMPLES} samples")
        val names = slots.mapIndexed { i, s -> "${kit[i].name} ${hex2(s)}" }
        menu.status("Starter kit: ${names.joinToString(" · ")}" + (if (slots.size < kit.size) " (no free slot for the rest)" else "") +
            " · ${kb(WsSamples.free(session.song))} free")
        overflowNote(session, menu, before)
    }

    private fun importWav(session: EditorSession, menu: MenuContext, slot: Int, name: String, bytes: ByteArray) {
        val before = session.song
        val rate = rateFor(before, slot)
        val sample = try {
            WsSamples.fromWav(bytes, name, rate, stereo = before.ws.color)
        } catch (e: IllegalArgumentException) {
            return menu.error("Not imported: ${e.message}")
        }
        val feed = if (before.ws.instrument(slot)?.isDma == true) WsFeed.DMA else WsFeed.CPU
        val slots = session.addWsSamples(slot, listOf(sample), "Import sample ${sample.name}") { index, n -> WsStarterKit.instrument(index, n, feed) }
        if (slots.isEmpty()) return menu.error("No room: the song has ${WsSound.MAX_SAMPLES} samples")
        menu.status("${sample.name}: ${sample.frames} frames${if (sample.stereo) " stereo (Hyper Voice only)" else ""} at $rate Hz on sound " +
            hex2(slots.first()) + (if (sample.frames >= WsSample.MAX_FRAMES) ", cut to the longest sample" else "") +
            " · ${kb(WsSamples.free(session.song))} free")
        overflowNote(session, menu, before)
    }

    /** Warns when the addition left the song larger than the player's data region (the song check reports it too). */
    private fun overflowNote(session: EditorSession, menu: MenuContext, before: Song) {
        val free = WsSamples.free(session.song)
        if (free < 0 && free < WsSamples.free(before)) menu.error("The song now needs ${kb(-free)} more than the player's data space. " +
            "Delete or shorten samples before playing or exporting.")
    }
}
