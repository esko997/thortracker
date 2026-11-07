package org.thortracker.model

import org.thortracker.system.SoundSystems
import org.thortracker.system.gb.gb
import org.thortracker.system.gb.HugeRules
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.ChannelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SongsTest {
    @Test
    fun gbChannelsAndBanks() {
        val song = Songs.blank()
        assertTrue(song.system === GbSystem)
        assertEquals(listOf("Pulse 1", "Pulse 2", "Wave", "Noise"), GbSystem.channels.map { it.name })
        assertEquals(
            listOf(ChannelKind.PULSE, ChannelKind.PULSE, ChannelKind.WAVE, ChannelKind.NOISE),
            GbSystem.channels.map { it.kind },
        )
        assertEquals(listOf(true, true, true, false), GbSystem.channels.map { it.tonal })
        assertEquals(listOf("Pulse", "Wave", "Noise"), GbSystem.banks.map { it.name })
        assertEquals("Duty 25%", GbSystem.instrumentName(song, GbSystem.channels[1].bank, 2))
        assertEquals("Noise 1", GbSystem.instrumentName(song, GbSystem.channels[3].bank, 1))
        assertThrows(IllegalArgumentException::class.java) { SoundSystems.of("other") }
    }

    @Test
    fun blankSongShape() {
        val song = Songs.blank()
        assertEquals(1, song.patterns.size)
        assertEquals(listOf(0), song.orders)
        assertTrue(song.patterns[0].tracks.all { track -> track.size == Songs.ROWS && track.all { it.isBlank } })
        for (bank in listOf(song.gb.pulse, song.gb.wave, song.gb.noise)) {
            assertEquals(HugeRules.INSTRUMENT_SLOTS, bank.size)
        }
        assertEquals((0 until 15).toList(), song.gb.wave.map { it.wave })
        assertEquals(16, song.gb.waves.size)
        assertTrue(song.gb.waves.all { w -> w.size == 32 && w.all { it in 0..15 } })
        // hUGETracker 1.0.11 DefaultWaves[6] (sine) is preserved exactly.
        assertEquals(listOf(7, 10, 12, 13, 13, 11, 7, 5, 2, 1, 1, 3, 6, 8, 11, 13, 13, 12, 9, 7, 4, 1, 0, 1, 4, 7, 9, 12, 13, 13, 11, 8), song.gb.waves[6])
        assertEquals(Songs.ROWS, Songs.blankPattern(song).tracks[3].size)
        assertEquals(4, Songs.blankPattern(song, "X").tracks.size)
    }

    @Test
    fun demoIsACompleteArrangement() {
        val demo = Songs.demo()
        assertEquals(listOf(0, 1, 2, 1, 2), demo.orders)
        assertEquals(1, demo.loopOrder)
        for (ch in 0 until 4) {
            assertTrue("channel $ch has notes", demo.patterns.any { p -> p.tracks[ch].any { it.note >= 0 } })
        }
        val cells = demo.patterns.flatMap { it.tracks.flatten() }
        assertTrue(cells.any { it.note == Cell.CUT })
        for (effect in listOf(0x0, 0x3, 0x4, 0x9, 0xC, 0xE)) {
            assertTrue("demo uses effect %X".format(effect), cells.any { it.effect == effect && it.hasEffect })
        }
    }

    @Test
    fun noteNames() {
        assertEquals("C-0", Songs.noteName(0))
        assertEquals("C-3", Songs.noteName(36))
        assertEquals("C-3", Songs.noteName(GbSystem.noteRange.first))
        assertEquals("A#7", Songs.noteName(94))
        assertEquals("B-9", Songs.noteName(Songs.NOTE_LIMIT))
        assertEquals("???", Songs.noteName(Songs.NOTE_LIMIT + 1))
        assertEquals("B-8", Songs.noteName(GbSystem.noteRange.last))
        assertEquals("---", Songs.noteName(Cell.EMPTY))
    }

    @Test
    fun effectCatalogue() {
        val effects = GbSystem.effects
        assertEquals((0..15).toList(), effects.map { it.code })
        assertEquals(listOf(6), effects.filter { !it.supported }.map { it.code })
        val codes = { ch: Int -> GbSystem.effectsFor(ch).map { it.code } }
        assertEquals((0..15).filter { it != 6 }, codes(0))
        assertEquals((0..15).filter { it != 6 && it != 0xA }, codes(2))
        assertEquals(listOf(5, 7, 8, 9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF), codes(3))
        val song = Songs.blank()
        assertEquals("Arpeggio: note, +3, +7 semitones", GbSystem.describeEffect(song, 0, 0x0, 0x37))
        assertEquals("No effect", GbSystem.describeEffect(song, 0, 0x0, 0x00))
        assertEquals("Duty 75%", GbSystem.describeEffect(song, 1, 0x9, 0xC0))
        assertEquals("Load wave 03 and retrigger", GbSystem.describeEffect(song, 2, 0x9, 0x03))
        assertEquals("Noise width 7-bit", GbSystem.describeEffect(song, 3, 0x9, 0x08))
        assertEquals("Wave volume 50%", GbSystem.describeEffect(song, 2, 0xC, 0x07))
        assertEquals("Vibrato +3 units on 1 of every 4 ticks", GbSystem.describeEffect(song, 0, 0x4, 0x33))
        assertEquals("Vibrato +2 units on every tick (constant detune)", GbSystem.describeEffect(song, 0, 0x4, 0x02))
        assertEquals("Arpeggio: not supported on Noise", GbSystem.describeEffect(song, 3, 0x0, 0x37))
        assertEquals("Pan Pulse 1 L+R, Pulse 2 L, Wave R, Noise off", GbSystem.describeEffect(song, 0, 0x8, 0x34 or 0x01 or 0x10))
    }

    @Test
    fun cellProblemReportsRangesAndContext() {
        val song = Songs.blank()
        assertTrue(GbSystem.cellProblem(song, 0, Cell(note = 116))!!.contains("outside"))
        assertTrue(GbSystem.cellProblem(song, 0, Cell(60, 16))!!.contains("instrument 16"))
        assertFalse(GbSystem.cellProblem(song, 0, Cell(60, 1, 0x0, 0x37)) != null)
    }
}
