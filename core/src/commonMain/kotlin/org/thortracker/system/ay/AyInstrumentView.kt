package org.thortracker.system.ay

import org.thortracker.backend.AyPlayerImage
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
import org.thortracker.system.fm.FmControls
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * AY-3-8910 / YM2149 instruments: a musical Sound patchboard and the exact Advanced editor share the one bank of
 * [AySystem.SLOTS] sounds (each empty or an [AyInstrument]). Advanced head rows select slot, name, Listen channel
 * and page. Its Sound page retains presets, gates, noise, hardware envelope, tone sync and drum sweep; Volume,
 * Arpeggio, Pitch, Mixer and Noise retain the step-by-step software envelopes; Envelope retains raw R13 shape,
 * auto/manual period, octave, the 16-bit period, sync and the chip-resolution ramp.
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index in
 * [PAGES]), [ViewState.envStep] (envelope pages: the step, size = append), [ViewState.listenChannel].
 * Holding Edit on a sound row sounds the instrument and every change restarts it, so edits are heard as made.
 */
object AyInstrumentView : SystemView() {
    enum class Page(val label: String, val envelope: AyEnvelopeKind? = null) {
        SOUND("Sound"),
        VOLUME("Volume", AyEnvelopeKind.VOLUME), ARPEGGIO("Arpeggio", AyEnvelopeKind.ARPEGGIO), PITCH("Pitch", AyEnvelopeKind.PITCH),
        MIXER("Mixer", AyEnvelopeKind.MIXER), NOISE("Noise", AyEnvelopeKind.NOISE),
        ENVELOPE("Envelope"),
    }

