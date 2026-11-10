package org.thortracker.conversion

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Scl
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.SoundSystems
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.opl3.Opl3System
import org.thortracker.system.opn.OpnSystem
import org.thortracker.system.opn.OpnaSystem
import org.thortracker.system.tia.TiaNtscSystem
import org.thortracker.system.ws.WsSystem
import org.thortracker.system.ws.WscSystem

class SongConversionTest {
    private fun put(song: Song, pattern: Int, channel: Int, row: Int, cell: Cell): Song = song.copy(
        patterns = song.patterns.mapIndexed { p, value -> if (p != pattern) value else value.copy(
            tracks = value.tracks.mapIndexed { ch, track -> if (ch != channel) track else track.mapIndexed { r, old -> if (r == row) cell else old } }) })

    private fun melody(system: SoundSystem = GbSystem): Song {
        var song = system.blank().copy(title = "Conversion melody", artist = "Author", comment = "Keep originals")
        song = put(song, 0, 0, 0, Cell(60, 1))
        song = put(song, 0, 0, 2, Cell(64, 1))
        return put(song, 0, 0, 4, Cell(Cell.CUT))
    }

    @Test fun everyTargetReceivesAValidatedAudibleMelodyAndExplicitOff() {
        val source = melody()
        val original = ProjectCodec.encode(source)
        for (target in SoundSystems.ALL) {
            val result = SongConversion.preview(source, target)
            val report = SongConversion.report(source, target, result)
            assertTrue(report, result.canApply)
            val song = requireNotNull(result.song)
            assertEquals(target.id, song.system.id)
            assertTrue(report, target.structuralErrors(song).isEmpty())
            assertTrue(report, target.errors(song).isEmpty())
            val lane = result.lanes.single { it.source == 0 }
            val track = song.patterns[0].tracks[lane.target]
            assertTrue(report, track[0].note >= 0)
            assertTrue(report, track[0].instrument > 0)
            assertTrue(report, target.instrumentName(song, target.channels[lane.target].bank, track[0].instrument) != null)
            assertEquals(report, Cell.CUT, track[4].note)
            assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
        }
        assertEquals(original, ProjectCodec.encode(source))
    }

    @Test fun hiddenUnusedPatternsAndAllArrangementMetadataSurvivePromotion() {
        var source = melody(WsSystem)
        source = source.copy(patterns = listOf(source.patterns[0].copy(name = "Short", length = 8),
            source.patterns[0].copy(name = "Unused", length = 3)), orders = listOf(0, 0, 0), loopOrder = 1,
            parkedOrders = listOf(2), tuning = Scl.parse("Test\n2\n700.0\n1200.0\n", "Fifths"))
        source = put(source, 1, 0, 63, Cell(69, 1))
        val result = SongConversion.preview(source, WscSystem)
        assertTrue(SongConversion.report(source, WscSystem, result), result.canApply)
        val converted = requireNotNull(result.song)
        assertEquals(source.copy(sound = converted.sound, patterns = converted.patterns), converted)
        assertEquals(source.patterns.map { it.name }, converted.patterns.map { it.name })
        assertEquals(source.patterns.map { it.length }, converted.patterns.map { it.length })
        assertEquals(source.patterns.map { it.scale }, converted.patterns.map { it.scale })
        source.patterns.forEachIndexed { p, pattern ->
            assertEquals(pattern.tracks, converted.patterns[p].tracks.take(4))
            assertTrue(converted.patterns[p].tracks[4].all { it.isBlank })
        }
        assertEquals(69, converted.patterns[1].tracks[0][63].note)
    }

    @Test fun realDemosPromoteWithoutLosingTheirRetainedTracks() {
        for ((system, target) in listOf(NesSystem to NesVrc6System, WsSystem to WscSystem, OpnSystem to OpnaSystem)) {
            val source = system.demo()
            val encoded = ProjectCodec.encode(source)
            val result = SongConversion.preview(source, target)
            val report = SongConversion.report(source, target, result)
            assertTrue(report, result.canApply)
            val converted = requireNotNull(result.song)
            assertEquals(source.orders, converted.orders)
            assertEquals(source.loopOrder, converted.loopOrder)
            for (lane in result.lanes) source.patterns.forEachIndexed { p, pattern ->
                assertEquals(report, pattern.tracks[lane.source], converted.patterns[p].tracks[lane.target])
            }
            assertEquals(encoded, ProjectCodec.encode(source))
            assertEquals(converted, ProjectCodec.decode(ProjectCodec.encode(converted)))
        }
    }

