package org.thortracker.system.ym2612

import org.thortracker.backend.YmPcmLowering
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.Hint
import org.thortracker.editor.MenuContext
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.input.Keymap
import org.thortracker.system.ym2612.YmInstrumentView.Row

/**
 * The PCM page of [YmInstrumentView] (a PCM kit's only page): the kit's static level, which source sample each key
 * plays on PCM 1-3 and at what pitch (a chromatic spread makes a pitched instrument), and the song's PCM samples (name,
 * delete, import a `.wav`, [YmPcmSampleMenu]). The sample-page cursors serve here as on the NES:
 * [org.thortracker.editor.ViewState.dpcmKey] is the key whose mapping the rows edit (and the key Listen plays), and
 * [org.thortracker.editor.ViewState.dpcmSample] the sample list cursor.
 */
internal object YmPcmPage {
    /** ↑↓ while editing the pitch: an octave. */
    private const val OCTAVE = 12

    /** Spread: this many keys either side of the shown one. */
    const val SPREAD = 12

    /** Rows whose cursor names the shown key's mapping. */
    val KEY_ROWS = setOf(Row.PCM_KEY, Row.PCM_SAMPLE, Row.PCM_PITCH, Row.PCM_SPREAD)

    /** Rows drawn inside the samples box. */
    val BOX_ROWS = setOf(Row.PCM_SAMPLES, Row.PCM_NAME, Row.PCM_ADD)

    fun normalise(v: ViewController) {
        val st = v.state
        st.dpcmKey = st.dpcmKey.coerceIn(YmRules.NOTES)
        st.dpcmSample = st.dpcmSample.coerceIn(0, maxOf(0, v.session.song.ym.pcmSamples.size - 1))
    }

    /** Points the key at the kit's first mapped key (when the shown key has none) and the sample list at its sample. */
    fun showFirstMapping(v: ViewController) {
        val st = v.state
        val kit = v.session.song.ymInstrument(st.instrument) as? YmPcmInstrument ?: return normalise(v)
        if (st.dpcmKey !in kit.pcm) kit.pcm.keys.minOrNull()?.let { st.dpcmKey = it }
        followSample(v)
        normalise(v)
    }

    private fun followSample(v: ViewController) {
        v.session.song.ymPcmMapping(v.state.instrument, v.state.dpcmKey)?.let { v.state.dpcmSample = it.sample }
    }

    /** Plain ←→ on rows that move within the page; false lets the view handle it (the slot). */
    fun direction(v: ViewController, row: Row, dx: Int): Boolean {
        val st = v.state
        when (row) {
            Row.PCM_KEY -> { st.dpcmKey = (st.dpcmKey + dx).coerceIn(YmRules.NOTES); followSample(v) }
            Row.PCM_SAMPLES -> st.dpcmSample = (st.dpcmSample + dx).coerceIn(0, maxOf(0, v.session.song.ym.pcmSamples.size - 1))
            Row.PCM_NAME -> st.textPos = (st.textPos + dx).coerceIn(0, YmPcm.MAX_NAME - 1)
            else -> return false
        }
        return true
    }

    private fun needsMapping(v: ViewController): Boolean {
        if (v.session.song.ymPcmMapping(v.state.instrument, v.state.dpcmKey) != null) return false
        v.session.note("${YmSystem.noteName(v.state.dpcmKey)} plays no PCM sample yet: choose one on the Sample row")
        return true
    }

