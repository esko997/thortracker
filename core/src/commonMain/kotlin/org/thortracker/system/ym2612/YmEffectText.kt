package org.thortracker.system.ym2612

import org.thortracker.model.Cell
import org.thortracker.model.Song

/**
 * The editor's longer reading of a Mega Drive effect where its row matters (`SoundSystem.effectDetail`): the notes an
 * arpeggio (`0xy`) steps through from the row's (or the sounding) note, and why a lock (`E`, `I`, `M`, `N`) on a row
 * without a note does nothing.
 */
object YmEffectText {
    fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
        val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row) ?: return null
        val code = cell.effectCode(column)
        val param = cell.effectParam(column)
        if (code in YmRules.NOTE_EFFECTS && cell.note < 0) {
            return "${YmRules.describe(song, channel, code, param)}: ignored, it applies when a note starts on its row"
        }
        if (code != YmRules.FX_ARPEGGIO || param == 0) return null
        val x = param shr 4
        val y = param and 0x0F
        val root = if (cell.note >= 0) cell.note else soundingNote(track, row) ?: return "note, +$x, +$y: no note sounding here"
        return listOf(root, root + x, root + y).joinToString(" → ") { name(it) } + " every tick"
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

    private fun name(note: Int): String = if (note in YmRules.NOTES) YmSystem.noteName(note) else "(out of range)"
}
