package org.thortracker.system

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.YmGoldenSongs
import org.thortracker.backend.YmLowering
import org.thortracker.backend.YmPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.ym2612.YmCurated
import org.thortracker.system.ym2612.YmExport
import org.thortracker.system.ym2612.YmExportRenderer
import org.thortracker.system.ym2612.YmExportTags
import org.thortracker.system.ym2612.YmFmInstrument
import org.thortracker.system.ym2612.YmPresets
import org.thortracker.system.ym2612.YmPsgInstrument
import org.thortracker.system.ym2612.YmRules
import org.thortracker.system.ym2612.YmSongSettings
import org.thortracker.system.ym2612.YmSound
import org.thortracker.system.ym2612.YmSystem
import org.thortracker.system.ym2612.ym

class YmModelTest {
    @After
    fun noRenderer() {
        YmExport.renderer = null
    }

    private fun with(song: Song, order: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun blankWith(channel: Int, row: Int, cell: Cell) = with(YmSystem.blank(), 0, channel, row, cell)

    private fun slot(name: String) = YmPresets.ALL.indexOfFirst { it.name == name } + 1

    private fun fm(name: String) = YmPresets.instrument(name) as YmFmInstrument

    // ---- system shape --------------------------------------------------------------------------------------

    @Test
    fun systemIsRegisteredWithThirteenChannels() {
        assertEquals(YmSystem, SoundSystems.of("ym2612-fm"))
        assertEquals(listOf("FM 1", "FM 2", "FM 3", "FM 4", "FM 5", "FM 6", "PSG 1", "PSG 2", "PSG 3", "Noise", "PCM 1", "PCM 2", "PCM 3"),
            YmSystem.channels.map { it.name })
        assertFalse(YmSystem.channels[9].tonal)
        assertTrue(YmSystem.channels.take(9).all { it.tonal })
        assertTrue(YmSystem.channels.drop(10).none { it.tonal })
        assertEquals(4, YmSystem.effectColumns(9))
        assertEquals("A-4", YmSystem.noteName(57))
        assertEquals(0 until 96, YmSystem.noteRange)
        // Effects per channel kind: pan and the FM locks on FM only, the noise lock on Noise only, PCM the flow, G and S.
        val fx = { ch: Int -> YmSystem.effectsFor(ch).map { it.char }.joinToString("") }
        assertEquals("012348ABCDEFGILMPS", fx(0))
        assertEquals("01234ABCDFGLPS", fx(6))
        assertEquals("01234ABCDFGLNPS", fx(9))
        for (ch in 10..12) assertEquals("BDFGLS", fx(ch))
        assertEquals(59.92, YmSystem.ticksPerSecond(YmSystem.blank()), 0.005)
        val pal = YmSystem.blank().let { it.copy(sound = it.ym.copy(pal = true)) }
        assertEquals(49.70, YmSystem.ticksPerSecond(pal), 0.005)
        assertEquals(listOf("vgm", "xgm", "xgc"), YmSystem.exports.map { it.extension })
        // The XGM files are XGM2: the Files menu says so.
        assertEquals(listOf(".vgm", ".xgm (XGM2)", ".xgc (XGM2 compiled)"), YmSystem.exports.map { it.label })
        // Three banks over one numbering: FM channels pick from FM voices, PSG and Noise from PSG sounds, PCM from kits.
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2), YmSystem.channels.map { it.bank })
        val song = YmSystem.blank()
        assertEquals(slot("Slap bass"), YmSystem.defaultInstrument(song, 0))
        assertEquals(slot("PSG lead"), YmSystem.defaultInstrument(song, 1))
        assertEquals("PSG lead", YmSystem.instrumentName(song, 1, slot("PSG lead")))
        assertEquals("PSG lead", YmSystem.instrumentName(song, 0, slot("PSG lead")))
        assertEquals("PSG lead", YmSystem.instrumentName(song, YmSystem.PCM_BANK, slot("PSG lead")))
        assertNull(YmSystem.instrumentName(song, 3, 1))
    }

    // ---- project files -------------------------------------------------------------------------------------

    @Test
    fun songsRoundTripThroughProjectFiles() {
        for (song in listOf(YmSystem.blank(), YmSystem.demo(), YmGoldenSongs.spike(), YmGoldenSongs.features())) {
            val text = ProjectCodec.encode(song)
            val back = ProjectCodec.decode(text)
            assertEquals(song, back)
            assertEquals(text, ProjectCodec.encode(back))
        }
    }

    @Test
    fun projectFilesNameTheBackendAndInstrumentKinds() {
        val text = ProjectCodec.encode(YmSystem.blank())
        assertTrue(text.contains("\"backend\":\"ym2612-fm\",\"backendVersion\":2"))
        assertTrue(text.contains("{\"type\":\"fm\",\"name\":\"Slap bass\""))
        assertTrue(text.contains("{\"type\":\"psg\",\"name\":\"PSG lead\""))
        assertTrue(text.contains("\"pal\":false,\"lfo\":0"))
        // Other systems' files are unchanged by the new system.
        for (system in listOf(SoundSystems.of("gb-huge"), SoundSystems.of("opl3-fm"))) {
            val song = system.demo()
            assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
        }
    }

    // ---- presets and curated controls ----------------------------------------------------------------------

    @Test
    fun presetsAreValidAndCoverEveryKind() {
        assertTrue(YmPresets.ALL.size >= 14)
        for (p in YmPresets.ALL) assertNull(p.name, p.instrument.problem())
        assertEquals(YmPresets.ALL.size, YmPresets.ALL.map { it.name }.toSet().size)
        val drums = YmPresets.ALL.filter { it.category == YmPresets.Category.DRUM }
        assertTrue(drums.count { it.instrument.sweep.active } >= 3)
        assertTrue(drums.all { it.instrument is YmFmInstrument && it.note != null })
        assertTrue(YmPresets.ALL.count { it.instrument is YmPsgInstrument } >= 6)
        assertTrue(YmPresets.ALL.any { it.instrument.let { i -> i is YmPsgInstrument && i.noise and 3 == YmPsgInstrument.PITCHED } })
        // All eight algorithms are not required, but the presets use several.
        assertTrue(YmPresets.ALL.mapNotNull { (it.instrument as? YmFmInstrument)?.algorithm }.toSet().size >= 3)
        val sound = YmSystem.blank().ym
        assertEquals(YmPresets.ALL.map { it.instrument }, sound.instruments.take(YmPresets.ALL.size))
        assertTrue(sound.instruments.drop(YmPresets.ALL.size).all { it == null })
        assertEquals(emptyList<String>(), YmSystem.errors(YmSystem.blank()))
        assertEquals(YmPresets.ALL.filter { it.category != YmPresets.Category.NOISE && it.instrument is YmPsgInstrument }, YmPresets.forChannel(6))
        assertTrue(YmPresets.forChannel(0).all { it.instrument is YmFmInstrument })
    }

    @Test
    fun curatedControlsReadBackWhatTheyWrite() {
        for (p in YmPresets.ALL) {
            val ins = p.instrument as? YmFmInstrument ?: continue
            val c = YmCurated.controls(ins)
            for (i in YmCurated.RATIOS.indices) {
                val r = YmCurated.withRatio(ins, i)
                assertEquals(i, YmCurated.controls(r).ratio)
                assertTrue(YmCurated.controls(r).ratioExact)
                // Only MUL and DT changed.
                assertEquals(ins.operators.map { it.copy(mul = 0, dt = 0) }, r.operators.map { it.copy(mul = 0, dt = 0) })
            }
            for (i in YmCurated.SSG_PRESETS.indices) {
                val s = YmCurated.withSsg(ins, i)
                // Presets that differ only on modulators read alike on additive voices: the reading writes the same.
                assertEquals(s.operators, YmCurated.withSsg(ins, YmCurated.controls(s).ssg).operators)
                assertTrue(YmCurated.controls(s).ssgExact)
                assertNull(s.problem())
            }
            for (v in 0..31) {
                assertEquals(v, YmCurated.controls(YmCurated.withAttack(ins, v)).attack)
                assertEquals(v, YmCurated.controls(YmCurated.withDecay(ins, v)).decay)
                assertEquals(v, YmCurated.controls(YmCurated.withSustainRate(ins, v)).sustainRate)
                if (c.toneDecay >= 0) assertEquals(v, YmCurated.controls(YmCurated.withToneDecay(ins, v)).toneDecay)
            }
            for (v in 0..15) {
                assertEquals(v, YmCurated.controls(YmCurated.withRelease(ins, v)).release)
                assertEquals(v, YmCurated.controls(YmCurated.withSustain(ins, v)).sustain)
            }
            for (v in 0..7) {
                assertEquals(v, YmCurated.controls(YmCurated.withFeedback(ins, v)).feedback)
                assertEquals(v, YmCurated.controls(YmCurated.withVibrato(ins, v)).vibrato)
            }
            for (v in 0..3) {
                val t = YmCurated.controls(YmCurated.withTremolo(ins, v))
                assertEquals(v, t.tremolo)
                assertTrue(t.tremoloExact)
            }
            for (k in YmCurated.KEY_SCALES.indices) {
                val ks = YmCurated.controls(YmCurated.withKeyScale(ins, k))
                assertEquals(k, ks.keyScale)
                assertTrue(ks.keyScaleExact)
            }
            assertEquals(127, YmCurated.controls(YmCurated.withLevel(ins, 127)).level)
            if (c.brightness >= 0) assertEquals(20, YmCurated.controls(YmCurated.withBrightness(ins, 20)).brightness)
            for (a in 0..7) {
                val changed = YmCurated.withAlgorithm(ins, a)
                assertEquals(a, YmCurated.controls(changed).algorithm)
                assertNull(changed.problem())
                // Loudness (the lowest carrier level) survives a change of routing.
                assertEquals(c.level, YmCurated.controls(changed).level)
                if (a != 7 && c.brightness >= 0) assertEquals(c.brightness, YmCurated.controls(changed).brightness)
            }
        }
    }

    @Test
    fun algorithmsDescribeTheChipsRouting() {
        assertEquals((0..7).toList(), YmCurated.ALGORITHMS.map { it.number })
        assertEquals(listOf(listOf(4), listOf(4), listOf(4), listOf(4), listOf(2, 4), listOf(2, 3, 4), listOf(2, 3, 4), listOf(1, 2, 3, 4)),
            YmCurated.ALGORITHMS.map { it.carriers })
        for (a in YmCurated.ALGORITHMS) {
            // Every modulator feeds something; carriers modulate nothing.
            val modulators = (1..4) - a.carriers.toSet()
            assertEquals(a.name, modulators.toSet(), a.edges.map { it.first }.toSet())
            assertTrue(a.edges.none { it.first in a.carriers })
        }
        assertEquals(-1, YmCurated.controls(fm("Organ")).brightness)
        assertEquals("½", YmCurated.multText(0))
        assertEquals(listOf("0", "+1", "+2", "+3", "0", "−1", "−2", "−3"), (0..7).map { YmCurated.detuneText(it) })
    }

    @Test
    fun handEditedGridShowsTheNearestEntryAsApproximate() {
        val bass = fm("Slap bass")
        val edited = bass.mapOperators { _, op -> op.copy(dt = 0) }.withOperator(0, bass.operators[0].copy(mul = 13, dt = 0))
        val c = YmCurated.controls(edited)
        assertFalse(c.ratioExact)
        assertEquals(14, YmCurated.RATIOS[c.ratio].mults[0])
        assertTrue(YmCurated.controls(YmCurated.withRatio(edited, c.ratio)).ratioExact)
        // Presets are voiced per operator, so the envelope reads approximate until a macro writes every operator it owns.
        assertFalse(YmCurated.controls(bass).envelopeExact)
        val even = YmCurated.withSustainRate(YmCurated.withSustain(YmCurated.withRelease(YmCurated.withToneDecay(YmCurated.withDecay(bass, 8), 9), 9), 12), 3)
        assertTrue(YmCurated.controls(even).envelopeExact)
        val odd = even.withOperator(1, even.operators[1].copy(ar = 3))
        assertFalse(YmCurated.controls(odd).envelopeExact)
        assertTrue(YmCurated.controls(YmCurated.withAttack(odd, 31)).envelopeExact)
        // SSG-EG by hand on one operator matches no preset.
        val ssg = bass.withOperator(2, bass.operators[2].copy(ssgEg = 9))
        assertFalse(YmCurated.controls(ssg).ssgExact)
    }

    @Test
    fun operatorRegistersRoundTrip() {
        for (p in YmPresets.ALL) {
            val ins = p.instrument as? YmFmInstrument ?: continue
            for (op in ins.operators) {
                assertEquals(op, org.thortracker.system.ym2612.YmOperator.ofRegisters(op.reg30, op.reg40, op.reg50, op.reg60, op.reg70, op.reg80, op.reg90))
            }
        }
    }

    // ---- tuning realisation --------------------------------------------------------------------------------

    @Test
    fun fineWritesPxxInAFreeColumn() {
        val fine = YmSystem.fine
        assertEquals(25.0, fine.cents(57, 16), 1e-9)
        val busy = Cell(57, 1, YmRules.FX_VIBRATO, 0x23)
        val tuned = fine.write(busy, 57, 16)!!
        assertEquals(YmRules.FX_FINE, tuned.effectCode(1))
        assertEquals(0x90, tuned.effectParam(1))
        assertEquals(16, fine.of(tuned))
        assertEquals(busy, fine.write(tuned, 57, 0))
        var full = Cell(57, 1)
        for (c in 0 until 4) full = full.withEffect(c, YmRules.FX_VOLUME, 0x30)
        assertNull(fine.write(full, 57, 8))
        assertNull(fine.of(Cell(57, 1, YmRules.FX_FINE, 0x70)))
        assertEquals(16, YmSystem.engine.fine(0, 57, 16))
        assertEquals(57, YmSystem.engine.note(YmSystem.blank(), 0, 57, 1))
    }

    // ---- validation ----------------------------------------------------------------------------------------

    @Test
    fun validationExplainsWhatTheLoaderRejects() {
        val bass = slot("Slap bass")
        val psg = slot("PSG lead")
        fun error(channel: Int, cell: Cell) = YmSystem.errors(blankWith(channel, 0, cell)).joinToString("\n")
        assertTrue(error(6, Cell(60, bass)).contains("is an FM voice; PSG 1 plays PSG instruments"))
        assertTrue(error(0, Cell(60, psg)).contains("is a PSG sound; FM 1-6 play FM instruments"))
        assertTrue(error(0, Cell(48, 60)).contains("instrument 3C is empty"))
        assertTrue(error(0, Cell(48, bass, YmRules.FX_JUMP, 0x01)).contains("jumps past the last order"))
        assertTrue(error(0, Cell(48, bass).withEffect(2, YmRules.FX_VOLUME, 0x80)).contains("FM volumes are C00-C7F"))
        assertTrue(error(6, Cell(60, psg, YmRules.FX_VOLUME, 0x10)).contains("PSG volumes are C00-C0F"))
        assertTrue(error(0, Cell(48, bass, YmRules.FX_SPEED, 0)).contains("F00"))
        assertTrue(error(0, Cell(48, bass, YmRules.FX_MULT, 0x51)).contains("operator digit is 1-4"))
        assertTrue(error(0, Cell(48, bass, YmRules.FX_LFO, 0x07)).contains("L00 (off) or L08-L0F"))
        assertTrue(error(0, Cell(48, bass, YmRules.FX_BRIGHTNESS, 0x80)).contains("I00-I7F"))
        assertTrue(error(0, Cell(48, bass, YmRules.FX_FEEDBACK, 8)).contains("E00-E07"))
        assertTrue(error(6, Cell(60, psg, YmRules.FX_PAN, 0x11)).contains("FM channels only"))
        assertTrue(error(7, Cell(60, psg, YmRules.FX_FEEDBACK, 1)).contains("FM channels only"))
        assertTrue(error(0, Cell(48, bass, YmRules.FX_NOISE, 0x13)).contains("Noise channel only"))
        assertTrue(error(9, Cell(48, slot("Noise hat"), YmRules.FX_NOISE, 0x24)).contains("x is 0 (periodic) or 1 (white)"))
        assertTrue(error(0, Cell(96, bass)).contains("outside C-0..B-7"))
        assertTrue(error(0, Cell(48, bass, 19, 1)).contains("does not exist on the Mega Drive"))
        // Envelope and register ranges.
        val badEnv = YmSystem.blank().let { s ->
            s.copy(sound = s.ym.withInstrument(1, YmPsgInstrument("x", volume = NesEnvelope(listOf(15, 16)))))
        }
        assertTrue(YmSystem.errors(badEnv).joinToString().contains("volume envelope values must be 0..15"))
        val badRelease = YmSystem.blank().let { s ->
            s.copy(sound = s.ym.withInstrument(1, YmPsgInstrument("x", arpeggio = NesEnvelope(listOf(0, 4, 7), loop = 0, release = 1))))
        }
        assertTrue(YmSystem.errors(badRelease).joinToString().contains("only the volume envelope has a release point"))
        val badSsg = YmSystem.blank().let { s -> s.copy(sound = s.ym.withInstrument(1, fm("Lead").withOperator(0, fm("Lead").operators[0].copy(ssgEg = 16)))) }
        assertTrue(YmSystem.errors(badSsg).joinToString().contains("SSG-EG 16 is outside 0-15"))
        val badLfo = YmSystem.blank().let { s -> s.copy(sound = s.ym.copy(lfo = 3)) }
        assertTrue(YmSystem.errors(badLfo).joinToString().contains("LFO 03 must be 00 (off) or 08-0F"))
    }

    @Test
    fun warningsCoverLocksDelaysPsgRangePitchedNoiseAndHeavyRows() {
        val bass = slot("Slap bass")
        val psg = slot("PSG lead")
        fun warnings(song: Song) = YmSystem.warnings(song).joinToString("\n")
        assertTrue(warnings(blankWith(0, 1, Cell(effect = YmRules.FX_BRIGHTNESS, param = 0x20))).contains("Ixx applies when a note starts"))
        assertTrue(warnings(blankWith(0, 0, Cell(48, bass, YmRules.FX_DELAY, 6))).contains("the row never plays"))
        assertTrue(warnings(blankWith(0, 4, Cell(48))).contains("has no instrument"))
        assertTrue(warnings(blankWith(6, 0, Cell(40, psg))).contains("below the PSG's range"))
        var noise = with(YmSystem.blank(), 0, 9, 0, Cell(36, slot("Buzz bass")))
        noise = with(noise, 0, 8, 2, Cell(60, psg))
        assertTrue(warnings(noise).contains("pitched noise on the Noise channel uses PSG 3's period"))
        assertFalse(warnings(with(noise, 0, 9, 1, Cell(Cell.CUT))).contains("pitched noise"))
        // Six FM channels loading six different instruments on one row (not the song's first).
        var heavy = YmSystem.blank()
        val names = listOf("Slap bass", "Brass", "E-piano", "Bell", "Strings", "Lead")
        for (ch in 0 until 6) heavy = with(heavy, 0, ch, 4, Cell(48, slot(names[ch])))
        assertTrue(warnings(heavy).contains("6 FM instrument changes at once"))
        assertFalse(warnings(with(YmSystem.blank(), 0, 0, 4, Cell(48, bass))).contains("instrument changes"))
    }

    @Test
    fun demoIsCleanAndShowsTheSystem() {
        val demo = YmSystem.demo()
        assertEquals(emptyList<String>(), YmSystem.errors(demo))
        assertEquals(emptyList<String>(), YmSystem.warnings(demo))
        val cells = demo.orders.toSet().flatMap { demo.patterns[it].tracks.flatten() }
        val sound = demo.ym
        assertTrue(cells.any { it.instrument != 0 && sound.instrument(it.instrument)!!.sweep.active })
        assertTrue(cells.any { it.instrument != 0 && sound.instrument(it.instrument) is YmPsgInstrument })
        assertTrue(cells.any { c -> (0..c.more.size).any { c.effectCode(it) == YmRules.FX_PAN } })
        assertTrue(cells.any { c -> (0..c.more.size).any { c.effectCode(it) == YmRules.FX_BRIGHTNESS } })
        assertTrue(cells.any { it.more.isNotEmpty() })
        // Every FM, PSG and Noise channel plays; PCM 1-3 stay empty (FM 6 plays, so the DAC is not taken).
        for (ch in 0 until YmRules.TONAL_CHANNELS) assertTrue("channel $ch", demo.patterns.any { p -> p.tracks[ch].any { it.note >= 0 } })
        for (ch in YmRules.PCM_FIRST until YmRules.CHANNELS) assertTrue("channel $ch", demo.patterns.all { p -> p.tracks[ch].all { it.isBlank } })
        val image = YmLowering.lower(demo)
        val size = (image[YmPlayerImage.H_DATA_SIZE].toInt() and 0xFF) or ((image[YmPlayerImage.H_DATA_SIZE + 1].toInt() and 0xFF) shl 8)
        assertEquals(YmLowering.dataSize(demo), size)
    }

    // ---- song settings, arrangement and exports --------------------------------------------------------

    @Test
    fun songSettingsSwitchRegionAndStepTheLfo() {
        val song = YmSystem.blank()
        val region = YmSongSettings.ALL.single { it.label == "Region" }
        val lfo = YmSongSettings.ALL.single { it.label == "LFO" }
        val pal = region.adjust(song, 1, 0)
        assertTrue(pal.ym.pal)
        assertEquals(1, YmLowering.lower(pal)[YmPlayerImage.H_REGION].toInt())
        assertEquals(song, region.reset(pal))
        val on = lfo.adjust(song, 1, 0)
        assertEquals(0x08, on.ym.lfo)
        assertEquals(0x0F, lfo.adjust(on, 0, 3).ym.lfo)
        assertEquals(0, lfo.adjust(on, -1, 0).ym.lfo)
        assertEquals(0x08, YmLowering.lower(on)[YmPlayerImage.H_LFO].toInt())
        assertTrue(lfo.value(on).contains("3.98 Hz"))
    }

    @Test
    fun arrangementRenumbersJumpsInEveryColumn() {
        val base = YmSystem.blank()
        val patterns = List(3) { Songs.blankPattern(base) }
        var song = base.copy(patterns = patterns, orders = listOf(0, 1, 2), parkedOrders = listOf(1))
        song = with(song, 2, 3, 5, Cell(48, 1).withEffect(2, YmRules.FX_JUMP, 2))
        val arranged = YmSystem.arranged(song)
        assertEquals(listOf(0, 2), arranged.orders)
        assertEquals(1, YmSystem.jumpTarget(arranged.patterns[2].tracks[3][5]))
        val loop = YmSystem.patternLoop(song, 2)
        assertEquals(0, YmSystem.jumpTarget(loop.patterns[2].tracks[3][5]))
    }

    @Test
    fun auditionChecksTheInstrumentKind() {
        val song = YmSystem.blank()
        val engine = YmSystem.engine
        assertNull(engine.auditionProblem(song, 0, 57, slot("Lead"), 0))
        assertNull(engine.auditionProblem(song, 9, 48, slot("Noise hat"), 0))
        assertTrue(engine.auditionProblem(song, 6, 57, slot("Lead"), 0)!!.contains("is an FM voice"))
        assertTrue(engine.auditionProblem(song, 0, 57, slot("PSG lead"), 0)!!.contains("is a PSG sound"))
        assertTrue(engine.auditionProblem(song, 0, 57, 60, 0)!!.contains("is empty"))
        assertEquals(YmPlayerImage.IMAGE_SIZE, engine.imageSize)
        assertEquals("ym2612-fm", engine.id)
    }

    @Test
    fun exportsCallTheNativeRenderer() {
        val demo = YmSystem.demo()
        for (format in YmSystem.exports) assertThrows(IllegalStateException::class.java) { format.export(demo) }
        val seen = ArrayList<Pair<Int, Int>>()
        var last: Pair<ByteArray, YmExportTags>? = null
        YmExport.renderer = YmExportRenderer { image, format, maxTicks, tags -> seen += format to maxTicks; last = image to tags; byteArrayOf(format.toByte()) }
        for ((i, format) in YmSystem.exports.withIndex()) assertArrayEquals(byteArrayOf(i.toByte()), format.export(demo))
        assertEquals(listOf(0 to 36000, 1 to 36000, 2 to 36000), seen)
        assertArrayEquals(YmLowering.lower(demo), last!!.first)
        assertEquals(YmExportTags("Neon Causeway", "Thor Tracker"), last!!.second)
        val pal = demo.copy(sound = demo.ym.copy(pal = true))
        YmSystem.exports[0].export(pal)
        assertEquals(30000, seen.last().second)
    }

    @Test
    fun effectDetailNamesArpeggioNotesAndIgnoredLocks() {
        var song = blankWith(0, 0, Cell(57, 1, YmRules.FX_ARPEGGIO, 0x47))
        song = with(song, 0, 0, 2, Cell(effect = YmRules.FX_ARPEGGIO, param = 0x37))
        song = with(song, 0, 0, 3, Cell(effect = YmRules.FX_BRIGHTNESS, param = 0x40))
        assertEquals("A-4 → C#5 → E-5 every tick", YmSystem.effectDetail(song, 0, 0, 0, 0))
        assertEquals("A-4 → C-5 → E-5 every tick", YmSystem.effectDetail(song, 0, 0, 2, 0))
        assertTrue(YmSystem.effectDetail(song, 0, 0, 3, 0)!!.contains("ignored"))
        assertNull(YmSystem.effectDetail(song, 0, 0, 1, 0))
    }

    @Test
    fun soundSerializerIsTheSystems() {
        assertEquals(YmSound.serializer(), YmSystem.soundSerializer)
        assertEquals(YmSystem, YmSystem.blank().sound.system)
    }
}
