package org.thortracker.system.opn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.OpnLowering
import org.thortracker.backend.OpnPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.ProjectFormatException
import org.thortracker.model.Song
import org.thortracker.system.ChannelKind
import org.thortracker.system.SoundSystems
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.ym2612.YmOperator
import org.thortracker.system.ym2612.YmSweep
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The PC-88/98 OPN (YM2203) and OPNA (YM2608) systems: their shape, project files, presets, validation (the native
 * loader's checks as messages), audition, the demos and the VGM seam. Tests never install the rhythm ROM.
 */
class OpnModelTest {
    @After
    fun noRenderer() {
        OpnVgm.renderer = null
    }

    private fun with(song: Song, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[0]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun slot(song: Song, part: OpnPart, name: String? = null): Int =
        song.opn.instruments.indexOfFirst { it != null && it.part == part && (name == null || it.name == name) } + 1

    private fun withSound(song: Song, slot: Int, ins: OpnInstrument?) = song.copy(sound = song.opn.withInstrument(slot, ins))

    private fun fx(system: OpnFamilySystem, channel: Int) = system.effectsFor(channel).map { it.char }.joinToString("")

    // ---- system shape --------------------------------------------------------------------------------------

    @Test
    fun systemsAreRegisteredWithTheirChannelsAndBanks() {
        assertEquals(OpnSystem, SoundSystems.of("ym2203-opn"))
        assertEquals(OpnaSystem, SoundSystems.of("ym2608-opna"))
        assertEquals("PC-88/98 OPN (YM2203)", OpnSystem.name)
        assertEquals("PC-88/98 OPNA (YM2608)", OpnaSystem.name)
        assertEquals("PC-88/98", OpnSystem.family)
        assertEquals("PC-88/98", OpnaSystem.family)
        assertEquals(OpnSystem, OpnChip.YM2203.system())
        assertEquals(OpnaSystem, OpnChip.YM2608.system())

        assertEquals(listOf("FM 1", "FM 2", "FM 3", "SSG 1", "SSG 2", "SSG 3"), OpnSystem.channels.map { it.name })
        assertEquals(
            listOf("FM 1", "FM 2", "FM 3", "FM 4", "FM 5", "FM 6", "SSG 1", "SSG 2", "SSG 3", "Kick", "Snare", "Cymbal", "Hi-hat", "Tom", "Rim", "ADPCM"),
            OpnaSystem.channels.map { it.name },
        )
        // Kinds, tonality and banks by part: FM voices 0, SSG sounds 1, rhythm 2, ADPCM 3.
        assertEquals(listOf(0, 0, 0, 1, 1, 1), OpnSystem.channels.map { it.bank })
        assertEquals(List(6) { 0 } + List(3) { 1 } + List(6) { 2 } + 3, OpnaSystem.channels.map { it.bank })
        assertEquals(listOf("FM voices", "SSG sounds"), OpnSystem.banks.map { it.name })
        assertEquals(listOf("FM voices", "SSG sounds", "Rhythm sounds", "ADPCM sounds"), OpnaSystem.banks.map { it.name })
        assertTrue(OpnaSystem.banks.all { it.slots == 64 })
        assertEquals(ChannelKind.VOICE, OpnaSystem.channels[0].kind)
        assertEquals(ChannelKind.PULSE, OpnaSystem.channels[6].kind)
        assertEquals(ChannelKind.SAMPLE, OpnaSystem.channels[9].kind)
        assertFalse(OpnaSystem.channels[9].tonal)
        assertEquals(ChannelKind.SAMPLE, OpnaSystem.channels[15].kind)
        assertTrue(OpnaSystem.channels[15].tonal)
        assertTrue(OpnSystem.channels.all { it.tonal })

        assertEquals("A-4", OpnSystem.noteName(57))
        assertEquals(0 until 96, OpnaSystem.noteRange)
        assertEquals(4, OpnaSystem.effectColumns(15))
        assertEquals(listOf("vgm"), OpnaSystem.exports.map { it.extension })
        assertEquals(256, OpnSystem.maxOrders)
        assertNull(OpnSystem.offEffect)
        assertTrue(OpnaSystem.rhythmRom)
        assertFalse(OpnSystem.rhythmRom)
        assertEquals(OpnSound.serializer(), OpnaSystem.soundSerializer)
        assertEquals(OpnaSystem, OpnaSystem.blank().sound.system)
        assertEquals(OpnSystem, OpnSystem.blank().sound.system)
    }

    /** Which effects load where: by part, and the YM2608's pans, LFO and rhythm level only on the YM2608. */
    @Test
    fun effectsDependOnThePartAndTheChip() {
        assertEquals("012348ABCDEFGILMPRST", fx(OpnaSystem, 0))
        assertEquals("01234ABCDEFGIMPST", fx(OpnSystem, 0))
        assertEquals("01234ABCDFGHJLNPRST", fx(OpnaSystem, 6))
        assertEquals("01234ABCDFGHJNPST", fx(OpnSystem, 3))
        assertEquals("8ABCDFGLRST", fx(OpnaSystem, 9))
        assertEquals("012348ABCDFGLPRST", fx(OpnaSystem, 15))
        assertEquals(listOf(0x7F, 0x0F, 0x1F, 0xFF), OpnPart.entries.map { OpnRules.volumeMax(it) })
        assertEquals(11, OpnRules.lowest(OpnChip.YM2608, 6))
        assertEquals(0, OpnRules.lowest(OpnChip.YM2608, 15))
        assertTrue(OpnSystem.effects.none { it.code in OpnRules.OPNA_ONLY })
    }

    /** Timer B sets the tick rate; the chip formulas the player and the docs use. */
    @Test
    fun timingAndPitchFormulas() {
        assertEquals(59.77, OpnSystem.tickHz, 0.005)
        val song = OpnaSystem.blank()
        assertEquals(59.77, OpnaSystem.ticksPerSecond(song), 0.005)
        assertEquals(47.49, OpnaSystem.ticksPerSecond(song.copy(sound = song.opn.copy(timerB = 183))), 0.005)
        assertEquals(577.78, OpnaSystem.ticksPerSecond(song.copy(sound = song.opn.copy(timerB = 250))), 0.005)
        // SSG: tone = 1996800 / (16 TP); A-4 = 440 Hz is TP 284, B-0 the lowest within 12 bits.
        assertEquals(284, OpnRules.ssgPeriod(57))
        assertTrue(OpnRules.ssgPeriod(11) <= 4095)
        assertEquals(4095, OpnRules.ssgPeriod(10))
        // ADPCM-B: delta-N = rate × 65536 / 55466.67 at the root, doubled an octave up.
        assertEquals(18905, OpnRules.deltaN(16000, 48, 48))
        assertEquals(37809, OpnRules.deltaN(16000, 48, 60))
        assertEquals(65535, OpnRules.deltaN(55466, 48, 60))
        // Timer A: (1024 - NA) native samples.
        assertEquals(55466.67 / 1024, OpnRules.timerAHz(0), 0.01)
        assertEquals(55466.67, OpnRules.timerAHz(1023), 0.01)
    }

    // ---- project files ----------------------------------------------------------------------------------------

    /** Every sound kind, the samples (as Base64) and every song setting survive a save and load. */
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun projectFilesRoundTripEverySoundKindAndTheSamples() {
        var song = OpnaSystem.blank()
        val free = song.opn.instruments.indexOfFirst { it == null } + 1
        song = withSound(song, free, OpnFmSound("Stack", algorithm = 7, feedback = 3, ams = 2, fms = 5,
            operators = List(4) { YmOperator(mul = it + 1, tl = 20 + it, am = it == 3, ssgEg = if (it == 0) 9 else 0) },
            ch3 = listOf(0, 256, -448, 3072), sweep = YmSweep(5, -7, 30)))
        song = withSound(song, free + 1, OpnSsgSound("Bells", volume = NesEnvelope(listOf(15, 10, 5), 1, 2),
            arpeggio = NesEnvelope(listOf(0, 12), 0), pitch = NesEnvelope(listOf(-3, 3)), pitchRelative = true,
            mixer = NesEnvelope(listOf(0, 1, 2, 3)), noise = NesEnvelope(listOf(31, 0)),
            envelope = S5bEnvelope(S5bShape.TRIANGLE_UP, autoPitch = false, octave = -2, period = 54321), sweep = YmSweep(-12, 0, 8)))
        song = withSound(song, free + 2, OpnRhythmSound("Left rim", level = 17, left = true, right = false))
        song = withSound(song, free + 3, OpnAdpcmSound("Loop", sample = 2, root = 60, level = 99, left = false, right = true, loop = true, sweep = YmSweep(0, 12, 4)))
        song = song.copy(sound = song.opn.copy(timerB = 230, fm3Mode = OpnFm3Mode.CSM, timerA = 777, lfo = 0x0C, rhythmLevel = 40))
        song = with(song, 9, 0, Cell(36, slot(song, OpnPart.RHYTHM)))
        song = with(song, 15, 4, Cell(48, free + 3, OpnRules.FX_PAN, 0x10))

        val text = ProjectCodec.encode(song)
        val back = ProjectCodec.decode(text)
        assertEquals(song, back)
        assertEquals(text, ProjectCodec.encode(back))

        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("ym2608-opna", root["backend"]!!.jsonPrimitive.content)
        assertEquals(1, root["backendVersion"]!!.jsonPrimitive.content.toInt())
        val sound = root["song"]!!.jsonObject["sound"]!!.jsonObject
        assertEquals("ym2608", sound["chip"]!!.jsonPrimitive.content)
        assertEquals("csm", sound["fm3Mode"]!!.jsonPrimitive.content)
        val types = sound["instruments"]!!.jsonArray.mapNotNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content }.toSet()
        assertEquals(setOf("fm", "ssg", "rhythm", "adpcm"), types)
        // Each sample's ADPCM-B bytes are one Base64 string.
        val samples = sound["samples"]!!.jsonArray
        assertEquals(song.opn.samples.size, samples.size)
        samples.forEachIndexed { k, s ->
            val data = s.jsonObject["data"]!!.jsonPrimitive
            assertTrue(data.isString)
            assertArrayEquals(song.opn.samples[k].data.toByteArray(), Base64.decode(data.content))
        }
        // The YM2203's blank and demo round-trip too.
        for (s in listOf(OpnSystem.blank(), OpnSystem.demo(), OpnaSystem.demo())) assertEquals(s, ProjectCodec.decode(ProjectCodec.encode(s)))
    }

    /** A project naming one chip's system but holding the other chip's data is refused, as is damaged sample data. */
    @Test
    fun chipMismatchAndBadSampleDataAreRefused() {
        val opnaData = OpnSystem.blank().copy(sound = OpnaSystem.blank().opn)
        val problems = OpnSystem.structuralErrors(opnaData)
        assertEquals(1, problems.size)
        assertTrue(problems.single(), problems.single().contains("YM2608 (OPNA)") && problems.single().contains("YM2203 (OPN)"))
        assertTrue(OpnaSystem.structuralErrors(OpnaSystem.blank().copy(sound = OpnSystem.blank().opn)).isNotEmpty())

        val text = ProjectCodec.encode(OpnaSystem.blank()).replace("\"ym2608-opna\"", "\"ym2203-opn\"")
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(text) }
        assertTrue(e.message, e.message!!.contains("YM2608 (OPNA)"))

        val root = Json.parseToJsonElement(ProjectCodec.encode(OpnaSystem.blank())).jsonObject
        val song = root["song"]!!.jsonObject
        val sound = song["sound"]!!.jsonObject
        val sample = sound["samples"]!!.jsonArray[0].jsonObject
        val damaged = JsonObject(root + ("song" to JsonObject(song + ("sound" to JsonObject(sound + ("samples" to
            JsonArray(listOf(JsonObject(sample + ("data" to JsonPrimitive("not base64!")))))))))))
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(damaged.toString()) }
    }

    // ---- presets ----------------------------------------------------------------------------------------------

    @Test
    fun presetsAreValidForTheirChips() {
        for (chip in OpnChip.entries) {
            val song = chip.system().blank()
            val sound = song.opn
            assertEquals(OpnSound.SLOTS, sound.instruments.size)
            sound.instruments.filterNotNull().forEach { assertNull(it.name, OpnValidator.instrumentProblem(chip, it)) }
            assertEquals(emptyList<String>(), chip.system().errors(song))
            assertEquals(chip.parts.toSet(), sound.instruments.filterNotNull().map { it.part }.toSet())
            for (part in chip.parts) {
                val bank = part.ordinal
                val first = chip.system().defaultInstrument(song, bank)
                assertEquals(part, sound.instrument(first)!!.part)
                assertNull(OpnValidator.instrumentProblem(chip, OpnPresets.default(chip, part).let {
                    if (it is OpnAdpcmSound) it.copy(sample = 0) else it
                }))
            }
        }
        // The YM2203's FM voices carry no LFO depths; the YM2608 keeps the Mega Drive presets' ones.
        val opn = OpnSystem.blank().opn.instruments.filterIsInstance<OpnFmSound>()
        assertTrue(opn.all { v -> v.ams == 0 && v.fms == 0 && v.operators.none { it.am } })
        val opna = OpnaSystem.blank().opn.instruments.filterIsInstance<OpnFmSound>()
        assertTrue(opna.any { it.ams != 0 || it.fms != 0 })
        assertEquals(OpnPresets.FM.size, opna.size)
        // The YM2608 starts with one rhythm sound per drum and one ADPCM sound per generated starter sample.
        val sound = OpnaSystem.blank().opn
        assertEquals(OpnChip.RHYTHM_NAMES, sound.instruments.filterIsInstance<OpnRhythmSound>().map { it.name })
        val adpcm = sound.instruments.filterIsInstance<OpnAdpcmSound>()
        assertEquals(OpnSampleKit.KINDS, sound.samples.map { it.name })
        assertEquals(sound.samples.indices.toList(), adpcm.map { it.sample })
        assertTrue(OpnSystem.blank().opn.samples.isEmpty())
        assertTrue(OpnSystem.blank().opn.instruments.none { it is OpnRhythmSound || it is OpnAdpcmSound })
    }

    // ---- validation -------------------------------------------------------------------------------------------

    /** The loader's checks, as messages: notes, kinds, effects per part and chip, parameter ranges. */
    @Test
    fun cellChecksMirrorTheLoader() {
        val opna = OpnaSystem.blank()
        val fm = slot(opna, OpnPart.FM)
        val ssg = slot(opna, OpnPart.SSG)
        val rhythm = slot(opna, OpnPart.RHYTHM)
        val adpcm = slot(opna, OpnPart.ADPCM)
        fun problem(channel: Int, cell: Cell, song: Song = opna) = song.system.cellProblem(song, channel, cell)

        assertNull(problem(0, Cell(57, fm)))
        assertNull(problem(6, Cell(11, ssg)))
        assertTrue(problem(6, Cell(10, ssg))!!.contains("below the SSG's range"))
        assertNull(problem(9, Cell(0, rhythm)))
        assertNull(problem(15, Cell(95, adpcm)))
        assertTrue(problem(0, Cell(57, ssg))!!.contains("is an SSG sound"))
        assertTrue(problem(9, Cell(57, fm))!!.contains("plays rhythm sounds"))
        assertTrue(problem(15, Cell(57, rhythm))!!.contains("plays ADPCM sounds"))
        assertTrue(problem(0, Cell(57, 60))!!.contains("is empty"))
        assertTrue(problem(0, Cell(96, fm))!!.contains("outside"))

        fun fx(channel: Int, code: Int, param: Int, song: Song = opna) = problem(channel, Cell(effect = code, param = param), song)
        assertNull(fx(0, OpnRules.FX_VOLUME, 0x7F))
        assertTrue(fx(0, OpnRules.FX_VOLUME, 0x80)!!.contains("C00-C7F"))
        assertTrue(fx(6, OpnRules.FX_VOLUME, 0x10)!!.contains("C00-C0F"))
        assertTrue(fx(9, OpnRules.FX_VOLUME, 0x20)!!.contains("C00-C1F"))
        assertNull(fx(15, OpnRules.FX_VOLUME, 0xFF))
        assertTrue(fx(6, OpnRules.FX_FEEDBACK, 1)!!.contains("does not play on SSG"))
        assertTrue(fx(0, OpnRules.FX_NOISE, 1)!!.contains("does not play on FM"))
        assertTrue(fx(9, OpnRules.FX_ARPEGGIO, 0x47)!!.contains("does not play on rhythm"))
        assertTrue(fx(6, OpnRules.FX_ENV_SHAPE, 0x05)!!.contains("H08-H0F"))
        assertNull(fx(6, OpnRules.FX_ENV_SHAPE, 0x0E))
        assertTrue(fx(6, OpnRules.FX_ENV_PERIOD, 0)!!.contains("J01-JFF"))
        assertTrue(fx(6, OpnRules.FX_NOISE, 0x20)!!.contains("N00-N1F"))
        assertTrue(fx(0, OpnRules.FX_RHYTHM_LEVEL, 0x40)!!.contains("R00-R3F"))
        assertTrue(fx(0, OpnRules.FX_LFO, 0x07)!!.contains("L08-L0F"))
        assertTrue(fx(0, OpnRules.FX_SPEED, 0)!!.contains("F01"))
        assertTrue(fx(0, OpnRules.FX_JUMP, 1)!!.contains("past the last order"))
        assertTrue(fx(0, OpnRules.FX_MULT, 0x51)!!.contains("1-4"))
        assertNull(fx(15, OpnRules.FX_TIMER_A, 0xFF))
        assertTrue(fx(0, 24, 1)!!.contains("does not exist"))
        // The YM2203 has no pans, LFO or rhythm level.
        val opn = OpnSystem.blank()
        assertTrue(fx(0, OpnRules.FX_PAN, 0x11, opn)!!.contains("YM2203 has no"))
        assertTrue(fx(3, OpnRules.FX_LFO, 0x08, opn)!!.contains("YM2203 has no"))
        assertTrue(fx(0, OpnRules.FX_RHYTHM_LEVEL, 0x20, opn)!!.contains("YM2203 has no"))
        // Errors report the arranged song's cells with their place.
        val bad = with(opna, 6, 3, Cell(5, ssg))
        assertTrue(OpnaSystem.errors(bad).single(), OpnaSystem.errors(bad).single().startsWith("Pattern 0 \"Pattern 00\", SSG 1 row 3:"))
    }

    /** Sound, sample and song-setting problems: ranges, the YM2203's missing features, ADPCM-B memory. */
    @Test
    fun soundAndSampleChecks() {
        val opna = OpnaSystem.blank()
        val free = opna.opn.instruments.indexOfFirst { it == null } + 1
        fun errors(song: Song) = song.system.errors(song)
        fun one(song: Song, text: String) = errors(song).let { assertTrue(it.toString(), it.any { e -> e.contains(text) }) }

        // Out-of-range values are structural: such a song is neither saved nor loaded.
        assertTrue(OpnaSystem.structuralErrors(withSound(opna, free, OpnFmSound(algorithm = 8))).single().contains("algorithm 8"))
        assertTrue(OpnaSystem.structuralErrors(withSound(opna, free, OpnFmSound(ch3 = listOf(0, 0, 0, 3073)))).single().contains("±3072"))
        assertTrue(OpnaSystem.structuralErrors(withSound(opna, free, OpnRhythmSound(level = 32))).single().contains("0-31"))
        assertTrue(OpnaSystem.structuralErrors(withSound(opna, free, OpnSsgSound(mixer = NesEnvelope(listOf(4))))).single().contains("mixer"))
        assertTrue(OpnaSystem.structuralErrors(withSound(opna, free, OpnSsgSound(noise = NesEnvelope(listOf(32))))).single().contains("noise"))
        assertTrue(OpnaSystem.structuralErrors(withSound(opna, free, OpnSsgSound(envelope = S5bEnvelope(octave = 9)))).single().contains("octave"))
        assertTrue(OpnaSystem.structuralErrors(opna.copy(sound = opna.opn.copy(timerB = 182))).single().contains("183-250"))
        assertTrue(OpnaSystem.structuralErrors(opna.copy(sound = opna.opn.copy(timerA = 1024))).single().contains("0-1023"))
        assertTrue(OpnaSystem.structuralErrors(opna.copy(sound = opna.opn.copy(lfo = 3))).single().contains("08-0F"))
        // What blocks playing: silent pans, missing samples, rates, memory, the sample table.
        one(withSound(opna, free, OpnRhythmSound(left = false, right = false)), "silent")
        one(withSound(opna, free, OpnAdpcmSound(sample = 40)), "plays sample 40")
        one(opna.copy(sound = opna.opn.withSample(0, opna.opn.samples[0].copy(rate = 999))), "1000-55466 Hz")
        one(opna.copy(sound = opna.opn.withSample(0, opna.opn.samples[0].copy(data = AdpcmBytes(ByteArray(0))))), "no data")
        val big = OpnaSample("Big", 16000, AdpcmBytes(ByteArray(200_000)))
        one(opna.copy(sound = opna.opn.copy(samples = opna.opn.samples + big + big)), "ADPCM-B memory holds 256.0 KiB")
        val many = List(65) { OpnaSample("S$it", 8000, AdpcmBytes(ByteArray(32))) }
        one(opna.copy(sound = opna.opn.copy(samples = many)), "at most 64")
        // The YM2203 refuses the YM2608's features.
        val opn = OpnSystem.blank()
        val opnFree = opn.opn.instruments.indexOfFirst { it == null } + 1
        one(withSound(opn, opnFree, OpnRhythmSound()), "no rhythm unit")
        one(withSound(opn, opnFree, OpnAdpcmSound()), "no ADPCM-B channel")
        one(withSound(opn, opnFree, OpnFmSound(fms = 2)), "uses the LFO")
        one(withSound(opn, opnFree, OpnFmSound(operators = List(4) { YmOperator(am = it == 1) })), "uses the LFO")
        one(opn.copy(sound = opn.opn.copy(lfo = 0x08)), "no LFO")
        one(opn.copy(sound = opn.opn.copy(samples = listOf(OpnaSample("S", 8000, AdpcmBytes(ByteArray(32)))))), "no ADPCM-B channel")
        // The YM2608 takes the same voice.
        assertEquals(emptyList<String>(), errors(withSound(opna, free, OpnFmSound(fms = 2))))
    }

    @Test
    fun warningsCoverRhythmWithoutTheRomLocksAndFm3Offsets() {
        assertFalse(Ym2608RhythmRom.installed)
        var song = OpnaSystem.blank()
        song = with(song, 9, 0, Cell(36, slot(song, OpnPart.RHYTHM)))
        assertTrue(OpnaSystem.warnings(song).contains(Ym2608RhythmRom.MISSING))
        assertEquals(emptyList<String>(), OpnaSystem.errors(song))
        song = with(song, 0, 4, Cell(effect = OpnRules.FX_FEEDBACK, param = 3))
        assertTrue(OpnaSystem.warnings(song).any { it.contains("row 4: Exx applies when a note starts") })
        val stack = song.opn.instruments.indexOfFirst { it == null } + 1
        song = withSound(song, stack, OpnFmSound("Stack", ch3 = listOf(0, 256, 448, 768)))
        song = with(song, 2, 8, Cell(48, stack))
        assertTrue(OpnaSystem.warnings(song).any { it.contains("FM 3 operator pitches") })
        val special = song.copy(sound = song.opn.copy(fm3Mode = OpnFm3Mode.SPECIAL))
        assertTrue(OpnaSystem.warnings(special).none { it.contains("FM 3 operator pitches") })
        assertTrue(OpnaSystem.warnings(with(special, 1, 1, Cell(effect = OpnRules.FX_TIMER_A, param = 0x80))).any { it.contains("CSM") })
    }

    // ---- audition, notes and samples ----------------------------------------------------------------------------

    @Test
    fun auditionChecksKindsRangesAndTheRhythmRom() {
        val song = OpnaSystem.blank()
        val e = OpnaSystem.engine
        val fm = slot(song, OpnPart.FM)
        val ssg = slot(song, OpnPart.SSG)
        val rhythm = slot(song, OpnPart.RHYTHM)
        val adpcm = slot(song, OpnPart.ADPCM)
        assertNull(e.auditionProblem(song, 0, 57, fm, 0))
        assertNull(e.auditionProblem(song, 15, 48, adpcm, 0))
        assertTrue(e.auditionProblem(song, 6, 57, fm, 0)!!.contains("is an FM sound"))
        assertTrue(e.auditionProblem(song, 6, 10, ssg, 0)!!.contains("lowest note"))
        assertTrue(e.auditionProblem(song, 0, 57, 60, 0)!!.contains("is empty"))
        assertTrue(e.auditionProblem(song, 0, 57, fm, 200)!!.contains("-128..127"))
        // The drums cannot sound without the user's ROM (tests never install it).
        assertFalse(Ym2608RhythmRom.installed)
        assertEquals(Ym2608RhythmRom.MISSING, e.auditionProblem(song, 9, 57, rhythm, 0))
        assertEquals("ym2608-opna", e.id)
        assertEquals(OpnPlayerImage.OPNA_IMAGE_SIZE, e.imageSize)
        assertEquals(OpnPlayerImage.IMAGE_SIZE, OpnSystem.engine.imageSize)
        assertTrue(e.driver.contains("YM2608") && e.driver.contains("ymfm"))
        assertEquals(57, e.note(song, 0, 57, fm))
        assertEquals(-17, e.fine(0, 57, -17))
        // A rhythm note is its channel's drum; an ADPCM note the sound's sample.
        assertEquals("Snare", OpnaSystem.noteSample(song, 10, 70, rhythm)?.name)
        val sample = OpnaSystem.noteSample(song, 15, 60, adpcm)!!
        assertEquals(song.opn.samples[0].name, sample.name)
        assertTrue(sample.detail, sample.detail.contains("16000 Hz"))
        assertNull(OpnaSystem.noteSample(song, 0, 60, fm))
        assertNull(OpnaSystem.mappedNotes(song, 15, adpcm))
    }

    @Test
    fun effectDetailNamesArpeggioNotesAndIgnoredLocks() {
        var song = OpnaSystem.blank()
        song = with(song, 0, 0, Cell(57, 1, OpnRules.FX_ARPEGGIO, 0x47))
        song = with(song, 0, 3, Cell(effect = OpnRules.FX_BRIGHTNESS, param = 0x40))
        song = with(song, 6, 2, Cell(effect = OpnRules.FX_ENV_SHAPE, param = 0x08))
        assertEquals("A-4 → C#5 → E-5 every tick", OpnaSystem.effectDetail(song, 0, 0, 0, 0))
        assertEquals("A-4 → C#5 → E-5 every tick", OpnaSystem.effectDetail(with(song, 0, 1, Cell(effect = OpnRules.FX_ARPEGGIO, param = 0x47)), 0, 0, 1, 0))
        assertTrue(OpnaSystem.effectDetail(song, 0, 0, 3, 0)!!.contains("ignored"))
        assertTrue(OpnaSystem.effectDetail(song, 0, 6, 2, 0)!!.contains("ignored"))
        assertNull(OpnaSystem.effectDetail(song, 0, 0, 1, 0))
    }

    @Test
    fun fineWritesPxxInAFreeColumn() {
        val fine = OpnaSystem.fine
        assertEquals("Pxx", fine.name)
        val cell = fine.write(Cell(effect = OpnRules.FX_VOLUME, param = 0x40), 57, 10)!!
        assertEquals(OpnRules.FX_FINE, cell.effectCode(1))
        assertEquals(0x8A, cell.effectParam(1))
        assertEquals(10, fine.of(cell))
        assertEquals(Cell(57, effect = OpnRules.FX_VOLUME, param = 0x40), fine.write(cell, 57, 0))
        assertEquals(15.625, fine.cents(57, 10), 1e-9)
    }

    @Test
    fun arrangementRenumbersJumpsInEveryColumn() {
        var song = OpnaSystem.blank()
        song = song.copy(patterns = song.patterns + song.patterns[0].copy(name = "B"), orders = listOf(0, 1, 0), parkedOrders = listOf(1))
        song = with(song, 4, 0, Cell(effect = OpnRules.FX_VOLUME, param = 1).withEffect(2, OpnRules.FX_JUMP, 2))
        val arranged = OpnaSystem.arranged(song)
        assertEquals(listOf(0, 0), arranged.orders)
        assertEquals(1, arranged.patterns[0].tracks[4][0].effectParam(2))
        val loop = OpnaSystem.patternLoop(song, 2)
        assertEquals(0, loop.patterns[0].tracks[4][0].effectParam(2))
    }

    // ---- demos and export -------------------------------------------------------------------------------------

    /** Both demos are clean and show their chip: FM 3's special mode, the SSG's envelope and noise, YM2608 extras. */
    @Test
    fun demosAreCleanAndShowTheirSystems() {
        for (system in listOf(OpnSystem, OpnaSystem)) {
            val demo = system.demo()
            assertEquals(system.name, emptyList<String>(), system.errors(demo))
            assertEquals(system, demo.system)
            assertEquals(OpnFm3Mode.SPECIAL, demo.opn.fm3Mode)
            val played = demo.orders.toSet().map { demo.patterns[it] }
            fun cells(channel: Int) = played.flatMap { p -> p.tracks[channel].take(p.length) }
            fun sounds(channels: IntRange) = channels.flatMap { ch -> cells(ch).filter { it.instrument > 0 }.map { demo.opn.instrument(it.instrument)!! } }
            // FM 3 plays a voice with operator pitches (chords from one channel).
            assertTrue(sounds(2..2).any { it is OpnFmSound && it.ch3.any { o -> o != 0 } })
            val chip = system.chip
            val ssg = sounds(chip.ssgFirst until chip.ssgFirst + 3).filterIsInstance<OpnSsgSound>()
            assertTrue(ssg.any { it.envelope.shape != S5bShape.OFF })
            assertTrue(ssg.any { it.mixer.values.any { m -> m == OpnPlayerImage.MIX_NOISE || m == OpnPlayerImage.MIX_BOTH } })
            assertTrue(OpnLowering.dataSize(demo) < OpnPlayerImage.DATA_CAPACITY)
        }
        val opna = OpnaSystem.demo()
        assertFalse(OpnValidator.playsRhythm(opna))
        assertTrue(opna.opn.lfo != 0)
        val played = opna.orders.toSet().map { opna.patterns[it] }
        assertTrue(played.any { p -> p.tracks.take(6).any { t -> t.any { c -> (0..c.more.size).any { c.effectCode(it) == OpnRules.FX_PAN } } } })
        assertTrue(played.any { p -> p.tracks[15].any { it.note >= 0 } })
        assertTrue(played.flatMap { it.tracks[15] }.filter { it.instrument > 0 }.any { (opna.opn.instrument(it.instrument) as OpnAdpcmSound).loop })
        assertNotNull(opna.opn.samples.firstOrNull())
    }

    /** VGM export renders the lowered image through the host's native renderer, with or without the rhythm ROM. */
    @Test
    fun vgmExportCallsTheNativeRenderer() {
        val demo = OpnaSystem.demo()
        assertThrows(IllegalStateException::class.java) { OpnaSystem.exports[0].export(demo) }
        var seen: Triple<ByteArray, Int, OpnVgmTags>? = null
        OpnVgm.renderer = OpnVgmRenderer { image, maxTicks, tags -> seen = Triple(image, maxTicks, tags); byteArrayOf(1, 2) }
        assertArrayEquals(byteArrayOf(1, 2), OpnaSystem.exports[0].export(demo))
        assertArrayEquals(OpnLowering.lower(demo), seen!!.first)
        assertEquals((OpnRules.tickHz(demo.opn.timerB) * 600).toInt(), seen!!.second)
        assertEquals(OpnVgmTags("Paper Lanterns", "Thor Tracker"), seen!!.third)
        // A song with drums exports without the ROM (VGM players bring their own).
        val drums = with(OpnaSystem.blank(), 9, 0, Cell(36, slot(OpnaSystem.blank(), OpnPart.RHYTHM)))
        assertArrayEquals(byteArrayOf(1, 2), OpnaSystem.exports[0].export(drums))
        assertEquals(OpnChip.YM2608.imageSize, seen!!.first.size)
        OpnSystem.exports[0].export(OpnSystem.demo())
        assertEquals(OpnChip.YM2203.imageSize, seen!!.first.size)
    }
}
