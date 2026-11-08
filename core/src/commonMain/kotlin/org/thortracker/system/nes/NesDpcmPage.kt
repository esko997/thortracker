package org.thortracker.system.nes

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.input.Keymap
import org.thortracker.system.nes.NesInstrumentView.Row

/**
 * The DPCM page of [NesInstrumentView]: which sample each key of the sound plays (FamiStudio's DPCM instrument
 * mappings: sample, pitch 0-15, loop, initial level) and the song's samples (name, initial level, delete, add from the
 * starter kit or a file, [NesSampleMenu]). [org.thortracker.editor.ViewState.dpcmKey] is the key whose mapping the
 * rows edit; [org.thortracker.editor.ViewState.dpcmSample] the sample list cursor.
 */
internal object NesDpcmPage {
    private const val COARSE_LEVEL = 8

    fun normalise(v: ViewController) {
        val st = v.state
        st.dpcmKey = st.dpcmKey.coerceIn(NesSystem.noteRange)
        st.dpcmSample = st.dpcmSample.coerceIn(0, maxOf(0, v.session.song.nes.samples.size - 1))
    }

    /** Points the key at the sound's first mapped note (when the shown key has none) and the sample list at its sample. */
    fun showFirstMapping(v: ViewController) {
        val st = v.state
        val ins = v.session.song.nesInstrument(st.instrument) ?: return
        if (st.dpcmKey !in ins.dpcm) ins.dpcm.keys.minOrNull()?.let { st.dpcmKey = it }
        followSample(v)
    }

    /** The sample list follows the shown key's mapping. */
    private fun followSample(v: ViewController) {
        v.session.song.dpcmMapping(v.state.instrument, v.state.dpcmKey)?.let { v.state.dpcmSample = it.sample }
    }

    /** Plain ←→ on rows that move within the page; false lets the view handle it (slot, name, page). */
    fun direction(v: ViewController, row: Row, dx: Int): Boolean {
        val st = v.state
        when (row) {
            Row.KEY -> { st.dpcmKey = (st.dpcmKey + dx).coerceIn(NesSystem.noteRange); followSample(v) }
            Row.SAMPLES -> st.dpcmSample = (st.dpcmSample + dx).coerceIn(0, maxOf(0, v.session.song.nes.samples.size - 1))
            Row.SAMPLE_NAME -> st.textPos = (st.textPos + dx).coerceIn(0, DpcmSample.MAX_NAME - 1)
            else -> return false
        }
        return true
    }

    private fun needsMapping(v: ViewController): Boolean {
        if (v.session.song.dpcmMapping(v.state.instrument, v.state.dpcmKey) != null) return false
        v.session.note("${NesSystem.noteName(v.state.dpcmKey)} plays no sample yet: choose one on the Sample row")
        return true
    }

