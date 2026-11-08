package org.thortracker.system.nes

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
import org.thortracker.system.ChannelKind
import org.thortracker.system.SystemView
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The NES instrument editor: the one shared bank, the selected sound's name, on songs with an expansion chip its chip
 * (and on VRC6 sounds the saw volume), the channel Listen plays on, its envelopes edited step by step with the
 * controller, and on 2A03 sounds a fifth page, DPCM: the sound's note → sample mappings and the song's samples
 * ([NesDpcmPage]). VRC7 sounds have no duty: their fourth page is the FM patch ([FM_PAGE]): the built-in patch or the
 * custom patch's registers per operator, and the release/stop sustain. 5B sounds have no duty either: after volume,
 * arpeggio and pitch come their mixer and noise envelopes and the hardware envelope page ([S5B_ENV_PAGE]: shape,
 * auto/manual period, octave, period). State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]),
 * [ViewState.envelope] (the page: see [pageKind], [FM_PAGE], [DPCM_PAGE], [S5B_ENV_PAGE]), [ViewState.envStep]
 * (0..size; size is the append position), [ViewState.listenChannel], on the DPCM page [ViewState.dpcmKey] and
 * [ViewState.dpcmSample], and on the FM page [ViewState.programField] (the operator column: 0 modulator, 1 carrier).
 */
object NesInstrumentView : SystemView() {
    enum class Row {
        SLOT, NAME, CHIP, SAW, LISTEN, ENVELOPE, LENGTH, LOOP, RELEASE, MODE, STEPS,
        // DPCM page: the mapping of one key, then the song's samples
        KEY, SAMPLE, PITCH, SAMPLE_LOOP, LEVEL, SAMPLES, SAMPLE_NAME, SAMPLE_LEVEL, ADD,
        // FM page (VRC7 sounds): the patch, the sustain overrides, then one row per patch register field
        PATCH, SUSTAIN_RELEASE, SUSTAIN_STOP,
        FM_MULT, FM_WAVE, FM_LEVEL, FM_FEEDBACK, FM_AR, FM_DR, FM_SL, FM_RR, FM_EG, FM_KSL, FM_KSR, FM_AM, FM_VIB,
        // 5B envelope page (5B sounds): the shape, auto or manual period, the auto octave, the manual period
        ENV_SHAPE, ENV_AUTO, ENV_OCTAVE, ENV_PERIOD,
    }

    /** The page after the four envelopes: DPCM mappings and samples (2A03 sounds only). */
    const val DPCM_PAGE = 4

    /** VRC7 sounds: the FM patch page, in the duty envelope's place (the FM channels have no duty). */
    val FM_PAGE = NesEnvelopeKind.DUTY.ordinal

    /** 5B sounds: the hardware envelope page, after volume, arpeggio, pitch, mixer and noise. */
    const val S5B_ENV_PAGE = 5

    /** The envelope each page of a sound shows, by chip (the 2A03 and VRC6 four; the 5B's mixer and noise in duty's place). */
    private val APU_PAGES = listOf(NesEnvelopeKind.VOLUME, NesEnvelopeKind.ARPEGGIO, NesEnvelopeKind.PITCH, NesEnvelopeKind.DUTY)
    private val S5B_PAGES = listOf(NesEnvelopeKind.VOLUME, NesEnvelopeKind.ARPEGGIO, NesEnvelopeKind.PITCH, NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE)

    /** The patch register field each FM row edits. */
    val FM_PARAMS: Map<Row, Vrc7Param> = Vrc7Param.entries.associateBy { Row.valueOf("FM_${it.name}") }