    /** Edit+arrows on a PCM row; true when the kit changed (so a held Listen sounds it again). */
    fun adjust(v: ViewController, row: Row, dx: Int, dy: Int): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val key = st.dpcmKey
        val samples = session.song.ym.pcmSamples
        val keyName = YmSystem.noteName(key)
        return when (row) {
            Row.PCM_LEVEL -> session.editYmPcm(slot, "Set PCM kit level") { it.copy(level = (it.level + dx + 4 * dy).coerceIn(0, YmPcm.LEVEL_MAX)) }
            Row.PCM_KEY -> {
                val mapped = (session.song.ymInstrument(slot) as? YmPcmInstrument)?.pcm?.keys?.sorted().orEmpty()
                st.dpcmKey = when {
                    dy != 0 -> (key + 12 * dy).coerceIn(YmRules.NOTES)
                    dx > 0 -> mapped.firstOrNull { it > key } ?: run { session.note("No mapped key above $keyName"); return false }
                    else -> mapped.lastOrNull { it < key } ?: run { session.note("No mapped key below $keyName"); return false }
                }
                followSample(v)
                false
            }
            Row.PCM_SAMPLE -> {
                if (samples.isEmpty()) { session.note("The song has no PCM samples yet: import one on the Add row"); return false }
                val cur = session.song.ymPcmMapping(slot, key)
                val next = ((cur?.sample ?: -1) + 1 + dx + 4 * dy).mod(samples.size + 1) - 1
                if (next < 0) session.setYmPcmMapping(slot, key, null, "Unmap PCM $keyName")
                else session.setYmPcmMapping(slot, key, (cur ?: YmPcmMapping(next)).copy(sample = next), "Map PCM $keyName").also { st.dpcmSample = next }
            }
            Row.PCM_PITCH -> !needsMapping(v) && session.editYmPcmMapping(slot, key, "Set PCM $keyName pitch") {
                it.copy(pitch = (it.pitch + dx + OCTAVE * dy).coerceIn(YmPcm.PITCH))
            }
            Row.PCM_SAMPLES -> { st.dpcmSample = (st.dpcmSample + dx + dy).coerceIn(0, maxOf(0, samples.size - 1)); false }
            Row.PCM_NAME -> {
                session.editYmPcmSample(st.dpcmSample, "Rename PCM sample") { it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, YmPcm.MAX_NAME)) }
                false
            }
            else -> false
        }
    }

    /** A tap of Edit on a PCM row; true when the kit changed. */
    fun editTap(v: ViewController, row: Row): Boolean {
        val st = v.state
        val session = v.session
        val key = st.dpcmKey
        val keyName = YmSystem.noteName(key)
        return when (row) {
            Row.PCM_SAMPLE, Row.PCM_SAMPLES -> {
                val sample = session.song.ym.pcmSamples.getOrNull(st.dpcmSample)
                    ?: run { session.note("The song has no PCM samples yet: import one on the Add row"); return false }
                val cur = session.song.ymPcmMapping(st.instrument, key)
                if (cur?.sample == st.dpcmSample) { session.note("$keyName already plays ${sample.name}"); return false }
                session.setYmPcmMapping(st.instrument, key, (cur ?: YmPcmMapping(st.dpcmSample)).copy(sample = st.dpcmSample), "Map PCM $keyName").also {
                    session.song.ymPcmMapping(st.instrument, key)?.let { m -> session.note(ymPcmMappingText(session.song, key, m)) }
                }
            }
            Row.PCM_SPREAD -> {
                if (needsMapping(v)) return false
                val added = session.spreadYmPcmMapping(st.instrument, key, SPREAD, SPREAD)
                session.note(if (added.isEmpty()) "Every key within an octave of $keyName already plays a PCM sample"
                else "Mapped ${added.size} keys ${YmSystem.noteName(added.first())}-${YmSystem.noteName(added.last())} chromatically from $keyName" +
                    (YmPcmLowering.problem(session.song.ym)?.let { " · $it" } ?: ""))
                added.isNotEmpty()
            }
            Row.PCM_ADD -> { YmPcmSampleMenu.open(session, st.instrument, key); false }
            else -> false
        }
    }

    fun clear(v: ViewController, row: Row) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val key = st.dpcmKey
        val keyName = YmSystem.noteName(key)
        when (row) {
            Row.PCM_LEVEL -> session.editYmPcm(slot, "Reset PCM kit level") { it.copy(level = YmPcm.LEVEL_MAX) }
            Row.PCM_KEY, Row.PCM_SAMPLE -> if (session.song.ymPcmMapping(slot, key) != null) session.setYmPcmMapping(slot, key, null, "Unmap PCM $keyName")
            Row.PCM_PITCH -> session.editYmPcmMapping(slot, key, "Set PCM $keyName pitch") { it.copy(pitch = 0) }
            Row.PCM_SAMPLES -> {
                val name = session.song.ym.pcmSamples.getOrNull(st.dpcmSample)?.name ?: return
                val removed = session.deleteYmPcmSample(st.dpcmSample)
                normalise(v)
                session.note("Deleted PCM sample $name" + if (removed > 0) " and the $removed key${if (removed == 1) "" else "s"} that played it" else "")
            }
            Row.PCM_NAME -> session.editYmPcmSample(st.dpcmSample, "Rename PCM sample") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            else -> Unit
        }
    }

    /** Plays the shown key on PCM 1 (or the Listen PCM lane), as a pattern note with this kit would. */
    fun audition(v: ViewController, channel: Int) {
        val st = v.state
        val song = v.session.song
        val m = song.ymPcmMapping(st.instrument, st.dpcmKey)
        if (m == null) { v.session.note("${YmSystem.noteName(st.dpcmKey)} plays no PCM sample in kit ${hex2(st.instrument)}"); return }
        v.startAudition(channel, st.dpcmKey, st.instrument)
        v.session.note(ymPcmMappingText(song, st.dpcmKey, m))
    }

    fun hints(v: ViewController, row: Row, h: MutableList<Hint>) {
        val clear = v.k(Action.CLEAR)
        val edit = v.k(Action.EDIT)
        when (row) {
            Row.PCM_LEVEL -> { h += Hint(v.editLr(), "Level ±1 (${v.ud()} ±4)"); h += Hint(clear, "Full level") }
            Row.PCM_KEY -> { h += Hint(v.lr(), "Key"); h += Hint(v.editLr(), "Mapped keys"); h += Hint(v.editUd(), "Octave"); h += Hint(clear, "Unmap key") }
            Row.PCM_SAMPLE -> { h += Hint(v.editLr(), "Sample"); h += Hint(edit, "Map listed sample"); h += Hint(clear, "Unmap key") }
            Row.PCM_PITCH -> { h += Hint(v.editLr(), "Pitch ±1 st (${v.ud()} octave)"); h += Hint(clear, "Pitch 0") }
            Row.PCM_SPREAD -> h += Hint(edit, "Map an octave either way")
            Row.PCM_SAMPLES -> { h += Hint(v.lr(), "Sample"); h += Hint(edit, "Map to key"); h += Hint(clear, "Delete sample") }
            Row.PCM_NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(clear, "Delete letter") }
            Row.PCM_ADD -> h += Hint(edit, "Import a .wav file")
            else -> Unit
        }
        if (row == Row.PCM_LEVEL || row == Row.PCM_SAMPLE || row == Row.PCM_PITCH || row == Row.PCM_SPREAD || row == Row.PCM_ADD) h += Hint(v.lr(), "Sound slot")
    }

    fun helpRows(keys: Keymap): List<Pair<String, String>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        return listOf(
            "PCM kit" to "PCM 1-3 play kits: each key plays a mapped sample, once (XGM2's three PCM channels, mixed into the DAC that " +
                "replaces FM 6). The instrument number picks the kit, the note picks the key",
            "Level" to "The kit's loudness 0-15 (15 = as recorded, 2 dB quieter per step, 0 silent), compiled into its sounds: there is no Cxx on PCM",
            "Key" to "$lr: the key whose mapping the rows below show and Listen plays; ${keys.held(Action.EDIT, lr)} jumps between mapped keys, " +
                "${keys.held(Action.EDIT, ud)} an octave; $clear unmaps it",
            "Sample" to "${keys.held(Action.EDIT, lr)}: the PCM sample the key plays (or none); $edit maps the sample selected in the list",
            "Pitch" to "Semitones up or down from the sample's own rate (${YmPcm.PITCH.first}..+${YmPcm.PITCH.last}): drums keep 0, pitched sounds follow the keys",
            "Spread" to "$edit: map the shown key's sample to every free key an octave either way, each a semitone apart: one sample becomes a playable instrument",
            "Samples" to "$lr: choose; $edit maps it to the key; $clear deletes it with the keys that played it",
            "Add" to "$edit: import a .wav (mono or stereo, 8-32 bit or float). Rates up to 48 kHz are kept, higher ones converted to 48 kHz, " +
                "stereo mixed to mono (the import says so). Samples become signed 8-bit",
            "Memory" to "Samples share ${YmPcm.kb(YmPcm.MEMORY)} (at most ${YmPcm.MAX_SAMPLES}). Every mapped key is compiled to the driver's " +
                "${YmPcm.rateText(YmPcm.fullRate(false))}: at most ${org.thortracker.backend.YmPlayerImage.PCM_MAX_VARIANTS} " +
                "different sounds and ${org.thortracker.backend.YmPlayerImage.PCM_BANK_SIZE / 1024} KB per song. A file that does not fit is refused, never cut",
        )
    }
}

