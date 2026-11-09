package org.thortracker.system.fm

import org.thortracker.model.Sha256
import kotlin.concurrent.Volatile

/**
 * The YM2608 rhythm ROM: the 8 KiB of Yamaha ADPCM-A drum samples (bass drum, snare, top cymbal, hi-hat, tom, rim
 * shot) the YM2608's six rhythm channels (the PC-88/98 OPNA system) and the YMF288's (the NES EPSM) play — MAME's
 * `ym2608_adpcm_rom.bin` (Mesen2: `ymf288_adpcm_rom.bin`). Yamaha's data, so Thor Tracker never ships or stores it with
 * songs: the user imports their own copy once, the host keeps it as device firmware ([FILE_NAME] under its data
 * directory's `firmware/`) and installs it into the one native module every chip reads (`ym2608_rhythm_rom.h`).
 * Everything else plays without it; a rhythm note does not ([MISSING]).
 *
 * Hosts: [problem] → persist → native install → [markInstalled].
 */
object Ym2608RhythmRom {
    const val SIZE = 8192

    /** SHA-256 of the one ROM dump (CRC32 23c9e0d8, SHA-1 50b6c3e288eaa12ad275d4f323267bb72b0445df). */
    const val SHA256 = "53afd0fa9c62eda3e2be939e23f3adf48a2af8ad37bb1640261726c5d5adeba8"

    /** The firmware file name hosts store it under (unchanged since the EPSM, so imported copies keep loading). */
    const val FILE_NAME = "ym2608_adpcm_rom.bin"

    /** File name endings the import offers. */
    val EXTENSIONS = listOf(".bin", ".rom")

    /** What a rhythm note or audition reports while no ROM is installed. */
    const val MISSING = "The rhythm drums (YM2608 and the NES EPSM) need the YM2608 rhythm ROM (ym2608_adpcm_rom.bin, " +
        "8192 bytes), which Thor Tracker cannot include: import your copy once with \"${RhythmRomMenu.LABEL}\" (main " +
        "menu, or the Instrument view's rhythm page). FM, SSG and ADPCM play without it"

    @Volatile
    private var present = false

    /** True once a host installed a ROM [problem] accepted into the native engine. */
    val installed: Boolean get() = present

    /** Records that the host installed the ROM into the native engine. */
    fun markInstalled() {
        present = true
    }

    /** Why [bytes] are not the YM2608 rhythm ROM (size first, then the exact digest), or null. */
    fun problem(bytes: ByteArray): String? = when {
        bytes.size != SIZE -> "This file is ${bytes.size} bytes; the YM2608 rhythm ROM (ym2608_adpcm_rom.bin) is exactly $SIZE bytes"
        Sha256.hex(bytes) != SHA256 -> "This $SIZE-byte file is not the YM2608 rhythm ROM (its SHA-256 differs from " +
            "${SHA256.take(12)}…); choose ym2608_adpcm_rom.bin (MAME) or ymf288_adpcm_rom.bin (Mesen2)"
        else -> null
    }
}
