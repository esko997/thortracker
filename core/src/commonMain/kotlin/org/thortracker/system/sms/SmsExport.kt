package org.thortracker.system.sms

import org.thortracker.backend.SmsImageLayout
import org.thortracker.backend.SmsLowering
import org.thortracker.model.Song

/** The GD3 tag the native VGM writer adds: [title], [author], release [date] "yyyy/mm/dd" (empty: today). */
data class SmsVgmTags(val title: String, val author: String, val date: String = "")

/**
 * Renders a Master System image as VGM 1.51 with the native machine (`sms_export_vgm`, `sms/sms_export.h`): the Banjo
 * driver's own writes, as playback hears them. [image] is [SmsImageLayout.IMAGE_SIZE] bytes from the system's engine;
 * rendering stops at the second arrival at the loop point or after [maxTicks] ticks (then without a loop).
 * @throws IllegalArgumentException with the loader's problem when the image is rejected.
 */
fun interface SmsVgmRenderer {
    fun render(image: ByteArray, maxTicks: Int, tags: SmsVgmTags): ByteArray
}

/**
 * The Master System exports: Banjo song sources a game assembles with the driver (WLA-DX `.asm`, as upstream's
 * examples include songs; SDAS `.s` for devkitSMS and GBDK, labels prefixed `_`), written here from the same Banjo data
 * the engine plays ([org.thortracker.backend.BanjoSong.asm]), and VGM through the native renderer each host registers
 * (Android JNI `ChipAudio.nativeSmsVgm`, Deck cinterop over `sms/sms_export.h`).
 */
object SmsExport {
    enum class Format(val label: String) { WLA_DX("WLA-DX source"), SDAS("SDAS source"), VGM("VGM") }

    /** Set by the host before the first VGM export; null where no native machine is linked. */
    var renderer: SmsVgmRenderer? = null

    /** Ten minutes of ticks: a song that never returns to its loop point ends there. */
    const val MAX_TICKS = 60 * 600

    /**
     * [song] as a [format] file.
     * @throws IllegalStateException for VGM when this build has no native renderer registered.
     */
    fun export(song: Song, format: Format): ByteArray = when (format) {
        Format.VGM -> {
            val r = renderer ?: throw IllegalStateException("VGM export needs the native Master System player, which this build does not include")
            val image = ByteArray(SmsImageLayout.IMAGE_SIZE)
            SmsLowering.lower(song, image)
            r.render(image, MAX_TICKS, SmsVgmTags(song.title, song.artist))
        }
        else -> source(song, format == Format.SDAS).encodeToByteArray()
    }

    /** The song's Banjo source text (WLA-DX, or SDAS when [sdas]). */
    fun source(song: Song, sdas: Boolean): String {
        val id = identifier(song.title)
        val layout = song.sms.layout
        val comment = listOf(
            "${song.title.ifBlank { "Untitled" }}${if (song.artist.isNotBlank()) " by ${song.artist}" else ""}",
            "${layout.displayName}: Banjo sound driver song data (joffb/banjo ${SmsImageLayout.BANJO_COMMIT.take(7)}), exported by Thor Tracker.",
            if (sdas) "SDCC/SDAS: link with Banjo's banjo, banjo_sn" + (if (layout.fm) ", banjo_opll" else "") +
                (if (layout.drums) ", banjo_opll_drums" else "") + " libraries; extern const song_data_t $id; banjo_play_song(&$id)."
            else "WLA-DX: include beside banjo.asm, banjo_sn.asm" + (if (layout.fm) ", banjo_opll.asm" else "") +
                (if (layout.drums) ", banjo_opll_drums.asm" else "") + "; play with ld hl, $id / ld d, 1 / call banjo_play_song.",
            "banjo_init needs ${layout.channelCount} channels and BANJO_HAS_SN" + (if (layout.fm) " | BANJO_HAS_OPLL" else "") +
                (if (layout.stereo) "; on a Game Gear banjo_check_hardware sets the stereo flag." else "."),
        )
        return SmsLowering.build(song).asm(id, sdas, comment)
    }

    /** A label from [title]: lower case letters, digits and underscores, starting with a letter ("song" when empty). */
    fun identifier(title: String): String {
        val cleaned = title.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("")
            .split('_').filter { it.isNotEmpty() }.joinToString("_")
        return when {
            cleaned.isEmpty() -> "song"
            cleaned[0] in '0'..'9' -> "song_$cleaned"
            else -> cleaned
        }
    }
}
