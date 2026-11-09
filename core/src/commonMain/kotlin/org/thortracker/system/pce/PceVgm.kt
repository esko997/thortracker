package org.thortracker.system.pce

import org.thortracker.backend.PceLowering
import org.thortracker.backend.PcePlayerImage
import org.thortracker.model.Song

/**
 * The GD3 tag the native VGM writer adds: track [title], [author] and release [date] "yyyy/mm/dd" (empty: the native
 * writer uses today's date).
 */
data class PceVgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a VGM 1.71 file for one HuC6280 at 3579545 Hz from a PC Engine image with the native player and DDA timer
 * (`pc_vgm_render` plus the GD3 tag, in `pce/pce_vgm.c`): one player implementation for playback and export, checked
 * against the Python reference by the native host test. [image] is [PcePlayerImage.IMAGE_SIZE] bytes from
 * [PceSystem.engine]; rendering stops at the second arrival at the loop point or after [maxTicks] ticks (then without
 * a loop).
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface PceVgmRenderer {
    fun render(image: ByteArray, maxTicks: Int, tags: PceVgmTags): ByteArray
}

/**
 * The VGM export seam. Core has no native code, so each host registers the native renderer at startup: Android
 * through JNI (`audio_jni.cpp`, `ChipAudio.nativePceVgm`), the Deck through cinterop (`DeckPceVgm`). JVM unit tests run
 * without it (the native host test covers the renderer's bytes against the reference; `tools/audio/render_pce_image.sh
 * --vgm` renders an image from the command line). Samples are written one D/A value per timer interrupt, about 7 KB
 * per second per sample channel at the default DDA rate.
 */
object PceVgm {
    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: PceVgmRenderer? = null

    /** Ten minutes of ticks at the song's multispeed: a song that never returns to its loop point ends there. */
    fun maxTicks(song: Song): Int = 60 * song.pce.multispeed * 600

    /**
     * [song] as a VGM file.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song): ByteArray {
        val r = renderer ?: throw IllegalStateException("VGM export needs the native PC Engine player, which this build does not include")
        val image = ByteArray(PcePlayerImage.IMAGE_SIZE)
        PceLowering.lower(song, image)
        return r.render(image, maxTicks(song), PceVgmTags(song.title, song.artist))
    }
}
