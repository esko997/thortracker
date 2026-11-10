package org.thortracker.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.opl3.Opl3System
import org.thortracker.system.opm.OpmSystem
import org.thortracker.system.opn.OpnSystem
import org.thortracker.system.opn.OpnaSystem
import org.thortracker.system.sid.SidSystem
import org.thortracker.system.sms.SmsSystem
import org.thortracker.system.tia.TiaNtscSystem
import org.thortracker.system.ym2612.YmSystem

class ConversionEffectsTest {
    private data class Converted(val cell: Cell, val issues: List<ConversionIssue>, val target: Song)

    private fun at(song: Song, channel: Int, row: Int, cell: Cell): Song = song.copy(patterns =
        song.patterns.mapIndexed { index, pattern -> if (index != 0) pattern else pattern.copy(tracks =
            pattern.tracks.mapIndexed { ch, track -> if (ch != channel) track else track.mapIndexed { r, old ->
                if (r == row) cell else old
            } }) })

    private fun convert(from: SoundSystem, to: SoundSystem, cell: Cell, sourceChannel: Int = 0,
                        targetChannel: Int = 0, row: Int = 7): Converted {
        val source = at(from.blank().copy(orders = listOf(0, 0, 0)), sourceChannel, row, cell)
        val destination = to.blank().copy(orders = source.orders)
        val issues = mutableListOf<ConversionIssue>()
        val target = destination.copy(ticksPerRow = ConversionEffects.speed(source, destination, issues))
        val result = ConversionEffects.cell(source, target, ConversionLane(sourceChannel, targetChannel),
            0, row, cell, cell.withoutEffects(), issues)
        return Converted(result, issues, target)
    }

    private fun Converted.hasLoss(column: Int, row: Int = 7): Boolean = issues.any {
        it.impact == ConversionImpact.LOSS && it.location == "Pattern 1, channel 1, row $row, effect ${column + 1}"
    }

    @Test fun baseSpeedRetainsTicksAndReportsClockOnlyOnce() {
        val source = Opl3System.blank().copy(ticksPerRow = 12)
        val issues = mutableListOf<ConversionIssue>()
        assertEquals(12, ConversionEffects.speed(source, GbSystem.blank(), issues))
        assertEquals(1, issues.count { it.location == "Clock" })
        assertTrue(issues.single { it.location == "Clock" }.detail.contains("not wall-clock"))
    }

    @Test fun baseSpeedFitsRealLegacyDriverBounds() {
        val issues = mutableListOf<ConversionIssue>()
        assertEquals(31, ConversionEffects.speed(Opl3System.blank().copy(ticksPerRow = 255), NesSystem.blank(), issues))
        assertEquals(3, ConversionEffects.speed(Opl3System.blank().copy(ticksPerRow = 1), SidSystem.blank(), issues))
        assertEquals(127, ConversionEffects.speed(Opl3System.blank().copy(ticksPerRow = 255), SidSystem.blank(), issues))
        assertEquals(3, issues.count { it.location == "Speed" })
    }

    @Test fun gbFlowUsesOneBasedAddressesAndBZeroMeansNextOrder() {
        assertEquals(Cell(effect = 11, param = 0), convert(GbSystem, Opl3System, Cell(effect = 11, param = 1)).cell)
        assertEquals(Cell(effect = 13, param = 0), convert(GbSystem, Opl3System, Cell(effect = 11, param = 0)).cell)
        assertEquals(Cell(effect = 13, param = 63), convert(GbSystem, Opl3System, Cell(effect = 13, param = 64)).cell)
        assertEquals(Cell(effect = 11, param = 1), convert(Opl3System, GbSystem, Cell(effect = 11, param = 0)).cell)
        assertEquals(Cell(effect = 13, param = 64), convert(Opl3System, GbSystem, Cell(effect = 13, param = 63)).cell)
    }

    @Test fun gbFlowBoundaryIsReportedRatherThanWrapped() {
        val result = convert(Opl3System, GbSystem, Cell(effect = 13, param = 64))
        assertFalse(result.cell.hasAnyEffect)
        assertTrue(result.hasLoss(0))
    }

