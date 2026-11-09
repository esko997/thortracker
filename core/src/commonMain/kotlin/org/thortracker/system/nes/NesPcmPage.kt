package org.thortracker.system.nes

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.Hint
import org.thortracker.editor.MenuContext
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.input.Keymap
import org.thortracker.system.nes.NesInstrumentView.Row

/**
 * The PCM page of [NesInstrumentView] (2A03 sounds of NES + MMC5 songs): which PCM sample each key of the sound plays on
 * the MMC5 PCM lane (sample, pitch in semitones, loop; a chromatic spread for pitched instruments) and the song's PCM
 * samples (name, loop start, delete, import a `.wav`, [PcmSampleMenu]). The DPCM page's cursors serve here too:
 * [org.thortracker.editor.ViewState.dpcmKey] is the key whose mapping the rows edit, and
 * [org.thortracker.editor.ViewState.dpcmSample] the PCM sample list cursor (re-pointed when the page is shown).
 */
internal object NesPcmPage {
    /** ↑↓ while editing the pitch: an octave. */
    private const val OCTAVE = 12

    /** Spread: this many keys either side of the shown one. */
    const val SPREAD = 12

    fun normalise(v: ViewController) {
        val st = v.state
        st.dpcmKey = st.dpcmKey.coerceIn(NesSystem.noteRange)
        st.dpcmSample = st.dpcmSample.coerceIn(0, maxOf(0, v.session.song.nes.pcmSamples.size - 1))
    }

    /** Points the key at the sound's first PCM-mapped note (when the shown key has none) and the sample list at its sample. */
    fun showFirstMapping(v: ViewController) {
        val st = v.state
        val ins = v.session.song.nesInstrument(st.instrument) ?: return
        if (st.dpcmKey !in ins.pcm) ins.pcm.keys.minOrNull()?.let { st.dpcmKey = it }
        followSample(v)
        normalise(v)
    }

    private fun followSample(v: ViewController) {
        v.session.song.pcmMapping(v.state.instrument, v.state.dpcmKey)?.let { v.state.dpcmSample = it.sample }
    }

    /** Plain ←→ on rows that move within the page; false lets the view handle it (slot, name, page). */
    fun direction(v: ViewController, row: Row, dx: Int): Boolean {
        val st = v.state
        when (row) {
            Row.PCM_KEY -> { st.dpcmKey = (st.dpcmKey + dx).coerceIn(NesSystem.noteRange); followSample(v) }
            Row.PCM_SAMPLES -> st.dpcmSample = (st.dpcmSample + dx).coerceIn(0, maxOf(0, v.session.song.nes.pcmSamples.size - 1))
            Row.PCM_NAME -> st.textPos = (st.textPos + dx).coerceIn(0, Pcm.MAX_NAME - 1)
            else -> return false
        }
        return true
    }

    private fun needsMapping(v: ViewController): Boolean {
        if (v.session.song.pcmMapping(v.state.instrument, v.state.dpcmKey) != null) return false
        v.session.note("${NesSystem.noteName(v.state.dpcmKey)} plays no PCM sample yet: choose one on the Sample row")
        return true
    }

