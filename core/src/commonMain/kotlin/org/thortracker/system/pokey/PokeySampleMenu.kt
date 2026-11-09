package org.thortracker.system.pokey

import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.MenuContext
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.nes.Dpcm

/**
 * Adding samples from the Sample page's Add row of sound [slot]: the starter kit ([PokeySamples.starterKit], its sounds
 * into this and the empty slots after it, [addPokeyKit]) or a `.wav` file converted on the device to 4-bit levels
 * ([PokeySamples.fromWav]) at a rate the user picks from [PokeySamples.IMPORT_RATES], each shown with its length
 * against the free sample memory. An imported file becomes the sample this sound plays, at the chosen rate.
 */
internal object PokeySampleMenu {
    /** Largest file offered for import (as the NES import): far more than 32768 levels hold at any rate. */
    private const val MAX_FILE = 32 * 1024 * 1024

    fun open(session: EditorSession, slot: Int) = session.requestMenu { menu ->
        val free = PokeySamples.free(session.song)
        val kit = PokeySamples.starterKit()
        menu.menu("Add samples for sound ${hex2(slot)}", listOf(
            "Starter kit: ${kit.joinToString(", ") { it.name.lowercase() }} (${levels(kit.sumOf { it.levels.size })})" to { addKit(session, menu, slot) },
            "Import .wav file… (${levels(free)} free)" to {
                menu.pickFile("Import sample", listOf(".wav"), MAX_FILE) { name, _, bytes -> chooseRate(session, menu, slot, name, bytes) }
            },
        ))
    }

    /** "12345 levels" (a level is one 4-bit value: two per byte of the 16 KB sample memory). */
    fun levels(n: Int): String = "$n level${if (n == 1) "" else "s"}"

    /** "0.77 s": [n] levels at [rate] levels per second. */
    fun seconds(n: Int, rate: Int): String = "${NumberText.fixed(n.toDouble() / rate, 2)} s"

    private fun addKit(session: EditorSession, menu: MenuContext, slot: Int) {
        val before = session.song
        val slots = session.addPokeyKit(slot)
        if (session.song === before) return menu.error(session.message ?: "The starter kit is already in the song")
        val p = session.song.pokey
        menu.status(if (slots.isEmpty()) "Starter kit samples added; no empty slot after ${hex2(slot)} took its sounds (use Preset on a sample sound)"
            else "Starter kit: " + slots.joinToString(", ") { "${hex2(it)} ${p.instrument(it)?.name.orEmpty()}" } + " · ${levels(PokeySamples.free(session.song))} free")
        overflowNote(session, menu, before)
    }

    /** Warns when an addition left the samples larger than the memory they share (the song check reports it too). */
    private fun overflowNote(session: EditorSession, menu: MenuContext, before: Song) {
        val free = PokeySamples.free(session.song)
        if (free < 0 && free < PokeySamples.free(before)) menu.error("The samples now need ${levels(-free)} more than the ${levels(PokeySample.MEMORY)} " +
            "of sample memory. Delete or shorten samples (or import at a lower rate) before playing or exporting.")
    }

    /**
     * Offers every import rate, highest first, with the length the file takes at it against the free memory (from the
     * file's own length; only the chosen rate is converted). The cursor starts on the highest rate that fits whole.
     */
    private fun chooseRate(session: EditorSession, menu: MenuContext, slot: Int, name: String, bytes: ByteArray) {
        val wav = try { Dpcm.Wav.read(bytes) } catch (e: IllegalArgumentException) { return menu.error("Not imported: ${e.message}") }
        if (wav.samples.isEmpty()) return menu.error("Not imported: the .wav file holds no sound")
        val free = PokeySamples.free(session.song)
        val rates = PokeySamples.IMPORT_RATES.sortedDescending()
        // PokeySamples.fromWav's own count (rounded), before its cut at PokeySample.MAX_LENGTH.
        fun length(rate: Int): Int = minOf(Int.MAX_VALUE.toLong(), (wav.samples.size.toLong() * rate + wav.rate / 2) / wav.rate).toInt()
        val entries = rates.map { rate ->
            val n = length(rate)
            val kept = minOf(n, PokeySample.MAX_LENGTH)
            val label = "$rate Hz · ${levels(kept)}" + when {
                kept > free -> " · ${levels(maxOf(0, free))} free"
                n > kept -> " · cut to ${seconds(kept, rate)}"
                else -> " · ${seconds(kept, rate)}"
            }
            label to { importAt(session, menu, slot, name, bytes, rate) }
        }
        fun fits(rate: Int) = minOf(length(rate), PokeySample.MAX_LENGTH) <= free
        val best = rates.indexOfFirst { length(it) <= PokeySample.MAX_LENGTH && fits(it) }.takeIf { it >= 0 }
            ?: rates.indexOfFirst { fits(it) }.takeIf { it >= 0 } ?: rates.lastIndex
        menu.menu("$name: convert at rate (${levels(maxOf(0, free))} free)", entries, selected = best)
    }

    private fun importAt(session: EditorSession, menu: MenuContext, slot: Int, name: String, bytes: ByteArray, rate: Int) {
        val converted = try { PokeySamples.fromWav(bytes, rate) } catch (e: IllegalArgumentException) { return menu.error("Not imported: ${e.message}") }
        if (converted.isEmpty()) return menu.error("Not imported: the .wav file holds no sound")
        val before = session.song
        val sample = PokeySample(name.take(PokeySample.MAX_NAME), converted.take(PokeySample.MAX_LENGTH))
        if (!session.importPokeySample(slot, sample, rate)) return menu.error(session.message ?: "Not imported")
        menu.status("${sample.name}: ${levels(sample.levels.size)} (${seconds(sample.levels.size, rate)}) at $rate Hz on " +
            "${PokeySystem.noteName(session.song.pokey.instrument(slot)?.root ?: PokeyInstrument.DEFAULT_ROOT)} in sound ${hex2(slot)}" +
            " · ${levels(PokeySamples.free(session.song))} free")
        overflowNote(session, menu, before)
    }
}