    @Test fun gbNextOrderDoesNotEraseCompanionBreakRow() {
        val source = at(at(GbSystem.blank(), 0, 7, Cell(effect = 11, param = 0)), 1, 7, Cell(effect = 13, param = 9))
        val issues = mutableListOf<ConversionIssue>()
        val target = Opl3System.blank()
        val first = ConversionEffects.cell(source, target, ConversionLane(0, 0), 0, 7,
            source.patterns[0].tracks[0][7], Cell.BLANK, issues)
        val second = ConversionEffects.cell(source, target, ConversionLane(1, 1), 0, 7,
            source.patterns[0].tracks[1][7], Cell.BLANK, issues)
        assertFalse(first.hasAnyEffect)
        assertEquals(Cell(effect = 13, param = 8), second)
    }

    @Test fun laterGbNextOrderCancelsEarlierAbsoluteJumpEvenWhenLanesMove() {
        val base = GbSystem.blank().copy(orders = listOf(0, 0, 0))
        val source = at(at(base, 0, 7, Cell(effect = 11, param = 3)), 1, 7, Cell(effect = 11, param = 0))
        val issues = mutableListOf<ConversionIssue>()
        val target = Opl3System.blank().copy(orders = source.orders)
        val earlier = ConversionEffects.cell(source, target, ConversionLane(0, 3), 0, 7,
            source.patterns[0].tracks[0][7], Cell.BLANK, issues)
        val winner = ConversionEffects.cell(source, target, ConversionLane(1, 0), 0, 7,
            source.patterns[0].tracks[1][7], Cell.BLANK, issues)
        assertEquals(Cell.BLANK, earlier)
        assertEquals(Cell(effect = 13, param = 0), winner)
    }

    @Test fun banjoLastDelayWinsBeforeZeroElisionAndColumnBudget() {
        val immediate = Cell(note = 48, instrument = 1, effect = 16, param = 3).withEffect(1, 16, 0)
        assertEquals(Cell(note = 48, instrument = 1), convert(SmsSystem.PSG, Opl3System, immediate).cell)
        val delayed = Cell(note = 48, instrument = 1, effect = 16, param = 1).withEffect(1, 16, 3)
        assertEquals(Cell(note = 48, instrument = 1, effect = 16, param = 3),
            convert(SmsSystem.PSG, NesSystem, delayed).cell)
    }

    @Test fun nesCannotAcquireFlowCommandsByLetter() {
        val original = Cell(effect = 11, param = 1).withEffect(1, 13, 8)
        val result = convert(Opl3System, NesSystem, original, row = 27)
        assertFalse(result.cell.hasAnyEffect)
        assertTrue(result.hasLoss(0, 27))
        assertTrue(result.hasLoss(1, 27))
    }

    @Test fun sidConflictingLettersAndInstantTieAreNotCopied() {
        for ((code, param) in listOf(11 to 1, 12 to 15, 13 to 15, 14 to 0x22, 3 to 0)) {
            val result = convert(SidSystem, Opl3System, Cell(note = 48, instrument = 1, effect = code, param = param))
            assertEquals(48, result.cell.note)
            assertFalse(result.cell.hasAnyEffect)
            assertTrue("SID command $code must be a located loss", result.hasLoss(0))
        }
    }

    @Test fun sidFunktempoResumeIsNotFittedToFixedSpeed() {
        val result = convert(SidSystem, Opl3System, Cell(effect = 15, param = 1))
        assertFalse(result.cell.hasAnyEffect)
        assertTrue(result.hasLoss(0))
    }

    @Test fun speedEffectUsesNesBoundsWithoutChangingItsNote() {
        val result = convert(Opl3System, NesSystem, Cell(note = 48, instrument = 1, effect = 15, param = 255))
        assertEquals(Cell(note = 48, instrument = 1, effect = 15, param = 31), result.cell)
        assertNull(NesSystem.cellProblem(result.target, 0, result.cell))
    }

