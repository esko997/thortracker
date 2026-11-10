package org.thortracker.backend

import org.thortracker.editor.EditorSession
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.system.nes.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real row attributes, exports and editor persistence: no separate multi-column playback path. */
class NesMultiEffectTest {
    private fun at(song: Song, ch: Int, row: Int, cell: Cell): Song = song.copy(patterns = song.patterns.mapIndexed { p, pattern ->
        if (p != 0) pattern else pattern.copy(tracks = pattern.tracks.mapIndexed { c, track ->
            if (c != ch) track else track.mapIndexed { r, old -> if (r == row) cell else old }
        })
    })

    private fun image(song: Song): ByteArray = ByteArray(song.system.engine.imageSize).also { song.system.engine.lower(song, it) }
    private fun text(song: Song) = FamiStudioText.export(song).decodeToString()
    private val four = Cell(48, 1, NesRules.FX_VOLUME, 9)
        .withEffect(1, NesRules.FX_VIBRATO, 0x63)
        .withEffect(2, NesRules.FX_FINE_PITCH, 0x84)
        .withEffect(3, NesRules.FX_SPEED, 8)

    @Test fun expansionVoicesApplyTheirOwnFinePitchAndKeepOtherAttributes() {
        for (expansion in NesExpansion.entries) {
            val base = expansion.system.blank()
            for (ch in NesRules.APU_CHANNELS until expansion.channels) {
                if (!NesRules.pitched(expansion, ch)) continue
                val bank = base.nes.instruments.toMutableList().also { it[0] = NesInstrument(chip = NesRules.chip(expansion, ch)) }
                val song = at(base.copy(sound = base.nes.copy(instruments = bank)), ch, 0, four)
                assertEquals(emptyList(), song.system.errors(song), "$expansion channel $ch")
                val n = FsSong.of(song).notes[ch][0]!!
                val pitchOnly = at(base.copy(sound = base.nes.copy(instruments = bank)), ch, 0,
                    Cell(48, 1, NesRules.FX_FINE_PITCH, 0x84))
                assertEquals(FsSong.of(pitchOnly).notes[ch][0]!!.finePitch, n.finePitch)
                assertEquals(9, n.volume)
                assertEquals(0x63, n.vibrato)
                assertEquals(8, n.speed)
                assertContentEquals(image(song), image(ProjectCodec.decode(ProjectCodec.encode(song))))
            }
        }
    }

    @Test fun instrumentNumbersRemainIndependentAcrossEffectEditsAndUndo() {
        val song = at(NesSystem.blank(), 0, 0, four)
        val session = EditorSession(song)
        val changed = four.copy(instrument = 23)
        session.setCell(0, 0, 0, changed)
        assertEquals(23, FsSong.of(session.song).notes[0][0]!!.instrument)
        assertEquals(four.more, session.song.patterns[0].tracks[0][0].more)
        session.setCell(0, 0, 0, changed.withEffect(3, NesRules.FX_SPEED, 7))
        assertEquals(23, FsSong.of(session.song).notes[0][0]!!.instrument)
        session.undo()
        assertEquals(changed, session.song.patterns[0].tracks[0][0])
        session.undo()
        assertEquals(song, session.song)
    }

    @Test fun everyVariantPersistsLowersAndExportsFourIndependentAttributes() {
        for (expansion in NesExpansion.entries) {
            val system = expansion.system
            val song = at(system.blank(), 0, 0, four)
            for (ch in system.channels.indices) assertEquals(4, system.effectColumns(ch), system.id)
            assertEquals(emptyList(), system.errors(song), system.id)
            val reopened = ProjectCodec.decode(ProjectCodec.encode(song))
            assertEquals(song, reopened, system.id)
            val n = assertNotNull(FsSong.of(reopened).notes[0][0])
            assertEquals(1, n.instrument)
            assertEquals(9, n.volume)
            assertEquals(0x63, n.vibrato)
            assertEquals(-4, n.finePitch)
            assertEquals(8, n.speed)
            assertContentEquals(image(song), image(reopened), system.id)
            assertContentEquals(NsfExporter.export(song), NsfExporter.export(reopened), system.id)
            assertEquals(text(song), text(reopened), system.id)
            val row = text(song).lineSequence().first { "FinePitch=\"-4\"" in it }
            assertTrue("Volume=\"9\"" in row && "Speed=\"8\"" in row && "VibratoSpeed=\"6\"" in row, row)
        }
    }

