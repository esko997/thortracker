package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.DpcmSample
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.Vrc7Sustain
import org.thortracker.system.nes.nes

/**
 * One row of one channel in FamiStudio's "simple note" model (what FamiStudio's exporter works on after
 * `ConvertToSimpleNotes`): a note value plus the note's effect attributes. -1 / null = attribute not set.
 */
internal class FsNote {
    /** [INVALID], [STOP], [RELEASE] or a musical FamiStudio note 1..96. */
    var value = INVALID

    /** Instrument slot 1..64 (index slot-1 in the bank), 0 = none. Only on musical notes. */
    var instrument = 0
    var attack = true

    /** 0xy as FamiStudio arpeggio (x shl 4 or y), -1 = none. Only on musical notes. */
    var arpeggio = -1

    /** Slide note target (FamiStudio note), 0 = not a slide note. */
    var slideTarget = 0
    var volume = -1
    var volumeSlideTarget = -1

    /** Raw FamiStudio vibrato byte (speed shl 4 or depth); 0 = off. */
    var vibrato = -1
    var speed = -1

    /** FamiStudio fine pitch (period units, positive = lower), [NO_FINE] = none. */
    var finePitch = NO_FINE
    var duty = -1
    var noteDelay = -1
    var cutDelay = -1

    /** DPCM `Zxx`: DMC output level 0..127, -1 = none. */
    var deltaCounter = -1

    /**
     * The 5B's or the EPSM SSG's envelope period (FamiStudio's `EnvelopePeriod`, 0..65535), -1 = none. Only on the first
     * SSG square: a row's `Ixx`/`Jyy` on any of its squares land here, as FamiStudio's exporter moves every period effect
     * to the first square.
     */
    var envelopePeriod = -1

    /** N163 and FDS `X00`: FamiStudio's phase reset (the wave restarts from its first sample). */
    var phaseReset = false

    /**
     * FDS `Ixx`/`Jxx`: FamiStudio's `FdsModSpeed` note attribute (0..4095), the whole 12-bit speed: the half the cell does
     * not set is the speed the channel has at that row along the played order (see [FsSong.of]). -1 = none.
     */
    var fdsModSpeed = -1

    /** FDS `Hxx`: FamiStudio's `FdsModDepth` note attribute (0..63), -1 = none. */
    var fdsModDepth = -1

    val isMusical get() = value in 1..96
    val isValid get() = value != INVALID
    val hasVolume get() = volume >= 0

    /** Any attribute that keeps FamiStudio's exporter from folding the row into an empty run. */
    val hasRunBreakingEffect
        get() = hasVolume || vibrato >= 0 || finePitch != NO_FINE || duty >= 0 || noteDelay >= 0 || cutDelay >= 0 || deltaCounter >= 0 ||
            phaseReset || envelopePeriod >= 0 || fdsModSpeed >= 0 || fdsModDepth >= 0

    val isEmpty get() = !isValid && !hasRunBreakingEffect && speed < 0

    companion object {
        const val INVALID = -1
        const val STOP = 0
        const val RELEASE = -2
        const val NO_FINE = Int.MIN_VALUE
    }
}

/**
 * A song as FamiStudio sees it: the arrangement unrolled into [rows] (absolute row index over the played orders,
 * channel-major), with every Thor Tracker cell turned into FamiStudio's note attributes. Both the lowering
 * ([FamiStudioLowering]) and the text export ([FamiStudioText]) read this, so they describe the same song.
 */
