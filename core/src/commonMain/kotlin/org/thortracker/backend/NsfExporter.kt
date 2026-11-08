package org.thortracker.backend

import org.thortracker.model.Song

/**
 * `.nsf` export (NSF 1, NTSC, no bank switching): the same player, song data and samples the preview engine runs —
 * the song's player build ([NesPlayer]) at its load address with its NSF entry table (init = load, play = load + 3),
 * zeros up to its song base, the lowered FamiStudio data, and when the song plays DPCM samples, zeros up to the sample
 * block's start (the top of `$C000-$FFF9`, where the image puts it) and the samples (without bank switching the whole
 * image is loaded at once). Header byte `$7B` names the expansion chip the player drives (bit 0: VRC6, bit 1: VRC7,
 * image is loaded at once). Header byte `$7B` names the expansion chip the player drives (bit 0: VRC6, bit 1: VRC7,
 * bit 2: FDS, bit 4: Namco 163, bit 5: Sunsoft 5B; FamiStudio's `NsfFile`), so NSF players emulate it. Plays in NSF
 * players and on hardware through cartridges that load NSFs (with the chip; the FDS's sound through an FDS-capable NSF
 * player).
 */
object NsfExporter {
    /** NTSC play rate in microseconds (NSF convention for 60.1 Hz). */
    private const val NTSC_SPEED = 16639

    /** @throws IllegalArgumentException when the song cannot be lowered. */
    fun export(song: Song): ByteArray = FamiStudioLowering.lower(song).let { export(it.player, it.data, it.samples, it.sampleStart, song.title, song.artist) }

    internal fun export(player: NesPlayer, data: ByteArray, samples: ByteArray, sampleStart: Int, title: String, artist: String): ByteArray {
        val code = player.bytes
        val end = if (samples.isEmpty()) player.songBase + data.size else sampleStart + samples.size
        val body = ByteArray(end - player.load)
        code.copyInto(body)
        data.copyInto(body, player.songBase - player.load)
        if (samples.isNotEmpty()) samples.copyInto(body, sampleStart - player.load)
        val header = ByteArray(128)
        "NESM".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        header[4] = 0x1a
        header[5] = 1 // version
        header[6] = 1 // songs
        header[7] = 1 // first song
        put16(header, 8, player.load)
        put16(header, 10, player.init)
        put16(header, 12, player.play)
        text(header, 0x0e, title.ifBlank { "Untitled" })
        text(header, 0x2e, artist)
        text(header, 0x4e, "Thor Tracker")
        put16(header, 0x6e, NTSC_SPEED)
        // 0x70-0x77 bank switching: none. 0x78 PAL speed, 0x7a NTSC, 0x7b expansion chips.
        put16(header, 0x78, 19997)
        header[0x7b] = player.nsfChips.toByte()
        return header + body
    }

    /** NSF text fields: 31 bytes of printable ASCII plus a terminating zero. */
    private fun text(out: ByteArray, at: Int, s: String) {
        val ascii = s.map { if (it.code in 0x20..0x7e) it else '?' }.take(31)
        ascii.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
    }

    private fun put16(out: ByteArray, at: Int, v: Int) {
        out[at] = v.toByte()
        out[at + 1] = (v shr 8).toByte()
    }
}