    @Test fun unusedColumnsAndIndependentColumnOrderDoNotChangeNativeOutput() {
        val base = NesSystem.blank()
        val left = at(base, 0, 0, four)
        val right = at(base, 0, 0, Cell(48, 1, NesRules.FX_SPEED, 8)
            .withEffect(1, NesRules.FX_FINE_PITCH, 0x84).withEffect(2, NesRules.FX_VOLUME, 9)
            .withEffect(3, NesRules.FX_VIBRATO, 0x63))
        assertContentEquals(image(left), image(right))
        assertContentEquals(NsfExporter.export(left), NsfExporter.export(right))
        assertEquals(text(left), text(right))
        val one = at(base, 0, 0, Cell(48, 1, NesRules.FX_VOLUME, 9))
        val last = at(base, 0, 0, Cell(48, 1).withEffect(3, NesRules.FX_VOLUME, 9))
        assertContentEquals(image(one), image(last))
        assertContentEquals(NsfExporter.export(one), NsfExporter.export(last))
        assertEquals(text(one), text(last))
    }

    @Test fun duplicateAndRelatedCommandsResolveToTheRightmostAttribute() {
        val cell = Cell(48, 1, NesRules.FX_VOLUME_SLIDE, 0x2f).withEffect(1, NesRules.FX_VOLUME, 9)
            .withEffect(2, NesRules.FX_SLIDE_UP, 5).withEffect(3, NesRules.FX_SLIDE_DOWN, 2)
        val song = at(NesSystem.blank(), 0, 0, cell)
        val n = assertNotNull(FsSong.of(song).notes[0][0])
        assertEquals(9, n.volume)
        assertEquals(-1, n.volumeSlideTarget)
        assertEquals(n.value - 2, n.slideTarget)
        val effective = at(NesSystem.blank(), 0, 0, Cell(48, 1, NesRules.FX_VOLUME, 9).withEffect(1, NesRules.FX_SLIDE_DOWN, 2))
        assertContentEquals(image(effective), image(song))
        assertEquals(text(effective), text(song))
        val slideLast = at(NesSystem.blank(), 0, 0, Cell(48, 1, NesRules.FX_VOLUME, 9).withEffect(3, NesRules.FX_VOLUME_SLIDE, 0x2f))
        assertEquals(2, FsSong.of(slideLast).notes[0][0]!!.volume)
        assertEquals(15, FsSong.of(slideLast).notes[0][0]!!.volumeSlideTarget)
    }

    @Test fun finePitchInLaterColumnsSurvivesNotesAndClearsOnlyOnAnUntunedNote() {
        var song = at(NesSystem.blank(), 0, 0, four)
        song = at(song, 0, 1, Cell(50, 1).withEffect(3, NesRules.FX_FINE_PITCH, 0x87))
        song = at(song, 0, 2, Cell(52, 1, NesRules.FX_VOLUME, 8))
        val fs = FsSong.of(song)
        assertEquals(-4, fs.notes[0][0]!!.finePitch)
        assertEquals(-7, fs.notes[0][1]!!.finePitch)
        assertEquals(0, fs.notes[0][2]!!.finePitch)
    }

    @Test fun tuningUsesAHoleOrExistingPitchColumnAndNeverOverwritesFullRows() {
        val fine = NesSystem.fine
        val source = Cell(48, 7, NesRules.FX_VOLUME, 8).withEffect(2, NesRules.FX_VIBRATO, 0x63)
        val tuned = assertNotNull(fine.write(source, 49, 3))
        assertEquals(source.copy(note = 49).withEffect(1, NesRules.FX_FINE_PITCH, 0x83), tuned)
        assertEquals(3, fine.of(tuned))
        assertEquals(source.copy(note = 50), fine.write(tuned, 50, 0))
        val full = source.withEffect(1, NesRules.FX_ARPEGGIO, 0x37).withEffect(3, NesRules.FX_SPEED, 6)
        assertNull(fine.write(full, 49, 3))
        assertEquals(full.copy(note = 49), fine.write(full, 49, 0))
        val duplicates = tuned.withEffect(3, NesRules.FX_FINE_PITCH, 0x85)
        assertEquals(source.copy(note = 49), fine.write(duplicates, 49, 0))
        assertEquals(5, fine.of(duplicates))
        val session = EditorSession(at(NesSystem.blank(), 0, 0, source))
        session.setCell(0, 0, 0, tuned)
        assertEquals(tuned, ProjectCodec.decode(ProjectCodec.encode(session.song)).patterns[0].tracks[0][0])
        session.undo()
        assertEquals(source, session.song.patterns[0].tracks[0][0])
    }

    @Test fun speedUsesFirstChannelAndLastColumnIncludingPcmAndWarnings() {
        var song = at(NesMmc5System.blank(), 0, 0, Cell(effect = 15, param = 4).withEffect(3, 15, 9))
        song = at(song, 1, 0, Cell(effect = 15, param = 5))
        song = at(song, NesRules.MMC5_PCM, 0, Cell().withEffect(2, 15, 7))
        song = at(song, NesRules.MMC5_PCM, 1, Cell().withEffect(3, 15, 3))
        song = at(song, 0, 1, Cell(48, 1).withEffect(3, NesRules.FX_NOTE_CUT, 4))
        val fs = FsSong.of(song)
        assertEquals(9, fs.speedAt(0))
        assertEquals(3, fs.speedAt(1))
        assertEquals(9, NesRules.rowSpeed(song.patterns[0], 0, 6))
        assertEquals(3, NesRules.rowSpeed(song.patterns[0], 1, 9))
        assertTrue(song.system.warnings(song).any { "row 1" in it && "lasts 3 frames" in it })
    }

