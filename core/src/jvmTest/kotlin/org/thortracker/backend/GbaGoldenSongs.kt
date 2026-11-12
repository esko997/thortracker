package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.gba.GbaEnvelope
import org.thortracker.system.gba.GbaEnvelopePoint
import org.thortracker.system.gba.GbaInstrument
import org.thortracker.system.gba.GbaSample
import org.thortracker.system.gba.GbaSound

/** No copyrighted assets: deterministic integer PCM and deliberately visible format edge cases. */
object GbaGoldenSongs {
    fun features(): Song {
        val samples = listOf(
            GbaSample(name = "Loop seven", pcm = List(17) { it * 13 - 100 }, sampleRate = 8363, rootNote = 48, loopStart = 3, loopEnd = 10, pan = 191),
            GbaSample(name = "One shot", pcm = List(769) { (it * 29 % 256) - 128 }, sampleRate = 22050, rootNote = 60, fineTune = -17, volume = 48),
            GbaSample(name = "Same PCM new pitch", pcm = List(17) { it * 13 - 100 }, sampleRate = 8363, rootNote = 36, loopStart = 3, loopEnd = 10),
        )
        val volume = GbaEnvelope(points = listOf(GbaEnvelopePoint(0, 64), GbaEnvelopePoint(7, 40), GbaEnvelopePoint(25, 0)), enabled = true, sustain = 1)
        val pan = GbaEnvelope(points = listOf(GbaEnvelopePoint(0, 32), GbaEnvelopePoint(8, 56), GbaEnvelopePoint(16, 32)), enabled = true, loopStart = 0, loopEnd = 2)
        val instruments = List(64) { slot -> when (slot) {
            0 -> GbaInstrument(name = "Sustain loop", keymap = List(96) { 0 }, volumeEnvelope = volume, panEnvelope = pan, fadeout = 97)
            1 -> GbaInstrument(name = "Drum map", keymap = List(96) { if (it == 48) 1 else if (it == 49) -1 else 0 })
            63 -> GbaInstrument(name = "Pitch duplicate", keymap = List(96) { 2 })
            else -> GbaInstrument()
        } }
        val rows = mapOf(
            (0 to 0) to Cell(48, 1), (1 to 0) to Cell(48, 1), // repeat compressor cache
            (2 to 0) to Cell(effect = 4, param = 0x24), (3 to 0) to Cell(effect = 4),
            (4 to 0) to Cell(Cell.CUT), (5 to 0) to Cell(60, 64),
            (0 to 1) to Cell(48, 2), (4 to 1) to Cell(49, 2), // explicitly silent key
            (7 to 0) to Cell(effect = 14, param = 0x60), (9 to 0) to Cell(effect = 14, param = 0x62),
            (12 to 0) to Cell(effect = 13, param = 0x03),
        )
        val first = pattern(16, rows)
        val second = pattern(10, mapOf((3 to 0) to Cell(95, 1), (4 to 0) to Cell(0, 1), (5 to 0) to Cell(Cell.CUT), (9 to 0) to Cell(effect = 11, param = 2)))
        return Song(title = "GBA format edges", ticksPerRow = 6, loopOrder = 2, patterns = listOf(first, pattern(8, emptyMap()), second), orders = listOf(0, 1, 2), parkedOrders = listOf(1), sound = GbaSound(instruments = instruments, samples = samples, bpm = 137))
    }

    fun effects(): Song {
        val effects = listOf(
            0 to 0x37, 1 to 0x12, 1 to 0, 2 to 0x08, 3 to 0x10, 4 to 0x47,
            5 to 0x10, 6 to 0x02, 7 to 0x34, 8 to 0xC0, 9 to 1, 10 to 0x10,
            12 to 0x20, 14 to 0x13, 14 to 0x11, 14 to 0x24, 14 to 0x21,
            14 to 0x8C, 14 to 0x93, 14 to 0xA2,
            14 to 0xB2, 14 to 0xC3, 14 to 0xD2, 14 to 0xE1, 15 to 3, 15 to 150,
        )
        val sample = GbaSample(name = "Offset ramp", pcm = List(1024) { (it * 7 % 256) - 128 })
        val inst = GbaInstrument(name = "Ramp", keymap = List(96) { 0 })
        val rows = effects.mapIndexed { i, (effect, param) -> (i to 0) to Cell(48, 1, effect, param) }.toMap()
        return Song(title = "GBA effects", patterns = listOf(pattern(32, rows)), orders = listOf(0), sound = GbaSound(samples = listOf(sample), instruments = List(64) { if (it == 0) inst else GbaInstrument() }))
    }

    fun pattern(length: Int, cells: Map<Pair<Int, Int>, Cell>): Pattern = Pattern(length = length, tracks = List(8) { channel -> List(Songs.ROWS) { row -> cells[row to channel] ?: Cell() } })
}
