package org.thortracker.system.pokey

import org.thortracker.backend.PokeyLowering
import org.thortracker.backend.PokeyPlayerImage
import org.thortracker.model.Song
import kotlin.math.ceil

/**
 * The GD3 tag the native VGM writer adds: track [title], [author] and release [date] "yyyy/mm/dd" (empty: the native
 * writer uses today's date).
 */
data class PokeyVgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a VGM 1.71 file (one POKEY) from a POKEY image with the native player (`pk_export_vgm`, `pokey/pokey_vgm.c`
 * plus the GD3 tag): one player implementation for playback and export. [image] is [PokeyPlayerImage.IMAGE_SIZE] bytes
 * from [PokeySystem.engine]; rendering stops at the second arrival at the loop point or after [maxTicks] ticks (then
 * without a loop).
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface PokeyVgmRenderer {
    fun render(image: ByteArray, maxTicks: Int, tags: PokeyVgmTags): ByteArray
}

/**
 * The VGM export seam. Core has no native code, so each host registers the native renderer at startup: Android through
 * JNI (`ChipAudio.nativePokeyVgm`), the Deck through cinterop (`DeckPokeyVgm`). JVM unit tests run without it.
 */
object PokeyVgm {
    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: PokeyVgmRenderer? = null

    /** Ten minutes of ticks at the song's machine and multispeed: a song that never returns to its loop point ends there. */
    fun maxTicks(song: Song): Int = ceil(PokeySystem.ticksPerSecond(song) * 600).toInt()

    /**
     * [song] as a VGM file.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song): ByteArray {
        val r = renderer ?: throw IllegalStateException("VGM export needs the native POKEY player, which this build does not include")
        val image = ByteArray(PokeyPlayerImage.IMAGE_SIZE)
        PokeyLowering.lower(song, image)
        return r.render(image, maxTicks(song), PokeyVgmTags(song.title, song.artist))
    }
}
