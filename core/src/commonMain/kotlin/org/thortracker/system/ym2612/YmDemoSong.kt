package org.thortracker.system.ym2612

import org.thortracker.model.Song

/** The bundled Mega Drive demo. */
internal object YmDemoSong {
    fun build(): Song = YmSystem.blank().copy(title = "Mega Drive demo", artist = "Thor Tracker")
}
