package org.thortracker.system.ym2612

import org.thortracker.backend.YmLowering
import org.thortracker.backend.YmPlayerImage
import org.thortracker.model.Song

/**
 * The GD3 tag the native writer adds to VGM and XGM files: track [title], [author] and release [date] "yyyy/mm/dd"
 * (empty: the native writer uses today's date). XGC files carry none.
 */
data class YmExportTags(val title: String, val author: String, val date: String = "")

/**
 * Renders an export from a Mega Drive image with the native player and xgmtool's conversion (`ym_export`,
 * `ym/ym_export.h`): one player implementation for playback and export, proven against the Python reference and
 * xgmtool by the native host test. [image] is [YmPlayerImage.IMAGE_SIZE] bytes from [YmSystem.engine]; [format] is
 * one of [YmPlayerImage.EXPORT_VGM], [YmPlayerImage.EXPORT_XGM], [YmPlayerImage.EXPORT_XGC]; rendering stops at the
 * second arrival at the loop point or after [maxTicks] ticks (then without a loop).
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface YmExportRenderer {
    fun render(image: ByteArray, format: Int, maxTicks: Int, tags: YmExportTags): ByteArray
}

/**
 * The export seam. Core has no native code, so each host registers the native renderer at startup: Android through
 * JNI (`ChipAudio.nativeYmExport`), the Deck through cinterop. JVM unit tests run without it (the native host test
 * covers the bytes; `tools/audio/render_ym_image.sh --vgm|--xgm|--xgc` renders an image from the command line).
 */
object YmExport {
    /** The three files: VGM 1.71 (players, archives), XGM (SGDK's `rescomp` `XGM` resource), XGC (`XGM_startPlay`). */
    enum class Format(val code: Int, val label: String) {
        VGM(YmPlayerImage.EXPORT_VGM, "VGM"),
        XGM(YmPlayerImage.EXPORT_XGM, "XGM"),
        XGC(YmPlayerImage.EXPORT_XGC, "XGC"),
    }

    /** Set by the host before the first export; null where no native player is linked. */
    var renderer: YmExportRenderer? = null

    /** Ten minutes of ticks in the song's region: a song that never returns to its loop point ends there. */
    fun maxTicks(song: Song): Int = (if (song.ym.pal) 50 else 60) * 600

    /**
     * [song] as a [format] file.
     * @throws IllegalStateException when this build has no native renderer registered.
     */
    fun export(song: Song, format: Format): ByteArray {
        val r = renderer ?: throw IllegalStateException("${format.label} export needs the native Mega Drive player, which this build does not include")
        val image = ByteArray(YmPlayerImage.IMAGE_SIZE)
        YmLowering.lower(song, image)
        return r.render(image, format.code, maxTicks(song), YmExportTags(song.title, song.artist))
    }
}
