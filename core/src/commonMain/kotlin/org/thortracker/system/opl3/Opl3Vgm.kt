package org.thortracker.system.opl3

import org.thortracker.backend.Opl3Lowering
import org.thortracker.model.Song

/**
 * The GD3 tag the native VGM writer adds: track [title], [author] and release [date] "yyyy/mm/dd" (empty: the native
 * writer uses today's date).
 */
data class Opl3VgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a VGM 1.71 file from an OPL3 image with the native player (`o3_vgm_render` plus the GD3 tag, in
 * `opl3/opl3_vgm.c`): one player implementation for playback and export, proven against the Python reference by the
 * native host test. [image] is [Opl3Lowering.IMAGE_SIZE] bytes from [Opl3System.engine]; rendering stops at the second
 * arrival at the loop point or after [maxTicks] ticks (then without a loop).
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface Opl3VgmRenderer {
    fun render(image: ByteArray, maxTicks: Int, tags: Opl3VgmTags): ByteArray
}

/**
 * The VGM export seam. Core has no native code, so each host registers the native renderer at startup: Android
 * through JNI (`audio_jni.cpp`), the Deck through cinterop. JVM unit tests run without it (the native host test
 * covers the renderer's bytes against the reference; `tools/audio/render_opl3_image.sh --vgm` renders an image
 * from the command line).
 */
object Opl3Vgm {
    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: Opl3VgmRenderer? = null

    /** Ten minutes of ticks at the song's multispeed: a song that never returns to its loop point ends there. */
    fun maxTicks(song: Song): Int = 60 * song.opl3.multispeed * 600

    /**
     * [song] as a VGM file.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song): ByteArray {
        val r = renderer ?: throw IllegalStateException("VGM export needs the native OPL3 player, which this build does not include")
        val image = ByteArray(Opl3Lowering.IMAGE_SIZE)
        Opl3Lowering.lower(song, image)
        return r.render(image, maxTicks(song), Opl3VgmTags(song.title, song.artist))
    }
}
