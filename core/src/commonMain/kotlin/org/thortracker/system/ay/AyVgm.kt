package org.thortracker.system.ay

import org.thortracker.backend.AyLowering
import org.thortracker.backend.AyPlayerImage
import org.thortracker.model.Song

/**
 * The GD3 tag the native VGM writer adds: track [title], [author] and release [date] "yyyy/mm/dd" (empty: the native
 * writer uses today's date).
 */
data class AyVgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a VGM 1.71 file from an AY image with the native player (`ay_export_vgm`, `ay/ay_export.h`: `ay_vgm_render`
 * plus the GD3 tag): one player implementation for playback and export, proven against the Python reference by the
 * native host test. [variant] is the image's variant byte ([AyVariant.index]); [image] is [AyPlayerImage.IMAGE_SIZE]
 * bytes from the variant's engine; rendering stops at the second arrival at the loop point or after [maxTicks] ticks
 * (then without a loop).
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface AyVgmRenderer {
    fun render(variant: Int, image: ByteArray, maxTicks: Int, tags: AyVgmTags): ByteArray
}

/**
 * The VGM export seam. Core has no native code, so each host registers the native renderer at startup: Android through
 * JNI (`ChipAudio.nativeAyVgm`), the Deck through cinterop (`DeckAyVgm`). JVM unit tests run without it (the native host
 * test covers the bytes; `tools/audio/render_ay_image.sh --vgm` renders an image from the command line).
 */
object AyVgm {
    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: AyVgmRenderer? = null

    /** Ten minutes of ticks at the variant's frame rate: a song that never returns to its loop point ends there. */
    fun maxTicks(song: Song): Int = (song.ay.variant.tickHz * 600).toInt()

    /**
     * [song] as a VGM file.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song): ByteArray {
        val r = renderer ?: throw IllegalStateException("VGM export needs the native AY player, which this build does not include")
        val image = ByteArray(AyPlayerImage.IMAGE_SIZE)
        AyLowering.lower(song, image)
        return r.render(song.ay.variant.index, image, maxTicks(song), AyVgmTags(song.title, song.artist))
    }
}
