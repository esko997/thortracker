package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.FdsSound
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesFdsSystem
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.ChannelKind
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesS5bSystem
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.NesVrc7System
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.nes.Vrc6SawVolume
import org.thortracker.system.nes.Vrc7Patch
import org.thortracker.system.nes.Vrc7Presets
import org.thortracker.system.nes.nes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The image contract of the native `"nes-famistudio"` engines (docs/AUDIO_ENGINE.md, NES image layout), checked on
 * every golden song with the player build its expansion needs (the VRC6 songs on `"nes-famistudio-vrc6"`, the VRC7 ones
 * on `"nes-famistudio-vrc7"`, the N163 ones on `"nes-famistudio-n163-<n>"`, the FDS ones on `"nes-famistudio-fds"`, the
 * 5B ones on `"nes-famistudio-s5b"`): header, envelope directory, instrument tables (with an expansion's table), channel
 * streams of `$41` per order plus `$42`, flat pattern streams with one terminator per row, and the engine-facing numbers
 * of each variant's engine.
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
        // Every sample list entry points at whole samples inside the sample block. The FamiStudio header's sample list
        // pointer follows the instrument pointer (and, on the VRC7 build, the expansion instrument pointer).
        val exp = player.expInstruments
        val firstStream = if (exp) 7 else 5
        val listAt = u16(image, region + firstStream - 2) + 4 - base
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
        // Directory: ascending, non-overlapping, inside the data, lengths 2..255, kinds 0..3 (N163 and FDS images also 4,
        // the wave-index envelopes at 256 + slot; 5B images also mixer 5 and noise 6 at 256 + slot*2 + 0/1); instruments
        // at slot*4+kind.
        val n163 = song.nes.expansion.n163Channels > 0
        val fds = song.nes.expansion == NesExpansion.FDS
        val s5b = song.nes.expansion == NesExpansion.S5B
        var last = 0
        val dir = HashMap<Int, Triple<Int, Int, Int>>()
        for (i in 0 until envCount) {
            val e = NesPlayerImage.HEADER_SIZE + 4 * i
            val addr = u16(image, e)
            if (addr == 0) continue
            val len = u8(image, e + 2)
            val kind = u8(image, e + 3)
            val kindOk = kind in 0..3 || (n163 || fds) && kind == 4 || s5b && kind in 5..6
            assertTrue(addr >= last && len in 2..255 && kindOk && at(addr) + len <= size, "$name: directory entry $i")
            last = addr + len
            dir[addr] = Triple(i, len, kind)
        }
        for (i in envCount until NesPlayerImage.MAX_ENVELOPES) assertEquals(0, u16(image, NesPlayerImage.HEADER_SIZE + 4 * i))
        // FamiStudio header.
        assertEquals(1, u8(data, 0))
        val instruments = at(u16(data, 1))
        val channels = player.channels
        assertEquals(song.nes.expansion.channels, channels, "$name: a stream per channel")
        val streams = IntArray(channels) { at(u16(data, firstStream + 2 * it)) }
        assertEquals(FamiStudioLowering.TEMPO_PAL, u16(data, firstStream + 2 * channels))
        assertEquals(FamiStudioLowering.TEMPO_NTSC, u16(data, firstStream + 2 + 2 * channels))
        for (slot in 0 until NesSystem.SLOTS) for (kind in 0 until 4) {
            val addr = u16(data, instruments + slot * 8 + kind * 2)
            assertEquals(slot * 4 + kind to kind, dir[addr]!!.let { it.first to it.third }, "$name: instrument $slot envelope $kind")
        }
        if (n163) checkN163(name, song, data, at(u16(data, 3)), dir, base)
        else if (fds) checkFds(name, song, data, at(u16(data, 3)), dir, base)
        else if (s5b) {
            // FamiStudio's 5B instruments, one per slot after the 2A03 table: volume, arpeggio and pitch envelopes, then
            // the mixer and noise envelopes, the shape ($08-$0F or 0), the octave (-8..8, or $80 = manual), the manual
            // period, two zero bytes; all from the sound.
            val expAt = at(u16(data, 3))
            assertEquals(instruments + NesSystem.SLOTS * 8, expAt, "$name: expansion instruments follow the 2A03 table")
            for (slot in 0 until NesSystem.SLOTS) {
                val e = expAt + FamiStudioLowering.EXP_INSTRUMENT_SIZE * slot
                for ((k, kind) in listOf(0, 1, 3).withIndex()) {
                    assertEquals(slot * 4 + kind to kind, dir[u16(data, e + 2 * k)]!!.let { it.first to it.third }, "$name: 5B instrument $slot envelope $kind")
                }
                val ins = song.nes.instruments[slot]
                assertEquals(256 + slot * 2 to 5, dir[u16(data, e + 6)]!!.let { it.first to it.third }, "$name: 5B mixer of $slot")
                assertEquals(257 + slot * 2 to 6, dir[u16(data, e + 8)]!!.let { it.first to it.third }, "$name: 5B noise of $slot")
                assertEquals(ins.s5b.shape.register, u8(data, e + 10), "$name: 5B shape of $slot")
                assertEquals(if (ins.s5b.autoPitch) ins.s5b.octave and 0xff else 0x80, u8(data, e + 11), "$name: 5B octave of $slot")
                assertEquals(ins.s5b.period, u16(data, e + 12), "$name: 5B period of $slot")
                assertEquals(0, u16(data, e + 14), "$name: 5B padding of $slot")
            }
        } else if (exp) {
            // FamiStudio's VRC7 instruments, one per slot after the 2A03 table: volume, arpeggio and pitch envelopes (the
            // slot's directory entries), the song's sustain overrides, patch << 4, then the 8 registers the patch plays.
            val expAt = at(u16(data, 3))
            assertEquals(instruments + NesSystem.SLOTS * 8, expAt, "$name: expansion instruments follow the 2A03 table")
            for (slot in 0 until NesSystem.SLOTS) {
                val e = expAt + FamiStudioLowering.EXP_INSTRUMENT_SIZE * slot
                for ((k, kind) in listOf(0, 1, 3).withIndex()) {
                    assertEquals(slot * 4 + kind to kind, dir[u16(data, e + 2 * k)]!!.let { it.first to it.third }, "$name: VRC7 instrument $slot envelope $kind")
                }
                val ins = song.nes.instruments[slot]
                assertEquals(song.nes.vrc7Sustain.engine, u8(data, e + 6), "$name: VRC7 instrument $slot sustain overrides")
                assertEquals(ins.fm.preset shl 4, u8(data, e + 7), "$name: VRC7 instrument $slot patch")
                assertEquals(ins.fm.sounding, (0 until 8).map { u8(data, e + 8 + it) }, "$name: VRC7 instrument $slot registers")
            }
        }
        val arranged = NesSystem.arranged(song)
        val orders = arranged.orders.size
        for (c in 0 until channels) {
            val s = streams[c]
            for (o in 0 until orders) {
                assertEquals(0x41, u8(data, s + 4 * o), "$name: channel $c order $o")
                val n = u8(data, s + 4 * o + 1)
                val rows = arranged.patterns[arranged.orders[o]].length
                assertEquals(rows, walk(data, at(u16(data, s + 4 * o + 2)), n, c, dir, base, song.nes.expansion), "$name: channel $c order $o rows")
            }
            assertEquals(0x42, u8(data, s + 4 * orders))
            assertEquals(base + s + 4 * arranged.loopOrder, u16(data, s + 4 * orders + 1))
        }
    }

    /**
     * The N163 expansion table: every slot's volume, arpeggio and pitch envelopes (the 2A03 table's), its wave-index
     * envelope (directory 256 + slot, kind 4, FamiStudio's bytes), wave position and size, a table of pointers to its
     * stored waves (packed two samples a byte, low nibble first) and 4 zero bytes.
     */
    private fun checkN163(name: String, song: Song, data: ByteArray, table: Int, dir: Map<Int, Triple<Int, Int, Int>>, base: Int) {
        val instruments = u16(data, 1) - base
        for ((slot, ins) in song.nes.instruments.withIndex()) {
            val e = table + FamiStudioLowering.EXP_INSTRUMENT_SIZE * slot
            for ((w, kind) in listOf(0 to 0, 1 to 1, 2 to 3)) {
                assertEquals(u16(data, instruments + slot * 8 + kind * 2), u16(data, e + 2 * w), "$name: slot $slot exp envelope $kind")
            }
            val wave = dir[u16(data, e + 6)]!!
            assertEquals(FamiStudioLowering.WAVE_ENVELOPES_START + slot to 4, wave.first to wave.third, "$name: slot $slot wave index")
            val bytes = FamiStudioEnvelopes.waveIndex(ins.wave)
            assertContentEquals(bytes, data.copyOfRange(u16(data, e + 6) - base, u16(data, e + 6) - base + bytes.size), "$name: slot $slot wave index bytes")
            assertEquals(ins.wave.position, u8(data, e + 8), "$name: slot $slot wave position")
            assertEquals(ins.wave.size, u8(data, e + 9), "$name: slot $slot wave size")
            val waves = u16(data, e + 10) - base
            for ((i, w) in FamiStudioEnvelopes.n163Waves(ins.wave).stored.withIndex()) {
                val at = u16(data, waves + 2 * i) - base
                val packed = data.copyOfRange(at, at + ins.wave.size / 2)
                for (s in w.indices) assertEquals(w[s], (u8(packed, s / 2) shr (4 * (s % 2))) and 0xF, "$name: slot $slot wave $i sample $s")
            }
            assertTrue((12 until 16).all { data[e + it] == 0.toByte() }, "$name: slot $slot exp bytes 12-15")
        }
    }

    /**
     * The FDS expansion table (`FamitoneMusicFile.OutputInstruments`): every slot's volume, arpeggio and pitch envelopes
     * (the 2A03 table's), its wave-index envelope (directory 256 + slot, kind 4, FamiStudio's bytes), (mod depth << 2) |
     * master volume, a table of pointers to its stored waves (64 bytes 0..63 each), its modulation table (32 bytes 0..7,
     * [FdsSound.modTable]), the manual mod speed (low byte, high nibble; with a low byte of $80-$FF bit 7 of the high
     * byte tags it as manual for Thor Tracker's FDS player) or $80 | numerator and the denominator (reduced), and the mod
     * delay.
     */
    private fun checkFds(name: String, song: Song, data: ByteArray, table: Int, dir: Map<Int, Triple<Int, Int, Int>>, base: Int) {
        val instruments = u16(data, 1) - base
        for ((slot, ins) in song.nes.instruments.withIndex()) {
            val e = table + FamiStudioLowering.EXP_INSTRUMENT_SIZE * slot
            val f = ins.fds
            for ((w, kind) in listOf(0 to 0, 1 to 1, 2 to 3)) {
                assertEquals(u16(data, instruments + slot * 8 + kind * 2), u16(data, e + 2 * w), "$name: slot $slot exp envelope $kind")
            }
            val wave = dir[u16(data, e + 6)]!!
            assertEquals(FamiStudioLowering.WAVE_ENVELOPES_START + slot to 4, wave.first to wave.third, "$name: slot $slot wave index")
            val bytes = FamiStudioEnvelopes.waveIndex(f)
            assertContentEquals(bytes, data.copyOfRange(u16(data, e + 6) - base, u16(data, e + 6) - base + bytes.size), "$name: slot $slot wave index bytes")
            assertEquals((f.modDepth shl 2) or f.masterVolume.register, u8(data, e + 8), "$name: slot $slot depth and master volume")
            val waves = u16(data, e + 9) - base
            for ((i, w) in FamiStudioEnvelopes.fdsWaves(f).stored.withIndex()) {
                val at = u16(data, waves + 2 * i) - base
                assertEquals(w, data.copyOfRange(at, at + FdsSound.WAVE_SIZE).map { it.toInt() }, "$name: slot $slot wave $i")
            }
            val mod = u16(data, e + 11) - base
            assertEquals(f.modTable, data.copyOfRange(mod, mod + FdsSound.MOD_SIZE).map { it.toInt() }, "$name: slot $slot mod table")
            if (f.autoMod) {
                assertEquals(0x80 or f.autoModFraction.first, u8(data, e + 13), "$name: slot $slot auto-mod numerator")
                assertEquals(f.autoModFraction.second, u8(data, e + 14), "$name: slot $slot auto-mod denominator")
            } else {
                val low = u8(data, e + 13)
                val high = u8(data, e + 14)
                assertEquals(f.modSpeed, low or ((high and 0x0F) shl 8), "$name: slot $slot mod speed")
                assertEquals(if (low and 0x80 != 0) 0x80 else 0, high and 0xF0, "$name: slot $slot manual speed tag")
            }
            assertEquals(f.modDelay, u8(data, e + 15), "$name: slot $slot mod delay")
        }
    }

    /** Walks [n] row terminators of a flat pattern stream; returns the rows they cover. Checks opcode limits. */
    private fun walk(data: ByteArray, start: Int, n: Int, channel: Int, dir: Map<Int, Triple<Int, Int, Int>>, base: Int, expansion: NesExpansion): Int {
        val s5b = NesRules.isS5b(expansion, channel)
        val fds = NesRules.isFds(expansion, channel)
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
                    0x45 -> {
                        assertFalse(NesRules.isVrc7(expansion, channel), "FM channels release with \$56")
                        assertFalse(NesRules.isN163(expansion, channel), "N163 channels release with \$5A")
                        assertFalse(fds, "the FDS releases with \$59")
                        rows++; terms++; delays = 0
                    }
                    0x56 -> { assertTrue(NesRules.isVrc7(expansion, channel), "\$56 off the VRC7 FM channels"); rows++; terms++; delays = 0 }
                    0x5a -> { assertTrue(NesRules.isN163(expansion, channel), "N163 release off the N163"); rows++; terms++; delays = 0 }
                    0x5b -> assertTrue(NesRules.isN163(expansion, channel), "N163 phase reset off the N163")
                    // The FDS: release, mod speed (≤ $FFF), mod depth (≤ $3F) and the generic phase reset.
                    0x59 -> { assertTrue(fds, "FDS release off the FDS"); rows++; terms++; delays = 0 }
                    0x57 -> { assertTrue(fds, "FDS mod speed off the FDS"); assertTrue(u16(data, p) <= 0xFFF); p += 2 }
                    0x58 -> { assertTrue(fds, "FDS mod depth off the FDS"); assertTrue(u8(data, p++) <= 0x3F) }
                    0x53 -> assertTrue(fds, "phase reset \$53 off the FDS")
                    0x50 -> { p += 3; rows++; terms++; delays = 0 }
                    0x43, 0x4a, 0x4c, 0x4d -> Unit
                    0x46 -> assertTrue(u8(data, p++) in 1..127)
                    0x47 -> { p++; delays++; assertTrue(delays == 1, "one note delay per row") }
                    0x48 -> p++
                    0x4e -> { assertTrue(channel != NesRules.NOISE); p++ }
                    0x4f -> {
                        val kind = NesRules.kind(expansion, channel)
                        assertTrue((kind == ChannelKind.PULSE || kind == ChannelKind.NOISE) && !s5b, "duty on ${NesRules.channelName(expansion, channel)}")
                        assertTrue(u8(data, p++) in 0..NesRules.dutyMax(expansion, channel))
                    }
                    0x55 -> { assertTrue(NesRules.isVrc6Saw(expansion, channel), "saw master volume off the saw"); assertTrue(u8(data, p++) in setOf(0x00, 0x01, 0xff)) }
                    0x5e -> { assertTrue(s5b && channel == NesRules.S5B_SQUARE1, "5B envelope period off 5B Square 1"); p += 2 }
                    0x5f -> { assertTrue(s5b || fds, "state-keeping instrument load off the expansion channels"); assertTrue(u8(data, p++) < NesSystem.SLOTS) }
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

    /**
     * The FDS variant: its own engine and image; notes are true pitch, FamiStudio's engine note 24 higher (A-4 = engine
     * note 70 = frequency 1031: 1789773 × 1031 / 2^22 = 439.9 Hz with a one-cycle wave), up to G#6; FDS sounds only on
     * the FDS channel.
     */
    @Test
    fun fdsEngineNumbers() {
        val e = NesFdsSystem.engine
        val base = NesFdsSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[1] = NesInstrument("Sine", chip = NesChip.FDS)
        val song = base.copy(sound = base.nes.copy(instruments = bank))
        assertEquals(70, e.note(song, NesRules.FDS, 57, 2))
        assertEquals(1031, FamiStudioLowering.NOTE_TABLE_FDS[70])
        assertEquals(440.0, 1789773.0 * FamiStudioLowering.NOTE_TABLE_FDS[70] / 4194304.0, 0.2)
        assertEquals(46, e.note(song, 0, 57, 1)) // the 2A03 keeps its notes
        assertEquals(null, e.auditionProblem(song, NesRules.FDS, 12, 2, 0)) // C-1
        assertEquals(null, e.auditionProblem(song, NesRules.FDS, 80, 2, 0)) // G#6
        val image = ByteArray(e.imageSize)
        e.lower(song, image)
        check("fds blank", song, image)
        // Manual mod speeds with a low byte of $80-$FF keep the image contract (tagged in the high byte).
        val speeds = NesGoldenSongs.fdsManualSpeeds()
        val speedImage = ByteArray(e.imageSize)
        e.lower(speeds, speedImage)
        check("fds manual speeds", speeds, speedImage)
        assertFailsWith<IllegalArgumentException> { NesSystem.engine.lower(song, ByteArray(NesSystem.engine.imageSize)) }
    }

    /** The VRC7 variant: eleven channels, its own engine and song base; FM channels reach C-1 and take only VRC7 sounds. */
    @Test
    fun vrc7EngineNumbers() {
        val e = NesVrc7System.engine
        val base = NesVrc7System.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[1] = NesInstrument("Piano", chip = NesChip.VRC7, fm = Vrc7Patch().withPreset(3))
        val song = base.copy(sound = base.nes.copy(instruments = bank))
        val fm = NesRules.VRC7_FM1
        val auditionNote = e.note(song, fm, 57, 2)
        val auditionHz = FamiStudioLowering.NOTE_TABLE_VRC7[auditionNote] * 1789773.0 / (36.0 * 524288.0)
        assertTrue(kotlin.math.abs(auditionHz - 440.0) < 2.0, "A-4 audition: $auditionHz Hz")
        assertEquals(null, e.auditionProblem(song, fm, 12, 2, 0))
        assertEquals(null, e.auditionProblem(song, fm, 95, 2, 0))
        assertNotEquals(null, e.auditionProblem(song, fm, 96, 2, 0))
        assertEquals(null, NesRules.cellProblem(song, fm, Cell(95, 2)))
        assertNotEquals(null, NesRules.cellProblem(song, fm, Cell(96, 2)))
        assertNotEquals(null, NesRules.cellProblem(song, fm, Cell(95, 2, NesRules.FX_SLIDE_UP, 1)))
        val pattern = song.patterns[0]
        val sounding = song.copy(patterns = listOf(pattern.copy(tracks = pattern.tracks.mapIndexed { ch, rows ->
            if (ch == fm) rows.toMutableList().also { it[0] = Cell(57, 2) } else rows
        })))
        val lowered = FamiStudioLowering.lower(sounding)
        val registers = IntArray(64)
        var selected = 0
        for (write in NesTracer(lowered.data, lowered.player).frame()) {
            when (write ushr 8) {
                0x9010 -> selected = write and 0xff
                0x9030 -> registers[selected] = write and 0xff
            }
        }
        val fnum = registers[0x10] or ((registers[0x20] and 1) shl 8)
        val block = (registers[0x20] shr 1) and 7
        val playedHz = 1789773.0 / 36.0 * fnum / (1 shl (19 - block))
        assertTrue(kotlin.math.abs(playedHz - 440.0) < 2.0, "A-4 through the real driver: $playedHz Hz")
        assertTrue(e.auditionProblem(song, fm, 57, 1, 0)!!.contains("2A03 sound"))
        assertTrue(e.auditionProblem(song, 0, 57, 2, 0)!!.contains("VRC7 sound"))
        // Fine pitch: a 2A03 period offset (up = shorter period), on FM the frequency step (× 8) nearest the same detune.
        assertEquals(-5, e.fine(0, 57, 5))
        val period = FamiStudioLowering.NOTE_TABLE[46] + 1.0
        val freq = FamiStudioLowering.NOTE_TABLE_VRC7[auditionNote].toDouble()
        for (fine in listOf(-40, -5, 5, 40)) {
            val pulseCents = 1200 * kotlin.math.log2(period / (period - fine))
            val fmCents = 1200 * kotlin.math.log2((freq + 8 * e.fine(fm, 57, fine)) / freq)
            // A half register step spans more cents after bending down than at the unshifted note.
            val targetFreq = freq * period / (period - fine)
            val halfStep = 1200 * kotlin.math.log2(targetFreq / (targetFreq - 4))
            assertTrue(kotlin.math.abs(fmCents - pulseCents) <= halfStep, "Pxx $fine: FM $fmCents cents, pulse $pulseCents")
        }
        val image = ByteArray(e.imageSize)
        e.lower(song, image)
        check("vrc7 blank", song, image)
        assertFailsWith<IllegalArgumentException> { NesVrc6System.engine.lower(song, ByteArray(NesVrc6System.engine.imageSize)) }
    }

    /** A loop's first custom note must restore its timbre after another channel changed the shared patch. */
    @Test
    fun vrc7LoopRestoresItsSharedCustomPatch() {
        val base = NesVrc7System.blank()
        val bank = base.nes.instruments.toMutableList()
        val lead = listOf(0x21, 0x61, 0x1A, 0x07, 0xF0, 0x80, 0x0F, 0x26)
        val bass = listOf(0x11, 0x01, 0x8A, 0x47, 0xF9, 0xF5, 0x13, 0x26)
        bank[0] = NesInstrument("Lead", volume = NesEnvelope(listOf(15, 12, 9, 6), loop = 3),
            chip = NesChip.VRC7, fm = Vrc7Patch(Vrc7Presets.CUSTOM, lead))
        bank[1] = NesInstrument("Bass", chip = NesChip.VRC7, fm = Vrc7Patch(Vrc7Presets.CUSTOM, bass))
        val fm1 = NesRules.VRC7_FM1
        fun song(otherCustom: Boolean, delayedBoundary: Boolean, legato: Boolean): Song {
            val blank = base.patterns[0]
            val first = blank.copy(tracks = blank.tracks.mapIndexed { ch, t -> if (ch == fm1) t.toMutableList().also { it[0] = Cell(57, 1) } else t })
            val loop = blank.copy(name = "Loop", tracks = blank.tracks.mapIndexed { ch, t ->
                when {
                    ch == fm1 -> t.toMutableList().also {
                        it[0] = Cell(60, if (legato) 0 else 1)
                        if (legato) it[16] = Cell(64)
                        if (delayedBoundary) it[it.lastIndex] = Cell(60, 1)
                    }
                    ch == fm1 + 1 && otherCustom -> t.toMutableList().also {
                        if (delayedBoundary) it[it.lastIndex - 1] = Cell(45, 2, NesRules.FX_NOTE_DELAY, 7)
                        else it[8] = Cell(45, 2)
                    }
                    else -> t
                }
            })
            return base.copy(patterns = listOf(first, loop), orders = listOf(0, 1), loopOrder = 1, sound = base.nes.copy(instruments = bank))
        }
        for ((otherCustom, delayedBoundary, legato) in listOf(
            Triple(false, false, false), Triple(true, false, false), Triple(true, true, false), Triple(true, false, true)
        )) {
            val s = song(otherCustom, delayedBoundary, legato)
            val fs = FsSong.of(s)
            val lowered = FamiStudioLowering.lower(s)
            val tracer = NesTracer(lowered.data, lowered.player)
            val registers = IntArray(64)
            var selected = 0
            val firstLoop = fs.orderStart[fs.loopOrder] * fs.speed
            val loopTicks = (fs.totalRows - fs.orderStart[fs.loopOrder]) * fs.speed
            for (frame in 0 until firstLoop + 3 * loopTicks) {
                for (write in tracer.frame()) {
                    when (write ushr 8) {
                        0x9010 -> selected = write and 0xff
                        0x9030 -> {
                            registers[selected] = write and 0xff
                            val inLoop = frame - firstLoop
                            if (legato && selected == 0x20 && inLoop >= 0 &&
                                inLoop % loopTicks in listOf(0, 16 * fs.speed)
                            ) {
                                assertTrue(write and 0x10 != 0, "restoring a shared patch must not key off a legato note")
                            }
                        }
                    }
                }
                if (frame < firstLoop) continue
                val loopFrame = (frame - firstLoop) % loopTicks
                if (loopFrame == 1 || legato && loopFrame == 16 * fs.speed + 1) {
                    assertContentEquals(lead, registers.take(8), "restored lead patch at frame $frame")
                    if (legato) assertEquals(9, registers[0x30] and 15, "patch restoration must keep the held volume envelope")
                } else if (loopFrame == if (delayedBoundary) loopTicks - fs.speed + 2 else 8 * fs.speed + 1) {
                    assertContentEquals(if (otherCustom) bass else lead, registers.take(8), "shared patch at frame $frame")
                }
            }
        }
    }

    /**
     * FamiStudio's export merges renamed identical sounds into one instrument (`Project.MergeIdenticalInstruments`), so
     * a song playing two copies must sound exactly like the same song playing one of them, legato loop entries
     * included; the streams still load each note's own slot (live edits of a copy reach its notes).
     */
    @Test
    fun renamedCopiesPlayAsOneInstrument() {
        fun replaced(song: Song, map: Map<Int, Int>) = song.copy(patterns = song.patterns.map { p ->
            p.copy(tracks = p.tracks.map { t -> t.map { c -> map[c.instrument]?.let { c.copy(instrument = it) } ?: c } })
        })
        for ((song, twins) in listOf(
            NesGoldenSongs.copies() to mapOf(2 to 1),
            NesGoldenSongs.vrc6Copies() to mapOf(2 to 1, 5 to 4),
            NesGoldenSongs.n163Copies() to mapOf(2 to 1),
            NesGoldenSongs.s5bCopies() to mapOf(2 to 1, 5 to 4),
            // Manual-period copies: a load of the other copy must not move the shared period FamiStudio's merged export holds.
            NesGoldenSongs.s5bManualCopies() to mapOf(2 to 1),
        )) {
            val one = replaced(song, twins)
            val lowered = FamiStudioLowering.lower(song)
            val single = FamiStudioLowering.lower(one)
            assertFalse(lowered.data.contentEquals(single.data), "${song.title}: the copies keep their own slots")
            val fs = FsSong.of(song)
            val frames = (fs.totalRows + 2 * (fs.totalRows - fs.orderStart[fs.loopOrder])) * fs.speed + 120
            val a = NesTracer(lowered.data, lowered.player)
            val b = NesTracer(single.data, single.player)
            for (f in 0 until frames) {
                val ta = a.frame()
                val tb = b.frame()
                assertContentEquals(tb, ta, "${song.title}: frame $f: copies ${NesTracer.text(ta)} vs one sound ${NesTracer.text(tb)}")
            }
        }
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

    /** The 5B variant: its own engine and image; 5B squares take 5B sounds and reach every note; a blank song's image. */
    @Test
    fun s5bEngineNumbers() {
        val e = NesS5bSystem.engine
        val base = NesS5bSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[1] = NesInstrument("Buzz", chip = NesChip.S5B, s5b = S5bEnvelope(S5bShape.SAW_DOWN, octave = -2))
        val song = base.copy(sound = base.nes.copy(instruments = bank))
        assertEquals("nes-famistudio-s5b", e.id)
        assertEquals(NesS5bPlayerImage.IMAGE_SIZE, e.imageSize)
        assertEquals(NesS5bPlayerImage.SONG_BASE, NesPlayer.S5B.songBase)
        assertEquals(0x20, NesPlayer.S5B.nsfChips)
        assertEquals(8, NesS5bSystem.channels.size)
        // True pitch: A-4 is FamiStudio's A-5 there (period 126 + 1: CPU / 32 / 127 = 440.4 Hz, as the pulses' A-4).
        assertEquals(58, e.note(song, NesRules.S5B_SQUARE1, 57, 2))
        assertEquals(-4, e.fine(NesRules.S5B_SQUARE1, 57, 8)) // 8 pulse units on A-4 (period 254) = 4 on the 5B's 127
        assertEquals(null, e.auditionProblem(song, NesRules.S5B_SQUARE1, 12, 2, 0)) // C-1
        assertTrue(e.auditionProblem(song, NesRules.S5B_SQUARE1, 96, 2, 0)!!.contains("B-7")) // C-8: FamiStudio note 97
        assertTrue(e.auditionProblem(song, NesRules.S5B_SQUARE1, 57, 1, 0)!!.contains("2A03 sound"))
        assertTrue(e.auditionProblem(song, 0, 57, 2, 0)!!.contains("5B sound"))
        val image = ByteArray(e.imageSize)
        e.lower(song, image)
        check("s5b blank", song, image)
        // Effect envelopes start after the 64 slots' mixer and noise envelopes.
        val lowered = FamiStudioLowering.lower(song)
        assertEquals(NesS5bPlayerImage.EFFECT_ENVELOPES, lowered.envelopes.filter { it.kind == FamiStudioEnvelopes.Kind.ARPEGGIO }.maxOf { it.index })
        assertTrue(lowered.envelopes.filter { it.index >= NesS5bPlayerImage.EFFECT_ENVELOPES }.none { it.kind == FamiStudioEnvelopes.Kind.MIXER })
        // A 5B mixer is FamiStudio's processed bits: tone only (the default) is $08, held.
        assertContentEquals(byteArrayOf(0xc8.toByte(), 0x7f, 0x00, 0x00), lowered.envelopes.first { it.index == FamiStudioLowering.S5B_ENVELOPES + 2 }.bytes)
        // A-4 through the real driver: the square's 12-bit tone period ($00/$01 through $C000/$E000) sounds 440 Hz.
        val sq1 = NesRules.S5B_SQUARE1
        val toneBank = song.nes.instruments.toMutableList().also { it[2] = NesInstrument("Tone", chip = NesChip.S5B) }
        val pattern = song.patterns[0]
        val sounding = song.copy(
            patterns = listOf(pattern.copy(tracks = pattern.tracks.mapIndexed { ch, rows ->
                if (ch == sq1) rows.toMutableList().also { it[0] = Cell(57, 3) } else rows
            })),
            sound = song.nes.copy(instruments = toneBank),
        )
        val played = FamiStudioLowering.lower(sounding)
        val registers = IntArray(16)
        var selected = 0
        for (write in NesTracer(played.data, played.player).frame()) {
            when (write ushr 8) {
                in 0xC000..0xDFFF -> selected = write and 0x0f
                in 0xE000..0xFFFF -> registers[selected] = write and 0xff
            }
        }
        val period = registers[0] or ((registers[1] and 0x0f) shl 8)
        val playedHz = 1789773.0 / (32.0 * period)
        assertTrue(kotlin.math.abs(playedHz - 440.0) < 2.0, "A-4 through the real driver: $playedHz Hz (period $period)")
        // The squares stop at B-7: notes and slides above it are refused before the engine.
        assertEquals(null, NesRules.cellProblem(song, sq1, Cell(95, 2)))
        assertNotEquals(null, NesRules.cellProblem(song, sq1, Cell(96, 2)))
        assertNotEquals(null, NesRules.cellProblem(song, sq1, Cell(95, 2, NesRules.FX_SLIDE_UP, 1)))
        // Ixx/Jxx exist only on 5B songs and only on the squares; the phase reset stays the N163's.
        val effects = NesS5bSystem.effects.map { it.code }
        assertTrue(NesRules.FX_ENV_PERIOD_HI in effects && NesRules.FX_ENV_PERIOD_LO in effects && NesRules.FX_PHASE_RESET !in effects)
        assertFalse(NesSystem.effects.any { it.code == NesRules.FX_ENV_PERIOD_HI || it.code == NesRules.FX_ENV_PERIOD_LO })
        assertFalse(NesVrc7System.effects.any { it.code == NesRules.FX_ENV_PERIOD_HI })
        val square = NesS5bSystem.effectsFor(sq1).map { it.code }
        assertTrue(NesRules.FX_ENV_PERIOD_HI in square && NesRules.FX_DUTY !in square && NesRules.FX_FINE_PITCH in square)
        assertFalse(NesRules.FX_ENV_PERIOD_HI in NesS5bSystem.effectsFor(0).map { it.code })
        assertNotEquals(null, NesRules.cellProblem(song, 0, Cell(57, 1, NesRules.FX_ENV_PERIOD_HI, 0x01)))
    }

    @Test
    fun s5bRejectsConflictingSharedEnvelopePeriodBytes() {
        val base = NesS5bSystem.blank()
        val tracks = base.patterns[0].tracks.map { it.toMutableList() }
        tracks[NesRules.S5B_SQUARE1][2] = Cell(Cell.EMPTY, 0, NesRules.FX_ENV_PERIOD_HI, 0x12)
        tracks[NesRules.S5B_SQUARE1 + 1][2] = Cell(Cell.EMPTY, 0, NesRules.FX_ENV_PERIOD_HI, 0x13)
        val song = base.copy(patterns = listOf(base.patterns[0].copy(tracks = tracks)))
        assertFailsWith<IllegalArgumentException> { FamiStudioLowering.lower(song) }
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
