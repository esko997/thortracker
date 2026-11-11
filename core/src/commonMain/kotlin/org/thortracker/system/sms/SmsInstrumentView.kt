package org.thortracker.system.sms

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
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.nes.NesEnvelopes
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * Sega 8-bit instruments: a musical cartridge Sound face and the exact Advanced editor share one bank of
 * [SmsSystem.SLOTS] sounds, each empty, PSG, an FM voice (FM layouts) or a rhythm kit (rhythm layouts).
 * Advanced keeps slot, name, Listen channel, type and page rows; PSG volume/arpeggio/pitch/noise macros; the
 * OPLL built-in/custom Voice register grid plus volume and arpeggio; and note-following or fixed rhythm-kit pitches.
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index),
 * [ViewState.envStep] (Voice grid: the operator 0 modulator / 1 carrier; envelopes: the step), [ViewState.listenChannel].
 */
object SmsInstrumentView : SystemView() {
    enum class Page(val label: String, val envelope: NesEnvelopeKind? = null) {
        VOLUME("Volume", NesEnvelopeKind.VOLUME), ARPEGGIO("Arpeggio", NesEnvelopeKind.ARPEGGIO), PITCH("Pitch", NesEnvelopeKind.PITCH),
        NOISE("Noise", NesEnvelopeKind.DUTY), VOICE("Voice"), KIT("Kit"),
    }

