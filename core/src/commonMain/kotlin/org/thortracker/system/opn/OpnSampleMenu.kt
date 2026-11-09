package org.thortracker.system.opn

import org.thortracker.backend.OpnPlayerImage
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.MenuContext
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.nes.Dpcm

/**
 * The YM2608's ADPCM-B sample menus, opened from the instrument editor's Sample page: adding a sample to sound [slot]
 * (a `.wav` file converted on the device at a rate the user picks, each rate shown with its size against the free
 * memory, or one of [OpnSampleKit]'s generated starter samples), the authoring tools for the sound's sample ([OpnEdits]:
 * normalize, gain, reverse, fades, resample, replace) and deleting it (asked first; refused while the arrangement plays
 * a sound that uses it). The chip's 256 KiB of ADPCM-B memory is shared by every sample of the song.
 */
internal object OpnSampleMenu {
    /** Largest file offered for import: minutes of CD audio, far more than the 256 KiB memory holds at any rate. */
    const val MAX_FILE = 32 * 1024 * 1024

    /** "12.3 KiB". */
    fun kib(bytes: Int): String = "${NumberText.fixed(bytes / 1024.0, 1)} KiB"

    /** "1.25 s". */
    fun seconds(s: Double): String = if (s >= 10) "${NumberText.fixed(s, 1)} s" else "${NumberText.fixed(s, 2)} s"

    /** "used 12.3 of 256 KiB · 243.7 KiB free". */
    fun memoryText(song: Song): String {
        val free = OpnEdits.free(song)
        return "used ${kib(song.opn.adpcmUsed)} of ${kib(OpnPlayerImage.ADPCM_MEMORY)} · " +
            if (free >= 0) "${kib(free)} free" else "${kib(-free)} too much"
    }

    /** Fade lengths offered, in milliseconds (0 = the whole sample). */
    private val FADES_MS = listOf(10, 50, 200, 500, 0)

    /** Gains offered, in decibels. */
    private val GAINS_DB = listOf(6, 3, -3, -6, -12)

    // ---- adding -----------------------------------------------------------------------------------------------

    /** The Add menu of sound [slot]: import a `.wav`, or generate a starter sample; the sound then plays it. */
    fun add(session: EditorSession, slot: Int): Unit = session.requestMenu { menu ->
        val song = session.song
        if (song.opn.samples.size >= OpnSound.MAX_SAMPLES) {
            return@requestMenu menu.error("The song already has ${OpnSound.MAX_SAMPLES} ADPCM samples: delete one first")
        }
        val entries = ArrayList<Pair<String, () -> Unit>>()
        entries += "Import .wav file…   ${kib(maxOf(0, OpnEdits.free(song)))} free" to {
            menu.pickFile("Import ADPCM-B sample", listOf(".wav"), MAX_FILE) { name, _, bytes -> chooseRate(session, menu, name, bytes) { add(session, menu, slot, it) } }
        }
        for (kind in OpnSampleKit.KINDS) entries += "Generate: $kind" to { generate(session, menu, slot, kind) }
        menu.menu("Add a sample to sound ${hex2(slot)} (${memoryText(song)})", entries)
    }

    private fun generate(session: EditorSession, menu: MenuContext, slot: Int, kind: String) {
        val sample = try { OpnSampleKit.generate(kind) } catch (e: IllegalArgumentException) { return menu.error("Not generated: ${e.message}") }
        add(session, menu, slot, sample)
    }

    private fun add(session: EditorSession, menu: MenuContext, slot: Int, sample: OpnaSample) {
        val before = session.song
        if (!session.apply("Add sample ${sample.name}") { OpnEdits.addAndUse(it, slot, sample) }) {
            return menu.error("The song already has ${OpnSound.MAX_SAMPLES} ADPCM samples: delete one first")
        }
        menu.status("${sample.name}: ${sample.rate} Hz, ${seconds(sample.seconds)}, ${kib(sample.stored)} · sound ${hex2(slot)} plays it · " +
            memoryText(session.song))
        overflowNote(session, menu, before)
    }

    /** Warns when the change left the samples larger than the memory they share (the song check refuses it too). */
    private fun overflowNote(session: EditorSession, menu: MenuContext, before: Song) {
        val free = OpnEdits.free(session.song)
        if (free < 0 && free < OpnEdits.free(before)) {
            menu.error("The samples now need ${kib(-free)} more than the ${kib(OpnPlayerImage.ADPCM_MEMORY)} of ADPCM-B memory. " +
                "Trim, resample to a lower rate or delete samples before playing or exporting.")
        }
    }

