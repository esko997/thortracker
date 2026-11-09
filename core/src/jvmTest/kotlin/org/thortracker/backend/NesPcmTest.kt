package org.thortracker.backend

import org.thortracker.editor.EditorSession
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesMmc5DemoSong
import org.thortracker.system.nes.NesMmc5System
import org.thortracker.system.nes.NesPcmKit
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.Pcm
import org.thortracker.system.nes.PcmData
import org.thortracker.system.nes.PcmMapping
import org.thortracker.system.nes.PcmSample
import org.thortracker.system.nes.deletePcmSample
import org.thortracker.system.nes.nes
import org.thortracker.system.nes.spreadPcmMapping
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MMC5 PCM lane on the Kotlin side: WAV import into unsigned 8-bit PCM, the project model and its upgrade, the
 * image extension the native software player reads (layout of nes_mmc5_pcm.h), the export refusal for songs whose lane
 * plays versus seven-channel exports of songs whose lane is silent, the lane's speed, validation and the PCM edits.
 */
class NesPcmTest {
    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF
    private fun u16(b: ByteArray, at: Int) = u8(b, at) or (u8(b, at + 1) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)

    /** A RIFF/WAVE file of 16-bit PCM [samples] (per channel interleaved) at [rate]. */
    private fun wav16(samples: ShortArray, rate: Int, channels: Int = 1): ByteArray {
        val data = samples.size * 2
        val out = java.io.ByteArrayOutputStream()
        fun i32(v: Int) { for (k in 0 until 4) out.write(v shr (8 * k) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + data); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(channels); i32(rate); i32(rate * 2 * channels); i16(2 * channels); i16(16)
        out.write("data".toByteArray()); i32(data)
        for (s in samples) i16(s.toInt())
        return out.toByteArray()
    }

    private fun extension(song: Song): ByteArray {
        val e = NesMmc5System.engine
        val image = ByteArray(e.imageSize)
        e.lower(song, image)
        return image.copyOfRange(NesPlayer.MMC5.imageSize, image.size)
    }

    private fun withPcm(song: Song, cells: Map<Int, Cell>, pattern: Int = 0): Song {
        val p = song.patterns[pattern]
        val tracks = p.tracks.mapIndexed { ch, t -> if (ch != NesRules.MMC5_PCM) t else t.mapIndexed { r, c -> cells[r] ?: c } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[pattern] = p.copy(tracks = tracks) })
    }

    /** A blank MMC5 song with the PCM kit: sound 01 the drums (kick C-3, hat F#3), sound 02 the chromatic bass. */
    private fun kitSong(): Song {
        val base = NesMmc5System.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[0] = NesPcmKit.drums(0)
        bank[1] = NesPcmKit.bassSound(0)
        return base.copy(sound = base.nes.copy(instruments = bank, pcmSamples = NesPcmKit.samples()))
    }

    // ---- import -------------------------------------------------------------------------------

    @Test
    fun wavImportKeepsRateAndRaisesOnlyTheDacHoldValue() {
        val values = shortArrayOf(0, 32767, -32768, -32513, -32512, 256, -256)
        val imported = Pcm.fromWav(wav16(values, 22050), "tone", Pcm.MEMORY)
        assertEquals(22050, imported.sample.rate)
        assertFalse(imported.resampled)
        // (v + 32768) >> 8: 0 -> 128, top -> 255, bottom -> 0 raised to 1 (the MMC5 DAC holds on $00), -32512 -> 1.
        assertEquals(listOf(128, 255, 1, 1, 1, 129, 127), (0 until imported.sample.data.size).map { imported.sample.data[it] })
        assertEquals(2, imported.raised)
        assertTrue((0 until imported.sample.data.size).none { imported.sample.data[it] == 0 })
        // Stereo is mixed to mono by FamiStudio's reader.
        val stereo = Pcm.fromWav(wav16(shortArrayOf(1000, 3000, -1000, -3000), 8000, channels = 2), "st", Pcm.MEMORY)
        assertEquals(listOf((2000 + 32768) shr 8, (-2000 + 32768) shr 8), (0 until 2).map { stereo.sample.data[it] })
    }

