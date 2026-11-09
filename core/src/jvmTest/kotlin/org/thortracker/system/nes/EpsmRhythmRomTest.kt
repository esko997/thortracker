package org.thortracker.system.nes

import org.thortracker.model.Sha256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The YM2608 rhythm ROM check (the user's own file, never shipped): exact size, then the exact SHA-256. Tests never call
 * [EpsmRhythmRom.markInstalled] (it is one-way for the process), so the missing-ROM paths stay testable.
 */
class EpsmRhythmRomTest {
    @Test
    fun wrongSizesAreRefusedFirst() {
        for (size in listOf(0, 100, 8191, 8193, 16384)) {
            val problem = EpsmRhythmRom.problem(ByteArray(size))
            assertTrue(problem != null && "8192" in problem && "$size bytes" in problem, problem)
        }
    }

    @Test
    fun theRightSizeWithAnotherDigestIsRefused() {
        for (fill in listOf<Byte>(0, 0x7f, -1)) {
            val problem = EpsmRhythmRom.problem(ByteArray(EpsmRhythmRom.SIZE) { fill })
            assertTrue(problem != null && "SHA-256" in problem && "ym2608_adpcm_rom.bin" in problem, problem)
        }
    }

    @Test
    fun constantsDescribeTheOneDump() {
        assertEquals(8192, EpsmRhythmRom.SIZE)
        assertEquals(64, EpsmRhythmRom.SHA256.length)
        assertTrue(EpsmRhythmRom.SHA256.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("ym2608_adpcm_rom.bin", EpsmRhythmRom.FILE_NAME)
        assertEquals(listOf(".bin", ".rom"), EpsmRhythmRom.EXTENSIONS)
        assertTrue(EpsmRhythmRom.FILE_NAME in EpsmRhythmRom.MISSING && "FM and SSG play without it" in EpsmRhythmRom.MISSING)
        assertFalse(EpsmRhythmRom.installed)
    }

    /** The repository (sources, resources, assets, fixtures) holds no copy of the ROM: it is Yamaha's, the user supplies it. */
    @Test
    fun theRepositoryHoldsNoRhythmRom() {
        val root = File("..").canonicalFile
        val skip = setOf(".git", "build", ".gradle", ".kotlin", ".cxx", "node_modules")
        val copies = root.walkTopDown().onEnter { it.name !in skip }
            .filter { it.isFile && it.length() == EpsmRhythmRom.SIZE.toLong() }
            .filter { Sha256.hex(it.readBytes()) == EpsmRhythmRom.SHA256 }
            .toList()
        assertEquals(emptyList(), copies)
    }
}
