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
 * repeat, its loop and release, and the shown wave's samples. FDS sounds have no duty: their fourth page is their Waves
 * page (the preset, the master volume, the sequence of 64-sample waves with repeats, loop and release, and the shown
 * wave's samples) and their fifth the Modulation page ([MOD_PAGE]): the mod table preset, speed, depth, delay, auto-mod
 * and its ratio, and the table's 32 targets beside the curve the chip plays. 5B sounds have no duty either: after
 * volume, arpeggio and pitch come their mixer and noise envelopes and the hardware envelope page ([S5B_ENV_PAGE]: shape,
 * auto/manual period, octave, period). EPSM sounds have the 5B's six pages (their SSG is the same YM2149 generator), then
 * the FM patch's curated controls ([EPSM_FM_PAGE]), its registers ([EPSM_EXPERT_PAGE]) and the rhythm page
 * ([EPSM_RHYTHM_PAGE]) — see [NesEpsmPage]. State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]),
 * [ViewState.envelope] (the page: see [pageKind], [FM_PAGE], [WAVE_PAGE], [MOD_PAGE], [DPCM_PAGE], [S5B_ENV_PAGE] and the
 * EPSM pages), [ViewState.envStep] (0..size; size is the append position), [ViewState.listenChannel], on the DPCM page
 * [ViewState.dpcmKey] and [ViewState.dpcmSample], on the FM page [ViewState.programField] (the operator column:
 * 0 modulator, 1 carrier), on the Waves pages [ViewState.wave] (the shown wave of the sequence) and [ViewState.waveCol]
 * (the sample under the cursor), as the Game Boy's Wave view uses them, on the Modulation page
 * [ViewState.programField] (the table entry under the cursor), and on the EPSM FM Expert page [ViewState.programField]
 * (the operator, 0..3).
 */
object NesInstrumentView : SystemView() {
    enum class Row {
        SLOT, NAME, CHIP, SAW, LISTEN, ENVELOPE, LENGTH, LOOP, RELEASE, MODE, STEPS,
        // DPCM page: the mapping of one key, then the song's samples
        KEY, SAMPLE, PITCH, SAMPLE_LOOP, LEVEL, SAMPLES, SAMPLE_NAME, SAMPLE_LEVEL, ADD,
        // FM page (VRC7 sounds): the patch, the sustain overrides, then one row per patch register field
        PATCH, SUSTAIN_RELEASE, SUSTAIN_STOP,
        FM_MULT, FM_WAVE, FM_LEVEL, FM_FEEDBACK, FM_AR, FM_DR, FM_SL, FM_RR, FM_EG, FM_KSL, FM_KSR, FM_AM, FM_VIB,
        // Waves page (N163 and FDS sounds): preset, size and RAM position (N163), master volume (FDS), the shown wave,
        // its repeat, the sequence's loop and release, then the shown wave's samples
        WAVE_PRESET, WAVE_SIZE, WAVE_POSITION, FDS_MASTER, WAVE_INDEX, WAVE_REPEAT, WAVE_LOOP, WAVE_RELEASE, WAVE_SAMPLES,
        // Modulation page (FDS sounds): the table preset, speed, depth, delay, auto-mod and its ratio, then the table
        MOD_PRESET, MOD_SPEED, MOD_DEPTH, MOD_DELAY, MOD_AUTO, MOD_NUMER, MOD_DENOM, MOD_TABLE,
        // SSG envelope page (5B and EPSM sounds): the shape, auto or manual period, the auto octave, the manual period
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

    /** VRC7 sounds: the FM patch page, in the duty envelope's place (the FM channels have no duty). */
    val FM_PAGE = NesEnvelopeKind.DUTY.ordinal

    /** N163 and FDS sounds: the Waves page, in the duty envelope's place (their channels have no duty). */
    val WAVE_PAGE = NesEnvelopeKind.DUTY.ordinal

    /** FDS sounds: the Modulation page, after the Waves page (where 2A03 sounds have DPCM). */
    const val MOD_PAGE = DPCM_PAGE

    /** 5B and EPSM sounds: the SSG hardware envelope page, after volume, arpeggio, pitch, mixer and noise. */
    const val S5B_ENV_PAGE = 5

    /** EPSM sounds: the FM patch's curated controls, its registers, and the rhythm drums, after the SSG envelope page. */
    const val EPSM_FM_PAGE = 6
    const val EPSM_EXPERT_PAGE = 7
    const val EPSM_RHYTHM_PAGE = 8

    /** The envelope each page of a sound shows, by chip (the 2A03 and VRC6 four; the 5B's and EPSM's mixer and noise in duty's place). */
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

    /** An FDS sound's Waves page: the N163's without size and position (FDS waves are 64 samples), with the master volume. */
    val FDS_WAVE_ROWS = listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE, Row.WAVE_PRESET, Row.FDS_MASTER,
        Row.WAVE_INDEX, Row.WAVE_REPEAT, Row.WAVE_LOOP, Row.WAVE_RELEASE, Row.WAVE_SAMPLES)

