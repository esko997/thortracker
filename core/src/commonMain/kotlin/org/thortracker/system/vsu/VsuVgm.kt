package org.thortracker.system.vsu

import org.thortracker.backend.VsuLowering
import org.thortracker.backend.VsuPlayerImage
import org.thortracker.model.Song

/**
 * The GD3 tag the native VGM writer adds: track [title], [author] and release [date] "yyyy/mm/dd" (empty: the native
 * writer uses today's date).
 */
data class VsuVgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a VGM 1.71 file from a VSU image with the native player (`vb_vgm_render` plus the GD3 tag, in
 * `vsu/vsu_vgm.c`): one player implementation for playback and export. [image] is [VsuPlayerImage.IMAGE_SIZE] bytes from
 * [VsuSystem.engine]; the file loops where two consecutive arrivals at the loop point hold the same state, or ends after
 * [maxTicks] ticks without a loop.
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface VsuVgmRenderer {
    fun render(image: ByteArray, maxTicks: Int, tags: VsuVgmTags): ByteArray
}

/**
 * The VGM export seam. Core has no native code, so each host registers the native renderer at startup: Android through
 * JNI (`ChipAudio.nativeVsuVgm`), the Deck through cinterop (`DeckVsuVgm`). JVM unit tests run without it
 * (`tools/audio/render_vsu_image.sh --vgm` renders an image from the command line).
 */
object VsuVgm {
    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: VsuVgmRenderer? = null

    /** Ten minutes of 50 Hz ticks: a song that never returns to its loop point in the same state ends there. */
    const val MAX_TICKS = VsuPlayerImage.TICK_HZ * 600

    /**
     * [song] as a VGM file.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song): ByteArray {
        val r = renderer ?: throw IllegalStateException("VGM export needs the native VSU player, which this build does not include")
        val image = ByteArray(VsuPlayerImage.IMAGE_SIZE)
        VsuLowering.lower(song, image)
        return r.render(image, MAX_TICKS, VsuVgmTags(song.title, song.artist))
    }
}