    val PAGES = listOf(Page.SOUND, Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.MIXER, Page.NOISE, Page.ENVELOPE)

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), PAGE("Page"),
        PRESET("Preset"), GATES("Gates"), NOISE_PERIOD("Noise period"), SHAPE("Envelope"), PERIOD("Period"), SYNC("Sync tone"),
        SWEEP_START("Sweep start"), SWEEP_END("Sweep end"), SWEEP_SPEED("Sweep speed"),
        LENGTH("Length"), LOOP("Loop"), ENV_RELEASE("Release"), MODE("Mode"), STEPS("Steps"),
        RAW_SHAPE("Shape R13"), AUTO("Auto/manual"), OCTAVE("Octave"), RAW_PERIOD("Period"),
    }

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.PAGE)

    private val SWEEP = listOf(Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED)

    private val ROWS = mapOf(
        Page.SOUND to HEAD + listOf(Row.PRESET, Row.GATES, Row.NOISE_PERIOD, Row.SHAPE, Row.PERIOD, Row.SYNC) + SWEEP,
        Page.VOLUME to HEAD + listOf(Row.LENGTH, Row.LOOP, Row.ENV_RELEASE, Row.STEPS),
        Page.ARPEGGIO to HEAD + listOf(Row.LENGTH, Row.LOOP, Row.STEPS),
        Page.PITCH to HEAD + listOf(Row.LENGTH, Row.LOOP, Row.MODE, Row.STEPS),
        Page.MIXER to HEAD + listOf(Row.LENGTH, Row.LOOP, Row.STEPS),
        Page.NOISE to HEAD + listOf(Row.LENGTH, Row.LOOP, Row.STEPS),
        Page.ENVELOPE to HEAD + listOf(Row.RAW_SHAPE, Row.AUTO, Row.OCTAVE, Row.RAW_PERIOD, Row.SYNC),
    )

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = ROWS.values.flatMap { it.drop(HEAD.size) }.toSet() - Row.STEPS

    /** The hardware envelope's rows: copy/paste there takes the hardware envelope settings. */
    val HARDWARE_ROWS = setOf(Row.SHAPE, Row.PERIOD, Row.SYNC, Row.RAW_SHAPE, Row.AUTO, Row.OCTAVE, Row.RAW_PERIOD)

    /** On/off settings: a tap switches them (Period on the Sound page: auto ⇄ manual). */
    private val TOGGLES = setOf(Row.SYNC, Row.AUTO, Row.MODE, Row.PERIOD)

    /** List controls: Edit+←→ chooses, wrapping. */
    private val LISTS = setOf(Row.GATES, Row.SHAPE, Row.RAW_SHAPE)

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = FmControls.NAME_MAX

    /** Edit+↑↓ on a 16-bit envelope period: one coarse register (R12) step. */
    const val PERIOD_COARSE = 256

    /** Edit+↑↓ on the noise period. */
    const val NOISE_COARSE = 4

    /** The gates (mixer values: bit 0 tone, bit 1 noise) in stepping order. */
    val GATE_ORDER = listOf(1, 2, 3, 0)

    /** The hardware envelope shapes in stepping order: off, then the eight R13 shapes. */
    val SHAPE_STEPS: List<Int> = listOf(0) + AyRules.SHAPES

    fun sound(song: Song, state: ViewState): AyInstrument? = song.ayInstrument(state.instrument)

    fun page(state: ViewState): Page = PAGES[state.envelope.coerceIn(0, PAGES.size - 1)]

    fun rows(state: ViewState): List<Row> = ROWS.getValue(page(state))

    fun row(state: ViewState): Row = rows(state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The software envelope the page shows, or null. */
    fun envelopeKind(state: ViewState): AyEnvelopeKind? = page(state).envelope

    /** The furthest step the cursor reaches on an envelope: the append position after the last step, while there is room. */
    fun lastStep(e: NesEnvelope): Int = minOf(e.values.size, NesEnvelope.MAX_LENGTH - 1)

    private fun envelope(song: Song, state: ViewState): NesEnvelope? {
        val kind = envelopeKind(state) ?: return null
        return (sound(song, state) ?: defaultAyInstrument(state.instrument)).steps(kind)
    }

    // ---- what the chip does (pure, shared with the painter and tests) ---------------------------------------------

    /** "Tone", "Noise", "Tone + noise" or "Neither" for mixer value [m]. */
    fun gateName(m: Int): String = AyRules.mixerText(m).replaceFirstChar { it.uppercase() }

    /** The gates [d] steps from the ones [mixer] starts with, in [GATE_ORDER]. */
    fun stepGates(mixer: NesEnvelope, d: Int): Int {
        val cur = AyEnvelopes.first(AyEnvelopeKind.MIXER, mixer) ?: 1
        return GATE_ORDER[(GATE_ORDER.indexOf(cur).coerceAtLeast(0) + d).mod(GATE_ORDER.size)]
    }

    /** The noise period [d] steps from the one [noise] starts with: none (null) below 0, at most 31. */
    fun stepNoise(noise: NesEnvelope, d: Int): Int? {
        val next = ((AyEnvelopes.first(AyEnvelopeKind.NOISE, noise) ?: -1) + d).coerceIn(-1, AyRules.NOISE.last)
        return if (next < 0) null else next
    }

    /** The shape [d] steps from [shape] in [SHAPE_STEPS], wrapping. */
    fun stepShape(shape: Int, d: Int): Int = SHAPE_STEPS[(SHAPE_STEPS.indexOf(shape).coerceAtLeast(0) + d).mod(SHAPE_STEPS.size)]

    /** The active period stepped: the auto octave by [dx] + [dy], or the manual period by [dx] (fine) and [dy] (R12). */
    fun stepPeriod(e: AyEnvelopeSettings, dx: Int, dy: Int): AyEnvelopeSettings =
        if (e.auto) e.copy(octave = (e.octave + dx + dy).coerceIn(AyRules.OCTAVES))
        else e.copy(period = (e.period + dx + PERIOD_COARSE * dy).coerceIn(AyRules.PERIOD))

    /** [s] with [row]'s value stepped: start/end by semitones (↑↓ octaves), speed by 1 (↑↓ 16). */
    fun stepSweep(s: AySweep, row: Row, dx: Int, dy: Int): AySweep {
        val limit = AyPlayerImage.SWEEP_LIMIT
        return when (row) {
            Row.SWEEP_START -> (s.start + dx + 12 * dy).coerceIn(-limit, limit).let { s.copy(start = it, speed = FmControls.sweepSpeed(s.speed, it, s.end)) }
            Row.SWEEP_END -> (s.end + dx + 12 * dy).coerceIn(-limit, limit).let { s.copy(end = it, speed = FmControls.sweepSpeed(s.speed, s.start, it)) }
            else -> s.copy(speed = (s.speed + dx + 16 * dy).coerceIn(0, 255))
        }
    }

    /** The shape R13 value [r13] plays as: 0-3 play as 9 (`\___`), 4-7 as 15 (`/___`); 8-15 as themselves. */
    fun canonicalShape(r13: Int): Int = when {
        r13 >= 8 -> r13
        r13 and 4 != 0 -> 15
        else -> 9
    }

    /** "\\\\ saw down" and the like for shape [shape] (8..15), "Off" for 0. */
    fun shapeName(shape: Int): String = if (shape == 0) "Off" else AyRules.SHAPE_NAMES[shape] ?: NumberText.hex(shape)

    /** The outline of shape [shape] alone ("\\\\"), "off" for 0. */
    fun shapeOutline(shape: Int): String = if (shape == 0) "off" else AyRules.SHAPE_GLYPHS[shape] ?: NumberText.hex(shape)

    /**
     * The levels the chip's envelope steps through for R13 value [r13] (any of the 16) over [ramps] ramps of [steps]
     * levels each (16: AY-3-8910, 32: YM2149), as the manuals' table and AUDIO_ENGINE describe it: attack (bit 2) rises
     * from 0, else falls from the top; continue (bit 3) clear holds after one ramp at 0; at a ramp's end hold (bit 0)
     * keeps the level where the (once more alternated, bit 1) direction ends, else alternate turns back or the ramp
     * restarts.
     */
    fun shapeLevels(r13: Int, steps: Int, ramps: Int): IntArray {
        val cont = r13 and 8 != 0
        val attack = r13 and 4 != 0
        val alternate = if (cont) r13 and 2 != 0 else attack
        val hold = if (cont) r13 and 1 != 0 else true
        val top = steps - 1
        val out = IntArray(steps * ramps)
        var rising = attack
        var held = -1
        for (r in 0 until ramps) {
            for (i in 0 until steps) out[r * steps + i] = if (held >= 0) held else if (rising) i else top - i
            if (held >= 0) continue
            if (hold) {
                val dir = if (alternate) !rising else rising
                held = if (dir) top else 0
            } else if (alternate) rising = !rising
        }
        return out
    }

    /** Envelope levels per ramp on [variant]'s chip: the YM2149's 32, the AY-3-8910's 16. */
    fun rampSteps(variant: AyVariant): Int = if (variant.ym) 32 else 16

    /**
     * Where [channel] is heard on [variant]'s stock output: the Amstrad CPC's stereo socket puts Channel A left, B on
     * both sides and C right; the other machines are mono (null).
     */
    fun side(variant: AyVariant, channel: Int): String? = if (!variant.stereo) null else when (channel) {
        0 -> "left"
        1 -> "both sides"
        else -> "right"
    }

    /**
     * What a note writes on its first tick: [pitch] (1/64 semitone: the note, the first arpeggio and pitch envelope
     * values and the sweep's start), the tone period TP and its frequency, the envelope period EP (null without a
     * hardware envelope) and the envelope's frequency, whether the tone is synced to it, the gates and the noise
     * period asked for (null: none).
     */
    class ListenReading(
        val note: Int, val pitch: Int, val tonePeriod: Int, val toneHz: Double,
        val envelopePeriod: Int?, val envelopeHz: Double?, val synced: Boolean, val gates: Int, val noise: Int?,
    )

    fun reading(variant: AyVariant, ins: AyInstrument, note: Int): ListenReading {
        val arp = AyEnvelopes.first(AyEnvelopeKind.ARPEGGIO, ins.arpeggio) ?: 0
        val bend = (AyEnvelopes.first(AyEnvelopeKind.PITCH, ins.pitch) ?: 0) * AyPlayerImage.PITCH_UNIT
        val sweep = if (ins.sweep.speed != 0) ins.sweep.start else 0
        val pitch = (AyPlayerImage.PITCH_UNITS_PER_SEMITONE * (note + arp + sweep) + bend).coerceIn(0, AyPlayerImage.PITCH_MAX)
        val env = ins.envelope
        val ep: Int? = when {
            !env.on -> null
            env.auto -> AyRules.autoEnvelopePeriod(variant, pitch, env.octave, env.shape)
            else -> env.period
        }
        val synced = ep != null && env.auto && env.sync
        val tp = if (ep != null && synced) AyRules.syncedTonePeriod(variant, ep, env.octave, env.shape) else AyRules.tonePeriod(variant, pitch)
        return ListenReading(
            note, pitch, tp, AyRules.toneHz(variant, tp), ep, ep?.let { AyRules.envelopeHz(variant, it, env.shape) }, synced,
            AyEnvelopes.first(AyEnvelopeKind.MIXER, ins.mixer) ?: 1, AyEnvelopes.first(AyEnvelopeKind.NOISE, ins.noise),
        )
    }

    /** "C-4: TP 01AB 261.6 Hz · EP 0010 (auto J+1, synced) 523.0 Hz" — the reading in one line. */
    fun readingText(song: Song, ins: AyInstrument, note: Int): String {
        val variant = song.ay.variant
        val r = reading(variant, ins, note)
        val env = ins.envelope
        val tone = "TP ${NumberText.hex(r.tonePeriod, 3)} ${hz(r.toneHz)}" + if (r.gates and 1 == 0) " (tone gate off)" else ""
        val envelope = r.envelopePeriod?.let { ep ->
            val mode = if (env.auto) "auto J${AyRules.signed(env.octave)}" + (if (r.synced) ", synced" else "") else "manual"
            " · EP ${NumberText.hex(ep, 4)} ($mode) ${shapeOutline(env.shape)} ${r.envelopeHz?.let(::hz) ?: ""}"
        } ?: " · no hardware envelope"
        val noise = if (r.gates and 2 == 0) "" else " · noise " + (r.noise?.let { "R6 $it" } ?: "R6 as it is")
        return "${song.system.noteName(note)}: $tone$envelope$noise"
    }

    fun hz(f: Double): String = if (f >= 1000.0) "${NumberText.fixed(f / 1000.0, 2)} kHz" else "${NumberText.fixed(f, 1)} Hz"

    /** A short reading of [ins]: gates, hardware envelope, sweep. */
    fun summary(ins: AyInstrument): String {
        val gates = if (AyEnvelopes.varies(ins.mixer)) "gates vary" else gateName(AyEnvelopes.first(AyEnvelopeKind.MIXER, ins.mixer) ?: 1)
        val env = ins.envelope
        val hw = if (!env.on) "software volume" else "hardware ${shapeOutline(env.shape)} " + if (env.auto) "auto J${AyRules.signed(env.octave)}" else "manual ${NumberText.hex(env.period, 4)}"
        val sweep = if (ins.sweep.active) " · sweep" else ""
        return "$gates · $hw$sweep"
    }

    // ---- controller -------------------------------------------------------------------------------------------------

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, AySystem.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envelope = st.envelope.coerceIn(0, PAGES.size - 1)
        st.instField = st.instField.coerceIn(-1, if (AySoundFace.friendly(st)) AySoundControl.entries.size + 1 else rows(st).size - 1)
        AySoundFace.settle(st)
        if (envelopeKind(st) != null) st.envStep = st.envStep.coerceIn(0, envelope(song, st)?.let(::lastStep) ?: 0)
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        val slot = subject.coerceIn(1, AySystem.SLOTS)
        if (state.instrument != slot) state.soundPanel("ay").apply { invalidate(); editBlocked = true }
        state.instrument = slot
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        if (state.instrument != selection.instrument || state.listenChannel != selection.channel)
            state.soundPanel("ay").apply { invalidate(); editBlocked = true }
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Selects the slot for entry (the one bank). */
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
        st.envStep = 0
    }

    /** Moves to another slot; Listen moves to the channel that slot plays on most. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, AySystem.SLOTS)
        if (slot == st.instrument) return
        st.soundPanel("ay").invalidate()
        st.instrument = slot
        FmControls.mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        envelope(v.session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        if (AySoundFace.direction(v, dx, dy)) return
        val st = v.state
        val song = v.session.song
        val rows = rows(st)
        val row = row(st)
        val e = envelope(song, st)
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            row == Row.STEPS && v.clearHeld -> { v.useClear(); if (e != null) st.envStep = (st.envStep + COARSE * dx).coerceIn(0, lastStep(e)) }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(-1, rows.size - 1)
                st.textPos = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, lastStep(e))
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
            Row.NAME -> {
                val cur = sound(session.song, st) ?: defaultAyInstrument(slot)
                session.setAyInstrument(slot, cur.copy(name = NameEdit.edit(cur.name, st.textPos, dx + 26 * dy, NAME_MAX)), "Rename sound")
            }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(session.song.system.channels.size); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.PRESET -> applyPreset(v, d)
            Row.GATES -> session.editAyEnvelope(slot, AyEnvelopeKind.MIXER, "Set gates") { AyEnvelopes.constant(AyEnvelopeKind.MIXER, stepGates(it, d)) }
            Row.NOISE_PERIOD -> session.editAyEnvelope(slot, AyEnvelopeKind.NOISE, "Set noise period") {
                AyEnvelopes.constant(AyEnvelopeKind.NOISE, stepNoise(it, dx + NOISE_COARSE * dy))
            }
            Row.SHAPE, Row.RAW_SHAPE -> session.editAyHardware(slot, "Set envelope shape") { it.copy(shape = stepShape(it.shape, d)) }
            Row.PERIOD -> session.editAyHardware(slot, "Set envelope period") { stepPeriod(it, dx, dy) }
            Row.AUTO -> session.editAyHardware(slot, "Set envelope period mode") { it.copy(auto = !it.auto) }
            Row.OCTAVE -> session.editAyHardware(slot, "Set envelope octave") { it.copy(octave = (it.octave + d).coerceIn(AyRules.OCTAVES)) }
            Row.RAW_PERIOD -> session.editAyHardware(slot, "Set envelope period") {
                it.copy(period = (it.period + dx + PERIOD_COARSE * dy).coerceIn(AyRules.PERIOD))
            }
            Row.SYNC -> session.editAyHardware(slot, "Set tone sync") { it.copy(sync = !it.sync) }
            Row.MODE -> session.editAy(slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED ->
                session.editAy(slot, "Set ${row.label.lowercase()}") { it.copy(sweep = stepSweep(it.sweep, row, dx, dy)) }
            Row.STEPS -> adjustSteps(v, dx, dy)
            Row.LENGTH, Row.LOOP, Row.ENV_RELEASE -> {
                val kind = envelopeKind(st) ?: return
                val name = kind.label.lowercase()
                session.editAyEnvelope(slot, kind, "Set $name ${row.label.lowercase()}") {
                    when (row) {
                        Row.LENGTH -> AyEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy)
                        Row.LOOP -> AyEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy)
                        else -> AyEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy)
                    }
                }
            }
        }
        if (changed) {
            changeNote(v)
            heard(v)
        }
    }

    /** Envelope steps: Edit+↑↓ changes the step's value (adding one at the end), Edit+←→ carries it along. */
    private fun adjustSteps(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val session = v.session
        val kind = envelopeKind(st) ?: return false
        val slot = st.instrument
        val name = kind.label.lowercase()
        val e = envelope(session.song, st) ?: return false
        var changed = false
        if (dy != 0) {
            changed = session.editAyEnvelope(slot, kind, "Set $name") {
                AyEnvelopes.withValue(kind, it, st.envStep, AyEnvelopes.valueAt(kind, it, st.envStep) + dy)
            }
        }
        if (dx != 0) {
            // Drawing sideways carries the value under the cursor to the next step, appending past the end.
            val value = AyEnvelopes.valueAt(kind, e, st.envStep)
            val next = (st.envStep + dx).coerceIn(0, NesEnvelope.MAX_LENGTH - 1)
            if (next == st.envStep) return changed
            st.envStep = next
            changed = session.editAyEnvelope(slot, kind, "Draw $name") { AyEnvelopes.withValue(kind, it, next, value) } || changed
            envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
        }
        return changed
    }

    /** A status line after an edit whose effect the row cannot show in full: the shape and periods the Listen note gets. */
    private fun changeNote(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val ins = sound(song, st) ?: return
        when (row(st)) {
            in HARDWARE_ROWS, Row.GATES, Row.NOISE_PERIOD -> v.session.note(readingText(song, ins, v.defaultNote()))
            else -> Unit
        }
    }

    /** Presets [delta] steps from the one the sound is (or the first), replacing the sound. */
    private fun applyPreset(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val presets = AyPresets.ALL
        if (presets.isEmpty()) return false
        val ins = sound(v.session.song, st)
        val preset = presets[FmControls.presetStep(presets.indexOfFirst { it.instrument == ins }, delta, presets.size)]
        val ok = v.session.setAyInstrument(st.instrument, preset.instrument, "Preset ${preset.name}")
        v.session.note("${preset.name} (${preset.category}): ${summary(preset.instrument)} · voiced at ${v.session.song.system.noteName(preset.note)}")
        return ok
    }

    /** After an edit: a held Listen or Edit sounds the changed instrument again. */
    private fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && row(v.state) in SOUND_ROWS)) audition(v)
    }

    override fun editPress(v: ViewController) {
        v.state.soundPanel("ay").editBlocked = false
        if (v.state.instField < 0) return
        if (AySoundFace.friendly(v.state)) {
            if (v.state.instField >= 2 && sound(v.session.song, v.state) != null) audition(v)
            return
        }
        if (row(v.state) in SOUND_ROWS && sound(v.session.song, v.state) != null) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        val panel = v.state.soundPanel("ay")
        if (panel.editBlocked) { panel.editBlocked = false; if (!v.auditionHeld) v.session.releaseAudition(); return }
        if (v.state.instField < 0) {
            if (tap) AySoundFace.selectMode(v, !panel.advanced, true)
            return
        }
        if (AySoundFace.friendly(v.state)) { if (!v.auditionHeld) v.session.releaseAudition(); return }
        val st = v.state
        val session = v.session
        val row = row(st)
        if (row in SOUND_ROWS && !v.auditionHeld) session.releaseAudition()
        if (!tap) return
        val slot = st.instrument
        val empty = sound(session.song, st) == null
        val changed = when {
            row == Row.SLOT -> {
                if (empty) session.setAyInstrument(slot, defaultAyInstrument(slot), "New sound").also {
                    session.note("Sound ${EditorSession.hex2(slot)}: a new sound ($AY_STARTING_PRESET) to start from")
                }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            row == Row.PRESET -> applyPreset(v, 1)
            row == Row.PERIOD -> session.editAyHardware(slot, "Set envelope period mode") { it.copy(auto = !it.auto) }.also { if (it) changeNote(v) }
            row in TOGGLES -> { adjust(v, 1, 0); false }
            row == Row.STEPS -> {
                val kind = envelopeKind(st) ?: return
                val e = envelope(session.song, st) ?: return
                if (st.envStep == e.values.size) {
                    session.editAyEnvelope(slot, kind, "Add ${kind.label.lowercase()} step") {
                        AyEnvelopes.withValue(kind, it, st.envStep, AyEnvelopes.valueAt(kind, it, st.envStep))
                    }.also { envelope(session.song, st)?.let { st.envStep = lastStep(it) } }
                } else false
            }
            empty && row in SOUND_ROWS -> session.setAyInstrument(slot, defaultAyInstrument(slot), "New sound")
            else -> false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    override fun clear(v: ViewController) {
        if (v.state.instField < 0 || (v.state.soundPanel("ay").editBlocked && v.editHeld)) return
        if (AySoundFace.friendly(v.state) && v.state.instField >= 2) return
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        fun hw(label: String, f: (AyEnvelopeSettings) -> AyEnvelopeSettings) { session.editAyHardware(slot, label, f) }
        fun env(kind: AyEnvelopeKind, label: String, f: (NesEnvelope) -> NesEnvelope) { session.editAyEnvelope(slot, kind, label, f) }
        when (row(st)) {
            Row.SLOT -> session.setAyInstrument(slot, null, "Empty sound").also {
                session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused")
            }
            Row.PRESET -> session.setAyInstrument(slot, defaultAyInstrument(slot), "Reset sound")
            Row.NAME -> session.renameAy(slot) { ViewController.deleteChar(it, st.textPos) }
            Row.GATES -> env(AyEnvelopeKind.MIXER, "Gates: tone") { NesEnvelope() }
            Row.NOISE_PERIOD -> env(AyEnvelopeKind.NOISE, "No noise period") { NesEnvelope() }
            Row.SHAPE, Row.RAW_SHAPE -> hw("Hardware envelope off") { it.copy(shape = 0) }
            Row.PERIOD -> hw("Auto envelope period") { it.copy(auto = true, octave = 0) }
            Row.AUTO -> hw("Auto envelope period") { it.copy(auto = true) }
            Row.OCTAVE -> hw("Envelope octave 0") { it.copy(octave = 0) }
            Row.RAW_PERIOD -> hw("Envelope period 0") { it.copy(period = 0) }
            Row.SYNC -> hw("Tone sync off") { it.copy(sync = false) }
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> session.editAy(slot, "Sweep off") { it.copy(sweep = AySweep()) }
            Row.LENGTH -> envelopeKind(st)?.let { k -> env(k, "Empty ${k.label.lowercase()} envelope") { NesEnvelope() } }
            Row.LOOP -> envelopeKind(st)?.let { k -> env(k, "Clear ${k.label.lowercase()} loop") { it.copy(loop = -1) } }
            Row.ENV_RELEASE -> envelopeKind(st)?.let { k -> env(k, "Clear ${k.label.lowercase()} release") { it.copy(release = -1) } }
            Row.MODE -> session.editAy(slot, "Set pitch mode") { it.copy(pitchRelative = false) }
            Row.STEPS -> envelopeKind(st)?.let { k ->
                env(k, "Delete ${k.label.lowercase()} step") { AyEnvelopes.removed(k, it, st.envStep) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
            }
            Row.LISTEN, Row.PAGE -> Unit
        }
        if (song !== session.song) changeNote(v)
    }

    override fun audition(v: ViewController) {
        val st = v.state
        if (sound(v.session.song, st) == null) {
            v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one")
            return
        }
        v.startAudition(st.listenChannel, v.defaultNote(), st.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        if (AySoundFace.command(v, a)) return true
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val row = row(st)
        val kind = envelopeKind(st)
        val onEnvelope = kind != null && row !in HEAD
        val onHardware = row in HARDWARE_ROWS
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showPage(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> when {
                onEnvelope -> session.copyAyEnvelope(slot, kind!!)
                onHardware -> session.copyAyHardware(slot)
                else -> session.copyAyInstrument(slot)
            }
            Action.PASTE -> session.pasteAy(slot, kind)
            Action.CUT -> when {
                onEnvelope -> {
                    session.copyAyEnvelope(slot, kind!!)
                    session.editAyEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { NesEnvelope() }
                }
                onHardware -> {
                    session.copyAyHardware(slot)
                    session.editAyHardware(slot, "Cut hardware envelope") { AyEnvelopeSettings() }
                }
                else -> {
                    session.copyAyInstrument(slot)
                    session.setAyInstrument(slot, null, "Cut sound")
                }
            }
            Action.DUPLICATE -> {
                if (slot >= AySystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(session.song, st) ?: return true
                session.setAyInstrument(slot + 1, src.copy(name = (src.name + "+").take(NAME_MAX)), "Duplicate sound")
                st.instrument++
                publish(v)
            }
            else -> return false
        }
        if (envelopeKind(st) != null) envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        if (AySoundFace.friendly(v.state) || v.state.instField < 0) { AySoundFace.hints(v, h); return }
        val st = v.state
        val song = v.session.song
        val row = row(st)
        val ins = sound(song, st)
        val auto = (ins ?: defaultAyInstrument(st.instrument)).envelope.auto
        when {
            row == Row.SLOT -> {
                h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots")
                h += Hint(v.k(Action.EDIT), if (ins == null) "Make a sound here" else "Use for entry")
                h += Hint(v.k(Action.CLEAR), "Empty the slot")
            }
            row == Row.NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            row == Row.LISTEN -> h += Hint(v.lr(), "Channel Listen plays on")
            row == Row.PAGE -> h += Hint(v.lr(), "Page")
            row == Row.PRESET -> { h += Hint(v.editLr(), "Start from a preset"); h += Hint(v.k(Action.CLEAR), "Default sound") }
            row == Row.STEPS -> {
                h += Hint(v.lr(), "Step"); h += Hint(v.editUd(), "Value"); h += Hint(v.editLr(), "Draw value along")
                h += Hint(v.k(Action.CLEAR), "Delete step")
            }
            row == Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "Empty envelope") }
            row == Row.LOOP -> { h += Hint(v.editLr(), "Loop start"); h += Hint(v.k(Action.CLEAR), "No loop") }
            row == Row.ENV_RELEASE -> { h += Hint(v.editLr(), "Release step"); h += Hint(v.k(Action.CLEAR), "No release") }
            row == Row.MODE -> h += Hint(v.k(Action.EDIT), "Relative / absolute")
            row == Row.PERIOD -> {
                h += Hint(v.k(Action.EDIT), if (auto) "Manual period" else "Auto period")
                h += Hint(v.editLr(), if (auto) "Octave" else "Period ±1 (${v.ud()} ±$PERIOD_COARSE)")
                h += Hint(v.k(Action.CLEAR), "Auto J+0")
            }
            row == Row.RAW_PERIOD -> { h += Hint(v.editLr(), "Fine ±1 (${v.ud()} coarse ±$PERIOD_COARSE)"); h += Hint(v.k(Action.CLEAR), "0") }
            row == Row.NOISE_PERIOD -> { h += Hint(v.editLr(), "Period (${v.ud()} ±$NOISE_COARSE)"); h += Hint(v.k(Action.CLEAR), "None") }
            row == Row.SYNC -> { h += Hint(v.k(Action.EDIT), "Sync on / off"); h += Hint(v.k(Action.CLEAR), "Off") }
            row == Row.AUTO -> { h += Hint(v.k(Action.EDIT), "Auto / manual"); h += Hint(v.k(Action.CLEAR), "Auto") }
            row in LISTS -> { h += Hint(v.editLr(), "Choose"); h += Hint(v.k(Action.CLEAR), "Default") }
            else -> { h += Hint(v.editLr(), "±1 (${v.ud()} coarse)"); h += Hint(v.k(Action.CLEAR), "Default") }
        }
        if (row !in HEAD && row != Row.STEPS) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(st.listenChannel)?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${song.system.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), when {
            envelopeKind(st) != null && row !in HEAD -> "Copy/paste envelope"
            row in HARDWARE_ROWS -> "Copy/paste hardware envelope"
            else -> "Copy/paste sound"
        })
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
            "Sound face" to listOf(
                ud to "Select instrument slot; $lr cycles musical controls, Name and Sound/Advanced",
                editLr to "Shape the selected control; on Name change a letter ($editUd moves the caret)",
                channels to "Choose the audition channel A/B/C; $listen listens, octave shortcuts change pitch",
                "Touch" to "Sound/Advanced tabs preserve the patch. Drag a control; compact panes have < > paging. + / - selects slots, Listen cycles channels",
                "Recipes" to "Source owns mixer, Contour owns software volume, Noise colour owns noise, Buzzer owns hardware shape. Custom programs are replaced only by explicitly choosing that recipe",
                "Shared buzzer" to "Latest requesting note wins the single noise/envelope generator. Contour is unavailable with hardware envelope; Buzzer register explicitly chooses note-following tuning and preserves the inactive manual period",
                "Advanced" to "All original precise pages and presets remain available below",
            ),
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, page, then the page's settings",
                lr to "On settings: the sound slot; on envelope steps: the step",
                channels to "Pages: Sound (curated), Volume, Arpeggio, Pitch, Mixer, Noise (software envelopes), Envelope (the hardware envelope, expert)",
                "Listen on" to "The channel $listen plays the sound on (Channel A-C). Holding $edit on a setting sounds the sound and every change restarts it. " +
                    "The ZX Spectrum 128, MSX and Atari ST are mono; the Amstrad CPC's stereo socket plays A left, B on both sides, C right",
                editLr to "Change the value ($editUd in bigger steps); lists step through their choices",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
                "$edit on Slot" to "An empty slot gets a new sound to start from ($AY_STARTING_PRESET); so does the first edit of any setting",
            ),
            "Sound page" to listOf(
                "Preset" to "Starting sounds: square lead, pluck, soft pad, arpeggio chords, buzzer bass, sync buzzer, bell, envelope tremolo, " +
                    "kick, snare, hats, tom and a noise riser",
                "Gates" to "Tone, Noise, Tone + noise or Neither (a constant level, or the bare hardware envelope: the classic buzzer). ≈: the Mixer envelope changes them per tick",
                "Noise period" to "0-31 (higher is lower noise; 0 plays as 1), or none: the note then hears whatever noise period the chip has. ≈: the Noise envelope changes it",
                "Envelope" to "The chip's hardware envelope instead of the volume: Off or one of the eight shapes (\\\\ saw, \\___ decay, \\/\\/ triangle, \\‾‾, ////, /‾‾, /\\/\\, /___)",
                "Period" to "Auto: the envelope's cycle follows the note, J octaves up (J+0 sounds the note itself); Manual: a fixed 16-bit period. $edit switches",
                "Sync tone" to "Auto only: the tone period is an exact multiple of the envelope's, so a buzzer and its tone never beat",
                "Sweep" to "Drum pitch sweep: each note starts at the start offset (semitones) and moves to the end at speed/16 semitones per tick",
                "Reading" to "What the Listen note writes: TP (tone period), EP (envelope period) and the frequencies they sound",
            ),
            "Envelope pages" to listOf(
                "Volume, Arpeggio, Pitch, Mixer, Noise" to "One value per tick, edited as on the NES: $editUd sets a step, $editLr draws along, loop and release points " +
                    "(release on volume only); volume 0-15, arpeggio in semitones, pitch in sixteenths of a semitone, mixer 0-3 (1 tone, 2 noise, 3 both), noise period 0-31",
                "Envelope" to "Expert: the R13 shape (all 16 values shown; 0-3 play as 9, 4-7 as F), auto/manual, octave J −8..7, the period " +
                    "($editLr fine, $editUd coarse = R12) and sync. The ramp is drawn at the chip's resolution: 16 steps on the AY-3-8910, 32 on the YM2149",
            ),
            "Shared noise and envelope" to listOf(
                "One each" to "The AY has one noise generator and one envelope generator for its three channels. Notes only ask for them",
                "Latest note wins" to "The most recent note asking for the noise period or the envelope takes it; others using it play the owner's. " +
                    "The Pattern lanes show ENV/NZ on the owner and env/nz where a channel plays another's",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = AySoundPainter(pane)
}
