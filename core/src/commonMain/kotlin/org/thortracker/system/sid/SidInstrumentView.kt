package org.thortracker.system.sid

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The SID instrument editor: the one shared bank, the selected sound's name, the voice Listen plays on, and pages:
 * the envelope (ADSR, presets), the wave, pulse and filter programs edited row by row, vibrato and hard restart, and
 * the song-wide tables as the player reads them (read-only; programs are where they are edited).
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the [Page]),
 * [ViewState.envStep] (program row 0..size, size = append; on the Tables page the table row 0..254),
 * [ViewState.programField] (the program row's column), [ViewState.rawTable] and [ViewState.listenChannel].
 * Holding Edit on a sound setting sounds the instrument and every change restarts it, so edits are heard as made.
 */
object SidInstrumentView : SystemView() {
    enum class Page(val label: String, val table: Int) {
        ENVELOPE("Envelope", -1), WAVE("Wave", SidTables.WAVE), PULSE("Pulse", SidTables.PULSE),
        FILTER("Filter", SidTables.FILTER), START("Vibrato·HR", -1), TABLES("Tables", -1),
    }

    enum class Row {
        SLOT, NAME, LISTEN, PAGE,
        PRESET, ATTACK, DECAY, SUSTAIN, RELEASE,
        LENGTH, STEPS,
        RESTART, GATE_TIMER, FIRST_WAVE, VIB_DELAY, VIB_SPEED, VIB_DEPTH, VIB_MODE,
        TABLE, RAW,
    }

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.PAGE)
    private val ROWS = mapOf(
        Page.ENVELOPE to HEAD + listOf(Row.PRESET, Row.ATTACK, Row.DECAY, Row.SUSTAIN, Row.RELEASE),
        Page.WAVE to HEAD + listOf(Row.LENGTH, Row.STEPS),
        Page.PULSE to HEAD + listOf(Row.LENGTH, Row.STEPS),
        Page.FILTER to HEAD + listOf(Row.LENGTH, Row.STEPS),
        Page.START to HEAD + listOf(Row.RESTART, Row.GATE_TIMER, Row.FIRST_WAVE, Row.VIB_DELAY, Row.VIB_SPEED, Row.VIB_DEPTH, Row.VIB_MODE),
        Page.TABLES to listOf(Row.SLOT, Row.NAME, Row.PAGE, Row.TABLE, Row.RAW),
    )

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = setOf(Row.PRESET, Row.ATTACK, Row.DECAY, Row.SUSTAIN, Row.RELEASE, Row.LENGTH, Row.STEPS,
        Row.RESTART, Row.GATE_TIMER, Row.FIRST_WAVE, Row.VIB_DELAY, Row.VIB_SPEED, Row.VIB_DEPTH, Row.VIB_MODE)

    /** Coarse steps (↑↓ while editing, Clear+direction on lists). */
    private const val COARSE = 8

    /** First-frame waveforms worth stepping through first: GoatTracker's usual ones, then gated waveforms. */
    val FIRST_WAVES = listOf(0x09, 0x00, 0xFF, 0xFE, 0x08, 0x81, 0x41, 0x21, 0x11)

    /** SID attack times (ms) per attack value 0-F; decay and release take 3× as long. */
    val ATTACK_MS = intArrayOf(2, 8, 16, 24, 38, 56, 68, 80, 100, 250, 500, 800, 1000, 3000, 5000, 8000)

    fun page(state: ViewState): Page = Page.entries[state.envelope.coerceIn(0, Page.entries.size - 1)]

    fun rows(state: ViewState): List<Row> = ROWS.getValue(page(state))

    fun row(state: ViewState): Row = rows(state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    fun sound(song: Song, state: ViewState): SidInstrument? = song.sidInstrument(state.instrument)

    /** The program the page shows, or null on pages without one. */
    fun program(song: Song, state: ViewState): SidProgram? = page(state).table.takeIf { it >= 0 }?.let { t -> sound(song, state)?.program(t) }

    /** The furthest program row the cursor reaches: the append position while there is room. */
    fun lastStep(p: SidProgram): Int = minOf(p.rows.size, SidPrograms.MAX_ROWS - 1)

    /** The editable columns of the program row under the cursor (none at the append position). */
    fun fields(song: Song, state: ViewState): List<SidPrograms.Field> {
        val p = program(song, state) ?: return emptyList()
        val r = p.rows.getOrNull(state.envStep) ?: return emptyList()
        return SidPrograms.fields(page(state).table, r, p.rows.size)
    }

    /** "1.5 s", "24 ms" */
    fun msText(ms: Int): String = if (ms >= 1000) "${NumberText.fixed(ms / 1000.0, if (ms % 1000 == 0) 0 else 1)} s" else "$ms ms"

    fun firstWaveText(w: Int): String = when (w) {
        0x09 -> "09  test + gate: a clean, hard start (usual)"
        0x00 -> "00  keep the waveform (smooth, may click)"
        0xFF -> "FF  keep the waveform, gate on"
        0xFE -> "FE  keep the waveform, gate off"
        else -> "${NumberText.hex(w, 2)}  ${SidTables.waveformName(w)}"
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        st.instrument = st.instrument.coerceIn(1, SidSystem.SLOTS)
        st.envelope = st.envelope.coerceIn(0, Page.entries.size - 1)
        st.instField = st.instField.coerceIn(0, rows(st).size - 1)
        st.listenChannel = st.listenChannel.coerceIn(0, v.session.song.system.channels.size - 1)
        st.rawTable = st.rawTable.coerceIn(0, SidTables.NAMES.size - 1)
        val p = program(v.session.song, st)
        st.envStep = when {
            page(st) == Page.TABLES -> st.envStep.coerceIn(0, SidTables.ROWS - 1)
            p != null -> st.envStep.coerceIn(0, lastStep(p))
            else -> 0
        }
        st.programField = st.programField.coerceIn(0, maxOf(0, fields(v.session.song, st).size - 1))
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, SidSystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        session.select(session.selection.copy(bank = 0, instrument = v.state.instrument, context = ViewKind.KEYBOARD))
    }

    /** Shows page [index] (wrapping), keeping the cursor on the same kind of row where the new page has it. */
    fun showPage(v: ViewController, index: Int) {
        val st = v.state
        val row = row(st)
        st.envelope = index.mod(Page.entries.size)
        val rows = rows(st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.PAGE)
        st.envStep = 0
        st.programField = 0
    }

    /** Moves to another slot; Listen moves to the voice that slot plays on most. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, SidSystem.SLOTS)
        if (slot == st.instrument) return
        st.instrument = slot
        mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        program(v.session.song, st)?.let { st.envStep = st.envStep.coerceIn(0, lastStep(it)) }
        publish(v)
    }

    /** The voice whose arranged rows use sound [slot] most, or null when none does. */
    fun mostUsedChannel(song: Song, slot: Int): Int? {
        val counts = IntArray(song.system.channels.size)
        for (pat in song.orders.toSet()) {
            val tracks = song.patterns[pat].tracks
            for (ch in counts.indices) for (cell in tracks[ch]) if (cell.instrument == slot) counts[ch]++
        }
        val best = counts.indices.maxBy { counts[it] }
        return if (counts[best] > 0) best else null
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val rows = rows(st)
        val row = row(st)
        val song = v.session.song
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            row == Row.STEPS && v.clearHeld -> { v.useClear(); program(song, st)?.let { st.envStep = (st.envStep - COARSE * dy + COARSE * dx).coerceIn(0, lastStep(it)) } }
            row == Row.RAW && v.clearHeld -> { v.useClear(); st.envStep = (st.envStep - COARSE * dy + COARSE * dx).coerceIn(0, SidTables.ROWS - 1) }
            row == Row.STEPS && dy != 0 -> {
                val p = program(song, st) ?: return
                if (dy > 0 && st.envStep == 0) { st.instField = rows.indexOf(Row.STEPS) - 1; return }
                st.envStep = (st.envStep - dy).coerceIn(0, lastStep(p))
                st.programField = st.programField.coerceIn(0, maxOf(0, fields(song, st).size - 1))
            }
            row == Row.RAW && dy != 0 -> {
                if (dy > 0 && st.envStep == 0) { st.instField = rows.indexOf(Row.RAW) - 1; return }
                st.envStep = (st.envStep - dy).coerceIn(0, SidTables.ROWS - 1)
            }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(0, rows.size - 1)
                st.textPos = 0
                if (row(st) == Row.STEPS || row(st) == Row.RAW) st.envStep = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row == Row.TABLE || row == Row.RAW -> st.rawTable = (st.rawTable + dx).mod(SidTables.NAMES.size)
            row == Row.STEPS -> st.programField = (st.programField + dx).coerceIn(0, maxOf(0, fields(song, st).size - 1))
            else -> moveSlot(v, dx)
        }
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val changed = when (row(st)) {
            Row.SLOT -> { moveSlot(v, dx - COARSE * dy); false }
            Row.NAME -> session.editSidInstrument(slot, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, SidInstrument.NAME_MAX))
            }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + dx + dy).mod(session.song.system.channels.size); false }
            Row.PAGE -> { showPage(v, st.envelope + dx + dy); false }
            Row.PRESET -> applyPreset(v, dx + dy)
            Row.ATTACK -> session.editSidInstrument(slot, "Set attack") { it.copy(attack = (it.attack + dx + 4 * dy).coerceIn(SidInstrument.NIBBLE)) }
            Row.DECAY -> session.editSidInstrument(slot, "Set decay") { it.copy(decay = (it.decay + dx + 4 * dy).coerceIn(SidInstrument.NIBBLE)) }
            Row.SUSTAIN -> session.editSidInstrument(slot, "Set sustain") { it.copy(sustain = (it.sustain + dx + 4 * dy).coerceIn(SidInstrument.NIBBLE)) }
            Row.RELEASE -> session.editSidInstrument(slot, "Set release") { it.copy(release = (it.release + dx + 4 * dy).coerceIn(SidInstrument.NIBBLE)) }
            Row.LENGTH -> {
                val table = page(st).table
                session.editSidProgram(slot, table, "Set ${SidTables.NAMES[table].lowercase()} program length") {
                    SidPrograms.withLength(table, it, it.size + dx + COARSE * dy)
                }
            }
            Row.STEPS -> adjustStep(v, dx, dy)
            Row.RESTART -> session.editSidInstrument(slot, "Set hard restart") {
                it.copy(restart = SidRestart.entries[(it.restart.ordinal + dx + dy).mod(SidRestart.entries.size)])
            }
            Row.GATE_TIMER -> session.editSidInstrument(slot, "Set gate timer") { it.copy(gateTimer = (it.gateTimer + dx + 4 * dy).coerceIn(SidInstrument.GATE_TIMER)) }
            Row.FIRST_WAVE -> session.editSidInstrument(slot, "Set first wave") {
                val i = FIRST_WAVES.indexOf(it.firstWave)
                val w = if (dx != 0) FIRST_WAVES[if (i < 0) 0 else (i + dx).mod(FIRST_WAVES.size)] else (it.firstWave + dy).coerceIn(0, 0xFF)
                it.copy(firstWave = w)
            }
            Row.VIB_DELAY -> session.editSidInstrument(slot, "Set vibrato delay") {
                val vib = it.vibrato
                val delay = (vib.delay + dx + 16 * dy).coerceIn(SidVibrato.DELAY)
                // Switching vibrato on starts from a gentle, note-independent wobble.
                it.copy(vibrato = if (!vib.active && delay > 0 && vib.speed == 0) SidVibrato(delay, 3, 3, true) else vib.copy(delay = delay))
            }
            Row.VIB_SPEED -> session.editSidInstrument(slot, "Set vibrato speed") {
                it.copy(vibrato = it.vibrato.copy(speed = (it.vibrato.speed + dx + 8 * dy).coerceIn(SidVibrato.SPEED)))
            }
            Row.VIB_DEPTH -> session.editSidInstrument(slot, "Set vibrato depth") {
                val vib = it.vibrato
                val range = if (vib.noteIndependent) SidVibrato.SHIFT else SidVibrato.DEPTH
                it.copy(vibrato = vib.copy(depth = (vib.depth + dx + (if (vib.noteIndependent) 1 else 16) * dy).coerceIn(range)))
            }
            Row.VIB_MODE -> toggleVibratoMode(v)
            Row.TABLE, Row.RAW -> { st.rawTable = (st.rawTable + dx + dy).mod(SidTables.NAMES.size); false }
        }
        if (changed) heard(v)
    }

    private fun adjustStep(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val table = page(st).table
        val p = program(v.session.song, st) ?: return false
        val label = "Edit ${SidTables.NAMES[table].lowercase()} program"
        if (st.envStep >= p.rows.size) return appendStep(v)
        val field = fields(v.session.song, st).getOrNull(st.programField) ?: return false
        val changed = v.session.editSidProgram(st.instrument, table, label) { rows ->
            rows.mapIndexed { i, r -> if (i == st.envStep) field.step(r, dx, dy) else r }
        }
        // A step that changed kind has other columns: stay on the kind column.
        st.programField = st.programField.coerceIn(0, maxOf(0, fields(v.session.song, st).size - 1))
        return changed
    }

    private fun appendStep(v: ViewController): Boolean {
        val st = v.state
        val table = page(st).table
        val p = program(v.session.song, st) ?: return false
        if (p.rows.size >= SidPrograms.MAX_ROWS) { v.session.note("A program has at most ${SidPrograms.MAX_ROWS} rows"); return false }
        val ok = v.session.editSidProgram(st.instrument, table, "Add ${SidTables.NAMES[table].lowercase()} step") {
            it + SidPrograms.appended(table, it.lastOrNull())
        }
        program(v.session.song, st)?.let { st.envStep = (it.rows.size - 1).coerceAtLeast(0) }
        st.programField = 0
        return ok
    }

    private fun toggleVibratoMode(v: ViewController): Boolean = v.session.editSidInstrument(v.state.instrument, "Set vibrato mode") {
        val vib = it.vibrato
        val independent = !vib.noteIndependent
        it.copy(vibrato = vib.copy(noteIndependent = independent, depth = if (independent) vib.depth.coerceIn(SidVibrato.SHIFT) else vib.depth))
    }

    /** Applies the preset [delta] steps from the one the sound is (or the first), keeping nothing of the old sound. */
    private fun applyPreset(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val ins = sound(v.session.song, st) ?: return false
        val presets = SidPresets.ALL
        val current = SidPresets.indexOf(ins, st.listenChannel)
        val next = if (current < 0) (if (delta < 0) presets.size - 1 else 0) else (current + delta).mod(presets.size)
        val preset = presets[next]
        val ok = v.session.setSidInstrument(st.instrument, preset.build(st.listenChannel), "Preset ${preset.name}")
        v.session.note("${preset.name}: ${preset.description}")
        return ok
    }

    /** After an edit: a held Listen or Edit sounds the changed instrument again. */
    private fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && row(v.state) in SOUND_ROWS)) audition(v)
    }

    override fun editPress(v: ViewController) {
        if (row(v.state) in SOUND_ROWS && page(v.state) != Page.TABLES) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        val st = v.state
        val session = v.session
        val row = row(st)
        if (row in SOUND_ROWS && !v.auditionHeld) session.releaseAudition()
        if (!tap) return
        val changed = when (row) {
            Row.SLOT -> { publish(v); session.note("Sound ${EditorSession.hex2(st.instrument)} selected for entry"); false }
            Row.PRESET -> applyPreset(v, 1)
            Row.RESTART -> session.editSidInstrument(st.instrument, "Set hard restart") {
                it.copy(restart = SidRestart.entries[(it.restart.ordinal + 1) % SidRestart.entries.size])
            }
            Row.VIB_MODE -> toggleVibratoMode(v)
            Row.STEPS -> {
                val p = program(session.song, st) ?: return
                when {
                    st.envStep >= p.rows.size -> appendStep(v)
                    fields(session.song, st).getOrNull(st.programField)?.toggle == true -> adjustStep(v, 1, 0)
                    else -> false
                }
            }
            Row.RAW -> { openRaw(v); false }
            else -> false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    /** From a table row to the program that owns it, on its own page. */
    private fun openRaw(v: ViewController) {
        val st = v.state
        if (st.rawTable > SidTables.FILTER) { v.session.note("Speed rows come from vibratos and effects 1-4 and E; edit those"); return }
        val placement = SidTables.placement(v.session.song.sid, st.rawTable)
        val owner = placement.owner[st.envStep + 1]
        if (owner == 0) { v.session.note("Row ${NumberText.hex(st.envStep + 1, 2)} is free"); return }
        val index = placement.index[st.envStep + 1]
        st.instrument = owner
        st.envelope = Page.entries.first { it.table == st.rawTable }.ordinal
        st.instField = rows(st).indexOf(Row.STEPS)
        st.envStep = index
        st.programField = 0
        publish(v)
    }

    override fun clear(v: ViewController) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val blank = SidInstrument.DEFAULT
        when (row(st)) {
            Row.SLOT, Row.PRESET -> session.setSidInstrument(slot, defaultSidInstrument(slot), "Reset sound")
            Row.NAME -> session.editSidInstrument(slot, "Rename sound") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.ATTACK -> session.editSidInstrument(slot, "Reset attack") { it.copy(attack = blank.attack) }
            Row.DECAY -> session.editSidInstrument(slot, "Reset decay") { it.copy(decay = blank.decay) }
            Row.SUSTAIN -> session.editSidInstrument(slot, "Reset sustain") { it.copy(sustain = blank.sustain) }
            Row.RELEASE -> session.editSidInstrument(slot, "Reset release") { it.copy(release = blank.release) }
            Row.LENGTH -> {
                val table = page(st).table
                session.editSidProgram(slot, table, "Clear ${SidTables.NAMES[table].lowercase()} program") { emptyList() }
                st.envStep = 0
            }
            Row.STEPS -> deleteStep(v)
            Row.RESTART -> session.editSidInstrument(slot, "Set hard restart") { it.copy(restart = blank.restart) }
            Row.GATE_TIMER -> session.editSidInstrument(slot, "Reset gate timer") { it.copy(gateTimer = blank.gateTimer) }
            Row.FIRST_WAVE -> session.editSidInstrument(slot, "Reset first wave") { it.copy(firstWave = blank.firstWave) }
            Row.VIB_DELAY, Row.VIB_SPEED, Row.VIB_DEPTH, Row.VIB_MODE -> session.editSidInstrument(slot, "Vibrato off") { it.copy(vibrato = SidVibrato()) }
            Row.LISTEN, Row.PAGE, Row.TABLE, Row.RAW -> Unit
        }
    }

    private fun deleteStep(v: ViewController) {
        val st = v.state
        val table = page(st).table
        v.session.editSidProgram(st.instrument, table, "Delete ${SidTables.NAMES[table].lowercase()} step") { SidPrograms.removed(it, st.envStep) }
        program(v.session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
        st.programField = 0
    }

    private fun insertStep(v: ViewController) {
        val st = v.state
        val table = page(st).table
        val p = program(v.session.song, st) ?: return
        if (p.rows.size >= SidPrograms.MAX_ROWS) { v.session.note("A program has at most ${SidPrograms.MAX_ROWS} rows"); return }
        val row = p.rows.getOrNull(st.envStep) ?: SidPrograms.appended(table, p.rows.lastOrNull())
        v.session.editSidProgram(st.instrument, table, "Insert ${SidTables.NAMES[table].lowercase()} step") { SidPrograms.inserted(it, st.envStep, row) }
    }

    override fun audition(v: ViewController) {
        v.startAudition(v.state.listenChannel, v.defaultNote(), v.state.instrument)
    }

    /** Program pages' rows copy, cut and paste the program; the others the whole sound. */
    private fun programRow(state: ViewState) = page(state).table >= 0 && (row(state) == Row.LENGTH || row(state) == Row.STEPS)

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val table = page(st).table
        val onProgram = programRow(st)
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showPage(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onProgram) session.copySidProgram(slot, table) else session.copySidInstrument(slot)
            Action.PASTE -> session.pasteSid(slot, if (onProgram) table else -1)
            Action.CUT -> if (onProgram) {
                session.copySidProgram(slot, table)
                session.editSidProgram(slot, table, "Cut ${SidTables.NAMES[table].lowercase()} program") { emptyList() }
                st.envStep = 0
            } else {
                session.copySidInstrument(slot)
                session.setSidInstrument(slot, defaultSidInstrument(slot), "Cut sound")
            }
            Action.DUPLICATE -> {
                if (slot >= SidSystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(session.song, st) ?: return true
                session.setSidInstrument(slot + 1, src.copy(name = (src.name + "+").take(SidInstrument.NAME_MAX)), "Duplicate sound")
                st.instrument++
                publish(v)
            }
            Action.INSERT_ROW -> if (row(st) == Row.STEPS) insertStep(v) else return false
            Action.DELETE_ROW -> if (row(st) == Row.STEPS) deleteStep(v) else return false
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        val st = v.state
        val row = row(st)
        val song = v.session.song
        when (row) {
            Row.SLOT -> { h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots"); h += Hint(v.k(Action.CLEAR), "Reset sound") }
            Row.NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            Row.LISTEN -> h += Hint(v.lr(), "Voice Listen plays on")
            Row.PAGE -> h += Hint(v.lr(), "Page")
            Row.PRESET -> { h += Hint(v.editLr(), "Start from a preset"); h += Hint(v.k(Action.CLEAR), "Blank sound") }
            Row.ATTACK, Row.DECAY, Row.SUSTAIN, Row.RELEASE, Row.GATE_TIMER, Row.VIB_DELAY, Row.VIB_SPEED, Row.VIB_DEPTH -> {
                h += Hint(v.editLr(), "±1 (${v.ud()} coarse)"); h += Hint(v.k(Action.CLEAR), "Default")
            }
            Row.RESTART -> { h += Hint(v.k(Action.EDIT), "Hard restart / gate off / legato"); h += Hint(v.k(Action.CLEAR), "Hard restart") }
            Row.FIRST_WAVE -> { h += Hint(v.editLr(), "Usual first waves"); h += Hint(v.editUd(), "Any byte"); h += Hint(v.k(Action.CLEAR), "09") }
            Row.VIB_MODE -> h += Hint(v.k(Action.EDIT), "Note-independent / frequency depth")
            Row.LENGTH -> { h += Hint(v.editLr(), "Rows ±1"); h += Hint(v.k(Action.CLEAR), "No program") }
            Row.STEPS -> {
                val p = program(song, st)
                if (p != null && st.envStep >= p.rows.size) {
                    h += Hint(v.k(Action.EDIT), "Add a step")
                } else {
                    val f = fields(song, st).getOrNull(st.programField)
                    h += Hint(v.lr(), "Column")
                    if (f != null) h += Hint(if (f.toggle) v.k(Action.EDIT) else v.editLr(), if (f.toggle) "Switch ${f.label}" else "Change ${f.label.lowercase()}")
                    h += Hint(v.k(Action.CLEAR), "Delete step")
                    h += Hint(v.keys.pair(Action.INSERT_ROW, Action.DELETE_ROW, st.kind), "Insert/delete")
                }
            }
            Row.TABLE -> h += Hint(v.lr(), "Table")
            Row.RAW -> { h += Hint(v.lr(), "Table"); h += Hint(v.k(Action.EDIT), "Edit this row's program") }
        }
        if (row == Row.ATTACK || row == Row.DECAY || row == Row.SUSTAIN || row == Row.RELEASE || row == Row.RESTART || row == Row.GATE_TIMER ||
            row == Row.FIRST_WAVE || row == Row.VIB_DELAY || row == Row.VIB_SPEED || row == Row.VIB_DEPTH || row == Row.VIB_MODE ||
            row == Row.LENGTH || row == Row.PRESET) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(st.listenChannel)?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${SidSystem.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (programRow(st)) "Copy/paste program" else "Copy/paste sound")
    }

    /** Help rows for this view (heading, then key → meaning), named with the current [keys]. */
    fun helpSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val listen = keys.label(Action.AUDITION)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        return listOf(
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen voice, page, then the page's settings or program rows",
                lr to "On settings: the sound slot; on a program row: its columns",
                channels to "Pages: envelope, wave, pulse and filter programs, vibrato and hard restart, tables",
                "Listen on" to "The voice $listen plays the sound on. Holding $edit on a setting sounds the sound and every change restarts it",
                "Presets" to "Envelope page: ${keys.held(Action.EDIT, lr)} picks a starting sound (saw lead, PWM pad, filter bass, snare, kick, hi-hat…); " +
                    "filter presets route the Listen voice",
                keys.held(Action.EDIT, lr) to "Change the value (${keys.held(Action.EDIT, ud)} in bigger steps); on a program row's first column: what the step is",
                "$edit tap" to "Past the last program row: add one; on a bit (G S R T, Lo Bd Hi, V1-3): switch it; on a Tables row: edit its program",
                "$clear tap" to "Delete the program row; on settings: their default",
                keys.pair(Action.INSERT_ROW, Action.DELETE_ROW) to "Insert / delete a program row; jumps keep pointing at the same rows",
            ),
            "Envelope" to listOf(
                "ADSR" to "Attack 2 ms-8 s, decay and release 6 ms-24 s, sustain level 0-F. Release starts at OFF (key off)",
                "Hard restart" to "Before each note (gate timer ticks early) the voice is silenced with the song's hard-restart envelope, so " +
                    "every attack is clean. Gate off only skips the restart; legato keeps the envelope running",
                "First wave" to "The waveform register on the note's first tick: 09 (test + gate) gives the usual hard start",
                "Vibrato" to "After the delay (ticks), the pitch wobbles: speed = ticks per turn, depth per tick (note-independent: " +
                    "a fraction of a semitone, the same on every note)",
            ),
            "Programs" to listOf(
                "Wave" to "One row per tick: a waveform (Tri, Saw, Pulse, Noise and their combinations; gate, sync, ring, test), " +
                    "a silent row, a wait, or a command, each at the note plus semitones (arpeggios), an absolute note (drums) or the pitch kept",
                "Pulse" to "Set a width (000-FFF, 800 = square), then sweep it by a signed speed per tick for some ticks",
                "Filter" to "Set the passband (low, band, high), resonance and the voices it filters, then the cutoff and sweeps. " +
                    "The chip has one filter: the last voice to start a filter program takes it (the Pattern view marks that voice)",
                "Jump" to "Continue at a row of the same program (loops); stop ends it. Without a jump the program stops after its last row",
                "Tables" to "The song-wide wave, pulse, filter and speed tables the player reads, built from every program; read-only here",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = SidInstrumentPainter(pane)
}
