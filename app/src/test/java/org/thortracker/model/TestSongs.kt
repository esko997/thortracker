package org.thortracker.model

/** Small helpers for building test songs from [Songs.blank]. */
internal object TestSongs {
    fun withCell(song: Song, pattern: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.patterns[pattern]
        val track = p.tracks[channel].toMutableList().also { it[row] = cell }
        val tracks = p.tracks.toMutableList().also { it[channel] = track }
        val patterns = song.patterns.toMutableList().also { it[pattern] = p.copy(tracks = tracks) }
        return song.copy(patterns = patterns)
    }

    fun withPulse(song: Song, slot: Int, instrument: Instrument): Song =
        song.copy(pulseInstruments = song.pulseInstruments.toMutableList().also { it[slot - 1] = instrument })

    /** Blank song with [count] blank patterns, orders 0..count-1. */
    fun patterns(count: Int): Song {
        val blank = Songs.blank()
        return blank.copy(
            patterns = List(count) { Songs.blankPattern(blank, "P$it") },
            orders = List(count) { it },
        )
    }
}