    @Test
    fun wavImportConvertsAboveFortyEightKilohertzAndSaysSo() {
        val tone = ShortArray(9600) { if ((it / 48) % 2 == 0) 8000 else -8000 }
        val imported = Pcm.fromWav(wav16(tone, 96000), "hi", Pcm.MEMORY)
        assertTrue(imported.resampled)
        assertEquals(96000, imported.sourceRate)
        assertEquals(Pcm.MAX_RATE, imported.sample.rate)
        assertEquals(4800, imported.sample.data.size) // the same 0.1 s
        // 48 kHz itself is kept.
        assertFalse(Pcm.fromWav(wav16(tone, 48000), "edge", Pcm.MEMORY).resampled)
    }

    @Test
    fun wavImportRefusesRatherThanTruncates() {
        val long = wav16(ShortArray(5000), 22050)
        val e = assertFailsWith<IllegalArgumentException> { Pcm.fromWav(long, "long", 4999) }
        assertTrue(e.message!!.contains("free"), e.message)
        assertEquals(5000, Pcm.fromWav(long, "long", 5000).sample.data.size)
        assertFailsWith<IllegalArgumentException> { Pcm.fromWav(wav16(ShortArray(10), 999), "slow", Pcm.MEMORY) }
        assertFailsWith<IllegalArgumentException> { Pcm.fromWav(ByteArray(12), "junk", Pcm.MEMORY) }
    }

    // ---- model --------------------------------------------------------------------------------

    @Test
    fun mmc5SongsHaveEightLanesAndSevenEngineChannels() {
        assertEquals(8, NesMmc5System.channels.size)
        assertEquals("MMC5 PCM", NesMmc5System.channels[NesRules.MMC5_PCM].name)
        assertFalse(NesMmc5System.channels[NesRules.MMC5_PCM].tonal)
        assertEquals(7, org.thortracker.system.nes.NesExpansion.MMC5.engineChannels)
        assertEquals(7, FsSong.of(NesMmc5System.demo()).channels)
        // Only C, F, G and S are offered on the lane.
        assertEquals(listOf(NesRules.FX_VOLUME, NesRules.FX_SPEED, NesRules.FX_NOTE_DELAY, NesRules.FX_NOTE_CUT),
            NesMmc5System.effectsFor(NesRules.MMC5_PCM).map { it.code })
    }

    @Test
    fun projectsKeepPcmSamplesAndMappingsAndUpgradeSevenLaneSongs() {
        val song = NesMmc5DemoSong.pcmDemo()
        val back = ProjectCodec.decode(ProjectCodec.encode(song))
        assertEquals(song.nes.pcmSamples, back.nes.pcmSamples)
        assertEquals(song.nes.instruments, back.nes.instruments)
        assertEquals(song.patterns, back.patterns)
        // A version 2 MMC5 project (seven lanes) gains an empty PCM lane.
        val text = ProjectCodec.encode(NesMmc5System.demo())
        val tree = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val songJson = tree["song"] as kotlinx.serialization.json.JsonObject
        val patterns = (songJson["patterns"] as kotlinx.serialization.json.JsonArray).map { p ->
            val o = p as kotlinx.serialization.json.JsonObject
            val tracks = o["tracks"] as kotlinx.serialization.json.JsonArray
            kotlinx.serialization.json.JsonObject(o + ("tracks" to kotlinx.serialization.json.JsonArray(tracks.take(7))))
        }
        val old = kotlinx.serialization.json.JsonObject(tree + mapOf(
            "backendVersion" to kotlinx.serialization.json.JsonPrimitive(2),
            "song" to kotlinx.serialization.json.JsonObject(songJson + ("patterns" to kotlinx.serialization.json.JsonArray(patterns))),
        ))
        val upgraded = ProjectCodec.decode(old.toString())
        assertEquals(NesMmc5System.demo().patterns, upgraded.patterns)
        // PCM data is MMC5-only, and a $00 byte cannot be stored.
        val plain = NesSystem.blank()
        assertTrue(NesSystem.structuralErrors(plain.copy(sound = plain.nes.copy(pcmSamples = NesPcmKit.samples()))).joinToString().contains("MMC5"))
        val zero = kitSong().let { it.copy(sound = it.nes.copy(pcmSamples = listOf(PcmSample("z", PcmData.of(listOf(128, 0)), 8000)))) }
        assertTrue(NesMmc5System.structuralErrors(zero).joinToString().contains("\$00"))
    }

