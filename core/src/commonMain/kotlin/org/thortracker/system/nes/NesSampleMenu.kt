package org.thortracker.system.nes

import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.MenuContext
import org.thortracker.model.NumberText
import org.thortracker.model.Song

/**
 * Adding DPCM samples to sound [slot] from the DPCM page's Add row: the [DpcmStarterKit] or a `.dmc` / `.wav` file
 * (converted on the device by [Dpcm.fromWav] at a rate the user picks, each rate shown with its size against the
 * free sample memory). New samples are mapped from the shown key upward, skipping keys the sound already maps.
 */
internal object NesSampleMenu {
    /** Largest file offered for import: minutes of CD audio, far more than a DMC sample can hold. */
    private const val MAX_FILE = 32 * 1024 * 1024

    fun open(session: EditorSession, slot: Int, key: Int) = session.requestMenu { menu ->
        val free = free(session.song)
        val kit = DpcmStarterKit.samples()
        menu.menu("Add samples to sound ${hex2(slot)} from ${NesSystem.noteName(key)}", listOf(
            "Starter kit: ${kit.joinToString(", ") { it.name.lowercase() }}" to { addKit(session, menu, slot, key, kit) },
            "Import .wav or .dmc file… (${kb(free)} free)" to {
                menu.pickFile("Import sample", listOf(".wav", ".dmc"), MAX_FILE) { name, extension, bytes ->
                    if (extension == ".dmc") addDmc(session, menu, slot, key, name, bytes) else chooseRate(session, menu, slot, key, name, bytes)
                }
            },
        ))
    }

    /** Sample memory left: [Dpcm.MEMORY] less what the song's samples take. */
    fun free(song: Song): Int = Dpcm.MEMORY - Dpcm.memoryUsed(song)

    /** "2.3 KB" (1 KB = 1024 bytes, as the 16 KB sample memory is counted). */
    fun kb(bytes: Int): String = "${NumberText.fixed(bytes / 1024.0, 1)} KB"

    /** "33.1 kHz": DMC rate [pitch]. */
    fun rateText(pitch: Int): String = "${NumberText.fixed(Dpcm.RATES_HZ[pitch] / 1000.0, 1)} kHz"

    /**
     * Maps the kit as [DpcmStarterKit.NOTES] lays it out (General MIDI drum keys from C) in the octave of [key];
     * keys the sound already maps keep their samples.
     */
    private fun addKit(session: EditorSession, menu: MenuContext, slot: Int, key: Int, kit: List<DpcmSample>) {
        val before = session.song
        val shift = key / 12 * 12 - DpcmStarterKit.NOTES.keys.min() / 12 * 12
        val layout = DpcmStarterKit.NOTES.entries.associate { (note, use) -> note + shift to use }
        val keys = session.addDpcmLayout(slot, kit, layout, "Add starter kit")
        if (keys.isEmpty()) return menu.error("Sound ${hex2(slot)} already maps every key of the kit from ${NesSystem.noteName(key / 12 * 12)}")
        menu.status("Starter kit: ${keys.size} keys ${keys.joinToString(" ") { NesSystem.noteName(it) }} in sound ${hex2(slot)} · ${kb(free(session.song))} free")
        overflowNote(session, menu, before)
    }

    private fun addDmc(session: EditorSession, menu: MenuContext, slot: Int, key: Int, name: String, bytes: ByteArray) {
        val data = try { Dpcm.fromDmc(bytes) } catch (e: IllegalArgumentException) { return menu.error("Not imported: ${e.message}") }
        add(session, menu, slot, key, DpcmSample(name.take(DpcmSample.MAX_NAME), data), DpcmMapping.PITCH.last)
    }

    private fun add(session: EditorSession, menu: MenuContext, slot: Int, key: Int, sample: DpcmSample, pitch: Int) {
        val before = session.song
        val keys = session.addAndMapDpcmSamples(slot, listOf(sample), key, pitch, "Import sample ${sample.name}")
        if (keys.isEmpty()) return menu.error("No free key from ${NesSystem.noteName(key)} up in sound ${hex2(slot)}")
        menu.status("${sample.name}: ${sample.data.size} bytes on ${NesSystem.noteName(keys.first())} at pitch ${pitch.toString(16).uppercase()} · " +
            "${kb(free(session.song))} free")
        overflowNote(session, menu, before)
    }

    /** Warns when the addition left the samples larger than the memory they share (the song check reports it too). */
    private fun overflowNote(session: EditorSession, menu: MenuContext, before: Song) {
        val free = free(session.song)
        if (free < 0 && free < free(before)) menu.error("The samples now need ${kb(-free)} more than the ${kb(Dpcm.MEMORY)} of sample memory. " +
            "Delete or shorten samples (or import at a lower rate) before playing or exporting.")
    }

    /**
     * Converts the WAV at every rate and offers them, highest first, with each one's size against the free memory;
     * the cursor starts on the highest rate that fits whole.
     */
    private fun chooseRate(session: EditorSession, menu: MenuContext, slot: Int, key: Int, name: String, bytes: ByteArray) {
        val converted = try {
            DpcmMapping.PITCH.reversed().map { it to Dpcm.fromWav(bytes, it) }
        } catch (e: IllegalArgumentException) { return menu.error("Not imported: ${e.message}") }
        val free = free(session.song)
        val entries = converted.map { (pitch, data) ->
            val cut = data.size >= DpcmSample.MAX_BYTES
            val fits = Dpcm.aligned(data.size) <= free
            val label = "${pitch.toString(16).uppercase()} · ${rateText(pitch)} · ${data.size} bytes" + when {
                !fits -> " · needs ${kb(Dpcm.aligned(data.size))}, ${kb(maxOf(0, free))} free"
                cut -> " · cut to ${NumberText.fixed(data.size * 8.0 / Dpcm.RATES_HZ[pitch], 2)} s"
                else -> ""
            }
            label to { add(session, menu, slot, key, DpcmSample(name.take(DpcmSample.MAX_NAME), data), pitch) }
        }
        val best = converted.indexOfFirst { (_, data) -> data.size < DpcmSample.MAX_BYTES && Dpcm.aligned(data.size) <= free }
            .takeIf { it >= 0 } ?: converted.indexOfFirst { (_, data) -> Dpcm.aligned(data.size) <= free }.coerceAtLeast(0)
        menu.menu("$name: convert at rate (${kb(maxOf(0, free))} free)", entries, selected = best)
    }
}
