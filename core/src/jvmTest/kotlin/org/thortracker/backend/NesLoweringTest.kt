package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.Vrc6SawVolume
import org.thortracker.system.nes.nes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The image contract of the native `"nes-famistudio"` engines (docs/AUDIO_ENGINE.md, NES image layout), checked on
 * every golden song with the player build its expansion needs (the VRC6 songs on `"nes-famistudio-vrc6"`): header,
 * envelope directory, instrument table, channel streams of `$41` per order plus `$42`, flat pattern streams with one
 * terminator per row, and the engine-facing numbers of [NesSystem.engine] and [NesVrc6System.engine].
 */
class NesLoweringTest {
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xff

    @Test
    fun imagesFollowTheEngineContract() {
        for ((name, build) in NesGoldenSongs.ALL) {
            val song = build()
            val engine = song.system.engine
            assertEquals(song.nes.expansion.player.engineId, engine.id, name)
            val image = ByteArray(engine.imageSize)
            engine.lower(song, image)
            check(name, song, image)
        }
    }

    private fun check(name: String, song: Song, image: ByteArray) {
        val player = song.nes.expansion.player
        assertEquals(player.imageSize, image.size, "$name: image size")
        val base = player.songBase
        val region = NesPlayerImage.HEADER_SIZE + NesPlayerImage.DIRECTORY_SIZE
        assertEquals("TTNS", image.copyOfRange(0, 4).decodeToString())
        assertEquals(player.playerId, u16(image, 4) or (u16(image, 6) shl 16))
        assertEquals(base, u16(image, 8))
        val size = u16(image, 10)
        val sampleSize = u16(image, NesPlayerImage.HEADER_SAMPLE_SIZE)
        val sampleStart = u16(image, NesPlayerImage.HEADER_SAMPLE_START)
        assertEquals(sampleSize == 0, sampleStart == 0, "$name: sample size and start")
        val songEnd = if (sampleSize == 0) player.regionEnd else sampleStart
        if (sampleSize > 0) {
            assertTrue(sampleStart >= NesPlayerImage.SAMPLE_BASE && sampleStart % 64 == 0 && sampleStart + sampleSize <= player.regionEnd, "$name: sample block")
        }
        assertTrue(base + size <= songEnd, "$name: song data runs into the samples")
        val samplesAt = region + songEnd - base
        assertTrue(image.copyOfRange(region + size, samplesAt).all { it == 0.toByte() }, "$name: region not zero after the data")
        assertTrue(image.copyOfRange(samplesAt + sampleSize, image.size).all { it == 0.toByte() }, "$name: region not zero after the samples")
        // Every sample list entry points at whole samples inside the sample block.
        val listAt = u16(image, region + 3) + 4 - base
        for (k in 0 until u8(image, NesPlayerImage.HEADER_DPCM_COUNT)) {
            val e = region + listAt + 4 * k
            val from = NesPlayerImage.SAMPLE_BASE + 64 * u8(image, e)
            assertTrue(from >= sampleStart && from + 16 * u8(image, e + 1) + 1 <= sampleStart + sampleSize, "$name: DPCM entry $k outside the samples")
            assertTrue(u8(image, e + 2) and 0x80 == 0 && u8(image, e + 3) <= 127, "$name: DPCM entry $k")
        }
        assertEquals(NesPlayerImage.LAYOUT_THOR, u8(image, 13))
        assertEquals(NesSystem.SLOTS, u8(image, 14))
        val envCount = u16(image, 16)
        val data = image.copyOfRange(region, region + size)
        fun at(addr: Int) = addr - base
        // Directory: ascending, non-overlapping, inside the data, lengths 2..255, kinds 0..3; instruments at slot*4+kind.
        var last = 0
        val dir = HashMap<Int, Triple<Int, Int, Int>>()
        for (i in 0 until envCount) {
            val e = NesPlayerImage.HEADER_SIZE + 4 * i
            val addr = u16(image, e)
            if (addr == 0) continue
            val len = u8(image, e + 2)
            val kind = u8(image, e + 3)
            assertTrue(addr >= last && len in 2..255 && kind in 0..3 && at(addr) + len <= size, "$name: directory entry $i")
            last = addr + len
            dir[addr] = Triple(i, len, kind)
        }
        for (i in envCount until NesPlayerImage.MAX_ENVELOPES) assertEquals(0, u16(image, NesPlayerImage.HEADER_SIZE + 4 * i))
        // FamiStudio header.
        assertEquals(1, u8(data, 0))
        val instruments = at(u16(data, 1))
        val channels = player.channels
        assertEquals(song.nes.expansion.channels, channels, "$name: a stream per channel")
        val streams = IntArray(channels) { at(u16(data, 5 + 2 * it)) }
        assertEquals(FamiStudioLowering.TEMPO_PAL, u16(data, 5 + 2 * channels))
        assertEquals(FamiStudioLowering.TEMPO_NTSC, u16(data, 7 + 2 * channels))
        for (slot in 0 until NesSystem.SLOTS) for (kind in 0 until 4) {
            val addr = u16(data, instruments + slot * 8 + kind * 2)
            assertEquals(slot * 4 + kind to kind, dir[addr]!!.let { it.first to it.third }, "$name: instrument $slot envelope $kind")
        }
        val arranged = NesSystem.arranged(song)
        val orders = arranged.orders.size
        for (c in 0 until channels) {
            val s = streams[c]
            for (o in 0 until orders) {
                assertEquals(0x41, u8(data, s + 4 * o), "$name: channel $c order $o")
                val n = u8(data, s + 4 * o + 1)
                val rows = arranged.patterns[arranged.orders[o]].length
                assertEquals(rows, walk(data, at(u16(data, s + 4 * o + 2)), n, c, dir, base), "$name: channel $c order $o rows")
            }
            assertEquals(0x42, u8(data, s + 4 * orders))
            assertEquals(base + s + 4 * arranged.loopOrder, u16(data, s + 4 * orders + 1))
        }
    }