    // ---- the image extension ------------------------------------------------------------------

    @Test
    fun extensionHoldsEverySampleAndMappingWithPeriods() {
        val song = kitSong()
        val ext = extension(song)
        assertEquals(NesPcmLowering.SIZE, ext.size)
        assertEquals(NesPcmLowering.MAGIC, u32(ext, 0))
        assertEquals(1, u8(ext, 4))
        assertEquals(3, u8(ext, 5))
        assertEquals(2 + 25, u16(ext, 6)) // kick, hat, 25 bass keys
        val total = NesPcmKit.samples().sumOf { it.data.size }
        assertEquals(total, u32(ext, 8))
        assertEquals(song.orders.size, u8(ext, 14))
        // Samples back to back, their bytes in the data area; the bass's loop start.
        var offset = 0
        for ((s, sample) in NesPcmKit.samples().withIndex()) {
            val e = NesPcmLowering.SAMPLES_AT + 12 * s
            assertEquals(offset, u32(ext, e))
            assertEquals(sample.data.size, u32(ext, e + 4))
            assertEquals(sample.loopStart, u32(ext, e + 8))
            assertContentEquals(sample.data.toByteArray(), ext.copyOfRange(NesPcmLowering.DATA_AT + offset, NesPcmLowering.DATA_AT + offset + sample.data.size))
            offset += sample.data.size
        }
        assertTrue(ext.copyOfRange(NesPcmLowering.DATA_AT + total, ext.size).all { it == 0.toByte() })
        // Mapping order: slot then key. Sound 02's A-2 (pitch 0) plays the bass at its own rate; A-3 an octave up.
        val index = NesPcmLowering.mappings(song)
        val root = index.getValue(2 to NesPcmKit.BASS_ROOT)
        val up = index.getValue(2 to NesPcmKit.BASS_ROOT + 12)
        fun period(i: Int) = u32(ext, NesPcmLowering.MAPPINGS_AT + 8 * i + 4).toLong() and 0xFFFFFFFFL
        assertEquals(2, u8(ext, NesPcmLowering.MAPPINGS_AT + 8 * root))
        assertEquals(1, u8(ext, NesPcmLowering.MAPPINGS_AT + 8 * root + 1)) // loop
        assertEquals(Pcm.period(NesPcmKit.BASS_RATE, 0), period(root))
        assertEquals(Math.round(1789773.0 * 65536 / 11025), Pcm.period(NesPcmKit.BASS_RATE, 0))
        assertTrue(kotlin.math.abs(period(root).toDouble() / period(up) - 2.0) < 1e-6)
        assertEquals(0, index.getValue(1 to NesPcmKit.KICK_KEY))
        // Each entry names its binding (slot, key): (slot - 1) x 96 + (key - 12), strictly ascending through the table.
        fun binding(i: Int) = u16(ext, NesPcmLowering.MAPPINGS_AT + 8 * i + 2)
        assertEquals(NesPcmKit.KICK_KEY - 12, binding(0))
        assertEquals(96 + NesPcmKit.BASS_ROOT - 12, binding(root))
        for ((key, i) in index) assertEquals(NesPcmLowering.binding(key.first, key.second), binding(i))
        assertTrue((1 until index.size).all { binding(it) > binding(it - 1) })
        assertEquals(6143, NesPcmLowering.binding(NesSystem.SLOTS, NesSystem.noteRange.last))
        assertFailsWith<IllegalArgumentException> { NesPcmLowering.binding(1, NesSystem.noteRange.first - 1) }
        // Every rate and pitch the editor allows fits the player's period range (no overflow).
        for (rate in listOf(Pcm.MIN_RATE, Pcm.MAX_RATE)) for (pitch in listOf(Pcm.PITCH.first, Pcm.PITCH.last)) {
            assertTrue(Pcm.period(rate, pitch) in Pcm.PERIOD_MIN..Pcm.PERIOD_MAX)
        }
        // The native engine's audition note is the mapping index + 1; an unmapped key has none.
        assertEquals(root + 1, NesMmc5System.engine.note(song, NesRules.MMC5_PCM, NesPcmKit.BASS_ROOT, 2))
        assertEquals(0, NesMmc5System.engine.note(song, NesRules.MMC5_PCM, 90, 2))
        assertNull(NesMmc5System.engine.auditionProblem(song, NesRules.MMC5_PCM, NesPcmKit.KICK_KEY, 1, 0))
        assertTrue(NesMmc5System.engine.auditionProblem(song, NesRules.MMC5_PCM, 90, 1, 0)!!.contains("no PCM sample"))
    }