    fun adjust(v: ViewController, row: Row, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val key = st.dpcmKey
        val samples = session.song.nes.pcmSamples
        val keyName = NesSystem.noteName(key)
        when (row) {
            Row.PCM_KEY -> {
                val mapped = session.song.nesInstrument(slot)?.pcm?.keys?.sorted().orEmpty()
                st.dpcmKey = when {
                    dy != 0 -> (key + 12 * dy).coerceIn(NesSystem.noteRange)
                    dx > 0 -> mapped.firstOrNull { it > key } ?: return session.note("No PCM-mapped key above $keyName")
                    else -> mapped.lastOrNull { it < key } ?: return session.note("No PCM-mapped key below $keyName")
                }
                followSample(v)
            }
            Row.PCM_SAMPLE -> {
                if (samples.isEmpty()) return session.note("The song has no PCM samples yet: import one on the Add row")
                val cur = session.song.pcmMapping(slot, key)
                val next = ((cur?.sample ?: -1) + 1 + dx + 4 * dy).mod(samples.size + 1) - 1
                if (next < 0) session.setPcmMapping(slot, key, null, "Unmap PCM $keyName")
                else {
                    session.setPcmMapping(slot, key, (cur ?: PcmMapping(next)).copy(sample = next), "Map PCM $keyName")
                    st.dpcmSample = next
                }
            }
            Row.PCM_PITCH -> if (!needsMapping(v)) session.editPcmMapping(slot, key, "Set PCM $keyName pitch") {
                it.copy(pitch = (it.pitch + dx + OCTAVE * dy).coerceIn(Pcm.PITCH))
            }
            Row.PCM_LOOP -> if (!needsMapping(v)) session.editPcmMapping(slot, key, "Set PCM $keyName loop") { it.copy(loop = !it.loop) }
            Row.PCM_SAMPLES -> st.dpcmSample = (st.dpcmSample + dx + dy).coerceIn(0, maxOf(0, samples.size - 1))
            Row.PCM_NAME -> session.editPcmSample(st.dpcmSample, "Rename PCM sample") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, Pcm.MAX_NAME))
            }
            Row.PCM_LOOP_START -> session.editPcmSample(st.dpcmSample, "Set PCM loop start") {
                // ←→ a hundredth of the sample, ↑↓ a tenth.
                val step = maxOf(1, it.data.size / 100)
                it.copy(loopStart = (it.loopStart + step * dx + 10 * step * dy).coerceIn(0, it.data.size - 1))
            }
            else -> Unit
        }
    }

    fun editTap(v: ViewController, row: Row) {
        val st = v.state
        val session = v.session
        val key = st.dpcmKey
        val keyName = NesSystem.noteName(key)
        when (row) {
            Row.PCM_LOOP -> if (!needsMapping(v)) session.editPcmMapping(st.instrument, key, "Set PCM $keyName loop") { it.copy(loop = !it.loop) }
            Row.PCM_SAMPLE, Row.PCM_SAMPLES -> {
                val sample = session.song.nes.pcmSamples.getOrNull(st.dpcmSample)
                    ?: return session.note("The song has no PCM samples yet: import one on the Add row")
                val cur = session.song.pcmMapping(st.instrument, key)
                if (cur?.sample == st.dpcmSample) return session.note("$keyName already plays ${sample.name}")
                session.setPcmMapping(st.instrument, key, (cur ?: PcmMapping(st.dpcmSample)).copy(sample = st.dpcmSample), "Map PCM $keyName")
                session.note(pcmMappingText(session.song, key, session.song.pcmMapping(st.instrument, key)!!))
            }
            Row.PCM_SPREAD -> {
                if (needsMapping(v)) return
                val added = session.spreadPcmMapping(st.instrument, key, SPREAD, SPREAD)
                session.note(if (added.isEmpty()) "Every key within an octave of $keyName already plays a PCM sample"
                else "Mapped ${added.size} keys ${NesSystem.noteName(added.first())}-${NesSystem.noteName(added.last())} chromatically from $keyName")
            }
            Row.PCM_ADD -> PcmSampleMenu.open(session, st.instrument, key)
            else -> Unit
        }
    }

    fun clear(v: ViewController, row: Row) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val key = st.dpcmKey
        val keyName = NesSystem.noteName(key)
        when (row) {
            Row.PCM_KEY, Row.PCM_SAMPLE -> if (session.song.pcmMapping(slot, key) != null) session.setPcmMapping(slot, key, null, "Unmap PCM $keyName")
            Row.PCM_PITCH -> session.editPcmMapping(slot, key, "Set PCM $keyName pitch") { it.copy(pitch = 0) }
            Row.PCM_LOOP -> session.editPcmMapping(slot, key, "Set PCM $keyName loop") { it.copy(loop = false) }
            Row.PCM_SAMPLES -> {
                val name = session.song.nes.pcmSamples.getOrNull(st.dpcmSample)?.name ?: return
                val removed = session.deletePcmSample(st.dpcmSample)
                normalise(v)
                session.note("Deleted PCM sample $name" + if (removed > 0) " and the $removed key${if (removed == 1) "" else "s"} that played it" else "")
            }
            Row.PCM_NAME -> session.editPcmSample(st.dpcmSample, "Rename PCM sample") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.PCM_LOOP_START -> session.editPcmSample(st.dpcmSample, "Set PCM loop start") { it.copy(loopStart = 0) }
            else -> Unit
        }
    }

    /** Plays the shown key on the MMC5 PCM lane, as a pattern note with this sound would (held: a loop sustains). */
    fun audition(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val m = song.pcmMapping(st.instrument, st.dpcmKey)
        if (m == null) { v.session.note("${NesSystem.noteName(st.dpcmKey)} plays no PCM sample in sound ${hex2(st.instrument)}"); return }
        v.startAudition(NesRules.MMC5_PCM, st.dpcmKey, st.instrument)
        v.session.note(pcmMappingText(song, st.dpcmKey, m))
    }

    fun hints(v: ViewController, row: Row, h: MutableList<Hint>) {
        val clear = v.k(Action.CLEAR)
        val edit = v.k(Action.EDIT)
        when (row) {
            Row.PCM_KEY -> { h += Hint(v.lr(), "Key"); h += Hint(v.editLr(), "Mapped keys"); h += Hint(v.editUd(), "Octave"); h += Hint(clear, "Unmap key") }
            Row.PCM_SAMPLE -> { h += Hint(v.editLr(), "Sample"); h += Hint(edit, "Map listed sample"); h += Hint(clear, "Unmap key") }
            Row.PCM_PITCH -> { h += Hint(v.editLr(), "Pitch ±1 st (${v.ud()} octave)"); h += Hint(clear, "Pitch 0") }
            Row.PCM_LOOP -> h += Hint(edit, "Loop on / off")
            Row.PCM_SPREAD -> h += Hint(edit, "Map an octave either way")
            Row.PCM_SAMPLES -> { h += Hint(v.lr(), "Sample"); h += Hint(edit, "Map to key"); h += Hint(clear, "Delete sample") }
            Row.PCM_NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(clear, "Delete letter") }
            Row.PCM_LOOP_START -> { h += Hint(v.editLr(), "Loop start ±1% (${v.ud()} ±10%)"); h += Hint(clear, "Whole sample") }
            Row.PCM_ADD -> h += Hint(edit, "Import a .wav file")
            else -> Unit
        }
        if (row == Row.PCM_SAMPLE || row == Row.PCM_PITCH || row == Row.PCM_LOOP || row == Row.PCM_SPREAD || row == Row.PCM_LOOP_START ||
            row == Row.PCM_ADD) h += Hint(v.lr(), "Sound slot")
    }

    fun helpRows(keys: Keymap): List<Pair<String, String>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        return listOf(
            "Page" to "NES + MMC5 songs: the last page of a 2A03 sound: which PCM sample each key plays on the MMC5 PCM lane " +
                "(Thor Tracker's own sample player on the MMC5's 8-bit DAC; FamiStudio has no MMC5 PCM). Its DPCM page is separate",
            "Key" to "$lr: the key whose mapping the rows below show; ${keys.held(Action.EDIT, lr)} jumps between mapped keys, ${keys.held(Action.EDIT, ud)} an octave; $clear unmaps it",
            "Sample" to "${keys.held(Action.EDIT, lr)}: the PCM sample the key plays (or none); $edit maps the sample selected in the list",
            "Pitch" to "Semitones up or down from the sample's own rate (${Pcm.PITCH.first}..+${Pcm.PITCH.last}): drums keep 0, pitched sounds " +
                "follow the keys",
            "Loop" to "Repeat from the sample's loop start until the next note, OFF or a cut",
            "Spread" to "$edit: map the shown key's sample to every free key an octave either way, each a semitone apart: one sample becomes a playable instrument",
            "Samples" to "$lr: choose; $edit maps it to the key; $clear deletes it with the keys that played it",
            "Loop start" to "Where a looping key goes back to: 0 = the whole sample, later skips an attack",
            "Add" to "$edit: import a .wav (mono or stereo, 8-32 bit or float). Rates up to 48 kHz are kept; higher ones are " +
                "converted to 48 kHz (the import says so). Samples become unsigned 8-bit: the MMC5's DAC ignores \$00, so the very " +
                "lowest level becomes 1",
            "Memory" to "PCM samples share ${Pcm.kb(Pcm.MEMORY)} (at most ${Pcm.MAX_SAMPLES}); a file that does not fit is not " +
                "imported rather than cut. The PCM lane plays in the app and in WAV exports only: .nsf and FamiStudio text refuse it",
        )
    }
}