    /** An FDS sound's Modulation page (FamiStudio's order of the FDS mod parameters, then the table). */
    val MOD_ROWS = listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE, Row.MOD_PRESET, Row.MOD_SPEED, Row.MOD_DEPTH,
        Row.MOD_DELAY, Row.MOD_AUTO, Row.MOD_NUMER, Row.MOD_DENOM, Row.MOD_TABLE)

    /** The Waves and Modulation pages' own rows (the rest are the sound's). */
    private val WAVE_SETTINGS = (WAVE_ROWS.drop(5) + FDS_WAVE_ROWS.drop(5) + MOD_ROWS.drop(5)).toSet()

    /** The rows of the Waves page that work on the shown wave itself: copy, cut and paste take that wave. */
    private val WAVE_SHAPE_ROWS = setOf(Row.WAVE_INDEX, Row.WAVE_SAMPLES)

    /** The SSG envelope page (only on 5B and EPSM songs, so it has the Chip row). */
    val S5B_ROWS = listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE, Row.ENV_SHAPE, Row.ENV_AUTO, Row.ENV_OCTAVE, Row.ENV_PERIOD)

    /** The EPSM's FM, FM Expert and rhythm pages (EPSM songs always have the Chip row). */
    private val EPSM_HEAD = listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE)
    private val EPSM_ROWS by lazy {
        mapOf(
            EPSM_FM_PAGE to EPSM_HEAD + NesEpsmPage.SOUND_ROWS,
            EPSM_EXPERT_PAGE to EPSM_HEAD + NesEpsmPage.EXPERT_ROWS,
            EPSM_RHYTHM_PAGE to EPSM_HEAD + NesEpsmPage.RHYTHM_ROWS,
        )
    }

    /** Envelope rows jump by this much with the coarse ([Action.UP]/[Action.DOWN]) direction while editing. */
    private const val COARSE = 8

    /** The FDS mod speed's coarse step (↑↓ while editing; ←→ step by 1). */
    private const val SPEED_COARSE = 16

    /** Whether the DPCM page of a 2A03 sound is shown. */
    fun dpcmPage(song: Song, state: ViewState): Boolean = state.envelope == DPCM_PAGE && chip(song, state) == NesChip.APU

    /** Whether the FM patch page of a VRC7 sound is shown. */
    fun fmPage(song: Song, state: ViewState): Boolean = state.envelope == FM_PAGE && chip(song, state) == NesChip.VRC7

    /** Whether the Waves page of an N163 sound is shown. */
    fun wavePage(song: Song, state: ViewState): Boolean = state.envelope == WAVE_PAGE && chip(song, state) == NesChip.N163

    /** Whether the Waves page of an FDS sound is shown. */
    fun fdsWavePage(song: Song, state: ViewState): Boolean = state.envelope == WAVE_PAGE && chip(song, state) == NesChip.FDS

    /** Whether the Modulation page of an FDS sound is shown. */
    fun modPage(song: Song, state: ViewState): Boolean = state.envelope == MOD_PAGE && chip(song, state) == NesChip.FDS

    /** Whether an FDS sound's own pages (Waves or Modulation) are shown. */
    private fun fdsPage(song: Song, state: ViewState): Boolean = fdsWavePage(song, state) || modPage(song, state)

    /** Whether the hardware envelope page of a 5B or EPSM sound is shown. */
    fun s5bEnvPage(song: Song, state: ViewState): Boolean = state.envelope == S5B_ENV_PAGE && chip(song, state).hasSsg

    /** The EPSM FM, FM Expert or rhythm page shown ([EPSM_FM_PAGE], [EPSM_EXPERT_PAGE], [EPSM_RHYTHM_PAGE]), or null. */
    fun epsmPage(song: Song, state: ViewState): Int? =
        state.envelope.takeIf { it in EPSM_FM_PAGE..EPSM_RHYTHM_PAGE && chip(song, state) == NesChip.EPSM }

    /** The envelope page [page] of a [chip] sound shows, or null for its DPCM, FM, Waves, Modulation, SSG envelope or EPSM page. */
    fun pageKind(chip: NesChip, page: Int): NesEnvelopeKind? = when (chip) {
        NesChip.S5B, NesChip.EPSM -> S5B_PAGES.getOrNull(page)
        NesChip.VRC7, NesChip.N163, NesChip.FDS -> APU_PAGES.getOrNull(page)?.takeIf { it != NesEnvelopeKind.DUTY }
        else -> APU_PAGES.getOrNull(page)
    }

    /**
     * The envelope shown; on the DPCM, FM, Waves, Modulation, SSG envelope and EPSM pages the page's neighbour (duty, or
     * noise on the 5B and the EPSM: callers check [dpcmPage], [fmPage], [wavePage], [fdsWavePage], [modPage], [s5bEnvPage]
     * and [epsmPage] first).
     */
    fun kind(song: Song, state: ViewState): NesEnvelopeKind = if (chip(song, state).hasSsg) {
        S5B_PAGES[state.envelope.coerceIn(0, S5B_PAGES.size - 1)]
    } else {
        APU_PAGES[state.envelope.coerceIn(0, APU_PAGES.size - 1)]
    }

    /** The chip of the shown sound (2A03 when there is none). */
    fun chip(song: Song, state: ViewState): NesChip = sound(song, state)?.chip ?: NesChip.APU

    /**
     * Pages of the shown sound: its four envelopes, and DPCM on 2A03 sounds (only they map samples); a VRC7 sound's
     * volume, arpeggio and pitch envelopes and its FM patch; an N163 sound's three and its Waves page; an FDS sound's
     * three, its Waves and its Modulation page; a 5B sound's volume, arpeggio, pitch, mixer and noise envelopes and its
     * hardware envelope; an EPSM sound's six as the 5B's, then FM, FM Expert and Rhythm.
     */
    fun pages(song: Song, state: ViewState): Int = when (chip(song, state)) {
        NesChip.APU -> DPCM_PAGE + 1
        NesChip.FDS -> MOD_PAGE + 1
        NesChip.S5B -> S5B_ENV_PAGE + 1
        NesChip.EPSM -> EPSM_RHYTHM_PAGE + 1
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
        if (fdsWavePage(song, state)) return FDS_WAVE_ROWS
        if (modPage(song, state)) return MOD_ROWS
        if (s5bEnvPage(song, state)) return S5B_ROWS
        epsmPage(song, state)?.let { return EPSM_ROWS.getValue(it) }
        val base = ROWS.getValue(kind(song, state))
        if (song.nes.expansion == NesExpansion.NONE) return base
        val extra = if (chip(song, state) == NesChip.VRC6) listOf(Row.CHIP, Row.SAW) else listOf(Row.CHIP)
        return base.take(2) + extra + base.drop(2)
    }

    fun row(state: ViewState, song: Song): Row = rows(state, song).let { it[state.instField.coerceIn(0, it.size - 1)] }

    fun sound(song: Song, state: ViewState): NesInstrument? = song.nesInstrument(state.instrument)

    /** The shown envelope; null on the FM, Waves, Modulation, SSG envelope and EPSM pages (no step envelope there). */
    fun envelope(song: Song, state: ViewState): NesEnvelope? =
        if (fmPage(song, state) || wavePage(song, state) || fdsPage(song, state) || s5bEnvPage(song, state) || epsmPage(song, state) != null) null
        else sound(song, state)?.let { kind(song, state).of(it) }

    /** The shown sound's waves when it is an N163 sound, else null. */
    fun n163Wave(song: Song, state: ViewState): N163Wave? = sound(song, state)?.takeIf { it.chip == NesChip.N163 }?.wave

    /** The shown sound's waves and modulation when it is an FDS sound, else null. */
    fun fdsSound(song: Song, state: ViewState): FdsSound? = sound(song, state)?.takeIf { it.chip == NesChip.FDS }?.fds

    /**
     * An N163 or FDS sound's sequence of waves as the shared Waves rows read it: [waves] of [size] samples 0..[top],
     * each wave's [repeat], the [loop] and [release], whether it holds the most waves FamiStudio keeps ([full]) and the
     * level a cleared sample takes ([centre]).
     */
    class WaveSequence(val waves: List<List<Int>>, val repeat: List<Int>, val loop: Int, val release: Int, val size: Int,
                       val top: Int, val full: Boolean, val centre: Int)

    /** The shown sound's sequence of waves when it is an N163 or FDS sound, else null. */
    fun waveSequence(song: Song, state: ViewState): WaveSequence? = sound(song, state)?.let { ins ->
        when (ins.chip) {
            NesChip.N163 -> ins.wave.let {
                WaveSequence(it.waves, it.repeat, it.loop, it.release, it.size, N163Wave.SAMPLE.last, N163Waves.full(it), N163Waves.CENTRE)
            }
            NesChip.FDS -> ins.fds.let {
                WaveSequence(it.waves, it.repeat, it.loop, it.release, FdsSound.WAVE_SIZE, FdsSound.SAMPLE.last, FdsEdits.full(it), FdsEdits.CENTRE)
            }
            else -> null
        }
    }

    /** The shown wave of the sequence, kept inside it. */
    fun waveIndex(state: ViewState, s: WaveSequence): Int = state.wave.coerceIn(0, s.waves.size - 1)

    /** The sample under the cursor, kept inside the wave. */
    fun sampleIndex(state: ViewState, s: WaveSequence): Int = state.waveCol.coerceIn(0, s.size - 1)

    /** The modulation table entry under the cursor (0..31). */
    fun modIndex(state: ViewState): Int = state.programField.coerceIn(0, FdsSound.MOD_SIZE - 1)

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

    /** The NTSC CPU clock the FDS's units count (FamiStudio's `NesApu.FreqNtsc`, NesApu.cs line 248). */
    private const val CPU_HZ = 1789773.0

    /**
     * What FDS note names mean. Notes are true pitch: the chip plays a 64-step wave at CPU × frequency / 2^22 (NESdev
     * "FDS audio", Disch's FDS.txt, FamiStudio's own Nes_Fds and RegisterInterpreter.cs 144-146) while FamiStudio's FDS
     * note table is frequency = f × 2^20 / CPU (NesApu.cs 435-449), so the FDS channel plays FamiStudio's note two octaves
     * above the written one ([NesRules.FDS_TRANSPOSE]) and a wave holding one cycle sounds as named.
     */
    const val FDS_TUNING = "One cycle per wave sounds as named; 2 cycles an octave up, 4 cycles two octaves up"

    /**
     * How often the modulation table runs through its 32 entries at mod [speed]: CPU × speed / 2^22 Hz. The mod unit
     * adds the speed to its accumulator every 16 CPU cycles and steps the table on each carry out of bit 11, 64 steps
     * (each entry twice) per pass (NESdev "FDS audio", `$4086/$4087`); FamiStudio's register view shows the same
     * (RegisterInterpreter.cs 144-156).
     */
    fun modRateHz(speed: Int): Double = CPU_HZ * speed / 4194304.0

    /** A frequency for the editor: two decimals below 10 Hz, one below 100, whole hertz above. */
    fun hzText(hz: Double): String = org.thortracker.model.NumberText.fixed(hz, if (hz < 10.0) 2 else if (hz < 100.0) 1 else 0) + " Hz"

    /** An auto-mod ratio as FamiStudio exports it (reduced): "3/2", or "1" for 1/1. */
    fun ratioText(f: FdsSound): String = f.autoModFraction.let { (n, d) -> if (d == 1) "$n" else "$n/$d" }

    /**
     * The mod speed row: with auto-mod the ratio it follows the note by; 0 (the table stands still); else the table's rate
     * ([modRateHz]).
     */
    fun modSpeedText(f: FdsSound): String = when {
        f.autoMod -> "auto: the note's frequency × ${ratioText(f)} (the table runs at ${ratioText(f)} × the pitch heard)"
        f.modSpeed == 0 -> "0: the table stands still"
        else -> "${f.modSpeed}: the table runs at ${hzText(modRateHz(f.modSpeed))}"
    }

    /** The mod depth row: 0 turns modulation off (FamiStudio's engine then writes no speed), else the depth of 63. */
    fun modDepthText(depth: Int): String = if (depth == 0) "0: no modulation" else "$depth of 63"

    /**
     * The mod delay row. FamiStudio 4.5.3's engine keeps the modulation reset while its delay counter counts down and
     * turns it on in the frame the counter reaches 0 (famistudio_ca65.s `@check_mod_delay`: `dec; bne`), so delay d holds
     * it off for the note's first d − 1 frames and 0 and 1 both start it at once.
     */
    fun modDelayText(delay: Int): String = when {
        delay <= 1 -> "$delay: modulation from the note's first frame"
        else -> "$delay: modulation held off for the first ${delay - 1} frame${if (delay == 2) "" else "s"} " +
            "(${((delay - 1) * 1000.0 / 60.0).toInt()} ms)"
    }

    /**
     * The auto-mod numerator row: the ratio, and from which note FamiStudio's engine overflows its 16-bit multiply
     * (frequency × numerator ≥ 65536, `famistudio_mul`), wrapping the speed.
     */
    fun autoModText(f: FdsSound): String {
        val (n, d) = f.autoModFraction
        val reduced = if (n != f.autoModNumer) " (reduced from ${f.autoModNumer}/${f.autoModDenom})" else ""
        val overflow = NesRules.fdsLowestOverflowingNote(n)?.let { "; from ${NesSystem.noteName(it)} up the engine's multiply overflows" } ?: ""
        return "× $n/$d$reduced$overflow"
    }

    /** One modulation table entry: its FamiStudio target, what the chip reaches there, and the 3-bit step it took. */
    fun modEntryText(f: FdsSound, index: Int): String {
        val code = f.modTable[index]
        val step = if (code == FdsSound.MOD_RESET) "reset" else FdsSound.MOD_STEPS[code].let { s -> "step ${if (s > 0) "+" else ""}${2 * s}" }
        return "Entry $index of ${FdsSound.MOD_SIZE}: target ${f.modulation[index]}, plays ${f.modCurve[index]} ($step)"
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
     * [shape] is a 5B or EPSM SSG sound's hardware envelope (its level then replaces the volume envelope's);
     * [n163Channels] (the song's N163 channel count) sets the N163's pitch step.
     */
    fun channelNote(kind: NesEnvelopeKind, channel: ChannelKind, chip: NesChip = NesChip.APU, saw: Vrc6SawVolume = Vrc6SawVolume.HALF,
                    channelChip: NesChip = chip, shape: S5bShape = S5bShape.OFF, n163Channels: Int = 1): String? = when {
        channelChip != chip -> "${chip.label} sounds play on the ${chip.label} channel${if (chip == NesChip.FDS) "" else "s"} only"
        chip == NesChip.EPSM -> when (channel) {
            ChannelKind.SAMPLE -> if (kind == NesEnvelopeKind.VOLUME) "Rhythm: only the first step, the drum's level" else "Rhythm drums ignore it"
            ChannelKind.VOICE -> when (kind) {
                NesEnvelopeKind.VOLUME -> "FM: lowers the carriers' level"
                NesEnvelopeKind.PITCH -> "FM: + raises the pitch (frequency steps × 8)"
                NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "FM ignores it (SSG only)"
                else -> null
            }
            else -> when (kind) {
                NesEnvelopeKind.VOLUME -> if (shape == S5bShape.OFF) "SSG: 3 dB per step, 0 silences"
                    else "Not heard: the SSG envelope (${shape.label.lowercase()}) sets the level"
                NesEnvelopeKind.MIXER -> "SSG 1-3 share one noise generator"
                NesEnvelopeKind.NOISE -> "Shared: the highest sounding square sets the rate"
                else -> null
            }
        }
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
        channel == ChannelKind.WAVE && channelChip == NesChip.FDS -> when (kind) {
            NesEnvelopeKind.VOLUME -> "FDS: volume 0-15 sets the gain 0-30 (× 2)"
            NesEnvelopeKind.PITCH -> "+ raises the pitch (FDS frequency steps)"
            NesEnvelopeKind.DUTY -> "The FDS channel has no duty"
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
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "5B and EPSM sounds only"
        }
        NesChip.VRC6 -> when (kind) {
            NesEnvelopeKind.VOLUME -> "VRC6 pulses: level 0-15 per frame. Saw: 0-15 times the saw volume (its accumulator rate)"
            NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per frame"
            NesEnvelopeKind.PITCH -> "Period offset per frame (relative: added up each frame)"
            NesEnvelopeKind.DUTY -> "VRC6 pulses: 0-7 = 1/16 to 8/16 (6.25-50%). Saw: ignored"
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "5B and EPSM sounds only"
        }
        NesChip.VRC7 -> when (kind) {
            NesEnvelopeKind.VOLUME -> "FM channels: carrier level 0-15 per frame (the chip's volume, 3 dB steps; 0 is not silent)"
            NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per frame"
            NesEnvelopeKind.PITCH -> "Frequency offset per frame, × 8 frequency steps: + raises the pitch (relative: added up each frame)"
            NesEnvelopeKind.DUTY -> "The FM channels have no duty: this page is the FM patch"
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "5B and EPSM sounds only"
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
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "5B and EPSM sounds only"
        }
        NesChip.FDS -> when (kind) {
            NesEnvelopeKind.VOLUME -> "FDS channel: level 0-15 per frame, written × 2 as the chip's gain (0-30 of 32), times the master volume"
            NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per frame"
            NesEnvelopeKind.PITCH -> "Frequency offset per frame in FDS frequency steps: + raises the pitch"
            NesEnvelopeKind.DUTY -> "The FDS channel has no duty: this page is the waves"
            NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE -> "5B and EPSM sounds only"
        }
        NesChip.EPSM -> when (kind) {
            NesEnvelopeKind.VOLUME -> "SSG: level 0-15 per frame (ignored under a hardware envelope). FM: lowers the carriers. " +
                "Rhythm: the first step is the drum's level"
            NesEnvelopeKind.ARPEGGIO -> "SSG and FM: semitones added to the note per frame. Rhythm: ignored (fixed-pitch drums)"
            NesEnvelopeKind.PITCH -> "SSG: period offset per frame; FM: frequency steps × 8, + raises (relative: added up each frame). Rhythm: ignored"
            NesEnvelopeKind.DUTY -> "EPSM sounds have no duty"
            NesEnvelopeKind.MIXER -> "SSG per frame: tone+noise, noise, tone, or off (a constant level). Empty: tone"
            NesEnvelopeKind.NOISE -> "SSG noise period 0-31 per frame (higher is lower). One generator for SSG 1-3"
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

    /** How the SSG envelope page shows a [chip] (5B or EPSM) sound's hardware envelope rows. */
    fun s5bRowText(row: Row, e: S5bEnvelope, chip: NesChip = NesChip.S5B): String = when (row) {
        Row.ENV_SHAPE -> if (e.shape == S5bShape.OFF) "Off: the volume envelope sets the level" else
            "${e.shape.label} ${e.shape.picture}: ${if (e.shape.repeats) "repeats while the note holds" else "once per note"}; each attack restarts it"
        Row.ENV_AUTO -> if (e.autoPitch) "Auto: the period follows the note" else "Manual: a fixed period (Ixx/Jyy change it)"
        Row.ENV_OCTAVE -> if (e.autoPitch) "${e.octave}: ${NesS5b.octaveText(e.shape, e.octave)}" else "${e.octave} (auto only)"
        Row.ENV_PERIOD -> if (e.autoPitch) "${e.period} (manual only)" else "${e.period}: ${NesS5b.periodText(e.shape, e.period, chip)}"
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
        waveSequence(song, st)?.let { s ->
            st.wave = waveIndex(st, s)
            st.waveCol = sampleIndex(st, s)
        }
        if (modPage(song, st)) st.programField = modIndex(st)
        if (epsmPage(song, st) == EPSM_EXPERT_PAGE) st.programField = NesEpsmPage.operator(st)
        NesDpcmPage.normalise(v)
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, NesSystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        val wasDpcm = dpcmPage(song, state)
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
        // An EPSM sound's drum settings are its rhythm page: show it while a rhythm channel is selected.
        if (chip(song, state) == NesChip.EPSM) {
            val onRhythm = NesRules.isEpsmRhythm(song.nes.expansion, selection.channel)
            if (onRhythm != (state.envelope == EPSM_RHYTHM_PAGE)) state.envelope = if (onRhythm) EPSM_RHYTHM_PAGE else 0
            return
        }
        // The DPCM channel's sounds are their sample mappings: show that page, on the selected note's key.
        val onDpcm = selection.channel == NesRules.DPCM
        if (onDpcm != wasDpcm) state.envelope = if (onDpcm) DPCM_PAGE else 0
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
     * sound Listen moves to a channel the page is about (SSG, FM or rhythm).
     */
    fun showEnvelope(v: ViewController, index: Int) {
        val st = v.state
        val song = v.session.song
        val row = row(st, song)
        val wasDpcm = dpcmPage(song, st)
        st.envelope = index.mod(pages(song, st))
        val rows = rows(st, song)
        st.instField = rows.indexOf(row).takeIf { it >= 0 }
            ?: SAME_ROW[row]?.firstNotNullOfOrNull { same -> rows.indexOf(same).takeIf { it >= 0 } }
            ?: rows.indexOf(when {
                dpcmPage(song, st) -> Row.KEY
                fmPage(song, st) -> Row.PATCH
                wavePage(song, st) || fdsWavePage(song, st) -> Row.WAVE_PRESET
                modPage(song, st) -> Row.MOD_PRESET
                s5bEnvPage(song, st) -> Row.ENV_SHAPE
                epsmPage(song, st) == EPSM_FM_PAGE -> Row.OPN_PRESET
                epsmPage(song, st) == EPSM_EXPERT_PAGE -> Row.OPN_RAW_ALGORITHM
                epsmPage(song, st) == EPSM_RHYTHM_PAGE -> Row.RHYTHM_LEVEL
                else -> Row.LOOP
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
            3, 4, S5B_ENV_PAGE -> { ch -> NesRules.isEpsmSsg(expansion, ch) }
            EPSM_FM_PAGE, EPSM_EXPERT_PAGE -> { ch -> NesRules.isEpsmFm(expansion, ch) }
            EPSM_RHYTHM_PAGE -> { ch -> NesRules.isEpsmRhythm(expansion, ch) }
            else -> { ch -> NesRules.chip(expansion, ch) == NesChip.EPSM }
        }
        return if (fits(current)) current else song.system.channels.indices.firstOrNull(fits) ?: current
    }

    /**
     * Rows of the Waves, Modulation and envelope pages that do the same thing: page changes keep the cursor on them (the
     * first of the listed rows the new page has).
     */
    private val SAME_ROW = mapOf(
        Row.STEPS to listOf(Row.WAVE_SAMPLES, Row.MOD_TABLE),
        Row.WAVE_SAMPLES to listOf(Row.STEPS, Row.MOD_TABLE),
        Row.MOD_TABLE to listOf(Row.WAVE_SAMPLES, Row.STEPS),
        Row.LOOP to listOf(Row.WAVE_LOOP), Row.WAVE_LOOP to listOf(Row.LOOP),
        Row.RELEASE to listOf(Row.WAVE_RELEASE), Row.WAVE_RELEASE to listOf(Row.RELEASE),
        Row.WAVE_PRESET to listOf(Row.MOD_PRESET), Row.MOD_PRESET to listOf(Row.WAVE_PRESET),
    )

    /**
     * Moves to another slot; Listen moves to the channel that slot plays on most, so a bass sounds on Triangle, or
     * when it plays nowhere yet, to the first channel of its chip. On the fifth page (a 2A03 sound's DPCM, an FDS
     * sound's Modulation) a slot of the same chip keeps it; another chip's slot goes to the first page.
     */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, NesSystem.SLOTS)
        if (slot == st.instrument) return
        val song = v.session.song
        val before = chip(song, st)
        st.instrument = slot
        st.listenChannel = mostUsedChannel(song, slot) ?: listenFor(song, chip(song, st), st.listenChannel)
        // A page the new sound lacks (DPCM, the 5B envelope page) falls back to volume, as does the fifth page between an
        // FDS sound and another chip's (Modulation is not DPCM or a 5B envelope), and a page past pitch between an EPSM
        // sound and a 2A03 one (mixer and noise are not duty and DPCM); DPCM shows the first mapped key.
        val now = chip(song, st)
        val fdsSwitch = now != before && (now == NesChip.FDS || before == NesChip.FDS) && st.envelope == MOD_PAGE
        val epsmSwitch = now != before && (now == NesChip.EPSM || before == NesChip.EPSM) && st.envelope > 2
        if (st.envelope >= pages(song, st) || fdsSwitch || epsmSwitch) showEnvelope(v, 0)
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
     * Makes the shown sound a sound of the song's next chip (2A03 ↔ VRC6 / VRC7 / N163 / FDS / 5B / EPSM); Listen follows to
     * a channel of that chip, and from or to the EPSM a page past pitch goes back to volume.
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

    /** Steps the FDS master volume 100% / 66% / 50% / 40%. */
    private fun stepMaster(v: ViewController, delta: Int) {
        val volumes = FdsMasterVolume.entries
        editFds(v, "Set master volume") {
            it.copy(masterVolume = volumes[(volumes.indexOf(it.masterVolume) + delta).coerceIn(0, volumes.size - 1)])
        }
    }

    /** "5B" or "SSG": the shown sound's SSG envelope, as undo labels name it. */
    private fun envName(v: ViewController): String = if (chip(v.session.song, v.state) == NesChip.EPSM) "SSG" else "5B"

    /** Steps the 5B or EPSM sound's hardware envelope shape: off, then `$08-$0F`. */
    private fun stepShape(v: ViewController, delta: Int) {
        val shapes = S5bShape.entries
        v.session.editNesS5b(v.state.instrument, "Set ${envName(v)} envelope shape") {
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
            // Clear held on the modulation table: ←→ jump along it.
            row == Row.MOD_TABLE && v.clearHeld -> moveModEntry(v, COARSE * dx)
            dy != 0 -> { st.instField = (st.instField - dy).coerceIn(0, rows.size - 1); st.textPos = 0 }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.ENVELOPE -> showEnvelope(v, st.envelope + dx)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, lastStep(e))
            row == Row.WAVE_INDEX -> stepWave(v, dx)
            row == Row.WAVE_SAMPLES -> moveSample(v, dx)
            row == Row.MOD_TABLE -> moveModEntry(v, dx)
            // FM fields: left/right picks the operator (modulator / carrier).
            row in FM_PARAMS -> st.programField = (st.programField + dx).coerceIn(0, 1)
            // The EPSM Expert grid: left/right picks the operator.
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
            Row.PATCH -> stepPatch(v, dx - dy)
            Row.SUSTAIN_RELEASE -> if (dx != 0 || dy != 0) toggleSustain(v, release = true)
            Row.SUSTAIN_STOP -> if (dx != 0 || dy != 0) toggleSustain(v, release = false)
            in FM_PARAMS -> {
                val param = FM_PARAMS.getValue(row)
                val coarse = when { param.max >= 63 -> 8; param.max >= 15 -> 4; else -> 1 }
                setFmParam(v, param) { it + dx + coarse * dy }
            }
            Row.WAVE_PRESET -> if (chip == NesChip.FDS) stepFdsPreset(v, dx - dy) else stepPreset(v, dx - dy)
            Row.WAVE_SIZE -> editWave(v, "Set wave size") { w, ram -> N163Waves.withSize(w, w.size + 4 * dx + 16 * dy, ram) }
            Row.WAVE_POSITION -> editWave(v, "Set wave position") { w, ram -> N163Waves.withPosition(w, w.position + 2 * dx + 16 * dy, ram) }
            Row.FDS_MASTER -> stepMaster(v, dx - dy)
            Row.WAVE_INDEX -> editWaves(v, "Set wave count",
                { w, ram -> N163Waves.withCount(w, w.waves.size + dx + COARSE * dy, ram) },
                { FdsEdits.withCount(it, it.waves.size + dx + COARSE * dy) })
            Row.WAVE_REPEAT -> {
                val i = shownWave(v) ?: return
                editWaves(v, "Set wave repeat",
                    { w, ram -> N163Waves.withRepeat(w, i, w.repeat.getOrElse(i) { 1 } + dx + 4 * dy, ram) },
                    { FdsEdits.withRepeat(it, i, it.repeat.getOrElse(i) { 1 } + dx + 4 * dy) })
            }
            Row.WAVE_LOOP -> editWaves(v, "Set wave loop",
                { w, ram -> N163Waves.withLoop(w, w.loop + dx + COARSE * dy, ram) },
                { FdsEdits.withLoop(it, it.loop + dx + COARSE * dy) })
            Row.WAVE_RELEASE -> editWaves(v, "Set wave release",
                { w, ram -> N163Waves.withRelease(w, w.release + dx + COARSE * dy, ram) },
                { FdsEdits.withRelease(it, it.release + dx + COARSE * dy) })
            Row.WAVE_SAMPLES -> drawSample(v, dx, dy)
            Row.MOD_PRESET -> stepModPreset(v, dx - dy)
            Row.MOD_SPEED -> setModSpeed(v) { it + dx + SPEED_COARSE * dy }
            Row.MOD_DEPTH -> editFds(v, "Set mod depth") { it.copy(modDepth = it.modDepth + dx + COARSE * dy) }
            Row.MOD_DELAY -> editFds(v, "Set mod delay") { it.copy(modDelay = it.modDelay + dx + COARSE * dy) }
            Row.MOD_AUTO -> if (dx != 0 || dy != 0) toggleAutoMod(v)
            Row.MOD_NUMER -> setRatio(v, numerator = true) { it + dx + 4 * dy }
            Row.MOD_DENOM -> setRatio(v, numerator = false) { it + dx + 4 * dy }
            Row.MOD_TABLE -> drawModEntry(v, dx, dy)
            // SSG envelope page: Edit+←→ steps, Edit+↑↓ steps the octave coarsely / doubles or halves the manual period.
            Row.ENV_SHAPE -> stepShape(v, dx - dy)
            Row.ENV_AUTO -> if (dx != 0 || dy != 0) session.editNesS5b(slot, "Set ${envName(v)} envelope period mode") { it.copy(autoPitch = !it.autoPitch) }
            Row.ENV_OCTAVE -> session.editNesS5b(slot, "Set ${envName(v)} envelope octave") { it.copy(octave = it.octave + dx + 4 * dy) }
            Row.ENV_PERIOD -> session.editNesS5b(slot, "Set ${envName(v)} envelope period") { it.copy(period = octaveOf(it.period + dx, dy)) }
            else -> if (!NesEpsmPage.adjust(v, row, dx, dy)) NesDpcmPage.adjust(v, row, dx, dy)
        }
    }

    /** Edits the shown N163 sound's waves; [f] gets the waves and the song's wave RAM in samples. */
    private fun editWave(v: ViewController, label: String, f: (N163Wave, Int) -> N163Wave): Boolean {
        val ram = N163Waves.ram(v.session.song)
        return v.session.editNesWave(v.state.instrument, label) { f(it, ram) }
    }

    /** Edits the shown FDS sound's waves and modulation. */
    private fun editFds(v: ViewController, label: String, f: (FdsSound) -> FdsSound): Boolean =
        v.session.editNesFds(v.state.instrument, label, f)

    /** Edits the shown sound's sequence of waves: an FDS sound's with [fds], an N163 sound's with [n163]. */
    private fun editWaves(v: ViewController, label: String, n163: (N163Wave, Int) -> N163Wave, fds: (FdsSound) -> FdsSound): Boolean =
        if (chip(v.session.song, v.state) == NesChip.FDS) editFds(v, label, fds) else editWave(v, label, n163)

    /** The shown wave of the shown N163 or FDS sound, or null for other sounds. */
    private fun shownWave(v: ViewController): Int? = waveSequence(v.session.song, v.state)?.let { waveIndex(v.state, it) }

    /** Shows the previous / next wave of the sequence. */
    private fun stepWave(v: ViewController, delta: Int) {
        val s = waveSequence(v.session.song, v.state) ?: return
        v.state.wave = (waveIndex(v.state, s) + delta).coerceIn(0, s.waves.size - 1)
    }

    /** Moves the sample cursor along the wave. */
    private fun moveSample(v: ViewController, delta: Int) {
        val s = waveSequence(v.session.song, v.state) ?: return
        v.state.waveCol = (sampleIndex(v.state, s) + delta).coerceIn(0, s.size - 1)
    }

    /**
     * Edit held on the samples: ↑↓ raise / lower the sample under the cursor; ←→ carry its value to the next sample (draws
     * flat runs), as on an envelope's steps.
     */
    private fun drawSample(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val s = waveSequence(v.session.song, st) ?: return
        val wave = waveIndex(st, s)
        val at = sampleIndex(st, s)
        val value = s.waves[wave][at]
        if (dy != 0) {
            editWaves(v, "Set wave sample",
                { cur, ram -> N163Waves.withSample(cur, wave, at, value + dy, ram) },
                { FdsEdits.withSample(it, wave, at, value + dy) })
        }
        if (dx != 0) {
            val next = (at + dx).coerceIn(0, s.size - 1)
            if (next == at) return
            st.waveCol = next
            editWaves(v, "Draw wave",
                { cur, ram -> N163Waves.withSample(cur, wave, next, value, ram) },
                { FdsEdits.withSample(it, wave, next, value) })
        }
    }

    /**
     * The preset [delta] steps from [current] among [presets]; from drawn values ([current] null) the first step goes to
     * the first or last preset. Presets that would make the values already there ([same]) are passed over; null past
     * either end.
     */
    private fun nextPreset(presets: List<WavePreset>, current: WavePreset?, delta: Int, same: (WavePreset) -> Boolean): WavePreset? {
        if (delta == 0) return null
        val step = if (delta > 0) 1 else -1
        var i = current?.let { presets.indexOf(it) } ?: if (step > 0) -1 else presets.size
        do {
            i += step
            if (i !in presets.indices) return null
        } while (same(presets[i]))
        return presets[i]
    }

    /**
     * Steps the N163 waves' preset through [WavePreset] and applies it to every wave; from drawn ("Custom") waves the
     * first step goes to the first or last preset. Presets that would make the same waves are passed over.
     */
    private fun stepPreset(v: ViewController, delta: Int) {
        val w = n163Wave(v.session.song, v.state) ?: return
        val preset = nextPreset(WavePreset.entries, N163Presets.presetOf(w.waves, w.size), delta) {
            N163Presets.waves(it, w.size, w.waves.size) == w.waves
        } ?: return
        editWave(v, "Set wave preset") { cur, ram -> N163Waves.withPreset(cur, preset, ram) }
    }

    /** Steps the FDS waves' preset through [WavePreset] (the PWM presets sweep across the waves), as [stepPreset]. */
    private fun stepFdsPreset(v: ViewController, delta: Int) {
        val f = fdsSound(v.session.song, v.state) ?: return
        val preset = nextPreset(WavePreset.entries, FdsPresets.presetOf(f.waves), delta) {
            FdsPresets.waves(it, f.waves.size) == f.waves
        } ?: return
        editFds(v, "Set wave preset") { FdsEdits.withPreset(it, preset) }
    }

    /** Steps the FDS modulation table's preset through [FdsPresets.MOD_PRESETS] (FamiStudio's: no PWM), as [stepPreset]. */
    private fun stepModPreset(v: ViewController, delta: Int) {
        val f = fdsSound(v.session.song, v.state) ?: return
        val preset = nextPreset(FdsPresets.MOD_PRESETS, FdsPresets.modPresetOf(f.modulation), delta) {
            FdsPresets.modulation(it) == f.modulation
        } ?: return
        editFds(v, "Set mod preset") { FdsEdits.withModPreset(it, preset) }
    }

    /** A note why the shown sound holds no more waves: FamiStudio's limits (N163: by size; FDS: 16). */
    private fun fullText(v: ViewController, s: WaveSequence): String {
        val slot = EditorSession.hex2(v.state.instrument)
        return if (chip(v.session.song, v.state) == NesChip.FDS) "Sound $slot holds ${s.waves.size} waves, the most FamiStudio keeps for an FDS sound"
        else "Sound $slot holds ${s.waves.size} waves, the most of ${s.size} samples FamiStudio keeps"
    }

    /** Inserts a copy of the shown wave after it and shows the copy. */
    private fun duplicateWave(v: ViewController) {
        val st = v.state
        val s = waveSequence(v.session.song, st) ?: return
        if (s.full) return v.session.note(fullText(v, s))
        val i = waveIndex(st, s)
        if (editWaves(v, "Duplicate wave", { cur, ram -> N163Waves.duplicated(cur, i, ram) }, { FdsEdits.duplicated(it, i) })) st.wave = i + 1
    }

    /** Deletes the shown wave (a sound keeps at least one). */
    private fun deleteWave(v: ViewController) {
        val st = v.state
        val s = waveSequence(v.session.song, st) ?: return
        if (s.waves.size == 1) return v.session.note("Sound ${EditorSession.hex2(st.instrument)} keeps at least one wave")
        val i = waveIndex(st, s)
        if (editWaves(v, "Delete wave", { cur, ram -> N163Waves.removed(cur, i, ram) }, { FdsEdits.removed(it, i) })) {
            st.wave = i.coerceAtMost(s.waves.size - 2)
        }
    }

    /** Copies the shown wave, then deletes it; the only wave becomes flat (silent) instead. */
    private fun cutWave(v: ViewController) {
        val st = v.state
        val s = waveSequence(v.session.song, st) ?: return
        val i = waveIndex(st, s)
        v.session.copyNesWave(st.instrument, i)
        if (s.waves.size > 1) {
            if (editWaves(v, "Cut wave", { cur, ram -> N163Waves.removed(cur, i, ram) }, { FdsEdits.removed(it, i) })) {
                st.wave = i.coerceAtMost(s.waves.size - 2)
            }
        } else {
            editWaves(v, "Cut wave",
                { cur, ram -> N163Waves.withWave(cur, i, List(cur.size) { N163Waves.CENTRE }, ram) },
                { FdsEdits.withWave(it, i, List(FdsSound.WAVE_SIZE) { FdsEdits.CENTRE }) })
        }
    }

    /** Moves the modulation table cursor. */
    private fun moveModEntry(v: ViewController, delta: Int) {
        v.state.programField = (modIndex(v.state) + delta).coerceIn(0, FdsSound.MOD_SIZE - 1)
    }

    /**
     * Edit held on the modulation table: ↑↓ raise / lower the target under the cursor; ←→ carry its value to the next
     * entry (draws flat runs), as on the samples.
     */
    private fun drawModEntry(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val f = fdsSound(v.session.song, st) ?: return
        val at = modIndex(st)
        val value = f.modulation[at]
        if (dy != 0) editFds(v, "Set mod target") { FdsEdits.withTarget(it, at, value + dy) }
        if (dx != 0) {
            val next = (at + dx).coerceIn(0, FdsSound.MOD_SIZE - 1)
            if (next == at) return
            st.programField = next
            editFds(v, "Draw mod table") { FdsEdits.withTarget(it, next, value) }
        }
    }

    /** Sets the manual mod speed; with auto-mod on, the notes set it (FamiStudio greys the row), so nothing changes. */
    private fun setModSpeed(v: ViewController, speed: (Int) -> Int) {
        val f = fdsSound(v.session.song, v.state) ?: return
        if (f.autoMod) return v.session.note("Auto-mod sets the mod speed from each note: turn Auto-mod off to set it")
        editFds(v, "Set mod speed") { it.copy(modSpeed = speed(it.modSpeed)) }
    }

    /** Sets the auto-mod numerator or denominator (1-32); only with auto-mod on (FamiStudio greys them otherwise). */
    private fun setRatio(v: ViewController, numerator: Boolean, value: (Int) -> Int) {
        val f = fdsSound(v.session.song, v.state) ?: return
        if (!f.autoMod) return v.session.note("Turn Auto-mod on to set its ratio")
        if (numerator) editFds(v, "Set auto-mod numerator") { it.copy(autoModNumer = value(it.autoModNumer)) }
        else editFds(v, "Set auto-mod denominator") { it.copy(autoModDenom = value(it.autoModDenom)) }
    }

    private fun toggleAutoMod(v: ViewController) {
        editFds(v, "Set auto-mod") { it.copy(autoMod = !it.autoMod) }
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
            Row.ENV_AUTO -> session.editNesS5b(st.instrument, "Set ${envName(v)} envelope period mode") { it.copy(autoPitch = !it.autoPitch) }
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
            Row.WAVE_PRESET -> if (chip == NesChip.FDS) stepFdsPreset(v, 1) else stepPreset(v, 1)
            Row.WAVE_INDEX -> duplicateWave(v)
            Row.WAVE_SIZE, Row.WAVE_POSITION, Row.FDS_MASTER, Row.WAVE_REPEAT, Row.WAVE_LOOP, Row.WAVE_RELEASE, Row.WAVE_SAMPLES -> Unit
            Row.MOD_PRESET -> stepModPreset(v, 1)
            Row.MOD_AUTO -> toggleAutoMod(v)
            Row.MOD_SPEED, Row.MOD_DEPTH, Row.MOD_DELAY, Row.MOD_NUMER, Row.MOD_DENOM, Row.MOD_TABLE -> Unit
            in FM_PARAMS -> FM_PARAMS.getValue(row).let { p -> if (p.max == 1) setFmParam(v, p) { 1 - it } }
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
            Row.PATCH -> session.editNesFm(slot, "Set VRC7 patch") { it.withPreset(Vrc7Presets.BELL) }
            Row.SUSTAIN_RELEASE -> session.editVrc7Sustain("Set release sustain") { it.copy(release = false) }
            Row.SUSTAIN_STOP -> session.editVrc7Sustain("Set stop sustain") { it.copy(stop = false) }
            in FM_PARAMS -> setFmParam(v, FM_PARAMS.getValue(row)) { 0 }
            Row.WAVE_PRESET -> editWaves(v, "Set wave preset",
                { w, ram -> N163Waves.withPreset(w, WavePreset.SINE, ram) }, { FdsEdits.withPreset(it, WavePreset.SINE) })
            Row.WAVE_SIZE -> editWave(v, "Set wave size") { w, ram -> N163Waves.withSize(w, N163Wave.DEFAULT.size, ram) }
            Row.WAVE_POSITION -> editWave(v, "Set wave position") { w, ram -> N163Waves.withPosition(w, 0, ram) }
            Row.FDS_MASTER -> editFds(v, "Set master volume") { it.copy(masterVolume = FdsMasterVolume.FULL) }
            Row.WAVE_INDEX -> deleteWave(v)
            Row.WAVE_REPEAT -> {
                val i = shownWave(v) ?: return
                editWaves(v, "Set wave repeat", { w, ram -> N163Waves.withRepeat(w, i, 1, ram) }, { FdsEdits.withRepeat(it, i, 1) })
            }
            Row.WAVE_LOOP -> editWaves(v, "Clear wave loop", { w, ram -> N163Waves.withLoop(w, -1, ram) }, { FdsEdits.withLoop(it, -1) })
            Row.WAVE_RELEASE -> editWaves(v, "Clear wave release", { w, ram -> N163Waves.withRelease(w, -1, ram) }, { FdsEdits.withRelease(it, -1) })
            Row.WAVE_SAMPLES -> {
                val s = waveSequence(session.song, st) ?: return
                val wave = waveIndex(st, s)
                val at = sampleIndex(st, s)
                editWaves(v, "Clear wave sample",
                    { w, ram -> N163Waves.withSample(w, wave, at, N163Waves.CENTRE, ram) },
                    { FdsEdits.withSample(it, wave, at, FdsEdits.CENTRE) })
            }
            Row.MOD_PRESET -> editFds(v, "Set mod preset") { FdsEdits.withModPreset(it, WavePreset.FLAT) }
            Row.MOD_SPEED -> setModSpeed(v) { 0 }
            Row.MOD_DEPTH -> editFds(v, "Set mod depth") { it.copy(modDepth = 0) }
            Row.MOD_DELAY -> editFds(v, "Set mod delay") { it.copy(modDelay = 0) }
            Row.MOD_AUTO -> editFds(v, "Set auto-mod") { it.copy(autoMod = false) }
            Row.MOD_NUMER -> setRatio(v, numerator = true) { 1 }
            Row.MOD_DENOM -> setRatio(v, numerator = false) { 1 }
            Row.MOD_TABLE -> {
                val at = modIndex(st)
                editFds(v, "Clear mod target") { FdsEdits.withTarget(it, at, 0) }
            }
            Row.ENV_SHAPE -> session.editNesS5b(slot, "Set ${envName(v)} envelope shape") { it.copy(shape = S5bShape.OFF) }
            Row.ENV_AUTO -> session.editNesS5b(slot, "Set ${envName(v)} envelope period mode") { it.copy(autoPitch = S5bEnvelope.defaultFor(chip).autoPitch) }
            Row.ENV_OCTAVE -> session.editNesS5b(slot, "Set ${envName(v)} envelope octave") { it.copy(octave = S5bEnvelope.defaultFor(chip).octave) }
            Row.ENV_PERIOD -> session.editNesS5b(slot, "Set ${envName(v)} envelope period") { it.copy(period = S5bEnvelope.defaultFor(chip).period) }
            else -> if (!NesEpsmPage.clear(v, row)) NesDpcmPage.clear(v, row)
        }
    }

    override fun audition(v: ViewController) {
        // DPCM plays the shown key's mapping, whichever page is up.
        if (dpcmPage(v.session.song, v.state) || v.state.listenChannel == NesRules.DPCM) NesDpcmPage.audition(v)
        else v.startAudition(v.state.listenChannel, v.defaultNote(), v.state.instrument)
    }

    /**
     * Rows about the whole sound copy, cut and paste the sound; envelope rows the shown envelope. The DPCM page, an FDS
     * sound's Waves and Modulation pages, a 5B or EPSM sound's hardware envelope page and the EPSM's FM and rhythm pages
     * have no envelope.
     */
    private fun envelopeRow(song: Song, state: ViewState, row: Row) =
        !dpcmPage(song, state) && !fdsPage(song, state) && !s5bEnvPage(song, state) && epsmPage(song, state) == null &&
            row != Row.SLOT && row != Row.NAME &&
            row != Row.CHIP &&
            row != Row.SAW && row != Row.LISTEN && row != Row.PATCH && row != Row.SUSTAIN_RELEASE && row != Row.SUSTAIN_STOP &&
            row !in FM_PARAMS && row !in WAVE_SETTINGS

    /** On the Waves page's Wave and Samples rows, copy, cut and paste take the shown wave. */
    private fun waveRow(song: Song, state: ViewState, row: Row) =
        row in WAVE_SHAPE_ROWS && (wavePage(song, state) || fdsWavePage(song, state))

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val kind = kind(session.song, st)
        val slot = st.instrument
        val row = row(st, session.song)
        val onEnvelope = envelopeRow(session.song, st, row)
        val onWave = waveRow(session.song, st, row)
        val envelopeClip = session.clipboard is NesClip.Envelope
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showEnvelope(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> when {
                onWave -> shownWave(v)?.let { session.copyNesWave(slot, it) }
                onEnvelope -> session.copyNesEnvelope(slot, kind)
                else -> session.copyNesInstrument(slot)
            }
            Action.PASTE -> when {
                onWave -> shownWave(v)?.let { session.pasteNesWave(slot, it, kind) }
                envelopeClip && (wavePage(session.song, st) || fdsWavePage(session.song, st)) ->
                    session.note("The Waves page has no envelope: paste envelopes on the volume, arpeggio or pitch page")
                envelopeClip && modPage(session.song, st) ->
                    session.note("The Modulation page has no envelope: paste envelopes on the volume, arpeggio or pitch page")
                envelopeClip && (s5bEnvPage(session.song, st) || epsmPage(session.song, st) != null) ->
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
            Row.FDS_MASTER -> { h += Hint(v.editLr(), "Master volume"); h += Hint(v.k(Action.CLEAR), "100%") }
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
                h += Hint(v.k(Action.CLEAR), "Sample to ${waveSequence(v.session.song, st)?.centre ?: N163Waves.CENTRE}")
            }
            Row.MOD_PRESET -> { h += Hint(v.editLr(), "Table preset"); h += Hint(v.k(Action.EDIT), "Next preset"); h += Hint(v.k(Action.CLEAR), "Flat") }
            Row.MOD_SPEED -> {
                if (fdsSound(v.session.song, st)?.autoMod == true) h += Hint(v.editLr(), "Set by auto-mod")
                else h += Hint(v.editLr(), "Speed ±1 (${v.ud()} ±$SPEED_COARSE)")
                h += Hint(v.k(Action.CLEAR), "0")
            }
            Row.MOD_DEPTH -> { h += Hint(v.editLr(), "Depth ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "0: off") }
            Row.MOD_DELAY -> { h += Hint(v.editLr(), "Frames ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "0") }
            Row.MOD_AUTO -> { h += Hint(v.k(Action.EDIT), "On / off"); h += Hint(v.k(Action.CLEAR), "Off") }
            Row.MOD_NUMER, Row.MOD_DENOM -> {
                val part = if (row == Row.MOD_NUMER) "Numerator" else "Denominator"
                if (fdsSound(v.session.song, st)?.autoMod == true) h += Hint(v.editLr(), "$part ±1 (${v.ud()} ±4)")
                else h += Hint(v.editLr(), "Auto-mod is off")
                h += Hint(v.k(Action.CLEAR), "1")
            }
            Row.MOD_TABLE -> {
                h += Hint(v.lr(), "Entry")
                h += Hint(v.editUd(), "Target")
                h += Hint(v.editLr(), "Draw target along")
                h += Hint(v.keys.held(Action.CLEAR, v.lr()), "Jump $COARSE entries")
                h += Hint(v.k(Action.CLEAR), "Target to 0")
            }
            Row.ENV_SHAPE -> { h += Hint(v.editLr(), "Shape"); h += Hint(v.k(Action.CLEAR), "Off") }
            Row.ENV_AUTO -> { h += Hint(v.k(Action.EDIT), "Auto / manual period"); h += Hint(v.k(Action.CLEAR), "Auto") }
            Row.ENV_OCTAVE -> { h += Hint(v.editLr(), "Octave ±1 (${v.ud()} ±4)"); h += Hint(v.k(Action.CLEAR), "3") }
            Row.ENV_PERIOD -> {
                h += Hint(v.editLr(), "Period ±1 (${v.ud()} ×2 / ÷2)")
                h += Hint(v.k(Action.CLEAR), "${S5bEnvelope.defaultFor(chip(song, st)).period}")
            }
            else -> if (!NesEpsmPage.hints(v, row, h)) NesDpcmPage.hints(v, row, h)
        }
        if (row == Row.LENGTH || row == Row.LOOP || row == Row.RELEASE || row == Row.MODE || row == Row.CHIP || row == Row.SAW ||
            row == Row.PATCH || row == Row.SUSTAIN_RELEASE || row == Row.SUSTAIN_STOP ||
            (row in WAVE_SETTINGS && row !in WAVE_SHAPE_ROWS && row != Row.MOD_TABLE) || row in S5B_ROWS.drop(5)) h += Hint(v.lr(), "Sound slot")
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
     * rows (the VRC6's Chip and Saw rows, the VRC7's FM patch page, the N163's Waves page for its channel count, the FDS's
     * Waves and Modulation pages, the 5B's mixer, noise and hardware envelope pages, and their envelope meanings).
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
            NesExpansion.FDS -> fdsHelp(keys)
            NesExpansion.EPSM -> NesEpsmPage.helpRows(keys)
            else -> n163Help(keys, expansion.n163Channels)
        }
    }

    /** The FDS sections of Help: the Chip row, the Waves page and the Modulation page with their keys. */
    private fun fdsHelp(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        return listOf(
            "FDS sounds" to listOf(
                "Chip" to "On songs with the FDS: whether the sound plays on the 2A03's channels or on the FDS channel ($edit+$lr there). " +
                    "FDS sounds have volume, arpeggio and pitch envelopes, no duty and no DPCM page; their fourth page is Waves, " +
                    "their fifth Modulation",
                "Waves page" to "The sound's waves: 64 samples of 0-63 the channel plays over and over, one or a sequence of up to " +
                    "${FdsSound.MAX_WAVES} (FamiStudio's limit), each for its repeat frames, then looping",
                "Tuning" to "$FDS_TUNING. Notes are true pitch: the chip plays a 64-sample wave at CPU × frequency / 2^22, so " +
                    "the FDS channel plays FamiStudio's note two octaves above the written one (FamiStudio's own FDS note table " +
                    "is for 2^20); notes reach ${NesSystem.noteName(NesRules.highestNote(NesExpansion.FDS, NesRules.FDS))}",
                "Preset" to "$edit+$lr: Sine, Triangle, Sawtooth, Square 50%/25%, Flat, PWM 1-4 (FamiStudio's), made for every wave " +
                    "(the PWM presets change the width from wave to wave); $edit tap: the next one; $clear: Sine. Drawn waves show Custom",
                "Master volume" to "The whole channel's output: 100%, 66%, 50% or 40% (the chip's \$4089; $edit+$lr; $clear: 100%)",
                "Wave" to "$lr: the previous / next wave of the sequence; $edit tap: duplicate it; $clear: delete it; " +
                    "$edit+$lr: waves ±1 at the end ($edit+$ud ±$COARSE)",
                "Repeat" to "Frames the shown wave plays (1-15) before the next ($edit+$lr, $edit+$ud ±4; $clear: 1)",
                "Loop / release" to "As on a volume envelope, in waves: after the last wave the sequence goes back to the loop wave " +
                    "(none: the last wave holds); after OFF the waves from the release play out. A release needs a loop",
                "Samples" to "$lr: the sample; $edit+$ud: raise / lower it; $edit+$lr: carry its value along; $clear: back to " +
                    "${FdsEdits.CENTRE}; $clear hold+$lr: jump $COARSE samples, $clear hold+$ud: previous / next wave",
                "Copy / paste" to "On Wave and Samples: the shown wave (an N163 wave is rescaled from 0-15 to 0-63 and resampled to 64); " +
                    "elsewhere the sound",
            ) + APU_PAGES.dropLast(1).map { it.label to envelopeSummary(it, NesChip.FDS) },
            "FDS modulation" to listOf(
                "Modulation page" to "The chip's frequency modulation: a 32-entry table the mod unit steps through at the mod speed, " +
                    "moving a counter (−64..63) whose value × the depth bends the wave's pitch: vibrato at low speeds, FM at audio rates",
                "Table" to "FamiStudio's 32 targets for the counter (bars, $lr the entry, $edit+$ud raise / lower, $edit+$lr carry " +
                    "along, $clear: 0, $clear hold+$lr: jump $COARSE). The chip moves the counter only by 0, ±2, ±4 or ±8 per entry, " +
                    "or resets it (a target of 0), so FamiStudio picks the nearest step from where the counter is: the line shows " +
                    "what the chip plays",
                "Preset" to "$edit+$lr: Sine, Triangle, Sawtooth, Square 50%/25%, Flat (FamiStudio's; no PWM); $edit tap: the next; $clear: Flat",
                "Speed" to "0-4095 ($edit+$lr ±1, $edit+$ud ±$SPEED_COARSE; $clear: 0): the table runs at CPU × speed / 2^22 Hz " +
                    "(speed 12 ≈ 5.1 Hz). Every speed plays as itself here; FamiStudio 4.5.3's own .nsf export plays a speed whose " +
                    "low byte is \$80-\$FF as auto-mod",
                "Depth" to "0-63 ($edit+$lr ±1, $edit+$ud ±$COARSE; $clear: 0): how far the counter bends the pitch; 0 turns modulation off",
                "Delay" to "Frames after the note starts before modulation runs, 0-255. FamiStudio's engine turns it on in frame d, " +
                    "so delay d holds it off for d − 1 frames (0 and 1 start at once)",
                "Auto-mod" to "On: every frame the speed is the note's frequency × numerator / denominator (1-32 each, reduced), so the " +
                    "modulation follows the pitch (FM at a fixed ratio); the speed row is then unused. $edit tap or $edit+$lr: on / off. " +
                    "Large numerators overflow the engine's 16-bit multiply on high notes (the row and the check say from which note)",
            ),
        )
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