/**
 * Importing PCM samples to kit [slot] from the PCM page's Add row: a `.wav` file converted on the device
 * ([YmPcm.fromWav]) and mapped at the shown key (or the next free key up), at pitch 0.
 */
internal object YmPcmSampleMenu {
    private const val MAX_FILE = 32 * 1024 * 1024

    fun free(session: EditorSession): Int = YmPcm.MEMORY - YmPcm.stored(session.song.ym)

    fun open(session: EditorSession, slot: Int, key: Int) = session.requestMenu { menu ->
        val free = free(session)
        if (session.song.ym.pcmSamples.size >= YmPcm.MAX_SAMPLES) return@requestMenu menu.error("The song holds ${YmPcm.MAX_SAMPLES} PCM samples; delete one first")
        menu.pickFile("Import PCM sample (${YmPcm.kb(maxOf(0, free))} free)", listOf(".wav"), MAX_FILE) { name, _, bytes -> import(session, menu, slot, key, name, bytes) }
    }

    private fun import(session: EditorSession, menu: MenuContext, slot: Int, key: Int, name: String, bytes: ByteArray) {
        val imported = try { YmPcm.fromWav(bytes, name, free(session)) } catch (e: IllegalArgumentException) { return menu.error("Not imported: ${e.message}") }
        val sample = imported.sample
        val at = session.addAndMapYmPcmSample(slot, sample, key, "Import PCM sample ${sample.name}")
            ?: return menu.error(
                if (session.song.ymInstrument(slot).let { it != null && it !is YmPcmInstrument }) "Sound ${hex2(slot)} is not a PCM kit"
                else "No free key from ${YmSystem.noteName(key)} up in kit ${hex2(slot)}"
            )
        menu.status(buildString {
            append("${sample.name}: ${sample.data.size} bytes, ${YmPcm.rateText(sample.rate.toDouble())}, ${YmPcm.seconds(sample)} on ${YmSystem.noteName(at)}")
            if (imported.resampled) append(" · converted from ${YmPcm.rateText(imported.sourceRate.toDouble())} (above 48 kHz)")
            if (imported.downmixed) append(" · stereo mixed to mono")
            append(" · ${YmPcm.kb(free(session))} free")
            YmPcmLowering.problem(session.song.ym)?.let { append(" · $it") }
        })
    }
}