    val PSG_PAGES = listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.NOISE)
    val FM_PAGES = listOf(Page.VOICE, Page.VOLUME, Page.ARPEGGIO)
    val KIT_PAGES = listOf(Page.KIT)

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), TYPE("Type"), PAGE("Page"),
        PRESET("Preset"), PATCH("Instrument"), LEVEL("Mod level"), FEEDBACK("Feedback"),
        OP_MULT("MULT"), OP_AM("AM"), OP_VIB("VIB"), OP_EG("EG"), OP_KSR("KSR"), OP_KSL("KSL"), OP_WAVE("Wave"),
        OP_AR("AR"), OP_DR("DR"), OP_SL("SL"), OP_RR("RR"),
        LENGTH("Length"), LOOP("Loop"), ENV_RELEASE("Release"), STEPS("Steps"),
        KIT_MODE("Pitch"), KIT_KICK("Bass drum"), KIT_SNARE("Snare, hi-hat"), KIT_TOM("Tom, cymbal"),
    }

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.TYPE, Row.PAGE)

    /** The custom voice's grid: one register field per row, modulator and carrier side by side. */
    val GRID = listOf(Row.OP_MULT, Row.OP_AM, Row.OP_VIB, Row.OP_EG, Row.OP_KSR, Row.OP_KSL, Row.OP_WAVE, Row.OP_AR, Row.OP_DR, Row.OP_SL, Row.OP_RR)

    /** Grid rows that are on/off bits: a tap switches them. */
    private val TOGGLES = setOf(Row.OP_AM, Row.OP_VIB, Row.OP_EG, Row.OP_KSR, Row.OP_WAVE)

    /** Rows that only apply to the custom voice. */
    val CUSTOM_ROWS = GRID.toSet() + Row.LEVEL + Row.FEEDBACK

    private val KIT_PITCHES = listOf(Row.KIT_KICK, Row.KIT_SNARE, Row.KIT_TOM)

    private fun rowsOf(page: Page): List<Row> = HEAD + when (page) {
        Page.VOICE -> listOf(Row.PRESET, Row.PATCH, Row.LEVEL, Row.FEEDBACK) + GRID
        Page.KIT -> listOf(Row.PRESET, Row.KIT_MODE) + KIT_PITCHES
        Page.VOLUME -> listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.ENV_RELEASE, Row.STEPS)
        else -> listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.STEPS)
    }

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = Page.entries.flatMap { rowsOf(it).drop(HEAD.size) }.toSet() - Row.STEPS + Row.TYPE

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = FmControls.NAME_MAX

    fun layout(song: Song): SmsLayout = song.sms.layout

    fun sound(song: Song, state: ViewState): SmsInstrument? = song.smsInstrument(state.instrument)

    /** The kind whose pages the slot shows: its sound's, or for an empty slot the Listen channel's. */
    fun kind(song: Song, state: ViewState): SmsInstrumentKind =
        sound(song, state)?.kind ?: layout(song).instrumentKind(state.listenChannel.coerceIn(0, layout(song).channelCount - 1))

    fun pages(song: Song, state: ViewState): List<Page> = when (kind(song, state)) {
        SmsInstrumentKind.PSG -> PSG_PAGES
        SmsInstrumentKind.FM -> FM_PAGES
        SmsInstrumentKind.KIT -> KIT_PAGES
    }

    fun page(song: Song, state: ViewState): Page = pages(song, state).let { it[state.envelope.coerceIn(0, it.size - 1)] }

    fun rows(song: Song, state: ViewState): List<Row> = rowsOf(page(song, state))

    fun row(song: Song, state: ViewState): Row = rows(song, state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The operator (0 modulator, 1 carrier) the grid's cursor is on. */
    fun operator(state: ViewState): Int = state.envStep.coerceIn(0, 1)

    fun envelopeKind(song: Song, state: ViewState): NesEnvelopeKind? = page(song, state).envelope

    /**
     * The channel a held Listen plays [ins] on: Noise for a sound with a noise envelope, else the Listen channel when it
     * plays the sound's kind, else the kind's first channel.
     */
    fun listenChannel(song: Song, state: ViewState, ins: SmsInstrument?): Int {
        val layout = layout(song)
        val listen = state.listenChannel.coerceIn(0, layout.channelCount - 1)
        if (ins is SmsPsgInstrument && ins.noise.values.isNotEmpty()) return SmsLayout.NOISE
        if (ins == null || layout.instrumentKind(listen) == ins.kind) return listen
        return when (ins.kind) {
            SmsInstrumentKind.PSG -> if (ins is SmsPsgInstrument && ins.noise.values.isNotEmpty()) SmsLayout.NOISE else 0
            SmsInstrumentKind.FM -> SmsLayout.FIRST_FM
            SmsInstrumentKind.KIT -> SmsLayout.FIRST_FM + layout.fmChannels
        }
    }

    fun lastStep(e: NesEnvelope): Int = minOf(e.values.size, NesEnvelope.MAX_LENGTH - 1)

    /** The envelope the page shows (a default sound's for an empty slot). */
    fun envelope(song: Song, state: ViewState): NesEnvelope? {
        val kind = envelopeKind(song, state) ?: return null
        return (sound(song, state) ?: defaultSmsInstrument(state.instrument, kind(song, state))).envelope(kind)
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, SmsSystem.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envelope = st.envelope.coerceIn(0, pages(song, st).size - 1)
        st.instField = st.instField.coerceIn(-1, if (SmsSoundFace.friendly(st)) SmsSoundFace.controls(song, st).size + 1 else rows(song, st).size - 1)
        SmsSoundFace.settle(song, st)
        when {
            page(song, st) == Page.VOICE -> st.envStep = st.envStep.coerceIn(0, 1)
            envelopeKind(song, st) != null -> st.envStep = st.envStep.coerceIn(0, envelope(song, st)?.let(::lastStep) ?: 0)
        }
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        val slot = subject.coerceIn(1, SmsSystem.SLOTS)
        if (state.instrument != slot) state.soundPanel("sms").apply { invalidate(); editBlocked = true }
        state.instrument = slot
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        if (state.instrument != selection.instrument || state.listenChannel != selection.channel)
            state.soundPanel("sms").apply { invalidate(); editBlocked = true }
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Selects the slot for entry, in the bank of its kind. */
    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        val layout = layout(session.song)
        val bank = layout.kinds.indexOf(kind(session.song, v.state)).coerceAtLeast(0)
        session.select(session.selection.copy(bank = bank, instrument = v.state.instrument, context = ViewKind.KEYBOARD))
    }

    fun showPage(v: ViewController, index: Int) {
        val st = v.state
        val song = v.session.song
        val row = row(song, st)
        st.envelope = index.mod(pages(song, st).size)
        val rows = rows(song, st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.PAGE)
        st.envStep = 0
    }

    private fun keepPage(v: ViewController, row: Row) {
        val st = v.state
        val song = v.session.song
        st.envelope = st.envelope.coerceIn(0, pages(song, st).size - 1)
        val rows = rows(song, st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: st.instField.coerceIn(0, rows.size - 1)
    }

    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, SmsSystem.SLOTS)
        if (slot == st.instrument) return
        st.soundPanel("sms").invalidate()
        val row = row(v.session.song, st)
        st.instrument = slot
        FmControls.mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        keepPage(v, row)
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        if (SmsSoundFace.direction(v, dx, dy)) return
        val st = v.state
        val song = v.session.song
        val rows = rows(song, st)
        val row = row(song, st)
        val e = envelope(song, st)
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            row == Row.STEPS && v.clearHeld -> { v.useClear(); if (e != null) st.envStep = (st.envStep + COARSE * dx).coerceIn(0, lastStep(e)) }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(-1, rows.size - 1)
                st.textPos = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row == Row.LISTEN -> { st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size); keepPage(v, row) }
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row in GRID -> st.envStep = (st.envStep + dx).coerceIn(0, 1)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, lastStep(e))
            else -> moveSlot(v, dx)
        }
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val d = dx + dy
        val row = row(session.song, st)
        val kind = kind(session.song, st)
        val changed = when (row) {
            Row.SLOT -> { moveSlot(v, dx - COARSE * dy); false }
            Row.NAME -> {
                val cur = sound(session.song, st) ?: defaultSmsInstrument(slot, kind)
                session.setSmsInstrument(slot, cur.named(NameEdit.edit(cur.name, st.textPos, dx + 26 * dy, NAME_MAX)), "Rename sound")
            }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(session.song.system.channels.size); keepPage(v, row); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.TYPE -> switchType(v, d)
            Row.PRESET -> applyPreset(v, d)
            Row.STEPS -> adjustSteps(v, dx, dy)
            Row.LENGTH, Row.LOOP, Row.ENV_RELEASE -> {
                val env = envelopeKind(session.song, st) ?: return
                ensure(v)
                session.editSmsEnvelope(slot, env, "Set ${row.label.lowercase()}") {
                    when (row) {
                        Row.LENGTH -> NesEnvelopes.withLength(env, it, it.values.size + dx + COARSE * dy)
                        Row.LOOP -> NesEnvelopes.withLoop(env, it, it.loop + dx + COARSE * dy)
                        else -> NesEnvelopes.withRelease(env, it, it.release + dx + COARSE * dy)
                    }
                }
            }
            Row.PATCH -> session.editSms<SmsFmInstrument>(slot, SmsInstrumentKind.FM, "Set instrument") { stepPatch(it, d) }
            Row.KIT_MODE -> session.editSms<SmsDrumKit>(slot, SmsInstrumentKind.KIT, "Set kit pitch") { it.copy(fixed = !it.fixed) }
            in KIT_PITCHES -> session.editSms<SmsDrumKit>(slot, SmsInstrumentKind.KIT, "Set ${row.label.lowercase()}") { stepKit(it, row, dx + 12 * dy) }
            else -> session.editSms<SmsFmInstrument>(slot, SmsInstrumentKind.FM, "Set ${row.label}") { step(it, row, operator(st), dx, dy) }
        }
        if (changed) heard(v)
    }

    /** An empty slot becomes the page kind's starting sound before an envelope edit. */
    private fun ensure(v: ViewController) {
        val st = v.state
        if (sound(v.session.song, st) == null) v.session.setSmsInstrument(st.instrument, defaultSmsInstrument(st.instrument, kind(v.session.song, st)), "New sound")
    }

    /** Built-in instruments 1-15 and the custom voice (0); choosing custom from a built-in starts from that instrument's registers. */
    fun stepPatch(ins: SmsFmInstrument, d: Int): SmsFmInstrument {
        val next = (ins.patch + d).mod(16)
        return if (next == 0 && ins.patch != 0) ins.copy(patch = 0, custom = OpllPatch.rom(ins.patch)) else ins.copy(patch = next)
    }

    fun stepKit(kit: SmsDrumKit, row: Row, d: Int): SmsDrumKit {
        fun n(v: Int) = (v + d).coerceIn(0, SmsInstruments.FM_TOP)
        return when (row) {
            Row.KIT_KICK -> kit.copy(kick = n(kit.kick))
            Row.KIT_SNARE -> kit.copy(snareHat = n(kit.snareHat))
            else -> kit.copy(tomCymbal = n(kit.tomCymbal))
        }
    }

    /** [ins] with [row] stepped by [dx] (fine) and [dy] (coarse); custom-voice fields switch the voice to custom. */
    fun step(ins: SmsFmInstrument, row: Row, op: Int, dx: Int, dy: Int): SmsFmInstrument {
        val d = dx + dy
        val base = if (ins.isCustom) ins else ins.copy(patch = 0, custom = OpllPatch.rom(ins.patch))
        val p = base.custom
        return when (row) {
            Row.LEVEL -> base.copy(custom = p.copy(level = (p.level + dx + COARSE * dy).coerceIn(0, 63)))
            Row.FEEDBACK -> base.copy(custom = p.copy(feedback = (p.feedback + d).coerceIn(0, 7)))
            in GRID -> {
                val o = if (op == 0) p.modulator else p.carrier
                val stepped = stepOperator(o, row, dx, dy)
                base.copy(custom = if (op == 0) p.copy(modulator = stepped) else p.copy(carrier = stepped))
            }
            else -> ins
        }
    }

    fun stepOperator(o: OpllOperator, row: Row, dx: Int, dy: Int): OpllOperator {
        val d = dx + dy
        return when (row) {
            Row.OP_MULT -> o.copy(mult = (o.mult + d).coerceIn(0, 15))
            Row.OP_AM -> o.copy(am = !o.am)
            Row.OP_VIB -> o.copy(vibrato = !o.vibrato)
            Row.OP_EG -> o.copy(sustained = !o.sustained)
            Row.OP_KSR -> o.copy(ksr = !o.ksr)
            Row.OP_KSL -> o.copy(ksl = (o.ksl + d).coerceIn(0, 3))
            Row.OP_WAVE -> o.copy(rectified = !o.rectified)
            Row.OP_AR -> o.copy(ar = (o.ar + d).coerceIn(0, 15))
            Row.OP_DR -> o.copy(dr = (o.dr + d).coerceIn(0, 15))
            Row.OP_SL -> o.copy(sl = (o.sl + d).coerceIn(0, 15))
            Row.OP_RR -> o.copy(rr = (o.rr + d).coerceIn(0, 15))
            else -> o
        }
    }

    private fun adjustSteps(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val session = v.session
        val env = envelopeKind(session.song, st) ?: return false
        val slot = st.instrument
        ensure(v)
        val e = envelope(session.song, st) ?: return false
        var changed = false
        if (dy != 0) {
            changed = session.editSmsEnvelope(slot, env, "Set step") {
                NesEnvelopes.withValue(env, it, st.envStep, NesEnvelopes.valueAt(env, it, st.envStep) + dy)
            }
        }
        if (dx != 0) {
            val value = NesEnvelopes.valueAt(env, e, st.envStep)
            val next = (st.envStep + dx).coerceIn(0, NesEnvelope.MAX_LENGTH - 1)
            if (next == st.envStep) return changed
            st.envStep = next
            changed = session.editSmsEnvelope(slot, env, "Draw steps") { NesEnvelopes.withValue(env, it, next, value) } || changed
            envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
        }
        return changed
    }

    /** The next kind the layout plays: the slot gets its starting sound (keeping its name); undo brings the old one back. */
    private fun switchType(v: ViewController, d: Int): Boolean {
        v.state.soundPanel("sms").invalidate()
        if (v.editHeld) v.state.soundPanel("sms").editBlocked = true
        val st = v.state
        val session = v.session
        val layout = layout(session.song)
        val kinds = layout.kinds
        if (kinds.size < 2) { session.note("The ${layout.displayName} has PSG sounds only"); return false }
        val slot = st.instrument
        val cur = sound(session.song, st)
        val next = kinds[(kinds.indexOf(kind(session.song, st)) + (if (d == 0) 1 else d)).mod(kinds.size)]
        val fresh = defaultSmsInstrument(slot, next)
        val ok = session.setSmsInstrument(slot, cur?.let { fresh.named(it.name) } ?: fresh, "Make ${next.bankName.dropLast(1).lowercase()}")
        st.listenChannel = listenChannel(session.song, st, fresh)
        keepPage(v, Row.TYPE)
        session.note("Sound ${EditorSession.hex2(slot)} is now ${typeText(next)}" + if (cur != null) "; undo brings the old sound back" else "")
        return ok
    }

    fun typeText(kind: SmsInstrumentKind): String = when (kind) {
        SmsInstrumentKind.PSG -> "a PSG sound (Square 1-3, Noise)"
        SmsInstrumentKind.FM -> "an FM voice (the FM channels)"
        SmsInstrumentKind.KIT -> "a drum kit (the rhythm channels)"
    }

    /** Presets of the slot's kind, [delta] steps from the one the sound is (or the first); noise sounds listen on Noise. */
    private fun applyPreset(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val presets = SmsPresets.forKind(kind(v.session.song, st))
        if (presets.isEmpty()) return false
        val ins = sound(v.session.song, st)
        val preset = presets[FmControls.presetStep(presets.indexOfFirst { it.instrument == ins }, delta, presets.size)]
        val ok = v.session.setSmsInstrument(st.instrument, preset.instrument, "Preset ${preset.name}")
        if (preset.category == SmsPresets.Category.NOISE) st.listenChannel = SmsLayout.NOISE
        else if (preset.category == SmsPresets.Category.PSG && st.listenChannel == SmsLayout.NOISE) st.listenChannel = 0
        v.session.note("${preset.name}: ${SmsPresets.summary(preset.instrument)}" + (preset.note?.let { " · voiced at ${v.session.song.system.noteName(it)}" } ?: ""))
        return ok
    }

    private fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && row(v.session.song, v.state) in SOUND_ROWS)) audition(v)
    }

    override fun editPress(v: ViewController) {
        v.state.soundPanel("sms").editBlocked = false
        if (v.state.instField < 0) return
        if (SmsSoundFace.friendly(v.state)) {
            if (v.state.instField >= 2 && sound(v.session.song, v.state) != null) audition(v)
            return
        }
        if (row(v.session.song, v.state) in SOUND_ROWS && sound(v.session.song, v.state) != null) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        val panel = v.state.soundPanel("sms")
        if (panel.editBlocked) { panel.editBlocked = false; if (!v.auditionHeld) v.session.releaseAudition(); return }
        if (v.state.instField < 0) {
            if (tap) SmsSoundFace.selectMode(v, !panel.advanced, true)
            return
        }
        if (SmsSoundFace.friendly(v.state)) { if (!v.auditionHeld) v.session.releaseAudition(); return }
        val st = v.state
        val session = v.session
        val row = row(session.song, st)
        if (row in SOUND_ROWS && !v.auditionHeld) session.releaseAudition()
        if (!tap) return
        val slot = st.instrument
        val empty = sound(session.song, st) == null
        val kind = kind(session.song, st)
        val changed = when {
            row == Row.SLOT -> {
                if (empty) session.setSmsInstrument(slot, defaultSmsInstrument(slot, kind), "New sound").also {
                    session.note("Sound ${EditorSession.hex2(slot)}: ${typeText(kind)} to start from")
                }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            row == Row.TYPE -> switchType(v, 1)
            row == Row.PRESET -> applyPreset(v, 1)
            row in TOGGLES -> session.editSms<SmsFmInstrument>(slot, SmsInstrumentKind.FM, "Set ${row.label}") { step(it, row, operator(st), 1, 0) }
            row == Row.KIT_MODE -> { adjust(v, 1, 0); false }
            row == Row.STEPS -> {
                val env = envelopeKind(session.song, st) ?: return
                ensure(v)
                val e = envelope(session.song, st) ?: return
                if (st.envStep == e.values.size) {
                    session.editSmsEnvelope(slot, env, "Add step") {
                        NesEnvelopes.withValue(env, it, st.envStep, NesEnvelopes.valueAt(env, it, st.envStep))
                    }.also { envelope(session.song, st)?.let { st.envStep = lastStep(it) } }
                } else false
            }
            empty && row in SOUND_ROWS -> session.setSmsInstrument(slot, defaultSmsInstrument(slot, kind), "New sound")
            else -> false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    override fun clear(v: ViewController) {
        if (v.state.instField < 0 || (v.state.soundPanel("sms").editBlocked && v.editHeld)) return
        if (SmsSoundFace.friendly(v.state) && v.state.instField >= 2) return
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val row = row(song, st)
        val kind = kind(song, st)
        when (row) {
            Row.SLOT -> session.setSmsInstrument(slot, null, "Empty sound").also { session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused") }
            Row.PRESET -> session.setSmsInstrument(slot, defaultSmsInstrument(slot, kind), "Reset sound")
            Row.NAME -> session.renameSms(slot) { ViewController.deleteChar(it, st.textPos) }
            Row.PATCH -> session.editSms<SmsFmInstrument>(slot, SmsInstrumentKind.FM, "Reset instrument") { it.copy(patch = 1) }
            Row.LEVEL, Row.FEEDBACK -> session.editSms<SmsFmInstrument>(slot, SmsInstrumentKind.FM, "Reset ${row.label.lowercase()}") {
                val p = it.custom
                it.copy(custom = if (row == Row.LEVEL) p.copy(level = 0) else p.copy(feedback = 0))
            }
            in GRID -> session.editSms<SmsFmInstrument>(slot, SmsInstrumentKind.FM, "Reset ${row.label}") {
                val op = operator(st)
                val d = OpllOperator()
                val o = if (op == 0) it.custom.modulator else it.custom.carrier
                val reset = when (row) {
                    Row.OP_MULT -> o.copy(mult = d.mult)
                    Row.OP_AM -> o.copy(am = d.am)
                    Row.OP_VIB -> o.copy(vibrato = d.vibrato)
                    Row.OP_EG -> o.copy(sustained = d.sustained)
                    Row.OP_KSR -> o.copy(ksr = d.ksr)
                    Row.OP_KSL -> o.copy(ksl = d.ksl)
                    Row.OP_WAVE -> o.copy(rectified = d.rectified)
                    Row.OP_AR -> o.copy(ar = d.ar)
                    Row.OP_DR -> o.copy(dr = d.dr)
                    Row.OP_SL -> o.copy(sl = d.sl)
                    else -> o.copy(rr = d.rr)
                }
                it.copy(custom = if (op == 0) it.custom.copy(modulator = reset) else it.custom.copy(carrier = reset))
            }
            Row.KIT_MODE -> session.editSms<SmsDrumKit>(slot, SmsInstrumentKind.KIT, "Kit pitched by notes") { it.copy(fixed = false) }
            in KIT_PITCHES -> session.editSms<SmsDrumKit>(slot, SmsInstrumentKind.KIT, "Reset ${row.label.lowercase()}") {
                val d = SmsDrumKit()
                when (row) {
                    Row.KIT_KICK -> it.copy(kick = d.kick)
                    Row.KIT_SNARE -> it.copy(snareHat = d.snareHat)
                    else -> it.copy(tomCymbal = d.tomCymbal)
                }
            }
            Row.LENGTH -> envelopeKind(song, st)?.let { k -> session.editSmsEnvelope(slot, k, "Empty envelope") { NesEnvelope() } }
            Row.LOOP -> envelopeKind(song, st)?.let { k -> session.editSmsEnvelope(slot, k, "Clear loop") { it.copy(loop = -1) } }
            Row.ENV_RELEASE -> envelopeKind(song, st)?.let { k -> session.editSmsEnvelope(slot, k, "Clear release") { it.copy(release = -1) } }
            Row.STEPS -> envelopeKind(song, st)?.let { k ->
                session.editSmsEnvelope(slot, k, "Delete step") { NesEnvelopes.removed(k, it, st.envStep) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
            }
            else -> Unit // Listen, Type, Page
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val ins = sound(song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        val ch = listenChannel(song, st, ins)
        if (ch != st.listenChannel) v.session.note("${ins.name.ifEmpty { "This sound" }} is ${typeText(ins.kind)}: listening on ${song.system.channels[ch].name}")
        v.startAudition(ch, v.defaultNote(), st.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        if (SmsSoundFace.command(v, a)) return true
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val row = row(song, st)
        val env = envelopeKind(song, st)
        val onEnvelope = env != null && row !in HEAD
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                val d = if (a == Action.NEXT_CHANNEL) 1 else -1
                if (row in GRID) st.envStep = (st.envStep + d).coerceIn(0, 1) else showPage(v, st.envelope + d)
            }
            Action.COPY -> if (onEnvelope) session.copySmsEnvelope(slot, env!!) else session.copySmsInstrument(slot)
            Action.PASTE -> { session.pasteSms(slot, env); keepPage(v, row) }
            Action.CUT -> if (onEnvelope) {
                session.copySmsEnvelope(slot, env!!)
                session.editSmsEnvelope(slot, env, "Cut envelope") { NesEnvelope() }
            } else {
                session.copySmsInstrument(slot)
                session.setSmsInstrument(slot, null, "Cut sound")
                keepPage(v, row)
            }
            Action.DUPLICATE -> {
                if (slot >= SmsSystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(song, st) ?: return true
                session.setSmsInstrument(slot + 1, src.named((src.name + "+").take(NAME_MAX)), "Duplicate sound")
                st.instrument++
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        if (SmsSoundFace.friendly(v.state) || v.state.instField < 0) { SmsSoundFace.hints(v, h); return }
        val st = v.state
        val song = v.session.song
        val row = row(song, st)
        val ins = sound(song, st)
        when {
            row == Row.SLOT -> {
                h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots")
                h += Hint(v.k(Action.EDIT), if (ins == null) "Make a sound here" else "Use for entry")
                h += Hint(v.k(Action.CLEAR), "Empty the slot")
            }
            row == Row.NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            row == Row.LISTEN -> h += Hint(v.lr(), "Channel Listen plays on")
            row == Row.PAGE -> h += Hint(v.lr(), "Page")
            row == Row.TYPE -> h += Hint(v.k(Action.EDIT), "Next kind of sound")
            row == Row.PRESET -> { h += Hint(v.editLr(), "Start from a preset"); h += Hint(v.k(Action.CLEAR), "Default sound") }
            row == Row.PATCH -> { h += Hint(v.editLr(), "Built-in instrument or custom"); h += Hint(v.k(Action.CLEAR), "Violin") }
            row in GRID -> {
                h += Hint(v.lr(), "Modulator / carrier"); h += Hint(v.channels(), "Modulator / carrier")
                if (row in TOGGLES) h += Hint(v.k(Action.EDIT), "Switch ${row.label}") else h += Hint(v.editLr(), "${row.label} ±1")
                h += Hint(v.k(Action.CLEAR), "Default")
            }
            row == Row.STEPS -> {
                h += Hint(v.lr(), "Step"); h += Hint(v.editUd(), "Value"); h += Hint(v.editLr(), "Draw value along")
                h += Hint(v.k(Action.CLEAR), "Delete step")
            }
            row == Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "Empty envelope") }
            row == Row.LOOP -> { h += Hint(v.editLr(), "Loop start"); h += Hint(v.k(Action.CLEAR), "No loop") }
            row == Row.ENV_RELEASE -> { h += Hint(v.editLr(), "Release step"); h += Hint(v.k(Action.CLEAR), "No release") }
            row == Row.KIT_MODE -> h += Hint(v.k(Action.EDIT), "Pitched by notes / fixed")
            row in KIT_PITCHES -> { h += Hint(v.editLr(), "Semitone (${v.ud()} octave)"); h += Hint(v.k(Action.CLEAR), "Default") }
            else -> { h += Hint(v.editLr(), "±1 (${v.ud()} coarse)"); h += Hint(v.k(Action.CLEAR), "Default") }
        }
        if (row !in HEAD && row !in GRID && row != Row.STEPS) h += Hint(v.lr(), "Sound slot")
        if (row !in GRID) h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(listenChannel(song, st, ins))?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${song.system.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (envelopeKind(song, st) != null && row !in HEAD) "Copy/paste envelope" else "Copy/paste sound")
    }

    fun helpSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val listen = keys.label(Action.AUDITION)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        val editLr = keys.held(Action.EDIT, lr)
        return listOf(
            "Sound face" to listOf(
                ud to "Select sound slot; $lr cycles musical controls, Name and Sound/Advanced",
                editLr to "Shape the selected sound control; on Name change a letter, Edit+Up/Down moves the caret",
                channels to "Choose the audition channel; on empty slots this also chooses PSG, FM or kit. $listen listens, octave shortcuts change pitch",
                "Touch" to "Drag a control; compact panes have < > paging. + / - selects slots, Listen cycles channels. Sound/Advanced never changes the patch",
                "PSG" to "Contour chooses Held, Pluck or Swell, Chord an arpeggio, Grain a noise recipe. Complex programs stay Custom until explicitly replaced; Grain is unavailable when a pitch macro occupies the extra envelope",
                "Game Gear" to "Stereo belongs to pattern pan effects, not instruments; the Sound face never invents a patch pan",
                "FM" to "Voice recalls built-ins or the preserved custom patch. Brightness, Harmonics, Edge and Contour edit only Custom; one shared custom voice means the latest instrument load wins",
                "Rhythm" to "Tuning chooses notes or a fixed kit. Kick owns its pitch; snare/hat and tom/cymbal each share a pitch. Fixed pitches remain stored while note tracking is active",
                "Advanced" to "The original pages below retain all registers, macros, presets and PSG/FM/kit Type creation",
            ),
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, type, page, then the page's settings",
                lr to "On settings: the sound slot; on the voice grid: modulator or carrier; on envelope steps: the step",
                channels to "Pages. PSG sounds: Volume, Arpeggio, Pitch, Noise. FM voices: Voice, Volume, Arpeggio. Drum kits: Kit",
                "Listen on" to "The channel $listen plays the sound on (each channel plays its own kind). Holding $edit on a setting sounds it",
                "Type" to "PSG sound, FM voice or drum kit (with the FM unit); $edit switches, giving the slot that kind's starting sound",
                editLr to "Change the value (${keys.held(Action.EDIT, ud)} in bigger steps); lists step through their choices",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
            ),
            "PSG sounds" to listOf(
                "Volume" to "Level 0-15 per tick (Banjo's volume macro), loop and release: after OFF the loop runs out into the release part",
                "Arpeggio" to "Semitones added to the note per tick (−64..63)",
                "Pitch" to "1/128 semitone per tick (±½ semitone; Banjo resolves 1/16). One extra envelope per sound: pitch or noise",
                "Noise" to "The SN76489's noise mode per tick: 0 periodic, 1 white, 2/3 the same pitched by Square 3. " +
                    "Fixed rates come from the note: C low, C# middle, D-B high",
            ),
            "FM voices and kits" to listOf(
                "Instrument" to "One of the YM2413's 15 built-in instruments, or Custom: the one voice the chip holds for all channels " +
                    "(choosing it starts from the built-in's registers; a song playing two custom voices at once hears the last loaded)",
                "Voice grid" to "Mod level and feedback, then per operator MULT, AM, VIB, EG (sustained/percussive), KSR, KSL, Wave (sine/half), AR, DR, SL, RR",
                "Kit" to "Rhythm mode drums: pitched by each drum's note (snare and hi-hat share a pitch, so do tom and cymbal) or three fixed pitches " +
                    "the kit sets when a drum channel loads it",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = SmsSoundPainter(pane)
}