    @Test
    fun mappingAnEarlierKeyShiftsTableIndicesButNeverBindings() {
        val song = kitSong()
        val before = extension(song)
        val index = NesPcmLowering.mappings(song)
        // Map one more key of sound 01 below the kick: every later entry moves one place down the table.
        val drums = song.nes.instruments[0]
        val extra = NesPcmKit.KICK_KEY - 1
        val bank = song.nes.instruments.toMutableList()
        bank[0] = drums.copy(pcm = drums.pcm + (extra to PcmMapping(0)))
        val edited = song.copy(sound = song.nes.copy(instruments = bank))
        val after = extension(edited)
        val moved = NesPcmLowering.mappings(edited)
        val root = 2 to NesPcmKit.BASS_ROOT
        assertEquals(index.getValue(root) + 1, moved.getValue(root))
        fun entry(ext: ByteArray, i: Int) = ext.copyOfRange(NesPcmLowering.MAPPINGS_AT + 8 * i, NesPcmLowering.MAPPINGS_AT + 8 * i + 8).toList()
        // The bass root's whole entry (sample, loop, binding, period) is the same bytes at its new place.
        assertEquals(entry(before, index.getValue(root)), entry(after, moved.getValue(root)))
        assertEquals(NesPcmLowering.binding(1, extra), u16(after, NesPcmLowering.MAPPINGS_AT + 2))
        assertEquals(index.size + 1, u16(after, 6))
    }

    @Test
    fun eventsCarryNotesOffVolumeDelayAndCutPerOrder() {
        val song = withPcm(kitSong(), mapOf(
            0 to Cell(NesPcmKit.KICK_KEY, 1, NesRules.FX_VOLUME, 0x0A),
            2 to Cell(NesPcmKit.HAT_KEY, 0, NesRules.FX_NOTE_DELAY, 0x03), // legato: sound 01 carries on
            4 to Cell(Cell.CUT),
            6 to Cell(Cell.EMPTY, 0, NesRules.FX_NOTE_CUT, 0x02),
            8 to Cell(Cell.EMPTY, 0, NesRules.FX_SPEED, 0x04), // engine speed only: no lane event
        ))
        assertEquals(emptyList(), NesMmc5System.errors(song))
        val two = song.copy(orders = listOf(0, 0))
        val ext = extension(two)
        assertEquals(4, u16(ext, 12)) // one shared range of four events
        assertEquals(listOf(0, 4, 0, 4), listOf(u16(ext, 16), u16(ext, 18), u16(ext, 20), u16(ext, 22)))
        fun ev(i: Int) = ext.copyOfRange(NesPcmLowering.EVENTS_AT + 6 * i, NesPcmLowering.EVENTS_AT + 6 * i + 6).map { it.toInt() and 0xFF }
        val index = NesPcmLowering.mappings(two)
        assertEquals(listOf(0, NesPcmLowering.EV_NOTE or NesPcmLowering.EV_VOLUME, index.getValue(1 to NesPcmKit.KICK_KEY), 0, 0x0A, 0), ev(0))
        assertEquals(listOf(2, NesPcmLowering.EV_NOTE or NesPcmLowering.EV_DELAY, index.getValue(1 to NesPcmKit.HAT_KEY), 0, 3 shl 4, 0), ev(1))
        assertEquals(listOf(4, NesPcmLowering.EV_STOP, 0, 0, 0, 0), ev(2))
        assertEquals(listOf(6, NesPcmLowering.EV_CUT, 0, 0, (2 shl 9) and 0xFF, (2 shl 9) shr 8), ev(3))
    }

