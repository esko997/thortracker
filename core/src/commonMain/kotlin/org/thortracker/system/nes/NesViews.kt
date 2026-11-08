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
 * ([NesDpcmPage]). EPSM sounds have no duty: after volume, arpeggio and pitch come the SSG's mixer and noise envelopes,
 * then the SSG hardware envelope ([ENV_PAGE]), the FM patch's curated controls ([EPSM_FM_PAGE]) and registers
 * ([EPSM_EXPERT_PAGE]), and the rhythm page ([EPSM_RHYTHM_PAGE]) — see [NesEpsmPage]. State: [ViewState.instrument],
 * [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page: see [pageKind] and the page constants),
 * [ViewState.envStep] (0..size; size is the append position), [ViewState.listenChannel], on the DPCM page
 * [ViewState.dpcmKey] and [ViewState.dpcmSample], and on the FM Expert page [ViewState.programField] (the operator).
 */
object NesInstrumentView : SystemView() {
    enum class Row {
        SLOT, NAME, CHIP, SAW, LISTEN, ENVELOPE, LENGTH, LOOP, RELEASE, MODE, STEPS,
        // DPCM page: the mapping of one key, then the song's samples
        KEY, SAMPLE, PITCH, SAMPLE_LOOP, LEVEL, SAMPLES, SAMPLE_NAME, SAMPLE_LEVEL, ADD,
        // SSG hardware envelope page (EPSM sounds): the shape, auto or manual period, the auto octave, the manual period
        ENV_SHAPE, ENV_AUTO, ENV_OCTAVE, ENV_PERIOD,
        // EPSM FM page (the Mega Drive editor's curated controls) and FM Expert page (registers; OPN_OP_* is the grid)
        OPN_PRESET, OPN_ALGORITHM, OPN_RATIO, OPN_BRIGHTNESS, OPN_LEVEL, OPN_FEEDBACK, OPN_ATTACK, OPN_DECAY, OPN_SUSTAIN,
        OPN_SUSTAIN_RATE, OPN_RELEASE, OPN_TONE_DECAY, OPN_KEY_SCALE, OPN_VIBRATO, OPN_TREMOLO, OPN_SSG,
        OPN_RAW_ALGORITHM, OPN_RAW_FEEDBACK, OPN_AMS, OPN_FMS,
        OPN_OP_DT, OPN_OP_MUL, OPN_OP_TL, OPN_OP_KS, OPN_OP_AR, OPN_OP_AM, OPN_OP_D1R, OPN_OP_D2R, OPN_OP_SL, OPN_OP_RR, OPN_OP_SSG,
        // EPSM: the patch's pan (FM and the drums), then the rhythm page's level and ROM
        EPSM_PAN, RHYTHM_LEVEL, RHYTHM_ROM,
    }

    /** The page after the four envelopes: DPCM mappings and samples (2A03 sounds only). */
    const val DPCM_PAGE = 4

    /** EPSM sounds: the SSG hardware envelope page, after volume, arpeggio, pitch, mixer and noise. */
    const val ENV_PAGE = 5

    /** EPSM sounds: the FM patch's curated controls, its registers, and the rhythm drums. */
    const val EPSM_FM_PAGE = 6
    const val EPSM_EXPERT_PAGE = 7
    const val EPSM_RHYTHM_PAGE = 8

    /** The envelope each page of a sound shows, by chip (the 2A03 and VRC6 four; the EPSM's mixer and noise in duty's place). */
    private val APU_PAGES = listOf(NesEnvelopeKind.VOLUME, NesEnvelopeKind.ARPEGGIO, NesEnvelopeKind.PITCH, NesEnvelopeKind.DUTY)
    private val EPSM_PAGES = listOf(NesEnvelopeKind.VOLUME, NesEnvelopeKind.ARPEGGIO, NesEnvelopeKind.PITCH, NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE)