internal class FsSong(
    val title: String,
    val artist: String,
    val speed: Int,
    val loopOrder: Int,
    /** Rows of each played order (its pattern's length). */
    val lengths: IntArray,
    /** Pattern index (in the source song) each order plays, for names. */
    val patternOfOrder: IntArray,
    val patternNames: List<String>,
    /** `notes[channel][absoluteRow]`, null = empty. */
    val notes: Array<Array<FsNote?>>,
    val instruments: List<NesInstrument>,
    val samples: List<DpcmSample>,
    /** The song's expansion chip: its channels follow the 2A03's five in [notes]. */
    val expansion: NesExpansion = NesExpansion.NONE,
    /** VRC7 songs: the release/stop sustain overrides every VRC7 instrument carries. */
    val vrc7Sustain: Vrc7Sustain = Vrc7Sustain(),
    /** EPSM songs: the chip LFO register `$22` every EPSM instrument's patch carries ([org.thortracker.system.nes.NesSound.epsmLfo]). */
    val epsmLfo: Int = 0,
) {
    val orderStart = IntArray(lengths.size + 1).also { for (i in lengths.indices) it[i + 1] = it[i] + lengths[i] }
    val totalRows get() = orderStart[lengths.size]
    val orders get() = lengths.size
    val channels get() = notes.size

    /** The player build that plays the song. */
    val player: NesPlayer get() = expansion.player

    fun note(ch: Int, row: Int): FsNote? = if (row in 0 until totalRows) notes[ch][row] else null

    /** Speed set at absolute [row] by any channel (FamiStudio's `ApplySpeedEffectAt`), or -1. */
    fun speedAt(row: Int): Int {
        for (ch in notes.indices) notes[ch][row]?.let { if (it.speed >= 0) return it.speed }
        return -1
    }

    companion object {
        /**
         * The song as FamiStudio plays it: [song]'s arrangement ([NesSystem.arranged]) with each cell's meaning
         * (see [NesRules.EFFECTS]) as FamiStudio note attributes. Context the cells leave implicit is resolved along
         * the played order here, so both consumers agree:
         * - a note without an instrument is legato (no attack) on the channel's current instrument;
         * - OFF releases a sounding note whose instrument has a volume release point (on the N163's channels and the FDS
         *   also one whose wave sequence has a release point), and on the VRC7's and the EPSM's FM channels any sounding
         *   note (key off: FamiStudio's `CanRelease`), otherwise (or when the note was already released) it stops the
         *   channel; on the EPSM's drums it always stops (FamiStudio's engine has no volume envelope slot for them, so its
         *   release opcode there would act on another channel's envelope);
         * - fine pitch (Pxx) belongs to the playing note: the next note without its own Pxx resets it to none; on the
         *   VRC7's and the EPSM's FM, the N163's channels and the FDS it is converted to the frequency step nearest the
         *   same pitch ([NesRules.vrc7FinePitch], [NesRules.epsmFmFinePitch], [NesRules.n163FinePitch],
         *   [NesRules.fdsFinePitch]), on the 5B and EPSM SSG squares to their own periods ([NesRules.s5bFinePitch],
         *   [NesRules.epsmSsgFinePitch]);
         * - a row's 5B or EPSM SSG envelope period (`Ixx` high byte, `Jyy` low byte, on any SSG squares:
         *   [NesRules.envPeriod]) is one `EnvelopePeriod` on the first square;
         * - FDS `Ixx`/`Jxx` set one half of FamiStudio's 12-bit `FdsModSpeed`; the other half is the speed the channel has
         *   at that row: the sound's own Mod speed once a note with an attack loads it (an auto-mod sound leaves the
         *   engine's speed as it was: `famistudio_set_fds_instrument` skips it), else the last I/J value;
         * - the MMC5's software PCM lane is not one of the engine's channels ([NesExpansion.engineChannels]: [notes] holds
         *   the seven engine channels) and is lowered separately ([NesPcmLowering]); its `Fxx` is a song speed like any
         *   lane's, so a row whose engine channels set no speed takes the PCM lane's on Pulse 1 (as the song's first speed
         *   is placed): the engine, which times the PCM lane's rows too, and both exports get it, and the first lane in
         *   order still wins a row where several set one.
         * Requires a song without [NesSystem.errors].
         */
        fun of(document: Song): FsSong {
            val song = NesSystem.arranged(document)
            val instruments = song.nes.instruments
            val lengths = IntArray(song.orders.size) { song.patterns[song.orders[it]].length }
            val total = lengths.sum()
            val expansion = song.nes.expansion
            val channels = expansion.engineChannels
            val notes = Array(channels) { arrayOfNulls<FsNote>(total) }
            for (ch in 0 until channels) {
                val fds = NesRules.isFds(expansion, ch)
                var current = 0 // instrument slot sounding on the channel
                var sounding = false // a note is playing and has not been released or stopped
                var fine = 0
                var playing = 0 // engine note of the last note (the pitch a Pxx without note detunes)
                var modSpeed = 0 // FDS: the engine's mod speed (famistudio_fds_mod_speed starts at 0)
                var row = 0
                for (o in song.orders.indices) {
                    val pattern = song.patterns[song.orders[o]]
                    for (r in 0 until pattern.length) {
                        val cell = pattern.tracks[ch][r]
                        if (cell.note >= 0) playing = NesRules.engineNote(expansion, ch, cell.note)
                        if (fds && cell.note >= 0 && cell.instrument != 0) {
                            instruments.getOrNull(cell.instrument - 1)?.fds?.let { if (!it.autoMod) modSpeed = it.modSpeed }
                        }
                        val n = convert(cell, ch, expansion, current, sounding, instruments, fine, playing, modSpeed)
                        if (n != null && n.fdsModSpeed >= 0) modSpeed = n.fdsModSpeed
                        if (n != null) {
                            if (n.isMusical) {
                                if (n.instrument != 0) current = n.instrument
                                sounding = true
                            } else if (n.value == FsNote.STOP || n.value == FsNote.RELEASE) {
                                sounding = false
                            }
                            if (n.finePitch != FsNote.NO_FINE) fine = n.finePitch
                            if (n.cutDelay >= 0) sounding = false
                        }
                        notes[ch][row++] = n
                    }
                }
            }
            val squares = NesRules.ssgChannels(expansion)
            if (!squares.isEmpty()) {
                // FamiStudio keeps every 5B/EPSM envelope period effect on the first square (`MoveAllEnvelopePeriodsOnFirstChannel`).
                var row = 0
                for (o in song.orders.indices) {
                    val pattern = song.patterns[song.orders[o]]
                    for (r in 0 until pattern.length) {
                        val period = NesRules.envPeriod(squares.map { pattern.tracks[it][r] }) ?: -1
                        if (period >= 0) {
                            val ch = squares.first
                            notes[ch][row] = (notes[ch][row] ?: FsNote()).also { it.envelopePeriod = period }
                        }
                        row++
                    }
                }
            }
            if (expansion.hasPcmLane) {
                var row = 0
                for (o in song.orders.indices) {
                    val pattern = song.patterns[song.orders[o]]
                    for (r in 0 until pattern.length) {
                        val cell = pattern.tracks[NesRules.MMC5_PCM][r]
                        if (cell.hasEffect && cell.effect == NesRules.FX_SPEED && (0 until channels).none { (notes[it][row]?.speed ?: -1) >= 0 }) {
                            notes[0][row] = (notes[0][row] ?: FsNote()).also { it.speed = cell.param }
                        }
                        row++
                    }
                }
            }
            // The song speed is set again at the first row (a loop to order 0 starts at the song speed, and the engine
            // starts at 6); FamiStudio's text export says the same, so both lowerings pick the same speed channel.
            if ((0 until channels).none { (notes[it][0]?.speed ?: -1) >= 0 }) {
                notes[0][0] = (notes[0][0] ?: FsNote()).also { it.speed = song.ticksPerRow }
            }
            return FsSong(song.title, song.artist, song.ticksPerRow, song.loopOrder, lengths,
                IntArray(song.orders.size) { song.orders[it] }, song.patterns.map { it.name }, notes, instruments, song.nes.samples,
                song.nes.expansion, song.nes.vrc7Sustain, song.nes.epsmLfo)
        }

        private fun convert(cell: Cell, ch: Int, expansion: NesExpansion, current: Int, sounding: Boolean, bank: List<NesInstrument>, fine: Int,
                            playing: Int, modSpeed: Int): FsNote? {
            if (cell.isBlank) return null
            val vrc7 = NesRules.isVrc7(expansion, ch)
            val n163 = NesRules.isN163(expansion, ch)
            val s5b = NesRules.isS5b(expansion, ch)
            val fds = NesRules.isFds(expansion, ch)
            val epsmFm = NesRules.isEpsmFm(expansion, ch)
            val epsmSsg = NesRules.isEpsmSsg(expansion, ch)
            val n = FsNote()
            val p = cell.param
            when {
                cell.note >= 0 -> {
                    // FamiStudio's engine note for the true pitch ([NesRules.engineNoteShift]: VRC7, 5B, FDS, EPSM SSG/FM).
                    n.value = NesRules.engineNote(expansion, ch, cell.note)
                    if (cell.instrument != 0) n.instrument = cell.instrument
                    else if (current != 0) {
                        n.instrument = current
                        n.attack = false
                    }
                    if (fine != 0 && !(cell.effect == NesRules.FX_FINE_PITCH && cell.hasEffect)) n.finePitch = 0
                }
                cell.note == Cell.CUT -> {
                    // DPCM has no release: OFF stops the sample; nor have the EPSM drums (see above).
                    val releases = ch != NesRules.DPCM && !NesRules.isEpsmRhythm(expansion, ch) && sounding && current != 0 &&
                        (vrc7 || epsmFm || bank[current - 1].let {
                            it.volume.release >= 0 && it.volume.loop >= 0 || n163 && it.wave.release >= 0 && it.wave.loop >= 0 ||
                                fds && it.fds.release >= 0 && it.fds.loop >= 0
                        })
                    n.value = if (releases) FsNote.RELEASE else FsNote.STOP
                }
            }
            if (!cell.hasEffect) return n
            when (cell.effect) {
                NesRules.FX_ARPEGGIO -> n.arpeggio = p
                NesRules.FX_VIBRATO -> n.vibrato = p
                NesRules.FX_VOLUME_SLIDE -> {
                    n.volume = p shr 4
                    n.volumeSlideTarget = p and 0xF
                }
                NesRules.FX_VOLUME -> n.volume = p
                NesRules.FX_SPEED -> n.speed = p
                NesRules.FX_NOTE_DELAY -> n.noteDelay = p
                NesRules.FX_FINE_PITCH -> n.finePitch = when {
                    vrc7 -> NesRules.vrc7FinePitch(playing, p - NesRules.FINE_CENTRE)
                    n163 -> NesRules.n163FinePitch(playing, p - NesRules.FINE_CENTRE, expansion.n163Channels)
                    fds -> NesRules.fdsFinePitch(playing, p - NesRules.FINE_CENTRE)
                    s5b -> -NesRules.s5bFinePitch(playing, p - NesRules.FINE_CENTRE)
                    epsmFm -> NesRules.epsmFmFinePitch(playing, p - NesRules.FINE_CENTRE)
                    epsmSsg -> -NesRules.epsmSsgFinePitch(playing, p - NesRules.FINE_CENTRE)
                    else -> NesRules.FINE_CENTRE - p
                }
                NesRules.FX_SLIDE_UP -> n.slideTarget = n.value + p
                NesRules.FX_SLIDE_DOWN -> n.slideTarget = n.value - p
                NesRules.FX_NOTE_CUT -> n.cutDelay = p
                NesRules.FX_DUTY -> n.duty = p
                NesRules.FX_DELTA_COUNTER -> n.deltaCounter = p
                NesRules.FX_PHASE_RESET -> n.phaseReset = true
                // I/J: the FDS's mod speed here; the 5B/EPSM envelope period is gathered per row on the first square by [of].
                NesRules.FX_FDS_MOD_DEPTH -> if (fds) n.fdsModDepth = p
                NesRules.FX_FDS_MOD_SPEED_HI -> if (fds) n.fdsModSpeed = ((p and 0xF) shl 8) or (modSpeed and 0xFF)
                NesRules.FX_FDS_MOD_SPEED_LO -> if (fds) n.fdsModSpeed = (modSpeed and 0xF00) or p
            }
            // On Noise FamiStudio has no pitch effects; the rules reject them, nothing to translate.
            if (ch == NesRules.NOISE) n.finePitch = FsNote.NO_FINE
            return n
        }
    }
}