    // ---- exports and speed ------------------------------------------------------------------------

    @Test
    fun exportsRefuseAPlayingPcmLane() {
        val song = NesMmc5DemoSong.pcmDemo()
        assertEquals(emptyList(), NesMmc5System.errors(song))
        assertTrue(NesMmc5System.warnings(song).any { it.contains("MMC5 PCM lane plays") })
        for (format in NesMmc5System.exports) {
            val e = assertFailsWith<IllegalArgumentException> { format.export(song) }
            assertTrue(e.message!!.contains("MMC5 PCM lane plays"), e.message)
        }
        // The engine image still plays it (preview and WAV).
        val image = ByteArray(NesMmc5System.engine.imageSize)
        NesMmc5System.engine.lower(song, image)
        assertTrue(u16(image, NesPlayer.MMC5.imageSize + 12) > 0)
    }

    @Test
    fun silentPcmLaneExportsTheSevenChannelsUnchanged() {
        val demo = NesMmc5System.demo()
        // Samples and mappings nothing plays stay in the project only.
        val bank = demo.nes.instruments.toMutableList()
        bank[20] = bank[20].copy(pcm = NesPcmKit.drums(0).pcm) // an otherwise untouched slot
        bank[1] = bank[1].copy(pcm = mapOf(40 to PcmMapping(2, pitch = 3, loop = true)))
        val unplayed = demo.copy(sound = demo.nes.copy(instruments = bank, pcmSamples = NesPcmKit.samples()))
        assertEquals(emptyList(), NesMmc5System.errors(unplayed))
        val nsf = NesMmc5System.exports.first { it.extension == "nsf" }
        assertContentEquals(nsf.export(demo), nsf.export(unplayed))
        assertEquals(FamiStudioText.text(demo), FamiStudioText.text(unplayed))
        assertEquals(FamiStudioLowering.lower(demo).data.toList(), FamiStudioLowering.lower(unplayed).data.toList())
        // The seven-channel part of the engine image is the same bytes; the extension holds the unplayed mappings.
        val a = ByteArray(NesMmc5System.engine.imageSize).also { NesMmc5System.engine.lower(demo, it) }
        val b = ByteArray(NesMmc5System.engine.imageSize).also { NesMmc5System.engine.lower(unplayed, it) }
        val base = NesPlayer.MMC5.imageSize
        assertContentEquals(a.copyOfRange(0, base), b.copyOfRange(0, base))
        assertEquals(0, u16(a, base + 6))
        assertEquals(3, u16(b, base + 6))
        assertEquals(0, u16(b, base + 12)) // no events
    }

    @Test
    fun pcmLaneSpeedReachesTheEngineAndTheExportsAfterEngineLanes() {
        val base = kitSong()
        val onPcm = withPcm(base, mapOf(16 to Cell(Cell.EMPTY, 0, NesRules.FX_SPEED, 0x03)))
        // The same speed written on Pulse 1 gives the same engine data and text.
        val p = base.patterns[0]
        val onPulse = base.copy(patterns = listOf(p.copy(tracks = p.tracks.mapIndexed { ch, t ->
            if (ch == 0) t.toMutableList().also { it[16] = Cell(Cell.EMPTY, 0, NesRules.FX_SPEED, 0x03) } else t
        })))
        assertContentEquals(FamiStudioLowering.lower(onPulse).data, FamiStudioLowering.lower(onPcm).data)
        assertEquals(FamiStudioText.text(onPulse), FamiStudioText.text(onPcm))
        assertEquals(3, FsSong.of(onPcm).speedAt(16))
        // An engine lane's speed on the same row wins (the first lane in order, as FamiStudio's).
        val both = withPcm(onPulse.copy(patterns = listOf(onPulse.patterns[0].copy(tracks = onPulse.patterns[0].tracks.mapIndexed { ch, t ->
            if (ch == 0) t.toMutableList().also { it[16] = Cell(Cell.EMPTY, 0, NesRules.FX_SPEED, 0x05) } else t
        }))), mapOf(16 to Cell(Cell.EMPTY, 0, NesRules.FX_SPEED, 0x03)))
        assertEquals(5, FsSong.of(both).speedAt(16))
    }