    /**
     * Reads the WAV's length and rate, then offers its own rate (clamped to the chip's) and every lower one of
     * [OpnAdpcm.RATES], highest first, each with its size against the free memory; the cursor starts on the highest rate
     * that fits. The chosen rate is converted ([OpnAdpcm.importWav]) and [use] receives the sample. [replacing]: bytes
     * the replaced sample frees.
     */
    private fun chooseRate(session: EditorSession, menu: MenuContext, name: String, bytes: ByteArray, replacing: Int = 0, use: (OpnaSample) -> Unit) {
        val label = name.take(OpnaSample.MAX_NAME)
        val wav = try { Dpcm.Wav.read(bytes) } catch (e: IllegalArgumentException) { return menu.error("Not imported: ${e.message}") }
        if (wav.samples.isEmpty()) return menu.error("Not imported: the .wav file has no audio")
        val own = wav.rate.coerceIn(OpnPlayerImage.RATE_MIN, OpnPlayerImage.RATE_MAX)
        val length = seconds(wav.samples.size.toDouble() / wav.rate)
        val free = OpnEdits.free(session.song) + replacing
        val rates = (OpnAdpcm.RATES.filter { it < own } + own).distinct().sortedDescending()
        // The resampled length (OpnAdpcm.resample), padded to whole 32-byte units.
        fun size(rate: Int): Int {
            val samples = maxOf(1L, wav.samples.size.toLong() * rate / wav.rate)
            return ((samples + OpnEdits.UNIT_SAMPLES - 1) / OpnEdits.UNIT_SAMPLES * OpnPlayerImage.ADPCM_ALIGN).toInt()
        }
        val entries = rates.map { rate ->
            val bytesAt = size(rate)
            val text = "$rate Hz${if (rate == wav.rate) " (the file's)" else ""} · ${kib(bytesAt)} · $length" + when {
                bytesAt > OpnPlayerImage.ADPCM_MEMORY -> " · longer than the whole 256 KiB"
                bytesAt > free -> " · needs more than the ${kib(maxOf(0, free))} free"
                else -> ""
            }
            text to {
                val sample = try {
                    OpnAdpcm.importWav(label, bytes, rate)
                } catch (e: IllegalArgumentException) { null.also { menu.error("Not imported: ${e.message}") } }
                if (sample != null) use(sample)
            }
        }
        val best = rates.indexOfFirst { size(it) <= free }.takeIf { it >= 0 } ?: rates.lastIndex
        menu.menu("$label: convert at (${kib(maxOf(0, free))} free)", entries, selected = best)
    }

    // ---- authoring --------------------------------------------------------------------------------------------

    /** The tools of sample [index] (the sound's): normalize, gain, reverse, fades, resample, replace, delete. */
    fun tools(session: EditorSession, index: Int): Unit = session.requestMenu { menu ->
        val song = session.song
        val sample = song.opn.samples.getOrNull(index) ?: return@requestMenu menu.error("There is no sample ${index + 1}")
        val n = sample.data.size * 2
        val peak = OpnEdits.peak(sample)
        val peakText = if (peak == 0) "silent" else "peak ${NumberText.fixed(20 * kotlin.math.log10(peak / 32767.0), 1)} dB"
        val entries = ArrayList<Pair<String, () -> Unit>>()
        entries += "Normalize   $peakText → 0 dB" to { transform(session, menu, index, "Normalize sample") { OpnEdits.normalize(it, index) } }
        for (db in GAINS_DB) {
            entries += "Gain ${if (db > 0) "+" else "−"}${kotlin.math.abs(db)} dB" to {
                transform(session, menu, index, "Sample gain") { OpnEdits.gain(it, index, db.toDouble()) }
            }
        }
        entries += "Reverse" to { transform(session, menu, index, "Reverse sample") { OpnEdits.reverse(it, index) } }
        entries += "Fade in…" to { fade(session, menu, index, n, fadeIn = true) }
        entries += "Fade out…" to { fade(session, menu, index, n, fadeIn = false) }
        entries += "Resample…   now ${sample.rate} Hz, ${kib(sample.stored)}" to { resample(session, menu, index) }
        entries += "Replace with a .wav file…   sounds playing it keep playing it" to {
            menu.pickFile("Replace ${sample.name}", listOf(".wav"), MAX_FILE) { name, _, bytes ->
                chooseRate(session, menu, name, bytes, replacing = sample.stored) { s ->
                    val before = session.song
                    if (session.apply("Replace sample ${sample.name}") { OpnEdits.replaceSample(it, index, s) }) {
                        menu.status("Sample ${index + 1} is now ${s.name}: ${s.rate} Hz, ${kib(s.stored)} · ${memoryText(session.song)}")
                        overflowNote(session, menu, before)
                    }
                }
            }
        }
        entries += "Delete sample…" to { delete(session, menu, index) }
        menu.menu("${sample.name}: ${sample.rate} Hz · ${seconds(sample.seconds)} · ${kib(sample.stored)}", entries)
    }

