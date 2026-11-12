package org.thortracker.system.gba

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs

/** Original four-phrase study, synthesized samples only; no game audio or borrowed melodies. */
object GbaDemoSong {
    fun create(): Song {
        val roots = listOf(36, 41, 33, 38)
        val melody = listOf(0, 7, 12, 10, 7, 3, 5, 2, 0, 3, 7, 14, 12, 10, 7, 5)
        val patterns = roots.mapIndexed { phrase, root ->
            val tracks = List(8) { MutableList(Songs.ROWS) { Cell.BLANK } }
            fun put(ch: Int, row: Int, note: Int, sound: Int, effect: Int = 0, param: Int = 0) {
                tracks[ch][row] = Cell(note, sound, effect, param)
            }
            for (step in 0 until 16) {
                val row = step * 4
                put(0, row, root + 12 + melody[(step + phrase * 3) % melody.size], 1, 12, 32)
                put(0, row + 3, Cell.CUT, 0)
                if (step % 2 == 0) put(1, row, root + if (step % 4 == 0) 0 else 7, 2, 12, 44)
                if (step % 4 == 0) {
                    put(2, row, root + 24, 3, 8, 64)
                    put(3, row + 2, root + 31, 3, 8, 192)
                    put(4, row, 48, 4)
                }
                if (step % 4 == 2) put(5, row, 48, 5)
                put(6, row, 48, 6, 12, if (step % 2 == 0) 30 else 20)
                if (step % 4 == 3) put(7, row + 2, root + 36, 3, 12, 14)
            }
            Pattern("Prism ${phrase + 1}", tracks.map { it.toList() })
        }
        return Song(
            title = "Prism Circuit", artist = "Thor Tracker",
            comment = "Original GBA/Maxmod demo and procedural PCM. Eight sample lanes; XM key-off, volume and stereo pan.",
            ticksPerRow = 6, orders = patterns.indices.toList(), patterns = patterns,
            sound = GbaSamples.starterBank().copy(bpm = 128),
        )
    }
}
