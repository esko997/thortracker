package org.thortracker.system.opm

import org.thortracker.backend.OpmLowering
import org.thortracker.backend.OpmPlayerImage
import org.thortracker.model.Song

/**
 * The GD3 tag the native VGM writer adds: track [title], [author] and release [date] "yyyy/mm/dd" (empty: the native
 * writer uses today's date).
 */
data class OpmVgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a VGM 1.71 file for one YM2151 at 4 MHz from an OPM image with the native player (`om_vgm_render` plus the
 * GD3 tag, in `opm/opm_vgm.c`): one player implementation for playback and export, checked against the Python
 * reference by the native host test. [image] is [OpmPlayerImage.IMAGE_SIZE] bytes from [OpmSystem.engine]; rendering
 * stops at the second arrival at the loop point or after [maxTicks] ticks (then without a loop).
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface OpmVgmRenderer {
    fun render(image: ByteArray, maxTicks: Int, tags: OpmVgmTags): ByteArray
}

/**
 * The VGM export seam. Core has no native code, so each host registers the native renderer at startup: Android
 * through JNI (`audio_jni.cpp`), the Deck through cinterop. JVM unit tests run without it (the native host test
 * covers the renderer's bytes against the reference; `tools/audio/render_opm_image.sh --vgm` renders an image
 * from the command line).
 */
object OpmVgm {
    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: OpmVgmRenderer? = null

    /** Ten minutes of ticks at the song's multispeed: a song that never returns to its loop point ends there. */
    fun maxTicks(song: Song): Int = 60 * song.opm.multispeed * 600

    /**
     * [song] as a VGM file.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song): ByteArray {
        val r = renderer ?: throw IllegalStateException("VGM export needs the native OPM player, which this build does not include")
        val image = ByteArray(OpmPlayerImage.IMAGE_SIZE)
        OpmLowering.lower(song, image)
        return r.render(image, maxTicks(song), OpmVgmTags(song.title, song.artist))
    }
}