    /** Walks [n] row terminators of a flat pattern stream; returns the rows they cover. Checks opcode limits. */
    private fun walk(data: ByteArray, start: Int, n: Int, channel: Int, dir: Map<Int, Triple<Int, Int, Int>>, base: Int): Int {
        var p = start
        var rows = 0
        var terms = 0
        var delays = 0
        while (terms < n) {
            val op = u8(data, p++)
            when {
                channel == NesRules.DPCM -> when {
                    // Runs, sample list entries (0 = stop), and only speed, delays, no attack and the DMC level.
                    op >= 0x80 -> { assertTrue(op and 1 == 1, "DPCM has no instruments"); rows += 1 + ((op and 0x7f) shr 1); terms++; delays = 0 }
                    op < 0x40 -> { rows++; terms++; delays = 0 }
                    op == 0x43 -> Unit
                    op == 0x46 -> assertTrue(u8(data, p++) in 1..127)
                    op == 0x47 -> { p++; delays++; assertTrue(delays == 1, "one note delay per row") }
                    op == 0x48 -> p++
                    op == 0x52 -> p++
                    else -> error("unexpected opcode ${op.toString(16)} on DPCM")
                }
                op >= 0x80 && op and 1 == 1 -> { rows += 1 + ((op and 0x7f) shr 1); terms++; delays = 0 }
                op >= 0x80 -> assertTrue((op and 0x7f) shr 1 < NesSystem.SLOTS)
                op < 0x40 -> { rows++; terms++; delays = 0 }
                op in 0x70..0x7f -> assertTrue(channel != NesRules.TRIANGLE, "volume on the Triangle")
                else -> when (op) {
                    0x40 -> { assertTrue(u8(data, p++) in 1..96); rows++; terms++; delays = 0 }
                    0x45 -> { rows++; terms++; delays = 0 }
                    0x50 -> { p += 3; rows++; terms++; delays = 0 }
                    0x43, 0x4a, 0x4c, 0x4d -> Unit
                    0x46 -> assertTrue(u8(data, p++) in 1..127)
                    0x47 -> { p++; delays++; assertTrue(delays == 1, "one note delay per row") }
                    0x48 -> p++
                    0x4e -> { assertTrue(channel != NesRules.NOISE); p++ }
                    0x4f -> {
                        assertTrue(channel != NesRules.TRIANGLE && channel != NesRules.VRC6_SAW)
                        assertTrue(u8(data, p++) in 0..NesRules.dutyMax(
                            if (channel >= NesRules.APU_CHANNELS) org.thortracker.system.nes.NesExpansion.VRC6 else org.thortracker.system.nes.NesExpansion.NONE, channel))
                    }
                    0x55 -> { assertEquals(NesRules.VRC6_SAW, channel, "saw master volume off the saw"); assertTrue(u8(data, p++) in setOf(0x00, 0x01, 0xff)) }
                    0x51 -> p += 2
                    0x49, 0x4b -> {
                        val addr = u16(data, p); p += 2
                        val kind = if (op == 0x49) 3 else 1
                        assertEquals(kind, dir[addr]?.third, "envelope pointer ${addr.toString(16)} must be a directory entry of kind $kind")
                        if (op == 0x49) assertTrue(channel != NesRules.NOISE)
                    }
                    else -> error("unexpected opcode ${op.toString(16)} in a pattern stream")
                }
            }
        }
        return rows
    }

