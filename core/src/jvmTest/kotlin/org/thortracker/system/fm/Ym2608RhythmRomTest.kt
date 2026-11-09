package org.thortracker.system.fm

import org.thortracker.model.Sha256
import org.thortracker.system.SoundSystems
import org.thortracker.system.nes.NesEpsmSystem
import org.thortracker.system.opn.OpnaSystem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The YM2608 rhythm ROM check (the user's own file, never shipped), shared by the NES EPSM and the PC-88/98 OPNA:
 * exact size, then the exact SHA-256. Tests never call [Ym2608RhythmRom.markInstalled] (it is one-way for the process),
 * so the missing-ROM paths stay testable.
 */
class Ym2608RhythmRomTest {
    @Test
    fun wrongSizesAreRefusedFirst() {
        for (size in listOf(0, 100, 8191, 8193, 16384)) {
            val problem = Ym2608RhythmRom.problem(ByteArray(size))
            assertTrue(problem != null && "8192" in problem && "$size bytes" in problem, problem)
        }
    }

    @Test
    fun theRightSizeWithAnotherDigestIsRefused() {
        for (fill in listOf<Byte>(0, 0x7f, -1)) {
            val problem = Ym2608RhythmRom.problem(ByteArray(Ym2608RhythmRom.SIZE) { fill })
            assertTrue(problem != null && "SHA-256" in problem && "ym2608_adpcm_rom.bin" in problem, problem)
        }
    }

    @Test
    fun constantsDescribeTheOneDump() {
        assertEquals(8192, Ym2608RhythmRom.SIZE)
        assertEquals(64, Ym2608RhythmRom.SHA256.length)
        assertTrue(Ym2608RhythmRom.SHA256.all { it in '0'..'9' || it in 'a'..'f' })
        // Unchanged from the EPSM-only days, so a copy imported then keeps loading.
        assertEquals("ym2608_adpcm_rom.bin", Ym2608RhythmRom.FILE_NAME)
        assertEquals(listOf(".bin", ".rom"), Ym2608RhythmRom.EXTENSIONS)
        assertTrue(Ym2608RhythmRom.FILE_NAME in Ym2608RhythmRom.MISSING)
        assertTrue("FM, SSG and ADPCM play without it" in Ym2608RhythmRom.MISSING)
        assertTrue(RhythmRomMenu.LABEL in Ym2608RhythmRom.MISSING)
        assertFalse(Ym2608RhythmRom.installed)
    }

    /** Exactly the systems with rhythm channels offer the import: the NES EPSM and the YM2608. */
    @Test
    fun onlyRhythmSystemsNeedTheRom() {
        assertEquals(setOf(NesEpsmSystem.id, OpnaSystem.id), SoundSystems.ALL.filter { it.rhythmRom }.map { it.id }.toSet())
    }

    /** The repository (sources, resources, assets, fixtures) holds no copy of the ROM: it is Yamaha's, the user supplies it. */
    @Test
    fun theRepositoryHoldsNoRhythmRom() {
        val root = File("..").canonicalFile
        val skip = setOf(".git", "build", ".gradle", ".kotlin", ".cxx", "node_modules")
        val copies = root.walkTopDown().onEnter { it.name !in skip }
            .filter { it.isFile && it.length() == Ym2608RhythmRom.SIZE.toLong() }
            .filter { Sha256.hex(it.readBytes()) == Ym2608RhythmRom.SHA256 }
            .toList()
        assertEquals(emptyList(), copies)
    }
}