    @Test fun nativeLastSpeedWinsEvenWhenNesUsesFirstChannel() {
        val source = at(at(Opl3System.blank(), 0, 7, Cell(effect = 15, param = 5)), 1, 7, Cell(effect = 15, param = 9))
        val issues = mutableListOf<ConversionIssue>()
        val target = NesSystem.blank()
        val first = ConversionEffects.cell(source, target, ConversionLane(0, 0), 0, 7,
            source.patterns[0].tracks[0][7], Cell.BLANK, issues)
        val second = ConversionEffects.cell(source, target, ConversionLane(1, 1), 0, 7,
            source.patterns[0].tracks[1][7], Cell.BLANK, issues)
        assertFalse(first.hasAnyEffect)
        assertEquals(Cell(effect = 15, param = 9), second)
    }

    @Test fun nesFirstSpeedWinsWhenNativeUsesLastChannel() {
        val source = at(at(NesSystem.blank(), 0, 7, Cell(effect = 15, param = 5)), 1, 7, Cell(effect = 15, param = 9))
        val issues = mutableListOf<ConversionIssue>()
        val target = Opl3System.blank()
        val first = ConversionEffects.cell(source, target, ConversionLane(0, 0), 0, 7,
            source.patterns[0].tracks[0][7], Cell.BLANK, issues)
        val second = ConversionEffects.cell(source, target, ConversionLane(1, 1), 0, 7,
            source.patterns[0].tracks[1][7], Cell.BLANK, issues)
        assertEquals(Cell(effect = 15, param = 5), first)
        assertFalse(second.hasAnyEffect)
    }

    @Test fun nesSpeedConversionKeepsTheLastColumnOfTheFirstChannel() {
        val source = at(at(NesSystem.blank(), 0, 7, Cell(effect = 15, param = 5).withEffect(3, 15, 8)),
            1, 7, Cell().withEffect(2, 15, 9))
        val target = Opl3System.blank()
        val issues = mutableListOf<ConversionIssue>()
        val first = ConversionEffects.cell(source, target, ConversionLane(0, 0), 0, 7,
            source.patterns[0].tracks[0][7], Cell.BLANK, issues)
        val second = ConversionEffects.cell(source, target, ConversionLane(1, 1), 0, 7,
            source.patterns[0].tracks[1][7], Cell.BLANK, issues)
        assertEquals(Cell(effect = 15, param = 8), first)
        assertFalse(second.hasAnyEffect)
    }

    @Test fun nesConversionKeepsFourIndependentCommandsAndEffectiveVolume() {
        val source = Cell(48, 1, 12, 63).withEffect(1, 15, 8).withEffect(2, 16, 2).withEffect(3, 28, 5)
        val converted = convert(Opl3System, NesSystem, source)
        assertEquals(Cell(48, 1, 12, 15).withEffect(1, 15, 8).withEffect(2, 16, 2).withEffect(3, 28, 5), converted.cell)
        assertFalse(converted.issues.any { it.impact == ConversionImpact.LOSS })
        val duplicate = convert(NesSystem, Opl3System, Cell(48, 1, 10, 0x2f).withEffect(3, 12, 15))
        assertEquals(Cell(48, 1, 12, 63), duplicate.cell)
        assertFalse(duplicate.issues.any { it.impact == ConversionImpact.LOSS })
    }

    @Test fun gbNoteDelayAndCutTranslateByMeaning() {
        val delayed = convert(GbSystem, Opl3System, Cell(note = 48, instrument = 1, effect = 7, param = 3))
        assertEquals(Cell(note = 48, instrument = 1, effect = 16, param = 3), delayed.cell)
        val cut = convert(GbSystem, Opl3System, Cell(effect = 14, param = 2))
        assertEquals(Cell(effect = 28, param = 2), cut.cell)
        assertTrue(cut.issues.any { it.location.endsWith("effect 1") && it.detail.contains("release") })
    }