    private val ENVELOPE_ROWS = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.ENVELOPE, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE, Row.STEPS)
    private val ROWS = NesEnvelopeKind.entries.associateWith { kind ->
        ENVELOPE_ROWS.filter { (it != Row.RELEASE || kind.canRelease) && (it != Row.MODE || kind == NesEnvelopeKind.PITCH) }
    }

    /** DPCM plays only on its own channel, so the page has no Listen row. */
    val DPCM_ROWS = listOf(Row.SLOT, Row.NAME, Row.ENVELOPE, Row.KEY, Row.SAMPLE, Row.PITCH, Row.SAMPLE_LOOP, Row.LEVEL,
        Row.SAMPLES, Row.SAMPLE_NAME, Row.SAMPLE_LEVEL, Row.ADD)

    /** The head of the EPSM pages that are not step envelopes (EPSM songs always have the Chip row). */
    private val EPSM_HEAD = listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE)
    private val EPSM_ROWS = mapOf(
        ENV_PAGE to EPSM_HEAD + NesEpsmPage.ENV_ROWS,
        EPSM_FM_PAGE to EPSM_HEAD + NesEpsmPage.SOUND_ROWS,
        EPSM_EXPERT_PAGE to EPSM_HEAD + NesEpsmPage.EXPERT_ROWS,
        EPSM_RHYTHM_PAGE to EPSM_HEAD + NesEpsmPage.RHYTHM_ROWS,
    )

    /** Envelope rows jump by this much with the coarse ([Action.UP]/[Action.DOWN]) direction while editing. */
    private const val COARSE = 8

    /** Whether the DPCM page of a 2A03 sound is shown. */
    fun dpcmPage(song: Song, state: ViewState): Boolean = state.envelope == DPCM_PAGE && chip(song, state) == NesChip.APU

    /** The EPSM page shown ([ENV_PAGE], [EPSM_FM_PAGE], [EPSM_EXPERT_PAGE], [EPSM_RHYTHM_PAGE]), or null. */
    fun epsmPage(song: Song, state: ViewState): Int? =
        state.envelope.takeIf { it in EPSM_ROWS && chip(song, state) == NesChip.EPSM }

    /** The envelope page [page] of a [chip] sound shows, or null for its DPCM or EPSM pages. */
    fun pageKind(chip: NesChip, page: Int): NesEnvelopeKind? = if (chip == NesChip.EPSM) EPSM_PAGES.getOrNull(page) else APU_PAGES.getOrNull(page)

    /**
     * The envelope shown; on a page that shows none its neighbour (duty, or noise on the EPSM: callers check [dpcmPage]
     * and [epsmPage] first).
     */
    fun kind(song: Song, state: ViewState): NesEnvelopeKind =
        (if (chip(song, state) == NesChip.EPSM) EPSM_PAGES else APU_PAGES).let { it[state.envelope.coerceIn(0, it.size - 1)] }

    /** The chip of the shown sound (2A03 when there is none). */
    fun chip(song: Song, state: ViewState): NesChip = sound(song, state)?.chip ?: NesChip.APU

    /**
     * Pages of the shown sound: its four envelopes, and DPCM on 2A03 sounds (only they map samples); an EPSM sound's
     * volume, arpeggio, pitch, mixer and noise envelopes, its SSG hardware envelope, FM, FM Expert and rhythm pages.
     */
    fun pages(song: Song, state: ViewState): Int = when (chip(song, state)) {
        NesChip.APU -> DPCM_PAGE + 1
        NesChip.EPSM -> EPSM_RHYTHM_PAGE + 1
        else -> DPCM_PAGE
    }

    /**
     * The rows of the shown page. Songs with an expansion chip add Chip after Name, and VRC6 sounds the saw volume;
     * 2A03-only songs keep the rows they always had.
     */
    fun rows(state: ViewState, song: Song): List<Row> {
        if (dpcmPage(song, state)) return DPCM_ROWS
        epsmPage(song, state)?.let { return EPSM_ROWS.getValue(it) }
        val base = ROWS.getValue(kind(song, state))
        if (song.nes.expansion == NesExpansion.NONE) return base
        val extra = if (chip(song, state) == NesChip.VRC6) listOf(Row.CHIP, Row.SAW) else listOf(Row.CHIP)
        return base.take(2) + extra + base.drop(2)
    }

    fun row(state: ViewState, song: Song): Row = rows(state, song).let { it[state.instField.coerceIn(0, it.size - 1)] }

    fun sound(song: Song, state: ViewState): NesInstrument? = song.nesInstrument(state.instrument)

    /** The shown envelope; null on the EPSM pages that are not step envelopes. */
    fun envelope(song: Song, state: ViewState): NesEnvelope? =
        if (epsmPage(song, state) != null) null else sound(song, state)?.let { kind(song, state).of(it) }

    /** The furthest step the cursor reaches: the append position after the last step, while there is room. */
    fun lastStep(e: NesEnvelope): Int = minOf(e.values.size, NesEnvelope.MAX_LENGTH - 1)

    /**
     * Why [channel] ignores (or treats specially) envelope [kind] of a [chip] sound, or null when it plays it as written;
     * [shape] is an EPSM sound's SSG hardware envelope (its level then replaces the volume envelope's).
     */
    fun channelNote(kind: NesEnvelopeKind, channel: ChannelKind, chip: NesChip = NesChip.APU, saw: Vrc6SawVolume = Vrc6SawVolume.HALF,
                    channelChip: NesChip = chip, shape: S5bShape = S5bShape.OFF): String? = when {
        channelChip != chip -> "${chip.label} sounds play on the ${chip.label} channels only"
        chip == NesChip.EPSM -> when (channel) {
            ChannelKind.SAMPLE -> if (kind == NesEnvelopeKind.VOLUME) "Rhythm: only the first step, the drum's level" else "Rhythm drums ignore it"
            ChannelKind.VOICE -> when (kind) {
                NesEnvelopeKind.VOLUME -> "FM: lowers the carriers' level"
                NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "FM ignores it (SSG only)"
                else -> null
            }
            else -> when (kind) {
                NesEnvelopeKind.VOLUME -> if (shape == S5bShape.OFF) "SSG: 3 dB per step, 0 silences"
                    else "Not heard: the SSG envelope (${shape.label.lowercase()}) sets the level"
                NesEnvelopeKind.MIXER -> "SSG 1-3 share one noise generator"
                NesEnvelopeKind.NOISE -> "Shared by SSG 1-3: one noise generator"
                else -> null
            }
        }
        channel == ChannelKind.SAMPLE -> "DPCM plays samples: envelopes do not apply"
        channel == ChannelKind.SAW -> when (kind) {
            NesEnvelopeKind.VOLUME -> "Saw: volume ${sawText(saw)}"
            NesEnvelopeKind.DUTY -> "The saw has no duty"
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
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "EPSM sounds only"
        }
        NesChip.VRC6 -> when (kind) {
            NesEnvelopeKind.VOLUME -> "VRC6 pulses: level 0-15 per frame. Saw: 0-15 times the saw volume (its accumulator rate)"
            NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per frame"
            NesEnvelopeKind.PITCH -> "Period offset per frame (relative: added up each frame)"
            NesEnvelopeKind.DUTY -> "VRC6 pulses: 0-7 = 1/16 to 8/16 (6.25-50%). Saw: ignored"
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "EPSM sounds only"
        }
        NesChip.EPSM -> when (kind) {
            NesEnvelopeKind.VOLUME -> "SSG: level 0-15 per frame (ignored under a hardware envelope). FM: lowers the carriers. " +
                "Rhythm: the first step is the drum's level"
            NesEnvelopeKind.ARPEGGIO -> "SSG and FM: semitones added to the note per frame. Rhythm: ignored (fixed-pitch drums)"
            NesEnvelopeKind.PITCH -> "SSG and FM: pitch offset per frame (relative: added up each frame). Rhythm: ignored"
            NesEnvelopeKind.DUTY -> "EPSM sounds have no duty"
            NesEnvelopeKind.MIXER -> "SSG per frame: tone+noise, noise, tone, or off (a constant level). Empty: tone"
            NesEnvelopeKind.NOISE -> "SSG noise period 0-31 per frame (higher is lower). One generator for SSG 1-3"
        }
    }

    /** A step value as the editor writes it: duty as a fraction or percentage (or noise mode), offsets signed, the mixer by name. */
    fun valueText(kind: NesEnvelopeKind, value: Int, channel: ChannelKind?, chip: NesChip = NesChip.APU): String = when (kind) {
        NesEnvelopeKind.VOLUME, NesEnvelopeKind.NOISE -> value.toString()
        NesEnvelopeKind.ARPEGGIO, NesEnvelopeKind.PITCH -> if (value > 0) "+$value" else value.toString()
        NesEnvelopeKind.DUTY -> when {
            chip == NesChip.VRC6 -> NesRules.dutyText(chip, value.coerceIn(chip.duty))
            channel == ChannelKind.NOISE -> if (value and 1 == 0) "long" else "short"
            else -> when (value) { 0 -> "12.5%"; 1 -> "25%"; 2 -> "50%"; else -> "75%" }
        }
        NesEnvelopeKind.MIXER -> EpsmSsg.mixerText(value)
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
        if (epsmPage(song, st) == EPSM_EXPERT_PAGE) st.programField = NesEpsmPage.operator(st)
        NesDpcmPage.normalise(v)
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, NesSystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
        if (chip(song, state) == NesChip.EPSM) {
            // The drums' sounds are their level and pan: show the rhythm page while a rhythm channel is selected.
            val onRhythm = NesRules.isEpsmRhythm(song.nes.expansion, selection.channel)
            if (onRhythm != (state.envelope == EPSM_RHYTHM_PAGE)) state.envelope = if (onRhythm) EPSM_RHYTHM_PAGE else 0
            return
        }
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

    /**
     * Shows page [index] (wrapping), keeping the cursor on the same kind of row where the new page has it. On an EPSM
     * sound Listen moves to a channel the page is about (FM, SSG or rhythm).
     */
    fun showEnvelope(v: ViewController, index: Int) {
        val st = v.state
        val song = v.session.song
        val row = row(st, song)
        val wasDpcm = dpcmPage(song, st)
        st.envelope = index.mod(pages(song, st))
        val rows = rows(st, song)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(when (epsmPage(song, st)) {
            ENV_PAGE -> Row.ENV_SHAPE
            EPSM_FM_PAGE -> Row.OPN_PRESET
            EPSM_EXPERT_PAGE -> Row.OPN_RAW_ALGORITHM
            EPSM_RHYTHM_PAGE -> Row.RHYTHM_LEVEL
            else -> if (dpcmPage(song, st)) Row.KEY else Row.LOOP
        })
        envelope(song, st)?.let { st.envStep = st.envStep.coerceIn(0, lastStep(it)) }
        if (chip(song, st) == NesChip.EPSM) st.listenChannel = epsmListen(song, st.envelope, st.listenChannel)
        // Arriving on DPCM from an envelope page: start on the sound's first mapped key if the shown one has none.
        if (dpcmPage(song, st) && !wasDpcm) NesDpcmPage.showFirstMapping(v)
    }

    /**
     * [current] if it is a channel EPSM page [page] is about, else the song's first such channel: SSG for the mixer, noise
     * and hardware envelope pages, FM for the FM pages, a drum for the rhythm page, any EPSM channel for volume, arpeggio
     * and pitch.
     */
    private fun epsmListen(song: Song, page: Int, current: Int): Int {
        val expansion = song.nes.expansion
        val fits: (Int) -> Boolean = when (page) {
            3, 4, ENV_PAGE -> { ch -> NesRules.isEpsmSsg(expansion, ch) }
            EPSM_FM_PAGE, EPSM_EXPERT_PAGE -> { ch -> NesRules.isEpsmFm(expansion, ch) }
            EPSM_RHYTHM_PAGE -> { ch -> NesRules.isEpsmRhythm(expansion, ch) }
            else -> { ch -> NesRules.chip(expansion, ch) == NesChip.EPSM }
        }
        return if (fits(current)) current else song.system.channels.indices.firstOrNull(fits) ?: current
    }

    /**
     * Moves to another slot; Listen moves to the channel that slot plays on most, so a bass sounds on Triangle, or
     * when it plays nowhere yet, to the first channel of its chip.
     */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, NesSystem.SLOTS)
        if (slot == st.instrument) return
        val before = chip(v.session.song, st)
        st.instrument = slot
        val song = v.session.song
        st.listenChannel = mostUsedChannel(song, slot) ?: listenFor(song, chip(song, st), st.listenChannel)
        // A page the new sound lacks or shows differently (DPCM, the EPSM pages) falls back to volume.
        val epsmChange = chip(song, st) != before && (before == NesChip.EPSM || chip(song, st) == NesChip.EPSM)
        if (st.envelope >= pages(song, st) || (st.envelope > 2 && epsmChange)) showEnvelope(v, 0)
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

    /**
     * Makes the shown sound a sound of the song's next chip (2A03 ↔ VRC6 / EPSM); Listen follows to a channel of that
     * chip, and from or to the EPSM a page past pitch goes back to volume.
     */
    private fun stepChip(v: ViewController, delta: Int) {
        val st = v.state
        val session = v.session
        val chips = session.song.nes.expansion.chips
        val before = chip(session.song, st)
        val next = chips[(chips.indexOf(before) + delta).mod(chips.size)]
        if (session.setNesChip(st.instrument, next)) {
            st.listenChannel = listenFor(session.song, next, st.listenChannel)
            if (st.envelope > 2 && (before == NesChip.EPSM || next == NesChip.EPSM)) st.envelope = 0
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
            // The Expert grid: left/right picks the operator.
            row in NesEpsmPage.GRID -> st.programField = (NesEpsmPage.operator(st) + dx).coerceIn(0, 3)
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
            else -> if (!NesEpsmPage.adjust(v, row, dx, dy)) NesDpcmPage.adjust(v, row, dx, dy)
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
            Row.STEPS -> {
                val e = envelope(session.song, st) ?: return
                if (st.envStep == e.values.size) {
                    session.editNesEnvelope(st.instrument, kind, "Add ${kind.label.lowercase()} step") {
                        NesEnvelopes.withValue(kind, it, st.envStep, NesEnvelopes.valueAt(kind, it, st.envStep), chip)
                    }
                    envelope(session.song, st)?.let { st.envStep = lastStep(it) }
                }
            }
            Row.NAME, Row.SAW, Row.LISTEN, Row.ENVELOPE, Row.LENGTH, Row.LOOP, Row.RELEASE -> Unit
            else -> if (!NesEpsmPage.editTap(v, row)) NesDpcmPage.editTap(v, row)
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
            Row.CHIP -> if (session.setNesChip(slot, NesChip.APU) && chip == NesChip.EPSM && st.envelope > 2) st.envelope = 0
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
            else -> if (!NesEpsmPage.clear(v, row)) NesDpcmPage.clear(v, row)
        }
    }

    override fun audition(v: ViewController) {
        // DPCM plays the shown key's mapping, whichever page is up.
        if (dpcmPage(v.session.song, v.state) || v.state.listenChannel == NesRules.DPCM) NesDpcmPage.audition(v)
        else v.startAudition(v.state.listenChannel, v.defaultNote(), v.state.instrument)
    }

    /** Rows about the whole sound copy, cut and paste the sound; envelope rows the shown envelope. */
    private fun envelopeRow(song: Song, state: ViewState, row: Row) =
        !dpcmPage(song, state) && envelope(song, state) != null && row != Row.SLOT && row != Row.NAME && row != Row.CHIP &&
            row != Row.SAW && row != Row.LISTEN

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val song = session.song
        val kind = kind(song, st)
        val slot = st.instrument
        val onEnvelope = envelopeRow(song, st, row(st, song))
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showEnvelope(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onEnvelope) session.copyNesEnvelope(slot, kind) else session.copyNesInstrument(slot)
            Action.PASTE -> if (epsmPage(song, st) != null && session.clipboard is NesClip.Envelope) {
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
                val src = sound(song, st) ?: return true
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
            Row.CHIP -> { h += Hint(v.editLr(), "2A03 / ${song.nes.expansion.chip.label} sound"); h += Hint(v.k(Action.CLEAR), "2A03") }
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
            else -> if (!NesEpsmPage.hints(v, row, h)) NesDpcmPage.hints(v, row, h)
        }
        if (row == Row.LENGTH || row == Row.LOOP || row == Row.RELEASE || row == Row.MODE || row == Row.CHIP || row == Row.SAW) h += Hint(v.lr(), "Sound slot")
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
     * rows (the VRC6's Chip and Saw rows and envelope meanings, the EPSM's pages).
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
            NesExpansion.EPSM -> NesEpsmPage.helpRows(keys)
        }
    }

    override fun painter(pane: PaneView): Painter = NesInstrumentPainter(pane)
}