    // ---- validation and edits ---------------------------------------------------------------------

    @Test
    fun validationNamesPcmProblems() {
        val song = kitSong()
        fun errors(cells: Map<Int, Cell>) = NesMmc5System.errors(withPcm(song, cells)).joinToString()
        assertEquals("", errors(mapOf(0 to Cell(NesPcmKit.KICK_KEY, 1))))
        assertTrue(errors(mapOf(0 to Cell(60, 1))).contains("maps no PCM sample"))
        assertTrue(errors(mapOf(0 to Cell(NesPcmKit.KICK_KEY, 0))).contains("none has played on MMC5 PCM"))
        assertTrue(errors(mapOf(0 to Cell(NesPcmKit.KICK_KEY, 1, NesRules.FX_VIBRATO, 0x44))).contains("no effect on MMC5 PCM"))
        assertTrue(errors(mapOf(0 to Cell(NesPcmKit.KICK_KEY, 1, NesRules.FX_DELTA_COUNTER, 0x10))).contains("no effect on MMC5 PCM"))
        assertEquals("", errors(mapOf(0 to Cell(NesPcmKit.KICK_KEY, 1, NesRules.FX_VOLUME, 0x0F))))
        // Memory: more than 256 KiB of samples is an error, not a silent cut.
        val big = song.copy(sound = song.nes.copy(pcmSamples = song.nes.pcmSamples + PcmSample("big", PcmData.of(ByteArray(Pcm.MEMORY) { 0x40 }), 8000)))
        assertTrue(NesMmc5System.errors(big).joinToString().contains("PCM samples take"))
        // A 2A03 song may not hold PCM mappings.
        val plain = NesSystem.blank()
        val bank = plain.nes.instruments.toMutableList().also { it[0] = NesInstrument(pcm = mapOf(36 to PcmMapping(0))) }
        assertTrue(NesSystem.structuralErrors(plain.copy(sound = plain.nes.copy(instruments = bank))).joinToString().contains("MMC5"))
    }

    @Test
    fun deletingAPcmSampleRemapsAndSpreadMapsChromatically() {
        val session = EditorSession(kitSong())
        assertEquals(1, session.deletePcmSample(0)) // the kick: one key played it
        val drums = session.song.nes.instruments[0].pcm
        assertNull(drums[NesPcmKit.KICK_KEY])
        assertEquals(0, drums.getValue(NesPcmKit.HAT_KEY).sample) // the hat moved down
        assertTrue(session.song.nes.instruments[1].pcm.values.all { it.sample == 1 }) // the bass followed
        assertEquals(emptyList(), NesMmc5System.structuralErrors(session.song))
        // Spread: the hat on F#3 becomes a chromatic instrument an octave either way, pitch = distance.
        val added = session.spreadPcmMapping(1, NesPcmKit.HAT_KEY, 12, 12)
        assertEquals(24, added.size)
        val pcm = session.song.nes.instruments[0].pcm
        assertEquals(-12, pcm.getValue(NesPcmKit.HAT_KEY - 12).pitch)
        assertEquals(7, pcm.getValue(NesPcmKit.HAT_KEY + 7).pitch)
        assertNotNull(NesPcmLowering.mappings(session.song)[1 to NesPcmKit.HAT_KEY + 12])
    }

    @Test
    fun fixturesAreValid() {
        for (song in listOf(NesMmc5DemoSong.pcmDemo(), NesMmc5DemoSong.pcmShortest())) {
            assertEquals(emptyList(), NesMmc5System.errors(song), song.title)
            assertTrue(NesPcmLowering.plays(song))
        }
        assertFalse(NesPcmLowering.plays(NesMmc5System.demo()))
        // The synthesized samples never hold $00 and the bass loops a whole cycle.
        for (s in NesPcmKit.samples()) assertTrue((0 until s.data.size).none { s.data[it] == 0 }, s.name)
        assertEquals(NesPcmKit.BASS_LOOP + NesPcmKit.BASS_CYCLE, NesPcmKit.samples()[2].data.size)
    }
}
