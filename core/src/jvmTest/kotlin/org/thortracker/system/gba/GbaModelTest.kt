package org.thortracker.system.gba

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Fx
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Tuning
import org.thortracker.system.ChannelKind

class GbaModelTest {
    @Test fun blankAndDemoHavePlayableOriginalBanks() {
        for (song in listOf(GbaSystem.blank(), GbaSystem.demo())) {
            assertEquals(8, song.system.channels.size)
            assertTrue(song.system.channels.all { it.kind == ChannelKind.SAMPLE && it.tonal })
            assertEquals(64, song.gba.instruments.size)
            assertEquals(6, song.gba.samples.size)
            assertTrue(GbaSystem.structuralErrors(song).toString(), GbaSystem.structuralErrors(song).isEmpty())
            assertTrue(GbaSystem.errors(song).toString(), GbaSystem.errors(song).isEmpty())
            for (slot in 1..6) assertNull(GbaSystem.engine.auditionProblem(song, 0, 48, slot, 0))
        }
        assertEquals(GbaSamples.starterBank(), GbaSamples.starterBank())
        assertTrue(GbaSystem.demo().patterns.flatMap { it.tracks }.flatten().any { it.note == Cell.CUT })
        assertEquals(0..95, GbaSystem.noteRange)
        assertEquals(1, GbaSystem.effectColumns(0))
        assertEquals(50.0, GbaSystem.ticksPerSecond(GbaSystem.blank()), 0.0)
    }

    @Test fun projectPreservesPcmKeysAndBothEnvelopesExactly() {
        val source = GbaSystem.blank()
        val envelope = GbaEnvelope(listOf(GbaEnvelopePoint(0, 31), GbaEnvelopePoint(4, 64), GbaEnvelopePoint(255, 0)), true, 1, 0, 2)
        val samples = source.gba.samples.toMutableList().also {
            it[0] = GbaSample("Exact signed edges", listOf(-128, -1, 0, 1, 127), 22050, 37, -113, 53, 173, 1, 5)
        }
        val instruments = source.gba.instruments.toMutableList().also {
            it[0] = GbaInstrument("Drum map", List(96) { note -> if (note % 3 == 0) 0 else -1 }, envelope, envelope.copy(sustain = -1), 1234)
        }
        val song = source.copy(sound = source.gba.copy(samples = samples, instruments = instruments, bpm = 173))
        val text = ProjectCodec.encode(song)
        assertEquals(song, ProjectCodec.decode(text))
        val encodedSound = Json.encodeToString(GbaSound.serializer(), song.gba)
        assertEquals(song.gba, Json.decodeFromString(GbaSound.serializer(), encodedSound))
    }

    @Test fun untrustedCollectionsAreBoundedBeforeTypedSampleAllocation() {
        val overKeys = JsonArray(List(97) { JsonPrimitive(-1) })
        assertThrows(SerializationException::class.java) {
            Json.decodeFromJsonElement(GbaInstrument.serializer(), JsonObject(mapOf("keymap" to overKeys)))
        }
        val points = JsonArray(List(13) { JsonObject(mapOf("tick" to JsonPrimitive(it), "value" to JsonPrimitive(64))) })
        assertThrows(SerializationException::class.java) {
            Json.decodeFromJsonElement(GbaEnvelope.serializer(), JsonObject(mapOf("points" to points)))
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromJsonElement(GbaSound.serializer(), JsonObject(mapOf("samples" to JsonArray(List(129) { JsonObject(emptyMap()) }))))
        }
        val pcm = JsonArray(List(GbaSound.MAX_PCM_BYTES / 2 + 1) { JsonPrimitive(0) })
        val sample = JsonObject(mapOf("pcm" to pcm))
        val invalid = JsonObject(mapOf("samples" to JsonArray(listOf(sample, sample))))
        val failure = assertThrows(SerializationException::class.java) { Json.decodeFromJsonElement(GbaSound.serializer(), invalid) }
        assertTrue(failure.message.orEmpty().contains("512 KiB"))
    }

    @Test fun invalidSampleMetadataAndMappingsAreActionable() {
        val sample = GbaSample(pcm = listOf(-128, 0, 127))
        assertNull(GbaRules.sampleProblem(sample))
        for (bad in listOf(sample.copy(pcm = listOf(128)), sample.copy(sampleRate = 48001), sample.copy(rootNote = 96),
            sample.copy(fineTune = 128), sample.copy(volume = 65), sample.copy(pan = 256), sample.copy(loopStart = 2, loopEnd = 1),
            sample.copy(loopEnd = 4))) assertNotNull(GbaRules.sampleProblem(bad))
        assertNotNull(GbaRules.instrumentProblem(GbaInstrument(keymap = List(96) { 1 }), 1))
        assertNotNull(GbaRules.envelopeProblem(GbaEnvelope(listOf(GbaEnvelopePoint(1, 0), GbaEnvelopePoint(1, 64)))))
        assertNotNull(GbaRules.envelopeProblem(GbaEnvelope(sustain = 2)))
        val song = GbaSystem.blank()
        assertTrue(GbaSystem.engine.auditionProblem(song, 0, 48, 7, 0)!!.contains("unmapped"))
        assertTrue(GbaSystem.engine.auditionProblem(song, 0, 48, 1, 1)!!.contains("microtuning"))
        assertNotNull(GbaSystem.cellProblem(song, 0, Cell(48, 7)))
        assertNotNull(GbaSystem.cellProblem(song, 0, Cell(96, 1)))
        assertNull(GbaSystem.cellProblem(song, 0, Cell(Cell.CUT)))
    }

