package org.thortracker.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.HugeLowering
import org.thortracker.backend.UgeExporter

class ParkedOrdersTest {
    private fun jump(order: Int) = Cell(Cell.EMPTY, 0, HugeRules.FX_POSITION_JUMP, order + 1)

    /** Five orders playing patterns 0-4; pattern 0 holds jumps aimed at every order. */
    private fun five(): Song {
        var song = TestSongs.patterns(5)
        for (o in 0..4) song = TestSongs.withCell(song, 0, o % 4, 10 + o, jump(o))
        return song
    }

    @Test fun nothingParkedIsTheSongItself() {
        val song = five()
        assertSame(song, HugeRules.arranged(song))
        assertEquals(listOf(0, 1, 2, 3, 4), HugeRules.playedOrders(song))
    }

    @Test fun parkedOrdersLeaveTheArrangementAndEverythingThatNamesAnOrderFollows() {
        val song = five().copy(parkedOrders = listOf(1, 3), loopOrder = 3)
        assertEquals(listOf(0, 2, 4), HugeRules.playedOrders(song))
        val arranged = HugeRules.arranged(song)
        assertEquals(listOf(0, 2, 4), arranged.orders)
        assertEquals(emptyList<Int>(), arranged.parkedOrders)
        assertEquals("a parked loop target loops to the next played order", 2, arranged.loopOrder)
        // Jumps to orders 0..4 → played 0, next after 1 (=2 → 1), 2 → 1, next after 3 (=4 → 2), 4 → 2.
        val params = (0..4).map { o -> arranged.patterns[0].tracks[o % 4][10 + o].param }
        assertEquals(listOf(1, 2, 2, 3, 3), params)
        assertEquals(song.patterns[0], five().patterns[0]) // the document is untouched
    }

    @Test fun aJumpToAParkedOrderAfterTheLastPlayedOneFollowsTheLoop() {
        val song = five().copy(parkedOrders = listOf(4), loopOrder = 1)
        val arranged = HugeRules.arranged(song)
        assertEquals(1, arranged.loopOrder)
        assertEquals(2, arranged.patterns[0].tracks[0][14].param) // B05 → order 4 parked → loop order 1 → B02
        assertEquals(0, HugeRules.arranged(song.copy(loopOrder = 4)).loopOrder)
    }

    @Test fun playbackAndExportUseOnlyTheArrangement() {
        val song = five().copy(parkedOrders = listOf(1, 3))
        val arranged = HugeRules.arranged(song)
        assertEquals(emptyList<String>(), SongValidator.errors(song))
        val parked = HugeLowering.lower(song)
        val plain = HugeLowering.lower(arranged)
        assertArrayEquals(plain.orderPatterns, parked.orderPatterns)
        assertArrayEquals(UgeExporter.export(arranged), UgeExporter.export(song))
    }

    @Test fun onlyPlayedOrdersCanBlockExport() {
        // Pattern 1 jumps past the end of the song, which blocks export only while an order plays it.
        val song = TestSongs.withCell(TestSongs.patterns(3), 1, 0, 5, jump(20))
        assertTrue(SongValidator.errors(song).single().contains("would jump to order 20"))
        val parked = song.copy(parkedOrders = listOf(1))
        assertEquals(emptyList<String>(), SongValidator.errors(parked))
        assertTrue(SongValidator.warnings(parked).any { it.contains("which the song no longer has") })
    }

    @Test fun everyOrderParkedIsReportedAndNeverLowered() {
        val song = TestSongs.patterns(2).copy(parkedOrders = listOf(0, 1))
        assertTrue(SongValidator.errors(song).single().contains("Every order is parked"))
        assertThrows(IllegalArgumentException::class.java) { HugeLowering.lower(song) }
    }

    @Test fun parkedOrdersMustNameAscendingExistingOrders() {
        val song = TestSongs.patterns(3)
        for (bad in listOf(listOf(3), listOf(2, 1), listOf(1, 1), listOf(-1))) {
            assertTrue("$bad", SongValidator.structuralErrors(song.copy(parkedOrders = bad)).any { it.contains("Parked orders") })
        }
    }

    @Test fun parkedOrdersRoundTripAndNeedFormatSeven() {
        val song = TestSongs.patterns(3).copy(parkedOrders = listOf(1))
        val text = ProjectCodec.encode(song)
        assertEquals(song, ProjectCodec.decode(text))
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val six = kotlinx.serialization.json.JsonObject(root.toMutableMap().also { it["version"] = kotlinx.serialization.json.JsonPrimitive(6) })
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(six.toString()) }
        val plain = ProjectCodec.encode(TestSongs.patterns(3))
        val plainSix = plain.replaceFirst("\"version\":7", "\"version\":6")
        assertEquals(emptyList<Int>(), ProjectCodec.decode(plainSix).parkedOrders)
    }
}