    private val ENVELOPE_ROWS = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.ENVELOPE, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE, Row.STEPS)
    private val ROWS = NesEnvelopeKind.entries.associateWith { kind ->
        ENVELOPE_ROWS.filter { (it != Row.RELEASE || kind.canRelease) && (it != Row.MODE || kind == NesEnvelopeKind.PITCH) }
    }

    /** DPCM plays only on its own channel, so the page has no Listen row. */
    val DPCM_ROWS = listOf(Row.SLOT, Row.NAME, Row.ENVELOPE, Row.KEY, Row.SAMPLE, Row.PITCH, Row.SAMPLE_LOOP, Row.LEVEL,
        Row.SAMPLES, Row.SAMPLE_NAME, Row.SAMPLE_LEVEL, Row.ADD)

    /** The FM page (only on VRC7 songs, so it has the Chip row). */
    val FM_ROWS = listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE, Row.PATCH, Row.SUSTAIN_RELEASE, Row.SUSTAIN_STOP) +
        Vrc7Param.entries.map { Row.valueOf("FM_${it.name}") }

    /** The 5B envelope page (only on 5B songs, so it has the Chip row). */
    val S5B_ROWS = listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE, Row.ENV_SHAPE, Row.ENV_AUTO, Row.ENV_OCTAVE, Row.ENV_PERIOD)

    /** Envelope rows jump by this much with the coarse ([Action.UP]/[Action.DOWN]) direction while editing. */
    private const val COARSE = 8

    /** Whether the DPCM page of a 2A03 sound is shown. */
    fun dpcmPage(song: Song, state: ViewState): Boolean = state.envelope == DPCM_PAGE && chip(song, state) == NesChip.APU

    /** Whether the FM patch page of a VRC7 sound is shown. */
    fun fmPage(song: Song, state: ViewState): Boolean = state.envelope == FM_PAGE && chip(song, state) == NesChip.VRC7

    /** Whether the hardware envelope page of a 5B sound is shown. */
    fun s5bEnvPage(song: Song, state: ViewState): Boolean = state.envelope == S5B_ENV_PAGE && chip(song, state) == NesChip.S5B

    /** The envelope page [page] of a [chip] sound shows, or null for its DPCM, FM or 5B envelope page. */
    fun pageKind(chip: NesChip, page: Int): NesEnvelopeKind? = when (chip) {
        NesChip.S5B -> S5B_PAGES.getOrNull(page)
        NesChip.VRC7 -> APU_PAGES.getOrNull(page)?.takeIf { it != NesEnvelopeKind.DUTY }
        else -> APU_PAGES.getOrNull(page)
    }

    /**
     * The envelope shown; on the DPCM, FM and 5B envelope pages the page's neighbour (duty, or noise on the 5B: callers
     * check [dpcmPage], [fmPage] and [s5bEnvPage] first).
     */
    fun kind(song: Song, state: ViewState): NesEnvelopeKind = if (chip(song, state) == NesChip.S5B) {
        S5B_PAGES[state.envelope.coerceIn(0, S5B_PAGES.size - 1)]
    } else {
        APU_PAGES[state.envelope.coerceIn(0, APU_PAGES.size - 1)]
    }

    /** The chip of the shown sound (2A03 when there is none). */
    fun chip(song: Song, state: ViewState): NesChip = sound(song, state)?.chip ?: NesChip.APU

    /**
     * Pages of the shown sound: its four envelopes, and DPCM on 2A03 sounds (only they map samples); a VRC7 sound's
     * volume, arpeggio and pitch envelopes and its FM patch; a 5B sound's volume, arpeggio, pitch, mixer and noise
     * envelopes and its hardware envelope.
     */
    fun pages(song: Song, state: ViewState): Int = when (chip(song, state)) {
        NesChip.APU -> DPCM_PAGE + 1
        NesChip.S5B -> S5B_ENV_PAGE + 1
        else -> DPCM_PAGE
    }

    /**
     * The rows of the shown page. Songs with an expansion chip add Chip after Name, and VRC6 sounds the saw volume;
     * 2A03-only songs keep the rows they always had.
     */
    fun rows(state: ViewState, song: Song): List<Row> {
        if (dpcmPage(song, state)) return DPCM_ROWS
        if (fmPage(song, state)) return FM_ROWS
        if (s5bEnvPage(song, state)) return S5B_ROWS
        val base = ROWS.getValue(kind(song, state))
        if (song.nes.expansion == NesExpansion.NONE) return base
        val extra = if (chip(song, state) == NesChip.VRC6) listOf(Row.CHIP, Row.SAW) else listOf(Row.CHIP)
        return base.take(2) + extra + base.drop(2)
    }

    fun row(state: ViewState, song: Song): Row = rows(state, song).let { it[state.instField.coerceIn(0, it.size - 1)] }

    fun sound(song: Song, state: ViewState): NesInstrument? = song.nesInstrument(state.instrument)

    /** The shown envelope; null on the FM and 5B envelope pages (no step envelope there). */
    fun envelope(song: Song, state: ViewState): NesEnvelope? =
        if (fmPage(song, state) || s5bEnvPage(song, state)) null else sound(song, state)?.let { kind(song, state).of(it) }

    /** The operator column of the FM page: 0 modulator, 1 carrier (modulator-only fields always the modulator). */
    fun fmOperator(state: ViewState, param: Vrc7Param): Int = if (param.modulatorOnly) 0 else state.programField.coerceIn(0, 1)

    /** A patch field's value as the editor shows it. */
    fun fmValueText(param: Vrc7Param, value: Int): String = when (param) {
        Vrc7Param.MULT -> if (value == 0) "×½" else "×${listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 12, 12, 15, 15)[value - 1]}"
        Vrc7Param.WAVE -> if (value == 0) "sine" else "half"
        Vrc7Param.EG, Vrc7Param.KSR, Vrc7Param.AM, Vrc7Param.VIB -> if (value == 0) "off" else "on"
        Vrc7Param.LEVEL -> if (value == 0) "0 dB" else "-${org.thortracker.model.NumberText.fixed(value * 0.75, 2)} dB"
        Vrc7Param.KSL -> listOf("0", "1.5", "3", "6")[value] + " dB/oct"
        else -> value.toString()
    }

    /** The furthest step the cursor reaches: the append position after the last step, while there is room. */
    fun lastStep(e: NesEnvelope): Int = minOf(e.values.size, NesEnvelope.MAX_LENGTH - 1)

    /**
     * Why [channel] ignores (or treats specially) envelope [kind] of a [chip] sound, or null when it plays it as written;
     * [shape] is a 5B sound's hardware envelope (its level then replaces the volume envelope's).
     */
    fun channelNote(kind: NesEnvelopeKind, channel: ChannelKind, chip: NesChip = NesChip.APU, saw: Vrc6SawVolume = Vrc6SawVolume.HALF,
                    channelChip: NesChip = chip, shape: S5bShape = S5bShape.OFF): String? = when {
        channelChip != chip -> "${chip.label} sounds play on the ${chip.label} channels only"
        channel == ChannelKind.SAMPLE -> "DPCM plays samples: envelopes do not apply"
        channel == ChannelKind.SAW -> when (kind) {
            NesEnvelopeKind.VOLUME -> "Saw: volume ${sawText(saw)}"
            NesEnvelopeKind.DUTY -> "The saw has no duty"
            else -> null
        }
        channel == ChannelKind.VOICE -> when (kind) {
            NesEnvelopeKind.VOLUME -> "FM: the carrier's 4-bit level, 3 dB per step"
            NesEnvelopeKind.PITCH -> "FM: + raises the pitch (frequency steps × 8)"
            NesEnvelopeKind.DUTY -> "The FM channels have no duty"
            else -> null
        }
        chip == NesChip.S5B -> when (kind) {
            NesEnvelopeKind.VOLUME -> if (shape == S5bShape.OFF) "5B: 3 dB per step, 0 silences" else "Not heard: the 5B envelope (${shape.label.lowercase()}) sets the level"
            NesEnvelopeKind.MIXER -> "Squares share one noise generator"
            NesEnvelopeKind.NOISE -> "Shared: the highest sounding square sets the rate"
            else -> null
        }
        kind == NesEnvelopeKind.VOLUME -> if (channel == ChannelKind.TRIANGLE) "Triangle has no volume: 0 silences it, any other value plays full" else null
        kind == NesEnvelopeKind.DUTY -> when (channel) {
            ChannelKind.TRIANGLE -> "Triangle ignores duty"
            ChannelKind.NOISE -> "On Noise, duty is the mode: 0 long noise, 1 short (metallic)"
            else -> null
        }
        kind == NesEnvelopeKind.ARPEGGIO -> if (channel == ChannelKind.NOISE) "On Noise, arpeggio steps the noise rate" else null
        else -> if (channel == ChannelKind.NOISE) "On Noise, pitch moves the noise rate" else null
    }

    /** "× 2 = accumulator rate 0-30": how [saw] turns volume 0-15 into the saw's rate (above 42 the saw distorts). */
    fun sawText(saw: Vrc6SawVolume): String {
        val top = 15 shl saw.shift
        return "× ${1 shl saw.shift} = rate 0-$top" + if (top > 42) " (distorts above ${42 shr saw.shift})" else ""
    }

    /** One line per envelope: what each channel does with it. */
    fun envelopeSummary(kind: NesEnvelopeKind, chip: NesChip = NesChip.APU): String = when (chip) {
        NesChip.APU -> when (kind) {
            NesEnvelopeKind.VOLUME -> "Pulse and Noise: level 0-15 per frame. Triangle: no volume, 0 silences it"
            NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per frame. Noise: steps the noise rate"
            NesEnvelopeKind.PITCH -> "Period offset per frame (relative: added up each frame). Noise: moves the rate"
            NesEnvelopeKind.DUTY -> "Pulse: 12.5, 25, 50, 75%. Noise: 0 long, 1 short noise. Triangle: ignored"
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "5B sounds only"
        }
        NesChip.VRC6 -> when (kind) {
            NesEnvelopeKind.VOLUME -> "VRC6 pulses: level 0-15 per frame. Saw: 0-15 times the saw volume (its accumulator rate)"
            NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per frame"
            NesEnvelopeKind.PITCH -> "Period offset per frame (relative: added up each frame)"
            NesEnvelopeKind.DUTY -> "VRC6 pulses: 0-7 = 1/16 to 8/16 (6.25-50%). Saw: ignored"
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "5B sounds only"
        }
        NesChip.VRC7 -> when (kind) {
            NesEnvelopeKind.VOLUME -> "FM channels: carrier level 0-15 per frame (the chip's volume, 3 dB steps; 0 is not silent)"
            NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per frame"
            NesEnvelopeKind.PITCH -> "Frequency offset per frame, × 8 frequency steps: + raises the pitch (relative: added up each frame)"
            NesEnvelopeKind.DUTY -> "The FM channels have no duty: this page is the FM patch"
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "5B sounds only"
        }
        NesChip.S5B -> when (kind) {
            NesEnvelopeKind.VOLUME -> "5B squares: level 0-15 per frame, 3 dB per step (0 silent); ignored while a hardware envelope plays"
            NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per frame (an auto-period envelope follows it)"
            NesEnvelopeKind.PITCH -> "Period offset per frame (relative: added up each frame)"
            NesEnvelopeKind.DUTY -> "The 5B squares have no duty"
            NesEnvelopeKind.MIXER -> "Per frame: tone+noise (the square gated by noise), noise, tone, or off (a constant level). Empty: tone"
            NesEnvelopeKind.NOISE -> "Noise period 0-31 per frame (higher is lower). One generator: the highest sounding square sets it"
        }
    }

    /** A step value as the editor writes it: duty as a fraction or percentage (or noise mode), offsets signed, the 5B mixer by name. */
    fun valueText(kind: NesEnvelopeKind, value: Int, channel: ChannelKind?, chip: NesChip = NesChip.APU): String = when (kind) {
        NesEnvelopeKind.VOLUME, NesEnvelopeKind.NOISE -> value.toString()
        NesEnvelopeKind.ARPEGGIO, NesEnvelopeKind.PITCH -> if (value > 0) "+$value" else value.toString()
        NesEnvelopeKind.DUTY -> when {
            chip == NesChip.VRC6 -> NesRules.dutyText(chip, value.coerceIn(chip.duty))
            channel == ChannelKind.NOISE -> if (value and 1 == 0) "long" else "short"
            else -> when (value) { 0 -> "12.5%"; 1 -> "25%"; 2 -> "50%"; else -> "75%" }
        }
        NesEnvelopeKind.MIXER -> NesS5b.mixerText(value)
    }

    /** How the 5B envelope page shows a sound's hardware envelope rows. */
    fun s5bRowText(row: Row, e: S5bEnvelope): String = when (row) {
        Row.ENV_SHAPE -> if (e.shape == S5bShape.OFF) "Off: the volume envelope sets the level" else
            "${e.shape.label} ${e.shape.picture}: ${if (e.shape.repeats) "repeats while the note holds" else "once per note"}; each attack restarts it"
        Row.ENV_AUTO -> if (e.autoPitch) "Auto: the period follows the note" else "Manual: a fixed period (Ixx/Jyy change it)"
        Row.ENV_OCTAVE -> if (e.autoPitch) "${e.octave}: ${NesS5b.octaveText(e.shape, e.octave)}" else "${e.octave} (auto only)"
        Row.ENV_PERIOD -> if (e.autoPitch) "${e.period} (manual only)" else "${e.period}: ${NesS5b.periodText(e.shape, e.period)}"
        else -> ""
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, NesSystem.SLOTS)
        st.envelope = st.envelope.coerceIn(0, pages(song, st) - 1)
        st.instField = st.instField.coerceIn(0, rows(st, song).size - 1)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        val e = envelope(song, st)
        st.envStep = st.envStep.coerceIn(0, if (e == null) 0 else lastStep(e))
        NesDpcmPage.normalise(v)
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, NesSystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
        // The DPCM channel's sounds are their sample mappings: show that page, on the selected note's key.
        val onDpcm = selection.channel == NesRules.DPCM
        if (onDpcm != dpcmPage(song, state)) state.envelope = if (onDpcm) DPCM_PAGE else 0
        if (onDpcm && selection.order in song.orders.indices) {
            val note = song.patterns[song.orders[selection.order]].tracks.getOrNull(selection.channel)?.getOrNull(selection.row)?.note ?: -1
            if (note >= 0) state.dpcmKey = note
        }
    }

    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        session.select(session.selection.copy(bank = 0, instrument = v.state.instrument, context = ViewKind.KEYBOARD))
    }

    /** Shows page [index] (wrapping), keeping the cursor on the same kind of row where the new page has it. */
    fun showEnvelope(v: ViewController, index: Int) {
        val st = v.state
        val song = v.session.song
        val row = row(st, song)
        val wasDpcm = dpcmPage(song, st)
        st.envelope = index.mod(pages(song, st))
        val rows = rows(st, song)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(when {
            dpcmPage(song, st) -> Row.KEY
            fmPage(song, st) -> Row.PATCH
            s5bEnvPage(song, st) -> Row.ENV_SHAPE
            else -> Row.LOOP
        })
        envelope(song, st)?.let { st.envStep = st.envStep.coerceIn(0, lastStep(it)) }
        // Arriving on DPCM from an envelope page: start on the sound's first mapped key if the shown one has none.
        if (dpcmPage(song, st) && !wasDpcm) NesDpcmPage.showFirstMapping(v)
    }

    /**
     * Moves to another slot; Listen moves to the channel that slot plays on most, so a bass sounds on Triangle, or
     * when it plays nowhere yet, to the first channel of its chip.
     */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, NesSystem.SLOTS)
        if (slot == st.instrument) return
        st.instrument = slot
        val song = v.session.song
        st.listenChannel = mostUsedChannel(song, slot) ?: listenFor(song, chip(song, st), st.listenChannel)
        // A page the new sound lacks (DPCM, the 5B envelope page) falls back to volume; DPCM shows the first mapped key.
        if (st.envelope >= pages(song, st)) showEnvelope(v, 0)
        else if (dpcmPage(song, st)) NesDpcmPage.showFirstMapping(v)
        publish(v)
    }

    /** [current] if it plays [chip] sounds, else the song's first channel that does. */
    private fun listenFor(song: Song, chip: NesChip, current: Int): Int {
        val expansion = song.nes.expansion
        return if (NesRules.chip(expansion, current) == chip) current
        else song.system.channels.indices.firstOrNull { NesRules.chip(expansion, it) == chip } ?: current
    }

    /** The channel whose arranged rows use sound [slot] most, or null when none does. */
    fun mostUsedChannel(song: Song, slot: Int): Int? {
        val counts = IntArray(song.system.channels.size)
        for (pat in song.orders.toSet()) {
            val tracks = song.patterns[pat].tracks
            for (ch in counts.indices) for (cell in tracks[ch]) if (cell.instrument == slot) counts[ch]++
        }
        val best = counts.indices.maxBy { counts[it] }
        return if (counts[best] > 0) best else null
    }

    /** Makes the shown sound a sound of the song's next chip (2A03 ↔ VRC6 / VRC7 / 5B); Listen follows to a channel of that chip. */
    private fun stepChip(v: ViewController, delta: Int) {
        val st = v.state
        val session = v.session
        val chips = session.song.nes.expansion.chips
        val next = chips[(chips.indexOf(chip(session.song, st)) + delta).mod(chips.size)]
        if (session.setNesChip(st.instrument, next)) {
            st.listenChannel = listenFor(session.song, next, st.listenChannel)
            st.instField = rows(st, session.song).indexOf(Row.CHIP)
        }
    }

    /** Steps the VRC6 saw volume Full / Half / Quarter. */
    private fun stepSaw(v: ViewController, delta: Int) {
        val volumes = Vrc6SawVolume.entries
        v.session.editNesInstrument(v.state.instrument, "Set saw volume") {
            it.copy(sawVolume = volumes[(volumes.indexOf(it.sawVolume) + delta).coerceIn(0, volumes.size - 1)])
        }
    }

    /** Steps the 5B sound's hardware envelope shape: off, then `$08-$0F`. */
    private fun stepShape(v: ViewController, delta: Int) {
        val shapes = S5bShape.entries
        v.session.editNesS5b(v.state.instrument, "Set 5B envelope shape") {
            it.copy(shape = shapes[(shapes.indexOf(it.shape) + delta).coerceIn(0, shapes.size - 1)])
        }
    }

    /** Halves or doubles a manual period (an octave of envelope rate per step), at least 1, at most 65535. */
    private fun octaveOf(period: Int, up: Int): Int = when {
        up > 0 -> maxOf(1, period) * 2
        up < 0 -> maxOf(1, period / 2)
        else -> period
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val song = v.session.song
        val rows = rows(st, song)
        val row = row(st, song)
        if (dpcmPage(song, st) && !v.editHeld && dy == 0 && NesDpcmPage.direction(v, row, dx)) return
        val e = envelope(song, st)
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            row == Row.STEPS && v.clearHeld -> if (e != null) st.envStep = (st.envStep + COARSE * dx).coerceIn(0, lastStep(e))
            dy != 0 -> { st.instField = (st.instField - dy).coerceIn(0, rows.size - 1); st.textPos = 0 }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.ENVELOPE -> showEnvelope(v, st.envelope + dx)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, lastStep(e))
            // FM fields: left/right picks the operator (modulator / carrier).
            row in FM_PARAMS -> st.programField = (st.programField + dx).coerceIn(0, 1)
            else -> moveSlot(v, dx)
        }
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val kind = kind(session.song, st)
        val slot = st.instrument
        val name = kind.label.lowercase()
        val chip = chip(session.song, st)
        when (val row = row(st, session.song)) {
            Row.SLOT -> moveSlot(v, dx - COARSE * dy)
            Row.NAME -> session.editNesInstrument(slot, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, EditorSession.MAX_NAME))
            }
            Row.CHIP -> stepChip(v, dx + dy)
            Row.SAW -> stepSaw(v, dx - dy)
            Row.LISTEN -> st.listenChannel = (st.listenChannel + dx + dy).mod(session.song.system.channels.size)
            Row.ENVELOPE -> showEnvelope(v, st.envelope + dx + dy)
            Row.LENGTH -> session.editNesEnvelope(slot, kind, "Set $name length") {
                NesEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy, chip)
            }
            Row.LOOP -> session.editNesEnvelope(slot, kind, "Set $name loop") {
                NesEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy, chip)
            }
            Row.RELEASE -> session.editNesEnvelope(slot, kind, "Set $name release") {
                NesEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy)
            }
            Row.MODE -> session.editNesInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.STEPS -> {
                val e = envelope(session.song, st) ?: return
                if (dy != 0) {
                    // Up raises the value; at the append position the new step starts from the last value.
                    session.editNesEnvelope(slot, kind, "Set $name") {
                        NesEnvelopes.withValue(kind, it, st.envStep, NesEnvelopes.valueAt(kind, it, st.envStep) + dy, chip)
                    }
                }
                if (dx != 0) {
                    // Drawing sideways carries the value under the cursor to the next step, appending past the end.
                    val value = NesEnvelopes.valueAt(kind, e, st.envStep)
                    val next = (st.envStep + dx).coerceIn(0, NesEnvelope.MAX_LENGTH - 1)
                    if (next == st.envStep) return
                    st.envStep = next
                    session.editNesEnvelope(slot, kind, "Draw $name") { NesEnvelopes.withValue(kind, it, next, value, chip) }
                    envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
                }
            }
            Row.PATCH -> stepPatch(v, dx - dy)
            Row.SUSTAIN_RELEASE -> if (dx != 0 || dy != 0) toggleSustain(v, release = true)
            Row.SUSTAIN_STOP -> if (dx != 0 || dy != 0) toggleSustain(v, release = false)
            in FM_PARAMS -> {
                val param = FM_PARAMS.getValue(row)
                val coarse = when { param.max >= 63 -> 8; param.max >= 15 -> 4; else -> 1 }
                setFmParam(v, param) { it + dx + coarse * dy }
            }
            // 5B envelope page: Edit+←→ steps, Edit+↑↓ steps the octave coarsely / doubles or halves the manual period.
            Row.ENV_SHAPE -> stepShape(v, dx - dy)
            Row.ENV_AUTO -> if (dx != 0 || dy != 0) session.editNesS5b(slot, "Set 5B envelope period mode") { it.copy(autoPitch = !it.autoPitch) }
            Row.ENV_OCTAVE -> session.editNesS5b(slot, "Set 5B envelope octave") { it.copy(octave = it.octave + dx + 4 * dy) }
            Row.ENV_PERIOD -> session.editNesS5b(slot, "Set 5B envelope period") { it.copy(period = octaveOf(it.period + dx, dy)) }
            else -> NesDpcmPage.adjust(v, row, dx, dy)
        }
    }

    /** Steps the VRC7 sound's patch: Custom, then the 15 built-in patches (a preset loads its ROM values). */
    private fun stepPatch(v: ViewController, delta: Int) {
        v.session.editNesFm(v.state.instrument, "Set VRC7 patch") { it.withPreset((it.preset + delta).coerceIn(0, Vrc7Presets.PRESETS.last)) }
    }

    private fun toggleSustain(v: ViewController, release: Boolean) {
        v.session.editNesFm(v.state.instrument, if (release) "Set release sustain" else "Set stop sustain") {
            if (release) it.copy(sustainRelease = !it.sustainRelease) else it.copy(sustainStop = !it.sustainStop)
        }
    }

    /**
     * Sets one patch field of the shown operator; a built-in patch becomes the custom patch with that change (starting
     * from the preset's own values), so any patch can be the start of a custom sound.
     */
    private fun setFmParam(v: ViewController, param: Vrc7Param, value: (Int) -> Int) {
        val st = v.state
        val op = fmOperator(st, param)
        val before = sound(v.session.song, st)?.fm ?: return
        v.session.editNesFm(st.instrument, "Set ${param.label.lowercase()}") { p ->
            val regs = param.set(p.regs, op, value(param.get(p.regs, op)))
            if (regs == p.regs) p else p.copy(preset = Vrc7Presets.CUSTOM, regs = regs)
        }
        val after = sound(v.session.song, st)?.fm ?: return
        if (before.preset != Vrc7Presets.CUSTOM && after.preset == Vrc7Presets.CUSTOM) {
            v.session.note("Sound ${EditorSession.hex2(st.instrument)} is now the custom patch, starting from ${Vrc7Presets.NAMES[before.preset]}")
        }
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        if (!tap) return
        val st = v.state
        val session = v.session
        val kind = kind(session.song, st)
        val chip = chip(session.song, st)
        when (val row = row(st, session.song)) {
            Row.SLOT -> { publish(v); session.note("Sound ${EditorSession.hex2(st.instrument)} selected for entry") }
            Row.MODE -> session.editNesInstrument(st.instrument, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.CHIP -> stepChip(v, 1)
            Row.PATCH -> stepPatch(v, 1)
            Row.SUSTAIN_RELEASE -> toggleSustain(v, release = true)
            Row.SUSTAIN_STOP -> toggleSustain(v, release = false)
            Row.ENV_AUTO -> session.editNesS5b(st.instrument, "Set 5B envelope period mode") { it.copy(autoPitch = !it.autoPitch) }
            Row.ENV_SHAPE -> stepShape(v, 1)
            Row.STEPS -> {
                val e = envelope(session.song, st) ?: return
                if (st.envStep == e.values.size) {
                    session.editNesEnvelope(st.instrument, kind, "Add ${kind.label.lowercase()} step") {
                        NesEnvelopes.withValue(kind, it, st.envStep, NesEnvelopes.valueAt(kind, it, st.envStep), chip)
                    }
                    envelope(session.song, st)?.let { st.envStep = lastStep(it) }
                }
            }
            Row.NAME, Row.SAW, Row.LISTEN, Row.ENVELOPE, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.ENV_OCTAVE, Row.ENV_PERIOD -> Unit
            in FM_PARAMS -> FM_PARAMS.getValue(row).let { p -> if (p.max == 1) setFmParam(v, p) { 1 - it } }
            else -> NesDpcmPage.editTap(v, row)
        }
    }

    override fun clear(v: ViewController) {
        val st = v.state
        val session = v.session
        val kind = kind(session.song, st)
        val slot = st.instrument
        val name = kind.label.lowercase()
        val blank = kind.of(defaultNesInstrument(slot))
        val chip = chip(session.song, st)
        when (val row = row(st, session.song)) {
            Row.SLOT -> session.setNesInstrument(slot, defaultNesInstrument(slot), "Reset sound")
            Row.NAME -> session.editNesInstrument(slot, "Rename sound") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.CHIP -> session.setNesChip(slot, NesChip.APU)
            Row.SAW -> session.editNesInstrument(slot, "Set saw volume") { it.copy(sawVolume = Vrc6SawVolume.HALF) }
            Row.LISTEN, Row.ENVELOPE -> Unit
            Row.LENGTH -> session.editNesEnvelope(slot, kind, "Reset $name envelope") { blank }
            Row.LOOP -> session.editNesEnvelope(slot, kind, "Clear $name loop") { it.copy(loop = -1) }
            Row.RELEASE -> session.editNesEnvelope(slot, kind, "Clear $name release") { it.copy(release = -1) }
            Row.MODE -> session.editNesInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = false) }
            Row.STEPS -> {
                session.editNesEnvelope(slot, kind, "Delete $name step") { NesEnvelopes.removed(kind, it, st.envStep, chip) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
            }
            Row.PATCH -> session.editNesFm(slot, "Set VRC7 patch") { it.withPreset(Vrc7Presets.BELL) }
            Row.SUSTAIN_RELEASE -> session.editNesFm(slot, "Set release sustain") { it.copy(sustainRelease = false) }
            Row.SUSTAIN_STOP -> session.editNesFm(slot, "Set stop sustain") { it.copy(sustainStop = false) }
            in FM_PARAMS -> setFmParam(v, FM_PARAMS.getValue(row)) { 0 }
            Row.ENV_SHAPE -> session.editNesS5b(slot, "Set 5B envelope shape") { it.copy(shape = S5bShape.OFF) }
            Row.ENV_AUTO -> session.editNesS5b(slot, "Set 5B envelope period mode") { it.copy(autoPitch = S5bEnvelope.DEFAULT.autoPitch) }
            Row.ENV_OCTAVE -> session.editNesS5b(slot, "Set 5B envelope octave") { it.copy(octave = S5bEnvelope.DEFAULT.octave) }
            Row.ENV_PERIOD -> session.editNesS5b(slot, "Set 5B envelope period") { it.copy(period = S5bEnvelope.DEFAULT.period) }
            else -> NesDpcmPage.clear(v, row)
        }
    }

    override fun audition(v: ViewController) {
        // DPCM plays the shown key's mapping, whichever page is up.
        if (dpcmPage(v.session.song, v.state) || v.state.listenChannel == NesRules.DPCM) NesDpcmPage.audition(v)
        else v.startAudition(v.state.listenChannel, v.defaultNote(), v.state.instrument)
    }

    /** Rows about the whole sound copy, cut and paste the sound; envelope rows the shown envelope. */
    private fun envelopeRow(song: Song, state: ViewState, row: Row) =
        !dpcmPage(song, state) && !s5bEnvPage(song, state) && row != Row.SLOT && row != Row.NAME && row != Row.CHIP &&
            row != Row.SAW && row != Row.LISTEN && row != Row.PATCH && row != Row.SUSTAIN_RELEASE && row != Row.SUSTAIN_STOP &&
            row !in FM_PARAMS

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val kind = kind(session.song, st)
        val slot = st.instrument
        val onEnvelope = envelopeRow(session.song, st, row(st, session.song))
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showEnvelope(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onEnvelope) session.copyNesEnvelope(slot, kind) else session.copyNesInstrument(slot)
            Action.PASTE -> if (s5bEnvPage(session.song, st) && session.clipboard is NesClip.Envelope) {
                session.note("An envelope pastes into an envelope page")
            } else session.pasteNes(slot, kind)
            Action.CUT -> if (onEnvelope) {
                session.copyNesEnvelope(slot, kind)
                session.editNesEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { kind.of(defaultNesInstrument(slot)) }
            } else {
                session.copyNesInstrument(slot)
                session.setNesInstrument(slot, defaultNesInstrument(slot), "Cut sound")
            }
            Action.DUPLICATE -> {
                if (slot >= NesSystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(session.song, st) ?: return true
                session.setNesInstrument(slot + 1, src.copy(name = (src.name + "+").take(EditorSession.MAX_NAME)), "Duplicate sound")
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
        val kind = kind(song, st)
        val name = kind.label.lowercase()
        val row = row(st, song)
        when (row) {
            Row.SLOT -> { h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots"); h += Hint(v.k(Action.CLEAR), "Reset sound") }
            Row.NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            Row.CHIP -> { h += Hint(v.editLr(), "2A03 / ${v.session.song.nes.expansion.chip.label} sound"); h += Hint(v.k(Action.CLEAR), "2A03") }
            Row.SAW -> { h += Hint(v.editLr(), "Saw volume"); h += Hint(v.k(Action.CLEAR), "Half") }
            Row.LISTEN -> h += Hint(v.lr(), "Channel Listen plays on")
            Row.ENVELOPE -> h += Hint(v.lr(), "Page")
            Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "Empty $name envelope") }
            Row.LOOP -> { h += Hint(v.editLr(), "Loop start"); h += Hint(v.k(Action.CLEAR), "No loop") }
            Row.RELEASE -> { h += Hint(v.editLr(), "Release step"); h += Hint(v.k(Action.CLEAR), "No release") }
            Row.MODE -> h += Hint(v.k(Action.EDIT), "Relative / absolute")
            Row.STEPS -> {
                h += Hint(v.lr(), "Step")
                h += Hint(v.editUd(), "Value")
                h += Hint(v.editLr(), "Draw value along")
                h += Hint(v.k(Action.CLEAR), "Delete step")
            }
            Row.PATCH -> { h += Hint(v.editLr(), "Built-in patch / Custom"); h += Hint(v.k(Action.CLEAR), "Bell") }
            Row.SUSTAIN_RELEASE, Row.SUSTAIN_STOP -> { h += Hint(v.k(Action.EDIT), "On / off"); h += Hint(v.k(Action.CLEAR), "Off") }
            in FM_PARAMS -> {
                val param = FM_PARAMS.getValue(row)
                if (!param.modulatorOnly) h += Hint(v.lr(), "Modulator / carrier")
                h += Hint(v.editLr(), if (param.max == 1) "On / off" else "Value (${v.ud()} coarse)")
                h += Hint(v.k(Action.CLEAR), "0")
            }
            Row.ENV_SHAPE -> { h += Hint(v.editLr(), "Shape"); h += Hint(v.k(Action.CLEAR), "Off") }
            Row.ENV_AUTO -> { h += Hint(v.k(Action.EDIT), "Auto / manual period"); h += Hint(v.k(Action.CLEAR), "Auto") }
            Row.ENV_OCTAVE -> { h += Hint(v.editLr(), "Octave ±1 (${v.ud()} ±4)"); h += Hint(v.k(Action.CLEAR), "3") }
            Row.ENV_PERIOD -> { h += Hint(v.editLr(), "Period ±1 (${v.ud()} ×2 / ÷2)"); h += Hint(v.k(Action.CLEAR), "1000") }
            else -> NesDpcmPage.hints(v, row, h)
        }
        if (row == Row.LENGTH || row == Row.LOOP || row == Row.RELEASE || row == Row.MODE || row == Row.CHIP || row == Row.SAW ||
            row == Row.PATCH || row == Row.SUSTAIN_RELEASE || row == Row.SUSTAIN_STOP || row in S5B_ROWS.drop(5)) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        if (dpcmPage(song, st)) {
            h += Hint(v.k(Action.AUDITION), "Listen to ${NesSystem.noteName(st.dpcmKey)}")
            h += Hint(v.copyPaste(), "Copy/paste sound")
            return
        }
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(st.listenChannel)?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${NesSystem.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (envelopeRow(song, st, row)) "Copy/paste envelope" else "Copy/paste sound")
    }

    /**
     * Help rows for this view (heading, then key → meaning), named with the current [keys]; [expansion] adds its chip's
     * rows (the VRC6's Chip and Saw rows, the VRC7's FM patch page, the 5B's mixer, noise and hardware envelope pages,
     * and their envelope meanings).
     */
    fun helpSections(keys: Keymap, expansion: NesExpansion = NesExpansion.NONE): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        return listOf(
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, page, length, loop, release (volume), mode (pitch), then the steps",
                lr to "On settings: the sound slot; on the steps: move along them",
                channels to "Pages: volume, arpeggio, pitch or duty envelope, then DPCM samples",
                "Listen on" to "The channel ${keys.label(Action.AUDITION)} plays the sound on ($lr there). Choosing a slot moves it to the channel that sound plays on most; " +
                    "envelope tabs that channel ignores are greyed",
                keys.held(Action.EDIT, ud) to "On a step: raise / lower its value; past the last step it adds one",
                keys.held(Action.EDIT, lr) to "On a step: carry its value to the next step (draws flat runs, adds steps at the end); on settings: change them",
                "$edit tap" to "Past the last step: add a step; on Mode: relative / absolute pitch",
                "$clear tap" to "Delete the step; on settings: reset that setting",
                "$clear hold+$lr" to "Move along the steps by $COARSE",
            ),
            "Envelopes" to APU_PAGES.map { it.label to envelopeSummary(it) } + listOf(
                "Loop" to "The steps from the loop point to the end repeat while the note holds; without one the last value holds",
                "Release" to "Volume only, and only with a loop: the loop runs before the release point until OFF, then the steps from it play out. " +
                    "Without one, OFF stops the note. Setting a release on an envelope without a loop loops it from step 0",
            ),
            "DPCM samples" to NesDpcmPage.helpRows(keys),
        ) + when (expansion) {
            NesExpansion.NONE -> emptyList()
            NesExpansion.VRC6 -> listOf(
                "VRC6 sounds" to listOf(
                    "Chip" to "On songs with the VRC6: whether the sound plays on the 2A03's channels or on VRC6 Pulse 1/2 and the Saw " +
                        "($edit+$lr there). VRC6 sounds have eight duties and no DPCM page",
                    "Saw volume" to "VRC6 sounds: how the saw turns volume 0-15 into its accumulator rate: Quarter ×1, Half ×2 " +
                        "(FamiStudio's default), Full ×4 (rates above 42 overflow and distort, so Full distorts above volume 10)",
                ) + APU_PAGES.map { it.label to envelopeSummary(it, NesChip.VRC6) },
            )
            NesExpansion.VRC7 -> listOf(
                "VRC7 sounds" to listOf(
                    "Chip" to "On songs with the VRC7: whether the sound plays on the 2A03's channels or on VRC7 FM 1-6 ($edit+$lr there). " +
                        "VRC7 sounds have volume, arpeggio and pitch envelopes, no duty and no DPCM page",
                    "FM patch page" to "The fourth page: the patch, one of the chip's 15 built-in sounds (Bell … Chorus) or Custom, " +
                        "the chip's one programmable patch. Changing any field of a built-in patch makes the sound Custom, starting " +
                        "from that patch",
                    "One custom patch" to "All six FM channels share the custom patch: two different Custom sounds playing at " +
                        "once both sound like the one loaded last (the song check warns)",
                    "Operators" to "$lr picks the modulator or the carrier column; $edit+$lr changes the value, $edit+$ud in bigger steps",
                    "Release sustain" to "On: notes keep the chip's sustain flag, so OFF fades slowly (rate 5) instead of with the " +
                        "patch's release. FamiStudio's engine keeps the last VRC7 sound's setting for all six channels",
                    "Stop sustain" to "On: a stop or cut lets the note fade at that rate instead of ending with the patch's release",
                ) + Vrc7Param.entries.map { it.label to fmHelp(it) } +
                    APU_PAGES.dropLast(1).map { it.label to envelopeSummary(it, NesChip.VRC7) },
            )
            NesExpansion.S5B -> listOf(
                "5B sounds" to listOf(
                    "Chip" to "On songs with the 5B: whether the sound plays on the 2A03's channels or on 5B Square 1-3 ($edit+$lr there). " +
                        "5B sounds have volume, arpeggio, pitch, mixer and noise envelopes and a hardware envelope page; no duty, no DPCM page",
                    "Pitch" to "5B notes sound at their true pitch, like the pulses', from C-1 to B-7 (the 5B divides by 32 where the " +
                        "pulses divide by 16, so the export plays FamiStudio's note an octave up; FamiStudio shows it an octave higher)",
                    "Mixer" to "Per frame: tone+noise (the square switched by the noise: pitched noise), noise, tone, or off (the channel " +
                        "holds its level). Noisy drums: a short mixer run of noise, then tone, with a falling volume",
                    "Noise" to "The noise period per frame, 0-31 (higher is lower). The three squares share one noise generator: the " +
                        "highest sounding square sets it (Squares 2 and 3 even without noise in their mixer: FamiStudio's engine)",
                    "5B envelope page" to "The chip's one envelope generator: a shape (saw, triangle, or a one-shot attack/decay) that " +
                        "replaces the volume envelope; each note with attack restarts it. Auto: its period follows the note, shifted by " +
                        "the octave (4 = a saw in unison, 5 = a triangle in unison, 3 an octave below: buzz basses). Manual: a fixed " +
                        "period (ramp = 32 × 16 × period CPU cycles), changed by Ixx/Jyy in the pattern",
                    "Shared" to "The highest square sounding an envelope sound sets the shape and period for every envelope sound; the " +
                        "Pattern view tags the squares that drive the envelope (ENV) and the noise (NOISE), and the check warns when two fight",
                    "${keys.held(Action.EDIT, lr)} on the page" to "Shape, auto/manual, octave ±1 (${keys.held(Action.EDIT, ud)} ±4), " +
                        "period ±1 (${keys.held(Action.EDIT, ud)}: × 2 / ÷ 2)",
                ) + S5B_PAGES.map { it.label to envelopeSummary(it, NesChip.S5B) },
            )
        }
    }

    /** What one patch field does, for Help. */
    private fun fmHelp(param: Vrc7Param): String = when (param) {
        Vrc7Param.MULT -> "Frequency multiple of the operator: ×½, ×1 … ×15 (11, 13 and 14 repeat 10, 12 and 15)"
        Vrc7Param.WAVE -> "Half-sine: the operator plays only the positive half of its sine (brighter, buzzier)"
        Vrc7Param.LEVEL -> "Modulator only: how strongly it modulates the carrier, 0 (strongest) to 63 (-47 dB)"
        Vrc7Param.FEEDBACK -> "Modulator only: feeds its output back into itself, 0 (none) to 7 (noisy)"
        Vrc7Param.AR -> "Attack rate 0-15 (0 never rises, 15 at once)"
        Vrc7Param.DR -> "Decay rate 0-15, from full level down to the sustain level"
        Vrc7Param.SL -> "Sustain level 0 (full) to 15 (-45 dB), where the decay stops"
        Vrc7Param.RR -> "Release rate 0-15 after OFF (with Sustained on); without it, the rate the note decays at past the sustain level"
        Vrc7Param.EG -> "Sustained: the envelope holds at the sustain level while the note is held; off: it keeps decaying (percussive)"
        Vrc7Param.KSL -> "Key scale level: higher notes get quieter by 0, 1.5, 3 or 6 dB per octave"
        Vrc7Param.KSR -> "Key scale rate: higher notes get faster envelopes"
        Vrc7Param.AM -> "Tremolo from the chip's LFO (3.7 Hz, 4.8 dB)"
        Vrc7Param.VIB -> "Vibrato from the chip's LFO (6.4 Hz, 14 cents)"
    }

    override fun painter(pane: PaneView): Painter = NesInstrumentPainter(pane)
}