    @Test fun delayedCutsUseRowTimeAndRejectOnlyCutBeforeNoteOnEngineLanes() {
        for (cut in listOf(2, 5)) {
            val cell = Cell(48, 1, NesRules.FX_NOTE_DELAY, 2).withEffect(2, NesRules.FX_NOTE_CUT, cut)
            val song = at(NesSystem.blank(), 0, 0, cell)
            assertEquals(emptyList(), song.system.errors(song))
            val n = FsSong.of(song).notes[0][0]!!
            assertEquals(2, n.noteDelay)
            assertEquals(cut - 2, n.cutDelay)
            val row = text(song).lineSequence().first { "NoteDelay=\"2\"" in it }
            assertTrue("CutDelay=\"${cut - 2}\"" in row, row)
            assertTrue(image(song).isNotEmpty())
        }
        val bad = at(NesSystem.blank(), 0, 3, Cell(48, 1, NesRules.FX_NOTE_DELAY, 3).withEffect(3, NesRules.FX_NOTE_CUT, 2))
        assertTrue(bad.system.errors(bad).any { "Pulse 1 row 3" in it && "cuts before" in it })
        assertNotNull(NesSystem.cellProblem(NesSystem.blank(), 0, Cell(48, 1).withEffect(3, NesRules.FX_NOTE_DELAY, 0)))
    }

    @Test fun fdsHalvesComposeAndHeldStateCarriesAcrossRows() {
        val base = NesFdsSystem.blank()
        val bank = base.nes.instruments.toMutableList().also { it[0] = NesInstrument(chip = NesChip.FDS, fds = FdsSound.DEFAULT.copy(modSpeed = 0x123)) }
        var song = base.copy(sound = base.nes.copy(instruments = bank))
        song = at(song, NesRules.FDS, 0, Cell(48, 1, NesRules.FX_FDS_MOD_SPEED_HI, 2)
            .withEffect(1, NesRules.FX_FDS_MOD_SPEED_LO, 0x45).withEffect(2, NesRules.FX_FDS_MOD_DEPTH, 7).withEffect(3, NesRules.FX_PHASE_RESET, 0))
        song = at(song, NesRules.FDS, 1, Cell().withEffect(3, NesRules.FX_FDS_MOD_SPEED_LO, 0x67))
        assertEquals(emptyList(), song.system.errors(song))
        val fs = FsSong.of(song)
        assertEquals(0x245, fs.notes[NesRules.FDS][0]!!.fdsModSpeed)
        assertEquals(7, fs.notes[NesRules.FDS][0]!!.fdsModDepth)
        assertTrue(fs.notes[NesRules.FDS][0]!!.phaseReset)
        assertEquals(0x267, fs.notes[NesRules.FDS][1]!!.fdsModSpeed)
        assertTrue(text(song).contains("FdsModSpeed=\"581\""))
    }

    @Test fun ssgPairedBytesShareOnePeriodAndConflictsRemainErrors() {
        for (system in listOf(NesS5bSystem, NesEpsmSystem)) {
            val squares = NesRules.ssgChannels(system.expansion)
            var song = at(system.blank(), squares.first, 0, Cell().withEffect(1, NesRules.FX_ENV_PERIOD_HI, 1)
                .withEffect(2, NesRules.FX_ENV_PERIOD_HI, 2).withEffect(3, NesRules.FX_ENV_PERIOD_LO, 0x34))
            assertEquals(emptyList(), system.errors(song))
            assertEquals(0x234, FsSong.of(song).notes[squares.first][0]!!.envelopePeriod)
            assertTrue(text(song).contains("EnvelopePeriod=\"564\""))
            song = at(song, squares.last, 0, Cell().withEffect(3, NesRules.FX_ENV_PERIOD_HI, 3))
            assertTrue(system.errors(song).any { "envelope period twice" in it && "row 0" in it })
        }
    }

    @Test fun invalidLaterEffectsAreNotSilentlyIgnored() {
        val song = at(NesSystem.blank(), NesRules.TRIANGLE, 4, Cell(48, 1).withEffect(3, NesRules.FX_VOLUME, 8))
        assertTrue(song.system.errors(song).any { "row 4" in it && "effect column 4" in it && "no effect" in it })
        assertNotNull(NesRules.cellRangeProblem(NesExpansion.NONE, Cell().withEffect(3, 25, 256)))
        assertFalse(NesRules.cellRangeProblem(NesExpansion.NONE, four) != null)
    }
}
