package org.thortracker.system.ym2612

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.YmGoldenSongs
import org.thortracker.backend.YmLowering
import org.thortracker.backend.YmPcmLowering
import org.thortracker.backend.YmPlayerImage
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.ProjectCodec
import org.thortracker.model.ProjectFormatException
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.model.Tuning
import org.thortracker.system.nes.PcmData
import org.thortracker.system.ym2612.YmInstrumentView.Page
import org.thortracker.system.ym2612.YmInstrumentView.Row
import java.math.BigInteger

/**
 * The Mega Drive's XGM2 data on the Kotlin side: the version 1 → 2 project migration (thirteen lanes, nothing lost),
 * the layout 2 image (the tonal 64 KB prefix and its old capacity, FM 3 offsets, PCM orders, keys, variants and bank),
 * the PCM bank compiler (integer step, dedup, the 123-sound and 1 MiB limits, never cutting), malformed samples and
 * mappings, PCM-lane effects and keys, the DAC and FM 3 warnings, WAV import into signed bytes, the PCM and Ch3 editor
 * pages, and the Night Ferry and shortest fixtures.
 */
class YmXgm2Test {
    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF
    private fun u16(b: ByteArray, at: Int) = u8(b, at) or (u8(b, at + 1) shl 8)

    private fun slot(name: String) = YmPresets.ALL.indexOfFirst { it.name == name } + 1

