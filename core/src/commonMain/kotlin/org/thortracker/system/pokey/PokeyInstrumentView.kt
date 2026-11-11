package org.thortracker.system.pokey

import kotlin.math.roundToInt
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
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The POKEY instrument editor, controller-only: the one bank of [PokeySystem.SLOTS] sounds. Head rows: slot, name, the
 * channel Listen plays on, the sound's kind, the page. Pages: *Sound* (preset, clock, the high-pass offset and
 * difference mode, the partner channels, the pitch mode; beside them which POKEY channels a note takes and how in tune
 * it plays), the FamiStudio-style envelopes *Volume*, *Arpeggio*, *Pitch*, *Distortion* (edited step by step as on the
 * NES), and for sample sounds *Sample* (the song sample it plays, root note, rate, loop, the song's samples and adding
 * more). Sample sounds have no volume or distortion envelope (the player does not use them).
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index among
 * [pages]), [ViewState.envStep] (the envelope step, size = append), [ViewState.listenChannel], [ViewState.dpcmSample]
 * (the sample list cursor), [ViewState.textPos] (sound or sample name). Holding Edit on a sound row sounds the sound
 * and every change restarts it, so edits are heard as made.
 */
object PokeyInstrumentView : SystemView() {
    enum class Page(val label: String, val envelope: PokeyEnvelopeKind? = null) {
        SOUND("Sound"),
        VOLUME("Volume", PokeyEnvelopeKind.VOLUME),
        ARPEGGIO("Arpeggio", PokeyEnvelopeKind.ARPEGGIO),
        PITCH("Pitch", PokeyEnvelopeKind.PITCH),
        DISTORTION("Distortion", PokeyEnvelopeKind.DISTORTION),
        SAMPLE("Sample"),
    }