    @Test
    fun identicalOrderStreamsAreStoredOnce() {
        val song = NesSystem.blank().let { it.copy(orders = listOf(0, 0, 0, 0)) }
        val data = FamiStudioLowering.lower(song).data
        val pulse = u16(data, 5) - NesPlayerImage.SONG_BASE
        // Orders 1-3 share one stream (order 0 also sets the speed).
        val targets = (0 until 4).map { u16(data, pulse + 4 * it + 2) }
        assertEquals(1, targets.drop(1).toSet().size)
        assertNotEquals(targets[0], targets[1])
    }

    @Test
    fun instrumentEnvelopesKeepTheirDirectorySlots() {
        val a = NesSystem.demo()
        val bank = a.nes.instruments.toMutableList()
        bank[0] = bank[0].copy(volume = NesEnvelope(listOf(15, 1)))
        val b = a.copy(sound = a.nes.copy(instruments = bank))
        val ea = FamiStudioLowering.lower(a).envelopes.associateBy { it.index }
        val eb = FamiStudioLowering.lower(b).envelopes.associateBy { it.index }
        assertEquals(ea.keys, eb.keys)
        assertTrue(!ea.getValue(0).bytes.contentEquals(eb.getValue(0).bytes))
        assertContentEquals(ea.getValue(4 + 1).bytes, eb.getValue(4 + 1).bytes)
    }

    @Test
    fun engineNumbers() {
        val e = NesSystem.engine
        val song = NesSystem.blank()
        assertEquals("nes-famistudio", e.id)
        assertEquals(NesPlayerImage.IMAGE_SIZE, e.imageSize)
        assertEquals(46, e.note(song, 0, 57, 1)) // A-4 = FamiStudio note 46, 440 Hz on the pulses
        assertEquals(-5, e.fine(0, 57, 5))
        assertEquals(null, e.auditionProblem(song, 0, 57, 1, 0))
        assertTrue(e.auditionProblem(song, 0, 57, 0, 0)!!.contains("instrument"))
        assertTrue(e.auditionProblem(song, 2, 20, 1, 0)!!.contains("A-1"))
        assertEquals(null, e.auditionProblem(song, 3, 12, 1, 0))
        assertTrue(e.auditionProblem(song, NesRules.DPCM, 48, 1, 0)!!.contains("maps no DPCM sample to C-4"))
    }

