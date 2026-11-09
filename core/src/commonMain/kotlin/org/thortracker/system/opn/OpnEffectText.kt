package org.thortracker.system.opn

import org.thortracker.model.Cell
import org.thortracker.model.Song

/**
 * The editor's longer reading of an OPN-family effect where its row matters (`SoundSystem.effectDetail`): the notes an
 * arpeggio (`0xy`) steps through from the row's (or the sounding) note, and why a lock (`E`, `I`, `M` on FM; `H`, `J`
 * on the SSG) on a row without a note does nothing.
 */
object OpnEffectText {
    fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
        val chip = (song.sound as? OpnSound)?.chip ?: return null
        if (channel !in 0 until chip.channelCount) return null
        val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row) ?: return null
        val code = cell.effectCode(column)
        val param = cell.effectParam(column)
        if (code in OpnRules.NOTE_EFFECTS && cell.note < 0) {
            return "${OpnRules.describe(chip, channel, code, param)}: ignored, it applies when a note starts on its row"
        }
        if (code != OpnRules.FX_ARPEGGIO || param == 0 || chip.part(channel) == OpnPart.RHYTHM) return null
        val x = param shr 4
        val y = param and 0x0F
        val root = if (cell.note >= 0) cell.note else soundingNote(track, row) ?: return "note, +$x, +$y: no note sounding here"
        return listOf(root, root + x, root + y).joinToString(" → ") { name(chip, it) } + " every tick"
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

    private fun name(chip: OpnChip, note: Int): String = if (note in OpnRules.NOTES) chip.system().noteName(note) else "(out of range)"
}
