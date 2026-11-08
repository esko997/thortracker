package org.thortracker.system.sid

import org.thortracker.model.Song

/**
 * The speedtable a song needs: one row per distinct value (left shl 8 or right) that instrument vibratos, wave program
 * commands 1-4 and pattern effects 1-4 / E use ([SidRules.speedOf]). Rows are numbered in a fixed walk — instruments
 * by slot, then their wave programs, then every pattern (played or not, in pattern order) by voice and row — so a
 * value keeps its row unless a new value appears earlier in that walk; the player re-reads a voice's speed row at each
 * row start, so a renumbering is heard at most until the next row.
 */
object SidSpeeds {
    /** Distinct speed values of [song] in row order (row = index + 1). */
    fun of(song: Song): List<Int> {
        val rows = LinkedHashSet<Int>()
        for (ins in song.sid.instruments) vibratoValue(ins)?.let { rows += it }
        for (ins in song.sid.instruments) for (r in ins.wave.rows) {
            val c = r.left - SidTables.WAVE_COMMAND
            if (r.left in SidTables.WAVE_COMMAND until SidTables.JUMP) SidRules.speedOf(c, r.right).takeIf { it >= 0 }?.let { rows += it }
        }
        for (pattern in song.patterns) for (track in pattern.tracks) for (row in 0 until minOf(pattern.length, track.size)) {
            val cell = track[row]
            if (cell.hasEffect) SidRules.speedOf(cell.effect, cell.param).takeIf { it >= 0 }?.let { rows += it }
        }
        return rows.toList()
    }

    /** The speedtable value of [ins]'s vibrato, or null without one. */
    fun vibratoValue(ins: SidInstrument): Int? {
        val v = ins.vibrato
        if (!v.active) return null
        return ((v.speed or if (v.noteIndependent) 0x80 else 0) shl 8) or v.depth
    }
}