    /** The VRC6 variant: its own engine, image size and song base; sounds of the wrong chip are refused before the engine. */
    @Test
    fun vrc6EngineNumbers() {
        val e = NesVrc6System.engine
        val base = NesVrc6System.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[1] = NesInstrument("Saw", chip = NesChip.VRC6, sawVolume = Vrc6SawVolume.FULL)
        val song = base.copy(sound = base.nes.copy(instruments = bank))
        assertEquals("nes-famistudio-vrc6", e.id)
        assertEquals(NesVrc6PlayerImage.IMAGE_SIZE, e.imageSize)
        assertEquals(NesVrc6PlayerImage.SONG_BASE, NesPlayer.VRC6.songBase)
        assertEquals(8, NesVrc6System.channels.size)
        assertEquals(5, NesSystem.channels.size)
        assertEquals(46, e.note(song, NesRules.VRC6_SAW, 57, 2))
        assertEquals(null, e.auditionProblem(song, NesRules.VRC6_SAW, 57, 2, 0))
        assertEquals(null, e.auditionProblem(song, NesRules.VRC6_PULSE1, 13, 2, 0)) // the VRC6 reaches below A-1
        assertTrue(e.auditionProblem(song, NesRules.VRC6_PULSE1, 57, 1, 0)!!.contains("2A03 sound"))
        assertTrue(e.auditionProblem(song, 0, 57, 2, 0)!!.contains("VRC6 sound"))
        val image = ByteArray(e.imageSize)
        e.lower(song, image)
        check("vrc6 blank", song, image)
        assertFailsWith<IllegalArgumentException> { NesSystem.engine.lower(song, ByteArray(NesSystem.engine.imageSize)) }
    }

    /** The saw's master volume: `$55` before an instrument of another volume, and with each order's first instrument. */
    @Test
    fun sawMasterVolumeIsSentWithInstruments() {
        val song = NesGoldenSongs.vrc6Effects()
        val lowered = FamiStudioLowering.lower(song)
        val data = lowered.data
        val base = NesPlayer.VRC6.songBase
        val saw = u16(data, 5 + 2 * NesRules.VRC6_SAW) - base
        val firstOrder = u16(data, saw + 2) - base
        // Order 0's saw row 0 is Saw full (slot 3): `$55 $01` right before its instrument byte `$84` (after any speed or
        // state opcodes the order starts with).
        val head = (0 until 16).map { u8(data, firstOrder + it) }
        assertTrue(head.windowed(3).contains(listOf(0x55, 0x01, 0x80 or (2 shl 1))), head.toString())
        val volumes = ArrayList<Int>()
        for (i in 0 until data.size - 2) if (u8(data, i) == 0x55 && u8(data, i + 2) >= 0x80 && u8(data, i + 2) and 1 == 0) volumes += u8(data, i + 1)
        assertTrue(volumes.containsAll(listOf(0x00, 0x01, 0xff)), volumes.toString())
    }

    @Test
    fun unplayableSongsThrowActionableMessages() {
        val song = NesSystem.blank().let { s ->
            s.copy(patterns = listOf(s.patterns[0].copy(tracks = s.patterns[0].tracks.mapIndexed { ch, t ->
                if (ch == 0) t.toMutableList().also { it[0] = Cell(57, 1, NesRules.FX_SPEED, 0x40) } else t
            })))
        }
        val e = assertFailsWith<IllegalArgumentException> { NesSystem.engine.lower(song, ByteArray(NesSystem.engine.imageSize)) }
        assertTrue(e.message!!.contains("F01-F1F"), e.message)
    }

    @Test
    fun envelopeEncodingMatchesFamiStudioCases() {
        // Single value: FamiStudio's 127-frame hold.
        assertContentEquals(byteArrayOf(0x00, 0xcf.toByte(), 0x7f, 0x00, 0x02), FamiStudioEnvelopes.encode(NesEnvelope(listOf(15)), FamiStudioEnvelopes.Kind.VOLUME))
        assertContentEquals(byteArrayOf(0x7f, 0x00, 0x00), FamiStudioEnvelopes.encode(NesEnvelope(), FamiStudioEnvelopes.Kind.DUTY))
        // Release: header byte is the index after the release jump.
        val rel = FamiStudioEnvelopes.encode(NesEnvelope(listOf(15, 12, 10, 9, 8, 6, 4, 2, 0), loop = 3, release = 5), FamiStudioEnvelopes.Kind.VOLUME)
        assertEquals(0, rel[rel[0].toInt() - 2].toInt())
        assertTrue(NesInstrument.DEFAULT.volume.values == listOf(15))
    }

