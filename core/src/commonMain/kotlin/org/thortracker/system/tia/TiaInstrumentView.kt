package org.thortracker.system.tia

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
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The Atari 2600 instrument editor, controller-only: the one bank of [TiaSystem.SLOTS] sounds. Head rows: slot, name,
 * the channel Listen plays on, the waveform (AUDC 0-F, with its span on the song's TV standard), the pitch mode (tonal
 * or raw), a preset, then the page. Pages: the volume, arpeggio, pitch and waveform-sequence envelopes, edited step by
 * step as on the NES, and the **pitch guide**: the waveform's 32 dividers (AUDF → frequency → nearest note and cents),
 * where Edit plays the divider under the cursor.
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index),
 * [ViewState.envStep] (the envelope step, size = append; on the guide: the AUDF), [ViewState.listenChannel],
 * [ViewState.textPos] (names).
 * Holding Edit on a sound row sounds the instrument and every change restarts it, so edits are heard as made.
 */
object TiaInstrumentView : SystemView() {
    enum class Page(val label: String, val envelope: TiaEnvelopeKind?) {
        VOLUME("Volume", TiaEnvelopeKind.VOLUME),
        ARPEGGIO("Arpeggio", TiaEnvelopeKind.ARPEGGIO),
        PITCH("Pitch", TiaEnvelopeKind.PITCH),
        WAVEFORMS("Waveform seq", TiaEnvelopeKind.WAVEFORM),
        GUIDE("Pitch guide", null),
    }

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), WAVEFORM("Waveform"), MODE("Pitch mode"), PRESET("Preset"), PAGE("Page"),
        LENGTH("Length"), LOOP("Loop"), RELEASE("Release"), RELATIVE("Mode"), STEPS("Steps"), LADDER("Dividers"),
    }

    val PAGES: List<Page> = Page.entries

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.WAVEFORM, Row.MODE, Row.PRESET, Row.PAGE)

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = setOf(Row.WAVEFORM, Row.MODE, Row.PRESET, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.RELATIVE)

    /** Rows where ←→ moves within the row rather than to another slot. */
    private val LR_ROWS = setOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.PAGE, Row.STEPS, Row.LADDER)

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = FmControls.NAME_MAX

    fun sound(song: Song, state: ViewState): TiaInstrument? = song.tiaInstrument(state.instrument)

    fun page(state: ViewState): Page = PAGES[state.envelope.coerceIn(0, PAGES.size - 1)]

    fun rows(state: ViewState): List<Row> {
        val kind = page(state).envelope ?: return HEAD + Row.LADDER
        return HEAD + if (kind == TiaEnvelopeKind.PITCH) listOf(Row.LENGTH, Row.LOOP, Row.RELEASE, Row.RELATIVE, Row.STEPS)
            else listOf(Row.LENGTH, Row.LOOP, Row.RELEASE, Row.STEPS)
    }

    fun row(state: ViewState): Row = rows(state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The envelope the shown page edits, or null (the pitch guide). */
    fun envelopeKind(state: ViewState): TiaEnvelopeKind? = page(state).envelope

    /** The shown envelope (an empty slot's: the new sound's). */
    fun envelope(song: Song, state: ViewState): TiaEnvelope? =
        envelopeKind(state)?.of(sound(song, state) ?: defaultTiaInstrument(state.instrument))

    /** What a new step of [kind] starts from on an empty envelope: the waveform sequence starts on the sound's waveform. */
    fun fallback(ins: TiaInstrument?, kind: TiaEnvelopeKind): Int = if (kind == TiaEnvelopeKind.WAVEFORM) ins?.waveform ?: kind.fallback else kind.fallback

    /** A step value as the editor writes it: offsets signed, waveforms as AUDC hex digits. */
    fun valueText(kind: TiaEnvelopeKind, value: Int): String = when (kind) {
        TiaEnvelopeKind.VOLUME -> value.toString()
        TiaEnvelopeKind.WAVEFORM -> TiaRules.audcText(value)
        else -> if (value > 0) "+$value" else value.toString()
    }

    /** One line per envelope: what the player does with it. */
    fun envelopeSummary(kind: TiaEnvelopeKind, raw: Boolean): String = when (kind) {
        TiaEnvelopeKind.VOLUME -> "AUDV 0-15 per frame (empty = 15). A release point makes OFF a release"
        TiaEnvelopeKind.ARPEGGIO -> if (raw) "Semitones added to the note per frame: divider steps on a raw sound" else "Semitones added to the note per frame"
        TiaEnvelopeKind.PITCH -> if (raw) "Raw sounds ignore the pitch envelope" else "Sixty-fourths of a semitone per frame, heard where they cross a divider"
        TiaEnvelopeKind.WAVEFORM -> "The AUDC played each frame (empty = the sound's Waveform). Wxx is ignored while it has steps"
    }

    /** The waveform the guide and Listen describe: the one a note keys on with. */
    fun guideWaveform(song: Song, state: ViewState): Int =
        (sound(song, state) ?: defaultTiaInstrument(state.instrument)).keyOnWaveform()

    /**
     * The note and upward fine pitch (0..63 sixty-fourths of a semitone, as auditions take it) that make [ins] key on
     * with divider [audf], near [near]: tonal: the divider's own frequency as a pitch, split into the note below it and
     * the rest (the edges lie halfway between dividers, so it plays exactly that one); raw: the note 31 − AUDF in the
     * 32-semitone cycle closest to [near]. Null for a constant waveform in tonal mode or a divider outside the notes.
     */
    fun ladderNote(region: TiaRegion, ins: TiaInstrument, audf: Int, near: Int): Pair<Int, Int>? {
        if (ins.raw) {
            val base = TiaRules.AUDF_MAX - audf
            val cycle = TiaRules.AUDF_MAX + 1
            val k = ((near - base).toDouble() / cycle).roundToInt()
            val note = (base + k * cycle).let { if (it > TiaRules.NOTES.last) it - cycle else if (it < 0) it + cycle else it }
            return note to 0
        }
        val hz = TiaRules.frequency(region, ins.keyOnWaveform(), audf) ?: return null
        val units = (TiaRules.UNITS * 12 * ln(hz / TiaRules.noteHz(0)) / ln(2.0)).roundToInt()
        val note = units.floorDiv(TiaRules.UNITS)
        if (note !in TiaRules.NOTES) return null
        return note to units.mod(TiaRules.UNITS)
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, TiaSystem.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envelope = st.envelope.coerceIn(0, PAGES.size - 1)
        st.instField = st.instField.coerceIn(0, rows(st).size - 1)
        val e = envelope(song, st)
        st.envStep = if (e != null) st.envStep.coerceIn(0, TiaEnvelopes.lastStep(e)) else st.envStep.coerceIn(0, TiaRules.AUDF_MAX)
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, TiaSystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Selects the slot for entry. */
    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        session.select(session.selection.copy(bank = 0, instrument = v.state.instrument, context = ViewKind.KEYBOARD))
    }

    /** Shows page [index] (wrapping), keeping the cursor on the same kind of row where the new page has it. */
    fun showPage(v: ViewController, index: Int) {
        val st = v.state
        val row = row(st)
        st.envelope = index.mod(PAGES.size)
        val rows = rows(st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.PAGE)
        st.envStep = if (page(st) == Page.GUIDE) guideStart(v) else 0
    }

    /** The guide opens on the divider the default Listen note plays. */
    private fun guideStart(v: ViewController): Int {
        val song = v.session.song
        val ins = sound(song, v.state) ?: return 0
        return if (ins.raw) TiaRules.rawAudf(v.defaultNote())
            else TiaRules.audf(song.tia.region, ins.keyOnWaveform(), v.defaultNote() * TiaRules.UNITS) ?: 0
    }

    /** Moves to another slot; Listen moves to the channel that slot plays on most. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, TiaSystem.SLOTS)
        if (slot == st.instrument) return
        st.instrument = slot
        FmControls.mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        normalise(v)
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val song = v.session.song
        val rows = rows(st)
        val row = row(st)
        val e = envelope(song, st)
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            row == Row.STEPS && v.clearHeld -> { v.useClear(); if (e != null) st.envStep = (st.envStep + COARSE * dx).coerceIn(0, TiaEnvelopes.lastStep(e)) }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(0, rows.size - 1)
                st.textPos = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, TiaEnvelopes.lastStep(e))
            row == Row.LADDER -> st.envStep = (st.envStep + dx).coerceIn(0, TiaRules.AUDF_MAX)
            else -> moveSlot(v, dx)
        }
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val d = dx + dy
        val row = row(st)
        val changed = when (row) {
            Row.SLOT -> { moveSlot(v, dx - COARSE * dy); false }
            Row.NAME -> session.editTiaInstrument(slot, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, NAME_MAX))
            }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(session.song.system.channels.size); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.WAVEFORM -> session.setTiaWaveform(slot, (sound(session.song, st)?.waveform ?: TiaInstrument.DEFAULT_WAVEFORM) + dx + 4 * dy)
            Row.MODE -> if (d != 0) session.toggleTiaRaw(slot) else false
            Row.PRESET -> stepPreset(v, d)
            Row.LENGTH, Row.LOOP, Row.RELEASE -> {
                val kind = envelopeKind(st) ?: return
                val fallback = fallback(sound(session.song, st), kind)
                session.editTiaEnvelope(slot, kind, "Set ${kind.label.lowercase()} ${row.label.lowercase()}") {
                    when (row) {
                        Row.LENGTH -> TiaEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy, fallback)
                        Row.LOOP -> TiaEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy)
                        else -> TiaEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy)
                    }
                }
            }
            Row.RELATIVE -> session.editTiaInstrument(slot, "Set pitch envelope mode") { it.copy(relativePitch = !it.relativePitch) }
            Row.STEPS -> adjustSteps(v, dx, dy)
            Row.LADDER -> { st.envStep = (st.envStep + dx + COARSE * dy).coerceIn(0, TiaRules.AUDF_MAX); false }
        }
        if (changed) heard(v)
    }

    /** Envelope steps: Edit+↑↓ changes the step's value (adding one at the end), Edit+←→ carries it along. */
    private fun adjustSteps(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val session = v.session
        val kind = envelopeKind(st) ?: return false
        val slot = st.instrument
        val name = kind.label.lowercase()
        val fallback = fallback(sound(session.song, st), kind)
        val e = envelope(session.song, st) ?: return false
        var changed = false
        if (dy != 0) {
            changed = session.editTiaEnvelope(slot, kind, "Set $name") {
                TiaEnvelopes.withValue(kind, it, st.envStep, TiaEnvelopes.valueAt(it, st.envStep, fallback) + dy)
            }
        }
        if (dx != 0) {
            // Drawing sideways carries the value under the cursor to the next step, appending past the end.
            val value = TiaEnvelopes.valueAt(e, st.envStep, fallback)
            val next = (st.envStep + dx).coerceIn(0, TiaEnvelope.MAX_LENGTH - 1)
            if (next == st.envStep) return changed
            st.envStep = next
            changed = session.editTiaEnvelope(slot, kind, "Draw $name") { TiaEnvelopes.withValue(kind, it, next, value) } || changed
            envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(TiaEnvelopes.lastStep(it)) }
        }
        return changed
    }

    /** The preset the sound is (by name and settings), or -1. */
    fun presetIndex(ins: TiaInstrument?): Int {
        if (ins == null) return -1
        return TiaPresets.ALL.indexOfFirst { p ->
            (ins.name == p.instrument.name || ins.name == p.name.take(NAME_MAX)) && p.instrument.copy(name = ins.name) == ins
        }
    }

    /** Presets [delta] steps from the one the sound is (or the first), replacing the sound. */
    private fun stepPreset(v: ViewController, delta: Int): Boolean {
        val presets = TiaPresets.ALL
        if (presets.isEmpty() || delta == 0) return false
        val i = FmControls.presetStep(presetIndex(sound(v.session.song, v.state)), delta, presets.size)
        return usePreset(v, presets[i])
    }

    /** Replaces the sound by [preset] and says what its waveform reaches. */
    private fun usePreset(v: ViewController, preset: TiaPresets.Preset): Boolean {
        val ok = v.session.applyTiaPreset(v.state.instrument, preset)
        val ins = preset.instrument
        val region = v.session.song.tia.region
        v.session.note("${preset.name}: AUDC ${TiaRules.audcText(ins.waveform)} ${TiaRules.WAVEFORMS[ins.waveform].short}, " + when {
            ins.raw -> "raw (the note is the divider)"
            else -> TiaRules.reach(region, ins.waveform)?.let { "tonal ${TiaSystem.noteText(it.first)}..${TiaSystem.noteText(it.last)}" } ?: "a DC level"
        })
        return ok
    }

    /** The presets popup: every preset, the cursor on the sound's own. */
    private fun openPresets(v: ViewController) {
        val presets = TiaPresets.ALL
        val current = presetIndex(sound(v.session.song, v.state))
        v.session.requestMenu { menu ->
            menu.menu("Preset for sound ${EditorSession.hex2(v.state.instrument)}", presets.map { p ->
                "${p.name} · AUDC ${TiaRules.audcText(p.instrument.waveform)}${if (p.category == TiaPresets.Category.RAW) " raw" else ""}" to { usePreset(v, p); Unit }
            }, selected = maxOf(0, current))
        }
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
        val ins = sound(session.song, st)
        val changed = when (row) {
            Row.SLOT -> {
                if (ins == null) session.setTiaInstrument(slot, defaultTiaInstrument(slot), "New sound").also {
                    session.note("Sound ${EditorSession.hex2(slot)}: a new div 6 square to start from")
                }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            Row.PRESET -> { openPresets(v); false }
            Row.MODE -> session.toggleTiaRaw(slot)
            Row.RELATIVE -> session.editTiaInstrument(slot, "Set pitch envelope mode") { it.copy(relativePitch = !it.relativePitch) }
            Row.STEPS -> {
                val kind = envelopeKind(st) ?: return
                val e = envelope(session.song, st) ?: return
                if (st.envStep == e.values.size) {
                    val fallback = fallback(ins, kind)
                    session.editTiaEnvelope(slot, kind, "Add ${kind.label.lowercase()} step") {
                        TiaEnvelopes.withValue(kind, it, st.envStep, TiaEnvelopes.valueAt(it, st.envStep, fallback))
                    }.also { envelope(session.song, st)?.let { st.envStep = TiaEnvelopes.lastStep(it) } }
                } else false
            }
            Row.LADDER -> { playDivider(v); false }
            else -> if (ins == null && row in SOUND_ROWS) session.setTiaInstrument(slot, defaultTiaInstrument(slot), "New sound") else false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    /** Plays the divider under the guide's cursor on the Listen channel. */
    private fun playDivider(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val ins = sound(song, st) ?: run { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        val (note, fine) = ladderNote(song.tia.region, ins, st.envStep, v.defaultNote())
            ?: run { v.session.note("AUDF ${st.envStep} lies outside the notes C-0..B-9, or the waveform has no pitch"); return }
        v.startAudition(st.listenChannel, note, st.instrument, fine)
        v.session.note("AUDF ${st.envStep}: ${TiaSystem.noteText(note)}" + if (fine != 0) " ${if (fine > 0) "+" else ""}$fine/64" else "")
    }

    override fun clear(v: ViewController) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val ins = sound(session.song, st)
        val blank = defaultTiaInstrument(slot)
        when (row(st)) {
            Row.SLOT -> session.setTiaInstrument(slot, null, "Empty sound").also { session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused") }
            Row.NAME -> if (ins != null) session.setTiaInstrument(slot, ins.copy(name = ViewController.deleteChar(ins.name, st.textPos)), "Rename sound")
            Row.WAVEFORM -> session.setTiaWaveform(slot, TiaInstrument.DEFAULT_WAVEFORM)
            Row.MODE -> session.editTiaInstrument(slot, "Set pitch mode") { it.copy(raw = false) }
            Row.PRESET -> session.setTiaInstrument(slot, if (ins != null) blank.copy(name = ins.name) else blank, "Reset sound")
            Row.LENGTH -> envelopeKind(st)?.let { k -> session.editTiaEnvelope(slot, k, "Reset ${k.label.lowercase()} envelope") { TiaEnvelope.NONE } }
            Row.LOOP -> envelopeKind(st)?.let { k -> session.editTiaEnvelope(slot, k, "Clear ${k.label.lowercase()} loop") { it.copy(loop = -1) } }
            Row.RELEASE -> envelopeKind(st)?.let { k -> session.editTiaEnvelope(slot, k, "Clear ${k.label.lowercase()} release") { it.copy(release = -1) } }
            Row.RELATIVE -> session.editTiaInstrument(slot, "Set pitch envelope mode") { it.copy(relativePitch = false) }
            Row.STEPS -> envelopeKind(st)?.let { k ->
                session.editTiaEnvelope(slot, k, "Delete ${k.label.lowercase()} step") { TiaEnvelopes.removed(k, it, st.envStep) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(TiaEnvelopes.lastStep(it)) }
            }
            Row.LISTEN, Row.PAGE, Row.LADDER -> Unit
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val ins = sound(v.session.song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        v.startAudition(st.listenChannel, v.defaultNote(), st.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val row = row(st)
        val kind = envelopeKind(st)
        val onEnvelope = kind != null && row !in HEAD
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showPage(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onEnvelope) session.copyTiaEnvelope(slot, kind!!) else session.copyTiaInstrument(slot)
            Action.PASTE -> session.pasteTia(slot, kind)
            Action.CUT -> if (onEnvelope) {
                session.copyTiaEnvelope(slot, kind!!)
                session.editTiaEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { TiaEnvelope.NONE }
            } else {
                session.copyTiaInstrument(slot)
                session.setTiaInstrument(slot, null, "Cut sound")
            }
            Action.DUPLICATE -> {
                if (slot >= TiaSystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(session.song, st) ?: return true
                session.setTiaInstrument(slot + 1, src.copy(name = (src.name + "+").take(NAME_MAX)), "Duplicate sound")
                st.instrument++
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        val st = v.state
        val song = v.session.song
        val row = row(st)
        val ins = sound(song, st)
        when (row) {
            Row.SLOT -> {
                h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots")
                h += Hint(v.k(Action.EDIT), if (ins == null) "Make a sound here" else "Use for entry")
                h += Hint(v.k(Action.CLEAR), "Empty the slot")
            }
            Row.NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            Row.LISTEN -> h += Hint(v.lr(), "Channel Listen plays on")
            Row.PAGE -> h += Hint(v.lr(), "Page")
            Row.WAVEFORM -> { h += Hint(v.editLr(), "AUDC 0-F (${v.ud()} ±4)"); h += Hint(v.k(Action.CLEAR), "C, div 6 square") }
            Row.MODE -> { h += Hint(v.k(Action.EDIT), "Tonal / raw"); h += Hint(v.k(Action.CLEAR), "Tonal") }
            Row.PRESET -> { h += Hint(v.editLr(), "Start from a preset"); h += Hint(v.k(Action.EDIT), "Presets list"); h += Hint(v.k(Action.CLEAR), "Default sound") }
            Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "Empty envelope") }
            Row.LOOP -> { h += Hint(v.editLr(), "Loop start"); h += Hint(v.k(Action.CLEAR), "No loop") }
            Row.RELEASE -> { h += Hint(v.editLr(), "Release step"); h += Hint(v.k(Action.CLEAR), "No release") }
            Row.RELATIVE -> h += Hint(v.k(Action.EDIT), "Relative / absolute")
            Row.STEPS -> {
                h += Hint(v.lr(), "Step"); h += Hint(v.editUd(), "Value"); h += Hint(v.editLr(), "Draw value along")
                h += Hint(v.k(Action.CLEAR), "Delete step")
            }
            Row.LADDER -> { h += Hint(v.lr(), "Divider (AUDF)"); h += Hint(v.k(Action.EDIT), "Play this divider") }
        }
        if (row !in LR_ROWS) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(st.listenChannel)?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${TiaSystem.noteText(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (envelopeKind(st) != null && row !in HEAD) "Copy/paste envelope" else "Copy/paste sound")
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
        val editUd = keys.held(Action.EDIT, ud)
        return listOf(
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, waveform, pitch mode, preset, page, then the page's settings",
                lr to "On settings: the sound slot; on envelope steps: the step; on the pitch guide: the divider",
                channels to "Pages: Volume, Arpeggio, Pitch, Waveform seq, Pitch guide",
                "Listen on" to "The channel $listen plays the sound on (TIA 1 or 2). Holding $edit on a setting sounds the sound and " +
                    "every change restarts it",
                "Waveform" to "The TIA's AUDC 0-F: div 2 and div 6 squares, the div 31 and div 93 tones, polynomial buzzes, white " +
                    "noise (8) and a constant level (0, B). Shown with the notes it reaches on the song's TV standard",
                "Pitch mode" to "Tonal: the note's pitch picks the nearest divider (AUDF) and sounds at its true frequency; " +
                    "notes outside the waveform's span play its nearest end. Raw: the note is the divider (C-0 = AUDF 31, " +
                    "one step brighter per semitone, every 32 semitones), for noise and drums",
                "Preset" to "$editLr steps through the starting sounds; $edit opens the list",
                editLr to "Change the value ($editUd in bigger steps)",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
                "$edit on Slot" to "An empty slot gets a new div 6 square to start from",
            ),
            "Envelopes" to listOf(
                editUd to "On a step: raise / lower its value; past the last step it adds one",
                editLr to "On a step: carry its value to the next step (draws flat runs, adds steps at the end)",
                "$clear hold+$lr" to "Move along the steps by $COARSE",
                "Volume" to envelopeSummary(TiaEnvelopeKind.VOLUME, false),
                "Arpeggio" to "Semitones added to the note per frame; on raw sounds they step the divider",
                "Pitch" to envelopeSummary(TiaEnvelopeKind.PITCH, false) + "; raw sounds ignore it",
                "Waveform seq" to envelopeSummary(TiaEnvelopeKind.WAVEFORM, false),
                "Loop" to "The steps from the loop point to the end (or to the release point) repeat while the note holds; without one the last value holds",
                "Release" to "With a release point on the volume envelope OFF releases the note: every envelope with a release point " +
                    "jumps to it and plays out. Without one OFF stops the note",
            ),
            "Pitch guide" to listOf(
                lr to "The waveform's dividers, AUDF 0 (highest) to 31: each one's frequency on the song's TV standard and the " +
                    "nearest note with its error in cents",
                edit to "Play the divider under the cursor on the Listen channel",
                "NTSC / PAL" to "The PAL TIA runs about 1 % slower, so every divider sounds about 16 cents lower than on NTSC",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = TiaInstrumentPainter(pane)
}
