package org.thortracker.system.nes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.thortracker.editor.EditorSession
import org.thortracker.model.Cell

/** How long a one-shot DPCM note sounds, in rows, for the Pattern view's sustain rails. */
class DpcmSoundingRowsTest {
    private val session = EditorSession(NesSystem.blank()).apply {
        addAndMapDpcmSamples(1, listOf(DpcmSample("Zap", List(1137) { 0x55 })), 36, 15, "Add")
        setCell(0, NesRules.DPCM, 0, Cell(note = 36, instrument = 1))
    }

    @Test
    fun aSampleSpansItsBitsAtTheRateOverTheRowSpeed() {
        // 1137 bytes = 9096 bits at 33143.9 Hz = 0.274 s = 16.5 frames → 17 ticks: three rows at speed 6.
        assertEquals(17, dpcmTicks(1137, 15))
        assertEquals(6, session.song.ticksPerRow)
        assertEquals(3, NesSystem.soundingRows(session.song, 0, NesRules.DPCM, 0))
        // The other channels have no end of their own.
        assertNull(NesSystem.soundingRows(session.song, 0, 0, 0))
    }

    @Test
    fun speedChangesOnAnyChannelCount() {
        // F03 from row 1: 6 + 3 + 3 + 3 + 3 ticks reach 17 on the fifth row.
        session.setCell(0, 0, 1, Cell(effect = NesRules.FX_SPEED, param = 3))
        assertEquals(5, NesSystem.soundingRows(session.song, 0, NesRules.DPCM, 0))
        // A legato note keeps the previous note's sound.
        session.setCell(0, NesRules.DPCM, 8, Cell(note = 36))
        assertEquals(6, NesSystem.soundingRows(session.song, 0, NesRules.DPCM, 8))
    }

    @Test
    fun loopingAndUnmappedNotesSoundUntilTheNextNote() {
        session.editDpcmMapping(1, 36, "Loop") { it.copy(loop = true) }
        assertNull(NesSystem.soundingRows(session.song, 0, NesRules.DPCM, 0))
        session.setCell(0, NesRules.DPCM, 4, Cell(note = 37, instrument = 1))
        assertNull(NesSystem.soundingRows(session.song, 0, NesRules.DPCM, 4))
    }
}