    @Test fun supportedEffectsRejectConverterLossAndExtraColumns() {
        val song = GbaSystem.blank()
        for ((effect, param) in listOf(1 to 0xE0, 2 to 0xFF, 5 to 0x11, 6 to 0x11, 10 to 0x11,
            12 to 65, 13 to 0x0A, 13 to 0x64, 14 to 0x10, 14 to 0x20, 14 to 0x30, 14 to 0x40, 14 to 0x47, 14 to 0x50, 14 to 0x70, 14 to 0x77, 14 to 0xF1, 15 to 0, 16 to 0)) {
            assertNotNull("$effect/$param", GbaSystem.cellProblem(song, 0, Cell(effect = effect, param = param)))
        }
        for ((effect, param) in listOf(0 to 0x37, 1 to 0, 3 to 0xFF, 4 to 0x47, 8 to 0xFF, 12 to 64,
            13 to 0x63, 14 to 0x11, 14 to 0x80, 14 to 0xC0, 15 to 31, 15 to 32)) {
            assertNull("$effect/$param", GbaSystem.cellProblem(song, 0, Cell(effect = effect, param = param)))
        }
        assertNotNull(GbaSystem.cellProblem(song, 0, Cell(effect = 9, param = 1)))
        assertNotNull(GbaSystem.cellProblem(song, 0, Cell(48, 1, 9, 1)))
        assertNull(GbaSystem.cellProblem(song, 0, Cell(48, 2, 9, 1)))
        assertNotNull(GbaSystem.cellProblem(song, 0, Cell(more = listOf(Fx(12, 32)))))
        assertTrue(GbaSystem.errors(song.copy(ticksPerRow = 32)).any { it.contains("speed", true) })
        assertTrue(GbaSystem.errors(song.copy(sound = song.gba.copy(bpm = 31))).any { it.contains("BPM") })
        val micro = song.copy(tuning = Tuning("Quarter tones", (1..24).map { it * 50.0 }))
        assertTrue(GbaSystem.errors(micro).any { it.contains("microtuning") })
        assertNull(GbaSystem.fine.write(Cell(), 48, 1))
    }

    @Test fun parkedOrdersRewriteJumpsAndLoopAndPatternLoop() {
        val source = GbaSystem.blank()
        val tracks = source.patterns[0].tracks.mapIndexed { ch, track ->
            if (ch == 0) track.toMutableList().also { it[0] = Cell(effect = 11, param = 1) } else track
        }
        val song = source.copy(patterns = listOf(source.patterns[0].copy(tracks = tracks)), orders = listOf(0, 0, 0, 0), parkedOrders = listOf(1, 3), loopOrder = 1)
        val arranged = GbaSystem.arranged(song)
        assertEquals(listOf(0, 0), arranged.orders)
        assertEquals(1, arranged.loopOrder)
        assertEquals(1, arranged.patterns[0].tracks[0][0].param)
        assertTrue(arranged.parkedOrders.isEmpty())
        val loop = GbaSystem.patternLoop(song, 2)
        assertEquals(listOf(0), loop.orders)
        assertEquals(0, loop.patterns[0].tracks[0][0].param)
        assertEquals(0, loop.loopOrder)
        assertTrue(GbaSystem.errors(song.copy(parkedOrders = song.orders.indices.toList())).any { it.contains("Every order") })
    }

    @Test fun converterBudgetAndQuantizationAreVisible() {
        val song = GbaSystem.blank()
        val instruments = song.gba.instruments.toMutableList().also { it[0] = it[0].copy(fadeout = 1) }
        assertTrue(GbaSystem.warnings(song.copy(sound = song.gba.copy(instruments = instruments))).isNotEmpty())
        val largeBank = List(17) { GbaSample(pcm = listOf(1, -1)) }
        instruments[0] = instruments[0].copy(keymap = List(96) { if (it < 84) it % 17 else 0 })
        assertTrue(GbaSystem.errors(song.copy(sound = song.gba.copy(samples = largeBank, instruments = instruments))).isNotEmpty())
    }
}
