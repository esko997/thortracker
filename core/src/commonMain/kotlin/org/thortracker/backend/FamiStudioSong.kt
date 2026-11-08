package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
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

    val isMusical get() = value in 1..96
    val isValid get() = value != INVALID
    val hasVolume get() = volume >= 0

    /** Any attribute that keeps FamiStudio's exporter from folding the row into an empty run. */
    val hasRunBreakingEffect
        get() = hasVolume || vibrato >= 0 || finePitch != NO_FINE || duty >= 0 || noteDelay >= 0 || cutDelay >= 0

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
) {
    val orderStart = IntArray(lengths.size + 1).also { for (i in lengths.indices) it[i + 1] = it[i] + lengths[i] }
    val totalRows get() = orderStart[lengths.size]
    val orders get() = lengths.size

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
         * - OFF releases a sounding note whose instrument has a volume release point, otherwise (or when the note was
         *   already released) it stops the channel;
         * - fine pitch (Pxx) belongs to the playing note: the next note without its own Pxx resets it to none.
         * Requires a song without [NesSystem.errors].
         */
        fun of(document: Song): FsSong {
            val song = NesSystem.arranged(document)
            val instruments = song.nes.instruments
            val lengths = IntArray(song.orders.size) { song.patterns[song.orders[it]].length }
            val total = lengths.sum()
            val channels = NesSystem.channels.size
            val notes = Array(channels) { arrayOfNulls<FsNote>(total) }
            for (ch in 0 until channels) {
                var current = 0 // instrument slot sounding on the channel
                var sounding = false // a note is playing and has not been released or stopped
                var fine = 0
                var row = 0
                for (o in song.orders.indices) {
                    val pattern = song.patterns[song.orders[o]]
                    for (r in 0 until pattern.length) {
                        val cell = pattern.tracks[ch][r]
                        val n = convert(cell, ch, current, sounding, instruments, fine)
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
            // The song speed is set again at the first row (a loop to order 0 starts at the song speed, and the engine
            // starts at 6); FamiStudio's text export says the same, so both lowerings pick the same speed channel.
            if ((0 until channels).none { (notes[it][0]?.speed ?: -1) >= 0 }) {
                notes[0][0] = (notes[0][0] ?: FsNote()).also { it.speed = song.ticksPerRow }
            }
            return FsSong(song.title, song.artist, song.ticksPerRow, song.loopOrder, lengths,
                IntArray(song.orders.size) { song.orders[it] }, song.patterns.map { it.name }, notes, instruments)
        }

        private fun convert(cell: Cell, ch: Int, current: Int, sounding: Boolean, bank: List<NesInstrument>, fine: Int): FsNote? {
            if (cell.isBlank) return null
            val n = FsNote()
            val p = cell.param
            when {
                cell.note >= 0 -> {
                    n.value = NesRules.fsNote(cell.note)
                    if (cell.instrument != 0) n.instrument = cell.instrument
                    else if (current != 0) {
                        n.instrument = current
                        n.attack = false
                    }
                    if (fine != 0 && !(cell.effect == NesRules.FX_FINE_PITCH && cell.hasEffect)) n.finePitch = 0
                }
                cell.note == Cell.CUT -> {
                    // DPCM has no release: OFF stops the sample.
                    val releases = ch != NesRules.DPCM && sounding && current != 0 &&
                        bank[current - 1].let { it.volume.release >= 0 && it.volume.loop >= 0 }
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
                NesRules.FX_FINE_PITCH -> n.finePitch = NesRules.FINE_CENTRE - p
                NesRules.FX_SLIDE_UP -> n.slideTarget = n.value + p
                NesRules.FX_SLIDE_DOWN -> n.slideTarget = n.value - p
                NesRules.FX_NOTE_CUT -> n.cutDelay = p
                NesRules.FX_DUTY -> n.duty = p
            }
            // On Noise FamiStudio has no pitch effects; the rules reject them, nothing to translate.
            if (ch == NesRules.NOISE) n.finePitch = FsNote.NO_FINE
            return n
        }
    }
}
