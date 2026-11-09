package org.thortracker.system.scc

import org.thortracker.backend.SccLowering
import org.thortracker.backend.SccPlayerImage
import org.thortracker.model.Song

/**
 * The GD3 tag the native VGM writer adds: track [title], [author] and release [date] "yyyy/mm/dd" (empty: the native
 * writer uses today's date).
 */
data class SccVgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a VGM 1.71 file from an SCC or SCC+ image with the native player (`sc_export_vgm`: `sc_vgm_render` plus the
 * GD3 tag, in `scc/scc_vgm.c`): one player implementation for playback and export, proven against the Python reference
 * by the native host test. [image] is [SccPlayerImage.IMAGE_SIZE] bytes from the system's engine (its variant byte picks
 * the K051649 or K052539 header); rendering stops at the second arrival at the loop point or after [maxTicks] ticks
 * (then without a loop).
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface SccVgmRenderer {
    fun render(image: ByteArray, maxTicks: Int, tags: SccVgmTags): ByteArray
}

/**
 * The VGM export seam. Core has no native code, so each host registers the native renderer at startup: Android
 * through JNI (`audio_jni.cpp`), the Deck through cinterop. JVM unit tests run without it (the native host test
 * covers the renderer's bytes against the reference; `tools/audio/render_scc_image.sh --vgm` renders an image
 * from the command line).
 */
object SccVgm {
    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: SccVgmRenderer? = null

    /** Ten minutes of ticks at the song's multispeed: a song that never returns to its loop point ends there. */
    fun maxTicks(song: Song): Int = 60 * song.scc.multispeed * 600

    /**
     * [song] (SCC or SCC+) as a VGM file.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song): ByteArray {
        val r = renderer ?: throw IllegalStateException("VGM export needs the native SCC player, which this build does not include")
        val image = ByteArray(SccPlayerImage.IMAGE_SIZE)
        SccLowering.lower(song, image)
        return r.render(image, maxTicks(song), SccVgmTags(song.title, song.artist))
    }
}
