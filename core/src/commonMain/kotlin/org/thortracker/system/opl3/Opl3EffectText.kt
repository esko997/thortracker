package org.thortracker.system.opl3

import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.Cell
import org.thortracker.model.Song

/**
 * The editor's longer reading of an OPL3 effect where its row matters (`SoundSystem.effectDetail`): the notes a chord
 * (`Hxy`, `Kxx`) or arpeggio (`0xy`) sounds from the row's note, and why a chord would stay silent.
 */
object Opl3EffectText {
    fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
        val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row) ?: return null
        val code = cell.effectCode(column)
        val param = cell.effectParam(column)
        val x = param shr 4
        val y = param and 0x0F
        var label = ""
        val intervals = when (code) {
            Opl3Rules.FX_CHORD -> listOf(x, y).filter { it != 0 }
            Opl3Rules.FX_SHAPE -> song.opl3.chords.getOrNull(param - 1)?.also { label = if (it.name.isEmpty()) "" else "${it.name}: " }?.intervals ?: return null
            Opl3Rules.FX_ARPEGGIO -> if (param == 0) return null else listOf(x, y)
            else -> return null
        }
        if (code == Opl3Rules.FX_ARPEGGIO) {
            // An arpeggio also changes the sounding note's steps on a row without a note.
            val root = if (cell.note >= 0) cell.note else soundingNote(track, row) ?: return "note, +$x, +$y: no note sounding here"
            return (listOf(root) + intervals.map { root + it }).joinToString(" → ") { name(it) } + " every tick"
        }
        if (cell.note < 0) return "${label}ignored: chords sound with a note on their row"
        if (intervals.isEmpty()) return "no extra notes"
        val notes = "$label${name(cell.note)} + ${intervals.joinToString(" ") { name(cell.note + it) }}"
        val slot = if (cell.instrument > 0) cell.instrument else instrumentAbove(track, row)
        val fourOp = slot?.let { song.opl3.instrument(it) }?.fourOp == true
        return if (fourOp && slot != null) "$notes, silent: sound ${hex2(slot)} is 4-op (chords need 2-op sounds)"
            else "$notes on ${intervals.size} spare voice${if (intervals.size == 1) "" else "s"}"
    }

    /** The note sounding at [row] of [track] from the rows above it (the pattern only), or null after an OFF or none. */
    private fun soundingNote(track: List<Cell>, row: Int): Int? {
        for (r in row - 1 downTo 0) {
            val n = track[r].note
            if (n >= 0) return n
            if (n == Cell.CUT) return null
        }
        return null
    }

    /** The last sound number written above [row] in the pattern, or null. */
    private fun instrumentAbove(track: List<Cell>, row: Int): Int? = (row - 1 downTo 0).firstNotNullOfOrNull { r -> track[r].instrument.takeIf { it > 0 } }

    private fun name(note: Int): String = if (note in Opl3Rules.NOTES) Opl3System.noteName(note) else "(out of range)"
}
