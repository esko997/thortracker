package org.thortracker.system.ws

import org.thortracker.backend.WsLowering
import org.thortracker.backend.WsPlayerImage
import org.thortracker.model.Song

/**
 * The GD3 tag the native VGM writer adds: track [title], [author] and release [date] "yyyy/mm/dd" (empty: the native
 * writer uses today's date).
 */
data class WsVgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a VGM 1.71 file for one WonderSwan at 3072000 Hz from a WonderSwan image with the native player, voice timer
 * and Sound DMA (`ws_vgm_render` plus the GD3 tag, in `ws/ws_vgm.c`): one player implementation for playback and
 * export, checked against the Python reference by the native host test. [image] is [WsPlayerImage.IMAGE_SIZE] bytes
 * from the system's engine; rendering ends at the first repeating pair of loop arrivals or after [maxTicks] ticks
 * (then without a loop).
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface WsVgmRenderer {
    fun render(image: ByteArray, maxTicks: Int, tags: WsVgmTags): ByteArray
}

/**
 * The VGM export seam. Core has no native code, so each host registers the native renderer at startup: Android through
 * JNI (`audio_jni.cpp`, `ChipAudio.nativeWsVgm`), the Deck through cinterop (`DeckWsVgm`). JVM unit tests run without
 * it (the native host test covers the renderer's bytes against the reference; `tools/audio/render_ws_image.sh --vgm`
 * renders an image from the command line). VGM's WonderSwan chip has channel 2's voice port but no Hyper Voice, so a
 * Color song with Hyper Voice notes is refused (WAV export carries it); Sound DMA to CH2 is written as the bytes it
 * sends.
 */
object WsVgm {
    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: WsVgmRenderer? = null

    /** Ten minutes of ticks (75.47 per second): a song that never returns to its loop point ends there. */
    const val MAX_TICKS = 45283

    /**
     * [song] as a VGM file.
     * @throws IllegalArgumentException when the song plays Hyper Voice, which VGM cannot carry.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song): ByteArray {
        require(!WsValidator.usesHyperVoice(song)) {
            "VGM's WonderSwan chip has no Hyper Voice: export WAV, or move the Hyper Voice notes to CH2 for a VGM"
        }
        val r = renderer ?: throw IllegalStateException("VGM export needs the native WonderSwan player, which this build does not include")
        val image = ByteArray(WsPlayerImage.IMAGE_SIZE)
        WsLowering.lower(song, image)
        return r.render(image, MAX_TICKS, WsVgmTags(song.title, song.artist))
    }
}