/**
 * Importing MMC5 PCM samples to sound [slot] from the PCM page's Add row: a `.wav` file converted on the device
 * ([Pcm.fromWav]) and mapped at the shown key (or the next free key up), at pitch 0.
 */
internal object PcmSampleMenu {
    private const val MAX_FILE = 32 * 1024 * 1024

    fun free(session: EditorSession): Int = Pcm.MEMORY - Pcm.stored(session.song.nes)

    fun open(session: EditorSession, slot: Int, key: Int) = session.requestMenu { menu ->
        val free = free(session)
        if (session.song.nes.pcmSamples.size >= Pcm.MAX_SAMPLES) return@requestMenu menu.error("The song holds ${Pcm.MAX_SAMPLES} PCM samples; delete one first")
        menu.pickFile("Import PCM sample (${Pcm.kb(maxOf(0, free))} free)", listOf(".wav"), MAX_FILE) { name, _, bytes -> import(session, menu, slot, key, name, bytes) }
    }

    private fun import(session: EditorSession, menu: MenuContext, slot: Int, key: Int, name: String, bytes: ByteArray) {
        val imported = try { Pcm.fromWav(bytes, name, free(session)) } catch (e: IllegalArgumentException) { return menu.error("Not imported: ${e.message}") }
        val sample = imported.sample
        val at = session.addAndMapPcmSample(slot, sample, key, "Import PCM sample ${sample.name}")
            ?: return menu.error("No free PCM key from ${NesSystem.noteName(key)} up in sound ${hex2(slot)}")
        menu.status(buildString {
            append("${sample.name}: ${sample.data.size} bytes, ${Pcm.rateText(sample.rate.toDouble())}, ${pcmSeconds(sample)} on ${NesSystem.noteName(at)}")
            if (imported.resampled) append(" · converted from ${Pcm.rateText(imported.sourceRate.toDouble())} (above 48 kHz)")
            if (imported.raised > 0) append(" · ${imported.raised} lowest samples raised to 1 (the DAC ignores \$00)")
            append(" · ${Pcm.kb(free(session))} free")
        })
    }
}
