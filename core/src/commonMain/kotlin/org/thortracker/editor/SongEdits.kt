package org.thortracker.editor

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Scale
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem

/** Pure, immutable song transformations used by [EditorSession] commands. */
internal fun <T> List<T>.replaced(index: Int, value: T): List<T> =
    if (this[index] == value) this else toMutableList().also { it[index] = value }

internal fun <T> List<T>.inserted(index: Int, value: T): List<T> = toMutableList().also { it.add(index, value) }

internal fun <T> List<T>.removed(index: Int): List<T> = toMutableList().also { it.removeAt(index) }

fun Song.channelCount(): Int = system.channels.size

/** Rows that play and are shown: the pattern's length; rows past it are hidden. */
fun Song.rowCount(pattern: Int): Int = patterns[pattern].length

fun Song.patternAt(order: Int): Int = orders[order.coerceIn(0, orders.size - 1)]

fun Song.cellAt(pattern: Int, channel: Int, row: Int): Cell = patterns[pattern].tracks[channel][row]

/** Number of order entries that reference [pattern]. */
fun Song.patternUses(pattern: Int): Int = orders.count { it == pattern }

internal fun Pattern.withCell(channel: Int, row: Int, cell: Cell): Pattern {
    val track = tracks[channel]
    if (track[row] == cell) return this
    return copy(tracks = tracks.replaced(channel, track.replaced(row, cell)))
}

internal fun Song.withPattern(index: Int, pattern: Pattern): Song =
    if (patterns[index] == pattern) this else copy(patterns = patterns.replaced(index, pattern))

/**
 * A cell normalised to what [system] can represent: notes within its range, sound numbers within its largest bank, a
 * cut, key on or empty note carrying no instrument; effects within the system's codes and parameters within a byte,
 * in at most the effect columns [channel] has ([SoundSystem.effectColumns]; every channel's most when -1).
 */
fun normaliseCell(cell: Cell, system: SoundSystem, channel: Int = -1): Cell {
    val note = when {
        cell.note == Cell.CUT -> Cell.CUT
        cell.note == system.keyOn -> cell.note
        cell.note < 0 -> Cell.EMPTY
        else -> cell.note.coerceIn(system.noteRange)
    }
    val instrument = if (note < 0) 0 else cell.instrument.coerceIn(0, system.banks.maxOf { it.slots })
    val maxCode = system.effects.maxOf { it.code }
    var out = Cell(note, instrument, cell.effect.coerceIn(0, maxCode), cell.param.coerceIn(0, 255))
    if (cell.more.isNotEmpty()) {
        val columns = if (channel in system.channels.indices) system.effectColumns(channel) else system.channels.indices.maxOf { system.effectColumns(it) }
        for (col in 1 until columns) out = out.withEffect(col, cell.effectCode(col).coerceIn(0, maxCode), cell.effectParam(col).coerceIn(0, 255))
    }
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
 * (its played rows) also carries its editor-only [scale], which only a whole-pattern paste
 * ([EditorSession.pastePattern]) applies along with the clip's height as the pattern length. Systems add their own
 * clips (sounds, wave tables).
 */
interface Clip {
    data class Cells(val tracks: List<List<Cell>>, val scale: Scale? = null) : Clip {
        val width: Int get() = tracks.size
        val height: Int get() = tracks.firstOrNull()?.size ?: 0
    }
}
