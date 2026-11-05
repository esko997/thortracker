package org.thortracker.editor

import org.thortracker.model.Cell
import org.thortracker.model.Instrument
import org.thortracker.model.InstrumentKind
import org.thortracker.model.Pattern
import org.thortracker.model.Scale
import org.thortracker.model.Song
import org.thortracker.model.Songs

/** Pure, immutable song transformations used by [EditorSession] commands. */
internal fun <T> List<T>.replaced(index: Int, value: T): List<T> =
    if (this[index] == value) this else toMutableList().also { it[index] = value }

internal fun <T> List<T>.inserted(index: Int, value: T): List<T> = toMutableList().also { it.add(index, value) }

internal fun <T> List<T>.removed(index: Int): List<T> = toMutableList().also { it.removeAt(index) }

fun Song.channelCount(): Int = Songs.channelNames(this).size

fun Song.rowCount(pattern: Int): Int = patterns[pattern].tracks.firstOrNull()?.size ?: Songs.ROWS

fun Song.patternAt(order: Int): Int = orders[order.coerceIn(0, orders.size - 1)]

fun Song.cellAt(pattern: Int, channel: Int, row: Int): Cell = patterns[pattern].tracks[channel][row]

fun Song.bank(kind: InstrumentKind): List<Instrument> = when (kind) {
    InstrumentKind.PULSE -> pulseInstruments
    InstrumentKind.WAVE -> waveInstruments
    InstrumentKind.NOISE -> noiseInstruments
}

fun Song.withBank(kind: InstrumentKind, list: List<Instrument>): Song = when (kind) {
    InstrumentKind.PULSE -> copy(pulseInstruments = list)
    InstrumentKind.WAVE -> copy(waveInstruments = list)
    InstrumentKind.NOISE -> copy(noiseInstruments = list)
}

/** Instrument referenced by slot 1..15 in [kind]'s bank, or null for slot 0 / out of range. */
fun Song.instrument(kind: InstrumentKind, slot: Int): Instrument? = bank(kind).getOrNull(slot - 1)

/** Number of order entries that reference [pattern]. */
fun Song.patternUses(pattern: Int): Int = orders.count { it == pattern }

/** First channel whose instrument bank has [kind], or -1. */
fun Song.firstChannelOf(kind: InstrumentKind): Int =
    (0 until channelCount()).firstOrNull { Songs.channelKind(this, it) == kind } ?: -1

internal fun Pattern.withCell(channel: Int, row: Int, cell: Cell): Pattern {
    val track = tracks[channel]
    if (track[row] == cell) return this
    return copy(tracks = tracks.replaced(channel, track.replaced(row, cell)))
}

internal fun Song.withPattern(index: Int, pattern: Pattern): Song =
    if (patterns[index] == pattern) this else copy(patterns = patterns.replaced(index, pattern))

/** A cell normalised to what the GB/hUGE backend can represent: a cut or empty note carries no instrument. */
fun normaliseCell(cell: Cell): Cell {
    val note = when {
        cell.note == Cell.CUT -> Cell.CUT
        cell.note < 0 -> Cell.EMPTY
        else -> cell.note.coerceIn(0, Songs.NOTE_MAX)
    }
    val instrument = if (note < 0) 0 else cell.instrument.coerceIn(0, 15)
    val effect = cell.effect.coerceIn(0, 15)
    val param = cell.param.coerceIn(0, 255)
    val out = Cell(note, instrument, effect, param)
    return if (out == cell) cell else out
}

/** Rectangle of cells inside one pattern; bounds are inclusive. */
data class Block(val firstChannel: Int, val lastChannel: Int, val firstRow: Int, val lastRow: Int) {
    val width: Int get() = lastChannel - firstChannel + 1
    val height: Int get() = lastRow - firstRow + 1
    fun contains(channel: Int, row: Int) = channel in firstChannel..lastChannel && row in firstRow..lastRow

    companion object {
        fun of(c0: Int, r0: Int, c1: Int, r1: Int) = Block(minOf(c0, c1), maxOf(c0, c1), minOf(r0, r1), maxOf(r0, r1))
    }
}

/**
 * Shared clipboard contents. Cell clips are channel-major, like [Pattern.tracks]; a whole copied pattern
 * also carries its editor-only [scale], which only a whole-pattern paste ([EditorSession.pastePattern]) applies.
 */
sealed interface Clip {
    data class Cells(val tracks: List<List<Cell>>, val scale: Scale? = null) : Clip {
        val width: Int get() = tracks.size
        val height: Int get() = tracks.firstOrNull()?.size ?: 0
    }
    data class Sound(val instrument: Instrument) : Clip
    data class Wave(val samples: List<Int>) : Clip
}

/** Generated GB wave shapes: real 32-sample, 4-bit tables. */
object WaveShapes {
    const val SAMPLES = 32
    const val MAX = 15

    fun sine(): List<Int> = List(SAMPLES) { i ->
        Math.round(7.5 + 7.5 * Math.sin(2.0 * Math.PI * i / SAMPLES)).toInt().coerceIn(0, MAX)
    }

    fun triangle(): List<Int> = List(SAMPLES) { i -> if (i < 16) i else 31 - i }

    fun saw(): List<Int> = List(SAMPLES) { i -> i / 2 }

    fun square(): List<Int> = List(SAMPLES) { i -> if (i < 16) MAX else 0 }

    fun invert(w: List<Int>): List<Int> = w.map { MAX - it }

    fun rotate(w: List<Int>, by: Int): List<Int> = List(w.size) { i -> w[Math.floorMod(i - by, w.size)] }

    /** Three-tap circular smoothing, rounded. */
    fun smooth(w: List<Int>): List<Int> = List(w.size) { i ->
        val a = w[Math.floorMod(i - 1, w.size)]
        val b = w[i]
        val c = w[(i + 1) % w.size]
        Math.round((a + 2 * b + c) / 4.0).toInt().coerceIn(0, MAX)
    }
}