    /** Pages of tone sounds (every kind but samples) and of sample sounds. */
    val TONE_PAGES = listOf(Page.SOUND, Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.DISTORTION)
    val SAMPLE_PAGES = listOf(Page.SOUND, Page.SAMPLE, Page.ARPEGGIO, Page.PITCH)

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), KIND("Kind"), PAGE("Page"),
        PRESET("Preset"), CLOCK("Clock"), FILTER("HP offset"), DIFFERENCE("Difference"), PARTNER_DIST("Partner dist"),
        PARTNER_VOL("Partner vol"), MODE("Pitch mode"),
        LENGTH("Length"), LOOP("Loop"), ENV_RELEASE("Release"), STEPS("Steps"),
        SAMPLE("Sample"), ROOT("Root note"), RATE("Rate"), SAMPLE_LOOP("Loop from"), SAMPLES("Samples"), SAMPLE_NAME("Name"), ADD("Add"),
    }

    val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.KIND, Row.PAGE)

    /** Sample page rows drawn inside the sample box. */
    val BOX_ROWS = setOf(Row.SAMPLES, Row.SAMPLE_NAME, Row.ADD)

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = setOf(Row.KIND, Row.PRESET, Row.CLOCK, Row.FILTER, Row.DIFFERENCE, Row.PARTNER_DIST, Row.PARTNER_VOL, Row.MODE,
        Row.LENGTH, Row.LOOP, Row.ENV_RELEASE, Row.SAMPLE, Row.ROOT, Row.RATE, Row.SAMPLE_LOOP)

    /** On/off settings: a tap switches them. */
    private val TOGGLES = setOf(Row.CLOCK, Row.DIFFERENCE, Row.MODE)

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = POKEY_NAME_MAX

    fun sound(song: Song, state: ViewState): PokeyInstrument? = song.pokeyInstrument(state.instrument)

    /** The kind the pages follow: the sound's, or 8-bit for an empty slot. */
    fun kind(song: Song, state: ViewState): PokeyKind = sound(song, state)?.kind ?: PokeyKind.TONE

    fun pages(song: Song, state: ViewState): List<Page> = if (kind(song, state) == PokeyKind.SAMPLE) SAMPLE_PAGES else TONE_PAGES

    fun page(song: Song, state: ViewState): Page = pages(song, state).let { it[state.envelope.coerceIn(0, it.size - 1)] }

    fun rows(song: Song, state: ViewState): List<Row> {
        val kind = kind(song, state)
        return HEAD + when (page(song, state)) {
            Page.SOUND -> listOf(Row.PRESET, Row.CLOCK) +
                (if (kind.filtered) listOf(Row.FILTER, Row.DIFFERENCE) else emptyList()) +
                (if (kind.joined || kind.filtered) listOf(Row.PARTNER_DIST, Row.PARTNER_VOL) else emptyList()) +
                Row.MODE
            Page.PITCH -> listOf(Row.LENGTH, Row.LOOP, Row.ENV_RELEASE, Row.MODE, Row.STEPS)
            Page.SAMPLE -> listOf(Row.SAMPLE, Row.ROOT, Row.RATE, Row.SAMPLE_LOOP, Row.SAMPLES, Row.SAMPLE_NAME, Row.ADD)
            else -> listOf(Row.LENGTH, Row.LOOP, Row.ENV_RELEASE, Row.STEPS)
        }
    }

    fun row(song: Song, state: ViewState): Row = rows(song, state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The envelope a page shows, or null. */
    fun envelopeKind(song: Song, state: ViewState): PokeyEnvelopeKind? = page(song, state).envelope

    /** The furthest step the cursor reaches on an envelope: the append position after the last step, while there is room. */
    fun lastStep(e: NesEnvelope): Int = minOf(e.values.size, NesEnvelope.MAX_LENGTH - 1)

    /** The envelope the page shows (an empty slot's: a new sound's). */
    fun envelope(song: Song, state: ViewState): NesEnvelope? {
        val kind = envelopeKind(song, state) ?: return null
        return sound(song, state)?.let { kind.of(it) } ?: kind.default
    }

    /** The channel Listen plays [ins] on: the Listen channel when the kind plays there, else the kind's first channel. */
    fun listenChannel(state: ViewState, ins: PokeyInstrument?): Int = when {
        ins == null || state.listenChannel in ins.kind.lanes -> state.listenChannel
        else -> ins.kind.lanes.min()
    }

    /** The note Listen plays: a sample sound's root (its rate as set), else C of the listening octave. */
    fun listenNote(v: ViewController, ins: PokeyInstrument?): Int =
        if (ins != null && ins.kind == PokeyKind.SAMPLE) ins.root else v.defaultNote()

    /** "POKEY 1, 3" for 0-based channels. */
    fun channelsText(channels: Collection<Int>): String = "POKEY " + channels.sorted().joinToString(", ") { (it + 1).toString() }

    /**
     * What each POKEY channel (0..3) does for a note of [kind] on [lane]: "♪ tone", "low byte", "high-pass clock", …;
     * null for channels it leaves alone. All null when the kind cannot play on [lane].
     */
    fun roles(kind: PokeyKind, lane: Int): List<String?> {
        val taken = kind.taken(lane)
        return List(4) { ch ->
            if (ch !in taken) null else when (kind) {
                PokeyKind.TONE -> "♪ 8-bit"
                PokeyKind.SAMPLE -> "♪ samples"
                PokeyKind.JOINED -> if (ch == lane) "♪ high byte" else "low byte"
                PokeyKind.FILTERED -> if (ch == lane) "♪ tone" else "high-pass clock"
                PokeyKind.JOINED_FILTERED -> when (ch) { 1 -> "♪ high byte"; 0 -> "low byte"; 3 -> "clock high"; else -> "clock low" }
            }
        }
    }

    /** Whether [ins] plays at 1.79 MHz on [lane] ([PokeyKind.fastLanes]). */
    fun fastOn(ins: PokeyInstrument, lane: Int): Boolean = ins.fast && lane in ins.kind.fastLanes

    /** The song's base clock: "64 kHz" or "15 kHz" (the song's start setting; `Kxx` changes it while playing). */
    fun baseText(song: Song): String = if (song.pokey.base15) "15 kHz" else "64 kHz"

    /**
     * How [note] of [ins] plays on [lane] at the song's machine and base clock, from [PokeyPitch]: "A-4 440.1 Hz +0¢"
     * (the first step of the distortion envelope, else pure tone); samples give their level rate and how far it is from
     * the rate the note asks for; a counter at the end of its range (the note was clamped) is flagged; volume only and
     * channels the kind cannot use say so.
     */
    fun tuningText(song: Song, ins: PokeyInstrument, lane: Int, note: Int): String {
        val p = song.pokey
        val name = PokeySystem.noteName(note)
        if (lane !in ins.kind.lanes) return "$name: cannot play on POKEY ${lane + 1}"
        val dist = if (ins.kind == PokeyKind.SAMPLE) PokeyDistortion.VOLUME_ONLY else ins.distortion.values.firstOrNull() ?: PokeyDistortion.PURE
        if (dist == PokeyDistortion.VOLUME_ONLY && ins.kind != PokeyKind.SAMPLE) return "$name: volume only, a level with no pitch"
        val pitch = note * 64
        val hz = PokeyPitch.hz(p.machine, p.base15, ins, lane, pitch, dist)
        if (hz.isNaN()) return "$name: cannot play on POKEY ${lane + 1}"
        val cents = PokeyPitch.centsText(PokeyPitch.cents(p.machine, p.base15, ins, lane, pitch, dist))
        val limit = if (PokeyPitch.atLimit(ins, lane, PokeyPitch.chosen(p.machine, p.base15, ins, lane, pitch, dist))) " · out of the counter's range" else ""
        if (ins.kind == PokeyKind.SAMPLE) return "$name: ${hz.roundToInt()} levels/s $cents$limit"
        val noise = !PokeyDistortion.pitched(dist)
        return "$name ${if (noise) "noise at the rate of " else ""}${PokeyPitch.hzText(hz)} $cents$limit"
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, PokeySystem.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envelope = st.envelope.coerceIn(0, pages(song, st).size - 1)
        st.instField = st.instField.coerceIn(-1, if (PokeySoundFace.friendly(st)) PokeySoundFace.COUNT + 1 else rows(song, st).size - 1)
        PokeySoundFace.settle(st)
        envelope(song, st)?.let { st.envStep = st.envStep.coerceIn(0, lastStep(it)) }
        st.dpcmSample = st.dpcmSample.coerceIn(0, maxOf(0, song.pokey.samples.size - 1))
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        if (state.instrument != subject) {
            PokeySoundFace.panel(state).invalidate()
            PokeySoundFace.panel(state).editBlocked = true
        }
        state.instrument = subject.coerceIn(1, PokeySystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        if (state.instrument != selection.instrument || state.listenChannel != selection.channel) {
            PokeySoundFace.panel(state).invalidate()
            PokeySoundFace.panel(state).editBlocked = true
        }
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Selects the slot for entry. */
    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        session.select(session.selection.copy(bank = 0, instrument = v.state.instrument, context = ViewKind.KEYBOARD))
    }

    /** Shows page [index] (wrapping), keeping the cursor on the same row where the new page has it. */
    fun showPage(v: ViewController, index: Int) {
        val st = v.state
        val song = v.session.song
        val row = row(song, st)
        st.envelope = index.mod(pages(song, st).size)
        val rows = rows(song, st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.PAGE)
        st.envStep = 0
        if (page(song, st) == Page.SAMPLE) sound(song, st)?.let { if (it.sample in song.pokey.samples.indices) st.dpcmSample = it.sample }
    }

    /** After the slot or its kind changed: the same page where the new pages have it (else Sound), the cursor on [row]. */
    private fun keepPage(v: ViewController, row: Row, page: Page) {
        val st = v.state
        val song = v.session.song
        st.envelope = pages(song, st).indexOf(page).coerceAtLeast(0)
        val rows = rows(song, st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: st.instField.coerceIn(0, rows.size - 1)
        envelope(song, st)?.let { st.envStep = st.envStep.coerceIn(0, lastStep(it)) }
    }

    /** Moves to another slot; Listen moves to the channel that slot plays on most. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, PokeySystem.SLOTS)
        if (slot == st.instrument) return
        PokeySoundFace.invalidate(v)
        val song = v.session.song
        val row = row(song, st)
        val page = page(song, st)
        st.instrument = slot
        FmControls.mostUsedChannel(song, slot)?.let { st.listenChannel = it }
        keepPage(v, row, page)
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        if (PokeySoundFace.direction(v, dx, dy)) return
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
            row == Row.SAMPLE_NAME -> st.textPos = (st.textPos + dx).coerceIn(0, PokeySample.MAX_NAME - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, lastStep(e))
            row == Row.SAMPLES -> st.dpcmSample = (st.dpcmSample + dx).coerceIn(0, maxOf(0, song.pokey.samples.size - 1))
            else -> moveSlot(v, dx)
        }
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val d = dx + dy
        val row = row(song, st)
        val ins = sound(song, st) ?: defaultPokeyInstrument(slot)
        val changed = when (row) {
            Row.SLOT -> { moveSlot(v, dx + COARSE * dy); false }
            Row.NAME -> session.editPokeyInstrument(slot, "Rename sound") { it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, NAME_MAX)) }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(song.system.channels.size); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.KIND -> switchKind(v, PokeyKind.entries[(kind(song, st).ordinal + d).mod(PokeyKind.entries.size)])
            Row.PRESET -> applyPreset(v, d)
            Row.CLOCK -> session.setPokeyFast(slot, !ins.fast)
            Row.FILTER -> session.setPokeyFilterOffset(slot, ins.filterOffset + dx + COARSE * dy)
            Row.DIFFERENCE -> session.setPokeyDifference(slot, !ins.difference)
            Row.PARTNER_DIST -> session.setPokeyPartnerDistortion(slot, (ins.partnerDistortion + d).mod(PokeyDistortion.RANGE.last + 1))
            Row.PARTNER_VOL -> session.setPokeyPartnerVolume(slot, ins.partnerVolume + dx + 4 * dy)
            Row.MODE -> session.setPokeyPitchRelative(slot, !ins.pitchRelative)
            Row.LENGTH, Row.LOOP, Row.ENV_RELEASE -> {
                val kind = envelopeKind(song, st) ?: return
                val name = kind.label.lowercase()
                session.editPokeyEnvelope(slot, kind, "Set $name ${row.label.lowercase()}") {
                    when (row) {
                        Row.LENGTH -> PokeyEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy)
                        Row.LOOP -> PokeyEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy)
                        else -> PokeyEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy)
                    }
                }
            }
            Row.STEPS -> adjustSteps(v, dx, dy)
            Row.SAMPLE -> stepSample(v, ins, d)
            Row.ROOT -> session.setPokeyRoot(slot, ins.root + dx + 12 * dy)
            Row.RATE -> session.setPokeyRate(slot, stepRate(ins.rate, dx, dy))
            Row.SAMPLE_LOOP -> stepLoop(v, ins, dx, dy)
            Row.SAMPLES -> { st.dpcmSample = (st.dpcmSample + d).coerceIn(0, maxOf(0, song.pokey.samples.size - 1)); false }
            Row.SAMPLE_NAME -> session.renamePokeySample(st.dpcmSample) { NameEdit.edit(it, st.textPos, dx + 26 * dy, PokeySample.MAX_NAME) }
            Row.ADD -> false
        }
        if (changed) heard(v)
    }

    /**
     * A sample sound's rate stepped: ←→ by 10 Hz; ↑↓ to the next / previous import rate ([PokeySamples.IMPORT_RATES]),
     * past the last to the ends of [PokeyInstrument.RATES].
     */
    fun stepRate(rate: Int, dx: Int, dy: Int): Int {
        var r = rate + 10 * dx
        val rates = PokeySamples.IMPORT_RATES.sorted()
        if (dy > 0) repeat(dy) { r = rates.firstOrNull { it > r } ?: PokeyInstrument.RATES.last }
        if (dy < 0) repeat(-dy) { r = rates.lastOrNull { it < r } ?: PokeyInstrument.RATES.first }
        return r.coerceIn(PokeyInstrument.RATES)
    }

    /** The sample a sample sound plays, stepped through the song's samples (wrapping); the list follows. */
    private fun stepSample(v: ViewController, ins: PokeyInstrument, d: Int): Boolean {
        val samples = v.session.song.pokey.samples
        if (samples.isEmpty()) { v.session.note("The song has no samples yet: add some on the Add row"); return false }
        val cur = ins.sample.takeIf { it in samples.indices } ?: -1
        val next = (cur + d).mod(samples.size)
        val ok = v.session.setPokeySample(v.state.instrument, next)
        v.state.dpcmSample = next
        return ok
    }

    /** Loop start stepped: ←→ one level, ↑↓ a sixteenth of the sample; below 0 is off (plays once). */
    private fun stepLoop(v: ViewController, ins: PokeyInstrument, dx: Int, dy: Int): Boolean {
        val sample = v.session.song.pokey.samples.getOrNull(ins.sample)
            ?: run { v.session.note("Sound ${EditorSession.hex2(v.state.instrument)} plays no sample: choose one on the Sample row"); return false }
        val step = dx + maxOf(1, sample.levels.size / 16) * dy
        val loop = if (ins.loop < 0) (if (step > 0) step - 1 else -1) else ins.loop + step
        return v.session.setPokeyLoop(v.state.instrument, maxOf(-1, loop))
    }

    /** Envelope steps: Edit+↑↓ changes the step's value (adding one at the end), Edit+←→ carries it along. */
    private fun adjustSteps(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val session = v.session
        val kind = envelopeKind(session.song, st) ?: return false
        val slot = st.instrument
        val name = kind.label.lowercase()
        val e = envelope(session.song, st) ?: return false
        var changed = false
        if (dy != 0) {
            changed = session.editPokeyEnvelope(slot, kind, "Set $name") {
                PokeyEnvelopes.withValue(kind, it, st.envStep, PokeyEnvelopes.valueAt(kind, it, st.envStep) + dy)
            }
        }
        if (dx != 0) {
            // Drawing sideways carries the value under the cursor to the next step, appending past the end.
            val value = PokeyEnvelopes.valueAt(kind, envelope(session.song, st) ?: e, st.envStep)
            val next = (st.envStep + dx).coerceIn(0, NesEnvelope.MAX_LENGTH - 1)
            if (next == st.envStep) return changed
            st.envStep = next
            changed = session.editPokeyEnvelope(slot, kind, "Draw $name") { PokeyEnvelopes.withValue(kind, it, next, value) } || changed
            envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
        }
        return changed
    }

    /**
     * Makes the slot a [kind] sound (an empty slot gets a new one), keeping its other settings; Listen moves to a
     * channel the kind plays on, and the note says which channels it takes.
     */
    private fun switchKind(v: ViewController, kind: PokeyKind): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val page = page(session.song, st)
        val ok = session.setPokeyKind(slot, kind)
        if (st.listenChannel !in kind.lanes) st.listenChannel = kind.lanes.min()
        keepPage(v, Row.KIND, page)
        session.note(kindNote(kind, st.listenChannel))
        return ok
    }

    /** "16-bit: plays on POKEY 2, 4; on POKEY 2 it takes POKEY 1 (low byte)". */
    fun kindNote(kind: PokeyKind, lane: Int): String {
        val roles = roles(kind, lane)
        val others = roles.withIndex().filter { (ch, r) -> r != null && ch != lane }
        return "${kind.label}: plays on ${channelsText(kind.lanes)}" +
            if (others.isEmpty()) "; takes only its own channel" else "; on POKEY ${lane + 1} it also takes " +
                others.joinToString(", ") { (ch, r) -> "POKEY ${ch + 1} ($r)" }
    }

    /** The presets the editor offers: [PokeyPresets.ALL], then the starter kit's sounds when the song holds the kit. */
    fun presets(song: Song): List<PokeyPreset> {
        val kit = pokeyKitStart(song)
        return PokeyPresets.ALL + if (kit >= 0) PokeyPresets.drumKit(kit) else emptyList()
    }

    /** The preset [delta] steps from the one the sound is (or the first), replacing the sound in place. */
    private fun applyPreset(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val song = v.session.song
        val presets = presets(song)
        if (presets.isEmpty()) return false
        val page = page(song, st)
        val ins = sound(song, st)
        val preset = presets[FmControls.presetStep(presets.indexOfFirst { it.instrument == ins }, delta, presets.size)]
        val ok = v.session.setPokeyInstrument(st.instrument, preset.instrument, "Preset ${preset.name}")
        val kind = preset.instrument.kind
        if (st.listenChannel !in kind.lanes) st.listenChannel = kind.lanes.min()
        keepPage(v, Row.PRESET, page)
        v.session.note("${preset.name}: ${kindNote(kind, st.listenChannel)}")
        return ok
    }

    /** After an edit: a held Listen or Edit sounds the changed sound again. */
    private fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && row(v.session.song, v.state) in SOUND_ROWS)) audition(v)
    }

    override fun editPress(v: ViewController) {
        PokeySoundFace.panel(v.state).editBlocked = false
        if (v.state.instField < 0) return
        if (PokeySoundFace.friendly(v.state)) {
            if (v.state.instField >= 2 && sound(v.session.song, v.state) != null) audition(v)
            return
        }
        if (row(v.session.song, v.state) in SOUND_ROWS && sound(v.session.song, v.state) != null) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        val panel = PokeySoundFace.panel(v.state)
        if (panel.editBlocked) {
            panel.editBlocked = false
            if (!v.auditionHeld) v.session.releaseAudition()
            return
        }
        if (v.state.instField < 0) {
            if (tap) PokeySoundFace.selectMode(v, !panel.advanced, true)
            return
        }
        if (PokeySoundFace.friendly(v.state)) {
            if (!v.auditionHeld) v.session.releaseAudition()
            if (tap && v.state.instField == 7 && kind(v.session.song, v.state) == PokeyKind.SAMPLE) PokeySampleMenu.open(v.session, v.state.instrument)
            return
        }
        val st = v.state
        val session = v.session
        val song = session.song
        val row = row(song, st)
        if (row in SOUND_ROWS && !v.auditionHeld) session.releaseAudition()
        if (!tap) return
        val slot = st.instrument
        val ins = sound(song, st)
        val changed = when (row) {
            Row.SLOT -> if (ins == null) session.setPokeyInstrument(slot, defaultPokeyInstrument(slot), "New sound").also {
                session.note("Sound ${EditorSession.hex2(slot)}: a new 8-bit sound to start from")
            } else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            Row.KIND -> switchKind(v, PokeyKind.entries[(kind(song, st).ordinal + 1).mod(PokeyKind.entries.size)])
            Row.PRESET -> applyPreset(v, 1)
            in TOGGLES -> { adjust(v, 1, 0); false }
            Row.SAMPLE, Row.SAMPLES -> {
                val sample = song.pokey.samples.getOrNull(st.dpcmSample)
                when {
                    sample == null -> { session.note("The song has no samples yet: add some on the Add row"); false }
                    ins != null && ins.kind == PokeyKind.SAMPLE && ins.sample == st.dpcmSample -> { session.note("Sound ${EditorSession.hex2(slot)} already plays ${sample.name}"); false }
                    else -> session.setPokeySample(slot, st.dpcmSample)
                }
            }
            Row.SAMPLE_LOOP -> when {
                ins == null -> false
                ins.sample !in song.pokey.samples.indices -> { session.note("Sound ${EditorSession.hex2(slot)} plays no sample: choose one on the Sample row"); false }
                else -> session.setPokeyLoop(slot, if (ins.loop < 0) 0 else -1)
            }
            Row.ADD -> { PokeySampleMenu.open(session, slot); false }
            Row.STEPS -> {
                val kind = envelopeKind(song, st) ?: return
                val e = envelope(song, st) ?: return
                if (st.envStep == e.values.size) {
                    session.editPokeyEnvelope(slot, kind, "Add ${kind.label.lowercase()} step") {
                        PokeyEnvelopes.withValue(kind, it, st.envStep, PokeyEnvelopes.valueAt(kind, it, st.envStep))
                    }.also { envelope(session.song, st)?.let { st.envStep = lastStep(it) } }
                } else false
            }
            else -> if (ins == null && row in SOUND_ROWS) session.setPokeyInstrument(slot, defaultPokeyInstrument(slot), "New sound") else false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    override fun clear(v: ViewController) {
        if (v.state.instField < 0 || (PokeySoundFace.panel(v.state).editBlocked && v.editHeld)) return
        if (PokeySoundFace.friendly(v.state) && v.state.instField >= 2) return
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val row = row(song, st)
        val reset = PokeyInstrument()
        when (row) {
            Row.SLOT -> {
                val page = page(song, st)
                session.setPokeyInstrument(slot, null, "Empty sound")
                keepPage(v, row, page)
                session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused")
            }
            Row.NAME -> session.renamePokey(slot) { ViewController.deleteChar(it, st.textPos) }
            Row.KIND -> if (sound(song, st) != null) switchKind(v, PokeyKind.TONE)
            Row.PRESET -> {
                val page = page(song, st)
                session.setPokeyInstrument(slot, defaultPokeyInstrument(slot), "Reset sound")
                keepPage(v, row, page)
            }
            Row.CLOCK -> session.setPokeyFast(slot, false)
            Row.FILTER -> session.setPokeyFilterOffset(slot, reset.filterOffset)
            Row.DIFFERENCE -> session.setPokeyDifference(slot, false)
            Row.PARTNER_DIST -> session.setPokeyPartnerDistortion(slot, reset.partnerDistortion)
            Row.PARTNER_VOL -> session.setPokeyPartnerVolume(slot, reset.partnerVolume)
            Row.MODE -> session.setPokeyPitchRelative(slot, false)
            Row.LENGTH -> envelopeKind(song, st)?.let { k -> session.editPokeyEnvelope(slot, k, "Reset ${k.label.lowercase()} envelope") { k.default } }
            Row.LOOP -> envelopeKind(song, st)?.let { k -> session.editPokeyEnvelope(slot, k, "Clear ${k.label.lowercase()} loop") { it.copy(loop = -1) } }
            Row.ENV_RELEASE -> envelopeKind(song, st)?.let { k -> session.editPokeyEnvelope(slot, k, "Clear ${k.label.lowercase()} release") { it.copy(release = -1) } }
            Row.STEPS -> envelopeKind(song, st)?.let { k ->
                session.editPokeyEnvelope(slot, k, "Delete ${k.label.lowercase()} step") { PokeyEnvelopes.removed(k, it, st.envStep) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
            }
            Row.SAMPLE -> session.setPokeySample(slot, 0)
            Row.ROOT -> session.setPokeyRoot(slot, PokeyInstrument.DEFAULT_ROOT)
            Row.RATE -> session.setPokeyRate(slot, PokeyInstrument.DEFAULT_RATE)
            Row.SAMPLE_LOOP -> session.setPokeyLoop(slot, -1)
            Row.SAMPLES -> {
                val name = song.pokey.samples.getOrNull(st.dpcmSample)?.name ?: return
                if (session.deletePokeySample(st.dpcmSample)) {
                    normalise(v)
                    session.note("Deleted sample $name")
                }
            }
            Row.SAMPLE_NAME -> session.renamePokeySample(st.dpcmSample) { ViewController.deleteChar(it, st.textPos) }
            Row.LISTEN, Row.PAGE, Row.ADD -> Unit
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val ins = sound(v.session.song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        val ch = listenChannel(st, ins)
        if (ch != st.listenChannel) {
            v.session.note("${ins.name.ifEmpty { "This sound" }} is ${ins.kind.label}, which plays on ${channelsText(ins.kind.lanes)}: listening on POKEY ${ch + 1}")
        }
        v.startAudition(ch, listenNote(v, ins), st.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        if (PokeySoundFace.command(v, a)) return true
        if (a == Action.PASTE || a == Action.CUT || a == Action.DUPLICATE) PokeySoundFace.invalidate(v)
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val row = row(song, st)
        val kind = envelopeKind(song, st)
        val onEnvelope = kind != null && row !in HEAD
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showPage(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onEnvelope) session.copyPokeyEnvelope(slot, kind!!) else session.copyPokeyInstrument(slot)
            Action.PASTE -> {
                val page = page(song, st)
                session.pastePokey(slot, if (onEnvelope) kind else null)
                keepPage(v, row, page)
            }
            Action.CUT -> if (onEnvelope) {
                session.copyPokeyEnvelope(slot, kind!!)
                session.editPokeyEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { kind.default }
            } else {
                val page = page(song, st)
                session.copyPokeyInstrument(slot)
                session.setPokeyInstrument(slot, null, "Cut sound")
                keepPage(v, row, page)
            }
            Action.DUPLICATE -> {
                val src = sound(song, st) ?: return true
                // Into the next empty slot: no sound that exists is replaced.
                val target = (slot + 1..PokeySystem.SLOTS).firstOrNull { song.pokeyInstrument(it) == null }
                    ?: run { session.note("No empty slot after ${EditorSession.hex2(slot)} to duplicate into"); return true }
                session.setPokeyInstrument(target, src.copy(name = (src.name + "+").take(NAME_MAX)), "Duplicate sound")
                session.note("Duplicated sound ${EditorSession.hex2(slot)} into ${EditorSession.hex2(target)}")
                st.instrument = target
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        if (PokeySoundFace.friendly(v.state) || v.state.instField == -1) { PokeySoundFace.hints(v, h); return }
        val st = v.state
        val song = v.session.song
        val row = row(song, st)
        val ins = sound(song, st)
        val clear = v.k(Action.CLEAR)
        val edit = v.k(Action.EDIT)
        when (row) {
            Row.SLOT -> {
                h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots")
                h += Hint(edit, if (ins == null) "Make a sound here" else "Use for entry")
                h += Hint(clear, "Empty the slot")
            }
            Row.NAME, Row.SAMPLE_NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(clear, "Delete letter") }
            Row.LISTEN -> h += Hint(v.lr(), "Channel Listen plays on")
            Row.PAGE -> h += Hint(v.lr(), "Page")
            Row.KIND -> { h += Hint(v.editLr(), "Kind"); h += Hint(edit, "Next kind"); h += Hint(clear, "8-bit") }
            Row.PRESET -> { h += Hint(v.editLr(), "Start from a preset"); h += Hint(clear, "New 8-bit sound") }
            Row.CLOCK -> { h += Hint(edit, "1.79 MHz / ${baseText(song)} base"); h += Hint(clear, "Base clock") }
            Row.DIFFERENCE -> { h += Hint(edit, "Difference on / off"); h += Hint(clear, "Off") }
            Row.MODE -> { h += Hint(edit, "Relative / absolute"); h += Hint(clear, "Absolute") }
            Row.FILTER -> { h += Hint(v.editLr(), "Offset ±1 (${v.ud()} ±$COARSE)"); h += Hint(clear, "Offset 1") }
            Row.PARTNER_DIST -> { h += Hint(v.editLr(), "Distortion"); h += Hint(clear, "A (pure tone)") }
            Row.PARTNER_VOL -> { h += Hint(v.editLr(), "Volume ±1 (${v.ud()} ±4)"); h += Hint(clear, "Silent") }
            Row.STEPS -> {
                h += Hint(v.lr(), "Step"); h += Hint(v.editUd(), "Value"); h += Hint(v.editLr(), "Draw value along")
                h += Hint(clear, "Delete step")
            }
            Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(clear, "Empty envelope") }
            Row.LOOP -> { h += Hint(v.editLr(), "Loop start"); h += Hint(clear, "No loop") }
            Row.ENV_RELEASE -> { h += Hint(v.editLr(), "Release step"); h += Hint(clear, "No release") }
            Row.SAMPLE -> { h += Hint(v.editLr(), "Song sample"); h += Hint(edit, "Play the listed sample"); h += Hint(clear, "First sample") }
            Row.ROOT -> { h += Hint(v.editLr(), "Semitone (${v.ud()} octave)"); h += Hint(clear, PokeySystem.noteName(PokeyInstrument.DEFAULT_ROOT)) }
            Row.RATE -> { h += Hint(v.editLr(), "±10 Hz (${v.ud()} import rates)"); h += Hint(clear, "${PokeyInstrument.DEFAULT_RATE} Hz") }
            Row.SAMPLE_LOOP -> { h += Hint(v.editLr(), "Loop start (${v.ud()} ¹⁄₁₆)"); h += Hint(edit, "Loop on / off"); h += Hint(clear, "Play once") }
            Row.SAMPLES -> { h += Hint(v.lr(), "Sample"); h += Hint(edit, "This sound plays it"); h += Hint(clear, "Delete sample") }
            Row.ADD -> h += Hint(edit, "Starter kit or .wav file")
        }
        if (row !in HEAD && row != Row.STEPS && row != Row.SAMPLES && row != Row.SAMPLE_NAME) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on POKEY ${listenChannel(st, ins) + 1}")
        if (ins?.kind != PokeyKind.SAMPLE) h += Hint(v.octaves(), "Listen at ${PokeySystem.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (envelopeKind(song, st) != null && row !in HEAD) "Copy/paste envelope" else "Copy/paste sound")
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
                ud to "Choose an instrument slot; $lr cycles mode, name and musical controls",
                editLr to "Shape the selected control; on Name change a letter, $editUd moves the caret",
                "Sound / Advanced" to "Switch faces without changing the patch. Advanced retains every page and sample tool",
                "Voice" to "8-bit, paired 16-bit, high-pass, all-channel 16-bit high-pass or samples; the channel lamps show ownership",
                "Custom" to "Programs stay intact. A contour choice explicitly replaces only volume; distortion programs require Advanced",
            ),
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, kind, page, then the page's settings",
                lr to "On settings: the sound slot; on envelope steps: the step; on names: the letter",
                channels to "Pages: Sound, then the Volume, Arpeggio, Pitch and Distortion envelopes; sample sounds: Sound, Sample, Arpeggio, Pitch",
                "Listen on" to "The channel $listen plays the sound on (POKEY 1-4). A kind that cannot play there listens on its first channel. " +
                    "Holding $edit on a setting sounds the sound and every change restarts it",
                editLr to "Change the value ($editUd in bigger steps); lists step through their choices",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
                "$edit on Slot" to "An empty slot gets a new 8-bit pure tone to start from; Kind or Preset make another",
            ),
            "Kinds and channels" to listOf(
                "8-bit" to "One channel, any of POKEY 1-4: 256 pitches per clock, coarse in the high notes",
                "16-bit" to "Plays on POKEY 2 or 4 and takes 1 or 3 as its low byte: fine pitch over the whole range",
                "High-pass" to "Plays on POKEY 1 or 2; POKEY 3 or 4 clocks its high-pass filter. HP offset is the clock's counter against the tone's: " +
                    "small offsets give a slowly phasing pulse. Difference: the note is the two rates' difference (the POKEY saw), offset 1-127",
                "16-bit high-pass" to "Plays on POKEY 2 and takes all four channels",
                "Samples" to "4-bit samples through volume-only mode, on POKEY 1, 2 or 4 (timer 3 has no interrupt)",
                "Taken channels" to "A note takes every channel its kind needs, cutting the sound that held them; " +
                    "Partner dist/vol is what those channels play (volume 0: silent, the usual)",
                "Clock" to "1.79 MHz (the machine clock) for exact high notes where the kind allows it (8-bit: POKEY 1, 3; 16-bit: always; " +
                    "high-pass and samples: POKEY 1), else the song's 64 kHz or 15 kHz base clock (Song view, Kxx)",
                "Tuning" to "The Sound page shows the frequency a note really plays and how far from true pitch, for the Listen channel",
            ),
            "Envelopes" to listOf(
                "Steps" to "One value per player tick: $editUd sets a step, $editLr draws along, $edit on the + adds a step, $clear deletes",
                "Loop, Release" to "The steps from Loop repeat while the note is held; after OFF the steps from Release play out. " +
                    "A release without a loop holds the step before it until OFF",
                "Volume" to "0-15 per tick (empty: 15); the channel volume Cxx scales it",
                "Arpeggio" to "Semitones added to the note per tick",
                "Pitch" to "Sixteenths of a semitone per tick: absolute (an offset from the note) or relative (added up each tick: slides)",
                "Distortion" to "0 2 4 6 8 A C E: AUDC distortions (A = pure tone, C = 4-bit buzz, 8 = noise, 0/4 poly noises, 2/6 gated tone), " +
                    "V = volume only (a level, no pitch). Empty: A. Vxx overrides it",
            ),
            "Samples" to listOf(
                "Sample" to "$editLr: the song sample this sound plays; $edit plays the one selected in the list",
                "Root note, Rate" to "On the root note the sample plays at its rate (levels per second); other notes play faster or slower. " +
                    "$editLr: ±10 Hz, $editUd: the import rates",
                "Loop from" to "The level a held sample loops back to; $clear: plays once",
                "Samples" to "$lr: choose; $edit: this sound plays it; $clear deletes it (refused while a sound plays it). Each shows its length",
                "Add" to "$edit: the starter kit (kick, snare, hat, tom made in code; its sounds go into this and empty slots) or a .wav file, " +
                    "converted on the device at the rate you choose (higher is clearer but larger)",
                "Memory" to "All samples share 32768 levels (16 KB); at most ${PokeySystem.MAX_SAMPLES} samples",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = PokeyInstrumentPainter(pane)
}
