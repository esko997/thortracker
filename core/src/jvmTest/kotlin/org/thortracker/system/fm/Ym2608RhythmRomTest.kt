package org.thortracker.system.fm

import org.thortracker.system.SoundSystems
import org.thortracker.system.nes.NesEpsmSystem
import org.thortracker.system.opn.OpnaSystem
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** Exactly the systems with rhythm channels offer the import: the NES EPSM and the YM2608. */
    @Test
    fun onlyRhythmSystemsNeedTheRom() {
        assertEquals(setOf(NesEpsmSystem.id, OpnaSystem.id), SoundSystems.ALL.filter { it.rhythmRom }.map { it.id }.toSet())
    }

}