    /** A sample-less song with [patterns] distinct, busy patterns (pseudo-random notes and volumes on four channels). */
    private fun busySong(patterns: Int): Song {
        require(patterns <= NesRules.MAX_ORDERS)
        val base = NesSystem.blank()
        var seed = 12345
        fun next(n: Int): Int { seed = seed * 1103515245 + 12345; return (seed ushr 16) % n }
        val list = List(patterns) { p ->
            org.thortracker.model.Pattern("Busy $p", List(NesSystem.channels.size) { ch ->
                List(org.thortracker.model.Songs.ROWS) {
                    if (ch == NesRules.DPCM || next(3) == 0) Cell.BLANK
                    else Cell(36 + next(40), 1 + next(4), if (ch != NesRules.TRIANGLE && next(2) == 0) NesRules.FX_VOLUME else 0, next(16))
                        .let { if (it.effect == 0) it.copy(param = 0) else it }
                }
            })
        }
        return base.copy(patterns = list, orders = list.indices.toList())
    }

    /** 0.9.0 gave song data 24570 bytes; without samples the whole $8C00-$FFF9 is the song's, so those songs still play. */
    @Test
    fun sampleLessSongsKeepTheWholeRegion() {
        var patterns = 30
        var song = busySong(patterns)
        while (FamiStudioLowering.lower(FsSong.of(song)).data.size < 20000) song = busySong(++patterns)
        val size = FamiStudioLowering.lower(song).data.size
        assertTrue(size in 13313..24570, "song data $size bytes")
        assertEquals(emptyList(), NesSystem.errors(song))
        val image = ByteArray(NesSystem.engine.imageSize)
        NesSystem.engine.lower(song, image)
        check("busy", song, image)
        assertEquals(0, u16(image, NesPlayerImage.HEADER_SAMPLE_START))
        NesSystem.exports.forEach { it.export(song) }
    }

    /** Song data and samples share the region: too much of both is one error naming both sizes. */
    @Test
    fun songAndSamplesShareOneBudget() {
        var patterns = 30
        var busy = busySong(patterns)
        while (FamiStudioLowering.lower(FsSong.of(busy)).data.size < 18500) busy = busySong(++patterns)
        val big = List(3) { i -> org.thortracker.system.nes.DpcmSample("Big $i", List(4081) { (it * 7 + i) and 0xFF }) }
        val bank = busy.nes.instruments.toMutableList()
        bank[9] = NesInstrument("Bigs", dpcm = big.indices.associate { 48 + it to org.thortracker.system.nes.DpcmMapping(it) })
        val tracks = busy.patterns[0].tracks.toMutableList()
        tracks[NesRules.DPCM] = List(64) { if (it < 3) Cell(48 + it, 10) else Cell.BLANK }
        val song = busy.copy(patterns = listOf(busy.patterns[0].copy(tracks = tracks)) + busy.patterns.drop(1),
            sound = busy.nes.copy(instruments = bank, samples = big))
        val errors = NesSystem.errors(song)
        assertTrue(errors.any { it.startsWith("Song data") && "+ DPCM samples 12.0 KB" in it && "exceed the NES's 28.9 KB" in it }, errors.toString())
        // Fewer samples fit beside the same song.
        val fewer = song.copy(patterns = song.patterns.mapIndexed { i, p -> if (i == 0) p.copy(tracks = p.tracks.mapIndexed { ch, t ->
            if (ch == NesRules.DPCM) List(64) { r -> if (r == 0) Cell(48, 10) else Cell.BLANK } else t }) else p })
        assertEquals(emptyList(), NesSystem.errors(fewer))
        // The preview image adds unplayed mappings only while the song still fits: not all of the other big samples.
        val image = ByteArray(NesSystem.engine.imageSize)
        NesSystem.engine.lower(fewer, image)
        check("fewer", fewer, image)
        assertTrue(u8(image, NesPlayerImage.HEADER_DPCM_COUNT) in 1..2)
    }
}
