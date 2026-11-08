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
 * custom patch's registers per operator, and the release/stop sustain. N163 sounds have no duty either: their fourth
 * page is the Waves page ([WAVE_PAGE]): the preset, wave size and RAM position, the sequence of waves with each wave's
 * repeat, its loop and release, and the shown wave's samples. 5B sounds have no duty either: after volume, arpeggio and
 * pitch come their mixer and noise envelopes and the hardware envelope page ([S5B_ENV_PAGE]: shape, auto/manual period,
 * octave, period). State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the
 * page: see [pageKind], [FM_PAGE], [WAVE_PAGE], [DPCM_PAGE], [S5B_ENV_PAGE]), [ViewState.envStep] (0..size; size is the
 * append position), [ViewState.listenChannel], on the DPCM page [ViewState.dpcmKey] and [ViewState.dpcmSample], on the
 * FM page [ViewState.programField] (the operator column: 0 modulator, 1 carrier), and on the Waves page
 * [ViewState.wave] (the shown wave of the sequence) and [ViewState.waveCol] (the sample under the cursor), as the Game
 * Boy's Wave view uses them.
 */
object NesInstrumentView : SystemView() {
    enum class Row {
        SLOT, NAME, CHIP, SAW, LISTEN, ENVELOPE, LENGTH, LOOP, RELEASE, MODE, STEPS,
        // DPCM page: the mapping of one key, then the song's samples
        KEY, SAMPLE, PITCH, SAMPLE_LOOP, LEVEL, SAMPLES, SAMPLE_NAME, SAMPLE_LEVEL, ADD,
        // FM page (VRC7 sounds): the patch, the sustain overrides, then one row per patch register field
        PATCH, SUSTAIN_RELEASE, SUSTAIN_STOP,
        FM_MULT, FM_WAVE, FM_LEVEL, FM_FEEDBACK, FM_AR, FM_DR, FM_SL, FM_RR, FM_EG, FM_KSL, FM_KSR, FM_AM, FM_VIB,
        // Waves page (N163 sounds): preset, size, RAM position, the shown wave, its repeat, the sequence's loop and
        // release, then the shown wave's samples
        WAVE_PRESET, WAVE_SIZE, WAVE_POSITION, WAVE_INDEX, WAVE_REPEAT, WAVE_LOOP, WAVE_RELEASE, WAVE_SAMPLES,
        // 5B envelope page (5B sounds): the shape, auto or manual period, the auto octave, the manual period
        ENV_SHAPE, ENV_AUTO, ENV_OCTAVE, ENV_PERIOD,
    }

    /** The page after the four envelopes: DPCM mappings and samples (2A03 sounds only). */
    const val DPCM_PAGE = 4

    /** VRC7 sounds: the FM patch page, in the duty envelope's place (the FM channels have no duty). */
    val FM_PAGE = NesEnvelopeKind.DUTY.ordinal

    /** N163 sounds: the Waves page, in the duty envelope's place (the N163's channels have no duty). */
    val WAVE_PAGE = NesEnvelopeKind.DUTY.ordinal

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

