package org.thortracker.system.opn

import org.thortracker.backend.OpnLowering
import org.thortracker.model.Song

/**
 * The GD3 tag the native VGM writer adds: track [title], [author] and release [date] "yyyy/mm/dd" (empty: the native
 * writer uses today's date).
 */
data class OpnVgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a VGM 1.71 file from an OPN or OPNA image with the native player (`opn_export_vgm` in `opn/opn_vgm.c`): one
 * player implementation for playback and export. [image] is a whole lowered image ([OpnChip.imageSize] bytes, the chip
 * is its header's); rendering stops at the second arrival at the loop point or after [maxTicks] ticks (then without a
 * loop). A YM2608 file carries the song's ADPCM-B memory as a data block and never the rhythm ROM.
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface OpnVgmRenderer {
    fun render(image: ByteArray, maxTicks: Int, tags: OpnVgmTags): ByteArray
}

/**
 * The VGM export seam. Core has no native code, so each host registers the native renderer at startup: Android
 * through JNI (`ChipAudio.nativeOpnVgm`), the Deck through cinterop (`DeckOpnVgm`). JVM unit tests run without it (the
 * native host test covers the renderer; `tools/audio/render_opn_image.sh` renders an image from the command line).
 * The export never needs the rhythm ROM: VGM players supply their own, so a song with rhythm notes exports without it.
 */
object OpnVgm {
    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: OpnVgmRenderer? = null

    /** Ten minutes of ticks at the song's tick rate: a song that never returns to its loop point ends there. */
    fun maxTicks(song: Song): Int = (OpnRules.tickHz(song.opn.timerB) * 600).toInt()

    /**
     * [song] as a VGM file.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song): ByteArray {
        val r = renderer ?: throw IllegalStateException("VGM export needs the native OPN player, which this build does not include")
        val image = OpnLowering.lower(song)
        return r.render(image, maxTicks(song), OpnVgmTags(song.title, song.artist))
    }
}