    fun adjust(v: ViewController, row: Row, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val key = st.dpcmKey
        val samples = session.song.nes.samples
        val keyName = NesSystem.noteName(key)
        when (row) {
            Row.KEY -> {
                // ←→ jump to the sound's previous / next mapped key, ↑↓ an octave.
                val mapped = session.song.nesInstrument(slot)?.dpcm?.keys?.sorted().orEmpty()
                st.dpcmKey = when {
                    dy != 0 -> (key + 12 * dy).coerceIn(NesSystem.noteRange)
                    dx > 0 -> mapped.firstOrNull { it > key } ?: return session.note("No mapped key above $keyName")
                    else -> mapped.lastOrNull { it < key } ?: return session.note("No mapped key below $keyName")
                }
                followSample(v)
            }
            Row.SAMPLE -> {
                if (samples.isEmpty()) return session.note("The song has no samples yet: add some on the Add row")
                // Positions: no sample, then every sample of the song.
                val cur = session.song.dpcmMapping(slot, key)
                val next = ((cur?.sample ?: -1) + 1 + dx + 4 * dy).mod(samples.size + 1) - 1
                if (next < 0) session.setDpcmMapping(slot, key, null, "Unmap $keyName")
                else {
                    session.setDpcmMapping(slot, key, (cur ?: DpcmMapping(next)).copy(sample = next), "Map $keyName")
                    st.dpcmSample = next
                }
            }
            Row.PITCH -> if (!needsMapping(v)) session.editDpcmMapping(slot, key, "Set $keyName pitch") {
                it.copy(pitch = (it.pitch + dx + 4 * dy).coerceIn(DpcmMapping.PITCH))
            }
            Row.SAMPLE_LOOP -> if (!needsMapping(v)) session.editDpcmMapping(slot, key, "Set $keyName loop") { it.copy(loop = !it.loop) }
            Row.LEVEL -> if (!needsMapping(v)) session.editDpcmMapping(slot, key, "Set $keyName level") {
                it.copy(dmcInitial = (it.dmcInitial + dx + COARSE_LEVEL * dy).coerceIn(-1, DpcmSample.DMC_INITIAL.last))
            }
            Row.SAMPLES -> st.dpcmSample = (st.dpcmSample + dx + dy).coerceIn(0, maxOf(0, samples.size - 1))
            Row.SAMPLE_NAME -> session.editDpcmSample(st.dpcmSample, "Rename sample") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, DpcmSample.MAX_NAME))
            }
            Row.SAMPLE_LEVEL -> session.editDpcmSample(st.dpcmSample, "Set sample level") {
                it.copy(dmcInitial = (it.dmcInitial + dx + COARSE_LEVEL * dy).coerceIn(DpcmSample.DMC_INITIAL))
            }
            else -> Unit
        }
    }

    fun editTap(v: ViewController, row: Row) {
        val st = v.state
        val session = v.session
        val key = st.dpcmKey
        when (row) {
            Row.SAMPLE_LOOP -> if (!needsMapping(v)) session.editDpcmMapping(st.instrument, key, "Set ${NesSystem.noteName(key)} loop") { it.copy(loop = !it.loop) }
            Row.SAMPLE, Row.SAMPLES -> {
                val sample = session.song.nes.samples.getOrNull(st.dpcmSample) ?: return session.note("The song has no samples yet: add some on the Add row")
                val cur = session.song.dpcmMapping(st.instrument, key)
                if (cur?.sample == st.dpcmSample) return session.note("${NesSystem.noteName(key)} already plays ${sample.name}")
                session.setDpcmMapping(st.instrument, key, (cur ?: DpcmMapping(st.dpcmSample)).copy(sample = st.dpcmSample), "Map ${NesSystem.noteName(key)}")
                session.note(dpcmMappingText(session.song, key, session.song.dpcmMapping(st.instrument, key)!!))
            }
            Row.ADD -> NesSampleMenu.open(session, st.instrument, key)
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
            Row.KEY, Row.SAMPLE -> if (session.song.dpcmMapping(slot, key) != null) session.setDpcmMapping(slot, key, null, "Unmap $keyName")
            Row.PITCH -> session.editDpcmMapping(slot, key, "Set $keyName pitch") { it.copy(pitch = DpcmMapping.PITCH.last) }
            Row.SAMPLE_LOOP -> session.editDpcmMapping(slot, key, "Set $keyName loop") { it.copy(loop = false) }
            Row.LEVEL -> session.editDpcmMapping(slot, key, "Set $keyName level") { it.copy(dmcInitial = -1) }
            Row.SAMPLES -> {
                val name = session.song.nes.samples.getOrNull(st.dpcmSample)?.name ?: return
                val removed = session.deleteDpcmSample(st.dpcmSample)
                normalise(v)
                session.note("Deleted sample $name" + if (removed > 0) " and the $removed key${if (removed == 1) "" else "s"} that played it" else "")
            }
            Row.SAMPLE_NAME -> session.editDpcmSample(st.dpcmSample, "Rename sample") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.SAMPLE_LEVEL -> session.editDpcmSample(st.dpcmSample, "Set sample level") { it.copy(dmcInitial = DpcmSample.DEFAULT_DMC_INITIAL) }
            else -> Unit
        }
    }

    /** Plays the shown key on the DPCM channel, as a pattern note with this sound would. */
    fun audition(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val m = song.dpcmMapping(st.instrument, st.dpcmKey)
        if (m == null) { v.session.note("${NesSystem.noteName(st.dpcmKey)} plays no sample in sound ${hex2(st.instrument)}"); return }
        v.startAudition(NesRules.DPCM, st.dpcmKey, st.instrument)
        v.session.note(dpcmMappingText(song, st.dpcmKey, m))
    }

    fun hints(v: ViewController, row: Row, h: MutableList<Hint>) {
        val clear = v.k(Action.CLEAR)
        val edit = v.k(Action.EDIT)
        when (row) {
            Row.KEY -> { h += Hint(v.lr(), "Key"); h += Hint(v.editLr(), "Mapped keys"); h += Hint(v.editUd(), "Octave"); h += Hint(clear, "Unmap key") }
            Row.SAMPLE -> { h += Hint(v.editLr(), "Sample"); h += Hint(edit, "Map listed sample"); h += Hint(clear, "Unmap key") }
            Row.PITCH -> { h += Hint(v.editLr(), "Pitch ±1 (${v.ud()} ±4)"); h += Hint(clear, "Pitch F") }
            Row.SAMPLE_LOOP -> h += Hint(edit, "Loop on / off")
            Row.LEVEL -> { h += Hint(v.editLr(), "Level ±2 (${v.ud()} ±16)"); h += Hint(clear, "Sample's own") }
            Row.SAMPLES -> { h += Hint(v.lr(), "Sample"); h += Hint(edit, "Map to key"); h += Hint(clear, "Delete sample") }
            Row.SAMPLE_NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(clear, "Delete letter") }
            Row.SAMPLE_LEVEL -> { h += Hint(v.editLr(), "Level ±2 (${v.ud()} ±16)"); h += Hint(clear, "Level 64") }
            Row.ADD -> h += Hint(edit, "Starter kit or file")
            else -> Unit
        }
        if (row == Row.SAMPLE || row == Row.PITCH || row == Row.SAMPLE_LOOP || row == Row.LEVEL || row == Row.SAMPLE_LEVEL || row == Row.ADD) h += Hint(v.lr(), "Sound slot")
    }

    fun helpRows(keys: Keymap): List<Pair<String, String>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        return listOf(
            "Page" to "The last page of a sound: which sample each key plays on the DPCM channel (FamiStudio's DPCM instrument). " +
                "On DPCM a note picks its sound's sample for that key; envelopes and the other effects do not apply",
            "Key" to "$lr: the key whose mapping the rows below show; ${keys.held(Action.EDIT, lr)} jumps between mapped keys, ${keys.held(Action.EDIT, ud)} an octave; $clear unmaps it",
            "Sample" to "${keys.held(Action.EDIT, lr)}: the sample the key plays (or none); $edit maps the sample selected in the list",
            "Pitch" to "DMC rate 0-F (F = 33.1 kHz, 0 = 4.2 kHz). A sample plays at its own speed at the rate it was converted for",
            "Loop" to "Repeat the sample until the next note or OFF",
            "Level" to "Initial DMC output level (\$4011) when the note starts: the sample's own, or set here. Zxx sets it from a pattern row",
            "Samples" to "$lr: choose; $edit maps it to the key; $clear deletes it with the keys that played it. Each shows its size and waveform",
            "Add" to "$edit: the starter kit (kick, snare, hat, two toms, bass on General MIDI drum keys in the shown key's octave; made in code, free to use) or a .wav or .dmc file. " +
                "A .wav is converted on the device at the rate you choose (higher is clearer but larger)",
            "Memory" to "Samples share \$C000-\$FFF9 (16 KB, 64-byte aligned), at most 4081 bytes each; Help → Check shows what is used",
        )
    }
}
