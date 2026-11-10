package org.thortracker.system.saa

import org.thortracker.backend.SaaLowering
import org.thortracker.backend.SaaPlayerImage
import org.thortracker.model.Song

/**
 * The GD3 tag the native VGM writer adds: track [title], [author] and release [date] "yyyy/mm/dd" (empty: the native
 * writer uses today's date).
 */
data class SaaVgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a VGM 1.71 file from a SAM Coupé or CMS image with the native player (`sa_export_vgm`: `sa_vgm_render` plus
 * the GD3 tag, in `saa/saa_vgm.c`): one player implementation for playback and export, proven against the Python
 * reference by the native host test. [image] is [SaaPlayerImage.IMAGE_SIZE] bytes from the system's engine (its variant
 * byte picks one SAA1099 at 8 MHz or the CMS's dual chip at 7159090 Hz); rendering stops at the second arrival at the
 * loop point or after [maxTicks] ticks (then without a loop).
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface SaaVgmRenderer {
    fun render(image: ByteArray, maxTicks: Int, tags: SaaVgmTags): ByteArray
}

/**
 * The VGM export seam. Core has no native code, so each host registers the native renderer at startup: Android
 * through JNI (`ChipAudio.nativeSaaVgm`), the Deck through cinterop (`DeckSaaVgm`). JVM unit tests run without it (the
 * native host test covers the renderer's bytes against the reference; `tools/audio/render_saa_image.sh --vgm` renders
 * an image from the command line).
 */
object SaaVgm {
    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: SaaVgmRenderer? = null

    /** Ten minutes of ticks at the song's rate and multispeed: a song that never returns to its loop point ends there. */
    fun maxTicks(song: Song): Int = song.saa.variant.rate * song.saa.multispeed * 600

    /**
     * [song] (SAM Coupé or CMS) as a VGM file.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song): ByteArray {
        val r = renderer ?: throw IllegalStateException("VGM export needs the native SAA1099 player, which this build does not include")
        val image = ByteArray(SaaPlayerImage.IMAGE_SIZE)
        SaaLowering.lower(song, image)
        return r.render(image, maxTicks(song), SaaVgmTags(song.title, song.artist))
    }
}