    private fun with(song: Song, channel: Int, row: Int, cell: Cell, pattern: Int = 0): Song {
        val p = song.patterns[pattern]
        val tracks = p.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[pattern] = p.copy(tracks = tracks) })
    }

    private fun withSound(song: Song, f: (YmSound) -> YmSound): Song = song.copy(sound = f(song.ym))

    private val ramp = PcmData.of(ByteArray(300) { (it * 7).toByte() })

    /** A blank song with the synthesized kit: slot 50 the drums (kick C-3, snare D-3, hat F#3), slot 51 the chromatic bass. */
    private fun kitSong(): Song = withSound(YmSystem.blank()) { s ->
        s.copy(instruments = s.instruments.toMutableList().also { it[49] = YmPcmKit.drums(0); it[50] = YmPcmKit.bassKit(0) },
            pcmSamples = YmPcmKit.samples())
    }

    private fun kit(vararg keys: Pair<Int, YmPcmMapping>, level: Int = 15) = YmPcmInstrument("kit", level, keys.toMap())

    private fun soundWith(samples: List<YmPcmSample>, vararg kits: Pair<Int, YmPcmInstrument>): YmSound =
        YmSystem.blank().ym.let { s -> s.copy(instruments = s.instruments.toMutableList().also { b -> for ((n, k) in kits) b[n - 1] = k }, pcmSamples = samples) }

    // ---- project migration -------------------------------------------------------------------------------------------

    /**
     * [song]'s project as Mega Drive data version 1 wrote it: ten tracks per pattern (played or not), no FM 3 mode, no
     * PCM samples and no FM 3 offsets.
     */
    private fun versionOne(song: Song, tracks: Int = YmRules.TONAL_CHANNELS): String {
        val tree = Json.parseToJsonElement(ProjectCodec.encode(song)) as JsonObject
        val songJson = tree["song"] as JsonObject
        val patterns = (songJson["patterns"] as JsonArray).map { p ->
            val o = p as JsonObject
            JsonObject(o + ("tracks" to JsonArray((o["tracks"] as JsonArray).take(tracks))))
        }
        val sound = songJson["sound"] as JsonObject
        val instruments = (sound["instruments"] as JsonArray).map { i -> if (i is JsonObject) JsonObject(i - "ch3") else i }
        val oldSound = JsonObject(sound - "fm3Mode" - "pcmSamples" + ("instruments" to JsonArray(instruments)))
        return JsonObject(tree + mapOf(
            "backendVersion" to JsonPrimitive(1),
            "song" to JsonObject(songJson + mapOf("patterns" to JsonArray(patterns), "sound" to oldSound)),
        )).toString()
    }

    @Test
    fun versionOneProjectsGainThreeEmptyPcmTracksAndLoseNothing() {
        // Effect columns, PSG envelopes, PAL, parked orders and a pattern no order plays, plus a tuning.
        val features = YmGoldenSongs.features()
        val unplayed = features.patterns[0].copy(name = "Unplayed", length = 17)
        val song = features.copy(
            patterns = features.patterns + unplayed,
            tuning = Tuning("12-TET", (1..12).map { it * 100.0 }, root = 57),
        )
        val old = versionOne(song)
        assertTrue(old.contains("\"backendVersion\":1"))
        assertFalse(old.contains("pcmSamples") || old.contains("fm3Mode") || old.contains("\"ch3\""))
        val upgraded = ProjectCodec.decode(old)
        assertEquals(song, upgraded)
        for (p in upgraded.patterns) {
            assertEquals(YmRules.CHANNELS, p.tracks.size)
            for (k in YmRules.PCM_FIRST until YmRules.CHANNELS) assertTrue(p.tracks[k].size == Songs.ROWS && p.tracks[k].all { it.isBlank })
        }
        assertEquals(listOf(1), upgraded.parkedOrders)
        assertEquals(17, upgraded.patterns.last().length)
        assertEquals(YmFm3Mode.NORMAL, upgraded.ym.fm3Mode)
        assertTrue(upgraded.ym.pcmSamples.isEmpty())
        // Saving writes version 2, and it reads back unchanged.
        val saved = ProjectCodec.encode(upgraded)
        assertTrue(saved.contains("\"backendVersion\":2"))
        assertEquals(upgraded, ProjectCodec.decode(saved))
    }

    @Test
    fun neonCausewayIsItsOwnVersionOneMigration() {
        // The bundled demo's music is unchanged: its version 1 project (ten tracks) upgrades to exactly the demo.
        val demo = YmSystem.demo()
        assertEquals(demo, ProjectCodec.decode(versionOne(demo)))
        assertEquals(YmFm3Mode.NORMAL, demo.ym.fm3Mode)
        assertTrue(demo.ym.pcmSamples.isEmpty())
        assertTrue(demo.ym.instruments.none { it is YmPcmInstrument || (it as? YmFmInstrument)?.hasCh3 == true })
    }

    @Test
    fun malformedOrNewerMegaDriveProjectsAreRefused() {
        // A version 1 pattern with eleven tracks is not a ten-track pattern: it stays as it is and fails the check.
        val eleven = versionOne(YmSystem.blank(), tracks = 11)
        assertTrue(assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(eleven) }.message!!.contains("channels"))
        val newer = ProjectCodec.encode(YmSystem.blank()).replace("\"backendVersion\":2", "\"backendVersion\":3")
        assertTrue(assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(newer) }.message!!.contains("data version 3"))
    }

    // ---- the image: tonal prefix and capacity ------------------------------------------------------------------------

    @Test
    fun layoutTwoKeepsTheTonalSixtyFourKilobytes() {
        assertEquals(2, YmPlayerImage.LAYOUT)
        assertEquals(65536, YmPlayerImage.OFF_DATA + YmPlayerImage.DATA_CAPACITY)
        assertEquals(57536, YmPlayerImage.DATA_CAPACITY)
        assertEquals(65536, YmPlayerImage.OFF_CH3)
        assertEquals(YmPlayerImage.IMAGE_SIZE, YmPlayerImage.OFF_PCM_BANK + YmPlayerImage.PCM_BANK_SIZE)
        assertEquals(1 shl 20, YmPlayerImage.PCM_BANK_SIZE)
        assertEquals(123, YmPlayerImage.PCM_MAX_VARIANTS)
        // Adding a PCM kit (in the empty last slot), samples and a PCM note changes nothing of the tonal image but the
        // instrument count, the header's new bytes and the kit's own record.
        val demo = YmSystem.demo()
        val kitSlot = YmSystem.SLOTS
        assertNull(demo.ym.instrument(kitSlot))
        val pcm = with(withSound(demo) { s ->
            s.copy(instruments = s.instruments.toMutableList().also { it[kitSlot - 1] = YmPcmKit.drums(0) }, pcmSamples = YmPcmKit.samples())
        }, YmRules.PCM_FIRST, 0, Cell(YmPcmKit.KICK_KEY, kitSlot))
        assertEquals(emptyList<String>(), YmSystem.errors(pcm))
        val a = YmLowering.lower(demo)
        val b = YmLowering.lower(pcm)
        val record = YmPlayerImage.OFF_INSTRUMENTS + YmPlayerImage.INSTRUMENT_SIZE * (kitSlot - 1)
        for (i in 0 until 65536) {
            if (i == YmPlayerImage.H_INSTRUMENTS || i in 16 until 24 || i in record until record + YmPlayerImage.INSTRUMENT_SIZE) continue
            assertEquals("tonal byte $i", a[i], b[i])
        }
        assertEquals(YmSystem.SLOTS, u8(b, YmPlayerImage.H_INSTRUMENTS))
        assertEquals(YmPlayerImage.FLAG_DEFINED or YmPlayerImage.FLAG_PCM, u8(b, record))
        assertTrue((1 until YmPlayerImage.INSTRUMENT_SIZE).all { b[record + it].toInt() == 0 })
        assertEquals(3, u8(b, YmPlayerImage.H_PCM_VARIANTS))
        // The PCM-free demo has an empty bank.
        assertEquals(0, u8(a, YmPlayerImage.H_PCM_VARIANTS))
        assertEquals(0, u16(a, YmPlayerImage.H_PCM_BANK_SIZE))
        assertTrue((YmPlayerImage.OFF_PCM_KEYS until YmPlayerImage.IMAGE_SIZE).all { it in YmPlayerImage.OFF_PCM_DATA until YmPlayerImage.OFF_PCM_BANK || a[it].toInt() == 0 })
    }

    /** A 64-row track encoding to exactly [bytes] (3..600): note-and-four-G rows, one shorter row, then one empty-row run. */
    private fun track(bytes: Int, seed: Int): List<Cell> {
        fun row(size: Int, r: Int): Cell {
            // Even sizes: a note and (size - 2) / 2 effects; odd sizes: (size - 1) / 2 effects and no note.
            val effects = if (size % 2 == 0) (size - 2) / 2 else (size - 1) / 2
            var c = Cell(note = if (size % 2 == 0) (seed * 7 + r) % 96 else Cell.EMPTY)
            val params = listOf(seed and 0xFF, seed shr 8 and 0xFF, r, 1)
            for (e in 0 until effects) c = c.withEffect(e, YmRules.FX_DELAY, params[e])
            return c
        }
        val content = bytes - 1 // the trailing empty rows' run byte
        var full = content / 10
        val sizes = ArrayList<Int>()
        when (val rem = content % 10) {
            0 -> Unit
            1 -> { full -= 1; sizes += listOf(5, 6) }
            else -> sizes += rem
        }
        val rows = List(full) { 10 } + sizes
        val out = List(Songs.ROWS) { r -> if (r < rows.size) row(rows[r], r) else Cell.BLANK }
        assertEquals(bytes, YmLowering.encode(out, Songs.ROWS).size)
        return out
    }

    /**
     * A song of [total] bytes of tonal pattern data and no instruments (so no envelopes): unique tracks on FM 1-6 and
     * PSG 1-3, the Noise lane blank (one shared run byte); with [pcm], busy PCM tracks the tonal data never counts.
     */
    private fun tonal(total: Int, pcm: Boolean = false): Song {
        val sizes = ArrayList<Int>()
        var left = total - 1 // the blank Noise track
        while (left > 602) { sizes += 600; left -= 600 }
        sizes += left
        val patterns = sizes.chunked(9).mapIndexed { p, chunk ->
            Pattern("P$p", List(YmRules.CHANNELS) { ch ->
                when {
                    ch < chunk.size -> track(chunk[ch], p * 9 + ch + 1)
                    pcm && YmRules.isPcm(ch) -> List(Songs.ROWS) { r -> Cell(note = (r + p + ch) % 96, instrument = 0) }
                    else -> List(Songs.ROWS) { Cell.BLANK }
                }
            })
        }
        val blank = YmSystem.blank()
        return blank.copy(patterns = patterns, orders = patterns.indices.toList(), sound = blank.ym.copy(instruments = List(YmSystem.SLOTS) { null }))
    }

    @Test
    fun theTonalCapacityIsExactlyLayoutOnesAndPcmDataDoesNotCount() {
        val full = tonal(YmPlayerImage.DATA_CAPACITY)
        assertEquals(YmPlayerImage.DATA_CAPACITY, YmLowering.dataSize(full))
        assertEquals(YmPlayerImage.DATA_CAPACITY, u16(YmLowering.lower(full), YmPlayerImage.H_DATA_SIZE))
        assertTrue(YmSystem.errors(full).none { it.contains("image holds") })
        val over = tonal(YmPlayerImage.DATA_CAPACITY + 1)
        assertEquals(YmPlayerImage.DATA_CAPACITY + 1, YmLowering.dataSize(over))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { YmLowering.lower(over) }.message!!.contains("Reuse patterns"))
        assertTrue(YmSystem.errors(over).joinToString().contains("Reuse patterns or shorten the song"))
        // Busy PCM lanes go to their own region.
        val withPcm = tonal(YmPlayerImage.DATA_CAPACITY, pcm = true)
        assertEquals(YmPlayerImage.DATA_CAPACITY, YmLowering.dataSize(withPcm))
        val image = YmLowering.lower(withPcm)
        assertTrue(YmLowering.pcmDataSize(withPcm) > 0)
        assertEquals(YmLowering.pcmDataSize(withPcm), u16(image, YmPlayerImage.H_PCM_DATA_SIZE))
    }

    // ---- the PCM bank compiler ---------------------------------------------------------------------------------------

    @Test
    fun stepIsTheExactIntegerRatioAtOctavePitches() {
        for (pal in listOf(false, true)) {
            val mclk = YmPcmLowering.mclk(pal)
            for (rate in listOf(1000, 8000, 22050, 48000)) for (pitch in listOf(-48, -36, -24, -12, 0, 12, 24)) {
                val octave = pitch / 12
                // floor(rate × 2^(32 + octave) × 4032 / MCLK), independently in arbitrary precision.
                val expected = BigInteger.valueOf(rate.toLong()).shiftLeft(32 + octave).multiply(BigInteger.valueOf(YmPlayerImage.PCM_FULL_DIVIDER.toLong()))
                    .divide(BigInteger.valueOf(mclk)).toLong()
                assertEquals("rate $rate pitch $pitch pal $pal", expected, YmPcmLowering.step(rate, pitch, mclk))
            }
        }
        // A semitone up shortens a sound by about 2^(1/12); the length covers every source position.
        val mclk = YmPcmLowering.mclk(false)
        val base = YmPcmLowering.step(22050, 0, mclk)
        val up = YmPcmLowering.step(22050, 1, mclk)
        assertEquals(Math.pow(2.0, 1 / 12.0), up.toDouble() / base, 1e-6)
        val n = YmPcmLowering.length(1000, base)
        assertTrue((n - 1).toLong() * base < (1000L shl 32) && n.toLong() * base >= (1000L shl 32))
    }

    @Test
    fun theBankIsDeterministicSignedPaddedAndShared() {
        val square = PcmData.of(ByteArray(1000) { (if ((it / 25) % 2 == 0) 100 else -100).toByte() })
        val samples = listOf(YmPcmSample("ramp", ramp, 8000), YmPcmSample("copy", ramp, 8000), YmPcmSample("square", square, 22050))
        val sound = soundWith(samples,
            2 to kit(36 to YmPcmMapping(0), 37 to YmPcmMapping(1), 38 to YmPcmMapping(2), 42 to YmPcmMapping(0)),
            3 to kit(36 to YmPcmMapping(2), level = 7),
            5 to kit(50 to YmPcmMapping(2, 3), level = 0),
        )
        val bank = YmPcmLowering.compile(sound)
        // The copy shares the ramp's variant by its bytes, key 42 by its (sample, pitch, level); level 7 is a new sound.
        assertEquals(listOf(1, 1, 2, 1), listOf(36, 37, 38, 42).map { bank.id(2, it) })
        assertEquals(3, bank.id(3, 36))
        assertEquals(4, bank.id(5, 50))
        assertEquals(0, bank.id(2, 39))
        assertEquals(4, bank.variants.size)
        for (v in bank.variants) {
            assertEquals(0, v.bytes.size % YmPlayerImage.PCM_ALIGN)
            assertTrue(v.bytes.size - v.length < YmPlayerImage.PCM_ALIGN)
            assertTrue((v.length until v.bytes.size).all { v.bytes[it].toInt() == 0 })
        }
        assertEquals(bank.variants.sumOf { it.pages }, bank.pages)
        // Level 15 keeps the first byte exactly (signed); level 7 scales it by its Q15 gain; level 0 is silence.
        assertEquals(100, bank.variants[1].bytes[0].toInt())
        assertEquals((100 * YmPcmLowering.LEVEL_Q15[7]) shr 15, bank.variants[2].bytes[0].toInt())
        assertTrue(bank.variants[3].bytes.all { it.toInt() == 0 })
        assertTrue(bank.variants[1].bytes.take(bank.variants[1].length).any { it < 0 })
        // The same inputs compile to the same bytes; PAL compiles at its own rate.
        val pal = YmPcmLowering.compile(sound.copy(pal = true))
        assertFalse(pal.variants[1].bytes.contentEquals(bank.variants[1].bytes))
        // (PAL replaced the compiler's memo: this compiles again from copied sources, to the same bytes.)
        val again = YmPcmLowering.compile(sound.copy(pcmSamples = samples.map { it.copy(data = PcmData.of(it.data.toByteArray())) }))
        for (i in bank.variants.indices) assertArrayEquals(bank.variants[i].bytes, again.variants[i].bytes)
        // In the image: the keys, the contiguous variant table and the bank.
        val song = YmSystem.blank().copy(sound = sound)
        val image = YmLowering.lower(song)
        assertEquals(4, u8(image, YmPlayerImage.H_PCM_VARIANTS))
        assertEquals(bank.pages, u16(image, YmPlayerImage.H_PCM_BANK_SIZE))
        assertEquals(2, u8(image, YmPlayerImage.OFF_PCM_KEYS + YmPlayerImage.PCM_KEYS * 1 + 38))
        var page = 0
        bank.variants.forEachIndexed { i, v ->
            val entry = YmPlayerImage.OFF_PCM_VARIANTS + YmPlayerImage.PCM_VARIANT_SIZE * i
            assertEquals(page, u16(image, entry))
            assertEquals(v.pages, u16(image, entry + 2))
            val at = YmPlayerImage.OFF_PCM_BANK + page * YmPlayerImage.PCM_ALIGN
            assertArrayEquals(v.bytes, image.copyOfRange(at, at + v.bytes.size))
            page += v.pages
        }
        val end = YmPlayerImage.OFF_PCM_BANK + page * YmPlayerImage.PCM_ALIGN
        assertTrue((end until YmPlayerImage.IMAGE_SIZE).all { image[it].toInt() == 0 })
    }

    @Test
    fun theBankHoldsOneHundredTwentyThreeSoundsAndNeverDropsOne() {
        val source = PcmData.of(ByteArray(2000) { (it * 7).toByte() })
        val samples = listOf(YmPcmSample("ramp", source, 48000))
        val a = kit(*(0..72).map { it to YmPcmMapping(0, it - 48) }.toTypedArray())
        val b = kit(*(0..49).map { it to YmPcmMapping(0, it - 48) }.toTypedArray(), level = 14)
        val fits = soundWith(samples, 1 to a, 2 to b)
        assertNull(YmPcmLowering.problem(fits))
        assertEquals(123, YmPcmLowering.compile(fits).variants.size)
        val tooMany = soundWith(samples, 1 to a, 2 to kit(*(0..50).map { it to YmPcmMapping(0, it - 48) }.toTypedArray(), level = 14))
        val problem = YmPcmLowering.problem(tooMany)!!
        assertTrue(problem, problem.contains("123"))
        val song = YmSystem.blank().copy(sound = tooMany)
        assertTrue(YmSystem.errors(song).joinToString().contains("123"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { YmLowering.lower(song) }.message!!.contains("123"))
        // Auditions say why too.
        assertTrue(YmSystem.engine.auditionProblem(song, YmRules.PCM_FIRST, 0, 1, 0)!!.contains("123"))
    }

    @Test
    fun theBankRefusesMoreThanOneMegabyteWithoutCompilingIt() {
        val long = YmPcmSample("long", PcmData.of(ByteArray(40000) { (it % 200 - 100).toByte() }), 1000)
        val one = soundWith(listOf(long), 1 to kit(36 to YmPcmMapping(0)))
        assertNull(YmPcmLowering.problem(one))
        assertTrue(YmPcmLowering.compile(one).pages > 2000)
        val two = soundWith(listOf(long), 1 to kit(36 to YmPcmMapping(0), 35 to YmPcmMapping(0, -1)))
        val problem = YmPcmLowering.problem(two)!!
        assertTrue(problem, problem.contains("1024 KB") && problem.contains("shorter samples"))
        // Four octaves down would be 33 MB: refused before any of it is made.
        val huge = soundWith(listOf(long), 1 to kit(36 to YmPcmMapping(0, -48)))
        assertTrue(YmPcmLowering.problem(huge)!!.contains("1024 KB"))
    }

    // ---- malformed samples, kits and offsets -------------------------------------------------------------------------

    @Test
    fun malformedPcmDataAndOffsetsAreStructuralErrors() {
        val good = YmPcmSample("s", ramp, 8000)
        fun errors(sound: YmSound) = YmSystem.structuralErrors(YmSystem.blank().copy(sound = sound)).joinToString("\n")
        assertTrue(errors(soundWith(listOf(good), 1 to kit(36 to YmPcmMapping(5)))).contains("maps missing PCM sample 6"))
        assertTrue(errors(soundWith(listOf(good), 1 to kit(36 to YmPcmMapping(0, 25)))).contains("PCM pitch 25 is outside -48..+24"))
        assertTrue(errors(soundWith(listOf(good), 1 to kit(96 to YmPcmMapping(0)))).contains("PCM key 96 is outside"))
        assertTrue(errors(soundWith(listOf(good), 1 to kit(36 to YmPcmMapping(0), level = 16))).contains("PCM level 16"))
        assertTrue(errors(soundWith(listOf(good.copy(rate = 999)))).contains("rate 999 Hz is outside 1000-48000"))
        assertTrue(errors(soundWith(listOf(good.copy(data = PcmData.of(ByteArray(0)))))).contains("the sample is empty"))
        assertTrue(errors(soundWith(List(65) { good }))).contains("at most 64"))
        val big = YmPcmSample("big", PcmData.of(ByteArray(140000)), 8000)
        assertTrue(errors(soundWith(listOf(big, big))).contains("they share 256.0 KB"))
        val far = (YmPresets.instrument("Lead") as YmFmInstrument).copy(ch3 = listOf(0, 0, 3073, 0))
        assertTrue(errors(YmSystem.blank().ym.withInstrument(1, far)).contains("outside ±3072"))
        val three = (YmPresets.instrument("Lead") as YmFmInstrument).copy(ch3 = listOf(0, 0, 0))
        assertTrue(errors(YmSystem.blank().ym.withInstrument(1, three)).contains("FM 3 operator offsets"))
        // Such a song cannot be saved.
        assertThrows(ProjectFormatException::class.java) {
            ProjectCodec.encode(YmSystem.blank().copy(sound = soundWith(listOf(good), 1 to kit(36 to YmPcmMapping(5)))))
        }
    }

    // ---- PCM lanes: kinds, keys and effects --------------------------------------------------------------------------

    @Test
    fun pcmLanesTakeMappedKeysOfKitsAndOnlyTheirEffects() {
        val song = kitSong()
        fun errors(s: Song) = YmSystem.errors(s).joinToString("\n")
        val pcm = YmRules.PCM_FIRST
        assertEquals("", errors(with(song, pcm, 0, Cell(YmPcmKit.KICK_KEY, 50))))
        assertTrue(errors(with(song, pcm, 0, Cell(37, 50))).contains("C#3 plays no PCM sample in kit 32"))
        // A note without an instrument plays the lane's kit: its key must be mapped too.
        val implicit = with(with(song, pcm, 0, Cell(YmPcmKit.KICK_KEY, 50)), pcm, 1, Cell(37))
        assertTrue(errors(implicit).contains("PCM 1 row 1: C#3 plays no PCM sample"))
        assertEquals("", errors(with(with(song, pcm, 0, Cell(YmPcmKit.KICK_KEY, 50)), pcm, 1, Cell(YmPcmKit.SNARE_KEY))))
        // Kinds: kits on PCM 1-3 only, FM voices and PSG sounds never there.
        assertTrue(errors(with(song, 0, 0, Cell(48, 50))).contains("is a PCM kit; FM 1-6 play FM instruments"))
        assertTrue(errors(with(song, YmRules.PSG_FIRST, 0, Cell(60, 50))).contains("is a PCM kit; PSG 1 plays PSG instruments"))
        assertTrue(errors(with(song, pcm, 0, Cell(57, slot("Lead")))).contains("is an FM voice; PCM 1 plays PCM kits"))
        assertTrue(errors(with(song, pcm + 2, 0, Cell(57, slot("PSG lead")))).contains("is a PSG sound; PCM 3 plays PCM kits"))
        // Effects: the flow, LFO, delay and stop play; volume, pitch and pan do not exist for a running sample.
        for (code in listOf(YmRules.FX_JUMP, YmRules.FX_BREAK, YmRules.FX_SPEED, YmRules.FX_DELAY, YmRules.FX_LFO, YmRules.FX_CUT)) {
            val param = if (code == YmRules.FX_LFO) 0x08 else if (code == YmRules.FX_JUMP || code == YmRules.FX_BREAK) 0 else 2
            assertEquals("${fxChar(code)}", "", errors(with(song, pcm, 0, Cell(YmPcmKit.KICK_KEY, 50, code, param))))
        }
        for (code in listOf(YmRules.FX_VOLUME, YmRules.FX_VOLUME_SLIDE, YmRules.FX_SLIDE_UP, YmRules.FX_ARPEGGIO, YmRules.FX_PAN,
            YmRules.FX_FINE, YmRules.FX_VIBRATO, YmRules.FX_PORTAMENTO, YmRules.FX_BRIGHTNESS)) {
            val e = errors(with(song, pcm, 0, Cell(YmPcmKit.KICK_KEY, 50, code, 1)))
            assertTrue(e, e.contains("does not play on PCM lanes"))
        }
        // Auditions on a PCM lane: a mapped key plays, an unmapped one says how to fix it.
        val engine = YmSystem.engine
        assertNull(engine.auditionProblem(song, pcm, YmPcmKit.SNARE_KEY, 50, 0))
        assertTrue(engine.auditionProblem(song, pcm, 90, 50, 0)!!.contains("map it on the kit's PCM page"))
        assertTrue(engine.auditionProblem(song, 0, 48, 50, 0)!!.contains("is a PCM kit"))
    }

    private fun fxChar(code: Int) = org.thortracker.system.EffectSpec.charOf(code)

    @Test
    fun warningsNameTheDacsHoldOnFmSixAndFm3sMode() {
        val song = with(kitSong(), YmRules.PCM_FIRST, 0, Cell(YmPcmKit.KICK_KEY, 50))
        assertTrue(YmSystem.warnings(song).none { it.contains("FM 6") })
        val fm6 = with(with(song, YmRules.FM6, 4, Cell(50, slot("Snare"))), YmRules.FM6, 8, Cell(effect = YmRules.FX_PAN, param = 0x10))
        val warnings = YmSystem.warnings(fm6).joinToString("\n")
        assertTrue(warnings, warnings.contains("FM 6 is inaudible while the DAC is on"))
        assertTrue(warnings, warnings.contains("pans every PCM channel together"))
        // FM 6 alone (no PCM) is plain FM.
        assertTrue(YmSystem.warnings(with(YmSystem.blank(), YmRules.FM6, 0, Cell(50, slot("Snare")))).none { it.contains("DAC") })
        // FM 3 operator offsets sound only in special mode.
        val lead = (YmPresets.instrument("Lead") as YmFmInstrument).copy(ch3 = listOf(0, 256, 448, 768))
        val normal = with(withSound(YmSystem.blank()) { it.withInstrument(40, lead) }, YmRules.FM3, 0, Cell(57, 40))
        assertTrue(YmSystem.warnings(normal).joinToString().contains("sound only in FM 3's special mode"))
        val special = withSound(normal) { it.copy(fm3Mode = YmFm3Mode.SPECIAL) }
        assertTrue(YmSystem.warnings(special).none { it.contains("special mode") })
        // Other FM channels ignore the offsets: no warning there.
        assertTrue(YmSystem.warnings(with(withSound(YmSystem.blank()) { it.withInstrument(40, lead) }, 0, 0, Cell(57, 40))).none { it.contains("FM 3") })
        // The image carries the mode and the offsets.
        val image = YmLowering.lower(special)
        assertEquals(YmPlayerImage.FM3_SPECIAL, u8(image, YmPlayerImage.H_FM3))
        assertEquals(256, u16(image, YmPlayerImage.OFF_CH3 + 8 * 39 + 2))
        assertEquals(YmPlayerImage.FM3_NORMAL, u8(YmLowering.lower(normal), YmPlayerImage.H_FM3))
    }

    // ---- WAV import --------------------------------------------------------------------------------------------------

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

    @Test
    fun wavImportMakesSignedBytesAndSaysWhatItConverted() {
        val values = shortArrayOf(0, 32767, -32768, 255, 256, -1, -256, -257)
        val mono = YmPcm.fromWav(wav16(values, 22050), "tone", YmPcm.MEMORY)
        assertEquals(22050, mono.sample.rate)
        assertFalse(mono.resampled || mono.downmixed)
        // v >> 8, arithmetic: 0 stays silence, the full range is used, nothing is raised.
        assertEquals(listOf(0, 127, -128, 0, 1, -1, -1, -2), (0 until mono.sample.data.size).map { mono.sample.data.signed(it) })
        val stereo = YmPcm.fromWav(wav16(shortArrayOf(1000, 3000, -1000, -3000), 8000, channels = 2), "st", YmPcm.MEMORY)
        assertTrue(stereo.downmixed)
        assertEquals(listOf(2000 shr 8, -2000 shr 8), (0 until 2).map { stereo.sample.data.signed(it) })
        val tone = ShortArray(9600) { if ((it / 48) % 2 == 0) 8000 else -8000 }
        val hi = YmPcm.fromWav(wav16(tone, 96000), "hi", YmPcm.MEMORY)
        assertTrue(hi.resampled)
        assertEquals(96000, hi.sourceRate)
        assertEquals(YmPcm.MAX_RATE, hi.sample.rate)
        assertEquals(4800, hi.sample.data.size)
        // Too long for the memory left: refused, not cut.
        val long = wav16(ShortArray(5000), 22050)
        assertTrue(assertThrows(IllegalArgumentException::class.java) { YmPcm.fromWav(long, "long", 4999) }.message!!.contains("free"))
        assertEquals(5000, YmPcm.fromWav(long, "long", 5000).sample.data.size)
        assertThrows(IllegalArgumentException::class.java) { YmPcm.fromWav(wav16(ShortArray(10), 999), "slow", YmPcm.MEMORY) }
    }

    // ---- the editor's PCM and Ch3 pages ------------------------------------------------------------------------------

    private val session = EditorSession(YmSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(setup: ViewState.() -> Unit) = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT).apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.goTo(row: Row) { state.instField = YmInstrumentView.rows(session.song, state).indexOf(row) }

    private fun pcmKit(slot: Int) = session.song.ymInstrument(slot) as YmPcmInstrument

    @Test
    fun pcmPageMakesAKitMapsPitchesSpreadsAndDeletes() {
        val v = view { instrument = 50; listenChannel = YmRules.PCM_FIRST }
        assertEquals(YmInstrumentView.PCM_PAGES, YmInstrumentView.pages(session.song, v.state))
        v.tap(Action.EDIT) // on Slot: a new kit
        assertEquals("Kit 32", pcmKit(50).name)
        // An import (the file picker is the host's) maps the shown key; the same file again is reused on the next key.
        val kick = YmPcmKit.samples()[0]
        assertEquals(36, session.addAndMapYmPcmSample(50, kick, 36, "Import"))
        assertEquals(37, session.addAndMapYmPcmSample(50, kick, 36, "Import"))
        assertEquals(1, session.song.ym.pcmSamples.size)
        assertEquals("PCM kick", pcmKit(50).name)
        // Level, pitch (an octave with ↑), spread.
        v.goTo(Row.PCM_LEVEL)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(14, pcmKit(50).level)
        v.state.dpcmKey = 37
        v.goTo(Row.PCM_PITCH)
        v.hold(Action.EDIT, Action.RIGHT, Action.UP)
        assertEquals(13, pcmKit(50).pcm.getValue(37).pitch)
        assertTrue(auditions.isNotEmpty() && auditions.all { it.first == YmRules.PCM_FIRST && it.third == 50 })
        assertEquals(37, auditions.last().second)
        v.state.dpcmKey = 36
        v.goTo(Row.PCM_SPREAD)
        v.tap(Action.EDIT)
        val keys = pcmKit(50).pcm
        assertEquals((24..48).toSet(), keys.keys)
        assertEquals(-12, keys.getValue(24).pitch)
        assertEquals(13, keys.getValue(37).pitch) // kept
        assertTrue(YmSystem.errors(session.song).isEmpty())
        // Listen plays the shown key; an unmapped key says so instead.
        auditions.clear()
        v.tap(Action.AUDITION)
        assertEquals(Triple(YmRules.PCM_FIRST, 36, 50), auditions.last())
        // Deleting the sample unmaps every key that played it.
        v.goTo(Row.PCM_SAMPLES)
        v.state.dpcmSample = 0
        v.tap(Action.CLEAR)
        assertTrue(session.song.ym.pcmSamples.isEmpty())
        assertTrue(pcmKit(50).pcm.isEmpty())
        // Undo brings sample and keys back.
        session.undo()
        assertEquals(25, pcmKit(50).pcm.size)
    }

    @Test
    fun ch3PageSetsTheModeAndOperatorOffsetsAndListensOnFm3() {
        val slot = slot("Lead")
        val v = view { instrument = slot; listenChannel = 0 }
        v.state.envelope = YmInstrumentView.FM_PAGES.indexOf(Page.CH3)
        v.goTo(Row.FM3_MODE)
        v.tap(Action.EDIT)
        assertEquals(YmFm3Mode.SPECIAL, session.song.ym.fm3Mode)
        assertEquals(YmRules.FM3, auditions.last().first)
        v.goTo(Row.CH3_SEMI)
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(1, v.state.envStep)
        v.hold(Action.EDIT, Action.RIGHT, Action.UP)
        assertEquals(13 * 64, (session.song.ymInstrument(slot) as YmFmInstrument).ch3[1])
        v.goTo(Row.CH3_FINE)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(13 * 64 - 1, (session.song.ymInstrument(slot) as YmFmInstrument).ch3[1])
        // A preset keeps the offsets; clearing a cell zeroes that operator's.
        v.state.envelope = YmInstrumentView.FM_PAGES.indexOf(Page.SOUND)
        v.goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(13 * 64 - 1, (session.song.ymInstrument(slot) as YmFmInstrument).ch3[1])
        v.state.envelope = YmInstrumentView.FM_PAGES.indexOf(Page.CH3)
        v.goTo(Row.CH3_SEMI)
        v.state.envStep = 1
        v.tap(Action.CLEAR)
        assertEquals(listOf(0, 0, 0, 0), (session.song.ymInstrument(slot) as YmFmInstrument).ch3)
        // The Song view's row is the same setting.
        val row = YmSongSettings.ALL.single { it.label == "FM 3 mode" }
        assertEquals(YmFm3Mode.NORMAL, row.adjust(session.song, 1, 0).ym.fm3Mode)
        assertEquals(YmFm3Mode.NORMAL, row.reset(session.song).ym.fm3Mode)
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    @Test
    fun nightFerryIsCleanAndUsesPcmAndFm3() {
        val ferry = YmDemoSong.xgm2Demo()
        assertEquals(emptyList<String>(), YmSystem.errors(ferry))
        assertEquals(emptyList<String>(), YmSystem.warnings(ferry))
        assertEquals(ferry, ProjectCodec.decode(ProjectCodec.encode(ferry)))
        assertEquals(YmFm3Mode.SPECIAL, ferry.ym.fm3Mode)
        val cells = { ch: Int -> ferry.orders.toSet().flatMap { ferry.patterns[it].tracks[ch] } }
        for (ch in YmRules.PCM_FIRST until YmRules.CHANNELS) assertTrue("PCM lane $ch", cells(ch).any { it.note >= 0 })
        assertTrue(cells(YmRules.FM6).none { it.note >= 0 })
        assertTrue(cells(YmRules.FM3).any { it.instrument != 0 && (ferry.ym.instrument(it.instrument) as YmFmInstrument).hasCh3 })
        val pcmEffects = (YmRules.PCM_FIRST until YmRules.CHANNELS).flatMap(cells).flatMap { c -> (0..c.more.size).map { c.effectCode(it) } }.toSet()
        assertTrue(pcmEffects.containsAll(listOf(YmRules.FX_DELAY, YmRules.FX_CUT, YmRules.FX_SPEED)))
        assertTrue((YmRules.PCM_FIRST until YmRules.CHANNELS).flatMap(cells).any { it.note == Cell.CUT })
        // Three kits: drums, the quieter drums and the bass's 25 keys compile to 31 sounds.
        val bank = YmPcmLowering.compile(ferry.ym)
        assertEquals(31, bank.variants.size)
        assertTrue(bank.bytes < YmPlayerImage.PCM_BANK_SIZE / 2)
        val image = YmLowering.lower(ferry)
        assertEquals(31, u8(image, YmPlayerImage.H_PCM_VARIANTS))
        // The kit's samples are original, synthesized here: no silence-only sample, all within the source memory.
        assertTrue(YmPcmKit.samples().all { s -> (0 until s.data.size).any { s.data.signed(it) != 0 } })
        assertTrue(YmPcm.stored(ferry.ym) < YmPcm.MEMORY)
    }

    @Test
    fun shortestSongsAreOneTickAndValid() {
        for (song in listOf(YmDemoSong.shortest(), YmDemoSong.pcmShortest(), YmDemoSong.fm3Shortest())) {
            assertEquals(song.title, emptyList<String>(), YmSystem.errors(song))
            assertEquals(1, song.ticksPerRow)
            assertEquals(listOf(0), song.orders)
            assertEquals(1, song.patterns.single().length)
            val image = YmLowering.lower(song)
            assertEquals(YmPlayerImage.IMAGE_SIZE, image.size)
        }
        assertEquals(25, u8(YmLowering.lower(YmDemoSong.pcmShortest()), YmPlayerImage.H_PCM_VARIANTS))
        assertEquals(YmPlayerImage.FM3_SPECIAL, u8(YmLowering.lower(YmDemoSong.fm3Shortest()), YmPlayerImage.H_FM3))
        assertEquals(Cell(YmPcmKit.BASS_ROOT, 1), YmDemoSong.pcmShortest().patterns.single().tracks[YmRules.PCM_FIRST][0])
    }
}
