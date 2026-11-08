package org.thortracker.system.vsu

import org.thortracker.backend.VsuPlayerImage
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.system.fm.FmControls
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The Virtual Boy instrument editor, controller-only: the one bank of [VsuSystem.SLOTS] sounds, the selected sound's
 * name, the lane Listen plays on, and three pages, one row per instrument field. *Sound*: preset, wave, envelope (start
 * level, on, direction, step time, repeat), hardware length and the noise tap (Noise only). *Sweep/Mod*: Wave 5's
 * hardware sweep or modulation (a Wave 5 preset, on, function, table repeat, clock, interval, direction, shift).
 * *Pitch sweep*: the player's software sweep (start, end, speed). Fields a lane ignores are shown dimmed with the reason.
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the [Page]) and
 * [ViewState.listenChannel]. Holding Edit on a sound row sounds the instrument and every change restarts it.
 */
object VsuInstrumentView : SystemView() {
    enum class Page(val label: String) { SOUND("Sound"), WAVE5("Sweep/Mod"), PITCH("Pitch sweep") }

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), PAGE("Page"),
        PRESET("Preset"), WAVE("Wave"), LEVEL("Level"), ENVELOPE("Envelope"), DIRECTION("Direction"), INTERVAL("Step time"),
        REPEAT("Repeat"), AUTO_LENGTH("Auto length"), LENGTH("Length"), NOISE_TAP("Noise tap"),
        FM_PRESET("W5 preset"), FM_ON("Sweep/Mod"), FM_FUNCTION("Function"), FM_REPEAT("Table loop"), FM_CLOCK("Clock"),
        FM_INTERVAL("Interval"), FM_DIRECTION("Sweep dir"), FM_SHIFT("Shift"),
        SWEEP_START("Sweep start"), SWEEP_END("Sweep end"), SWEEP_SPEED("Sweep speed"),
    }

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.PAGE)

    private val ROWS = mapOf(
        Page.SOUND to HEAD + listOf(Row.PRESET, Row.WAVE, Row.LEVEL, Row.ENVELOPE, Row.DIRECTION, Row.INTERVAL, Row.REPEAT,
            Row.AUTO_LENGTH, Row.LENGTH, Row.NOISE_TAP),
        Page.WAVE5 to HEAD + listOf(Row.FM_PRESET, Row.FM_ON, Row.FM_FUNCTION, Row.FM_REPEAT, Row.FM_CLOCK, Row.FM_INTERVAL,
            Row.FM_DIRECTION, Row.FM_SHIFT),
        Page.PITCH to HEAD + listOf(Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED),
    )

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = Page.entries.flatMap { ROWS.getValue(it).drop(HEAD.size) }.toSet()

    /** On/off fields: a tap (or any Edit+direction) switches them. */
    val TOGGLES = setOf(Row.ENVELOPE, Row.DIRECTION, Row.REPEAT, Row.AUTO_LENGTH, Row.FM_ON, Row.FM_FUNCTION, Row.FM_REPEAT,
        Row.FM_CLOCK, Row.FM_DIRECTION)

    /** Rows only Wave 5 plays. */
    val WAVE5_ROWS = ROWS.getValue(Page.WAVE5).drop(HEAD.size).toSet()

    private const val COARSE = FmControls.COARSE

    const val NAME_MAX = FmControls.NAME_MAX

    fun page(state: ViewState): Page = Page.entries[state.envelope.coerceIn(0, Page.entries.size - 1)]

    fun rows(state: ViewState): List<Row> = ROWS.getValue(page(state))

    fun row(state: ViewState): Row = rows(state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    fun sound(song: Song, state: ViewState): VsuInstrument? = song.vsuInstrument(state.instrument)

    /** The note Listen plays on [lane]: the listening octave's note, raised into the wave lanes' range (D#2 up). */
    fun listenNote(v: ViewController, lane: Int): Int = v.defaultNote().coerceIn(VsuRules.noteRange(lane))

    /** Why [row] does nothing for a note on [lane] with [ins], or null when it applies there. */
    fun ignored(row: Row, lane: Int, ins: VsuInstrument): String? = when {
        row == Row.WAVE && lane == VsuRules.NOISE_LANE -> "Noise plays its shift register, not a wave"
        row == Row.NOISE_TAP && lane != VsuRules.NOISE_LANE -> "only the Noise lane has a tap"
        row in WAVE5_ROWS && row != Row.FM_PRESET && lane != VsuRules.SWEEP_LANE -> "only Wave 5 has sweep/modulation"
        row == Row.DIRECTION || row == Row.INTERVAL || row == Row.REPEAT -> if (!ins.envelope) "the envelope is off: the level holds" else null
        row == Row.LENGTH && !ins.autoLength -> "auto length is off: the note plays until OFF"
        row == Row.FM_REPEAT && (!ins.freqMod.modulation) -> "the table loops only for modulation"
        (row == Row.FM_DIRECTION || row == Row.FM_SHIFT) && ins.freqMod.modulation -> "modulation reads the table; direction and shift are the sweep's"
        else -> null
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        st.instrument = st.instrument.coerceIn(1, VsuSystem.SLOTS)
        st.envelope = st.envelope.coerceIn(0, Page.entries.size - 1)
        st.instField = st.instField.coerceIn(0, rows(st).size - 1)
        st.listenChannel = st.listenChannel.coerceIn(0, VsuPlayerImage.CHANNELS - 1)
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, VsuSystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Publishes the slot; an unpinned sibling is pointed at the wave it plays (the keyboard for Noise). */
    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        val ins = sound(session.song, v.state)
        val context = if (v.state.listenChannel == VsuRules.NOISE_LANE) ViewKind.KEYBOARD else ViewKind.WAVE
        session.select(session.selection.copy(bank = 0, instrument = v.state.instrument, wave = ins?.wave ?: session.selection.wave, context = context))
    }

    /** Shows page [index] (wrapping), keeping the cursor on the same kind of row where the new page has it. */
    fun showPage(v: ViewController, index: Int) {
        val st = v.state
        val row = row(st)
        st.envelope = index.mod(Page.entries.size)
        val rows = rows(st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.PAGE)
    }

    /** Moves to another slot; Listen moves to the lane that slot plays on most. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, VsuSystem.SLOTS)
        if (slot == st.instrument) return
        st.instrument = slot
        FmControls.mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val rows = rows(st)
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            dy != 0 -> { st.instField = (st.instField - dy).coerceIn(0, rows.size - 1); st.textPos = 0 }
            row(st) == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row(st) == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(VsuPlayerImage.CHANNELS)
            row(st) == Row.PAGE -> showPage(v, st.envelope + dx)
            else -> moveSlot(v, dx)
        }
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val d = dx + dy
        val changed = when (val row = row(st)) {
            Row.SLOT -> { moveSlot(v, dx - COARSE * dy); false }
            Row.NAME -> session.editVsuInstrument(slot, "Rename sound") { it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, NAME_MAX)) }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(VsuPlayerImage.CHANNELS); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.PRESET -> applyPreset(v, d, null)
            Row.FM_PRESET -> applyPreset(v, d, VsuPresets.Category.WAVE5)
            else -> session.editVsuInstrument(slot, "Set ${row.label.lowercase()}") { step(it, row, dx, dy) }.also { if (it) fieldNote(v) }
        }
        if (changed) heard(v)
    }

    /** [ins] with [row]'s value stepped by [dx] (fine) and [dy] (coarse); lists wrap, switches flip. */
    fun step(ins: VsuInstrument, row: Row, dx: Int, dy: Int): VsuInstrument {
        val d = dx + dy
        if (d == 0) return ins
        val fm = ins.freqMod
        return when (row) {
            Row.WAVE -> ins.copy(wave = (ins.wave + d).mod(VsuPlayerImage.WAVES))
            Row.LEVEL -> ins.copy(level = (ins.level + dx + 4 * dy).coerceIn(0, VsuPlayerImage.LEVEL_MAX))
            Row.ENVELOPE -> ins.copy(envelope = !ins.envelope)
            Row.DIRECTION -> ins.copy(grow = !ins.grow)
            Row.INTERVAL -> ins.copy(interval = (ins.interval + d).coerceIn(0, VsuPlayerImage.INTERVAL_MAX))
            Row.REPEAT -> ins.copy(repeat = !ins.repeat)
            Row.AUTO_LENGTH -> ins.copy(autoLength = !ins.autoLength)
            // Setting a length switches auto length on, so the change is heard.
            Row.LENGTH -> ins.copy(autoLength = true, length = (ins.length + dx + COARSE * dy).coerceIn(0, VsuPlayerImage.LENGTH_MAX))
            Row.NOISE_TAP -> ins.copy(noiseTap = (ins.noiseTap + d).mod(VsuPlayerImage.TAP_MAX + 1))
            Row.FM_ON -> ins.copy(freqMod = fm.copy(on = !fm.on))
            Row.FM_FUNCTION -> ins.copy(freqMod = fm.copy(modulation = !fm.modulation))
            Row.FM_REPEAT -> ins.copy(freqMod = fm.copy(repeat = !fm.repeat))
            Row.FM_CLOCK -> ins.copy(freqMod = fm.copy(slowClock = !fm.slowClock))
            // An interval on a switched-off unit switches it on, so the change is heard.
            Row.FM_INTERVAL -> (fm.interval + d).coerceIn(0, 7).let { i -> ins.copy(freqMod = fm.copy(interval = i, on = fm.on || (i > 0 && fm.interval == 0))) }
            Row.FM_DIRECTION -> ins.copy(freqMod = fm.copy(up = !fm.up))
            Row.FM_SHIFT -> ins.copy(freqMod = fm.copy(shift = (fm.shift + d).coerceIn(0, 7)))
            Row.SWEEP_START -> ins.copy(sweep = sweepWith(ins.sweep, start = (ins.sweep.start + dx + 12 * dy).coerceIn(-VsuPlayerImage.SWEEP_LIMIT, VsuPlayerImage.SWEEP_LIMIT)))
            Row.SWEEP_END -> ins.copy(sweep = sweepWith(ins.sweep, end = (ins.sweep.end + dx + 12 * dy).coerceIn(-VsuPlayerImage.SWEEP_LIMIT, VsuPlayerImage.SWEEP_LIMIT)))
            Row.SWEEP_SPEED -> ins.copy(sweep = ins.sweep.copy(speed = (ins.sweep.speed + dx + 16 * dy).coerceIn(0, 255)))
            else -> ins
        }
    }

    /** A sweep with a new start or end; setting either on a sweep without speed gives it a moderate one so it is heard. */
    private fun sweepWith(s: VsuSweep, start: Int = s.start, end: Int = s.end): VsuSweep =
        s.copy(start = start, end = end, speed = FmControls.sweepSpeed(s.speed, start, end))

    /** A status line where a change has a consequence the row cannot show: the sweep overflow, ignored fields. */
    private fun fieldNote(v: ViewController) {
        val st = v.state
        val ins = sound(v.session.song, st) ?: return
        val row = row(st)
        val lane = st.listenChannel
        ignored(row, lane, ins)?.let { v.session.note("${row.label}: $it"); return }
        if (row in WAVE5_ROWS) overflowText(ins)?.let { v.session.note(it) }
    }

    /**
     * The hardware's sweep overflow for [ins] on Wave 5: with the sweep function and direction up, notes whose register F
     * plus F >> shift passes 2047 stop at once, even with the sweep off. Null when no note is affected.
     */
    fun overflowText(ins: VsuInstrument): String? {
        val fm = ins.freqMod
        if (fm.modulation || !fm.up) return null
        val lowest = VsuPitch.lowestStoppingNote(fm.shift) ?: return null
        val off = if (fm.running) "" else " (the hardware checks it even with the sweep off)"
        return "Sweep up, shift ${fm.shift}: Wave 5 notes from ${VsuSystem.noteName(lowest)} up stop at once$off"
    }

    /** Presets [delta] steps from the one the sound is (or the first) of [category] (null: all), replacing the sound. */
    private fun applyPreset(v: ViewController, delta: Int, category: VsuPresets.Category?): Boolean {
        val st = v.state
        val presets = VsuPresets.ALL.filter { category == null || it.category == category }
        if (presets.isEmpty() || delta == 0) return false
        val ins = sound(v.session.song, st)
        val preset = presets[FmControls.presetStep(presets.indexOfFirst { it.instrument == ins }, delta, presets.size)]
        val ok = v.session.setVsuInstrument(st.instrument, preset.instrument, "Preset ${preset.name}")
        // Wave 5 and noise sounds are heard where they play.
        val fits = when (preset.category) {
            VsuPresets.Category.WAVE5 -> st.listenChannel == VsuRules.SWEEP_LANE
            VsuPresets.Category.NOISE -> st.listenChannel == VsuRules.NOISE_LANE
            VsuPresets.Category.MELODIC -> st.listenChannel != VsuRules.NOISE_LANE
        }
        if (!fits) st.listenChannel = preset.lane
        v.session.note("${preset.name} (${preset.category.label}): ${VsuSystem.summary(v.session.song, preset.instrument)}" +
            (preset.note?.let { " · voiced at ${VsuSystem.noteName(it)}" } ?: ""))
        return ok
    }

    /** After an edit: a held Listen or Edit sounds the changed instrument again. */
    private fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && row(v.state) in SOUND_ROWS)) audition(v)
    }

    override fun editPress(v: ViewController) {
        if (row(v.state) in SOUND_ROWS && sound(v.session.song, v.state) != null) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        val st = v.state
        val session = v.session
        val row = row(st)
        if (row in SOUND_ROWS && !v.auditionHeld) session.releaseAudition()
        if (!tap) return
        val slot = st.instrument
        val empty = sound(session.song, st) == null
        val changed = when {
            row == Row.SLOT -> {
                if (empty) session.setVsuInstrument(slot, defaultVsuInstrument(slot), "New sound").also { session.note("Sound ${EditorSession.hex2(slot)}: a new sound to start from") }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            row == Row.PRESET -> applyPreset(v, 1, null)
            row == Row.FM_PRESET -> applyPreset(v, 1, VsuPresets.Category.WAVE5)
            row in TOGGLES -> session.editVsuInstrument(slot, "Set ${row.label.lowercase()}") { step(it, row, 1, 0) }.also { if (it) fieldNote(v) }
            empty && row in SOUND_ROWS -> session.setVsuInstrument(slot, defaultVsuInstrument(slot), "New sound")
            else -> false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    override fun clear(v: ViewController) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val row = row(st)
        val d = defaultVsuInstrument(slot)
        when (row) {
            Row.SLOT -> session.setVsuInstrument(slot, null, "Empty sound").also { session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused") }
            Row.PRESET -> session.setVsuInstrument(slot, d, "Reset sound")
            Row.NAME -> session.editVsuInstrument(slot, "Rename sound") { it.copy(name = org.thortracker.editor.ViewController.deleteChar(it.name, st.textPos)) }
            Row.WAVE -> session.editVsuInstrument(slot, "Reset wave") { it.copy(wave = d.wave) }
            Row.LEVEL -> session.editVsuInstrument(slot, "Reset level") { it.copy(level = d.level) }
            Row.ENVELOPE, Row.DIRECTION, Row.INTERVAL, Row.REPEAT -> session.editVsuInstrument(slot, "Reset envelope") {
                it.copy(envelope = d.envelope, grow = d.grow, interval = d.interval, repeat = d.repeat)
            }
            Row.AUTO_LENGTH, Row.LENGTH -> session.editVsuInstrument(slot, "Length off") { it.copy(autoLength = false, length = 0) }
            Row.NOISE_TAP -> session.editVsuInstrument(slot, "Reset noise tap") { it.copy(noiseTap = 0) }
            Row.FM_PRESET, Row.FM_ON, Row.FM_FUNCTION, Row.FM_REPEAT, Row.FM_CLOCK, Row.FM_INTERVAL, Row.FM_DIRECTION, Row.FM_SHIFT ->
                session.editVsuInstrument(slot, "Sweep/modulation off") { it.copy(freqMod = VsuFreqMod()) }
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> session.editVsuInstrument(slot, "Pitch sweep off") { it.copy(sweep = VsuSweep.NONE) }
            Row.LISTEN, Row.PAGE -> Unit
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val ins = sound(v.session.song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        val lane = st.listenChannel
        v.startAudition(lane, listenNote(v, lane), st.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showPage(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> session.copyVsuInstrument(slot)
            Action.PASTE -> session.pasteVsuInstrument(slot)
            Action.CUT -> {
                session.copyVsuInstrument(slot)
                session.setVsuInstrument(slot, null, "Cut sound")
            }
            Action.DUPLICATE -> {
                if (slot >= VsuSystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(session.song, st) ?: return true
                session.setVsuInstrument(slot + 1, src.copy(name = (src.name + "+").take(NAME_MAX)), "Duplicate sound")
                st.instrument++
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        val st = v.state
        val row = row(st)
        val ins = sound(v.session.song, st)
        when {
            row == Row.SLOT -> {
                h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots")
                h += Hint(v.k(Action.EDIT), if (ins == null) "Make a sound here" else "Use for entry")
                h += Hint(v.k(Action.CLEAR), "Empty the slot")
            }
            row == Row.NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            row == Row.LISTEN -> h += Hint(v.lr(), "Lane Listen plays on")
            row == Row.PAGE -> h += Hint(v.lr(), "Page")
            row == Row.PRESET || row == Row.FM_PRESET -> {
                h += Hint(v.editLr(), if (row == Row.PRESET) "Start from a preset" else "Wave 5 presets"); h += Hint(v.k(Action.CLEAR), "Default sound")
            }
            row in TOGGLES -> { h += Hint(v.k(Action.EDIT), "Switch ${row.label.lowercase()}"); h += Hint(v.k(Action.CLEAR), "Default") }
            row == Row.WAVE || row == Row.NOISE_TAP -> { h += Hint(v.editLr(), "Choose"); h += Hint(v.k(Action.CLEAR), "Default") }
            else -> { h += Hint(v.editLr(), "±1 (${v.ud()} coarse)"); h += Hint(v.k(Action.CLEAR), "Default") }
        }
        if (row != Row.SLOT && row != Row.NAME && row != Row.LISTEN && row != Row.PAGE) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${VsuRules.CHANNEL_NAMES[st.listenChannel.coerceIn(0, VsuPlayerImage.CHANNELS - 1)]}")
        h += Hint(v.octaves(), "Listen at ${VsuSystem.noteName(listenNote(v, st.listenChannel.coerceIn(0, VsuPlayerImage.CHANNELS - 1)))}")
        h += Hint(v.copyPaste(), "Copy/paste sound")
    }

    /** Help rows for this view (heading, then key → meaning), named with the current [keys]. */
    fun helpSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val listen = keys.label(Action.AUDITION)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        val editLr = keys.held(Action.EDIT, lr)
        return listOf(
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen lane, page, then the page's settings",
                lr to "On settings: the sound slot",
                channels to "Pages: Sound, Sweep/Mod (Wave 5), Pitch sweep",
                "Listen on" to "The lane $listen plays the sound on (wave lanes from D#2 up). Holding $edit on a setting sounds the sound and every change restarts it",
                editLr to "Change the value (${keys.held(Action.EDIT, ud)} in bigger steps); switches flip, lists step through their choices",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
                "$edit on Slot" to "An empty slot gets a new sound (the Square lead preset) to start from",
            ),
            "Sound page" to listOf(
                "Preset" to "Leads, bass, pluck, swelling pad, tremolo organ, Wave 5 vibrato lead, laser, sweep kick and riser, a pitch-swept tom, " +
                    "noise kick, snare and hats; Wave 5 and noise presets move Listen to their lane",
                "Wave" to "Which of the song's five waves the sound plays (Wave view); Noise ignores it",
                "Envelope" to "Start level 0-15; with the envelope on it steps down (or up) one level every step time (15.36 ms × 1-8); " +
                    "Repeat restarts it from the start level after it reaches the end",
                "Length" to "With auto length the hardware stops the note after (length + 1) × 3.84 ms; otherwise it plays until OFF",
                "Noise tap" to "The Noise lane's shift-register tap: 0 is white noise (32767 steps), 7 the most tonal (28 steps)",
            ),
            "Sweep/Mod, Pitch sweep" to listOf(
                "Sweep/Mod" to "Wave 5's hardware: a sweep multiplies the frequency register by 1 ± 1/2^shift every interval; modulation " +
                    "adds the song's 32-value table (Wave view's last page) to it, once or looping. Clock: 0.96 ms or 7.68 ms per interval step",
                "Overflow" to "Sweeping up, a frequency register past 2047 stops the channel: notes high enough stop at once, even with the sweep off",
                "Pitch sweep" to "The player's sweep on any lane: each note starts at the start offset (semitones) and moves to the end at speed/16 semitones per tick",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = VsuInstrumentPainter(pane)
}