    /** Applies [f] to the song as one undo step and reports the sample's new size, or that nothing changed. */
    private fun transform(session: EditorSession, menu: MenuContext, index: Int, label: String, f: (Song) -> Song) {
        val before = session.song
        if (!session.apply(label) { f(it) }) return menu.status("$label: nothing to change")
        val s = session.song.opn.samples.getOrNull(index) ?: return
        menu.status("$label: ${s.name} · ${s.rate} Hz · ${seconds(s.seconds)} · ${kib(s.stored)} · ${memoryText(session.song)}")
        overflowNote(session, menu, before)
    }

    private fun fade(session: EditorSession, menu: MenuContext, index: Int, length: Int, fadeIn: Boolean) {
        val sample = session.song.opn.samples.getOrNull(index) ?: return
        if (length <= 0) return menu.error("${sample.name} has no audio to fade")
        val entries = FADES_MS.map { ms ->
            val count = if (ms == 0) length else (sample.rate.toLong() * ms / 1000).toInt().coerceIn(1, length)
            val text = if (ms == 0) "The whole sample (${seconds(sample.seconds)})" else "$ms ms"
            text to {
                if (fadeIn) transform(session, menu, index, "Fade in sample") { OpnEdits.fadeIn(it, index, count) }
                else transform(session, menu, index, "Fade out sample") { OpnEdits.fadeOut(it, index, count) }
            }
        }
        menu.menu(if (fadeIn) "Fade ${sample.name} in over" else "Fade ${sample.name} out over", entries)
    }

    /** Every rate of [OpnAdpcm.RATES] but the sample's own, each with its new size; the sound keeps its pitch. */
    private fun resample(session: EditorSession, menu: MenuContext, index: Int) {
        val sample = session.song.opn.samples.getOrNull(index) ?: return
        val free = OpnEdits.free(session.song) + sample.stored
        val n = sample.data.size * 2L
        val rates = OpnAdpcm.RATES.filter { it != sample.rate }.sortedDescending()
        val entries = rates.map { rate ->
            val samples = (n * rate + sample.rate - 1) / sample.rate
            val bytes = ((samples + OpnEdits.UNIT_SAMPLES - 1) / OpnEdits.UNIT_SAMPLES * OpnPlayerImage.ADPCM_ALIGN).toInt()
            "$rate Hz · ${kib(bytes)}${if (rate > sample.rate) " (no clearer than now)" else ""}" +
                (if (bytes > free) " · needs more than the ${kib(maxOf(0, free))} free" else "") to {
                    transform(session, menu, index, "Resample to $rate Hz") { OpnEdits.resample(it, index, rate) }
                }
        }
        val below = rates.indexOfFirst { it < sample.rate }.coerceAtLeast(0)
        menu.menu("Resample ${sample.name} (now ${sample.rate} Hz, ${kib(sample.stored)})", entries, selected = below)
    }

    // ---- deleting ---------------------------------------------------------------------------------------------

    /**
     * Deletes sample [index] after asking: an unused sample goes; sounds that play it are emptied with it, but only
     * when the arrangement plays none of them (otherwise the deletion is refused, naming them).
     */
    fun delete(session: EditorSession, index: Int): Unit = session.requestMenu { menu -> delete(session, menu, index) }

    private fun delete(session: EditorSession, menu: MenuContext, index: Int) {
        val song = session.song
        val sample = song.opn.samples.getOrNull(index) ?: return menu.error("There is no sample ${index + 1}")
        val users = OpnEdits.soundsUsing(song, index)
        val rows = OpnEdits.rowsUsing(song, users.toSet())
        val names = users.joinToString(", ") { "${hex2(it)} ${song.opn.instrument(it)?.name.orEmpty()}".trim() }
        if (rows > 0) {
            return menu.error("${sample.name} is played by sound${if (users.size == 1) "" else "s"} $names on $rows row${if (rows == 1) "" else "s"}: " +
                "give ${if (users.size == 1) "it" else "them"} another sample (Sample row) or remove those notes first")
        }
        val action = if (users.isEmpty()) "Delete ${sample.name} (frees ${kib(sample.stored)})"
            else "Delete ${sample.name} and empty sound${if (users.size == 1) "" else "s"} $names (not used in the arrangement)"
        menu.menu("Delete sample ${index + 1}, ${sample.name}?", listOf(
            "Keep it" to { menu.status("${sample.name} kept") },
            action to {
                if (session.apply("Delete sample ${sample.name}") { OpnEdits.deleteSample(it, index) }) {
                    menu.status("Deleted ${sample.name}" + (if (users.isEmpty()) "" else " and emptied sound${if (users.size == 1) "" else "s"} $names") +
                        "; later samples moved down one, their sounds follow · ${memoryText(session.song)}")
                }
            },
        ))
    }
}