    @Test fun banjoDelayAndCutAreAdditiveNotAbsolute() {
        val source = Cell(note = 48, instrument = 1, effect = 16, param = 3).withEffect(1, 28, 2)
        val native = convert(SmsSystem.PSG, Opl3System, source)
        assertEquals(source.withEffect(1, 28, 5), native.cell)
        val sms = convert(Opl3System, SmsSystem.PSG, source.withEffect(1, 28, 5))
        assertEquals(source, sms.cell)
        assertNull(SmsSystem.PSG.cellProblem(sms.target, 0, sms.cell))
    }

    @Test fun cutBeforeDelayedNativeRowNeverBecomesAnAudibleEarlyCut() {
        val source = Cell(note = 48, instrument = 1, effect = 16, param = 3).withEffect(1, 28, 2)
        val result = convert(Opl3System, NesSystem, source)
        assertEquals(Cell(note = 48, instrument = 1, effect = 16, param = 3), result.cell)
        assertTrue(result.issues.any { it.location.endsWith("effect 2") && it.detail.contains("never fires") })
    }

    @Test fun banjoImmediateCutBecomesOffNotRejectedSZero() {
        val result = convert(Opl3System, SmsSystem.PSG, Cell(note = 48, instrument = 1, effect = 28, param = 0))
        assertEquals(Cell(note = Cell.CUT), result.cell)
        assertNull(SmsSystem.PSG.cellProblem(result.target, 0, result.cell))
    }

    @Test fun gbOffOwnsItsEffectColumnAndNeverDisappears() {
        val result = convert(Opl3System, GbSystem, Cell(note = Cell.CUT, effect = 15, param = 5))
        assertEquals(Cell(note = Cell.CUT), result.cell)
        assertTrue(result.hasLoss(0))
        assertNull(GbSystem.cellProblem(result.target, 0, result.cell))
    }

    @Test fun twoColumnDestinationPrioritizesSpeedAndCutOverModulation() {
        val source = Cell(note = 48, instrument = 1, effect = 0, param = 0x37)
            .withEffect(1, 4, 0x23).withEffect(2, 15, 6).withEffect(3, 28, 3)
        val result = convert(Opl3System, TiaNtscSystem, source)
        assertEquals(Cell(note = 48, instrument = 1, effect = 15, param = 6).withEffect(1, 28, 3), result.cell)
        assertTrue(result.hasLoss(0))
        assertTrue(result.hasLoss(1))
    }

    @Test fun nativeFineZeroIsNegativeBendNotBlank() {
        val result = convert(Opl3System, OpmSystem, Cell(note = 48, instrument = 1, effect = 25, param = 0))
        assertEquals(25, result.cell.effect)
        assertEquals(0, result.cell.param)
        assertTrue(result.cell.hasEffect)
        assertFalse(convert(Opl3System, OpmSystem, Cell.BLANK).cell.hasAnyEffect)
    }

    @Test fun periodFineAndVibratoAreNotNativePitchBytes() {
        assertTrue(convert(GbSystem, Opl3System, Cell(note = 48, instrument = 1, effect = 4, param = 15)).hasLoss(0))
        assertTrue(convert(NesSystem, Opl3System, Cell(note = 48, instrument = 1, effect = 25, param = 129)).hasLoss(0))
        assertTrue(convert(Opl3System, NesSystem, Cell(note = 48, instrument = 1, effect = 4, param = 0x24)).hasLoss(0))
    }

    @Test fun nativeAndBanjoSlidesScaleTheirNominalUnits() {
        assertEquals(8, convert(Opl3System, SmsSystem.PSG, Cell(effect = 1, param = 16)).cell.param)
        assertEquals(16, convert(SmsSystem.PSG, Opl3System, Cell(effect = 1, param = 8)).cell.param)
        val saturated = convert(SmsSystem.PSG, Opl3System, Cell(effect = 1, param = 255))
        assertEquals(255, saturated.cell.param)
        assertTrue(saturated.issues.any { it.location.endsWith("effect 1") && it.detail.contains("saturation") })
    }

    @Test fun volumeUsesDestinationRangeNotSameHexValue() {
        val result = convert(Opl3System, YmSystem, Cell(note = 48, instrument = 1, effect = 12, param = 63))
        assertEquals(127, result.cell.param)
        assertNull(YmSystem.cellProblem(result.target, 0, result.cell))
    }

