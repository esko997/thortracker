package org.thortracker.backend

import org.thortracker.model.Song
import org.thortracker.backend.SidPlayerImage as P

/**
 * C64 exports of a SID song: the player (GoatTracker 2.77's playroutine, its five song parameters written) and the
 * lowered data ([SidLowering]) as one C64 memory block from [P.LOAD] to the data end — the bytes the preview engine
 * plays.
 * - `.sid`: PSID v2 as gt2reloc writes it (PAL, the song's chip model; multispeed songs load and init at
 *   [P.SPEED_CODE_ADDRESS], where gt2reloc's CIA speed code programs the timer before the player's init). Plays in
 *   sidplayfp and other SID players.
 * - `.prg`: a C64 program: `10 SYS2061`, then a few bytes that init the player and hook it into the KERNAL's CIA 1
 *   timer interrupt at the song's rate (`$4CC7` / multispeed cycles: 50 Hz on PAL), returning to BASIC with the music
 *   playing; then the same block from [P.LOAD]. `RUN` it on a PAL C64 (or `LOAD"name",8` and `RUN`).
 * - `.sng`: GoatTracker 2's song file ([GtSong.sng]); GoatTracker 2.77 opens it and its packer packs it to the same
 *   playroutine data. Chip model, multispeed, hard-restart envelope and a tuning's frequency table are packer options
 *   there, not part of the file.
 */
object SidExport {
    /** The C64 memory block from [P.LOAD]: patched player, then the region up to the data end. */
    fun block(gt: GtSong): ByteArray {
        val image = ByteArray(P.IMAGE_SIZE)
        val lowered = SidLowering.lower(gt, image)
        val out = ByteArray(lowered.dataEnd - P.LOAD)
        P.BYTES.copyInto(out)
        out[P.PATCH_FIRSTNOHRINSTR - P.LOAD] = lowered.firstNoHr.toByte()
        out[P.PATCH_FIRSTLEGATOINSTR - P.LOAD] = lowered.firstLegato.toByte()
        out[P.PATCH_ADPARAM - P.LOAD] = gt.hrAd.toByte()
        out[P.PATCH_SRPARAM - P.LOAD] = gt.hrSr.toByte()
        out[P.PATCH_DEFAULTTEMPO - P.LOAD] = gt.tempo.toByte()
        image.copyInto(out, P.REGION_BASE - P.LOAD, P.HEADER_SIZE, P.HEADER_SIZE + lowered.dataEnd - P.REGION_BASE)
        return out
    }

    fun psid(song: Song): ByteArray = psid(GtSong.of(song))

    fun psid(gt: GtSong): ByteArray {
        val block = block(gt)
        val multi = gt.multiplier > 1
        val load = if (multi) P.SPEED_CODE_ADDRESS else P.LOAD
        val out = Bytes()
        out.bytes("PSID".encodeToByteArray())
        be16(out, 2)
        be16(out, P.PSID_HEADER_SIZE)
        be16(out, 0) // load address: the first two data bytes
        be16(out, load)
        be16(out, P.PLAY)
        be16(out, 1)
        be16(out, 1)
        val speed = if (multi) 0xFF else 0x00
        repeat(4) { out.byte(speed) }
        for (t in listOf(gt.name, gt.author, gt.copyright)) out.bytes(text(t))
        be16(out, P.PSID_FLAGS_PAL or if (gt.model == P.MODEL_6581) P.PSID_FLAGS_6581 else P.PSID_FLAGS_8580)
        repeat(4) { out.byte(0) }
        out.byte(load and 0xFF)
        out.byte(load shr 8)
        if (multi) out.bytes(speedCode(gt.multiplier))
        out.bytes(block)
        return out.toByteArray()
    }

    /** gt2reloc's CIA speed code for [multiplier]: timer A = `$4CC7` / multiplier, then falls into init. */
    private fun speedCode(multiplier: Int): ByteArray = P.SPEED_CODE.also {
        val v = P.CIA_PAL_LATCH / multiplier
        it[1] = v.toByte()
        it[6] = (v shr 8).toByte()
    }

    /** BASIC line `10 SYS2061` at $0801. */
    private val BASIC = byteArrayOf(0x0B, 0x08, 0x0A, 0x00, 0x9E.toByte(), 0x32, 0x30, 0x36, 0x31, 0x00, 0x00, 0x00)
    const val PRG_LOAD = 0x0801
    const val PRG_START = 0x080D

    /**
     * Start code at [PRG_START]: `SEI`, init (A = 0), IRQ vector (`$0314`) → handler, CIA 1 timer A latch, start the
     * timer (continuous, force load), `CLI`, `RTS` to BASIC. Handler: `JSR play`, `JMP $EA31` (the KERNAL's IRQ code,
     * which also acknowledges the CIA).
     */
    private fun startCode(multiplier: Int): ByteArray {
        val latch = P.CIA_PAL_LATCH / multiplier
        val handler = PRG_START + 33
        val code = intArrayOf(
            0x78,                                     // SEI
            0xA9, 0x00, 0x20, P.INIT and 0xFF, P.INIT shr 8, // LDA #0 / JSR init
            0xA9, handler and 0xFF, 0x8D, 0x14, 0x03, // LDA #<handler / STA $0314
            0xA9, handler shr 8, 0x8D, 0x15, 0x03,    // LDA #>handler / STA $0315
            0xA9, latch and 0xFF, 0x8D, 0x04, 0xDC,   // timer A latch low
            0xA9, latch shr 8, 0x8D, 0x05, 0xDC,      // timer A latch high
            0xA9, 0x11, 0x8D, 0x0E, 0xDC,             // LDA #$11 / STA $DC0E: start, force load
        )
        val tail = intArrayOf(
            0x58, 0x60,                               // CLI / RTS
            0x20, P.PLAY and 0xFF, P.PLAY shr 8,      // handler: JSR play
            0x4C, 0x31, 0xEA,                         // JMP $EA31
        )
        // The handler must start right after CLI/RTS.
        check(PRG_START + code.size + 2 == handler) { "start code layout" }
        return (code + tail).map { it.toByte() }.toByteArray()
    }

    fun prg(song: Song): ByteArray = prg(GtSong.of(song))

    fun prg(gt: GtSong): ByteArray {
        val block = block(gt)
        val out = ByteArray(2 + P.LOAD - PRG_LOAD + block.size)
        out[0] = PRG_LOAD.toByte()
        out[1] = (PRG_LOAD shr 8).toByte()
        BASIC.copyInto(out, 2)
        startCode(gt.multiplier).copyInto(out, 2 + PRG_START - PRG_LOAD)
        block.copyInto(out, 2 + P.LOAD - PRG_LOAD)
        return out
    }

    fun sng(song: Song): ByteArray = GtSong.of(song).sng()

    private fun be16(out: Bytes, v: Int) { out.byte(v shr 8); out.byte(v and 0xFF) }

    /** A 32-byte PSID text field (Latin-1, zero-padded; 32 characters fill it, as gt2reloc writes them). */
    private fun text(s: String): ByteArray = ByteArray(32).also { b ->
        s.take(32).forEachIndexed { i, ch -> b[i] = (if (ch.code in 0x20..0xFF) ch.code else '?'.code).toByte() }
    }
}
