package org.thortracker.system.scc

import org.thortracker.model.Cell
import org.thortracker.model.Song

/**
 * The editor's longer reading of an SCC effect where its row matters (`SoundSystem.effectDetail`): the notes an
 * arpeggio (`0xy`) steps through from the row's (or the sounding) note; what a wave (`Wxx`) changes, including the
 * other channel on the SCC's shared wave RAM; and every bit of a test register value (`Txx`).
 */
object SccEffectText {
    fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
        val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row) ?: return null
        val sound = song.sound as? SccSound ?: return null
        val code = cell.effectCode(column)
        val param = cell.effectParam(column)
        val shared = sound.variant.sharedWave && channel in SccSound.SHARED_CHANNELS
        return when (code) {
            SccRules.FX_ARPEGGIO -> {
                if (param == 0) return null
                val x = param shr 4
                val y = param and 0x0F
                val root = if (cell.note >= 0) cell.note else soundingNote(track, row) ?: return "note, +$x, +$y: no note sounding here"
                listOf(root, root + x, root + y).joinToString(" → ") { name(sound, it) } + " every tick"
            }
            SccRules.FX_WAVE -> {
                if (param !in 1..SccSound.WAVES) return null
                val wave = SccRules.waveLabel(song, param)
                val what = if (cell.note >= 0) "The note on this row plays $wave instead of its instrument's wave envelope"
                    else "The sounding note switches to $wave until its next note"
                val other = SccRules.CHANNEL_NAMES[if (channel == SccSound.SHARED_CHANNELS.first) SccSound.SHARED_CHANNELS.last else SccSound.SHARED_CHANNELS.first]
                if (shared) "$what; on the SCC this claims the wave RAM SCC 4 and SCC 5 share, so $other plays it too"
                else what
            }
            SccRules.FX_TEST -> {
                val meanings = SccRules.testMeanings(param, sound.variant)
                if (meanings.isEmpty()) "Test register 00: normal operation, every channel (clears earlier Txx bits)"
                else "Test register ${SccRules.effectText(code, param).substring(1)} for every channel: " + meanings.joinToString("; ")
            }
            else -> null
        }
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

    private fun name(sound: SccSound, note: Int): String =
        if (note in SccRules.NOTES) sound.system.noteName(note) else "(out of range)"
}