    @Test fun nesAIsNotNativeVolumeSlide() {
        val result = convert(NesSystem, Opl3System, Cell(note = 48, instrument = 1, effect = 10, param = 0xf0))
        assertFalse(result.cell.hasAnyEffect)
        assertTrue(result.hasLoss(0))
    }

    @Test fun banjoSpeedPhaseChangeIsExplicit() {
        val result = convert(Opl3System, SmsSystem.PSG, Cell(effect = 15, param = 8))
        assertEquals(Cell(effect = 15, param = 8), result.cell)
        assertTrue(result.issues.any { it.location.endsWith("effect 1") && it.detail.contains("next row") })
    }

    @Test fun safeApuPromotionRetainsPeriodFinePitch() {
        val source = Cell(note = 48, instrument = 1, effect = 25, param = 129)
            .withEffect(1, 12, 9).withEffect(2, 4, 0x63).withEffect(3, 15, 8)
        val result = convert(NesSystem, NesVrc6System, source)
        assertEquals(source, result.cell)
        assertFalse(result.issues.any { it.impact == ConversionImpact.LOSS })
    }

    @Test fun safeOpnPromotionMovesSsgFlowWithoutChangingItsMeaning() {
        val source = Cell(effect = 13, param = 9)
        val result = convert(OpnSystem, OpnaSystem, source, sourceChannel = 3, targetChannel = 6)
        assertEquals(source, result.cell)
        assertNull(OpnaSystem.cellProblem(result.target, 6, result.cell))
    }

    @Test fun banjoFirstJumpAndBreakPrecedenceSurviveConversion() {
        val source = Cell(effect = 11, param = 1).withEffect(1, 11, 2).withEffect(2, 13, 0)
        val result = convert(SmsSystem.PSG, Opl3System, source)
        assertEquals(Cell(effect = 11, param = 1), result.cell)
    }

    @Test fun hiddenRowsHaveFullLossCoordinates() {
        val original = Cell(effect = 18, param = 1)
        val populated = at(Opl3System.blank(), 0, 63, original)
        val source = populated.copy(patterns = populated.patterns.map { it.copy(length = 1) })
        val issues = mutableListOf<ConversionIssue>()
        val result = ConversionEffects.cell(source, GbSystem.blank(), ConversionLane(0, 0), 0, 63,
            original, Cell.BLANK, issues)
        assertFalse(result.hasAnyEffect)
        assertTrue(issues.any { it.impact == ConversionImpact.LOSS &&
            it.location == "Pattern 1, channel 1, row 63, effect 1" })
    }

    @Test fun lastNativeCutSurvivesOneColumnBudget() {
        val result = convert(Opl3System, GbSystem, Cell(effect = 28, param = 1).withEffect(1, 28, 3))
        assertEquals(Cell(effect = 14, param = 3), result.cell)
    }

    @Test fun nativeCutBeyondNesRangeIsNotWrapped() {
        val result = convert(Opl3System, NesSystem, Cell(effect = 28, param = 32))
        assertFalse(result.cell.hasAnyEffect)
        assertTrue(result.hasLoss(0))
    }

    @Test fun gbDelayZeroDoesNotInventAnAudibleAttack() {
        val result = convert(GbSystem, Opl3System, Cell(note = 48, instrument = 1, effect = 7, param = 0))
        assertEquals(Cell.BLANK, result.cell)
        assertTrue(result.issues.any { it.location.endsWith("effect 1") && it.detail.contains("never triggers") })
    }

    @Test fun banjoSwingLossIsReportedAsTimingNotHardware() {
        val result = convert(SmsSystem.PSG, Opl3System, Cell(effect = 24, param = 9))
        assertFalse(result.cell.hasAnyEffect)
        assertTrue(result.hasLoss(0))
        assertTrue(result.issues.any { it.location.endsWith("effect 1") && it.detail.contains("odd-row speed") })
    }
}