    @Test fun realCrossFamilyDemosProducePlayableTargetsAndReportReplacements() {
        for (source in listOf(GbSystem.demo(), WsSystem.demo())) {
            val result = SongConversion.preview(source, Opl3System)
            val report = SongConversion.report(source, Opl3System, result)
            assertTrue(report, result.canApply)
            val converted = requireNotNull(result.song)
            assertTrue(report, Opl3System.errors(converted).isEmpty())
            assertTrue(report, converted.patterns.any { p -> p.tracks.any { t -> t.any { it.note >= 0 && it.instrument > 0 } } })
            assertTrue(report, result.issues.any { it.impact == ConversionImpact.LOSS })
            assertEquals(source.patterns.size, converted.patterns.size)
        }
    }

    @Test fun droppedLaneReportIncludesHiddenGlobalEffectsAndExactCoordinates() {
        val sounding = melody(Opl3System)
        val short = sounding.copy(patterns = sounding.patterns.map { it.copy(length = 8) })
        val source = put(short, 0, 8, 63, Cell(effect = 15, param = 9))
        val result = SongConversion.preview(source, TiaNtscSystem)
        assertTrue(result.issues.any { it.impact == ConversionImpact.LOSS &&
            it.location == "Pattern 1, channel 9, row 63" && it.detail.contains("F09") })
        assertTrue(SongConversion.report(source, TiaNtscSystem, result).contains("F09"))
    }

    @Test fun orderOverflowAndMalformedSourcesBlockInsteadOfTruncating() {
        val target = SoundSystems.ALL.minBy { it.maxOrders }
        val sourceSystem = SoundSystems.ALL.maxBy { it.maxOrders }
        assertTrue(sourceSystem.maxOrders > target.maxOrders)
        val source = sourceSystem.blank().copy(orders = List(target.maxOrders + 1) { 0 })
        val result = SongConversion.preview(source, target)
        assertFalse(result.canApply)
        assertNull(result.song)
        assertTrue(result.issues.any { it.impact == ConversionImpact.BLOCKER && it.detail.contains("orders") })
        val malformed = melody().copy(orders = listOf(999))
        val invalid = SongConversion.preview(malformed, Opl3System)
        assertFalse(invalid.canApply)
        assertTrue(invalid.issues.any { it.impact == ConversionImpact.BLOCKER && it.location == "Source" })
    }

    @Test fun instrumentCapacityBlocksWithoutMergingEditableIdentities() {
        var source = NesSystem.blank()
        for (slot in 1..16) source = put(source, 0, 0, slot - 1, Cell(60, slot))
        val before = ProjectCodec.encode(source)
        val result = SongConversion.preview(source, GbSystem)
        assertFalse(result.canApply)
        assertNull(result.song)
        assertTrue(result.issues.any { it.impact == ConversionImpact.BLOCKER && it.detail.contains("slot") })
        assertEquals(before, ProjectCodec.encode(source))
    }

    @Test fun identicalSourceSlotsStaySeparatelyEditableButRepeatedIdentityIsShared() {
        var source = put(melody(NesSystem), 0, 0, 2, Cell(64, 2))
        source = put(source, 0, 1, 0, Cell(60, 1))
        val result = SongConversion.preview(source, Opl3System)
        assertTrue(SongConversion.report(source, Opl3System, result), result.canApply)
        val converted = requireNotNull(result.song)
        val firstLane = result.lanes.single { it.source == 0 }.target
        val secondLane = result.lanes.single { it.source == 1 }.target
        val first = converted.patterns[0].tracks[firstLane][0].instrument
        val second = converted.patterns[0].tracks[firstLane][2].instrument
        assertNotEquals(first, second)
        assertEquals(first, converted.patterns[0].tracks[secondLane][0].instrument)
    }

    @Test fun unresolvedStartupNeverInventsAnAudibleEditorDefault() {
        val source = put(GbSystem.blank(), 0, 0, 0, Cell(60, 0))
        val result = SongConversion.preview(source, Opl3System)
        assertFalse(result.canApply)
        assertNull(result.song)
        assertTrue(result.issues.any { it.impact == ConversionImpact.BLOCKER &&
            it.location == "Pattern 1, channel 1, row 0" && it.detail.contains("initial sound") })
        assertEquals(0, source.patterns[0].tracks[0][0].instrument)
    }

    @Test fun sameSystemIsAnIdentityNoOp() {
        val source = GbSystem.demo()
        val result = SongConversion.preview(source, GbSystem)
        assertSame(source, result.song)
        assertTrue(result.issues.isEmpty())
    }
}