    /** The Waves page (only on N163 songs, so it has the Chip row). */
    val WAVE_ROWS = listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE, Row.WAVE_PRESET, Row.WAVE_SIZE, Row.WAVE_POSITION,
        Row.WAVE_INDEX, Row.WAVE_REPEAT, Row.WAVE_LOOP, Row.WAVE_RELEASE, Row.WAVE_SAMPLES)

    /** The Waves page's own rows (the rest are the sound's). */
    private val WAVE_SETTINGS = WAVE_ROWS.drop(5).toSet()

    /** The rows of the Waves page that work on the shown wave itself: copy, cut and paste take that wave. */
    private val WAVE_SHAPE_ROWS = setOf(Row.WAVE_INDEX, Row.WAVE_SAMPLES)

    /** The 5B envelope page (only on 5B songs, so it has the Chip row). */
    val S5B_ROWS = listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE, Row.ENV_SHAPE, Row.ENV_AUTO, Row.ENV_OCTAVE, Row.ENV_PERIOD)

    /** Envelope rows jump by this much with the coarse ([Action.UP]/[Action.DOWN]) direction while editing. */
    private const val COARSE = 8

    /** Whether the DPCM page of a 2A03 sound is shown. */
    fun dpcmPage(song: Song, state: ViewState): Boolean = state.envelope == DPCM_PAGE && chip(song, state) == NesChip.APU

    /** Whether the FM patch page of a VRC7 sound is shown. */
    fun fmPage(song: Song, state: ViewState): Boolean = state.envelope == FM_PAGE && chip(song, state) == NesChip.VRC7

    /** Whether the Waves page of an N163 sound is shown. */
    fun wavePage(song: Song, state: ViewState): Boolean = state.envelope == WAVE_PAGE && chip(song, state) == NesChip.N163

    /** Whether the hardware envelope page of a 5B sound is shown. */
    fun s5bEnvPage(song: Song, state: ViewState): Boolean = state.envelope == S5B_ENV_PAGE && chip(song, state) == NesChip.S5B

    /** The envelope page [page] of a [chip] sound shows, or null for its DPCM, FM, Waves or 5B envelope page. */
    fun pageKind(chip: NesChip, page: Int): NesEnvelopeKind? = when (chip) {
        NesChip.S5B -> S5B_PAGES.getOrNull(page)
        NesChip.VRC7, NesChip.N163 -> APU_PAGES.getOrNull(page)?.takeIf { it != NesEnvelopeKind.DUTY }
        else -> APU_PAGES.getOrNull(page)
    }

    /**
     * The envelope shown; on the DPCM, FM, Waves and 5B envelope pages the page's neighbour (duty, or noise on the 5B:
     * callers check [dpcmPage], [fmPage], [wavePage] and [s5bEnvPage] first).
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
     * volume, arpeggio and pitch envelopes and its FM patch; an N163 sound's three and its Waves page; a 5B sound's
     * volume, arpeggio, pitch, mixer and noise envelopes and its hardware envelope.
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
        if (wavePage(song, state)) return WAVE_ROWS
        if (s5bEnvPage(song, state)) return S5B_ROWS
        val base = ROWS.getValue(kind(song, state))
        if (song.nes.expansion == NesExpansion.NONE) return base
        val extra = if (chip(song, state) == NesChip.VRC6) listOf(Row.CHIP, Row.SAW) else listOf(Row.CHIP)
        return base.take(2) + extra + base.drop(2)
    }

    fun row(state: ViewState, song: Song): Row = rows(state, song).let { it[state.instField.coerceIn(0, it.size - 1)] }

    fun sound(song: Song, state: ViewState): NesInstrument? = song.nesInstrument(state.instrument)

    /** The shown envelope; null on the FM, Waves and 5B envelope pages (no step envelope there). */
    fun envelope(song: Song, state: ViewState): NesEnvelope? =
        if (fmPage(song, state) || wavePage(song, state) || s5bEnvPage(song, state)) null
        else sound(song, state)?.let { kind(song, state).of(it) }

    /** The shown sound's waves when it is an N163 sound, else null. */
    fun n163Wave(song: Song, state: ViewState): N163Wave? = sound(song, state)?.takeIf { it.chip == NesChip.N163 }?.wave

    /** The shown wave of the sequence, kept inside it. */
    fun waveIndex(state: ViewState, w: N163Wave): Int = state.wave.coerceIn(0, w.waves.size - 1)

    /** The sample under the cursor, kept inside the wave. */
    fun sampleIndex(state: ViewState, w: N163Wave): Int = state.waveCol.coerceIn(0, w.size - 1)

    /**
     * What a wave of [size] samples does to the pitch (FamiStudio's N163 note tables are for 16 samples): "16 samples:
     * in tune", "32 samples: one octave lower", "24 samples: 7.0 semitones lower — out of tune".
     */
    fun sizeText(size: Int): String {
        val t = NesRules.n163Transpose(size)
        if (NesRules.n163InTune(size)) {
            val octaves = kotlin.math.round(t / 12.0).toInt()
            return "$size samples: " + when {
                octaves == 0 -> "in tune"
                octaves == -1 -> "one octave lower"
                octaves == 1 -> "one octave higher"
                octaves < 0 -> "${-octaves} octaves lower"
                else -> "$octaves octaves higher"
            }
        }
        val semis = org.thortracker.model.NumberText.fixed(kotlin.math.abs(t), 1)
        return "$size samples: $semis semitones ${if (t < 0) "lower" else "higher"} — out of tune"
    }

    /** Where [w] lies in the wave RAM of [channels] N163 channels: "RAM 0-15 of 0-127 (8 channels: 128 bytes minus 64 for registers)". */
    fun ramText(w: N163Wave, channels: Int): String {
        val ram = NesRules.n163WaveRam(channels)
        return "RAM ${w.position}-${w.position + w.size - 1} of 0-${ram - 1} " +
            "($channels channel${if (channels == 1) "" else "s"}: 128 bytes minus ${8 * channels} for registers)"
    }

    /**
     * The other N163 sounds of [song] whose RAM overlaps sound [slot]'s (slot numbers): a channel playing one overwrites
     * the waves of a channel playing the other. Sounds with the same size, position and waves share the RAM safely and
     * are left out.
     */
    fun n163Overlaps(song: Song, slot: Int): List<Int> {
        val w = song.nesInstrument(slot)?.takeIf { it.chip == NesChip.N163 }?.wave ?: return emptyList()
        return song.nes.instruments.withIndex().filter { (i, ins) ->
            val o = ins.wave
            i + 1 != slot && ins.chip == NesChip.N163 && o.position < w.position + w.size && w.position < o.position + o.size &&
                !(o.position == w.position && o.size == w.size && o.waves == w.waves)
        }.map { it.index + 1 }
    }

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
     * [shape] is a 5B sound's hardware envelope (its level then replaces the volume envelope's); [n163Channels] (the
     * song's N163 channel count) sets the N163's pitch step.
     */
    fun channelNote(kind: NesEnvelopeKind, channel: ChannelKind, chip: NesChip = NesChip.APU, saw: Vrc6SawVolume = Vrc6SawVolume.HALF,
                    channelChip: NesChip = chip, shape: S5bShape = S5bShape.OFF, n163Channels: Int = 1): String? = when {
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
        channel == ChannelKind.WAVE -> when (kind) {
            NesEnvelopeKind.VOLUME -> "N163: 4-bit linear volume"
            NesEnvelopeKind.PITCH -> "+ raises the pitch (N163 frequency steps of 2^${NesRules.n163PitchShift(n163Channels)})"
            NesEnvelopeKind.DUTY -> "The N163 channels have no duty"
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
        NesChip.N163 -> when (kind) {
            NesEnvelopeKind.VOLUME -> "N163 channels: linear level 0-15 per frame (0 is silent), times the wave"
            NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per frame"
            NesEnvelopeKind.PITCH -> "Frequency offset per frame in N163 steps (2^2 with 1 channel … 2^5 with 5-8): + raises the pitch"
            NesEnvelopeKind.DUTY -> "The N163 channels have no duty: this page is the waves"
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "5B sounds only"
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
        n163Wave(song, st)?.let { w ->
            st.wave = waveIndex(st, w)
            st.waveCol = sampleIndex(st, w)
        }
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
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: SAME_ROW[row]?.let { rows.indexOf(it) }?.takeIf { it >= 0 } ?: rows.indexOf(when {
            dpcmPage(song, st) -> Row.KEY
            fmPage(song, st) -> Row.PATCH
            wavePage(song, st) -> Row.WAVE_PRESET
            s5bEnvPage(song, st) -> Row.ENV_SHAPE
            else -> Row.LOOP
        })
        envelope(song, st)?.let { st.envStep = st.envStep.coerceIn(0, lastStep(it)) }
        // Arriving on DPCM from an envelope page: start on the sound's first mapped key if the shown one has none.
        if (dpcmPage(song, st) && !wasDpcm) NesDpcmPage.showFirstMapping(v)
    }

    /** Rows of the Waves page and of the envelope pages that do the same thing: page changes keep the cursor on them. */
    private val SAME_ROW = mapOf(
        Row.STEPS to Row.WAVE_SAMPLES, Row.WAVE_SAMPLES to Row.STEPS,
        Row.LOOP to Row.WAVE_LOOP, Row.WAVE_LOOP to Row.LOOP,
        Row.RELEASE to Row.WAVE_RELEASE, Row.WAVE_RELEASE to Row.RELEASE,
    )

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

    /** Makes the shown sound a sound of the song's next chip (2A03 ↔ VRC6 / VRC7 / N163 / 5B); Listen follows to a channel of that chip. */
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
            // Clear held on the samples: ←→ jump along the wave, ↑↓ show the previous / next wave.
            row == Row.WAVE_SAMPLES && v.clearHeld -> { moveSample(v, COARSE * dx); stepWave(v, -dy) }
            dy != 0 -> { st.instField = (st.instField - dy).coerceIn(0, rows.size - 1); st.textPos = 0 }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.ENVELOPE -> showEnvelope(v, st.envelope + dx)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, lastStep(e))
            row == Row.WAVE_INDEX -> stepWave(v, dx)
            row == Row.WAVE_SAMPLES -> moveSample(v, dx)
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
            Row.WAVE_PRESET -> stepPreset(v, dx - dy)
            Row.WAVE_SIZE -> editWave(v, "Set wave size") { w, ram -> N163Waves.withSize(w, w.size + 4 * dx + 16 * dy, ram) }
            Row.WAVE_POSITION -> editWave(v, "Set wave position") { w, ram -> N163Waves.withPosition(w, w.position + 2 * dx + 16 * dy, ram) }
            Row.WAVE_INDEX -> editWave(v, "Set wave count") { w, ram -> N163Waves.withCount(w, w.waves.size + dx + COARSE * dy, ram) }
            Row.WAVE_REPEAT -> editWave(v, "Set wave repeat") { w, ram ->
                val i = waveIndex(st, w)
                N163Waves.withRepeat(w, i, w.repeat.getOrElse(i) { 1 } + dx + 4 * dy, ram)
            }
            Row.WAVE_LOOP -> editWave(v, "Set wave loop") { w, ram -> N163Waves.withLoop(w, w.loop + dx + COARSE * dy, ram) }
            Row.WAVE_RELEASE -> editWave(v, "Set wave release") { w, ram -> N163Waves.withRelease(w, w.release + dx + COARSE * dy, ram) }
            Row.WAVE_SAMPLES -> drawSample(v, dx, dy)
            // 5B envelope page: Edit+←→ steps, Edit+↑↓ steps the octave coarsely / doubles or halves the manual period.
            Row.ENV_SHAPE -> stepShape(v, dx - dy)
            Row.ENV_AUTO -> if (dx != 0 || dy != 0) session.editNesS5b(slot, "Set 5B envelope period mode") { it.copy(autoPitch = !it.autoPitch) }
            Row.ENV_OCTAVE -> session.editNesS5b(slot, "Set 5B envelope octave") { it.copy(octave = it.octave + dx + 4 * dy) }
            Row.ENV_PERIOD -> session.editNesS5b(slot, "Set 5B envelope period") { it.copy(period = octaveOf(it.period + dx, dy)) }
            else -> NesDpcmPage.adjust(v, row, dx, dy)
        }
    }

    /** Edits the shown N163 sound's waves; [f] gets the waves and the song's wave RAM in samples. */
    private fun editWave(v: ViewController, label: String, f: (N163Wave, Int) -> N163Wave): Boolean {
        val ram = N163Waves.ram(v.session.song)
        return v.session.editNesWave(v.state.instrument, label) { f(it, ram) }
    }

    /** Shows the previous / next wave of the sequence. */
    private fun stepWave(v: ViewController, delta: Int) {
        val w = n163Wave(v.session.song, v.state) ?: return
        v.state.wave = (waveIndex(v.state, w) + delta).coerceIn(0, w.waves.size - 1)
    }

    /** Moves the sample cursor along the wave. */
    private fun moveSample(v: ViewController, delta: Int) {
        val w = n163Wave(v.session.song, v.state) ?: return
        v.state.waveCol = (sampleIndex(v.state, w) + delta).coerceIn(0, w.size - 1)
    }

    /**
     * Edit held on the samples: ↑↓ raise / lower the sample under the cursor; ←→ carry its value to the next sample (draws
     * flat runs), as on an envelope's steps.
     */
    private fun drawSample(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val w = n163Wave(v.session.song, st) ?: return
        val wave = waveIndex(st, w)
        val at = sampleIndex(st, w)
        val value = w.waves[wave][at]
        if (dy != 0) editWave(v, "Set wave sample") { cur, ram -> N163Waves.withSample(cur, wave, at, value + dy, ram) }
        if (dx != 0) {
            val next = (at + dx).coerceIn(0, w.size - 1)
            if (next == at) return
            st.waveCol = next
            editWave(v, "Draw wave") { cur, ram -> N163Waves.withSample(cur, wave, next, value, ram) }
        }
    }

    /**
     * Steps the waves' preset through [N163Preset] and applies it to every wave; from drawn ("Custom") waves the first
     * step goes to the first or last preset. Presets that would make the same waves are passed over.
     */
    private fun stepPreset(v: ViewController, delta: Int) {
        if (delta == 0) return
        val w = n163Wave(v.session.song, v.state) ?: return
        val presets = N163Preset.entries
        val step = if (delta > 0) 1 else -1
        var i = N163Presets.presetOf(w.waves, w.size)?.ordinal ?: if (step > 0) -1 else presets.size
        do {
            i += step
            if (i !in presets.indices) return
        } while (N163Presets.waves(presets[i], w.size, w.waves.size) == w.waves)
        val preset = presets[i]
        editWave(v, "Set wave preset") { cur, ram -> N163Waves.withPreset(cur, preset, ram) }
    }

    /** Inserts a copy of the shown wave after it and shows the copy. */
    private fun duplicateWave(v: ViewController) {
        val st = v.state
        val w = n163Wave(v.session.song, st) ?: return
        if (N163Waves.full(w)) {
            return v.session.note("Sound ${EditorSession.hex2(st.instrument)} holds ${w.waves.size} waves, the most of ${w.size} samples FamiStudio keeps")
        }
        val i = waveIndex(st, w)
        if (editWave(v, "Duplicate wave") { cur, ram -> N163Waves.duplicated(cur, i, ram) }) st.wave = i + 1
    }

    /** Deletes the shown wave (a sound keeps at least one). */
    private fun deleteWave(v: ViewController) {
        val st = v.state
        val w = n163Wave(v.session.song, st) ?: return
        if (w.waves.size == 1) return v.session.note("Sound ${EditorSession.hex2(st.instrument)} keeps at least one wave")
        val i = waveIndex(st, w)
        if (editWave(v, "Delete wave") { cur, ram -> N163Waves.removed(cur, i, ram) }) st.wave = i.coerceAtMost(w.waves.size - 2)
    }

    /** Copies the shown wave, then deletes it; the only wave becomes silent instead. */
    private fun cutWave(v: ViewController) {
        val st = v.state
        val w = n163Wave(v.session.song, st) ?: return
        val i = waveIndex(st, w)
        v.session.copyNesWave(st.instrument, i)
        if (w.waves.size > 1) {
            if (editWave(v, "Cut wave") { cur, ram -> N163Waves.removed(cur, i, ram) }) st.wave = i.coerceAtMost(w.waves.size - 2)
        } else {
            editWave(v, "Cut wave") { cur, ram -> N163Waves.withWave(cur, i, List(cur.size) { N163Waves.CENTRE }, ram) }
        }
    }

    /** Steps the VRC7 sound's patch: Custom, then the 15 built-in patches (a preset loads its ROM values). */
    private fun stepPatch(v: ViewController, delta: Int) {
        v.session.editNesFm(v.state.instrument, "Set VRC7 patch") { it.withPreset((it.preset + delta).coerceIn(0, Vrc7Presets.PRESETS.last)) }
    }

    /** Toggles one of the song's VRC7 sustain overrides (shared by every VRC7 sound, as the engine keeps one). */
    private fun toggleSustain(v: ViewController, release: Boolean) {
        v.session.editVrc7Sustain(if (release) "Set release sustain" else "Set stop sustain") {
            if (release) it.copy(release = !it.release) else it.copy(stop = !it.stop)
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
            Row.WAVE_PRESET -> stepPreset(v, 1)
            Row.WAVE_INDEX -> duplicateWave(v)
            Row.WAVE_SIZE, Row.WAVE_POSITION, Row.WAVE_REPEAT, Row.WAVE_LOOP, Row.WAVE_RELEASE, Row.WAVE_SAMPLES -> Unit
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
            Row.SUSTAIN_RELEASE -> session.editVrc7Sustain("Set release sustain") { it.copy(release = false) }
            Row.SUSTAIN_STOP -> session.editVrc7Sustain("Set stop sustain") { it.copy(stop = false) }
            in FM_PARAMS -> setFmParam(v, FM_PARAMS.getValue(row)) { 0 }
            Row.WAVE_PRESET -> editWave(v, "Set wave preset") { w, ram -> N163Waves.withPreset(w, N163Preset.SINE, ram) }
            Row.WAVE_SIZE -> editWave(v, "Set wave size") { w, ram -> N163Waves.withSize(w, N163Wave.DEFAULT.size, ram) }
            Row.WAVE_POSITION -> editWave(v, "Set wave position") { w, ram -> N163Waves.withPosition(w, 0, ram) }
            Row.WAVE_INDEX -> deleteWave(v)
            Row.WAVE_REPEAT -> editWave(v, "Set wave repeat") { w, ram -> N163Waves.withRepeat(w, waveIndex(st, w), 1, ram) }
            Row.WAVE_LOOP -> editWave(v, "Clear wave loop") { w, ram -> N163Waves.withLoop(w, -1, ram) }
            Row.WAVE_RELEASE -> editWave(v, "Clear wave release") { w, ram -> N163Waves.withRelease(w, -1, ram) }
            Row.WAVE_SAMPLES -> editWave(v, "Clear wave sample") { w, ram ->
                N163Waves.withSample(w, waveIndex(st, w), sampleIndex(st, w), N163Waves.CENTRE, ram)
            }
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
            row !in FM_PARAMS && row !in WAVE_SETTINGS

    /** On the Waves page's Wave and Samples rows, copy, cut and paste take the shown wave. */
    private fun waveRow(song: Song, state: ViewState, row: Row) = row in WAVE_SHAPE_ROWS && wavePage(song, state)

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val kind = kind(session.song, st)
        val slot = st.instrument
        val row = row(st, session.song)
        val onEnvelope = envelopeRow(session.song, st, row)
        val onWave = waveRow(session.song, st, row)
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showEnvelope(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> when {
                onWave -> n163Wave(session.song, st)?.let { session.copyNesWave(slot, waveIndex(st, it)) }
                onEnvelope -> session.copyNesEnvelope(slot, kind)
                else -> session.copyNesInstrument(slot)
            }
            Action.PASTE -> when {
                onWave -> n163Wave(session.song, st)?.let { session.pasteNesWave(slot, waveIndex(st, it), kind) }
                wavePage(session.song, st) && session.clipboard is NesClip.Envelope ->
                    session.note("The Waves page has no envelope: paste envelopes on the volume, arpeggio or pitch page")
                s5bEnvPage(session.song, st) && session.clipboard is NesClip.Envelope ->
                    session.note("An envelope pastes into an envelope page")
                else -> session.pasteNes(slot, kind)
            }
            Action.CUT -> if (onWave) cutWave(v) else if (onEnvelope) {
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
            Row.WAVE_PRESET -> { h += Hint(v.editLr(), "Preset (all waves)"); h += Hint(v.k(Action.EDIT), "Next preset"); h += Hint(v.k(Action.CLEAR), "Sine") }
            Row.WAVE_SIZE -> { h += Hint(v.editLr(), "Size ±4 (${v.ud()} ±16)"); h += Hint(v.k(Action.CLEAR), "16 samples") }
            Row.WAVE_POSITION -> { h += Hint(v.editLr(), "RAM position ±2 (${v.ud()} ±16)"); h += Hint(v.k(Action.CLEAR), "0") }
            Row.WAVE_INDEX -> {
                h += Hint(v.lr(), "Previous / next wave")
                h += Hint(v.editLr(), "Waves ±1 (${v.ud()} ±$COARSE)")
                h += Hint(v.k(Action.EDIT), "Duplicate wave")
                h += Hint(v.k(Action.CLEAR), "Delete wave")
            }
            Row.WAVE_REPEAT -> { h += Hint(v.editLr(), "Frames ±1 (${v.ud()} ±4)"); h += Hint(v.k(Action.CLEAR), "1 frame") }
            Row.WAVE_LOOP -> { h += Hint(v.editLr(), "Loop wave"); h += Hint(v.k(Action.CLEAR), "No loop") }
            Row.WAVE_RELEASE -> { h += Hint(v.editLr(), "Release wave"); h += Hint(v.k(Action.CLEAR), "No release") }
            Row.WAVE_SAMPLES -> {
                h += Hint(v.lr(), "Sample")
                h += Hint(v.editUd(), "Value")
                h += Hint(v.editLr(), "Draw value along")
                h += Hint(v.keys.held(Action.CLEAR, v.ud()), "Previous / next wave")
                h += Hint(v.k(Action.CLEAR), "Sample to 8")
            }
            Row.ENV_SHAPE -> { h += Hint(v.editLr(), "Shape"); h += Hint(v.k(Action.CLEAR), "Off") }
            Row.ENV_AUTO -> { h += Hint(v.k(Action.EDIT), "Auto / manual period"); h += Hint(v.k(Action.CLEAR), "Auto") }
            Row.ENV_OCTAVE -> { h += Hint(v.editLr(), "Octave ±1 (${v.ud()} ±4)"); h += Hint(v.k(Action.CLEAR), "3") }
            Row.ENV_PERIOD -> { h += Hint(v.editLr(), "Period ±1 (${v.ud()} ×2 / ÷2)"); h += Hint(v.k(Action.CLEAR), "1000") }
            else -> NesDpcmPage.hints(v, row, h)
        }
        if (row == Row.LENGTH || row == Row.LOOP || row == Row.RELEASE || row == Row.MODE || row == Row.CHIP || row == Row.SAW ||
            row == Row.PATCH || row == Row.SUSTAIN_RELEASE || row == Row.SUSTAIN_STOP ||
            (row in WAVE_SETTINGS && row !in WAVE_SHAPE_ROWS) || row in S5B_ROWS.drop(5)) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        if (dpcmPage(song, st)) {
            h += Hint(v.k(Action.AUDITION), "Listen to ${NesSystem.noteName(st.dpcmKey)}")
            h += Hint(v.copyPaste(), "Copy/paste sound")
            return
        }
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(st.listenChannel)?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${NesSystem.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), when {
            waveRow(song, st, row) -> "Copy/paste wave"
            envelopeRow(song, st, row) -> "Copy/paste envelope"
            else -> "Copy/paste sound"
        })
    }

    /**
     * Help rows for this view (heading, then key → meaning), named with the current [keys]; [expansion] adds its chip's
     * rows (the VRC6's Chip and Saw rows, the VRC7's FM patch page, the N163's Waves page for its channel count, the
     * 5B's mixer, noise and hardware envelope pages, and their envelope meanings).
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
                    "One custom patch" to "All six FM channels share the custom patch: a Custom sound starting while another FM channel " +
                        "holds a note of a different Custom sound changes that note's patch too (the song check warns)",
                    "Operators" to "$lr picks the modulator or the carrier column; $edit+$lr changes the value, $edit+$ud in bigger steps",
                    "Release sustain" to "Song setting, the same for every VRC7 sound (FamiStudio's engine keeps one): on, OFF keys the " +
                        "note off with the chip's sustain bit, so it fades at rate 5 instead of the patch's release",
                    "Stop sustain" to "Song setting as well: on, a stop or cut fades at rate 5 too; off, it uses the patch's release",
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
            else -> n163Help(keys, expansion.n163Channels)
        }
    }

    /** The N163 section of Help for songs with [channels] N163 channels: the Chip row, the Waves page and its keys. */
    private fun n163Help(keys: Keymap, channels: Int): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val ram = NesRules.n163WaveRam(channels)
        return listOf(
            "N163 sounds" to listOf(
                "Chip" to "On songs with the N163: whether the sound plays on the 2A03's channels or on N163 1-$channels ($edit+$lr there). " +
                    "N163 sounds have volume, arpeggio and pitch envelopes, no duty and no DPCM page; their fourth page is Waves",
                "Waves page" to "The sound's waves: 4-bit samples (0-15, 8 is silence) the channel plays over and over, one or a sequence " +
                    "of several, each for its repeat frames, then looping",
                "Preset" to "$edit+$lr: Sine, Triangle, Sawtooth, Square 50%/25%, Flat, PWM 1-4 (FamiStudio's), made for every wave " +
                    "(the PWM presets change the width from wave to wave); $edit tap: the next one; $clear: Sine. Drawn waves show Custom",
                "Size" to "Samples per wave, 4 to what fits after the position, in fours ($edit+$lr ±4, $edit+$ud ±16; $clear: 16). " +
                    "The note tables are tuned for 16: 32 sounds an octave lower, 8 an octave higher, sizes that are not 16 × 2^k " +
                    "out of tune. Presets are made again at the new size, drawn waves are resampled",
                "Position" to "Where the wave lies in the N163's RAM, an even sample 0-${ram - 4} ($edit+$lr ±2, $edit+$ud ±16; $clear: 0). " +
                    "With $channels channel${if (channels == 1) "" else "s"} waves have samples 0-${ram - 1}: the RAM's 128 bytes " +
                    "minus ${8 * channels} for the channel registers",
                "RAM map" to "This sound's samples highlighted over the other N163 sounds' ranges and the registers. Sounds whose " +
                    "ranges overlap overwrite each other's waves when they play at once (the same waves at the same place share safely)",
                "Wave" to "$lr: the previous / next wave of the sequence; $edit tap: duplicate it; $clear: delete it; " +
                    "$edit+$lr: waves ±1 at the end ($edit+$ud ±$COARSE). At most ${N163Wave.MAX_WAVES} waves and " +
                    "${N163Wave.MAX_SAMPLES} samples in all (FamiStudio's limit: ${N163Wave.maxWaves(240)} waves of 240 samples)",
                "Repeat" to "Frames the shown wave plays (1-15) before the next ($edit+$lr, $edit+$ud ±4; $clear: 1)",
                "Loop / release" to "As on a volume envelope, in waves: after the last wave the sequence goes back to the loop wave " +
                    "(none: the last wave holds); after OFF the waves from the release play out. A release needs a loop",
                "Samples" to "$lr: the sample; $edit+$ud: raise / lower it; $edit+$lr: carry its value along; $clear: back to 8; " +
                    "$clear hold+$lr: jump $COARSE samples, $clear hold+$ud: previous / next wave",
                "Copy / paste" to "On Wave and Samples: the shown wave (pasted waves are resampled to the size); elsewhere the sound",
            ) + APU_PAGES.dropLast(1).map { it.label to envelopeSummary(it, NesChip.N163) },
        )
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
        Vrc7Param.RR -> "Release rate 0-15 after OFF on sustained patches (percussive ones release at 7); percussive patches " +
            "also fall at this rate below the sustain level while the note holds"
        Vrc7Param.EG -> "Sustained: the level holds at the sustain level while the note is held; off: it keeps falling (percussive)"
        Vrc7Param.KSL -> "Key scale level: higher notes get quieter by 0, 1.5, 3 or 6 dB per octave"
        Vrc7Param.KSR -> "Key scale rate: higher notes get faster envelopes"
        Vrc7Param.AM -> "Tremolo from the chip's LFO (3.7 Hz, 4.8 dB)"
        Vrc7Param.VIB -> "Vibrato from the chip's LFO (6.4 Hz, 14 cents)"
    }

    override fun painter(pane: PaneView): Painter = NesInstrumentPainter(pane)
}
